/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.threading.region.DeferredEntityAdmissionScope;
import org.jetbrains.annotations.ApiStatus;

/**
 * Plan-owned accounting for nearby spawn limits. Inputs are detached values captured while the server is
 * quiescent; this object never queries a worker's region-clipped entity view or reads another worker's entity.
 *
 * This is an internal building block, not permission to enable an adapter. Its owner must account for every
 * relevant admission, removal and bounding-box change, and resolve tickets using the actual admission result.
 * A successful deferred-add enqueue is not an admission result. Closing a plan releases all its data.
 */
@ApiStatus.Internal
public final class NearbySpawnReservations implements AutoCloseable {
    /** Includes living entities regardless of health; the upstream query does not filter by isAlive. */
    public record Sample(UUID id, ResourceLocation type, AABB bounds) {
        public Sample {
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
            Objects.requireNonNull(bounds);
            requireFinite(bounds);
        }
    }

    /** The query is a block-sized box inflated in all three axes, not a sphere around entity coordinates. */
    public record Limit(BlockPos position, int maximum, double radius) {
        public Limit {
            position = Objects.requireNonNull(position).immutable();
            if (maximum < 0 || !Double.isFinite(radius) || radius < 1.0 || radius > 256.0) {
                throw new IllegalArgumentException("Unsupported nearby spawn limit");
            }
        }

        public AABB area() {
            return new AABB(this.position).inflate(this.radius);
        }
    }

    /** A ticket belongs to exactly one ledger and can be resolved exactly once. */
    public static final class Ticket {
        private final NearbySpawnReservations owner;
        private final Sample sample;
        private final Limit limit;
        private boolean resolved;

        private Ticket(NearbySpawnReservations owner, Sample sample, Limit limit) {
            this.owner = owner;
            this.sample = sample;
            this.limit = limit;
        }

        /**
         * Use as the completion of a DeferredEntityAdmissionScope receipt. FAILED/UNOBSERVED invalidate the
         * entire view: an admission may have entered storage or followed a different path. Closing a plan makes late
         * shutdown notifications harmless; direct double resolution on an open plan remains an error.
         * This only resolves accounting, not admission ordering or candidate geometry validation.
         */
        public void completeAdmission(DeferredEntityAdmissionScope.Result result) {
            this.owner.completeAdmission(this, Objects.requireNonNull(result));
        }
    }

    private final Map<ResourceLocation, Map<UUID, Sample>> admitted = new HashMap<>();
    private final Map<UUID, Ticket> pending = new HashMap<>();
    private final Map<UUID, ResourceLocation> identities = new HashMap<>();
    private boolean closed;

    /** Caller supplies a complete level-wide view, never a lease-clipped worker query. */
    public NearbySpawnReservations(Collection<Sample> existing) {
        for (final Sample sample : existing) {
            Objects.requireNonNull(sample);
            if (this.identities.putIfAbsent(sample.id(), sample.type()) != null) {
                throw new IllegalArgumentException("Duplicate entity identity in nearby snapshot");
            }
            this.admitted.computeIfAbsent(sample.type(), key -> new HashMap<>()).put(sample.id(), sample);
        }
    }

    /**
     * Atomically checks the candidate's window and reserves its bounding box. Different radii are evaluated
     * separately, exactly as successive candidate queries would be; intersecting windows are not merged into
     * a single region quota. No RNG, event or world mutation is performed while holding this monitor.
     */
    public synchronized Optional<Ticket> reserve(Sample candidate, Limit limit) {
        this.requireOpen();
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(limit);
        if (this.identities.containsKey(candidate.id()) || this.pending.containsKey(candidate.id())) {
            throw new IllegalArgumentException("Entity identity already accounted for");
        }
        if (this.count(candidate.type(), limit.area()) >= limit.maximum()) {
            return Optional.empty();
        }
        final Ticket ticket = new Ticket(this, candidate, limit);
        this.pending.put(candidate.id(), ticket);
        return Optional.of(ticket);
    }

    private synchronized void completeAdmission(Ticket ticket, DeferredEntityAdmissionScope.Result result) {
        if (this.closed) return;
        this.requireTicket(ticket);
        switch (result) {
            case ADMITTED -> this.commit(ticket);
            case REJECTED, ABANDONED -> this.cancel(ticket);
            case FAILED, UNOBSERVED -> this.close();
        }
    }

    /**
     * Call after finalization cancellation, a rejected join or an abandoned deferred addition.
     * If the real admission threw, use completeAdmission(FAILED) instead: the world may already contain it.
     * Closing this ledger also releases unresolved tickets. Double resolution is an integration error.
     */
    public synchronized void cancel(Ticket ticket) {
        this.requireTicket(ticket);
        this.pending.remove(ticket.sample.id());
        ticket.resolved = true;
    }

    /**
     * Validate position/type/box before invoking the real admission. An entity changed by another listener
     * cannot silently consume a slot reserved for different geometry. The owner must cancel on false and
     * defer/reject safely; it must never replay a finalizer with side effects.
     */
    public synchronized boolean matches(Ticket ticket, Sample actual, Limit actualLimit) {
        this.requireTicket(ticket);
        return ticket.sample.equals(actual) && ticket.limit.equals(actualLimit);
    }

    /**
     * Commit only after the real admission succeeded. Reserved geometry must already have been checked with
     * matches immediately before admission. The count is unchanged: the pending sample becomes admitted.
     */
    public synchronized void commit(Ticket ticket) {
        this.requireTicket(ticket);
        this.pending.remove(ticket.sample.id());
        final Sample sample = ticket.sample;
        this.identities.put(sample.id(), sample.type());
        this.admitted.computeIfAbsent(sample.type(), key -> new HashMap<>()).put(sample.id(), sample);
        ticket.resolved = true;
    }

    /**
     * Account for an admitted entity that did not have a nearby rule (for example a secondary spawn). Such
     * entities still contribute to later candidates' queries. Must be called in admission order, and never
     * while a concurrent reservation can miss an admission that has already become visible in the world.
     */
    public synchronized void admitUnrestricted(Sample sample) {
        this.requireOpen();
        Objects.requireNonNull(sample);
        if (this.identities.containsKey(sample.id()) || this.pending.containsKey(sample.id())) {
            throw new IllegalArgumentException("Entity identity already accounted for");
        }
        this.identities.put(sample.id(), sample.type());
        this.admitted.computeIfAbsent(sample.type(), key -> new HashMap<>()).put(sample.id(), sample);
    }

    /** The owner must forward removals/moves during the plan; an initial snapshot alone is insufficient. */
    public synchronized void remove(UUID id) {
        this.requireOpen();
        Objects.requireNonNull(id);
        if (this.pending.containsKey(id)) {
            throw new IllegalStateException("Resolve a pending entity with its admission ticket");
        }
        final ResourceLocation type = this.identities.remove(id);
        if (type != null) {
            final Map<UUID, Sample> bucket = this.admitted.get(type);
            bucket.remove(id);
            if (bucket.isEmpty()) this.admitted.remove(type);
        }
    }

    /** Updates an already admitted entity after an ownership-safe position/bounding-box publication. */
    public synchronized void update(Sample sample) {
        this.requireOpen();
        Objects.requireNonNull(sample);
        final ResourceLocation previous = this.identities.get(sample.id());
        if (previous == null || !previous.equals(sample.type())) {
            throw new IllegalArgumentException("Unknown or changed entity type");
        }
        this.admitted.get(previous).put(sample.id(), sample);
    }

    public synchronized int count(ResourceLocation type, AABB area) {
        this.requireOpen();
        Objects.requireNonNull(type);
        requireFinite(Objects.requireNonNull(area));
        int count = 0;
        for (final Sample sample : this.admitted.getOrDefault(type, Map.of()).values()) {
            if (sample.bounds().intersects(area)) count++;
        }
        for (final Ticket ticket : this.pending.values()) {
            final Sample sample = ticket.sample;
            if (sample.type().equals(type) && sample.bounds().intersects(area)) count++;
        }
        return count;
    }

    private void requireTicket(Ticket ticket) {
        this.requireOpen();
        Objects.requireNonNull(ticket);
        if (ticket.owner != this || ticket.resolved || this.pending.get(ticket.sample.id()) != ticket) {
            throw new IllegalStateException("Foreign or resolved nearby admission ticket");
        }
    }

    private void requireOpen() {
        if (this.closed) throw new IllegalStateException("Nearby spawn plan is closed");
    }

    private static void requireFinite(AABB box) {
        if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)) {
            throw new IllegalArgumentException("Non-finite entity geometry");
        }
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.admitted.clear();
        this.pending.clear();
        this.identities.clear();
    }
}
