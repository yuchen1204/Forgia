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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the fail-fast chunk source guard of a native region worker: the refusal itself, the per entity
 * pre-load margin that keeps the common case off the chunk source, safe pre-body fallback and normal error
 * reporting when an already-started tick requests an unavailable chunk. A partial tick is never replayed.
 * <p>
 * The level is a {@link org.mockito.Mockito#CALLS_REAL_METHODS} mock, so the worker runs the real
 * {@code guardEntityTick} and {@code tickNonPassenger} code; the chunk source is a mock, so a test decides
 * which chunks are loaded without loading one.
 */
@Timeout(30)
class RegionWorkerChunkGuardTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final String WORKER_THREAD_PREFIX = "NeoForge Region Worker";

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void workerPathNavigationSeesLoadedTerrainWithoutLoadingMissingChunks() throws Exception {
        this.installEngine();
        final ServerLevel level = mockLevel(true);
        final ServerChunkCache source = level.getChunkSource();
        final ChunkMap chunkMap = mock(ChunkMap.class);
        final ChunkHolder holder = mock(ChunkHolder.class);
        final LevelChunk loaded = mock(LevelChunk.class);
        when(chunkMap.getVisibleChunkIfPresent(0L)).thenReturn(holder);
        when(holder.getChunkIfPresent(ChunkStatus.FULL)).thenReturn(loaded);
        when(loaded.getBlockState(org.mockito.ArgumentMatchers.any())).thenReturn(Blocks.STONE.defaultBlockState());
        when(loaded.getFluidState(org.mockito.ArgumentMatchers.any())).thenReturn(Blocks.WATER.defaultBlockState().getFluidState());
        for (final String name : List.of("level", "chunkMap")) {
            final Field field = ServerChunkCache.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(source, name.equals("level") ? level : chunkMap);
        }
        doReturn(-64).when(level).getMinBuildHeight();
        doReturn(384).when(level).getHeight();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final AtomicInteger completed = new AtomicInteger();
        doAnswer(invocation -> {
            assertSame(loaded, source.getChunkNow(0, 0));
            assertNull(source.getChunkNow(20, 20), "a missing chunk must not be loaded or awaited");
            final PathNavigationRegion terrain = new PathNavigationRegion(level,
                    new BlockPos(0, 60, 0), new BlockPos(15, 70, 15));
            assertTrue(terrain.getBlockState(new BlockPos(1, 63, 1)).is(Blocks.STONE),
                    "pathfinding must see the ground beneath the mob on a region worker");
            assertEquals(Blocks.WATER.defaultBlockState().getFluidState(), terrain.getFluidState(new BlockPos(1, 64, 1)));
            completed.incrementAndGet();
            return null;
        }).when(entity).tick();
        // The main-thread preflight still sees the loaded neighborhood. The worker executes the real method.
        doAnswer(invocation -> Thread.currentThread().getName().startsWith(WORKER_THREAD_PREFIX)
                ? invocation.callRealMethod()
                : loaded).when(source).getChunkNow(anyInt(), anyInt());

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(1, completed.get());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().chunkFallbackEntityTicks());
    }

    @Test
    void workerRequiredChunkLookupPreservesAnInjectedLoadedProviderBeforeRefusing() throws Exception {
        this.loadedChunkProviderCase("loaded");
    }

    @Test
    void workerRequiredChunkLookupPreservesProvidersOwnEmptyResult() throws Exception {
        this.loadedChunkProviderCase("empty");
    }

    @Test
    void unknownMissingChunkIsStillRefusedWithoutRequestingALoadOrFuture() throws Exception {
        this.loadedChunkProviderCase("missing");
    }

    @Test
    void loadedProviderExceptionPropagatesWithoutReplayingTheEntity() throws Exception {
        this.loadedChunkProviderCase("failure");
    }

    private void loadedChunkProviderCase(String mode) throws Exception {
        this.installEngine();
        final ServerLevel level = mockLevel(true);
        final var server = mock(net.minecraft.server.MinecraftServer.class);
        final Thread main = Thread.currentThread();
        when(server.isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        doReturn(server).when(level).getServer();
        final ServerChunkCache chunks = level.getChunkSource();
        final var map = mock(ChunkMap.class);
        for (String name : List.of("level", "chunkMap", "mainThread")) {
            final Field field = ServerChunkCache.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(chunks, name.equals("level") ? level : name.equals("chunkMap") ? map : main);
        }
        final int cx = 1280065;
        final int cz = 1280960;
        final LevelChunk supplied = mode.equals("empty") ? mock(net.minecraft.world.level.chunk.EmptyLevelChunk.class) : mock(LevelChunk.class);
        final AtomicInteger lookups = new AtomicInteger();
        final RuntimeException failure = new RuntimeException("provider failed");
        // Simulates a HEAD injection into the public non-loading lookup, not a vanilla visible holder.
        doAnswer(call -> {
            assertSame(main, Thread.currentThread(), "the provider owns non-thread-safe mod lookup maps");
            lookups.incrementAndGet();
            if (mode.equals("failure")) throw failure;
            return mode.equals("missing") ? null : supplied;
        }).when(chunks).getChunkNow(cx, cz);
        doCallRealMethod().when(chunks).getChunk(cx, cz, ChunkStatus.FULL, true);
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final AtomicInteger before = new AtomicInteger();
        final AtomicInteger after = new AtomicInteger();
        final var result = new java.util.concurrent.atomic.AtomicReference<net.minecraft.world.level.chunk.ChunkAccess>();
        doAnswer(call -> {
            before.incrementAndGet();
            result.set(chunks.getChunk(cx, cz, ChunkStatus.FULL, true));
            assertTrue(Thread.currentThread().getName().startsWith(WORKER_THREAD_PREFIX));
            after.incrementAndGet();
            return null;
        }).when(entity).tick();
        final var plan = plan(level);
        assertTrue(plan.accept(entity));
        if (mode.equals("missing")) {
            // A chunk refusal is truncated, not reported: the tick is lost and counted, never replayed, and
            // the class is declared server-thread-only so the next tick of it runs where the read is legal.
            plan.executeAndAwait();
            assertEquals(0, after.get());
            assertEquals(1L, this.engine.diagnostics().truncatedEntityTicks());
        } else if (mode.equals("failure")) {
            // A provider failure is not a chunk refusal: it keeps the fail-closed path and is reported.
            final var reported = assertThrows(net.minecraft.ReportedException.class, plan::executeAndAwait);
            assertEquals(0, after.get());
            assertSame(failure, reported.getCause());
        } else {
            plan.executeAndAwait();
            assertSame(supplied, result.get());
            assertEquals(1, after.get());
        }
        assertEquals(1, before.get());
        assertEquals(1, lookups.get());
        verify(chunks, org.mockito.Mockito.never()).getChunkFuture(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(chunks, org.mockito.Mockito.never()).addRegionTicket(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void unrelatedOffThreadChunkNowLookupStillReturnsNull() throws Exception {
        final ServerChunkCache source = mock(ServerChunkCache.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        final Field mainThread = ServerChunkCache.class.getDeclaredField("mainThread");
        mainThread.setAccessible(true);
        mainThread.set(source, new Thread());
        assertNull(source.getChunkNow(0, 0), "ordinary off-thread callers keep vanilla semantics");
    }

    @Test
    void refusalIsRecognizedThroughWrappingCauses() {
        final RegionWorkerChunkUnavailableException refusal = RegionWorkerChunkUnavailableException.forChunk(3, -7, ChunkStatus.FULL);

        assertTrue(RegionWorkerChunkUnavailableException.isChunkUnavailable(refusal));
        assertTrue(RegionWorkerChunkUnavailableException.isChunkUnavailable(new RuntimeException("wrapper", refusal)));
        assertTrue(RegionWorkerChunkUnavailableException.isChunkUnavailable(new RuntimeException(new IllegalStateException(refusal))));
        assertFalse(RegionWorkerChunkUnavailableException.isChunkUnavailable(new IllegalStateException("unrelated")));
        assertFalse(RegionWorkerChunkUnavailableException.isChunkUnavailable(null));
        // A cause chain that points at itself must not hang the worker that is already recovering.
        final Throwable cyclic = new Throwable("cyclic") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(RegionWorkerChunkUnavailableException.isChunkUnavailable(cyclic));
    }

    @Test
    void everyRefusedChunkRequestIsCountedOnce() {
        final long before = RegionWorkerChunkUnavailableException.refusalCount();

        final RegionWorkerChunkUnavailableException chunkRefusal = RegionWorkerChunkUnavailableException.forChunk(1, 2, ChunkStatus.FULL);
        final RegionWorkerChunkUnavailableException futureRefusal = RegionWorkerChunkUnavailableException.forChunkFuture(1, 2, ChunkStatus.FULL);

        assertEquals(before + 2L, RegionWorkerChunkUnavailableException.refusalCount());
        assertTrue(chunkRefusal.getMessage().contains("1, 2"), chunkRefusal.getMessage());
        assertTrue(futureRefusal.getMessage().contains("1, 2"), futureRefusal.getMessage());
        assertThrows(RegionWorkerChunkUnavailableException.class, () -> {
            throw chunkRefusal;
        });
    }

    @Test
    void missingChunkAfterInventoryAndDropEffectsIsTruncatedWithoutReplayingTheTick() {
        this.installEngine();
        final ServerLevel level = mockLevel(true);
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final var inventory = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
        final List<net.minecraft.world.item.ItemStack> outputs = new CopyOnWriteArrayList<>();
        final AtomicInteger pre = new AtomicInteger();
        final AtomicInteger post = new AtomicInteger();
        final java.util.function.Consumer<net.neoforged.neoforge.event.tick.EntityTickEvent.Pre> before = event -> {
            if (event.getEntity() == entity) pre.incrementAndGet();
        };
        final java.util.function.Consumer<net.neoforged.neoforge.event.tick.EntityTickEvent.Post> after = event -> {
            if (event.getEntity() == entity) post.incrementAndGet();
        };
        doAnswer(call -> {
            outputs.add(inventory.split(1));
            if (Thread.currentThread().getName().startsWith(WORKER_THREAD_PREFIX)) {
                throw RegionWorkerChunkUnavailableException.forChunk(100, 100, ChunkStatus.FULL);
            }
            return null;
        }).when(entity).tick();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre.class, before);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.EntityTickEvent.Post.class, after);
        try {
            final RegionEntityTickPlan plan = plan(level);
            assertTrue(plan.accept(entity));
            plan.executeAndAwait();
            assertEquals(1L, this.engine.diagnostics().truncatedEntityTicks(),
                    "the refused tick is lost and counted, never rerun and never pretended complete");
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(before);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(after);
        }
        assertEquals(1, inventory.getCount());
        assertEquals(1, outputs.size());
        assertEquals(1, outputs.getFirst().getCount());
        assertEquals(1, pre.get());
        assertEquals(0, post.get(), "a failed tick has no successful post event");
        verify(entity, times(1)).tick();
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void entityWhoseTickRefusesAChunkIsTruncatedWithoutReplayAndTheGroupContinues() {
        this.installEngine();
        final ServerLevel level = mockLevel(true);
        final Entity refusing = mockEntity(level, new BlockPos(1, 64, 1));
        final Entity healthy = mockEntity(level, new BlockPos(2, 64, 2));
        final List<String> refusingTickThreads = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            refusingTickThreads.add(Thread.currentThread().getName());
            if (attempts.incrementAndGet() == 1) {
                throw RegionWorkerChunkUnavailableException.forChunk(1, 1, ChunkStatus.FULL);
            }
            return null;
        }).when(refusing).tick();
        final List<String> healthyTickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            healthyTickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(healthy).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(refusing));
        assertTrue(plan.accept(healthy));
        plan.executeAndAwait();

        assertEquals(1, refusingTickThreads.size(), refusingTickThreads.toString());
        assertTrue(refusingTickThreads.getFirst().startsWith(WORKER_THREAD_PREFIX), refusingTickThreads.toString());
        verify(refusing, times(1)).tick();
        assertEquals(1, healthyTickThreads.size(), "a truncated tick does not stop the group's remaining entities");
        assertEquals(1L, this.engine.diagnostics().truncatedEntityTicks());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers(), "only the healthy entity completed a worker tick");
        assertEquals(0L, this.engine.diagnostics().chunkFallbackEntityTicks());
        assertEquals(0L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(0L, this.engine.diagnostics().fallbackEntityTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void entityWhoseNeighborhoodIsNotLoadedTicksOnTheServerThread() {
        this.installEngine();
        final ServerLevel level = mockLevel(false);
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, tickThreads.size(), tickThreads.toString());
        assertFalse(tickThreads.get(0).startsWith(WORKER_THREAD_PREFIX), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(0L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().chunkFallbackEntityTicks());
        // The region was never leased, so it is not a lease fallback either.
        assertEquals(0L, this.engine.diagnostics().fallbackEntityTicks());
    }

    @Test
    void entityWhoseNeighborhoodIsLoadedTicksOnTheWorkerWithoutRefusals() {
        this.installEngine();
        final ServerLevel level = mockLevel(true);
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, tickThreads.size(), tickThreads.toString());
        assertTrue(tickThreads.get(0).startsWith(WORKER_THREAD_PREFIX), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(0L, this.engine.diagnostics().chunkFallbackEntityTicks());
    }

    @Test
    void entityWhoseOwnNeighborhoodIsLoadedTicksOnAWorkerEvenWhenItsRegionIsNot() {
        this.installEngine();
        // The first three chunks of the region are never loaded, so the region rectangle plus its margin is
        // incomplete. The entity stands in an interior chunk whose own neighborhood is loaded, which is all
        // the per entity check asks for.
        final ServerLevel level = mockLevel((chunkX, chunkZ) -> chunkX > 2 || chunkZ > 2);
        final Entity entity = mockEntity(level, chunkCenter(8, 8));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, tickThreads.size(), tickThreads.toString());
        assertTrue(tickThreads.get(0).startsWith(WORKER_THREAD_PREFIX), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
    }

    @Test
    void edgeEntityFallsBackWhileItsRegionTicksTheRestOfItsEntitiesOnWorkers() {
        this.installEngine();
        // Exactly the chunks of the region at 0, 0 are loaded. The three interior entities have a loaded
        // neighborhood; the entity in the last chunk of the region needs the margin chunk beyond it, which
        // is not loaded.
        final ServerLevel level = mockLevel((chunkX, chunkZ) -> chunkX >= 0 && chunkX < 16 && chunkZ >= 0 && chunkZ < 16);
        final List<Entity> interior = List.of(mockEntity(level, chunkCenter(7, 7)), mockEntity(level, chunkCenter(8, 8)), mockEntity(level, chunkCenter(9, 9)));
        final Entity edge = mockEntity(level, chunkCenter(15, 15));
        final List<String> interiorThreads = new CopyOnWriteArrayList<>();
        for (final Entity entity : interior) {
            doAnswer(invocation -> {
                interiorThreads.add(Thread.currentThread().getName());
                return null;
            }).when(entity).tick();
        }
        final List<String> edgeThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            edgeThreads.add(Thread.currentThread().getName());
            return null;
        }).when(edge).tick();

        final RegionEntityTickPlan plan = plan(level);
        for (final Entity entity : interior) {
            assertTrue(plan.accept(entity));
        }
        assertTrue(plan.accept(edge));
        plan.executeAndAwait();

        // One edge entity is refused, not the four entities of its region.
        assertEquals(1L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(3L, this.engine.diagnostics().entityTicksOnWorkers());
        // The region was leased, so the edge entity is a margin refusal and not a lease fallback.
        assertEquals(0L, this.engine.diagnostics().fallbackEntityTicks());
        assertEquals(0L, this.engine.diagnostics().chunkFallbackEntityTicks());
        assertEquals(3, interiorThreads.size(), interiorThreads.toString());
        for (final String thread : interiorThreads) {
            assertTrue(thread.startsWith(WORKER_THREAD_PREFIX), interiorThreads.toString());
        }
        assertEquals(1, edgeThreads.size(), edgeThreads.toString());
        assertFalse(edgeThreads.get(0).startsWith(WORKER_THREAD_PREFIX), edgeThreads.toString());
    }

    @Test
    void edgeFallbackEntityIsTickedExactlyOnceAndItsRegionLeaseIsReleased() {
        this.installEngine();
        final ServerLevel level = mockLevel((chunkX, chunkZ) -> chunkX >= 0 && chunkX < 16 && chunkZ >= 0 && chunkZ < 16);
        final Entity interior = mockEntity(level, chunkCenter(8, 8));
        final Entity edge = mockEntity(level, chunkCenter(0, 15));
        doAnswer(invocation -> null).when(interior).tick();
        doAnswer(invocation -> null).when(edge).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(interior));
        assertTrue(plan.accept(edge));
        plan.executeAndAwait();

        // The refused entity is ticked exactly once, by the server-thread fallback, even though the rest of
        // its region ran on a worker in the same tick.
        verify(edge, times(1)).tick();
        verify(interior, times(1)).tick();
        assertEquals(1L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().totalRegionTickCount());
        // The batch completed its handoff and released the lease of the region.
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    /**
     * {@return the pre-load margin of the installed engine, which the neighborhood stubs are built from}
     */
    private static int margin() {
        return RegionThreadingEngine.entityTickChunkMargin();
    }

    private void installEngine() {
        // The worker slice mechanics are exercised directly; production keeps entity storage on the server
        // thread and therefore never submits an entity group.
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, true, false, false, false, RegionPolicy.DYNAMIC, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "entity tick worker execution must be enabled");
        return plan;
    }

    /**
     * {@return the block position at the center of chunk {@code chunkX, chunkZ}}
     */
    private static BlockPos chunkCenter(int chunkX, int chunkZ) {
        return new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8);
    }

    /**
     * Creates a level whose chunk source reports the chunks of the loaded rectangle of the dynamic region
     * at chunk {@code 0, 0} as loaded or as missing.
     * <p>
     * A {@link RegionPolicy#DYNAMIC} region is 16 chunks wide and the pre-load margin is
     * {@link RegionThreadingEngine#entityTickChunkMargin()} chunks around it, so the stub answers exactly
     * the chunks the pre-load check of an entity in that region asks for and reports every other chunk as
     * missing.
     */
    private static ServerLevel mockLevel(boolean neighborhoodLoaded) {
        final int regionChunkSize = RegionLayout.DYNAMIC.regionChunkSize();
        final int minChunk = -margin();
        final int maxChunk = regionChunkSize - 1 + margin();
        return mockLevel((chunkX, chunkZ) -> neighborhoodLoaded
                && chunkX >= minChunk && chunkX <= maxChunk && chunkZ >= minChunk && chunkZ <= maxChunk);
    }

    /**
     * Creates a level whose chunk source reports exactly the chunks that {@code loadedChunk} accepts as
     * loaded and every other chunk as missing.
     */
    private static ServerLevel mockLevel(BiPredicate<Integer, Integer> loadedChunk) {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // The seed of an entity group random source is derived from the world seed, the game time and the
        // dimension of its level.
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn("test-level").when(level).toString();
        final LevelChunk loadedChunkInstance = mock(LevelChunk.class);
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            return loadedChunk.test(chunkX, chunkZ) ? loadedChunkInstance : null;
        });
        doReturn(chunkSource).when(level).getChunkSource();
        return level;
    }

    private static Entity mockEntity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.getPassengers()).thenReturn(List.of());
        return entity;
    }
}
