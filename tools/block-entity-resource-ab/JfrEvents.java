/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Stream the authoritative binary recording; avoid expanding repeated stack metadata to multi-GB JSON. */
public final class JfrEvents {
    private static final Set<String> TYPES = Set.of("jdk.Compilation", "jdk.Deoptimization", "jdk.GCPhasePause",
            "jdk.GarbageCollection", "jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.ObjectAllocationSample", "jdk.JavaMonitorEnter",
            "jdk.ThreadPark", "jdk.SafepointBegin", "jdk.SafepointEnd");
    private static final String[][] CATEGORIES = {
            { "FurnaceWorkPreparation", "catalog/current furnace audit" },
            { "FurnaceResourcePreparation", "resource claims capture" },
            { "BlockEntityWorkPlanner", "dependency planner" },
            { "BlockEntityResourceOwnership", "resource identity/reservation/scope" },
            { "AbstractFurnaceBlockEntity", "original furnace body" },
            { "RegionWorkStealingDispatcher", "work dispatcher" },
            { "RegionThreadingEngine", "other region engine" }, { "RMI", "JMX/RMI" }, { "RMIConnection", "JMX/RMI" }
    };

    private static String quote(String value) {
        if (value == null) return "null";
        final StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); ++i) {
            final char c = value.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    private static String method(RecordedMethod method) {
        return method.getType().getName().replace('/', '.') + "." + method.getName();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input.jfr output.ndjson");
        long count = 0;
        try (RecordingFile input = new RecordingFile(Path.of(args[0]));
                BufferedWriter output = Files.newBufferedWriter(Path.of(args[1]))) {
            while (input.hasMoreEvents()) {
                final RecordedEvent event = input.readEvent();
                final String type = event.getEventType().getName();
                if (!TYPES.contains(type)) continue;
                final List<RecordedFrame> stack = event.getStackTrace() == null ? List.of() : event.getStackTrace().getFrames();
                String category = "other JVM/server code";
                boolean found = false, workerPark = false, barrierPark = false;
                for (final RecordedFrame frame : stack) {
                    final String name = method(frame.getMethod());
                    if (name.contains("RegionWorkerPool")) workerPark = true;
                    if (name.contains("RegionTickBarrier") || name.contains("managedBlock")) barrierPark = true;
                    if (!found) {
                        for (final String[] candidate : CATEGORIES) {
                            if (name.contains(candidate[0])) {
                                category = candidate[1];
                                found = true;
                                break;
                            }
                        }
                    }
                }
                final RecordedThread thread = type.equals("jdk.ExecutionSample") || type.equals("jdk.NativeMethodSample")
                        ? event.getThread("sampledThread") : event.getThread();
                final StringBuilder line = new StringBuilder(600);
                line.append("{\"type\":").append(quote(type)).append(",\"values\":{\"startTime\":")
                        .append(quote(event.getStartTime().toString())).append(",\"duration\":").append(quote(event.getDuration().toString()))
                        .append(",\"eventThread\":{\"javaName\":").append(quote(thread == null || thread.getJavaName() == null ? "unknown" : thread.getJavaName())).append('}')
                        .append(",\"normalizedCategory\":").append(quote(category))
                        .append(",\"normalizedLeaf\":").append(quote(stack.isEmpty() ? "" : method(stack.getFirst().getMethod())))
                        .append(",\"normalizedParkLabel\":").append(quote(workerPark ? "worker pool idle/dispatch park" : barrierPark
                                ? "server/worker barrier or managed block" : "other/20TPS limiter/JMX"));
                if (type.equals("jdk.ObjectAllocationSample")) {
                    line.append(",\"weight\":").append(event.getLong("weight"))
                            .append(",\"objectClass\":{\"name\":").append(quote(event.getClass("objectClass").getName())).append('}');
                }
                if (type.equals("jdk.Compilation") || type.equals("jdk.Deoptimization")) {
                    final RecordedMethod target = event.getValue("method");
                    line.append(",\"method\":{\"type\":{\"name\":").append(quote(target.getType().getName())).append("},\"name\":")
                            .append(quote(target.getName())).append('}');
                    if (type.equals("jdk.Compilation")) {
                        line.append(",\"compileLevel\":").append(event.getInt("compileLevel"))
                                .append(",\"isOsr\":").append(event.getBoolean("isOsr"));
                    } else {
                        line.append(",\"reason\":").append(quote(event.getString("reason")))
                                .append(",\"action\":").append(quote(event.getString("action")));
                    }
                }
                output.write(line.append("}}\n").toString());
                ++count;
            }
        }
        System.out.println("STREAMED_JFR_EVENTS " + count);
    }
}
