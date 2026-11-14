# C6 — First trusted Mod execution closure

Baseline: `835ca1408e7eb4f49e4441963bb283a65d32812f`.

C6.1 selects and investigates a real upstream ticker. **No Mod Worker authorization exists.** C6.2 investigation subsequently reproduced an actual runtime-function evidence gap and stopped before a trusted verifier could be implemented. C6.3–C6.5 are not completed. The final results below are refusal/serial observations and unchanged-baseline regressions, not a first Mod Worker milestone.

## C6.1 selection

The local inventory contains 198 distinct mod jar names; 90 have BlockEntity/TileEntity-named classes. Names are a discovery heuristic, not a complete BE census or an admission rule. Four original artifacts were indexed with ASM, including fields, method descriptors, calls, invokedynamic handles and field writers. This inventory describes original jar bytes only; it is neither runtime evidence nor Proof.

| Candidate | Audit finding | Decision |
|---|---|---|
| Create 6.0.10 kinetic bodies | Existing legacy adapter audits many kinetic/behaviour/network classes. Topology, shared KineticNetwork, initialization, recipes, inventory and notification closures need additional protocols. Its original-resource hash/merged-Mixin checks do not prove arbitrary later ASM. | Do not select for first Core closure. |
| GraveStone 1.0.40 GraveStoneTileEntity | The inspected concrete BE has no server ticker. Its inventory/death persistence is not a useful positive Worker ticker target. | Reject selection; do not invent a ticker. |
| Quark 4.1-482 TinyPotatoBlockEntity.commonTick | Server execution always consumes shared Level RNG; jump can register a block event. | Reject first target. |
| Quark CloudBlockEntity.tick | A bounded positive-lifetime server branch only decrements private state, but expiry removes a block, client code consumes Math.random/particles, and Zeta/Quark introduce a broader class/factory/runtime closure. | Reserve alternative; do not grant. |
| Cook's Collection OvenBlockEntity.tick | Rechecks open-container state, fuel/world inputs, recipe/crafting and repeated setChanged. | Reject first target. |
| 方块电玩 / piq_fc_arcade 0.31.0-alpha.61 FurnitureBlockEntity.maintenanceTick | One private integer update before a periodic maintenance branch, one original direct caller, no added library mod requirement. A bounded counter-only invocation has no resource-discovery/provider call. | First target: original furniture ticker, initially constrained to folding stool and counter-only invocations. |

Selected original jar SHA-256: `d4cb3a41cd3d98c99a0118b1e97475b5781106711ac1d4e321d87c4c2506c510` (`game_console-0.31.0-alpha.61.jar`). It declares All Rights Reserved. The upstream jar, decompiled implementation and class bytes are not redistributed. This report records independently written analysis and factual symbols/hashes.

## Original call chain and dispatch

1. Original Level canonical membership → original LevelChunk rebindable/bound ticker → original BlockEntityTicker object.
2. `FurnitureBlock.getTicker(Level, BlockState, BlockEntityType)` reads the actual Level client-side flag and compares the actual frozen furniture type holder. Its noncapturing lambda is issued only for the server/type match; null otherwise.
3. Actual lambda implementation: `FurnitureBlock.lambda$getTicker$0(Level, BlockPos, BlockState, BlockEntity)` checks/casts the source and invokes `FurnitureBlockEntity.maintenanceTick()`.
4. `FurnitureBlockEntity` is final. `maintenanceTick` updates its private int `ticks`, using Java int overflow and remainder semantics. The updated counter is tested modulo20.
5. When the remainder is nonzero, the complete original ticker returns without reading Level, entering another Mod method, RNG, inventory, config, recipe, events or notifications.
6. When the remainder is zero, the ticker reads its Level field, checks/casts ServerLevel and calls `FurnitureService.reconcile(ServerLevel,FurnitureBlockEntity)`.
7. `reconcile` first reads the actual source BlockState/Block. A folding stool is not a WoodenBenchBlock, so that service method returns. The other branch accesses world snapshot flags and shared level SavedData/ledger, resolves furniture identity/part cells, queries chunks/other blocks and may eject seats, remove blocks or destroy a bench. These paths reach ordinary world/entity/item/notification hooks and are outside the first positive candidate.

The initial positive candidate is deliberately **a complete original invocation whose updated counter has nonzero remainder**, not a replacement counter callback, partial ticker, synthetic ticker or copied implementation. Every20th invocation, unsupported block/runtime/binding and unknown callback must remain the full original Serial Fence. The periodic branch is audited and explicitly refused; it is not silently omitted from the closure. No modulo computation may mutate the real counter during preparation.

The actual bound function must be matched, not only the BE class/method label. A copied type, another wrapper/delegate, equivalent-looking lambda or replacement must not inherit identity. Factory recognition must cover its actual runtime instructions and dynamic function linkage; registry type getters cannot be treated as arbitrary read-only providers.

## Resources and writers

| Access / implicit dependency | Actual backing root / writers | Required handling |
|---|---|---|
| Counter-only body READ/WRITE | Private `FurnitureBlockEntity.ticks:I` in the exact original source instance | Core must derive the access from actual runtime instructions; no Adapter-supplied object can substitute for this storage. |
| Counter initial value | Implicit JVM zero initialization in each new source; no explicit counter write in constructor/loadAdditional | Replacement source is a different root and invalidates the prepared invocation. Integer overflow must match JVM semantics. |
| Counter updates | Original-jar census finds exactly one PUTFIELD writer: maintenanceTick. The only direct caller is FurnitureBlock.lambda$getTicker$0. | Original census is necessary but insufficient: runtime transformations, new callers, reflection/unsafe/async access and lifecycle stability need independent treatment. A method name or successful history is not a writer proof. |
| Source identity / binding | LevelChunk actual map, original wrapper/delegate/function, actual Level/position/type, exact final source class | Recapture before body; no load/create BE or Capability query. |
| Factory/type metadata | Actual FurnitureBlock factory, frozen DeferredHolder/type, JVM lambda linkage | Exact runtime closure and actual original function identity; metadata strings grant nothing. |
| Source lifecycle | Original Core Level membership, chunk replacement/removal/unload, shutdown and active canonical phase | Existing outer exclusion must cover execution/join/necessary commit. Unstarted invalidation serializes or skips according to original Level eligibility. |
| UUID identity | Constructor, identity(UUID), loadAdditional; read by periodic reconcile/eject | Not reached in counter-only body. Do not certify UUID/ledger access through the counter proof. |
| World/state/clock/RNG | Counter-only branch has no world, time or RNG read. Factory/wrapper have existing Core checks. Periodic service reads actual state/world and may modify world/entity storage. | Preserve original Core wrapper timing. Do not reserve the whole Level or call a provider to discover a resource. Periodic service remains serial. |
| Inventory/ItemStack/Fluid/Energy/Recipe/Config | No access in the counter-only branch. Destroy/drop/bench maintenance can reach ItemStack, SavedData and world hooks. | No positive certificate for those paths; no dynamic recipe/config/capability closure implied. |
| Events/listeners/notifications/Commit | Counter-only body returns with its local write and no setChanged/effect publication. Periodic world operations may synchronously invoke hooks and deferred effects. | No new effect API/queue. Existing kernel join and commit boundaries still apply; fault tests must not claim arbitrary callbacks are audited. |
| Other threads / writers | Original method-call census has no async counter caller; broader Mod includes asynchronous features unrelated to the counter | Absence in original bytes is not a global sandbox. Unknown transformations, asynchronous writer routes or ownership cannot be accepted as safe. |

C6 must not declare a READ root and upgrade it to WRITE after inspection. Two declarations of the same actual backing root must collide through the existing domain/identity planner; names, wrappers and copied objects are not roots. Cross-level, cross-dimension and inconsistent home claims must refuse.

## Runtime evidence gate

Current C5 APIs do not expose the final defined method bytes. `Class.getResourceAsStream` observes artifact resources; `MixinMerged` only covers particular Mixin provenance. ModLauncher11.0.5 audit APIs expose activities and labels, not a final immutable JVM method snapshot. The source audit examined `ClassTransformer`, `TransformingClassLoader`, `LaunchPluginHandler`, `TransformerAuditTrail`, `ITransformerAuditTrail` and `ILaunchPluginService`.

Audit activities are still useful refusal evidence. They cannot be ignored or confused with bytes. A plugin is invoked before/after the transformer sets; its reported flags determine part of its audit record. A final snapshot must also cover later JVM instrumentation/redefinition. An observer installed at an arbitrary AFTER plugin position is not guaranteed to be last.

A prospective small Core-owned runtime evidence component must therefore observe final class-definition bytes after classloader transforms, bind them to the exact defining ClassLoader/name, have no Adapter publication or proof-construction API, and refuse unavailable/tainted/truncated evidence. A positive route also needs a reliable no-later-redefinition boundary. Unknown agents, attach/redefinition and classloader provenance cannot be silently allowed. This is a prerequisite under investigation, not an implemented grant.

## Core verifier and manifest contract

The prospective Core verifier must derive a bounded executed path from actual bytes, rather than trusting names, expected digest, audit strings, observed accesses or Adapter validators. For the selected initial path:

- Recognize the actual issued factory/function/source binding; check the final source dispatch.
- Independently interpret a bounded, side-effect-free preparation model for the real instructions. Supported effects may only be READ/WRITE of independently owned private primitive storage. Reject any reached unknown call, static state, array, provider, monitor, unsupported instruction, exception handler or unbounded control flow.
- Compute prospective local values without modifying source. If the complete path would reach the periodic service, refuse the whole invocation before any Worker body.
- Check all writer and lifecycle assumptions under existing outer exclusion. Revalidate initial storage/binding after serial callbacks and before reservation/body; source snapshots alone cannot stop a writer.
- Construct the trusted manifest inside Core only after the checks succeed, bound to original level/source/admission/generation. Adapter metadata may only constrain/reject.
- Convert to existing Claims; apply whole-phase aliases/dependencies and original cumulative budgets, original all-or-none reservation, original Lease/Scope and original physical join/necessary commit.
- Grant the original unstarted Ticket only. A C5 Serial Permit already in RUNNING cannot be promoted.

If runtime integrity, ownership or the complete original binding cannot be established, C6 must stop without a positive Mod proof. A prototype bytecode interpreter, mock source, constructed manifest or direct verifier test does not prove real Worker execution.

## C6.2 investigation: runtime-function provenance blocker

The optional definition observer was implemented and tested only as a local experiment. It never entered the Core authorization path, created a trusted manifest or reserved resources. Its source/build/jar are archived under private evidence; the temporary project and settings entry were removed, restoring all production and build bytes to the starting HEAD. It is not shipped as a supported bootstrap/agent feature.

Five actual JVM probes passed after correcting a default-package test name: observation of a named class, defensive copy, loader identity and duplicate-attempt handling; absent observer; attach enabled; duplicate observer; and watch-list overflow. These are component experiments, not Mod tests or authorization evidence. The first build failure from automatically generated Minecraft package annotations and the first fixture failure are preserved.

A stronger adversarial JVM probe then reproduced the missing prerequisite:

```text
HIDDEN_DEFINITION_GAP_REPRODUCED hostEvidence=true originalLambdaEvidence=false
otherHiddenEvidence=false sameNestHost=true differentBodies=true noWorkerGranted=true
```

The original factory-style lambda and an explicitly defined hidden nestmate have the same host, but different bodies and neither produces ordinary transformer definition evidence. Thus host provenance, `isHidden`, interface/method names, original artifact hashes and Mixin labels cannot stand in for actual function linkage. The observer's refusal to return hidden-class evidence is correct. Promoting the named host's bytes into a hidden ticker certificate would be a false proof.

This does not prove that trustworthy lambda provenance is impossible. It identifies the missing implementation: a Core-audited original factory/linkage capture which binds the **actual function object** used in the original LevelChunk delegate to its verified factory/call-site closure without invoking an unproved factory during lookahead. That bridge must also remain valid after rebind/replacement and runtime updates. It does not exist here; implementing its semantics as a Mod-specific `getTicker` call or accepting the Adapter's returned object would violate the requested trust boundary.

The public `maintenanceTick` remains another callable entry. The original jar has one direct caller and one counter writer, but neither census nor a freshness predicate establishes that all transformed, generated, reflective or asynchronous call paths participate in the Core exclusion protocol. An independently complete writer/provenance boundary must be established for the actual supported runtime. Version checks only detect some changes; they do not exclude a writer while a Worker runs.

### Alternative-target investigation

To avoid treating a lambda-only problem as a reason to skip other targets, a second ASM scan inspected **65,912 original classes with zero parse errors** across the 176 non-NeoForge artifact paths used for that scan. It found four directly named implementations of BlockEntityTicker; indirect/dynamically generated implementations remain outside that discovery census.

| Direct named implementation | Additional audit result | Reason no alternate grant was made |
|---|---|---|
| Create `SmartBlockEntityTicker` | Virtual `hasLevel`/`setLevel`, then cast and virtual `SmartBlockEntity.tick`. Smart tick can initialize/post behaviour events, lazy-tick and invoke attached behaviour consumers; Kinetic tick adds effects/config/topology/network paths. | Named function identity does not close source dispatch, behaviour roots, all writers or shared network state. Existing v1 adapter does not supply this Core proof. |
| GraveStone `SimpleBlockEntityTicker` | Dispatches ITickable/IClientTickable/IServerTickable interfaces. The inspected concrete GraveStoneTileEntity does not implement those server tick interfaces. | No meaningful positive target with a mutating closed original ticker; never invent a ticker or count a no-op interface mismatch as the requested result. |
| Vinery `ApplePressBlockEntity` | Implements ticker, but the actual ApplePressBlock factory still returns a lambda through createTickerHelper. Its execution consults inventories/recipes and writes progress; ContainerData.set and loadAdditional are additional progress writers. | Does not bypass actual function provenance; inventory/recipe/menu/other-writer closure is broader. No claim of an audited safe shared handler. |
| Sable `HarvesterTicker` | Virtual hasLevel/setLevel and interface `sable$clientTick`, with shared static behaviour/context and entity-drop helpers elsewhere in the class. | Dynamic interface dispatch and physics/behaviour context require an additional audited closure. It is not the simple server-private-storage target sought here. |

These findings reject the inspected candidates **for this milestone**. They do not certify every method of those Mods or prove that no other target could ever work. The task stops on an unresolved architectural prerequisite rather than implementing a name/hash allowlist, partial ticker, callback probe or another refusal backend under a positive C6 label.

## Actual original-Mod / real-Level observation

A private exploratory GameTest loads the exact original piq_fc_arcade artifact through the normal Mod loader and registers a serial-only C5 adapter through the real startup event. Two actual folding stools are placed a fixed Region width apart in a real ServerLevel under the installed canonical bridge. The original ticker executes through more than two periodic maintenance boundaries per source; the observer only counts bodies and reads the real private counter. It neither calls maintenanceTick directly nor constructs Proof.

```text
C6_REAL_MOD_AUDIT originalSources=2 differentRegions=true hiddenTicker=true
completeSerialBodies=87 counterDelta=87 workers=0 periodicMaintenance=true noProof=true
```

Actual result: **1/1 required**, exit0, no Crash Report. The filtered framework XML has232grouped entries and230skipped entries; those230 are not passes. The ordinary feature-gating check also emits a passed framework row. Original class, actual factory hidden function and complete counter/body exactly-once are observed on the real Level. This is **serial evidence only**. There is no Mod Worker overlap, Resource Claim, Reservation or Lease to report.

The first private init-script attempt failed because Gradle also applied it in buildSrc; adding the same project-existence guard used by earlier validation fixed the harness. Its raw log/result are retained. No production fix or assertion relaxation was needed. All exploratory source, init scripts, command/input hashes, XML, configs, stderr and logs remain local. The original All Rights Reserved Mod jar and decompiled source are not published.

## Authorization and requested failure-case status

There is no Core Mod closure verifier or trusted manifest, so conversion is deliberately absent:

```text
Original registered Mod ticker
  -> original canonical Serial Fence / C5 Core refusal gate
  -> complete original ticker once
  -> original phase retirement

No trusted Mod manifest -> no Mod Claims -> no Mod Reservation/Lease -> no Mod Worker Permit.
```

The original Native/Furnace proof, claims, reservation, outer exclusion, physical join and ordered commit paths remain byte-identical. Their existing tests do not substitute for first-Mod authorization evidence.

| Requested C6.4 case | Actual coverage in this task |
|---|---|
| 1 Single Mod BE Worker; 2 two-Region Mod Worker | **Not executed/not implemented**. Actual original two-Region sources run serial, workers0. |
| 3 shared resource; 4 READ/READ; 5 READ/WRITE; 6 WRITE/WRITE | No Mod manifest converter or Mod grants; no positive Mod resource-sharing claim. Existing Core/Furnace cases are regression only. |
| 7 inconsistent Region Home; 8 Level/Dimension; 9 identity mismatch; 10 ownership change | Existing refusal/resource tests rerun; no new Mod Worker authorization coverage. |
| 11 registration invalidation/unregister; 12 Validator failure | Existing C5/Canonical cases rerun. Exploratory original Mod registers SERIAL; this is not a C6 positive proof/lifecycle test. |
| 13 Worker Admission refusal; 14 executor rejection/delay | Existing Core/Furnace tests rerun; no Mod candidate exists to exercise these as Mod Worker cases. |
| 15 callback exception; 16 Worker body exception; 17 commit exception | Existing exact-once/failure tests rerun; no new authorized Mod Worker or Mod commit coverage. |
| 18 phase close/shutdown; 19 Lease duplicate release | Existing original kernel/resource/lifecycle tests rerun; zero Mod Lease instances. |
| 20 mid-failure exactly-once/effect consistency | Actual original-Mod serial body/counter equality87/87 across periodic boundaries. No fault injected into an authorized Mod Worker; do not infer rollback or Mod Worker exactly-once from this. |

## Final validation

Results below verify the unchanged C5.5 baseline and refusal boundary. They do **not** complete C6.4/C6.5. Machine commands, source/result/log/XML hashes and actual framework limits are in [the evidence summary](FIRST-TRUSTED-MOD-EXECUTION-CLOSURE.json).

| Run | Actually executed result | failure/error/skip | Gradle exit |
|---|---:|---|---:|
| `final-v1-fixed-on` | 419/419 | 0/0/0 | 0 |
| `final-v1-persistent-off` | 419/419 | 0/0/0 | 0 |
| `final-v1-persistent-on` | 419/419 | 0/0/0 | 0 |
| `final-v1-full-core-fixed-off` | 1400/1400 | 0/0/0 | 0 |
| `final-v1-unknown-furnace` | 1/1 | 0/0/0 | 0 |
| `final-v1-create` | 16/16 | 0/0/0 | 0 |
| `final-v1-unknown-create` | 1/1 | 0/0/0 | 0 |
| `final-v1-msc` | 25/25 | 0/0/0 | 0 |
| `final-v1-unknown-msc` | 1/1 | 0/0/0 | 0 |
| `full-game-persistent-on-v1` | 177/177required | 0failures/4NotProcessed | 0 |
| `full-game-fixed-off-v1` | 177/177required | 0failures/4NotProcessed | 0 |

All1400baseline cases and each prior419related case multiset were actually compared to C5.5 raw XML, normalizing only Ingredient object display identity. There are **zero added production tests and zero passed positive Mod Worker tests**. Every relevant configuration reruns the original84canonical cases and confirms Native/Furnace overlap2/2 plus original C5 mixed-phase overlap2/2; this is preserved existing authority.

Two complete unfiltered fresh-world GameTest profiles retain231grouped entries/179uniqueIDs/227passed entries/4NotProcessed each. The four names remain testFeatureFlagPacks, extendPoiTypeTest, customBoatType and testAttachmentSyncManual. They are not executed passes. Original C5 Mod bodies remain serial; save/reload reconstructs25chunks/13BEs/9entities as distinct instances. No private furniture test or original furniture artifact is included in these baseline profiles; the separate filtered1/1original-Mod audit is explicitly serial only.

`full-game-persistent-on-v1`: exit0/noCrash; 63 ERROR/FATAL and 63 WARN lines retained in raw evidence.

`full-game-fixed-off-v1`: exit0/noCrash; 63 ERROR/FATAL and 63 WARN lines retained in raw evidence.

Legacy entity attributes, fresh properties file, optional tag and deliberate permission diagnostics are not suppressed or called zero-error logs. No client/manual/soak/hard-kill coverage is inferred. No installed server or performance A/B was run.

The independent raw JVM witness logs **every** relevant named definition callback and observes the real host, while neither hidden function produces a callback. This evidence is independent of the prototype observer lookup explicitly rejecting hidden classes, its watch list, or any assumed callback name. The earlier lookup probe alone would only establish fail-closed lookup behavior; the independent raw callback witness establishes the missing definition observation.

Actual complete regression commands:

`final-v1-fixed-on`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -PfurnaceResourceOwnership=true :tests:junitTest --tests '*CoreMod*Test' --tests '*CanonicalBlockEntity*Test' --tests '*GeneralBlockEntityAdapter*Test' --tests '*BlockEntityTickAdapterTest' --tests '*FurnaceWorkWavesTest' --tests '*BlockEntityResource*Test' --tests '*BlockEntityExecutionEngineTest' --tests '*RegionBlockEntityTick*Test' --tests '*RegionTaskBatchTest' --tests '*RegionPoi*Test' --tests '*RegionEntityTickPolicyTest' --tests '*CustomFeatureFlagsTest' --tests '*ParallelApiTest'
```

`final-v1-persistent-off`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -PfurnacePersistentWorkers=true :tests:junitTest --tests '*CoreMod*Test' --tests '*CanonicalBlockEntity*Test' --tests '*GeneralBlockEntityAdapter*Test' --tests '*BlockEntityTickAdapterTest' --tests '*FurnaceWorkWavesTest' --tests '*BlockEntityResource*Test' --tests '*BlockEntityExecutionEngineTest' --tests '*RegionBlockEntityTick*Test' --tests '*RegionTaskBatchTest' --tests '*RegionPoi*Test' --tests '*RegionEntityTickPolicyTest' --tests '*CustomFeatureFlagsTest' --tests '*ParallelApiTest'
```

`final-v1-persistent-on`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -PfurnacePersistentWorkers=true -PfurnaceResourceOwnership=true :tests:junitTest --tests '*CoreMod*Test' --tests '*CanonicalBlockEntity*Test' --tests '*GeneralBlockEntityAdapter*Test' --tests '*BlockEntityTickAdapterTest' --tests '*FurnaceWorkWavesTest' --tests '*BlockEntityResource*Test' --tests '*BlockEntityExecutionEngineTest' --tests '*RegionBlockEntityTick*Test' --tests '*RegionTaskBatchTest' --tests '*RegionPoi*Test' --tests '*RegionEntityTickPolicyTest' --tests '*CustomFeatureFlagsTest' --tests '*ParallelApiTest'
```

`final-v1-full-core-fixed-off`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I /home/yc/Minecraft/Forgia/tmp/be-trusted-mod-closure/format-selected.gradle :licenseCheck :tests:licenseCheck :checkFormatting :tests:checkFormatting :tests:junitTest
```

`final-v1-unknown-furnace`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -PfurnaceUnverifiedHookControl=true :tests:junitTest
```

`final-v1-create`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta '-PcreateJar=/home/yc/Minecraft/Forgia/tmp/create-adapter/final-server/mods/[机械动力] create-1.21.1-6.0.10.jar' :create-adapter:junitTest
```

`final-v1-unknown-create`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta '-PcreateJar=/home/yc/Minecraft/Forgia/tmp/create-adapter/final-server/mods/[机械动力] create-1.21.1-6.0.10.jar' -PcreateAdapterUnverifiedHookControl=true :create-adapter:junitTest
```

`final-v1-msc`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -PmobSpawnControllerJar=/home/yc/Minecraft/Forgia/tmp/msc-v3/pack/mods/mobspawncontroller-neoforge-1.21.1-1.2.3.jar :mobspawncontroller-adapter:junitTest
```

`final-v1-unknown-msc`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -PmobSpawnControllerJar=/home/yc/Minecraft/Forgia/tmp/msc-v3/pack/mods/mobspawncontroller-neoforge-1.21.1-1.2.3.jar -PmscUnverifiedHookControl=true :mobspawncontroller-adapter:junitTest
```

`full-game-persistent-on-v1`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I /home/yc/Minecraft/Forgia/tmp/be-trusted-mod-closure/game-private.gradle -Pc5Run=full-game-persistent-on-v1 -Pc5Profile=canonicalGameTestServer :tests:runCanonicalGameTestServer
```

`full-game-fixed-off-v1`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I /home/yc/Minecraft/Forgia/tmp/be-trusted-mod-closure/game-private.gradle -Pc5Run=full-game-fixed-off-v1 -Pc5Profile=canonicalGameTestServer -PcanonicalResources=false -PcanonicalPersistent=false :tests:runCanonicalGameTestServer
```


## Stage status and readiness

| Stage | Actual completion |
|---|---|
| C6.1 | Candidate selection, original call/dispatch/resource/writer investigation and explicit rejection risks delivered. This is not a complete runtime execution Proof. |
| C6.2 | Investigation/prototype/real-JVM counterexample delivered; **blocked before Core verifier and trusted manifest implementation**. Prototype remains private and unshipped. |
| C6.3 | **Not implemented.** No Mod Claims/Reservation/Lease; do not bypass the unresolved proof gate. |
| C6.4 | **Not implemented.** No Mod Worker bodies/overlap; only original-Mod serial observation and existing baseline regressions. |
| C6.5 | **Not completed.** Final audit report and baseline regression review do not constitute the requested first-Mod architecture validation. |

**No ticker has newly obtained Mod Worker authority.** The furniture counter-only branch is a source-audited candidate still under complete Serial Fence; its function/writer provenance is unproved. Periodic furniture maintenance, Create/Quark/Vinery and all other unproved paths remain serial. Existing General/Dedicated declarations and Dedicated audit strings remain requests only. Existing legacy Create metadata/tests do not become C6 Core Proof.

A first Worker-enabled Dedicated Mod Adapter is still not ready, so readiness for a second Worker-enabled Adapter is **not established**. The next prerequisite is the actual original function provenance boundary plus independently stable writer ownership; only after it succeeds can a constrained Core verifier construct a trusted manifest and use the existing Claims/planner/reservation/Lease on the original unstarted Ticket. No Mod-specific certifier callback or proof constructor should be exposed.

All production Java, public API, patches, build/settings, native/furnace preparation/resource/kernel execution, defaults/budgets and existing tests remain byte-identical to starting HEAD. Only documentation and factual evidence summaries are delivered. No second authority, actual Mod support, installed-server verification, Modpack certification or MSPT benefit is claimed. No second Adapter or C7 is started.
