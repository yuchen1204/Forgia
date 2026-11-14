# Canonical BE C3-B：最小 invocation Effect Bridge

基线：C3-A2 提交 `b061da4d57294bf4581f007e2460eadd86b64d96`。本片已接入生产的 invocation-scoped continuation 归属与有序提交，修复上一片真实 `nativeServerTaskMustBeVisibleToTheNextUnknownFence` 目标。**统一cursor尚未实现，三项原C1目标仍红；本片不是完整C3验收。**

## 生产范围与归属

仍只认证原始冷却／禁用 Hopper 的封闭 body。新 Level 路径进一步要求 v1 ADMITTED 与这个核心原函数证明取交集；未知原函数或 Chest callback 即使返回 ADMITTED 也完整串行。没有新增 Generic 协议。v1 prepare-once／owner Worker check 保留；check 是原只读合同的额外拒绝门，不是开放未知正文的许可。C1 Generic fixture 改在真实已审计 Hopper 上验证原来的次数／线程／refusal断言，原目标预期不改。

[BlockEntityExecutionEngine](../src/main/java/net/neoforged/neoforge/server/threading/region/BlockEntityExecutionEngine.java) 用既有 Ticket/Permit 保存 continuation 归属：同phase及Ticket identity、inspection generation、canonical ordinal、invocation内effect index及明确kind `INVOCATION_SERVER_TASK`。只有当前线程已经开始、持有原Guard/Permit且启用该证明的body能记录；另一个线程、未开始／serial Permit不继承。Adapter拿不到记录、Permit或commit权。上限取既有每invocation reservation单位；超出为已开始正文的协议失败，不改资源2048/512/131072预算，也不在body后重跑。

核心入口 `queueServerTaskInCurrentBatch` 在受证明的canonical Native body中归属该invocation。真正原phase-end生产者——缺chunk的邻居级联及entity-addition handoff——改用显式 `queueLevelEndServerTaskInCurrentBatch`，保留原entity-batch或level-end transport。未知正文在body前串行，不能通过当前Region位置、旧type声明、provider预检或历史入队记录获得该权限。历史explicit/indexed桥保留原时机，不能当作新Level路径的认证入口。

这里的封闭 Hopper body 生产上只直接写自己的计数，本身不发延迟业务callback；effect案例通过准入后观察钩子注入生产队列入口，测试真实Ticket、Worker、队列和commit，不声称原始Hopper会发此callback，更不声称任意Mod Runnable安全。新桥尚未认证其他effect kind；其他body仍完整串行。受保护Furnace notification仍用旧桥，C4再合并。

## join、commit与失败

canonical Native的原Region lease由server coordinator保留到physical join及必要effect commit之后；Worker在原body返回时关闭自己的Permit/Guard/owner，不能提前释放外层lease。原active batch覆盖所有提交和fallback。记录按ordinal、再effect index提取，与Worker入队到达顺序无关；generation和Ticket identity再次核对，提取先退休再调用payload。一个effect部分生效后失败，不重试它，继续其他已开始调用的必要commit，保留原异常身份及suppressed链。必要commit／body失败后不启动未知fallback后缀，未开始Ticket按原kernel退休。

level-end queue未整体提前drain，`EventHooks.fireLevelTickPost`仍先发布真实Post event，再由endLevelTick执行原任务。operation Request/queue/close状态机、scheduled mutation队列、原通知顺序、Region RNG算法／流和原始完整ticker均未改。本片没有把旧group按wave重复构造，也未创建另一套Ticket／Permit／Region lease／active batch。

## 新增真实路径验证

[InvocationEffectsTest](../tests/src/junit/java/net/neoforged/neoforge/unittest/CanonicalBlockEntityInvocationEffectsTest.java) 新增5项：

- 两条真实不同Region Worker强制B先入队，仍提交A0,A1,B0,B1，并在Unknown前可见；物理body返回先于Fence。
- 同一body的invocation effect在Fence前可见，显式level-end任务经过真实`EventHooks.fireLevelTickPost`，在Post listener之后恰好执行一次。
- 第一个commit已有状态后抛原异常，后续已开始调用仍commit，Unknown未开始，endLevelTick不重放。
- 已开始body在入队后失败，保留其必要effect及另一Region的effect，同Region未开始tail不执行、不重放。
- 一个真实Chest source/未知bound函数即使注册v1返回ADMITTED，仍在server完整调用；另一已证明Hopper继续Worker并行。

世界仍是有界mock，原始Hopper／bound wrapper／Level loop／Worker pool／Ticket／Permit／queue／Post Event入口是真实实现。Native真实cohort跨Region overlap继续由C3-A2测试在四配置实际确认，不以全串行制造通过。

| 实际配置／范围 | 总数 | 通过 | C3-C目标failure | error／skip |
|---|---:|---:|---:|---:|
| `final-fixed-on` | 236 | 233 | 3 | 0／0 |
| `final-persistent-off` | 236 | 233 | 3 | 0／0 |
| `final-persistent-on` | 236 | 233 | 3 | 0／0 |
| `final-full-core-fixed-off` | 1302 | 1299 | 3 | 0／0 |

每组58个canonical相关invocation，55pass／3failure，0error/skip；三条顺序目标依赖C3-C。上一片effect visibility原断言及新增5项均通过。全部JUnit真实exit1，原失败测试未改预期、未禁用／过滤。原1244项本轮完整重跑并核对XML Counter通过。原C1 Sequence/Fence/NativeEffects/Inventory/Collection源hash不变，仅support迁移真实输入。

实际命令、源码freeze、exit、日志和原XML位于本地`tmp/be-canonical-c3b/`。Formatting/license通过，无Vanilla patch改动，A2权威patch及配置hash全部保持。本片无installed-server、GameTest、Modpack、Create或性能结论。默认开关、API、预算未改；C3-C下一阶段实现连续prefix/Fence/fresh suffix，C4尚未进入，C5/Create/MSPT范围不扩张。
