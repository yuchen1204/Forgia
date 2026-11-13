/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntitySequenceTest.dump;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntitySequenceTest.serial;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.await;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.get;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.FurnaceWorkScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Protected real-driver behavior and the independently executed original Level serial oracle. */
@Timeout(120)
class CanonicalBlockEntityFenceTest {
    private static final BlockPos FIRST = new BlockPos(20, 64, 20);
    private static final BlockPos SECOND_REGION = new BlockPos(68, 64, 20);
    private static final BlockPos UNKNOWN = new BlockPos(116, 64, 20);

    @Test
    void vanillaSerialOracleExecutesTheActualListIncludingForeignAndFullFurnaceBodies() {
        final var snapshot = serial(f -> {
            f.script("N", SECOND_REGION, "minecraft:hopper", () -> {});
            f.foreign("U", UNKNOWN, () -> assertEquals(0, f.cells.get("G").get()));
            f.furnace("F", FIRST, 1);
            f.generic("G", SECOND_REGION.offset(3, 0, 0), () -> {});
        });
        assertEquals(List.of("N", "U", "F", "G"), snapshot.order());
        assertTrue(snapshot.events().stream().noneMatch(CanonicalBlockEntityTestSupport.Event::worker));
        assertTrue(snapshot.state().get("F").contains("minecraft:iron_ingot"));
        assertTrue(snapshot.state().get("F").startsWith("99:0:2:"));
    }

    @Test
    void vanillaFrozenPhasePrunesRemovedTickersAndRunsNoBodies() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.script("A", FIRST, "minecraft:hopper", () -> {});
            final var removed = mock(TickingBlockEntity.class);
            doReturn(true).when(removed).isRemoved();
            f.level.addBlockEntityTicker(removed);
            f.tickRate.setFrozen(true);
            f.tickRate.tick();
            f.levelPhase();
            assertEquals(List.of(), f.bodyOrder());
            assertEquals(1, CanonicalBlockEntityTestSupport.tickerList(f.level).size());
            assertFalse((boolean) get(Level.class, f.level, "tickingBlockEntities"));
        }
    }

    @Test
    void vanillaOnLoadFailureIsNotRetriedAndFinallyPublishesThePendingTickerForNextPhase() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.script("A", FIRST, "minecraft:hopper", () -> {});
            final var fresh = mock(BlockEntity.class);
            final var calls = new AtomicInteger();
            final var failure = new IllegalStateException("C1 onLoad after pending addition");
            doReturn(true).when(fresh).hasLevel();
            doAnswer(call -> {
                calls.incrementAndGet();
                f.foreign("P", UNKNOWN, () -> {});
                throw failure;
            }).when(fresh).onLoad();
            f.level.addFreshBlockEntities(List.of(fresh));
            assertSame(failure, assertThrows(IllegalStateException.class, f::levelPhase));
            assertEquals(List.of(), f.bodyOrder());
            assertFalse((boolean) get(Level.class, f.level, "tickingBlockEntities"));
            assertEquals(2, CanonicalBlockEntityTestSupport.tickerList(f.level).size());
            f.levelPhase();
            assertEquals(1, calls.get());
            assertEquals(List.of("A", "P"), f.bodyOrder());
            assertEquals(1, f.cells.get("P").get());
        }
    }

    @ParameterizedTest(name = "{displayName} [waves={0}]")
    @ValueSource(booleans = { false, true })
    void aRealWorkerPrefixPhysicallyReturnsBeforeTheUnknownTickerAndItsObservations(boolean waves) {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            prefix(f, waves);
            f.script("U", UNKNOWN, "test:unknown", () -> {
                assertSame(f.serverThread, Thread.currentThread());
                assertFalse(FurnaceWorkScope.active());
                assertFalse(BlockEntityResourceOwnership.Scope.active());
                if (waves) {
                    assertEquals(1, f.furnaces.get("A").getItem(2).getCount());
                    assertEquals(1, f.furnaces.get("B").getItem(2).getCount());
                } else {
                    assertEquals(1, f.cells.get("A").get());
                    assertEquals(1, f.cells.get("B").get());
                }
            });
        };
        final var oracle = serial(input);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(waves);
            final var entered = new CountDownLatch(2);
            final var release = new CountDownLatch(1);
            hold(f, Set.of("A", "B"), entered, release);
            final var once = new AtomicBoolean();
            final var normal = f.barrier;
            f.barrier = done -> {
                dispatch(f);
                if (once.compareAndSet(false, true)) {
                    await(entered);
                    assertEquals(0, count(f, "U", "body-start"));
                    assertEquals(2, f.trace().stream().filter(e -> e.action().equals("body-start") && e.worker()).count());
                    assertTrue(f.engine.diagnostics().runningRegionCount() > 0);
                    release.countDown();
                }
                normal.await(done);
            };
            try {
                f.levelPhase();
                f.before("A", "body-end", "U", "body-start");
                f.before("B", "body-end", "U", "body-start");
                if (waves) {
                    f.before("A", "changed", "U", "body-start");
                    f.before("B", "changed", "U", "body-start");
                    assertEquals(2, f.engine.furnaceWorkMetrics().nativeCompletions());
                    assertEquals(Boolean.getBoolean("forgia.furnace.resourceOwnership") ? 2 : 0, f.engine.blockEntityResourceMetrics().issuedLeases());
                } else assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
                assertEquals(oracle.state(), f.state());
                f.clean();
                f.probes();
            } finally {
                release.countDown();
                dump("prefix-waves=" + waves, f);
            }
        }
    }

    @Test
    void aFenceReloadOfTheSameRecipeManagerReceivesFreshAuditAndOnlyOriginalServerRecipeCallbacks() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            prefix(f, true);
            f.script("U", UNKNOWN, "test:reload", () -> {
                final var manager = f.recipes;
                manager.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:after_fence"),
                        new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0, 2) {
                            @Override
                            public boolean matches(SingleRecipeInput input, Level level) {
                                assertSame(f.serverThread, Thread.currentThread());
                                f.event("recipe", "matches");
                                return super.matches(input, level);
                            }
                        })));
                assertSame(manager, f.level.getRecipeManager());
            });
            f.furnace("C", FIRST.offset(6, 0, 0), 1);
            f.furnace("D", FIRST.offset(9, 0, 0), 1);
        };
        final var oracle = serial(input);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(true);
            try {
                f.levelPhase();
                assertEquals(2, f.engine.furnaceWorkMetrics().nativeCompletions());
                assertEquals(3, f.engine.furnaceWorkMetrics().serialInvocations());
                final long oracleMatches = oracle.events().stream().filter(e -> e.id().equals("recipe") && e.action().equals("matches")).count();
                assertEquals(oracleMatches, count(f, "recipe", "matches"), "preserve every callback the complete serial ticker actually invokes");
                f.before("U", "body-end", "recipe", "matches");
                assertEquals(oracle.state(), f.state());
                f.clean();
                f.probes();
            } finally {
                dump("fresh-recipe", f);
            }
        }
    }

    @Test
    void aFenceChangingLiveComponentsAndAnInventoryAttachmentCannotReuseTheEarlierAudit() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            prefix(f, true);
            f.script("U", UNKNOWN, "test:inventory", () -> {
                final var source = f.furnaces.get("C");
                source.setItem(0, source.getItem(0).copy());
                source.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("changed in complete fence"));
            });
            f.furnace("C", FIRST.offset(6, 0, 0), 1);
            f.furnace("D", FIRST.offset(9, 0, 0), 1);
        };
        final var oracle = serial(input);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(true);
            try {
                f.levelPhase();
                assertEquals(2, f.engine.furnaceWorkMetrics().nativeCompletions());
                assertEquals(3, f.engine.furnaceWorkMetrics().serialInvocations());
                assertEquals(oracle.state(), f.state());
                f.before("A", "changed", "U", "body-start");
                f.before("U", "body-end", "C", "changed");
                assertTrue(f.trace().stream().filter(e -> Set.of("C", "D").contains(e.id()) && e.action().equals("changed")).noneMatch(CanonicalBlockEntityTestSupport.Event::worker));
                f.clean();
                f.probes();
            } finally {
                dump("fresh-inventory-components", f);
            }
        }
    }

    @Test
    void auditedGenericOriginalBodiesOverlapWithOnePrepareAndOneOwnerCheckPerInvocation() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.generic("A", FIRST, () -> {});
            f.generic("B", SECOND_REGION, () -> {});
            f.enable(false);
            final var preparations = new AtomicInteger();
            final var checks = new AtomicInteger();
            f.register(new BlockEntityTickAdapter() {
                @Override
                public void prepare(ServerLevel level) {
                    assertSame(f.serverThread, Thread.currentThread());
                    preparations.incrementAndGet();
                    f.event("adapter", "prepare");
                }

                @Override
                public Decision check(BlockEntity source) {
                    assertTrue(Thread.currentThread() != f.serverThread);
                    checks.incrementAndGet();
                    f.event(f.names.get(source.getBlockPos()), "check");
                    return Decision.ADMITTED;
                }
            });
            final var entered = new CountDownLatch(2);
            final var release = new CountDownLatch(1);
            hold(f, Set.of("A", "B"), entered, release);
            final var normal = f.barrier;
            f.barrier = done -> {
                dispatch(f);
                await(entered);
                release.countDown();
                normal.await(done);
            };
            try {
                f.levelPhase();
                assertEquals(1, preparations.get());
                assertEquals(2, checks.get());
                assertEquals(1, f.cells.get("A").get());
                assertEquals(1, f.cells.get("B").get());
                f.before("A", "check", "A", "body-start");
                f.before("B", "check", "B", "body-start");
                assertTrue(f.trace().stream().filter(e -> e.action().equals("body-start")).allMatch(e -> e.worker() && "REGION_OWNER".equals(e.mode())));
                f.clean();
                f.probes();
            } finally {
                release.countDown();
                dump("generic-prefix", f);
            }
        }
    }

    @Test
    void aStartedNativeTickerWithEffectsIsNeverReplayedAndItsUnstartedSuffixIsRetired() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var failure = new IllegalStateException("C1 original ticker failed after own-cell effect");
            f.script("A", FIRST, "minecraft:hopper", () -> {
                throw failure;
            });
            f.script("C", FIRST.offset(3, 0, 0), "minecraft:hopper", () -> {});
            f.script("B", SECOND_REGION, "minecraft:hopper", () -> {});
            f.enable(false);
            try {
                final Throwable reported = assertThrows(RuntimeException.class, f::levelPhase);
                assertTrue(hasCause(reported, failure));
                assertEquals(1, f.cells.get("A").get());
                assertEquals(0, f.cells.get("C").get());
                assertEquals(1, f.cells.get("B").get());
                assertEquals(1, count(f, "A", "body-start"));
                assertEquals("FAILED", ticketState(f, "A"));
                assertEquals("CANCELLED", ticketState(f, "C"));
                assertEquals("SUCCEEDED", ticketState(f, "B"));
                f.clean();
                f.probes();
            } finally {
                dump("native-body-failure", f);
            }
        }
    }

    @Test
    void aThrowingSerialFencePreservesTheJoinedPrefixAndNeverStartsTheSuffix() {
        final var failure = new IllegalStateException("C1 unknown callback after effect");
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            prefix(f, true);
            f.script("U", UNKNOWN, "test:unknown", () -> {
                throw failure;
            });
            f.furnace("C", FIRST.offset(6, 0, 0), 1);
            f.furnace("D", FIRST.offset(9, 0, 0), 1);
        };
        java.util.Map<String, String> oracle;
        try (var serial = new CanonicalBlockEntityTestSupport()) {
            input.accept(serial);
            assertSame(failure, assertThrows(IllegalStateException.class, serial::levelPhase));
            oracle = serial.state();
        }
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(true);
            try {
                assertSame(failure, assertThrows(IllegalStateException.class, f::levelPhase));
                assertEquals(oracle, f.state());
                assertEquals(1, f.cells.get("U").get());
                assertEquals(0, count(f, "C", "changed"));
                assertEquals("CANCELLED", ticketState(f, "C"));
                assertEquals("FAILED", ticketState(f, "U"));
                f.before("A", "changed", "U", "body-start");
                f.clean();
                f.probes();
            } finally {
                dump("fence-failure", f);
            }
        }
    }

    @Test
    void aCommitFailureDoesNotReplayEitherOriginalFurnaceOrSkipTheOtherRequiredCommit() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            prefix(f, true);
            f.enable(true);
            final var failure = new IllegalStateException("C1 canonical change callback failed");
            f.onChange = id -> {
                if (id.equals("A")) throw failure;
            };
            try {
                assertSame(failure, assertThrows(IllegalStateException.class, f::levelPhase));
                assertEquals(1, count(f, "A", "body-start"));
                assertEquals(1, count(f, "B", "body-start"));
                assertEquals(1, count(f, "B", "changed"));
                assertEquals(1, f.furnaces.get("A").getItem(2).getCount());
                assertEquals(1, f.furnaces.get("B").getItem(2).getCount());
                f.clean();
                f.probes();
            } finally {
                dump("commit-failure", f);
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [waves={0}, interrupt={1}]")
    @CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void cancellationOfTheManagedWaitStillPhysicallyJoinsBeforeAnyAuthorityReturns(boolean waves, boolean interrupt) throws InterruptedException {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            prefix(f, waves);
            f.enable(waves);
            final var entered = new CountDownLatch(2);
            final var release = new CountDownLatch(1);
            final var physicalJoin = new CountDownLatch(1);
            final var controllerFailure = new AtomicReference<Throwable>();
            hold(f, Set.of("A", "B"), entered, release);
            final var cancellation = new CancellationException("C1 managed wait cancelled");
            final var controller = new Thread(() -> {
                try {
                    await(physicalJoin);
                    assertTrue(f.engine.diagnostics().runningRegionCount() > 0);
                    assertEquals(0, count(f, "A", "body-end"));
                    assertEquals(0, count(f, "B", "body-end"));
                    f.event("controller", "release-bodies");
                } catch (Throwable failure) {
                    controllerFailure.set(failure);
                } finally {
                    release.countDown();
                }
            }, "canonical-cancel-controller");
            f.barrier = done -> {
                dispatch(f);
                await(entered);
                observePhysicalJoin(f, done, waves, physicalJoin);
                f.event("phase", "cancel-request");
                if (interrupt) Thread.currentThread().interrupt();
                throw cancellation;
            };
            controller.start();
            try {
                assertSame(cancellation, assertThrows(CancellationException.class, f::levelPhase));
                f.event("phase", "returned");
                assertEquals(interrupt, Thread.interrupted(), "join must preserve caller interrupt status");
                controller.join(10_000);
                assertFalse(controller.isAlive());
                assertNull(controllerFailure.get());
                f.before("phase", "physical-join", "controller", "release-bodies");
                f.before("A", "body-end", "phase", "returned");
                f.before("B", "body-end", "phase", "returned");
                if (waves) {
                    assertEquals(2, f.engine.furnaceWorkMetrics().nativeCompletions());
                    assertEquals(1, f.furnaces.get("A").getItem(2).getCount());
                } else {
                    assertEquals(1, f.cells.get("A").get());
                    assertEquals(1, f.cells.get("B").get());
                }
                f.clean();
                f.probes();
            } finally {
                Thread.interrupted();
                release.countDown();
                controller.join(10_000);
                dump("cancel-waves=" + waves + "-interrupt=" + interrupt, f);
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [waves={0}, shutdown={1}]")
    @CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void unloadOrShutdownWaitsForActualBodiesAndThePublishedPhase(boolean waves, boolean shutdown) throws InterruptedException {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            prefix(f, waves);
            f.script("U", UNKNOWN, "test:unknown", () -> {});
            f.enable(waves);
            final var entered = new CountDownLatch(2);
            final var release = new CountDownLatch(1);
            final var cleanupDone = new CountDownLatch(1);
            final var cleanupFailure = new AtomicReference<Throwable>();
            hold(f, Set.of("A", "B"), entered, release);
            final var heldStart = f.onStart;
            f.onStart = id -> {
                if (Set.of("A", "B").contains(id)) {
                    assertThrows(IllegalStateException.class, () -> f.engine.clearLevelRegionTasks(f.level));
                    assertThrows(IllegalStateException.class, f.engine::shutdown);
                }
                heldStart.accept(id);
            };
            final var cleanup = new Thread(() -> {
                try {
                    await(entered);
                    if (shutdown) f.engine.shutdown();
                    else f.engine.clearLevelRegionTasks(f.level);
                    f.event("lifecycle", "returned");
                } catch (Throwable failure) {
                    cleanupFailure.set(failure);
                } finally {
                    cleanupDone.countDown();
                }
            }, "canonical-lifecycle-controller");
            final var normal = f.barrier;
            final var firstServerWait = new AtomicBoolean();
            f.barrier = done -> {
                dispatch(f);
                if (Thread.currentThread() == f.serverThread && firstServerWait.compareAndSet(false, true)) {
                    await(entered);
                    await(f.lifecycleWriteHeld);
                    assertEquals(1, cleanupDone.getCount(), "the real lifecycle write lock is held, but cleanup must await the published phase");
                    assertEquals(0, count(f, "U", "body-start"));
                    release.countDown();
                }
                normal.await(done);
            };
            cleanup.start();
            try {
                f.levelPhase();
                cleanup.join(10_000);
                assertFalse(cleanup.isAlive());
                assertNull(cleanupFailure.get());
                f.before("A", "body-end", "lifecycle", "returned");
                f.before("B", "body-end", "lifecycle", "returned");
                f.before("U", "body-end", "lifecycle", "returned");
                if (waves) f.before("B", "changed", "lifecycle", "returned");
                f.clean();
                if (!shutdown) f.probes();
            } finally {
                release.countDown();
                cleanup.join(10_000);
                dump("lifecycle-waves=" + waves + "-shutdown=" + shutdown, f);
            }
        }
    }

    @Test
    void unloadCannotRetireThePhaseWhileTheCanonicalFurnaceCommitIsBlocked() throws InterruptedException {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            prefix(f, true);
            f.enable(true);
            final var commitEntered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var failure = new AtomicReference<Throwable>();
            final var returned = new AtomicBoolean();
            f.onChange = id -> {
                if (id.equals("A")) {
                    commitEntered.countDown();
                    await(release);
                }
            };
            final var unload = new Thread(() -> {
                try {
                    await(commitEntered);
                    f.engine.clearLevelRegionTasks(f.level);
                    returned.set(true);
                    f.event("unload", "returned");
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "canonical-commit-unload");
            final var controller = new Thread(() -> {
                try {
                    await(f.lifecycleWriteHeld);
                    assertFalse(returned.get());
                    assertTrue(f.engine.diagnostics().runningRegionCount() > 0);
                    f.event("controller", "release-commit");
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    release.countDown();
                }
            }, "canonical-commit-controller");
            unload.start();
            controller.start();
            try {
                f.levelPhase();
                unload.join(10_000);
                controller.join(10_000);
                assertFalse(unload.isAlive());
                assertFalse(controller.isAlive());
                assertNull(failure.get());
                f.before("A", "changed", "unload", "returned");
                f.before("B", "changed", "unload", "returned");
                f.clean();
                f.probes();
            } finally {
                release.countDown();
                unload.join(10_000);
                controller.join(10_000);
                dump("commit-unload", f);
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [waves={0}]")
    @ValueSource(booleans = { false, true })
    void fixedExecutorRejectionRunsEachCompleteTickerOnceOnTheServer(boolean waves) {
        final var oracle = serial(f -> prefix(f, waves));
        try (var f = new CanonicalBlockEntityTestSupport()) {
            prefix(f, waves);
            // This explicit fixed-only fault is not reported as a persistent executor rejection test.
            f.enable(waves, false);
            f.controlled.reject = true;
            try {
                f.levelPhase();
                if (waves) {
                    assertEquals(0, f.engine.furnaceWorkMetrics().nativeCompletions());
                    assertEquals(1, f.furnaces.get("A").getItem(2).getCount());
                    assertEquals(1, f.furnaces.get("B").getItem(2).getCount());
                } else {
                    assertEquals(1, f.cells.get("A").get());
                    assertEquals(1, f.cells.get("B").get());
                }
                assertTrue(f.trace().stream().filter(e -> e.action().equals("body-start") || e.action().equals("changed")).noneMatch(CanonicalBlockEntityTestSupport.Event::worker));
                assertEquals(oracle.state(), f.state(), "a second original tick must also be detected through burn/cook counters, input and components");
                f.clean();
            } finally {
                dump("fixed-rejection-waves=" + waves, f);
            }
        }
    }

    private static void prefix(CanonicalBlockEntityTestSupport f, boolean waves) {
        if (waves) {
            f.furnace("A", FIRST, 1);
            f.furnace("B", FIRST.offset(3, 0, 0), 1);
        } else {
            f.script("A", FIRST, "minecraft:hopper", () -> {});
            f.script("B", SECOND_REGION, "minecraft:hopper", () -> {});
        }
    }

    private static long count(CanonicalBlockEntityTestSupport f, String id, String action) {
        return f.trace().stream().filter(e -> e.id().equals(id) && e.action().equals(action)).count();
    }

    private static String ticketState(CanonicalBlockEntityTestSupport f, String id) {
        final Object ticket = f.observedTickets.get(id);
        assertNotNull(ticket, "ticket must have come from the actual active batch");
        return get(ticket.getClass(), ticket, "state").toString();
    }

    private static void hold(CanonicalBlockEntityTestSupport f, Set<String> ids, CountDownLatch entered, CountDownLatch release) {
        f.onStart = id -> {
            if (ids.contains(id)) {
                assertTrue(Thread.currentThread() != f.serverThread);
                entered.countDown();
                await(release);
            }
        };
    }

    private static void dispatch(CanonicalBlockEntityTestSupport f) {
        if (f.controlled != null) f.controlled.dispatchAll();
    }

    private static boolean hasCause(Throwable actual, Throwable expected) {
        for (Throwable cursor = actual; cursor != null; cursor = cursor.getCause()) if (cursor == expected) return true;
        return false;
    }

    /** Observe, without bypassing, the very latch on which the OLD driver physically joins its tasks. */
    private static void observePhysicalJoin(CanonicalBlockEntityTestSupport f, BooleanSupplier condition, boolean waves, CountDownLatch entered) {
        Object barrier = null;
        if (waves) {
            for (var field : condition.getClass().getDeclaredFields()) {
                if (!field.getType().getName().endsWith("RegionTaskBatch$Barrier")) continue;
                barrier = get(condition.getClass(), condition, field.getName());
            }
        } else {
            final Object batch = f.observedBatches.iterator().next();
            barrier = get(batch.getClass().getSuperclass().getSuperclass(), batch, "groupBarrier");
        }
        assertNotNull(barrier, "physical join observation must use the actual barrier, never a simulated one");
        final var original = (CountDownLatch) get(barrier.getClass(), barrier, "completed");
        set(barrier.getClass(), barrier, "completed", new CountDownLatch(1) {
            @Override
            public void await() throws InterruptedException {
                f.event("phase", "physical-join");
                entered.countDown();
                original.await();
            }

            @Override
            public void countDown() {
                original.countDown();
            }

            @Override
            public long getCount() {
                return original.getCount();
            }

            @Override
            public boolean await(long time, TimeUnit unit) throws InterruptedException {
                entered.countDown();
                return original.await(time, unit);
            }
        });
    }
}
