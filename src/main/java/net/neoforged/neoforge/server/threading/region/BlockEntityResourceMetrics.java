/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.LongAdder;
import org.jetbrains.annotations.ApiStatus;

/** Ownership overhead only. Issued leases are not native body completions or milliseconds saved. */
@ApiStatus.Internal
public final class BlockEntityResourceMetrics {
    private final LongAdder phases = new LongAdder();
    private final LongAdder proofs = new LongAdder();
    private final LongAdder waves = new LongAdder();
    private final LongAdder refused = new LongAdder();
    private final LongAdder leases = new LongAdder();
    private final LongAdder proofAndPlanNanos = new LongAdder();
    private final LongAdder reservationNanos = new LongAdder();

    void prepared(int proofs, long nanos) {
        this.phases.increment();
        this.proofs.add(proofs);
        this.proofAndPlanNanos.add(nanos);
    }

    void reserved(int leases, long nanos) {
        this.reservationNanos.add(nanos);
        if (leases < 0) this.refused.increment();
        else {
            this.waves.increment();
            this.leases.add(leases);
        }
    }

    public record Snapshot(long phases, long proofs, long waves, long refusedWaves, long issuedLeases,
            long proofAndPlanNanos, long reservationNanos) {}

    public Snapshot snapshot() {
        return new Snapshot(this.phases.sum(), this.proofs.sum(), this.waves.sum(), this.refused.sum(),
                this.leases.sum(), this.proofAndPlanNanos.sum(), this.reservationNanos.sum());
    }
}
