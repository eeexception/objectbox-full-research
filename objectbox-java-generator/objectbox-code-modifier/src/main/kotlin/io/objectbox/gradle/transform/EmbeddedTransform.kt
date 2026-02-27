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
     */
    data class EmbeddedContainer(
        /** Entity-side field name, e.g. `price`. The cursor body assigns to `$1.<this>`. */
        val fieldName: String,
        /** FQN of the container POJO type, e.g. `com.example.Money`. Used in `new <this>()`. */
        val typeFqn: String,
        /** Inner fields to flatten. Order matches declaration order in the POJO's bytecode. */
        val syntheticFields: List<SyntheticField>,
    )

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
    )

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Phase A — entity
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Walks `@Embedded`-annotated fields on [ctClass], loading container types on-demand from
     * [context]'s probed-class set, and computes synthetic flat-field metadata. Validates each
     * computed synthetic name against [entityInfoCtClass] (the `Entity_` class — REQUIRED, must
     * be pre-loaded into the pool by the caller's `makeCtClass` pass).
     *
     * Does NOT mutate [ctClass] — that's [injectEmbeddedSyntheticFields]'s job. Split so the
     * metadata can be stashed on the Context even if injection is skipped (e.g. re-run on an
     * already-transformed class where fields already exist).
     *
     * @throws TransformException on missing `Entity_`, missing container POJO, or name mismatch.
     */
    fun discoverEmbeddedContainers(
        context: ClassTransformer.Context,
        ctClass: CtClass,
        debug: Boolean,
    ): List<EmbeddedContainer> {
        val entityInfoCtClass = requireEntityInfoInPool(context, ctClass)

        return ctClass.declaredFields
            .mapNotNull { field ->
                val ann = field.fieldInfo.exGetAnnotation(ClassConst.embeddedAnnotationName) ?: return@mapNotNull null
                // Not filtering on transient/static here — APT would have rejected those
                // configurations already. If the bytecode somehow has `@Embedded transient`,
                // we still want to process it (the transient modifier on the CONTAINER doesn't
                // affect flat-field injection; it only affects Java serialization of the POJO ref).

                val containerTypeFqn = descriptorToJavaType(field.fieldInfo.descriptor)
                val containerCtClass = loadContainerType(context, containerTypeFqn, ctClass.name, field.name)
                val resolvedPrefix = resolvePrefix(ann, field.name)

                if (debug) log("@Embedded ${ctClass.simpleName}.${field.name}: container=$containerTypeFqn prefix='${resolvedPrefix ?: "<field-name>"}'")

                val synthetics = enumerateInnerFields(containerCtClass)
                    .map { inner ->
                        val synthName = applySyntheticName(resolvedPrefix, field.name, inner.name)
                        validateSyntheticNameInEntityInfo(entityInfoCtClass, synthName, ctClass.name, field.name, inner.name)
                        SyntheticField(
                            name = synthName,
                            innerFieldName = inner.name,
                            javaType = descriptorToJavaType(inner.descriptor),
                        )
                    }

                EmbeddedContainer(
                    fieldName = field.name,
                    typeFqn = containerTypeFqn,
                    syntheticFields = synthetics,
                )
            }
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
     *  - Always-hydrate: the container is never null post-read, even if every DB column was NULL
     *    (all inner fields then hold their Java defaults). Matches JPA `@Embedded` semantics and
     *    avoids brittle was-this-zero-or-default? heuristics.
     *  - Build-into-local-then-assign: `__emb.x = ...; $1.price = __emb;` (not `$1.price.x = ...`
     *    directly). This dodges the null-check on `$1.price` — the entity's container field is
     *    whatever the user's constructor/initializer left it (often null), and we're about to
     *    overwrite it anyway. The local-variable approach is one less branch per inner field.
     *  - Per-container `{}` scopes let every container's local share the name `__emb` without
     *    collision — simpler than suffixing (`__emb0`, `__emb1`).
     *  - Direct field access (`__emb.currency`) not setters — matches the APT's write-path which
     *    also uses direct access via the hoisted `__emb_price.currency`. If APT ever switches to
     *    getter/setter dispatch for embedded, both halves change together.
     */
    fun buildAttachEmbeddedBody(containers: List<EmbeddedContainer>): String = buildString {
        append("{")
        append("if ($1 == null) return;")
        containers.forEach { c ->
            append("{")
            append("${c.typeFqn} __emb = new ${c.typeFqn}();")
            c.syntheticFields.forEach { sf ->
                append("__emb.${sf.innerFieldName} = \$1.${sf.name};")
            }
            append("\$1.${c.fieldName} = __emb;")
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

    /**
     * Resolves the `@Embedded.prefix` attribute to a concrete prefix string, or `null` to signal
     * "derive from field name" (the `USE_FIELD_NAME` sentinel behaviour).
     *
     * Bytecode-level subtlety: `@Embedded` with NO explicit `prefix` omits the member from the
     * annotation's attribute bytes entirely — `getMemberValue("prefix")` returns null. `@Embedded(
     * prefix="\0")` (the sentinel, spelled out) includes it with value `"\0"`. Both mean the same
     * thing: use the field name. `@Embedded(prefix="")` includes it with value `""` — a REAL
     * empty prefix, distinct from the sentinel.
     *
     * Returns:
     *  - `null` → caller should use the entity field name as prefix (e.g. `price` → `priceCurrency`)
     *  - `""`   → no prefix: bare inner names (e.g. `currency`)
     *  - `"x"`  → literal prefix (e.g. `xCurrency`)
     */
    private fun resolvePrefix(
        embeddedAnn: javassist.bytecode.annotation.Annotation,
        @Suppress("UNUSED_PARAMETER") fieldName: String, // kept for symmetry with APT, may need it for diagnostics
    ): String? {
        val rawPrefix = (embeddedAnn.getMemberValue("prefix") as? StringMemberValue)?.value
        return when {
            // Member absent OR explicit sentinel → "derive from field name" signal.
            rawPrefix == null || rawPrefix == ClassConst.embeddedPrefixUseFieldName -> null
            // Empty or any other literal → use as-is. Caller handles the "" case in applySyntheticName.
            else -> rawPrefix
        }
    }

    /**
     * Computes the synthetic flat-field name. MUST match the APT's `parseEmbedded()` exactly —
     * cross-validated against `Entity_` downstream, so a drift here manifests as a build failure
     * (which is the intended fail-loud behaviour, not a silent runtime mismatch).
     *
     * Rules (mirroring `objectbox-processor/Properties.kt:parseEmbedded()`):
     *  - resolvedPrefix == null (USE_FIELD_NAME) → `<fieldName> + capFirst(<inner>)` → `priceCurrency`
     *  - resolvedPrefix == ""                    → `<inner>` as-is                    → `currency`
     *  - resolvedPrefix == "x"                   → `x + capFirst(<inner>)`            → `xCurrency`
     */
    private fun applySyntheticName(resolvedPrefix: String?, fieldName: String, innerFieldName: String): String =
        when {
            resolvedPrefix == null   -> fieldName + capFirst(innerFieldName)
            resolvedPrefix.isEmpty() -> innerFieldName
            else                     -> resolvedPrefix + capFirst(innerFieldName)
        }

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
