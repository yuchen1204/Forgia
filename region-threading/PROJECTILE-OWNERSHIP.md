# Projectile owner and target interaction audit (Minecraft 1.21.1)

This is a source audit of Forgia's currently generated NeoForge sources, not evidence that every listed path
has failed on the live modpack. It follows the ownership findings in [FOLIA-REFERENCE.md](FOLIA-REFERENCE.md).
Do not treat `Projectile.getOwner()` or a retained AI/hit reference as a mutation lease.

## Findings and work boundaries

| Path in `net.minecraft.world.entity.projectile` | Data accessed or changed | Required adaptation and present status |
|---|---|---|
| `Projectile.getOwner`, `getEffectSource`, `checkLeftOwner`, `canHitEntity` | Cached live owner, UUID lookup, passenger tree and owner position | Reference resolution is distinct from authorization. Current getter remains unchanged; hiding owners could change damage attribution, friendly-fire and block permission checks. Audit consumers before introducing a filtered getter. |
| `Projectile.hitTargetOrDeflectSelf`, `onHit` | Hit target, deflection/ownership replacement, hit event context | Complete impact/deflection transaction is needed before any target mutation. Generic whole-interaction API is available but these call sites are not yet wired to it. |
| `ThrownEnderpearl.onHit`, `tick`, `onInsideBlock` | Owner dismount, event, teleport, fall damage, endermite roll, gateway callback | Entire pearl tick is server-thread-only, including subclasses. `onInsideBlock` now forwards the immutable gateway state through the owner-operation API; tests exercise real connection/impact/cancel/gateway callbacks. Parallel-flight restoration remains blocked on immutable impact requests plus teleport protocol. |
| `ThrownTrident.tick`, `isAcceptibleReturnOwner`, `tryPickup` | Owner alive/spectator/eye position, return direction, eventual inventory insertion | Returning null can discard a loyal trident or change item conservation. Owner position needs a defined snapshot/follow policy and pickup must be an exactly-once inventory operation. Current behavior is not changed by this audit. |
| `AbstractArrow.onHitEntity`, `ThrownTrident.onHitEntity` | Target damage, knockback, post-hit effects/enchantments, projectile damage state | The target's hurt result controls subsequent effects. Do not move only `hurt()` and manufacture its boolean result; move the full impact or introduce explicit asynchronous completion. Not covered by melee AI hooks. |
| `FireworkRocketEntity.tick` | `attachedToEntity` reference outside `getOwner`, owner's velocity and position during elytra boost | A `getOwner` filter would not fix this separate cached reference. Need a per-owner boost operation or published owner input with ordered impulse application. Not yet adapted. |
| `FireworkRocketEntity.dealExplosionDamage` | Attached entity and area targets, damage/line-of-sight checks | Area effect queries can reach players published by the query layer. Query visibility does not authorize writes; require a whole effect transaction or per-target semantics, including the event ordering contract. Not yet adapted. |
| `WitherSkull.onHitEntity` | Victim damage/effect, and **heals owner on kill** | A source-consumed owner operation can carry a heal request, but the preceding damage/kill decision also needs safe ownership and exactly-once result handling. No partial automatic rewrite is applied. |
| `ThrownPotion.applySplash`, `makeAreaOfEffectCloud` | Many victims, mutable effect instances, owner attribution on a new cloud | Snapshot effect parameters, preserve cancellation/source attribution, then apply each owner's operation under an explicit ordering policy. A queued single live effect instance must not be reused across threads. Not yet adapted. |
| `Snowball`, `ThrownEgg`, `LlamaSpit`, `SmallFireball`, `ShulkerBullet` hit callbacks | Target hurt/effects and attacker attribution, sometimes owner-based enchantments | Damage attribution alone can be read-only, but hurt and callback chains are not. Do not declare these safe merely because most ticks only move. Not yet adapted. |
| `FishingHook` owner/retrieval/removal | Player inventory/rod, caught entity/item and hook lifecycle | Existing class server-thread policy retained. Full fishing interaction protocol needed before parallelization. |

Useful repeatable searches:

```sh
rg -n 'getOwner\(' projects/neoforge/src/main/java/net/minecraft/world/entity/projectile
rg -n 'attachedToEntity|\.hurt\(|\.heal\(|\.addEffect\(|\.getInventory\(' \
  projects/neoforge/src/main/java/net/minecraft/world/entity/projectile
```

These searches are navigation aids, not a complete mod/mixin call graph or automatic proof of thread safety.

## Two different lifetimes, two APIs

`submit(source, target, action)` requires both participants to remain in their original level until execution.
It is appropriate for an attack whose source must still exist. A removed or dimension-changed participant is
SKIPPED. It must be called before the operation changes either participant.

`submitToOwner(source, owner, action)` uses the source for admission only. The source worker must own it and
submit while the source is still valid. After acceptance, source disposal or movement must not cancel the
owner operation; the request retains only the owner, original level and callback. The callback receives only
the live owner, encouraging the source data to be captured by value:

```java
Vec3 impact = projectile.position(); // immutable value, captured before later movement
EntityInteractionTask request = threading.entityInteractions()
        .submitToOwner(projectile, owner, currentOwner -> {
            // Apply the complete agreed owner operation using currentOwner and impact.
            // Do not read projectile or an EntityHitResult captured in this callback.
        });
// Handle REJECTED explicitly before consuming the source for this operation.
```

This is an API example, not a pearl teleport implementation. Both methods currently run callbacks on the
server thread after entity worker completion, share the bounded per-batch capacity and explicit task outcome
model, reject unowned-source/unsupported-thread requests, and revalidate owner level/removal at execution.
A retired/teleported owner is skipped rather than replaced by UUID in another world. Target instance migration
across asynchronous teleports is not yet supported; silently resolving a new entity instance would change identity.

Cancellation clears the callback and live references. Shutdown closes admission and retires both operation
kinds before waiting for workers; normal batch closure has its own `BATCH_CLOSED` reason rather than falsely
reporting world unload or shutdown. Failures remain visible and later accepted operations still drain.

## First concrete integration: pearl gateway callback

The pearl's `onInsideBlock` keeps its original super call and gateway/owner selection, then submits the
owner notification before the existing `ServerPlayer.onInsideBlock` invocation. The immutable BlockState is
the only captured input; the owner callback does not read the pearl. A source-owning entity worker queues it
for completion, even if the pearl is consumed after acceptance. The conservative class policy still keeps
normal pearl ticks on the server thread; a test disables only that policy in its private engine to exercise
the worker boundary without enabling production flight.

Server-thread completion may execute the owner callback immediately while its own batch is still published,
but only after every worker of every active batch has returned and those batches belong to the current
completion thread. Being a main-thread service callback inside a worker barrier is insufficient. Foreign
running/submitted regions still forbid immediate execution. This supports nested owner operations from a
real gateway callback without falsely rejecting them just because its completed batch has not been retired yet.

When no engine is installed, vanilla executes its original callback. A pearl which was removed earlier in
its same tick can still reach vanilla's `checkInsideBlocks` tail: in that case the original callback remains
available only on the proven quiescent server thread. Unsupported contexts fail before touching the owner.

The full pearl impact event is not rewritten. NeoForge exposes the live pearl and hit object to event
listeners; a generic immutable owner request cannot preserve that API by silently replacing those objects.
The next impact milestone must explicitly preserve or revise this contract and handle the rest of the
projectile tick, rather than claiming this gateway integration implements asynchronous teleportation.

## Conditions before re-enabling parallel pearl flight

1. Capture impact coordinates, dimension and immutable event input before the source continues moving.
2. Admit the owner request before consuming the pearl; include explicit rejection and owner-retirement behavior.
3. Resolve the NeoForge teleport event cancellation, passenger changes, connection state and target chunk load
   in the owner's execution context. Preserve the intended event and damage semantics under asynchronous timing.
4. Journal any multi-step teleport so world unload, disconnect, shutdown and save cannot lose the owner/passengers.
5. Verify real player teleport acknowledgement, cancellation, gateway behavior, source disposal, inventory/entity
   conservation and failure recovery in integration tests and a controlled live comparison.

The new owner-operation primitive addresses the source-lifetime part of this list. It does not complete the
teleport, projectile impact, protection-event or inventory protocols. Existing guards and pearl fallback stay active.
