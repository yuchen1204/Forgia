/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
    private final Map<Object, BooleanSupplier> boundaries;
    private final BooleanSupplier live;
    private final Thread coordinator = Thread.currentThread();
    private final AtomicInteger activeScopes = new AtomicInteger();
    private volatile boolean open = true;
    private volatile boolean reserved;
    private List<Lease> leases = List.of();

    BlockEntityResourceOwnership(Object domain, Map<Object, BooleanSupplier> heldRegions, BooleanSupplier live) {
        this.domain = Objects.requireNonNull(domain);
        this.boundaries = new IdentityHashMap<>(heldRegions.size());
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
        private final Object[] instances;
        private final int[] index;
        private final BooleanSupplier current;
        private final int count;
        private final int size;

        private SharedReads(Object domain, List<?> instances, BooleanSupplier current) {
            this.domain = Objects.requireNonNull(domain);
            this.current = Objects.requireNonNull(current);
            this.count = Math.min(instances.size(), MAX_RESOURCES_PER_UNIT + 1);
            final int bounded = this.count <= MAX_RESOURCES_PER_UNIT ? this.count : 0;
            this.instances = new Object[bounded];
            this.index = bounded <= 8 ? null : new int[tableCapacity(bounded)];
            int size = 0;
            for (int input = 0; input < bounded; ++input) {
                final Object instance = Objects.requireNonNull(instances.get(input));
                if (identityIndex(this.instances, size, this.index, instance) < 0) {
                    this.instances[size] = instance;
                    indexIdentity(this.index, instance, size++);
                }
            }
            this.size = size;
        }

        private boolean contains(Object instance) {
            return identityIndex(this.instances, this.size, this.index, instance) >= 0;
        }

        private boolean sameInstances(SharedReads other) {
            if (this.domain != other.domain || this.size != other.size) return false;
            for (int index = 0; index < this.size; ++index) if (!other.contains(this.instances[index])) return false;
            return true;
        }

        public static SharedReads capture(Object domain, List<?> instances, BooleanSupplier current) {
            return new SharedReads(domain, instances, current);
        }
    }

    /** Immutable bounded proof input. Every attachment validator survives duplicate-resource normalization. */
    public static final class Claims {
        private static final Binding[] NO_BINDINGS = new Binding[0];
        private static final Object[] NO_INSTANCES = new Object[0];
        private static final Access[] NO_ACCESS = new Access[0];
        private final Object domain;
        private final Object home;
        private final Object[] instances;
        private final Access[] access;
        private final int[] index;
        private final int size;
        private final Binding[] bindings;
        private final SharedReads shared;
        private final boolean complete;

        private Claims(Object domain, Object home, Binding[] bindings, Access[] modes, @Nullable SharedReads shared, boolean complete) {
            this.domain = domain;
            this.home = home;
            this.bindings = bindings;
            this.shared = shared;
            this.instances = bindings.length == 0 ? NO_INSTANCES : new Object[bindings.length];
            this.access = bindings.length == 0 ? NO_ACCESS : new Access[bindings.length];
            this.index = bindings.length <= 8 ? null : new int[tableCapacity(bindings.length)];
            int size = 0;
            for (int binding = 0; binding < bindings.length; ++binding) {
                final Object instance = bindings[binding].identity.instance;
                int slot = identityIndex(this.instances, size, this.index, instance);
                if (slot < 0) {
                    slot = size++;
                    this.instances[slot] = instance;
                    indexIdentity(this.index, instance, slot);
                }
                if (this.access[slot] != Access.WRITE) this.access[slot] = modes[binding];
            }
            this.size = size;
            if (shared != null) {
                for (int slot = 0; slot < size; ++slot) if (this.access[slot] == Access.WRITE && shared.contains(this.instances[slot])) complete = false;
            }
            BlockPos firstPosition = null;
            Object firstInstance = null;
            Map<BlockPos, Object> positions = null;
            for (final Binding binding : bindings) {
                if (binding.home == null && this.localPermission(binding.identity.instance) == Access.WRITE) complete = false;
                if (binding.position == null) continue;
                if (firstPosition == null) {
                    firstPosition = binding.position;
                    firstInstance = binding.identity.instance;
                } else if (firstPosition.equals(binding.position)) {
                    if (firstInstance != binding.identity.instance) complete = false;
                } else {
                    if (positions == null) positions = new HashMap<>();
                    final Object previous = positions.putIfAbsent(binding.position, binding.identity.instance);
                    if (previous != null && previous != binding.identity.instance) complete = false;
                }
            }
            this.complete = complete && (size != 0 || (shared != null && shared.size != 0));
        }

        public static Builder builder(Object domain, Object home) {
            return new Builder(domain, home);
        }

        public static Claims fence(Object domain, Object home) {
            return new Claims(domain, home, NO_BINDINGS, NO_ACCESS, null, false);
        }

        public boolean complete() {
            return this.complete;
        }

        int resourceCount() {
            return this.bindings.length + (this.shared == null ? 0 : this.shared.count);
        }

        private Access localPermission(Object instance) {
            final int slot = identityIndex(this.instances, this.size, this.index, instance);
            return slot < 0 ? null : this.access[slot];
        }

        private Access permission(Object instance) {
            final Access local = this.localPermission(instance);
            return local != null ? local : this.shared != null && this.shared.contains(instance) ? Access.READ : null;
        }

        public boolean sameResources(Claims other) {
            return this.sameResources(other, null);
        }

        /** Memoizes only equality of immutable captured sets, never validation of mutable world state. */
        boolean sameResources(Claims other, @Nullable Map<SharedReads, SharedReads> compared) {
            if (!this.complete || !other.complete || this.domain != other.domain || this.home != other.home) return false;
            boolean localEqual = this.size == other.size;
            for (int slot = 0; localEqual && slot < this.size; ++slot) localEqual = this.access[slot] == other.localPermission(this.instances[slot]);
            if (localEqual) {
                if (this.shared == other.shared) return true;
                if (this.shared != null && other.shared != null) {
                    if (compared != null && compared.get(this.shared) == other.shared) return true;
                    if (this.shared.sameInstances(other.shared)) {
                        if (compared != null) compared.put(this.shared, other.shared);
                        return true;
                    }
                }
            }
            // Expanded/grouped representations can describe the same normalized permissions. Compare
            // their captured unions directly, without constructing diagnostic planner sets or proofs.
            return this.permissionsIncludedIn(other) && other.permissionsIncludedIn(this);
        }

        private boolean permissionsIncludedIn(Claims other) {
            for (int slot = 0; slot < this.size; ++slot) if (this.access[slot] != other.permission(this.instances[slot])) return false;
            if (this.shared != null) {
                for (int slot = 0; slot < this.shared.size; ++slot) {
                    final Object instance = this.shared.instances[slot];
                    if (this.permission(instance) != other.permission(instance)) return false;
                }
            }
            return true;
        }

        /** Diagnostic expansion only. The execution planner consumes the immutable local arrays directly. */
        public BlockEntityWorkPlanner.Unit unit() {
            if (!this.complete) return new BlockEntityWorkPlanner.Unit(Set.of(), Set.of(), true);
            final Set<BlockEntityWorkPlanner.Resource> reads = new HashSet<>();
            final Set<BlockEntityWorkPlanner.Resource> writes = new HashSet<>();
            for (int slot = 0; slot < this.size; ++slot) {
                (this.access[slot] == Access.WRITE ? writes : reads).add(new BlockEntityWorkPlanner.Resource(this.domain, this.instances[slot]));
            }
            if (this.shared != null) {
                for (int slot = 0; slot < this.shared.size; ++slot) {
                    final Object instance = this.shared.instances[slot];
                    if (this.localPermission(instance) == null) reads.add(new BlockEntityWorkPlanner.Resource(this.domain, instance));
                }
            }
            return new BlockEntityWorkPlanner.Unit(reads, writes, false);
        }

        public static final class Builder {
            private final Object domain;
            private final Object home;
            private Binding[] bindings = new Binding[4];
            private Access[] access = new Access[4];
            private int size;
            private SharedReads shared;
            private boolean complete = true;

            private Builder(Object domain, Object home) {
                this.domain = Objects.requireNonNull(domain);
                this.home = Objects.requireNonNull(home);
            }

            public Builder claim(Binding binding, Access mode) {
                Objects.requireNonNull(binding);
                Objects.requireNonNull(mode);
                if (this.size + (this.shared == null ? 0 : this.shared.count) >= MAX_RESOURCES_PER_UNIT || binding.identity.domain != this.domain
                        || (binding.home != null && binding.home != this.home) || (binding.home == null && mode == Access.WRITE)) {
                    this.complete = false;
                    return this;
                }
                if (this.size == this.bindings.length) {
                    this.bindings = Arrays.copyOf(this.bindings, this.size * 2);
                    this.access = Arrays.copyOf(this.access, this.size * 2);
                }
                this.bindings[this.size] = binding;
                this.access[this.size++] = mode;
                return this;
            }

            /** One shared publication per unit; merge its resources during fresh preparation, not here. */
            public Builder sharedReads(SharedReads shared) {
                Objects.requireNonNull(shared);
                if (this.shared != null || shared.domain != this.domain || shared.count + this.size > MAX_RESOURCES_PER_UNIT) this.complete = false;
                if (this.shared == null) this.shared = shared;
                return this;
            }

            public Claims build() {
                // Builders may be reused. Neither a later claim nor a later build can mutate a publication.
                return new Claims(this.domain, this.home, Arrays.copyOf(this.bindings, this.size), this.access, this.shared, this.complete);
            }
        }
    }

    public record Plan(List<Claims> claims, BlockEntityWorkPlanner.ExecutionPlan dependencies) {
        public Plan {
            claims = List.copyOf(claims);
        }
    }

    /** Refuse inconsistent homes/global write aliases across the entire ordered phase, then compute depths. */
    public static Plan plan(List<Claims> input) {
        if (input.size() > RegionBlockEntityWorkGraph.MAX_UNITS) throw new IllegalArgumentException("Resource proof unit budget exceeded");
        final Map<SharedReads, Boolean> groups = new IdentityHashMap<>();
        final int entries = entryBudget(input, groups);
        if (entries < 0) throw new IllegalArgumentException("Resource proof phase budget exceeded");
        final ResourceTable table = new ResourceTable(entries);
        for (final SharedReads group : groups.keySet()) {
            for (int slot = 0; slot < group.size; ++slot) table.flags[table.slot(group.domain, group.instances[slot])] |= ResourceTable.SHARED;
        }
        for (final Claims claims : input) {
            if (!claims.complete) continue;
            for (int local = 0; local < claims.size; ++local) {
                final int slot = table.slot(claims.domain, claims.instances[local]);
                table.flags[slot] |= claims.access[local] == Access.WRITE ? ResourceTable.WRITE : ResourceTable.READ;
            }
            for (final Binding binding : claims.bindings) table.home(table.slot(claims.domain, binding.identity.instance), binding.home);
        }
        for (int slot = 0; slot < table.flags.length; ++slot) {
            if ((table.flags[slot] & (ResourceTable.SHARED | ResourceTable.WRITE)) == (ResourceTable.SHARED | ResourceTable.WRITE)) table.flags[slot] |= ResourceTable.INVALID;
        }
        for (final SharedReads group : groups.keySet()) {
            for (int local = 0; local < group.size; ++local) {
                if (table.invalid(table.slot(group.domain, group.instances[local]))) {
                    groups.put(group, Boolean.TRUE);
                    break;
                }
            }
        }
        final List<Claims> effective = new ArrayList<>(input.size());
        final List<List<Integer>> waves = new ArrayList<>();
        final int[] writers = new int[table.flags.length];
        final int[] readers = new int[table.flags.length];
        int barrier = 0;
        int maximum = -1;
        int width = 0;
        for (int index = 0; index < input.size(); ++index) {
            Claims claims = input.get(index);
            boolean invalid = groups.get(claims.shared) == Boolean.TRUE;
            for (int local = 0; claims.complete && !invalid && local < claims.size; ++local) invalid = table.invalid(table.slot(claims.domain, claims.instances[local]));
            if (claims.complete && invalid) claims = Claims.fence(claims.domain, claims.home);
            effective.add(claims);
            int depth = claims.complete ? barrier : maximum + 1;
            if (claims.complete) {
                // Shared publications cannot be written anywhere in this accepted phase. Local arrays
                // suffice for dependency discovery; no per-unit Resource/Set/Depths expansion is needed.
                for (int local = 0; local < claims.size; ++local) {
                    final int slot = table.slot(claims.domain, claims.instances[local]);
                    depth = Math.max(depth, writers[slot]);
                    if (claims.access[local] == Access.WRITE) depth = Math.max(depth, readers[slot]);
                }
            }
            while (waves.size() <= depth) waves.add(new ArrayList<>());
            final List<Integer> wave = waves.get(depth);
            wave.add(index);
            width = Math.max(width, wave.size());
            maximum = Math.max(maximum, depth);
            if (!claims.complete) {
                // Depth arrays store predecessor depth + 1. A fence's new barrier dominates every
                // previous entry, so no clearing, epoch cache or resource-history authority is needed.
                barrier = depth + 1;
            } else {
                for (int local = 0; local < claims.size; ++local) {
                    final int slot = table.slot(claims.domain, claims.instances[local]);
                    if (claims.access[local] == Access.WRITE) {
                        writers[slot] = depth + 1;
                        readers[slot] = 0;
                    } else readers[slot] = Math.max(readers[slot], depth + 1);
                }
            }
        }
        return new Plan(effective, new BlockEntityWorkPlanner.ExecutionPlan(waves, width));
    }

    /** Charge logical uses before allocating a table; only storage for identical publications is shared. */
    private static int entryBudget(List<Claims> input, Map<SharedReads, Boolean> groups) {
        int bindings = 0;
        int entries = 0;
        for (final Claims claims : input) {
            bindings += claims.resourceCount();
            if (bindings > MAX_BINDINGS_PER_PHASE) return -1;
            if (!claims.complete) continue;
            entries += claims.size;
            if (claims.shared != null && groups.put(claims.shared, Boolean.FALSE) == null) entries += claims.shared.size;
        }
        return entries;
    }

    private static int tableCapacity(int entries) {
        return Math.max(4, Integer.highestOneBit(Math.max(1, entries * 2 - 1)) << 1);
    }

    private static int identityHash(Object instance) {
        final int hash = System.identityHashCode(instance);
        return hash ^ (hash >>> 16);
    }

    private static int identityIndex(Object[] instances, int size, @Nullable int[] index, Object instance) {
        if (index == null) {
            for (int slot = 0; slot < size; ++slot) if (instances[slot] == instance) return slot;
        } else {
            final int mask = index.length - 1;
            for (int slot = identityHash(instance) & mask; index[slot] != 0; slot = (slot + 1) & mask) {
                final int found = index[slot] - 1;
                if (instances[found] == instance) return found;
            }
        }
        return -1;
    }

    private static void indexIdentity(@Nullable int[] index, Object instance, int value) {
        if (index == null) return;
        final int mask = index.length - 1;
        int slot = identityHash(instance) & mask;
        while (index[slot] != 0) slot = (slot + 1) & mask;
        index[slot] = value + 1;
    }

    /** Call-local, pre-sized pair-identity table. No user hash/equals, per-entry nodes or retained proof. */
    private static final class ResourceTable {
        private static final int READ = 1;
        private static final int WRITE = 2;
        private static final int SHARED = 4;
        private static final int INVALID = 8;
        private final Object[] keys;
        private final Object[] homes;
        private final byte[] flags;

        private ResourceTable(int entries) {
            final int capacity = tableCapacity(entries);
            this.keys = new Object[capacity * 2];
            this.homes = new Object[capacity];
            this.flags = new byte[capacity];
        }

        private int slot(Object domain, Object instance) {
            final int hash = 31 * System.identityHashCode(domain) + System.identityHashCode(instance);
            final int mask = this.flags.length - 1;
            int slot = (hash ^ (hash >>> 16)) & mask;
            while (this.keys[slot * 2 + 1] != null) {
                if (this.keys[slot * 2] == domain && this.keys[slot * 2 + 1] == instance) return slot;
                slot = (slot + 1) & mask;
            }
            this.keys[slot * 2] = domain;
            this.keys[slot * 2 + 1] = instance;
            return slot;
        }

        private void home(int slot, @Nullable Object home) {
            if (home == null) this.flags[slot] |= SHARED;
            else if (this.homes[slot] == null) this.homes[slot] = home;
            else if (this.homes[slot] != home) this.flags[slot] |= INVALID;
        }

        private boolean invalid(int slot) {
            return (this.flags[slot] & INVALID) != 0;
        }
    }

    /**
     * Reserve a complete wave, all-or-nothing, without waiting. It can succeed only once. No live leases
     * escape on a refusal, and no worker is published before all conflicts, bindings and boundaries pass.
     */
    @Nullable
    List<Lease> tryAcquireAll(List<Claims> requests) {
        this.checkCoordinator();
        if (!this.open || this.reserved || requests.isEmpty() || requests.size() > RegionBlockEntityWorkGraph.MAX_UNITS || !this.live.getAsBoolean()) return null;
        final Map<SharedReads, Boolean> groups = new IdentityHashMap<>();
        final int entries = entryBudget(requests, groups);
        if (entries < 0) return null;
        final ResourceTable table = new ResourceTable(entries);
        for (final SharedReads group : groups.keySet()) {
            for (int local = 0; local < group.size; ++local) table.flags[table.slot(group.domain, group.instances[local])] |= ResourceTable.SHARED;
        }
        final Set<Object> checkedHomes = Collections.newSetFromMap(new IdentityHashMap<>(this.boundaries.size()));
        for (final Claims claims : requests) {
            final BooleanSupplier boundary = this.boundaries.get(claims.home);
            if (!claims.complete || claims.domain != this.domain || boundary == null || (checkedHomes.add(claims.home) && !boundary.getAsBoolean())) return null;
            for (int local = 0; local < claims.size; ++local) {
                final int slot = table.slot(claims.domain, claims.instances[local]);
                final int occupied = table.flags[slot];
                final int mode = claims.access[local] == Access.WRITE ? ResourceTable.WRITE : ResourceTable.READ;
                if ((occupied & ResourceTable.WRITE) != 0 || (mode == ResourceTable.WRITE && (occupied & (ResourceTable.READ | ResourceTable.SHARED)) != 0)) return null;
                table.flags[slot] |= mode;
            }
            for (final Binding binding : claims.bindings) {
                final int slot = table.slot(claims.domain, binding.identity.instance);
                table.home(slot, binding.home);
                if (table.invalid(slot)) return null;
            }
        }
        for (int slot = 0; slot < table.flags.length; ++slot) {
            if ((table.flags[slot] & (ResourceTable.SHARED | ResourceTable.WRITE)) == (ResourceTable.SHARED | ResourceTable.WRITE)) return null;
        }
        for (final Claims claims : requests) {
            for (final Binding binding : claims.bindings) if (!binding.current.getAsBoolean()) return null;
            if (claims.shared != null && groups.put(claims.shared, Boolean.TRUE) != Boolean.TRUE && !claims.shared.current.getAsBoolean()) return null;
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
