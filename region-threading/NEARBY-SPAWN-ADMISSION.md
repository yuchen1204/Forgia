# Nearby spawn admission and ordered finalization

Status: **native-loop integration implemented**, exposed through plan-level `Ready(snapshot, true)` and
used by MobSpawnController adapter 0.3.0 for the supported nearby/chance subset. Unsupported settings
retain the complete native serial plan before core cap construction. Live deployment is not part of this work.

**Delivery decision:** adapter 0.3.0 does not claim natural spawning by default. The local pack produced
truncated spawn work and no proven performance benefit; see [measurement limits and results](MOBSPAWNCONTROLLER-LOCAL-RESULTS.md).
Only explicit experimental `-Dforgia.mscOrderedSpawning=true` activates the claim. The integration described
below establishes mechanisms, not full-pack acceptance.

## Upstream semantics

MobSpawnController 1.2.3 was inspected without copying its implementation into this repository.
Its nearby filter counts LivingEntity instances of the same exact EntityType, excluding the candidate,
inside a block-sized AABB inflated in all three axes. Inclusion uses strict bounding-box intersection:
face contact alone does not intersect; cube corners and large entities with origins outside can count.
There is no health/age/persistence/spawn-reason filter in that query. Admission requires count < maxNearby;
zero rejects. Different candidates have separate windows, not one fixed region quota.

The nearby branch runs after chance and other world reads. A region-clipped query or a snapshot of only
initial entities cannot replace it: pending admissions, cancelled joins, removals and secondary spawns
change the answer. A synchronous child finalizer also must not count an unadmitted parent as existing.

The final protocol preserves the original evaluator and its RNG consumption. Worker selection stops
before finalization; original rule evaluation and real admission execute in one ordered server transaction.
The next transaction observes a confirmed world state, including cancellation. This does not promise the
same global candidate/RNG sequence as vanilla: cross-group order is submitted transaction order.

## Native integration

1. `ParallelApi.Ready` retains its single-argument constructor and adds `orderedFinalization`. Only plan
   preparation may request it; changing modes during per-unit preparation is an error. Registration can
   negotiate `ORDERED_SPAWN_FINALIZATION` through `Registrar.capabilities()` without publishing a partial
   runtime. A plan rejected by any provider keeps the native serial state/caps.
2. `RegionSpawnPlan` carries the mode. Startup reserves REGION_OWNED storage for requested spawning even
   when entity worker ticks are off and an adapter has not yet replaced its temporary spawn restriction.
   This does not enable a slice, override an administrator switch, or permit dynamic layouts.
3. `RegionTickBatch` owns `RegionSpawnAdmissionBarrier` for the opted-in batch. Actual running groups enroll
   before mailbox delivery/world reads and retire after their body. Unstarted groups are not parties.
   A rejection before an inline group starts still releases its region lease. Rejected inline failures do
   not stop remaining leased groups from being submitted or strand an open setup permit.
4. Native category/position loops and chunk boundaries poll safe points. Pending transactions keep new
   groups outside the active set. The nonblocking server pump waits for all enrolled groups to park while
   the normal batch wait continues servicing ordinary main-thread handoffs.
5. With peers parked, the server publishes prior deferred admissions/removals, block updates and tick
   effects, then runs `NaturalSpawner.finalizeSpawnForRegion`: original finalize, real passenger admission,
   afterSpawn and cluster/group-limit callbacks. The original default callsite remains in the nonordered
   branch. Workers receive group data and control flags; they do not continue callbacks on admitted entities.
6. A continuation carries the already-started unit's immutable prepared data and the same per-chunk RNG
   object. No preparation or replay occurs in SERVER_SERVICE. Ordinary query handoffs receive no borrowed
   snapshot. Unit/server/level/game-time checks reject retired continuations.
7. Thread-pool inline rejection pumps ordinary work while reaching the same parked phase. Nested server
   finalization executes inline and preserves native parent/child visibility. Exceptions, interruption,
   shutdown and partial passenger joins retain their actual outcomes; an operation with side effects is
   never retried or relabelled as an unattempted cancellation.

## Adapter boundary

RuleSnapshots copies mutable maps deeply; NaturalSpawnSettings values are upstream immutable records.
Supported records equal the upstream defaults in every field except chance, maxNearby, nearbyRadius,
spawnTypes and excludedSpawnTypes. Non-finite permitted numeric values are refused. Any unsupported
entry, including secondary-only settings, rejects the whole plan before cap construction.

The original FinalizeSpawn listener stays at its existing EventBus position. The natural-settings field
read is redirected to the frozen map; the evaluator itself is not copied or replaced. Applicable world
rules on a worker are rejected, never answered with an empty world. Attribute effects remain at their
original position and execute once. The existing empty-structure-selector short circuit remains.

The upstream ACTIVE_LEVEL ThreadLocal is saved/restored around both spawnForChunk and the server
continuation, including nested/exceptional paths. Supported configuration multipliers are one, so prepared
boost/cap reads do not consult or write the mutable upstream cache. Other multipliers retain serial caps.

Ordered mode also checks actual MixinMerged provenance on NaturalSpawner. Own hooks and the pinned
MSC boost hooks are supported. Additional inspected selection hooks are ByePregen 1.1.2.1 and YUNG Better
Nether Fortresses 1.21.1-NeoForge-3.1.5. Unknown origins/versions use
`forgia_mobspawncontroller:unverified_spawn_hooks` and remain serial. This does not certify arbitrary
ASM/coremods or unrelated third-party event listeners.

## Internal admission accounting

`NearbySpawnReservations` remains a detached, plan-owned accounting helper with atomic reservations,
commit/cancel, geometry checks and close semantics. `DeferredEntityAdmissionScope` transfers exactly-once
receipts into real deferred riding-graph admissions. ADMITTED/REJECTED/known ABANDONED outcomes commit
or release accounting. FAILED may already have entered storage; UNOBSERVED may have followed another
admission path. Both invalidate the view. Queue callbacks run outside queue locks; cleanup restores
ThreadLocals and continues after observer failures.

These helpers are independently tested. The adapter's ordered path uses the original live server query,
not speculative reservations. Receipts alone cannot make a candidate rejected by a later-cancelled
reservation succeed without replay, which is why complete finalization/admission ordering is required.

## Validation

- Scheduler tests: simultaneous requests, cancellation before next check, parked peers, ordinary handoff
  progress, group retirement, new entrants, inline rejection, nested parent visibility, FIFO, failures,
  shutdown, interruption and prevention of recursive waits before a parked phase.
- Real core worker/storage test: a join event cancels the first entity, the next candidate sees its absence,
  joins successfully, and neither event is replayed. Per-member receipts separately cover partial graphs,
  observer errors, close, abandoned suffixes and a throw after storage already accepted an entity.
- Real target-mod integration: two actual region groups use original transformed MSC world evaluation,
  one-slot competition, first-join cancellation, frozen rules after hot update, identical carried RNG,
  correct main-thread context, attribute effects, unsupported-setting fallback before cap construction,
  and empty selectors. The test world controls entity storage inputs; it is not a full-pack gameplay claim.
- Full-pack local runs must separately prove actual natural entities, advancing worker/ordered-finalization
  counts, no new safety failures and clean shutdown. Missing values are errors, not zero. Performance
  evidence must include parked phases and publication costs, not only finalizer service nanoseconds.

`compat` reports `transactions` (attempts, including denial/cancellation) and `serverMs` (continuation body
only); tick timing is needed to measure the full cost. Structural/other world filters and multiplier/cap
parallelism remain unsupported. Network/multiplayer behavior and long-duration gameplay require their
own evidence; local tests must not be presented as those results.
