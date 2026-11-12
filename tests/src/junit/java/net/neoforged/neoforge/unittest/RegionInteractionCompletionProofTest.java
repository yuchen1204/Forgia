/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.EntityInteractionTask;
import net.neoforged.neoforge.server.threading.EntityInteractionTask.Reason;
import net.neoforged.neoforge.server.threading.EntityInteractionTask.State;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Completion proof is required for every whole-operation flavor, not only ticket-backed loading. */
class RegionInteractionCompletionProofTest {
    private static final String TYPE = "net.neoforged.neoforge.server.threading.region.RegionEntityInteractionTask";

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void ordinaryAndOwnerCallbacksSkipWhenCompletionLostQuiescence(boolean owner) throws ReflectiveOperationException {
        final ServerLevel level = mock(ServerLevel.class);
        final Entity source = entity(level);
        final Entity target = entity(level);
        final AtomicInteger bodies = new AtomicInteger();
        final EntityInteractionTask task = request(owner, source, target, level, bodies);
        assertNull(execute(task, () -> Reason.INVALID_CONTEXT));
        assertEquals(State.SKIPPED, task.state());
        assertEquals(Reason.INVALID_CONTEXT, task.reason());
        assertEquals(0, bodies.get(), "invalid completion must not execute arbitrary entity effects");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void ownerRetirementDuringCompletionProofIsRevalidated(boolean owner) throws ReflectiveOperationException {
        final ServerLevel level = mock(ServerLevel.class);
        final Entity source = entity(level);
        final Entity target = entity(level);
        final AtomicBoolean removed = new AtomicBoolean();
        when(target.isRemoved()).thenAnswer(call -> removed.get());
        final AtomicInteger bodies = new AtomicInteger();
        final EntityInteractionTask task = request(owner, source, target, level, bodies);
        assertNull(execute(task, () -> {
            removed.set(true);
            return Reason.NONE;
        }));
        assertEquals(State.SKIPPED, task.state());
        assertEquals(Reason.ENTITY_REMOVED, task.reason());
        assertEquals(0, bodies.get());
    }

    private static Entity entity(ServerLevel level) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        return entity;
    }

    private static EntityInteractionTask request(boolean owner, Entity source, Entity target, ServerLevel level,
            AtomicInteger bodies) throws ReflectiveOperationException {
        final Class<?> type = Class.forName(TYPE);
        final Consumer<State> observer = state -> {};
        final Constructor<?> constructor;
        if (owner) {
            constructor = type.getDeclaredConstructor(Entity.class, ServerLevel.class, Consumer.class, Consumer.class);
            constructor.setAccessible(true);
            return (EntityInteractionTask) constructor.newInstance(target, level, (Consumer<Entity>) current -> bodies.incrementAndGet(), observer);
        }
        constructor = type.getDeclaredConstructor(Entity.class, Entity.class, ServerLevel.class, BiConsumer.class, Consumer.class);
        constructor.setAccessible(true);
        return (EntityInteractionTask) constructor.newInstance(source, target, level,
                (BiConsumer<Entity, Entity>) (first, second) -> bodies.incrementAndGet(), observer);
    }

    private static Object execute(EntityInteractionTask task, Supplier<Reason> lifecycle) throws ReflectiveOperationException {
        final Method execute = task.getClass().getDeclaredMethod("execute", Supplier.class);
        execute.setAccessible(true);
        return execute.invoke(task, lifecycle);
    }
}
