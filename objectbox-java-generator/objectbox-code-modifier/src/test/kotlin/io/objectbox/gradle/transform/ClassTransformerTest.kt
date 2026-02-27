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

package io.objectbox.gradle.transform

import javassist.ClassPool
import javassist.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.reflect.KClass

class ClassTransformerTest : AbstractTransformTest() {

    // ── Bytecode-inspection helper ───────────────────────────────────────────────────────
    // Like testTransformOrCopy() but invokes [inspect] BEFORE the temp dir is deleted, giving
    // the test a chance to crack open the output .class files and assert on injected fields /
    // rewritten method bodies. The existing helper only exposes stats counters — sufficient for
    // relation transforms (where correctness is observable via the counter + integration tests),
    // but @Embedded needs field-name + type + modifier verification at the bytecode level.
    private fun testTransformAndInspect(
        kClasses: List<KClass<*>>,
        expectedTransformed: Int,
        expectedCopied: Int,
        inspect: (stats: ClassTransformerStats, outDir: File) -> Unit,
    ) {
        val tempDir = File.createTempFile(this.javaClass.name, "").apply { delete(); mkdir() }
        try {
            val probed = kClasses.map { probeClass(it, tempDir) }
            val stats = ClassTransformer(debug = true).transformOrCopyClasses(probed)
            assertEquals("transformed count", expectedTransformed, stats.countTransformed)
            assertEquals("copied count", expectedCopied, stats.countCopied)
            inspect(stats, tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Loads a single `.class` file from [outDir] into a fresh pool for inspection (isolated from test classpath). */
    private fun loadOutputCtClass(outDir: File, fqn: String): javassist.CtClass {
        val classFile = File(outDir, fqn.replace('.', '/') + ".class")
        assertTrue("expected output class file at ${classFile.path}", classFile.exists())
        // Fresh pool with only java.* on the path — we want the OUTPUT bytes, not whatever
        // javac put in build/classes/kotlin/testFixtures (that's the PRE-transform version).
        val pool = ClassPool(false)
        pool.appendClassPath(PrefixedClassPath("java.", java.lang.Object::class.java))
        return classFile.inputStream().use { pool.makeClass(it) }
    }

    @Test
    fun testClassInPool() {
        val classPool = ClassTransformer.Context(emptyList()).classPool

        // Ensure we have the real java.lang.Object (a fake would have itself as superclass)
        assertNull(classPool.get("java.lang.Object").superclass)

        val toOne = classPool.get(ClassConst.toOne)
        val constructorSignature = toOne.constructors.single().signature
        // Verify its not the test fake
        assertEquals("(Ljava/lang/Object;Lio/objectbox/relation/RelationInfo;)V", constructorSignature)
        assertTrue(toOne.declaredFields.isNotEmpty())
        assertTrue(toOne.declaredMethods.isNotEmpty())
    }

    @Test
    fun entity_isTransformed() {
        val classes = listOf(ExampleEntity::class, ExampleEntity_::class)
        val (stats) = testTransformOrCopy(classes, 1, 1)
        // Data class no-param constructor and special Kotlin constructor call other constructor,
        // should not be transformed.
        assertEquals(1, stats.constructorsCheckedForTransform)
        assertEquals(1, stats.boxStoreFieldsAdded)
        assertEquals(1, stats.toOnesFound)
        assertEquals(2, stats.toManyFound)
        assertEquals(1, stats.toOnesInitializerAdded)
        // toManyProperty is already initialized, should only init toManyListProperty
        assertEquals(1, stats.toManyInitializerAdded)
    }

    @Test
    fun testTransformEntity_toOne() {
        val classes = listOf(EntityToOne::class, EntityToOne_::class, EntityEmpty::class)
        val (stats) = testTransformOrCopy(classes, 1, 2)
        assertEquals(0, stats.toManyFound)
        assertEquals(1, stats.toOnesFound)
        assertEquals(0, stats.toOnesInitializerAdded)
    }

    @Test
    fun testTransformEntity_toOneLateInit() {
        val classes = listOf(EntityToOneLateInit::class, EntityToOneLateInit_::class, EntityEmpty::class)
        val (stats) = testTransformOrCopy(classes, 1, 2)
        assertEquals(0, stats.toManyFound)
        assertEquals(1, stats.toOnesFound)
        assertEquals(1, stats.toOnesInitializerAdded)
    }

    @Test
    fun testTransformEntity_toOneSuffix() {
        val classes = listOf(EntityToOneSuffix::class, EntityToOneSuffix_::class, EntityEmpty::class)
        val (stats) = testTransformOrCopy(classes, 1, 2)
        assertEquals(0, stats.toManyFound)
        assertEquals(1, stats.toOnesFound)
        assertEquals(1, stats.toOnesInitializerAdded)
    }

    @Test
    fun testTransformEntity_toMany() {
        val classes = listOf(EntityToMany::class, EntityToMany_::class, EntityEmpty::class)
        val (stats) = testTransformOrCopy(classes, 1, 2)
        assertEquals(1, stats.boxStoreFieldsAdded)
        assertEquals(2, stats.toManyFound)
        assertEquals(0, stats.toManyInitializerAdded)
    }

    @Test
    fun testTransformEntity_toManyLateInit() {
        val classes = listOf(EntityToManyLateInit::class, EntityToManyLateInit_::class, EntityEmpty::class)
        val (stats) = testTransformOrCopy(classes, 1, 2)
        assertEquals(1, stats.boxStoreFieldsAdded)
        assertEquals(0, stats.toOnesFound)
        assertEquals(1, stats.toManyFound)
        assertEquals(1, stats.toManyInitializerAdded)
    }

    @Test
    fun testTransformEntity_toManySuffix() {
        val classes = listOf(EntityToManySuffix::class, EntityToManySuffix_::class, EntityEmpty::class)
        val (stats) = testTransformOrCopy(classes, 1, 2)
        assertEquals(1, stats.boxStoreFieldsAdded)
        assertEquals(0, stats.toOnesFound)
        assertEquals(1, stats.toManyFound)
        assertEquals(1, stats.toManyInitializerAdded)
    }

    @Test
    fun testTransformEntity_toManyListLateInit() {
        val classes = listOf(EntityToManyListLateInit::class, EntityToManyListLateInit_::class, EntityEmpty::class)
        val (stats) = testTransformOrCopy(classes, 1, 2)
        assertEquals(1, stats.boxStoreFieldsAdded)
        assertEquals(0, stats.toOnesFound)
        assertEquals(1, stats.toManyFound)
        assertEquals(1, stats.toManyInitializerAdded)
    }

    @Test
    fun testTransformEntity_transientList() {
        val classes = listOf(EntityTransientList::class, EntityTransientList_::class, EntityEmpty::class)
        val (stats) = testTransformOrCopy(classes, 1, 2)
        assertEquals(1, stats.boxStoreFieldsAdded)
        assertEquals(0, stats.toOnesFound)
        assertEquals(1, stats.toManyFound)
        assertEquals(1, stats.toManyInitializerAdded)
    }


    @Test
    fun doNotTransform_constructorCallingConstructor() {
        val classes = listOf(EntityMultipleCtors::class, EntityMultipleCtors_::class)
        val (stats) = testTransformOrCopy(classes, 1, 1)
        assertEquals(1, stats.toManyInitializerAdded) // only ctor calling super should be transformed
        assertEquals(1, stats.boxStoreFieldsAdded)
        assertEquals(0, stats.toOnesFound)
        assertEquals(1, stats.toManyFound)
    }

    @Test
    fun cursor_isTransformed() {
        val classes = listOf(TestCursor::class, EntityBoxStoreField::class)
        testTransformOrCopy(classes, 1, 1)
    }

    @Test
    fun cursorAttachReads_isTransformed() {
        val classes = listOf(CursorExistingImplReads::class, EntityBoxStoreField::class)
        testTransformOrCopy(classes, 1, 1)
    }

    @Test
    fun cursorAttachWrites_notTransformed() {
        val classes = listOf(CursorExistingImplWrites::class, EntityBoxStoreField::class)
        testTransformOrCopy(classes, 0, 2)
    }

    @Test
    fun testCopy() {
        val result = testTransformOrCopy(JustCopyMe::class, 0, 1)
        val copiedFile = result.second.single()
        val expectedPath = '/' + JustCopyMe::class.qualifiedName!!.replace('.', '/') + ".class"
        val actualPath = copiedFile.absolutePath.replace('\\', '/')
        assertTrue(actualPath, actualPath.endsWith(expectedPath))
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // @Embedded transforms — M3.3 (entity: inject synthetic flat fields) + M3.4 (cursor: inject
    // attachEmbedded() hydration body). Unlike relations, these transforms are bytecode-verifiable
    // in isolation: the injected fields/body can be asserted directly without a native DB round-trip.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Default-prefix case: `@Embedded MoneyEmbeddable price` → synthetic flat fields
     * `priceCurrency: String` and `priceAmount: long`, both `transient` (so they're excluded from
     * Java serialization + ObjectBox's own property-scan of the ENTITY — the synthetic fields are
     * an implementation detail of the read path, NOT user-visible schema).
     *
     * Probed-class list MUST include:
     *  - `EntityEmbedded` — the subject of the transform.
     *  - `EntityEmbedded_` — cross-validation target: transformer verifies each computed synthetic
     *    name matches a field here (fail-loud on APT/transformer naming drift).
     *  - `MoneyEmbeddable` — the container POJO. NOT an entity or EntityInfo, so the transformer's
     *    pre-load pass (which only loads isEntity/isEntityInfo into the class pool) skips it. The
     *    embedded-discovery step loads it on-demand by matching the @Embedded field's descriptor
     *    to a probed-class FQN. Without this, the walk of container.declaredFields would fail.
     */
    @Test
    fun testTransformEntity_embedded_injectsSyntheticFlatFields() {
        val classes = listOf(EntityEmbedded::class, EntityEmbedded_::class, MoneyEmbeddable::class)
        testTransformAndInspect(classes, expectedTransformed = 1, expectedCopied = 2) { stats, outDir ->
            // ── Stats: one container discovered, two inner fields flattened. ────────────────
            assertEquals(1, stats.embeddedContainersFound)
            assertEquals(2, stats.embeddedSyntheticFieldsAdded)
            // Orthogonality: no relations → no __boxStore injection, no ToOne/ToMany work.
            assertEquals(0, stats.boxStoreFieldsAdded)
            assertEquals(0, stats.toOnesFound)
            assertEquals(0, stats.toManyFound)

            // ── Bytecode: synthetic fields landed on the entity with correct shape. ─────────
            val ct = loadOutputCtClass(outDir, EntityEmbedded::class.qualifiedName!!)
            val byName = ct.declaredFields.associateBy { it.name }

            // The original container field survives (transformer ADDS fields, never removes).
            assertTrue("container field `price` must survive", byName.containsKey("price"))

            val priceCurrency = byName["priceCurrency"]
                ?: fail("synthetic field `priceCurrency` not injected") as Nothing
            assertTrue("must be transient (JNI-only, not a user-visible property)",
                Modifier.isTransient(priceCurrency.modifiers))
            assertFalse("must NOT be static", Modifier.isStatic(priceCurrency.modifiers))
            // Descriptor for java.lang.String. Asserting the raw descriptor avoids pulling the
            // type through the class pool (which would need String on the path — fine here, but
            // descriptor check is closer to what JNI actually sees when matching by Property.name).
            assertEquals("Ljava/lang/String;", priceCurrency.fieldInfo.descriptor)

            val priceAmount = byName["priceAmount"]
                ?: fail("synthetic field `priceAmount` not injected") as Nothing
            assertTrue(Modifier.isTransient(priceAmount.modifiers))
            // Primitive long — descriptor is `J`. Critically NOT `Ljava/lang/Long;`: the APT
            // preserved the inner field's primitiveness (MoneyEmbeddable.amount is `long`), so JNI
            // will set this via a primitive-typed PUTFIELD, not an object reference.
            assertEquals("J", priceAmount.fieldInfo.descriptor)
        }
    }

    /**
     * Explicit-empty-prefix case: `@Embedded(prefix="") MoneyEmbeddable bal` → synthetic names
     * are the bare inner names (`currency`, `amount`). Tests that the transformer:
     *  1. Actually READS the `prefix` annotation member (vs assuming default).
     *  2. Distinguishes member-absent (→ USE_FIELD_NAME sentinel) from member-present-but-empty.
     *     In bytecode, `@Embedded` with no explicit prefix omits the member entirely, so
     *     `Annotation.getMemberValue("prefix")` returns null — the transformer must interpret
     *     null as the sentinel. `@Embedded(prefix="")` includes the member with value `""`.
     */
    @Test
    fun testTransformEntity_embeddedNoPrefix_usesBareInnerNames() {
        val classes = listOf(EntityEmbeddedNoPrefix::class, EntityEmbeddedNoPrefix_::class, MoneyEmbeddable::class)
        testTransformAndInspect(classes, expectedTransformed = 1, expectedCopied = 2) { stats, outDir ->
            assertEquals(1, stats.embeddedContainersFound)
            assertEquals(2, stats.embeddedSyntheticFieldsAdded)

            val ct = loadOutputCtClass(outDir, EntityEmbeddedNoPrefix::class.qualifiedName!!)
            val names = ct.declaredFields.map { it.name }.toSet()

            // Bare inner names — NOT `balCurrency` / `balAmount`.
            assertTrue("synthetic `currency` should be injected (no prefix)", names.contains("currency"))
            assertTrue("synthetic `amount` should be injected (no prefix)", names.contains("amount"))
            // Negative: ensure we didn't ALSO emit the default-prefix variant (i.e. the
            // transformer didn't ignore the explicit "" and fall through to field-name prefix).
            assertFalse("default-prefix name must NOT be emitted", names.contains("balCurrency"))
            assertFalse("default-prefix name must NOT be emitted", names.contains("balAmount"))
        }
    }

    /**
     * M3.4 — cursor transform: the `attachEmbedded()` stub must get its body REPLACED with a
     * hydration sequence (null-guard + `new Container()` + copy synthetic-flat → container.inner
     * + assign container to entity field).
     *
     * This test drives the END-TO-END transform (entity gets synthetic fields, cursor gets
     * hydration body) and asserts on the cursor output bytecode:
     *   - The `attachEmbedded` method body is no longer 1-byte RETURN (it has real opcodes).
     *   - The constant pool references `MoneyEmbeddable.<init>` (the `new MoneyEmbeddable()`).
     *   - The body does PUTFIELD to `EntityEmbedded.price` (the final container assignment).
     *   - The body reads `EntityEmbedded.priceCurrency` / `priceAmount` (the synthetic flat
     *     fields M3.3 injected onto the entity — proving phase-A's output is phase-B's input).
     *
     * Why opcode/constpool inspection rather than decompiled source matching? Javassist's
     * `setBody()` emits bytecode directly — there's no "generated source" to grep. The constant
     * pool IS the ground truth for "what fields/methods does this bytecode reference".
     */
    @Test
    fun testTransformCursor_embedded_injectsHydrationBody() {
        // Full closure: entity + Entity_ + container POJO + cursor with empty attachEmbedded stub.
        // Transformer phase order: entity first (injects syntheticFields, stashes metadata on
        // Context) → cursor second (looks up metadata by entity FQN from attachEmbedded signature).
        val classes = listOf(
            EntityEmbedded::class,
            EntityEmbedded_::class,
            MoneyEmbeddable::class,
            EntityEmbeddedCursor::class,
        )
        // 2 transformed (entity: synthetic fields; cursor: attachEmbedded body), 2 copied
        // (Entity_ + POJO are passive — no rewriting).
        testTransformAndInspect(classes, expectedTransformed = 2, expectedCopied = 2) { stats, outDir ->
            // ── Stats: both phases fired. ────────────────────────────────────────────────────
            assertEquals(1, stats.embeddedContainersFound)
            assertEquals(2, stats.embeddedSyntheticFieldsAdded)
            assertEquals(1, stats.embeddedAttachBodiesInjected)

            // ── Cursor bytecode: body is no longer a stub. ──────────────────────────────────
            val ct = loadOutputCtClass(outDir, EntityEmbeddedCursor::class.qualifiedName!!)
            // The output has TWO `attachEmbedded` methods: the real override (which the
            // transformer rewrote) and the compiler-generated bridge (`(Ljava/lang/Object;)V`,
            // ACC_BRIDGE + ACC_SYNTHETIC) that casts-and-delegates. The bridge body is untouched
            // — still the original cast+invokevirtual. We want the real one.
            val attach = ct.declaredMethods.single {
                it.name == "attachEmbedded" && (it.methodInfo.accessFlags and 0x0040) == 0 // !ACC_BRIDGE
            }
            val bodyBytes = attach.methodInfo.codeAttribute.code
            assertTrue(
                "attachEmbedded body must be rewritten (was ${bodyBytes.size} bytes: ${bodyBytes.joinToString { "%02x".format(it) }})",
                // A non-trivial body — null-guard + new + dup + invokespecial + ~4 field ops
                // is easily 30+ bytes. We just want "not the 1-byte RETURN stub".
                bodyBytes.size > 1,
            )

            // ── Constant pool: references we expect the hydration body to emit. ─────────────
            // The const pool is the ground-truth "what does this bytecode touch" ledger. If the
            // body does `new MoneyEmbeddable()`, the pool MUST have a Methodref for its <init>.
            // If the body reads $1.priceCurrency, the pool MUST have a Fieldref for it.
            val constPool = ct.classFile.constPool
            val poolDump = (1 until constPool.size).mapNotNull { idx ->
                runCatching {
                    // Try each ref type — most indices aren't Fieldref/Methodref, so catch+null.
                    val cls = constPool.getFieldrefClassName(idx)
                    val name = constPool.getFieldrefName(idx)
                    "Fieldref: $cls.$name"
                }.getOrElse {
                    runCatching {
                        val cls = constPool.getMethodrefClassName(idx)
                        val name = constPool.getMethodrefName(idx)
                        "Methodref: $cls.$name"
                    }.getOrNull()
                }
            }

            // `new MoneyEmbeddable()` → Methodref to its <init>.
            assertTrue(
                "constpool should reference MoneyEmbeddable.<init> (new-instance), was: $poolDump",
                poolDump.any { it.endsWith("${MoneyEmbeddable::class.qualifiedName}.<init>") },
            )
            // `$1.priceCurrency` / `$1.priceAmount` → Fieldrefs on the entity (the synthetic
            // flat fields that M3.3 injected). This is the CRITICAL cross-phase assertion:
            // phase B's hydration body reads phase A's injected fields by NAME.
            assertTrue(
                "constpool should reference EntityEmbedded.priceCurrency (synthetic flat field), was: $poolDump",
                poolDump.any { it.endsWith("${EntityEmbedded::class.qualifiedName}.priceCurrency") },
            )
            assertTrue(
                "constpool should reference EntityEmbedded.priceAmount, was: $poolDump",
                poolDump.any { it.endsWith("${EntityEmbedded::class.qualifiedName}.priceAmount") },
            )
            // `$1.price = __emb` → Fieldref to the container field on the entity.
            assertTrue(
                "constpool should reference EntityEmbedded.price (container assignment), was: $poolDump",
                poolDump.any { it.endsWith("${EntityEmbedded::class.qualifiedName}.price") },
            )
            // `__emb.currency = ...` / `__emb.amount = ...` → Fieldrefs on the container.
            assertTrue(
                "constpool should reference MoneyEmbeddable.currency (inner field write), was: $poolDump",
                poolDump.any { it.endsWith("${MoneyEmbeddable::class.qualifiedName}.currency") },
            )
            assertTrue(
                "constpool should reference MoneyEmbeddable.amount, was: $poolDump",
                poolDump.any { it.endsWith("${MoneyEmbeddable::class.qualifiedName}.amount") },
            )
        }
    }

    /**
     * Coexistence: an entity with BOTH relations AND `@Embedded` (hypothetical, but the
     * transformer must not assume mutual exclusivity). The cursor would have BOTH `attachEntity`
     * (relation __boxStore wiring) AND `attachEmbedded` (hydration) stubs. Both must be
     * transformed, and `writeFile()` must happen exactly once (after both rewrites).
     *
     * Here we test the DEGENERATE case: cursor with ONLY `attachEmbedded` (no `attachEntity`).
     * The existing `transformCursor()` returns `false` when `attachEntity` is absent — if that
     * early-return survives, the cursor is COPIED, not transformed, even though it has an
     * `attachEmbedded` stub needing injection. This test guards against that regression.
     *
     * (The fixture `EntityEmbeddedCursor` has NO `attachEntity` — only getId/put/attachEmbedded.
     * That matches M2.3's generated cursor shape: `attachEntity` is only emitted when the entity
     * has relations AND doesn't already have a `__boxStore` field.)
     */
    @Test
    fun testTransformCursor_embedded_cursorWithoutAttachEntityStillTransformed() {
        val classes = listOf(
            EntityEmbedded::class, EntityEmbedded_::class, MoneyEmbeddable::class, EntityEmbeddedCursor::class,
        )
        // Cursor MUST be in the transformed set (not copied). If this fails with
        // "transformed count expected 2 but was 1", the attachEmbedded path isn't wired.
        val (stats, _) = testTransformOrCopy(classes, expectedTransformed = 2, expectedCopied = 2)
        assertEquals(1, stats.embeddedAttachBodiesInjected)
    }

    /**
     * Negative: if `Entity_` is absent from the probed-class set (i.e. APT didn't run, or the
     * class list is misconfigured), the transformer has nothing to cross-validate against and
     * must fail loudly rather than silently inject unvalidated names.
     *
     * Mirrors the existing relation-field behaviour in [ClassTransformer] — see
     * `findRelationNameInEntityInfo()` which throws when `Entity_` can't be loaded from the pool.
     */
    @Test
    fun testTransformEntity_embedded_failsWhenEntityInfoMissing() {
        // Deliberately omit EntityEmbedded_ — cross-validation has nothing to check against.
        val classes = listOf(EntityEmbedded::class, MoneyEmbeddable::class)
        val tempDir = File.createTempFile(this.javaClass.name, "").apply { delete(); mkdir() }
        try {
            val probed = classes.map { probeClass(it, tempDir) }
            try {
                ClassTransformer(debug = true).transformOrCopyClasses(probed)
                fail("expected TransformException (EntityInfo missing for cross-validation)")
            } catch (e: TransformException) {
                // Root cause may be wrapped by transformEntityAndBases' catch-and-rethrow — check
                // the full chain message, not just the top-level.
                val fullMessage = generateSequence<Throwable>(e) { it.cause }.joinToString(" / ") { it.message ?: "" }
                assertTrue(
                    "error should name the missing Entity_ class, was: $fullMessage",
                    fullMessage.contains("EntityEmbedded_")
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

}