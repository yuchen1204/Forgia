/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.AdapterDescriptor;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Inspection;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Mode;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Outcome;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Refresh;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Resources;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Access;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Binding;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Claims;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Identity;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAdmission;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import org.junit.jupiter.api.Test;

/** Drives the internal authorization gates without exporting constructors or using a split Java package. */
class BlockEntityExecutionEngineTest {
    private final Thread coordinator = Thread.currentThread();
    private final ServerLevel level = mock(ServerLevel.class);
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final AtomicBoolean current = new AtomicBoolean(true);
    private Object home;

    private static final AdapterDescriptor REGION = new AdapterDescriptor(ResourceLocation.parse("test:native"),
            Set.of(ResourceLocation.parse("test:machine")), Mode.REGION_OWNER);
    private static final AdapterDescriptor FINE = new AdapterDescriptor(ResourceLocation.parse("test:furnace"),
            Set.of(ResourceLocation.parse("test:machine")), Mode.RESOURCE_SCOPED);

    private RegionBlockEntityTickAdmission admission(int x) {
        when(this.level.getServer()).thenReturn(this.server);
        when(this.server.isSameThread()).thenAnswer(call -> Thread.currentThread() == this.coordinator);
        final var ticker = mock(TickingBlockEntity.class);
        final var pos = new BlockPos(x, 64, 17);
        when(ticker.getPos()).thenReturn(pos);
        when(ticker.getType()).thenReturn("test:machine");
        return new RegionBlockEntityTickAdmission(this.level, ticker, pos, "test:machine",
                new RegionBlockEntityTickAdmission.Binding(ticker, this.current::get),
                RegionBlockEntityTickAdmission.Decision.ADMITTED, x);
    }

    private Object phase(RegionBlockEntityTickAdmission... admissions) {
        return reflect(() -> {
            final var constructor = Class.forName("net.neoforged.neoforge.server.threading.region.BlockEntityExecutionEngine")
                    .getDeclaredConstructor(ServerLevel.class, List.class, BooleanSupplier.class, Function.class);
            constructor.setAccessible(true);
            return constructor.newInstance(this.level, List.of(admissions), (BooleanSupplier) () -> true,
                    (Function<BlockPos, Object>) pos -> this.home);
        });
    }

    private Object region() {
        return reflect(() -> {
            final var constructor = Class.forName("net.neoforged.neoforge.server.threading.region.RegionState")
                    .getDeclaredConstructor(RegionCoordinate.class);
            constructor.setAccessible(true);
            final var region = constructor.newInstance(new RegionCoordinate(this.level, 0, 0));
            this.home = region;
            assertEquals(true, call(region, "tryMarkRunning"));
            return region;
        });
    }

    private static Object inspected(Object phase, RegionBlockEntityTickAdmission admission, boolean fine) {
        return call(phase, "inspect", admission,
                new Inspection(fine ? FINE : REGION, fine ? Resources.FURNACE_LOCAL : Resources.REGION_LOCAL, Outcome.CANDIDATE));
    }

    private static Object permit(Object phase, Object ticket, Object guard) {
        final Inspection inspection = (Inspection) call(ticket, "inspection");
        return call(phase, "permit", ticket, guard, new Refresh(inspection, Outcome.CANDIDATE), null);
    }

    @Test
    void publicDescriptionsAreImmutableAndDoNotContainExecutionAuthority() {
        final var types = new java.util.HashSet<>(REGION.types());
        final var descriptor = new AdapterDescriptor(REGION.id(), types, Mode.REGION_OWNER);
        types.clear();
        assertEquals(REGION.types(), descriptor.types());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.types().clear());
        assertThrows(IllegalArgumentException.class, () -> new AdapterDescriptor(REGION.id(), Set.of(), Mode.REGION_OWNER));
        assertThrows(IllegalArgumentException.class, () -> new Inspection(REGION, Resources.FURNACE_LOCAL, Outcome.CANDIDATE));
        for (final Class<?> type : List.of(AdapterDescriptor.class, Inspection.class, Refresh.class)) {
            assertTrue(type.isRecord());
            for (final var component : type.getRecordComponents()) {
                assertFalse(Runnable.class.isAssignableFrom(component.getType()));
                assertFalse(component.getType().getSimpleName().matches(".*(Permit|Lease|Ticket).*"));
            }
        }
    }

    @Test
    void exactTickerCanOnlyBeCapturedAndStartedOnceAndCannotCrossPhases() {
        final var admission = this.admission(17);
        assertThrows(IllegalArgumentException.class, () -> this.phase(admission, admission));
        final var phase = this.phase(admission);
        final var other = this.phase(admission);
        final var region = this.region();
        call(region, "beginOwnership");
        final var guard = call(phase, "regionOwner", region);
        final var ticket = inspected(phase, admission, false);
        final var permit = permit(phase, ticket, guard);
        assertNotNull(permit);
        assertThrows(IllegalStateException.class, () -> call(phase, "close"));
        assertThrows(IllegalStateException.class, () -> call(region, "clearRunning"));
        assertThrows(IllegalStateException.class, () -> permit(phase, ticket, guard));
        assertThrows(IllegalStateException.class, () -> permit(other, ticket, guard));
        assertThrows(IllegalStateException.class, () -> call(phase, "beginSerial", admission));
        call(permit, "succeeded");
        call(permit, "close");
        assertEquals("SUCCEEDED", call(ticket, "state").toString());
        assertThrows(IllegalStateException.class, () -> permit(phase, ticket, guard));
        call(guard, "close");
        call(region, "endOwnership");
        call(region, "clearRunning");
        call(phase, "close");
        call(other, "close");
    }

    @Test
    void freshResultMustMatchTheExactInspectionAndCurrentBinding() {
        final var admission = this.admission(17);
        final var phase = this.phase(admission);
        final var region = this.region();
        call(region, "beginOwnership");
        final var guard = call(phase, "regionOwner", region);
        final var ticket = inspected(phase, admission, false);
        final var copy = new Inspection(REGION, Resources.REGION_LOCAL, Outcome.CANDIDATE);
        assertThrows(IllegalStateException.class, () -> call(phase, "permit", ticket, guard, new Refresh(copy, Outcome.CANDIDATE), null));
        this.current.set(false);
        assertEquals(null, permit(phase, ticket, guard));
        call(guard, "close");
        call(region, "endOwnership");
        final var serial = call(phase, "beginSerial", admission);
        call(serial, "succeeded");
        call(serial, "close");
        call(region, "clearRunning");
        call(phase, "close");
    }

    @Test
    void finePermitsCannotBecomeFullRegionOwnersAndCommitWaitsForAllFineBodies() {
        final var first = this.admission(17);
        final var second = this.admission(20);
        final var phase = this.phase(first, second);
        final var region = this.region();
        final var guard = call(phase, "subordinate", region);
        final var ticket = inspected(phase, first, true);
        final var other = inspected(phase, second, true);
        final var failure = new AtomicReference<Throwable>();
        final var worker = new Thread(() -> {
            try {
                final var permit = permit(phase, ticket, guard);
                assertThrows(IllegalStateException.class, () -> call(region, "beginOwnership"));
                assertThrows(IllegalStateException.class, () -> call(guard, "close"));
                assertThrows(IllegalStateException.class, () -> call(phase, "close"));
                call(permit, "succeeded");
                call(permit, "close");
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "fine-authorization-test");
        worker.start();
        reflect(() -> {
            worker.join(10000);
            return null;
        });
        assertFalse(worker.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        // Idle authority is not a join: a second unstarted body still belongs to this wave.
        assertThrows(IllegalStateException.class, () -> call(region, "beginOwnership"));
        final var permit = permit(phase, other, guard);
        assertThrows(IllegalStateException.class, () -> call(region, "beginOwnership"));
        assertThrows(IllegalStateException.class, () -> call(guard, "close"));
        call(permit, "succeeded");
        call(permit, "close");
        call(guard, "sealForCommit");
        call(region, "beginOwnership");
        assertThrows(IllegalStateException.class, () -> call(phase, "regionOwner", region));
        call(region, "endOwnership");
        call(guard, "close");
        call(region, "clearRunning");
        call(phase, "close");
    }

    @Test
    void ownerCannotBeOverwrittenAndFineAuthorityCannotOverlapAnOwner() {
        final var phase = this.phase(this.admission(17));
        final var region = this.region();
        call(region, "beginOwnership");
        assertThrows(IllegalStateException.class, () -> call(region, "beginOwnership"));
        assertThrows(IllegalStateException.class, () -> call(phase, "subordinate", region));
        final var guard = call(phase, "regionOwner", region);
        assertThrows(IllegalStateException.class, () -> call(phase, "subordinate", region));
        assertThrows(IllegalStateException.class, () -> call(phase, "regionOwner", region));
        call(guard, "close");
        call(region, "endOwnership");
        call(region, "clearRunning");
        call(phase, "close");
    }

    @Test
    void permitIsThreadBoundAndFailureCannotReturnToSerial() {
        final var admission = this.admission(17);
        final var phase = this.phase(admission);
        final var region = this.region();
        call(region, "beginOwnership");
        final var guard = call(phase, "regionOwner", region);
        final var ticket = inspected(phase, admission, false);
        final var permit = permit(phase, ticket, guard);
        final var error = new AtomicReference<Throwable>();
        final var worker = new Thread(() -> {
            try {
                assertThrows(IllegalStateException.class, () -> call(permit, "succeeded"));
                assertThrows(IllegalStateException.class, () -> call(permit, "close"));
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        worker.start();
        reflect(() -> {
            worker.join(10000);
            return null;
        });
        assertFalse(worker.isAlive());
        if (error.get() != null) throw new AssertionError(error.get());
        call(permit, "close");
        assertEquals("FAILED", call(ticket, "state").toString());
        assertThrows(IllegalStateException.class, () -> call(phase, "beginSerial", admission));
        call(guard, "close");
        call(region, "endOwnership");
        call(region, "clearRunning");
        call(phase, "close");
    }

    @Test
    void unknownInvocationNeedsNoAdapterAndRetirementCancelsAllUnstartedTickets() {
        final var first = this.admission(17);
        final var second = this.admission(20);
        final var phase = this.phase(first, second);
        final var ticket = inspected(phase, second, false);
        final var serial = call(phase, "beginSerial", first);
        call(serial, "succeeded");
        call(serial, "close");
        call(phase, "close");
        assertEquals("CANCELLED", call(ticket, "state").toString());
        assertThrows(IllegalStateException.class, () -> call(phase, "beginSerial", second));
        assertThrows(IllegalStateException.class, () -> call(phase, "beginSerial", first));
    }

    @Test
    void actualResourceGrantAndScopeRemainSubordinateUntilTheirPhysicalReturn() {
        final var first = this.admission(17);
        final var second = this.admission(20);
        final var phase = this.phase(first, second);
        final var region = this.region();
        final var resourceHome = new Object();
        final var guard = call(phase, "subordinate", region, resourceHome);
        final var inspection = new Inspection(FINE, Resources.FURNACE_CLAIMS, Outcome.CANDIDATE);
        final var ticket = call(phase, "inspect", first, inspection);
        final var otherInspection = new Inspection(FINE, Resources.FURNACE_CLAIMS, Outcome.CANDIDATE);
        final var other = call(phase, "inspect", second, otherInspection);
        final var identity = new Object();
        final Claims claims = Claims.builder(this.level, resourceHome)
                .claim(new Binding(new Identity(this.level, identity), resourceHome, () -> true, null), Access.WRITE).build();
        final var owner = reflect(() -> {
            final var constructor = BlockEntityResourceOwnership.class.getDeclaredConstructor(Object.class, Map.class, BooleanSupplier.class);
            constructor.setAccessible(true);
            return constructor.newInstance(this.level, Map.of(resourceHome, (BooleanSupplier) () -> true), (BooleanSupplier) () -> true);
        });
        final var grants = (List<?>) call(owner, "tryAcquireAll", List.of(claims));
        assertNotNull(grants);
        final var lease = grants.getFirst();
        assertThrows(IllegalStateException.class, () -> call(phase, "permit", ticket, guard, new Refresh(inspection, Outcome.CANDIDATE), null));
        final var permit = call(phase, "permit", ticket, guard, new Refresh(inspection, Outcome.CANDIDATE), lease);
        final var scope = call(lease, "enter");
        BlockEntityResourceOwnership.Scope.require(this.level, identity, Access.WRITE);
        assertThrows(IllegalStateException.class, () -> call(permit, "succeeded"));
        assertThrows(IllegalStateException.class, () -> call(permit, "close"));
        assertThrows(IllegalStateException.class, () -> call(owner, "close"));
        assertThrows(IllegalStateException.class, () -> call(region, "beginOwnership"));
        assertThrows(IllegalStateException.class, () -> call(guard, "close"));
        call(scope, "close");
        call(permit, "succeeded");
        call(permit, "close");
        assertThrows(IllegalStateException.class, () -> call(phase, "permit", other, guard, new Refresh(otherInspection, Outcome.CANDIDATE), lease));
        call(guard, "sealForCommit");
        call(region, "beginOwnership");
        call(region, "endOwnership");
        call(owner, "close");
        call(guard, "close");
        call(region, "clearRunning");
        call(phase, "close");
        assertFalse(BlockEntityResourceOwnership.Scope.active());
    }

    @Test
    void sameCoordinateRegionReplacementCannotInheritAnInvocationAndUnknownCannotBeAdmitted() {
        final var original = this.admission(17);
        final var uncaptured = this.admission(20);
        final var unknown = new RegionBlockEntityTickAdmission(this.level, uncaptured.ticker(), uncaptured.position(), uncaptured.type(), uncaptured.binding(),
                RegionBlockEntityTickAdmission.Decision.TYPE_NOT_DECLARED, uncaptured.sequence());
        final var phase = this.phase(original, unknown);
        final var actual = this.region();
        final var replacement = this.region();
        this.home = actual;
        call(replacement, "beginOwnership");
        final var guard = call(phase, "regionOwner", replacement);
        final var ticket = inspected(phase, original, false);
        assertEquals(null, permit(phase, ticket, guard), "equal coordinates do not prove actual region identity");
        this.home = replacement;
        final var unknownTicket = inspected(phase, unknown, false);
        assertEquals(null, permit(phase, unknownTicket, guard), "a fabricated candidate cannot widen unknown type admission");
        call(guard, "close");
        call(replacement, "endOwnership");
        call(replacement, "clearRunning");
        call(actual, "clearRunning");
        final var serial = call(phase, "beginSerial", unknown);
        call(serial, "succeeded");
        call(serial, "close");
        call(phase, "close");
    }

    @Test
    void serialFenceCannotStartWhileAWorkerAuthorityIsReservedEvenBeforeItsFirstBody() {
        final var admission = this.admission(17);
        final var phase = this.phase(admission);
        final var region = this.region();
        final var guard = call(phase, "subordinate", region);
        assertThrows(IllegalStateException.class, () -> call(phase, "beginSerial", admission));
        assertThrows(IllegalStateException.class, () -> call(phase, "close"));
        call(guard, "close");
        call(region, "clearRunning");
        final var serial = call(phase, "beginSerial", admission);
        call(serial, "succeeded");
        call(serial, "close");
        call(phase, "close");
    }

    @Test
    void joinedGuardRevokesQueuedUnstartedPermitsBeforeCanonicalCommit() {
        final var admission = this.admission(17);
        final var phase = this.phase(admission);
        final var region = this.region();
        final var guard = call(phase, "subordinate", region);
        final var ticket = inspected(phase, admission, true);
        call(guard, "sealForCommit");
        call(region, "beginOwnership");
        call(region, "endOwnership");
        assertThrows(IllegalStateException.class, () -> permit(phase, ticket, guard));
        call(guard, "close");
        call(region, "clearRunning");
        call(phase, "close");
        assertEquals("CANCELLED", call(ticket, "state").toString());
    }

    static String currentMode() {
        return reflect(() -> {
            final var method = Class.forName("net.neoforged.neoforge.server.threading.region.BlockEntityExecutionEngine").getDeclaredMethod("currentMode");
            method.setAccessible(true);
            final var mode = method.invoke(null);
            return mode == null ? null : mode.toString();
        });
    }

    private static Object call(Object target, String name, Object... args) {
        return reflect(() -> {
            final var methods = new ArrayList<java.lang.reflect.Method>();
            for (final var method : target.getClass().getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) methods.add(method);
            }
            assertEquals(1, methods.size(), "unambiguous internal gate: " + name);
            final var method = methods.getFirst();
            method.setAccessible(true);
            return method.invoke(target, args);
        });
    }

    private static <T> T reflect(Reflective<T> operation) {
        try {
            return operation.run();
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException | InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private interface Reflective<T> {
        T run() throws ReflectiveOperationException, InterruptedException;
    }
}
