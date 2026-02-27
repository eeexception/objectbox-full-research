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

package io.objectbox;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.objectbox.query.Query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Contract + wiring tests for {@link Cursor#attachEmbedded}.
 * <p>
 * The <b>contract tests</b> (C1/C1b/C1c/C3) guard the signature and no-op default of the runtime
 * hook that enables {@link io.objectbox.annotation.Embedded @Embedded} component mapping.
 * <p>
 * The <b>wiring tests</b> (W1–W7) use {@link AttachEmbeddedSpyCursor} — a cursor subclass whose
 * {@code attachEmbedded} override increments an observable counter — to prove the hook
 * <em>fires</em> on every read path. Removing the {@code attachEmbedded(entity)} call from any
 * wired read method in {@link Cursor} or {@link Query} will turn the corresponding W-test red.
 */
public class CursorAttachEmbeddedTest extends AbstractObjectBoxTest {

    private Box<TestEntityMinimal> spyBox;

    /**
     * Registers {@link TestEntityMinimal} under the spy cursor factory so every cursor for that
     * entity is an {@link AttachEmbeddedSpyCursor} with an observable call counter.
     * <p>
     * {@code TestEntity} keeps its normal cursor for the contract tests (C1/C1b/C1c/C3).
     */
    @Override
    protected BoxStore createBoxStore() {
        BoxStoreBuilder builder = new BoxStoreBuilder(createTestModelWithTwoEntities(false)).directory(boxStoreDir);
        builder.entity(new TestEntity_());
        builder.entity(AttachEmbeddedSpyCursor.SpyEntityInfo.INSTANCE); // spy swap
        return builder.build();
    }

    @Before
    @Override
    public void setUp() throws IOException {
        super.setUp();
        AttachEmbeddedSpyCursor.reset();
        spyBox = store.boxFor(TestEntityMinimal.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Contract tests — signature & no-op default. These had a real RED phase (method didn't exist).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * C1 — The base {@link Cursor#attachEmbedded(Object)} must exist and do nothing.
     * Direct invocation on a live cursor for an entity with no embedded fields must not mutate
     * the entity and must not throw.
     */
    @Test
    public void attachEmbeddedDefaultIsNoOp() {
        TestEntity entity = new TestEntity();
        entity.setSimpleInt(1977);
        entity.setSimpleString("nanotech");

        Transaction tx = store.beginTx();
        Cursor<TestEntity> cursor = tx.createCursor(TestEntity.class);
        try {
            cursor.attachEmbedded(entity);
            assertEquals(1977, entity.getSimpleInt());
            assertEquals("nanotech", entity.getSimpleString());
        } finally {
            cursor.close();
            tx.abort();
        }
    }

    /**
     * C1b — {@link Cursor#attachEmbedded(Object)} must tolerate {@code null} (e.g. {@code get()} on
     * a missing key returns {@code null} and the wiring must pass it through untouched).
     */
    @Test
    public void attachEmbeddedAcceptsNull() {
        Transaction tx = store.beginTx();
        Cursor<TestEntity> cursor = tx.createCursor(TestEntity.class);
        try {
            cursor.attachEmbedded((TestEntity) null); // must not throw
        } finally {
            cursor.close();
            tx.abort();
        }
    }

    /**
     * C1c — {@link Cursor#attachEmbedded(List)} must tolerate {@code null} and an empty list.
     */
    @Test
    public void attachEmbeddedListAcceptsNullAndEmpty() {
        Transaction tx = store.beginTx();
        Cursor<TestEntity> cursor = tx.createCursor(TestEntity.class);
        try {
            cursor.attachEmbedded((List<TestEntity>) null);
            cursor.attachEmbedded(new ArrayList<TestEntity>());
        } finally {
            cursor.close();
            tx.abort();
        }
    }

    /**
     * C3 — Contract guard: a generated Cursor subclass must be able to {@code @Override}
     * {@link Cursor#attachEmbedded(Object)} with a concrete entity type.
     * <p>
     * This is a compile-time guard — the {@code @Override} in {@link AttachEmbeddedSpyCursor}
     * (and in the inline class below) breaks if the hook signature is removed, renamed, made
     * {@code final}, or changed incompatibly.
     */
    @Test
    public void subclassCanOverrideAttachEmbedded() {
        // Runtime proof: the spy-registered cursor IS our overriding subclass.
        Transaction tx = store.beginTx();
        try {
            Cursor<TestEntityMinimal> cursor = tx.createCursor(TestEntityMinimal.class);
            try {
                assertEquals(AttachEmbeddedSpyCursor.class, cursor.getClass());
            } finally {
                cursor.close();
            }
        } finally {
            tx.abort();
        }

        // Compile-time proof — mirrors a real generated cursor (extends Cursor<T> directly).
        @SuppressWarnings("unused")
        class OverrideProof extends Cursor<TestEntity> {
            OverrideProof(Transaction tx, long handle, BoxStore store) {
                super(tx, handle, TestEntity_.__INSTANCE, store);
            }
            @Override protected long getId(TestEntity e) { return e.getId(); }
            @Override public long put(TestEntity e) { throw new UnsupportedOperationException(); }
            @Override public void attachEmbedded(TestEntity entity) { super.attachEmbedded(entity); }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Wiring tests — observable spy counter proves the hook FIRES on every read path.
    // RED if the `attachEmbedded(...)` call is removed from the wired read method.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * W1 — {@link Cursor#get(long)} must invoke {@code attachEmbedded} once per get, including
     * on miss ({@code null} result).
     */
    @Test
    public void attachEmbeddedFiresOnCursorGet() {
        long id = spyBox.put(new TestEntityMinimal(0, "one"));
        AttachEmbeddedSpyCursor.reset(); // discount put

        Transaction tx = store.beginReadTx();
        Cursor<TestEntityMinimal> cursor = tx.createCursor(TestEntityMinimal.class);
        try {
            TestEntityMinimal hit = cursor.get(id);
            assertNotNull(hit);
            assertEquals("one", hit.getText());
            assertEquals("get(hit) must fire hook", 1, AttachEmbeddedSpyCursor.calls.get());
            assertEquals(0, AttachEmbeddedSpyCursor.nullCalls.get());

            assertNull(cursor.get(999_999L));
            assertEquals("get(miss) must fire hook with null", 2, AttachEmbeddedSpyCursor.calls.get());
            assertEquals(1, AttachEmbeddedSpyCursor.nullCalls.get());
        } finally {
            cursor.close();
            tx.abort();
        }
    }

    /**
     * W2 — {@link Cursor#first()} and {@link Cursor#next()} must each invoke {@code attachEmbedded},
     * including the final {@code next()} returning the end-of-iteration {@code null}.
     */
    @Test
    public void attachEmbeddedFiresOnFirstAndNext() {
        spyBox.put(new TestEntityMinimal(0, "a"));
        spyBox.put(new TestEntityMinimal(0, "b"));
        spyBox.put(new TestEntityMinimal(0, "c"));
        AttachEmbeddedSpyCursor.reset();

        Transaction tx = store.beginReadTx();
        Cursor<TestEntityMinimal> cursor = tx.createCursor(TestEntityMinimal.class);
        try {
            assertNotNull(cursor.first()); // 1
            assertNotNull(cursor.next());  // 2
            assertNotNull(cursor.next());  // 3
            assertNull(cursor.next());     // 4 — null sentinel

            assertEquals(4, AttachEmbeddedSpyCursor.calls.get());
            assertEquals(1, AttachEmbeddedSpyCursor.nullCalls.get());
        } finally {
            cursor.close();
            tx.abort();
        }
    }

    /**
     * W3 — {@link Cursor#getAll()} must invoke {@code attachEmbedded} once per returned entity
     * (list overload iterates and delegates).
     */
    @Test
    public void attachEmbeddedFiresOnCursorGetAll() {
        spyBox.put(new TestEntityMinimal(0, "x"));
        spyBox.put(new TestEntityMinimal(0, "y"));
        AttachEmbeddedSpyCursor.reset();

        Transaction tx = store.beginReadTx();
        Cursor<TestEntityMinimal> cursor = tx.createCursor(TestEntityMinimal.class);
        try {
            List<TestEntityMinimal> all = cursor.getAll();
            assertEquals(2, all.size());
            assertEquals(2, AttachEmbeddedSpyCursor.calls.get());
            assertEquals(0, AttachEmbeddedSpyCursor.nullCalls.get());
        } finally {
            cursor.close();
            tx.abort();
        }
    }

    /**
     * W4 — {@link Box#get(long)} routes through {@link Cursor#get(long)}, so the hook fires
     * transitively. Guards that Box's reader path uses the registered cursor subclass.
     */
    @Test
    public void attachEmbeddedFiresOnBoxGet() {
        long id = spyBox.put(new TestEntityMinimal(0, "box"));
        AttachEmbeddedSpyCursor.reset();

        TestEntityMinimal e = spyBox.get(id);
        assertNotNull(e);
        assertEquals("box", e.getText());
        assertEquals(1, AttachEmbeddedSpyCursor.calls.get());
    }

    /**
     * W5 — {@link Query#find()} must invoke {@code attachEmbedded} once per result (native find
     * bypasses Cursor's Java read methods, so Query wires the hook explicitly).
     */
    @Test
    public void attachEmbeddedFiresOnQueryFind() {
        spyBox.put(new TestEntityMinimal(0, "q1"));
        spyBox.put(new TestEntityMinimal(0, "q2"));
        spyBox.put(new TestEntityMinimal(0, "q3"));
        AttachEmbeddedSpyCursor.reset();

        try (Query<TestEntityMinimal> query = spyBox.query().build()) {
            List<TestEntityMinimal> results = query.find();
            assertEquals(3, results.size());
            assertEquals(3, AttachEmbeddedSpyCursor.calls.get());
        }
    }

    /**
     * W6 — {@link Query#findFirst()} must invoke {@code attachEmbedded} once for the single result
     * (or once with {@code null} when empty).
     */
    @Test
    public void attachEmbeddedFiresOnQueryFindFirst() {
        spyBox.put(new TestEntityMinimal(0, "first"));
        spyBox.put(new TestEntityMinimal(0, "second"));
        AttachEmbeddedSpyCursor.reset();

        try (Query<TestEntityMinimal> query = spyBox.query().build()) {
            TestEntityMinimal e = query.findFirst();
            assertNotNull(e);
            assertEquals(1, AttachEmbeddedSpyCursor.calls.get());
            assertEquals(0, AttachEmbeddedSpyCursor.nullCalls.get());
        }

        // Empty store → findFirst() returns null, hook still fires.
        spyBox.removeAll();
        AttachEmbeddedSpyCursor.reset();
        try (Query<TestEntityMinimal> query = spyBox.query().build()) {
            assertNull(query.findFirst());
            assertEquals(1, AttachEmbeddedSpyCursor.calls.get());
            assertEquals(1, AttachEmbeddedSpyCursor.nullCalls.get());
        }
    }

    /**
     * W7 — {@link Query#findUnique()} must invoke {@code attachEmbedded} once (with the match,
     * or with {@code null} when no match).
     */
    @Test
    public void attachEmbeddedFiresOnQueryFindUnique() {
        spyBox.put(new TestEntityMinimal(0, "only"));
        AttachEmbeddedSpyCursor.reset();

        try (Query<TestEntityMinimal> query = spyBox.query().build()) {
            TestEntityMinimal e = query.findUnique();
            assertNotNull(e);
            assertEquals("only", e.getText());
            assertEquals(1, AttachEmbeddedSpyCursor.calls.get());
        }
    }

    /**
     * W8 — {@link Query#find(long, long)} (offset/limit) must invoke {@code attachEmbedded} once
     * per returned entity.
     */
    @Test
    public void attachEmbeddedFiresOnQueryFindOffsetLimit() {
        for (int i = 0; i < 5; i++) spyBox.put(new TestEntityMinimal(0, "e" + i));
        AttachEmbeddedSpyCursor.reset();

        try (Query<TestEntityMinimal> query = spyBox.query().build()) {
            List<TestEntityMinimal> page = query.find(1, 2); // skip 1, take 2
            assertEquals(2, page.size());
            assertEquals(2, AttachEmbeddedSpyCursor.calls.get());
        }
    }
}
