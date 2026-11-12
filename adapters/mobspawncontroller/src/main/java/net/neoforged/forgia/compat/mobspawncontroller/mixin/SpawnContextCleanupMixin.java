/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Restore the upstream context on exceptional exits and nested spawning, not just normal RETURN. */
@Mixin(value = NaturalSpawner.class, remap = false)
abstract class SpawnContextCleanupMixin {
    @Unique
    private static final String forgia$spawnContextCleanup = "finally-v2";

    @Unique
    private static final MethodHandle forgia$context = forgia$bindContext();

    @Unique
    private static MethodHandle forgia$bindContext() {
        try {
            final Class<?> boost = Class.forName("com.mobspawncontroller.natural.NaturalSpawnBoost", false, NaturalSpawner.class.getClassLoader());
            return MethodHandles.privateLookupIn(boost, MethodHandles.lookup()).findStaticGetter(boost, "ACTIVE_LEVEL", ThreadLocal.class);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Missing MobSpawnController spawn context", failure);
        }
    }

    @WrapMethod(method = "finalizeSpawnForRegion", remap = false, require = 1, allow = 1)
    @SuppressWarnings("unchecked")
    private static net.neoforged.neoforge.server.threading.RegionThreadingHooks.SpawnFinalization forgia$orderedContext(
            ServerLevel level, net.minecraft.world.entity.Mob mob, net.minecraft.world.entity.SpawnGroupData data,
            net.minecraft.world.level.chunk.ChunkAccess chunk, NaturalSpawner.AfterSpawnCallback afterSpawn,
            int total, int groupCount, Operation<net.neoforged.neoforge.server.threading.RegionThreadingHooks.SpawnFinalization> original) throws Throwable {
        final ThreadLocal<ServerLevel> context = (ThreadLocal<ServerLevel>) forgia$context.invokeExact();
        final ServerLevel previous = context.get();
        context.set(level);
        try {
            return original.call(level, mob, data, chunk, afterSpawn, total, groupCount);
        } finally {
            if (previous == null) context.remove();
            else context.set(previous);
        }
    }

    @WrapMethod(method = "spawnForChunk", remap = false, require = 1, allow = 1)
    @SuppressWarnings("unchecked")
    private static void forgia$spawnFinally(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state,
            boolean friendly, boolean enemies, boolean persistent, Operation<Void> original) {
        final ThreadLocal<ServerLevel> context;
        try {
            context = (ThreadLocal<ServerLevel>) forgia$context.invokeExact();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot read MobSpawnController spawn context", failure);
        }
        final ServerLevel previous = context.get();
        try {
            original.call(level, chunk, state, friendly, enemies, persistent);
        } finally {
            if (previous == null) context.remove();
            else context.set(previous);
        }
    }
}
