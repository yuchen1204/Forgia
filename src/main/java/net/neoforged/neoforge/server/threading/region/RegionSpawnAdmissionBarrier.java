/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Cooperative safe points for complete, ordered spawn-finalization transactions. The server never waits
 * inside pump: while a worker is still running, the surrounding batch must continue servicing its ordinary
 * main-thread requests. A transaction may run only when every enrolled worker is parked; unstarted groups
 * do not count as participants and cannot enter while transactions are pending/running.
 *
 * This is an internal scheduling primitive, not an enabled spawn mode. Callers must place checkpoints at
 * all relevant selection-loop boundaries and include finalization AND real admission in each transaction.
 * It must not be used to wait for the existing end-of-batch deferred admission queue: that would deadlock.
 */
@ApiStatus.Internal
public final class RegionSpawnAdmissionBarrier implements AutoCloseable {
    private final Thread serverThread;
    private final int maximumWorkers;
    private final Runnable beforeTransaction;
    private final Map<Thread, Worker> workers = new IdentityHashMap<>();
    private final ArrayDeque<Request<?>> pending = new ArrayDeque<>();
    private boolean servicing;
    private boolean inlinePending;
    private boolean closed;

    public RegionSpawnAdmissionBarrier(int maximumWorkers) {
        this(maximumWorkers, () -> {});
    }

    public RegionSpawnAdmissionBarrier(int maximumWorkers, Runnable beforeTransaction) {
        if (maximumWorkers < 1) throw new IllegalArgumentException("Worker bound must be positive");
        this.maximumWorkers = maximumWorkers;
        this.beforeTransaction = Objects.requireNonNull(beforeTransaction);
        this.serverThread = Thread.currentThread();
    }

    /** Enroll before touching group-owned world data. Use callInline for executor-rejected server groups. */
    public Worker enter() {
        if (Thread.currentThread() == this.serverThread) {
            throw new IllegalStateException("The server thread cannot enroll as a spawn worker");
        }
        boolean interrupted = false;
        try {
            synchronized (this) {
                if (this.workers.containsKey(Thread.currentThread())) throw new IllegalStateException("Worker already enrolled");
                while (this.servicing || (!this.closed && !this.pending.isEmpty())) interrupted |= this.awaitSignal();
                this.requireOpen();
                if (this.workers.size() >= this.maximumWorkers) throw new IllegalStateException("Spawn admission worker bound exceeded");
                final Worker worker = new Worker(Thread.currentThread());
                this.workers.put(Thread.currentThread(), worker);
                return worker;
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Executes a group/transaction rejected by the worker executor on the publishing server thread. This is
     * required during task submission, before the normal batch wait starts: merely waiting for workers here
     * could strand one that needs an ordinary server handoff to reach its safe point. New worker groups stay
     * outside the active set until this parked phase ends. Nested native finalization already inside a parked
     * server transaction runs inline, preserving its ordinary parent/child visibility.
     */
    public <T> T callInline(Supplier<T> operation, Runnable serviceOtherRequests) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(serviceOtherRequests);
        if (Thread.currentThread() != this.serverThread) throw new IllegalStateException("Inline admission requires the owning server thread");
        final Request<T> request;
        final boolean nested;
        synchronized (this) {
            this.requireOpen();
            nested = this.servicing;
            if (!nested && this.inlinePending) throw new IllegalStateException("Inline admission cannot wait recursively before workers are parked");
            request = nested ? null : new Request<>(operation);
            if (request != null) {
                this.pending.addLast(request);
                this.inlinePending = true;
            }
        }
        if (nested) return operation.get();
        try {
            while (true) {
                synchronized (this) {
                    if (request.finished && !this.servicing) break;
                }
                serviceOtherRequests.run();
                if (!this.pump()) LockSupport.parkNanos(100_000L);
            }
        } catch (Throwable failure) {
            // Submission/wait failed. Wake queued workers before the caller waits for group completion.
            this.close();
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Inline spawn admission wait failed", failure);
        } finally {
            synchronized (this) {
                this.inlinePending = false;
            }
        }
        return request.result();
    }

    /**
     * Nonblocking server-thread pump. False means workers still need to reach safe points (or no work exists),
     * so the caller must keep pumping ordinary batch handoffs instead of blocking here. Each queued operation
     * runs once, outside this object's monitor, with all workers parked. A failing operation does not replay or
     * prevent later independent operations from receiving their result.
     */
    public boolean pump() {
        if (Thread.currentThread() != this.serverThread) throw new IllegalStateException("Spawn admission requires the owning server thread");
        final List<Request<?>> requests;
        synchronized (this) {
            if (this.servicing) throw new IllegalStateException("Spawn admission pump cannot be reentered");
            if (this.closed || this.pending.isEmpty()) return false;
            for (final Worker worker : this.workers.values()) if (!worker.parked) return false;
            requests = new ArrayList<>(this.pending);
            this.pending.clear();
            this.servicing = true;
        }
        try {
            for (final Request<?> request : requests) this.execute(request);
        } finally {
            synchronized (this) {
                this.servicing = false;
                this.notifyAll();
            }
        }
        return true;
    }

    public synchronized boolean isServerPhase() {
        return Thread.currentThread() == this.serverThread && this.servicing;
    }

    private <T> void execute(Request<T> request) {
        synchronized (this) {
            if (this.closed) {
                request.failure = closedFailure();
                request.finished = true;
                return;
            }
        }
        T value = null;
        Throwable failure = null;
        try {
            this.beforeTransaction.run();
            value = request.operation.get();
        } catch (Throwable throwable) {
            failure = throwable;
        }
        synchronized (this) {
            request.value = value;
            request.failure = failure;
            request.finished = true;
        }
    }

    /**
     * Cancel unstarted work and wake waiters. A transaction already executing may have world side effects;
     * it is allowed to finish and is never relabelled as cancelled or retried. Workers remain parked until
     * that transaction has stopped touching their candidates, even if close or interruption happened.
     */
    @Override
    public synchronized void close() {
        this.closed = true;
        for (final Request<?> request : this.pending) {
            request.failure = closedFailure();
            request.finished = true;
        }
        this.pending.clear();
        this.notifyAll();
    }

    private static IllegalStateException closedFailure() {
        return new IllegalStateException("Spawn admission barrier is closed; operation must not be replayed");
    }

    private void requireOpen() {
        if (this.closed) throw closedFailure();
    }

    /** All waits hold this monitor and preserve interruption until world access is safe again. */
    private boolean awaitSignal() {
        try {
            this.wait();
            return false;
        } catch (InterruptedException interrupted) {
            return true;
        }
    }

    public final class Worker implements AutoCloseable {
        private final Thread thread;
        private boolean parked;
        private boolean retired;

        private Worker(Thread thread) {
            this.thread = thread;
        }

        /** Poll between selection attempts, after releasing temporary world references/iteration cursors. */
        public void checkpoint() {
            boolean interrupted = false;
            try {
                synchronized (RegionSpawnAdmissionBarrier.this) {
                    this.requireOwner();
                    while (RegionSpawnAdmissionBarrier.this.servicing
                            || (!RegionSpawnAdmissionBarrier.this.closed && !RegionSpawnAdmissionBarrier.this.pending.isEmpty())) {
                        if (!this.parked) {
                            this.parked = true;
                            RegionSpawnAdmissionBarrier.this.notifyAll();
                        }
                        interrupted |= RegionSpawnAdmissionBarrier.this.awaitSignal();
                    }
                    this.parked = false;
                    RegionSpawnAdmissionBarrier.this.requireOpen();
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /**
         * Submit one complete candidate transaction and suspend this worker. A peer cannot decide against a
         * provisional slot: the real result of earlier server transactions is visible before it executes.
         * The worker resumes only after the server finishes the entire parked phase, not midway through a
         * peer's finalizer. No transaction may retain a continuation that touches the candidate after return.
         */
        public <T> T call(Supplier<T> operation) {
            Objects.requireNonNull(operation);
            boolean interrupted = false;
            final Request<T> request = new Request<>(operation);
            try {
                synchronized (RegionSpawnAdmissionBarrier.this) {
                    this.requireOwner();
                    RegionSpawnAdmissionBarrier.this.requireOpen();
                    RegionSpawnAdmissionBarrier.this.pending.addLast(request);
                    this.parked = true;
                    RegionSpawnAdmissionBarrier.this.notifyAll();
                    while (!request.finished || RegionSpawnAdmissionBarrier.this.servicing) {
                        interrupted |= RegionSpawnAdmissionBarrier.this.awaitSignal();
                    }
                    // A peer may have queued the next phase before this worker woke. It must reach a
                    // checkpoint before further world access; this method's completed transaction is not replayed.
                    this.parked = false;
                    return request.result();
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void requireOwner() {
            if (Thread.currentThread() != this.thread || this.retired) throw new IllegalStateException("Spawn worker token is not owned by this thread");
        }

        @Override
        public void close() {
            synchronized (RegionSpawnAdmissionBarrier.this) {
                this.requireOwner();
                if (this.parked) throw new IllegalStateException("Cannot retire a parked spawn worker");
                this.retired = true;
                RegionSpawnAdmissionBarrier.this.workers.remove(this.thread);
                RegionSpawnAdmissionBarrier.this.notifyAll();
            }
        }
    }

    private static final class Request<T> {
        private final Supplier<T> operation;
        private T value;
        private Throwable failure;
        private boolean finished;

        private Request(Supplier<T> operation) {
            this.operation = operation;
        }

        private T result() {
            if (this.failure instanceof RuntimeException exception) throw exception;
            if (this.failure instanceof Error error) throw error;
            if (this.failure != null) throw new IllegalStateException("Spawn admission failed", this.failure);
            return this.value;
        }
    }
}
