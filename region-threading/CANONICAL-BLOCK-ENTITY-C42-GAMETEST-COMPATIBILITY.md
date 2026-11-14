# C4.2 GameTest Compatibility & Regression

C4.2从`2a22661b5f57d39d0ecde03f11dd62ad1d4c7cf9`调查C4.1四个required失败及all-slice崩溃。最终冻结源码的两个完整unfiltered GameTest均176/176 required通过，真实Gradle exit0，无Crash Report。完整core1328与三个相关配置各329全部通过；原C4.1全部1328/原1244实际重跑并保持原结果。本轮没有发现需要生产代码修复的缺陷，交付限定于测试契约、夹具、显式测试profile和报告。

## 调查结论与新验证合同

| 历史问题 | 证据与原因 | 当前应验证的行为 |
|---|---|---|
| `blockEntityOwnerOperations` | 旧自定义声明型Source期待Worker；声明从未证明任意callback closure | Source完整server Serial Fence，跨Region transfer恰好一次，moved4，source12/target64；真实受证明Hopper前后缀继续Worker；按tick验证顺序和无重放 |
| `furnaceTicksOnRegionWorker` | 旧单炉coarse Worker期待不符合受保护fine wave的至少2成员条件 | 新增独立单炉serial对照；两台不同chunk/Region的exact already-burning普通炉实际Worker，每台完整220tick，烧炼一次，BurnTime=400-bodyCount、CookTime=0、两个必要通知提交 |
| `unsafeTickerForcesWholeRegionFallback` | 旧whole-Region poisoning不再是canonical合同；原Beacon离炉1格还会使notification proof拒绝 | 同一Region两不同chunk炉→unknown Beacon→闭合Hopper正文；Beacon完整serial，前后Worker不被全Region毒化；不要求并发炉正文互相全序，只要求Fence边界 |
| `savesAndReloadsRegionThreadingState` | C4.1配置缺POI或Entity/Chunk slice，原状态断言无需放宽 | 显式opt-in profile同时开启POI、Entity、Chunk和BE/Furnace；真实flush/unload/reload，增加chunk/BE/entity新对象证明，保留全部block/NBT/UUID/position/POI/ticket/Region比较和save后零deferral |
| `CustomSuperSustainingBlock.canSustainPlant` | fixture把plant所在格canOcclude等同方向错误；Vanilla传UP且原Cactus canOcclude，现存plant的survival/growth合法 | bridge OFF确定性真实回调同样red；显式expectedFacing，保留soil identity和方向拒绝；实际原Cactus survival/randomTick/neighbor/growth重验，UP/DOWN/SOUTH原案例均重跑 |
| `RegionPoiRegionizationTest.theSaveFormatIsTheVanillaFormatAndAReloadRestoresTheSameRecords` | 相关Persistent/OFF真实JUnit失败：Vanilla参考磁盘tag为空。旧夹具误把IOWorker.close当flush，并忽略regionized synchronize future | 在原tag/类型/顺序/free-ticket完整相等断言前等待两侧实际synchronize(true).join；关闭原writer后才独立读盘。沿用已有RegionPoiSaveReloadTest的Vanilla参考持久化边界，不改生产POI写入 |
| optional `testFeatureGating` | Vanilla GameTestServer对Level用FeatureFlags.REGISTRY.allFlags，disabled-flag期待前提不成立，C4.1在XML被标optional失败 | 不跳过测试；验证真实GameTest Level四item全enabled，再对真实selected-pack metadata请求的flags保留原base/extended enabled+disabled四断言；普通Server仍对其真实Level四断言 |

新观察均使用已存在的`setBlockEntityBodyObserverForTesting`和`setFurnaceBodyObserverForTesting`，只在生产准入后记录，Native/Serial始终转发完整body。没有替换ticker、Ticket、Permit、Lease、Coordinator、RecipeManager或生产planner，没有由观察签发权限。Furnace fine body通过现有原Scope observer，原metric/completion/notification路径不改。

只修正fixture或旧合同验证，没有生产修正；`RegionPoiStorage.flushAll()`早已真实join，GameTest实际Level flushing save的所有重载状态/新对象断言保持通过。

## 运行配置与命令

标准环境为JDK21.0.13+9、Linux/WSL2、Minecraft1.21.1、NeoForge/Forgia21.1.250.60-beta、4实际Workers，所有重型运行严格串行，offline Gradle/max-workers1/no-parallel。新增`tests:runCanonicalGameTestServer`是显式opt-in GameTest配置；默认GameTest和实际server默认开关保持。完整验证不传gameTestFilter。定向运行会显式报告过滤范围，不替代完整运行。私有init只隔离世界目录；不要用同一名字覆盖旧log/XML/crash。

## GameTest与JUnit结果

实际命令/UTC起止/墙钟/Gradle exit/XML路径和SHA256见[机器摘要](CANONICAL-BLOCK-ENTITY-C42-GAMETEST-COMPATIBILITY.json)；原始log/XML/crash留在本地`tmp/be-canonical-c42/`与原C4.1目录。

## 失败保留与覆盖边界

C4.1原XML/log/crash均保留原SHA；C4.2所有失败尝试保留。最早C4.2private Gradle init的`providers`作用域错误保留（没有执行JUnit/GameTest）。基线四项目标复测启用所需slice后save/reload通过，另外3red包括IO加载timeout；C4.1原真实旧Worker断言失败仍是契约调查证据。最早新版Native夹具未实际供电，Hopper.onPlace恢复ENABLED，正确被生产拒绝；更正为真实REDSTONE_BLOCK供电后用新世界重新运行，不放宽admission或旧正确性边界。

本阶段没有重复C4.1长soak；仅测试/配置/报告变化时无新binary行为或安装服收益结论。仍不支持未知Generic/活跃Hopper Capability物流/共享handler自动Worker，fine Furnace仍只受保护原cohort。GameTest受控world/Mod集合不等同任意Modpack、Create、玩家多天运行或强杀恢复。默认off检查/benchmark或hard-kill未激活的既有条目应按真实触发状态另列。

## 最终完整GameTest与日志计数

| Run | Pool/Resource | registered required | Framework XML | Gradle exit | Crash | wall seconds |
|---|---|---:|---|---:|---:|---:|
| `full-gametest-persistent-on-v2` | Persistent/ON |176/176|230 entries /0failure /4Not Processed|0|0|104.631|
| `full-gametest-fixed-off-v1` | Fixed/OFF |176/176|230 entries /0failure /4Not Processed|0|0|129.630|

230 XML case entries (178 unique IDs, some tests appear in multiple groups);226 passed entries and4 Not Processed entries. The unchanged dumper labels Not Processed as Failed but optional with an empty message. Those4 are not176 registered required GameTests and are not counted as executed passes.

四个Not Processed IDs：`testFeatureFlagPacks`、`extendPoiTypeTest`、`customBoatType`、`testAttachmentSyncManual`。原文本summary明确为Not Processed；其中前两项曾在startup日志记录Passed，后被framework重置，不能把startup结果冒充最终summary通过。没有改dumper、禁用测试或改写XML。GameTest注册函数176项完整运行并通过；不能据此称230条全执行或客户端/manual功能已验收。

两个完整配置均观察到：每个双炉220次fine Worker正文，真实maxSimultaneousBodies=2；单炉220完整serial；同Region Furnace→Beacon→Hopper轨迹为fine220+fine220/serial220/native220；Source转移恰好一次，两个真实Native前后缀各26次；每tick无重放和Fence返回先于后缀断言通过。25chunks/13BEs/9entities全部为重建对象，114block states/13full BE NBT/9UUID&positions/10POI records/2taken tickets及Region maps相等，save时batch/deferral计数0。村民自身EntityTickEvent.Post在Worker上看到其真实HOME和JOB_SITE记忆，非全局counter推断。

| JUnit配置/范围 | 通过/总数 | failure/error/skip | exit |
|---|---:|---|---:|
| `final-v2-fixed-on` |329/329|0/0/0|0|
| `final-v2-persistent-off` |329/329|0/0/0|0|
| `final-v2-persistent-on` |329/329|0/0/0|0|
| `final-v2-full-core-fixed-off` |1328/1328|0/0/0|0|

相关回归覆盖Canonical/真实Worker/Furnace/Resource/Execution/Adapter/RegionTask、Region POI equivalence/regionization/disk save-reload、EntityTickPolicy和CustomFeatureFlags；full1328与C4.1 XML逐case multiset相等，其中原1244非canonical与C4基线同样相等。84canonical实际运行，其原JUnit源码和断言均不变。仅一项非canonical POI格式夹具补充实际IO future join/close，原NBT/顺序/票据数相等断言全部保留。两次完整GameTest使用的5个main/build输入完全一致；后续唯一JUnit夹具变化不在GameTest加载的mods/source sets中，未重复完整GameTest。

## 完整命令

`full-gametest-persistent-on-v2`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I tmp/be-canonical-c42/game-private.gradle -Pc42Run=full-gametest-persistent-on-v2 -Pc42Profile=canonicalGameTestServer :tests:runCanonicalGameTestServer
```

`full-gametest-fixed-off-v1`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I tmp/be-canonical-c42/game-private.gradle -Pc42Run=full-gametest-fixed-off-v1 -Pc42Profile=canonicalGameTestServer -PcanonicalResources=false -PcanonicalPersistent=false :tests:runCanonicalGameTestServer
```

`final-v2-fixed-on`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -PfurnaceResourceOwnership=true :tests:junitTest --tests '*CanonicalBlockEntity*Test' --tests '*BlockEntityTickAdapterTest' --tests '*FurnaceWorkWavesTest' --tests '*BlockEntityResource*Test' --tests '*BlockEntityExecutionEngineTest' --tests '*RegionBlockEntityTick*Test' --tests '*RegionTaskBatchTest' --tests '*RegionPoi*Test' --tests '*RegionEntityTickPolicyTest' --tests '*CustomFeatureFlagsTest'
```

`final-v2-persistent-off`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -PfurnacePersistentWorkers=true :tests:junitTest --tests '*CanonicalBlockEntity*Test' --tests '*BlockEntityTickAdapterTest' --tests '*FurnaceWorkWavesTest' --tests '*BlockEntityResource*Test' --tests '*BlockEntityExecutionEngineTest' --tests '*RegionBlockEntityTick*Test' --tests '*RegionTaskBatchTest' --tests '*RegionPoi*Test' --tests '*RegionEntityTickPolicyTest' --tests '*CustomFeatureFlagsTest'
```

`final-v2-persistent-on`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -PfurnacePersistentWorkers=true -PfurnaceResourceOwnership=true :tests:junitTest --tests '*CanonicalBlockEntity*Test' --tests '*BlockEntityTickAdapterTest' --tests '*FurnaceWorkWavesTest' --tests '*BlockEntityResource*Test' --tests '*BlockEntityExecutionEngineTest' --tests '*RegionBlockEntityTick*Test' --tests '*RegionTaskBatchTest' --tests '*RegionPoi*Test' --tests '*RegionEntityTickPolicyTest' --tests '*CustomFeatureFlagsTest'
```

`final-v2-full-core-fixed-off`:

```sh
export JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I /home/yc/Minecraft/Forgia/tmp/be-canonical-c42/format-selected.gradle :licenseCheck :tests:licenseCheck :checkFormatting :tests:checkFormatting :tests:junitTest
```

两个GameTest主运行均不传`gameTestFilter`。私有init只为实际run隔离新世界目录；可复用的仓库命令为同样Gradle flags下`:tests:runCanonicalGameTestServer`，Fixed/OFF另传`-PcanonicalPersistent=false -PcanonicalResources=false`。该opt-in profile同时启用bridge/workers/BE/Furnace/Entity/Chunk/POI/Scheduled，fixed3chunks、4Workers；spawning/global-worker/border/game-event/packed-lease等额外开关维持默认，本报告不称全部实验slice均启用。

## 保留的失败与诊断输出

- `baseline-four-v2`: exit1; required/XML failures ['3'], crash files 0; 原log/XML保留。
- `baseline-four`: exit1; required/XML failures [], crash files 0; 原log/XML保留。
- `canonical-focused-v1`: exit1; required/XML failures ['2'], crash files 0; 原log/XML保留。
- `canonical-focused-v2`: exit0; required/XML failures ['0'], crash files 0; 原log/XML保留。
- `final-v1-persistent-off`: exit1，真实JUnit {'tests': 329, 'failures': 1, 'errors': 0, 'skipped': 0}；失败case [{'class': 'net.neoforged.neoforge.unittest.RegionPoiRegionizationTest', 'name': 'theSaveFormatIsTheVanillaFormatAndAReloadRestoresTheSameRecords()', 'failures': ['org.opentest4j.AssertionFailedError: the saved column of [0, 0] is the vanilla column ==> expected: <Optional.empty> but was: <Optional[{DataVersion:3955,Sections:{4:{Records:[{free_tickets:0,pos:[I;3,64,3],type:"minecraft:home"}],Valid:1b}}}]>'], 'errors': [], 'skipped': False}]，原XML/log保留。
- `full-gametest-persistent-on-v1`: exit1; required/XML failures ['1'], crash files 0; 原log/XML保留。
- `plant-after-bridge-off`: exit0; required/XML failures ['0'], crash files 0; 原log/XML保留。
- `plant-before-bridge-off`: exit1; required/XML failures ['1'], crash files 0; 原log/XML保留。

首次全套run另外暴露`villagerTakesPoi`的历史poi:pinning断言；源码`RegionEntityTickPolicy`早已明确移除此类declaration，本阶段保留完整bed/job-site获取断言并新增精确实体Post事件Worker/brain证明，而不添加新的Entity政策或改生产POI存储。optional `testFeatureGating`旧失败在最终完整运行中是真正Passed，不是继续optional-skipped。

`full-gametest-persistent-on-v2`日志ERROR/FATAL 63行，WARN 63行；分类 {'test-mod-entity-registration-missing-attributes': 4, 'fresh-GameTest-directory-properties-file-absent': 1, 'legacy-tag-fixture-local-orElse-default-mismatch': 1, 'intentional-unregistered-permission-node': 57}。

`full-gametest-fixed-off-v1`日志ERROR/FATAL 63行，WARN 63行；分类 {'test-mod-entity-registration-missing-attributes': 4, 'fresh-GameTest-directory-properties-file-absent': 1, 'legacy-tag-fixture-local-orElse-default-mismatch': 1, 'intentional-unregistered-permission-node': 57}。

这些日志没有抑制：PermissionTest源码明确刻意查询未注册node并catch/log；新GameTest目录缺server.properties会由Settings记录后生成；测试Mod实体缺attributes为注册诊断；遗留DuplicateOptionalTagTest用两个不同本地orElse集合却未注册defaults，仍输出既有失配诊断，未纳入required GameTest/JUnit gate。没有将其当作生产ticker异常或称所有旧手工Mod诊断全绿。原C4.1真实运行中的fixture callback crash的完整报告与SHA保持，修复后两完整run均无Crash Report、无Incorrect direction异常。

## 停止边界

C4.2在当前required GameTest gate与相关JUnit全部通过后停止。没有生产修正commit，因为src/main/patches/API/admission/default/budget/Adapter完全不变；不构造新proof或C5协议。C4.1长soak不重复、不折算为新run；本阶段没有新的安装服、Create Adapter、任意Modpack/未知异步writer、强杀、多天玩家负载或性能结论。当前支持范围仍仅原受证明Native Hopper闭合分支及原protected Furnace cohort，未知callback完整serial。
