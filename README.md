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
| **Block entities** | Furnace/hopper family admitted on workers with a per-chunk admission cache | at parity; interior-only (a hopper reads its neighbour) |
| **Redstone** | Cross-region updates converge in the **same tick** through a fixpoint | 98 layouts, 5 880 tick comparisons, identical to vanilla |
| **Cross-region reads** | Seqlock chunk versions; a stale read re-runs the region that made it | 0 torn reads, 0 stale takes |
| **Global state** | Explicit global phase + bounded cross-region mailbox | weather/time writes from a region fail closed |
| **Failures** | A worker tick failure is reported by the server thread, never built on the worker; a tick that reaches a chunk the lease does not hold is truncated once and its class moves to the server thread | barrier wait 200 ms → **1.25 ms** |

Correctness is the acceptance criterion throughout: the POI manager, the redstone components and the chunk
random tick are compared against **real vanilla implementations**, tick by tick, not against expectations.

## What is not done yet

Forgia is not a finished Folia replacement. The honest list:

- **Light loads do not win yet.** A 2048-hopper line runs at 3.7–4.0 ms against 3.3 ms inline and 4096
  furnaces at 2.7–3.3 ms against 2.6 ms. The worker body itself costs about 2.5× the inline cost per ticker,
  and the persistent-pool work that removes the submission cost is off by default because it costs the
  entity slice its dynamic balancing; the fix is a work-stealing dispatcher, not a global switch.
- **The production soak is at parity, not ahead.** With everything on it lands at or below the inline mspt
  (median ratio 0.983, p50 5.60 ms against 6.22 ms), but a realistic world does not have enough parallel work
  to gain more than that at 3-chunk regions.
- **Block entities still require the interior rule**, so ~92 % of their ticks stay on the server thread: a
  hopper reads the container one block away and a foreign block-entity read answers null.
- **Structures are not region-aware.** A piston that moves blocks across a border, and Create-style
  contraptions, still run on the server thread.
- **Cross-region game events (sculk) are not regionized.**
- **Players are not regionized.** Teleport, portals and player entities stay on the server thread.
- **No long-run or multiplayer validation yet**, and **no mod-pack validation yet** — a 170-mod pack is
  prepared for that stage.
- **Region size is static.** Dynamic regions (split/merge by load) and continuous region ticking (a tick clock
  instead of a per-tick barrier) are designed but not built.

The full remaining plan is in [region-threading/STATUS.md](region-threading/STATUS.md#roadmap).
The experimental public mod integration API and compile-only facade are described in
[the parallel API guide](region-threading/MOD-PARALLEL-API.md). Broader preparation and parallel-spawn filter
contracts are proposed in [the API RFC](region-threading/MOD-PARALLEL-API-RFC.md); those later stages are not yet implemented.

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
