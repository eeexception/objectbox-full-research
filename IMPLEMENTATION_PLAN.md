# @Embedded Objects — Implementation Plan

## 1. Environment & Build Audit

### Environment
| Component       | Version/Status |
|-----------------|----------------|
| JDK             | OpenJDK 21.0.10 (Homebrew) — targeting Java 8 bytecode |
| Gradle          | 8.7 (via wrapper in `objectbox-java/`) |
| OS              | macOS Darwin 25.3.0 |
| APT/KSP         | **Not present** in this repo — Java annotation processing lives in a separate internal repo |

### Build Baseline
- `./gradlew :objectbox-java-api:build :objectbox-java:build` — **SUCCESS**
- `./gradlew :tests:objectbox-java-test:test` — **439 tests, 1 failure, 2 skipped**
  - Sole failure: `BoxStoreBuilderTest.maxDataSize` (environment-specific `DbFullException`, pre-existing, unrelated)

### Repository Structure (Relevant Modules)
```
objectbox-java/
├── objectbox-java-api/       # Annotations: @Entity, @Id, @Convert, etc.
├── objectbox-java/           # Core runtime: Cursor, Property, ModelBuilder, Box, JNI bridge
└── tests/objectbox-java-test/
    ├── src/main/java/        # Hand-maintained "generated" code (TestEntity, TestEntity_, TestEntityCursor)
    └── src/test/java/        # Integration tests + AbstractObjectBoxTest base class
```

### Critical Architectural Constraint
There is **no annotation processor in this repository**. Test entities (`TestEntity`, `TestEntity_`, `TestEntityCursor`) are **hand-written** to emulate what the real Gradle plugin would generate. This is the established pattern for testing runtime behavior — our embedded entity test code will follow the same pattern.

---

## 2. Architecture Analysis: How Entity Persistence Works

### PUT Path (Java → Database)
1. Generated `XxxCursor.put(entity)` extracts every field value from the entity
2. Groups properties by type and calls native `collect*` methods with `(propertyId, value)` pairs
3. `PUT_FLAG_FIRST` on the first call, `PUT_FLAG_COMPLETE` on the last
4. Null handling: passing `propertyId=0` tells the native layer to skip that slot (NULL)

### GET Path (Database → Java)
1. `Cursor.get(key)` → JNI `nativeGetEntity(cursor, key)`
2. JNI uses the entity's `Class<?>` (registered via `BoxStore.nativeRegisterEntityClass`)
3. **Default mode**: JNI invokes the **all-args constructor** with flat property values in DB property order
4. **Alternative**: `EntityFlags.USE_NO_ARG_CONSTRUCTOR` — JNI uses no-arg ctor + field-by-name setting

### Key Insight for @Embedded
The all-args constructor **IS** the assembly point. JNI passes flat DB values; the constructor body can assemble embedded objects from those flat values. The Cursor `put()` does the reverse: disassembles embedded objects into flat property values. **Zero reflection at runtime.**

---

## 3. Implementation Strategy

### 3.1 Core Principle: Compile-Time Flattening

An `@Embedded` field is **not** a separate entity. Its fields are flattened into the parent entity's property list with prefixed column names. At the database level, there is no "embedded object" — just additional columns.

**Example**: Given `Bill` with `@Embedded Money price`:
```
DB Properties for "Bill":
  id          (Long)     — property id 1
  provider    (String)   — property id 2
  price_currency (String) — property id 3   ← flattened from Money.currency
  price_amount   (Double) — property id 4   ← flattened from Money.amount
```

The prefix defaults to the field name (`price`) + `_`, but can be customized via `@Embedded(prefix = "...")`.

### 3.2 Generated Code Pattern

For the `Bill` entity with `@Embedded Money price`:

**All-args constructor** (called by JNI with flat values):
```java
public Bill(long id, String provider, String price_currency, double price_amount) {
    this.id = id;
    this.provider = provider;
    // Assembly: construct embedded object from flat DB values
    this.price = new Money(price_currency, price_amount);
}
```

**Cursor.put()** (disassembly into flat values):
```java
public long put(Bill entity) {
    Money price = entity.price;
    String price_currency = price != null ? price.currency : null;
    double price_amount = price != null ? price.amount : 0.0;
    // ... collect calls with flat values ...
}
```

### 3.3 Null Embedded Object Handling
When the entire embedded object is `null`:
- All flattened properties are passed with `propertyId=0` (NULL) in the Cursor PUT
- On GET, the all-args constructor receives null/default values for all embedded fields
- The constructor detects "all null" and sets the embedded field to `null`
- For mixed primitive/object fields: if all object-typed fields are null AND all primitives are at their defaults, treat the embedded object as null

---

## 4. Implementation Milestones

### Phase 2: Annotation + Test Entity Infrastructure

**Step 2.1 — Create `@Embedded` Annotation**
- File: `objectbox-java-api/src/main/java/io/objectbox/annotation/Embedded.java`
- `@Retention(CLASS)`, `@Target(FIELD)`
- Attribute: `prefix()` with default `""` (auto-derived from field name + `_`)

**Step 2.2 — Create Embeddable Value Object (`Address`)**
- File: `tests/objectbox-java-test/src/main/java/io/objectbox/embedded/Address.java`
- Plain Java class (no `@Entity`, no `@Id`)
- Fields: `String street`, `String city`, `int zip`
- No-arg constructor + all-args constructor

**Step 2.3 — Create Parent Entity (`EmbeddedEntity`)**
- File: `tests/objectbox-java-test/src/main/java/io/objectbox/embedded/EmbeddedEntity.java`
- `@Entity` class with `@Id long id`, `String name`, `@Embedded Address address`
- All-args constructor that assembles `Address` from flat DB values
- DB property layout (5 properties):
  - ordinal 0, id 1: `id` (Long)
  - ordinal 1, id 2: `name` (String)
  - ordinal 2, id 3: `address_street` (String)
  - ordinal 3, id 4: `address_city` (String)
  - ordinal 4, id 5: `address_zip` (Int)

**Step 2.4 — Create EntityInfo (`EmbeddedEntity_`)**
- File: `tests/objectbox-java-test/src/main/java/io/objectbox/embedded/EmbeddedEntity_.java`
- Hand-written following `TestEntityMinimal_` pattern
- 5 static `Property<EmbeddedEntity>` fields matching the flat layout above
- Implements `EntityInfo<EmbeddedEntity>`: `getAllProperties()`, `getIdGetter()`, `getCursorFactory()`

**Step 2.5 — Create Cursor (`EmbeddedEntityCursor`)**
- File: `tests/objectbox-java-test/src/main/java/io/objectbox/embedded/EmbeddedEntityCursor.java`
- Hand-written following `TestEntityMinimalCursor` pattern
- `put()` method: extracts `Address` fields with null guard, calls `collect313311` with flat values

**Step 2.6 — Create Test Base (`AbstractEmbeddedTest`)**
- File: `tests/objectbox-java-test/src/test/java/io/objectbox/embedded/AbstractEmbeddedTest.java`
- Extends the lifecycle pattern from `AbstractObjectBoxTest`
- Model building via `ModelBuilder` registering the `EmbeddedEntity` with 5 flat properties
- BoxStore creation registering `EmbeddedEntity_`

---

### Phase 3: Integration Tests (TDD) + Make Green

All tests in `tests/objectbox-java-test/src/test/java/io/objectbox/embedded/EmbeddedEntityTest.java`.
Extends `AbstractEmbeddedTest`. Each test method's full logic is specified below.

---

#### TEST 1: `testPutAndGet`
**Purpose**: Verify basic round-trip persistence of an entity with a fully populated embedded object.

```java
@Test
public void testPutAndGet() {
    // 1. Create entity with all fields populated
    EmbeddedEntity entity = new EmbeddedEntity();
    entity.setName("Office");
    entity.setAddress(new Address("Main St 1", "Berlin", 10115));

    // 2. Put into database via Box
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);
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
```

---

#### TEST 2: `testPutAndGet_nullEmbeddedObject`
**Purpose**: Verify that a null `@Embedded` field persists as null and reads back as null.

```java
@Test
public void testPutAndGet_nullEmbeddedObject() {
    // 1. Create entity with null embedded object
    EmbeddedEntity entity = new EmbeddedEntity();
    entity.setName("Empty");
    entity.setAddress(null);  // explicitly null

    // 2. Put and read back
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);
    long id = box.put(entity);
    EmbeddedEntity read = box.get(id);

    // 3. Assert top-level fields
    assertNotNull(read);
    assertEquals("Empty", read.getName());

    // 4. Assert embedded object is null (all flattened fields were NULL in DB)
    assertNull(read.getAddress());
}
```

---

#### TEST 3: `testPutAndGet_defaultOrNullValues`
**Purpose**: Verify that putting a completely default entity (no fields set, no embedded object) round-trips correctly.

```java
@Test
public void testPutAndGet_defaultOrNullValues() {
    // 1. Put a completely default entity (no fields set)
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);
    long id = box.put(new EmbeddedEntity());

    // 2. Read back
    EmbeddedEntity read = box.get(id);

    // 3. Assert all fields are at default/null
    assertNotNull(read);
    assertNull(read.getName());       // String defaults to null
    assertNull(read.getAddress());    // Embedded object defaults to null
}
```

---

#### TEST 4: `testPutAndGet_embeddedWithPartialNullFields`
**Purpose**: Verify that an embedded object with some null fields (e.g., city=null) persists correctly — the embedded object itself is NOT null, but some of its fields are.

```java
@Test
public void testPutAndGet_embeddedWithPartialNullFields() {
    // 1. Create embedded object with city=null but street and zip populated
    Address partial = new Address("Park Ave", null, 10001);
    EmbeddedEntity entity = new EmbeddedEntity();
    entity.setName("Partial");
    entity.setAddress(partial);

    // 2. Put and read back
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);
    long id = box.put(entity);
    EmbeddedEntity read = box.get(id);

    // 3. Assert the embedded object IS present (not null — street has a value)
    assertNotNull(read.getAddress());
    assertEquals("Park Ave", read.getAddress().getStreet());
    assertNull(read.getAddress().getCity());   // city was null
    assertEquals(10001, read.getAddress().getZip());
}
```

---

#### TEST 5: `testPutAndGet_embeddedWithOnlyPrimitiveSet`
**Purpose**: Verify that when only the primitive field (zip) of the embedded object is set, the embedded object is still reconstructed (not treated as null).

```java
@Test
public void testPutAndGet_embeddedWithOnlyPrimitiveSet() {
    // 1. Create embedded object with only zip set (strings are null)
    Address onlyZip = new Address(null, null, 99999);
    EmbeddedEntity entity = new EmbeddedEntity();
    entity.setAddress(onlyZip);

    // 2. Put and read back
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);
    long id = box.put(entity);
    EmbeddedEntity read = box.get(id);

    // 3. Assert embedded object IS present because zip != 0
    assertNotNull(read.getAddress());
    assertNull(read.getAddress().getStreet());
    assertNull(read.getAddress().getCity());
    assertEquals(99999, read.getAddress().getZip());
}
```

---

#### TEST 6: `testPutAndGet_embeddedAllObjectFieldsNullPrimitiveDefault`
**Purpose**: Edge case — embedded object exists in Java with all Strings null AND zip=0 (primitive default). On read, this is indistinguishable from "no embedded object" and should be read back as null.

```java
@Test
public void testPutAndGet_embeddedAllObjectFieldsNullPrimitiveDefault() {
    // 1. Create embedded object where all fields are at defaults
    Address allDefaults = new Address(null, null, 0);
    EmbeddedEntity entity = new EmbeddedEntity();
    entity.setAddress(allDefaults);

    // 2. Put and read back
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);
    long id = box.put(entity);
    EmbeddedEntity read = box.get(id);

    // 3. Assert embedded object reads as null
    //    (all String fields null + primitive at default → indistinguishable from null embedded)
    assertNull(read.getAddress());
}
```

---

#### TEST 7: `testPutGetUpdateGet`
**Purpose**: Verify that updating the embedded object's fields persists correctly on re-put.

```java
@Test
public void testPutGetUpdateGet() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

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
    assertEquals("HQ", read2.getName());   // name unchanged
    assertEquals("New St 99", read2.getAddress().getStreet());
    assertEquals("Hamburg", read2.getAddress().getCity());
    assertEquals(20095, read2.getAddress().getZip());
}
```

---

#### TEST 8: `testPutGetUpdateEmbeddedToNull`
**Purpose**: Verify that updating an entity to set its embedded object to null clears all flattened fields.

```java
@Test
public void testPutGetUpdateEmbeddedToNull() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

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
```

---

#### TEST 9: `testPutGetUpdateNullToEmbedded`
**Purpose**: Verify the reverse — entity initially has null embedded, then gets updated to a populated embedded object.

```java
@Test
public void testPutGetUpdateNullToEmbedded() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

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
```

---

#### TEST 10: `testPutManyAndGetAll`
**Purpose**: Verify batch put and getAll with multiple entities containing embedded objects.

```java
@Test
public void testPutManyAndGetAll() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

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
```

---

#### TEST 11: `testRemove`
**Purpose**: Verify delete of an entity with embedded object, including count checks and remove-twice semantics.

```java
@Test
public void testRemove() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

    // 1. Put entity
    EmbeddedEntity entity = new EmbeddedEntity();
    entity.setName("Temp");
    entity.setAddress(new Address("Temp St", "Nowhere", 0));
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
```

---

#### TEST 12: `testRemoveAll`
**Purpose**: Verify removeAll with multiple embedded entities.

```java
@Test
public void testRemoveAll() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

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
```

---

#### TEST 13: `testQueryByEmbeddedStringField`
**Purpose**: Verify querying by a flattened String property of the embedded object.

```java
@Test
public void testQueryByEmbeddedStringField() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

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

        // 3. Assert: 2 results (e1 and e3)
        assertEquals(2, results.size());
        assertEquals("Office 1", results.get(0).getName());
        assertEquals("Office 3", results.get(1).getName());
    }
}
```

---

#### TEST 14: `testQueryByEmbeddedIntField`
**Purpose**: Verify querying by the flattened Int property (zip) of the embedded object.

```java
@Test
public void testQueryByEmbeddedIntField() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

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
```

---

#### TEST 15: `testQueryByEmbeddedField_isNull`
**Purpose**: Verify querying for entities where the embedded field's property is null (i.e., the entire embedded object is null).

```java
@Test
public void testQueryByEmbeddedField_isNull() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

    // 1. Put one entity with address, one without
    EmbeddedEntity withAddr = new EmbeddedEntity();
    withAddr.setName("Has Address");
    withAddr.setAddress(new Address("St 1", "Berlin", 10115));
    box.put(withAddr);

    EmbeddedEntity noAddr = new EmbeddedEntity();
    noAddr.setName("No Address");
    noAddr.setAddress(null);
    box.put(noAddr);

    // 2. Query: address_street isNull (embedded object is null → all fields null)
    try (Query<EmbeddedEntity> query = box.query(
            EmbeddedEntity_.address_street.isNull()
    ).build()) {
        List<EmbeddedEntity> results = query.find();

        // 3. Assert: only the entity without address
        assertEquals(1, results.size());
        assertEquals("No Address", results.get(0).getName());
    }

    // 4. Query: address_street notNull
    try (Query<EmbeddedEntity> query = box.query(
            EmbeddedEntity_.address_street.notNull()
    ).build()) {
        List<EmbeddedEntity> results = query.find();
        assertEquals(1, results.size());
        assertEquals("Has Address", results.get(0).getName());
    }
}
```

---

#### TEST 16: `testQueryCombinedTopLevelAndEmbeddedField`
**Purpose**: Verify a query that combines conditions on both a top-level field and an embedded field.

```java
@Test
public void testQueryCombinedTopLevelAndEmbeddedField() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

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
```

---

#### TEST 17: `testCount`
**Purpose**: Verify count accuracy with mixed null/non-null embedded objects.

```java
@Test
public void testCount() {
    Box<EmbeddedEntity> box = store.boxFor(EmbeddedEntity.class);

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

    // 3. Assert count = 3 (count is entity-level, not dependent on embedded null/non-null)
    assertEquals(3, box.count());
}
```

---

#### TEST 18: `testPutAndGet_multipleEmbeddedFields`
**Purpose**: (Phase 4) Verify an entity with TWO embedded fields of the same type, each using a different prefix.

This test requires a separate entity (`MultiEmbeddedEntity`) with:
- `@Embedded Address billingAddress` → prefix `billingAddress_`
- `@Embedded Address shippingAddress` → prefix `shippingAddress_`

```java
@Test
public void testPutAndGet_multipleEmbeddedFields() {
    Box<MultiEmbeddedEntity> box = store.boxFor(MultiEmbeddedEntity.class);

    // 1. Create entity with both embedded fields populated
    MultiEmbeddedEntity entity = new MultiEmbeddedEntity();
    entity.setName("Order 1");
    entity.setBillingAddress(new Address("Billing St 1", "Berlin", 10115));
    entity.setShippingAddress(new Address("Shipping St 2", "Munich", 80331));
    long id = box.put(entity);

    // 2. Read back
    MultiEmbeddedEntity read = box.get(id);

    // 3. Assert both embedded objects are distinct and correct
    assertNotNull(read.getBillingAddress());
    assertEquals("Billing St 1", read.getBillingAddress().getStreet());
    assertEquals("Berlin", read.getBillingAddress().getCity());
    assertEquals(10115, read.getBillingAddress().getZip());

    assertNotNull(read.getShippingAddress());
    assertEquals("Shipping St 2", read.getShippingAddress().getStreet());
    assertEquals("Munich", read.getShippingAddress().getCity());
    assertEquals(80331, read.getShippingAddress().getZip());
}
```

---

#### TEST 19: `testPutAndGet_multipleEmbedded_oneNull`
**Purpose**: (Phase 4) Verify that one embedded field can be null while the other is populated.

```java
@Test
public void testPutAndGet_multipleEmbedded_oneNull() {
    Box<MultiEmbeddedEntity> box = store.boxFor(MultiEmbeddedEntity.class);

    // 1. Billing populated, shipping null
    MultiEmbeddedEntity entity = new MultiEmbeddedEntity();
    entity.setName("Order 2");
    entity.setBillingAddress(new Address("Bill St", "Berlin", 10115));
    entity.setShippingAddress(null);
    long id = box.put(entity);

    // 2. Read back
    MultiEmbeddedEntity read = box.get(id);

    // 3. Assert billing present, shipping null
    assertNotNull(read.getBillingAddress());
    assertEquals("Bill St", read.getBillingAddress().getStreet());
    assertNull(read.getShippingAddress());
}
```

---

#### TEST 20: `testPutAndGet_multipleEmbedded_bothNull`
**Purpose**: (Phase 4) Verify that both embedded fields can be null simultaneously.

```java
@Test
public void testPutAndGet_multipleEmbedded_bothNull() {
    Box<MultiEmbeddedEntity> box = store.boxFor(MultiEmbeddedEntity.class);

    // 1. Both null
    MultiEmbeddedEntity entity = new MultiEmbeddedEntity();
    entity.setName("Empty Order");
    entity.setBillingAddress(null);
    entity.setShippingAddress(null);
    long id = box.put(entity);

    // 2. Read back
    MultiEmbeddedEntity read = box.get(id);

    // 3. Assert both null
    assertEquals("Empty Order", read.getName());
    assertNull(read.getBillingAddress());
    assertNull(read.getShippingAddress());
}
```

---

#### TEST 21: `testQueryByMultipleEmbedded_differentPrefixes`
**Purpose**: (Phase 4) Verify querying across two differently-prefixed embedded fields.

```java
@Test
public void testQueryByMultipleEmbedded_differentPrefixes() {
    Box<MultiEmbeddedEntity> box = store.boxFor(MultiEmbeddedEntity.class);

    // 1. Put entities with different billing/shipping cities
    MultiEmbeddedEntity e1 = new MultiEmbeddedEntity();
    e1.setName("Order A");
    e1.setBillingAddress(new Address("St", "Berlin", 10115));
    e1.setShippingAddress(new Address("St", "Munich", 80331));
    box.put(e1);

    MultiEmbeddedEntity e2 = new MultiEmbeddedEntity();
    e2.setName("Order B");
    e2.setBillingAddress(new Address("St", "Munich", 80331));
    e2.setShippingAddress(new Address("St", "Berlin", 10115));
    box.put(e2);

    // 2. Query: billing city = "Berlin"
    try (Query<MultiEmbeddedEntity> query = box.query(
            MultiEmbeddedEntity_.billingAddress_city.equal("Berlin")
    ).build()) {
        List<MultiEmbeddedEntity> results = query.find();
        assertEquals(1, results.size());
        assertEquals("Order A", results.get(0).getName());
    }

    // 3. Query: shipping city = "Berlin"
    try (Query<MultiEmbeddedEntity> query = box.query(
            MultiEmbeddedEntity_.shippingAddress_city.equal("Berlin")
    ).build()) {
        List<MultiEmbeddedEntity> results = query.find();
        assertEquals(1, results.size());
        assertEquals("Order B", results.get(0).getName());
    }
}
```

---

#### TEST 22: `testPutAndGet_nestedEmbedded`
**Purpose**: (Phase 4) Verify nested embedding — an embedded object that itself contains an embedded object. Uses `ContactInfo { String phone; Address address; }` and `CustomerEntity { @Id long id; String name; @Embedded ContactInfo contact; }`.

Flat DB layout: `id`, `name`, `contact_phone`, `contact_address_street`, `contact_address_city`, `contact_address_zip`.

```java
@Test
public void testPutAndGet_nestedEmbedded() {
    Box<CustomerEntity> box = store.boxFor(CustomerEntity.class);

    // 1. Create nested embedded structure
    Address addr = new Address("Nested St", "Frankfurt", 60311);
    ContactInfo contact = new ContactInfo("+49123456", addr);
    CustomerEntity entity = new CustomerEntity();
    entity.setName("Max");
    entity.setContact(contact);
    long id = box.put(entity);

    // 2. Read back
    CustomerEntity read = box.get(id);

    // 3. Assert outer embedded
    assertNotNull(read.getContact());
    assertEquals("+49123456", read.getContact().getPhone());

    // 4. Assert inner embedded (nested)
    assertNotNull(read.getContact().getAddress());
    assertEquals("Nested St", read.getContact().getAddress().getStreet());
    assertEquals("Frankfurt", read.getContact().getAddress().getCity());
    assertEquals(60311, read.getContact().getAddress().getZip());
}
```

---

#### TEST 23: `testPutAndGet_nestedEmbedded_innerNull`
**Purpose**: (Phase 4) Verify nested embedding where the inner embedded is null but the outer embedded is populated.

```java
@Test
public void testPutAndGet_nestedEmbedded_innerNull() {
    Box<CustomerEntity> box = store.boxFor(CustomerEntity.class);

    // 1. Outer embedded with phone, but inner address is null
    ContactInfo contact = new ContactInfo("+49999999", null);
    CustomerEntity entity = new CustomerEntity();
    entity.setName("Lisa");
    entity.setContact(contact);
    long id = box.put(entity);

    // 2. Read back
    CustomerEntity read = box.get(id);

    // 3. Outer embedded present (phone is non-null)
    assertNotNull(read.getContact());
    assertEquals("+49999999", read.getContact().getPhone());

    // 4. Inner embedded is null
    assertNull(read.getContact().getAddress());
}
```

---

#### TEST 24: `testPutAndGet_nestedEmbedded_outerNull`
**Purpose**: (Phase 4) Verify nested embedding where the entire outer embedded is null.

```java
@Test
public void testPutAndGet_nestedEmbedded_outerNull() {
    Box<CustomerEntity> box = store.boxFor(CustomerEntity.class);

    // 1. Entire contact is null
    CustomerEntity entity = new CustomerEntity();
    entity.setName("NoContact");
    entity.setContact(null);
    long id = box.put(entity);

    // 2. Read back
    CustomerEntity read = box.get(id);

    // 3. Assert outer embedded is null (implies inner is also null)
    assertEquals("NoContact", read.getName());
    assertNull(read.getContact());
}
```

---

#### TEST 25: `testQueryByNestedEmbeddedField`
**Purpose**: (Phase 4) Verify querying by a property of the inner nested embedded object.

```java
@Test
public void testQueryByNestedEmbeddedField() {
    Box<CustomerEntity> box = store.boxFor(CustomerEntity.class);

    // 1. Put entities with different nested cities
    CustomerEntity e1 = new CustomerEntity();
    e1.setName("Customer1");
    e1.setContact(new ContactInfo("+1", new Address("St", "Berlin", 10115)));
    box.put(e1);

    CustomerEntity e2 = new CustomerEntity();
    e2.setName("Customer2");
    e2.setContact(new ContactInfo("+2", new Address("St", "Munich", 80331)));
    box.put(e2);

    // 2. Query by nested embedded city
    try (Query<CustomerEntity> query = box.query(
            CustomerEntity_.contact_address_city.equal("Berlin")
    ).build()) {
        List<CustomerEntity> results = query.find();
        assertEquals(1, results.size());
        assertEquals("Customer1", results.get(0).getName());
    }
}
```

---

### Test Infrastructure: `AbstractEmbeddedTest` — Full Logic

```java
public abstract class AbstractEmbeddedTest {
    protected File boxStoreDir;
    protected BoxStore store;

    // UID/ID counters for model building
    int lastEntityId;
    int lastIndexId;
    long lastUid;
    long lastEntityUid;
    long lastIndexUid;

    @Before
    public void setUp() throws IOException {
        boxStoreDir = File.createTempFile("embedded-test", "");
        boxStoreDir.delete(); // createTempFile creates a file, we need a dir

        store = createBoxStore();
    }

    @After
    public void tearDown() {
        if (store != null) {
            store.close();
            store.deleteAllFiles();
        }
        // Clean up directory
    }

    protected BoxStore createBoxStore() {
        BoxStoreBuilder builder = new BoxStoreBuilder(createModel()).directory(boxStoreDir);
        registerEntities(builder);
        return builder.build();
    }

    protected byte[] createModel() {
        ModelBuilder modelBuilder = new ModelBuilder();
        addEmbeddedEntity(modelBuilder);
        // Subclasses override to add additional entities (MultiEmbeddedEntity, CustomerEntity)
        addAdditionalEntities(modelBuilder);
        modelBuilder.lastEntityId(lastEntityId, lastEntityUid);
        modelBuilder.lastIndexId(lastIndexId, lastIndexUid);
        return modelBuilder.build();
    }

    protected void addAdditionalEntities(ModelBuilder modelBuilder) {
        // Override in subclass for multi-embedded and nested tests
    }

    protected void registerEntities(BoxStoreBuilder builder) {
        builder.entity(new EmbeddedEntity_());
        // Override in subclass to register additional entities
    }

    private void addEmbeddedEntity(ModelBuilder modelBuilder) {
        lastEntityUid = ++lastUid;
        EntityBuilder eb = modelBuilder.entity("EmbeddedEntity").id(++lastEntityId, lastEntityUid);

        // ordinal 0 → id (Long, ID flag)
        eb.property("id", PropertyType.Long)
                .id(EmbeddedEntity_.id.id, ++lastUid)
                .flags(PropertyFlags.ID);

        // ordinal 1 → name (String)
        eb.property("name", PropertyType.String)
                .id(EmbeddedEntity_.name.id, ++lastUid);

        // ordinal 2 → address_street (String) — flattened from Address.street
        eb.property("address_street", PropertyType.String)
                .id(EmbeddedEntity_.address_street.id, ++lastUid);

        // ordinal 3 → address_city (String) — flattened from Address.city
        eb.property("address_city", PropertyType.String)
                .id(EmbeddedEntity_.address_city.id, ++lastUid);

        // ordinal 4 → address_zip (Int) — flattened from Address.zip
        eb.property("address_zip", PropertyType.Int)
                .id(EmbeddedEntity_.address_zip.id, ++lastUid);

        int lastPropId = EmbeddedEntity_.address_zip.id;
        eb.lastPropertyId(lastPropId, lastUid);
        eb.entityDone();
    }
}
```

---

### Phase 4: Advanced Edge Cases

**Step 4.1 — Multiple Embedded Fields** (Tests 18–21)
- New entity `MultiEmbeddedEntity` with `@Embedded Address billingAddress` + `@Embedded Address shippingAddress`
- New hand-written `MultiEmbeddedEntity_`, `MultiEmbeddedEntityCursor`
- DB layout: `id`, `name`, `billingAddress_street`, `billingAddress_city`, `billingAddress_zip`, `shippingAddress_street`, `shippingAddress_city`, `shippingAddress_zip`

**Step 4.2 — Nested Embedded Objects** (Tests 22–25)
- New value object `ContactInfo { String phone; Address address; }`
- New entity `CustomerEntity { @Id long id; String name; @Embedded ContactInfo contact; }`
- DB layout: `id`, `name`, `contact_phone`, `contact_address_street`, `contact_address_city`, `contact_address_zip`
- New hand-written `CustomerEntity_`, `CustomerEntityCursor`

**Step 4.3 — Custom Prefix**
- Test `@Embedded(prefix = "billing_")` override
- Verify custom prefix appears in DB column names

**Step 4.4 — Naming Collision Detection**
- Document in `@Embedded` Javadoc that the default prefix (`fieldName_`) prevents collisions
- If a user specifies an empty prefix, collisions are their responsibility

### Phase 5: Final Validation & Cleanup

**Step 5.1 — Full Regression Test Run**
- Run `./gradlew :tests:objectbox-java-test:test`
- Confirm all new embedded tests pass
- Confirm all 438 existing passing tests still pass

**Step 5.2 — Code Review Checklist**
- Zero reflection at runtime (all mapping in generated code)
- SOLID compliance: single responsibility per class, clean separation
- DRY: no duplicated flattening logic
- All edge cases covered by tests
- Javadoc on `@Embedded` annotation is comprehensive

---

## 5. File Change Summary

### New Files (annotations module)
| File | Purpose |
|------|---------|
| `objectbox-java-api/.../annotation/Embedded.java` | `@Embedded` annotation definition |

### New Files (test module — `src/main/java/io/objectbox/embedded/`)
| File | Purpose |
|------|---------|
| `Address.java` | Embeddable value object (street, city, zip) |
| `ContactInfo.java` | Embeddable value object with nested `Address` (phone, address) |
| `EmbeddedEntity.java` | Parent entity with single `@Embedded Address` |
| `EmbeddedEntity_.java` | Hand-written EntityInfo with 5 flattened properties |
| `EmbeddedEntityCursor.java` | Hand-written Cursor with flattening put() |
| `MultiEmbeddedEntity.java` | Entity with two `@Embedded Address` fields |
| `MultiEmbeddedEntity_.java` | Hand-written EntityInfo with 8 flattened properties |
| `MultiEmbeddedEntityCursor.java` | Hand-written Cursor for dual-embedded entity |
| `CustomerEntity.java` | Entity with nested `@Embedded ContactInfo` |
| `CustomerEntity_.java` | Hand-written EntityInfo with 6 flattened properties |
| `CustomerEntityCursor.java` | Hand-written Cursor for nested-embedded entity |

### New Files (test module — `src/test/java/io/objectbox/embedded/`)
| File | Purpose |
|------|---------|
| `AbstractEmbeddedTest.java` | Test base: model building + BoxStore setup for embedded entities |
| `EmbeddedEntityTest.java` | Tests 1–17: basic CRUD, null handling, queries |
| `MultiEmbeddedEntityTest.java` | Tests 18–21: multiple embedded fields of same type |
| `CustomerEntityTest.java` | Tests 22–25: nested embedded objects |

### Files NOT Modified
- No changes to `Property.java`, `Cursor.java`, `ModelBuilder.java`, or `EntityInfo.java`
- The flattening is handled entirely in generated code (Cursor, EntityInfo, constructor)
- The existing runtime infrastructure already supports everything needed

---

## 6. Risk Assessment

| Risk | Mitigation |
|------|------------|
| JNI all-args constructor expects exact parameter count/order | Property ordinals must match constructor parameter order exactly — verified in tests |
| Native `collect*` signature mismatch for embedded field types | Use existing `collect*` variants that already handle the needed types (String, Double, Int, etc.) |
| Null embedded object detection at GET time | Convention: if all object-typed fields are null AND all primitives at default → embedded = null |
| Naming collisions between parent and embedded fields | Default prefix strategy (`fieldName_`) prevents collisions; document in @Embedded Javadoc |
| Pre-existing test failure (`maxDataSize`) confusing CI | Document as known issue, not related to @Embedded changes |

---

## 7. Success Criteria

1. All 25 new `@Embedded` tests pass (basic CRUD, null handling, queries, multi-field, nested)
2. All 438 existing passing tests continue to pass
3. Zero use of runtime reflection — all mapping is in hand-written generated code
4. `@Embedded` annotation has complete Javadoc
5. Clean build with no new compilation warnings
