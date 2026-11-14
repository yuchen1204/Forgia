/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapter;
import net.neoforged.neoforge.server.threading.api.adapter.BlockEntityAdapters;
import net.neoforged.neoforge.server.threading.api.adapter.DedicatedBlockEntityAdapter;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.registration.RegistrationHelper;

/** Actual registered test-Mod BEs, startup registration event, original Level path and real Workers. */
@EventBusSubscriber(modid = "neotests")
@ForEachTest(groups = "level.region.adapter")
public class GeneralBlockEntityAdapterGameTests {
    private static String firstType;
    private static String secondType;
    private static final Map<Long, Frame> FRAMES = new ConcurrentHashMap<>();
    private static final Object UNKNOWN_HANDLER_ROOT = new Object();

    private static final class Handler {
        int effects;

        void write() {
            ++this.effects;
        }
    }

    private static final Handler HANDLER = new Handler();

    private record Frame(BlockEntityAdapters.Phase phase, int beforeBody) {}

    @SubscribeEvent
    public static void registerAdapters(RegisterParallelAdaptersEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) return;
        final var registrar = event.registrar();
        if (firstType == null || secondType == null) throw new IllegalStateException("C5 test-Mod types were not registered before startup");
        final BlockEntityAdapter general = invocation -> {
            if (!event.getServer().isSameThread() || RegionThreadingHooks.isNativeRegionWorkerThread())
                throw new AssertionError("unknown Mod adapter must be inspected on the server");
            final var source = (Source) invocation.subject();
            FRAMES.put(source.getBlockPos().asLong(), new Frame(invocation.phase(), source.bodies));
            return new BlockEntityAdapters.Request(BlockEntityAdapters.Execution.REGION_OWNER,
                    List.of(new BlockEntityAdapters.Resource(source, BlockEntityAdapters.Access.WRITE,
                            BlockEntityAdapters.Ownership.INSTANCE_LOCAL, () -> BlockEntityAdapters.Validation.CURRENT)),
                    List.of());
        };
        registrar.registerBlockEntityAdapter(1, new BlockEntityAdapters.Descriptor("neotests:c5_general", Set.of(firstType), Set.of(BlockEntityAdapters.Execution.REGION_OWNER)), general);
        registrar.registerBlockEntityAdapter(1, new BlockEntityAdapters.Descriptor("neotests:c5_dedicated_metadata", Set.of(secondType), Set.of(BlockEntityAdapters.Execution.RESOURCE_SCOPED)), new DedicatedBlockEntityAdapter() {
            @Override
            public Audit audit() {
                return new Audit("test-only/no-core-proof", "1");
            }

            @Override
            public BlockEntityAdapters.Request inspect(BlockEntityAdapters.Invocation invocation) {
                general.inspect(invocation);
                return new BlockEntityAdapters.Request(BlockEntityAdapters.Execution.RESOURCE_SCOPED,
                        List.of(new BlockEntityAdapters.Resource(UNKNOWN_HANDLER_ROOT, BlockEntityAdapters.Access.WRITE,
                                BlockEntityAdapters.Ownership.UNKNOWN, () -> {
                                    throw new AssertionError("never probe an unknown handler");
                                })),
                        List.of());
            }
        });
    }

    private static final class Source extends BlockEntity {
        int bodies;
        boolean serverOnly = true;

        Source(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        void tick() {
            if (RegionThreadingManager.currentOrNull() != null) requireCoreRefusal();
            ++this.bodies;
            this.serverOnly &= this.level.getServer().isSameThread() && !RegionThreadingHooks.isNativeRegionWorkerThread();
            HANDLER.write(); // An unknown shared callback is part of the FULL original ticker.
        }
    }

    /** Observe the original production Permit/Ticket; this never issues or substitutes authority. */
    private static void requireCoreRefusal() {
        try {
            final var kernel = Class.forName("net.neoforged.neoforge.server.threading.region.BlockEntityExecutionEngine");
            final var current = kernel.getDeclaredField("CURRENT");
            current.setAccessible(true);
            final var permit = ((ThreadLocal<?>) current.get(null)).get();
            if (permit == null) throw new AssertionError("registered Mod body bypassed the canonical kernel");
            final var ticketField = permit.getClass().getDeclaredField("ticket");
            ticketField.setAccessible(true);
            final var ticket = ticketField.get(permit);
            final var proof = ticket.getClass().getDeclaredField("adapterProof");
            proof.setAccessible(true);
            if (!proof.get(ticket).toString().equals("SERIAL_ONLY"))
                throw new AssertionError("registered Mod body bypassed the Core proof refusal gate");
            final var guard = permit.getClass().getDeclaredField("guard");
            guard.setAccessible(true);
            if (guard.get(permit) != null || net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Scope.active())
                throw new AssertionError("unproven Mod body acquired Worker/resource authority");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class SourceBlock extends Block implements EntityBlock {
        private final Supplier<BlockEntityType<Source>> type;

        SourceBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<Source>> type) {
            super(properties);
            this.type = type;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Source(this.type.get(), pos, state);
        }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide ? null : (world, pos, blockState, entity) -> ((Source) entity).tick();
        }
    }

    @GameTest(batch = "regionC5Adapters", timeoutTicks = 800)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Two real Mod adapter requests share the canonical phase, keep full unknown callbacks serial, and preserve original cross-Region Native Worker overlap")
    static void generalAdaptersShareCanonicalPhase(final DynamicTest test, final RegistrationHelper reg) {
        final var first = reg.blocks().registerBlockWithBEType("c5_general_source", SourceBlock::new, Source::new, BlockBehaviour.Properties.of());
        final var second = reg.blocks().registerBlockWithBEType("c5_dedicated_source", SourceBlock::new, Source::new, BlockBehaviour.Properties.of());
        firstType = first.getId().toString();
        secondType = second.getId().toString();
        test.onGameTest(helper -> {
            final ServerLevel level = helper.getLevel();
            final var installed = RegionThreadingManager.currentOrNull();
            final var engine = installed instanceof RegionThreadingEngine active ? active : null;
            final boolean parallel = engine != null;
            if (!parallel) helper.assertTrue(!ForgiaConfig.SERVER.enableRegionThreadingBridge.get(), "missing bridge must be an explicit default-off control");
            final int size = parallel ? engine.diagnostics().regionChunkSize() : 3;
            final int width = size * 16;
            final BlockPos anchor = helper.absolutePos(new BlockPos(0, 0, 0));
            final BlockPos h0 = new BlockPos(Math.floorDiv(anchor.getX() + 30000, width) * width + 20, 100,
                    Math.floorDiv(anchor.getZ() - 30000, width) * width + 20);
            final BlockPos h1 = h0.offset(width, 0, 0);
            final BlockPos a = h0.west(32);
            final BlockPos b = h1.east(32);
            final var positions = List.of(a, h0, h1, b);
            final Set<BlockPos> nativePositions = Set.of(h0, h1);
            final var observed = new ConcurrentHashMap<BlockPos, AtomicInteger>();
            final var firstNative = ConcurrentHashMap.<BlockPos>newKeySet();
            final var rendezvous = new CountDownLatch(2);
            final var inFlight = new AtomicInteger();
            final var maximum = new AtomicInteger();
            final int[] baseline = new int[3];
            for (final var pos : positions) observed.put(pos, new AtomicInteger());
            if (parallel) engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                final var count = observed.get(ticker.getPos());
                if (count == null) {
                    body.run();
                    return;
                }
                count.incrementAndGet();
                if (nativePositions.contains(ticker.getPos()) && firstNative.add(ticker.getPos())) {
                    if (!RegionThreadingHooks.isNativeRegionWorkerThread()) throw new AssertionError("the original closed Hopper must remain on a real Worker");
                    maximum.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    rendezvous.countDown();
                    try {
                        if (!rendezvous.await(10, TimeUnit.SECONDS)) throw new AssertionError("the two canonical Native groups did not physically overlap");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }
                body.run();
            });
            helper.addEndListener(passed -> {
                if (parallel) engine.setBlockEntityBodyObserverForTesting(null);
                for (final var pos : positions) {
                    level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    final var chunk = new ChunkPos(pos);
                    level.setChunkForced(chunk.x, chunk.z, false);
                }
                FRAMES.clear();
            });
            helper.startSequence()
                    .thenExecute(() -> {
                        FRAMES.clear();
                        for (final var pos : positions) {
                            final var chunk = new ChunkPos(pos);
                            level.setChunkForced(chunk.x, chunk.z, true);
                            level.getChunk(chunk.x, chunk.z);
                        }
                    })
                    .thenWaitUntil(() -> helper.assertTrue(positions.stream().allMatch(pos -> level.areEntitiesLoaded(ChunkPos.asLong(pos))), "all four real chunks must be ticking"))
                    .thenExecute(() -> {
                        // The first C5 source is a Fence before BOTH new Hoppers, so the two checking
                        // groups fit in one original prefix even with other test tickers in the level.
                        level.setBlock(a, first.get().defaultBlockState(), 3);
                        for (final var pos : List.of(h0, h1)) {
                            level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                            level.setBlock(pos, Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.ENABLED, false), 3);
                        }
                        level.setBlock(b, second.get().defaultBlockState(), 3);
                    })
                    .thenIdle(5)
                    .thenExecute(() -> {
                        baseline[0] = ((Source) level.getBlockEntity(a)).bodies;
                        baseline[1] = ((Source) level.getBlockEntity(b)).bodies;
                        baseline[2] = HANDLER.effects;
                    })
                    .thenIdle(20)
                    .thenExecute(() -> {
                        final var sourceA = (Source) level.getBlockEntity(a);
                        final var sourceB = (Source) level.getBlockEntity(b);
                        helper.assertTrue(sourceA.serverOnly && sourceB.serverOnly, "both full unknown Mod tickers must stay on the server");
                        helper.assertTrue(sourceA.bodies - baseline[0] == 20 && sourceB.bodies - baseline[1] == 20, "each original Mod ticker must execute once per tick");
                        helper.assertTrue(HANDLER.effects - baseline[2] == 40, "the unknown shared callback must execute exactly once in each full original body");
                        if (parallel) {
                            helper.assertTrue(maximum.get() == 2, "real cross-Region Native Worker overlap must remain active");
                            helper.assertTrue(observed.get(a).get() == sourceA.bodies && observed.get(b).get() == sourceB.bodies, "actual body observations must match complete original ticker calls");
                            final var frameA = FRAMES.get(a.asLong());
                            final var frameB = FRAMES.get(b.asLong());
                            helper.assertTrue(frameA != null && frameB != null && frameA.phase() == frameB.phase(), "both adapters must see the same actual canonical phase");
                            helper.assertTrue(frameA.beforeBody() + 1 == sourceA.bodies && frameB.beforeBody() + 1 == sourceB.bodies, "inspection must occur before each corresponding complete body");
                            final var api = ParallelApi.forServer(level.getServer()).orElseThrow();
                            final var statuses = api.blockEntityAdapters();
                            final var generalStatus = statuses.stream().filter(s -> s.descriptor().id().equals("neotests:c5_general")).findFirst().orElseThrow();
                            final var dedicatedStatus = statuses.stream().filter(s -> s.descriptor().id().equals("neotests:c5_dedicated_metadata")).findFirst().orElseThrow();
                            helper.assertTrue(generalStatus.refusals().getOrDefault(BlockEntityAdapters.Refusal.CORE_AUDIT_UNAVAILABLE, 0L) > 0, "a declared local write still lacks a generic core audit");
                            helper.assertTrue(dedicatedStatus.refusals().getOrDefault(BlockEntityAdapters.Refusal.UNKNOWN_RESOURCE, 0L) > 0, "dedicated metadata cannot certify an unknown shared handler");
                            System.out.println("C5_GAME_PROOF nativeOverlap=" + maximum.get() + " samePhase=true modSerialBodies=40 sharedCallbacks=40 sourceA=" + sourceA.bodies + " sourceB=" + sourceB.bodies);
                        } else {
                            helper.assertTrue(FRAMES.isEmpty(), "default-off execution must not fabricate canonical inspections");
                        }
                    })
                    .thenSucceed();
        });
    }
}
