/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.await;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.get;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual canonical Level input, original workers/bodies and one phase kernel across every Fence. */
@Tag("canonical-be-contract")
@Timeout(120)
class CanonicalBlockEntityCoordinatorTest {
    private static final BlockPos A = new BlockPos(20, 64, 20);
    private static final BlockPos B = new BlockPos(68, 64, 20);
    private static final BlockPos U = new BlockPos(116, 64, 20);

    @Test
    void aLateRefusalPreventsEveryLaterBodyAcrossRegionsAndRefreshesTheSameTickets() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.generic("A", A, () -> {});
            f.generic("B", B, () -> {});
            f.generic("C", A.east(), () -> {});
            f.enable(false);
            f.register(source -> {
                final String id = f.names.get(source.getBlockPos());
                f.event(id, "check");
                if (id.equals("B")) {
                    assertEquals(0, f.cells.get("A").get(), "all checks precede the first prefix body");
                    assertEquals(0, f.cells.get("C").get());
                    return BlockEntityTickAdapter.Decision.CALLBACK_REQUIRED;
                }
                return BlockEntityTickAdapter.Decision.ADMITTED;
            });
            f.levelPhase();
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.before("B", "check", "A", "body-start");
            f.before("A", "body-end", "B", "body-start");
            f.before("B", "body-end", "C", "body-start");
            assertEquals(1, f.observedBatches.size());
            assertEquals(3, f.observedTickets.size());
            for (final var id : List.of("A", "B", "C")) assertEquals("SUCCEEDED", ticketState(f, id));
            f.clean();
            f.probes();
        }
    }

    @Test
    void theFenceCanActivateAndThenInvalidateRealHopperBranchesBeforeTheirOnlyBody() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.script("A", A, "minecraft:hopper", () -> {});
            f.script("U", U, "test:branch-change", () -> {
                final var source = (HopperBlockEntity) f.world.blockEntities.get(B);
                source.setCooldown(4);
                final var sourceC = (HopperBlockEntity) f.world.blockEntities.get(A.east());
                sourceC.setCooldown(1);
            });
            f.script("B", B, "minecraft:hopper", () -> {});
            ((HopperBlockEntity) f.world.blockEntities.get(B)).setCooldown(0);
            f.script("C", A.east(), "minecraft:hopper", () -> {});
            f.enable(false);
            f.levelPhase();
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertTrue(f.trace().stream().anyMatch(e -> e.id().equals("B") && e.action().equals("body-start") && e.worker()));
            assertTrue(f.trace().stream().anyMatch(e -> e.id().equals("C") && e.action().equals("body-start") && !e.worker()));
            for (String id : List.of("A", "B", "C", "U")) assertEquals(1, f.cells.get(id).get());
            f.before("U", "body-end", "B", "body-start");
            assertEquals(1, f.observedBatches.size());
            f.clean();
            f.probes();
        }
    }

    @Test
    void canonicalRegionRandomContinuesAcrossTheFenceWithoutRestartingTheSeed() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            set(Level.class, f.level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
            final List<RandomSource> streams = new ArrayList<>();
            final List<Long> draws = new ArrayList<>();
            final Runnable draw = () -> {
                streams.add(f.engine.currentGroupRandom());
                draws.add(f.level.random.nextLong());
            };
            f.script("A", A, "minecraft:hopper", draw);
            f.script("U", U, "test:fence", () -> {});
            f.script("B", A.east(), "minecraft:hopper", draw);
            f.enable(false);
            final var batch = Class.forName("net.neoforged.neoforge.server.threading.region.RegionTickBatch");
            final Method seed = batch.getDeclaredMethod("regionRandomSeed", ServerLevel.class,
                    net.neoforged.neoforge.server.threading.region.RegionCoordinate.class);
            seed.setAccessible(true);
            final var expected = RandomSource.create((Long) CanonicalBlockEntityTestSupport.invoke(seed, null,
                    f.level, RegionLayout.FIXED_3X3.regionAt(f.level, A)));
            f.levelPhase();
            assertEquals(List.of(expected.nextLong(), expected.nextLong()), draws);
            assertNotNull(streams.getFirst());
            assertSame(streams.getFirst(), streams.getLast());
            assertEquals(1, f.observedBatches.size());
            f.clean();
            f.probes();
        }
    }

    @Test
    void aForeignRegionTaskCannotStartInsideTheUnknownServerFence() throws InterruptedException {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var inFence = new CountDownLatch(1);
            final var attempted = new CountDownLatch(1);
            final var done = new CountDownLatch(1);
            final var actionCalls = new java.util.concurrent.atomic.AtomicInteger();
            final var failure = new AtomicReference<Throwable>();
            f.script("A", A, "minecraft:hopper", () -> {});
            f.script("U", U, "test:unknown", () -> {
                inFence.countDown();
                await(attempted);
                assertEquals(0, actionCalls.get());
                assertEquals(0, f.engine.diagnostics().runningRegionCount());
                assertNull(f.engine.beginBlockEntityTickPlan(f.level, f.barrier), "nested phase must use the complete serial loop");
            });
            f.script("B", B, "minecraft:hopper", () -> {});
            f.enable(false);
            final var sender = new Thread(() -> {
                try {
                    await(inFence);
                    f.engine.runInBlockRegion(f.level, B, actionCalls::incrementAndGet);
                    attempted.countDown();
                } catch (Throwable error) {
                    failure.set(error);
                    attempted.countDown();
                } finally {
                    done.countDown();
                }
            }, "canonical-foreign-admission");
            sender.start();
            try {
                f.levelPhase();
                await(done);
                assertNull(failure.get());
                assertEquals(0, actionCalls.get());
                f.engine.drainRegionTasks(f.level, B);
                assertEquals(1, actionCalls.get());
                f.clean();
                f.probes();
            } finally {
                sender.join(10000);
                assertFalse(sender.isAlive());
            }
        }
    }

    @Test
    void repeatedSourceMembershipCannotReuseItsPrecedingCooldownProof() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.script("A", A, "minecraft:hopper", () -> {});
            final var source = (HopperBlockEntity) f.world.blockEntities.get(A);
            source.setCooldown(2);
            final var bind = CanonicalBlockEntityTestSupport.class.getDeclaredMethod("bound", String.class,
                    net.minecraft.world.level.block.entity.BlockEntity.class, net.minecraft.world.level.block.entity.BlockEntityTicker.class);
            bind.setAccessible(true);
            CanonicalBlockEntityTestSupport.invoke(bind, f, "B", source, source.getBlockState().getTicker(f.level, source.getType()));
            f.enable(false);
            f.levelPhase();
            assertEquals(1, f.engine.diagnostics().blockEntityTicksOnWorkers(), "the second original invocation enters the active branch and must be serial");
            assertEquals(0, get(HopperBlockEntity.class, source, "cooldownTime"));
            f.clean();
            f.probes();
        }
    }

    @Test
    void anUnknownServerCallbackCannotSubmitAnUnrelatedGlobalWorker() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.script("A", A, "minecraft:hopper", () -> {});
            f.script("U", U, "test:unknown", () -> {
                f.engine.drainGlobalTasks();
                final var global = get(f.engine.getClass(), f.engine, "globalRegion");
                assertFalse(((java.util.concurrent.atomic.AtomicBoolean) get(global.getClass(), global, "workerSubmitted")).get());
            });
            f.script("B", B, "minecraft:hopper", () -> {});
            f.enable(false);
            set(f.engine.getClass(), f.engine, "globalWorkerExecutionEnabled", true);
            f.levelPhase();
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
            f.probes();
        }
    }

    @Test
    void anUnknownWorldClockIsNotCalledAsWorkerPreflightOrReplayed() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var clock = org.mockito.Mockito.mock(net.minecraft.world.level.storage.WritableLevelData.class);
            final var calls = new java.util.concurrent.atomic.AtomicInteger();
            org.mockito.Mockito.doAnswer(call -> {
                assertSame(f.serverThread, Thread.currentThread());
                calls.incrementAndGet();
                return 91L;
            }).when(clock).getGameTime();
            set(Level.class, f.level, "levelData", clock);
            org.mockito.Mockito.doCallRealMethod().when(f.level).getGameTime();
            f.script("A", A, "minecraft:hopper", () -> {});
            f.enable(false);
            f.levelPhase();
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(1, calls.get());
            assertEquals(91L, get(HopperBlockEntity.class, f.world.blockEntities.get(A), "tickedGameTime"));
            f.clean();
            f.probes();
        }
    }

    private static String ticketState(CanonicalBlockEntityTestSupport f, String id) {
        return get(f.observedTickets.get(id).getClass(), f.observedTickets.get(id), "state").toString();
    }
}
