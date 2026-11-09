/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Entry point for Folia-style regionized server execution.
 * <p>
 * Implementations are provided by the dedicated server once the region threading core is installed.
 */
public interface RegionThreading {
    /**
     * {@return scheduler for work that belongs to server-global state}
     */
    GlobalRegionScheduler globalScheduler();

    /**
     * {@return scheduler for work owned by a chunk/region position}
     */
    RegionScheduler regionScheduler();

    /**
     * {@return scheduler for work owned by a specific entity}
     */
    EntityRegionScheduler entityScheduler();

    /** Whole interactions with explicit lifetime outcomes; call before changing either entity. */
    @org.jetbrains.annotations.ApiStatus.Experimental
    EntityInteractionScheduler entityInteractions();

    /**
     * {@return true when the current thread owns the region containing {@code pos}}
     */
    boolean isOwnedByCurrentRegion(ServerLevel level, BlockPos pos);

    /**
     * {@return true when the current thread owns {@code entity}}
     */
    boolean isOwnedByCurrentRegion(Entity entity);

    /**
     * Throws if the current thread does not own the region containing {@code pos}.
     */
    default void assertOwnedByCurrentRegion(ServerLevel level, BlockPos pos) {
        if (!this.isOwnedByCurrentRegion(level, pos)) {
            throw new IllegalStateException("Current thread does not own region at " + pos);
        }
    }

    /**
     * Throws if the current thread does not own {@code entity}.
     */
    default void assertOwnedByCurrentRegion(Entity entity) {
        if (!this.isOwnedByCurrentRegion(entity)) {
            throw new IllegalStateException("Current thread does not own entity " + entity);
        }
    }

    /**
     * Runs {@code task} immediately when the current thread owns {@code pos}, otherwise schedules it for the owning region.
     */
    default void executeOrSchedule(ServerLevel level, BlockPos pos, Runnable task) {
        if (this.isOwnedByCurrentRegion(level, pos)) {
            task.run();
        } else {
            this.regionScheduler().execute(level, pos, task);
        }
    }

    /**
     * Runs {@code task} immediately when the current thread owns {@code entity}, otherwise schedules it for the owning entity region.
     */
    default void executeOrSchedule(Entity entity, Runnable task) {
        if (this.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            this.entityScheduler().execute(entity, task);
        }
    }

    /**
     * {@return the current execution context}
     */
    RegionThreadingContext currentContext();
}
