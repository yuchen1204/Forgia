/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.forgia.compat.mobspawncontroller.RuleSnapshots;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mod-owned call sites only. Frozen data is selected only within an admitted prepared body. */
@Mixin(targets = "com.mobspawncontroller.command.MobSpawnManager", remap = false)
abstract class RuleSnapshotMixin {
    @Shadow
    @Final
    private static Map<ResourceLocation, Map<ResourceLocation, Double>> ATTRIBUTE_OVERRIDES;

    @Unique
    private static final String forgia$verifiedSnapshotHooks = "snapshot-v1";

    @Inject(method = "getAllowed", at = @At("HEAD"), cancellable = true, require = 1, allow = 1, remap = false)
    private static void forgia$allowed(ResourceLocation id, MobSpawnType type, CallbackInfoReturnable<Boolean> callback) {
        RuleSnapshots.current().ifPresent(snapshot -> callback.setReturnValue(snapshot.rules().getOrDefault(id, Map.of()).get(type)));
    }

    @Inject(method = "getAllNaturalSpawnSettings", at = @At("HEAD"), cancellable = true, require = 1, allow = 1, remap = false)
    private static void forgia$natural(CallbackInfoReturnable<Map<ResourceLocation, Object>> callback) {
        RuleSnapshots.current().ifPresent(snapshot -> callback.setReturnValue(snapshot.natural()));
    }

    @Inject(method = "isNaturalSpawnAllowed", at = @At("HEAD"), cancellable = true, require = 1, allow = 1, remap = false)
    private static void forgia$noWorldRules(@Coerce Object entity, @Coerce Object level, MobSpawnType type,
            CallbackInfoReturnable<Boolean> callback) {
        // A Ready unit was checked before any body effects. Its snapshot has no applicable natural-world
        // filters; retain the original no-settings early return without reading a hot-reloaded live table.
        if (RuleSnapshots.current().isPresent()) callback.setReturnValue(true);
    }

    @Redirect(method = "applyAttributeOverrides", at = @At(value = "FIELD", target = "Lcom/mobspawncontroller/command/MobSpawnManager;ATTRIBUTE_OVERRIDES:Ljava/util/Map;"), require = 1, allow = 1, remap = false)
    private static Map<ResourceLocation, Map<ResourceLocation, Double>> forgia$attributes() {
        return RuleSnapshots.current().map(RuleSnapshots.Snapshot::attributes).orElse(ATTRIBUTE_OVERRIDES);
    }

    @WrapMethod(method = "setAllowed", remap = false, require = 1, allow = 1)
    private static void forgia$writeAllowed(ResourceLocation id, MobSpawnType type, boolean allowed, Operation<Void> original) {
        RuleSnapshots.mutate(() -> original.call(id, type, allowed));
    }

    @WrapMethod(method = "setAllAllowed", remap = false, require = 1, allow = 1)
    private static void forgia$writeAll(ResourceLocation id, boolean allowed, Operation<Void> original) {
        RuleSnapshots.mutate(() -> original.call(id, allowed));
    }

    @WrapMethod(method = "setNaturalSpawnSettings", remap = false, require = 1, allow = 1)
    private static void forgia$writeNatural(ResourceLocation id, @Coerce Object settings, Operation<Void> original) {
        RuleSnapshots.mutate(() -> original.call(id, settings));
    }

    @WrapMethod(method = "setActiveSpawnSettings", remap = false, require = 1, allow = 1)
    private static void forgia$writeActive(ResourceLocation id, @Coerce Object settings, Operation<Void> original) {
        RuleSnapshots.mutate(() -> original.call(id, settings));
    }

    @WrapMethod(method = "setAttributeOverrides", remap = false, require = 1, allow = 1)
    private static void forgia$writeAttributes(ResourceLocation id, Map<ResourceLocation, Double> values, Operation<Void> original) {
        RuleSnapshots.mutate(() -> original.call(id, values));
    }

    @WrapMethod(method = "clear", remap = false, require = 1, allow = 1)
    private static void forgia$clear(ResourceLocation id, Operation<Void> original) {
        RuleSnapshots.mutate(() -> original.call(id));
    }

    @WrapMethod(method = "clearAll", remap = false, require = 1, allow = 1)
    private static void forgia$clearAll(Operation<Void> original) {
        RuleSnapshots.mutate(() -> original.call());
    }

    @WrapMethod(method = "load", remap = false, require = 1, allow = 1)
    private static void forgia$load(Operation<Void> original) {
        RuleSnapshots.mutate(() -> original.call());
    }
}
