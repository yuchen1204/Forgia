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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Repeated actual Level phases and failure/topology recovery. These are JUnit world seams, not installed-server runs. */
@Tag("canonical-be-contract")
@Timeout(180)
class CanonicalBlockEntityRecoveryTest {
    private static final BlockPos A = new BlockPos(20, 64, 20);
    private static final BlockPos B = new BlockPos(68, 64, 20);
    private static final BlockPos U = new BlockPos(116, 64, 20);

    @Test
    void twoHundredMixedPhasesRetireEveryKernelAndMatchTheIndependentSerialWorld() {
        java.util.Map<String, String> expected;
        try (var serial = new CanonicalBlockEntityTestSupport()) {
            mixed(serial);
            for (int i = 0; i < 200; i++) {
                refill(serial);
                serial.levelPhase();
            }
            expected = serial.state();
        }
        try (var f = new CanonicalBlockEntityTestSupport()) {
            mixed(f);
            f.enable(true);
            for (int i = 0; i < 200; i++) {
                refill(f);
                f.levelPhase();
                f.clean();
                assertNull(get(get(f.engine.getClass(), f.engine, "regionWorkAdmission").getClass(),
                        get(f.engine.getClass(), f.engine, "regionWorkAdmission"), "phaseOwner"));
            }
            assertEquals(expected, f.state());
            assertEquals(200, f.observedBatches.size());
            assertEquals(1400, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(800, f.engine.furnaceWorkMetrics().nativeCompletions());
            for (var id : List.of("N0", "N1", "N2", "U")) assertEquals(200, f.cells.get(id).get());
            f.probes();
            System.out.println("C41_REPEATED_JUNIT_PHASES=200 bodies=1600 workerBodies=1400 batchLeaks=0 permitScopes=0");
        }
    }

    @Test
    void aFailedStartedNativeCommitsOnceAndItsUnstartedMixedSuffixRunsOnlyInTheNextPhase() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var fail = new AtomicBoolean(true);
            final var effects = new ArrayList<String>();
            final var error = new IllegalStateException("C41 injected native failure after owned effect");
            f.script("N0", A, "minecraft:hopper", () -> {
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> effects.add("N0")));
                if (fail.getAndSet(false)) throw error;
            });
            f.script("N1", A.east(3), "minecraft:hopper", () -> {});
            f.script("N2", B, "minecraft:hopper", () -> assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> effects.add("N2"))));
            f.furnace("F0", A.east(6), 1);
            f.furnace("F1", B.east(6), 1);
            f.foreign("U", U, () -> {});
            f.enable(true);
            final var thrown = assertThrows(RuntimeException.class, f::levelPhase);
            assertTrue(hasCause(thrown, error));
            assertEquals(List.of("N0", "N2"), effects);
            assertEquals(1, f.cells.get("N0").get());
            assertEquals(0, f.cells.get("N1").get());
            assertEquals(1, f.cells.get("N2").get());
            assertEquals("CANCELLED", ticketState(f, "N1"));
            assertEquals("CANCELLED", ticketState(f, "F0"));
            f.clean();
            f.levelPhase();
            assertEquals(List.of("N0", "N2", "N0", "N2"), effects);
            assertEquals(2, f.cells.get("N0").get());
            assertEquals(1, f.cells.get("N1").get());
            assertEquals(2, f.cells.get("N2").get());
            assertEquals(1, f.cells.get("U").get());
            assertEquals(1, f.furnaces.get("F0").getItem(2).getCount());
            assertEquals(1, f.furnaces.get("F1").getItem(2).getCount());
            assertEquals(2, f.observedBatches.size());
            f.clean();
            f.probes();
        }
    }

    @Test
    void aMixedFurnaceCommitFailureDoesNotReplayAndTheUntouchedSuffixRemainsEligibleNextPhase() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            mixed(f);
            f.enable(true);
            final var error = new IllegalStateException("C41 injected notification failure");
            f.onChange = id -> {
                if (id.equals("F0")) throw error;
            };
            assertSame(error, assertThrows(IllegalStateException.class, f::levelPhase));
            assertEquals(0, f.cells.get("U").get());
            assertEquals(0, f.cells.get("N2").get());
            assertEquals(1, f.trace().stream().filter(e -> e.id().equals("F1") && e.action().equals("changed")).count());
            assertEquals("CANCELLED", ticketState(f, "F2"));
            f.clean();
            f.onChange = id -> {};
            f.levelPhase();
            assertEquals(2, f.cells.get("N0").get());
            assertEquals(2, f.cells.get("N1").get());
            assertEquals(1, f.cells.get("N2").get());
            assertEquals(1, f.cells.get("U").get());
            for (var id : List.of("F0", "F1", "F2", "F3")) assertEquals(1, f.furnaces.get(id).getItem(2).getCount(), id);
            assertEquals(6, f.engine.furnaceWorkMetrics().nativeCompletions());
            f.clean();
            f.probes();
        }
    }

    @ParameterizedTest(name = "real closed pool, persistent={0}")
    @ValueSource(booleans = { false, true })
    void aClosedActualWorkerPoolRunsEveryUnstartedCompleteTickerExactlyOnce(boolean persistent) throws Exception {
        java.util.Map<String, String> expected;
        try (var serial = new CanonicalBlockEntityTestSupport()) {
            mixed(serial);
            serial.levelPhase();
            expected = serial.state();
        }
        try (var f = new CanonicalBlockEntityTestSupport()) {
            mixed(f);
            f.enable(true, persistent);
            final var pool = get(f.engine.getClass(), f.engine, "workerPool");
            final var close = pool.getClass().getDeclaredMethod("close");
            close.setAccessible(true);
            invoke(close, pool);
            f.levelPhase();
            assertEquals(expected, f.state());
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(0, f.engine.furnaceWorkMetrics().nativeCompletions());
            assertEquals(1, f.observedBatches.size());
            f.clean();
        }
    }

    @Test
    void chunkBindingReplacementAtTheFenceRetiresOldInvocationsAndAddsFreshTickersOnlyNextPhase() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.script("N0", A, "minecraft:hopper", () -> {});
            f.script("N1", B, "minecraft:hopper", () -> {});
            final BlockPos target = new BlockPos(164, 64, 20);
            final var changed = new AtomicBoolean();
            f.foreign("U", U, () -> {
                if (changed.getAndSet(true)) return;
                for (String id : List.of("old0", "old1")) f.furnaces.get(id).setRemoved();
                final var oldChunk = f.chunks.remove(ChunkPos.asLong(target));
                f.level.unregisterBlockEntityTickerChunk(oldChunk);
                f.furnace("new0", target, 1);
                f.furnace("new1", target.east(3), 1);
                assertNotSame(oldChunk, f.chunk(target));
            });
            f.furnace("old0", target, 1);
            f.furnace("old1", target.east(3), 1);
            f.enable(true);
            f.levelPhase();
            assertTrue(f.furnaces.get("old0").getItem(2).isEmpty());
            assertTrue(f.furnaces.get("new0").getItem(2).isEmpty());
            assertTrue(f.furnaces.get("new1").getItem(2).isEmpty());
            assertEquals("CANCELLED", ticketState(f, "old0"));
            f.clean();
            f.levelPhase();
            assertEquals(1, f.furnaces.get("new0").getItem(2).getCount());
            assertEquals(1, f.furnaces.get("new1").getItem(2).getCount());
            assertEquals(2, f.engine.furnaceWorkMetrics().nativeCompletions());
            f.clean();
            f.probes();
        }
    }

    @Test
    void fixedRegionRetirementWaitsForTheFurnaceBodyAndCommitAndTheNativeSuffixGetsAFreshRegion() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.furnace("F0", A, 1);
            f.furnace("F1", B, 1);
            f.foreign("U", U, () -> {});
            f.script("N0", A.east(3), "minecraft:hopper", () -> {});
            f.script("N1", B.east(3), "minecraft:hopper", () -> {});
            f.enable(true);
            f.engine.addRegionSection(f.level, A.getX() / 16, A.getZ() / 16);
            f.engine.addRegionSection(f.level, B.getX() / 16, B.getZ() / 16);
            final var old = region(f, RegionLayout.FIXED_3X3, A);
            final var entered = new CountDownLatch(2);
            final var release = new CountDownLatch(1);
            f.onStart = id -> {
                if (Set.of("F0", "F1").contains(id)) {
                    entered.countDown();
                    await(release);
                }
            };
            f.onChange = id -> {
                if (id.equals("F0")) {
                    assertFalse(dead(old));
                    assertTrue(running(old));
                }
            };
            final var normal = f.barrier;
            final var once = new AtomicBoolean();
            f.barrier = done -> {
                if (f.controlled != null) f.controlled.dispatchAll();
                if (once.compareAndSet(false, true)) {
                    await(entered);
                    f.engine.removeRegionSection(f.level, A.getX() / 16, A.getZ() / 16);
                    assertFalse(dead(old));
                    assertTrue(running(old));
                    release.countDown();
                }
                normal.await(done);
            };
            try {
                f.levelPhase();
                assertTrue(dead(old));
                assertEquals(4, f.engine.diagnostics().blockEntityTicksOnWorkers());
                f.before("F0", "changed", "U", "body-start");
                f.before("U", "body-end", "N0", "body-start");
                f.clean();
                f.probes();
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest(name = "mixed full phase cleanup, shutdown={0}")
    @ValueSource(booleans = { false, true })
    void cleanupDuringTheFirstFinePrefixWaitsForMixedSuffixAndEveryRequiredCommit(boolean shutdown) throws InterruptedException {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            mixed(f);
            f.enable(true);
            final var entered = new CountDownLatch(2);
            final var release = new CountDownLatch(1);
            final var fineStarted = new AtomicBoolean();
            final var once = new AtomicBoolean();
            final var returned = new AtomicBoolean();
            final var failure = new AtomicReference<Throwable>();
            f.onStart = id -> {
                if (Set.of("F0", "F1").contains(id)) {
                    fineStarted.set(true);
                    entered.countDown();
                    await(release);
                }
            };
            final var cleanup = new Thread(() -> {
                try {
                    await(entered);
                    if (shutdown) f.engine.shutdown();
                    else f.engine.clearLevelRegionTasks(f.level);
                    f.event("cleanup", "returned");
                    returned.set(true);
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "canonical-mixed-cleanup");
            final var normal = f.barrier;
            f.barrier = done -> normal.await(() -> {
                if (fineStarted.get() && once.compareAndSet(false, true)) {
                    await(entered);
                    await(f.lifecycleWriteHeld);
                    assertFalse(returned.get());
                    assertEquals(0, f.cells.get("U").get());
                    release.countDown();
                }
                return done.getAsBoolean();
            });
            cleanup.start();
            try {
                f.levelPhase();
                cleanup.join(10_000);
                assertFalse(cleanup.isAlive());
                assertNull(failure.get());
                assertTrue(returned.get());
                f.before("U", "body-end", "cleanup", "returned");
                f.before("F3", "changed", "cleanup", "returned");
                for (var id : List.of("N0", "N1", "N2", "U")) assertEquals(1, f.cells.get(id).get());
                for (var id : List.of("F0", "F1", "F2", "F3")) assertEquals(1, f.furnaces.get(id).getItem(2).getCount());
                assertEquals(1, f.observedBatches.size());
                f.clean();
                if (!shutdown) f.probes();
            } finally {
                release.countDown();
                cleanup.join(10_000);
            }
        }
    }

    @Test
    void dynamicMergeDefersAcrossBothOriginalLeasesAndTheNextPhaseUsesTheMergedHome() throws Exception {
        final var far = new BlockPos(532, 64, 20);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.script("A", A, "minecraft:hopper", () -> {});
            f.script("B", far, "minecraft:hopper", () -> {});
            f.enable(false, Boolean.getBoolean("forgia.furnace.persistentWorkers"), RegionLayout.DYNAMIC);
            f.engine.addRegionSection(f.level, 1, 1);
            f.engine.addRegionSection(f.level, 33, 1);
            final var left = region(f, RegionLayout.DYNAMIC, A);
            final var right = region(f, RegionLayout.DYNAMIC, far);
            assertNotSame(left, right);
            final var entered = new CountDownLatch(2);
            final var release = new CountDownLatch(1);
            f.onStart = id -> {
                entered.countDown();
                await(release);
            };
            final var normal = f.barrier;
            final var once = new AtomicBoolean();
            f.barrier = done -> {
                if (f.controlled != null) f.controlled.dispatchAll();
                if (once.compareAndSet(false, true)) {
                    await(entered);
                    f.engine.addRegionSection(f.level, 17, 1);
                    assertFalse(dead(left));
                    assertFalse(dead(right));
                    assertNotSame(region(f, RegionLayout.DYNAMIC, A), region(f, RegionLayout.DYNAMIC, far));
                    release.countDown();
                }
                normal.await(done);
            };
            try {
                f.levelPhase();
                assertSame(region(f, RegionLayout.DYNAMIC, A), region(f, RegionLayout.DYNAMIC, far));
                f.clean();
                f.onStart = id -> {};
                f.levelPhase();
                assertEquals(2, f.cells.get("A").get());
                assertEquals(2, f.cells.get("B").get());
                assertEquals(4, f.engine.diagnostics().blockEntityTicksOnWorkers());
                assertEquals(2, f.observedBatches.size());
                f.clean();
                f.probes();
            } finally {
                release.countDown();
            }
        }
    }

    private static Object region(CanonicalBlockEntityTestSupport f, RegionLayout layout, BlockPos pos) {
        final var regionizer = get(f.engine.getClass(), f.engine, "regionizer");
        try {
            final var lookup = regionizer.getClass().getDeclaredMethod("get", RegionCoordinate.class);
            lookup.setAccessible(true);
            return invoke(lookup, regionizer, layout.regionAt(f.level, pos));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static boolean dead(Object region) {
        return ((java.util.concurrent.atomic.AtomicReference<?>) get(region.getClass(), region, "lifecycleState")).get().toString().equals("DEAD");
    }

    private static boolean running(Object region) {
        return ((java.util.concurrent.atomic.AtomicReference<?>) get(region.getClass(), region, "lifecycleState")).get().toString().equals("TICKING");
    }

    private static String ticketState(CanonicalBlockEntityTestSupport f, String id) {
        final var ticket = f.observedTickets.get(id);
        return get(ticket.getClass(), ticket, "state").toString();
    }

    private static boolean hasCause(Throwable thrown, Throwable expected) {
        if (thrown == expected) return true;
        if (thrown.getCause() != null && hasCause(thrown.getCause(), expected)) return true;
        for (var suppressed : thrown.getSuppressed()) if (hasCause(suppressed, expected)) return true;
        return false;
    }

    private static void mixed(CanonicalBlockEntityTestSupport f) {
        f.script("N0", A, "minecraft:hopper", () -> {});
        f.script("N1", B, "minecraft:hopper", () -> {});
        f.furnace("F0", A.east(3), 1);
        f.furnace("F1", B.east(3), 1);
        f.foreign("U", U, () -> {});
        f.script("N2", A.east(6), "minecraft:hopper", () -> {});
        f.furnace("F2", A.east(9), 1);
        f.furnace("F3", B.east(9), 1);
    }

    private static void refill(CanonicalBlockEntityTestSupport f) {
        for (var furnace : f.furnaces.values()) {
            furnace.setItem(0, new ItemStack(Items.IRON_ORE, 3));
            furnace.setItem(2, ItemStack.EMPTY);
            set(AbstractFurnaceBlockEntity.class, furnace, "litTime", 100);
            set(AbstractFurnaceBlockEntity.class, furnace, "cookingProgress", 1);
        }
    }
}
