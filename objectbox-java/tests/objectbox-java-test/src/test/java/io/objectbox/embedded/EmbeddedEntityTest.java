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

import java.util.ArrayList;
import java.util.List;

import io.objectbox.Box;
import io.objectbox.query.Query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for {@code @Embedded} support using {@link EmbeddedEntity} with
 * a single embedded {@link Address} field.
 * <p>
 * Tests 1–17 covering: basic CRUD, null handling, partial nulls, updates, batch operations,
 * remove semantics, and queries on flattened embedded properties.
 */
public class EmbeddedEntityTest extends AbstractEmbeddedTest {

    private Box<EmbeddedEntity> box;

    @Before
    public void setUpBox() {
        box = store.boxFor(EmbeddedEntity.class);
    }

    // ===== TEST 1: Basic round-trip =====

    @Test
    public void testPutAndGet() {
        // 1. Create entity with all fields populated
        EmbeddedEntity entity = new EmbeddedEntity();
        entity.setName("Office");
        entity.setAddress(new Address("Main St 1", "Berlin", 10115));

        // 2. Put into database
        long id = box.put(entity);

        // 3. Assert PUT succeeded
        assertTrue(id != 0);
        assertEquals(id, entity.getId());

        // 4. Read back from database
        EmbeddedEntity read = box.get(id);

        // 5. Assert top-level fields
        assertNotNull(read);
        assertEquals(id, read.getId());
        assertEquals("Office", read.getName());

        // 6. Assert embedded object was reconstructed
        assertNotNull(read.getAddress());
        assertEquals("Main St 1", read.getAddress().getStreet());
        assertEquals("Berlin", read.getAddress().getCity());
        assertEquals(10115, read.getAddress().getZip());
    }

    // ===== TEST 2: Null embedded object =====

    @Test
    public void testPutAndGet_nullEmbeddedObject() {
        // 1. Create entity with null embedded object
        EmbeddedEntity entity = new EmbeddedEntity();
        entity.setName("Empty");
        entity.setAddress(null);

        // 2. Put and read back
        long id = box.put(entity);
        EmbeddedEntity read = box.get(id);

        // 3. Assert top-level fields
        assertNotNull(read);
        assertEquals("Empty", read.getName());

        // 4. Assert embedded object is null
        assertNull(read.getAddress());
    }

    // ===== TEST 3: Default/null values =====

    @Test
    public void testPutAndGet_defaultOrNullValues() {
        // 1. Put a completely default entity
        long id = box.put(new EmbeddedEntity());

        // 2. Read back
        EmbeddedEntity read = box.get(id);

        // 3. Assert all fields at default/null
        assertNotNull(read);
        assertNull(read.getName());
        assertNull(read.getAddress());
    }

    // ===== TEST 4: Embedded with partial null fields =====

    @Test
    public void testPutAndGet_embeddedWithPartialNullFields() {
        // 1. Create embedded with city=null but street and zip populated
        Address partial = new Address("Park Ave", null, 10001);
        EmbeddedEntity entity = new EmbeddedEntity();
        entity.setName("Partial");
        entity.setAddress(partial);

        // 2. Put and read back
        long id = box.put(entity);
        EmbeddedEntity read = box.get(id);

        // 3. Assert embedded object IS present (street has a value)
        assertNotNull(read.getAddress());
        assertEquals("Park Ave", read.getAddress().getStreet());
        assertNull(read.getAddress().getCity());
        assertEquals(10001, read.getAddress().getZip());
    }

    // ===== TEST 5: Embedded with only primitive set =====

    @Test
    public void testPutAndGet_embeddedWithOnlyPrimitiveSet() {
        // 1. Create embedded with only zip set (strings are null)
        Address onlyZip = new Address(null, null, 99999);
        EmbeddedEntity entity = new EmbeddedEntity();
        entity.setAddress(onlyZip);

        // 2. Put and read back
        long id = box.put(entity);
        EmbeddedEntity read = box.get(id);

        // 3. Assert embedded IS present because zip != 0
        assertNotNull(read.getAddress());
        assertNull(read.getAddress().getStreet());
        assertNull(read.getAddress().getCity());
        assertEquals(99999, read.getAddress().getZip());
    }

    // ===== TEST 6: All embedded fields at defaults → null embedded =====

    @Test
    public void testPutAndGet_embeddedAllFieldsAtDefaults() {
        // 1. Create embedded where all fields are at defaults (null strings, zip=0)
        Address allDefaults = new Address(null, null, 0);
        EmbeddedEntity entity = new EmbeddedEntity();
        entity.setAddress(allDefaults);

        // 2. Put and read back
        long id = box.put(entity);
        EmbeddedEntity read = box.get(id);

        // 3. All Strings null + primitive at default → indistinguishable from null embedded
        assertNull(read.getAddress());
    }

    // ===== TEST 7: Put → Get → Update embedded → Get =====

    @Test
    public void testPutGetUpdateGet() {
        // 1. Create and put initial entity
        EmbeddedEntity entity = new EmbeddedEntity();
        entity.setName("HQ");
        entity.setAddress(new Address("Old St 1", "Munich", 80331));
        long id = box.put(entity);

        // 2. Read back, verify initial values
        EmbeddedEntity read1 = box.get(id);
        assertNotNull(read1);
        assertEquals("Old St 1", read1.getAddress().getStreet());
        assertEquals("Munich", read1.getAddress().getCity());
        assertEquals(80331, read1.getAddress().getZip());

        // 3. Update embedded fields
        read1.getAddress().setStreet("New St 99");
        read1.getAddress().setCity("Hamburg");
        read1.getAddress().setZip(20095);
        box.put(read1);

        // 4. Read back again, verify updated values
        EmbeddedEntity read2 = box.get(id);
        assertNotNull(read2);
        assertEquals(id, read2.getId());
        assertEquals("HQ", read2.getName());
        assertEquals("New St 99", read2.getAddress().getStreet());
        assertEquals("Hamburg", read2.getAddress().getCity());
        assertEquals(20095, read2.getAddress().getZip());
    }

    // ===== TEST 8: Update embedded → null =====

    @Test
    public void testPutGetUpdateEmbeddedToNull() {
        // 1. Put entity with populated embedded
        EmbeddedEntity entity = new EmbeddedEntity();
        entity.setName("Office");
        entity.setAddress(new Address("Some St", "Berlin", 10115));
        long id = box.put(entity);

        // 2. Verify embedded was stored
        EmbeddedEntity read1 = box.get(id);
        assertNotNull(read1.getAddress());

        // 3. Update: set embedded to null
        read1.setAddress(null);
        box.put(read1);

        // 4. Read back: embedded should now be null
        EmbeddedEntity read2 = box.get(id);
        assertNotNull(read2);
        assertEquals("Office", read2.getName());
        assertNull(read2.getAddress());
    }

    // ===== TEST 9: Update null → embedded =====

    @Test
    public void testPutGetUpdateNullToEmbedded() {
        // 1. Put entity with null embedded
        EmbeddedEntity entity = new EmbeddedEntity();
        entity.setName("Warehouse");
        entity.setAddress(null);
        long id = box.put(entity);

        // 2. Verify embedded is null
        EmbeddedEntity read1 = box.get(id);
        assertNull(read1.getAddress());

        // 3. Update: add embedded object
        read1.setAddress(new Address("Dock St", "Hamburg", 20457));
        box.put(read1);

        // 4. Read back: embedded should now be populated
        EmbeddedEntity read2 = box.get(id);
        assertNotNull(read2.getAddress());
        assertEquals("Dock St", read2.getAddress().getStreet());
        assertEquals("Hamburg", read2.getAddress().getCity());
        assertEquals(20457, read2.getAddress().getZip());
    }

    // ===== TEST 10: Batch put + getAll =====

    @Test
    public void testPutManyAndGetAll() {
        // 1. Create and put 10 entities with distinct embedded values
        List<EmbeddedEntity> entities = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            EmbeddedEntity e = new EmbeddedEntity();
            e.setName("Location " + i);
            e.setAddress(new Address("Street " + i, "City " + i, 10000 + i));
            entities.add(e);
        }
        box.put(entities);

        // 2. Assert count
        assertEquals(10, box.count());

        // 3. Read all back and verify each
        List<EmbeddedEntity> readAll = box.getAll();
        assertEquals(10, readAll.size());

        for (int i = 0; i < 10; i++) {
            EmbeddedEntity read = readAll.get(i);
            assertEquals("Location " + i, read.getName());
            assertNotNull(read.getAddress());
            assertEquals("Street " + i, read.getAddress().getStreet());
            assertEquals("City " + i, read.getAddress().getCity());
            assertEquals(10000 + i, read.getAddress().getZip());
        }
    }

    // ===== TEST 11: Remove =====

    @Test
    public void testRemove() {
        // 1. Put entity
        EmbeddedEntity entity = new EmbeddedEntity();
        entity.setName("Temp");
        entity.setAddress(new Address("Temp St", "Nowhere", 11111));
        long id = box.put(entity);

        // 2. Verify exists
        assertEquals(1, box.count());
        assertTrue(box.contains(id));

        // 3. Remove
        assertTrue(box.remove(id));

        // 4. Verify removed
        assertEquals(0, box.count());
        assertNull(box.get(id));
        assertFalse(box.contains(id));

        // 5. Second remove returns false
        assertFalse(box.remove(id));
    }

    // ===== TEST 12: RemoveAll =====

    @Test
    public void testRemoveAll() {
        // 1. Put 5 entities
        for (int i = 0; i < 5; i++) {
            EmbeddedEntity e = new EmbeddedEntity();
            e.setName("Item " + i);
            e.setAddress(new Address("St " + i, "City", 10000 + i));
            box.put(e);
        }
        assertEquals(5, box.count());

        // 2. Remove all
        box.removeAll();

        // 3. Verify empty
        assertEquals(0, box.count());
        assertTrue(box.getAll().isEmpty());
    }

    // ===== TEST 13: Query by embedded String field =====

    @Test
    public void testQueryByEmbeddedStringField() {
        // 1. Put 3 entities with different cities
        EmbeddedEntity e1 = new EmbeddedEntity();
        e1.setName("Office 1");
        e1.setAddress(new Address("St 1", "Berlin", 10115));
        box.put(e1);

        EmbeddedEntity e2 = new EmbeddedEntity();
        e2.setName("Office 2");
        e2.setAddress(new Address("St 2", "Munich", 80331));
        box.put(e2);

        EmbeddedEntity e3 = new EmbeddedEntity();
        e3.setName("Office 3");
        e3.setAddress(new Address("St 3", "Berlin", 10117));
        box.put(e3);

        // 2. Query by embedded city = "Berlin"
        try (Query<EmbeddedEntity> query = box.query(
                EmbeddedEntity_.address_city.equal("Berlin")
        ).build()) {
            List<EmbeddedEntity> results = query.find();

            // 3. Assert: 2 results
            assertEquals(2, results.size());
            assertEquals("Office 1", results.get(0).getName());
            assertEquals("Office 3", results.get(1).getName());
        }
    }

    // ===== TEST 14: Query by embedded Int field =====

    @Test
    public void testQueryByEmbeddedIntField() {
        // 1. Put 3 entities with different zip codes
        for (int i = 0; i < 3; i++) {
            EmbeddedEntity e = new EmbeddedEntity();
            e.setName("Place " + i);
            e.setAddress(new Address("St", "City", 10000 + i));
            box.put(e);
        }

        // 2. Query: zip greater than 10000
        try (Query<EmbeddedEntity> query = box.query(
                EmbeddedEntity_.address_zip.greater(10000)
        ).build()) {
            List<EmbeddedEntity> results = query.find();

            // 3. Assert: 2 results (zip=10001 and zip=10002)
            assertEquals(2, results.size());
        }

        // 4. Query: zip equal to 10000
        try (Query<EmbeddedEntity> query = box.query(
                EmbeddedEntity_.address_zip.equal(10000)
        ).build()) {
            assertEquals(1, query.count());
        }
    }

    // ===== TEST 15: Query isNull / notNull on embedded String field =====

    @Test
    public void testQueryByEmbeddedField_isNull() {
        // 1. Put one entity with address, one without
        EmbeddedEntity withAddr = new EmbeddedEntity();
        withAddr.setName("Has Address");
        withAddr.setAddress(new Address("St 1", "Berlin", 10115));
        box.put(withAddr);

        EmbeddedEntity noAddr = new EmbeddedEntity();
        noAddr.setName("No Address");
        noAddr.setAddress(null);
        box.put(noAddr);

        // 2. Query: address_street isNull
        try (Query<EmbeddedEntity> query = box.query(
                EmbeddedEntity_.address_street.isNull()
        ).build()) {
            List<EmbeddedEntity> results = query.find();
            assertEquals(1, results.size());
            assertEquals("No Address", results.get(0).getName());
        }

        // 3. Query: address_street notNull
        try (Query<EmbeddedEntity> query = box.query(
                EmbeddedEntity_.address_street.notNull()
        ).build()) {
            List<EmbeddedEntity> results = query.find();
            assertEquals(1, results.size());
            assertEquals("Has Address", results.get(0).getName());
        }
    }

    // ===== TEST 16: Combined query: top-level AND embedded field =====

    @Test
    public void testQueryCombinedTopLevelAndEmbeddedField() {
        // 1. Put diverse entities
        EmbeddedEntity e1 = new EmbeddedEntity();
        e1.setName("Alpha");
        e1.setAddress(new Address("St 1", "Berlin", 10115));
        box.put(e1);

        EmbeddedEntity e2 = new EmbeddedEntity();
        e2.setName("Beta");
        e2.setAddress(new Address("St 2", "Berlin", 80331));
        box.put(e2);

        EmbeddedEntity e3 = new EmbeddedEntity();
        e3.setName("Alpha");
        e3.setAddress(new Address("St 3", "Munich", 80333));
        box.put(e3);

        // 2. Query: name = "Alpha" AND city = "Berlin"
        try (Query<EmbeddedEntity> query = box.query(
                EmbeddedEntity_.name.equal("Alpha")
                        .and(EmbeddedEntity_.address_city.equal("Berlin"))
        ).build()) {
            List<EmbeddedEntity> results = query.find();

            // 3. Assert: only e1 matches
            assertEquals(1, results.size());
            assertEquals("St 1", results.get(0).getAddress().getStreet());
        }
    }

    // ===== TEST 17: Count with mixed null/non-null embedded =====

    @Test
    public void testCount() {
        // 1. Initially empty
        assertEquals(0, box.count());

        // 2. Put 3 entities: 2 with address, 1 without
        EmbeddedEntity e1 = new EmbeddedEntity();
        e1.setAddress(new Address("A", "B", 1));
        box.put(e1);

        EmbeddedEntity e2 = new EmbeddedEntity();
        e2.setAddress(null);
        box.put(e2);

        EmbeddedEntity e3 = new EmbeddedEntity();
        e3.setAddress(new Address("C", "D", 2));
        box.put(e3);

        // 3. Count is entity-level (not dependent on embedded null/non-null)
        assertEquals(3, box.count());
    }
}
