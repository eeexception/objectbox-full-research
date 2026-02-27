/*
 * ObjectBox Build Tools
 * Copyright (C) 2017-2025 ObjectBox Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.objectbox.generator.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.objectbox.generator.IdUid;
import io.objectbox.generator.TextUtil;
import io.objectbox.model.EntityFlags;

/**
 * Model class for an entity: a Java data object mapped to a data base representation.
 * A new entity is added to a {@link Schema} by {@link Schema#addEntity(String)}.
 * <p> Use the various add methods to add entity properties, indexes, and relations
 * to other entities (addToOne, addToMany).
 */
public class Entity implements HasParsedElement {
    private final Schema schema;
    private final String className;
    private Integer modelId;
    private Long modelUid;
    private IdUid lastPropertyId;
    private final List<Property> properties;
    private List<Property> propertiesColumns;
    private final List<Property> propertiesPk;

    /**
     * For fail fast checks which can show the stacktrace of the add operation
     * (there's another check after the 3rd init phase).
     */
    private final Map<String, Object> names;
    private final List<Index> indexes;
    private final List<Index> multiIndexes;
    private final List<ToOne> toOneRelations;
    private final List<ToManyBase> toManyRelations;
    private final List<ToManyBase> incomingToManyRelations;
    /** {@code @Embedded} container fields — see {@link EmbeddedField}. Synthetic properties they produce live in {@link #properties}. */
    private final List<EmbeddedField> embeddedFields;
    private final Collection<String> additionalImportsDao;

    private String dbName;
    private String classNameDao;
    private String javaPackage;
    private String javaPackageDao;
    private Property pkProperty;
    private String pkType;

    private boolean hasAllArgsConstructor;
    private boolean hasBoxStoreField;
    private Object parsedElement;
    private boolean syncEnabled;
    private boolean syncSharedGlobalIds;
    @Nullable private String externalName;

    private Integer entityFlags;
    private Integer entityFlagsModelFile;
    private Set<String> entityFlagsNames;

    Entity(Schema schema, String className) {
        this.schema = schema;
        this.className = className;
        properties = new ArrayList<>();
        propertiesPk = new ArrayList<>();
        names = new HashMap<>();
        indexes = new ArrayList<>();
        multiIndexes = new ArrayList<>();
        toOneRelations = new ArrayList<>();
        toManyRelations = new ArrayList<>();
        incomingToManyRelations = new ArrayList<>();
        embeddedFields = new ArrayList<>();
        additionalImportsDao = new TreeSet<>();
        hasAllArgsConstructor = false;
    }

    public Entity setModelId(Integer modelId) {
        this.modelId = modelId;
        return this;
    }

    public Integer getModelId() {
        return modelId;
    }

    public Entity setModelUid(Long modelUid) {
        this.modelUid = modelUid;
        return this;
    }

    public Long getModelUid() {
        return modelUid;
    }

    public IdUid getLastPropertyId() {
        return lastPropertyId;
    }

    public Entity setLastPropertyId(IdUid lastPropertyId) {
        this.lastPropertyId = lastPropertyId;
        return this;
    }

    public Property.PropertyBuilder addProperty(PropertyType propertyType, String propertyName) throws ModelException {
        Property.PropertyBuilder builder = new Property.PropertyBuilder(schema, this, propertyType, propertyName);
        Property property = builder.getProperty();
        trackUniqueName(names, propertyName, property);
        properties.add(property);
        return builder;
    }

    /** Adds a standard id property. */
    public Property.PropertyBuilder addIdProperty() throws ModelException {
        return addProperty(PropertyType.Long, "id").primaryKey();
    }

    /**
     * Adds a to-many relation based on a to-one relation ({@link #addToOne}) from another entity to this entity.
     *
     * @throws ModelException if this entity already has a property or relation with {@code name}.
     */
    public ToManyByBacklink addToManyByToOneBacklink(ToManyByBacklink toMany, Entity targetEntity, ToOne targetToOne)
            throws ModelException {
        toMany.setSourceAndTargetEntity(this, targetEntity);
        toMany.setTargetToOne(targetToOne);
        trackNameAndaddToMany(toMany);
        return toMany;
    }

    /**
     * Adds a to-many relation based on a (stand-alone) to-many relation ({@link #addToMany}) from another entity
     * to this entity.
     *
     * @throws ModelException if this entity already has a property or relation with {@code name}.
     */
    public ToManyByBacklink addToManyByToManyBacklink(ToManyByBacklink toMany, Entity targetEntity,
            ToManyStandalone targetToMany) throws ModelException {
        toMany.setSourceAndTargetEntity(this, targetEntity);
        toMany.setTargetToMany(targetToMany);
        trackNameAndaddToMany(toMany);
        return toMany;
    }

    /**
     * Adds a (stand-alone) to-many relation ({@link ToManyStandalone}) to another entity.
     *
     * @throws ModelException if this entity already has a property or relation with {@code name}.
     */
    public ToManyStandalone addToMany(ToManyStandalone toMany, Entity target) throws ModelException {
        toMany.setSourceAndTargetEntity(this, target);
        trackNameAndaddToMany(toMany);
        return toMany;
    }

    private void trackNameAndaddToMany(ToManyBase toMany) throws ModelException {
        trackUniqueName(names, toMany.getName(), toMany);
        toManyRelations.add(toMany);
        toMany.getTargetEntity().getIncomingToManyRelations().add(toMany);
    }

    /**
     * Adds a to-one relationship to the given target entity.
     *
     * @throws ModelException if this entity already has a property or relation with {@code name}.
     */
    public ToOne addToOne(ToOne toOne, Entity target) throws ModelException {
        toOne.setSourceAndTargetEntity(this, target);
        toOneRelations.add(toOne);
        trackUniqueName(names, toOne.getName(), toOne);
        return toOne;
    }

    /** Adds a new index to the entity. */
    public Entity addIndex(Index index) {
        indexes.add(index);
        return this;
    }

    /**
     * Registers an {@code @Embedded} container field.
     * <p>
     * For a <b>root</b> container (one directly on this entity — no {@link EmbeddedField#getParent()
     * parent}), the container's {@link EmbeddedField#getName() name} is tracked in the entity's
     * unique-name set — a subsequent {@link #addProperty} or {@link #addToOne}/{@link #addToMany}
     * with the same name will throw. This prevents e.g. {@code @Embedded Money price} colliding
     * with a regular {@code String price} field on the same entity.
     * <p>
     * For a <b>nested</b> container (one living on an intermediate embedded type, not on this
     * entity), name tracking is <b>skipped</b> — the nested container's simple name (e.g.
     * {@code cur} for {@code Money.cur}) has no meaning in this entity's namespace. A user's
     * own {@code Bill.cur} field is entirely unrelated and must not false-collide. Nested
     * containers still land in {@link #getEmbeddedFields()} (the codegen hoist loop needs them
     * all, flat), but their names belong to their parent type's scope, not this entity's.
     * <p>
     * The synthetic flattened properties produced by this container should be added separately
     * via {@link #addProperty} (each with its own prefixed name), then linked back via
     * {@link EmbeddedField#addProperty}. Those synthetic names ARE entity-scoped (they become
     * transformer-injected fields) and ARE tracked by {@link #addProperty}'s own call to
     * {@link #trackUniqueName}.
     *
     * @throws ModelException if (root container only) another property, relation, or embedded
     *   field already uses this name.
     */
    public Entity addEmbedded(EmbeddedField embedded) throws ModelException {
        if (embedded.getParent() == null) {
            // Root container: its name IS a Java field on this entity — reserve it.
            trackUniqueName(names, embedded.getName(), embedded);
        }
        // else nested: name lives in the parent type's scope, not this entity's. Don't reserve.
        embeddedFields.add(embedded);
        return this;
    }

    public Schema getSchema() {
        return schema;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getClassName() {
        return className;
    }

    public List<Property> getProperties() {
        return properties;
    }

    public Property findPropertyByName(String name) {
        for (Property property : properties) {
            if (name.equals(property.getPropertyName())) {
                return property;
            }
        }
        return null;
    }

    public List<Property> getPropertiesColumns() {
        return propertiesColumns;
    }

    public String getJavaPackage() {
        return javaPackage;
    }

    public void setJavaPackage(String javaPackage) {
        this.javaPackage = javaPackage;
    }

    public String getJavaPackageDao() {
        return javaPackageDao;
    }

    public void setJavaPackageDao(String javaPackageDao) {
        this.javaPackageDao = javaPackageDao;
    }

    public String getClassNameDao() {
        return classNameDao;
    }

    public void setClassNameDao(String classNameDao) {
        this.classNameDao = classNameDao;
    }

    /** Internal property used by templates, don't use during entity definition. */
    public List<Property> getPropertiesPk() {
        return propertiesPk;
    }

    /** Internal property used by templates, don't use during entity definition. */
    public Property getPkProperty() {
        return pkProperty;
    }

    public List<Index> getIndexes() {
        return indexes;
    }

    /** Internal property used by templates, don't use during entity definition. */
    public String getPkType() {
        return pkType;
    }

    public boolean hasAllArgsConstructor() {
        return hasAllArgsConstructor;
    }

    /** Set to indicate the associated class has a constructor with an argument for every property available. */
    public void setHasAllArgsConstructor(boolean hasAllArgsConstructor) {
        this.hasAllArgsConstructor = hasAllArgsConstructor;
    }

    public boolean hasRelations() {
        return !toOneRelations.isEmpty() || !toManyRelations.isEmpty();
    }

    /**
     * {@code true} if this entity has one or more {@code @Embedded} container fields.
     * <p>
     * Used by codegen templates to decide whether to emit the {@code attachEmbedded()} override
     * in the generated Cursor, and to gate null-guard blocks in {@code put()}.
     */
    public boolean hasEmbedded() {
        return !embeddedFields.isEmpty();
    }

    /**
     * The list of {@code @Embedded} container fields on this entity.
     * <p>
     * Each entry's {@link EmbeddedField#getProperties() properties} list holds the synthetic
     * flattened properties it produced; those same properties also appear in {@link #getProperties()}.
     */
    public List<EmbeddedField> getEmbeddedFields() {
        return embeddedFields;
    }

    public List<ToOne> getToOneRelations() {
        return toOneRelations;
    }

    public List<ToManyBase> getToManyRelations() {
        return toManyRelations;
    }

    public List<ToManyBase> getIncomingToManyRelations() {
        return incomingToManyRelations;
    }

    public Collection<String> getAdditionalImportsDao() {
        return additionalImportsDao;
    }

    public boolean getHasBoxStoreField() {
        return hasBoxStoreField;
    }

    public void setHasBoxStoreField(boolean hasBoxStoreField) {
        this.hasBoxStoreField = hasBoxStoreField;
    }

    void init2ndPass() {
        init2ndPassNamesWithDefaults();

        for (int i = 0; i < properties.size(); i++) {
            Property property = properties.get(i);
            property.setOrdinal(i);
            property.init2ndPass();
            if (property.isPrimaryKey()) {
                propertiesPk.add(property);
            }
        }

        for (final Index index : indexes) {
            final int propertiesSize = index.getProperties().size();
            if (propertiesSize == 1) {
                final Property property = index.getProperties().get(0);
                property.setIndex(index);
            } else if (propertiesSize > 1) {
                multiIndexes.add(index);
            }
        }

        if (propertiesPk.size() == 1) {
            pkProperty = propertiesPk.get(0);
            pkType = schema.mapToJavaTypeNullable(pkProperty.getPropertyType());
        } else {
            pkType = "Void";
        }

        propertiesColumns = new ArrayList<>(properties);
        for (ToOne toOne : toOneRelations) {
            Property targetIdProperty = toOne.getIdRefProperty();
            if (!propertiesColumns.contains(targetIdProperty)) {
                propertiesColumns.add(targetIdProperty);
            }
        }
    }

    protected void init2ndPassNamesWithDefaults() {
        if (dbName == null) {
            dbName = TextUtil.dbName(className);
        }

        if (classNameDao == null) {
            classNameDao = className + "Dao";
        }

        if (javaPackage == null) {
            javaPackage = schema.getDefaultJavaPackage();
        }

        if (javaPackageDao == null) {
            javaPackageDao = schema.getDefaultJavaPackageDao();
            if (javaPackageDao == null) {
                javaPackageDao = javaPackage;
            }
        }
    }

    void init3rdPass() throws ModelException {
        for (Property property : properties) {
            property.init3ndPass();
        }

        init3rdPassRelations();
        init3rdPassAdditionalImports();

        // Check again, because names may have changed during 2nd or 3rd pass
        verifyAllNamesUnique();
    }

    private void verifyAllNamesUnique() throws ModelException {
        Map<String, Object> names = new HashMap<>();
        for (Property property : properties) {
            trackUniqueName(names, property.getPropertyName(), property);
        }
        for (ToOne toOne : toOneRelations) {
            trackUniqueName(names, toOne.getName(), toOne);
        }
        for (ToManyBase toMany : toManyRelations) {
            trackUniqueName(names, toMany.getName(), toMany);
        }
        for (EmbeddedField embedded : embeddedFields) {
            // A ROOT container's name occupies a slot in the entity's Java namespace (it IS a
            // field on this class), even though it has no DB column. Prevents e.g. a regular
            // property shadowing the container.
            //
            // A NESTED container's name does NOT — it's a field on an intermediate embedded
            // type (e.g. Money.cur when Money is itself @Embedded in this entity). Its name
            // has no meaning in this entity's namespace; a user's own Bill.cur property is
            // unrelated. Same guard as addEmbedded() — see P2.4.
            if (embedded.getParent() != null) continue;
            trackUniqueName(names, embedded.getName(), embedded);
        }
    }

    private void trackUniqueName(Map<String, Object> names, String name, Object object) throws ModelException {
        Object oldValue = names.put(name.toLowerCase(), object);
        if (oldValue != null) {
            throw new ModelException("Duplicate name \"" + name + "\" in entity \"" + className +
                    "\": [" + object + "] vs. [" + oldValue + "]");
        }
    }

    private void init3rdPassRelations() {
        for (ToOne toOne : toOneRelations) {
            toOne.init3ndPass();
        }
        for (ToManyBase toMany : toManyRelations) {
            toMany.init3rdPass();
        }
    }

    private void init3rdPassAdditionalImports() {
        for (ToOne toOne : toOneRelations) {
            Entity targetEntity = toOne.getTargetEntity();
            checkAdditionalImportsDaoTargetEntity(targetEntity);
        }

        for (ToManyBase toMany : toManyRelations) {
            Entity targetEntity = toMany.getTargetEntity();
            checkAdditionalImportsDaoTargetEntity(targetEntity);
        }

        // @Embedded container types: the generated Cursor's put() declares a hoisted local
        // (`Money __emb_price = entity.price`) typed as the embedded class, so the Cursor
        // needs an import if the embedded type lives outside the DAO package. Same pattern
        // as customType/converter below — only add if package differs.
        for (EmbeddedField embedded : embeddedFields) {
            String typeFqn = embedded.getTypeFullyQualifiedName();
            String pack = TextUtil.getPackageFromFullyQualified(typeFqn);
            if (pack != null && !pack.equals(javaPackageDao)) {
                additionalImportsDao.add(typeFqn);
            }
        }

        for (Property property : properties) {
            String customType = property.getCustomType();
            if (customType != null) {
                String pack = TextUtil.getPackageFromFullyQualified(customType);
                if (pack != null && !pack.equals(javaPackageDao)) {
                    additionalImportsDao.add(customType);
                }
            }

            String converter = property.getConverter();
            if (converter != null) {
                String pack = TextUtil.getPackageFromFullyQualified(converter);
                if (pack != null && !pack.equals(javaPackageDao)) {
                    additionalImportsDao.add(converter);
                }
            }

        }
    }

    private void checkAdditionalImportsDaoTargetEntity(Entity targetEntity) {
        if (!targetEntity.getJavaPackage().equals(javaPackageDao)) {
            additionalImportsDao.add(targetEntity.getJavaPackage() + "." + targetEntity.getClassName());
        }
    }

    public List<Index> getMultiIndexes() {
        return multiIndexes;
    }

    public Object getParsedElement() {
        return parsedElement;
    }

    public void setParsedElement(Object parsedElement) {
        this.parsedElement = parsedElement;
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public boolean isSyncSharedGlobalIds() {
        return this.syncSharedGlobalIds;
    }

    public void setSyncSharedGlobalIds(boolean enabled) {
        this.syncSharedGlobalIds = enabled;
    }

    /**
     * The {@link io.objectbox.annotation.ExternalName} of this entity.
     */
    @Nullable
    public String getExternalName() {
        return externalName;
    }

    /**
     * @see #getExternalName()
     */
    public void setExternalName(@Nonnull String externalName) {
        this.externalName = externalName;
    }

    /**
     * Based on this entities attributes computes required {@link EntityFlags}.
     *
     * @see #getEntityFlags()
     * @see #getEntityFlagsForModelFile()
     * @see #getEntityFlagsNames()
     */
    public void computeEntityFlags() {
        int flags = 0;
        int flagsModelFile = 0;
        Set<String> flagsNames = new LinkedHashSet<>(); // keep in insert-order

        if (!hasAllArgsConstructor()) {
            flags |= EntityFlags.USE_NO_ARG_CONSTRUCTOR;
            flagsNames.add("io.objectbox.model.EntityFlags.USE_NO_ARG_CONSTRUCTOR");
        }
        if (isSyncEnabled()) {
            flags |= EntityFlags.SYNC_ENABLED;
            flagsModelFile |= EntityFlags.SYNC_ENABLED;
            flagsNames.add("io.objectbox.model.EntityFlags.SYNC_ENABLED");
        }
        if (isSyncSharedGlobalIds()) {
            flags |= EntityFlags.SHARED_GLOBAL_IDS;
            flagsModelFile |= EntityFlags.SHARED_GLOBAL_IDS;
            flagsNames.add("io.objectbox.model.EntityFlags.SHARED_GLOBAL_IDS");
        }

        this.entityFlags = flags;
        this.entityFlagsModelFile = flagsModelFile;
        this.entityFlagsNames = flagsNames;
    }

    /**
     * Returns combined {@link io.objectbox.model.EntityFlags} value.
     */
    public int getEntityFlags() {
        if (entityFlags == null) {
            computeEntityFlags();
        }
        return entityFlags;
    }

    /**
     * Returns combined {@link io.objectbox.model.EntityFlags} value of only those flags
     * that should be stored in the model file. Returns null if there would be no flags.
     */
    @Nullable
    public Integer getEntityFlagsForModelFile() {
        if (entityFlagsModelFile == null) {
            computeEntityFlags();
        }
        return entityFlagsModelFile != 0 ? entityFlagsModelFile : null;
    }

    /**
     * Returns names of {@link io.objectbox.model.EntityFlags}.
     */
    public Set<String> getEntityFlagsNames() {
        if (entityFlagsNames == null) {
            computeEntityFlags();
        }
        return entityFlagsNames;
    }

    @Override
    public String toString() {
        return "Entity " + className + " (package: " + javaPackage + ")";
    }

}
