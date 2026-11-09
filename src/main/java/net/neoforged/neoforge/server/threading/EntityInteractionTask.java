/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/** Outcome of an entity interaction admitted before its side effects start. */
@ApiStatus.Experimental
public interface EntityInteractionTask {
    enum State {
        QUEUED, RUNNING, COMPLETED, SKIPPED, CANCELLED, REJECTED, FAILED
    }

    enum Reason {
        NONE, CANCELLED, ENTITY_REMOVED, LEVEL_CHANGED, LEVEL_CLOSED, SHUTDOWN, BATCH_CLOSED, QUEUE_FULL, INVALID_CONTEXT, UNOWNED_SOURCE
    }

    State state();

    Reason reason();

    /** Cancels only work which has not started. A successful cancellation guarantees the callback will not run. */
    boolean cancel();

    /** The callback's failure, if any. Failures also follow the engine's normal crash-reporting path. */
    Optional<Throwable> failure();

    default boolean isDone() {
        return this.state() != State.QUEUED && this.state() != State.RUNNING;
    }
}
