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
 * ObjectBox Cursor implementation for "EmbeddedEntity".
 * <p>
 * Handles flattening of the embedded {@link Address} into individual property values on PUT,
 * and relies on JNI calling the all-args constructor for assembly on GET.
 * <p>
 * Property type layout for collect313311:
 * <ul>
 *   <li>3 Strings: name, address_street, address_city</li>
 *   <li>1 Int: address_zip</li>
 *   <li>id goes into keyIfComplete</li>
 * </ul>
 */
public final class EmbeddedEntityCursor extends Cursor<EmbeddedEntity> {

    @Internal
    static final class Factory implements CursorFactory<EmbeddedEntity> {
        @Override
        public Cursor<EmbeddedEntity> createCursor(Transaction tx, long cursorHandle, BoxStore boxStoreForEntities) {
            return new EmbeddedEntityCursor(tx, cursorHandle, boxStoreForEntities);
        }
    }

    // Static property ID cache (resolved at cursor creation via property dbName verification)
    private static final int __ID_name = EmbeddedEntity_.name.id;
    private static final int __ID_address_street = EmbeddedEntity_.address_street.id;
    private static final int __ID_address_city = EmbeddedEntity_.address_city.id;
    private static final int __ID_address_zip = EmbeddedEntity_.address_zip.id;

    public EmbeddedEntityCursor(Transaction tx, long cursor, BoxStore boxStore) {
        super(tx, cursor, EmbeddedEntity_.__INSTANCE, boxStore);
    }

    @Override
    protected long getId(EmbeddedEntity entity) {
        return entity.getId();
    }

    /**
     * Puts an object into its box.
     * <p>
     * Disassembles the embedded {@link Address} into flat property values. When the embedded
     * object is {@code null}, all its flattened property IDs are set to 0 (signaling NULL to native).
     *
     * @return The ID of the object within its box.
     */
    @Override
    public long put(EmbeddedEntity entity) {
        // Extract top-level nullable String
        String name = entity.getName();
        int __id_name = name != null ? __ID_name : 0;

        // Extract embedded Address fields (null-guard the embedded object)
        Address address = entity.getAddress();

        String address_street = address != null ? address.getStreet() : null;
        int __id_address_street = address_street != null ? __ID_address_street : 0;

        String address_city = address != null ? address.getCity() : null;
        int __id_address_city = address_city != null ? __ID_address_city : 0;

        // For primitive zip: pass real ID only when embedded object is non-null
        int address_zip = address != null ? address.getZip() : 0;
        int __id_address_zip = address != null ? __ID_address_zip : 0;

        // Use collect313311: 3 Strings, 1 ByteArray(unused), 3 Longs(unused), 3 Ints, 1 Float(unused), 1 Double(unused)
        // Single collect call with PUT_FLAG_FIRST | PUT_FLAG_COMPLETE
        long __assignedId = collect313311(cursor, entity.getId(), PUT_FLAG_FIRST | PUT_FLAG_COMPLETE,
                __id_name, name,                       // String slot 1: name
                __id_address_street, address_street,    // String slot 2: address_street
                __id_address_city, address_city,        // String slot 3: address_city
                0, null,                                // ByteArray slot 1: unused
                0, 0, 0, 0,                             // Long slots 1-2: unused
                0, 0,                                   // Long slot 3: unused
                __id_address_zip, address_zip,          // Int slot 1: address_zip
                0, 0,                                   // Int slot 2: unused
                0, 0,                                   // Int slot 3: unused
                0, 0,                                   // Float slot: unused
                0, 0                                    // Double slot: unused
        );

        entity.setId(__assignedId);
        return __assignedId;
    }
}
