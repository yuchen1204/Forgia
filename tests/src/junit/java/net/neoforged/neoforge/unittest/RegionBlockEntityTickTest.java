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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the bounded region block-entity tick slice against the real engine and the real
 * {@link Level#tickBlockEntities()} body.
 * <p>
 * The engine tests drive {@link RegionBlockEntityTickPlan} directly. The level tests use a
 * {@link org.mockito.Mockito#CALLS_REAL_METHODS} {@link ServerLevel} mock with the block-entity ticker
 * fields initialized, so the vanilla preparation, pending-list merge, and finalization run for real while
 * only the level and the tickers are mocks.
 */
@Timeout(30)
class RegionBlockEntityTickTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void planIsDisabledWhenBlockEntityTickWorkersAreOff() {
        this.installEngine(false);
        final ServerLevel level = mockLevel();

        assertNull(this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER));
        assertFalse(this.engine.diagnostics().blockEntityTickWorkerExecutionEnabled());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void planFallsBackToTheServerThreadWhileTheProfilerIsActive() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        doReturn(mock(ProfilerFiller.class)).when(level).getProfiler();

        assertNull(this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER));
    }

    @Test
    void planIsDisabledWhenTheLevelIsAlreadyClosed() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        this.engine.clearLevelRegionTasks(level);

        assertNull(this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER));
    }

    @Test
    void shutdownAfterCollectionTicksTheSnapshotOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        this.engine.shutdown();

        plan.executeAndAwait();

        verify(ticker, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void tickerTicksOnceOnARegionWorker() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        plan.executeAndAwait();

        verify(ticker, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().skippedBlockEntityTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void missingChunkAfterMachineInventoryAndOutputEffectsDoesNotRerunTheMachine() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final var inventory = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 2);
        final List<net.minecraft.world.item.ItemStack> outputs = new CopyOnWriteArrayList<>();
        final AtomicInteger callbacks = new AtomicInteger();
        final var refusal = RegionWorkerChunkUnavailableException.forChunk(100, 100, ChunkStatus.FULL);
        doAnswer(call -> {
            callbacks.incrementAndGet();
            outputs.add(inventory.split(1));
            if (Thread.currentThread().getName().startsWith("NeoForge Region Worker")) throw refusal;
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        assertSame(refusal, assertThrows(RegionWorkerChunkUnavailableException.class, plan::executeAndAwait));
        assertEquals(1, inventory.getCount());
        assertEquals(1, outputs.size());
        assertEquals(1, outputs.getFirst().getCount());
        assertEquals(1, callbacks.get());
        verify(ticker, times(1)).tick();
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void tickerThatRefusesAChunkIsReportedWithoutReplayingIt() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            if (attempts.incrementAndGet() == 1) {
                throw RegionWorkerChunkUnavailableException.forChunk(1, 1, ChunkStatus.FULL);
            }
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        assertThrows(RegionWorkerChunkUnavailableException.class, plan::executeAndAwait);

        verify(ticker, times(1)).tick();
        assertEquals(1, tickThreads.size(), tickThreads.toString());
        assertTrue(tickThreads.getFirst().startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void tickersInDifferentRegionsTickInParallel() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity first = mockTicker(new BlockPos(16, 64, 16));
        final TickingBlockEntity second = mockTicker(new BlockPos(4113, 64, 17));
        final CyclicBarrier rendezvous = new CyclicBarrier(2);
        doAnswer(invocation -> {
            rendezvous.await(10, TimeUnit.SECONDS);
            return null;
        }).when(first).tick();
        doAnswer(invocation -> {
            rendezvous.await(10, TimeUnit.SECONDS);
            return null;
        }).when(second).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(first);
        plan.accept(second);
        plan.executeAndAwait();

        // The rendezvous only opens when both regions are inside their worker tick at the same time.
        verify(first, times(1)).tick();
        verify(second, times(1)).tick();
        assertEquals(2L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void packedRegionLeasesTickEveryRegionOfAPackExactlyOnce() {
        // Sixteen regions on four workers: every packed task holds two regions, and every region is leased once,
        // ticked once and released again. The counters prove the packing happened and the tickers prove that no
        // region of a pack was skipped, repeated or ticked while another task held it.
        this.installEngine(true, 4, true, true);
        final ServerLevel level = mockLevel();
        final List<TickingBlockEntity> tickers = new ArrayList<>();
        for (int index = 0; index < 16; ++index) {
            tickers.add(mockTicker(new BlockPos(17 + index * 48, 64, 17)));
        }

        final RegionBlockEntityTickPlan plan = plan(level);
        tickers.forEach(plan::accept);
        plan.executeAndAwait();

        for (final TickingBlockEntity ticker : tickers) {
            verify(ticker, times(1)).tick();
        }
        final RegionThreadingDiagnostics.WorkerDispatchCounters dispatch = this.engine.diagnostics().workerDispatch();
        assertEquals(16L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(8L, dispatch.packedTasks(), "sixteen regions on four workers must pack into eight tasks");
        assertEquals(16L, dispatch.packedRegions(), "every region of the batch must be held by a packed task");
        assertEquals(0L, dispatch.partialPackFallbacks());
        assertTrue(dispatch.persistentWorkers());
        assertTrue(dispatch.packedRegionLeases());
        assertEquals(0L, dispatch.submissions() - dispatch.packedTasks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount(), "a packed task must release every lease it held");
    }

    @Test
    void unsafeBlockEntityTypeTicksOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        // Beacon queries entity storage and then mutates the returned players, so it must never run on a
        // worker even though the query itself would be handed off.
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17), "minecraft:beacon");
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        plan.executeAndAwait();

        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("typeNotDeclared"));
        verify(ticker, times(1)).tick();
        assertEquals(List.of(Thread.currentThread().getName()), tickThreads, "an undeclared block entity must stay on the server thread");
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void brewingStandCraftingRemainderCannotSpawnFromAWorker() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        // The patched BrewingStandBlockEntity completion can call Containers.dropItemStack for a leftover
        // crafting remainder. An interior position does not make entity creation safe, so the whole
        // region must stay on the server thread.
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17), "minecraft:brewing_stand");
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        plan.executeAndAwait();

        verify(ticker, times(1)).tick();
        assertEquals(List.of(Thread.currentThread().getName()), tickThreads, "a brewing stand must never tick on a worker");
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void interiorOnlyBlockEntityAtARegionBoundaryTicksOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        // Chunk 0,0 borders the regions at -1,0 and 0,-1, so a neighbor-writing furnace cannot be leased.
        final TickingBlockEntity ticker = mockTicker(new BlockPos(1, 64, 1), "minecraft:furnace");

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        plan.executeAndAwait();

        verify(ticker, times(1)).tick();
        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("boundaryChunk"));
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void boundaryFurnacesInDifferentRegionsRunConcurrentlyWithBorderPropagation() {
        this.installEngine(true, 2, false, false);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = mockLevel();
        when(level.getChunkSource().hasChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        final TickingBlockEntity first = mockTicker(new BlockPos(47, 64, 17), "minecraft:furnace");
        final TickingBlockEntity second = mockTicker(new BlockPos(48, 64, 17), "minecraft:smoker");
        final CyclicBarrier rendezvous = new CyclicBarrier(2);
        final List<Boolean> workers = new CopyOnWriteArrayList<>();
        for (final TickingBlockEntity ticker : List.of(first, second)) {
            doAnswer(call -> {
                workers.add(RegionThreadingHooks.isNativeRegionWorkerThread());
                rendezvous.await(10, TimeUnit.SECONDS);
                return null;
            }).when(ticker).tick();
        }
        final RegionBlockEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(first));
        assertTrue(plan.accept(second));
        readyBoundaryNeighbors(level);
        plan.executeAndAwait();
        assertEquals(List.of(true, true), workers);
        verify(first, times(1)).tick();
        verify(second, times(1)).tick();
        assertEquals(2L, this.engine.boundaryBlockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void unloadedBoundaryNeighborhoodFallsBackBeforeTheBodyStarts() {
        this.installEngine(true, 2, false, false);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(47, 64, 17));
        final List<Boolean> workers = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            workers.add(RegionThreadingHooks.isNativeRegionWorkerThread());
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(ticker));
        plan.executeAndAwait();
        assertEquals(List.of(false), workers);
        verify(ticker, times(1)).tick();
        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("unloadedNeighborhood"));
        assertEquals(0L, this.engine.boundaryBlockEntityTicksOnWorkers());
    }

    @Test
    void fullTicketWithoutAReadyNeighborStillFallsBackBeforeTheBody() {
        this.installEngine(true, 2, false, false);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = mockLevel();
        this.engine.addRegionSection(level, 3, 1);
        when(level.getChunkSource().hasChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        final TickingBlockEntity ticker = mockTicker(new BlockPos(47, 64, 17));
        final List<Boolean> workers = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            workers.add(RegionThreadingHooks.isNativeRegionWorkerThread());
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(ticker));
        plan.executeAndAwait();
        assertEquals(List.of(false), workers);
        verify(ticker, times(1)).tick();
        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("unloadedNeighborhood"));
        assertEquals(0L, this.engine.boundaryBlockEntityTicksOnWorkers());
    }

    @Test
    void loadedButUntrackedBoundaryNeighborKeepsTheCompleteTickerOnTheServer() {
        this.installEngine(true, 2, false, false);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = mockLevel();
        when(level.getChunkSource().hasChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        final TickingBlockEntity ticker = mockTicker(new BlockPos(47, 64, 17));
        final List<Boolean> workers = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            workers.add(RegionThreadingHooks.isNativeRegionWorkerThread());
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(ticker));
        readyBoundaryNeighbors(level);
        plan.executeAndAwait();
        assertEquals(List.of(false), workers);
        verify(ticker, times(1)).tick();
        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("untrackedNeighborhood"));
        assertEquals(0L, this.engine.blockEntityFallbackAdmissionCounts().get("unloadedNeighborhood"));
        assertEquals(0L, this.engine.boundaryBlockEntityTicksOnWorkers());
    }

    @Test
    void disablingBorderPropagationAfterCollectionRefusesBoundaryWorkerAdmission() {
        this.installEngine(true, 2, false, false);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = mockLevel();
        when(level.getChunkSource().hasChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        final TickingBlockEntity ticker = mockTicker(new BlockPos(47, 64, 17));
        final List<Boolean> workers = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            workers.add(RegionThreadingHooks.isNativeRegionWorkerThread());
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(ticker));
        this.engine.setBorderUpdatesEnabledForTesting(false);
        plan.executeAndAwait();
        assertEquals(List.of(false), workers);
        verify(ticker, times(1)).tick();
        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("boundaryChunk"));
        assertEquals(0L, this.engine.boundaryBlockEntityTicksOnWorkers());
    }

    @Test
    void boundaryHopperKeepsFurnaceInTheSameChunkOnTheServerInOrder() {
        this.installEngine(true, 2, false, false);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = mockLevel();
        when(level.getChunkSource().hasChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        final TickingBlockEntity furnace = mockTicker(new BlockPos(46, 64, 17));
        final TickingBlockEntity hopper = mockTicker(new BlockPos(47, 64, 17), "minecraft:hopper");
        final List<String> trace = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            trace.add("furnace:" + RegionThreadingHooks.isNativeRegionWorkerThread());
            return null;
        }).when(furnace).tick();
        doAnswer(call -> {
            trace.add("hopper:" + RegionThreadingHooks.isNativeRegionWorkerThread());
            return null;
        }).when(hopper).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(furnace));
        assertTrue(plan.accept(hopper));
        plan.executeAndAwait();
        assertEquals(List.of("furnace:false", "hopper:false"), trace);
        verify(furnace, times(1)).tick();
        verify(hopper, times(1)).tick();
        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("safeInMixedChunk"));
        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("boundaryChunk"));
        assertEquals(0L, this.engine.boundaryBlockEntityTicksOnWorkers());
    }

    @Test
    void failedBoundaryTickerDoesNotReplayItsInventoryEffect() {
        this.installEngine(true, 2, false, false);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = mockLevel();
        when(level.getChunkSource().hasChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        this.engine.addRegionSection(level, 3, 1);
        final TickingBlockEntity ticker = mockTicker(new BlockPos(47, 64, 17));
        final var inventory = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 2);
        final List<net.minecraft.world.item.ItemStack> outputs = new CopyOnWriteArrayList<>();
        final var refusal = RegionWorkerChunkUnavailableException.forChunk(100, 100, ChunkStatus.FULL);
        doAnswer(call -> {
            outputs.add(inventory.split(1));
            throw refusal;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(ticker));
        readyBoundaryNeighbors(level);
        assertSame(refusal, assertThrows(RegionWorkerChunkUnavailableException.class, plan::executeAndAwait));
        assertEquals(1, inventory.getCount());
        assertEquals(1, outputs.size());
        verify(ticker, times(1)).tick();
        assertEquals(0L, this.engine.boundaryBlockEntityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void rejectedBoundaryWorkerTaskIsExcludedFromNativeBoundaryCompletions() throws ReflectiveOperationException {
        this.installEngine(true, 2, false, false);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        closeWorkerPool(this.engine);
        final ServerLevel level = mockLevel();
        this.engine.addRegionSection(level, 3, 1);
        when(level.getChunkSource().hasChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        final TickingBlockEntity ticker = mockTicker(new BlockPos(47, 64, 17));
        final List<Boolean> workers = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            workers.add(RegionThreadingHooks.isNativeRegionWorkerThread());
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(ticker));
        readyBoundaryNeighbors(level);
        plan.executeAndAwait();
        assertEquals(List.of(false), workers);
        verify(ticker, times(1)).tick();
        assertEquals(0L, this.engine.boundaryBlockEntityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void interiorOnlyBlockEntityInsideTheRegionTicksOnAWorker() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        // Chunk 1,1 is the interior chunk of fixed region 0,0.
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17), "minecraft:furnace");
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        plan.executeAndAwait();

        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1, tickThreads.size());
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
    }

    @Test
    void chunkWithOneUnsafeTickerKeepsItsSafeTickerOnTheServerThreadWithoutDoubleTicking() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity safe = mockTicker(new BlockPos(17, 64, 17), "minecraft:furnace");
        final TickingBlockEntity unsafe = mockTicker(new BlockPos(18, 64, 17), "minecraft:conduit");
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(safe).tick();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(unsafe).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(safe);
        plan.accept(unsafe);
        plan.executeAndAwait();

        verify(safe, times(1)).tick();
        verify(unsafe, times(1)).tick();
        assertEquals(List.of(Thread.currentThread().getName(), Thread.currentThread().getName()), tickThreads);
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(2L, this.engine.diagnostics().fallbackBlockEntityTicks());
        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("typeNotDeclared"));
        assertEquals(1L, this.engine.blockEntityFallbackAdmissionCounts().get("safeInMixedChunk"));
        assertEquals(0L, this.engine.blockEntityFallbackAdmissionCounts().get("boundaryChunk"));
    }

    @Test
    void tickersInTheSameRegionTickSeriallyInSnapshotOrder() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity first = mockTicker(new BlockPos(16, 64, 16));
        final TickingBlockEntity second = mockTicker(new BlockPos(18, 64, 18));
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final List<TickingBlockEntity> order = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            order.add(first);
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20L);
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(first).tick();
        doAnswer(invocation -> {
            order.add(second);
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20L);
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(second).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(first);
        plan.accept(second);
        plan.executeAndAwait();

        assertEquals(1, maxActive.get(), "one region must never tick against itself");
        assertEquals(List.of(first, second), order);
        verify(first, times(1)).tick();
        verify(second, times(1)).tick();
    }

    @Test
    void tickerRemovedByAnEarlierTickerOfTheSameRegionIsSkipped() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity first = mockTicker(new BlockPos(16, 64, 16));
        final TickingBlockEntity second = mockTicker(new BlockPos(17, 64, 16));
        doAnswer(invocation -> {
            when(second.isRemoved()).thenReturn(true);
            return null;
        }).when(first).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(first);
        plan.accept(second);
        plan.executeAndAwait();

        verify(first, times(1)).tick();
        verify(second, never()).tick();
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void failedTickerReleasesTheRegionLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity failing = mockTicker(new BlockPos(17, 64, 17));
        doAnswer(invocation -> {
            throw new IllegalStateException("boom");
        }).when(failing).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(failing);
        assertThrows(IllegalStateException.class, plan::executeAndAwait);

        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());

        final TickingBlockEntity healthy = mockTicker(new BlockPos(17, 64, 17));
        final RegionBlockEntityTickPlan retry = plan(level);
        retry.accept(healthy);
        retry.executeAndAwait();
        verify(healthy, times(1)).tick();
    }

    @Test
    void tickerWhoseRegionDiedBeforeTheBatchTicksOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final BlockPos pos = new BlockPos(17, 64, 17);
        markRegionDead(this.engine, level, pos);
        final TickingBlockEntity ticker = mockTicker(pos);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        plan.executeAndAwait();

        verify(ticker, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void mergedRegionIsReResolvedBeforeTheBatch() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(16, 64, 16));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        // Merge the region of the ticker with the adjacent section's region before the batch leases it.
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 16, 0);
        assertEquals(1, this.engine.diagnostics().regionCount());

        plan.executeAndAwait();

        verify(ticker, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void barrierFailureStillWaitsForTheGroupAndReleasesTheLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final AtomicBoolean tickFinished = new AtomicBoolean();
        doAnswer(invocation -> {
            tickFinished.set(true);
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        final RegionTickBarrier throwingBarrier = condition -> {
            throw new IllegalStateException("barrier boom");
        };
        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> plan.executeAndAwait(throwingBarrier));

        assertEquals("barrier boom", failure.getMessage());
        assertTrue(tickFinished.get(), "the batch must wait for the worker before it reports the barrier failure");
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void rejectedWorkerGroupRunsOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine(true);
        closeWorkerPool(this.engine);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        plan.executeAndAwait();

        verify(ticker, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void closedLevelBeforeTheBatchSkipsTheCollectedTickers() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);

        this.engine.clearLevelRegionTasks(level);
        assertEquals(0, this.engine.diagnostics().regionCount());

        plan.executeAndAwait();

        verify(ticker, never()).tick();
        assertEquals(1L, this.engine.diagnostics().skippedBlockEntityTicks());
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void levelTicksBlockEntitiesOnWorkersAndPublishesTickersAddedDuringTheBatch() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity first = mockTicker(new BlockPos(16, 64, 16));
        final TickingBlockEntity second = mockTicker(new BlockPos(17, 64, 16));
        final TickingBlockEntity added = mockTicker(new BlockPos(18, 64, 16));
        final AtomicBoolean addedOnce = new AtomicBoolean();
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            if (addedOnce.compareAndSet(false, true)) {
                level.addBlockEntityTicker(added);
            }
            return null;
        }).when(first).tick();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(second).tick();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(added).tick();
        level.addBlockEntityTicker(first);
        level.addBlockEntityTicker(second);

        tickBlockEntities(level);

        verify(first, times(1)).tick();
        verify(second, times(1)).tick();
        verify(added, never()).tick();
        assertEquals(2, tickThreads.size());
        assertTrue(tickThreads.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")), tickThreads.toString());
        assertEquals(List.of(first, second, added), blockEntityTickers(level));
        assertTrue(pendingBlockEntityTickers(level).isEmpty(), "a ticker added while ticking must be published at finalization");
        assertFalse(tickingBlockEntities(level));

        tickBlockEntities(level);

        verify(added, times(1)).tick();
        assertEquals(3, blockEntityTickers(level).size());
        assertTrue(pendingBlockEntityTickers(level).isEmpty());
    }

    @Test
    void levelTicksBlockEntitiesInlineWhenTheSliceIsDisabled() throws ReflectiveOperationException {
        this.installEngine(false);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(16, 64, 16));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();
        blockEntityTickers(level).add(ticker);

        tickBlockEntities(level);

        verify(ticker, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void levelRemovesRemovedTickersBeforeTheBatch() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity removed = mockTicker(new BlockPos(16, 64, 16));
        when(removed.isRemoved()).thenReturn(true);
        level.addBlockEntityTicker(removed);

        tickBlockEntities(level);

        verify(removed, never()).tick();
        assertTrue(blockEntityTickers(level).isEmpty());
    }

    @Test
    void levelRunsFreshBlockEntityOnLoadOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final BlockEntity fresh = mock(BlockEntity.class);
        when(fresh.hasLevel()).thenReturn(true);
        setLevelField(level, "freshBlockEntities", new ArrayList<>(List.of(fresh)));

        tickBlockEntities(level);

        verify(fresh, times(1)).onLoad();
        assertTrue(freshBlockEntities(level).isEmpty());
    }

    @Test
    void freshBlockEntitiesAddedWhileTickingStayPendingUntilTheNextTick() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(16, 64, 16));
        final BlockEntity fresh = mock(BlockEntity.class);
        when(fresh.hasLevel()).thenReturn(true);
        final AtomicBoolean addedOnce = new AtomicBoolean();
        doAnswer(invocation -> {
            if (addedOnce.compareAndSet(false, true)) {
                level.addFreshBlockEntities(List.of(fresh));
            }
            return null;
        }).when(ticker).tick();
        level.addBlockEntityTicker(ticker);

        tickBlockEntities(level);

        verify(fresh, never()).onLoad();
        assertEquals(List.of(fresh), pendingFreshBlockEntities(level), "a fresh block entity added while ticking must stay pending like vanilla");
        assertTrue(freshBlockEntities(level).isEmpty());

        tickBlockEntities(level);

        verify(fresh, times(1)).onLoad();
        assertTrue(pendingFreshBlockEntities(level).isEmpty());
    }

    @Test
    void failingInlineTickerResetsTickingAndPublishesPendingTickerOnce() throws ReflectiveOperationException {
        this.installEngine(false);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity failing = mockTicker(new BlockPos(16, 64, 16));
        final TickingBlockEntity added = mockTicker(new BlockPos(17, 64, 16));
        final IllegalStateException failure = new IllegalStateException("inline ticker boom");
        final AtomicBoolean firstTick = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstTick.compareAndSet(true, false)) {
                level.addBlockEntityTicker(added);
                throw failure;
            }
            return null;
        }).when(failing).tick();
        blockEntityTickers(level).add(failing);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> tickBlockEntities(level));

        assertSame(failure, thrown, "the original tick failure must reach the crash handler unchanged");
        assertFalse(tickingBlockEntities(level), "the ticking flag must reset after a failing inline ticker");
        assertTrue(pendingBlockEntityTickers(level).isEmpty(), "tickers added while ticking must be published at finalization");
        assertEquals(List.of(failing, added), blockEntityTickers(level));

        tickBlockEntities(level);

        verify(added, times(1)).tick();
        assertFalse(tickingBlockEntities(level));
        assertTrue(pendingBlockEntityTickers(level).isEmpty());
    }

    @Test
    void failingWorkerTickerResetsTickingAndPublishesPendingTickerOnce() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity failing = mockTicker(new BlockPos(17, 64, 17));
        final TickingBlockEntity added = mockTicker(new BlockPos(18, 64, 17));
        final IllegalStateException failure = new IllegalStateException("worker ticker boom");
        final AtomicBoolean firstTick = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstTick.compareAndSet(true, false)) {
                level.addBlockEntityTicker(added);
                throw failure;
            }
            return null;
        }).when(failing).tick();
        level.addBlockEntityTicker(failing);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> tickBlockEntities(level));

        assertSame(failure, thrown, "the original worker failure must reach the crash handler unchanged");
        assertFalse(tickingBlockEntities(level), "the ticking flag must reset after a failing worker batch");
        assertTrue(pendingBlockEntityTickers(level).isEmpty(), "tickers added while ticking must be published at finalization");
        assertEquals(List.of(failing, added), blockEntityTickers(level));

        tickBlockEntities(level);

        verify(added, times(1)).tick();
        assertFalse(tickingBlockEntities(level));
    }

    @Test
    void failingFreshBlockEntityOnLoadIsNotRetriedAndPublishesPendingTickerOnce() throws ReflectiveOperationException {
        this.installEngine(false);
        final ServerLevel level = mockLevel();
        final BlockEntity failingFresh = mock(BlockEntity.class);
        when(failingFresh.hasLevel()).thenReturn(true);
        final BlockEntity healthyFresh = mock(BlockEntity.class);
        when(healthyFresh.hasLevel()).thenReturn(true);
        final TickingBlockEntity added = mockTicker(new BlockPos(17, 64, 16));
        final IllegalStateException failure = new IllegalStateException("fresh onLoad boom");
        doAnswer(invocation -> {
            level.addBlockEntityTicker(added);
            throw failure;
        }).when(failingFresh).onLoad();
        setLevelField(level, "freshBlockEntities", new ArrayList<>(List.of(failingFresh, healthyFresh)));

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> tickBlockEntities(level));

        assertSame(failure, thrown, "the original onLoad failure must reach the crash handler unchanged");
        assertFalse(tickingBlockEntities(level), "the ticking flag must reset after a failing fresh onLoad");
        assertTrue(pendingBlockEntityTickers(level).isEmpty());
        assertEquals(List.of(added), blockEntityTickers(level));
        assertEquals(List.of(healthyFresh), freshBlockEntities(level), "only the attempted fresh entry is dropped");
        verify(failingFresh, times(1)).onLoad();
        verify(healthyFresh, never()).onLoad();

        tickBlockEntities(level);

        verify(failingFresh, times(1)).onLoad();
        verify(healthyFresh, times(1)).onLoad();
        verify(added, times(1)).tick();
        assertTrue(freshBlockEntities(level).isEmpty());
    }

    @Test
    void levelClearWaitsForTheActiveBlockEntityBatchAndRetiresItsRegion() throws InterruptedException, ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        assertEquals(1, this.engine.diagnostics().regionCount());
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final java.util.concurrent.CountDownLatch ticking = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseTick = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            ticking.countDown();
            awaitOrFail(releaseTick, "the block entity tick was never released");
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait();
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "block-entity-clear-batch-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the block entity tick never started");

        final AtomicBoolean clearReturned = new AtomicBoolean();
        final AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        final Thread clearThread = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
                clearReturned.set(true);
            } catch (Throwable throwable) {
                clearFailure.set(throwable);
            }
        }, "block-entity-clear-test");
        clearThread.start();
        Thread.sleep(200L);
        assertFalse(clearReturned.get(), "the level clear must wait for the active block entity batch");

        // The batch already leased the region, so removing its section while the clear waits cannot
        // retire it: only the worker's own removeIfEmpty after the tick may do that.
        this.engine.removeRegionSection(level, 0, 0);
        assertEquals(1, this.engine.diagnostics().regionCount(), "the leased region must stay alive while it ticks");

        releaseTick.countDown();
        batchThread.join(10_000L);
        clearThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the block entity batch never completed");
        assertFalse(clearThread.isAlive(), "the level clear never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(clearFailure.get(), () -> "clear failed: " + clearFailure.get());
        verify(ticker, times(1)).tick();
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().skippedBlockEntityTicks());
        assertEquals(0, this.engine.diagnostics().regionCount(), "the worker's removeIfEmpty must retire the cleared region");
        assertNull(this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER), "the cleared level identity must reject new plans");
    }

    @Test
    void shutdownWaitsForTheRunningBlockEntityBatch() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final java.util.concurrent.CountDownLatch ticking = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseTick = new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean tickFinished = new AtomicBoolean();
        doAnswer(invocation -> {
            ticking.countDown();
            awaitOrFail(releaseTick, "the block entity tick was never released");
            tickFinished.set(true);
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait();
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "block-entity-batch-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the batch never started");

        final AtomicBoolean shutdownReturned = new AtomicBoolean();
        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        final Thread shutdownThread = new Thread(() -> {
            try {
                this.engine.shutdown();
                if (!tickFinished.get()) {
                    shutdownFailure.set(new AssertionError("shutdown returned before the block entity tick finished"));
                }
                shutdownReturned.set(true);
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            }
        }, "block-entity-shutdown-test");
        shutdownThread.start();
        Thread.sleep(200L);
        assertFalse(shutdownReturned.get(), "shutdown must wait for the running block entity batch");

        releaseTick.countDown();
        batchThread.join(10_000L);
        shutdownThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the block entity batch never completed");
        assertFalse(shutdownThread.isAlive(), "shutdown never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(shutdownFailure.get(), () -> "shutdown failed: " + shutdownFailure.get());
        verify(ticker, times(1)).tick();
    }

    @Test
    void levelUnloadWaitsForTheRunningBlockEntityBatch() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(new BlockPos(17, 64, 17));
        final java.util.concurrent.CountDownLatch ticking = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseTick = new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean tickFinished = new AtomicBoolean();
        doAnswer(invocation -> {
            ticking.countDown();
            awaitOrFail(releaseTick, "the block entity tick was never released");
            tickFinished.set(true);
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        plan.accept(ticker);
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait();
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "block-entity-unload-batch-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the batch never started");

        final AtomicBoolean unloadReturned = new AtomicBoolean();
        final AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
        final Thread unloadThread = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
                if (!tickFinished.get()) {
                    unloadFailure.set(new AssertionError("the level unload returned before the block entity tick finished"));
                }
                unloadReturned.set(true);
            } catch (Throwable throwable) {
                unloadFailure.set(throwable);
            }
        }, "block-entity-unload-test");
        unloadThread.start();
        Thread.sleep(200L);
        assertFalse(unloadReturned.get(), "the level unload must wait for the running block entity batch");

        releaseTick.countDown();
        batchThread.join(10_000L);
        unloadThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the block entity batch never completed");
        assertFalse(unloadThread.isAlive(), "the level unload never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(unloadFailure.get(), () -> "unload failed: " + unloadFailure.get());
        verify(ticker, times(1)).tick();
    }

    @Test
    void fallbackPhaseReusesEachLeaseAndPreservesChunkAndNestedCallbackOrder() {
        this.installEngine(true);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = mockLevel();
        final Thread serverThread = Thread.currentThread();
        final net.minecraft.server.MinecraftServer server = mock(net.minecraft.server.MinecraftServer.class);
        when(server.isSameThread()).thenAnswer(call -> Thread.currentThread() == serverThread);
        doReturn(server).when(level).getServer();
        final List<Integer> executed = new CopyOnWriteArrayList<>();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        for (int i = 0; i < 256; ++i) {
            final int index = i;
            final BlockPos position = new BlockPos(i < 128 ? 8 : 300, 64, 8);
            final TickingBlockEntity ticker = mockTicker(position, "example:unclassified_machine");
            doAnswer(call -> {
                assertEquals(serverThread, Thread.currentThread());
                RegionThreadingHooks.runBlockTickTask(level, position, () -> {
                    assertTrue(this.engine.isOwnedByCurrentRegion(level, position));
                    executed.add(index);
                    final var context = this.engine.currentContext();
                    RegionThreadingHooks.runBlockTickTask(level, position, () -> {
                        assertSame(context, this.engine.currentContext(), "same-region nested work retains its context and neighbor collector");
                        executed.add(-index - 1);
                    });
                });
                return null;
            }).when(ticker).tick();
            plan.accept(ticker);
        }
        plan.executeAndAwait();
        final List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 256; ++i) {
            expected.add(i);
            expected.add(-i - 1);
        }
        assertEquals(expected, executed);
        final var counters = this.engine.blockEntityFallbackScopeCounters();
        assertEquals(1L, counters.phases());
        assertEquals(2L, counters.leases(), "256 tickers in two regions need only two leases");
        assertEquals(counters.leases(), counters.releases());
        assertEquals(512L, counters.callbacks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers(), "admission policy must not widen");
    }

    @Test
    void fallbackPhaseReleasesItsLeasesAndScopeAfterATickerThrows() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final net.minecraft.server.MinecraftServer server = mock(net.minecraft.server.MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        doReturn(server).when(level).getServer();
        final BlockPos pos = new BlockPos(8, 64, 8);
        final TickingBlockEntity ticker = mockTicker(pos, "example:unclassified_machine");
        final IllegalStateException failure = new IllegalStateException("ticker failed");
        doAnswer(call -> {
            RegionThreadingHooks.runBlockTickTask(level, pos, () -> {
                throw failure;
            });
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        plan.accept(ticker);
        assertEquals(failure, assertThrows(IllegalStateException.class, plan::executeAndAwait));
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(1L, this.engine.blockEntityFallbackScopeCounters().releases());
        final long callbacks = this.engine.blockEntityFallbackScopeCounters().callbacks();
        RegionThreadingHooks.runBlockTickTask(level, pos, () -> {});
        assertEquals(callbacks, this.engine.blockEntityFallbackScopeCounters().callbacks(), "the scope must not leak past failure");
    }

    @Test
    void fallbackScopeIsRefusedWhenTheCallerIsNotTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final net.minecraft.server.MinecraftServer server = mock(net.minecraft.server.MinecraftServer.class);
        when(server.isSameThread()).thenReturn(false);
        doReturn(server).when(level).getServer();
        final BlockPos pos = new BlockPos(8, 64, 8);
        final TickingBlockEntity ticker = mockTicker(pos, "example:unclassified_machine");
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            RegionThreadingHooks.runBlockTickTask(level, pos, ran::incrementAndGet);
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        plan.accept(ticker);
        plan.executeAndAwait();
        assertEquals(1, ran.get());
        assertEquals(0L, this.engine.blockEntityFallbackScopeCounters().phases());
        assertEquals(1L, this.engine.blockEntityFallbackScopeCounters().refused());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void quiescentFallbackAppliesCrossRegionUpdatesInlineInsteadOfRecordingAFixpoint() {
        this.installEngine(true);
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = mockLevel();
        final net.minecraft.server.MinecraftServer server = mock(net.minecraft.server.MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        doReturn(server).when(level).getServer();
        final BlockPos own = new BlockPos(8, 64, 8);
        final BlockPos foreign = new BlockPos(300, 64, 8);
        final Block block = mock(Block.class);
        final AtomicInteger written = new AtomicInteger();
        final TickingBlockEntity ticker = mockTicker(own, "example:unclassified_machine");
        doAnswer(call -> {
            RegionThreadingHooks.runBlockTickTask(level, own, () -> {
                assertFalse(RegionThreadingHooks.deferBorderNeighborUpdate(level, foreign, block, own, false),
                        "a quiescent server-thread update should execute inline");
                this.engine.runInBlockRegion(level, foreign, written::incrementAndGet);
                assertEquals(1, written.get(), "the caller sees cross-region side effects immediately");
            });
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        plan.accept(ticker);
        plan.executeAndAwait();
        assertEquals(0L, this.engine.diagnostics().borderUpdates().recorded());
        assertEquals(2L, this.engine.blockEntityFallbackScopeCounters().leases());
        assertEquals(2L, this.engine.blockEntityFallbackScopeCounters().releases());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void fallbackScopeIsRefusedWhileAnotherLevelsWorkerBatchIsInFlight() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel workerLevel = mockLevel();
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final TickingBlockEntity workerTicker = mockTicker(new BlockPos(17, 64, 17));
        doAnswer(call -> {
            entered.countDown();
            awaitOrFail(release, "the held worker was not released");
            return null;
        }).when(workerTicker).tick();
        final RegionBlockEntityTickPlan workerPlan = this.plan(workerLevel);
        workerPlan.accept(workerTicker);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread otherBatch = new Thread(() -> {
            try {
                workerPlan.executeAndAwait();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "other-level-batch");
        otherBatch.start();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            final ServerLevel fallbackLevel = mockLevel();
            final net.minecraft.server.MinecraftServer server = mock(net.minecraft.server.MinecraftServer.class);
            when(server.isSameThread()).thenReturn(true);
            doReturn(server).when(fallbackLevel).getServer();
            final TickingBlockEntity ticker = mockTicker(new BlockPos(8, 64, 8), "example:unclassified_machine");
            final AtomicInteger ran = new AtomicInteger();
            doAnswer(call -> {
                RegionThreadingHooks.runBlockTickTask(fallbackLevel, ticker.getPos(), ran::incrementAndGet);
                return null;
            }).when(ticker).tick();
            final RegionBlockEntityTickPlan plan = this.plan(fallbackLevel);
            plan.accept(ticker);
            plan.executeAndAwait();
            assertEquals(1, ran.get());
            assertEquals(0L, this.engine.blockEntityFallbackScopeCounters().phases());
            assertEquals(1L, this.engine.blockEntityFallbackScopeCounters().refused());
        } finally {
            release.countDown();
            otherBatch.join(10_000L);
        }
        assertFalse(otherBatch.isAlive());
        assertNull(failure.get(), () -> "the other level batch failed: " + failure.get());
    }

    @Test
    void fallbackScopeIsRefusedInsideAnExistingRegionContext() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final net.minecraft.server.MinecraftServer server = mock(net.minecraft.server.MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        doReturn(server).when(level).getServer();
        final BlockPos pos = new BlockPos(8, 64, 8);
        final TickingBlockEntity ticker = mockTicker(pos, "example:unclassified_machine");
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            RegionThreadingHooks.runBlockTickTask(level, pos, ran::incrementAndGet);
            return null;
        }).when(ticker).tick();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        plan.accept(ticker);
        this.engine.runInBlockRegion(level, pos, plan::executeAndAwait);
        assertEquals(1, ran.get());
        assertEquals(0L, this.engine.blockEntityFallbackScopeCounters().phases());
        assertEquals(1L, this.engine.blockEntityFallbackScopeCounters().refused());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void busyRegionOutsideAPublishedBatchKeepsTheFallbackCallbackQueued() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Thread main = Thread.currentThread();
        final net.minecraft.server.MinecraftServer server = mock(net.minecraft.server.MinecraftServer.class);
        when(server.isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        doReturn(server).when(level).getServer();
        final BlockPos pos = new BlockPos(8, 64, 8);
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread holder = new Thread(() -> {
            try {
                this.engine.runInBlockRegion(level, pos, () -> {
                    entered.countDown();
                    awaitOrFail(release, "the busy region holder was not released");
                });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "manual-region-holder");
        holder.start();
        final AtomicInteger ran = new AtomicInteger();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            final TickingBlockEntity ticker = mockTicker(pos, "example:unclassified_machine");
            doAnswer(call -> {
                RegionThreadingHooks.runBlockTickTask(level, pos, ran::incrementAndGet);
                return null;
            }).when(ticker).tick();
            final RegionBlockEntityTickPlan plan = this.plan(level);
            plan.accept(ticker);
            plan.executeAndAwait();
            assertEquals(0, ran.get(), "an occupied region must never be accessed inline without its lease");
            assertEquals(0L, this.engine.blockEntityFallbackScopeCounters().phases());
            assertEquals(1L, this.engine.blockEntityFallbackScopeCounters().refused());
            assertEquals(0L, this.engine.blockEntityFallbackScopeCounters().leases());
        } finally {
            release.countDown();
            holder.join(10_000L);
        }
        assertFalse(holder.isAlive());
        assertNull(failure.get(), () -> "region holder failed: " + failure.get());
        this.engine.drainRegionTasks(level, pos);
        assertEquals(1, ran.get(), "the queued fallback must run once after the owner releases the region");
    }

    private static void readyBoundaryNeighbors(ServerLevel level) {
        final var loaded = mock(net.minecraft.world.level.chunk.LevelChunk.class);
        when(loaded.isLoaded()).thenReturn(true);
        final ServerChunkCache chunks = level.getChunkSource();
        doReturn(loaded).when(chunks).getChunkNow(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    private void installEngine(boolean blockEntityTickWorkers) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, blockEntityTickWorkers);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private void installEngine(boolean blockEntityTickWorkers, int workerThreads, boolean persistentWorkers, boolean packedRegionLeases) {
        final RegionThreadingEngine created = new RegionThreadingEngine(workerThreads, false, false, false, blockEntityTickWorkers, false, false, false, persistentWorkers, packedRegionLeases, RegionLayout.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionBlockEntityTickPlan plan(ServerLevel level) {
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER);
        assertNotNull(plan, "block entity tick worker execution must be enabled");
        return plan;
    }

    private static ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // The group random of a block-entity group is derived from the level's dimension, seed and game
        // time, so the level has to answer them like a real server level.
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("test-level").when(level).toString();
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        doReturn(true).when(level).shouldTickBlocksAt(any(BlockPos.class));
        // This class exercises where an admitted ticker runs, not the admission decision itself. The
        // level therefore reports the entity load as complete, so the tickers reach the batch. The
        // entity-load gate is proven against a real PersistentEntitySectionManager in
        // RegionBlockEntityTickEligibilityTest.
        doReturn(true).when(level).areEntitiesLoaded(anyLong());
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doAnswer(invocation -> {
            final BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(level).getChunkSource();
        setLevelFieldUnchecked(level, "blockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelFieldUnchecked(level, "pendingBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelFieldUnchecked(level, "freshBlockEntities", new ArrayList<BlockEntity>());
        setLevelFieldUnchecked(level, "pendingFreshBlockEntities", new ArrayList<BlockEntity>());
        setLevelFieldUnchecked(level, "blockEntityTickerLock", new Object());
        setLevelFieldUnchecked(level, "foreignBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelFieldUnchecked(level, "blockEntityTickerChunks", new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<net.minecraft.world.level.chunk.LevelChunk>());
        setLevelFieldUnchecked(level, "blockEntityTickerSequence", new java.util.concurrent.atomic.AtomicLong());
        return level;
    }

    private static TickingBlockEntity mockTicker(BlockPos pos) {
        return mockTicker(pos, "minecraft:furnace");
    }

    private static TickingBlockEntity mockTicker(BlockPos pos, String type) {
        final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
        when(ticker.getPos()).thenReturn(pos);
        when(ticker.getType()).thenReturn(type);
        return ticker;
    }

    @SuppressWarnings("unchecked")
    private static List<TickingBlockEntity> blockEntityTickers(ServerLevel level) throws ReflectiveOperationException {
        return (List<TickingBlockEntity>) getLevelField(level, "blockEntityTickers");
    }

    @SuppressWarnings("unchecked")
    private static List<TickingBlockEntity> pendingBlockEntityTickers(ServerLevel level) throws ReflectiveOperationException {
        return (List<TickingBlockEntity>) getLevelField(level, "pendingBlockEntityTickers");
    }

    @SuppressWarnings("unchecked")
    private static List<BlockEntity> freshBlockEntities(ServerLevel level) throws ReflectiveOperationException {
        return (List<BlockEntity>) getLevelField(level, "freshBlockEntities");
    }

    @SuppressWarnings("unchecked")
    private static List<BlockEntity> pendingFreshBlockEntities(ServerLevel level) throws ReflectiveOperationException {
        return (List<BlockEntity>) getLevelField(level, "pendingFreshBlockEntities");
    }

    private static boolean tickingBlockEntities(ServerLevel level) throws ReflectiveOperationException {
        return (boolean) getLevelField(level, "tickingBlockEntities");
    }

    private static Object getLevelField(ServerLevel level, String name) throws ReflectiveOperationException {
        final Field field = Level.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(level);
    }

    private static void setLevelField(ServerLevel level, String name, Object value) throws ReflectiveOperationException {
        final Field field = Level.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(level, value);
    }

    private static void setLevelFieldUnchecked(ServerLevel level, String name, Object value) {
        try {
            setLevelField(level, name, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void tickBlockEntities(ServerLevel level) throws ReflectiveOperationException {
        final Method method = Level.class.getDeclaredMethod("tickBlockEntities");
        method.setAccessible(true);
        try {
            method.invoke(level);
        } catch (InvocationTargetException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static void closeWorkerPool(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
        field.setAccessible(true);
        final Object pool = field.get(engine);
        final Method close = pool.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(pool);
    }

    private static void markRegionDead(RegionThreadingEngine engine, ServerLevel level, BlockPos pos) throws ReflectiveOperationException {
        final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
        regionizerField.setAccessible(true);
        final Object regionizer = regionizerField.get(engine);
        final Method getOrCreate = regionizer.getClass().getDeclaredMethod("getOrCreate", RegionCoordinate.class);
        getOrCreate.setAccessible(true);
        final Object region = getOrCreate.invoke(regionizer, RegionCoordinate.fromBlock(level, pos));
        assertNotNull(region, "the region was not created");
        final Method markDead = region.getClass().getDeclaredMethod("markDead");
        markDead.setAccessible(true);
        markDead.invoke(region);
    }

    private static void awaitOrFail(java.util.concurrent.CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }
}
