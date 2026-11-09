# Map registry and ordered I/O concurrency

This follows the named RNG and crash audit work. Reference: Folia dev/1.21.1 at
`2e7bc0721af95196c85500c7bb136aeea0bc12ce`, core patch's MapIndex/SavedData/ServerChunkCache/Projectile
sections. Forgia's implementation is written against NeoForge's source and APIs. No Paper/Bukkit/Moonrise
implementation body is imported.

## Map ID allocation

MapIndex's mutable `usedAuxIds` is one world-level counter map. Its read/increment/put operation must be
indivisible: a concurrent map alone would not make that compound operation atomic. Allocation and NBT
encoding now coordinate on the counter map's monitor. Dirty access on this class uses the same monitor.
Loading populates an unpublished new object as before. Method signatures, the existing default counter -1,
integer numbering, other numeric keys and the NBT schema are retained. The preexisting int exhaustion
behavior is unchanged; this is not a new wider map-ID format.

This is the small synchronization mechanism identified in Folia's MapIndex patch, applied independently
without changing the larger map update behavior. In particular no own-terrain-only map refresh filtering is
introduced.

A pre-fix eight-thread test requested 8000 IDs but observed only 4291 unique values in that run. That number
is scheduling-dependent; the defect is the non-atomic counter operation, not that specific duplicate count.
A second controlled test paused allocation while a save still encoded the old counter. Both tests fail
before the patch. With the patch the counter/save boundary is coordinated and a reload after quiescence
continues after all 8000 issued IDs. Existing extra numeric keys and ordinary sequential numbering remain.

## One registry instance, not just a locked counter

ServerLevel's map methods all use the **overworld** DimensionDataStorage, including calls made in another
dimension. That cache is a plain HashMap and computeIfAbsent is a get/create/set sequence. Locking MapIndex
would not prevent two worker callers from racing first-load and obtaining different counter instances.

Native batch workers now hand off the complete `getFreeMapId`, `getMapData` and `setMapData` registry
operations to the server thread through the existing serviced-batch transport. The server thread invokes
the original methods and code paths; the caller receives the result or original failure before continuing
on its original worker. The unmodified signatures and vanilla bodies remain in place. Main-thread calls
follow the original code directly. No entity tick is replayed or made serial to perform these small registry
operations. Both dimensions still resolve to the original overworld registry.

A native worker outside a serviced batch rejects before accessing the cache, rather than executing the
operation inline or recursing through the worker branch. A callback awaiting a batch service requires the
batch-aware barrier; ordinary main-thread task enqueueing would not provide that guarantee.

The returned MapItemSavedData remains a live value. Lookup does **not** authorize concurrent pixel, banner,
decoration or player-tracker mutation. Direct mod access to DimensionDataStorage bypasses these map entry
points and remains subject to its ownership requirements. This repair does not claim to make every global
SavedData registry or map update thread-safe. Player map updates remain on their existing main-thread path.

## Ordered I/O queue

NeoForge's IOUtilities used one unsynchronized mutable CompletableFuture chain. Concurrent submitters could
replace each other's successors. Waiting also unconditionally reset the tail after join, erasing tracking
of any successor appended during that wait. A failed predecessor made thenRunAsync skip all later tasks.
The pre-fix integration regression reproduced the last problem: the expected following write did not run.

IOUtilities now uses OrderedIOQueue:

- Reserve each successor under a short monitor so there is one order for accepted submissions.
- Invoke the executor and task outside the monitor. A task may append more work without deadlocking.
- Schedule a successor after its predecessor completes, including exceptional completion. Errors remain
  observable at await; they no longer skip independently accepted later writes.
- Waiting covers the prefix accepted before the call. Reset only if that exact prefix is still the tail;
  a concurrent successor remains tracked and ordered ahead of further submissions.
- Executor rejection completes that reserved slot exceptionally, allowing successors to run. Acknowledged
  failures do not poison the next save cycle. Reused exception instances never self-suppress.
- A task cannot await its own queue: reject that self-wait explicitly rather than deadlocking the I/O worker.

If new work is appended during a wait, an earlier failure may be reported again by the later prefix's await.
This is intentional: a prefix wait must not erase tracking of the longer prefix, and no failure is silently
lost. Error reporting remains exceptional; the queue does not retry tasks which may have partially written
or executed callbacks. The existing atomic file replacement behavior is retained.

The I/O queue change repairs a NeoForge-specific shared structure found during the audit. No equivalent
Folia queue implementation is asserted. Folia's applicable principle is choosing the right state owner and
preserving compound operations, not a claim that every such fix is present verbatim in Folia.

## Remaining persistence protocol work

The queue accepting a snapshot is **not** proof it reached disk. Generic SavedData still clears dirty after
serialization/submission and catches IOException by logging. A concurrent dirty mark can still be cleared,
and an I/O failure still needs a version-aware retry/acknowledgement protocol. The new queue only observes
exceptions which actually escape its Runnable. A caught IOException is not magically converted into a retry.

MapIndex and RandomSequences now provide class-specific coherent NBT encoding; arbitrary mod SavedData
needs its own owner/snapshot discipline. A volatile dirty field alone does not freeze a mod's mutable data.
Folia moves dirty-clear before serialization, but that alone cannot supply failure retry, consistent arbitrary
mod snapshots or a durability acknowledgement in this NeoForge pipeline. That broader repair remains separate.

## Missing-chunk loading and subsequent replay removal

Folia's syncLoad checks chunk ownership and schedules a load through Moonrise's chunk scheduler. Its projectile
changes can also stop movement before an unowned step; getOwner filters foreign owners and explicitly admits
behavior sacrifices. Those are not transparent replacements for Forgia's vanilla chunk system and NeoForge
live projectile events.

Forgia's existing main-thread computation transport runs while other groups may still hold leases. Moving a
load into it does not prove that all chunk publication, level/entity callbacks and regionizer lifecycle work
may safely run during that interval. A safe protocol must define ownership across load completion, tickets,
retirement/shutdown and continuation of the suspended operation. Do not implement synchronous chunk loading
by merely removing worker guards or assuming the thread name supplies quiescence.

The later follow-up [CHUNK-OWNER-OPERATIONS.md](CHUNK-OWNER-OPERATIONS.md) removes entity/block-entity
whole-tick replay and introduces an explicit ticket-backed synchronous owner completion stage. Arbitrary
mid-body mod accesses still require adaptation; the full async load protocol and other interaction gaps in
[CRASH-AND-LOGIC-AUDIT.md](CRASH-AND-LOGIC-AUDIT.md) remain incomplete. No projectile owner is nulled out to
hide those gaps.

## Verification results

The full local tree passed 906 JUnit tests with zero failures, errors or skips, plus formatting and build
checks. A fresh-world GameTest run passed all 169 required tests and exited successfully. The preexisting
auxiliary `testFeatureGating` diagnostic still failed separately. All 758 patches reapplied with zero rejects
and no patch-cache hit; forced regeneration was
byte-identical. The original I/O integration and counter tests failed before their fixes, and the two map
registry entry-point controls failed when their handoff statements were temporarily removed.

## Tests

- MapIndexConcurrencyTest: concurrent unique allocation/reload, controlled counter-versus-save exclusion,
  existing numeric keys/schema and sequential output.
- OrderedIOQueueTest: 4000 concurrently submitted tasks without overlap or loss; original/reused/distinct
  failures; a task appended while a prefix await is blocked; executor rejection; nested append without
  holding the queue monitor; rejection of self-await.
- IOUtilitiesWorkerTest: production IOUtilities continues later work after an injected failure and supports
  the next save cycle. The preexisting wait-completion test remains.
- RegionSelfDrivenEntityTickTest: two actual region workers allocate/publish/read maps via the original
  ServerLevel and DimensionDataStorage paths; registry access runs on the main thread and both entity ticks
  remain on workers. Handoff failure identity and rejection outside a serviced batch are checked too.

These checks are local correctness evidence, not a live-modpack soak or performance/TPS measurement.
