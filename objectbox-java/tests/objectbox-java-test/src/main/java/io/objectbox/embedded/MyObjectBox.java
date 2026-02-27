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

import io.objectbox.BoxStoreBuilder;
import io.objectbox.ModelBuilder;
import io.objectbox.ModelBuilder.EntityBuilder;
import io.objectbox.model.EntityFlags;
import io.objectbox.model.PropertyFlags;
import io.objectbox.model.PropertyType;

/**
 * Schema builder for {@link TestBill} integration tests — hand-authored replica of what the
 * annotation processor would generate.
 * <p>
 * From the schema's perspective an {@code @Embedded Money price} field simply contributes its inner
 * fields as regular flat properties ({@code priceCurrency}, {@code priceAmount}) — there is NO
 * schema-level concept of "embedded". The schema is identical to what you'd get if a user
 * literally declared {@code String priceCurrency; double priceAmount;} on the entity. All the
 * embedded machinery (container hoisting in put(), synthetic flat fields on the entity,
 * attachEmbedded hydration) lives purely in generated/transformed Java.
 * <p>
 * This is key to the design: <b>zero native/schema changes</b>. The DB engine sees a plain table
 * with plain columns and needs no awareness of the Java-side container object.
 * <p>
 * UIDs here are arbitrary fixed large longs — stable across test runs (no IdSync churn). In a
 * real project these would be minted by {@code objectbox-models/default.json}.
 */
public class MyObjectBox {

    public static BoxStoreBuilder builder() {
        BoxStoreBuilder builder = new BoxStoreBuilder(getModel());
        builder.entity(TestBill_.__INSTANCE);
        return builder;
    }

    private static byte[] getModel() {
        ModelBuilder modelBuilder = new ModelBuilder();
        modelBuilder.lastEntityId(1, 4837291650182736495L);
        modelBuilder.lastIndexId(0, 0L);
        modelBuilder.lastRelationId(0, 0L);

        buildEntityTestBill(modelBuilder);

        return modelBuilder.build();
    }

    private static void buildEntityTestBill(ModelBuilder modelBuilder) {
        EntityBuilder entityBuilder = modelBuilder.entity("TestBill");
        entityBuilder.id(1, 4837291650182736495L).lastPropertyId(4, 8462910374651028394L);

        // USE_NO_ARG_CONSTRUCTOR is critical — it tells JNI to:
        //   1. Instantiate via `new TestBill()` (NOT an all-args ctor)
        //   2. Set each property via reflection-like `SetXxxField(entity, propertyName, value)`
        // Without this flag, JNI would try to find a ctor matching the flattened property signature
        // `TestBill(long, String, String, double)` which doesn't exist (user wrote `TestMoney price`
        // not `String priceCurrency, double priceAmount`). The real APT sets this flag automatically
        // for @Embedded entities because `hasAllPropertiesConstructor()` naturally returns false
        // when embedded properties' `parsedElement` points at inner fields — verified via M4 dump.
        entityBuilder.flags(EntityFlags.USE_NO_ARG_CONSTRUCTOR);

        entityBuilder.property("id", PropertyType.Long)
                .id(1, 2948571036492817465L)
                .flags(PropertyFlags.ID);
        entityBuilder.property("provider", PropertyType.String)
                .id(2, 7301948562093746182L);

        // ─── Flattened embedded properties — just regular columns as far as the schema cares ───
        // Property NAME must exactly match TestBill.priceCurrency / .priceAmount field names,
        // because JNI uses Property.name as the literal Java field identifier for SetXxxField.
        entityBuilder.property("priceCurrency", PropertyType.String)
                .id(3, 5619283740192836471L);
        entityBuilder.property("priceAmount", PropertyType.Double)
                .id(4, 8462910374651028394L);

        entityBuilder.entityDone();
    }
}
