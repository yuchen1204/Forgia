/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickCompatibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class BlockEntityTickAdapterTest {
    private static final ResourceLocation ID = ResourceLocation.parse("test:machines");
    private static final Set<ResourceLocation> TYPES = Set.of(ResourceLocation.parse("minecraft:hopper"));
    private static final BlockPos POS = new BlockPos(17, 64, 17);
    private static final RegionTickBarrier BARRIER = done -> {
        while (!done.getAsBoolean()) LockSupport.parkNanos(100_000);
    };
    private final Thread main = Thread.currentThread();
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerLevel level = mock(ServerLevel.class);
    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    private final LevelChunk chunk = mock(LevelChunk.class);
    private RegionThreadingEngine engine;

    @BeforeEach
    void setup() throws Exception {
        when(this.server.isSameThread()).thenAnswer(call -> Thread.currentThread() == this.main);
        when(this.level.getServer()).thenReturn(this.server);
        when(this.level.dimension()).thenReturn(Level.OVERWORLD);
        when(this.level.getProfiler()).thenReturn(InactiveProfiler.INSTANCE);
        when(this.level.areEntitiesLoaded(anyLong())).thenReturn(true);
        when(this.level.shouldTickBlocksAt(any(BlockPos.class))).thenReturn(true);
        when(this.level.tickRateManager()).thenReturn(new TickRateManager());
        when(this.level.getWorldBorder()).thenReturn(new net.minecraft.world.level.border.WorldBorder());
        final ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(this.level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(this.chunk);
        when(this.chunk.isLoaded()).thenReturn(true);
        when(this.chunk.getFullStatus()).thenReturn(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING);
        when(this.chunk.getBlockEntities()).thenReturn(this.entities);
        when(this.chunk.getBlockEntity(any(), any(LevelChunk.EntityCreationType.class))).thenAnswer(call -> this.entities.get(call.getArgument(0)));
        when(this.chunk.getBlockState(any())).thenReturn(Blocks.HOPPER.defaultBlockState());
        set(LevelChunk.class, this.chunk, "level", this.level);
        this.engine = new RegionThreadingEngine(2, false, false, false, true, false, false, false,
                Boolean.getBoolean("forgia.furnace.persistentWorkers"), false,
                net.neoforged.neoforge.server.threading.region.RegionLayout.DYNAMIC,
                net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode.MAIN_THREAD_ONLY);
        this.engine.setBlockEntityAdmissionCacheEnabledForTesting(false);
        RegionThreadingManager.install(this.engine);
    }

    @AfterEach
    void close() {
        RegionThreadingManager.clear();
        ParallelAdapterRuntime.close(this.server);
        RegionTickCompatibility.declareServerThreadOnlyTypes(Map.of());
    }

    private void register(BlockEntityTickAdapter adapter) {
        ParallelAdapterRuntime.initialize(this.server, registrar -> registrar.registerBlockEntityTicks(1, ID, TYPES, adapter));
    }

    private BlockEntityTickAdapter.Status status() {
        return ParallelApi.forServer(this.server).orElseThrow().blockEntityTickAdapters().getFirst();
    }

    @Test
    void registrationFreezesInvalidatesAndRejectsDuplicateTypesAcrossAdapters() {
        this.register(be -> BlockEntityTickAdapter.Decision.ADMITTED);
        final var service = ParallelApi.forServer(this.server).orElseThrow();
        assertTrue(service.capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_TICK_ADMISSION));
        assertThrows(IllegalStateException.class, () -> ((ParallelApi.Registrar) service).registerBlockEntityTicks(1, ID, TYPES, be -> null));
        ParallelAdapterRuntime.close(this.server);
        assertThrows(IllegalStateException.class, service::blockEntityTickAdapters);
        assertThrows(IllegalStateException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.registerBlockEntityTicks(1, ID, TYPES, be -> BlockEntityTickAdapter.Decision.ADMITTED);
            assertThrows(IllegalArgumentException.class, () -> registrar.registerBlockEntityTicks(1,
                    ResourceLocation.parse("test:other"), TYPES, be -> BlockEntityTickAdapter.Decision.ADMITTED));
        }));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void auditedAdaptersInDifferentRegionsOverlapAndEveryOriginalTickerRunsOnce() throws Exception {
        final var prepares = new AtomicInteger();
        this.register(new BlockEntityTickAdapter() {
            @Override
            public void prepare(ServerLevel level) {
                prepares.incrementAndGet();
            }

            @Override
            public Decision check(BlockEntity source) {
                assertNotEquals(BlockEntityTickAdapterTest.this.main, Thread.currentThread());
                return Decision.ADMITTED;
            }
        });
        final var rendezvous = new java.util.concurrent.CyclicBarrier(2);
        final var bodies = new AtomicInteger();
        final Runnable body = () -> {
            assertEquals("REGION_OWNER", BlockEntityExecutionEngineTest.currentMode());
            try {
                rendezvous.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            bodies.incrementAndGet();
        };
        this.tick(this.ticker(POS, body), this.ticker(new BlockPos(4113, 64, 17), body));
        assertEquals(1, prepares.get());
        assertEquals(2, bodies.get());
        assertEquals(2, this.status().checks());
        assertEquals(2, this.status().workerCompletions());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void admissionIsReevaluatedForEachInvocationIncludingAlreadyWhitelistedVanillaTypes() throws Exception {
        final AtomicReference<BlockEntityTickAdapter.Decision> decision = new AtomicReference<>(BlockEntityTickAdapter.Decision.ADMITTED);
        this.register(be -> decision.get());
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final TickingBlockEntity ticker = this.ticker(POS, () -> {
            ticks.incrementAndGet();
            thread.set(Thread.currentThread());
        });
        this.tick(ticker);
        assertNotEquals(this.main, thread.get());
        decision.set(BlockEntityTickAdapter.Decision.CALLBACK_REQUIRED);
        this.tick(ticker);
        assertSame(this.main, thread.get());
        decision.set(BlockEntityTickAdapter.Decision.ADMITTED);
        this.tick(ticker);
        assertNotEquals(this.main, thread.get());
        assertEquals(3, ticks.get());
        assertEquals(3, this.status().checks());
        assertEquals(1, this.status().refusals());
        assertEquals(2, this.status().workerCompletions());
        assertEquals(1, this.status().reasons().get(BlockEntityTickAdapter.Decision.CALLBACK_REQUIRED));
    }

    @Test
    void serverOnlyDeclarationAndBoundaryGeometryCannotBeWidenedByAnAdapter() throws Exception {
        this.register(be -> {
            throw new AssertionError("predicate must never be called");
        });
        final AtomicReference<Thread> thread = new AtomicReference<>();
        RegionTickCompatibility.declareServerThreadOnlyTypes(Map.of("minecraft:hopper", "test pin"));
        this.tick(this.ticker(POS, () -> thread.set(Thread.currentThread())));
        assertSame(this.main, thread.get());
        RegionTickCompatibility.declareServerThreadOnlyTypes(Map.of());
        this.tick(this.ticker(new BlockPos(0, 64, 17), () -> thread.set(Thread.currentThread())));
        assertSame(this.main, thread.get());
        assertEquals(0, this.status().checks());
    }

    @Test
    void samePositionReplacementIsNotPassedToTheOriginalTickersPredicate() throws Exception {
        this.register(be -> {
            throw new AssertionError("replacement is not the captured source");
        });
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final TickingBlockEntity ticker = this.ticker(POS, () -> thread.set(Thread.currentThread()));
        final HopperBlockEntity replacement = new HopperBlockEntity(POS, Blocks.HOPPER.defaultBlockState());
        replacement.setLevel(this.level);
        this.entities.put(POS, replacement);
        this.tick(ticker);
        assertSame(this.main, thread.get());
        assertEquals(1, this.status().reasons().get(BlockEntityTickAdapter.Decision.INVALID_INSTANCE));
        assertEquals(0, this.status().workerCompletions());
    }

    @Test
    void predicateFailureDoesNotInvokeOrReplayTheBody() throws Exception {
        final IllegalStateException failure = new IllegalStateException("admission failed");
        this.register(be -> {
            throw failure;
        });
        final AtomicInteger ticks = new AtomicInteger();
        final TickingBlockEntity ticker = this.ticker(POS, ticks::incrementAndGet);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> this.tick(ticker)));
        assertEquals(0, ticks.get());
        assertEquals(0, this.status().workerCompletions());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void bodyFailureAfterEffectsIsNeverReplayedOrCountedAsCompleted() throws Exception {
        this.register(be -> BlockEntityTickAdapter.Decision.ADMITTED);
        final AtomicInteger effects = new AtomicInteger();
        final TickingBlockEntity ticker = this.ticker(POS, () -> {
            effects.incrementAndGet();
            throw new IllegalStateException("body failed");
        });
        assertThrows(RuntimeException.class, () -> this.tick(ticker));
        assertEquals(1, effects.get());
        assertEquals(0, this.status().workerCompletions());
    }

    @Test
    void stoppedServiceRefusesPreviouslyCollectedCandidacyBeforeTheBody() throws Exception {
        this.register(be -> {
            throw new AssertionError("retired adapter");
        });
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(this.level, BARRIER);
        assertNotNull(plan);
        assertTrue(plan.accept(this.ticker(POS, () -> thread.set(Thread.currentThread()))));
        ParallelAdapterRuntime.close(this.server);
        plan.executeAndAwait();
        assertSame(this.main, thread.get());
        assertEquals(0, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void shutdownAfterCollectionKeepsTheCompleteSerialBodyWithoutAdapterPreflight() throws Exception {
        this.register(new BlockEntityTickAdapter() {
            @Override
            public void prepare(ServerLevel preparedLevel) {
                throw new AssertionError("shutdown must not prepare work");
            }

            @Override
            public Decision check(BlockEntity entity) {
                throw new AssertionError("shutdown must not admit work");
            }
        });
        final var thread = new AtomicReference<Thread>();
        final var plan = this.engine.beginBlockEntityTickPlan(this.level, BARRIER);
        assertNotNull(plan);
        assertTrue(plan.accept(this.ticker(POS, () -> thread.set(Thread.currentThread()))));
        this.engine.shutdown();
        plan.executeAndAwait();
        assertSame(this.main, thread.get());
        assertEquals(0, this.status().checks());
        assertEquals(0, this.status().workerCompletions());
    }

    @Test
    void preflightRunsOnceOnTheQuiescentServerBeforeAnyInvocation() throws Exception {
        final AtomicInteger prepares = new AtomicInteger();
        this.register(new BlockEntityTickAdapter() {
            @Override
            public void prepare(ServerLevel preparedLevel) {
                assertSame(BlockEntityTickAdapterTest.this.level, preparedLevel);
                assertSame(BlockEntityTickAdapterTest.this.main, Thread.currentThread());
                assertEquals(ParallelApi.Phase.QUIESCENT_SERVER,
                        ParallelApi.forServer(BlockEntityTickAdapterTest.this.server).orElseThrow().currentPhase());
                prepares.incrementAndGet();
            }

            @Override
            public Decision check(BlockEntity entity) {
                assertEquals(1, prepares.get());
                return Decision.ADMITTED;
            }
        });
        final AtomicInteger bodies = new AtomicInteger();
        this.tick(this.ticker(POS, bodies::incrementAndGet));
        assertEquals(1, prepares.get());
        assertEquals(1, bodies.get());
        assertTrue(this.status().preparationNanos() > 0);
    }

    @Test
    void failingPreflightDoesNotPublishABatchOrRunAnyOriginalBody() throws Exception {
        final IllegalStateException failure = new IllegalStateException("preflight failed");
        this.register(new BlockEntityTickAdapter() {
            @Override
            public void prepare(ServerLevel preparedLevel) {
                throw failure;
            }

            @Override
            public Decision check(BlockEntity entity) {
                throw new AssertionError("no body admission after failure");
            }
        });
        final AtomicInteger bodies = new AtomicInteger();
        final var ticker = this.ticker(POS, bodies::incrementAndGet);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> this.tick(ticker)));
        assertEquals(0, bodies.get());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.status().checks());
    }

    private TickingBlockEntity ticker(BlockPos pos, Runnable body) throws Exception {
        final HopperBlockEntity entity = new HopperBlockEntity(pos, Blocks.HOPPER.defaultBlockState());
        entity.setLevel(this.level);
        this.entities.put(pos, entity);
        final var factory = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
        factory.setAccessible(true);
        return (TickingBlockEntity) factory.invoke(this.chunk, entity, (BlockEntityTicker<HopperBlockEntity>) (level, at, state, source) -> body.run());
    }

    private void tick(TickingBlockEntity... tickers) {
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(this.level, BARRIER);
        assertNotNull(plan);
        for (final var ticker : tickers) assertTrue(plan.accept(ticker));
        plan.executeAndAwait();
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
