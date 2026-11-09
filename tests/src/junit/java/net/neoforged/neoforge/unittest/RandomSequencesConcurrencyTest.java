/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.RandomSequences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Named streams retain vanilla algorithms, but each operation and encoded generator state must be indivisible. */
@Timeout(45)
class RandomSequencesConcurrencyTest {
    private static final long SEED = 0x1234567812345678L;
    private static final ResourceLocation ID = ResourceLocation.parse("neotests:shared_loot");

    @Test
    void mixedSequentialOperationsMatchTheOriginalGeneratorForEverySeedDefaultCombination() {
        for (final boolean world : List.of(false, true)) {
            for (final boolean name : List.of(false, true)) {
                final RandomSequences sequences = new RandomSequences(SEED);
                sequences.setSeedDefaults(123, world, name);
                final RandomSource actual = sequences.get(ID);
                final RandomSource expected = baseline(123, world, name);
                for (int i = 0; i < 100; i++) {
                    assertEquals(expected.nextInt(), actual.nextInt());
                    assertEquals(expected.nextInt(37), actual.nextInt(37));
                    assertEquals(expected.nextLong(), actual.nextLong());
                    assertEquals(expected.nextBoolean(), actual.nextBoolean());
                    assertEquals(expected.nextFloat(), actual.nextFloat());
                    assertEquals(expected.nextDouble(), actual.nextDouble());
                    assertEquals(expected.nextGaussian(), actual.nextGaussian());
                    assertEquals(expected.triangle(1.5, 2.5), actual.triangle(1.5, 2.5));
                    assertEquals(expected.nextInt(-10, 25), actual.nextInt(-10, 25));
                    assertEquals(expected.nextIntBetweenInclusive(-4, 9), actual.nextIntBetweenInclusive(-4, 9));
                    expected.consumeCount(3);
                    actual.consumeCount(3);
                }
                for (final long seed : List.of(0L, -1L, Long.MAX_VALUE)) {
                    expected.setSeed(seed);
                    actual.setSeed(seed);
                    final RandomSource expectedFork = expected.fork();
                    final RandomSource actualFork = actual.fork();
                    for (int i = 0; i < 10; i++) assertEquals(expectedFork.nextLong(), actualFork.nextLong());
                    final var expectedPositional = expected.forkPositional();
                    final var actualPositional = actual.forkPositional();
                    assertEquals(expectedPositional.at(-19, 64, 27).nextLong(), actualPositional.at(-19, 64, 27).nextLong());
                    assertEquals(expected.nextLong(), actual.nextLong());
                }
            }
        }
    }

    @Test
    void concurrentFirstLookupSharesOneNamedStreamAndConservesAllPrimitiveDraws() throws Exception {
        final RandomSequences sequences = new RandomSequences(SEED);
        final int workers = 8;
        final int draws = 2000;
        final CyclicBarrier start = new CyclicBarrier(workers);
        final List<Long> values = Collections.synchronizedList(new ArrayList<>());
        final List<CompletableFuture<Void>> jobs = new ArrayList<>();
        try (final var pool = java.util.concurrent.Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                jobs.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    final RandomSource random = sequences.get(ID);
                    for (int j = 0; j < draws; j++) values.add(random.nextLong());
                }, pool));
            }
            for (final var job : jobs) job.get(20, TimeUnit.SECONDS);
        }
        final List<Long> expected = new ArrayList<>();
        final RandomSource reference = baseline(0, true, true);
        for (int i = 0; i < workers * draws; i++) expected.add(reference.nextLong());
        Collections.sort(values);
        Collections.sort(expected);
        assertEquals(expected, values, "the assignments can vary by scheduling, but draws cannot be duplicated or lost");
        final AtomicInteger streams = new AtomicInteger();
        sequences.forAllSequences((id, random) -> streams.incrementAndGet());
        assertEquals(1, streams.get());
        assertEquals(reference.nextLong(), sequences.get(ID).nextLong(), "the persisted stream must advance by every accepted draw");
    }

    @Test
    void wrappersForOneGeneratorSerializeEvenIfTheSameNamedStreamWasLookedUpTwice() throws Exception {
        final RandomSequences sequences = new RandomSequences(SEED);
        final RandomSource first = sequences.get(ID);
        final RandomSource second = sequences.get(ID);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger collisions = new AtomicInteger();
        final RandomSource probe = mock(RandomSource.class);
        doAnswer(call -> {
            if (active.incrementAndGet() != 1) collisions.incrementAndGet();
            Thread.yield();
            active.decrementAndGet();
            return 1L;
        }).when(probe).nextLong();
        replaceGenerator(first, probe);
        replaceGenerator(second, probe);
        final CyclicBarrier start = new CyclicBarrier(2);
        final var a = CompletableFuture.runAsync(() -> {
            await(start);
            for (int i = 0; i < 500; i++) first.nextLong();
        });
        final var b = CompletableFuture.runAsync(() -> {
            await(start);
            for (int i = 0; i < 500; i++) second.nextLong();
        });
        a.get(15, TimeUnit.SECONDS);
        b.get(15, TimeUnit.SECONDS);
        assertEquals(0, collisions.get(), "locking a wrapper would leave two wrappers for the same generator racing");
    }

    @Test
    void saveCannotEncodeHalfOfAnInProgressGeneratorMutation() throws Exception {
        final RandomSequences sequences = new RandomSequences(SEED);
        final RandomSource wrapper = sequences.get(ID);
        final Field randomField = wrapper.getClass().getDeclaredField("random");
        randomField.setAccessible(true);
        final RandomSource underlying = (RandomSource) randomField.get(wrapper);
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final RandomSource probe = mock(RandomSource.class);
        doAnswer(call -> {
            synchronized (underlying) {
                inside.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return underlying.nextLong();
            }
        }).when(probe).nextLong();
        replaceGenerator(wrapper, probe);
        final var draw = CompletableFuture.supplyAsync(wrapper::nextLong);
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        final CountDownLatch saving = new CountDownLatch(1);
        final var save = CompletableFuture.supplyAsync(() -> {
            saving.countDown();
            return sequences.save(new CompoundTag(), null);
        });
        assertTrue(saving.await(10, TimeUnit.SECONDS));
        try {
            assertThrows(java.util.concurrent.TimeoutException.class, () -> save.get(150, TimeUnit.MILLISECONDS),
                    "NBT encoding must coordinate with mutations of the actual generator, not just the registry");
            CompletableFuture.runAsync(() -> sequences.reset(ID, 27, false, false)).get(5, TimeUnit.SECONDS);
            // The snapshot already selected the old registration. Registry maintenance must not wait on the
            // generator, and must not replace the generator which this in-progress snapshot encodes.
        } finally {
            release.countDown();
        }
        draw.get(10, TimeUnit.SECONDS);
        final RandomSequences restored = RandomSequences.load(SEED, save.get(10, TimeUnit.SECONDS));
        assertEquals(underlying.nextLong(), restored.get(ID).nextLong());
    }

    @Test
    void defaultsResetAndOldWrappersRetainTheirVanillaBinding() {
        final RandomSequences sequences = new RandomSequences(SEED);
        final RandomSource old = sequences.get(ID);
        final RandomSource oldBaseline = baseline(0, true, true);
        assertEquals(oldBaseline.nextLong(), old.nextLong());
        sequences.setSeedDefaults(4321, false, false);
        sequences.reset(ID);
        final RandomSource fresh = sequences.get(ID);
        final RandomSource freshBaseline = baseline(4321, false, false);
        assertEquals(freshBaseline.nextLong(), fresh.nextLong());
        assertEquals(oldBaseline.nextLong(), old.nextLong(), "reset does not retroactively rebind an existing vanilla wrapper");
        assertEquals(1, sequences.clear());
        assertEquals(freshBaseline.nextLong(), fresh.nextLong());
        assertEquals(baseline(4321, false, false).nextLong(), sequences.get(ID).nextLong());
        sequences.reset(ID, -29, true, false);
        assertEquals(baseline(-29, true, false).nextLong(), sequences.get(ID).nextLong());
    }

    @Test
    void traversalCallbacksRunOutsideTheRegistryLockAndCanResetOrCreateStreams() throws Exception {
        final RandomSequences sequences = new RandomSequences(SEED);
        sequences.get(ID);
        final AtomicInteger visited = new AtomicInteger();
        final ResourceLocation added = ResourceLocation.parse("neotests:added_from_callback");
        sequences.forAllSequences((id, value) -> {
            visited.incrementAndGet();
            try {
                CompletableFuture.runAsync(() -> {
                    sequences.reset(id);
                    sequences.get(added);
                }).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError("callbacks must not hold registry or generator locks", failure);
            }
        });
        assertEquals(1, visited.get(), "traversal uses a snapshot, not fail-fast or weakly changing live entries");
        final List<ResourceLocation> ids = new ArrayList<>();
        sequences.forAllSequences((id, random) -> ids.add(id));
        assertEquals(2, ids.size());
        assertTrue(ids.contains(added));
    }

    @Test
    void nbtKeepsTheExistingSchemaAndRestoresTheExactNextGeneratorValue() {
        final RandomSequences sequences = new RandomSequences(SEED);
        sequences.setSeedDefaults(-19, true, false);
        final RandomSource random = sequences.get(ID);
        final RandomSource expected = baseline(-19, true, false);
        for (int i = 0; i < 87; i++) assertEquals(expected.nextLong(), random.nextLong());
        final CompoundTag saved = sequences.save(new CompoundTag(), null);
        assertEquals(java.util.Set.of("salt", "include_world_seed", "include_sequence_id", "sequences"), saved.getAllKeys());
        final RandomSequence reference = new RandomSequence(SEED ^ -19L, Optional.empty());
        for (int i = 0; i < 87; i++) reference.random().nextLong();
        assertEquals(RandomSequence.CODEC.encodeStart(NbtOps.INSTANCE, reference).result().orElseThrow(),
                saved.getCompound("sequences").get(ID.toString()));
        final RandomSource loaded = RandomSequences.load(SEED, saved).get(ID);
        for (int i = 0; i < 500; i++) assertEquals(expected.nextLong(), loaded.nextLong());
    }

    @Test
    void unrelatedStreamsCanDrawWhileOneGeneratorIsBusy() throws Exception {
        final RandomSequences sequences = new RandomSequences(SEED);
        final RandomSource first = sequences.get(ID);
        final ResourceLocation other = ResourceLocation.parse("neotests:independent");
        final RandomSource second = sequences.get(other);
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final RandomSource probe = mock(RandomSource.class);
        doAnswer(call -> {
            inside.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return 1L;
        }).when(probe).nextLong();
        replaceGenerator(first, probe);
        final var blocked = CompletableFuture.supplyAsync(first::nextLong);
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        try {
            assertEquals(new RandomSequence(SEED, Optional.of(other)).random().nextLong(),
                    CompletableFuture.supplyAsync(second::nextLong).get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        blocked.get(10, TimeUnit.SECONDS);
    }

    @Test
    void mutationsMarkDirtyAndLoadingDoesNotInventAnUnsavedChange() {
        final RandomSequences sequences = new RandomSequences(SEED);
        assertFalse(sequences.isDirty());
        sequences.setSeedDefaults(123, true, false);
        assertTrue(sequences.isDirty());
        sequences.setDirty(false);
        sequences.reset(ID);
        assertTrue(sequences.isDirty());
        sequences.setDirty(false);
        sequences.reset(ID, 17, false, true);
        assertTrue(sequences.isDirty());
        final CompoundTag saved = sequences.save(new CompoundTag(), null);
        final RandomSequences loaded = RandomSequences.load(SEED, saved);
        assertFalse(loaded.isDirty(), "restoring settings must not mark a loaded file dirty by itself");
        final RandomSource random = loaded.get(ID);
        random.nextLong();
        assertTrue(loaded.isDirty());
        loaded.setDirty(false);
        assertThrows(IllegalArgumentException.class, () -> random.nextInt(0));
        assertTrue(loaded.isDirty(), "an unsuccessful call still preserves vanilla's dirty-marking behavior");
        assertEquals(1, loaded.clear());
        assertTrue(loaded.isDirty());
    }

    @Test
    void concurrentDefaultsAndFirstLookupNeverMixSeedConfigurationFields() throws Exception {
        final RandomSequences sequences = new RandomSequences(SEED);
        sequences.setSeedDefaults(13, false, false);
        final CyclicBarrier start = new CyclicBarrier(2);
        final var update = CompletableFuture.runAsync(() -> {
            await(start);
            for (int i = 0; i < 3000; i++) {
                sequences.setSeedDefaults(13, false, false);
                sequences.setSeedDefaults(-29, true, true);
            }
        });
        final var read = CompletableFuture.runAsync(() -> {
            await(start);
            for (int i = 0; i < 1500; i++) {
                final ResourceLocation id = ResourceLocation.parse("neotests:race_" + i);
                final long value = sequences.get(id).nextLong();
                final long a = new RandomSequence(13L, Optional.empty()).random().nextLong();
                final long b = new RandomSequence(SEED ^ -29L, Optional.of(id)).random().nextLong();
                assertTrue(value == a || value == b, "a named sequence must use one complete set of defaults");
            }
        });
        update.get(15, TimeUnit.SECONDS);
        read.get(15, TimeUnit.SECONDS);
    }

    @Test
    void concurrentLookupResetClearAndSaveKeepEveryEncodedStreamDecodable() throws Exception {
        final RandomSequences sequences = new RandomSequences(SEED);
        final CyclicBarrier start = new CyclicBarrier(3);
        final var draws = CompletableFuture.runAsync(() -> {
            await(start);
            for (int i = 0; i < 2500; i++) sequences.get(ResourceLocation.parse("neotests:id_" + i % 17)).nextLong();
        });
        final var resets = CompletableFuture.runAsync(() -> {
            await(start);
            for (int i = 0; i < 500; i++) {
                sequences.reset(ResourceLocation.parse("neotests:id_" + i % 17), i, true, true);
                if (i % 11 == 0) sequences.clear();
            }
        });
        final var snapshots = CompletableFuture.runAsync(() -> {
            await(start);
            for (int i = 0; i < 100; i++) {
                final CompoundTag saved = sequences.save(new CompoundTag(), null);
                for (final String key : saved.getCompound("sequences").getAllKeys()) {
                    final RandomSequence encoded = RandomSequence.CODEC.parse(NbtOps.INSTANCE, saved.getCompound("sequences").get(key))
                            .result().orElseThrow();
                    final RandomSource reloaded = RandomSequences.load(SEED, saved).get(ResourceLocation.parse(key));
                    assertEquals(encoded.random().nextLong(), reloaded.nextLong());
                }
            }
        });
        for (final var task : List.of(draws, resets, snapshots)) task.get(20, TimeUnit.SECONDS);
    }

    @Test
    void actualLootContextsOnNativeWorkersUseOneProtectedNamedStreamAndKeepExplicitSeedOverride() throws Exception {
        final RandomSequences sequences = new RandomSequences(SEED);
        final var level = mock(net.minecraft.server.level.ServerLevel.class);
        final var server = mock(net.minecraft.server.MinecraftServer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(level.getServer()).thenReturn(server);
        org.mockito.Mockito.when(level.getRandomSequence(ID)).thenAnswer(call -> sequences.get(ID));
        final var params = mock(net.minecraft.world.level.storage.loot.LootParams.class);
        org.mockito.Mockito.when(params.getLevel()).thenReturn(level);
        final var engine = new net.neoforged.neoforge.server.threading.region.RegionThreadingEngine(2, false, false);
        final Field pool = engine.getClass().getDeclaredField("workerPool");
        pool.setAccessible(true);
        final var workers = (net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor) pool.get(engine);
        final CyclicBarrier start = new CyclicBarrier(2);
        final List<Long> actual = Collections.synchronizedList(new ArrayList<>());
        final List<CompletableFuture<Void>> completed = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                final CompletableFuture<Void> done = new CompletableFuture<>();
                completed.add(done);
                assertTrue(workers.execute("named loot sequence", () -> {
                    try {
                        assertTrue(Thread.currentThread().getName().startsWith("NeoForge Region Worker"));
                        final var context = new net.minecraft.world.level.storage.loot.LootContext.Builder(params).create(Optional.of(ID));
                        await(start);
                        for (int j = 0; j < 1000; j++) actual.add(context.getRandom().nextLong());
                        final var explicit = new net.minecraft.world.level.storage.loot.LootContext.Builder(params)
                                .withOptionalRandomSeed(987L).create(Optional.of(ID));
                        assertEquals(RandomSource.create(987L).nextLong(), explicit.getRandom().nextLong());
                        done.complete(null);
                    } catch (Throwable failure) {
                        done.completeExceptionally(failure);
                    }
                }));
            }
            for (final var task : completed) task.get(20, TimeUnit.SECONDS);
        } finally {
            engine.shutdown();
        }
        final RandomSource reference = baseline(0, true, true);
        final List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 2000; i++) expected.add(reference.nextLong());
        Collections.sort(actual);
        Collections.sort(expected);
        assertEquals(expected, actual);
        assertEquals(reference.nextLong(), sequences.get(ID).nextLong(), "explicit seeds must not consume named streams");
    }

    private static RandomSource baseline(int salt, boolean world, boolean name) {
        return new RandomSequence((world ? SEED : 0L) ^ (long) salt, name ? Optional.of(ID) : Optional.empty()).random();
    }

    private static void replaceGenerator(RandomSource wrapper, RandomSource generator) throws Exception {
        final Field random = wrapper.getClass().getDeclaredField("random");
        random.setAccessible(true);
        random.set(wrapper, generator);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
