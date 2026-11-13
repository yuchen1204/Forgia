/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.FurnaceWorkScope;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickCompatibility;

/**
 * Test-only world seams around the actual Level loop, chunk index/wrappers, bound original furnace ticker
 * and engine. There is no test coordinator or dependency planner. Script tickers mutate only their own cell;
 * unknown server callbacks may inspect/change the fixture, as a complete serial fence must permit.
 */
final class CanonicalBlockEntityTestSupport implements AutoCloseable {
    final Thread serverThread = Thread.currentThread();
    final MinecraftServer server = mock(MinecraftServer.class);
    final RegionRedstoneWorld world = new RegionRedstoneWorld(new AtomicLong());
    final ServerLevel level = this.world.level;
    final RecipeManager recipes = new RecipeManager(RegistryAccess.EMPTY);
    final TickRateManager tickRate = new TickRateManager();
    final Map<String, AtomicInteger> cells = new ConcurrentHashMap<>();
    final Map<String, AbstractFurnaceBlockEntity> furnaces = new LinkedHashMap<>();
    final Map<String, TickingBlockEntity> tickers = new LinkedHashMap<>();
    final Map<BlockPos, String> names = new HashMap<>();
    final List<Event> events = new ArrayList<>();
    final CountDownLatch lifecycleWriteHeld = new CountDownLatch(1);
    final Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
    final Map<Long, Boolean> eligible = new ConcurrentHashMap<>();
    final Set<Object> observedBatches = ConcurrentHashMap.newKeySet();
    final ThreadLocal<String> currentBody = new ThreadLocal<>();
    private boolean phaseStarted;
    Consumer<String> onStart = name -> {};
    Consumer<String> onChange = name -> {};
    final Map<String, Object> observedTickets = new ConcurrentHashMap<>();
    RegionTickBarrier barrier = done -> {
        while (!done.getAsBoolean()) Thread.onSpinWait();
    };
    RegionThreadingEngine engine;
    ControlledExecutor controlled;

    CanonicalBlockEntityTestSupport() {
        assertNull(RegionThreadingManager.currentOrNull(), "fixtures must never replace another installed engine");
        set(Level.class, this.level, "blockEntityTickerLock", new Object());
        for (String name : List.of("blockEntityTickers", "pendingBlockEntityTickers", "foreignBlockEntityTickers", "freshBlockEntities", "pendingFreshBlockEntities")) {
            set(Level.class, this.level, name, new ArrayList<>());
        }
        set(Level.class, this.level, "blockEntityTickerChunks", new Long2ObjectLinkedOpenHashMap<LevelChunk>());
        set(Level.class, this.level, "blockEntityTickerSequence", new AtomicLong());
        doCallRealMethod().when(this.level).addBlockEntityTicker(any());
        doCallRealMethod().when(this.level).addFreshBlockEntities(any());
        doCallRealMethod().when(this.level).registerBlockEntityTickerChunk(any());
        doCallRealMethod().when(this.level).unregisterBlockEntityTickerChunk(any());
        doCallRealMethod().when(this.level).collectBlockEntityTickerChunks(any());
        doCallRealMethod().when(this.level).collectForeignBlockEntityTickers(any());
        doCallRealMethod().when(this.level).nextBlockEntityTickerSequence();
        doCallRealMethod().when(this.level).markBlockEntityTickerRemoved();
        invoke(phaseMethod(), doCallRealMethod().when(this.level));
        doReturn(this.server).when(this.level).getServer();
        doAnswer(call -> Thread.currentThread() == this.serverThread).when(this.server).isSameThread();
        doReturn(this.tickRate).when(this.level).tickRateManager();
        doReturn(true).when(this.level).areEntitiesLoaded(anyLong());
        doAnswer(call -> this.eligible.getOrDefault(call.getArgument(0), true)).when(this.level).shouldTickBlocksAt(anyLong());
        doAnswer(call -> this.eligible.getOrDefault(ChunkPos.asLong((BlockPos) call.getArgument(0)), true)).when(this.level).shouldTickBlocksAt(any(BlockPos.class));
        doReturn(RegistryAccess.EMPTY).when(this.level).registryAccess();
        doReturn(this.recipes).when(this.level).getRecipeManager();
        final var chunkSource = this.level.getChunkSource();
        doAnswer(call -> this.chunks.get(ChunkPos.asLong(call.getArgument(0), call.getArgument(1))))
                .when(chunkSource).getChunkNow(anyInt(), anyInt());
        doAnswer(call -> {
            this.barrier.await(call.getArgument(0));
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        // The real engine explicitly refuses a shared active profiler. Keep that gate intact.
        doReturn(InactiveProfiler.INSTANCE).when(this.level).getProfiler();
        doAnswer(call -> {
            final String id = this.names.getOrDefault(call.getArgument(0), "?");
            this.event(id, "change-start");
            this.onChange.accept(id);
            this.event(id, "changed");
            return null;
        }).when(this.level).blockEntityChanged(any());
        this.replaceRecipe(2);
    }

    void enable(boolean waves) {
        this.enable(waves, Boolean.getBoolean("forgia.furnace.persistentWorkers"));
    }

    void enable(boolean waves, boolean persistent) {
        this.engine = new RegionThreadingEngine(2, false, false, false, true, false, false, false,
                persistent, false,
                RegionLayout.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        this.engine.setFurnaceWorkWavesEnabledForTesting(waves);
        this.engine.setBlockEntityResourcesEnabledForTesting(Boolean.getBoolean("forgia.furnace.resourceOwnership"));
        set(RegionThreadingEngine.class, this.engine, "lifecycleGate", new ReentrantReadWriteLock() {
            private final WriteLock observed = new WriteLock(this) {
                @Override
                public void lock() {
                    super.lock();
                    event("phase", "lifecycle-write-held");
                    lifecycleWriteHeld.countDown();
                }
            };

            @Override
            public WriteLock writeLock() {
                return this.observed;
            }
        });
        final Object actualPool = get(RegionThreadingEngine.class, this.engine, "workerPool");
        assertEquals(persistent, get(actualPool.getClass(), actualPool, "workers") != null, "fixture must use the selected real pool");
        if (!persistent) {
            final Object pool = actualPool;
            final var executor = (ExecutorService) get(pool.getClass(), pool, "executor");
            this.controlled = new ControlledExecutor(executor, this);
            set(pool.getClass(), pool, "executor", this.controlled);
        }
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            final String id = this.names.get(source.getBlockPos());
            // Observe the existing original-body tail; always retain its original metrics callback.
            final var current = (ThreadLocal<?>) get(FurnaceWorkScope.class, null, "CURRENT");
            final Object scope = current.get();
            final Runnable original = (Runnable) get(FurnaceWorkScope.class, scope, "bodyEndObserver");
            set(FurnaceWorkScope.class, scope, "bodyEndObserver", (Runnable) () -> {
                try {
                    original.run();
                } finally {
                    this.event(id, "body-end");
                    this.currentBody.remove();
                }
            });
            this.start(id);
        });
        RegionThreadingManager.install(this.engine);
        this.barrier = done -> {
            if (this.controlled != null) this.controlled.dispatchAll();
            while (!done.getAsBoolean()) Thread.onSpinWait();
        };
        this.event("phase", persistent ? "persistent-pool" : "controlled-fixed-pool");
    }

    void register(BlockEntityTickAdapter adapter) {
        ParallelAdapterRuntime.initialize(this.server, registrar -> registrar.registerBlockEntityTicks(1,
                ResourceLocation.parse("test:canonical_generic"), Set.of(ResourceLocation.parse("minecraft:chest")), adapter));
    }

    LevelChunk chunk(BlockPos pos) {
        return this.chunks.computeIfAbsent(ChunkPos.asLong(pos), key -> {
            final var chunk = mock(LevelChunk.class, CALLS_REAL_METHODS);
            set(LevelChunk.class, chunk, "level", this.level);
            set(LevelChunk.class, chunk, "tickersInLevel", new HashMap<BlockPos, TickingBlockEntity>());
            doReturn(this.level).when(chunk).getLevel();
            doReturn(new ChunkPos(key)).when(chunk).getPos();
            doReturn(true).when(chunk).isLoaded();
            doReturn(FullChunkStatus.BLOCK_TICKING).when(chunk).getFullStatus();
            doReturn(this.world.blockEntities).when(chunk).getBlockEntities();
            doAnswer(call -> this.world.blockEntities.get(call.getArgument(0))).when(chunk).getBlockEntity(any(), any(LevelChunk.EntityCreationType.class));
            doAnswer(call -> {
                final BlockPos at = call.getArgument(0);
                return this.world.states.getOrDefault(at, Blocks.AIR.defaultBlockState());
            }).when(chunk).getBlockState(any());
            return chunk;
        });
    }

    TickingBlockEntity script(String id, BlockPos pos, String type, Runnable action) {
        return this.wrap(id, this.chunk(pos), this.recordingTicker(id, pos, type, action));
    }

    private TickingBlockEntity recordingTicker(String id, BlockPos pos, String type, Runnable action) {
        this.names.put(pos, id);
        this.cells.put(id, new AtomicInteger());
        return new TickingBlockEntity() {
            @Override
            public void tick() {
                start(id);
                try {
                    cells.get(id).incrementAndGet();
                    action.run();
                } finally {
                    event(id, "body-end");
                    currentBody.remove();
                }
            }

            @Override
            public boolean isRemoved() {
                return false;
            }

            @Override
            public BlockPos getPos() {
                return pos;
            }

            @Override
            public String getType() {
                return type;
            }
        };
    }

    TickingBlockEntity foreign(String id, BlockPos pos, Runnable action) {
        // No chunk wrapper or creation sequence is manufactured for a genuine foreign ticker.
        this.chunk(pos);
        final TickingBlockEntity foreign = this.recordingTicker(id, pos, "test:foreign", action);
        this.level.addBlockEntityTicker(foreign);
        this.tickers.put(id, foreign);
        return foreign;
    }

    TickingBlockEntity generic(String id, BlockPos pos, Runnable action) {
        this.names.put(pos, id);
        this.cells.put(id, new AtomicInteger());
        final var source = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        source.setLevel(this.level);
        this.world.blockEntities.put(pos, source);
        this.world.states.put(pos, source.getBlockState());
        final BlockEntityTicker<ChestBlockEntity> body = (level, at, state, be) -> {
            this.start(id);
            try {
                this.cells.get(id).incrementAndGet();
                action.run();
            } finally {
                this.event(id, "body-end");
                this.currentBody.remove();
            }
        };
        return this.bound(id, source, body);
    }

    TickingBlockEntity furnace(String id, BlockPos pos, int progress) {
        this.names.put(pos, id);
        final var state = Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.LIT, true);
        final var source = new net.minecraft.world.level.block.entity.FurnaceBlockEntity(pos, state);
        source.setLevel(this.level);
        this.world.blockEntities.put(pos, source);
        this.world.states.put(pos, state);
        source.setItem(0, new ItemStack(Items.IRON_ORE, 3));
        source.setItem(1, new ItemStack(Items.COAL));
        set(AbstractFurnaceBlockEntity.class, source, "litTime", 100);
        set(AbstractFurnaceBlockEntity.class, source, "litDuration", 100);
        set(AbstractFurnaceBlockEntity.class, source, "cookingProgress", progress);
        set(AbstractFurnaceBlockEntity.class, source, "cookingTotalTime", 2);
        ((Map<?, ?>) get(AbstractFurnaceBlockEntity.class, source, "recipesUsed")).clear();
        this.furnaces.put(id, source);
        for (final var direction : net.minecraft.core.Direction.values()) {
            for (int distance = 1; distance <= 2; distance++) this.chunk(pos.relative(direction, distance));
        }
        return this.bound(id, source, state.getTicker(this.level, source.getType()));
    }

    private TickingBlockEntity bound(String id, BlockEntity source, BlockEntityTicker<?> body) {
        try {
            final Method factory = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
            factory.setAccessible(true);
            final LevelChunk chunk = this.chunk(source.getBlockPos());
            return this.wrap(id, chunk, (TickingBlockEntity) invoke(factory, chunk, source, body));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private TickingBlockEntity wrap(String id, LevelChunk chunk, TickingBlockEntity delegate) {
        try {
            final var type = Class.forName("net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper");
            final var constructor = type.getDeclaredConstructor(LevelChunk.class, TickingBlockEntity.class);
            constructor.setAccessible(true);
            final var wrapper = (TickingBlockEntity) constructor.newInstance(chunk, delegate);
            tickerMap(chunk).put(delegate.getPos(), wrapper);
            this.level.addBlockEntityTicker(wrapper);
            this.level.registerBlockEntityTickerChunk(chunk);
            chunk.bumpBlockEntityTickerRevision();
            this.tickers.put(id, wrapper);
            return wrapper;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    void replaceRecipe(int time) {
        this.recipes.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:canonical_iron"),
                new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0, time))));
    }

    /** Calls the actual Level loop. With no engine, transparent observers forward to original furnace tickers. */
    void levelPhase() {
        this.prepareTrace();
        final var replacements = new java.util.IdentityHashMap<TickingBlockEntity, TickingBlockEntity>();
        if (this.engine == null) {
            final List<TickingBlockEntity> list = tickerList(this.level);
            for (int index = 0; index < list.size(); index++) {
                final TickingBlockEntity original = list.get(index);
                final String id = this.names.get(original.getPos());
                if (!this.furnaces.containsKey(id)) continue;
                final TickingBlockEntity observer = new TickingBlockEntity() {
                    @Override
                    public void tick() {
                        start(id);
                        try {
                            original.tick();
                        } finally {
                            event(id, "body-end");
                            currentBody.remove();
                        }
                    }

                    @Override
                    public boolean isRemoved() {
                        return original.isRemoved();
                    }

                    @Override
                    public BlockPos getPos() {
                        return original.getPos();
                    }

                    @Override
                    public String getType() {
                        return original.getType();
                    }
                };
                replacements.put(observer, original);
                list.set(index, observer);
            }
        }
        try {
            invoke(phaseMethod(), this.level);
        } finally {
            tickerList(this.level).replaceAll(ticker -> replacements.getOrDefault(ticker, ticker));
        }
    }

    private synchronized void prepareTrace() {
        if (!this.phaseStarted) {
            this.events.clear();
            this.phaseStarted = true;
        }
        if (this.engine == null) this.event("phase", "vanilla-serial");
        else {
            final Object pool = get(RegionThreadingEngine.class, this.engine, "workerPool");
            this.event("phase", get(pool.getClass(), pool, "workers") == null ? "controlled-fixed-pool" : "persistent-pool");
        }
    }

    /** Isolates driver order from the chunk/foreign collection problem without constructing a test scheduler. */
    void explicitPhase(String... ids) {
        this.prepareTrace();
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(this.level, this.barrier);
        assertNotNull(plan);
        for (String id : ids) assertTrue(plan.accept(this.tickers.get(id)), id);
        plan.executeAndAwait();
    }

    private void start(String id) {
        assertNotNull(id, "every actual body must belong to the independently constructed fixture");
        this.currentBody.set(id);
        this.event(id, "body-start");
        if (this.engine != null) {
            this.observedBatches.addAll((Set<?>) get(RegionThreadingEngine.class, this.engine, "activeTickBatches"));
            for (Object batch : this.observedBatches) {
                final Object execution = get(batch.getClass().getSuperclass(), batch, "execution");
                final Map<?, ?> tickets = (Map<?, ?>) get(execution.getClass(), execution, "tickets");
                this.tickers.forEach((name, ticker) -> {
                    if (tickets.containsKey(ticker)) this.observedTickets.put(name, tickets.get(ticker));
                });
            }
        }
        this.onStart.accept(id);
    }

    synchronized void event(String id, String action) {
        this.events.add(new Event(this.events.size(), id, action, Thread.currentThread().getName(),
                RegionThreadingHooks.isNativeRegionWorkerThread(), BlockEntityExecutionEngineTest.currentMode()));
    }

    synchronized List<Event> trace() {
        return List.copyOf(this.events);
    }

    List<String> bodyOrder() {
        return this.trace().stream().filter(e -> e.action.equals("body-start")).map(Event::id).toList();
    }

    void before(String first, String action, String later, String laterAction) {
        final var trace = this.trace();
        final var from = trace.stream().filter(e -> e.id.equals(first) && e.action.equals(action)).findFirst().orElseThrow();
        final var to = trace.stream().filter(e -> e.id.equals(later) && e.action.equals(laterAction)).findFirst().orElseThrow();
        assertTrue(from.index < to.index, () -> from + " must happen before " + to + "\n" + trace);
    }

    Map<String, String> state() {
        final var result = new LinkedHashMap<String, String>();
        this.cells.forEach((id, value) -> result.put(id, Integer.toString(value.get())));
        this.furnaces.forEach((id, source) -> result.put(id,
                get(AbstractFurnaceBlockEntity.class, source, "litTime") + ":"
                        + get(AbstractFurnaceBlockEntity.class, source, "cookingProgress") + ":"
                        + get(AbstractFurnaceBlockEntity.class, source, "cookingTotalTime") + ":"
                        + source.getItem(0) + source.getItem(0).getComponents().toString() + ":"
                        + source.getItem(1) + source.getItem(1).getComponents().toString() + ":"
                        + source.getItem(2) + source.getItem(2).getComponents().toString() + ":"
                        + get(AbstractFurnaceBlockEntity.class, source, "recipesUsed") + ":" + source.getBlockState()));
        return result;
    }

    void clean() {
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertTrue(((Set<?>) get(RegionThreadingEngine.class, this.engine, "activeTickBatches")).isEmpty());
        assertFalse(FurnaceWorkScope.active());
        assertFalse(BlockEntityResourceOwnership.Scope.active());
        assertNull(BlockEntityExecutionEngineTest.currentMode());
        for (Object batch : this.observedBatches) {
            final Object execution = get(batch.getClass().getSuperclass(), batch, "execution");
            assertTrue(((Map<?, ?>) get(execution.getClass(), execution, "tickets")).isEmpty());
            assertTrue(((Set<?>) get(execution.getClass(), execution, "guards")).isEmpty());
            assertEquals(0, ((AtomicInteger) get(execution.getClass(), execution, "active")).get());
            assertEquals(false, get(execution.getClass(), execution, "open"));
        }
    }

    void probes() {
        final var entered = new CountDownLatch(2);
        final var release = new CountDownLatch(1);
        final var returned = new CountDownLatch(2);
        final var failure = new AtomicReference<Throwable>();
        final Object pool = get(RegionThreadingEngine.class, this.engine, "workerPool");
        try {
            final Method execute = pool.getClass().getDeclaredMethod("execute", String.class, Runnable.class);
            execute.setAccessible(true);
            for (int i = 0; i < 2; i++) assertEquals(true, invoke(execute, pool, "canonical cleanup probe", (Runnable) () -> {
                try {
                    assertNull(BlockEntityExecutionEngineTest.currentMode());
                    assertFalse(FurnaceWorkScope.active());
                    assertFalse(BlockEntityResourceOwnership.Scope.active());
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    entered.countDown();
                    await(release);
                    returned.countDown();
                }
            }));
            if (this.controlled != null) this.controlled.dispatchAll();
            await(entered);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        } finally {
            release.countDown();
        }
        await(returned);
        assertNull(failure.get());
    }

    @Override
    public void close() {
        if (this.controlled != null) this.controlled.dispatchAll();
        RegionThreadingManager.clear();
        ParallelAdapterRuntime.close(this.server);
        RegionTickCompatibility.declareServerThreadOnlyTypes(Map.of());
        for (LevelChunk chunk : this.chunks.values()) chunk.setBlockEntityTickAdmissionCache(null);
        this.currentBody.remove();
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "event latch did not arrive");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    static Object get(Class<?> owner, Object target, String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    static void set(Class<?> owner, Object target, String name, Object value) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Method phaseMethod() {
        try {
            final Method method = Level.class.getDeclaredMethod("tickBlockEntities");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @SuppressWarnings("unchecked")
    static List<TickingBlockEntity> tickerList(ServerLevel level) {
        return (List<TickingBlockEntity>) get(Level.class, level, "blockEntityTickers");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Map<BlockPos, TickingBlockEntity> tickerMap(LevelChunk chunk) {
        return (Map) get(LevelChunk.class, chunk, "tickersInLevel");
    }

    record Event(int index, String id, String action, String thread, boolean worker, String mode) {}

    /** Defers delivery but retains the real pool's worker creation, registration, scope and shutdown. */
    static final class ControlledExecutor extends AbstractExecutorService {
        final ExecutorService delegate;
        final CanonicalBlockEntityTestSupport fixture;
        final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
        boolean reject;

        ControlledExecutor(ExecutorService delegate, CanonicalBlockEntityTestSupport fixture) {
            this.delegate = delegate;
            this.fixture = fixture;
        }

        @Override
        public void execute(Runnable task) {
            this.fixture.event("executor", "queued");
            if (this.reject) throw new java.util.concurrent.RejectedExecutionException("C1 controlled rejection");
            this.queued.add(task);
        }

        void dispatchAll() {
            Runnable task;
            while ((task = this.queued.poll()) != null) this.delegate.execute(task);
        }

        @Override
        public void shutdown() {
            this.dispatchAll();
            this.delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            this.dispatchAll();
            return this.delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return this.delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return this.delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long time, TimeUnit unit) throws InterruptedException {
            return this.delegate.awaitTermination(time, unit);
        }
    }
}
