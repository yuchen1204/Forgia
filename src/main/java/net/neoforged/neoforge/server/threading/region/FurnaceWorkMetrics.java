/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.jetbrains.annotations.ApiStatus;

/** Actual executed furnace-wave diagnostics, separate from the historical shadow work graph. */
@ApiStatus.Internal
public final class FurnaceWorkMetrics {
    private final LongAdder phases = new LongAdder();
    private final LongAdder waves = new LongAdder();
    private final LongAdder candidates = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder serial = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder changes = new LongAdder();
    private final LongAdder rejectedTasks = new LongAdder();
    private final LongAdder preparationNanos = new LongAdder();
    private final LongAdder executionNanos = new LongAdder();
    private final LongAdder commitNanos = new LongAdder();
    private final AtomicInteger activeBodies = new AtomicInteger();
    private final AtomicInteger maxSimultaneousBodies = new AtomicInteger();

    public enum Refusal {
        UNVERIFIED_RUNTIME, INVALID_BINDING, UNKNOWN_INSTANCE, NOT_BURNING, UNAVAILABLE_CHUNK,
        CUSTOM_ITEM, CUSTOM_RECIPE, ACTIVE_NOTIFICATIONS, SHARED_INVENTORY, REVALIDATION, SINGLE_UNIT, OVERSIZED_PHASE, RESOURCE_PROOF
    }

    private final java.util.Map<Refusal, LongAdder> refusals = new java.util.EnumMap<>(Refusal.class);

    FurnaceWorkMetrics() {
        for (final var reason : Refusal.values()) this.refusals.put(reason, new LongAdder());
    }

    public record Snapshot(long phases, long waves, long candidates, long nativeCompletions, long serialInvocations,
            long skipped, long failed, long committedChanges, long rejectedTasks, int maxSimultaneousBodies,
            long preparationNanos, long executionNanos, long commitNanos, java.util.Map<Refusal, Long> refusals) {
        public Snapshot {
            refusals = java.util.Map.copyOf(refusals);
        }
    }

    void refused(Refusal reason) {
        this.refusals.get(reason).increment();
    }

    void prepared(int candidates, long nanos) {
        this.phases.increment();
        this.candidates.add(candidates);
        this.preparationNanos.add(nanos);
    }

    void wave() {
        this.waves.increment();
    }

    void bodyStarted() {
        this.maxSimultaneousBodies.accumulateAndGet(this.activeBodies.incrementAndGet(), Math::max);
    }

    void bodyEnded() {
        this.activeBodies.decrementAndGet();
    }

    void completed() {
        this.completed.increment();
    }

    void serial() {
        this.serial.increment();
    }

    void skipped() {
        this.skipped.increment();
    }

    void failed() {
        this.failures.increment();
    }

    void change() {
        this.changes.increment();
    }

    void rejected(long count) {
        this.rejectedTasks.add(count);
    }

    void executed(long nanos) {
        this.executionNanos.add(nanos);
    }

    void committed(long nanos) {
        this.commitNanos.add(nanos);
    }

    public Snapshot snapshot() {
        final java.util.Map<Refusal, Long> counts = new java.util.EnumMap<>(Refusal.class);
        this.refusals.forEach((reason, count) -> {
            if (count.sum() != 0) counts.put(reason, count.sum());
        });
        return new Snapshot(this.phases.sum(), this.waves.sum(), this.candidates.sum(), this.completed.sum(), this.serial.sum(),
                this.skipped.sum(), this.failures.sum(), this.changes.sum(), this.rejectedTasks.sum(), this.maxSimultaneousBodies.get(),
                this.preparationNanos.sum(), this.executionNanos.sum(), this.commitNanos.sum(), counts);
    }
}
