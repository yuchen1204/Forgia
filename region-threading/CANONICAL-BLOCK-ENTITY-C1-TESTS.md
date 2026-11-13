# Canonical BE Coordinator C1：Sequence、Serial Oracle 与 Fence 行为测试

基线为 `f74fb11b5e243ce3c0979e99c51d37e76c6d7f86`。本轮落实[架构计划](CANONICAL-BLOCK-ENTITY-COORDINATOR.md)的 C1 测试阶段，新增 31 个可执行案例与共享夹具。**四种配置下均为 23 项通过、8 项目标契约失败；这不是全绿结果，也没有实现生产 Coordinator。** 红测直接保留 canonical 断言，不捕获断言当作成功，不加 `@Disabled` 或默认排除。

## 1. 实际文件与执行路径

| 文件 | 职责 |
|---|---|
| [CanonicalBlockEntityTestSupport](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityTestSupport.java) | 真实 Level 串行／计划入口、原 chunk index／wrapper／bound ticker、稀疏世界、真实 worker pool、事件与状态观察 |
| [CanonicalBlockEntitySequenceTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntitySequenceTest.java) | 9 个目标契约案例：全局顺序、Foreign、拒绝前缀、混合输入、Fence 后资格变化；其中 8 项保留红测 |
| [CanonicalBlockEntityFenceTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityFenceTest.java) | 22 个串行 oracle／已保护路径案例：前缀 join、原炉通知、fresh 拒绝、原 v1、异常／取消／生命周期 |
| 本文、README、STATUS | 新结果、范围、复现与限制 |

没有修改生产 Java、Vanilla patch、构建／配置文件、旧测试或 Adapter 模块。冻结并核对 2379 个原 tracked Java／patch／Gradle／TOML 的 SHA256；新准入、API、默认开关、资源预算、Furnace 审计算法和提交函数均未改变。临时脚本、原始 XML、日志与收据位于本地 `tmp/be-canonical-c1/`，两个原未跟踪日志保留。

测试走的真实链路是：`Level.tickBlockEntities` → `RegionBlockEntityTickPlan.collect` → 原 `RegionThreadingEngine.tickBlockEntities` → Native group／fallback 或 Furnace prepare／fresh／reservation／wave／commit → 已发布的 Ticket／Permit／Guard。混合输入另用 `plan.accept`／`executeAndAwait` 驱动同一真实 driver，单独隔离收集序号问题；没有测试版 Coordinator、图规划器或替代状态机。

Native 测试输入是原 chunk wrapper 下、使用现有已声明类型的可观察脚本 ticker，正文只修改自身 fixture cell。Generic 使用 test-only v1 registration、真实 Chest 实例／bound ticker 和完整 fixture callback；没有新增 Mod BE 或生产支持范围。Furnace 使用真实 FurnaceBlockEntity、原 `FurnaceWorkScope.ticker()`、RecipeManager、ItemStack 与完整原 `serverTick`，实际检查消耗、产物、lit／cook counters、components、recipe 使用及通知。Native 脚本不等于重测真实 Hopper 物流；Generic fixture 不等于真实 Mod 适配。

## 2. Vanilla Serial Oracle 与事件证据

Oracle 在没有安装 Region engine 时调用**同一实际 `Level.tickBlockEntities` 方法的原 iterator／serial 分支**。fixture 通过 Level 的实际注册方法构造 ticker list，保持真实插入顺序；不先按 sequence、Region 或 Chunk 排序，也不根据 worker planner 算参考结果。

Oracle 的状态对照覆盖 fixture 的 cell、炉三槽及 components、litTime／cookingProgress／cookingTotalTime、recipesUsed 和 block state；Fence callback 另记录它实际观察到的后缀状态。它不是任意世界对象的深快照，也没有对每个 ordinal 导出全部世界状态。

为记录完整原炉 ticker 的进入／返回，只有 oracle 在原 Level list 的相同位置使用透明观察 wrapper，转发原 `tick/isRemoved/getPos/getType`，finally 恢复仍在 list 中的原引用。它不分拆正文、不改变 eligibility 或原 ticker 内函数。新测试还单独验证 frozen phase 移除 removed ticker、fresh `onLoad` 失败不重试，以及 finally 将 pending ticker 发布给下一 phase。

Worker 分支保留 `InactiveProfiler.INSTANCE`。生产代码对 active shared profiler 的否决没有被绕过。Furnace 观察使用已有 body observer，并在当前 Scope 的既有 body-end callback 外记录事件，始终调用原 callback；不替换原炉 ticker、不省略 hook／validator。Native driver 中的真实原炉只观察其必要通知，未把该路径的通知时间冒充完整正文返回。

每条事件记录单调 index、ticker id、action、实际线程、native-worker 标记与当前 `REGION_OWNER`／`RESOURCE_SCOPED` 模式。独立候选的 body 可以重叠，不要求 OS 按 ordinal 开始；断言针对 Fence、依赖与提交的 happens-before。状态与事件输出到 XML 的 `C1_TRACE / C1_STATE / C1_METRICS`。

- Fixed：`ControlledExecutor` 只延迟真实固定池的任务交付，并提供明确 rejection 注入；仍由原线程工厂、注册、Worker Scope 与 task runner 执行。
- Persistent：真正构造原 persistent pool，保留 deques／stealing；由正文内 Latch 控制交错，未用固定池替代。fixture 核验实际 pool 的 workers 字段，并记录 pool 模式。
- 取消：在正文阻塞时观察真实 driver 所等的 completion latch，透明转发它的 `await/countDown`；控制线程只有在服务器**实际进入 physical join**后才放开正文。
- 生命周期：仅在真实 lifecycle write lock 成功取得后发出 Latch；正文／commit 阻塞时，cleanup 必须仍未返回，释放后再核对事件先后。

没有 `sleep`、定时 park 或依靠时长猜测并发的断言。Latch 的 10 秒超时和 JUnit 120 秒超时只用于暴露挂起，不作成功的时序证据。方法条件等待持续检查真实 barrier。

## 3. 8 个保留失败及其含义

| 目标断言 | 实际旧路径事件／状态 | 案例数 |
|---|---|---:|
| Native 的 `A → U → B`，U 为完整未知 Fence | A、B Worker 都先结束，U 才执行；U 观察到 B 已 tick | 1 |
| 完全串行输入也保留跨 Chunk list 顺序 | list `A(chunk1), U(chunk4), B(chunk1)`，fallback 为 `A,B,U` | 1 |
| Foreign 保留实际插入位置 | oracle `A,B,U,C`；Native 为 `A,B,C,U`，Furnace 为 `A,U,B,C` | 2 |
| B 在正文前被 v1 check 拒绝，不能继续 C | 原 Native group 为 `check A/body A → refuse B → check C/body C → serial B` | 1 |
| Native/Furnace/Generic/Unknown 混合输入不能越过 U | Native driver 的后缀 N1 及炉状态在 U 前已变化；Furnace driver 的对应混合输入通过 | 1 |
| U 能激活当前 phase 中稍后的 chunk | oracle 在 U 后 tick B；两条旧 collect 路径均在起点丢掉 B，最终 `B=0` 而不是 1 | 2 |

这些是实际执行后的 AssertionFailedError，非缺类／编译失败，也非测试只打印 planner 模拟 waves。四种配置的失败集合相同；纯串行顺序、Foreign 与 eligibility 用例不依赖哪个 Worker 恰好先完成。测试中的旧 driver Worker 数断言同时证明红测确实触达预期旧执行桥；未来新路径允许保守串行化旧 v1 entry，不能把这些旧路径证据当成必须保留相同并发数量的承诺。

最后一项是串行 oracle 相对于当前 chunk-indexed collection 的语义差异，不直接证明普通生产世界一定会在 BE callback 中激活 chunk。它具体说明：将 phase 起点资格结果沿用整个 phase，不能满足设计中 Fence 后重新观察资格的目标。

红测类带 `canonical-be-contract` tag，**当前 Gradle 没有排除该 tag**。因此通常的完整 `:tests:junitTest` 也会返回非零并显示这 8 项，不能对外描述为全部通过。C2 及后续实现应让目标断言转绿，不应把观察到的错误顺序改成预期值。

## 4. 已通过的 23 个新案例

| 新覆盖 | 实际断言 |
|---|---|
| 原串行 oracle，3 项 | 跨 Chunk／Foreign／原炉／Generic 的实际 list；frozen 移除；onLoad 失败、pending 与 finally |
| 两条 driver 的真实 Worker 前缀，2 项 | 两个正文在不同 Worker 同时受 Latch 阻塞；未知完整 ticker 要等双方返回；Furnace 的原序通知先于 Fence |
| Fence 后 fresh，2 项 | 同一个 RecipeManager 换为未知 recipe 实现，后缀回到完整服务器 ticker；库存 attachment 与 live components 改变不能继承旧成功；与原串行完整状态相同 |
| 原 v1 Generic，1 项 | 一次服务器 prepare、每个 owner Worker 一次 check；两 Region 原 callback 确实重叠、各执行一次 |
| 已开始 Native 原 ticker 失败，1 项 | 自身效果保留一次；不重放；同组未开始后缀 CANCELLED、其他已执行项完成 |
| Fence／commit 失败，2 项 | 未知回调产生效果后抛原异常：保留已提交前缀、后缀不开始；首个炉 change callback 失败仍完成其他必要 commit，不再执行正文 |
| managed wait 取消／interrupt，4 项 | Native／Furnace 两条真实 driver，保留中断状态；physical join 前权限不释放，正文确实完成后才返回 |
| unload／shutdown，4 项 | 两条 driver 均等待已发布 phase；Worker 重入 cleanup／shutdown 立即拒绝；原 Scope／必要提交／未知后续 ticker 完成后 cleanup 才返回 |
| commit 期间 unload，1 项 | 原炉服务器 `setChanged` 阻塞时 active phase 与 outer Region 仍保留，全部必要通知结束前不能卸载完成 |
| 明确 fixed-only rejection，2 项 | 原任务在服务器完整执行一次，不计 native completion；此项在 persistent 参数组中也明确创建固定池，不冒充 persistent rejection |
| Furnace driver 混合输入，1 项 | Native fixture／原炉／Generic／未知 Fence 的完整状态等于 serial oracle，前后炉通知与正文满足 Fence 顺序 |

Fresh recipe 测试比较 oracle 实际发生的全部 `matches` 回调，包括原 ticker 的重复查询；没有人为减少到每炉一次。Furnace Resource ON 时还检查真实 issued Lease 数，OFF 为零。清理检查 actual active batches 为空、running Regions 为零、kernel Ticket／Guard map 清空、active Permit 为零且 phase closed；活着的两个真实 Worker 另跑 rendezvous probes，检查旧 Permit／Furnace／Resource ThreadLocal 未残留。

body／fence／commit 异常测试检查原异常身份或 cause，已开始的效果与调用次数不被抹掉。`CancellationException` 只用于注入 managed wait 离开，不是把 canonical 断言失败捕获当作成功。

不同 worker／resource 配置是对相同 31 案例的重跑，不累加成 124 个新增测试。31 个案例中 26 项实际选择该组 worker 模式；另 3 项只驱动无 engine 的串行 oracle，2 项明确固定池 rejection。

## 5. 实际最终运行结果

| 实际运行 | 总数 | 通过 | 目标失败 | error / skip | 其中实际重跑的原有案例 |
|---|---:|---:|---:|---:|---:|
| 完整 core：Fixed、Resource OFF | 1275 | 1267 | 8 | 0 / 0 | 1244，全部通过 |
| 定向：Fixed、Resource ON | 161 | 153 | 8 | 0 / 0 | 130，全部通过 |
| 定向：Persistent、Resource OFF | 161 | 153 | 8 | 0 / 0 | 130，全部通过 |
| 定向：Persistent、Resource ON | 161 | 153 | 8 | 0 / 0 | 130，全部通过 |

四组均保存独立 Gradle 命令、退出码、原始 JUnit XML、测试源码冻结 SHA256。8 项非零失败均落在上述同一目标集合，所有其余新／旧案例没有 failure、error 或 skip。完整 core 的原 1244 案例逐项与上一片 XML multiset 对照，使用本轮实际执行结果确认；不是引用历史成绩。

格式检查仅本轮三个新 Java，license 使用现有 task。Gradle flags 为 JDK21.0.13、`--offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta`；资源／worker 变化只用于测试 JVM property。

前期探索失败也保留：Mockito 嵌套 stubbing；active profiler 触发现有串行否决；邻接 loaded chunk 不全导致 Furnace 安全回退；误 import package-private batch 的编译失败；原炉重复 recipe 查询次数和 lifecycle 线程 managed wait 的夹具断言。均只修测试夹具，未更改生产安全判断。这些轮次不计最终通过数；最终四组使用同一冻结源码。

## 6. 复现

只跑 22 个已保护行为（当前预期全通过）：

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 \
  -PforgiaVersion=21.1.250.60-beta :tests:junitTest --tests '*CanonicalBlockEntityFenceTest'
```

直接跑目标契约（当前 9 项中 8 项真实失败、1 项通过）：

```sh
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 \
  -PforgiaVersion=21.1.250.60-beta :tests:junitTest --tests '*CanonicalBlockEntitySequenceTest'
```

分别加 `-PfurnacePersistentWorkers=true`、`-PfurnaceResourceOwnership=true` 或二者，得到其余三种配置。所有测试和 Gradle／服务器进程串行运行。Local runner／matrix assessor 保留于 `tmp/be-canonical-c1/`；assessor 将已知红测记作目标失败，不把 Gradle exit1 转写成全部 PASS。未来修复后不能继续使用“必须还有 8 项失败”的阶段收据作为验收条件。

## 7. 尚未覆盖的边界与后续

- 生产 Coordinator、全新 Inspect／Refresh 注册协议尚不存在。本轮证明旧桥的行为与缺口，不能称新 Coordinator 或 Generic Fence 后 fresh 授权已经通过。
- 没有构造一个合法的旧 Furnace `A(X), B(X,Y), C(Z)` 并在 B fresh 拒绝后观察 C 提前的完整生产场景。Native late-refusal 已用真实路径重现；“连续前缀”完整新 wave 语义仍需后续新 Coordinator 测试，未用 planner 模拟代替。
- 没有验证 Native 和 Furnace 在同 wave 的混合 lease／跨桥 shared-root 与 typed-effect commit ordering。现有 driver 仍互斥，混合输入不等于混合 Worker 模式共存。
- 取消覆盖 managed wait 的 CancellationException／中断与正常 retirement，不是新增一个任意 queued-task cancel API，也没有强杀线程或认证未知异步 writer。
- 生命周期是实际 engine cleanup／shutdown 方法和 barrier，世界存储为稀疏 fixture；没有真实磁盘 save/unload/reload、安装服、Hopper 连接管线、长期 soak 或真实 Mod pack。
- 本轮没有运行 Create 集成、unknown Mixin 负对照、GameTest 或新安装器。既有 core 的相关原测试属于本轮重跑范围；不能把原安装服或上一片结果补入未运行的项目。
- 没有性能测量或收益主张，Resource Ownership MSPT 优化继续冻结。原并行准入、库存／Capability 支持、多 home 与 Create 独立模块均不扩大。

下一实施片可从真实 Level membership 的 canonical ordinal 收集与纯诊断开始，针对 Foreign／Chunk 分组／资格 red tests 转绿；未知 Fence 的调度修复与新 fresh 协议按原计划另片推进。C1 没有因发现红测而删除旧路径、修改原炉行为或进入生产实现；本阶段也未提交、推送或部署。
