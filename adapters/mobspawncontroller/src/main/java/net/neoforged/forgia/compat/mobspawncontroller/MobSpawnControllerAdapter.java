/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.threading.api.ParallelApi;

/** Conservative parallel admission; world-dependent configurations keep the original complete serial body. */
@Mod(MobSpawnControllerAdapter.MOD_ID)
public final class MobSpawnControllerAdapter {
    public static final String MOD_ID = "forgia_mobspawncontroller";
    /** Experimental opt-in; local pack validation found truncated spawn work and no proven speedup. */
    private static final String ORDERED_OPT_IN = "forgia.mscOrderedSpawning";

    public MobSpawnControllerAdapter() {
        NeoForge.EVENT_BUS.addListener(MobSpawnControllerAdapter::register);
        NeoForge.EVENT_BUS.addListener(MobSpawnControllerAdapter::stopped);
    }

    private static void register(RegisterParallelAdaptersEvent event) {
        event.registrar().register(ParallelApi.MAJOR_VERSION, RuleSnapshots.KEY, new ParallelApi.NaturalSpawnPreparation<RuleSnapshots.Snapshot>() {
            @Override
            public ParallelApi.Preparation<RuleSnapshots.Snapshot> preparePlan(ParallelApi.SpawnPlan plan) {
                return RuleSnapshots.prepareRules();
            }

            @Override
            public ParallelApi.Preparation<RuleSnapshots.Snapshot> prepare(ParallelApi.SpawnUnit unit) {
                return RuleSnapshots.prepare(unit);
            }
        });
        if (Boolean.getBoolean(ORDERED_OPT_IN)) {
            event.registrar().claimNaturalSpawning(RuleSnapshots.KEY, "mobspawncontroller", RuleSnapshots::verify);
        }
    }

    private static void stopped(ServerStoppedEvent event) {
        RuleSnapshots.mutate(() -> {});
    }
}
