/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.LongValue;
import net.neoforged.neoforge.common.NeoForgeConfig.RegionThreadingOwnershipViolationMode;
import net.neoforged.neoforge.server.threading.RegionThreadingSupport;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import org.apache.commons.lang3.tuple.Pair;

/** Forgia's server configuration, isolated from the upstream NeoForge configuration. */
public final class ForgiaConfig {
    public static final String FILE_NAME = "forgia.toml";
    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        final Pair<Server, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER_SPEC = pair.getRight();
        SERVER = pair.getLeft();
    }

    private ForgiaConfig() {}

    public static boolean isServerConfigLoaded() {
        return SERVER_SPEC.isLoaded();
    }

    public static final class Server {
        public final BooleanValue enableRegionThreadingBridge;

        public final BooleanValue enableRegionThreadingWorkers;

        public final BooleanValue enableGlobalRegionThreadingWorker;

        public final BooleanValue enableRegionThreadingEntityTicks;

        public final BooleanValue enableRegionThreadingBlockEntityTicks;

        public final BooleanValue enableRegionThreadingFurnaceWorkWaves;

        public final BooleanValue enableRegionThreadingScheduledTicks;

        public final BooleanValue enableRegionThreadingRandomTicks;

        public final BooleanValue enableRegionThreadingSpawning;

        public final BooleanValue enableRegionThreadingPoi;

        public final BooleanValue enableRegionThreadingBorderUpdates;

        /**
         * Experimental: installs the region threading engine even when the known-conflict table would remove a
         * worker slice. Meant for the compatibility harness, which has to observe what a mod actually does
         * instead of trusting a static table; every conflict is still detected and reported.
         */
        public final BooleanValue regionThreadingForceWithKnownConflicts;

        public final BooleanValue enableRegionThreadingGameEvents;

        public final BooleanValue enableRegionThreadingPersistentWorkers;

        public final BooleanValue enableRegionThreadingPackedRegionLeases;

        public final IntValue regionThreadingWorkerThreads;

        public final ModConfigSpec.EnumValue<RegionPolicy> regionThreadingMode;

        public final IntValue regionThreadingFixedRegionChunks;

        public final ModConfigSpec.EnumValue<RegionThreadingOwnershipViolationMode> regionThreadingOwnershipViolationMode;

        public final ModConfigSpec.EnumValue<RegionThreadingSupport> defaultRegionThreadingSupport;

        public final LongValue regionThreadingLongTickWarningNanos;

        public final IntValue regionThreadingMaxImmediateTasksPerDrain;

        public final IntValue regionThreadingMaxScheduledTasksPerDrain;

        Server(ModConfigSpec.Builder builder) {
            enableRegionThreadingBridge = builder
                    .comment("EXPERIMENTAL: Enables the temporary single-thread region threading bridge. This exposes the region scheduler API before the full Folia-style tick scheduler is ported.")
                    .translation("neoforge.configgui.enableRegionThreadingBridge")
                    .worldRestart()
                    .define("enableRegionThreadingBridge", false);

            enableRegionThreadingWorkers = builder
                    .comment("EXPERIMENTAL: Executes due region queue drains on native region worker threads instead of the server thread bridge. Requires enableRegionThreadingBridge. Unsafe until ownership guards are complete.")
                    .translation("neoforge.configgui.enableRegionThreadingWorkers")
                    .worldRestart()
                    .define("enableRegionThreadingWorkers", false);

            enableGlobalRegionThreadingWorker = builder
                    .comment("EXPERIMENTAL: Executes global-region queue drains on the native worker pool instead of the server thread bridge. Requires enableRegionThreadingBridge.")
                    .translation("neoforge.configgui.enableGlobalRegionThreadingWorker")
                    .worldRestart()
                    .define("enableGlobalRegionThreadingWorker", false);

            enableRegionThreadingEntityTicks = builder
                    .comment("EXPERIMENTAL: Ticks the real ServerLevel entities of a tick on their owning region worker thread while the server thread waits for the batch. Requires enableRegionThreadingBridge and a fixed region policy, because the region-owned entity storage this needs is the physical section partition of a fixed layout. Enabling it selects region-owned entity storage: a worker only reads the physical section bucket of the region it holds, every storage effect of its ticks - the position change's section publication and chunk acquisition, the removal's level callback and the admission of a spawned entity - is recorded and applied by the batch barrier on the server thread, and any other storage access from a worker is rejected and counted. With a dynamic layout, and with this switch off, the whole entity slice stays on the server thread.")
                    .translation("neoforge.configgui.enableRegionThreadingEntityTicks")
                    .worldRestart()
                    .define("enableRegionThreadingEntityTicks", false);

            enableRegionThreadingBlockEntityTicks = builder
                    .comment("EXPERIMENTAL: Ticks the real ServerLevel block entities of a tick on their owning region worker thread while the server thread waits for the batch. Requires enableRegionThreadingBridge. Only block entity types whose compatibility declaration proves that their server tick never queries or mutates entity storage, never spawns/removes an entity and writes only inside its region run on workers; every other type stays on the server thread. This is a bounded block-entity tick slice, not regionized world ticking.")
                    .translation("neoforge.configgui.enableRegionThreadingBlockEntityTicks")
                    .worldRestart()
                    .define("enableRegionThreadingBlockEntityTicks", false);

            enableRegionThreadingFurnaceWorkWaves = builder
                    .comment("EXPERIMENTAL, default off: execute verified already-burning vanilla furnace-family ticks in directed resource waves, including independent instances in one chunk/region. Requires the block-entity worker slice and a fixed layout. Fuel/re-light/extinguish, unverified recipes/items/tickers and all other machines keep complete serial ticks in canonical order. Notifications commit on the server after each wave; no general region, entity or chunk mutation permission is granted. This is a narrow vanilla protocol, not arbitrary mod parallelism.")
                    .worldRestart()
                    .define("enableRegionThreadingFurnaceWorkWaves", false);

            enableRegionThreadingScheduledTicks = builder
                    .comment("EXPERIMENTAL: Requests the bounded scheduled block and fluid tick slice. Requires enableRegionThreadingBridge. Production admits only complete block phases of vanilla redstone-lamp callbacks whose radius-two signal/shape footprint is loaded, contained in one fixed region and contains only audited inert vanilla blocks. Other block callbacks, fluids, unsafe footprints and over-budget phases retain the original serial LevelTicks loop and ordering. Diagnostics separate collected/admitted callbacks, native/serial completions, failures, skips, refusal reasons and timing. Default off.")
                    .translation("neoforge.configgui.enableRegionThreadingScheduledTicks")
                    .worldRestart()
                    .define("enableRegionThreadingScheduledTicks", false);

            enableRegionThreadingRandomTicks = builder
                    .comment(
                            "EXPERIMENTAL: Ticks the chunks of a level on their owning region worker thread while the server thread waits for the batch. This is the chunk tick body - random block and fluid ticks, ice and snow, and thunder - not only the random ticks the key is named after. Requires enableRegionThreadingBridge. A chunk runs on a worker when its own chunk and the eight chunks around it are loaded, so a random tick can never force-load a chunk from a worker. A chunk of a region border is dispatched too: a block write of its body that lands in another region is recorded and applied by the region that owns the position on its own worker in the same tick, with the vanilla call, and a callback that reads that position back in the same tick observes the state before the write (counted as readAfterBlockWrite). A write that cannot be expressed as a position, a state and update flags - a block-entity mutation carrying data - keeps the existing no-replay refusal. A chunk whose neighborhood is not loaded stays on the server thread. Natural spawning, the inhabited time update, the custom spawners and every other global decision stay on the server thread. Every refusal is counted and reported by the region threading diagnostics.")
                    .translation("neoforge.configgui.enableRegionThreadingRandomTicks")
                    .worldRestart()
                    .define("enableRegionThreadingRandomTicks", false);

            enableRegionThreadingSpawning = builder
                    .comment(
                            "EXPERIMENTAL: Runs the natural spawning of a level on the worker of the region that owns the chunk, while the server thread waits for the batch. The mob cap is the vanilla level-wide cap: the server thread counts the level's mobs per category once per tick and every region spawns against that count, so a mob that walks into the neighbouring region still holds its slot. A region spawns from a random source derived from the world seed, the dimension, the game time, the region and the chunk, and every mob a region spawns is admitted by the server thread at the barrier. Natural spawning requires enableRegionThreadingBridge and a fixed region policy (the physical entity sections of a level are partitioned per region only for a fixed layout). Only chunks whose eight neighboring chunks are loaded run on a worker; a chunk with an unloaded neighbor, a chunk whose region cannot be leased and a chunk the batch cannot admit stays on the server thread, where it spawns from the vanilla level-wide state exactly like vanilla. The spawn gate, the custom spawners and the per-chunk broadcast stay on the server thread. Every refusal is counted by reason and reported by the region threading diagnostics.")
                    .translation("neoforge.configgui.enableRegionThreadingSpawning")
                    .worldRestart()
                    .define("enableRegionThreadingSpawning", false);

            enableRegionThreadingPoi = builder
                    .comment(
                            "EXPERIMENTAL: Partitions the POI storage of a level per region so that the POI users (villagers, iron golems, raiders, bees, zombies and foxes) tick on their region worker again. A POI section belongs to the region that owns its chunk; each region publishes an immutable snapshot of the sections it wrote at the end of every region phase, and a query from a worker reads its own region's live sections plus the published snapshots of the regions its box overlaps, in vanilla's result order. A foreign answer is at most one publish old, free-ticket counts are live, and the village distance is answered from the published village-center index instead of draining the level-wide distance tracker. Requires enableRegionThreadingBridge and a fixed region policy (regionThreadingMode FIXED_3X3), because the storage maps a chunk to exactly one region coordinate. Enabling enableRegionThreadingEntityTicks implies this storage, because a worker tick of a POI user reaches the level's POI manager. With the switch off, and without entity worker ticks, the vanilla PoiManager storage is used unchanged.")
                    .translation("neoforge.configgui.enableRegionThreadingPoi")
                    .worldRestart()
                    .define("enableRegionThreadingPoi", false);

            regionThreadingForceWithKnownConflicts = builder
                    .comment(
                            "EXPERIMENTAL: installs the region threading engine even when a known conflict would remove a worker slice, so a compatibility run can observe what a mod actually does. Every conflict is still detected and reported. Leave this off outside of measurement runs.")
                    .translation("neoforge.configgui.regionThreadingForceWithKnownConflicts")
                    .worldRestart()
                    .define("regionThreadingForceWithKnownConflicts", false);
            enableRegionThreadingBorderUpdates = builder
                    .comment(
                            "EXPERIMENTAL: Captures the neighbor updates a region worker cannot apply because they target another region, and applies them on the target region's own worker in the same tick. The fixpoint repeats until no border update remains or the level's max-chained-neighbor-updates limit is reached; the rest falls back to the target region's next tick through the region mailbox and is counted as delayed. Requires enableRegionThreadingBridge. With the switch off, a region context uses the unchanged vanilla neighbor collector and no border update is recorded.")
                    .translation("neoforge.configgui.enableRegionThreadingBorderUpdates")
                    .worldRestart()
                    .define("enableRegionThreadingBorderUpdates", false);

            enableRegionThreadingGameEvents = builder
                    .comment(
                            "EXPERIMENTAL: Captures a game event whose listeners belong to another region and delivers it on that region's own worker in the same tick, in posting order, instead of writing the foreign block entity from the posting worker. A game event reaches every listener within its notification radius (16 blocks for the common events, 32 for shriek), and a listener writes its own block entity, so with 3-chunk regions the dispatch crosses a border in the common case. A captured section column is replayed by the target region's worker with the vanilla listener registry, before the next slice of the level tick, so a sculk sensor records the vibration in the same game time and activates on the same tick as inline. Requires enableRegionThreadingBridge. The counters report every posted event, the columns delivered locally, deferred and delivered, and every refusal and drop. With the switch off the dispatch is exactly vanilla and therefore races as before: the posting worker visits every listener of the event's radius itself, including the listeners of other regions, and writes their block entities inline.")
                    .translation("neoforge.configgui.enableRegionThreadingGameEvents")
                    .worldRestart()
                    .define("enableRegionThreadingGameEvents", false);

            enableRegionThreadingPersistentWorkers = builder
                    .comment(
                            "EXPERIMENTAL: Dispatches through persistent workers with per-worker deques. Batches larger than the worker count submit at most one runner per worker; an idle runner steals half of another runner's unstarted task range. Explicit packed leases remain indivisible. This reduces per-task submission overhead while retaining each group's ownership and completion barrier. Requires enableRegionThreadingBridge. With the switch off, tasks use the existing fixed thread pool.")
                    .translation("neoforge.configgui.enableRegionThreadingPersistentWorkers")
                    .worldRestart()
                    .define("enableRegionThreadingPersistentWorkers", false);

            enableRegionThreadingPackedRegionLeases = builder
                    .comment(
                            "EXPERIMENTAL: Lets one worker task of a tick slice hold several regions of that slice, so a tick submits about one task per worker instead of one task per region. A pack leases its regions in one canonical order, all or nothing: a pack that cannot lease every region releases the ones it took and the regions are leased one by one instead, which is counted. Exclusivity is unchanged - each region is still held by exactly one worker for the whole task, with its own lease, group random and ownership lease. Requires enableRegionThreadingBridge. With the switch off, every slice submits one task per region exactly like before.")
                    .translation("neoforge.configgui.enableRegionThreadingPackedRegionLeases")
                    .worldRestart()
                    .define("enableRegionThreadingPackedRegionLeases", false);

            regionThreadingWorkerThreads = builder
                    .comment("EXPERIMENTAL: Number of native region worker threads reserved for the future region scheduler. A value of 0 uses max(1, available processors - 1).")
                    .translation("neoforge.configgui.regionThreadingWorkerThreads")
                    .worldRestart()
                    .defineInRange("regionThreadingWorkerThreads", 0, 0, 512);

            regionThreadingMode = builder
                    .comment("EXPERIMENTAL: Region coordinate policy. FIXED_3X3 uses a non-overlapping global fixed-chunk grid whose edge length is regionThreadingFixedRegionChunks (3 chunks unless configured otherwise); regions never merge, split, or change coordinates. DYNAMIC keeps the legacy 16x16-chunk grid with merge and split recalculation for compatibility.")
                    .translation("neoforge.configgui.regionThreadingMode")
                    .worldRestart()
                    .defineEnum("regionThreadingMode", RegionPolicy.FIXED_3X3);

            regionThreadingFixedRegionChunks = builder
                    .comment("EXPERIMENTAL: Edge length in chunks of one fixed region, used when regionThreadingMode is FIXED_3X3. 3 reproduces the original 3x3-chunk grid, where only the center chunk of a region has all eight neighbors in the same region. Larger regions keep more neighboring chunks inside one region at the cost of coarser parallelism and longer region leases. A value outside 3..32 is clamped into that range when the config loads, and the region layout rejects it with an IllegalArgumentException.")
                    .translation("neoforge.configgui.regionThreadingFixedRegionChunks")
                    .worldRestart()
                    .defineInRange("regionThreadingFixedRegionChunks", RegionLayout.FIXED_3X3_REGION_CHUNK_SIZE, RegionLayout.MIN_FIXED_REGION_CHUNK_SIZE, RegionLayout.MAX_FIXED_REGION_CHUNK_SIZE);

            regionThreadingOwnershipViolationMode = builder
                    .comment("EXPERIMENTAL: Controls how region threading ownership assertion failures are handled.")
                    .translation("neoforge.configgui.regionThreadingOwnershipViolationMode")
                    .defineEnum("regionThreadingOwnershipViolationMode", RegionThreadingOwnershipViolationMode.WARN);

            defaultRegionThreadingSupport = builder
                    .comment("EXPERIMENTAL: Default region threading support status for mods that do not declare explicit metadata yet.")
                    .translation("neoforge.configgui.defaultRegionThreadingSupport")
                    .worldRestart()
                    .defineEnum("defaultRegionThreadingSupport", RegionThreadingSupport.UNKNOWN);

            regionThreadingLongTickWarningNanos = builder
                    .comment("EXPERIMENTAL: Logs a warning when a global or region queue drain exceeds this duration in nanoseconds. 0 disables warnings.")
                    .translation("neoforge.configgui.regionThreadingLongTickWarningNanos")
                    .defineInRange("regionThreadingLongTickWarningNanos", 0L, 0L, Long.MAX_VALUE);

            regionThreadingMaxImmediateTasksPerDrain = builder
                    .comment("EXPERIMENTAL: Maximum immediate region tasks drained from one queue per tick. 0 disables the limit.")
                    .translation("neoforge.configgui.regionThreadingMaxImmediateTasksPerDrain")
                    .defineInRange("regionThreadingMaxImmediateTasksPerDrain", 10000, 0, Integer.MAX_VALUE);

            regionThreadingMaxScheduledTasksPerDrain = builder
                    .comment("EXPERIMENTAL: Maximum due scheduled region tasks drained from one queue per tick. 0 disables the limit.")
                    .translation("neoforge.configgui.regionThreadingMaxScheduledTasksPerDrain")
                    .defineInRange("regionThreadingMaxScheduledTasksPerDrain", 10000, 0, Integer.MAX_VALUE);
        }
    }
}
