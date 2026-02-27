/*
 * ObjectBox Build Tools
 * Copyright (C) 2025 ObjectBox Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.objectbox.gradle.transform

import io.objectbox.logging.log
import javassist.CtClass
import javassist.CtField
import javassist.Modifier
import javassist.NotFoundException
import javassist.bytecode.AccessFlag
import javassist.bytecode.FieldInfo
import javassist.bytecode.annotation.StringMemberValue

/**
 * Transform support for `@Embedded` component mapping. Kept in its own unit because — unlike
 * relations (ToOne/ToMany) where the container type IS the signal and the transform is uniform
 * (constructor init + BoxStore field) — embedded requires re-deriving per-container metadata
 * from the `@Embedded` annotation's attributes + the container POJO's field list. That
 * re-derivation has to match **exactly** what the annotation processor did when it synthesised
 * Property entries into `Entity_`, so the naming rules below are a deliberate duplication of
 * the APT's `parseEmbedded()` logic. The cross-validation against `Entity_` field names is the
 * safety net that catches drift between the two.
 *
 * Two-phase transform across [ClassTransformer]'s existing entity→cursor pipeline:
 *
 *   **Phase A (entity)** — [discoverEmbeddedContainers] + [injectEmbeddedSyntheticFields].
 *   Called from `transformEntity()`. Walks `@Embedded` fields, loads each container POJO
 *   on-demand from the probed-class set (these are NOT pre-loaded — they're neither `isEntity`
 *   nor `isEntityInfo`), enumerates non-static non-transient inner fields, applies the prefix
 *   rule to compute synthetic flat-field names, cross-validates each name against `Entity_`,
 *   injects `transient <type> <name>;` fields onto the entity. Returns the discovered
 *   [EmbeddedContainer] metadata, which the caller stashes on the Context for phase B.
 *
 *   **Phase B (cursor)** — [buildAttachEmbeddedBody].
 *   Called from `transformCursor()`. Looks up phase-A metadata by entity FQN (extracted from
 *   the `attachEmbedded(L<Entity>;)V` signature), builds a Javassist source-string body
 *   (null-guard + per-container instantiate-copy-assign blocks) and hands it back for
 *   `CtMethod.setBody()`.
 */
// Not `internal` — ClassTransformer.Context (which is effectively public, see the "// Use internal
// once fixed" TODO there) exposes `embeddedContainersByEntity` typed by the nested data classes
// below. Kotlin refuses to let a public member expose an internal type. Narrowing Context's own
// visibility would be the cleaner fix, but that's out of scope for the @Embedded feature.
object EmbeddedTransform {

    /**
     * Per-container metadata bridge between phase A (entity transform) and phase B (cursor
     * transform). Lives in `ClassTransformer.Context`, keyed by entity FQN. Ephemeral — only
     * valid for a single `transformOrCopyClasses()` invocation.
     *
     * For nested `@Embedded` chains (container field annotated `@Embedded` inside ANOTHER
     * container), each level produces one `EmbeddedContainer` instance — they form a flat list
     * (parent-before-child — hydration order) with parent-linkage via [parent]. Each
     * instance's [syntheticFields] holds only its DIRECT scalar leaves, not transitively-nested
     * ones; nested leaves belong to the nested container's own entry. This keeps the cursor body
     * builder's per-container loop uniform: it hydrates one POJO's direct fields + assigns it
     * to its target, same whether root or nested.
     */
    data class EmbeddedContainer(
        /** Field name on the OWNING level, e.g. `price` (root) or `cur` (nested inside Money). */
        val fieldName: String,
        /** FQN of the container POJO type, e.g. `com.example.Money`. Used in `new <this>()`. */
        val typeFqn: String,
        /** DIRECT leaf inner-fields only. Nested-container inner-fields get their OWN entry. */
        val syntheticFields: List<SyntheticField>,
        /**
         * Parent container for nested chains, null for root. Used by [assignmentTargetExpression]
         * to walk the path back to `$1` (the entity). Mirrors `EmbeddedField.parent` on the
         * generator-model side — same design, same reason: flat list + parent-ref beats nested
         * lists when the consumer iterates flat (which the body builder does).
         */
        val parent: EmbeddedContainer? = null,
    ) {
        /**
         * The Javassist-source LHS expression to assign this container's hydrated instance to.
         *
         *   Root:   `$1.price`           (direct entity field)
         *   Nested: `$1.price.cur`       (via parent chain — PARENT'S target + `.fieldName`)
         *   Depth-3: `$1.a.b.c`          (fully general)
         *
         * Why this works without an NPE at hydration time: the discovery walk emits containers
         * PARENT-BEFORE-CHILD (depth-first, see [discoverOne]'s `listOf(self) + nestedDesc`),
         * and [buildAttachEmbeddedBody] iterates that list in order. So by the time the nested
         * container's block executes, its parent's block has already run `$1.price = __emb;` —
         * guaranteeing `$1.price` is non-null when `$1.price.cur = __emb;` dereferences it.
         *
         * `\$1` is the Kotlin-escaped literal for Javassist's `$1` first-param placeholder.
         */
        fun assignmentTargetExpression(): String =
            (parent?.assignmentTargetExpression() ?: "\$1") + "." + fieldName
    }

    /**
     * One synthetic flat-field to inject onto the entity + its hydration target in the container.
     */
    data class SyntheticField(
        /** Synthetic name on the entity, e.g. `priceCurrency`. Matches a `Property` in `Entity_`. */
        val name: String,
        /** Name of the inner field on the container POJO, e.g. `currency`. */
        val innerFieldName: String,
        /**
         * Java-source type name for `CtField.make("transient <this> <name>;", ...)`.
         * Primitives are keywords (`long`, `int`), references are FQNs (`java.lang.String`).
         */
        val javaType: String,
    ) {
        /**
         * True for reference-typed fields (can hold null → usable as null-detection signal),
         * false for the eight JVM primitives (can't be null → ambiguous between "DB was NULL"
         * and "DB stored the zero-value"). Arrays are reference types → true.
         *
         * Consumed by the hydration-guard builder: only object-typed synthetics participate in
         * the `if ($1.x != null || ...)` predicate that decides hydrate-vs-nullify. See
         * [buildAttachEmbeddedBody] AT4 block.
         */
        val isObjectType: Boolean
            get() = javaType !in JVM_PRIMITIVE_KEYWORDS

        companion object {
            // Frozen set — the JLS eight. `void` can't appear on a field so excluded.
            private val JVM_PRIMITIVE_KEYWORDS = hashSetOf(
                "boolean", "byte", "char", "short", "int", "long", "float", "double",
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Phase A — entity
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Walks `@Embedded`-annotated fields on [ctClass], loading container types on-demand from
     * [context]'s probed-class set, and computes synthetic flat-field metadata. Validates each
     * computed synthetic name against the `Entity_` class (REQUIRED, must be pre-loaded into
     * the pool by the caller's `makeCtClass` pass).
     *
     * Recursively descends into nested `@Embedded` fields on container POJOs, compounding
     * prefixes at each level. Returns a FLAT list of all containers (root and nested), ordered
     * depth-first PARENT-BEFORE-CHILD — this ordering is a contract that
     * [buildAttachEmbeddedBody] relies on (parent must be hydrated+assigned before nested derefs
     * it via `$1.price.cur = ...`).
     *
     * Does NOT mutate [ctClass] — that's [injectEmbeddedSyntheticFields]'s job. Split so the
     * metadata can be stashed on the Context even if injection is skipped (e.g. re-run on an
     * already-transformed class where fields already exist).
     *
     * @throws TransformException on missing `Entity_`, missing container POJO, name mismatch,
     *   or `@Embedded` type cycle (A embeds B embeds A, or A embeds A).
     */
    fun discoverEmbeddedContainers(
        context: ClassTransformer.Context,
        ctClass: CtClass,
        debug: Boolean,
    ): List<EmbeddedContainer> {
        val entityInfoCtClass = requireEntityInfoInPool(context, ctClass)

        // Top level: walk entity fields, dispatch each @Embedded-annotated one into the
        // recursive walker. Each root can yield MULTIPLE containers (self + nested descendants),
        // hence flatMap.
        return ctClass.declaredFields
            .asSequence()
            .mapNotNull { field ->
                val ann = field.fieldInfo.exGetAnnotation(ClassConst.embeddedAnnotationName) ?: return@mapNotNull null
                field to ann
            }
            .flatMap { (field, ann) ->
                discoverOne(
                    context = context,
                    entityInfoCtClass = entityInfoCtClass,
                    ownerFqn = ctClass.name,
                    field = field.fieldInfo,
                    embeddedAnn = ann,
                    parentEffectivePrefix = "",   // no parent → local prefix IS the effective prefix
                    parentContainer = null,       // root level
                    visitedTypes = emptySet(),    // fresh cycle-detection path per root
                    debug = debug,
                ).asSequence()
            }
            .toList()
    }

    /**
     * Recursive discovery for ONE `@Embedded` field (root or nested). Returns self +
     * all nested descendants, parent-first.
     *
     * Algorithm (mirrors the APT's recursive `parseEmbedded()`):
     *   1. Resolve this level's LOCAL prefix (USE_FIELD_NAME → field name; else literal).
     *   2. COMPOUND with parent's effective prefix → this level's effective prefix.
     *   3. Load container type; cycle-check its FQN against the visited-path set.
     *   4. Partition inner fields: `@Embedded` inner fields become PENDING nested containers
     *      (recursed AFTER self is constructed); scalar inner fields become DIRECT leaf
     *      [SyntheticField]s with compound-prefixed names.
     *   5. Construct SELF with direct leaves; pass SELF as `parent` into each pending recurse.
     *   6. Return `[self] + allNestedDescendants` — parent-first ordering contract.
     *
     * The construct-self-before-recurse sequencing is important: nested containers need a
     * parent ref, but we need the DIRECT-leaf list (which excludes nested-container inner
     * fields) to construct self. Solution: collect pending nesteds during the inner-field walk,
     * then recurse into them AFTER self is built. Nested descendants never mutate self's
     * synthetics list — their leaves belong to their own `EmbeddedContainer` entry.
     *
     * @param ownerFqn FQN of whatever DECLARES [field] — the entity for root, the parent
     *   container type for nested. Used only for error messages (so a user sees the full
     *   `Owner.field` path in diagnostics).
     * @param parentEffectivePrefix Already-compounded prefix from all ancestor levels. Empty
     *   string at root (NOT null — we've concretized USE_FIELD_NAME sentinel already).
     * @param visitedTypes Set of container-type FQNs on the CURRENT descent path. Passed as an
     *   immutable copy at each recurse (`visitedTypes + thisTypeFqn`) so sibling branches get
     *   the same parent view — no mutable add/remove bookkeeping. Siblings of the same type
     *   (e.g. `@Embedded Money m1; @Embedded Money m2;` inside one container) are legal and
     *   NOT a cycle — only ancestor reappearance is.
     */
    private fun discoverOne(
        context: ClassTransformer.Context,
        entityInfoCtClass: CtClass,
        ownerFqn: String,
        field: FieldInfo,
        embeddedAnn: javassist.bytecode.annotation.Annotation,
        parentEffectivePrefix: String,
        parentContainer: EmbeddedContainer?,
        visitedTypes: Set<String>,
        debug: Boolean,
    ): List<EmbeddedContainer> {
        // ── 1 + 2. Prefix resolution + compounding ──────────────────────────────────────────
        // Local prefix: USE_FIELD_NAME sentinel → field name; else the literal annotation value.
        // Effective prefix: parent's effective prefix compounded with our local.
        //   parent "" + local "price" → "price"          (root, default)
        //   parent "price" + local "cur" → "priceCur"    (nested, default)
        //   parent "price" + local "" → "price"          (nested explicit-empty: inherits parent)
        //   parent "" + local "" → ""                    (both empty → bare inner names)
        // This MUST match the APT's `parentEmbedded?.syntheticNameFor(localPrefix) ?: localPrefix`
        // exactly — the Entity_ cross-validation below catches drift.
        val localPrefix = resolveLocalPrefix(embeddedAnn, field.name)
        val effectivePrefix = compoundPrefix(parentEffectivePrefix, localPrefix)

        // ── 3. Container type + cycle check ─────────────────────────────────────────────────
        val containerTypeFqn = descriptorToJavaType(field.descriptor)
        if (containerTypeFqn in visitedTypes) {
            // The APT would have caught this first (and blocked the build), so hitting this
            // in practice means either an APT bypass or a version skew. Fail loud anyway —
            // infinite recursion here would OOM the Gradle daemon which is a worse UX than
            // a clear error message.
            throw TransformException(
                "@Embedded cycle detected in bytecode transform: '$containerTypeFqn' embeds itself " +
                    "(directly or transitively) via '$ownerFqn.${field.name}'. The annotation " +
                    "processor should have rejected this — check for stale build outputs."
            )
        }
        val containerCtClass = loadContainerType(context, containerTypeFqn, ownerFqn, field.name)

        if (debug) {
            val depth = generateSequence(parentContainer) { it.parent }.count()
            log("@Embedded${"  ".repeat(depth)} $ownerFqn.${field.name}: container=$containerTypeFqn effectivePrefix='$effectivePrefix'")
        }

        // ── 4. Inner-field walk: partition into direct-leaves vs pending-nesteds ────────────
        // We can't recurse IN-LOOP because nested containers need `self` as their parent ref,
        // and `self` isn't constructed until we have the full direct-leaf list. So: collect
        // pending nesteds (field + annotation pair — need both for the recurse call), finish
        // the leaf walk, construct self, THEN recurse into pendings with self as parent.
        val directLeaves = mutableListOf<SyntheticField>()
        val pendingNested = mutableListOf<Pair<FieldInfo, javassist.bytecode.annotation.Annotation>>()

        for (inner in enumerateInnerFields(containerCtClass)) {
            val nestedAnn = inner.exGetAnnotation(ClassConst.embeddedAnnotationName)
            if (nestedAnn != null) {
                // Nested @Embedded — defer recursion. NOT a leaf; produces no SyntheticField on
                // THIS container. Its leaves will be on its OWN EmbeddedContainer entry.
                pendingNested += inner to nestedAnn
            } else {
                // Scalar leaf — flatten with the compound effective prefix.
                val synthName = syntheticNameFor(effectivePrefix, inner.name)
                validateSyntheticNameInEntityInfo(
                    entityInfoCtClass, synthName, ownerFqn, field.name, inner.name
                )
                directLeaves += SyntheticField(
                    name = synthName,
                    innerFieldName = inner.name,
                    javaType = descriptorToJavaType(inner.descriptor),
                )
            }
        }

        // ── 5. Construct self; it's safe to use as a parent ref now (nested containers only
        //        read parent.fieldName + parent.parent for the assignment chain — neither cares
        //        about direct-leaf contents). ────────────────────────────────────────────────
        val self = EmbeddedContainer(
            fieldName = field.name,
            typeFqn = containerTypeFqn,
            syntheticFields = directLeaves,
            parent = parentContainer,
        )

        // ── 6. Recurse into pendings with self as parent; concat self-first for ordering ────
        val nestedDescendants = pendingNested.flatMap { (innerField, innerAnn) ->
            discoverOne(
                context = context,
                entityInfoCtClass = entityInfoCtClass,
                ownerFqn = containerTypeFqn,   // diagnostics: owner of the NESTED field is THIS container type
                field = innerField,
                embeddedAnn = innerAnn,
                parentEffectivePrefix = effectivePrefix,
                parentContainer = self,
                visitedTypes = visitedTypes + containerTypeFqn, // extend path for cycle check
                debug = debug,
            )
        }

        // Parent-first ordering — see EmbeddedContainer.assignmentTargetExpression() kdoc
        // for why hydration depends on this.
        return listOf(self) + nestedDescendants
    }

    /**
     * Injects `transient <javaType> <name>;` fields onto [ctClass] for every synthetic in
     * [containers], skipping any that already exist (idempotent re-runs must not duplicate).
     * Returns the number of fields actually added.
     */
    fun injectEmbeddedSyntheticFields(
        ctClass: CtClass,
        containers: List<EmbeddedContainer>,
        debug: Boolean,
    ): Int {
        var added = 0
        // Snapshot BEFORE we start adding — otherwise we'd see our own just-added fields in
        // the existence check, which is harmless but wastes a few iterations.
        val existing = ctClass.declaredFields.mapTo(hashSetOf()) { it.name }

        containers.asSequence()
            .flatMap { it.syntheticFields.asSequence() }
            .filterNot { existing.contains(it.name) }
            .forEach { sf ->
                // CtField.make parses a Java-source declaration. The type MUST be resolvable
                // in the class pool — primitives are built-in CtClass instances, java.* is on
                // the path via PrefixedClassPath. User-defined inner types (if APT ever permits
                // them) would need the same on-demand loading as container types; for now APT
                // restricts inner fields to leaf types, so this is not a concern.
                val decl = "transient ${sf.javaType} ${sf.name};"
                if (debug) log("  + injecting synthetic flat field: $decl")
                ctClass.addField(CtField.make(decl, ctClass))
                added++
            }
        return added
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Phase B — cursor
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the `attachEmbedded()` method body as a Javassist source-string. The caller feeds
     * this to `CtMethod.setBody()`, which REPLACES the 1-byte-RETURN stub emitted by the
     * annotation processor's `cursor.ftl`.
     *
     * Generated shape (for two containers `price: Money{currency, amount}` and `addr: Addr{zip}`):
     *
     * ```java
     * {
     *     if ($1 == null) return;
     *     {
     *         com.example.Money __emb = new com.example.Money();
     *         __emb.currency = $1.priceCurrency;
     *         __emb.amount   = $1.priceAmount;
     *         $1.price = __emb;
     *     }
     *     {
     *         com.example.Addr __emb = new com.example.Addr();
     *         __emb.zip = $1.addrZip;
     *         $1.addr = __emb;
     *     }
     * }
     * ```
     *
     * Design notes:
     *  - **Null-detection (AT4)**: if the container was saved as null, the write path skipped
     *    every flat property (collect ID = 0) → DB stores nothing → on read the native layer
     *    leaves object-typed synthetic fields as null and primitive ones as zero. We use the
     *    object-typed fields as the null-signal: if EVERY object-typed synthetic is null, the
     *    container is presumed null and we set it to null explicitly; otherwise we hydrate.
     *    Primitives are EXCLUDED from the guard — they're ambiguous (zero could mean "DB was
     *    NULL" or "user stored zero"). Consequence: an all-primitive container cannot null-detect
     *    and ALWAYS hydrates; users needing null round-trip on such containers should use wrapper
     *    types (`Integer` instead of `int`) for at least one field.
     *  - **Nested parent-guard**: a nested container's block is preceded by
     *    `if (<parentTarget> != null)` — if the parent was nullified by ITS guard, the nested
     *    block must skip entirely (both hydrate AND nullify would NPE on `$1.price.cur`). The
     *    parent-first iteration order guarantees the parent's block has already run by the
     *    time the nested block's guard checks the parent target.
     *  - **Build-into-local-then-assign**: `__emb.x = ...; $1.price = __emb;` (not
     *    `$1.price.x = ...` directly). One less branch per inner field; the local-variable
     *    approach dodges the need to null-check `$1.price` before each inner assignment.
     *  - **Per-container `{}` scopes** let every container's local share the name `__emb`
     *    without collision — simpler than suffixing (`__emb0`, `__emb1`).
     *  - **Direct field access** (`__emb.currency`) not setters — matches the APT's write-path
     *    which also uses direct access. If APT switches to getter/setter dispatch, both halves
     *    change together.
     *
     * Generated shape for `price: Money{currency: String, amount: long}` (canonical AT4 case):
     * ```java
     * {
     *     if ($1 == null) return;
     *     {
     *         if ($1.priceCurrency != null) {
     *             com.example.Money __emb = new com.example.Money();
     *             __emb.currency = $1.priceCurrency;
     *             __emb.amount   = $1.priceAmount;
     *             $1.price = __emb;
     *         } else {
     *             $1.price = null;
     *         }
     *     }
     * }
     * ```
     *
     * For a nested chain `price: Money{amount: long, @Embedded cur: Currency{code: String, ...}}`:
     * ```java
     * {
     *     if ($1 == null) return;
     *     { /* Money: primitive-only → always-hydrate, no guard */
     *         com.example.Money __emb = new com.example.Money();
     *         __emb.amount = $1.priceAmount;
     *         $1.price = __emb;
     *     }
     *     if ($1.price != null) { /* nested: parent-guard first */
     *         if ($1.priceCurCode != null) {
     *             com.example.Currency __emb = new com.example.Currency();
     *             __emb.code = $1.priceCurCode;
     *             /* ... */
     *             $1.price.cur = __emb;
     *         } else {
     *             $1.price.cur = null;
     *         }
     *     }
     * }
     * ```
     */
    fun buildAttachEmbeddedBody(containers: List<EmbeddedContainer>): String = buildString {
        append("{")
        append("if ($1 == null) return;")
        containers.forEach { c ->
            val target = c.assignmentTargetExpression()  // `$1.price` or `$1.price.cur` etc.

            // ── Nested: guard on parent non-null. ──────────────────────────────────────────
            // If parent was nullified by its own hydration guard, dereferencing it here (for
            // either the hydrate assignment OR the else-nullify) would NPE. Wrap the entire
            // per-container block in `if (parentTarget != null)`. For root containers there's
            // no parent → no wrapper (the top-level `$1 == null` check already covers it).
            //
            // We emit this guard unconditionally for nested containers, even when the parent
            // is known to always-hydrate (primitive-only) and the guard is therefore never
            // false at runtime. The alternative — checking at build time whether the parent
            // can nullify — saves one branch but couples the nested block's shape to the
            // parent's field-type mix. Not worth the complexity; the JIT will drop the
            // always-true branch anyway.
            val parentTarget = c.parent?.assignmentTargetExpression()
            if (parentTarget != null) append("if ($parentTarget != null) ")

            append("{")

            // ── Hydration guard: OR-join all object-typed synthetics. ──────────────────────
            // Null if no object-typed synthetics exist (all-primitive container) — that's the
            // "can't detect, always hydrate" fallback.
            val objectSynthetics = c.syntheticFields.filter { it.isObjectType }
            val hydrationGuard = if (objectSynthetics.isEmpty()) null
                else objectSynthetics.joinToString(" || ") { "\$1.${it.name} != null" }

            if (hydrationGuard != null) append("if ($hydrationGuard) {")

            // ── Hydrate block (unchanged from always-hydrate impl). ────────────────────────
            append("${c.typeFqn} __emb = new ${c.typeFqn}();")
            c.syntheticFields.forEach { sf ->
                append("__emb.${sf.innerFieldName} = \$1.${sf.name};")
            }
            append("$target = __emb;")

            // ── Else: explicitly nullify. ──────────────────────────────────────────────────
            // Without this, the user's constructor-default (if any) would survive post-read,
            // breaking AT4 round-trip (null-saved → null-read).
            if (hydrationGuard != null) append("} else {$target = null;}")

            append("}")
        }
        append("}")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Converts a raw JVM field descriptor to a Java-source type name usable in `CtField.make()`.
     * Handles primitives (`J` → `long`), reference types (`Ljava/lang/String;` → `java.lang.String`),
     * and arrays (`[I` → `int[]`). Avoids `CtField.getType()` which triggers class-pool resolution —
     * we want a pure string transformation so the inspect/test path doesn't need every user type
     * on the pool's classpath.
     */
    @JvmStatic // keeps the `when` map a single bytecode tableswitch, trivially testable
    fun descriptorToJavaType(descriptor: String): String = when (descriptor) {
        "Z" -> "boolean"
        "B" -> "byte"
        "C" -> "char"
        "S" -> "short"
        "I" -> "int"
        "J" -> "long"
        "F" -> "float"
        "D" -> "double"
        "V" -> "void" // shouldn't appear on a field but harmless to handle
        else -> when {
            descriptor.startsWith("L") && descriptor.endsWith(";") ->
                descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            descriptor.startsWith("[") ->
                descriptorToJavaType(descriptor.substring(1)) + "[]"
            else ->
                throw TransformException("Unsupported field descriptor: '$descriptor'")
        }
    }

    // ── Prefix helpers ──────────────────────────────────────────────────────────────────────
    // The single-level impl used a String?-returning resolver (null = USE_FIELD_NAME signal) +
    // a 3-branch name-apply. That doesn't compose for nesting — you can't cleanly chain "null"
    // through a compound. The refactored trio below instead CONCRETIZES the sentinel early
    // (USE_FIELD_NAME → field name) so every downstream helper works in plain strings, and
    // compounding reduces to the same `syntheticNameFor` rule the APT's `EmbeddedField` uses.
    //
    // The THREE helpers are intentionally all the same shape: `"" → identity, else prefix +
    // capFirst`. That's not coincidence — compound-prefix and leaf-name-derivation are the
    // SAME operation applied at different levels (parent-to-child vs container-to-leaf). The
    // APT's `EmbeddedField.syntheticNameFor` is used for both, and we mirror that symmetry.

    /**
     * Resolves `@Embedded.prefix` to a CONCRETE prefix string — no null-signaling.
     *
     * Bytecode-level subtlety preserved from the old helper: `@Embedded` with NO explicit
     * `prefix` omits the member from the annotation's attribute bytes (`getMemberValue`
     * returns null). `@Embedded(prefix="\0")` (explicit sentinel) includes it with value
     * `"\0"`. Both → USE_FIELD_NAME → return [fieldName]. `@Embedded(prefix="")` includes
     * it with value `""` → return `""` (real empty prefix, distinct from the sentinel).
     */
    private fun resolveLocalPrefix(
        embeddedAnn: javassist.bytecode.annotation.Annotation,
        fieldName: String,
    ): String {
        val rawPrefix = (embeddedAnn.getMemberValue("prefix") as? StringMemberValue)?.value
        return if (rawPrefix == null || rawPrefix == ClassConst.embeddedPrefixUseFieldName) {
            fieldName  // USE_FIELD_NAME sentinel → concretize to the field name
        } else {
            rawPrefix  // "" or explicit "x" — literal
        }
    }

    /**
     * Compounds a child's local prefix under a parent's effective prefix.
     * Mirrors the APT's `parentEmbedded.syntheticNameFor(localPrefix)` — the empty-parent
     * short-circuit makes all four empty/non-empty parent×child combos work:
     *   `compound("", "price") → "price"`, `compound("price", "cur") → "priceCur"`,
     *   `compound("price", "") → "price"`, `compound("", "") → ""`.
     */
    private fun compoundPrefix(parentEffective: String, childLocal: String): String =
        if (parentEffective.isEmpty()) childLocal
        else if (childLocal.isEmpty()) parentEffective
        else parentEffective + capFirst(childLocal)

    /**
     * Final synthetic flat-field name from an effective prefix + a leaf's inner-field name.
     * MUST match the APT's `EmbeddedField.syntheticNameFor()` exactly — cross-validated against
     * `Entity_` so drift is a build failure, not a silent runtime mismatch.
     */
    private fun syntheticNameFor(effectivePrefix: String, innerFieldName: String): String =
        if (effectivePrefix.isEmpty()) innerFieldName
        else effectivePrefix + capFirst(innerFieldName)

    // Character.toUpperCase rather than Char.uppercaseChar() — the latter is @ExperimentalStdlibApi
    // at Kotlin API level 1.4 (which this module is pinned to; see the build.gradle apiVersion).
    private fun capFirst(s: String): String =
        if (s.isEmpty() || Character.isUpperCase(s[0])) s else Character.toUpperCase(s[0]) + s.substring(1)

    /**
     * Enumerates container inner fields that should be flattened. Filters out:
     *  - static (e.g. Kotlin `Companion` holder, constants)
     *  - transient (explicitly user-opted-out of persistence)
     *  - synthetic (compiler-generated — `$` in name or ACC_SYNTHETIC flag)
     *
     * Returns raw [FieldInfo] so the caller can read both `.name` and `.descriptor` without
     * triggering `CtField.getType()` resolution.
     */
    private fun enumerateInnerFields(containerCtClass: CtClass): List<FieldInfo> {
        @Suppress("UNCHECKED_CAST")
        val raw = containerCtClass.classFile.fields as List<FieldInfo>
        return raw.filter { fi ->
            val mods = AccessFlag.toModifier(fi.accessFlags)
            !Modifier.isStatic(mods)
                && !Modifier.isTransient(mods)
                // ACC_SYNTHETIC (0x1000) — compiler-generated fields. Not exposed via Modifier.*
                // so check the raw flag. Also belt-and-suspenders on the `$` convention.
                && (fi.accessFlags and 0x1000) == 0
                && !fi.name.contains('$')
        }
    }

    private fun requireEntityInfoInPool(context: ClassTransformer.Context, ctClass: CtClass): CtClass {
        val entityInfoName = ctClass.name + '_'
        return try {
            context.classPool.get(entityInfoName)
        } catch (e: NotFoundException) {
            throw TransformException(
                "@Embedded on '${ctClass.name}' requires generated EntityInfo class '$entityInfoName' " +
                    "for cross-validation, but it was not found in the class pool. " +
                    "Ensure ObjectBox annotation processing ran before the bytecode transform.",
                e,
            )
        }
    }

    /**
     * Loads the embedded container POJO's `CtClass`, preferring the already-pooled version
     * (idempotent) but falling back to on-demand loading from the probed-class set.
     *
     * The container POJO is NOT an `@Entity` and NOT an `EntityInfo`, so the transformer's
     * pre-load pass at the top of `transformOrCopyClasses()` skips it. But it IS in the
     * probed-class list (every `.class` file in the build output is probed). So we find it
     * by FQN and load its bytes directly.
     *
     * Cross-module container types (POJO lives in a dependency jar, not this module's output)
     * are NOT supported — the probed-class set only covers this module. The error message
     * calls this out explicitly so users know to co-locate the POJO.
     */
    private fun loadContainerType(
        context: ClassTransformer.Context,
        containerTypeFqn: String,
        entityFqn: String,
        entityFieldName: String,
    ): CtClass {
        // Fast path: already in pool (another embedded field in this or a prior entity used it).
        context.classPool.getOrNull(containerTypeFqn)?.let { return it }

        // Slow path: find the probed class, load its bytes.
        val probed = context.probedClasses.find { it.name == containerTypeFqn }
            ?: throw TransformException(
                "@Embedded container type '$containerTypeFqn' (field '$entityFqn.$entityFieldName') " +
                    "was not found in the transformer's class set. Embedded container types must be " +
                    "compiled in the same module as the entity that embeds them."
            )
        return probed.file.inputStream().use { context.classPool.makeClass(it) }
    }

    /** Cross-validation: computed synthetic name must match a field on `Entity_`. */
    private fun validateSyntheticNameInEntityInfo(
        entityInfoCtClass: CtClass,
        syntheticName: String,
        entityFqn: String,
        containerFieldName: String,
        innerFieldName: String,
    ) {
        val found = entityInfoCtClass.declaredFields.any { it.name == syntheticName }
        if (!found) {
            throw TransformException(
                "@Embedded naming mismatch: transformer computed synthetic property name '$syntheticName' " +
                    "for '$entityFqn.$containerFieldName.$innerFieldName', but no matching field exists " +
                    "on generated '${entityInfoCtClass.name}'. The annotation processor and the bytecode " +
                    "transformer disagree on the prefix/naming rule — likely a version skew or a " +
                    "build-cache artifact. Clean and rebuild; if the error persists, report a bug."
            )
        }
    }
}
