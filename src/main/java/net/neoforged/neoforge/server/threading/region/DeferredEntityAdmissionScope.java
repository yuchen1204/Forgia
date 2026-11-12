/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Internal receipts for admissions recorded inside a worker's prepared body. A queued addition is not a
 * successful world admission. Receipts are transferred to the batch, which resolves them from the actual
 * per-member result. This scope grants no world access and does not enable any parallel adapter.
 */
@ApiStatus.Internal
public final class DeferredEntityAdmissionScope implements AutoCloseable {
    public enum Result {
        /** The real addFreshEntity call returned true. */
        ADMITTED,
        /** The real call returned false, or graph prevalidation refused the admission. */
        REJECTED,
        /** Never attempted by the owning queue: shutdown, closed seam or an unattempted graph suffix. */
        ABANDONED,
        /** No transfer to the observed queue. Another admission path may have been used; invalidate the view. */
        UNOBSERVED,
        /** The real call threw; it may already have produced side effects. Never replay it. */
        FAILED
    }

    /** Exactly-once notification; a callback failure is reported by the owning scope/barrier. */
    static final class Receipt {
        private final Consumer<Result> completion;
        private final AtomicBoolean resolved = new AtomicBoolean();
        private boolean transferred;

        private Receipt(Consumer<Result> completion) {
            this.completion = completion;
        }

        void complete(Result result) {
            if (this.resolved.compareAndSet(false, true)) this.completion.accept(result);
        }
    }

    private static final ThreadLocal<DeferredEntityAdmissionScope> CURRENT = new ThreadLocal<>();
    @Nullable
    private final DeferredEntityAdmissionScope previous;
    private final Thread owner = Thread.currentThread();
    private final Map<Entity, Receipt> receipts = new IdentityHashMap<>();
    private boolean closed;

    private DeferredEntityAdmissionScope() {
        this.previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static DeferredEntityAdmissionScope open() {
        return new DeferredEntityAdmissionScope();
    }

    /**
     * Bind on the candidate's owning thread, before its deferred addition. Unrecorded candidates report
     * UNOBSERVED at scope exit: an out-of-lease handoff or direct admission may have used another path.
     * Completion may run on the calling worker, server or shutdown
     * thread; it must only update thread-safe accounting, never query or mutate the world.
     */
    public void watch(Entity entity, Consumer<Result> completion) {
        this.requireCurrent();
        Objects.requireNonNull(entity);
        Objects.requireNonNull(completion);
        for (var scope = this; scope != null; scope = scope.previous) {
            if (scope.receipts.containsKey(entity)) throw new IllegalArgumentException("Entity already has an admission receipt");
        }
        this.receipts.put(entity, new Receipt(completion));
    }

    /**
     * Explicit finalizer cancellation before any admission attempt. Only the owner of the finalizer can
     * establish this fact; scope exit alone cannot distinguish cancellation from an unobserved handoff.
     */
    public void rejectBeforeAdmission(Entity entity) {
        this.requireCurrent();
        final Receipt receipt = this.receipts.get(Objects.requireNonNull(entity));
        if (receipt == null || receipt.transferred || receipt.resolved.get()) {
            throw new IllegalStateException("Candidate is not awaiting its first admission attempt");
        }
        receipt.complete(Result.REJECTED);
    }

    static boolean isActive() {
        return CURRENT.get() != null;
    }

    static void validateCapture(Entity entity) {
        for (var scope = CURRENT.get(); scope != null; scope = scope.previous) {
            final Receipt receipt = scope.receipts.get(entity);
            if (receipt != null) {
                if (!receipt.transferred && receipt.resolved.get()) {
                    throw new IllegalStateException("A cancelled candidate cannot queue an admission");
                }
                return;
            }
        }
    }

    /** Called only after the batch has accepted the graph. Does not execute any completion under its lock. */
    @Nullable
    static Receipt capture(Entity entity) {
        for (var scope = CURRENT.get(); scope != null; scope = scope.previous) {
            final Receipt receipt = scope.receipts.get(entity);
            if (receipt != null) {
                if (receipt.transferred) return null;
                receipt.transferred = true;
                return receipt;
            }
        }
        return null;
    }

    private void requireCurrent() {
        if (Thread.currentThread() != this.owner || this.closed || CURRENT.get() != this) {
            throw new IllegalStateException("Admission scope must be used and closed in nesting order on its owning thread");
        }
    }

    @Override
    public void close() {
        this.requireCurrent();
        this.closed = true;
        if (this.previous == null) CURRENT.remove();
        else CURRENT.set(this.previous);
        Throwable failure = null;
        for (final Receipt receipt : this.receipts.values()) {
            if (!receipt.transferred) {
                try {
                    receipt.complete(Result.UNOBSERVED);
                } catch (Throwable throwable) {
                    if (failure == null) failure = throwable;
                    else if (failure != throwable) failure.addSuppressed(throwable);
                }
            }
        }
        this.receipts.clear();
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Admission receipt cleanup failed", failure);
    }
}
