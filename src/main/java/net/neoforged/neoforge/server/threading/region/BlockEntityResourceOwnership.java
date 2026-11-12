/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One wave's nonblocking, subordinate resource ownership. Only the coordinator holding the outer region
 * leases may construct it. Claims describe a complete audited operation, never an observed access history.
 * The package-private minting surface is an internal protocol, not an adapter permission registration API.
 */
@ApiStatus.Internal
public final class BlockEntityResourceOwnership implements AutoCloseable {
    public static final int MAX_RESOURCES_PER_UNIT = 512;
    public static final int MAX_BINDINGS_PER_PHASE = 131072;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final Object domain;
    private final Map<Object, BooleanSupplier> boundaries = new IdentityHashMap<>();
    private final BooleanSupplier live;
    private final Thread coordinator = Thread.currentThread();
    private final AtomicInteger activeScopes = new AtomicInteger();
    private volatile boolean open = true;
    private volatile boolean reserved;
    private List<Lease> leases = List.of();

    BlockEntityResourceOwnership(Object domain, Map<Object, BooleanSupplier> heldRegions, BooleanSupplier live) {
        this.domain = Objects.requireNonNull(domain);
        heldRegions.forEach((region, current) -> this.boundaries.put(Objects.requireNonNull(region), Objects.requireNonNull(current)));
        this.live = Objects.requireNonNull(live);
    }

    public enum Access {
        READ, WRITE
    }

    /** Actual object identity in an actual level/domain. Views, coordinates and user equals() do not split it. */
    public static final class Identity {
        private final Object domain;
        private final Object instance;

        public Identity(Object domain, Object instance) {
            this.domain = Objects.requireNonNull(domain);
            this.instance = Objects.requireNonNull(instance);
        }

        BlockEntityWorkPlanner.Resource plannerKey() {
            return new BlockEntityWorkPlanner.Resource(this.domain, this.instance);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity && this.domain == identity.domain && this.instance == identity.instance;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(this.domain) + System.identityHashCode(this.instance);
        }
    }

    /**
     * Attachment is separate from identity. Null home denotes an audited shared read publication, never a
     * writable global resource. All validators are bounded, pure, current-operation core proofs; they run
     * only on the coordinator before publication, not as arbitrary callbacks during worker execution.
     */
    public record Binding(Identity identity, @Nullable Object home, BooleanSupplier current, @Nullable BlockPos position) {
        public Binding {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(current);
            if (position != null) position = position.immutable();
        }
    }

    /** Immutable bounded proof input. Every attachment validator survives duplicate-resource normalization. */
    public static final class Claims {
        private final Object domain;
        private final Object home;
        private final Map<Identity, Access> access;
        private final List<Binding> bindings;
        private final boolean complete;

        private Claims(Object domain, Object home, Map<Identity, Access> access, List<Binding> bindings, boolean complete) {
            this.domain = domain;
            this.home = home;
            this.access = Map.copyOf(access);
            this.bindings = List.copyOf(bindings);
            this.complete = complete;
        }

        public static Builder builder(Object domain, Object home) {
            return new Builder(domain, home);
        }

        public static Claims fence(Object domain, Object home) {
            return new Claims(domain, home, Map.of(), List.of(), false);
        }

        public boolean complete() {
            return this.complete;
        }

        int resourceCount() {
            return this.bindings.size();
        }

        public boolean sameResources(Claims other) {
            return this.complete && other.complete && this.domain == other.domain && this.home == other.home && this.access.equals(other.access);
        }

        public BlockEntityWorkPlanner.Unit unit() {
            if (!this.complete) return new BlockEntityWorkPlanner.Unit(Set.of(), Set.of(), true);
            final Set<BlockEntityWorkPlanner.Resource> reads = new HashSet<>();
            final Set<BlockEntityWorkPlanner.Resource> writes = new HashSet<>();
            this.access.forEach((identity, access) -> (access == Access.WRITE ? writes : reads).add(identity.plannerKey()));
            return new BlockEntityWorkPlanner.Unit(reads, writes, false);
        }

        public static final class Builder {
            private final Object domain;
            private final Object home;
            private final Map<Identity, Access> access = new LinkedHashMap<>();
            private final List<Binding> bindings = new ArrayList<>();
            private boolean complete = true;

            private Builder(Object domain, Object home) {
                this.domain = Objects.requireNonNull(domain);
                this.home = Objects.requireNonNull(home);
            }

            public Builder claim(Binding binding, Access mode) {
                Objects.requireNonNull(binding);
                Objects.requireNonNull(mode);
                if (this.bindings.size() >= MAX_RESOURCES_PER_UNIT || binding.identity.domain != this.domain
                        || (binding.home != null && binding.home != this.home) || (binding.home == null && mode == Access.WRITE)) {
                    this.complete = false;
                    return this;
                }
                this.bindings.add(binding);
                this.access.merge(binding.identity, mode, (before, after) -> before == Access.WRITE ? before : after);
                return this;
            }

            public Claims build() {
                // A local writable object aliasing a shared publication is not a region-local write.
                for (final Binding binding : this.bindings) {
                    if (binding.home == null && this.access.get(binding.identity) == Access.WRITE) this.complete = false;
                }
                final Map<BlockPos, Identity> positions = new HashMap<>();
                for (final Binding binding : this.bindings) {
                    if (binding.position != null) {
                        final Identity previous = positions.putIfAbsent(binding.position, binding.identity);
                        if (previous != null && !previous.equals(binding.identity)) this.complete = false;
                    }
                }
                return new Claims(this.domain, this.home, this.access, this.bindings, this.complete && !this.access.isEmpty());
            }
        }
    }

    public record Plan(List<Claims> claims, BlockEntityWorkPlanner.Plan dependencies) {
        public Plan {
            claims = List.copyOf(claims);
        }
    }

    /** Refuse inconsistent homes/global write aliases across the entire ordered phase, then reuse the DAG. */
    public static Plan plan(List<Claims> input) {
        if (input.size() > RegionBlockEntityWorkGraph.MAX_UNITS) throw new IllegalArgumentException("Resource proof unit budget exceeded");
        int bindings = 0;
        for (final Claims claims : input) {
            bindings += claims.resourceCount();
            if (bindings > MAX_BINDINGS_PER_PHASE) throw new IllegalArgumentException("Resource proof phase budget exceeded");
        }
        final Map<Identity, Object> homes = new HashMap<>();
        final Set<Identity> shared = new HashSet<>();
        final Set<Identity> writes = new HashSet<>();
        final Set<Identity> invalid = new HashSet<>();
        for (final Claims claims : input) {
            if (!claims.complete) continue;
            claims.access.forEach((identity, mode) -> {
                if (mode == Access.WRITE) writes.add(identity);
            });
            for (final Binding binding : claims.bindings) {
                if (binding.home == null) shared.add(binding.identity);
                else {
                    final Object previous = homes.putIfAbsent(binding.identity, binding.home);
                    if (previous != null && previous != binding.home) invalid.add(binding.identity);
                }
            }
        }
        for (final Identity identity : shared) if (writes.contains(identity)) invalid.add(identity);
        final List<Claims> effective = new ArrayList<>(input.size());
        final List<BlockEntityWorkPlanner.Unit> units = new ArrayList<>(input.size());
        for (final Claims claims : input) {
            final Claims selected = claims.complete && !Collections.disjoint(claims.access.keySet(), invalid)
                    ? Claims.fence(claims.domain, claims.home)
                    : claims;
            effective.add(selected);
            units.add(selected.unit());
        }
        return new Plan(effective, BlockEntityWorkPlanner.plan(units));
    }

    /**
     * Reserve a complete wave, all-or-nothing, without waiting. It can succeed only once. No live leases
     * escape on a refusal, and no worker is published before all conflicts, bindings and boundaries pass.
     */
    @Nullable
    List<Lease> tryAcquireAll(List<Claims> requests) {
        this.checkCoordinator();
        if (!this.open || this.reserved || requests.isEmpty() || requests.size() > RegionBlockEntityWorkGraph.MAX_UNITS || !this.live.getAsBoolean()) return null;
        final Map<Identity, Access> occupied = new HashMap<>();
        final Map<Identity, Object> homes = new HashMap<>();
        final Set<Identity> shared = new HashSet<>();
        int bindings = 0;
        for (final Claims claims : requests) {
            bindings += claims.resourceCount();
            if (bindings > MAX_BINDINGS_PER_PHASE) return null;
            final BooleanSupplier boundary = this.boundaries.get(claims.home);
            if (!claims.complete || claims.domain != this.domain || boundary == null || !boundary.getAsBoolean()) return null;
            for (final var entry : claims.access.entrySet()) {
                final Access previous = occupied.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null && (previous == Access.WRITE || entry.getValue() == Access.WRITE)) return null;
            }
            for (final Binding binding : claims.bindings) {
                if (binding.home == null) shared.add(binding.identity);
                else {
                    final Object previous = homes.putIfAbsent(binding.identity, binding.home);
                    if (previous != null && previous != binding.home) return null;
                }
            }
        }
        for (final Identity identity : shared) if (occupied.get(identity) == Access.WRITE) return null;
        for (final Claims claims : requests) {
            for (final Binding binding : claims.bindings) if (!binding.current.getAsBoolean()) return null;
        }
        // A pure validator may have observed a lifecycle transition; never publish a partial proof.
        if (!this.live.getAsBoolean()) return null;
        for (final Claims claims : requests) if (!this.boundaries.get(claims.home).getAsBoolean()) return null;
        final List<Lease> granted = new ArrayList<>(requests.size());
        for (final Claims claims : requests) granted.add(new Lease(this, claims, this.boundaries.get(claims.home)));
        this.leases = List.copyOf(granted);
        this.reserved = true;
        return this.leases;
    }

    private void checkCoordinator() {
        if (Thread.currentThread() != this.coordinator) throw new IllegalStateException("Resource reservation/release requires its region-lease coordinator");
    }

    @Override
    public void close() {
        this.checkCoordinator();
        if (!this.open) throw new IllegalStateException("Resource ownership already closed");
        this.open = false;
        if (this.activeScopes.get() != 0) {
            this.open = true;
            throw new IllegalStateException("Cannot release resources while an ownership scope is active");
        }
        for (final Lease lease : this.leases) lease.release();
        this.boundaries.clear();
    }

    /** One-shot invocation authority. Holding a handle alone grants no access outside its entered scope. */
    public static final class Lease {
        private static final int RESERVED = 0;
        private static final int ENTERED = 1;
        private static final int CONSUMED = 2;
        private static final int RELEASED = 3;
        private final BlockEntityResourceOwnership owner;
        private final Object domain;
        private final BooleanSupplier boundary;
        private final Map<Object, Access> permissions = new IdentityHashMap<>();
        private final Set<Object> local = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<BlockPos, Object> references = new HashMap<>();
        private final AtomicInteger state = new AtomicInteger(RESERVED);

        private Lease(BlockEntityResourceOwnership owner, Claims claims, BooleanSupplier boundary) {
            this.owner = owner;
            this.domain = claims.domain;
            this.boundary = boundary;
            claims.access.forEach((identity, access) -> this.permissions.put(identity.instance, access));
            for (final Binding binding : claims.bindings) {
                if (binding.home != null) this.local.add(binding.identity.instance);
                if (binding.position != null) this.references.put(binding.position, binding.identity.instance);
            }
        }

        Scope enter() {
            if (CURRENT.get() != null) throw new IllegalStateException("Nested resource ownership scopes are forbidden");
            this.owner.activeScopes.incrementAndGet();
            if (!this.owner.open || !this.owner.reserved || !this.state.compareAndSet(RESERVED, ENTERED)) {
                this.owner.activeScopes.decrementAndGet();
                throw new IllegalStateException("Resource lease is closed, foreign or already consumed");
            }
            try {
                if (!this.owner.open || !this.boundary.getAsBoolean()) throw new IllegalStateException("Resource lease lost its outer region exclusion");
                final Scope scope = new Scope(this);
                CURRENT.set(scope);
                return scope;
            } catch (Throwable failure) {
                this.state.set(CONSUMED);
                this.owner.activeScopes.decrementAndGet();
                throw failure;
            }
        }

        private void release() {
            final int before = this.state.get();
            if (before == ENTERED || !this.state.compareAndSet(before, RELEASED)) throw new IllegalStateException("Cannot release a running resource lease");
        }
    }

    /** Fine-grained permission, always subordinate to the coordinator's still-held region exclusion. */
    public static final class Scope implements AutoCloseable {
        private final Lease lease;
        private boolean closed;

        private Scope(Lease lease) {
            this.lease = lease;
        }

        public static boolean active() {
            return CURRENT.get() != null;
        }

        public static void require(Object domain, Object instance, Access requested) {
            Objects.requireNonNull(requested);
            final Scope scope = CURRENT.get();
            if (scope == null) throw new IllegalStateException("No block-entity resource ownership scope");
            final Lease lease = scope.lease;
            if (scope.closed || domain != lease.domain || !lease.boundary.getAsBoolean())
                throw new IllegalStateException("Resource access exceeded its level/region/lifetime boundary");
            final Access granted = lease.permissions.get(instance);
            if (granted == null || (requested == Access.WRITE && granted != Access.WRITE)) throw new IllegalStateException("Undeclared block-entity resource access");
        }

        /** Live BE lookups expose mutable objects and therefore require WRITE, not a read-only promise. */
        public static void checkReference(Level level, BlockPos position) {
            final Scope scope = CURRENT.get();
            if (scope == null) return;
            final Object instance = scope.lease.references.get(position);
            if (instance == null) throw new IllegalStateException("Undeclared block-entity reference");
            require(level, instance, Access.WRITE);
        }

        public static boolean checkChunk(ChunkAccess chunk, boolean write) {
            final Scope scope = CURRENT.get();
            if (scope == null) return false;
            if (!scope.lease.local.contains(chunk)) throw new IllegalStateException("Shared publication claims grant no foreign chunk access");
            if (!(chunk instanceof LevelChunk loaded)) throw new IllegalStateException("Uncaptured chunk implementation");
            require(loaded.getLevel(), loaded, write ? Access.WRITE : Access.READ);
            return true;
        }

        public static void rejectEffect(String operation) {
            if (CURRENT.get() != null) throw new IllegalStateException("Resource-owned work has no effect protocol for " + operation);
        }

        @Override
        public void close() {
            if (this.closed || CURRENT.get() != this) throw new IllegalStateException("Resource ownership scope closed on the wrong thread or out of order");
            this.closed = true;
            CURRENT.remove();
            this.lease.state.set(Lease.CONSUMED);
            this.lease.owner.activeScopes.decrementAndGet();
        }
    }
}
