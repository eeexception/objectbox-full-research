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
 * Properties for entity "EmbeddedEntity". Can be used for QueryBuilder and for referencing DB names.
 * <p>
 * Embedded fields from {@link Address} are flattened with prefix {@code address_}.
 */
public final class EmbeddedEntity_ implements EntityInfo<EmbeddedEntity> {

    public static final String __ENTITY_NAME = "EmbeddedEntity";

    public static final int __ENTITY_ID = 1;

    public static final Class<EmbeddedEntity> __ENTITY_CLASS = EmbeddedEntity.class;

    public static final String __DB_NAME = "EmbeddedEntity";

    public static final CursorFactory<EmbeddedEntity> __CURSOR_FACTORY = new EmbeddedEntityCursor.Factory();

    @Internal
    static final EmbeddedEntityIdGetter __ID_GETTER = new EmbeddedEntityIdGetter();

    public static final EmbeddedEntity_ __INSTANCE = new EmbeddedEntity_();

    // --- Properties (ordinal order matches all-args constructor parameter order) ---

    /** ordinal=0, id=1: Entity ID */
    public static final Property<EmbeddedEntity> id =
            new Property<>(__INSTANCE, 0, 1, long.class, "id", true, "id");

    /** ordinal=1, id=2: Top-level name field */
    public static final Property<EmbeddedEntity> name =
            new Property<>(__INSTANCE, 1, 2, String.class, "name");

    /** ordinal=2, id=3: Flattened from Address.street */
    public static final Property<EmbeddedEntity> address_street =
            new Property<>(__INSTANCE, 2, 3, String.class, "address_street");

    /** ordinal=3, id=4: Flattened from Address.city */
    public static final Property<EmbeddedEntity> address_city =
            new Property<>(__INSTANCE, 3, 4, String.class, "address_city");

    /** ordinal=4, id=5: Flattened from Address.zip */
    public static final Property<EmbeddedEntity> address_zip =
            new Property<>(__INSTANCE, 4, 5, int.class, "address_zip");

    @SuppressWarnings("unchecked")
    public static final Property<EmbeddedEntity>[] __ALL_PROPERTIES = new Property[]{
            id,
            name,
            address_street,
            address_city,
            address_zip
    };

    public static final Property<EmbeddedEntity> __ID_PROPERTY = id;

    @Override
    public String getEntityName() {
        return __ENTITY_NAME;
    }

    @Override
    public int getEntityId() {
        return __ENTITY_ID;
    }

    @Override
    public Class<EmbeddedEntity> getEntityClass() {
        return __ENTITY_CLASS;
    }

    @Override
    public String getDbName() {
        return __DB_NAME;
    }

    @Override
    public Property<EmbeddedEntity>[] getAllProperties() {
        return __ALL_PROPERTIES;
    }

    @Override
    public Property<EmbeddedEntity> getIdProperty() {
        return __ID_PROPERTY;
    }

    @Override
    public IdGetter<EmbeddedEntity> getIdGetter() {
        return __ID_GETTER;
    }

    @Override
    public CursorFactory<EmbeddedEntity> getCursorFactory() {
        return __CURSOR_FACTORY;
    }

    @Internal
    static final class EmbeddedEntityIdGetter implements IdGetter<EmbeddedEntity> {
        @Override
        public long getId(EmbeddedEntity object) {
            return object.getId();
        }
    }
}
