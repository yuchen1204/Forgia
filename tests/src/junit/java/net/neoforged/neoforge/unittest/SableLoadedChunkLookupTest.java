/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.region.SableLoadedChunkLookup;
import org.junit.jupiter.api.Test;

class SableLoadedChunkLookupTest {
    /** Tests the optional adapter's actual reflection path without adding fake Sable classes to the runtime. */
    public static class Provider {
        public Object emptyChunk = mock(EmptyLevelChunk.class);
        static Provider current;
        public boolean claimed = true;
        public RuntimeException failure;

        public static Provider getContainer(ServerLevel level) {
            return current;
        }

        public boolean inBounds(int x, int z) {
            if (this.failure != null) throw this.failure;
            return this.claimed && x == 1280065 && z == 1280960;
        }
    }

    private LevelChunk resolve(Provider provider, int x, int z) throws Throwable {
        Provider.current = provider;
        final Class<?> binding = Class.forName(SableLoadedChunkLookup.class.getName() + "$Binding");
        final var ctor = binding.getDeclaredConstructor(Method.class, Method.class, java.lang.reflect.Field.class);
        ctor.setAccessible(true);
        final Object access = ctor.newInstance(Provider.class.getMethod("getContainer", ServerLevel.class),
                Provider.class.getMethod("inBounds", int.class, int.class), Provider.class.getField("emptyChunk"));
        final Method resolve = SableLoadedChunkLookup.class.getDeclaredMethod("resolve", binding, Object.class, ServerLevel.class, int.class, int.class);
        resolve.setAccessible(true);
        try {
            return (LevelChunk) resolve.invoke(null, access, provider, mock(ServerLevel.class), x, z);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            Provider.current = null;
        }
    }

    @Test
    void claimedGapUsesExactlyTheProvidersSentinel() throws Throwable {
        final var provider = new Provider();
        assertSame(provider.emptyChunk, this.resolve(provider, 1280065, 1280960));
    }

    @Test
    void missingNormalWorldChunkNeverBecomesAir() throws Throwable {
        assertNull(this.resolve(new Provider(), 1, 1));
        final var provider = new Provider();
        provider.claimed = false;
        assertNull(this.resolve(provider, 1280065, 1280960));
    }

    @Test
    void absentContainerDoesNotSupplyAnEmptyChunk() throws Throwable {
        assertNull(this.resolve(null, 1280065, 1280960));
    }

    @Test
    void wrongSentinelTypeIsAnExplicitCompatibilityFailure() {
        final var provider = new Provider();
        provider.emptyChunk = mock(LevelChunk.class);
        assertThrows(IllegalStateException.class, () -> this.resolve(provider, 1280065, 1280960));
    }

    @Test
    void providerFailureKeepsItsOriginalCause() {
        final var provider = new Provider();
        final var failure = new IllegalStateException("plot lookup failed");
        provider.failure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> this.resolve(provider, 1280065, 1280960)));
    }
}
