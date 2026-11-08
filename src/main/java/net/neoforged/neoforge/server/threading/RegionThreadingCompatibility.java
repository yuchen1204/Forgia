/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ForgiaConfig;
import org.jetbrains.annotations.ApiStatus;

/**
 * Compatibility lookup for regionized execution support.
 */
public final class RegionThreadingCompatibility {
    private RegionThreadingCompatibility() {}

    /**
     * Returns the configured default until explicit mod metadata is wired in.
     */
    @ApiStatus.Experimental
    public static RegionThreadingSupport defaultSupport() {
        return ForgiaConfig.SERVER.defaultRegionThreadingSupport.get();
    }

    /**
     * Returns the declared support level for {@code modId}.
     */
    @ApiStatus.Experimental
    public static RegionThreadingSupport supportForMod(String modId) {
        return declaredSupportForMod(modId).orElseGet(RegionThreadingCompatibility::defaultSupport);
    }

    private static Optional<RegionThreadingSupport> declaredSupportForMod(String modId) {
        return ModList.get().getModContainerById(modId)
                .flatMap(container -> container.getModInfo().getConfig().<String>getConfigElement("regionThreading"))
                .flatMap(RegionThreadingCompatibility::parseSupport);
    }

    private static Optional<RegionThreadingSupport> parseSupport(String value) {
        try {
            return Optional.of(RegionThreadingSupport.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /**
     * {@return true when the current compatibility policy allows installing the bridge}
     */
    @ApiStatus.Internal
    public static boolean canInstallBridge() {
        return defaultSupport() != RegionThreadingSupport.UNSUPPORTED && unsupportedMods().isEmpty();
    }

    /**
     * {@return loaded mod ids that explicitly declare region threading unsupported}
     */
    @ApiStatus.Internal
    public static List<String> unsupportedMods() {
        return ModList.get().getMods().stream()
                .filter(mod -> declaredSupportForMod(mod.getModId()).orElse(RegionThreadingSupport.UNKNOWN) == RegionThreadingSupport.UNSUPPORTED)
                .map(mod -> mod.getModId())
                .toList();
    }

    /**
     * {@return loaded mod ids that explicitly require region threading}
     */
    @ApiStatus.Internal
    public static List<String> requiredMods() {
        return ModList.get().getMods().stream()
                .filter(mod -> declaredSupportForMod(mod.getModId()).orElse(RegionThreadingSupport.UNKNOWN) == RegionThreadingSupport.REQUIRED)
                .map(mod -> mod.getModId())
                .toList();
    }
}
