/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Real furnace bodies and bound tickers, with the existing sparse redstone world supplying the level seams. */
@Timeout(60)
class RegionBoundaryFurnaceTest {
    private static final RegionTickBarrier BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };

    // The JUnit harness does not load the furnace-fuel data map. Supply coal's normal value through the
    // actual NeoForge event so the real fuel-consumption path, including its callback, still executes.
    private final Consumer<FurnaceFuelBurnTimeEvent> fuel = event -> {
        if (event.getItemStack().is(Items.COAL)) {
            event.setBurnTime(1600);
        }
    };

    @BeforeEach
    void provideFuelData() {
        NeoForge.EVENT_BUS.addListener(this.fuel);
    }

    @AfterEach
    void clearEngine() {
        RegionThreadingManager.clear();
        NeoForge.EVENT_BUS.unregister(this.fuel);
    }

    static Stream<Arguments> boundaries() {
        final List<Arguments> cases = new ArrayList<>();
        for (final Block block : List.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER)) {
            cases.add(Arguments.of(block, new BlockPos(47, 64, 17), Direction.EAST));
            cases.add(Arguments.of(block, new BlockPos(-48, 64, 17), Direction.WEST));
            cases.add(Arguments.of(block, new BlockPos(17, 64, 47), Direction.SOUTH));
            cases.add(Arguments.of(block, new BlockPos(17, 64, -48), Direction.NORTH));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    void boundarySmeltingAndComparatorTraceMatchesSerial(Block block, BlockPos position, Direction outward) throws ReflectiveOperationException {
        final List<String> serial = this.run(false, block, position, outward);
        final List<String> parallel = this.run(true, block, position, outward);
        assertEquals(serial, parallel, "inventory, furnace state, comparator state and scheduled ticks must agree each tick");
    }

    private List<String> run(boolean parallel, Block block, BlockPos position, Direction outward) throws ReflectiveOperationException {
        RegionThreadingManager.clear();
        final AtomicLong gameTime = new AtomicLong();
        final RegionRedstoneWorld world = new RegionRedstoneWorld(gameTime);
        final ServerLevel level = world.level;
        final ServerChunkCache chunkSource = level.getChunkSource();
        doReturn(24).when(level).getSectionsCount();
        doReturn(384).when(level).getHeight();
        doAnswer(call -> (int) call.getArgument(0) >> 4).when(level).getSectionIndex(anyInt());
        final RegistryAccess registryAccess = mock(RegistryAccess.class);
        doReturn(biomeRegistry()).when(registryAccess).registryOrThrow(Registries.BIOME);
        doReturn(registryAccess).when(level).registryAccess();
        doReturn(true).when(level).areEntitiesLoaded(anyLong());
        doReturn(true).when(level).shouldTickBlocksAt(any(BlockPos.class));
        doReturn(true).when(chunkSource).hasChunk(anyInt(), anyInt());
        final RecipeManager recipes = new RecipeManager(RegistryAccess.EMPTY);
        final Ingredient input = Ingredient.of(Items.IRON_ORE);
        final ItemStack result = new ItemStack(Items.IRON_INGOT);
        recipes.replaceRecipes(List.of(
                new RecipeHolder<>(ResourceLocation.parse("test:smelting"), new SmeltingRecipe("", CookingBookCategory.MISC, input, result, 0.0F, 2)),
                new RecipeHolder<>(ResourceLocation.parse("test:blasting"), new BlastingRecipe("", CookingBookCategory.MISC, input, result, 0.0F, 2)),
                new RecipeHolder<>(ResourceLocation.parse("test:smoking"), new SmokingRecipe("", CookingBookCategory.MISC, input, result, 0.0F, 2))));
        doReturn(recipes).when(level).getRecipeManager();

        level.setBlock(position, block.defaultBlockState(), 3);
        final AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) world.blockEntities.get(position);
        assertNotNull(furnace);
        final BlockPos comparator = position.relative(outward);
        level.setBlock(comparator.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(comparator, Blocks.COMPARATOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, outward.getOpposite()), 3);
        world.addTickContainer(comparator.getX() >> 4, comparator.getZ() >> 4);
        furnace.setItem(0, new ItemStack(Items.IRON_ORE, 3));
        furnace.setItem(1, new ItemStack(Items.COAL));
        setFurnaceField(furnace, "cookingTotalTime", 2);
        assertTrue(furnace.hasLevel());
        assertFalse(furnace.isRemoved());
        assertTrue(furnace.getItem(1).getBurnTime(null) > 0, "the fixture needs usable vanilla fuel");
        assertTrue(recipes.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING,
                new SingleRecipeInput(furnace.getItem(0)), level).isPresent(), "the fixture needs a matching recipe");
        final LevelChunk chunk = chunk(level, position);
        assertEquals(block.defaultBlockState(), chunk.getBlockState(position), "the real bound ticker must read the furnace block");
        assertTrue(level.getWorldBorder().isWithinBounds(position));
        final LevelChunk neighboringChunk = mock(LevelChunk.class);
        doReturn(true).when(neighboringChunk).isLoaded();
        doAnswer(call -> (int) call.getArgument(0) == chunk.getPos().x && (int) call.getArgument(1) == chunk.getPos().z ? chunk : neighboringChunk)
                .when(chunkSource).getChunkNow(anyInt(), anyInt());
        final Method createTicker = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
        createTicker.setAccessible(true);
        final TickingBlockEntity ticker = (TickingBlockEntity) createTicker.invoke(chunk, furnace,
                (BlockEntityTicker<AbstractFurnaceBlockEntity>) AbstractFurnaceBlockEntity::serverTick);

        final RegionThreadingEngine engine;
        if (parallel) {
            engine = new RegionThreadingEngine(2, false, false, false, true, false, false,
                    RegionLayout.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
            engine.setBorderUpdatesEnabledForTesting(true);
            RegionThreadingManager.install(engine);
            engine.addRegionSection(level, chunk.getPos().x, chunk.getPos().z);
            engine.addRegionSection(level, comparator.getX() >> 4, comparator.getZ() >> 4);
        } else {
            engine = null;
        }
        final List<String> trace = new ArrayList<>();
        final TraceBox box = new TraceBox(position.getX() - 2, position.getX() + 2, 63, 65, position.getZ() - 2, position.getZ() + 2);
        try {
            for (int tick = 1; tick <= 10; ++tick) {
                gameTime.set(tick);
                world.blockTicks.tick(tick, 1000, (pos, scheduled) -> level.getBlockState(pos).tick(level, pos, world.scheduledRandom));
                if (tick == 8) {
                    setFurnaceField(furnace, "litTime", 1);
                }
                chunk.getSection(level.getSectionIndex(position.getY())).setBlockState(
                        position.getX() & 15, position.getY() & 15, position.getZ() & 15, level.getBlockState(position));
                if (parallel) {
                    final RegionBlockEntityTickPlan plan = engine.beginBlockEntityTickPlan(level, BARRIER);
                    assertNotNull(plan);
                    assertTrue(plan.accept(ticker));
                    plan.executeAndAwait();
                } else {
                    ticker.tick();
                }
                assertEquals(3, furnace.getItem(0).getCount() + furnace.getItem(2).getCount(), "ore plus ingots must be conserved");
                trace.add(world.snapshot(box) + "pending=" + world.pendingTicks());
            }
            assertEquals(3, furnace.getItem(2).getCount(), "each input must produce exactly one ingot: " + trace);
            assertTrue(furnace.getItem(0).isEmpty());
            assertTrue(furnace.getItem(1).isEmpty(), "fuel must be consumed exactly once");
            assertFalse(level.getBlockState(position).getValue(AbstractFurnaceBlock.LIT));
            assertEquals(0L, world.refusedBlockEntityReads(), "no comparator may substitute a missing foreign furnace");
            if (parallel) {
                assertEquals(10L, engine.boundaryBlockEntityTicksOnWorkers());
                assertEquals(0L, engine.diagnostics().fallbackBlockEntityTicks());
                assertEquals(0L, engine.diagnostics().ownershipViolationCount());
                assertTrue(engine.diagnostics().borderUpdates().applied() > 0L, "real furnace effects must traverse the border fixpoint");
                assertEquals(0, engine.diagnostics().runningRegionCount());
            }
            return trace;
        } finally {
            RegionThreadingManager.clear();
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static LevelChunk chunk(ServerLevel level, BlockPos position) {
        final LevelChunkSection[] sections = new LevelChunkSection[24];
        for (int index = 0; index < sections.length; ++index) {
            sections[index] = new LevelChunkSection(
                    new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                    (PalettedContainerRO<Holder<Biome>>) mock(PalettedContainerRO.class));
        }
        sections[position.getY() >> 4].setBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15, level.getBlockState(position));
        final LevelChunk chunk = new LevelChunk(level, new ChunkPos(position), UpgradeData.EMPTY,
                new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, sections, null, null);
        chunk.setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
        chunk.setLoaded(true);
        return chunk;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Registry<Biome> biomeRegistry() {
        return (Registry<Biome>) mock(Registry.class);
    }

    private static void setFurnaceField(AbstractFurnaceBlockEntity furnace, String name, int value) throws ReflectiveOperationException {
        final Field field = AbstractFurnaceBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(furnace, value);
    }
}
