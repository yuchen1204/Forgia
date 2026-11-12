/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create;

import com.simibubi.create.content.decoration.bracket.BracketedBlockEntityBehaviour;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.foundation.advancement.AdvancementBehaviour;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticEffectHandlerAccessor;
import net.neoforged.forgia.compat.create.mixin.SmartBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.TorquePropagatorAccessor;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter;
import org.jetbrains.annotations.Nullable;

/** Original ticks only. Network inspection is quiescent; workers read their instance and frozen ids only. */
public final class CreateTickAdmission implements BlockEntityTickAdapter {
    public static final ResourceLocation ID = ResourceLocation.parse("forgia_create:steady_ticks");
    public static final Set<ResourceLocation> TYPES = Set.of(
            type("simple_kinetic"), type("encased_shaft"), type("encased_cogwheel"),
            type("encased_large_cogwheel"), type("gearbox"), type("millstone"),
            type("water_wheel"), type("large_water_wheel"));
    private static final Set<Class<?>> CLASSES = Set.of(KineticBlockEntity.class, SimpleKineticBlockEntity.class,
            BracketedKineticBlockEntity.class, GearboxBlockEntity.class, MillstoneBlockEntity.class,
            WaterWheelBlockEntity.class, LargeWaterWheelBlockEntity.class);
    private static final Set<Class<?>> BEHAVIOURS = Set.of(BracketedBlockEntityBehaviour.class,
            DirectBeltInputBehaviour.class, AdvancementBehaviour.class);
    private static final int MAX_NETWORKS = 4096;
    private static final int MAX_MEMBERS = 65536;
    private final boolean verified;
    @Nullable
    private volatile Prepared prepared;

    private record Prepared(ResourceKey<Level> dimension, long gameTime, Set<Long> stableNetworks) {
        Prepared {
            stableNetworks = Set.copyOf(stableNetworks);
        }
    }

    public CreateTickAdmission() {
        this.verified = CreateAudit.verify();
    }

    private static ResourceLocation type(String name) {
        return ResourceLocation.fromNamespaceAndPath("create", name);
    }

    @Override
    public void prepare(ServerLevel level) {
        this.prepared = null;
        if (!this.verified) return;
        final var networks = TorquePropagatorAccessor.forgia$networks().get(level);
        final Set<Long> stable = new HashSet<>();
        final Set<Long> aliasedNetworks = new HashSet<>();
        final var inventories = new IdentityHashMap<ItemStackHandler, Long>();
        int members = 0;
        if (networks != null && networks.size() <= MAX_NETWORKS) {
            for (final var entry : networks.entrySet()) {
                final KineticNetwork network = entry.getValue();
                if (network == null || network.getClass() != KineticNetwork.class) continue;
                members += network.members.size();
                if (members > MAX_MEMBERS) {
                    stable.clear();
                    break;
                }
                if (!network.initialized || !Objects.equals(entry.getKey(), network.id)
                        || network.getSize() != network.members.size()
                        || !network.members.keySet().containsAll(network.sources.keySet()))
                    continue;
                boolean complete = true;
                for (final KineticBlockEntity member : network.members.keySet()) {
                    if (member == null || member.isRemoved() || member.getLevel() != level
                            || !Objects.equals(member.network, entry.getKey()) || topology(member) != Decision.ADMITTED) {
                        complete = false;
                        break;
                    }
                    if (member instanceof MillstoneBlockEntity millstone) {
                        for (final var inventory : new ItemStackHandler[] { millstone.inputInv, millstone.outputInv }) {
                            final Long previous = inventories.putIfAbsent(inventory, entry.getKey());
                            if (previous != null) {
                                aliasedNetworks.add(previous);
                                aliasedNetworks.add(entry.getKey());
                            }
                        }
                    }
                    final var pos = member.getBlockPos();
                    final LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                    if (chunk == null || !chunk.isLoaded() || chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK) != member) {
                        complete = false;
                        break;
                    }
                }
                if (complete) stable.add(entry.getKey());
            }
        }
        stable.removeAll(aliasedNetworks);
        this.prepared = new Prepared(level.dimension(), level.getGameTime(), stable);
    }

    @Override
    public Decision check(BlockEntity blockEntity) {
        if (!this.verified) return Decision.UNVERIFIED_TRANSFORMATIONS;
        if (blockEntity == null || !CLASSES.contains(blockEntity.getClass())) return Decision.UNKNOWN_CLASS;
        final KineticBlockEntity kinetic = (KineticBlockEntity) blockEntity;
        final Decision local = topology(kinetic);
        if (local != Decision.ADMITTED) return local;
        if (kinetic.network != null) {
            final Prepared snapshot = this.prepared;
            if (snapshot == null || !snapshot.dimension().equals(kinetic.getLevel().dimension())
                    || snapshot.gameTime() != kinetic.getLevel().getGameTime()
                    || !snapshot.stableNetworks().contains(kinetic.network))
                return Decision.SHARED_RESOURCE;
        } else if (kinetic.source != null || kinetic.getTheoreticalSpeed() != 0) {
            return Decision.SHARED_RESOURCE;
        }
        if (kinetic instanceof MillstoneBlockEntity millstone) return millstone(millstone);
        return Decision.ADMITTED;
    }

    /** The whole known component must stay topology-inert, including members that retain a serial body. */
    private static Decision topology(KineticBlockEntity kinetic) {
        if (!CLASSES.contains(kinetic.getClass())) return Decision.UNKNOWN_CLASS;
        if (!((SmartBlockEntityAccessor) kinetic).forgia$isInitialized()) return Decision.INITIALIZING;
        final var fields = (KineticBlockEntityAccessor) kinetic;
        if (kinetic.needsSpeedUpdate() || kinetic.networkDirty || fields.forgia$validationCountdown() <= 0
                || kinetic instanceof GeneratingKineticBlockEntity generator && generator.reActivateSource
                || kinetic instanceof WaterWheelBlockEntity && ((SmartBlockEntityAccessor) kinetic).forgia$lazyTickCounter() <= 0) {
            return Decision.TOPOLOGY_CHANGE;
        }
        final KineticEffectHandler effects = fields.forgia$effects();
        if (effects == null || effects.getClass() != KineticEffectHandler.class
                || ((KineticEffectHandlerAccessor) effects).forgia$owner() != kinetic)
            return Decision.SHARED_RESOURCE;
        if (((KineticEffectHandlerAccessor) effects).forgia$particleCountdown() == 1) return Decision.CALLBACK_REQUIRED;
        for (final var behaviour : kinetic.getAllBehaviours()) {
            if (!BEHAVIOURS.contains(behaviour.getClass()) || behaviour.blockEntity != kinetic) return Decision.UNKNOWN_BEHAVIOUR;
        }
        if (kinetic instanceof MillstoneBlockEntity millstone && !plainInventories(millstone)) return Decision.SHARED_RESOURCE;
        return Decision.ADMITTED;
    }

    private static boolean plainInventories(MillstoneBlockEntity millstone) {
        return millstone.inputInv != null && millstone.outputInv != null
                && millstone.inputInv.getClass() == ItemStackHandler.class && millstone.outputInv.getClass() == ItemStackHandler.class
                && millstone.inputInv != millstone.outputInv;
    }

    private static Decision millstone(MillstoneBlockEntity millstone) {
        // The plan excludes frozen ticks. Avoid getSpeed(), which would consult the level tick manager.
        final float speed = millstone.isOverStressed() ? 0.0f : millstone.getTheoreticalSpeed();
        if (speed == 0.0f) return Decision.ADMITTED;
        for (int slot = 0; slot < millstone.outputInv.getSlots(); ++slot) {
            if (millstone.outputInv.getStackInSlot(slot).getCount() == millstone.outputInv.getSlotLimit(slot)) return Decision.ADMITTED;
        }
        if (millstone.timer > 0) {
            final int decrement = Mth.clamp((int) Math.abs(speed / 16.0f), 1, 512);
            return millstone.timer > decrement ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
        }
        return millstone.inputInv.getStackInSlot(0).isEmpty() ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
    }
}
