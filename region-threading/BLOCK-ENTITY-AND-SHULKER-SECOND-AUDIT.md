# Block entity parallelism investigation and second Shulker-related crash

## Live evidence

The user-provided counters also occur verbatim in the archived server log at 2026-11-11 03:22:55 UTC,
from the .44 run: workers=6229, fallback=396800162, noTickers=33444930. This is **0.00157%** worker share
of worker+fallback ticker dispatches. It does not show industrial block entities executing effectively in
parallel, and dispatch share is not CPU-time share.

## What each counter actually means

- Workers increments after `ticker.tick()` returns through the worker-group path. It is cumulative since
  engine construction, not a unique block entity count or interval rate. Executor-rejected groups may run
  inline on the submitting thread and still increment this legacy counter, so it is not a strict physical
  worker-thread counter in rejection scenarios.
- Fallback increments when an admission/chunk is routed to server fallback, commonly by the number of
  ticker admissions in that chunk. It is a dispatch count, not proof every body executed: removed tickers,
  unloaded/changed chunks and vanilla bound-ticker eligibility can skip bodies. Existing exact-once tests
  cover mixed chunks and wrapper rebinding; no evidence here establishes duplicate execution.
- The old label `noTickers` was wrong: the underlying field is `nonTickingBlockEntityChunks`. It counts chunk
  visits rejected because tick rate is paused, `shouldTickBlocksAt` is false, or full status is below
  BLOCK_TICKING. It does **not** count empty/no-ticker instances. Display corrected to `nonTickingChunks`;
  the raw field name is unchanged.

## Admission restrictions explain low coverage

`RegionTickCompatibility` has only four default allowed types: minecraft:furnace, blast_furnace, smoker,
and hopper. Create and other industrial machines are not admitted by default. A pack can explicitly
widen the type list, but listing a machine is not a proof its global network/world/event mutations are safe.

All allowed types must also occupy an interior chunk: their eight neighboring chunks must belong to the
same region. A fixed 3x3 region has only one interior chunk out of nine (geometric eligibility, not a claim
that machine distribution is uniform). A larger fixed grid changes that geometric ratio but does not make
cross-region machine/network effects safe.

Finally, worker admission is **all-or-nothing per chunk** (`chunk.allAdmitted()`): one undeclared ticker
sends even otherwise safe tickers in that same chunk to fallback. The old whole-region restriction was
already replaced; the current chunk-level restriction still suppresses most factory chunks. Splitting
individual tickers without an interaction/order protocol can change inventories, kinetic networks and
neighbor visibility, so this investigation does not widen that boundary silently.

## NeoForge wrapping

LevelChunk explicitly recognizes RebindableTickingBlockEntityWrapper and captures the exact bound
instance/revision. RegionBlockEntityTickScope allows that captured binding, while stale/unknown bindings
fall back. The wrapper is therefore not a blanket prohibition on worker ticking. Existing tests use real
bound ticker wrappers and check rebinding, load eligibility, skipped bodies and no replay.

The undeclared-types table is updated when cached admissions are rebuilt, **not every ticker tick**; it
must not be interpreted as an accurate ranking of cumulative fallback time or invocations. This is why
its distribution cannot reconstruct the historical 396800162 dispatches.

## Added diagnostics

`regionthreading compat` now reports cumulative fallback admission dispatch counts:

- typeNotDeclared: admission explicitly lacks a safe-type declaration;
- boundaryChunk: allowed type fails the interior rule;
- safeInMixedChunk: admitted type displaced because another ticker in that chunk is unsafe.

These partition the unsafe-chunk admission branch, not every fallback reason. Stale snapshots/bindings,
lease failure and update-budget refusal remain separate existing diagnostics/attribution paths. Tests
assert that a mixed chunk dispatches each body exactly once and counts one undeclared plus one safe
collateral fallback. Use counter differences over a timed window; ticker CPU/wall profiling is required
before prioritizing Create types by actual cost. No arbitrary Create ticker has been enabled.

## Second crash: actual .49 stack

`crash-2026-11-11_11.17.43-server.txt` explicitly names .49. The entity being ticked is ShulkerBullet,
not Shulker. The path is:

ShulkerBullet.onHitEntity -> EnchantmentHelper.doPostAttackEffects -> DamageEntity.apply -> Shulker.hurt
-> teleportSomewhere -> setPos -> Entity.setPosRaw (foreign storage refusal).

Restricting Shulker's own tick does not restrict another entity's damage callback. The prior fix was
incomplete for this indirect operation. New class policy pins ShulkerBullet and subclasses before tick
admission, reason `shulker_bullet_damage_and_enchantment_effects`. It preserves complete impact/secondary
callbacks and does not skip damage, replay a partially applied hit or loosen movement guards.

The regression invokes the actual ShulkerBullet impact method with a foreign owner-mutation probe in the
hurt callback, checks it executes once on the server thread, verifies no ownership violation, and verifies
subclasses cannot bypass per-entity admission. This is a protocol regression, not a claim that the entire
live enchanted combat setup has been simulated. Original Shulker real-peek tests remain.

Subsequent 11:19:04/11:19:40 failures are **different startup failures**: upstream NeoForge249 was restored
but the Forgia adapter remained and its required ParallelApi was absent. On inspection the server was
offline, core sizes matched upstream249; no active adapter jar was found during the follow-up mods listing
(the operator appears to have removed/renamed it already). No server restart/core replacement was done.
Rollback must remove/disable Forgia-only adapters together with the core; a vanilla jar pair alone is not
a complete rollback after installing adapters.

## Verification and current state

Focused entity-policy/projectile/BE suites: 174 tests, zero failures/errors. Full core suite: 1024 tests,
zero failures/errors. Formatting and release packaging are recorded in tmp/shulker-second.
A follow-up panel resources query observed the server running again and no active MSC adapter jar in mods;
the assistant performed no power action or core replacement. .50 is a local build, not an uploaded fix.
