# General BE Resource Ownership — Mixed Workload Validation

验证对象是提交`681e34df0cff29a0bd4b0cadf60627fceb8af9c5`对应的冻结`.59-beta`安装包。本阶段验证现有Furnace资源协议，不增加BE准入、不改变默认值、Lease、Region排除、生命周期或动态验证。所有实际世界使用独立临时目录；构建、回归、安装服和重型离线分析串行执行。

## 验证边界与方法

原有协议已覆盖identity、home、READ/WRITE、one-shot Lease、线程绑定Scope、完整wave reservation和fresh准备；过去的真实安装服主要是持续燃烧孤立Furnace。本阶段增加混合BE与正常世界Tick，以及有界长稳态和合法动态变更。

| 层级 | 验证内容 | 不能由此推出的结论 |
|---|---|---|
| 真实安装服 | 原版Furnace／Blast Furnace／Smoker、Hopper环、酿造台、箱子、日光传感器、红石、普通实体Tick；资源开／关完整状态与成本对照 | 没有证明任意modpack、未知异步写入或新增BE适配器安全 |
| 核心真实正文测试／稀疏world seam | 波次之间未知callback更换inventory、抛异常、chunk／recipe／binding失效及回收 | seam不是完整真实服务器负载，耗时不作性能结论 |
| 通用声明执行测试 | 多home READ/WRITE链、shared reads和serial fences的实际并发执行与原序串行状态比较 | 声明不能证明调用方完整effects closure |

未知BE仍是完整串行fence；Hopper／酿造／普通实体／随机与scheduled tick没有新增worker准入。正常服务器各phase依次执行，混合表示同一个服务器tick内共存，不宣称Hopper正文与Furnace worker同时运行。仅已验证的原版燃烧正文会进入既有资源波次。Furnace旁8个Comparator令加工完成的有活动通知Tick保守串行，其余已审计正文保持现有规则。

## 可复现负载

- JDK21、2GiB固定G1、4persistent workers、seed813584、正常20TPS；两条件只切换Resource Ownership，既有Furnace waves都开启，其他并行slice保持原先关闭。
- 同Region：64机器分布在同一固定3×3Region的9个chunks；跨Region：分布在8个固定Region中心chunk。机器之间间距4，排除闭包仍由生产代码逐次核验。
- 32Furnace＋16Blast Furnace＋16Smoker，明确覆盖三种原版配方并统一200Tick加工时间。每测量窗口前在静止主线程补入64原料／64煤，BurnTime30000、CookTime100；保留原BE实例，更换当前ItemStack对象，测量区内持续正常加工。
- 8个四Hopper闭环，共32个Hopper／512铁粒，无外部item source；每次完整快照逐环核对守恒。8酿造台每窗口补入3水瓶／1地狱疣，另外8箱子／8日光传感器。
- 8个Comparator贴邻Furnace。8红石灯由原版tick function每20Tick切换电源，核对通电与scheduled熄灭；32个NoAI／NoGravity、Age负值的羊正常执行实体Tick，逐只核对Age增量，未启用实体worker。
- 使用既有外部只读local JMX：原生100槽tick ring、CPU、server＋worker allocation、GC count/time和heap。原生MSPT的内部active区间排除freeze、快照和控制时间；完整NBT、正文、产物／通知计数涵盖精确gameTick窗口。Ring采样的端点歧义／observer成本与之前相同。
- 独立JVM反序轮次分别保存；同JVM连续窗口不是独立重复，JFR／perf诊断不与正式时延统计合池。所有失败预检保留且不计正式PASS。

## 执行结果

正式稳态矩阵已完成8个独立进程／16窗口／9369个原生Tick样本。每种布局两次独立JVM配对，第二轮反转开关顺序；下表只合并同进程两个600Tick窗口，不合并独立轮次。全部配对完整BE NBT、所选确定性实体字段、产物、普通世界Tick和原始正文／串行路由计数一致。

| 布局／独立轮次 | mean MSPT，OFF→ON ms | p95 ms | server＋worker allocation MB/s |
|---|---:|---:|---:|
| 同Region／0 | 1.2168 → 1.3885 | 1.4899 → 1.6824 | 4.2400 → 7.8237 |
| 跨Region／0 | 1.2643 → 1.4532 | 1.6030 → 1.7599 | 4.4796 → 8.0932 |
| 同Region／1，反序 | 1.8027 → 1.5672 | 2.4941 → 1.9192 | 4.2124 → 7.8400 |
| 跨Region／1，反序 | 1.2835 → 1.4631 | 1.6172 → 1.8347 | 4.4770 → 8.0849 |

3／4配对的mean与p95回退；跨Region两次方向一致。反序同Region配对中的OFF有337ms MXBean collection time，ON为19ms，而首轮与两次跨Region均为零。MXBean collection time包含收集器统计，不能当作全部STW或给整轮差异定因；反序同Region的无GC窗口也比首轮OFF慢。所有窗口完整保留，未删除较慢基线或裁掉长Tick。

四组ON分配均比OFF增加约3.6MB/s（约81–86%）。这与`.59`相对旧`.58`资源实现降低成本并不矛盾：两侧已有相同Furnace worker能力，新增资源层没有在当前范围创造更多可执行正文。资源ON每个窗口签发数量与实际原始正文严格相等；OFF资源计数与分段耗时全部为零。

核心回归1224项、fixed／persistent定向各115项、实际未知Mixin控制1项均通过，零失败／错误／跳过；格式与许可证通过。新增三个Java测试包括80×48个多home操作实际执行与canonical串行对照、未知callback更换库存后后续wave失效且下一Tick恢复、callback部分效果后抛异常不重放／不提前执行后续wave。未知Mixin通过独立测试JVM真实merged方法验证保守串行；不是完整modpack安装服结论。

## 动态变化正确性

动态组使用新世界，OFF／ON各一个正常保存退出进程，每个包含5个精确检查窗口：

| 检查窗口 | 原生机制与断言 | 结果 |
|---|---|---|
| 库存变化 | 运行期间第70gameTick由scheduled vanilla function更换当前input的components；后续Tick必须保守检查，输入＋输出守恒且无丢失／重复正文 | 两条件通过 |
| BE移除／替换 | 第70gameTick移除原方块，在同位置创建新Furnace和匹配的受控测试状态；旧BE／ticker不得继承资源权限，累计加工进度与burn计数正确 | 两条件通过 |
| Recipe reload | 完成真实`reload`后load-tag计数必须前进，将三种配方改为400Tick，检查400Tick恰好一个输出 | 两条件通过 |
| Recipe恢复 | 再次完成reload并恢复200Tick，重新准备当前inventory与proof，200Tick恰好一个输出 | 两条件通过 |
| Chunk真实卸载／重新加载 | 移除所有forceload并把spawn移走；原版perf报告中目标holder不在内存；重新加载后检查状态、32个原UUID唯一保留，再验证200Tick加工与完整计数 | 两条件通过 |

这些操作通过合法主线程入口发生在正常Tick期间，不能称为对任意并发字段写入的支持。移除／重建脚本显式重建测试库存，已有掉落实体不计入容器守恒oracle，也没有由此声称完整世界所有item都守恒。卸载与加载所需Tick受异步IO影响，按每侧完整合法运行检查持久状态／有限效果范围；不将两个JVM加载延迟强制视为相同。

`perf`仅用于确认holder实际离开内存，整个动态组为诊断证据，不和正式性能矩阵混池。重新加载后observer看到的时延／分配包含诊断与重新预热影响，不能视为稳态收益或回退。

## 旧资源实现控制

冻结`.58`在相同输入、布局、VM和资源ON下分别启动一个独立进程，1200Tick预热、两个600Tick窗口。功能状态与`.59`逐窗口核对；这是每布局一次旧实现控制，未反序重复，不构成可靠时延置信区间。

| 布局 | `.58`窗口mean MSPT ms | `.58`allocation MB/s | `.59`正式两轮allocation MB/s |
|---|---:|---:|---:|
| 同Region | 1.8383／1.7810 | 14.5331／14.4956 | 7.79–7.86 |
| 跨Region | 1.8855／1.7761 | 14.8770／14.7901 | 8.06–8.12 |

`.59`相对旧资源层的实际分配改善在混合场景继续可见，约降低46%；但`.59`OFF仍只有4.2–4.5MB/s。这支持“旧层成本下降”，不支持“资源层开启带来净收益”。

长稳态、真实连接式管线与全部离线复算均已完成。正式证据覆盖16个正常保存退出的服务器进程、94个窗口、91200个精确gameTick与85081个原生Tick时间样本；其中4个管线窗口只作正确性证据。合计5789230次原始炉子worker正文、4501970次完整串行BE Tick、29056个加工输出。全部已检查配对的完整状态与工作量等价，失败／跳过／拒绝任务／资源拒绝／ownership violation均为零，每个静止检查点runningRegions为零。数据及逐窗口完整状态见[机器可读记录](BLOCK-ENTITY-RESOURCE-MIXED-PROFILE.json)。

## 30分钟跨Region稳定性对照

ON、OFF各一个独立JVM，按ON→OFF顺序运行；每侧1200Tick预热，30×1200Tick测量，共36000gameTick，即30分钟正常20TPS活动时间。每分钟静止快照／补料不计入MSPT；Hopper、实体、recipe-use累计和红石时钟跨窗口连续。两侧每个对应检查点的完整状态与工作量相同，且各完成2302560次原始炉子正文、1729440次完整串行BE Tick、11520个产物。ON恰好2302560个Lease，OFF全部资源工作为零；正文累计最大并发均为4。

| 指标 | OFF | ON | 判断 |
|---|---:|---:|---|
| 原生Tick样本 | 35561 | 35567 | 内部active区间；精确gameTick另计 |
| mean MSPT ms | 1.1976 | 1.4232 | 增加18.8% |
| p50 ms | 1.1158 | 1.3118 | 增加17.6% |
| p95 ms | 1.8564 | 2.4912 | 增加34.2% |
| p99 ms | 2.1701 | 2.8801 | 增加32.7% |
| 最大Tick ms | 124.9285 | 128.4265 | 长尾完整保留 |
| server＋worker allocation MB/s | 4.4296 | 8.0823 | 增加82.5% |
| 进程CPU，100%=一个逻辑核 | 14.347% | 14.767% | 小幅增加；包含JIT／GC／监测服务 |
| MXBean GC count／time | 16／396ms | 16／314ms | 本次ON未增加该time；不代表全部STW或CPU |

ON分段ms／精确gameTick：proofAndPlan0.10397、fresh revalidation0.10673、reservation0.04029、fullPhase0.85379。OFF没有资源分段；共同炉子prepare为0.20444→0.31195ms／tick，executeAwait为0.24381→0.25641。资源分段不覆盖所有工作，不能相加当完整MSPT。

| 趋势，前5窗→后5窗 | OFF | ON |
|---|---:|---:|
| mean MSPT ms | 1.3067 → 1.0867 | 1.4060 → 1.3141 |
| allocation MB/s | 4.4401 → 4.4272 | 8.0908 → 8.0749 |

分配没有随时间增长，平均时延尾段也没有持续恶化；两侧中途均出现过慢窗和GC长尾，未剔除。ON逐窗allocation范围8.0717–8.1216MB/s，OFF4.4229–4.4687MB/s。Heap used呈回收锯齿；逐窗平均占用范围分别约560–1131MB与474–1232MB，未发生OOM。OFF首窗到末窗平均占用也可从约646增加到1232MB，只凭这种采样不能区分GC周期与retained heap，**不宣称已经排除慢泄漏**。本次没有在计时内强制full GC或抓取heap dump。

该长测只是一轮独立长配对，不能生成可靠时延置信区间；但分配增量与两轮短矩阵一致，且本次完整Tick的mean／p95／p99均回退。当前证据不支持Resource ON的实际净收益。

## 连接式Hopper／Furnace库存管线

另开两个正确性服务器，在8个Region各建立输入Hopper→Furnace→输出Hopper→箱子。保留其余56台Furnace家族、32个独立循环Hopper、酿造／日光／红石／实体负载。Hopper ticker先于新建Furnace注册，确保真实容器操作出现在后续炉子之前；未增加任何Hopper或Container资源准入。

两条件各两个1200Tick窗口，共4窗。每个窗口每Tick恰好128个可Tick BE，实际输入、加工和输出抽取到sink箱子均验证，8条线各自原料＋产物始终64；其余机器输出、红石、实体Age及完整前后状态全部一致。每窗67200个原始worker正文＋86400个完整串行BE Tick；ON恰好67200个Lease、OFF为零。

连接的8台炉子在该ticker顺序中被Hopper fence分隔为单元素波，保持完整串行；另56台独立机器继续既有波次。workgraph明确记录SINGLE_UNIT／ACTIVE_NOTIFICATIONS／UNKNOWN_INSTANCE拒绝原因。它证明混合容器网络得到保守执行并与OFF保持相同结果，**没有证明这些管线已获得资源并行许可或worker收益**。本组不发布性能提速结论。

## 交付检查与排除项

Python协议测试16项通过；core1224、fixed115、persistent115、实际未知Mixin1项全部通过，零失败／错误／跳过；root/tests格式和许可证通过。本阶段未重新运行Create／GameTest，没有把历史结果计作本轮测试。生产代码、Vanilla patch、默认值、准入和冻结安装包SHA-256均未变；没有新生产版本或线上部署。

两个预检问题按失败保留：首个Smoker脚本覆盖错误配方ID，被产物oracle拒绝；首个动态脚本未接受Vanilla实体UUID提示文本，被完整数据解析拒绝。均在新世界修正重跑，失败／不完整进程不计正式样本。首次新增异常测试预期包装类型错误，改为验证原异常同一对象后完整回归通过，不将此前失败算通过。短预热pilot-v2仅为协议预检，不并入正式性能。

可复现工具为[mixed_workload.py](../tools/block-entity-resource-ab/mixed_workload.py)、[mixed_pipeline.py](../tools/block-entity-resource-ab/mixed_pipeline.py)、[export_mixed.py](../tools/block-entity-resource-ab/export_mixed.py)，命令与度量定义见[工具README](../tools/block-entity-resource-ab/README.md#mixed-workload-validation)。导出器从原始JMX重算全部MSPT／CPU／allocation／GC／heap，校验完整状态、酿造进度、Hopper传物、recipe-use、路由与安全计数，拒绝缺失／重复／未正常退出的样本。运行世界、properties／口令、日志、perf ZIP及jar留在本地临时目录。

## 对五个里程碑问题的判断

1. **通过范围**：现有Furnace家族与保守串行BE／正常世界Tick共存，完整ON/OFF状态和执行路由相同；多home资源依赖实际执行与canonical结果相同；合法动态库存／配方／binding／chunk变化后重新验证，scope／lease／join／无重放约束保持。通过结论限定在明确的当前操作证明与本报告负载内。
2. **仍有风险或性能问题**：当前lease不是Java sandbox，未经hook的字段写入、未知异步Mod callback和backing-resource别名仍需单独审计；有限负载不能证明所有Mod行为。实际ON新增约3.6MB/s分配，短窗口时延在3／4配对回退，GC与宿主时序会影响尾部。
3. **是否真正改善实际服务器性能**：**没有建立净收益，长测为负面结果**。30分钟配对mean增加18.8%、p95增加34.2%、allocation增加82.5%；短矩阵3／4配对也回退。`.59`比`.58`省成本与ON比OFF更快是两个不同问题。不能把已有相同并发正文数量、满20TPS或旧实现分配下降算作净收益。
4. **是否具备扩大BE准入条件**：**不具备。**本阶段强化了既有范围内的可靠性证据，没有补齐Hopper／Container／Capability／Create等新操作完整effects closure。继续默认关闭，不扩大任何准入。
5. **最值得实施的下一阶段**：先在保留全部fresh验证与权限条件下，针对当前pass的catalog遍历、claims描述构建及serial fence导致的重复准备做成本归因；以真实混合安装服反序ON/OFF为验收基线，目标是降低完整Tick成本。并把本轮动态／管线正确性负载保留为回归资产。跨Tick复用成功证明或删除失效检查不在这个建议内。

## 已知局限与下一步判断原则

Lease不是Java sandbox，不能防御没有hook的任意字段写入或不遵守生命周期的外部异步Mod。当前范围只允许具有完整当前调用闭包证明的正文；未知callback必须保持保守，禁止以历史行为推导权限。

- 负载是无玩家的可复现原版混合场景，普通实体使用NoAI避免随机行为污染状态对照；它没有覆盖玩家移动／多人网络、活跃AI、饱和modpack吞吐、任意Mod容器实现或未知异步写入。开启randomTickSpeed只是保持正常世界phase，本夹具没有把农田／作物生长当作已经验证的负载。
- 30分钟每条件只有一个独立JVM，且按ON→OFF顺序运行；不生成独立置信区间，也不能排除宿主频率／调度／JIT历史差异。GC heap-used是锯齿状占用，未强制full GC，不把它等同retained heap或证明无缓慢泄漏。

资源ON/OFF均启用既有Furnace waves。若两边都可并行同样正文，新增资源层主要提供安全结构而不是新增并发，不能事先保证正性能收益。是否降低完整服务器成本取决于本轮真实数据，而不是此前`.59`相对`.58`的分配改善。是否扩大准入还需新增BE的callback、backing resource alias和通知闭包证明；现有负载通过也不能自动授予该条件。
