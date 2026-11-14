/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real mixed phase callbacks must reject a lifecycle self-wait before contending for cleanup's monitor. */
@Tag("canonical-be-contract")
@Timeout(120)
class CanonicalBlockEntityLifecycleTest {
    private static final BlockPos A = new BlockPos(20, 64, 20);
    private static final BlockPos B = new BlockPos(68, 64, 20);
    private static final BlockPos U = new BlockPos(116, 64, 20);

    @ParameterizedTest(name = "server lifecycle callback rejects before monitor, shutdown={0}")
    @ValueSource(booleans = { false, true })
    void anActiveServerFenceRejectsLifecycleSelfWaitBeforeTheCleanupMonitor(boolean shutdown) throws InterruptedException {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var inFence = new CountDownLatch(1);
            final var monitorHeld = new CountDownLatch(1);
            final var attempted = new CountDownLatch(1);
            final var rejected = new CountDownLatch(1);
            final var fastRejection = new AtomicBoolean();
            final var failure = new AtomicReference<Throwable>();
            f.script("N0", A, "minecraft:hopper", () -> {});
            f.script("N1", B, "minecraft:hopper", () -> {
                assertTrue(f.engine.queueServerTaskInCurrentBatch(() -> f.event("effect", "applied")));
            });
            f.furnace("F0", A.east(3), 1);
            f.furnace("F1", B.east(3), 1);
            f.foreign("U", U, () -> {
                f.before("effect", "applied", "U", "body-start");
                f.before("F1", "changed", "U", "body-start");
                inFence.countDown();
                await(monitorHeld);
                attempted.countDown();
                try {
                    assertThrows(IllegalStateException.class, () -> {
                        if (shutdown) f.engine.shutdown();
                        else f.engine.clearLevelRegionTasks(f.level);
                    });
                } finally {
                    rejected.countDown();
                }
            });
            f.script("N2", B.east(6), "minecraft:hopper", () -> {});
            f.furnace("F2", A.east(6), 1);
            f.furnace("F3", B.east(9), 1);
            f.enable(true);
            final var holder = new Thread(() -> {
                try {
                    await(inFence);
                    synchronized (f.engine) {
                        monitorHeld.countDown();
                        await(attempted);
                        // The timeout releases only this test's monitor, allowing old code to finish
                        // and report a real failure instead of leaving an uninterruptible deadlock.
                        fastRejection.set(rejected.await(5, TimeUnit.SECONDS));
                    }
                } catch (Throwable error) {
                    failure.set(error);
                    monitorHeld.countDown();
                }
            }, "canonical-lifecycle-monitor-holder");
            holder.start();
            try {
                f.levelPhase();
                holder.join(10_000);
                assertFalse(holder.isAlive());
                assertNull(failure.get());
                assertTrue(fastRejection.get(), "active server callback must reject before taking a cleanup monitor whose owner may be awaiting this phase");
                assertEquals(1, f.observedBatches.size());
                assertEquals(8, f.observedTickets.size());
                assertEquals(7, f.engine.diagnostics().blockEntityTicksOnWorkers());
                assertEquals(4, f.engine.furnaceWorkMetrics().nativeCompletions());
                for (final var id : new String[] { "N0", "N1", "N2", "U" }) assertEquals(1, f.cells.get(id).get());
                for (final var id : new String[] { "F0", "F1", "F2", "F3" }) assertEquals(1, f.furnaces.get(id).getItem(2).getCount());
                f.clean();
                f.probes();
            } finally {
                holder.join(10_000);
                System.out.println("C41_LIFECYCLE_MONITOR shutdown=" + shutdown + " rejectedBeforeMonitor=" + fastRejection.get());
            }
        }
    }
}
