/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.BlockEntityAdapterRegistry;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import org.jetbrains.annotations.Nullable;

/**
 * Minecraft binding edge for the Java-only request protocol. Attached to the one published canonical
 * batch; it cannot schedule, mint proof, reserve resources, issue Permits or commit effects. Registered
 * targets cut a prefix and are inspected only inside their original serial Permit at the actual cursor.
 */
final class BlockEntityAdapterPhase {
    private final BlockEntityAdapterRegistry.Session session;

    private BlockEntityAdapterPhase(BlockEntityAdapterRegistry.Session session) {
        this.session = session;
    }

    @Nullable
    static BlockEntityAdapterPhase open(ServerLevel level) {
        final var session = ParallelAdapterRuntime.beginBlockEntityAdapters(level);
        return session == null ? null : new BlockEntityAdapterPhase(session);
    }

    boolean handles(RegionBlockEntityTickAdmission admission) {
        return this.session.handles(admission.type());
    }

    void inspect(RegionBlockEntityTickAdmission admission) {
        if (!this.handles(admission)) return;
        if (!BlockEntityExecutionEngine.isCanonicalSerialInvocation())
            throw new IllegalStateException("Adapter inspection requires the original canonical serial Permit");
        final var pos = admission.position();
        final var chunks = admission.level().getChunkSource();
        final LevelChunk chunk = chunks.getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        // Never create/load a BE or query a Capability provider to discover a subject. A copied type id
        // or unrelated replacement in the same position cannot inherit this captured wrapper/source.
        BlockEntity subject = chunk == null || !chunk.isLoaded() ? null : chunk.getBlockEntities().get(pos);
        if (subject != null && (subject.isRemoved() || subject.getLevel() != admission.level()
                || !pos.equals(subject.getBlockPos()) || !admission.isCurrent()
                || !LevelChunk.isBlockEntityTickSource(admission.binding().delegate(), subject)
                || !admission.type().equals(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(subject.getType())))))
            subject = null;
        this.session.inspect(admission.type(), admission.sequence(), pos.getX(), pos.getY(), pos.getZ(), subject);
    }

    void close(boolean successful) {
        this.session.close(successful);
    }
}
