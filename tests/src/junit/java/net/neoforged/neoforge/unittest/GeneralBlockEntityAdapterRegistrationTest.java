/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapter;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters;
import net.neoforged.neoforge.server.threading.api.adapter.DedicatedBlockEntityAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual registration transaction; mock servers supply thread identity only, never execution proof. */
@Timeout(30)
class GeneralBlockEntityAdapterRegistrationTest {
    private final Thread owner = Thread.currentThread();
    private final MinecraftServer server = mock(MinecraftServer.class);
    private static final BlockEntityAdapter SERIAL = invocation -> BlockEntityAdapters.Request.serial();

    GeneralBlockEntityAdapterRegistrationTest() {
        when(this.server.isSameThread()).thenAnswer(call -> Thread.currentThread() == this.owner);
    }

    @AfterEach
    void close() {
        ParallelAdapterRuntime.close(this.server);
    }

    private static BlockEntityAdapters.Descriptor descriptor(String id, String type) {
        return new BlockEntityAdapters.Descriptor(id, Set.of(type), Set.of(BlockEntityAdapters.Execution.values()));
    }

    @Test
    void registrationUsesExistingTransactionAndV1MajorAndFreezesWithoutPublishingEarly() {
        final var saved = new AtomicReference<ParallelApi.Registrar>();
        ParallelAdapterRuntime.initialize(this.server, registrar -> {
            assertTrue(ParallelApi.forServer(this.server).isEmpty());
            assertTrue(registrar.capabilities().contains(ParallelApi.Capability.GENERAL_BLOCK_ENTITY_ADAPTERS));
            saved.set(registrar);
            registrar.registerBlockEntityAdapter(1, descriptor("test:z", "test:machine_z"), SERIAL);
            registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:machine_a"), SERIAL);
        });
        final var api = ParallelApi.forServer(this.server).orElseThrow();
        assertEquals(1, api.apiMajorVersion());
        assertEquals(java.util.List.of("test:a", "test:z"), api.blockEntityAdapters().stream().map(s -> s.descriptor().id()).toList());
        assertThrows(IllegalStateException.class, () -> saved.get().registerBlockEntityAdapter(1, descriptor("test:b", "test:b"), SERIAL));
        assertTrue(api.lastBlockEntityAdapterPhase().isEmpty());
    }

    @Test
    void duplicateIdEvenWhenCaughtPoisonsTheWholeTransaction() {
        assertThrows(IllegalStateException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), SERIAL);
            assertThrows(IllegalArgumentException.class, () -> registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:b"), SERIAL));
        }));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void duplicateExactTypesAreRejectedWithoutRegistrationOrderWinners() {
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), SERIAL);
            registrar.registerBlockEntityAdapter(1, descriptor("test:b", "test:a"), SERIAL);
        }));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void generalThenV1DuplicateTypeInvalidatesTransaction() {
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), SERIAL);
            registrar.registerBlockEntityTicks(1, ResourceLocation.parse("test:b"), Set.of(ResourceLocation.parse("test:a")), be -> BlockEntityTickAdapter.Decision.ADMITTED);
        }));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void v1ThenGeneralDuplicateTypeInvalidatesTransaction() {
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.registerBlockEntityTicks(1, ResourceLocation.parse("test:a"), Set.of(ResourceLocation.parse("test:machine")), be -> BlockEntityTickAdapter.Decision.ADMITTED);
            registrar.registerBlockEntityAdapter(1, descriptor("test:b", "test:machine"), SERIAL);
        }));
    }

    @Test
    void spawnAndGeneralShareIdNamespaceInBothRegistrationOrders() {
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:machine"), SERIAL);
            registrar.register(1, new ParallelApi.DataKey<>(ResourceLocation.parse("test:a"), String.class), unit -> new ParallelApi.Ready<>("x"));
        }));
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.register(1, new ParallelApi.DataKey<>(ResourceLocation.parse("test:a"), String.class), unit -> new ParallelApi.Ready<>("x"));
            registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:machine"), SERIAL);
        }));
    }

    @Test
    void allProtocolsShareTheOriginal128RegistrationBound() {
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            for (int i = 0; i < 127; ++i) registrar.register(1, new ParallelApi.DataKey<>(ResourceLocation.parse("test:spawn_" + i), String.class), unit -> new ParallelApi.Ready<>("x"));
            registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), SERIAL);
            registrar.registerBlockEntityAdapter(1, descriptor("test:b", "test:b"), SERIAL);
        }));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void wrongProtocolAndNullAdapterNeverPublishPartialServices() {
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server,
                registrar -> registrar.registerBlockEntityAdapter(2, descriptor("test:a", "test:a"), SERIAL)));
        assertThrows(NullPointerException.class, () -> ParallelAdapterRuntime.initialize(this.server,
                registrar -> registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), null)));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void dedicatedAuditIsMetadataAndCannotGrantAnyBackend() {
        ParallelAdapterRuntime.initialize(this.server, registrar -> registrar.registerBlockEntityAdapter(1, descriptor("test:dedicated", "test:machine"), new DedicatedBlockEntityAdapter() {
            @Override
            public Audit audit() {
                return new Audit("test/audit.md", "test-revision");
            }

            @Override
            public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation) {
                throw new AssertionError("registration cannot execute inspection");
            }
        }));
        final var status = ParallelApi.forServer(this.server).orElseThrow().blockEntityAdapters().getFirst();
        assertEquals("test-revision", status.audit().orElseThrow().revision());
        assertEquals(0, status.inspections());
        assertEquals(BlockEntityAdapters.State.ACTIVE, status.state());
    }

    @Test
    void throwingDedicatedAuditRollsBackAllRegistrations() {
        final var failure = new IllegalStateException("audit unavailable");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), SERIAL);
            registrar.registerBlockEntityAdapter(1, descriptor("test:b", "test:b"), new DedicatedBlockEntityAdapter() {
                @Override
                public Audit audit() {
                    throw failure;
                }

                @Override
                public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation) {
                    return BlockEntityAdapters.Request.serial();
                }
            });
        })));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void unregisterInvalidateAndServerStopRetireIdentityWithoutResurrection() {
        final var handle = new AtomicReference<BlockEntityAdapters.Registration>();
        ParallelAdapterRuntime.initialize(this.server, registrar -> handle.set(registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), SERIAL)));
        final var api = ParallelApi.forServer(this.server).orElseThrow();
        handle.get().invalidate();
        assertEquals(BlockEntityAdapters.State.INVALIDATED, api.blockEntityAdapters().getFirst().state());
        handle.get().close();
        handle.get().close();
        assertEquals(BlockEntityAdapters.State.UNREGISTERED, handle.get().state());
        ParallelAdapterRuntime.close(this.server);
        assertEquals(BlockEntityAdapters.State.CLOSED, handle.get().state());
        assertThrows(IllegalStateException.class, handle.get()::invalidate);
        assertThrows(IllegalStateException.class, api::blockEntityAdapters);
        assertThrows(IllegalStateException.class, api::lastBlockEntityAdapterPhase);
        ParallelAdapterRuntime.initialize(this.server, registrar -> registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), SERIAL));
        assertEquals(BlockEntityAdapters.State.ACTIVE, ParallelApi.forServer(this.server).orElseThrow().blockEntityAdapters().getFirst().state());
        assertEquals(BlockEntityAdapters.State.CLOSED, handle.get().state());
    }

    @Test
    void externalThreadCannotChangeRegistrationLifecycle() throws InterruptedException {
        final var handle = new AtomicReference<BlockEntityAdapters.Registration>();
        ParallelAdapterRuntime.initialize(this.server, registrar -> handle.set(registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), SERIAL)));
        final var failure = new AtomicReference<Throwable>();
        final var other = new Thread(() -> {
            try {
                handle.get().invalidate();
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        other.start();
        other.join(10000);
        assertFalse(other.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(BlockEntityAdapters.State.ACTIVE, handle.get().state());
    }

    @Test
    void reentrantAuditCannotReplaceAnAlreadyRegisteredIdentity() {
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> registrar.registerBlockEntityAdapter(1,
                descriptor("test:a", "test:a"), new DedicatedBlockEntityAdapter() {
                    @Override
                    public Audit audit() {
                        registrar.registerBlockEntityAdapter(1, descriptor("test:a", "test:a"), SERIAL);
                        return new Audit("test/audit", "1");
                    }

                    @Override
                    public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation) {
                        return BlockEntityAdapters.Request.serial();
                    }
                })));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void reentrantAuditCannotCollideWithALegacyProtocol() {
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> registrar.registerBlockEntityAdapter(1,
                descriptor("test:a", "test:a"), new DedicatedBlockEntityAdapter() {
                    @Override
                    public Audit audit() {
                        registrar.register(1, new ParallelApi.DataKey<>(ResourceLocation.parse("test:a"), String.class), unit -> new ParallelApi.Ready<>("x"));
                        return new Audit("test/audit", "1");
                    }

                    @Override
                    public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation) {
                        return BlockEntityAdapters.Request.serial();
                    }
                })));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void reentrantAuditCannotExceedTheSharedRegistrationBudget() {
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> registrar.registerBlockEntityAdapter(1,
                descriptor("test:a", "test:a"), new DedicatedBlockEntityAdapter() {
                    @Override
                    public Audit audit() {
                        for (int i = 0; i < 128; ++i) registrar.register(1, new ParallelApi.DataKey<>(ResourceLocation.parse("test:spawn_" + i), String.class), unit -> new ParallelApi.Ready<>("x"));
                        return new Audit("test/audit", "1");
                    }

                    @Override
                    public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation) {
                        return BlockEntityAdapters.Request.serial();
                    }
                })));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void descriptorIsBoundedAndImmutable() {
        final var types = new java.util.HashSet<>(Set.of("test:machine"));
        final var value = new BlockEntityAdapters.Descriptor("test:a", types, Set.of(BlockEntityAdapters.Execution.SERIAL));
        types.clear();
        assertEquals(Set.of("test:machine"), value.types());
        assertThrows(UnsupportedOperationException.class, () -> value.types().clear());
        assertThrows(IllegalArgumentException.class, () -> descriptor("bad id", "test:a"));
        assertThrows(IllegalArgumentException.class, () -> new BlockEntityAdapters.Descriptor("test:a", Set.of(), Set.of(BlockEntityAdapters.Execution.SERIAL)));
        assertNotNull(value);
    }
}
