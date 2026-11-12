/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.server.threading.api.ParallelApi;

/** Copies mod-owned mutable registries, never the upstream rule evaluator. */
public final class RuleSnapshots {
    public static final ParallelApi.DataKey<Snapshot> KEY = new ParallelApi.DataKey<>(
            ResourceLocation.parse("forgia_mobspawncontroller:rules"), Snapshot.class);
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static long revision;
    private static volatile Snapshot cached;
    private static final ResourceLocation SERIAL = ResourceLocation.parse("forgia_mobspawncontroller:world_dependent_rules");

    private RuleSnapshots() {}

    /** All inspected upstream mutators use the same gate as snapshot capture. Never held by worker reads. */
    public static void mutate(Runnable action) {
        LOCK.lock();
        try {
            action.run();
        } finally {
            revision++;
            cached = null;
            LOCK.unlock();
        }
    }

    public static ParallelApi.Preparation<Snapshot> prepare(ParallelApi.SpawnUnit unit) {
        return prepareRules();
    }

    public static ParallelApi.Preparation<Snapshot> prepareRules() {
        final Snapshot snapshot = capture();
        if (snapshot.requiresWorldRules && !Binding.INSTANCE.nativeHooksSupported) {
            return new ParallelApi.ServerRequired<>(ResourceLocation.parse("forgia_mobspawncontroller:unverified_spawn_hooks"));
        }
        // Unsupported filters/cap protocols retain the full native serial plan before core cap construction.
        return !snapshot.supportsOrderedFinalization ? new ParallelApi.ServerRequired<>(SERIAL)
                : new ParallelApi.Ready<>(snapshot, snapshot.requiresWorldRules);
    }

    @SuppressWarnings("unchecked")
    public static Snapshot capture() {
        LOCK.lock();
        try {
            if (cached != null) return cached;
            final Binding binding = Binding.INSTANCE;
            final Map<ResourceLocation, Map<MobSpawnType, Boolean>> rules = new HashMap<>();
            ((Map<ResourceLocation, Map<MobSpawnType, Boolean>>) binding.rules.invoke()).forEach((id, values) -> rules.put(id, Map.copyOf(values)));
            final Map<ResourceLocation, Object> natural = Map.copyOf((Map<ResourceLocation, Object>) binding.natural.invoke());
            final Map<ResourceLocation, Map<ResourceLocation, Double>> attributes = new HashMap<>();
            for (final ResourceLocation id : (java.util.Set<ResourceLocation>) binding.attributeIds.invoke()) {
                attributes.put(id, Map.copyOf((Map<ResourceLocation, Double>) binding.attributes.invoke(id)));
            }
            // All settings records are inspected, including secondary spawn reasons. Evaluation stays in
            // the original listener on the parked server, with its real entity query and cancellation order.
            final boolean worldRules = !natural.isEmpty();
            boolean supported = true;
            for (final Object settings : natural.values()) supported &= binding.supportsOrdered(settings);
            cached = new Snapshot(revision, Map.copyOf(rules), natural, Map.copyOf(attributes), worldRules, supported);
            return cached;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot capture MobSpawnController rules", failure);
        } finally {
            LOCK.unlock();
        }
    }

    /** Values are upstream immutable NaturalSpawnSettings records; all mutable maps are copied deeply. */
    public record Snapshot(long revision, Map<ResourceLocation, Map<MobSpawnType, Boolean>> rules,
            Map<ResourceLocation, Object> natural, Map<ResourceLocation, Map<ResourceLocation, Double>> attributes,
            boolean requiresWorldRules, boolean supportsOrderedFinalization) {}

    public static boolean appliesTo(Object settings, MobSpawnType type) {
        try {
            return (boolean) Binding.INSTANCE.appliesTo.invoke(settings, type);
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot read prepared spawn type filter", failure);
        }
    }

    public static java.util.Optional<Snapshot> current() {
        return ParallelApi.prepared(KEY);
    }

    /** Mixin marker is resolved on the transformed target; jar presence alone is insufficient. */
    public static boolean verify() {
        try {
            final var lookup = MethodHandles.lookup();
            final Class<?> boost = Class.forName("com.mobspawncontroller.natural.NaturalSpawnBoost", true, RuleSnapshots.class.getClassLoader());
            final String boostMarker = (String) MethodHandles.privateLookupIn(boost, lookup)
                    .findStaticGetter(boost, "forgia$preparedBoost", String.class).invoke();
            final Class<?> spawner = net.minecraft.world.level.NaturalSpawner.class;
            final String cleanupMarker = (String) MethodHandles.privateLookupIn(spawner, lookup)
                    .findStaticGetter(spawner, "forgia$spawnContextCleanup", String.class).invoke();
            return "snapshot-v2".equals((String) Binding.INSTANCE.marker.invoke()) && "snapshot-v1".equals(boostMarker)
                    && "finally-v2".equals(cleanupMarker);
        } catch (Throwable failure) {
            throw new IllegalStateException("MobSpawnController snapshot hooks failed verification", failure);
        }
    }

    private static final class Binding {
        private static final Binding INSTANCE = new Binding();
        private final MethodHandle rules;
        private final MethodHandle natural;
        private final MethodHandle attributeIds;
        private final MethodHandle attributes;
        private final MethodHandle marker;
        private final Map<String, MethodHandle> settingsAccessors;
        private final Object defaults;
        private final MethodHandle appliesTo;
        private final boolean nativeHooksSupported;

        private static boolean knownNativeHooks() {
            try {
                return inspectNativeHooks();
            } catch (RuntimeException | LinkageError unavailableMetadata) {
                // Even a patched Minecraft class can contain an injected signature unavailable on this dist.
                // An inspection failure is unverified provenance, never permission to run or a tick failure.
                com.mojang.logging.LogUtils.getLogger().warn("Cannot inspect native spawn hooks; MobSpawnController natural rules remain serial", unavailableMetadata);
                return false;
            }
        }

        private static boolean inspectNativeHooks() {
            for (final var method : net.minecraft.world.level.NaturalSpawner.class.getDeclaredMethods()) {
                final var merged = method.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
                if (merged == null) continue;
                final String owner = merged.mixin();
                if (owner.startsWith("net.neoforged.forgia.compat.mobspawncontroller.mixin.")
                        || owner.equals("com.mobspawncontroller.mixin.NaturalSpawnerBoostMixin"))
                    continue;
                final String mod;
                final String version;
                if (owner.equals("com.moepus.byepregen.mixin.server.fasttick.NaturalSpawnerChunkCacheMixin")
                        || owner.equals("com.moepus.byepregen.mixin.server.tick.compat.sable.SableNaturalSpawnerMixin")) {
                    mod = "byepregen";
                    version = "1.1.2.1";
                } else if (owner.equals("com.yungnickyoung.minecraft.betterfortresses.mixin.FixMobSpawningMixin")) {
                    mod = "betterfortresses";
                    version = "1.21.1-NeoForge-3.1.5";
                } else {
                    com.mojang.logging.LogUtils.getLogger().warn("Unverified native spawn hook {}; MobSpawnController natural rules remain serial", owner);
                    return false;
                }
                if (!net.neoforged.fml.ModList.get().getModContainerById(mod)
                        .map(container -> container.getModInfo().getVersion().toString().equals(version)).orElse(false)) {
                    com.mojang.logging.LogUtils.getLogger().warn("Native spawn hook {} requires inspected {} {}; natural rules remain serial", owner, mod, version);
                    return false;
                }
            }
            return true;
        }

        private boolean supportsOrdered(Object settings) throws Throwable {
            for (final var accessor : this.settingsAccessors.entrySet()) {
                final String name = accessor.getKey();
                final Object value = accessor.getValue().invoke(settings);
                if (java.util.Set.of("chance", "maxNearby", "nearbyRadius", "spawnTypes", "excludedSpawnTypes").contains(name)) {
                    if (value instanceof Double number && !Double.isFinite(number)) return false;
                    continue;
                }
                if (!java.util.Objects.equals(value, accessor.getValue().invoke(this.defaults))) return false;
            }
            return true;
        }

        private Binding() {
            try {
                final var lookup = MethodHandles.publicLookup();
                final ClassLoader loader = RuleSnapshots.class.getClassLoader();
                final Class<?> manager = Class.forName("com.mobspawncontroller.command.MobSpawnManager", true, loader);
                this.rules = lookup.findStatic(manager, "getAllRules", MethodType.methodType(Map.class));
                this.natural = lookup.findStatic(manager, "getAllNaturalSpawnSettings", MethodType.methodType(Map.class));
                this.attributeIds = lookup.findStatic(manager, "getAttributeOverrideMobs", MethodType.methodType(java.util.Set.class));
                this.attributes = lookup.findStatic(manager, "getAttributeOverrides", MethodType.methodType(Map.class, ResourceLocation.class));
                this.marker = MethodHandles.privateLookupIn(manager, MethodHandles.lookup())
                        .findStaticGetter(manager, "forgia$verifiedSnapshotHooks", String.class);
                final Class<?> settings = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettings", true, loader);
                this.defaults = lookup.findStatic(settings, "defaults", MethodType.methodType(settings)).invoke();
                this.appliesTo = lookup.findVirtual(settings, "appliesTo", MethodType.methodType(boolean.class, MobSpawnType.class));
                final Map<String, MethodHandle> accessors = new HashMap<>();
                for (final var component : settings.getRecordComponents()) {
                    accessors.put(component.getName(), lookup.unreflect(component.getAccessor()));
                }
                this.settingsAccessors = Map.copyOf(accessors);
                this.nativeHooksSupported = knownNativeHooks();
            } catch (Throwable failure) {
                throw new IllegalStateException("Unsupported MobSpawnController 1.2.3 ABI", failure);
            }
        }
    }
}
