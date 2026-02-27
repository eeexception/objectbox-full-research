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

import io.objectbox.BoxStore;
import io.objectbox.Cursor;
import io.objectbox.Transaction;
import io.objectbox.annotation.apihint.Internal;
import io.objectbox.internal.CursorFactory;

/**
 * Cursor for "TestBill" — hand-authored replica of the <b>post-transform</b> cursor.
 * <p>
 * Two halves of the {@code @Embedded} story live here:
 * <dl>
 *   <dt>{@link #put(TestBill)}</dt>
 *   <dd>Write path — matches the annotation processor's output EXACTLY (verified against a dump
 *   of the real generator via {@code objectbox-processor/.../EmbeddedTest.kt}). Hoists the
 *   container into a local, guards every embedded property's ID on container-null, reads through
 *   the container via {@code __emb_price.currency} / {@code __emb_price.amount}.</dd>
 *
 *   <dt>{@link #attachEmbedded(TestBill)}</dt>
 *   <dd>Read path — matches the bytecode transformer's injected body EXACTLY (verified against
 *   {@code EmbeddedTransform.buildAttachEmbeddedBody()} in the code-modifier module). The real
 *   generator emits an EMPTY stub here; the transformer fills it post-APT. We hand-write the
 *   filled form because this module bypasses the transformer.</dd>
 * </dl>
 * If either the generator's {@code PropertyCollector} or the transformer's
 * {@code buildAttachEmbeddedBody} changes shape, this class must be updated to match — it is the
 * ground-truth spec for what the pipeline should produce. Any divergence that passes processor
 * tests but fails here means the JNI contract was violated.
 */
public final class TestBillCursor extends Cursor<TestBill> {

    @Internal
    static final class Factory implements CursorFactory<TestBill> {
        @Override
        public Cursor<TestBill> createCursor(Transaction tx, long cursorHandle, BoxStore boxStoreForEntities) {
            return new TestBillCursor(tx, cursorHandle, boxStoreForEntities);
        }
    }

    private static final TestBill_.TestBillIdGetter ID_GETTER = TestBill_.__ID_GETTER;

    // Property IDs — static because immutable per-schema; cached because read on every put().
    private final static int __ID_provider = TestBill_.provider.id;
    private final static int __ID_priceCurrency = TestBill_.priceCurrency.id;
    private final static int __ID_priceAmount = TestBill_.priceAmount.id;

    public TestBillCursor(Transaction tx, long cursor, BoxStore boxStore) {
        super(tx, cursor, TestBill_.__INSTANCE, boxStore);
    }

    @Override
    public long getId(TestBill entity) {
        return ID_GETTER.getId(entity);
    }

    /**
     * <b>Write path</b> — serializes {@code entity} into a native {@code collect} call.
     * <p>
     * The embedded handling pattern (verified against real generator output):
     * <ol>
     *   <li><b>Hoist</b> the container once: {@code TestMoney __emb_price = entity.price;}.
     *       One read, one null-check site per container — NOT per inner field.</li>
     *   <li><b>Guard</b> every embedded property's ID on container-null:
     *       {@code __id2 = __emb_price != null ? __ID_priceCurrency : 0}. Passing {@code 0} as a
     *       property ID tells native to treat that slot as <i>absent</i> — the column is stored
     *       as null (reference types) or left at default (primitive types).</li>
     *   <li><b>Double-guard the value expression</b>: {@code __id2 != 0 ? __emb_price.currency
     *       : null}. The {@code __id2 != 0} branch proves {@code __emb_price != null}, so the
     *       container dereference is NPE-safe without an explicit container check at every site.</li>
     * </ol>
     * This is precisely what {@code PropertyCollector.java} emits — see the
     * {@code isEmbedded()} branch in {@code appendProperty()}.
     * <p>
     * The {@code collect313311} slot layout ({@link Cursor#collect313311}):
     * {@code (3×String, 1×byte[], 3×long, 3×int, 1×float, 1×double)} — our 2 Strings go in slots
     * 1–2, the double in the trailing double slot. Every unused slot passes {@code 0, 0/null}.
     */
    @Override
    public long put(TestBill entity) {
        TestMoney __emb_price = entity.price;

        String provider = entity.provider;
        int __id1 = provider != null ? __ID_provider : 0;
        int __id2 = __emb_price != null ? __ID_priceCurrency : 0;
        int __id3 = __emb_price != null ? __ID_priceAmount : 0;

        long __assignedId = collect313311(cursor, entity.id, PUT_FLAG_FIRST | PUT_FLAG_COMPLETE,
                __id1, provider, __id2, __id2 != 0 ? __emb_price.currency : null,
                0, null, 0, null,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, __id3, __id3 != 0 ? __emb_price.amount : 0);

        entity.id = __assignedId;

        return __assignedId;
    }

    /**
     * <b>Read path</b> — hydrates the {@link TestBill#price} container from the synthetic flat
     * fields JNI has just set (or nullifies it if those flats signal "container was null").
     * <p>
     * Call sequence: {@code nativeGetEntity()} → JNI allocates {@code TestBill} via no-arg ctor
     * → JNI calls {@code SetObjectField(priceCurrency, ...)} + {@code SetDoubleField(priceAmount,
     * ...)} by property name → base {@link Cursor#get(long)} calls {@code attachEmbedded(entity)}
     * → THIS method either copies flats into a fresh {@link TestMoney} or sets
     * {@code entity.price = null}.
     * <p>
     * Body shape matches the transformer's {@code EmbeddedTransform.buildAttachEmbeddedBody()}:
     * <ul>
     *   <li><b>Null-guard first</b> — called unconditionally from every read hook including misses
     *       ({@code get(missingKey)} → null) and iteration-end ({@code next()} → null).</li>
     *   <li><b>Hydration guard</b> on object-typed synthetic(s): {@code if (priceCurrency != null)}.
     *       Object-typed flats are the only reliable null-vs-absent signal — a primitive
     *       {@code double} can't tell DB-NULL apart from stored-zero. If every object-typed flat
     *       reads back as null, the container is presumed to have been null at write time
     *       ({@code put()} passes property-ID {@code 0} for every embedded column when the
     *       container is null → native stores nothing → JNI doesn't call {@code SetObjectField}
     *       → synthetic stays at its Java default of {@code null}).</li>
     *   <li><b>Else-nullify</b> — explicitly sets {@code entity.price = null}. Without this the
     *       user's constructor-default (typically {@code null} but not guaranteed —
     *       {@code = new Money()} in the ctor is legal) would survive, breaking round-trip.</li>
     *   <li><b>Build-into-local-then-assign</b> — {@code __emb.x = ...; entity.price = __emb;}
     *       not {@code entity.price.x = ...}. Dodges the null-check on the entity's existing
     *       container field (which is whatever the user's ctor left it — typically null).</li>
     * </ul>
     * The per-container block scope (explicit {@code { }}) lets multiple containers share the
     * {@code __emb} local name without collision — see the two-container transformer test.
     */
    @Override
    public void attachEmbedded(TestBill entity) {
        if (entity == null) return;
        {
            if (entity.priceCurrency != null) {
                TestMoney __emb = new TestMoney();
                __emb.currency = entity.priceCurrency;
                __emb.amount = entity.priceAmount;
                entity.price = __emb;
            } else {
                entity.price = null;
            }
        }
    }
}
