/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CoreModExecutionProofTest.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import org.junit.jupiter.api.Test;

/** Existing real reservation implementation, not Mod authorization and not a duplicate proof model. */
class CoreModReservationBoundaryTest {
    @Test
    void originalReservationScopeAndLeaseCannotBeReleasedTwiceOrReenteredAfterJoin() {
        final var fixture = new BlockEntityResourceOwnershipTest();
        final var owner = call(fixture, "ownership");
        final var claims = call(fixture, "claims", new Object(), BlockEntityResourceOwnership.Access.WRITE);
        final var leases = (List<?>) call(owner, "tryAcquireAll", List.of(claims));
        assertEquals(1, leases.size());
        final var lease = leases.getFirst();
        final var scope = (BlockEntityResourceOwnership.Scope) call(lease, "enter");
        try {
            assertThrows(IllegalStateException.class, () -> call(owner, "close"));
            assertThrows(IllegalStateException.class, () -> call(lease, "enter"));
        } finally {
            scope.close();
        }
        assertThrows(IllegalStateException.class, scope::close);
        assertThrows(IllegalStateException.class, () -> call(lease, "enter"));
        call(owner, "close");
        assertThrows(IllegalStateException.class, () -> call(owner, "close"));
        assertThrows(IllegalStateException.class, () -> call(lease, "enter"));
        assertNull(call(owner, "tryAcquireAll", List.of(claims)));
    }
}
