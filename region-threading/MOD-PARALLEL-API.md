# 模组并行 API：第一阶段接入指南

状态：Experimental，API major = 1。本页说明当前已实现的接口；全部公开类型、方法签名、调度器和配置入口见 [API 接口总览](API-REFERENCE.md)，完整规划见 [RFC](MOD-PARALLEL-API-RFC.md)。不保证未冻结 API 的二进制兼容性。

## C5：通用 Block Entity Adapter 申请协议

[General Mod Adapter 架构与验证](GENERAL-MOD-ADAPTER-ARCHITECTURE.md)提供新的可选
`GENERAL_BLOCK_ENTITY_ADAPTERS` capability。`Registrar.registerBlockEntityAdapter(1, descriptor, adapter)`
在原注册事件内使用，C5 protocol=1、ParallelApi major仍为1。新增`api.adapter`包不引用Minecraft类型；
`BlockEntityAdapter`、`BlockEntityAdapters`及`DedicatedBlockEntityAdapter`共同描述exact类型、原ticker执行
申请、资源READ/WRITE、依赖/生命周期验证和拒绝。Dedicated audit reference/revision只提供审查出处。

**C5没有通用Worker证明后端，全部C5注册目标完整串行。** 注册回调、CURRENT、自称local、Dedicated标记
或Mod/类名不认证body closure。检查在唯一canonical cursor对应的原Serial Permit里执行，前prefix已真实
join/必要commit，随后完整原ticker最多一次；异常传播、未开始suffix停止，不将已产生效果的body重放。
未注册C5的原Native Hopper/Furnace proof与Worker路径保持。没有worker/执行/commit回调或调度器接口。

新协议共享v1/spawn的128总注册上限和id空间，与v1类型交集也使整次事务失败。注册冻结；Registration
可在服务器协调线程invalidate/close，保留有界tombstone且不复活旧身份；服务停止后旧句柄终态CLOSED，
新启服创建独立服务。资源声明永不转换成Claims/Lease。UNKNOWN资源不探测provider；依赖失效或缺Core
审计均明确serial。纯只读validator每次重新调用，累计预算不按Fence重置。`phaseClosed`清理在同一原batch
仍活跃且物理工作/必要提交完成后执行，异常不会阻止其它清理和Core finally。

通过`Server.blockEntityAdapters()`查询只含元数据/计数/拒绝的状态，通过
`Server.lastBlockEntityAdapterPhase()`查询最近关闭会话的声明依赖诊断；这些waves省略其它ticker，不能作为
Level执行plan。现有`compat`命令仍显示既有spawn/v1栏目，C5诊断使用上述API。当前没有热重注册、任意
Capability/共享handler、多home或通用effect协议，也未实现Create/Mekanism/AE2 Dedicated Adapter。

## 已经可以使用的能力

- 服务器实例级注册事件 `RegisterParallelAdaptersEvent`，在 `NeoForge.EVENT_BUS` 上订阅。
- `ParallelApi.forServer(server)`：查询能力、执行阶段、是否安装 region 引擎、适配器准备统计。
- `NATURAL_SPAWN_PREPARATION`：为一个 chunk 的完整自然刷怪调用准备脱离世界的只读数据，或在 body 开始前要求串行执行。
- `ParallelApi.prepared(key)` / `currentSpawnUnit()`：只在对应 worker 刷怪调用及其同步监听器内取得准备结果，退出后清除。
- `/neoforge regionthreading compat`：列出注册、ready/serial 次数、累计准备耗时及最近串行原因。
- `NATURAL_SPAWN_PLAN_PREPARATION`：在核心 cap/state 构造前决定是否保留原生串行计划，或冻结整份计划的规则数据。
- `ORDERED_SPAWN_FINALIZATION`：候选选择在 worker，完整原生最终化/加入续跑在所有刷怪 worker 停下后由主线程执行。
- `NATURAL_SPAWN_CLAIMS`：已注册 preparation 可通过启动验证显式接管某个 mod 的自然刷怪限制；仅注册 preparation 本身不放行。

- `BLOCK_ENTITY_OPERATIONS`：不透明实例引用、完整双 owner 操作、带方向/槽位规则的 Container 搬运，以及取消、失效、失败与至多执行一次句柄。
- `SCHEDULED_TICK_DIAGNOSTICS`：通过 `Server.scheduledTicks()` 查询有界的阶段准入、实际完成、回退原因和耗时分布。
- `GENERAL_BLOCK_ENTITY_ADAPTERS`：上述C5保守申请/生命周期协议，C5注册类型完整Serial Fence，无新增Modded BE Worker准入。
- `BLOCK_ENTITY_TICK_ADMISSION`：通过 `Registrar.registerBlockEntityTicks` 注册原生机器 tick 的逐实例准入器；在 owner lease 内、每次调用原 ticker 前重新检查，拒绝则完整主线程回退。类型候选可缓存，实例许可不缓存，不能解除显式串行声明或 chunk 内部规则。`Server.blockEntityTickAdapters()` 提供实际检查、拒绝和原生 worker 完成统计。[Create 6.0.10 独立适配器](../adapters/create/README.md)保留旧v1协议实现和legacy桥验证；当前canonical Level还要求独立核心函数/closure证明，v1 ADMITTED不能认证Create或其它未知机器。

跨 region 接入示例、线程与生命周期合同、scheduled tick 生产准入见 [完整操作指南](CROSS-REGION-OPERATIONS.md)。跨区操作在静止的服务器完成阶段执行，调用不自动放行模组机器 tick。

本阶段没有结构查询快照服务或通用纯候选过滤器。
[独立适配模块 0.3.0](../adapters/mobspawncontroller/README.md) 实现准备协议、显式 claim 和有序最终化，但**默认不接管自然刷怪**。本地整包发现截断、尚无加速证据，只有显式实验开关才启用 nearby/chance 子集；参见 [本地结果](MOBSPAWNCONTROLLER-LOCAL-RESULTS.md)。倍率、结构及其他未实现条件仍保留完整原生串行计划。

## 编译与加载

先构建：

```sh
./gradlew :neoforge:parallelApiJar
```

产物位于 `projects/neoforge/build/libs/*-parallel-api.jar`，仅包含公开 facade（包括其嵌套类型）和注册事件，不包含 Engine 或 Runtime。开发环境仍需要 NeoForge/Minecraft 的正常编译依赖。

将该 jar 作为 `compileOnly` 使用，不要 shade 或 jar-in-jar。运行时由 Forgia 核心提供这些类。尚未发布独立 Maven 坐标；不要依赖 RFC 中的建议坐标。

兼容原版 NeoForge 的模组应将适配入口放在独立适配模组，或只在确认 Forgia 能力存在后加载的入口类。普通入口、字段和方法签名不要引用可选 Forgia 类，否则会在检测前发生类加载错误。本阶段没有可供原版 NeoForge 直接加载的空实现 jar。

## 最小示例：为自己的纯规则监听器准备快照

以下代码放在一个专用于 Forgia 的类中，监听的是 NeoForge 游戏事件总线。`Rules` 只是示例：实际模组可以在配置重载时原子替换它；集合必须复制为不可变集合。

```java
private static final ParallelApi.DataKey<Rules> RULES = new ParallelApi.DataKey<>(
        ResourceLocation.parse("example:natural_spawn_rules"), Rules.class);

private static final AtomicReference<Rules> CURRENT =
        new AtomicReference<>(new Rules(0L, Set.of()));

record Rules(long revision, Set<ResourceLocation> deniedTypes) {
    Rules {
        deniedTypes = Set.copyOf(deniedTypes);
    }
}

@SubscribeEvent
public static void register(RegisterParallelAdaptersEvent event) {
    event.registrar().register(ParallelApi.MAJOR_VERSION, RULES,
            unit -> new ParallelApi.Ready<>(CURRENT.get()));
}

@SubscribeEvent
public static void filter(FinalizeSpawnEvent event) {
    if (event.getSpawnType() != MobSpawnType.NATURAL) {
        return; // 非自然生成按模组自己的原有策略处理
    }
    Rules rules = ParallelApi.prepared(RULES).orElseGet(CURRENT::get);
    ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType());
    if (rules.deniedTypes().contains(type)) {
        event.setSpawnCancelled(true);
    }
}
```

示例只适用于模组自己拥有、判断为纯集合查询的一个监听器。它不替换另一份原模组监听器，不表示任意监听器在拿不到 snapshot 时都能安全读取世界。也不能在遇到 PASS 时调用 `setSpawnCancelled(false)` 清掉别人的拒绝。

存在准备数据不足时，返回：

```java
return new ParallelApi.ServerRequired<>(ResourceLocation.parse("example:structure_data_unavailable"));
```

核心会在调用 `NaturalSpawner.spawnForChunk` 前选择完整串行单元，不重复执行 body。该回退丢弃 per-unit 数据，但保留 `preparePlan` 已固定的 Ready 数据。普通 NeoForge、引擎/刷怪 worker 关闭以及整份计划被拒绝时，均走原生路径，prepared 数据为空。

如规则会影响 mob cap 或要求保持完整原生刷怪计划，应覆盖 `preparePlan(SpawnPlan)`：

- 返回 `ServerRequired`：核心在创建自己的 cap counter 前放弃并行计划，使用原生 `NaturalSpawner.createState` 和完整串行路径。
- 返回 `Ready<S>`：该 snapshot 固定到本计划结束，后续 chunk 不再调用该 provider 的 `prepare`；计划中的主线程回退也读取同一份数据。
- 返回 `new Ready<>(snapshot, true)`：在上述固定规则之外，请求有序最终化。只允许在 `preparePlan` 返回；逐 chunk 准备阶段才请求会被拒绝。旧的单参数构造器保持原 worker 最终化模式。
- 默认返回 `PerUnit<S>`：保持下例 lambda 的逐 chunk 准备协议。

`serialPlans` 统计整份计划拒绝，`serialUnits` 统计单个 chunk 回退，两者不能混成生成实体数。

## 精确执行合同

### 注册

- 世界 tick 前、biome modifiers 和 `ServerAboutToStartEvent` 之后，调用注册事件；包括 region 引擎关闭的服务器。
- 注册器只在事件内、服务器线程可用。事件结束冻结；保存注册器稍后调用会失败。
- id 是适配器身份，建议使用模组自己的 namespace；相同 id 即使 snapshot class 不同也不能重复。
- API major 不匹配、重复 id、超过 128 个注册都使整次注册失败，不发布半成品服务。即使监听器捕获校验异常，也不放行已被标记失败的注册。
- 注册按 id 排序，不按事件订阅先后执行。注册不能声明其他监听器已安全。只有显式 `claimNaturalSpawning(key, modId, verified)`、同一 key 已注册、验证成功且注册整体冻结后，核心才可将该 mod 的 DISABLE_SPAWNING 交给逐单元准入；不能解除 DISABLE_ENGINE 或管理员关闭的 slice。claim 是适配作者的合同，不是自动安全证明。

### 有序最终化

注册期间可用 `Registrar.capabilities()` 查询协议支持；这不会提前发布未冻结的 Server 服务，也不代表适配器已经放行。使用双参数 Ready 前检查 `ORDERED_SPAWN_FINALIZATION`。

该模式将原生 finalize、真实 passenger admission、afterSpawn 和 cluster/group-limit 回调放进一个主线程操作。其他刷怪 worker 必须全部停在安全点，之后的候选才能看到前一个操作的真实加入/取消结果。主线程等待时继续服务普通 handoff；线程池拒绝的组通过同一屏障在提交线程执行，异常、停机与中断不重放已产生副作用的操作。

原生选择循环在 worker 上执行；最终化没有变成 worker 并行。续跑携带同一个规则版本、unit 上下文和随机数流，prepared body 只开始一次。此前延期的世界效果先在停点发布。主线程服务仍不能调用 preparation；普通查询 handoff 不会继承 prepared 数据，只有经过本协议的对应续跑能够读取它。

适配器仍须验证原监听器及相关原生注入。不支持的配置或未知注入应在计划开始前 ServerRequired，不能把已执行的候选改成“重试串行”。跨组事务按提交顺序执行，未承诺原版全局候选顺序/RNG 序列逐位一致；实际开销必须包括停点和发布成本。

### 准备与快照

- `preparePlan` 在任何核心刷怪 cap 构造之前执行。选择 `PerUnit` 时，收集 chunk 才准备该单元；选择 `Ready` 时则冻结整份计划的规则 revision。默认 lambda 仍是 per-unit，不自动保证跨 chunk 的同一 revision。
- `SpawnUnit` 只包含维度、源 chunk 坐标和 gameTime，不提供 live level 或加载权限。
- 源 chunk 不代表候选位置一定处于其中。快照需要覆盖自己监听器可能查询的完整范围；无法保证时要求串行。
- 准备回调禁止游戏 RNG 消耗、实体构造、发事件、修改世界。返回的数据禁止携带 live chunk/entity、可变 NBT 或可变共享容器。Java 泛型/record 不能证明深层不可变，这是适配器实现的责任。
- per-unit 返回 ServerRequired 会丢弃该单元的部分准备结果；只有计划级 Ready 数据继续供本计划串行单元使用。preparePlan 的 ServerRequired 则完全退出核心并行计划，所有 prepared 数据均不进入原生路径。
- callback 异常、null 结果和错误 snapshot 类型会传播，不转成成功或自动重试。
- 首版限制注册数，但不抢占 Java 回调，也没有硬性耗时预算或结构数据缓存；耗时由诊断累计。回调必须自行约束工作量。

### 执行与清理

- 快照通过当前线程的短生命周期作用域传递，不从监听器调度的新线程继承。
- 作用域对应完整 `spawnForChunk` 调用，不代表候选已经过滤、mob 已获准加入或任何事件已执行。
- 串行回退隐藏不属于本计划的外层快照；计划级固定数据在本计划的串行单元中保留。异常退出恢复/清空作用域。
- 注册服务关闭后，旧 service 查询会抛明确异常，旧 prepared 单元不能再次执行；同一 JVM 再次启服建立独立服务。
- 有准备数据的单元执行时检查服务器、维度与 gameTime；失效时跳过 body。不得把 prepared 单元当作跨 tick 队列。
- 公开的 snapshot 值可以作为不可变纯数据保存，但保存数据不能延长所有权或表示未来世界仍处于该状态。

### 线程阶段

| Phase | 含义 |
|---|---|
| QUIESCENT_SERVER | 服务器线程且没有其他 worker 持有活跃工作；仍需遵守每个世界操作自身的合同 |
| SERVER_SERVICE | 服务器线程在活跃批次期间服务 worker；不能准备数据。普通 handoff 不允许任意世界写入；有序最终化只允许在同批 worker 全部暂停后执行其完整原生续跑 |
| REGION_WORKER | 当前服务器维度的已服务 region 批次 worker；仍需所有权检查 |
| EXTERNAL | 其他线程或非 region 批次 worker，无世界权限 |

能力存在不等于对应 slice 已启用，phase 也不等于持久 mutation lease。现有调度和跨实体操作继续通过 `RegionThreading` 等接口使用；本阶段不提供 worker 内阻塞等待任意主线程任务的公共接口。

## 原生方块实体逐次准入

`BLOCK_ENTITY_TICK_ADMISSION` 提供 `BlockEntityTickAdapter`，针对整次原始 tick，不替换机器算法。
在 `RegisterParallelAdaptersEvent` 中调用：

```java
event.registrar().registerBlockEntityTicks(ParallelApi.MAJOR_VERSION,
        ResourceLocation.parse("example:steady_machine"),
        Set.of(ResourceLocation.parse("example:machine")), new BlockEntityTickAdapter() {
            @Override
            public void prepare(ServerLevel level) {
                // 每个非空 level BE batch 前，主线程且世界静止。
                // 只读已加载资源；有界检查整个共享组件，发布只含不可变值的批次 metadata。
            }

            @Override
            public Decision check(BlockEntity instance) {
                // 当前 owner 的字段 + prepare 的只读 metadata。必须覆盖继承/behaviour/lazy tick。
                return auditedWholeTickIsLocal(instance) ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
            }
        });
```

`auditedWholeTickIsLocal` 是适配者自己的完整正文证明，不是引擎提供的检测器。谓词执行于原生 worker 的
精确 ticker 作用域，禁止修改状态、随机数、发事件、加载 chunk、查询外部 live 资源或保留实例。
核心在调用前验证实例、类型、位置、level 和 captured bound ticker 的精确身份。

类型候选的查找可缓存，`prepare` 每批执行，`check` 每次原始调用前执行。服务器显式串行声明仍优先；
适配类型限于 region 内部 chunk，未准入类型导致整个 chunk 保留串行。实例拒绝只推迟完整 ticker，
不先执行一部分、不中途重试。谓词、prepare 或原始正文异常作为失败传播；已产生的机器效果不重放。

适配器 id 和类型集合不可重复，最多 128 个注册，每个注册最多 128 个非空类型。注册仅在服务器启动事件
期间有效，停止后服务/候选失效。注册不打开 engine/slice，也不会解除其它模组的兼容限制。

`Server.blockEntityTickAdapters()` 与 `compat` 显示检查、拒绝、实际 worker 完成、准备与检查耗时、
有界拒绝理由。完成统计不包含 executor 拒绝后的内联调用；这些不是性能提升的证明。

[Create 适配器](../adapters/create/README.md) 是该协议的受限实现：动力网络的读遍历只发生在 quiescent
preflight，worker 不遍历 Create 的共享动力 `HashMap`。无法证明整个网络稳定时保留串行。

## 验证边界和下一步

`ParallelApiTest` 覆盖注册生命周期、版本、重复、冻结、显式回退、异常、不重放、作用域清理、过期与外部线程。`RegionSpawnTest` 通过实际 spawn plan、worker batch 和真实 NaturalSpawner cap 路径检查数据传递、主线程服务隔离与完整串行单元只执行一次。

这些测试不是 MobSpawnController 的结构过滤验收。针对 1.2.3 的实际调用、规则共享、附近数量限制和接管边界，见
[适配前审查](MOBSPAWNCONTROLLER-ADAPTER-AUDIT.md)。显式启动验证和按配置回退已经实现；结构数据服务、附近数量/倍率的并行协议仍待实现。首版也未对全量第三方事件监听器提供自动安全认证。
