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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityWorkGraph;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real engine batches and real bound tickers/chunk reference lookup; the ticker bodies vary by scenario. */
@Timeout(60)
class RegionBlockEntityWorkGraphTest {
    private static final BlockPos FIRST = new BlockPos(17, 64, 17);
    private static final BlockPos SECOND = new BlockPos(18, 64, 17);
    private static final RegionTickBarrier BARRIER = done -> {
        while (!done.getAsBoolean()) LockSupport.parkNanos(100_000L);
    };
    private final ServerLevel level = mock(ServerLevel.class);
    private final LevelChunk chunk = mock(LevelChunk.class, CALLS_REAL_METHODS);
    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    private RegionThreadingEngine engine;

    @BeforeEach
    void setup() throws Exception {
        when(this.level.dimension()).thenReturn(Level.OVERWORLD);
        when(this.level.getProfiler()).thenReturn(InactiveProfiler.INSTANCE);
        when(this.level.areEntitiesLoaded(anyLong())).thenReturn(true);
        when(this.level.shouldTickBlocksAt(any(BlockPos.class))).thenReturn(true);
        when(this.level.tickRateManager()).thenReturn(new TickRateManager());
        when(this.level.getWorldBorder()).thenReturn(new net.minecraft.world.level.border.WorldBorder());
        final ServerChunkCache source = mock(ServerChunkCache.class);
        when(this.level.getChunkSource()).thenReturn(source);
        when(source.getChunkNow(anyInt(), anyInt())).thenReturn(this.chunk);
        set(LevelChunk.class, this.chunk, "level", this.level);
        set(net.minecraft.world.level.chunk.ChunkAccess.class, this.chunk, "blockEntities", this.entities);
        set(net.minecraft.world.level.chunk.ChunkAccess.class, this.chunk, "pendingBlockEntities", new HashMap<>());
        doReturn(this.level).when(this.chunk).getLevel();
        doReturn(new ChunkPos(1, 1)).when(this.chunk).getPos();
        doReturn(true).when(this.chunk).isLoaded();
        doReturn(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING).when(this.chunk).getFullStatus();
        doReturn(Blocks.HOPPER.defaultBlockState()).when(this.chunk).getBlockState(any(BlockPos.class));
        this.engine = new RegionThreadingEngine(2, false, false, false, true, false, false,
                RegionLayout.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        this.engine.setBlockEntityAdmissionCacheEnabledForTesting(false);
        RegionThreadingManager.install(this.engine);
    }

    @AfterEach
    void close() {
        RegionThreadingManager.clear();
    }

    private RegionBlockEntityWorkGraph graph() {
        return this.engine.blockEntityWorkGraph();
    }

    private RegionBlockEntityWorkGraph.Summary latest() {
        return this.graph().snapshot().latest().orElseThrow();
    }

    @Test
    void observationIsOffUntilRequestedAndStopsAfterTheBoundedNumberOfPhases() throws Exception {
        final var calls = new AtomicInteger();
        final var ticker = this.ticker(FIRST, calls::incrementAndGet);
        this.tick(ticker);
        assertTrue(this.graph().snapshot().latest().isEmpty());
        this.graph().start(2);
        this.tick(ticker);
        this.tick(ticker);
        final var last = this.latest();
        this.tick(ticker);
        assertSame(last, this.latest());
        assertEquals(4, calls.get());
        assertEquals(2, this.graph().snapshot().completedPhases());
        assertEquals(0, this.graph().snapshot().remainingPhases());
        assertThrows(IllegalArgumentException.class, () -> this.graph().start(201));
    }

    @Test
    void realBoundTickersInOneRegionProduceAWideObservedPlanWithoutChangingTheirExecution() throws Exception {
        this.graph().start(1);
        final var first = this.ticker(FIRST, () -> {});
        final var second = this.ticker(SECOND, () -> {});
        this.tick(first, second);
        assertEquals(2, this.latest().nativeInvocations());
        assertEquals(2, this.latest().invokedUnits());
        assertEquals(1, this.latest().waveDepth());
        assertEquals(2, this.latest().maxWaveWidth());
        assertEquals(2, this.latest().components());
        assertEquals(this.engine.regionLayout().regionAt(this.level, FIRST), this.engine.regionLayout().regionAt(this.level, SECOND));
    }

    @Test
    void realChunkGetBlockEntityRecordsPotentialContainerWritesAndOrdersTheLaterOwner() throws Exception {
        this.graph().start(1);
        final var target = this.ticker(SECOND, () -> {});
        final var source = this.ticker(FIRST, () -> assertNotNull(this.chunk.getBlockEntity(SECOND, LevelChunk.EntityCreationType.CHECK)));
        this.tick(source, target);
        assertEquals(2, this.latest().waveDepth());
        assertEquals(1, this.latest().maxWaveWidth());
        assertEquals(1, this.latest().components());
        assertEquals(2, this.latest().largestComponent());
        assertEquals(1, this.latest().dependencyLinks());
        assertEquals(0, this.latest().failedInvocations());
    }

    @Test
    void mainThreadFallbackFormsAnExclusiveFenceAndStillRunsExactlyOnce() {
        this.graph().start(1);
        final List<Thread> threads = new ArrayList<>();
        final var ticker = this.mockTicker(FIRST, "example:unknown", () -> threads.add(Thread.currentThread()));
        this.tick(ticker);
        assertEquals(List.of(Thread.currentThread()), threads);
        assertEquals(1, this.latest().serialInvocations());
        assertEquals(0, this.latest().nativeInvocations());
        assertEquals(1, this.latest().exclusiveUnits());
    }

    @Test
    void referenceOverflowFencesTheInvocationAndReportsMissingCoverage() throws Exception {
        this.graph().start(1);
        final var ticker = this.ticker(FIRST, () -> {
            for (int index = 0; index < 100; ++index) RegionThreadingHooks.observeBlockEntityReference(this.level, new BlockPos(index, 66, 20));
        });
        this.tick(ticker);
        assertEquals(1, this.latest().overflowedUnits());
        assertEquals(1, this.latest().exclusiveUnits());
        assertEquals(RegionBlockEntityWorkGraph.MAX_RESOURCES_PER_UNIT, this.latest().resources());
    }

    @Test
    void tooManyCandidatesRemainBoundedAndNeverBecomeAnApparentlyCompleteParallelSet() {
        this.graph().start(1);
        final List<TickingBlockEntity> entries = new ArrayList<>();
        final var calls = new AtomicInteger();
        for (int index = 0; index < RegionBlockEntityWorkGraph.MAX_UNITS + 5; ++index) {
            entries.add(this.mockTicker(new BlockPos(17, index, 17), "minecraft:furnace", calls::incrementAndGet));
        }
        this.tick(entries.toArray(TickingBlockEntity[]::new));
        assertEquals(RegionBlockEntityWorkGraph.MAX_UNITS + 5, calls.get());
        assertEquals(5, this.latest().omittedUnits());
        assertEquals(RegionBlockEntityWorkGraph.MAX_UNITS, this.latest().plannedUnits());
        assertEquals(1, this.latest().maxWaveWidth());
    }

    @Test
    void startedFailureIsRecordedWithoutReplayAndThreadLocalObservationIsRestored() {
        this.graph().start(1);
        final IllegalStateException failure = new IllegalStateException("original effect then failure");
        final var calls = new AtomicInteger();
        final var ticker = this.mockTicker(FIRST, "minecraft:furnace", () -> {
            calls.incrementAndGet();
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> this.tick(ticker)));
        assertEquals(1, calls.get());
        assertEquals(1, this.latest().failedInvocations());
        assertEquals(1, this.latest().exclusiveUnits());
        final var snapshot = this.latest();
        RegionThreadingHooks.observeBlockEntityReference(this.level, SECOND);
        assertSame(snapshot, this.latest());
        this.graph().start(1);
        this.tick(this.mockTicker(SECOND, "minecraft:furnace", () -> {}));
        assertEquals(0, this.latest().failedInvocations());
        assertEquals(0, this.latest().overflowedUnits());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void stopFromInsideAnInvocationRetiresOnlyItsObservationNotItsOriginalWork() {
        this.graph().start(10);
        final var calls = new AtomicInteger();
        this.tick(this.mockTicker(FIRST, "minecraft:furnace", () -> {
            this.graph().stop();
            calls.incrementAndGet();
        }));
        assertEquals(1, calls.get());
        assertTrue(this.graph().snapshot().latest().isEmpty());
        assertEquals(0, this.graph().snapshot().remainingPhases());
        this.graph().start(1);
        this.tick(this.mockTicker(FIRST, "minecraft:furnace", calls::incrementAndGet));
        assertEquals(1, this.graph().snapshot().completedPhases());
        assertEquals(2, calls.get());
    }

    @Test
    void unknownPublicationFencesTheObservedInvocationAndDisablingDoesNotDropWork() throws Exception {
        this.graph().start(1);
        this.tick(this.ticker(FIRST, RegionBlockEntityWorkGraph::opaqueEffect));
        assertEquals(1, this.latest().exclusiveUnits());
        this.engine.shutdown();
        assertEquals(0, this.graph().snapshot().remainingPhases());
        assertFalse(this.latest().dimension().isEmpty());
    }

    private TickingBlockEntity ticker(BlockPos position, Runnable body) throws Exception {
        final var entity = new HopperBlockEntity(position, Blocks.HOPPER.defaultBlockState());
        entity.setLevel(this.level);
        this.entities.put(position, entity);
        final var factory = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
        factory.setAccessible(true);
        return (TickingBlockEntity) factory.invoke(this.chunk, entity,
                (BlockEntityTicker<HopperBlockEntity>) (level, pos, state, source) -> body.run());
    }

    private TickingBlockEntity mockTicker(BlockPos pos, String type, Runnable body) {
        final var ticker = mock(TickingBlockEntity.class);
        when(ticker.getPos()).thenReturn(pos);
        when(ticker.getType()).thenReturn(type);
        org.mockito.Mockito.doAnswer(call -> {
            body.run();
            return null;
        }).when(ticker).tick();
        return ticker;
    }

    private void tick(TickingBlockEntity... entries) {
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(this.level, BARRIER);
        assertNotNull(plan);
        for (final var ticker : entries) assertTrue(plan.accept(ticker));
        plan.executeAndAwait();
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
