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

import org.junit.After;
import org.junit.Before;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

import io.objectbox.BoxStore;
import io.objectbox.BoxStoreBuilder;
import io.objectbox.ModelBuilder;
import io.objectbox.ModelBuilder.EntityBuilder;
import io.objectbox.model.PropertyFlags;
import io.objectbox.model.PropertyType;

import static org.junit.Assert.fail;

/**
 * Base class for embedded entity integration tests.
 * <p>
 * Provides BoxStore lifecycle management and model building for the {@link EmbeddedEntity}
 * with its flattened {@link Address} properties.
 */
public abstract class AbstractEmbeddedTest {

    protected static final boolean IN_MEMORY = Objects.equals(System.getProperty("obx.inMemory"), "true");

    protected File boxStoreDir;
    protected BoxStore store;

    // UID/ID counters for model building (same pattern as AbstractObjectBoxTest)
    int lastEntityId;
    int lastIndexId;
    long lastUid;
    long lastEntityUid;
    long lastIndexUid;

    @Before
    public void setUp() throws IOException {
        boxStoreDir = prepareTempDir("embedded-test");
        store = createBoxStore();
    }

    @After
    public void tearDown() {
        if (store != null) {
            try {
                store.close();
                store.deleteAllFiles();
            } catch (Exception e) {
                System.err.println("Could not clean up test: " + e.getMessage());
            }
        }
        cleanUpAllFiles(boxStoreDir);
    }

    protected File prepareTempDir(String prefix) throws IOException {
        if (IN_MEMORY) {
            return new File(BoxStore.IN_MEMORY_PREFIX + prefix + System.nanoTime());
        } else {
            File tempFile = File.createTempFile(prefix, "");
            if (!tempFile.delete()) {
                throw new IOException("Could not prep temp dir; file delete failed for " + tempFile.getAbsolutePath());
            }
            return tempFile;
        }
    }

    protected void cleanUpAllFiles(File dir) {
        if (dir != null && dir.exists()) {
            try (Stream<Path> stream = Files.walk(dir.toPath())) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                System.err.println("Could not delete file: " + path);
                                fail("Could not delete file");
                            }
                        });
            } catch (IOException e) {
                System.err.println("Could not walk directory for cleanup: " + e.getMessage());
            }
        }
    }

    protected BoxStore createBoxStore() {
        BoxStoreBuilder builder = new BoxStoreBuilder(createModel()).directory(boxStoreDir);
        registerEntities(builder);
        return builder.build();
    }

    protected byte[] createModel() {
        ModelBuilder modelBuilder = new ModelBuilder();
        addEmbeddedEntity(modelBuilder);
        addAdditionalEntities(modelBuilder);
        modelBuilder.lastEntityId(lastEntityId, lastEntityUid);
        if (lastIndexId > 0) {
            modelBuilder.lastIndexId(lastIndexId, lastIndexUid);
        }
        return modelBuilder.build();
    }

    /**
     * Override to add additional entities (MultiEmbeddedEntity, CustomerEntity) to the model.
     */
    protected void addAdditionalEntities(ModelBuilder modelBuilder) {
        // Default: no additional entities
    }

    /**
     * Override to register additional EntityInfo instances with the BoxStoreBuilder.
     */
    protected void registerEntities(BoxStoreBuilder builder) {
        builder.entity(new EmbeddedEntity_());
    }

    private void addEmbeddedEntity(ModelBuilder modelBuilder) {
        lastEntityUid = ++lastUid;
        EntityBuilder eb = modelBuilder.entity("EmbeddedEntity").id(++lastEntityId, lastEntityUid);

        // ordinal 0: id (Long) — ID flag
        eb.property("id", PropertyType.Long)
                .id(EmbeddedEntity_.id.id, ++lastUid)
                .flags(PropertyFlags.ID);

        // ordinal 1: name (String)
        eb.property("name", PropertyType.String)
                .id(EmbeddedEntity_.name.id, ++lastUid);

        // ordinal 2: address_street (String) — flattened from Address.street
        eb.property("address_street", PropertyType.String)
                .id(EmbeddedEntity_.address_street.id, ++lastUid);

        // ordinal 3: address_city (String) — flattened from Address.city
        eb.property("address_city", PropertyType.String)
                .id(EmbeddedEntity_.address_city.id, ++lastUid);

        // ordinal 4: address_zip (Int) — flattened from Address.zip
        eb.property("address_zip", PropertyType.Int)
                .id(EmbeddedEntity_.address_zip.id, ++lastUid);

        int lastPropId = EmbeddedEntity_.address_zip.id;
        eb.lastPropertyId(lastPropId, lastUid);
        eb.entityDone();
    }
}
