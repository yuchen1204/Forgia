/*
 * Copyright (c) Forgia contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.mixin;

import dev.forgia.compat.RegionThreadsCompat;
import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Serializes the block-drop event seam and never lets a null drop list reach the vanilla loop.
 * <p>
 * A region worker runs a chunk's random ticks, so a leaf decay reaches {@code Block.dropResources} on a worker.
 * A mod that handles {@link BlockDropsEvent} through a shared, unsynchronized structure - Connector's
 * {@code CommonHooksMixin} is one, a 1.21.1 pack crashed with
 * {@code NullPointerException: BlockDropsEvent.getDrops() is null} inside {@code CommonHooks.handleBlockDrops} -
 * then races itself across the workers. Posting behind one lock keeps the mod's handler single-threaded exactly
 * like the vanilla server thread it was written for; the seam is low frequency, so the contention is noise.
 * <p>
 * The second redirect is the fail-closed half: if a handler still produces a null list, the vanilla loop sees an
 * empty list instead of throwing out of a chunk tick and taking the server down. Losing one drop is recoverable;
 * a crash is not.
 */
@Mixin(CommonHooks.class)
public class CommonHooksMixin {
    private static final Object FORGIA_COMPAT_EVENT_LOCK = new Object();

    @Redirect(method = "handleBlockDrops",
              at = @At(value = "INVOKE",
                       target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"))
    private static Event forgia$postSerialized(IEventBus bus, Event event) {
        if (!RegionThreadsCompat.isRegionThread()) {
            return bus.post(event);
        }
        synchronized (FORGIA_COMPAT_EVENT_LOCK) {
            return bus.post(event);
        }
    }

    @Redirect(method = "handleBlockDrops",
              at = @At(value = "INVOKE",
                       target = "Lnet/neoforged/neoforge/event/level/BlockDropsEvent;getDrops()Ljava/util/List;"))
    private static List<ItemEntity> forgia$neverNullDrops(BlockDropsEvent event) {
        final List<ItemEntity> drops = event.getDrops();
        return drops != null ? drops : List.of();
    }
}
