# Canonical BE C4：Furnace 接入同一 phase Coordinator

基线：C3-C `0c064576f6a9899593be431c5493567c5bf7eae2`。本片把正常 Level 路径的受保护 Furnace 接入已经通过验证的 canonical Coordinator：**Native 与 Furnace 共享同一个 phase batch、execution kernel、Ticket集合和cursor，没有独立active batch。** C3-C通过并独立提交之后才进入本片。

## 生产接线及原语义

正常Level的canonical入口不再因Furnace Waves开关分派到第二个driver。开关默认值保持，OFF只执行已证明Native子集、Furnace完整serial；ON额外允许原受保护Furnace／Blast Furnace／Smoker通过fine prefix。Native与Furnace按不同prefix执行，不在同一wave混用REGION_OWNER和RESOURCE_SCOPED。Unknown／未证明Generic／binding或fresh拒绝都在当前cursor形成完整Serial Fence，physical join及必要commit完成后才进入该位置，之后重新准备后缀。

[FurnaceWorkPreparation](../src/main/java/net/neoforged/neoforge/server/threading/region/FurnaceWorkPreparation.java)、[FurnaceResourcePreparation](../src/main/java/net/neoforged/neoforge/server/threading/region/FurnaceResourcePreparation.java)、[FurnaceWorkScope](../src/main/java/net/neoforged/neoforge/server/threading/region/FurnaceWorkScope.java)、原planner／ownership／dispatcher实现均未改。保留exact source/function/runtime、recipe／ingredient／item／component、当前chunk/binding、通知闭包及完整原ticker。每次候选及整wave在原lease下fresh；全phase inventory alias扫描仍包含尚未开始的后项，不以Fence拆分缩小alias域。

同一phase只创建一个FurnaceResourcePreparation，保留home table、scratch及累计logical-use预算；每个prefix不会重置2048units／512resources／131072logical uses限制，也不缓存成功证明。只允许dependency plan整个首wave正好覆盖当前连续prefix；遇依赖就切在其前，绝不使用合法但有空洞的`[A,C]`绕过B。保留原whole-wave fresh comparison、全部validator、all-or-none reservation、Scope退出／physical join／seal、原序setChanged通知、失败后其他必要commit及最终lease释放。单炉prefix仍按原策略完整serial。

[RegionThreadingEngine](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java) 仅将原Furnace执行／提交helper的batch形参泛化为已经存在的共同BlockEntityExecutionBatch，在同一kernel中取得原Ticket；没有构造第二个FurnaceWorkBatch，没有复制Ticket/Permit/Region lease或给Adapter提交权限。active batch覆盖Native、Furnace、serial Fence及最终phase cleanup；原unload/shutdown、取消/interrupt、executor rejection、started失败不重放测试继续实际通过。

invocation continuations仍走C3-B有归属记录并在Fence前commit；真正level-end tasks仍在实际Post event后执行。原operation Request/queue/close、Region RNG及通知commit代码保持。Native Region RNG在Native/Furnace/Fence切换后仍保留同一phase逻辑Region流。Generic v1在Native配置仍仅收窄核心已证明的原函数，Furnace配置保留旧prepare=0合同，ADAPTER_GUARDED输入完整serial；没有扩大Generic Adapter协议或Create类型分支。

## Mailbox与未开始证明

正常canonical phase为每个logical Region保留一次mailbox delivery标记。仍使用原queue、message身份、容量／取消／retired规则，在原Region lease下由server完整执行未知message callback，之后再做whole-prefix fresh检查，不让未知callback在Worker持某个ticker的Permit运行。callback改变recipe／库存／component／binding会使原候选拒绝；不会为了复用旧group而再次delivery同一phase、重置RNG或另开active batch。原explicit/indexed兼容桥保留原driver与原时机测试，是历史内部入口，不是新的Level准入证明。RegionMailbox的说明同步澄清delivery线程；签名/API版本未改。

## 新增五项验证

[CanonicalBlockEntityFurnaceIntegrationTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityFurnaceIntegrationTest.java) 实际执行原Level loop、原Hopper/Furnace tickers、原Fixed/Persistent池、kernel和资源桥：

1. 同一phase的两个不同Region Native body overlap=2、两个不同Region Furnace body overlap=2；前后多个prefix和Unknown，共一个observed active batch、8个原Ticket、7个Worker正文（3Native＋4Furnace），Resource ON实际签发4个fine Lease；完整fixture状态与独立Vanilla serial oracle相等，通知按ordinal并先于Unknown。
2. Native/Unknown Fence改变后缀Furnace组件，旧证明不能复用；仅先前两炉fine，后缀完整serial，全部状态与oracle一致。
3. 跨Native/Fence的后项共享输入ItemStack仍被whole-phase alias扫描拒绝，炉子完整serial，Native安全prefix继续；共享库存守恒及完整状态与oracle一致。
4. 真正mailbox callback在无ticker Permit的server上下文改变组件，整Furnace wave在body前拒绝，原ticker只serial一次，后续两Native仍Worker执行。
5. Furnace通知commit之后，Unknown将最初活跃、未证明的后项Hopper改成冷却分支，后缀fresh证明后实际进入Worker，Ticket与batch不替换。

原C1/NativeEffect/Inventory/Coordinator等测试源与预期本片全部未改，没有禁用或过滤。Sparse world仅提供有界世界seam；原ticker、Capability分发、Worker、Permit、Lease、commit和Post event均真实。测试观察钩子在生产准入后运行，不提供权限，不把任意Mod callback认证为安全。

| 实际配置／范围 | 总数 | 通过 | failure／error／skip |
|---|---:|---:|---:|
| `final-fixed-on` | 248 | 248 | 0／0／0 |
| `final-persistent-off` | 248 | 248 | 0／0／0 |
| `final-persistent-on` | 248 | 248 | 0／0／0 |
| `final-full-core-fixed-off` | 1314 | 1314 | 0／0／0 |

每组70个canonical相关invocation全部通过，原C1八项目标及Native visibility/order／phase-end时机全部通过；完整core原1244项本轮再实际执行并以XML Counter核对。全部命令真实exit0、0failure/error/skip，Fixed/Persistent × Resource OFF/ON真实覆盖，Furnace Waves ON/OFF均由现有参数案例及新混合案例执行。真实两类overlap和单一batch证据在各配置原XML的system-out中。

实际命令、冻结源码hash、退出码、日志和JUnit XML保存在本地`tmp/be-canonical-c4/`。Formatting/license/diff通过。本片未改Vanilla patch，C3-C基线的权威patch及配置hash保持，不生成无变化patch。探索轮先69/69通过，再补真实mailbox案例；初次该案例直接调用private enqueue方法编译失败，改为反射现有入口并保留private visibility，随后70/70通过，最终冻结后重跑矩阵。

## 实际支持边界

- Native并行：原始、exact instance/function/binding/runtime、无附件、原PrimaryLevelData时钟的Hopper，进入时cooldown>1或disabled；完整原ticker，未知Capability／共享库存不会被探测或调用。活跃搬运和未证明回调路径body前完整serial。
- Furnace并行：原protected driver已认证的燃烧炉家族及recipe/item/component/通知/资源证明，在开关ON时通过同一cursor的fine prefix；任何证明不完整、全phase alias、预算／validator／reservation拒绝均完整serial，不扩大旧cohort。
- Generic／Foreign／Unknown：无核心正文闭包证明的输入完整serial；没有自动认证所有Mod、未知异步writer、反射／ASM或任意Capability root，Lease不是sandbox。

统一Coordinator的正常Level生产入口已工作；历史内部explicit/indexed兼容桥仍用于旧合同回归，不能把它的作者声明放行扩大成新cohort认证。C5 Generic Adapter Protocol与C6 Create未进入，默认开关、预算、注册API及MSPT冻结保持。没有新安装服、GameTest、Modpack、Create/Mixin、磁盘unload/reload、soak或性能结论。
