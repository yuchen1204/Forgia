# Block-entity resource ownership cost probe

Standalone JDK 21 **synthetic mechanism overhead**, not Minecraft bodies, worker dispatch, outer Region
acquisition, callbacks, cross-core contention or server MSPT. It invokes production Forgia classes; no
Mockito, copied ownership implementation or elapsed-time pass/fail assertion is used.

## Inputs and measured phase

12 cases: 2/16/64/256 units × 4/64/256 shared READ objects. Each unit has its own source/input/output
WRITE and one common region-local chunk-shaped READ. Inputs and effect counters are prebuilt.

Every sample measures, including allocations:

1. `capture`: construct the complete claims (and, in shared mode, one new shared publication).
2. `plan`: production ownership consistency checks and production dependency planning.
3. `revalidate`: a **second fresh capture** and resource-set equality checks.
4. `reserve`: new ownership coordinator, production all-or-nothing reservation and leases.
5. `scope`: sequential entry of every lease, five `require` checks per unit, exit.
6. `release`: ownership close. `total` is directly timed from step 1 through step 6.

This differs from the historical `.57` probe, which did not include fresh revalidation. Do **not** divide
its old totals by these new totals. Per-component quantiles are independently sorted and need not sum
to the directly measured total quantile. Allocation quantiles cover the coordinator thread only, excluding
input construction, output formatting and the returned timing array; there are no workers.

All proof predicates are constant true substitutes for current publications/bindings/held Regions.
Actual dynamic world/recipe validation is deliberately **not** represented. Shared mode has one explicit
publication proof per captured shared set, rather than pretending different attachment callbacks may be
omitted. Production still validates every distinct ordinary Binding and every distinct shared proof.

## Compile and run the current implementation

```sh
export JAVA_HOME=/absolute/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 \
  -PforgiaVersion=21.1.250.58-beta :neoforge:compileJava
mkdir -p tmp/be-resource-opt/current-probe-classes
javac -proc:none -cp projects/neoforge/build/classes/java/main \
  -d tmp/be-resource-opt/current-probe-classes tools/block-entity-resource-probe/*CostProbe.java
# Finish/stop all competing builds, tests and private servers before timing.
./gradlew --stop
java -Xms512m -Xmx1g -XX:+UseG1GC -Xbatch \
  -cp tmp/be-resource-opt/current-probe-classes:projects/neoforge/build/classes/java/main \
  net.neoforged.neoforge.server.threading.region.OwnershipCostProbe 21 shared \
  > tmp/be-resource-opt/current-shared.jsonl
```

Arguments are `[5..101 samples] [expanded|shared]`; defaults `21 expanded`.
The common `OwnershipCostProbe.java` compiles against both `.57` and `.58`. `SharedReadOwnershipCostProbe`
uses the new production representation; reflective selection happens once **outside all timing**.
Expanded mode uses exactly the old per-unit Binding construction on either production version.

Before any measurements, warm all cases with three passes of 24 runs/case, plus 32 further runs before
each case. Report p50/p95 for every component, full phase, and allocated bytes from 21 samples/case.

## Frozen production baseline

Do not compare only to the old published numbers. Preserve the actual `.57` production classes before
building `.58`, or extract the ownership/planner classes from the `.57` universal jar:

```sh
mkdir -p tmp/be-resource-opt/baseline-classes
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
base = Path('tmp/be-resource-opt/baseline-classes')
prefix = 'net/neoforged/neoforge/server/threading/region/'
with ZipFile('projects/neoforge/build/libs/neoforge-21.1.250.57-beta-universal.jar') as jar:
    for name in jar.namelist():
        if name.endswith('.class') and any(name.startswith(prefix + cls) for cls in
                ['BlockEntityResourceOwnership', 'BlockEntityWorkPlanner']):
            path = base / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(jar.read(name))
PY
# Compile the identical common harness against the frozen classes FIRST on the classpath.
javac -proc:none \
  -cp tmp/be-resource-opt/baseline-classes:projects/neoforge/build/classes/java/main \
  -d tmp/be-resource-opt/baseline-classes tools/block-entity-resource-probe/OwnershipCostProbe.java
java -Xms512m -Xmx1g -XX:+UseG1GC -Xbatch \
  -cp tmp/be-resource-opt/baseline-classes:projects/neoforge/build/classes/java/main \
  net.neoforged.neoforge.server.threading.region.OwnershipCostProbe 21 expanded \
  > tmp/be-resource-opt/baseline-expanded.jsonl
```

The baseline is `02037058856a6a6c61ebc63224bf7b7e19271eeb`. Its 15 ownership/planner class files were
verified byte-for-byte against the `.57` universal jar. The current common harness is separately compiled
for each version because the internal `dependencies()` result changed from diagnostic Plan to ExecutionPlan.
No Java source checkout, worktree, mod jar replacement or test world change is needed for paired timing.

Use fresh JVMs, the same JVM arguments, and alternating `before/after/after/before/before/after` rounds.
Also run current `expanded` as an ablation/control, not a separately proven speedup. Final records contain
every accepted round, component/full p50/p95 and allocations; summarize as the median of each condition's
three **round quantiles**, not as a pooled-sample quantile. Exploratory earlier runs are retained locally.

The small fixed sample count, JIT/GC/frequency/timer effects and input-specific optimizations limit inference.
Even a large reduction here is **not a whole-server speedup**, and small/light shapes may still regress.
See [architecture](../../region-threading/GENERAL-BLOCK-ENTITY-RESOURCE-OWNERSHIP.md),
[optimization details](../../region-threading/BLOCK-ENTITY-RESOURCE-OWNERSHIP-OPTIMIZATION.md),
[paired records](../../region-threading/BLOCK-ENTITY-RESOURCE-OWNERSHIP-OPTIMIZATION-PROFILE.json), and
[historical first-stage records](../../region-threading/BLOCK-ENTITY-RESOURCE-OWNERSHIP-PROFILE.json).
