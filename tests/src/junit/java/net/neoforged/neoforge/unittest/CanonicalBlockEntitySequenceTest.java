/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable target contracts for C2-C5. These assertions deliberately remain red where the OLD drivers
 * violate canonical order. Do not turn the violation into assertThrows, disable it, or "fix" the oracle.
 * The tag identifies target contracts; it is not excluded by the repository's normal junitTest task.
 */
@Tag("canonical-be-contract")
@Timeout(120)
class CanonicalBlockEntitySequenceTest {
    private static final BlockPos A = new BlockPos(17, 64, 17);
    private static final BlockPos U = new BlockPos(65, 64, 17);
    private static final BlockPos B = new BlockPos(113, 64, 17);
    private static final BlockPos C = new BlockPos(161, 64, 17);

    @Test
    void nativeUnknownFenceMustFinishBeforeTheLaterRegionBody() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.script("A", A, "minecraft:hopper", () -> {});
            f.script("U", U, "test:unknown", () -> f.cells.get("U").set(f.cells.get("B").get()));
            f.script("B", B, "minecraft:hopper", () -> {});
        };
        final var oracle = serial(input);
        assertEquals(List.of("A", "U", "B"), oracle.order);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(false);
            try {
                f.levelPhase();
                f.clean();
                assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
                f.before("A", "body-end", "U", "body-start");
                f.before("U", "body-end", "B", "body-start");
                assertEquals(oracle.state, f.state(), "the unknown callback must see B before B ticks");
            } finally {
                dump("native-unknown", f);
            }
        }
    }

    @Test
    void completeSerialChunksMustNotRegroupTheInterleavedLevelList() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.script("A", A, "test:unknown", () -> {});
            f.script("U", U, "test:unknown", () -> {});
            f.script("B", A.offset(1, 0, 0), "test:unknown", () -> {});
        };
        final var oracle = serial(input);
        assertEquals(List.of("A", "U", "B"), oracle.order);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(false);
            try {
                f.levelPhase();
                f.clean();
                assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
                assertEquals(oracle.order, f.bodyOrder(), "even all-serial fallback must retain global list order");
            } finally {
                dump("serial-chunk-regroup", f);
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [waves={0}]")
    @ValueSource(booleans = { false, true })
    void foreignTickerKeepsItsActualInsertionBetweenChunkTickers(boolean waves) {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.script("A", A, "test:unknown", () -> {});
            f.script("B", U, "test:unknown", () -> {});
            f.foreign("U", C, () -> {});
            f.script("C", B, "test:unknown", () -> {});
        };
        final var oracle = serial(input);
        assertEquals(List.of("A", "B", "U", "C"), oracle.order);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(waves);
            try {
                f.levelPhase();
                f.clean();
                assertEquals(oracle.order, f.bodyOrder(), "foreign position must come from Level membership, not a separate sequence domain");
            } finally {
                dump("foreign-waves=" + waves, f);
            }
        }
    }

    @Test
    void anAdapterRefusalMustStopTheContiguousWorkerPrefixBeforeLaterBodies() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.generic("A", A, () -> {});
            f.generic("B", A.offset(1, 0, 0), () -> {});
            f.generic("C", A.offset(2, 0, 0), () -> {});
        };
        final var oracle = serial(input);
        assertEquals(List.of("A", "B", "C"), oracle.order);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(false);
            f.register(source -> {
                final String id = f.names.get(source.getBlockPos());
                f.event(id, "check");
                return id.equals("B") ? BlockEntityTickAdapter.Decision.CALLBACK_REQUIRED : BlockEntityTickAdapter.Decision.ADMITTED;
            });
            try {
                f.levelPhase();
                f.clean();
                assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
                assertTrue(f.trace().stream().anyMatch(e -> e.id().equals("B") && e.action().equals("check") && e.worker()));
                f.before("B", "body-end", "C", "body-start");
                assertEquals(oracle.state, f.state());
            } finally {
                dump("late-adapter-refusal", f);
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [waves={0}]")
    @ValueSource(booleans = { false, true })
    void mixedNativeFurnaceGenericAndUnknownRespectTheSameFence(boolean waves) {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.script("N0", A, "minecraft:hopper", () -> {});
            f.furnace("F0", U, 1);
            f.furnace("F1", U.offset(3, 0, 0), 1);
            f.generic("G", B, () -> {});
            f.script("U", C, "test:unknown", () -> f.cells.get("U").set(f.cells.get("N1").get()));
            f.furnace("F2", U.offset(6, 0, 0), 1);
            f.furnace("F3", U.offset(9, 0, 0), 1);
            f.script("N1", A.offset(2, 0, 0), "minecraft:hopper", () -> {});
        };
        final var oracle = serial(input);
        assertEquals(List.of("N0", "F0", "F1", "G", "U", "F2", "F3", "N1"), oracle.order);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(waves);
            final var prepares = new AtomicInteger();
            f.register(new BlockEntityTickAdapter() {
                @Override
                public void prepare(net.minecraft.server.level.ServerLevel level) {
                    assertSame(f.serverThread, Thread.currentThread());
                    f.event("G", "prepare");
                    prepares.incrementAndGet();
                }

                @Override
                public Decision check(BlockEntity source) {
                    f.event("G", "check");
                    return Decision.ADMITTED;
                }
            });
            try {
                // This first uses the explicit real driver to isolate scheduling from collection provenance.
                f.engine.setBlockEntityAdmissionCacheEnabledForTesting(false);
                f.explicitPhase("N0", "F0", "F1", "G", "U", "F2", "F3", "N1");
                f.clean();
                assertEquals(waves ? 0 : 1, prepares.get(), "old v1 prepare semantics remain driver-specific");
                assertEquals(waves ? 4 : 0, f.engine.furnaceWorkMetrics().nativeCompletions());
                f.before("F0", "changed", "U", "body-start");
                f.before("F1", "changed", "U", "body-start");
                f.before("G", "body-end", "U", "body-start");
                if (waves) {
                    f.before("U", "body-end", "F2", "body-start");
                    f.before("U", "body-end", "F3", "body-start");
                }
                f.before("U", "body-end", "N1", "body-start");
                assertEquals(oracle.state, f.state());
            } finally {
                dump("mixed-waves=" + waves, f);
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [waves={0}]")
    @ValueSource(booleans = { false, true })
    void aSerialFenceCanActivateALaterChunkThatWasIneligibleAtPhaseCollection(boolean waves) {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.script("A", A, "minecraft:hopper", () -> {});
            f.script("U", U, "test:activation", () -> f.eligible.put(net.minecraft.world.level.ChunkPos.asLong(B), true));
            f.script("B", B, "minecraft:hopper", () -> {});
            f.eligible.put(net.minecraft.world.level.ChunkPos.asLong(B), false);
        };
        final var oracle = serial(input);
        assertEquals(List.of("A", "U", "B"), oracle.order);
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.enable(waves);
            try {
                f.levelPhase();
                f.clean();
                assertEquals(oracle.state, f.state(), "eligibility must be observed at the later ticker after the complete fence");
            } finally {
                dump("fence-activation-waves=" + waves, f);
            }
        }
    }

    static Snapshot serial(Consumer<CanonicalBlockEntityTestSupport> input) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            f.levelPhase();
            dump("vanilla-serial-oracle", f);
            return new Snapshot(f.bodyOrder(), f.state(), f.trace());
        }
    }

    static void dump(String label, CanonicalBlockEntityTestSupport f) {
        System.out.println("C1_TRACE " + label + " " + f.trace());
        System.out.println("C1_STATE " + label + " " + f.state());
        if (f.engine != null) System.out.println("C1_METRICS " + label + " " + f.engine.furnaceWorkMetrics()
                + " resources=" + f.engine.blockEntityResourceMetrics());
    }

    record Snapshot(List<String> order, Map<String, String> state, List<CanonicalBlockEntityTestSupport.Event> events) {}
}
