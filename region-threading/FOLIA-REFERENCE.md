# Folia 1.21.1 reference audit and Forgia follow-up

This audit uses a local read-only reference checkout at `/home/yc/Minecraft/Folia-reference-1.21.1`,
branch `dev/1.21.1`, commit [`2e7bc0721af95196c85500c7bb136aeea0bc12ce`](https://github.com/PaperMC/Folia/tree/2e7bc0721af95196c85500c7bb136aeea0bc12ce).
The branch matches Forgia's Minecraft version. It is not evidence of what every later Folia version does.
The repository is a patch stack on Paper; no Folia server was built or benchmarked in this audit.

Folia's patch set carries GPL-3.0 terms. This work references its design and writes Forgia-specific code;
no Folia/Paper implementation body is copied into NeoForge's source tree.

## What the source actually does

| Problem | Evidence in the pinned source | Implication for Forgia |
|---|---|---|
| A region is data ownership, not a worker thread name | `0003-Threaded-Regions.patch`, `TickThread` section, around lines 86–280; checks actual region/entity membership, world, and movement bounds | A cached entity reference, UUID answer or scheduler callback is not permission to mutate that entity. Keep guards at mutation boundaries. |
| An AI target can leave the source region | Same patch, `Mob.getTarget`, around 14975: clears targets which are removed or not owned | Forgia's three melee AI sites now defer whole attacks. Do not blindly copy target clearing: NeoForge mods may rely on target persistence and Brain memories. Other direct mod interactions remain unadapted. |
| Projectile owner lookup can expose a foreign live entity | Same patch, `Projectile.getOwner/getOwnerRaw`, around 15881–15955: ownership-filtered public owner lookup versus restricted raw lookup | Our projectile owner cache is still a reference hazard. Returning null everywhere could change attribution, protection checks and ownership behavior. Audit call sites before changing the global getter. |
| A pearl must act on its owner's execution context | Same patch, `ThrownEnderpearl`, around 15991–16112: capture destination, schedule owner operation, revalidate entity, call async teleport and finish effects in a completion callback | Our conservative pearl tick admission fix is safe but serializes flight too. Parallel flight needs an impact request and owner operation which do not keep reading the projectile after it moves. |
| Teleport is a multi-stage protocol | Same patch, `Entity.teleportAsync`, around 14313; [overview](https://docs.papermc.io/folia/reference/overview/) | Source removal/transform, destination loading/place, passenger restoration and completion belong to one protocol. Deferring only the packet listener's final teleport is insufficient. |
| Shutdown can interrupt teleport between worlds | Same patch, `RegionShutdownThread`, around 1200–1380; overview shutdown section | Pending transitions need an explicit terminal state or recovery placement before world/player save. A plain Runnable queue is not an adequate teleport journal. |
| Scheduled entity references can become invalid | Same patch, `EntityScheduler` around 1182 and pearl callback comments; overview `EntityScheduler` | Revalidate on execution; expose cancellation, retirement and failure rather than treating queued as completed. Never wait synchronously from one owner for another. |
| A queued region job may outlive the loaded chunk | Same patch, `RegionizedTaskQueue`, around 2083: chunk/tick tasks and task-queue ticket | Our generic region queue is not an async chunk-loader API. Adding load support requires ticket ownership and release semantics. |
| Neighbor physics can exceed loaded/owned terrain | [`0010-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch`](https://github.com/PaperMC/Folia/blob/2e7bc0721af95196c85500c7bb136aeea0bc12ce/patches/server/0010-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch) explicitly suppresses some updates | Our existing continuation hands off remaining updates rather than suppressing them. Preserve that tested contract; copying Folia suppression would change redstone behavior. |
| Fixed adjacent grids do not give Folia's isolation invariant | [region logic](https://docs.papermc.io/folia/reference/region-logic/) and overview: buffer, no adjacent ticking independent regions, eventual merge/split | Forgia fixed regions plus cross-border fixpoints/snapshots and one server barrier are materially different. Dynamic-region and independent-clock work cannot be solved by renaming the policy. |
| Region clocks, global state and shared scratch data need separate ownership | Same patch, `RegionizedWorldData`, `RegionizedData`, `LivingEntity.updateTicks`; README and `regiontodo.txt` | Independent clocks require timestamp conversion at merge/split. Scoreboards, raids, world loading, callbacks and mutable static work buffers need their own audit. ThreadLocal alone does not make entity/world data safe. |
| More worker threads need a CPU budget | Pinned README configuration section reserves cores for network, chunk I/O, generation and concurrent GC | Tune worker counts with measurements. A process using few cores may be waiting on one hot region or serial phase; increasing workers alone cannot fix that. |

The pinned README explicitly warns that most plugins need adaptation, lists broken APIs, and describes hard
thread checks as a correctness mechanism. Its todo file still includes async event completion, scoreboards,
redstone boundaries, scheduler overhead and `Projectile#getOwner`. It does not promise transparent compatibility.

## Behavior-preserving adaptation candidates

[FOLIA-PRESERVING-ADAPTATIONS.md](FOLIA-PRESERVING-ADAPTATIONS.md) separates data-isolation, small shared-state
synchronization, loading/teleport lifecycle and diagnostic mechanisms from Folia changes which filter results,
skip physics or disable APIs. It also distinguishes feature preservation from exact single-threaded ordering,
and lists acceptance tests and current Forgia surfaces for each candidate.

## Recent incidents by problem class

[RECENT-BUG-CLASSES.md](RECENT-BUG-CLASSES.md) compares the recent Forgia fixes individually with the pinned
Folia sources: cross-owner callbacks, shared state, construction/removal/riding lifecycles, chunk read/load
contracts, deferred ordering and mod-specific failures. It distinguishes directly corresponding code paths
from architectural analogies and cases with no identified equivalent. Use that broader map to select the next
work, rather than treating pearls as the only owner-interaction problem.

## First implemented follow-up: whole entity interactions with outcomes

Forgia now exposes an experimental `RegionThreading.entityInteractions()` API, using the engine's current
barrier model. This is an independently implemented building block, not a port of Folia's entity scheduler.

```java
EntityInteractionTask task = RegionThreadingManager.getOrThrow()
        .entityInteractions()
        .submit(source, target, (currentSource, currentTarget) -> {
            // Perform the whole operation here. Capture immutable request input, not other live entities.
            // Use callback parameters; do not assume that scheduling preserved selection-time entity state.
        });
```

- Entity-tick workers must own `source`; admission happens before changing either participant.
- Accepted callbacks run after that entity batch's workers finish, on the server thread. Same-level source and
  target are revalidated then; removal and dimension changes produce SKIPPED with a reason.
- A quiescent server-thread caller executes immediately, including completion of batches which the current
  server thread owns and whose workers have all returned. Main-thread service callbacks while workers remain
  active still reject. Async threads, workers outside entity batches and unowned sources are rejected. This
  version is not a general executor from arbitrary threads.
- Each batch admits at most 4096 interactions. Overflow returns REJECTED/QUEUE_FULL before any callback starts;
  cancelling a task does not replenish that batch's admission budget.
- QUEUED/RUNNING/COMPLETED/SKIPPED/CANCELLED/REJECTED/FAILED are observable. Cancellation succeeds only before
  execution; shutdown retires queued tasks; terminal tasks release entity/world/action references.
- Exceptions remain visible through normal entity-batch reporting, and later accepted work still drains.
- The melee admission hook now uses this common mechanism; existing local attacks retain their inline path.
- Status includes aggregate interaction outcomes alongside the existing melee and pearl policy diagnostics.

This deliberately does **not** promise an immediate `hurt()` boolean, a snapshot of weapons or target health,
exact vanilla ordering between different regions, async chunk loading, passenger transfers or asynchronous
teleport completion. It does not automatically adapt every projectile or mod event. Pearl ticks currently
remain on the server thread under `ender_pearl_owner_interaction` until those prerequisites are implemented.

## Ordered next milestones

The next source-level audit and source-consumed owner-operation contract are in
[PROJECTILE-OWNERSHIP.md](PROJECTILE-OWNERSHIP.md). `submitToOwner` now provides the admission-only source
lifetime described there; it does not enable parallel pearl flight or implement asynchronous teleportation.

1. **Interaction and state audit across recent bug classes:** use the incident matrix to audit projectile owner
   and hit callbacks, attached fireworks/player impulses, owner heal, area effects, riding/inventory paths, and
   shared event/capture/scratch state. Treat projectile references as one family, not the whole problem.
   Separate immutable identity/read data from mutable owner access, and prove attribution/protection behavior
   before making getters return null for foreign owners.
2. **Owner operation and event completion:** immutable pearl-impact request, owner scheduling/retirement,
   cancellation and event outcome, exactly-once damage/drop/discard, stable impact coordinates. Compare with
   NeoForge's current ender-pearl event behavior, including mod subclasses and gateways.
3. **Teleport transaction:** source/target ownership, destination ticketing/loading, passenger graph,
   disconnect/retirement, level unload, shutdown/save and restart recovery. Only then restore pearl worker flight.
4. **Other interaction families:** projectile deflection/hurt, area effects, explosions, passengers, tame/owner
   effects, and modded callbacks, one verified entry point at a time. Retain the original mutation guards.
5. **Global and regional data:** scoreboard, raid and world lifecycle APIs, callback concurrency and region clocks.
   Continue the separate region-size/worker-budget benchmarks rather than assuming Folia's sizing fits this pack.

Acceptance for each milestone: deterministic regression before/after, no replay of partially applied effects,
terminal outcome accounting, exact item/entity conservation where the operation promises it, removal/unload/
shutdown tests, full JUnit/required GameTests and a measured live or isolated comparison before throughput claims.
