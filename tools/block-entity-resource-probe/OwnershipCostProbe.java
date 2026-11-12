/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Access;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Binding;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Claims;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Identity;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Scope;

/** Standalone synthetic ownership costs, not Minecraft bodies, dispatch, Region leasing or server MSPT. */
public final class OwnershipCostProbe {
    private static final BooleanSupplier CURRENT = () -> true;
    private static volatile long sink;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        final int samples = args.length == 0 ? 21 : Integer.parseInt(args[0]);
        if (samples < 5 || samples > 101) throw new IllegalArgumentException("Samples must be in 5..101");
        final var allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        final List<Input> cases = new ArrayList<>();
        for (int units : new int[] { 2, 16, 64, 256 }) {
            for (int shared : new int[] { 4, 64, 256 }) cases.add(new Input(units, shared));
        }
        // Warm every shape before measuring any case, including the larger map/normalization paths.
        for (int round = 0; round < 3; ++round) {
            for (final Input input : cases) for (int iteration = 0; iteration < 24; ++iteration) run(input, allocations);
        }
        for (final Input input : cases) {
            for (int iteration = 0; iteration < 32; ++iteration) run(input, allocations);
            final long[][] values = new long[7][samples];
            for (int sample = 0; sample < samples; ++sample) {
                final long[] result = run(input, allocations);
                for (int part = 0; part < values.length; ++part) values[part][sample] = result[part];
            }
            for (final long[] part : values) Arrays.sort(part);
            final int p50 = samples / 2;
            final int p95 = Math.min(samples - 1, (int) Math.ceil(samples * .95) - 1);
            System.out.printf("{\"units\":%d,\"sharedReads\":%d,\"claimsPerUnit\":%d,\"samples\":%d,\"captureP50Ns\":%d,\"planP50Ns\":%d,\"reserveP50Ns\":%d,\"scopeP50Ns\":%d,\"releaseP50Ns\":%d,\"totalP50Ns\":%d,\"totalP95Ns\":%d,\"allocatedBytesP50\":%d}%n",
                    input.sources.length, input.shared.length, input.shared.length + 4, samples,
                    values[0][p50], values[1][p50], values[2][p50], values[3][p50], values[4][p50],
                    values[5][p50], values[5][p95], values[6][p50]);
        }
    }

    private static long[] run(Input input, ThreadMXBean allocations) {
        final long allocatedBefore = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
        final long start = System.nanoTime();
        final List<Claims> claims = new ArrayList<>(input.sources.length);
        for (int unit = 0; unit < input.sources.length; ++unit) {
            final var builder = Claims.builder(input.level, input.region);
            builder.claim(input.binding(input.sources[unit], input.region), Access.WRITE);
            builder.claim(input.binding(input.chunk, input.region), Access.READ);
            builder.claim(input.binding(input.inputs[unit], input.region), Access.WRITE);
            builder.claim(input.binding(input.outputs[unit], input.region), Access.WRITE);
            for (final Object shared : input.shared) builder.claim(input.binding(shared, null), Access.READ);
            claims.add(builder.build());
        }
        final long captured = System.nanoTime();
        final var planned = BlockEntityResourceOwnership.plan(claims);
        if (planned.dependencies().waves().size() != 1 || planned.dependencies().maxWidth() != input.sources.length) throw new AssertionError("Independent work lost its wave");
        final long plannedAt = System.nanoTime();
        final var ownership = new BlockEntityResourceOwnership(input.level, Map.of(input.region, CURRENT), CURRENT);
        final var leases = ownership.tryAcquireAll(planned.claims());
        if (leases == null || leases.size() != input.sources.length) throw new AssertionError("Current complete proof was refused");
        final long reserved = System.nanoTime();
        for (int unit = 0; unit < leases.size(); ++unit) {
            try (final var scope = leases.get(unit).enter()) {
                Scope.require(input.level, input.sources[unit], Access.WRITE);
                Scope.require(input.level, input.chunk, Access.READ);
                Scope.require(input.level, input.inputs[unit], Access.WRITE);
                Scope.require(input.level, input.outputs[unit], Access.WRITE);
                Scope.require(input.level, input.shared[0], Access.READ);
                ++input.effects[unit];
            }
        }
        final long scoped = System.nanoTime();
        ownership.close();
        final long ended = System.nanoTime();
        final long bytes = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocatedBefore;
        if (Scope.active()) throw new AssertionError("Ownership scope leaked");
        sink = input.effects[0];
        return new long[] { captured - start, plannedAt - captured, reserved - plannedAt, scoped - reserved,
                ended - scoped, ended - start, bytes };
    }

    private static final class Input {
        private final Object level = new Object();
        private final Object region = new Object();
        private final Object chunk = new Object();
        private final Object[] sources;
        private final Object[] inputs;
        private final Object[] outputs;
        private final Object[] shared;
        private final long[] effects;

        private Input(int units, int sharedReads) {
            this.sources = objects(units);
            this.inputs = objects(units);
            this.outputs = objects(units);
            this.shared = objects(sharedReads);
            this.effects = new long[units];
        }

        private Binding binding(Object instance, Object home) {
            return new Binding(new Identity(this.level, instance), home, CURRENT, null);
        }

        private static Object[] objects(int count) {
            final Object[] objects = new Object[count];
            for (int index = 0; index < count; ++index) objects[index] = new Object();
            return objects;
        }
    }
}
