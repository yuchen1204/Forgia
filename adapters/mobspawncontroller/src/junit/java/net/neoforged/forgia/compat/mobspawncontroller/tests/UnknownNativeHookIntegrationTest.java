/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.forgia.compat.mobspawncontroller.RuleSnapshots;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import org.junit.jupiter.api.Test;

/** Run only in the separately forked unknown-Mixin profile. */
class UnknownNativeHookIntegrationTest {
    @Test
    void aMergedUnverifiedNativeHookKeepsEvenSupportedNearbySettingsSerial() throws Throwable {
        assertTrue(Boolean.getBoolean("forgia.msc.testUnverifiedHook"));
        final var lookup = MethodHandles.publicLookup();
        final Class<?> manager = Class.forName("com.mobspawncontroller.command.MobSpawnManager");
        final var clear = lookup.findStatic(manager, "clearAll", MethodType.methodType(void.class));
        final Class<?> settings = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettings");
        final Class<?> codec = Class.forName("com.mobspawncontroller.natural.NaturalSpawnSettingsJsonCodec");
        final var json = new com.google.gson.JsonObject();
        json.addProperty("max_nearby", 1);
        final Object rule = lookup.findStatic(codec, "decode", MethodType.methodType(settings, com.google.gson.JsonObject.class)).invoke(json);
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel level = mock(ServerLevel.class);
        when(server.isSameThread()).thenReturn(true);
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        clear.invoke();
        try {
            lookup.findStatic(manager, "setNaturalSpawnSettings", MethodType.methodType(void.class, ResourceLocation.class, settings))
                    .invoke(ResourceLocation.parse("minecraft:zombie"), rule);
            assertTrue(RuleSnapshots.verify(), "normal adapter hooks are present");
            assertTrue(RuleSnapshots.capture().supportsOrderedFinalization(), "setting shape is supported; hook provenance is the refusal");
            ParallelAdapterRuntime.initialize(server, registrar -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent(server, registrar)));
            assertFalse(ParallelAdapterRuntime.preparePlan(level).workerReady());
            final var status = ParallelApi.forServer(server).orElseThrow().adapters().getFirst();
            assertEquals(1, status.serialPlans());
            assertEquals(ResourceLocation.parse("forgia_mobspawncontroller:unverified_spawn_hooks"), status.lastSerialReason().orElseThrow());
        } finally {
            ParallelAdapterRuntime.close(server);
            clear.invoke();
        }
    }
}
