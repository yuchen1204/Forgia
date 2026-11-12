# Follow-up audit: entity lifecycle and completion authority

This audit follows the installed-callback/cleared-added-marker defect described in
[ENTITY-CALLBACK-LIFECYCLE.md](ENTITY-CALLBACK-LIFECYCLE.md). It examines neighboring entity admission,
riding graph, movement/removal completion and interaction execution paths. It does not claim that every
mod callback or all concurrency hazards have been proven safe. No production server changes are part of it.

## Confirmed findings and fixes

### 1. New-entity admission trusted only the lifecycle marker

`deferEntityAddition`, `deferSpawnAddition`, fallback passenger validation and server completion still used
`isAddedToLevel()` alone. A stored entity/passenger with a cleared marker could therefore be accepted as a
new pending admission. The section manager might reject it later as a duplicate, but the worker had already
reported acceptance and transferred a receipt into the wrong protocol. The fallback could also accept a
riding graph with an already attached passenger whose marker was false.

Admission now uses the common `hasLevelEffects` predicate: an added marker or installed non-NULL callback
precludes a construction-only admission. This applies to both root and graph members, the handoff passenger
check and the barrier. Diagnostics classify these as already attached, and foreign stored entities are no
longer miscounted as attempted construction mutations merely because their marker was cleared. An existing
root's vanilla duplicate-readd handoff remains distinct from a graph containing an attached passenger.

Regressions use actual entities, a real guarded section manager and native workers. They verify refusal
before recording on both local/spawn paths, passenger/fallback refusal, and that a callback attached between
capture and completion cannot reenter vanilla admission. The pre-fix code failed all these checks.

### 2. A single graph preflight did not survive synchronous prefix callbacks

The barrier validated the entire riding graph once, then invoked each member's native add operation and
receipt. A prefix join/onAdded/receipt callback can change a suffix member's level, attach it independently,
or remove it. The old loop still attempted its admission into the original level.

Each member is now revalidated immediately before its own add call, in addition to the initial whole-graph
check. A changed suffix receives an explicit rejected outcome and reason; already applied prefix effects
are kept and never retried or rolled back. Later independent accepted work still drains. The regression
lets the real first-member admission synchronously move the suffix into a different level, then requires
that the old level never even calls addFreshEntity on that suffix. It failed before the fix.

This does not promise transactional rollback of arbitrary mod/event side effects. A callback can still
throw after native admission has partially happened; that remains FAILED, not proof nothing was applied.

### 3. Ordinary pair/owner operations ignored their completion proof

`RegionEntityInteractionTask.execute` used its supplied lifecycle/quiescence proof for ticket-backed loads,
but the plain pair and owner-only branches invoked their action without consulting it. Engine continuations
checked shutdown/closed-level status, but could not substitute for a current server-completion proof.

All ordinary/owner callbacks now consult lifecycle before their effects and revalidate participant removal
and original-level identity after that proof returns. Invalid context becomes SKIPPED/INVALID_CONTEXT;
owner retirement is also skipped, and the callback never starts. Owner-only operations still survive source
consumption, and cancellation, exception reporting and at-most-once behavior are unchanged.

Four direct task regressions failed before the fix: both ordinary and owner callbacks ran after a proof
returned INVALID_CONTEXT, and both could run after target retirement during the proof. An additional real
engine/native-worker test verifies queued pair/owner operations skip after their server-thread proof is
withdrawn. Old unit fixtures now model the server thread explicitly, rather than accidentally relying on
this missing check; the index-only fixture explicitly declares that it has no section identity ledger.

## Inspected neighboring paths

| Path | Outcome of this bounded audit |
|---|---|
| Stored entity movement/removal with a false marker | Previous repair retained: actual callback controls deferral; strict owner/worker/seam checks remain |
| Movement/removal callback at completion | Exact callback identity is rechecked; detached/replaced callbacks are not replayed |
| Truly new entities with NULL callback | Construction positioning and deferred admission remain supported; existing tests retain them |
| Chunk-backed owner operations | Proof before loading and again after it; owner/world revalidation and finally ticket release retained |
| BlockEntityOperations references and completion | Service-scoped opaque identities, chunk readiness and exact live-instance checks retained; no new confirmed defect here |
| Entity transfer identity ledger | Separate identity/epoch protocol inspected; no additional marker-only authorization found in this scope |
| Added-marker uses in vanilla lifecycle/configuration | Not mechanically replaced: for example target chunk acquisition preserves vanilla's marker condition |
| Mod-owned raw callback replacement / arbitrary live references | Not certified; public submission contracts and storage guards still apply |

## Validation

- Before fixes: **8 new checks failed**, covering all three confirmed findings.
- After fixes: full current-tree suite **1108 JUnit tests, zero failures/errors/skips**.
- Root/test format/license checks and `.53` installer/universal build passed.
- Real-server regression with native entity workers enabled: **2/2 required GameTests passed**
  (`storedCallbackWithClearedMarker`, `chunkBackedOwnerOperation`), Gradle exit zero.
- Local configuration restored byte-for-byte; no live server write/restart/upload, commit or push.
- The existing unrelated auxiliary `testFeatureGating` diagnostic still fails separately; it is not counted
  as one of the passing required GameTests.

The eight pre-fix checks establish the findings independently of production crash speculation; the real
server tests verify the retained callback deferral and actual ticket-backed owner operation paths. They
are not a recreation of all modded reentrant scenarios or a full modpack soak. Evidence and artifact hashes
are stored in `tmp/callback-followup-audit/validation.json` and adjacent logs.

This repair adds no Minecraft patch, no type blacklist, no automatic whole-tick replay, and no permission
for workers to touch the section manager. `.53` supersedes the local `.52` candidate with these additional
checks. Neither build has been uploaded or installed on the panel server by this task.
