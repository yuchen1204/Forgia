/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.TorquePropagator;
import java.util.Map;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TorquePropagator.class, remap = false)
public interface TorquePropagatorAccessor {
    /** Read only on the quiescent server, never accessed by the adapter's worker predicate. */
    @Accessor("networks")
    static Map<LevelAccessor, Map<Long, KineticNetwork>> forgia$networks() {
        throw new AssertionError("Mixin accessor not applied");
    }
}
