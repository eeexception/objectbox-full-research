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
 * Test entity with an {@code @Embedded} {@link Money} field.
 * <p>
 * DB property layout (flat):
 * <pre>
 *   ordinal 0, id 1: id              (Long)   — ID
 *   ordinal 1, id 2: provider        (String)
 *   ordinal 2, id 3: price_currency  (String)  — flattened from Money.currency
 *   ordinal 3, id 4: price_amount    (Double)  — flattened from Money.amount
 * </pre>
 */
@Entity
public class Bill {

    @Id
    private long id;

    private String provider;

    @Embedded
    private Money price;

    /** No-arg constructor. */
    public Bill() {
    }

    /**
     * All-args constructor called by JNI with flat DB property values in ordinal order.
     *
     * @param id              entity ID
     * @param provider        top-level provider field
     * @param price_currency  flattened Money.currency (may be null)
     * @param price_amount    flattened Money.amount (0.0 when NULL in DB)
     */
    public Bill(long id, String provider, String price_currency, double price_amount) {
        this.id = id;
        this.provider = provider;
        if (price_currency != null || price_amount != 0.0) {
            this.price = new Money(price_currency, price_amount);
        } else {
            this.price = null;
        }
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Money getPrice() {
        return price;
    }

    public void setPrice(Money price) {
        this.price = price;
    }
}
