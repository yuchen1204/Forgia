# MobSpawnController 0.3.0 local measurement and delivery decision

**Decision: keep MobSpawnController natural spawning serial by default.** Ordered finalization is an
experimental opt-in (`-Dforgia.mscOrderedSpawning=true`), not a recommended deployment setting.

## Recorded evidence

Both runs used the installed .48 core, identical adapter/rules hashes and copied initial world. The
selection contained 169 source-pack jars (C2ME excluded because it disables the engine), adapter and a
private fake-player network helper: 171 jar files total. Both game and RCON were loopback-only. Private
copies of libraries/config/scripts/world were used; no source-pack or online-server mutation occurred.

The test used one fake player near spawn, 49 forced chunks, midnight, and discarded mobs every 100 ticks
to sustain spawn demand. This is a synthetic spawning workload, not normal gameplay or multiplayer.
Each run had a 30-second warmup, a roughly 60-second measured window and seven timing samples.

| Recorded metric | ordered-h | serial-h |
|---|---:|---:|
| Median reported overall ms/tick | 39.306 | 17.794 |
| Ordered finalizations during measured window | 690 | 0 |
| Worker spawn chunks during window | 114277 | 0 |
| Truncated worker spawn bodies during window | 5294 | 0 |
| Refused versionless foreign chunk reads at final sample | 4437 | 0 |
| Natural join events at final sample (includes warmup) | 210 | 283 |
| Natural join events during window | 162 | 218 |
| Ownership violations / recorded tick failures | 0 / 0 | 0 / 0 |
| Process stop | clean, exit 0 | clean, exit 0 |

**Limitations:** the ordered run overlapped the final Gradle test run, while the serial control did not.
Therefore the observed 2.21 ratio is not a controlled estimate of the feature's slowdown; CPU contention,
warmup/JIT and differing spawn histories confound it. It provides no evidence of a speedup. A fair
performance claim requires isolated repeated A/B runs after all builds/tests finish. The published
`serverMs` counter measures only continuation bodies, not all stop-point/publication/selection costs.

More decisively, ordered spawning recorded 5294 truncated bodies and 4437 refused foreign reads. These
are existing containment mechanisms, but they change completed spawn work. A run with them cannot be
called an equivalence/correctness pass just because it did not crash. The generic runner emitted PASS
because its legacy safety predicate did not include those fields; that label is overridden here:
**ordered-h = functional execution demonstrated, acceptance FAILED**. The roughly 3.65% fraction is
5294/(114277+5294), not a fraction of successfully generated entities.

The serial run completed with no worker truncations/refusals. Default adapter registration now retains
the original DISABLE_SPAWNING action, explicitly covered by a real registration/conflict-plan test. The
empty-selector optimization and exception-safe context cleanup remain usable on the serial path.

## Failed setup attempts

serial-a failed the old distant fixture's chunk-loading precondition; serial-b hit Rhino's players
property/method mapping. ordered-c exposed StarterKit/Collective assuming a network channel on a fake
player. ordered-d/e hit KubeJS class-filter restrictions while preparing private instrumentation.
ordered-f had invalid private helper metadata and never loaded. These are not successful measurements.
ordered-g exposed repeated checkpoint notifications causing monitor contention/livelock; fixed in .48
and covered with four workers, 24 queued groups and 240 transactions. All evidence is retained under
ignored `tmp/msc-v3`; helpers and original mod bytecode are not packaged into delivery artifacts.

## Separate live crash

The live .44 crash was Shulker.onPeekAmountChange moving a foreign entity from a worker. It is fixed
by pre-body class admission (`shulker_peek_push`), with real peek/move positive and negative controls.
See [SHULKER-PEEK-CRASH.md](SHULKER-PEEK-CRASH.md). Current live rollback was verified from startup logs as
upstream 21.1.249; no upload/restart was performed. The local .49 build includes the fix and the default-off
adapter decision; it is not deployed by producing these artifacts.
