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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original Hopper bodies, original function tokens and actual production Level/Worker admission. */
@Tag("canonical-be-contract")
@Timeout(120)
class CanonicalBlockEntityNativeAdmissionTest {
    private static final BlockPos FIRST = new BlockPos(20, 64, 20);
    private static final BlockPos SECOND = new BlockPos(68, 64, 20);

    @ParameterizedTest(name = "closed original Hopper branches overlap, disabled={0}")
    @ValueSource(booleans = { false, true })
    void originalClosedBranchesOverlapOnTwoRealRegionWorkers(boolean disabled) throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var queries = new AtomicInteger();
            try (var ignored = capability(f, new ItemStackHandler(0), p -> queries.incrementAndGet())) {
                final var a = hopper(f, "A", FIRST);
                final var b = hopper(f, "B", SECOND);
                for (final var source : List.of(a, b)) {
                    source.setCooldown(disabled ? 0 : 3);
                    if (disabled) {
                        final var state = source.getBlockState().setValue(HopperBlock.ENABLED, false);
                        source.setBlockState(state);
                        f.world.states.put(source.getBlockPos(), state);
                    }
                }
                if (!disabled) b.setCooldown(2);
                System.out.println("C3_A2_RUNTIME verified=" + get(net.neoforged.neoforge.server.threading.region.NativeBlockEntityPreparation.class, null, "VERIFIED")
                        + " reason=" + get(net.neoforged.neoforge.server.threading.region.NativeBlockEntityPreparation.class, null, "runtimeRefusal")
                        + " level=" + f.level.getClass() + " chunk=" + f.chunk(FIRST).getClass() + " border=" + f.level.getWorldBorder().getClass());
                f.enable(false);
                final var entered = new AtomicInteger();
                final var maximum = new AtomicInteger();
                final var both = new CountDownLatch(2);
                f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                    assertTrue(RegionThreadingHooks.isNativeRegionWorkerThread());
                    assertEquals("REGION_OWNER", BlockEntityExecutionEngineTest.currentMode());
                    f.event(f.names.get(ticker.getPos()), "original-hopper-start");
                    maximum.accumulateAndGet(entered.incrementAndGet(), Math::max);
                    both.countDown();
                    try {
                        await(both);
                        body.run();
                    } finally {
                        entered.decrementAndGet();
                    }
                });
                f.levelPhase();
                assertEquals(2, maximum.get());
                assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
                assertEquals(disabled ? 0 : 2, get(HopperBlockEntity.class, a, "cooldownTime"));
                assertEquals(disabled ? 0 : 1, get(HopperBlockEntity.class, b, "cooldownTime"));
                assertEquals(f.level.getGameTime(), get(HopperBlockEntity.class, a, "tickedGameTime"));
                assertEquals(0, queries.get(), "no Capability preflight or provider callback");
                f.clean();
                f.probes();
                System.out.println("C3_A2_ORIGINAL_HOPPER_OVERLAP=" + maximum.get() + " disabled=" + disabled + " " + f.trace());
            }
        }
    }

    @Test
    void aSharedUnknownHandlerIsReachedOnlyByCompleteServerInvocations() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var threads = new ConcurrentLinkedQueue<Thread>();
            final var queries = new AtomicInteger();
            final var root = new ItemStackHandler(0) {
                @Override
                public int getSlots() {
                    threads.add(Thread.currentThread());
                    return super.getSlots();
                }
            };
            try (var ignored = capability(f, root, p -> queries.incrementAndGet())) {
                hopper(f, "A", FIRST);
                hopper(f, "B", SECOND);
                f.enable(false);
                f.levelPhase();
                assertEquals(2, queries.get());
                assertFalse(threads.isEmpty());
                assertTrue(threads.stream().allMatch(t -> t == f.serverThread));
                assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
                f.clean();
                f.probes();
            }
        }
    }

    @Test
    void anExpiredCooldownIsNotUsedAsAWorkerCertificate() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var queries = new AtomicInteger();
            try (var ignored = capability(f, new ItemStackHandler(0), p -> {
                assertSame(f.serverThread, Thread.currentThread());
                queries.incrementAndGet();
            })) {
                final var source = hopper(f, "A", FIRST);
                source.setCooldown(1);
                f.enable(false);
                f.levelPhase();
                assertEquals(1, queries.get());
                assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
                f.clean();
                f.probes();
            }
        }
    }

    @Test
    void aBranchChangedAfterCollectionIsRefusedBeforeItsOriginalBody() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var queries = new AtomicInteger();
            try (var ignored = capability(f, new ItemStackHandler(0), p -> {
                assertSame(f.serverThread, Thread.currentThread());
                queries.incrementAndGet();
            })) {
                final var source = hopper(f, "A", FIRST);
                source.setCooldown(3);
                // This test changes state before dispatch through the controlled fixed executor.
                // Other cases use the selected real fixed/persistent pool without this deterministic seam.
                f.enable(false, false);
                final var normal = f.barrier;
                f.barrier = done -> {
                    source.setCooldown(1);
                    normal.await(done);
                };
                f.levelPhase();
                assertEquals(1, queries.get(), "no original body or provider is replayed");
                assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
                f.clean();
                f.probes();
            }
        }
    }

    @Test
    void aReplacementFunctionNeverInheritsTheOriginalSourceCertificate() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var source = hopper(f, "A", FIRST);
            source.setCooldown(3);
            final var original = source.getBlockState().getTicker(f.level, source.getType());
            final var caller = new ConcurrentLinkedQueue<Thread>();
            final Method bind = CanonicalBlockEntityTestSupport.class.getDeclaredMethod("bound", String.class, BlockEntity.class, BlockEntityTicker.class);
            bind.setAccessible(true);
            // Remove the first membership, then install the same source through an unissued function.
            CanonicalBlockEntityTestSupport.tickerList(f.level).clear();
            invoke(bind, f, "A", source, (BlockEntityTicker<HopperBlockEntity>) (level, pos, state, be) -> {
                caller.add(Thread.currentThread());
                ((BlockEntityTicker<HopperBlockEntity>) original).tick(level, pos, state, be);
            });
            f.enable(false);
            f.levelPhase();
            assertEquals(List.of(f.serverThread), List.copyOf(caller));
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
            f.probes();
        }
    }

    private static HopperBlockEntity hopper(CanonicalBlockEntityTestSupport f, String id, BlockPos position) throws Exception {
        final Method factory = CanonicalBlockEntityNativeInventoryTest.class.getDeclaredMethod("hopper", CanonicalBlockEntityTestSupport.class, String.class, BlockPos.class);
        factory.setAccessible(true);
        return (HopperBlockEntity) invoke(factory, null, f, id, position);
    }

    private static AutoCloseable capability(CanonicalBlockEntityTestSupport f, IItemHandler root, Consumer<BlockPos> observed) throws Exception {
        final Method factory = CanonicalBlockEntityNativeInventoryTest.class.getDeclaredMethod("capability", CanonicalBlockEntityTestSupport.class, IItemHandler.class, Consumer.class);
        factory.setAccessible(true);
        return (AutoCloseable) invoke(factory, null, f, root, observed);
    }
}
