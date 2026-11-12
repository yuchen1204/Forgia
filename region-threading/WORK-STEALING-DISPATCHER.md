# P0：Work-Stealing Dispatcher

版本：`21.1.250.56-beta`。批量派发与半区段窃取已实现，沿用
`enableRegionThreadingPersistentWorkers`，默认仍为 `false`。开启只选择派发方式，不扩大任何 worker 准入权限。

此前 persistent pool 已有每 worker deque 和逐任务窃取；本次增加的是批次级派发器。
旧路径每个任务都选择一次目标 worker、建立一个队列节点并发布／唤醒，新路径把完整批次发布成若干索引区段，
一个批次最多提交与 worker 数相同的 runner。runner 先处理自己区段，空闲后窃取其他区段剩余工作的一半（至少一个）。

## 实现与边界

- `RegionWorkStealingDispatcher` 是包内实现；区段 `[from, to)` 的两个边界编码在一个 `AtomicLong` 中。
  owner 从头部 CAS 领取任务，thief 从尾部 CAS 移除一半，再发布为自己的区段。任务被领取后不再出现在队列中。
- `RegionTaskBatch` 在实际 persistent pool 且原始任务数大于 worker 数时使用新派发器。
  N 个任务仅提交 `min(N, workerCount)` 个 runner。小批次、固定 executor 和自定义 executor 保留原来的提交方式。
- 显式 `addPacked` 的整个 pack 是一个不可拆的任务；pack 内各 group 继续顺序运行在同一 worker，
  保留各自的 region lease、ownership scope、random、失败记录与完成许可。
- 原版熔炉执行波次在 persistent 模式下复用同一派发器。它在已经证明独立的一波正文之间窃取，
  每个 runner 只安装一次 batch scope；每台熔炉仍在正文前安装自己的精确实例 scope。
  非 persistent 模式继续使用原来的静态 lane。
- 原有 directed waves、完整串行屏障、原生类型／配方／组件／通知周边证明没有放宽。
  一个波次的所有 runner 完成后，仍按原始 `results` 顺序在服务器线程提交通知，再释放外层区域租约。

派发器不要求逻辑 runner 固定到某个物理 worker。一个 runner 延迟启动时，它的未开始任务仍可被其他 runner
窃取；同一个已开始的正文不会迁移、重跑或提前完成。单个很重的 group／pack 仍是不可分的临界路径。

## 完成、失败与关闭

`RegionTaskBatch` 仍按原始 group 计数，不能用 runner 数代替 group 数。
开放 barrier 的全部 group 许可在首个 runner 发布前注册；setup 许可保留到调用者结束发布。
即使一个 runner 在其他 runner 尚未提交时窃取并完成了其任务，也不能提前完成开放 barrier。

一个任务失败时，它释放自己的许可，派发器继续处理尚未开始的任务，并保留失败供批次报告。
每个失败任务的原始描述仍会记录；不存在“先有副作用、失败后再串行重试”的路径。
拒绝的 runner 在提交线程执行，仍使用同一套原子领取协议和既有 inline group scope。
`rejectedCount` 计物理提交拒绝次数，一个 inline runner 可以完成多个原始任务。

本次同时修复了关闭过程的两个相关问题：

1. 外部并发 `close()` 调用现在都等待同一次完整 drain，第二个调用者不会因 `closed=true` 提前返回。
   worker 自身的关闭检查在锁外，避免与外部等待它的 closer 相互等待。
2. 固定 executor 的 closer 被中断时，仅中断正在运行的 worker 并继续等待，不再用 `shutdownNow()`
   丢弃已接受队列条目；它们仍持有必须释放的 lease／barrier 许可。调用者中断状态最终恢复。

persistent pool 的窃取扫描也修正为轮转遍历所有其他 worker，避免部分轮转起点扫描到自己并漏掉一个邻居。
spin／park 发布协议与 shutdown 排队 drain 继续沿用。

## 如何启用与观察

在已有 region threading 测试配置中设置并重启：

```toml
enableRegionThreadingBridge = true
enableRegionThreadingPersistentWorkers = true
```

所需实体／BE／chunk 等 slice 仍需各自的开关和准入。熔炉细粒度执行还需
`enableRegionThreadingBlockEntityTicks=true`、`enableRegionThreadingFurnaceWorkWaves=true` 与 fixed layout。
默认值没有改变，也没有修改任何线上配置。

`/neoforge regionthreading status` 的 worker dispatch 行新增：

| 计数 | 含义 |
|---|---|
| `dispatchBatches` | 使用区段派发器的批次数；包括已准入的熔炉波次 |
| `batchedTasks` | 这些批次中的原始不可拆任务数；熔炉波次为原始正文候选数 |
| `batchRunners` | runner 提交尝试数；可以包含被拒绝而 inline 的 runner |
| `batchSteals` | 成功窃取区段的次数 |
| `batchStolenTasks` | 被转移的任务索引数量；同一未开始任务可以多次转移 |

已有 `taskSubmissions`／`submissions` 继续表示池实际接受的物理任务，`steals` 是外层 worker deque 的窃取。
这些计数都是累计值，不是完成的 Minecraft 正文或省下的毫秒。
`WorkerDispatchCounters` 追加新组件，同时保留旧 12 参数构造器供既有调用者使用。

## 派发成本测量

使用同一个真实 persistent pool 比较两条路径：转发 executor 触发原逐任务提交；直接提交给 pool 触发新批次派发。
4 个 worker，64／256／1024 个原始任务，每种工作负载六轮，顺序为
`individual / batched / batched / individual / individual / batched`，每轮预热 100 批、测量 400 批。

任务和输入在计时前建立，两条路径都复用输入。完整测量包括提交、任务正文和逐 group barrier 完成等待。
任务拥有的计数／结果写入相隔 128 字节，避免连续数组在轮转派发时制造额外的 cache-line 竞争。
工作负载没有 Minecraft 世界、Mockito、sleep 或按耗时判定通过的断言。
测量时没有其他构建、测试或测试服负载并行运行。

下表是三轮各自均值的中位数；分配只统计提交线程，排除 worker 分配和输入构建。
不均匀工作负载的前四分之一任务各执行 2048 次 xor-shift，其余是轻任务。

| 任务数 | 工作负载 | 原逐任务均值 ms | 新批次均值 ms | 原提交线程分配 B/批 | 新提交线程分配 B/批 | 物理提交次数/批 |
|---:|---|---:|---:|---:|---:|---|
| 64 | 极轻任务 | 0.782 | 0.065 | 5781 | 936 | 64 → 4 |
| 256 | 极轻任务 | 2.471 | 0.060 | 22688 | 936 | 256 → 4 |
| 1024 | 极轻任务 | 9.984 | 0.101 | 90294 | 960 | 1024 → 4 |
| 64 | 不均匀计算 | 0.837 | 0.090 | 5776 | 999 | 64 → 4 |
| 256 | 不均匀计算 | 2.534 | 0.161 | 22692 | 1000 | 256 → 4 |
| 1024 | 不均匀计算 | 4.519 | 0.510 | 90305 | 1000 | 1024 → 4 |

这些是单 JVM、单机器上的派发专用测量，保留 JIT／GC／频率和唤醒行为的影响，**不是整服 MSPT**。
`submitNanos` 只计线程池 handoff，派发器／区段构造在完整 wall time 里，不能仅看该子计数宣称整体收益。
初次使用相邻数组的实验保留在本地，最终表格只使用隔离写入后的配对结果。
完整 36 轮数据、p50/p95 和分配定义见 [测量 JSON](WORK-STEALING-DISPATCHER-BENCHMARK.json)。

复现，使用 JDK 21，在其他构建与测试服停止后运行：

```sh
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 \
  -PforgiaVersion=21.1.250.56-beta \
  :tests:junitBenchmark --tests '*RegionDispatcherBenchmark'
```

## 正确性与安装包验证

- 完整核心回归 **1169 项**通过，无失败、错误、跳过；现有 Create 集成 **16 项**通过。
- persistent 模式复跑原熔炉／边界／工作图安全测试 **53 项**通过。
- 单独 JVM 中实际未知 Mixin 拒绝控制 **1 项**通过，persistent 已开启。
- 两轮默认 GameTest 各 **174/174 必需测试**通过，使用两个全新私有目录；完整格式／许可证检查通过。
- GameTest 启动时另有非 GameTest 的 `testFeatureGating` 框架状态失败：GameTestServer 用
  `FeatureFlags.REGISTRY.allFlags()` 建世界，而该 ServerStartedEvent 检查要求特定 flag 关闭。
  它不在 174 个必需 GameTest 中；保留日志，不把它计为通过，也没有为此修改功能开关实现。

定向测试验证：1024 个任务只提交 4 个 runner；阻塞首个 group 时空闲 runner 处理其后的未开始任务；
100 轮不均匀批次逐项 exactly-once；packed lease 不拆开；失败后无重放；setup 许可；
拒绝 inline；单 worker；固定／自定义 executor 路径；并发提交与 shutdown；并发 closer 和中断后的队列 drain。

新增六熔炉测试在同一个 chunk 中阻塞一个原始正文，其余五个正文仍能完成。
六次加工通知在所有正文退出后按原次序于服务器线程提交，没有提前通知、重复库存变更或遗留 lease。

实际 `.56-beta` 安装包私有服务器，无 helper mod，persistent＋furnace waves＋六台惰性布局：

| 验证项 | 结果 |
|---|---:|
| 正常推进 tick | 441 |
| 原始 worker 正文成功完成 | 2640 |
| 最大同时处于原始正文内 | 2 |
| 原生加工完成通知提交 | 12 |
| 输出铁锭 | 12 |
| 正文 failed／skipped／拒绝提交 | 0 / 0 / 0 |
| pool 累计成功区段窃取 | 656 |
| 保存退出码 | 0 |

pool 的区段计数包含全服务器该池的各类批次，不能将 656 次转移全部归因于熔炉。
这次安装服验证加工与收尾正确性，不是性能 A/B。

复现安装包六熔炉夹具：

```sh
python3 tools/block-entity-workgraph-smoke.py \
  --persistent-workers --furnace-waves --inert-layout --furnace-count 6 \
  --run-dir /absolute/path/to/new-private-directory \
  --libraries /absolute/path/to/installed-library-cache \
  --eula /absolute/path/to/already-accepted/eula.txt \
  --java /absolute/path/to/jdk-21/bin/java
```

工具默认 `.56-beta`，可用 `--version` 与 `--installer` 复现旧版本。原有双熔炉与关闭控制仍可使用。
本地完整证据在 `tmp/work-stealing-p0/`，其中保留第一次完整回归的失败记录：
一个旧 handoff 测试依赖 chunk-source 任务恰好先执行。已添加显式前置 latch，要求两种服务都发生，
没有削弱断言；完整回归重跑通过。

## 尚未完成的性能工作

派发专用负载已验证提交次数、提交线程分配和批次完成时间改善；真实服仍需跑完整的炉子／漏斗／实体矩阵，
以及与既有 region 路径的完整 tick A/B，再判断是否适合默认开启。历史 `.55` Mockito 熔炉慢约 1.98 倍的
结果没有因这个微测量自动失效。

本次没有替换有向 work planner、实现跨 tick 证明缓存、拆分显式 packed lease，或扩展漏斗／红石通知准入。
这些继续按 [总体优化方案](BLOCK-ENTITY-OPTIMIZATION-PLAN.md)推进。单个正在运行的重 region／pack 仍不能被窃取，
真实依赖链也不能通过增加 runner 消除。新的执行路径继续默认关闭。
