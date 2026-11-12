# Shulker shell-opening crash (2026-11-11)

The live report `crash-2026-11-11_04.10.47-server.txt` identifies **Forgia 21.1.250.44-beta**:

```text
IllegalStateException: Native region worker attempted server-owned entity storage access: Entity.move
  Entity.move
  Shulker.onPeekAmountChange
  Shulker.tick
  RegionThreadingEngine.runInEntityRegion
```

The entity was a vanilla Shulker in the End. Opening its shell computes an expanding lid AABB and calls
`other.move(MoverType.SHULKER, delta)` for nearby non-Shulker entities. Those targets can be players or
entities whose storage belongs to another region. This direct move bypasses the deferred Entity.push
interaction path. The guard correctly refused the foreign write; the admission policy had incorrectly
allowed the Shulker's whole tick onto a worker.

The fix declares Shulker **and subclasses** server-thread-only before their tick begins, with reason
`shulker_peek_push`. The normal server-thread path retains the full original tick and shell pushing.
There is no ignored foreign movement, whole-tick replay, change to Entity.move's guard or global disabling
of entity workers. Other ordinary mob ticks remain eligible for workers. A riding stack inherits a
passenger's class restriction through the existing policy.

The regression executes the real private `Shulker.onPeekAmountChange` method and the real Entity.move
guard/position update against a target across the fixed-region border at x=47/48. The fixture bypasses
unrelated collision simulation after the lid selected its target; it verifies one SHULKER movement, the
actual position delta, main-thread execution, continued Cow worker ticking and subclass admission refusal.
A negative control invokes the same real peek operation from an unpinned worker actor: the foreign move
is still refused, the target position stays unchanged and the violation is counted. These tests do not
claim a full Sable/End-city simulation.

## Second crash on .49: projectile-induced teleport

The later report `crash-2026-11-11_11.17.43-server.txt` was a separate path: a worker-ticked
ShulkerBullet hit triggers post-attack enchantment damage, which calls Shulker.hurt and teleports the
foreign Shulker through Entity.setPosRaw. Pinning Shulker's own tick alone cannot cover this callback.
The additional pre-body ShulkerBullet/subclass policy uses reason
`shulker_bullet_damage_and_enchantment_effects`; see
[the second crash and block entity investigation](BLOCK-ENTITY-AND-SHULKER-SECOND-AUDIT.md).
The .49 fix must not be represented as resolving all Shulker-related combat paths.

Live investigation was read-only. At inspection the panel reported running, and the new startup log
explicitly reported **upstream NeoForge 21.1.249**, including the downgrade `.44 -> 21.1.249`. The on-disk
core-pair sizes also matched the known upstream rollback. The assistant did not upload, replace, restart
or roll back the live server. A local build containing this fix is separate from live deployment.
