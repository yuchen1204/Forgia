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
    void actualNearbyRuleForcesCompleteSerialFallbackBeforeBody() throws Throwable {
        final Class<?> settings = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettings");
        final Class<?> codec = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettingsJsonCodec");
        final var json = new com.google.gson.JsonObject();
        json.addProperty("max_nearby", 1);
        json.addProperty("nearby_radius", 64);
        final Object decoded = MethodHandles.publicLookup().findStatic(codec, "decode",
                MethodType.methodType(settings, com.google.gson.JsonObject.class)).invoke(json);
        call("setNaturalSpawnSettings", void.class, new Class<?>[] { ResourceLocation.class, settings }, MOB, decoded);
        this.register();
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        assertFalse(work.workerReady());
        assertTrue(RuleSnapshots.capture().requiresWorldRules());
        assertEquals(1, ParallelApi.forServer(this.server).orElseThrow().adapters().getFirst().serialPlans());
        assertTrue(ParallelApi.prepared(RuleSnapshots.KEY).isEmpty());
        assertThrows(IllegalStateException.class, () -> work.run(this.level, () -> {
            throw new AssertionError("partial spawn body");
        }));
    }

    @Test
    void secondarySpawnOnlyWorldRulesAlsoRequireSerialPreparation() throws Throwable {
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
        assertFalse(ParallelAdapterRuntime.prepare(this.level, 0, 0).workerReady(),
                "natural ticks may create secondary entities; NATURAL-only checks are insufficient");
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
