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
        // This version makes no approximation of structural, spatial or live-world filters. Even height-only
        // settings enter the original evaluator's unconditional world reads, so they stay serial for now.
        return snapshot.requiresWorldRules ? new ParallelApi.ServerRequired<>(SERIAL) : new ParallelApi.Ready<>(snapshot);
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
            // A natural body can synchronously create JOCKEY and other secondary spawns. Until their world
            // filters are also prepared, any natural-settings entry requires a complete serial unit.
            final boolean worldRules = !natural.isEmpty();
            cached = new Snapshot(revision, Map.copyOf(rules), natural, Map.copyOf(attributes), worldRules);
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
            boolean requiresWorldRules) {}

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
            return "snapshot-v1".equals((String) Binding.INSTANCE.marker.invoke()) && "snapshot-v1".equals(boostMarker)
                    && "finally-v1".equals(cleanupMarker);
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
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported MobSpawnController 1.2.3 ABI", failure);
            }
        }
    }
}
