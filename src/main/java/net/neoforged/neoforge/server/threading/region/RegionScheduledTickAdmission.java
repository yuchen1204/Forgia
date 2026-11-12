/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus.SerialReason;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * First production scheduled callback profile: vanilla lamp turn-off in an inert, region-contained footprint.
 * <p>
 * RedstoneLampBlock.tick reads neighboring signals (a conductor can query direct signals one block farther),
 * toggles only its own LIT state with flags=2 and consumes no RNG. The radius-two cube therefore contains
 * its signal reads and immediate shape updates. Only exact built-in air/stone/glass/redstone-block/lamp states
 * are accepted there: none can extend a shape cascade, access entities, schedule ticks or consume RNG through
 * this call path. A whole phase containing any other scheduled type or unsafe footprint stays in the original
 * serial LevelTicks loop, including its live queue and clearArea semantics. This is deliberately not a generic
 * claim that redstone, fluid ticks or modded callbacks are safe.
 */
@ApiStatus.Internal
final class RegionScheduledTickAdmission {
    private RegionScheduledTickAdmission() {}

    @Nullable
    static SerialReason refusal(ServerLevel level, RegionLayout layout, ScheduledTickPhase phase,
            List<? extends ScheduledTick<?>> ticks, int budget) {
        if (phase != ScheduledTickPhase.BLOCK) return SerialReason.FLUID_CALLBACK;
        for (final ScheduledTick<?> tick : ticks) {
            if (tick.type() != Blocks.REDSTONE_LAMP) return SerialReason.UNCLASSIFIED_CALLBACK;
        }
        if (!layout.isFixed()) return SerialReason.NON_FIXED_LAYOUT;
        if (ticks.size() > budget) return SerialReason.DEFERRED_UPDATE_BUDGET;
        final Map<Long, LevelChunk> loaded = new HashMap<>();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (final ScheduledTick<?> tick : ticks) {
            final BlockPos position = tick.pos();
            final RegionCoordinate owner = layout.regionAt(level, position);
            for (int dx = -2; dx <= 2; ++dx) {
                for (int dz = -2; dz <= 2; ++dz) {
                    final int x = position.getX() + dx;
                    final int z = position.getZ() + dz;
                    final int chunkX = x >> 4;
                    final int chunkZ = z >> 4;
                    if (!layout.isInRegion(level, chunkX, chunkZ, owner)) return SerialReason.FOREIGN_FOOTPRINT;
                    final long key = ChunkPos.asLong(chunkX, chunkZ);
                    LevelChunk chunk = loaded.get(key);
                    if (chunk == null) {
                        chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                        if (chunk == null || !chunk.isLoaded()) return SerialReason.UNLOADED_FOOTPRINT;
                        loaded.put(key, chunk);
                    }
                    for (int dy = -2; dy <= 2; ++dy) {
                        cursor.set(x, position.getY() + dy, z);
                        final Block block = chunk.getBlockState(cursor).getBlock();
                        if (!inert(block)) return SerialReason.UNSAFE_NEIGHBOR;
                    }
                }
            }
        }
        return null;
    }

    private static boolean inert(Block block) {
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR
                || block == Blocks.STONE || block == Blocks.GLASS || block == Blocks.REDSTONE_BLOCK || block == Blocks.REDSTONE_LAMP;
    }
}
