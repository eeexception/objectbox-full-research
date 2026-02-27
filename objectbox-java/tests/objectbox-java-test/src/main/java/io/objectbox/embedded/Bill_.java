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

import io.objectbox.EntityInfo;
import io.objectbox.Property;
import io.objectbox.annotation.apihint.Internal;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

// THIS CODE IS HAND-MAINTAINED to emulate what the ObjectBox Gradle plugin would generate.

/**
 * Properties for entity "Bill". Can be used for QueryBuilder and for referencing DB names.
 * <p>
 * Embedded fields from {@link Money} are flattened with prefix {@code price_}.
 */
public final class Bill_ implements EntityInfo<Bill> {

    public static final String __ENTITY_NAME = "Bill";

    public static final int __ENTITY_ID = 2;

    public static final Class<Bill> __ENTITY_CLASS = Bill.class;

    public static final String __DB_NAME = "Bill";

    public static final CursorFactory<Bill> __CURSOR_FACTORY = new BillCursor.Factory();

    @Internal
    static final BillIdGetter __ID_GETTER = new BillIdGetter();

    public static final Bill_ __INSTANCE = new Bill_();

    // --- Properties (ordinal order matches all-args constructor parameter order) ---

    /** ordinal=0, id=1: Entity ID */
    public static final Property<Bill> id =
            new Property<>(__INSTANCE, 0, 1, long.class, "id", true, "id");

    /** ordinal=1, id=2: Top-level provider field */
    public static final Property<Bill> provider =
            new Property<>(__INSTANCE, 1, 2, String.class, "provider");

    /** ordinal=2, id=3: Flattened from Money.currency */
    public static final Property<Bill> price_currency =
            new Property<>(__INSTANCE, 2, 3, String.class, "price_currency");

    /** ordinal=3, id=4: Flattened from Money.amount */
    public static final Property<Bill> price_amount =
            new Property<>(__INSTANCE, 3, 4, double.class, "price_amount");

    @SuppressWarnings("unchecked")
    public static final Property<Bill>[] __ALL_PROPERTIES = new Property[]{
            id,
            provider,
            price_currency,
            price_amount
    };

    public static final Property<Bill> __ID_PROPERTY = id;

    @Override
    public String getEntityName() {
        return __ENTITY_NAME;
    }

    @Override
    public int getEntityId() {
        return __ENTITY_ID;
    }

    @Override
    public Class<Bill> getEntityClass() {
        return __ENTITY_CLASS;
    }

    @Override
    public String getDbName() {
        return __DB_NAME;
    }

    @Override
    public Property<Bill>[] getAllProperties() {
        return __ALL_PROPERTIES;
    }

    @Override
    public Property<Bill> getIdProperty() {
        return __ID_PROPERTY;
    }

    @Override
    public IdGetter<Bill> getIdGetter() {
        return __ID_GETTER;
    }

    @Override
    public CursorFactory<Bill> getCursorFactory() {
        return __CURSOR_FACTORY;
    }

    @Internal
    static final class BillIdGetter implements IdGetter<Bill> {
        @Override
        public long getId(Bill object) {
            return object.getId();
        }
    }
}
