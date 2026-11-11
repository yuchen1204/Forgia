/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.forgia.compat.mobspawncontroller.RuleSnapshots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ready units have no applicable natural settings and therefore no multiplier; avoid shared cap caches. */
@Mixin(targets = "com.mobspawncontroller.natural.NaturalSpawnBoost", remap = false)
abstract class PreparedBoostMixin {
    @Unique
    private static final String forgia$preparedBoost = "snapshot-v1";

    @Inject(method = "scaleCategoryCap", at = @At("HEAD"), cancellable = true, require = 1, allow = 1, remap = false)
    private static void forgia$cap(MobCategory category, int vanillaCap, CallbackInfoReturnable<Integer> callback) {
        if (RuleSnapshots.current().isPresent()) callback.setReturnValue(vanillaCap);
    }

    @Inject(method = "scaleSpawnWeights", at = @At("HEAD"), cancellable = true, require = 1, allow = 1, remap = false)
    private static void forgia$weights(ServerLevel level, @Coerce Object original, CallbackInfoReturnable<Object> callback) {
        if (RuleSnapshots.current().isPresent()) callback.setReturnValue(original);
    }
}
