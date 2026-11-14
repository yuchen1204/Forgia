/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.await;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.get;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.invoke;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapter;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Access;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Execution;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Ownership;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Refusal;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Validation;
import net.neoforged.neoforge.server.threading.api.adapter.DedicatedBlockEntityAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real Level membership/wrappers, original kernel, physical barriers and Fixed/Persistent pools. Unknown
 * test BE bodies use exact-bound Minecraft tickers; they are never admitted by a mock or a test proof.
 * Existing original Hopper/Furnace proofs supply all worker authority and overlap observations.
 */
@Tag("canonical-be-contract")
@Timeout(120)
class GeneralBlockEntityAdapterIntegrationTest {
    private static final BlockPos A = new BlockPos(20, 64, 20);
    private static final BlockPos B = new BlockPos(68, 64, 20);
    private static final BlockPos U = new BlockPos(116, 64, 20);

    private static final class Probe extends BlockEntity {
        final CanonicalBlockEntityTestSupport fixture;
        final String id;
        final Runnable action;

        Probe(CanonicalBlockEntityTestSupport fixture, String id, BlockPos pos, boolean second, Runnable action) {
            super(second ? BlockEntityType.BEACON : BlockEntityType.CHEST, pos,
                    second ? Blocks.BEACON.defaultBlockState() : Blocks.CHEST.defaultBlockState());
            this.fixture = fixture;
            this.id = id;
            this.action = action;
            this.setLevel(fixture.level);
        }

        void body() {
            try {
                final var start = CanonicalBlockEntityTestSupport.class.getDeclaredMethod("start", String.class);
                start.setAccessible(true);
                invoke(start, this.fixture, this.id);
                this.fixture.cells.get(this.id).incrementAndGet();
                this.action.run();
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            } finally {
                this.fixture.event(this.id, "body-end");
                this.fixture.currentBody.remove();
            }
        }
    }

    private static Probe unknown(CanonicalBlockEntityTestSupport f, String id, BlockPos pos, boolean second, Runnable action) {
        final var source = new Probe(f, id, pos, second, action);
        f.world.blockEntities.put(pos, source);
        f.world.states.put(pos, source.getBlockState());
        f.names.put(pos, id);
        f.cells.put(id, new AtomicInteger());
        try {
            final var bound = CanonicalBlockEntityTestSupport.class.getDeclaredMethod("bound", String.class, BlockEntity.class, BlockEntityTicker.class);
            bound.setAccessible(true);
            invoke(bound, f, id, source, (BlockEntityTicker<Probe>) (level, at, state, entity) -> entity.body());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        return source;
    }

    private static BlockEntityAdapters.Descriptor descriptor(String id, String type) {
        return new BlockEntityAdapters.Descriptor(id, Set.of(type), Set.of(Execution.REGION_OWNER, Execution.SERIAL));
    }

    private static BlockEntityAdapters.Resource resource(Object identity, Access access, BlockEntityAdapters.Validator validator) {
        return new BlockEntityAdapters.Resource(identity, access, Ownership.INSTANCE_LOCAL, validator);
    }

    private static BlockEntityAdapters.Request request(Object identity) {
        return new BlockEntityAdapters.Request(Execution.REGION_OWNER, List.of(resource(identity, Access.WRITE, () -> Validation.CURRENT)), List.of());
    }

    private static ParallelApi.Server api(CanonicalBlockEntityTestSupport f) {
        return ParallelApi.forServer(f.server).orElseThrow();
    }

    private static void register(CanonicalBlockEntityTestSupport f, BlockEntityAdapter first, BlockEntityAdapter second) {
        ParallelAdapterRuntime.initialize(f.server, registrar -> {
            registrar.registerBlockEntityAdapter(1, descriptor("test:a", "minecraft:chest"), first);
            if (second != null) registrar.registerBlockEntityAdapter(1, descriptor("test:b", "minecraft:beacon"), second);
        });
    }

    private static void assertSerialInvocation(CanonicalBlockEntityTestSupport f) {
        assertSame(f.serverThread, Thread.currentThread());
        assertNull(BlockEntityExecutionEngineTest.currentMode());
        try {
            final var method = Class.forName("net.neoforged.neoforge.server.threading.region.BlockEntityExecutionEngine").getDeclaredMethod("isCanonicalSerialInvocation");
            method.setAccessible(true);
            assertEquals(true, invoke(method, null));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void noSessionReferences(CanonicalBlockEntityTestSupport f) {
        final var runtime = api(f);
        final var registry = get(runtime.getClass(), runtime, "blockEntityAdapters");
        assertEquals(0, get(registry.getClass(), registry, "sessions"));
        f.clean();
    }

    @Test
    void twoAdaptersShareTheOriginalPhaseWithRealNativeAndFurnaceOverlapAndExactlyOnceFences() {
        final Consumer<CanonicalBlockEntityTestSupport> input = f -> {
            f.script("N0", A, "minecraft:hopper", () -> {});
            f.script("N1", B, "minecraft:hopper", () -> {});
            f.furnace("F0", A.east(3), 1);
            f.furnace("F1", B.east(3), 1);
            unknown(f, "A", U, false, () -> assertSerialInvocation(f));
            unknown(f, "B", U.east(3), true, () -> assertSerialInvocation(f));
            f.foreign("U", U.east(6), () -> {});
            f.script("N2", A.east(6), "minecraft:hopper", () -> {});
            f.script("N3", B.east(6), "minecraft:hopper", () -> {});
        };
        // The oracle executes the original unknown bodies without an engine, so its body assertion must
        // only apply to the parallel fixture. Build the serial state separately with the same mutations.
        final Consumer<CanonicalBlockEntityTestSupport> oracleInput = f -> {
            f.script("N0", A, "minecraft:hopper", () -> {});
            f.script("N1", B, "minecraft:hopper", () -> {});
            f.furnace("F0", A.east(3), 1);
            f.furnace("F1", B.east(3), 1);
            unknown(f, "A", U, false, () -> {});
            unknown(f, "B", U.east(3), true, () -> {});
            f.foreign("U", U.east(6), () -> {});
            f.script("N2", A.east(6), "minecraft:hopper", () -> {});
            f.script("N3", B.east(6), "minecraft:hopper", () -> {});
        };
        final var oracle = CanonicalBlockEntitySequenceTest.serial(oracleInput).state();
        try (var f = new CanonicalBlockEntityTestSupport()) {
            input.accept(f);
            final var phases = new java.util.ArrayList<BlockEntityAdapters.Phase>();
            final var closes = new AtomicInteger();
            final var effect = new AtomicInteger();
            final BlockEntityAdapter general = new BlockEntityAdapter() {
                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation) {
                    assertSerialInvocation(f);
                    assertEquals(1, effect.get());
                    assertEquals(1, f.cells.get("N0").get());
                    assertEquals(1, f.furnaces.get("F0").getItem(2).getCount());
                    assertEquals(0, f.cells.get("N2").get());
                    phases.add(invocation.phase());
                    f.event(((Probe) invocation.subject()).id, "inspect");
                    return request(invocation.subject());
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    assertTrue(successful);
                    assertNull(BlockEntityExecutionEngineTest.currentMode());
                    assertEquals(1, ((Set<?>) get(f.engine.getClass(), f.engine, "activeTickBatches")).size());
                    closes.incrementAndGet();
                }
            };
            register(f, general, new DedicatedBlockEntityAdapter() {
                @Override
                public Audit audit() {
                    return new Audit("test/full-closure-audit", "untrusted-test-metadata");
                }

                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation) {
                    return general.inspect(invocation);
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    general.phaseClosed(phase, successful);
                }
            });
            f.enable(true);
            final var nativeEntered = new CountDownLatch(2);
            final var furnaceEntered = new CountDownLatch(2);
            final var nativeActive = new AtomicInteger();
            final var furnaceActive = new AtomicInteger();
            final var nativeMaximum = new AtomicInteger();
            final var furnaceMaximum = new AtomicInteger();
            f.onStart = id -> {
                if (id.equals("N0")) assertTrue(f.engine.queueServerTaskInCurrentBatch(effect::incrementAndGet));
                if (!Set.of("N0", "N1", "F0", "F1").contains(id)) return;
                final boolean nativeUnit = id.startsWith("N");
                final var active = nativeUnit ? nativeActive : furnaceActive;
                final var maximum = nativeUnit ? nativeMaximum : furnaceMaximum;
                final var entered = nativeUnit ? nativeEntered : furnaceEntered;
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                    await(entered);
                } finally {
                    active.decrementAndGet();
                }
            };
            f.levelPhase();
            assertEquals(2, nativeMaximum.get());
            assertEquals(2, furnaceMaximum.get());
            assertEquals(6, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(2, closes.get());
            assertSame(phases.get(0), phases.get(1));
            assertSame(phases.get(0), api(f).lastBlockEntityAdapterPhase().orElseThrow().phase());
            assertEquals(1, f.observedBatches.size());
            assertEquals(9, f.observedTickets.size());
            assertEquals(oracle, f.state());
            f.before("F1", "changed", "A", "inspect");
            f.before("A", "inspect", "A", "body-start");
            f.before("A", "body-end", "B", "inspect");
            f.before("B", "body-end", "U", "body-start");
            f.before("U", "body-end", "N2", "body-start");
            for (final var status : api(f).blockEntityAdapters()) {
                assertEquals(1, status.inspections());
                assertEquals(1L, status.refusals().get(Refusal.CORE_AUDIT_UNAVAILABLE));
            }
            noSessionReferences(f);
            f.probes();
            System.out.println("C5_ONE_PHASE nativeOverlap=" + nativeMaximum.get() + " furnaceOverlap=" + furnaceMaximum.get()
                    + " batches=" + f.observedBatches.size() + " tickets=" + f.observedTickets.size());
        }
    }

    @Test
    void declaredCrossAdapterAliasesUseExistingIdentityPlannerWithoutGrantingAnyAuthority() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final Object x = new Object() {
                @Override
                public boolean equals(Object other) {
                    throw new AssertionError("user equality must not be called");
                }

                @Override
                public int hashCode() {
                    throw new AssertionError("user hash must not be called");
                }
            };
            final Object y = new Object();
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            unknown(f, "B", B, true, () -> assertSerialInvocation(f));
            unknown(f, "C", U, false, () -> assertSerialInvocation(f));
            final BlockEntityAdapter adapter = invocation -> {
                final String id = ((Probe) invocation.subject()).id;
                final var resources = id.equals("A") ? List.of(x) : id.equals("B") ? List.of(x, y) : List.of(y);
                return new BlockEntityAdapters.Request(Execution.REGION_OWNER,
                        resources.stream().map(root -> resource(root, Access.WRITE, () -> Validation.CURRENT)).toList(), List.of());
            };
            register(f, adapter, adapter);
            f.enable(false);
            f.levelPhase();
            assertEquals(List.of(List.of(0L), List.of(1L), List.of(2L)), api(f).lastBlockEntityAdapterPhase().orElseThrow().declaredWaves());
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(Map.of("A", "1", "B", "1", "C", "1"), f.state());
            noSessionReferences(f);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void readReadIsIndependentAndADeclaredWriterCreatesADirectedConflict(boolean writer) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var root = new Object();
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            unknown(f, "B", B, true, () -> assertSerialInvocation(f));
            register(f, i -> new BlockEntityAdapters.Request(Execution.REGION_OWNER, List.of(resource(root, writer ? Access.WRITE : Access.READ, () -> Validation.CURRENT)), List.of()),
                    i -> new BlockEntityAdapters.Request(Execution.REGION_OWNER, List.of(resource(root, Access.READ, () -> Validation.CURRENT)), List.of()));
            f.enable(false);
            f.levelPhase();
            assertEquals(writer ? List.of(List.of(0L), List.of(1L)) : List.of(List.of(0L, 1L)), api(f).lastBlockEntityAdapterPhase().orElseThrow().declaredWaves());
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            noSessionReferences(f);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void unknownResourcesAndSharedWritesRefuseWithoutProbingAnyProvider(boolean sharedWrite) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            register(f, i -> new BlockEntityAdapters.Request(Execution.REGION_OWNER, List.of(new BlockEntityAdapters.Resource(new Object(), Access.WRITE,
                    sharedWrite ? Ownership.SHARED_READ : Ownership.UNKNOWN, () -> {
                        throw new AssertionError("no provider probe");
                    })), List.of()), null);
            f.enable(false);
            f.levelPhase();
            assertEquals(1, f.cells.get("A").get());
            assertEquals(1L, api(f).blockEntityAdapters().getFirst().refusals().get(Refusal.UNKNOWN_RESOURCE));
            noSessionReferences(f);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Validation.class, names = { "INVALID", "UNKNOWN" })
    void dependencyAndLifecycleVersionsAreFreshAfterThePreviousFullFence(Validation result) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var revision = new AtomicInteger();
            final var validations = new AtomicInteger();
            unknown(f, "A", A, false, revision::incrementAndGet);
            unknown(f, "B", B, true, () -> assertSerialInvocation(f));
            register(f, i -> BlockEntityAdapters.Request.serial(), i -> new BlockEntityAdapters.Request(Execution.REGION_OWNER,
                    List.of(resource(i.subject(), Access.WRITE, () -> Validation.CURRENT)), List.of(new BlockEntityAdapters.Dependency(() -> {
                        assertEquals(1, revision.get());
                        validations.incrementAndGet();
                        return result;
                    }))));
            f.enable(false);
            f.levelPhase();
            assertEquals(1, validations.get());
            final var reason = result == Validation.INVALID ? Refusal.INVALID_DEPENDENCY : Refusal.UNKNOWN_DEPENDENCY;
            assertEquals(1L, api(f).blockEntityAdapters().get(1).refusals().get(reason));
            assertEquals(1, f.cells.get("B").get());
            noSessionReferences(f);
        }
    }

    @Test
    void duplicateValidatorOccurrencesAndRepeatedPhasesNeverReuseSuccessfulChecks() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var validations = new AtomicInteger();
            final var inspections = new AtomicInteger();
            final var borrowed = new AtomicReference<BlockEntityAdapters.Invocation>();
            final var dependency = new BlockEntityAdapters.Dependency(() -> {
                validations.incrementAndGet();
                return Validation.CURRENT;
            });
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            register(f, i -> {
                borrowed.set(i);
                inspections.incrementAndGet();
                final var resource = resource(i.subject(), Access.WRITE, () -> {
                    validations.incrementAndGet();
                    return Validation.CURRENT;
                });
                return new BlockEntityAdapters.Request(Execution.REGION_OWNER, List.of(resource, resource), List.of(dependency, dependency));
            }, null);
            f.enable(false);
            for (int phase = 1; phase <= 100; ++phase) {
                f.levelPhase();
                assertEquals(phase, inspections.get());
                assertEquals(phase * 4, validations.get());
                assertEquals(phase, f.cells.get("A").get());
                assertThrows(IllegalStateException.class, borrowed.get()::subject);
                assertThrows(IllegalStateException.class, borrowed.get()::phase);
                assertNull(get(borrowed.get().getClass(), borrowed.get(), "subject"));
                assertEquals(phase, api(f).lastBlockEntityAdapterPhase().orElseThrow().phase().sequence());
                noSessionReferences(f);
            }
        }
    }

    @Test
    void invalidatingTheNextAdapterFromACompleteBodyPreservesItsSerialBodyAndSkipsInspection() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var next = new AtomicReference<BlockEntityAdapters.Registration>();
            unknown(f, "A", A, false, () -> next.get().invalidate());
            unknown(f, "B", B, true, () -> assertSerialInvocation(f));
            ParallelAdapterRuntime.initialize(f.server, registrar -> {
                registrar.registerBlockEntityAdapter(1, descriptor("test:a", "minecraft:chest"), i -> BlockEntityAdapters.Request.serial());
                next.set(registrar.registerBlockEntityAdapter(1, descriptor("test:b", "minecraft:beacon"), i -> {
                    throw new AssertionError("invalidated callback");
                }));
            });
            f.enable(false);
            f.levelPhase();
            assertEquals(Map.of("A", "1", "B", "1"), f.state());
            final var status = api(f).blockEntityAdapters().get(1);
            assertEquals(0, status.inspections());
            assertEquals(1L, status.refusals().get(Refusal.INVALIDATED));
            noSessionReferences(f);
        }
    }

    @Test
    void unregisterDuringInspectionDefersCallbackRetirementUntilPhaseCleanup() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var handle = new AtomicReference<BlockEntityAdapters.Registration>();
            final var closed = new AtomicInteger();
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            ParallelAdapterRuntime.initialize(f.server, registrar -> handle.set(registrar.registerBlockEntityAdapter(1, descriptor("test:a", "minecraft:chest"), new BlockEntityAdapter() {
                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation) {
                    handle.get().close();
                    return request(invocation.subject());
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    assertTrue(successful);
                    closed.incrementAndGet();
                }
            })));
            f.enable(false);
            f.levelPhase();
            assertEquals(1, closed.get());
            assertEquals(1, f.cells.get("A").get());
            final var registry = get(api(f).getClass(), api(f), "blockEntityAdapters");
            final var entries = (Map<?, ?>) get(registry.getClass(), registry, "entries");
            final var entry = entries.get("test:a");
            assertNull(get(entry.getClass(), entry, "adapter"));
            f.levelPhase();
            assertEquals(1, closed.get());
            assertEquals(2, f.cells.get("A").get());
            noSessionReferences(f);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Validation.class, names = { "INVALID", "UNKNOWN" })
    void invalidOrUnknownAttachmentCannotBeConvertedIntoAProof(Validation validation) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            register(f, i -> new BlockEntityAdapters.Request(Execution.REGION_OWNER,
                    List.of(resource(i.subject(), Access.WRITE, () -> validation)), List.of()), null);
            f.enable(false);
            f.levelPhase();
            assertEquals(1L, api(f).blockEntityAdapters().getFirst().refusals().get(validation == Validation.INVALID ? Refusal.INVALID_RESOURCE : Refusal.UNKNOWN_RESOURCE));
            assertEquals(1, f.cells.get("A").get());
            noSessionReferences(f);
        }
    }

    enum Fault {
        INSPECT, RESOURCE, DEPENDENCY, NULL_RESULT, SCHEMA
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void callbackFailuresAbortBeforeTheBodyAndSuffixWithPriorEffectsCommitted(Fault fault) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var failure = new IllegalStateException("C5 callback failure");
            final var committed = new AtomicInteger();
            final var closed = new AtomicInteger();
            f.script("N", A, "minecraft:hopper", () -> assertTrue(f.engine.queueServerTaskInCurrentBatch(committed::incrementAndGet)));
            unknown(f, "A", B, false, () -> {});
            f.script("suffix", U, "minecraft:hopper", () -> {});
            register(f, new BlockEntityAdapter() {
                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                    assertSerialInvocation(f);
                    assertEquals(1, committed.get());
                    if (fault == Fault.INSPECT) throw failure;
                    if (fault == Fault.NULL_RESULT) return null;
                    final var resource = resource(i.subject(), Access.WRITE, () -> {
                        if (fault == Fault.RESOURCE) throw failure;
                        return Validation.CURRENT;
                    });
                    return new BlockEntityAdapters.Request(fault == Fault.SCHEMA ? Execution.RESOURCE_SCOPED : Execution.REGION_OWNER,
                            List.of(resource), List.of(new BlockEntityAdapters.Dependency(() -> {
                                if (fault == Fault.DEPENDENCY) throw failure;
                                return Validation.CURRENT;
                            })));
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    assertFalse(successful);
                    closed.incrementAndGet();
                }
            }, null);
            f.enable(false);
            final var observed = assertThrows(RuntimeException.class, f::levelPhase);
            if (Set.of(Fault.INSPECT, Fault.RESOURCE, Fault.DEPENDENCY).contains(fault)) assertSame(failure, observed);
            assertEquals(1, closed.get());
            assertEquals(1, committed.get());
            assertEquals(1, f.cells.get("N").get());
            assertEquals(0, f.cells.get("A").get());
            assertEquals(0, f.cells.get("suffix").get());
            assertEquals(1L, api(f).blockEntityAdapters().getFirst().refusals().get(Refusal.CALLBACK_FAILED));
            assertFalse(api(f).lastBlockEntityAdapterPhase().orElseThrow().successful());
            noSessionReferences(f);
            f.probes();
        }
    }

    @Test
    void aPartiallyEffectiveOriginalBodyIsNeverReplayedAndTheNextPhaseCanRecover() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var first = new AtomicInteger();
            final var closed = new AtomicInteger();
            unknown(f, "A", A, false, () -> {
                if (first.getAndIncrement() == 0) throw new IllegalStateException("partial effect remains");
            });
            unknown(f, "B", B, true, () -> assertSerialInvocation(f));
            register(f, new BlockEntityAdapter() {
                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                    return request(i.subject());
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    closed.incrementAndGet();
                }
            }, i -> request(i.subject()));
            f.enable(false);
            assertThrows(RuntimeException.class, f::levelPhase);
            assertEquals(1, f.cells.get("A").get());
            assertEquals(0, f.cells.get("B").get());
            assertEquals(1, first.get());
            noSessionReferences(f);
            f.levelPhase();
            assertEquals(2, f.cells.get("A").get());
            assertEquals(1, f.cells.get("B").get());
            assertEquals(2, first.get());
            assertEquals(2, closed.get());
            noSessionReferences(f);
        }
    }

    @Test
    void oneCleanupFailureStillClosesEveryAdapterAndCoreAuthorityWithoutReplay() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var calls = new AtomicInteger();
            final var failure = new IllegalStateException("phase cleanup failure");
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            unknown(f, "B", B, true, () -> assertSerialInvocation(f));
            f.script("N", U, "minecraft:hopper", () -> {});
            final BlockEntityAdapter first = new BlockEntityAdapter() {
                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                    return request(i.subject());
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    assertEquals(1, f.cells.get("N").get());
                    if (calls.incrementAndGet() == 1) throw failure;
                }
            };
            register(f, first, new BlockEntityAdapter() {
                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                    return request(i.subject());
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    calls.incrementAndGet();
                }
            });
            f.enable(false);
            assertSame(failure, assertThrows(IllegalStateException.class, f::levelPhase));
            assertEquals(2, calls.get());
            assertEquals(Map.of("A", "1", "B", "1", "N", "1"), f.state());
            assertFalse(api(f).lastBlockEntityAdapterPhase().orElseThrow().successful());
            noSessionReferences(f);
            f.probes();
        }
    }

    @Test
    void sourceReplacementCannotBePassedToAnOldBindingInspection() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            final var replacement = new Probe(f, "replacement", A, false, () -> {});
            f.world.blockEntities.put(A, replacement);
            register(f, i -> {
                throw new AssertionError("the replacement cannot inherit the original ticker");
            }, null);
            f.enable(false);
            f.levelPhase();
            assertEquals(0, api(f).blockEntityAdapters().getFirst().inspections());
            assertEquals(1L, api(f).blockEntityAdapters().getFirst().refusals().get(Refusal.INVALID_BINDING));
            assertEquals(1, f.cells.get("A").get(), "the original wrapper still follows its full vanilla serial path");
            noSessionReferences(f);
        }
    }

    @Test
    void borrowedViewsAreUnavailableOnOtherThreadsAndRetireTheirSubjectReference() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var saved = new AtomicReference<BlockEntityAdapters.Invocation>();
            final var problem = new AtomicReference<Throwable>();
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            register(f, i -> {
                saved.set(i);
                final var other = new Thread(() -> {
                    try {
                        i.subject();
                    } catch (Throwable failure) {
                        problem.set(failure);
                    }
                });
                other.start();
                try {
                    other.join(10000);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                assertFalse(other.isAlive());
                assertTrue(problem.get() instanceof IllegalStateException);
                return request(i.subject());
            }, null);
            f.enable(false);
            f.levelPhase();
            assertThrows(IllegalStateException.class, saved.get()::ordinal);
            assertThrows(IllegalStateException.class, saved.get()::type);
            assertNull(get(saved.get().getClass(), saved.get(), "subject"));
            noSessionReferences(f);
        }
    }

    @Test
    void perUnitBudgetRefusesBeforeEnteringAnyResourceValidator() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            register(f, i -> new BlockEntityAdapters.Request(Execution.REGION_OWNER,
                    java.util.Collections.nCopies(513, resource(i.subject(), Access.WRITE, () -> {
                        throw new AssertionError("over-budget callback");
                    })), List.of()), null);
            f.enable(false);
            f.levelPhase();
            assertEquals(1L, api(f).blockEntityAdapters().getFirst().refusals().get(Refusal.BUDGET_EXCEEDED));
            assertEquals(1, f.cells.get("A").get());
            noSessionReferences(f);
        }
    }

    @Test
    void phaseBudgetCountsEveryLogicalOccurrenceAcrossAllSerialFences() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var validated = new AtomicInteger();
            for (int i = 0; i < 257; ++i) unknown(f, "A" + i, A.offset(i % 16, 0, i / 16), false, () -> assertSerialInvocation(f));
            register(f, i -> new BlockEntityAdapters.Request(Execution.REGION_OWNER,
                    java.util.Collections.nCopies(512, resource(i.subject(), Access.READ, () -> {
                        validated.incrementAndGet();
                        return Validation.CURRENT;
                    })), List.of()), null);
            f.enable(false);
            f.levelPhase();
            assertEquals(131072, validated.get());
            final var status = api(f).blockEntityAdapters().getFirst();
            assertEquals(257, status.inspections());
            assertEquals(256L, status.refusals().get(Refusal.CORE_AUDIT_UNAVAILABLE));
            assertEquals(1L, status.refusals().get(Refusal.BUDGET_EXCEEDED));
            assertTrue(f.cells.values().stream().allMatch(count -> count.get() == 1));
            noSessionReferences(f);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void unloadOrShutdownWaitsForTheSamePublishedPhaseAndRejectsCallbackSelfWait(boolean shutdown) throws InterruptedException {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var finished = new CountDownLatch(1);
            final var failure = new AtomicReference<Throwable>();
            final var cleanup = new AtomicReference<Thread>();
            final var closed = new AtomicInteger();
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            register(f, new BlockEntityAdapter() {
                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                    assertSerialInvocation(f);
                    final var thread = new Thread(() -> {
                        try {
                            if (shutdown) f.engine.shutdown();
                            else f.engine.clearLevelRegionTasks(f.level);
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            finished.countDown();
                        }
                    }, "C5-real-lifecycle-cleanup");
                    cleanup.set(thread);
                    thread.start();
                    await(f.lifecycleWriteHeld);
                    assertEquals(1, finished.getCount());
                    if (shutdown) assertThrows(IllegalStateException.class, f.engine::shutdown);
                    else assertThrows(IllegalStateException.class, () -> f.engine.clearLevelRegionTasks(f.level));
                    return BlockEntityAdapters.Request.serial();
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    assertEquals(1, finished.getCount());
                    closed.incrementAndGet();
                }
            }, null);
            f.enable(false);
            f.levelPhase();
            await(finished);
            cleanup.get().join(10000);
            assertFalse(cleanup.get().isAlive());
            assertNull(failure.get());
            assertEquals(1, f.cells.get("A").get());
            assertEquals(1, closed.get());
            noSessionReferences(f);
        }
    }

    @Test
    void anUnregisteredUnknownBodyRemainsSerialWhileCorePrefixesContinue() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.script("N", A, "minecraft:hopper", () -> {});
            unknown(f, "unknown", B, true, () -> assertSerialInvocation(f));
            register(f, i -> {
                throw new AssertionError("unrelated adapter must not inspect an unknown type");
            }, null);
            f.enable(false);
            f.levelPhase();
            assertEquals(1, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(0, api(f).blockEntityAdapters().getFirst().inspections());
            assertEquals(1, f.cells.get("unknown").get());
            noSessionReferences(f);
        }
    }

    @Test
    void stoppingTheApiDuringInspectionRetainsCallbacksUntilTheOriginalPhaseCloses() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var closed = new AtomicInteger();
            final var handle = new AtomicReference<BlockEntityAdapters.Registration>();
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            unknown(f, "B", B, true, () -> assertSerialInvocation(f));
            ParallelAdapterRuntime.initialize(f.server, registrar -> {
                handle.set(registrar.registerBlockEntityAdapter(1, descriptor("test:a", "minecraft:chest"), new BlockEntityAdapter() {
                    @Override
                    public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                        ParallelAdapterRuntime.close(f.server);
                        return request(i.subject());
                    }

                    @Override
                    public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                        assertTrue(successful);
                        closed.incrementAndGet();
                    }
                }));
                registrar.registerBlockEntityAdapter(1, descriptor("test:b", "minecraft:beacon"), i -> {
                    throw new AssertionError("closed service cannot inspect a later body");
                });
            });
            final var retained = api(f);
            final var registry = get(retained.getClass(), retained, "blockEntityAdapters");
            f.enable(false);
            f.levelPhase();
            assertEquals(1, closed.get());
            assertEquals(Map.of("A", "1", "B", "1"), f.state());
            assertTrue(ParallelApi.forServer(f.server).isEmpty());
            assertThrows(IllegalStateException.class, retained::blockEntityAdapters);
            assertEquals(BlockEntityAdapters.State.CLOSED, handle.get().state());
            assertEquals(0, get(registry.getClass(), registry, "sessions"));
            assertNull(get(registry.getClass(), registry, "coordinator"));
            for (final var entry : ((Map<?, ?>) get(registry.getClass(), registry, "entries")).values())
                assertNull(get(entry.getClass(), entry, "adapter"));
            f.clean();
            f.probes();
        }
    }

    @Test
    void inspectionAndMultipleCleanupFailuresArePreservedWhileEveryReferenceIsRetired() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var primary = new IllegalStateException("primary inspection failure");
            final var cleanupA = new IllegalStateException("cleanup A");
            final var cleanupB = new IllegalStateException("cleanup B");
            final var cleanupCalls = new AtomicInteger();
            unknown(f, "A", A, false, () -> assertSerialInvocation(f));
            unknown(f, "B", B, true, () -> assertSerialInvocation(f));
            unknown(f, "C", U, false, () -> {});
            register(f, new BlockEntityAdapter() {
                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                    if (((Probe) i.subject()).id.equals("C")) throw primary;
                    return request(i.subject());
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    assertFalse(successful);
                    cleanupCalls.incrementAndGet();
                    throw cleanupA;
                }
            }, new BlockEntityAdapter() {
                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                    return request(i.subject());
                }

                @Override
                public void phaseClosed(BlockEntityAdapters.Phase phase, boolean successful) {
                    assertFalse(successful);
                    cleanupCalls.incrementAndGet();
                    throw cleanupB;
                }
            });
            f.enable(false);
            assertSame(primary, assertThrows(IllegalStateException.class, f::levelPhase));
            assertEquals(2, cleanupCalls.get());
            assertEquals(List.of(cleanupA), List.of(primary.getSuppressed()));
            assertEquals(List.of(cleanupB), List.of(cleanupA.getSuppressed()));
            assertEquals(Map.of("A", "1", "B", "1", "C", "0"), f.state());
            noSessionReferences(f);
            f.probes();
        }
    }
}
