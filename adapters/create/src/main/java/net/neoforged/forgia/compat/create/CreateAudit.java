/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.decoration.bracket.BracketedBlockEntityBehaviour;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.TorquePropagator;
import com.simibubi.create.content.kinetics.base.DirectionalShaftHalvesBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.foundation.advancement.AdvancementBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticEffectHandlerAccessor;
import net.neoforged.forgia.compat.create.mixin.SmartBlockEntityAccessor;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/** Pinned original bytecode plus bounded inspection of merged Mixin provenance. No upstream code is bundled. */
public final class CreateAudit {
    private static final Map<Class<?>, String> AUDITED = Map.ofEntries(
            Map.entry(TorquePropagator.class, "2c3e376f0ebe57589f9bd4b238156b3016f272a91a2710a3d6fe152b25eceee9"),
            Map.entry(KineticNetwork.class, "bab6c531d5b916cbc231fb53083283cc049a0d58884cd669a8000adc474127f9"),
            Map.entry(GeneratingKineticBlockEntity.class, "e914e3ab8d1a310fddc2c8a4884d4120d46cc4202ad20d10f76e900242eb64df"),
            Map.entry(WaterWheelBlockEntity.class, "1219e362c15d1368645706a537a3632226f7f360f3d4524e9c1e783c103578da"),
            Map.entry(LargeWaterWheelBlockEntity.class, "0a955bf423919058afeff4009519a4352dd7b8686f421c0bc2b83db66fd4f104"),
            Map.entry(SmartBlockEntity.class, "6e5272cffba116187a6c37a36321e1e76e67bb206f8d8804d60f9a92ee1ef2f9"),
            Map.entry(KineticBlockEntity.class, "3bce80f18d09ccff5e6c1b5cdbeeb2793f0a507006879b267148cde0df443572"),
            Map.entry(KineticEffectHandler.class, "dfe10be2e0eadc4367e441b09af3d925902901ea5098fcf4be354c44b8a20ae9"),
            Map.entry(DirectionalShaftHalvesBlockEntity.class, "eec965ed4713c9a417c6fd704e85f688b0e5798c419a3b0bb7b4ff156a17a636"),
            Map.entry(SimpleKineticBlockEntity.class, "44697e40f4b6d98fd4c91909a21a206f7d047c47f430a9c34fb18f47c57b60dc"),
            Map.entry(BracketedKineticBlockEntity.class, "8284d796d3b24a9526de58ec91aaaecf364766c6519a8f4ae8d900ef8e7fa4f7"),
            Map.entry(GearboxBlockEntity.class, "fe7ec69e1d207251109632835f2208e9937e3599521c63a05f8af0ae0edc8cc8"),
            Map.entry(MillstoneBlockEntity.class, "1b3558a8d881f68f1d5decdae731f9e93386f1dcc72f444bc39484172ca06573"),
            Map.entry(BlockEntityBehaviour.class, "2088a6dc96882f59f4df86430fe1c0f0bd277535ba282e341755f24060e162fc"),
            Map.entry(AdvancementBehaviour.class, "cd8a7214dcde3b891bea587426887dd8aa8a404fdc192cbf72a89ccaa6c65cc3"),
            Map.entry(DirectBeltInputBehaviour.class, "1fef40a6d01c4b8b65fc5e0ae554a7732e01c446d3fef392647a010e9fad6d9b"),
            Map.entry(BracketedBlockEntityBehaviour.class, "9ae1a461672e7edea503bc2e31bea11e05c724e053f383363a6db7e3d69c599e"));
    private static final Set<String> ACCESSORS = Set.of(
            "net.neoforged.forgia.compat.create.mixin.SmartBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor",
            "net.neoforged.forgia.compat.create.mixin.KineticEffectHandlerAccessor",
            "net.neoforged.forgia.compat.create.mixin.TorquePropagatorAccessor",
            "com.simibubi.create.foundation.mixin.accessor.ItemStackHandlerAccessor");

    private CreateAudit() {}

    public static boolean verify() {
        try {
            if (!SmartBlockEntityAccessor.class.isAssignableFrom(SmartBlockEntity.class)
                    || !KineticBlockEntityAccessor.class.isAssignableFrom(KineticBlockEntity.class)
                    || !KineticEffectHandlerAccessor.class.isAssignableFrom(KineticEffectHandler.class)) {
                LogUtils.getLogger().warn("Forgia Create accessors missing; Create ticks remain serial");
                return false;
            }
            for (final var entry : AUDITED.entrySet()) {
                final Class<?> type = entry.getKey();
                try (final var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                    if (input == null || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).equals(entry.getValue())) {
                        LogUtils.getLogger().warn("Unaudited Create bytecode {}; Create ticks remain serial", type.getName());
                        return false;
                    }
                }
                if (!knownTransformations(type)) return false;
            }
            return knownTransformations(ItemStackHandler.class);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException | LinkageError failure) {
            LogUtils.getLogger().warn("Cannot verify Create tick implementation; Create ticks remain serial", failure);
            return false;
        }
    }

    private static boolean knownTransformations(Class<?> type) {
        for (final var method : type.getDeclaredMethods()) {
            final MixinMerged merged = method.getAnnotation(MixinMerged.class);
            if (merged != null && !ACCESSORS.contains(merged.mixin())) {
                LogUtils.getLogger().warn("Unverified Create tick transformation {} on {}; Create ticks remain serial", merged.mixin(), type.getName());
                return false;
            }
        }
        return true;
    }
}
