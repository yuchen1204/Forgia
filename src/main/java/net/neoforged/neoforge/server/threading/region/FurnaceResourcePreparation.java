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
    private final Map<LevelChunk, Binding> chunks = new IdentityHashMap<>();
    private final BlockEntityResourceOwnership.Scratch scratch = new BlockEntityResourceOwnership.Scratch();
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

    BlockEntityResourceOwnership.Plan plan(List<Claims> claims) {
        return BlockEntityResourceOwnership.plan(claims, this.scratch);
    }

    Claims recapture(FurnaceWorkPreparation.Candidate captured, FurnaceWorkPreparation.Candidate fresh, Claims planned) {
        // A fresh prepare() has already re-audited every current item/component, recipe and notification.
        // Share only immutable local descriptors when all attachments and their captured empty/nonempty
        // state match. A zero-count stack can become nonempty in place without changing its reference.
        // Reservation still executes every original local validator against current world state.
        // Changed slots take a complete capture, preserving safe permutations of the same permission set.
        return captured.sameLocalAttachments(fresh)
                ? planned.withFreshSharedReads(java.util.Objects.requireNonNull(fresh.recipeReads()))
                : this.capture(fresh);
    }

    Claims capture(FurnaceWorkPreparation.Candidate candidate) {
        final var admission = candidate.admission();
        if (!this.layout.isFixed() || admission.level() != this.level) return this.fence(admission);
        final Object home = this.home(this.layout.regionAt(this.level, admission.position()));
        final var builder = Claims.builder(this.level, home);
        final var source = candidate.source();
        final var chunk = candidate.chunk();
        final var position = admission.position();
        final Binding chunkBinding = this.chunks.computeIfAbsent(chunk, loaded -> new Binding(new Identity(this.level, loaded), home,
                () -> loaded.getLevel() == this.level && loaded.isLoaded()
                        && this.level.getChunkSource().getChunkNow(loaded.getPos().x, loaded.getPos().z) == loaded,
                null));
        final BooleanSupplier chunkCurrent = chunkBinding.current();
        builder.claim(new Binding(new Identity(this.level, source), home,
                () -> chunkCurrent.getAsBoolean() && chunk.getPos().toLong() == ChunkPos.asLong(position)
                        && LevelChunk.furnaceWorkMatchesChunk(admission.ticker(), chunk)
                        && admission.isCurrent() && !source.isRemoved() && source.getLevel() == this.level
                        && chunk.getBlockEntities().get(position) == source,
                position), Access.WRITE);
        // Share only the immutable description inside this phase. Each unit still executes this chunk
        // validator, and its source proof independently checks the admission's exact chunk coordinates.
        builder.claim(chunkBinding, Access.READ);
        for (int slot = 0; slot < 3; ++slot) {
            final int capturedSlot = slot;
            final var stack = candidate.item(slot);
            if (!stack.isEmpty()) builder.claim(new Binding(new Identity(this.level, stack), home,
                    () -> source.getItem(capturedSlot) == stack, null), Access.WRITE);
        }
        // A catalog is a shared read publication, not a global write grant. Include its exposed mutable
        // stacks so inventory aliases cannot hide behind the coarse RecipeManager identity.
        builder.sharedReads(java.util.Objects.requireNonNull(candidate.recipeReads()));
        return builder.build();
    }

    BlockEntityResourceOwnership underHeldRegions(List<RegionState> leased, BooleanSupplier live) {
        final Map<Object, BooleanSupplier> boundaries = new IdentityHashMap<>(leased.size());
        for (final RegionState region : leased) {
            if (region.coordinate().level() != this.level) throw new IllegalStateException("Resource proof received a foreign region lease");
            // Only the engine's coordinator calls this after acquiring its own exact region leases.
            // No regionizer/lifecycle lock or world callback runs when a worker checks this predicate.
            boundaries.put(this.home(region.coordinate()), () -> region.isRunning() && !region.isDead());
        }
        return new BlockEntityResourceOwnership(this.level, boundaries, live, this.scratch);
    }

    Object home(RegionCoordinate coordinate) {
        return this.homes.computeIfAbsent(coordinate, ignored -> new Object());
    }
}
