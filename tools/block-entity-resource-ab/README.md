# Installed `.58` Resource Ownership A/B

This is a real installed Minecraft server fixture using the frozen production `.58` binary. It measures
incremental Resource Ownership overhead with **Furnace waves already enabled in both conditions**.
It is not a modpack/multiplayer claim or a comparison against vanilla/stock NeoForge.

The observer is an **external, read-only local JMX client** using the JVM's built-in local management
service, started before warmup. No helper mod, Mixin, bytecode instrumentation agent, server class edit,
new worker admission or source rebuild is installed. The native100-slot Minecraft tick ring and standard
JVM MXBeans provide full server tick duration, CPU, GC, heap and server/worker allocation. Complete
quiescent NBT and existing engine counters prove that equivalent real work completed.

## Reproduce

Requirements: JDK21, accepted local `eula.txt`, previously installed `.58` libraries, and the exact installer
in `projects/neoforge/build/libs/`. Both installer and installed universal hashes are enforced. Private
world directories must be new. Never point `--runs` at a real server or world. Only immutable installed
libraries are shared; worlds/config/storage/logs are private. Keep competing builds/tests/servers stopped.

```sh
export JAVA_HOME=/absolute/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p tmp/be-resource-tail/metrics-classes
javac --add-modules jdk.attach -d tmp/be-resource-tail/metrics-classes \
  tools/block-entity-resource-ab/{JvmMetrics,JfrEvents}.java
python3 -m unittest discover -s tools/block-entity-resource-ab -p 'test_*.py' -v

python3 -u tools/block-entity-resource-ab/server_ab.py \
  --runs tmp/be-resource-tail/matrix \
  --installation /absolute/path/to/private-installed-58 \
  --eula /absolute/path/to/already-accepted/eula.txt \
  --java "$JAVA_HOME/bin/java" --classes tmp/be-resource-tail/metrics-classes \
  --counts 2 64 256 --layouts chunk chunks regions --workers 1 2 4 8 \
  --warmup-ticks 400 --window-ticks 200 --windows 2

python3 tools/block-entity-resource-ab/export_results.py \
  tmp/be-resource-tail/matrix tmp/be-resource-tail/exported-matrix.json
```

The full matrix is intentionally sequential and takes substantial wall time at real20TPS. Each shape
has400 discarded warmup ticks and two exact200tick windows. Longer checks can use `--window-ticks 1200` and `--warmup-ticks 1200`; the parser requires both to be
positive multiples of 200. `--rounds 2` reverses order on another independent JVM pair. Consecutive windows are not independent
JVM replicates. Counts up to256 are supported and0 is an optional idle control; sample shape order is fixed.

To resume an interrupted matrix, pass `--resume-completed` with identical experiment parameters. The
tool retains a manifest hash/history, accepts only complete PASS/serverExit0 processes with every expected
shape/window, and refuses partial or failed world directories. Archive a failed pre-measurement directory
under a different evidence name; it must never be relabelled as a successful cell. A bootstrap refusal
between server `Done` and RCON readiness is retried before any workload/timing begins.

For diagnosis only, pass `--jfr /absolute/path/to/settings.jfc`: the external `jcmd` starts recording,
then discards another200ticks. Keep profiled runs separate from the primary unprofiled A/B; profiling
changes JIT timing and adds overhead. After all performance runs stop, stream the binary recording:

```sh
jfr summary /absolute/path/to/steady.jfr
java -Xmx256m -cp tmp/be-resource-tail/metrics-classes JfrEvents \
  /absolute/path/to/steady.jfr tmp/be-resource-tail/events.ndjson
python3 tools/block-entity-resource-ab/analyze_jfr.py \
  tmp/be-resource-tail/events.ndjson /absolute/path/to/run/result.json \
  tmp/be-resource-tail/jfr-summary.json
```

Repeated park-event stack metadata expands a10MB binary JFR into over13GB of pretty JSON. `JfrEvents`
reads one event at a time, retaining its exact timestamp/duration/thread plus explicitly labelled nearest
caller/leaf/park classification and compilation/allocation fields; `analyze_jfr.py` streams those records.
The binary JFR remains authoritative for full stacks. The analyzer refuses a whole JSON expansion over50MB.
A small diagnostic JSON from `jfr print --json` is also supported for parser cross-checks. Never infer
CPU time or exact bytes from execution/allocation sample weights.

## Metric definitions and safeguards

- `chunk`: up to256 furnaces in a single chunk, vertical/horizontal spacing4blocks.
- `chunks`: up to9 chunks in the same fixed3×3chunk Region.
- `regions`: up to8 chunk centres in distinct fixed Regions. Complete3×3neighbourhoods are loaded.
- Fixed seed813584, flat world, no players/other mods, no mob spawning/random ticks. Exact vanilla inert
  furnaces start with64ore/64coal, BurnTime30000, CookTime100,200tick recipe. The100tick phase centres
  every completion/ordered notification inside the measured active interval, rather than trimming it
  with the freeze boundaries. Complete before/afterNBT and200tick output counts are checked. This is continuous burning,
  not fuel transitions, active comparator/Hopper notifications, container networks or mod callbacks.
- All measurements use identical2GiB fixed G1 heap/VM flags, except worker count and resource condition.
  Original waves, persistent workers and the BE slice are enabled in both conditions. All product defaults
  remain disabled. The1worker case records concurrency1; it is not called successful overlap.
- A scheduled vanilla function freezes at the exact target gameTime. A second read proves it stopped.
  Snapshots and safety counters are taken while frozen, outside the active timing interval. Complete NBT
  is collected in bounded groups of64because Vanilla abbreviates long SNBT lists at128elements.
  `<...>`, incomplete snapshots, wrong outputs, conservation errors, skipped/failed/rejected bodies,
  refused resources, tick failures or ownership violations stop the run.
- `ticks`, output items, original body and lease deltas cover the exact200gameTick window.
- `metrics.mspt` uses only reconstructed native tick times wholly inside the proven-active inner interval:
  unfreeze acknowledged +100ms through the last below-target gameTime query -100ms. No frozen polling,
  snapshot, startup or warmup duration contributes. Native`tickServer` time excludes the20TPS limiter sleep
  and its finalServerTickPost hook, matching Minecraft's own metric; this fixture has no callback mods.
- JMX reads the native ring every100ms. Changed cyclic ranges preserve equal-valued interior slots;
  a4second gap risks overwrite and fails. An equal-valued endpoint is inherently ambiguous without
  instrumenting server code. Record raw rings and counts; do not claim perfect zero-overhead tracing.
- `tps` uses native observed ticks per inner-active wall interval; `controlWindowTps` also includes
  freeze acknowledgment and is **not** actual server TPS. Observer latency is recorded.
- CPU100% means one logical core. The reference host has4physical/8logical CPUs; `cpuPercentOfEightLogicalCPUs`
  is CPU/8. Other hosts must use their own CPU count when interpreting this convenience field.
- GC is the sum of standard MXBean collection count/time deltas, not a claim that all GC time is CPU or
  that all collections are stop-the-world. Diagnostic JFR provides separate pause/event evidence.
- `serverAndWorkerAllocatedBytes` covers those threads, not all JVM/native memory. Its first/last thread
  samples lie inside the active interval and its own observation duration is reported.
- `maxSimultaneousOriginalBodiesSinceBoot` is the existing cumulative exact body-overlap counter.
  Per-window thread stacks and worker CPU provide additional samples; the cumulative maximum is not
  a window concurrency distribution or worker utilisation. Short bodies may escape sampled stacks.
- The exporter requires every paired window, recomputes quantiles from native rings, and asserts exact
  before/after NBT hash and completion equivalence. It exports all active tick values so quantiles can
  be recalculated. It never replaces missing metrics with zero.

Do not upload runtime directories: they contain worlds, properties/credentials, logs, JFR and jars.
To stop gracefully, create `STOP` in that run root or signal the Python controller. It stops after a
complete RCON exchange and closes its own connection. Do not race a second RCON controller's `stop`
against an outstanding request: Vanilla shutdown can wait for a client waiting on the server thread.
Only the explicitly exported performance records, tools/tests and interpretation belong in the repository.
See [investigation and limits](../../region-threading/BLOCK-ENTITY-RESOURCE-TAIL-AND-SERVER-AB.md).
