/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;
import com.simibubi.create.content.kinetics.millstone.MillingRecipe;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.forgia.compat.create.CreateAudit;
import net.neoforged.forgia.compat.create.CreateTickAdmission;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class CreateTickIntegrationTest {
    private static final BlockPos FIRST = new BlockPos(17, 64, 17);
    private static final BlockPos SECOND = new BlockPos(65, 64, 17);

    @Test
    void originalBytecodeAndActuallyMergedAccessorsAreVerified() {
        assertTrue(CreateAudit.verify());
    }

    @Test
    void independentMillstonesExecuteOriginalTicksConcurrently() {
        try (final var fixture = new CreateFixture()) {
            final var first = fixture.millstone(FIRST, 100, 64);
            final var second = fixture.millstone(SECOND, 100, 128);
            final var rendezvous = new CyclicBarrier(2);
            final List<Thread> threads = new CopyOnWriteArrayList<>();
            fixture.tick(fixture.ticker(first, () -> {
                threads.add(Thread.currentThread());
                if (Thread.currentThread() != fixture.main) await(rendezvous);
                first.tick();
            }), fixture.ticker(second, () -> {
                threads.add(Thread.currentThread());
                if (Thread.currentThread() != fixture.main) await(rendezvous);
                second.tick();
            }));
            assertEquals(96, first.timer);
            assertEquals(92, second.timer);
            assertEquals(2, threads.size());
            assertTrue(threads.stream().noneMatch(thread -> thread == fixture.main), threads.toString());
            assertNotSame(threads.get(0), threads.get(1), "both original bodies must reach the barrier concurrently");
            assertEquals(2, fixture.status().workerCompletions());
            assertEquals(0, fixture.engine.diagnostics().tickFailures().total());
            assertEquals(0, fixture.engine.diagnostics().ownershipViolationCount());
        }
    }

    @Test
    void aCachedCandidateRefusesCompletionAndRunsTheOriginalRecipeOnceOnTheServer() {
        try (final var fixture = new CreateFixture()) {
            final var millstone = fixture.millstone(FIRST, 8, 64);
            millstone.inputInv.setStackInSlot(0, new ItemStack(Items.WHEAT, 3));
            final MillingRecipe recipe = mock(MillingRecipe.class);
            when(recipe.matches(any(), eq(fixture.level))).thenAnswer(call -> {
                assertSame(fixture.main, Thread.currentThread());
                return true;
            });
            final AtomicInteger rolls = new AtomicInteger();
            when(recipe.rollResults(any())).thenAnswer(call -> {
                assertSame(fixture.main, Thread.currentThread());
                assertSame(fixture.level.random, call.getArgument(0));
                rolls.incrementAndGet();
                ((RandomSource) call.getArgument(0)).nextInt(100);
                return List.of(new ItemStack(Items.FLINT));
            });
            CreateFixture.set(MillstoneBlockEntity.class, millstone, "lastRecipe", recipe);
            final var ticker = fixture.ticker(millstone, millstone::tick);
            fixture.tick(ticker);
            final var cache = fixture.chunks.get(net.minecraft.world.level.ChunkPos.asLong(FIRST)).blockEntityTickAdmissionCache();
            assertEquals(4, millstone.timer);
            assertEquals(0, rolls.get());
            fixture.tick(ticker);
            assertSame(cache, fixture.chunks.get(net.minecraft.world.level.ChunkPos.asLong(FIRST)).blockEntityTickAdmissionCache());
            assertEquals(0, millstone.timer);
            assertEquals(1, rolls.get());
            assertEquals(2, millstone.inputInv.getStackInSlot(0).getCount());
            assertEquals(1, millstone.outputInv.getStackInSlot(0).getCount());
            assertEquals(1, fixture.status().workerCompletions());
            assertEquals(1, fixture.status().reasons().get(Decision.CALLBACK_REQUIRED));
            final RandomSource reference = RandomSource.create(12345);
            reference.nextInt(100);
            assertEquals(reference.nextLong(), fixture.level.random.nextLong(), "steady tick must not consume the recipe RNG");
        }
    }

    @Test
    void timerStartRetainsTheOriginalRecipeDurationAndCallback() {
        try (final var fixture = new CreateFixture()) {
            final var millstone = fixture.millstone(FIRST, 0, 64);
            millstone.inputInv.setStackInSlot(0, new ItemStack(Items.WHEAT, 3));
            final MillingRecipe recipe = mock(MillingRecipe.class);
            when(recipe.matches(any(), eq(fixture.level))).thenAnswer(call -> {
                assertSame(fixture.main, Thread.currentThread());
                return true;
            });
            when(recipe.getProcessingDuration()).thenReturn(73);
            CreateFixture.set(MillstoneBlockEntity.class, millstone, "lastRecipe", recipe);
            fixture.tick(fixture.ticker(millstone, millstone::tick));
            assertEquals(73, millstone.timer);
            assertEquals(3, millstone.inputInv.getStackInSlot(0).getCount());
            verify(recipe, never()).rollResults(any());
            assertEquals(0, fixture.status().workerCompletions());
            assertEquals(1, fixture.status().reasons().get(Decision.CALLBACK_REQUIRED));
        }
    }

    @Test
    void steadyTickTraceEqualsOriginalSerialTicksAtDifferentSpeedsAndOutputStates() {
        try (final var fixture = new CreateFixture()) {
            for (final float speed : new float[] { 0, 1, -64, 128, 8192 }) {
                final var worker = fixture.millstone(FIRST, 100000, speed);
                final var serial = fixture.millstone(SECOND, 100000, speed);
                final var ticker = fixture.ticker(worker, worker::tick);
                for (int tick = 0; tick < 12; ++tick) {
                    if (tick == 4) {
                        worker.outputInv.setStackInSlot(0, new ItemStack(Items.FLINT, 64));
                        serial.outputInv.setStackInSlot(0, new ItemStack(Items.FLINT, 64));
                    }
                    if (tick == 8) {
                        worker.outputInv.setStackInSlot(0, ItemStack.EMPTY);
                        serial.outputInv.setStackInSlot(0, ItemStack.EMPTY);
                    }
                    fixture.tick(ticker);
                    serial.tick();
                    assertEquals(serial.timer, worker.timer, "speed=" + speed + " tick=" + tick);
                    assertEquals(serial.getSpeed(), worker.getSpeed());
                    for (int slot = 0; slot < 9; ++slot) assertTrue(ItemStack.matches(serial.outputInv.getStackInSlot(slot), worker.outputInv.getStackInSlot(slot)));
                }
                fixture.chunks.clear();
            }
            assertTrue(fixture.status().workerCompletions() > 0);
        }
    }

    @Test
    void unknownBehaviourStillTicksExactlyOnceOnTheServer() {
        try (final var fixture = new CreateFixture()) {
            final var millstone = fixture.millstone(FIRST, 100, 64);
            final AtomicInteger callbacks = new AtomicInteger();
            millstone.attachBehaviourLate(new BlockEntityBehaviour(millstone) {
                private final BehaviourType<BlockEntityBehaviour> type = new BehaviourType<>();

                @Override
                public BehaviourType<?> getType() {
                    return this.type;
                }

                @Override
                public void tick() {
                    assertSame(fixture.main, Thread.currentThread());
                    callbacks.incrementAndGet();
                }
            });
            fixture.tick(fixture.ticker(millstone, millstone::tick));
            assertEquals(1, callbacks.get());
            assertEquals(96, millstone.timer);
            assertEquals(1, fixture.status().reasons().get(Decision.UNKNOWN_BEHAVIOUR));
            assertEquals(0, fixture.status().workerCompletions());
        }
    }

    @Test
    void aModdedSubclassCannotInheritAnAuditedTypeAdmission() {
        try (final var fixture = new CreateFixture()) {
            final var millstone = new MillstoneBlockEntity(AllBlockEntityTypes.MILLSTONE.get(), FIRST, AllBlocks.MILLSTONE.getDefaultState()) {};
            fixture.steady(millstone, 64);
            millstone.timer = 100;
            final AtomicReference<Thread> thread = new AtomicReference<>();
            fixture.tick(fixture.ticker(millstone, () -> {
                thread.set(Thread.currentThread());
                millstone.tick();
            }));
            assertSame(fixture.main, thread.get());
            assertEquals(96, millstone.timer);
            assertEquals(1, fixture.status().reasons().get(Decision.UNKNOWN_CLASS));
        }
    }

    @Test
    void customInventoryHandlerRetainsItsFullSerialTick() {
        try (final var fixture = new CreateFixture()) {
            final var millstone = fixture.millstone(FIRST, 100, 64);
            millstone.outputInv = new ItemStackHandler(9) {};
            final AtomicReference<Thread> thread = new AtomicReference<>();
            fixture.tick(fixture.ticker(millstone, () -> {
                thread.set(Thread.currentThread());
                millstone.tick();
            }));
            assertSame(fixture.main, thread.get());
            assertEquals(96, millstone.timer);
            assertEquals(1, fixture.status().reasons().get(Decision.SHARED_RESOURCE));
        }
    }

    @Test
    void inheritedInitializationTopologyAndParticlePathsAreRefusedBeforeEffects() {
        try (final var fixture = new CreateFixture()) {
            final var millstone = fixture.millstone(FIRST, 100, 64);
            fixture.attach(millstone);
            final var gate = new CreateTickAdmission();
            gate.prepare(fixture.level);
            assertEquals(Decision.ADMITTED, gate.check(millstone));
            CreateFixture.set(SmartBlockEntity.class, millstone, "initialized", false);
            assertEquals(Decision.INITIALIZING, gate.check(millstone));
            CreateFixture.set(SmartBlockEntity.class, millstone, "initialized", true);
            millstone.updateSpeed = true;
            assertEquals(Decision.TOPOLOGY_CHANGE, gate.check(millstone));
            millstone.updateSpeed = false;
            millstone.networkDirty = true;
            assertEquals(Decision.TOPOLOGY_CHANGE, gate.check(millstone));
            millstone.networkDirty = false;
            CreateFixture.set(KineticBlockEntity.class, millstone, "validationCountdown", 0);
            assertEquals(Decision.TOPOLOGY_CHANGE, gate.check(millstone));
            CreateFixture.set(KineticBlockEntity.class, millstone, "validationCountdown", 100);
            final var effects = ((KineticBlockEntityAccessor) millstone).forgia$effects();
            CreateFixture.set(KineticEffectHandler.class, effects, "particleSpawnCountdown", 1);
            assertEquals(Decision.CALLBACK_REQUIRED, gate.check(millstone));
            assertEquals(100, millstone.timer);
        }
    }

    @Test
    void configuredEightChunkRegionsAdmitInteriorMachinesAndKeepBorderMachinesSerial() {
        try (final var fixture = new CreateFixture(8)) {
            final var interior = fixture.millstone(new BlockPos(145, 64, 17), 100, 64);
            final var border = fixture.millstone(new BlockPos(128, 64, 17), 100, 64);
            final AtomicReference<Thread> interiorThread = new AtomicReference<>();
            final AtomicReference<Thread> borderThread = new AtomicReference<>();
            fixture.tick(fixture.ticker(interior, () -> {
                interiorThread.set(Thread.currentThread());
                interior.tick();
            }));
            fixture.tick(fixture.ticker(border, () -> {
                borderThread.set(Thread.currentThread());
                border.tick();
            }));
            assertNotSame(fixture.main, interiorThread.get());
            assertSame(fixture.main, borderThread.get());
            assertEquals(96, interior.timer);
            assertEquals(96, border.timer);
            assertEquals(1, fixture.status().workerCompletions());
            assertEquals(1, fixture.engine.blockEntityFallbackAdmissionCounts().get("boundaryChunk"));
        }
    }

    @Test
    void originalShaftTickUsesItsRealBehaviourAndCountdownOnAWorker() {
        try (final var fixture = new CreateFixture()) {
            final var shaft = new BracketedKineticBlockEntity(AllBlockEntityTypes.BRACKETED_KINETIC.get(), FIRST, AllBlocks.SHAFT.getDefaultState());
            fixture.steady(shaft, 64);
            final AtomicReference<Thread> thread = new AtomicReference<>();
            fixture.tick(fixture.ticker(shaft, () -> {
                thread.set(Thread.currentThread());
                shaft.tick();
            }));
            assertNotSame(fixture.main, thread.get());
            assertEquals(999, ((KineticBlockEntityAccessor) shaft).forgia$validationCountdown());
            assertEquals(1, fixture.status().workerCompletions());
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("independent original ticks did not overlap", failure);
        }
    }
}
