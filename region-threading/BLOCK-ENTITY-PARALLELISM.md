# Block entity parallelism and the next ownership protocols

This work covers block entity admission, cross-region operations and mod APIs, then scheduled ticks and
observability. The boundary furnace milestone below is followed by the implemented bounded operation and
scheduled callback protocols. The first implemented vanilla work-graph milestone is
[opt-in shadow resource observation and directed wave planning](BLOCK-ENTITY-WORK-GRAPH.md). A separately
gated [executed vanilla furnace-wave prototype](FURNACE-WORK-WAVES.md), default off, now adds instance-scoped
parallel original burning ticks inside a region; other types retain serial fences in that experimental mode.
The next default-off [general resource ownership foundation](GENERAL-BLOCK-ENTITY-RESOURCE-OWNERSHIP.md)
adds object identities, READ/WRITE claims, nonblocking leases and subordinate scopes to those furnace waves;
its generic planner can express shared resources, but first-phase production admission remains furnace-only.
The vanilla-first dependency-graph direction, including the reviewed Chorus
0.23.0 artifact and the ownership/order requirements before adopting waves, is recorded in
[the Chorus block-entity reference](CHORUS-BLOCK-ENTITY-REFERENCE.md); it is a design investigation, not an
implemented wave scheduler. Existing performance measurements predate these milestones; no new speedup
is claimed. See [the public protocols and examples](CROSS-REGION-OPERATIONS.md).

## Implemented: furnace-family boundary admission

The previous default admission required every allowed block entity to occupy an interior chunk. In a
fixed 3×3 region that meant only the center chunk could pass, even for a furnace whose tick only changes
its own inventory and block. The restrictions were shared with hoppers, whose tick really does need live
neighboring containers and entity queries.

The engine now distinguishes two worker admissions:

| Admission | Types | Required before the body |
|---|---|---|
| Interior | Furnace, blast furnace, smoker, hopper, explicitly pack-admitted types | Existing type, chunk, entity-load, binding, region lease and deferred-update checks |
| Boundary with border propagation | Built-in furnace, blast furnace and smoker only | All existing checks, border propagation enabled, and all eight neighboring chunks loaded with live region mappings |

The furnace-family body does not transfer items to a neighboring inventory. Its own block-state change,
neighbor notifications and comparator notifications use the existing region ownership checks, deferred
level publications and border fixpoint. The fixpoint runs after the worker groups return. It never reruns
the furnace body to repair a stale propagation read.

`enableRegionThreadingBlockEntityTicks` enables the slice; `enableRegionThreadingBorderUpdates` is also
required for boundary admission. Existing bridge/worker requirements still apply. The switches remain
off by default. No additional type is admitted by this change.

Safety details:

- Server-thread-only declarations take precedence over both admissions. Pack admission grants only the
  existing interior contract; a custom machine cannot acquire boundary access by adopting a furnace-like name.
- A chunk with any unproven ticker retains whole-chunk server fallback, including otherwise admitted tickers.
  This preserves the existing handling and ordering of mixed chunks.
- The per-chunk admission cache includes border propagation policy, region layout and ticker revision.
  Neighbor availability is checked each batch through the non-loading lookup and `LevelChunk.isLoaded()`;
  a FULL ticket alone does not prove its chunk finished loading. Availability is not stored as a lasting
  permission in the admission cache.
- A missing neighbor, missing live region mapping or unavailable propagation protocol selects the complete
  server body before it starts. A loaded but untracked neighbor cannot accept a border update: the fixpoint
  drops an update without a live target region. A missing chunk encountered after a body starts remains a
  reported failure. Inventory consumption,
  outputs and callbacks are never retried as a whole tick.
- This uses the existing furnace compatibility contract. Modded recipes, fuel callbacks, attachments and
  injected block behavior remain the mod/adapter's responsibility; this does not prove arbitrary callbacks
  thread-safe merely because the block entity reports a vanilla type.
- The existing border queue and fixpoint bounds still apply. Updates that exceed the bound retain the
  existing delayed propagation behavior; this does not promise unlimited same-tick convergence.

`/neoforge regionthreading compat` reports a separate count of boundary ticker invocations that returned
successfully on native workers. Executor rejection followed by inline execution does not increment it.
This is a ticker invocation count, not proof that a bound wrapper executed its underlying body or a measure
of saved CPU time. Fallback dispatches add `unloadedNeighborhood` and `untrackedNeighborhood`; the existing
`boundaryChunk` reason identifies types/positions without an admissible boundary protocol. Use interval
differences of cumulative counters. Opt-in block entity timing attribution also separates these preflight
fallbacks.

The tests use the real engine, region workers, admission snapshots and bound ticker wrappers. The new
furnace equivalence fixture uses real furnace-family tick bodies, inventories, recipes and comparators,
with the existing sparse level/redstone harness supplying world storage. The JUnit harness has no loaded
fuel data map, so a scoped pure fuel-event listener supplies coal's normal burn time while preserving the
real NeoForge callback and consumption path. The fixture compares inventories, block
states and pending scheduled ticks each tick at positive and negative X/Z borders. A separate real-server
GameTest forces the surrounding chunks, checks the production chunk's boundary admission, smelts one ore
with one fuel into one ingot, and requires a nonzero comparator output across the region border. Neither
fixture is a modpack soak or a throughput measurement.

Validation on the final implementation: **1052 JUnit tests, zero failures/errors/skips**; root and test
format/license checks and the core jar build passed. The focused real-server run passed **1/1 required
GameTest** (`boundaryFurnaceTicksAndNotifiesComparator`) with the boundary slice and propagation enabled.
The test runner restored the exact original local configuration afterwards. The unrelated existing
`testFeatureGating` auxiliary diagnostic still reports its disabled-feature assertion separately; the
filtered run is not a claim that every GameTest or startup diagnostic passed. Evidence is retained under
`tmp/be-boundary/` (`final-validation.log`, `final-junit-result.txt`, `gametest.log`, `gametest-result.txt`).

## Implemented: complete cross-region operations for mods

`ParallelApi.Server.blockEntities()` now exposes opaque instance references, typed two-owner operations,
explicit task outcomes and a bounded sided Container transfer. Only the exact native bound source ticker
can authorize a worker request. Its operation runs at the server completion point after every worker
returned and preceding deferred effects were published. Both live instances and chunks are revalidated;
replacement, unload, cancellation, service/engine shutdown and failures are explicit. Acceptance does not
promise success or rollback, and a failure never replays a partial transfer.

This supplies the operation boundary needed by adapters for pipes, energy or item networks. It does not
rewrite existing hopper/Create Java code or grant arbitrary machines worker admission. Interior hoppers
and explicitly declared mod tickers keep their established admission; adapters must convert the complete
cross-region action before any of its effects. Current two-owner operations serialize at batch completion;
parallel multi-owner leases, unbounded networks and automatic IItemHandler adaptation remain future work.

The public facade and mod example are in [CROSS-REGION-OPERATIONS.md](CROSS-REGION-OPERATIONS.md).

## Implemented: scheduled callback admission and observability

Production scheduled ticks now have a pure preflight before draining the live LevelTicks queue. The first
admitted profile is the real vanilla redstone-lamp callback with a loaded, fixed-region-contained radius-two
footprint made only of audited inert vanilla blocks. The whole selected phase must fit that profile and
the update budget. Different regions can then tick in parallel without sharing mutable state or advancing
the vanilla RNG. Unknown callbacks, mixed phases, fluids and unsafe footprints retain the original live
serial queue, priority/subtick order and queue mutation semantics.

Started scheduled callback failures are reported once without replay or silent successful truncation.
`ParallelApi.Server.scheduledTicks()` and `regionthreading compat` expose collected/admitted callbacks,
native/serial completions, failures, skips and serial reasons, preparation/worker/dispatch/completion time,
and exact p50/p95/p99 over a bounded 128-phase window. Whole block entity operation outcomes and timing
are reported separately. Detailed units, limits and acceptance scenarios are in the public protocol guide.

