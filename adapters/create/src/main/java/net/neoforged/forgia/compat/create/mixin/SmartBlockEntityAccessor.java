/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SmartBlockEntity.class, remap = false)
public interface SmartBlockEntityAccessor {
    @Accessor("initialized")
    boolean forgia$isInitialized();

    @Accessor("lazyTickCounter")
    int forgia$lazyTickCounter();
}
