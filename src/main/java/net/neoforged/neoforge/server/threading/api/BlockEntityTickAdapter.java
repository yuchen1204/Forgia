/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api;

import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-invocation admission of an audited native block-entity tick. Register during
 * {@code RegisterParallelAdaptersEvent}; this is stronger than a type allowlist and does not enable a slice.
 * <p>
 * The engine invokes the predicate immediately before the original ticker, under its region ownership.
 * Inspect only the supplied instance's owned fields and immutable metadata. Do not query foreign objects,
 * load chunks, invoke arbitrary callbacks, consume random numbers or mutate anything. Return a refusal
 * before any effects: the complete original tick then runs once on the server after workers return.
 * Exceptions are failures, never permission or a replay request. The supplied instance must not be retained.
 * <p>
 * An admitted tick must remain safe for its whole body, including inherited ticks, behaviours, lazy ticks
 * and third-party transformations. Registration is the adapter author's contract, not a safety proof.
 * Admission is never cached. Type eligibility, the interior-chunk rule and the existing whole-chunk
 * fallback for undeclared types are still cached. A transient refusal may defer an individual ticker.
 */
@ApiStatus.Experimental
@FunctionalInterface
public interface BlockEntityTickAdapter {
    /**
     * Optional bounded, read-only preflight, once per nonempty level batch on the quiescent server thread.
     * Inspect already-loaded resources only. Publish detached immutable metadata for this batch; never
     * retain the level or live participants as worker mutation authority. Do not load chunks or mutate.
     * Exceptions abort before any body. This hook must not schedule or wait for asynchronous work.
     */
    default void prepare(ServerLevel level) {}

    Decision check(BlockEntity blockEntity);

    /** Bounded diagnostic labels; none may encode a position, network or arbitrary user value. */
    enum Decision {
        ADMITTED, INITIALIZING, TOPOLOGY_CHANGE, CALLBACK_REQUIRED, UNKNOWN_CLASS, UNKNOWN_BEHAVIOUR,
        SHARED_RESOURCE, INVALID_INSTANCE, UNVERIFIED_TRANSFORMATIONS
    }

    /** Checks/refusals are invocation counts. Completions count actual successful native-worker bodies. */
    record Status(ResourceLocation id, Set<ResourceLocation> types, long checks, long refusals,
            long workerCompletions, long preparationNanos, long checkNanos, Map<Decision, Long> reasons) {
        public Status {
            types = Set.copyOf(types);
            reasons = Map.copyOf(reasons);
        }
    }
}
