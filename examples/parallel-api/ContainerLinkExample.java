/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.examples;

import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations;
import net.neoforged.neoforge.server.threading.api.ParallelApi;

/**
 * Minimal compile-only integration. Keep this class behind Forgia's optional entrypoint in a portable mod.
 * This does not declare the caller's ticker safe; audit and admit its local tick separately.
 */
public final class ContainerLinkExample {
    private final BlockEntityOperations operations;
    private final BlockEntityOperations.Reference target;
    private BlockEntityOperations.Task<BlockEntityOperations.TransferResult> pending;

    /** Create/update the link on the quiescent server while the target's chunk is already loaded. */
    public ContainerLinkExample(MinecraftServer server, BlockEntity target) {
        final ParallelApi.Server api = ParallelApi.forServer(server).orElseThrow();
        if (!api.capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_OPERATIONS)) {
            throw new IllegalStateException("Block entity operation capability is unavailable");
        }
        this.operations = api.blockEntities();
        this.target = this.operations.reference(target);
    }

    /** Call from the admitted source's own ticker, before applying any extraction/insertion effects. */
    public void tick(BlockEntity source) {
        if (this.pending == null) {
            this.pending = this.operations.transfer(this.operations.reference(source), this.target,
                    new BlockEntityOperations.Transfer(0, 0, 8, Direction.DOWN, Direction.UP));
        }
    }

    /** Poll from the source's later safe tick; never wait on a region worker. */
    public Optional<BlockEntityOperations.TransferResult> completedTransfer() {
        if (this.pending == null || !this.pending.isDone()) return Optional.empty();
        if (this.pending.state() == BlockEntityOperations.State.COMPLETED) return this.pending.result();
        // A real adapter handles this terminal reason/failure according to its own operation semantics.
        // It must not blindly retry a FAILED operation, whose callback may already have effects.
        return Optional.empty();
    }

    public Optional<BlockEntityOperations.Reason> terminalReason() {
        return this.pending == null || !this.pending.isDone() ? Optional.empty() : Optional.of(this.pending.reason());
    }
}
