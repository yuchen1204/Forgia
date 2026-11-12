/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Directed dependency waves in the input's canonical order. This planner grants no ownership and executes
 * nothing. An observed footprint is not a complete footprint; callers must not use this as worker admission.
 */
@ApiStatus.Internal
public final class BlockEntityWorkPlanner {
    private BlockEntityWorkPlanner() {}

    /** Identity-scoped resource. Equal coordinates in another level/server never alias. */
    public static final class Resource {
        private final Object domain;
        private final Kind kind;
        private final long key;
        private final Object instance;

        public Resource(Object domain, Kind kind, long key) {
            this.domain = Objects.requireNonNull(domain);
            this.kind = Objects.requireNonNull(kind);
            if (kind == Kind.INSTANCE) throw new IllegalArgumentException("Instance keys require an actual object");
            this.key = key;
            this.instance = null;
        }

        /** Current-operation object key. Historical position keys remain a separate diagnostic form. */
        public Resource(Object domain, Object instance) {
            this.domain = Objects.requireNonNull(domain);
            this.kind = Kind.INSTANCE;
            this.key = 0L;
            this.instance = Objects.requireNonNull(instance);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Resource resource && this.domain == resource.domain
                    && this.kind == resource.kind && this.key == resource.key && this.instance == resource.instance;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * System.identityHashCode(this.domain) + this.kind.hashCode()) + Long.hashCode(this.key))
                    + System.identityHashCode(this.instance);
        }
    }

    public enum Kind {
        BLOCK_ENTITY, CHUNK, INSTANCE
    }

    public record Unit(Set<Resource> reads, Set<Resource> writes, boolean exclusive) {
        public Unit {
            reads = Set.copyOf(reads);
            writes = Set.copyOf(writes);
        }
    }

    /** Indices refer to input order. Links count unique predecessor links, not transitive closure. */
    public record Plan(List<List<Integer>> waves, long dependencyLinks, int components, int largestComponent, int maxWidth) {
        public Plan {
            waves = waves.stream().map(List::copyOf).toList();
        }
    }

    /** Execution-only result. Graph links and connected components are computed only by {@link #plan}. */
    public record ExecutionPlan(List<List<Integer>> waves, int maxWidth) {
        public ExecutionPlan {
            waves = waves.stream().map(List::copyOf).toList();
        }
    }

    /**
     * The earliest legal depth needs only the latest writer depth and greatest following reader depth.
     * A fence follows the global maximum and replaces all earlier resource state with its barrier depth.
     * No reader/predecessor index sets, union-find or quadratic fence links are needed for execution.
     */
    public static ExecutionPlan planExecution(List<Unit> input) {
        if (input.size() > RegionBlockEntityWorkGraph.MAX_UNITS) throw new IllegalArgumentException("Work graph unit limit exceeded");
        final Map<Resource, Depths> accesses = new HashMap<>();
        final List<List<Integer>> waves = new ArrayList<>();
        int barrier = 0;
        int maximum = -1;
        int width = 0;
        for (int index = 0; index < input.size(); ++index) {
            final Unit unit = input.get(index);
            int depth = unit.exclusive() ? maximum + 1 : barrier;
            if (!unit.exclusive()) {
                for (final Resource resource : unit.reads()) {
                    final Depths previous = accesses.get(resource);
                    if (previous != null) depth = Math.max(depth, previous.writer + 1);
                }
                for (final Resource resource : unit.writes()) {
                    final Depths previous = accesses.get(resource);
                    if (previous != null) depth = Math.max(depth, Math.max(previous.writer, previous.reader) + 1);
                }
            }
            while (waves.size() <= depth) waves.add(new ArrayList<>());
            final List<Integer> wave = waves.get(depth);
            wave.add(index);
            width = Math.max(width, wave.size());
            maximum = Math.max(maximum, depth);
            if (unit.exclusive()) {
                barrier = depth + 1;
                accesses.clear();
            } else {
                for (final Resource resource : unit.reads()) {
                    if (!unit.writes().contains(resource)) {
                        final Depths state = accesses.computeIfAbsent(resource, ignored -> new Depths());
                        state.reader = Math.max(state.reader, depth);
                    }
                }
                for (final Resource resource : unit.writes()) {
                    final Depths state = accesses.computeIfAbsent(resource, ignored -> new Depths());
                    state.writer = depth;
                    state.reader = -1;
                }
            }
        }
        return new ExecutionPlan(waves, width);
    }

    private static final class Depths {
        private int writer = -1;
        private int reader = -1;
    }

    public static Plan plan(List<Unit> input) {
        if (input.size() > RegionBlockEntityWorkGraph.MAX_UNITS) throw new IllegalArgumentException("Work graph unit limit exceeded");
        final Map<Resource, Access> accesses = new HashMap<>();
        final List<List<Integer>> waves = new ArrayList<>();
        final int[] depths = new int[input.size()];
        final int[] parents = new int[input.size()];
        final int[] sizes = new int[input.size()];
        int latestBarrier = -1;
        int maxDepth = -1;
        int maxWidth = 0;
        long links = 0L;
        for (int index = 0; index < input.size(); ++index) {
            parents[index] = index;
            sizes[index] = 1;
            final Unit unit = input.get(index);
            final Set<Integer> predecessors = new HashSet<>();
            if (unit.exclusive()) {
                // The unknown operation is a fence, including against units whose resources were unobserved.
                for (int prior = 0; prior < index; ++prior) predecessors.add(prior);
            } else {
                if (latestBarrier >= 0) predecessors.add(latestBarrier);
                for (final Resource resource : unit.reads()) {
                    final Access access = accesses.get(resource);
                    if (access != null && access.writer >= 0) predecessors.add(access.writer);
                }
                for (final Resource resource : unit.writes()) {
                    final Access access = accesses.get(resource);
                    if (access != null) {
                        if (access.writer >= 0) predecessors.add(access.writer);
                        predecessors.addAll(access.readers);
                    }
                }
            }
            int depth = unit.exclusive() ? maxDepth + 1 : 0;
            for (final int predecessor : predecessors) {
                depth = Math.max(depth, depths[predecessor] + 1);
                join(parents, sizes, index, predecessor);
            }
            links += predecessors.size();
            depths[index] = depth;
            maxDepth = Math.max(maxDepth, depth);
            while (waves.size() <= depth) waves.add(new ArrayList<>());
            final List<Integer> wave = waves.get(depth);
            wave.add(index);
            maxWidth = Math.max(maxWidth, wave.size());
            // Update after dependency discovery: a read/write of the same resource never depends on itself.
            for (final Resource resource : unit.reads()) {
                if (!unit.writes().contains(resource)) accesses.computeIfAbsent(resource, ignored -> new Access()).readers.add(index);
            }
            for (final Resource resource : unit.writes()) {
                final Access access = accesses.computeIfAbsent(resource, ignored -> new Access());
                access.writer = index;
                access.readers.clear();
            }
            if (unit.exclusive()) latestBarrier = index;
        }
        int components = 0;
        int largest = 0;
        for (int index = 0; index < parents.length; ++index) {
            if (root(parents, index) == index) {
                ++components;
                largest = Math.max(largest, sizes[index]);
            }
        }
        return new Plan(waves, links, components, largest, maxWidth);
    }

    private static int root(int[] parents, int index) {
        while (parents[index] != index) {
            parents[index] = parents[parents[index]];
            index = parents[index];
        }
        return index;
    }

    private static void join(int[] parents, int[] sizes, int first, int second) {
        final int a = root(parents, first);
        final int b = root(parents, second);
        if (a != b) {
            parents[b] = a;
            sizes[a] += sizes[b];
        }
    }

    private static final class Access {
        private int writer = -1;
        private final Set<Integer> readers = new HashSet<>();
    }
}
