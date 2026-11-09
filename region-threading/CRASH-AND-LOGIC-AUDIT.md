# Crash and logic audit of worker-reachable code

Scope: direct inspection of the current local Forgia tree, focusing on entity worker admission, deferred
interaction/addition lifetimes, batch cleanup and exception aggregation, block-drop capture and named RNG.
This is a bounded audit, not a proof that every vanilla or mod tick is safe. No live server restart or deployment
was performed. Earlier local changes are included in the inspected tree and remain uncommitted.

## Confirmed and repaired in this audit

### Fireworks can mutate an entity outside their lease

`FireworkRocketEntity.tick` writes its attached LivingEntity's velocity while flying. Its private
`dealExplosionDamage` directly damages the attached entity, even a player, and then nearby entities.
The attached entity need not belong to the rocket's region. Pinning player ticks alone cannot authorize this.
Lethal damage and event callbacks can reach guarded mutations and fail the worker; velocity writes can race
without immediately hitting a guard.

The complete rocket tick now has the class policy `firework_attached_and_area_interactions`, inherited by
subclasses and considered for passenger-stack admission. All original flight, impact, damage and events are
retained on the server thread. This is an explicit temporary fallback until a complete ownership protocol
exists; neither catching and replaying an explosion nor deferring only the final damage call is safe.

The regression executes the actual private vanilla explosion damage body on a rocket attached to a player
outside its lease. The mock damage receiver probes ownership at the mutation boundary; it does not claim to
reproduce every real damage/death event. With the policy absent it reaches the normal Ticking entity failure
path. With the policy present it runs once on the test's main thread. The same class policy also covers attached
flight, whose vanilla code is retained unchanged; no separate full flight simulation is claimed by this test.

### Wither-skull impact can mutate its foreign owner

`WitherSkull.onHitEntity` damages its target and may call `heal(5)` on its owner when the target dies, then
`onHit` can explode. The owner may be far from the skull and need not have a lease in the skull's group.
The normal heal method itself has no ownership guard, so an unsafe owner write need not immediately crash;
mod heal listeners and later target/death/explosion callbacks can enter protected operations.

The complete skull tick now has class policy `wither_skull_impact_interactions`, including subclasses and
passenger admission. The regression invokes the real protected onHitEntity body with a foreign owner and
checks the healing boundary and exactly-once target damage. A probe guard exposes the unauthorized context
when the policy is removed. This is an ownership regression, not evidence of a newly observed live skull crash.

### A delayed addition can publish into an obsolete level

`RegionThreadingHooks.handOffEntityAddition` checked `entity.level() == level` when recording a request,
but checked only removal when running it. If the entity changed levels before the barrier, the old level
still received `addFreshEntity(entity)`.

Execution now revalidates the entity's level before calling the original admission path. The regression
queues two additions, changes one entity's level before completion, and verifies only the unchanged entity
reaches admission. Before the fix both reached the original level. This prevents a wrong-level publication;
it does not turn a queued admission into a guaranteed spawn or create a new cross-dimension transfer protocol.

### Exception aggregation can abort the remaining cleanup

Several engine drains and `runBatchCompletion` called `original.addSuppressed(next)` even when both arguments
were the same Throwable instance. Java rejects self-suppression with IllegalArgumentException. A mod which
reuses an exception instance can therefore mask its original failure and prevent unrelated queued mutations,
section publications, removals/additions, portal transitions or following completion actions from running.

A common `accumulateFailure` helper now preserves the original throwable and only suppresses distinct
instances. Existing checks in the ordinary deferred-server-task drains already avoided self-suppression.
Tests queue two callbacks throwing the same instance, a distinct failure, and a successful tail. The original
failure must propagate after the tail executes and the queue empties; a repeated completion failure must
also leave later completion actions runnable. This is cleanup correctness after a real failure, not suppressing
that failure or making a broken callback succeed.

## Subsequent map/I/O follow-up

The next source pass adapted compound map-ID allocation and main-thread map registry access, and repaired
NeoForge's shared I/O task-chain append/wait/failure handling. See
[MAP-AND-IO-CONCURRENCY.md](MAP-AND-IO-CONCURRENCY.md). Generic SavedData dirty/write acknowledgement,
map pixel/tracker ownership remain separate unresolved work.

## Subsequent missing-chunk follow-up

The entity/block-entity whole-tick replay queues were subsequently removed. Mid-body failures now report
normally without another invocation or silent success. New tests preserve actual inventory/output counts
and event counts; a real bound furnace reports from the server thread. An explicit ticket-backed owner
operation can load at quiescent completion and invoke its callback once, with cancellation, retirement and
post-load revalidation. See [CHUNK-OWNER-OPERATIONS.md](CHUNK-OWNER-OPERATIONS.md).

The initial audit evidence below is retained as history; the replay defect is repaired, but arbitrary mod
chunk access still needs a pre-body declaration or an explicit continuation. The new API is not automatic
Java stack resumption, asynchronous loading or a claim that unsupported callbacks cannot fail.

## Subsequent mob/item pickup follow-up

A .38 live fox pickup failed at the final ItemEntity discard after earlier stack/equipment effects. The
common Mob.aiStep dispatch now admits the whole virtual pickup before candidate predicate/stack access,
including local non-ticking items. Source/target and eligibility are revalidated at quiescent completion;
Fox remainder/spit and competing picker counts are covered. See [MOB-ITEM-PICKUP.md](MOB-ITEM-PICKUP.md)
for validation, deferred timing and direct-call/specialized item-transfer scope limits.

## Subsequent Sable loaded-chunk follow-up

The .39 enderman LOS crash exposed a worker lookup which bypassed Sable's separate plot storage. Required
worker requests now retain mod-injected non-loading lookup results via serviced server-thread lookup, with
Sable's own EmptyLevelChunk sentinel restricted to its claimed plot gaps. It does not create air for missing
world chunks, load chunks on workers or retry entity ticks. See [SABLE-LOADED-CHUNKS.md](SABLE-LOADED-CHUNKS.md)
for source evidence, tests and remaining moving-structure/read ownership limits.

## Confirmed remaining blockers

### Mid-tick missing-chunk replay (historical finding; removed in follow-up)

The original `RegionThreadingEngine.handOffRefusedChunk` queued a whole entity tick after an ordinary
missing-chunk refusal. The block-entity group did the same. A temporary self-driven safety probe produced
two increments instead of one, recorded in `/tmp/crash-audit-logical-red.log`. The old tests explicitly
expected two tick invocations while incorrectly describing the operation as once.

That code path and queue are now removed. Regression tests require normal failure reporting and one tick
invocation, including inventory, emitted outputs and event counts. Loaded-neighborhood/type/admission
fallback before the body starts remains valid. Broader chunk/spawn/scheduled truncation semantics and
full async-loading/teleport recovery remain separate incomplete protocols.

### Other direct interaction families still lack whole-operation ownership (high)

Inspection identified further unadapted worker-reachable calls:

- AbstractArrow and other projectile impacts can damage an entity returned by a query or deflect another
  projectile. A query result is not mutation authority.
- ThrownPotion and AreaEffectCloud can apply instantaneous effects/damage or mutate foreign effect state.
- Explosion damage and mod tick/damage listeners can mutate targets beyond the entity's lease.

The new firework/skull policies do not automatically cover these classes or direct mod calls. These are
source-confirmed unsafe interaction shapes; no new live crash or complete controlled runtime reproduction
for every family is claimed here. Implement whole-operation admission before the first virtual/event effect,
with defined result/cancellation semantics, or use an explicit class policy until that protocol is complete.

### Bounded interaction queue rejection currently becomes a hard failure (load-dependent)

The shared entity batch accepts at most 4096 interaction records; cancellation does not replenish this budget.
`deferMeleeAttack` turns rejected submission (including QUEUE_FULL) into an ownership rejection rather than a
safe before-tick fallback. If more than 4096 such cross-owner operations are requested in one batch, an
otherwise valid attack can therefore fail the entity tick. This bound is deliberate memory protection;
raising it or returning false would not supply ownership. Admission must reserve capacity before affected
work starts, or use a separately bounded safe fallback protocol. No live instance of queue saturation is
asserted by this source audit.

## Inspected without a new confirmed crash defect in this audit

- BlockDropCapture nesting, suspended scope during events, owner-thread enforcement and finally cleanup.
- Interaction task state/cancellation transitions, executing callbacks outside task monitor, terminal reference
  release and accepted owner-operation lifetime after source removal.
- Named stream registry/generator/dirty lock ordering and callback-outside-lock behavior. The separately known
  generic SavedData dirty-clear/write-ack race remains unresolved; see NAMED-RANDOM-SEQUENCES.md.
- Fixed-layout activation index range checks and negative-coordinate floor division. No reproducible ordinary
  fixed-layout array out-of-bounds defect was established. This does not authorize arbitrary malformed positions.

## Validation

The final combined tree passed 893 JUnit tests (zero failures, errors or skips), formatting and build in
3m53s. A fresh-world GameTest run passed all 169 required tests and Gradle exited successfully in 1m6s. The
existing auxiliary testframework `testFeatureGating` failure remained separate and unchanged. The Minecraft
patch files were unchanged from the previously byte-identical, zero-reject roundtrip; no new patch generation
was necessary for these Java-side fixes. Final diff whitespace checks passed.

The five final negative controls failed when the two class policies and self-suppression guard were removed;
restoring the fixes passed the targeted suites and full run. The wrong-level addition regression separately
failed before its application-time check was added. These counts describe the original audit milestone.
Subsequent replay-removal and owner-operation validation is recorded separately in
[CHUNK-OWNER-OPERATIONS.md](CHUNK-OWNER-OPERATIONS.md). No deployment or live modpack performance validation
was performed.
