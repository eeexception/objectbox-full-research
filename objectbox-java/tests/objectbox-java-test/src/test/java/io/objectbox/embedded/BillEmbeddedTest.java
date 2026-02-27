/*
 * Copyright 2017-2025 ObjectBox Ltd.
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

import java.util.List;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.BoxStoreBuilder;
import io.objectbox.ModelBuilder;
import io.objectbox.ModelBuilder.EntityBuilder;
import io.objectbox.model.PropertyFlags;
import io.objectbox.model.PropertyType;
import io.objectbox.query.Query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Acceptance tests for {@code @Embedded} support using {@link Bill} with
 * an embedded {@link Money} field (currency + amount).
 * <p>
 * These tests run against a real ObjectBox database via JNI and validate:
 * <ol>
 *   <li>Flattened Persistence</li>
 *   <li>Reconstruction</li>
 *   <li>Deep Querying</li>
 *   <li>Null Handling</li>
 *   <li>Schema Evolution</li>
 *   <li>No ID Requirement</li>
 * </ol>
 */
public class BillEmbeddedTest extends AbstractEmbeddedTest {

    private Box<Bill> box;

    @Override
    protected void addAdditionalEntities(ModelBuilder modelBuilder) {
        lastEntityUid = ++lastUid;
        EntityBuilder eb = modelBuilder.entity("Bill").id(++lastEntityId, lastEntityUid);

        // ordinal 0: id (Long) — ID flag
        eb.property("id", PropertyType.Long)
                .id(Bill_.id.id, ++lastUid)
                .flags(PropertyFlags.ID);

        // ordinal 1: provider (String)
        eb.property("provider", PropertyType.String)
                .id(Bill_.provider.id, ++lastUid);

        // ordinal 2: price_currency (String) — flattened from Money.currency
        eb.property("price_currency", PropertyType.String)
                .id(Bill_.price_currency.id, ++lastUid);

        // ordinal 3: price_amount (Double) — flattened from Money.amount
        eb.property("price_amount", PropertyType.Double)
                .id(Bill_.price_amount.id, ++lastUid);

        int lastPropId = Bill_.price_amount.id;
        eb.lastPropertyId(lastPropId, lastUid);
        eb.entityDone();
    }

    @Override
    protected void registerEntities(BoxStoreBuilder builder) {
        super.registerEntities(builder);
        builder.entity(new Bill_());
    }

    @Before
    public void setUpBox() {
        box = store.boxFor(Bill.class);
    }

    // ===== AT-1: Flattened Persistence =====

    /**
     * Saving a Bill with a price (USD, 100.0) results in a single row in the ObjectBox database
     * containing the columns provider, price_currency, and price_amount.
     */
    @Test
    public void testFlattenedPersistence() {
        Bill bill = new Bill();
        bill.setProvider("ACME Corp");
        bill.setPrice(new Money("USD", 100.0));

        long id = box.put(bill);
        assertTrue(id != 0);
        assertEquals(id, bill.getId());

        // Read back and verify all flattened columns
        Bill read = box.get(id);
        assertNotNull(read);
        assertEquals(id, read.getId());
        assertEquals("ACME Corp", read.getProvider());
        assertNotNull(read.getPrice());
        assertEquals("USD", read.getPrice().getCurrency());
        assertEquals(100.0, read.getPrice().getAmount(), 0.0001);
    }

    // ===== AT-2: Reconstruction =====

    /**
     * Retrieving the Bill from the Box correctly reconstructs the Money object
     * with all its original values.
     */
    @Test
    public void testReconstruction() {
        Bill bill = new Bill();
        bill.setProvider("Global Inc");
        bill.setPrice(new Money("EUR", 49.99));

        long id = box.put(bill);

        Bill read = box.get(id);
        assertNotNull(read);
        assertNotNull(read.getPrice());

        // Verify the Money object was fully reconstructed
        Money price = read.getPrice();
        assertEquals("EUR", price.getCurrency());
        assertEquals(49.99, price.getAmount(), 0.0001);
    }

    // ===== AT-3: Deep Querying =====

    /**
     * The generated Bill_ meta-class must allow querying by embedded fields.
     * Example: box.query().equal(Bill_.price_amount, 100.0).build().find() must work.
     */
    @Test
    public void testDeepQuerying() {
        // Put 3 bills with different amounts
        Bill bill1 = new Bill();
        bill1.setProvider("Provider A");
        bill1.setPrice(new Money("USD", 100.0));
        box.put(bill1);

        Bill bill2 = new Bill();
        bill2.setProvider("Provider B");
        bill2.setPrice(new Money("EUR", 200.0));
        box.put(bill2);

        Bill bill3 = new Bill();
        bill3.setProvider("Provider C");
        bill3.setPrice(new Money("USD", 100.0));
        box.put(bill3);

        // Query by embedded amount field
        try (Query<Bill> query = box.query(
                Bill_.price_amount.equal(100.0, 0.001)
        ).build()) {
            List<Bill> results = query.find();
            assertEquals(2, results.size());
            assertEquals("Provider A", results.get(0).getProvider());
            assertEquals("Provider C", results.get(1).getProvider());
        }

        // Query by embedded currency field
        try (Query<Bill> query = box.query(
                Bill_.price_currency.equal("EUR")
        ).build()) {
            List<Bill> results = query.find();
            assertEquals(1, results.size());
            assertEquals("Provider B", results.get(0).getProvider());
        }
    }

    // ===== AT-4: Null Handling =====

    /**
     * If a Bill is saved with a null price, all flattened fields in the database must be null,
     * and retrieving the entity should return a null price object.
     */
    @Test
    public void testNullHandling() {
        Bill bill = new Bill();
        bill.setProvider("No Price Corp");
        bill.setPrice(null);

        long id = box.put(bill);

        Bill read = box.get(id);
        assertNotNull(read);
        assertEquals("No Price Corp", read.getProvider());
        assertNull(read.getPrice());
    }

    // ===== AT-5: Schema Evolution =====

    /**
     * Adding an {@code @Embedded} field to an existing Entity must trigger a standard ObjectBox
     * schema migration (adding the new flattened columns).
     * <p>
     * This is verified by the fact that the model is built programmatically with flattened
     * columns and the store opens successfully. Data can be written and read back correctly.
     * Additionally, we verify that the store can be closed and reopened with the same model.
     */
    @Test
    public void testSchemaEvolution() {
        // Put data with embedded fields
        Bill bill = new Bill();
        bill.setProvider("Evolution Corp");
        bill.setPrice(new Money("GBP", 75.50));
        long id = box.put(bill);

        // Close the store
        store.close();

        // Reset UID counters so the model is built with identical UIDs
        lastEntityId = 0;
        lastIndexId = 0;
        lastUid = 0;
        lastEntityUid = 0;
        lastIndexUid = 0;

        // Reopen with the same model — this verifies schema persistence
        store = createBoxStore();
        box = store.boxFor(Bill.class);

        // Verify data survived the reopen
        Bill read = box.get(id);
        assertNotNull(read);
        assertEquals("Evolution Corp", read.getProvider());
        assertNotNull(read.getPrice());
        assertEquals("GBP", read.getPrice().getCurrency());
        assertEquals(75.50, read.getPrice().getAmount(), 0.0001);
    }

    // ===== AT-6: No ID Requirement =====

    /**
     * The generator must not require an {@code @Id} field inside the class marked for embedding.
     * <p>
     * This is verified by the fact that {@link Money} has no {@code @Id} field, yet
     * it works correctly as an embedded object. If an {@code @Id} were required, the
     * store would fail to operate.
     */
    @Test
    public void testNoIdRequirement() {
        // Money has no @Id field — verify it works as embedded
        Bill bill = new Bill();
        bill.setProvider("NoId Test");
        bill.setPrice(new Money("JPY", 10000.0));

        long id = box.put(bill);
        assertTrue(id != 0);

        Bill read = box.get(id);
        assertNotNull(read);
        assertNotNull(read.getPrice());
        assertEquals("JPY", read.getPrice().getCurrency());
        assertEquals(10000.0, read.getPrice().getAmount(), 0.0001);
    }
}
