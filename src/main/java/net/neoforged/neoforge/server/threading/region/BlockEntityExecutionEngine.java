/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Inspection;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Mode;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Outcome;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Refresh;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Resources;
import org.jetbrains.annotations.Nullable;

/**
 * Shared invocation authority, subordinate to a published BE batch and its real outer region leases.
 * Dispatch, auditing, effects and original tickers remain in the two protected execution bridges.
 * No adapter receives this engine, its tickets, region guards or permits.
 */
final class BlockEntityExecutionEngine implements AutoCloseable {
    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();
    private final ServerLevel level;
    private final Thread coordinator = Thread.currentThread();
    private final BooleanSupplier live;
    private final Function<BlockPos, RegionState> home;
    private final Map<TickingBlockEntity, InvocationTicket> tickets = new IdentityHashMap<>();
    private final Set<RegionGuard> guards = ConcurrentHashMap.newKeySet();
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean open = true;

    BlockEntityExecutionEngine(ServerLevel level, List<RegionBlockEntityTickAdmission> admissions, BooleanSupplier live,
            Function<BlockPos, RegionState> home) {
        this.level = level;
        this.live = live;
        this.home = home;
        for (final var admission : admissions) {
            if (admission.level() != level || this.tickets.put(admission.ticker(), new InvocationTicket(this, admission)) != null)
                throw new IllegalArgumentException("Duplicate ticker or foreign level in block-entity phase");
        }
    }

    static Mode currentMode() {
        final var permit = CURRENT.get();
        return permit == null || permit.guard == null ? null : permit.guard.mode;
    }

    private void requireOpen() {
        if (!this.open) throw new IllegalStateException("Block-entity execution phase is closed");
    }

    synchronized InvocationTicket ticket(RegionBlockEntityTickAdmission admission) {
        this.requireOpen();
        final var ticket = this.tickets.get(admission.ticker());
        if (ticket == null || ticket.admission != admission) throw new IllegalStateException("Foreign block-entity invocation");
        return ticket;
    }

    InvocationTicket inspect(RegionBlockEntityTickAdmission admission, Inspection inspection) {
        final var ticket = this.ticket(admission);
        synchronized (ticket) {
            if (ticket.state != State.CAPTURED) throw new IllegalStateException("Invocation already inspected or started");
            ticket.inspection = inspection;
            ticket.state = State.INSPECTED;
            return ticket;
        }
    }

    RegionGuard regionOwner(RegionState region) {
        return new RegionGuard(this, region, Mode.REGION_OWNER, region);
    }

    RegionGuard subordinate(RegionState region) {
        return this.subordinate(region, region);
    }

    RegionGuard subordinate(RegionState region, Object resourceHome) {
        if (Thread.currentThread() != this.coordinator) throw new IllegalStateException("Subordinate work requires its coordinator");
        return new RegionGuard(this, region, Mode.RESOURCE_SCOPED, resourceHome);
    }

    @Nullable
    Permit permit(InvocationTicket ticket, RegionGuard guard, Refresh refresh, @Nullable BlockEntityResourceOwnership.Lease resources) {
        this.requireOpen();
        synchronized (ticket) {
            if (ticket.phase != this || guard.phase != this || ticket.state != State.INSPECTED
                    || ticket.inspection != refresh.inspection())
                throw new IllegalStateException("Foreign, stale or already started block-entity authorization");
            final var inspection = ticket.inspection;
            if (refresh.outcome() != Outcome.CANDIDATE || inspection.outcome() != Outcome.CANDIDATE
                    || !this.live.getAsBoolean() || !ticket.admission.isCurrent()
                    || this.home.apply(ticket.admission.position()) != guard.region)
                return null;
            final var descriptor = inspection.descriptor();
            if (descriptor == null || descriptor.mode() != guard.mode
                    || !descriptor.types().contains(ResourceLocation.tryParse(ticket.admission.type())))
                throw new IllegalStateException("Execution description does not cover this invocation");
            if (guard.mode == Mode.REGION_OWNER && !ticket.admission.isAdmitted()) return null;
            if (guard.mode == Mode.RESOURCE_SCOPED
                    && ticket.admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED)
                return null;
            if (inspection.resources() == Resources.FURNACE_CLAIMS) {
                if (resources == null || !resources.isReservedFor(this.level, guard.resourceHome))
                    throw new IllegalStateException("Invocation lacks its actual resource lease");
            } else if (resources != null) {
                throw new IllegalStateException("Unexpected resource lease on a different execution path");
            }
            return this.start(ticket, guard, resources);
        }
    }

    /** A true serial fence starts only after all worker authorities have returned. */
    Permit beginSerial(RegionBlockEntityTickAdmission admission) {
        this.requireCoordinator();
        if (this.active.get() != 0 || !this.guards.isEmpty()) throw new IllegalStateException("Serial fence overlaps worker authority");
        return this.start(this.ticket(admission), null, null);
    }

    /** Preserves the old executor-rejection path; it is not a quiescent global serial fence. */
    Permit beginInline(RegionBlockEntityTickAdmission admission, RegionGuard guard) {
        this.requireCoordinator();
        if (guard.phase != this || guard.mode != Mode.REGION_OWNER || this.home.apply(admission.position()) != guard.region)
            throw new IllegalStateException("Foreign inline region authority");
        return this.start(this.ticket(admission), guard, null);
    }

    private void requireCoordinator() {
        this.requireOpen();
        if (Thread.currentThread() != this.coordinator) throw new IllegalStateException("Server invocation requires the phase coordinator");
    }

    private Permit start(InvocationTicket ticket, @Nullable RegionGuard guard, @Nullable BlockEntityResourceOwnership.Lease resources) {
        synchronized (ticket) {
            this.requireOpen();
            if (ticket.phase != this || (ticket.state != State.CAPTURED && ticket.state != State.INSPECTED)
                    || CURRENT.get() != null)
                throw new IllegalStateException("Invocation already started or nested");
            if (guard != null) {
                synchronized (guard.region) {
                    if (guard.closed || guard.region.blockEntityExecution != guard || !guard.region.isRunning()
                            || (guard.mode == Mode.REGION_OWNER && !guard.region.isOwnedByCurrentThread())
                            || (guard.mode == Mode.RESOURCE_SCOPED && (guard.sealed || guard.region.hasOwner())))
                        throw new IllegalStateException("Invocation lost its outer region authority");
                    if (guard.mode == Mode.REGION_OWNER && guard.active != 0)
                        throw new IllegalStateException("A region owner cannot execute two native invocations at once");
                    ++guard.active;
                }
            }
            ticket.state = State.RUNNING;
            this.active.incrementAndGet();
            final var permit = new Permit(ticket, guard, resources);
            CURRENT.set(permit);
            return permit;
        }
    }

    @Override
    public synchronized void close() {
        this.requireCoordinator();
        if (this.active.get() != 0 || !this.guards.isEmpty()) throw new IllegalStateException("Cannot retire active execution authority");
        this.open = false;
        for (final var ticket : this.tickets.values()) {
            synchronized (ticket) {
                if (ticket.state == State.CAPTURED || ticket.state == State.INSPECTED) ticket.state = State.CANCELLED;
            }
        }
        this.tickets.clear();
    }

    enum State {
        CAPTURED, INSPECTED, RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    static final class InvocationTicket {
        private final BlockEntityExecutionEngine phase;
        private final RegionBlockEntityTickAdmission admission;
        private volatile State state = State.CAPTURED;
        @Nullable
        private Inspection inspection;

        private InvocationTicket(BlockEntityExecutionEngine phase, RegionBlockEntityTickAdmission admission) {
            this.phase = phase;
            this.admission = admission;
        }

        Inspection inspection() {
            if (this.inspection == null) throw new IllegalStateException("Invocation has no inspection");
            return this.inspection;
        }

        State state() {
            return this.state;
        }
    }

    static final class RegionGuard implements AutoCloseable {
        private final BlockEntityExecutionEngine phase;
        private final RegionState region;
        private final Mode mode;
        private final Object resourceHome;
        private final Thread coordinator = Thread.currentThread();
        private int active;
        private boolean closed;
        private boolean sealed;

        private RegionGuard(BlockEntityExecutionEngine phase, RegionState region, Mode mode, Object resourceHome) {
            this.phase = phase;
            this.region = region;
            this.mode = mode;
            this.resourceHome = resourceHome;
            synchronized (region) {
                phase.requireOpen();
                if (region.coordinate().level() != phase.level || !region.isRunning() || region.blockEntityExecution != null
                        || (mode == Mode.REGION_OWNER ? !region.isOwnedByCurrentThread() : region.hasOwner()))
                    throw new IllegalStateException("Conflicting block-entity region authorities");
                region.blockEntityExecution = this;
                phase.guards.add(this);
            }
        }

        /** Fine workers cannot acquire full ownership; only the joined coordinator can commit. */
        boolean mayBeginOwnership() {
            return !this.closed && this.coordinator == Thread.currentThread() && this.active == 0
                    && (this.mode == Mode.REGION_OWNER || this.sealed);
        }

        /** After join (or cancellation before submission), revoke all future starts before commit. */
        void sealForCommit() {
            synchronized (this.region) {
                if (this.coordinator != Thread.currentThread() || this.closed || this.active != 0 || this.mode != Mode.RESOURCE_SCOPED)
                    throw new IllegalStateException("Cannot commit before subordinate work returns");
                this.sealed = true;
            }
        }

        @Override
        public void close() {
            synchronized (this.region) {
                if (this.coordinator != Thread.currentThread() || this.closed || this.active != 0
                        || this.region.blockEntityExecution != this)
                    throw new IllegalStateException("Cannot release running, foreign or closed region authority");
                this.closed = true;
                this.region.blockEntityExecution = null;
                this.phase.guards.remove(this);
            }
        }
    }

    static final class Permit implements AutoCloseable {
        private final InvocationTicket ticket;
        @Nullable
        private final RegionGuard guard;
        @Nullable
        private final BlockEntityResourceOwnership.Lease resources;
        private final Thread thread = Thread.currentThread();
        private boolean succeeded;
        private boolean closed;

        private Permit(InvocationTicket ticket, @Nullable RegionGuard guard, @Nullable BlockEntityResourceOwnership.Lease resources) {
            this.ticket = ticket;
            this.guard = guard;
            this.resources = resources;
        }

        private void requireCurrent() {
            if (Thread.currentThread() != this.thread || CURRENT.get() != this || this.closed)
                throw new IllegalStateException("Foreign, nested or closed invocation permit");
        }

        void succeeded() {
            this.requireCurrent();
            if (this.succeeded || (this.resources != null && !this.resources.consumed()))
                throw new IllegalStateException("Invocation already completed or resource scope has not returned");
            this.succeeded = true;
        }

        @Override
        public void close() {
            this.requireCurrent();
            if (this.resources != null && this.resources.entered())
                throw new IllegalStateException("Cannot release an invocation with an active resource scope");
            this.closed = true;
            this.ticket.state = this.succeeded ? State.SUCCEEDED : State.FAILED;
            CURRENT.remove();
            if (this.guard != null) {
                synchronized (this.guard.region) {
                    --this.guard.active;
                }
            }
            this.ticket.phase.active.decrementAndGet();
        }
    }
}
