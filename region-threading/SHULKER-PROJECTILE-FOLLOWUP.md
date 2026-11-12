# Follow-up review of Shulker / projectile ownership fixes

Scope: review the .49/.50 Shulker fixes and nearby same-class failure paths. Create adaptation is paused.
No upload, online file change or power action is part of this review.

## Additional findings and changes

1. **The bug pattern extends beyond ShulkerBullet.** `AbstractArrow.onHitEntity`,
   `ThrownTrident.onHitEntity`, `SmallFireball.onHitEntity`, `LargeFireball.onHitEntity`,
   `LlamaSpit.onHitEntity` call hurt and/or post-attack enchantments directly. Snowball and egg impacts also
   call hurt (zero damage can still enter callbacks). Potions apply effects/damage to multiple entities.
   Local target geometry does not prove that secondary callbacks stay local: the live ShulkerBullet crash
   mutated its owner through post-attack effects.

   Added a pre-body `Projectile.class` restriction with reason `projectile_impact_callbacks`. All subclasses,
   including modded subclasses, retain their complete server-thread tick until a whole-impact continuation
   exists. Existing more specific pearl/firework/skull/ShulkerBullet reasons still win through nearest-class
   lookup. This is deliberately conservative; it reduces projectile parallelism rather than claiming a
   generic impact API is implemented. Ordinary mobs remain eligible.

2. **Same-region melee shortcut.** The existing fast path permitted same-owned-group nonplayer targets to
   be hit inline. It now also requires that the target has no server-thread-only policy. Hitting a declared
   target such as Shulker routes the whole virtual attack through the existing completion protocol, even
   when the attacker and victim positions lie in the same region. Damage/teleport is never replayed.
   This does not prove all third-party damage callbacks of undeclared ordinary mobs are thread-safe.

3. **BE diagnostics review.** The new reason counters count admission dispatches and partition only unsafe
   chunk fallback, not all fallback paths. NoTickers was a label bug and is now nonTickingChunks. Type
   encounter counts are explicitly documented as cached snapshot decisions, not hot-ticker CPU time.
   These corrections do not enable any industrial ticker or change machine execution order.

## Regression evidence

- Projectile-family admission covers arrow, trident, small fireball, potion, spit, snowball and egg;
  passenger vehicles cannot bypass their policy; ordinary Cow remains worker-admissible.
- Actual LlamaSpit.onHitEntity executes against a foreign victim mutation probe on the server once.
- Actual ShulkerBullet.onHitEntity executes damage/secondary-owner mutation probes once on server;
  subclasses cannot bypass admission.
- Actual Shulker lid method + Entity.move position-update tests retained, plus negative worker guard control.
- Same-region attack against Shulker queues before side effects and completes exactly once on server.

These tests use real impact/lid methods with controlled world/mutation probes. They are not a full replay
of all live enchantments/Sable interactions. Projectile.class does not include every autonomous damaging
Entity (for example area-effect clouds and evoker fangs); those require a separate broader combat audit.
No claim is made that every possible mod callback or ownership violation in the server is eliminated.

## Actual verification results

Focused ownership/projectile/chunk-guard suites: 109 tests passed. Formatting and universal51 build passed.
Full core run: 1027 tests, one failure in RegionSpawnTest.adapterFallbackRunsOneCompleteSerialSpawnWithoutWorkerSubmission
(expected mocked inline-body count1, observed0). Entire RegionSpawnTest then passed independently, 15/15.
The order/isolation-dependent failure has not been explained; this is not a clean full-suite certification.
No new live projectile failure was observed in these tests. Installer packaging is still blocked by
createLauncherProfile -> ConnectException. Universal artifact content/hash is recorded in
tmp/shulker-second/projectile-artifact.json; no new release was uploaded or restarted.
