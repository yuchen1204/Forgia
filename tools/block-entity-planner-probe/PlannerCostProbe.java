/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner.Resource;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner.Unit;

/**
 * Standalone planning experiment. No Minecraft runtime, executor, world access or execution permission.
 * The prototype returns only waves; the production planner also computes graph diagnostics.
 */
public final class PlannerCostProbe {
    private static volatile Object sink;
    private static final Object DOMAIN = new Object();
    private static final ThreadMXBean ALLOCATIONS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        final int cases = args.length > 0 ? Integer.parseInt(args[0]) : 10000;
        final int samples = args.length > 1 ? Integer.parseInt(args[1]) : 21;
        if (cases < 1 || samples < 3) throw new IllegalArgumentException("cases >= 1, samples >= 3 required");
        if (!ALLOCATIONS.isThreadAllocatedMemorySupported()) throw new IllegalStateException("JVM allocation counter unavailable");
        ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        verify(cases);
        System.out.printf("{\"kind\":\"verification\",\"randomCases\":%d,\"seed\":20261112,\"java\":\"%s\",\"cpus\":%d,\"result\":\"PASS\"}%n",
                cases, System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        for (final int size : new int[] {64, 256, 1024, 2048}) {
            for (final String pattern : List.of("independent", "all_fences", "three_then_fence", "shared_write")) {
                measure(size, pattern, samples);
            }
        }
    }

    private static void verify(int cases) {
        final Random random = new Random(20261112L);
        final Object otherDomain = new Object();
        final List<Resource> resources = new ArrayList<>();
        for (int i = 0; i < 24; ++i) {
            resources.add(new Resource(i % 2 == 0 ? DOMAIN : otherDomain,
                    i % 3 == 0 ? BlockEntityWorkPlanner.Kind.CHUNK : BlockEntityWorkPlanner.Kind.BLOCK_ENTITY, i / 2));
        }
        for (int iteration = 0; iteration < cases; ++iteration) {
            final List<Unit> input = new ArrayList<>();
            for (int i = 0, size = random.nextInt(97); i < size; ++i) {
                final Set<Resource> reads = new HashSet<>();
                final Set<Resource> writes = new HashSet<>();
                for (int j = 0, count = random.nextInt(5); j < count; ++j) reads.add(resources.get(random.nextInt(resources.size())));
                for (int j = 0, count = random.nextInt(4); j < count; ++j) writes.add(resources.get(random.nextInt(resources.size())));
                input.add(new Unit(reads, writes, random.nextInt(8) == 0));
            }
            check(input);
        }
        final Resource x = resources.get(0);
        final Resource y = resources.get(1);
        final var counterexample = List.of(new Unit(Set.of(), Set.of(x), false),
                new Unit(Set.of(), Set.of(x, y), false), new Unit(Set.of(), Set.of(y), false));
        if (!compact(counterexample).equals(List.of(List.of(0), List.of(1), List.of(2)))) {
            throw new AssertionError("A(X), B(X,Y), C(Y) lost original directed order");
        }
        for (final int size : new int[] {64, 256, 1024, 2048}) {
            for (final String pattern : List.of("independent", "all_fences", "three_then_fence", "shared_write")) check(input(size, pattern));
        }
    }

    private static void check(List<Unit> input) {
        final var actual = compact(input);
        final var reference = BlockEntityWorkPlanner.plan(input).waves();
        if (!actual.equals(reference)) throw new AssertionError("Different waves: " + actual + " vs " + reference);
        final int[] depths = new int[input.size()];
        for (int d = 0; d < actual.size(); ++d) for (final int i : actual.get(d)) depths[i] = d;
        for (int i = 0; i < input.size(); ++i) {
            for (int j = i + 1; j < input.size(); ++j) {
                final Unit a = input.get(i);
                final Unit b = input.get(j);
                if ((a.exclusive() || b.exclusive() || overlaps(a.writes(), b.reads())
                        || overlaps(a.writes(), b.writes()) || overlaps(a.reads(), b.writes())) && depths[i] >= depths[j]) {
                    throw new AssertionError("Reordered a directed conflict at " + i + " -> " + j);
                }
            }
        }
    }

    private static boolean overlaps(Set<Resource> a, Set<Resource> b) {
        for (final Resource resource : a) if (b.contains(resource)) return true;
        return false;
    }

    private static List<Unit> input(int size, String pattern) {
        final List<Unit> units = new ArrayList<>(size);
        final Resource chunk = new Resource(DOMAIN, BlockEntityWorkPlanner.Kind.CHUNK, 0);
        for (int i = 0; i < size; ++i) {
            final boolean fence = pattern.equals("all_fences") || (pattern.equals("three_then_fence") && i % 4 == 3);
            final Resource source = new Resource(DOMAIN, BlockEntityWorkPlanner.Kind.BLOCK_ENTITY, pattern.equals("shared_write") ? 0 : i);
            units.add(new Unit(Set.of(chunk), Set.of(source), fence));
        }
        return units;
    }

    private static void measure(int size, String pattern, int samples) {
        final List<Unit> units = input(size, pattern);
        for (int i = 0; i < 12; ++i) {
            sink = BlockEntityWorkPlanner.plan(units);
            sink = compact(units);
        }
        final long[][] times = new long[2][samples];
        final long[] bytes = new long[2];
        final long thread = Thread.currentThread().threadId();
        for (int i = 0; i < samples; ++i) {
            for (int position = 0; position < 2; ++position) {
                final int implementation = (i + position) % 2;
                final long allocated = ALLOCATIONS.getThreadAllocatedBytes(thread);
                final long start = System.nanoTime();
                sink = implementation == 0 ? BlockEntityWorkPlanner.plan(units) : compact(units);
                times[implementation][i] = System.nanoTime() - start;
                bytes[implementation] += ALLOCATIONS.getThreadAllocatedBytes(thread) - allocated;
            }
        }
        final var reference = BlockEntityWorkPlanner.plan(units);
        for (int implementation = 0; implementation < 2; ++implementation) {
            Arrays.sort(times[implementation]);
            System.out.printf("{\"kind\":\"measurement\",\"units\":%d,\"pattern\":\"%s\",\"implementation\":\"%s\",\"samples\":%d,\"p50Ms\":%.6f,\"p95Ms\":%.6f,\"meanAllocatedBytes\":%d,\"waves\":%d,\"referenceLinks\":%d}%n",
                    size, pattern, implementation == 0 ? "production_with_diagnostics" : "compact_waves_only", samples,
                    times[implementation][samples / 2] / 1e6,
                    times[implementation][(int) Math.ceil(samples * .95) - 1] / 1e6,
                    bytes[implementation] / samples, reference.waves().size(), reference.dependencyLinks());
        }
    }

    /**
     * A read depends on the latest writer. A write depends on the latest writer and all readers since it.
     * Only their maximum depth is needed to produce waves. A fence follows the global maximum depth;
     * all later work follows that fence, so earlier resource records can be discarded.
     * This intentionally omits component/link diagnostics and supplies no current-operation proof.
     */
    private static List<List<Integer>> compact(List<Unit> input) {
        final Map<Resource, Depths> resources = new HashMap<>();
        final List<List<Integer>> waves = new ArrayList<>();
        int barrierDepth = -1;
        int maximumDepth = -1;
        for (int i = 0; i < input.size(); ++i) {
            final Unit unit = input.get(i);
            int depth;
            if (unit.exclusive()) {
                depth = maximumDepth + 1;
                barrierDepth = depth;
                resources.clear();
            } else {
                depth = barrierDepth + 1;
                for (final Resource resource : unit.reads()) {
                    final Depths known = resources.get(resource);
                    if (known != null) depth = Math.max(depth, known.writer + 1);
                }
                for (final Resource resource : unit.writes()) {
                    final Depths known = resources.get(resource);
                    if (known != null) depth = Math.max(depth, Math.max(known.writer, known.reader) + 1);
                }
                for (final Resource resource : unit.reads()) {
                    if (!unit.writes().contains(resource)) {
                        final Depths known = resources.computeIfAbsent(resource, ignored -> new Depths());
                        known.reader = Math.max(known.reader, depth);
                    }
                }
                for (final Resource resource : unit.writes()) {
                    final Depths known = resources.computeIfAbsent(resource, ignored -> new Depths());
                    known.writer = depth;
                    known.reader = -1;
                }
            }
            maximumDepth = Math.max(maximumDepth, depth);
            while (waves.size() <= depth) waves.add(new ArrayList<>());
            waves.get(depth).add(i);
        }
        return waves.stream().map(List::copyOf).toList();
    }

    private static final class Depths {
        private int writer = -1;
        private int reader = -1;
    }
}
