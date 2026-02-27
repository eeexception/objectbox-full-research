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

package io.objectbox.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field of an {@link Entity} as an embedded value object (component mapping).
 * <p>
 * The fields of the embedded type are flattened into the owning entity's table as if they were
 * declared directly on the entity. The embedded type must not be an {@code @Entity} itself and
 * must have a no-argument constructor.
 * <p>
 * Example:
 * <pre>
 * public class Money {
 *     public String currency;
 *     public double amount;
 * }
 *
 * &#64;Entity
 * public class Bill {
 *     &#64;Id public long id;
 *     &#64;Embedded public Money price; // stored as flat columns priceCurrency, priceAmount
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface Embedded {

    /**
     * Optional prefix for the flattened column names.
     * By default the field name (with its first letter capitalized in the suffix) is used as a prefix,
     * e.g. {@code @Embedded Money price} produces {@code priceCurrency}, {@code priceAmount}.
     * Set this to an empty string to flatten without any prefix.
     */
    String prefix() default Embedded.USE_FIELD_NAME;

    /**
     * Sentinel default for {@link #prefix()} meaning "derive prefix from the annotated field's name".
     * Distinct from the empty string so that an explicit {@code prefix = ""} can mean "no prefix at all".
     */
    String USE_FIELD_NAME = "\0";
}
