# BE Resource Ownership 性能优化（第二阶段）

版本：`21.1.250.58-beta`；基线为 `020370588` 的 `.57-beta` 生产实现。
本阶段只优化内部资源表示、当前准备过程与执行规划，**没有扩大 BE 准入范围**。
`enableRegionThreadingBlockEntityResources`、Furnace waves 和 persistent workers 仍默认关闭。

## 1. 审查确认的成本

| 原实现 | 成本 | 本次改动 |
|---|---|---|
| `FurnaceResourcePreparation.capture` | 同一目录中 R 个 READ 对象，在 N 台炉子上各分配 Identity、Binding、map entry | 每次新 `FurnaceWorkPreparation`、每种 cooking type 构建一个 `SharedReads` publication；N 个单元引用它 |
| `Claims.Builder`／`Claims.unit()` | builder map → immutable map → Resource／read/write Set，重复展开共享 footprint | 局部声明与共享 READ 分离；诊断 `unit()` 仍能展开完整集合，执行规划不展开只读 publication |
| `BlockEntityWorkPlanner.plan` | reader/predecessor index Set、union-find、连通分量；屏障向全部先前单元建边 | 新 `planExecution` 只追踪 writer depth 和最大 reader depth；原诊断 planner 保留 |
| `tryAcquireAll` | 对 N 份共享 READ 重复登记和调用同一个 publication 谓词；同 home 重复检查 | 同一个显式 group 在每次 reservation 只登记／验证一次；所有不同的 proof 和普通 Binding 均保留 |
| `Lease` | 每个 lease 复制 permissions、local identity Set、position map | lease 引用不可变 Claims；少于等于 8 个局部资源直接按引用比较，较大声明建一次 identity lookup table |
| fresh／planned resource set 比较 | 同一对共享集合每单元重复比较 | 仅在当前波次局部 memoize 两份不可变身份集合的相等性，不 memoize 验证结果 |

普通小声明仍使用 builder map、immutable snapshot、Binding 与 planner Unit，没有声称已经零分配。

## 2. 共享 READ 不是动态验证缓存

`SharedReads.capture(domain, instances, current)` 是**一份当前已审计 publication 的不可变身份集合**。
它不带 local home，不带 position lookup，不提供 WRITE。调用方的一个纯谓词证明的是整个 publication；
不是把一组任意对象的不同 attachment callbacks 悄悄替换成一次调用。

Furnace 的安全依据保持为：

1. 每个 phase 初始准备创建新的 `FurnaceWorkPreparation`。
2. 每个实际 wave 在取得外层 Region leases、交付 mailbox **之后**，再创建新的 preparation。
3. 在这个 fresh preparation 内，仍逐个审计整个 cooking catalog、ordinary ingredients、所有相关结果和组件，
   捕获 OR children 各自 cache 的真实 stack 身份；同 RecipeManager 并不允许跳过这些检查。
4. 同一 fresh pass 的同类候选共享这份 READ 表。每次 reservation 检查其 manager publication 谓词；
   新旧集合必须与原计划相同，且原有实例、ticker、chunk、燃烧状态、库存和通知周边检查仍成立。
5. 下一个 wave／tick 没有继承成功验证的 cache；串行 fence、mailbox 和 reload 后仍重新证明。

全 phase 库存 alias 扫描仍保留。manager 未换但 recipe result 原地加组件，或同 manager `replaceRecipes`，
均不能靠 group 身份复用获得 worker 权限。group 是内部证明表示，不是新模组注册或执行能力。

### 预算与失败关闭

- 仍是每单元 512 binding、每 phase 131072 binding、2048 单元。
- group **按每个使用单元的逻辑资源数量完整计费**，即使底层只存一份。
  输入重复对象也计入原始数量，不能通过压缩把溢出变成成功。
- 过大 group 仅保留一个上限外的 count witness，不遍历／分配整个输入；不授予截断后权限。
- 不同 home 的相同身份、foreign domain、publication 与任何 WRITE 别名仍使相关证明成为串行 fence。
- 普通 Binding 的每个 validator 仍逐个运行，即使是同一个 Binding／谓词对象的重复声明。
- 同资源集合但不同 group 对象的 proof 仍分别运行；拒绝或异常不留下部分占用或 lease。
- reservation 前后均检查 live／所有涉及的 outer home；worker 的每次 scope 权限仍受实时 Region exclusion 约束。

## 3. 执行规划和诊断图分开

`BlockEntityWorkPlanner.planExecution` 返回独立 `ExecutionPlan(waves, maxWidth)`。

- READ 跟随最近 WRITE。
- WRITE 跟随最近 WRITE 和其后最大 reader depth；同单元 read/write 不依赖自己。
- exclusive fence 位于已处理单元的全局最大 depth 之后；随后以新 barrier 代替旧状态。
- 保留完整 domain/object 或 domain/kind/coordinate 身份比较。
- canonical 输入中的 `A(X), B(X,Y), C(Y)` 仍只能是 `[A], [B], [C]`。

通用 ownership planner **先完成整个 phase 的 home／publication-WRITE 别名检查**。
被接受的 shared publication 在 phase 中不可能被任何可执行单元写入，因此它的 READ 本身没有额外依赖边；
执行 footprint 可不重复展开它。发生冲突的单元仍是完整 exclusive fence，而不是删除访问让它执行。

`Claims.unit()` 保留完整展开表示，供差分和诊断验证。Shadow Work Graph 继续调用原
`BlockEntityWorkPlanner.plan`，保留 dependencyLinks、components 等统计；观测结果仍不授予执行权。
资源开启和旧 Furnace 波次执行均用 compact planner，没有替换诊断字段为虚假的零值。

## 4. 生命周期与异常不变

Region lease、批次 publication、shutdown／Level unload、worker join、server 有序 commit 和释放顺序未改。
没有 worker 资源锁、等待队列、临时申请额外资源、未知回调试运行、回滚或部分效果后的重放。
scope 权限仍为一次性、线程绑定、不可嵌套，不允许 READ 升级 WRITE。

lease 只是共享不可变 Claims 的存储；它自己的 RESERVED／ENTERED／CONSUMED／RELEASED 状态独立，
不会因同一个 Claims 被两个 lease 引用而共享一次性状态。group 的 READ 不得通过 chunk hook 得到世界 chunk 访问。

小表无独立 lookup map；BE position／local-chunk 检查在有界局部 Binding 表中查找。
这可能使未来拥有大量局部 lookup 的调用者退步，不能将本次结果泛化为所有资源形状的收益。

## 5. 前后性能验证

[完整阶段性能记录](BLOCK-ENTITY-RESOURCE-OWNERSHIP-OPTIMIZATION-PROFILE.json) 和
[探针复现说明](../tools/block-entity-resource-probe/README.md) 是数据定义与完整逐轮结果的来源。

采用生产 `.57` 类与优化后生产类，**没有把一个简化算法称为旧生产基线**。
共享对象和任务输入事先构造；每个 sample 内包括：初次 capture、执行 plan、第二次完全 fresh capture＋
resource set 比较、reserve、逐单元 scope／五次 require、close。直接计时完整 resource 机制阶段。

与 `.57` 首次探针相比，新增了 fresh revalidation，故总耗时不能直接与历史
[第一阶段表](BLOCK-ENTITY-RESOURCE-OWNERSHIP-PROFILE.json) 相除。原始探针基线也保留于本地。
对照使用同一新公共 harness，baseline 采用原 expanded 声明，优化版采用生产 shared 声明；
另跑优化版 expanded 输入，以区分表示改变与通用 planner／lease 改动。

覆盖 2/16/64/256 单元 × 4/64/256 shared READ，逐组件和完整阶段记录 p50/p95，记录 coordinator 分配 p50/p95。
正式顺序为 before/after/after/before/before/after，每轮每 shape 21 个样本；下表为各条件三轮 quantile 的中位数，
不是 pooled percentile。正式量时统一加 `-Xbatch`，不与构建、测试或服务器重叠，不以耗时作为测试成败断言。

| 单元 / shared READ | 完整阶段 p50 ms，前 → 后 | p95 ms，前 → 后 | 分配 B/phase，前 → 后 |
|---|---:|---:|---:|
| 2 / 4 | 0.0061 → 0.0092 | 0.0063 → 0.0105 | 18,280 → 15,840 |
| 2 / 64 | 0.0375 → 0.0278 | 0.0595 → 0.0443 | 110,088 → 42,352 |
| 2 / 256 | 0.1955 → 0.0797 | 0.2612 → 0.1152 | 410,568 → 130,128 |
| 16 / 4 | 0.0434 → 0.0467 | 0.1260 → 0.0610 | 124,808 → 79,064 |
| 16 / 64 | 0.3323 → 0.0711 | 0.3717 → 0.1397 | 717,352 → 105,672 |
| 16 / 256 | 1.5487 → 0.0773 | 2.2876 → 0.2125 | 2,662,024 → 192,408 |
| 64 / 4 | 0.1808 → 0.1066 | 0.2691 → 0.2119 | 490,992 → 295,520 |
| 64 / 64 | 1.5818 → 0.1252 | 1.9197 → 5.0182 | 2,815,984 → 321,088 |
| 64 / 256 | 6.7833 → 0.1958 | 7.9264 → 0.2683 | 10,467,920 → 409,872 |
| 256 / 4 | 0.9227 → 0.5592 | 1.0481 → 0.6424 | 1,967,936 → 1,163,792 |
| 256 / 64 | 7.1535 → 0.6250 | 8.5430 → 0.7865 | 11,348,160 → 1,189,360 |
| 256 / 256 | 32.2603 → 0.6980 | 35.5646 → 0.8448 | 42,182,400 → 1,274,032 |

大集合受益显著：256 / 256 的完整机制 p50 降低约 97.8%，分配减少约 97.0%。
但 **2 / 4 的 p50/p95、16 / 4 的 p50，以及 64 / 64 的 p95 存在回退**。
64 / 64 的三个 after round 都在 plan 分段记录到同量级尾部；没有删除异常值，也没有将其断言为已知 GC 或 JIT 原因。
当前小样本、预热、同步 JIT／deoptimization、GC 和频率影响仍在，不宣称已解决所有轻负载／尾延迟问题。

JSON 包含全部 **72 行**正式记录、各组件 p50/p95、allocation p50/p95，另含 12 行 current-expanded 单轮控制。
早期探索的未精简小表／异步编译结果保留在本地，不冒充最终源码结果。

**限制**：所有验证谓词都是 constant true，不模拟真实目录审计、世界状态、Region leasing、mailbox、
派发／跨核争用、正文或通知。单线程 allocation 不含 worker；没有全服 tick／MSPT，也没有整服性能收益结论。

## 6. 阶段计时的覆盖范围

`workgraph` 资源行新增：

- `revalidation`：重新创建 preparation、全批 alias、fresh claims 和身份集合比较；在 reservation 之前。
- `completedPhases/fullPhase`：实际执行的实验炉子 BE phase，从 publication 前至 graph／batch 收尾；
  含初始准备、Region 等待／mailbox、重验证、预留、worker 提交／join、串行 fences、commit 和资源释放。
  不包括 Level 其他 tick 阶段，也不统计因 shutdown／Level 已关闭而在 publication 入口直接退出的路径。

`proofAndPlan` 仍只覆盖初始 `FurnaceResourcePreparation.captureForPlan` 和通用 plan；
新共享表在 catalog 审计中构造，该部分计入既有炉子 `prepare` 和新的 `fullPhase`，
**不能直接拿 `.57/.58` 的 `proofAndPlan` 子指标差值称为节省时间**。
`reservation`、`revalidation`、`prepare` 等是 phase 的子区间，不能与 fullPhase 相加。
这些是累计 diagnostics，需窗口差分，正常保存并不等于性能验收。

## 7. 正确性与交付验证

新增覆盖：10000 组 compact／diagnostic 有向计划差分，2048 fences，有资源的 fence、read/write 重叠、
世界／资源种类身份；1000 组 grouped／expanded 混合 alias 差分，另保留原有 1000 组 pairwise 顺序 oracle。

共享表测试覆盖：每 reservation 重验证、不同 proof 都运行、普通重复 Binding 不省略、grouped/expanded 权限等价、
global WRITE alias 和 homes、不可变快照、重复资源逻辑预算、溢出、chunk 权限拒绝、post-validation 生命周期检查、
抛异常后无部分授权。Furnace 新增同一结果对象在串行 fence 后／下一 tick 原地改组件的拒绝控制。

| 最终验证 | 结果 |
|---|---:|
| 完整核心回归，新资源开关默认关闭 | **1215 项**通过，零失败／错误／跳过 |
| 资源模式＋fixed executor 定向集合 | **106 项**通过 |
| 资源模式＋persistent workers 同组集合 | **106 项**通过 |
| 现有 Create 集成 | **16 项**通过，未扩大准入 |
| 单独 JVM 的实际未知 Mixin 控制，resource＋persistent 开启 | **1 项**通过，保持完整串行原 Tick |
| 全新私有目录默认 GameTest | **174 个必需测试**通过，保存退出成功 |
| root/tests 格式、许可证、`git diff --check` | 通过 |

GameTest 启动附带的非必需 `testFeatureGating` framework status 仍失败：GameTestServer 使用 allFlags，
而该检查要求某些 flag 关闭。它不在 174 个必需 GameTest 内，未计为通过，没有为本阶段修改 feature 实现。

最终安装服为 `.58` 实际 installer、无 helper mod、六台同 chunk 惰性周边原版炉子、两个 persistent workers。
资源模式开启：**441 tick、2640 leases／2640 成功原始 worker 正文、最大重叠 2、12 次加工通知、12 个铁锭**；
正文失败／跳过／提交拒绝和资源预留拒绝均为零，保存退出码 0。
同版本、同 seed／布局／输入的资源关闭对照也完成 **441 tick、2640 个原始 worker 正文、12 次通知、12 个铁锭**，
保存退出码 0，`issuedLeases=0`，新资源阶段计时保持零。两次均为功能 fixture，没有做整服稳态 timing A/B。

该功能 fixture 累计 `fullPhase=488.03 ms`、`revalidation=70.15 ms`、`reservation=25.13 ms`，
初始 `proofAndPlan=64.48 ms`。所有数据均包含冷启动和 fixture 初始化阶段，没有配对稳态窗口，
不能从这些累计计时推导整服收益；特别不能将新的 proofAndPlan 与 `.57` 的旧子指标作直接性能比较。

最终 installer：`projects/neoforge/build/libs/neoforge-21.1.250.58-beta-installer.jar`，**11,017,419 bytes**。
SHA-256：`7d736190ccd2c97c949368a3a57af35a31fe71a5f48e7814517494b78d7da34f`。
本阶段未修改 vanilla 源／patch，沿用已验证的 `.57` chunk 绑定防护。

## 8. 剩余成本和下一阶段

1. 局部 Binding、builder／immutable map、Resource／Unit、整 phase home／alias 表仍分配，未使用 packed arena。
2. 每个实际 wave 的整个 catalog 动态审计、inert 周边和全 phase inventory alias 扫描仍运行，不能在无可靠失效机制时省略。
3. 默认关闭路径、少量共享对象、窄波次、已有多 Region 并行的真实负载仍须测量；合成微秒结果不能替代整服 A/B。
4. 本次无“按历史观测授权”，无跨 tick recipe/result cache；未来任何结构缓存必须明确列出所有失效来源。
5. 不扩展 Hopper／Container／Capability／Create；通知资源闭包、provider/backing identity 和生命周期协议仍须单独审计。
