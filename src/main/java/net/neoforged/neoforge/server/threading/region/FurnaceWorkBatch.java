/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.server.level.ServerLevel;

/** Published lifecycle permit covering every joined furnace wave and all serial fences/commits. */
final class FurnaceWorkBatch extends RegionTickBatch {
    FurnaceWorkBatch(ServerLevel level) {
        super(level, RegionTickSlice.BLOCK_ENTITY_TICK, RegionTickSlice.BLOCK_ENTITY_TICK.reservationPerUnit());
        this.enableExternalWaves();
    }
}
