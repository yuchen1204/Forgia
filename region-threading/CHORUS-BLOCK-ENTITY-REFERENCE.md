# Chorus block-entity reference and Forgia implementation direction

Status: reference investigation and implementation design. The first implementation milestone,
[shadow observation and directed wave planning](BLOCK-ENTITY-WORK-GRAPH.md), is integrated; a separately
opt-in [verified furnace-wave execution prototype](FURNACE-WORK-WAVES.md) now handles a narrow vanilla
operation family. A general dependency scheduler for arbitrary machines is **not implemented**. The active
objective is improving vanilla block-entity
parallelism before extending mod support. This investigation changes the focus from counting admitted
types to proving the independence of actual work. No runtime admission, engine switch or live server is
changed by this document.

## Evidence and scope

Reviewed the public site and the exact released mod artifact:

- [Engine](https://chorusmc.dev/engine/), especially conflict cartographer, block-entity engine and waves.
- [Configuration](https://chorusmc.dev/docs/): `beParallel`, `waves`, `waves.minCluster`, `carto`, `carto.rebuild`.
- [Roadmap](https://chorusmc.dev/roadmap/): the wave engine has not been deep-verified against real Create
  bases under load; bit-identical vanilla RNG output is not claimed.
- [Downloads](https://chorusmc.dev/downloads/) and
  [published checksums](https://chorusmc.dev/dl/SHA256SUMS).
- `Chorus-1.20.1-complete-0.23.0.jar`, 6,106,745 bytes, SHA-256
  `0dba6cb62bff1820b064b6c5c19320ea4550220ab68fc9d5c410e83270fa5796`, matches the published checksum.

The inspected build targets Minecraft 1.20.1/Fabric; Forgia targets 1.21.1/NeoForge. No source-repository
link was found on the inspected project pages; this does not establish that none exists. Examined the
released class files through CFR and checked the scheduling/exception paths with `javap -c -p`. CFR could
not reconstruct `ChorusCarto.rebuild` and produced warning-marked locals in `ChorusBeEngine.run`; do not
use those reconstructions as a complete source implementation. The wave selection and exception/lookup
findings below have direct bytecode/method evidence. The artifact was not launched.

Jar metadata declares LGPL-3.0. This is an architectural reference, not source imported into Forgia's
LGPL-2.1-only codebase. Downloaded artifacts, decompiler output, checksums and bytecode listings stay in
ignored `tmp/chorus-reference/`; no Chorus implementation or decompiled source is copied into tracked code.
This is a bounded static review, not a production corruption reproduction or a certification of either server.

## What is useful

Chorus separates three concepts that Forgia currently combines too coarsely:

1. **The ticking unit:** one original bound block-entity ticker.
2. **The dependency component:** the resources several tickers share, including inventories/controllers,
   rather than the chunk or region they happen to occupy.
3. **The execution wave:** ready, non-conflicting work from those components, with a barrier before dependent
   work continues.

`ChorusCarto.beginBE/touch/endBE` associates observed position accesses with the original invocation and
builds components. `ChorusBeEngine.tickOne/hardTouch` records a worker's block-entity accesses; `runWaves`
uses those sets plus learned edges and cartographer adjacency. The engine captures loaded chunks around
candidates and uses a worker pool. Level RNG streams are separated. Block updates, block changes, sounds,
entity additions and scheduled ticks have deferred entry points.

This addresses a real Forgia limit: 500 independent furnaces in one region still share one region worker.
Shrinking region size cannot expose independence between machines in the same chunk, and a whole-chunk
refusal can serialize a safe furnace simply because a different ticker occupies that chunk. Conversely,
machines in different chunks can still share one inventory or network. Geography alone is neither a
complete dependency proof nor an accurate estimate of available parallel work.

## Where the published implementation needs a different contract in Forgia

### Observed access is a prediction, not ownership

The reviewed `HARD` sets contain the ticker's own packed block position and recorded `getBlockEntity`
positions; `hardTouch` stops recording once its set reaches 64 positions. Recorded position accesses do not
cover arbitrary Java field references, previously cached IItemHandler/Container references, controllers,
entity mutation or world-global state. They also do not distinguish reads from writes. `HARD`, `EDGES` and
cartographer component keys use packed position longs without a dimension/level identity in those keys.

A machine that did not cross a boundary for 100 ticks can cross it on tick 101 because a hopper changed its
input or a recipe completed. Rebuilding history or learning an edge after a wave cannot retroactively make
that wave conflict-free. In Forgia, history should guide diagnostics and candidate preparation. Execution
requires a complete conservative footprint validated for the current invocation, or a genuinely buffered
transaction protocol. An overflowed/truncated resource set must select serial work, never be treated as a
complete smaller set. Resource keys must include server/level and instance lifetime.

### Non-conflicting colours do not imply serial dependency order

`ChorusBeEngine.runWaves` scans waves from index zero for each ticker and chooses the first wave whose
claimed resources/member sets do not conflict. It subsequently executes waves in index order. That can
separate conflicting tasks while reversing an earlier task's dependency on another wave.

Minimal independent scheduling example, checked locally:

```text
Original list: A touches X; B touches X and Y; C touches Y.
First-fit conflict colours: [A, C], [B].
Required directed dependencies: A -> B -> C.
Order-preserving waves: [A], [B], [C].
```

For the default minimum cluster size of five, a connected extension gives the same problem: add
`D touches Y,Z` and `E touches Z`. First-fit produces `[A,C,E], [B], [D]`, reversing both `B -> C` and
`D -> E`; directed waves preserve `[A], [B], [C], [D], [E]`. Both small examples were executed locally;
the five-unit input/result is retained in `tmp/chorus-reference/wave-order-counterexample.json`.

This is a scheduling counterexample, not a reproduction of an observed hopper loss in Chorus. It establishes
why Forgia must build directed edges from the original sequence, not use undirected graph colouring as a
substitute for preserving causality. A read/read overlap need not serialize; read/write, write/read and
write/write overlaps do. For an ordered unit `u`, its earliest wave is strictly after every conflicting
predecessor, rather than the first wave not currently containing a neighbour.

### Deferred success must preserve return-value semantics

In the reviewed `LevelAccessMixin`, worker `setBlock` appends an intent and returns true before the original
operation runs. `ServerLevelSyncMixin` similarly queues `addFreshEntity` and returns true. This does not
establish that the write/admission actually succeeded or that a later read sees an earlier queued write.

Forgia's existing opaque references and explicit operation results are a useful stronger boundary. A
request can be accepted, skipped, cancelled or failed, and admission cancellation is not equivalent to a
successful spawn. A vanilla operation that needs a synchronous success result should initially remain a
complete serial operation; it cannot be parallelized by pretending success and repairing its caller later.

Deferred neighbour effects also need a defined commit phase and order. `ConcurrentLinkedQueue` safely
stores entries but concurrent submission order is not the canonical original tick order. Worker-returned
block updates, sounds and events must not be described as vanilla-ordered without that additional protocol.

### Current out-of-capture behaviour differs from the website summary

`LevelAccessMixin.chorus$read` returns AIR when a worker has no captured chunk;
`chorus$readBe` returns null. Both mark the current component dirty. `demoteCurrent` does not itself replay
that invocation. `tickOne` catches Throwable, marks the component dirty, logs and returns; its null-position
branch catches Throwable without reporting it. These are concrete code paths in the inspected release.

The website's border-read wording says the unit is caught and re-run where valid. The inspected paths do
not establish such a complete same-invocation retry. Neither silently substituting AIR/null nor replaying a
partially executed inventory operation is acceptable as a correctness repair in Forgia. Before effects,
unsupported work selects a complete serial body. After effects start, an unexpected violation is a real
failure and must be reported without replay or counted successful completion.

### Serial work cannot overlap unknown worker footprints

In the reviewed `ChorusBeEngine.run`, small component tasks are submitted before the loop over the serial
list, and the final join happens after that serial loop. A serial ticker may still reach an unrecorded
resource owned by a running worker. Having a serial lane is insufficient without ownership/dependency
exclusion between lanes. Forgia currently waits for workers before executing its block-entity fallback;
this exclusion is worth retaining. If a future scheduler interleaves serial and worker waves, a serial unit
with unknown effects must form an exclusive barrier, preserving all relevant earlier/later dependencies.

The documented slow-task demotion is a useful scheduling policy for subsequent invocations. It does not
permit abandoning a running task or executing a replacement on main while the first still runs. Chorus's
wave wait itself continues waiting after marking a slow component; Forgia should keep that non-abandonment
property and its existing shutdown/exception cleanup.

## Forgia architecture to pursue

Keep the existing region ownership layer for entity/chunk/POI execution and external interactions. Add a
separate block-entity work graph inside a quiescent block-entity phase. A region lease remains the outer
exclusion boundary; a future smaller work lease must explicitly own its resources rather than giving two
workers the current entire-region entity/BE permissions. `RegionState.ownerThread`,
`RegionBlockEntityTickScope` and entity-query ownership currently assume one owner, so simply submitting
several groups with the same region coordinate is unsafe.

Resources must cover:

- The exact BE and inventory identities, including both halves of a double chest and a multiblock controller.
- Block/chunk state that an original body or comparator/neighbour cascade can touch. Two different block
  positions can still share a mutable section palette, block-entity map, heightmap or chunk mutation version.
- Any entities queried or mutated, RNG consumption, global callbacks, event order and world-owned state.
- Alias/capability relationships that bypass a world lookup. Unknown callbacks/endpoints stay serial.

For vanilla, use actual known method bodies to derive conservative current footprints and operation
boundaries. Supplement them with observations, but never infer safety solely from a type id or a previously
clean trace. First targets are independent furnace arrays and hopper/container chains because their
conservation/order can be checked directly. Brewing completion, campfire drops, shulker opening/pushing,
beacon player effects and spawner admission remain complete operations until their full effect contracts
are supported. The first milestone must not advertise those classes as fully parallel merely because their
idle counters were moved to workers.

Implementation order:

1. **Record and model:** invocation-scoped resource accesses and bounded per-level dependency diagnostics;
   compute a shadow order-preserving plan while existing execution remains authoritative. Report graph
   completeness, serial barriers, component size, wave depth/width and measured time, including preparation.
2. **Lease and schedule:** validated current footprints, explicit smaller resource leases, canonical directed
   dependencies, no concurrent unknown serial work, and barrier-safe cancellation/shutdown. Both actual
   simultaneous execution and read/write exclusion must be independently demonstrated.
3. **Vanilla validation:** compare every tick with the original serial list for furnace/hopper networks;
   check inventories, cooldowns, fuel, recipe callbacks, comparator and scheduled/event traces. Include
   same-chunk independent machines, shared output, double chests, negative coordinates, topology/input
   changes, unload/replacement, chunk/graph overflow, rejection and failure after effects.
4. **Performance gate:** repeated paired same-world tests of a dense furnace array, an independent hopper
   array and a fully coupled hopper chain. Count graph build/validation, worker body, commits and wait time.
   A serial chain should show the limit of the design, not be reported as a wide parallel success.
5. **Expand vanilla operations, then adapters:** integrate additional complete original operations and only
   afterwards extend mod resource declarations/adapters. Existing Create work is preserved but not expanded
   as part of this vanilla-first objective.

This preserves the useful Chorus insight—parallelize independent work even when it is dense—without
weakening Forgia's explicit ownership and failure contracts. The broad wave scheduler is a new engine
milestone, not a change that can truthfully be delivered by adding more registry ids to the current allowlist.
