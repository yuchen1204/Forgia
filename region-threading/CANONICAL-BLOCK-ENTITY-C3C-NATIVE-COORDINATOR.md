# Canonical BE C3-C：单 phase Native Coordinator

基线：C3-B `8ce1364fc75b39046d48612402d7875821e48ce0`。实际 Level Native 入口现在使用一个phase-level Coordinator、一个active batch、同一kernel/Ticket集合和单调cursor。只从当前ordinal提交没有空洞的安全prefix，Unknown／refusal／binding失效的位置完整串行，之后重新证明后缀。Furnace Waves ON仍用原受保护driver，C4下一阶段合并，不能把本片称为已完成Furnace统一。

## 生产执行与边界

[CanonicalBlockEntityCoordinator](../src/main/java/net/neoforged/neoforge/server/threading/region/CanonicalBlockEntityCoordinator.java) 不授权限：它只推进canonical ordinal/cursor。实际driver每次从cursor重新读取资格与当前原Hopper封闭分支证明，不复用phase起点的成功。不能认证的条目由同一Ticket的serial Permit完整调用。initial ineligible可在Fence后变为eligible；被移除／rebind／同位置replacement不继承原Worker权限。相同source的多次membership必须拆prefix，前次cooldown写后重新检查。当前额外要求原始PrimaryLevelData时钟；自定义或派生数据时钟完整串行，避免未知getGameTime在Worker进入。现有source用chunk membership map核对，预检不触发BE创建回调。

Native prefix按实际Region分组，在同一Region保持canonical相对顺序，不同Region可实际重叠；每prefix最多含真实pool线程数个Region，防止all-before-body检查占满pool却等待未调度checker。全部Region先取得原lease，全部fresh proof／既有预算reservation／v1 owner-Worker check完成后才允许任何正文。第一个拒绝offset截断全prefix，不让另一个Region的后项提前开始。拒绝处及后项body尚未运行；前面的连续部分physical join／commit后，该位置完整serial，再重新准备后缀。v1 prepare仍一次，check仍在实际owner Worker，只拒绝核心已证明body，不扩大未知协议。

若executor拒绝，在任何body前拒绝整个prefix；所有已提交checker仍物理返回，server逐项执行完整原ticker。已开始body异常不重放，同Region未开始tail退休，其他已开始调用必要effects照原失败政策完成；managed-wait取消或interrupt仍物理join后才清权限／lease。Region RNG由phase按logical Region coordinate保留同实例，空RegionState退休／重新lease也不重置seed。

内核新增内部 `reinspectUnstarted`，只替换CAPTURED/INSPECTED的Inspection、递增generation并使旧Refresh失效；RUNNING／终态／带effect记录拒绝刷新，Ticket身份不变。Native body及commit沿用C3-B同Ticket归属和原Region lease，任务barrier只计物理完成，不是第二套执行authority。

## 完整Serial Fence与参与式quiescence

[RegionWorkAdmission](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionWorkAdmission.java) 在existing Region/GlobalRegion的running/submitted转换及batch publication上关闭新工作准入，先等待原参与式工作结束，再由这个phase的server持有boundary。它不是Region lease或世界访问权限，body仍必须有原Guard/Permit和lease。closure检查与mark/publication用同一monitor；空闲观测不再留新Region工作插入的窗口。

等待boundary不持lifecycle read锁；既有batch的同步server handoff仍由server服务，避免join自等待。已进入phase后，另一个线程只能将Region任务排队，不能启动它；其他slice/batch的publication等待phase退出。Unknown完整server ticker在此boundary内直接执行原runBlockTickTask，不借Worker授权、不把半个ticker排队。嵌套BE phase返回原inline loop；同owner不发布第二个parallel batch。unload/shutdown等待原active batch，包括跨Level的server-wide boundary；必要commit与清理前不放行。

Global mailbox／POI的真正phase publication留在整个phase尾，prefix之间不关闭／重开旧batch或drainlevel任务。最小cohort不产生border/gameevent/operation/scheduler业务效果，相关未证明body完整串行；未改原operation queue、原通知顺序、默认开关、资源预算、API或Create。未参与协议的异步／反射／ASM writer仍不由引擎认证。

## 测试夹具迁移与实际验证

新增 [CoordinatorTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityCoordinatorTest.java) 七项：不同Region late-refusal在任一body前截断；Fence激活／作废真实分支；跨Fence同Region RNG连续；外来Region任务无法插入Unknown且稍后只执行一次；同source重复wrapper第二次必须重新证明；phase owner的Unknown callback不能提交无关Global Worker；未知WritableLevelData时钟不在preflight执行，只由完整server原ticker调用一次。均走实际Level、原ticker、原pool／kernel，确认一个observed active batch及原Ticket终态。

原C1 Sequence预期未改。混合explicit输入现在选择实际canonical execution entry，retained旧桥的5个调用显式命名为`retainedExplicitPhase`保留旧timing保护。原Fence的取消／interrupt helper只改为观察当前实际prefix物理barrier，而非旧一次性batch setup latch；原所有assertion保持。新的barrier指向真实执行器完成计数，观察包装仍转发原await/countDown，不替代physical join。

| 实际配置／范围 | 总数 | 通过 | failure／error／skip |
|---|---:|---:|---:|
| `final-v4-fixed-on` | 243 | 243 | 0／0／0 |
| `final-v4-persistent-off` | 243 | 243 | 0／0／0 |
| `final-v4-persistent-on` | 243 | 243 | 0／0／0 |
| `final-v4-full-core-fixed-off` | 1309 | 1309 | 0／0／0 |

每组65个canonical相关invocation全部通过，**原C1八项目标全部真正转绿**，Nativevisibility／ordering／真实Post-before-level-end／exactlyonce及真实不同Regionoverlap均通过。原1244项在本轮完整重跑，XML Counter逐项通过，Ingredient显示名仅规范化对象identity后缀。实际命令、exit、源码freeze、日志和原JUnit XML保存本地 `tmp/be-canonical-c3c/`。

探索轮原managed-cancel helper观测旧setup latch导致两项10秒事件等待失败，已改观察真实prefixbarrier并保持原断言；旧mixed explicit尚走旧driver的一项失败通过真正路由到canonical entry解决，无预期修改／禁用／过滤。较完整回归中两个无MinecraftServer的旧稀疏fixture触发coordinator线程检查，改为该类未知输入完整serial，并要求Native原函数证明必须有server，原pending／异常身份断言保持。修正前1307项全core已通过；提交前发现owner线程仍可提交无关Global Worker的窗口，拆分running与submitted准入并增加第六个真实入口案例，随后重跑最终完整矩阵。扩展call closure的probe首次因fixture未转发真实getLevelData而23项拒绝，保留结果并将getter转发原field后复验；这是fixture拒绝，非新生产放行。另一轮完整core1308项中旧Furnace stealing测试maxSimultaneousBodies偶发1，发现快runner可在慢runner开始前完成其他5正文；只新增先等slowEntered的Latch，保留全部原assert／overlap2／steals／canonical commit预期后重跑。Formatting/license/diff通过，无Vanilla patch变更，A2权威patch和配置hash保持。

本片独立验证后进入C4，将原Furnace preparation／resource／dependency／reservation／完整body／canonical通知commit接入这个同phase batch与cursor。C5、Create、MSPT不扩展；无installed-server、GameTest、Modpack或性能结论。
