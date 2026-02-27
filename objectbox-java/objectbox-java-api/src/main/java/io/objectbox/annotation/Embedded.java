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

package io.objectbox.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as an embedded object (value object / component).
 * <p>
 * The fields of the embedded class are flattened into the parent entity's database table. The embedded class itself
 * is <b>not</b> a separate entity — it has no {@link Entity @Entity} annotation and no {@link Id @Id} property.
 * <p>
 * <b>Example — Value Object:</b>
 * <pre>
 * public class Money {
 *     public String currency;
 *     public double amount;
 *
 *     public Money() {}
 *     public Money(String currency, double amount) {
 *         this.currency = currency;
 *         this.amount = amount;
 *     }
 * }
 * </pre>
 * <b>Example — Parent Entity:</b>
 * <pre>
 * &#64;Entity
 * public class Bill {
 *     &#64;Id public long id;
 *     public String provider;
 *     &#64;Embedded public Money price;
 * }
 * </pre>
 * This produces database columns: {@code id}, {@code provider}, {@code price_currency}, {@code price_amount}.
 * <p>
 * <b>Prefix:</b> By default, flattened column names are prefixed with the field name followed by an underscore
 * (e.g. {@code price_currency}). Use {@link #prefix()} to override this.
 * <p>
 * <b>Null handling:</b> When the embedded object is {@code null}, all its flattened columns are stored as NULL.
 * On read, if all flattened columns are NULL (or at their primitive default), the embedded field is set to
 * {@code null}.
 * <p>
 * <b>Naming collisions:</b> The default prefix strategy ({@code fieldName_}) prevents collisions between parent
 * properties and embedded properties. If a custom empty prefix is used, it is the developer's responsibility to
 * ensure no naming collisions occur.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface Embedded {

    /**
     * Custom prefix for the flattened column names in the database.
     * <p>
     * If empty (the default), the prefix is automatically derived as {@code fieldName_} where {@code fieldName} is
     * the name of the annotated field. For example, a field named {@code price} of type {@code Money} with a
     * property {@code currency} produces the column name {@code price_currency}.
     * <p>
     * Set to a custom value (e.g. {@code "billing_"}) to override. Include a trailing separator if desired.
     */
    String prefix() default "";
}
