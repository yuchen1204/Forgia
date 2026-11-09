# Whole mob-item pickup admission

## Live defect

Forgia .38 failed at 2026-11-09 08:30:21 (server log time): `Fox.pickUpItem -> Entity.discard`
was rejected on a native entity worker. The report names fox ID 26687 at (607.40, 67, -2234.97), but does
not identify the candidate item or its position. Do not infer that the item crossed a region boundary.

A physical region query can return a non-ticking item which is not in the current entity group's tick
membership. Visible and readable does not mean authorized for stack mutation or removal. Fox pickup first
splits the stack, spawns a remainder, spits out its held item, changes equipment and emits pickup effects;
the final discard guard is too late to supply an operation admission boundary. No guard is weakened.

## Implementation

Mob.aiStep now offers each queried pickup candidate to `RegionThreadingHooks.deferMobItemPickup` **before**
the original candidate predicate reads its mutable ItemStack or invokes `wantsToPickUp`. Every native-worker
candidate uses the existing strict source/target interaction scheduler, including same-region items. Only the
source mob needs current tick ownership; a non-ticking target may be validated and handled at completion.

After all workers finish, the strict task validates both identities' removal/world state. The new callback
then rechecks the live picker eligibility, alive/dead state, item emptiness and pickup delay, and invokes the
original virtual `wantsToPickUp` and `pickUpItem`. The original inline predicate and virtual call remain in
Mob.aiStep for server-thread execution; the Fox method and other overrides are not moved or replaced.
There is no item-filter null substitution, repeated tick or retry after a partial pickup.

The existing griefing event admits the source scan once. It is not posted again at completion. A denied
scan enqueues no pickups. Cancellation, source/target removal or world change, shutdown and closed levels
follow the existing interaction outcomes. A failed mod override is reported normally, without replay;
later independent accepted tasks are still drained. The shared 4096 request bound is retained: exhaustion
rejects before that candidate's predicate/body starts rather than bypassing ownership. Queue saturation
remains a broader admission limitation; this patch does not promise it cannot fail a tick.

Multiple mobs observing the same live item recheck it in serialized completion order. If one consumes and
removes it, later requests skip it. If the original method consumes only part, later requests see that
remaining count. Both equipment and item stack changes occur in one quiescent operation. Existing no-replay
and discard/removal guards remain active.

## Scope and ordering

This repairs the common Mob.aiStep pickup dispatch, covering the virtual bodies used there, including Fox
and mod subclasses. It does not automatically intercept direct calls from arbitrary mod listeners, Allay or
Piglin-specific inventory paths, or every other item transfer family. Those need their own operation-boundary
audit. It also does not widen worker entity queries, so their published/clamped visibility remains unchanged.

Picker tick code after the loot scan runs before the deferred pickup. The picker therefore sees its previous
held stack until completion; exact vanilla same-tick intra-entity ordering is not claimed. Relative ordering
between competing regions is request order, not deterministic single-threaded vanilla replay. If a mod needs
an immediate pickup result or relies on that intra-tick ordering, declare that entity type server-thread-only
until its contract is adapted. No broad Fox or Mob class fallback is enabled by this repair.

The callback runs under the main thread's quiescence, not while other worker groups mutate the world. It can
use the original subclass's split/remainder/drop/notification logic without assuming that query results grant
worker write authority. Arbitrary mod callbacks that mutate unrelated state remain subject to their existing
contracts; one pickup hook is not proof that the entire modpack is thread-safe.

The live report cannot establish how many items were lost/duplicated before the failing discard. Successful
shutdown saving also does not prove those partial effects were rolled back. This fix prevents recurrence at
the admitted dispatch boundary; it is not a historical inventory reconstruction or automatic rollback.

## Verification results

The final `.39` tree passed **933 JUnit tests** (zero failures, errors or skips), formatting and build checks.
A fresh-world GameTest run passed **all 170 required tests** and Gradle exited successfully. The preexisting
auxiliary `testFeatureGating` failure remains separate. The three negative controls failed when the new
Mob dispatch hook was temporarily removed; restoring it passed the complete entity suite and full tree.

All 758 patches reapplied with zero rejects and no patch-cache hit; forced regeneration was byte-identical.
The release installer completed successfully and the pickup hook is present in its universal jar. This is
local correctness evidence; it does not prove every mod's direct item-transfer callback is safe.

## Verification approach

The engine regressions invoke real Mob/Fox AI pickup dispatch and the real Fox protected pickup body on
actual native entity workers, with a non-ticking ItemEntity candidate outside the worker's membership. The
item's real guarded discard runs on the main thread. Real remainder/spit ItemEntities are constructed and
their counts are checked: a five-item diamond stack yields one held plus four dropped, while the previous
two emeralds are retained as a spit item. Two competing foxes consume the candidate once.

Other cases cover a foreign candidate, live pickup delay, candidate removal/world change, picker death,
griefing denial and event count, a throwing virtual override with later accepted work, unchanged server-thread
execution, and ordinary Mob partial equipment pickup retaining the unconsumed stack. World/query/notification
fixtures are controlled mocks; no deployed modpack reproduction or every-mod compatibility is inferred from
these tests. Removing the hook is used as a negative control before full-tree verification.
