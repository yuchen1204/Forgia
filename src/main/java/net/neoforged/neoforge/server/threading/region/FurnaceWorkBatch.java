/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Published lifecycle permit covering every joined furnace wave and all serial fences/commits. */
final class FurnaceWorkBatch extends BlockEntityExecutionBatch {
    FurnaceWorkBatch(ServerLevel level, List<RegionBlockEntityTickChunk> chunks, BooleanSupplier live, Function<BlockPos, RegionState> home) {
        super(level, RegionTickSlice.BLOCK_ENTITY_TICK.reservationPerUnit(), chunks, live, home);
        this.enableExternalWaves();
    }
}
