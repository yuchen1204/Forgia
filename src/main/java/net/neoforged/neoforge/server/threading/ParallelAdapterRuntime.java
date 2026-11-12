/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAdmission;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickScope;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionWorkerScope;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Server-owned registration and short-lived prepared-data transport. Not a mod API. */
@ApiStatus.Internal
public final class ParallelAdapterRuntime implements ParallelApi.Server, ParallelApi.Registrar {
    private static final Map<MinecraftServer, ParallelAdapterRuntime> SERVERS = new IdentityHashMap<>();
    private static final ThreadLocal<PreparedSpawn> CURRENT = new ThreadLocal<>();
    private static final int MAX_ADAPTERS = 128;
    private final MinecraftServer server;
    private final BlockEntityOperationRuntime blockEntities;
    private volatile Map<ResourceLocation, Entry<?>> entries = new TreeMap<>();
    private volatile Map<ResourceLocation, TickEntry> tickEntries = new TreeMap<>();
    private volatile Map<ResourceLocation, TickEntry> tickTypes = new TreeMap<>();
    private volatile boolean open = true;
    private volatile boolean frozen;
    private boolean invalid;
    private final Map<String, ParallelApi.DataKey<?>> spawnClaims = new LinkedHashMap<>();

    private ParallelAdapterRuntime(MinecraftServer server) {
        this.server = Objects.requireNonNull(server);
        this.blockEntities = new BlockEntityOperationRuntime(server, () -> this.open);
    }

    /** Registration failure never leaves a published partially registered service. */
    public static void initialize(MinecraftServer server, Consumer<ParallelApi.Registrar> registration) {
        final ParallelAdapterRuntime runtime = new ParallelAdapterRuntime(server);
        runtime.requireServerThread();
        synchronized (SERVERS) {
            if (SERVERS.putIfAbsent(server, runtime) != null) {
                throw new IllegalStateException("Parallel API already initialized for this server");
            }
        }
        try {
            registration.accept(runtime);
            if (runtime.invalid) {
                throw new IllegalStateException("Parallel adapter registration failed validation");
            }
            runtime.entries = Collections.unmodifiableMap(new TreeMap<>(runtime.entries));
            runtime.tickEntries = Collections.unmodifiableMap(new TreeMap<>(runtime.tickEntries));
            runtime.tickTypes = Collections.unmodifiableMap(new TreeMap<>(runtime.tickTypes));
            runtime.frozen = true;
        } catch (RuntimeException | Error failure) {
            close(server);
            throw failure;
        }
    }

    public static Optional<ParallelApi.Server> find(MinecraftServer server) {
        synchronized (SERVERS) {
            final ParallelAdapterRuntime runtime = SERVERS.get(server);
            return runtime == null || !runtime.frozen || !runtime.open ? Optional.empty() : Optional.of(runtime);
        }
    }

    public static void close(MinecraftServer server) {
        synchronized (SERVERS) {
            final ParallelAdapterRuntime runtime = SERVERS.remove(server);
            if (runtime != null) {
                runtime.open = false;
                runtime.entries = Map.of();
                runtime.tickEntries = Map.of();
                runtime.tickTypes = Map.of();
            }
        }
    }

    private void requireOpen() {
        if (!this.open) {
            throw new IllegalStateException("Parallel API server has stopped");
        }
    }

    private void requireServerThread() {
        this.requireOpen();
        if (!this.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) {
            throw new IllegalStateException("Parallel registration/preparation requires the server thread");
        }
    }

    @Override
    public <S> void register(int major, ParallelApi.DataKey<S> key, ParallelApi.NaturalSpawnPreparation<S> preparation) {
        this.requireServerThread();
        if (this.frozen) {
            throw new IllegalStateException("Parallel adapter registration is frozen");
        }
        try {
            Objects.requireNonNull(key);
            Objects.requireNonNull(preparation);
            if (major != ParallelApi.MAJOR_VERSION) {
                throw new IllegalArgumentException("Unsupported parallel API major version: " + major);
            }
            if (this.entries.size() + this.tickEntries.size() >= MAX_ADAPTERS || this.entries.containsKey(key.id()) || this.tickEntries.containsKey(key.id())) {
                throw new IllegalArgumentException("Duplicate adapter id or adapter limit reached: " + key.id());
            }
            this.entries.put(key.id(), new Entry<>(key, preparation));
        } catch (RuntimeException failure) {
            this.invalid = true;
            throw failure;
        }
    }

    @Override
    public void registerBlockEntityTicks(int major, ResourceLocation id, Set<ResourceLocation> types, BlockEntityTickAdapter adapter) {
        this.requireServerThread();
        if (this.frozen) throw new IllegalStateException("Parallel adapter registration is frozen");
        try {
            Objects.requireNonNull(id);
            Objects.requireNonNull(adapter);
            final Set<ResourceLocation> registered = Set.copyOf(types);
            if (major != ParallelApi.MAJOR_VERSION || registered.isEmpty() || registered.size() > 128
                    || this.entries.size() + this.tickEntries.size() >= MAX_ADAPTERS
                    || this.entries.containsKey(id) || this.tickEntries.containsKey(id)
                    || registered.stream().anyMatch(this.tickTypes::containsKey)) {
                throw new IllegalArgumentException("Invalid/duplicate block-entity adapter, types or API version: " + id);
            }
            final TickEntry entry = new TickEntry(id, registered, adapter);
            this.tickEntries.put(id, entry);
            registered.forEach(type -> this.tickTypes.put(type, entry));
        } catch (RuntimeException | Error failure) {
            this.invalid = true;
            throw failure;
        }
    }

    @Override
    public List<BlockEntityTickAdapter.Status> blockEntityTickAdapters() {
        this.requireOpen();
        return this.tickEntries.values().stream().map(TickEntry::status).toList();
    }

    /** Read-only adapter-wide preflight happens before publication/leases or any original ticker body. */
    public static void prepareBlockEntityTicks(ServerLevel level) {
        final ParallelAdapterRuntime runtime;
        synchronized (SERVERS) {
            runtime = SERVERS.get(level.getServer());
        }
        if (runtime == null || !runtime.open || !runtime.frozen || runtime.tickEntries.isEmpty()) return;
        runtime.requireServerThread();
        if (runtime.currentPhase() != ParallelApi.Phase.QUIESCENT_SERVER) {
            throw new IllegalStateException("Block-entity preflight requires a quiescent server");
        }
        for (final TickEntry entry : runtime.tickEntries.values()) {
            final long start = System.nanoTime();
            try {
                entry.adapter.prepare(level);
            } finally {
                entry.preparationNanos.add(System.nanoTime() - start);
            }
        }
    }

    /** Cached type candidacy is not invocation admission. No registration survives server shutdown. */
    public static boolean hasBlockEntityTickAdapter(ServerLevel level, @Nullable String type) {
        return tickEntry(level, type) != null;
    }

    @Nullable
    private static TickEntry tickEntry(ServerLevel level, @Nullable String type) {
        final ResourceLocation key = type == null ? null : ResourceLocation.tryParse(type);
        if (key == null) return null;
        synchronized (SERVERS) {
            final ParallelAdapterRuntime runtime = SERVERS.get(level.getServer());
            return runtime == null || !runtime.open || !runtime.frozen ? null : runtime.tickTypes.get(key);
        }
    }

    /** Called immediately before a native body, under its owner's region context. Never loads a chunk. */
    public static boolean admitBlockEntityTick(RegionBlockEntityTickAdmission admission) {
        if (!RegionBlockEntityTickScope.isAdmitted(admission.level(), admission.ticker(), admission.position())) {
            throw new IllegalStateException("Block-entity admission requires the exact native ticker's ownership");
        }
        final TickEntry entry = tickEntry(admission.level(), admission.type());
        if (entry == null) return false;
        final long start = System.nanoTime();
        entry.checks.increment();
        try {
            final var pos = admission.position();
            final var source = admission.level().getChunkSource();
            final LevelChunk chunk = source == null ? null : source.getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            final BlockEntity blockEntity = chunk == null ? null : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            final BlockEntityTickAdapter.Decision decision;
            if (blockEntity == null || blockEntity.isRemoved() || blockEntity.getLevel() != admission.level()
                    || !pos.equals(blockEntity.getBlockPos()) || !admission.isCurrent()
                    || !LevelChunk.isBlockEntityTickSource(admission.binding().delegate(), blockEntity)
                    || !entry.types.contains(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()))) {
                decision = BlockEntityTickAdapter.Decision.INVALID_INSTANCE;
            } else {
                decision = Objects.requireNonNull(entry.adapter.check(blockEntity), "Null block-entity tick admission");
            }
            if (decision != BlockEntityTickAdapter.Decision.ADMITTED) {
                entry.refusals.increment();
                entry.reasons.get(decision).increment();
                return false;
            }
            return true;
        } finally {
            entry.nanos.add(System.nanoTime() - start);
        }
    }

    /** Submission/rejection/serial fallback never count as a successful native body. */
    public static void completeBlockEntityTick(RegionBlockEntityTickAdmission admission) {
        final TickEntry entry = tickEntry(admission.level(), admission.type());
        if (entry != null) entry.completed.increment();
    }

    @Override
    public void claimNaturalSpawning(ParallelApi.DataKey<?> key, String modId, java.util.function.BooleanSupplier verified) {
        this.requireServerThread();
        if (this.frozen) {
            throw new IllegalStateException("Parallel adapter registration is frozen");
        }
        try {
            final Entry<?> entry = this.entries.get(Objects.requireNonNull(key).id());
            if (entry == null || !entry.key.equals(key) || modId == null || modId.isBlank()
                    || this.spawnClaims.containsKey(modId) || !Objects.requireNonNull(verified).getAsBoolean()) {
                throw new IllegalArgumentException("Unverified, duplicate or unregistered natural-spawn claim: " + modId);
            }
            this.spawnClaims.put(modId, key);
        } catch (RuntimeException | Error failure) {
            this.invalid = true;
            throw failure;
        }
    }

    /** Resolves claims only after every registration and verification completed successfully. */
    public static java.util.Set<String> naturalSpawnClaims(MinecraftServer server) {
        synchronized (SERVERS) {
            final ParallelAdapterRuntime runtime = SERVERS.get(server);
            return runtime == null || !runtime.open || !runtime.frozen ? java.util.Set.of()
                    : java.util.Set.copyOf(runtime.spawnClaims.keySet());
        }
    }

    @Override
    public int apiMajorVersion() {
        this.requireOpen();
        return ParallelApi.MAJOR_VERSION;
    }

    @Override
    public Set<ParallelApi.Capability> capabilities() {
        this.requireOpen();
        return Set.of(ParallelApi.Capability.EXECUTION_CONTEXT, ParallelApi.Capability.NATURAL_SPAWN_PREPARATION,
                ParallelApi.Capability.NATURAL_SPAWN_PLAN_PREPARATION, ParallelApi.Capability.NATURAL_SPAWN_CLAIMS,
                ParallelApi.Capability.ORDERED_SPAWN_FINALIZATION, ParallelApi.Capability.BLOCK_ENTITY_OPERATIONS,
                ParallelApi.Capability.BLOCK_ENTITY_TICK_ADMISSION, ParallelApi.Capability.SCHEDULED_TICK_DIAGNOSTICS);
    }

    @Override
    public BlockEntityOperations blockEntities() {
        this.requireOpen();
        return this.blockEntities;
    }

    @Override
    public List<ScheduledTickStatus> scheduledTicks() {
        this.requireOpen();
        return RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine ? engine.scheduledTickStatus() : List.of();
    }

    @Override
    public boolean regionThreadingInstalled() {
        this.requireOpen();
        return RegionThreadingManager.isInstalled();
    }

    @Override
    public ParallelApi.Phase currentPhase() {
        this.requireOpen();
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (this.server.isSameThread() && !RegionWorkerScope.isNativeWorkerThread()) {
            if (threading instanceof RegionThreadingEngine engine && !engine.isQuiescentForParallelApi()) {
                return ParallelApi.Phase.SERVER_SERVICE;
            }
            return ParallelApi.Phase.QUIESCENT_SERVER;
        }
        if (threading instanceof RegionThreadingEngine engine && engine.isRegionTickBatchWorkerThread()
                && engine.currentContext().level().map(level -> level.getServer() == this.server).orElse(false)) {
            return ParallelApi.Phase.REGION_WORKER;
        }
        return ParallelApi.Phase.EXTERNAL;
    }

    @Override
    public List<ParallelApi.AdapterStatus> adapters() {
        this.requireOpen();
        final List<ParallelApi.AdapterStatus> result = new ArrayList<>();
        this.entries.values().forEach(entry -> result.add(new ParallelApi.AdapterStatus(entry.key.id(),
                entry.ready.sum(), entry.serial.sum(), entry.serialPlans.sum(), entry.nanos.sum(), Optional.ofNullable(entry.lastReason))));
        return List.copyOf(result);
    }

    /** Before the core constructs its own cap state, an adapter may retain the full native serial plan. */
    public static PreparedSpawnPlan preparePlan(ServerLevel level) {
        final ParallelAdapterRuntime runtime;
        synchronized (SERVERS) {
            runtime = SERVERS.get(level.getServer());
        }
        if (runtime == null || !runtime.frozen || !runtime.open || runtime.entries.isEmpty()) {
            return PreparedSpawnPlan.EMPTY;
        }
        runtime.requireServerThread();
        if (runtime.currentPhase() != ParallelApi.Phase.QUIESCENT_SERVER) {
            throw new IllegalStateException("Cannot prepare parallel spawn data while workers are active");
        }
        final Map<ParallelApi.DataKey<?>, Object> frozen = new LinkedHashMap<>();
        final List<Entry<?>> perUnit = new ArrayList<>();
        boolean orderedFinalization = false;
        final var context = new ParallelApi.SpawnPlan(level.dimension(), level.getGameTime());
        for (final Entry<?> entry : runtime.entries.values()) {
            final long start = System.nanoTime();
            try {
                final var result = Objects.requireNonNull(entry.preparation.preparePlan(context), "Null plan preparation");
                if (result instanceof ParallelApi.ServerRequired<?> serial) {
                    entry.serialPlans.increment();
                    entry.lastReason = serial.reason();
                    return new PreparedSpawnPlan(runtime, level, context, Map.of(), List.of(), false, false);
                } else if (result instanceof ParallelApi.Ready<?> ready) {
                    frozen.put(entry.key, entry.key.snapshotType().cast(ready.snapshot()));
                    orderedFinalization |= ready.orderedFinalization();
                } else {
                    perUnit.add(entry);
                }
            } finally {
                entry.nanos.add(System.nanoTime() - start);
            }
        }
        return new PreparedSpawnPlan(runtime, level, context, Map.copyOf(frozen), List.copyOf(perUnit), true, orderedFinalization);
    }

    /** Convenience for a standalone unit; production plans reuse the same prepared-plan identity. */
    public static PreparedSpawn prepare(ServerLevel level, int chunkX, int chunkZ) {
        return prepare(level, chunkX, chunkZ, preparePlan(level));
    }

    public static PreparedSpawn prepare(ServerLevel level, int chunkX, int chunkZ, PreparedSpawnPlan plan) {
        if (plan.runtime == null) return PreparedSpawn.EMPTY;
        plan.requireCurrent(level);
        final var runtime = plan.runtime;
        runtime.requireServerThread();
        if (runtime.currentPhase() != ParallelApi.Phase.QUIESCENT_SERVER) {
            throw new IllegalStateException("Cannot prepare parallel spawn data while workers are active");
        }
        final var unit = new ParallelApi.SpawnUnit(level.dimension(), chunkX, chunkZ, level.getGameTime());
        if (!plan.workerReady) return new PreparedSpawn(runtime, level, unit, Map.of(), false);
        final Map<ParallelApi.DataKey<?>, Object> snapshots = new LinkedHashMap<>(plan.frozen);
        for (final var key : plan.frozen.keySet()) runtime.entries.get(key.id()).ready.increment();
        for (final Entry<?> entry : plan.perUnit) {
            if (!entry.prepare(unit, snapshots)) {
                return new PreparedSpawn(runtime, level, unit, Map.of(), false);
            }
        }
        return new PreparedSpawn(runtime, level, unit, Map.copyOf(snapshots), true);
    }

    public static final class PreparedSpawnPlan {
        private static final PreparedSpawnPlan EMPTY = new PreparedSpawnPlan(null, null, null, Map.of(), List.of(), true, false);
        @Nullable
        private final ParallelAdapterRuntime runtime;
        @Nullable
        private final ServerLevel level;
        @Nullable
        private final ParallelApi.SpawnPlan context;
        private final Map<ParallelApi.DataKey<?>, Object> frozen;
        private final List<Entry<?>> perUnit;
        private final boolean workerReady;
        private final boolean orderedFinalization;

        private PreparedSpawnPlan(@Nullable ParallelAdapterRuntime runtime, @Nullable ServerLevel level,
                @Nullable ParallelApi.SpawnPlan context, Map<ParallelApi.DataKey<?>, Object> frozen,
                List<Entry<?>> perUnit, boolean workerReady, boolean orderedFinalization) {
            this.runtime = runtime;
            this.level = level;
            this.context = context;
            this.frozen = frozen;
            this.perUnit = perUnit;
            this.workerReady = workerReady;
            this.orderedFinalization = orderedFinalization;
        }

        public boolean workerReady() {
            return this.workerReady;
        }

        public boolean orderedFinalization() {
            return this.orderedFinalization;
        }

        private void requireCurrent(ServerLevel level) {
            if (this.runtime != null && (!this.runtime.open || level != this.level || level.getServer() != this.runtime.server
                    || level.getGameTime() != this.context.gameTime() || !level.dimension().equals(this.context.dimension()))) {
                throw new IllegalStateException("Prepared spawn plan has retired");
            }
        }

        /** Frozen plan-wide rules also apply to fallback units; per-unit partial preparation is discarded. */
        public void runSerial(ServerLevel level, int x, int z, Runnable action) {
            this.requireCurrent(level);
            if (!this.workerReady) throw new IllegalStateException("A native serial plan is required");
            if (this.runtime != null && this.runtime.currentPhase() != ParallelApi.Phase.QUIESCENT_SERVER) {
                throw new IllegalStateException("Serial spawn fallback requires a quiescent server");
            }
            if (this.frozen.isEmpty()) {
                ParallelAdapterRuntime.runSerial(action);
            } else {
                if (this.runtime.currentPhase() != ParallelApi.Phase.QUIESCENT_SERVER) {
                    throw new IllegalStateException("Serial spawn fallback requires a quiescent server");
                }
                new PreparedSpawn(this.runtime, level, new ParallelApi.SpawnUnit(level.dimension(), x, z, level.getGameTime()),
                        this.frozen, true).run(level, action);
            }
        }
    }

    /** Carries an already-started unit across its parked server continuation; never prepares or replays it. */
    public static <T> java.util.function.Supplier<T> captureOrderedSpawnContext(ServerLevel level, java.util.function.Supplier<T> action) {
        final PreparedSpawn captured = CURRENT.get();
        return () -> {
            if (!level.getServer().isSameThread()
                    || !(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)
                    || !engine.isOrderedSpawnServerPhase(level)) {
                throw new IllegalStateException("Prepared continuation requires an ordered server spawn phase");
            }
            if (captured != null && captured.runtime != null && (!captured.runtime.open || captured.level != level
                    || captured.runtime.server != level.getServer() || captured.unit.gameTime() != level.getGameTime())) {
                throw new IllegalStateException("Prepared spawn continuation has retired");
            }
            final PreparedSpawn previous = CURRENT.get();
            if (captured == null) CURRENT.remove();
            else CURRENT.set(captured);
            try {
                return action.get();
            } finally {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
            }
        };
    }

    public static <S> Optional<S> prepared(ParallelApi.DataKey<S> key) {
        final PreparedSpawn scope = CURRENT.get();
        if (scope == null || scope.runtime == null || !scope.runtime.open) {
            return Optional.empty();
        }
        return Optional.ofNullable(scope.snapshots.get(key)).map(key.snapshotType()::cast);
    }

    public static Optional<ParallelApi.SpawnUnit> currentUnit() {
        final PreparedSpawn scope = CURRENT.get();
        return scope == null || scope.runtime == null || !scope.runtime.open ? Optional.empty() : Optional.of(scope.unit);
    }

    /** A serial or nested spawn must never inherit another unit's prepared data. */
    public static void runSerial(Runnable action) {
        final PreparedSpawn previous = CURRENT.get();
        CURRENT.remove();
        try {
            action.run();
        } finally {
            if (previous != null) {
                CURRENT.set(previous);
            }
        }
    }

    public static final class PreparedSpawn {
        private static final PreparedSpawn EMPTY = new PreparedSpawn(null, null, null, Map.of(), true);
        @Nullable
        private final ParallelAdapterRuntime runtime;
        @Nullable
        private final ServerLevel level;
        @Nullable
        private final ParallelApi.SpawnUnit unit;
        private final Map<ParallelApi.DataKey<?>, Object> snapshots;
        private final boolean workerReady;
        private final AtomicBoolean started = new AtomicBoolean();

        private PreparedSpawn(@Nullable ParallelAdapterRuntime runtime, @Nullable ServerLevel level, @Nullable ParallelApi.SpawnUnit unit,
                Map<ParallelApi.DataKey<?>, Object> snapshots, boolean workerReady) {
            this.runtime = runtime;
            this.level = level;
            this.unit = unit;
            this.snapshots = snapshots;
            this.workerReady = workerReady;
        }

        public boolean workerReady() {
            return this.workerReady;
        }

        /** Returns false for retired work; no callback and no stale snapshot access on the next server. */
        public boolean run(ServerLevel level, Runnable action) {
            if (this.runtime == null) {
                runSerial(action);
                return true;
            }
            if (!this.runtime.open || level != this.level || level.getServer() != this.runtime.server
                    || !level.dimension().equals(this.unit.dimension()) || level.getGameTime() != this.unit.gameTime()) {
                return false;
            }
            final boolean orderedServer = RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine
                    && engine.isOrderedSpawnServerPhase(level);
            if (!this.workerReady || this.runtime.currentPhase() == ParallelApi.Phase.EXTERNAL
                    || (this.runtime.currentPhase() == ParallelApi.Phase.SERVER_SERVICE && !orderedServer)) {
                throw new IllegalStateException("Prepared spawn body requires admitted worker or quiescent server execution");
            }
            if (!this.started.compareAndSet(false, true)) {
                throw new IllegalStateException("Prepared spawn unit cannot be replayed");
            }
            final PreparedSpawn previous = CURRENT.get();
            CURRENT.set(this);
            try {
                action.run();
            } finally {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
            return true;
        }
    }

    private static final class TickEntry {
        private final ResourceLocation id;
        private final Set<ResourceLocation> types;
        private final BlockEntityTickAdapter adapter;
        private final LongAdder checks = new LongAdder();
        private final LongAdder refusals = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder preparationNanos = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        private final Map<BlockEntityTickAdapter.Decision, LongAdder> reasons = new EnumMap<>(BlockEntityTickAdapter.Decision.class);

        private TickEntry(ResourceLocation id, Set<ResourceLocation> types, BlockEntityTickAdapter adapter) {
            this.id = id;
            this.types = types;
            this.adapter = adapter;
            for (final var decision : BlockEntityTickAdapter.Decision.values()) this.reasons.put(decision, new LongAdder());
        }

        private BlockEntityTickAdapter.Status status() {
            final Map<BlockEntityTickAdapter.Decision, Long> counts = new EnumMap<>(BlockEntityTickAdapter.Decision.class);
            this.reasons.forEach((reason, count) -> {
                if (count.sum() != 0L) counts.put(reason, count.sum());
            });
            return new BlockEntityTickAdapter.Status(this.id, this.types, this.checks.sum(), this.refusals.sum(),
                    this.completed.sum(), this.preparationNanos.sum(), this.nanos.sum(), counts);
        }
    }

    private static final class Entry<S> {
        private final ParallelApi.DataKey<S> key;
        private final ParallelApi.NaturalSpawnPreparation<S> preparation;
        private final LongAdder ready = new LongAdder();
        private final LongAdder serial = new LongAdder();
        private final LongAdder serialPlans = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        @Nullable
        private volatile ResourceLocation lastReason;

        private Entry(ParallelApi.DataKey<S> key, ParallelApi.NaturalSpawnPreparation<S> preparation) {
            this.key = key;
            this.preparation = preparation;
        }

        private boolean prepare(ParallelApi.SpawnUnit unit, Map<ParallelApi.DataKey<?>, Object> snapshots) {
            final long start = System.nanoTime();
            try {
                final ParallelApi.Preparation<S> result = Objects.requireNonNull(this.preparation.prepare(unit), "Null preparation result");
                if (result instanceof ParallelApi.Ready<S> ready) {
                    if (ready.orderedFinalization()) throw new IllegalStateException("Ordered finalization must be requested by preparePlan");
                    snapshots.put(this.key, this.key.snapshotType().cast(ready.snapshot()));
                    this.ready.increment();
                    return true;
                }
                if (!(result instanceof ParallelApi.ServerRequired<S> serial)) {
                    throw new IllegalStateException("PerUnit is only valid from preparePlan");
                }
                this.lastReason = serial.reason();
                this.serial.increment();
                return false;
            } finally {
                this.nanos.add(System.nanoTime() - start);
            }
        }
    }
}
