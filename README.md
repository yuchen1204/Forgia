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
The legacy [experimental Create 6.0.10 adapter](adapters/create/README.md) contains whole-network preflight
and original v1 transmission/water-wheel/millstone integration. The current canonical Level path also requires
an independent core body-closure proof; the old v1 ADMITTED decision cannot certify unknown Mod machines.
Those legacy bridge checks do not expand the current Native/Furnace cohort.

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

The next stage began with the [Modded Block Entity adaptation architecture investigation](region-threading/MODDED-BLOCK-ENTITY-ADAPTATION-ARCHITECTURE.md).
Additional Resource Ownership MSPT tuning is frozen. The earlier [first execution-contract migration](region-threading/BLOCK-ENTITY-EXECUTION-CONTRACT.md)
gave native adapter ticks and protected Furnace Waves a shared internal ticket/permit lifecycle and
explicit exclusion between a Region owner and subordinate resource work. At that checkpoint, auditing,
switches, serial fallback and commit remained in their original drivers, with no new Mod BE admission or
generic adapter registration protocol. Core1244, resource-on181, persistent199, actual unknown Furnace
Mixin1, independent Create16 and unknown Create Mixin1 pass; full-server performance remains unmeasured.

The [Canonical BE Coordinator design](region-threading/CANONICAL-BLOCK-ENTITY-COORDINATOR.md) began as a
documentation-only plan. [C1](region-threading/CANONICAL-BLOCK-ENTITY-C1-TESTS.md) recorded eight enabled
sequence/fence failures; [C2](region-threading/CANONICAL-BLOCK-ENTITY-C2-COLLECTION.md) fixed five by collecting
actual Level membership after pending merge/onLoad. The [C3 bridge audit](region-threading/CANONICAL-BLOCK-ENTITY-C3-NATIVE-BLOCKERS.md)
and [production effect inventory](region-threading/CANONICAL-BLOCK-ENTITY-C3A-NATIVE-EFFECT-INVENTORY.md)
located the remaining admission and effect-timing gaps. Those reports preserve their historical red results.

Production now has [minimum original-Hopper admission](region-threading/CANONICAL-BLOCK-ENTITY-C3A2-MINIMUM-NATIVE-ADMISSION.md),
[owned invocation commits](region-threading/CANONICAL-BLOCK-ENTITY-C3B-INVOCATION-EFFECT-BRIDGE.md),
a [canonical Native cursor](region-threading/CANONICAL-BLOCK-ENTITY-C3C-NATIVE-COORDINATOR.md), and
[Furnace integration into that same phase](region-threading/CANONICAL-BLOCK-ENTITY-C4-UNIFIED-FURNACE.md).
The actual Level path uses one active batch/kernel/Ticket set and cursor for Native, Furnace and complete
Serial Fences. Worker prefixes are contiguous, join and commit before Unknown, and re-prove the suffix.
Original cooling/disabled Hoppers and the existing protected furnace cohort each show real different-Region
overlap=2 in a mixed single-batch phase; unknown capability/callback/resource paths remain fully serial.
Genuine level-end effects retain their timing. All original C1 targets pass; full core1314 and each targeted
worker/resource setting248 pass with zero failures/errors/skips. Defaults, budgets and original expectations
are unchanged; C5/Create and additional MSPT tuning remain outside this migration.

[Earlier C4.1 lifecycle and installed-server soak validation](region-threading/CANONICAL-BLOCK-ENTITY-C41-LIFECYCLE-AND-SOAK.md)
fixes lifecycle self-waits before the cleanup monitor and publishes the same phase before Native adapter
preparation. Full core1328 and three targeted262 runs pass with original expectations retained. Actual installed
Minecraft1.21.1 runs include a60-minute core mixed soak, chunk unload/reload, saved-world new-JVM restarts,
and a10-minute raw Create load. Across85400 checked ticks/10322400 bodies,33 cleanup snapshots show zero
live invocation authority/batches; bounded idle Native scope fields are verified separately. Create transforms
remain conservatively serial, with no Adapter expansion. **The separate full GameTest gate remains non-green:**
old Worker-contract expectations fail and the extra all-slice attempt crashes in a test plant callback.
Those results and diagnostic ERROR/FATAL output are retained and classified; this is not an all-green
compatibility or performance claim. That historical checkpoint stopped with the GameTest gate non-green.

[C4.2 GameTest compatibility and regression](region-threading/CANONICAL-BLOCK-ENTITY-C42-GAMETEST-COMPATIBILITY.md)
updates the old driver fixtures to verify real Native/Furnace prefixes, complete Serial Fences, original-body
counts and notifications. Two full unfiltered parallel profiles, Persistent/resources ON and Fixed/OFF,
each pass all176registered required GameTests with exit0 and no crash. The existing-plant growth regression
also passes with the bridge off; the direction crash came from an invalid occlusion check in test soil.
The opt-in `:tests:runCanonicalGameTestServer` profile enables the POI/entity/chunk prerequisites and proves
25chunks/13BEs/9entities are reconstructed, with original saved state/tickets/UUIDs intact. Current Villager
POI and feature-flag tests observe actual Worker/pack behavior. An asynchronous reference-writer race in a
POI JUnit fixture is fixed by joining both IO futures before comparing the files. Full core1328 and three
related329 runs pass, with original canonical assertions retained. Framework XML still lists4Not Processed
entries, separately documented; the report keeps all failed attempts and legacy test-Mod diagnostic errors.
Production source/patches/admission/defaults/budgets/API are unchanged. That C4.2 checkpoint stopped before
C5/Create protocol work, performance tuning or repeated long installed soak.

[C6 closure and writer-boundary investigation](region-threading/MOD-CLOSURE-LINKAGE-AND-WRITER-BOUNDARY.md)
continues from the published [first target audit](region-threading/FIRST-TRUSTED-MOD-EXECUTION-CLOSURE.md).
A private JVM linkage experiment observes the actual original furniture ticker in its real LevelChunk
binding. A controlled external writer still updates the original source while a canonical Serial Permit
is paused. Core writer exclusion, Closure Verifier, trusted Manifest and Mod Claims remain unimplemented;
**zero Mod Worker authority**, C6.3–C6.5 incomplete. New Core1400/1400 and two private observer+original-Mod
full profiles179/179required each pass, retaining4NotProcessed. The normal shipped test suite is unchanged;
the two added audit tests and optional observer/bootstrap bridge remain local and are not shipped.

[C5.1–C5.5 Core execution proof readiness](region-threading/CORE-MOD-EXECUTION-PROOF-READINESS.md) adds
an independent binding/lifecycle refusal gate on the original kernel Ticket. It rechecks the exact source
and registration after adapter callbacks; refused work remains a complete Serial Fence with no replay.
There is no positive Mod closure backend or adapter-declaration-to-Claims converter yet. Existing
Native/Furnace authority is retained. The report separates actual final tests, existing resource reservation
coverage and design-only prerequisites for a first Worker-enabled Dedicated Adapter. Final Core1400/1400,
three related419/419 configurations and two complete parallel GameTest suites177/177required each pass,
retaining four framework NotProcessed entries per full suite. No MSPT claim follows.

The earlier [C5 General Mod Adapter architecture](region-threading/GENERAL-MOD-ADAPTER-ARCHITECTURE.md) adds a
Java-only request API, server-scoped registration/retirement and one short-lived session attached to the
original canonical phase. Execution/resource/dependency/lifecycle descriptions and Dedicated audit metadata
use the existing registration transaction and total128bound; v1 API major and signatures stay compatible.
C5 has no generic Worker proof backend, so every C5 target remains a complete Serial Fence. Inspection runs
inside its original serial Permit at the actual cursor after physical join/necessary commit, then the full
original ticker runs once; declarations never mint Claims/Lease/Permit or choose pools/commit. Cleanup and
callback failures retain exactly-once semantics and clear borrowed subjects and temporary resources. Existing
Native Hopper/Furnace proofs and grants are preserved. No concrete Create/Mekanism/AE2 adapter, broader
Modded BE Worker admission, default/budget change, Bootstrap rewrite or MSPT optimization is included.
The earlier report records its C5 checkpoint tests: full core1375/1375, three related394/394 configurations and two
unfiltered full parallel GameTest profiles with177/177required passed, exit0/noCrash. Real registered Mod
requests keep unknown shared callbacks serial while Native overlap2 persists; mixed Native/Furnace overlap
is2/2 in one batch. All1328C4.2 and original1244JUnit cases rerun/pass. The four framework Not Processed
entries and retained failed probes remain explicit, alongside existing adapter/Mixin compatibility checks.
The new20class API package compiles with only JDK/annotations; the60class facade excludes runtime authority.
C5 stops at this foundation; no installed-server or whole-tick performance claim is added.

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
