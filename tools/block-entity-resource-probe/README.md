# Block-entity resource ownership cost probe

This standalone JDK 21 probe measures **synthetic mechanism overhead**, not Minecraft block-entity bodies,
worker dispatch, outer Region acquisition, vanilla behavior, or server MSPT. It needs compiled Forgia core
classes but no running Minecraft server and no Mockito. It does not grant admission or change scheduling.

Each unit claims its own source/input/output object as WRITE, one shared region-local chunk-shaped object
as READ, and 4/64/256 audited-publication-shaped objects as shared READ. Inputs are prebuilt. Every sample
constructs complete claims, plans with the existing directed planner, reserves one wave, enters each scope
sequentially and executes five `require` checks, closes the scopes, and releases the ownership.

The same true predicate stands in for a **constant-cost** current attachment / already-held Region proof.
Real world binding and recipe preflight costs are **not represented**. Sequential scopes do not measure
cross-core cache contention. Allocation counters cover only the current thread; there are no worker threads.

## Run

```sh
export JAVA_HOME=/absolute/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 \
  -PforgiaVersion=21.1.250.57-beta :neoforge:compileJava
mkdir -p tmp/be-resources/probe-classes
javac -proc:none -cp projects/neoforge/build/classes/java/main \
  -d tmp/be-resources/probe-classes tools/block-entity-resource-probe/OwnershipCostProbe.java
# Stop competing builds, tests and private servers before timing.
java -Xms512m -Xmx1g -XX:+UseG1GC \
  -cp tmp/be-resources/probe-classes:projects/neoforge/build/classes/java/main \
  net.neoforged.neoforge.server.threading.region.OwnershipCostProbe 21 \
  > tmp/be-resources/ownership-probe.jsonl
```

There are 12 cases: 2/16/64/256 units × 4/64/256 shared reads. Before measurement, warm all cases in
three passes of 24 runs/case, then another 32 runs before each case. Print one JSON record per case from
21 measured samples. The optional argument selects 5..101 samples. `total` is measured directly;
independently reported component p50 values need not sum to total p50. Array bounds, successful
reservation, single-wave width, and cleanup are asserted; elapsed time is never an acceptance assertion.

Results are single-JVM, single-host exploratory costs, affected by JIT/GC/frequency and timer granularity.
They do not compare a full serial baseline or establish a speedup. Increasing shared footprint replication
is deliberately visible: the first version allocates maps/sets and the existing planner includes graph
diagnostics. Do not use this as evidence that more claims, leases, or simultaneous bodies improve MSPT.
See [architecture](../../region-threading/GENERAL-BLOCK-ENTITY-RESOURCE-OWNERSHIP.md) and
[bounded measurements](../../region-threading/BLOCK-ENTITY-RESOURCE-OWNERSHIP-PROFILE.json).
