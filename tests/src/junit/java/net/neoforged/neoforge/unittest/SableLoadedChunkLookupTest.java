/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.region.SableLoadedChunkLookup;
import org.junit.jupiter.api.Test;

/**
 * Covers the optional Sable adapter without adding fake Sable classes to the runtime.
 * <p>
 * The binding is resolved through {@code MethodHandles} on purpose: {@code Class.getMethod} builds the whole
 * member array of the class it is called on, and building it resolves every signature. On a dedicated server
 * the runtime dist cleaner refuses a signature that mentions a client-only class, so enumerating a mod's API
 * class throws. That is the crash this test set guards: {@code crash-2026-11-09_17.01.56-server.txt} took the
 * 178-mod pack down 56 minutes after start, and every binding failure below must degrade to "no
 * compatibility" instead of propagating.
 */
public class SableLoadedChunkLookupTest {
    /** Stands in for {@code dev.ryanhcode.sable.api.sublevel.SubLevelContainer}. */
    public static class Container {
        public Object emptyChunk = mock(EmptyLevelChunk.class);
        static Container current;
        public boolean claimed = true;
        public RuntimeException failure;

        public static Container getContainer(Level level) {
            return current;
        }

        public boolean inBounds(int x, int z) {
            if (this.failure != null) throw this.failure;
            return this.claimed && x == 1280065 && z == 1280960;
        }
    }

    /** A container that does not offer the API the adapter knows. */
    public static class UnrelatedApi {
        public Object emptyChunk = mock(EmptyLevelChunk.class);
    }

    /** A container that offers the names but not the descriptors. */
    public static class WrongDescriptors {
        public Object emptyChunk = mock(EmptyLevelChunk.class);

        public static WrongDescriptors getContainer() {
            return null;
        }

        public boolean inBounds(long x, long z) {
            return false;
        }
    }

    /** A container whose known members are not public, so neither lookup can bind them. */
    public static class InaccessibleApi {
        public Object emptyChunk = mock(EmptyLevelChunk.class);

        static InaccessibleApi getContainer(Level level) {
            return null;
        }

        boolean inBounds(int x, int z) {
            return false;
        }
    }

    /** Referenced only from a signature, so a loader can hide it from the container's own class. */
    public static class Hidden {}

    /**
     * Mirrors Sable's three {@code getContainer} overloads, including their covariant returns: the
     * {@code (Level)} one returns the container type, the {@code (ServerLevel)} one a subtype. Binding must
     * pick the {@code (Level)} overload, because {@code MethodHandles} matches descriptors exactly.
     */
    public static class OverloadedApi {
        public Object emptyChunk = mock(EmptyLevelChunk.class);

        public static OverloadedApi getContainer(Level level) {
            return null;
        }

        public static ServerOverload getContainer(ServerLevel level) {
            return null;
        }

        public boolean inBounds(int x, int z) {
            return true;
        }
    }

    /** The covariant return of the {@code (ServerLevel)} overload, as {@code ServerSubLevelContainer} is. */
    public static class ServerOverload extends OverloadedApi {}

    /**
     * A container that declares the known API and, in a signature the adapter never asks for, a type that the
     * dist cleaner refuses in production. Enumerating this class's members throws, exactly like the crash.
     */
    public static class GappedApi {
        public Object emptyChunk = mock(EmptyLevelChunk.class);

        public static GappedApi getContainer(Level level) {
            return null;
        }

        public boolean inBounds(int x, int z) {
            return true;
        }

        public Hidden widget(Hidden hidden) {
            return hidden;
        }
    }

    /** Defines a copy of {@link GappedApi} whose {@link Hidden} cannot be resolved. */
    private static final class HidingLoader extends ClassLoader {
        HidingLoader() {
            super(SableLoadedChunkLookupTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(Hidden.class.getName())) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }

        Class<?> define(byte[] bytes) {
            return defineClass(GappedApi.class.getName(), bytes, 0, bytes.length);
        }
    }

    private static Class<?> gappedApiWithoutHidden() throws Exception {
        final byte[] bytes;
        try (var in = SableLoadedChunkLookupTest.class.getClassLoader()
                .getResourceAsStream(GappedApi.class.getName().replace('.', '/') + ".class")) {
            bytes = in.readAllBytes();
        }
        return new HidingLoader().define(bytes);
    }

    private static Optional<?> bind(Class<?> container) throws Throwable {
        final Method bindContainer = SableLoadedChunkLookup.class.getDeclaredMethod("bindContainer", Class.class, Field.class);
        bindContainer.setAccessible(true);
        try {
            return (Optional<?>) bindContainer.invoke(null, container, container.getField("emptyChunk"));
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private LevelChunk resolve(Container provider, int x, int z) throws Throwable {
        Container.current = provider;
        final Object binding = bind(Container.class).orElseThrow();
        final Method resolve = SableLoadedChunkLookup.class.getDeclaredMethod("resolve", binding.getClass(),
                Object.class, ServerLevel.class, int.class, int.class);
        resolve.setAccessible(true);
        try {
            return (LevelChunk) resolve.invoke(null, binding, provider, mock(ServerLevel.class), x, z);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            Container.current = null;
        }
    }

    @Test
    void claimedGapUsesExactlyTheProvidersSentinel() throws Throwable {
        final var provider = new Container();
        assertSame(provider.emptyChunk, this.resolve(provider, 1280065, 1280960));
    }

    @Test
    void missingNormalWorldChunkNeverBecomesAir() throws Throwable {
        assertNull(this.resolve(new Container(), 1, 1));
        final var provider = new Container();
        provider.claimed = false;
        assertNull(this.resolve(provider, 1280065, 1280960));
    }

    @Test
    void absentContainerDoesNotSupplyAnEmptyChunk() throws Throwable {
        assertNull(this.resolve(null, 1280065, 1280960));
    }

    @Test
    void wrongSentinelTypeIsAnExplicitCompatibilityFailure() {
        final var provider = new Container();
        provider.emptyChunk = mock(LevelChunk.class);
        assertThrows(IllegalStateException.class, () -> this.resolve(provider, 1280065, 1280960));
    }

    @Test
    void providerFailureKeepsItsOriginalCause() {
        final var provider = new Container();
        final var failure = new IllegalStateException("plot lookup failed");
        provider.failure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> this.resolve(provider, 1280065, 1280960)));
    }

    @Test
    void aContainerWithoutTheKnownApiDisablesTheAdapter() throws Throwable {
        assertTrue(bind(UnrelatedApi.class).isEmpty());
    }

    @Test
    void aContainerWithOtherDescriptorsDisablesTheAdapter() throws Throwable {
        assertTrue(bind(WrongDescriptors.class).isEmpty());
    }

    @Test
    void anInaccessibleContainerApiDisablesTheAdapter() throws Throwable {
        assertTrue(bind(InaccessibleApi.class).isEmpty());
    }

    /**
     * The production failure: binding must not enumerate the container's members. Enumerating them resolves
     * every signature, and a signature the runtime dist cleaner refuses is not a compatibility answer Forgia
     * may die on. This reproduces the refusal with a hidden type instead of a client-only one.
     */
    @Test
    void aSignatureTheLoaderCannotResolveDisablesEnumerationButNotTheBinding() throws Throwable {
        final Class<?> gapped = gappedApiWithoutHidden();
        assertThrows(LinkageError.class, () -> gapped.getMethod("getContainer", Level.class));
        assertTrue(bind(gapped).isPresent());
    }

    /**
     * The .41 live failure: Sable's {@code getContainer(ServerLevel)} returns {@code ServerSubLevelContainer},
     * so a descriptor asking for {@code (ServerLevel)SubLevelContainer} matches nothing and the adapter went
     * silently off on the real server. Binding must use the {@code (Level)} overload.
     */
    @Test
    void theLevelOverloadBindsEvenWhenTheServerOverloadReturnsASubtype() throws Throwable {
        assertTrue(bind(OverloadedApi.class).isPresent());
    }
}
