/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.junit.jupiter.api.Test;

class RegionFailureAggregationTest {
    @Test
    void repeatedThrowableDoesNotAbortTheRemainingMutationDrain() throws Exception {
        final RegionThreadingEngine engine = new RegionThreadingEngine(1, false, false);
        try {
            final RuntimeException reused = new RuntimeException("same mod failure");
            final RuntimeException separate = new RuntimeException("another failure");
            final AtomicInteger completed = new AtomicInteger();
            final Queue<Runnable> mutations = new ArrayDeque<>(List.of(
                    () -> {
                        throw reused;
                    },
                    () -> {
                        throw reused;
                    },
                    () -> {
                        throw separate;
                    },
                    () -> {
                        completed.incrementAndGet();
                    }));
            final Method drain = RegionThreadingEngine.class.getDeclaredMethod("applyRegionTickMutations", Queue.class);
            drain.setAccessible(true);
            final InvocationTargetException reported = assertThrows(InvocationTargetException.class, () -> drain.invoke(engine, mutations));
            assertSame(reused, reported.getCause());
            assertEquals(1, completed.get(), "a suppression error must not strand unrelated accepted work");
            assertEquals(0, mutations.size());
            assertEquals(List.of(separate), List.of(reused.getSuppressed()));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void completionKeepsTheOriginalThrowableAndStillRunsFollowingActions() throws Exception {
        final Method completion = RegionThreadingEngine.class.getDeclaredMethod("runBatchCompletion", Throwable.class, Runnable.class);
        completion.setAccessible(true);
        final RuntimeException reused = new RuntimeException("same batch failure");
        final Runnable failAgain = () -> {
            throw reused;
        };
        assertSame(reused, completion.invoke(null, reused, failAgain));
        final AtomicInteger completed = new AtomicInteger();
        final Runnable finish = () -> {
            completed.incrementAndGet();
        };
        assertSame(reused, completion.invoke(null, reused, finish));
        assertEquals(1, completed.get());
        assertEquals(0, reused.getSuppressed().length);
    }
}
