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
 *
 * <b>Null handling.</b> Saving an entity with a {@code null} embedded container stores null for every flattened column.
 * On read, the container reference is restored to {@code null} — <b>provided the embedded type has
 * at least one reference-typed (non-primitive) field</b>. Null-detection works by checking whether
 * any object-typed flat column read back as non-null; if all are null the container is presumed to
 * have been null at save time.
 * <p>
 * <b>Limitation:</b> an embedded type whose fields are <i>all</i> primitives ({@code int},
 * {@code long}, {@code double}, …) cannot distinguish a null container from one whose fields all
 * happen to hold the zero value — reading such an entity always returns a populated container.
 * If null round-trip matters for an all-primitive embeddable, use a wrapper type ({@code Long}
 * instead of {@code long}) on at least one field to provide a null-detection signal.
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
