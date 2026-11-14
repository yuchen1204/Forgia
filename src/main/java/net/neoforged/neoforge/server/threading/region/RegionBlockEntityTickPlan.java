/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One level's block-entity phase. The actual Level entry uses {@link #collectCanonical()} after pending
 * merge and fresh onLoad: list indexes are the sole phase ordinals and ineligible entries remain available
 * for a later serial eligibility check. The lock protects only membership copying, never user callbacks.
 * <p>
 * The historical chunk-indexed {@link #collect()} and explicit {@link #accept(TickingBlockEntity)} bridges
 * remain for compatibility and their regression tests. Cached chunk admissions provide declaration/binding
 * templates, not canonical order or lasting eligibility. This collection change retains both execution
 * drivers; their worker-prefix and Fence scheduling are a separate migration.
 */
@ApiStatus.Internal
public final class RegionBlockEntityTickPlan {
    /**
     * Initial capacity of the collected chunk list and the chunk visit map. Both grow when a level collects
     * more, so the hint only avoids re-growing the collections of a large level every tick.
     */
    private static final int CHUNK_CAPACITY_HINT = 256;
    private final RegionThreadingEngine engine;
    private final ServerLevel level;
    private final RegionTickBarrier barrier;
    /** The chunks this plan hands to the batch, in the order it collected them. */
    private final List<RegionBlockEntityTickChunk> chunks = new ArrayList<>(CHUNK_CAPACITY_HINT);
    /** The chunks of the tickers {@link #accept(TickingBlockEntity)} admitted, in acceptance order. */
    private final Long2ObjectMap<ExplicitUnit> explicitUnits = new Long2ObjectLinkedOpenHashMap<>();
    /**
     * Tickers a caller added to the level itself instead of through a chunk. The chunk index cannot see them,
     * so the plan admits them one by one instead of dropping them.
     */
    private final List<TickingBlockEntity> foreignTickers = new ArrayList<>();
    /**
     * Per-tick state of the chunks whose tickers {@link #accept(TickingBlockEntity)} visits, so the chunk
     * lookup and the entity-load read of a chunk happen once per tick instead of once per ticker. The plan
     * lives for exactly one level tick, so this map never outlives the tick it belongs to.
     */
    private final Long2ObjectMap<ChunkVisit> chunkVisits = new Long2ObjectOpenHashMap<>(CHUNK_CAPACITY_HINT);
    /** Scratch list of the level's chunk index, reused so collecting the plan allocates no chunk list. */
    private final List<LevelChunk> registeredChunks = new ArrayList<>(CHUNK_CAPACITY_HINT);
    /** Creation sequence of the explicitly accepted tickers, so their order survives the region grouping. */
    private long explicitSequence;
    private boolean canonicalSequence;
    private List<TickingBlockEntity> canonicalTickers = List.of();

    RegionBlockEntityTickPlan(RegionThreadingEngine engine, ServerLevel level, RegionTickBarrier barrier) {
        this.engine = engine;
        this.level = level;
        this.barrier = barrier;
    }

    /**
     * Collects the chunks whose tickers may tick on a region worker this tick.
     * <p>
     * Runs on the server thread and does no per-ticker work: the level's chunk index names the chunks that
     * hold tickers, and each of them is decided once. A chunk that is not block-ticking, whose entities are
     * still loading or that is no longer loaded ticks nothing, exactly like the vanilla loop skips it, and the
     * reason is counted so the skipped work is observable.
     */
    public void collect() {
        if (this.canonicalSequence) throw new IllegalStateException("Cannot mix canonical and indexed membership");
        this.level.collectForeignBlockEntityTickers(this.foreignTickers);
        for (final TickingBlockEntity ticker : this.foreignTickers) {
            // A ticker the level owns itself has no chunk whose snapshot the workers could iterate, so it
            // keeps the per-ticker admission of the explicit path instead of the chunk-indexed one.
            this.accept(ticker);
        }
        final boolean tickRateNormal = this.level.tickRateManager().runsNormally();
        this.registeredChunks.clear();
        this.level.collectBlockEntityTickerChunks(this.registeredChunks);
        final RegionLayout layout = this.engine.regionLayout();
        for (final LevelChunk chunk : this.registeredChunks) {
            final long chunkKey = chunk.getPos().toLong();
            if (!chunk.isLoaded()) {
                // The chunk left the level but is still registered: the vanilla loop would not tick it either.
                this.engine.countMissingBlockEntityChunk();
                continue;
            }
            if (!tickRateNormal || !this.level.shouldTickBlocksAt(chunkKey) || !chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
                this.engine.countNonTickingBlockEntityChunk();
                continue;
            }
            if (!this.level.areEntitiesLoaded(chunkKey)) {
                // The vanilla bound ticker skips its body while the entity load is pending, so the chunk is
                // not handed over and nothing of it ticks this tick.
                this.engine.countEntitiesLoadingBlockEntityChunk();
                continue;
            }
            final RegionBlockEntityTickChunkCache snapshot = this.snapshot(chunk, layout);
            if (snapshot.admissions().isEmpty()) {
                continue;
            }
            this.chunks.add(RegionBlockEntityTickChunk.ofSnapshot(chunk, snapshot));
        }
    }

    /**
     * Captures the actual Level list after its pending merge and fresh onLoad boundary. Ordinals are list
     * indexes, including entries subsequently skipped. Cached admissions are templates only: their wrapper
     * creation sequences never enter this phase. All user getters run after the Level released its lock.
     * The old indexed collector remains available to its compatibility callers.
     */
    public void collectCanonical() {
        if (this.canonicalSequence || !this.chunks.isEmpty() || !this.explicitUnits.isEmpty()) {
            throw new IllegalStateException("A canonical phase must collect exactly once from Level membership");
        }
        this.canonicalSequence = true;
        this.canonicalTickers = List.copyOf(this.level.snapshotBlockEntityTickers());
        final boolean runsNormally = this.level.tickRateManager().runsNormally();
        final List<TickingBlockEntity> removed = new ArrayList<>();
        try {
            for (int ordinal = 0; ordinal < this.canonicalTickers.size(); ++ordinal) {
                final TickingBlockEntity ticker = this.canonicalTickers.get(ordinal);
                if (ticker.isRemoved()) {
                    removed.add(ticker);
                    continue;
                }
                if (!runsNormally) continue;
                final BlockPos position = ticker.getPos().immutable();
                final long key = ChunkPos.asLong(position);
                ExplicitUnit unit = this.explicitUnits.get(key);
                if (unit == null) {
                    final ServerChunkCache source = this.level.getChunkSource();
                    final LevelChunk chunk = source == null ? null : source.getChunkNow(position.getX() >> 4, position.getZ() >> 4);
                    final var cache = chunk == null ? null : this.snapshot(chunk, this.engine.regionLayout());
                    unit = new ExplicitUnit(key, chunk, cache);
                    this.explicitUnits.put(key, unit);
                }
                final var template = unit.cache == null ? null : unit.cache.admission(ticker);
                final RegionBlockEntityTickAdmission admission;
                if (template != null && template.isCurrentForCollection()) {
                    admission = template.withSequence(ordinal);
                } else {
                    final String type = ticker.getType();
                    admission = new RegionBlockEntityTickAdmission(this.level, ticker, position, type,
                            LevelChunk.captureBlockEntityTickBinding(ticker, this.level),
                            RegionTickCompatibility.decideBlockEntityTick(this.level, type,
                                    this.engine.isInteriorRegionChunk(new ChunkPos(key)), this.engine.borderUpdatesEnabled()),
                            ordinal);
                }
                final RegionBlockEntityTickAdmission narrowed = this.engine.narrowCanonicalBlockEntity(admission);
                // Ineligible entries stay in the phase. They cannot go to a worker using the initial
                // decision; the complete server call rechecks eligibility at its actual list position.
                unit.add(narrowed, (!LevelChunk.isChunkOwnedTicker(ticker) || template != null)
                        && (unit.cache == null || unit.cache.allAdmitted())
                        && (unit.chunk == null || unit.chunk.isLoaded() && unit.chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING))
                        && this.level.shouldTickBlocksAt(position) && this.level.areEntitiesLoaded(key));
            }
        } finally {
            this.level.removeCollectedBlockEntityTickers(removed);
        }
    }

    /** Actual immutable reference membership; the index of each entry is its phase-local ordinal. */
    @org.jetbrains.annotations.VisibleForTesting
    public List<TickingBlockEntity> canonicalTickers() {
        return this.canonicalTickers;
    }

    /**
     * Admits one explicitly named ticker to the batch.
     * <p>
     * This is the per-ticker counterpart of the chunk-indexed {@link #collect()}: it is what a ticker the
     * level owns itself goes through, because the chunk index cannot see it, and it is what the admission
     * tests drive to reach the batch with tickers of their own. It evaluates the vanilla entity-load
     * eligibility of the ticker's chunk here, before the ticker is admitted: a worker cannot read the
     * server-owned entity section status, and the block ticking range alone does not prove that the chunk's
     * entities finished loading. A rejected ticker stays with the unchanged inline loop.
     *
     * @return {@code true} when the ticker was admitted to the worker batch
     */
    public boolean accept(TickingBlockEntity ticker) {
        if (this.canonicalSequence) throw new IllegalStateException("Cannot append explicit work to canonical membership");
        final BlockPos position = ticker.getPos();
        final int chunkX = SectionPos.blockToSectionCoord(position.getX());
        final int chunkZ = SectionPos.blockToSectionCoord(position.getZ());
        final long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        final ChunkVisit visit = this.engine.isBlockEntityAdmissionCacheEnabled() ? this.chunkVisit(chunkX, chunkZ, chunkKey) : ChunkVisit.MISSING;
        if (visit.kind() == ChunkVisit.Kind.LOADING) {
            return false;
        }
        final RegionBlockEntityTickChunkCache cache = visit.cache();
        final RegionBlockEntityTickAdmission cached = cache == null ? null : cache.admission(ticker);
        if (cached != null && cached.isCurrentForCollection()) {
            this.explicitUnit(chunkKey, visit.chunk(), cache).add(cached);
            return true;
        }
        final String type = ticker.getType();
        final RegionBlockEntityTickAdmission admission = new RegionBlockEntityTickAdmission(
                this.level,
                ticker,
                position,
                type,
                LevelChunk.captureBlockEntityTickBinding(ticker, this.level),
                RegionTickCompatibility.decideBlockEntityTick(this.level, type,
                        cache != null ? cache.interiorChunk() : this.engine.isInteriorRegionChunk(new ChunkPos(chunkX, chunkZ)),
                        this.engine.borderUpdatesEnabled()),
                this.explicitSequence++);
        if (visit.kind() == ChunkVisit.Kind.READY) {
            // The visit already evaluated the entity-load eligibility of this chunk on this server thread.
            if (!admission.isCurrent()) {
                return false;
            }
            this.explicitUnit(chunkKey, visit.chunk(), cache).add(admission);
            return true;
        }
        // No chunk of this position: the ticker keeps the uncached evaluation, which is the reference
        // behavior of the cached one.
        if (!this.level.areEntitiesLoaded(chunkKey) || !admission.isCurrent()) {
            return false;
        }
        this.explicitUnit(chunkKey, null, null).add(admission);
        return true;
    }

    /**
     * {@return the chunks this plan hands to the batch, in collection order}
     * <p>
     * The equivalence test of the chunk snapshot compares the tick set of the chunk-indexed plan with the one
     * of the explicit per-ticker admission, so the admitted chunks and their tickers are observable without
     * ticking.
     */
    @org.jetbrains.annotations.VisibleForTesting
    public List<RegionBlockEntityTickChunk> collectedChunks() {
        final List<RegionBlockEntityTickChunk> collected = new ArrayList<>(this.chunks.size() + this.explicitUnits.size());
        collected.addAll(this.chunks);
        for (final ExplicitUnit unit : this.explicitUnits.values()) {
            collected.add(unit.toUnit());
        }
        return Collections.unmodifiableList(collected);
    }

    /**
     * Ticks every collected chunk on its owning region worker and waits for the batch.
     */
    public void executeAndAwait() {
        this.executeAndAwait(this.barrier);
    }

    @org.jetbrains.annotations.VisibleForTesting
    public void executeAndAwait(RegionTickBarrier barrier) {
        // The server thread is done collecting and admitting the tickers; the attribution of the slice
        // closes its plan segment here, before the batch is published.
        RegionBlockEntityTickAttribution.endPlanSegment();
        try {
            for (final ExplicitUnit unit : this.explicitUnits.values()) {
                this.chunks.add(unit.toUnit());
            }
            this.engine.tickBlockEntities(this.level, this.chunks, barrier, this.canonicalSequence);
        } finally {
            this.chunks.clear();
            this.explicitUnits.clear();
            this.foreignTickers.clear();
            this.chunkVisits.clear();
            this.registeredChunks.clear();
            this.canonicalTickers = List.of();
        }
    }

    /**
     * {@return the admission snapshot of {@code chunk}, rebuilt when the chunk's ticker set or the region
     * layout changed since it was built, or on every tick when the admission cache is off}
     */
    private RegionBlockEntityTickChunkCache snapshot(LevelChunk chunk, RegionLayout layout) {
        final long tickerRevision = chunk.blockEntityTickerRevision();
        final RegionBlockEntityTickChunkCache cached = chunk.blockEntityTickAdmissionCache();
        if (this.engine.isBlockEntityAdmissionCacheEnabled() && cached != null && cached.isCurrent(layout, tickerRevision, this.engine.borderUpdatesEnabled())) {
            return cached;
        }
        final RegionBlockEntityTickChunkCache rebuilt = RegionBlockEntityTickChunkCache.build(
                this.level, chunk, layout, this.engine.isInteriorRegionChunk(chunk.getPos()), this.engine.borderUpdatesEnabled());
        // The setter releases the snapshot it replaces, so a chunk never holds more than one.
        chunk.setBlockEntityTickAdmissionCache(rebuilt);
        return rebuilt;
    }

    /**
     * {@return the state of the chunk of {@code position} for this tick, looked up once per chunk}
     */
    private ChunkVisit chunkVisit(int chunkX, int chunkZ, long chunkKey) {
        final ChunkVisit known = this.chunkVisits.get(chunkKey);
        if (known != null) {
            return known;
        }
        final ChunkVisit visit;
        final ServerChunkCache chunkSource = this.level.getChunkSource();
        final LevelChunk chunk = chunkSource == null ? null : chunkSource.getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            visit = ChunkVisit.MISSING;
        } else if (!this.level.areEntitiesLoaded(chunkKey)) {
            visit = ChunkVisit.LOADING;
        } else {
            visit = new ChunkVisit(ChunkVisit.Kind.READY, chunk, this.snapshot(chunk, this.engine.regionLayout()));
        }
        this.chunkVisits.put(chunkKey, visit);
        return visit;
    }

    /**
     * {@return the unit of the explicitly admitted tickers of the chunk at {@code chunkKey}, created on the
     * first acceptance}
     */
    private ExplicitUnit explicitUnit(long chunkKey, @Nullable LevelChunk chunk, @Nullable RegionBlockEntityTickChunkCache cache) {
        ExplicitUnit unit = this.explicitUnits.get(chunkKey);
        if (unit == null) {
            unit = new ExplicitUnit(chunkKey, chunk, cache);
            this.explicitUnits.put(chunkKey, unit);
        }
        return unit;
    }

    /**
     * The explicitly admitted tickers of one chunk, appended in acceptance order.
     */
    private static final class ExplicitUnit {
        private final long chunkKey;
        private final LevelChunk chunk;
        private final RegionBlockEntityTickChunkCache cache;
        private final List<RegionBlockEntityTickAdmission> admissions = new ArrayList<>();
        private boolean allAdmitted = true;

        ExplicitUnit(long chunkKey, @Nullable LevelChunk chunk, @Nullable RegionBlockEntityTickChunkCache cache) {
            this.chunkKey = chunkKey;
            this.chunk = chunk;
            this.cache = cache;
        }

        void add(RegionBlockEntityTickAdmission admission) {
            this.add(admission, true);
        }

        void add(RegionBlockEntityTickAdmission admission, boolean eligible) {
            this.admissions.add(admission);
            this.allAdmitted &= eligible && admission.isAdmitted() && admission.isCurrent();
        }

        RegionBlockEntityTickChunk toUnit() {
            return new RegionBlockEntityTickChunk(this.chunkKey, this.chunk, this.cache, this.admissions, this.allAdmitted);
        }
    }

    /**
     * What the plan decided for one visited chunk of this tick.
     */
    private record ChunkVisit(Kind kind, @Nullable LevelChunk chunk, @Nullable RegionBlockEntityTickChunkCache cache) {

        /** The chunk is not loaded (or the admission cache is off): its tickers keep the uncached evaluation. */
        static final ChunkVisit MISSING = new ChunkVisit(Kind.MISSING, null, null);
        /** The chunk's entities are still loading: every ticker of it keeps the unchanged inline path. */
        static final ChunkVisit LOADING = new ChunkVisit(Kind.LOADING, null, null);
        enum Kind {
            MISSING,
            LOADING,
            READY
        }
    }
}
