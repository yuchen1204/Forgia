/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.await;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.get;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.invoke;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.IBlockCapabilityProvider;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real original Hopper bodies reach the real capability dispatcher, without granting their callbacks safety. */
@Tag("canonical-be-contract")
@Timeout(120)
class CanonicalBlockEntityNativeInventoryTest {
    private static final BlockPos FIRST = new BlockPos(20, 64, 20);
    private static final BlockPos OTHER_REGION = new BlockPos(68, 64, 20);

    @ParameterizedTest(name = "unproven hopper provider, waves={0}")
    @ValueSource(booleans = { false, true })
    void unprovenProviderMustKeepTheCompleteOriginalHopperTickOnTheServer(boolean waves) throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var callers = new ConcurrentLinkedQueue<Thread>();
            try (var ignored = capability(f, new ItemStackHandler(0), pos -> {
                callers.add(Thread.currentThread());
                f.event("provider", "queried");
            })) {
                final var source = hopper(f, "A", FIRST);
                f.enable(waves);
                try {
                    f.levelPhase();
                    assertEquals(1, callers.size(), "one complete original invocation, never a provider preflight plus replay");
                    assertEquals(1, source.getItem(0).getCount());
                    assertEquals(0, get(HopperBlockEntity.class, source, "cooldownTime"));
                    assertTrue(callers.stream().allMatch(t -> t == f.serverThread),
                            "The original Hopper must stay serial before an unaudited capability provider can run: " + f.trace());
                } finally {
                    f.clean();
                    f.probes();
                    System.out.println("original-hopper-provider waves=" + waves + " " + f.trace());
                }
            }
        }
    }

    @Test
    void retainedNativeBridgeCanReachOneCapabilityRootFromTwoRegionsConcurrently() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var bothEntered = new CountDownLatch(2);
            final var queriedPositions = new ConcurrentLinkedQueue<BlockPos>();
            final var entered = new AtomicInteger();
            final var max = new AtomicInteger();
            // No writes race: this handler only records overlap and exposes no slots. It is the same
            // object returned by the production provider dispatcher for two different target positions.
            final var root = new ItemStackHandler(0) {
                @Override
                public int getSlots() {
                    assertTrue(RegionThreadingHooks.isNativeRegionWorkerThread());
                    max.accumulateAndGet(entered.incrementAndGet(), Math::max);
                    bothEntered.countDown();
                    try {
                        await(bothEntered);
                        return 0;
                    } finally {
                        entered.decrementAndGet();
                    }
                }
            };
            try (var ignored = capability(f, root, queriedPositions::add)) {
                final var first = hopper(f, "A", FIRST);
                final var second = hopper(f, "B", OTHER_REGION);
                f.enable(false);
                assertFalse(RegionLayout.FIXED_3X3.regionAt(f.level, FIRST).equals(RegionLayout.FIXED_3X3.regionAt(f.level, OTHER_REGION)));
                try {
                    f.retainedExplicitPhase("A", "B");
                    assertEquals(2, max.get());
                    assertEquals(2, queriedPositions.size());
                    assertTrue(queriedPositions.contains(FIRST.below()));
                    assertTrue(queriedPositions.contains(OTHER_REGION.below()));
                    assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
                    assertEquals(1, first.getItem(0).getCount());
                    assertEquals(1, second.getItem(0).getCount());
                    f.clean();
                    f.probes();
                } finally {
                    System.out.println("retained-original-hopper-shared-root overlap=" + max + " " + f.trace());
                }
            }
        }
    }

    @Test
    void retainedNativeProviderTasksKeepLevelEndOrderAndDrainPastFailureWithoutReplay() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var applied = new AtomicInteger();
            final var queries = new AtomicInteger();
            final var injected = new IllegalStateException("native inventory level-end effect");
            try (var ignored = capability(f, new ItemStackHandler(0), pos -> {
                assertEquals(1, queries.incrementAndGet());
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> {
                    applied.incrementAndGet();
                    f.event("effect", "first");
                    throw injected;
                }));
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> {
                    applied.incrementAndGet();
                    f.event("effect", "second");
                }));
            })) {
                hopper(f, "A", FIRST);
                f.enable(false);
                try {
                    f.retainedExplicitPhase("A");
                    assertEquals(0, applied.get(), "a complete phase return must retain the old level-end timing");
                    f.event("phase", "end-level-tick");
                    assertSame(injected, assertThrows(IllegalStateException.class, () -> f.engine.endLevelTick(f.level)));
                    assertEquals(2, applied.get(), "a failed effect does not strand the later queued task");
                    f.engine.endLevelTick(f.level);
                    assertEquals(2, applied.get());
                    assertEquals(1, queries.get());
                    f.before("phase", "end-level-tick", "effect", "first");
                    f.before("effect", "first", "effect", "second");
                    f.clean();
                    f.probes();
                } finally {
                    f.engine.endLevelTick(f.level);
                    System.out.println("retained-original-hopper-task-failure " + f.trace());
                }
            }
        }
    }

    @Test
    void retainedNativeProviderScheduledMutationsKeepClearThenScheduleAndBlockEventTiming() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var at = FIRST.above();
            f.world.blockTicks.addContainer(new ChunkPos(at), new LevelChunkTicks<>());
            final var queries = new AtomicInteger();
            try (var ignored = capability(f, new ItemStackHandler(0), pos -> {
                queries.incrementAndGet();
                f.world.blockTicks.schedule(new ScheduledTick<>(Blocks.STONE, at, 100, 0));
                f.world.blockTicks.clearArea(new BoundingBox(at));
                f.world.blockTicks.schedule(new ScheduledTick<>(Blocks.GLASS, at, 101, 1));
                f.level.blockEvent(at, Blocks.NOTE_BLOCK, 0, 0);
                assertFalse(f.world.blockTicks.hasScheduledTick(at, Blocks.GLASS));
                assertEquals(0, f.world.blockEventCount(), "registration is deferred; the event body is a later phase");
            })) {
                hopper(f, "A", FIRST);
                f.enable(false);
                f.retainedExplicitPhase("A");
                assertEquals(1, queries.get());
                assertFalse(f.world.blockTicks.hasScheduledTick(at, Blocks.STONE));
                assertTrue(f.world.blockTicks.hasScheduledTick(at, Blocks.GLASS));
                assertEquals(1, f.world.blockTicks.count());
                assertEquals(1, f.world.blockEventCount());
                f.engine.endLevelTick(f.level);
                f.engine.endLevelTick(f.level);
                assertEquals(1, f.world.blockTicks.count());
                assertEquals(1, f.world.blockEventCount());
                f.clean();
                f.probes();
            }
        }
    }

    @Test
    void retainedNativeRegionRandomContinuesAcrossTwoOriginalHopperInvocations() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            set(Level.class, f.level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
            final var draws = new ArrayList<Long>();
            final var streams = new ArrayList<RandomSource>();
            try (var ignored = capability(f, new ItemStackHandler(0), pos -> {
                streams.add(f.engine.currentGroupRandom());
                draws.add(f.level.random.nextLong());
            })) {
                hopper(f, "A", FIRST);
                hopper(f, "B", FIRST.east());
                f.enable(false);
                final var batch = Class.forName("net.neoforged.neoforge.server.threading.region.RegionTickBatch");
                final Method seed = batch.getDeclaredMethod("regionRandomSeed", ServerLevel.class, RegionCoordinate.class);
                seed.setAccessible(true);
                final var expected = RandomSource.create((Long) invoke(seed, null, f.level, RegionLayout.FIXED_3X3.regionAt(f.level, FIRST)));
                f.retainedExplicitPhase("A", "B");
                assertEquals(List.of(expected.nextLong(), expected.nextLong()), draws);
                assertEquals(2, streams.size());
                assertNotNull(streams.getFirst());
                assertSame(streams.getFirst(), streams.getLast(), "one Region stream, without restarting at a ticker boundary");
                f.clean();
                f.probes();
            }
        }
    }

    private static HopperBlockEntity hopper(CanonicalBlockEntityTestSupport f, String id, BlockPos pos) throws Exception {
        final var state = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN);
        final var source = new HopperBlockEntity(pos, state);
        source.setLevel(f.level);
        source.setItem(0, new ItemStack(Items.IRON_INGOT));
        f.world.blockEntities.put(pos, source);
        f.world.states.put(pos, state);
        f.world.states.put(pos.below(), Blocks.IRON_BLOCK.defaultBlockState());
        f.names.put(pos, id);
        final var original = state.getTicker(f.level, source.getType());
        assertNotNull(original);
        assertEquals(HopperBlockEntity.class, source.getClass());
        final Method bind = CanonicalBlockEntityTestSupport.class.getDeclaredMethod("bound", String.class, BlockEntity.class, BlockEntityTicker.class);
        bind.setAccessible(true);
        invoke(bind, f, id, source, original);
        return source;
    }

    private interface Restore extends AutoCloseable {
        @Override
        void close();
    }

    @SuppressWarnings("unchecked")
    private static Restore capability(CanonicalBlockEntityTestSupport f, IItemHandler root, Consumer<BlockPos> observed) throws Exception {
        final var cap = Capabilities.ItemHandler.BLOCK;
        final var providers = (Map<Block, List<IBlockCapabilityProvider<IItemHandler, Direction>>>) get(BlockCapability.class, cap, "providers");
        final var previous = providers.put(Blocks.IRON_BLOCK, new ArrayList<>());
        try {
            final var constructor = RegisterCapabilitiesEvent.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance().registerBlock(cap, (level, pos, state, source, side) -> {
                observed.accept(pos);
                return root;
            }, Blocks.IRON_BLOCK);
            // Forward the actual default Level extension and actual registered provider dispatch, instead
            // of mocking the provider result or replacing the original Hopper body.
            doCallRealMethod().when(f.level).getCapability(eq(cap), any(BlockPos.class), any(BlockState.class), any(), any());
            return () -> {
                if (previous == null) providers.remove(Blocks.IRON_BLOCK);
                else providers.put(Blocks.IRON_BLOCK, previous);
            };
        } catch (Throwable failure) {
            if (previous == null) providers.remove(Blocks.IRON_BLOCK);
            else providers.put(Blocks.IRON_BLOCK, previous);
            throw failure;
        }
    }
}
