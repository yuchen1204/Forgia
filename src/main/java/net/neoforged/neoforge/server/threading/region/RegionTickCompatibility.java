/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Declares which vanilla block entities may tick on a region worker under the main-thread-owned entity
 * storage protocol.
 * <p>
 * The list is an allowlist, not a denylist: an unknown or modded block entity type always stays on the
 * server thread. A declaration can only narrow it further - {@link #declareServerThreadOnlyTypes(Map)} removes
 * an entry and can never add one, so a pack that pins a type to the server thread costs throughput and never
 * safety. A type is only allowed on a worker when its server tick
 * <ul>
 * <li>only reaches entity storage through the region-scoped query of the lease and only mutates an entity
 * that lease owns, with every server-owned effect deferred to the batch barrier,</li>
 * <li>never adds an entity, and</li>
 * <li>only mutates its own block/container without writing outside the region lease it holds.</li>
 * </ul>
 * Without border propagation, every entry is interior-only and uses the same one-chunk bound as the random
 * tick slice: the ticker's chunk and all eight neighboring chunks must belong to the same region. With border
 * propagation, only the audited furnace family may also run in a boundary chunk: its body writes its own
 * inventory and block, and its neighbor/comparator effects use the existing border capture. The batch must
 * check that the neighboring chunks are loaded before any body starts. Hoppers and pack-admitted types keep
 * the interior rule because their bodies can read and mutate live neighboring containers.
 * Chained neighbor cascades that travel farther than one chunk, the scheduled tick
 * a neighbor update can start (deferred to the server thread) and arbitrary modded cross-region writes are
 * outside this bound and are not claimed to be safe.
 * Every other known pattern stays on the server thread. In particular, a tick that queries entity
 * storage and then mutates the returned entities (beacon, conduit, bell, shulker box openers,
 * enchanting table, vault and trial spawner are examples of that pattern) cannot become region-safe by
 * shipping the query to the server thread, because the consumer would still mutate another region's
 * entities from a worker. Those types are therefore simply absent from the allowlist.
 * <p>
 * The brewing stand is absent even though its normal brewing path only writes its own block: the patched
 * completion calls {@code Containers.dropItemStack} when the ingredient has a crafting remainder and is
 * not consumed entirely, which spawns an item entity. An interior position does not make entity creation
 * safe, so no declared type is safe at an arbitrary position.
 * <p>
 * The hopper is the one declared type that reaches entity storage, and it is declared because the entity
 * slice gave the block-entity slice the machinery its tick needs. Its item query is a region-scoped query of
 * the leased region, its container reads and writes are one block away - the block above it, the block it
 * faces and the chest half of a double chest, all inside the one-chunk neighborhood the interior rule
 * requires - and the one entity it mutates, the item entity it absorbs, is removed through the ownership
 * lease, so the level callback of that removal runs on the server thread at the barrier. Its
 * {@code level.random} draw (the container entity of a block that has no container block entity) resolves the
 * group random, and the scheduled tick its own {@code setChanged} can start next to a comparator is deferred
 * to the server thread. A modded {@code IItemHandler} or {@code Container} of an adjacent block stays
 * arbitrary modded code and is not claimed to be safe, exactly like every other modded write.
 * <p>
 * The {@code getType()} key of a modded block entity that reuses a vanilla ticker is its own registry
 * key, so a modded subclass never inherits a vanilla entry by accident.
 */
@ApiStatus.Internal
public final class RegionTickCompatibility {
    /**
     * Types whose server tick writes the blocks around its own position only within the same chunk
     * neighborhood, so it may only run when the chunks around its position belong to the same region.
     * <p>
     * The furnace family is audited to only mutate its own container and block with neighbor updates and
     * to never query entity storage or spawn/remove an entity. The hopper is audited to query the item
     * entities of its own region through the region-scoped query, to read and write the containers one block
     * away, and to remove the item entity it absorbs through the ownership lease of its group, which defers
     * the level callback of that removal to the batch barrier.
     */
    private static final Set<String> REGION_SAFE_INTERIOR_BLOCK_ENTITY_TYPES = Set.of(
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:hopper",
            "minecraft:smoker");
    /** Only these audited bodies can replace the interior rule with border capture and loaded neighbors. */
    private static final Set<String> BORDER_SAFE_BLOCK_ENTITY_TYPES = Set.of(
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:smoker");
    /**
     * Types a declaration file, a mod or a pack author pinned to the server thread, by type id with the
     * reason of the declaration.
     * <p>
     * The map only narrows the allowlist above: a declared type is removed from it, and a type that is not on
     * the allowlist cannot be added by a declaration. It is empty until a declaration is applied, and the
     * admission reads it once per ticker of a rebuilt chunk snapshot, so a server without a declaration pays
     * one empty-map test per collected ticker and nothing per tick.
     * <p>
     * The declaration is applied at server start, before a level loads its chunks, because the admission of a
     * chunk's tickers is cached until the chunk's ticker set changes
     * ({@link RegionBlockEntityTickChunkCache}); a declaration that arrives later would only be honoured by
     * chunks that are rebuilt. Nothing in the engine applies one later, and this comment is what keeps a
     * future caller from believing it would.
     */
    private static volatile Map<String, String> serverThreadOnlyTypes = Map.of();

    /**
     * Types a pack author admitted to worker ticks, by type id.
     * <p>
     * The engine can prove the safety of the vanilla types in the allowlist above by reading their code; it
     * cannot do that for a mod it has never seen. A pack author who has a modded machine whose tick only touches
     * its own chunk adds the type here, and the interior-chunk rule still applies. The set only ever widens the
     * allowlist, and a type that is also declared server-thread-only stays on the server thread because the
     * declaration is the stronger statement.
     */
    private static volatile Set<String> admittedTypes = Set.of();

    /**
     * How often a type was undeclared when an admission snapshot was built. Cached snapshots reuse the
     * decision, so this is not a count of fallback ticks or time spent. Use it to identify encountered types;
     * use dispatch deltas and profiling to identify hot tickers. Bounded to avoid unbounded modded type growth.
     */
    private static final int MAX_TRACKED_UNDECLARED_TYPES = 128;

    private static final Map<String, java.util.concurrent.atomic.LongAdder> UNDECLARED_TICK_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();

    private RegionTickCompatibility() {}

    /**
     * Replaces the declared server-thread-only block-entity types.
     * <p>
     * This is the hook the declaration files of {@code RegionThreadingDeclarations} call; the programmatic API
     * stays the allowlist itself, which a mod can only narrow through this call. Applying an empty map clears
     * the declarations of an earlier server start.
     *
     * @param declarations the declared type ids with their reason, keyed by the type id the ticker reports
     */
    public static void declareServerThreadOnlyTypes(Map<String, String> declarations) {
        serverThreadOnlyTypes = Map.copyOf(declarations);
    }

    /**
     * Replaces the block-entity types a pack admitted to worker ticks.
     * <p>
     * This is the widening half of the declaration files: the types named here may tick on a region worker in an
     * interior chunk of their region, exactly like the built-in vanilla types. Applying an empty set clears the
     * declarations of an earlier server start.
     *
     * @param types the admitted type ids, as the ticker reports them
     */
    public static void declareAdmittedBlockEntityTypes(Set<String> types) {
        admittedTypes = Set.copyOf(types);
    }

    /** {@return the block-entity types a pack admitted to worker ticks} */
    public static Set<String> admittedBlockEntityTypes() {
        return admittedTypes;
    }

    /** {@return the declared server-thread-only block-entity types with their reason} */
    public static Map<String, String> serverThreadOnlyTypes() {
        return serverThreadOnlyTypes;
    }

    /**
     * {@return true when a block entity of {@code type} may tick on a region worker}
     * <p>
     * A type that requires an interior position still has to pass
     * {@link #requiresInteriorRegion(String)} at its actual position.
     */
    public static boolean isRegionSafeBlockEntityType(@Nullable String type) {
        return requiresInteriorRegion(type);
    }

    /**
     * {@return true when {@code type} is admitted under the conservative interior-chunk protocol}
     * <p>
     * The furnace family can additionally use boundary admission through
     * {@link #decideBlockEntityTick(String, boolean, boolean)} when border propagation is enabled. A declared
     * server-thread-only type is never admitted, whatever its position or the available propagation protocol.
     */
    public static boolean requiresInteriorRegion(@Nullable String type) {
        return type != null
                && (REGION_SAFE_INTERIOR_BLOCK_ENTITY_TYPES.contains(type) || admittedTypes.contains(type))
                && !serverThreadOnlyTypes.containsKey(type);
    }

    /**
     * {@return the admission decision without border propagation}
     */
    public static RegionBlockEntityTickAdmission.Decision decideBlockEntityTick(@Nullable String type, boolean interiorChunk) {
        return decideBlockEntityTick(type, interiorChunk, false);
    }

    /**
     * {@return the pre-body admission decision for the available propagation protocol}
     * <p>
     * Boundary admission is restricted to audited furnace-family bodies, even when a pack admits another
     * type. Loaded-neighborhood availability is deliberately not part of this cached decision: the batch
     * rechecks it each tick before it leases the chunk. Server-thread declarations always take precedence.
     */
    public static RegionBlockEntityTickAdmission.Decision decideBlockEntityTick(@Nullable String type, boolean interiorChunk, boolean borderUpdatesEnabled) {
        if (!requiresInteriorRegion(type)) {
            recordUndeclaredType(type);
            return RegionBlockEntityTickAdmission.Decision.TYPE_NOT_DECLARED;
        }
        if (interiorChunk) {
            return RegionBlockEntityTickAdmission.Decision.ADMITTED;
        }
        return borderUpdatesEnabled && BORDER_SAFE_BLOCK_ENTITY_TYPES.contains(type)
                ? RegionBlockEntityTickAdmission.Decision.ADMITTED_WITH_BORDER_UPDATES
                : RegionBlockEntityTickAdmission.Decision.NOT_INTERIOR_CHUNK;
    }

    /** Adapter candidacy is cached; its instance predicate is still mandatory before every native body. */
    public static RegionBlockEntityTickAdmission.Decision decideBlockEntityTick(ServerLevel level, @Nullable String type,
            boolean interiorChunk, boolean borderUpdatesEnabled) {
        if (type != null && !serverThreadOnlyTypes.containsKey(type) && ParallelAdapterRuntime.hasBlockEntityTickAdapter(level, type)) {
            return interiorChunk ? RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED
                    : RegionBlockEntityTickAdmission.Decision.NOT_INTERIOR_CHUNK;
        }
        return decideBlockEntityTick(type, interiorChunk, borderUpdatesEnabled);
    }

    /** Records one ticker of {@code type} that no declaration admits. */
    private static void recordUndeclaredType(@Nullable String type) {
        if (type == null) {
            return;
        }
        java.util.concurrent.atomic.LongAdder counter = UNDECLARED_TICK_COUNTS.get(type);
        if (counter == null) {
            if (UNDECLARED_TICK_COUNTS.size() >= MAX_TRACKED_UNDECLARED_TYPES) {
                return;
            }
            counter = UNDECLARED_TICK_COUNTS.computeIfAbsent(type, ignored -> new java.util.concurrent.atomic.LongAdder());
        }
        counter.increment();
    }

    /**
     * {@return the {@code limit} most frequent block-entity types that no declaration admits, largest first}
     * <p>
     * This is the triage list for a pack: the types at the top are where the block-entity slice loses its ticks,
     * and each one is a candidate for {@code admit_block_entities} in
     * {@code config/regionthreading-declarations.json}.
     */
    public static List<Map.Entry<String, Long>> undeclaredTypes(int limit) {
        final List<Map.Entry<String, Long>> entries = new ArrayList<>(UNDECLARED_TICK_COUNTS.size());
        UNDECLARED_TICK_COUNTS.forEach((type, counter) -> entries.add(Map.entry(type, counter.sum())));
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return entries.size() <= limit ? entries : entries.subList(0, limit);
    }
}
