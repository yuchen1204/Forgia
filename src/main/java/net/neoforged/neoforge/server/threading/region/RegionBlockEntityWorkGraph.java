/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner.Kind;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner.Resource;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Bounded shadow observation of actual block-entity ticker invocations. No scheduling or admission authority.
 * A live reference can mutate a container without a world write, so every observed BE reference is a
 * potential write. Raw fields/capabilities/global callbacks remain unobserved; every report is partial.
 */
@ApiStatus.Internal
public final class RegionBlockEntityWorkGraph {
    public static final int MAX_UNITS = 2048;
    public static final int MAX_RESOURCES_PER_UNIT = 64;
    public static final int MAX_PHASES = 200;
    private static final AtomicInteger ACTIVE_PHASES = new AtomicInteger();
    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();
    private long generation;
    private int remaining;
    private int completed;
    @Nullable
    private Summary latest;

    public record Snapshot(int remainingPhases, int completedPhases, Optional<Summary> latest) {}

    /** Width/depth describe observed dependencies only, not a safe schedule or a speedup estimate. */
    public record Summary(String dimension, long gameTime, int plannedUnits, int invokedUnits, int nativeInvocations,
            int serialInvocations, int failedInvocations, int repeatedInvocations, int omittedUnits, int overflowedUnits,
            int exclusiveUnits, int resources, int waveDepth, int maxWaveWidth, int components, int largestComponent,
            long dependencyLinks, long invocationNanos, long preparationNanos, long planningNanos) {}

    public synchronized void start(int phases) {
        if (phases < 1 || phases > MAX_PHASES) throw new IllegalArgumentException("Work-graph phase count must be in 1.." + MAX_PHASES);
        ++this.generation;
        this.remaining = phases;
        this.completed = 0;
        this.latest = null;
    }

    /** Stops future collection. An already-started original tick is never cancelled or replayed. */
    public synchronized void stop() {
        ++this.generation;
        this.remaining = 0;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this.remaining, this.completed, Optional.ofNullable(this.latest));
    }

    @Nullable
    synchronized Phase begin(ServerLevel level, List<RegionBlockEntityTickChunk> chunks) {
        if (this.remaining == 0) return null;
        final Phase phase = new Phase(this, this.generation, level, chunks);
        --this.remaining;
        ACTIVE_PHASES.incrementAndGet();
        return phase;
    }

    private synchronized void publish(long generation, Summary summary) {
        if (this.generation == generation) {
            ++this.completed;
            this.latest = summary;
        }
    }

    /** No level/chunk getters are evaluated when no invocation is being observed. */
    public static void chunkAccess(net.minecraft.world.level.chunk.ChunkAccess chunk, boolean write) {
        if (ACTIVE_PHASES.get() == 0) return;
        final Invocation scope = CURRENT.get();
        if (scope == null) return;
        if (chunk instanceof net.minecraft.world.level.chunk.LevelChunk loaded && loaded.getLevel() != null && chunk.getPos() != null) {
            scope.access(loaded.getLevel(), Kind.CHUNK, chunk.getPos().toLong(), write);
        } else scope.exclusive = true;
    }

    /** Existing chunk access hooks call these; disabled observation takes only a volatile counter read. */
    public static void readChunk(Level level, long chunk) {
        if (ACTIVE_PHASES.get() == 0) return;
        final Invocation scope = CURRENT.get();
        if (scope != null) scope.access(level, Kind.CHUNK, chunk, false);
    }

    public static void writeChunk(Level level, long chunk) {
        if (ACTIVE_PHASES.get() == 0) return;
        final Invocation scope = CURRENT.get();
        if (scope != null) scope.access(level, Kind.CHUNK, chunk, true);
    }

    /** Conservative: a returned live BE/container reference may be mutated without calling setBlock. */
    public static void blockEntityReference(Level level, BlockPos position) {
        if (ACTIVE_PHASES.get() == 0) return;
        final Invocation scope = CURRENT.get();
        if (scope != null) scope.access(level, Kind.BLOCK_ENTITY, position.asLong(), true);
    }

    /** A publication/query/operation with unmodelled effects fences the observed plan. */
    public static void opaqueEffect() {
        if (ACTIVE_PHASES.get() == 0) return;
        final Invocation scope = CURRENT.get();
        if (scope != null) scope.exclusive = true;
    }

    /** One immutable candidate list; mutable entries belong only to its invocation scopes. */
    static final class Phase {
        private final RegionBlockEntityWorkGraph owner;
        private final long generation;
        private final ServerLevel level;
        private final String dimension;
        private final long gameTime;
        private final List<Entry> ordered = new ArrayList<>();
        private final Map<TickingBlockEntity, Entry> entries = new IdentityHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final long preparationNanos;
        private final int omitted;

        private Phase(RegionBlockEntityWorkGraph owner, long generation, ServerLevel level, List<RegionBlockEntityTickChunk> chunks) {
            final long start = System.nanoTime();
            this.owner = owner;
            this.generation = generation;
            this.level = level;
            this.dimension = level.dimension().location().toString();
            this.gameTime = level.getGameTime();
            // Keep the smallest canonical sequences, bounded independently of a world's ticker count.
            final Comparator<Candidate> canonical = Comparator.comparingLong((Candidate candidate) -> candidate.admission().sequence())
                    .thenComparingInt(Candidate::ordinal);
            final var candidates = new java.util.PriorityQueue<Candidate>(canonical.reversed());
            int count = 0;
            for (final var chunk : chunks) {
                for (final var admission : chunk.admissions()) {
                    final Candidate candidate = new Candidate(admission, count++);
                    if (candidates.size() < MAX_UNITS) candidates.add(candidate);
                    else if (canonical.compare(candidate, candidates.peek()) < 0) {
                        candidates.poll();
                        candidates.add(candidate);
                    }
                }
            }
            this.omitted = Math.max(0, count - candidates.size());
            final var sorted = new ArrayList<>(candidates);
            sorted.sort(canonical);
            for (final var candidate : sorted) {
                final var admission = candidate.admission();
                if (!this.entries.containsKey(admission.ticker())) {
                    final Entry entry = new Entry(admission);
                    this.ordered.add(entry);
                    this.entries.put(admission.ticker(), entry);
                }
            }
            this.preparationNanos = System.nanoTime() - start;
        }

        private record Candidate(RegionBlockEntityTickAdmission admission, int ordinal) {}

        void run(TickingBlockEntity ticker, boolean nativeWorker) {
            final Entry entry = this.entries.get(ticker);
            if (entry == null || this.closed.get()) {
                ticker.tick();
                return;
            }
            final Invocation previous = CURRENT.get();
            if (previous != null) previous.exclusive = true;
            final Invocation scope = new Invocation(this.level, entry.position);
            CURRENT.set(scope);
            final long start = System.nanoTime();
            boolean failed = true;
            try {
                ticker.tick();
                failed = false;
            } finally {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
                entry.record(scope, nativeWorker, failed, System.nanoTime() - start);
            }
        }

        void finish() {
            if (!this.closed.compareAndSet(false, true)) return;
            try {
                final long start = System.nanoTime();
                final List<BlockEntityWorkPlanner.Unit> units = new ArrayList<>();
                int nativeCount = 0, serialCount = 0, failures = 0, repeated = 0, overflow = 0, fences = 0, resources = 0;
                long nanos = 0L;
                for (final Entry entry : this.ordered) {
                    if (entry.invocations == 0) continue;
                    final boolean fence = this.omitted != 0 || entry.exclusive || entry.overflow || entry.failures != 0 || entry.invocations > 1;
                    units.add(new BlockEntityWorkPlanner.Unit(entry.reads, entry.writes, fence));
                    nativeCount += entry.nativeInvocations;
                    serialCount += entry.invocations - entry.nativeInvocations;
                    failures += entry.failures;
                    repeated += Math.max(0, entry.invocations - 1);
                    overflow += entry.overflow ? 1 : 0;
                    fences += fence ? 1 : 0;
                    resources += entry.reads.size() + entry.writes.size();
                    nanos += entry.nanos;
                }
                final var plan = BlockEntityWorkPlanner.plan(units);
                this.owner.publish(this.generation, new Summary(this.dimension, this.gameTime, this.ordered.size(), units.size(),
                        nativeCount, serialCount, failures, repeated, this.omitted, overflow, fences, resources,
                        plan.waves().size(), plan.maxWidth(), plan.components(), plan.largestComponent(), plan.dependencyLinks(),
                        nanos, this.preparationNanos, System.nanoTime() - start));
            } finally {
                // No report retains a level, ticker, live BE, resource identity or captured closure.
                this.entries.clear();
                this.ordered.clear();
                ACTIVE_PHASES.decrementAndGet();
            }
        }
    }

    private static final class Entry {
        private final BlockPos position;
        private final Set<Resource> reads = new HashSet<>();
        private final Set<Resource> writes = new HashSet<>();
        private boolean exclusive;
        private boolean overflow;
        private int invocations;
        private int nativeInvocations;
        private int failures;
        private long nanos;

        private Entry(RegionBlockEntityTickAdmission admission) {
            this.position = admission.position();
            // Unknown bodies and adapter callbacks cannot supply an observed vanilla-local contract.
            this.exclusive = switch (admission.decision()) {
                case ADMITTED, ADMITTED_WITH_BORDER_UPDATES -> false;
                default -> true;
            };
        }

        private synchronized void record(Invocation scope, boolean nativeWorker, boolean failed, long nanos) {
            ++this.invocations;
            if (nativeWorker) ++this.nativeInvocations;
            if (failed) ++this.failures;
            this.nanos += nanos;
            this.exclusive |= scope.exclusive;
            this.overflow |= scope.overflow;
            for (final Resource read : scope.reads) this.access(read, false);
            for (final Resource write : scope.writes) this.access(write, true);
        }

        private void access(Resource resource, boolean write) {
            if (this.reads.contains(resource) || this.writes.contains(resource) || this.reads.size() + this.writes.size() < MAX_RESOURCES_PER_UNIT) {
                if (write) {
                    this.reads.remove(resource);
                    this.writes.add(resource);
                } else if (!this.writes.contains(resource)) this.reads.add(resource);
            } else this.overflow = true;
        }
    }

    private static final class Invocation {
        private final Set<Resource> reads = new HashSet<>();
        private final Set<Resource> writes = new HashSet<>();
        private boolean exclusive;
        private boolean overflow;

        private Invocation(ServerLevel level, BlockPos position) {
            this.access(level, Kind.BLOCK_ENTITY, position.asLong(), true);
            this.access(level, Kind.CHUNK, ChunkPos.asLong(position), false);
        }

        private void access(Level level, Kind kind, long key, boolean write) {
            final Resource resource = new Resource(level, kind, key);
            if (this.reads.contains(resource) || this.writes.contains(resource) || this.reads.size() + this.writes.size() < MAX_RESOURCES_PER_UNIT) {
                if (write) {
                    this.reads.remove(resource);
                    this.writes.add(resource);
                } else if (!this.writes.contains(resource)) this.reads.add(resource);
            } else this.overflow = true;
        }
    }
}
