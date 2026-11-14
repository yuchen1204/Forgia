/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.await;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real original Native/Furnace bodies through one phase's active batch, kernel and canonical cursor. */
@Tag("canonical-be-contract")
@Timeout(120)
class CanonicalBlockEntityFurnaceIntegrationTest {
    private static final BlockPos A = new BlockPos(20, 64, 20);
    private static final BlockPos B = new BlockPos(68, 64, 20);
    private static final BlockPos U = new BlockPos(116, 64, 20);

    @Test
    void nativeAndFurnacePrefixesOverlapOnRealWorkersAndShareExactlyOnePhaseKernel() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.script("N0", A, "minecraft:hopper", () -> {});
            f.script("N1", B, "minecraft:hopper", () -> {});
            f.furnace("F0", A.east(3), 1);
            f.furnace("F1", B.east(3), 1);
            f.script("U", U, "test:unknown", () -> {
                assertEquals(1, f.cells.get("N0").get());
                assertEquals(1, f.cells.get("N1").get());
                assertEquals(1, f.furnaces.get("F0").getItem(2).getCount());
                assertEquals(1, f.furnaces.get("F1").getItem(2).getCount());
            });
            f.script("N2", A.east(6), "minecraft:hopper", () -> {});
            f.furnace("F2", A.east(9), 1);
            f.furnace("F3", B.east(9), 1);
        };
        final Map<String, String> oracle = CanonicalBlockEntitySequenceTest.serial(input).state();
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(true);
            final var nativeEntered = new CountDownLatch(2);
            final var furnaceEntered = new CountDownLatch(2);
            final var nativeActive = new AtomicInteger();
            final var furnaceActive = new AtomicInteger();
            final var nativeMaximum = new AtomicInteger();
            final var furnaceMaximum = new AtomicInteger();
            f.onStart = id -> {
                final boolean nativePrefix = Set.of("N0", "N1").contains(id);
                final boolean furnacePrefix = Set.of("F0", "F1").contains(id);
                if (!nativePrefix && !furnacePrefix) return;
                final var active = nativePrefix ? nativeActive : furnaceActive;
                final var maximum = nativePrefix ? nativeMaximum : furnaceMaximum;
                final var entered = nativePrefix ? nativeEntered : furnaceEntered;
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                    await(entered);
                    assertEquals(1, ((Set<?>) get(f.engine.getClass(), f.engine, "activeTickBatches")).size());
                } finally {
                    active.decrementAndGet();
                }
            };
            f.levelPhase();
            assertEquals(2, nativeMaximum.get());
            assertEquals(2, furnaceMaximum.get());
            assertEquals(4, f.engine.furnaceWorkMetrics().nativeCompletions());
            assertEquals(7, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(Boolean.getBoolean("forgia.furnace.resourceOwnership") ? 4 : 0, f.engine.blockEntityResourceMetrics().issuedLeases());
            assertEquals(1, f.observedBatches.size());
            assertEquals(8, f.observedTickets.size());
            f.before("N0", "body-end", "F0", "body-start");
            f.before("N1", "body-end", "F1", "body-start");
            f.before("F0", "changed", "F1", "changed");
            f.before("F1", "changed", "U", "body-start");
            f.before("U", "body-end", "N2", "body-start");
            f.before("U", "body-end", "F2", "body-start");
            assertEquals(oracle, f.state());
            f.clean();
            f.probes();
            System.out.println("C4_ONE_PHASE_NATIVE_OVERLAP=" + nativeMaximum.get() + " FURNACE_OVERLAP=" + furnaceMaximum.get()
                    + " batches=" + f.observedBatches.size() + " " + f.trace());
        }
    }

    @Test
    void aNativeFenceInvalidatesFurnaceComponentsBeforeAnyLaterPreparation() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.furnace("F0", A, 1);
            f.furnace("F1", A.east(3), 1);
            f.script("N", B, "minecraft:hopper", () -> {});
            f.script("U", U, "test:components", () -> f.furnaces.get("F2").getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("after canonical fence")));
            f.furnace("F2", A.east(6), 1);
            f.furnace("F3", A.east(9), 1);
        };
        final var oracle = CanonicalBlockEntitySequenceTest.serial(input).state();
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(true);
            f.levelPhase();
            assertEquals(2, f.engine.furnaceWorkMetrics().nativeCompletions());
            assertEquals(3, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(1, f.observedBatches.size());
            assertEquals(oracle, f.state());
            f.before("F1", "changed", "N", "body-start");
            f.before("U", "body-end", "F2", "changed");
            f.clean();
            f.probes();
        }
    }

    @Test
    void furnaceAliasesRemainWholePhaseEvenWhenANativePrefixAndUnknownFenceSeparateTheOwners() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.script("N0", A, "minecraft:hopper", () -> {});
            f.script("N1", B, "minecraft:hopper", () -> {});
            f.furnace("F0", A.east(3), 1);
            f.furnace("F1", A.east(6), 1);
            f.script("U", U, "test:unknown", () -> {});
            f.furnace("F2", B.east(3), 1);
            final var shared = new ItemStack(Items.IRON_ORE, 6);
            f.furnaces.get("F0").setItem(0, shared);
            f.furnaces.get("F2").setItem(0, shared);
        };
        final var oracle = CanonicalBlockEntitySequenceTest.serial(input).state();
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(true);
            f.levelPhase();
            assertEquals(0, f.engine.furnaceWorkMetrics().nativeCompletions());
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(oracle, f.state());
            assertTrue(f.engine.furnaceWorkMetrics().refusals().getOrDefault(
                    net.neoforged.neoforge.server.threading.region.FurnaceWorkMetrics.Refusal.SHARED_INVENTORY, 0L) > 0);
            assertEquals(1, f.observedBatches.size());
            f.clean();
            f.probes();
        }
    }

    @Test
    void aMailboxCallbackInvalidatesTheWholeFurnacePrefixBeforeAnyWorkerBody() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.furnace("F0", A, 1);
            f.furnace("F1", A.east(3), 1);
            f.script("N0", A.east(6), "minecraft:hopper", () -> {});
            f.script("N1", B, "minecraft:hopper", () -> {});
            f.enable(true);
            final var delivered = new AtomicInteger();
            final var enqueue = f.engine.getClass().getDeclaredMethod("enqueueRegionTask", net.minecraft.server.level.ServerLevel.class, BlockPos.class, Runnable.class);
            enqueue.setAccessible(true);
            CanonicalBlockEntityTestSupport.invoke(enqueue, f.engine, f.level, A, (Runnable) () -> {});
            final var target = net.neoforged.neoforge.server.threading.region.RegionLayout.FIXED_3X3.regionAt(f.level, A);
            assertEquals(net.neoforged.neoforge.server.threading.region.RegionMailbox.PostResult.ACCEPTED,
                    f.engine.mailbox(f.level).post(target, () -> {
                        assertSame(f.serverThread, Thread.currentThread());
                        assertNull(BlockEntityExecutionEngineTest.currentMode());
                        f.furnaces.get("F0").getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("mailbox changed before fresh proof"));
                        f.event("mailbox", "delivered");
                        delivered.incrementAndGet();
                    }));
            f.levelPhase();
            assertEquals(1, delivered.get());
            assertEquals(0, f.engine.furnaceWorkMetrics().nativeCompletions());
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(1, f.furnaces.get("F0").getItem(2).getCount());
            assertEquals(1, f.furnaces.get("F1").getItem(2).getCount());
            f.before("mailbox", "delivered", "F0", "changed");
            assertEquals(1, f.observedBatches.size());
            f.engine.drainRegionTasks(f.level, A);
            f.clean();
            f.probes();
        }
    }

    @Test
    void aFenceCanTurnAnUnprovenLaterHopperIntoTheProvenNativeSubsetAfterFurnaceCommit() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.furnace("F0", A, 1);
            f.furnace("F1", A.east(3), 1);
            f.script("U", U, "test:activate", () -> {
                assertEquals(1, f.furnaces.get("F0").getItem(2).getCount());
                ((HopperBlockEntity) f.world.blockEntities.get(B)).setCooldown(3);
            });
            f.script("N", B, "minecraft:hopper", () -> {});
            ((HopperBlockEntity) f.world.blockEntities.get(B)).setCooldown(0);
            f.enable(true);
            f.levelPhase();
            assertEquals(2, f.engine.furnaceWorkMetrics().nativeCompletions());
            assertEquals(3, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.before("F1", "changed", "U", "body-start");
            f.before("U", "body-end", "N", "body-start");
            assertEquals(1, f.observedBatches.size());
            assertNotNull(f.observedTickets.get("N"));
            f.clean();
            f.probes();
        }
    }
}
