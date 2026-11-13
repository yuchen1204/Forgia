# Resource Ownership Hot Path Allocation & Validation Cost Reduction

以 `5402f63f38e7e1c521b7d3caa2051d255135141f` 的冻结 `.58-beta` 为基线，本阶段先降低已经由真实安装服定位的协调线程分配与表构造成本。候选包使用 `.59-beta`，单人实现与检查。Resource Ownership 继续默认关闭，Furnace 之外的准入范围保持原样。

## 成本与修改

上一阶段的[安装服调查](BLOCK-ENTITY-RESOURCE-TAIL-AND-SERVER-AB.md)显示：64台同Chunk炉子／4workers，资源开启使 server＋worker allocation 从约2.35增加到12.06–12.30MB/s；JFR主要命中 ownership／claim／planner 的 `HashMap` 插入与扩容、身份包装、不可变 Map/Set。该负载的原生炉子正文很短，协调线程准备成本占比高。

本次修改生产路径的三个文件：

- `BlockEntityResourceOwnership`：Claims 保存不可变本地实例与权限数组，最多8个 attachment 时线性引用比较，较大输入使用整数槽索引；重复身份只归一化权限，全部 attachment 与 validator 原样保留。SharedReads 保存同一次 fresh pass 的不可变身份数组与索引，不再为每个共享实例包装 Identity、HashSet 节点和不可变 Set。
- Phase plan／reservation 各自使用一次调用内、预估容量的 pair-identity 开放寻址表。表的键为实际 domain／instance 引用，直接比较引用，不调用用户 `equals`／`hashCode`。home、一致性、shared WRITE alias 与读写占用状态合并存储，不为每个状态再创建一份 map/set。预估上界来自逻辑预算内的本地归一化项和每个不同 SharedReads 一次；未使用跨调用对象池或 retained proof。
- Planner 直接读 Claims 数组，计算 directed depth，不再创建每单元 `Resource`、read/write HashSet、不可变 Set、`Unit` 和每身份 `Depths`。原诊断 Planner 与公开的 `Claims.unit()` 保留，仍可独立验证波次。`sameResources` 直接比较归一化权限并集，避免为 shared／expanded 等价比较创建诊断集合。
- `FurnaceResourcePreparation`：同一个 phase 中相同实际 LevelChunk 共享不可变 Binding／Identity／predicate 描述；每个单元仍调用此 chunk validator，source validator 另外核验 admission 的精确 chunk 坐标。更换实际 chunk 对象会创建不同条目，旧描述不会替新对象签发权限。边界表按已持有的 Region 数量预估容量。
- `FurnaceWorkPreparation`：当前 catalog 的身份集合预估容量；同一次 catalog 遍历复用一个递归预算容器，每个 ingredient 前重新设为256。全部原有 catalog／ingredient／可变 result/components 审计仍运行。

## 不变的证明边界

初始 preparation 与 outer Region lease／mailbox 之后的 fresh revalidation 均保留。RecipeManager 身份相同不能跳过结果、成分、库存、ticker、chunk 和通知闭包检查。每个普通 Binding occurrence 的 validator 都执行；不同 SharedReads predicate 即使身份集合相同，也各自执行。只有同一个明确 publication 对象在同一 reservation 中的 whole-publication predicate 可按原协议共享一次。

512个逻辑资源／单元、131072个逻辑 uses／phase、2048个单元的上限不变；重复资源与重复共享 uses 仍计入预算。表的容量在预算检查后分配，保持至少半数空槽。shared READ 与任意 WRITE alias、跨 home 同身份、未知／不完整／超预算 proof 均保守拒绝或转完整串行 fence。

全 phase 的 alias 检查在计算任何执行波次前完成。已接受的 shared publication 在此 phase 不可被写，因此无需展开到每个单元的依赖 footprint。本地依赖仍保持 canonical 方向：READ 等待前序 writer，WRITE 等待前序 writer／reader。深度表记录 predecessor depth＋1；每个 fence 将 barrier 提升至此前全局最大 depth＋2，覆盖此前全部深度项，故无需清空表也不会跨 fence 提前执行。

Lease 的 one-shot CAS、线程绑定 Scope、Region exclusion、完整 wave 原子发布、异常不重放、worker join 和原序通知提交均保持原协议。没有缓存任何跨 wave／Tick 的验证成功结果；同 phase 的 chunk 描述共享不存储一次验证的返回值。

## 验证与实验协议

新增6项 JUnit 覆盖 Builder 重用／快照隔离、1／8／9／64／512边界上的权限、强制 pair-hash 碰撞与环绕、多个位置 alias 与 MutableBlockPos 隔离、逻辑满预算下全部131072个 validator 调用，以及2000组跨 domain／home／共享与普通表示的参考差分。已有1000组 pairwise oracle、1000组 grouped／expanded differential、10000组一般执行／诊断 Planner differential 和炉子生命周期／callback／异常安全用例继续保留。

机制对照使用相同公共 harness、冻结 `.58` 与 `.59` production classes、每形状2000次显式预热、101样本，三次独立 JVM 配对，交替启动顺序。两侧都使用 shared 表示；该实验包含两次 fresh capture、plan、sameResources、reservation、scope 和 release，但 validator 是 constant true，不能替代真实世界审计成本。

安装服沿用原测量协议：相同 seed813584、布局、输入、2GiB G1/JDK21、正常20TPS、CookTime100使完成通知位于窗口内部；只读外部JMX采集原生Tick时间环与CPU／allocation／GC，完整NBT和精确 gameTick 计数覆盖全部炉子。新版本与两个 SHA-256 必须显式固定，原 `.58` 默认哈希检查仍生效。`compare_builds.py` 拒绝工作量、flags、线程数、时间窗或 resource condition 不一致的跨包比较。

主对照为2／64／256炉、同Chunk、4workers、Resource ON，两次独立JVM配对反转启动顺序；每数量1200warmup＋两个600Tick窗口。另有64炉 Resource OFF 控制，以及64炉不同Region的ON控制。跨Region首轮出现时延回退，因此追加一次相同协议的独立反序复验，两轮均保留。独立JFR诊断另行预热、采样，不混入主性能分位数。所有性能运行与构建、测试、探针、离线JFR解析严格串行。

## 机制成本结果

下表是三个独立JVM的**各自分位数再取中位数**，没有把不同JVM的样本混池。总耗时包含完整机制路径，单位μs；allocation单位B／次。完整逐轮p50／p95／p99／max与各分段数据见[机器可读记录](BLOCK-ENTITY-RESOURCE-HOT-PATH-PROFILE.json)。

| units／shared READ | total p50，`.58`→`.59` μs | total p95 μs | allocation p50 B |
|---|---:|---:|---:|
| 2／4 | 5.0 → 4.2 | 5.4 → 5.4 | 15,840 → 5,848 |
| 2／64 | 14.7 → 8.9 | 16.5 → 17.4 | 42,352 → 15,000 |
| 2／256 | 46.1 → 21.6 | 54.7 → 24.3 | 130,128 → 45,648 |
| 16／4 | 24.2 → 17.5 | 27.3 → 20.6 | 79,064 → 28,880 |
| 16／64 | 34.5 → 20.2 | 38.5 → 22.0 | 105,672 → 30,416 |
| 16／256 | 68.1 → 21.3 | 98.8 → 22.2 | 192,408 → 61,136 |
| 64／4 | 95.9 → 39.7 | 114.1 → 59.6 | 295,520 → 107,896 |
| 64／64 | 109.7 → 42.2 | 131.9 → 43.8 | 321,088 → 109,432 |
| 64／256 | 188.5 → 48.8 | 217.8 → 51.5 | 409,872 → 114,040 |
| 256／4 | 540.6 → 154.0 | 685.5 → 172.7 | 1,163,792 → 426,888 |
| 256／64 | 569.3 → 157.5 | 672.1 → 193.1 | 1,189,360 → 428,424 |
| 256／256 | 648.1 → 166.8 | 756.4 → 207.1 | 1,274,032 → 433,032 |

12个形状的allocation中位数均下降，幅度63.1–72.2%。64／64 total p50下降61.5%，256／256下降74.3%。保留2／64 p95约5.5%的回退和2／4 p95持平；没有因为分配下降而宣称所有小负载尾部改善。该结果只说明机制成本，不等于炉子吞吐或完整服务器Tick收益。

## 真实安装服结果

10个未profile服务器进程、36个稳态窗口，共**21050个原生Tick样本**；另有两个独立JFR进程／两个窗口／2374个Tick样本。12个进程全部正常保存退出，合计**2160000次原始炉子正文、10800个加工输出**。每个跨版本窗口完整前后NBT、正文、输出、通知及lease数量一致；失败／跳过／串行回退／任务拒绝／资源拒绝均为零。实际persistent模式与4worker线程由运行证据核验。

下表只合并同一进程同一数量的两个连续窗口，**不合并独立JVM**。主场景两轮启动顺序为`.58→.59`与`.59→.58`，表中数值始终为`.58→.59`。allocation为server＋worker线程的精确MXBean分配差分／其实际观测时长，MB使用十进制。

| 独立轮次 | 同Chunk炉子 | mean MSPT ms | p95 ms | allocation MB/s |
|---|---:|---:|---:|---:|
| 0，旧→新启动 | 2 | 0.9181 → 0.7884 | 1.0857 → 0.9618 | 3.9522 → 2.0784 |
| 0，旧→新启动 | 64 | 2.1765 → 1.2067 | 3.5185 → 1.4458 | 12.6462 → 6.2085 |
| 0，旧→新启动 | 256 | 3.6469 → 2.7837 | 4.1276 → 3.2417 | 50.9160 → 28.4301 |
| 1，新→旧启动 | 2 | 0.9263 → 0.7767 | 1.1222 → 0.9474 | 3.9503 → 2.0805 |
| 1，新→旧启动 | 64 | 1.2859 → 1.1240 | 1.4484 → 1.3538 | 12.5201 → 6.1316 |
| 1，新→旧启动 | 256 | 3.4592 → 2.4565 | 3.7414 → 2.6950 | 50.6471 → 28.2571 |

主场景6组配对的分配、mean和p95均下降：分配降低约**44–51%**。64炉mean降低44.6%／12.6%，256炉降低23.7%／29.0%。第一轮`.58`的64炉两个窗口mean分别1.5333／2.8174ms，差异很大；不能将第一轮较大的降幅推广为稳定总体收益，也不能从两个独立JVM配对生成可靠置信区间。原样数据和GC全部保留，未剔除较慢窗口。

当前资源完整BE阶段及分段计时如下，单位ms／精确gameTick。`proofAndPlan`只含初始capture与plan，`revalidation`覆盖fresh准备和资源集比较，`reservation`含整波验证／签发；三者没有覆盖全部阶段，不能相加当MSPT。

| 轮次／炉子 | proofAndPlan | fresh revalidation | reservation | fullPhase |
|---|---:|---:|---:|---:|
| 0／2 | 0.05252 → 0.02831 | 0.07767 → 0.02213 | 0.03229 → 0.01180 | 0.59986 → 0.46856 |
| 0／64 | 0.52538 → 0.11457 | 0.38773 → 0.12334 | 0.12849 → 0.04028 | 1.63138 → 0.77423 |
| 0／256 | 0.68452 → 0.20989 | 0.43195 → 0.22058 | 0.17407 → 0.07911 | 1.88699 → 1.08564 |
| 1／2 | 0.05487 → 0.02758 | 0.06542 → 0.02244 | 0.03612 → 0.01183 | 0.59823 → 0.45898 |
| 1／64 | 0.22177 → 0.09623 | 0.14786 → 0.09224 | 0.06716 → 0.03577 | 0.91457 → 0.70280 |
| 1／256 | 0.62006 → 0.16869 | 0.40536 → 0.17526 | 0.15907 → 0.07080 | 1.76382 → 0.94549 |

CPU100%代表一个逻辑核：64炉两次分别19.35→15.14%、15.00→14.52%；256炉19.29→16.85%、19.11→16.13%。CPU不是按MSPT降幅同比下降，进程CPU还包含JIT、GC和监测服务。对应64炉MXBean GC为1次／149ms→1次／13ms、1次／13ms→0；256炉为2次／28ms→1次／14ms、5次／48ms→1次／16ms。MXBean collection time不等于完整STW暂停时间，不将这些GC数字直接解释成全部尾部来源。

未profile窗口的观察TPS为19.944–20.027。所有场景接近20TPS限速，并未测量饱和吞吐。正文累计最大并发在2炉时为2，在64／256炉时为4，两版一致；这是since-boot最大值，不是每窗口的并发分布或平均worker利用率。

## 关闭控制与跨Region复验

| 条件 | 独立启动顺序 | mean MSPT ms，旧→新 | p95 ms | allocation MB/s |
|---|---|---:|---:|---:|
| 64同Chunk，资源关闭 | 新→旧 | 0.7874 → 0.7415 | 1.0325 → 0.9703 | 2.3487 → 2.2801 |
| 64不同Region，资源开启 | 旧→新 | 1.4840 → 2.1631 | 2.5376 → 2.3536 | 12.4999 → 6.2137 |
| 64不同Region，资源开启，追加复验 | 新→旧 | 1.5638 → 1.0922 | 1.9399 → 1.2844 | 12.5133 → 6.2155 |

关闭控制中，所有资源分段计时／签发数仍为零。共享的catalog递归预算容器也用于原有炉子路径，因此OFF存在约0.069MB/s的分配下降；不能把这部分全部归入新资源表。该控制是同条件跨版本比较，启动历史只有64炉，与主场景先2再64再256不同，不能当精确的同版本ON/OFF增量实验。

跨Region首轮是必须保留的负面结果：候选mean／p50和三个proof子计时回退，虽然allocation减半、p95下降。GC日志记录一个**184.856ms G1 mixed pause**，MXBean约185ms；最大native Tick为187.510ms。但另一个无GC窗口的mean仍回退，不能把该结果全部归因于GC。

因此在相同flags／预热／输入／窗口下反转启动顺序重跑：allocation再次约减半，而mean下降30.2%、p95下降33.8%；fullPhase从1.11836降至0.67655ms／tick。第一次的fullPhase则为1.06812→1.52142ms／tick，两次结果都发布。证据支持**真实分配成本稳定下降**，尚不支持跨Region时延收益普遍稳定，也没有对第一次回退指定未观测到的JIT／宿主调度根因。WSL宿主频率与调度未锁定，有限预热不保证所有路径处于最终C2状态。

## 独立JFR诊断

同Chunk64炉／4workers／资源开启，分别运行旧、新包；JFR启动后额外丢弃200Tick，再测1200Tick。该组未进入上面的性能统计。

| 诊断项 | `.58` | `.59` |
|---|---:|---:|
| mean／p95 MSPT ms | 2.1992／4.1127 | 1.0279／1.2847 |
| server＋worker allocation MB/s | 12.0228 | 5.7847 |
| 进程CPU，单核百分比 | 28.22 | 22.61 |
| Server线程：ownership＋planner执行样本 | 347＋80 | 33＋1 |
| Server线程：同组合计allocation采样weight | 501768544B | 143888536B |
| Server线程：catalog审计执行样本 | 129 | 82 |
| 活动窗口GC pause事件 | 0 | 0 |
| Compilation事件 | 754 | 712 |

`.59`将执行规划搬入Ownership，因此比较caller时合并ownership与planner；不能将类别移动当性能收益。样本数量和allocation weight不是精确CPU时长／字节，本表的强弱证据分别来自采样与独立MXBean，不能将诊断的分位数当未profile收益。

旧侧热点主要是HashMap插入／扩容、IdentityHashMap扩容和不可变Map/Set探测；新侧主要看到当前catalog审计、身份数组／整数索引、ResourceTable.slot及chunk查询。新侧Binding、Identity、Claims数组和capture仍有分配，JFR没有证明“资源开销消失”。下一步若继续降低成本，应先看当前pass内的catalog遍历、局部描述构造和既有派发／join成本，保留失效与闭包证明。

两个诊断窗口没有超过1ms阈值的monitor enter事件。每个worker在约59秒活动内park约57.9–58.1秒，主要是池空闲／dispatch等待；Server barrier相关park总计11.491→7.454ms，不能把累计park解释为资源锁竞争。旧侧有一次`RegionWorkerPool.unparkOneParked`的`unstable_if`去优化，新侧无；正常异步编译事件不能直接算服务器阻塞，也不能据此给全部时延变化定因。

二进制JFR用256MiB上限流式提取，两侧所有选定事件类型的总数量与JDK原生`jfr summary`交叉核对一致。完整活动区间、编译／去优化、GC、monitor、park、CPU及allocation采样摘要均在JSON中。

## 交付与结论

| 本轮检查 | 结果 |
|---|---:|
| 核心JUnit | 1221项通过，零失败／错误／跳过 |
| Resource开启，fixed／persistent定向回归 | 各112项通过 |
| 实际未知Mixin＋Resource＋persistent控制 | 1项通过，完整串行保留 |
| 测量协议与跨版本pinning／等价性测试 | 12项通过 |
| root/tests格式与许可证 | 通过 |
| 当前生产代码与冻结候选源码hash、22个相关class（其中ownership/planner为19个）、实际安装universal | 一致 |
| 两版patched Minecraft server jar内1627个class | 逐个字节一致 |
| 原`.58` installer／universal | 原SHA-256保持不变 |

原离线installer构建因工具缓存缺少binarypatcher而拒绝；补齐构建依赖后成功生成并安装，不计为测试成功或生产故障。本轮没有修改Vanilla patch或Create准入，未将旧GameTest／Create结果计作本轮重跑。

候选产物为`neoforge-21.1.250.59-beta-installer.jar`，11019969B，SHA-256：
`a8af404408d99d4a3bc37267bcf829cc5a6c2637cd2fd8bf0f572018beac2fd4`。
Universal为4368494B，SHA-256：`772c6cff4dc2c83033a5465af753830a5b795703fa0a3b7b69c0c5866a42e25d`。
所有私服使用独立新世界，原配置、世界与mod jar均未更改。运行日志、世界、口令、binary JFR及jar留在本地，交付代码、协议测试和导出的完整数据。

本阶段达到了先降低分配／准备成本的目标：相同公共机制路径分配下降63–72%，真实资源开启场景下降约44–51%，同Chunk三个规模的两次独立配对均减少mean／p95及阶段耗时。**这不等于已证明默认开启资源层具有净收益。**跨Region有一次mean回退且复验方向不同；饱和吞吐、玩家／modpack、补燃料、Hopper／Container／Capability／Create均未由这些数据证明。继续默认关闭、不扩大BE准入。
