/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.event.server;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import org.jetbrains.annotations.ApiStatus;

/**
 * Fired on NeoForge.EVENT_BUS after biome modifiers and ServerAboutToStartEvent, before worlds tick.
 * Register only during this event. Registration is server-scoped, including when region threading is off.
 * This does not certify other listeners as parallel safe or override a known compatibility restriction.
 */
@ApiStatus.Experimental
public final class RegisterParallelAdaptersEvent extends ServerLifecycleEvent {
    private final ParallelApi.Registrar registrar;

    @ApiStatus.Internal
    public RegisterParallelAdaptersEvent(MinecraftServer server, ParallelApi.Registrar registrar) {
        super(server);
        this.registrar = registrar;
    }

    public ParallelApi.Registrar registrar() {
        return this.registrar;
    }
}
