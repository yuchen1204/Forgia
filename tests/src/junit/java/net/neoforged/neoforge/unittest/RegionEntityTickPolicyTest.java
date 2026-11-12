/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the per-type entity policy of the entity slices against the real engine: a declared type is
 * handed to the server thread before its body starts, an undeclared type keeps the region worker, and the
 * decision is counted by its reason.
 * <p>
 * The level mock reports the test's tick list through the seed seam, so the self-driven slice under test is
 * the production one: no entity is collected on the server thread. The entities are mocks of the real entity
 * classes, so the class hierarchy the policy resolves is the vanilla one.
 */
@Timeout(120)
class RegionEntityTickPolicyTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int REGION_CHUNK_SIZE = 3;
    private static final String TEST_REASON = "test: declared for the policy mechanics";

    private RegionThreadingEngine engine;
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.tickList.clear();
        this.engine = null;
    }

    @Test
    void projectileFamiliesAndTheirRidingVehiclesStayOutOfWorkerAdmission() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        for (final Class<? extends Entity> type : List.of(
                net.minecraft.world.entity.projectile.Arrow.class,
                net.minecraft.world.entity.projectile.ThrownTrident.class,
                net.minecraft.world.entity.projectile.SmallFireball.class,
                net.minecraft.world.entity.projectile.ThrownPotion.class,
                net.minecraft.world.entity.projectile.LlamaSpit.class,
                net.minecraft.world.entity.projectile.Snowball.class,
                net.minecraft.world.entity.projectile.ThrownEgg.class)) {
            final Entity projectile = this.entity(type, level, new BlockPos(1, 64, 1));
            final var plan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);
            assertFalse(plan.accept(projectile), type.getSimpleName());
            final Cow vehicle = this.entity(Cow.class, level, new BlockPos(1, 64, 1));
            when(vehicle.getPassengers()).thenReturn(List.of(projectile));
            assertFalse(plan.accept(vehicle), "a vehicle cannot bypass its projectile passenger's restriction");
            verify(projectile, never()).tick();
        }
        final Cow ordinary = this.entity(Cow.class, level, new BlockPos(1, 64, 1));
        assertTrue(this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER).accept(ordinary));
        assertEquals(14L, this.engine.diagnostics().serverThreadOnlyEntityTicksByReason().get("projectile_impact_callbacks"));
    }

    @Test
    void realShulkerLidPushRunsOnceOnTheServerAndKeepsOtherMobsOnWorkers() throws Throwable {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread serverThread = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(invocation -> Thread.currentThread() == serverThread);
        this.engine.addRegionSection(level, 2, 0);
        this.engine.addRegionSection(level, 3, 0);
        final var shulker = this.entity(Shulker.class, level, new BlockPos(47, 64, 1));
        final var target = new PeekMoveTarget(level);
        target.setPos(48.0, 65.0, 1.5);
        target.onAddedToLevel();
        final var peek = this.realPeek(shulker, level, target);
        doAnswer(invocation -> {
            peek.invoke(shulker);
            return null;
        }).when(shulker).tick();
        final var cow = this.entity(Cow.class, level, new BlockPos(49, 64, 1));
        final List<Thread> otherThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            otherThreads.add(Thread.currentThread());
            return null;
        }).when(cow).tick();
        this.tickList.add(shulker);
        this.tickList.add(cow);
        this.plan(level).executeAndAwait();
        assertEquals(1, target.moves.get());
        assertEquals(net.minecraft.world.entity.MoverType.SHULKER, target.lastMover);
        assertEquals(66.0, target.getY(), 1.0E-6, "lid pushing is preserved, not skipped");
        assertEquals(List.of(serverThread), target.threads);
        verify(shulker, times(1)).tick();
        verify(cow, times(1)).tick();
        assertEquals(1, otherThreads.size());
        assertTrue(otherThreads.getFirst().getName().startsWith("NeoForge Region Worker"));
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
        assertEquals(Map.of("shulker_peek_push", 1L), this.engine.diagnostics().serverThreadOnlyEntityTicksByReason());
        final var subtype = this.entity(ModdedShulker.class, level, new BlockPos(47, 64, 1));
        assertFalse(this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER).accept(subtype),
                "a modded Shulker subclass inherits the pre-body restriction");
    }

    @Test
    void foreignEntityMoveGuardStillRejectsTheSameRealPeekOperationFromAWorker() throws Throwable {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 2, 0);
        final var shulker = this.entity(Shulker.class, level, new BlockPos(47, 64, 1));
        final var target = new PeekMoveTarget(level);
        target.setPos(48.0, 65.0, 1.5);
        target.onAddedToLevel();
        final var peek = this.realPeek(shulker, level, target);
        final var workerActor = this.entity(Cow.class, level, new BlockPos(47, 64, 1));
        doAnswer(invocation -> {
            peek.invoke(shulker);
            return null;
        }).when(workerActor).tick();
        this.tickList.add(workerActor);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        this.engine.setEntityTickFailureObserverForTesting((entity, failure) -> failures.add(failure));
        this.plan(level).executeAndAwait();
        assertEquals(65.0, target.getY(), 1.0E-6);
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().getMessage().contains("Entity.move"));
        assertEquals(1, this.engine.diagnostics().ownershipViolationCount(), "the fix must not loosen foreign movement guards");
    }

    private java.lang.invoke.MethodHandle realPeek(Shulker shulker, ServerLevel level, PeekMoveTarget target) throws ReflectiveOperationException {
        when(level.getChunkSource().getChunk(anyInt(), anyInt(), any(ChunkStatus.class), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(mock(LevelChunk.class));
        when(shulker.getScale()).thenReturn(1.0F);
        when(shulker.getAttachFace()).thenReturn(net.minecraft.core.Direction.DOWN);
        when(shulker.getX()).thenReturn(47.5);
        when(shulker.getY()).thenReturn(64.0);
        when(shulker.getZ()).thenReturn(1.5);
        final var current = Shulker.class.getDeclaredField("currentPeekAmount");
        current.setAccessible(true);
        current.setFloat(shulker, 1.0F);
        final var previous = Shulker.class.getDeclaredField("currentPeekAmountO");
        previous.setAccessible(true);
        previous.setFloat(shulker, 0.0F);
        doAnswer(invocation -> {
            final net.minecraft.world.phys.AABB area = invocation.getArgument(1);
            final java.util.function.Predicate<Entity> filter = invocation.getArgument(2);
            assertTrue(area.intersects(target.getBoundingBox()), "fixture must intersect the actual opening lid");
            assertTrue(filter.test(target));
            return List.of(target);
        }).when(level).getEntities(any(Entity.class), any(net.minecraft.world.phys.AABB.class), any(java.util.function.Predicate.class));
        return java.lang.invoke.MethodHandles.privateLookupIn(Shulker.class, java.lang.invoke.MethodHandles.lookup())
                .findVirtual(Shulker.class, "onPeekAmountChange", java.lang.invoke.MethodType.methodType(void.class));
    }

    private static class ModdedShulker extends Shulker {
        private ModdedShulker(net.minecraft.world.entity.EntityType<? extends Shulker> type, Level level) {
            super(type, level);
        }
    }

    /** Real Entity.move guard and position update; bypass unrelated collision simulation inside the fixture. */
    private static final class PeekMoveTarget extends Entity {
        private final AtomicInteger moves = new AtomicInteger();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private net.minecraft.world.entity.MoverType lastMover;

        private PeekMoveTarget(Level level) {
            super(net.minecraft.world.entity.EntityType.ITEM, level);
        }

        @Override
        public void move(net.minecraft.world.entity.MoverType mover, net.minecraft.world.phys.Vec3 delta) {
            this.moves.incrementAndGet();
            this.threads.add(Thread.currentThread());
            this.lastMover = mover;
            final boolean previous = this.noPhysics;
            this.noPhysics = true;
            try {
                super.move(mover, delta);
            } finally {
                this.noPhysics = previous;
            }
        }

        @Override
        protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}
    }

    @Test
    void declaredTypeTicksOnTheServerThreadInTheSelfDrivenSlice() {
        this.installEngine();
        this.engine.serverThreadOnlyEntity(Zombie.class, TEST_REASON);
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Zombie zombie = this.entity(Zombie.class, level, new BlockPos(1, 64, 1));
        this.tickList.add(zombie);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(zombie).tick();

        this.plan(level).executeAndAwait();

        verify(zombie, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), "a declared type must not tick on a worker");
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(Map.of(TEST_REASON, 1L), diagnostics.serverThreadOnlyEntityTicksByReason(),
                "the handed-back tick is counted by the reason of its declaration");
        assertEquals(0, diagnostics.entityTicksOnWorkers(), "no entity of this region may tick on the worker");
        assertEquals(1, diagnostics.fallbackEntityTicks(), "the server-thread body of the declared type ran once");
        assertEquals(0, diagnostics.serverThreadOnlyEntityTicks(), "the always-ticking counter is a different decision");
    }

    @Test
    void undeclaredTypeStillTicksOnTheWorker() {
        this.installEngine();
        this.engine.serverThreadOnlyEntity(Zombie.class, TEST_REASON);
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Cow cow = this.entity(Cow.class, level, new BlockPos(1, 64, 1));
        this.tickList.add(cow);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(cow).tick();

        this.plan(level).executeAndAwait();

        verify(cow, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), "an undeclared type keeps the worker");
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(1, diagnostics.entityTicksOnWorkers());
        assertTrue(diagnostics.serverThreadOnlyEntityTicksByReason().isEmpty(), "an undeclared type is never counted");
    }

    @Test
    void declaredTypeTicksOnTheServerThreadInThePerEntitySlice() {
        this.installEngine();
        this.engine.serverThreadOnlyEntity(Zombie.class, TEST_REASON);
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Zombie zombie = this.entity(Zombie.class, level, new BlockPos(1, 64, 1));
        final Cow cow = this.entity(Cow.class, level, new BlockPos(2, 64, 2));
        final RegionEntityTickPlan plan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);

        // The plan does not collect the declared entity, so the vanilla inline body of the caller ticks it on
        // the server thread in its own tick-list position, exactly like the entity it handed back before the
        // policy existed.
        assertFalse(plan.accept(zombie), "a declared type must not be collected for a region worker");
        assertEquals(Map.of(TEST_REASON, 1L), this.engine.diagnostics().serverThreadOnlyEntityTicksByReason());
        assertTrue(plan.accept(cow), "an undeclared type is collected like before");

        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(cow).tick();
        plan.executeAndAwait();

        verify(cow, times(1)).tick();
        verify(zombie, never()).tick();
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), "the collected entity keeps the worker");
        assertEquals(1, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void decisionIsTakenBeforeTheBodyStartsSoAFailingDeclaredTickNeverEnteredAWorkerGroup() {
        this.installEngine();
        this.engine.serverThreadOnlyEntity(Zombie.class, TEST_REASON);
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Zombie zombie = this.entity(Zombie.class, level, new BlockPos(1, 64, 1));
        this.tickList.add(zombie);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        final List<Throwable> workerFailures = new CopyOnWriteArrayList<>();
        this.engine.setEntityTickFailureObserverForTesting((entity, throwable) -> workerFailures.add(throwable));
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            throw new IllegalStateException("declared tick failed");
        }).when(zombie).tick();

        assertThrows(RuntimeException.class, () -> this.plan(level).executeAndAwait(),
                "the failure of a server-thread tick reaches the caller like any other server-thread failure");

        // A failure of the body therefore cannot be a POI race: the body ran on the server thread, and the
        // diagnostics-only observer of the worker loop - the only place a failing worker tick is reported -
        // never saw the entity, so the entity never entered a region worker group.
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), "the declared body ran on the server thread");
        assertTrue(workerFailures.isEmpty(), "the failing body never reached a worker group");
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(Map.of(TEST_REASON, 1L), diagnostics.serverThreadOnlyEntityTicksByReason());
        assertEquals(0, diagnostics.entityTicksOnWorkers());
    }

    @Test
    void declaredPassengerIsDecidedAtTheVehicleThatTicksIt() {
        this.installEngine();
        this.engine.serverThreadOnlyEntity(Zombie.class, TEST_REASON);
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Zombie passenger = this.entity(Zombie.class, level, new BlockPos(1, 64, 1));
        final Cow vehicle = this.entity(Cow.class, level, new BlockPos(1, 64, 1));
        when(vehicle.getPassengers()).thenReturn(List.of(passenger));
        when(passenger.getVehicle()).thenReturn(vehicle);
        when(vehicle.hasPassenger(passenger)).thenReturn(true);

        final RegionEntityTickPlan plan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);

        // The vehicle carries a declared passenger and the passenger is ticked through it
        // (Entity#rideTick runs the whole vanilla tick of a passenger), so the vehicle is the entity the
        // policy hands to the server thread. The passenger keeps the vanilla "ticked as part of the stack"
        // answer and is not decided a second time.
        assertFalse(plan.accept(vehicle), "the vehicle of a declared passenger must not be collected");
        assertFalse(plan.accept(passenger), "a passenger is ticked by its vehicle, not by the plan");
        assertEquals(Map.of(TEST_REASON, 1L), this.engine.diagnostics().serverThreadOnlyEntityTicksByReason(),
                "one decision per riding stack, not one per entity of it");
    }

    @Test
    void registryIsEmptyByDefaultAndTheSwitchOffPathIsUnchanged() {
        // An engine that installs no policy knows no declared type: the undeclared default of the slices is
        // the worker, so a level whose engine does not install the policy ticks exactly like before.
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Zombie zombie = this.entity(Zombie.class, level, new BlockPos(1, 64, 1));
        this.tickList.add(zombie);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(zombie).tick();

        this.plan(level).executeAndAwait();

        verify(zombie, times(1)).tick();
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), "the empty registry keeps every type on the worker");
        assertTrue(this.engine.diagnostics().serverThreadOnlyEntityTicksByReason().isEmpty());

        // The switch-off path is the vanilla inline entity tick: with the entity tick workers disabled the
        // slices plan nothing at all, so no declaration can move a tick anywhere.
        RegionThreadingManager.clear();
        this.installEngine(false);
        assertNull(this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER),
                "a switched-off entity slice plans nothing, with or without a policy");
        assertFalse(this.engine.isEntityTickWorkerExecutionEnabled());
    }

    /**
     * The former POI pins are gone: the types the POI audit used to hand back to the server thread keep their
     * region worker, because the level's POI storage is partitioned per region now. The registry stays for the
     * mod compatibility layer, and a type that is not declared never appears in the per-reason counters.
     */
    @Test
    void formerPoiTypesKeepTheWorkerBecauseThePoiStorageIsRegionized() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final List<Entity> formerPins = List.of(
                this.entity(Villager.class, level, new BlockPos(1, 64, 1)),
                this.entity(IronGolem.class, level, new BlockPos(2, 64, 1)),
                this.entity(Raider.class, level, new BlockPos(3, 64, 1)),
                this.entity(Bee.class, level, new BlockPos(4, 64, 1)),
                this.entity(Zombie.class, level, new BlockPos(5, 64, 1)),
                this.entity(Fox.class, level, new BlockPos(6, 64, 1)));
        this.tickList.addAll(formerPins);
        final Cow cow = this.entity(Cow.class, level, new BlockPos(7, 64, 1));
        this.tickList.add(cow);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        for (final Entity entity : formerPins) {
            doAnswer(invocation -> {
                tickThreads.add(Thread.currentThread().getName());
                return null;
            }).when(entity).tick();
        }
        final List<String> cowThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            cowThreads.add(Thread.currentThread().getName());
            return null;
        }).when(cow).tick();

        this.plan(level).executeAndAwait();

        for (final Entity entity : formerPins) {
            verify(entity, times(1)).tick();
        }
        verify(cow, times(1)).tick();
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertTrue(diagnostics.serverThreadOnlyEntityTicksByReason().isEmpty(),
                "no vanilla declaration is installed any more: " + diagnostics.serverThreadOnlyEntityTicksByReason());
        assertEquals(0L, diagnostics.serverThreadOnlyEntityTicks());
        assertEquals(formerPins.size() + 1, diagnostics.entityTicksOnWorkers(), "every type keeps the worker");
        assertEquals(formerPins.size(), tickThreads.size(), "every former pin ticked once");
        for (final String thread : tickThreads) {
            assertTrue(thread.startsWith("NeoForge Region Worker"), "a former POI pin stayed on the server thread: " + thread);
        }
        assertEquals(1, cowThreads.size());
        assertTrue(cowThreads.get(0).startsWith("NeoForge Region Worker"));
    }

    /**
     * A worker tick that reaches a chunk the lease does not hold is truncated instead of failing the batch.
     * <p>
     * Every crash of this class stopped the 178-mod pack with {@code ReportedException: Ticking entity}, because
     * vanilla answers a throwing entity tick that way and {@code Level#getChunk} on a worker cannot load. The
     * body has already written entity fields when the read fails, so the tick is lost rather than replayed; the
     * class is declared server-thread-only in the same step, which is why the loss happens once per class.
     */
    @Test
    void aChunkRefusalInsideAWorkerTickTruncatesOnceAndPinsTheClassToTheServerThread() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Cow cow = this.entity(Cow.class, level, new BlockPos(1, 64, 1));
        this.tickList.add(cow);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            if (calls.incrementAndGet() == 1) {
                throw RegionWorkerChunkUnavailableException.forChunk(11, -4, ChunkStatus.FULL);
            }
            return null;
        }).when(cow).tick();

        // The first tick is refused by the worker and truncated: the batch completes and nothing is reported.
        this.plan(level).executeAndAwait();

        final RegionThreadingDiagnostics first = this.engine.diagnostics();
        assertEquals(1L, first.truncatedEntityTicks(), "the refused tick is counted, not reported");
        assertEquals(0L, first.entityTicksOnWorkers(), "the truncated tick did not complete on a worker");
        assertEquals(0L, first.serverThreadOnlyEntityTicks(), "the decision is taken for the next tick, not this one");

        // The declaration the truncation installed moves the next tick of the class to the server thread.
        this.plan(level).executeAndAwait();

        assertEquals(2, tickThreads.size());
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.get(0));
        assertFalse(tickThreads.get(1).startsWith("NeoForge Region Worker"),
                "the class is pinned to the server thread after the truncated tick: " + tickThreads.get(1));
        final RegionThreadingDiagnostics second = this.engine.diagnostics();
        assertEquals(Map.of(RegionThreadingEngine.CHUNK_UNAVAILABLE_ENTITY_REASON, 1L),
                second.serverThreadOnlyEntityTicksByReason());
        assertEquals(1L, second.truncatedEntityTicks(), "the loss happens once per class, not once per tick");
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "the entity tick worker slice must be enabled");
        return plan;
    }

    private void installEngine() {
        this.installEngine(true);
    }

    private void installEngine(boolean entityTickWorkers) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, entityTickWorkers, false, false, false,
                RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn("test-level").when(level).toString();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<Entity> action = invocation.getArgument(0);
            for (final Entity entity : this.tickList) {
                action.accept(entity);
            }
            return Boolean.TRUE;
        }).when(level).forEachEntityInTickListForRegionIndex(any());
        doAnswer(invocation -> true).when(level).isInEntityTickingRange(anyLong());
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSpawningAnimals()).thenReturn(true);
        when(server.areNpcsEnabled()).thenReturn(true);
        doReturn(server).when(level).getServer();
        return level;
    }

    private <T extends Entity> T entity(Class<T> type, ServerLevel level, BlockPos pos) {
        final T entity = mock(type);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.chunkPosition()).thenReturn(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        when(entity.getPassengers()).thenReturn(List.of());
        return entity;
    }
}
