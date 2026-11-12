/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.neoforged.forgia.compat.mobspawncontroller.RuleSnapshots;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** All updates and lookups below invoke transformed upstream methods, not duplicate rule implementations. */
class RuleSnapshotsIntegrationTest {
    private static final ResourceLocation MOB = ResourceLocation.parse("minecraft:zombie");
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerLevel level = mock(ServerLevel.class);
    private final Thread main = Thread.currentThread();

    private static Object call(String name, Class<?> result, Class<?>[] parameters, Object... args) throws Throwable {
        final Class<?> manager = Class.forName("com.mobspawncontroller.command.MobSpawnManager");
        return MethodHandles.publicLookup().findStatic(manager, name, MethodType.methodType(result, parameters)).invokeWithArguments(args);
    }

    @BeforeEach
    void setup() throws Throwable {
        when(this.server.isSameThread()).thenAnswer(call -> Thread.currentThread() == this.main);
        when(this.level.getServer()).thenReturn(this.server);
        when(this.level.dimension()).thenReturn(Level.OVERWORLD);
        when(this.level.getGameTime()).thenReturn(12L);
        call("clearAll", void.class, new Class<?>[0]);
    }

    @AfterEach
    void close() throws Throwable {
        ParallelAdapterRuntime.close(this.server);
        call("clearAll", void.class, new Class<?>[0]);
    }

    private void register() {
        ParallelAdapterRuntime.initialize(this.server, registrar -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent(this.server, registrar)));
    }

    @Test
    void mixinsVerifyAndOnlyFrozenRegisteredClaimsAreVisible() {
        assertTrue(RuleSnapshots.verify());
        assertTrue(ParallelAdapterRuntime.naturalSpawnClaims(this.server).isEmpty());
        this.register();
        assertEquals(java.util.Set.of("mobspawncontroller"), ParallelAdapterRuntime.naturalSpawnClaims(this.server));
        ParallelAdapterRuntime.close(this.server);
        assertTrue(ParallelAdapterRuntime.naturalSpawnClaims(this.server).isEmpty());
    }

    @Test
    void defaultRegistrationRetainsTheModsSerialSpawnRestriction() {
        final String previous = System.getProperty("forgia.mscOrderedSpawning");
        System.clearProperty("forgia.mscOrderedSpawning");
        try {
            this.register();
            assertTrue(ParallelAdapterRuntime.naturalSpawnClaims(this.server).isEmpty());
            assertEquals(1, ParallelApi.forServer(this.server).orElseThrow().adapters().size());
            final var plan = net.neoforged.neoforge.server.threading.RegionThreadingConflicts.plan(true, true, true,
                    "mobspawncontroller"::equals, false,
                    net.neoforged.neoforge.server.threading.RegionThreadingConflicts.withNaturalSpawnAdapters(
                            ParallelAdapterRuntime.naturalSpawnClaims(this.server)));
            assertFalse(plan.spawnWorkers(), "registration alone must preserve DISABLE_SPAWNING");
        } finally {
            if (previous == null) System.clearProperty("forgia.mscOrderedSpawning");
            else System.setProperty("forgia.mscOrderedSpawning", previous);
        }
    }

    @Test
    void liveRuleChangesDoNotChangeAnAdmittedUnitsDecision() throws Throwable {
        call("setAllowed", void.class, new Class<?>[] { ResourceLocation.class, MobSpawnType.class, boolean.class }, MOB, MobSpawnType.NATURAL, false);
        this.register();
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        final long revision = RuleSnapshots.capture().revision();
        call("setAllowed", void.class, new Class<?>[] { ResourceLocation.class, MobSpawnType.class, boolean.class }, MOB, MobSpawnType.NATURAL, true);
        assertTrue(RuleSnapshots.capture().revision() > revision);
        final var answer = new AtomicReference<Object>();
        assertTrue(work.run(this.level, () -> {
            try {
                answer.set(call("getAllowed", Boolean.class, new Class<?>[] { ResourceLocation.class, MobSpawnType.class }, MOB, MobSpawnType.NATURAL));
                assertEquals(revision, ParallelApi.prepared(RuleSnapshots.KEY).orElseThrow().revision());
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        }));
        assertEquals(false, answer.get());
        assertEquals(true, call("getAllowed", Boolean.class, new Class<?>[] { ResourceLocation.class, MobSpawnType.class }, MOB, MobSpawnType.NATURAL));
        assertTrue(ParallelApi.prepared(RuleSnapshots.KEY).isEmpty());
    }

    @Test
    void nestedMapsAreDetachedImmutableAndCaptureIsCachedUntilMutation() throws Throwable {
        final ResourceLocation attribute = ResourceLocation.parse("minecraft:generic.max_health");
        call("setAttributeOverrides", void.class, new Class<?>[] { ResourceLocation.class, Map.class }, MOB, Map.of(attribute, 42.0));
        final var snapshot = RuleSnapshots.capture();
        assertSame(snapshot, RuleSnapshots.capture());
        assertEquals(42.0, snapshot.attributes().get(MOB).get(attribute));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.attributes().get(MOB).put(attribute, 1.0));
        call("setAttributeOverrides", void.class, new Class<?>[] { ResourceLocation.class, Map.class }, MOB, Map.of(attribute, 84.0));
        assertEquals(42.0, snapshot.attributes().get(MOB).get(attribute));
        assertEquals(84.0, RuleSnapshots.capture().attributes().get(MOB).get(attribute));
    }

    @Test
    void actualNearbyRuleSelectsOrderedFinalizationBeforeTheBody() throws Throwable {
        final Class<?> settings = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettings");
        final Class<?> codec = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettingsJsonCodec");
        final var json = new com.google.gson.JsonObject();
        json.addProperty("max_nearby", 1);
        json.addProperty("nearby_radius", 64);
        final Object decoded = MethodHandles.publicLookup().findStatic(codec, "decode",
                MethodType.methodType(settings, com.google.gson.JsonObject.class)).invoke(json);
        call("setNaturalSpawnSettings", void.class, new Class<?>[] { ResourceLocation.class, settings }, MOB, decoded);
        this.register();
        final var plan = ParallelAdapterRuntime.preparePlan(this.level);
        assertTrue(plan.workerReady());
        assertTrue(plan.orderedFinalization());
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0, plan);
        assertTrue(work.workerReady());
        assertTrue(RuleSnapshots.capture().requiresWorldRules());
        assertEquals(0, ParallelApi.forServer(this.server).orElseThrow().adapters().getFirst().serialPlans());
        assertTrue(ParallelApi.prepared(RuleSnapshots.KEY).isEmpty());
        assertTrue(work.run(this.level, () -> assertTrue(RuleSnapshots.current().orElseThrow().supportsOrderedFinalization())));
    }

    @Test
    void secondarySpawnOnlyNearbyRulesAlsoSelectOrderedFinalization() throws Throwable {
        final Class<?> settings = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettings");
        final Class<?> codec = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettingsJsonCodec");
        final var json = new com.google.gson.JsonObject();
        json.addProperty("max_nearby", 1);
        final var types = new com.google.gson.JsonArray();
        types.add("jockey");
        json.add("spawn_types", types);
        final Object decoded = MethodHandles.publicLookup().findStatic(codec, "decode",
                MethodType.methodType(settings, com.google.gson.JsonObject.class)).invoke(json);
        assertFalse((boolean) MethodHandles.publicLookup().findVirtual(settings, "appliesTo",
                MethodType.methodType(boolean.class, MobSpawnType.class)).invoke(decoded, MobSpawnType.NATURAL));
        call("setNaturalSpawnSettings", void.class, new Class<?>[] { ResourceLocation.class, settings }, MOB, decoded);
        this.register();
        final var plan = ParallelAdapterRuntime.preparePlan(this.level);
        assertTrue(plan.workerReady());
        assertTrue(plan.orderedFinalization(), "secondary finalizers must run inside the same native server transaction");
    }

    @Test
    void unsupportedWorldConditionsKeepTheWholeNativeSerialPlan() throws Throwable {
        this.register();
        final Class<?> settings = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettings");
        final Class<?> codec = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettingsJsonCodec");
        final var decode = MethodHandles.publicLookup().findStatic(codec, "decode", MethodType.methodType(settings, com.google.gson.JsonObject.class));
        for (final String condition : java.util.List.of("min_height", "structures")) {
            call("clearAll", void.class, new Class<?>[0]);
            final var json = new com.google.gson.JsonObject();
            json.addProperty("max_nearby", 1);
            if (condition.equals("structures")) {
                final var selectors = new com.google.gson.JsonArray();
                selectors.add("minecraft:fortress");
                json.add(condition, selectors);
            } else json.addProperty(condition, 0);
            call("setNaturalSpawnSettings", void.class, new Class<?>[] { ResourceLocation.class, settings }, MOB, decode.invoke(json));
            assertFalse(ParallelAdapterRuntime.preparePlan(this.level).workerReady(), condition);
        }
        assertEquals(2, ParallelApi.forServer(this.server).orElseThrow().adapters().getFirst().serialPlans());
    }

    @Test
    void orderedNearbyChecksUseFrozenRulesAndTheConfirmedJoinResultsAcrossRegions() throws Throwable {
        this.orderedNearbyCandidates(true);
    }

    @Test
    void orderedNearbyChecksDoNotOverspawnWhenBothCandidatesWouldTakeTheLastSlot() throws Throwable {
        this.orderedNearbyCandidates(false);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void orderedNearbyCandidates(boolean cancelFirstJoin) throws Throwable {
        final Class<?> settingsType = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettings");
        final Class<?> codec = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettingsJsonCodec");
        final var decode = MethodHandles.publicLookup().findStatic(codec, "decode", MethodType.methodType(settingsType, com.google.gson.JsonObject.class));
        final var json = new com.google.gson.JsonObject();
        json.addProperty("max_nearby", 1);
        json.addProperty("nearby_radius", 64);
        call("setNaturalSpawnSettings", void.class, new Class<?>[] { ResourceLocation.class, settingsType }, MOB, decode.invoke(json));
        this.register();
        final var engine = new net.neoforged.neoforge.server.threading.region.RegionThreadingEngine(2, false, false, false, false, false, false, true,
                net.neoforged.neoforge.server.threading.region.RegionLayout.of(net.neoforged.neoforge.server.threading.region.RegionPolicy.FIXED_3X3),
                net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode.REGION_OWNED);
        net.neoforged.neoforge.server.threading.RegionThreadingManager.install(engine);
        final java.util.List<net.minecraft.world.entity.Mob> admitted = new java.util.ArrayList<>();
        final var joins = new java.util.concurrent.atomic.AtomicInteger();
        final var finalizers = new java.util.concurrent.atomic.AtomicInteger();
        final var afterSpawns = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.Map<Integer, net.minecraft.util.RandomSource> randoms = new java.util.concurrent.ConcurrentHashMap<>();
        final var ready = new java.util.concurrent.CountDownLatch(2);
        final Class<?> boost = Class.forName("com.mobspawncontroller.natural.NaturalSpawnBoost");
        final var activeContext = MethodHandles.privateLookupIn(boost, MethodHandles.lookup()).findStaticGetter(boost, "ACTIVE_LEVEL", ThreadLocal.class);
        final var chunks = mock(net.minecraft.server.level.ServerChunkCache.class);
        when(chunks.hasChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        when(this.level.getChunkSource()).thenReturn(chunks);
        when(this.level.entitySectionRegionChunkSize()).thenReturn(3);
        when(this.level.getProfiler()).thenReturn(net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
        when(this.level.getAllEntities()).thenReturn(java.util.List.of());
        when(this.level.getLevel()).thenReturn(this.level);
        when(this.level.getSharedSpawnPos()).thenReturn(net.minecraft.core.BlockPos.ZERO);
        when(this.level.players()).thenReturn(java.util.List.of());
        when(this.level.getCurrentDifficultyAt(org.mockito.ArgumentMatchers.any())).thenReturn(mock(net.minecraft.world.DifficultyInstance.class));
        final net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = mock(net.minecraft.core.Holder.class);
        when(biome.unwrapKey()).thenReturn(java.util.Optional.empty());
        when(this.level.getBiome(org.mockito.ArgumentMatchers.any())).thenReturn(biome);
        when(this.level.getRandom()).thenAnswer(invocation -> {
            assertSame(this.main, Thread.currentThread());
            assertSame(this.level, ((ThreadLocal<?>) activeContext.invoke()).get(), "upstream context is carried to the main continuation");
            final int x = ParallelApi.currentSpawnUnit().orElseThrow().chunkX();
            assertSame(randoms.get(x), engine.currentGroupRandom(), "server continuation keeps the unit's random stream");
            return engine.currentGroupRandom();
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            assertSame(this.main, Thread.currentThread(), "nearby query must use the full server view");
            final net.minecraft.world.phys.AABB area = invocation.getArgument(1);
            final java.util.function.Predicate<net.minecraft.world.entity.LivingEntity> filter = invocation.getArgument(2);
            return admitted.stream().filter(mob -> mob.getBoundingBox().intersects(area) && filter.test(mob)).toList();
        }).when(this.level).getEntitiesOfClass(org.mockito.ArgumentMatchers.eq(net.minecraft.world.entity.LivingEntity.class),
                org.mockito.ArgumentMatchers.any(net.minecraft.world.phys.AABB.class), org.mockito.ArgumentMatchers.any(java.util.function.Predicate.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            assertSame(this.main, Thread.currentThread());
            final net.minecraft.world.entity.Mob mob = invocation.getArgument(0);
            if (!mob.isSpawnCancelled() && !net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.entity.EntityJoinLevelEvent(mob, this.level)).isCanceled())
                admitted.add(mob);
            return null;
        }).when(this.level).addFreshEntityWithPassengers(org.mockito.ArgumentMatchers.any());
        final java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> joinListener = event -> {
            if (event.getLevel() == this.level && joins.incrementAndGet() == 1 && cancelFirstJoin) event.setCanceled(true);
        };
        final java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent> finalizeListener = event -> {
            if (event.getLevel() == this.level) {
                assertSame(this.main, Thread.currentThread());
                assertEquals(ParallelApi.Phase.SERVER_SERVICE, ParallelApi.forServer(this.server).orElseThrow().currentPhase());
                finalizers.incrementAndGet();
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent.class, joinListener);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent.class, finalizeListener);
        try {
            final java.util.List<net.minecraft.world.level.chunk.LevelChunk> work = new java.util.ArrayList<>();
            for (final int chunkX : new int[] { 2, 3 }) {
                final var chunk = mock(net.minecraft.world.level.chunk.LevelChunk.class);
                final var pos = new net.minecraft.world.level.ChunkPos(chunkX, 1);
                final var candidate = mock(net.minecraft.world.entity.Mob.class);
                final var position = new net.minecraft.core.BlockPos(chunkX == 2 ? 47 : 48, 64, 16);
                org.mockito.Mockito.doReturn(net.minecraft.world.entity.EntityType.ZOMBIE).when(candidate).getType();
                when(candidate.blockPosition()).thenReturn(position);
                when(candidate.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(position));
                when(candidate.level()).thenReturn(this.level);
                org.mockito.Mockito.doCallRealMethod().when(candidate).setSpawnCancelled(org.mockito.ArgumentMatchers.anyBoolean());
                org.mockito.Mockito.doCallRealMethod().when(candidate).isSpawnCancelled();
                final var fired = new java.util.concurrent.atomic.AtomicBoolean();
                when(chunk.getPos()).thenAnswer(invocation -> {
                    if (Thread.currentThread() != this.main && fired.compareAndSet(false, true)) {
                        assertEquals(ParallelApi.Phase.REGION_WORKER, ParallelApi.forServer(this.server).orElseThrow().currentPhase());
                        randoms.put(chunkX, engine.currentGroupRandom());
                        ready.countDown();
                        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
                        final var result = net.neoforged.neoforge.server.threading.RegionThreadingHooks.orderedSpawnFinalization(
                                this.level, candidate, null, chunk, (mob, sourceChunk) -> {
                                    assertSame(this.main, Thread.currentThread());
                                    afterSpawns.incrementAndGet();
                                }, 1, 1);
                        org.junit.jupiter.api.Assertions.assertNotNull(result);
                    }
                    return pos;
                });
                work.add(chunk);
            }
            final var source = new net.neoforged.neoforge.server.threading.region.RegionSpawnSource(
                    (key, consumer) -> {}, key -> java.util.List.of(), key -> false, () -> 0);
            final net.neoforged.neoforge.server.threading.region.RegionTickBarrier barrier = condition -> {
                while (!condition.getAsBoolean()) java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
            };
            final var planRef = new AtomicReference<net.neoforged.neoforge.server.threading.region.RegionSpawnPlan>();
            engine.runInGlobalRegion(() -> {
                planRef.set(engine.beginSpawnPlan(this.level, source, true, true, false, barrier));
                for (final var chunk : work) planRef.get().accept(chunk, true);
            });
            assertTrue(planRef.get().orderedFinalization());
            json.addProperty("max_nearby", 0); // The admitted plan must keep revision 1, including on the server.
            call("setNaturalSpawnSettings", void.class, new Class<?>[] { ResourceLocation.class, settingsType }, MOB, decode.invoke(json));
            planRef.get().executeAndAwait(barrier);
            assertEquals(1, admitted.size());
            assertEquals(cancelFirstJoin ? 2 : 1, joins.get());
            assertEquals(2, finalizers.get(), "one original finalize event per candidate");
            assertEquals(2, afterSpawns.get(), "preserve native afterSpawn even for a denied admission");
            assertEquals(2, engine.diagnostics().spawnChunksOnWorkers());
            assertEquals(2, engine.orderedSpawnTransactions());
            assertEquals(null, ((ThreadLocal<?>) activeContext.invoke()).get());
            assertTrue(RuleSnapshots.current().isEmpty());
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(joinListener);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(finalizeListener);
            net.neoforged.neoforge.server.threading.RegionThreadingManager.clear();
        }
    }

    @Test
    void preparedBoostIgnoresLaterLiveMultiplierUpdates() throws Throwable {
        this.register();
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        final Class<?> settings = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettings");
        final Class<?> codec = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettingsJsonCodec");
        final var json = new com.google.gson.JsonObject();
        json.addProperty("spawn_multiplier", 4.0);
        final Object decoded = MethodHandles.publicLookup().findStatic(codec, "decode",
                MethodType.methodType(settings, com.google.gson.JsonObject.class)).invoke(json);
        call("setNaturalSpawnSettings", void.class, new Class<?>[] { ResourceLocation.class, settings }, MOB, decoded);
        final Class<?> boost = Class.forName("com.mobspawncontroller.natural.NaturalSpawnBoost");
        final var scale = MethodHandles.publicLookup().findStatic(boost, "scaleCategoryCap",
                MethodType.methodType(int.class, net.minecraft.world.entity.MobCategory.class, int.class));
        assertTrue(work.run(this.level, () -> {
            try {
                assertEquals(70, (int) scale.invoke(net.minecraft.world.entity.MobCategory.MONSTER, 70));
                assertTrue(((Map<?, ?>) call("getAllNaturalSpawnSettings", Map.class, new Class<?>[0])).isEmpty());
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        }));
        assertFalse(ParallelAdapterRuntime.prepare(this.level, 0, 0).workerReady(), "new revision must fall back");
    }

    @Test
    void failingRealSpawnBodyClearsUpstreamBoostThreadLocal() throws Throwable {
        final Class<?> boost = Class.forName("com.mobspawncontroller.natural.NaturalSpawnBoost");
        final var active = MethodHandles.privateLookupIn(boost, MethodHandles.lookup())
                .findStaticGetter(boost, "ACTIVE_LEVEL", ThreadLocal.class);
        final IllegalStateException failure = new IllegalStateException("spawn body failure");
        when(this.level.getProfiler()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> net.minecraft.world.level.NaturalSpawner.spawnForChunk(this.level, null, null, true, true, false)));
        assertEquals(null, ((ThreadLocal<?>) active.invoke()).get(), "RETURN-only cleanup misses this failure");
    }

    @Test
    void frozenRuleIsReadOnAnActualSpawnWorkerAfterALiveUpdate() throws Throwable {
        call("setAllowed", void.class, new Class<?>[] { ResourceLocation.class, MobSpawnType.class, boolean.class }, MOB, MobSpawnType.NATURAL, false);
        this.register();
        final var engine = new net.neoforged.neoforge.server.threading.region.RegionThreadingEngine(2, false, false, false, false, false, false, true,
                net.neoforged.neoforge.server.threading.region.RegionLayout.of(net.neoforged.neoforge.server.threading.region.RegionPolicy.FIXED_3X3),
                net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode.REGION_OWNED);
        net.neoforged.neoforge.server.threading.RegionThreadingManager.install(engine);
        try {
            final var chunks = mock(net.minecraft.server.level.ServerChunkCache.class);
            when(chunks.hasChunk(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
            when(this.level.getChunkSource()).thenReturn(chunks);
            when(this.level.entitySectionRegionChunkSize()).thenReturn(3);
            when(this.level.getProfiler()).thenReturn(net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
            when(this.level.getAllEntities()).thenReturn(java.util.List.of());
            final var chunk = mock(net.minecraft.world.level.chunk.LevelChunk.class);
            final var pos = new net.minecraft.world.level.ChunkPos(1, 1);
            final var observed = new java.util.concurrent.atomic.AtomicBoolean();
            when(chunk.getPos()).thenAnswer(invocation -> {
                if (Thread.currentThread() != this.main) {
                    observed.set(true);
                    assertEquals(ParallelApi.Phase.REGION_WORKER, ParallelApi.forServer(this.server).orElseThrow().currentPhase());
                    assertEquals(false, call("getAllowed", Boolean.class, new Class<?>[] { ResourceLocation.class, MobSpawnType.class }, MOB, MobSpawnType.NATURAL));
                }
                return pos;
            });
            final var source = new net.neoforged.neoforge.server.threading.region.RegionSpawnSource(
                    (key, consumer) -> {}, key -> java.util.List.of(), key -> false, () -> 0);
            final net.neoforged.neoforge.server.threading.region.RegionTickBarrier barrier = condition -> {
                while (!condition.getAsBoolean()) java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
            };
            final var planRef = new AtomicReference<net.neoforged.neoforge.server.threading.region.RegionSpawnPlan>();
            engine.runInGlobalRegion(() -> planRef.set(engine.beginSpawnPlan(this.level, source, true, true, false, barrier)));
            final var plan = planRef.get();
            org.junit.jupiter.api.Assertions.assertNotNull(plan);
            engine.runInGlobalRegion(() -> plan.accept(chunk, true));
            call("setAllowed", void.class, new Class<?>[] { ResourceLocation.class, MobSpawnType.class, boolean.class }, MOB, MobSpawnType.NATURAL, true);
            plan.executeAndAwait(barrier);
            assertTrue(observed.get());
            assertEquals(1, engine.diagnostics().spawnChunksOnWorkers());
        } finally {
            net.neoforged.neoforge.server.threading.RegionThreadingManager.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedFailureRestoresTheOuterBoostContext() throws Throwable {
        final Class<?> boost = Class.forName("com.mobspawncontroller.natural.NaturalSpawnBoost");
        final var getter = MethodHandles.privateLookupIn(boost, MethodHandles.lookup())
                .findStaticGetter(boost, "ACTIVE_LEVEL", ThreadLocal.class);
        final ThreadLocal<ServerLevel> context = (ThreadLocal<ServerLevel>) getter.invoke();
        final ServerLevel outer = mock(ServerLevel.class);
        context.set(outer);
        try {
            when(this.level.getProfiler()).thenThrow(new IllegalStateException("nested failure"));
            assertThrows(IllegalStateException.class,
                    () -> net.minecraft.world.level.NaturalSpawner.spawnForChunk(this.level, null, null, true, true, false));
            assertSame(outer, context.get());
        } finally {
            context.remove();
        }
    }

    @Test
    void naturalFinalizerUsesFrozenAttributesExactlyOnce() throws Throwable {
        this.finalizerAttributes(MobSpawnType.NATURAL, 42.0, true);
    }

    @Test
    void nestedNonNaturalFinalizerUsesTheUnitsRevision() throws Throwable {
        this.finalizerAttributes(MobSpawnType.SPAWN_EGG, 42.0, true);
    }

    @Test
    void outsidePreparedBodyNonNaturalFinalizerStillUsesLiveAttributes() throws Throwable {
        this.finalizerAttributes(MobSpawnType.SPAWN_EGG, 84.0, false);
    }

    private void finalizerAttributes(MobSpawnType type, double expected, boolean preparedBody) throws Throwable {
        final var health = net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH;
        final var id = health.unwrapKey().orElseThrow().location();
        call("setAttributeOverrides", void.class, new Class<?>[] { ResourceLocation.class, Map.class }, MOB, Map.of(id, 42.0));
        this.register();
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        call("setAttributeOverrides", void.class, new Class<?>[] { ResourceLocation.class, Map.class }, MOB, Map.of(id, 84.0));
        final var mob = mock(net.minecraft.world.entity.Mob.class);
        org.mockito.Mockito.doReturn(net.minecraft.world.entity.EntityType.ZOMBIE).when(mob).getType();
        final var attributes = mock(net.minecraft.world.entity.ai.attributes.AttributeMap.class);
        final var instance = mock(net.minecraft.world.entity.ai.attributes.AttributeInstance.class);
        when(mob.getAttributes()).thenReturn(attributes);
        when(attributes.hasAttribute(health)).thenReturn(true);
        when(attributes.getInstance(health)).thenReturn(instance);
        when(mob.getMaxHealth()).thenReturn(12.0F);
        final var event = new net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent(mob, this.level,
                0, 64, 0, mock(net.minecraft.world.DifficultyInstance.class), type, null, null);
        final Class<?> entry = Class.forName("com.mobspawncontroller.neoforge.MobSpawnControllerNeoForge");
        final Object listener = mock(entry);
        final var finalize = MethodHandles.privateLookupIn(entry, MethodHandles.lookup()).findVirtual(entry,
                "onFinalizeSpawn", MethodType.methodType(void.class, net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent.class));
        final Runnable body = () -> {
            try {
                finalize.invoke(listener, event);
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        };
        if (preparedBody) assertTrue(work.run(this.level, body));
        else body.run();
        org.mockito.Mockito.verify(instance).setBaseValue(expected);
        org.mockito.Mockito.verify(mob).setHealth(12.0F);
        assertTrue(RuleSnapshots.current().isEmpty());
    }

    @Test
    void deniedNaturalSpawnKeepsCancellationAndSkipsAttributes() throws Throwable {
        call("setAllowed", void.class, new Class<?>[] { ResourceLocation.class, MobSpawnType.class, boolean.class }, MOB, MobSpawnType.NATURAL, false);
        this.register();
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        final var mob = mock(net.minecraft.world.entity.Mob.class);
        org.mockito.Mockito.doReturn(net.minecraft.world.entity.EntityType.ZOMBIE).when(mob).getType();
        final var event = new net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent(mob, this.level,
                0, 64, 0, mock(net.minecraft.world.DifficultyInstance.class), MobSpawnType.NATURAL, null, null);
        final Class<?> entry = Class.forName("com.mobspawncontroller.neoforge.MobSpawnControllerNeoForge");
        final var listener = mock(entry);
        final var finalize = MethodHandles.privateLookupIn(entry, MethodHandles.lookup()).findVirtual(entry,
                "onFinalizeSpawn", MethodType.methodType(void.class, net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent.class));
        assertTrue(work.run(this.level, () -> {
            try {
                finalize.invoke(listener, event);
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        }));
        org.mockito.Mockito.verify(mob).setSpawnCancelled(true);
        org.mockito.Mockito.verify(mob, org.mockito.Mockito.never()).getAttributes();
        assertTrue(RuleSnapshots.current().isEmpty());
    }

    @Test
    void multiplierConfigurationRejectsThePlanBeforeCoreCapConstruction() throws Throwable {
        final Class<?> settings = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettings");
        final Class<?> codec = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettingsJsonCodec");
        final var json = new com.google.gson.JsonObject();
        json.addProperty("spawn_multiplier", 4.0);
        final Object decoded = MethodHandles.publicLookup().findStatic(codec, "decode",
                MethodType.methodType(settings, com.google.gson.JsonObject.class)).invoke(json);
        call("setNaturalSpawnSettings", void.class, new Class<?>[] { ResourceLocation.class, settings }, MOB, decoded);
        this.register();
        final var engine = new net.neoforged.neoforge.server.threading.region.RegionThreadingEngine(2, false, false, false, false, false, false, true,
                net.neoforged.neoforge.server.threading.region.RegionLayout.of(net.neoforged.neoforge.server.threading.region.RegionPolicy.FIXED_3X3),
                net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode.REGION_OWNED);
        net.neoforged.neoforge.server.threading.RegionThreadingManager.install(engine);
        try {
            when(this.level.entitySectionRegionChunkSize()).thenReturn(3);
            when(this.level.getProfiler()).thenReturn(net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
            final var reads = new java.util.concurrent.atomic.AtomicInteger();
            final var source = new net.neoforged.neoforge.server.threading.region.RegionSpawnSource(
                    (key, consumer) -> {}, key -> java.util.List.of(), key -> false, reads::incrementAndGet);
            final var result = new AtomicReference<net.neoforged.neoforge.server.threading.region.RegionSpawnPlan>();
            engine.runInGlobalRegion(() -> result.set(engine.beginSpawnPlan(this.level, source, true, true, false, condition -> {})));
            org.junit.jupiter.api.Assertions.assertNull(result.get(), "caller must execute vanilla's full serial spawn-state construction");
            assertEquals(0, reads.get(), "core cap counter must not be constructed for this configuration");
            org.mockito.Mockito.verify(this.level, org.mockito.Mockito.never()).getAllEntities();
        } finally {
            net.neoforged.neoforge.server.threading.RegionThreadingManager.clear();
        }
    }

    @Test
    void preparedNoSettingsBranchDoesNotTouchWorldOrRandom() {
        this.register();
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        assertTrue(work.workerReady());
        assertTrue(work.run(this.level, () -> {
            try {
                assertEquals(true, call("isNaturalSpawnAllowed", boolean.class,
                        new Class<?>[] { net.minecraft.world.entity.Entity.class, net.minecraft.world.level.ServerLevelAccessor.class, MobSpawnType.class },
                        null, null, MobSpawnType.NATURAL));
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        }));
    }
}
