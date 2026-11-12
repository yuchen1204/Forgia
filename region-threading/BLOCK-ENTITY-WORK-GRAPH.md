# Block-entity shadow work graph

Implemented first milestone of [the Chorus-inspired vanilla direction](CHORUS-BLOCK-ENTITY-REFERENCE.md):
actual ticker observation and a directed wave planner. Its observation-only mode retains the existing region
leases. The next, separately gated milestone now executes
[verified furnace resource waves](FURNACE-WORK-WAVES.md), default off; shadow observations still grant no
permissions. No new block-entity type or general same-region scheduler is claimed, and there is no measured
speedup. This stage makes the input/order/coverage contract testable before broader resource ownership.

## Operator use

The existing bridge and block-entity worker slice must be enabled. Permission level 2 is required:

```text
/neoforge regionthreading workgraph start
/neoforge regionthreading workgraph start 20
/neoforge regionthreading workgraph
/neoforge regionthreading workgraph stop
```

The number is **nonempty level block-entity batches**, not seconds or whole server ticks. Several dimensions
can consume several samples per server tick. Default 20, maximum 200. Observation is off until requested,
stops automatically at the budget, and stops on engine shutdown. Starting again resets the generation;
stopping does not cancel a ticker or its batch. An old in-flight sample cannot replace a newer generation's
report. Nothing is persisted or enabled on next startup. There is no config migration or restart requirement.

The command reports the last completed sample, its dimension/game time, captured/invoked units, successful
or failing wrapper invocation counts on native/server threads, repetition, omitted units, resource overflow,
exclusive fences and observed dependency waves/components. It retains only detached aggregate values.
It does not retain live levels, tickers, containers, resource keys or closures after phase completion.

`native`/`serial` count actual wrapper invocations observed on those threads, not proof that every bound
wrapper reached its underlying body. `failed` is a wrapper that threw. `invoked` counts distinct captured
units; `repeated` makes multiple invocations visible. Removed/uninvoked entries may make `planned` exceed
`invoked`. Observation never retries a failed body or converts it to success.

## Resource and ordering model

A sample has the current batch's immutable candidate identities and original ticker creation sequence;
collection order breaks sequence ties. It never merges observations from previous ticks or other dimensions
into today's execution permission.

- Each invocation potentially writes its own BE/container resource.
- A returned live BE reference is conservatively a **potential write**, because `Container.setItem` and
  direct mutable fields need not call `setBlock`. Production `LevelChunk.getBlockEntity` records this access
  before the original lookup and keeps its return value, creation policy and ownership checks unchanged.
- Block/fluid access through the chunk read seam records a chunk read. Chunk mutation/write/dirty seams
  record chunk writes, sharing one resource for mutable palettes/maps/version state. Different positions in
  one chunk do not automatically imply independent chunk mutation.
- Recorded entity queries, admissions, scheduled effects and block publications are exclusive fences where
  the fine-grained effect footprint is not modelled. Undeclared/adapter tickers, repeated or failed invocations
  and truncated resource captures also fence the observed plan.
- Resource domains compare **level object identity**, never just a dimension name or packed position.
  Different servers/level instances cannot alias. Instance/rebind correctness is still enforced by the
  existing admission and bound ticker; observation does not replace it.

`BlockEntityWorkPlanner` uses read/write dependencies in canonical order. Read/read overlap can share a
wave. A write waits for all preceding readers since the last write and the preceding writer; later readers
wait for that writer. Unknown effects are barriers before and after their original sequence position.

For `A(X), B(X,Y), C(Y)`, the result is `[A], [B], [C]`. It must never colour that input as `[A,C], [B]`.
The planner constructs no worker tasks, grants no lease, consumes no game RNG and commits no effects.

## Bounds and interpretation

Each sample captures at most **2048 units** and **64 distinct resources per unit**, independently of world
size. It keeps the earliest canonical candidates. Omitted units are reported and force all retained units
to exclusive waves; a resource overflow fences that invocation. Incomplete smaller sets are never reported
as complete scheduling evidence. Samples do not accumulate historical raw graphs. Planner work/memory are
bounded by these limits; a long dependency chain is expected to remain narrow.

**All reports have partial coverage.** Raw Java fields, cached capability/container aliases, arbitrary event
listeners, RNG/global state and accesses bypassing instrumented entry points are not completely tracked.
An observed width is neither a safe parallel capacity nor a forecast of speedup. It does not authorize
relaxing mixed-chunk fallback or the interior-chunk rule. Furnace callbacks can remain unobserved even when
the type is built in. Extra real resource leases and current-operation proofs are still required.

`invocationSum` is the sum of measured invocation wall times including observation; simultaneous intervals
can overlap. `prepare` and `plan` are separate sample overheads. They are not milliseconds saved, CPU time,
whole-tick MSPT or a controlled performance A/B result. Disabled access hooks return after a volatile check
and do not evaluate extra chunk getters or allocate resource sets/timestamps.

## Validation on the first implementation

- Focused suites: 40 tests pass, including randomized dependency/state checks, real engine/bound-ticker
  reference capture, bounds/lifecycle/failure handling and furnace-family observation-on/off comparisons.
- Full core suite: **1136 tests, zero failures/errors/skips**. Root/test format and license checks pass.
- Patch regeneration changes only the intended `LevelChunk` patch. Reapplying the patch set succeeds and
  its source artifact contains the new production BE-reference observation hook. Core installer build passes.
- Private installed-artifact smoke, `21.1.250.54-beta`: two real furnaces in the **same chunk and region**, no
  helper/adaptor mods, normal ticking. Observation starts disabled, captures exactly 20 nonempty batches,
  stops automatically, and a new one-batch generation resets correctly. The final captured quiet tick shows
  `planned=2, invoked=2, native=2, serial=0, failed=0, omitted=0, overflowed=0`, and the **observed** plan has
  depth 1/width 2, two components and zero links. Execution remains the original single-region worker.
- That server runs **441 normal ticks**, consumes four ore and two coal and produces exactly four ingots;
  ownership/tick-failure/drop counters remain zero. It saves and exits with code 0. This is functionality
  validation, not an in-region-parallelism or performance result.

Evidence remains in ignored `tmp/be-workgraph/`: focused/full XML, build log, applied-patch check,
`server-smoke/` commands and raw diagnostics, and `final-validation.json`. The reusable runner is
[tools/block-entity-workgraph-smoke.py](../tools/block-entity-workgraph-smoke.py):

```sh
python3 tools/block-entity-workgraph-smoke.py \
  --run-dir /absolute/path/to/new-private-workgraph-smoke \
  --libraries /absolute/path/to/existing-server/libraries \
  --eula /absolute/path/to/already-accepted/eula.txt \
  --java /absolute/path/to/jdk-21/bin/java
```

It copies input libraries/EULA, refuses existing run directories, installs the built core privately and
binds game/RCON to loopback. It never modifies the input server/world. No live server was changed.

## Validation model and next stage

Planner tests independently enumerate every read/write conflict and compare read-dependent synthetic state
with the serial order after randomized permutations within waves. They cover the causal chain counterexample,
read-only sharing, exclusive barriers, level identity, immutable results and bounds.

Engine tests drive real worker batches and bound ticker/chunk reference lookup, server fallback, failures
without replay, stop/reset, limits and ThreadLocal cleanup. Real furnace-family inventory/comparator/scheduled
traces are compared with observation off and on at positive/negative X/Z borders. These validate observation
and planning, not a same-region parallel scheduler or arbitrary mod compatibility.

The bounded [furnace execution milestone](FURNACE-WORK-WAVES.md) introduces conservative current-operation
admission and instance/read-chunk scopes for verified burning furnace bodies; unknown serial work remains
exclusive. General container/hopper resources, arbitrary callbacks and notification receivers still need
larger operation proofs before the scheme expands. Demonstrate simultaneous original bodies separately
from conservation/order and include full preparation/dispatch/commit costs in measurements.
