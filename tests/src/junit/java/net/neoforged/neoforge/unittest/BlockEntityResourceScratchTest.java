/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Access;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Binding;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Claims;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Identity;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.SharedReads;
import org.junit.jupiter.api.Test;

/** Internal storage/lifetime checks reflect package-private gates, without exporting minting APIs. */
class BlockEntityResourceScratchTest {
    private final Object domain = new Object();
    private final Object home = new Object();
    private final Object item = new Object();
    private static final Class<?> SCRATCH = reflect(() -> Class.forName(BlockEntityResourceOwnership.class.getName() + "$Scratch"));

    private Claims local(BooleanSupplier current) {
        return Claims.builder(this.domain, this.home)
                .claim(new Binding(new Identity(this.domain, this.item), this.home, current, null), Access.WRITE).build();
    }

    private static Object scratch() {
        return reflect(() -> {
            final var constructor = SCRATCH.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        });
    }

    private static BlockEntityResourceOwnership.Plan plan(List<Claims> claims, Object scratch) {
        return reflect(() -> {
            final var method = BlockEntityResourceOwnership.class.getDeclaredMethod("plan", List.class, SCRATCH);
            method.setAccessible(true);
            return (BlockEntityResourceOwnership.Plan) method.invoke(null, claims, scratch);
        });
    }

    private static Claims fresh(Claims local, SharedReads reads) {
        return reflect(() -> {
            final var method = Claims.class.getDeclaredMethod("withFreshSharedReads", SharedReads.class);
            method.setAccessible(true);
            return (Claims) method.invoke(local, reads);
        });
    }

    private static BlockEntityResourceOwnership.Scope enter(BlockEntityResourceOwnership.Lease lease) {
        return reflect(() -> {
            final var method = BlockEntityResourceOwnership.Lease.class.getDeclaredMethod("enter");
            method.setAccessible(true);
            return (BlockEntityResourceOwnership.Scope) method.invoke(lease);
        });
    }

    @Test
    void planningAndReservationScratchNeverCarryOccupancyAcrossRefusalThrowOrLaterWaves() {
        final Object scratch = scratch();
        final var calls = new AtomicInteger();
        final var current = new AtomicBoolean(true);
        final Claims claims = this.local(() -> {
            calls.incrementAndGet();
            return current.get();
        });
        for (int wave = 0; wave < 5; ++wave) {
            assertEquals(List.of(List.of(0), List.of(1)), plan(List.of(claims, claims), scratch).dependencies().waves());
            try (final var owner = new Owner(scratch)) {
                assertNull(owner.tryAcquireAll(List.of(claims, claims)));
                current.set(false);
                assertNull(owner.tryAcquireAll(List.of(claims)));
                final var failure = new IllegalStateException("fresh validator failure");
                assertSame(failure, assertThrows(IllegalStateException.class, () -> owner.tryAcquireAll(List.of(this.local(() -> {
                    throw failure;
                })))));
                current.set(true);
                final var leases = owner.tryAcquireAll(List.of(claims));
                assertNotNull(leases);
                try (final var scope = enter(leases.getFirst())) {
                    BlockEntityResourceOwnership.Scope.require(this.domain, this.item, Access.WRITE);
                }
                assertThrows(IllegalStateException.class, () -> enter(leases.getFirst()));
            }
        }
        assertEquals(10, calls.get(), "every false and successful current proof runs again");
        assertFalse(BlockEntityResourceOwnership.Scope.active());
    }

    @Test
    void sharedDescriptionReuseKeepsEveryLocalValidatorAndUsesOnlyTheNewPublication() {
        final var localCalls = new AtomicInteger();
        final var oldCalls = new AtomicInteger();
        final var newCalls = new AtomicInteger();
        final Object oldRead = new Object();
        final Object newRead = new Object();
        final Binding binding = new Binding(new Identity(this.domain, this.item), this.home, () -> {
            localCalls.incrementAndGet();
            return true;
        }, null);
        final Claims original = Claims.builder(this.domain, this.home).claim(binding, Access.READ).claim(binding, Access.WRITE)
                .sharedReads(SharedReads.capture(this.domain, List.of(oldRead), () -> {
                    oldCalls.incrementAndGet();
                    return false;
                })).build();
        final Claims fresh = fresh(original, SharedReads.capture(this.domain, List.of(newRead), () -> {
            newCalls.incrementAndGet();
            return true;
        }));
        assertFalse(original.sameResources(fresh));
        try (final var owner = new Owner(scratch())) {
            final var leases = owner.tryAcquireAll(List.of(fresh));
            assertNotNull(leases);
            try (final var scope = enter(leases.getFirst())) {
                BlockEntityResourceOwnership.Scope.require(this.domain, this.item, Access.WRITE);
                BlockEntityResourceOwnership.Scope.require(this.domain, newRead, Access.READ);
                assertThrows(IllegalStateException.class, () -> BlockEntityResourceOwnership.Scope.require(this.domain, oldRead, Access.READ));
                assertThrows(IllegalStateException.class, () -> BlockEntityResourceOwnership.Scope.require(this.domain, newRead, Access.WRITE));
            }
        }
        assertEquals(2, localCalls.get());
        assertEquals(0, oldCalls.get());
        assertEquals(1, newCalls.get());
        final var collision = fresh(original, SharedReads.capture(this.domain, List.of(this.item), () -> true));
        assertFalse(collision.complete(), "new shared/write aliases cannot inherit the old proof");
        final var oversized = fresh(original, SharedReads.capture(this.domain,
                java.util.stream.IntStream.range(0, 512).mapToObj(i -> new Object()).toList(), () -> true));
        assertFalse(oversized.complete(), "descriptor reuse still charges all logical uses");
        assertTrue(original.complete());
    }

    @Test
    void scratchReleasesAllCapturedIdentitiesAfterPlanningAndThrowingReservation() throws Exception {
        final Object scratch = scratch();
        plan(List.of(this.local(() -> true)), scratch);
        assertCleared(scratch);
        try (final var owner = new Owner(scratch)) {
            assertThrows(IllegalArgumentException.class, () -> owner.tryAcquireAll(List.of(this.local(() -> {
                throw new IllegalArgumentException("callback failure");
            }))));
            assertCleared(scratch);
        }
    }

    private static void assertCleared(Object scratch) throws Exception {
        final var field = scratch.getClass().getDeclaredField("table");
        field.setAccessible(true);
        final var table = field.get(scratch);
        for (final String name : List.of("keys", "homes", "flags")) {
            final var member = table.getClass().getDeclaredField(name);
            member.setAccessible(true);
            final var values = member.get(table);
            if (values instanceof Object[] objects) {
                for (final Object value : objects) assertNull(value);
            } else {
                for (final byte value : (byte[]) values) assertEquals(0, value);
            }
        }
    }

    private final class Owner implements AutoCloseable {
        private final BlockEntityResourceOwnership delegate;

        Owner(Object scratch) {
            this.delegate = reflect(() -> {
                final var constructor = BlockEntityResourceOwnership.class.getDeclaredConstructor(Object.class, Map.class, BooleanSupplier.class, SCRATCH);
                constructor.setAccessible(true);
                return constructor.newInstance(domain, Map.of(home, (BooleanSupplier) () -> true), (BooleanSupplier) () -> true, scratch);
            });
        }

        List<BlockEntityResourceOwnership.Lease> tryAcquireAll(List<Claims> claims) {
            return reflect(() -> {
                final var method = BlockEntityResourceOwnership.class.getDeclaredMethod("tryAcquireAll", List.class);
                method.setAccessible(true);
                return (List<BlockEntityResourceOwnership.Lease>) method.invoke(this.delegate, claims);
            });
        }

        @Override
        public void close() {
            this.delegate.close();
        }
    }

    @FunctionalInterface
    private interface Reflection<T> {
        T run() throws ReflectiveOperationException;
    }

    private static <T> T reflect(Reflection<T> operation) {
        try {
            return operation.run();
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            if (exception.getCause() instanceof Error error) throw error;
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
