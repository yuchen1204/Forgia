/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.threading.NearbySpawnReservations;
import net.neoforged.neoforge.server.threading.NearbySpawnReservations.Limit;
import net.neoforged.neoforge.server.threading.NearbySpawnReservations.Sample;
import net.neoforged.neoforge.server.threading.region.DeferredEntityAdmissionScope;
import org.junit.jupiter.api.Test;

/** Detached geometry tests: no region query can accidentally make an incomplete view look complete. */
class NearbySpawnReservationsTest {
    private static final ResourceLocation ZOMBIE = ResourceLocation.parse("minecraft:zombie");
    private static final ResourceLocation SKELETON = ResourceLocation.parse("minecraft:skeleton");

    private static Sample sample(int id, double x, double y, double z) {
        return new Sample(new UUID(0, id), ZOMBIE, new AABB(x, y, z, x + 0.6, y + 1.8, z + 0.6));
    }

    @Test
    void twoWorkersCannotReserveTheLastSlotAcrossARegionBorder() throws Exception {
        try (var ledger = new NearbySpawnReservations(List.of(sample(1, 47, 64, 0)));
                var workers = Executors.newFixedThreadPool(2)) {
            final var start = new CyclicBarrier(2);
            final var first = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return ledger.reserve(sample(2, 47, 64, 0), new Limit(new BlockPos(47, 64, 0), 2, 64));
            });
            final var second = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return ledger.reserve(sample(3, 48, 64, 0), new Limit(new BlockPos(48, 64, 0), 2, 64));
            });
            final var a = first.get(5, TimeUnit.SECONDS);
            final var b = second.get(5, TimeUnit.SECONDS);
            assertNotEquals(a.isPresent(), b.isPresent(), "exactly one candidate owns the last slot");
            ledger.commit(a.orElseGet(b::orElseThrow));
            assertEquals(2, ledger.count(ZOMBIE, new Limit(BlockPos.ZERO, 2, 64).area()));
        }
    }

    @Test
    void cancellationReturnsTheSlotAndCommitNeverCountsTwice() {
        try (var ledger = new NearbySpawnReservations(List.of())) {
            final var limit = new Limit(BlockPos.ZERO, 1, 16);
            final var first = ledger.reserve(sample(1, 0, 0, 0), limit).orElseThrow();
            assertTrue(ledger.reserve(sample(2, 0, 0, 0), limit).isEmpty());
            ledger.cancel(first); // real join cancellation, not success of deferred enqueue
            final var next = ledger.reserve(sample(2, 0, 0, 0), limit).orElseThrow();
            assertEquals(1, ledger.count(ZOMBIE, limit.area()));
            ledger.commit(next);
            assertEquals(1, ledger.count(ZOMBIE, limit.area()));
            assertTrue(ledger.reserve(sample(3, 0, 0, 0), limit).isEmpty());
        }
    }

    @Test
    void boundingBoxIntersectionMatchesMinecraftIncludingStrictEdgesAndVerticalExtent() {
        // maxX == area's minX touches but does not intersect. A large box intersects even with its origin outside.
        final var leftEdge = new Sample(new UUID(0, 1), ZOMBIE, new AABB(-2, 0, 0, -1, 1, 1));
        final var large = new Sample(new UUID(0, 2), ZOMBIE, new AABB(-10, 0, 0, 0, 1, 1));
        final var upperEdge = new Sample(new UUID(0, 3), ZOMBIE, new AABB(0, 2, 0, 1, 3, 1));
        try (var ledger = new NearbySpawnReservations(List.of(leftEdge, large, upperEdge, sample(4, 1.5, 1.5, 1.5)))) {
            final var area = new Limit(BlockPos.ZERO, 5, 1).area();
            assertEquals(new AABB(-1, -1, -1, 2, 2, 2), area);
            assertEquals(2, ledger.count(ZOMBIE, area), "cube corners count; distance/sphere tests are incorrect");
        }
    }

    @Test
    void sameTypeCountsAcrossAllRegionsAndUnrestrictedSecondaryAdmissionsAlsoCount() {
        final var otherType = new Sample(new UUID(0, 2), SKELETON, sample(2, 0, 0, 0).bounds());
        try (var ledger = new NearbySpawnReservations(List.of(sample(1, 100, 0, 0), otherType))) {
            final var limit = new Limit(BlockPos.ZERO, 2, 128);
            assertEquals(1, ledger.count(ZOMBIE, limit.area()));
            ledger.admitUnrestricted(sample(3, -100, 0, 0));
            assertTrue(ledger.reserve(sample(4, 0, 0, 0), limit).isEmpty());
            ledger.remove(new UUID(0, 3));
            assertTrue(ledger.reserve(sample(4, 0, 0, 0), limit).isPresent());
        }
    }

    @Test
    void differentRadiiDoNotTurnOverlappingWindowsIntoOneQuota() {
        try (var ledger = new NearbySpawnReservations(List.of())) {
            final var first = ledger.reserve(sample(1, 0, 0, 0), new Limit(BlockPos.ZERO, 1, 64)).orElseThrow();
            // Just as serial upstream queries, the second candidate checks its own smaller window.
            final var second = ledger.reserve(sample(2, 32, 0, 0), new Limit(new BlockPos(32, 0, 0), 1, 1)).orElseThrow();
            assertTrue(ledger.reserve(sample(3, 0, 0, 0), new Limit(BlockPos.ZERO, 1, 64)).isEmpty());
            ledger.commit(first);
            ledger.commit(second);
        }
    }

    @Test
    void zeroLimitAlwaysRefusesAndDistantWindowsProgressIndependently() {
        try (var ledger = new NearbySpawnReservations(List.of())) {
            assertTrue(ledger.reserve(sample(1, 0, 0, 0), new Limit(BlockPos.ZERO, 0, 16)).isEmpty());
            assertTrue(ledger.reserve(sample(1, 0, 0, 0), new Limit(BlockPos.ZERO, 1, 16)).isPresent());
            assertTrue(ledger.reserve(sample(2, 256, 0, 0), new Limit(new BlockPos(256, 0, 0), 1, 16)).isPresent());
        }
    }

    @Test
    void finalizerMovementOrSizeChangeRequiresRevalidationBeforeAdmission() {
        try (var ledger = new NearbySpawnReservations(List.of())) {
            final var candidate = sample(1, 0, 0, 0);
            final var limit = new Limit(BlockPos.ZERO, 1, 16);
            final var ticket = ledger.reserve(candidate, limit).orElseThrow();
            assertTrue(ledger.matches(ticket, candidate, limit));
            assertFalse(ledger.matches(ticket, sample(1, 1, 0, 0), limit));
            assertFalse(ledger.matches(ticket, candidate, new Limit(BlockPos.ZERO, 2, 16)));
            ledger.cancel(ticket);
            assertTrue(ledger.reserve(sample(2, 0, 0, 0), limit).isPresent());
        }
    }

    @Test
    void liveRemovalAndBoxPublicationChangeFollowingQueries() {
        final var original = sample(1, 0, 0, 0);
        try (var ledger = new NearbySpawnReservations(List.of(original))) {
            final var limit = new Limit(BlockPos.ZERO, 1, 16);
            assertTrue(ledger.reserve(sample(2, 0, 0, 0), limit).isEmpty());
            ledger.update(sample(1, 100, 0, 0));
            final var ticket = ledger.reserve(sample(2, 0, 0, 0), limit).orElseThrow();
            assertThrows(IllegalStateException.class, () -> ledger.remove(new UUID(0, 2)));
            ledger.cancel(ticket);
            ledger.remove(original.id());
            assertEquals(0, ledger.count(ZOMBIE, new Limit(BlockPos.ZERO, 5, 256).area()));
        }
    }

    @Test
    void ticketsCannotBeReusedOrResolvedAgainstAnotherPlan() {
        final var candidate = sample(1, 0, 0, 0);
        try (var a = new NearbySpawnReservations(List.of()); var b = new NearbySpawnReservations(List.of())) {
            final var ticket = a.reserve(candidate, new Limit(BlockPos.ZERO, 1, 16)).orElseThrow();
            assertThrows(IllegalStateException.class, () -> b.commit(ticket));
            assertThrows(IllegalArgumentException.class, () -> a.admitUnrestricted(candidate));
            assertThrows(IllegalArgumentException.class, () -> a.reserve(candidate, new Limit(BlockPos.ZERO, 1, 16)));
            a.commit(ticket);
            assertThrows(IllegalStateException.class, () -> a.cancel(ticket));
            assertThrows(IllegalStateException.class, () -> a.commit(ticket));
        }
    }

    @Test
    void shutdownRejectsLateResolutionAndReleasesAllPendingSamples() {
        final var ledger = new NearbySpawnReservations(List.of(sample(1, 0, 0, 0)));
        final var ticket = ledger.reserve(sample(2, 0, 0, 0), new Limit(BlockPos.ZERO, 3, 16)).orElseThrow();
        ledger.close();
        ledger.close();
        assertThrows(IllegalStateException.class, () -> ledger.commit(ticket));
        assertThrows(IllegalStateException.class, () -> ledger.reserve(sample(3, 0, 0, 0), new Limit(BlockPos.ZERO, 3, 16)));
        assertThrows(IllegalStateException.class, () -> ledger.count(ZOMBIE, new AABB(BlockPos.ZERO)));
    }

    @Test
    void actualAdmissionResultsCommitOrReleaseAndFailureInvalidatesTheView() {
        final var limit = new Limit(BlockPos.ZERO, 3, 16);
        try (var ledger = new NearbySpawnReservations(List.of())) {
            final var admitted = ledger.reserve(sample(1, 0, 0, 0), limit).orElseThrow();
            final var rejected = ledger.reserve(sample(2, 0, 0, 0), limit).orElseThrow();
            final var failed = ledger.reserve(sample(3, 0, 0, 0), limit).orElseThrow();
            admitted.completeAdmission(DeferredEntityAdmissionScope.Result.ADMITTED);
            rejected.completeAdmission(DeferredEntityAdmissionScope.Result.REJECTED);
            assertEquals(2, ledger.count(ZOMBIE, limit.area()));
            assertThrows(IllegalStateException.class, () -> admitted.completeAdmission(DeferredEntityAdmissionScope.Result.ADMITTED));
            failed.completeAdmission(DeferredEntityAdmissionScope.Result.FAILED);
            assertThrows(IllegalStateException.class, () -> ledger.reserve(sample(4, 0, 0, 0), limit));
        }
    }

    @Test
    void lateShutdownReceiptsAreHarmlessAfterTheOwnerHasClosed() {
        final var ledger = new NearbySpawnReservations(List.of());
        final var ticket = ledger.reserve(sample(1, 0, 0, 0), new Limit(BlockPos.ZERO, 1, 16)).orElseThrow();
        ledger.close();
        ticket.completeAdmission(DeferredEntityAdmissionScope.Result.ABANDONED);
        assertThrows(IllegalStateException.class, () -> ledger.count(ZOMBIE, new AABB(BlockPos.ZERO)));
    }

    @Test
    void invalidGeometryAndDuplicateSnapshotsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new Limit(BlockPos.ZERO, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Limit(BlockPos.ZERO, 1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Limit(BlockPos.ZERO, 1, 257));
        assertThrows(IllegalArgumentException.class, () -> new Limit(BlockPos.ZERO, -1, 16));
        assertThrows(IllegalArgumentException.class, () -> new Sample(new UUID(0, 1), ZOMBIE, new AABB(0, 0, 0, Double.NaN, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new NearbySpawnReservations(List.of(sample(1, 0, 0, 0), sample(1, 1, 0, 0))));
    }

    @Test
    void mixedReservationsAndCancellationsMatchAnIndependentSerialQueryModel() {
        final Random random = new Random(721429L);
        final List<Sample> initial = new ArrayList<>();
        for (int i = 1; i <= 200; i++) initial.add(sample(i, random.nextInt(400) - 200, random.nextInt(100), random.nextInt(400) - 200));
        final List<Sample> admitted = new ArrayList<>(initial);
        final List<Sample> pending = new ArrayList<>();
        final List<NearbySpawnReservations.Ticket> tickets = new ArrayList<>();
        try (var ledger = new NearbySpawnReservations(initial)) {
            for (int i = 201; i <= 2200; i++) {
                final var candidate = sample(i, random.nextInt(400) - 200, random.nextInt(100), random.nextInt(400) - 200);
                final var limit = new Limit(BlockPos.containing(candidate.bounds().minX, candidate.bounds().minY, candidate.bounds().minZ),
                        random.nextInt(8), 1 + random.nextInt(128));
                final long count = java.util.stream.Stream.concat(admitted.stream(), pending.stream())
                        .filter(sample -> sample.type().equals(candidate.type()) && sample.bounds().intersects(limit.area())).count();
                final var ticket = ledger.reserve(candidate, limit);
                assertEquals(count < limit.maximum(), ticket.isPresent(), "step " + i);
                if (ticket.isPresent()) {
                    pending.add(candidate);
                    tickets.add(ticket.get());
                }
                if (!pending.isEmpty() && random.nextBoolean()) {
                    final int index = random.nextInt(pending.size());
                    final var resolved = pending.remove(index);
                    final var token = tickets.remove(index);
                    if (random.nextBoolean()) {
                        ledger.commit(token);
                        admitted.add(resolved);
                    } else {
                        ledger.cancel(token);
                    }
                }
                assertEquals(java.util.stream.Stream.concat(admitted.stream(), pending.stream())
                        .filter(sample -> sample.bounds().intersects(limit.area())).count(), ledger.count(ZOMBIE, limit.area()));
            }
        }
    }
}
