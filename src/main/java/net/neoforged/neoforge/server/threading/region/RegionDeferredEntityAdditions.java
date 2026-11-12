/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Deferred admissions of one tick batch.
 * <p>
 * A worker that spawns an entity inside its leased region cannot admit it: the admission writes the level's
 * UUID set, its visible lookup, the entity section, the ticking list, the tracking and the region index. The
 * worker constructs and configures the entity - the position writes of a pending entity are field writes and
 * are granted - and records the whole riding graph, and the barrier runs the unmodified
 * {@code ServerLevel.addEntity} for every member on the server thread.
 * <p>
 * Both slices that can spawn an entity own one of these: the entity tick batch (a tick that spawns) and the
 * chunk tick batch (a random tick that hatches or otherwise spawns).
 * <p>
 * One entry contains the members of a riding graph that have not already been recorded. Shared members belong
 * to their earlier entry, so overlapping graphs never replay an admission. The per-member calls of
 * {@code addFreshEntityWithPassengers} are no-ops when the earlier entry already carries that member.
 */
@ApiStatus.Internal
final class RegionDeferredEntityAdditions {
    /**
     * One deferred admission: the entity the worker added and the as-yet unrecorded members of its riding graph,
     * in the order the barrier admits them. The root is kept for diagnostics; the graph is what the barrier admits.
     */
    record DeferredEntityAddition(Entity root, List<Entity> graph, Map<Entity, DeferredEntityAdmissionScope.Receipt> receipts) {
        @Nullable
        Throwable complete(Entity member, DeferredEntityAdmissionScope.Result result) {
            final var receipt = this.receipts.get(member);
            if (receipt == null) return null;
            try {
                receipt.complete(result);
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        }

        @Nullable
        Throwable completeRemaining(DeferredEntityAdmissionScope.Result result) {
            if (this.receipts.isEmpty()) return null;
            Throwable failure = null;
            for (final Entity member : this.graph) {
                final Throwable next = this.complete(member, result);
                if (next != null) {
                    if (failure == null) failure = next;
                    else if (failure != next) failure.addSuppressed(next);
                }
            }
            return failure;
        }
    }

    /**
     * One drain of the deferred admissions of a batch: the admissions to apply and the number of records the
     * batch accepted, which the caller counts as recorded.
     */
    record Drain(List<DeferredEntityAddition> additions, long recorded) {}

    private final RegionTickBatch batch;
    private final List<DeferredEntityAddition> additions = new ArrayList<>();
    /**
     * Every entity of an already recorded admission, by identity. It makes the per-entity calls of
     * {@code addFreshEntityWithPassengers} no-ops of the graph that already carries them instead of a second
     * record; guarded by the monitor of {@link #additions}.
     */
    private final Set<Entity> recordedMembers = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Admissions this batch recorded; guarded by the monitor of {@link #additions}. */
    private long recordedCount;
    /** Set when the engine shuts down; guarded by the monitor of {@link #additions}. */
    private volatile boolean seamClosed;

    RegionDeferredEntityAdditions(RegionTickBatch batch) {
        this.batch = batch;
    }

    /**
     * {@return true when this record no longer accepts or applies deferred admissions}
     * <p>
     * A batch that already completed its barrier, or whose seam was closed because the engine shut down,
     * cannot admit an entity, so {@link #defer(Entity, List)} refuses and the worker keeps the storage
     * refusal.
     */
    boolean isSeamClosed() {
        return this.seamClosed;
    }

    /**
     * Records the admission of one riding graph that a worker deferred to the barrier.
     *
     * @param entity the entity the caller is adding, the root of the recorded graph
     * @param graph  the root and its indirect passengers, in the order they have to be admitted
     * @return {@code false} when the seam was closed or the barrier already completed, so the admission can
     *         never be applied and the caller has to keep the storage refusal
     */
    boolean defer(Entity entity, List<Entity> graph) {
        synchronized (this.additions) {
            if (this.seamClosed || this.batch.isComplete()) {
                return false;
            }
            if (this.recordedMembers.contains(entity)) {
                return true;
            }
            // A mod can record a passenger first, then record a vehicle whose graph contains that passenger.
            // Replaying the passenger twice makes the barrier reject the entire second record as already added,
            // losing the vehicle. The earlier record owns the shared members; only new members belong here.
            final List<Entity> unrecorded = graph.stream().filter(member -> !this.recordedMembers.contains(member)).toList();
            Map<Entity, DeferredEntityAdmissionScope.Receipt> receipts = null;
            if (DeferredEntityAdmissionScope.isActive()) {
                // Validate the entire graph before transferring any receipt; a bad suffix must not orphan
                // earlier tickets when no admission record can be queued.
                for (final Entity member : unrecorded) DeferredEntityAdmissionScope.validateCapture(member);
                for (final Entity member : unrecorded) {
                    final var receipt = DeferredEntityAdmissionScope.capture(member);
                    if (receipt != null) {
                        if (receipts == null) receipts = new IdentityHashMap<>();
                        receipts.put(member, receipt);
                    }
                }
            }
            this.additions.add(new DeferredEntityAddition(entity, unrecorded,
                    receipts == null ? Map.of() : Collections.unmodifiableMap(receipts)));
            this.recordedMembers.addAll(unrecorded);
            this.recordedCount++;
            return true;
        }
    }

    /**
     * Stops accepting deferred admissions and releases the ones that already wait.
     * <p>
     * Called when the engine shuts down: the barrier of this batch will never admit an entity again, so a
     * worker that still ticks must be refused instead of granted, and the released records must not keep an
     * entity reference alive.
     *
     * @return the released admissions and the records this batch accepted
     */
    Drain close() {
        final Drain released;
        synchronized (this.additions) {
            this.seamClosed = true;
            released = this.drainLocked();
        }
        // Completion may acquire adapter accounting locks. Never run it under the deferred queue lock.
        for (final DeferredEntityAddition addition : released.additions()) {
            final Throwable failure = addition.completeRemaining(DeferredEntityAdmissionScope.Result.ABANDONED);
            if (failure != null) LogUtils.getLogger().error("Deferred admission receipt failed during shutdown cleanup", failure);
        }
        return released;
    }

    /** {@return the number of admissions that still wait for the barrier} */
    int pendingCount() {
        synchronized (this.additions) {
            return this.additions.size();
        }
    }

    /** Takes the deferred admissions that wait for the end of the batch. */
    Drain drain() {
        synchronized (this.additions) {
            return this.drainLocked();
        }
    }

    private Drain drainLocked() {
        final Drain drain = new Drain(List.copyOf(this.additions), this.recordedCount);
        this.additions.clear();
        this.recordedMembers.clear();
        this.recordedCount = 0L;
        return drain;
    }
}
