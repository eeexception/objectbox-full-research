/*
 * ObjectBox Build Tools
 * Copyright (C) 2017-2025 ObjectBox Ltd.
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

package io.objectbox.processor

import io.objectbox.annotation.ConflictStrategy
import io.objectbox.annotation.Convert
import io.objectbox.annotation.DatabaseType
import io.objectbox.annotation.DefaultValue
import io.objectbox.annotation.Embedded
import io.objectbox.annotation.ExternalName
import io.objectbox.annotation.ExternalType
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.IdCompanion
import io.objectbox.annotation.Index
import io.objectbox.annotation.IndexType
import io.objectbox.annotation.NameInDb
import io.objectbox.annotation.Transient
import io.objectbox.annotation.Type
import io.objectbox.annotation.Uid
import io.objectbox.annotation.Unique
import io.objectbox.annotation.Unsigned
import io.objectbox.converter.FlexObjectConverter
import io.objectbox.converter.IntegerFlexMapConverter
import io.objectbox.converter.IntegerLongMapConverter
import io.objectbox.converter.LongFlexMapConverter
import io.objectbox.converter.LongLongMapConverter
import io.objectbox.converter.NullToEmptyStringConverter
import io.objectbox.converter.StringFlexMapConverter
import io.objectbox.converter.StringLongMapConverter
import io.objectbox.converter.StringMapConverter
import io.objectbox.generator.IdUid
import io.objectbox.generator.model.EmbeddedField
import io.objectbox.generator.model.Entity
import io.objectbox.generator.model.ModelException
import io.objectbox.generator.model.Property
import io.objectbox.generator.model.PropertyType
import io.objectbox.model.PropertyFlags
import java.util.*
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

/**
 * Parses properties from fields for a given entity and adds them to the entity model.
 */
class Properties(
    private val elementUtils: Elements,
    private val typeUtils: Types,
    private val messages: Messages,
    private val relations: Relations,
    private val entityModel: Entity,
    entityElement: Element,
    private val isSuperEntity: Boolean
) {

    private val typeHelper = TypeHelper(elementUtils, typeUtils)

    private val fields: List<VariableElement> = ElementFilter.fieldsIn(entityElement.enclosedElements)
    private val methods: List<ExecutableElement> = ElementFilter.methodsIn(entityElement.enclosedElements)

    fun hasBoxStoreField(): Boolean {
        return fields.find { it.simpleName.toString() == BOXSTORE_FIELD_NAME } != null
    }

    fun parseFields() {
        for (field in fields) {
            parseField(field)
        }
    }

    private fun String.isReservedName(): Boolean {
        return this == BOXSTORE_FIELD_NAME
    }

    private fun parseField(field: VariableElement) {
        // ignore static, transient or @Transient fields
        val modifiers = field.modifiers
        if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.TRANSIENT)
            || field.hasAnnotation(Transient::class.java)
        ) {
            return
        }

        if (field.simpleName.toString().isReservedName()) {
            messages.error(
                "A property can not be named `__boxStore`. Adding a BoxStore field for relations? Annotate it with @Transient.",
                field
            )
            return
        }

        if (typeHelper.isToOne(field.asType())) {
            // ToOne<TARGET> property
            checkNotSuperEntity(field)
            checkNoIndexOrUniqueAnnotation(field, "ToOne")
            relations.parseToOne(entityModel, field)
        } else if (
            !field.hasAnnotation(Convert::class.java)
            && typeHelper.isList(field.asType())
            && !typeHelper.isStringList(field.asType())
        ) {
            // List<TARGET> property, except List<String>
            checkNotSuperEntity(field)
            checkNoIndexOrUniqueAnnotation(field, "List")
            relations.parseToMany(entityModel, field)
        } else if (typeHelper.isToMany(field.asType())) {
            // ToMany<TARGET> property
            checkNotSuperEntity(field)
            checkNoIndexOrUniqueAnnotation(field, "ToMany")
            relations.parseToMany(entityModel, field)
        } else if (field.hasAnnotation(Embedded::class.java)) {
            // @Embedded container field — flatten inner fields into synthetic properties.
            // Note: unlike ToOne/ToMany above, NO checkNotSuperEntity — @Embedded on a
            // @BaseEntity works via plain Java inheritance (the container field is inherited
            // data, needs no __boxStore injection on the concrete type). The shared entityModel
            // means synthetic props land on the concrete @Entity regardless of where in the
            // chain the @Embedded is declared.
            checkNoIndexOrUniqueAnnotation(field, "@Embedded")
            parseEmbedded(field)
        } else {
            // regular property
            parseProperty(field)
        }
    }

    /**
     * Handles an `@Embedded` container field: walks the embedded type's fields and produces
     * one synthetic [Property] per inner field, each with a prefixed name, then records an
     * [EmbeddedField] on the entity linking container → synthetic properties.
     *
     * ### What this does
     * 1. Resolves the effective prefix from `@Embedded(prefix=...)` and the [Embedded.USE_FIELD_NAME]
     *    sentinel.
     * 2. Resolves the embedded type's [TypeElement] and walks its instance fields, applying the same
     *    skip rules as [parseField] (static / transient / `@Transient`).
     * 3. For each surviving inner field, resolves its [PropertyType] via [TypeHelper.getPropertyType]
     *    and adds a synthetic property to the entity with a prefixed name. The property carries
     *    [EmbeddedField] origin metadata (see [Property.PropertyBuilder.embeddedOrigin]).
     * 4. Registers the [EmbeddedField] itself on the entity for M2 codegen
     *    (Cursor `put()` null-guard + `attachEmbedded()` override generation).
     *
     * ### What this does NOT do (yet)
     * - No constraint validation (target is not `@Entity`, no-arg ctor exists, no nested
     *   `@Embedded`). That is M1.3 — failures in those cases currently surface as downstream
     *   errors (unsupported type for inner fields, etc.) or silently pass.
     * - No codegen changes. Flattened properties land in the model; `PropertyCollector` / templates
     *   are updated in M2.
     *
     * ### Intentional design choices
     * - Synthetic properties are **NOT** marked `fieldAccessible`. At APT time no field named
     *   e.g. `priceCurrency` exists on the entity (the bytecode transformer injects it later).
     *   Leaving this unset means the default [Property.getValueExpression] would emit
     *   `getPriceCurrency()` — also non-existent — so if M2's `PropertyCollector` forgets to
     *   short-circuit on [Property.isEmbedded], the generated Cursor will **fail to compile**
     *   loudly rather than silently reading garbage.
     * - Synthetic properties are **NOT** marked virtual. The transformer-injected field IS real;
     *   JNI must set it directly by `Property.name` during reads.
     *
     * ### Nested `@Embedded` (P2.4)
     * When an inner field is itself `@Embedded`, this method **recurses** with
     * `parentEmbedded` set. The child's prefix is compounded through the parent's
     * [EmbeddedField.syntheticNameFor] (e.g. parent `price` + child `cur` → `priceCur`,
     * so leaf `code` → `priceCurCode`). The child [EmbeddedField] carries a `parent` ref
     * so codegen can emit a **chained hoist** (`__emb_price_cur = __emb_price != null ?
     * __emb_price.cur : null`) — see [EmbeddedField.hoistRhsExpression]. All nested
     * [EmbeddedField]s go into the entity's flat list in depth-first order (parent
     * registered BEFORE inner-field walk → guaranteed before any nested child), which
     * is what PropertyCollector's hoist loop depends on (each nested hoist reads the
     * parent's local, which must already be declared).
     *
     * Cycle detection uses a per-recursion-path set of visited type FQNs: a type embedding
     * itself (directly or transitively) produces infinite recursion and infinite columns;
     * we catch it at the first revisit with a clear error naming the cycle edge.
     *
     * @param field the `@Embedded`-annotated [VariableElement]
     * @param parentEmbedded `null` at the top level (called from [parseField]); set to
     *   the enclosing container when recursing into a nested `@Embedded`.
     * @param visitedTypes FQNs of types already on the current recursion path (NOT a
     *   global cache — two sibling embeds of the same type are fine; only a PATH cycle
     *   is an error). A fresh set is passed at each recurse with the current type added.
     * @return count of LEAF synthetic properties produced (including transitive leaves
     *   from nested embeds); `-1` if any error was reported (error already emitted,
     *   caller should abort). The top-level call from [parseField] ignores the return.
     */
    private fun parseEmbedded(
        field: VariableElement,
        parentEmbedded: EmbeddedField? = null,
        visitedTypes: Set<String> = emptySet()
    ): Int {
        val annotation = field.getAnnotation(Embedded::class.java)
        val fieldName = field.simpleName.toString()

        // ─── Resolve prefix: sentinel → field name; explicit "" → no prefix; else → as given ───
        // For nested embeds, compound through the parent: the parent's prefix becomes
        // part of OURS so that syntheticNameFor() on a leaf produces the fully-qualified
        // flat name (priceCurCode, not curCode). syntheticNameFor's empty-prefix
        // short-circuit makes this compose correctly for all four empty/non-empty combos
        // (e.g. outer "" + inner "cur" → just "cur"; outer "price" + inner "" → "price").
        val rawPrefix = annotation.prefix
        val localPrefix = if (rawPrefix == Embedded.USE_FIELD_NAME) fieldName else rawPrefix
        val effectivePrefix = parentEmbedded?.syntheticNameFor(localPrefix) ?: localPrefix

        // ─── Resolve embedded type element to walk its fields ───
        val fieldType = field.asType()
        if (fieldType !is DeclaredType) {
            // Primitive, array, etc. — cannot be a container. M1.3 will sharpen this message.
            messages.error(
                "@Embedded field '$fieldName' must be a declared class type, got $fieldType.",
                field
            )
            return -1
        }
        val typeElement = fieldType.asElement() as? TypeElement
        if (typeElement == null) {
            messages.error(
                "@Embedded field '$fieldName' type could not be resolved to a class element.",
                field
            )
            return -1
        }
        val typeFqn = typeElement.qualifiedName.toString()
        val typeSimple = typeElement.simpleName.toString()

        // ─── P2.4 cycle check — BEFORE validateEmbeddedType (cheaper, and the cycle ───
        // message is more actionable than a validation failure that happens to be
        // transitively caused by a cycle). Compared against FQN so distinct types
        // with the same simple name don't false-positive; and we check against the
        // PATH set (not a global "seen types") so two sibling @Embedded Money
        // fields in the same entity are fine — only Money embedding Money is a cycle.
        if (typeFqn in visitedTypes) {
            messages.error(
                "@Embedded cycle detected: '$typeSimple' ($typeFqn) embeds itself " +
                        "(directly or transitively) via field '$fieldName'. " +
                        "Break the cycle by removing @Embedded at one level.",
                field
            )
            return -1
        }

        // ─── M1.3 type-level validation — reject before polluting the entity model ───
        if (!validateEmbeddedType(fieldName, typeElement, typeSimple, field)) {
            return -1
        }

        // ─── Create and register the container model BEFORE synthesizing properties ───
        // Registering first reserves the container name in the entity's unique-name set,
        // so a subsequent synthetic prop with the same name (e.g. prefix="" + inner field
        // name == container name) fails fast via trackUniqueName().
        //
        // For nested embeds, registering here (before the recursive walk) ALSO guarantees
        // the flat entity.embeddedFields list is in depth-first parent-before-child order,
        // which PropertyCollector's hoist loop REQUIRES (a nested hoist reads from its
        // parent's local var — that local must already be declared by the time the nested
        // hoist emits its own declaration line).
        val isContainerFieldAccessible = !field.modifiers.contains(Modifier.PRIVATE)
        val embedded = EmbeddedField(
            name = fieldName,
            isFieldAccessible = isContainerFieldAccessible,
            prefix = effectivePrefix,
            typeFullyQualifiedName = typeFqn,
            typeSimpleName = typeSimple,
            parent = parentEmbedded
        )
        embedded.setParsedElement(field)
        try {
            entityModel.addEmbedded(embedded)
        } catch (e: ModelException) {
            messages.error("Could not add @Embedded container: ${e.message}", field)
            return -1
        }

        // ─── Walk inner fields, applying the same skip rules as parseField() ───
        val innerFields = ElementFilter.fieldsIn(typeElement.enclosedElements)
        var producedCount = 0
        for (innerField in innerFields) {
            val innerModifiers = innerField.modifiers
            if (innerModifiers.contains(Modifier.STATIC)
                || innerModifiers.contains(Modifier.TRANSIENT)
                || innerField.hasAnnotation(Transient::class.java)
            ) {
                continue
            }

            val innerFieldName = innerField.simpleName.toString()

            // ─── P2.4: Nested @Embedded — recurse with compound prefix + cycle tracking ───
            // The recursive call handles ALL of: prefix compounding, cycle detection,
            // type validation, and registration of the nested EmbeddedField (with
            // parent=embedded) on the entity's flat list. Leaf properties synthesised
            // downstream of the recursion get their embeddedOrigin set to the INNERMOST
            // container (the recursive call's own `embedded` local), which is exactly
            // what PropertyCollector needs — the per-property guard/deref uses the
            // origin's localVarName, and for nested that's the path-qualified
            // `__emb_price_cur` (not the outer `__emb_price`).
            //
            // We pass a FRESH set (visitedTypes + typeFqn) rather than mutating — two
            // sibling nested embeds at the same level should each see the same parent
            // path, not whatever the first sibling's traversal added/removed. Copy
            // cost is negligible for realistic nesting depths (1-3).
            //
            // Transitive leaf count rolls up so the "no persistable fields" check below
            // recognises a container whose ONLY field is another @Embedded (which in
            // turn has real fields) as valid — it produced no direct leaves but it DID
            // contribute properties to the entity.
            if (innerField.hasAnnotation(Embedded::class.java)) {
                val nestedCount = parseEmbedded(
                    innerField,
                    parentEmbedded = embedded,
                    visitedTypes = visitedTypes + typeFqn
                )
                if (nestedCount < 0) return -1 // error already emitted; propagate abort
                producedCount += nestedCount
                continue
            }

            // ─── R5 (complete): ObjectBox property-config annotations on inner fields ───
            // Today these are silently ignored — parseEmbedded() only reads the inner field's
            // TYPE and NAME, never its annotations beyond @Transient/@Embedded. That silent-ignore
            // is the worst possible behaviour: users slap @Index on an inner field, compilation
            // succeeds, and queries degrade silently because the index was never created.
            //
            // Phase 2 (P2.1 @NameInDb/@Uid, P2.2 @Convert) will wire SOME of these through —
            // the flattened property IS a real column and e.g. @Index on it is perfectly
            // meaningful. @Id never makes sense inside a value object (only @Entity has an ID
            // column). Until then: explicit rejection with the workaround spelled out, so users
            // know exactly what to do.
            //
            // Batched as four sequential checks rather than a single combined predicate:
            // each annotation has a DIFFERENT fix hint, and the error should name the specific
            // annotation the user wrote. @Id gets "not allowed" (permanent); the rest get
            // "not yet supported" (Phase 2 landing).
            if (innerField.hasAnnotation(Id::class.java)) {
                messages.error(
                    "@Embedded '$fieldName': @Id on inner field '$innerFieldName' is not allowed. " +
                            "Only @Entity classes have an ID column; the owning entity already has one.",
                    innerField
                )
                return -1
            }
            if (innerField.hasAnnotation(Index::class.java) || innerField.hasAnnotation(HnswIndex::class.java)) {
                messages.error(
                    "@Embedded '$fieldName': @Index on inner field '$innerFieldName' is not yet supported. " +
                            "To index the flattened property directly in the entity, add a duplicate " +
                            "property on the entity (or wait for @Embedded @Index passthrough support).",
                    innerField
                )
                return -1
            }
            if (innerField.hasAnnotation(Unique::class.java)) {
                messages.error(
                    "@Embedded '$fieldName': @Unique on inner field '$innerFieldName' is not yet supported. " +
                            "Add the unique constraint on an entity-level property instead.",
                    innerField
                )
                return -1
            }
            val innerTypeMirror = innerField.asType()

            // ─── R5: Relations inside @Embedded — explicit rejection ───
            // A ToOne/ToMany inside a value object would require injecting __boxStore into a
            // non-@Entity class AND synthesising the relation's FK column through two layers
            // of flattening. The pattern the user almost certainly wants — a relation from the
            // OWNING entity — already works. Intercept BEFORE getPropertyType() (which would
            // just return null and fall through to the generic "unsupported type" below with no
            // hint that the fix is trivial: move the relation one level up).
            if (typeHelper.isToOne(innerTypeMirror) || typeHelper.isToMany(innerTypeMirror)) {
                messages.error(
                    "@Embedded '$fieldName': relations are not supported inside an @Embedded type " +
                            "(inner field '$innerFieldName' is a ${innerTypeMirror}). " +
                            "Move the relation to the owning entity instead.",
                    innerField
                )
                return -1
            }

            // ─── P2.2: @Convert on inner field — resolve type from annotation's dbType ───
            // When @Convert is present, the synthetic property's PropertyType comes from the
            // annotation's dbType (e.g. String.class), NOT the inner field's Java type (e.g.
            // java.util.UUID). The field's Java type becomes `customType`. The downstream
            // machinery — cursor.ftl's converter-instance emission, PropertyCollector's
            // getDatabaseValueExpression() wrap, Entity.init2ndPass()'s import handling —
            // all key off `customType != null` on ANY property in entity.properties.
            // Synthetic embedded props are in that list. So everything composes for free
            // once we set customType/converter here.
            //
            // Mirror-based extraction because converter/dbType are Class<?> members —
            // direct getAnnotation() would throw MirroredTypeException. Pattern mirrored
            // from customPropertyBuilderOrNull().
            var convertCustomType: String? = null
            var convertConverter: String? = null
            val convertMirror = getAnnotationMirror(innerField, Convert::class.java)
            val innerPropertyType: PropertyType
            if (convertMirror != null) {
                val converter = getAnnotationValueType(convertMirror, "converter")!!
                val dbType = getAnnotationValueType(convertMirror, "dbType")!!
                val dbPropertyType = typeHelper.getPropertyType(dbType)
                if (dbPropertyType == null) {
                    messages.error(
                        "@Embedded '$fieldName' inner field '$innerFieldName': @Convert dbType '$dbType' " +
                                "is not a supported ObjectBox type. Use a Java primitive wrapper class.",
                        innerField
                    )
                    return -1
                }
                innerPropertyType = dbPropertyType
                // Erase parameterised types (e.g. List<Thing> → java.util.List) for customType.
                convertCustomType = typeUtils.erasure(innerTypeMirror).toString()
                convertConverter = converter.toString()
            } else {
                // Non-@Convert path: type derived directly from the inner field's Java type.
                val resolved = typeHelper.getPropertyType(innerTypeMirror)
                if (resolved == null) {
                    messages.error(
                        "@Embedded '$fieldName' inner field '$innerFieldName' has unsupported type " +
                                "'$innerTypeMirror'. Use an ObjectBox-supported type or add @Convert.",
                        innerField
                    )
                    return -1
                }
                innerPropertyType = resolved
            }

            val syntheticName = embedded.syntheticNameFor(innerFieldName)

            // Add synthetic property to the entity with the prefixed name.
            // This is a REAL property: it gets a DB column, a model ID, a static Property field
            // in the generated EntityInfo_ class, and JNI will set a field of this name on reads.
            val builder: Property.PropertyBuilder = try {
                entityModel.addProperty(innerPropertyType, syntheticName)
            } catch (e: ModelException) {
                messages.error(
                    "Could not add synthetic @Embedded property '$syntheticName' " +
                            "(from '$fieldName.$innerFieldName'): ${e.message}",
                    field
                )
                return -1
            }

            // ─── Set flags mirroring supportedPropertyBuilderOrNull() — ───
            // the INNER field's type determines nullability/primitiveness, not the container.
            // For @Convert, these predicates operate on the FIELD type (e.g. UUID → not
            // primitive) which is correct: a @Convert property is never typeNotNull at the
            // DB layer even if dbType is a primitive wrapper (customPropertyBuilderOrNull()
            // never calls typeNotNull either). The nonPrimitiveFlag branch below also
            // happens not to fire for e.g. dbType=String (String.isScalar == false), so
            // we add it explicitly for the @Convert case right after.
            val innerIsPrimitive = innerTypeMirror.kind.isPrimitive
            if (!innerIsPrimitive && (innerPropertyType.isScalar || typeHelper.isStringList(innerTypeMirror))) {
                builder.nonPrimitiveFlag()
            }
            if (innerPropertyType == PropertyType.StringArray && typeHelper.isStringList(innerTypeMirror)) {
                builder.isList()
            }
            if (innerIsPrimitive) {
                builder.typeNotNull()
            }

            // ─── P2.2: Apply @Convert metadata captured above ───
            // customType() MUST be called BEFORE nonPrimitiveFlag(): the builder's
            // nonPrimitiveFlag() check at Property.java:123-128 throws if called on a
            // non-scalar type unless customType is already set (the "custom type is
            // inherently nullable" escape hatch). Order mirrors customPropertyBuilderOrNull().
            if (convertCustomType != null) {
                builder.customType(convertCustomType, convertConverter)
                builder.nonPrimitiveFlag()
            }

            // ─── P2.1: @NameInDb / @Uid passthrough from inner field ───
            // Both annotations modulate the SYNTHETIC property's DB-side identity:
            //   @NameInDb → overrides the column name (what model.json's "name" becomes).
            //     Used verbatim — we do NOT re-apply the container prefix to the user's
            //     explicit override. If the same @Embedded type is used twice and the inner
            //     @NameInDb collides, trackUniqueName() fails loudly (that's the user's
            //     explicit choice — what-you-write-is-what-you-get).
            //   @Uid → pins the column's UID for schema migration stability. Lets users
            //     rename Money.currency → Money.isoCode without IdSync treating it as a
            //     drop-and-recreate.
            // Pattern mirrored exactly from parseField()'s handling at lines ~585-618.
            val nameInDbAnnotation = innerField.getAnnotation(NameInDb::class.java)
            if (nameInDbAnnotation != null) {
                val dbName = nameInDbAnnotation.value
                if (dbName.isNotEmpty()) {
                    builder.dbName(dbName)
                }
            }
            val uidAnnotation = innerField.getAnnotation(Uid::class.java)
            if (uidAnnotation != null) {
                // 0L sentinel → -1 → IdSync prints current UID and fails (the "what's my UID?"
                // workflow). Same semantics as regular properties.
                val uid = if (uidAnnotation.value == 0L) -1 else uidAnnotation.value
                builder.modelId(IdUid(0, uid))
            }

            // ─── Embedded-origin metadata for M2 codegen ───
            val innerIsFieldAccessible = !innerField.modifiers.contains(Modifier.PRIVATE)
            builder.embeddedOrigin(embedded, innerFieldName, innerIsFieldAccessible)
            // parsedElement → the inner field (for error reporting / diagnostics)
            builder.property.parsedElement = innerField

            embedded.addProperty(builder.property)
            producedCount++
        }

        if (producedCount == 0) {
            messages.error(
                "@Embedded '$fieldName' of type '$typeSimple' has no persistable fields " +
                        "(all fields are static, transient, or @Transient).",
                field
            )
            return -1
        }
        return producedCount
    }

    /**
     * M1.3 — Validates that the `@Embedded` field's type is a legal container.
     *
     * Checks run **before** any model mutation so that rejection doesn't leave a half-built
     * [EmbeddedField] or orphaned synthetic properties in the entity. Each check addresses a
     * specific failure mode that would otherwise surface far downstream (at M2 codegen or,
     * worse, at runtime):
     *
     * 1. **Not an `@Entity`** — Embedding an entity would flatten its `@Id` into the owner as
     *    a regular column (semantic nonsense). Users wanting entity composition need relations.
     *    Without this check, compilation succeeds silently today (the `@Id` on the inner type
     *    is ignored by [parseEmbedded] since it only calls [TypeHelper.getPropertyType]).
     *
     * 2. **Concrete class** — M2's generated `attachEmbedded()` emits `new TypeName()` to
     *    hydrate the container. Interfaces and abstract classes cannot be instantiated, so
     *    the generated Cursor would fail to compile — but at a layer far removed from the
     *    actual mistake. We catch it here with a precise pointer.
     *
     * 3. **No-arg constructor** — Same reason as (2). Java suppresses the default ctor if ANY
     *    explicit ctor is declared, so this is easy to hit accidentally. The check mirrors
     *    [io.objectbox.processor.ObjectBoxProcessor.hasNoArgConstructor] but inlined here to
     *    keep the embedded validation cohesive.
     *
     * @return `true` if all checks pass; `false` if an error was reported and processing
     *         should abort for this field.
     */
    private fun validateEmbeddedType(
        fieldName: String,
        typeElement: TypeElement,
        typeSimple: String,
        errorElement: Element
    ): Boolean {
        // ─── 1. Must NOT itself be an @Entity ───
        // Note: FQN used because `io.objectbox.generator.model.Entity` (the model class) is
        // already imported and would shadow the annotation.
        if (typeElement.getAnnotation(io.objectbox.annotation.Entity::class.java) != null) {
            messages.error(
                "@Embedded '$fieldName': type '$typeSimple' is an @Entity. Embedded types " +
                        "must be plain value objects — use a ToOne or ToMany relation instead.",
                errorElement
            )
            return false
        }

        // ─── 2. Must be a concrete class (not interface, enum, annotation, or abstract) ───
        // A DeclaredType can be any of these; the M1.2 !is DeclaredType check only catches
        // primitives/arrays. ElementKind.CLASS excludes interfaces/enums/annotations; the
        // abstract modifier check catches `abstract class Foo`.
        if (typeElement.kind != ElementKind.CLASS
            || typeElement.modifiers.contains(Modifier.ABSTRACT)
        ) {
            messages.error(
                "@Embedded '$fieldName': type '$typeSimple' must be a concrete class " +
                        "(not an interface, enum, or abstract class) — the generated cursor " +
                        "needs to instantiate it via `new $typeSimple()`.",
                errorElement
            )
            return false
        }

        // ─── 3. Must have a no-argument constructor ───
        // Visibility is NOT checked: the generated Cursor lives in the same package as the
        // entity, and the transformer can inject a bridge if needed. We only require the
        // *shape* (zero params). Mirrors ObjectBoxProcessor.hasNoArgConstructor().
        val hasNoArg = ElementFilter.constructorsIn(typeElement.enclosedElements)
            .any { it.parameters.isEmpty() }
        if (!hasNoArg) {
            messages.error(
                "@Embedded '$fieldName': type '$typeSimple' must have a no-argument " +
                        "constructor — the generated cursor instantiates the container " +
                        "via `new $typeSimple()` when hydrating reads.",
                errorElement
            )
            return false
        }

        return true
    }

    private fun checkNotSuperEntity(field: VariableElement) {
        if (isSuperEntity) {
            messages.error("A super class of an @Entity must not have a relation.", field)
        }
    }

    private fun checkNoIndexOrUniqueAnnotation(field: VariableElement, relationType: String) {
        val hasIndex = field.hasAnnotation(Index::class.java)
        if (hasIndex || field.hasAnnotation(Unique::class.java)) {
            val annotationName = if (hasIndex) "Index" else "Unique"
            messages.error(
                "@$annotationName can not be used with a $relationType relation, remove @$annotationName.",
                field
            )
        }
    }

    private fun parseProperty(field: VariableElement) {
        // Why nullable? A property might not be parsed due to an error. Do not throw here.
        val propertyBuilder: Property.PropertyBuilder = when {
            field.hasAnnotation(DefaultValue::class.java) -> {
                defaultValuePropertyBuilderOrNull(field)
            }

            field.hasAnnotation(Convert::class.java) -> {
                // verify @Convert custom type
                customPropertyBuilderOrNull(field)
            }

            else -> {
                // Is it a type directly supported by the database?
                val propertyType = typeHelper.getPropertyType(field.asType())
                if (propertyType != null) {
                    supportedPropertyBuilderOrNull(field, propertyType)
                } else {
                    // Maybe a built-in converter can be used?
                    autoConvertedPropertyBuilderOrNull(field)
                }
            }
        } ?: return

        propertyBuilder.property.parsedElement = field

        // checks if field is accessible
        val isPrivate = field.modifiers.contains(Modifier.PRIVATE)
        if (!isPrivate) {
            propertyBuilder.fieldAccessible()
        }
        // find getter method name
        val getterMethodName = getGetterMethodNameFor(field.asType(), propertyBuilder.property)
        propertyBuilder.getterMethodName(getterMethodName)

        // @Id
        val idAnnotation = field.getAnnotation(Id::class.java)
        val hasIdAnnotation = idAnnotation != null
        if (hasIdAnnotation) {
            if (propertyBuilder.property.propertyType != PropertyType.Long) {
                messages.error("An @Id property must be a not-null long.", field)
            }
            if (isPrivate && getterMethodName == null) {
                messages.error("An @Id property must not be private or have a not-private getter and setter.", field)
            }
            propertyBuilder.primaryKey()
            if (idAnnotation.assignable) {
                propertyBuilder.idAssignable()
            }
        }

        // @IdCompanion
        if (field.hasAnnotation(IdCompanion::class.java)) {
            // Ensure there is at most one @IdCompanion.
            val existing = entityModel.properties.find { it.isIdCompanion }
            if (existing != null) {
                messages.error("'${existing.propertyName}' is already an @IdCompanion property, there can only be one.")
            } else {
                // Only Date or DateNano are supported.
                if (propertyBuilder.property.propertyType != PropertyType.Date
                    && propertyBuilder.property.propertyType != PropertyType.DateNano
                ) {
                    messages.error(
                        "@IdCompanion has to be of type Date or a long annotated with @Type(DateNano).",
                        field
                    )
                } else {
                    propertyBuilder.idCompanion()
                }
            }
        }

        // @Unsigned
        if (field.hasAnnotation(Unsigned::class.java)) {
            val type = propertyBuilder.property.propertyType
            if (type != PropertyType.Byte && type != PropertyType.Short && type != PropertyType.Int
                && type != PropertyType.Long && type != PropertyType.Char
            ) {
                messages.error("@Unsigned is only supported for integer properties.")
            } else if (hasIdAnnotation) {
                messages.error("@Unsigned can not be used with @Id. ID properties are unsigned internally by default.")
            } else {
                propertyBuilder.unsigned()
            }
        }

        // @NameInDb
        val nameInDbAnnotation = field.getAnnotation(NameInDb::class.java)
        if (nameInDbAnnotation != null) {
            val name = nameInDbAnnotation.value
            if (name.isNotEmpty()) {
                propertyBuilder.dbName(name)
            }
        }

        // @ExternalName
        field.getAnnotation(ExternalName::class.java)?.value
            ?.also { propertyBuilder.externalName(it) }

        // @ExternalType
        // Note: not validating the external type for the property type here. Instead, let the database do
        // it at runtime as this can be complex. And we don't want to maintain checks in multiple places.
        field.getAnnotation(ExternalType::class.java)?.value
            ?.also { propertyBuilder.externalType(it) }

        // @Index, @Unique
        parseIndexAndUniqueAnnotations(field, propertyBuilder, hasIdAnnotation)

        // @HnswIndex
        // Note: using other index annotations on FloatArray currently
        // errors, so no need to integrate with regular index processing.
        parseHnswIndexAnnotation(field, propertyBuilder)

        // @Uid
        val uidAnnotation = field.getAnnotation(Uid::class.java)
        if (uidAnnotation != null) {
            // just storing uid, id model sync will replace with correct id+uid
            // Note: UID values 0 and -1 are special: print current value and fail later
            val uid = if (uidAnnotation.value == 0L) -1 else uidAnnotation.value
            propertyBuilder.modelId(IdUid(0, uid))
        }
    }

    private fun parseHnswIndexAnnotation(field: VariableElement, propertyBuilder: Property.PropertyBuilder) {
        val hnswIndexAnnotation = field.getAnnotation(HnswIndex::class.java) ?: return
        val propertyType = propertyBuilder.property.propertyType
        if (propertyType != PropertyType.FloatArray) {
            messages.error("@HnswIndex is only supported for float vector properties.")
        }
        try {
            propertyBuilder.hnswParams(hnswIndexAnnotation)
        } catch (e: ModelException) {
            messages.error(e.message!!, field)
        }
    }

    private fun parseIndexAndUniqueAnnotations(
        field: VariableElement, propertyBuilder: Property.PropertyBuilder,
        hasIdAnnotation: Boolean
    ) {
        val indexAnnotation = field.getAnnotation(Index::class.java)
        val uniqueAnnotation = field.getAnnotation(Unique::class.java)
        if (indexAnnotation == null && uniqueAnnotation == null) {
            return
        }

        // determine index type
        val propertyType = propertyBuilder.property.propertyType
        val supportsHashIndex = propertyType == PropertyType.String
        // || propertyType == PropertyType.ByteArray // Not yet supported for byte[]
        val indexType = indexAnnotation?.type ?: IndexType.DEFAULT

        // error if HASH or HASH64 is not supported by property type
        if (!supportsHashIndex && (indexType == IndexType.HASH || indexType == IndexType.HASH64)) {
            messages.error("IndexType.$indexType is not supported for $propertyType.", field)
        }

        // error if used with @Id
        if (hasIdAnnotation) {
            val annotationName = if (indexAnnotation != null) "Index" else "Unique"
            messages.error("@Id property is unique and indexed by default, remove @$annotationName.", field)
        }

        // error if unsupported property type
        if (propertyType == PropertyType.Float ||
            propertyType == PropertyType.Double ||
            propertyType == PropertyType.BooleanArray ||
            propertyType == PropertyType.ByteArray ||
            propertyType == PropertyType.ShortArray ||
            propertyType == PropertyType.CharArray ||
            propertyType == PropertyType.IntArray ||
            propertyType == PropertyType.LongArray ||
            propertyType == PropertyType.FloatArray ||
            propertyType == PropertyType.DoubleArray ||
            propertyType == PropertyType.StringArray
        ) {
            val annotationName = if (indexAnnotation != null) "Index" else "Unique"
            messages.error("@$annotationName is not supported for $propertyType, remove @$annotationName.", field)
        }

        // compute actual property flags for model
        var indexFlags: Int = when (indexType) {
            IndexType.VALUE -> PropertyFlags.INDEXED
            IndexType.HASH -> PropertyFlags.INDEX_HASH
            IndexType.HASH64 -> PropertyFlags.INDEX_HASH64
            IndexType.DEFAULT -> {
                // auto detect
                if (supportsHashIndex) {
                    PropertyFlags.INDEX_HASH // String and byte[] like HASH
                } else {
                    PropertyFlags.INDEXED // others like VALUE
                }
            }
        }
        if (uniqueAnnotation != null) {
            indexFlags = indexFlags or PropertyFlags.UNIQUE
            // determine unique conflict resolution
            if (uniqueAnnotation.onConflict == ConflictStrategy.REPLACE) {
                indexFlags = indexFlags or PropertyFlags.UNIQUE_ON_CONFLICT_REPLACE
            }
        }

        propertyBuilder.index(indexFlags, 0)
    }

    private fun defaultValuePropertyBuilderOrNull(field: VariableElement): Property.PropertyBuilder? {
        if (field.hasAnnotation(Convert::class.java)) {
            messages.error("Can not use both @Convert and @DefaultValue.", field)
            return null
        }

        when (field.getAnnotation(DefaultValue::class.java).value) {
            "" -> {
                val propertyType = typeHelper.getPropertyType(field.asType())
                if (propertyType != PropertyType.String) {
                    messages.error("For @DefaultValue(\"\") property must be String.", field)
                    return null
                }

                val builder = try {
                    entityModel.addProperty(propertyType, field.simpleName.toString())
                } catch (e: Exception) {
                    messages.error("Could not add property: ${e.message}", field)
                    if (e is ModelException) return null else throw e
                }
                builder.customType(field.asType().toString(), NullToEmptyStringConverter::class.java.canonicalName)

                return builder
            }

            else -> {
                messages.error("Only @DefaultValue(\"\") is supported.", field)
                return null
            }
        }
    }

    private fun customPropertyBuilderOrNull(field: VariableElement): Property.PropertyBuilder? {
        // extract @Convert annotation member values
        // as they are types, need to access them via annotation mirrors
        val annotationMirror = getAnnotationMirror(field, Convert::class.java)
            ?: return null // did not find @Convert mirror

        // converter and dbType value existence guaranteed by compiler
        val converter = getAnnotationValueType(annotationMirror, "converter")!!
        val dbType = getAnnotationValueType(annotationMirror, "dbType")!!

        // Detect property type based on dbType of annotation.
        val propertyType = typeHelper.getPropertyType(dbType)
        if (propertyType == null) {
            messages.error("@Convert dbType type is not supported, use a Java primitive wrapper class.", field)
            return null
        }
        val propertyDbType = determinePropertyDatabaseType(
            field,
            propertyType
        ) ?: return null

        // may be a parameterized type like List<CustomType>, so erase any type parameters
        val customType = typeUtils.erasure(field.asType())

        val propertyBuilder = entityModel.tryToAddProperty(propertyDbType, field) ?: return null

        propertyBuilder.customType(customType.toString(), converter.toString())
        // Flag custom type properties as non-primitive to the database
        propertyBuilder.nonPrimitiveFlag()
        return propertyBuilder
    }

    /**
     * Parses the [field] and tries to add the property to the entity model, returns the started builder.
     * If adding the property to the model fails, prints an error and returns null.
     */
    private fun supportedPropertyBuilderOrNull(
        field: VariableElement,
        propertyType: PropertyType
    ): Property.PropertyBuilder? {
        val propertyDbType = determinePropertyDatabaseType(
            field,
            propertyType
        ) ?: return null

        val propertyBuilder = entityModel.tryToAddProperty(propertyDbType, field) ?: return null

        val typeMirror = field.asType()
        val isPrimitive = typeMirror.kind.isPrimitive
        // Flag wrapper types (Long, Integer, ...) of scalar types and String list as non-primitive to the database
        if (!isPrimitive && (propertyDbType.isScalar || typeHelper.isStringList(typeMirror))) {
            propertyBuilder.nonPrimitiveFlag()
        }
        // For String vectors, indicate if the Java type is a List (or otherwise an array).
        if (propertyType == PropertyType.StringArray && typeHelper.isStringList(typeMirror)) {
            propertyBuilder.isList()
        }
        // Only Java primitive types can never be null
        if (isPrimitive) propertyBuilder.typeNotNull()

        return propertyBuilder
    }

    /**
     * If property type is overridden using a `@Type` annotation, returns the new property type.
     * Errors and returns null if `@Type` is used incorrectly.
     */
    private fun determinePropertyDatabaseType(field: VariableElement, propertyType: PropertyType): PropertyType? {
        val typeAnnotation = field.getAnnotation(Type::class.java)
        if (typeAnnotation != null) {
            return when (typeAnnotation.value) {
                DatabaseType.DateNano -> {
                    if (propertyType == PropertyType.Long) {
                        PropertyType.DateNano
                    } else {
                        messages.error("@Type(DateNano) only supports properties with type Long.", field)
                        null
                    }
                }

                else -> {
                    messages.error("@Type does not support the given type.", field)
                    null
                }
            }
        }

        // Not overridden, use property type detected based on field type.
        return propertyType
    }

    /**
     * If [field] has a type for which a built-in converter is available,
     * prints which converter is used and returns a builder. Otherwise, prints an error and returns null.
     */
    private fun autoConvertedPropertyBuilderOrNull(field: VariableElement): Property.PropertyBuilder? {
        val fieldType = field.asType()

        if (typeHelper.isStringStringMap(fieldType)) {
            return addAutoConvertedMapProperty(field, StringMapConverter::class.java.canonicalName)
        }

        if (typeHelper.isStringLongMap(fieldType)) {
            return addAutoConvertedMapProperty(field, StringLongMapConverter::class.java.canonicalName)
        }

        if (typeHelper.isStringMap(fieldType)) {
            return addAutoConvertedMapProperty(field, StringFlexMapConverter::class.java.canonicalName)
        }

        if (typeHelper.isIntegerLongMap(fieldType)) {
            return addAutoConvertedMapProperty(field, IntegerLongMapConverter::class.java.canonicalName)
        }

        if (typeHelper.isIntegerMap(fieldType)) {
            return addAutoConvertedMapProperty(field, IntegerFlexMapConverter::class.java.canonicalName)
        }

        if (typeHelper.isLongLongMap(fieldType)) {
            return addAutoConvertedMapProperty(field, LongLongMapConverter::class.java.canonicalName)
        }

        if (typeHelper.isLongMap(fieldType)) {
            return addAutoConvertedMapProperty(field, LongFlexMapConverter::class.java.canonicalName)
        }

        if (typeHelper.isObject(fieldType)) {
            val builder = entityModel.tryToAddProperty(PropertyType.Flex, field)
                ?: return null
            val converterCanonicalName = FlexObjectConverter::class.java.canonicalName
            builder.customType(field.asType().toString(), converterCanonicalName)
            messages.info("Using $converterCanonicalName to convert Object property '${field.simpleName}' in '${entityModel.className}', to change this use @Convert.")
            return builder
        }

        messages.error(
            "Field type \"$fieldType\" is not supported. Consider making the target an @Entity, " +
                    "or using @Convert or @Transient on the field (see docs).", field
        )
        return null
    }

    private fun addAutoConvertedMapProperty(
        field: VariableElement,
        converterCanonicalName: String
    ): Property.PropertyBuilder? {
        val builder = entityModel.tryToAddProperty(PropertyType.Flex, field)
            ?: return null

        // Is Map<K, V>, so erase type params (-> Map) as generator model does not support them.
        val plainMapType = typeUtils.erasure(field.asType()).toString()

        builder.customType(plainMapType, converterCanonicalName)
        messages.info("Using $converterCanonicalName to convert map property '${field.simpleName}' in '${entityModel.className}', to change this use @Convert.")

        return builder
    }

    private fun Entity.tryToAddProperty(propertyType: PropertyType, field: VariableElement): Property.PropertyBuilder? {
        return try {
            addProperty(propertyType, field.simpleName.toString())
        } catch (e: Exception) {
            messages.error("Could not add property: ${e.message}", field)
            if (e is ModelException) null else throw e
        }
    }

    private fun getAnnotationMirror(element: Element, annotationClass: Class<*>): AnnotationMirror? {
        val annotationMirrors = element.annotationMirrors
        for (annotationMirror in annotationMirrors) {
            val annotationType = annotationMirror.annotationType
            val convertType = elementUtils.getTypeElement(annotationClass.canonicalName).asType()
            if (typeUtils.isSameType(annotationType, convertType)) {
                return annotationMirror
            }
        }
        return null
    }

    private fun getAnnotationValueType(annotationMirror: AnnotationMirror, memberName: String): TypeMirror? {
        val elementValues = annotationMirror.elementValues
        for ((key, value) in elementValues) {
            val elementName = key.simpleName.toString()
            if (elementName == memberName) {
                // this is a shortcut instead of using entry.getValue().accept(visitor, null)
                return value.value as TypeMirror
            }
        }
        return null
    }

    private fun <A : Annotation> Element.hasAnnotation(annotationType: Class<A>): Boolean {
        return getAnnotation(annotationType) != null
    }

    /**
     * Tries to find a getter method name for the given property that returns the given type.
     * Prefers isPropertyName over getPropertyName if property starts with 'is' then uppercase letter.
     * Prefers isPropertyName over getPropertyName if property is Boolean.
     * Otherwise, looks for getPropertyName method.
     * If none is found, returns null (Property falls back to expecting regular getter).
     */
    private fun getGetterMethodNameFor(fieldType: TypeMirror, property: Property): String? {
        val propertyName = property.propertyName
        val propertyNameCapitalized = propertyName.replaceFirstChar { it.titlecase(Locale.getDefault()) }

        // https://kotlinlang.org/docs/reference/java-to-kotlin-interop.html#properties
        // Kotlin: 'isProperty' (but not 'isproperty').
        if (propertyName.startsWith("is") && propertyName[2].isUpperCase()) {
            methods.find {
                it.simpleName.toString() == propertyName && typeUtils.isSameType(it.returnType, fieldType)
            }?.let {
                return it.simpleName.toString() // Getter is called 'isProperty' (setter 'setProperty').
            }
        }

        // https://docs.oracle.com/javase/tutorial/javabeans/writing/properties.html
        // Java: 'isProperty' for booleans (JavaBeans spec).
        if (property.propertyType == PropertyType.Boolean) {
            methods.find {
                it.simpleName.toString() == "is$propertyNameCapitalized" && typeUtils.isSameType(
                    it.returnType,
                    fieldType
                )
            }?.let {
                return it.simpleName.toString() // Getter is called 'isPropertyName'.
            }
        }

        // At last, check for regular getter.
        return methods.find {
            it.simpleName.toString() == "get$propertyNameCapitalized" && typeUtils.isSameType(it.returnType, fieldType)
        }?.simpleName?.toString()
    }

    companion object {
        @Suppress("unused") // Preserved for future use.
        private const val INDEX_MAX_VALUE_LENGTH_MAX = 450

        private const val BOXSTORE_FIELD_NAME = "__boxStore"
    }

}
