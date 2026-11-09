# Folia mechanisms worth adapting without removing vanilla features

Reference: Folia `dev/1.21.1`, commit
[`2e7bc0721af95196c85500c7bb136aeea0bc12ce`](https://github.com/PaperMC/Folia/tree/2e7bc0721af95196c85500c7bb136aeea0bc12ce).
This continues [the incident comparison](RECENT-BUG-CLASSES.md) and [the general source audit](FOLIA-REFERENCE.md).
The sources were inspected, not built or benchmarked as a Folia server. No reference implementation body is
copied into Forgia. New candidate issues below are source-level risks, not confirmed live crashes.

## What preservation means

Preserving a feature, conserving entities/items, preserving an event's cancellation contract, preserving a
scheduled delay, and reproducing the exact single-threaded ordering/random assignment are different claims.
A lock may preserve all state updates while changing which of two regions consumes the next random value.
Independent region clocks can preserve a repeater's local delay while changing its order relative to another
region. A concurrent map prevents corruption but does not make a save or a compound operation atomic.

For Forgia the default contract should remain: keep the vanilla operation and NeoForge event behavior, do not
substitute empty/absent data or skip work merely to avoid a crash, explicitly document deferred timing, and
keep operations serial where a correct parallel protocol has not been implemented. Do not claim bit-for-bit
vanilla replay just because the resulting behavior is thread-safe. Existing Forgia per-group random streams,
published query snapshots and cross-border deferred effects already have their own documented timing limits.

## Candidates, evidence and acceptance criteria

The code references below are sections of Folia's pinned
[`0003-Threaded-Regions.patch`](https://github.com/PaperMC/Folia/blob/2e7bc0721af95196c85500c7bb136aeea0bc12ce/patches/server/0003-Threaded-Regions.patch).

### 1. Named random sequences used by loot — high priority, requires an ordering contract

**Evidence:** `RandomSequences`, lines 13690–13783: concurrent sequence map and synchronization of each
underlying random generator for the DirtyMarkingRandomSource methods.

**Forgia surface at the original audit:** generated `RandomSequences` held an Object2ObjectOpenHashMap and
unsynchronized generator wrappers. The subsequent named-stream repair is documented below. `LootContext.Builder.create` can select
`ServerLevel.getRandomSequence`, so protecting Block's captured item list does not protect this different
shared state. This is distinct from the region-aware `level.random` source.

**Adaptation:** retain named sequence identities, seed derivation, serialized state and draw algorithms.
Protect map creation/reset/defaults and generator state, and define save/reset synchronization with draws.
Do not merely replace the map and assume a multi-field random generator is safe. Locking each individual
primitive does not make an entire loot evaluation atomic, and methods returning a new generator/factory
need their own lifetime/ownership audit.

**Preservation boundary:** single-threaded draw sequences and save/reload continuation must remain identical.
With concurrent calls, the shared stream can advance safely but the value assigned to each block can depend
on scheduling. Exact per-block vanilla allocation requires explicit global ordering or whole-operation
serialization; reseeding independently per worker would change the stream instead of fixing it.

**Tests before implementation acceptance:** same-seed sequential output versus base code; concurrent same-ID
creation and draws; reset/default changes versus draws; fork/forkPositional and Gaussian state; save/reload
continuation; two real concurrent loot contexts; no lost dirty updates. Declare the deterministic ordering
policy before claiming loot equivalence.

### 2. SavedData dirty-state and consistent snapshot protocol — high priority

**Evidence:** `SavedData`, around 18859–18888: volatile dirty and moving dirty-clear before save, so a later
mutation is not erased by an unconditional clear at the end.

**Current Forgia surface:** generated SavedData uses a plain boolean and clears it after NBT creation and
submission through NeoForge IOUtilities. A concurrent mutation during that window could mark dirty and then
have its mark cleared. Normal quiescent saves may avoid that interleave; this inspection does not prove the
live pack has hit it, and mod SavedData implementations are not automatically thread-safe.

**Adaptation:** use a revision/snapshot acknowledgement protocol, or equivalent correctly ordered dirty
handling, so only the captured revision becomes clean. Keep dirty on serialization/write failure and preserve
mutations newer than the snapshot. Ensure that reading the mutable data into NBT itself is consistent; a
volatile flag is not a snapshot lock. Honor NeoForge's copy-before-I/O behavior and actual completion semantics.

**Preservation boundary:** NBT layout and vanilla contents need not change. Avoid moving mod save callbacks
under a broad engine lock; they can call back into world state or I/O. Do not directly copy Folia's different
Paper async-save method/signature, or claim its early-clear alone provides write-failure retry semantics.

**Tests:** deterministic mutation during save, failed serializer, failed writer, concurrent snapshots and
completion order, later mutation after a successful snapshot, clean shutdown/flush and reload. Require no
lost accepted update, without inventing a new crash-recovery durability guarantee beyond completed saves.

### 3. Map IDs and per-map data consistency — small IDs first, map terrain separately

**Evidence:** `MapIndex`, around 18889–18914: synchronize ID-map access for allocation and save;
`MapItemSavedData`, 18915 onward: synchronize operations on a particular map; `MapItem`, 16460–16525:
synchronize update against the same data but also restrict terrain access to loaded/owned chunks.

**Forgia surface at the original audit:** MapIndex allocation was get-plus-increment-plus-put on an unprotected
map. Allocation/encoding coordination and main-thread map registry access are now implemented; see the
follow-up below. MapItemSavedData still has mutable player/decorations/frame maps. Players currently stay
on the main thread, so some Folia concurrency scenarios are not active here; mod callers and save paths
still need an audit.

**Adaptation:** protect a whole map-ID allocation, not just the backing map operations. Preserve ID format,
monotonic progression and the saved counter. For actual map data, synchronize consistent copies/mutations
with the correct owner and define all exposed mutable fields, collections, packets and save paths. A few
synchronized methods are insufficient if mods can mutate exposed data outside that discipline.

**Preservation boundary:** do not import Folia's restriction on drawing non-owned terrain as if it were merely
a lock. It can change map refresh coverage. Keep existing rendering behavior on the server thread or introduce
a proven terrain snapshot/request path, including marker and packet ordering.

**Tests:** many concurrent allocations have no duplicates and no gaps beyond intentionally reserved IDs;
NBT roundtrip; map copy/lock/scale; player/frame/banner updates while saving; packet dirty rectangles and map
colors compared with a sequential reference. Revalidate mod/API access when changing fields or signatures.

### 4. Region data containers and scoped scratch — structural prevention, not a gameplay change

**Evidence:** `RegionizedData`, around 1381–1620: per-world checks, regional lifetime and explicit merge/split
callbacks; `RegionizedWorldData`, around 2852–3608: entity/chunk tick lists, neighbor updater and transient
capture state are associated with their owners. Initializer/merge callbacks may run under region locks and
must not block or perform arbitrary world work.

**Adaptation:** provide typed world/region storage whose access checks owner and lifecycle; use call scopes
with finally restoration for temporary recursion/capture state. Give retained regional data a real retirement
path and eventually explicit merge/split migration. Do not put persistent map/entity data in a worker ThreadLocal:
a worker can tick different regions and a region can move between workers.

**Preservation boundary:** storage isolation can preserve ordinary in-region behavior, but moving a previously
global counter into per-region copies changes its meaning. Classify each field before moving it. A world-global
mob cap, permission registry or named random stream must not be accidentally duplicated.

**Tests:** two regions interleaving on the same/different workers, same-thread nested callbacks, exception
restoration, wrong-world access, unload/reference release and merge/split if that policy is supported. The
new BlockDropCapture repair is one concrete scoped-scratch example; it is not a general fix for mod listeners.

### 5. Ticket-backed location tasks and nonblocking loading — feature-preserving only with a full protocol

**Evidence:** `RegionizedTaskQueue`, around 2083–2305: location-specific queues and reference-counted
TASK_QUEUE_TICKET acquisition/release; `ChunkTaskScheduler`, around 809–923: regional scheduling and an
explicit path avoiding ticket/schedule-lock acquisition to prevent deadlocks.

**Adaptation:** separate a queued location task from a terrain-load request. Define requested status, ticket
level, admission/cancellation, completion executor and ownership check at callback. Keep the task's chunk/region
lifetime until callback completion and release exactly once on all terminal paths. A queue ticket in Folia is
not, by itself, proof that a FULL chunk has already loaded; load status and region liveness are distinct.

**Preservation boundary:** waiting synchronously from one region for another can deadlock. A nonblocking
continuation can preserve the requested operation, but its completion is later; events expecting immediate
return values need a completion protocol. Never return air or silently discard the task on unloaded chunks.

**Tests:** unload during queued/load work, cancellation before/after start, shared tasks/ticket reference counts,
load failure, world close, shutdown and callbacks which reenter scheduling. No leaked ticket, no double callback,
no work on a retired region. Do not port Moonrise queue classes without NeoForge chunk-holder integration.

### 6. Entity movement/teleport transaction and shutdown reconciliation — feature preservation needs graph identity

**Evidence:** `Entity.teleportAsync` and EntityTreeNode, around 14009–14540; `RegionShutdownThread`,
1201–1380; `ServerLevel.PendingTeleport`, around 11040. Source transform, destination placement,
passenger/tracker restoration and pending-transfer completion are separate states. References may change when
non-player entities are recreated; completion callbacks must use the current entity, not an old capture.

**Adaptation:** keep a transaction identity and explicit original/current entity graph, destination ticket,
placement, tracker/connection acknowledgement, cancellation policy and save/close resolution. Preserve
inventory, UUID and passenger conservation. Decide how NeoForge travel/teleport event cancellation maps to
these states before mutating/removing the source. Delay global/player save until pending work is reconciled.

**Preservation boundary:** the mechanism can retain teleports, vehicles and portals without removing features,
but timing and cancellation are not automatically identical. An in-memory pending list does not guarantee
recovery from forced process death; that requires a separately tested persistence policy. Forgia currently
keeps player/pearl operations serial while this protocol is incomplete.

**Tests:** same/cross-world teleports with nested passengers and inventories; event cancellation/target changes;
disconnect; unloaded target; failure between each state; level unload/normal stop; reload and hard-kill behavior
only if promised. Keep original mod entry points/call shapes; do not copy Bukkit method replacements.

### 7. Atomic compound reservations — reuse the principle, preserve existing APIs

**Evidence:** `StructureStart`, 18783–18847: an atomic tryReference operation replaces separate check and
increment. This avoids two callers both passing the cap check. Folia also throws from old canBeReferenced /
addReference entry points in this pinned patch.

**Adaptation:** an atomic reservation can preserve the feature and prevent duplicate claims. Audit every
caller and rollback/cancellation path, and retain compatible old NeoForge entry points until deliberately
migrated. Applies to identity admission, mob-cap slots, task capacity and inventory transfers only where the
entire compound operation and rollback contract are defined.

**Preservation boundary:** copying the exceptions into existing methods would break callers/mods. AtomicInteger
alone does not make separate check-then-act callers atomic. Forgia already uses reservations in some queues
and spawn-cap paths; don't create a parallel competing reservation ledger.

**Tests:** deterministic last-slot race, cancellation/release, save/reload, old API callers and duplicate
identities. Verify semantics of maximum references and overflow rather than assuming every counter is unbounded.

### 8. Scheduler/watchdog observability — low gameplay impact, no automatic performance claim

**Evidence:** TickRegionScheduler around 5532 onward; separate
[`0019-Add-watchdog-thread.patch`](https://github.com/PaperMC/Folia/blob/2e7bc0721af95196c85500c7bb136aeea0bc12ce/patches/server/0019-Add-watchdog-thread.patch)
and region-profiler patch. Regional task/lifecycle progress and alive-thread evidence aid diagnosis.

**Adaptation:** distinguish queue delay, actual run/CPU time, worker span, server completion and blocked dependency;
attach region, operation ID and last phase to watchdog diagnostics. Forgia already has barrier attribution;
use it before adding another profiler or optimizing a perceived wait. Bound logging and samples.

**Preservation boundary:** diagnostic-only changes can preserve gameplay, but sampling, logging and locks can
perturb scheduling/performance. Do not lower safety checks, extend watchdog timeouts indefinitely or label
elapsed time as entity CPU cost. Test diagnostics disabled overhead and avoid unsafely inspecting live maps.

## Implementation follow-up

The named-stream concurrency repair, preserved behavior, tests and the separate generic SavedData write-ack
limitation are documented in [NAMED-RANDOM-SEQUENCES.md](NAMED-RANDOM-SEQUENCES.md). MapIndex compound
allocation/encoding, map registry ownership and the independently discovered NeoForge I/O queue repair are
covered in [MAP-AND-IO-CONCURRENCY.md](MAP-AND-IO-CONCURRENCY.md). These follow-ups do not claim a live fault,
deterministic cross-region loot assignment or completed map pixel/update synchronization.

## Do not import these as feature-preserving fixes

- Patch 0010's skipped unloaded/non-owned neighbor updates: avoids some crashes but suppresses physics.
- Foreign Mob targets/projectile owners/kill credit returned absent: may alter attacks, attribution, owner logic
  and loot/protection conditions. Our operation/snapshot protocols require separate result semantics.
- Entity.getTeam returning null, disabled scoreboard behavior or unsupported API exceptions: these remove or
  restrict behavior rather than make the original contract concurrent.
- Full independent-region clocks: preserve local relative deadlines only if every timestamp is migrated;
  redstone/global interleavings still differ. Do not enable production scheduled workers based on that analogy.
- A universal lock around event dispatch: state may be created before the lock, listeners may wait for other
  owners, and nested events/cancel return values still need correct scopes and ordering.
- Automatic admission of all mod machines/entities: Folia's Bukkit compatibility boundary does not establish
  NeoForge mod/mixin safety, and its nearby-region buffer is absent from our fixed layout.

## Recommended order for the next bounded work

1. **Named loot RandomSequences audit and deterministic regression:** a concrete remaining shared object on a
   worker-reachable path. Define the stream ordering contract before implementing per-generator synchronization.
2. **SavedData revision/snapshot and failure semantics**, followed by **map-ID allocation**: preserve data formats,
   acknowledge saves correctly, and avoid unnecessary synchronization of world callbacks.
3. **Typed region/scoped state** for the next audited captures, then **ticket-backed task loading** with explicit
   outcomes. Retain the already tested drop-capture and interaction mechanisms.
4. **Complete teleport transactions** and each remaining interaction family after their event/graph contracts are
   tested. Restore parallel work only when safe, rather than hiding failures with absent/empty answers.

These recommendations come from inspected code and current Forgia call paths. The named-stream and map-ID
access repairs have separate implementation follow-ups above. The other candidates remain unimplemented by
this audit; none is measured as a throughput improvement here.
