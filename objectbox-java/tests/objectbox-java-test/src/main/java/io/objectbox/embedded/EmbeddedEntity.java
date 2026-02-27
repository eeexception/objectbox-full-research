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

import io.objectbox.annotation.Embedded;
import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;

/**
 * Test entity with a single {@code @Embedded} field.
 * <p>
 * The annotations are informational only — see note in TestEntity. The "generated" code
 * ({@link EmbeddedEntity_}, {@link EmbeddedEntityCursor}) is hand-maintained for testing.
 * <p>
 * DB property layout (flat):
 * <pre>
 *   ordinal 0, id 1: id             (Long)   — ID
 *   ordinal 1, id 2: name           (String)
 *   ordinal 2, id 3: address_street (String)  — flattened from Address.street
 *   ordinal 3, id 4: address_city   (String)  — flattened from Address.city
 *   ordinal 4, id 5: address_zip    (Int)     — flattened from Address.zip
 * </pre>
 */
@Entity
public class EmbeddedEntity {

    @Id
    private long id;

    private String name;

    @Embedded
    private Address address;

    /** No-arg constructor. */
    public EmbeddedEntity() {
    }

    /**
     * All-args constructor called by JNI with flat DB property values in ordinal order.
     * <p>
     * Assembles the embedded {@link Address} from the flattened fields. If all embedded fields
     * are at their null/default values, the address is set to {@code null}.
     *
     * @param id              entity ID
     * @param name            top-level name field
     * @param address_street  flattened Address.street (may be null)
     * @param address_city    flattened Address.city (may be null)
     * @param address_zip     flattened Address.zip (0 when NULL in DB)
     */
    public EmbeddedEntity(long id, String name, String address_street, String address_city, int address_zip) {
        this.id = id;
        this.name = name;
        // Embedded null detection: if all object-typed fields are null AND primitive at default → null
        if (address_street != null || address_city != null || address_zip != 0) {
            this.address = new Address(address_street, address_city, address_zip);
        } else {
            this.address = null;
        }
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }
}
