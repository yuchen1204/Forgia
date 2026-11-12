/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.neoforge.server.threading.region.RegionTaskBatch;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import net.neoforged.neoforge.server.threading.region.RegionWorkerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class RegionWorkStealingDispatcherTest {
    private final List<AutoCloseable> pools = new ArrayList<>();

    @AfterEach
    void closePools() throws Exception {
        for (final AutoCloseable pool : this.pools) pool.close();
    }

    @Test
    void aLargeBatchSubmitsOnlyOneRunnerPerWorker() throws Exception {
        final RegionWorkerExecutor pool = this.pool(4, true);
        final AtomicIntegerArray runs = new AtomicIntegerArray(1024);
        final RegionTaskBatch batch = batch(runs, 0);
        final var barrier = batch.submit(pool);
        barrier.awaitCompletion();

        assertNull(barrier.failure());
        assertEquals(0, barrier.rejectedCount());
        assertEquals(1024, barrier.groupCount());
        assertEveryTaskRan(runs, 1);
        assertEquals(4, counter(pool, "submissions"));
        assertEquals(1, counter(pool, "dispatchBatches"));
        assertEquals(1024, counter(pool, "batchedTasks"));
        assertEquals(4, counter(pool, "batchRunners"));
    }

    @Test
    void anIdleRunnerStealsTheUnstartedTailOfABlockedRunner() throws Exception {
        final RegionWorkerExecutor pool = this.pool(2, true);
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch others = new CountDownLatch(7);
        final AtomicIntegerArray runs = new AtomicIntegerArray(8);
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("slow region", () -> {
            blocked.countDown();
            await(release);
            runs.incrementAndGet(0);
        });
        for (int index = 1; index < 8; ++index) {
            final int task = index;
            batch.add("region " + index, () -> {
                runs.incrementAndGet(task);
                others.countDown();
            });
        }
        final var barrier = batch.submit(pool);
        try {
            await(blocked);
            assertTrue(others.await(10, TimeUnit.SECONDS), "work behind the slow region was pinned to its runner");
            assertFalse(barrier.isComplete(), "the started body must remain part of the barrier");
            assertTrue(counter(pool, "batchSteals") > 0);
            assertTrue(counter(pool, "batchStolenTasks") >= 3);
        } finally {
            release.countDown();
        }
        barrier.awaitCompletion();
        assertNull(barrier.failure());
        assertEveryTaskRan(runs, 1);
    }

    @Test
    void repeatedImbalancedBatchesNeverShareOrReplayAGroup() throws Exception {
        final RegionWorkerExecutor pool = this.pool(4, true);
        final AtomicIntegerArray runs = new AtomicIntegerArray(257);
        final AtomicIntegerArray active = new AtomicIntegerArray(257);
        final AtomicBoolean overlap = new AtomicBoolean();
        for (int round = 0; round < 100; ++round) {
            final RegionTaskBatch batch = new RegionTaskBatch();
            final int iteration = round;
            for (int index = 0; index < runs.length(); ++index) {
                final int task = index;
                batch.add("region " + task, () -> {
                    if (active.incrementAndGet(task) != 1) overlap.set(true);
                    for (int spin = 0; spin < (task * 17 + iteration * 31) % 128; ++spin) Thread.onSpinWait();
                    runs.incrementAndGet(task);
                    active.decrementAndGet(task);
                });
            }
            final var barrier = batch.submit(pool);
            barrier.awaitCompletion();
            assertNull(barrier.failure());
        }
        assertFalse(overlap.get(), "two runners held the same logical group");
        assertEveryTaskRan(runs, 100);
        assertEquals(400, counter(pool, "submissions"));
    }

    @Test
    void packedTasksRemainIndivisibleWhileOtherPacksCanBeStolen() throws Exception {
        final RegionWorkerExecutor pool = this.pool(4, true);
        final AtomicIntegerArray runs = new AtomicIntegerArray(64);
        final RegionTaskBatch batch = new RegionTaskBatch();
        for (int pack = 0; pack < 16; ++pack) {
            final AtomicReference<Thread> owner = new AtomicReference<>();
            final List<Runnable> works = new ArrayList<>();
            for (int member = 0; member < 4; ++member) {
                final int task = pack * 4 + member;
                works.add(() -> {
                    owner.compareAndSet(null, Thread.currentThread());
                    assertSame(owner.get(), Thread.currentThread(), "a packed lease moved between workers");
                    assertTrue(RegionWorkerScope.isNativeWorkerThread());
                    runs.incrementAndGet(task);
                });
            }
            batch.addPacked("pack " + pack, works);
        }
        final var barrier = batch.submit(pool);
        barrier.awaitCompletion();
        assertNull(barrier.failure());
        assertEquals(64, barrier.groupCount());
        assertEveryTaskRan(runs, 1);
        assertEquals(16, counter(pool, "batchedTasks"));
        assertEquals(4, counter(pool, "submissions"));
    }

    @Test
    void aFailureAfterEffectsStillDrainsEveryTaskExactlyOnce() throws Exception {
        final RegionWorkerExecutor pool = this.pool(2, true);
        final AtomicIntegerArray runs = new AtomicIntegerArray(64);
        final IllegalStateException failure = new IllegalStateException("after effect");
        final RegionTaskBatch batch = new RegionTaskBatch();
        for (int index = 0; index < runs.length(); ++index) {
            final int task = index;
            batch.add("region " + task, () -> {
                runs.incrementAndGet(task);
                if (task == 0) throw failure;
            });
        }
        final var barrier = batch.submit(pool);
        barrier.awaitCompletion();
        assertSame(failure, assertThrows(IllegalStateException.class, barrier::throwIfFailed));
        assertEveryTaskRan(runs, 1);
        final CountDownLatch survived = new CountDownLatch(1);
        assertTrue(pool.execute("after failure", survived::countDown));
        await(survived);
    }

    @Test
    void anOpenBarrierKeepsItsSetupPermitAfterRunnersFinish() throws Exception {
        final RegionWorkerExecutor pool = this.pool(4, true);
        final AtomicIntegerArray runs = new AtomicIntegerArray(64);
        final CountDownLatch finished = new CountDownLatch(64);
        final RegionTaskBatch batch = new RegionTaskBatch();
        for (int index = 0; index < runs.length(); ++index) {
            final int task = index;
            batch.add("region " + index, () -> {
                runs.incrementAndGet(task);
                finished.countDown();
            });
        }
        final var barrier = RegionTaskBatch.Barrier.open();
        batch.submitOpen(pool, barrier);
        await(finished);
        assertFalse(barrier.isComplete());
        final Method complete = RegionTaskBatch.Barrier.class.getDeclaredMethod("setupComplete");
        complete.setAccessible(true);
        complete.invoke(barrier);
        barrier.awaitCompletion();
        assertEveryTaskRan(runs, 1);
        assertNull(barrier.failure());
    }

    @Test
    void aClosedPoolDrainsUnstartedTasksInlineWithoutWorkerGrants() throws Exception {
        final RegionWorkerExecutor pool = this.pool(4, true);
        ((AutoCloseable) pool).close();
        final Thread submitter = Thread.currentThread();
        final AtomicInteger runs = new AtomicInteger();
        final RegionTaskBatch batch = new RegionTaskBatch();
        for (int index = 0; index < 64; ++index) {
            batch.add("refused region " + index, () -> {
                assertSame(submitter, Thread.currentThread());
                assertFalse(RegionWorkerScope.isNativeWorkerThread());
                runs.incrementAndGet();
            });
        }
        final var barrier = batch.submit(pool);
        barrier.awaitCompletion();
        assertEquals(64, runs.get());
        assertEquals(4, barrier.rejectedCount(), "rejections count runner submission attempts");
        assertEquals(0, counter(pool, "submissions"));
        assertNull(barrier.failure());
    }

    @Test
    void oneWorkerAmortizesSubmissionsWithoutStealing() throws Exception {
        final RegionWorkerExecutor pool = this.pool(1, true);
        final AtomicIntegerArray runs = new AtomicIntegerArray(64);
        final var barrier = batch(runs, 0).submit(pool);
        barrier.awaitCompletion();
        assertNull(barrier.failure());
        assertEveryTaskRan(runs, 1);
        assertEquals(1, counter(pool, "submissions"));
        assertEquals(0, counter(pool, "batchSteals"));
    }

    @Test
    void theFixedExecutorAndCustomExecutorKeepIndividualSubmissions() throws Exception {
        for (final boolean persistent : new boolean[] { false, true }) {
            final RegionWorkerExecutor pool = this.pool(4, persistent);
            final RegionWorkerExecutor executor = persistent ? pool::execute : pool;
            final AtomicIntegerArray runs = new AtomicIntegerArray(64);
            final var barrier = batch(runs, 0).submit(executor);
            barrier.awaitCompletion();
            assertNull(barrier.failure());
            assertEveryTaskRan(runs, 1);
            assertEquals(64, counter(pool, "submissions"));
            assertEquals(0, counter(pool, "dispatchBatches"));
        }
    }

    @Test
    void concurrentSubmissionsRacingShutdownNeverLoseOrRepeatWork() throws Exception {
        final RegionWorkerExecutor pool = this.pool(4, true);
        final int producers = 4;
        final int rounds = 10;
        final int tasks = 64;
        final AtomicIntegerArray runs = new AtomicIntegerArray(producers * rounds * tasks);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch firstWork = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(producers + 1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int producer = 0; producer < producers; ++producer) {
            final int id = producer;
            new Thread(() -> {
                try {
                    await(start);
                    for (int round = 0; round < rounds; ++round) {
                        final int base = (id * rounds + round) * tasks;
                        final RegionTaskBatch batch = new RegionTaskBatch();
                        for (int task = 0; task < tasks; ++task) {
                            final int index = base + task;
                            batch.add("racing task", () -> {
                                runs.incrementAndGet(index);
                                firstWork.countDown();
                            });
                        }
                        final var barrier = batch.submit(pool);
                        barrier.awaitCompletion();
                        barrier.throwIfFailed();
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    done.countDown();
                }
            }, "dispatcher-producer-" + producer).start();
        }
        new Thread(() -> {
            try {
                await(firstWork);
                ((AutoCloseable) pool).close();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                done.countDown();
            }
        }, "dispatcher-close-race").start();
        start.countDown();
        await(done);
        assertNull(failure.get());
        assertEveryTaskRan(runs, 1);
    }

    private RegionWorkerExecutor pool(int threads, boolean persistent) throws Exception {
        final Class<?> type = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool");
        final Constructor<?> constructor = type.getDeclaredConstructor(int.class, boolean.class);
        constructor.setAccessible(true);
        final Object pool = constructor.newInstance(threads, persistent);
        this.pools.add((AutoCloseable) pool);
        return (RegionWorkerExecutor) pool;
    }

    private static long counter(RegionWorkerExecutor pool, String name) throws Exception {
        final Method snapshot = pool.getClass().getDeclaredMethod("dispatchSnapshot");
        snapshot.setAccessible(true);
        final Object counters = snapshot.invoke(pool);
        final Method accessor = counters.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return (Long) accessor.invoke(counters);
    }

    private static RegionTaskBatch batch(AtomicIntegerArray runs, int base) {
        final RegionTaskBatch batch = new RegionTaskBatch();
        for (int index = base; index < runs.length(); ++index) {
            final int task = index;
            batch.add("region " + task, () -> runs.incrementAndGet(task));
        }
        return batch;
    }

    private static void assertEveryTaskRan(AtomicIntegerArray runs, int count) {
        for (int index = 0; index < runs.length(); ++index) assertEquals(count, runs.get(index), "task " + index);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("dispatcher test timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
