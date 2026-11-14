/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual admitted original bodies plus fault/queue observations, not a second scheduler or authority. */
@Tag("canonical-be-contract")
@Timeout(120)
class CanonicalBlockEntityInvocationEffectsTest {
    private static final BlockPos A = new BlockPos(20, 64, 20);
    private static final BlockPos B = new BlockPos(68, 64, 20);
    private static final BlockPos U = new BlockPos(116, 64, 20);

    @Test
    void invocationOrderWinsOverReversedWorkerEnqueueArrival() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final List<String> committed = new ArrayList<>();
            final var bQueued = new CountDownLatch(1);
            f.script("A", A, "minecraft:hopper", () -> {
                await(bQueued);
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> committed.add("A0")));
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> committed.add("A1")));
                f.event("A", "queued");
            });
            f.script("B", B, "minecraft:hopper", () -> {
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> committed.add("B0")));
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> committed.add("B1")));
                f.event("B", "queued");
                bQueued.countDown();
            });
            f.script("U", U, "test:unknown", () -> assertEquals(List.of("A0", "A1", "B0", "B1"), committed));
            f.enable(false);
            f.levelPhase();
            assertEquals(List.of("A0", "A1", "B0", "B1"), committed);
            f.before("B", "queued", "A", "queued");
            f.before("A", "body-end", "U", "body-start");
            f.before("B", "body-end", "U", "body-start");
            f.engine.endLevelTick(f.level);
            assertEquals(4, committed.size());
            f.clean();
            f.probes();
            System.out.println("C3_B_REVERSED_ARRIVAL_CANONICAL_COMMIT " + committed + " " + f.trace());
        }
    }

    @Test
    void genuineLevelEndTasksStayAfterTheActualPostEventWhileInvocationEffectsReachTheFence() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var invocation = new AtomicInteger();
            final var levelEnd = new AtomicInteger();
            final var posts = new AtomicInteger();
            f.script("A", A, "minecraft:hopper", () -> {
                assertTrue(f.engine.queueServerTaskInCurrentBatch(invocation::incrementAndGet));
                assertTrue(f.engine.queueLevelEndServerTaskInCurrentBatch(() -> {
                    assertEquals(1, posts.get());
                    levelEnd.incrementAndGet();
                    f.event("level-end", "applied");
                }));
            });
            f.script("U", U, "test:unknown", () -> {
                assertEquals(1, invocation.get());
                assertEquals(0, levelEnd.get());
            });
            f.enable(false);
            final Consumer<LevelTickEvent.Post> listener = event -> {
                if (event.getLevel() == f.level) {
                    assertEquals(0, levelEnd.get());
                    posts.incrementAndGet();
                    f.event("post", "observed");
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            try {
                f.levelPhase();
                assertEquals(0, levelEnd.get());
                EventHooks.fireLevelTickPost(f.level, () -> false);
                assertEquals(1, invocation.get());
                assertEquals(1, levelEnd.get());
                assertEquals(1, posts.get());
                f.engine.endLevelTick(f.level);
                assertEquals(1, levelEnd.get());
                f.before("U", "body-end", "post", "observed");
                f.before("post", "observed", "level-end", "applied");
                f.clean();
                f.probes();
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
        }
    }

    @Test
    void aCommitFailureRetiresEachEffectOnceAndStillCommitsOtherStartedInvocations() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var failure = new IllegalStateException("C3-B commit after effect");
            final List<String> committed = new ArrayList<>();
            f.script("A", A, "minecraft:hopper", () -> {
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> {
                    committed.add("A0");
                    throw failure;
                }));
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> committed.add("A1")));
            });
            f.script("B", B, "minecraft:hopper", () -> assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> committed.add("B0"))));
            f.script("U", U, "test:unknown", () -> {});
            f.enable(false);
            assertSame(failure, assertThrows(IllegalStateException.class, f::levelPhase));
            assertEquals(List.of("A0", "A1", "B0"), committed);
            assertEquals(1, f.cells.get("A").get());
            assertEquals(1, f.cells.get("B").get());
            assertEquals(0, f.cells.get("U").get());
            f.engine.endLevelTick(f.level);
            assertEquals(3, committed.size());
            f.clean();
            f.probes();
        }
    }

    @Test
    void partialEffectsOfAFailedStartedBodyAreCommittedWithoutReplayingItsUnstartedTail() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var failure = new IllegalStateException("C3-B body after queued effect");
            final List<String> committed = new ArrayList<>();
            f.script("A", A, "minecraft:hopper", () -> {
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> committed.add("A")));
                throw failure;
            });
            f.script("C", A.east(), "minecraft:hopper", () -> {});
            f.script("B", B, "minecraft:hopper", () -> assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> committed.add("B"))));
            f.enable(false);
            assertTrue(hasCause(assertThrows(RuntimeException.class, f::levelPhase), failure));
            assertEquals(List.of("A", "B"), committed);
            assertEquals(1, f.cells.get("A").get());
            assertEquals(0, f.cells.get("C").get());
            assertEquals(1, f.cells.get("B").get());
            f.clean();
            f.probes();
        }
    }

    private static boolean hasCause(Throwable actual, Throwable expected) {
        if (actual == expected) return true;
        if (actual.getCause() != null && hasCause(actual.getCause(), expected)) return true;
        for (final var suppressed : actual.getSuppressed()) if (hasCause(suppressed, expected)) return true;
        return false;
    }

    @Test
    void aV1AdmissionBooleanDoesNotCertifyAnUnknownOriginalBody() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var checks = new AtomicInteger();
            final var calls = new AtomicInteger();
            final var source = new ChestBlockEntity(B, Blocks.CHEST.defaultBlockState());
            source.setLevel(f.level);
            f.world.blockEntities.put(B, source);
            f.world.states.put(B, source.getBlockState());
            f.names.put(B, "G");
            final Method bind = CanonicalBlockEntityTestSupport.class.getDeclaredMethod("bound", String.class, BlockEntity.class, BlockEntityTicker.class);
            bind.setAccessible(true);
            CanonicalBlockEntityTestSupport.invoke(bind, f, "G", source, (BlockEntityTicker<ChestBlockEntity>) (level, pos, state, be) -> {
                assertSame(f.serverThread, Thread.currentThread());
                calls.incrementAndGet();
            });
            f.script("A", A, "minecraft:hopper", () -> {});
            f.enable(false);
            ParallelAdapterRuntime.initialize(f.server, registrar -> registrar.registerBlockEntityTicks(1,
                    ResourceLocation.parse("test:unknown_body"), Set.of(ResourceLocation.parse("minecraft:chest")), be -> {
                        checks.incrementAndGet();
                        return BlockEntityTickAdapter.Decision.ADMITTED;
                    }));
            f.levelPhase();
            assertEquals(1, calls.get());
            assertEquals(0, checks.get(), "unknown body is rejected before consulting a Worker predicate");
            assertEquals(1, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
            f.probes();
        }
    }
}
