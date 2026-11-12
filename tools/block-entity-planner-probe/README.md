# Block-entity planner cost probe

This standalone JDK 21 tool compares the existing planner with an experimental planner that returns only
directed dependency waves. It executes no Minecraft body, grants no ownership and does not change the
production scheduler. The reference planner also computes dependency-link and connected-component
statistics; the prototype intentionally leaves that diagnostic work out of its execution result.

The tool verifies exact wave equality for 10,000 seeded random cases and checks every original directed
conflict separately. Inputs include read/read, write/read, read/write, shared writes, same-resource
read/write, exclusive fences and distinct world identities. It also checks the `A(X), B(X,Y), C(Y)`
counterexample and the measured 64/256/1024/2048-unit patterns. Verification and input construction happen
outside timing. Each measurement uses 12 warmup pairs and 21 samples with alternating implementation
order, and reads the current thread's allocated-byte counter.

From the repository root, first compile the core with JDK 21:

```sh
export JAVA_HOME=/absolute/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --max-workers=1 \
  -PforgiaVersion=21.1.250.55-beta :neoforge:compileJava
```

After compilation finishes and other tests or performance servers stop, run the probe:

```sh
mkdir -p tmp/be-optimization/probe-classes
javac -proc:none -cp projects/neoforge/build/classes/java/main \
  -d tmp/be-optimization/probe-classes \
  tools/block-entity-planner-probe/PlannerCostProbe.java
java -Xms512m -Xmx1g -XX:+UseG1GC \
  -cp tmp/be-optimization/probe-classes:projects/neoforge/build/classes/java/main \
  PlannerCostProbe 10000 21 > tmp/be-optimization/planner-probe.jsonl
```

Arguments are random-case count and sample count. Output is JSON lines: a verification record, followed by
paired timings and allocation summaries. Timings are machine dependent and have no pass/fail threshold.
The allocation figures exclude preconstructed inputs. They measure cumulative allocation per invocation,
not retained heap. The source prototype still creates maps/lists; it is a proof of a smaller scheduling
state, not the final packed-array implementation.

See the [recorded result](../../region-threading/BLOCK-ENTITY-PLANNER-PROBE.json) and
[optimization plan](../../region-threading/BLOCK-ENTITY-OPTIMIZATION-PLAN.md). No result here is a real-server
MSPT measurement or a claim that parallel execution has become faster.
