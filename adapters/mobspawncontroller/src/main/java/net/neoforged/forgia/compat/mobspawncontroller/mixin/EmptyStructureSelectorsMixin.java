/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller.mixin;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Empty selectors cannot match a structure; avoid touching the world before discovering that fact. */
@Mixin(targets = "com.mobspawncontroller.command.MobSpawnManager", remap = false)
abstract class EmptyStructureSelectorsMixin {
    @Inject(method = "matchesAnyStructure(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/List;)Z", at = @At("HEAD"), cancellable = true, require = 1, allow = 1, remap = false)
    private static void forgia$skipEmptyStructureSelectors(ServerLevel level, BlockPos position, List<String> selectors,
            CallbackInfoReturnable<Boolean> callback) {
        if (selectors.isEmpty()) {
            callback.setReturnValue(false);
        }
    }
}
