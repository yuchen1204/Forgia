/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus.SerialReason;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Production admission, with real vanilla lamp callbacks and real LevelTicks rather than the test-only gate. */
@Timeout(40)
class RegionScheduledTickAdmissionTest {
    private static final BlockPos FIRST = new BlockPos(17, 64, 8);
    private static final BlockPos SECOND = new BlockPos(65, 64, 8);
    private static final RegionTickBarrier BARRIER = done -> {
        while (!done.getAsBoolean()) LockSupport.parkNanos(100_000L);
    };
    private RegionThreadingEngine engine;

    @AfterEach
    void close() {
        RegionThreadingManager.clear();
    }

    private RegionRedstoneWorld world() {
        final RegionRedstoneWorld world = new RegionRedstoneWorld(new AtomicLong());
        final ServerChunkCache chunks = world.level.getChunkSource();
        final LevelChunk loaded = mock(LevelChunk.class);
        doReturn(true).when(loaded).isLoaded();
        doAnswer(call -> world.states.getOrDefault(((BlockPos) call.getArgument(0)).immutable(), Blocks.AIR.defaultBlockState()))
                .when(loaded).getBlockState(any(BlockPos.class));
        doReturn(loaded).when(chunks).getChunkNow(anyInt(), anyInt());
        return world;
    }

    private void install() {
        this.engine = new RegionThreadingEngine(2, false, false, false, false, true, false,
                RegionLayout.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        RegionThreadingManager.install(this.engine);
    }

    private static void lamp(RegionRedstoneWorld world, BlockPos position) {
        world.level.setBlock(position, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true), 2);
    }

    private void run(RegionRedstoneWorld world, long time, BiConsumer<BlockPos, Block> body) {
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(world.level, ScheduledTickPhase.BLOCK, BARRIER);
        assertNotNull(dispatcher);
        world.blockTicks.tick(time, 65536, body, body, dispatcher);
    }

    @Test
    void admittedLampRegionsActuallyExecuteConcurrently() {
        final RegionRedstoneWorld world = this.world();
        lamp(world, FIRST);
        lamp(world, SECOND);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, 1L, 0L));
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, SECOND, 1L, 1L));
        this.install();
        final CyclicBarrier concurrent = new CyclicBarrier(2);
        final List<Boolean> workers = new CopyOnWriteArrayList<>();
        this.run(world, 1L, (pos, block) -> {
            workers.add(RegionThreadingHooks.isNativeRegionWorkerThread());
            try {
                concurrent.await(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            world.level.getBlockState(pos).tick(world.level, pos, world.scheduledRandom);
        });
        assertEquals(List.of(true, true), workers);
        assertFalse(world.level.getBlockState(FIRST).getValue(BlockStateProperties.LIT));
        assertFalse(world.level.getBlockState(SECOND).getValue(BlockStateProperties.LIT));
        final ScheduledTickStatus status = this.engine.scheduledTickStatus().getFirst();
        assertEquals(2L, status.collected());
        assertEquals(2L, status.admitted());
        assertEquals(2L, status.workerCompletions());
        assertEquals(0L, status.serialCompletions());
        assertEquals(1, status.latency().samples());
        assertTrue(status.timings().preparationNanos() > 0L);
        assertTrue(status.timings().workerNanos() > 0L);
        assertTrue(status.timings().dispatchAwaitNanos() > 0L);
        assertTrue(status.timings().phaseNanos() >= status.timings().dispatchAwaitNanos());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void realLampStateTraceAndSharedRandomSequenceMatchSerialAcrossTicks() {
        final List<String> serial = this.trace(false);
        final List<String> workers = this.trace(true);
        assertEquals(serial, workers);
    }

    private List<String> trace(boolean parallel) {
        RegionThreadingManager.clear();
        final RegionRedstoneWorld world = this.world();
        lamp(world, FIRST);
        lamp(world, SECOND);
        world.level.setBlock(SECOND.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 2);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, 1L, 0L));
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, SECOND, 1L, 1L));
        final RandomSource random = RandomSource.create(4321L);
        assertEquals(random.nextLong(), world.level.random.nextLong());
        if (parallel) this.install();
        final List<String> states = new ArrayList<>();
        for (int tick = 1; tick <= 8; ++tick) {
            if (tick == 2) world.level.setBlock(SECOND.west(), Blocks.AIR.defaultBlockState(), 3);
            if (parallel) this.run(world, tick, (pos, block) -> world.level.getBlockState(pos).tick(world.level, pos, world.scheduledRandom));
            else world.blockTicks.tick(tick, 65536, (pos, block) -> world.level.getBlockState(pos).tick(world.level, pos, world.scheduledRandom));
            states.add(world.level.getBlockState(FIRST) + "/" + world.level.getBlockState(SECOND) + "/" + world.pendingTicks());
        }
        assertEquals(random.nextLong(), world.level.random.nextLong(), "dispatching a no-RNG callback must not consume the shared stream");
        RegionThreadingManager.clear();
        return states;
    }

    @Test
    void unknownCallbackRetainsLiveQueueAndClearAreaSemantics() {
        final RegionRedstoneWorld world = this.world();
        this.install();
        final LevelTicks<Block> ticks = new LevelTicks<>(key -> true, () -> InactiveProfiler.INSTANCE);
        ticks.addContainer(new ChunkPos(FIRST), new LevelChunkTicks<>());
        final BlockPos next = FIRST.east();
        ticks.schedule(new ScheduledTick<>(Blocks.STONE, FIRST, 1L, TickPriority.HIGH, 0L));
        ticks.schedule(new ScheduledTick<>(Blocks.STONE, next, 1L, TickPriority.LOW, 1L));
        final List<BlockPos> trace = new ArrayList<>();
        ticks.tick(1L, 100, (pos, type) -> {
            assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread());
            trace.add(pos);
            assertFalse(ticks.willTickThisTick(FIRST, Blocks.STONE));
            assertTrue(ticks.willTickThisTick(next, Blocks.STONE));
            ticks.clearArea(new BoundingBox(next));
            // Vanilla removes the callback from the live queue but retains its lazily built predicate
            // set until cleanup. Preserve that observable answer as well as suppressing its callback.
            assertTrue(ticks.willTickThisTick(next, Blocks.STONE));
        }, this.engine.beginScheduledTickDispatcher(world.level, ScheduledTickPhase.BLOCK, BARRIER));
        assertEquals(List.of(FIRST), trace, "preflight refusal must not detach ticks from the queue a callback clears");
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().serialCompletions());
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().skippedCallbacks());
        assertEquals(0L, this.engine.scheduledTickStatus().getFirst().admitted());
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().serialReasons().get(SerialReason.UNCLASSIFIED_CALLBACK));
    }

    @Test
    void mixedUnknownPhaseNeverMovesAKnownTickAheadOfTheSerialOrder() {
        final RegionRedstoneWorld world = this.world();
        lamp(world, FIRST);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, 1L, TickPriority.LOW, 0L));
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.STONE, SECOND, 1L, TickPriority.HIGH, 1L));
        this.install();
        final List<Block> order = new ArrayList<>();
        this.run(world, 1L, (pos, type) -> {
            assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread());
            order.add(type);
        });
        assertEquals(List.of(Blocks.STONE, Blocks.REDSTONE_LAMP), order);
        assertEquals(0L, this.engine.scheduledTickStatus().getFirst().workerCompletions());
    }

    @Test
    void unsafeNeighborAndForeignFootprintArePreBodySerialDecisions() {
        final RegionRedstoneWorld world = this.world();
        lamp(world, FIRST);
        world.level.setBlock(FIRST.east(), Blocks.HOPPER.defaultBlockState(), 2);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, 1L, 0L));
        this.install();
        this.run(world, 1L, (pos, type) -> assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread()));
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().serialReasons().get(SerialReason.UNSAFE_NEIGHBOR));
        final BlockPos boundary = new BlockPos(47, 64, 8);
        lamp(world, boundary);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, boundary, 2L, 0L));
        this.run(world, 2L, (pos, type) -> assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread()));
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().serialReasons().get(SerialReason.FOREIGN_FOOTPRINT));
    }

    @Test
    void actualChunkReadinessAndBudgetAreCheckedBeforeDispatch() {
        final RegionRedstoneWorld world = this.world();
        lamp(world, FIRST);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, 1L, 0L));
        final ServerChunkCache chunks = world.level.getChunkSource();
        doReturn(null).when(chunks).getChunkNow(0, 0);
        this.install();
        this.run(world, 1L, (pos, type) -> assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread()));
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().serialReasons().get(SerialReason.UNLOADED_FOOTPRINT));
        this.engine.setDeferredBlockUpdateBudget(1);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, 2L, 0L));
        this.run(world, 2L, (pos, type) -> assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread()));
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().serialReasons().get(SerialReason.DEFERRED_UPDATE_BUDGET));
    }

    @Test
    void profilerActivatedAfterDispatcherCreationKeepsTheOriginalSerialPhase() {
        final RegionRedstoneWorld world = this.world();
        lamp(world, FIRST);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, 1L, 0L));
        this.install();
        final var dispatcher = this.engine.<Block>beginScheduledTickDispatcher(world.level, ScheduledTickPhase.BLOCK, BARRIER);
        doReturn(mock(net.minecraft.util.profiling.ProfilerFiller.class)).when(world.level).getProfiler();
        world.blockTicks.tick(1L, 100, (pos, type) -> assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread()), dispatcher);
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().serialReasons().get(SerialReason.PROFILER_ACTIVE));
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().serialCompletions());
    }

    @Test
    void failedScheduledCallbackCountsTheUnstartedRemainderSeparately() {
        final RegionRedstoneWorld world = this.world();
        final BlockPos next = FIRST.east();
        lamp(world, FIRST);
        lamp(world, next);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, 1L, 0L));
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, next, 1L, 1L));
        this.install();
        assertThrows(IllegalStateException.class, () -> this.run(world, 1L, (pos, type) -> {
            throw new IllegalStateException("callback failed");
        }));
        final var status = this.engine.scheduledTickStatus().getFirst();
        assertEquals(2L, status.collected());
        assertEquals(1L, status.failedCallbacks());
        assertEquals(1L, status.skippedCallbacks());
        assertEquals(0L, status.workerCompletions());
    }

    @Test
    void fluidCallbacksHaveTheirOwnSerialPhaseCounters() {
        final RegionRedstoneWorld world = this.world();
        this.install();
        final LevelTicks<net.minecraft.world.level.material.Fluid> fluids = new LevelTicks<>(key -> true, () -> InactiveProfiler.INSTANCE);
        fluids.addContainer(new ChunkPos(FIRST), new LevelChunkTicks<>());
        fluids.schedule(new ScheduledTick<>(net.minecraft.world.level.material.Fluids.WATER, FIRST, 1L, 0L));
        fluids.tick(1L, 100, (pos, fluid) -> assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread()),
                this.engine.beginScheduledTickDispatcher(world.level, ScheduledTickPhase.FLUID, BARRIER));
        final var status = this.engine.scheduledTickStatus().getLast();
        assertEquals(1L, status.collected());
        assertEquals(1L, status.serialCompletions());
        assertEquals(1L, status.serialReasons().get(SerialReason.FLUID_CALLBACK));
        assertEquals(0L, status.workerCompletions());
        assertEquals(0L, this.engine.scheduledTickStatus().getFirst().collected());
    }

    @Test
    void latencyWindowStaysBoundedAfterManyCompletedPhases() {
        final RegionRedstoneWorld world = this.world();
        lamp(world, FIRST);
        this.install();
        for (int tick = 1; tick <= 140; ++tick) {
            world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, tick, tick));
            this.run(world, tick, (pos, block) -> world.level.getBlockState(pos).tick(world.level, pos, world.scheduledRandom));
        }
        final var status = this.engine.scheduledTickStatus().getFirst();
        assertEquals(140L, status.phases());
        assertEquals(140L, status.workerCompletions());
        assertEquals(128, status.latency().samples());
        assertTrue(status.latency().p50Nanos() > 0L);
        assertTrue(status.latency().p95Nanos() >= status.latency().p50Nanos());
        assertTrue(status.latency().p99Nanos() >= status.latency().p95Nanos());
        assertThrows(UnsupportedOperationException.class, () -> status.serialReasons().put(SerialReason.FLUID_CALLBACK, 1L));
    }

    @Test
    void unstableReadAfterAnEffectIsReportedOnceAndNeverCountedAsCompletion() {
        final RegionRedstoneWorld world = this.world();
        lamp(world, FIRST);
        world.blockTicks.schedule(new ScheduledTick<>(Blocks.REDSTONE_LAMP, FIRST, 1L, 0L));
        this.install();
        final AtomicInteger effects = new AtomicInteger();
        final var failure = RegionWorkerChunkUnavailableException.forUnstableRead(4, 0);
        assertSame(failure, assertThrows(RegionWorkerChunkUnavailableException.class, () -> this.run(world, 1L, (pos, type) -> {
            effects.incrementAndGet();
            throw failure;
        })));
        assertEquals(1, effects.get());
        assertEquals(1L, this.engine.scheduledTickStatus().getFirst().failedCallbacks());
        assertEquals(0L, this.engine.scheduledTickStatus().getFirst().workerCompletions());
        assertEquals(0L, this.engine.scheduledTickStatus().getFirst().serialCompletions());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }
}
