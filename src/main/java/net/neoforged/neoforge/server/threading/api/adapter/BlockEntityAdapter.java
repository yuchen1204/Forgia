/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api.adapter;

import org.jetbrains.annotations.ApiStatus;

/**
 * Read-only inspection of a complete original block-entity invocation. Register through the existing
 * server-scoped ParallelApi registrar after checking GENERAL_BLOCK_ENTITY_ADAPTERS. No execution,
 * dispatch, Runnable, commit or worker-pool surface is provided. The core invokes inspect inside the
 * original serial Permit at the actual canonical cursor, after physical join and necessary effects.
 * A request/refusal never splits, replaces or replays the original ticker. Exceptions abort the phase;
 * they are not successful refusals. C5 keeps every registered target serial, with an explicit reason.
 */
@ApiStatus.Experimental
@FunctionalInterface
public interface BlockEntityAdapter {
    BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation);

    /**
     * Release adapter-owned inspection snapshots after all phase work has physically joined and required
     * effects committed. Invoked once for each adapter whose inspect was entered, even if it threw or was
     * unregistered. No world/authority is supplied. Throwing preserves failure while all core cleanup and
     * other adapters' cleanup still run. Do not start work, mutate worlds or wait for asynchronous work.
     */
    default void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {}
}
