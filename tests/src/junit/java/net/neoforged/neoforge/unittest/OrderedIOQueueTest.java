/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.neoforge.common.util.OrderedIOQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class OrderedIOQueueTest {
    @Test
    void failedWorkDoesNotPreventLaterWritesAndFailureIsReportedAfterTheDrain() {
        final Queue<Runnable> scheduled = new ConcurrentLinkedQueue<>();
        final OrderedIOQueue queue = new OrderedIOQueue(scheduled::add);
        final RuntimeException failure = new RuntimeException("disk write failure");
        final RuntimeException laterFailure = new RuntimeException("another failure");
        final List<Integer> completed = new ArrayList<>();
        queue.submit(() -> {
            throw failure;
        });
        queue.submit(() -> {
            throw failure;
        });
        queue.submit(() -> {
            completed.add(1);
        });
        queue.submit(() -> {
            throw laterFailure;
        });
        queue.submit(() -> {
            completed.add(2);
        });
        while (!scheduled.isEmpty()) scheduled.remove().run();
        assertEquals(List.of(1, 2), completed);
        assertSame(failure, assertThrows(CompletionException.class, queue::await).getCause());
        assertEquals(List.of(laterFailure), List.of(failure.getSuppressed()));
        queue.submit(() -> {
            completed.add(3);
        });
        scheduled.remove().run();
        queue.await();
        assertEquals(List.of(1, 2, 3), completed, "acknowledged failures must not poison the next save cycle");
    }

    @Test
    void concurrentSubmittersRetainEveryWriteAndNeverOverlapBodies() throws Exception {
        try (final var executor = Executors.newFixedThreadPool(4); final var submitters = Executors.newFixedThreadPool(8)) {
            final OrderedIOQueue queue = new OrderedIOQueue(executor);
            final AtomicInteger active = new AtomicInteger();
            final AtomicInteger overlap = new AtomicInteger();
            final AtomicInteger writes = new AtomicInteger();
            final CountDownLatch start = new CountDownLatch(1);
            final List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                jobs.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    for (int i = 0; i < 500; i++) queue.submit(() -> {
                        if (active.incrementAndGet() != 1) overlap.incrementAndGet();
                        Thread.yield();
                        writes.incrementAndGet();
                        active.decrementAndGet();
                    });
                }, submitters));
            }
            start.countDown();
            for (final var job : jobs) job.get(15, TimeUnit.SECONDS);
            queue.await();
            assertEquals(4000, writes.get());
            assertEquals(0, overlap.get());
        }
    }

    @Test
    void waiterCannotEraseWorkAppendedWhileItWasWaiting() throws Exception {
        final Queue<Runnable> scheduled = new ConcurrentLinkedQueue<>();
        final OrderedIOQueue queue = new OrderedIOQueue(scheduled::add);
        final List<Integer> writes = new ArrayList<>();
        queue.submit(() -> {
            writes.add(1);
        });
        final var waiter = new Thread(queue::await, "saved data prefix wait");
        waiter.start();
        try {
            // Join waits on this exact future. Observing its dependent completion wait avoids a timing sleep.
            final Field tail = OrderedIOQueue.class.getDeclaredField("tail");
            tail.setAccessible(true);
            final var prefix = (CompletableFuture<?>) tail.get(queue);
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (prefix.getNumberOfDependents() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(prefix.getNumberOfDependents() > 0);
            queue.submit(() -> {
                writes.add(2);
            });
            scheduled.remove().run();
            waiter.join(5000);
            assertFalse(waiter.isAlive());
            queue.submit(() -> {
                writes.add(3);
            });
            assertEquals(1, scheduled.size(), "the third write must still be chained behind the second");
            scheduled.remove().run();
            scheduled.remove().run();
            queue.await();
            assertEquals(List.of(1, 2, 3), writes);
        } finally {
            while (!scheduled.isEmpty()) scheduled.remove().run();
            waiter.join(5000);
        }
    }

    @Test
    void executorRejectionIsReportedButDoesNotStrandASuccessor() {
        final AtomicInteger submissions = new AtomicInteger();
        final OrderedIOQueue queue = new OrderedIOQueue(task -> {
            if (submissions.getAndIncrement() == 0) throw new java.util.concurrent.RejectedExecutionException("injected rejection");
            task.run();
        });
        final AtomicInteger writes = new AtomicInteger();
        queue.submit(() -> {
            fail("rejected task must not execute");
        });
        queue.submit(() -> {
            writes.incrementAndGet();
        });
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                assertThrows(CompletionException.class, queue::await).getCause());
        assertEquals(1, writes.get());
    }

    @Test
    void taskCannotDeadlockByWaitingForItsOwnQueue() {
        final OrderedIOQueue queue = new OrderedIOQueue(Runnable::run);
        queue.submit(queue::await);
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, queue::await).getCause());
    }

    @Test
    void nestedSubmissionRunsAfterItsSubmittingBodyAndOutsideTheQueueMonitor() {
        final Queue<Runnable> scheduled = new ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicReference<OrderedIOQueue> reference = new java.util.concurrent.atomic.AtomicReference<>();
        final OrderedIOQueue queue = new OrderedIOQueue(task -> {
            assertFalse(Thread.holdsLock(reference.get()), "executors may call back into the queue and must run outside its monitor");
            scheduled.add(task);
        });
        reference.set(queue);
        final List<String> order = new ArrayList<>();
        queue.submit(() -> {
            assertFalse(Thread.holdsLock(queue));
            order.add("begin");
            queue.submit(() -> {
                order.add("child");
            });
            order.add("end");
        });
        while (!scheduled.isEmpty()) scheduled.remove().run();
        queue.await();
        assertEquals(List.of("begin", "end", "child"), order);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
