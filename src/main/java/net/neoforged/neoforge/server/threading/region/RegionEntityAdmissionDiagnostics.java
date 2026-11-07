/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.ApiStatus;

/** Bounded diagnostics for deferred admission records that did not complete. A record may contain several entities. */
@ApiStatus.Internal
public final class RegionEntityAdmissionDiagnostics {
    public enum Reason {
        SHUTDOWN, LEVEL_CLOSED, SEAM_CLOSED, REMOVED, ALREADY_ADDED, LEVEL_CHANGED, VANILLA_REFUSED, PARTIAL_ADMISSION, EXCEPTION
    }

    private final Map<Reason, AtomicLong> counts = new EnumMap<>(Reason.class);
    // Only strings are retained: no diagnostic sample may keep an entity or an unloaded level alive.
    private final Map<Reason, String> samples = new EnumMap<>(Reason.class);

    public RegionEntityAdmissionDiagnostics() {
        for (final Reason reason : Reason.values()) {
            this.counts.put(reason, new AtomicLong());
        }
    }

    void record(Reason reason, long count) {
        this.counts.get(reason).addAndGet(count);
    }

    void record(Reason reason, Entity member, int graphSize) {
        this.record(reason, 1L);
        synchronized (this.samples) {
            if (!this.samples.containsKey(reason)) {
                this.samples.put(reason, "type=" + (member.getType() == null ? "unknown" : EntityType.getKey(member.getType())) + "/uuid=" + member.getUUID()
                        + "/removed=" + member.getRemovalReason() + "/added=" + member.isAddedToLevel()
                        + "/graphSize=" + graphSize);
            }
        }
    }

    public long count(Reason reason) {
        return this.counts.get(reason).get();
    }

    public String summaryLine() {
        final StringBuilder text = new StringBuilder("Region entity admission records: reasons={");
        for (final Reason reason : Reason.values()) {
            if (reason.ordinal() != 0) {
                text.append(',');
            }
            text.append(reason).append('=').append(this.count(reason));
        }
        synchronized (this.samples) {
            return text.append("} firstSamples=").append(this.samples).toString();
        }
    }
}
