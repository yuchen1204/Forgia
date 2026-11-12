/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * A published batch of independent tasks, drained by at most one runner per pool thread.
 * <p>
 * Each runner owns a range of unstarted task indices. It claims from the front of that range; an idle
 * runner atomically removes half of another range's tail and publishes it as its own range. Both bounds
 * occupy one CAS word, so an owner and a thief cannot claim the same task, even at the final element.
 * Already claimed work, and the groups inside an explicitly packed task, cannot be stolen.
 * <p>
 * Ranges and task bodies are published before any runner is submitted. A runner may drain a range whose
 * runner has not started yet, so delayed or executor-rejected runners cannot pin unstarted work. A runner
 * never waits for another runner; completion is still the task batch's per-group barrier.
 */
final class RegionWorkStealingDispatcher {
    private final AtomicLong[] ranges;
    private final IntConsumer work;
    private final IntConsumer onSteal;

    RegionWorkStealingDispatcher(int tasks, int runners, IntConsumer work, IntConsumer onSteal) {
        if (tasks <= 0 || runners <= 0 || runners > tasks) {
            throw new IllegalArgumentException("Require 0 < runners <= tasks");
        }
        this.work = work;
        this.onSteal = onSteal;
        this.ranges = new AtomicLong[runners];
        for (int runner = 0; runner < runners; ++runner) {
            final int from = (int) ((long) tasks * runner / runners);
            final int to = (int) ((long) tasks * (runner + 1) / runners);
            this.ranges[runner] = new AtomicLong(bounds(from, to));
        }
    }

    int runnerCount() {
        return this.ranges.length;
    }

    /** Drain once for this runner, retaining the first failure until all available work has been tried. */
    void run(int runner) {
        Throwable failure = null;
        int victimCursor = (runner + 1) % this.ranges.length;
        while (true) {
            final int task = this.takeOwn(runner);
            if (task >= 0) {
                try {
                    this.work.accept(task);
                } catch (Throwable throwable) {
                    // The work wrapper releases its group permits even on failure. Retiring here would leave
                    // the remainder of this range queued behind a runner which can never come back.
                    if (failure == null) failure = throwable;
                }
                continue;
            }
            final int victim = this.steal(runner, victimCursor);
            if (victim < 0) break;
            victimCursor = (victim + 1) % this.ranges.length;
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Region dispatcher task failed", failure);
    }

    private int takeOwn(int runner) {
        final AtomicLong range = this.ranges[runner];
        while (true) {
            final long observed = range.get();
            final int from = from(observed);
            final int to = to(observed);
            if (from == to) return -1;
            if (range.compareAndSet(observed, bounds(from + 1, to))) return from;
        }
    }

    /** Transfer an unstarted tail to this runner; its previous range is empty. */
    private int steal(int runner, int first) {
        for (int offset = 0; offset < this.ranges.length; ++offset) {
            final int victim = (first + offset) % this.ranges.length;
            if (victim == runner) continue;
            final AtomicLong range = this.ranges[victim];
            while (true) {
                final long observed = range.get();
                final int from = from(observed);
                final int to = to(observed);
                if (from == to) break;
                final int count = Math.max(1, (to - from) / 2);
                final int split = to - count;
                if (range.compareAndSet(observed, bounds(from, split))) {
                    // Only this runner publishes to its own empty range. Other runners may remove work
                    // after publication, but no task re-enters a range after it has been claimed.
                    this.ranges[runner].set(bounds(split, to));
                    this.onSteal.accept(count);
                    return victim;
                }
            }
        }
        return -1;
    }

    private static long bounds(int from, int to) {
        return (long) from << 32 | to & 0xffffffffL;
    }

    private static int from(long bounds) {
        return (int) (bounds >>> 32);
    }

    private static int to(long bounds) {
        return (int) bounds;
    }
}
