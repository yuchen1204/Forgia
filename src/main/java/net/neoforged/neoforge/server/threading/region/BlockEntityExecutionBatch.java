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
    private boolean canonicalSequence;

    BlockEntityExecutionBatch(ServerLevel level, int blockUpdateEntryBudget, List<RegionBlockEntityTickChunk> chunks,
            BooleanSupplier live, Function<BlockPos, RegionState> home) {
        super(level, RegionTickSlice.BLOCK_ENTITY_TICK, blockUpdateEntryBudget);
        final List<RegionBlockEntityTickAdmission> admissions = new ArrayList<>();
        for (final var chunk : chunks) admissions.addAll(chunk.admissions());
        this.execution = new BlockEntityExecutionEngine(level, admissions, live, home);
    }

    final void canonicalSequence(boolean canonicalSequence) {
        this.canonicalSequence = canonicalSequence;
    }

    final boolean canonicalSequence() {
        return this.canonicalSequence;
    }

    /** Server boundary only; dynamic eligibility is not a lasting permission from phase collection. */
    final boolean eligibleNow(RegionBlockEntityTickAdmission admission) {
        if (!this.canonicalSequence) return true;
        if (admission.ticker().isRemoved()) {
            this.level().removeCollectedBlockEntityTickers(List.of(admission.ticker()));
            return false;
        }
        return this.level().shouldTickBlocksAt(admission.ticker().getPos())
                && this.level().areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(admission.ticker().getPos()));
    }

    final BlockEntityExecutionEngine execution() {
        return this.execution;
    }
}
