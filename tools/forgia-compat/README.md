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

The throwing frame carried `pl:mixin:APP:connector.mixins.json:CommonHooksMixin from mod connector`: Sinytra
Connector's handler assumed the server thread.

## What it does

The jar holds the risky event seams on a lock while a region worker runs them, and fails closed if a handler
still produces a broken value (`BlockDropsEvent.getDrops()` returning `null` becomes an empty list instead of an
exception out of a chunk tick). Every patch is inert on a plain NeoForge core, so the same jar can stay installed
while the server runs the vanilla tick.

Covered today:

| seam | patch | reason |
|---|---|---|
| `CommonHooks.handleBlockDrops` | post behind one lock, null drops become empty | Connector crashed a live server here |

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

A seam belongs here when the crash report names a mod's mixin or handler inside a frame that runs on a region
worker (`RegionThreadingEngine` in the stack) and the handler assumes the server thread. Patch it by redirecting
the event post into a lock, and add the guard that turns a broken value into an empty one. Never change the
meaning of a cancelable event: those still have to run on the server thread, so they need the engine's
server-thread handoff instead.
