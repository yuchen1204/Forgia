/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.block;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.registration.RegistrationHelper;

@ForEachTest(groups = { "level.block.survivability" })
public class CanSustainPlantTests {
    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Lily Pads should be placeable on water surface but not lava or land. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityLilyPadTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(player -> player.moveTo(helper.absolutePos(belowBlock).above().north().getCenter()))
                .thenExecute(player -> player.lookAt(EntityAnchorArgument.Anchor.EYES, helper.absolutePos(belowBlock).getCenter()))

                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.WATER))
                .thenExecute(player -> player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LILY_PAD)))
                .thenExecute(player -> Items.LILY_PAD.use(helper.getLevel(), player, InteractionHand.MAIN_HAND))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.LILY_PAD, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.LAVA))
                .thenExecute(player -> player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LILY_PAD)))
                .thenExecute(player -> Items.LILY_PAD.use(helper.getLevel(), player, InteractionHand.MAIN_HAND))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.LILY_PAD, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.GRASS_BLOCK))
                .thenExecute(player -> player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LILY_PAD)))
                .thenExecute(player -> Items.LILY_PAD.use(helper.getLevel(), player, InteractionHand.MAIN_HAND))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.LILY_PAD, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LILY_PAD)))
                .thenExecute(player -> Items.LILY_PAD.use(helper.getLevel(), player, InteractionHand.MAIN_HAND))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.LILY_PAD, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Red Mushroom should be placeable on mycelium in any light and on other blocks when dark. Never on farmland. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityRedMushroomTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.MYCELIUM))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.RED_MUSHROOM), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.RED_MUSHROOM, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.STONE))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.RED_MUSHROOM), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.RED_MUSHROOM, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.RED_MUSHROOM), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.RED_MUSHROOM, belowBlock.above()))

                // Commented out due to issues with getting lighting engine to recalculate dark area in time.
//                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().north(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().south(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().east(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().west(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above(2), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.STONE))
//                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.RED_MUSHROOM), Direction.UP))
//                .thenExecute(() -> helper.assertBlockPresent(Blocks.RED_MUSHROOM, belowBlock.above()))
//
//                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().north(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().south(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().east(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().west(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above(2), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
//                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.RED_MUSHROOM), Direction.UP))
//                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.RED_MUSHROOM, belowBlock.above()))
//
//                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().north(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().south(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().east(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().west(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above(2), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
//                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.RED_MUSHROOM), Direction.UP))
//                .thenExecute(() -> helper.assertBlockPresent(Blocks.RED_MUSHROOM, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Wheat should be placeable on farmland but not on dirt nor in too dark areas. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityWheatTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.WHEAT_SEEDS), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.WHEAT, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.WHEAT_SEEDS), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.WHEAT, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.WHEAT_SEEDS), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.WHEAT, belowBlock.above()))

                // Commented out due to issues with getting lighting engine to recalculate dark area in time.
//                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().north(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().south(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().east(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().west(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above(2), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
//                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.WHEAT_SEEDS), Direction.UP))
//                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.WHEAT, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Pitcher Crop should be placeable on farmland but not on dirt nor in too dark areas. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityPitcherCropTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.PITCHER_POD), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.PITCHER_CROP, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.PITCHER_POD), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.PITCHER_CROP, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.PITCHER_POD), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.PITCHER_CROP, belowBlock.above()))

                // Commented out due to issues with getting lighting engine to recalculate dark area in time.
//                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().north(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().south(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().east(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above().west(), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock.above(2), Blocks.STONE))
//                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
//                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.PITCHER_POD), Direction.UP))
//                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.PITCHER_CROP, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Bamboo Stalk should be placeable on dirt, sand, gravel, other bamboo, but not on farmland. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityBambooTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BAMBOO), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BAMBOO_SAPLING, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.BAMBOO))
                .thenExecute(player -> helper.useBlock(belowBlock.above(), player, new ItemStack(Items.BAMBOO), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BAMBOO, belowBlock.above(2)))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock.above(2), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.SAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BAMBOO), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BAMBOO_SAPLING, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.GRAVEL))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BAMBOO), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BAMBOO_SAPLING, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BAMBOO), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.BAMBOO_SAPLING, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BAMBOO), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BAMBOO_SAPLING, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Cactus should be placeable on sand, red sand, other cactus, but not on terracotta. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityCactusTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.SAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CACTUS), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.CACTUS, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.CACTUS))
                .thenExecute(player -> helper.useBlock(belowBlock.above(), player, new ItemStack(Items.CACTUS), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.CACTUS, belowBlock.above(2)))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.RED_SAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CACTUS), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.CACTUS, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.TERRACOTTA))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CACTUS), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.CACTUS, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CACTUS), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.CACTUS, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Dead Bushes should be placeable on dirt, sand, and terracotta, but not on glazed terracotta nor farmland. And plantable on custom blocks that allow the plant.",
            "(neoforged/NeoForge#306)"
    })
    static void survivabilityDeadBushTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.TERRACOTTA))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.DEAD_BUSH), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.DEAD_BUSH, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.WHITE_TERRACOTTA))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.DEAD_BUSH), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.DEAD_BUSH, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.DEAD_BUSH), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.DEAD_BUSH, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.SAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.DEAD_BUSH), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.DEAD_BUSH, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.WHITE_GLAZED_TERRACOTTA))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.DEAD_BUSH), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.DEAD_BUSH, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.DEAD_BUSH), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.DEAD_BUSH, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.DEAD_BUSH), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.DEAD_BUSH, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Oak Sapling should be placeable on dirt and farmland but not on sand. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityOakSaplingTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.OAK_SAPLING), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.OAK_SAPLING, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.OAK_SAPLING), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.OAK_SAPLING, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.SAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.OAK_SAPLING), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.OAK_SAPLING, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.OAK_SAPLING), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.OAK_SAPLING, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Hanging Mangrove Propagule should be survivable on underside of Mangrove Leaves but not Stone. And survivable on custom blocks that allow the plant."
    })
    static void survivabilityHangingMangrovePropaguleTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", properties -> new CustomSuperSustainingBlock(properties, Direction.DOWN), BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos aboveBlock = new BlockPos(1, 3, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(aboveBlock, Blocks.MANGROVE_LEAVES))
                .thenExecute(() -> helper.setBlock(aboveBlock.below(), Blocks.MANGROVE_PROPAGULE.defaultBlockState().setValue(MangrovePropaguleBlock.HANGING, true)))
                .thenExecute(() -> helper.setBlock(aboveBlock.below().north(), Blocks.STONE)) // Trigger block update on neighbors
                .thenExecute(() -> helper.assertBlockPresent(Blocks.MANGROVE_PROPAGULE, aboveBlock.below()))

                .thenExecute(() -> helper.setBlock(aboveBlock, Blocks.STONE))
                .thenExecute(() -> helper.setBlock(aboveBlock.below(), Blocks.MANGROVE_PROPAGULE.defaultBlockState().setValue(MangrovePropaguleBlock.HANGING, true)))
                .thenExecute(() -> helper.setBlock(aboveBlock.below().north(), Blocks.DIRT)) // Trigger block update on neighbors
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.MANGROVE_PROPAGULE, aboveBlock.below()))

                .thenExecute(() -> helper.setBlock(aboveBlock, sustainingBlock.get()))
                .thenExecute(() -> helper.setBlock(aboveBlock.below(), Blocks.MANGROVE_PROPAGULE.defaultBlockState().setValue(MangrovePropaguleBlock.HANGING, true)))
                .thenExecute(() -> helper.setBlock(aboveBlock.below().north(), Blocks.STONE)) // Trigger block update on neighbors
                .thenExecute(() -> helper.assertBlockPresent(Blocks.MANGROVE_PROPAGULE, aboveBlock.below()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Sugar Cane should be placeable on dirt and sand next to water or frosted ice. And plantable on other sugar cane or custom blocks that allow the plant. Not plantable next to lava or on farmland."
    })
    static void survivabilitySugarCaneTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(() -> helper.setBlock(belowBlock.north(), Blocks.WATER))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SUGAR_CANE), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SUGAR_CANE, belowBlock.above()))

                .thenExecute(player -> helper.useBlock(belowBlock.above(), player, new ItemStack(Items.SUGAR_CANE), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SUGAR_CANE, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(2), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.SAND))
                .thenExecute(() -> helper.setBlock(belowBlock.north(), Blocks.WATER))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SUGAR_CANE), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SUGAR_CANE, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(() -> helper.setBlock(belowBlock.north(), Blocks.IRON_BLOCK))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SUGAR_CANE), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SUGAR_CANE, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.SAND))
                .thenExecute(() -> helper.setBlock(belowBlock.north(), Blocks.LAVA))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SUGAR_CANE), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.SUGAR_CANE, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
                .thenExecute(() -> helper.setBlock(belowBlock.north(), Blocks.WATER))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SUGAR_CANE), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.SUGAR_CANE, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(() -> helper.setBlock(belowBlock.north(), Blocks.IRON_BLOCK))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SUGAR_CANE), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.SUGAR_CANE, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Small Dripleaf should be placeable on any block in water or on dry clay and moss blocks. And plantable on custom blocks that allow the plant even outside of water."
    })
    static void survivabilitySmallDripleafTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 2, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock.north(), Blocks.DIRT))
                .thenExecute(() -> helper.setBlock(belowBlock.south(), Blocks.DIRT))
                .thenExecute(() -> helper.setBlock(belowBlock.east(), Blocks.DIRT))
                .thenExecute(() -> helper.setBlock(belowBlock.west(), Blocks.DIRT))

                .thenExecute(() -> helper.setBlock(belowBlock.below(), Blocks.DIRT))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.WATER))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SMALL_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SMALL_DRIPLEAF, belowBlock))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SMALL_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock.below(), sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SMALL_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SMALL_DRIPLEAF, belowBlock))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SMALL_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.below(), Blocks.CLAY))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SMALL_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SMALL_DRIPLEAF, belowBlock))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SMALL_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.below(), Blocks.MOSS_BLOCK))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SMALL_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SMALL_DRIPLEAF, belowBlock))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.SMALL_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.below(), Blocks.GRASS_BLOCK))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.SMALL_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.SMALL_DRIPLEAF, belowBlock))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.SMALL_DRIPLEAF, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Big Dripleaf should be placeable on dirt, farmland, clay, moss block, and big dripleaf but not on sand. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityBigDripleafTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BIG_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BIG_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.FARMLAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BIG_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BIG_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.CLAY))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BIG_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BIG_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.MOSS_BLOCK))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BIG_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BIG_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BIG_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BIG_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.SAND))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.BIG_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.BIG_DRIPLEAF, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.BIG_DRIPLEAF))
                .thenExecute(player -> helper.useBlock(belowBlock.above(), player, new ItemStack(Items.BIG_DRIPLEAF), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.BIG_DRIPLEAF, belowBlock.above(2)))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Chorus Plant should be placeable on End Stone and itself but not on End Stone Bricks or Dirt. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityChorusPlantTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.END_STONE))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CHORUS_PLANT), Direction.UP))
                .thenExecute(() -> helper.assertBlockState(belowBlock.above(), (state) -> state.getValue(ChorusPlantBlock.DOWN), () -> "Chorus Plant not found with down property"))

                .thenExecute(player -> helper.useBlock(belowBlock.above(), player, new ItemStack(Items.CHORUS_PLANT), Direction.UP))
                .thenExecute(() -> helper.assertBlockState(belowBlock.above(2), (state) -> state.getValue(ChorusPlantBlock.DOWN), () -> "Chorus Plant not found with down property"))

                .thenExecute(() -> helper.setBlock(belowBlock.above(2), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.END_STONE_BRICKS))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CHORUS_PLANT), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.CHORUS_PLANT, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CHORUS_PLANT), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.CHORUS_PLANT, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CHORUS_PLANT), Direction.UP))
                .thenExecute(() -> helper.assertBlockState(belowBlock.above(), (state) -> state.getValue(ChorusPlantBlock.DOWN), () -> "Chorus Plant not found with down property"))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Chorus Flower should be placeable on End Stone and Chorus Plant but not on End Stone Bricks or Dirt. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityChorusFlowerTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos belowBlock = new BlockPos(1, 1, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.END_STONE))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CHORUS_FLOWER), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.CHORUS_FLOWER, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.CHORUS_PLANT))
                .thenExecute(player -> helper.useBlock(belowBlock.above(), player, new ItemStack(Items.CHORUS_FLOWER), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.CHORUS_FLOWER, belowBlock.above(2)))

                .thenExecute(() -> helper.setBlock(belowBlock.above(2), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.END_STONE_BRICKS))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CHORUS_FLOWER), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.CHORUS_FLOWER, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, Blocks.DIRT))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CHORUS_FLOWER), Direction.UP))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.CHORUS_FLOWER, belowBlock.above()))

                .thenExecute(() -> helper.setBlock(belowBlock.above(), Blocks.AIR))
                .thenExecute(() -> helper.setBlock(belowBlock, sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(belowBlock, player, new ItemStack(Items.CHORUS_FLOWER), Direction.UP))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.CHORUS_FLOWER, belowBlock.above()))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = {
            "Cocoa should be placeable on Jungle Logs but not on Oak Logs. And plantable on custom blocks that allow the plant."
    })
    static void survivabilityCocoaTest(final DynamicTest test, final RegistrationHelper reg) {
        final var sustainingBlock = reg.blocks()
                .registerBlock("super_sustaining_sustaining_block", properties -> new CustomSuperSustainingBlock(properties, Direction.SOUTH), BlockBehaviour.Properties.of())
                .withLang("Super Sustaining block")
                .withDefaultWhiteModel()
                .withBlockItem();

        final BlockPos centerBlock = new BlockPos(1, 2, 1);
        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL))
                .thenExecute(() -> helper.setBlock(centerBlock.north(), Blocks.JUNGLE_LOG))
                .thenExecute(player -> helper.useBlock(centerBlock.north(), player, new ItemStack(Items.COCOA_BEANS), Direction.SOUTH))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.COCOA, centerBlock))

                .thenExecute(() -> helper.setBlock(centerBlock, Blocks.AIR))
                .thenExecute(() -> helper.setBlock(centerBlock.north(), Blocks.OAK_LOG))
                .thenExecute(player -> helper.useBlock(centerBlock.north(), player, new ItemStack(Items.COCOA_BEANS), Direction.SOUTH))
                .thenExecute(() -> helper.assertBlockNotPresent(Blocks.COCOA, centerBlock))

                .thenExecute(() -> helper.setBlock(centerBlock, Blocks.AIR))
                .thenExecute(() -> helper.setBlock(centerBlock.north(), sustainingBlock.get()))
                .thenExecute(player -> helper.useBlock(centerBlock.north(), player, new ItemStack(Items.COCOA_BEANS), Direction.SOUTH))
                .thenExecute(() -> helper.assertBlockPresent(Blocks.COCOA, centerBlock))

                .thenSucceed());
    }

    @GameTest(batch = "plantSustainRecheck")
    @EmptyTemplate(value = "5x6x5", floor = true)
    @TestHolder(description = "An existing occluding cactus survives and grows on custom soil through real neighbor updates")
    static void existingCactusSurvivesAndGrows(final DynamicTest test, final RegistrationHelper reg) {
        final var soil = reg.blocks().registerBlock("rechecked_soil", CustomSuperSustainingBlock::new, BlockBehaviour.Properties.of());
        test.onGameTest(helper -> {
            final BlockPos soilPos = helper.absolutePos(new BlockPos(2, 1, 2));
            final BlockPos cactusPos = soilPos.above();
            final var level = helper.getLevel();
            helper.addEndListener(passed -> {
                level.setBlock(cactusPos.above(), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(cactusPos, Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(soilPos, Blocks.AIR.defaultBlockState(), 2);
            });
            level.setBlock(soilPos, soil.get().defaultBlockState(), 2);
            final BlockState cactus = Blocks.CACTUS.defaultBlockState().setValue(net.minecraft.world.level.block.CactusBlock.AGE, 15);
            level.setBlock(cactusPos, cactus, 2);
            helper.assertTrue(cactus.canOcclude(), "the regression must cover an already present occluding plant");
            helper.assertTrue(cactus.canSurvive(level, cactusPos), "an existing cactus must retain its valid soil support");
            assertInvalidPlantArguments(helper, () -> soil.get().defaultBlockState().canSustainPlant(level, soilPos, Direction.DOWN, cactus),
                    "Incorrect direction param passed in");
            assertInvalidPlantArguments(helper, () -> soil.get().defaultBlockState().canSustainPlant(level, soilPos.below(), Direction.UP, cactus),
                    "Incorrect soil position param passed in");
            // The same full vanilla growth/neighbor path that interrupted the C4.1 all-slice run.
            cactus.randomTick(level, cactusPos, RandomSource.create(42));
            helper.assertTrue(level.getBlockState(cactusPos).is(Blocks.CACTUS)
                    && level.getBlockState(cactusPos.above()).is(Blocks.CACTUS), "growth must retain the base and add exactly one cactus");
            System.out.println("C42_CACTUS_RECHECK bridge=" + net.neoforged.neoforge.server.threading.RegionThreadingHooks.diagnostics().isPresent()
                    + " thread=" + Thread.currentThread().getName() + " occluding=true growth=2");
            helper.succeed();
        });
    }

    private static void assertInvalidPlantArguments(net.minecraft.gametest.framework.GameTestHelper helper, Runnable call, String expected) {
        String failure = null;
        try {
            call.run();
        } catch (RuntimeException invalid) {
            failure = invalid.getMessage();
        }
        helper.assertTrue(expected.equals(failure), "the fixture must still reject invalid arguments: " + expected + ", got " + failure);
    }

    private static class CustomSuperSustainingBlock extends Block {
        public CustomSuperSustainingBlock(Properties properties) {
            this(properties, Direction.UP);
        }

        private final Direction expectedFacing;

        CustomSuperSustainingBlock(Properties properties, Direction expectedFacing) {
            super(properties);
            this.expectedFacing = expectedFacing;
        }

        @Override
        public TriState canSustainPlant(BlockState state, BlockGetter level, BlockPos soilPosition, Direction facing, BlockState plant) {
            if (level.getBlockState(soilPosition).getBlock() != this) {
                throw new RuntimeException("Incorrect soil position param passed in");
            }

            // canSurvive also runs after placement and during growth/neighbor updates. The plant may
            // already occupy this face and may itself occlude (notably CactusBlock); occlusion is not
            // evidence of an invalid direction. Each fixture instead declares the face it supports.
            if (facing != this.expectedFacing) {
                throw new RuntimeException("Incorrect direction param passed in");
            }

            return TriState.TRUE;
        }
    }
}
