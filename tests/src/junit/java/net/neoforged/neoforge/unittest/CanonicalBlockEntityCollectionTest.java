/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAdmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real canonical collection, including its lock, Level phase and original execution bridges. */
@Timeout(120)
class CanonicalBlockEntityCollectionTest {
    private static final BlockPos FIRST = new BlockPos(20, 64, 20);
    private static final BlockPos SECOND = new BlockPos(68, 64, 20);
    private static final BlockPos THIRD = new BlockPos(116, 64, 20);

    @ParameterizedTest(name = "{displayName} [cache={0}]")
    @ValueSource(booleans = { false, true })
    void canonicalOrdinalsFollowActualMembershipAndDoNotModifyCachedTemplates(boolean cache) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.chunk(THIRD); // Chunk registration order deliberately differs from ticker registration.
            final var a = f.script("A", FIRST, "test:unknown", () -> {});
            final var u = f.foreign("U", SECOND, () -> {});
            final var b = f.script("B", FIRST.offset(1, 0, 0), "test:unknown", () -> {});
            final var c = f.script("C", THIRD, "test:unknown", () -> {});
            final var membership = CanonicalBlockEntityTestSupport.tickerList(f.level);
            membership.clear();
            membership.addAll(List.of(c, u, a, b));
            f.enable(false);
            f.engine.setBlockEntityAdmissionCacheEnabledForTesting(cache);
            final var plan = RegionThreadingHooks.beginBlockEntityTickPlan(f.level, f.barrier);
            plan.collectCanonical();
            assertEquals(List.of(c, u, a, b), plan.canonicalTickers());
            final var ordered = new ArrayList<RegionBlockEntityTickAdmission>();
            for (var chunk : plan.collectedChunks()) ordered.addAll(chunk.admissions());
            ordered.sort(Comparator.comparingLong(RegionBlockEntityTickAdmission::sequence));
            assertEquals(List.of(c, u, a, b), ordered.stream().map(RegionBlockEntityTickAdmission::ticker).toList());
            assertEquals(List.of(0L, 1L, 2L, 3L), ordered.stream().map(RegionBlockEntityTickAdmission::sequence).toList());
            final var cachedC = f.chunk(THIRD).blockEntityTickAdmissionCache().admissions().stream()
                    .filter(admission -> admission.ticker() == c).findFirst().orElseThrow();
            assertNotSame(cachedC, ordered.getFirst());
            assertEquals(2L, cachedC.sequence(), "the chunk template retains its creation sequence");
            assertSame(cachedC.binding(), ordered.getFirst().binding());
            plan.executeAndAwait();
            assertEquals(List.of("C", "U", "A", "B"), f.bodyOrder());
            assertEquals(List.of(), plan.canonicalTickers(), "phase references are released after execution");
            f.clean();
        }
    }

    @Test
    void gettersAndRemovedChecksRunOutsideTheTickerLock() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final Object lock = get(Level.class, f.level, "blockEntityTickerLock");
            final var removed = new AtomicBoolean();
            final var bodies = new AtomicInteger();
            final var observations = new AtomicInteger();
            final TickingBlockEntity ticker = new TickingBlockEntity() {
                private void outside() {
                    assertFalse(Thread.holdsLock(lock), "ticker callback while holding the membership lock");
                    observations.incrementAndGet();
                }

                @Override
                public void tick() {
                    this.outside();
                    bodies.incrementAndGet();
                }

                @Override
                public boolean isRemoved() {
                    this.outside();
                    return removed.get();
                }

                @Override
                public BlockPos getPos() {
                    this.outside();
                    return FIRST;
                }

                @Override
                public String getType() {
                    this.outside();
                    return "test:lock_observer";
                }
            };
            f.level.addBlockEntityTicker(ticker);
            f.enable(false);
            f.levelPhase();
            assertEquals(1, bodies.get());
            assertTrue(observations.get() > 0);
            removed.set(true);
            f.levelPhase();
            assertEquals(1, bodies.get());
            assertEquals(List.of(), CanonicalBlockEntityTestSupport.tickerList(f.level));
            f.clean();
        }
    }

    @Test
    void frozenCanonicalPhasePrunesRemovedWithoutQueryingLivePositionsOrTypes() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var live = mock(TickingBlockEntity.class);
            doAnswer(call -> {
                throw new AssertionError("a frozen phase queried a live position");
            }).when(live).getPos();
            doAnswer(call -> {
                throw new AssertionError("a frozen phase queried a live type");
            }).when(live).getType();
            doAnswer(call -> {
                throw new AssertionError("a frozen phase started a body");
            }).when(live).tick();
            final var removed = mock(TickingBlockEntity.class);
            doReturn(true).when(removed).isRemoved();
            f.level.addBlockEntityTicker(live);
            f.level.addBlockEntityTicker(removed);
            f.tickRate.setFrozen(true);
            f.tickRate.tick();
            f.enable(false);
            f.levelPhase();
            assertEquals(List.of(live), CanonicalBlockEntityTestSupport.tickerList(f.level));
            assertFalse((boolean) get(Level.class, f.level, "tickingBlockEntities"));
            f.clean();
        }
    }

    @ParameterizedTest(name = "{displayName} [waves={0}]")
    @ValueSource(booleans = { false, true })
    void freshOnLoadAdditionsRemainPendingUntilTheNextCanonicalPhase(boolean waves) {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.script("A", FIRST, "test:unknown", () -> {});
            final var calls = new AtomicInteger();
            final var fresh = mock(BlockEntity.class);
            doReturn(true).when(fresh).hasLevel();
            doAnswer(call -> {
                calls.incrementAndGet();
                f.foreign("P", SECOND, () -> {});
                return null;
            }).when(fresh).onLoad();
            f.level.addFreshBlockEntities(List.of(fresh));
            f.enable(waves);
            f.levelPhase();
            assertEquals(List.of("A"), f.bodyOrder());
            assertEquals(0, f.cells.get("P").get());
            f.levelPhase();
            assertEquals(List.of("A", "A", "P"), f.bodyOrder());
            assertEquals(1, f.cells.get("P").get());
            assertEquals(1, calls.get());
            f.clean();
        }
    }

    @Test
    void aLaterTickerRemovedByTheSerialFenceIsNotInvokedAndLeavesMembership() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var removed = new AtomicBoolean();
            f.script("U", FIRST, "test:unknown", () -> removed.set(true));
            final var later = mock(TickingBlockEntity.class);
            doReturn(SECOND).when(later).getPos();
            doReturn("test:later").when(later).getType();
            doAnswer(call -> removed.get()).when(later).isRemoved();
            doAnswer(call -> {
                throw new AssertionError("removed suffix body ran");
            }).when(later).tick();
            f.level.addBlockEntityTicker(later);
            f.enable(false);
            f.levelPhase();
            assertFalse(CanonicalBlockEntityTestSupport.tickerList(f.level).contains(later));
            assertEquals(List.of("U"), f.bodyOrder());
            f.clean();
        }
    }

    @Test
    void duplicateCanonicalMembershipIsRejectedBeforeAnyBodyAndFinallyResetsThePhase() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var a = f.script("A", FIRST, "test:unknown", () -> {});
            f.level.addBlockEntityTicker(a);
            f.enable(false);
            assertThrows(IllegalArgumentException.class, f::levelPhase);
            assertEquals(List.of(), f.bodyOrder());
            assertFalse((boolean) get(Level.class, f.level, "tickingBlockEntities"));
            f.clean();
        }
    }
}
