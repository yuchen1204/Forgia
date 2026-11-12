/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionWorkerScope;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Server-scoped implementation; opaque references retain neither a world nor a block entity strongly. */
@ApiStatus.Internal
public final class BlockEntityOperationRuntime implements BlockEntityOperations {
    private final MinecraftServer server;
    private final BooleanSupplier open;
    private final Object referenceOwner = new Object();
    private final Counts counts = new Counts();

    BlockEntityOperationRuntime(MinecraftServer server, BooleanSupplier open) {
        this.server = server;
        this.open = open;
    }

    @Override
    public Reference reference(BlockEntity blockEntity) {
        Objects.requireNonNull(blockEntity);
        if (!this.open.getAsBoolean() || !(blockEntity.getLevel() instanceof ServerLevel level) || level.getServer() != this.server) {
            throw new IllegalStateException("A block entity reference requires a live block entity of this server");
        }
        final RegionThreadingEngine engine = engine();
        final boolean allowed = RegionWorkerScope.isNativeWorkerThread()
                ? engine != null && engine.isCurrentBlockEntityOperationSource(blockEntity)
                : this.server.isSameThread() && (engine == null || (engine.canRunEntityInteractionImmediately(level) && engine.isQuiescentForParallelApi()));
        if (!allowed) {
            throw new IllegalStateException("A block entity reference requires its exact admitted ticker or a quiescent server thread");
        }
        final Ref reference = new Ref(this.referenceOwner, level, blockEntity);
        if (resolve(reference) != blockEntity || blockEntity.isRemoved()) {
            throw new IllegalStateException("A block entity reference requires an attached instance in a loaded chunk");
        }
        return reference;
    }

    @Override
    public <R> Task<R> submit(Reference source, Reference target, Operation<R> operation) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        Objects.requireNonNull(operation);
        final Ref first = source instanceof Ref ref && ref.owner == this.referenceOwner ? ref : null;
        final Ref second = target instanceof Ref ref && ref.owner == this.referenceOwner ? ref : null;
        final Request<R> request = new Request<>(first, second, operation, this.counts);
        if (!this.open.getAsBoolean()) {
            request.retire(State.REJECTED, Reason.SHUTDOWN);
        } else if (first == null || second == null) {
            request.retire(State.REJECTED, Reason.INVALID_REFERENCE);
        } else if (first.level.get() == null || first.level.get() != second.level.get()) {
            request.retire(State.REJECTED, Reason.LEVEL_CHANGED);
        } else {
            final RegionThreadingEngine engine = engine();
            if (engine != null) {
                engine.submitBlockEntityOperation(request, () -> this.lifecycle(first.level.get()));
            } else if (!this.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) {
                request.retire(State.REJECTED, Reason.INVALID_CONTEXT);
            } else {
                request.accepted();
                rethrow(request.execute(() -> this.lifecycle(first.level.get())));
            }
        }
        return request;
    }

    private Reason lifecycle(@Nullable ServerLevel level) {
        if (!this.open.getAsBoolean()) return Reason.SHUTDOWN;
        if (level == null || level.getServer() != this.server) return Reason.LEVEL_CHANGED;
        final RegionThreadingEngine engine = engine();
        return engine != null ? engine.blockEntityOperationLifecycle(level)
                : this.server.isSameThread() && !RegionWorkerScope.isNativeWorkerThread() ? Reason.NONE : Reason.INVALID_CONTEXT;
    }

    @Nullable
    private static RegionThreadingEngine engine() {
        return RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine ? engine : null;
    }

    @Override
    public Task<TransferResult> transfer(Reference source, Reference target, Transfer request) {
        Objects.requireNonNull(request);
        return this.submit(source, target, (from, to) -> transferNow(from, to, request));
    }

    private static TransferResult transferNow(BlockEntity first, BlockEntity second, Transfer request) {
        if (first == second) return new TransferResult(0, TransferStatus.SAME_CONTAINER);
        if (!(first instanceof Container source) || !(second instanceof Container target)) {
            return new TransferResult(0, TransferStatus.UNSUPPORTED_CONTAINER);
        }
        final int from = request.sourceSlot();
        final int to = request.targetSlot();
        if (from >= source.getContainerSize() || to >= target.getContainerSize()) return new TransferResult(0, TransferStatus.INVALID_SLOT);
        if ((source instanceof WorldlyContainer && request.extractionSide() == null)
                || (target instanceof WorldlyContainer && request.insertionSide() == null))
            return new TransferResult(0, TransferStatus.SIDE_REQUIRED);
        final ItemStack input = source.getItem(from);
        if (input.isEmpty()) return new TransferResult(0, TransferStatus.NO_ITEMS);
        if (!source.canTakeItem(target, from, input) || !target.canPlaceItem(to, input)
                || (source instanceof WorldlyContainer sided && (!exposes(sided, from, request.extractionSide())
                        || !sided.canTakeItemThroughFace(from, input, request.extractionSide())))
                || (target instanceof WorldlyContainer sided && (!exposes(sided, to, request.insertionSide())
                        || !sided.canPlaceItemThroughFace(to, input, request.insertionSide())))) {
            return new TransferResult(0, TransferStatus.ITEM_REJECTED);
        }
        final ItemStack output = target.getItem(to);
        if (!output.isEmpty() && !ItemStack.isSameItemSameComponents(input, output)) return new TransferResult(0, TransferStatus.ITEM_REJECTED);
        final int space = target.getMaxStackSize(input) - output.getCount();
        final int moved = Math.min(request.maxItems(), Math.min(input.getCount(), space));
        if (moved <= 0) return new TransferResult(0, TransferStatus.DESTINATION_FULL);
        final ItemStack expected = input.copyWithCount(moved);
        final ItemStack extracted = source.removeItem(from, moved);
        if (extracted.getCount() != moved || !ItemStack.isSameItemSameComponents(expected, extracted)) {
            throw new IllegalStateException("Container extraction violated the admitted transfer; partial effects are not retried");
        }
        target.setItem(to, output.isEmpty() ? extracted : output.copyWithCount(output.getCount() + moved));
        source.setChanged();
        target.setChanged();
        return new TransferResult(moved, TransferStatus.MOVED);
    }

    private static boolean exposes(WorldlyContainer container, int slot, Direction side) {
        for (final int exposed : container.getSlotsForFace(side)) {
            if (exposed == slot) return true;
        }
        return false;
    }

    @Override
    public Counters counters() {
        return this.counts.snapshot();
    }

    /** Called only under a valid local lease or on the proven quiescent server. Does not load chunks. */
    @Nullable
    private static BlockEntity resolve(Ref ref) {
        final ServerLevel level = ref.level.get();
        if (level == null) return null;
        final LevelChunk chunk = level.getChunkSource().getChunkNow(ref.position.getX() >> 4, ref.position.getZ() >> 4);
        return chunk == null || !chunk.isLoaded() ? null : chunk.getBlockEntities().get(ref.position);
    }

    private static final class Ref implements Reference {
        private final Object owner;
        private final WeakReference<ServerLevel> level;
        private final WeakReference<BlockEntity> entity;
        private final ResourceKey<Level> dimension;
        private final BlockPos position;
        private final BlockEntityType<?> entityType;
        private final ResourceLocation type;

        private Ref(Object owner, ServerLevel level, BlockEntity entity) {
            this.owner = owner;
            this.level = new WeakReference<>(level);
            this.entity = new WeakReference<>(entity);
            this.dimension = level.dimension();
            this.position = entity.getBlockPos().immutable();
            this.entityType = entity.getType();
            this.type = Objects.requireNonNull(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(this.entityType));
        }

        @Override
        public ResourceKey<Level> dimension() {
            return this.dimension;
        }

        @Override
        public BlockPos position() {
            return this.position;
        }

        @Override
        public ResourceLocation type() {
            return this.type;
        }
    }

    /** A bounded batch retains only these requests, never a continuation capturing a worker group. */
    public static final class Request<R> implements Task<R> {
        @Nullable
        private Ref source;
        @Nullable
        private Ref target;
        @Nullable
        private Operation<R> operation;
        @Nullable
        private R result;
        @Nullable
        private Throwable failure;
        private State state = State.QUEUED;
        private Reason reason = Reason.NONE;
        private final Counts counts;

        private Request(@Nullable Ref source, @Nullable Ref target, Operation<R> operation, Counts counts) {
            this.source = source;
            this.target = target;
            this.operation = operation;
            this.counts = counts;
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
        public synchronized Optional<R> result() {
            return Optional.ofNullable(this.result);
        }

        @Override
        public synchronized Optional<Throwable> failure() {
            return Optional.ofNullable(this.failure);
        }

        @Override
        public synchronized boolean cancel() {
            return this.retire(State.CANCELLED, Reason.CANCELLED);
        }

        public synchronized boolean retire(State terminal, Reason reason) {
            if (this.state != State.QUEUED) return false;
            this.finish(terminal, reason, null, null);
            return true;
        }

        public void accepted() {
            this.counts.accepted.increment();
        }

        @Nullable
        public synchronized ServerLevel sourceLevel() {
            return this.source == null ? null : this.source.level.get();
        }

        @Nullable
        public synchronized BlockEntity sourceEntity() {
            return this.source == null ? null : this.source.entity.get();
        }

        @Nullable
        public Throwable execute(Supplier<Reason> lifecycle) {
            final Ref first;
            final Ref second;
            final Operation<R> action;
            synchronized (this) {
                if (this.state != State.QUEUED) return null;
                this.state = State.RUNNING;
                first = this.source;
                second = this.target;
                action = this.operation;
                this.clearReferences();
            }
            R result = null;
            Throwable failure = null;
            Reason reason = Reason.NONE;
            try {
                reason = lifecycle.get();
                if (reason == Reason.NONE) {
                    final BlockEntity source = first.entity.get();
                    final BlockEntity target = second.entity.get();
                    if (source == null || target == null || source.isRemoved() || target.isRemoved()) {
                        reason = Reason.BLOCK_ENTITY_REMOVED;
                    } else if (source.getLevel() != first.level.get() || target.getLevel() != second.level.get()
                            || first.level.get() != second.level.get()) {
                                reason = Reason.LEVEL_CHANGED;
                            } else {
                                final ServerLevel level = first.level.get();
                                final LevelChunk sourceChunk = level.getChunkSource().getChunkNow(first.position.getX() >> 4, first.position.getZ() >> 4);
                                final LevelChunk targetChunk = level.getChunkSource().getChunkNow(second.position.getX() >> 4, second.position.getZ() >> 4);
                                reason = lifecycle.get();
                                if (reason == Reason.NONE) {
                                    if (sourceChunk == null || targetChunk == null || !sourceChunk.isLoaded() || !targetChunk.isLoaded()) {
                                        reason = Reason.CHUNK_UNAVAILABLE;
                                    } else if (!valid(first, source, sourceChunk)) {
                                        reason = Reason.SOURCE_REPLACED;
                                    } else if (!valid(second, target, targetChunk)) {
                                        reason = Reason.TARGET_REPLACED;
                                    } else {
                                        final long start = System.nanoTime();
                                        try {
                                            result = action.execute(source, target);
                                        } finally {
                                            this.counts.callbackNanos.add(System.nanoTime() - start);
                                        }
                                    }
                                }
                            }
                }
            } catch (Throwable throwable) {
                failure = throwable;
            }
            synchronized (this) {
                this.finish(failure != null ? State.FAILED : reason == Reason.NONE ? State.COMPLETED : State.SKIPPED, reason, result, failure);
            }
            return failure;
        }

        private static boolean valid(Ref reference, BlockEntity entity, LevelChunk chunk) {
            return reference.position.equals(entity.getBlockPos()) && reference.entityType == entity.getType()
                    && !entity.isRemoved() && entity.getLevel() == reference.level.get()
                    && chunk.getBlockEntities().get(reference.position) == entity;
        }

        private void finish(State state, Reason reason, @Nullable R result, @Nullable Throwable failure) {
            this.clearReferences();
            this.result = result;
            this.failure = failure;
            this.reason = reason;
            this.state = state;
            this.counts.states[state.ordinal()].increment();
            if (reason != Reason.NONE) this.counts.reasons[reason.ordinal()].increment();
        }

        private void clearReferences() {
            this.source = null;
            this.target = null;
            this.operation = null;
        }
    }

    private static final class Counts {
        private final LongAdder accepted = new LongAdder();
        private final LongAdder callbackNanos = new LongAdder();
        private final LongAdder[] states = adders(State.values().length);
        private final LongAdder[] reasons = adders(Reason.values().length);

        private static LongAdder[] adders(int size) {
            final LongAdder[] result = new LongAdder[size];
            for (int i = 0; i < size; ++i) result[i] = new LongAdder();
            return result;
        }

        private Counters snapshot() {
            final EnumMap<Reason, Long> reasons = new EnumMap<>(Reason.class);
            for (final Reason reason : Reason.values()) {
                final long count = this.reasons[reason.ordinal()].sum();
                if (count > 0L) reasons.put(reason, count);
            }
            return new Counters(this.accepted.sum(), this.states[State.COMPLETED.ordinal()].sum(), this.states[State.SKIPPED.ordinal()].sum(),
                    this.states[State.CANCELLED.ordinal()].sum(), this.states[State.REJECTED.ordinal()].sum(), this.states[State.FAILED.ordinal()].sum(),
                    this.callbackNanos.sum(), reasons);
        }
    }

    public static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Block entity operation failed", failure);
    }
}
