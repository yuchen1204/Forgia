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
        NATURAL_SPAWN_PREPARATION,
        NATURAL_SPAWN_PLAN_PREPARATION,
        NATURAL_SPAWN_CLAIMS,
        ORDERED_SPAWN_FINALIZATION,
        BLOCK_ENTITY_OPERATIONS,
        BLOCK_ENTITY_TICK_ADMISSION,
        SCHEDULED_TICK_DIAGNOSTICS
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

        /** Whole two-owner operations with explicit results; does not widen any worker tick declaration. */
        default BlockEntityOperations blockEntities() {
            throw new UnsupportedOperationException("This server does not support block entity operations");
        }

        /** Per-instance native ticker admission diagnostics; registration does not enable any engine switch. */
        default List<BlockEntityTickAdapter.Status> blockEntityTickAdapters() {
            return List.of();
        }

        /** Empty when no region engine is installed; collected/accepted/completed units remain distinct. */
        default List<ScheduledTickStatus> scheduledTicks() {
            return List.of();
        }
    }

    /** Registration is only valid inside the registration event, on its server thread. */
    public interface Registrar {
        /** Available during registration; this describes protocols, never adapter activation. */
        default Set<Capability> capabilities() {
            return Set.of();
        }

        <S> void register(int apiMajorVersion, DataKey<S> key, NaturalSpawnPreparation<S> preparation);

        /**
         * Registers disjoint block-entity type ids for audited, interior-only original worker ticks. A
         * server-thread-only declaration takes precedence. The predicate runs afresh before each body;
         * a refusal retains the full original server tick. Duplicate ids/types invalidate registration.
         */
        default void registerBlockEntityTicks(int apiMajorVersion, ResourceLocation id, Set<ResourceLocation> types,
                BlockEntityTickAdapter adapter) {
            throw new UnsupportedOperationException("This registrar does not support native block-entity admission");
        }

        /**
         * Claims only the natural-spawn restriction for a mod. The registered preparation must conservatively
         * return ServerRequired for every unsupported configuration. Verification runs before activation; a
         * false result aborts registration. This is an adapter author's explicit contract, not a safety proof.
         */
        default void claimNaturalSpawning(DataKey<?> key, String modId, java.util.function.BooleanSupplier verified) {
            throw new UnsupportedOperationException("This registrar does not support natural-spawn claims");
        }
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

    public record SpawnPlan(ResourceKey<Level> dimension, long gameTime) {
        public SpawnPlan {
            Objects.requireNonNull(dimension);
        }
    }

    @FunctionalInterface
    public interface NaturalSpawnPreparation<S> {
        /**
         * Before cap/state construction. ServerRequired keeps the entire native serial plan. Ready freezes
         * data for every unit, including serial fallback bodies; PerUnit preserves the per-unit protocol.
         */
        default Preparation<S> preparePlan(SpawnPlan plan) {
            return new PerUnit<>();
        }

        /**
         * Runs before this unit's body, on the quiescent server thread. Capture a version of your own immutable
         * rules here. All adapters must return Ready for worker admission; an explicit ServerRequired keeps the
         * original serial body. Exceptions propagate as failures, never as successful preparation or a retry.
         */
        Preparation<S> prepare(SpawnUnit unit);
    }

    public sealed interface Preparation<S> permits Ready, ServerRequired, PerUnit {}

    public record PerUnit<S>() implements Preparation<S> {}

    /**
     * Snapshot must be deeply immutable and detached from live world objects. Runtime type checking cannot
     * prove immutability; do not include entities, chunks, mutable NBT, collections or shared RNGs.
     * orderedFinalization may only be requested by preparePlan. It keeps selection on workers but moves
     * the complete native finalization/admission continuation to the server with peers parked. The
     * one-argument constructor retains the original worker-finalization protocol.
     */
    public record Ready<S>(S snapshot, boolean orderedFinalization) implements Preparation<S> {
        public Ready(S snapshot) {
            this(snapshot, false);
        }

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

    public record AdapterStatus(ResourceLocation id, long preparedUnits, long serialUnits, long serialPlans, long preparationNanos,
            Optional<ResourceLocation> lastSerialReason) {}

    /**
     * Read prepared data only during the matching natural-spawn body (including its synchronous events).
     * Empty on native/off paths, other threads and after the body. Per-unit fallback discards per-unit data;
     * plan-wide Ready data is retained for that plan's serial fallback bodies. Presence does NOT mean a
     * filter already ran or authorize skipping another listener. Never use this as an event-cancellation flag.
     */
    public static <S> Optional<S> prepared(DataKey<S> key) {
        return ParallelAdapterRuntime.prepared(Objects.requireNonNull(key));
    }

    public static Optional<SpawnUnit> currentSpawnUnit() {
        return ParallelAdapterRuntime.currentUnit();
    }
}
