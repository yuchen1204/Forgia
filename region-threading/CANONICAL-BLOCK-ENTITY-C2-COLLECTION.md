# Canonical BE Coordinator C2：真实 Level Sequence Collection

实施基线为 `f6b48e24bd1c4e57ce8344a784943c04761ed0a3`。本片完成 C2，实际 Level worker 入口改用同一 ticker list 的 phase ordinal，保留原 Native／Furnace 执行桥。C1 的 8 项红测中 5 项已通过，3 项 Native 调度断言仍失败；**没有将这 3 项登记成成功，也没有实现统一 Coordinator。**

## 1. 实际变化

- [Level patch](../patches/net/minecraft/world/level/Level.java.patch)：pending merge、fresh `onLoad`、finally 规则不变；在选择已有 Region plan 后调用 `collectCanonical`。`snapshotBlockEntityTickers` 锁内只复制核心 list 的引用；removed 由锁外观察，再用 identity set 移除，锁内不执行 ticker 的 getter、`isRemoved`、Adapter 或用户 equals/hashCode。
- [RegionBlockEntityTickPlan](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionBlockEntityTickPlan.java)：实际 membership index 是 `0..n-1` ordinal，含本 phase 后来跳过的条目；缓存 admission 只作 binding／declaration 模板，重新构造不可变 admission 副本，不写回 creation sequence。`canonicalTickers` 的不可变引用列表仅供内部测试观察，phase 退出清空。
- [RegionBlockEntityTickAdmission](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionBlockEntityTickAdmission.java)：内部 `withSequence` 复制模板；原 cache 对象、binding 与原 wrapper 保留，实际执行只为本 phase 的 admission 建一套 Ticket。
- [BlockEntityExecutionBatch](../src/main/java/net/neoforged/neoforge/server/threading/region/BlockEntityExecutionBatch.java)：记录输入是否为 canonical，并在串行位置动态检查当前 removed／shouldTickBlocksAt／entities-loaded，不把起点不 eligible 的项永久丢弃。
- [RegionThreadingEngine](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java)：Native 串行 fallback 的所有 chunks／individual admissions 在 canonical 输入下合并按 ordinal 执行；shutdown publication 的完整 serial snapshot 同样保序。Furnace 的原准备／fresh 在 canonical 输入中再查当前 eligibility；原完整 body、资源 reservation 与通知函数不改写。

全局 list 仍只是一份浅引用 membership，**不授予世界访问权限**。没有按 Region、chunk key、位置、type 或 Adapter 重排 ordinal。原 lease acquisition 的 Region 排序仍只用于防死锁，不定义 BE 顺序。

旧 `collect()` chunk-indexed helper 与 `accept()` explicit helper 保留给已有调用者／回归测试；它们不会与 `collectCanonical` 混用，同一个 plan 尝试混用会拒绝。实际 `Level.tickBlockEntities` 的可选 worker 入口已切到 canonical 路径。保留 helper 的 creation sequence 不再混入这一实际 Level 输入流；explicit 测试入口的旧行为不能冒称全 Level canonical membership。

## 2. 资格、pending 与安全边界

本 phase 的 `runsNormally` 在 fresh `onLoad` 后取值，frozen 时只处理 removed，不查 live ticker 的位置／类型、不执行 body。`onLoad` 或 body 新增 ticker 继续进入 pending；finally 发布，下一 phase 才参与。fresh 失败不重试已尝试条目，finally 仍复位。

收集阶段不 eligible 的活 ticker 保留原 ordinal，并使相关 Native chunk 暂不能进 Worker；轮到完整服务器调用时再检查实际资格。Fence 使后项合法时，它仍存在于 phase；使其 removed 时，服务器跳过并清除 membership。此处不缓存一个“将来一定 eligible”的许可，不给替换 delegate 继承 Worker Permit。原 whole-chunk candidacy、边界邻域检查、exact binding 和 v1/Furnace 审计保持。

C2 **没有**给 Native 建连续 cursor。若后项起点已 eligible，旧 Native driver 仍可能提前执行，未知回调也仍可能排在 Worker 后。这是保留的 C3 红测范围，不能用 C2 输入正确推断 Fence 调度已正确。Furnace 仍拥有自己的 flatten／wave 决策，非连续 wave 的 late refusal 等统一调度问题未解决。

## 3. 新增与已修复的测试

新增 [CanonicalBlockEntityCollectionTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityCollectionTest.java)，共 8 个实际 invocation：

| 案例 | 证据 |
|---|---|
| list ordinal 与缓存模板，cache OFF/ON 两项 | 故意打乱实际 list 与 wrapper／chunk creation 顺序，逐条核对引用与 0..n-1，原 cache creation sequence 不变，执行顺序与 membership 相同 |
| 锁外 callback，一项 | getter、isRemoved、body 内检查 `Thread.holdsLock`，正常执行及随后 removed prune 均锁外 |
| frozen，一项 | live getter／body 若被调用直接抛错；实际不调用且 removed 被移除 |
| fresh onLoad pending，waves OFF/ON 两项 | 当 phase 只执行原 A，新增 P 下一 phase 恰好执行一次，onLoad 恰好一次 |
| Fence 移除后缀，一项 | 服务器 U 后 removed 后缀不执行并离开 membership |
| 重复 wrapper，一项 | 真实 kernel 在任何 body 前拒绝 duplicate，Level finally 正确复位，没有替代 Ticket 或 silent dedup |

[C1 目标测试](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntitySequenceTest.java)没有改预期、没有删改断言；它们的文件哈希与 f6b48e24 基线一致。实际转绿的原 5 项是：all-serial 交错 chunk 顺序 1 项、Foreign 两条 driver 2 项、Fence 激活后项两条 driver 2 项。原 22 个保护案例继续通过；mixed Furnace 目标继续通过。

剩余 3 项仍为真实 AssertionFailedError：`nativeUnknownFenceMustFinishBeforeTheLaterRegionBody`、`anAdapterRefusalMustStopTheContiguousWorkerPrefixBeforeLaterBodies`、`mixedNativeFurnaceGenericAndUnknownRespectTheSameFence[ waves=false ]`。未加 `@Disabled`、未过滤 tag、未捕获断言当成功，普通 core JUnit 仍非零。

## 4. 实际验证

| 实际运行 | 总数 | 通过 | 仍失败的 C3 断言 | error / skip | 原 1244 范围的实际重跑 |
|---|---:|---:|---:|---:|---:|
| 完整 core：Fixed、Resource OFF | 1283 | 1280 | 3 | 0 / 0 | 1244，全部通过 |
| 定向：Fixed、Resource ON | 217 | 214 | 3 | 0 / 0 | 178，全部通过 |
| 定向：Persistent、Resource OFF | 217 | 214 | 3 | 0 / 0 | 178，全部通过 |
| 定向：Persistent、Resource ON | 217 | 214 | 3 | 0 / 0 | 178，全部通过 |

新 C2 八项及 C1 31 项合计 39 个案例，每组 36 通过、3 项 Native C3 断言失败；重复配置不累加成新增案例。Full core 的原有案例与 C1 发布时的 XML Counter multiset 对照，本轮实际重跑全部 1244 项并通过。所有最终运行用同一冻结源码、独立原始 XML／命令／退出码；本地 `tmp/be-canonical-c2/` 保存收据与探索失败，历史数字不替代本轮执行。

原 `CanonicalBlockEntityFenceTest` 用 body Latch 证明两条旧 driver 中不同 Region 的两个真实 Worker 同时活跃，再验证 physical join、必要通知及清理。Persistent 组明确检查真实 persistent pool；两个 rejection 案例仍明确 fixed-only。Resource ON 实际核对 issued Lease，OFF 为零。它们证明本片保留的执行桥确有并行，不证明已有统一 Coordinator。

Patch：首次同一 Gradle invocation 中执行 genPatches 和 applyPatches 触发 Gradle implicit-dependency validation，JUnit 未执行；保留该失败后分开调用，`:neoforge:genPatches` 与下一次 `:neoforge:applyPatches` 分别实际成功，只有 Level patch 变化。另一次新增测试误调用 package-private cache 方法导致编译失败，改为使用已有公开只读 admissions 后实际测试通过；没有放宽生产可见性。

第一版矩阵 assessor 把新增 8 项误计为 9 项而停止，raw JUnit 本身为 217 项／3 failure；修正本地计数并为两个参数方法加入唯一显示名后，冻结源码重跑最终矩阵。此处不是生产故障，也不把脚本退出当 JUnit 结果。最终 formatting、license、diff／scope 检查全部通过。

复现四配置：

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 \
  -PforgiaVersion=21.1.250.60-beta :tests:junitTest --tests '*CanonicalBlockEntity*Test'
```

分别增加 `-PfurnacePersistentWorkers=true`、`-PfurnaceResourceOwnership=true` 或二者。全 core 移除 `--tests`。上述包含全部 canonical 案例的命令仍因 3 项未修的 Native 调度断言 exit1；单独 `--tests '*CanonicalBlockEntityCollectionTest'` 可检查本片新增测试。原 helper／API 注册、whole-chunk／boundary、FurnaceWorkWaves、Resource Ownership／Scratch、Ticket／Permit、RegionTaskBatch 与生命周期相关旧测试均在定向／完整 core 中实际重跑。

## 5. 阶段与限制

C2 达到顺序和 collection 门槛后，按本轮授权自动进入 C3 Native effect／Fence 迁移审计。当前授权的顺序为 **C2 collection → C3 Native coordinator → C4 Furnace integration**，取代 C0 方案原表中 Native／Furnace 两片的先后。C3 必须先解决完整 effects 与 v1 fresh 的证明，不能仅包裹旧 worker 方法；若架构性阻塞则停止 C4。

本片没有生产 Coordinator、统一 cursor、新公开 Inspect／Refresh、Create 特判、Facade 变化或 Adapter commit Runnable。Native／Furnace 仍两条旧 driver；没有同 wave 混合 coarse/fine。当前准入范围、默认配置、2048／512／131072 资源预算不变，额外 MSPT 优化冻结。没有真实 Mod pack、Create/Mixin/GameTest、磁盘 IO、安装服、长期 soak 或性能测试，也没有发布新 jar 或部署。
