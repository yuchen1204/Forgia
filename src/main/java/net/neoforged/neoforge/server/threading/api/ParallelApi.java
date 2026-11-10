/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import org.jetbrains.annotations.ApiStatus;

/**
 * Experimental mod integration contracts. Obtain a server-scoped service after adapter registration.
 * Registration does not bypass compatibility restrictions or grant world ownership. No API reference may be
 * bundled into a mod jar: implementations and API classes are provided by Forgia.
 */
@ApiStatus.Experimental
public final class ParallelApi {
    public static final int MAJOR_VERSION = 1;

    private ParallelApi() {}

    /** Empty before registration and after shutdown. A retained service becomes unusable after shutdown. */
    public static Optional<Server> forServer(MinecraftServer server) {
        return ParallelAdapterRuntime.find(Objects.requireNonNull(server));
    }

    public enum Capability {
        EXECUTION_CONTEXT,
        NATURAL_SPAWN_PREPARATION
    }

    /** Phase describes execution, not permission to mutate arbitrary objects. */
    public enum Phase {
        QUIESCENT_SERVER,
        SERVER_SERVICE,
        REGION_WORKER,
        EXTERNAL
    }

    public interface Server {
        int apiMajorVersion();

        /** Capabilities describe available protocols, not whether a particular slice is enabled. */
        Set<Capability> capabilities();

        boolean regionThreadingInstalled();

        Phase currentPhase();

        List<AdapterStatus> adapters();
    }

    /** Registration is only valid inside the registration event, on its server thread. */
    public interface Registrar {
        <S> void register(int apiMajorVersion, DataKey<S> key, NaturalSpawnPreparation<S> preparation);
    }

    /** Key identifies one adapter and its snapshot type. An id may be registered only once per server. */
    public record DataKey<S>(ResourceLocation id, Class<S> snapshotType) {
        public DataKey {
            Objects.requireNonNull(id);
            Objects.requireNonNull(snapshotType);
            if (snapshotType.isPrimitive()) {
                throw new IllegalArgumentException("Snapshot type must be a reference type");
            }
        }
    }

    /**
     * Metadata for one chunk's complete natural-spawning call. Coordinates name the source chunk, not a bound
     * on candidate positions. Preparation must be pure: no RNG consumption, events, entity construction or world
     * mutations. This first capability offers no structure/chunk loading API.
     */
    public record SpawnUnit(ResourceKey<Level> dimension, int chunkX, int chunkZ, long gameTime) {
        public SpawnUnit {
            Objects.requireNonNull(dimension);
        }
    }

    @FunctionalInterface
    public interface NaturalSpawnPreparation<S> {
        /**
         * Runs before this unit's body, on the quiescent server thread. Capture a version of your own immutable
         * rules here. All adapters must return Ready for worker admission; an explicit ServerRequired keeps the
         * original serial body. Exceptions propagate as failures, never as successful preparation or a retry.
         */
        Preparation<S> prepare(SpawnUnit unit);
    }

    public sealed interface Preparation<S> permits Ready, ServerRequired {}

    /**
     * Snapshot must be deeply immutable and detached from live world objects. Runtime type checking cannot
     * prove immutability; do not include entities, chunks, mutable NBT, collections or shared RNGs.
     */
    public record Ready<S>(S snapshot) implements Preparation<S> {
        public Ready {
            Objects.requireNonNull(snapshot);
        }
    }

    /** Explicit pre-body fallback. A reason is namespaced and must not encode per-position identifiers. */
    public record ServerRequired<S>(ResourceLocation reason) implements Preparation<S> {
        public ServerRequired {
            Objects.requireNonNull(reason);
        }
    }

    public record AdapterStatus(ResourceLocation id, long preparedUnits, long serialUnits, long preparationNanos,
            Optional<ResourceLocation> lastSerialReason) {}

    /**
     * Read prepared data only during the matching natural-spawn body (including its synchronous events).
     * Empty on vanilla/off paths, serial fallback, other threads and after the body. Presence does NOT mean a
     * filter already ran or authorize skipping another listener. Never use this as an event-cancellation flag.
     */
    public static <S> Optional<S> prepared(DataKey<S> key) {
        return ParallelAdapterRuntime.prepared(Objects.requireNonNull(key));
    }

    public static Optional<SpawnUnit> currentSpawnUnit() {
        return ParallelAdapterRuntime.currentUnit();
    }
}
