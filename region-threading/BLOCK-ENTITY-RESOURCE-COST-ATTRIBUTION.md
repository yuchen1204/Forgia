# Resource Ownership Cost Attribution & Hot Path Optimization

本阶段基于 `e774553df4a09a2bb3fe3595dd964f96de723110`，比较冻结 `.59-beta` 与候选 `.60-beta`。先完成源码调用链归因和独立真实安装服 JFR，写下优化选择，再修改生产实现。没有增加 BE 类型、修改默认值或放宽当前操作证明。实现、工具、核心／动态回归及安装服证据由同一助手串行完成。

本轮通过减少不可变本地描述与临时身份表的重复分配，降低资源ON相对OFF的allocation增量35.1–36.6%。**完整Tick时延优化未验收通过**：相对`.59` ON，候选mean／p95只有1/4独立配对下降；八组fence控制也回退。10分钟自身ON/OFF的mean增加16.9%、p95增加51.9%、p99增加146.3%，分配仍增加51.2%。当前交付是可重复的分配下降和有边界的成本归因，不能宣布Resource ON优于OFF。Resource Ownership继续默认关闭，BE准入保持原样。

## 源码级成本归因

完整 Tick 中的入口为 `RegionThreadingEngine.tickFurnaceWorkWaves`。初始 pass 收集 canonical admissions，检查全 phase 库存 alias，以每个实际出现的 cooking type 遍历当前 Catalog，构建声明并进行 whole-phase 依赖／home／shared-WRITE alias 规划。初始计划不签发执行权限。

对每个多单元 wave，coordinator 取得 canonical 外层 Region Lease、投递 mailbox，然后新建 `FurnaceWorkPreparation`，再次审计当前绑定、库存、配方、ingredient／OR children／结果及 components、通知闭包，并重新扫描全 phase 库存 alias。当前捕获权限集必须与初始计划一致。随后 fresh reservation 重新检查 READ/WRITE、home、所有普通 Binding validator、每个 distinct shared proof、预算及最终生命周期／Region 边界，才发布 one-shot Lease。join、按原序通知 commit、scope／Lease 释放仍在原有外层排他内完成。

| 成本 | `.59` 的具体来源 | 必须保留的语义 | 本轮处理 |
|---|---|---|---|
| Catalog 遍历 | 每个 fresh pass／type 调用 `getAllRecipesFor`，遍历 recipe／ingredient／result／components；`getIngredients()` 创建 NonNullList／ArrayList | 未知 serial callback 能原地修改结果／库存或替换 Catalog；manager 身份不证明内容没变 | 保留全部 fresh 遍历；不缓存成功结论 |
| Catalog 资源声明 | identity set、publication list、SharedReads 身份数组／索引；OR merged cache 去重后仍需捕获每个 child 自身 cache | 全部实际可读 mutable stack 都必须进入 READ publication，逻辑预算照完整使用计费 | 保留当前 publication；没有把它误算为纯 Planner 成本 |
| 本地 Claims／Binding | 初始捕获和 fresh 捕获各创建 Builder、临时／不可变数组、Binding、Identity 与槽位 predicate | 当前 source／chunk／ticker／槽位附件必须与声明相符，每个 validator occurrence 都执行 | 同 phase 内共享不变的不可变本地描述；当前验证结果不共享 |
| Dependency Planner | whole-phase identity table、home/global alias检查、directed depths／waves | canonical READ/WRITE 顺序及未知完整 fence | 全部重算；只复用已清空 table 容量 |
| Fresh Reservation | 新的 occupancy／home table、边界 map、每个 Binding 与 shared predicate、Lease | 整波 all-or-nothing、无跨 home/global WRITE、最终生命周期重检 | 只复用清空后的表容量；冲突／验证／Lease仍全做 |
| Serial Fence | 已有未知 BE／通知动作完整串行；多 wave 时重复 Catalog、全 phase alias、Region leasing、dispatch／join | 不可跨过未知 callback 或改变通知顺序 | 保留 fence；通过相同对象的注册顺序控制独立测量重复成本 |
| 正文与生命周期 | worker scope、ThreadLocal权限、one-shot CAS、active scope、join／commit、Region释放 | 已执行效果不重放，不提前释放 | 完全保留 |

`Ingredient.getValues()`／已初始化的 `getItems()` 返回当前数组，并非每次重新分配。主要 Catalog wrapper 分配来自 `AbstractCookingRecipe.getIngredients()` 与当前 recipe list 等；OFF 同样支付其中一部分。不能把整个 Catalog 时间／分配都归为资源 ON 的增量。

标准混合负载虽每 Tick 有约48个完整串行 BE，实际大多数 Tick 仍只有一个多单元炉子 wave；此前负面结果不能主要归因为几十次 worker 调度。八组 fence 控制只调整同一64台机器与32个 Hopper的 ticker 注册顺序，其余位置、库存、配方、世界负载和完整状态 oracle保持相同。两个负载独立统计。

### 计时边界

- `furnace.prepare` 包含初始 Catalog／局部审计、声明与规划；`resource.proofAndPlan` 只计资源捕获与资源规划，**不包含此前 Catalog 审计**，是前者的子集。
- `resource.revalidation` 包含外层 Lease／mailbox 后的 fresh Catalog、whole-phase alias、当前候选审计、当前 Claims与权限集合比较。
- `resource.reservation` 计 `tryAcquireAll`；其前面的 owner／boundary map构造不在该 timer 内。
- `resource.fullPhase` 包含完整该层 BE phase：初始准备、串行 BE、外层 Lease／mailbox、fresh准备／reservation、worker dispatch／join、commit与cleanup。它不是完整服务器 Tick；`furnace.executeAwait`又包含worker与等待的重叠时间。
- 因此不能将这些嵌套分段相加当作 MSPT。完整 Tick 以原生 `MinecraftServer.tickServer` duration 为准，allocation以Server＋Region Worker MXBean计数为准；JFR只解释采样到的 caller／class。

## 按证据选择的优化

`.60` 的 `Candidate` 记录本次审计得到的三个实际槽位引用，以及当时每槽是否非空。fresh pass 完成原有审计后，只有 source、实际 chunk、全部槽位引用和捕获的空／非空状态相同，才从初始 Claims共享不可变本地 Binding／normalized permission arrays，并接上**全新** SharedReads。Binding保存的是待执行的当前检查函数，并未保存上一次成功的布尔值；reservation仍运行全部普通 validator occurrence。任意槽位变化都完整重捕获，资源权限并集不同则沿用整 wave 串行拒绝；槽位互换但并集相同也使用新附件检查，不伪造旧-slot成功。

`Scratch`属于当前 phase的单一coordinator，只保留table容量。规划及每次reservation仍分别重建identity／home／occupancy并重算各自所有条件；每条成功、拒绝和抛异常路径均在`finally`清空全部keys、homes、flags，worker不从scratch取得权限。没有跨Tick池，没有旧resource身份或成功证明残留。READ/WRITE、512／unit、131072／phase、2048units预算及现有outer Region约束不变。容量复用来自Furnace核心桥在同一phase传递同一个内部scratch；独立调用公开internal planner并另建owner的其他路径不自动获得这一收益，也没有据此宣称所有通用调用都更快。

容量复用也有明确取舍：scratch可能保留初始整phase的大表，而后续小wave若独立分配只需较小容量；当前reservation的flags扫描与`finally`清空按保留容量执行。因此“省掉数组分配”不等于“减少所有CPU工作”，八组fence的时延回退必须保留。这是可继续研究的实现成本（例如完整验证过的occupied-slot清理／扫描），不是可以用安全机制必需来解释掉的开销。本轮没有把它宣称为MSPT优化成功。

没有本轮证据支持删除后续wave的Catalog重审：未知串行callback缺少完整且强制的effects invalidation协议。也没有安全理由把Hopper fence移开或授权新的BE。Catalog wrapper、初始声明、Planner depth arrays和Lease／Scope仍有成本；“必须保留当前检查”并不表示当前所有对象包装都是理论上不可优化，只表示本轮没有用未经证明的缓存替代它。

## 测量方法与结果

相同i5-1135G7／4物理8逻辑核、WSL2、JDK21.0.13+9、2GiB固定G1、seed813584、4persistent workers、正常20TPS。两侧Furnace waves和BE slice都开启，只切资源开关；其他并行slice仍关闭。使用私有新世界和固定安装包hash，不装observer mod／Mixin／instrumentation agent，不改真实服务器配置或世界。

标准混合负载与上阶段相同：32Furnace、16Blast Furnace、16Smoker，三种原版配方统一200Tick；32循环Hopper、8酿造台、8箱子、8日光、8Comparator、8红石时钟／灯、32普通NoAI实体及正常世界phase。每进程1200Tick预热＋2×600Tick窗口；同Region／跨Region分别两次独立JVM配对，ON/OFF与版本启动顺序反转。内部active JMX区间与完整精确gameTick状态／工作窗口分开；连续窗口不是独立JVM重复。未固定宿主频率／调度，不生成未经支持的时延置信区间。

| 版本／布局／独立轮次 | mean OFF→ON ms | p95 | p99 | max | allocation MB/s | GC count／time |
|---|---|---|---|---|---|---|
| 59／同Region／0 | 1.2153 → 1.5021 | 1.5879 → 1.8848 | 1.9488 → 2.2312 | 4.3360 → 20.0441 | 4.239 → 7.831 | 1／19ms → 1／18ms |
| 59／跨Region／0 | 1.3608 → 2.0960 | 1.6953 → 3.6057 | 1.9681 → 4.0048 | 2.5904 → 19.3355 | 4.485 → 8.094 | 0／0ms → 1／17ms |
| 60／同Region／0 | 1.9376 → 1.6868 | 3.0470 → 3.1065 | 4.0671 → 6.0953 | 7.9551 → 21.6666 | 4.277 → 6.571 | 0／0ms → 1／19ms |
| 60／跨Region／0 | 2.7879 → 3.1610 | 3.8025 → 4.4194 | 4.3526 → 6.0310 | 9.7010 → 56.1709 | 4.523 → 6.811 | 0／0ms → 1／52ms |
| 59／同Region／1 | 2.6430 → 2.4388 | 4.0631 → 3.6772 | 5.6164 → 5.0087 | 113.9904 → 19.8662 | 4.208 → 7.789 | 1／107ms → 1／17ms |
| 59／跨Region／1 | 2.7740 → 10.0170 | 3.9181 → 21.0889 | 5.1591 → 42.3796 | 6.7288 → 727.8097 | 4.464 → 8.008 | 1／38ms → 2／794ms |
| 60／同Region／1 | 2.7944 → 2.9600 | 3.8691 → 4.1259 | 4.6980 → 4.9148 | 33.0063 → 6.2598 | 4.276 → 6.551 | 1／30ms → 0／0ms |
| 60／跨Region／1 | 3.1770 → 5.1044 | 6.7544 → 14.3087 | 13.1500 → 18.5987 | 20.6054 → 27.3293 | 4.528 → 6.827 | 0／0ms → 0／0ms |

| 资源ON：布局／轮次 | `.59→.60` mean ms | p95 | p99 | allocation MB/s | 分配变化 |
|---|---|---|---|---|---|
| 同Region／0 | 1.5021 → 1.6868 | 1.8848 → 3.1065 | 2.2312 → 6.0953 | 7.831 → 6.571 | -16.1% |
| 跨Region／0 | 2.0960 → 3.1610 | 3.6057 → 4.4194 | 4.0048 → 6.0310 | 8.094 → 6.811 | -15.9% |
| 同Region／1 | 2.4388 → 2.9600 | 3.6772 → 4.1259 | 5.0087 → 4.9148 | 7.789 → 6.551 | -15.9% |
| 跨Region／1 | 10.0170 → 5.1044 | 21.0889 → 14.3087 | 42.3796 → 18.5987 | 8.008 → 6.827 | -14.7% |

| 资源ON：布局／轮次 | proof+plan ms/tick | fresh ms/tick | reservation ms/tick | full BE phase ms/tick | server CPU，100%=1核 |
|---|---|---|---|---|---|
| 同Region／0 | 0.0827 → 0.1283 | 0.0863 → 0.0930 | 0.0361 → 0.0388 | 0.8583 → 0.9367 | 8.3096 → 8.6372 |
| 跨Region／0 | 0.2055 → 0.1992 | 0.2091 → 0.2233 | 0.0753 → 0.1282 | 1.2392 → 1.6929 | 9.1873 → 17.1365 |
| 同Region／1 | 0.1536 → 0.1898 | 0.2026 → 0.2056 | 0.0698 → 0.0798 | 1.3767 → 1.6266 | 14.8635 → 17.3587 |
| 跨Region／1 | 1.1668 → 0.6133 | 1.8547 → 0.4904 | 0.4275 → 0.1160 | 6.1101 → 2.8840 | 28.4569 → 21.1466 |

资源ON相对旧实现的分配在四组独立对照均下降；mean在1/4、p95在1/4组下降。把各自OFF同时扣除后，资源分配增量下降35.1–36.6%。下表同时保留时延增量，不能用单侧ON改善代替净开销判断。

| 布局／轮次 | mean 的 ON−OFF 增量 `.59→.60` ms | allocation增量 MB/s | 分配增量下降 |
|---|---|---|---|
| 同Region／0 | 0.2868 → -0.2508 | 3.593 → 2.294 | 36.2% |
| 跨Region／0 | 0.7352 → 0.3731 | 3.609 → 2.288 | 36.6% |
| 同Region／1 | -0.2041 → 0.1656 | 3.581 → 2.275 | 36.5% |
| 跨Region／1 | 7.2430 → 1.9274 | 3.544 → 2.299 | 35.1% |

Candidate记录更多当前槽位引用，也影响共同准备路径的对象表示；全部OFF控制保留于上表和JSON，不能把OFF变化都归为噪音。独立进程仍有预热／宿主时序差异，GC及所有慢窗未删除。资源ON没有新增可执行正文，Lease数量与原始正文相等不构成吞吐收益；本轮不测饱和吞吐。

### Serial fence 控制

| 版本／条件 | mean ms | p95 | p99 | allocation MB/s | GC | waves/tick | prepare ms/tick | fresh | reservation |
|---|---|---|---|---|---|---|---|---|---|
| 59／OFF | 4.3232 | 5.8314 | 7.7975 | 6.871 | 0／0ms | 7.9950 | 0.4000 | 0.0000 | 0.0000 |
| 59／ON | 5.6222 | 7.9005 | 12.5822 | 11.639 | 1／58ms | 7.9950 | 0.6410 | 0.9207 | 0.1679 |
| 60／OFF | 4.3155 | 5.5821 | 7.0183 | 6.952 | 0／0ms | 7.9950 | 0.4079 | 0.0000 | 0.0000 |
| 60／ON | 8.3304 | 19.8955 | 28.4079 | 10.106 | 0／0ms | 7.9950 | 1.3675 | 1.8458 | 0.2601 |

八组控制实际约7.995waves/tick，普通混合约1wave/tick；每Tick的native／serial工作量不变。`.59`资源分配增量4.768MB/s，`.60`为3.155MB/s，下降约33.8%，但候选mean／p95／p99在本次控制均回退，不能称为时延优化通过。fence造成重复Region lease／mailbox、whole-phase alias审计、Catalog与dispatch／join，这部分不会因为声明存储变少而消失。该控制每版本只有一次独立ON/OFF配对，不将其当作可靠时延置信区间或选择性提速证据。

### 独立 JFR 诊断

`.59` 与 `.60`各自有独立ON/OFF进程，1200Tick预热＋200recorder预热＋1200Tick测量。它们不进入正式未profiled时延表。流式读取binary JFR（`-Xmx256m`），所有11种选定事件的全量计数与JDK`jfr summary`核对，再裁剪到内部active interval。采样保留最多24个方法名以区分initial／fresh Catalog、local capture、规划与reservation；原binary仍是完整stack的权威来源。

| 服务器线程阶段 | `.59→.60` execution samples | allocation sample weight MB |
|---|---|---|
| initial catalog audit/publication | 111 → 120 | 36.078 → 33.510 |
| fresh catalog audit/publication | 49 → 59 | 32.509 → 40.004 |
| initial local claims capture | 31 → 22 | 61.119 → 57.167 |
| fresh local claims capture | 22 → 6 | 49.848 → 2.095 |
| whole-phase dependency and alias planning | 40 → 45 | 67.251 → 70.703 |
| fresh whole-wave reservation | 21 → 22 | 42.861 → 6.651 |

fresh local capture的allocation采样weight从49.848降到2.095MB；旧侧Binding／Identity／predicate与多个数组均可见，新侧该分类只采到新的Claims壳。fresh reservation从42.861降到6.651MB，而初始Claims约61.119→57.167MB、规划约67.251→70.703MB，表明它们的成本仍在。Catalog初始＋fresh合计约68.587→73.514MB，没有因本轮优化消失。源码变化与这两个具体采样下降相符，并与未profiled MXBean allocation下降交叉印证；不是把调用者分类移动当作收益。

两个ON活动窗口均无GCPhasePause和>1ms JavaMonitorEnter事件；Compilation1167→1059，deopt6→1，旧侧包括TimSort／ForkJoinPool／RMI Arrays.fill，新侧为后台Worker-Main的CompletableFuture.postComplete。没有证据用这些事件给全部主矩阵时延定因。worker park仍包含大量正常idle／dispatch，全部编译／deopt、park和native样本在JSON保留。

JFR allocation weight不是精确字节，执行样本不是方法累计CPU。没有把park总量当作资源锁争用；包含正常worker idle、20TPS限速和managed barrier。MXBean GC collection time也不是全部STW或GC CPU，异常／编译／monitor事件仅在有明确证据时归因。

### 有界持续运行

| 指标 | OFF | ON | 变化 |
|---|---|---|---|
| mean | 1.4327 | 1.6742 | +16.9% |
| p50 | 1.2202 | 1.4075 | +15.3% |
| p95 | 2.2015 | 3.3439 | +51.9% |
| p99 | 2.5871 | 6.3722 | +146.3% |
| maximum | 182.9219 | 50.8863 | -72.2% |
| allocation MB/s | 4.494 | 6.797 | +51.2% |
| CPU，100%=1逻辑核 | 15.7760 | 18.2565 | +15.7% |
| GC count／time | 4／240ms | 7／113ms | 不等于全部STW／CPU |

各对应检查点完整BE／确定性实体状态、产物和route一致。前3窗与后3窗只描述同一JVM的有界趋势，不视为独立重复：

| 条件 | mean 前3→后3 ms | allocation 前3→后3 MB/s | 逐窗allocation范围 |
|---|---|---|---|
| OFF | 2.0326 → 1.1731 | 4.506 → 4.490 | 4.486–4.522 |
| ON | 2.3518 → 1.3510 | 6.811 → 6.788 | 6.786–6.833 |

本轮为每条件10分钟active gameTick，含静止补料检查点。上一阶段[30分钟冻结`.59`负面基线](BLOCK-ENTITY-RESOURCE-MIXED-VALIDATION.md#30分钟跨region稳定性对照)保留，未冒充本轮重跑。Heap-used锯齿不能证明retained heap稳定；没有在计时内强制full GC或heap dump，不能宣称排除慢泄漏。

## 正确性回归与交付范围

共30个正式服务器进程正常保存并exit0，70个含native metrics窗口＋4个管线正确性窗口，57600个精确gameTick、51786个原生Tick时间样本，3640175次原始worker正文、2887825次完整串行BE Tick和18304个产物。稳态、fence、profile、soak及管线配对的完整状态与路由核对一致；动态组的异步IO差异按下文边界单列。安全／任务／正文失败及资源拒绝为零。

完整动态回归重新执行scheduled当前库存components变化、同位置BE替换、真实400Tick recipe reload与200Tick恢复、原版perf holder不在内存证明及32原UUID唯一保留后的重新加工。前4窗口ON/OFF完整状态、native／serial／通知计数相等。异步卸载／加载后的第5窗口before-state不同，OFF／ON native10785／10786、serial11615／11614、通知39／40；每侧总22400次BE Tick、完整有限效果、产物、守恒与UUID检查通过。这里保留1次路由差异，不声称两个不同IO历史的逐项相等或单一归因。

真实连接式Hopper→Furnace→Hopper→箱子管线保留原先每条件两个1200Tick窗口；128tickable BE/tick、逐线守恒／实际抽取、普通世界／实体Tick与完整状态均重新验证，受fence影响的8炉仍保持串行。核心1230、fixed121、persistent121、实际未知Mixin1、Python协议19项均通过，零失败／错误／跳过；格式／许可证通过。

新增六项Java测试分别验证scratch多次规划／冲突／false validator／抛异常后的重新签发、所有captured identity的清理、共享描述仍执行每个重复local validator且只使用新shared proof、空槽出现新对象后完整重捕获、同一zero-count stack原地变非空后不能继承省略的WRITE、槽位互换保留当前attachment语义。现有READ/WRITE参考差分、预算、chunk reload／ticker replacement、原地组件变化、unknown callback与部分效果后异常不重放等全部保留。

最初临时格式脚本误作用于`buildSrc`，已修正并重跑；首次新测试置于生产包触发FML模块拆包拒绝启动，已移至现有unittest包并使用反射内部入口。两次都不计通过，也没有因此扩大生产API。首版候选在源码复核发现“同一zero-count stack可原地变非空”后主动停止，补上捕获时empty/nonempty mask与回归；首版全部测量移入探索归档，不计本报告正式验收，修正版完整反序矩阵、动态与管线重新运行。未重跑Create／GameTest，不把历史结果算作本轮通过。

生产改动只涉及Ownership、Furnace准备桥和Engine调用点；没有Vanilla patch、配置默认值或BE准入改动。新工具可复现JFR归因和八组fence控制，exporter拒绝profiled／unprofiled混池。完整接受窗口、原始native Tick、状态、allocation／CPU／GC／heap、JFR分类及失败边界见[机器可读记录](BLOCK-ENTITY-RESOURCE-COST-PROFILE.json)，方法见[安装服工具说明](../tools/block-entity-resource-ab/README.md)。运行世界、口令／properties、日志、JFR、perf ZIP、jar与构建缓存留在本地，不属于交付源文件。

Lease不是Java sandbox；任意无hook字段写入、未知外部异步Mod、玩家／活跃AI／完整modpack和饱和吞吐仍不在本轮证明范围内。后续成本工作应继续区分安全语义与对象表示开销，以同一完整Tick ON/OFF验收；新增BE仍需独立完整effects closure审计。
