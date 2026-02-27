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
}
