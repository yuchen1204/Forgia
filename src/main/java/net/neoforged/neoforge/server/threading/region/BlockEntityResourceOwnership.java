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

    /**
     * An immutable, explicitly shared READ publication captured by one fresh preparation pass. Its one
     * pure predicate proves the whole captured publication, not arbitrary per-object attachments. It is
     * rechecked on every reservation; sharing storage never subtracts from logical binding budgets.
     */
    public static final class SharedReads {
        private final Object domain;
        private final Set<Identity> identities;
        private final Set<Object> instances;
        private final BooleanSupplier current;
        private final int count;

        private SharedReads(Object domain, List<?> instances, BooleanSupplier current) {
            this.domain = Objects.requireNonNull(domain);
            this.current = Objects.requireNonNull(current);
            this.count = Math.min(instances.size(), MAX_RESOURCES_PER_UNIT + 1);
            final Set<Identity> identities = new HashSet<>();
            this.instances = Collections.newSetFromMap(new IdentityHashMap<>());
            if (this.count <= MAX_RESOURCES_PER_UNIT) {
                for (int index = 0; index < this.count; ++index) {
                    final Object instance = instances.get(index);
                    identities.add(new Identity(domain, instance));
                    this.instances.add(instance);
                }
            }
            this.identities = Set.copyOf(identities);
        }

        public static SharedReads capture(Object domain, List<?> instances, BooleanSupplier current) {
            return new SharedReads(domain, instances, current);
        }
    }

    /** Immutable bounded proof input. Every attachment validator survives duplicate-resource normalization. */
    public static final class Claims {
        private final Object domain;
        private final Object home;
        private final Map<Identity, Access> access;
        private final List<Binding> bindings;
        private final SharedReads shared;
        private final Map<Object, Access> permissions;
        private final boolean complete;

        private Claims(Object domain, Object home, Map<Identity, Access> access, List<Binding> bindings, @Nullable SharedReads shared, boolean complete) {
            this.domain = domain;
            this.home = home;
            this.access = Map.copyOf(access);
            this.bindings = List.copyOf(bindings);
            this.shared = shared;
            this.permissions = access.size() <= 8 ? null : new IdentityHashMap<>();
            if (this.permissions != null) access.forEach((identity, mode) -> this.permissions.put(identity.instance, mode));
            this.complete = complete;
        }

        public static Builder builder(Object domain, Object home) {
            return new Builder(domain, home);
        }

        public static Claims fence(Object domain, Object home) {
            return new Claims(domain, home, Map.of(), List.of(), null, false);
        }

        public boolean complete() {
            return this.complete;
        }

        int resourceCount() {
            return this.bindings.size() + (this.shared == null ? 0 : this.shared.count);
        }

        private Access permission(Object instance) {
            if (this.permissions != null) {
                final Access local = this.permissions.get(instance);
                if (local != null) return local;
            } else {
                for (final var entry : this.access.entrySet()) if (entry.getKey().instance == instance) return entry.getValue();
            }
            return this.shared != null && this.shared.instances.contains(instance) ? Access.READ : null;
        }

        public boolean sameResources(Claims other) {
            if (this.shared == null && other.shared == null) {
                return this.complete && other.complete && this.domain == other.domain && this.home == other.home && this.access.equals(other.access);
            }
            return this.sameResources(other, new IdentityHashMap<>());
        }

        /** Memoizes only equality of immutable captured sets, never validation of mutable world state. */
        boolean sameResources(Claims other, Map<SharedReads, SharedReads> compared) {
            if (!this.complete || !other.complete || this.domain != other.domain || this.home != other.home) return false;
            if (this.access.equals(other.access)) {
                if (this.shared == other.shared) return true;
                if (this.shared != null && other.shared != null) {
                    if (compared.get(this.shared) == other.shared) return true;
                    if (this.shared.identities.equals(other.shared.identities)) {
                        compared.put(this.shared, other.shared);
                        return true;
                    }
                }
            }
            // Expanded/grouped representations can describe the same normalized permissions.
            return this.unit().equals(other.unit());
        }

        public BlockEntityWorkPlanner.Unit unit() {
            return this.unit(true);
        }

        private BlockEntityWorkPlanner.Unit unit(boolean includeShared) {
            if (!this.complete) return new BlockEntityWorkPlanner.Unit(Set.of(), Set.of(), true);
            final Set<BlockEntityWorkPlanner.Resource> reads = new HashSet<>();
            final Set<BlockEntityWorkPlanner.Resource> writes = new HashSet<>();
            this.access.forEach((identity, access) -> (access == Access.WRITE ? writes : reads).add(identity.plannerKey()));
            if (includeShared && this.shared != null) {
                for (final Identity identity : this.shared.identities) if (!this.access.containsKey(identity)) reads.add(identity.plannerKey());
            }
            return new BlockEntityWorkPlanner.Unit(reads, writes, false);
        }

        public static final class Builder {
            private final Object domain;
            private final Object home;
            private final Map<Identity, Access> access = new LinkedHashMap<>();
            private final List<Binding> bindings = new ArrayList<>();
            private SharedReads shared;
            private boolean complete = true;

            private Builder(Object domain, Object home) {
                this.domain = Objects.requireNonNull(domain);
                this.home = Objects.requireNonNull(home);
            }

            public Builder claim(Binding binding, Access mode) {
                Objects.requireNonNull(binding);
                Objects.requireNonNull(mode);
                if (this.bindings.size() + (this.shared == null ? 0 : this.shared.count) >= MAX_RESOURCES_PER_UNIT || binding.identity.domain != this.domain
                        || (binding.home != null && binding.home != this.home) || (binding.home == null && mode == Access.WRITE)) {
                    this.complete = false;
                    return this;
                }
                this.bindings.add(binding);
                this.access.merge(binding.identity, mode, (before, after) -> before == Access.WRITE ? before : after);
                return this;
            }

            /** One shared publication per unit; merge its resources during fresh preparation, not here. */
            public Builder sharedReads(SharedReads shared) {
                Objects.requireNonNull(shared);
                if (this.shared != null || shared.domain != this.domain || shared.count + this.bindings.size() > MAX_RESOURCES_PER_UNIT) this.complete = false;
                if (this.shared == null) this.shared = shared;
                return this;
            }

            public Claims build() {
                if (this.shared != null) {
                    for (final var entry : this.access.entrySet()) {
                        if (entry.getValue() == Access.WRITE && this.shared.instances.contains(entry.getKey().instance)) this.complete = false;
                    }
                }
                // A local writable object aliasing a shared publication is not a region-local write.
                for (final Binding binding : this.bindings) {
                    if (binding.home == null && this.access.get(binding.identity) == Access.WRITE) this.complete = false;
                }
                Map<BlockPos, Identity> positions = null;
                for (final Binding binding : this.bindings) {
                    if (binding.position != null) {
                        if (positions == null) positions = new HashMap<>();
                        final Identity previous = positions.putIfAbsent(binding.position, binding.identity);
                        if (previous != null && !previous.equals(binding.identity)) this.complete = false;
                    }
                }
                return new Claims(this.domain, this.home, this.access, this.bindings, this.shared,
                        this.complete && (!this.access.isEmpty() || (this.shared != null && !this.shared.identities.isEmpty())));
            }
        }
    }

    public record Plan(List<Claims> claims, BlockEntityWorkPlanner.ExecutionPlan dependencies) {
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
        final Set<SharedReads> groups = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Claims claims : input) {
            if (!claims.complete) continue;
            if (claims.shared != null && groups.add(claims.shared)) shared.addAll(claims.shared.identities);
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
        final Set<SharedReads> invalidGroups = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final SharedReads group : groups) if (!Collections.disjoint(group.identities, invalid)) invalidGroups.add(group);
        final List<Claims> effective = new ArrayList<>(input.size());
        final List<BlockEntityWorkPlanner.Unit> units = new ArrayList<>(input.size());
        for (final Claims claims : input) {
            final Claims selected = claims.complete && (!Collections.disjoint(claims.access.keySet(), invalid) || invalidGroups.contains(claims.shared))
                    ? Claims.fence(claims.domain, claims.home)
                    : claims;
            effective.add(selected);
            // Shared publications cannot be written anywhere in an accepted phase. After the whole-phase
            // alias check they add no dependency edges; do not expand N identical sets into N planner units.
            units.add(selected.unit(false));
        }
        return new Plan(effective, BlockEntityWorkPlanner.planExecution(units));
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
        final Set<SharedReads> groups = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> checkedHomes = Collections.newSetFromMap(new IdentityHashMap<>());
        int bindings = 0;
        for (final Claims claims : requests) {
            bindings += claims.resourceCount();
            if (bindings > MAX_BINDINGS_PER_PHASE) return null;
            final BooleanSupplier boundary = this.boundaries.get(claims.home);
            if (!claims.complete || claims.domain != this.domain || boundary == null || (checkedHomes.add(claims.home) && !boundary.getAsBoolean())) return null;
            if (claims.shared != null && groups.add(claims.shared)) {
                for (final Identity identity : claims.shared.identities) {
                    if (occupied.putIfAbsent(identity, Access.READ) == Access.WRITE) return null;
                    shared.add(identity);
                }
            }
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
        final Set<SharedReads> validated = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Claims claims : requests) {
            for (final Binding binding : claims.bindings) if (!binding.current.getAsBoolean()) return null;
            if (claims.shared != null && validated.add(claims.shared) && !claims.shared.current.getAsBoolean()) return null;
        }
        // A pure validator may have observed a lifecycle transition; never publish a partial proof.
        if (!this.live.getAsBoolean()) return null;
        for (final Object home : checkedHomes) if (!this.boundaries.get(home).getAsBoolean()) return null;
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
        private final Claims claims;
        private final AtomicInteger state = new AtomicInteger(RESERVED);

        private Lease(BlockEntityResourceOwnership owner, Claims claims, BooleanSupplier boundary) {
            this.owner = owner;
            this.domain = claims.domain;
            this.boundary = boundary;
            this.claims = claims;
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
            final Access granted = lease.claims.permission(instance);
            if (granted == null || (requested == Access.WRITE && granted != Access.WRITE)) throw new IllegalStateException("Undeclared block-entity resource access");
        }

        /** Live BE lookups expose mutable objects and therefore require WRITE, not a read-only promise. */
        public static void checkReference(Level level, BlockPos position) {
            final Scope scope = CURRENT.get();
            if (scope == null) return;
            Object instance = null;
            for (final Binding binding : scope.lease.claims.bindings) {
                if (position.equals(binding.position)) {
                    instance = binding.identity.instance;
                    break;
                }
            }
            if (instance == null) throw new IllegalStateException("Undeclared block-entity reference");
            require(level, instance, Access.WRITE);
        }

        public static boolean checkChunk(ChunkAccess chunk, boolean write) {
            final Scope scope = CURRENT.get();
            if (scope == null) return false;
            boolean local = false;
            for (final Binding binding : scope.lease.claims.bindings) if (binding.home != null && binding.identity.instance == chunk) local = true;
            if (!local) throw new IllegalStateException("Shared publication claims grant no foreign chunk access");
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
