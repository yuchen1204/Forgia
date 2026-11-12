/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner.Kind;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner.Resource;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner.Unit;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityWorkGraph;
import org.junit.jupiter.api.Test;

class BlockEntityWorkPlannerTest {
    private final Object level = new Object();

    private Resource resource(long id) {
        return new Resource(this.level, Kind.BLOCK_ENTITY, id);
    }

    private Unit writes(long... positions) {
        final Set<Resource> resources = new HashSet<>();
        for (final long position : positions) resources.add(this.resource(position));
        return new Unit(Set.of(), resources, false);
    }

    @Test
    void intersectingChainPreservesCausalityInsteadOfFirstFitColouring() {
        final var plan = BlockEntityWorkPlanner.plan(List.of(this.writes(1), this.writes(1, 2), this.writes(2), this.writes(2, 3), this.writes(3)));
        assertEquals(List.of(List.of(0), List.of(1), List.of(2), List.of(3), List.of(4)), plan.waves());
        assertEquals(1, plan.components());
        assertEquals(5, plan.largestComponent());
    }

    @Test
    void independentMachinesRemainWideIncludingIdenticalChunkReadAccess() {
        final Resource chunk = new Resource(this.level, Kind.CHUNK, -1L);
        final var plan = BlockEntityWorkPlanner.plan(List.of(
                new Unit(Set.of(chunk), Set.of(this.resource(1)), false),
                new Unit(Set.of(chunk), Set.of(this.resource(2)), false),
                new Unit(Set.of(chunk), Set.of(this.resource(3)), false)));
        assertEquals(List.of(List.of(0, 1, 2)), plan.waves());
        assertEquals(3, plan.components());
        assertEquals(3, plan.maxWidth());
        assertEquals(0, plan.dependencyLinks());
    }

    @Test
    void writeWaitsForAllPriorReadersAndLaterReadsWaitForTheWrite() {
        final Resource resource = this.resource(1);
        final var plan = BlockEntityWorkPlanner.plan(List.of(new Unit(Set.of(resource), Set.of(), false),
                new Unit(Set.of(resource), Set.of(), false), new Unit(Set.of(resource), Set.of(resource), false),
                new Unit(Set.of(resource), Set.of(), false), new Unit(Set.of(resource), Set.of(), false)));
        assertEquals(List.of(List.of(0, 1), List.of(2), List.of(3, 4)), plan.waves());
        assertEquals(1, plan.components());
        assertEquals(4, plan.dependencyLinks());
    }

    @Test
    void unknownEffectIsAnExclusiveFenceEvenWithoutARecordedPosition() {
        final var plan = BlockEntityWorkPlanner.plan(List.of(this.writes(1), this.writes(2),
                new Unit(Set.of(), Set.of(), true), this.writes(3), this.writes(4)));
        assertEquals(List.of(List.of(0, 1), List.of(2), List.of(3, 4)), plan.waves());
        assertEquals(1, plan.components());
        assertEquals(5, plan.largestComponent());
    }

    @Test
    void domainIdentityAndResourceKindsDoNotAccidentallyAlias() {
        final Object first = new String("same dimension label");
        final Object second = new String("same dimension label");
        final Resource be = new Resource(first, Kind.BLOCK_ENTITY, -20);
        assertNotEquals(be, new Resource(second, Kind.BLOCK_ENTITY, -20));
        assertNotEquals(be, new Resource(first, Kind.CHUNK, -20));
        assertEquals(be, new Resource(first, Kind.BLOCK_ENTITY, -20));
        final var plan = BlockEntityWorkPlanner.plan(List.of(new Unit(Set.of(), Set.of(be), false),
                new Unit(Set.of(), Set.of(new Resource(second, Kind.BLOCK_ENTITY, -20)), false)));
        assertEquals(2, plan.maxWidth());
    }

    @Test
    void plansAndInputsAreDetachedAndTheUnitLimitIsExplicit() {
        final Set<Resource> resources = new HashSet<>(Set.of(this.resource(1)));
        final Unit copied = new Unit(Set.of(), resources, false);
        resources.clear();
        assertEquals(1, copied.writes().size());
        final var plan = BlockEntityWorkPlanner.plan(List.of(copied));
        assertThrows(UnsupportedOperationException.class, () -> plan.waves().add(List.of(9)));
        assertThrows(UnsupportedOperationException.class, () -> plan.waves().getFirst().add(9));
        assertThrows(IllegalArgumentException.class, () -> BlockEntityWorkPlanner.plan(
                java.util.Collections.nCopies(RegionBlockEntityWorkGraph.MAX_UNITS + 1, copied)));
        assertTrue(BlockEntityWorkPlanner.plan(List.of()).waves().isEmpty());
    }

    @Test
    void randomizedPlansPreserveEveryConflictAndProduceTheSerialStateUnderWavePermutations() {
        final Random random = new Random(813584L);
        for (int round = 0; round < 150; ++round) {
            final List<Unit> units = new ArrayList<>();
            for (int index = 0; index < 60; ++index) {
                final Set<Resource> reads = new HashSet<>();
                final Set<Resource> writes = new HashSet<>();
                for (int access = 0; access < 4; ++access) {
                    (random.nextBoolean() ? reads : writes).add(this.resource(random.nextInt(15)));
                }
                units.add(new Unit(reads, writes, random.nextInt(30) == 0));
            }
            final var plan = BlockEntityWorkPlanner.plan(units);
            final var execution = BlockEntityWorkPlanner.planExecution(units);
            assertEquals(plan.waves(), execution.waves());
            assertEquals(plan.maxWidth(), execution.maxWidth());
            final int[] waveOf = new int[units.size()];
            final Set<Integer> seen = new HashSet<>();
            for (int wave = 0; wave < plan.waves().size(); ++wave) {
                for (final int index : plan.waves().get(wave)) {
                    assertTrue(seen.add(index), "every unit is scheduled once");
                    waveOf[index] = wave;
                }
            }
            assertEquals(units.size(), seen.size());
            for (int first = 0; first < units.size(); ++first) {
                for (int second = first + 1; second < units.size(); ++second) {
                    if (conflict(units.get(first), units.get(second))) assertTrue(waveOf[first] < waveOf[second]);
                }
            }
            final Map<Resource, Integer> serial = new HashMap<>();
            final Map<Resource, Integer> reordered = new HashMap<>();
            for (int index = 0; index < units.size(); ++index) execute(units.get(index), index, serial);
            for (final List<Integer> wave : plan.waves()) {
                final List<Integer> shuffled = new ArrayList<>(wave);
                java.util.Collections.shuffle(shuffled, random);
                for (final int index : shuffled) execute(units.get(index), index, reordered);
            }
            assertEquals(serial, reordered, "read-dependent writes must agree even after an arbitrary order inside each wave");
        }
    }

    @Test
    void executionPlannerMatchesDiagnosticsAcrossTenThousandMixedIdentityAndFenceCases() {
        final Random random = new Random(20261114L);
        final Object otherLevel = new String("same label");
        final List<Resource> resources = new ArrayList<>();
        for (int index = 0; index < 12; ++index) {
            final Object instance = new Object();
            resources.add(new Resource(this.level, instance));
            resources.add(new Resource(otherLevel, instance));
            resources.add(this.resource(index));
            resources.add(new Resource(this.level, Kind.CHUNK, index));
        }
        for (int iteration = 0; iteration < 10000; ++iteration) {
            final List<Unit> units = new ArrayList<>();
            for (int index = random.nextInt(40); index > 0; --index) {
                final Set<Resource> reads = new HashSet<>();
                final Set<Resource> writes = new HashSet<>();
                for (int count = random.nextInt(8); count > 0; --count) {
                    (random.nextBoolean() ? reads : writes).add(resources.get(random.nextInt(resources.size())));
                }
                units.add(new Unit(reads, writes, random.nextInt(5) == 0));
            }
            final var diagnostic = BlockEntityWorkPlanner.plan(units);
            final var execution = BlockEntityWorkPlanner.planExecution(units);
            assertEquals(diagnostic.waves(), execution.waves(), "seeded case " + iteration);
            assertEquals(diagnostic.maxWidth(), execution.maxWidth());
        }
    }

    @Test
    void executionOnlyFencesAreBoundedAndDetachedWithoutDiagnosticLinks() {
        final var fence = new Unit(Set.of(), Set.of(), true);
        final var plan = BlockEntityWorkPlanner.planExecution(java.util.Collections.nCopies(RegionBlockEntityWorkGraph.MAX_UNITS, fence));
        assertEquals(RegionBlockEntityWorkGraph.MAX_UNITS, plan.waves().size());
        for (int index = 0; index < plan.waves().size(); ++index) assertEquals(List.of(index), plan.waves().get(index));
        assertEquals(1, plan.maxWidth());
        assertThrows(UnsupportedOperationException.class, () -> plan.waves().getFirst().add(1));
        assertThrows(UnsupportedOperationException.class, () -> plan.waves().clear());
        assertThrows(IllegalArgumentException.class, () -> BlockEntityWorkPlanner.planExecution(java.util.Collections.nCopies(RegionBlockEntityWorkGraph.MAX_UNITS + 1, fence)));
        assertTrue(BlockEntityWorkPlanner.planExecution(List.of()).waves().isEmpty());
    }

    private static boolean conflict(Unit first, Unit second) {
        if (first.exclusive() || second.exclusive()) return true;
        return first.writes().stream().anyMatch(resource -> second.reads().contains(resource) || second.writes().contains(resource))
                || second.writes().stream().anyMatch(first.reads()::contains);
    }

    private static void execute(Unit unit, int index, Map<Resource, Integer> state) {
        final int value = unit.reads().stream().mapToInt(resource -> state.getOrDefault(resource, 0)).sum() + index + 1;
        for (final Resource write : unit.writes()) state.put(write, value);
    }
}
