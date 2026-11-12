/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api;

import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Cumulative scheduled callback counts for one block/fluid phase, aggregated across this engine's levels.
 * Dispatch eligibility is not execution: admitted counts selected callbacks, worker/serial completions count
 * invocations which returned normally, and failures/skips remain separate. Reasons count refused phases.
 * Use interval differences for rates. Labels and memory are bounded; no position or mod type is a metric key.
 */
@ApiStatus.Experimental
public record ScheduledTickStatus(String phase, long phases, long collected, long admitted, long workerCompletions,
        long serialCompletions, long failedCallbacks, long skippedCallbacks, long serialPhases,
        Map<SerialReason, Long> serialReasons, Timings timings, Latency latency) {

    public ScheduledTickStatus {
        serialReasons = Map.copyOf(serialReasons);
    }
    public enum SerialReason {
        UNCLASSIFIED_CALLBACK, FLUID_CALLBACK, NON_FIXED_LAYOUT, PROFILER_ACTIVE,
        UNLOADED_FOOTPRINT, FOREIGN_FOOTPRINT, UNSAFE_NEIGHBOR, DEFERRED_UPDATE_BUDGET
    }

    /** Worker time sums callback wall time across workers; phase time is wall time, including preparation. */
    public record Timings(long preparationNanos, long workerNanos, long dispatchAwaitNanos, long completionNanos, long phaseNanos) {}

    /** Exact percentiles of the last 128 nonempty dispatches, including refused serial phases; excludes collection. */
    public record Latency(int samples, long p50Nanos, long p95Nanos, long p99Nanos) {}
}
