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
public class OwnershipCostProbe {
    static final BooleanSupplier CURRENT = () -> true;
    private static volatile long sink;
    private static final boolean TRACE = Boolean.getBoolean("forgia.probe.trace");

    /** Optional profiling only. Its allocations/CPU reads are deliberately absent in normal paired runs. */
    @jdk.jfr.Name("forgia.OwnershipPlan")
    @jdk.jfr.Label("Ownership planning interval")
    @jdk.jfr.StackTrace(false)
    static final class PlanEvent extends jdk.jfr.Event {
        int units;
        int sharedReads;
        long wallNanos;
        long cpuNanos;
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        Locale.setDefault(Locale.ROOT);
        final int samples = args.length == 0 ? 101 : Integer.parseInt(args[0]);
        final int caseWarmup = Integer.getInteger("forgia.probe.caseWarmup", 2000);
        if (caseWarmup < 0) throw new IllegalArgumentException("Case warmup must be nonnegative");
        if (samples < 5 || samples > 101) throw new IllegalArgumentException("Samples must be in 5..101");
        final String mode = args.length < 2 ? "expanded" : args[1];
        if (!mode.equals("expanded") && !mode.equals("shared")) throw new IllegalArgumentException("Mode must be expanded or shared");
        // Reflection is outside all timing. The common expanded harness also compiles against the frozen
        // .57 production classes, where SharedReads does not exist. Both versions execute real core code.
        final OwnershipCostProbe probe = mode.equals("expanded") ? new OwnershipCostProbe()
                : (OwnershipCostProbe) Class.forName(OwnershipCostProbe.class.getPackageName() + ".SharedReadOwnershipCostProbe").getDeclaredConstructor().newInstance();
        final var allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        if (TRACE) {
            if (!allocations.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("Thread CPU timing is unavailable");
            if (!allocations.isThreadCpuTimeEnabled()) allocations.setThreadCpuTimeEnabled(true);
        }
        final List<Input> cases = new ArrayList<>();
        for (int units : new int[] { 2, 16, 64, 256 }) {
            for (int shared : new int[] { 4, 64, 256 }) cases.add(new Input(units, shared));
        }
        for (int round = 0; round < 3; ++round) {
            for (final Input input : cases) for (int iteration = 0; iteration < 24; ++iteration) probe.run(input, allocations);
        }
        for (final Input input : cases) {
            for (int iteration = 0; iteration < caseWarmup; ++iteration) probe.run(input, allocations);
            final long[][] values = new long[8][samples];
            final boolean sampleTrace = Boolean.getBoolean("forgia.probe.sampleTrace");
            final long[] starts = sampleTrace ? new long[samples] : null;
            // Clock anchor is outside every measured interval. Midpoint uncertainty is recorded, not hidden.
            final long anchorBefore = sampleTrace ? System.nanoTime() : 0L;
            final var anchor = sampleTrace ? java.time.Instant.now() : null;
            final long anchorAfter = sampleTrace ? System.nanoTime() : 0L;
            for (int sample = 0; sample < samples; ++sample) {
                if (starts != null) starts[sample] = System.nanoTime();
                final long[] result = probe.run(input, allocations);
                for (int part = 0; part < values.length; ++part) values[part][sample] = result[part];
            }
            if (starts != null) {
                for (int sample = 0; sample < samples; ++sample) {
                    System.err.printf("{\"units\":%d,\"sharedReads\":%d,\"sample\":%d,\"startNano\":%d,\"anchorNano\":%d,\"anchorEpoch\":\"%s\",\"anchorUncertaintyNs\":%d,\"captureNs\":%d,\"planNs\":%d,\"totalNs\":%d}%n",
                            input.sources.length, input.shared.length, sample, starts[sample], (anchorBefore + anchorAfter) / 2,
                            anchor, anchorAfter - anchorBefore, values[0][sample], values[1][sample], values[6][sample]);
                }
            }
            for (final long[] part : values) Arrays.sort(part);
            final int p50 = samples / 2;
            final int p95 = Math.min(samples - 1, (int) Math.ceil(samples * .95) - 1);
            final int p99 = Math.min(samples - 1, (int) Math.ceil(samples * .99) - 1);
            System.out.printf("{\"mode\":\"%s\",\"units\":%d,\"sharedReads\":%d,\"claimsPerUnit\":%d,\"samples\":%d,\"caseWarmup\":%d,\"trace\":%b,\"sampleTrace\":%b",
                    mode, input.sources.length, input.shared.length, input.shared.length + 4, samples, caseWarmup, TRACE, sampleTrace);
            final String[] names = { "capture", "plan", "revalidate", "reserve", "scope", "release", "total" };
            for (int part = 0; part < names.length; ++part) {
                System.out.printf(",\"%sP50Ns\":%d,\"%sP95Ns\":%d,\"%sP99Ns\":%d,\"%sMaxNs\":%d", names[part], values[part][p50], names[part], values[part][p95],
                        names[part], values[part][p99], names[part], values[part][samples - 1]);
            }
            System.out.printf(",\"allocatedBytesP50\":%d,\"allocatedBytesP95\":%d}%n", values[7][p50], values[7][p95]);
        }
    }

    List<Claims> capture(Input input) {
        final List<Claims> claims = new ArrayList<>(input.sources.length);
        for (int unit = 0; unit < input.sources.length; ++unit) {
            final var builder = input.localClaims(unit);
            for (final Object shared : input.shared) builder.claim(input.binding(shared, null), Access.READ);
            claims.add(builder.build());
        }
        return claims;
    }

    boolean sameResources(List<Claims> planned, List<Claims> fresh) {
        for (int unit = 0; unit < planned.size(); ++unit) if (!planned.get(unit).sameResources(fresh.get(unit))) return false;
        return true;
    }

    private long[] run(Input input, ThreadMXBean allocations) {
        final PlanEvent trace = TRACE ? new PlanEvent() : null;
        final long allocatedBefore = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
        final long start = System.nanoTime();
        final List<Claims> claims = this.capture(input);
        final long captured = System.nanoTime();
        final long planCpuStart = TRACE ? allocations.getCurrentThreadCpuTime() : 0L;
        if (trace != null) trace.begin();
        final var planned = BlockEntityResourceOwnership.plan(claims);
        if (trace != null) trace.end();
        final long planCpuEnd = TRACE ? allocations.getCurrentThreadCpuTime() : 0L;
        if (planned.dependencies().waves().size() != 1 || planned.dependencies().maxWidth() != input.sources.length) throw new AssertionError("Independent work lost its wave");
        final long plannedAt = System.nanoTime();
        // Model the production revalidation boundary, including a second entirely fresh capture. No
        // mutable catalog/world checks are modelled; CURRENT is still only a constant pure predicate.
        final List<Claims> fresh = this.capture(input);
        if (!this.sameResources(planned.claims(), fresh)) throw new AssertionError("Identical fresh resources changed");
        final long revalidated = System.nanoTime();
        final var ownership = new BlockEntityResourceOwnership(input.level, Map.of(input.region, CURRENT), CURRENT);
        final var leases = ownership.tryAcquireAll(fresh);
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
        if (trace != null) {
            trace.units = input.sources.length;
            trace.sharedReads = input.shared.length;
            trace.wallNanos = plannedAt - captured;
            trace.cpuNanos = planCpuEnd - planCpuStart;
            trace.commit();
        }
        return new long[] { captured - start, plannedAt - captured, revalidated - plannedAt, reserved - revalidated,
                scoped - reserved, ended - scoped, ended - start, bytes };
    }

    static final class Input {
        final Object level = new Object();
        final Object region = new Object();
        final Object chunk = new Object();
        final Object[] sources;
        final Object[] inputs;
        final Object[] outputs;
        final Object[] shared;
        final long[] effects;

        private Input(int units, int sharedReads) {
            this.sources = objects(units);
            this.inputs = objects(units);
            this.outputs = objects(units);
            this.shared = objects(sharedReads);
            this.effects = new long[units];
        }

        Claims.Builder localClaims(int unit) {
            return Claims.builder(this.level, this.region)
                    .claim(this.binding(this.sources[unit], this.region), Access.WRITE)
                    .claim(this.binding(this.chunk, this.region), Access.READ)
                    .claim(this.binding(this.inputs[unit], this.region), Access.WRITE)
                    .claim(this.binding(this.outputs[unit], this.region), Access.WRITE);
        }

        Binding binding(Object instance, Object home) {
            return new Binding(new Identity(this.level, instance), home, CURRENT, null);
        }

        private static Object[] objects(int count) {
            final Object[] objects = new Object[count];
            for (int index = 0; index < count; ++index) objects[index] = new Object();
            return objects;
        }
    }
}
