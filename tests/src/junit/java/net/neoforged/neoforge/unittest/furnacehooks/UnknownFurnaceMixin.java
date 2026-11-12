/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.furnacehooks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractFurnaceBlockEntity.class, remap = false)
public abstract class UnknownFurnaceMixin {
    @Inject(method = "serverTick", at = @At("HEAD"))
    private static void forgiaTest$unknownOriginalHook(Level level, BlockPos pos, BlockState state,
            AbstractFurnaceBlockEntity source, CallbackInfo ci) {}
}
