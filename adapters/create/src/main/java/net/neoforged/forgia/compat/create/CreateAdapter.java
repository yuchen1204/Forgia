/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent;
import net.neoforged.neoforge.server.threading.api.ParallelApi;

/** Standalone server adapter. No changes to the installed Create jar or global engine switches. */
@Mod(CreateAdapter.MOD_ID)
public final class CreateAdapter {
    public static final String MOD_ID = "forgia_create";

    public CreateAdapter() {
        NeoForge.EVENT_BUS.addListener(CreateAdapter::register);
    }

    private static void register(RegisterParallelAdaptersEvent event) {
        if (!event.registrar().capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_TICK_ADMISSION)) {
            throw new IllegalStateException("Forgia Create requires the BLOCK_ENTITY_TICK_ADMISSION capability");
        }
        event.registrar().registerBlockEntityTicks(ParallelApi.MAJOR_VERSION, CreateTickAdmission.ID,
                CreateTickAdmission.TYPES, new CreateTickAdmission());
        LogUtils.getLogger().info("Forgia Create registered steady tick admission; topology, recipe and unknown behaviour callbacks retain full serial ticks");
    }
}
