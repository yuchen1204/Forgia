/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.Test;

/** Calls the real 1.2.3 method after Mixin transformation; no copy of the mod implementation is tested. */
class EmptyStructureSelectorsIntegrationTest {
    private static final BlockPos POSITION = new BlockPos(51 * 16 + 3, 64, -52 * 16 + 5);
    private static final ResourceLocation STRUCTURE_ID = ResourceLocation.parse("test:structure");

    private static boolean matches(ServerLevel level, List<String> selectors) throws Throwable {
        final Class<?> manager = Class.forName("com.mobspawncontroller.command.MobSpawnManager");
        final MethodHandle method = MethodHandles.privateLookupIn(manager, MethodHandles.lookup()).findStatic(manager,
                "matchesAnyStructure", MethodType.methodType(boolean.class, ServerLevel.class, BlockPos.class, List.class));
        return (boolean) method.invokeExact(level, POSITION, selectors);
    }

    @Test
    void emptySelectorsNeverTouchTheWorldOrRandomSource() throws Throwable {
        final ServerLevel level = mock(ServerLevel.class);
        final RandomSource random = mock(RandomSource.class);
        when(level.getRandom()).thenReturn(random);

        assertFalse(matches(level, List.of()));
        verifyNoInteractions(level, random);
    }

    @Test
    void emptySelectorsDoNotEvenRequireALevel() throws Throwable {
        assertFalse(matches(null, List.of()), "without the actual mixin, the original method dereferences level here");
    }

    @Test
    void nonEmptyIdAndWildcardSelectorsRetainOriginalQueries() throws Throwable {
        final Fixture fixture = fixture();
        assertTrue(matches(fixture.level, List.of(STRUCTURE_ID.toString())));
        assertTrue(matches(fixture.level, List.of("*")));
        verify(fixture.manager, org.mockito.Mockito.times(2)).getAllStructuresAt(POSITION);
        verify(fixture.manager, org.mockito.Mockito.times(2)).getStructureAt(POSITION, fixture.structure);
        verifyNoInteractions(fixture.random);
    }

    @Test
    void unknownSelectorStillRunsTheOriginalLookupAndDoesNotMatch() throws Throwable {
        final Fixture fixture = fixture();
        assertFalse(matches(fixture.level, List.of("test:other")));
        verify(fixture.manager).getStructureAt(POSITION, fixture.structure);
        verifyNoInteractions(fixture.random);
    }

    @Test
    void structureTagsKeepTheirOriginalMembershipSemantics() throws Throwable {
        final Fixture fixture = fixture();
        when(fixture.holder.is(TagKey.create(Registries.STRUCTURE, ResourceLocation.parse("test:tag")))).thenReturn(true);
        assertTrue(matches(fixture.level, List.of("#test:tag")));
        assertFalse(matches(fixture.level, List.of("#test:missing_tag")));
        verifyNoInteractions(fixture.random);
    }

    @Test
    void originalNonEmptyLookupFailureStillPropagates() {
        final Fixture fixture = fixture();
        final IllegalStateException failure = new IllegalStateException("original structure lookup failure");
        when(fixture.manager.getAllStructuresAt(POSITION)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> matches(fixture.level, List.of("*"))));
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        final ServerLevel level = mock(ServerLevel.class);
        final RegistryAccess access = mock(RegistryAccess.class);
        final Registry<Structure> registry = mock(Registry.class);
        final StructureManager manager = mock(StructureManager.class);
        final Structure structure = mock(Structure.class);
        final StructureStart start = mock(StructureStart.class);
        final Holder.Reference<Structure> holder = mock(Holder.Reference.class);
        final RandomSource random = mock(RandomSource.class);
        when(level.getRandom()).thenReturn(random);
        when(level.registryAccess()).thenReturn(access);
        when(level.structureManager()).thenReturn(manager);
        doReturn(registry).when(access).registryOrThrow(Registries.STRUCTURE);
        when(manager.getAllStructuresAt(POSITION)).thenReturn(Map.of(structure, new LongOpenHashSet()));
        when(manager.getStructureAt(POSITION, structure)).thenReturn(start);
        when(start.isValid()).thenReturn(true);
        when(registry.getKey(structure)).thenReturn(STRUCTURE_ID);
        final ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, STRUCTURE_ID);
        when(registry.getResourceKey(structure)).thenReturn(Optional.of(key));
        when(registry.getHolder(key)).thenReturn(Optional.of(holder));
        return new Fixture(level, manager, structure, holder, random);
    }

    private record Fixture(ServerLevel level, StructureManager manager, Structure structure,
            Holder.Reference<Structure> holder, RandomSource random) {}
}
