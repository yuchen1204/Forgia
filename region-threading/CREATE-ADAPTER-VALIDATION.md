# Create Adapter 0.1.0 validation

Target: Minecraft 1.21.1, unmodified `create-1.21.1-6.0.10.jar` SHA-256
`ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a`,
Forgia `21.1.250.52-beta`, JDK 21. Source and installation instructions:
[adapter README](../adapters/create/README.md).

## Real transformed integration

16 Create integration tests pass with the real transformed jar and actual original BE tick bodies:

- Two separate native region workers rendezvous inside bound tickers, then execute real millstone ticks.
- Serial/worker timer and inventory traces agree for stopped, positive, negative and capped speeds and full-output transitions.
- A cached candidate changes from a steady worker tick to a complete serial processing tick; the original recipe,
  inventory extraction, rolled output and RNG execute once on the server. Recipe objects are test doubles for this case.
- The original recipe duration path remains serial. Unknown behaviour callbacks still execute once.
- Unknown subclasses, replacement handlers and public inventory aliasing refuse admission.
- Whole-network dirty state, missing members, unknown members and expiring snapshots retain serial ticks.
- A real waterwheel/millstone component runs native steady ticks, with the periodic flow path excluded.
- Configured 8x8 regions admit an interior machine while retaining a boundary machine's full serial tick.
- Audited original bytecode and actually applied accessors are checked.

A separate JVM injects an unknown no-op Mixin into the real KineticBlockEntity tick. Its one integration test
passes only when original ticks stay serial and diagnostics report `UNVERIFIED_TRANSFORMATIONS`.

Core `BlockEntityTickAdapterTest` covers registration/freezing/stop, per-invocation decisions, explicit server
pins, boundary geometry, exact source identity, predicate/preflight failure, shutdown before execution, and
no replay after body effects. The final full run passes **1,095 core tests**, **16 Create integration tests**,
format/license checks and builds of the installer, facade and adapter. The separate unknown-hook test passes.
Two initial full runs exposed a pre-existing live-FML/global-phase race in RegionSpawnTest. Its three phase
entry points now wait with a five-second bound until the test action actually acquires the phase; assertions
are unchanged and failed/partially executed actions are not retried. The final full run passes all tests.
Tests use world/chunk doubles and do not certify arbitrary packs, asynchronous capabilities or long multiplayer runs.

## Installed server smoke

A fresh private installation uses the built installer, original Create and the built adapter; embedded Create
dependencies load normally. It does not use a development classpath, copied source world, players or addons.
Only loopback game/RCON listeners are bound. Two separate 3x3 regions each hold an actual waterwheel, gearbox
and millstone with 16 wheat. Water supplies real rotation (8 rpm); no test code sets speed or replaces the ticker.

After **801 normal ticks**, the installed artifact reports:

| Counter | Value |
|---|---:|
| Native admission checks | 4,806 |
| Refused before body | 196 |
| Original tick invocations returned on native workers | 4,610 |
| Initialization refusals | 6 |
| Topology refusals | 110 |
| Shared-network refusals | 56 |
| Processing/particle callback refusals | 24 |
| Whole-network preparation total | 29.38 ms |
| Instance/identity checks total | 47.96 ms |
| Ownership violations | 0 |
| Worker tick failures (all slices) | 0 |
| Dropped entity additions/removals | 0 |
| Server exit | 0 (saved and stopped normally) |

Each millstone consumes five wheat and retains eleven. One produces six wheat flour and two seeds; the other
produces seven flour and one seed. Both report Timer 115, live waterwheel network capacity 256 and stress 32.
The differing flour counts follow Create's probabilistic recipe. No identical global RNG sequence is promised
for every possible inter-region callback order.

An earlier `/tick freeze` + `/tick step 800` fixture did **not** pass processing acceptance: Create checks
`isFrozen()` in getSpeed even while stepped game elements run, so the flour output remained empty. The run
recorded native callbacks but was explicitly failed. The accepted run unfreezes normal ticking; no code was
changed to bypass Create's freeze behaviour. Initial build interruptions also required regenerating truncated
Gradle script/configuration caches; those are build-environment failures, not passed tests.

Evidence for the final installed artifacts is retained in ignored local `tmp/create-adapter/final-server/`
(commands, compat, raw diagnostics, BE state, installer/server logs and summary). The preceding successful run
is in `server-smoke2/`, with the failed frozen fixture beside it. The reusable smoke runner is tracked at
[adapters/create/tools/server_smoke.py](../adapters/create/tools/server_smoke.py). No live server or
original Create jar was changed.

## Limits

These are functionality/concurrency results, **not a performance benchmark**. No throughput improvement or
whole-server speedup is claimed; simple transmission/countdown work is light and may lose to dispatch cost.
The adapter retains mixed-chunk fallback and interior-only placement. A network containing a mixer, belt,
unsupported motor or other unreviewed class keeps its supported members serial too. Loading additional Mixin
providers may invalidate the bytecode provenance check. The full pack and long-running multiplayer validation
remain outstanding.
