/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.neoforged.forgia.compat.create.CreateTickAdmission;
import net.neoforged.forgia.compat.create.mixin.TorquePropagatorAccessor;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class CreateNetworkIntegrationTest {
    private static final BlockPos FIRST = new BlockPos(17, 64, 17);
    private static final BlockPos SECOND = new BlockPos(65, 64, 17);

    @Test
    void anotherMembersDirtyNetworkPreventsAWorkerUsingAnOutdatedStressState() {
        try (final var fixture = new CreateFixture()) {
            final var first = fixture.millstone(FIRST, 100, 64);
            final var second = fixture.millstone(SECOND, 100, 64);
            fixture.attach(first);
            fixture.attach(second);
            final KineticNetwork network = TorquePropagatorAccessor.forgia$networks().get(fixture.level).get(first.network);
            second.network = first.network;
            second.networkDirty = true;
            network.members.put(second, 0f);
            final var gate = new CreateTickAdmission();
            gate.prepare(fixture.level);
            assertEquals(Decision.SHARED_RESOURCE, gate.check(first));
            assertEquals(Decision.TOPOLOGY_CHANGE, gate.check(second));
            second.networkDirty = false;
            gate.prepare(fixture.level);
            assertEquals(Decision.ADMITTED, gate.check(first));
        }
    }

    @Test
    void unknownMachineOrMissingLoadedMemberRetainsTheEntireNetwork() {
        try (final var fixture = new CreateFixture()) {
            final var known = fixture.millstone(FIRST, 100, 64);
            final var unknown = new MillstoneBlockEntity(AllBlockEntityTypes.MILLSTONE.get(), SECOND, AllBlocks.MILLSTONE.getDefaultState()) {};
            fixture.steady(unknown, 64);
            fixture.attach(known);
            fixture.attach(unknown);
            final var network = TorquePropagatorAccessor.forgia$networks().get(fixture.level).get(known.network);
            unknown.network = known.network;
            network.members.put(unknown, 0f);
            final var gate = new CreateTickAdmission();
            gate.prepare(fixture.level);
            assertEquals(Decision.SHARED_RESOURCE, gate.check(known));
            network.members.remove(unknown);
            gate.prepare(fixture.level);
            assertEquals(Decision.ADMITTED, gate.check(known));
            fixture.chunks.get(net.minecraft.world.level.ChunkPos.asLong(FIRST)).getBlockEntities().clear();
            gate.prepare(fixture.level);
            assertEquals(Decision.SHARED_RESOURCE, gate.check(known));
        }
    }

    @Test
    void stableWaterWheelAndMillstoneNetworkUseWorkersUntilTheFlowRecheck() {
        try (final var fixture = new CreateFixture()) {
            final var wheel = new WaterWheelBlockEntity(AllBlockEntityTypes.WATER_WHEEL.get(), FIRST, AllBlocks.WATER_WHEEL.getDefaultState());
            fixture.steady(wheel, 8);
            wheel.flowScore = 1;
            final var millstone = fixture.millstone(SECOND, 100, 8);
            millstone.network = wheel.network;
            final var network = TorquePropagatorAccessor.forgia$networks().get(fixture.level).get(wheel.network);
            network.members.put(millstone, 0f);
            network.sources.put(wheel, 512f);
            final var wheelTicker = fixture.ticker(wheel, wheel::tick);
            final AtomicReference<Thread> thread = new AtomicReference<>();
            final var millstoneTicker = fixture.ticker(millstone, () -> {
                thread.set(Thread.currentThread());
                millstone.tick();
            });
            fixture.tick(wheelTicker, millstoneTicker);
            assertTrue(thread.get() != fixture.main);
            assertEquals(99, millstone.timer);
            assertEquals(2, fixture.status().workerCompletions());
            CreateFixture.set(SmartBlockEntity.class, wheel, "lazyTickCounter", 0);
            final var gate = new CreateTickAdmission();
            gate.prepare(fixture.level);
            assertEquals(Decision.TOPOLOGY_CHANGE, gate.check(wheel));
            assertEquals(Decision.SHARED_RESOURCE, gate.check(millstone));
        }
    }

    @Test
    void twoNetworksSharingAnInventoryCannotRunAsIndependentOwners() {
        try (final var fixture = new CreateFixture()) {
            final var first = fixture.millstone(FIRST, 100, 64);
            final var second = fixture.millstone(SECOND, 100, 64);
            fixture.attach(first);
            fixture.attach(second);
            second.outputInv = first.outputInv;
            final var gate = new CreateTickAdmission();
            gate.prepare(fixture.level);
            assertEquals(Decision.SHARED_RESOURCE, gate.check(first));
            assertEquals(Decision.SHARED_RESOURCE, gate.check(second));
        }
    }

    @Test
    void expiryOfTheNetworkSnapshotNeverBecomesAFutureLease() {
        try (final var fixture = new CreateFixture()) {
            final var millstone = fixture.millstone(FIRST, 100, 64);
            fixture.attach(millstone);
            final var gate = new CreateTickAdmission();
            gate.prepare(fixture.level);
            assertEquals(Decision.ADMITTED, gate.check(millstone));
            org.mockito.Mockito.when(fixture.level.getGameTime()).thenReturn(1L);
            assertEquals(Decision.SHARED_RESOURCE, gate.check(millstone));
            assertSame(fixture.level, millstone.getLevel());
        }
    }
}
