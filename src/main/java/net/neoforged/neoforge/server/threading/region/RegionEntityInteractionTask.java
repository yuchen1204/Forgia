/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.EntityInteractionTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** One transferable request. No world callback is invoked while holding its monitor. */
@ApiStatus.Internal
public final class RegionEntityInteractionTask implements EntityInteractionTask {
    private static final TicketType<UUID> OWNER_OPERATION = TicketType.create("forgia_owner_operation", UUID::compareTo);
    @Nullable
    private BlockPos loadPosition;
    @Nullable
    private BiConsumer<Entity, LevelChunk> loadedAction;
    @Nullable
    private Entity source;
    @Nullable
    private Entity target;
    @Nullable
    private ServerLevel level;
    @Nullable
    private BiConsumer<Entity, Entity> action;
    @Nullable
    private Consumer<Entity> ownerAction;
    @Nullable
    private Throwable failure;
    private State state = State.QUEUED;
    private Reason reason = Reason.NONE;
    private final Consumer<State> terminalObserver;

    RegionEntityInteractionTask(Entity source, Entity target, @Nullable ServerLevel level, BiConsumer<Entity, Entity> action,
            Consumer<State> terminalObserver) {
        this.source = source;
        this.target = target;
        this.level = level;
        this.action = action;
        this.terminalObserver = terminalObserver;
    }

    RegionEntityInteractionTask(Entity target, @Nullable ServerLevel level, Consumer<Entity> action,
            Consumer<State> terminalObserver) {
        this.target = target;
        this.level = level;
        this.ownerAction = action;
        this.terminalObserver = terminalObserver;
    }

    RegionEntityInteractionTask(Entity target, @Nullable ServerLevel level, BlockPos position,
            BiConsumer<Entity, LevelChunk> action, Consumer<State> terminalObserver) {
        this.target = target;
        this.level = level;
        this.loadPosition = position.immutable();
        this.loadedAction = action;
        this.terminalObserver = terminalObserver;
    }

    @Override
    public synchronized State state() {
        return this.state;
    }

    @Override
    public synchronized Reason reason() {
        return this.reason;
    }

    @Override
    public synchronized Optional<Throwable> failure() {
        return Optional.ofNullable(this.failure);
    }

    @Override
    public synchronized boolean cancel() {
        return this.retire(State.CANCELLED, Reason.CANCELLED);
    }

    synchronized boolean retire(State terminal, Reason reason) {
        if (this.state != State.QUEUED) {
            return false;
        }
        this.finish(terminal, reason, null);
        return true;
    }

    /** Called only at a quiescent server-thread execution point. Returns a failure for normal batch reporting. */
    @Nullable
    Throwable execute(Supplier<Reason> lifecycle) {
        final Entity source;
        final Entity target;
        final ServerLevel level;
        final BiConsumer<Entity, Entity> action;
        final Consumer<Entity> ownerAction;
        final BlockPos loadPosition;
        final BiConsumer<Entity, LevelChunk> loadedAction;
        synchronized (this) {
            if (this.state != State.QUEUED) {
                return null;
            }
            this.state = State.RUNNING;
            source = this.source;
            target = this.target;
            level = this.level;
            action = this.action;
            ownerAction = this.ownerAction;
            loadPosition = this.loadPosition;
            loadedAction = this.loadedAction;
            this.clearReferences();
        }
        State result = State.COMPLETED;
        Reason reason = Reason.NONE;
        Throwable failure = null;
        try {
            if ((source != null && source.isRemoved()) || target.isRemoved()) {
                result = State.SKIPPED;
                reason = Reason.ENTITY_REMOVED;
            } else if ((source != null && source.level() != level) || target.level() != level) {
                result = State.SKIPPED;
                reason = Reason.LEVEL_CHANGED;
            } else if (loadedAction != null) {
                reason = lifecycle.get();
                if (reason == Reason.NONE) {
                    final ChunkPos chunk = new ChunkPos(loadPosition);
                    final UUID ticket = UUID.randomUUID();
                    final var chunks = level.getChunkSource();
                    Throwable loadFailure = null;
                    try {
                        // Radius zero requests FULL without force-ticking the destination. Unique keys prevent
                        // nested operations for the same chunk from removing one another's retention ticket.
                        chunks.addRegionTicket(OWNER_OPERATION, chunk, 0, ticket);
                        final LevelChunk loaded = level.getChunk(chunk.x, chunk.z);
                        reason = lifecycle.get();
                        if (reason == Reason.NONE) {
                            if (target.isRemoved()) {
                                reason = Reason.ENTITY_REMOVED;
                            } else if (target.level() != level) {
                                reason = Reason.LEVEL_CHANGED;
                            } else {
                                loadedAction.accept(target, java.util.Objects.requireNonNull(loaded, "loaded chunk"));
                            }
                        }
                    } catch (Throwable throwable) {
                        loadFailure = throwable;
                    } finally {
                        try {
                            chunks.removeRegionTicket(OWNER_OPERATION, chunk, 0, ticket);
                        } catch (Throwable releaseFailure) {
                            if (loadFailure == null) {
                                loadFailure = releaseFailure;
                            } else if (loadFailure != releaseFailure) {
                                loadFailure.addSuppressed(releaseFailure);
                            }
                        }
                    }
                    if (loadFailure != null) {
                        result = State.FAILED;
                        failure = loadFailure;
                    }
                }
                if (failure == null && reason != Reason.NONE) {
                    result = State.SKIPPED;
                }
            } else {
                // Ordinary pair/owner operations need the same quiescent completion proof as loading.
                // A proof can run lifecycle checks; validate participants again after it returns.
                reason = lifecycle.get();
                if (reason == Reason.NONE) {
                    if ((source != null && source.isRemoved()) || target.isRemoved()) {
                        reason = Reason.ENTITY_REMOVED;
                    } else if ((source != null && source.level() != level) || target.level() != level) {
                        reason = Reason.LEVEL_CHANGED;
                    } else if (ownerAction != null) {
                        ownerAction.accept(target);
                    } else {
                        action.accept(source, target);
                    }
                }
                if (reason != Reason.NONE) result = State.SKIPPED;
            }
        } catch (Throwable throwable) {
            result = State.FAILED;
            failure = throwable;
        }
        synchronized (this) {
            this.finish(result, reason, failure);
        }
        return failure;
    }

    private void finish(State state, Reason reason, @Nullable Throwable failure) {
        this.clearReferences();
        this.reason = reason;
        this.failure = failure;
        this.state = state;
        this.terminalObserver.accept(state);
    }

    private void clearReferences() {
        this.source = null;
        this.target = null;
        this.level = null;
        this.action = null;
        this.ownerAction = null;
        this.loadPosition = null;
        this.loadedAction = null;
    }
}
