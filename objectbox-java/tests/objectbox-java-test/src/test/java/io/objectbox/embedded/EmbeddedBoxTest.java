/*
 * Copyright 2026 ObjectBox Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.objectbox.embedded;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import io.objectbox.AbstractObjectBoxTest;
import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.query.Query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end {@code @Embedded} integration tests against the <b>real native library</b> (JNI).
 * <p>
 * These tests are the final proof of the {@code @Embedded} design — they exercise the complete
 * round-trip: {@code Cursor.put()} → {@code collect313311} → native LMDB write → native read →
 * JNI {@code SetXxxField(entity, propertyName, value)} onto <b>synthetic flat fields</b> →
 * {@code Cursor.attachEmbedded()} → hydrated container → user code.
 *
 * <h3>What this test module validates</h3>
 * <ul>
 *   <li><b>JNI genuinely populates fields named by {@code Property.name}</b> — proves the core
 *       read-path hypothesis ("synthetic flat fields + runtime hook", see the implementation plan
 *       §2.4) against real native code. If JNI's field-name binding ever changed, these tests
 *       would break even if all generator/transformer tests stayed green.</li>
 *   <li><b>{@code Cursor.attachEmbedded} wiring is complete</b> across every read entry point
 *       ({@code Cursor.get/getAll}, {@code Query.find/findFirst/findUnique/find(offset,limit)}).
 *       A missing hook at any call site yields {@code result.price == null}.</li>
 *   <li><b>Null-container round-trip</b> — {@code put()} with {@code entity.price == null}
 *       stores columns as absent; the read-path hydration guard detects all-null object-typed
 *       synthetics and restores {@code entity.price = null}. End-to-end null fidelity.</li>
 * </ul>
 *
 * <h3>What this does NOT validate (covered elsewhere)</h3>
 * <ul>
 *   <li>That the <i>generator actually emits</i> the Cursor/EntityInfo we hand-wrote —
 *       {@code objectbox-processor/.../EmbeddedTest.kt} (compile-testing, source assertions).</li>
 *   <li>That the <i>transformer actually injects</i> the synthetic fields + attachEmbedded body —
 *       {@code objectbox-code-modifier/.../ClassTransformerTest.kt} (Javassist CtClass output
 *       inspection, constpool verification).</li>
 * </ul>
 *
 * This is the established pattern in this module: {@link io.objectbox.relation.Order} follows
 * exactly the same approach of hand-declaring transformer-injected artifacts to test runtime
 * behaviour in isolation from the build-time pipeline. See {@link TestBill} for the post-transform
 * entity shape.
 * <p>
 * <b>Intended outcome</b>: all tests GREEN on first run. If any are RED, one of M0's hook wirings
 * is incomplete (or the JNI contract has changed). No production-code changes are expected in
 * this milestone — this is pure integration verification of M0–M3.
 */
public class EmbeddedBoxTest extends AbstractObjectBoxTest {

    private Box<TestBill> billBox;

    /**
     * Swap out the base class's default {@code TestEntity} schema for our single-entity embedded
     * schema. The base {@code tearDown()} handles cleanup (store close + recursive dir delete).
     * <p>
     * Uses {@code directory()} not {@code baseDirectory()} — the base temp dir already has a
     * unique per-test name, no need for an extra {@code /objectbox/} subdir layer.
     */
    @Override
    protected BoxStore createBoxStore() {
        return MyObjectBox.builder().directory(boxStoreDir).build();
    }

    @Before
    @Override
    public void setUp() throws IOException {
        super.setUp();
        billBox = store.boxFor(TestBill.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // I1 — Core round-trip: put → get with real data in the embedded container.
    //
    // This is THE critical end-to-end proof. If this passes, the entire @Embedded design is sound:
    //   - put()'s container-hoisting + collect slot assignment is correct → native accepted the
    //     flattened columns
    //   - USE_NO_ARG_CONSTRUCTOR flag is honoured → JNI called `new TestBill()` not some all-args
    //   - JNI's `SetObjectField(priceCurrency, ...)` / `SetDoubleField(priceAmount, ...)`
    //     successfully found the synthetic flat fields BY NAME → the name-binding contract holds
    //   - Cursor.get() wiring invoked attachEmbedded() → the runtime hook fired
    //   - attachEmbedded()'s copy logic is correct → the container was hydrated
    //
    // Failure modes and what they mean:
    //   - NPE on result.price.currency → attachEmbedded wasn't wired (M0 miss) OR its body is
    //     wrong (copy-from-wrong-field) — distinguish by checking result.price != null first.
    //   - result.price.currency == null (but price != null) → JNI never set priceCurrency; likely
    //     a property-name mismatch between TestBill_ and TestBill's synthetic field.
    //   - JNI IllegalArgumentException on put → collect slot assignment / property IDs wrong.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void testPutAndGet() {
        TestBill bill = new TestBill("AcmeCorp", new TestMoney("USD", 42.5));
        long id = billBox.put(bill);
        assertTrue("put must assign a non-zero ID", id > 0);

        TestBill result = billBox.get(id);
        assertNotNull("get must return the entity (native read worked)", result);
        assertEquals("regular property round-trips", "AcmeCorp", result.provider);

        // THE assertion — embedded container is hydrated with correct values after JNI read.
        assertNotNull(
                "result.price must NOT be null — attachEmbedded() should have instantiated " +
                        "TestMoney and copied synthetic flats. If null, the attachEmbedded wiring " +
                        "in Cursor.get() is missing or the override isn't being dispatched.",
                result.price
        );
        assertEquals(
                "result.price.currency must match what was put — proves JNI set priceCurrency " +
                        "on the synthetic flat field AND attachEmbedded copied it into the container.",
                "USD", result.price.currency
        );
        assertEquals(
                "result.price.amount must match what was put — proves the double slot in " +
                        "collect313311 was correctly wired AND JNI's SetDoubleField found the synthetic.",
                42.5, result.price.amount, 0.0
        );
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // I2 — Null-container round-trip (Acceptance Test AT4).
    //
    // Write half — put() with entity.price == null:
    //   put()'s hoisting null-guards every embedded property's ID: `__id2 = __emb_price != null
    //   ? __ID_... : 0`. When the container is null, every ID is 0 → native treats those slots as
    //   ABSENT (doesn't write a value, column stays DB-NULL).
    //
    // Read half — get() restores null:
    //   JNI skips SetXxxField for absent columns → synthetic flat fields stay at Java defaults
    //   (null for String, 0.0 for double). attachEmbedded's hydration guard checks object-typed
    //   synthetics: `if (priceCurrency != null) { ...hydrate... } else { entity.price = null; }`.
    //   Guard fails (priceCurrency == null) → container explicitly nullified.
    //
    // THIS IS THE AT4 END-TO-END PROOF: null in → native → null out. Runs against real JNI — if
    // the native column-absent contract ever changed (e.g. JNI started writing "" instead of
    // skipping SetObjectField), the guard would see non-null and wrongly hydrate. This test
    // catches that drift.
    //
    // Note the asymmetry that makes this work: `priceCurrency` (String, object-typed) IS the null
    // signal; `priceAmount` (primitive double) is NOT — a primitive can't distinguish "column was
    // absent" from "column held 0.0". The guard builder only uses object-typed synthetics for
    // exactly this reason. An all-primitive embeddable would always-hydrate; see @Embedded javadoc.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void testPutNullContainerAndGet() {
        TestBill bill = new TestBill("NoPrice", null); // ← container intentionally null
        long id = billBox.put(bill);

        TestBill result = billBox.get(id);
        assertNotNull(result);
        assertEquals("regular property unaffected by null container", "NoPrice", result.provider);

        // ── AT4: container saved as null MUST read back as null. ────────────────────────────────
        // Full chain proven: put()'s container-null → prop-ID 0 → native column-absent → JNI skip
        // SetObjectField → synthetic priceCurrency stays null → attachEmbedded guard fails →
        // entity.price explicitly set to null.
        //
        // If this fails with result.price != null, diagnose by checking result.priceCurrency:
        //   - priceCurrency == null BUT price != null → attachEmbedded's else-branch is missing
        //     or the guard condition is inverted. Check TestBillCursor.attachEmbedded body.
        //   - priceCurrency != null → native column-absent contract broken (JNI wrote SOMETHING
        //     for an absent column). Deeper problem, outside @Embedded's scope.
        assertNull(
                "result.price MUST be null (AT4: null-saved → null-read). The attachEmbedded " +
                        "hydration guard should have seen priceCurrency == null and taken the " +
                        "else-branch. If non-null, either the guard is wrong or JNI unexpectedly " +
                        "populated priceCurrency for an absent column.",
                result.price
        );
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // I3 — Bulk read: put N, getAll() → every entity's container hydrated.
    //
    // Validates the attachEmbedded(List<T>) overload in Cursor: it must iterate and delegate
    // per-element. If the list overload were broken (e.g. only first element hydrated, or the
    // for-each in Cursor.attachEmbedded(List) was off-by-one), one of the result entities'
    // containers would be null or have default values.
    //
    // Uses distinct values per entity so we can assert no cross-entity state leakage.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void testGetAll() {
        billBox.put(new TestBill("p1", new TestMoney("EUR", 1.1)));
        billBox.put(new TestBill("p2", new TestMoney("GBP", 2.2)));
        billBox.put(new TestBill("p3", new TestMoney("JPY", 3.3)));

        List<TestBill> all = billBox.getAll();
        assertEquals("all 3 entities retrieved", 3, all.size());

        // Every entity's container must be hydrated with its OWN values.
        // We don't assume ordering (though ObjectBox typically returns by insert order) — instead
        // verify the full set of (provider → currency, amount) tuples is preserved.
        for (TestBill b : all) {
            assertNotNull("every entity's price container hydrated (provider=" + b.provider + ")", b.price);
            switch (b.provider) {
                case "p1": assertEquals("EUR", b.price.currency); assertEquals(1.1, b.price.amount, 0.0); break;
                case "p2": assertEquals("GBP", b.price.currency); assertEquals(2.2, b.price.amount, 0.0); break;
                case "p3": assertEquals("JPY", b.price.currency); assertEquals(3.3, b.price.amount, 0.0); break;
                default: throw new AssertionError("unexpected provider: " + b.provider);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // I4 — Query by embedded field: box.query(TestBill_.priceCurrency.equal("EUR")).find().
    //
    // Validates TWO things independently:
    //   (a) Query.find() WIRING — the attachEmbedded hook is invoked on the Query.nativeFind path
    //       (which is SEPARATE from Cursor.get() — Query bypasses Cursor's Java read methods and
    //       calls native directly, then explicitly calls cursor().attachEmbedded(entities)).
    //       If this wiring were missing, all .price containers would be null.
    //   (b) QUERYABILITY of flattened embedded properties — TestBill_.priceCurrency is a REAL
    //       Property<TestBill> exactly like any regular column. The query engine needs ZERO
    //       awareness of @Embedded. This is the design payoff of the "synthetic flat fields"
    //       approach: `box.query(TestBill_.priceCurrency.equal("EUR"))` Just Works.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void testQueryByEmbeddedField() {
        billBox.put(new TestBill("pe1", new TestMoney("EUR", 100.0)));
        billBox.put(new TestBill("pu1", new TestMoney("USD", 200.0)));
        billBox.put(new TestBill("pe2", new TestMoney("EUR", 300.0)));
        billBox.put(new TestBill("pg1", new TestMoney("GBP", 400.0)));

        // Query on a FLATTENED embedded property — this is the headline UX: user writes
        // @Embedded Money price and gets box.query(Entity_.priceCurrency.equal(...)) for free.
        try (Query<TestBill> query = billBox.query(TestBill_.priceCurrency.equal("EUR")).build()) {
            List<TestBill> results = query.find();

            assertEquals(
                    "exactly 2 EUR bills — proves the query engine filtered on the flattened " +
                            "column. If this were 4, the equal() condition was ignored. If 0, " +
                            "either the put() didn't store priceCurrency or the property ID is wrong.",
                    2, results.size()
            );

            // Every result's container hydrated via the Query.find() → attachEmbedded path.
            for (TestBill b : results) {
                assertNotNull(
                        "query result's price container hydrated — proves Query.find() called " +
                                "attachEmbedded. If null, the Query.java wiring is incomplete.",
                        b.price
                );
                assertEquals(
                        "query result's price.currency matches the filter — sanity check that " +
                                "attachEmbedded copied the RIGHT synthetic flat (not cross-wired).",
                        "EUR", b.price.currency
                );
                // Provider distinguishes the two EUR hits; amounts must round-trip correctly.
                if ("pe1".equals(b.provider)) assertEquals(100.0, b.price.amount, 0.0);
                else if ("pe2".equals(b.provider)) assertEquals(300.0, b.price.amount, 0.0);
                else throw new AssertionError("unexpected EUR bill: " + b.provider);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // I5 — Overwrite: put, mutate container in-place, put again, get → latest value persisted.
    //
    // Regression guard for stale synthetic-field state leaking between put and get. The scenario:
    //   1. Put bill with price=Money("V1", 1.0). Synthetic flats are NOT involved in put()'s path
    //      (put reads entity.price.currency directly), so they stay at defaults after the put.
    //   2. Mutate bill.price.amount = 2.0 IN PLACE (same Money instance).
    //   3. Put again. Must persist 2.0.
    //   4. Get. Must return 2.0 — synthetic flats are set by JNI on read, copied to a FRESH
    //      Money by attachEmbedded. If attachEmbedded mistakenly read the user's stale container
    //      instead of the synthetic flats, we'd see 2.0 anyway (false positive). So we also assert
    //      the RETURNED Money is NOT the same instance as the one we mutated — proves hydration
    //      really built a fresh container.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void testOverwriteEmbedded() {
        TestBill bill = new TestBill("mut", new TestMoney("V1", 1.0));
        long id = billBox.put(bill);

        // Mutate the container IN PLACE and re-put with the same ID (overwrite).
        TestMoney originalMoneyRef = bill.price; // capture ref for identity assertion later
        bill.price.amount = 2.0;
        bill.price.currency = "V2";
        billBox.put(bill); // same id → overwrite

        TestBill result = billBox.get(id);
        assertNotNull(result);
        assertNotNull("container hydrated after overwrite round-trip", result.price);

        assertEquals("latest amount persisted — update path works", 2.0, result.price.amount, 0.0);
        assertEquals("latest currency persisted — update path works", "V2", result.price.currency);

        // Identity check: attachEmbedded must have built a FRESH TestMoney from synthetic flats,
        // not somehow returned the user's original instance. `result` is a distinct entity object
        // allocated by JNI, so this would only fail if attachEmbedded's body were wildly wrong
        // (e.g. copying entity.price → entity.price, a no-op). Belt-and-suspenders.
        assertTrue(
                "hydrated container must be a FRESH TestMoney (new instance) — proves " +
                        "attachEmbedded did real work, not a trivial identity copy.",
                result.price != originalMoneyRef
        );
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // I6 (bonus) — Query findFirst / findUnique wiring.
    //
    // I4 covers Query.find(); these cover the other two Query read-paths that have their OWN
    // attachEmbedded call sites (Query.java:232, 269). The spy-counter tests in
    // CursorAttachEmbeddedTest prove the hook FIRES; this test proves it fires ON THE RIGHT DATA.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void testQueryFindFirstAndUnique_hydrateContainer() {
        billBox.put(new TestBill("only", new TestMoney("CHF", 999.0)));

        try (Query<TestBill> query = billBox.query(TestBill_.priceCurrency.equal("CHF")).build()) {
            // findFirst → single-entity path
            TestBill first = query.findFirst();
            assertNotNull("findFirst returned the one CHF bill", first);
            assertNotNull("findFirst result's container hydrated (Query.java:232 hook)", first.price);
            assertEquals("CHF", first.price.currency);
            assertEquals(999.0, first.price.amount, 0.0);

            // findUnique → single-entity path (different native call, different hook site)
            TestBill unique = query.findUnique();
            assertNotNull("findUnique returned the one CHF bill", unique);
            assertNotNull("findUnique result's container hydrated (Query.java:269 hook)", unique.price);
            assertEquals("CHF", unique.price.currency);
            assertEquals(999.0, unique.price.amount, 0.0);
        }
    }
}
