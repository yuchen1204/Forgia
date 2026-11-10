/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ParallelApiTest {
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerLevel level = mock(ServerLevel.class);
    private final Thread main = Thread.currentThread();
    private static final ParallelApi.DataKey<String> KEY = key("rules");

    private static ParallelApi.DataKey<String> key(String name) {
        return new ParallelApi.DataKey<>(ResourceLocation.fromNamespaceAndPath("test", name), String.class);
    }

    private void setup() {
        when(this.server.isSameThread()).thenAnswer(call -> Thread.currentThread() == this.main);
        when(this.level.getServer()).thenReturn(this.server);
        when(this.level.dimension()).thenReturn(Level.OVERWORLD);
        when(this.level.getGameTime()).thenReturn(17L);
    }

    @AfterEach
    void close() {
        ParallelAdapterRuntime.close(this.server);
    }

    @Test
    void lifecyclePublishesOnlyFrozenServiceAndInvalidatesRetainedHandles() {
        this.setup();
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
        ParallelAdapterRuntime.initialize(this.server, registrar -> {
            assertTrue(ParallelApi.forServer(this.server).isEmpty(), "registration is not yet a published service");
        });
        final var service = ParallelApi.forServer(this.server).orElseThrow();
        assertEquals(1, service.apiMajorVersion());
        assertTrue(service.capabilities().contains(ParallelApi.Capability.NATURAL_SPAWN_PREPARATION));
        assertFalse(service.regionThreadingInstalled(), "API also exists in serial mode");
        assertEquals(ParallelApi.Phase.QUIESCENT_SERVER, service.currentPhase());
        ParallelAdapterRuntime.close(this.server);
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
        assertThrows(IllegalStateException.class, service::capabilities);
        ParallelAdapterRuntime.initialize(this.server, registrar -> {});
        assertNotSame(service, ParallelApi.forServer(this.server).orElseThrow());
    }

    @Test
    void duplicateAndVersionMismatchDoNotPublishPartialRegistrations() {
        this.setup();
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("first"));
            registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("second"));
        }));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server,
                registrar -> registrar.register(2, KEY, unit -> new ParallelApi.Ready<>("unsupported"))));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void caughtRegistrationErrorStillInvalidatesTheWholeRegistration() {
        this.setup();
        assertThrows(IllegalStateException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("first"));
            assertThrows(IllegalArgumentException.class,
                    () -> registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("duplicate")));
        }));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void anAdapterLimitFailureDoesNotPublishTheFirst128Registrations() {
        this.setup();
        assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(this.server, registrar -> {
            for (int index = 0; index <= 128; index++) {
                registrar.register(1, key("adapter_" + index), unit -> new ParallelApi.Ready<>("bounded"));
            }
        }));
        assertTrue(ParallelApi.forServer(this.server).isEmpty());
    }

    @Test
    void simultaneousServerInstancesDoNotShareRegistrationsOrRetirement() {
        this.setup();
        final MinecraftServer other = mock(MinecraftServer.class);
        when(other.isSameThread()).thenAnswer(call -> Thread.currentThread() == this.main);
        final ServerLevel otherLevel = mock(ServerLevel.class);
        when(otherLevel.getServer()).thenReturn(other);
        when(otherLevel.dimension()).thenReturn(Level.OVERWORLD);
        when(otherLevel.getGameTime()).thenReturn(17L);
        try {
            ParallelAdapterRuntime.initialize(this.server,
                    registrar -> registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("first")));
            ParallelAdapterRuntime.initialize(other,
                    registrar -> registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("second")));
            final var firstWork = ParallelAdapterRuntime.prepare(this.level, 0, 0);
            final var secondWork = ParallelAdapterRuntime.prepare(otherLevel, 0, 0);
            assertFalse(firstWork.run(otherLevel, () -> fail("wrong server")));
            ParallelAdapterRuntime.close(this.server);
            assertFalse(firstWork.run(this.level, () -> fail("retired server")));
            assertTrue(secondWork.run(otherLevel, () -> assertEquals("second", ParallelApi.prepared(KEY).orElseThrow())));
        } finally {
            ParallelAdapterRuntime.close(other);
        }
    }

    @Test
    void retainedRegistrarCannotRegisterAfterTheEvent() {
        this.setup();
        final var saved = new AtomicReference<ParallelApi.Registrar>();
        ParallelAdapterRuntime.initialize(this.server, saved::set);
        assertThrows(IllegalStateException.class,
                () -> saved.get().register(1, KEY, unit -> new ParallelApi.Ready<>("late")));
    }

    @Test
    void preparationsAreOrderedAndDataOnlyExistsInsideTheBody() {
        this.setup();
        final List<String> order = new ArrayList<>();
        final var other = key("aaa");
        ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.register(1, KEY, unit -> {
                order.add("rules");
                assertEquals(new ParallelApi.SpawnUnit(Level.OVERWORLD, 51, -52, 17), unit);
                return new ParallelApi.Ready<>("revision-1");
            });
            registrar.register(1, other, unit -> {
                order.add("aaa");
                return new ParallelApi.Ready<>("other");
            });
        });
        final var work = ParallelAdapterRuntime.prepare(this.level, 51, -52);
        assertEquals(List.of("aaa", "rules"), order);
        assertTrue(work.workerReady());
        assertTrue(ParallelApi.prepared(KEY).isEmpty());
        assertTrue(work.run(this.level, () -> {
            assertEquals("revision-1", ParallelApi.prepared(KEY).orElseThrow());
            assertEquals(51, ParallelApi.currentSpawnUnit().orElseThrow().chunkX());
            assertTrue(ParallelApi.prepared(new ParallelApi.DataKey<>(KEY.id(), Integer.class)).isEmpty());
        }));
        assertTrue(ParallelApi.prepared(KEY).isEmpty());
        assertTrue(ParallelApi.currentSpawnUnit().isEmpty());
        final var status = ParallelApi.forServer(this.server).orElseThrow().adapters();
        assertEquals(2, status.size());
        assertEquals(1, status.getFirst().preparedUnits());
        assertThrows(UnsupportedOperationException.class, status::clear);
    }

    @Test
    void explicitFallbackDiscardsPartialDataBeforeTheBody() {
        this.setup();
        final var reason = ResourceLocation.fromNamespaceAndPath("test", "missing_structure");
        ParallelAdapterRuntime.initialize(this.server, registrar -> {
            registrar.register(1, key("aaa"), unit -> new ParallelApi.Ready<>("partial"));
            registrar.register(1, KEY, unit -> new ParallelApi.ServerRequired<>(reason));
        });
        final var work = ParallelAdapterRuntime.prepare(this.level, 51, -52);
        assertFalse(work.workerReady());
        final var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> work.run(this.level, calls::incrementAndGet));
        assertEquals(0, calls.get());
        assertTrue(ParallelApi.prepared(key("aaa")).isEmpty());
        final var status = ParallelApi.forServer(this.server).orElseThrow().adapters().getLast();
        assertEquals(1, status.serialUnits());
        assertEquals(reason, status.lastSerialReason().orElseThrow());
    }

    @Test
    void callbackFailurePropagatesAndIsNeverRetriedAsFallback() {
        this.setup();
        final var failure = new IllegalStateException("preparation bug");
        final var calls = new AtomicInteger();
        ParallelAdapterRuntime.initialize(this.server, registrar -> registrar.register(1, KEY, unit -> {
            calls.incrementAndGet();
            throw failure;
        }));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ParallelAdapterRuntime.prepare(this.level, 0, 0)));
        assertEquals(1, calls.get());
        assertEquals(0, ParallelApi.forServer(this.server).orElseThrow().adapters().getFirst().serialUnits());
    }

    @Test
    void nestedSerialCallsAndExceptionalExitDoNotLeakSnapshots() {
        this.setup();
        ParallelAdapterRuntime.initialize(this.server,
                registrar -> registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("outer")));
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        assertThrows(IllegalStateException.class, () -> work.run(this.level, () -> {
            ParallelAdapterRuntime.runSerial(() -> assertTrue(ParallelApi.prepared(KEY).isEmpty()));
            assertEquals("outer", ParallelApi.prepared(KEY).orElseThrow());
            throw new IllegalStateException("body failed");
        }));
        assertTrue(ParallelApi.prepared(KEY).isEmpty());
        assertTrue(ParallelApi.currentSpawnUnit().isEmpty());
    }

    @Test
    void staleOrRetiredWorkCannotRun() {
        this.setup();
        ParallelAdapterRuntime.initialize(this.server,
                registrar -> registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("data")));
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        final var calls = new AtomicInteger();
        final ServerLevel replacement = mock(ServerLevel.class);
        when(replacement.getServer()).thenReturn(this.server);
        when(replacement.dimension()).thenReturn(Level.OVERWORLD);
        when(replacement.getGameTime()).thenReturn(17L);
        assertFalse(work.run(replacement, calls::incrementAndGet),
                "reloaded dimension with the same key and clock must not inherit the old level's snapshot");
        when(this.level.getGameTime()).thenReturn(18L);
        assertFalse(work.run(this.level, calls::incrementAndGet));
        when(this.level.getGameTime()).thenReturn(17L);
        ParallelAdapterRuntime.close(this.server);
        assertFalse(work.run(this.level, calls::incrementAndGet));
        assertEquals(0, calls.get());
    }

    @Test
    void admittedPreparedUnitCannotBeExecutedTwice() {
        this.setup();
        ParallelAdapterRuntime.initialize(this.server,
                registrar -> registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("data")));
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        final var calls = new AtomicInteger();
        assertTrue(work.run(this.level, calls::incrementAndGet));
        assertThrows(IllegalStateException.class, () -> work.run(this.level, calls::incrementAndGet));
        assertEquals(1, calls.get());
    }

    @Test
    void externalThreadsCannotPrepareOrReadAnActiveSnapshot() throws Exception {
        this.setup();
        ParallelAdapterRuntime.initialize(this.server,
                registrar -> registrar.register(1, KEY, unit -> new ParallelApi.Ready<>("data")));
        final var service = ParallelApi.forServer(this.server).orElseThrow();
        final var work = ParallelAdapterRuntime.prepare(this.level, 0, 0);
        final var failure = new AtomicReference<Throwable>();
        work.run(this.level, () -> {
            final Thread thread = new Thread(() -> {
                try {
                    assertEquals(ParallelApi.Phase.EXTERNAL, service.currentPhase());
                    assertTrue(ParallelApi.prepared(KEY).isEmpty());
                    assertThrows(IllegalStateException.class, () -> ParallelAdapterRuntime.prepare(this.level, 0, 0));
                    assertThrows(IllegalStateException.class, () -> work.run(this.level, () -> fail("external body")));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            thread.start();
            try {
                thread.join(5000);
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            assertFalse(thread.isAlive());
        });
        assertNull(failure.get());
    }
}
