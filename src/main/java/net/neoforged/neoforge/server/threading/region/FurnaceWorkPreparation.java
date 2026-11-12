/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/** Current-invocation proof for a narrow original vanilla body. Never uses observed historical footprints. */
final class FurnaceWorkPreparation {
    private static final Set<Class<?>> FURNACES = Set.of(FurnaceBlockEntity.class, BlastFurnaceBlockEntity.class, SmokerBlockEntity.class);
    private static final Set<Class<?>> RECIPES = Set.of(SmeltingRecipe.class, BlastingRecipe.class, SmokingRecipe.class);
    private static final Set<net.minecraft.world.level.block.Block> INERT = Set.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.DIRT, Blocks.BEDROCK, Blocks.GLASS);
    private static final List<Class<?>> AUDITED_CLASSES = List.of(AbstractFurnaceBlockEntity.class, FurnaceBlockEntity.class,
            BlastFurnaceBlockEntity.class, SmokerBlockEntity.class, BlockEntity.class,
            net.minecraft.world.level.block.entity.BaseContainerBlockEntity.class,
            net.minecraft.world.level.block.AbstractFurnaceBlock.class, LevelChunk.class,
            net.minecraft.world.level.Level.class, ServerLevel.class, net.minecraft.server.level.ServerChunkCache.class,
            net.minecraft.world.level.chunk.ChunkAccess.class, net.minecraft.world.level.block.Block.class,
            net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class,
            RecipeManager.class, Ingredient.class, AbstractCookingRecipe.class, SmeltingRecipe.class, BlastingRecipe.class,
            SmokingRecipe.class, ItemStack.class, Item.class, BlockItem.class, DyeItem.class, ItemNameBlockItem.class,
            net.neoforged.neoforge.common.crafting.CompoundIngredient.class);
    private static final Set<String> REPORTED_CATALOG_REFUSALS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final boolean VERIFIED_METHODS = unmodifiedMethods();
    private final Map<RecipeType<?>, Boolean> catalogs = new IdentityHashMap<>();
    private final ServerLevel level;
    private final RegionLayout layout;
    private final RecipeManager recipes;
    private final boolean verified;
    private FurnaceWorkMetrics.Refusal refusal = FurnaceWorkMetrics.Refusal.UNVERIFIED_RUNTIME;

    FurnaceWorkMetrics.Refusal refusal() {
        return this.refusal;
    }

    @Nullable
    private Candidate refuse(FurnaceWorkMetrics.Refusal reason) {
        this.refusal = reason;
        return null;
    }

    FurnaceWorkPreparation(ServerLevel level, RegionLayout layout) {
        this.level = level;
        this.layout = layout;
        this.recipes = level.getRecipeManager();
        this.verified = this.recipes != null && this.recipes.getClass() == RecipeManager.class && VERIFIED_METHODS;
    }

    private static boolean unmodifiedMethods() {
        try {
            for (final Class<?> type : AUDITED_CLASSES) {
                for (final var method : type.getDeclaredMethods()) {
                    if (method.getAnnotation(MixinMerged.class) != null) return false;
                }
            }
            for (final var block : INERT) {
                for (Class<?> type = block.getClass(); type != null; type = type.getSuperclass()) {
                    for (final var method : type.getDeclaredMethods()) {
                        if (method.getAnnotation(MixinMerged.class) != null) return false;
                    }
                }
            }
            return true;
        } catch (LinkageError | RuntimeException unavailable) {
            return false;
        }
    }

    @Nullable
    Candidate prepare(RegionBlockEntityTickAdmission admission) {
        if (!this.verified || net.neoforged.neoforge.server.timings.TimeTracker.BLOCK_ENTITY_UPDATE.isEnabled())
            return this.refuse(FurnaceWorkMetrics.Refusal.UNVERIFIED_RUNTIME);
        if (!admission.isCurrent() || admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED
                || RegionTickCompatibility.serverThreadOnlyTypes().containsKey(admission.type()))
            return this.refuse(FurnaceWorkMetrics.Refusal.INVALID_BINDING);
        final AbstractFurnaceBlockEntity source = LevelChunk.furnaceWorkSource(admission.ticker());
        if (source == null || !FURNACES.contains(source.getClass()) || source.hasAttachments()
                || source.getLevel() != this.level || source.isRemoved())
            return this.refuse(FurnaceWorkMetrics.Refusal.UNKNOWN_INSTANCE);
        if (!source.supportsFurnaceWorkTick()) return this.refuse(FurnaceWorkMetrics.Refusal.NOT_BURNING);
        final var block = source.getBlockState().getBlock();
        if (block != Blocks.FURNACE && block != Blocks.BLAST_FURNACE && block != Blocks.SMOKER)
            return this.refuse(FurnaceWorkMetrics.Refusal.UNKNOWN_INSTANCE);
        final var position = admission.position();
        final LevelChunk chunk = this.level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null || !chunk.isLoaded() || chunk.getBlockEntity(position, LevelChunk.EntityCreationType.CHECK) != source
                || chunk.getBlockState(position) != source.getBlockState())
            return this.refuse(FurnaceWorkMetrics.Refusal.UNAVAILABLE_CHUNK);
        for (int slot = 0; slot < 3; ++slot) if (!plain(source.getItem(slot))) return this.refuse(FurnaceWorkMetrics.Refusal.CUSTOM_ITEM);
        if (!this.catalogs.computeIfAbsent(source.furnaceWorkRecipeType(), this::pureCatalog)) return this.refuse(FurnaceWorkMetrics.Refusal.CUSTOM_RECIPE);
        if (source.furnaceWorkMayNotify() && !this.inertNotifications(position)) return this.refuse(FurnaceWorkMetrics.Refusal.ACTIVE_NOTIFICATIONS);
        return new Candidate(admission, source, chunk);
    }

    private boolean inertNotifications(net.minecraft.core.BlockPos position) {
        // setChanged calls six neighbours, and weak-change receivers one block beyond a conductor.
        // Unknown/active neighbours are serial fences before a body can alter an inventory.
        for (final var direction : net.minecraft.core.Direction.values()) {
            for (int distance = 1; distance <= 2; ++distance) {
                final var pos = position.relative(direction, distance);
                if (this.level.isOutsideBuildHeight(pos)) continue;
                if (!this.layout.isInRegion(this.level, pos, this.layout.regionAt(this.level, position))) return false;
                final var loaded = this.level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (loaded == null || !loaded.isLoaded() || !INERT.contains(loaded.getBlockState(pos).getBlock())) return false;
            }
        }
        return true;
    }

    private boolean pureCatalog(RecipeType<?> type) {
        if (type != RecipeType.SMELTING && type != RecipeType.BLASTING && type != RecipeType.SMOKING) return false;
        final var catalog = this.recipes.getAllRecipesFor((RecipeType) type);
        if (catalog.size() > 4096) return false;
        for (final Object entry : catalog) {
            final var recipe = ((net.minecraft.world.item.crafting.RecipeHolder<?>) entry).value();
            if (!RECIPES.contains(recipe.getClass())) return refusedCatalog((net.minecraft.world.item.crafting.RecipeHolder<?>) entry, recipe.getClass().getName());
            for (final var ingredient : recipe.getIngredients()) {
                if (!plainIngredient(ingredient, 0, new int[] { 256 })) return refusedCatalog((net.minecraft.world.item.crafting.RecipeHolder<?>) entry,
                        "ingredient " + (ingredient.isCustom() ? ingredient.getCustomIngredient().getClass().getName() : "unverified values"));
                // Audited ordinary ingredient resolution is deterministic; publish its lazy cache before workers.
                for (final var item : ingredient.getItems()) {
                    if (!item.isEmpty() && (!item.isComponentsPatchEmpty()
                            || !BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace().equals("minecraft")))
                        return false;
                }
            }
            final var result = recipe.getResultItem(this.level.registryAccess());
            if (!plain(result)) return refusedCatalog((net.minecraft.world.item.crafting.RecipeHolder<?>) entry,
                    "result " + BuiltInRegistries.ITEM.getKey(result.getItem()) + " class=" + result.getItem().getClass().getName()
                            + " componentPatch=" + !result.isComponentsPatchEmpty());
        }
        return true;
    }

    private static boolean plainIngredient(Ingredient ingredient, int depth, int[] remaining) {
        if (depth > 8 || --remaining[0] < 0) return false;
        if (ingredient.isCustom()) {
            final var custom = ingredient.getCustomIngredient();
            // NeoForge decodes ordinary vanilla ingredient arrays as this exact OR record.
            if (custom == null || custom.getClass() != net.neoforged.neoforge.common.crafting.CompoundIngredient.class) return false;
            final var children = ((net.neoforged.neoforge.common.crafting.CompoundIngredient) custom).children();
            if (children.size() > 64 || (children.getClass() != java.util.ArrayList.class
                    && !children.getClass().getName().startsWith("java.util.ImmutableCollections$")))
                return false;
            for (final var child : children) if (!plainIngredient(child, depth + 1, remaining)) return false;
            return true;
        }
        for (final var value : ingredient.getValues()) {
            if (value.getClass() != Ingredient.ItemValue.class && value.getClass() != Ingredient.TagValue.class) return false;
        }
        return true;
    }

    private static boolean refusedCatalog(net.minecraft.world.item.crafting.RecipeHolder<?> recipe, String detail) {
        final String key = recipe.id() + " " + detail;
        if (REPORTED_CATALOG_REFUSALS.size() < 8 && REPORTED_CATALOG_REFUSALS.add(key)) {
            com.mojang.logging.LogUtils.getLogger().info("Furnace work catalog kept serial: {} ({})", recipe.id(), detail);
        }
        return false;
    }

    static boolean plain(ItemStack stack) {
        if (stack.isEmpty()) return true;
        final Item item = stack.getItem();
        return stack.isComponentsPatchEmpty() && (item.getClass() == Item.class || item.getClass() == BlockItem.class || item.getClass() == DyeItem.class || item.getClass() == ItemNameBlockItem.class)
                && BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft");
    }

    record Candidate(RegionBlockEntityTickAdmission admission, AbstractFurnaceBlockEntity source, LevelChunk chunk) {}

    /** Public inventory references must be exclusive, even if a mod aliases two otherwise vanilla instances. */
    static Set<AbstractFurnaceBlockEntity> aliased(List<RegionBlockEntityTickAdmission> admissions) {
        final var owners = new IdentityHashMap<ItemStack, AbstractFurnaceBlockEntity>();
        final Set<AbstractFurnaceBlockEntity> bad = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (final var admission : admissions) {
            final var source = LevelChunk.furnaceWorkSource(admission.ticker());
            if (source == null || !FURNACES.contains(source.getClass())) continue;
            for (int slot = 0; slot < 3; ++slot) {
                final var stack = source.getItem(slot);
                if (stack.isEmpty()) continue;
                final var previous = owners.putIfAbsent(stack, source);
                if (previous != null) {
                    bad.add(previous);
                    bad.add(source);
                }
            }
        }
        return bad;
    }
}
