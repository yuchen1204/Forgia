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
import static org.mockito.Mockito.spy;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class MapIndexConcurrencyTest {
    @Test
    void concurrentAllocationsAreUniqueAndReloadContinuesAfterEveryIssuedId() throws Exception {
        final MapIndex index = new MapIndex();
        final List<CompletableFuture<List<Integer>>> jobs = new ArrayList<>();
        try (final var pool = Executors.newFixedThreadPool(8)) {
            final CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < 8; i++) jobs.add(CompletableFuture.supplyAsync(() -> {
                await(start);
                final List<Integer> result = new ArrayList<>();
                for (int j = 0; j < 1000; j++) result.add(index.getFreeAuxValueForMap().id());
                return result;
            }, pool));
            start.countDown();
            final HashSet<Integer> ids = new HashSet<>();
            for (final var job : jobs) ids.addAll(job.get(15, TimeUnit.SECONDS));
            assertEquals(8000, ids.size(), "two callers must not allocate the same map id");
            for (int i = 0; i < 8000; i++) assertTrue(ids.contains(i));
        }
        assertTrue(index.isDirty());
        final CompoundTag saved = index.save(new CompoundTag(), null);
        assertEquals(7999, saved.getInt("map"));
        assertEquals(8000, MapIndex.load(saved, null).getFreeAuxValueForMap().id());
    }

    @Test
    void allocationAndEncodingCannotObserveAHalfUpdatedCounter() throws Exception {
        final MapIndex index = new MapIndex();
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        final Object2IntMap<String> map = spy(new Object2IntOpenHashMap<String>());
        map.defaultReturnValue(-1);
        doAnswer(call -> {
            final int value = (int) call.callRealMethod();
            if (reads.getAndIncrement() == 0) {
                reading.countDown();
                await(release);
            }
            return value;
        }).when(map).getInt("map");
        final Field data = MapIndex.class.getDeclaredField("usedAuxIds");
        data.setAccessible(true);
        data.set(index, map);
        final var allocation = CompletableFuture.supplyAsync(index::getFreeAuxValueForMap);
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        final CountDownLatch saving = new CountDownLatch(1);
        final var save = CompletableFuture.supplyAsync(() -> {
            saving.countDown();
            return index.save(new CompoundTag(), null);
        });
        try {
            assertTrue(saving.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> save.get(150, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
        }
        assertEquals(0, allocation.get(5, TimeUnit.SECONDS).id());
        assertEquals(0, save.get(5, TimeUnit.SECONDS).getInt("map"));
    }

    @Test
    void sequentialSchemaPreservesOtherCountersAndNumericLoadingRules() {
        final CompoundTag original = new CompoundTag();
        original.putInt("map", 41);
        original.putInt("mod_aux_counter", 123);
        original.putString("ignored_non_numeric", "not an id");
        final MapIndex index = MapIndex.load(original, null);
        assertFalse(index.isDirty());
        assertEquals(42, index.getFreeAuxValueForMap().id());
        final CompoundTag saved = index.save(new CompoundTag(), null);
        assertEquals(java.util.Set.of("map", "mod_aux_counter"), saved.getAllKeys());
        assertEquals(123, saved.getInt("mod_aux_counter"));
        assertEquals(42, saved.getInt("map"));
        assertEquals(43, MapIndex.load(saved, null).getFreeAuxValueForMap().id());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
