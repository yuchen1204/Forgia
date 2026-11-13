/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

import com.sun.tools.attach.VirtualMachine;
import java.io.BufferedWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/** External read-only observer of the unmodified installed server's native tickTimes/JVM MXBeans. */
public final class JvmMetrics {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("pid output.jsonl (stdin EOF stops)");
        final VirtualMachine vm = VirtualMachine.attach(args[0]);
        final String address;
        try {
            address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        final var ticksName = new javax.management.ObjectName("net.minecraft.server:type=Server");
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
                BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]))) {
            final MBeanServerConnection connection = connector.getMBeanServerConnection();
            final var os = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME,
                    com.sun.management.OperatingSystemMXBean.class);
            final var threads = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.THREAD_MXBEAN_NAME,
                    com.sun.management.ThreadMXBean.class);
            final var memory = ManagementFactory.newPlatformMXBeanProxy(connection, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
            final List<GarbageCollectorMXBean> collectors = new ArrayList<>();
            for (final var name : connection.queryNames(new javax.management.ObjectName("java.lang:type=GarbageCollector,*"), null)) {
                collectors.add(ManagementFactory.newPlatformMXBeanProxy(connection, name.toString(), GarbageCollectorMXBean.class));
            }
            // Enable only CPU/allocation counters, never contention monitoring or a method transformation.
            if (!threads.isThreadCpuTimeEnabled()) threads.setThreadCpuTimeEnabled(true);
            if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
            final Thread stop = new Thread(() -> {
                try {
                    while (System.in.read() != -1) { }
                } catch (java.io.IOException ignored) { }
            });
            stop.setDaemon(true);
            stop.start();
            System.out.println("READY");
            System.out.flush();
            int sample = 0;
            while (stop.isAlive()) {
                final long before = System.nanoTime();
                final long epoch = System.currentTimeMillis();
                final long[] ticks = (long[]) connection.getAttribute(ticksName, "tickTimes");
                final long cpu = os.getProcessCpuTime();
                long gcCount = 0, gcMillis = 0;
                for (final var gc : collectors) {
                    gcCount += gc.getCollectionCount();
                    gcMillis += gc.getCollectionTime();
                }
                final StringBuilder line = new StringBuilder(2500);
                line.append("{\"beforeNs\":").append(before).append(",\"epochMillis\":").append(epoch)
                        .append(",\"processCpuNs\":").append(cpu).append(",\"gcCount\":").append(gcCount)
                        .append(",\"gcMillis\":").append(gcMillis).append(",\"heapBytes\":").append(memory.getHeapMemoryUsage().getUsed())
                        .append(",\"ticksNs\":").append(java.util.Arrays.toString(ticks));
                if (sample++ % 10 == 0) {
                    final ThreadInfo[] infos = threads.getThreadInfo(threads.getAllThreadIds(), 32);
                    final List<ThreadInfo> selected = new ArrayList<>();
                    for (final var info : infos) if (info != null && (info.getThreadName().equals("Server thread")
                            || info.getThreadName().startsWith("NeoForge Region Worker #"))) selected.add(info);
                    final long[] ids = selected.stream().mapToLong(ThreadInfo::getThreadId).toArray();
                    final long[] cpus = threads.getThreadCpuTime(ids);
                    final long[] allocations = threads.getThreadAllocatedBytes(ids);
                    line.append(",\"threads\":[");
                    for (int i = 0; i < ids.length; ++i) {
                        if (i != 0) line.append(',');
                        final var info = selected.get(i);
                        // These platform-generated names/states have no quote/backslash characters.
                        line.append("{\"name\":\"").append(info.getThreadName()).append("\",\"id\":").append(ids[i])
                                .append(",\"cpuNs\":").append(cpus[i]).append(",\"allocatedBytes\":").append(allocations[i])
                                .append(",\"blockedCount\":").append(info.getBlockedCount()).append(",\"waitedCount\":").append(info.getWaitedCount())
                                .append(",\"state\":\"").append(info.getThreadState()).append("\",\"furnaceBodyOnStack\":")
                                .append(java.util.Arrays.stream(info.getStackTrace()).anyMatch(frame -> frame.getClassName().endsWith("AbstractFurnaceBlockEntity")))
                                .append('}');
                    }
                    line.append(']');
                }
                final long after = System.nanoTime();
                line.append(",\"afterNs\":").append(after).append('}');
                out.write(line.toString());
                out.newLine();
                out.flush();
                // Keep fixed wall cadence rather than 100ms + measurement cost. Oversleeps remain visible.
                final long remaining = 100_000_000L - (System.nanoTime() - before);
                if (remaining > 0) Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            }
        }
    }
}
