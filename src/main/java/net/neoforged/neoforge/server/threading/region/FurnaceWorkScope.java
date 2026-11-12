/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** An instance-only work lease. It never grants a region, entity or shared-chunk mutation permission. */
@ApiStatus.Internal
public final class FurnaceWorkScope implements AutoCloseable {
    private static final BlockEntityTicker<AbstractFurnaceBlockEntity> TICKER = AbstractFurnaceBlockEntity::serverTick;
    private static final ThreadLocal<FurnaceWorkScope> CURRENT = new ThreadLocal<>();
    private final RegionBlockEntityTickAdmission admission;
    private final AbstractFurnaceBlockEntity source;
    private final LevelChunk chunk;
    private final Consumer<AbstractFurnaceBlockEntity> bodyObserver;
    private final Runnable bodyEndObserver;
    private boolean changed;
    private boolean bodyStarted;
    private boolean bodyEnded;
    private boolean taskStarted;
    private boolean closed;

    private FurnaceWorkScope(RegionBlockEntityTickAdmission admission, AbstractFurnaceBlockEntity source,
            LevelChunk chunk, Consumer<AbstractFurnaceBlockEntity> bodyObserver, Runnable bodyEndObserver) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested furnace work scopes are not allowed");
        this.admission = admission;
        this.source = source;
        this.chunk = chunk;
        this.bodyObserver = bodyObserver;
        this.bodyEndObserver = bodyEndObserver;
        CURRENT.set(this);
    }

    public static BlockEntityTicker<AbstractFurnaceBlockEntity> ticker() {
        return TICKER;
    }

    static FurnaceWorkScope open(RegionBlockEntityTickAdmission admission, AbstractFurnaceBlockEntity source,
            LevelChunk chunk, Consumer<AbstractFurnaceBlockEntity> bodyObserver, Runnable bodyEndObserver) {
        return new FurnaceWorkScope(admission, source, chunk, bodyObserver, bodyEndObserver);
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static boolean admitted(ServerLevel level, @Nullable TickingBlockEntity ticker, BlockPos position) {
        final var scope = CURRENT.get();
        return scope != null && !scope.closed && level == scope.admission.level()
                && (ticker == scope.admission.ticker() || ticker == scope.admission.binding().delegate())
                && position.equals(scope.admission.position()) && scope.admission.isCurrent();
    }

    public static boolean runTask(ServerLevel level, BlockPos position, Runnable task) {
        final var scope = CURRENT.get();
        if (scope == null) return false;
        if (level != scope.admission.level() || !position.equals(scope.admission.position()) || scope.taskStarted) {
            throw new IllegalStateException("Furnace work attempted an unrelated or repeated block task");
        }
        scope.taskStarted = true;
        task.run();
        return true;
    }

    public static void beforeBody(Level level, AbstractFurnaceBlockEntity source) {
        final var scope = CURRENT.get();
        if (scope == null) return;
        if (level != scope.admission.level() || source != scope.source || scope.bodyStarted) {
            throw new IllegalStateException("Furnace work attempted an unrelated or repeated original body");
        }
        scope.bodyStarted = true;
        scope.bodyObserver.accept(source);
    }

    /** Success intervals end in the original method, not after its wrapper/diagnostics returned. */
    public static void afterBody(Level level, AbstractFurnaceBlockEntity source) {
        final var scope = CURRENT.get();
        if (scope == null) return;
        if (level != scope.admission.level() || source != scope.source || !scope.bodyStarted || scope.bodyEnded) {
            throw new IllegalStateException("Furnace body completion did not match its entry");
        }
        scope.endBody();
    }

    private void endBody() {
        if (this.bodyStarted && !this.bodyEnded) {
            this.bodyEnded = true;
            this.bodyEndObserver.run();
        }
    }

    public static boolean deferChanged(Level level, BlockPos position, BlockState state) {
        final var scope = CURRENT.get();
        if (scope == null) return false;
        if (level != scope.admission.level() || !position.equals(scope.admission.position()) || state != scope.source.getBlockState()) {
            throw new IllegalStateException("Furnace work attempted a foreign block-entity notification");
        }
        scope.changed = true;
        return true;
    }

    /** Validate before any lookup. No AIR/null substitution and no acquiring another resource mid-body. */
    public static void checkReference(Level level, BlockPos position) {
        final var scope = CURRENT.get();
        if (scope != null && (level != scope.admission.level() || !position.equals(scope.admission.position()))) {
            throw new IllegalStateException("Furnace work attempted a foreign block-entity lookup");
        }
    }

    /** True only for an already-captured read chunk; all chunk mutations stay forbidden. */
    public static boolean checkChunk(ChunkAccess chunk, boolean write) {
        final var scope = CURRENT.get();
        if (scope == null) return false;
        if (write || chunk != scope.chunk) throw new IllegalStateException("Furnace work exceeded its captured read-only chunk");
        return true;
    }

    public static void rejectEffect(String operation) {
        if (CURRENT.get() != null) throw new IllegalStateException("Furnace work cannot perform " + operation);
    }

    boolean bodyStarted() {
        return this.bodyStarted;
    }

    boolean changed() {
        return this.changed;
    }

    @Override
    public void close() {
        if (this.closed || CURRENT.get() != this) throw new IllegalStateException("Furnace work scope closed out of order");
        try {
            this.endBody(); // Failed original bodies have no success tail; close their interval without replay.
        } finally {
            this.closed = true;
            CURRENT.remove();
        }
    }
}
