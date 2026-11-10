/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.ArrayList;
import java.util.Collections;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
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
    private volatile Map<ResourceLocation, Entry<?>> entries = new TreeMap<>();
    private volatile boolean open = true;
    private volatile boolean frozen;
    private boolean invalid;

    private ParallelAdapterRuntime(MinecraftServer server) {
        this.server = Objects.requireNonNull(server);
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
            if (this.entries.size() >= MAX_ADAPTERS || this.entries.containsKey(key.id())) {
                throw new IllegalArgumentException("Duplicate adapter id or adapter limit reached: " + key.id());
            }
            this.entries.put(key.id(), new Entry<>(key, preparation));
        } catch (RuntimeException failure) {
            this.invalid = true;
            throw failure;
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
        return Set.of(ParallelApi.Capability.EXECUTION_CONTEXT, ParallelApi.Capability.NATURAL_SPAWN_PREPARATION);
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
                entry.ready.sum(), entry.serial.sum(), entry.nanos.sum(), Optional.ofNullable(entry.lastReason))));
        return List.copyOf(result);
    }

    /** Empty fast path when no preparation adapters are registered for this server. */
    public static PreparedSpawn prepare(ServerLevel level, int chunkX, int chunkZ) {
        final ParallelAdapterRuntime runtime;
        synchronized (SERVERS) {
            runtime = SERVERS.get(level.getServer());
        }
        if (runtime == null || !runtime.frozen || !runtime.open || runtime.entries.isEmpty()) {
            return PreparedSpawn.EMPTY;
        }
        runtime.requireServerThread();
        if (runtime.currentPhase() != ParallelApi.Phase.QUIESCENT_SERVER) {
            throw new IllegalStateException("Cannot prepare parallel spawn data while workers are active");
        }
        final ParallelApi.SpawnUnit unit = new ParallelApi.SpawnUnit(level.dimension(), chunkX, chunkZ, level.getGameTime());
        final Map<ParallelApi.DataKey<?>, Object> snapshots = new LinkedHashMap<>();
        for (final Entry<?> entry : runtime.entries.values()) {
            if (!entry.prepare(unit, snapshots)) {
                // No body has begun. Discard all partial snapshots; serial execution must use original logic.
                return new PreparedSpawn(runtime, level, unit, Map.of(), false);
            }
        }
        return new PreparedSpawn(runtime, level, unit, Map.copyOf(snapshots), true);
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
            if (!this.workerReady || this.runtime.currentPhase() == ParallelApi.Phase.EXTERNAL
                    || this.runtime.currentPhase() == ParallelApi.Phase.SERVER_SERVICE) {
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

    private static final class Entry<S> {
        private final ParallelApi.DataKey<S> key;
        private final ParallelApi.NaturalSpawnPreparation<S> preparation;
        private final LongAdder ready = new LongAdder();
        private final LongAdder serial = new LongAdder();
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
                    snapshots.put(this.key, this.key.snapshotType().cast(ready.snapshot()));
                    this.ready.increment();
                    return true;
                }
                final var serial = (ParallelApi.ServerRequired<S>) result;
                this.lastReason = serial.reason();
                this.serial.increment();
                return false;
            } finally {
                this.nanos.add(System.nanoTime() - start);
            }
        }
    }
}
