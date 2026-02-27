/*
 * ObjectBox Build Tools
 * Copyright (C) 2017-2024 ObjectBox Ltd.
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

@file:Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS", "unused", "UNUSED_PARAMETER")

package io.objectbox.gradle.transform

import io.objectbox.BoxStore
import io.objectbox.Cursor
import io.objectbox.EntityInfo
import io.objectbox.Property
import io.objectbox.annotation.Embedded
import io.objectbox.annotation.Entity
import io.objectbox.internal.CursorFactory
import io.objectbox.internal.IdGetter
import io.objectbox.relation.RelationInfo
import io.objectbox.relation.ToMany
import io.objectbox.relation.ToOne


@Entity
class EntityEmpty

@Entity
class EntityBoxStoreField {
    @JvmField // mimic generated Java code (transform adds field, not property with set/get)
    var __boxStore: BoxStore? = null
}

@Entity
class EntityToOne {
    val entityEmpty = ToOne<EntityEmpty>(this, null)
}

object EntityToOne_ : EntityInfo<EntityToOneLateInit>, EntityInfoStub<EntityToOneLateInit>() {
    @JvmField
    val entityEmpty = RelationInfo<EntityToOneLateInit, EntityEmpty>(null, null, null, null)
}

@Entity
class EntityToOneLateInit {
    lateinit var entityEmpty: ToOne<EntityEmpty>
}

object EntityToOneLateInit_ : EntityInfo<EntityToOneLateInit>, EntityInfoStub<EntityToOneLateInit>() {
    @JvmField
    val entityEmpty = RelationInfo<EntityToOneLateInit, EntityEmpty>(null, null, null, null)
}

@Entity
class EntityToOneSuffix {
    lateinit var entityEmptyToOne: ToOne<EntityEmpty>
}

object EntityToOneSuffix_ : EntityInfo<EntityToOneLateInit>, EntityInfoStub<EntityToOneLateInit>() {
    @JvmField
    val entityEmpty = RelationInfo<EntityToOneLateInit, EntityEmpty>(null, null, null, null)
}

@Entity
class EntityToMany {
    val entityEmpty = ToMany<EntityEmpty>(this, null)
    val entityEmptyList = listOf<EntityEmpty>()
}

object EntityToMany_ : EntityInfo<EntityToOneLateInit>, EntityInfoStub<EntityToOneLateInit>() {
    @JvmField
    val entityEmpty = RelationInfo<EntityToOneLateInit, EntityEmpty>(null, null, null, null)
}

@Entity
class EntityToManyLateInit {
    lateinit var entityEmpty: ToMany<EntityEmpty>
}

object EntityToManyLateInit_ : EntityInfo<EntityToOneLateInit>, EntityInfoStub<EntityToOneLateInit>() {
    @JvmField
    val entityEmpty = RelationInfo<EntityToManyLateInit, EntityEmpty>(null, null, null, null)
}

@Entity
class EntityToManySuffix {
    lateinit var entityEmptyToMany: ToMany<EntityEmpty>
}

object EntityToManySuffix_ : EntityInfo<EntityToOneLateInit>, EntityInfoStub<EntityToOneLateInit>() {
    @JvmField
    val entityEmpty = RelationInfo<EntityToOneLateInit, EntityEmpty>(null, null, null, null)
}

@Entity
class EntityToManyListLateInit {
    lateinit var typelessList: List<*>
    lateinit var entityEmpty: List<EntityEmpty>
}

object EntityToManyListLateInit_ : EntityInfo<EntityToOneLateInit>, EntityInfoStub<EntityToOneLateInit>() {
    @JvmField
    val entityEmpty = RelationInfo<EntityToOneLateInit, EntityEmpty>(null, null, null, null)
}

@Entity
class EntityTransientList {
    @Transient
    lateinit var transientList1: List<EntityEmpty>

    @io.objectbox.annotation.Transient
    lateinit var transientList2: List<EntityEmpty>

    lateinit var actualRelation: List<EntityEmpty>

    @Deprecated(message = "non-ObjectBox annotation")
    val dummyWithAlienAnnotation: Boolean = false
}

object EntityTransientList_ : EntityInfo<EntityToOneLateInit>, EntityInfoStub<EntityToOneLateInit>() {
    @JvmField
    val actualRelation = RelationInfo<EntityToOneLateInit, EntityEmpty>(null, null, null, null)
}

@Entity
class EntityMultipleCtors {
    lateinit var toMany: ToMany<EntityMultipleCtors>

    constructor()

    @Suppress("UNUSED_PARAMETER")
    constructor(someValue: String) : this()
}

object EntityMultipleCtors_ : EntityInfo<EntityMultipleCtors>, EntityInfoStub<EntityMultipleCtors>() {
    @JvmField
    val toMany = RelationInfo<EntityMultipleCtors, EntityMultipleCtors>(null, null, null, null)
}

class TestCursor : Cursor<EntityBoxStoreField>(null, 0, null, null) {
    override fun getId(entity: EntityBoxStoreField): Long = throw NotImplementedError("Stub for testing")
    override fun put(entity: EntityBoxStoreField): Long = throw NotImplementedError("Stub for testing")
    private fun attachEntity(entity: EntityBoxStoreField) {}
}

class CursorExistingImplReads : Cursor<EntityBoxStoreField>(null, 0, null, null) {
    override fun getId(entity: EntityBoxStoreField): Long = throw NotImplementedError("Stub for testing")
    override fun put(entity: EntityBoxStoreField): Long = throw NotImplementedError("Stub for testing")
    private fun attachEntity(entity: EntityBoxStoreField) {
        println(entity.__boxStore)
    }
}

class CursorExistingImplWrites : Cursor<EntityBoxStoreField>(null, 0, null, null) {
    override fun getId(entity: EntityBoxStoreField): Long = throw NotImplementedError("Stub for testing")
    override fun put(entity: EntityBoxStoreField): Long = throw NotImplementedError("Stub for testing")
    private fun attachEntity(entity: EntityBoxStoreField) {
        entity.__boxStore = super.boxStoreForEntities!!
    }
}

class JustCopyMe

// ─────────────────────────────────────────────────────────────────────────────────────────
// @Embedded fixtures — value-object container whose fields are flattened into the owning
// entity's table. Unlike ToOne/ToMany there's no relation type signal (container is just a
// POJO), so the transformer probes for the @Embedded annotation directly.
// ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * The embedded POJO. NOT an @Entity. Transformer must load this into its class pool to walk
 * its fields and derive synthetic flat-field names + types.
 *
 * `@JvmField` → Kotlin emits a real public field (not a property with get/set), matching
 * what the transformer's CtClass field walk will see AND what the generated hydration body
 * will access via `entity.price.currency = ...`.
 */
class MoneyEmbeddable {
    @JvmField var currency: String? = null
    @JvmField var amount: Long = 0L
}

/**
 * Entity with a single @Embedded container field. After the entity transform, this class
 * should have two additional `transient` fields: `priceCurrency: String` and `priceAmount: long`,
 * named per the default-prefix rule (fieldName + capFirst(inner)). JNI sets these directly
 * by property-name when reading from the database.
 */
@Entity
class EntityEmbedded {
    @Embedded
    @JvmField var price: MoneyEmbeddable? = null
}

/**
 * Fake EntityInfo for [EntityEmbedded]. The transformer cross-validates each computed
 * synthetic name against the field list here — if `priceCurrency` or `priceAmount` were
 * absent, the transformer must fail-loud (APT and transformer disagreed on naming).
 *
 * Field values are never accessed by the transformer (it only checks field existence by
 * name via `entityInfoCtClass.fields.any { it.name == syntheticName }`), so the `Property`
 * instances here are construct-time nulls — same approach as the RelationInfo fixtures above.
 */
object EntityEmbedded_ : EntityInfo<EntityEmbedded>, EntityInfoStub<EntityEmbedded>() {
    @JvmField val priceCurrency = Property<EntityEmbedded>(null, 1, 2, String::class.java, "priceCurrency")
    @JvmField val priceAmount = Property<EntityEmbedded>(null, 2, 3, Long::class.javaPrimitiveType, "priceAmount")
}

/**
 * Cursor for [EntityEmbedded]. The `attachEmbedded` override is EMPTY (compiles to 1-byte
 * RETURN, same invariant as `attachEntity`) — the transformer injects the null-guard and
 * hydration body. Body matches M2.3's generated stub.
 *
 * NB: The parameter is **nullable** here because the base hook declares `@Nullable T` and
 * Kotlin (API-level 1.4, as used by this module) requires the override signature to match
 * exactly — it won't accept a non-null refinement of a `@Nullable`-annotated Java param.
 * The M2.3-generated Java stub (`public void attachEmbedded(Bill entity)`) has no Kotlin-side
 * nullability, so javac compiles it fine; only this Kotlin fixture needs the `?`. At the
 * bytecode level the descriptor is identical either way — the transformer sees `(LEntityEmbedded;)V`
 * and injects `if ($1 == null) return;` as the first statement, so null-safety is preserved
 * at runtime regardless of the source-level nullability annotation.
 */
class EntityEmbeddedCursor : Cursor<EntityEmbedded>(null, 0, null, null) {
    override fun getId(entity: EntityEmbedded): Long = throw NotImplementedError("Stub for testing")
    override fun put(entity: EntityEmbedded): Long = throw NotImplementedError("Stub for testing")
    override fun attachEmbedded(entity: EntityEmbedded?) {}
}

/**
 * Variant with an explicit `prefix = ""` — inner field names are used AS-IS (no prefix).
 * Tests that the transformer reads the annotation attribute correctly and distinguishes
 * the empty-string prefix from the absent-attribute case (= default = USE_FIELD_NAME = "\0").
 */
@Entity
class EntityEmbeddedNoPrefix {
    @Embedded(prefix = "")
    @JvmField var bal: MoneyEmbeddable? = null
}

/** Matching EntityInfo: synthetic names are just `currency` / `amount` (no prefix). */
object EntityEmbeddedNoPrefix_ : EntityInfo<EntityEmbeddedNoPrefix>, EntityInfoStub<EntityEmbeddedNoPrefix>() {
    @JvmField val currency = Property<EntityEmbeddedNoPrefix>(null, 1, 2, String::class.java, "currency")
    @JvmField val amount = Property<EntityEmbeddedNoPrefix>(null, 2, 3, Long::class.javaPrimitiveType, "amount")
}

// ─────────────────────────────────────────────────────────────────────────────────────────
// Nested @Embedded fixtures (P2.4-T) — two-level chain: Entity → Money → Currency.
// Tests compound-prefix flattening + parent-before-child hydration ordering.
// ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * Innermost POJO in the nested chain. Pure leaf fields — no further `@Embedded`. The
 * transformer's recursive walk bottoms out here.
 */
class CurrencyEmbeddable {
    @JvmField var code: String? = null
    @JvmField var rate: Long = 0L
}

/**
 * Middle POJO: one direct scalar leaf (`amount`) PLUS one nested `@Embedded` (`cur`).
 *
 * The transformer must treat `cur` as a CONTAINER-to-recurse-into, NOT as a leaf to
 * flatten. A single-level implementation would incorrectly try to produce a synthetic
 * field `priceCur: CurrencyEmbeddable` (i.e. treat the nested POJO as an opaque property
 * type) and would then fail cross-validation against `EntityEmbeddedNested_` — which is
 * the desired RED behaviour before the recursion is implemented.
 */
class MoneyNestedEmbeddable {
    @JvmField var amount: Long = 0L
    @Embedded
    @JvmField var cur: CurrencyEmbeddable? = null
}

/**
 * Entity with a 2-level nested chain. After transform, should have THREE synthetic
 * transient fields (one direct leaf + two nested leaves), all on the entity:
 *
 *   `priceAmount: long`       ← root-level leaf  (price.amount)
 *   `priceCurCode: String`    ← nested leaf      (price.cur.code)
 *   `priceCurRate: long`      ← nested leaf      (price.cur.rate)
 *
 * Compound-prefix rule: root `price` + nested `cur` → effective prefix `priceCur`, then
 * `priceCur` + leaf `code` → `priceCurCode`. MUST match what the APT produced — the
 * `Entity_` cross-validation below locks the expected names.
 */
@Entity
class EntityEmbeddedNested {
    @Embedded
    @JvmField var price: MoneyNestedEmbeddable? = null
}

/**
 * EntityInfo with the APT's compound-flattened synthetic names. A transformer that
 * doesn't recurse will compute `priceCur` (treating `cur` as a leaf), fail to find it
 * here, and throw the naming-mismatch error — proving RED.
 */
object EntityEmbeddedNested_ : EntityInfo<EntityEmbeddedNested>, EntityInfoStub<EntityEmbeddedNested>() {
    @JvmField val priceAmount = Property<EntityEmbeddedNested>(null, 1, 2, Long::class.javaPrimitiveType, "priceAmount")
    @JvmField val priceCurCode = Property<EntityEmbeddedNested>(null, 2, 3, String::class.java, "priceCurCode")
    @JvmField val priceCurRate = Property<EntityEmbeddedNested>(null, 3, 4, Long::class.javaPrimitiveType, "priceCurRate")
}

/**
 * Cursor stub for the nested entity. The transformer must inject a CHAINED hydration body:
 * root container hydrated + assigned FIRST (so `$1.price` is non-null), then nested container
 * hydrated + assigned via `$1.price.cur = __emb` (not `$1.cur = __emb` — `cur` is NOT a field
 * on the entity, it's on the intermediate POJO).
 */
class EntityEmbeddedNestedCursor : Cursor<EntityEmbeddedNested>(null, 0, null, null) {
    override fun getId(entity: EntityEmbeddedNested): Long = throw NotImplementedError("Stub for testing")
    override fun put(entity: EntityEmbeddedNested): Long = throw NotImplementedError("Stub for testing")
    override fun attachEmbedded(entity: EntityEmbeddedNested?) {}
}

open class EntityInfoStub<T> : EntityInfo<T> {
    override fun getEntityName(): String = throw NotImplementedError("Stub for testing")
    override fun getDbName(): String = throw NotImplementedError("Stub for testing")
    override fun getEntityClass(): Class<T> = throw NotImplementedError("Stub for testing")
    override fun getEntityId(): Int = throw NotImplementedError("Stub for testing")
    override fun getAllProperties(): Array<Property<T>> = throw NotImplementedError("Stub for testing")
    override fun getIdProperty(): Property<T> = throw NotImplementedError("Stub for testing")
    override fun getIdGetter(): IdGetter<T> = throw NotImplementedError("Stub for testing")
    override fun getCursorFactory(): CursorFactory<T> = throw NotImplementedError("Stub for testing")
}
