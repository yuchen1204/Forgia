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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockDropCapture;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real Block drop entry points and popResource funnel, with deterministic nested/concurrent loot callbacks. */
@Timeout(40)
class BlockDropCaptureTest {
    private final List<Consumer<BlockDropsEvent>> listeners = new ArrayList<>();
    private RegionThreadingEngine engine;

    @AfterEach
    void cleanup() {
        this.listeners.forEach(NeoForge.EVENT_BUS::unregister);
        if (this.engine != null) {
            this.engine.shutdown();
        }
    }

    @Test
    void twoNativeWorkersNeverMixDropListsOrReturnNullAndConserveEveryItem() throws Exception {
        final ServerLevel level = level();
        final BlockPos firstPos = new BlockPos(1, 64, 1);
        final BlockPos secondPos = new BlockPos(128, 64, 1);
        final ItemEntity firstA = item(2);
        final ItemEntity firstB = item(3);
        final ItemEntity secondA = item(5);
        final ItemEntity secondB = item(7);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch secondStarted = new CountDownLatch(1);
        final CountDownLatch firstFinished = new CountDownLatch(1);
        final BlockState first = state(() -> {
            emit(level, firstA);
            firstStarted.countDown();
            await(secondStarted);
            emit(level, firstB);
        });
        final BlockState second = state(() -> {
            await(firstStarted);
            emit(level, secondA);
            secondStarted.countDown();
            await(firstFinished);
            emit(level, secondB);
        });
        final Map<BlockPos, List<ItemEntity>> events = new ConcurrentHashMap<>();
        this.listen(event -> {
            if (event.getLevel() == level) {
                assertNotNull(event.getDrops(), "each completed capture must have its own non-null list");
                events.put(event.getPos(), List.copyOf(event.getDrops()));
                if (event.getPos().equals(firstPos)) {
                    firstFinished.countDown();
                }
            }
        });
        final List<ItemEntity> admitted = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        final CompletableFuture<Void> a = this.worker(() -> Block.dropResources(first, level, firstPos));
        final CompletableFuture<Void> b = this.worker(() -> Block.dropResources(second, level, secondPos));
        a.get(20, TimeUnit.SECONDS);
        b.get(20, TimeUnit.SECONDS);
        assertEquals(List.of(firstA, firstB), events.get(firstPos));
        assertEquals(List.of(secondA, secondB), events.get(secondPos));
        assertEquals(4, admitted.size());
        for (final ItemEntity entity : List.of(firstA, firstB, secondA, secondB)) {
            assertEquals(1L, admitted.stream().filter(candidate -> candidate == entity).count());
        }
        assertEquals(17, admitted.stream().mapToInt(entity -> entity.getItem().getCount()).sum());
        assertFalse(BlockDropCapture.capture(item(1)), "worker capture state must not spill into the server thread");
    }

    @Test
    void nestedLootRestoresTheOuterCaptureAcrossAllThreeDropResourcesOverloads() {
        final ServerLevel level = level();
        final BlockPos outerPos = new BlockPos(1, 64, 1);
        final BlockPos middlePos = new BlockPos(2, 64, 1);
        final BlockPos innerPos = new BlockPos(3, 64, 1);
        final ItemEntity before = item(2);
        final ItemEntity after = item(3);
        final ItemEntity middleItem = item(5);
        final ItemEntity innerItem = item(7);
        final BlockState inner = state(() -> emit(level, innerItem));
        final BlockState middle = state(() -> {
            emit(level, middleItem);
            Block.dropResources(inner, level, innerPos, null, null, ItemStack.EMPTY);
        });
        final BlockState outer = state(() -> {
            emit(level, before);
            Block.dropResources(middle, (LevelAccessor) level, middlePos, null);
            emit(level, after);
        });
        final Map<BlockPos, List<ItemEntity>> events = new ConcurrentHashMap<>();
        final List<BlockPos> order = new ArrayList<>();
        final List<ItemEntity> admitted = new ArrayList<>();
        this.listen(event -> {
            if (event.getLevel() == level) {
                order.add(event.getPos());
                events.put(event.getPos(), List.copyOf(event.getDrops()));
            }
        });
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        Block.dropResources(outer, level, outerPos);
        assertEquals(List.of(innerPos, middlePos, outerPos), order);
        assertEquals(List.of(innerItem), events.get(innerPos));
        assertEquals(List.of(middleItem), events.get(middlePos));
        assertEquals(List.of(before, after), events.get(outerPos));
        assertEquals(17, admitted.stream().mapToInt(entity -> entity.getItem().getCount()).sum());
        assertEquals(4, admitted.size());
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    @Test
    void nestedCancelledDropEventDoesNotCancelOrStealTheOuterDrops() {
        final ServerLevel level = level();
        final BlockPos outerPos = new BlockPos(1, 64, 1);
        final BlockPos innerPos = new BlockPos(2, 64, 1);
        final ItemEntity outerItem = item(4);
        final ItemEntity innerItem = item(9);
        final BlockState inner = state(() -> emit(level, innerItem));
        final BlockState outer = state(() -> {
            emit(level, outerItem);
            Block.dropResources(inner, level, innerPos);
        });
        this.listen(event -> {
            if (event.getLevel() == level && event.getPos().equals(innerPos)) {
                assertEquals(List.of(innerItem), event.getDrops());
                event.setCanceled(true);
            }
        });
        final List<ItemEntity> admitted = new ArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        Block.dropResources(outer, level, outerPos);
        assertEquals(List.of(outerItem), admitted);
        verify(inner, never()).spawnAfterBreak(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    @Test
    void lootFailureCleansEveryEntryPointAndDoesNotCaptureTheNextStandaloneDrop() {
        for (int overload = 0; overload < 3; overload++) {
            final ServerLevel level = level();
            final ItemEntity partial = item(5);
            final ItemEntity later = item(8);
            final IllegalStateException failure = new IllegalStateException("loot callback failed");
            final BlockState state = state(() -> {
                emit(level, partial);
                throw failure;
            });
            final BlockPos pos = new BlockPos(overload, 64, 1);
            final int variant = overload;
            assertSame(failure, assertThrows(IllegalStateException.class, () -> {
                switch (variant) {
                    case 0 -> Block.dropResources(state, level, pos);
                    case 1 -> Block.dropResources(state, (LevelAccessor) level, pos, null);
                    case 2 -> Block.dropResources(state, level, pos, null, null, ItemStack.EMPTY);
                    default -> throw new AssertionError();
                }
            }));
            verify(level, never()).addFreshEntity(partial);
            emit(level, later);
            verify(level).addFreshEntity(later);
            assertFalse(BlockDropCapture.capture(item(1)));
        }
    }

    @Test
    void caughtNestedLootFailureRestoresTheParentInsteadOfLeakingPartialItems() {
        final ServerLevel level = level();
        final ItemEntity first = item(2);
        final ItemEntity last = item(3);
        final ItemEntity interrupted = item(99);
        final IllegalArgumentException failure = new IllegalArgumentException("nested loot failed");
        final BlockState inner = state(() -> {
            emit(level, interrupted);
            throw failure;
        });
        final BlockState outer = state(() -> {
            emit(level, first);
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> Block.dropResources(inner, level, new BlockPos(2, 64, 1))));
            emit(level, last);
        });
        final List<ItemEntity> admitted = new ArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        Block.dropResources(outer, level, new BlockPos(1, 64, 1));
        assertEquals(List.of(first, last), admitted);
        assertEquals(5, admitted.stream().mapToInt(entity -> entity.getItem().getCount()).sum());
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    @Test
    void throwingEventListenerDoesNotLeakItsFinishedCaptureIntoLaterCalls() {
        final ServerLevel level = level();
        final BlockPos failedPos = new BlockPos(1, 64, 1);
        final ItemEntity blocked = item(5);
        final ItemEntity later = item(7);
        final IllegalStateException failure = new IllegalStateException("drop event failed");
        final BlockState state = state(() -> emit(level, blocked));
        this.listen(event -> {
            if (event.getLevel() == level && event.getPos().equals(failedPos)) {
                throw failure;
            }
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> Block.dropResources(state, level, failedPos)));
        verify(level, never()).addFreshEntity(blocked);
        emit(level, later);
        verify(level).addFreshEntity(later);
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    @Test
    void eventAndAfterBreakStandaloneDropsStayOutsideTheSuspendedParentLootCapture() {
        final ServerLevel level = level();
        final BlockPos outerPos = new BlockPos(1, 64, 1);
        final BlockPos innerPos = new BlockPos(2, 64, 1);
        final ItemEntity before = item(2);
        final ItemEntity after = item(3);
        final ItemEntity nested = item(5);
        final ItemEntity eventExtra = item(7);
        final ItemEntity afterBreakExtra = item(11);
        final BlockState inner = state(() -> emit(level, nested));
        doAnswer(call -> {
            emit(level, afterBreakExtra);
            return null;
        })
                .when(inner).spawnAfterBreak(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        final BlockState outer = state(() -> {
            emit(level, before);
            Block.dropResources(inner, level, innerPos);
            emit(level, after);
        });
        final Map<BlockPos, List<ItemEntity>> events = new ConcurrentHashMap<>();
        this.listen(event -> {
            if (event.getLevel() == level) {
                events.put(event.getPos(), List.copyOf(event.getDrops()));
                if (event.getPos().equals(innerPos)) emit(level, eventExtra);
            }
        });
        final List<ItemEntity> admitted = new ArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        Block.dropResources(outer, level, outerPos);
        assertEquals(List.of(nested), events.get(innerPos));
        assertEquals(List.of(before, after), events.get(outerPos),
                "nested event/after-break extras must not be pulled into a different block's event");
        assertEquals(List.of(eventExtra, nested, afterBreakExtra, before, after), admitted);
        assertEquals(28, admitted.stream().mapToInt(entity -> entity.getItem().getCount()).sum());
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    @Test
    void eventCallbackCanBreakAnotherBlockWithoutCorruptingTheRemainingDrops() {
        final ServerLevel level = level();
        final BlockPos firstPos = new BlockPos(1, 64, 1);
        final BlockPos nestedPos = new BlockPos(2, 64, 1);
        final ItemEntity first = item(2);
        final ItemEntity second = item(3);
        final BlockState state = state(() -> emit(level, first));
        final BlockState nested = state(() -> emit(level, second));
        final List<ItemEntity> admitted = new ArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        this.listen(event -> {
            if (event.getLevel() == level && event.getPos().equals(firstPos)) {
                Block.dropResources(nested, level, nestedPos);
                assertEquals(List.of(first), event.getDrops());
            }
        });
        Block.dropResources(state, level, firstPos);
        assertEquals(List.of(second, first), admitted);
        assertEquals(5, admitted.stream().mapToInt(entity -> entity.getItem().getCount()).sum());
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    @Test
    void aWorkerReusedAfterLootFailureHasNoStaleCaptureOrMisroutedLaterItems() throws Exception {
        final ServerLevel level = level();
        final ItemEntity partial = item(99);
        final ItemEntity standalone = item(2);
        final ItemEntity next = item(3);
        final IllegalStateException failure = new IllegalStateException("first loot failed");
        final BlockState failed = state(() -> {
            emit(level, partial);
            throw failure;
        });
        final BlockState healthy = state(() -> emit(level, next));
        final List<ItemEntity> admitted = new CopyOnWriteArrayList<>();
        final List<List<ItemEntity>> events = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        this.listen(event -> {
            if (event.getLevel() == level) events.add(List.copyOf(event.getDrops()));
        });
        this.worker(() -> {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> Block.dropResources(failed, level, new BlockPos(1, 64, 1))));
            emit(level, standalone);
            Block.dropResources(healthy, level, new BlockPos(2, 64, 1));
            assertFalse(BlockDropCapture.capture(item(1)));
        }).get(20, TimeUnit.SECONDS);
        assertEquals(List.of(standalone, next), admitted);
        assertEquals(List.of(List.of(next)), events);
        assertEquals(5, admitted.stream().mapToInt(entity -> entity.getItem().getCount()).sum());
    }

    @Test
    void aCaughtNestedEventFailureRestoresTheParentLootCapture() {
        final ServerLevel level = level();
        final BlockPos innerPos = new BlockPos(2, 64, 1);
        final ItemEntity before = item(2);
        final ItemEntity after = item(3);
        final ItemEntity blocked = item(99);
        final IllegalArgumentException failure = new IllegalArgumentException("nested event failed");
        final BlockState inner = state(() -> emit(level, blocked));
        this.listen(event -> {
            if (event.getLevel() == level && event.getPos().equals(innerPos)) throw failure;
        });
        final BlockState outer = state(() -> {
            emit(level, before);
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> Block.dropResources(inner, level, innerPos)));
            emit(level, after);
        });
        final List<ItemEntity> admitted = new ArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        Block.dropResources(outer, level, new BlockPos(1, 64, 1));
        assertEquals(List.of(before, after), admitted);
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    @Test
    void eventMayReplaceItsMutableDropListContentsWithoutReplayingOriginalItems() {
        final ServerLevel level = level();
        final ItemEntity generated = item(5);
        final ItemEntity replacement = item(7);
        final BlockState state = state(() -> emit(level, generated));
        this.listen(event -> {
            if (event.getLevel() == level) {
                assertEquals(List.of(generated), event.getDrops());
                event.getDrops().clear();
                event.getDrops().add(replacement);
            }
        });
        final List<ItemEntity> admitted = new ArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        Block.dropResources(state, level, new BlockPos(1, 64, 1));
        assertEquals(List.of(replacement), admitted, "the existing mutable event contract must stay authoritative");
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    @Test
    void captureCloseRejectsWrongThreadAndWrongNestingWithoutDestroyingTheOwningScopes() throws Exception {
        BlockDropCapture.begin();
        final BlockDropCapture.Scope outer = BlockDropCapture.currentOrThrow();
        final ItemEntity first = item(2);
        final ItemEntity second = item(3);
        try {
            assertTrue(BlockDropCapture.capture(first));
            BlockDropCapture.begin();
            final BlockDropCapture.Scope inner = BlockDropCapture.currentOrThrow();
            try {
                assertThrows(IllegalStateException.class, outer::close);
                final Throwable refused = this.workerFailure(inner::close).get(20, TimeUnit.SECONDS);
                assertTrue(refused instanceof IllegalStateException);
                assertTrue(BlockDropCapture.capture(second));
                assertEquals(List.of(second), BlockDropCapture.end());
                assertFalse(BlockDropCapture.capture(item(7)), "events must not capture into the suspended parent");
            } finally {
                inner.close();
            }
            assertEquals(List.of(first), BlockDropCapture.end());
        } finally {
            outer.close();
        }
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    @Test
    void afterBreakFailurePreservesAlreadyAdmittedDropsWithoutReplayAndRestoresCapture() {
        final ServerLevel level = level();
        final ItemEntity generated = item(5);
        final ItemEntity later = item(7);
        final BlockState state = state(() -> emit(level, generated));
        final IllegalStateException failure = new IllegalStateException("after-break callback failed");
        doAnswer(call -> {
            throw failure;
        }).when(state).spawnAfterBreak(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        final List<ItemEntity> admitted = new ArrayList<>();
        doAnswer(call -> {
            admitted.add(call.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> Block.dropResources(state, level, new BlockPos(1, 64, 1))));
        assertEquals(List.of(generated), admitted, "a failure after admission must not cause replay or rollback");
        emit(level, later);
        assertEquals(List.of(generated, later), admitted);
        assertFalse(BlockDropCapture.capture(item(1)));
    }

    private CompletableFuture<Throwable> workerFailure(Runnable task) throws ReflectiveOperationException {
        final CompletableFuture<Throwable> result = new CompletableFuture<>();
        this.worker(() -> {
            try {
                task.run();
                result.complete(new AssertionError("expected the wrong-thread close to fail"));
            } catch (Throwable failure) {
                result.complete(failure);
            }
        });
        return result;
    }

    private static ServerLevel level() {
        final ServerLevel level = mock(ServerLevel.class);
        final GameRules rules = mock(GameRules.class);
        when(rules.getBoolean(GameRules.RULE_DOBLOCKDROPS)).thenReturn(true);
        when(level.getGameRules()).thenReturn(rules);
        return level;
    }

    private static BlockState state(Runnable loot) {
        final BlockState state = mock(BlockState.class);
        doAnswer(call -> {
            loot.run();
            return List.of();
        }).when(state).getDrops(any(LootParams.Builder.class));
        return state;
    }

    private static ItemEntity item(int count) {
        final ItemEntity entity = mock(ItemEntity.class);
        when(entity.getItem()).thenReturn(new ItemStack(Items.COBBLESTONE, count));
        return entity;
    }

    private static void emit(ServerLevel level, ItemEntity item) {
        try {
            final Method method = Block.class.getDeclaredMethod("popResource", Level.class, Supplier.class, ItemStack.class);
            method.setAccessible(true);
            method.invoke(null, level, (Supplier<ItemEntity>) () -> item, item.getItem());
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private void listen(Consumer<BlockDropsEvent> listener) {
        NeoForge.EVENT_BUS.addListener(BlockDropsEvent.class, listener);
        this.listeners.add(listener);
    }

    private CompletableFuture<Void> worker(Runnable work) throws ReflectiveOperationException {
        if (this.engine == null) this.engine = new RegionThreadingEngine(2, false, false);
        final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
        field.setAccessible(true);
        final RegionWorkerExecutor executor = (RegionWorkerExecutor) field.get(this.engine);
        final CompletableFuture<Void> done = new CompletableFuture<>();
        assertTrue(executor.execute("drop capture regression", () -> {
            try {
                assertTrue(Thread.currentThread().getName().startsWith("NeoForge Region Worker"));
                work.run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        }));
        return done;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the deterministic drop interleave did not finish");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
