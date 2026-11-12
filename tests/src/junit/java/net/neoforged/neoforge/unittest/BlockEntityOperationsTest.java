/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Container;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations.Reason;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations.Reference;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations.State;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations.Task;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations.Transfer;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations.TransferStatus;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(40)
class BlockEntityOperationsTest {
    private static final BlockPos SOURCE = new BlockPos(17, 64, 17);
    private static final BlockPos TARGET = new BlockPos(65, 64, 17);
    private static final RegionTickBarrier BARRIER = done -> {
        while (!done.getAsBoolean()) LockSupport.parkNanos(100_000L);
    };
    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final Thread serverThread = Thread.currentThread();
    private MinecraftServer server;
    private ServerLevel level;
    private BlockEntityOperations operations;
    private HopperBlockEntity source;
    private ChestBlockEntity target;
    private Reference sourceRef;
    private Reference targetRef;
    private RegionThreadingEngine engine;

    @BeforeEach
    void setup() {
        this.server = mock(MinecraftServer.class);
        when(this.server.isSameThread()).thenAnswer(call -> Thread.currentThread() == this.serverThread);
        this.level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(this.server).when(this.level).getServer();
        doReturn(Level.OVERWORLD).when(this.level).dimension();
        doReturn(InactiveProfiler.INSTANCE).when(this.level).getProfiler();
        doReturn("operations-test").when(this.level).toString();
        doReturn(1L).when(this.level).getSeed();
        doReturn(0L).when(this.level).getGameTime();
        doReturn(3).when(this.level).entitySectionRegionChunkSize();
        doReturn(new net.minecraft.world.level.border.WorldBorder()).when(this.level).getWorldBorder();
        doReturn(true).when(this.level).areEntitiesLoaded(anyLong());
        org.mockito.Mockito.doNothing().when(this.level).invalidateCapabilities(any(BlockPos.class));
        doReturn(true).when(this.level).shouldTickBlocksAt(any(BlockPos.class));
        final TickRateManager rate = mock(TickRateManager.class);
        when(rate.runsNormally()).thenReturn(true);
        doReturn(rate).when(this.level).tickRateManager();
        final ServerChunkCache source = mock(ServerChunkCache.class);
        doAnswer(call -> this.chunks.get(ChunkPos.asLong(call.getArgument(0), call.getArgument(1))))
                .when(source).getChunkNow(anyInt(), anyInt());
        doAnswer(call -> {
            final BooleanSupplier done = call.getArgument(0);
            BARRIER.await(done);
            return null;
        }).when(source).managedBlockOnMainThread(any());
        doReturn(source).when(this.level).getChunkSource();
        this.source = new HopperBlockEntity(SOURCE, Blocks.HOPPER.defaultBlockState());
        this.target = new ChestBlockEntity(TARGET, Blocks.CHEST.defaultBlockState());
        this.attach(this.source);
        this.attach(this.target);
        ParallelAdapterRuntime.initialize(this.server, registrar -> {});
        final ParallelApi.Server api = ParallelApi.forServer(this.server).orElseThrow();
        assertTrue(api.capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_OPERATIONS));
        this.operations = api.blockEntities();
        this.sourceRef = this.operations.reference(this.source);
        this.targetRef = this.operations.reference(this.target);
    }

    @AfterEach
    void close() {
        ParallelAdapterRuntime.close(this.server);
        RegionThreadingManager.clear();
    }

    @Test
    void serialModeWorksWithNoRegionEngineAndReturnsDetachedTransferResult() {
        this.source.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        this.target.setItem(0, new ItemStack(Items.IRON_INGOT, 62));
        final var task = this.operations.transfer(this.sourceRef, this.targetRef, new Transfer(0, 0, 8, null, null));
        assertEquals(State.COMPLETED, task.state());
        assertEquals(2, task.result().orElseThrow().moved());
        assertEquals(TransferStatus.MOVED, task.result().orElseThrow().status());
        assertEquals(8, this.source.getItem(0).getCount());
        assertEquals(64, this.target.getItem(0).getCount());
        assertEquals(72, this.total());
        assertFalse(task.cancel());
        assertEquals(1, this.operations.counters().completed());
        assertCleared(task);
    }

    @Test
    void workerRequestRunsOnceAtQuiescentCompletionAndNeverBlocksItsTicker() {
        this.install();
        this.source.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        final AtomicReference<Task<Integer>> handle = new AtomicReference<>();
        final List<Thread> callbacks = new CopyOnWriteArrayList<>();
        final TickingBlockEntity ticker = this.ticker(this.source, () -> {
            final Reference local = this.operations.reference(this.source);
            final Task<Integer> task = this.operations.submit(local, this.targetRef, (source, target) -> {
                callbacks.add(Thread.currentThread());
                assertTrue(this.engine.canRunEntityInteractionImmediately(this.level));
                ((Container) target).setItem(0, ((Container) source).removeItem(0, 4));
                return 4;
            });
            handle.set(task);
            assertEquals(State.QUEUED, task.state());
            assertTrue(task.result().isEmpty());
            assertEquals(10, this.source.getItem(0).getCount());
        });
        this.tick(ticker);
        assertEquals(List.of(this.serverThread), callbacks);
        assertEquals(State.COMPLETED, handle.get().state());
        assertEquals(4, handle.get().result().orElseThrow());
        assertEquals(10, this.total());
        assertEquals(6, this.source.getItem(0).getCount());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertCleared(handle.get());
    }

    @Test
    void foreignWorkerMustReturnBeforeTheOperationObservesItsContainer() {
        this.install();
        final HopperBlockEntity peer = new HopperBlockEntity(TARGET.offset(1, 0, 0), Blocks.HOPPER.defaultBlockState());
        this.attach(peer);
        final CountDownLatch submitted = new CountDownLatch(1);
        final AtomicInteger peerDone = new AtomicInteger();
        final Task<?>[] task = new Task<?>[1];
        this.tick(this.ticker(this.source, () -> {
            task[0] = this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> {
                assertEquals(1, peerDone.get());
                return "done";
            });
            submitted.countDown();
        }), this.ticker(peer, () -> {
            await(submitted);
            peerDone.incrementAndGet();
        }));
        assertEquals(State.COMPLETED, task[0].state());
    }

    @Test
    void cancellingQueuedWorkPreventsAllEffectsAndReleasesItsCallback() {
        this.install();
        final AtomicInteger bodies = new AtomicInteger();
        final AtomicReference<Task<?>> handle = new AtomicReference<>();
        this.tick(this.ticker(this.source, () -> {
            final Task<?> task = this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> bodies.incrementAndGet());
            handle.set(task);
            assertTrue(task.cancel());
            assertFalse(task.cancel());
        }));
        assertEquals(0, bodies.get());
        assertEquals(State.CANCELLED, handle.get().state());
        assertEquals(Reason.CANCELLED, handle.get().reason());
        assertEquals(1L, this.operations.counters().cancelled());
        assertCleared(handle.get());
    }

    @Test
    void samePositionReplacementAndUnloadedTargetHaveExplicitOutcomes() {
        this.install();
        final List<Task<?>> tasks = new CopyOnWriteArrayList<>();
        final AtomicInteger callbacks = new AtomicInteger();
        final RegionTickBarrier replaceAtCompletion = done -> {
            BARRIER.await(done);
            final ChestBlockEntity replacement = new ChestBlockEntity(TARGET, Blocks.CHEST.defaultBlockState());
            this.attach(replacement);
        };
        this.tick(replaceAtCompletion, this.ticker(this.source, () -> tasks.add(this.operations.submit(this.sourceRef, this.targetRef,
                (first, second) -> callbacks.incrementAndGet()))));
        assertEquals(State.SKIPPED, tasks.getFirst().state());
        assertEquals(Reason.TARGET_REPLACED, tasks.getFirst().reason());
        this.attach(this.target);
        this.chunks.remove(ChunkPos.asLong(TARGET));
        this.tick(this.ticker(this.source, () -> tasks.add(this.operations.submit(this.sourceRef, this.targetRef,
                (first, second) -> callbacks.incrementAndGet()))));
        assertEquals(Reason.CHUNK_UNAVAILABLE, tasks.getLast().reason());
        assertEquals(0, callbacks.get());
    }

    @Test
    void sourceReplacementRetiresTheWholeOperationInsteadOfUsingTheNewMachine() {
        this.install();
        final AtomicReference<Task<?>> handle = new AtomicReference<>();
        this.tick(done -> {
            BARRIER.await(done);
            this.attach(new HopperBlockEntity(SOURCE, Blocks.HOPPER.defaultBlockState()));
        }, this.ticker(this.source, () -> handle.set(this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> {
            throw new AssertionError("replaced source must not run");
        }))));
        assertEquals(State.SKIPPED, handle.get().state());
        assertEquals(Reason.SOURCE_REPLACED, handle.get().reason());
    }

    @Test
    void failureDoesNotReplayPartialInventoryEffectsOrStrandLaterRequests() {
        this.install();
        this.source.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        final List<Task<?>> handles = new CopyOnWriteArrayList<>();
        final IllegalStateException failure = new IllegalStateException("transfer callback failed");
        final AtomicInteger later = new AtomicInteger();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> this.tick(this.ticker(this.source, () -> {
            handles.add(this.operations.submit(this.sourceRef, this.targetRef, (source, target) -> {
                ((Container) target).setItem(0, ((Container) source).removeItem(0, 1));
                throw failure;
            }));
            handles.add(this.operations.submit(this.sourceRef, this.targetRef, (source, target) -> later.incrementAndGet()));
        }))));
        assertEquals(1, this.target.getItem(0).getCount());
        assertEquals(2, this.source.getItem(0).getCount());
        assertEquals(3, this.total());
        assertEquals(1, later.get());
        assertEquals(State.FAILED, handles.getFirst().state());
        assertSame(failure, handles.getFirst().failure().orElseThrow());
        assertEquals(State.COMPLETED, handles.getLast().state());
        handles.forEach(BlockEntityOperationsTest::assertCleared);
    }

    @Test
    void boundedQueueRejectsExcessWorkAndCancelledRequestsDoNotRefillIt() {
        this.install();
        final List<Task<?>> tasks = new ArrayList<>();
        this.tick(this.ticker(this.source, () -> {
            for (int i = 0; i < 4097; ++i) {
                final Task<?> task = this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> {
                    throw new AssertionError("cancelled or rejected work must not execute");
                });
                tasks.add(task);
                if (i < 4096) assertTrue(task.cancel());
            }
        }));
        assertEquals(State.REJECTED, tasks.getLast().state());
        assertEquals(Reason.QUEUE_FULL, tasks.getLast().reason());
        assertEquals(4096L, this.operations.counters().accepted());
        assertEquals(4096L, this.operations.counters().cancelled());
        assertEquals(1L, this.operations.counters().rejected());
    }

    @Test
    void siblingSourceAndAsyncCallersCannotBorrowTickerAuthority() throws InterruptedException {
        this.install();
        final HopperBlockEntity sibling = new HopperBlockEntity(SOURCE.east(), Blocks.HOPPER.defaultBlockState());
        this.attach(sibling);
        final Reference siblingRef = this.operations.reference(sibling);
        final AtomicReference<Task<?>> handle = new AtomicReference<>();
        this.tick(this.ticker(this.source, () -> {
            assertThrows(IllegalStateException.class, () -> this.operations.reference(sibling));
            handle.set(this.operations.submit(siblingRef, this.targetRef, (first, second) -> true));
        }));
        assertEquals(State.REJECTED, handle.get().state());
        assertEquals(Reason.UNOWNED_SOURCE, handle.get().reason());
        final Thread external = new Thread(() -> handle.set(this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> true)));
        external.start();
        external.join(10_000L);
        assertFalse(external.isAlive());
        assertEquals(Reason.INVALID_CONTEXT, handle.get().reason());
    }

    @Test
    void serviceClosureRetiresQueuedWorkBeforeItsBody() {
        this.install();
        final AtomicReference<Task<?>> handle = new AtomicReference<>();
        this.tick(this.ticker(this.source, () -> {
            handle.set(this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> {
                throw new AssertionError("closed service must not execute queued work");
            }));
            ParallelAdapterRuntime.close(this.server);
        }));
        assertEquals(State.SKIPPED, handle.get().state());
        assertEquals(Reason.SHUTDOWN, handle.get().reason());
        assertEquals(Reason.SHUTDOWN, this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> true).reason());
        assertThrows(IllegalStateException.class, () -> this.operations.reference(this.source));
    }

    @Test
    void closedLevelAndRemovedParticipantsDoNotRun() {
        this.install();
        this.target.setRemoved();
        final Task<?> removed = this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> true);
        assertEquals(State.SKIPPED, removed.state());
        assertEquals(Reason.BLOCK_ENTITY_REMOVED, removed.reason());
        this.engine.clearLevelRegionTasks(this.level);
        final Task<?> closed = this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> true);
        assertEquals(State.REJECTED, closed.state());
        assertEquals(Reason.LEVEL_CLOSED, closed.reason());
    }

    @Test
    void samePositionSameTypeReplacementCannotBorrowTheRunningTickerIdentity() {
        this.install();
        final HopperBlockEntity replacement = new HopperBlockEntity(SOURCE, Blocks.HOPPER.defaultBlockState());
        this.attach(replacement);
        final Reference replacementRef = this.operations.reference(replacement);
        this.attach(this.source);
        final AtomicReference<Task<?>> handle = new AtomicReference<>();
        this.tick(this.ticker(this.source, () -> {
            this.attach(replacement);
            assertThrows(IllegalStateException.class, () -> this.operations.reference(replacement));
            handle.set(this.operations.submit(replacementRef, this.targetRef, (first, second) -> {
                throw new AssertionError("a replacement cannot be the original bound ticker's source");
            }));
        }));
        assertEquals(State.REJECTED, handle.get().state());
        assertEquals(Reason.UNOWNED_SOURCE, handle.get().reason());
    }

    @Test
    void workerScheduledMutationsArePublishedBeforeTheWholeOperationRuns() {
        this.install();
        final AtomicInteger published = new AtomicInteger();
        final AtomicReference<Task<?>> handle = new AtomicReference<>();
        this.tick(this.ticker(this.source, () -> {
            RegionThreadingHooks.regionTickMutationQueue().add(published::incrementAndGet);
            handle.set(this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> {
                assertEquals(1, published.get(), "earlier worker scheduling effects must be visible");
                return true;
            }));
        }));
        assertEquals(State.COMPLETED, handle.get().state());
        assertEquals(1, published.get());
    }

    @Test
    void failingScheduledPublicationStillDrainsAcceptedWholeOperations() {
        this.install();
        final IllegalStateException failure = new IllegalStateException("publication failed");
        final AtomicReference<Task<?>> handle = new AtomicReference<>();
        final AtomicInteger callbacks = new AtomicInteger();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> this.tick(this.ticker(this.source, () -> {
            RegionThreadingHooks.regionTickMutationQueue().add(() -> {
                throw failure;
            });
            handle.set(this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> callbacks.incrementAndGet()));
        }))));
        assertEquals(State.COMPLETED, handle.get().state());
        assertEquals(1, callbacks.get());
        assertCleared(handle.get());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void engineShutdownRetiresQueuedWorkAndUnblocksTheWorkerBeforeWaitingForItsBatch() throws InterruptedException {
        this.install();
        final AtomicReference<Task<?>> handle = new AtomicReference<>();
        final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        final CountDownLatch queued = new CountDownLatch(1);
        final CountDownLatch retired = new CountDownLatch(1);
        final Thread shutdown = new Thread(() -> {
            try {
                await(queued);
                this.engine.shutdown();
            } catch (Throwable failure) {
                asynchronousFailure.set(failure);
            } finally {
                retired.countDown();
            }
        }, "block-operation-shutdown");
        final Thread observer = new Thread(() -> {
            try {
                await(queued);
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!handle.get().isDone() && System.nanoTime() < deadline) LockSupport.parkNanos(100_000L);
                assertEquals(State.SKIPPED, handle.get().state());
            } catch (Throwable failure) {
                asynchronousFailure.set(failure);
            } finally {
                retired.countDown();
            }
        }, "block-operation-retirement");
        shutdown.start();
        observer.start();
        try {
            this.tick(this.ticker(this.source, () -> {
                handle.set(this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> {
                    throw new AssertionError("shutdown must retire this queued operation");
                }));
                queued.countDown();
                await(retired);
            }));
        } finally {
            retired.countDown();
            shutdown.join(10_000L);
            observer.join(10_000L);
        }
        assertFalse(shutdown.isAlive());
        assertFalse(observer.isAlive());
        assertNull(asynchronousFailure.get());
        assertEquals(Reason.SHUTDOWN, handle.get().reason());
        assertCleared(handle.get());
    }

    @Test
    void sidedFurnaceExtractionRequiresTheCorrectExposedFace() {
        final var furnace = new net.minecraft.world.level.block.entity.FurnaceBlockEntity(SOURCE, Blocks.FURNACE.defaultBlockState());
        this.attach(furnace);
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 10));
        final Reference reference = this.operations.reference(furnace);
        assertEquals(TransferStatus.SIDE_REQUIRED, this.operations.transfer(reference, this.targetRef, new Transfer(2, 0, 3, null, null))
                .result().orElseThrow().status());
        assertEquals(TransferStatus.ITEM_REJECTED, this.operations.transfer(reference, this.targetRef, new Transfer(2, 0, 3, Direction.UP, null))
                .result().orElseThrow().status());
        final var moved = this.operations.transfer(reference, this.targetRef, new Transfer(2, 0, 3, Direction.DOWN, null));
        assertEquals(3, moved.result().orElseThrow().moved());
        assertEquals(7, furnace.getItem(2).getCount());
        assertEquals(3, this.target.getItem(0).getCount());
    }

    @Test
    void zeroMoveResultsAreExplicitAndNeverConsumeItems() {
        this.source.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        this.target.setItem(0, new ItemStack(Items.GOLD_INGOT, 1));
        assertEquals(TransferStatus.ITEM_REJECTED, this.operations.transfer(this.sourceRef, this.targetRef, new Transfer(0, 0, 3, null, null)).result().orElseThrow().status());
        this.target.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        assertEquals(TransferStatus.DESTINATION_FULL, this.operations.transfer(this.sourceRef, this.targetRef, new Transfer(0, 0, 3, null, null)).result().orElseThrow().status());
        assertEquals(TransferStatus.INVALID_SLOT, this.operations.transfer(this.sourceRef, this.targetRef, new Transfer(99, 0, 3, null, null)).result().orElseThrow().status());
        assertEquals(TransferStatus.SAME_CONTAINER, this.operations.transfer(this.sourceRef, this.sourceRef, new Transfer(0, 1, 3, null, null)).result().orElseThrow().status());
        assertEquals(10, this.source.getItem(0).getCount());
    }

    @Test
    void retainedReferencesCannotCrossServerServiceLifetimes() {
        ParallelAdapterRuntime.close(this.server);
        ParallelAdapterRuntime.initialize(this.server, registrar -> {});
        final BlockEntityOperations replacement = ParallelApi.forServer(this.server).orElseThrow().blockEntities();
        final Task<?> task = replacement.submit(this.sourceRef, this.targetRef, (first, second) -> true);
        assertEquals(State.REJECTED, task.state());
        assertEquals(Reason.INVALID_REFERENCE, task.reason());
        assertCleared(task);
    }

    @Test
    void serviceCallbackWhileAWorkerRunsCannotExecuteOrCaptureWorldOperations() {
        this.install();
        final AtomicReference<Task<?>> rejected = new AtomicReference<>();
        final AtomicReference<Task<?>> queued = new AtomicReference<>();
        final CountDownLatch submitted = new CountDownLatch(1);
        final CountDownLatch serviceChecked = new CountDownLatch(1);
        this.tick(done -> {
            await(submitted);
            try {
                assertThrows(IllegalStateException.class, () -> this.operations.reference(this.target));
                rejected.set(this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> true));
            } finally {
                serviceChecked.countDown();
            }
            BARRIER.await(done);
        }, this.ticker(this.source, () -> {
            queued.set(this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> true));
            submitted.countDown();
            await(serviceChecked);
        }));
        assertEquals(Reason.INVALID_CONTEXT, rejected.get().reason());
        assertEquals(State.COMPLETED, queued.get().state());
    }

    @Test
    void reentrantOperationCancellationCannotUndoAnAlreadyRunningCallback() {
        this.install();
        final AtomicReference<Task<?>> first = new AtomicReference<>();
        final AtomicInteger callbacks = new AtomicInteger();
        this.tick(this.ticker(this.source, () -> first.set(this.operations.submit(this.sourceRef, this.targetRef, (source, target) -> {
            assertFalse(first.get().cancel());
            assertEquals(State.RUNNING, first.get().state());
            final Task<?> nested = this.operations.submit(this.sourceRef, this.targetRef, (otherSource, otherTarget) -> callbacks.incrementAndGet());
            assertEquals(State.COMPLETED, nested.state());
            return callbacks.incrementAndGet();
        }))));
        assertEquals(State.COMPLETED, first.get().state());
        assertEquals(2, callbacks.get());
    }

    @Test
    void dimensionChangesBetweenSubmissionAndCompletionSkipTheBody() {
        this.install();
        final AtomicReference<Task<?>> handle = new AtomicReference<>();
        final ServerLevel other = mock(ServerLevel.class);
        this.tick(this.ticker(this.source, () -> {
            handle.set(this.operations.submit(this.sourceRef, this.targetRef, (first, second) -> {
                throw new AssertionError("changed dimension");
            }));
            this.target.setLevel(other);
        }));
        assertEquals(Reason.LEVEL_CHANGED, handle.get().reason());
    }

    @Test
    void sidedTransfersRespectTheHopperExtractionAndInsertionSlots() {
        final HopperBlockEntity destination = new HopperBlockEntity(TARGET, Blocks.HOPPER.defaultBlockState());
        this.attach(destination);
        final Reference target = this.operations.reference(destination);
        this.source.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        // Hoppers are ordinary Containers, so their side-independent inventory is valid without side arguments.
        final var transfer = this.operations.transfer(this.sourceRef, target, new Transfer(0, 0, 3, Direction.DOWN, Direction.UP));
        assertEquals(3, transfer.result().orElseThrow().moved());
        assertEquals(10, this.source.getItem(0).getCount() + destination.getItem(0).getCount());
        assertThrows(IllegalArgumentException.class, () -> new Transfer(0, 0, 65, null, null));
    }

    private void install() {
        this.engine = new RegionThreadingEngine(2, false, false, false, true, false, false,
                RegionLayout.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        RegionThreadingManager.install(this.engine);
    }

    private void attach(BlockEntity entity) {
        entity.setLevel(this.level);
        final BlockPos pos = entity.getBlockPos();
        final LevelChunk chunk = this.chunks.computeIfAbsent(ChunkPos.asLong(pos), key -> {
            final LevelChunk loaded = mock(LevelChunk.class);
            when(loaded.isLoaded()).thenReturn(true);
            when(loaded.getPos()).thenReturn(new ChunkPos(pos));
            when(loaded.getBlockEntities()).thenReturn(new HashMap<>());
            when(loaded.getFullStatus()).thenReturn(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING);
            try {
                final Field level = LevelChunk.class.getDeclaredField("level");
                level.setAccessible(true);
                level.set(loaded, this.level);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
            doAnswer(call -> {
                final BlockEntity member = loaded.getBlockEntities().get(call.getArgument(0));
                return member == null ? Blocks.AIR.defaultBlockState() : member.getBlockState();
            }).when(loaded).getBlockState(any(BlockPos.class));
            return loaded;
        });
        chunk.getBlockEntities().put(pos, entity);
    }

    private TickingBlockEntity ticker(HopperBlockEntity entity, Runnable body) {
        try {
            final var create = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, net.minecraft.world.level.block.entity.BlockEntityTicker.class);
            create.setAccessible(true);
            return (TickingBlockEntity) create.invoke(this.chunks.get(ChunkPos.asLong(entity.getBlockPos())), entity,
                    (net.minecraft.world.level.block.entity.BlockEntityTicker<HopperBlockEntity>) (level, pos, state, source) -> body.run());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private void tick(TickingBlockEntity... tickers) {
        this.tick(BARRIER, tickers);
    }

    private void tick(RegionTickBarrier barrier, TickingBlockEntity... tickers) {
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(this.level, barrier);
        assertNotNull(plan);
        for (final TickingBlockEntity ticker : tickers) assertTrue(plan.accept(ticker));
        plan.executeAndAwait();
    }

    private int total() {
        return this.source.getItem(0).getCount() + this.target.getItem(0).getCount();
    }

    private static void assertCleared(Task<?> task) {
        try {
            for (final String name : List.of("source", "target", "operation")) {
                final Field field = task.getClass().getDeclaredField(name);
                field.setAccessible(true);
                assertNull(field.get(task), name + " must be released at a terminal outcome");
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
