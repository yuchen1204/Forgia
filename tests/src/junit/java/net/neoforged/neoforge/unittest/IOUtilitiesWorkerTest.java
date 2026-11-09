/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class IOUtilitiesWorkerTest {
    @Test
    void failedWorkerTaskDoesNotPoisonFollowingSaves() {
        final RuntimeException failure = new RuntimeException("injected failed save task");
        final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        IOUtilities.withIOWorker(() -> {
            throw failure;
        });
        IOUtilities.withIOWorker(() -> {
            writes.incrementAndGet();
        });
        try {
            final var reported = Assertions.assertThrows(java.util.concurrent.CompletionException.class, IOUtilities::waitUntilIOWorkerComplete);
            Assertions.assertSame(failure, reported.getCause());
            Assertions.assertEquals(1, writes.get(), "later accepted writes must execute even after an earlier task failed");
        } finally {
            // Acknowledge any still-pending prefix so this static queue does not contaminate another test.
            try {
                IOUtilities.waitUntilIOWorkerComplete();
            } catch (java.util.concurrent.CompletionException ignored) {}
        }
        IOUtilities.withIOWorker(() -> {
            writes.incrementAndGet();
        });
        IOUtilities.waitUntilIOWorkerComplete();
        Assertions.assertEquals(2, writes.get());
    }

    /**
     * Tests that waiting on the IO worker will complete remaining tasks
     */
    @Test
    void testWaitOnWorker() {
        AtomicBoolean value = new AtomicBoolean(false);
        IOUtilities.withIOWorker(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {}
            value.set(true);
        });
        Assertions.assertFalse(value.get(), "Value should not be set yet");
        IOUtilities.waitUntilIOWorkerComplete();
        Assertions.assertTrue(value.get(), "Value should be set after waiting");
    }
}
