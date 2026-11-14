/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapter;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Refusal;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.State;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Validation;
import net.neoforged.neoforge.server.threading.api.adapter.DedicatedBlockEntityAdapter;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityWorkGraph;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Server-owned request registry. No Minecraft types, binding discovery, scheduler or minting authority.
 * The shared ParallelApi transaction owns registration/freeze and the existing canonical batch owns each
 * Session. Declared resource identities go only to the existing diagnostic planner, never to Claims,
 * reservation, native proof or Permit. The original kernel independently assesses binding/lifecycle;
 * there is deliberately no positive Mod closure backend that could turn declarations into authority.
 */
@ApiStatus.Internal
public final class BlockEntityAdapterRegistry {
    private final Map<String, Entry> entries = new TreeMap<>();
    private final Map<String, Entry> types = new TreeMap<>();
    @Nullable
    private Thread coordinator = Thread.currentThread();
    private boolean open = true;
    private boolean frozen;
    private int sessions;
    private long sequence;
    @Nullable
    private volatile BlockEntityAdapters.PhaseSummary lastPhase;

    public synchronized int size() {
        return this.entries.size();
    }

    public synchronized boolean containsId(String id) {
        return this.entries.containsKey(id);
    }

    public synchronized boolean containsType(String type) {
        return this.types.containsKey(type);
    }

    private void requireCoordinator() {
        if (!this.open || Thread.currentThread() != this.coordinator)
            throw new IllegalStateException("Block-entity adapter lifecycle requires its live server coordinator");
    }

    public BlockEntityAdapters.Registration register(int protocol, BlockEntityAdapters.Descriptor descriptor, BlockEntityAdapter adapter) {
        synchronized (this) {
            this.requireCoordinator();
            if (this.frozen) throw new IllegalStateException("Block-entity adapter registration is frozen");
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(adapter);
            if (protocol != BlockEntityAdapters.PROTOCOL_VERSION || this.entries.containsKey(descriptor.id())
                    || descriptor.types().stream().anyMatch(this.types::containsKey))
                throw new IllegalArgumentException("Invalid/duplicate block-entity adapter protocol, id or types");
        }
        // External code must run without the registry monitor: lifecycle close acquires SERVERS then
        // this monitor, while audit may query ParallelApi (SERVERS). Holding it would invert that order.
        final var audit = adapter instanceof DedicatedBlockEntityAdapter dedicated ? Objects.requireNonNull(dedicated.audit()) : null;
        synchronized (this) {
            // The callback can reenter registration, freeze or close the service. Recheck before publishing.
            this.requireCoordinator();
            if (this.frozen) throw new IllegalStateException("Block-entity adapter registration is frozen");
            if (this.entries.containsKey(descriptor.id()) || descriptor.types().stream().anyMatch(this.types::containsKey))
                throw new IllegalArgumentException("Reentrant block-entity adapter registration conflicts");
            final var entry = new Entry(descriptor, adapter, audit);
            this.entries.put(descriptor.id(), entry);
            descriptor.types().forEach(type -> this.types.put(type, entry));
            return entry;
        }
    }

    public synchronized void freeze() {
        this.requireCoordinator();
        this.frozen = true;
    }

    public synchronized List<BlockEntityAdapters.Status> statuses() {
        if (!this.open) throw new IllegalStateException("Block-entity adapter registry has stopped");
        return this.entries.values().stream().map(Entry::status).toList();
    }

    public Optional<BlockEntityAdapters.PhaseSummary> lastPhase() {
        return Optional.ofNullable(this.lastPhase);
    }

    /** Closed registrations cannot be reused; no user callback runs under this registry's monitor. */
    public synchronized void close() {
        this.open = false;
        for (final var entry : this.entries.values()) entry.state = State.CLOSED;
        this.retireReferences();
        this.lastPhase = null;
    }

    private void retireReferences() {
        if (this.sessions != 0) return;
        for (final var entry : this.entries.values()) if (entry.state != State.ACTIVE) entry.adapter = null;
        if (!this.open) this.coordinator = null;
    }

    @Nullable
    public synchronized Session begin(String dimension, long gameTime) {
        this.requireCoordinator();
        if (!this.frozen) throw new IllegalStateException("Unfrozen adapter registry");
        if (this.types.isEmpty()) return null;
        if (this.sessions != 0) throw new IllegalStateException("Adapter sessions cannot nest or replace a canonical phase");
        ++this.sessions;
        return new Session(new BlockEntityAdapters.Phase(++this.sequence, dimension, gameTime), Map.copyOf(this.types));
    }

    private final class Entry implements BlockEntityAdapters.Registration {
        private final BlockEntityAdapters.Descriptor descriptor;
        @Nullable
        private BlockEntityAdapter adapter;
        @Nullable
        private final DedicatedBlockEntityAdapter.Audit audit;
        private volatile State state = State.ACTIVE;
        private final LongAdder inspections = new LongAdder();
        private final LongAdder serial = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final Map<Refusal, LongAdder> refusals = new EnumMap<>(Refusal.class);

        Entry(BlockEntityAdapters.Descriptor descriptor, BlockEntityAdapter adapter, @Nullable DedicatedBlockEntityAdapter.Audit audit) {
            this.descriptor = descriptor;
            this.adapter = adapter;
            this.audit = audit;
            for (final var reason : Refusal.values()) this.refusals.put(reason, new LongAdder());
        }

        @Override
        public State state() {
            return this.state;
        }

        @Override
        public void invalidate() {
            synchronized (BlockEntityAdapterRegistry.this) {
                requireCoordinator();
                if (this.state == State.ACTIVE) this.state = State.INVALIDATED;
                retireReferences();
            }
        }

        @Override
        public void close() {
            synchronized (BlockEntityAdapterRegistry.this) {
                requireCoordinator();
                if (this.state != State.UNREGISTERED) this.state = State.UNREGISTERED;
                retireReferences();
            }
        }

        BlockEntityAdapters.Status status() {
            final Map<Refusal, Long> reasons = new EnumMap<>(Refusal.class);
            this.refusals.forEach((reason, count) -> {
                if (count.sum() != 0) reasons.put(reason, count.sum());
            });
            return new BlockEntityAdapters.Status(this.descriptor, this.state, Optional.ofNullable(this.audit),
                    this.inspections.sum(), this.serial.sum(), this.failures.sum(), reasons);
        }
    }

    /** Pure request transport attached to one existing canonical batch, not an execution batch or kernel. */
    public final class Session {
        private final BlockEntityAdapters.Phase phase;
        private final Thread thread = Thread.currentThread();
        private final Map<String, Entry> captured;
        private final Map<Entry, BlockEntityAdapter> touched = new IdentityHashMap<>();
        private final List<BlockEntityWorkPlanner.Unit> declarations = new ArrayList<>();
        private final List<Long> ordinals = new ArrayList<>();
        private int logicalUses;
        private long inspections;
        private boolean closed;

        Session(BlockEntityAdapters.Phase phase, Map<String, Entry> captured) {
            this.phase = phase;
            this.captured = new TreeMap<>(captured);
        }

        private void requireCurrent() {
            if (this.closed || this.thread != Thread.currentThread()) throw new IllegalStateException("Retired or foreign adapter phase");
        }

        public boolean handles(String type) {
            this.requireCurrent();
            // Tombstones remain fences. Removing a registration must not turn a captured refusal into
            // new worker eligibility or let a later equivalent registration inherit this identity.
            return this.captured.containsKey(type);
        }

        private Refusal refused(Entry entry, Refusal refusal) {
            entry.refusals.get(refusal).increment();
            return refusal;
        }

        /** Core recheck after external callbacks. No external code runs under this monitor. */
        public boolean current(String type) {
            this.requireCurrent();
            synchronized (BlockEntityAdapterRegistry.this) {
                final var entry = this.captured.get(type);
                return open && entry != null && entry.state == State.ACTIVE;
            }
        }

        /** Record an independent Core rejection in addition to declaration diagnostics. */
        public void coreRefusal(String type, Refusal declared, Refusal actual) {
            this.requireCurrent();
            if (actual != declared) this.refused(this.captured.get(type), actual);
        }

        /** Retained internal C5 entry point; diagnostic inspection cannot grant execution authority. */
        public void inspect(String type, long ordinal, int x, int y, int z, @Nullable Object subject) {
            this.inspectRequest(type, ordinal, x, y, z, subject);
        }

        /** Must be called inside the existing serial Permit at this invocation's actual canonical cursor. */
        public Refusal inspectRequest(String type, long ordinal, int x, int y, int z, @Nullable Object subject) {
            this.requireCurrent();
            final var entry = this.captured.get(type);
            if (entry == null) throw new IllegalArgumentException("Unregistered invocation in adapter phase");
            entry.serial.increment();
            if (entry.state != State.ACTIVE) {
                return this.refused(entry, Refusal.INVALIDATED);
            }
            if (subject == null) {
                return this.refused(entry, Refusal.INVALID_BINDING);
            }
            if (this.ordinals.size() >= RegionBlockEntityWorkGraph.MAX_UNITS) {
                return this.refused(entry, Refusal.BUDGET_EXCEEDED);
            }
            BlockEntityWorkPlanner.Unit declaration = new BlockEntityWorkPlanner.Unit(Set.of(), Set.of(), true);
            final var view = new View(this.phase, ordinal, type, x, y, z, subject);
            final BlockEntityAdapter adapter;
            synchronized (BlockEntityAdapterRegistry.this) {
                adapter = entry.adapter;
            }
            if (adapter == null) {
                view.retire();
                return this.refused(entry, Refusal.INVALIDATED);
            }
            this.touched.putIfAbsent(entry, adapter);
            ++this.inspections;
            entry.inspections.increment();
            try {
                final var request = Objects.requireNonNull(adapter.inspect(view), "Null block-entity adapter request");
                view.retire();
                if (!entry.descriptor.executions().contains(request.execution()))
                    throw new IllegalArgumentException("Undeclared block-entity execution request");
                if (entry.state != State.ACTIVE) {
                    return this.refused(entry, Refusal.INVALIDATED);
                }
                if (request.execution() == BlockEntityAdapters.Execution.SERIAL) {
                    return this.refused(entry, Refusal.ADAPTER_SERIAL);
                }
                final long uses = (long) request.resources().size() + request.dependencies().size();
                if (uses > BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT
                        || uses + this.logicalUses > BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE) {
                    return this.refused(entry, Refusal.BUDGET_EXCEEDED);
                }
                this.logicalUses += (int) uses;
                if (request.resources().isEmpty()) {
                    return this.refused(entry, Refusal.UNKNOWN_RESOURCE);
                }
                final Set<BlockEntityWorkPlanner.Resource> reads = new HashSet<>();
                final Set<BlockEntityWorkPlanner.Resource> writes = new HashSet<>();
                for (final var resource : request.resources()) {
                    if (resource.ownership() == BlockEntityAdapters.Ownership.UNKNOWN
                            || (resource.ownership() == BlockEntityAdapters.Ownership.SHARED_READ && resource.access() == BlockEntityAdapters.Access.WRITE)) {
                        return this.refused(entry, Refusal.UNKNOWN_RESOURCE);
                    }
                    final var current = Objects.requireNonNull(resource.current().validate(), "Null resource validation");
                    if (current != Validation.CURRENT) {
                        return this.refused(entry, current == Validation.INVALID ? Refusal.INVALID_RESOURCE : Refusal.UNKNOWN_RESOURCE);
                    }
                    final var identity = new BlockEntityWorkPlanner.Resource(this.phase, resource.identity());
                    (resource.access() == BlockEntityAdapters.Access.READ ? reads : writes).add(identity);
                }
                for (final var dependency : request.dependencies()) {
                    final var current = Objects.requireNonNull(dependency.current().validate(), "Null dependency validation");
                    if (current != Validation.CURRENT) {
                        return this.refused(entry, current == Validation.INVALID ? Refusal.INVALID_DEPENDENCY : Refusal.UNKNOWN_DEPENDENCY);
                    }
                }
                if (entry.state != State.ACTIVE) {
                    return this.refused(entry, Refusal.INVALIDATED);
                }
                declaration = new BlockEntityWorkPlanner.Unit(reads, writes, false);
                return this.refused(entry, Refusal.CORE_AUDIT_UNAVAILABLE);
            } catch (RuntimeException | Error failure) {
                entry.failures.increment();
                this.refused(entry, Refusal.CALLBACK_FAILED);
                throw failure;
            } finally {
                view.retire();
                this.declarations.add(declaration);
                this.ordinals.add(ordinal);
            }
        }

        /** All work must already have physically joined and committed. Always retire even on callback failure. */
        public void close(boolean successful) {
            this.requireCurrent();
            Throwable failure = null;
            try {
                for (final var entry : this.touched.keySet().stream().sorted(java.util.Comparator.comparing(e -> e.descriptor.id())).toList()) {
                    try {
                        this.touched.get(entry).phaseClosed(this.phase, successful);
                    } catch (RuntimeException | Error problem) {
                        entry.failures.increment();
                        if (failure == null) failure = problem;
                        else if (failure != problem) failure.addSuppressed(problem);
                    }
                }
                final var plan = BlockEntityWorkPlanner.planExecution(this.declarations);
                final var waves = plan.waves().stream().map(wave -> wave.stream().map(this.ordinals::get).toList()).toList();
                synchronized (BlockEntityAdapterRegistry.this) {
                    if (open) lastPhase = new BlockEntityAdapters.PhaseSummary(this.phase, waves, this.inspections, successful && failure == null);
                }
            } finally {
                this.closed = true;
                this.touched.clear();
                this.captured.clear();
                this.declarations.clear();
                this.ordinals.clear();
                synchronized (BlockEntityAdapterRegistry.this) {
                    --sessions;
                    retireReferences();
                }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }

    private static final class View implements BlockEntityAdapters.Invocation {
        private final Thread thread = Thread.currentThread();
        private final BlockEntityAdapters.Phase phase;
        private final long ordinal;
        private final String type;
        private final int x;
        private final int y;
        private final int z;
        @Nullable
        private Object subject;

        View(BlockEntityAdapters.Phase phase, long ordinal, String type, int x, int y, int z, Object subject) {
            this.phase = phase;
            this.ordinal = ordinal;
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.subject = subject;
        }

        private void requireBorrow() {
            if (this.subject == null || Thread.currentThread() != this.thread) throw new IllegalStateException("Retired or foreign invocation view");
        }

        void retire() {
            this.subject = null;
        }

        @Override
        public BlockEntityAdapters.Phase phase() {
            this.requireBorrow();
            return this.phase;
        }

        @Override
        public long ordinal() {
            this.requireBorrow();
            return this.ordinal;
        }

        @Override
        public String type() {
            this.requireBorrow();
            return this.type;
        }

        @Override
        public int x() {
            this.requireBorrow();
            return this.x;
        }

        @Override
        public int y() {
            this.requireBorrow();
            return this.y;
        }

        @Override
        public int z() {
            this.requireBorrow();
            return this.z;
        }

        @Override
        public Object subject() {
            this.requireBorrow();
            return this.subject;
        }
    }
}
