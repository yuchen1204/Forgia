# forgia-compat

A standalone NeoForge mod that makes a modded pack survive Forgia's parallel chunk ticking.

## Why

Forgia runs a region's chunk random ticks on a worker thread. A mod that handles the events those ticks raise
through a shared, unsynchronized structure then races itself. The first observed instance on a 178-mod pack:

```
java.lang.NullPointerException: Cannot invoke "java.util.List.iterator()" because the return value of
  "net.neoforged.neoforge.event.level.BlockDropsEvent.getDrops()" is null
  at CommonHooks.handleBlockDrops(CommonHooks.java:542)
  ...
  at RegionThreadingEngine.lambda$chunkTickGroup(RegionThreadingEngine.java:6166)
```

The throwing frame carried `pl:mixin:APP:connector.mixins.json:CommonHooksMixin from mod connector`.
That identifies a transformed frame, not proof that Connector caused the null list. A later engine audit
reproduced the same null result without Connector: `Block.capturedDrops` was a static singleton list shared
by concurrent and nested loot evaluations. The engine fix replaces that holder with thread-confined,
nesting-aware capture scopes and exception cleanup; see
[the drop-capture audit](../../region-threading/BLOCK-DROP-CAPTURE.md).

## What it does

The jar holds selected event seams on a lock while a region worker runs them. Its legacy null-drops fallback
returns an empty list instead of throwing out of the chunk tick. **That is crash containment, not a proof of
item conservation**: a lost capture list cannot be reconstructed by returning empty. The engine capture fix
addresses the upstream shared-holder race before the event is constructed; this jar's event lock alone cannot.
Every patch is inert on a plain NeoForge core, so the same jar can stay installed while the server runs the
vanilla tick. The current task does not remove or redeploy the installed compatibility jar.

Covered today:

| seam | patch | reason |
|---|---|---|
| `CommonHooks.handleBlockDrops` | post behind one lock, null drops become empty | Legacy containment for a live null-drops failure; shared capture requires the engine fix |

## Build

```bash
./build.sh [install-dir] [out-jar]
```

`install-dir` defaults to `/home/yc/Minecraft/forgia-vanilla`; it is only used as the compile classpath (NeoForge
universal jar, vanilla server jar, sponge-mixin, event bus), so the build needs no network and no Gradle.

## Verify a mixin was applied

```bash
java -Dmixin.debug.verbose=true -Dmixin.debug.export=true ... @unix_args.txt nogui
grep 'CommonHooksMixin' logs/latest.log
javap -p -c -cp .mixin.out/class:... net.neoforged.neoforge.common.CommonHooks | grep forgia
```

## Adding a seam

Identify the actual shared data or ownership violation before adding a seam. A mod name in a transformed
stack frame alone is not sufficient attribution. If the mutable state is created before event dispatch, a lock
around the post does not protect it. Prefer scoped ownership and cleanup, test deterministic concurrency and
reentrancy, and verify item/entity counts. Preserve cancellation and synchronous result semantics; an operation
requiring the server thread needs a whole-operation admission before side effects, not an empty-value fallback
or fire-and-forget posting of a cancelable event.
