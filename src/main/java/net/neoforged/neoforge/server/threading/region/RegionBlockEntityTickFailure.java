/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForgeConfig;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Carries a real bound ticker failure to the server without building its crash report on a leased worker. */
@ApiStatus.Internal
public final class RegionBlockEntityTickFailure extends RuntimeException {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionBlockEntityTickFailure.class);
    private final Level level;
    private final BlockEntity blockEntity;

    public RegionBlockEntityTickFailure(Level level, BlockEntity blockEntity, Throwable cause) {
        super("Region block entity tick failed", cause, false, false);
        this.level = level;
        this.blockEntity = blockEntity;
    }

    void report() {
        final CrashReport report = CrashReport.forThrowable(this.getCause(), "Ticking block entity");
        this.blockEntity.fillCrashReportCategory(report.addCategory("Block entity being ticked"));
        final boolean remove = NeoForgeConfig.isServerConfigLoaded()
                ? NeoForgeConfig.SERVER.removeErroringBlockEntities.get()
                : NeoForgeConfig.SERVER.removeErroringBlockEntities.getDefault();
        if (remove) {
            LOGGER.error("{}", report.getFriendlyReport(ReportType.CRASH));
            this.blockEntity.setRemoved();
            this.level.removeBlockEntity(this.blockEntity.getBlockPos());
        } else {
            throw new ReportedException(report);
        }
    }
}
