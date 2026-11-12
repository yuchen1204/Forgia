/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller.tests.hooks;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Its semantics are deliberately harmless: provenance has to be explicitly verified, not guessed. */
@Mixin(value = NaturalSpawner.class, remap = false)
abstract class UnknownNaturalSpawnerMixin {
    @Inject(method = "spawnForChunk", at = @At("HEAD"), require = 1, allow = 1, remap = false)
    private static void unverified$spawnHook(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state,
            boolean friendly, boolean enemies, boolean persistent, CallbackInfo callback) {}
}
