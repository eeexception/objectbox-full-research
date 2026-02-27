/*
 * ObjectBox Build Tools
 * Copyright (C) 2026 ObjectBox Ltd.
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
package io.objectbox.generator.model

import io.objectbox.generator.TextUtil

/**
 * Model for an `@Embedded` field: a value-object container whose fields are flattened
 * into the owning [Entity] as synthetic [Property] entries.
 *
 * Unlike [ToOne], an embedded container is **not** a relation — no foreign key, no target
 * entity. Each inner field of the embedded type becomes a real database column on the
 * owning entity (stored flat, read flat by JNI into transformer-injected fields, then
 * hydrated into the container by the generated `Cursor.attachEmbedded()` override).
 *
 * ### Lifecycle
 * 1. Processor creates this during field parsing (`Properties.kt:parseEmbedded()`),
 *    resolving the prefix and walking the embedded type's fields.
 * 2. For each inner field, a synthetic `Property` is added to the entity with a prefixed
 *    name and linked back here via [Property.getEmbeddedOrigin] / [addProperty].
 * 3. Downstream codegen (M2) uses [hasEmbedded][Entity.hasEmbedded] +
 *    [getEmbeddedFields][Entity.getEmbeddedFields] to:
 *    - emit a null-guarded `put()` read path that dereferences via [containerValueExpression]
 *    - emit an `attachEmbedded()` override that constructs the container and copies
 *      transformer-injected flat fields into it
 *
 * ### Design notes
 * - Synthetic properties are **NOT** virtual (`Property.isVirtual() == false`). The
 *   bytecode transformer injects real `transient` fields with matching names onto the
 *   entity class; JNI sets them directly by `Property.name`. Virtual would make native
 *   skip the set, breaking reads.
 * - The container field *name* is tracked in the entity's unique-name set (prevents e.g.
 *   `@Embedded Money price` colliding with a regular `String price` property), but the
 *   container itself is **not** a `Property` — it has no DB column, no model ID.
 *
 * @param name the Java field name on the entity, e.g. `price` for `@Embedded Money price`
 * @param isFieldAccessible `true` if the container field is not private (can use
 *   `entity.price` directly); `false` requires getter/setter access (`entity.getPrice()`)
 * @param prefix the resolved synthetic-name prefix (after sentinel resolution — never
 *   `@Embedded.USE_FIELD_NAME`). Empty string means "no prefix at all".
 * @param typeFullyQualifiedName e.g. `com.example.Money`; used for imports in generated code
 * @param typeSimpleName e.g. `Money`; used for local var declarations in generated code
 */
class EmbeddedField(
    val name: String,
    val isFieldAccessible: Boolean,
    val prefix: String,
    val typeFullyQualifiedName: String,
    val typeSimpleName: String
) : HasParsedElement {

    /**
     * Synthetic [Property] entries produced by flattening this embedded container.
     * Populated during processor field-walk; each property's [Property.getEmbeddedOrigin]
     * points back to this instance.
     */
    val properties: MutableList<Property> = mutableListOf()

    private var parsedElement: Any? = null

    /**
     * Expression for reading the container from an entity instance, relative to `entity.`.
     * E.g. `price` (field-accessible) or `getPrice()` (private field, conventional getter).
     *
     * Mirrors [ToOne.toOneValueExpression]. Used by the `put()` code generator to emit
     * `entity.price.currency` (or null-guarded variants thereof).
     */
    val containerValueExpression: String
        get() = if (isFieldAccessible) name else "get" + TextUtil.capFirst(name) + "()"

    /**
     * Expression for writing the container back to the entity, relative to `entity.` and given
     * a value expression.
     *
     * E.g. `price = {value}` (field-accessible) or `setPrice({value})`.
     * Used by `attachEmbedded()` codegen (M2.3) to assign the hydrated container.
     */
    fun containerSetExpression(value: String): String =
        if (isFieldAccessible) "$name = $value" else "set${TextUtil.capFirst(name)}($value)"

    /**
     * Local variable name used in generated `put()` body to hold the hoisted container reference.
     *
     * Pattern: `__emb_<name>` — follows the generator's double-underscore convention for
     * internals (`__id`, `__assignedId`) and is distinct from any user field (Java identifiers
     * can't start with `__emb_` and still collide with a user's `price` field).
     *
     * Hoisting once per container (rather than `entity.price` inline at each use site) avoids
     * repeated `entity.price != null` evaluations and — critically — ensures the null-guard
     * and the value-read see the SAME reference if user code mutates `entity.price` concurrently
     * (unlikely inside a sync `put()`, but correctness-by-construction).
     */
    val localVarName: String
        get() = "__emb_$name"

    /**
     * Registers a synthetic property as produced by this embedded field.
     * Call after [Entity.addProperty] — the property is already in the entity's list;
     * this secondary registration enables M2 codegen to iterate per-container.
     */
    fun addProperty(property: Property) {
        properties.add(property)
    }

    /**
     * Builds the synthetic property name for an inner field of the embedded type,
     * applying this container's [prefix].
     *
     * @param innerFieldName the embedded type's field name, e.g. `currency`
     * @return e.g. `priceCurrency` (prefix = `price`), `currency` (prefix = `""`)
     */
    fun syntheticNameFor(innerFieldName: String): String {
        return if (prefix.isEmpty()) innerFieldName else prefix + TextUtil.capFirst(innerFieldName)
    }

    override fun getParsedElement(): Any? = parsedElement

    override fun setParsedElement(parsedElement: Any) {
        this.parsedElement = parsedElement
    }

    override fun toString(): String =
        "EmbeddedField '$name' ($typeSimpleName) with ${properties.size} properties"
}
