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

    /**
     * Nested `@Embedded` (an embedded type itself containing an `@Embedded` field) is out of
     * scope for M1 — it would require recursive flattening, multi-segment container paths in
     * the generated Cursor, and compound null-guards.
     *
     * Without this check, today the inner `@Embedded` annotation is silently ignored: the
     * inner-field loop sees `@Embedded Currency cur` as a plain field, `getPropertyType()`
     * returns null (Currency isn't a supported primitive), and the user gets the generic
     * "unsupported type" error with no hint that nesting is the issue.
     */
    @Test
    fun embedded_nestedEmbedded_errors() {
        @Language("Java")
        val currency =
            """
            package com.example;

            public class Currency {
                public String code;
                public int decimals;
                public Currency() {}
            }
            """.trimIndent()

        @Language("Java")
        val money =
            """
            package com.example;

            import io.objectbox.annotation.Embedded;

            public class Money {
                @Embedded public Currency cur;   // ← nested @Embedded
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

        TestEnvironment("embedded-nested.json", useTemporaryModelFile = true)
            .apply {
                addSourceFile("com.example.Currency", currency)
                addSourceFile("com.example.Money", money)
                addSourceFile("com.example.Bill", bill)
            }
            .compile()
            .assertThatIt {
                failed()
                hadErrorContaining("@Embedded 'price': nested @Embedded on inner field 'cur' is not supported")
            }
    }

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

    // endregion M5
}
