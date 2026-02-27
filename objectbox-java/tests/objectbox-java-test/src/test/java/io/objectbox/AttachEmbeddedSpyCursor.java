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

import java.util.concurrent.atomic.AtomicInteger;

import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * Test-only spy cursor for {@link TestEntityMinimal}.
 * <p>
 * Overrides {@link Cursor#attachEmbedded(Object)} with an observable call counter so that wiring
 * tests can prove the hook <em>fires</em> on every read path — not merely that a no-op hook
 * doesn't break reads. Use {@link #reset()} before each test.
 * <p>
 * Register via {@link SpyEntityInfo#INSTANCE} in place of {@link TestEntityMinimal_}:
 * <pre>
 * builder.entity(AttachEmbeddedSpyCursor.SpyEntityInfo.INSTANCE);
 * </pre>
 * All cursor-creation paths ({@code Transaction.createCursor}, {@code Box.getReader/getWriter},
 * {@code Query.cursor()}) resolve through the registered {@code EntityInfo}'s cursor factory,
 * so every read on {@code TestEntityMinimal} will use this spy.
 */
public class AttachEmbeddedSpyCursor extends TestEntityMinimalCursor {

    /** Total invocations of {@link #attachEmbedded(TestEntityMinimal)} since the last {@link #reset()}. */
    public static final AtomicInteger calls = new AtomicInteger();

    /** Invocations where the argument was {@code null} (e.g. {@code get()} miss, end-of-iteration sentinel). */
    public static final AtomicInteger nullCalls = new AtomicInteger();

    /** Resets all spy counters. Call in {@code @Before}. */
    public static void reset() {
        calls.set(0);
        nullCalls.set(0);
    }

    AttachEmbeddedSpyCursor(Transaction tx, long cursorHandle, BoxStore boxStore) {
        super(tx, cursorHandle, boxStore);
    }

    @Override
    public void attachEmbedded(TestEntityMinimal entity) {
        calls.incrementAndGet();
        if (entity == null) {
            nullCalls.incrementAndGet();
        }
        super.attachEmbedded(entity);
    }

    /**
     * Spy factory — produces {@link AttachEmbeddedSpyCursor} instead of {@link TestEntityMinimalCursor}.
     */
    static final class SpyFactory implements CursorFactory<TestEntityMinimal> {
        @Override
        public Cursor<TestEntityMinimal> createCursor(Transaction tx, long cursorHandle, BoxStore boxStore) {
            return new AttachEmbeddedSpyCursor(tx, cursorHandle, boxStore);
        }
    }

    /**
     * Delegating {@link EntityInfo} — identical to {@link TestEntityMinimal_} except
     * {@link #getCursorFactory()} returns the spy factory.
     * <p>
     * BoxStore keys EntityInfo by {@link #getEntityClass()}, so this slots in transparently
     * wherever {@code TestEntityMinimal.class} is looked up.
     */
    public static final class SpyEntityInfo implements EntityInfo<TestEntityMinimal> {

        public static final SpyEntityInfo INSTANCE = new SpyEntityInfo();

        private static final CursorFactory<TestEntityMinimal> SPY_FACTORY = new SpyFactory();
        private final TestEntityMinimal_ delegate = TestEntityMinimal_.__INSTANCE;

        @Override public String getEntityName() { return delegate.getEntityName(); }
        @Override public String getDbName() { return delegate.getDbName(); }
        @Override public Class<TestEntityMinimal> getEntityClass() { return delegate.getEntityClass(); }
        @Override public int getEntityId() { return delegate.getEntityId(); }
        @Override public Property<TestEntityMinimal>[] getAllProperties() { return delegate.getAllProperties(); }
        @Override public Property<TestEntityMinimal> getIdProperty() { return delegate.getIdProperty(); }
        @Override public IdGetter<TestEntityMinimal> getIdGetter() { return delegate.getIdGetter(); }
        @Override public CursorFactory<TestEntityMinimal> getCursorFactory() { return SPY_FACTORY; }
    }
}
