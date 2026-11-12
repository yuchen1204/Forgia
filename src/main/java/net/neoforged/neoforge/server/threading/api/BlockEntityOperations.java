/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Complete two-block-entity operations, submitted before changing either participant.
 * <p>
 * Capture references on the quiescent server thread or for the exact admitted block entity currently ticking
 * on a native worker. References identify an instance and position; they expose no live entity or lease and
 * never force chunks to load. Obtain a foreign target reference before worker execution and publish it as
 * immutable input. References belong to this server service and cannot be reused after it stops.
 * <p>
 * Native submission requires the exact source ticker's ownership. Its operation runs on the server after all
 * workers of that block-entity batch return, after deferred publications/removals/scheduled mutations and
 * before serial fallback.
 * A quiescent server submission executes immediately, also when region threading is disabled. Service callbacks
 * while workers are active and unrelated async/worker threads are rejected. Never block a worker on a task.
 * <p>
 * Both identities, positions, levels and loaded chunks are revalidated before invocation. Acceptance promises
 * at-most-once invocation, not completion, rollback, a synchronous transfer result or global tick ordering.
 * A callback must use its supplied participants and immutable captured input. It must not capture unrelated
 * live objects, retain the supplied participants as future mutation authority, or retry partially applied work.
 * Exceptions remain failures and are also reported through the calling server/batch's normal failure path.
 */
@ApiStatus.Experimental
public interface BlockEntityOperations {
    /** Throws for an invalid capture context, removed entity, unavailable chunk or stopped service. */
    Reference reference(BlockEntity blockEntity);

    <R> Task<R> submit(Reference source, Reference target, Operation<R> operation);

    /**
     * Transfers at most the requested item count between two Container slots. Sided containers require a side
     * and respect their exposed slots and insertion/extraction checks. Unavailable/replaced participants skip
     * the task; unsupported containers and blocked moves complete with a zero-move result. Does not adapt
     * arbitrary IItemHandler networks. Custom Container methods must obey their contracts; a throwing or
     * inconsistent implementation can leave partial effects, which are reported and never retried.
     */
    Task<TransferResult> transfer(Reference source, Reference target, Transfer request);

    Counters counters();

    /** Opaque instance reference created by this service; implementing this interface does not mint a reference. */
    interface Reference {
        ResourceKey<Level> dimension();

        BlockPos position();

        ResourceLocation type();
    }

    @FunctionalInterface
    interface Operation<R> {
        /** Result must be detached immutable data. A null result is allowed; inspect state to distinguish completion. */
        @Nullable
        R execute(BlockEntity source, BlockEntity target);
    }

    interface Task<R> {
        State state();

        Reason reason();

        /** A successful cancellation guarantees the operation has not started and will not be invoked. */
        boolean cancel();

        /** Present only after successful completion with a non-null result. */
        Optional<R> result();

        Optional<Throwable> failure();

        default boolean isDone() {
            final State current = this.state();
            return current != State.QUEUED && current != State.RUNNING;
        }
    }

    enum State {
        QUEUED, RUNNING, COMPLETED, SKIPPED, CANCELLED, REJECTED, FAILED
    }

    enum Reason {
        NONE, CANCELLED, INVALID_REFERENCE, INVALID_CONTEXT, UNOWNED_SOURCE, BLOCK_ENTITY_REMOVED,
        SOURCE_REPLACED, TARGET_REPLACED, LEVEL_CHANGED, CHUNK_UNAVAILABLE, LEVEL_CLOSED, SHUTDOWN,
        BATCH_CLOSED, QUEUE_FULL
    }

    record Transfer(int sourceSlot, int targetSlot, int maxItems, @Nullable Direction extractionSide, @Nullable Direction insertionSide) {
        public Transfer {
            if (sourceSlot < 0 || targetSlot < 0 || maxItems <= 0 || maxItems > 64) {
                throw new IllegalArgumentException("Transfer slots must be nonnegative and maxItems must be in 1..64");
            }
        }
    }

    enum TransferStatus {
        MOVED, NO_ITEMS, DESTINATION_FULL, ITEM_REJECTED, INVALID_SLOT, SIDE_REQUIRED, UNSUPPORTED_CONTAINER, SAME_CONTAINER
    }

    record TransferResult(int moved, TransferStatus status) {}

    /** Cumulative requests and callback wall time. Reason labels are bounded by the enum, never positions/types. */
    record Counters(long accepted, long completed, long skipped, long cancelled, long rejected, long failed,
            long callbackNanos, Map<Reason, Long> reasons) {
        public Counters {
            reasons = Map.copyOf(reasons);
        }
    }
}
