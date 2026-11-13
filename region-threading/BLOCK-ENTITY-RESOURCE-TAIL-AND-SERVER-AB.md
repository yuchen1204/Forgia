# `.58` Resource Ownership 尾延迟调查与安装服 A/B

本阶段以 `512a055fe867a82784e37ca4dc75814f5b929685` 为基线。用户要求先定位问题，并用**同一份 `.58-beta` 安装包**测量 Resource Ownership 开／关。本阶段单人完成，没有多 Agent 或独立审查。生产权限规则、默认配置、跨 Tick 验证与准入类型均保持不变。

## 尾延迟证据

历史 64 units / 64 shared READ 的 3 个 after round，plan p95 分别为 4.8449、5.5343、4.8396 ms，total p95 为 4.9772、5.6928、5.0182 ms。它们来自每形状 **21 个样本**的最近秩分位数：p95 是第二大样本，不能等同长期服务器 p95。

调查冻结 `.58` universal jar 中 18 个 ownership/planner class，使用原探针、JIT／safepoint／GC 日志、HotSpot LogCompilation，以及 JFR 的 Compilation、Deoptimization、GCPhasePause、ExecutionSample、ObjectAllocationSample、JavaMonitorEnter、ThreadPark 事件。

关键交叉证据：

- 原始探针、原始生产类、不启用 JFR 的三个重复运行，仍重现约5ms的 plan p95。
- 保留原 `run` 方法，只在计时循环外记录 sample 起点和已有分段结果。64/64 的 sample8／11 分别出现 **5.6847／6.9303 ms** 的规划时间。
- 两段区间分别与 **`java.util.List.of`／`java.util.ImmutableCollections.listCopy`** 的 C2 编译重合。LogCompilation 的 task 均为 `blocking=1`，编译本身约5／7ms；这两条调用由生产计划不可变 List 构造触发。
- 当时没有对应区间的 GC 或全局 safepoint。原探针是单个协调线程、constant-true proof、顺序 scope，无 worker／资源锁争用，不能把此尖峰解释为多线程资源所有权竞争。
- 原基准使用 **`-Xbatch`**：HotSpot 在达到热度阈值时让发起线程等待编译。原来的预热次数没有使这些 List 路径在该形状测量前达到稳定编译状态。
- 仅启用 JFR（不改原探针）也会改变 JDK 调用／编译阈值时序，使同位置的5ms尖峰消失。因此不能把一份没有尖峰的 JFR 称为生产修复。记录器与事件埋点的观察者效应单独保留。

**归因边界**：这里定位的是已重现的原始合成基准中特定5–7ms尖峰。不能由此断言所有服务器尾延迟均来自 JIT，也不能把未观测到的 GC／锁事件当作任意工作负载的排除证明。小集合增加的 map／claim／boundary／scope工作是真实成本，需要服务器数据评估。

预热与异步编译对照、样本时间轴、完整 JFR 摘要和原始基准重复数据，将由配套数据记录提供。原有 `.58` 记录不改写、不删异常值。生产代码不为绕过 JVM 编译阈值或缩短某个测量分段而修改；显式追加的探针 trace 仅在诊断时启用。

## 安装服实验协议

工具为 [`server_ab.py`](../tools/block-entity-resource-ab/server_ab.py) 与外部只读 [`JvmMetrics.java`](../tools/block-entity-resource-ab/JvmMetrics.java)。
它在预热前启动JDK内建本地JMX管理服务，没有辅助mod、Mixin、字节码instrumentation agent、服务器类改写或执行权限放宽。

- 冻结 installer SHA-256：`7d736190ccd2c97c949368a3a57af35a31fe71a5f48e7814517494b78d7da34f`。
- 冻结 universal SHA-256：`0c137cdaa9a88b160c8c146633eb1e39700d42ecc39ac4dd17cd45bd3d441e45`；运行前核验安装目录中实际加载的文件。
- 原版1.21.1、相同 flat seed813584、无玩家／无其他 mod。关闭随机 Tick、生成、实体并行及天气变化；显式加载所有候选 chunk 的3×3邻域，独立可保存世界。
- 两条件都启用原有 Furnace waves 和 persistent workers，唯一新增条件是 Resource Ownership 开／关；比较的是资源层的增量成本，**不比较 waves 开／关，也不比较 stock NeoForge**。
- Java21.0.13，`-Xms2G -Xmx2G -XX:+UseG1GC -Dfile.encoding=UTF-8`，不使用 `-Xbatch`。同一 WSL2 VM、4物理核／8逻辑CPU，宿主机调频和VM调度未锁定。
- 数量2／64／256；同Chunk、同Region的不同Chunk、分散最多8个Region；Workers1／2／4／8。炉子沿xyz至少相隔4格，通知周边为已加载惰性方块／AIR。
- 每种数量重建输入，实际原版炉子 `BurnTime=30000`、64iron_ore／64coal、200tick配方、初始`CookTime=100`。
  预热／窗口长度均为200的倍数，使每次完成与通知落在内部活动区间的中部，避免被边界裁剪系统性排除。
  测试覆盖持续燃烧，不代表补燃料、Hopper、比较器或模组机器。
- 每形状丢弃400个正常20TPS预热Tick，然后两个200Tick稳态窗口。两窗口是同进程连续观测，**不是两次独立JVM重复**。不同worker／布局交替ON/OFF启动顺序。窗口较短，p99和GC统计的可推广性有限。
- Vanilla command function 在精确目标gameTime冻结；确认目标差值，不用手动轮询延迟假装Tick时间。每个窗口前后冻结时收集**全部炉子完整NBT**、累计计数器与异常指标，验证完成数量及库存守恒。大SNBT列表分成最多64项，拒绝Vanilla`<...>`显示截断。
- 原生`MinecraftServer.tickTimesNanos`100槽环经本地JMX每100ms读取；读取服务器／worker线程CPU与分配量，每秒采样一次线程栈。只分析unfreeze已确认后到最后一个低于目标gameTime查询前的内部活动区间，并额外去掉100ms边界。冻结／快照／RCON控制时间均不计入MSPT和CPU窗口。
- 原生时间环记录`MinecraftServer.tickServer`原有时长：包括世界／BE／调度等阶段，不含20TPS限速睡眠；与原生实现一样不包括最后的ServerTickPost回调。本实例没有额外回调mod。
- JMX环无法单独给出绝对tickCount：按发生变化的连续环范围恢复样本；内部相同值槽仍保留，超过4s采样缺口拒绝。相同值恰在范围端点的极少数样本无法辨别，原始采样与限制保留；不声称零扰动逐Tick追踪。
- 完成数／lease是**精确200gameTick窗口差分**；MSPT／CPU是该窗口内有证明处于活动状态的子窗口。累计最大原始正文并发度标注为since-boot，同时记录稳态采样栈／worker CPU；不把累计max当每个窗口的并发分布。
- TPS由正常活动区间的原生Tick数／墙钟时间得到。`controlWindowTps`另含冻结确认延迟，保留原值但不当成实际TPS。CPU100%表示占满1个逻辑核，另给8逻辑CPU总量比例。
- 性能窗口不与构建、测试、其他私服或探针并行；原始日志、JFR、世界、口令与jar留在`tmp/be-resource-tail/`，不上传。

工具预检／探索数据单独保留：默认函数权限2不能执行`tick freeze`，设置为4；Vanilla长SNBT显示在128项后缩略，改为64项分组并拒绝`<...>`。
另外，第一版200Tick窗口初始CookTime0会把加工完成放在被裁剪的末端；整组探索矩阵已停用，正式矩阵从新世界以CookTime100重跑。
停止探索时第二个RCON控制连接与进行中的请求相遇，线程转储确认`Server thread -> GenericThread.stop/join`、`RconClient -> executeBlocking/CompletableFuture.join`的Vanilla关闭等待环；
该临时进程捕获证据后强制结束，不计为成功退出或功能验证，未修改原世界。工具增加单控制器安全停止与异常清理兜底；正式运行只使用自己的完整RCON交互完成后关闭。
这些预检、终止与废弃数据均不纳入正式性能统计，也不冒充生产Resource Lease故障或修复。

## 正式矩阵结果

[完整性能记录](BLOCK-ENTITY-RESOURCE-SERVER-AB-PROFILE.json) 包括36组配对、144个稳态窗口与**26,562个原生Tick时间样本**。
每一配对的完整前后NBT、原始正文、通知与输出数均一致：共3,091,200次成功原始炉子正文、15,456个加工输出；
正文失败／跳过／提交拒绝、资源拒绝与所有权违规均为零。运行时实际线程数量与persistent模式也经过核验，未把配置值当作生效证据。

开启资源层后，**35/36组平均MSPT增加，34/36组p95增加**。正常TPS观察范围为19.76–20.08；轻负载限速在20TPS，
不能用TPS仍满值否认MSPT与CPU开销。各自短窗口之间存在明显差异，单组或单次较快不构成稳定收益。

下表每条件仅合并其两个同进程稳态窗口，属于每组约370个Tick的统计，不是跨JVM平均或置信区间。
更详细的p50／p99／CPU／GC／阶段计时／allocation与每个窗口见JSON，不对多种规模混算一个“整服加速比”。

| 布局 | Workers | 炉子 | 平均MSPT，关→开 | p95 ms，关→开 |
|---|---:|---:|---:|---:|
| 同Chunk | 1 | 2 | 1.901 → 2.515 | 2.514 → 3.341 |
| 同Chunk | 1 | 64 | 2.230 → 3.364 | 2.994 → 4.272 |
| 同Chunk | 1 | 256 | 4.038 → 18.805 | 4.975 → 54.642 |
| 同Chunk | 2 | 2 | 1.724 → 2.453 | 3.741 → 3.245 |
| 同Chunk | 2 | 64 | 2.964 → 3.278 | 4.780 → 4.164 |
| 同Chunk | 2 | 256 | 8.799 → 7.809 | 11.148 → 15.045 |
| 同Chunk | 4 | 2 | 1.562 → 2.136 | 2.426 → 3.301 |
| 同Chunk | 4 | 64 | 2.003 → 11.453 | 3.001 → 16.513 |
| 同Chunk | 4 | 256 | 3.521 → 7.358 | 4.582 → 11.833 |
| 同Chunk | 8 | 2 | 1.907 → 2.402 | 2.743 → 3.305 |
| 同Chunk | 8 | 64 | 2.268 → 3.721 | 3.103 → 9.002 |
| 同Chunk | 8 | 256 | 4.080 → 9.626 | 5.261 → 15.358 |
| 不同Chunk／同Region | 1 | 2 | 1.044 → 1.267 | 1.220 → 1.456 |
| 不同Chunk／同Region | 1 | 64 | 1.269 → 1.713 | 1.549 → 1.880 |
| 不同Chunk／同Region | 1 | 256 | 2.776 → 3.275 | 3.647 → 3.716 |
| 不同Chunk／同Region | 2 | 2 | 1.054 → 1.284 | 1.284 → 1.464 |
| 不同Chunk／同Region | 2 | 64 | 1.259 → 1.755 | 1.452 → 1.978 |
| 不同Chunk／同Region | 2 | 256 | 2.157 → 3.683 | 2.365 → 5.615 |
| 不同Chunk／同Region | 4 | 2 | 1.032 → 1.221 | 1.351 → 1.490 |
| 不同Chunk／同Region | 4 | 64 | 1.215 → 1.643 | 1.438 → 1.886 |
| 不同Chunk／同Region | 4 | 256 | 2.043 → 3.460 | 2.303 → 4.973 |
| 不同Chunk／同Region | 8 | 2 | 0.976 → 1.232 | 1.200 → 1.514 |
| 不同Chunk／同Region | 8 | 64 | 1.167 → 1.638 | 1.376 → 1.818 |
| 不同Chunk／同Region | 8 | 256 | 2.010 → 3.770 | 2.556 → 5.438 |
| 不同Region | 1 | 2 | 1.073 → 1.307 | 1.199 → 1.507 |
| 不同Region | 1 | 64 | 1.285 → 4.513 | 1.474 → 6.248 |
| 不同Region | 1 | 256 | 2.164 → 4.182 | 2.522 → 10.617 |
| 不同Region | 2 | 2 | 1.113 → 1.404 | 1.285 → 1.747 |
| 不同Region | 2 | 64 | 1.380 → 1.816 | 1.548 → 2.179 |
| 不同Region | 2 | 256 | 2.141 → 3.182 | 2.373 → 3.558 |
| 不同Region | 4 | 2 | 0.992 → 1.241 | 1.184 → 1.503 |
| 不同Region | 4 | 64 | 1.257 → 1.703 | 1.532 → 1.999 |
| 不同Region | 4 | 256 | 2.274 → 3.723 | 2.637 → 5.044 |
| 不同Region | 8 | 2 | 1.044 → 1.309 | 1.320 → 1.605 |
| 不同Region | 8 | 64 | 1.295 → 1.755 | 1.691 → 2.148 |
| 不同Region | 8 | 256 | 2.412 → 3.545 | 3.409 → 4.119 |

## 长窗口复验

同Chunk／64台炉子／4Workers，采用两次独立JVM配对，启动顺序分别为关→开与开→关；
每进程丢弃1200个预热Tick，再取两个1200Tick稳态窗口。共9486个原生Tick样本，没有与短矩阵混算。

| 独立配对 | 平均MSPT，关→开 | p50 ms，关→开 | p95 ms，关→开 | p99 ms，关→开 |
|---|---:|---:|---:|---:|
| 关→开启动 | 0.7498 → 1.3694 | 0.7327 → 1.3387 | 1.0125 → 1.6113 | 1.1910 → 1.9162 |
| 开→关启动 | 0.9887 → 1.2961 | 0.9156 → 1.2751 | 1.3485 → 1.5502 | 1.7668 → 1.8237 |

平均MSPT分别增加82.6%／31.1%，p95增加59.1%／15.0%。这两个配对都未显示资源层提升性能。
绝对差值0.31–0.62ms／tick，不能把不同JVM的比例包装成任意modpack增量，也不能从两次重复构造可靠总体置信区间。

服务器＋worker线程分配从2.35MB/s增加到12.06–12.30MB/s。进程CPU约13.79→15.13%／14.74→14.83%单核，
CPU增长比墙钟差值小且背景编译／VM调度也影响总进程CPU；不把墙钟差值全称作CPU计算时间。
MXBean记录两次配对关侧共1次／17msGC、开侧共3次／49msGC。开侧未出现每个Tick都因GC停顿的证据。

资源开启的完整实验BE阶段分别约1.039／0.974ms每Tick；初始proofAndPlan约0.254／0.223ms、
fresh revalidation约0.181／0.164ms、reservation约0.074／0.071ms。
这些子计时的口径不同且部分包括嵌套阶段，不能与fullPhase相加，更不是完整服务器Tick的拆解。
两侧正文均为每进程153600次、12次原生配方完成／炉子（每进程768个输出）、正文累计最大并发4。
完整NBT、燃烧／加工计数、recipe usage与通知提交完全一致，未减少工作量换取指标。

短矩阵同场景开侧均值11.453ms不应当成稳定成本：其中一个窗口MXBean与GC日志记录到606.776ms的G1 mixed pause，
并且长预热复验的绝对值明显下降。但单次暂停也不能解释整个短窗p95的形成；短窗原值与GC数据均保留，没有剔除。
这与旧合成探针的特定阻塞编译尖峰是两种独立证据，不把所有尾部统一归因为JIT。

## 单独的服务器JFR证据

另起两个同版本同配置服务器，长预热后启用JFR，再丢弃200Tick。每侧1200Tick的诊断窗口结果
为平均0.860→1.751ms、p95 1.141→4.018ms、CPU20.23→27.09%单核。
**诊断结果不与未profile的A/B混算**。JFR启动改变了调用／编译时序，开侧779个、关侧670个编译事件与活动窗口重合，
它们发生在正常异步编译器线程，不可把这些任务时长直接当成服务器阻塞；活动窗口没有记录到deoptimization事件。
预热只是排除了规定的启动／准备阶段，不等于证明所有调用均已达到最终C2状态。

流式事件分析在完整二进制JFR上计算，摘要及编译事件列表见性能JSON的`separateProfiledPair`。
关侧131个Server-thread执行样本中57个在当前catalog审计；开侧467个中243个在资源身份／预留／scope路径，
另有103个在catalog审计、48个在dependency planner、4个在资源claim capture。
开侧叶子热点包括`HashMap.putVal/resize/newNode`、`IdentityHashMap.resize`、immutable map/set probe与Claims Builder。
这说明当前增加的协调线程表构造／一致性工作是真实CPU路径；采样数量较少，不能将比例当精确CPU百分比。

开侧按最近已审计调用栈分类的allocation sample weight约412MB来自ownership、68MB来自claim capture、78MB来自planner，
并出现大量`HashMap.Node`／table arrays、`Identity`、`Binding`、`LinkedHashMap.Entry`。
这些是**采样权重**，不是逐对象精确计数；与独立JMX的约5倍相关线程分配增长方向一致。

- GC：关侧活动内无GC pause事件；开侧1个约12.063ms暂停，MXBean约13ms。它不能单独解释持续的资源层开销。
- 锁：活动内未记录到超过1ms阈值的`JavaMonitorEnter`。不是“任意锁永不竞争”的证明；没有资源worker阻塞互斥锁这一实现路径。
- Park：每个worker在约59秒活动内累计park约58秒，栈归属`RegionWorkerPool`空闲／dispatch等待；
  server barrier相关park总计约3.020→10.937ms。park含正常空闲／join，不能把累计park相加当关键路径延迟或资源锁等待。
- 其他：两条件JMX/RMI都有采样与分配。外部observer会引入开销、触发JDK类加载／编译，宿主WSL调频和调度未锁定。
  相同observer与参数、反序重复能降低偏差，不能消除全部环境噪声。native sample另行记录：
  两侧活动内约2955／2956个，主要来自网络accept/read/poll、文件watch与epoll等待，不与Java执行样本比例混算为CPU工作。

## 修改与验证结论

本阶段修复的是**测量根因与证据工具**：明确长预热、101样本、p99／max、可选wall/threadCPU与JFR标记，
逐Tick原生时间环、精确冻结／完整NBT／计数验证、完成通知居中、JFR流式解析、启动就绪重试和可审核恢复。
旧探针的原样六轮配对与新的长预热控制均保留在[尾延迟证据](BLOCK-ENTITY-RESOURCE-TAIL-PROFILE.json)。
新控制64/64使用同一个公共harness和冻结生产实现；最终无构建／测试／JFR解析竞争的归档轮次中，
plan p95为0.8153→0.0686ms，total p95为1.6695→0.1765ms；
它是一次机制测量控制，没有把测量协议变化写成新的生产加速。

**没有为绕过验证或迎合分位数修改生产Resource Ownership。** 真实开销主要属于现有每次fresh proof／plan／reservation表构造，
还包括必须执行的动态catalog／alias／lifecycle验证。JFR支持后续压缩表与减少当前pass内部临时对象的方向，
尚未给出可以删除哪次动态证明或跨Tick复用状态的安全失效协议。此时扩大并行BE类型会增加效果闭包与失效证明负担，缺少性能依据。

结论：**本阶段没有证明实际服务器性能改善；继续默认关闭，不扩大Hopper／Container／Capability／Create准入。**
后续如实施当前pass内的packed identity表或容量预估，需要以相同功能等价／安全测试、独立长窗口服务器对照重新验收，
而不能只让合成probe更快。未来任何自动路由／proof复用也必须保留Region排他、完整验证、一次性Lease与fail-closed。

性能实验完成后执行环境重启，30个服务器进程均已正常退出、原始记录完整；被中断的是离线解析／机制基准／回归队列。
机制基准的后续重复在重启后完成并单独标注，不与之前的服务器样本混合。Gradle外部依赖缓存缺失导致首次离线启动失败，
恢复依赖后再按离线模式复跑。JFR的全量pretty-JSON展开也因重复park栈膨胀到约13.7GB，停止后改为256MiB上限的流式二进制读取；
原始JFR完好，膨胀输出未用于结论、未上传。以上都是测量工具／环境边界，未计为成功的测试运行。

## 最终验证与交付

| 本轮验证 | 结果 |
|---|---:|
| 离线完整核心回归 | **1215项**通过，零失败／错误／跳过 |
| Resource开启＋fixed executor定向集合 | **106项**通过 |
| Resource开启＋persistent workers同组集合 | **106项**通过 |
| Resource＋persistent＋实际未知Mixin控制 | **1项**通过，未知hook保持完整串行 |
| 测量协议／SNBT／时间环／通知窗口／JFR精度与流式解析 | **10项**通过 |
| root/tests格式与许可证、`git diff --check` | 通过 |
| 新工具Java编译、JFR二进制事件交叉核验 | 通过 |

原始六轮配对机制probe再次覆盖12种形状／每形状21样本，仍复现64/64的旧协议尖峰；
新的2000warmup／101sample控制在两版冻结生产类上均运行，包含全部组件p50／p95／p99／max与allocation。
没有把旧协议的尖峰值删除后重新计算历史表，也没有对真实服务器p99作耗时成功断言。

安装服方面，正式矩阵24个进程、长窗口4个进程、单独JFR2个进程全部正常保存退出。
完整状态差分均一致，全部时段共3,859,200次原始炉子正文、19,296个加工输出；
主矩阵、长复验和profiled诊断分别存储，没有混算成一个性能分位数。

installer／universal维持上述冻结`.58`哈希；本轮没有生成新的生产版本。
Vanilla patch、Region排他、READ/WRITE、Lease生命周期、动态验证与fail-closed行为没有改动；
不需要新的patch生成／重应用或重复宣称旧GameTest／Create结果属于本轮测试。
所有实验默认值保持关闭，原线上配置、世界与mod jar均未更改。

交付包括本报告、两个完整机器可读profile、可重复运行的安装服／JMX／JFR工具及协议测试。
临时日志、binary JFR、jar、测试世界、口令与环境缓存均不在交付文件中。
