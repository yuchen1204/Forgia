# C6 — First trusted Mod execution closure

Baseline: `835ca1408e7eb4f49e4441963bb283a65d32812f`.

C6.1 selects and audits a real upstream ticker. **No Mod Worker authorization exists at this checkpoint.** Subsequent stages must establish runtime evidence, Core-only manifest construction, existing Claims conversion and original unstarted-Ticket admission before claiming a positive result.

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

## Stage status

- C6.1: original target selection/call-path/resource/writer audit complete; runtime and ownership prerequisites remain explicit. No grant.
- C6.2: not implemented at the C6.1 checkpoint; investigating trustworthy runtime evidence and the constrained Core verifier.
- C6.3: not implemented; no Mod Claims/Reservation/Lease minted.
- C6.4: not implemented; zero proven Mod Worker bodies/overlap. No real Mod Worker tests passed yet.
- C6.5: not completed; final integration/matrix must report actual outcomes only.

Native Hopper/Furnace preparation, existing claims/reservation, admission conditions, canonical execution, defaults/budgets and previous tests are unchanged at this source-audit checkpoint. Original private indexes, failed inventory JSON escaping attempt and raw decompilation/disassembly remain local under `tmp/be-trusted-mod-closure/`. No installed-server validation or MSPT benefit is claimed.
