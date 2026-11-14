/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.get;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.invoke;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapter;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Refusal;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Validation;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual Level/ticker/kernel; no simulated verifier or test-issued proof. */
@Tag("canonical-be-contract")
@Timeout(120)
class CoreModProofIntegrationTest {
    static final BlockPos A = new BlockPos(20, 64, 20);
    static final BlockPos B = new BlockPos(68, 64, 20);

    static BlockEntity source(CanonicalBlockEntityTestSupport f, String id, BlockPos pos, boolean second, Runnable body) {
        try {
            final var m = GeneralBlockEntityAdapterIntegrationTest.class.getDeclaredMethod("unknown",
                    CanonicalBlockEntityTestSupport.class, String.class, BlockPos.class, boolean.class, Runnable.class);
            m.setAccessible(true);
            return (BlockEntity) invoke(m, null, f, id, pos, second, body);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    static BlockEntityAdapters.Descriptor descriptor(String id, String type) {
        return new BlockEntityAdapters.Descriptor(id, Set.of(type), Set.of(BlockEntityAdapters.Execution.values()));
    }

    static void register(CanonicalBlockEntityTestSupport f, BlockEntityAdapter adapter) {
        ParallelAdapterRuntime.initialize(f.server, r -> r.registerBlockEntityAdapter(1, descriptor("test:proof", "minecraft:chest"), adapter));
    }

    static BlockEntityAdapters.Request request(Object root, Validation result) {
        return new BlockEntityAdapters.Request(BlockEntityAdapters.Execution.RESOURCE_SCOPED,
                List.of(new BlockEntityAdapters.Resource(root, BlockEntityAdapters.Access.WRITE,
                        BlockEntityAdapters.Ownership.INSTANCE_LOCAL, () -> result)),
                List.of());
    }

    static void assertAssessment(CanonicalBlockEntityTestSupport f, String id, String state, Refusal refusal) {
        final var ticket = f.observedTickets.get(id);
        assertEquals(state, get(ticket.getClass(), ticket, "adapterProof").toString());
        assertEquals(refusal, get(ticket.getClass(), ticket, "adapterRefusal"));
    }

    @ParameterizedTest
    @EnumSource(Validation.class)
    void adapterValidationAlwaysPassesTheOriginalKernelRefusalGate(Validation validation) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            source(f, "M", A, false, () -> {});
            register(f, i -> request(i.subject(), validation));
            final var reason = validation == Validation.CURRENT ? Refusal.CORE_AUDIT_UNAVAILABLE
                    : validation == Validation.INVALID ? Refusal.INVALID_RESOURCE : Refusal.UNKNOWN_RESOURCE;
            f.onStart = id -> {
                assertAssessment(f, id, "SERIAL_ONLY", reason);
                assertNull(BlockEntityExecutionEngineTest.currentMode());
                assertFalse(BlockEntityResourceOwnership.Scope.active());
            };
            f.enable(true);
            f.levelPhase();
            assertEquals(1, f.cells.get("M").get());
            assertAssessment(f, "M", "RETIRED", reason);
            f.clean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void equalNamesAndArbitraryBackingRootsNeverBecomeCoreClaims(boolean copiedIdentity) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var root = new String("shared-handler");
            source(f, "M", A, false, () -> {});
            register(f, i -> request(copiedIdentity ? new String(root) : root, Validation.CURRENT));
            f.onStart = id -> assertAssessment(f, id, "SERIAL_ONLY", Refusal.CORE_AUDIT_UNAVAILABLE);
            f.enable(true);
            f.levelPhase();
            assertEquals(1, f.cells.get("M").get());
            assertAssessment(f, "M", "RETIRED", Refusal.CORE_AUDIT_UNAVAILABLE);
            f.clean();
        }
    }

    @Test
    void coreRecapturesActualSourceAfterAnAdversarialValidatorReturnsCurrent() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var original = source(f, "M", A, false, () -> {});
            final var replacement = new ChestBlockEntity(A, Blocks.CHEST.defaultBlockState());
            replacement.setLevel(f.level);
            register(f, i -> new BlockEntityAdapters.Request(BlockEntityAdapters.Execution.REGION_OWNER,
                    List.of(new BlockEntityAdapters.Resource(i.subject(), BlockEntityAdapters.Access.WRITE,
                            BlockEntityAdapters.Ownership.INSTANCE_LOCAL, () -> {
                                // Deliberately violate the callback contract. Core must not trust CURRENT.
                                f.world.blockEntities.put(A, replacement);
                                return Validation.CURRENT;
                            })),
                    List.of()));
            f.onStart = id -> assertAssessment(f, id, "SERIAL_ONLY", Refusal.INVALID_BINDING);
            f.enable(false);
            f.levelPhase();
            assertEquals(1, f.cells.get("M").get(), "complete original serial wrapper is invoked once");
            assertTrue(original != f.world.blockEntities.get(A));
            assertAssessment(f, "M", "RETIRED", Refusal.INVALID_BINDING);
            f.clean();
        }
    }
}
