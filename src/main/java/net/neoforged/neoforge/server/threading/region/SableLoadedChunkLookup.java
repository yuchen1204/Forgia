/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Optional compatibility with Sable's existing plot-gap result; never creates an empty world chunk. */
@ApiStatus.Internal
public final class SableLoadedChunkLookup {
    private SableLoadedChunkLookup() {}

    private record Binding(Method container, Method inBounds, Field emptyChunk) {}

    private static final ClassValue<Optional<Binding>> BINDINGS = new ClassValue<>() {
        @Override
        protected Optional<Binding> computeValue(Class<?> type) {
            final Field empty;
            try {
                empty = ServerChunkCache.class.getDeclaredField("sable$emptyChunk");
            } catch (NoSuchFieldException absentMixin) {
                return Optional.empty();
            }
            try {
                final Class<?> container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer", false, type.getClassLoader());
                empty.setAccessible(true);
                return Optional.of(new Binding(container.getMethod("getContainer", ServerLevel.class),
                        container.getMethod("inBounds", int.class, int.class), empty));
            } catch (ReflectiveOperationException incompatibleVersion) {
                throw new IllegalStateException("Unsupported Sable loaded-plot lookup API", incompatibleVersion);
            }
        }
    };

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
        try {
            final Object container = binding.container.invoke(null, level);
            if (container == null || !(boolean) binding.inBounds.invoke(container, chunkX, chunkZ)) {
                return null;
            }
            final Object empty = binding.emptyChunk.get(chunks);
            if (!(empty instanceof EmptyLevelChunk)) {
                throw new IllegalStateException("Sable's plot-gap sentinel is not an EmptyLevelChunk");
            }
            // This is the same Sable-owned sentinel returned by its getChunkFutureMainThread injection
            // for claimed plot gaps. Outside that domain no empty result is manufactured or substituted.
            return (LevelChunk) empty;
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Sable plot lookup failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot resolve Sable's loaded plot-gap result", failure);
        }
    }
}
