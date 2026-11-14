# Canonical BE Coordinator C3：Native Effect Bridge 阻塞记录

本片基线为 C2 提交 `c277f1e0e780971acd0c39c9e11925ee0b8bd133`。**C2 collection 已完成；C3 未完成，未启用生产 Coordinator，C4 未进入。** C3 在实际效果桥审计中触发了任务要求的架构性停止条件，随后仅补齐测试、复验与文档。没有将生产入口整体串行化、关闭开关、降低 admission 或改动 C1 目标预期来越过门槛。

**后续状态：** 本文4项失败是 `3608b60f5` 的历史检查点。[C3-A 生产效果清单与原始 Hopper 探针](CANONICAL-BLOCK-ENTITY-C3A-NATIVE-EFFECT-INVENTORY.md)在该基线上新增 body 前未知 provider 应完整串行的真实失败；当前5项失败，原1244项本轮再通过。16类效果清单明确区分原 task transport 延迟与真正后续phase语义，按门槛停在C3-A，C3-B/C3-C/C4未进入。本文第4节的数字不代表最新套件总数。

C2 的实现及四配置历史结果见 [C2 collection 报告](CANONICAL-BLOCK-ENTITY-C2-COLLECTION.md)。其 5 项修复保持通过。当前新增真实 Native level-end 队列的 Fence 可见性目标后，普通 core JUnit **保留 4 项失败**：原 3 项 Native 调度缺口，加 1 项效果可见性断言。该结果是阻塞证据，不是新 Coordinator 验收通过。

## 1. 可重复的真实执行证据

新增 [CanonicalBlockEntityNativeEffectsTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityNativeEffectsTest.java)，2 个 invocation：

| 用例 | 实际路径与结果 |
|---|---|
| `nativeServerTaskMustBeVisibleToTheNextUnknownFence` | 真实 `Level.tickBlockEntities` canonical 输入、原 Native worker／Ticket／Permit，body 调用实际 `queueServerTaskInCurrentBatch`；Unknown 必须看到之前效果的断言失败 |
| `retainedExplicitNativeBridgeKeepsLevelEndTaskTimingAndExactlyOnceExecution` | 保留的 explicit helper／原 Native driver；queue 在 phase 结束仍未执行，到实际 `engine.endLevelTick` 执行一次，再调用 endLevelTick 不重放；通过 |

输入为 `A → Unknown U`。A 的测试正文创建一个可观察 own-cell effect；服务器完整串行运行时直接执行它，Native Worker 按原生产桥将它排入 level-end queue。测试没有替代队列、替代 Coordinator、模拟 planner 或 sleep，也没有通过拦截 AssertionError 报成功。

实际顺序为：

```text
Serial Oracle: A body → effect applied → A return → U sees effect=1
Native driver: A worker body → server-task queued → A return
               → physical join → U sees effect=0
               → endLevelTick → effect applied once
```

错误即使在最后清空队列后仍存在：U 已经观察到旧状态，后补效果不能修复其观测。finally 调用真实 level-end drain、检查 effect 恰好一次、kernel／Region 清理及真实 Worker probes；不会重新执行 A，也不会重放队列来“修复”可见性。最终 AssertionFailedError 直接比较 `effect/applied` 与 `U/body-start` 的事件 index。

此例使用与 C1 相同的 test-only Native script ticker 和现有类型声明；它证明**当前执行桥容纳这条未标注效果队列且缺少 Fence commit 语义**，不证明真实 Vanilla Hopper 会触发这个特定 callback，也不证明任何 Mod 已兼容。FIFO Runnable 的实际 core 通道、线程、入队与 endLevelTick drain 均为生产实现。

## 2. 为什么不能直接把旧 group 包装成 Coordinator wave

| 实际核心文件 | 目前行为 | 缺少的证明／迁移工作 |
|---|---|---|
| [RegionThreadingEngine.queueServerTaskInCurrentBatch / endLevelTick](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java) | 非 entity-batch Region Worker 的任意 Runnable 进入 level-wide `workerServerTasks`；原定 level-end drain | 没有 phase／invocation ordinal、effect kind 或“允许 Fence 前提交”的证据；不能将整条 level queue 的任务任意提前 |
| [RegionBlockEntityTickBatch](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionBlockEntityTickBatch.java) | entity removals、operation entries 与 `Queue<Runnable> regionTickMutations` 属于旧全 batch；operation drain 会 close queue | 不能每 wave 直接 drain/close 后继续复用；需保持原操作状态、异常和所有未开始项，不重建另一套 Ticket |
| [RegionDeferredBlockUpdates](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionDeferredBlockUpdates.java) | 有 typed light/block records，但顺序是跨 Worker 入队顺序 | 没有 `(ordinal, invocation effect-index)`，不同调用的 canonical effect 顺序不能由 OS 入队顺序推得 |
| [Native blockEntityTickGroup](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java) | Worker finally 关闭 guard／owner 后直接 `region.clearRunning()`，随后 group 返回并到 barrier | 新路径要求 coordinator 将 outer lease 留到 physical join 和必要提交结束；复用旧 Runnable 会提前释放 |
| [RegionBlockEntityTickBatch.Group](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionBlockEntityTickBatch.java) | 每 group 根据 seed 新建 Region RNG | 按 wave 重建 group 会从相同 seed 重复起点，必须保留 phase 内 Region RNG 流并验证 draw 顺序 |
| [BlockEntityTickAdapter / ParallelAdapterRuntime](../src/main/java/net/neoforged/neoforge/server/threading/api/BlockEntityTickAdapter.java) | server prepare 一次、owner Worker check；descriptor／Resources.REGION_LOCAL 不列效果闭包，也不提供 Fence 后 fresh 数据 | 不能把旧 prepare 的成功在 Fence 后继续当证明，不能移动 check 线程或偷偷重调 prepare；新路径无完整 fresh 桥的 v1 必须完整 serial |
| [BlockEntityExecutionEngine](../src/main/java/net/neoforged/neoforge/server/threading/region/BlockEntityExecutionEngine.java) | 一个 admission／Ticket、inspect 一次，beginSerial 检查本 kernel 的活跃权限 | 尚无严格内部 reinspectUnstarted，且本 kernel 静止不等于 server-wide 参与式 quiescence；不能仅通过 beginSerial 声称全服务器安全 |

这些是架构边界，不能仅由合成 own-cell tickers 在不同 Region 并行证明。C2 只修输入及保留的串行顺序，没有改变以上实现。C3 也没有将 production method 抽成新 callback 或制造第二个 batch。

## 3. 本轮停止的具体原因与未采用的绕过

关键阻塞是**在原 body 开始之前，现有通用 admission 无法证明它只会产生已经可在新 Fence 边界提交的效果**。当前 Native descriptor 由原声明／注册身份得到，资源描述为 REGION_LOCAL；它没有这类 exact body 的完整效果证明。通用 v1 check 也只返回 decision，不能提供新的 commit callback 或效果类别。仅凭类型、Region、历史成功、此次未见入队都不能补齐证明。

按任务约束，新 Coordinator 中的这类 ticker 应完整串行。为做到这一点仍需先建立受审计的内部选择桥，证明哪些旧目标能在 Worker 上保留，以及哪些必须 serial；本轮没有把所有现有 Native 功能临时关闭，也没有把测试脚本的“只改 cell”属性当生产白名单。这条选择桥尚未完成，因此没有安装一个只满足部分样例的 Coordinator。

以下做法无法满足本次要求：

- 在 Fence 前 drain 整条 level-wide Runnable queue，会提前执行旧 level-end 或其他阶段任务，且没有 ordinal／effect-kind 来区分；第二个保护案例特意保留旧 explicit bridge 的 level-end 时机。
- 等 body 入队后再判定“不可证明”并重跑完整 ticker，会重复已发生效果；不能 rollback，也不能用旧 driver 重跑整个 phase。
- 给每个前缀创建一个旧 Native batch 再创建 Furnace batch，会重复 publication／Ticket 生命周期，并失去整 phase 的卸载保护；RNG、operation queue 与预算也会被切段重置。
- 把所有 generic／native 输入改成 server-only 再移除 Worker 数／overlap 断言，无法证明原桥被保留，也违反当前测试与功能约束。
- 在 Fence 后复用 v1 Prepared identity，或仅将 check 的 boolean 包成 Refresh，不证明共享网络／库存／recipe 未变。

本轮没有尝试这些绕过，也没有进入 C5 Generic Adapter Protocol 或 Create 适配。新公开协议不是本片补丁的隐含内容。

## 4. 实际复验矩阵

| 实际运行 | 总数 | 通过 | C3 目标失败 | error / skip | 原有案例实际通过 |
|---|---:|---:|---:|---:|---:|
| 完整 core：Fixed、Resource OFF | 1285 | 1281 | 4 | 0 / 0 | 1244 |
| 定向：Fixed、Resource ON | 219 | 215 | 4 | 0 / 0 | 178 |
| 定向：Persistent、Resource OFF | 219 | 215 | 4 | 0 / 0 | 178 |
| 定向：Persistent、Resource ON | 219 | 215 | 4 | 0 / 0 | 178 |

每组 canonical 相关案例为 41 项：C1 的 31 项、C2 的 8 项及本片的 2 项；37 通过、4 failure。配置重跑不计为新增案例。旧 C1 sequence 的源码与原发布 hash 一致；C2 已修的 5 项保持通过，8 项 collection 和原 22 项 Fence 保护仍通过。

Full core 的 原 1244 个案例在本轮再次实际执行并全部通过，逐项 XML Counter multiset 与 C2 结果一致；它们包含原 Native、FurnaceWorkWaves、Adapter、Ticket／Permit／Guard、Resource Ownership／Scratch、Operation／Task／Region 生命周期保护。定向配置各实际重跑 178 个 C1 前的原案例，全部通过。所有错误数／skip 数为 0。普通 JUnit 返回非零，因为 4 项 canonical 目标确实失败，没有过滤 tag 或修改异常处理来制造全绿。

不同 Region 的真实并行重叠仍由原 C1 Latch 案例在本轮四配置中实际证明；Persistent 不是固定池替代，fixture 检查实际 workers，Resource ON/OFF 检查实际 issued Lease。新 queue probe 每次只需一个 Native body，它本身不声称证明不同 Region overlap，也不声称任何 world callback 已安全迁移。

实际完整命令、退出码、原始 XML、冻结源码 hash 和比对收据位于本地 `tmp/be-canonical-c3/`。本片 production／patch／build／config／adapter 的 2383 个原基线 hash 全部未变；只有新增测试与文档。Formatting／license／diff 检查通过。没有 Vanilla patch 修改，必要的 patch generation／apply 已在 C2 独立成功，本片不重复生成无变化 patch。

复现新增两项：

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 \
  -PforgiaVersion=21.1.250.60-beta :tests:junitTest --tests '*CanonicalBlockEntityNativeEffectsTest'
```

当前为 2 项／1 pass／1 failure／0 error／0 skip，exit1。增加 `-PfurnacePersistentWorkers=true`、`-PfurnaceResourceOwnership=true` 或二者可切换其他配置；本轮最终矩阵同时包含原相关保护案例。Full core 移除 `--tests`，当前 4 项失败。

## 5. 最终阶段状态与下一安全入口

| 阶段 | 本轮实际状态 |
|---|---|
| C2 | 已实施并独立提交，真实 membership ordinal／Foreign／serial order／动态资格目标通过；不代表整体 canonical 套件全绿 |
| C3 | 进入实际审计和测试；Native effect bridge 触发停止条件，生产 Coordinator 未实现，4 项目标失败保留 |
| C4 | 按依赖未进入；Furnace 仍使用原全局 wave／Fence 决策 |

恢复 C3 需要一个独立可审核的 Native invocation/effect 桥：phase 持 lease 至 join／提交，复用同一 kernel；按原始 invocation 归属验证 typed effects；为不能证明的调用在 body 前选择完整 serial；保留 RNG 与原操作状态机、v1 线程／prepare 合同，并建立 all-before-body、后缀 fresh 与跨 phase quiescence 测试。完成这些证明再实现 cursor 并重新运行所有红测，不能将本片“已知 4 failure”作为未来验收成功标准。

当前仍两条旧 Native/Furnace 独立调度路径，没有统一 phase-level Coordinator，没有 mixed coarse/fine wave。准入、默认开关、资源预算、API/Adapter 注册和 Create 独立模块全部保持不变。未知异步 writer、多 home／跨维度、未参与协议的反射／ASM 写和 JVM sandbox 均未认证；没有真实 Mod pack、Create/Mixin/GameTest、磁盘 unload/reload、安装服、soak 或性能结论。Resource Ownership MSPT 优化继续冻结。
