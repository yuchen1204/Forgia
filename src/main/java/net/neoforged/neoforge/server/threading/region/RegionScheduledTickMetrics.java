/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.concurrent.atomic.LongAdder;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus.SerialReason;
import org.jetbrains.annotations.ApiStatus;

/** Two instances per engine, independent of the number of chunks, callbacks or dimensions. */
@ApiStatus.Internal
final class RegionScheduledTickMetrics {
    final LongAdder phases = new LongAdder();
    final LongAdder collected = new LongAdder();
    final LongAdder admitted = new LongAdder();
    final LongAdder workers = new LongAdder();
    final LongAdder serial = new LongAdder();
    final LongAdder failures = new LongAdder();
    final LongAdder skipped = new LongAdder();
    final LongAdder serialPhases = new LongAdder();
    final LongAdder preparationNanos = new LongAdder();
    final LongAdder workerNanos = new LongAdder();
    final LongAdder dispatchAwaitNanos = new LongAdder();
    final LongAdder completionNanos = new LongAdder();
    final LongAdder phaseNanos = new LongAdder();
    private final LongAdder[] reasons = new LongAdder[SerialReason.values().length];
    private final long[] window = new long[128];
    private int cursor;
    private int samples;

    RegionScheduledTickMetrics() {
        for (int i = 0; i < this.reasons.length; ++i) this.reasons[i] = new LongAdder();
    }

    void refused(SerialReason reason) {
        this.serialPhases.increment();
        this.reasons[reason.ordinal()].increment();
    }

    synchronized void recordPhase(long nanos) {
        this.phaseNanos.add(nanos);
        this.window[this.cursor] = nanos;
        this.cursor = (this.cursor + 1) % this.window.length;
        this.samples = Math.min(this.samples + 1, this.window.length);
    }

    synchronized ScheduledTickStatus snapshot(ScheduledTickPhase phase) {
        final EnumMap<SerialReason, Long> reasons = new EnumMap<>(SerialReason.class);
        for (final SerialReason reason : SerialReason.values()) {
            final long count = this.reasons[reason.ordinal()].sum();
            if (count > 0) reasons.put(reason, count);
        }
        final long[] sorted = Arrays.copyOf(this.window, this.samples);
        Arrays.sort(sorted);
        return new ScheduledTickStatus(phase.description(), this.phases.sum(), this.collected.sum(), this.admitted.sum(),
                this.workers.sum(), this.serial.sum(), this.failures.sum(), this.skipped.sum(), this.serialPhases.sum(), reasons,
                new ScheduledTickStatus.Timings(this.preparationNanos.sum(), this.workerNanos.sum(), this.dispatchAwaitNanos.sum(),
                        this.completionNanos.sum(), this.phaseNanos.sum()),
                new ScheduledTickStatus.Latency(this.samples, percentile(sorted, .50), percentile(sorted, .95), percentile(sorted, .99)));
    }

    private static long percentile(long[] sorted, double quantile) {
        return sorted.length == 0 ? 0L : sorted[Math.max(0, (int) Math.ceil(sorted.length * quantile) - 1)];
    }
}
