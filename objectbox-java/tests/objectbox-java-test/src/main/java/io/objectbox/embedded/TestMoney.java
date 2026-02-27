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

/**
 * Value object for {@code @Embedded} integration testing.
 * <p>
 * This is a plain POJO — NOT an {@code @Entity}. Its fields are flattened into the containing
 * {@link TestBill} entity's table as columns {@code priceCurrency} (String) and {@code priceAmount}
 * (double). A public no-arg constructor is REQUIRED (the read-path hydration instantiates this
 * class via {@code new TestMoney()} then populates fields directly).
 * <p>
 * Intentionally covers the two most asymmetric primitive behaviours:
 * <ul>
 *   <li>{@link #currency} — reference type → DB null maps back to Java {@code null}</li>
 *   <li>{@link #amount} — primitive {@code double} → DB absent maps back to {@code 0.0}</li>
 * </ul>
 * so I2 (null-container write) can assert both sentinels in one round-trip.
 */
public class TestMoney {

    public String currency;
    public double amount;

    /** Required for read-path hydration (attachEmbedded's {@code new TestMoney()}). */
    public TestMoney() {
    }

    /** Convenience for test readability — NOT required by the framework. */
    public TestMoney(String currency, double amount) {
        this.currency = currency;
        this.amount = amount;
    }
}
