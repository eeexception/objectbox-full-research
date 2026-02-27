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

import io.objectbox.gradle.transform.EmbeddedTransform.EmbeddedContainer
import io.objectbox.gradle.transform.EmbeddedTransform.SyntheticField
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EmbeddedTransform.buildAttachEmbeddedBody] — pure source-string assertions,
 * no bytecode round-trip. Complements the end-to-end tests in [ClassTransformerTest] which
 * verify the Javassist-compiled output.
 *
 * Why a separate test class: the body builder is a pure function of the [EmbeddedContainer]
 * list. Testing the source string directly gives pinpoint failure locations ("missing `else`
 * branch" vs a Javassist compile error at the end-to-end level) and is FAST (no classloading,
 * no probing, no file I/O). The end-to-end tests catch integration issues; these catch LOGIC
 * issues in the builder itself.
 *
 * The `$1` Javassist placeholder appears in these assertions as the literal two-character
 * sequence `$1` (Kotlin escapes it as `\$1` in source). The body builder emits it verbatim for
 * Javassist's source-to-bytecode compiler to resolve as the first method parameter.
 */
class EmbeddedTransformBodyTest {

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // AT4: Null handling — container saved as null MUST read back as null, not empty-object.
    //
    // Heuristic: if every OBJECT-typed synthetic flat field is null, the container is presumed
    // to have been null at save time (write-side skips all properties when container == null →
    // DB stores nothing → read-side sees null for object fields, zero for primitives). Object
    // fields are the only reliable signal; primitives are ambiguous (DB-NULL vs real-zero are
    // indistinguishable at the Java level once the native PUTFIELD has happened).
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Single container, mixed object + primitive synthetics. The body must guard hydration on
     * the object-typed field(s); else branch nullifies the container.
     *
     * This is the canonical AT4 case: `Bill { @Embedded Money price }` with
     * `Money { String currency; long amount }`. Null price → DB NULL → read `priceCurrency =
     * null, priceAmount = 0` → guard fails → `$1.price = null`.
     */
    @Test
    fun body_singleContainer_objectTypedGuard_hydrateOrNull() {
        val container = EmbeddedContainer(
            fieldName = "price",
            typeFqn = "com.example.Money",
            syntheticFields = listOf(
                SyntheticField("priceCurrency", "currency", "java.lang.String"),  // object — participates in guard
                SyntheticField("priceAmount", "amount", "long"),                  // primitive — does NOT
            ),
        )
        val body = EmbeddedTransform.buildAttachEmbeddedBody(listOf(container))

        // ── Guard: object-typed synthetic non-null. ─────────────────────────────────────────
        // Only `priceCurrency` (String) participates — `priceAmount` (long) is ambiguous.
        assertTrue(
            "body must guard hydration on object-typed synthetic `priceCurrency != null`. body:\n$body",
            body.contains("\$1.priceCurrency != null")
        )
        // Primitive should NOT appear in the guard (it's ambiguous).
        assertFalse(
            "body must NOT guard on primitive-typed synthetic `priceAmount` (ambiguous 0 vs NULL). body:\n$body",
            body.contains("\$1.priceAmount != null")
        )

        // ── Hydrate branch: inside the guard, all fields (object + primitive) are copied. ───
        assertTrue("hydrate branch must instantiate container. body:\n$body",
            body.contains("com.example.Money __emb = new com.example.Money();"))
        assertTrue("hydrate branch must copy primitive synthetic too. body:\n$body",
            body.contains("__emb.amount = \$1.priceAmount;"))
        assertTrue("hydrate branch must assign container. body:\n$body",
            body.contains("\$1.price = __emb;"))

        // ── Else branch: container explicitly nullified. ────────────────────────────────────
        // THIS IS THE AT4 REQUIREMENT. Without the else-nullify, the constructor's default
        // (often `null` but NOT guaranteed — user could have `price = new Money()` in ctor)
        // would survive, breaking round-trip fidelity.
        assertTrue(
            "body must have else-branch setting container to null (AT4 null round-trip). body:\n$body",
            body.contains("\$1.price = null;")
        )
        assertTrue("body must have the else keyword (not just a stray `= null;`). body:\n$body",
            body.contains("else"))
    }

    /**
     * Multiple object-typed synthetics: guard should OR them. ANY non-null object field → hydrate.
     * This matches the "at least one column had a real value" semantics — a container with ONE
     * populated column and all others null/default is a REAL container, not a null-signal.
     */
    @Test
    fun body_singleContainer_multipleObjectTypedSynthetics_guardORed() {
        val container = EmbeddedContainer(
            fieldName = "addr",
            typeFqn = "com.example.Address",
            syntheticFields = listOf(
                SyntheticField("addrStreet", "street", "java.lang.String"),
                SyntheticField("addrCity", "city", "java.lang.String"),
                SyntheticField("addrZip", "zip", "int"),  // primitive, excluded from guard
            ),
        )
        val body = EmbeddedTransform.buildAttachEmbeddedBody(listOf(container))

        // Both object-typed synthetics in the guard, OR-joined. Order matches declaration
        // order (stable, deterministic). Primitive `addrZip` NOT in guard.
        assertTrue(
            "guard must OR-join all object-typed synthetics. body:\n$body",
            body.contains("\$1.addrStreet != null || \$1.addrCity != null")
        )
        assertFalse("primitive addrZip must not be in guard. body:\n$body",
            body.contains("\$1.addrZip != null"))
    }

    /**
     * Edge case: container with ONLY primitive synthetics. No reliable null-detection signal
     * exists (every primitive is ambiguous). Fall back to ALWAYS-HYDRATE — same behaviour as
     * the pre-AT4 implementation for this specific shape.
     *
     * This IS a semantic limitation: `@Embedded Point(int x, int y)` saved as null reads back
     * as `Point(0, 0)`. But the alternative — guarding on `x != 0` — would nullify a legit
     * `Point(0, 0)` the user explicitly stored, which is worse (lossy on VALID data vs lossy
     * on NULL data). Document in the `@Embedded` javadoc; users needing null-detection on
     * all-primitive containers should use wrapper types (`Integer` instead of `int`).
     */
    @Test
    fun body_singleContainer_onlyPrimitives_alwaysHydratesNoGuard() {
        val container = EmbeddedContainer(
            fieldName = "pt",
            typeFqn = "com.example.Point",
            syntheticFields = listOf(
                SyntheticField("ptX", "x", "int"),
                SyntheticField("ptY", "y", "int"),
            ),
        )
        val body = EmbeddedTransform.buildAttachEmbeddedBody(listOf(container))

        // No hydration guard — hydration is unconditional for all-primitive containers.
        assertFalse("no guard on primitive-only container (can't distinguish 0 from NULL). body:\n$body",
            body.contains("!= null)"))
        // No else-nullify either (there's no if to else).
        assertFalse("no else-nullify on primitive-only container. body:\n$body",
            body.contains("\$1.pt = null;"))
        // But hydration still happens (always-hydrate fallback).
        assertTrue("always-hydrate: container still instantiated. body:\n$body",
            body.contains("new com.example.Point();"))
        assertTrue("always-hydrate: container still assigned. body:\n$body",
            body.contains("\$1.pt = __emb;"))
    }

    /**
     * Nested container: the nested block must guard on PARENT being non-null first (if parent
     * was nullified by its own guard, `$1.price.cur = ...` would NPE). Then its own hydration
     * guard + else-nullify, same as root.
     *
     * The parent-guard is `$1.price != null` — testing the ASSIGNMENT TARGET of the parent
     * (not the parent's synthetics). This is the only correct signal: the parent's guard
     * already decided hydrate-or-null by the time we get here (containers iterate parent-first).
     */
    @Test
    fun body_nestedContainer_parentGuardPreventNPE_thenOwnGuard() {
        val root = EmbeddedContainer(
            fieldName = "price",
            typeFqn = "com.example.Money",
            syntheticFields = listOf(
                SyntheticField("priceAmount", "amount", "long"),  // primitive only — root always-hydrates
            ),
        )
        val nested = EmbeddedContainer(
            fieldName = "cur",
            typeFqn = "com.example.Currency",
            syntheticFields = listOf(
                SyntheticField("priceCurCode", "code", "java.lang.String"),  // object — nested has guard
                SyntheticField("priceCurRate", "rate", "long"),
            ),
            parent = root,
        )
        val body = EmbeddedTransform.buildAttachEmbeddedBody(listOf(root, nested))

        // ── Root: primitive-only → always-hydrate (no guard, no else-nullify). ──────────────
        assertTrue("root container always hydrated (primitive-only). body:\n$body",
            body.contains("\$1.price = __emb;"))
        assertFalse("root has no hydration guard (primitive-only). body:\n$body",
            body.contains("\$1.priceAmount != null"))

        // ── Nested: parent-guard FIRST. ─────────────────────────────────────────────────────
        // Without this, if root HAD been nullified (e.g. if it had object fields and they were
        // null), the nested block's `$1.price.cur = __emb;` would NPE. In THIS fixture root
        // always-hydrates so the guard is never false at runtime, but the structure must be
        // uniform (we don't special-case "parent happens to always hydrate" at build time).
        assertTrue(
            "nested block must guard on parent being non-null (`\$1.price != null`) to prevent NPE. body:\n$body",
            body.contains("\$1.price != null")
        )

        // ── Nested: own hydration guard (object-typed `code`). ──────────────────────────────
        assertTrue("nested must guard on its object-typed synthetic. body:\n$body",
            body.contains("\$1.priceCurCode != null"))

        // ── Nested: else-nullify via parent chain (`$1.price.cur = null`). ──────────────────
        assertTrue("nested else-branch must nullify via parent chain. body:\n$body",
            body.contains("\$1.price.cur = null;"))

        // ── Nested: hydrate branch assigns via parent chain. ────────────────────────────────
        assertTrue("nested hydrate assigns via parent chain. body:\n$body",
            body.contains("\$1.price.cur = __emb;"))
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Structural invariants (not AT4-specific, but pin the body shape against accidental drift).
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Top-level null-guard on `$1` (the entity) must survive — `get()` on a miss returns null. */
    @Test
    fun body_topLevelNullGuard_preserved() {
        val c = EmbeddedContainer("x", "T", listOf(SyntheticField("xA", "a", "long")))
        val body = EmbeddedTransform.buildAttachEmbeddedBody(listOf(c))
        assertTrue("top-level `if (\$1 == null) return;` must be present. body:\n$body",
            body.contains("if (\$1 == null) return;"))
    }

    /** Arrays are object-typed (heap refs, can be null) → participate in hydration guard. */
    @Test
    fun body_arrayTypedSynthetic_participatesInGuard() {
        val c = EmbeddedContainer(
            fieldName = "v",
            typeFqn = "com.example.Vec",
            syntheticFields = listOf(
                SyntheticField("vData", "data", "byte[]"),   // byte[] — object, nullable
                SyntheticField("vLen", "len", "int"),        // primitive
            ),
        )
        val body = EmbeddedTransform.buildAttachEmbeddedBody(listOf(c))
        assertTrue("byte[] is object-typed → must participate in hydration guard. body:\n$body",
            body.contains("\$1.vData != null"))
    }
}
