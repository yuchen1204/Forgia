# Canonical BE C3-A2：最小真实 Native 准入

基线：`0b7cfe1b6d20ebfbfd953bdb7c764819fc5a7204`。本片接入生产代码，范围是原始 Hopper 的两个封闭分支：进入原函数前 `cooldownTime > 1`，或当前实际 `HopperBlock.ENABLED=false`。完整原 ticker 仍在 Native Region Worker 上执行，更新原 source 的 cooldown／tickedGameTime；不会进入 inventory／loot／Capability／entity／neighbor／RNG／operation 路径。活跃搬运 Hopper 完整串行，不调用 provider 做预检；这不是永久关闭 Hopper。

## 生产准入

[NativeBlockEntityPreparation](../src/main/java/net/neoforged/neoforge/server/threading/region/NativeBlockEntityPreparation.java) 持有核心签发的单一原始 `HopperBlockEntity::pushItemsTick` 函数引用。HopperBlock 的 ticker factory 返回这个引用；LevelChunk 只能从实际 bound wrapper／delegate 提取该引用对应的 source，类型字符串或等价 lambda 都不能继承。

证明核对精确 Hopper source、无附件、原 level／position、实际已加载 chunk 及当前 membership、wrapper binding、实际 state 与 source state 同一引用、原 Hopper block/type validity、当前封闭分支。只接受原 ServerLevel／LevelChunk／WorldBorder runtime，TimeTracker 打开时拒绝。相关 body／wrapper／继承调用路径出现 MixinMerged 即拒绝；BlockEntityType 只检查实际调用的 `isValid`／`getKey`，其注册期 accessor 不属于 body 闭包。原 gameTime 读取及 Region RNG 行为不改。未参与协议的任意异步／反射／ASM writer 不由 Lease 变成安全对象，未新增运行时 sandbox 承诺。

真实 Level canonical collection 缩窄 Native candidacy，Worker 在 body 前再次检查当前证明。非原函数、实例／binding／chunk／state／分支变化均完整 server fallback；已开始 body 失败不重放。既有受保护 Furnace Waves 与 v1 显式作者协议暂留原 driver，C3-C／C4 再接入统一路径；本片不声称它们已经使用一个 cursor。历史 indexed/explicit driver 的原回归入口保留，不作为新 Level 准入证书。

没有新增 Ticket、Permit、Region lease 或 active batch，没有改变默认开关、资源预算、注册 API 或 Create。C3-A2 只完成最小安全子集；旧 Native 的 prefix／效果时机缺口留给下一阶段。

## 测试输入与观察

[NativeAdmissionTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityNativeAdmissionTest.java) 新增6个 invocation：冷却／禁用两分支分别验证两个不同 Region 的原始 Hopper Worker overlap=2、原 cooldown／tickedGameTime 结果、provider零调用；共享未知 handler 只由完整 server 调用；冷却1必须串行；dispatch前分支变化拒绝；替换 function 不继承原 source 证书。分支变化案例显式使用可控 Fixed executor，其余案例随 Fixed/Persistent 配置使用实际池。旧 inventory 的未知 provider 目标现在转绿，保留原函数完整调用次数和状态断言。

C1 的 Sequence／Fence／NativeEffects 及上一片 Inventory 测试源码、断言均未改。此前 C1 Native fixture 是返回 `minecraft:hopper` 字符串的脚本 ticker；[TestSupport](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityTestSupport.java) 现使用真实原函数、真实冷却 Hopper。事件／own-cell／fault／Latch 通过 body 观察钩子注入，钩子只在生产准入后运行、不参与证明或权限签发；真实 Vanilla serial oracle 用透明 observer 转发同一原 ticker。它们是调度／故障观测，不能据此声称任意 Mod callback 安全。

旧 `RegionBlockEntityTickTest.levelTicksBlockEntitiesOnWorkersAndPublishesTickersAddedDuringTheBatch` 同样改为 spy 真实原始 Hopper wrapper，所有原线程／次数／membership／pending／finally 断言保留；spy 总是转发原 ticker。该 fixture 迁移没有改变既有1244项的 testcase 标识／数量，避免用类型名 mock 获得新生产权限。

## 实际验证

| 配置／范围 | 实际总数 | 通过 | 后续目标失败 | error／skip |
|---|---:|---:|---:|---:|
| `final-v4-fixed-on` | 231 | 227 | 4 | 0／0 |
| `final-v4-persistent-off` | 231 | 227 | 4 | 0／0 |
| `final-v4-persistent-on` | 231 | 227 | 4 | 0／0 |
| `final-v4-full-core-fixed-off` | 1297 | 1293 | 4 | 0／0 |

新Native6项与原Inventory6项在每组全部通过。canonical相关共53项，49通过、4项后续目标失败；C1原八项仍有三项顺序目标未修复，另有上一片Native task Fence visibility。四条JUnit命令均真实exit1，未过滤／禁用或吞断言；不是完整C3验收通过。原1244项本轮实际完整重跑，Counter testcase multiset核对，Ingredient显示名仅规范化对象identity后缀。完整命令数组、实际exit、源码hash和原XML保存在本地 `tmp/be-canonical-c3a2/`。

Formatting／license通过。`:neoforge:genPatches` 与 `:neoforge:applyPatches` 分开执行，各exit0；正式矩阵实际编译修改后的Minecraft源。原C1失败断言保持，未增加tag排除或改变配置默认值。原两未跟踪日志按bytes/hash保留，临时证据／生成Vanilla源不提交。

探索记录保留：较完整回归还发现对无原函数的mock过早读取缺失WorldBorder造成NPE，已改为先验证原函数／source并对缺失runtime完整拒绝，最终重跑消除该回归；首次矩阵暴露上述旧type-only mock fixture，改真实函数输入后独立7/7通过；扩大runtime审计时整类BlockEntityType检查拒绝了NeoForge注册期`neoforge$setValidBlocks` accessor，按真实调用闭包检查读取方法后独立7/7通过，未豁免被执行方法的变换；格式化runner最初错误套用“不准源码变化”保护，在Gradle格式化完成后报wrapper AssertionError，保留日志，修正仅applyFormatting允许变化并重新执行实际exit0检查。最终以冻结源码的全矩阵为准。

本阶段完成后进入C3-B最小归属／提交桥，再进入C3-C cursor。C4须等C3-C通过；C5／Create／MSPT不在本轮范围。未新增安装服、GameTest、Modpack、Create或性能验证结论。
