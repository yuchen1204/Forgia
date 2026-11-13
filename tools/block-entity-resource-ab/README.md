# Installed Resource Ownership A/B

This is a real installed Minecraft server fixture with pinned installer and universal hashes. Historical
commands default to the frozen production `.58` binary. It measures
incremental Resource Ownership overhead with **Furnace waves already enabled in both conditions**.
It is not a modpack/multiplayer claim or a comparison against vanilla/stock NeoForge.

The observer is an **external, read-only local JMX client** using the JVM's built-in local management
service, started before warmup. The native100-slot Minecraft tick ring and standard
JVM MXBeans provide full server tick duration, CPU, GC, heap and server/worker allocation. No helper mod,
Mixin, bytecode instrumentation agent or new admission is installed by the observer. The production build
under test is pinned explicitly. Complete
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
The binary JFR remains authoritative for full stacks. Execution/allocation samples additionally retain up to
24 method names for initial-versus-fresh catalog/capture, phase planning and reservation attribution.
Those caller categories are sampled evidence, not non-overlapping wall timers or exact allocation bytes.
The analyzer refuses a whole JSON expansion over50MB.
A small diagnostic JSON from `jfr print --json` is also supported for parser cross-checks. Never infer
CPU time or exact bytes from execution/allocation sample weights.

## Compare builds

A new candidate must provide all three of `--version`, `--installer-sha256` and `--universal-sha256`, and an
independent `--installation` with that exact core. The historical `.58` defaults remain enforced; selecting
another version does not disable verification. Keep the resource flag, VM, workload and observation
protocol identical across builds. `--conditions on` measures both builds with the resource layer active;
use another pair with `--conditions off` as a control. Reverse build launch order in separate fresh roots
for an independent JVM repeat. Keep profiled pairs separate.

After both runs save and exit normally:

```sh
python3 tools/block-entity-resource-ab/compare_builds.py \
  /absolute/path/to/before-run-root /absolute/path/to/after-run-root \
  tmp/build-comparison.json
```

The comparer verifies complete per-window NBT hashes, body/lease/notification/output counts and matching
workload parameters, exports all native tick values, and computes allocation rates and phase times per
exact gameTick. It refuses missing or duplicate windows. `export_results.py --unpaired` explicitly exports
a single resource condition without making an ON/OFF equivalence claim. Do not pool independent JVM
repeats or profiled/unprofiled tick samples. See the [hot-path cost reduction](../../region-threading/BLOCK-ENTITY-RESOURCE-HOT-PATH-REDUCTION.md).

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
- `ticks`, output items, original body and lease deltas cover the exact configured gameTick window.
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

## Mixed workload validation

`mixed_workload.py` uses the same pinned binaries, private-world controller, exact scheduled freeze and
external native-ring/JMX observer. It adds64vanilla furnace-family machines,32hoppers in closed loops,
8brewing stands,8chests,8daylight sensors,8active comparator neighbours,8redstone clocks/lamps and32normal
main-thread ageable entities. Only existing audited furnace bodies enter workers; all other BE operations
remain complete serial fences. Normal scheduled/random/entity world ticks remain on their existing paths.
The three selected vanilla recipes are explicitly pinned to200ticks by a vanilla datapack, including
`minecraft:baked_potato_from_smoking`. Full BE inventories/progress/recipe-use state, deterministic entity
fields, redstone checks and exact ordinary tick counters are checked independently of performance.

```sh
python3 -u tools/block-entity-resource-ab/mixed_workload.py \
  --runs tmp/be-resource-mixed/steady \
  --installation /absolute/path/to/private-installed-59 \
  --version 21.1.250.59-beta \
  --installer-sha256 a8af404408d99d4a3bc37267bcf829cc5a6c2637cd2fd8bf0f572018beac2fd4 \
  --universal-sha256 772c6cff4dc2c83033a5465af753830a5b795703fa0a3b7b69c0c5866a42e25d \
  --eula /absolute/path/to/already-accepted/eula.txt \
  --java "$JAVA_HOME/bin/java" --classes tmp/be-resource-mixed/metrics-classes \
  --layouts chunks regions --conditions off on --rounds 2 \
  --warmup-ticks 1200 --window-ticks 600 --windows 2
python3 tools/block-entity-resource-ab/export_mixed.py \
  tmp/be-resource-mixed/steady tmp/be-resource-mixed/steady-export.json
```

Compile`JvmMetrics.java`as above before any performance runs. Mixed runs also accept
`--jfr /absolute/path/to/settings.jfc`: recorder startup and another200ticks are discarded before the
first measured window. Manifest and per-window `profiled` flags must agree, and the exporter refuses
pooling profiled with unprofiled windows. Epoch bounds allow the same streaming JFR analyzer to clip
sampled costs to the actual active measurement interval.

For serial-fence attribution, `--fence-groups 8` registers the same64furnaces and32Hoppers as eight groups
of eight machines followed by their existing four-Hopper loop. All positions, inputs, recipes, normal
world workload and complete-state/conservation oracles remain identical; canonical ticker order changes.
The normal default is `--fence-groups 0`. Keep these workloads separate, measure actual wave counters,
and do not infer the cost of many waves from the ordinary fixture's many serial BE ticks.

`--mode soak --layouts regions --rounds 1
--window-ticks 1200 --windows 30` runs30minutes of exact active gameTicks per condition, plus startup/warmup
and quiescent checkpoints. Checkpoints replenish furnace/brewing input and reset current stacks outside
the active timing interval; hopper state, entities, recipe-use counts and redstone clock remain live.
This is a bounded checkpointed soak, not an uninterrupted multiplayer/modpack production claim.

`--mode dynamic --layouts regions --rounds 1` is separate correctness evidence: scheduled legal
main-thread inventory/component edits and removal/replacement, completed recipe reloads with altered
cooking time, and actual chunk unload/reload. A vanilla`perf`report audits chunk-holder absence and is
explicitly excluded from performance comparisons. Fixture entities retain their UUIDs across reload.
No unsafe asynchronous mutation is introduced or reclassified as supported. Export full independent
runs only after normal save/exit; failed/pilot roots remain separate and never become accepted cells.

A separate`mixed_pipeline.py`correctness-only run replaces8isolated machines with actual input
Hopper→Furnace→output Hopper→chest lines, ordered to exercise a serial container tick before its furnace.
It uses the same`--runs/--installation/--version/--installer-sha256/--universal-sha256/--eula/--java/--classes`
arguments. Each condition executes two1200tick windows after preparation; full state,128tickableBE
invocations per tick, pipeline ore/ingot conservation and actual extraction into sink chests are checked.
It does not publish performance samples or broaden Hopper admission.

See the [mixed validation report](../../region-threading/BLOCK-ENTITY-RESOURCE-MIXED-VALIDATION.md).
The [`.60` cost-attribution report](../../region-threading/BLOCK-ENTITY-RESOURCE-COST-ATTRIBUTION.md) keeps
old/new resource-on costs distinct from each build's ON/OFF increment. Reproduce its standard mixed
matrix using the same command above with these pins and an independent installation:

```sh
--version 21.1.250.60-beta \
--installer-sha256 9f0b2bb19bf9840e88828b221e3103cce5c0a5e85de4ca35a4bb0b556c652741 \
--universal-sha256 ec49ce19a10ae93769478e1a13cf14d38c042216b6bd6e7c16321b2ce831c6d4
```

Run `.59` and `.60` in reversed build order on the independent repeat, keep every complete window,
and export each root separately. Use `--fence-groups 8` for the separate eight-wave control;
`--mode soak --layouts regions --rounds 1 --window-ticks 1200 --windows 10` for the ten-minute check;
`--mode dynamic` and `mixed_pipeline.py` for the existing complete correctness regressions.
The asynchronous unload/reload diagnostic is checked per-side for holder absence, UUID persistence,
outputs/conservation and exact total tickable bodies. Different IO histories can change before-state
and native/serial route counts: keep those differences explicitly; never label them full-pair equality.
These paths do not introduce asynchronous inventory mutation or expand BE admission.

Do not upload runtime directories: they contain worlds, properties/credentials, logs, JFR and jars.
To stop gracefully, create `STOP` in that run root or signal the Python controller. It stops after a
complete RCON exchange and closes its own connection. Do not race a second RCON controller's `stop`
against an outstanding request: Vanilla shutdown can wait for a client waiting on the server thread.
Only the explicitly exported performance records, tools/tests and interpretation belong in the repository.
See [investigation and limits](../../region-threading/BLOCK-ENTITY-RESOURCE-TAIL-AND-SERVER-AB.md).
