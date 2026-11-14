# Canonical BE C3-A：Native Effect Inventory 与 body 前证明阻塞

基线：`3608b60f500fa1429bc5023bc247b26d7e3d7542`。本轮完成 Native 生产调用链的效果清单和真实原始 Hopper 探针，**C3-A 的 body 前安全证明门槛未通过，按停止条件停在 C3-A；C3-B、C3-C 均未进入**。没有实现或启用生产 invocation effect bridge／Coordinator，没有进入 C4／C5／Create。

清单包含 **16 类效果、31 个相关源码文件**；[机器可读 inventory](CANONICAL-BLOCK-ENTITY-C3A-EFFECT-INVENTORY.json) 为每类保存生产来源、原归属、时机、顺序、可观察性、缺失证明、具体行号及源码 SHA-256。它枚举仓库内的 Native 效果通道及 Hopper／炉子的直接调用链，明确标出开放 callback 的边界；不声称静态枚举了外部 Mod、异步 writer、反射或 ASM 的全部行为。

本轮没有新增生产支持。新测试用真实 `HopperBlockEntity`、原始 `HopperBlock` 返回的 ticker、真实 bound wrapper／Level loop／旧 Native Worker／Capability provider 分发，证明**原始函数身份仍不足以封闭效果**。当前完整 core 的 5 项失败中，3 项是尚未修复的原 C1 目标，1 项是上一片的 Native effect visibility，1 项是本片真实 Hopper 未在未知 provider 前完整串行的目标。不能把它们作为“预期成功”；**原始 C1 八项只有五项已由 C2 修复，C3 验收未通过**。

## 1. 实际生产时序：先分清注册、提交与下一阶段正文

真实 `ServerLevel.tick` 的通常顺序为：scheduled block/fluid ticks → chunkSource/spawn/chunk tick → **block events** → entity ticks → **BE ticks** → entityManagement；随后 `EventHooks.fireLevelTickPost` 先发布 `LevelTickEvent.Post`，再调用 `endLevelTick`。依据为本地生成源 `ServerLevel.java:403,462`、[ServerLevel 权威 patch](../patches/net/minecraft/server/level/ServerLevel.java.patch) 及 [EventHooks.fireLevelTickPost](../src/main/java/net/neoforged/neoforge/event/EventHooks.java#L1009)。生成源只作为本地被审计输入，其 hash／行号进入 inventory；没有复制或上传生成的 Vanilla 源文件。

因此，Native BE body 创建的 block-event **注册**可在 handoff 后进入原有 ordered set，正常情况下其 **body** 等待下一个合格的 block-event 阶段，不能在 BE Fence 前主动运行。原 `deferBlockEvent` 的“before vanilla block event phase / same tick”注释不能套到所有 slice，尤其不能作为 Native BE 的提交证明。本轮依据实际调用顺序作此澄清，没有改动生产时机。

旧 Native `tickBlockEntities` 的实际顺序是：

1. server 上 v1 `prepareBlockEntityTicks`，创建并发布一个 Native batch／execution kernel；租赁 Region groups。
2. Worker group 开启 Region owner／Guard、Region entity ownership、Region RNG／mutation ThreadLocal；每个 ticker 先检查 binding、预留 block-update 预算、开启 invocation scope，v1 在 owner Worker 上 `check` 后取得 Permit，再运行完整原 ticker。
3. `submitAndAwait` 在 managed barrier 中服务本 batch 的同步 main-thread computation；最终无论取消、interrupt 或 barrier 失败，都等待 group 的物理返回。
4. `runRegionBatch` 的 finally 先完成 border fixpoint、跨 Region game events，再结束外层 Region phase，drain global mailbox／发布 POI snapshot。
5. Native finally 依次应用 deferred block publication、entity-removal callbacks、BE operations、完整 serial fallback、剩余 LevelTicks/block-event mutations，再关闭 operation queue／kernel／batch。
6. 非 entity-batch 的 `workerServerTasks` 仍留到上述 **Post event 之后**的 level end。旧 Worker 自己在 finally 关闭 Guard／owner 并 `clearRunning`，早于 server 的这些 handoff。

入口和步骤见 [Native driver／group／handoff](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java#L5629)、[physical join 与 handoff](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionTickBatch.java#L476)。这不是新 Coordinator 已实现的时序。

## 2. 效果清单与归属结论

“invocation-scoped”在下表表示来源可追溯到一次原调用，**不表示当前记录已有 ordinal，也不表示允许在任意新边界提交**。真正的 later/phase-end timing 与仅因旧 transport 延迟的 invocation work 必须分别判断；未知归属的 Runnable 不能自动选其中之一。

| 效果 | 真实生产调用点／原存储 | 原时机和顺序 | 对新 Fence 的结论 |
|---|---|---|---|
| 本地库存、cook/burn/cooldown、recipe-used、block/chunk state | 原 `HopperBlockEntity.pushItemsTick/tryMoveItems`、`AbstractFurnaceBlockEntity.serverTick`；直接写 live source／target | body 内立即发生；同调用程序顺序，跨 Region 无 Level 全序 | 必须先证明所有可写根及 callbacks，事后收集 effect 不能回滚这些写 |
| light section/check／block publication | [LevelChunk patch](../patches/net/minecraft/world/level/chunk/LevelChunk.java.patch) → hooks → [RegionDeferredBlockUpdates](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionDeferredBlockUpdates.java#L90) | 全 batch typed FIFO；Native finally 在 removal/operation/fallback 前 drain；`sendBlockUpdated` 还失效 path cache、重算 mob navigation | 可前瞻归属一次调用，但缺 ordinal／跨效果顺序、回调闭包证明；payload typed 不等于 commit 纯函数 |
| 同 Region neighbor／shape／comparator callbacks | [RegionCollectingNeighborUpdater](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionCollectingNeighborUpdater.java#L91)、`BlockEntity.setChanged`、`Level.markAndNotifyBlock` | 同步 stack／方向／chained budget；可能在 body 内产生更多写 | 通知的传递闭包需审计，空间 interior 不封闭任意 Mod callback |
| 跨 Region border／未加载 chunk continuation | [RegionBorderUpdates](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionBorderUpdates.java#L176)、collector `NEEDS_SERVER_THREAD` | border 在全 batch 后按 target 分组 fixpoint；剩余缺 chunk cascade 进入 level-end queue，保留原 stack/index/budget | 原 fixpoint 会创建 propagation batch；不能直接套到一个仍持 lease 的新 prefix 并声称只有一个 active batch；不能重放 BE body |
| block/fluid scheduled storage | [LevelTicks patch](../patches/net/minecraft/world/ticks/LevelTicks.java.patch)：`addContainer/removeContainer/schedule/clearArea/copyAreaFrom` → Native `Queue<Runnable>` | 有 operation 时先在 operation 前 drain，否则 fallback 后 drain；两个 scheduler 共用队列 | 缺 ordinal；`hasScheduledTick` 不读 pending queue；`clear/copy` 跨多个位置。还需保留预先分配的 `ScheduledTick.subTickOrder` 与计数顺序 |
| block-event 注册及 event body | [deferBlockEvent](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java#L4149) → 同 mutation queue → level ordered set | 注册在 handoff；body 是独立后续 event phase，带原 dedup/reschedule 规则 | 可以审核注册的提交，不能因此提前执行 event body 或 drain 整个 blockEvents |
| owned ItemEntity 自身写及 storage removal | Hopper `addItem(Container,ItemEntity)` → `setItem/discard/setRemoved` → [RegionEntityOwnershipScope](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionEntityOwnershipScope.java#L587)／[RegionDeferredEntityRemovals](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionDeferredEntityRemovals.java#L35) | entity 自身 immediately removed；level callback 在 Native finally，publication 后、fallback 前。identity dedup，`IdentityHashMap.values()` 不是 canonical 顺序 | 必须记录第一次逻辑 remove 的 invocation/order，保留 callback identity／level／removed recheck、重复计数和失败政策 |
| entity addition | [ServerLevel.addEntity patch](../patches/net/minecraft/server/level/ServerLevel.java.patch) → typed seam 拒绝 Native Group 后 → [handOffEntityAddition](../src/main/java/net/neoforged/neoforge/server/threading/RegionThreadingHooks.java#L389) | Native Group 不授 addition；通用 handoff 可答 accepted-for-tick，实际 `addFreshEntity` 在 level end | 内置 Hopper／炉子普通 body 不产生 entity，未知 callback 可能产生；有 fallback 通道不证明该 callback 可并行或可提前发布 |
| blocking server computation | [runMapStorageOperation／runOnServerThread](../src/main/java/net/neoforged/neoforge/server/threading/RegionThreadingHooks.java#L718) → [pendingMainThreadWork](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionTickBatch.java#L363) | Worker 等待，managed barrier 在其物理返回前执行 Supplier；结果／异常回到 Worker，close 释放 pending | 不是可挪到 join 后的 commit；否则可能自等待。server thread 运行不自动证明其他 Worker 静止 |
| 完整 BE operation | [BlockEntityOperationRuntime](../src/main/java/net/neoforged/neoforge/server/threading/BlockEntityOperationRuntime.java#L69) → 当前精确 source → [queueOperation](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionBlockEntityTickBatch.java#L93) | batch list，最多4096；`drainOperations` 同时关闭队列。先应用待调度写，再逐 Request lifecycle/identity 重验执行，单个失败不抹掉后项 | 保留同一 Request 与 QUEUED/RUNNING/终态／cancel／weak refs；未来需将“take committed prefix”和“close phase”分开。仓库内未找到 Vanilla tick 或生产 adapter 的直接 submit 消费者，不能把 API 的可达性写成已经发生的内置调用 |
| level-end server tasks | [queueServerTaskInCurrentBatch](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java#L3015) | 具体 in-tree 来源只有 `handOffNeighborUpdateCascade` 和 `handOffEntityAddition`；public @Internal 仍接受任意 Runnable。spawning/chunk/BE 共用 level FIFO，Post 后 drain；失败累计但继续、level close 后计数丢弃 | 语义混合，既无 producer kind 又无 ordinal；绝不能整条提前 drain，未知任务也不能仅凭当前 ThreadLocal 取得提前提交许可 |
| server eventloop／POI debug tasks | `Level.markAndNotifyBlock` → `ServerLevel.onBlockStateChange` → `MinecraftServer.execute` 的 POI add/remove/debug packets | 原 server eventloop；与 batch pendingMainThreadWork／workerServerTasks 均不同。managed wait 不 pump 此队列 | 真正独立调度时机，不能抢到 Fence；普通炉 lit 切换的 POI 类型未改变，此分支不入队。未知 block callback 必须另证 |
| mailbox／scheduled Region tasks | [RegionMailbox](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionMailbox.java#L197)、`runInBlockRegion/enqueueRegionTask`、scheduler | Region 消息下个 target group，global 消息下一 global phase；bounded drain、identity/cancel/due 语义。失败是日志计数政策 | 不是逐 invocation commit。实际 delivery 会使旧后缀证明过期；不能为复用 driver 每 prefix 关闭／重开 phase 或提前 drain 未到期任务 |
| game event／listener | [captureCrossRegionGameEvent](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java#L3994)、[GameEventDispatcher patch](../patches/net/minecraft/world/level/gameevent/GameEventDispatcher.java.patch) | local listener inline；foreign SectionColumn 在 border fixpoint 后转交目标；有原容量拒绝／distance 排序／递归 dispatch | typed event 不认证 listener 安全；目标 callback 也可能产生新效果，旧 delivery batch 不能冒充同 kernel prefix |
| POI snapshot publication | `endRegionPhase` → `RegionPoiStorages.publishLevel` | 只在 outer phase 结束发布，不能当每 ticker 一次 | 保留真实 phase publication 边界，不以拆 wave 重置 phase 来取得方便的提交点 |
| RNG | [RegionAwareRandomSource](../src/main/java/net/neoforged/neoforge/server/threading/RegionAwareRandomSource.java#L37)、[Native Group.random](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionBlockEntityTickBatch.java#L321)；Hopper entity-container 的 `level.random.nextInt`；[Capability fallback 的 Collections.shuffle](../src/main/java/net/neoforged/neoforge/items/VanillaInventoryCodeHooks.java#L231) | Region group 一个流、来源为 world/dimension/gameTime/Region；server callback 用当时 server/default 流。shuffle 是独立 JDK RNG；lazy loot 另有流 | phase 内同 Region 必须延续同实例；重建 group 同 seed 会重放流。不能用 ordinal 消除已消费 draw；炉 award-XP 的 `Math.random` 在奖励方法，非 serverTick，不误算为其正文 RNG |

还有没有被这些 cooperative queues 捕获的效果：Capability provider／handler、Container、recipe／Item 方法、burn-time Event、邻居／listener、attachment/invalidation callback 可以直接写其他对象、发包、调 server executor 或启动异步工作。完整库存方法不是纯 getter，`simulate=true` 也没有 callback 纯度合同；Lease/Permit/Scope 不是 JVM sandbox。清单把这些显式列为 **UNPROVEN_CALLBACK**，没有声称“未观察到队列写”就是安全。

## 3. 真实 ticker 的 body 前判定矩阵

| cohort | 现在实际拥有的证明 | C3 新路径的结论 |
|---|---|---|
| Unknown／Foreign／invalid binding | 无 Worker 安全证明 | 原 ticker 在 body 前完整 serial；ordinal 只定义顺序，不授权限 |
| legacy `admit_block_entities` | 作者按 type 的承诺＋空间规则 | 不提供 exact body／效果／共享根的闭包证明；本轮不把声明升级成新证书 |
| v1 Adapter | server prepare 一次、owner Worker check、作者 whole-body 合同 | 没有可列举的 effect contract 或 Fence 后 fresh generation；保留 v1 的线程／次数／异常合同，不能移动 check、重调 prepare 或把旧 boolean 当 Refresh |
| 原始 active Hopper | 原类／原函数也会调用未知 provider、共享 `IItemHandler`、Container、lazy loot、Item 与通知 | **不能根据函数身份或 interior 认证全 body**；未知 closure 必须在原调用前 complete serial，不能先调用 provider 作为“只读发现” |
| 原始冷却 `cooldownTime > 1` 或 disabled 的 Hopper 分支 | 源码可界定为 decrement/tickedGameTime，或不进入搬运；有成为窄范围候选的可能 | 当前未有 issued original-function token、相应 runtime transform 审计、fresh 分支 guard 及 wrapper／TimeTracker 完整证明。它是待证明的最小内部 cohort，**不是本轮已放行的生产白名单** |
| 受保护炉家族 | 现有 [FurnaceWorkPreparation](../src/main/java/net/neoforged/neoforge/server/threading/region/FurnaceWorkPreparation.java#L111) 的 exact ticker/source／class、recipe/ingredient/items、通知闭包、fresh、alias、budget/reservation | 只在现有 protected driver 合同内成立并继续工作；旧 Native type candidate 不继承这些审计。本轮未复制炉证明为通用准入、未进入 C4 |
| C1 script ticker／test generic body | 测试提供可观察脚本与 callback | 不作为真实 ticker 安全证据，不添加测试类／type 名称白名单。原 C1 源码和预期均未修改 |

具体缺失合同位于 [RegionTickCompatibility.decideBlockEntityTick](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionTickCompatibility.java#L213)、[Native inspect](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java#L6357)、[v1 check bridge](../src/main/java/net/neoforged/neoforge/server/threading/ParallelAdapterRuntime.java#L217) 和 [BlockEntityTickAdapter](../src/main/java/net/neoforged/neoforge/server/threading/api/BlockEntityTickAdapter.java)。它们只提供 type／binding／declaration／adapter decision 及 REGION_LOCAL 描述，不能证明某个 handler 不是另一 Region 的 singleton，也不列出可提前提交的效果种类。source class／namespace／先调用 provider 得到 root，都不能填补此空缺。

## 4. 新的六项真实生产路径探针

[CanonicalBlockEntityNativeInventoryTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityNativeInventoryTest.java) 保持普通 JUnit tag 启用。它创建真实 `HopperBlockEntity`，使用 state 返回的原 ticker，经现有 fixture 的 sparse world／真实 LevelChunk bound wrapper 进入 engine；没有替换 Hopper 正文。一个短期测试 provider 通过实际 `RegisterCapabilitiesEvent.registerBlock` 注册到 IRON_BLOCK，Level capability default method 与真实 `BlockCapability.getCapability` 分发均执行，结束逐 entry 恢复原 provider 表。未知 handler 是可控观察对象，**不假称某个真实 Mod 已被审计**。

| invocation | 实际证明 | 当前结果 |
|---|---|---|
| `unprovenProviderMustKeepTheCompleteOriginalHopperTickOnTheServer(false)` | canonical Level path，provider 恰好调用一次，完整原 Hopper 的 cooldown／库存状态保留；未知 provider 必须在 Worker entry 前 complete serial | **failure**：真实 provider 在 `NeoForge Region Worker`、`REGION_OWNER` 中调用，缺失 body 前 closure gate |
| 同方法 `true` | Furnace Waves driver 对这个未知 Hopper 输入完整 serial；没有借该配置扩 Native 支持 | pass |
| `retainedNativeBridgeCanReachOneCapabilityRootFromTwoRegionsConcurrently` | 两个不同 Region、真实 original Hopper body、两个 target position 得到同一个 handler；Latch 使两条真实 Worker 同时处于 `getSlots` | pass，overlap=2；handler 不写业务状态，不制造数据竞争。证明当前共享根未被空间准入排除，不证明它安全 |
| `retainedNativeProviderTasksKeepLevelEndOrderAndDrainPastFailureWithoutReplay` | 实际 provider 创建两条真实 level-end tasks；第一条已有可观察效果后抛原异常，第二条仍执行；phase return 时0效果，end 后2，重复 end 不再执行 | pass；原异常身份、FIFO、phase-end 时机、恰好一次及 drain-past-failure 保留 |
| `retainedNativeProviderScheduledMutationsKeepClearThenScheduleAndBlockEventTiming` | provider 调用原 `LevelTicks.schedule → clearArea → schedule` 和既有 `blockEvent` seam，body 中未发布，handoff 后仅最后一次 schedule 与event注册保留，重复 end 不增加 | pass；证明旧 transport 的 observable storage/order，未运行 event body，也未证明未来 canonical 顺序 |
| `retainedNativeRegionRandomContinuesAcrossTwoOriginalHopperInvocations` | 同 Region 两次原 Hopper 的 provider draw 通过实际 RegionAwareRandomSource；核对同一 RNG identity 和实际 seed 派生后连续两个 draw | pass；是旧路径连续性保护，**未测试尚不存在的跨 prefix／Fence 新 Coordinator RNG** |

后四项显式使用 retained `explicitPhase`，对旧 driver 的行为作证和保护，不把它们写成新 Coordinator 的 green 验收。新的 serial-gate 失败是完整 Level path。世界仍为有界 sparse mock，不是安装服；wrapper／Hopper／Capability dispatcher／Worker pool／queues 为真实实现。新测试无 sleep，Latch 有界；cleanup 检查 Region/active batch、server scope 与两个真实 Worker probes。原 C1 的内核／生命周期案例也在本轮实际回归。

## 5. 实际命令、JUnit 和原回归

| 实际运行 | 总数 | 通过 | 目标 failure | error / skip | 原1244中本组实际通过 |
|---|---:|---:|---:|---:|---:|
| 完整 core：Fixed、Resource OFF | 1291 | 1286 | 5 | 0 / 0 | 1244 |
| 定向：Fixed、Resource ON | 225 | 220 | 5 | 0 / 0 | 178 |
| 定向：Persistent、Resource OFF | 225 | 220 | 5 | 0 / 0 | 178 |
| 定向：Persistent、Resource ON | 225 | 220 | 5 | 0 / 0 | 178 |

每组47个 canonical-related invocation＝C1的31＋C2的8＋上一片effect的2＋本片inventory的6；其中42pass／5failure。不是配置重跑后新增188项。定向组另有原178项全部通过；完整 core 中原1244项本轮实际重跑并通过，XML testcase multiset 与上一片逐项对照，仅规范化原 Ingredient display-name 对象 identity 后缀。上一片41项的相同4项失败也逐项核对保留；没有借历史结果抵扣本轮执行。

四条 JUnit 命令均真实 **exit1**；5项failure均为正常目标 AssertionFailedError，0 error／skip。新未知 provider 断言没有被异常捕获包装为成功。Fixed／Persistent、Resource OFF／ON 的实际 pool／Lease 路径仍由原测试及本片实际跨 Region Hopper overlap 验证；waves=false/true 的新原始 Hopper gate 也确实各执行一次。

实际 runner 将完整命令、退出码、冻结源码 hash 和 raw XML 存到本地 `tmp/be-canonical-c3a/`，所有run名唯一。主要命令为：

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 \
  -PforgiaVersion=21.1.250.60-beta :tests:junitTest
```

定向矩阵使用 `--tests '*CanonicalBlockEntity*Test'` 以及原 Adapter／Furnace／Resource Ownership／Scratch／execution kernel／Native chunk iteration／Task 等9组pattern；完整参数数组在 final validation 收据逐条保留。对应 property 为 `-PfurnacePersistentWorkers=true`、`-PfurnaceResourceOwnership=true` 或二者，全部串行运行。新增六项单独复现用 `--tests '*CanonicalBlockEntityNativeInventoryTest'`：当前6项／5pass／1failure／0error/skip、exit1。

Formatting／license 在新测试格式化后及最终full-core命令中实际检查通过（format命令exit0）。本轮随后串行执行 `:neoforge:genPatches`、再单独执行 `:neoforge:applyPatches`，两条命令均exit0，原patch hash未变。`git diff --check`、本地链接和inventory调用点／源码hash检查通过。

探索期 `original-hopper-probe` 实际6项／5pass／1同一未知provider目标failure，exit1；fixture编译／原始ticker／provider分发首次即成功，没有另一个被隐去的fixture失败。格式化后冻结源码并重跑完整最终矩阵，未用探索结果替代最终验证。inventory生成脚本首轮因一个patch缩进匹配不符中止（未生成清单），改为匹配唯一调用符号后成功；此脚本检查不构成JUnit结果。

本轮冻结的 **2384 个原 Java／patch／build/config 文件，包括原 C1/C2/上一片 effect 测试，hash 全部未变**。没有修改旧测试预期、过滤 tag、改变 admission／默认开关／资源预算／operation容量，亦未修改新旧 driver 或 Adapter。原两未跟踪日志逐 bytes/hash 保留；`.pi/`、`tmp/`、运行日志及生成源不进入提交。

## 6. 触发停止的边界与最小可行解决方案

这里的阻塞比“给 Runnable 加来源”更早：**调用 provider 本身就是任意代码，不能为了知道 root 是什么先调用它，然后才决定整个 Hopper 能否开始**。新原始 Hopper 探针已排除“只是 C1 synthetic ticker 有这个问题”的解释。两个 Region 可以得到同一根；原 `simulate` 和 descriptor 没有任何可读元数据能在调用前排除这一点。相似边界还包括 Container／lazy loot、recipe/Item/event 与动态 notification closure。

下一步的最小内部合同应先满足下列可审核条件，才能让 C3-A 通过并进入 C3-B；这些是**设计要求，未实现的生产代码**：

1. 核心签发窄范围的 exact-function／exact-source／fresh-generation 证据，先只考虑源码可封闭的原始分支，例如已冷却或 disabled 的 exact Hopper；完成相关 runtime transform、wrapper／TimeTracker、branch／binding／notification 审计。未知 provider、Container、recipe、声明或v1输入在 body 前保持完整 serial，不能通过调用 callback 来“发现安全”。这不是新增公开 Generic Adapter 协议，也不重用 `minecraft` namespace 作为证书。
2. 将现有 core 生产效果入口分成明确 kind／boundary 的 typed 操作：例如某个 audited neighbor continuation／entity storage publication。归属 key 至少为同 kernel 下 `(phase identity, Ticket identity, canonical ordinal, current proof generation, per-invocation effect index)`；数据记录不授新 Lease/Permit，禁止未知 Runnable 自动成为 Fence commit。真正 phase-end／eventloop／mailbox／后续 event body 仍由原 scheduler 执行。
3. 先证明 direct writes／读取、同步 handoff 和所有 callbacks 已封闭，再允许 Worker prefix。事后给已发生的直接写补 ordinal 没有补救效果；不能 rollback／重跑。必须明确跨类效果的因果顺序，不能仅分别排序各队列；scheduler 的 preallocated subTickOrder 也需单独证明。
4. 在同一 batch／kernel 内提供 prefix effect 的 take/commit 与最终 close 两种不同操作，保留原 Request/task 状态机和未开始后项；Region outer lease 延长至 physical join＋必要 commit 后才释放，Guard/Scope/Permit 已退出的检查不能冒充 physical join。Region RNG 由 phase 保留同一实例，不能按 wave 重建同 seed。
5. 先在真实原始 ticker 上独立验证该窄 cohort 的 body 前准入、未知 closure 拒绝、fresh 分支／binding 失效与实际跨 Region overlap，才能使 C3-A 的准入证明门槛通过。随后 C3-B 实现 bridge 并验证 visibility/order、phase-end时机、必要commit失败、取消/卸载及跨prefix RNG；再由 C3-C 实现cursor／完整Fence／fresh suffix，并要求原8项全部转绿。这些工作有明确的实现路径，本片没有证明它们永久不可能；当前也没有把尚未建立的合同当作已经具备。

保留已有独立 Native/Furnace driver 不是新路径的安全证明；本片没有整体关闭 Worker 来绕过验收。现有 protected Furnace、Native 和真实跨 Region并行仍运行，默认与准入均未改。C3-B／C3-C 未进入，C4／C5／Create 未进入；Resource Ownership MSPT 工作仍冻结。没有 installed-server、GameTest、真实 Mod pack、Create/Mixin、磁盘 unload/reload、soak 或性能结论。
