/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Same invocation lifecycle for native region groups and protected furnace waves. */
abstract class BlockEntityExecutionBatch extends RegionTickBatch {
    private final BlockEntityExecutionEngine execution;

    BlockEntityExecutionBatch(ServerLevel level, int blockUpdateEntryBudget, List<RegionBlockEntityTickChunk> chunks,
            BooleanSupplier live, Function<BlockPos, RegionState> home) {
        super(level, RegionTickSlice.BLOCK_ENTITY_TICK, blockUpdateEntryBudget);
        final List<RegionBlockEntityTickAdmission> admissions = new ArrayList<>();
        for (final var chunk : chunks) admissions.addAll(chunk.admissions());
        this.execution = new BlockEntityExecutionEngine(level, admissions, live, home);
    }

    final BlockEntityExecutionEngine execution() {
        return this.execution;
    }
}
