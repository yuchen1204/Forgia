/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

/**
 * Admits an entire source/target interaction before it changes either entity.
 * <p>
 * From an entity-tick worker owning the source, the callback runs on the server thread after that batch's
 * workers finish. From a quiescent server thread it runs immediately, including the server-thread completion
 * of its own batches once all workers have returned. A main-thread service callback while workers are still
 * active is not quiescent. Other contexts return a REJECTED handle;
 * this API does not block a worker waiting for another region. Both entities are revalidated against their
 * original level before the callback. Removal, dimension change, level unload and shutdown produce explicit
 * terminal outcomes. Cancelling queued work prevents its callback from running.
 * <p>
 * The callback must use its parameters for live entities and immutable captured input for the request. It must
 * not capture unrelated live entities and assume ownership of them. No synchronous damage/teleport result is
 * returned. An accepted operation runs at most once; acceptance does not promise completion or a fixed ordering
 * relative to another region's tick. This version uses Forgia's server-thread barrier rather than an independent
 * target-region tick clock. The experimental contract does not yet offer asynchronous chunk loading/teleport.
 */
@ApiStatus.Experimental
public interface EntityInteractionScheduler {
    EntityInteractionTask submit(Entity source, Entity target, BiConsumer<Entity, Entity> action);

    /**
     * Submits an operation on {@code owner}, authorized by the current source entity lease.
     * <p>
     * Unlike {@link #submit}, the source is only used for admission: its later removal or dimension change does
     * not cancel the operation, and the task does not retain it. This supports a projectile consuming itself
     * after submitting an impact. The callback receives only the live owner; capture immutable impact data
     * (for example a Vec3 or an immutable BlockPos), never the projectile or a mutable HitResult/entity reference.
     * The owner must still exist in the source's original level at execution. Owner removal, dimension change,
     * shutdown, queue capacity, cancellation and thread-context rules are the same as for {@code submit}.
     * <p>
     * This does not move the source's own remaining tick or automatically consume it. Admission and source
     * disposal are separate actions; a REJECTED request must not be treated as a successful owner operation.
     */
    EntityInteractionTask submitToOwner(Entity source, Entity owner, Consumer<Entity> action);

    /**
     * Admits a single owner operation which requires a FULL chunk, before any of that operation's effects.
     * The source authorizes admission only, as with {@link #submitToOwner}. At a quiescent server-thread
     * completion point the task acquires a temporary chunk ticket, loads the requested chunk, revalidates the
     * owner and lifecycle, invokes the callback once and releases the ticket in finally. The position is copied.
     * <p>
     * Loading may block the server tick: this is a ticket-backed synchronous completion stage, not asynchronous
     * loading or automatic resumption of arbitrary Java code. A failed load/callback propagates through normal
     * reporting and is never retried. Accepted work survives source consumption; cancellation is possible only
     * before execution starts, including before loading. Owner retirement or a level/lifecycle change while
     * loading skips the callback. The callback may only use the supplied owner, chunk and immutable captured
     * input; neither the ticket nor the LevelChunk reference may escape as later mutation authority.
     * <p>
     * This does not replace or replay the caller's whole tick. Calling it after catching a partially applied
     * operation is invalid. Callers must arrange their own continuation and acceptance/result semantics.
     */
    default EntityInteractionTask submitToOwnerAfterChunkLoad(Entity source, Entity owner, BlockPos position,
            BiConsumer<Entity, LevelChunk> action) {
        throw new UnsupportedOperationException("This interaction scheduler does not support chunk-backed owner operations");
    }
}
