/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAdmission;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.registration.RegistrationHelper;

/**
 * Regression coverage for region threading slices on a live level.
 * <p>
 * The block-entity cases exercise the actual canonical Level path: an unknown modded source is a
 * complete server-thread Fence, closed original Hopper branches remain Native prefixes, and at least
 * two independent already-burning furnaces are required for a fine worker wave. Observers only record
 * bodies after production admission and always forward the complete original ticker. They never issue
 * admission or change a body. An unsafe beacon remains serial without poisoning unrelated prefixes.
 * The historical method name {@code unsafeTickerForcesWholeRegionFallback} is retained for report
 * continuity; whole-region poisoning is deliberately no longer the execution contract.
 * <p>
 * These tests require the experimental region threading bridge and worker slices. The documented run
 * enables {@code enableRegionThreadingBridge}, {@code enableRegionThreadingWorkers},
 * {@code enableRegionThreadingEntityTicks}, {@code enableRegionThreadingBlockEntityTicks},
 * {@code enableRegionThreadingScheduledTicks} and {@code enableRegionThreadingRandomTicks}. When the
 * bridge is absent the tests assert that the bridge and the exercised slices are configured off instead
 * of passing silently; a default-off GameTestServer therefore checks the documented default state.
 */
@ForEachTest(groups = "level.region")
public class RegionThreadingGameTests {
    /**
     * Distance from the GameTest structure grid to the isolated evidence area. The grid is at most a few
     * hundred blocks wide and tall, so this keeps every evidence chunk away from other test structures.
     */
    private static final int EVIDENCE_AREA_Z_OFFSET = 1024;
    /** Distance between the evidence areas of the individual tests, larger than a dynamic 16x16 region. */
    private static final int EVIDENCE_AREA_SPACING = 512;

    @GameTest(batch = "regionChunkOwnerOperation", timeoutTicks = 200)
    @EmptyTemplate
    @TestHolder(description = "Loads a real distant chunk for one owner operation and releases nested retention tickets")
    static void chunkBackedOwnerOperation(final DynamicTest test) {
        test.onGameTest(helper -> {
            final ServerLevel level = helper.getLevel();
            // Isolated scheduler state: do not replace the server's installed bridge or its tick configuration.
            final var engine = new net.neoforged.neoforge.server.threading.region.RegionThreadingEngine(1, false, false);
            final ItemEntity owner = new ItemEntity(level, 0, 100, 0, new ItemStack(Items.STONE));
            final BlockPos destination = new BlockPos(640000, 100, -640000);
            final ChunkPos chunkPos = new ChunkPos(destination);
            final AtomicInteger callbacks = new AtomicInteger();
            try {
                helper.assertTrue(level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z) == null,
                        "the evidence chunk must start unloaded in the fresh test world");
                final var outer = engine.entityInteractions().submitToOwnerAfterChunkLoad(owner, owner, destination, (currentOwner, loaded) -> {
                    helper.assertTrue(level.getServer().isSameThread(), "real chunk completion must execute on the server thread");
                    helper.assertTrue(loaded.getPos().equals(chunkPos), "the requested chunk must be loaded before the callback");
                    helper.assertTrue(ownerOperationTickets(level, chunkPos) == 1, "outer operation must retain exactly one ticket");
                    final var inner = engine.entityInteractions().submitToOwnerAfterChunkLoad(owner, owner, destination, (nestedOwner, sameChunk) -> {
                        helper.assertTrue(sameChunk == loaded && nestedOwner == owner, "nested loading must preserve world and owner identity");
                        helper.assertTrue(ownerOperationTickets(level, chunkPos) == 2, "nested operations must use independent ticket keys");
                        callbacks.incrementAndGet();
                    });
                    helper.assertTrue(inner.state() == net.neoforged.neoforge.server.threading.EntityInteractionTask.State.COMPLETED,
                            "nested operation must complete exactly once");
                    helper.assertTrue(ownerOperationTickets(level, chunkPos) == 1, "inner cleanup must leave the outer ticket alive");
                    callbacks.incrementAndGet();
                });
                helper.assertTrue(outer.state() == net.neoforged.neoforge.server.threading.EntityInteractionTask.State.COMPLETED,
                        "real chunk owner operation must complete");
                helper.assertTrue(callbacks.get() == 2 && ownerOperationTickets(level, chunkPos) == 0,
                        "both callbacks must run once and all operation tickets must be released");
            } finally {
                engine.shutdown();
                owner.discard();
            }
            helper.succeed();
        });
    }

    private static long ownerOperationTickets(ServerLevel level, ChunkPos chunk) {
        try {
            final Field managerField = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("distanceManager");
            managerField.setAccessible(true);
            final Object manager = managerField.get(level.getChunkSource());
            final Field ticketsField = net.minecraft.server.level.DistanceManager.class.getDeclaredField("tickets");
            ticketsField.setAccessible(true);
            final var tickets = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>) ticketsField.get(manager);
            final Object set = tickets.get(chunk.toLong());
            if (set == null) return 0L;
            long result = 0;
            for (Object value : (Iterable<?>) set) {
                if (((net.minecraft.server.level.Ticket<?>) value).getType().toString().equals("forgia_owner_operation")) result++;
            }
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect owner operation ticket lifecycle", failure);
        }
    }

    @GameTest(batch = "regionPhysicalEntityStorage", timeoutTicks = 200)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Moves a tracked item across a fixed region boundary in the real persistent section storage")
    static void physicalEntitySectionCrossing(final DynamicTest test) {
        test.onGameTest(helper -> {
            final var diagnostics = workerDiagnostics(helper);
            final ServerLevel level = helper.getLevel();
            final EntitySectionStorage<Entity> storage = persistentSections(level);
            final boolean fixed = diagnostics.isPresent() && diagnostics.get().regionPolicy() == RegionPolicy.FIXED_3X3;
            helper.assertTrue((storageField(storage, "partitions") != null) == fixed, "server storage backend must match the installed policy");
            if (!fixed) {
                helper.succeed();
                return;
            }
            helper.assertTrue(storageField(storage, "sections") == null && storageField(storage, "sectionIds") == null,
                    "fixed server storage must not retain global authoritative containers");
            final int regionChunkSize = diagnostics.get().regionChunkSize();
            final BlockPos interior = evidenceBlockPos(helper, RegionPolicy.FIXED_3X3, regionChunkSize, 5);
            final int boundaryChunkX = (Math.floorDiv(SectionPos.blockToSectionCoord(interior.getX()), regionChunkSize) + 1)
                    * regionChunkSize;
            final int boundaryX = boundaryChunkX * 16;
            final BlockPos source = new BlockPos(boundaryX - 1, interior.getY(), interior.getZ());
            final BlockPos target = source.offset(2, 0, 0);
            final AtomicReference<ItemEntity> item = new AtomicReference<>();
            helper.addEndListener(passed -> {
                if (item.get() != null) item.get().discard();
                releaseForcedChunk(level, source);
                releaseForcedChunk(level, target);
            });
            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, source);
                        forceChunk(level, target);
                    })
                    .thenWaitUntil(() -> helper.assertTrue(level.areEntitiesLoaded(new ChunkPos(source).toLong())
                            && level.areEntitiesLoaded(new ChunkPos(target).toLong()), "both entity chunks must be loaded"))
                    .thenExecute(() -> {
                        ItemEntity entity = new ItemEntity(level, source.getX() + 0.5, source.getY(), source.getZ() + 0.5, new ItemStack(Items.STONE));
                        entity.setNoGravity(true);
                        entity.setDeltaMovement(0, 0, 0);
                        item.set(entity);
                        helper.assertTrue(level.addFreshEntity(entity), "item must be registered with the persistent manager");
                        helper.assertTrue(storage.getSection(SectionPos.asLong(source)).getEntities().filter(e -> e == entity).count() == 1,
                                "source section must own the item exactly once");
                        entity.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                        var oldSection = storage.getSection(SectionPos.asLong(source));
                        helper.assertTrue(oldSection == null || oldSection.getEntities().noneMatch(e -> e == entity), "source must release the moved item");
                        helper.assertTrue(storage.getSection(SectionPos.asLong(target)).getEntities().filter(e -> e == entity).count() == 1,
                                "destination partition must own the item exactly once");
                        helper.assertTrue(level.getEntity(entity.getUUID()) == entity, "tracking lookup must preserve identity");
                        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(source).minmax(new AABB(target)).inflate(2), e -> e == entity).size() == 1,
                                "query across the partition boundary must return the item once");
                    })
                    .thenSucceed();
        });
    }

    @SuppressWarnings("unchecked")
    private static EntitySectionStorage<Entity> persistentSections(ServerLevel level) {
        return (EntitySectionStorage<Entity>) storageField(storageField(level, "entityManager"), "sectionStorage");
    }

    private static Object storageField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @GameTest(batch = "regionBeaconFallback", timeoutTicks = 400)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Ticks a beacon with a base in an isolated region; the beacon is unsafe and must stay on the whole-region fallback path")
    static void beaconEntityQuery(final DynamicTest test) {
        test.onGameTest(helper -> {
            final Optional<RegionThreadingDiagnostics> diagnostics = workerDiagnostics(helper);
            if (diagnostics.isEmpty() || !diagnostics.get().blockEntityTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, false, "block entity worker slice");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final BlockPos beaconPos = evidenceBlockPos(helper, diagnostics.get().regionPolicy(), diagnostics.get().regionChunkSize(), 3);
            // The beacon queries ServerPlayer entities while it applies effects, so keep a player alive.
            helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
            final long fallbackBefore = diagnostics.get().fallbackBlockEntityTicks();
            final long workersBefore = diagnostics.get().blockEntityTicksOnWorkers();
            helper.addEndListener(passed -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setBlock(beaconPos.offset(dx, -1, dz), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                level.setBlock(beaconPos, Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, beaconPos);
            });

            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, beaconPos);
                        clearEvidenceChunkBlockEntities(level, beaconPos);
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                level.setBlock(beaconPos.offset(dx, -1, dz), Blocks.IRON_BLOCK.defaultBlockState(), 3);
                            }
                        }
                        level.setBlock(beaconPos, Blocks.BEACON.defaultBlockState(), 3);
                    })
                    // The beacon only re-evaluates its base and applies effects every 80 game ticks.
                    .thenIdle(100)
                    .thenExecute(() -> {
                        helper.assertTrue(
                                level.getBlockEntity(beaconPos) instanceof BeaconBlockEntity,
                                "beacon block entity was not ticked or was removed");
                        final RegionThreadingDiagnostics after = diagnostics();
                        helper.assertTrue(
                                after.fallbackBlockEntityTicks() > fallbackBefore,
                                "the unsafe beacon was never classified as a server-thread fallback");
                        helper.assertTrue(
                                after.blockEntityTicksOnWorkers() == workersBefore,
                                "the unsafe beacon advanced the block entity worker counter");
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionSingleFurnaceSerial", timeoutTicks = 1200)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "A singleton already-burning original furnace stays complete serial and still smelts exactly once")
    static void singleFurnaceRetainsCompleteSerialTick(final DynamicTest test) {
        test.onGameTest(helper -> {
            final var available = workerDiagnostics(helper);
            if (available.isEmpty() || !available.get().blockEntityTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, false, "block entity worker slice");
                helper.succeed();
                return;
            }
            final var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            final ServerLevel level = helper.getLevel();
            final BlockPos pos = evidenceBlockPos(helper, engine.regionPolicy(), engine.regionLayout().regionChunkSize(), 10);
            final var evidence = new CanonicalBodyEvidence(level, engine, java.util.Set.of(pos));
            helper.addEndListener(passed -> {
                evidence.close();
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, pos);
            });
            helper.startSequence()
                    .thenExecute(() -> forceChunk(level, pos))
                    .thenWaitUntil(() -> helper.assertTrue(level.areEntitiesLoaded(ChunkPos.asLong(pos)), "singleton furnace chunk has not loaded"))
                    .thenExecute(() -> {
                        clearEvidenceChunkBlockEntities(level, pos);
                        placeBurningFurnace(level, pos);
                    })
                    .thenIdle(220)
                    .thenExecute(() -> {
                        evidence.assertSerial(helper, pos);
                        evidence.assertCompleteTicks(helper, java.util.Set.of(pos));
                        assertSmeltedOnce(helper, level, pos, evidence.bodyCount(pos));
                        System.out.println("C42_SINGLE_FURNACE_SERIAL " + evidence.describe());
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionFurnaceWorker", timeoutTicks = 1200)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Two independent already-burning original furnaces execute complete fine Worker bodies and smelt exactly once")
    static void furnaceTicksOnRegionWorker(final DynamicTest test) {
        test.onGameTest(helper -> {
            final var available = workerDiagnostics(helper);
            if (available.isEmpty() || !available.get().blockEntityTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, false, "block entity worker slice");
                helper.succeed();
                return;
            }
            final var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            helper.assertTrue(engine.furnaceWorkWavesEnabled() && engine.regionLayout().isFixed(),
                    "the canonical furnace test requires the opt-in Furnace Waves and fixed layout profile");
            final ServerLevel level = helper.getLevel();
            final BlockPos first = evidenceBlockPos(helper, engine.regionPolicy(), engine.regionLayout().regionChunkSize(), 0);
            final BlockPos second = first.offset(engine.regionLayout().regionChunkSize() * 16, 0, 0);
            final var positions = java.util.List.of(first, second);
            final var evidence = new CanonicalBodyEvidence(level, engine, java.util.Set.copyOf(positions));
            final long changesBefore = engine.furnaceWorkMetrics().committedChanges();
            helper.addEndListener(passed -> {
                evidence.close();
                positions.forEach(pos -> {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    releaseForcedChunk(level, pos);
                });
            });
            helper.startSequence()
                    .thenExecute(() -> positions.forEach(pos -> forceChunk(level, pos)))
                    .thenWaitUntil(() -> helper.assertTrue(positions.stream().allMatch(pos -> level.areEntitiesLoaded(ChunkPos.asLong(pos))),
                            "both real furnace chunks must finish their asynchronous entity load"))
                    .thenExecute(() -> positions.forEach(pos -> {
                        clearEvidenceChunkBlockEntities(level, pos);
                        placeBurningFurnace(level, pos);
                    }))
                    .thenIdle(220)
                    .thenExecute(() -> {
                        positions.forEach(pos -> {
                            assertSmeltedOnce(helper, level, pos, evidence.bodyCount(pos));
                            evidence.assertWorker(helper, pos, true);
                        });
                        evidence.assertCompleteTicks(helper, java.util.Set.copyOf(positions));
                        helper.assertTrue(engine.furnaceWorkMetrics().committedChanges() >= changesBefore + 2,
                                "both original smelts must commit their deferred notifications");
                        helper.assertTrue(engine.furnaceWorkMetrics().failed() == 0, "fine worker failure must not be hidden by serial replay");
                        System.out.println("C42_TWO_FURNACES " + evidence.describe() + " metrics=" + engine.furnaceWorkMetrics());
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionBoundaryFurnaceWorker", timeoutTicks = 600)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "A boundary furnace smelts once on a real worker and notifies a comparator across the region border")
    static void boundaryFurnaceTicksAndNotifiesComparator(final DynamicTest test) {
        test.onGameTest(helper -> {
            final var available = workerDiagnostics(helper);
            if (available.isEmpty() || !available.get().blockEntityTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, false, "boundary furnace worker slice");
                helper.succeed();
                return;
            }
            final RegionThreadingEngine engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            if (!engine.borderUpdatesEnabled() || !engine.regionLayout().isFixed()) {
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final int size = engine.regionLayout().regionChunkSize();
            final BlockPos interior = evidenceBlockPos(helper, RegionPolicy.FIXED_3X3, size, 6);
            final int boundaryX = (Math.floorDiv(interior.getX() >> 4, size) + 1) * size * 16 - 1;
            final BlockPos furnacePos = new BlockPos(boundaryX, interior.getY(), interior.getZ());
            final BlockPos comparatorPos = furnacePos.east();
            final java.util.List<BlockPos> forced = new java.util.ArrayList<>();
            final AtomicReference<Long> workersBefore = new AtomicReference<>();
            helper.addEndListener(passed -> {
                level.setBlock(furnacePos, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(comparatorPos, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(comparatorPos.below(), Blocks.AIR.defaultBlockState(), 3);
                forced.forEach(pos -> releaseForcedChunk(level, pos));
            });
            helper.startSequence()
                    .thenExecute(() -> {
                        final ChunkPos center = new ChunkPos(furnacePos);
                        for (int dx = -1; dx <= 1; ++dx) {
                            for (int dz = -1; dz <= 1; ++dz) {
                                final BlockPos position = new BlockPos((center.x + dx) * 16, furnacePos.getY(), (center.z + dz) * 16);
                                forced.add(position);
                                forceChunk(level, position);
                                level.getChunkAt(position);
                            }
                        }
                        clearEvidenceChunkBlockEntities(level, furnacePos);
                        clearEvidenceChunkBlockEntities(level, comparatorPos);
                        level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
                        level.setBlock(comparatorPos.below(), Blocks.STONE.defaultBlockState(), 3);
                        level.setBlock(comparatorPos, Blocks.COMPARATOR.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), 3);
                    })
                    .thenWaitUntil(() -> helper.assertTrue(level.areEntitiesLoaded(ChunkPos.asLong(furnacePos)), "furnace entities have not loaded"))
                    .thenIdle(5)
                    .thenExecute(() -> {
                        final AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(furnacePos);
                        helper.assertTrue(furnace != null, "the real furnace is missing");
                        workersBefore.set(engine.boundaryBlockEntityTicksOnWorkers());
                        furnace.setItem(0, new ItemStack(Items.IRON_ORE));
                        furnace.setItem(1, new ItemStack(Items.COAL));
                    })
                    .thenIdle(220)
                    .thenExecute(() -> {
                        final AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(furnacePos);
                        helper.assertTrue(furnace != null && furnace.getItem(0).isEmpty() && furnace.getItem(1).isEmpty()
                                && furnace.getItem(2).is(Items.IRON_INGOT) && furnace.getItem(2).getCount() == 1,
                                "boundary smelting must consume one ore/fuel and produce one ingot");
                        final var snapshot = level.getChunkAt(furnacePos).blockEntityTickAdmissionCache();
                        helper.assertTrue(snapshot != null && snapshot.admissions().stream().anyMatch(admission -> admission.position().equals(furnacePos)
                                && admission.decision() == RegionBlockEntityTickAdmission.Decision.ADMITTED_WITH_BORDER_UPDATES),
                                "the real chunk must capture boundary admission for this exact furnace");
                        helper.assertTrue(engine.boundaryBlockEntityTicksOnWorkers() > workersBefore.get(), "no boundary ticker completed on a native worker");
                        helper.assertTrue(level.getBlockEntity(comparatorPos) instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity comparator
                                && comparator.getOutputSignal() > 0, "the foreign comparator did not observe the furnace inventory");
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionStoredCallback", timeoutTicks = 400)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "An installed entity-storage callback remains deferred when the added lifecycle marker is cleared during a native tick")
    static void storedCallbackWithClearedMarker(final DynamicTest test) {
        test.onGameTest(helper -> {
            final ServerLevel level = helper.getLevel();
            final Optional<RegionThreadingDiagnostics> diagnostics = RegionThreadingHooks.diagnostics();
            final boolean nativeTicks = entityTickSliceEffective(diagnostics);
            final BlockPos source = evidenceBlockPos(helper,
                    diagnostics.map(RegionThreadingDiagnostics::regionPolicy).orElse(RegionPolicy.FIXED_3X3),
                    diagnostics.map(RegionThreadingDiagnostics::regionChunkSize).orElse(3), 9);
            final BlockPos target = source.offset(16, 0, 0);
            final AtomicReference<net.minecraft.world.entity.animal.Cow> cow = new AtomicReference<>();
            final AtomicReference<Boolean> worker = new AtomicReference<>();
            final AtomicInteger calls = new AtomicInteger();
            final java.util.function.Consumer<net.neoforged.neoforge.event.tick.EntityTickEvent.Pre> listener = event -> {
                if (event.getEntity() != cow.get() || calls.getAndIncrement() != 0) return;
                final Entity entity = event.getEntity();
                worker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
                entity.onRemovedFromLevel();
                entity.setPosRaw(target.getX() + .5D, target.getY(), target.getZ() + .5D);
                event.setCanceled(true);
            };
            helper.addEndListener(passed -> {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
                if (cow.get() != null) cow.get().discard();
                releaseForcedChunk(level, source);
                releaseForcedChunk(level, target);
            });
            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, source);
                        forceChunk(level, target);
                        level.getChunkAt(source);
                        level.getChunkAt(target);
                    })
                    .thenWaitUntil(() -> helper.assertTrue(level.areEntitiesLoaded(ChunkPos.asLong(source))
                            && level.areEntitiesLoaded(ChunkPos.asLong(target)), "callback fixture chunks have not loaded"))
                    .thenExecute(() -> {
                        final var entity = new net.minecraft.world.entity.animal.Cow(EntityType.COW, level);
                        entity.setNoAi(true);
                        entity.setNoGravity(true);
                        entity.setPersistenceRequired();
                        entity.moveTo(source.getX() + .5D, source.getY(), source.getZ() + .5D, 0F, 0F);
                        cow.set(entity);
                        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                                net.neoforged.neoforge.event.tick.EntityTickEvent.Pre.class, listener);
                        helper.assertTrue(level.addFreshEntity(entity), "fixture cow admission failed");
                    })
                    .thenWaitUntil(() -> helper.assertTrue(worker.get() != null, "the callback fixture did not tick"))
                    .thenExecute(() -> {
                        final Entity entity = cow.get();
                        helper.assertTrue(!entity.isAddedToLevel(), "the fix must not synthesize an added lifecycle event");
                        helper.assertTrue(entity.blockPosition().equals(target), "movement did not complete");
                        if (nativeTicks) helper.assertTrue(Boolean.TRUE.equals(worker.get()), "the fixture must execute on a native worker");
                        try {
                            final Object callback = entity.getLevelCallback();
                            final Field key = callback.getClass().getDeclaredField("currentSectionKey");
                            key.setAccessible(true);
                            helper.assertTrue(key.getLong(callback) == SectionPos.asLong(target), "the real storage callback did not publish the final section");
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError("fixture requires a real installed section-manager callback", failure);
                        }
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionOwnerOperations", timeoutTicks = 600)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "An unknown modded source remains a complete Serial Fence and submits exactly one cross-region container transfer between Native prefixes")
    static void blockEntityOwnerOperations(final DynamicTest test, final RegistrationHelper reg) {
        class Source extends net.minecraft.world.level.block.entity.BlockEntity implements net.minecraft.world.Container {
            final net.minecraft.world.SimpleContainer inventory = new net.minecraft.world.SimpleContainer(1);
            net.neoforged.neoforge.server.threading.api.BlockEntityOperations.Reference target;
            volatile net.neoforged.neoforge.server.threading.api.BlockEntityOperations.Task<net.neoforged.neoforge.server.threading.api.BlockEntityOperations.TransferResult> task;
            volatile boolean nativeSource;
            boolean serverOnly = true;
            int submissions;

            Source(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
                super(type, pos, state);
            }

            void tick() {
                this.serverOnly &= this.level.getServer().isSameThread() && !RegionThreadingHooks.isNativeRegionWorkerThread();
                if (this.target == null || this.task != null) return;
                ++this.submissions;
                final var api = net.neoforged.neoforge.server.threading.api.ParallelApi.forServer(this.level.getServer()).orElseThrow();
                this.nativeSource = api.currentPhase() == net.neoforged.neoforge.server.threading.api.ParallelApi.Phase.REGION_WORKER;
                final var operations = api.blockEntities();
                this.task = operations.transfer(operations.reference(this), this.target,
                        new net.neoforged.neoforge.server.threading.api.BlockEntityOperations.Transfer(0, 0, 8, null, null));
            }

            @Override
            public int getContainerSize() {
                return this.inventory.getContainerSize();
            }

            @Override
            public boolean isEmpty() {
                return this.inventory.isEmpty();
            }

            @Override
            public ItemStack getItem(int slot) {
                return this.inventory.getItem(slot);
            }

            @Override
            public ItemStack removeItem(int slot, int count) {
                return this.inventory.removeItem(slot, count);
            }

            @Override
            public ItemStack removeItemNoUpdate(int slot) {
                return this.inventory.removeItemNoUpdate(slot);
            }

            @Override
            public void setItem(int slot, ItemStack stack) {
                this.inventory.setItem(slot, stack);
            }

            @Override
            public boolean stillValid(net.minecraft.world.entity.player.Player player) {
                return true;
            }

            @Override
            public void clearContent() {
                this.inventory.clearContent();
            }
        }
        class SourceBlock extends Block implements net.minecraft.world.level.block.EntityBlock {
            private final java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntityType<Source>> type;

            SourceBlock(BlockBehaviour.Properties properties, java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntityType<Source>> type) {
                super(properties);
                this.type = type;
            }

            @Override
            public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
                return new Source(this.type.get(), pos, state);
            }

            @Override
            public <T extends net.minecraft.world.level.block.entity.BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
                    net.minecraft.world.level.Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
                return level.isClientSide ? null : (world, pos, blockState, entity) -> ((Source) entity).tick();
            }
        }
        final var sourceBlock = reg.blocks().registerBlockWithBEType("operation_source", SourceBlock::new, Source::new, BlockBehaviour.Properties.of());
        test.onGameTest(helper -> {
            final ServerLevel level = helper.getLevel();
            final var api = net.neoforged.neoforge.server.threading.api.ParallelApi.forServer(level.getServer()).orElseThrow();
            helper.assertTrue(api.capabilities().contains(net.neoforged.neoforge.server.threading.api.ParallelApi.Capability.BLOCK_ENTITY_OPERATIONS), "public operation capability missing");
            final RegionPolicy policy = RegionThreadingHooks.diagnostics().map(RegionThreadingDiagnostics::regionPolicy).orElse(RegionPolicy.FIXED_3X3);
            final int size = RegionThreadingHooks.diagnostics().map(RegionThreadingDiagnostics::regionChunkSize).orElse(3);
            final BlockPos from = evidenceBlockPos(helper, policy, size, 7);
            final BlockPos to = from.offset(size * 16, 0, 0);
            final BlockPos firstHopper = from.west(4);
            final BlockPos lastHopper = to.west(4);
            final var installed = RegionThreadingManager.currentOrNull();
            final var evidence = installed instanceof RegionThreadingEngine engine
                    ? new CanonicalBodyEvidence(level, engine, java.util.Set.of(firstHopper, from, lastHopper))
                    : null;
            helper.addEndListener(passed -> {
                if (evidence != null) evidence.close();
                level.setBlock(firstHopper.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(lastHopper.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(firstHopper, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(lastHopper, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(from, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(to, Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, from);
                releaseForcedChunk(level, to);
            });
            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, from);
                        forceChunk(level, to);
                        clearEvidenceChunkBlockEntities(level, from);
                        clearEvidenceChunkBlockEntities(level, to);
                    })
                    .thenWaitUntil(() -> helper.assertTrue(level.areEntitiesLoaded(ChunkPos.asLong(from)) && level.areEntitiesLoaded(ChunkPos.asLong(to)), "operation chunks have not loaded"))
                    .thenExecute(() -> {
                        placeDisabledHopper(level, firstHopper);
                        level.setBlock(from, sourceBlock.get().defaultBlockState(), 3);
                        placeDisabledHopper(level, lastHopper);
                        level.setBlock(to, Blocks.CHEST.defaultBlockState(), 3);
                        final Source source = (Source) level.getBlockEntity(from);
                        final var target = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(to);
                        source.setItem(0, new ItemStack(Items.IRON_INGOT, 16));
                        target.setItem(0, new ItemStack(Items.IRON_INGOT, 60));
                        source.target = api.blockEntities().reference(target);
                    })
                    .thenWaitUntil(() -> {
                        final Source source = (Source) level.getBlockEntity(from);
                        helper.assertTrue(source.task != null && source.task.isDone(), "the complete transfer did not finish");
                        helper.assertTrue(source.task.state() == net.neoforged.neoforge.server.threading.api.BlockEntityOperations.State.COMPLETED
                                && source.task.result().orElseThrow().moved() == 4, "the transfer did not return its exact partial-insertion result");
                        helper.assertTrue(source.getItem(0).getCount() == 12
                                && ((net.minecraft.world.Container) level.getBlockEntity(to)).getItem(0).getCount() == 64, "items were duplicated or lost");
                        helper.assertTrue(source.submissions == 1 && source.serverOnly && !source.nativeSource,
                                "a declaration must not grant an unknown source Worker authority; one complete server operation is required");
                    })
                    .thenIdle(25)
                    .thenExecute(() -> {
                        final Source source = (Source) level.getBlockEntity(from);
                        helper.assertTrue(source.submissions == 1 && source.getItem(0).getCount() == 12
                                && ((net.minecraft.world.Container) level.getBlockEntity(to)).getItem(0).getCount() == 64,
                                "later phases must not replay the completed transfer");
                        if (evidence != null) {
                            evidence.assertWorker(helper, firstHopper, false);
                            evidence.assertWorker(helper, lastHopper, false);
                            evidence.assertSerial(helper, from);
                            evidence.assertCompleteTicks(helper, java.util.Set.of(firstHopper, from, lastHopper));
                            evidence.assertFence(helper, java.util.Set.of(firstHopper), from, java.util.Set.of(lastHopper));
                            System.out.println("C42_OWNER_OPERATION exactlyOnce=1 moved=4 source=12 target=64 " + evidence.describe());
                        }
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionScheduledLamp", timeoutTicks = 300)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Production scheduled admission turns off a real lamp on a native worker without the test-only callback gate")
    static void scheduledLampAdmission(final DynamicTest test) {
        test.onGameTest(helper -> {
            final ServerLevel level = helper.getLevel();
            final var diagnostics = RegionThreadingHooks.diagnostics();
            final int size = diagnostics.map(RegionThreadingDiagnostics::regionChunkSize).orElse(3);
            final RegionPolicy policy = diagnostics.map(RegionThreadingDiagnostics::regionPolicy).orElse(RegionPolicy.FIXED_3X3);
            final BlockPos lamp = evidenceBlockPos(helper, policy, size, 8);
            final AtomicReference<Long> before = new AtomicReference<>(0L);
            helper.addEndListener(passed -> {
                level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, lamp);
            });
            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, lamp);
                        level.getChunkAt(lamp);
                    })
                    .thenWaitUntil(() -> helper.assertTrue(level.areEntitiesLoaded(ChunkPos.asLong(lamp))
                            && level.getChunkAt(lamp).getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING),
                            "the scheduled lamp chunk is not ready for block ticking"))
                    .thenIdle(5)
                    .thenExecute(() -> {
                        clearEvidenceChunkBlockEntities(level, lamp);
                        for (int dx = -2; dx <= 2; ++dx) for (int dy = -2; dy <= 2; ++dy) for (int dz = -2; dz <= 2; ++dz) {
                            level.setBlock(lamp.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                        }
                        net.neoforged.neoforge.server.threading.api.ParallelApi.forServer(level.getServer()).ifPresent(api -> {
                            if (!api.scheduledTicks().isEmpty()) before.set(api.scheduledTicks().getFirst().workerCompletions());
                        });
                        level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, true), 2);
                        level.scheduleTick(lamp, Blocks.REDSTONE_LAMP, 1);
                    })
                    .thenWaitUntil(() -> {
                        helper.assertTrue(!level.getBlockState(lamp).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT), "the real delayed lamp did not turn off");
                        if (ForgiaConfig.SERVER.enableRegionThreadingScheduledTicks.get()) {
                            final var api = net.neoforged.neoforge.server.threading.api.ParallelApi.forServer(level.getServer()).orElseThrow();
                            helper.assertTrue(api.scheduledTicks().getFirst().workerCompletions() > before.get(), "no production scheduled callback completed on a native worker");
                        }
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionChunkTick", timeoutTicks = 600)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Requires a configured chunk tick slice to tick an interior chunk on its region worker, with a region-scoped entity query")
    static void configuredChunkTickRunsOnTheRegionWorker(final DynamicTest test, final RegistrationHelper reg) {
        final AtomicReference<String> randomTickThread = new AtomicReference<>();
        final AtomicReference<String> queryThread = new AtomicReference<>();
        final AtomicInteger queryHits = new AtomicInteger();
        final var queryBlock = reg.blocks().register("region_query_block", () -> new Block(BlockBehaviour.Properties.of().randomTicks()) {
            @Override
            protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
                randomTickThread.compareAndSet(null, Thread.currentThread().getName());
                // A real range query issued from the random tick callback. Inside a chunk tick worker the lease
                // of the region answers it from the physical bucket of the region, so the predicate runs on the
                // worker and sees only the entities of that region.
                level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4.0), item -> {
                    queryThread.compareAndSet(null, Thread.currentThread().getName());
                    queryHits.incrementAndGet();
                    return true;
                });
            }
        });
        test.onGameTest(helper -> {
            final Optional<RegionThreadingDiagnostics> diagnostics = workerDiagnostics(helper);
            if (diagnostics.isEmpty() || !diagnostics.get().chunkTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, true, "chunk tick worker slice");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final BlockPos blockPos = evidenceBlockPos(helper, diagnostics.get().regionPolicy(), diagnostics.get().regionChunkSize(), 1);
            final long chunkTicksBefore = diagnostics.get().chunkTicksOnWorkers();
            final GameRules.IntegerValue randomTickSpeed = level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
            final int previousRandomTickSpeed = randomTickSpeed.get();
            final AtomicReference<ItemEntity> queryEntity = new AtomicReference<>();
            helper.addEndListener(passed -> {
                randomTickSpeed.set(previousRandomTickSpeed, level.getServer());
                level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, blockPos);
                final ItemEntity entity = queryEntity.get();
                if (entity != null) {
                    entity.discard();
                }
            });

            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, blockPos);
                        clearEvidenceChunkBlockEntities(level, blockPos);
                        final var chunk = level.getChunkAt(blockPos);
                        helper.assertTrue(chunk != null, "the evidence random tick chunk was not loaded");
                        final ItemEntity entity = new ItemEntity(level, blockPos.getX() + 0.5, blockPos.getY() + 1.5, blockPos.getZ() + 0.5, new ItemStack(Items.STONE));
                        entity.setNoGravity(true);
                        entity.setDeltaMovement(0.0, 0.0, 0.0);
                        level.addFreshEntity(entity);
                        queryEntity.set(entity);
                        level.setBlock(blockPos, queryBlock.get().defaultBlockState(), 3);
                        helper.assertTrue(
                                level.getBlockState(blockPos).is(queryBlock.get()),
                                "the custom random tick block was not placed; state=" + level.getBlockState(blockPos));
                        // Random sampling can repeat positions. This larger speed/window gives the small
                        // query fixture many opportunities; its actual callback/query assertions stay below.
                        randomTickSpeed.set(4096, level.getServer());
                    })
                    .thenIdle(400)
                    .thenExecute(() -> {
                        final RegionThreadingDiagnostics after = diagnostics();
                        helper.assertTrue(randomTickThread.get() != null, "the custom random tick block never ran");
                        helper.assertTrue(
                                randomTickThread.get().startsWith("NeoForge Region Worker"),
                                "an interior chunk tick ran on '" + randomTickThread.get() + "' instead of a region worker");
                        helper.assertTrue(queryHits.get() > 0, "the item entity predicate never ran, so no real entity query was exercised");
                        helper.assertTrue(
                                queryThread.get() != null && queryThread.get().startsWith("NeoForge Region Worker"),
                                "the chunk tick entity query ran on '" + queryThread.get() + "' instead of the leased region worker");
                        helper.assertTrue(after.chunkTickWorkerExecutionEnabled(), "the requested chunk tick worker configuration must stay visible");
                        helper.assertTrue(after.chunkTickWorkerDispatchEffective(), "a configured chunk tick slice must dispatch");
                        helper.assertTrue(
                                after.chunkTicksOnWorkers() > chunkTicksBefore,
                                "the chunk tick worker counter did not advance: before=" + chunkTicksBefore
                                        + " after=" + after.chunkTicksOnWorkers()
                                        + " fallback=" + after.fallbackChunkTicks()
                                        + " boundary=" + after.boundaryChunkTicks()
                                        + " unloadedNeighborhood=" + after.unloadedNeighborhoodChunkTicks());
                        helper.assertTrue(after.truncatedChunkTicks() == 0L, "the evidence chunk tick was truncated");
                        helper.assertTrue(after.ownershipViolationCount() == 0L, "the chunk tick raised an ownership violation");
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionUnsafeFallback", timeoutTicks = 1200)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "An unsafe beacon is a complete Serial Fence between an independent Furnace prefix and a closed Native Hopper suffix in the same Region")
    static void unsafeTickerForcesWholeRegionFallback(final DynamicTest test) {
        test.onGameTest(helper -> {
            final var available = workerDiagnostics(helper);
            if (available.isEmpty() || !available.get().blockEntityTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, false, "block entity worker slice");
                helper.succeed();
                return;
            }
            final var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            helper.assertTrue(engine.furnaceWorkWavesEnabled() && engine.regionLayout().isFixed(), "the canonical mixed test requires Furnace Waves and fixed layout");
            final ServerLevel level = helper.getLevel();
            final BlockPos first = evidenceBlockPos(helper, engine.regionPolicy(), engine.regionLayout().regionChunkSize(), 4);
            final BlockPos second = first.east(16);
            final BlockPos beacon = first.south(4);
            final BlockPos hopper = first.west(4);
            final var positions = java.util.List.of(first, second, beacon, hopper);
            final var tracked = java.util.Set.copyOf(positions);
            final var evidence = new CanonicalBodyEvidence(level, engine, tracked);
            helper.assertTrue(positions.stream().map(pos -> engine.regionLayout().regionAt(level, pos)).distinct().count() == 1,
                    "the regression must keep all four original tickers in the same logical Region");
            helper.addEndListener(passed -> {
                evidence.close();
                level.setBlock(hopper.above(), Blocks.AIR.defaultBlockState(), 3);
                positions.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
                positions.forEach(pos -> releaseForcedChunk(level, pos));
            });
            helper.startSequence()
                    .thenExecute(() -> positions.forEach(pos -> forceChunk(level, pos)))
                    .thenWaitUntil(() -> helper.assertTrue(positions.stream().allMatch(pos -> level.areEntitiesLoaded(ChunkPos.asLong(pos))), "mixed chunks have not loaded"))
                    .thenExecute(() -> {
                        positions.forEach(pos -> clearEvidenceChunkBlockEntities(level, pos));
                        // Real Level membership order, not a separate test scheduler. Both fine bodies may
                        // overlap; the complete beacon must follow them and precede the Native suffix.
                        placeBurningFurnace(level, first);
                        placeBurningFurnace(level, second);
                        level.setBlock(beacon, Blocks.BEACON.defaultBlockState(), 3);
                        placeDisabledHopper(level, hopper);
                    })
                    .thenIdle(220)
                    .thenExecute(() -> {
                        assertSmeltedOnce(helper, level, first, evidence.bodyCount(first));
                        assertSmeltedOnce(helper, level, second, evidence.bodyCount(second));
                        evidence.assertWorker(helper, first, true);
                        evidence.assertWorker(helper, second, true);
                        evidence.assertWorker(helper, hopper, false);
                        evidence.assertSerial(helper, beacon);
                        evidence.assertCompleteTicks(helper, tracked);
                        evidence.assertFence(helper, java.util.Set.of(first, second), beacon, java.util.Set.of(hopper));
                        helper.assertTrue(((net.minecraft.world.level.block.entity.HopperBlockEntity) level.getBlockEntity(hopper)).isEmpty(),
                                "the proved disabled branch must not enter item transport");
                        System.out.println("C42_SAME_REGION_FENCE " + evidence.describe());
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionRandomMelt", timeoutTicks = 400)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Runs the random tick of a real block on the evidence chunk, on the region worker when the slice is effective")
    static void randomTickMeltsIce(final DynamicTest test, final RegistrationHelper reg) {
        // Cover the complete isolated section: random position sampling is not an exhaustive walk.
        // Nine cells on a single y-plane can be missed even while the chunk and section are ticking.
        // With all 4096 cells instrumented, every actual random sample exercises this original callback.
        final AtomicInteger randomTicks = new AtomicInteger();
        final AtomicReference<String> randomTickThread = new AtomicReference<>();
        final var tickBlock = reg.blocks().register("region_random_tick_block", () -> new Block(BlockBehaviour.Properties.of().randomTicks()) {
            @Override
            protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
                randomTickThread.compareAndSet(null, Thread.currentThread().getName());
                randomTicks.incrementAndGet();
            }
        });
        test.onGameTest(helper -> {
            final Optional<RegionThreadingDiagnostics> diagnostics = workerDiagnostics(helper);
            if (diagnostics.isEmpty() || !diagnostics.get().chunkTickWorkerExecutionEnabled()) {
                // The test is about the chunk tick slice; without it the vanilla path is covered by the rest of
                // the suite, and a forced evidence chunk does not reliably reach the vanilla chunk loop.
                assertSliceDisabled(helper, true, "chunk tick worker slice");
                helper.succeed();
                return;
            }
            final GameRules.IntegerValue randomTickSpeed = helper.getLevel().getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
            final int previousRandomTickSpeed = randomTickSpeed.get();
            final ServerLevel level = helper.getLevel();
            final BlockPos centre = evidenceBlockPos(helper, diagnostics.map(RegionThreadingDiagnostics::regionPolicy).orElse(RegionPolicy.FIXED_3X3), diagnostics.map(RegionThreadingDiagnostics::regionChunkSize).orElse(RegionLayout.FIXED_3X3_REGION_CHUNK_SIZE), 2);
            final java.util.List<BlockPos> positions = new java.util.ArrayList<>();
            final var section = new BlockPos(centre.getX() & -16, centre.getY() & -16, centre.getZ() & -16);
            for (int dx = 0; dx < 16; dx++) {
                for (int dy = 0; dy < 16; dy++) {
                    for (int dz = 0; dz < 16; dz++) positions.add(section.offset(dx, dy, dz));
                }
            }
            helper.addEndListener(passed -> {
                randomTickSpeed.set(previousRandomTickSpeed, level.getServer());
                for (final BlockPos pos : positions) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
                releaseForcedChunk(level, centre);
            });
            final long chunkTicksBefore = diagnostics.map(RegionThreadingDiagnostics::chunkTicksOnWorkers).orElse(0L);
            // A high random tick speed keeps the window short; the property does not depend on the speed.
            randomTickSpeed.set(200, level.getServer());

            helper.startSequence()
                    .thenExecute(() -> forceChunk(level, centre))
                    .thenWaitUntil(() -> helper.assertTrue(level.shouldTickBlocksAt(ChunkPos.asLong(centre))
                            && level.areEntitiesLoaded(ChunkPos.asLong(centre)), "the actual evidence chunk must become ticking before sampling"))
                    .thenExecute(() -> {
                        for (final BlockPos pos : positions) {
                            level.setBlock(pos, tickBlock.get().defaultBlockState(), 3);
                        }
                        helper.assertTrue(positions.stream().allMatch(pos -> level.getBlockState(pos).is(tickBlock.get())), "every random sample in the complete section must reach the original callback");
                    })
                    .thenIdle(60)
                    .thenExecute(() -> {
                        helper.assertTrue(randomTicks.get() > 0,
                                "no random tick of the evidence chunk ran, so the chunk tick slice did not execute its random ticks;"
                                        + " block-ticking=" + level.shouldTickBlocksAt(new ChunkPos(centre).toLong())
                                        + " sectionRandomlyTicking="
                                        + level.getChunkAt(centre).getSections()[level.getSectionIndex(centre.getY())].isRandomlyTicking()
                                        + " randomTickSpeed=" + randomTickSpeed.get()
                                        + (diagnostics.isPresent() ? " workers=" + diagnostics().chunkTicksOnWorkers()
                                                + " boundary=" + diagnostics().boundaryChunkTicks()
                                                + " truncated=" + diagnostics().truncatedChunkTicks()
                                                : " slice=absent"));
                        if (diagnostics.isPresent() && diagnostics.get().chunkTickWorkerExecutionEnabled()) {
                            final RegionThreadingDiagnostics after = diagnostics();
                            helper.assertTrue(after.chunkTickWorkerDispatchEffective(),
                                    "a configured chunk tick slice must report the effective dispatch");
                            helper.assertTrue(after.chunkTicksOnWorkers() > chunkTicksBefore,
                                    "the evidence chunk did not tick on a region worker: before=" + chunkTicksBefore
                                            + " after=" + after.chunkTicksOnWorkers()
                                            + " boundary=" + after.boundaryChunkTicks()
                                            + " truncated=" + after.truncatedChunkTicks());
                            helper.assertTrue(randomTickThread.get() != null && randomTickThread.get().startsWith("NeoForge Region Worker"),
                                    "a configured chunk tick slice must run the random tick on a worker: " + randomTickThread.get());
                        } else {
                            assertSliceDisabled(helper, true, "chunk tick worker slice");
                        }
                    })
                    .thenSucceed();
        });
    }

    private static void placeDisabledHopper(ServerLevel level, BlockPos pos) {
        // HopperBlock.onPlace recomputes ENABLED from real neighbor power. A literal false state with
        // no power would immediately become active and correctly lose the closed-branch proof.
        level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        level.setBlock(pos, Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.ENABLED, false), 3);
    }

    private static void placeBurningFurnace(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.LIT, true), 3);
        final var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos);
        furnace.setItem(0, new ItemStack(Blocks.COBBLESTONE));
        // Use normal persistent state to select the already-burning audited branch. Ignition remains a
        // complete serial operation in production; this fixture does not replace the function or its recipe.
        final var tag = furnace.saveWithoutMetadata(level.registryAccess());
        tag.putInt("BurnTime", 400);
        tag.putInt("CookTime", 0);
        tag.putInt("CookTimeTotal", 200);
        furnace.loadWithComponents(tag, level.registryAccess());
    }

    private static void assertSmeltedOnce(GameTestHelper helper, ServerLevel level, BlockPos pos, long bodies) {
        final var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(furnace != null && furnace.getItem(0).isEmpty() && furnace.getItem(1).isEmpty()
                && furnace.getItem(2).is(Items.STONE) && furnace.getItem(2).getCount() == 1,
                "the complete original furnace must consume one cobblestone and produce exactly one stone: " + pos);
        final var tag = furnace.saveWithoutMetadata(level.registryAccess());
        helper.assertTrue(bodies >= 200 && tag.getInt("BurnTime") == 400 - bodies && tag.getInt("CookTime") == 0,
                "burn/cook state must match exactly one full original invocation per observed tick: " + pos + " bodies=" + bodies + " state=" + tag);
    }

    /** Transparent observations at the existing post-admission seams; no permission or body is replaced. */
    private static final class CanonicalBodyEvidence implements AutoCloseable {
        private record Body(long tick, BlockPos pos, String stage, boolean worker, boolean fine, String thread) {}

        private final ServerLevel level;
        private final RegionThreadingEngine engine;
        private final java.util.Set<BlockPos> tracked;
        private final java.util.List<Body> events = new java.util.ArrayList<>();

        CanonicalBodyEvidence(ServerLevel level, RegionThreadingEngine engine, java.util.Set<BlockPos> tracked) {
            this.level = level;
            this.engine = engine;
            this.tracked = tracked;
            engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                final BlockPos pos = ticker.getPos().immutable();
                record(pos, "start", false);
                try {
                    body.run();
                } finally {
                    record(pos, "end", false);
                }
            });
            engine.setFurnaceBodyObserverForTesting(source -> record(source.getBlockPos(), "start", true));
        }

        private synchronized void record(BlockPos pos, String stage, boolean fine) {
            if (this.tracked.contains(pos)) this.events.add(new Body(this.level.getGameTime(), pos, stage,
                    RegionThreadingHooks.isNativeRegionWorkerThread(), fine, Thread.currentThread().getName()));
        }

        private synchronized java.util.List<Body> starts() {
            return this.events.stream().filter(event -> event.stage().equals("start")).toList();
        }

        long bodyCount(BlockPos pos) {
            return starts().stream().filter(event -> event.pos().equals(pos)).count();
        }

        void assertWorker(GameTestHelper helper, BlockPos pos, boolean fine) {
            helper.assertTrue(starts().stream().anyMatch(event -> event.pos().equals(pos) && event.worker() && event.fine() == fine
                    && event.thread().startsWith("NeoForge Region Worker")), "no actual " + (fine ? "fine" : "Native") + " Worker body at " + pos + ": " + describe());
        }

        void assertSerial(GameTestHelper helper, BlockPos pos) {
            final var found = starts().stream().filter(event -> event.pos().equals(pos)).toList();
            helper.assertTrue(!found.isEmpty() && found.stream().allMatch(event -> !event.worker() && !event.fine()
                    && event.thread().equals(this.level.getServer().getRunningThread().getName())), "an unknown ticker must execute its complete original body on the server: " + pos);
        }

        private java.util.Map<Long, java.util.List<Body>> byTick() {
            return starts().stream().collect(java.util.stream.Collectors.groupingBy(Body::tick, java.util.TreeMap::new, java.util.stream.Collectors.toList()));
        }

        void assertCompleteTicks(GameTestHelper helper, java.util.Set<BlockPos> positions) {
            int complete = 0;
            for (final var tick : byTick().values()) {
                for (final var pos : positions) helper.assertTrue(tick.stream().filter(event -> event.pos().equals(pos)).count() <= 1,
                        "an original ticker must never be replayed within a real Level phase: " + tick);
                if (tick.stream().map(Body::pos).collect(java.util.stream.Collectors.toSet()).containsAll(positions)) ++complete;
            }
            helper.assertTrue(complete >= 20, "at least twenty complete real Level phases must be observed, got " + complete + ": " + describe());
        }

        synchronized void assertFence(GameTestHelper helper, java.util.Set<BlockPos> before, BlockPos fence, java.util.Set<BlockPos> after) {
            int complete = 0;
            for (final var entry : byTick().entrySet()) {
                final var positions = entry.getValue().stream().map(Body::pos).collect(java.util.stream.Collectors.toSet());
                if (!positions.containsAll(before) || !positions.contains(fence) || !positions.containsAll(after)) continue;
                final var events = this.events.stream().filter(event -> event.tick() == entry.getKey()).toList();
                final Body fenceStart = events.stream().filter(event -> event.pos().equals(fence) && event.stage().equals("start")).findFirst().orElseThrow();
                final Body fenceEnd = events.stream().filter(event -> event.pos().equals(fence) && event.stage().equals("end")).findFirst().orElseThrow();
                for (final BlockPos pos : before) {
                    final Body start = events.stream().filter(event -> event.pos().equals(pos) && event.stage().equals("start")).findFirst().orElseThrow();
                    helper.assertTrue(events.indexOf(start) < events.indexOf(fenceStart), "an earlier body must precede its Serial Fence: " + events);
                    if (!start.fine()) {
                        final Body end = events.stream().filter(event -> event.pos().equals(pos) && event.stage().equals("end")).findFirst().orElseThrow();
                        helper.assertTrue(events.indexOf(end) < events.indexOf(fenceStart), "a Native body must finish before the complete Fence begins: " + events);
                    }
                }
                for (final BlockPos pos : after) {
                    final Body start = events.stream().filter(event -> event.pos().equals(pos) && event.stage().equals("start")).findFirst().orElseThrow();
                    helper.assertTrue(events.indexOf(fenceEnd) < events.indexOf(start), "the whole Fence must return before any suffix body starts: " + events);
                }
                ++complete;
            }
            helper.assertTrue(complete >= 20, "no sustained canonical Fence sequence was observed: " + describe());
        }

        String describe() {
            return this.tracked.stream().map(pos -> pos + "=" + starts().stream().filter(event -> event.pos().equals(pos))
                    .collect(java.util.stream.Collectors.groupingBy(event -> event.worker() ? event.fine() ? "fineWorker" : "nativeWorker" : "serial", java.util.stream.Collectors.counting())))
                    .sorted().collect(java.util.stream.Collectors.joining(" "));
        }

        @Override
        public void close() {
            this.engine.setBlockEntityBodyObserverForTesting(null);
            this.engine.setFurnaceBodyObserverForTesting(null);
        }
    }

    /**
     * {@return the diagnostics of the installed engine, or an empty optional when the bridge is off}
     * <p>
     * Asserts the default-off state instead of silently skipping when the bridge is absent.
     */
    private static Optional<RegionThreadingDiagnostics> workerDiagnostics(GameTestHelper helper) {
        final Optional<RegionThreadingDiagnostics> diagnostics = RegionThreadingHooks.diagnostics();
        if (diagnostics.isEmpty()) {
            helper.assertFalse(ForgiaConfig.SERVER.enableRegionThreadingBridge.get(),
                    "region threading bridge is enabled but its diagnostics are not installed");
            helper.assertFalse(ForgiaConfig.SERVER.enableRegionThreadingBlockEntityTicks.get(),
                    "block entity worker slice is enabled but the bridge did not install it");
            helper.assertFalse(ForgiaConfig.SERVER.enableRegionThreadingRandomTicks.get(),
                    "random tick worker slice is enabled but the bridge did not install it");
        }
        return diagnostics;
    }

    @GameTest(batch = "regionVillagerPoiPolicy", timeoutTicks = 600)
    @EmptyTemplate(value = "9x6x9", floor = true)
    @TestHolder(description = "A villager acquires its real bed and workstation through regionized POI storage while its complete entity tick runs on a Worker")
    static void villagerTakesPoi(final DynamicTest test) {
        test.onGameTest(helper -> {
            final Optional<RegionThreadingDiagnostics> diagnostics = workerDiagnostics(helper);
            final ServerLevel level = helper.getLevel();
            // Relative y=1 is the iron floor block of the empty template, so the bed, the workstation and the
            // villager stand on the layer above it.
            final BlockPos foot = helper.absolutePos(new BlockPos(2, 2, 2));
            final BlockPos head = helper.absolutePos(new BlockPos(2, 2, 3));
            // The workstation has to be within the two blocks AssignProfessionFromJobSite requires, otherwise the
            // villager has to walk to it first and the test depends on pathfinding timing: at a diagonal distance
            // of 2.8 it failed about one run in five.
            final BlockPos jobSite = helper.absolutePos(new BlockPos(5, 2, 5));
            final BlockPos spawn = helper.absolutePos(new BlockPos(4, 2, 4));
            final ChunkPos chunk = new ChunkPos(spawn);
            final AtomicReference<Villager> villager = new AtomicReference<>();
            final AtomicInteger workerTicks = new AtomicInteger();
            final AtomicInteger serialTicks = new AtomicInteger();
            final java.util.concurrent.atomic.AtomicBoolean workerWithClaims = new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.function.Consumer<net.neoforged.neoforge.event.tick.EntityTickEvent.Post> observer = event -> {
                if (event.getEntity() != villager.get()) return;
                final Villager entity = villager.get();
                if (RegionThreadingHooks.isNativeRegionWorkerThread()) {
                    workerTicks.incrementAndGet();
                    if (entity.getBrain().getMemory(MemoryModuleType.HOME).map(GlobalPos::pos)
                            .filter(pos -> pos.equals(foot) || pos.equals(head)).isPresent()
                            && entity.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).filter(jobSite::equals).isPresent()) {
                        workerWithClaims.set(true);
                    }
                } else {
                    serialTicks.incrementAndGet();
                }
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.EntityTickEvent.Post.class, observer);
            helper.addEndListener(passed -> {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(observer);
                if (villager.get() != null) {
                    villager.get().discard();
                }
                level.getChunkSource().removeRegionTicket(TicketType.START, chunk, 1, Unit.INSTANCE);
            });

            helper.startSequence()
                    .thenExecute(() -> {
                        // The entity tick of the villager needs an entity-ticking chunk, which the spawn ticket of
                        // the level gives this test chunk without moving a player here.
                        level.getChunkSource().addRegionTicket(TicketType.START, chunk, 1, Unit.INSTANCE);
                        level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                                .setValue(BedBlock.FACING, Direction.SOUTH)
                                .setValue(BedBlock.PART, BedPart.FOOT));
                        level.setBlockAndUpdate(head, Blocks.RED_BED.defaultBlockState()
                                .setValue(BedBlock.FACING, Direction.SOUTH)
                                .setValue(BedBlock.PART, BedPart.HEAD));
                        level.setBlockAndUpdate(jobSite, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
                        final Villager spawned = EntityType.VILLAGER.spawn(level, spawn, MobSpawnType.STRUCTURE);
                        helper.assertTrue(spawned != null, "the test could not spawn its villager");
                        spawned.setPersistenceRequired();
                        villager.set(spawned);
                    })
                    // The villager acquires the bed through AcquirePoi and the workstation through the job site
                    // search of its core activity; both are POI reads and takes of the level's PoiManager.
                    .thenWaitUntil(() -> {
                        final Villager checked = villager.get();
                        helper.assertTrue(checked != null && !checked.isRemoved(), "the villager disappeared");
                        helper.assertTrue(checked.getBrain().getMemory(MemoryModuleType.HOME)
                                .map(GlobalPos::pos)
                                .filter(pos -> pos.equals(foot) || pos.equals(head))
                                .isPresent(), "the villager did not take the bed");
                        // The job site search first records the workstation as POTENTIAL_JOB_SITE and claims it
                        // as JOB_SITE once the villager reached it, which is the same tick that assigns the
                        // cartographer profession. Both are POI takes of the level's PoiManager.
                        helper.assertTrue(checked.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                                .map(GlobalPos::pos)
                                .filter(jobSite::equals)
                                .isPresent(), "the villager did not take the workstation");
                    })
                    .thenExecute(() -> {
                        if (entityTickSliceEffective(diagnostics)) {
                            helper.assertTrue(ForgiaConfig.SERVER.enableRegionThreadingPoi.get(), "entity Workers require the actual Region POI storage");
                            helper.assertTrue(workerTicks.get() > 0 && workerWithClaims.get(),
                                    "this exact villager must complete Worker ticks with its actual HOME/JOB_SITE memories; worker=" + workerTicks + " serial=" + serialTicks);
                        } else {
                            helper.assertTrue(serialTicks.get() > 0, "the reference configuration must complete this villager's server tick");
                        }
                        System.out.println("C42_VILLAGER_POI workerTicks=" + workerTicks + " serialTicks=" + serialTicks + " workerWithHomeAndJob=" + workerWithClaims);
                    })
                    .thenSucceed();
        });
    }

    /**
     * {@return true when the installed engine really ticks entities on region workers}
     * <p>
     * Both the configured slice and the actual region-owned storage must be present; a declaration alone
     * does not prove which thread ticked an individual entity, so live cases also observe their own body.
     */
    private static boolean entityTickSliceEffective(Optional<RegionThreadingDiagnostics> diagnostics) {
        return diagnostics.map(regionDiagnostics -> regionDiagnostics.entityTickWorkerExecutionEnabled()
                && regionDiagnostics.entityStorageMode() == RegionEntityStorageMode.REGION_OWNED).orElse(false);
    }

    /**
     * Asserts that the slice the test could not exercise is explicitly configured off.
     */
    private static void assertSliceDisabled(GameTestHelper helper, boolean randomTickSlice, String sliceName) {
        if (randomTickSlice) {
            helper.assertFalse(ForgiaConfig.SERVER.enableRegionThreadingRandomTicks.get(), sliceName + " is enabled but its worker execution is disabled");
        } else {
            helper.assertFalse(ForgiaConfig.SERVER.enableRegionThreadingBlockEntityTicks.get(), sliceName + " is enabled but its worker execution is disabled");
        }
    }

    private static RegionThreadingDiagnostics diagnostics() {
        return RegionThreadingHooks.diagnostics().orElseThrow(() -> new AssertionError("region threading diagnostics disappeared during the test"));
    }

    /**
     * Chooses an absolute block position for one test in an isolated evidence area.
     * <p>
     * The fixed 3x3 and dynamic region layouts both put many test structures into one region, and the
     * GameTest runner keeps all previously placed structures in the level. An evidence block next to a
     * structure could therefore share a region with another test's block entities. Each test instead
     * uses its own slot far away from the structure grid. The slot's chunk is still made an interior
     * chunk, because the furnace family and random tick slices only leave the server thread when the
     * neighboring chunks belong to the same region.
     * <p>
     * {@code fixedRegionChunkSize} is the size of the installed layout's fixed grid
     * ({@code RegionThreadingDiagnostics.regionChunkSize()}), so the slot stays an interior chunk for any
     * configured {@code regionThreadingFixedRegionChunks} value.
     */
    private static BlockPos evidenceBlockPos(ExtendedGameTestHelper helper, RegionPolicy policy, int fixedRegionChunkSize, int slot) {
        final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        final int baseX = origin.getX();
        final int baseZ = origin.getZ() + EVIDENCE_AREA_Z_OFFSET + slot * EVIDENCE_AREA_SPACING;
        int chunkX = Math.floorDiv(baseX, 16);
        int chunkZ = Math.floorDiv(baseZ, 16);
        if (policy == RegionPolicy.FIXED_3X3) {
            chunkX = nearestChunkWithResidue(chunkX, fixedRegionChunkSize, 1);
            chunkZ = nearestChunkWithResidue(chunkZ, fixedRegionChunkSize, 1);
        } else {
            chunkX = dynamicInteriorChunk(chunkX);
            chunkZ = dynamicInteriorChunk(chunkZ);
        }
        return new BlockPos(chunkX * 16 + 8, origin.getY() + 2, chunkZ * 16 + 8);
    }

    /**
     * Removes the block entities of the single evidence chunk after it was force-loaded.
     * <p>
     * Only the loaded evidence chunk is touched. The old cleanup walked all nine chunks of a fixed
     * region, force-loading chunks next to other test structures and deleting their block entities.
     */
    private static void clearEvidenceChunkBlockEntities(ServerLevel level, BlockPos pos) {
        final var chunk = level.getChunkAt(pos);
        for (final BlockPos blockEntityPos : new java.util.ArrayList<>(chunk.getBlockEntities().keySet())) {
            level.removeBlock(blockEntityPos, false);
        }
    }

    /**
     * Adds a forced ticket so the target chunk is block-ticking even though the test structure is placed
     * far away from the world spawn. Without the ticket the block entity and random tick loops skip the
     * chunk and the test would not exercise any worker.
     */
    private static void forceChunk(ServerLevel level, BlockPos pos) {
        final ChunkPos chunk = new ChunkPos(pos);
        level.setChunkForced(chunk.x, chunk.z, true);
        // A plain FORCED ticket keeps the chunk block-ticking but vanilla skips the chunk tick when no
        // player is nearby. The force-ticking ticket makes the isolated evidence chunk tick without
        // moving a player (and loading a whole player view) to it.
        level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 1, chunk, true);
    }

    private static void releaseForcedChunk(ServerLevel level, BlockPos pos) {
        final ChunkPos chunk = new ChunkPos(pos);
        level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 1, chunk, true);
        level.setChunkForced(chunk.x, chunk.z, false);
    }

    private static int nearestChunkWithResidue(int chunk, int size, int residue) {
        return chunk + Math.floorMod(residue - Math.floorMod(chunk, size), size);
    }

    private static int dynamicInteriorChunk(int chunk) {
        final int residue = Math.floorMod(chunk, 16);
        if (residue == 0) {
            return chunk + 1;
        }
        if (residue == 15) {
            return chunk - 1;
        }
        return chunk;
    }
}
