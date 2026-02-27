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

import io.objectbox.BoxStore;
import io.objectbox.Cursor;
import io.objectbox.Transaction;
import io.objectbox.annotation.apihint.Internal;
import io.objectbox.internal.CursorFactory;

// THIS CODE IS HAND-MAINTAINED to emulate what the ObjectBox Gradle plugin would generate.

/**
 * ObjectBox Cursor implementation for "Bill".
 * <p>
 * Handles flattening of the embedded {@link Money} into individual property values on PUT,
 * and relies on JNI calling the all-args constructor for assembly on GET.
 * <p>
 * Property type layout for collect313311:
 * <ul>
 *   <li>String slot 1: provider</li>
 *   <li>String slot 2: price_currency</li>
 *   <li>String slot 3: unused</li>
 *   <li>Double slot 1: price_amount</li>
 * </ul>
 */
public final class BillCursor extends Cursor<Bill> {

    @Internal
    static final class Factory implements CursorFactory<Bill> {
        @Override
        public Cursor<Bill> createCursor(Transaction tx, long cursorHandle, BoxStore boxStoreForEntities) {
            return new BillCursor(tx, cursorHandle, boxStoreForEntities);
        }
    }

    private static final int __ID_provider = Bill_.provider.id;
    private static final int __ID_price_currency = Bill_.price_currency.id;
    private static final int __ID_price_amount = Bill_.price_amount.id;

    public BillCursor(Transaction tx, long cursor, BoxStore boxStore) {
        super(tx, cursor, Bill_.__INSTANCE, boxStore);
    }

    @Override
    protected long getId(Bill entity) {
        return entity.getId();
    }

    @Override
    public long put(Bill entity) {
        // Extract top-level nullable String
        String provider = entity.getProvider();
        int __id_provider = provider != null ? __ID_provider : 0;

        // Extract embedded Money fields (null-guard the embedded object)
        Money price = entity.getPrice();

        String price_currency = price != null ? price.getCurrency() : null;
        int __id_price_currency = price_currency != null ? __ID_price_currency : 0;

        // For primitive double: pass real ID only when embedded object is non-null
        double price_amount = price != null ? price.getAmount() : 0.0;
        int __id_price_amount = price != null ? __ID_price_amount : 0;

        // Use collect313311: 3S/1BA/3L/3I/1F/1D
        long __assignedId = collect313311(cursor, entity.getId(), PUT_FLAG_FIRST | PUT_FLAG_COMPLETE,
                __id_provider, provider,                // String slot 1: provider
                __id_price_currency, price_currency,    // String slot 2: price_currency
                0, null,                                // String slot 3: unused
                0, null,                                // ByteArray slot 1: unused
                0, 0, 0, 0,                             // Long slots 1-2: unused
                0, 0,                                   // Long slot 3: unused
                0, 0,                                   // Int slot 1: unused
                0, 0,                                   // Int slot 2: unused
                0, 0,                                   // Int slot 3: unused
                0, 0,                                   // Float slot: unused
                __id_price_amount, price_amount          // Double slot: price_amount
        );

        entity.setId(__assignedId);
        return __assignedId;
    }
}
