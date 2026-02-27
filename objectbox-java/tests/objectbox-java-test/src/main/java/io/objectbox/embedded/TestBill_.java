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

import io.objectbox.EntityInfo;
import io.objectbox.annotation.apihint.Internal;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * Properties for entity "TestBill" — hand-authored replica of what the annotation processor would
 * generate for {@code TestBill { @Id id; provider; @Embedded Money price{currency, amount} }}.
 * <p>
 * The embedded container contributes two <b>flattened</b> properties with the default-prefix
 * naming rule ({@code fieldName + capFirst(innerField)}):
 * <ul>
 *   <li>{@link #priceCurrency} — from {@code price} + {@code Currency}</li>
 *   <li>{@link #priceAmount}   — from {@code price} + {@code Amount}</li>
 * </ul>
 * These are <b>real</b> queryable {@link io.objectbox.Property Property}s — {@link EmbeddedBoxTest}
 * uses {@code box.query(TestBill_.priceCurrency.equal("EUR"))} (test I4) to verify query-by-
 * embedded-field works with NO special-casing on the query side (embedded props are just flat
 * columns as far as the schema + query engine are concerned).
 * <p>
 * Verified against actual generator output — see the M4 dump in
 * {@code objectbox-processor/.../EmbeddedTest.kt} (temp test, since removed).
 */
public final class TestBill_ implements EntityInfo<TestBill> {

    // Leading underscores for static constants to avoid naming conflicts with property names

    public static final String __ENTITY_NAME = "TestBill";

    public static final int __ENTITY_ID = 1;

    public static final Class<TestBill> __ENTITY_CLASS = TestBill.class;

    public static final String __DB_NAME = "TestBill";

    public static final CursorFactory<TestBill> __CURSOR_FACTORY = new TestBillCursor.Factory();

    @Internal
    static final TestBillIdGetter __ID_GETTER = new TestBillIdGetter();

    public final static TestBill_ __INSTANCE = new TestBill_();

    // Property ordinals/ids MUST match MyObjectBox.buildEntityTestBill() exactly — JNI uses
    // these ids to map collect() slot values to DB columns.

    public final static io.objectbox.Property<TestBill> id =
            new io.objectbox.Property<>(__INSTANCE, 0, 1, long.class, "id", true, "id");

    public final static io.objectbox.Property<TestBill> provider =
            new io.objectbox.Property<>(__INSTANCE, 1, 2, String.class, "provider");

    /**
     * Flattened embedded property — {@code TestMoney.currency} stored as a first-class String
     * column. The Property.name {@code "priceCurrency"} is the <b>authoritative</b> field name JNI
     * uses to {@code SetObjectField} on reads, which is why {@link TestBill#priceCurrency} (the
     * transformer-injected synthetic) must have exactly this name.
     */
    public final static io.objectbox.Property<TestBill> priceCurrency =
            new io.objectbox.Property<>(__INSTANCE, 2, 3, String.class, "priceCurrency");

    /**
     * Flattened embedded property — {@code TestMoney.amount} stored as a first-class double column.
     * Same JNI name-binding contract as {@link #priceCurrency}.
     */
    public final static io.objectbox.Property<TestBill> priceAmount =
            new io.objectbox.Property<>(__INSTANCE, 3, 4, double.class, "priceAmount");

    @SuppressWarnings("unchecked")
    public final static io.objectbox.Property<TestBill>[] __ALL_PROPERTIES = new io.objectbox.Property[]{
            id,
            provider,
            priceCurrency,
            priceAmount
    };

    public final static io.objectbox.Property<TestBill> __ID_PROPERTY = id;

    @Override
    public String getEntityName() {
        return __ENTITY_NAME;
    }

    @Override
    public int getEntityId() {
        return __ENTITY_ID;
    }

    @Override
    public Class<TestBill> getEntityClass() {
        return __ENTITY_CLASS;
    }

    @Override
    public String getDbName() {
        return __DB_NAME;
    }

    @Override
    public io.objectbox.Property<TestBill>[] getAllProperties() {
        return __ALL_PROPERTIES;
    }

    @Override
    public io.objectbox.Property<TestBill> getIdProperty() {
        return __ID_PROPERTY;
    }

    @Override
    public IdGetter<TestBill> getIdGetter() {
        return __ID_GETTER;
    }

    @Override
    public CursorFactory<TestBill> getCursorFactory() {
        return __CURSOR_FACTORY;
    }

    @Internal
    static final class TestBillIdGetter implements IdGetter<TestBill> {
        @Override
        public long getId(TestBill object) {
            return object.id;
        }
    }
}
