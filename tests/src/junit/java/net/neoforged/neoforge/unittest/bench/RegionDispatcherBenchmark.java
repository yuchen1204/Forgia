/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.neoforged.neoforge.server.threading.region.RegionTaskBatch;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Dispatch-only paired measurement of the same persistent pool with individual and batch submission.
 * No Minecraft world, Mockito proxies, sleeps or timing assertions. Inputs are prebuilt for both paths;
 * measured wall time includes submission, original task work and the complete per-group barrier wait.
 * Allocations cover the submitting thread only. This is not server MSPT or a whole-server speedup.
 */
@Tag("benchmark")
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class RegionDispatcherBenchmark {
    private static final int WORKERS = 4;
    private static final int WARMUP = 100;
    private static final int MEASURED = 400;
    private static final boolean[] BATCHED = { false, true, true, false, false, true };
    private static volatile long sink;

    @Test
    void reportsPairedDispatchAndImbalancedTaskCosts() throws Exception {
        final Class<?> type = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool");
        final Constructor<?> constructor = type.getDeclaredConstructor(int.class, boolean.class);
        constructor.setAccessible(true);
        final var allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        for (final int count : new int[] { 64, 256, 1024 }) {
            for (final boolean skewed : new boolean[] { false, true }) {
                final RegionWorkerExecutor pool = (RegionWorkerExecutor) constructor.newInstance(WORKERS, true);
                try {
                    // The forwarding executor exercises the unchanged pre-dispatcher per-task path through
                    // exactly the same pool, avoiding a different thread implementation as a confounder.
                    final RegionWorkerExecutor individual = pool::execute;
                    for (int round = 0; round < BATCHED.length; ++round) {
                        final boolean batched = BATCHED[round];
                        final RegionWorkerExecutor executor = batched ? pool : individual;
                        // Separate task-owned writes by 128 bytes, so round-robin assignment does not
                        // manufacture cache-line contention absent from the range-assigned control.
                        final int[] runs = new int[count * 32];
                        final long[] values = new long[count * 16];
                        final RegionTaskBatch batch = new RegionTaskBatch();
                        for (int index = 0; index < count; ++index) {
                            final int task = index;
                            final int steps = skewed && task < count / WORKERS ? 2048 : 0;
                            batch.add("dispatcher probe", () -> {
                                long value = values[task * 16] + task + 1;
                                for (int step = 0; step < steps; ++step) {
                                    value ^= value << 13;
                                    value ^= value >>> 7;
                                    value ^= value << 17;
                                }
                                values[task * 16] = value;
                                ++runs[task * 32];
                            });
                        }
                        for (int tick = 0; tick < WARMUP; ++tick) run(batch, executor);
                        final long beforeSubmissions = counter(pool, "submissions");
                        final long beforeSubmitNanos = counter(pool, "submitNanos");
                        final long beforeSteals = counter(pool, "batchSteals");
                        final long thread = Thread.currentThread().threadId();
                        final long[] times = new long[MEASURED];
                        final long beforeBytes = allocations.getThreadAllocatedBytes(thread);
                        for (int tick = 0; tick < MEASURED; ++tick) {
                            final long start = System.nanoTime();
                            run(batch, executor);
                            times[tick] = System.nanoTime() - start;
                        }
                        final long bytes = allocations.getThreadAllocatedBytes(thread) - beforeBytes;
                        final long submissions = counter(pool, "submissions") - beforeSubmissions;
                        final long submitNanos = counter(pool, "submitNanos") - beforeSubmitNanos;
                        final long steals = counter(pool, "batchSteals") - beforeSteals;
                        for (int task = 0; task < count; ++task) assertEquals(WARMUP + MEASURED, runs[task * 32]);
                        assertEquals((long) MEASURED * (batched ? WORKERS : count), submissions);
                        sink = Arrays.stream(values).sum();
                        Arrays.sort(times);
                        System.out.printf(Locale.ROOT,
                                "REGION_DISPATCH_BENCH tasks=%d skewed=%s round=%d batched=%s meanUs=%.6f p50Us=%.6f p95Us=%.6f submitMeanUs=%.6f submitterBytesPerBatch=%d submissions=%d rangeSteals=%d%n",
                                count, skewed, round, batched, Arrays.stream(times).average().orElseThrow() / 1000.0,
                                times[MEASURED / 2] / 1000.0, times[(int) Math.ceil(MEASURED * .95) - 1] / 1000.0,
                                submitNanos / (MEASURED * 1000.0), bytes / MEASURED, submissions, steals);
                    }
                } finally {
                    ((AutoCloseable) pool).close();
                }
            }
        }
    }

    private static void run(RegionTaskBatch batch, RegionWorkerExecutor executor) {
        final var barrier = batch.submit(executor);
        barrier.awaitCompletion();
        assertNull(barrier.failure());
    }

    private static long counter(RegionWorkerExecutor pool, String name) throws Exception {
        final Method snapshot = pool.getClass().getDeclaredMethod("dispatchSnapshot");
        snapshot.setAccessible(true);
        final Object counters = snapshot.invoke(pool);
        final Method accessor = counters.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return (Long) accessor.invoke(counters);
    }
}
