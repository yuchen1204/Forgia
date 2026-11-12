/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * Declares who may touch the vanilla entity section/index storage while region workers run.
 * <p>
 * Both persistent server storage (including physical fixed-grid section partitions) and transient
 * storage keep their by-id/by-uuid lookup, section maps and section ID trees in single-threaded
 * collections. Entity ticks move, remove and spawn entities and therefore mutate those collections,
 * so physical spatial partitioning alone does not permit entity ticks on a region worker. The mode
 * therefore decides whether the entity tick slice may leave the server thread at all
 * ({@link RegionThreadingEngine#beginEntityTickPlan}), and it is what makes the guarded storage surface
 * of the level meaningful: with {@link #REGION_OWNED} the guards hand a leased worker its own region's
 * section bucket and the batch barrier, and with {@link #MAIN_THREAD_ONLY} they reject the worker
 * outright.
 */
@ApiStatus.Internal
public enum RegionEntityStorageMode {
    /**
     * The vanilla storage stays single-threaded and is owned by the server thread.
     * <p>
     * Region workers never read or mutate it directly: native workers are rejected from the legacy entity
     * query surface ({@link RegionAwareEntityGetter}), from the public read surface of the persistent
     * section/index manager and from every guarded storage mutation, and region work only runs for tickers
     * whose compatibility declaration proves that they do not touch entity storage. Entity ticks
     * themselves stay on the server thread.
     */
    MAIN_THREAD_ONLY,
    /**
     * The entity tick slice runs on the region workers while the server thread waits for the batch.
     * <p>
     * The physical section partitions of a fixed layout are the region-private storage of a lease: a worker
     * reads only the bucket of the region it holds through {@link RegionScopedEntityQuery}, and every
     * storage effect of its ticks - the position change's section publication and chunk acquisition, the
     * removal's level callback and the admission of a new entity - is recorded and applied by the batch
     * barrier on the server thread. The shared vanilla section map, the by-id/by-uuid lookup and the
     * section id tree therefore stay server-owned; a worker that reaches one of them is rejected and
     * counted, not served. A level only supports this mode when its storage was partitioned with the fixed
     * layout of the engine (see {@link FixedEntitySectionPartitions}), which is why
     * {@link #forConfiguration(boolean, RegionLayout)} refuses it for a dynamic layout.
     */
    REGION_OWNED;

    /**
     * Selects owned entity query partitions for entity ticks or requested natural spawning. Use the requested
     * spawn switch here: an adapter claim may activate spawning after startup conflict planning but before
     * levels are created. Dynamic layouts remain server-only; this does not enable a worker slice or override
     * compatibility policy. Spawn-only servers must not accidentally inherit MAIN_THREAD_ONLY storage.
     */
    public static RegionEntityStorageMode forConfiguration(boolean entityTickWorkers, boolean spawningRequested, RegionLayout layout) {
        return forConfiguration(entityTickWorkers || spawningRequested, layout);
    }

    /** Existing entity-only configuration entry point. Fixed partitions are required for owned storage. */
    public static RegionEntityStorageMode forConfiguration(boolean entityTickWorkers, RegionLayout layout) {
        return entityTickWorkers && layout.isFixed() ? REGION_OWNED : MAIN_THREAD_ONLY;
    }
}
