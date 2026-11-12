/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.FurnaceWorkScope;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real original furnace bodies and bound tickers; sparse world seam supplies authoritative state/recipes. */
@Timeout(60)
class FurnaceWorkWavesTest {
    private static final BlockPos FIRST = new BlockPos(17, 64, 17);
    private static final BlockPos SECOND = new BlockPos(20, 64, 17);
    private static final RegionTickBarrier BARRIER = done -> {
        while (!done.getAsBoolean()) LockSupport.parkNanos(100_000L);
    };
    private final Thread main = Thread.currentThread();
    private final AtomicLong time = new AtomicLong();
    private final RegionRedstoneWorld world = new RegionRedstoneWorld(this.time);
    private final ServerLevel level = this.world.level;
    private final List<Thread> fuelThreads = new CopyOnWriteArrayList<>();
    private final Consumer<FurnaceFuelBurnTimeEvent> fuel = event -> {
        if (event.getItemStack().is(Items.COAL)) {
            this.fuelThreads.add(Thread.currentThread());
            event.setBurnTime(1600);
        }
    };
    private final RecipeManager recipes = new RecipeManager(net.minecraft.core.RegistryAccess.EMPTY);
    private RegionThreadingEngine engine;

    private void setup() throws Exception {
        this.setup(Boolean.getBoolean("forgia.furnace.persistentWorkers"));
    }

    private void setup(boolean persistentWorkers) throws Exception {
        doReturn(true).when(this.level).areEntitiesLoaded(anyLong());
        doReturn(true).when(this.level).shouldTickBlocksAt(any(BlockPos.class));
        doReturn(this.recipes).when(this.level).getRecipeManager();
        doReturn(net.minecraft.core.RegistryAccess.EMPTY).when(this.level).registryAccess();
        final Ingredient input = Ingredient.of(Items.IRON_ORE);
        this.recipes.replaceRecipes(List.of(
                new RecipeHolder<>(ResourceLocation.parse("test:smelt"), new SmeltingRecipe("", CookingBookCategory.MISC, input, new ItemStack(Items.IRON_INGOT), 0, 2)),
                new RecipeHolder<>(ResourceLocation.parse("test:blast"), new BlastingRecipe("", CookingBookCategory.MISC, input, new ItemStack(Items.IRON_INGOT), 0, 2)),
                new RecipeHolder<>(ResourceLocation.parse("test:smoke"), new SmokingRecipe("", CookingBookCategory.MISC, input, new ItemStack(Items.IRON_INGOT), 0, 2))));
        final LevelChunk chunk = mock(LevelChunk.class, org.mockito.Mockito.withSettings().stubOnly());
        field(LevelChunk.class, chunk, "level", this.level);
        doReturn(this.level).when(chunk).getLevel();
        doReturn(new net.minecraft.world.level.ChunkPos(1, 1)).when(chunk).getPos();
        doReturn(true).when(chunk).isLoaded();
        doReturn(FullChunkStatus.BLOCK_TICKING).when(chunk).getFullStatus();
        doAnswer(call -> this.world.states.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState())).when(chunk).getBlockState(any());
        doAnswer(call -> this.world.blockEntities.get(call.getArgument(0))).when(chunk).getBlockEntity(any(), any(LevelChunk.EntityCreationType.class));
        doReturn(this.world.blockEntities).when(chunk).getBlockEntities();
        final var chunkSource = this.level.getChunkSource();
        doReturn(chunk).when(chunkSource).getChunkNow(anyInt(), anyInt());
        NeoForge.EVENT_BUS.addListener(this.fuel);
        this.engine = new RegionThreadingEngine(2, false, false, false, true, false, false, false,
                persistentWorkers, false, RegionLayout.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        this.engine.setBlockEntityAdmissionCacheEnabledForTesting(false);
        this.engine.setFurnaceWorkWavesEnabledForTesting(true);
        this.engine.setBlockEntityResourcesEnabledForTesting(Boolean.getBoolean("forgia.furnace.resourceOwnership"));
        RegionThreadingManager.install(this.engine);
    }

    @AfterEach
    void close() {
        RegionThreadingManager.clear();
        NeoForge.EVENT_BUS.unregister(this.fuel);
    }

    private AbstractFurnaceBlockEntity furnace(BlockPos pos, Block block, int burning) throws Exception {
        this.level.setBlock(pos, block.defaultBlockState().setValue(AbstractFurnaceBlock.LIT, burning > 0), 3);
        final var furnace = (AbstractFurnaceBlockEntity) this.world.blockEntities.get(pos);
        furnace.setItem(0, new ItemStack(Items.IRON_ORE, 3));
        furnace.setItem(1, new ItemStack(Items.COAL));
        furnace.setItem(2, ItemStack.EMPTY);
        field(AbstractFurnaceBlockEntity.class, furnace, "cookingProgress", 0);
        field(AbstractFurnaceBlockEntity.class, furnace, "litTime", burning);
        field(AbstractFurnaceBlockEntity.class, furnace, "litDuration", 100);
        field(AbstractFurnaceBlockEntity.class, furnace, "cookingTotalTime", 2);
        final Field used = AbstractFurnaceBlockEntity.class.getDeclaredField("recipesUsed");
        used.setAccessible(true);
        ((java.util.Map<?, ?>) used.get(furnace)).clear();
        return furnace;
    }

    private TickingBlockEntity ticker(AbstractFurnaceBlockEntity source) throws Exception {
        final var factory = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
        factory.setAccessible(true);
        final var chunk = this.level.getChunkSource().getChunkNow(source.getBlockPos().getX() >> 4, source.getBlockPos().getZ() >> 4);
        return (TickingBlockEntity) factory.invoke(chunk, source, source.getBlockState().getTicker(this.level, source.getType()));
    }

    private void tick(TickingBlockEntity... entries) {
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(this.level, BARRIER);
        assertNotNull(plan);
        for (final var ticker : entries) assertTrue(plan.accept(ticker));
        plan.executeAndAwait();
    }

    @Test
    void genericOwnershipScopesAuthorizeOnlyCurrentCapturedResources() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.SMOKER, 100);
        final var entries = new AtomicInteger();
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            assertTrue(BlockEntityResourceOwnership.Scope.active());
            BlockEntityResourceOwnership.Scope.require(this.level, source, BlockEntityResourceOwnership.Access.WRITE);
            BlockEntityResourceOwnership.Scope.require(this.level, source.getItem(0), BlockEntityResourceOwnership.Access.WRITE);
            BlockEntityResourceOwnership.Scope.require(this.level, this.recipes, BlockEntityResourceOwnership.Access.READ);
            RegionThreadingHooks.observeBlockEntityReference(this.level, source.getBlockPos());
            final var chunk = this.level.getChunkSource().getChunkNow(1, 1);
            assertTrue(BlockEntityResourceOwnership.Scope.checkChunk(chunk, false));
            assertThrows(IllegalStateException.class, () -> BlockEntityResourceOwnership.Scope.checkChunk(chunk, true));
            assertThrows(IllegalStateException.class, () -> RegionThreadingHooks.observeBlockEntityReference(this.level, source == first ? SECOND : FIRST));
            assertThrows(IllegalStateException.class, () -> BlockEntityResourceOwnership.Scope.rejectEffect("unknown container callback"));
            assertThrows(IllegalStateException.class, () -> BlockEntityResourceOwnership.Scope.require(this.level, this.recipes, BlockEntityResourceOwnership.Access.WRITE));
            assertThrows(IllegalStateException.class, () -> BlockEntityResourceOwnership.Scope.require(this.level, new Object(), BlockEntityResourceOwnership.Access.READ));
            entries.incrementAndGet();
        });
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(2, entries.get());
        assertEquals(2, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(1, this.engine.blockEntityResourceMetrics().completedPhases());
        assertTrue(this.engine.blockEntityResourceMetrics().revalidationNanos() > 0);
        assertTrue(this.engine.blockEntityResourceMetrics().phaseNanos() >= this.engine.blockEntityResourceMetrics().revalidationNanos());
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertFalse(BlockEntityResourceOwnership.Scope.active());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void resourceFlagOffRetainsExistingFurnacePathWithoutIssuingLeases() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(false);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        this.engine.setFurnaceBodyObserverForTesting(source -> assertFalse(BlockEntityResourceOwnership.Scope.active()));
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(0, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(0, this.engine.blockEntityResourceMetrics().phases());
        assertEquals(0, this.engine.blockEntityResourceMetrics().completedPhases());
        assertEquals(0, this.engine.blockEntityResourceMetrics().phaseNanos());
    }

    @Test
    void inventoryAliasOfSharedRecipeResultIsSerialBeforeOtherOriginalBodies() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var third = this.furnace(new BlockPos(23, 64, 17), Blocks.FURNACE, 100);
        final ItemStack shared = this.recipes.getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.SMELTING).getFirst().value().getResultItem(net.minecraft.core.RegistryAccess.EMPTY);
        first.setItem(2, shared);
        for (final var source : List.of(first, second, third)) field(AbstractFurnaceBlockEntity.class, source, "cookingProgress", 1);
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            assertFalse(source == first, "writing into a shared catalog object must be a complete serial operation");
            assertEquals(2, shared.getCount(), "the earlier serial operation must commit before catalog readers");
        });
        this.tick(this.ticker(first), this.ticker(second), this.ticker(third));
        for (final var source : List.of(first, second, third)) {
            assertEquals(2, source.getItem(0).getCount());
            assertEquals(2, source.getItem(2).getCount());
        }
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(1, this.engine.furnaceWorkMetrics().serialInvocations());
        assertEquals(2, this.engine.blockEntityResourceMetrics().issuedLeases());
    }

    @Test
    void blockEntityReplacementAtTheSamePositionCannotInheritAPlannedResourceProof() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var firstTick = this.ticker(first);
        final var secondTick = this.ticker(second);
        final var replacement = new net.minecraft.world.level.block.entity.FurnaceBlockEntity(FIRST, first.getBlockState());
        replacement.setLevel(this.level);
        final var fence = mock(TickingBlockEntity.class);
        doReturn(new BlockPos(19, 64, 17)).when(fence).getPos();
        doReturn("example:replacement").when(fence).getType();
        doAnswer(call -> {
            first.setRemoved();
            this.world.blockEntities.put(FIRST, replacement);
            return null;
        }).when(fence).tick();
        this.tick(fence, firstTick, secondTick);
        assertSame(replacement, this.world.blockEntities.get(FIRST));
        assertEquals(100, readCounter(first, "litTime"));
        assertEquals(99, readCounter(second, "litTime"));
        assertEquals(0, readCounter(replacement, "litTime"));
        assertEquals(0, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
    }

    @Test
    void chunkUnloadRefusesPlannedResourcesAndReloadRequiresANewProof() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var firstTick = this.ticker(first);
        final var secondTick = this.ticker(second);
        final var chunk = this.level.getChunkSource().getChunkNow(1, 1);
        final var fence = mock(TickingBlockEntity.class);
        doReturn(new BlockPos(19, 64, 17)).when(fence).getPos();
        doReturn("example:unload").when(fence).getType();
        doAnswer(call -> {
            doReturn(false).when(chunk).isLoaded();
            doReturn(FullChunkStatus.INACCESSIBLE).when(chunk).getFullStatus();
            return null;
        }).when(fence).tick();
        this.tick(fence, firstTick, secondTick);
        assertEquals(0, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(100, readCounter(first, "litTime"));
        assertEquals(100, readCounter(second, "litTime"));
        doReturn(true).when(chunk).isLoaded();
        doReturn(FullChunkStatus.BLOCK_TICKING).when(chunk).getFullStatus();
        this.tick(firstTick, secondTick);
        assertEquals(2, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(99, readCounter(first, "litTime"));
        assertEquals(99, readCounter(second, "litTime"));
    }

    @Test
    void aTickerBoundToAnOldChunkCannotAcquireTheReloadedChunksResources() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var firstTick = this.ticker(first);
        final var secondTick = this.ticker(second);
        final var replacement = mock(LevelChunk.class, org.mockito.Mockito.withSettings().stubOnly());
        field(LevelChunk.class, replacement, "level", this.level);
        doReturn(this.level).when(replacement).getLevel();
        doReturn(new net.minecraft.world.level.ChunkPos(1, 1)).when(replacement).getPos();
        doReturn(true).when(replacement).isLoaded();
        doReturn(FullChunkStatus.BLOCK_TICKING).when(replacement).getFullStatus();
        doAnswer(call -> this.world.states.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState())).when(replacement).getBlockState(any());
        doAnswer(call -> this.world.blockEntities.get(call.getArgument(0))).when(replacement).getBlockEntity(any(), any(LevelChunk.EntityCreationType.class));
        doReturn(this.world.blockEntities).when(replacement).getBlockEntities();
        final var chunkSource = this.level.getChunkSource();
        doReturn(replacement).when(chunkSource).getChunkNow(anyInt(), anyInt());
        assertFalse(LevelChunk.furnaceWorkMatchesChunk(firstTick, replacement));
        this.tick(firstTick, secondTick);
        assertEquals(0, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(99, readCounter(first, "litTime"));
        assertEquals(99, readCounter(second, "litTime"));
        final var freshFirst = this.ticker(first);
        assertTrue(LevelChunk.furnaceWorkMatchesChunk(freshFirst, replacement));
        this.tick(freshFirst, this.ticker(second));
        assertEquals(2, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
    }

    @Test
    void levelUnloadWaitsForResourceScopesAndOrderedNotificationCommits() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        field(AbstractFurnaceBlockEntity.class, first, "cookingProgress", 1);
        field(AbstractFurnaceBlockEntity.class, second, "cookingProgress", 1);
        final var entered = new java.util.concurrent.CountDownLatch(2);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var unloading = new java.util.concurrent.CountDownLatch(1);
        final var closed = new java.util.concurrent.CountDownLatch(1);
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            assertTrue(BlockEntityResourceOwnership.Scope.active());
            assertThrows(IllegalStateException.class, () -> this.engine.clearLevelRegionTasks(this.level));
            entered.countDown();
            await(release);
            BlockEntityResourceOwnership.Scope.require(this.level, source, BlockEntityResourceOwnership.Access.WRITE);
        });
        final Thread closer = new Thread(() -> {
            try {
                await(entered);
                unloading.countDown();
                this.engine.clearLevelRegionTasks(this.level);
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                closed.countDown();
            }
        }, "resource-level-unload-test");
        closer.start();
        final var plan = this.engine.beginBlockEntityTickPlan(this.level, done -> {
            await(unloading);
            assertEquals(1, closed.getCount());
            release.countDown();
            BARRIER.await(done);
        });
        assertNotNull(plan);
        assertTrue(plan.accept(this.ticker(first)));
        assertTrue(plan.accept(this.ticker(second)));
        try {
            plan.executeAndAwait();
        } finally {
            release.countDown();
            closer.join(10_000L);
        }
        assertFalse(closer.isAlive());
        assertNull(failure.get());
        assertEquals(2, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, this.engine.furnaceWorkMetrics().committedChanges());
        assertEquals(1, first.getItem(2).getCount());
        assertEquals(1, second.getItem(2).getCount());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void deduplicatedCompoundIngredientChildrenRetainTheirOwnSharedReadIdentities() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final Ingredient firstChild = Ingredient.of(Items.IRON_ORE);
        final Ingredient secondChild = Ingredient.of(Items.IRON_ORE);
        final Ingredient compound = net.neoforged.neoforge.common.crafting.CompoundIngredient.of(firstChild, secondChild);
        final ItemStack hidden = secondChild.getItems()[0];
        assertEquals(1, compound.getItems().length);
        assertFalse(compound.getItems()[0] == hidden, "fixture must hide the second cache entry in the merged cache");
        this.recipes.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:deduplicated_or"),
                new SmeltingRecipe("", CookingBookCategory.MISC, compound, new ItemStack(Items.IRON_INGOT), 0, 2))));
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        this.engine.setFurnaceBodyObserverForTesting(source -> BlockEntityResourceOwnership.Scope.require(this.level, hidden, BlockEntityResourceOwnership.Access.READ));
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, this.engine.blockEntityResourceMetrics().issuedLeases());
    }

    @Test
    void unknownChunkSourceImplementationCannotAcquireResourceBodyPermission() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var firstTick = this.ticker(first);
        final var secondTick = this.ticker(second);
        final var chunk = this.level.getChunkSource().getChunkNow(1, 1);
        final var unknown = mock(net.minecraft.server.level.ServerChunkCache.class,
                org.mockito.Mockito.withSettings().extraInterfaces(Runnable.class).stubOnly());
        assertFalse(unknown.getClass() == net.minecraft.server.level.ServerChunkCache.class);
        doReturn(chunk).when(unknown).getChunkNow(anyInt(), anyInt());
        doReturn(unknown).when(this.level).getChunkSource();
        this.tick(firstTick, secondTick);
        assertEquals(0, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(99, readCounter(first, "litTime"));
        assertEquals(99, readCounter(second, "litTime"));
        assertEquals(2, this.engine.furnaceWorkMetrics().serialInvocations());
    }

    @Test
    void resourceBudgetOverflowKeepsCompleteSerialCookingTicks() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final List<RecipeHolder<?>> catalog = new ArrayList<>();
        for (int index = 0; index < 600; ++index) catalog.add(new RecipeHolder<>(ResourceLocation.parse("test:resource_" + index),
                new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0, 2)));
        this.recipes.replaceRecipes(catalog);
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(0, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, this.engine.furnaceWorkMetrics().serialInvocations());
        assertEquals(99, readCounter(first, "litTime"));
        assertEquals(99, readCounter(second, "litTime"));
        assertTrue(this.engine.furnaceWorkMetrics().refusals().containsKey(net.neoforged.neoforge.server.threading.region.FurnaceWorkMetrics.Refusal.RESOURCE_PROOF));
    }

    @Test
    void serialFenceReplacingOrdinaryRecipeObjectsInvalidatesThePlannedResourceSet() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var fence = mock(TickingBlockEntity.class);
        org.mockito.Mockito.when(fence.getPos()).thenReturn(new BlockPos(19, 64, 17));
        org.mockito.Mockito.when(fence.getType()).thenReturn("example:ordinary_reload");
        doAnswer(call -> {
            this.recipes.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:new_ordinary"),
                    new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0, 2))));
            return null;
        }).when(fence).tick();
        this.tick(fence, this.ticker(first), this.ticker(second));
        assertEquals(0, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(3, this.engine.furnaceWorkMetrics().serialInvocations());
        assertEquals(99, readCounter(first, "litTime"));
        assertEquals(99, readCounter(second, "litTime"));
    }

    @Test
    void serialFenceMutatingTheSameRecipeResultObjectStillReauditsItsComponents() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var result = this.recipes.getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.SMELTING).getFirst().value()
                .getResultItem(net.minecraft.core.RegistryAccess.EMPTY);
        final var fence = mock(TickingBlockEntity.class);
        doReturn(new BlockPos(19, 64, 17)).when(fence).getPos();
        doReturn("example:in_place_result_mutation").when(fence).getType();
        doAnswer(call -> {
            result.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("mutated"));
            return null;
        }).when(fence).tick();
        this.tick(fence, this.ticker(first), this.ticker(second));
        assertEquals(0, this.engine.blockEntityResourceMetrics().issuedLeases());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(3, this.engine.furnaceWorkMetrics().serialInvocations());
        assertEquals(99, readCounter(first, "litTime"));
        assertEquals(99, readCounter(second, "litTime"));
    }

    @Test
    void aNewTickNeverReusesASharedReadProofForMutatedRecipeComponents() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var firstTick = this.ticker(first);
        final var secondTick = this.ticker(second);
        this.tick(firstTick, secondTick);
        assertEquals(2, this.engine.blockEntityResourceMetrics().issuedLeases());
        final var result = this.recipes.getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.SMELTING).getFirst().value()
                .getResultItem(net.minecraft.core.RegistryAccess.EMPTY);
        result.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("next tick"));
        this.tick(firstTick, secondTick);
        assertEquals(2, this.engine.blockEntityResourceMetrics().issuedLeases(), "mutating the same result object must not acquire new leases");
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, this.engine.furnaceWorkMetrics().serialInvocations());
        assertEquals(98, readCounter(first, "litTime"));
        assertEquals(98, readCounter(second, "litTime"));
    }

    private static int readCounter(AbstractFurnaceBlockEntity source, String name) throws Exception {
        final Field counter = AbstractFurnaceBlockEntity.class.getDeclaredField(name);
        counter.setAccessible(true);
        return counter.getInt(source);
    }

    @Test
    void twoOriginalBodiesInOneChunkAndRegionExecuteSimultaneously() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var rendezvous = new CyclicBarrier(2);
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            threads.add(Thread.currentThread());
            try {
                rendezvous.await(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("original bodies did not overlap", failure);
            }
        });
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(2, threads.size());
        assertTrue(threads.stream().noneMatch(thread -> thread == this.main));
        assertFalse(threads.get(0) == threads.get(1));
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, this.engine.furnaceWorkMetrics().maxSimultaneousBodies());
        assertEquals(0, this.engine.furnaceWorkMetrics().serialInvocations());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void persistentRunnersStealUnstartedOriginalBodiesAndCommitInCanonicalOrder() throws Exception {
        this.setup(true);
        final List<AbstractFurnaceBlockEntity> furnaces = new java.util.ArrayList<>();
        final List<BlockPos> positions = List.of(new BlockPos(17, 64, 17), new BlockPos(21, 64, 17), new BlockPos(25, 64, 17),
                new BlockPos(17, 64, 21), new BlockPos(21, 64, 21), new BlockPos(25, 64, 21));
        for (final BlockPos pos : positions) {
            final var furnace = this.furnace(pos, Blocks.FURNACE, 100);
            field(AbstractFurnaceBlockEntity.class, furnace, "cookingProgress", 1);
            furnaces.add(furnace);
        }
        final var slowEntered = new java.util.concurrent.CountDownLatch(1);
        final var otherBodies = new java.util.concurrent.CountDownLatch(5);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final List<BlockPos> commits = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            assertSame(this.main, Thread.currentThread());
            commits.add(call.getArgument(0));
            return null;
        }).when(this.level).blockEntityChanged(any());
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            try {
                if (source == furnaces.getFirst()) {
                    slowEntered.countDown();
                    if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("slow original body was not released");
                } else otherBodies.countDown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final Thread releaser = new Thread(() -> {
            try {
                assertTrue(slowEntered.await(10, TimeUnit.SECONDS));
                assertTrue(otherBodies.await(10, TimeUnit.SECONDS), "unstarted bodies remained pinned behind the slow body");
                assertTrue(commits.isEmpty(), "notifications committed while an original body was still running");
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                release.countDown();
            }
        }, "furnace-steal-progress-test");
        final TickingBlockEntity[] tickers = new TickingBlockEntity[furnaces.size()];
        for (int index = 0; index < tickers.length; ++index) tickers[index] = this.ticker(furnaces.get(index));
        releaser.start();
        try {
            this.tick(tickers);
        } finally {
            release.countDown();
            releaser.join(15_000L);
        }
        assertFalse(releaser.isAlive());
        assertNull(failure.get());
        assertEquals(positions, commits);
        for (final var furnace : furnaces) {
            assertEquals(2, furnace.getItem(0).getCount());
            assertEquals(1, furnace.getItem(2).getCount());
        }
        assertEquals(6, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(6, this.engine.furnaceWorkMetrics().committedChanges());
        assertEquals(2, this.engine.furnaceWorkMetrics().maxSimultaneousBodies());
        assertTrue(this.engine.diagnostics().workerDispatch().batchSteals() > 0);
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void processingCompletionKeepsOriginalInventoryAndCommitsNotificationsOnTheServer() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.BLAST_FURNACE, 100);
        final var firstTick = this.ticker(first);
        final var secondTick = this.ticker(second);
        final List<Thread> commits = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            commits.add(Thread.currentThread());
            return null;
        }).when(this.level).blockEntityChanged(any());
        for (int tick = 0; tick < 6; ++tick) {
            this.tick(firstTick, secondTick);
            assertEquals(3, first.getItem(0).getCount() + first.getItem(2).getCount());
            assertEquals(3, second.getItem(0).getCount() + second.getItem(2).getCount());
        }
        assertEquals(3, first.getItem(2).getCount());
        assertEquals(3, second.getItem(2).getCount());
        assertEquals(1, first.getItem(1).getCount(), "already-burning ticks must not consume new fuel");
        assertEquals(12, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(6, this.engine.furnaceWorkMetrics().committedChanges());
        assertEquals(6, commits.size());
        assertTrue(commits.stream().allMatch(thread -> thread == this.main));
        assertTrue(this.fuelThreads.isEmpty());
    }

    @Test
    void completeFuelAndExtinguishTicksAreExclusiveSerialFences() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 0);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 0);
        final var firstTick = this.ticker(first);
        final var secondTick = this.ticker(second);
        this.tick(firstTick, secondTick);
        assertTrue(this.fuelThreads.stream().allMatch(thread -> thread == this.main));
        assertFalse(this.fuelThreads.isEmpty());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        this.tick(firstTick, secondTick);
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        field(AbstractFurnaceBlockEntity.class, first, "litTime", 1);
        field(AbstractFurnaceBlockEntity.class, second, "litTime", 1);
        first.setItem(0, ItemStack.EMPTY);
        second.setItem(0, ItemStack.EMPTY);
        this.tick(firstTick, secondTick);
        assertFalse(this.level.getBlockState(FIRST).getValue(AbstractFurnaceBlock.LIT));
        assertFalse(this.level.getBlockState(SECOND).getValue(AbstractFurnaceBlock.LIT));
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
    }

    @Test
    void replacementTickerDoesNotInheritVanillaBodyPermission() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var factory = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
        factory.setAccessible(true);
        final var calls = new AtomicInteger();
        final var chunk = this.level.getChunkSource().getChunkNow(1, 1);
        final var custom = (TickingBlockEntity) factory.invoke(chunk, first,
                (BlockEntityTicker<AbstractFurnaceBlockEntity>) (level, pos, state, source) -> {
                    assertSame(this.main, Thread.currentThread());
                    calls.incrementAndGet();
                    AbstractFurnaceBlockEntity.serverTick(level, pos, state, source);
                });
        this.tick(custom, this.ticker(second));
        assertEquals(1, calls.get());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
    }

    @Test
    void unknownIngredientCannotRunHiddenResolutionDuringResourcePreflight() throws Exception {
        this.setup();
        this.engine.setBlockEntityResourcesEnabledForTesting(true);
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var matches = new AtomicInteger();
        final var resolutions = new AtomicInteger();
        final var custom = mock(net.neoforged.neoforge.common.crafting.ICustomIngredient.class);
        org.mockito.Mockito.when(custom.getItems()).thenAnswer(call -> {
            assertSame(FurnaceWorkWavesTest.this.main, Thread.currentThread());
            resolutions.incrementAndGet();
            return java.util.stream.Stream.of(new ItemStack(Items.IRON_ORE));
        });
        org.mockito.Mockito.when(custom.test(any())).thenAnswer(call -> {
            assertSame(FurnaceWorkWavesTest.this.main, Thread.currentThread());
            matches.incrementAndGet();
            return true;
        });
        this.recipes.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:ingredient_unknown"),
                new SmeltingRecipe("", CookingBookCategory.MISC, new Ingredient(custom), new ItemStack(Items.IRON_INGOT), 0, 2))));
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(0, resolutions.get(), "proof collection must reject the class before executing its resolver");
        assertEquals(2, matches.get(), "both complete original serial ticks must still execute");
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(0, this.engine.blockEntityResourceMetrics().issuedLeases());
    }

    @Test
    void unknownRecipeIsNeverEvaluatedAsPartOfWorkerPreflightOrWorkerBody() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var matches = new AtomicInteger();
        this.recipes.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:unknown"),
                new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0, 2) {
                    @Override
                    public boolean matches(net.minecraft.world.item.crafting.SingleRecipeInput input, Level level) {
                        assertSame(FurnaceWorkWavesTest.this.main, Thread.currentThread());
                        matches.incrementAndGet();
                        return true;
                    }
                })));
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(2, matches.get());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
    }

    @Test
    void aForeignReadFailureDoesNotRetryAnAlreadyStartedOriginalBody() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var starts = new AtomicInteger();
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            if (source == first) {
                starts.incrementAndGet();
                source.getItem(0).shrink(1);
                RegionThreadingHooks.observeBlockEntityReference(this.level, SECOND);
            }
        });
        assertThrows(RuntimeException.class, () -> this.tick(this.ticker(first), this.ticker(second)));
        assertEquals(1, starts.get());
        assertEquals(2, first.getItem(0).getCount(), "effects before failure must not execute again on the server");
        assertEquals(1, this.engine.furnaceWorkMetrics().failed());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertFalse(FurnaceWorkScope.active());
    }

    @Test
    void activeTimingTrackerKeepsItsSingleThreadStateSerial() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        net.neoforged.neoforge.server.timings.TimeTracker.BLOCK_ENTITY_UPDATE.enable(5);
        try {
            this.tick(this.ticker(first), this.ticker(second));
            assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
            assertEquals(2, this.engine.furnaceWorkMetrics().serialInvocations());
        } finally {
            net.neoforged.neoforge.server.timings.TimeTracker.BLOCK_ENTITY_UPDATE.reset();
        }
    }

    @Test
    void twoFurnacesSharingAMutableItemStackCannotReceiveSeparateOwnership() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        second.setItem(0, first.getItem(0));
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, this.engine.furnaceWorkMetrics().serialInvocations());
    }

    @Test
    void notificationToAnotherMachineRetainsTheCompleteSerialCompletionTick() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var firstTick = this.ticker(first);
        final var secondTick = this.ticker(second);
        this.level.setBlock(FIRST.south(), Blocks.HOPPER.defaultBlockState(), 3);
        this.tick(firstTick, secondTick);
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        this.tick(firstTick, secondTick);
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(1, first.getItem(2).getCount());
        assertEquals(1, second.getItem(2).getCount());
    }

    @Test
    void rejectedWorkRunsTheOriginalBodyOnceOnTheSubmittingServer() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final Field poolField = RegionThreadingEngine.class.getDeclaredField("workerPool");
        poolField.setAccessible(true);
        final Object pool = poolField.get(this.engine);
        final Method closePool = pool.getClass().getDeclaredMethod("close");
        closePool.setAccessible(true);
        closePool.invoke(pool);
        final var bodies = new AtomicInteger();
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            assertSame(this.main, Thread.currentThread());
            bodies.incrementAndGet();
        });
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(2, bodies.get());
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, this.engine.furnaceWorkMetrics().serialInvocations());
        assertEquals(2, this.engine.furnaceWorkMetrics().rejectedTasks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void anUnknownSerialFenceCompletesBeforeLaterWorkersAndSeesEarlierWorkerChanges() throws Exception {
        this.setup();
        final List<TickingBlockEntity> order = new ArrayList<>();
        final var bodies = new AtomicInteger();
        this.engine.setFurnaceBodyObserverForTesting(source -> bodies.incrementAndGet());
        for (int x : new int[] { 17, 18 }) order.add(this.ticker(this.furnace(new BlockPos(x, 64, 17), Blocks.FURNACE, 100)));
        final var fence = mock(TickingBlockEntity.class);
        org.mockito.Mockito.when(fence.getPos()).thenReturn(new BlockPos(19, 64, 17));
        org.mockito.Mockito.when(fence.getType()).thenReturn("example:opaque");
        final var fenceCalls = new AtomicInteger();
        doAnswer(call -> {
            assertSame(this.main, Thread.currentThread());
            assertEquals(2, bodies.get(), "earlier worker bodies must have joined");
            fenceCalls.incrementAndGet();
            return null;
        }).when(fence).tick();
        order.add(fence);
        for (int x : new int[] { 20, 21 }) order.add(this.ticker(this.furnace(new BlockPos(x, 64, 17), Blocks.FURNACE, 100)));
        this.tick(order.toArray(TickingBlockEntity[]::new));
        assertEquals(1, fenceCalls.get());
        assertEquals(4, bodies.get());
        assertEquals(4, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, this.engine.furnaceWorkMetrics().waves());
    }

    @Test
    void serialFenceRecipeReloadIsRevalidatedBeforeTheNextWave() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var fence = mock(TickingBlockEntity.class);
        org.mockito.Mockito.when(fence.getPos()).thenReturn(new BlockPos(19, 64, 17));
        org.mockito.Mockito.when(fence.getType()).thenReturn("example:reload");
        final var matches = new AtomicInteger();
        doAnswer(call -> {
            this.recipes.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:reload"),
                    new SmeltingRecipe("", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0, 2) {
                        @Override
                        public boolean matches(net.minecraft.world.item.crafting.SingleRecipeInput input, Level level) {
                            assertSame(FurnaceWorkWavesTest.this.main, Thread.currentThread());
                            matches.incrementAndGet();
                            return true;
                        }
                    })));
            return null;
        }).when(fence).tick();
        this.tick(fence, this.ticker(first), this.ticker(second));
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, matches.get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("furnaceFamilies")
    void completeTickTraceMatchesSerialThroughInputOutputAndFuelChanges(Block family) throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, family, 0);
        final var second = this.furnace(SECOND, Blocks.SMOKER, 0);
        final var firstTick = this.ticker(first);
        final var secondTick = this.ticker(second);
        final List<String> parallel = this.scenario(first, second, firstTick, secondTick, true);
        this.engine.setFurnaceBodyObserverForTesting(null);
        final var serialFirst = this.furnace(FIRST, family, 0);
        final var serialSecond = this.furnace(SECOND, Blocks.SMOKER, 0);
        final List<String> serial = this.scenario(serialFirst, serialSecond, this.ticker(serialFirst), this.ticker(serialSecond), false);
        assertEquals(serial, parallel, "full inventory, state, burn/cook counters and pending schedule trace must match each tick");
        assertTrue(this.engine.furnaceWorkMetrics().nativeCompletions() > 0);
    }

    private static java.util.stream.Stream<Block> furnaceFamilies() {
        return java.util.stream.Stream.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER);
    }

    private List<String> scenario(AbstractFurnaceBlockEntity first, AbstractFurnaceBlockEntity second,
            TickingBlockEntity firstTick, TickingBlockEntity secondTick, boolean parallel) throws Exception {
        final List<String> trace = new ArrayList<>();
        for (int tick = 0; tick < 20; ++tick) {
            if (tick == 3) {
                first.setItem(2, new ItemStack(Items.IRON_INGOT, 64));
                second.setItem(2, new ItemStack(Items.IRON_INGOT, 64));
            }
            if (tick == 5) {
                first.setItem(2, ItemStack.EMPTY);
                second.setItem(2, ItemStack.EMPTY);
            }
            if (tick == 8) {
                first.setItem(0, ItemStack.EMPTY);
                second.setItem(0, ItemStack.EMPTY);
            }
            if (tick == 10) {
                first.setItem(0, new ItemStack(Items.IRON_ORE, 2));
                second.setItem(0, new ItemStack(Items.IRON_ORE, 2));
            }
            if (tick == 15) {
                field(AbstractFurnaceBlockEntity.class, first, "litTime", 1);
                field(AbstractFurnaceBlockEntity.class, second, "litTime", 1);
                first.setItem(1, ItemStack.EMPTY);
                second.setItem(1, ItemStack.EMPTY);
            }
            if (parallel) this.tick(firstTick, secondTick);
            else {
                firstTick.tick();
                secondTick.tick();
            }
            final StringBuilder state = new StringBuilder();
            for (final var furnace : List.of(first, second)) {
                state.append(this.level.getBlockState(furnace.getBlockPos()));
                for (int slot = 0; slot < 3; ++slot) state.append('|').append(furnace.getItem(slot));
                for (final var name : List.of("litTime", "litDuration", "cookingProgress", "cookingTotalTime")) {
                    final Field counter = AbstractFurnaceBlockEntity.class.getDeclaredField(name);
                    counter.setAccessible(true);
                    state.append('|').append(counter.get(furnace));
                }
                final Field used = AbstractFurnaceBlockEntity.class.getDeclaredField("recipesUsed");
                used.setAccessible(true);
                state.append('|').append(used.get(furnace));
            }
            state.append(this.world.pendingTicks());
            trace.add(state.toString());
        }
        return trace;
    }

    @Test
    void serialAndUnknownEffectsNeverRunWhileOriginalWorkersAreActive() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var fence = mock(TickingBlockEntity.class);
        org.mockito.Mockito.when(fence.getPos()).thenReturn(new BlockPos(21, 64, 17));
        org.mockito.Mockito.when(fence.getType()).thenReturn("example:serial");
        final var active = new AtomicInteger();
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            active.incrementAndGet();
            try {
                Thread.yield();
            } finally {
                active.decrementAndGet();
            }
        });
        doAnswer(call -> {
            assertEquals(0, active.get());
            assertSame(this.main, Thread.currentThread());
            return null;
        }).when(fence).tick();
        this.tick(this.ticker(first), this.ticker(second), fence);
        assertEquals(0, active.get());
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
    }

    @Test
    void mutableComponentItemsRetainCompleteServerTicks() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        first.getItem(0).set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("custom"));
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertTrue(this.engine.furnaceWorkMetrics().refusals().containsKey(net.neoforged.neoforge.server.threading.region.FurnaceWorkMetrics.Refusal.CUSTOM_ITEM));
    }

    @Test
    void throwingManagedWaitStillJoinsWorkersAndReleasesAllLeases() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var entered = new java.util.concurrent.CountDownLatch(2);
        final var release = new java.util.concurrent.CountDownLatch(1);
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            entered.countDown();
            await(release);
        });
        final var failure = new IllegalStateException("managed wait failed");
        final var plan = this.engine.beginBlockEntityTickPlan(this.level, done -> {
            await(entered);
            release.countDown();
            throw failure;
        });
        assertNotNull(plan);
        assertTrue(plan.accept(this.ticker(first)));
        assertTrue(plan.accept(this.ticker(second)));
        assertSame(failure, assertThrows(IllegalStateException.class, plan::executeAndAwait));
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertFalse(FurnaceWorkScope.active());
    }

    @Test
    void shutdownWaitsForThePublishedWholePhaseAndRejectsReentrantWorkerShutdown() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var entered = new java.util.concurrent.CountDownLatch(2);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var shutdownAttempted = new java.util.concurrent.CountDownLatch(1);
        final var shutdownDone = new java.util.concurrent.CountDownLatch(1);
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        this.engine.setFurnaceBodyObserverForTesting(source -> {
            assertThrows(IllegalStateException.class, this.engine::shutdown);
            entered.countDown();
            await(release);
        });
        final Thread shutdown = new Thread(() -> {
            try {
                await(entered);
                shutdownAttempted.countDown();
                this.engine.shutdown();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                shutdownDone.countDown();
            }
        }, "furnace-shutdown-test");
        shutdown.start();
        final var plan = this.engine.beginBlockEntityTickPlan(this.level, done -> {
            await(shutdownAttempted);
            assertEquals(1, shutdownDone.getCount(), "shutdown must not retire the active phase");
            release.countDown();
            BARRIER.await(done);
        });
        assertNotNull(plan);
        assertTrue(plan.accept(this.ticker(first)));
        assertTrue(plan.accept(this.ticker(second)));
        try {
            plan.executeAndAwait();
        } finally {
            release.countDown();
            shutdown.join(10_000L);
        }
        assertFalse(shutdown.isAlive());
        assertEquals(null, failure.get());
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
    }

    @Test
    void unknownActuallyMergedFurnaceMixinKeepsOriginalBodiesSerial() throws Exception {
        this.setup();
        assertTrue(Boolean.getBoolean("forgia.furnace.testUnverifiedHook"));
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, this.engine.furnaceWorkMetrics().serialInvocations());
        assertEquals(99, readCounter(first, "litTime"));
        assertEquals(99, readCounter(second, "litTime"));
        assertTrue(this.engine.furnaceWorkMetrics().refusals().containsKey(
                net.neoforged.neoforge.server.threading.region.FurnaceWorkMetrics.Refusal.UNVERIFIED_RUNTIME));
    }

    @Test
    void ordinaryVanillaDyeAndRedstoneRecipesDoNotInvalidateTheWholeCookingCatalog() throws Exception {
        this.setup();
        this.recipes.replaceRecipes(List.of(
                new RecipeHolder<>(ResourceLocation.parse("test:dye"), new SmeltingRecipe("", CookingBookCategory.MISC,
                        Ingredient.of(Items.CACTUS), new ItemStack(Items.GREEN_DYE), 0, 200)),
                new RecipeHolder<>(ResourceLocation.parse("test:redstone"), new SmeltingRecipe("", CookingBookCategory.MISC,
                        Ingredient.of(Items.REDSTONE_ORE), new ItemStack(Items.REDSTONE), 0, 200)),
                new RecipeHolder<>(ResourceLocation.parse("test:iron"), new SmeltingRecipe("", CookingBookCategory.MISC,
                        net.neoforged.neoforge.common.crafting.CompoundIngredient.of(Ingredient.of(Items.IRON_ORE), Ingredient.of(Items.DEEPSLATE_IRON_ORE)),
                        new ItemStack(Items.IRON_INGOT), 0, 2))));
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(2, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(0, this.engine.furnaceWorkMetrics().failed());
    }

    @Test
    void aCompoundIngredientWithUnknownChildCannotAcquireAWorkerBody() throws Exception {
        this.setup();
        final var first = this.furnace(FIRST, Blocks.FURNACE, 100);
        final var second = this.furnace(SECOND, Blocks.FURNACE, 100);
        final var custom = mock(net.neoforged.neoforge.common.crafting.ICustomIngredient.class);
        final var matches = new AtomicInteger();
        org.mockito.Mockito.when(custom.test(any())).thenAnswer(call -> {
            assertSame(this.main, Thread.currentThread());
            matches.incrementAndGet();
            return true;
        });
        final var ingredient = net.neoforged.neoforge.common.crafting.CompoundIngredient.of(
                new Ingredient(custom), Ingredient.of(Items.CACTUS));
        this.recipes.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("test:compound_custom"),
                new SmeltingRecipe("", CookingBookCategory.MISC, ingredient, new ItemStack(Items.IRON_INGOT), 0, 2))));
        this.tick(this.ticker(first), this.ticker(second));
        assertEquals(0, this.engine.furnaceWorkMetrics().nativeCompletions());
        assertEquals(2, matches.get());
    }

    @Test
    @org.junit.jupiter.api.Tag("benchmark")
    @Timeout(180)
    void measureDenseOriginalFurnaceWaveOverhead() throws Exception {
        this.setup();
        final List<AbstractFurnaceBlockEntity> machines = new ArrayList<>();
        final List<TickingBlockEntity> tickers = new ArrayList<>();
        for (int index = 0; index < 256; ++index) {
            final var machine = this.furnace(new BlockPos(16 + index % 16, 64, 16 + index / 16), Blocks.FURNACE, 100000);
            machines.add(machine);
            tickers.add(this.ticker(machine));
        }
        final TickingBlockEntity[] entries = tickers.toArray(TickingBlockEntity[]::new);
        for (int round = 0; round < 6; ++round) {
            final boolean waves = new boolean[] { false, true, true, false, false, true }[round];
            this.engine.setFurnaceWorkWavesEnabledForTesting(waves);
            for (final var machine : machines) {
                field(AbstractFurnaceBlockEntity.class, machine, "litTime", 100000);
                field(AbstractFurnaceBlockEntity.class, machine, "cookingProgress", 0);
                field(AbstractFurnaceBlockEntity.class, machine, "cookingTotalTime", 100000);
            }
            for (int tick = 0; tick < 100; ++tick) this.tick(entries);
            final var before = this.engine.furnaceWorkMetrics();
            final long[] samples = new long[400];
            for (int tick = 0; tick < samples.length; ++tick) {
                final long start = System.nanoTime();
                this.tick(entries);
                samples[tick] = System.nanoTime() - start;
            }
            final var after = this.engine.furnaceWorkMetrics();
            java.util.Arrays.sort(samples);
            final double mean = java.util.Arrays.stream(samples).average().orElseThrow() / 1000000.0;
            System.out.printf(java.util.Locale.ROOT,
                    "FURNACE_WAVE_BENCH round=%d waves=%s machines=256 meanMs=%.6f p50Ms=%.6f p95Ms=%.6f%n",
                    round, waves, mean, samples[200] / 1000000.0, samples[380] / 1000000.0);
            if (waves) {
                final double divisor = samples.length * 1000000.0;
                final double preparation = (after.preparationNanos() - before.preparationNanos()) / divisor;
                final double execution = (after.executionNanos() - before.executionNanos()) / divisor;
                final double commit = (after.commitNanos() - before.commitNanos()) / divisor;
                System.out.printf(java.util.Locale.ROOT,
                        "FURNACE_WAVE_COST round=%d prepareMeanMs=%.6f executeAwaitMeanMs=%.6f commitMeanMs=%.6f otherMeanMs=%.6f waves=%d nativeBodies=%d%n",
                        round, preparation, execution, commit, mean - preparation - execution - commit,
                        after.waves() - before.waves(), after.nativeCompletions() - before.nativeCompletions());
            }
            for (final var machine : machines) assertEquals(3, machine.getItem(0).getCount());
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "bounded lifecycle wait expired");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void field(Class<?> owner, Object target, String name, Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
