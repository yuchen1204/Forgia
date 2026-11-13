# Forgia

**A regionized, multithreaded Minecraft server for NeoForge 1.21.1.**

Forgia ports [Folia](https://github.com/PaperMC/Folia)'s region model into NeoForge natively: the world is
partitioned into regions, each region owns its chunks, entities, block entities and points of interest, and
the regions tick in parallel on worker threads. It is a NeoForge fork, not a plugin platform — scheduling and
ownership are exposed through native NeoForge API, and mods keep running as mods.

> **Project status: independent.** Forgia started as a fork of an earlier Forgia port. That upstream stopped
> updating, so this repository is now the continuation: it tracks NeoForge 21.1.x as its base, but the
> regionized runtime is developed here. See [region-threading/STATUS.md](region-threading/STATUS.md) for what works, what is
> measured, and what is still missing.

---

## What works today

Everything below is implemented, tested and measured on this repository. All region threading is **off by
default** and every slice fails closed: work that cannot be proven safe runs on the server thread and is
counted.

| Area | What it does | Measured |
|---|---|---|
| **Entity ticks** | Regions tick their own entities; the server thread no longer walks the entity list | **2.5–4.5×** on 1024 mobs (4 workers) |
| **Chunk ticks** | Random ticks, ice/snow, thunder on the region that owns the chunk, including chunks on a region border | 1.24–1.27× (100 % of dispatched chunks on workers) |
| **Natural spawning** | Per-region spawn bodies against the vanilla level-wide mob cap | spawn slice 4.6 → 2.8–3.9 ms |
| **Points of interest** | Region-partitioned storage with published snapshots; queries identical to vanilla | villagers **2.1–2.4×**, zombies **3.4×** |
| **Block entities** | Furnace/hopper family with a per-chunk admission cache; furnace-family boundary chunks can use border propagation when neighbors are loaded | historical parity; new boundary admission not benchmarked |
| **Redstone** | Cross-region updates converge in the **same tick** through a fixpoint | 98 layouts, 5 880 tick comparisons, identical to vanilla |
| **Cross-region reads** | Seqlock chunk versions; a stale read re-runs the region that made it | 0 torn reads, 0 stale takes |
| **Cross-region mod operations** | Public two-owner block entity operations, bounded sided Container transfers and explicit task outcomes | experimental; operations serialize after workers return |
| **Scheduled ticks** | Vanilla lamp callbacks in a loaded, contained inert footprint; other phases retain the live serial queue | experimental; dispatch/completion/refusal/timing diagnostics |
| **Global state** | Explicit global phase + bounded cross-region mailbox | weather/time writes from a region fail closed |
| **Failures** | A worker tick failure is reported by the server thread, never built on the worker; a tick that reaches a chunk the lease does not hold is truncated once and its class moves to the server thread | barrier wait 200 ms → **1.25 ms** |

Correctness is the acceptance criterion throughout: the POI manager, the redstone components and the chunk
random tick are compared against **real vanilla implementations**, tick by tick, not against expectations.

## What is not done yet

Forgia is not a finished Folia replacement. The honest list:

- **Light loads do not win yet.** A 2048-hopper line runs at 3.7–4.0 ms against 3.3 ms inline and 4096
  furnaces at 2.7–3.3 ms against 2.6 ms. The worker body itself costs about 2.5× the inline cost per ticker,
  and the persistent pool remains off by default pending the complete workload matrix. The
  [work-stealing dispatcher](region-threading/WORK-STEALING-DISPATCHER.md) now batches submissions and
  steals unstarted task ranges; its dispatch-only measurements do not establish a whole-server speedup.
- **The production soak is at parity, not ahead.** With everything on it lands at or below the inline mspt
  (median ratio 0.983, p50 5.60 ms against 6.22 ms), but a realistic world does not have enough parallel work
  to gain more than that at 3-chunk regions.
- **Hoppers and pack-admitted machines still require the interior rule**: they can read and mutate live
  neighboring containers. Furnace-family boundary chunks can now run with border propagation enabled and
  loaded neighbors; mixed chunks still keep whole-chunk fallback. See the
  [block entity implementation and next steps](region-threading/BLOCK-ENTITY-PARALLELISM.md).
- **Structures are not region-aware.** A piston that moves blocks across a border, and Create-style
  contraptions, still run on the server thread.
- **Cross-region game events (sculk) are not regionized.**
- **Players are not regionized.** Teleport, portals and player entities stay on the server thread.
- **No long-run or multiplayer validation yet**, and **no mod-pack validation yet** — a 170-mod pack is
  prepared for that stage.
- **Region size is static.** Dynamic regions (split/merge by load) and continuous region ticking (a tick clock
  instead of a per-tick barrier) are designed but not built.

The full remaining plan is in [region-threading/STATUS.md](region-threading/STATUS.md#roadmap).
The complete Forgia API inventory, including public types, method signatures, schedulers, operation handles,
compatibility declarations and configuration fields, is in [the API reference](region-threading/API-REFERENCE.md).
The experimental public mod integration API and compile-only facade are described in
[the parallel API guide](region-threading/MOD-PARALLEL-API.md). Complete cross-region operations and
scheduled callback admission/metrics are documented in [the operation guide](region-threading/CROSS-REGION-OPERATIONS.md). Broader preparation and parallel-spawn filter
contracts are proposed in [the API RFC](region-threading/MOD-PARALLEL-API-RFC.md); those later stages are not yet implemented.
An [experimental MobSpawnController adapter](adapters/mobspawncontroller/README.md) provides frozen rules and
verified admission for 1.2.3. The ordered nearby/chance experiment is **opt-in and off by default**:
local pack validation found truncated work and no proven speedup. Default natural spawning stays serial;
unsupported world filters, multipliers and unverified native hooks also retain the complete serial plan.
An [experimental Create 6.0.10 adapter](adapters/create/README.md) runs audited steady transmission,
water-wheel and millstone ticks on native workers after whole-network preflight; topology changes and
processing callbacks keep their complete serial ticks. It requires the new block-entity admission capability.

## Configuration

Forgia's region-threading settings live in **`config/forgia.toml`**, separate from `neoforge-server.toml`.
All enable switches remain **`false` by default**. Switching to upstream NeoForge leaves `forgia.toml`
untouched; switching back to Forgia reads the saved settings.

On the first start, if `forgia.toml` is missing, Forgia copies only its own keys from `neoforge-server.toml`
before the NeoForge config loader corrects that file. If upstream already removed those keys, the saved
`neoforge-server.forgia.toml` side file is also recognized. Existing `forgia.toml` always wins. The same
migration applies to a world's `serverconfig` directory, whose `forgia.toml` overrides the instance file
under the normal SERVER-config rules. Legacy source files are not changed by migration. If neither legacy
file retains the settings, Forgia uses its default-off configuration; previously erased values cannot be recovered.
Change engine switches while the server is stopped; they take effect on the next start.

A server that wants the full stack sets:

```toml
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingEntityTicks = true            # selects region-owned entity storage
enableRegionThreadingBlockEntityTicks = true
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true                    # implied by entity ticks
enableRegionThreadingBorderUpdates = true
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 4
```

`regionThreadingWorkerThreads = 0` means "one less than the CPU count". Entity ticks, spawning and POI
require the fixed layout; everything else degrades to the vanilla path when its requirement is missing.

A separate, default-off `enableRegionThreadingFurnaceWorkWaves` experiment executes verified burning
vanilla furnace-family bodies in resource waves, including independent instances inside one chunk/region.
Unknown/fuel/notification operations are complete serial fences; other BE types are also serial in this mode.
See [the exact admission, ownership and validation limits](region-threading/FURNACE-WORK-WAVES.md) before
using it. This does not claim a general wave engine or a measured server speedup. The follow-up
[cost analysis and optimization plan](region-threading/BLOCK-ENTITY-OPTIMIZATION-PLAN.md) separates the
verified execution path from the remaining performance work and its acceptance criteria.

The default-off `enableRegionThreadingBlockEntityResources` option layers
[general resource identities, READ/WRITE claims, nonblocking leases and scopes](region-threading/GENERAL-BLOCK-ENTITY-RESOURCE-OWNERSHIP.md)
under the existing Region exclusion and migrates Furnace waves as the first audited consumer.
It does not admit arbitrary container/capability/mod callbacks or enable cross-region worker transactions;
large proof footprints initially had substantial allocation/planning costs. The
[`.58` optimization](region-threading/BLOCK-ENTITY-RESOURCE-OWNERSHIP-OPTIMIZATION.md) shares fresh READ
publications, separates execution planning from diagnostics and reuses immutable permission tables;
paired mechanism measurements do not establish a whole-server speedup or change the default-off policy.
The [tail-latency investigation and installed-server A/B](region-threading/BLOCK-ENTITY-RESOURCE-TAIL-AND-SERVER-AB.md)
separates blocking JIT compilation in the old probe from full-tick costs on the frozen `.58` binary.
The resource-on layer increased mean MSPT in35/36matrix pairs and both independent long-window repeats;
it remains default-off, with no performance evidence supporting broader BE admission.
The [`.59` hot-path cost reduction](region-threading/BLOCK-ENTITY-RESOURCE-HOT-PATH-REDUCTION.md) replaces
node-based proof tables and per-unit planner expansion with immutable arrays and call-local identity tables.
Two installed-server build pairs confirm lower allocation with the resource layer active; dynamic proofs
and admission remain intact. This reduces the existing layer's cost and does not establish a reason to enable it by default.
The [mixed-workload validation](region-threading/BLOCK-ENTITY-RESOURCE-MIXED-VALIDATION.md) checks the frozen
`.59` implementation with other serial block entities, normal world ticks and dynamic state changes;
its real-server resource-on/off evidence is distinct from the old/new implementation cost comparison.
The bounded30-minute pair passes full-state checks but increases mean MSPT18.8% and allocation82.5%
with the resource layer on. The option remains off by default, and BE admission limits stay unchanged.
The [`.60` source cost attribution and optimization](region-threading/BLOCK-ENTITY-RESOURCE-COST-ATTRIBUTION.md)
shares unchanged immutable local descriptions within a phase and reuses cleared identity-table capacity,
while retaining every fresh audit and validator. Four reversed build/condition pairs reduce the measured
ON-minus-OFF allocation increment by35.1–36.6%; whole-tick latency remains inconsistent, with mean/p95
improving in only1/4build pairs and regressing in the10-minute ON/OFF check. This is an allocation reduction,
not a validated server-latency improvement or broader BE admission.

Diagnostics: `/neoforge regionthreading status` prints the per-region MSPT/TPS, every slice's worker and
fallback counters, the border-update and foreign-read counters, and the reason each refusal happened. Nothing
is silently dropped.

The Folia 1.21.1 source comparison, current interaction API and remaining ownership/teleport work are documented
in [region-threading/FOLIA-REFERENCE.md](region-threading/FOLIA-REFERENCE.md). The incident-by-incident
comparison and broader repair priorities are in [region-threading/RECENT-BUG-CLASSES.md](region-threading/RECENT-BUG-CLASSES.md).
Feature-preserving adaptation candidates and their behavioral limits are in
[region-threading/FOLIA-PRESERVING-ADAPTATIONS.md](region-threading/FOLIA-PRESERVING-ADAPTATIONS.md).
The latest bounded crash/logic audit and its confirmed unresolved blockers are in
[region-threading/CRASH-AND-LOGIC-AUDIT.md](region-threading/CRASH-AND-LOGIC-AUDIT.md).
Entity/machine partial-tick replay removal and the explicit ticket-backed owner completion contract are in
[region-threading/CHUNK-OWNER-OPERATIONS.md](region-threading/CHUNK-OWNER-OPERATIONS.md).
Whole mob/item pickup admission, conservation checks and its deferred timing contract are documented in
[region-threading/MOB-ITEM-PICKUP.md](region-threading/MOB-ITEM-PICKUP.md).
Worker lookup compatibility for loaded mod subworld chunks and Sable plot gaps is documented in
[region-threading/SABLE-LOADED-CHUNKS.md](region-threading/SABLE-LOADED-CHUNKS.md).

## Building

```
./gradlew --offline :neoforge:setup          # apply patches into projects/
./gradlew --offline build                    # build the loader
./gradlew --offline :tests:junitTest         # 676 unit tests
./gradlew --offline :tests:runGameTestServer # 166 in-game tests
```

JDK 21 is required. The repository is a patched-source NeoForge tree: vanilla sources live in
`projects/neoforge/src/main/java` (generated), the patch set in `patches/`, and the Forgia runtime in
`src/main/java/net/neoforged/neoforge/server/threading`.

Benchmarks and soaks:

```
./gradlew --offline :tests:runGameTestServer -PserverBenchmark=true \
    -PgameTestFilter=benchmarkEntity -PserverBenchmarkZombies=1024
./gradlew --offline :tests:runSoakGameTestServer        # 6000-tick production soak
./gradlew --offline :tests:runSoakGameTestServerReference
```

Measure with a low-pause collector (`-XX:+UseZGC -XX:+ZGenerational`): the default G1 on a 2 GiB heap spends
seconds in pauses on this workload and hides every effect.

## Architecture in one page

- **Regions** are a fixed, non-overlapping grid of N×N chunks (`regionThreadingFixedRegionChunks`). Each
  region has a lease; only the lease holder may tick its chunks, entities, block entities, spawns and POIs.
- **The server thread** builds the plan, leases every region, submits one task per region, waits at a barrier
  and runs the handoff: deferred section updates, removals, admissions, block updates, portal transitions,
  border updates and the propagation fixpoint.
- **Workers** own their region for the duration of a task and enumerate their own entities, tickers, chunks
  and POIs instead of receiving a per-unit list.
- **Everything cross-region is deferred**, never raced: neighbour updates, block writes, reads, POI queries,
  game events. Each has a bounded queue, a counter and a fallback.
- **Fail closed**: any work that cannot be proven safe — an unknown modded callback, a level without
  region-owned storage, a region that cannot be leased — runs on the server thread.

Deeper notes live next to the code (`src/main/java/net/neoforged/neoforge/server/threading/region/*.java`)
and in the task records under `.pi/tasks/`, which document the design and the evidence for each milestone.

## Licence and lineage

Forgia is a fork of NeoForge (LGPL-2.1) and of the earlier Forgia port; the regionized runtime added here is
under the same licence. Upstream NeoForge attribution is kept in the source headers and in
[NOTICE](NOTICE) where present. This repository no longer tracks the upstream Forgia project, which stopped
updating.
