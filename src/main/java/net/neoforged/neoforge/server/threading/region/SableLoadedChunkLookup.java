/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Optional;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional compatibility with Sable's existing plot-gap result; never creates an empty world chunk.
 * <p>
 * Two rules keep this adapter from being able to stop a server:
 * <ul>
 * <li><b>It binds members by name and descriptor, never by enumerating them.</b> {@code Class.getMethod}
 * and {@code Class.getDeclaredMethod} both build the class's whole member array, and building it resolves
 * the types in every method signature. On a dedicated server the runtime dist cleaner refuses a signature
 * that mentions a client-only class, so enumerating a mod's API class throws. Resolving one
 * {@link MethodHandle} from a name and a {@link MethodType} does not touch the other signatures.</li>
 * <li><b>An unavailable binding is not an error.</b> Sable absent, Sable present with another API version,
 * a differently transformed chunk cache and a member that cannot be resolved all mean "this optimization is
 * off". The caller then keeps the plain loaded-chunk answer it had before the adapter existed.</li>
 * </ul>
 * Measured on the 178-mod pack before this rule: binding enumerated the container class, the dist cleaner
 * threw on a {@code ClientLevel} signature, and the server died 56 minutes after it started
 * ({@code crash-2026-11-09_17.01.56-server.txt}).
 */
@ApiStatus.Internal
public final class SableLoadedChunkLookup {
    private SableLoadedChunkLookup() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("SableLoadedChunkLookup");
    private static final String CONTAINER_CLASS = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final String EMPTY_CHUNK_FIELD = "sable$emptyChunk";
    private static final String GET_CONTAINER = "getContainer";
    private static final String IN_BOUNDS = "inBounds";

    /**
     * The resolved Sable API, already adapted to {@code Object} so the call sites do not depend on the
     * container type, which Forgia has no compile-time dependency on.
     *
     * @param container  {@code (Level)Object}, the static {@code getContainer(Level)}
     * @param inBounds   {@code (Object,int,int)Object}, the virtual {@code inBounds(int,int)} of a container,
     *                   boxed so the call site does not need a primitive signature-polymorphic invocation
     * @param emptyChunk the transformed chunk cache's plot-gap sentinel field
     */
    record Binding(MethodHandle container, MethodHandle inBounds, Field emptyChunk) {}

    private static final ClassValue<Optional<Binding>> BINDINGS = new ClassValue<>() {
        @Override
        protected Optional<Binding> computeValue(Class<?> type) {
            return bind(type);
        }
    };

    /**
     * Resolves the optional binding for one chunk-cache class, reporting every failure as "unavailable".
     * <p>
     * Each probe is separated so that the ordinary case - a server without Sable at all - costs no log line:
     * only a chunk cache that carries Sable's field but cannot be bound is worth telling an operator about.
     */
    static Optional<Binding> bind(Class<?> chunksClass) {
        final Field empty;
        try {
            empty = ServerChunkCache.class.getDeclaredField(EMPTY_CHUNK_FIELD);
        } catch (NoSuchFieldException noSableMixin) {
            // Sable is not installed, or its mixin did not apply: there is nothing to be compatible with.
            return Optional.empty();
        } catch (RuntimeException | LinkageError unusableField) {
            // The field exists but its type cannot be resolved on this dist; treat exactly like absent.
            LOGGER.info("Sable plot-gap compatibility is disabled: {} could not be inspected ({})",
                    chunksClass.getName(), unusableField.toString());
            return Optional.empty();
        }
        try {
            final Class<?> container = Class.forName(CONTAINER_CLASS, false, chunksClass.getClassLoader());
            return bindContainer(container, empty);
        } catch (ClassNotFoundException | RuntimeException | LinkageError absentContainer) {
            LOGGER.info("Sable plot-gap compatibility is disabled: {} is not usable on this server ({})",
                    CONTAINER_CLASS, absentContainer.toString());
            return Optional.empty();
        }
    }

    /**
     * Binds the known container API. Package-visible so the guard itself is covered by a test.
     * <p>
     * The {@code (Level)} overload is the one that binds, not {@code (ServerLevel)}. Sable declares three
     * overloads of {@code getContainer} - {@code (Level)}, {@code (ServerLevel)} and {@code (ClientLevel)} -
     * returning {@code SubLevelContainer}, {@code ServerSubLevelContainer} and {@code ClientSubLevelContainer}.
     * {@link MethodHandles.Lookup#findStatic} matches the descriptor <em>exactly</em> and does not widen a
     * covariant return, so asking for {@code (ServerLevel)SubLevelContainer} finds nothing and the adapter
     * disables itself. The {@code (Level)} overload returns {@code SubLevelContainer} exactly and accepts a
     * {@code ServerLevel}.
     *
     * @param container the Sable container class, public in the Sable API package
     * @param empty     the plot-gap sentinel field of the transformed chunk cache
     * @return the binding, or empty when this Sable version does not offer the API the adapter knows
     */
    static Optional<Binding> bindContainer(Class<?> container, Field empty) {
        try {
            final MethodHandle getContainer = findStatic(container, GET_CONTAINER,
                    MethodType.methodType(container, Level.class))
                            .asType(MethodType.methodType(Object.class, ServerLevel.class));
            final MethodHandle inBounds = findVirtual(container, IN_BOUNDS,
                    MethodType.methodType(boolean.class, int.class, int.class))
                            .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            empty.setAccessible(true);
            return Optional.of(new Binding(getContainer, inBounds, empty));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableApi) {
            LOGGER.info("Sable plot-gap compatibility is disabled: the {} API could not be bound ({})",
                    CONTAINER_CLASS, unavailableApi.toString());
            return Optional.empty();
        }
    }

    /**
     * Resolves one static member by descriptor. The public lookup is tried first because a mod class lives in
     * the unnamed module; the caller's own lookup covers a loader layout where the public one is refused.
     */
    private static MethodHandle findStatic(Class<?> owner, String name, MethodType type)
            throws ReflectiveOperationException {
        try {
            return MethodHandles.publicLookup().findStatic(owner, name, type);
        } catch (IllegalAccessException notPubliclyReadable) {
            return MethodHandles.lookup().findStatic(owner, name, type);
        }
    }

    /** Resolves one virtual member by descriptor; see {@link #findStatic}. */
    private static MethodHandle findVirtual(Class<?> owner, String name, MethodType type)
            throws ReflectiveOperationException {
        try {
            return MethodHandles.publicLookup().findVirtual(owner, name, type);
        } catch (IllegalAccessException notPubliclyReadable) {
            return MethodHandles.lookup().findVirtual(owner, name, type);
        }
    }

    /** Called only from the serviced server-thread lookup, after public getChunkNow returned no chunk. */
    @Nullable
    public static LevelChunk claimedEmptyChunk(ServerChunkCache chunks, ServerLevel level, int chunkX, int chunkZ) {
        final var server = level.getServer();
        if (server == null || !server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) {
            throw new IllegalStateException("Sable plot lookup requires the server thread");
        }
        final Binding binding = BINDINGS.get(chunks.getClass()).orElse(null);
        return binding == null ? null : resolve(binding, chunks, level, chunkX, chunkZ);
    }

    @Nullable
    private static LevelChunk resolve(Binding binding, Object chunks, ServerLevel level, int chunkX, int chunkZ) {
        final Object container;
        final Object inBounds;
        try {
            container = binding.container().invokeExact(level);
            if (container == null) {
                return null;
            }
            inBounds = binding.inBounds().invokeExact(container, chunkX, chunkZ);
        } catch (RuntimeException | Error failure) {
            // Sable's own failure keeps its identity; this is a broken provider, not an absent API.
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Sable plot lookup failed", failure);
        }
        if (!(Boolean) inBounds) {
            return null;
        }
        try {
            final Object empty = binding.emptyChunk().get(chunks);
            if (!(empty instanceof EmptyLevelChunk)) {
                throw new IllegalStateException("Sable's plot-gap sentinel is not an EmptyLevelChunk");
            }
            // This is the same Sable-owned sentinel returned by its getChunkFutureMainThread injection
            // for claimed plot gaps. Outside that domain no empty result is manufactured or substituted.
            return (LevelChunk) empty;
        } catch (IllegalAccessException inaccessibleSentinel) {
            throw new IllegalStateException("Cannot read Sable's plot-gap sentinel", inaccessibleSentinel);
        }
    }
}
