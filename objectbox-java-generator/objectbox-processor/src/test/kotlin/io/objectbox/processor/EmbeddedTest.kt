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

package io.objectbox.processor

import com.google.common.truth.Truth.assertThat
import io.objectbox.generator.model.PropertyType
import org.intellij.lang.annotations.Language
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Tests for `@Embedded` annotation processing.
 *
 * M1 scope: model only — verify the annotation processor recognizes `@Embedded` fields,
 * walks the embedded POJO, and produces flattened synthetic [Property] entries in the
 * [Entity] model with correctly prefixed names and resolved property types.
 *
 * Code generation (Cursor.put() body, attachEmbedded() override, EntityInfo_ statics) is M2.
 *
 * ### Design reference
 * - `Properties.kt:parseField()` — insertion point for the `@Embedded` branch
 * - `Relations.kt:ensureToOneIdRefProperty()` — synthetic-property-synthesis pattern
 * - `ToOne.kt` — container-model-class pattern
 * - `@Embedded.prefix()` default sentinel `USE_FIELD_NAME = "\0"` distinguishes
 *   "derive prefix from field name" (default) from explicit `prefix=""` (no prefix)
 *
 * ### Prefix naming rule
 * - default (`USE_FIELD_NAME`):  fieldName + capFirst(innerField) → `price` + `Currency` = `priceCurrency`
 * - `prefix = ""`:               innerField unchanged             → `currency`
 * - `prefix = "cost"`:           prefix + capFirst(innerField)    → `cost` + `Currency` = `costCurrency`
 */
class EmbeddedTest : BaseProcessorTest() {

    /**
     * RED driver: before this feature, a `@Embedded Money price` field falls through to
     * `Properties.autoConvertedPropertyBuilderOrNull()` which errors with
     * `"Field type "com.example.Money" is not supported"`.
     *
     * GREEN: processor flattens `Money`'s two fields into synthetic properties on `Bill`:
     * - `priceCurrency` (String) — from `price` + capFirst("currency")
     * - `priceAmount` (Long)     — from `price` + capFirst("amount")
     */
    @Test
    fun embedded_defaultPrefix_flattensWithFieldNamePrefix() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;

                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-default.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        env.compile().assertThatIt { succeededWithoutWarnings() }

        val entity = env.schema.entities.single { it.className == "Bill" }
        val propNames = entity.properties.map { it.propertyName }

        // Core assertion: @Embedded expanded into exactly these synthetic properties
        assertThat(propNames).containsExactly("id", "priceCurrency", "priceAmount")

        // Types must be resolved via TypeHelper.getPropertyType() on each inner field
        val currencyProp = entity.properties.single { it.propertyName == "priceCurrency" }
        assertType(currencyProp, PropertyType.String)

        val amountProp = entity.properties.single { it.propertyName == "priceAmount" }
        // `long amount` is a Java primitive → typeNotNull
        assertPrimitiveType(amountProp, PropertyType.Long)

        // Synthetic props must NOT be virtual (native layer sets transformer-injected fields directly)
        assertThat(currencyProp.isVirtual).isFalse()
        assertThat(amountProp.isVirtual).isFalse()

        // Entity must record the embedded container for downstream codegen (M2)
        assertThat(entity.hasEmbedded()).isTrue()
        val embedded = entity.embeddedFields.single()
        assertThat(embedded.name).isEqualTo("price")
        assertThat(embedded.typeSimpleName).isEqualTo("Money")
        // Both synthetic properties must link back to their embedded origin
        assertThat(embedded.properties).containsExactly(currencyProp, amountProp)
    }

    /**
     * Explicit `prefix = ""` → flatten without any prefix.
     * `@Embedded(prefix = "") Money payment` → `currency`, `amount` (no `payment` prefix).
     */
    @Test
    fun embedded_emptyPrefix_flattensWithoutPrefix() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val receipt =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Receipt {
                @Id public long id;
                @Embedded(prefix = "") public Money payment;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-no-prefix.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Receipt", receipt)
            }

        env.compile().assertThatIt { succeededWithoutWarnings() }

        val entity = env.schema.entities.single { it.className == "Receipt" }
        assertThat(entity.properties.map { it.propertyName })
            .containsExactly("id", "currency", "amount")
    }

    /**
     * Explicit custom prefix. `@Embedded(prefix = "cost") Money price` → `costCurrency`, `costAmount`.
     * The annotated field's name (`price`) is ignored when a prefix is explicitly set.
     */
    @Test
    fun embedded_customPrefix_flattensWithGivenPrefix() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val invoice =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Invoice {
                @Id public long id;
                @Embedded(prefix = "cost") public Money price;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-custom-prefix.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Invoice", invoice)
            }

        env.compile().assertThatIt { succeededWithoutWarnings() }

        val entity = env.schema.entities.single { it.className == "Invoice" }
        assertThat(entity.properties.map { it.propertyName })
            .containsExactly("id", "costCurrency", "costAmount")
    }

    /**
     * Two embedded fields of the same type with different prefixes — no name collision.
     * Proves prefix disambiguation works and `Entity.trackUniqueName()` accepts the result.
     */
    @Test
    fun embedded_twoFieldsSameType_distinctPrefixes_noCollision() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val transfer =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Transfer {
                @Id public long id;
                @Embedded public Money source;
                @Embedded public Money target;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-two-same-type.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Transfer", transfer)
            }

        env.compile().assertThatIt { succeededWithoutWarnings() }

        val entity = env.schema.entities.single { it.className == "Transfer" }
        assertThat(entity.properties.map { it.propertyName })
            .containsExactly(
                "id",
                "sourceCurrency", "sourceAmount",
                "targetCurrency", "targetAmount"
            )
        assertThat(entity.embeddedFields).hasSize(2)
    }

    /**
     * Static / transient / `@Transient` fields in the embedded type must be skipped,
     * mirroring `Properties.parseField()`'s skip rules for entity fields.
     */
    @Test
    fun embedded_skipsStaticTransientAndAnnotatedTransient() {
        @Language("Java")
        val geo =
            """
            package com.example;

            import io.objectbox.annotation.Transient;

            public class Geo {
                public static final String SYSTEM = "WGS84"; // skipped: static
                public transient int cacheHash;              // skipped: transient
                @Transient public String debugLabel;         // skipped: @Transient
                public double lat;
                public double lon;
                public Geo() {}
            }
            """.trimIndent()

        @Language("Java")
        val place =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Place {
                @Id public long id;
                @Embedded public Geo geo;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-skip-transient.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Geo", geo)
                addSourceFile("com.example.Place", place)
            }

        env.compile().assertThatIt { succeededWithoutWarnings() }

        val entity = env.schema.entities.single { it.className == "Place" }
        // Only lat/lon should survive; static/transient/@Transient are skipped
        assertThat(entity.properties.map { it.propertyName })
            .containsExactly("id", "geoLat", "geoLon")
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // region M1.3 — Constraint validation (negative paths: these must produce COMPILE ERRORS)
    //
    // Each test proves the processor rejects invalid @Embedded usage with a clear, actionable
    // message — no silent misbehaviour, no cryptic downstream codegen failure.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * An `@Embedded` container's type must NOT itself be an `@Entity` — embedding an entity
     * would mean copying its fields (including `@Id`) into the owner, which makes no sense.
     * Users who want entity-to-entity composition should use `ToOne`/`ToMany` relations.
     *
     * Today this falls through: `Customer`'s fields get flattened, including its `@Id long id`,
     * which collides with the owner's `id` via `trackUniqueName()` — but with an opaque
     * "Duplicate name" error. We want a precise "X is an @Entity, use a relation" message.
     */
    @Test
    fun embedded_onEntityType_errors() {
        @Language("Java")
        val customer =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;

            @Entity
            public class Customer {
                @Id public long id;
                public String name;
            }
            """.trimIndent()

        @Language("Java")
        val order =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Order {
                @Id public long id;
                @Embedded public Customer customer;  // ← ERROR: Customer is @Entity
            }
            """.trimIndent()

        TestEnvironment("embedded-on-entity.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Customer", customer)
                addSourceFile("com.example.Order", order)
            }
            .compile()
            .assertThatIt {
                failed()
                hadErrorContaining("@Embedded 'customer': type 'Customer' is an @Entity")
                hadErrorContaining("use a ToOne or ToMany relation instead")
            }
    }

    /**
     * The embedded type must have a no-argument constructor so that M2's generated
     * `attachEmbedded()` override can instantiate it (`new Money()`) when hydrating.
     *
     * Java provides a default no-arg ctor only if NO explicit ctor is declared; declaring
     * `Money(String, long)` suppresses it.
     */
    @Test
    fun embedded_noNoArgConstructor_errors() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                // Only a full-args ctor — NO no-arg constructor
                public Money(String currency, long amount) {
                    this.currency = currency;
                    this.amount = amount;
                }
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        TestEnvironment("embedded-no-ctor.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()
            .assertThatIt {
                failed()
                hadErrorContaining("@Embedded 'price': type 'Money' must have a no-argument constructor")
            }
    }

    // ─── embedded_nestedEmbedded_errors() — REMOVED in P2.4 ───
    // The M1.3 "nested @Embedded not supported" rejection test was deleted when P2.4
    // added full nesting support (compound prefix flattening + chained hoist). The
    // positive-path replacement tests live in the P2.4 region below:
    //   · embedded_nested_compoundPrefixFlattening       — model assertions
    //   · embedded_nested_cursorEmitsChainedHoistWithNullPropagation — codegen assertions
    //   · embedded_nested_cycleDetection_errors          — the NEW boundary case
    // Same lifecycle as G2's @Convert rejection test → removed when P2.2 added support.

    /**
     * Prefix collision — a synthetic property's name clashes with an existing property on the
     * entity. The underlying mechanism is `Entity.trackUniqueName()` (case-insensitive), which
     * `addProperty()` already invokes; this test pins the error surface and wording so that
     * M1.2's `parseEmbedded()` reports it with enough context (which embedded field, which
     * inner field) instead of a bare "Duplicate name".
     */
    @Test
    fun embedded_prefixCollisionWithRegularProperty_errors() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val wallet =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Wallet {
                @Id public long id;
                public String currency;                        // regular property
                @Embedded(prefix = "") public Money balance;   // ← prefix="" → synthetic 'currency' collides
            }
            """.trimIndent()

        TestEnvironment("embedded-prefix-collision.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Wallet", wallet)
            }
            .compile()
            .assertThatIt {
                failed()
                // M1.2 already catches the ModelException, but the message must name the
                // @Embedded field, the inner field, and the synthetic name — all three.
                hadErrorContaining("Could not add synthetic @Embedded property 'currency'")
                hadErrorContaining("from 'balance.currency'")
            }
    }

    /**
     * `@Embedded` on an abstract class (or interface) cannot work — `new Money()` in the
     * generated `attachEmbedded()` would be invalid Java.
     *
     * `DeclaredType` alone doesn't catch this: an abstract class IS a DeclaredType and passes
     * the existing check in `parseEmbedded()`.
     */
    @Test
    fun embedded_abstractType_errors() {
        @Language("Java")
        val money =
            """
            package com.example;

            public abstract class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        TestEnvironment("embedded-abstract.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()
            .assertThatIt {
                failed()
                hadErrorContaining("@Embedded 'price': type 'Money' must be a concrete class")
            }
    }

    // endregion M1.3

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // region M2.2 — Write path: put() reads through the embedded container
    //
    // Generated Cursor.put() must dereference `entity.price.currency` (the REAL path on the
    // user's entity) rather than `entity.priceCurrency` (the synthetic field that doesn't
    // exist until the transformer runs). The container (`entity.price`) can be null, so every
    // embedded property's value must be guarded: when the container is null, pass property-ID
    // zero (tells native "skip this column") and a type-default value (ignored by native).
    //
    // Test strategy: the RED state is M1's `put()` which SKIPS embedded properties entirely —
    // `collect004000` with all zeros. GREEN state: `collect313311` (1 String + 1 Long triggers
    // that signature) with hoisted container local, conditional IDs, and container-path reads.
    //
    // We use `contentsAsString().contains()` fragment assertions rather than full source match
    // because (a) the exact collect signature could change with future properties added to the
    // test entity, and (b) failure messages are sharper ("expected `__emb_price.currency`").
    // The critical proof is that the generated Cursor COMPILES (javac validates every access
    // path) AND contains the container-path reads.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * RED driver: M1's `PropertyCollector` constructor skips `isEmbedded()` properties, so
     * `put()` passes zeros for those columns — data written via `box.put(bill)` would silently
     * drop `currency` and `amount`.
     *
     * GREEN: `put()` body must contain, in order:
     *  1. **Hoisted container local** — `Money __emb_price = entity.price;` — computed once,
     *     reused for all synthetic properties from this container. Avoids repeated
     *     `entity.price != null` evaluations AND makes the null-guard structure obvious.
     *  2. **Conditional property-IDs** — `int __idN = __emb_price != null ? __ID_X : 0;` —
     *     ID=0 tells native collect() to skip the column (leaves it NULL in DB). This is the
     *     SAME mechanism used for nullable scalar properties already.
     *  3. **Container-path reads** — `__emb_price.currency` / `__emb_price.amount` — NOT
     *     `entity.priceCurrency` (synthetic name).
     *
     * Negative assertions prove M1's fail-loud guard didn't silently leak through: the
     * generated put() must NOT contain `getPriceCurrency()` (the default value-expression for
     * a non-field-accessible synthetic property) and must NOT pass literal 0/null for
     * property-ID 0 in the slots that should carry data.
     */
    @Test
    fun embedded_put_readsThroughContainerWithNullGuard() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-put.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        val compilation = env.compile()

        // Gate: generated Cursor must COMPILE. If this fails, javac rejected a container-path
        // read (e.g. `Money` not imported, or `entity.price` not accessible, or a missing
        // synthetic-name getter leaked through).
        compilation.assertThatIt { succeededWithoutWarnings() }

        // Inspect put() body.
        val cursor = compilation.generatedSourceFileOrFail("com.example.BillCursor")
        val src = cursor.contentsAsString(StandardCharsets.UTF_8)

        // ─── 1. Hoisted container local ───
        // The exact variable name prefix `__emb_` is our chosen convention — it mirrors
        // `__id`, `__assignedId`, etc. (double-underscore prefix = generator-internal).
        src.contains("Money __emb_price = entity.price;")

        // ─── 2. Conditional property-IDs guarded on container null ───
        // Both embedded properties must route through the same hoisted local.
        src.contains("__emb_price != null ? __ID_priceCurrency : 0")
        src.contains("__emb_price != null ? __ID_priceAmount : 0")

        // ─── 3. Container-path reads ───
        // `__emb_price.currency` proves the READ goes through the container's real field,
        // not through a synthetic-named field on the entity.
        src.contains("__emb_price.currency")
        src.contains("__emb_price.amount")

        // ─── Negative: fail-loud guards must not have leaked ───
        // If M2's PropertyCollector forgot to handle isEmbedded() and fell through to the
        // default path, the generated put() would reference a non-existent getter
        // (and javac would have failed the succeededWithoutWarnings() gate above — but
        // belt-and-suspenders here for clarity).
        src.doesNotContain("getPriceCurrency")
        src.doesNotContain("getPriceAmount")

        // ─── Negative: M1's skip must not still be in effect ───
        // M1's put() was `collect004000(... 0, 0, 0, 0, 0, 0, 0, 0)` — no real data.
        // After M2, at least one collect call must reference __ID_priceCurrency by name
        // (indirectly via the conditional), not just zeros.
        src.doesNotContain("collect004000")
    }

    /**
     * Two `@Embedded` fields → put() must hoist TWO distinct container locals and guard each
     * synthetic property on its OWN container. Regression guard for the "hoist once per
     * container, not per property" design: ensures the hoisting loop iterates
     * `entity.getEmbeddedFields()` correctly and doesn't e.g. hoist only the first container
     * or use the wrong local for cross-container properties.
     */
    @Test
    fun embedded_put_twoContainers_hoistedIndependently() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val transfer =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Transfer {
                @Id public long id;
                @Embedded public Money source;
                @Embedded public Money target;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-put-two.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Transfer", transfer)
            }

        val compilation = env.compile()
        compilation.assertThatIt { succeededWithoutWarnings() }

        val src = compilation.generatedSourceFileOrFail("com.example.TransferCursor")
            .contentsAsString(StandardCharsets.UTF_8)

        // Each container hoisted to its own local — not mixed up.
        src.contains("Money __emb_source = entity.source;")
        src.contains("Money __emb_target = entity.target;")

        // Cross-check: each property guarded on the CORRECT container.
        // sourceCurrency → guarded by __emb_source (not target).
        src.contains("__emb_source != null ? __ID_sourceCurrency : 0")
        src.contains("__emb_source.currency")
        // targetAmount → guarded by __emb_target (not source).
        src.contains("__emb_target != null ? __ID_targetAmount : 0")
        src.contains("__emb_target.amount")
    }

    // endregion M2.2

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // region M2.3 — Read path: attachEmbedded() override STUB
    //
    // Writes go through put() above. Reads are a harder problem: JNI (native C++) constructs
    // the entity object directly and sets fields by `Property.name` — there is NO Java-side
    // hook during nativeGetEntity(). So at read time, native sets `entity.priceCurrency` and
    // `entity.priceAmount` (the transformer-injected synthetic flat fields), NOT
    // `entity.price.currency`. The container `entity.price` is null post-native.
    //
    // M0 wired a post-read hook: base `Cursor.attachEmbedded(T)` is a no-op called by
    // get()/next()/first()/getAll()/Query.find*() immediately after native returns. The
    // generated Cursor overrides it to hydrate: `entity.price = new Money();
    // entity.price.currency = entity.priceCurrency; ...`.
    //
    // BUT — those synthetic fields (`entity.priceCurrency`) don't exist at annotation-processor
    // time. They are injected by the M3 bytecode transformer AFTER APT compiles the Cursor.
    // Therefore the generated override must be a **STUB**: empty body (except a null-guard) with
    // a descriptive comment. The transformer injects the hydration body alongside injecting the
    // synthetic entity fields — keeping the field-declaration and field-use in the SAME build
    // phase (post-APT), so APT's javac pass always sees compilable code.
    //
    // This exactly mirrors the existing `attachEntity()` stub pattern in cursor.ftl: the
    // transformer injects `entity.__boxStore = boxStoreForEntities;` because `__boxStore` is
    // itself a transformer-injected field.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * RED driver: today the generated Cursor has no `attachEmbedded` override at all — the
     * base no-op runs, and after a `box.get(id)` the user sees `bill.price == null` despite
     * `priceCurrency`/`priceAmount` being set on the (post-transformer) flat fields.
     *
     * GREEN: the generated Cursor emits an `@Override public void attachEmbedded(Bill entity)`
     * stub with an EMPTY body (compiles to a single RETURN opcode — exactly like `attachEntity()`,
     * so the transformer's is-stub check is uniform). The transformer injects:
     *  - the null-guard (base hook is called with `@Nullable T` from get()/first()/next()),
     *  - the synthetic transient flat-fields onto the entity class,
     *  - the hydration body that copies those flat fields into freshly-instantiated containers.
     *
     * The stub is purely a MARKER: its PRESENCE tells the transformer "this cursor's entity has
     * @Embedded"; its body is owned by the transformer. Keeping the null-guard in transformer
     * code (not in the APT stub) means the stub contract is "1-byte RETURN" identical to
     * `attachEntity()` — no special-case empty-body detection in the transformer.
     *
     * Critically, the stub must NOT reference `entity.priceCurrency` or `new Money()` — those
     * would fail the `succeededWithoutWarnings()` gate because the synthetic flat fields don't
     * exist yet. The gate IS the real proof; the `doesNotContain()` asserts are belt-and-suspenders.
     */
    @Test
    fun embedded_attachEmbedded_generatesEmptyStubMarker() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-attach.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        val compilation = env.compile()

        // Gate: the stub must be valid Java at APT time. If the stub prematurely references
        // a transformer-injected field, THIS LINE fails first with a sharp javac error.
        compilation.assertThatIt { succeededWithoutWarnings() }

        val src = compilation.generatedSourceFileOrFail("com.example.BillCursor")
            .contentsAsString(StandardCharsets.UTF_8)

        // ─── 1. Override signature present ───
        // Must be `public` (matches base `Cursor.attachEmbedded(T)` visibility — overriding
        // with reduced visibility is a compile error). Concrete type substituted for T.
        src.contains("public void attachEmbedded(Bill entity)")

        // ─── 2. Body is EMPTY — no null-guard at APT time ───
        // The stub must compile to a single RETURN opcode so the transformer's is-stub check
        // (same check as attachEntity()) passes uniformly. The transformer injects the null-
        // guard as the first statement of the hydration body it injects. If a null-guard were
        // here, the transformer would see a multi-opcode body and warn about "unexpected code".
        src.doesNotContain("if (entity == null)")

        // ─── 3. Descriptive comment for M3 transformer ───
        // Mirrors the attachEntity() stub pattern: a human-readable hint showing WHAT the
        // transformer will inject and WHY the body is empty here.
        src.contains("Transformer will inject @Embedded hydration here")

        // ─── Negative: no premature synthetic-field references ───
        // `entity.priceCurrency` → the transformer-injected transient field. Does NOT exist
        // at APT time. If this appears in the stub, javac fails (gate above catches it, but
        // this assert pins the contract explicitly).
        // Safe negative: `__ID_priceCurrency` (static decl + put()) and `Bill_.priceCurrency`
        // (static decl) use different prefixes, so a bare `entity.priceCurrency` is unique.
        src.doesNotContain("entity.priceCurrency")
        src.doesNotContain("entity.priceAmount")

        // ─── Negative: no premature container instantiation ───
        // `new Money()` IS valid Java at APT time (Money exists as a source input), but if
        // it appears here then SOMETHING is assigning to `entity.price` prematurely, which
        // (a) wastes an allocation every read when no embedded data exists and (b) means the
        // stub isn't actually a stub — the design contract is broken.
        src.doesNotContain("new Money()")
    }

    /**
     * Negative space: an entity with NO `@Embedded` fields must NOT generate an
     * `attachEmbedded` override. The base no-op is already optimal (method-call overhead of
     * an empty final-class method is JIT-inlined to nothing). Generating an empty override
     * anyway would:
     *  - bloat every generated Cursor (most entities don't use @Embedded)
     *  - confuse users reading generated code ("why is this here?")
     *  - give the M3 transformer a body to inject into even when there's nothing to inject
     *
     * Regression guard for the `<#if entity.hasEmbedded()>` FTL gate.
     */
    @Test
    fun embedded_attachEmbedded_notGeneratedWhenEntityHasNoEmbedded() {
        @Language("Java")
        val plain =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;

            @Entity
            public class Plain {
                @Id public long id;
                public String name;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-attach-absent.json", useTemporaryModelFile = true)
            .apply { addSourceFile("com.example.Plain", plain) }

        val compilation = env.compile()
        compilation.assertThatIt { succeededWithoutWarnings() }

        val src = compilation.generatedSourceFileOrFail("com.example.PlainCursor")
            .contentsAsString(StandardCharsets.UTF_8)

        // No @Embedded fields → no override. The base no-op handles it.
        src.doesNotContain("attachEmbedded")
    }

    // endregion M2.3

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // region M5 — Hardening / test-gap closure
    //
    // These tests close known coverage gaps identified in the IMPLEMENTATION_PLAN.md §5.1 test
    // matrix that were not exercised during M1–M2. Most PIN behaviour that already works
    // (accessor-aware codegen, IdSync stability) — the point is to LOCK the contract so future
    // refactors can't silently regress it. T9 is the exception: it adds a sharper error for
    // relations inside embedded types (R5 in the plan).
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * T5 — Model JSON idempotence across re-compiles.
     *
     * The flattened synthetic properties (`priceCurrency`, `priceAmount`) MUST be stable in
     * IdSync: a re-compile against an existing `objectbox-models/default.json` must NOT
     * churn their UIDs. This is critical — if each incremental build assigns fresh UIDs to
     * embedded-derived columns, the schema drifts and users hit "schema mismatch" at runtime
     * after every edit-compile cycle.
     *
     * Mechanism: IdSync matches properties by `name`. The processor synthesises flattened names
     * via [EmbeddedField.syntheticNameFor] and passes them to `Entity.addProperty(type, name)`.
     * As long as those names are deterministic (which they are — pure function of container
     * field name + annotation prefix + inner field name), IdSync treats them like any other
     * property and re-uses their UIDs. This test proves that contract holds end-to-end.
     *
     * Test shape — two compiles against the SAME model file (no fixture commit needed):
     * 1. First compile: temp model file is created fresh (random UIDs assigned).
     * 2. Snapshot the resulting JSON.
     * 3. Second compile: same TestEnvironment instance → same temp file path → the init-block
     *    deletion already ran, so the `.tmp` from compile #1 is reused as the starting model.
     * 4. Assert JSON is byte-identical to the snapshot.
     *
     * Why no committed golden fixture? That pattern (used by e.g. `UnsignedTest`) bakes random
     * UIDs into git. Here we only care about CROSS-COMPILE stability, not absolute UID values —
     * a self-contained two-compile test proves idempotence without committing random data.
     */
    @Test
    fun embedded_modelJsonIdempotentAcrossRecompile() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        // ONE environment — useTemporaryModelFile=true deletes any stale .tmp at construction,
        // so compile #1 starts clean. We do NOT re-construct for compile #2; the .tmp from #1
        // becomes the input model for #2.
        val env = TestEnvironment("embedded-idempotence.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        // ─── Compile #1 — creates .tmp with fresh random UIDs ───
        env.compile().assertThatIt { succeededWithoutWarnings() }
        val model = env.readModel()
        val billEntity = model.findEntity("Bill", null)
            ?: error("Bill entity missing after first compile — processor didn't write model")
        // Sanity: flattened props present with real (non-zero) UIDs
        val currencyUid = billEntity.properties.single { it.name == "priceCurrency" }.uid
        val amountUid = billEntity.properties.single { it.name == "priceAmount" }.uid
        assertThat(currencyUid).isNotEqualTo(0L)
        assertThat(amountUid).isNotEqualTo(0L)

        // ─── Snapshot raw JSON ───
        // The .tmp path is internal to TestEnvironment, but the MODEL-DIR is stable.
        val modelsDir = when {
            File("src/test/resources/objectbox-models/").isDirectory -> "src/test/resources/objectbox-models/"
            else -> "objectbox-processor/src/test/resources/objectbox-models/"
        }
        val modelFile = File("${modelsDir}embedded-idempotence.json.tmp")
        val afterFirstCompile = modelFile.readText()

        // ─── Compile #2 — SAME env, SAME .tmp (still on disk from #1) ───
        // Fresh processor shim: ObjectBoxProcessor has internal state that must reset between
        // annotation-processing rounds. compile-testing wraps each compile() in a fresh javac
        // invocation, but TestEnvironment.processor is a val — so we use the overload that
        // accepts a fresh processor. Actually: looking at Compiler.javac().withProcessors(),
        // compile-testing calls init() each invocation → state IS reset. But `processor.schema`
        // would stale. We only read the model file, not env.schema, so no issue — but a
        // belt-and-suspenders second env with the same (non-deleted) .tmp is safer.
        //
        // Actually — simplest robust approach: we've snapshotted the JSON. Construct a SECOND
        // TestEnvironment with useTemporaryModelFile=true (this DELETES the .tmp), then write
        // our snapshot back as the starting model BEFORE compile. This mirrors exactly what a
        // user's incremental build does: existing model.json on disk → compile → same model.json.
        val env2 = TestEnvironment("embedded-idempotence.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
        modelFile.writeText(afterFirstCompile) // restore snapshot as input model
        env2.compile().assertThatIt { succeededWithoutWarnings() }

        val afterSecondCompile = modelFile.readText()

        // ─── Core assertion: byte-identical across compiles ───
        // A failure here means: the processor treats embedded-derived synthetic property names
        // as unstable (e.g. non-deterministic prefix resolution, or IdSync mismatch on flattened
        // names), causing UID re-generation → schema drift → production breakage.
        assertThat(afterSecondCompile).isEqualTo(afterFirstCompile)

        // ─── Belt-and-suspenders: verify UIDs match exactly via parsed model ───
        val model2 = env2.readModel()
        val billEntity2 = model2.findEntity("Bill", null)!!
        assertThat(billEntity2.properties.single { it.name == "priceCurrency" }.uid)
            .isEqualTo(currencyUid)
        assertThat(billEntity2.properties.single { it.name == "priceAmount" }.uid)
            .isEqualTo(amountUid)
    }

    /**
     * T3 — Generated `Bill_` (EntityInfo) exposes flattened properties as queryable `Property<Bill>`
     * constants.
     *
     * This is what makes embedded-field queries work: `box.query(Bill_.priceCurrency.equal("USD"))`.
     * The `entity-info.ftl` template iterates `entity.propertiesColumns` — which includes
     * embedded-derived synthetic properties exactly like regular ones. No template change was
     * needed (the plan correctly predicted this), but the template COULD regress independently
     * of the Cursor/put() path, so it needs its own pin.
     *
     * What we assert:
     * - Each flattened property appears as a `public final static Property<Bill>` constant
     *   with its SYNTHETIC name (the flattened name — this is what users reference in queries)
     * - The Java type matches the inner field's type (String.class for String currency,
     *   long.class for primitive long amount)
     * - Both appear in `__ALL_PROPERTIES` (used by the query builder to enumerate queryable columns)
     * - The Property's `name` string argument equals the synthetic name (this is the name JNI
     *   uses to find the transformer-injected flat field on the entity — if it diverges from
     *   the field name the transformer injects, reads silently drop data)
     *
     * What we DON'T assert:
     * - Ordinals/IDs (those depend on field declaration order and IdSync — T5 covers stability)
     * - Full source equivalence (ordinals make that brittle; fragment assertions are sufficient
     *   and give sharper failure messages)
     */
    @Test
    fun embedded_entityInfo_exposesFlattenedPropertiesAsQueryable() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-entityinfo.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        val compilation = env.compile()
        compilation.assertThatIt { succeededWithoutWarnings() }

        val src = compilation.generatedSourceFileOrFail("com.example.Bill_")
            .contentsAsString(StandardCharsets.UTF_8)

        // ─── Flattened property constants exist, are Property<Bill>, and have correct types ───
        // Template pattern per entity-info.ftl:
        //   public final static io.objectbox.Property<Bill> priceCurrency =
        //       new io.objectbox.Property<>(__INSTANCE, <ordinal>, <id>, String.class, "priceCurrency");
        // We fragment-assert around the ordinals (they depend on IdSync/declaration order).
        src.contains("Property<Bill> priceCurrency =")
        src.contains("String.class, \"priceCurrency\"")
        // long amount is a primitive → long.class not Long.class
        src.contains("Property<Bill> priceAmount =")
        src.contains("long.class, \"priceAmount\"")

        // ─── Both appear in __ALL_PROPERTIES ───
        // This is the list the query builder iterates. If a property is missing here, users
        // can reference Bill_.priceCurrency in code but certain bulk/reflection paths miss it.
        // Fragment-match within the braces (can't match the whole block — contains id too).
        src.contains("priceCurrency,")
        src.contains("priceAmount")  // last element, no trailing comma

        // ─── Negative: the CONTAINER field name must NOT appear as a Property ───
        // `price` itself is not a DB column — it's a Java-side convenience handle. A Property
        // named `price` would confuse users ("can I query on the whole Money?").
        src.doesNotContain("Property<Bill> price =")
        src.doesNotContain("Property<Bill> price=")
    }

    /**
     * T9 — `ToOne`/`ToMany` inside an `@Embedded` type → error (R5).
     *
     * Embedded types model **value semantics**: their fields become flat columns on the owner.
     * Relations model **reference semantics**: they point at OTHER entities via a foreign key
     * stored in a synthetic `<relationName>Id` column + runtime ToOne resolution.
     *
     * A `ToOne<Customer>` inside `Money` would need BOTH mechanisms at once:
     * - the ToOne's synthetic FK column flattened onto `Bill` (e.g. `priceOwnerId`)
     * - the transformer to inject `__boxStore` into `Money` (which isn't an @Entity)
     * - `attachEntity()` wiring for a non-entity
     *
     * That's a cross-cutting rabbit hole nobody has asked for. The fix is trivial for the user:
     * move the relation UP to the entity (`Bill.owner: ToOne<Customer>`) where it already works.
     *
     * RED state: falls through to line 274's generic "unsupported type 'ToOne<X>'" — correct
     * but doesn't explain WHY or how to fix. GREEN: explicit check with actionable guidance.
     */
    @Test
    fun embedded_toOneInsideContainer_errors() {
        @Language("Java")
        val customer =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;

            @Entity
            public class Customer {
                @Id public long id;
                public String name;
            }
            """.trimIndent()

        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.relation.ToOne;

            public class Money {
                public String currency;
                public long amount;
                public ToOne<Customer> owner;  // ← relation inside embedded type (R5 violation)
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        TestEnvironment("embedded-toone-inside.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Customer", customer)
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()
            .assertThatIt {
                failed()
                // R5-specific wording: name the offending field, name the pattern, point at the fix.
                hadErrorContaining("@Embedded 'price': relations are not supported inside an @Embedded type")
                hadErrorContaining("inner field 'owner'")
                hadErrorContaining("Move the relation to the owning entity")
            }
    }

    /**
     * T11 — Unsupported inner field type → error attributed to BOTH container AND inner field.
     *
     * `java.util.UUID` is a common user type with no built-in ObjectBox mapping (users must
     * use `@Convert` on a regular entity field; `@Convert` inside `@Embedded` is deferred).
     *
     * The generic unsupported-type path at Properties.kt:274 already handles this — the test
     * just PINS the contract that BOTH the `@Embedded` container field name AND the offending
     * inner field name appear in the error. Without that dual attribution, a user with three
     * `@Embedded` fields all pointing at the same value-object type sees only the inner field
     * name and has to hunt.
     */
    @Test
    fun embedded_unsupportedInnerType_errorsWithBothFieldNames() {
        @Language("Java")
        val money =
            """
            package com.example;

            import java.util.UUID;

            public class Money {
                public String currency;
                public long amount;
                public UUID txId;   // ← UUID has no PropertyType mapping → unsupported
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        TestEnvironment("embedded-unsupported-inner.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()
            .assertThatIt {
                failed()
                // Dual attribution — container field AND inner field.
                hadErrorContaining("@Embedded 'price'")
                hadErrorContaining("inner field 'txId'")
                hadErrorContaining("unsupported type 'java.util.UUID'")
            }
    }

    /**
     * T13 — Private `@Embedded` container field + public getter/setter → generated `put()`
     * uses `entity.getPrice()` (not `entity.price`).
     *
     * The model captures container accessibility (Properties.kt:219 →
     * [EmbeddedField.isFieldAccessible]), and `PropertyCollector` honours it via
     * [EmbeddedField.getContainerValueExpression] — which returns `getPrice()` for a private
     * field. This test proves that mechanism is wired end-to-end (not just present in the model)
     * and that the generated Cursor COMPILES against a private container (javac validates the
     * getter exists and returns the right type).
     *
     * Why does this matter? Kotlin properties, Lombok `@Getter/@Setter`, records — many modern
     * Java styles make fields private by default. Without accessor-aware codegen, every
     * `@Embedded` user would be forced into `public` container fields, a clumsy API leak.
     */
    @Test
    fun embedded_put_privateContainerField_usesGetterAccess() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;

                // Private container — codegen MUST use getPrice()/setPrice(), not field access.
                // javac will reject `entity.price` from the generated Cursor (different package).
                @Embedded private Money price;

                public Money getPrice() { return price; }
                public void setPrice(Money price) { this.price = price; }
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-private-container.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        val compilation = env.compile()

        // Gate: generated Cursor must COMPILE. If PropertyCollector emits `entity.price` for a
        // private field, javac fails here (Cursor is in com.example, same package — wait,
        // actually same package DOES allow private access... no: private is CLASS-scoped, not
        // package-scoped. javac will fail with "price has private access in Bill").
        compilation.assertThatIt { succeededWithoutWarnings() }

        val src = compilation.generatedSourceFileOrFail("com.example.BillCursor")
            .contentsAsString(StandardCharsets.UTF_8)

        // Container hoist uses the GETTER — this is the core T13 proof.
        src.contains("Money __emb_price = entity.getPrice();")

        // Negative: direct field access would fail javac, but pin the contract explicitly.
        src.doesNotContain("entity.price;")
        src.doesNotContain("entity.price ")
    }

    /**
     * T14 — Private inner field on the embedded type + public getter → generated `put()`
     * uses `__emb_price.getCurrency()` (not `__emb_price.currency`).
     *
     * Orthogonal to T13: T13 tests the CONTAINER's accessibility; T14 tests the INNER FIELD's.
     * Both are independently tracked ([EmbeddedField.isFieldAccessible] vs
     * [Property.embeddedSourceFieldAccessible]) and both must route through the accessor-aware
     * expression machinery ([Property.getEmbeddedSourceValueExpression]).
     *
     * Real-world motivation: value objects with Lombok `@Value`, records, or hand-written
     * immutable classes will have private fields with getters. Forcing `public` on inner fields
     * would be a worse API leak than T13 (value objects are meant to encapsulate).
     */
    @Test
    fun embedded_put_privateInnerField_usesGetterAccess() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                // Private inner field — codegen MUST use getCurrency(), not field access.
                private String currency;
                public long amount;

                public Money() {}
                public String getCurrency() { return currency; }
                public void setCurrency(String c) { this.currency = c; }
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-private-inner.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        val compilation = env.compile()

        // Gate: if codegen emits `__emb_price.currency` for a private field, javac rejects it
        // ("currency has private access in Money") and this line fails first.
        compilation.assertThatIt { succeededWithoutWarnings() }

        val src = compilation.generatedSourceFileOrFail("com.example.BillCursor")
            .contentsAsString(StandardCharsets.UTF_8)

        // Container hoist unchanged (public container → field access still fine).
        src.contains("Money __emb_price = entity.price;")

        // Inner read uses the GETTER — core T14 proof.
        src.contains("__emb_price.getCurrency()")

        // The public inner field (amount) still uses field access — proves per-field
        // accessibility resolution, not a global switch.
        src.contains("__emb_price.amount")

        // Negative.
        src.doesNotContain("__emb_price.currency")
    }

    /**
     * R5 (complete) — `@Id` on an inner field of an embedded type is meaningless: only
     * `@Entity` classes have an ID column, and the inner field becomes a flat data column
     * on the OWNING entity (which already has its own `@Id`).
     *
     * Today the `@Id` annotation on `Money.cents` is silently ignored — `parseEmbedded()`
     * never checks for it. The `long cents` field flattens to `priceCents` as a regular Long
     * column. User thinks they set an ID; they didn't. This test demands an explicit error.
     */
    @Test
    fun embedded_idAnnotationOnInnerField_errors() {
        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Id;

            public class Money {
                @Id public long cents;   // ← @Id is entity-only; meaningless inside a value object
                public String currency;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        TestEnvironment("embedded-id-inner.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()
            .assertThatIt {
                failed()
                hadErrorContaining("@Embedded 'price': @Id on inner field 'cents' is not allowed")
            }
    }

    /**
     * R5 (complete) — `@Index` on an inner field is silently ignored today. User expects
     * an index on the flattened column; they don't get one. Worse than a loud failure —
     * queries degrade silently.
     *
     * Phase 2 could wire this through (the flattened property IS a real column, indexable
     * like any other). For now: explicit "not yet supported" error so users aren't misled.
     */
    @Test
    fun embedded_indexAnnotationOnInnerField_errors() {
        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Index;

            public class Money {
                @Index public String currency;   // ← user expects index on priceCurrency column
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        TestEnvironment("embedded-index-inner.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()
            .assertThatIt {
                failed()
                hadErrorContaining("@Embedded 'price': @Index on inner field 'currency' is not yet supported")
                hadErrorContaining("index the flattened property directly in the entity")
            }
    }

    /**
     * R5 (complete) — `@Unique` on inner field: same failure mode as `@Index` (silent ignore →
     * user assumption violated). Unique constraints on value-object components are occasionally
     * useful (e.g. "no two Bills can have the same Money.transactionId"), so this too gets
     * a "not yet supported" message rather than a hard "never" — Phase 2 could wire it.
     */
    @Test
    fun embedded_uniqueAnnotationOnInnerField_errors() {
        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Unique;

            public class Money {
                public String currency;
                @Unique public long transactionId;   // ← user wants unique constraint on flattened column
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        TestEnvironment("embedded-unique-inner.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()
            .assertThatIt {
                failed()
                hadErrorContaining("@Embedded 'price': @Unique on inner field 'transactionId' is not yet supported")
            }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P2.2 — @Convert on inner fields of @Embedded types (replaces G2's rejection)
    //
    // The downstream machinery composes automatically:
    //   - cursor.ftl:51 iterates entity.properties; if property.customType set, emits
    //     `private final <Converter> <propName>Converter = new <Converter>();` — synthetic
    //     embedded props are in entity.properties → converter field appears for free.
    //   - PropertyCollector.java:317 calls property.getDatabaseValueExpression(innerAccess)
    //     which wraps in `<propName>Converter.convertToDatabaseValue(...)` when customType
    //     is set → write-path wrap is free.
    //   - Entity.init2ndPass() L482-499 iterates properties, auto-adds converter +
    //     customType imports if cross-package → imports are free.
    //
    // So P2.2 processor work is ONLY: (1) remove G2 @Convert rejection, (2) detect @Convert
    // on inner field via annotation mirror (Class<?> members can't use getAnnotation()),
    // (3) derive propertyType from dbType mirror (NOT the inner field's Java type),
    // (4) set customType/converter on the synthetic property builder.
    //
    // Read path (attachEmbedded → copy synthetic transient → container field) is transformer
    // territory; the transformer needs to convertToEntityProperty() when copying. Noted as
    // follow-up; processor tests cover write path + model metadata only.
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * `@Convert` on an inner field of an embedded type wires a custom converter to the
     * flattened synthetic property.
     *
     * Model assertions: the synthetic property carries `customType` (Java-side type, e.g.
     * `java.util.UUID`), `converter` (converter class FQN), and `propertyType` derived
     * from the annotation's `dbType` (NOT from the inner field's Java type).
     */
    @Test
    fun embedded_convertOnInnerField_setsCustomTypeOnSyntheticProperty() {
        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Convert;
            import io.objectbox.converter.PropertyConverter;

            public class Money {
                public String currency;
                public long amount;

                @Convert(converter = UUIDConverter.class, dbType = String.class)
                public java.util.UUID txId;   // ← UUID in Java, String in DB

                public Money() {}

                public static class UUIDConverter implements PropertyConverter<java.util.UUID, String> {
                    @Override public java.util.UUID convertToEntityProperty(String db) {
                        return db == null ? null : java.util.UUID.fromString(db);
                    }
                    @Override public String convertToDatabaseValue(java.util.UUID e) {
                        return e == null ? null : e.toString();
                    }
                }
            }
            """.trimIndent()

        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        val env = TestEnvironment("embedded-convert.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        env.compile().assertThatIt { succeededWithoutWarnings() }

        val billEntity = env.schema.entities.single { it.className == "Bill" }
        val txIdProp = billEntity.properties.single { it.propertyName == "priceTxId" }

        // PropertyType derived from @Convert.dbType (String), not from the UUID field type.
        // This is what determines the DB column type and native collect signature.
        assertType(txIdProp, PropertyType.String, hasNonPrimitiveFlag = true)

        // customType = the Java-side type (what users see in their entity object).
        // cursor.ftl checks property.customType?has_content to decide whether to emit
        // a converter instance field → this MUST be set for the whole chain to fire.
        assertThat(txIdProp.customType).isEqualTo("java.util.UUID")

        // converter = FQN of the PropertyConverter implementation. cursor.ftl uses
        // converterClassName (derived from this) for `new <Converter>()` instantiation.
        assertThat(txIdProp.converter).isEqualTo("com.example.Money.UUIDConverter")

        // Still flagged as embedded — converter wrapping composes WITH the container-path
        // read, not instead of it. PropertyCollector routes isEmbedded() → container path,
        // then getDatabaseValueExpression() applies the converter wrap to that path.
        assertThat(txIdProp.isEmbedded).isTrue()
        assertThat(txIdProp.embeddedOrigin?.name).isEqualTo("price")

        // Control: non-@Convert inner fields unaffected (no spurious customType set).
        val currencyProp = billEntity.properties.single { it.propertyName == "priceCurrency" }
        assertThat(currencyProp.customType).isNull()
    }

    /**
     * Codegen end of P2.2: the generated Cursor must (1) instantiate the converter as a
     * private final field, and (2) wrap the container-path read in a `convertToDatabaseValue()`
     * call. Both happen automatically once `customType`/`converter` are set on the synthetic
     * property — this test pins that the composition doesn't silently break.
     *
     * The golden fragment to look for:
     *   `priceTxIdConverter.convertToDatabaseValue(__emb_price.txId)`
     * Proves converter wrap (`priceTxIdConverter.convert…`) composes with container-path
     * read (`__emb_price.txId`) — neither stomps the other.
     */
    @Test
    fun embedded_convertOnInnerField_cursorWrapsContainerPathReadInConverter() {
        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Convert;
            import io.objectbox.converter.PropertyConverter;

            public class Money {
                public String currency;

                @Convert(converter = UUIDConverter.class, dbType = String.class)
                public java.util.UUID txId;

                public Money() {}

                public static class UUIDConverter implements PropertyConverter<java.util.UUID, String> {
                    @Override public java.util.UUID convertToEntityProperty(String db) { return db == null ? null : java.util.UUID.fromString(db); }
                    @Override public String convertToDatabaseValue(java.util.UUID e) { return e == null ? null : e.toString(); }
                }
            }
            """.trimIndent()

        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        val compilation = TestEnvironment("embedded-convert-codegen.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()

        compilation.assertThatIt { succeededWithoutWarnings() }

        val src = compilation
            .generatedSourceFileOrFail("com.example.BillCursor")
            .contentsAsString(StandardCharsets.UTF_8)

        // ─── Converter instance field — emitted by cursor.ftl:51 when customType set ───
        // `private final <ConverterClass> <syntheticName>Converter = new <ConverterClass>();`
        // Note the synthetic property name `priceTxId` (not `txId`) in the field name.
        // Template uses the simple class name (init2ndPass() adds the import); Entity's
        // import-auto-add loop iterates ALL properties including synthetic embedded ones.
        src.contains("import com.example.Money.UUIDConverter;")
        src.contains("private final UUIDConverter priceTxIdConverter = new UUIDConverter();")

        // ─── Container hoist (unchanged by @Convert presence) ───
        src.contains("Money __emb_price = entity.price;")

        // ─── The crux: converter wrap COMPOSES with container-path read ───
        // getDatabaseValueExpression(innerAccess) → <syntheticName>Converter.convertToDatabaseValue(<innerAccess>)
        // where innerAccess = __emb_price.txId. This single fragment proves the whole chain.
        src.contains("priceTxIdConverter.convertToDatabaseValue(__emb_price.txId)")

        // ─── Negative: must NOT read `entity.priceTxId` (the synthetic name) directly ───
        // That would be the non-embedded write path. We must route through the container.
        src.doesNotContain("entity.priceTxId")
        // And must NOT call the converter on the raw UUID without container-path routing.
        src.doesNotContain("convertToDatabaseValue(entity.")
    }

    // endregion M5

    // region P2 — Phase 2 hardening features
    // ═══════════════════════════════════════════════════════════════════════════════
    //
    // P2.3 — @Embedded on a @BaseEntity field is inherited by concrete @Entity subclasses.
    //
    // Mechanism: ObjectBoxProcessor.parseProperties() walks the inheritance chain
    // (super-most-first), creating one Properties instance per element with a SHARED
    // entityModel — so synthetic properties added while visiting the @BaseEntity land on
    // the concrete @Entity's model. The only thing stopping @Embedded from riding this
    // existing mechanism is a checkNotSuperEntity() call in the dispatch branch — a
    // copy-paste from the ToOne/ToMany branches, where the check IS needed (relations
    // need __boxStore injection on the concrete type). @Embedded has no such requirement:
    // the container field is just inherited Java data.
    //
    // What we prove below:
    //   T-P2.3a — model: flattened props appear in subclass schema, embedded metadata set
    //   T-P2.3b — codegen: subclass Cursor hoists inherited container, reads inner fields
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * An `@Embedded` field declared on a `@BaseEntity` class must flatten into the
     * concrete `@Entity` subclass's property set — exactly as if it were declared
     * directly on the subclass.
     *
     * Before this fix, `parseField()` rejected `@Embedded` when `isSuperEntity == true`
     * with the misleading message "A super class of an @Entity must not have a relation"
     * (it's not a relation). The inheritance field-walk mechanism itself needed no
     * changes — this is purely a dispatch-branch gate removal.
     */
    @Test
    fun embedded_onBaseEntity_flattensIntoSubclassModel() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val baseBill =
            """
            package com.example;

            import io.objectbox.annotation.BaseEntity;
            import io.objectbox.annotation.Embedded;
            import io.objectbox.annotation.Id;

            @BaseEntity
            public abstract class BaseBill {
                @Id public long id;
                @Embedded public Money price;   // ← declared on @BaseEntity
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;

            @Entity
            public class Bill extends BaseBill {
                public String provider;   // ← subclass-local prop to verify ordering doesn't matter
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-baseentity.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.BaseBill", baseBill)
                addSourceFile("com.example.Bill", bill)
            }

        env.compile().assertThatIt { succeededWithoutWarnings() }

        // ─── Model assertions: synthetic properties appear on the CONCRETE entity ───
        val schema = env.schema
        val billEntity = schema.entities.single { it.className == "Bill" }

        // All four expected props: id (inherited), provider (local), two synthetic (inherited embedded)
        val propNames = billEntity.properties.map { it.propertyName }.toSet()
        assertThat(propNames).containsExactly("id", "provider", "priceCurrency", "priceAmount")

        // Synthetic props carry embedded-origin metadata → M2/M3 codegen routes correctly
        val priceCurrency = billEntity.properties.single { it.propertyName == "priceCurrency" }
        assertThat(priceCurrency.isEmbedded).isTrue()
        assertThat(priceCurrency.embeddedOrigin?.name).isEqualTo("price")

        val priceAmount = billEntity.properties.single { it.propertyName == "priceAmount" }
        assertThat(priceAmount.isEmbedded).isTrue()
        assertPrimitiveType(priceAmount, PropertyType.Long)

        // Container NOT a property on the entity (it's an EmbeddedField, not a Property)
        assertThat(billEntity.properties.none { it.propertyName == "price" }).isTrue()
        assertThat(billEntity.hasEmbedded()).isTrue()
    }

    /**
     * Codegen end of P2.3: the generated `BillCursor.put()` must hoist the inherited
     * container field (`entity.price` — inherited from `BaseBill`) and read its inner
     * fields with the same null-guard pattern as a directly-declared `@Embedded`.
     *
     * Java inheritance makes `entity.price` visible on the subclass without any codegen
     * special-casing — the Cursor is generated against `Bill`, and `price` is an inherited
     * public field. This test pins that no special-casing CREEPS IN either.
     */
    @Test
    fun embedded_onBaseEntity_subclassCursorHoistsInheritedContainer() {
        @Language("Java")
        val money =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val baseBill =
            """
            package com.example;

            import io.objectbox.annotation.BaseEntity;
            import io.objectbox.annotation.Embedded;
            import io.objectbox.annotation.Id;

            @BaseEntity
            public abstract class BaseBill {
                @Id public long id;
                @Embedded public Money price;
            }
            """.trimIndent()

        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;

            @Entity
            public class Bill extends BaseBill {
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-baseentity-codegen.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.BaseBill", baseBill)
                addSourceFile("com.example.Bill", bill)
            }

        val compilation = env.compile()
        compilation.assertThatIt { succeededWithoutWarnings() }

        // contentsAsString() returns a Truth StringSubject — .contains() is the assertion
        val cursorSrc = compilation
            .generatedSourceFileOrFail("com.example.BillCursor")
            .contentsAsString(StandardCharsets.UTF_8)

        // Hoist the inherited container — no special-casing needed, `entity.price` inherits
        cursorSrc.contains("Money __emb_price = entity.price;")
        // Inner reads with null-guard (same pattern as direct @Embedded)
        cursorSrc.contains("__emb_price != null")
        cursorSrc.contains("__emb_price.currency")
        cursorSrc.contains("__emb_price.amount")
        // attachEmbedded override present — concrete entity hasEmbedded() → true via inherited field
        cursorSrc.contains("public void attachEmbedded(Bill entity)")
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P2.1 — @NameInDb / @Uid passthrough on inner fields
    //
    // Both annotations modulate the FLATTENED property, not the container:
    //   @NameInDb("cur") on Money.currency → synthetic property's DB column is "cur",
    //     NOT "priceCurrency". Property NAME (Java-side static field in Bill_) stays
    //     "priceCurrency"; only the dbName changes. This matches @NameInDb semantics
    //     elsewhere: explicit column-name override, verbatim.
    //   @Uid(N) on Money.currency → the synthetic property "priceCurrency" gets UID N.
    //     Lets users pin a flattened column's identity across schema migrations (e.g.
    //     rename Money.currency → Money.isoCode without losing the column).
    //
    // Design trade-off: same Money type embedded twice with @NameInDb/@Uid on an inner
    // field → collision (two columns want the same name/UID). We honor the user's literal
    // intent; trackUniqueName()/IdSync catch the collision with a clear error. The
    // alternative (silently prefixing @NameInDb values) would be surprising: user writes
    // "cur", gets "priceCur". What-you-write-is-what-you-get wins.
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * `@NameInDb("cur")` on an inner field overrides the DB column name for the
     * flattened synthetic property. The Java-side property name (for `Bill_.priceCurrency`)
     * stays derived — only `dbName` changes.
     *
     * Before this fix, `@NameInDb` on an inner field was silently ignored by the R5
     * rejection (G2) — this test replaces the rejection with passthrough.
     */
    @Test
    fun embedded_nameInDbOnInnerField_overridesDbColumnName() {
        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.NameInDb;

            public class Money {
                @NameInDb("cur") public String currency;  // ← column named "cur", not "priceCurrency"
                public long amount;                        // ← no override; column = "priceAmount" (default)
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        val env = TestEnvironment("embedded-nameindb.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        env.compile().assertThatIt { succeededWithoutWarnings() }

        val billEntity = env.schema.entities.single { it.className == "Bill" }

        // Property NAME (Java-side) unchanged — still the derived synthetic name.
        // This is what appears as `Bill_.priceCurrency` in generated EntityInfo_.
        val currencyProp = billEntity.properties.single { it.propertyName == "priceCurrency" }
        // DB column name overridden — this is what model.json's "name" field becomes.
        assertThat(currencyProp.dbName).isEqualTo("cur")

        // Control: the non-annotated inner field keeps the derived-from-synthetic default.
        val amountProp = billEntity.properties.single { it.propertyName == "priceAmount" }
        assertThat(amountProp.dbName).isEqualTo("priceAmount")

        // Cross-check model.json — the serialized "name" IS the dbName, so "cur" must appear
        // and "priceCurrency" must NOT (it's only a Java-side identifier).
        val model = env.readModel()
        val modelEntity = model.findEntity("Bill", null)!!
        assertThat(modelEntity.properties.map { it.name }).containsExactly("id", "cur", "priceAmount")
    }

    /**
     * `@Uid(N)` on an inner field pins the synthetic property's UID in the model.
     *
     * Use case: user renames `Money.currency` → `Money.isoCode`. Without a pinned UID,
     * IdSync sees `priceCurrency` disappear and `priceIsoCode` appear → treats as
     * drop + add → data loss. With `@Uid` pinning the original UID, the column identity
     * survives: IdSync matches by UID, sees "same column, new name", no data loss.
     *
     * ## Test structure — two-compile, self-contained
     * ObjectBox's @Uid contract: you can't write an arbitrary UID (IdSync rejects values
     * not already in the model or newUidPool — "Unexpected UID N was not in newUidPool").
     * The real workflow is: (1) build without @Uid, (2) read assigned UID from model.json,
     * (3) write @Uid(<that value>) to pin it. We mirror that here WITHOUT committing a
     * fixture with baked-in random UIDs (cf. uid.json — fragile, git-noisy).
     *
     * Compile #1: no annotation → capture the UID IdSync assigns to `priceCurrency`.
     * Compile #2: @Uid(<captured>) on Money.currency, AND rename the inner field to
     *             `isoCode` — if passthrough works, the column keeps the same UID despite
     *             the synthetic name changing from `priceCurrency` → `priceIsoCode`.
     *
     * This proves the full chain: annotation read from inner field → modelId set on synthetic
     * property → IdSync accepts existing UID → column identity survives rename.
     */
    @Test
    fun embedded_uidOnInnerField_pinsUidOnSyntheticProperty() {
        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        // ─── Compile #1 — establish a model with known UID for priceCurrency ───
        @Language("Java")
        val moneyV1 =
            """
            package com.example;

            public class Money {
                public String currency;
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        val env1 = TestEnvironment("embedded-uid.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", moneyV1)
                addSourceFile("com.example.Bill", bill)
            }
        env1.compile().assertThatIt { succeededWithoutWarnings() }

        // Capture the UID IdSync assigned + the raw model JSON (we'll feed this back in).
        val model1 = env1.readModel()
        val originalUid = model1.findEntity("Bill", null)!!
            .properties.single { it.name == "priceCurrency" }.uid
        assertThat(originalUid).isNotEqualTo(0L)

        val modelsDir = when {
            File("src/test/resources/objectbox-models/").isDirectory ->
                "src/test/resources/objectbox-models/"
            else -> "objectbox-processor/src/test/resources/objectbox-models/"
        }
        val modelFile = File("${modelsDir}embedded-uid.json.tmp")
        val model1Json = modelFile.readText()

        // ─── Compile #2 — rename inner field, pin original UID via @Uid on inner field ───
        // The inner-field rename changes the synthetic name (priceCurrency → priceIsoCode).
        // Without @Uid, IdSync would retire priceCurrency and create priceIsoCode (new UID).
        // With @Uid on the RENAMED inner field, IdSync must recognise "same column, new name".
        @Language("Java")
        val moneyV2 =
            """
            package com.example;

            import io.objectbox.annotation.Uid;

            public class Money {
                @Uid(${originalUid}L) public String isoCode;   // ← renamed from 'currency'
                public long amount;
                public Money() {}
            }
            """.trimIndent()

        val env2 = TestEnvironment("embedded-uid.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", moneyV2)
                addSourceFile("com.example.Bill", bill)
            }
        // env2's init deleted the .tmp — restore the compile #1 model so IdSync has
        // context (it needs to SEE the old priceCurrency column to recognise the UID).
        modelFile.writeText(model1Json)

        env2.compile().assertThatIt { succeededWithoutWarnings() }

        // ─── Core assertion: the renamed synthetic property kept the original UID ───
        val schemaEntity = env2.schema.entities.single { it.className == "Bill" }
        // New synthetic name (derived from renamed inner field)
        val renamedProp = schemaEntity.properties.single { it.propertyName == "priceIsoCode" }
        // Same UID as before the rename — proves @Uid on the inner field was honoured.
        assertThat(renamedProp.modelId.uid).isEqualTo(originalUid)

        // Cross-check model.json: priceCurrency gone, priceIsoCode present with original UID.
        val model2 = env2.readModel()
        val bill2 = model2.findEntity("Bill", null)!!
        assertThat(bill2.properties.map { it.name }).doesNotContain("priceCurrency")
        val isoInModel = bill2.properties.single { it.name == "priceIsoCode" }
        assertThat(isoInModel.uid).isEqualTo(originalUid)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // P2.4 — Nested @Embedded (lift R6)
    //
    // Recursive flattening: `Bill { @Embedded Money price }; Money { long amount;
    // @Embedded Currency cur }; Currency { String code; int scale }` produces four
    // leaf columns on Bill: priceAmount, priceCurCode, priceCurScale + id.
    //
    // Key invariant: each LEAF property's embeddedOrigin points to the INNERMOST
    // container (the EmbeddedField for `cur`, not `price`). That container's
    // localVarName encodes the full path (`__emb_price_cur`) so PropertyCollector's
    // existing per-property logic (`<localVar> != null ? ...` and `<localVar>.<leaf>`)
    // works UNCHANGED. Only the hoist loop needs to chain: nested containers hoist
    // from their PARENT's local var with null-propagation, not from `entity.` directly.
    //
    //   Money    __emb_price     = entity.price;                                       ← root
    //   Currency __emb_price_cur = __emb_price != null ? __emb_price.cur : null;       ← nested
    //   // then leaf reads:
    //   __emb_price_cur != null ? __ID_priceCurCode : 0
    //   __emb_price_cur.code
    //
    // Compound prefix: `outer.syntheticNameFor(inner.resolvedPrefix)` applied
    // transitively. So `price + capFirst(cur) = priceCur`, then `priceCur + Code`.
    // Empty-prefix cases compose naturally (see syntheticNameFor()'s empty-prefix
    // short-circuit).
    //
    // Cycle detection: walk tracks visited type FQNs; re-visiting the same type in
    // the chain → explicit error (not a stack overflow).
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Two-level `@Embedded`: `Bill { @Embedded Money price }; Money { long amount;
     * @Embedded Currency cur }; Currency { String code; int scale }`.
     *
     * Model assertions: four synthetic props on Bill with compound-prefixed names,
     * each leaf property pointing to its INNERMOST container as origin.
     */
    @Test
    fun embedded_nested_compoundPrefixFlattening() {
        @Language("Java")
        val currency =
            """
            package com.example;

            public class Currency {
                public String code;
                public int scale;
                public Currency() {}
            }
            """.trimIndent()

        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Embedded;

            public class Money {
                public long amount;
                @Embedded public Currency cur;   // ← nested embed; default prefix = "cur"
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        val env = TestEnvironment("embedded-nested.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Currency", currency)
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }

        env.compile().assertThatIt { succeededWithoutWarnings() }

        val billEntity = env.schema.entities.single { it.className == "Bill" }
        val propNames = billEntity.properties.map { it.propertyName }.toSet()

        // Four expected leaves: one direct from Money, two from nested Currency.
        // Compound prefix for nested: price + Cur = priceCur → priceCurCode, priceCurScale.
        assertThat(propNames).containsExactly("id", "priceAmount", "priceCurCode", "priceCurScale")

        // Leaf properties point to their INNERMOST container ("cur"), not the root ("price").
        // This is what makes PropertyCollector's existing leaf-read logic work: the leaf's
        // embeddedOrigin.localVarName is `__emb_price_cur`, so `<localVar>.code` → correct.
        val codeProp = billEntity.properties.single { it.propertyName == "priceCurCode" }
        assertThat(codeProp.isEmbedded).isTrue()
        assertThat(codeProp.embeddedOrigin?.name).isEqualTo("cur")
        assertType(codeProp, PropertyType.String)

        // Direct leaf from the OUTER container still points to "price" (unchanged).
        val amountProp = billEntity.properties.single { it.propertyName == "priceAmount" }
        assertThat(amountProp.embeddedOrigin?.name).isEqualTo("price")

        // Entity tracks TWO EmbeddedFields — root and nested — for hoisting and imports.
        // Order matters for the hoist chain (parent declared before child references it).
        assertThat(billEntity.hasEmbedded()).isTrue()
        val embFields = billEntity.embeddedFields
        assertThat(embFields.map { it.name }).containsExactly("price", "cur").inOrder()
    }

    /**
     * Codegen end of P2.4: the generated Cursor's `put()` must emit a CHAINED hoist
     * (nested container hoisted from parent's local var with null-propagation) and
     * route leaf reads through the deepest-level local.
     *
     * The hoist chain proves null-propagation: if `entity.price` is null,
     * `__emb_price_cur` ALSO becomes null (via the ternary), so the leaf null-guard
     * `__emb_price_cur != null ? ...` correctly skips ALL nested-leaf columns.
     * Without this chaining, a null outer container with a non-null default nested
     * container would write garbage.
     */
    @Test
    fun embedded_nested_cursorEmitsChainedHoistWithNullPropagation() {
        @Language("Java")
        val currency =
            """
            package com.example;

            public class Currency {
                public String code;
                public int scale;
                public Currency() {}
            }
            """.trimIndent()

        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Embedded;

            public class Money {
                public long amount;
                @Embedded public Currency cur;
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        val compilation = TestEnvironment("embedded-nested-codegen.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Currency", currency)
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()

        compilation.assertThatIt { succeededWithoutWarnings() }

        val src = compilation
            .generatedSourceFileOrFail("com.example.BillCursor")
            .contentsAsString(StandardCharsets.UTF_8)

        // ─── Root hoist — unchanged ───
        src.contains("Money __emb_price = entity.price;")

        // ─── Nested hoist — THE CRUX. Chains from parent's local with null-propagation. ───
        // NOT `entity.cur` (there's no such field on Bill).
        // NOT `__emb_price.cur` unguarded (would NPE if price is null).
        // The ternary ensures null propagates all the way down: null outer → null nested → skip all leaves.
        src.contains("Currency __emb_price_cur = __emb_price != null ? __emb_price.cur : null;")

        // ─── Leaf reads route through the DEEPEST local (NOT the root one) ───
        // PropertyCollector's existing embedded branch uses `<origin.localVarName>.<sourceValueExpr>`.
        // Since the leaf's origin is the `cur` EmbeddedField with localVarName `__emb_price_cur`,
        // this composes without PropertyCollector changes to the per-property logic.
        src.contains("__emb_price_cur != null")
        src.contains("__emb_price_cur.code")
        src.contains("__emb_price_cur.scale")

        // ─── Direct leaf from the outer container still uses the outer local ───
        src.contains("__emb_price.amount")

        // ─── Negatives: bad patterns that would indicate broken routing ───
        src.doesNotContain("entity.cur")         // ← nested container isn't on the entity
        src.doesNotContain("entity.price.cur")   // ← would bypass null-guard
        src.doesNotContain("__emb_cur ")         // ← would collide if two containers share inner name
    }

    /**
     * Cycle detection: `Money { @Embedded Money sub }` — a type embedding itself
     * (directly or transitively) must be rejected with an explicit cycle error,
     * NOT a stack overflow or an infinite-loop compiler hang.
     *
     * Cycles can arise naturally when refactoring entity hierarchies into value objects;
     * the user deserves a clear message with the type chain that forms the loop.
     */
    @Test
    fun embedded_nested_cycleDetection_errors() {
        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Embedded;

            public class Money {
                public long amount;
                @Embedded public Money sub;   // ← direct self-reference
                public Money() {}
            }
            """.trimIndent()

        @Language("Java")
        val bill = embeddedBillOwning("Money", "price")

        TestEnvironment("embedded-nested-cycle.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()
            .assertThatIt {
                failed()
                // Must name the type AND say "cycle" — "nested @Embedded not supported" would
                // mislead (nesting IS supported now; the problem is specifically the cycle).
                hadErrorContaining("cycle")
                hadErrorContaining("Money")
            }
    }

    /**
     * A NESTED container's simple name must NOT leak into the owning entity's
     * unique-name set — `Money.cur` is a field on `Money`, not on `Bill`, so a user's
     * own `Bill.cur` field (a plain String property with nothing to do with the nesting)
     * must not false-collide.
     *
     * The root container's name IS reserved (that's the point — `@Embedded Money price`
     * colliding with `String price` on the same entity SHOULD fail). But the nested
     * container lives one level down; its name only has meaning inside `Money`'s scope.
     *
     * This test also sanity-checks that the user's `cur` stays a normal non-embedded
     * property and the nested flattening still happens correctly alongside it.
     */
    @Test
    fun embedded_nested_containerNameDoesNotLeakIntoEntityNameset() {
        @Language("Java")
        val currency =
            """
            package com.example;

            public class Currency {
                public String code;
                public Currency() {}
            }
            """.trimIndent()

        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Embedded;

            public class Money {
                public long amount;
                @Embedded public Currency cur;   // ← nested container NAMED 'cur'
                public Money() {}
            }
            """.trimIndent()

        // Bill ALSO has a direct property named 'cur' — would collide with the nested
        // container's name IF addEmbedded() wrongly reserves nested names at entity scope.
        @Language("Java")
        val bill =
            """
            package com.example;

            import io.objectbox.annotation.Entity;
            import io.objectbox.annotation.Id;
            import io.objectbox.annotation.Embedded;

            @Entity
            public class Bill {
                @Id public long id;
                @Embedded public Money price;
                public String cur;   // ← plain prop; same simple name as nested container
            }
            """.trimIndent()

        val env = TestEnvironment("embedded-nested-nameleak.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Currency", currency)
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
        env.compile().assertThatIt { succeededWithoutWarnings() }

        val billEntity = env.schema.entities.single { it.className == "Bill" }
        val propNames = billEntity.properties.map { it.propertyName }.toSet()
        // Both the user's direct `cur` AND the nested flattened `priceCurCode` coexist.
        assertThat(propNames).containsExactly("id", "priceAmount", "priceCurCode", "cur")

        // User's `cur` is a plain (non-embedded) property.
        val userCur = billEntity.properties.single { it.propertyName == "cur" }
        assertThat(userCur.isEmbedded).isFalse()

        // Nested flattening unaffected — leaf still routes through innermost container.
        val nestedLeaf = billEntity.properties.single { it.propertyName == "priceCurCode" }
        assertThat(nestedLeaf.isEmbedded).isTrue()
        assertThat(nestedLeaf.embeddedOrigin?.name).isEqualTo("cur")
    }

    // endregion P2

    // ─── Helper: minimal entity that owns an @Embedded field of the given type ───
    // Cuts boilerplate for negative tests where only the embedded type varies.
    @Language("Java")
    private fun embeddedBillOwning(embeddedType: String, fieldName: String): String =
        """
        package com.example;

        import io.objectbox.annotation.Entity;
        import io.objectbox.annotation.Id;
        import io.objectbox.annotation.Embedded;

        @Entity
        public class Bill {
            @Id public long id;
            @Embedded public $embeddedType $fieldName;
        }
        """.trimIndent()
}
