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
import net.neoforged.neoforge.server.threading.EntityInteractionTask;
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
    void attackingAShulkerInTheSameRegionStillDefersItsCompleteDamageCallback() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Mob attacker = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final var victim = this.entity(net.minecraft.world.entity.monster.Shulker.class, level,
                new AtomicReference<>(new BlockPos(2, 64, 1)));
        final Thread main = Thread.currentThread();
        final AtomicInteger callbacks = new AtomicInteger();
        doAnswer(call -> {
            assertEquals(main, Thread.currentThread());
            RegionThreadingHooks.assertServerEntityStorageAccess(victim, "shulker damage teleport");
            callbacks.incrementAndGet();
            return true;
        }).when(attacker).doHurtTarget(victim);
        doAnswer(call -> {
            assertTrue(RegionThreadingHooks.deferMeleeAttack(attacker, victim));
            assertEquals(0, callbacks.get());
            return null;
        }).when(attacker).tick();
        this.tickList.addAll(List.of(attacker, victim));
        this.plan(level).executeAndAwait();
        assertEquals(1, callbacks.get());
        verify(attacker, times(1)).doHurtTarget(victim);
        assertEquals(1L, this.engine.meleeAttackCounters().applied());
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
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

    @Test
    void enderPearlTickAndItsPlayerTeleportStayOnTheServerThreadWhileOtherEntitiesUseWorkers() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final var pearl = this.entity(net.minecraft.world.entity.projectile.ThrownEnderpearl.class,
                level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final var owner = mock(net.minecraft.server.level.ServerPlayer.class);
        final var connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        connection.player = owner;
        owner.connection = connection;
        doCallRealMethod().when(connection).teleport(anyDouble(), anyDouble(), anyDouble(),
                org.mockito.ArgumentMatchers.anyFloat(), org.mockito.ArgumentMatchers.anyFloat(), any());
        final Entity ordinary = this.entity(level, new BlockPos(2, 64, 1));
        final Thread serverThread = Thread.currentThread();
        final List<String> stages = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            stages.add("ordinary-tick");
            threads.add(Thread.currentThread());
            return null;
        }).when(ordinary).tick();
        doAnswer(call -> {
            assertEquals(serverThread, Thread.currentThread());
            stages.add("pearl-tick");
            // Exercise the exact real guarded connection method from the live crash, not a mock guard.
            connection.teleport(369.33, 33.03, -19.58, 0F, 0F, java.util.Set.of());
            stages.add("teleport-complete");
            return null;
        }).when(pearl).tick();
        this.tickList.addAll(List.of(pearl, ordinary));
        this.plan(level).executeAndAwait();
        assertEquals(List.of("ordinary-tick", "pearl-tick", "teleport-complete"), stages);
        assertEquals(1, threads.size());
        assertTrue(threads.get(0).getName().startsWith("NeoForge Region Worker"));
        verify(pearl, times(1)).tick();
        verify(owner, times(1)).absMoveTo(369.33, 33.03, -19.58, 0F, 0F);
        verify(connection, times(1)).send(any(net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket.class));
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackEntityTicks());
        assertEquals(1L, this.engine.diagnostics().serverThreadOnlyEntityTicksByReason().get("ender_pearl_owner_interaction"));
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void enderPearlSubclassAndNonPlayerOwnerAreKeptOffBothWorkerAdmissionPaths() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final PearlSubclass pearl = this.entity(PearlSubclass.class, level,
                new AtomicReference<>(new BlockPos(1, 64, 1)));
        final Entity nonPlayerOwner = this.entity(level, new BlockPos(49, 64, 1));
        when(pearl.getOwner()).thenReturn(nonPlayerOwner);
        final RegionEntityTickPlan perEntity = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertFalse(perEntity.accept(pearl), "subclasses and non-player owners still reach server-owned teleport effects");
        perEntity.executeAndAwait();
        verify(pearl, never()).tick();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            threads.add(Thread.currentThread());
            return null;
        }).when(pearl).tick();
        this.tickList.add(pearl);
        this.plan(level).executeAndAwait();
        assertEquals(List.of(Thread.currentThread()), threads);
        assertEquals(0L, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void pearlFlightAndGatewayCallbacksAlsoStayOnTheServerThreadWithoutAnImpact() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final var pearl = this.entity(net.minecraft.world.entity.projectile.ThrownEnderpearl.class,
                level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final var owner = mock(net.minecraft.server.level.ServerPlayer.class);
        when(pearl.getOwner()).thenReturn(owner);
        when(owner.level()).thenReturn(level);
        final Thread serverThread = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == serverThread);
        final var gateway = mock(net.minecraft.world.level.block.state.BlockState.class);
        when(gateway.is(net.minecraft.world.level.block.Blocks.END_GATEWAY)).thenReturn(true);
        final Method inside = net.minecraft.world.entity.projectile.ThrownEnderpearl.class
                .getDeclaredMethod("onInsideBlock", net.minecraft.world.level.block.state.BlockState.class);
        inside.setAccessible(true);
        doCallRealMethod().when(pearl);
        inside.invoke(pearl, gateway);
        final AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            assertEquals(serverThread, Thread.currentThread());
            RegionThreadingHooks.assertServerEntityStorageAccess("pearl gateway player callback");
            calls.incrementAndGet();
            return null;
        }).when(owner).onInsideBlock(gateway);
        doAnswer(call -> {
            assertEquals(serverThread, Thread.currentThread(), "the complete flying tick stays on the server thread");
            inside.invoke(pearl, gateway);
            return null;
        }).when(pearl).tick();
        this.tickList.add(pearl);
        this.plan(level).executeAndAwait();
        assertEquals(1, calls.get());
        assertEquals(1L, this.engine.entityInteractionCounters().get("admitted"));
        assertEquals(1L, this.engine.entityInteractionCounters().get("COMPLETED"));
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void realPearlImpactKeepsCancellationAndTeleportDamageDiscardOrder() throws ReflectiveOperationException {
        this.checkRealPearlImpact(false);
        RegionThreadingManager.clear();
        this.tickList.clear();
        this.checkRealPearlImpact(true);
    }

    private void checkRealPearlImpact(boolean cancel) throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        // This unit fixture has no loaded server config. Let the underlying assertion/callback failure
        // propagate directly rather than the vanilla crash reporter trying to read that unloaded config.
        doAnswer(call -> {
            final Consumer<Entity> tick = call.getArgument(0);
            tick.accept(call.getArgument(1));
            return null;
        }).when(level).guardEntityTick(any(), any(Entity.class));
        final var impactedState = mock(net.minecraft.world.level.block.state.BlockState.class);
        doReturn(impactedState).when(level).getBlockState(any(BlockPos.class));
        org.mockito.Mockito.doNothing().when(level).gameEvent(any(net.minecraft.core.Holder.class),
                any(net.minecraft.world.phys.Vec3.class), any(net.minecraft.world.level.gameevent.GameEvent.Context.class));
        org.mockito.Mockito.doNothing().when(level).playSound(org.mockito.ArgumentMatchers.<Player>isNull(),
                anyDouble(), anyDouble(), anyDouble(), any(net.minecraft.sounds.SoundEvent.class), any(net.minecraft.sounds.SoundSource.class));
        final var pearl = this.entity(net.minecraft.world.entity.projectile.ThrownEnderpearl.class,
                level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final var owner = mock(net.minecraft.server.level.ServerPlayer.class);
        when(owner.level()).thenReturn(level);
        when(owner.isAlive()).thenReturn(true);
        final var connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        owner.connection = connection;
        when(connection.isAcceptingMessages()).thenReturn(true);
        when(pearl.getOwner()).thenReturn(owner);
        when(pearl.getX()).thenReturn(1D);
        when(pearl.getY()).thenReturn(64D);
        when(pearl.getZ()).thenReturn(1D);
        when(pearl.position()).thenReturn(new net.minecraft.world.phys.Vec3(1, 64, 1));
        when(owner.getDeltaMovement()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
        final var random = mock(net.minecraft.util.RandomSource.class);
        when(random.nextFloat()).thenReturn(1F); // Do not randomly spawn endermites in this fixture.
        final Field randomField = Entity.class.getDeclaredField("random");
        randomField.setAccessible(true);
        randomField.set(pearl, random);
        final var sources = mock(net.minecraft.world.damagesource.DamageSources.class);
        final var fall = mock(net.minecraft.world.damagesource.DamageSource.class);
        when(sources.fall()).thenReturn(fall);
        when(pearl.damageSources()).thenReturn(sources);
        final var impact = new net.minecraft.world.phys.BlockHitResult(pearl.position(), net.minecraft.core.Direction.UP,
                new BlockPos(1, 64, 1), false);
        final Method onHit = net.minecraft.world.entity.projectile.ThrownEnderpearl.class
                .getDeclaredMethod("onHit", net.minecraft.world.phys.HitResult.class);
        onHit.setAccessible(true);
        doCallRealMethod().when(pearl);
        onHit.invoke(pearl, impact);
        final List<String> effects = new CopyOnWriteArrayList<>();
        final Thread serverThread = Thread.currentThread();
        final Consumer<net.neoforged.neoforge.event.entity.EntityTeleportEvent.EnderPearl> listener = event -> {
            if (event.getPearlEntity() == pearl) {
                assertEquals(serverThread, Thread.currentThread());
                effects.add("event");
                event.setCanceled(cancel);
            }
        };
        doAnswer(call -> {
            assertEquals(serverThread, Thread.currentThread());
            RegionThreadingHooks.assertServerEntityStorageAccess("pearl owner teleport");
            final net.minecraft.world.level.portal.DimensionTransition transition = call.getArgument(0);
            assertEquals(pearl.position(), transition.pos());
            effects.add("teleport");
            return owner;
        }).when(owner).changeDimension(any(net.minecraft.world.level.portal.DimensionTransition.class));
        doAnswer(call -> {
            effects.add("fall-reset");
            return null;
        }).when(owner).resetFallDistance();
        doAnswer(call -> {
            effects.add("impulse-reset");
            return null;
        }).when(owner).resetCurrentImpulseContext();
        doAnswer(call -> {
            effects.add("damage");
            return true;
        }).when(owner).hurt(fall, 5F);
        doAnswer(call -> {
            effects.add("discard");
            return null;
        }).when(pearl).discard();
        doAnswer(call -> {
            assertEquals(serverThread, Thread.currentThread());
            onHit.invoke(pearl, impact);
            return null;
        }).when(pearl).tick();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.entity.EntityTeleportEvent.EnderPearl.class, listener);
        try {
            this.tickList.add(pearl);
            this.plan(level).executeAndAwait();
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
        }
        assertEquals(cancel ? List.of("event", "discard")
                : List.of("event", "teleport", "fall-reset", "impulse-reset", "damage", "discard"), effects);
        verify(pearl, times(1)).tick();
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    private static class PearlSubclass extends net.minecraft.world.entity.projectile.ThrownEnderpearl {
        PearlSubclass(Level level) {
            super(net.minecraft.world.entity.EntityType.ENDER_PEARL, level);
        }
    }

    @Test
    void interactionApiRevalidatesEntitiesAtTheBarrierAndDeliversExplicitCompletion() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity target = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicReference<EntityInteractionTask> handle = new AtomicReference<>();
        final List<Thread> ran = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            final EntityInteractionTask task = this.engine.entityInteractions().submit(source, target, (actor, victim) -> {
                assertEquals(source, actor);
                assertEquals(target, victim);
                assertEquals(EntityInteractionTask.State.RUNNING, handle.get().state());
                assertFalse(handle.get().cancel(), "a callback which has started cannot be cancelled");
                ran.add(Thread.currentThread());
            });
            assertEquals(EntityInteractionTask.State.QUEUED, task.state());
            assertFalse(task.isDone());
            assertEquals(List.of(), ran);
            handle.set(task);
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(List.of(Thread.currentThread()), ran);
        assertEquals(EntityInteractionTask.State.COMPLETED, handle.get().state());
        assertEquals(EntityInteractionTask.Reason.NONE, handle.get().reason());
        assertTrue(handle.get().isDone());
        assertFalse(handle.get().cancel());
        assertTrue(handle.get().failure().isEmpty());
    }

    @Test
    void interactionApiCancellationPreventsExecutionAndReleasesCapturedLiveState() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity target = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicReference<EntityInteractionTask> handle = new AtomicReference<>();
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            final EntityInteractionTask task = this.engine.entityInteractions().submit(source, target, (actor, victim) -> ran.incrementAndGet());
            handle.set(task);
            assertTrue(task.cancel());
            assertFalse(task.cancel());
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(0, ran.get());
        final EntityInteractionTask task = handle.get();
        assertEquals(EntityInteractionTask.State.CANCELLED, task.state());
        assertEquals(EntityInteractionTask.Reason.CANCELLED, task.reason());
        for (final String name : List.of("source", "target", "level", "action")) {
            final Field field = task.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertEquals(null, field.get(task), "terminal handles must release live entity/world/callback references");
        }
    }

    @Test
    void interactionApiCountsRetirementAndDimensionChangeWithoutCallingUserAction() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity removedTarget = this.entity(level, new BlockPos(49, 64, 1));
        final Entity movedTarget = this.entity(level, new BlockPos(50, 64, 1));
        final AtomicReference<Level> location = new AtomicReference<>(level);
        final java.util.concurrent.atomic.AtomicBoolean removed = new java.util.concurrent.atomic.AtomicBoolean();
        when(removedTarget.isRemoved()).thenAnswer(call -> removed.get());
        when(movedTarget.level()).thenAnswer(call -> location.get());
        final List<EntityInteractionTask> tasks = new CopyOnWriteArrayList<>();
        final AtomicInteger ran = new AtomicInteger();
        final ServerLevel otherLevel = mock(ServerLevel.class);
        doAnswer(call -> {
            tasks.add(this.engine.entityInteractions().submit(source, removedTarget, (actor, victim) -> ran.incrementAndGet()));
            tasks.add(this.engine.entityInteractions().submit(source, movedTarget, (actor, victim) -> ran.incrementAndGet()));
            removed.set(true);
            location.set(otherLevel);
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(0, ran.get());
        assertEquals(EntityInteractionTask.State.SKIPPED, tasks.get(0).state());
        assertEquals(EntityInteractionTask.Reason.ENTITY_REMOVED, tasks.get(0).reason());
        assertEquals(EntityInteractionTask.State.SKIPPED, tasks.get(1).state());
        assertEquals(EntityInteractionTask.Reason.LEVEL_CHANGED, tasks.get(1).reason());
    }

    @Test
    void interactionApiRejectsRequestsFromAWorkerThatDoesNotOwnTheirSource() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity ticking = this.entity(level, new BlockPos(1, 64, 1));
        final Entity foreignSource = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicReference<EntityInteractionTask> handle = new AtomicReference<>();
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            handle.set(this.engine.entityInteractions().submit(foreignSource, ticking, (actor, victim) -> ran.incrementAndGet()));
            return null;
        }).when(ticking).tick();
        this.tickList.add(ticking);
        this.plan(level).executeAndAwait();
        assertEquals(EntityInteractionTask.State.REJECTED, handle.get().state());
        assertEquals(EntityInteractionTask.Reason.UNOWNED_SOURCE, handle.get().reason());
        assertEquals(0, ran.get());
    }

    @Test
    void interactionApiHasABoundedQueueAndNeverExecutesRejectedOrCancelledWork() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity target = this.entity(level, new BlockPos(49, 64, 1));
        final List<EntityInteractionTask> tasks = new CopyOnWriteArrayList<>();
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            for (int i = 0; i < 4097; i++) {
                tasks.add(i % 2 == 0
                        ? this.engine.entityInteractions().submit(source, target, (actor, victim) -> ran.incrementAndGet())
                        : this.engine.entityInteractions().submitToOwner(source, target, owner -> ran.incrementAndGet()));
            }
            for (int i = 0; i < 4096; i++) {
                assertTrue(tasks.get(i).cancel());
            }
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(EntityInteractionTask.State.REJECTED, tasks.get(4096).state());
        assertEquals(EntityInteractionTask.Reason.QUEUE_FULL, tasks.get(4096).reason());
        assertEquals(0, ran.get());
    }

    @Test
    void interactionApiRetiresQueuedRequestsDuringShutdownBeforeTheirCallbackCanStart() throws InterruptedException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity target = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicReference<EntityInteractionTask> handle = new AtomicReference<>();
        final AtomicReference<EntityInteractionTask> ownerHandle = new AtomicReference<>();
        final AtomicReference<EntityInteractionTask> chunkHandle = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch queued = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch retired = new java.util.concurrent.CountDownLatch(1);
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            handle.set(this.engine.entityInteractions().submit(source, target, (actor, victim) -> ran.incrementAndGet()));
            ownerHandle.set(this.engine.entityInteractions().submitToOwner(source, target, owner -> ran.incrementAndGet()));
            chunkHandle.set(this.engine.entityInteractions().submitToOwnerAfterChunkLoad(source, target, new BlockPos(1600, 64, 1600),
                    (owner, chunk) -> ran.incrementAndGet()));
            queued.countDown();
            assertTrue(retired.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(source).tick();
        final Thread shutdown = new Thread(() -> {
            try {
                assertTrue(queued.await(10, java.util.concurrent.TimeUnit.SECONDS));
                this.engine.shutdown();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                retired.countDown();
            }
        }, "interaction-shutdown");
        // Shutdown waits for the batch, while closure itself must retire its task and unblock the worker.
        final Thread release = new Thread(() -> {
            try {
                assertTrue(queued.await(10, java.util.concurrent.TimeUnit.SECONDS));
                final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while ((!handle.get().isDone() || !ownerHandle.get().isDone() || !chunkHandle.get().isDone()) && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(100_000L);
                }
                assertEquals(EntityInteractionTask.State.SKIPPED, handle.get().state());
                assertEquals(EntityInteractionTask.State.SKIPPED, ownerHandle.get().state());
                assertEquals(EntityInteractionTask.State.SKIPPED, chunkHandle.get().state());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                retired.countDown();
            }
        }, "interaction-retirement-observer");
        shutdown.start();
        release.start();
        this.tickList.add(source);
        try {
            this.plan(level).executeAndAwait();
        } finally {
            retired.countDown();
            shutdown.join(10_000L);
            release.join(10_000L);
        }
        assertEquals(null, failure.get());
        assertFalse(shutdown.isAlive());
        assertFalse(release.isAlive());
        assertEquals(0, ran.get());
        assertEquals(EntityInteractionTask.Reason.SHUTDOWN, handle.get().reason());
        assertEquals(EntityInteractionTask.Reason.SHUTDOWN, ownerHandle.get().reason());
        assertEquals(EntityInteractionTask.Reason.SHUTDOWN, chunkHandle.get().reason());
        verify(level, never()).getChunk(100, 100);
    }

    @Test
    void interactionApiReportsCallbackFailureAndRunsLaterAcceptedWork() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity target = this.entity(level, new BlockPos(49, 64, 1));
        final IllegalStateException cause = new IllegalStateException("interaction callback failed");
        final List<EntityInteractionTask> tasks = new CopyOnWriteArrayList<>();
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            tasks.add(this.engine.entityInteractions().submit(source, target, (actor, victim) -> {
                throw cause;
            }));
            tasks.add(this.engine.entityInteractions().submit(source, target, (actor, victim) -> ran.incrementAndGet()));
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        final net.minecraft.ReportedException failure = assertThrows(net.minecraft.ReportedException.class,
                () -> this.plan(level).executeAndAwait());
        assertEquals(cause, failure.getReport().getException());
        assertEquals(EntityInteractionTask.State.FAILED, tasks.get(0).state());
        assertEquals(cause, tasks.get(0).failure().orElseThrow());
        assertEquals(EntityInteractionTask.State.COMPLETED, tasks.get(1).state());
        assertEquals(1, ran.get());
    }

    @Test
    void queuedOrdinaryAndOwnerInteractionsSkipWhenServerCompletionProofIsLost() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        final java.util.concurrent.atomic.AtomicBoolean valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main && valid.get());
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity target = this.entity(level, new BlockPos(49, 64, 1));
        final List<EntityInteractionTask> tasks = new CopyOnWriteArrayList<>();
        final AtomicInteger callbacks = new AtomicInteger();
        doAnswer(call -> {
            tasks.add(this.engine.entityInteractions().submit(source, target, (first, second) -> callbacks.incrementAndGet()));
            tasks.add(this.engine.entityInteractions().submitToOwner(source, target, owner -> callbacks.incrementAndGet()));
            valid.set(false);
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(0, callbacks.get());
        for (final EntityInteractionTask task : tasks) {
            assertEquals(EntityInteractionTask.State.SKIPPED, task.state());
            assertEquals(EntityInteractionTask.Reason.INVALID_CONTEXT, task.reason());
        }
    }

    @Test
    void interactionApiAllowsQuiescentServerThreadButRejectsAsyncCallersAndClosedLevels() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity target = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicInteger ran = new AtomicInteger();
        when(level.getServer().isSameThread()).thenReturn(false);
        final EntityInteractionTask rejected = this.engine.entityInteractions().submit(source, target, (actor, victim) -> ran.incrementAndGet());
        assertEquals(EntityInteractionTask.Reason.INVALID_CONTEXT, rejected.reason());
        when(level.getServer().isSameThread()).thenReturn(true);
        final EntityInteractionTask completed = this.engine.entityInteractions().submit(source, target, (actor, victim) -> ran.incrementAndGet());
        assertEquals(EntityInteractionTask.State.COMPLETED, completed.state());
        assertEquals(1, ran.get());
        this.engine.clearLevelRegionTasks(level);
        final EntityInteractionTask closed = this.engine.entityInteractions().submit(source, target, (actor, victim) -> ran.incrementAndGet());
        assertEquals(EntityInteractionTask.State.REJECTED, closed.state());
        assertEquals(EntityInteractionTask.Reason.LEVEL_CLOSED, closed.reason());
        assertEquals(1, ran.get());
    }

    @Test
    void interactionApiDoesNotHoldItsMonitorWhileCallingUserCode() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity target = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicReference<EntityInteractionTask> handle = new AtomicReference<>();
        doAnswer(call -> {
            handle.set(this.engine.entityInteractions().submit(source, target, (actor, victim) -> {
                final java.util.concurrent.CompletableFuture<EntityInteractionTask.State> observed = java.util.concurrent.CompletableFuture.supplyAsync(() -> handle.get().state());
                try {
                    assertEquals(EntityInteractionTask.State.RUNNING, observed.get(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError("task state must be readable without blocking on the action's monitor", failure);
                }
            }));
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(EntityInteractionTask.State.COMPLETED, handle.get().state());
    }

    @Test
    void ownerOperationSurvivesConsumedSourceAndKeepsCapturedImpactPosition() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity projectile = this.entity(level, new BlockPos(1, 64, 1));
        final Entity owner = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicReference<net.minecraft.world.phys.Vec3> projectilePosition = new AtomicReference<>(
                new net.minecraft.world.phys.Vec3(1, 64, 1));
        when(projectile.position()).thenAnswer(call -> projectilePosition.get());
        final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
        when(projectile.isRemoved()).thenAnswer(call -> consumed.get());
        final AtomicReference<EntityInteractionTask> request = new AtomicReference<>();
        final List<net.minecraft.world.phys.Vec3> destinations = new CopyOnWriteArrayList<>();
        final Thread serverThread = Thread.currentThread();
        doAnswer(call -> {
            final net.minecraft.world.phys.Vec3 impact = projectile.position();
            request.set(this.engine.entityInteractions().submitToOwner(projectile, owner, currentOwner -> {
                assertEquals(serverThread, Thread.currentThread());
                assertEquals(owner, currentOwner);
                RegionThreadingHooks.assertServerEntityStorageAccess("owner operation teleport");
                destinations.add(impact);
            }));
            // Both the source's remaining movement and its removal may precede application. The request must
            // neither access the projectile again nor cancel an already accepted owner operation for this reason.
            projectilePosition.set(new net.minecraft.world.phys.Vec3(20, 65, 20));
            consumed.set(true);
            assertEquals(EntityInteractionTask.State.QUEUED, request.get().state());
            final Field sourceField = request.get().getClass().getDeclaredField("source");
            sourceField.setAccessible(true);
            assertEquals(null, sourceField.get(request.get()), "owner-only handles must not retain the consumed projectile");
            return null;
        }).when(projectile).tick();
        this.tickList.add(projectile);
        this.plan(level).executeAndAwait();
        assertEquals(List.of(new net.minecraft.world.phys.Vec3(1, 64, 1)), destinations);
        assertEquals(EntityInteractionTask.State.COMPLETED, request.get().state());
        verify(projectile, times(1)).tick();
        verify(projectile, times(1)).position();
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void strictInteractionStillSkipsConsumedSourceWhileOwnerOnlyRequestCompletes() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity owner = this.entity(level, new BlockPos(49, 64, 1));
        final java.util.concurrent.atomic.AtomicBoolean removed = new java.util.concurrent.atomic.AtomicBoolean();
        when(source.isRemoved()).thenAnswer(call -> removed.get());
        final List<EntityInteractionTask> tasks = new CopyOnWriteArrayList<>();
        final List<String> ran = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            tasks.add(this.engine.entityInteractions().submit(source, owner, (actor, target) -> ran.add("strict")));
            tasks.add(this.engine.entityInteractions().submitToOwner(source, owner, target -> ran.add("owner")));
            removed.set(true);
            tasks.add(this.engine.entityInteractions().submitToOwner(source, owner, target -> ran.add("too-late")));
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(List.of("owner"), ran);
        assertEquals(EntityInteractionTask.State.SKIPPED, tasks.get(0).state());
        assertEquals(EntityInteractionTask.Reason.ENTITY_REMOVED, tasks.get(0).reason());
        assertEquals(EntityInteractionTask.State.COMPLETED, tasks.get(1).state());
        assertEquals(EntityInteractionTask.State.REJECTED, tasks.get(2).state());
        assertEquals(EntityInteractionTask.Reason.ENTITY_REMOVED, tasks.get(2).reason());
    }

    @Test
    void ownerOperationIsCancelledWhenTheOwnerRetiresOrChangesWorldAndCanBeCancelledExplicitly() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity removedOwner = this.entity(level, new BlockPos(49, 64, 1));
        final Entity movedOwner = this.entity(level, new BlockPos(50, 64, 1));
        final java.util.concurrent.atomic.AtomicBoolean removed = new java.util.concurrent.atomic.AtomicBoolean();
        final AtomicReference<Level> ownerLevel = new AtomicReference<>(level);
        when(removedOwner.isRemoved()).thenAnswer(call -> removed.get());
        when(movedOwner.level()).thenAnswer(call -> ownerLevel.get());
        final ServerLevel elsewhere = mock(ServerLevel.class);
        final List<EntityInteractionTask> tasks = new CopyOnWriteArrayList<>();
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            tasks.add(this.engine.entityInteractions().submitToOwner(source, removedOwner, target -> ran.incrementAndGet()));
            tasks.add(this.engine.entityInteractions().submitToOwner(source, movedOwner, target -> ran.incrementAndGet()));
            tasks.add(this.engine.entityInteractions().submitToOwner(source, movedOwner, target -> ran.incrementAndGet()));
            assertTrue(tasks.get(2).cancel());
            removed.set(true);
            ownerLevel.set(elsewhere);
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(0, ran.get());
        assertEquals(EntityInteractionTask.Reason.ENTITY_REMOVED, tasks.get(0).reason());
        assertEquals(EntityInteractionTask.Reason.LEVEL_CHANGED, tasks.get(1).reason());
        assertEquals(EntityInteractionTask.State.CANCELLED, tasks.get(2).state());
        for (final EntityInteractionTask task : tasks) {
            for (final String name : List.of("source", "target", "level", "action", "ownerAction")) {
                final Field field = task.getClass().getDeclaredField(name);
                field.setAccessible(true);
                assertEquals(null, field.get(task), "terminal tasks must release all entity/callback references");
            }
        }
    }

    @Test
    void ownerOperationReportsFailureForTheOwnerAndDrainsTheNextRequest() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity owner = this.entity(level, new BlockPos(49, 64, 1));
        final IllegalArgumentException failure = new IllegalArgumentException("owner callback failed");
        final List<EntityInteractionTask> tasks = new CopyOnWriteArrayList<>();
        final AtomicInteger completed = new AtomicInteger();
        doAnswer(call -> {
            tasks.add(this.engine.entityInteractions().submitToOwner(source, owner, target -> {
                throw failure;
            }));
            tasks.add(this.engine.entityInteractions().submitToOwner(source, owner, target -> completed.incrementAndGet()));
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        final net.minecraft.ReportedException report = assertThrows(net.minecraft.ReportedException.class,
                () -> this.plan(level).executeAndAwait());
        assertEquals(failure, report.getReport().getException());
        assertEquals(EntityInteractionTask.State.FAILED, tasks.get(0).state());
        assertEquals(failure, tasks.get(0).failure().orElseThrow());
        assertEquals(EntityInteractionTask.State.COMPLETED, tasks.get(1).state());
        assertEquals(1, completed.get());
        verify(source, times(1)).tick();
    }

    @Test
    void ownerOperationsRespectSourceAuthorizationAndRunInlineOnlyOnTheQuiescentServerThread() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity foreign = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicReference<EntityInteractionTask> rejected = new AtomicReference<>();
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            rejected.set(this.engine.entityInteractions().submitToOwner(foreign, source, owner -> ran.incrementAndGet()));
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(EntityInteractionTask.State.REJECTED, rejected.get().state());
        assertEquals(EntityInteractionTask.Reason.UNOWNED_SOURCE, rejected.get().reason());
        when(level.getServer().isSameThread()).thenReturn(false);
        final EntityInteractionTask async = this.engine.entityInteractions().submitToOwner(source, foreign, owner -> ran.incrementAndGet());
        assertEquals(EntityInteractionTask.Reason.INVALID_CONTEXT, async.reason());
        when(level.getServer().isSameThread()).thenReturn(true);
        final Thread server = Thread.currentThread();
        final EntityInteractionTask inline = this.engine.entityInteractions().submitToOwner(source, foreign, owner -> {
            assertEquals(server, Thread.currentThread());
            ran.incrementAndGet();
        });
        assertEquals(EntityInteractionTask.State.COMPLETED, inline.state());
        assertEquals(1, ran.get());
    }

    @Test
    void ownerOperationRejectsARealAsyncCallerAndAnAlreadyClosedLevel() throws Exception {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity owner = this.entity(level, new BlockPos(49, 64, 1));
        final Thread serverThread = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == serverThread);
        final AtomicInteger ran = new AtomicInteger();
        final EntityInteractionTask request = java.util.concurrent.CompletableFuture.supplyAsync(() -> this.engine.entityInteractions().submitToOwner(source, owner, current -> ran.incrementAndGet()))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(EntityInteractionTask.State.REJECTED, request.state());
        assertEquals(EntityInteractionTask.Reason.INVALID_CONTEXT, request.reason());
        this.engine.clearLevelRegionTasks(level);
        final EntityInteractionTask closed = this.engine.entityInteractions().submitToOwner(source, owner,
                current -> ran.incrementAndGet());
        assertEquals(EntityInteractionTask.State.REJECTED, closed.state());
        assertEquals(EntityInteractionTask.Reason.LEVEL_CLOSED, closed.reason());
        assertEquals(0, ran.get());
        this.engine.shutdown();
        final EntityInteractionTask stopped = this.engine.entityInteractions().submitToOwner(source, owner,
                current -> ran.incrementAndGet());
        assertEquals(EntityInteractionTask.State.REJECTED, stopped.state());
        assertEquals(EntityInteractionTask.Reason.SHUTDOWN, stopped.reason());
    }

    @Test
    void aRemovedEntityFinishingItsTickCanQueueAStrictInteractionWhichIsSafelySkipped() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity target = this.entity(level, new BlockPos(49, 64, 1));
        final java.util.concurrent.atomic.AtomicBoolean removed = new java.util.concurrent.atomic.AtomicBoolean();
        when(source.isRemoved()).thenAnswer(call -> removed.get());
        final AtomicReference<EntityInteractionTask> task = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            removed.set(true);
            task.set(this.engine.entityInteractions().submit(source, target, (actor, victim) -> calls.incrementAndGet()));
            assertEquals(EntityInteractionTask.State.QUEUED, task.get().state());
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(EntityInteractionTask.State.SKIPPED, task.get().state());
        assertEquals(EntityInteractionTask.Reason.ENTITY_REMOVED, task.get().reason());
        assertEquals(0, calls.get());
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void mainThreadBatchCompletionCanRunNestedOwnerOperationsAfterWorkersFinish() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity owner = this.entity(level, new BlockPos(49, 64, 1));
        final List<String> order = new CopyOnWriteArrayList<>();
        final AtomicReference<EntityInteractionTask> nested = new AtomicReference<>();
        doAnswer(call -> {
            this.engine.entityInteractions().submit(source, owner, (actor, target) -> {
                assertEquals(main, Thread.currentThread());
                order.add("outer");
                nested.set(this.engine.entityInteractions().submitToOwner(actor, target, currentOwner -> order.add("inner")));
                assertEquals(EntityInteractionTask.State.COMPLETED, nested.get().state());
                order.add("after-inner");
            });
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(List.of("outer", "inner", "after-inner"), order);
        assertEquals(2L, this.engine.entityInteractionCounters().get("COMPLETED"));
    }

    @Test
    void mainThreadServiceDuringWorkerBarrierStillRejectsOwnerOperations() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity owner = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicReference<EntityInteractionTask> task = new AtomicReference<>();
        final AtomicInteger ran = new AtomicInteger();
        doAnswer(call -> {
            task.set(this.engine.runOnServerThread(level, () -> {
                assertEquals(main, Thread.currentThread());
                return this.engine.entityInteractions().submitToOwner(source, owner, current -> ran.incrementAndGet());
            }));
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(EntityInteractionTask.State.REJECTED, task.get().state());
        assertEquals(EntityInteractionTask.Reason.INVALID_CONTEXT, task.get().reason());
        assertEquals(0, ran.get(), "being on the main thread is insufficient while another worker is still running");
    }

    @Test
    void removedPearlGatewayContinuationKeepsVanillaCallbackOnTheQuiescentServerThread() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        when(level.getServer().isSameThread()).thenReturn(true);
        final var pearl = this.entity(net.minecraft.world.entity.projectile.ThrownEnderpearl.class, level,
                new AtomicReference<>(new BlockPos(1, 64, 1)));
        final var owner = mock(net.minecraft.server.level.ServerPlayer.class);
        when(owner.level()).thenReturn(level);
        when(pearl.getOwner()).thenReturn(owner);
        when(pearl.isRemoved()).thenReturn(true);
        final var state = mock(net.minecraft.world.level.block.state.BlockState.class);
        when(state.is(net.minecraft.world.level.block.Blocks.END_GATEWAY)).thenReturn(true);
        final Method inside = net.minecraft.world.entity.projectile.ThrownEnderpearl.class.getDeclaredMethod("onInsideBlock",
                net.minecraft.world.level.block.state.BlockState.class);
        inside.setAccessible(true);
        doCallRealMethod().when(pearl);
        inside.invoke(pearl, state);
        inside.invoke(pearl, state);
        verify(owner, times(1)).onInsideBlock(state);
        assertEquals(0L, this.engine.entityInteractionCounters().get("admitted"),
                "a removed pearl's original tail is not a new owner-operation admission");
    }

    @Test
    void gatewayWithoutRegionEngineRetainsOriginalPlayerCallback() throws ReflectiveOperationException {
        final ServerLevel level = this.mockLevel();
        final var pearl = this.entity(net.minecraft.world.entity.projectile.ThrownEnderpearl.class, level,
                new AtomicReference<>(new BlockPos(1, 64, 1)));
        final var owner = mock(net.minecraft.server.level.ServerPlayer.class);
        when(pearl.getOwner()).thenReturn(owner);
        final var state = mock(net.minecraft.world.level.block.state.BlockState.class);
        when(state.is(net.minecraft.world.level.block.Blocks.END_GATEWAY)).thenReturn(true);
        final Method inside = net.minecraft.world.entity.projectile.ThrownEnderpearl.class.getDeclaredMethod("onInsideBlock",
                net.minecraft.world.level.block.state.BlockState.class);
        inside.setAccessible(true);
        doCallRealMethod().when(pearl);
        inside.invoke(pearl, state);
        inside.invoke(pearl, state);
        verify(owner, times(1)).onInsideBlock(state);
    }

    @Test
    void nativeWorkerCannotForgeAPearlGatewayRequestForAnUnownedPearl() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity ticking = this.entity(level, new BlockPos(1, 64, 1));
        final var foreignPearl = this.entity(net.minecraft.world.entity.projectile.ThrownEnderpearl.class, level,
                new AtomicReference<>(new BlockPos(49, 64, 1)));
        final var owner = mock(net.minecraft.server.level.ServerPlayer.class);
        final var state = mock(net.minecraft.world.level.block.state.BlockState.class);
        final AtomicReference<IllegalStateException> refused = new AtomicReference<>();
        doAnswer(call -> {
            refused.set(assertThrows(IllegalStateException.class,
                    () -> RegionThreadingHooks.submitPearlGatewayOwnerOperation(foreignPearl, owner, state)));
            return null;
        }).when(ticking).tick();
        this.tickList.add(ticking);
        this.plan(level).executeAndAwait();
        assertTrue(refused.get().getMessage().contains("UNOWNED_SOURCE"));
        verify(owner, never()).onInsideBlock(state);
    }

    @Test
    void gatewayCallbackCanSubmitANestedOwnerOperationWithoutRepeatingTheGateway() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        final var pearl = this.entity(net.minecraft.world.entity.projectile.ThrownEnderpearl.class, level,
                new AtomicReference<>(new BlockPos(1, 64, 1)));
        final var owner = this.entity(net.minecraft.server.level.ServerPlayer.class, level,
                new AtomicReference<>(new BlockPos(49, 64, 1)));
        when(pearl.getOwner()).thenReturn(owner);
        final var state = mock(net.minecraft.world.level.block.state.BlockState.class);
        when(state.is(net.minecraft.world.level.block.Blocks.END_GATEWAY)).thenReturn(true);
        final Method inside = net.minecraft.world.entity.projectile.ThrownEnderpearl.class.getDeclaredMethod("onInsideBlock",
                net.minecraft.world.level.block.state.BlockState.class);
        inside.setAccessible(true);
        doCallRealMethod().when(pearl);
        inside.invoke(pearl, state);
        final AtomicInteger effects = new AtomicInteger();
        doAnswer(call -> {
            final EntityInteractionTask nested = this.engine.entityInteractions().submitToOwner(owner, owner,
                    current -> effects.incrementAndGet());
            assertEquals(EntityInteractionTask.State.COMPLETED, nested.state());
            return null;
        }).when(owner).onInsideBlock(state);
        doAnswer(call -> {
            inside.invoke(pearl, state);
            return null;
        }).when(pearl).tick();
        this.tickList.add(pearl);
        this.plan(level).executeAndAwait();
        verify(owner, times(1)).onInsideBlock(state);
        assertEquals(1, effects.get());
        assertEquals(2L, this.engine.entityInteractionCounters().get("COMPLETED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourceOwnedGatewayCallbackIsQueuedAndSurvivesPearlConsumption() throws ReflectiveOperationException {
        this.installEngine();
        // Exercise the new boundary as a worker callback without enabling pearl worker flight in production.
        // Remove only this test engine's conservative class policy before it resolves any runtime classes.
        final Field policyField = RegionThreadingEngine.class.getDeclaredField("entityTickPolicy");
        policyField.setAccessible(true);
        final Object policy = policyField.get(this.engine);
        final Field declarations = policy.getClass().getDeclaredField("declarations");
        declarations.setAccessible(true);
        ((Map<Class<?>, String>) declarations.get(policy)).remove(net.minecraft.world.entity.projectile.ThrownEnderpearl.class);
        ((Map<Class<?>, String>) declarations.get(policy)).remove(net.minecraft.world.entity.projectile.Projectile.class);
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        final var pearl = this.entity(net.minecraft.world.entity.projectile.ThrownEnderpearl.class, level,
                new AtomicReference<>(new BlockPos(1, 64, 1)));
        final var owner = this.entity(net.minecraft.server.level.ServerPlayer.class, level,
                new AtomicReference<>(new BlockPos(49, 64, 1)));
        when(pearl.getOwner()).thenReturn(owner);
        final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
        when(pearl.isRemoved()).thenAnswer(call -> consumed.get());
        final var state = mock(net.minecraft.world.level.block.state.BlockState.class);
        when(state.is(net.minecraft.world.level.block.Blocks.END_GATEWAY)).thenReturn(true);
        final Method inside = net.minecraft.world.entity.projectile.ThrownEnderpearl.class.getDeclaredMethod("onInsideBlock",
                net.minecraft.world.level.block.state.BlockState.class);
        inside.setAccessible(true);
        doCallRealMethod().when(pearl);
        inside.invoke(pearl, state);
        final List<Thread> calls = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            calls.add(Thread.currentThread());
            RegionThreadingHooks.assertServerEntityStorageAccess("gateway owner mutation");
            return null;
        }).when(owner).onInsideBlock(state);
        doAnswer(call -> {
            assertTrue(Thread.currentThread().getName().startsWith("NeoForge Region Worker"));
            inside.invoke(pearl, state);
            assertEquals(List.of(), calls, "the owner must not be touched before the worker phase ends");
            consumed.set(true);
            return null;
        }).when(pearl).tick();
        this.tickList.add(pearl);
        this.plan(level).executeAndAwait();
        assertEquals(List.of(main), calls);
        assertEquals(1L, this.engine.entityInteractionCounters().get("COMPLETED"));
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void fireworkExplosionKeepsAttachedPlayerDamageOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final var rocket = this.entity(net.minecraft.world.entity.projectile.FireworkRocketEntity.class, level,
                new AtomicReference<>(new BlockPos(1, 64, 1)));
        final Player attached = this.entity(Player.class, level, new AtomicReference<>(new BlockPos(49, 64, 1)));
        final Field attachedField = net.minecraft.world.entity.projectile.FireworkRocketEntity.class.getDeclaredField("attachedToEntity");
        attachedField.setAccessible(true);
        attachedField.set(rocket, attached);
        final Field itemKey = net.minecraft.world.entity.projectile.FireworkRocketEntity.class.getDeclaredField("DATA_ID_FIREWORKS_ITEM");
        itemKey.setAccessible(true);
        final var data = mock(net.minecraft.network.syncher.SynchedEntityData.class);
        final var item = mock(net.minecraft.world.item.ItemStack.class);
        when(item.get(net.minecraft.core.component.DataComponents.FIREWORKS)).thenReturn(
                new net.minecraft.world.item.component.Fireworks(1, List.of(mock(net.minecraft.world.item.component.FireworkExplosion.class))));
        @SuppressWarnings("unchecked")
        final var key = (net.minecraft.network.syncher.EntityDataAccessor<net.minecraft.world.item.ItemStack>) itemKey.get(null);
        when(data.get(key)).thenReturn(item);
        final Field dataField = Entity.class.getDeclaredField("entityData");
        dataField.setAccessible(true);
        dataField.set(rocket, data);
        when(rocket.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0, 63, 0, 1, 65, 1));
        when(rocket.position()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
        doReturn(List.of()).when(level).getEntitiesOfClass(any(), any());
        final var sources = mock(net.minecraft.world.damagesource.DamageSources.class);
        when(rocket.damageSources()).thenReturn(sources);
        final var damage = mock(net.minecraft.world.damagesource.DamageSource.class);
        when(sources.fireworks(rocket, null)).thenReturn(damage);
        final Thread main = Thread.currentThread();
        final AtomicInteger applied = new AtomicInteger();
        doAnswer(call -> {
            RegionThreadingHooks.assertServerEntityStorageAccess(attached, "attached firework damage");
            assertEquals(main, Thread.currentThread());
            applied.incrementAndGet();
            return true;
        }).when(attached).hurt(damage, 7F);
        final Method explodeDamage = net.minecraft.world.entity.projectile.FireworkRocketEntity.class.getDeclaredMethod("dealExplosionDamage");
        explodeDamage.setAccessible(true);
        doAnswer(call -> {
            explodeDamage.invoke(rocket);
            return null;
        }).when(rocket).tick();
        this.tickList.add(rocket);
        this.plan(level).executeAndAwait();
        assertEquals(1, applied.get());
        verify(rocket, times(1)).tick();
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void llamaSpitRealImpactCannotDamageAForeignVictimOnAWorker() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final var spit = this.entity(net.minecraft.world.entity.projectile.LlamaSpit.class, level,
                new AtomicReference<>(new BlockPos(1, 64, 1)));
        final LivingEntity owner = this.entity(LivingEntity.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final Entity victim = this.entity(level, new BlockPos(49, 64, 1));
        when(spit.getOwner()).thenReturn(owner);
        final var sources = mock(net.minecraft.world.damagesource.DamageSources.class);
        final var damage = mock(net.minecraft.world.damagesource.DamageSource.class);
        when(spit.damageSources()).thenReturn(sources);
        when(sources.spit(spit, owner)).thenReturn(damage);
        final Thread main = Thread.currentThread();
        final AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            RegionThreadingHooks.assertServerEntityStorageAccess(victim, "projectile foreign victim damage");
            assertEquals(main, Thread.currentThread());
            calls.incrementAndGet();
            return false;
        }).when(victim).hurt(damage, 1F);
        final Method impact = net.minecraft.world.entity.projectile.LlamaSpit.class.getDeclaredMethod("onHitEntity", net.minecraft.world.phys.EntityHitResult.class);
        impact.setAccessible(true);
        final var hit = new net.minecraft.world.phys.EntityHitResult(victim, net.minecraft.world.phys.Vec3.ZERO);
        doCallRealMethod().when(spit);
        impact.invoke(spit, hit);
        doAnswer(call -> {
            impact.invoke(spit, hit);
            return null;
        }).when(spit).tick();
        this.tickList.add(spit);
        this.plan(level).executeAndAwait();
        assertEquals(1, calls.get());
        verify(spit, times(1)).tick();
        verify(victim, times(1)).hurt(damage, 1F);
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void shulkerBulletImpactAndSecondaryOwnerEffectsStayOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final var bullet = this.entity(net.minecraft.world.entity.projectile.ShulkerBullet.class, level,
                new AtomicReference<>(new BlockPos(1, 64, 1)));
        final LivingEntity owner = this.entity(net.minecraft.world.entity.monster.Shulker.class, level,
                new AtomicReference<>(new BlockPos(49, 64, 1)));
        final Entity victim = this.entity(level, new BlockPos(2, 64, 1));
        when(bullet.getOwner()).thenReturn(owner);
        final var sources = mock(net.minecraft.world.damagesource.DamageSources.class);
        when(bullet.damageSources()).thenReturn(sources);
        final var damage = mock(net.minecraft.world.damagesource.DamageSource.class);
        when(sources.mobProjectile(bullet, owner)).thenReturn(damage);
        final Thread main = Thread.currentThread();
        final AtomicInteger effects = new AtomicInteger();
        doAnswer(call -> {
            assertEquals(main, Thread.currentThread());
            // A hurt listener/enchantment can mutate the owner (the live report teleported the Shulker).
            RegionThreadingHooks.assertServerEntityStorageAccess(owner, "Entity.setPosRaw");
            effects.incrementAndGet();
            return true;
        }).when(victim).hurt(damage, 4F);
        final Method hit = net.minecraft.world.entity.projectile.ShulkerBullet.class.getDeclaredMethod("onHitEntity", net.minecraft.world.phys.EntityHitResult.class);
        hit.setAccessible(true);
        final var impact = new net.minecraft.world.phys.EntityHitResult(victim, net.minecraft.world.phys.Vec3.ZERO);
        doCallRealMethod().when(bullet);
        hit.invoke(bullet, impact);
        doAnswer(call -> {
            hit.invoke(bullet, impact);
            return null;
        }).when(bullet).tick();
        this.tickList.add(bullet);
        this.plan(level).executeAndAwait();
        assertEquals(1, effects.get());
        verify(victim, times(1)).hurt(damage, 4F);
        verify(bullet, times(1)).tick();
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
        assertEquals(1L, this.engine.diagnostics().serverThreadOnlyEntityTicksByReason()
                .get("shulker_bullet_damage_and_enchantment_effects"));
        final var subclass = this.entity(ShulkerBulletSubclass.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        assertFalse(this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER).accept(subclass));
    }

    private static class ShulkerBulletSubclass extends net.minecraft.world.entity.projectile.ShulkerBullet {
        ShulkerBulletSubclass(Level level) {
            super(net.minecraft.world.entity.EntityType.SHULKER_BULLET, level);
        }
    }

    @Test
    void witherSkullImpactKeepsItsForeignOwnersHealingOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final var skull = this.entity(net.minecraft.world.entity.projectile.WitherSkull.class, level,
                new AtomicReference<>(new BlockPos(1, 64, 1)));
        final LivingEntity owner = this.entity(LivingEntity.class, level, new AtomicReference<>(new BlockPos(49, 64, 1)));
        final Entity victim = this.entity(level, new BlockPos(1, 64, 1));
        when(skull.getOwner()).thenReturn(owner);
        final var sources = mock(net.minecraft.world.damagesource.DamageSources.class);
        when(skull.damageSources()).thenReturn(sources);
        final var damage = mock(net.minecraft.world.damagesource.DamageSource.class);
        when(sources.witherSkull(skull, owner)).thenReturn(damage);
        when(victim.hurt(damage, 8F)).thenReturn(true);
        final AtomicInteger heals = new AtomicInteger();
        final Thread main = Thread.currentThread();
        doAnswer(call -> {
            RegionThreadingHooks.assertServerEntityStorageAccess(owner, "wither skull owner healing");
            assertEquals(main, Thread.currentThread());
            heals.incrementAndGet();
            return null;
        }).when(owner).heal(5F);
        final Method hit = net.minecraft.world.entity.projectile.WitherSkull.class.getDeclaredMethod("onHitEntity", net.minecraft.world.phys.EntityHitResult.class);
        hit.setAccessible(true);
        final var impact = new net.minecraft.world.phys.EntityHitResult(victim, net.minecraft.world.phys.Vec3.ZERO);
        doCallRealMethod().when(skull);
        hit.invoke(skull, impact);
        // Prepare the HitResult before Mockito's stubbing starts: its constructor calls Entity methods.
        doAnswer(call -> {
            hit.invoke(skull, impact);
            return null;
        }).when(skull).tick();
        this.tickList.add(skull);
        this.plan(level).executeAndAwait();
        assertEquals(1, heals.get());
        verify(victim, times(1)).hurt(damage, 8F);
        verify(skull, times(1)).tick();
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void fireworkAndSkullSubclassesCannotBypassEitherAdmissionPathOrPassengerPolicy() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        for (final Class<? extends Entity> type : List.of(FireworkSubclass.class, SkullSubclass.class)) {
            final Entity projectile = this.entity(type, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
            assertFalse(this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER).accept(projectile));
            final Entity vehicle = this.entity(level, new BlockPos(1, 64, 1));
            when(vehicle.getPassengers()).thenReturn(List.of(projectile));
            when(vehicle.hasPassenger(projectile)).thenReturn(true);
            when(projectile.getVehicle()).thenReturn(vehicle);
            assertFalse(this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER).accept(vehicle));
            when(projectile.getVehicle()).thenReturn(null);
            doAnswer(call -> {
                threads.add(Thread.currentThread());
                return null;
            }).when(projectile).tick();
            this.tickList.add(projectile);
        }
        this.plan(level).executeAndAwait();
        assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), threads);
    }

    private static class FireworkSubclass extends net.minecraft.world.entity.projectile.FireworkRocketEntity {
        FireworkSubclass(Level level) {
            super(net.minecraft.world.entity.EntityType.FIREWORK_ROCKET, level);
        }
    }

    private static class SkullSubclass extends net.minecraft.world.entity.projectile.WitherSkull {
        SkullSubclass(Level level) {
            super(net.minecraft.world.entity.EntityType.WITHER_SKULL, level);
        }
    }

    @Test
    void handedOffAdmissionRevalidatesTheEntityLevelBeforePublishingIt() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final ServerLevel elsewhere = mock(ServerLevel.class);
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity pending = this.entity(level, new BlockPos(49, 64, 1));
        final Entity unchanged = this.entity(level, new BlockPos(49, 64, 1));
        final AtomicReference<Level> pendingLevel = new AtomicReference<>(level);
        when(pending.level()).thenAnswer(call -> pendingLevel.get());
        final List<Entity> admitted = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        doAnswer(call -> {
            assertTrue(RegionThreadingHooks.handOffEntityAddition(level, pending));
            assertTrue(RegionThreadingHooks.handOffEntityAddition(level, unchanged));
            pendingLevel.set(elsewhere);
            assertEquals(List.of(), admitted);
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        this.plan(level).executeAndAwait();
        assertEquals(List.of(unchanged), admitted, "a request cannot publish an entity which now belongs to a different level");
    }

    @Test
    void workersAllocateAndPublishMapsThroughOneMainThreadRegistry(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        when(level.getServer().overworld()).thenReturn(level);
        final var storage = org.mockito.Mockito.spy(new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(), null, null));
        doReturn(storage).when(level).getDataStorage();
        doAnswer(call -> {
            assertEquals(main, Thread.currentThread(), "first get/load of a global map record belongs to the main thread");
            return call.callRealMethod();
        }).when(storage).get(any(), any());
        doAnswer(call -> {
            assertEquals(main, Thread.currentThread(), "map publication must use the same server-owned cache");
            return call.callRealMethod();
        }).when(storage).set(any(), any());
        final List<Integer> ids = new CopyOnWriteArrayList<>();
        for (final int x : List.of(1, 49)) {
            final Entity entity = this.entity(level, new BlockPos(x, 64, 1));
            doAnswer(call -> {
                assertTrue(Thread.currentThread().getName().startsWith("NeoForge Region Worker"));
                for (int i = 0; i < 20; i++) {
                    final var id = level.getFreeMapId();
                    final var map = mock(net.minecraft.world.level.saveddata.maps.MapItemSavedData.class);
                    level.setMapData(id, map);
                    assertEquals(map, level.getMapData(id));
                    ids.add(id.id());
                }
                return null;
            }).when(entity).tick();
            this.tickList.add(entity);
        }
        this.plan(level).executeAndAwait();
        assertEquals(40, new java.util.HashSet<>(ids).size());
        for (int i = 0; i < 40; i++) assertTrue(ids.contains(i));
        assertEquals(2L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(40, level.getFreeMapId().id());
    }

    @Test
    void mapStorageHandoffPreservesFailuresAndItsCallerContinuesOnTheSameWorker() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final RuntimeException failure = new RuntimeException("map cache load failed");
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        final AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            final Thread worker = Thread.currentThread();
            final RuntimeException received = assertThrows(RuntimeException.class,
                    () -> RegionThreadingHooks.runMapStorageOperation(level, () -> {
                        assertEquals(main, Thread.currentThread());
                        calls.incrementAndGet();
                        throw failure;
                    }));
            assertEquals(failure, received);
            assertEquals(worker, Thread.currentThread());
            return null;
        }).when(entity).tick();
        this.tickList.add(entity);
        this.plan(level).executeAndAwait();
        assertEquals(1, calls.get());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void mapStorageAccessWithoutAServicedBatchRejectsBeforeTouchingTheCache() throws Exception {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Field pool = RegionThreadingEngine.class.getDeclaredField("workerPool");
        pool.setAccessible(true);
        final var workers = (net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor) pool.get(this.engine);
        final var done = new java.util.concurrent.CompletableFuture<IllegalStateException>();
        assertTrue(workers.execute("map access outside batch", () -> {
            try {
                done.complete(assertThrows(IllegalStateException.class, level::getFreeMapId));
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        }));
        assertTrue(done.get(5, java.util.concurrent.TimeUnit.SECONDS).getMessage().contains("serviced tick batch"));
        verify(level, never()).getDataStorage();
    }

    @Test
    void chunkBackedOwnerOperationLoadsAfterWorkersAndPreservesConsumedSourceEffectsExactlyOnce() {
        this.chunkOwnerOperationCase("success");
    }

    @Test
    void cancelledChunkOwnerOperationDoesNotAcquireATicketOrLoad() {
        this.chunkOwnerOperationCase("cancel");
    }

    @Test
    void ownerRetiredBeforeChunkOperationDoesNotLoad() {
        this.chunkOwnerOperationCase("removed-before");
    }

    @Test
    void ownerRetiredDuringChunkLoadingSkipsCallbackAndReleasesTicket() {
        this.chunkOwnerOperationCase("removed-during");
    }

    @Test
    void ownerChangesLevelDuringChunkLoadingSkipsCallbackAndReleasesTicket() {
        this.chunkOwnerOperationCase("level-during");
    }

    @Test
    void failedChunkLoadReleasesTicketDrainsLaterRequestsAndNeverReplaysSource() {
        this.chunkOwnerOperationCase("load-failure");
    }

    @Test
    void failedLoadedCallbackReleasesTicketAndDoesNotReplayItsInventoryEffects() {
        this.chunkOwnerOperationCase("callback-failure");
    }

    @Test
    void ticketReleaseFailureIsReportedAfterTheLoadedCallbackWithoutReplay() {
        this.chunkOwnerOperationCase("release-failure");
    }

    @Test
    void reusedLoadAndReleaseFailurePreservesOriginalFailureAndDrainsLaterRequests() {
        this.chunkOwnerOperationCase("both-failure");
    }

    @Test
    void lossOfQuiescentServerProofDuringLoadingSkipsTheOwnerCallback() {
        this.chunkOwnerOperationCase("context-during");
    }

    private void chunkOwnerOperationCase(String mode) {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        final var serverContextValid = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main && serverContextValid.get());
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity owner = this.entity(level, new BlockPos(49, 64, 1));
        final var sourceGone = new java.util.concurrent.atomic.AtomicBoolean();
        final var ownerGone = new java.util.concurrent.atomic.AtomicBoolean();
        final var ownerLevel = new AtomicReference<Level>(level);
        when(source.isRemoved()).thenAnswer(call -> sourceGone.get());
        when(owner.isRemoved()).thenAnswer(call -> ownerGone.get());
        when(owner.level()).thenAnswer(call -> ownerLevel.get());
        final var input = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
        final List<net.minecraft.world.item.ItemStack> output = new CopyOnWriteArrayList<>();
        final List<String> order = new CopyOnWriteArrayList<>();
        final var position = new BlockPos.MutableBlockPos(1600, 64, -1600);
        final net.minecraft.world.level.ChunkPos destination = new net.minecraft.world.level.ChunkPos(position);
        final var loaded = mock(net.minecraft.world.level.chunk.LevelChunk.class);
        final var chunks = level.getChunkSource();
        final AtomicReference<Object> ticketKey = new AtomicReference<>();
        final AtomicReference<Object> ticketType = new AtomicReference<>();
        final AtomicInteger tickets = new AtomicInteger();
        final RuntimeException injected = new RuntimeException(mode);
        doAnswer(call -> {
            assertEquals(main, Thread.currentThread());
            assertTrue(sourceGone.get(), "the source worker must return before ticket acquisition");
            assertEquals(destination, call.getArgument(1));
            assertEquals(0, (int) call.getArgument(2));
            ticketType.set(call.getArgument(0));
            ticketKey.set(call.getArgument(3));
            assertEquals(0, tickets.getAndIncrement());
            order.add("ticket");
            return null;
        }).when(chunks).addRegionTicket(any(), any(), anyInt(), any());
        doAnswer(call -> {
            assertEquals(main, Thread.currentThread());
            assertEquals(ticketType.get(), call.getArgument(0));
            assertEquals(destination, call.getArgument(1));
            assertEquals(ticketKey.get(), call.getArgument(3));
            assertEquals(1, tickets.getAndDecrement());
            order.add("release");
            if (mode.equals("release-failure") || mode.equals("both-failure")) throw injected;
            return null;
        }).when(chunks).removeRegionTicket(any(), any(), anyInt(), any());
        doAnswer(call -> {
            assertEquals(main, Thread.currentThread());
            assertEquals(1, tickets.get());
            order.add("load");
            if (mode.equals("removed-during")) ownerGone.set(true);
            if (mode.equals("level-during")) ownerLevel.set(mock(ServerLevel.class));
            if (mode.equals("context-during")) serverContextValid.set(false);
            if (mode.equals("load-failure") || mode.equals("both-failure")) throw injected;
            return loaded;
        }).when(level).getChunk(destination.x, destination.z);
        final AtomicReference<EntityInteractionTask> handle = new AtomicReference<>();
        final AtomicInteger later = new AtomicInteger();
        doAnswer(call -> {
            assertTrue(Thread.currentThread().getName().startsWith("NeoForge Region Worker"));
            output.add(input.split(1));
            order.add("source");
            handle.set(this.engine.entityInteractions().submitToOwnerAfterChunkLoad(source, owner, position, (currentOwner, chunk) -> {
                assertEquals(main, Thread.currentThread());
                assertEquals(owner, currentOwner);
                assertEquals(loaded, chunk);
                assertEquals(1, tickets.get());
                assertEquals(EntityInteractionTask.State.RUNNING, handle.get().state());
                assertFalse(handle.get().cancel());
                order.add("callback");
                if (mode.equals("callback-failure")) throw injected;
            }));
            assertEquals(EntityInteractionTask.State.QUEUED, handle.get().state());
            if (mode.equals("cancel")) assertTrue(handle.get().cancel());
            if (mode.equals("removed-before")) ownerGone.set(true);
            this.engine.entityInteractions().submitToOwner(source, source, current -> later.incrementAndGet());
            position.set(0, 0, 0);
            sourceGone.set(true);
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        final boolean failure = mode.equals("load-failure") || mode.equals("callback-failure")
                || mode.equals("release-failure") || mode.equals("both-failure");
        if (failure) {
            assertThrows(net.minecraft.ReportedException.class, () -> this.plan(level).executeAndAwait());
            assertEquals(EntityInteractionTask.State.FAILED, handle.get().state());
            assertEquals(injected, handle.get().failure().orElseThrow());
        } else {
            this.plan(level).executeAndAwait();
            final EntityInteractionTask.State expected = mode.equals("success") ? EntityInteractionTask.State.COMPLETED
                    : mode.equals("cancel") ? EntityInteractionTask.State.CANCELLED : EntityInteractionTask.State.SKIPPED;
            assertEquals(expected, handle.get().state());
        }
        if (mode.equals("context-during")) assertEquals(EntityInteractionTask.Reason.INVALID_CONTEXT, handle.get().reason());
        if (mode.equals("level-during")) assertEquals(EntityInteractionTask.Reason.LEVEL_CHANGED, handle.get().reason());
        for (String fieldName : List.of("source", "target", "level", "action", "ownerAction", "loadPosition", "loadedAction")) {
            try {
                final Field field = handle.get().getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                assertEquals(null, field.get(handle.get()), "terminal handle must release " + fieldName);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
        if (mode.startsWith("removed")) assertEquals(EntityInteractionTask.Reason.ENTITY_REMOVED, handle.get().reason());
        assertEquals(1, input.getCount());
        assertEquals(1, output.size());
        assertEquals(1, output.getFirst().getCount());
        verify(source, times(1)).tick();
        assertEquals(0, tickets.get());
        // This independent accepted request targets the consumed source and must reach its normal skip state;
        // use the counter to prove the queue continues after a load/callback failure.
        assertTrue(this.engine.entityInteractionCounters().get("SKIPPED") >= 1L);
        assertEquals(0, later.get());
        final boolean noLoad = mode.equals("cancel") || mode.equals("removed-before");
        assertEquals(noLoad ? List.of("source")
                : mode.equals("success") || mode.equals("callback-failure") || mode.equals("release-failure") ? List.of("source", "ticket", "load", "callback", "release")
                        : List.of("source", "ticket", "load", "release"),
                order);
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void selfDrivenMissingChunkTruncatesTheTickWithoutInventoryReplay() throws Exception {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        final var chunks = level.getChunkSource();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        when(chunks.getChunkNow(100, 100)).thenReturn(null);
        final var chunkMap = mock(net.minecraft.server.level.ChunkMap.class);
        final Field mapField = ServerChunkCache.class.getDeclaredField("chunkMap");
        mapField.setAccessible(true);
        mapField.set(chunks, chunkMap);
        final Field levelField = ServerChunkCache.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(chunks, level);
        final var input = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
        final List<net.minecraft.world.item.ItemStack> output = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            output.add(input.split(1));
            chunks.getChunk(100, 100, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            return null;
        }).when(entity).tick();
        doCallRealMethod().when(chunks).getChunk(100, 100, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        this.tickList.add(entity);
        this.plan(level).executeAndAwait();
        assertEquals(1L, this.engine.diagnostics().truncatedEntityTicks(),
                "the chunk refusal is truncated and counted instead of stopping the server");
        assertEquals(1, input.getCount());
        assertEquals(1, output.size());
        verify(entity, times(1)).tick();
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void declaredUnboundedChunkUserStaysOnServerBeforeAnyTickEffects() {
        this.installEngine();
        this.engine.serverThreadOnlyEntity(Entity.class, "requires_unbounded_chunk_access");
        final ServerLevel level = this.mockLevel();
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        final AtomicInteger effects = new AtomicInteger();
        doAnswer(call -> {
            assertFalse(Thread.currentThread().getName().startsWith("NeoForge Region Worker"));
            effects.incrementAndGet();
            return null;
        }).when(entity).tick();
        assertFalse(this.engine.beginPerEntityTickPlan(level, current -> true, PARKING_BARRIER).accept(entity));
        this.tickList.add(entity);
        this.plan(level).executeAndAwait();
        assertEquals(1, effects.get());
        verify(entity, times(1)).tick();
        assertEquals(0L, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void shutdownDuringAChunkLoadSkipsCallbackAndWaitsForTicketCleanup() throws Exception {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        final Entity source = this.entity(level, new BlockPos(1, 64, 1));
        final Entity owner = this.entity(level, new BlockPos(49, 64, 1));
        final var handle = new AtomicReference<EntityInteractionTask>();
        final AtomicInteger ran = new AtomicInteger();
        final AtomicInteger tickets = new AtomicInteger();
        final var chunkSource = level.getChunkSource();
        doAnswer(call -> {
            tickets.incrementAndGet();
            return null;
        }).when(chunkSource).addRegionTicket(any(), any(), anyInt(), any());
        doAnswer(call -> {
            tickets.decrementAndGet();
            return null;
        }).when(chunkSource).removeRegionTicket(any(), any(), anyInt(), any());
        final var loading = new java.util.concurrent.CountDownLatch(1);
        final var shutdownFailure = new AtomicReference<Throwable>();
        final Field stopped = RegionThreadingEngine.class.getDeclaredField("shutdown");
        stopped.setAccessible(true);
        final Thread shutdown = new Thread(() -> {
            try {
                assertTrue(loading.await(5, java.util.concurrent.TimeUnit.SECONDS));
                this.engine.shutdown();
                assertEquals(0, tickets.get(), "shutdown must await the active load's ticket cleanup");
            } catch (Throwable failure) {
                shutdownFailure.set(failure);
            }
        }, "chunk-owner-shutdown");
        doAnswer(call -> {
            assertEquals(EntityInteractionTask.State.RUNNING, handle.get().state());
            loading.countDown();
            final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!stopped.getBoolean(this.engine) && System.nanoTime() < deadline) LockSupport.parkNanos(100_000L);
            assertTrue(stopped.getBoolean(this.engine), "shutdown must publish retirement while this load is active");
            assertFalse(handle.get().cancel(), "loading work has already started");
            return mock(net.minecraft.world.level.chunk.LevelChunk.class);
        }).when(level).getChunk(100, 100);
        doAnswer(call -> {
            handle.set(this.engine.entityInteractions().submitToOwnerAfterChunkLoad(source, owner, new BlockPos(1600, 64, 1600),
                    (current, chunk) -> ran.incrementAndGet()));
            return null;
        }).when(source).tick();
        this.tickList.add(source);
        shutdown.start();
        try {
            this.plan(level).executeAndAwait();
        } finally {
            loading.countDown();
            shutdown.join(10_000L);
        }
        assertFalse(shutdown.isAlive());
        assertEquals(null, shutdownFailure.get());
        assertEquals(EntityInteractionTask.State.SKIPPED, handle.get().state());
        assertEquals(EntityInteractionTask.Reason.SHUTDOWN, handle.get().reason());
        assertEquals(0, ran.get());
        assertEquals(0, tickets.get());
    }

    @Test
    void foxPickupOfNonTickingLocalItemRunsWholeVirtualBodyAtBarrierAndConservesStacks() throws Throwable {
        this.mobPickupCase("local");
    }

    @Test
    void foxPickupOfForeignCandidateConservesRemainderAndOldHeldItem() throws Throwable {
        this.mobPickupCase("foreign");
    }

    @Test
    void competingFoxesCannotConsumeOneCandidateTwice() throws Throwable {
        this.mobPickupCase("competing");
    }

    @Test
    void delayedItemIsRecheckedBeforeAnyPickupMutation() throws Throwable {
        this.mobPickupCase("delay");
    }

    @Test
    void retiredPickupCandidateIsSkippedWithoutChangingEquipment() throws Throwable {
        this.mobPickupCase("removed");
    }

    @Test
    void pickupCandidateChangingLevelsIsSkipped() throws Throwable {
        this.mobPickupCase("changed-level");
    }

    @Test
    void deadPickerCannotExecuteAnAcceptedPickup() throws Throwable {
        this.mobPickupCase("picker-dead");
    }

    @Test
    void mobGriefingEventDenialPreventsPickupAdmission() throws Throwable {
        this.mobPickupCase("denied");
    }

    @Test
    void pickupOverrideFailureIsNotReplayedAndLaterCandidatesDrain() throws Throwable {
        this.mobPickupCase("failure");
    }

    @Test
    void serverThreadPickupRetainsInlineVirtualCallAndEventCount() throws Throwable {
        this.mobPickupCase("inline");
    }

    private void mobPickupCase(String mode) throws Throwable {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        final var rules = new net.minecraft.world.level.GameRules();
        when(level.getServer().isSpawningAnimals()).thenReturn(true);
        doReturn(rules).when(level).getGameRules();
        final Field worldRandom = Level.class.getDeclaredField("random");
        worldRandom.setAccessible(true);
        worldRandom.set(level, net.minecraft.util.RandomSource.create(123L));
        final var item = this.entity(net.minecraft.world.entity.item.ItemEntity.class, level,
                new AtomicReference<>(new BlockPos(mode.equals("foreign") ? 49 : 2, 64, 1)));
        final var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5);
        when(item.getItem()).thenReturn(stack);
        when(item.isAddedToLevel()).thenReturn(true);
        final var removed = new java.util.concurrent.atomic.AtomicBoolean();
        when(item.isRemoved()).thenAnswer(call -> removed.get());
        final var itemLevel = new AtomicReference<Level>(level);
        when(item.level()).thenAnswer(call -> itemLevel.get());
        final var delay = new java.util.concurrent.atomic.AtomicBoolean();
        when(item.hasPickUpDelay()).thenAnswer(call -> delay.get());
        doCallRealMethod().when(item).discard();
        doAnswer(call -> {
            removed.set(true);
            return null;
        }).when(item).remove(any());
        final List<net.minecraft.world.entity.item.ItemEntity> spawned = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            assertEquals(main, Thread.currentThread());
            spawned.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        doReturn(List.of(item)).when(level).getEntitiesOfClass(org.mockito.ArgumentMatchers.eq(net.minecraft.world.entity.item.ItemEntity.class), any());
        final AtomicInteger griefEvents = new AtomicInteger();
        final java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityMobGriefingEvent> listener = event -> {
            griefEvents.incrementAndGet();
            if (mode.equals("denied")) event.setCanGrief(false);
        };
        final List<net.minecraft.world.item.ItemStack> hands = new CopyOnWriteArrayList<>();
        final AtomicInteger pickups = new AtomicInteger();
        final AtomicInteger predicates = new AtomicInteger();
        final AtomicInteger tail = new AtomicInteger();
        final RuntimeException injected = new RuntimeException("mod pickup override failed");
        final int pickers = mode.equals("competing") ? 2 : 1;
        final Method pickup = net.minecraft.world.entity.animal.Fox.class.getDeclaredMethod("pickUpItem", net.minecraft.world.entity.item.ItemEntity.class);
        pickup.setAccessible(true);
        final Method reach = Mob.class.getDeclaredMethod("getPickupReach");
        reach.setAccessible(true);
        for (int i = 0; i < pickers; i++) {
            final var fox = this.entity(net.minecraft.world.entity.animal.Fox.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
            final var held = new AtomicReference<>(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 2));
            final var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
            when(fox.isAlive()).thenAnswer(call -> alive.get());
            doCallRealMethod().when(fox).aiStep();
            when(fox.canPickUpLoot()).thenReturn(true);
            when(fox.isEffectiveAi()).thenReturn(true);
            when(fox.isDeadOrDying()).thenReturn(true); // Skip unrelated freezing fields in this mock body.
            when(fox.getDeltaMovement()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
            when(fox.getLookAngle()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
            when(fox.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0, 63, 0, 3, 65, 3));
            doReturn(new net.minecraft.core.Vec3i(1, 0, 1)).when(fox);
            reach.invoke(fox);
            when(fox.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND)).thenAnswer(call -> held.get());
            when(fox.canHoldItem(any())).thenReturn(true);
            when(fox.wantsToPickUp(any())).thenAnswer(call -> {
                assertEquals(main, Thread.currentThread(), "mutable eligibility belongs to the completion operation");
                predicates.incrementAndGet();
                return true;
            });
            doAnswer(call -> {
                assertEquals(main, Thread.currentThread());
                held.set(call.getArgument(1));
                hands.add(held.get());
                return null;
            }).when(fox).setItemSlot(org.mockito.ArgumentMatchers.eq(net.minecraft.world.entity.EquipmentSlot.MAINHAND), any());
            doCallRealMethod().when(fox);
            pickup.invoke(fox, item);
            if (mode.equals("failure")) {
                doAnswer(call -> {
                    pickups.incrementAndGet();
                    throw injected;
                }).when(fox);
                pickup.invoke(fox, item);
            } else {
                doAnswer(call -> {
                    pickups.incrementAndGet();
                    return call.callRealMethod();
                }).when(fox);
                pickup.invoke(fox, item);
            }
            doAnswer(call -> {
                fox.aiStep();
                if (!mode.equals("inline")) {
                    assertEquals(5, stack.getCount(), "no stack splitting before worker completion");
                    assertEquals(0, spawned.size());
                    assertEquals(0, predicates.get());
                    assertEquals(0, pickups.get());
                }
                if (mode.equals("delay")) delay.set(true);
                if (mode.equals("removed")) removed.set(true);
                if (mode.equals("changed-level")) itemLevel.set(mock(ServerLevel.class));
                if (mode.equals("picker-dead")) alive.set(false);
                this.engine.entityInteractions().submitToOwner(fox, fox, current -> tail.incrementAndGet());
                return null;
            }).when(fox).tick();
            this.tickList.add(fox);
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.EntityMobGriefingEvent.class, listener);
        try {
            if (mode.equals("inline")) {
                this.engine.serverThreadOnlyEntity(net.minecraft.world.entity.animal.Fox.class, "test_inline_pickup");
            }
            if (mode.equals("failure")) assertThrows(net.minecraft.ReportedException.class, () -> this.plan(level).executeAndAwait());
            else this.plan(level).executeAndAwait();
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
        }
        assertEquals(pickers, griefEvents.get(), "one source grief decision, no repeated event at completion");
        final boolean success = List.of("local", "foreign", "competing", "inline").contains(mode);
        assertEquals(success || mode.equals("failure") ? 1 : 0, pickups.get());
        if (success) {
            assertTrue(removed.get());
            assertEquals(1, hands.size());
            assertEquals(1, hands.getFirst().getCount());
            assertEquals(2, spawned.size());
            assertEquals(4, spawned.getFirst().getItem().getCount());
            assertEquals(net.minecraft.world.item.Items.DIAMOND, spawned.getFirst().getItem().getItem());
            assertEquals(2, spawned.getLast().getItem().getCount());
            assertEquals(net.minecraft.world.item.Items.EMERALD, spawned.getLast().getItem().getItem());
            assertEquals(5, hands.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum()
                    + spawned.stream().filter(e -> e.getItem().is(net.minecraft.world.item.Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum());
        } else {
            assertEquals(5, stack.getCount());
            assertEquals(0, spawned.size());
            assertEquals(0, hands.size());
        }
        assertEquals(pickers, tail.get(), "independent requests drain even after a pickup override failure");
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void ordinaryMobPartialEquipmentPickupKeepsUnconsumedItemsAndVirtualOverride() throws Exception {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        doReturn(new net.minecraft.world.level.GameRules()).when(level).getGameRules();
        final Mob mob = this.entity(Mob.class, level, new AtomicReference<>(new BlockPos(1, 64, 1)));
        final var item = this.entity(net.minecraft.world.entity.item.ItemEntity.class, level, new AtomicReference<>(new BlockPos(2, 64, 1)));
        final var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5);
        when(item.getItem()).thenReturn(stack);
        when(item.isAddedToLevel()).thenReturn(true);
        when(mob.isAlive()).thenReturn(true);
        when(mob.canPickUpLoot()).thenReturn(true);
        when(mob.isEffectiveAi()).thenReturn(true);
        when(mob.isDeadOrDying()).thenReturn(true);
        when(mob.getDeltaMovement()).thenReturn(net.minecraft.world.phys.Vec3.ZERO);
        when(mob.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(0, 63, 0, 3, 65, 3));
        when(mob.wantsToPickUp(any())).thenReturn(true);
        final Method reach = Mob.class.getDeclaredMethod("getPickupReach");
        reach.setAccessible(true);
        doReturn(new net.minecraft.core.Vec3i(1, 0, 1)).when(mob);
        reach.invoke(mob);
        final AtomicReference<net.minecraft.world.item.ItemStack> equipped = new AtomicReference<>();
        when(mob.equipItemIfPossible(any())).thenAnswer(call -> {
            assertEquals(main, Thread.currentThread());
            final net.minecraft.world.item.ItemStack offered = call.getArgument(0);
            equipped.set(offered.copyWithCount(2));
            return equipped.get();
        });
        final Method pickup = Mob.class.getDeclaredMethod("pickUpItem", net.minecraft.world.entity.item.ItemEntity.class);
        pickup.setAccessible(true);
        doCallRealMethod().when(mob);
        pickup.invoke(mob, item);
        doCallRealMethod().when(mob).aiStep();
        doReturn(List.of(item)).when(level).getEntitiesOfClass(org.mockito.ArgumentMatchers.eq(net.minecraft.world.entity.item.ItemEntity.class), any());
        doAnswer(call -> {
            mob.aiStep();
            assertEquals(5, stack.getCount());
            assertEquals(null, equipped.get());
            return null;
        }).when(mob).tick();
        this.tickList.add(mob);
        this.plan(level).executeAndAwait();
        assertEquals(2, equipped.get().getCount());
        assertEquals(3, stack.getCount());
        verify(item, never()).discard();
        verify(mob, times(1)).onItemPickup(item);
        verify(mob, times(1)).take(item, 2);
    }

    @Test
    void endermanGazeRetainsTrueSightThroughModOwnedLoadedChunk() throws Exception {
        this.endermanSightCase(false);
    }

    @Test
    void endermanGazeRetainsObstructionInModOwnedLoadedChunk() throws Exception {
        this.endermanSightCase(true);
    }

    private void endermanSightCase(boolean blocked) throws Exception {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Thread main = Thread.currentThread();
        when(level.getServer().isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        final var enderman = this.entity(net.minecraft.world.entity.monster.EnderMan.class, level,
                new AtomicReference<>(new BlockPos(-2734, 28, -3391)));
        when(enderman.getX()).thenReturn(-2733.28);
        when(enderman.getZ()).thenReturn(-3390.72);
        when(enderman.getEyeY()).thenReturn(30.5);
        final Player player = mock(Player.class);
        when(player.level()).thenReturn(level);
        when(player.getX()).thenReturn(-2685.61);
        when(player.getZ()).thenReturn(-3335.82);
        when(player.getEyeY()).thenReturn(66.5);
        final var inventory = new net.minecraft.world.entity.player.Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getViewVector(1F)).thenReturn(new net.minecraft.world.phys.Vec3(-47.67, -36.0, -54.9).normalize());
        doCallRealMethod().when(player).hasLineOfSight(enderman);
        final Method gaze = net.minecraft.world.entity.monster.EnderMan.class.getDeclaredMethod("isLookingAtMe", Player.class);
        gaze.setAccessible(true);
        doCallRealMethod().when(enderman);
        gaze.invoke(enderman, player);
        final var chunks = level.getChunkSource();
        final var chunkMap = mock(net.minecraft.server.level.ChunkMap.class);
        for (String name : List.of("level", "chunkMap", "mainThread")) {
            final Field field = ServerChunkCache.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(chunks, name.equals("level") ? level : name.equals("chunkMap") ? chunkMap : main);
        }
        final var plotChunk = mock(net.minecraft.world.level.chunk.LevelChunk.class);
        final var state = blocked ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        when(plotChunk.getBlockState(any())).thenReturn(state);
        final AtomicInteger providers = new AtomicInteger();
        doAnswer(call -> {
            assertEquals(main, Thread.currentThread());
            providers.incrementAndGet();
            return plotChunk;
        }).when(chunks).getChunkNow(1280065, 1280960);
        doCallRealMethod().when(chunks).getChunk(1280065, 1280960, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        // Model Sable's transformed plot read at the clip boundary. Both the original LivingEntity LOS and
        // EnderMan gaze body run for real; the mod's full transform/pose implementation is not simulated.
        doAnswer(call -> {
            final net.minecraft.world.level.ClipContext context = call.getArgument(0);
            final var loaded = chunks.getChunk(1280065, 1280960, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            final var read = loaded.getBlockState(new BlockPos(20481040, 30, 20495360));
            return read.isAir()
                    ? net.minecraft.world.phys.BlockHitResult.miss(context.getTo(), net.minecraft.core.Direction.UP, BlockPos.containing(context.getTo()))
                    : new net.minecraft.world.phys.BlockHitResult(context.getTo(), net.minecraft.core.Direction.UP, BlockPos.containing(context.getTo()), false);
        }).when(level).clip(any());
        final List<Boolean> sight = new CopyOnWriteArrayList<>();
        final long refusalsBefore = this.engine.diagnostics().chunkGuardRefusals();
        doAnswer(call -> {
            sight.add((boolean) gaze.invoke(enderman, player));
            return null;
        }).when(enderman).tick();
        this.tickList.add(enderman);
        this.plan(level).executeAndAwait();
        assertEquals(List.of(!blocked), sight);
        assertEquals(1, providers.get());
        verify(enderman, times(1)).tick();
        assertEquals(refusalsBefore, this.engine.diagnostics().chunkGuardRefusals());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
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
        // This level fixture exposes only the engine tick index, not a real section manager/identity ledger.
        doReturn(null).when(level).entityIdentityLedger();
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
        final Thread serverThread = Thread.currentThread();
        when(server.isSameThread()).thenAnswer(call -> Thread.currentThread() == serverThread);
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
