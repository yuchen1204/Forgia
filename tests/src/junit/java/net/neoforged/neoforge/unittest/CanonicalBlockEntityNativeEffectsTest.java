/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntitySequenceTest.dump;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntitySequenceTest.serial;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The actual untyped level-end task bridge must be resolved before native canonical worker migration. */
@Tag("canonical-be-contract")
@Timeout(120)
class CanonicalBlockEntityNativeEffectsTest {
    private static final BlockPos FIRST = new BlockPos(20, 64, 20);
    private static final BlockPos UNKNOWN = new BlockPos(68, 64, 20);

    @Test
    void nativeServerTaskMustBeVisibleToTheNextUnknownFence() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            nativeTask(f);
            f.script("U", UNKNOWN, "test:unknown", () -> f.cells.get("U").set(f.cells.get("effect").get()));
        };
        final var oracle = serial(input);
        assertEquals("1", oracle.state().get("U"));
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(false);
            try {
                f.levelPhase();
                f.clean();
                assertEquals(1, f.engine.diagnostics().blockEntityTicksOnWorkers());
                assertTrue(f.trace().stream().anyMatch(e -> e.id().equals("A") && e.action().equals("server-task-queued") && e.worker()));
                // Preserve the existing real level-end bridge, including its exactly-once cleanup. Its
                // execution here cannot retroactively make the earlier unknown ticker observe the effect.
                f.event("phase", "end-level-tick");
                f.engine.endLevelTick(f.level);
                assertEquals(1, f.cells.get("effect").get());
                assertEquals(1, f.cells.get("A").get());
                f.before("effect", "applied", "U", "body-start");
                assertEquals(oracle.state(), f.state(), "the full unknown ticker must observe the preceding native effect");
            } finally {
                f.engine.endLevelTick(f.level);
                assertEquals(1, f.cells.get("effect").get(), "never replay a queued effect to repair visibility");
                f.clean();
                f.probes();
                dump("native-level-end-fence", f);
            }
        }
    }

    @Test
    void retainedExplicitNativeBridgeKeepsLevelEndTaskTimingAndExactlyOnceExecution() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            nativeTask(f);
            f.enable(false);
            try {
                f.explicitPhase("A");
                f.clean();
                assertEquals(1, f.cells.get("A").get());
                assertEquals(0, f.cells.get("effect").get(), "the retained old driver parks this task until level end");
                f.event("phase", "end-level-tick");
                f.engine.endLevelTick(f.level);
                f.engine.endLevelTick(f.level);
                assertEquals(1, f.cells.get("effect").get());
                assertEquals(1, f.cells.get("A").get());
                f.before("phase", "end-level-tick", "effect", "applied");
                f.clean();
                f.probes();
            } finally {
                f.engine.endLevelTick(f.level);
                dump("retained-native-level-end", f);
            }
        }
    }

    private static void nativeTask(CanonicalBlockEntityTestSupport f) {
        f.cells.put("effect", new AtomicInteger());
        f.script("A", FIRST, "minecraft:hopper", () -> {
            final Runnable effect = () -> {
                f.cells.get("effect").incrementAndGet();
                f.event("effect", "applied");
            };
            if (f.engine == null || !f.engine.queueServerTaskInCurrentBatch(effect)) {
                effect.run();
            } else {
                f.event("A", "server-task-queued");
            }
        });
    }
}
