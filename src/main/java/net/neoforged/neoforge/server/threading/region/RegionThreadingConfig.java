/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.common.NeoForgeConfig;

/**
 * Reads region threading settings, using the declared defaults until the server config is loaded.
 */
final class RegionThreadingConfig {
    private RegionThreadingConfig() {}

    static int maxImmediateTasksPerDrain() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.regionThreadingMaxImmediateTasksPerDrain.get()
                : ForgiaConfig.SERVER.regionThreadingMaxImmediateTasksPerDrain.getDefault();
    }

    static int maxScheduledTasksPerDrain() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.regionThreadingMaxScheduledTasksPerDrain.get()
                : ForgiaConfig.SERVER.regionThreadingMaxScheduledTasksPerDrain.getDefault();
    }

    static long longTickWarningNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.regionThreadingLongTickWarningNanos.get()
                : ForgiaConfig.SERVER.regionThreadingLongTickWarningNanos.getDefault();
    }

    /**
     * {@return true when a level created now partitions its POI storage per region}
     * <p>
     * The dedicated switch is off by default, and enabling the entity worker slice implies the storage: a
     * worker tick of a villager, iron golem, raider, bee, zombie or fox reaches the level's POI manager, and
     * the regionized storage is what makes that tick safe next to the server thread. A configuration that
     * enables entity worker ticks therefore gets the storage even with the dedicated switch off, instead of
     * silently running those ticks against the one shared vanilla manager.
     */
    static boolean poiStorageEnabled() {
        if (!ForgiaConfig.isServerConfigLoaded()) {
            return false;
        }
        return ForgiaConfig.SERVER.enableRegionThreadingPoi.get() || ForgiaConfig.SERVER.enableRegionThreadingEntityTicks.get();
    }

    /**
     * {@return true when a region context captures the neighbor updates that target another region}
     * <p>
     * The switch is off by default. When it is off the region context uses the vanilla collecting updater and
     * the level write paths keep their vanilla behavior, so the feature costs nothing on a server that does not
     * enable it.
     */
    static boolean borderUpdatesEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionThreadingBorderUpdates.get()
                : ForgiaConfig.SERVER.enableRegionThreadingBorderUpdates.getDefault();
    }

    /**
     * {@return true when a region context captures the game events whose listeners belong to another region}
     * <p>
     * The switch is off by default. When it is off the dispatch is exactly vanilla: the posting worker visits
     * every listener of the event's radius, including the listeners of other regions, and therefore writes a
     * foreign block entity while its owner may tick it - the documented racy baseline.
     */
    static boolean crossRegionGameEventsEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionThreadingGameEvents.get()
                : ForgiaConfig.SERVER.enableRegionThreadingGameEvents.getDefault();
    }

    static NeoForgeConfig.RegionThreadingOwnershipViolationMode ownershipViolationMode() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.regionThreadingOwnershipViolationMode.get()
                : ForgiaConfig.SERVER.regionThreadingOwnershipViolationMode.getDefault();
    }
}
