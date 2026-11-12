# Executed vanilla furnace work waves

This is the second milestone of [the block-entity work graph](BLOCK-ENTITY-WORK-GRAPH.md): a bounded,
opt-in execution path for verified original vanilla furnace-family ticks. Independent instances can run
at the same time **inside one chunk and one existing region**. The historical observed graph never grants
admission. This is not general Create/mod ticking, whole-hopper networks or all vanilla block entities.

In `.57-beta`, the separately gated [general resource ownership foundation](GENERAL-BLOCK-ENTITY-RESOURCE-OWNERSHIP.md)
can replace the position-key execution footprint with exact object claims and one-shot resource scopes under
those same Region leases. Set `enableRegionThreadingBlockEntityResources=true` in addition to the existing
wave flags to test it; default is false. Audited bodies, conservative aliases, unknown serial fences and
ordered notification commits remain mandatory. The flag does not admit more machine types.

## Enable and diagnose

With the usual bridge/workers/block-entity slice enabled and a fixed region layout, add to the effective
instance/world `forgia.toml` while stopped:

```toml
enableRegionThreadingFurnaceWorkWaves = true
```

Default **false**. Existing region size and worker settings apply. With this switch off, the previous
region-based block-entity path remains authoritative. With it on, this bounded phase executes selected
furnace work waves and complete serial fences in original ticker sequence; other block-entity types are
serial in this experimental mode, including types that have independent region-based worker admission.
A server dominated by hoppers/mod machines must not assume the switch improves performance.

```text
/neoforge regionthreading workgraph
```

The first line is **Executed furnace work waves**. `nativeCompleted` counts successful original-body
invocations on native workers, not task submissions or wrapper visits. `maxSimultaneousBodies` measures
simultaneous body intervals separately. `serial`, `skipped`, `failed`, `changes`, `rejectedTasks`, bounded
`refusals` and preparation/execute-await/commit timings make the limits observable. Counters are cumulative.
`serial` counts selections of the complete serial ticker path (a removed/unavailable wrapper can still skip
its body); `nativeCompleted` requires original-body entry and successful return. `failed`/`skipped` describe
the native-candidate path; the serial path retains its original error policy. These are not interchangeable
with successfully processed items or milliseconds saved.
The existing per-region ticker metrics do not separately attribute these smaller bodies; use this phase's
own timing and full server tick measurements for the experiment.
The optional historical shadow report follows and retains its separate partial-coverage wording.

## Current-operation admission

The preflight runs while quiescent and only admits:

- The exact built-in FurnaceBlockEntity, BlastFurnaceBlockEntity or SmokerBlockEntity with its current
  original bound ticker. A custom wrapper/function, subclass, adapter registration or same-position
  replacement cannot acquire this lease. The block is an exact vanilla furnace/smoker/blast furnace.
- Already burning with **more than one fuel tick remaining**. The full original `serverTick` runs,
  including matching and a safe processing completion; fuel consumption/remainders, start/re-light and
  extinguish preserve a complete serial tick.
- An exact RecipeManager and only exact vanilla cooking recipe classes with ordinary ingredients, including
  NeoForge's exact CompoundIngredient OR wrapper for vanilla JSON ingredient arrays. Its children must be
  recursively ordinary too (depth at most 8, at most 64 children/node and 256 nodes); arbitrary custom
  ingredients still refuse the whole relevant catalog before any matching callback runs. The catalog is
  checked before workers and ordinary ingredient caches are initialized quiescently.
- Plain built-in Item/BlockItem/DyeItem/ItemNameBlockItem stacks with no custom component patch. Mutable inventory stack aliases,
  attachments, custom handlers/items/recipes and unreviewed transformed methods remain serial.
- For a possible processing-completion notification, the six directions out to two blocks must be loaded,
  contained in the same outer region and made of the audited inert set (air variants, stone, cobblestone,
  dirt, bedrock, glass). Comparator/machine/unknown receivers cause a full serial fence before any effects.

The last bound matters: postponing an arbitrary neighbour callback until after another machine changes its
inventory is not equivalent to original ordering. This first implementation supports only notifications
whose receiver bodies are inert. It does not claim equivalent handling for arbitrary comparator networks
by merely putting their callbacks into a queue.

Merged Mixin provenance is checked for the inherited furnace/container/recipe/item/world methods used by
the protocol. Unknown merged methods refuse this path. This cannot automatically certify arbitrary
ASM/coremod/instrumentation, uncooperative async accesses or mods editing raw fields; such environments
need separate validation. The existing NeoForge block-entity timing tracker is single-owner, so an active
tracker also keeps this mode's units serial. The engine's normal profiler gate still applies.

## Ownership, ordering and effects

Canonical per-ticker sequence and the directed planner order both resource conflicts and unknown serial
fences. All work before a fence joins and commits before it starts; all later worker work waits until it
returns. A fresh preflight after preceding serial/mailbox work revalidates recipe catalogs, item aliases,
instances and chunk state. More than 2048 tickers selects the complete canonical serial phase.

The server acquires each outer region's exclusion lease in canonical order for the duration of preparation,
worker execution and commits. It does **not** give every worker that entire region. `FurnaceWorkScope`
authorizes one captured instance and its read-only chunk and wraps the actual original body. Foreign
references, shared chunk mutation, entity queries/admission, repeated bodies/tasks and unmodelled effects
are refused, with no AIR/null replacement. No shared RNG stream is used by the audited body.

`BlockEntity.setChanged` records a per-instance notification instead of mutating dirty chunks or neighbour
state on workers. After every task returns, the server publishes those original notifications once in
original sequence while retaining outer exclusion. Commit failure does not run a machine again. Worker
exceptions preserve the server-side crash/report policy; they never turn a partially executed machine
into a serial retry. Pre-body invalidation selects the complete serial operation; missing/stale wrappers
still use existing original load/removal checks.

A published lifecycle batch spans all waves, serial fences and commits. Rejected worker tasks execute on
the submitter once; they do not increase native completion counts. An interrupted/throwing wait still joins
every started task. Reentrant shutdown from a worker is rejected; external unload/shutdown waits for the
whole phase and cannot retire regions while original bodies run. The feature does not alter other slices'
region or entity ownership contracts.

## Recorded verification and cost

The initial full regression run passed **1153 core tests** and **16 existing Create integration tests**.
A fresh pre-push full run of the final source tree passed **1155 core tests**, with zero failures, errors
or skips, plus root/test format and license checks (evidence: `tmp/be-optimization/prepush-core-results/`).
The final body-interval accounting and ordinary vanilla dye/redstone/ingredient-array corrections were also checked
with **52 focused tests** (original furnace waves, existing boundary furnace equivalence and shadow graph),
format/license checks, patch reapplication and installer build. The separate actual unknown-Mixin profile
passed its one refusal/complete-serial test. Scope: real transformed original methods and actual native
worker/lease/lifecycle machinery with sparse world/chunk services; no claim of arbitrary modpack safety.

The deterministic rendezvous test enters two original `AbstractFurnaceBlockEntity.serverTick` methods in
one chunk/region simultaneously. Other tests independently cover 20-tick serial state/recipe/counter
traces, processing outputs, server-only fuel callbacks/notifications, custom ticker/recipe/item refusals,
mutable stack aliasing, unknown serial fences, recipe replacement between waves, rejection and throwing
waits, worker reentrant shutdown refusal and external shutdown waiting. Successful body intervals end
inside the original method, before wrapper/diagnostic completion; failed intervals close during unwind.

A separate tagged timing run uses the same 256 original warm-burning furnaces in one chunk, with identical
resets, 100 warmup and 400 measured ticks per round, order off/on/on/off/off/on. Mockito invocation recording
is disabled; no other test server, tests or compilation overlap the measurement. It includes preparation,
planning, actual execution/await and normal commit bookkeeping:

| Round | Waves | Mean ms | p50 ms | p95 ms |
|---|---|---:|---:|---:|
| 0 | off | 16.685658 | 15.125700 | 24.623602 |
| 1 | on | 29.249413 | 26.051401 | 49.729202 |
| 2 | on | 29.805995 | 26.279202 | 52.654905 |
| 3 | off | 14.660001 | 13.555901 | 20.523501 |
| 4 | off | 15.048166 | 14.113499 | 21.475798 |
| 5 | on | 31.363195 | 27.456001 | 49.193602 |

Median round means: **15.048166 ms off, 29.805995 ms on; on/off 1.9807**. The prototype is slower in this
fixture. These are **not real-server MSPT measurements**: Mockito/instrumentation heavily influences
absolute costs and possibly the ratio. This measurement predates the small success-tail interval-accounting
correction and explicit ordinary DyeItem/ItemNameBlockItem catalog admission; the cooking/scheduling algorithm measured
is the same. It supplies no evidence of a speedup in production and does not justify enabling the switch.

The original self-contained two-furnace installed layout passes with the switch both on and off: **441
normal ticks**, exactly **four iron ingots**, zero safety counters and saved exit 0 in each run. On-mode
records **876 native original bodies**, maximum overlap **2**; active comparator/close-machine notification
boundaries correctly retain complete serial processing ticks. Off-mode retains the prior region path.

A second installed layout spaces the same two furnaces four blocks apart in one chunk, without an active
notification receiver. It passes with **880 native original bodies**, maximum overlap **2**, exactly **four
native completion notifications committed**, four iron ingots, zero failed/skipped bodies and saved exit 0.
This separately proves the native-processing completion/commit path, rather than inferring it from steady
countdown dispatches. The runner's `--inert-layout` option reproduces it. Neither run is a performance A/B.

An intermediate installed run did execute workers but failed the fixture's exact-unit-count assertion
because its randomly generated world contained an extra ticking BE in the loaded area. That extra unit was
correctly a serial fence. Fixed seed/disabled structure generation now make the fixture deterministic; the
failed run remains recorded, not counted as a pass. The first two private installation attempts did not
pass the execution gate: the intact vanilla catalog
contains special built-in item classes (dyes/redstone) and ordinary ingredient arrays represented by
NeoForge's CompoundIngredient. The initial overly narrow catalog check conservatively kept every tick
serial. Both runs reported zero native completions and exited normally; they are not counted as successful
parallel validation. The corrected check audits those exact built-in paths, recursively refuses unknown OR
children, and logs at most eight concrete unsupported catalog reasons. Failed evidence is retained.

An earlier timing attempt did not finish: accumulating Mockito invocation records exhausted its test JVM heap,
and benchmark discovery tried to load a test Mixin class. Its printed partial rounds are invalid as a
completed performance comparison. The successful rerun disables that recording and excludes Mixin classes;
failed logs/XML remain beside the successful ones. Normal test discovery also excludes those classes.

To reproduce the successful tagged fixture (never alongside performance test servers):

```sh
./gradlew --offline --no-configuration-cache --no-parallel --max-workers=1 \
  -PforgiaVersion=21.1.250.55-beta \
  :tests:junitBenchmark --tests '*FurnaceWorkWavesTest'
```

Unknown transformed-original control, separate JVM:

```sh
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.55-beta -PfurnaceUnverifiedHookControl=true :tests:junitTest
```

Private installed-artifact validation uses the tracked runner, with actual original vanilla recipes,
chunk/ticker bindings and no helper mod:

```sh
python3 tools/block-entity-workgraph-smoke.py --furnace-waves --inert-layout \
  --run-dir /absolute/path/to/new-private-furnace-wave-test \
  --libraries /absolute/path/to/existing-server/libraries \
  --eula /absolute/path/to/already-accepted/eula.txt \
  --java /absolute/path/to/jdk-21/bin/java
```

Evidence lives in ignored `tmp/be-waves/`: full/focused/control XML, paired fixture logs/JSON, patch
verification, installer/build logs and the private server's commands, metrics, inventories and clean exit.
No original mod jar, production server or source world is modified.

## Verification scope

Tests must prove actual original bodies overlap, then independently compare inventories, burn/cook counters,
input/output changes and callback order against serial. Failure, identity, shared-stack, mixed/unknown
operations, recipe reload, timing, rejected tasks and lifecycle paths are separate regressions. A synthetic
unknown Mixin control runs in its own JVM so source methods cannot accidentally inherit permission after a
third-party transformation.

No throughput improvement is asserted from counters or overlap alone. Preparation, whole-catalog checks,
wave dispatch, conservative serial fences and commit overhead can outweigh these light vanilla bodies.
This switch remains experimental/default-off until controlled per-workload performance and larger
correctness coverage justify changing that policy. The [cost analysis and implementation plan](BLOCK-ENTITY-OPTIMIZATION-PLAN.md)
records the repeated preparation/planning costs, a separate compact planning prototype and the acceptance
criteria for the next performance work. That planner prototype is not installed in the production scheduler.
The `.56-beta` [work-stealing dispatcher](WORK-STEALING-DISPATCHER.md) now lets persistent furnace runners
steal unstarted original bodies within an already admitted wave; the eligibility and ordered-commit proof
are unchanged. Its dispatch-only timing does not replace the historical furnace or full-server measurements.
