# Implementation Plan — `@Embedded` (Component Mapping) for ObjectBox

**Author:** Senior Java Developer / Database Architect
**Date:** 2026-02-26
**Branch:** `12597_model_a`
**Status:** Phase 1 — Discovery & Planning (awaiting sign-off)

---

## 1. Environment & Baseline Audit

### 1.1 Tooling

| Tool | Version | Status |
|---|---|---|
| JDK | OpenJDK 21.0.10 (Homebrew, aarch64) | ✅ OK |
| Gradle Wrapper | 8.14.4 | ✅ OK |
| Kotlin (embedded) | 2.0.21 | ✅ OK |
| Processor toolchain | JDK 11 (Gradle-managed) / Java 8 target | ✅ OK |
| Generator toolchain | JDK 8 (Gradle-managed) | ✅ OK |
| OS | macOS (Darwin 25.3.0) | ✅ OK |

No missing or misconfigured dependencies detected.

### 1.2 Build & Test Baseline

| Module | Build | Tests | Notes |
|---|---|---|---|
| `objectbox-java-generator/objectbox-generator` | ✅ | ✅ | FreeMarker templates, model classes |
| `objectbox-java-generator/objectbox-processor` | ✅ | ✅ **94 tests / 0 failures** | compile-testing based |
| `objectbox-java-generator/objectbox-code-modifier` | ✅ | ✅ | Javassist bytecode transformer |
| `objectbox-java/objectbox-java-api` | ✅ | n/a (no tests) | Annotations only |
| `objectbox-java/objectbox-java` | ✅ compile | n/a (core lib; tests live in `tests/objectbox-java-test`) |
| `objectbox-java/tests/objectbox-java-test` | ✅ | ✅ `BoxTest.testPutAndGet` passed — native lib `objectbox-macos:5.2.0` resolved from Maven Central | See §5.4 for full integration-test analysis |

### 1.3 Cross-Project Dependency Note

`objectbox-java-generator` consumes `io.objectbox:objectbox-java-api:5.2.0` and `io.objectbox:objectbox-java:5.2.0` from **Maven repositories** (not project references). Adding `@Embedded` to `objectbox-java-api` requires publishing to `mavenLocal()` before the processor can see it.

**Verified:** `./gradlew :objectbox-java-api:publishToMavenLocal :objectbox-java:publishToMavenLocal` works.

---

## 2. Architecture Analysis

### 2.1 Module Map

```
┌──────────────────────────────────────────────────────────────────┐
│                     objectbox-java (SDK)                         │
│  ┌─────────────────────┐  ┌───────────────────────────────────┐  │
│  │ objectbox-java-api  │  │ objectbox-java                    │  │
│  │  (annotations)      │  │  Cursor, Box, Query, Property,    │  │
│  │  @Entity, @Id, ...  │  │  EntityInfo (runtime contracts)   │  │
│  └─────────────────────┘  └───────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
                              ▲ (Maven dep, 5.2.0)
                              │
┌──────────────────────────────────────────────────────────────────┐
│                objectbox-java-generator (Build Tools)            │
│  ┌───────────────────┐ ┌──────────────────┐ ┌─────────────────┐  │
│  │ objectbox-        │ │ objectbox-       │ │ objectbox-code- │  │
│  │   generator       │ │   processor      │ │   modifier      │  │
│  │ (model + FTL)     │ │ (APT, Kotlin)    │ │ (Javassist)     │  │
│  └───────────────────┘ └──────────────────┘ └─────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 Property Lifecycle (existing flow)

```
@Entity class Bill { @Id long id; String provider; }
        │
        ▼
┌─────────────────────────────────────────────────┐
│ ObjectBoxProcessor.parseEntity()                │
│  → Properties.parseField() per field            │
│  → Entity.addProperty(type, name) → Property    │
│  → IdSync writes objectbox-models/default.json  │
└─────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────┐
│ BoxGenerator + FreeMarker templates             │
│  • entity-info.ftl   → Bill_.java  (Property[]) │
│  • cursor.ftl        → BillCursor.java (put)    │
│  • myobjectbox.ftl   → MyObjectBox.java (model) │
└─────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────┐
│ ClassTransformer (bytecode, Gradle build phase) │
│  • Adds __boxStore field                        │
│  • Initializes ToOne/ToMany in constructors     │
│  • Fills Cursor.attachEntity() body             │
└─────────────────────────────────────────────────┘
```

### 2.3 CRITICAL ARCHITECTURAL CONSTRAINT — Read Path

**ObjectBox reads entities via JNI.** Entity construction happens in **native code** (`Cursor.nativeGetEntity`, `Query.nativeFind*`), which:

1. Calls the entity's **no-arg constructor** (or all-args, see `EntityFlags.USE_NO_ARG_CONSTRUCTOR`).
2. Sets fields **directly by name** via JNI `SetXxxField`, where the field name comes from `io.objectbox.Property.name`.

**Implication:** For a flattened property `priceCurrency`, JNI will look for a field literally named `priceCurrency` on the **entity class** — but the user's `Bill` class has `Money price`, not `String priceCurrency`.

Existing workarounds in codebase:
- **ToOne** uses `isVirtual=true` — JNI has **hard-coded** logic to strip the `Id` suffix and call `setTargetId` on a `ToOne` field. This is not extensible to arbitrary embedded types without native changes.

> **Native code (C++) is out of scope.** We cannot assume nested field-path support in JNI.

### 2.4 Chosen Strategy — **Synthetic Flat Fields + Runtime Hook**

The only zero-reflection, pure-build-tools solution that covers both read and write paths:

| Path | Mechanism |
|---|---|
| **Write** (Java → DB) | Generated `Cursor.put()` reads nested fields directly (`entity.price.currency`) with a null-guard on the container. No synthetic fields needed here. |
| **Read** (DB → Java) | **Bytecode transformer** injects `transient` synthetic fields on the entity (`String priceCurrency; double priceAmount;`) matching flattened property names. JNI sets these. A new runtime hook `Cursor.attachEmbedded(T)` copies synthetic → nested container. Hook wired into `Cursor.get/next/first/getAll/...` and `Query.find*`. |

**Why this works:**
- JNI sees regular fields on the entity → no native changes.
- Synthetic fields are `transient` → invisible to serialization / user code.
- Hook is a no-op by default → zero overhead for entities without `@Embedded`.
- Everything resolved at build time → **zero runtime reflection**.

---

## 3. Design

### 3.1 `@Embedded` Annotation Contract

```java
package io.objectbox.annotation;

/**
 * Flattens the fields of a value object into the parent entity's table.
 * The annotated type MUST have a public no-arg constructor.
 * The annotated type's fields are subject to the same type restrictions as
 * regular @Entity fields (scalar types, String, byte[], Date, etc.).
 *
 * Flattened property names default to <fieldName><CapFirstInnerField>,
 * e.g. `Money price` → `priceCurrency`, `priceAmount`.
 * Use `prefix` to override: @Embedded(prefix = "money_") → `money_currency`.
 */
@Retention(CLASS)  // Must be CLASS (not SOURCE) so the bytecode transformer can detect it
@Target(FIELD)
public @interface Embedded {
    String prefix() default "";
}
```

**Restrictions (enforced by processor, with compile-time errors):**
| # | Rule | Error trigger |
|---|---|---|
| R1 | Embedded type must NOT be annotated `@Entity` or `@BaseEntity`. | Processor error |
| R2 | Embedded type must have a public no-arg constructor. | Processor error |
| R3 | Embedded type fields must be supported scalar/object types (recursively same check as entity fields). | Processor error (reuses existing `getPropertyType` path) |
| R4 | Flattened property names must not collide with any existing entity property. | Processor error |
| R5 | `@Id`, `@Index`, `@Unique`, `@Convert`, relations (`ToOne`/`ToMany`) are **not allowed** inside the embedded type. | Processor error |
| R6 | Nested `@Embedded` (embedded-within-embedded) — **deferred to Phase 2**. | Processor error for now |
| R7 | `@Embedded` field cannot be `private` unless a getter+setter pair exists (same rule as regular properties). | Processor error |
| R8 | Embedded type and its fields must be `public` or package-private (accessible from generated Cursor). | Processor error |

### 3.2 Naming Strategy

| Annotation | Container field | Inner field | Flattened name |
|---|---|---|---|
| `@Embedded` | `price` | `currency` | `priceCurrency` |
| `@Embedded` | `price` | `amount` | `priceAmount` |
| `@Embedded(prefix="cost_")` | `price` | `currency` | `cost_currency` |
| `@Embedded(prefix="cost_")` | `price` | `amount` | `cost_amount` |

Default is **camelCase concatenation** (`fieldName + capFirst(innerName)`) — consistent with ObjectBox's existing `toOneId` convention.

### 3.3 Null Semantics

| Scenario | Behavior |
|---|---|
| `put()` with `entity.price == null` | All flattened columns stored as `null` (object types) or `0`/`false` (primitives). Mirrors existing nullable-wrapper handling in `PropertyCollector`. |
| `get()` when all flattened columns are `null`/default | Embedded container is **always reconstructed** (never `null` after read). User can check inner fields for sentinel values. |

> Rationale: guaranteed non-null container after read is simpler to reason about, matches the `ToOne` pattern (bytecode always initializes it), and avoids heuristic "all-null ⇒ container-null" ambiguity.

---

## 4. Change Inventory

### 4.1 `objectbox-java` (runtime SDK)

| File | Change | Why |
|---|---|---|
| `objectbox-java-api/.../annotation/Embedded.java` | **NEW** | Annotation definition. |
| `objectbox-java/.../Cursor.java` | **Add** `protected void attachEmbedded(T entity) {}` (no-op default).<br>**Wrap** `get()`, `next()`, `first()`, `getAll()`, `getBacklinkEntities()`, `getRelationEntities()` to call `attachEmbedded` on each returned entity. | Read-path hook. Single place to wire reconstruction. Overridden by generated cursors only when `@Embedded` is present. |
| `objectbox-java/.../query/Query.java` | **Add** private helper that obtains `Cursor<T>` via `InternalAccess.getActiveTxCursor(box)` and invokes `attachEmbedded` on results of `nativeFind*`, right before `resolveEagerRelation*`. | Query bypasses Cursor's Java methods; needs its own wiring. |
| `objectbox-java/.../InternalAccess.java` | **Add** `public static <T> void attachEmbedded(Cursor<T> c, T e)` pass-through. | Allows `Query` (different package) to reach `protected` cursor method without widening visibility. |

All changes are **additive** and **backwards-compatible** (default no-op preserves existing behavior exactly).

### 4.2 `objectbox-java-generator/objectbox-generator` (model + templates)

| File | Change | Why |
|---|---|---|
| `model/Property.java` | **Add** fields: `embeddedContainerName` (the field `price`), `embeddedContainerValueExpression` (accessor `price` or `getPrice()`), `embeddedContainerSetterExpression` (`price = ` or `setPrice(`), `embeddedContainerType` (FQCN `com.example.Money`), `embeddedInnerName` (the inner field `currency`), `embeddedInnerValueExpression` (accessor).<br>**Add** `PropertyBuilder.embedded(...)` fluent setter.<br>**Modify** `getValueExpression()` → if embedded, return `<containerExpr>.<innerExpr>`.<br>**Add** `isEmbedded()` predicate. | The model is the single source of truth consumed by both `PropertyCollector` and FreeMarker templates. |
| `model/Entity.java` | **Add** `hasEmbedded()` helper.<br>**Add** `getEmbeddedContainers()` → `List<EmbeddedContainer>` (groups properties by container for reconstruction codegen). | Used by `cursor.ftl`. |
| `model/EmbeddedContainer.java` | **NEW** small record-like class grouping flattened properties by their container field. | Clean template access — iterate containers, then their properties. |
| `PropertyCollector.java` | **Modify** `appendPreCall()` / `getValue()` → when property is embedded, hoist a null-guard on the container once per call-block (similar to existing nullable-wrapper logic at lines 336–380). | Prevents NPE on `entity.price.currency` when `price == null`. |
| `resources/.../cursor.ftl` | **Add** after the existing `attachEntity` stub: `<#if entity.hasEmbedded()>` block emitting an `attachEmbedded` override that constructs each container and copies synthetic → nested. | Read-path reconstruction. |
| `resources/.../entity-info.ftl` | **No change** — flattened properties appear in `propertiesColumns` like any other, so `Bill_.priceCurrency` is generated automatically. | Querying just works: `box.query(Bill_.priceAmount.greater(100))`. |
| `resources/.../myobjectbox.ftl` | **No change** — model JSON / schema builder already consumes the flattened property list. | — |

### 4.3 `objectbox-java-generator/objectbox-processor` (APT)

| File | Change | Why |
|---|---|---|
| `Properties.kt` | **Add** branch in `parseField()` at the point where unknown types currently error (lines ~527–531).<br>When `@Embedded` is present → delegate to new `parseEmbedded()`.<br>`parseEmbedded()` resolves the embedded `TypeElement`, iterates its fields, and for each recursively invokes the existing type-detection pipeline, then calls `Entity.addProperty(...).embedded(...)`. | Reuses existing type-mapping (DRY). No parallel code path for type detection. |
| `ObjectBoxProcessor.kt` | **Modify** `getSupportedAnnotationTypes()` only if needed (currently returns `@Entity`/`@BaseEntity`; embedded types are discovered transitively, so likely no change).<br>**Modify** `hasAllPropertiesConstructor()` → entities with `@Embedded` **never** use all-args ctor (force `USE_NO_ARG_CONSTRUCTOR` flag), because JNI would pass flattened args but user ctor expects `Money`. | Simpler contract; avoids JNI constructor-signature mismatch. |

### 4.4 `objectbox-java-generator/objectbox-code-modifier` (bytecode)

| File | Change | Why |
|---|---|---|
| `ClassConst.kt` | **Add** `val embeddedAnnotationName = Embedded::class.qualifiedName!!` and `cursorAttachEmbeddedMethodName = "attachEmbedded"`. | Constants for probing. |
| `ProbedClass.kt` | **Add** `hasEmbeddedRef: Boolean = false` and `embeddedFields: List<EmbeddedFieldInfo> = emptyList()` (name + type descriptor + prefix). | Propagate discovered `@Embedded` fields to the transformer. |
| `ClassProber.kt` | **Detect** fields annotated `@Embedded`; populate `embeddedFields`. | — |
| `ClassTransformer.kt` | **New** `transformEmbedded()`:<br>1. For each `@Embedded` field, resolve the embedded `CtClass` from the generated `Entity_` property list (same pattern as `findRelationNameInEntityInfo` at line 272).<br>2. Add `transient` synthetic `CtField`s to the entity with names matching the flattened property names (read from `Entity_`'s `Property<>` declarations — the property `.name` string is authoritative).<br>3. In every non-delegating constructor, inject `this.price = new com.example.Money();` (same pattern as existing ToOne initializer insertion at lines 308–348).<br>4. In the generated Cursor's `attachEmbedded` method body (initially empty stub from `cursor.ftl`), inject the field-copy statements — **OR** generate the full body in `cursor.ftl` and leave the transformer to only handle entity-side injection. **Chosen: full body in `cursor.ftl`**, transformer only touches the entity. Reason: `cursor.ftl` already has full type info from the generator model; transformer would have to re-derive it from bytecode. | JNI read-path needs real fields; constructors need non-null container for `put()` safety. |

---

## 5. TDD Test Matrix

All processor-level tests use **Google compile-testing** (`TestEnvironment`) — same harness as `ConvertTest.kt` (source-in-test, assert generated source tree equivalence + schema assertions). Transformer tests use the existing `AbstractTransformTest` harness.

### 5.1 Processor Tests — `EmbeddedTest.kt` (NEW)

| # | Test | Asserts |
|---|---|---|
| T1 | Happy path — `Bill { @Embedded Money price }` with `Money { String currency; double amount }` | Compiles without warnings; schema has 4 properties (`id`, `provider`, `priceCurrency`, `priceAmount`); types correct; `hasAllArgsConstructor()==false`. |
| T2 | Generated `BillCursor` matches expected source | `put()` reads `entity.price.currency` with null-guard; `attachEmbedded` constructs `Money` and sets fields from synthetic names. |
| T3 | Generated `Bill_` matches expected source | `priceCurrency` and `priceAmount` appear as `Property<Bill>` with correct types. |
| T4 | Custom prefix — `@Embedded(prefix="cost_")` | Properties are `cost_currency`, `cost_amount`. |
| T5 | Model JSON stability | Second compile against saved `embedded.json` model is idempotent (no UID churn). |
| T6 | **Error** — name collision: entity already has `priceCurrency` field | Fails with descriptive error. |
| T7 | **Error** — embedded type annotated `@Entity` | Fails with `R1` message. |
| T8 | **Error** — embedded type has no public no-arg ctor | Fails with `R2` message. |
| T9 | **Error** — embedded type contains `ToOne` | Fails with `R5` message. |
| T10 | **Error** — nested `@Embedded` inside embedded type | Fails with `R6` "not yet supported" message. |
| T11 | **Error** — unsupported field type inside embedded class | Fails with existing "Field type not supported" error, attributed to nested field. |
| T12 | Multiple `@Embedded` fields in one entity (two `Money`, two `Address`) | All flatten correctly; no collisions with distinct prefixes. |
| T13 | Private embedded container field + getter/setter | Generated code uses `getPrice()` / `setPrice(...)`. |
| T14 | Private inner field + getter/setter | Generated `put()` uses `getPrice().getCurrency()`. |
| T15 | Null container on write — `price == null` | Generated `put()` passes `0` for `__id` of each embedded property (column written as absent/null). Verify via cursor source inspection. |

### 5.2 Bytecode Transformer Tests — `ClassTransformerEmbeddedTest.kt` (NEW)

| # | Test | Asserts |
|---|---|---|
| B1 | Entity with `@Embedded` gets synthetic fields | Transformed `CtClass` contains `transient String priceCurrency; transient double priceAmount;`. |
| B2 | No-arg constructor initializes the embedded container | Bytecode shows `this.price = new Money();` before any subclass initialization. |
| B3 | User-initialized embedded field is not overwritten | If user writes `public Money price = new Money("EUR", 0)`, transformer detects existing initializer and skips (same pattern as `initializedRelationFields`). |
| B4 | Entity without `@Embedded` is untouched | Byte-identical pass-through (or `wasTransformed == false`). |

### 5.3 Runtime Library Unit Tests — `objectbox-java`

| # | Test | Asserts |
|---|---|---|
| C1 | `Cursor.attachEmbedded` default is no-op | Existing entities (no embedded) are returned unchanged. |
| C2 | Hook is called on `get/next/first/getAll` | Mock subclass overriding `attachEmbedded` → verify called exactly once per entity. |

### 5.4 Integration Tests — `tests/objectbox-java-test` (real JNI roundtrip)

#### 5.4.1 Viability — corrected finding

**Initial assumption was wrong.** `AGENTS.md`'s "JDK bundling the ObjectBox Linux library" refers to the CI Linux runner, not a general local constraint. On macOS:

- `build.gradle.kts:57-63` pulls `obxJniLibVersion` → `io.objectbox:objectbox-macos:5.2.0` (Maven Central)
- Verified locally: `./gradlew :tests:objectbox-java-test:test --tests "io.objectbox.BoxTest.testPutAndGet"` → **PASSED** (JNI lib loaded: `ObjectBox Database version: 5.1.1-pre-2026-02-16 (lmdb, VectorSearch)`)

**Conclusion:** true put→get→query roundtrip tests against the real native engine **can and will** run locally.

#### 5.4.2 Critical caveat — this module bypasses the generator

`TestEntity.java:30-36` states: *"The annotations in this class have no effect as the Gradle plugin is not configured in this project."*

All artifacts in this module are **hand-authored** and the schema is built **programmatically** via `ModelBuilder` (see `AbstractObjectBoxTest.addTestEntity()`, `relation/MyObjectBox.getModel()`). The bytecode transformer also does not run.

The existing relation tests show the established pattern for simulating transformer output — `Order.java:42-52`:

```java
long customerId;                                          // flat field hand-declared (normally virtual)
private ToOne<Customer> customer = new ToOne<>(this, …);  // normally injected by transformer
transient BoxStore __boxStore;                            // normally injected by transformer
```

`@Embedded` integration tests follow the **same pattern**: hand-write everything the generator+transformer would have produced.

#### 5.4.3 Test artifacts to add (hand-authored)

Under `tests/objectbox-java-test/src/main/java/io/objectbox/embedded/`:

| File | What it simulates | Key contents |
|---|---|---|
| `TestMoney.java` | User's value object | `public String currency; public double amount;` + no-arg ctor. No annotations. |
| `TestBill.java` | User's entity **post-transform** | `@Id long id; String provider; TestMoney price;` **plus** hand-declared synthetic flats: `transient String priceCurrency; transient double priceAmount;` (this is what the transformer would inject). No-arg ctor. |
| `TestBill_.java` | Generated `EntityInfo` | Flattened property constants: `id`, `provider`, `priceCurrency`, `priceAmount`. Mirrors `Order_.java`. |
| `TestBillCursor.java` | Generated `Cursor` | `put()` reads `entity.price.currency` with null-guard; `attachEmbedded()` override copies `entity.priceCurrency → entity.price.currency` etc. Mirrors `OrderCursor.java`. |
| `MyObjectBox.java` | Generated builder | `ModelBuilder` with flat properties `priceCurrency`/`priceAmount` + `EntityFlags.USE_NO_ARG_CONSTRUCTOR`. Mirrors `relation/MyObjectBox.java`. |

Under `tests/objectbox-java-test/src/test/java/io/objectbox/embedded/`:

| File | Purpose |
|---|---|
| `AbstractEmbeddedTest.java` | Base class — opens `BoxStore` with the hand-built `MyObjectBox.builder()`, provides `Box<TestBill>`, teardown. Mirrors `AbstractRelationTest`. |
| `EmbeddedBoxTest.java` | The roundtrip tests (I1–I5 below). |

#### 5.4.4 Test matrix — `EmbeddedBoxTest.java`

| # | Test | Asserts | Validates |
|---|---|---|---|
| I1 | `testPutAndGet` — `put(bill{price=Money("USD",42.5)})` then `get(id)` | `result.price != null`, `result.price.currency.equals("USD")`, `result.price.amount == 42.5`. | Full JNI roundtrip: `collect313311` → LMDB → `nativeGetEntity` → synthetic-field fill → `attachEmbedded` copy. **This is the critical end-to-end proof.** |
| I2 | `testPutNullContainerAndGet` — `put(bill{price=null})` then `get(id)` | `result.price != null` (transformer-style init in test ctor), `result.price.currency == null`, `result.price.amount == 0.0`. | Null-container write semantics (columns absent in DB → JNI sets defaults on synthetic fields → defaults copied into fresh container). |
| I3 | `testGetAll` — put 3 bills with distinct embedded values, `box.getAll()` | All 3 have correct non-null `price` with correct values. | `attachEmbedded` fires once per element of `nativeGetAllEntities` result list. |
| I4 | `testQueryByEmbeddedField` — put mixed, `query(TestBill_.priceCurrency.equal("EUR")).find()` | Returns only EUR bills; each with correctly hydrated `price`. | `Query.find()` path (separate from `Cursor.get()`) invokes hook via `InternalAccess`. |
| I5 | `testOverwriteEmbedded` — put, mutate `bill.price.amount`, put again, `get` | Latest value persisted. | Update path — no stale synthetic state leaking between `put` and `get`. |

#### 5.4.5 What this validates vs. what it does NOT

**Validates:**
- JNI genuinely populates fields named `priceCurrency`/`priceAmount` when `USE_NO_ARG_CONSTRUCTOR` is set — proves the core read-path hypothesis against real native code.
- `Cursor.attachEmbedded` wiring is complete across every read entry point (`get`/`getAll`/`query.find`).
- Write-path null-guard produces absent columns the native lib accepts.

**Does NOT validate (covered elsewhere):**
- That the **generator actually emits** the Cursor/EntityInfo code we hand-wrote — that's M1/M2 (processor tests with `compile-testing`, golden-file comparison).
- That the **transformer actually injects** the synthetic fields — that's M3 (Javassist tests on `CtClass` output).

This is standard practice in this codebase: `Order.java` follows exactly the same pattern of hand-declaring transformer-injected artifacts to test runtime behavior in isolation from the build-time pipeline.

#### 5.4.6 Gap acknowledged

There is no single test that exercises **APT → transformer → JNI** end-to-end in one Gradle invocation. `TestEntity.java:36` explicitly directs such tests to *"the internal integration test project"* — not part of this repository. Mitigation: the three layers are independently verified and the integration tests validate the seam (JNI ↔ runtime hook) against the **real** native library, which is the highest-risk boundary.

---

## 6. Milestones & Workflow

> Each milestone delivers **tests first → red → implement → green → refactor**.
> I will stop after each milestone and report results for your confirmation before proceeding.

### M0 — Foundation (runtime hooks + annotation)
1. Create `@Embedded` annotation in `objectbox-java-api`.
2. Add `attachEmbedded` hook + wiring in `Cursor.java`, `Query.java`, `InternalAccess.java`.
3. `./gradlew :objectbox-java-api:publishToMavenLocal :objectbox-java:publishToMavenLocal`.
4. Write tests C1, C2 (mock-based, no JNI). Run — green.
5. **Stop → confirm with user.**

### M1 — Generator Model
1. Write tests T1, T4, T6–T11 (schema + error cases) in `EmbeddedTest.kt`. Run — **red** (annotation unknown to processor).
2. Extend `Property.java`, add `EmbeddedContainer.java`, `Entity.java` helpers.
3. Extend `Properties.kt` → `parseEmbedded()`, name-collision check, forced no-arg ctor.
4. Run tests → **green** for schema/error cases.
5. **Stop → confirm.**

### M2 — Code Generation (write path)
1. Write tests T2, T3, T5, T12–T15 (generated source equivalence). Run — **red**.
2. Extend `PropertyCollector.java` null-guard for embedded container.
3. Extend `cursor.ftl` → `attachEmbedded` override stub.
4. Run tests → **green**.
5. **Stop → confirm.**

### M3 — Bytecode Transformer (read path completion)
1. Write tests B1–B4 in `ClassTransformerEmbeddedTest.kt`. Run — **red**.
2. Extend `ClassProber.kt`, `ProbedClass.kt`, `ClassConst.kt`.
3. Extend `ClassTransformer.kt` → synthetic field injection + constructor initializer.
4. Run tests → **green**.
5. Full regression: all 94+ existing processor tests still green; all transformer tests still green.
6. **Stop → confirm.**

### M4 — Integration Roundtrip (real JNI)
1. Hand-author `TestMoney`, `TestBill` (+ synthetic flats), `TestBill_`, `TestBillCursor`, `MyObjectBox`, `AbstractEmbeddedTest` per §5.4.3.
2. Write tests I1–I5. Run — **green first time** (no production code changes in this milestone; this is pure integration verification of M0–M3).
   - If **red**: one of M0's hook wirings is incomplete (e.g. `Query.find()` not calling `attachEmbedded`). Fix in `Cursor.java`/`Query.java`, re-run.
3. Full regression: `./gradlew :tests:objectbox-java-test:test` — all existing integration tests still green.
4. **Stop → final review.**

### M5 — Hardening (if time permits / Phase 2)
- Nested `@Embedded` (depth > 1) — lift restriction R6.
- `@NameInDb` / `@Uid` pass-through on inner fields.
- `@Convert` inside embedded class.
- `@Embedded` in `@BaseEntity` super classes (inheritance + embedding).

---

## 7. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| JNI `getAll`/`nativeFind` returns list with huge count → iterating `attachEmbedded` adds overhead. | Low | Low | Hook is a no-op unless entity has `@Embedded`. For embedded entities, the copy is one field assignment per property — far cheaper than the JNI call itself. Overhead is O(n × embedded_field_count), strictly linear, zero allocations beyond the container itself. |
| Synthetic field name collides with an unrelated user method/field. | Low | Medium | Processor performs collision check (T6). User can escape via `prefix`. |
| `mavenLocal()` not resolved before processor build when iterating locally. | Medium | Low | Documented in dev workflow; Gradle composite build is a future improvement. |
| Entities with `@Embedded` returned from `nativeGetBacklinkEntities` (relation resolution) | Low | Medium | Covered — hook wired in `Cursor.getBacklinkEntities()` too. |
| User serializes entity (Kryo/Gson) and sees "extra" synthetic fields. | Low | Low | Synthetic fields are `transient`. If a serializer ignores `transient`, user sees harmless default values — document. |
| `ObjectWithScore<T>` from `Query.findWithScores` not covered. | Low | Low | Add hook invocation in `Query.findWithScores` alongside `resolveEagerRelations`. |

---

## 8. Out of Scope

- **Native (C++) ObjectBox library changes.** Everything in this plan is Java/Kotlin/bytecode only.
- **Cross-repository pipeline test** (APT → transformer → JNI in one Gradle invocation). `TestEntity.java:36` directs this to *"the internal integration test project"* which is outside this repo. Mitigated by M4 — see §5.4.6.
- **Kotlin `data class` immutability.** `@Embedded` on a Kotlin `data class` with `val` inner fields would need constructor-based reconstruction. Deferred — require mutable fields in embedded type for MVP.
- **`objectbox-generator` (Go)** — the Go-based generator is a parallel codebase and will not be modified.

---

## 9. Sign-Off Request

Phase 1 (Discovery & Planning) is complete. The build baseline is green (94/94 processor tests, generator + code-modifier build clean).

**I am awaiting your explicit confirmation to proceed to Milestone M0 (Foundation).**
