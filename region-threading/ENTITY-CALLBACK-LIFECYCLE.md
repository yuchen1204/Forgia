# Installed entity callback with a cleared lifecycle marker

The November 12 panel incident ran Forgia `21.1.250.51-beta`. Its fatal path was
`netherexp:vessel` in Nether Expansion 2.4.1, at approximately (-291, 98, -442) in the Nether:

```
Vessel.tick -> LivingEntity movement -> Entity.setPosRaw
-> PersistentEntitySectionManager.Callback.onMove
-> native worker storage guard
```

The archived log records the refusal on a native region worker before the server reports the entity tick
failure at its barrier. This is an ownership failure, not an OutOfMemoryError or a server-hang watchdog.
The server was already restarted on upstream NeoForge 21.1.249 when investigated. No production files,
configuration, entities or power state were changed during this investigation/fix.

## Reproduced core defect

The old ownership scope used `isAddedToLevel()` to distinguish a pending entity's plain field writes from
stored entity movement/removal. However, vanilla `setPosRaw` invokes its installed level callback when the
position changes **regardless of that marker**. A cleared marker with a real installed section-manager
callback therefore had two incompatible decisions: the guard granted a construction-only position write,
but the deferral refused to queue the callback. Position changed first, and the inline callback then failed
on the worker. A foreign stored entity could also be mistaken for a pending entity by the construction grant.

Two regressions reproduced these defects before the fix: an owned entity with a real guarded manager
callback and cleared added marker crashed on onMove; a foreign entity in that state was mutated before its
storage callback refused the worker. Both failed on the original code and pass after the fix.

The production crash report does not include the entity's added flag or active lease fields. The reproduced
state explains the observed path and establishes a real core defect; it does not prove which mod/lifecycle
transition produced that exact state on the live server. Mixin lists and crash-report banners are not
sufficient evidence to blame Nether Expansion, Sable or Connector. This change does not certify arbitrary
mod callbacks or every possible source of an inline storage callback.

## Fix

- Treat an added marker **or an actual non-NULL callback** as having level effects. Only a genuinely
  callback-free entity may use the construction-only grant. Foreign stored entities keep the ownership
  refusal before position writes.
- An owned live entity with an installed callback queues its section publication for the batch barrier even
  if its lifecycle marker is false. Do not invent `onAddedToLevel()` calls or change the entity's marker.
- The server completion rechecks the captured callback's exact identity before invoking it. A detached or
  replaced callback is dropped rather than publishing through stale storage authority.
- Preserve vanilla's `isAddedToLevel` condition for the target chunk acquisition; an installed callback alone
  needs section publication, not an invented chunk load.
- Apply the same callback-based rule to owned removals and their barrier: a cleared marker must neither
  permit a worker callback nor silently lose the actual stored entity removal.
- Keep all worker/member/level/removal/seam and shutdown guards. Never allow direct worker section storage
  access, replay a partially applied tick, or blacklist Vessel as a substitute for the common fix.

This fix changes the scope and completion implementation; it requires no new Minecraft patch or mod mixin.

## Validation

Related native-worker ownership, section, removal and admission tests: **67 passed**. The regressions cover
real section-manager callbacks, final section reconciliation, pending entities, foreign pre-mutation refusal,
cleared-marker removal and stale callback replacement. Full current-tree JUnit: **1099 tests, zero failures,
errors or skips**. Root/test formatting/license and `.52` installer/universal build passed.

A new real-server `storedCallbackWithClearedMarker` GameTest deliberately clears the marker during a native
entity tick while retaining the actual section-manager callback. It moves across a section and verifies the
real callback's final section, unchanged false marker and native execution. It tests the reproduced core
state with a vanilla cow, not a false claim that the entire live Vessel/modpack scenario was recreated.
Its required test passes with Gradle exit zero and byte-identical configuration restoration; the
repository's existing auxiliary `testFeatureGating` disabled-feature assertion
continues to report separately. Exact run exit/config restoration evidence is retained in
`tmp/vessel-section-fix/gametest-result.json` and adjacent logs.

Local candidate: `projects/neoforge/build/libs/neoforge-21.1.250.52-beta-installer.jar`. This is a built,
locally tested candidate; no server installation, upload, restart, commit or push is part of this repair.
Current unrelated Create adapter/API work in the shared tree has been preserved.
