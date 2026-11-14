/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/** A fresh, closed original body branch, not a type declaration or an observed callback footprint. */
@ApiStatus.Internal
public final class NativeBlockEntityPreparation {
    private static final BlockEntityTicker<HopperBlockEntity> HOPPER = HopperBlockEntity::pushItemsTick;
    private static String runtimeRefusal;
    private static final boolean VERIFIED = unmodifiedMethods();

    private NativeBlockEntityPreparation() {}

    /** The exact original function issued to HopperBlock; a replacement function never inherits its proof. */
    public static BlockEntityTicker<HopperBlockEntity> hopperTicker() {
        return HOPPER;
    }

    private static boolean unmodifiedMethods() {
        try {
            for (final Class<?> outer : List.of(HopperBlockEntity.class, HopperBlock.class, BlockEntity.class,
                    net.minecraft.world.level.block.entity.BaseContainerBlockEntity.class,
                    net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.class,
                    LevelChunk.class, Level.class, ServerLevel.class, net.minecraft.world.level.storage.PrimaryLevelData.class,
                    net.minecraft.world.level.block.entity.BlockEntityType.class,
                    net.minecraft.world.level.border.WorldBorder.class, TimeTracker.class,
                    net.minecraft.world.level.block.state.StateHolder.class,
                    net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class)) {
                for (Class<?> parent = outer; parent != null; parent = parent.getSuperclass()) {
                    for (final Class<?> type : parent.getNestMembers()) {
                        for (final var method : type.getDeclaredMethods()) {
                            // NeoForge's BlockEntityTypeAddBlocksEvent has a mandatory accessor Mixin.
                            // That registration-time setter is outside this invocation's read-only type
                            // checks. Audit both reached methods instead of treating every class member
                            // as part of the body closure; no transformed reached method is exempted.
                            if (type == net.minecraft.world.level.block.entity.BlockEntityType.class
                                    && !java.util.Set.of("isValid", "getKey").contains(method.getName()))
                                continue;
                            if (method.getAnnotation(MixinMerged.class) != null) {
                                runtimeRefusal = "Transformed method: " + type.getName() + "." + method.getName();
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        } catch (LinkageError | RuntimeException unavailable) {
            runtimeRefusal = unavailable.toString();
            return false;
        }
    }

    /** No inventory, provider, loot, entity, neighbor, RNG, or operation callback is entered in this branch. */
    @Nullable
    static Candidate prepare(RegionBlockEntityTickAdmission admission) {
        if (!VERIFIED || TimeTracker.BLOCK_ENTITY_UPDATE.isEnabled() || !admission.isAdmitted()
                || !admission.isCurrent() || RegionTickCompatibility.serverThreadOnlyTypes().containsKey(admission.type()))
            return null;
        final HopperBlockEntity source = LevelChunk.nativeHopperSource(admission.ticker());
        if (source == null || source.getClass() != HopperBlockEntity.class || source.hasAttachments()
                || source.getLevel() != admission.level() || source.isRemoved())
            return null;
        final var border = admission.level().getWorldBorder();
        if (admission.level().getServer() == null || admission.level().getClass() != ServerLevel.class
                || border == null || border.getClass() != net.minecraft.world.level.border.WorldBorder.class)
            return null;
        final var clock = admission.level().getLevelData();
        if (clock == null || clock.getClass() != net.minecraft.world.level.storage.PrimaryLevelData.class) return null;
        final var position = admission.position();
        final LevelChunk chunk = admission.level().getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null || chunk.getClass() != LevelChunk.class || !chunk.isLoaded() || !LevelChunk.furnaceWorkMatchesChunk(admission.ticker(), chunk)
                || chunk.getBlockEntities().get(position) != source)
            return null;
        final var state = chunk.getBlockState(position);
        if (state != source.getBlockState() || state.getBlock() != Blocks.HOPPER || !source.getType().isValid(state)
                || !source.nativeTickHasNoCallbacks(state))
            return null;
        return new Candidate(admission, source, chunk);
    }

    record Candidate(RegionBlockEntityTickAdmission admission, HopperBlockEntity source, LevelChunk chunk) {
        boolean fresh() {
            final var current = prepare(this.admission);
            return current != null && current.source == this.source && current.chunk == this.chunk;
        }
    }
}
