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

import io.objectbox.annotation.Embedded;
import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;

/**
 * {@code @Embedded} integration test entity — <b>post-transform</b> shape.
 * <p>
 * The annotations in this class have no effect as the Gradle plugin is not configured in this
 * project (see {@link io.objectbox.TestEntity} for the canonical explanation). However, this class
 * is hand-written to look EXACTLY as it would after the bytecode transformer has processed a real
 * {@code @Embedded}-annotated entity — specifically, it includes the synthetic flat fields
 * ({@link #priceCurrency}, {@link #priceAmount}) that the transformer would inject for JNI reads.
 * <p>
 * This is the same pattern {@link io.objectbox.relation.Order} uses to test ToOne relations without
 * the build pipeline — declare by hand what the transformer would declare.
 *
 * <h3>What a user would actually write</h3>
 * <pre>{@code
 * @Entity
 * public class TestBill {
 *     @Id public long id;
 *     public String provider;
 *     @Embedded public TestMoney price;   // ← this is all the user writes
 * }
 * }</pre>
 *
 * <h3>What the bytecode transformer adds</h3>
 * <pre>{@code
 * transient String priceCurrency;   // ← injected: JNI sets this on reads
 * transient double priceAmount;     // ← injected: JNI sets this on reads
 * }</pre>
 * The transformer also fills {@link TestBillCursor#attachEmbedded} with code that copies these
 * synthetic flats into a fresh {@link TestMoney} and assigns it to {@link #price}.
 */
@Entity
public class TestBill {

    // ─── User-declared fields ─────────────────────────────────────────────────────────────────────

    @Id
    public long id;

    public String provider;

    /**
     * The embedded value object. From the user's perspective this is the ONLY interesting field —
     * they read/write {@code bill.price.currency} and never see flat columns.
     * <p>
     * In a real project the {@code @Embedded} annotation on this field is what triggers the APT +
     * transformer pipeline. Here the annotation is present for documentation parity but is inert.
     * <p>
     * <b>Lifecycle:</b> may be {@code null} at user-write time (I2 tests this — all flat columns
     * are then stored absent/default). Is <b>never</b> {@code null} after a read, because
     * {@code attachEmbedded} always-hydrates (fresh instance, then copy).
     */
    @Embedded
    public TestMoney price;

    // ─── Transformer-injected synthetic flat fields (hand-declared for this test module) ──────────
    //
    // JNI's nativeGetEntity() looks up fields BY NAME on the entity class using the property names
    // from the schema. For embedded properties those names are the FLATTENED names
    // (`priceCurrency`, `priceAmount`), so matching fields must exist on the entity for JNI to set.
    //
    // Must be `transient` — keeps them out of Java serialization; the user should never see these.
    // Package-private (not public) because only TestBillCursor.attachEmbedded() reads them.
    //
    // The transformer injects these as `transient <T> <syntheticName>;` — same modifiers here.

    transient String priceCurrency;
    transient double priceAmount;

    // ─── Constructors ─────────────────────────────────────────────────────────────────────────────
    //
    // No-arg is REQUIRED — the schema declares EntityFlags.USE_NO_ARG_CONSTRUCTOR (the APT detects
    // that embedded properties' flattened signature won't match any user ctor, so it never sets
    // the all-args flag). JNI will call this, then SetXxxField per property by name.

    public TestBill() {
    }

    /** Test-convenience constructor — JNI never calls this. */
    public TestBill(String provider, TestMoney price) {
        this.provider = provider;
        this.price = price;
    }
}
