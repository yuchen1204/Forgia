/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Access;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Binding;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Claims;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Identity;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Scope;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.SharedReads;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Resource/lifecycle tests; two lookup-hook boundary tests use mocked worlds, never historical grants. */
@Timeout(20)
class BlockEntityResourceOwnershipTest {
    private final Object level = new Object();
    private final Object region = new Object();
    private final Object x = new Object();
    private final Object y = new Object();

    private Binding binding(Object instance) {
        return new Binding(new Identity(this.level, instance), this.region, () -> true, null);
    }

    private Claims claims(Object instance, Access access) {
        return Claims.builder(this.level, this.region).claim(this.binding(instance), access).build();
    }

    private Owner ownership() {
        return new Owner(this.level, Map.of(this.region, () -> true), () -> true);
    }

    @Test
    void identityUsesActualDomainAndObjectAndIgnoresUserEquals() {
        final Object equalA = new String("same");
        final Object equalB = new String("same");
        assertEquals(equalA, equalB);
        final var first = new Identity(this.level, equalA);
        assertEquals(first, new Identity(this.level, equalA));
        assertEquals(first.hashCode(), new Identity(this.level, equalA).hashCode());
        assertNotEquals(first, new Identity(this.level, equalB));
        assertNotEquals(first, new Identity(new Object(), equalA));
        assertNotEquals(new BlockEntityWorkPlanner.Resource(this.level, equalA), new BlockEntityWorkPlanner.Resource(this.level, BlockEntityWorkPlanner.Kind.BLOCK_ENTITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new BlockEntityWorkPlanner.Resource(this.level, BlockEntityWorkPlanner.Kind.INSTANCE, 0));
    }

    @Test
    void readsShareAndEveryWriteConflictPreservesDirectedOrder() {
        assertEquals(List.of(List.of(0, 1)), BlockEntityResourceOwnership.plan(List.of(this.claims(this.x, Access.READ), this.claims(this.x, Access.READ))).dependencies().waves());
        for (final Access first : Access.values()) {
            for (final Access second : Access.values()) {
                if (first == Access.READ && second == Access.READ) continue;
                assertEquals(List.of(List.of(0), List.of(1)), BlockEntityResourceOwnership.plan(List.of(this.claims(this.x, first), this.claims(this.x, second))).dependencies().waves());
            }
        }
        final var middle = Claims.builder(this.level, this.region).claim(this.binding(this.x), Access.WRITE).claim(this.binding(this.y), Access.WRITE).build();
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), BlockEntityResourceOwnership.plan(List.of(this.claims(this.x, Access.WRITE), middle, this.claims(this.y, Access.WRITE))).dependencies().waves());
    }

    @Test
    void duplicateResourcesUpgradeToWriteButKeepEveryAttachmentValidator() {
        final var calls = new AtomicInteger();
        final var claims = Claims.builder(this.level, this.region)
                .claim(new Binding(new Identity(this.level, this.x), this.region, () -> {
                    calls.incrementAndGet();
                    return true;
                }, null), Access.READ)
                .claim(new Binding(new Identity(this.level, this.x), this.region, () -> {
                    calls.incrementAndGet();
                    return false;
                }, null), Access.WRITE).build();
        assertTrue(claims.complete());
        assertEquals(Set.of(new BlockEntityWorkPlanner.Resource(this.level, this.x)), claims.unit().writes());
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(claims)));
            assertEquals(2, calls.get());
            assertNotNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))), "failed reservation must not leave partial occupancy");
        }
    }

    @Test
    void conflictsAreRefusedAtomicallyBeforeAnyLeaseEscapes() {
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ), this.claims(this.x, Access.WRITE))));
            final var grants = owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ), this.claims(this.x, Access.READ)));
            assertNotNull(grants);
            assertEquals(2, grants.size());
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))), "no expanding a published wave");
        }
    }

    @Test
    void foreignDomainsRegionsAndMissingOuterExclusionNeverGrant() {
        final var foreignLevel = Claims.builder(new Object(), this.region).claim(this.binding(this.x), Access.WRITE).build();
        final var foreignRegion = Claims.builder(this.level, new Object()).claim(this.binding(this.x), Access.WRITE).build();
        assertFalse(foreignLevel.complete());
        assertFalse(foreignRegion.complete());
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(foreignLevel)));
            assertNull(owner.tryAcquireAll(List.of(foreignRegion)));
        }
        try (final var owner = new Owner(this.level, Map.of(), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ))));
        }
        try (final var owner = new Owner(this.level, Map.of(this.region, () -> false), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ))));
        }
    }

    @Test
    void globalReadsCannotBeConvertedToRegionWritesThroughAnAlias() {
        final var shared = new Binding(new Identity(this.level, this.x), null, () -> true, null);
        final var read = Claims.builder(this.level, this.region).claim(shared, Access.READ).build();
        assertTrue(read.complete());
        assertFalse(Claims.builder(this.level, this.region).claim(shared, Access.WRITE).build().complete());
        assertFalse(Claims.builder(this.level, this.region).claim(shared, Access.READ).claim(this.binding(this.x), Access.WRITE).build().complete());
        final var plan = BlockEntityResourceOwnership.plan(List.of(read, this.claims(this.x, Access.WRITE)));
        assertTrue(plan.claims().stream().noneMatch(Claims::complete));
        assertEquals(List.of(List.of(0), List.of(1)), plan.dependencies().waves());
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(read, this.claims(this.x, Access.WRITE))));
        }
    }

    @Test
    void inconsistentRegionHomesRefuseEvenReadAliasesAndDoNotWaitForLocks() {
        final Object otherRegion = new Object();
        final var first = this.claims(this.x, Access.READ);
        final var second = Claims.builder(this.level, otherRegion)
                .claim(new Binding(new Identity(this.level, this.x), otherRegion, () -> true, null), Access.READ).build();
        assertTrue(BlockEntityResourceOwnership.plan(List.of(first, second)).claims().stream().noneMatch(Claims::complete));
        try (final var owner = new Owner(this.level, Map.of(this.region, () -> true, otherRegion, () -> true), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(first, second)));
        }
    }

    @Test
    void unknownEmptyAndOverflowedProofsAreCompleteSerialFences() {
        final var builder = Claims.builder(this.level, this.region);
        for (int index = 0; index <= BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT; ++index) builder.claim(this.binding(new Object()), Access.READ);
        final var overflow = builder.build();
        assertFalse(overflow.complete());
        assertFalse(Claims.builder(this.level, this.region).build().complete());
        final var plan = BlockEntityResourceOwnership.plan(List.of(this.claims(this.x, Access.WRITE), overflow, this.claims(this.y, Access.READ)));
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), plan.dependencies().waves());
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(overflow)));
        }
    }

    @Test
    void sharedReadPublicationCannotBeUsedAsAWorldChunkPermission() {
        final var world = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        final var chunk = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunk.class);
        org.mockito.Mockito.doReturn(world).when(chunk).getLevel();
        final var shared = Claims.builder(world, this.region)
                .claim(new Binding(new Identity(world, chunk), null, () -> true, null), Access.READ).build();
        try (final var owner = new Owner(world, Map.of(this.region, () -> true), () -> true)) {
            final var leases = owner.tryAcquireAll(List.of(shared));
            assertNotNull(leases);
            try (final var scope = leases.getFirst().enter()) {
                assertThrows(IllegalStateException.class, () -> Scope.checkChunk(chunk, false));
            }
        }
        final var local = Claims.builder(world, this.region)
                .claim(new Binding(new Identity(world, chunk), this.region, () -> true, null), Access.READ).build();
        try (final var owner = new Owner(world, Map.of(this.region, () -> true), () -> true)) {
            final var leases = owner.tryAcquireAll(List.of(local));
            assertNotNull(leases);
            try (final var scope = leases.getFirst().enter()) {
                assertTrue(Scope.checkChunk(chunk, false));
                assertThrows(IllegalStateException.class, () -> Scope.checkChunk(chunk, true));
            }
        }
    }

    @Test
    void liveBlockEntityLookupCannotUpgradeAReadClaimToMutableAccess() {
        final var world = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        final var position = new net.minecraft.core.BlockPos(17, 64, 17);
        for (final Access mode : Access.values()) {
            final var claims = Claims.builder(world, this.region)
                    .claim(new Binding(new Identity(world, this.x), this.region, () -> true, position), mode).build();
            try (final var owner = new Owner(world, Map.of(this.region, () -> true), () -> true)) {
                final var leases = owner.tryAcquireAll(List.of(claims));
                assertNotNull(leases);
                try (final var scope = leases.getFirst().enter()) {
                    if (mode == Access.READ) assertThrows(IllegalStateException.class, () -> Scope.checkReference(world, position));
                    else Scope.checkReference(world, position);
                    assertThrows(IllegalStateException.class, () -> Scope.checkReference(world, position.east()));
                }
            }
        }
    }

    @Test
    void aggregateProofBudgetCannotPublishAPartiallyVerifiedPhase() {
        final var builder = Claims.builder(this.level, this.region);
        for (int index = 0; index < BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT; ++index) builder.claim(this.binding(new Object()), Access.READ);
        final var claims = builder.build();
        assertTrue(claims.complete());
        final var inputs = java.util.Collections.nCopies(BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE / BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT + 1, claims);
        assertThrows(IllegalArgumentException.class, () -> BlockEntityResourceOwnership.plan(inputs));
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(inputs));
            assertNotNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))));
        }
    }

    @Test
    void scopesAreReadWriteCheckedThreadBoundAndOneShotEvenAfterBodyFailure() {
        final var calls = new AtomicInteger();
        final var owner = this.ownership();
        final var grants = owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ)));
        assertNotNull(grants);
        final var lease = grants.getFirst();
        assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.x, Access.READ));
        assertThrows(UnsupportedOperationException.class, () -> {
            try (final var scope = lease.enter()) {
                Scope.require(this.level, this.x, Access.READ);
                assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.x, Access.WRITE));
                assertThrows(IllegalStateException.class, () -> Scope.require(new Object(), this.x, Access.READ));
                assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.y, Access.READ));
                assertThrows(NullPointerException.class, () -> Scope.require(this.level, this.x, null));
                assertThrows(IllegalStateException.class, lease::enter);
                calls.incrementAndGet();
                throw new UnsupportedOperationException("after effect");
            }
        });
        assertFalse(Scope.active());
        assertEquals(1, calls.get());
        assertThrows(IllegalStateException.class, lease::enter);
        owner.close();
        assertThrows(IllegalStateException.class, lease::enter);
    }

    @Test
    void readReadScopesOverlapAndCoordinatorCannotReleaseRunningResources() throws Exception {
        final var owner = this.ownership();
        final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ), this.claims(this.x, Access.READ)));
        assertNotNull(leases);
        final var entered = new CyclicBarrier(3);
        final var release = new CountDownLatch(1);
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (final var lease : leases) futures.add(executor.submit(() -> {
                try (final var scope = lease.enter()) {
                    Scope.require(this.level, this.x, Access.READ);
                    entered.await(5, TimeUnit.SECONDS);
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("reader not released");
                    Scope.require(this.level, this.x, Access.READ);
                }
                return null;
            }));
            try {
                entered.await(5, TimeUnit.SECONDS);
                assertThrows(IllegalStateException.class, owner::close);
            } finally {
                release.countDown();
            }
            for (final var future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            owner.close();
        }
    }

    @Test
    void oneLeaseCannotExecuteOnTwoThreadsOrBeReplayedAfterJoin() throws Exception {
        final var effects = new AtomicInteger();
        final var start = new CyclicBarrier(2);
        try (final var owner = this.ownership(); final var executor = Executors.newFixedThreadPool(2)) {
            final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE)));
            assertNotNull(leases);
            final var lease = leases.getFirst();
            final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 2; ++index) futures.add(executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try (final var scope = lease.enter()) {
                    Scope.require(this.level, this.x, Access.WRITE);
                    effects.incrementAndGet();
                } catch (IllegalStateException refused) {
                    // The other runner owns or consumed this exact invocation.
                }
                return null;
            }));
            for (final var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(1, effects.get());
            assertThrows(IllegalStateException.class, lease::enter);
        }
    }

    @Test
    void staleBoundaryAndLifecycleCannotResurrectOldLeases() {
        final var loaded = new AtomicBoolean(true);
        final var live = new AtomicBoolean(false);
        try (final var owner = new Owner(this.level, Map.of(this.region, loaded::get), live::get)) {
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE))));
            live.set(true);
            final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE)));
            assertNotNull(leases);
            loaded.set(false);
            assertThrows(IllegalStateException.class, leases.getFirst()::enter);
            loaded.set(true);
            assertThrows(IllegalStateException.class, leases.getFirst()::enter);
        }
    }

    @Test
    void shutdownRequestAfterPublicationDoesNotRevokeAnAcceptedBatch() {
        final var live = new AtomicBoolean(true);
        try (final var owner = new Owner(this.level, Map.of(this.region, () -> true), live::get)) {
            final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE)));
            assertNotNull(leases);
            live.set(false); // Existing outer batch publication is still held until join/commit.
            try (final var scope = leases.getFirst().enter()) {
                Scope.require(this.level, this.x, Access.WRITE);
            }
        }
    }

    @Test
    void reservationAndReleaseCannotBeInvokedFromAnotherThread() throws Exception {
        try (final var owner = this.ownership(); final var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertThrows(IllegalStateException.class, () -> owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ))));
                assertThrows(IllegalStateException.class, owner::close);
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void scopeCannotBeInheritedOrClosedByAnUnrelatedThread() throws Exception {
        try (final var owner = this.ownership(); final var executor = Executors.newSingleThreadExecutor()) {
            final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE)));
            assertNotNull(leases);
            final var scope = leases.getFirst().enter();
            try {
                executor.submit(() -> {
                    assertFalse(Scope.active());
                    assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.x, Access.WRITE));
                    assertThrows(IllegalStateException.class, scope::close);
                }).get(5, TimeUnit.SECONDS);
                Scope.require(this.level, this.x, Access.WRITE);
            } finally {
                scope.close();
            }
            assertThrows(IllegalStateException.class, scope::close);
            assertFalse(Scope.active());
        }
    }

    @Test
    void aThrowingAttachmentProofPublishesNoLeaseAndRetainsTheOriginalFailure() {
        final var failure = new IllegalStateException("unverifiable attachment");
        final var proof = Claims.builder(this.level, this.region).claim(new Binding(new Identity(this.level, this.x), this.region, () -> {
            throw failure;
        }, null), Access.WRITE).build();
        try (final var owner = this.ownership()) {
            assertEquals(failure, assertThrows(IllegalStateException.class, () -> owner.tryAcquireAll(List.of(proof))));
            assertNotNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))));
            assertFalse(Scope.active());
        }
    }

    @Test
    void randomizedDirectedWavesMatchAnIndependentPairwiseOrderOracle() {
        final var random = new Random(20261113L);
        final Object[] resources = new Object[8];
        for (int index = 0; index < resources.length; ++index) resources[index] = new Object();
        for (int iteration = 0; iteration < 1000; ++iteration) {
            final List<Claims> inputs = new ArrayList<>();
            final List<BlockEntityWorkPlanner.Unit> units = new ArrayList<>();
            final int size = 1 + random.nextInt(30);
            for (int index = 0; index < size; ++index) {
                final var builder = Claims.builder(this.level, this.region);
                for (int count = 1 + random.nextInt(4); count > 0; --count) builder.claim(this.binding(resources[random.nextInt(resources.length)]), random.nextBoolean() ? Access.READ : Access.WRITE);
                final var claims = random.nextInt(8) == 0 ? Claims.fence(this.level, this.region) : builder.build();
                inputs.add(claims);
                units.add(claims.unit());
            }
            final int[] depth = new int[size];
            final List<List<Integer>> expected = new ArrayList<>();
            for (int after = 0; after < size; ++after) {
                final var b = units.get(after);
                for (int before = 0; before < after; ++before) {
                    final var a = units.get(before);
                    if (a.exclusive() || b.exclusive() || intersects(a.writes(), b.reads()) || intersects(a.writes(), b.writes()) || intersects(a.reads(), b.writes())) depth[after] = Math.max(depth[after], depth[before] + 1);
                }
                while (expected.size() <= depth[after]) expected.add(new ArrayList<>());
                expected.get(depth[after]).add(after);
            }
            assertEquals(expected, BlockEntityResourceOwnership.plan(inputs).dependencies().waves(), "seeded case " + iteration);
        }
    }

    @Test
    void sharedReadPublicationIsValidatedOncePerReservationNotCachedAcrossWaves() {
        final var calls = new AtomicInteger();
        final var valid = new AtomicBoolean(true);
        final var publication = SharedReads.capture(this.level, List.of(this.x, this.y), () -> {
            calls.incrementAndGet();
            return valid.get();
        });
        final var read = Claims.builder(this.level, this.region).sharedReads(publication).build();
        for (int wave = 0; wave < 2; ++wave) {
            try (final var owner = this.ownership()) {
                final var leases = owner.tryAcquireAll(List.of(read, read));
                assertNotNull(leases);
                for (final var lease : leases) {
                    try (final var scope = lease.enter()) {
                        Scope.require(this.level, this.x, Access.READ);
                        Scope.require(this.level, this.y, Access.READ);
                        assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.x, Access.WRITE));
                    }
                }
            }
            assertEquals(wave + 1, calls.get());
        }
        valid.set(false);
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(read, read)));
        }
        assertEquals(3, calls.get(), "each new reservation must validate even an identical immutable footprint");
    }

    @Test
    void distinctSharedProofsAndEveryLegacyAttachmentValidatorAreRetained() {
        final var calls = new AtomicInteger();
        final var first = SharedReads.capture(this.level, List.of(this.x), () -> {
            calls.incrementAndGet();
            return true;
        });
        final var second = SharedReads.capture(this.level, List.of(this.x), () -> {
            calls.incrementAndGet();
            return false;
        });
        final var a = Claims.builder(this.level, this.region).sharedReads(first).build();
        final var b = Claims.builder(this.level, this.region).sharedReads(second).build();
        assertTrue(a.sameResources(b));
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(a, a, b)));
            assertEquals(2, calls.get());
        }
        final var duplicateCalls = new AtomicInteger();
        final var binding = new Binding(new Identity(this.level, this.x), null, () -> {
            duplicateCalls.incrementAndGet();
            return true;
        }, null);
        final var expanded = Claims.builder(this.level, this.region).claim(binding, Access.READ).claim(binding, Access.READ).build();
        try (final var owner = this.ownership()) {
            assertNotNull(owner.tryAcquireAll(List.of(expanded, expanded)));
        }
        assertEquals(4, duplicateCalls.get(), "ordinary bindings are never silently converted into a group proof");
    }

    @Test
    void groupedReadsPreserveGlobalWriteAliasRefusalIncludingOtherHomesAndExpandedClaims() {
        final var shared = SharedReads.capture(this.level, List.of(this.x), () -> true);
        final var read = Claims.builder(this.level, this.region).sharedReads(shared).build();
        assertFalse(Claims.builder(this.level, this.region).sharedReads(shared).claim(this.binding(this.x), Access.WRITE).build().complete());
        assertFalse(Claims.builder(this.level, this.region).claim(this.binding(this.x), Access.WRITE).sharedReads(shared).build().complete());
        final Object otherRegion = new Object();
        final var write = Claims.builder(this.level, otherRegion)
                .claim(new Binding(new Identity(this.level, this.x), otherRegion, () -> true, null), Access.WRITE).build();
        final var independent = this.claims(this.y, Access.WRITE);
        final var plan = BlockEntityResourceOwnership.plan(List.of(read, independent, write, read));
        assertFalse(plan.claims().get(0).complete());
        assertTrue(plan.claims().get(1).complete());
        assertFalse(plan.claims().get(2).complete());
        assertFalse(plan.claims().get(3).complete());
        assertEquals(BlockEntityWorkPlanner.plan(plan.claims().stream().map(Claims::unit).toList()).waves(), plan.dependencies().waves());
        try (final var owner = new Owner(this.level, Map.of(this.region, () -> true, otherRegion, () -> true), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(read, write)));
            assertNull(owner.tryAcquireAll(List.of(write, read)), "conflict detection cannot depend on group visitation order");
            assertNotNull(owner.tryAcquireAll(List.of(read, read)));
        }
    }

    @Test
    void immutableGroupedAndExpandedSnapshotsHaveTheSamePermissions() {
        final List<Object> mutable = new ArrayList<>(List.of(this.x, this.y));
        final var shared = SharedReads.capture(this.level, mutable, () -> true);
        final var grouped = Claims.builder(this.level, this.region).sharedReads(shared).build();
        mutable.clear();
        mutable.add(new Object());
        final var expanded = Claims.builder(this.level, this.region)
                .claim(new Binding(new Identity(this.level, this.x), null, () -> true, null), Access.READ)
                .claim(new Binding(new Identity(this.level, this.y), null, () -> true, null), Access.READ).build();
        assertTrue(grouped.sameResources(expanded));
        assertTrue(expanded.sameResources(grouped));
        assertEquals(expanded.unit(), grouped.unit());
        final var changed = Claims.builder(this.level, this.region).sharedReads(SharedReads.capture(this.level, List.of(this.x), () -> true)).build();
        assertFalse(grouped.sameResources(changed));
        assertFalse(Claims.builder(new Object(), this.region).sharedReads(shared).build().complete());
        assertFalse(Claims.builder(this.level, this.region).sharedReads(shared).sharedReads(shared).build().complete());
    }

    @Test
    void sharedGroupsStillChargeLogicalDuplicateUnitAndPhaseBudgets() {
        final var shared = SharedReads.capture(this.level, java.util.Collections.nCopies(BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT, this.x), () -> true);
        final var read = Claims.builder(this.level, this.region).sharedReads(shared).build();
        assertTrue(read.complete());
        assertFalse(Claims.builder(this.level, this.region).sharedReads(shared).claim(this.binding(this.y), Access.READ).build().complete());
        assertFalse(Claims.builder(this.level, this.region).claim(this.binding(this.y), Access.READ).sharedReads(shared).build().complete());
        final var inputs = java.util.Collections.nCopies(BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE / BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT + 1, read);
        assertThrows(IllegalArgumentException.class, () -> BlockEntityResourceOwnership.plan(inputs));
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(inputs));
            assertNotNull(owner.tryAcquireAll(List.of(read)));
        }
        final var oversized = SharedReads.capture(this.level, java.util.Collections.nCopies(Integer.MAX_VALUE, this.x), () -> true);
        assertFalse(Claims.builder(this.level, this.region).sharedReads(oversized).build().complete());
    }

    @Test
    void groupedReadsCannotGrantWorldChunkAccessAndStillCheckPostValidationBoundaries() {
        final var chunk = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunk.class);
        final var held = new AtomicBoolean(true);
        final var shared = SharedReads.capture(this.level, List.of(chunk), () -> {
            held.set(false);
            return true;
        });
        final var claims = Claims.builder(this.level, this.region).sharedReads(shared).build();
        try (final var owner = new Owner(this.level, Map.of(this.region, held::get), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(claims)), "all homes must still pass after all validators");
        }
        final var valid = Claims.builder(this.level, this.region).sharedReads(SharedReads.capture(this.level, List.of(chunk), () -> true)).build();
        try (final var owner = this.ownership()) {
            final var leases = owner.tryAcquireAll(List.of(valid));
            assertNotNull(leases);
            try (final var scope = leases.getFirst().enter()) {
                Scope.require(this.level, chunk, Access.READ);
                assertThrows(IllegalStateException.class, () -> Scope.checkChunk(chunk, false));
            }
        }
    }

    @Test
    void aThrowingSharedPublicationProofNeverLeavesPartialAuthority() {
        final var failure = new IllegalArgumentException("publication changed during proof");
        final var shared = SharedReads.capture(this.level, List.of(this.x), () -> {
            throw failure;
        });
        final var read = Claims.builder(this.level, this.region).sharedReads(shared).build();
        try (final var owner = this.ownership()) {
            assertEquals(failure, assertThrows(IllegalArgumentException.class, () -> owner.tryAcquireAll(List.of(read, read))));
            assertNotNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))));
            assertFalse(Scope.active());
        }
    }

    @Test
    void groupedAndExpandedPlansMatchForAThousandSharedReadWriteAliasCases() {
        final var random = new Random(981371L);
        final List<Object> resources = List.of(new Object(), new Object(), new Object(), new Object());
        for (int iteration = 0; iteration < 1000; ++iteration) {
            final List<Object> reads = resources.subList(0, 1 + random.nextInt(resources.size()));
            final var shared = SharedReads.capture(this.level, reads, () -> true);
            final List<Claims> grouped = new ArrayList<>();
            final List<Claims> expanded = new ArrayList<>();
            for (int index = 0; index < 20; ++index) {
                final var a = Claims.builder(this.level, this.region);
                final var b = Claims.builder(this.level, this.region);
                if (random.nextBoolean()) {
                    a.sharedReads(shared);
                    for (final Object instance : reads) b.claim(new Binding(new Identity(this.level, instance), null, () -> true, null), Access.READ);
                }
                final Object resource = resources.get(random.nextInt(resources.size()));
                final Access mode = random.nextBoolean() ? Access.READ : Access.WRITE;
                a.claim(this.binding(resource), mode);
                b.claim(this.binding(resource), mode);
                if (random.nextInt(6) == 0) {
                    grouped.add(Claims.fence(this.level, this.region));
                    expanded.add(Claims.fence(this.level, this.region));
                } else {
                    grouped.add(a.build());
                    expanded.add(b.build());
                }
            }
            final var groupedPlan = BlockEntityResourceOwnership.plan(grouped);
            final var expandedPlan = BlockEntityResourceOwnership.plan(expanded);
            assertEquals(expandedPlan.claims().stream().map(Claims::complete).toList(), groupedPlan.claims().stream().map(Claims::complete).toList());
            assertEquals(expandedPlan.dependencies().waves(), groupedPlan.dependencies().waves(), "seeded shared case " + iteration);
            assertEquals(BlockEntityWorkPlanner.plan(groupedPlan.claims().stream().map(Claims::unit).toList()).waves(), groupedPlan.dependencies().waves());
        }
    }

    // Tests reflect the internal grant/entry gates instead of exporting a permission-minting test API.
    private static final class Owner implements AutoCloseable {
        private final BlockEntityResourceOwnership delegate;

        Owner(Object domain, Map<Object, java.util.function.BooleanSupplier> regions, java.util.function.BooleanSupplier live) {
            this.delegate = reflect(() -> {
                final var constructor = BlockEntityResourceOwnership.class.getDeclaredConstructor(Object.class, Map.class, java.util.function.BooleanSupplier.class);
                constructor.setAccessible(true);
                return constructor.newInstance(domain, regions, live);
            });
        }

        List<Lease> tryAcquireAll(List<Claims> claims) {
            final List<?> granted = reflect(() -> {
                final var method = BlockEntityResourceOwnership.class.getDeclaredMethod("tryAcquireAll", List.class);
                method.setAccessible(true);
                return (List<?>) method.invoke(this.delegate, claims);
            });
            return granted == null ? null : granted.stream().map(value -> new Lease((BlockEntityResourceOwnership.Lease) value)).toList();
        }

        @Override
        public void close() {
            this.delegate.close();
        }
    }

    private record Lease(BlockEntityResourceOwnership.Lease delegate) {
        Scope enter() {
            return reflect(() -> {
                final var method = BlockEntityResourceOwnership.Lease.class.getDeclaredMethod("enter");
                method.setAccessible(true);
                return (Scope) method.invoke(this.delegate);
            });
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

    private static boolean intersects(Set<?> first, Set<?> second) {
        return first.stream().anyMatch(second::contains);
    }
}
