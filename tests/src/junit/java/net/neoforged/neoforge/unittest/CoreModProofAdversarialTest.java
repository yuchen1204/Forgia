/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.await;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.get;
import static net.neoforged.neoforge.unittest.CoreModProofIntegrationTest.A;
import static net.neoforged.neoforge.unittest.CoreModProofIntegrationTest.B;
import static net.neoforged.neoforge.unittest.CoreModProofIntegrationTest.assertAssessment;
import static net.neoforged.neoforge.unittest.CoreModProofIntegrationTest.descriptor;
import static net.neoforged.neoforge.unittest.CoreModProofIntegrationTest.register;
import static net.neoforged.neoforge.unittest.CoreModProofIntegrationTest.request;
import static net.neoforged.neoforge.unittest.CoreModProofIntegrationTest.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.neoforge.server.threading.BlockEntityAdapterRegistry;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapter;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Access;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Refusal;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Validation;
import net.neoforged.neoforge.server.threading.api.adapter.DedicatedBlockEntityAdapter;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Adversarial calls into the production registry, actual Level loop, kernel and lifecycle boundary. */
@Tag("canonical-be-contract")
@Timeout(120)
class CoreModProofAdversarialTest {
    @Test
    void dedicatedAuditCannotPublishARegistrationAfterReentrantFreeze() {
        final var registry = new BlockEntityAdapterRegistry();
        try {
            assertThrows(IllegalStateException.class, () -> registry.register(1, descriptor("test:frozen", "test:machine"), new DedicatedBlockEntityAdapter() {
                @Override
                public Audit audit() {
                    registry.freeze();
                    return new Audit("test/reentrant-freeze", "1");
                }

                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                    return BlockEntityAdapters.Request.serial();
                }
            }));
            assertEquals(0, registry.size());
        } finally {
            registry.close();
        }
    }

    enum Loss {
        INVALIDATE, UNREGISTER, SERVER_CLOSE
    }

    @ParameterizedTest
    @EnumSource(Loss.class)
    void lifecycleLossDuringProofCreationPreservesExactlyOneCompleteSerialBody(Loss loss) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var handle = new AtomicReference<BlockEntityAdapters.Registration>();
            final var cleanup = new AtomicInteger();
            source(f, "M", A, false, () -> {});
            ParallelAdapterRuntime.initialize(f.server, r -> handle.set(r.registerBlockEntityAdapter(1,
                    descriptor("test:proof", "minecraft:chest"), new BlockEntityAdapter() {
                        @Override
                        public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation i) {
                            return new BlockEntityAdapters.Request(BlockEntityAdapters.Execution.RESOURCE_SCOPED,
                                    List.of(new BlockEntityAdapters.Resource(i.subject(), Access.WRITE, BlockEntityAdapters.Ownership.INSTANCE_LOCAL, () -> {
                                        if (loss == Loss.INVALIDATE) handle.get().invalidate();
                                        else if (loss == Loss.UNREGISTER) handle.get().close();
                                        else ParallelAdapterRuntime.close(f.server);
                                        return Validation.CURRENT;
                                    })), List.of());
                        }

                        @Override
                        public void phaseClosed(BlockEntityAdapters.Phase p, boolean success) {
                            cleanup.incrementAndGet();
                        }
                    })));
            f.onStart = id -> assertAssessment(f, id, "SERIAL_ONLY", Refusal.INVALIDATED);
            f.enable(true);
            f.levelPhase();
            assertEquals(1, f.cells.get("M").get());
            assertEquals(1, cleanup.get());
            assertAssessment(f, "M", "RETIRED", Refusal.INVALIDATED);
            f.clean();
        }
    }

    @ParameterizedTest
    @CsvSource({ "READ,READ", "READ,WRITE", "WRITE,READ", "WRITE,WRITE" })
    void twoAdaptersWithOneSharedRootRemainCompleteOrderedFences(Access a, Access b) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final Object root = new Object();
            source(f, "A", A, false, () -> assertFalse(BlockEntityResourceOwnership.Scope.active()));
            source(f, "B", B, true, () -> assertFalse(BlockEntityResourceOwnership.Scope.active()));
            ParallelAdapterRuntime.initialize(f.server, r -> {
                r.registerBlockEntityAdapter(1, descriptor("test:a", "minecraft:chest"), i -> new BlockEntityAdapters.Request(BlockEntityAdapters.Execution.RESOURCE_SCOPED,
                        List.of(new BlockEntityAdapters.Resource(root, a, BlockEntityAdapters.Ownership.INSTANCE_LOCAL, () -> Validation.CURRENT)), List.of()));
                r.registerBlockEntityAdapter(1, descriptor("test:b", "minecraft:beacon"), i -> new BlockEntityAdapters.Request(BlockEntityAdapters.Execution.RESOURCE_SCOPED,
                        List.of(new BlockEntityAdapters.Resource(root, b, BlockEntityAdapters.Ownership.INSTANCE_LOCAL, () -> Validation.CURRENT)), List.of()));
            });
            f.onStart = id -> assertAssessment(f, id, "SERIAL_ONLY", Refusal.CORE_AUDIT_UNAVAILABLE);
            f.enable(true);
            f.levelPhase();
            assertEquals(List.of("A", "B"), f.bodyOrder());
            assertEquals(1, f.cells.get("A").get());
            assertEquals(1, f.cells.get("B").get());
            final var waves = ParallelApi.forServer(f.server).orElseThrow().lastBlockEntityAdapterPhase().orElseThrow().declaredWaves();
            assertEquals(a == Access.READ && b == Access.READ ? 1 : 2, waves.size(), "diagnostic only, never worker authority");
            f.clean();
        }
    }

    @Test
    void ownershipChangeInsideAnAdversarialInspectIsIndependentlyRejected() {
        try (var f = new CanonicalBlockEntityTestSupport(); var other = new CanonicalBlockEntityTestSupport()) {
            final var be = source(f, "M", A, false, () -> {});
            register(f, i -> {
                be.setLevel(other.level);
                return request(i.subject(), Validation.CURRENT);
            });
            f.onStart = id -> assertAssessment(f, id, "SERIAL_ONLY", Refusal.INVALID_BINDING);
            f.enable(false);
            f.levelPhase();
            assertEquals(1, f.cells.get("M").get());
            assertAssessment(f, "M", "RETIRED", Refusal.INVALID_BINDING);
            f.clean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void exceptionDuringInspectionOrValidationFailsTheOriginalProofAndSkipsUnstartedSuffix(boolean validator) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var error = new IllegalStateException("proof callback failure");
            final var ticket = new AtomicReference<Object>();
            source(f, "M", A, false, () -> {});
            f.script("suffix", B, "minecraft:hopper", () -> {});
            register(f, i -> {
                ticket.set(currentTicket());
                if (!validator) throw error;
                return new BlockEntityAdapters.Request(BlockEntityAdapters.Execution.REGION_OWNER,
                        List.of(new BlockEntityAdapters.Resource(i.subject(), Access.WRITE, BlockEntityAdapters.Ownership.INSTANCE_LOCAL, () -> {
                            throw error;
                        })), List.of());
            });
            f.enable(false);
            assertSame(error, assertThrows(IllegalStateException.class, f::levelPhase));
            assertEquals(0, f.cells.get("M").get());
            assertEquals(0, f.cells.get("suffix").get());
            assertEquals("FAILED", CoreModExecutionProofTest.call(ticket.get(), "state").toString());
            assertEquals("RETIRED", get(ticket.get().getClass(), ticket.get(), "adapterProof").toString());
            f.clean();
        }
    }

    @Test
    void callbackCanQueryAndCloseServiceOnAnotherThreadWithoutRegistryOrCoreLockDeadlock() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var registrar = new AtomicReference<ParallelApi.Registrar>();
            final var handle = new AtomicReference<BlockEntityAdapters.Registration>();
            final var problem = new AtomicReference<Throwable>();
            source(f, "M", A, false, () -> {});
            ParallelAdapterRuntime.initialize(f.server, r -> {
                registrar.set(r);
                handle.set(r.registerBlockEntityAdapter(1, descriptor("test:proof", "minecraft:chest"), i -> {
                    final var done = new CountDownLatch(1);
                    final var other = new Thread(() -> {
                        try {
                            assertEquals(1, ParallelApi.forServer(f.server).orElseThrow().blockEntityAdapters().size());
                            assertThrows(IllegalStateException.class, handle.get()::close);
                            assertThrows(IllegalStateException.class, () -> registrar.get().registerBlockEntityAdapter(1,
                                    descriptor("test:late", "test:late"), view -> BlockEntityAdapters.Request.serial()));
                            ParallelAdapterRuntime.close(f.server);
                        } catch (Throwable failure) {
                            problem.set(failure);
                        } finally {
                            done.countDown();
                        }
                    }, "C54-callback-lifecycle");
                    other.start();
                    await(done);
                    try {
                        other.join(10000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    assertFalse(other.isAlive());
                    assertNull(problem.get());
                    return request(i.subject(), Validation.CURRENT);
                }));
            });
            f.onStart = id -> assertAssessment(f, id, "SERIAL_ONLY", Refusal.INVALIDATED);
            f.enable(false);
            f.levelPhase();
            assertEquals(BlockEntityAdapters.State.CLOSED, handle.get().state());
            assertEquals(1, f.cells.get("M").get());
            assertTrue(ParallelApi.forServer(f.server).isEmpty());
            f.clean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void lifecycleAndResourceVersionChangeAfterBodyEffectsNeverCauseSerialReplay(boolean fail) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var handle = new AtomicReference<BlockEntityAdapters.Registration>();
            final var version = new AtomicInteger();
            final var failure = new IllegalStateException("after real serial effect");
            source(f, "M", A, false, () -> {
                version.incrementAndGet();
                handle.get().invalidate();
                if (fail) throw failure;
            });
            f.script("suffix", B, "minecraft:hopper", () -> {});
            ParallelAdapterRuntime.initialize(f.server, r -> handle.set(r.registerBlockEntityAdapter(1,
                    descriptor("test:proof", "minecraft:chest"), i -> request(i.subject(), Validation.CURRENT))));
            f.enable(false);
            if (fail) assertThrows(RuntimeException.class, f::levelPhase);
            else f.levelPhase();
            assertEquals(1, version.get());
            assertEquals(1, f.cells.get("M").get());
            assertEquals(fail ? 0 : 1, f.cells.get("suffix").get());
            assertAssessment(f, "M", "RETIRED", Refusal.CORE_AUDIT_UNAVAILABLE);
            f.clean();
        }
    }

    private static Object currentTicket() {
        try {
            final var cls = Class.forName("net.neoforged.neoforge.server.threading.region.BlockEntityExecutionEngine");
            final var field = cls.getDeclaredField("CURRENT");
            field.setAccessible(true);
            final var permit = ((ThreadLocal<?>) field.get(null)).get();
            return get(permit.getClass(), permit, "ticket");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
