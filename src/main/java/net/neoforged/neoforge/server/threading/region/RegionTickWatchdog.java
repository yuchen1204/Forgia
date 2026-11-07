/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class RegionTickWatchdog {
    private static final Logger LOGGER = LogManager.getLogger();

    private RegionTickWatchdog() {}

    static void warnIfLongTick(String name, long durationNanos) {
        warnIfLongTick(name, durationNanos, null);
    }

    /**
     * Warns about a long region tick and, when {@code detail} is given, what the region was holding.
     * <p>
     * A bare duration only says that a region was slow; it cannot say whether the region held too many entities,
     * too many block entities or too many chunks. A live server spent an afternoon with one region at 1.1 s per
     * batch and the log could not name the cause, so the entity and block-entity slices pass a short inventory of
     * their region and the line becomes actionable.
     *
     * @param name          the slice and coordinate the duration belongs to
     * @param durationNanos the measured duration
     * @param detail        a short inventory of the region, or {@code null} when the slice has none
     */
    static void warnIfLongTick(String name, long durationNanos, @Nullable java.util.function.Supplier<String> detail) {
        final long threshold = RegionThreadingConfig.longTickWarningNanos();
        if (threshold > 0L && durationNanos > threshold) {
            if (detail == null) {
                LOGGER.warn("Region threading {} drain took {} ns", name, durationNanos);
            } else {
                LOGGER.warn("Region threading {} drain took {} ns ({})", name, durationNanos, detail.get());
            }
        }
    }
}
