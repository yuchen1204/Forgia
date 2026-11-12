/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Access;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Binding;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Claims;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Identity;

/** Bridges the existing audited furnace proof to generic resources; it does not admit another body/type. */
final class FurnaceResourcePreparation {
    private final ServerLevel level;
    private final RegionLayout layout;
    private final Map<RegionCoordinate, Object> homes = new HashMap<>();
    private int bindings;
    private boolean budgetExceeded;

    FurnaceResourcePreparation(ServerLevel level, RegionLayout layout) {
        this.level = level;
        this.layout = layout;
    }

    Claims fence(RegionBlockEntityTickAdmission admission) {
        return Claims.fence(this.level, this.home(this.layout.regionAt(this.level, admission.position())));
    }

    boolean budgetExceeded() {
        return this.budgetExceeded;
    }

    Claims captureForPlan(FurnaceWorkPreparation.Candidate candidate) {
        if (this.budgetExceeded) return this.fence(candidate.admission());
        final Claims claims = this.capture(candidate);
        this.bindings += claims.resourceCount();
        if (this.bindings > BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE) {
            this.budgetExceeded = true;
            return this.fence(candidate.admission());
        }
        return claims;
    }

    Claims capture(FurnaceWorkPreparation.Candidate candidate) {
        final var admission = candidate.admission();
        if (!this.layout.isFixed() || admission.level() != this.level) return this.fence(admission);
        final Object home = this.home(this.layout.regionAt(this.level, admission.position()));
        final var builder = Claims.builder(this.level, home);
        final var source = candidate.source();
        final var chunk = candidate.chunk();
        final var position = admission.position();
        final BooleanSupplier chunkCurrent = () -> chunk.getLevel() == this.level && chunk.isLoaded()
                && chunk.getPos().toLong() == ChunkPos.asLong(position)
                && this.level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == chunk;
        builder.claim(new Binding(new Identity(this.level, source), home,
                () -> chunkCurrent.getAsBoolean() && LevelChunk.furnaceWorkMatchesChunk(admission.ticker(), chunk)
                        && admission.isCurrent() && !source.isRemoved() && source.getLevel() == this.level
                        && chunk.getBlockEntities().get(position) == source,
                position), Access.WRITE);
        builder.claim(new Binding(new Identity(this.level, chunk), home, chunkCurrent, null), Access.READ);
        for (int slot = 0; slot < 3; ++slot) {
            final int capturedSlot = slot;
            final var stack = source.getItem(slot);
            if (!stack.isEmpty()) builder.claim(new Binding(new Identity(this.level, stack), home,
                    () -> source.getItem(capturedSlot) == stack, null), Access.WRITE);
        }
        // A catalog is a shared read publication, not a global write grant. Include its exposed mutable
        // stacks so inventory aliases cannot hide behind the coarse RecipeManager identity.
        final BooleanSupplier recipesCurrent = () -> this.level.getRecipeManager() == candidate.recipes();
        builder.claim(new Binding(new Identity(this.level, candidate.recipes()), null, recipesCurrent, null), Access.READ);
        for (final var stack : candidate.recipeReads()) {
            builder.claim(new Binding(new Identity(this.level, stack), null, recipesCurrent, null), Access.READ);
        }
        return builder.build();
    }

    BlockEntityResourceOwnership underHeldRegions(List<RegionState> leased, BooleanSupplier live) {
        final Map<Object, BooleanSupplier> boundaries = new IdentityHashMap<>();
        for (final RegionState region : leased) {
            if (region.coordinate().level() != this.level) throw new IllegalStateException("Resource proof received a foreign region lease");
            // Only the engine's coordinator calls this after acquiring its own exact region leases.
            // No regionizer/lifecycle lock or world callback runs when a worker checks this predicate.
            boundaries.put(this.home(region.coordinate()), () -> region.isRunning() && !region.isDead());
        }
        return new BlockEntityResourceOwnership(this.level, boundaries, live);
    }

    private Object home(RegionCoordinate coordinate) {
        return this.homes.computeIfAbsent(coordinate, ignored -> new Object());
    }
}
