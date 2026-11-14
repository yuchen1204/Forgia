/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.get;
import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.invoke;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters.Refusal;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAdmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual kernel gates; real Level integration/adversarial cases exercise the production binding bridge. */
class CoreModExecutionProofTest {
    static Object call(Object target, String name, Object... args) {
        final var methods = Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length).toList();
        assertEquals(1, methods.size(), name);
        final var method = methods.getFirst();
        method.setAccessible(true);
        return invoke(method, target, args);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void originalSerialTicketOwnsTheAssessmentAndCannotBeReplayed(boolean validBinding) {
        final var fixture = new BlockEntityExecutionEngineTest();
        final var a = (RegionBlockEntityTickAdmission) call(fixture, "admission", 17);
        final var engine = call(fixture, "phase", (Object) new RegionBlockEntityTickAdmission[] { a });
        call(engine, "orderedEffects", true);
        final var ticket = call(engine, "ticket", a);
        assertThrows(IllegalStateException.class, () -> call(engine, "beginAdapterProof", a));
        final var permit = call(engine, "beginSerial", a);
        call(engine, "beginAdapterProof", a);
        assertEquals("CHECKING", get(ticket.getClass(), ticket, "adapterProof").toString());
        assertThrows(IllegalStateException.class, () -> call(permit, "succeeded"));
        assertEquals(validBinding ? Refusal.CORE_AUDIT_UNAVAILABLE : Refusal.INVALID_BINDING,
                call(engine, "refuseAdapterProof", a, validBinding, true, Refusal.CORE_AUDIT_UNAVAILABLE));
        assertEquals("SERIAL_ONLY", get(ticket.getClass(), ticket, "adapterProof").toString());
        assertThrows(IllegalStateException.class, () -> call(engine, "beginAdapterProof", a));
        assertThrows(IllegalStateException.class, () -> call(engine, "refuseAdapterProof", a, true, true, Refusal.CORE_AUDIT_UNAVAILABLE));
        call(permit, "succeeded");
        call(permit, "close");
        assertThrows(IllegalStateException.class, () -> call(engine, "beginSerial", a));
        assertThrows(IllegalStateException.class, () -> call(engine, "reinspectUnstarted", a,
                net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Inspection.serialFence()));
        call(engine, "close");
        assertEquals("RETIRED", get(ticket.getClass(), ticket, "adapterProof").toString());
        assertEquals(validBinding ? Refusal.CORE_AUDIT_UNAVAILABLE : Refusal.INVALID_BINDING,
                get(ticket.getClass(), ticket, "adapterRefusal"));
    }

    @Test
    void registrationLossNeverReturnsAnyWorkerAuthority() {
        final var fixture = new BlockEntityExecutionEngineTest();
        final var a = (RegionBlockEntityTickAdmission) call(fixture, "admission", 17);
        final var engine = call(fixture, "phase", (Object) new RegionBlockEntityTickAdmission[] { a });
        call(engine, "orderedEffects", true);
        final var permit = call(engine, "beginSerial", a);
        call(engine, "beginAdapterProof", a);
        assertEquals(Refusal.INVALIDATED, call(engine, "refuseAdapterProof", a, true, false, Refusal.CORE_AUDIT_UNAVAILABLE));
        call(permit, "succeeded");
        call(permit, "close");
        call(engine, "close");
    }

    @Test
    void exceptionalPendingAssessmentFailsWithTheOriginalPermitAndRetiresOnKernelClose() {
        final var fixture = new BlockEntityExecutionEngineTest();
        final var a = (RegionBlockEntityTickAdmission) call(fixture, "admission", 17);
        final var engine = call(fixture, "phase", (Object) new RegionBlockEntityTickAdmission[] { a });
        call(engine, "orderedEffects", true);
        final var ticket = call(engine, "ticket", a);
        final var permit = call(engine, "beginSerial", a);
        call(engine, "beginAdapterProof", a);
        call(permit, "close");
        assertEquals("FAILED", get(ticket.getClass(), ticket, "adapterProof").toString());
        assertEquals("FAILED", call(ticket, "state").toString());
        call(engine, "close");
        assertEquals("RETIRED", get(ticket.getClass(), ticket, "adapterProof").toString());
    }
}
