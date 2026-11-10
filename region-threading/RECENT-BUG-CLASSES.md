# Recent Forgia bug classes and the corresponding Folia mechanisms

This audit compares the recent Forgia fixes with **Folia `dev/1.21.1` at
`2e7bc0721af95196c85500c7bb136aeea0bc12ce`**, checked out at
`/home/yc/Minecraft/Folia-reference-1.21.1`. It is a source comparison, not a Folia runtime reproduction of
our modpack. The reference contains Folia patches on top of Paper/Moonrise; absence of an exact fix here
means it was not identified in this inspected patch stack, not proof that it cannot exist in Paper or later Folia.
No Folia implementation body is copied into Forgia.

The table separates **the same defect with a directly corresponding code path**, **the same problem class
solved by a different architecture**, and **platform-specific failures without an identified equivalent**.
A similar method name alone is insufficient evidence. Source evidence is indexed below.

## Main result

Most recent failures fall into six recurring classes:

1. **Wrong owner:** a live/cached reference is available, but the current lease does not authorize its mutation.
2. **Wrong state scope:** a formerly single-threaded world/global scratch object is shared by concurrent workers.
3. **Wrong lifecycle:** construction, admission, removal, riding graphs and pending operations are conflated.
4. **Wrong loading assumption:** a call which looks like a read can load terrain or publish server-owned storage.
5. **Incomplete continuation:** a new parallel/deferred path fails to preserve ordering, return values or the
   rest of a multi-step operation.
6. **Mod/platform contract:** transformed methods and mod event semantics differ from the vanilla/Paper model.

Folia does not make arbitrary references and callbacks thread-safe. Its strongest common pattern is to keep
state with its owner, check access, and schedule whole operations with explicit lifetime handling. It also
changes or disables some behavior. Forgia cannot copy those tradeoffs silently while promising existing
NeoForge/mod behavior.

## Incident-by-incident comparison

| Forgia incident / fix | Class and why it failed | What the pinned Folia source shows | Applicability to Forgia |
|---|---|---|---|
| Shark attacks pufferfish; death pose reaches `setPosRaw` (`8b3333810`) | Wrong owner. Attacker's tick can reach target health, pose, position, loot and mod override effects through a retained AI target. The crash guard is downstream of the first side effect. | **Direct corresponding ownership policy:** `Mob.getTarget()` clears targets which are removed or not owned; `Projectile.getOwner()` filters foreign references [S1,S2]. Region membership, not thread name, determines ownership [S3]. This does not prove every modded Brain target is automatically covered. | Forgia instead keeps the target and moves the entire virtual melee attack at the three audited AI call sites to a server barrier. That is a different ordering policy. Reuse strict interaction admission for more whole operations; do not move only `hurt()` or replay the source tick. |
| Ender pearl teleports player on worker (.37; current local pearl class policy) | Wrong owner / incomplete continuation. Pinning the player's own tick does not stop a projectile callback from mutating its connection, dismount state and health. | **Direct corresponding pearl implementation:** captures destination, schedules owner task, calls async teleport and completes effects on the teleported entity [S2]. | Keep current conservative whole-pearl server policy until real NeoForge impact events, connection completion, target loading and recovery are covered. Owner-operation API and gateway boundary are prerequisites, not an async teleport implementation. |
| Mounted player ticks through boat/Create seat (`5469fc9ef`, `1fe3b52d1`) | Wrong owner / riding graph. Dispatching each top-level tick-list entry misses recursive passenger ticks. | **Same problem, different architecture:** local player/entity/connection ownership; passenger tick uses region entity membership and checks ownership again after tick; teleport handles a passenger tree [S4,S5]. Folia also has a separate disconnect vehicle-position fix [S6], which is not the same bug as our worker admission. | Continue testing the full riding graph at admission and recursive tick boundaries. Do not infer that a safe vehicle means all passengers are safe. Our player-on-main policy is not Folia's player-region model. |
| UUID miss crashes persistent anger (polar bear); global scans/range queries/player list unsafe (`900d3f0d1`, `2b24ad4fc`, `defe92ab9`, `59a5021f8`) | Wrong state scope / query contract. A vanilla level getter may expose a world-wide mutable index, and a missing local answer is not authority to query it live. | **Architectural correspondence:** `RegionizedWorldData` holds local entities/players, entity tick list and connection set; ServerLevel's players storage is made copy-on-write and many tick consumers use `getLocalPlayers()` [S4,S7]. No exact polar-bear null-UUID fallback fix identified in this patch stack. | Keep LOCAL/PUBLISHED/TRUNCATED query accounting, bounded published player data and no live global fallback. A published player reference is still not permission to modify a player. Folia local results and Forgia one-publication-old snapshots are different contracts. |
| Removed bee finishes `aiStep` and attempts movement (`d7d1b1600`) | Wrong lifecycle. Logical removal is not always the end of the currently executing call stack; field changes and section publication have different validity. | **Direct corresponding lifecycle rule:** TickThread permits region membership and certain removed/pending entity cases; lookup removes region membership only after full removal callbacks [S3]. It does not grant all removed entities to every thread. | Preserve narrow lease ownership and skip obsolete section/chunk publication for a removed owned entity. Do not broaden the grant to a removed entity from another lease. |
| Create deployer fake player constructor calls `setUUID` (`c7b051247`) | Wrong lifecycle plus platform-specific constructor. Not-yet-added entity identity initialization must not rebind the world's admission ledger. | **Lifecycle analogy:** Folia distinguishes null callback/not-added entities from world-owned entities, with special player/global ownership rules [S3]. **No direct Create fake-player or Forgia identity-ledger fix** exists in the inspected source. | Retain our added-to-level ledger gate and constructor tests. Do not copy Folia's player exception as permission to run arbitrary NeoForge FakePlayer methods on a worker. |
| `finalizeSpawn` re-adds self; Primal creates jockey during spawn (`c278c8477`, `43fa18d95`) | Wrong lifecycle / missing admission domain. A mod may spawn from the spawn/chunk slice, not just the entity slice. Self re-add and an already-added passenger are different cases. | **Architectural correspondence:** region-owned entity add/tick lists and region task routing [S4,S8]; constructor riding events suppressed for not-added passengers [S5]. **No exact Primal spawn or generic deferred-add fix** identified. NaturalSpawner patch in this checkout mostly adjusts region tick time [S9]. | A complete admission API must cover each slice and entity graph. Our server-owned section manager requires barrier admission; copying Folia region data calls would not make it worker-safe. Preserve vanilla duplicate/cancellation behavior, no blind retries. |
| Rider recorded first, then new vehicle graph rejected due to overlapping rider (`3d6fda5d4`) | Wrong lifecycle / incomplete continuation. Two deferred records share an entity; treating the second graph as wholly new drops its new vehicle. | **Graph-level analogy:** Folia detaches/restores full passenger trees during teleport, and performs identity/lifetime-aware placement [S5]. **No exact overlap-record bug/fix** identified. | Our identity-based shared-member deduplication is specific to our batch protocol. Keep exact-once admission tests, not a wholesale copy of teleport graph code. |
| `droppedDeferredEntityAdditions` mistaken for lost entities (`3d6fda5d4`) | Observability/contract error. Cancelled join events, duplicate UUIDs, partial graphs and actual discarded operations share a legacy total. | No equivalent interpretation fix identified; Folia's different storage pipeline does not validate our counter meaning. | Report records versus entities, distinguish reasons and preserve cancellation. Metrics alone do not prove conservation loss. |
| Neighbor cascade after border write requests unloaded FULL chunk (`05e5e156e`) | Wrong loading assumption. Applying a local block write can expand its physics into unavailable terrain. | **Direct corresponding failure class:** patch 0010 explicitly prevents neighbor/shape/rail/comparator updates in non-owned/unloaded chunks, by skipping some of them [S10]. | We chose detached continuation with preserved stack/direction/budget and server loading, not suppression. Keep our tested redstone semantics; Folia's suppression is an explicit behavior change. |
| Mobs cannot move: loaded biome wrapper treated as versionless, `getChunkNow` returns null (`5469fc9ef`) | Wrong loading/view abstraction. Loaded terrain can be represented by an ImposterProtoChunk; a non-loading lookup must not return artificial empty terrain on a legitimate worker. | **Related, not exact:** Moonrise replaces the chunk path; Folia syncLoad checks owned chunk coordinates [S11]. Patch 0011 separately blocks some worldgen-thread block-entity reads through ImposterProtoChunk; that is not our wrapper-version bug. | Retain wrapper normalization, non-loading worker terrain lookup and real pathfinding tests. Never interpret this as permission for worker force-load or unrestricted foreign reads. |
| Redstone `shouldSignal`, update stack and callback scratch state shared across workers | Wrong state scope. Mutable singleton flags and level-wide collectors assume one call stack. | **Direct corresponding patterns:** shouldSignal/turbo and neighbor updater move to RegionizedWorldData; scratch flags such as block-entity IGNORE_TILE_UPDATES move to ThreadLocal [S7,S12]. | Apply region storage for persistent state and scoped/restored thread-local state for temporary recursion state. A static concurrent map is not a substitute for the correct lifetime. |
| `LevelTicks.willTickThisTick` false while collected callbacks still pending (`6ba22ca89`) | Incomplete continuation/order bookkeeping. Our dispatcher cleared collected-tick membership before callback begin. | **Architectural correspondence only:** Folia has per-region block/fluid LevelTicks with merge/split and clock offsets [S7]. **No exact early-set-clear fix** identified in inspected patches. | Keep our beginTick protocol and ordered production comparisons. Per-region clocks/collections do not prove global scheduled callback interleaving equivalence; the production seam remains gated. |
| Connector-frame `BlockDropsEvent.getDrops()` null (`ac654de52` containment; local capture repair) | Shared core capture plus platform contract. A later regression reproduced null lists without Connector: Block's static single-slot capture is overwritten by concurrent or nested loot. | **State-scope analogy:** captureDrops/captureBlockStates are region data; CraftEventFactory scratch override is ThreadLocal [S7,S12]. **No Connector/NeoForge event equivalent.** | The local engine repair uses thread-confined nested scopes with exception cleanup; see [BLOCK-DROP-CAPTURE.md](BLOCK-DROP-CAPTURE.md). It preserves mutable/cancelled event semantics. The installed compatibility lock/null guard remains containment, not listener safety or item-conservation proof. |
| `Ingredient.getStackingIds` IllegalMonitorStateException under transformed recipe stack (`bcc6f922e`) | Platform contract plus shared lazy cache. The failure involved our monitor path and mod-transformed code, not simply an entity-region border. | **No direct Ingredient/Polymorph fix identified.** Folia does synchronize selected truly global maps, but that does not imply a universal monitor solution [S13]. | Keep local-build/volatile-publication semantics and test cache invalidation separately. Do not attribute the transformer/JVM failure mechanism beyond the captured evidence. |
| Every block-entity chunk falls back; per-ticker lease/context cost (`199328379`, `8b3333810`) | Admission granularity/performance. One unproven ticker used to serialize an entire region; then safe main-thread fallback repeatedly acquired/released leases. | **Architectural analogy:** Folia's region loop owns its tickers and block events inside RegionizedWorldData [S7]. **No direct Forgia allowlist/fallback-cache fix** identified. | Per-chunk admission and quiescent fallback lease reuse are local adaptations. Do not equate optimizing fallback with making unknown Create machines safe on workers. |
| Moving contraption touches many blocks/entities across fixed borders | Wrong owner / whole-operation footprint, with mod-specific structure behavior. | **Architectural analogy:** Folia's region buffers and merge invariants keep adjacent terrain under one ticking owner; movement bounds are checked [S3,S14]. **No Create compatibility fix.** | Keep moving structures on main until there is an audited multi-region operation/claim protocol. Folia invariants are not provided by a fixed grid or a bigger region alone. |
| Sable plot-gap adapter enumerated a mod API class (`crash-2026-11-09_17.01.56-server.txt`) | **Reflection on a class you do not own.** `Class.getMethod` builds the class's whole member array, and building it resolves every signature; the runtime dist cleaner refuses a signature that mentions a client-only class, so the *binding* threw and took the tick with it. The adapter was written to avoid a crash and became one. | No Folia equivalent: Folia does not reflect into a mod's API class to reach a mod's chunk storage. | Resolve one member by name and descriptor (`MethodHandles`), and treat every unavailable binding as "adapter off". Never enumerate members of a class outside this repository; a failure to bind is a configuration, not an exception. See `SABLE-LOADED-CHUNKS.md`. |
| Worker entity tick reads a chunk the lease does not hold (`crash-2026-11-09_11.45.26`, `11.51.15`, `2026-11-08_03.33.25`, `2026-11-10_05.59.18`) | **Wrong loading assumption, fatal by vanilla's own rule.** `Level#getChunk` loads, a worker may not, and vanilla answers a throwing entity tick with `ReportedException: Ticking entity` - so a mod's unbounded read (a bullet raycast, a structure lookup in a spawn hook, a collision against a moving sub-level) stopped the server four times. | No Folia equivalent to copy: Folia contains a failing region tick instead of stopping the process. | The tick is truncated and counted (`truncatedEntityTicks`), never replayed - its body already wrote entity fields - and the runtime class is declared server-thread-only with reason `chunk_unavailable`, so the loss happens once per class. A provider failure is still reported; only a chunk refusal is truncated. See `STATUS.md` known limitations. |

## Source evidence index

All ranges below refer to
[`0003-Threaded-Regions.patch`](https://github.com/PaperMC/Folia/blob/2e7bc0721af95196c85500c7bb136aeea0bc12ce/patches/server/0003-Threaded-Regions.patch)
unless another patch is named. Line numbers are from the pinned reference file, not vanilla line numbers.

- **S1:** `Mob.getTarget/getTargetRaw`, lines 14973–15003. The checked getter clears a foreign/removed target;
  raw lookup is separated so setting a target can compare the real stored reference.
- **S2:** `Projectile.getOwner/getOwnerRaw`, 15881–15955; `ThrownEnderpearl.attemptTeleport/onHit`,
  15991–16112. Saved destination, owner scheduler, live callback entity, teleport completion and endermite
  placement are distinct steps.
- **S3:** `TickThread.isTickThreadFor(Entity/position/bounds)`, 86–263; Moonrise EntityLookup removal
  callback, 264–304. Pending/removed/local membership is distinguished and membership is removed after hooks.
- **S4:** `RegionizedWorldData` entity/player/connection methods, 3330–3465; ServerLevel passenger tick,
  11365–11413. Local entity membership and connection ownership are separate from Java thread identity.
- **S5:** Entity not-added mount/dismount game-event guards, 13929–13949; `EntityTreeNode` and
  `teleportAsync`, 14009–14540, including same-region tree/tracker restoration and asynchronous placement.
- **S6:** [`0016-Sync-vehicle-position-to-player-position-on-player-d.patch`](https://github.com/PaperMC/Folia/blob/2e7bc0721af95196c85500c7bb136aeea0bc12ce/patches/server/0016-Sync-vehicle-position-to-player-position-on-player-d.patch).
  Related passenger synchronization issue, not a direct fix for our worker-recursive-player admission.
- **S7:** `RegionizedWorldData` fields/construction and tickers, 3190–3555; ServerLevel constructor/tick-list
  changes, 11070–11096; captureDrops and neighbor updater changes, 11463–11535. Persistent tick state is regional.
- **S8:** `RegionizedTaskQueue`, 2083 onward: location tasks, queue tickets and lifetime/reference handling;
  EntityScheduler retirement query at 1182–1202. These do not provide a NeoForge PersistentEntitySectionManager
  worker admission API by themselves.
- **S9:** NaturalSpawner patch, 17179–17193. Region clock timing adjustment; no Primal finalizeSpawn hook.
- **S10:** [`0010-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch`](https://github.com/PaperMC/Folia/blob/2e7bc0721af95196c85500c7bb136aeea0bc12ce/patches/server/0010-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch),
  including CollectingNeighborUpdater Multi/Shape/Simple updates. Its introductory text explicitly calls this
  block-update suppression.
- **S11:** ServerChunkCache.syncLoad, 10651–10704;
  [`0011-Block-reading-in-world-tile-entities-on-worldgen-thr.patch`](https://github.com/PaperMC/Folia/blob/2e7bc0721af95196c85500c7bb136aeea0bc12ce/patches/server/0011-Block-reading-in-world-tile-entities-on-worldgen-thr.patch).
  Different chunk system and a distinct worldgen/block-entity issue, not the same getChunkNow/wrapper defect.
- **S12:** RedstoneWireTurbo shouldSignal, 954–976; BlockEntity.IGNORE_TILE_UPDATES, 17809–17842;
  CraftEventFactory.sourceBlockOverrideRT near 20232. Thread-local scratch and region-owned persistent data
  have different lifetimes.
- **S13:** SavedData/MapIndex/MapItemSavedData synchronization around 18860–19040 and
  [`0014-Synchronize-PaperPermissionManager.patch`](https://github.com/PaperMC/Folia/blob/2e7bc0721af95196c85500c7bb136aeea0bc12ce/patches/server/0014-Synchronize-PaperPermissionManager.patch).
  Locks are used selectively for global state, not to serialize every world callback.
- **S14:** [Folia region logic](https://docs.papermc.io/folia/reference/region-logic/) and
  [overview](https://docs.papermc.io/folia/reference/overview/): buffered ownership, no adjacent independent
  ticking regions, eventual merge/split, own clocks. Compare with Forgia's fixed grid and server barrier.

## What to change systemically next

### 1. Expand pre-side-effect interaction admission, not just late guards

The recent melee failure was detected at a position mutation after damage had begun. The pearl failed at the
connection teleport after earlier impact work. Move complete operations at the first cross-owner boundary.
Use strict interactions when both entities must remain valid and owner operations when the source is consumed.
Do not generalize by returning successful `hurt()` results before damage or by replaying the attacker tick.

Next families to inspect together: projectile deflection/hit, owner heal, attached fireworks and player impulses,
area effects, inventory pickup and riding/dismount. Each needs its own operation/result semantics but should
share admission, outcome accounting, cancellation and retirement. A query or snapshot which yields a player
must never authorize a player mutation.

### 2. Audit scope and state for every worker-reachable callback

List mutable static fields, singleton blocks, level capture maps, event scratch state, listener-owned state,
recipe caches and mod fake-player access. Classify each as region-persistent, scope-temporary, published immutable
or global synchronized/server-owned. Test two regions interleaving and nested/reentrant calls; one thread's
exception must not clear another's collector. The current compatibility mod is not a substitute for this audit.

### 3. Finish a general effect pipeline across slice and lifecycle boundaries

Entity addition is already reachable from entity ticks, chunk ticks, spawn callbacks and block-entity callbacks.
The same is true of many interactions. Unsupported contexts must reject before side effects with an explicit
outcome. Extend bounded queues only with their drain, unload, shutdown, save and rejection semantics, not merely
by accepting a Runnable from more threads. Keep item/entity graph conservation checks and distinguish normal
vanilla cancellation from unintended loss.

### 4. Preserve loaded-view and physics contracts

Never substitute air for an unloaded block read merely to stop crashes. Own-region loaded reads, published
foreign reads, requests requiring load, and writes need distinct contracts. Reuse the continuation tests for
physics, and keep the wrapper/version and pathfinding terrain regressions. Folia's update suppression is not
an automatic replacement for our existing cross-border fixpoint behavior.

### 5. Keep performance and correctness evidence separate

A busy region, a serial mod ticker and a shared lock can all lower TPS without a new guard failure. The prior
live profile showed a concentrated entity batch and serial block-entity work; neither the number of warnings
nor Folia's default sizing proves a faster layout. Benchmark worker budgets, fixed region sizes and fallback
wrapper cost independently. Do not weaken guards or blindly whitelist machines to improve counters.

## Checklist for each newly adapted operation

- Define whose state it reads/mutates, including recursive callbacks and captured references.
- Check or schedule before the first side effect; preserve caller return/cooldown/event semantics explicitly.
- Decide construction, added, removed, passenger, changed-world and retired-identity cases separately.
- Define immutable request input and the live parameters allowed at execution.
- Test queue rejection/capacity/cancellation and unload/shutdown/save termination; no unbounded wait between owners.
- Prove accepted effects run at most once; do not silently drop inventory/entity effects or replay partial ticks.
- Retain precise guards and LOCAL/PUBLISHED/DEFERRED/REFUSED/outcome diagnostics.
- Verify both deterministic behavior and mod event compatibility, then measure throughput separately.

This audit does not mark the uncovered projectile, area-effect, inventory, fake-player or mod-listener paths as
fixed. The first shared-callback-state repair, with its separate tests and limits, is documented in
[BLOCK-DROP-CAPTURE.md](BLOCK-DROP-CAPTURE.md).
