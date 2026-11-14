/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.function.BooleanSupplier;

/**
 * Closes participating work admission while a canonical phase owns the server's serial/parallel boundary.
 * This grants no world access: every body still needs the original Region lease, Guard and Ticket/Permit.
 * Marking an existing Region running/submitted and publishing a batch use this same monitor, so the idle
 * test and admission closure are atomic. The phase owner may acquire its normal leases; other callers
 * keep their tasks queued. Existing work is joined before this boundary can close, never stopped early.
 */
final class RegionWorkAdmission {
    private Thread phaseOwner;

    synchronized boolean allowsCurrentThread() {
        return this.phaseOwner == null || this.phaseOwner == Thread.currentThread();
    }

    synchronized boolean isOwner() {
        return this.phaseOwner == Thread.currentThread();
    }

    synchronized boolean tryStart(BooleanSupplier transition) {
        return this.allowsCurrentThread() && transition.getAsBoolean();
    }

    synchronized boolean trySubmit(BooleanSupplier transition) {
        // The coordinator may acquire its ordinary leases, but even it cannot start an unrelated
        // Region/GlobalRegion Worker from inside an Unknown callback or a prefix commit.
        return this.phaseOwner == null && transition.getAsBoolean();
    }

    synchronized boolean tryEnter(BooleanSupplier quiescent) {
        if (this.phaseOwner != null || !quiescent.getAsBoolean()) return false;
        this.phaseOwner = Thread.currentThread();
        return true;
    }

    synchronized void awaitPublication() {
        boolean interrupted = false;
        while (!this.allowsCurrentThread()) {
            try {
                this.wait();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    synchronized void exit() {
        if (!this.isOwner()) throw new IllegalStateException("Foreign canonical work-admission owner");
        this.phaseOwner = null;
        this.notifyAll();
    }
}
