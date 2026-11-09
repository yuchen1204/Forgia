/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.util;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Call-scoped block-drop collection. Separate threads and nested loot evaluations never share a list. */
@ApiStatus.Internal
public final class BlockDropCapture {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private BlockDropCapture() {}

    public static void begin() {
        CURRENT.set(new Scope(CURRENT.get()));
    }

    public static Scope currentOrThrow() {
        final Scope scope = CURRENT.get();
        if (scope == null) {
            CURRENT.remove();
            throw new IllegalStateException("No block-drop capture is active");
        }
        return scope;
    }

    /** Returns false when the caller should add the item normally, without creating any capture. */
    public static boolean capture(ItemEntity item) {
        final Scope scope = CURRENT.get();
        if (scope == null) {
            CURRENT.remove();
            return false;
        }
        if (scope.drops == null) {
            // The current loot has finished, but its event/after-break callbacks are still running. They must
            // not inject standalone drops into an enclosing loot evaluation which has not resumed yet.
            return false;
        }
        scope.drops.add(item);
        return true;
    }

    /** Ends collection before event dispatch; enclosing collection resumes only when this scope closes. */
    public static List<ItemEntity> end() {
        return currentOrThrow().finish();
    }

    /** A finally guard for an individual begin/end pair, including drop events and after-break callbacks. */
    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        @Nullable
        private Scope previous;
        @Nullable
        private List<ItemEntity> drops = new ArrayList<>();
        private boolean closed;

        private Scope(@Nullable Scope previous) {
            this.previous = previous;
        }

        private void checkOwner() {
            if (Thread.currentThread() != this.owner) {
                throw new IllegalStateException("Block-drop capture must finish on the thread which started it");
            }
            if (CURRENT.get() != this) {
                throw new IllegalStateException("Block-drop captures must finish in nested order");
            }
        }

        private List<ItemEntity> finish() {
            this.checkOwner();
            if (this.drops == null) {
                throw new IllegalStateException("Block-drop capture has already finished");
            }
            final List<ItemEntity> captured = this.drops;
            this.drops = null;
            return captured;
        }

        private void detach() {
            if (this.previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(this.previous);
            }
            this.previous = null;
            this.drops = null;
            this.closed = true;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != this.owner) {
                throw new IllegalStateException("Block-drop capture must close on the thread which started it");
            }
            if (!this.closed) {
                this.checkOwner();
                // An interrupted loot computation must not leak its partial items into the next block's event.
                // The original exception still propagates; no extra items are spawned or replayed here.
                this.detach();
            }
        }
    }
}
