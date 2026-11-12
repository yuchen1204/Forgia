/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.neoforged.forgia.compat.create.CreateAudit;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter.Decision;
import org.junit.jupiter.api.Test;

class UnknownCreateHookIntegrationTest {
    @Test
    void unknownActuallyMergedMixinRetainsTheCompleteOriginalSerialBody() {
        assertFalse(CreateAudit.verify());
        try (final var fixture = new CreateFixture()) {
            final var millstone = fixture.millstone(new BlockPos(17, 64, 17), 100, 64);
            final var thread = new AtomicReference<Thread>();
            fixture.tick(fixture.ticker(millstone, () -> {
                thread.set(Thread.currentThread());
                millstone.tick();
            }));
            assertSame(fixture.main, thread.get());
            assertEquals(96, millstone.timer);
            assertEquals(0, fixture.status().workerCompletions());
            assertEquals(1, fixture.status().reasons().get(Decision.UNVERIFIED_TRANSFORMATIONS));
        }
    }
}
