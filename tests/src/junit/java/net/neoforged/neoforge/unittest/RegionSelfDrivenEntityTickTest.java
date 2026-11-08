/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.MeleeAttack;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.animal.Pufferfish;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the self-driven entity tick against the real engine: the server thread activates and leases the
 * level's regions, one worker task per region enumerates the region's entity tick index, and the server thread
 * only runs the handoff.
 * <p>
 * The level mock reports the test's tick list through the seed seam, so the production path is the one under
 * test: no entity is collected on the server thread. The level is a {@code CALLS_REAL_METHODS} mock, so the
 * worker runs the real {@code guardEntityTick} and {@code tickNonPassenger} code with mock entities.
 */
@Timeout(60)
class RegionSelfDrivenEntityTickTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int REGION_CHUNK_SIZE = 3;

    private RegionThreadingEngine engine;
    /** The level's entity tick list as the seed seam reports it. */
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    /** Chunks the distance manager does not report as entity-ticking. */
    private final List<ChunkPos> nonTickingChunks = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.tickList.clear();
        this.nonTickingChunks.clear();
        this.engine = null;
    }

    @Test
    void selfDrivenTickRunsExactlyTheEntitiesOfEachRegionOnItsWorker() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        // One region per entity: chunk 0 is region 0 and chunk 3 is region 1 of the fixed 3x3 layout.
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, REGION_CHUNK_SIZE, 0);
        final Entity west = this.entity(level, new BlockPos(1, 64, 1));
        final Entity eastFirst = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
        final Entity eastSecond = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 2, 64, 1));
        this.tickList.addAll(List.of(west, eastFirst, eastSecond));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        for (final Entity entity : List.of(west, eastFirst, eastSecond)) {
            doAnswer(invocation -> {
                tickThreads.add(Thread.currentThread().getName());
                return null;
            }).when(entity).tick();
        }

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.ticksEntitiesOnRegionWorkers(), "the entity tick must be driven by the regions");
        plan.executeAndAwait();

        verify(west, times(1)).tick();
        verify(eastFirst, times(1)).tick();
        verify(eastSecond, times(1)).tick();
        assertEquals(3, tickThreads.size());
        for (final String thread : tickThreads) {
            assertTrue(thread.startsWith("NeoForge Region Worker"), "an entity of a region must tick on its region worker: " + thread);
        }
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(3, diagnostics.entityTicksOnWorkers());
        assertEquals(2, diagnostics.entityTickRegionsOnWorkers(), "one worker task per active region");
        assertEquals(0, diagnostics.entityTicksOnWorkers() - 3);
        assertEquals(0, diagnostics.fallbackEntityTicks(), "a covered entity must not fall back");
        assertEquals(0, diagnostics.fallbackEntityTickRegions());
        assertEquals(0, diagnostics.entityTicksOutsideRegion());
        assertEquals(0, diagnostics.serverThreadOnlyEntityTicks());
    }

    @Test
    void loadedChunkWrapperDoesNotAbortEntityTicksOnWorkers() {
        this.installEngine();
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = this.mockLevel();
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        final ImposterProtoChunk imposter = mock(ImposterProtoChunk.class);
        when(imposter.getWrapped()).thenReturn(mock(LevelChunk.class));
        final AtomicInteger completed = new AtomicInteger();
        doAnswer(invocation -> {
            RegionThreadingHooks.recordForeignChunkRead(level, 0, 0, imposter);
            completed.incrementAndGet();
            return null;
        }).when(entity).tick();

        this.plan(level).executeAndAwait();
        this.plan(level).executeAndAwait();

        assertEquals(2, completed.get(), "the biome chunk wrapper must not abort every entity tick");
        assertEquals(2L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().borderUpdates().readOfVersionlessChunk());
    }

    @Test
    void playerRidingStackTicksOnTheServerThread() {
        this.installEngine();
        this.engine.setBorderUpdatesEnabledForTesting(true);
        final ServerLevel level = this.mockLevel();
        final BlockPos position = new BlockPos(1, 64, 1);
        final Entity vehicle = this.entity(level, position);
        final Player rider = mock(Player.class);
        when(rider.level()).thenReturn(level);
        when(rider.blockPosition()).thenReturn(position);
        when(rider.chunkPosition()).thenReturn(new ChunkPos(position));
        when(rider.getUUID()).thenReturn(UUID.randomUUID());
        when(rider.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(rider.isAlwaysTicking()).thenReturn(true);
        when(rider.getVehicle()).thenReturn(vehicle);
        when(vehicle.getPassengers()).thenReturn(List.of(rider));
        when(vehicle.hasPassenger(rider)).thenReturn(true);
        this.tickList.addAll(List.of(vehicle, rider));
        final ImposterProtoChunk imposter = mock(ImposterProtoChunk.class);
        when(imposter.getWrapped()).thenReturn(mock(LevelChunk.class));
        final AtomicInteger completedVehicleTicks = new AtomicInteger();
        final List<Thread> riderThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            // The biome lookup in LivingEntity.baseTick asks for BIOMES, served as a loaded chunk wrapper.
            RegionThreadingHooks.recordForeignChunkRead(level, 0, 0, imposter);
            completedVehicleTicks.incrementAndGet();
            return null;
        }).when(vehicle).tick();
        doAnswer(invocation -> {
            riderThreads.add(Thread.currentThread());
            return null;
        }).when(rider).rideTick();

        assertFalse(this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER).accept(vehicle),
                "the per-entity collection must also keep a player's riding stack off workers");
        this.plan(level).executeAndAwait();
        this.plan(level).executeAndAwait();

        assertEquals(2, completedVehicleTicks.get(), "the vehicle must finish each tick before ticking passengers");
        verify(rider, times(2)).rideTick();
        verify(rider, never()).tick();
        assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), riderThreads,
                "the player ride tick, including dismount input, must reach the server-thread handoff");
        assertEquals(0L, this.engine.diagnostics().borderUpdates().readOfVersionlessChunk());
        assertEquals(0L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(2L, this.engine.diagnostics().fallbackEntityTicks());
    }

    @Test
    void crossRegionMoveIsPublishedAtTheBarrierAndTickedByItsNewRegionNextTick() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, REGION_CHUNK_SIZE, 0);
        final AtomicReference<BlockPos> position = new AtomicReference<>(new BlockPos(1, 64, 1));
        final Entity entity = this.entity(level, position);
        this.tickList.add(entity);
        doAnswer(invocation -> {
            position.set(new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
            // The deferred ownership update the entity tick posts: the worker never writes the mapping.
            RegionThreadingHooks.moveRegionEntity(entity);
            return null;
        }).when(entity).tick();

        // Opening the plan seeds the region index, which publishes the mapping at the start position.
        final RegionEntityTickPlan plan = this.plan(level);
        assertEquals(0, regionX(entity), "the entity starts in region 0");
        plan.executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, regionX(entity), "the barrier must publish the region the tick moved the entity into");

        // The next tick dispatches the new region with the migrated index entry and ticks the entity once.
        this.plan(level).executeAndAwait();

        verify(entity, times(2)).tick();
        assertEquals(2, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().entityTicksOutsideRegion(), "an entity that moved must not leave the tick");
    }

    @Test
    void readOnlyGroupingDoesNotWriteTheRegionizerAndGroupsLikeTheWritePath() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity tracked = this.entity(level, new BlockPos(1, 64, 1));
        this.engine.addRegionEntity(tracked);
        final long writeLocks = this.engine.diagnostics().regionizerWriteLockAcquisitions();

        final RegionEntityTickPlan plan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertTrue(plan.accept(tracked));
        assertEquals(writeLocks, this.engine.diagnostics().regionizerWriteLockAcquisitions(),
                "grouping an already tracked entity must not take the regionizer write lock");

        // The first observation of an untracked entity is the only one that publishes the mapping.
        final Entity untracked = this.entity(level, new BlockPos(2, 64, 1));
        assertTrue(plan.accept(untracked));
        assertEquals(writeLocks + 1, this.engine.diagnostics().regionizerWriteLockAcquisitions(),
                "exactly one write lock acquisition publishes the mapping of an untracked entity");

        // The read-only grouping and the write grouping name the same region for the same position.
        final Entity readOnly = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
        final Entity writeGrouped = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
        this.engine.addRegionEntity(readOnly);
        final RegionEntityTickPlan readOnlyPlan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        final RegionEntityTickPlan writePlan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertTrue(readOnlyPlan.accept(readOnly));
        assertTrue(writePlan.accept(writeGrouped));
        assertEquals(regionX(readOnly), regionX(writeGrouped), "the read-only grouping must group like the write path");
    }

    @Test
    void alwaysTickingEntityIsTickedByTheServerThread() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Entity player = this.entity(level, new BlockPos(1, 64, 1));
        when(player.isAlwaysTicking()).thenReturn(true);
        this.tickList.add(player);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(player).tick();

        this.plan(level).executeAndAwait();

        verify(player, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), "an always-ticking entity must not tick on a worker");
        assertEquals(1, this.engine.diagnostics().serverThreadOnlyEntityTicks());
        assertEquals(0, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1, this.engine.diagnostics().fallbackEntityTicks());
    }

    @Test
    void entityThatLeftTheRegionRectangleIsTickedByTheServerThread() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final AtomicReference<BlockPos> position = new AtomicReference<>(new BlockPos(1, 64, 1));
        final Entity entity = this.entity(level, position);
        this.tickList.add(entity);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        // Opening the plan publishes the mapping at the start position; the position then outruns it without
        // a tick, so the region's activation snapshot cannot decide the entity's chunk.
        final RegionEntityTickPlan plan = this.plan(level);
        position.set(new BlockPos(30 * 16 + 1, 64, 1));
        plan.executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, this.engine.diagnostics().entityTicksOutsideRegion());
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), "an undecidable entity must tick on the server thread");
        assertEquals(0, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void regionPublishedByItsEntityIsActivatedWithoutAnyChunkLoadEvent() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        // No chunk load is reported to this engine: the region gets its chunk rectangle from the mapping that
        // publishes the entity, which is what an engine installed after the level's chunks were loaded sees.
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);

        this.plan(level).executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, this.engine.diagnostics().entityTickRegionsOnWorkers());
        assertEquals(1, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().fallbackEntityTickRegions());
    }

    @Test
    void entityOutsideTheEntityTickingRangeIsDespawnedButNotTicked() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        // Every chunk of the region's rectangle is outside the entity ticking range, so the region is
        // dispatched for the despawn check only.
        for (int chunkX = 0; chunkX < REGION_CHUNK_SIZE; chunkX++) {
            for (int chunkZ = 0; chunkZ < REGION_CHUNK_SIZE; chunkZ++) {
                this.nonTickingChunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }

        this.plan(level).executeAndAwait();

        verify(entity, times(1)).checkDespawn();
        verify(entity, never()).tick();
        assertEquals(1, this.engine.diagnostics().entityTickRegionsWithoutEligibleChunk());
        assertEquals(0, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().fallbackEntityTicks());
    }

    @Test
    void levelWithoutATickListFallsBackToThePerEntityPlan() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        // A level that cannot report its tick list keeps the per-entity collection of the slice.
        doReturn(false).when(level).forEachEntityInTickListForRegionIndex(any());

        final RegionEntityTickPlan plan = this.plan(level);

        assertFalse(plan.ticksEntitiesOnRegionWorkers());
        assertEquals(1, this.engine.diagnostics().entityTickIndexFallbacks());
    }

    @Test
    void brainMeleeAttackDefersTheWholeVirtualCallBeforeForeignPufferfishPositionCallbacks() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 3, 0);
        final Mob attacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(47, 64, 1)));
        final Pufferfish victim = this.entity(Pufferfish.class, level, new AtomicReference<>(new BlockPos(49, 64, 1)));
        final MinecraftServer server = level.getServer();
        when(server.isSpawningAnimals()).thenReturn(true);
        final Field position = Entity.class.getDeclaredField("position");
        position.setAccessible(true);
        position.set(victim, new net.minecraft.world.phys.Vec3(49, 64, 1));
        final Field levelField = Entity.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(victim, level);
        when(victim.isAddedToLevel()).thenReturn(true);
        final LevelChunk loaded = mock(LevelChunk.class);
        doReturn(loaded).when(level).getChunk(anyInt(), anyInt());
        doCallRealMethod().when(victim).setPosRaw(anyDouble(), anyDouble(), anyDouble());
        final Brain<Mob> brain = this.attackBrain(attacker, victim);
        final List<String> effects = new CopyOnWriteArrayList<>();
        final List<Thread> attackThreads = new CopyOnWriteArrayList<>();
        final Thread serverThread = Thread.currentThread();
        final java.util.concurrent.atomic.AtomicBoolean victimTickDone = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            victimTickDone.set(true);
            return null;
        }).when(victim).tick();
        doAnswer(call -> {
            attackThreads.add(Thread.currentThread());
            assertTrue(victimTickDone.get(), "all target worker ticks must finish before the attack");
            effects.add("damage");
            // The real patched operation reached by Pufferfish's death-pose/dimension refresh. It refuses a
            // foreign worker before changing position; the server-thread continuation may perform it.
            victim.setPosRaw(49, 64, 1);
            effects.add("death-pose");
            effects.add("mod-post-attack-and-drops");
            return true;
        }).when(attacker).doHurtTarget(victim);
        doAnswer(call -> {
            assertTrue(MeleeAttack.create(20).trigger(level, attacker, 0L));
            assertTrue(brain.hasMemoryValue(MemoryModuleType.ATTACK_COOLING_DOWN));
            assertEquals(List.of(), effects, "no target damage or mod override effect starts on the source worker");
            return null;
        }).when(attacker).tick();
        this.tickList.addAll(List.of(attacker, victim));
        this.plan(level).executeAndAwait();
        assertEquals(List.of("damage", "death-pose", "mod-post-attack-and-drops"), effects);
        assertEquals(List.of(serverThread), attackThreads);
        verify(attacker, times(1)).tick();
        verify(attacker, times(1)).doHurtTarget(victim);
        assertEquals(1L, this.engine.meleeAttackCounters().queued());
        assertEquals(1L, this.engine.meleeAttackCounters().applied());
        assertEquals(0L, this.engine.meleeAttackCounters().failed());
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void brainMeleeAttackBetweenOwnedEntitiesKeepsTheInlineWorkerCall() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Mob attacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final LivingEntity victim = this.entity(LivingEntity.class, level, new AtomicReference<>(new BlockPos(2, 64, 1)));
        this.attackBrain(attacker, victim);
        final List<Thread> calls = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            calls.add(Thread.currentThread());
            return true;
        }).when(attacker).doHurtTarget(victim);
        doAnswer(call -> {
            assertTrue(MeleeAttack.create(20).trigger(level, attacker, 0L));
            assertEquals(1, calls.size(), "same-lease attacks retain their synchronous outcome");
            return null;
        }).when(attacker).tick();
        this.tickList.addAll(List.of(attacker, victim));
        this.plan(level).executeAndAwait();
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).getName().startsWith("NeoForge Region Worker"));
        assertEquals(0L, this.engine.meleeAttackCounters().queued());
    }

    @Test
    void sameRegionTargetOutsideTheTickGroupIsAlsoHandedOff() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Mob attacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final LivingEntity victim = this.entity(LivingEntity.class, level, new AtomicReference<>(new BlockPos(2, 64, 1)));
        final List<Thread> calls = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            calls.add(Thread.currentThread());
            return true;
        }).when(attacker).doHurtTarget(victim);
        doAnswer(call -> {
            assertTrue(RegionThreadingHooks.deferMeleeAttack(attacker, victim));
            assertEquals(List.of(), calls);
            return null;
        }).when(attacker).tick();
        this.tickList.add(attacker);
        this.plan(level).executeAndAwait();
        assertEquals(List.of(Thread.currentThread()), calls);
        assertEquals(1L, this.engine.meleeAttackCounters().applied());
    }

    @Test
    void anAttackWhoseTargetWasRemovedBeforeTheBarrierIsCountedAndNotReplayed() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Mob attacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final LivingEntity victim = this.entity(LivingEntity.class, level, new AtomicReference<>(new BlockPos(49, 64, 1)));
        final java.util.concurrent.atomic.AtomicBoolean removed = new java.util.concurrent.atomic.AtomicBoolean();
        when(victim.isRemoved()).thenAnswer(call -> removed.get());
        doAnswer(call -> {
            assertTrue(RegionThreadingHooks.deferMeleeAttack(attacker, victim));
            removed.set(true);
            return null;
        }).when(attacker).tick();
        this.tickList.add(attacker);
        this.plan(level).executeAndAwait();
        verify(attacker, never()).doHurtTarget(victim);
        verify(attacker, times(1)).tick();
        assertEquals(1L, this.engine.meleeAttackCounters().queued());
        assertEquals(1L, this.engine.meleeAttackCounters().skipped());
        assertEquals(0L, this.engine.meleeAttackCounters().applied());
    }

    @Test
    void meleeAttackHookLeavesServerThreadCallersOnTheOriginalPath() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Mob attacker = mock(Mob.class);
        final Entity target = this.entity(level, new BlockPos(1, 64, 1));
        assertFalse(RegionThreadingHooks.deferMeleeAttack(attacker, target));
        verify(attacker, never()).doHurtTarget(target);
        assertEquals(0L, this.engine.meleeAttackCounters().queued());
    }

    @Test
    void bothVanillaGoalAttackPathsHandOffTheVirtualCall() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final net.minecraft.world.entity.PathfinderMob goalAttacker = this.entity(net.minecraft.world.entity.PathfinderMob.class,
                level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final Mob catAttacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(2, 64, 1)));
        final LivingEntity victim = this.entity(LivingEntity.class, level, new AtomicReference<>(new BlockPos(49, 64, 1)));
        final List<Thread> attacks = new CopyOnWriteArrayList<>();
        for (final Mob attacker : List.of(goalAttacker, catAttacker)) {
            doAnswer(call -> {
                attacks.add(Thread.currentThread());
                return true;
            }).when(attacker).doHurtTarget(victim);
        }
        final var goal = new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(goalAttacker, 1.0, false) {
            @Override
            protected boolean canPerformAttack(LivingEntity target) {
                return true;
            }

            void attack() {
                this.checkAndPerformAttack(victim);
            }
        };
        when(catAttacker.getTarget()).thenReturn(victim);
        when(catAttacker.getLookControl()).thenReturn(mock(net.minecraft.world.entity.ai.control.LookControl.class));
        when(catAttacker.getNavigation()).thenReturn(mock(net.minecraft.world.entity.ai.navigation.PathNavigation.class));
        final var catGoal = new net.minecraft.world.entity.ai.goal.OcelotAttackGoal(catAttacker);
        assertTrue(catGoal.canUse());
        doAnswer(call -> {
            goal.attack();
            return null;
        }).when(goalAttacker).tick();
        doAnswer(call -> {
            catGoal.tick();
            return null;
        }).when(catAttacker).tick();
        this.tickList.addAll(List.of(goalAttacker, catAttacker));
        this.plan(level).executeAndAwait();
        assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), attacks);
        assertEquals(2L, this.engine.meleeAttackCounters().queued());
        assertEquals(2L, this.engine.meleeAttackCounters().applied());
        verify(goalAttacker, times(1)).doHurtTarget(victim);
        verify(catAttacker, times(1)).doHurtTarget(victim);
    }

    @Test
    void queuedAttackToAPlayerRunsOnTheServerThread() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Mob attacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final Player player = this.entity(Player.class, level, new AtomicReference<>(new BlockPos(2, 64, 1)));
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            threads.add(Thread.currentThread());
            return true;
        }).when(attacker).doHurtTarget(player);
        doAnswer(call -> {
            assertTrue(RegionThreadingHooks.deferMeleeAttack(attacker, player));
            return null;
        }).when(attacker).tick();
        this.tickList.addAll(List.of(attacker, player));
        this.plan(level).executeAndAwait();
        assertEquals(List.of(Thread.currentThread()), threads);
        assertEquals(1L, this.engine.meleeAttackCounters().applied());
    }

    @Test
    void failedQueuedAttackIsReportedAfterTheOtherAcceptedAttackRuns() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Mob attacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final LivingEntity victim = this.entity(LivingEntity.class, level, new AtomicReference<>(new BlockPos(49, 64, 1)));
        final AtomicInteger calls = new AtomicInteger();
        final IllegalStateException failure = new IllegalStateException("mod attack failed");
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                throw failure;
            }
            return false;
        }).when(attacker).doHurtTarget(victim);
        doAnswer(call -> {
            assertTrue(RegionThreadingHooks.deferMeleeAttack(attacker, victim));
            assertTrue(RegionThreadingHooks.deferMeleeAttack(attacker, victim));
            return null;
        }).when(attacker).tick();
        this.tickList.add(attacker);
        final net.minecraft.ReportedException reported = assertThrows(net.minecraft.ReportedException.class,
                () -> this.plan(level).executeAndAwait());
        assertEquals(failure, reported.getReport().getException());
        assertEquals(2, calls.get());
        assertEquals(2L, this.engine.meleeAttackCounters().queued());
        assertEquals(1L, this.engine.meleeAttackCounters().failed());
        assertEquals(1L, this.engine.meleeAttackCounters().applied(), "a false damage result is an executed attack, not a retry");
        verify(attacker, times(1)).tick();
    }

    @Test
    void anUnrelatedDeferredTaskFailureDoesNotSilentlyLoseAnAcceptedAttack() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Mob attacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final LivingEntity victim = this.entity(LivingEntity.class, level, new AtomicReference<>(new BlockPos(49, 64, 1)));
        final IllegalStateException failure = new IllegalStateException("unrelated task failed");
        doAnswer(call -> {
            assertTrue(this.engine.queueServerTaskInCurrentBatch(() -> {
                throw failure;
            }));
            assertTrue(RegionThreadingHooks.deferMeleeAttack(attacker, victim));
            return null;
        }).when(attacker).tick();
        this.tickList.add(attacker);
        assertEquals(failure, assertThrows(IllegalStateException.class, () -> this.plan(level).executeAndAwait()));
        verify(attacker, times(1)).doHurtTarget(victim);
        assertEquals(1L, this.engine.meleeAttackCounters().applied());
    }

    @Test
    void targetChangingLevelsBeforeTheBarrierCancelsTheQueuedAttack() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Mob attacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final LivingEntity victim = this.entity(LivingEntity.class, level, new AtomicReference<>(new BlockPos(49, 64, 1)));
        final AtomicReference<Level> targetLevel = new AtomicReference<>(level);
        when(victim.level()).thenAnswer(call -> targetLevel.get());
        final ServerLevel elsewhere = mock(ServerLevel.class);
        doAnswer(call -> {
            assertTrue(RegionThreadingHooks.deferMeleeAttack(attacker, victim));
            targetLevel.set(elsewhere);
            return null;
        }).when(attacker).tick();
        this.tickList.add(attacker);
        this.plan(level).executeAndAwait();
        verify(attacker, never()).doHurtTarget(victim);
        assertEquals(1L, this.engine.meleeAttackCounters().skipped());
    }

    private Brain<Mob> attackBrain(Mob attacker, LivingEntity victim) {
        final Brain<Mob> brain = new Brain<>(List.of(MemoryModuleType.LOOK_TARGET, MemoryModuleType.ATTACK_TARGET,
                MemoryModuleType.ATTACK_COOLING_DOWN, MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES),
                List.of(), ImmutableList.of(), () -> null);
        brain.setMemory(MemoryModuleType.ATTACK_TARGET, victim);
        final NearestVisibleLivingEntities visible = mock(NearestVisibleLivingEntities.class);
        when(visible.contains(victim)).thenReturn(true);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, visible);
        doReturn(brain).when(attacker).getBrain();
        when(attacker.isWithinMeleeAttackRange(victim)).thenReturn(true);
        return brain;
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "the entity tick worker slice must be enabled");
        return plan;
    }

    private void installEngine() {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, true, false, false, false,
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
        // The seed seam of the self-driven entity tick: the level reports the test's tick list.
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<Entity> action = invocation.getArgument(0);
            for (final Entity entity : this.tickList) {
                action.accept(entity);
            }
            return Boolean.TRUE;
        }).when(level).forEachEntityInTickListForRegionIndex(any());
        doAnswer(invocation -> {
            final long chunkKey = invocation.getArgument(0);
            return !this.nonTickingChunks.contains(new ChunkPos(chunkKey));
        }).when(level).isInEntityTickingRange(anyLong());
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSpawningAnimals()).thenReturn(false);
        when(server.areNpcsEnabled()).thenReturn(true);
        doReturn(server).when(level).getServer();
        return level;
    }

    private Entity entity(ServerLevel level, BlockPos pos) {
        return this.entity(level, new AtomicReference<>(pos));
    }

    private Entity entity(ServerLevel level, AtomicReference<BlockPos> position) {
        return this.entity(Entity.class, level, position);
    }

    private <T extends Entity> T entity(Class<T> type, ServerLevel level, AtomicReference<BlockPos> position) {
        final T entity = mock(type);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenAnswer(invocation -> position.get());
        when(entity.chunkPosition()).thenAnswer(invocation -> {
            final BlockPos pos = position.get();
            return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        });
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        return entity;
    }

    private int regionX(Entity entity) throws ReflectiveOperationException {
        final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
        regionizerField.setAccessible(true);
        final Object regionizer = regionizerField.get(this.engine);
        final Field entitiesField = regionizer.getClass().getDeclaredField("entities");
        entitiesField.setAccessible(true);
        final Object region = ((Map<?, ?>) entitiesField.get(regionizer)).get(entity);
        assertNotNull(region, "the entity is not tracked by the regionizer");
        final Method coordinateMethod = region.getClass().getDeclaredMethod("coordinate");
        coordinateMethod.setAccessible(true);
        return ((RegionCoordinate) coordinateMethod.invoke(region)).regionX();
    }
}
