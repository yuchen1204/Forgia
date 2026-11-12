# Ownership protocols: implementation and validation

The requested sequence is implemented as bounded experimental protocols: boundary furnace-family ticking,
complete cross-region block entity operations with a public mod facade, production scheduled callback
admission, and bounded execution/refusal/timing diagnostics. This does not automatically parallelize
arbitrary mod Java code or claim a measured performance improvement.

## Implementation

- `ParallelApi.Server.blockEntities()` / `BLOCK_ENTITY_OPERATIONS`: opaque weak instance references;
  capture under quiescence or the exact bound source ticker; bounded 4096-request BE batch queue;
  at-most-once typed results, cancellation, replacement/unload/shutdown/context/failure outcomes;
  whole operations execute serially on the proven quiescent server completion point. No worker waits.
- Built-in bounded Container slot transfers honor capacity, item components, extraction/insertion policy,
  sided slots and faces, exact partial insertion and notification. Unknown handlers/networks are not silently
  adapted; arbitrary throwing/misbehaving Container implementations cannot be rolled back transparently.
- Source authorization checks the bound ticker's actual block entity identity, not only its type/position.
  A same-position/type replacement cannot borrow the original ticker's lease. Captured target replacement
  skips rather than retargeting the operation.
- Preceding scheduled mutations are published before whole operations. A failed publication or callback
  does not strand later accepted operations; partial callback effects are never retried. Operation timing
  is separate from the previous entity-removal attribution.
- `LevelTicks.TickDispatcher` now preflights before draining the live queue. Production admits complete
  lamp-only block phases with an actually loaded, fixed-region-contained radius-two inert vanilla footprint.
  Unknown/mixed callbacks, fluids, unsafe footprints, profiler activity and budget limits keep the original
  live serial queue. Priority, subtick ordering, clearArea/copyArea and vanilla predicate behavior remain.
- Production no-RNG dispatch does not advance shared RNG. Started scheduled failures, including unstable
  reads, are reported once; remaining unstarted group callbacks are skipped/countable, not silent success.
- `ScheduledTickStatus`, `api.scheduledTicks()` and `regionthreading compat`: collected/admitted callbacks,
  native/serial completions, failures/skips, bounded refusal enums, preparation/worker/dispatch/completion
  timing and exact p50/p95/p99 over the last 128 nonempty phases.
- Public guide: `region-threading/CROSS-REGION-OPERATIONS.md`; compile-only example:
  `examples/parallel-api/ContainerLinkExample.java`. Existing docs and configuration description updated.

## Validation

| Check | Result | Evidence under `tmp/owner-protocols/` |
|---|---|---|
| Full JUnit suite | 1085 tests, 89 suites, zero failures/errors/skips | `final-full-validation.log`, `final-junit-result.json` |
| Root/test format and license checks; core jar and public facade build | Passed | `final-full-validation.log` |
| Enabled production GameTests | 3/3 required: public mod-source transfer, boundary furnace/comparator, scheduled lamp | `gametest.log`, `gametest-result.json` |
| Default-off full GameTests on a fresh generated world | 173/173 required passed | `default-fresh-gametest-ready.log`, `default-fresh-result-ready.json` |
| Reapply all 758 patches | Passed with no updating/fuzzy mode | `strict-apply.log` |
| Forced patch regeneration and source match | Both changed patches byte-identical; applied LevelTicks/LevelChunk sources match working sources | `roundtrip-generate.log`, `roundtrip-result.json`, `strict-source-match.json` |
| Public example compiles without threading implementation on classpath | Passed; facade contains 30 public/event classes and no runtime/engine classes | `facade-example.json`, `facade-example.log` |

The full JUnit result covers the final core implementation. The later GameTest-only correction waits for
entity loading and BLOCK_TICKING before scheduling the lamp in a newly forced chunk; the full default and
enabled GameTests exercise that corrected fixture. No core behavior changed after the full JUnit pass.

## Findings and practical limits

The first full JUnit attempt exposed two older tests which expected silent scheduled-tick truncation on
unstable/versionless reads. They now assert a reported exception, one pre-error effect and no replay. That
run also had one transient spawn-plan test failure; its focused rerun and the subsequent entire 1085-test
suite passed without changing spawning implementation. The original logs are retained.

An existing persistent test world caused the save/reload fixture to include a moving dropped item. The
fresh-world run passed the strict save/reload comparison without modifying it or relaxing assertions. A
fresh world also exposed the new lamp fixture scheduling before its forced chunk became block-ticking;
waiting for real load/tick readiness fixed the fixture. Original worlds are retained/restored; the fresh
validated world is retained separately for evidence.

The existing auxiliary `testFeatureGating` disabled-feature assertion still reports FAILED separately from
required GameTests, including the successful required runs. Startup also emits existing test-mod attribute
and optional-tag diagnostic errors. This report does not relabel those auxiliary diagnostics as passing.

All experimental switches remain default-off. Hoppers, Create, arbitrary IItemHandler networks and other
modded machines need an explicit full-operation adapter and their local tick's compatibility admission;
the new facade is not a blanket safe-thread declaration. Cross-region operation bodies are serialized at
completion; parallel multi-owner leases, cross-dimension operations, asynchronous chunk loading, arbitrary
rollback/continuations, long-run modpack validation and performance improvements are not claimed.

No commit, push, production server write/restart or external publication was performed. Local test config
and temporary declarations are restored by the runner; original config SHA-256 is
`eef43298a40881761da1f1a3647a238055bfb9c3e6924972edfda2763d422c96`.
