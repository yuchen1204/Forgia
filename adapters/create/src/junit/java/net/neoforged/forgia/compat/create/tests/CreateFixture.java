/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.tests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.forgia.compat.create.mixin.TorquePropagatorAccessor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickChunkCache;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;

/** Real transformed Create BEs and Minecraft bound tickers; world/chunk services are test doubles. */
final class CreateFixture implements AutoCloseable {
    static final RegionTickBarrier BARRIER = done -> {
        final long deadline = System.nanoTime() + 15_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("region batch exceeded test deadline");
            LockSupport.parkNanos(100_000);
        }
    };
    final Thread main = Thread.currentThread();
    final MinecraftServer server = mock(MinecraftServer.class);
    final ServerLevel level = mock(ServerLevel.class);
    final RegionThreadingEngine engine;
    final Map<Long, LevelChunk> chunks = new HashMap<>();
    final Map<Long, List<TickingBlockEntity>> tickers = new HashMap<>();
    final Map<Long, AtomicReference<RegionBlockEntityTickChunkCache>> caches = new HashMap<>();

    CreateFixture() {
        this(3);
    }

    CreateFixture(int regionChunks) {
        this.engine = new RegionThreadingEngine(2, false, false, false, true, false, false,
                RegionLayout.fixed(regionChunks), RegionEntityStorageMode.MAIN_THREAD_ONLY);
        when(this.server.isSameThread()).thenAnswer(call -> Thread.currentThread() == this.main);
        when(this.level.getServer()).thenReturn(this.server);
        when(this.level.dimension()).thenReturn(Level.OVERWORLD);
        when(this.level.getProfiler()).thenReturn(InactiveProfiler.INSTANCE);
        when(this.level.areEntitiesLoaded(anyLong())).thenReturn(true);
        when(this.level.shouldTickBlocksAt(any(BlockPos.class))).thenReturn(true);
        when(this.level.shouldTickBlocksAt(anyLong())).thenReturn(true);
        when(this.level.getWorldBorder()).thenReturn(new net.minecraft.world.level.border.WorldBorder());
        when(this.level.entitySectionRegionChunkSize()).thenReturn(regionChunks);
        when(this.level.tickRateManager()).thenReturn(new TickRateManager());
        set(Level.class, this.level, "random", RandomSource.create(12345));
        final ServerChunkCache source = mock(ServerChunkCache.class);
        when(this.level.getChunkSource()).thenReturn(source);
        when(source.getChunkNow(anyInt(), anyInt())).thenAnswer(call -> this.chunks.get(ChunkPos.asLong(call.getArgument(0), call.getArgument(1))));
        when(this.level.getBlockEntity(any())).thenAnswer(call -> {
            final BlockPos pos = call.getArgument(0);
            final var chunk = this.chunks.get(ChunkPos.asLong(pos));
            return chunk == null ? null : chunk.getBlockEntities().get(pos);
        });
        when(this.level.getBlockState(any())).thenAnswer(call -> {
            final BlockEntity be = this.level.getBlockEntity(call.getArgument(0));
            return be == null ? Blocks.AIR.defaultBlockState() : be.getBlockState();
        });
        TorquePropagatorAccessor.forgia$networks().put(this.level, new HashMap<>());
        RegionThreadingManager.install(this.engine);
        ParallelAdapterRuntime.initialize(this.server, registrar -> NeoForge.EVENT_BUS.post(new RegisterParallelAdaptersEvent(this.server, registrar)));
    }

    MillstoneBlockEntity millstone(BlockPos pos, int timer, float speed) {
        final var millstone = new MillstoneBlockEntity(AllBlockEntityTypes.MILLSTONE.get(), pos, AllBlocks.MILLSTONE.getDefaultState());
        this.steady(millstone, speed);
        millstone.timer = timer;
        return millstone;
    }

    void steady(KineticBlockEntity be, float speed) {
        be.setLevel(this.level);
        be.setSpeed(speed);
        be.updateSpeed = false;
        be.networkDirty = false;
        set(SmartBlockEntity.class, be, "initialized", true);
        set(KineticBlockEntity.class, be, "validationCountdown", 1000);
        set(SmartBlockEntity.class, be, "lazyTickCounter", 1000);
        be.network = be.getBlockPos().asLong();
        final KineticNetwork network = new KineticNetwork();
        network.id = be.network;
        network.initialized = true;
        network.members.put(be, 0f);
        TorquePropagatorAccessor.forgia$networks().get(this.level).put(be.network, network);
    }

    LevelChunk attach(BlockEntity be) {
        final BlockPos pos = be.getBlockPos();
        final long key = ChunkPos.asLong(pos);
        final LevelChunk chunk = this.chunks.computeIfAbsent(key, unused -> {
            final LevelChunk loaded = mock(LevelChunk.class);
            set(LevelChunk.class, loaded, "level", this.level);
            when(loaded.getLevel()).thenReturn(this.level);
            when(loaded.getPos()).thenReturn(new ChunkPos(pos));
            when(loaded.isLoaded()).thenReturn(true);
            when(loaded.getFullStatus()).thenReturn(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING);
            when(loaded.getBlockEntities()).thenReturn(new HashMap<>());
            when(loaded.getBlockEntity(any(), any(LevelChunk.EntityCreationType.class))).thenAnswer(call -> loaded.getBlockEntities().get(call.getArgument(0)));
            when(loaded.getBlockState(any())).thenAnswer(call -> {
                final var member = loaded.getBlockEntities().get(call.getArgument(0));
                return member == null ? Blocks.AIR.defaultBlockState() : member.getBlockState();
            });
            final List<TickingBlockEntity> registered = new ArrayList<>();
            this.tickers.put(key, registered);
            when(loaded.blockEntityTickers()).thenReturn(registered);
            final var cache = new AtomicReference<RegionBlockEntityTickChunkCache>();
            this.caches.put(key, cache);
            when(loaded.blockEntityTickAdmissionCache()).thenAnswer(call -> cache.get());
            doAnswer(call -> {
                cache.set(call.getArgument(0));
                return null;
            }).when(loaded).setBlockEntityTickAdmissionCache(any());
            return loaded;
        });
        be.setLevel(this.level);
        chunk.getBlockEntities().put(pos, be);
        return chunk;
    }

    TickingBlockEntity ticker(BlockEntity be, Runnable body) {
        final LevelChunk chunk = this.attach(be);
        try {
            final var create = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
            create.setAccessible(true);
            final TickingBlockEntity ticker = (TickingBlockEntity) create.invoke(chunk, be,
                    (BlockEntityTicker<BlockEntity>) (level, pos, state, instance) -> body.run());
            this.tickers.get(ChunkPos.asLong(be.getBlockPos())).add(ticker);
            return ticker;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    void tick(TickingBlockEntity... entries) {
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(this.level, BARRIER);
        assertNotNull(plan);
        for (final TickingBlockEntity entry : entries) assertTrue(plan.accept(entry));
        plan.executeAndAwait();
    }

    BlockEntityTickAdapter.Status status() {
        return ParallelApi.forServer(this.server).orElseThrow().blockEntityTickAdapters().getFirst();
    }

    static void set(Class<?> owner, Object target, String name, Object value) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Override
    public void close() {
        RegionThreadingManager.clear();
        ParallelAdapterRuntime.close(this.server);
        TorquePropagatorAccessor.forgia$networks().remove(this.level);
        this.caches.values().forEach(cache -> {
            if (cache.get() != null) cache.get().release();
        });
    }
}
