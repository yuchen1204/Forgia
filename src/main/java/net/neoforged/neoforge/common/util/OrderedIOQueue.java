/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.util;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.jetbrains.annotations.ApiStatus;

/** Ordered writes whose failures remain observable without preventing later accepted work from running. */
@ApiStatus.Internal
public final class OrderedIOQueue {
    private final Executor executor;
    private final ThreadLocal<Boolean> executing = new ThreadLocal<>();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    public OrderedIOQueue(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        final CompletableFuture<Void> previous;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        synchronized (this) {
            previous = this.tail;
            this.tail = completion;
        }
        // Reserve the successor first, but invoke neither the executor nor task under the queue monitor.
        previous.whenComplete((unused, previousFailure) -> {
            try {
                this.executor.execute(() -> {
                    final boolean nested = Boolean.TRUE.equals(this.executing.get());
                    this.executing.set(Boolean.TRUE);
                    Throwable failure = previousFailure;
                    try {
                        task.run();
                    } catch (Throwable next) {
                        failure = combine(failure, next);
                    } finally {
                        if (nested) {
                            this.executing.set(Boolean.TRUE);
                        } else {
                            this.executing.remove();
                        }
                    }
                    complete(completion, failure);
                });
            } catch (Throwable rejected) {
                complete(completion, combine(previousFailure, rejected));
            }
        });
    }

    /** Waits for work accepted before this call. Later submissions remain tracked, even during this wait. */
    public void await() {
        if (Boolean.TRUE.equals(this.executing.get())) {
            throw new IllegalStateException("An I/O task cannot wait for its own ordered queue");
        }
        final CompletableFuture<Void> pending;
        synchronized (this) {
            pending = this.tail;
        }
        try {
            pending.join();
        } finally {
            synchronized (this) {
                // Do not erase a successor appended while join was blocked. A newer tail carries any earlier
                // failure until that longer prefix is awaited; observing an error twice is preferable to loss.
                if (this.tail == pending) {
                    this.tail = CompletableFuture.completedFuture(null);
                }
            }
        }
    }

    private static Throwable combine(Throwable previous, Throwable next) {
        if (previous == null) {
            return next;
        }
        if (previous != next) {
            previous.addSuppressed(next);
        }
        return previous;
    }

    private static void complete(CompletableFuture<Void> completion, Throwable failure) {
        if (failure == null) {
            completion.complete(null);
        } else {
            completion.completeExceptionally(failure);
        }
    }
}
