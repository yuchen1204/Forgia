/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.neoforged.neoforge.server.threading.region.RegionSpawnAdmissionBarrier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class RegionSpawnAdmissionBarrierTest {
    @Test
    void cancelledFirstCandidateIsResolvedBeforeTheSecondChecksTheFinalSlot() throws Exception {
        try (var harness = new Harness(2)) {
            final var enrolled = new CountDownLatch(2);
            final var first = new Submission<Boolean>();
            final var second = new Submission<Boolean>();
            final var joins = new AtomicInteger();
            final var admitted = new AtomicInteger();
            final Future<Boolean> a = harness.submit(enrolled, first, () -> {
                assertEquals(0, admitted.get());
                joins.incrementAndGet();
                return false; // The real join was cancelled. No provisional slot survives this transaction.
            });
            final Future<Boolean> b = harness.submit(enrolled, second, () -> {
                assertEquals(1, joins.get(), "first transaction is completely resolved, not just queued");
                if (admitted.get() >= 1) return false;
                joins.incrementAndGet();
                admitted.incrementAndGet();
                return true;
            });
            await(enrolled);
            first.releaseAndAwaitQueued();
            second.releaseAndAwaitQueued();
            assertTrue(harness.barrier.pump());
            assertFalse(a.get(5, TimeUnit.SECONDS));
            assertTrue(b.get(5, TimeUnit.SECONDS));
            assertEquals(1, admitted.get());
            assertEquals(2, joins.get(), "neither transaction was replayed");
        }
    }

    @Test
    void mainThreadCanServiceOrdinaryWorkBeforeAllWorkersReachSafePoints() throws Exception {
        try (var harness = new Harness(2)) {
            final var enrolled = new CountDownLatch(2);
            final var first = new Submission<Integer>();
            final var ordinaryMainResult = new CountDownLatch(1);
            final Future<Integer> transaction = harness.submit(enrolled, first, () -> 42);
            final Future<?> peer = harness.executor.submit(() -> {
                try (var worker = harness.barrier.enter()) {
                    enrolled.countDown();
                    await(ordinaryMainResult); // Models a regular batch main-thread query needed before polling.
                    worker.checkpoint();
                }
            });
            await(enrolled);
            first.releaseAndAwaitQueued();
            assertFalse(harness.barrier.pump(), "pump must not block while another worker needs main-thread work");
            ordinaryMainResult.countDown();
            harness.pumpUntil(() -> transaction.isDone() && peer.isDone());
            assertEquals(42, transaction.get(5, TimeUnit.SECONDS));
            peer.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void noWorkerResumesWhileAnotherTransactionStillUsesWorldStorage() throws Exception {
        try (var harness = new Harness(2)) {
            final var enrolled = new CountDownLatch(2);
            final var first = new Submission<Integer>();
            final var second = new Submission<Integer>();
            final var resumed = new AtomicBoolean();
            final Future<?> a = harness.executor.submit(() -> {
                try (var worker = harness.barrier.enter()) {
                    enrolled.countDown();
                    first.beforeCall();
                    worker.call(() -> 1);
                    resumed.set(true);
                }
            });
            final Future<Integer> b = harness.submit(enrolled, second, () -> {
                assertFalse(resumed.get());
                assertFalse(a.isDone(), "first worker must remain parked during the second finalizer");
                return 2;
            });
            await(enrolled);
            first.releaseAndAwaitQueued();
            second.releaseAndAwaitQueued();
            assertTrue(harness.barrier.pump());
            a.get(5, TimeUnit.SECONDS);
            assertEquals(2, b.get(5, TimeUnit.SECONDS));
            assertTrue(resumed.get());
        }
    }

    @Test
    void operationFailureIsReturnedOnceAndDoesNotSkipIndependentTransactions() throws Exception {
        try (var harness = new Harness(2)) {
            final var enrolled = new CountDownLatch(2);
            final var first = new Submission<Integer>();
            final var second = new Submission<Integer>();
            final var calls = new AtomicInteger();
            final var sentinel = new IllegalArgumentException("finalizer failure after a side effect");
            final Future<Integer> a = harness.submit(enrolled, first, () -> {
                calls.incrementAndGet();
                throw sentinel;
            });
            final Future<Integer> b = harness.submit(enrolled, second, calls::incrementAndGet);
            await(enrolled);
            first.releaseAndAwaitQueued();
            second.releaseAndAwaitQueued();
            assertTrue(harness.barrier.pump());
            assertSame(sentinel, assertThrows(ExecutionException.class, () -> a.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(2, b.get(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertFalse(harness.barrier.pump());
        }
    }

    @Test
    void interruptionCannotResumeTheWorkerDuringItsFinalizer() throws Exception {
        try (var harness = new Harness(1)) {
            final var enrolled = new CountDownLatch(1);
            final var submission = new Submission<Integer>();
            final var resumed = new AtomicBoolean();
            final Future<Boolean> result = harness.executor.submit(() -> {
                try (var worker = harness.barrier.enter()) {
                    enrolled.countDown();
                    submission.beforeCall();
                    worker.call(() -> {
                        submission.thread.get().interrupt();
                        waitUntil(() -> submission.thread.get().getState() == Thread.State.WAITING);
                        assertFalse(resumed.get());
                        return 42;
                    });
                    resumed.set(true);
                    return Thread.currentThread().isInterrupted();
                }
            });
            await(enrolled);
            submission.releaseAndAwaitQueued();
            assertTrue(harness.barrier.pump());
            assertTrue(result.get(5, TimeUnit.SECONDS), "interrupt status is restored only after the phase ends");
            assertTrue(resumed.get());
        }
    }

    @Test
    void closeCancelsUnstartedTransactionsWithoutRunningThem() throws Exception {
        try (var harness = new Harness(1)) {
            final var enrolled = new CountDownLatch(1);
            final var submission = new Submission<Integer>();
            final var calls = new AtomicInteger();
            final Future<Integer> result = harness.submit(enrolled, submission, calls::incrementAndGet);
            await(enrolled);
            submission.releaseAndAwaitQueued();
            harness.barrier.close();
            assertTrue(assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause() instanceof IllegalStateException);
            assertEquals(0, calls.get());
            assertFalse(harness.barrier.pump());
        }
    }

    @Test
    void closeDuringARealTransactionDoesNotRelabelItAsAnUnattemptedCancellation() throws Exception {
        try (var harness = new Harness(2)) {
            final var enrolled = new CountDownLatch(2);
            final var first = new Submission<Integer>();
            final var second = new Submission<Integer>();
            final var calls = new AtomicInteger();
            final Future<Integer> a = harness.submit(enrolled, first, () -> {
                calls.incrementAndGet();
                harness.barrier.close();
                return 42; // An actual admission can already have happened when shutdown is requested.
            });
            final Future<Integer> b = harness.submit(enrolled, second, calls::incrementAndGet);
            await(enrolled);
            first.releaseAndAwaitQueued();
            second.releaseAndAwaitQueued();
            assertTrue(harness.barrier.pump());
            assertEquals(42, a.get(5, TimeUnit.SECONDS));
            assertTrue(assertThrows(ExecutionException.class, () -> b.get(5, TimeUnit.SECONDS)).getCause() instanceof IllegalStateException);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void newGroupCannotStartWorldAccessDuringAServerTransaction() throws Exception {
        try (var harness = new Harness(2)) {
            final var enrolled = new CountDownLatch(1);
            final var first = new Submission<Integer>();
            final var entering = new AtomicReference<Thread>();
            final var entered = new AtomicBoolean();
            final var newcomer = new AtomicReference<Future<?>>();
            final Future<Integer> result = harness.submit(enrolled, first, () -> {
                newcomer.set(harness.executor.submit(() -> {
                    entering.set(Thread.currentThread());
                    try (var worker = harness.barrier.enter()) {
                        entered.set(true);
                    }
                }));
                waitUntil(() -> entering.get() != null && entering.get().getState() == Thread.State.WAITING);
                assertFalse(entered.get());
                return 42;
            });
            await(enrolled);
            first.releaseAndAwaitQueued();
            assertTrue(harness.barrier.pump());
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            newcomer.get().get(5, TimeUnit.SECONDS);
            assertTrue(entered.get());
        }
    }

    @Test
    void retirementCountsActualRunningGroupsRatherThanAllQueuedGroups() throws Exception {
        try (var harness = new Harness(2)) {
            final var enrolled = new CountDownLatch(2);
            final var submission = new Submission<Integer>();
            final var retire = new CountDownLatch(1);
            final Future<Integer> result = harness.submit(enrolled, submission, () -> 42);
            final Future<?> peer = harness.executor.submit(() -> {
                try (var worker = harness.barrier.enter()) {
                    enrolled.countDown();
                    await(retire);
                }
            });
            await(enrolled);
            submission.releaseAndAwaitQueued();
            assertFalse(harness.barrier.pump());
            retire.countDown();
            peer.get(5, TimeUnit.SECONDS);
            assertTrue(harness.barrier.pump(), "finished groups must not remain barrier parties");
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectedWorkerGroupCanRunInlineWhileOrdinaryHandoffsStillMakeProgress() throws Exception {
        try (var harness = new Harness(1)) {
            final var entered = new CountDownLatch(1);
            final var ordinaryResult = new CountDownLatch(1);
            final var readingWorld = new AtomicBoolean();
            final Future<?> worker = harness.executor.submit(() -> {
                try (var token = harness.barrier.enter()) {
                    readingWorld.set(true);
                    entered.countDown();
                    await(ordinaryResult);
                    readingWorld.set(false);
                    token.checkpoint();
                }
            });
            await(entered);
            final Thread server = Thread.currentThread();
            assertEquals(42, harness.barrier.callInline(() -> {
                assertSame(server, Thread.currentThread());
                assertFalse(readingWorld.get());
                return 42;
            }, ordinaryResult::countDown));
            worker.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void nestedInlineFinalizerPreservesNativeParentChildVisibility() {
        try (var barrier = new RegionSpawnAdmissionBarrier(1)) {
            final var admitted = new AtomicInteger();
            final Thread server = Thread.currentThread();
            assertEquals(2, barrier.callInline(() -> {
                assertEquals(0, admitted.get(), "parent is still in finalization");
                final int nested = barrier.callInline(() -> {
                    assertSame(server, Thread.currentThread());
                    assertEquals(0, admitted.get(), "unadmitted parent must not be counted as an existing entity");
                    return admitted.incrementAndGet();
                }, () -> {
                    throw new AssertionError("nested finalization must not wait on its own parked phase");
                });
                assertEquals(1, nested);
                return admitted.incrementAndGet();
            }, () -> {}));
        }
    }

    @Test
    void inlineFallbackRunsAfterAlreadyQueuedCandidateTransactions() throws Exception {
        try (var harness = new Harness(1)) {
            final var enrolled = new CountDownLatch(1);
            final var submission = new Submission<Integer>();
            final var completed = new AtomicInteger();
            final Future<Integer> first = harness.submit(enrolled, submission, completed::incrementAndGet);
            await(enrolled);
            submission.releaseAndAwaitQueued();
            assertEquals(2, harness.barrier.callInline(() -> {
                assertEquals(1, completed.get());
                return completed.incrementAndGet();
            }, () -> {}));
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(2, completed.get());
        }
    }

    @Test
    void inlineWaitFailureClosesQueuedRequestsBeforeTheCallerJoinsItsWorkers() throws Exception {
        try (var harness = new Harness(1)) {
            final var enrolled = new CountDownLatch(1);
            final var submission = new Submission<Integer>();
            final var calls = new AtomicInteger();
            final Future<Integer> queued = harness.submit(enrolled, submission, calls::incrementAndGet);
            await(enrolled);
            submission.releaseAndAwaitQueued();
            final var sentinel = new IllegalArgumentException("server wait failed");
            assertSame(sentinel, assertThrows(IllegalArgumentException.class, () -> harness.barrier.callInline(
                    calls::incrementAndGet, () -> {
                        throw sentinel;
                    })));
            assertThrows(ExecutionException.class, () -> queued.get(5, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void anInlineWaitCannotRecursivelyEnqueueAnotherWaitOnItsOwnUnparkedPhase() {
        try (var barrier = new RegionSpawnAdmissionBarrier(1)) {
            assertEquals(42, barrier.callInline(() -> 42, () -> assertThrows(IllegalStateException.class,
                    () -> barrier.callInline(() -> 0, () -> {}))));
            assertEquals(43, barrier.callInline(() -> 43, () -> {}), "inline wait ownership is released after return");
        }
    }

    @Test
    void fourWorkersAndManyQueuedGroupsMakeProgressAcrossRepeatedSafePoints() throws Exception {
        try (var harness = new Harness(4)) {
            final var transactions = new AtomicInteger();
            final var completed = new AtomicInteger();
            final var futures = new java.util.ArrayList<Future<?>>();
            for (int group = 0; group < 24; group++) {
                futures.add(harness.executor.submit(() -> {
                    try (var worker = harness.barrier.enter()) {
                        for (int attempt = 0; attempt < 30; attempt++) {
                            worker.checkpoint();
                            if (attempt % 3 == 0) {
                                worker.call(() -> {
                                    transactions.incrementAndGet();
                                    return null;
                                });
                            } else {
                                // Models the other groups' candidate/chunk selection between safe points.
                                Thread.yield();
                            }
                        }
                    }
                    completed.incrementAndGet();
                }));
            }
            harness.pumpUntil(() -> completed.get() == 24);
            for (final var future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(240, transactions.get(), "no missed or replayed native transactions across queued groups");
        }
    }

    @Test
    void wrongThreadReentryAndDuplicateEnrollmentFailInsteadOfWaitingForThemselves() throws Exception {
        try (var harness = new Harness(1)) {
            assertThrows(IllegalStateException.class, harness.barrier::enter);
            harness.executor.submit(() -> {
                try (var worker = harness.barrier.enter()) {
                    assertThrows(IllegalStateException.class, harness.barrier::enter);
                    assertThrows(IllegalStateException.class, harness.barrier::pump);
                }
            }).get(5, TimeUnit.SECONDS);
            final var enrolled = new CountDownLatch(1);
            final var submission = new Submission<Integer>();
            final Future<Integer> result = harness.submit(enrolled, submission, () -> {
                assertThrows(IllegalStateException.class, harness.barrier::pump);
                return 42;
            });
            await(enrolled);
            submission.releaseAndAwaitQueued();
            assertTrue(harness.barrier.pump());
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "worker did not reach the expected test phase");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void waitUntil(BooleanSupplier condition) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for a cooperative safe point");
            LockSupport.parkNanos(100_000L);
        }
    }

    private static final class Submission<T> {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch submitting = new CountDownLatch(1);
        private final AtomicReference<Thread> thread = new AtomicReference<>();

        private void beforeCall() {
            await(this.release);
            this.thread.set(Thread.currentThread());
            this.submitting.countDown();
        }

        private void releaseAndAwaitQueued() {
            this.release.countDown();
            await(this.submitting);
            waitUntil(() -> this.thread.get().getState() == Thread.State.WAITING);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final ExecutorService executor;
        private final RegionSpawnAdmissionBarrier barrier;

        private Harness(int threads) {
            this.executor = Executors.newFixedThreadPool(threads);
            this.barrier = new RegionSpawnAdmissionBarrier(threads);
        }

        private <T> Future<T> submit(CountDownLatch enrolled, Submission<T> submission, Supplier<T> operation) {
            return this.executor.submit(() -> {
                try (var worker = this.barrier.enter()) {
                    enrolled.countDown();
                    submission.beforeCall();
                    return worker.call(operation);
                }
            });
        }

        private void pumpUntil(BooleanSupplier complete) {
            waitUntil(() -> {
                this.barrier.pump();
                return complete.getAsBoolean();
            });
        }

        @Override
        public void close() throws InterruptedException {
            this.barrier.close();
            this.executor.shutdownNow();
            assertTrue(this.executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
