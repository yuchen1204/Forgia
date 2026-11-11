/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingCompatReport;
import net.neoforged.neoforge.server.threading.RegionThreadingCompatibility;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionEntityQueryCounters;
import net.neoforged.neoforge.server.threading.region.RegionMetricsReport;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickCompatibility;

final class RegionThreadingCommand {
    /** Regions the per-region listing shows when no count is given. */
    static final int DEFAULT_LISTED_REGIONS = 10;
    /** Upper bound of the listing, so one command cannot dump thousands of regions into the chat. */
    static final int MAX_LISTED_REGIONS = 100;

    private RegionThreadingCommand() {}

    static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("regionthreading")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(context -> {
                            final CommandSourceStack source = context.getSource();
                            RegionThreadingHooks.diagnostics().ifPresentOrElse(
                                    diagnostics -> sendStatus(source, diagnostics),
                                    () -> sendNotInstalled(source));
                            return 1;
                        })
                        .then(Commands.literal("raw").executes(context -> {
                            final CommandSourceStack source = context.getSource();
                            RegionThreadingHooks.diagnostics().ifPresentOrElse(
                                    diagnostics -> sendRawDiagnostics(source, diagnostics),
                                    () -> sendNotInstalled(source));
                            return 1;
                        })))
                .then(Commands.literal("compat").executes(context -> sendCompatibility(context.getSource())))
                .then(Commands.literal("regions")
                        .executes(context -> sendRegionListing(context.getSource(), DEFAULT_LISTED_REGIONS))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_LISTED_REGIONS))
                                .executes(context -> sendRegionListing(context.getSource(), IntegerArgumentType.getInteger(context, "count")))));
    }

    /**
     * Sends the compatibility verdict of the running server: the declarations in effect, the detected known
     * conflicts, the action each of them took and the slices that were therefore left off, followed by the
     * limits of the detection.
     */
    private static int sendCompatibility(CommandSourceStack source) {
        RegionThreadingCompatReport.lines().forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        net.neoforged.neoforge.server.threading.api.ParallelApi.forServer(source.getServer()).ifPresent(api -> {
            source.sendSuccess(() -> Component.literal("Parallel API v" + api.apiMajorVersion() + " (experimental): " + api.capabilities()
                    + "; preparation registrations do not override compatibility restrictions"), false);
            for (final var adapter : api.adapters()) {
                source.sendSuccess(() -> Component.literal("  " + adapter.id() + ": ready=" + adapter.preparedUnits()
                        + ", serial=" + adapter.serialUnits() + ", serialPlans=" + adapter.serialPlans()
                        + ", prepareMs=" + millis(adapter.preparationNanos())
                        + adapter.lastSerialReason().map(reason -> ", lastSerialReason=" + reason).orElse("")), false);
            }
        });
        return 1;
    }

    /**
     * Sends the per-region listing of the {@code count} slowest regions by p99.
     */
    private static int sendRegionListing(CommandSourceStack source, int count) {
        RegionThreadingHooks.diagnostics().ifPresentOrElse(
                diagnostics -> RegionMetricsReport.listingLines(diagnostics.regionTicks(), count)
                        .forEach(line -> source.sendSuccess(() -> Component.literal(line), false)),
                () -> source.sendSuccess(() -> Component.literal(RegionMetricsReport.notInstalledLine()), false));
        return 1;
    }

    /**
     * Sends the human-readable status: one coloured line per category, and a zero counter stays out of the line,
     * so a clean run is short and a broken one is red where it broke.
     * <p>
     * This is the default because the raw dump is one line of about 150 fields - readable by a script, not by an
     * operator looking for the one number that moved. {@code status raw} keeps the unchanged machine-readable
     * lines for scripts, logs and bug reports.
     */
    private static void sendStatus(CommandSourceStack source, RegionThreadingDiagnostics diagnostics) {
        final boolean enabled = ForgiaConfig.SERVER.enableRegionThreadingBridge.get();
        statusLine("Region threading", ChatFormatting.GOLD)
                .add(enabled ? "on" : "off", enabled ? ChatFormatting.GREEN : ChatFormatting.RED)
                .add(diagnostics.regionPolicy() + "/" + diagnostics.regionChunkSize() + "ch", ChatFormatting.GRAY)
                .add(diagnostics.workerThreadCount() + " workers", ChatFormatting.GRAY)
                .add(diagnostics.regionCount() + " regions", ChatFormatting.GRAY)
                .add(diagnostics.loadedChunkCount() + " chunks", ChatFormatting.GRAY)
                .add(diagnostics.trackedEntityCount() + " entities", ChatFormatting.GRAY)
                .send(source);

        statusLine("Slices", ChatFormatting.GOLD)
                .chip("entity", diagnostics.entityTickWorkerExecutionEnabled(), diagnostics.entityTickWorkerExecutionEnabled())
                .chip("blockEntity", diagnostics.blockEntityTickWorkerExecutionEnabled(), diagnostics.blockEntityTickWorkerExecutionEnabled())
                .chip("chunk", diagnostics.chunkTickWorkerExecutionEnabled(), diagnostics.chunkTickWorkerDispatchEffective())
                .chip("spawn", diagnostics.spawnWorkerExecutionEnabled(), diagnostics.spawnWorkerDispatchEffective())
                .chip("scheduled", diagnostics.scheduledTickWorkerExecutionEnabled(), diagnostics.scheduledTickWorkerDispatchEffective())
                .send(source);

        statusLine("Entities", ChatFormatting.GOLD)
                .add("workers", diagnostics.entityTicksOnWorkers(), ChatFormatting.GREEN)
                .nonZero("serverThread", diagnostics.serverThreadOnlyEntityTicks(), ChatFormatting.YELLOW)
                .nonZero("truncated", diagnostics.truncatedEntityTicks(), ChatFormatting.YELLOW)
                .nonZero("fallback", diagnostics.fallbackEntityTicks(), ChatFormatting.GRAY)
                .nonZero("skipped", diagnostics.skippedEntityTicks(), ChatFormatting.GRAY)
                .nonZero("mutationsRejected", diagnostics.entityMutationsRejectedByOwnership(), ChatFormatting.RED)
                .send(source);
        if (!diagnostics.serverThreadOnlyEntityTicksByReason().isEmpty()) {
            statusLine("  reasons", ChatFormatting.DARK_GRAY)
                    .add(diagnostics.serverThreadOnlyEntityTicksByReason().toString(),
                            diagnostics.serverThreadOnlyEntityTicksByReason()
                                    .containsKey(RegionThreadingEngine.CHUNK_UNAVAILABLE_ENTITY_REASON)
                                            ? ChatFormatting.YELLOW
                                            : ChatFormatting.GRAY)
                    .send(source);
        }

        statusLine("Chunks", ChatFormatting.GOLD)
                .add("workers", diagnostics.chunkTicksOnWorkers(), ChatFormatting.GREEN)
                .nonZero("boundary", diagnostics.boundaryChunkTicks(), ChatFormatting.GRAY)
                .nonZero("unloadedNeighborhood", diagnostics.unloadedNeighborhoodChunkTicks(), ChatFormatting.GRAY)
                .nonZero("truncated", diagnostics.truncatedChunkTicks(), ChatFormatting.YELLOW)
                .nonZero("late", diagnostics.lateChunkTicks(), ChatFormatting.YELLOW)
                .nonZero("unleased", diagnostics.unleasedChunkTicks(), ChatFormatting.YELLOW)
                .nonZero("fallback", diagnostics.fallbackChunkTicks(), ChatFormatting.GRAY)
                .send(source);

        statusLine("Block entities", ChatFormatting.GOLD)
                .add("workers", diagnostics.blockEntityTicksOnWorkers(), ChatFormatting.GREEN)
                .nonZero("fallback", diagnostics.fallbackBlockEntityTicks(), ChatFormatting.YELLOW)
                .nonZero("noTickers", diagnostics.nonTickingBlockEntityChunks(), ChatFormatting.GRAY)
                .nonZero("staleSnapshots", diagnostics.staleBlockEntityChunkSnapshots(), ChatFormatting.RED)
                .send(source);

        statusLine("Spawn", ChatFormatting.GOLD)
                .add("workers", diagnostics.spawnChunksOnWorkers(), ChatFormatting.GREEN)
                .nonZero("truncated", diagnostics.truncatedSpawnChunks(), ChatFormatting.YELLOW)
                .nonZero("gateRefused", diagnostics.gateRefusedSpawnChunks(), ChatFormatting.GRAY)
                .nonZero("refusedAdditions", diagnostics.refusedSpawnAdditions(), ChatFormatting.GRAY)
                .nonZero("skipped", diagnostics.skippedSpawnChunks(), ChatFormatting.YELLOW)
                .send(source);

        // The one line an operator should read first: anything red here is a correctness problem, not a cost.
        final long problems = diagnostics.tickFailures().total() + diagnostics.ownershipViolationCount()
                + diagnostics.rejectedWorkerTaskCount() + diagnostics.failedQueuedTaskCount()
                + diagnostics.entityAddRejectedOnWorker() + diagnostics.droppedDeferredEntityAdditions();
        statusLine("Health", problems == 0L ? ChatFormatting.GREEN : ChatFormatting.RED)
                .add(problems == 0L ? "clean" : "CHECK", problems == 0L ? ChatFormatting.GREEN : ChatFormatting.RED)
                .nonZero("tickFailures", diagnostics.tickFailures().total(), ChatFormatting.RED)
                .nonZero("ownershipViolations", diagnostics.ownershipViolationCount(), ChatFormatting.RED)
                .nonZero("rejectedTasks", diagnostics.rejectedWorkerTaskCount(), ChatFormatting.RED)
                .nonZero("failedTasks", diagnostics.failedQueuedTaskCount(), ChatFormatting.RED)
                .nonZero("droppedAdditions", diagnostics.droppedDeferredEntityAdditions(), ChatFormatting.RED)
                .nonZero("droppedSectionUpdates", diagnostics.droppedDeferredEntitySectionUpdates(), ChatFormatting.YELLOW)
                .nonZero("droppedRemovals", diagnostics.droppedDeferredEntityRemovals(), ChatFormatting.YELLOW)
                .send(source);

        statusLine("Timing", ChatFormatting.GOLD)
                .add("global " + millis(diagnostics.globalLastTickDurationNanos()), ChatFormatting.GRAY)
                .add("worstRegion " + millis(diagnostics.maxRegionLastTickDurationNanos()),
                        diagnostics.maxRegionLastTickDurationNanos() > 50_000_000L ? ChatFormatting.RED : ChatFormatting.GRAY)
                .add("regionTicks", diagnostics.totalRegionTickCount(), ChatFormatting.GRAY)
                .nonZero("cappedDrains", diagnostics.cappedDrainCount(), ChatFormatting.RED)
                .send(source);

        final long queries = diagnostics.entityQueriesAllowedByLease() + diagnostics.entityQueriesTruncatedByLease()
                + diagnostics.entityQueriesRejected();
        if (queries > 0L) {
            statusLine("Entity queries", ChatFormatting.GOLD)
                    .add("lease", diagnostics.entityQueriesAllowedByLease(), ChatFormatting.GREEN)
                    .nonZero("truncated", diagnostics.entityQueriesTruncatedByLease(), ChatFormatting.YELLOW)
                    .nonZero("refused", diagnostics.entityQueriesRejected(), ChatFormatting.RED)
                    .add("snapshots", diagnostics.playerSnapshotsPublished(), ChatFormatting.GRAY)
                    .send(source);
        }

        final int unsupported = RegionThreadingCompatibility.unsupportedMods().size();
        final int required = RegionThreadingCompatibility.requiredMods().size();
        final int conflicts = RegionThreadingCompatReport.conflictCount();
        statusLine("Compat", ChatFormatting.GOLD)
                .add(RegionThreadingCompatibility.defaultSupport().toString(), ChatFormatting.GRAY)
                .add("unsupported " + unsupported, unsupported > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY)
                .add("required " + required, required > 0 ? ChatFormatting.RED : ChatFormatting.GRAY)
                .add("conflicts " + conflicts, conflicts > 0 ? ChatFormatting.RED : ChatFormatting.GRAY)
                .send(source);

        source.sendSuccess(() -> Component.literal("full dump: /neoforge regionthreading status raw")
                .withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /** {@return nanosecond count as milliseconds with two decimals} */
    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0D);
    }

    private static StatusLine statusLine(String title, ChatFormatting titleColour) {
        return new StatusLine(title, titleColour);
    }

    /**
     * One rendered line of the human-readable status.
     * <p>
     * The title carries the category and every value after it carries the verdict of that number, so the colour
     * says whether the number is a cost, a loss or a correctness problem. {@link #nonZero} keeps a clean counter
     * out of the line entirely: the operator sees what is happening rather than the whole counter space.
     */
    private static final class StatusLine {
        private final MutableComponent text = Component.empty();

        private StatusLine(String title, ChatFormatting titleColour) {
            this.text.append(Component.literal(title).withStyle(titleColour));
        }

        private StatusLine add(String value, ChatFormatting colour) {
            this.text.append(Component.literal("  "));
            this.text.append(Component.literal(value).withStyle(colour));
            return this;
        }

        private StatusLine add(String label, long value, ChatFormatting colour) {
            return this.add(label + " " + value, colour);
        }

        /** Adds {@code label value} only when the number is not zero. */
        private StatusLine nonZero(String label, long value, ChatFormatting colour) {
            return value == 0L ? this : this.add(label + " " + value, colour);
        }

        /**
         * Adds one slice chip. The state word is part of the text as well as the colour, so a log that lost the
         * colours (the server console does) still says whether the slice runs on workers.
         */
        private StatusLine chip(String name, boolean requested, boolean effective) {
            if (!requested) {
                return this.add(name + " off", ChatFormatting.DARK_GRAY);
            }
            return this.add(name + (effective ? " on" : " notEffective"),
                    effective ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        }

        private void send(CommandSourceStack source) {
            final Component line = this.text;
            source.sendSuccess(() -> line, false);
        }
    }

    private static void sendRawDiagnostics(CommandSourceStack source, RegionThreadingDiagnostics diagnostics) {
        source.sendSuccess(() -> Component.literal("Region threading diagnostics: enabled=" + ForgiaConfig.SERVER.enableRegionThreadingBridge.get()
                + ", regions=" + diagnostics.regionCount()
                + ", runningRegions=" + diagnostics.runningRegionCount()
                + ", submittedRegions=" + diagnostics.submittedRegionCount()
                + ", sections=" + diagnostics.sectionCount()
                + ", loadedChunks=" + diagnostics.loadedChunkCount()
                + ", pendingRecalculations=" + diagnostics.pendingRecalculationCount()
                + ", structuralChanges=" + diagnostics.structuralChangeCount()
                + ", splitChecks=" + diagnostics.splitCheckRequestCount()
                + ", mergeChecks=" + diagnostics.mergeCheckRequestCount()
                + ", trackedEntities=" + diagnostics.trackedEntityCount()
                + ", entityTasks=" + diagnostics.trackedEntityTaskCount()
                + ", workerThreads=" + diagnostics.workerThreadCount()
                + ", workerExecution=" + diagnostics.workerExecutionEnabled()
                + ", globalWorkerExecution=" + diagnostics.globalWorkerExecutionEnabled()
                + ", entityTickWorkerExecution=" + diagnostics.entityTickWorkerExecutionEnabled()
                + ", blockEntityTickWorkerExecution=" + diagnostics.blockEntityTickWorkerExecutionEnabled()
                + ", scheduledTickWorkerExecutionRequested=" + diagnostics.scheduledTickWorkerExecutionEnabled()
                + ", scheduledTickWorkerDispatchEffective=" + diagnostics.scheduledTickWorkerDispatchEffective()
                + ", chunkTickWorkerExecutionRequested=" + diagnostics.chunkTickWorkerExecutionEnabled()
                + ", chunkTickWorkerDispatchEffective=" + diagnostics.chunkTickWorkerDispatchEffective()
                + ", spawnWorkerExecutionRequested=" + diagnostics.spawnWorkerExecutionEnabled()
                + ", spawnWorkerDispatchEffective=" + diagnostics.spawnWorkerDispatchEffective()
                + ", borderUpdateEnabled=" + ForgiaConfig.SERVER.enableRegionThreadingBorderUpdates.get()
                + ", borderUpdates=" + diagnostics.borderUpdates()
                + ", gameEvents=" + diagnostics.gameEvents()
                + ", entityTicksOnWorkers=" + diagnostics.entityTicksOnWorkers()
                + ", skippedEntityTicks=" + diagnostics.skippedEntityTicks()
                + ", fallbackEntityTicks=" + diagnostics.fallbackEntityTicks()
                + ", deferredEntityTicks=" + diagnostics.deferredEntityTicks()
                + ", truncatedEntityTicks=" + diagnostics.truncatedEntityTicks()
                + ", entityMutationsAllowedByOwnership=" + diagnostics.entityMutationsAllowedByOwnership()
                + ", entityMutationsRejectedByOwnership=" + diagnostics.entityMutationsRejectedByOwnership()
                + ", entityQueriesAllowedByLease=" + diagnostics.entityQueriesAllowedByLease()
                + ", entityQueriesTruncatedByLease=" + diagnostics.entityQueriesTruncatedByLease()
                + ", entityQueriesRejected=" + diagnostics.entityQueriesRejected()
                + ", deferredEntitySectionUpdatesApplied=" + diagnostics.deferredEntitySectionUpdatesApplied()
                + ", droppedDeferredEntitySectionUpdates=" + diagnostics.droppedDeferredEntitySectionUpdates()
                + ", droppedDeferredEntitySectionUpdatesForRemovedEntity=" + diagnostics.droppedDeferredEntitySectionUpdatesForRemovedEntity()
                + ", deferredEntityRemovalsApplied=" + diagnostics.deferredEntityRemovalsApplied()
                + ", droppedDeferredEntityRemovals=" + diagnostics.droppedDeferredEntityRemovals()
                + ", repeatedDeferredEntityRemovals=" + diagnostics.repeatedDeferredEntityRemovals()
                + ", deferredEntityAdditionsRecorded=" + diagnostics.deferredEntityAdditionsRecorded()
                + ", deferredEntityAdditionsApplied=" + diagnostics.deferredEntityAdditionsApplied()
                + ", droppedDeferredEntityAdditions=" + diagnostics.droppedDeferredEntityAdditions()
                + ", entityAddRejectedOnWorker=" + diagnostics.entityAddRejectedOnWorker()
                + ", chunkGuardRefusals=" + diagnostics.chunkGuardRefusals()
                + ", chunkFallbackEntityTicks=" + diagnostics.chunkFallbackEntityTicks()
                + ", chunkPreloadRefusedEntityTicks=" + diagnostics.chunkPreloadRefusedEntityTicks()
                + ", entityTickChunkMargin=" + RegionThreadingEngine.entityTickChunkMargin()
                + ", blockEntityTicksOnWorkers=" + diagnostics.blockEntityTicksOnWorkers()
                + ", skippedBlockEntityTicks=" + diagnostics.skippedBlockEntityTicks()
                + ", fallbackBlockEntityTicks=" + diagnostics.fallbackBlockEntityTicks()
                + ", nonTickingBlockEntityChunks=" + diagnostics.nonTickingBlockEntityChunks()
                + ", entitiesLoadingBlockEntityChunks=" + diagnostics.entitiesLoadingBlockEntityChunks()
                + ", missingBlockEntityChunks=" + diagnostics.missingBlockEntityChunks()
                + ", staleBlockEntityChunkSnapshots=" + diagnostics.staleBlockEntityChunkSnapshots()
                + ", scheduledTicksOnWorkers=" + diagnostics.scheduledTicksOnWorkers()
                + ", skippedScheduledTicks=" + diagnostics.skippedScheduledTicks()
                + ", fallbackScheduledTicks=" + diagnostics.fallbackScheduledTicks()
                + ", chunkTicksOnWorkers=" + diagnostics.chunkTicksOnWorkers()
                + ", skippedChunkTicks=" + diagnostics.skippedChunkTicks()
                + ", fallbackChunkTicks=" + diagnostics.fallbackChunkTicks()
                + ", boundaryChunkTicks=" + diagnostics.boundaryChunkTicks()
                + ", unloadedNeighborhoodChunkTicks=" + diagnostics.unloadedNeighborhoodChunkTicks()
                + ", truncatedChunkTicks=" + diagnostics.truncatedChunkTicks()
                + ", lateChunkTicks=" + diagnostics.lateChunkTicks()
                + ", unleasedChunkTicks=" + diagnostics.unleasedChunkTicks()
                + ", chunkTickLightningStrikes=" + diagnostics.chunkTickLightningStrikes()
                + ", deferredBlockUpdates=" + diagnostics.deferredBlockUpdates()
                + ", tickFailures=" + diagnostics.tickFailures()
                + ", deferredUpdateBudgetChunkTicks=" + diagnostics.deferredUpdateBudgetChunkTicks()
                + ", nonPartitionedChunkTicks=" + diagnostics.nonPartitionedChunkTicks()
                + ", spawnChunksOnWorkers=" + diagnostics.spawnChunksOnWorkers()
                + ", gateRefusedSpawnChunks=" + diagnostics.gateRefusedSpawnChunks()
                + ", unloadedNeighborhoodSpawnChunks=" + diagnostics.unloadedNeighborhoodSpawnChunks()
                + ", deferredUpdateBudgetSpawnChunks=" + diagnostics.deferredUpdateBudgetSpawnChunks()
                + ", lateSpawnChunks=" + diagnostics.lateSpawnChunks()
                + ", unleasedSpawnChunks=" + diagnostics.unleasedSpawnChunks()
                + ", truncatedSpawnChunks=" + diagnostics.truncatedSpawnChunks()
                + ", skippedSpawnChunks=" + diagnostics.skippedSpawnChunks()
                + ", capRefusedSpawns=" + diagnostics.capRefusedSpawns()
                + ", refusedSpawnAdditions=" + diagnostics.refusedSpawnAdditions()
                + ", globalRunning=" + diagnostics.globalRegionRunning()
                + ", globalSubmitted=" + diagnostics.globalRegionSubmitted()
                + ", rejectedWorkerTasks=" + diagnostics.rejectedWorkerTaskCount()
                + ", failedQueuedTasks=" + diagnostics.failedQueuedTaskCount()
                + ", ownershipViolations=" + ForgiaConfig.SERVER.regionThreadingOwnershipViolationMode.get()
                + ", ownershipViolationCount=" + diagnostics.ownershipViolationCount()
                + ", defaultModSupport=" + RegionThreadingCompatibility.defaultSupport()
                + ", unsupportedMods=" + RegionThreadingCompatibility.unsupportedMods().size()
                + ", requiredMods=" + RegionThreadingCompatibility.requiredMods().size()
                + ", compatDeclarations=" + RegionThreadingCompatReport.declarationCount()
                + ", compatConflicts=" + RegionThreadingCompatReport.conflictCount()
                + ", compatLeftOffSlices=" + RegionThreadingCompatReport.state().plan().leftOffSlices()
                + ", globalTicks=" + diagnostics.globalTickCount()
                + ", globalLastTickNs=" + diagnostics.globalLastTickDurationNanos()
                + ", regionTicks=" + diagnostics.totalRegionTickCount()
                + ", maxRegionLastTickNs=" + diagnostics.maxRegionLastTickDurationNanos()
                + ", cappedDrains=" + diagnostics.cappedDrainCount()
                + ", pendingEntityTransfers=" + diagnostics.pendingEntityTransferCount()
                + ", rememberedEntityTransferResults=" + diagnostics.rememberedEntityTransferResultCount()
                + ", rejectedEntityTransferRecords=" + diagnostics.rejectedEntityTransferRecordCount()
                + ", entityTickRegionsOnWorkers=" + diagnostics.entityTickRegionsOnWorkers()
                + ", fallbackEntityTickRegions=" + diagnostics.fallbackEntityTickRegions()
                + ", entityTickRegionsWithoutEligibleChunk=" + diagnostics.entityTickRegionsWithoutEligibleChunk()
                + ", entityTicksOutsideRegion=" + diagnostics.entityTicksOutsideRegion()
                + ", serverThreadOnlyEntityTicks=" + diagnostics.serverThreadOnlyEntityTicks()
                + ", serverThreadOnlyEntityTicksByReason=" + diagnostics.serverThreadOnlyEntityTicksByReason()
                + ", deferredPortalTransitions=" + diagnostics.deferredPortalTransitions()
                + ", appliedPortalTransitions=" + diagnostics.appliedPortalTransitions()
                + ", refusedPortalTransitions=" + diagnostics.refusedPortalTransitions()
                + ", unindexedEntityTickStarts=" + diagnostics.unindexedEntityTickStarts()
                + ", entityTickIndexFallbacks=" + diagnostics.entityTickIndexFallbacks()
                + ", regionPolicy=" + diagnostics.regionPolicy()
                + ", regionChunkSize=" + diagnostics.regionChunkSize()
                + ", entityStorageMode=" + diagnostics.entityStorageMode()), false);
        // Added, never merged into the line above: the region metrics are a second, independent line so the
        // existing diagnostics line stays stable for scripts that match it.
        source.sendSuccess(() -> Component.literal(RegionMetricsReport.summaryLine(diagnostics.regionMetrics())), false);
        // A third independent line for the regionized POI storage, so the stable diagnostics line and the
        // region metrics line keep their shape for scripts that match them.
        final RegionThreadingDiagnostics.PoiCounters poi = diagnostics.poiCounters();
        source.sendSuccess(() -> Component.literal("Region POI: localQueries=" + poi.localQueries()
                + ", publishedQueries=" + poi.publishedQueries()
                + ", fallbackQueries=" + poi.fallbackQueries()
                + ", staleTakes=" + poi.staleTakes()
                + ", deferredWrites=" + poi.deferredWrites()
                + ", publishes=" + poi.publishes()
                + ", loadedColumns=" + poi.loadedColumns()
                + ", savedColumns=" + poi.savedColumns()
                + ", villageDistanceQueries=" + poi.villageDistanceQueries()), false);
        // A fourth independent line for the worker dispatch path and the packed region leases, so the stable
        // diagnostics line and the two lines above keep their shape for scripts that match them.
        final RegionThreadingDiagnostics.WorkerDispatchCounters dispatch = diagnostics.workerDispatch();
        source.sendSuccess(() -> Component.literal("Region worker dispatch: persistentWorkers=" + dispatch.persistentWorkers()
                + ", packedRegionLeases=" + dispatch.packedRegionLeases()
                + ", taskSubmissions=" + dispatch.submissions()
                + ", submissionNanos=" + dispatch.submitNanos()
                + ", wakeUpNanos=" + dispatch.wakeNanos()
                + ", spinWakeUps=" + dispatch.spinWakeUps()
                + ", parkWakeUps=" + dispatch.parkWakeUps()
                + ", steals=" + dispatch.steals()
                + ", packedTasks=" + dispatch.packedTasks()
                + ", packedRegions=" + dispatch.packedRegions()
                + ", regionsPerPackedTask=" + regionsPerPackedTask(dispatch)
                + ", partialPackFallbacks=" + dispatch.partialPackFallbacks()), false);
        // A fifth independent line for the per-ticker attribution of the block-entity worker slice. The
        // accumulators are only filled while the attribution is enabled, which is off by default; the line keeps
        // its shape either way and reports zeros when nothing was measured.
        source.sendSuccess(() -> Component.literal(blockEntityAttributionLine()), false);
        // A sixth independent line for the player region transitions, so the five lines above keep their shape
        // for scripts that match them.
        source.sendSuccess(() -> Component.literal(blockEntityAdmissionLine()), false);
        source.sendSuccess(() -> Component.literal("Region players: " + diagnostics.playerRegions()), false);
        // A seventh independent line for the per-operation entity query answers of a native region worker, so
        // the six lines above keep their shape for scripts that match them. The line renders the record the
        // diagnostics carry instead of re-reading the counters, so the report and the line cannot disagree.
        final var threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            source.sendSuccess(() -> Component.literal(engine.entityAdmissionDiagnostics().summaryLine()
                    + " workerServerTasksQueued=" + engine.workerServerTasksQueued()
                    + " workerServerTasksApplied=" + engine.workerServerTasksApplied()
                    + " workerServerTasksDropped=" + engine.workerServerTasksDropped()
                    + " handedOffNeighborCascades=" + engine.handedOffNeighborCascades()), false);
            source.sendSuccess(() -> Component.literal("Region entity interactions: " + engine.entityInteractionCounters()), false);
            source.sendSuccess(() -> Component.literal("Region melee attacks: " + engine.meleeAttackCounters()), false);
            source.sendSuccess(() -> Component.literal("Region block entity fallback scope: " + engine.blockEntityFallbackScopeCounters()), false);
        }
        source.sendSuccess(() -> Component.literal(RegionEntityQueryCounters.summaryLine(diagnostics.entityQueryPaths())
                + " playerSnapshots=" + diagnostics.playerSnapshotsPublished()), false);
    }

    /**
     * {@return the status line of the per-ticker attribution of the block-entity worker slice}
     * <p>
     * The ticker columns are microseconds per ticker of the measured window: the admission checks, the
     * reservation of the block-write effects, the entry and exit of the admission scope, the deferred-mutation
     * registration inside the body and the body itself. The group columns are microseconds per group for the
     * work the slice adds around the ticker loop, and {@code wrapperMsPerTick} is what is left of the group wall
     * time after the tickers and those group parts - the dispatch and batch bookkeeping the slice adds to the
     * tick. {@code tickerRegistrations} is the number of block writes a ticker deferred, so a body whose cost is
     * registration rather than vanilla work is visible in the numbers.
     */
    /**
     * {@return the line that names the block-entity types the pack admitted and the types that lose the slice}
     * <p>
     * The block-entity slice only widens by naming types, and the engine cannot guess which type a pack spends
     * its ticks in. This line is the triage list: {@code declared} counts the pack's admissions, and
     * {@code mostFrequentUndeclared} names the types whose tickers are still on the server thread, largest
     * first, so a pack author knows exactly which entry to add to {@code admit_block_entities}.
     */
    private static String blockEntityAdmissionLine() {
        final List<Map.Entry<String, Long>> undeclared = RegionTickCompatibility.undeclaredTypes(10);
        final StringBuilder line = new StringBuilder("Region block entity admission: declaredByPack=")
                .append(RegionTickCompatibility.admittedBlockEntityTypes().size())
                .append(", declaredServerThreadOnly=").append(RegionTickCompatibility.serverThreadOnlyTypes().size())
                .append(", mostFrequentUndeclared=[");
        for (int i = 0; i < undeclared.size(); i++) {
            if (i > 0) {
                line.append(", ");
            }
            line.append(undeclared.get(i).getKey()).append('=').append(undeclared.get(i).getValue());
        }
        return line.append(']').toString();
    }

    private static String blockEntityAttributionLine() {
        final RegionBlockEntityTickAttribution.Snapshot attribution = RegionBlockEntityTickAttribution.snapshot();
        final RegionBlockEntityTickAttribution.TickerAttribution tickers = attribution.tickers();
        final RegionBlockEntityTickAttribution.GroupDistribution groups = attribution.groups();
        final long slices = attribution.sliceCount();
        // The ticker cost is the whole ticker loop of a group: the admission checks and the reservation run before
        // the ticker's own scope, so they are part of it and not part of the wrapper.
        final long wrapperNanos = groups.nanos() - groups.tickerNanos() - tickers.admissionNanos()
                - tickers.reservationNanos() - tickers.ownershipScopeNanos() - tickers.groupPrepareNanos()
                - tickers.groupEndNanos() - groups.retireNanos();
        return "Region block entity attribution: enabled=" + RegionBlockEntityTickAttribution.isEnabled()
                + ", slices=" + slices
                + ", groups=" + groups.groups()
                + ", tickers=" + tickers.tickers()
                + ", tickerAdmissionUs=" + microsPerUnit(tickers.admissionNanos(), tickers.tickers())
                + ", tickerReserveUs=" + microsPerUnit(tickers.reservationNanos(), tickers.tickers())
                + ", tickerScopeUs=" + microsPerUnit(tickers.scopeNanos(), tickers.tickers())
                + ", tickerQueryUs=" + microsPerUnit(tickers.queryNanos(), tickers.tickers())
                + ", tickerQueries=" + perUnit(tickers.queries(), tickers.tickers())
                + ", tickerDeferredRegistrationUs=" + microsPerUnit(tickers.deferredRegistrationNanos(), tickers.tickers())
                + ", tickerRegistrations=" + perUnit(tickers.deferredRegistrations(), tickers.tickers())
                + ", tickerBodyUs=" + microsPerUnit(tickers.bodyNanos(), tickers.tickers())
                + ", tickerTotalUs=" + microsPerUnit(groups.tickerNanos(), tickers.tickers())
                + ", ownershipScopeUsPerGroup=" + microsPerUnit(tickers.ownershipScopeNanos(), groups.groups())
                + ", groupPrepareUsPerGroup=" + microsPerUnit(tickers.groupPrepareNanos(), groups.groups())
                + ", groupEndUsPerGroup=" + microsPerUnit(tickers.groupEndNanos(), groups.groups())
                + ", wrapperMsPerTick=" + millisPerTick(wrapperNanos, slices);
    }

    /** {@return {@code nanos} per {@code units} in microseconds, or {@code 0.000} when nothing was measured} */
    private static String microsPerUnit(long nanos, long units) {
        return units <= 0L ? "0.000" : String.format(Locale.ROOT, "%.3f", nanos / (double) units / 1_000.0);
    }

    /** {@return {@code count} per {@code units}, or {@code 0.00} when nothing was measured} */
    private static String perUnit(long count, long units) {
        return units <= 0L ? "0.00" : String.format(Locale.ROOT, "%.2f", count / (double) units);
    }

    /** {@return {@code nanos} per tick of the measured window in milliseconds} */
    private static String millisPerTick(long nanos, long ticks) {
        return ticks <= 0L ? "0.0000" : String.format(Locale.ROOT, "%.4f", nanos / (double) ticks / 1_000_000.0);
    }

    /** {@return the average number of regions a packed task held, formatted for the diagnostics line} */
    private static String regionsPerPackedTask(RegionThreadingDiagnostics.WorkerDispatchCounters dispatch) {
        if (dispatch.packedTasks() == 0L) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", dispatch.packedRegions() / (double) dispatch.packedTasks());
    }

    private static void sendNotInstalled(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Region threading bridge is not installed: enabled="
                + ForgiaConfig.SERVER.enableRegionThreadingBridge.get()
                + ", workerExecution=" + ForgiaConfig.SERVER.enableRegionThreadingWorkers.get()
                + ", globalWorkerExecution=" + ForgiaConfig.SERVER.enableGlobalRegionThreadingWorker.get()
                + ", entityTickWorkerExecution=" + ForgiaConfig.SERVER.enableRegionThreadingEntityTicks.get()
                + ", blockEntityTickWorkerExecution=" + ForgiaConfig.SERVER.enableRegionThreadingBlockEntityTicks.get()
                + ", scheduledTickWorkerExecutionRequested=" + ForgiaConfig.SERVER.enableRegionThreadingScheduledTicks.get()
                + ", chunkTickWorkerExecutionRequested=" + ForgiaConfig.SERVER.enableRegionThreadingRandomTicks.get()
                + ", spawnWorkerExecutionRequested=" + ForgiaConfig.SERVER.enableRegionThreadingSpawning.get()
                + ", borderUpdateEnabled=" + ForgiaConfig.SERVER.enableRegionThreadingBorderUpdates.get()
                + ", ownershipViolations=" + ForgiaConfig.SERVER.regionThreadingOwnershipViolationMode.get()
                + ", defaultModSupport=" + RegionThreadingCompatibility.defaultSupport()
                + ", unsupportedMods=" + RegionThreadingCompatibility.unsupportedMods().size()
                + ", requiredMods=" + RegionThreadingCompatibility.requiredMods().size()
                + ", compatDeclarations=" + RegionThreadingCompatReport.declarationCount()
                + ", compatConflicts=" + RegionThreadingCompatReport.conflictCount()
                + ", compatLeftOffSlices=" + RegionThreadingCompatReport.state().plan().leftOffSlices()
                + ", configuredWorkerThreads=" + ForgiaConfig.SERVER.regionThreadingWorkerThreads.get()
                + ", configuredRegionPolicy=" + ForgiaConfig.SERVER.regionThreadingMode.get()
                + ", configuredRegionChunkSize=" + ForgiaConfig.SERVER.regionThreadingFixedRegionChunks.get()), false);
        source.sendSuccess(() -> Component.literal(RegionMetricsReport.notInstalledLine()), false);
    }
}
