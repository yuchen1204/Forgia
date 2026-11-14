# C4.1 Canonical Coordinator Lifecycle & Soak Validation

基线 `9869e3f720bfcc9cc967aa04686de2b5806cb903`，Minecraft 1.21.1／Forgia NeoForge `21.1.250.60-beta`。本片不新增BE并行类型、不扩大Adapter准入、不改默认开关／资源预算，不做MSPT优化，停在C4.1。**生产生命周期缺陷已修复；JUnit与安装服证据和GameTest红色结果分别报告，GameTest未全绿，不能宣称整个验证门槛通过。**

## 生产修复与可重现证据

1. `a57376d48bdee6af804fa7978c4d62f1920eee82`：在engine monitor／lifecycle lock前拒绝Worker或active server callback的生命周期自等待。原先另一个cleanup持monitor等待phase时，phase内callback可先卡在同一monitor，无法到达后置自等待检查。两个有界真实mixed-phase案例在旧源码各失败，controller只释放测试monitor以避免测试JVM永久死锁；修复后通过。work graph只在shutdown真正被接受时停止，拒绝请求不改状态。
2. `4fa74eff06e5b6d7485287a8bb33bd268c65cb6c`：完整core发现一个旧异常消息兼容性失败，恢复生产代码的原shutdown／retire消息。未修改旧断言；23项handoff/lifecycle回归通过。
3. `5d24c2bf5d7bd505b3b38aa16dc523cca35b8ca4`：在Native v1 prepare callback前发布并标记**同一个**canonical batch。原先外部unload/shutdown可在prepare仍执行、active batch尚未发布时越过它。两个有界并发案例在旧源码实际失败，修复后116项canonical/Adapter/handoff通过。prepare仍在server一次、早于Worker，Furnace prepare=0保持；没有第二个batch、Ticket或lease系统。

实际源码见 [RegionThreadingEngine](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java)，新增 [LifecycleTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityLifecycleTest.java) 与 [RecoveryTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityRecoveryTest.java)。原C1／effects／inventory断言全部保留，未禁用、过滤或吞失败；support仅增加layout参数，原默认fixture行为保持。

新增14个invocation覆盖：mixed Native/Furnace完整prefix与Fence中的锁前拒绝、已发布prepare与外部cleanup；原始pool真实close后Fixed/Persistent拒绝回退；200个连续mixed phase、1600正文和每phase kernel清理及独立serial oracle状态；started异常的必要效果有序commit且不重放、未启动mixed后缀下个phase仍执行；Furnace通知失败继续其余必要commit、下个phase不重复产物；Fence内chunk实例/binding替换及pending下一phase；fine正文和commit期间禁止fixed Region retirement、Native后缀取得fresh home；真实dynamic regionizer在两原lease中延后merge、下phase使用合并home。最后两类是有界JUnit world seam，不冒充磁盘unload或安装服。

## JUnit

| 配置／范围 | 通过／总数 | failure／error／skip |
|---|---:|---:|
| `final-v4-fixed-on` | 262/262 | 0/0/0 |
| `final-v4-persistent-off` | 262/262 | 0/0/0 |
| `final-v4-persistent-on` | 262/262 | 0/0/0 |
| `final-v4-full-core-fixed-off` | 1328/1328 | 0/0/0 |

全部命令真实exit0；full core1328，另外三配置各262，84canonical。

原1244项本轮再次实际重跑并以XML Counter核对，原C1八项目标及全部84个canonical相关invocation通过。旧managed-wait取消／interrupt、Worker exception、fixed rejection、unload/shutdown等待physical join／commit案例保持；基础真实pool的noncooperative close／interrupt／accepted queue drain也在full core执行。新正常mixed案例继续实际Native/Furnace跨Region overlap=2，故障案例的串行回退不替代并行验收。

## GameTest：独立、非全绿证据

GameTest使用独立真实游戏测试server，加载仓库测试模组；这是development GameTest，不是下面的安装服。缺失DevLaunch1.0.2的offline启动失败未执行测试，补齐原artifact并核对发布checksum，之后确实执行完整测试。旧测试源码/断言未改。

| 独立GameTest run | 实际退出／结果 |
|---|---|
| `gametest-current-v4` | Gradle1；framework XML tests=228 failures=4 skipped=5 |
| `gametest-current-v5` | Gradle1；framework XML tests=228 failures=4 skipped=5 |
| `gametest-current-v6` | Gradle1；server crash中断，无完整XML |

原事件测试testFeatureGating的disabled-custom-flag失败也保留，不能计为该XML的required失败集合或隐去。4个required失败名称：`blockEntityOwnerOperations`、`furnaceTicksOnRegionWorker`、`unsafeTickerForcesWholeRegionFallback`、`savesAndReloadsRegionThreadingState`。

BE-focused全suite的旧required失败涉及声明型custom source期待旧Native Worker、单炉期待旧coarse Worker、unsafe加入前单炉必须先有Worker，以及全切片save/reload所需配置。当前核心仅放行已证明Hopper分支、Furnace保留single-unit完整serial；不能为这些旧期望重开未知Mod正文或调整现有安全/策略门槛。修正POI配置后保存重载案例进一步要求entity/chunk切片。全切片额外组合在测试用CustomSuperSustainingBlock因真实Cactus随机生长的方向回调抛异常后crash，完整日志及crash report保留；未完成的run没有JUnit总结，不能当成全suite结果。异常栈经过chunk random tick/neighbor更新，未据此宣称已找到/排除全部根因；同条件参考控制与跨切片组合修复仍未覆盖。

这些GameTest结果**没有计作通过**，没有关闭BE并行、放宽准入或删除旧断言。正常closed cohort的canonical/lifecycle保证由上面的真实kernel JUnit与下方独立安装服证据支持；旧声明型Mod Worker保证不属于尚未进入的C5协议。

## 真实安装环境与协议

环境：11th Gen Intel(R) Core(TM) i5-1135G7 @ 2.40GHz，8logical CPUs；Linux-6.18.40.1-microsoft-standard-WSL2-x86_64-with-glibc2.41；JDK21.0.13+9。原生Windows宿主/WSL调度与频率未锁定，不作性能比较。生产源码HEAD `5d24c2bf5d7bd505b3b38aa16dc523cca35b8ca4`。

| Binary | bytes | SHA256 |
|---|---:|---|
| installer | 11085897 | `55d2c2ac213412880a6034734504912378e7d42a8768095665a19ef8c9ed38bf` |
| universal | 4433432 | `5b673b107574ee1525965153e0297f2ee0eca2a0f52ea4cc28486ef8f9829923` |


安装器从本轮生产源码构建，安装目录独立；382个threading/TimeTracker class与compiled输出及installed universal逐class核对。版本字符串与历史binary相同，必须以本轮source/installer/universal SHA256区分。EULA复制既有接受文件，game/RCON仅localhost、private worlds、2GiB G1、4个真实Workers、正常20TPS。监测使用外部只读JMX原生100槽tick ring/JVM MXBeans、线程转储和冻结检查点的live full-GC histogram；没有observer/test模组、没有新增生产监测权限或白名单。

复用 [installed soak工具与完整操作协议](../tools/canonical-be-lifecycle/README.md) 和原mixed datapack/RCON/JMX。64台原始Furnace/Blast/Smoker、32Hopper八闭环、8个真实powered disabled Hopper、brew/chest/daylight、redstone clocks/lamps、32NoAI持久羊，8组炉/Hopper注册序。每受检窗口检查120×ticks总正文、Worker/serial分解、逐炉BurnTime/完整NBT/物品守恒、Hopper环守恒、羊Age/UUID与clock/redstone。健康核心必须Native/Furnace Worker计数都>0；Resource ON Lease issuance与fine completions相等，OFF资源动作0。

chunk unload/reload复用真实save/forceload撤销、等待和Vanilla perf `chunks.csv`，证明旧holder确已离开内存后才重载，检查全部原32唯一实体UUID和完整物品状态。diagnostic perf区间不纳入steady窗口。保存重启是完整停止原DedicatedServer、新JVM读取原世界，记录真实退出码、完整状态守恒与有界进度，再运行200 paced ticks；bootstrap不冒充逐BE精确窗口。

真实Mod层只加载原始Create6.0.10 jar及其原jarjar依赖，不加载Forgia Create adapter；两套真实waterwheel/gearbox/millstone加工。Create与任何变换后未证明路径保持原拒绝/完整serial，未认证C5/C6或其他Modpack。模组层的Worker减少/拒绝也不包装成并行证明。

## 安装服、soak和资源检查

| 实际场景（含重启） | 受检活动tick | 名义20TPS活动时长 | 实测总wall时长 | 退出 |
|---|---:|---:|---:|---:|
| `core-pilot-v4` | 400 | 0.333min | 165.067s | 0 |
| `fixed-resource-off` | 400 | 0.333min | 111.863s | 0 |
| `core-soak-60min` | 72200 | 60.167min | 3832.426s | 0 |
| `create-pilot` | 200 | 0.167min | 92.500s | 0 |
| `create-soak-10min` | 12200 | 10.167min | 780.320s | 0 |

正式5场景共9次DedicatedServer JVM启动（含4次保存世界重启），失败pilot另记；合计85400个受检活动tick、10322400次正文，总wall4982.175s；主soak单一JVM完成60×1200=72000个steady tick（名义正常20TPS活动60min），另有unload后窗口与保存重启200tick。单进程窗口之间有冻结/refill/checkpoint诊断，不称无间断服务或独立JVM重复。主soak总wall3832.426s。

| 主soak诊断数据 | 数值 |
|---|---|
| samples | 71338 |
| weightedMeanMspt | 7.494668340099246 |
| perWindowP95RangeMs | [8.5751, 32.1058] |
| maxMspt | 96.179 |
| perWindowObservedTpsRange | [19.973970588052367, 20.014148294385134] |

Create层实际workgraph保持`enabled=true`，且`UNVERIFIED_RUNTIME`拒绝，Worker正文0。其120+6个真实BE/tick全部完整serial；这是未知runtime拒绝与Fence/lifecycle验证，**不是Create或混合模组并行验收**。原始Create6.0.10 SHA256 `ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a`，无Forgia Create adapter。核心正常负载Native/Furnace实际Worker分别记录于机器摘要。


正式进程完成33个冻结清理检查点，14次真实heap字段审计；所有受检临时权限对象0、running Regions0、RegionState数量与当前regionizer一致，实际Worker数均4。Native idle scope至多4个/128B，直接heap字段均关闭且无world/admission/parent引用。主soak稳态Region数量：[24]。正式server ERROR/FATAL行18，WARN行73；见机器摘要逐场景分类与原始行号。18条ERROR/FATAL全部逐UUID匹配Vanilla perf为三个维度构造的9个`Level dump / Exception("dummy") / Minecraft Test Report`，未生成真实crash-report文件，正式安装服未捕获tick异常0；没有抑制日志或修改CrashReport代码。WARN分类：{'resource-union-schema': 18, 'localhost-offline-mode': 36, 'config-default-completion': 5, 'keep-up': 5, 'Create-refmap': 3, 'Create-annotation-ClassNotFoundException': 3, 'Create-existing-report-only-compatibility-notice': 3}。

直方图中零存活目标包括canonical kernel、InvocationTicket、Permit、RegionGuard、OwnedEffect、RegionBlockEntityTickBatch、legacy FurnaceWorkBatch、Coordinator、Plan、FurnaceResourcePreparation/FurnaceWorkScope和resource Lease/Scope。running Regions为0，live RegionState与regionizer实际数核对。`RegionBlockEntityTickScope`原设计按Worker保留空闲缓存；每进程不超过4个，初/末/重启heap dump按实际字段布局验证active=false、admission/region/previous=null，不把对象缓存误报成活权限或改掉既有缓存制造零对象。

full-GC/heapdump仅在冻结检查点，所有诊断时长单独记录；不将GC collection time当全部STW，不从TPS或Lease累计量推断没有泄漏，也不宣称性能净收益。heap dump可能含RCON口令，所有dump/properties/world/jars/日志保留在ignored本地tmp，绝不上传；报告只给非秘密结果/hash。有限检查点零活权限和堆观测不证明无限期/全部JVM/native memory无泄漏。

安装服以聚合正文计数与独立逐炉状态检查exact ticks；Hopper逐invocation故障事件由真实kernel JUnit验证，安装服没有逐ticker探针，因此不把其聚合计数/物品守恒冒充每个未知Mod callback的exactly-once证明。

## 失败记录与覆盖边界

| 保留的失败run | 实际结果／类型 |
|---|---|
| `final-fixed-on` | exit1；未执行JUnit，启动/编译/离线依赖失败见原log |
| `final-v2-full-core-fixed-off` | exit1；{'tests': 1326, 'failures': 1, 'errors': 0, 'skipped': 0} |
| `gametest-current-v2` | exit1；未执行JUnit，启动/编译/离线依赖失败见原log |
| `gametest-current-v3` | exit1；未执行JUnit，启动/编译/离线依赖失败见原log |
| `gametest-current-v4` | exit1；独立GameTest结果见上表（不是JUnit） |
| `gametest-current-v5` | exit1；独立GameTest结果见上表（不是JUnit） |
| `gametest-current-v6` | exit1；独立GameTest结果见上表（不是JUnit） |
| `lifecycle-monitor-red-v2` | exit1；{'tests': 2, 'failures': 2, 'errors': 0, 'skipped': 0} |
| `lifecycle-monitor-red` | exit1；未执行JUnit，启动/编译/离线依赖失败见原log |
| `preparation-lifetime-red-v2` | exit1；{'tests': 2, 'failures': 2, 'errors': 0, 'skipped': 0} |
| `preparation-lifetime-red` | exit1；未执行JUnit，启动/编译/离线依赖失败见原log |
| `recovery-probe` | exit1；未执行JUnit，启动/编译/离线依赖失败见原log |
| `core-pilot` | 观察器假设错误，按实际字段修正后用新世界重跑；完整错误见机器摘要 |
| `core-pilot-v2` | 观察器假设错误，按实际字段修正后用新世界重跑；完整错误见机器摘要 |
| `core-pilot-v3` | 观察器假设错误，按实际字段修正后用新世界重跑；完整错误见机器摘要 |


原始命令、实际退出码、source hash、JUnit XML、GameTest XML/summary/crash、installed binary class/hash、RCON journals、完整状态与JMX/线程/live heap证据分别在本地`tmp/be-canonical-c41/`保存。首次pilot观察器误用了record accessor名代替command raw字段名、误要求已有idle Native scope缓存对象数为0、未兼容Vanilla block-data回复格式；均保留失败run并用新世界重跑。生产scope缓存没有删除，修正观察分类后以真实heap字段验证，而不是改变权限条件。

仍未覆盖：无限期/多天负载、玩家/联机客户端、真实跨dimension动态Level unload/recreate、动态layout下的安装服merge/retirement压力（fixed本就不merge，动态merge仅JUnit）、active phase中的OS强杀/硬件断电恢复、heap/native-memory全部泄漏种类、非协议异步writer/反射/ASM、其他Modpack/物流拓扑/contraption/Create adapter。真实服务正常RCON停服/世界重启与JUnit并发active-phase unload/shutdown是不同证据，不能互相冒充。

本阶段完成后停在C4.1；GameTest gate仍非全绿，保留具体阻塞供后续处理。C5／C6／额外MSPT优化均未进入，当前结果不扩大已发布cohort或作者声明的含义。
