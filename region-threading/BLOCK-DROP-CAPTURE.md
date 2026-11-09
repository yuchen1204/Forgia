# Block drop capture: shared singleton race and scoped repair

This audit follows the shared-state category in [RECENT-BUG-CLASSES.md](RECENT-BUG-CLASSES.md).
The issue is in the NeoForge `Block` patch, not an identified Folia/Paper Ingredient/Connector implementation.
Folia's regional `captureDrops` is an architectural analogy; this fix is independently implemented for
NeoForge's synchronous drop event contract.

## Root cause and reproduction

`Block.capturedDrops` was a single static mutable `List<ItemEntity>`. All three `dropResources` overloads
assigned a new list in `beginCapturingDrops`, routed `popResource` into it, then called `stopCapturingDrops`,
which returned the list and set the global slot to null. Concurrent region workers and reentrant loot callbacks
both replace that same slot:

1. A starts capture and emits A1.
2. B starts capture, replacing A's slot, and emits B1.
3. A emits A2 into B's list, stops capture and clears the slot.
4. B emits B2 directly into the world, then stops and returns null to `BlockDropsEvent`.

The deterministic regression drives **real native worker threads**, the real three Block entry points,
real private `popResource` supplier funnel and real CommonHooks/event dispatch. Worlds, loot states and item
entities are mocks with real stack counts. No Connector or compatibility mixin is present.

Against the old implementation, five of the first six regression tests failed: null event list on concurrent
workers, null outer list after nesting, outer/inner item cross-contamination, and stale capture after a loot
exception. This independently reproduces the historical `BlockDropsEvent.getDrops()` null symptom. It shows
a sufficient core defect, not proof that every historical transformed-mod failure had exactly this cause.

## Fix and behavior contract

`BlockDropCapture` maintains a thread-confined linked stack of short-lived scopes. It is not one shared list
protected by an event-post lock. A scope begins before the existing getDrops call and closes in finally around
the existing loot/stop/event sequence. The three public overloads and the private begin/stop/pop signatures
are unchanged; existing getDrops, pop and event calls remain in their methods.

- `popResource` captures into the current scope while its loot is running.
- `end` returns a non-null mutable list and stops collection **before** posting the event.
- The current scope remains as a suspended marker while the event and after-break callbacks run. Standalone
  drops from those callbacks spawn normally rather than being captured by an enclosing block's loot.
- Event callbacks may start another dropResources scope. Its close restores the suspended marker, and the
  original outer loot resumes only after the complete nested event/after-break sequence returns.
- Finally restores the previous scope on successful return, cancelled events and loot/listener/after-break
  exceptions. Outermost close removes the ThreadLocal entry, and clears scope-held item/parent references.
- The event retains its existing mutable-list and cancellation authority. No automatic re-addition of removed
  event drops, no empty-list substitution and no replay/rollback is introduced.
- A failed loot evaluation still propagates its failure. The repair does not spawn a partial captured list as
  compensation. Failure after some drops were already admitted also propagates without admitting them twice.
- Wrong-thread or out-of-order scope closure is rejected without clearing another scope's state.

The scope stores call-local scratch, so thread confinement is appropriate even on a worker which later ticks a
different region. This is not a claim that persistent world state can be made safe with ThreadLocal alone.

## Verification coverage

`BlockDropCaptureTest` covers native-worker interleaving, all three overloads, nested capture/event/after-break
behavior, explicit event cancellation and mutable content replacement, caught nested failures, exception
cleanup and worker reuse, wrong-thread/nesting rejection, and no replay after after-break failures.
Normal uncancelled scenarios verify identity-based one-time admission and exact stack-count totals. Cancelled
or event-modified scenarios compare with the event's intended result. These are mock world admission checks,
not a live-modpack or save/reload acceptance claim.

The fixture intentionally pauses B until A's event has completed; it therefore tests the specific interleave
which produced null and mixed lists, not just probabilistic stress. The test does not serialize production loot
or require a global event lock to pass.

## Compatibility and remaining scope

The existing `forgia-compat` jar remains unchanged/installed until separately validated. Its event-post lock
cannot protect a capture list created before posting, and its legacy null-as-empty fallback is crash
containment rather than conservation. The README now distinguishes the transformed stack-frame evidence from
actual fault attribution. This task does not redeploy or remove that jar.

`Block.capturedDrops` was private; mods targeting its exact private field through mixins/reflection need review.
Public methods and the original call locations remain, but preserving method shapes does not prove compatibility
with every possible private-field transformer. A token scan of the available reference pack's mixin classes
found one `capturedDrops` token, which javap identified as a **LivingEntity.captureDrops** call/local variable,
not this Block field. That limited scan is not an exhaustive mod/mixin acceptance test.

This change does **not** make event listeners, `level.captureBlockSnapshots`, `level.restoringBlockSnapshots`,
mod-held fake players, or arbitrary global loot modifiers thread-safe. Those remain separate audit entries.
It also does not widen block-entity/contraption worker admission. Runtime guards and existing owner-operation
contracts are retained.
