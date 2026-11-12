/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.region.DeferredEntityAdmissionScope;
import org.junit.jupiter.api.Test;

class DeferredEntityAdmissionScopeTest {
    @Test
    void scopeExitWithoutAnObservedAdditionCannotProveCancellation() {
        final List<DeferredEntityAdmissionScope.Result> results = new ArrayList<>();
        try (var scope = DeferredEntityAdmissionScope.open()) {
            scope.watch(mock(Entity.class), results::add);
            assertEquals(List.of(), results);
        }
        assertEquals(List.of(DeferredEntityAdmissionScope.Result.UNOBSERVED), results);
    }

    @Test
    void explicitFinalizerCancellationReleasesItsReceiptOnlyOnce() {
        final List<DeferredEntityAdmissionScope.Result> results = new ArrayList<>();
        final Entity entity = mock(Entity.class);
        try (var scope = DeferredEntityAdmissionScope.open()) {
            scope.watch(entity, results::add);
            scope.rejectBeforeAdmission(entity);
            assertEquals(List.of(DeferredEntityAdmissionScope.Result.REJECTED), results);
            assertThrows(IllegalStateException.class, () -> scope.rejectBeforeAdmission(entity));
        }
        assertEquals(List.of(DeferredEntityAdmissionScope.Result.REJECTED), results);
    }

    @Test
    void exceptionalFinalizerStillReportsItsUnrecordedCandidate() {
        final List<DeferredEntityAdmissionScope.Result> results = new ArrayList<>();
        assertThrows(IllegalArgumentException.class, () -> {
            try (var scope = DeferredEntityAdmissionScope.open()) {
                scope.watch(mock(Entity.class), results::add);
                throw new IllegalArgumentException("finalizer failed");
            }
        });
        assertEquals(List.of(DeferredEntityAdmissionScope.Result.UNOBSERVED), results);
    }

    @Test
    void nestedScopesRestoreTheOuterScopeAndDoNotResolveItsCandidateEarly() {
        final List<String> results = new ArrayList<>();
        final Entity outerEntity = mock(Entity.class);
        try (var outer = DeferredEntityAdmissionScope.open()) {
            outer.watch(outerEntity, result -> results.add("outer:" + result));
            try (var inner = DeferredEntityAdmissionScope.open()) {
                inner.watch(mock(Entity.class), result -> results.add("inner:" + result));
                assertThrows(IllegalArgumentException.class, () -> inner.watch(outerEntity, result -> {}));
                assertThrows(IllegalStateException.class, outer::close);
            }
            assertEquals(List.of("inner:UNOBSERVED"), results);
            outer.watch(mock(Entity.class), result -> results.add("outer2:" + result));
        }
        assertEquals(3, results.size());
        assertEquals(1, results.stream().filter(result -> result.equals("outer:UNOBSERVED")).count());
        assertEquals(1, results.stream().filter(result -> result.equals("outer2:UNOBSERVED")).count());
    }

    @Test
    void cleanupFailureStillNotifiesOtherCandidatesAndRestoresTheThreadLocal() {
        final List<DeferredEntityAdmissionScope.Result> results = new ArrayList<>();
        final var sentinel = new IllegalStateException("accounting failure");
        final Entity entity = mock(Entity.class);
        final var scope = DeferredEntityAdmissionScope.open();
        scope.watch(entity, result -> {
            throw sentinel;
        });
        scope.watch(mock(Entity.class), results::add);
        assertEquals(sentinel, assertThrows(IllegalStateException.class, scope::close));
        assertEquals(List.of(DeferredEntityAdmissionScope.Result.UNOBSERVED), results);
        try (var next = DeferredEntityAdmissionScope.open()) {
            next.watch(entity, results::add);
        }
        assertEquals(2, results.size());
        assertThrows(IllegalStateException.class, scope::close);
    }

    @Test
    void scopeCannotBeUsedOrClosedFromAnotherThread() throws Exception {
        try (var scope = DeferredEntityAdmissionScope.open(); var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, () -> scope.watch(mock(Entity.class), result -> {}));
                assertThrows(IllegalStateException.class, scope::close);
            }).get(5, TimeUnit.SECONDS);
            scope.watch(mock(Entity.class), result -> {});
        }
    }
}
