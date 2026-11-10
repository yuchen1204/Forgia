# 模组并行 API：第一阶段接入指南

状态：Experimental，API major = 1。本页说明当前已实现的接口；完整规划见 [RFC](MOD-PARALLEL-API-RFC.md)。不保证未冻结 API 的二进制兼容性。

## 已经可以使用的能力

- 服务器实例级注册事件 `RegisterParallelAdaptersEvent`，在 `NeoForge.EVENT_BUS` 上订阅。
- `ParallelApi.forServer(server)`：查询能力、执行阶段、是否安装 region 引擎、适配器准备统计。
- `NATURAL_SPAWN_PREPARATION`：为一个 chunk 的完整自然刷怪调用准备脱离世界的只读数据，或在 body 开始前要求串行执行。
- `ParallelApi.prepared(key)` / `currentSpawnUnit()`：只在对应 worker 刷怪调用及其同步监听器内取得准备结果，退出后清除。
- `/neoforge regionthreading compat`：列出注册、ready/serial 次数、累计准备耗时及最近串行原因。

本阶段没有结构查询快照服务、纯候选过滤器、原监听器自动接管，也不会解除 `mobspawncontroller` 的 `DISABLE_SPAWNING`。仅注册 preparation 不代表它的 FinalizeSpawn 监听器已经安全。

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

核心会在调用 `NaturalSpawner.spawnForChunk` 前选择完整串行路径，不重复执行 body。回退 body 中 prepared 数据为空，模组应走自己完整的串行判断。普通 NeoForge、引擎/刷怪 worker 关闭、已有邻域/预算回退也均使用原有串行逻辑，不强行提供一个虚构的 snapshot。

## 精确执行合同

### 注册

- 世界 tick 前、biome modifiers 和 `ServerAboutToStartEvent` 之后，调用注册事件；包括 region 引擎关闭的服务器。
- 注册器只在事件内、服务器线程可用。事件结束冻结；保存注册器稍后调用会失败。
- id 是适配器身份，建议使用模组自己的 namespace；相同 id 即使 snapshot class 不同也不能重复。
- API major 不匹配、重复 id、超过 128 个注册都使整次注册失败，不发布半成品服务。即使监听器捕获校验异常，也不放行已被标记失败的注册。
- 注册按 id 排序，不按事件订阅先后执行。注册不能声明其他监听器已安全，也不能绕过管理员或兼容表收窄策略。

### 准备与快照

- 目前是 **per-unit**：收集一个已有资格的 chunk 时，调用准备回调；部分串行单元仍在现有 inline 点立即执行。不保证整个 tick 共享一个规则 revision，也不改变原有刷怪 RNG 或全局 cap 协议。
- `SpawnUnit` 只包含维度、源 chunk 坐标和 gameTime，不提供 live level 或加载权限。
- 源 chunk 不代表候选位置一定处于其中。快照需要覆盖自己监听器可能查询的完整范围；无法保证时要求串行。
- 准备回调禁止游戏 RNG 消耗、实体构造、发事件、修改世界。返回的数据禁止携带 live chunk/entity、可变 NBT 或可变共享容器。Java 泛型/record 不能证明深层不可变，这是适配器实现的责任。
- 一个适配器返回 ServerRequired 就丢弃该单元已准备的其他快照；没有准备好的部分数据进入串行 body。
- callback 异常、null 结果和错误 snapshot 类型会传播，不转成成功或自动重试。
- 首版限制注册数，但不抢占 Java 回调，也没有硬性耗时预算或结构数据缓存；耗时由诊断累计。回调必须自行约束工作量。

### 执行与清理

- 快照通过当前线程的短生命周期作用域传递，不从监听器调度的新线程继承。
- 作用域对应完整 `spawnForChunk` 调用，不代表候选已经过滤、mob 已获准加入或任何事件已执行。
- 串行回退隐藏外层快照；异常退出也会恢复/清空作用域。
- 注册服务关闭后，旧 service 查询会抛明确异常，旧 prepared 单元不能再次执行；同一 JVM 再次启服建立独立服务。
- 有准备数据的单元执行时检查服务器、维度与 gameTime；失效时跳过 body。不得把 prepared 单元当作跨 tick 队列。
- 公开的 snapshot 值可以作为不可变纯数据保存，但保存数据不能延长所有权或表示未来世界仍处于该状态。

### 线程阶段

| Phase | 含义 |
|---|---|
| QUIESCENT_SERVER | 服务器线程且没有其他 worker 持有活跃工作；仍需遵守每个世界操作自身的合同 |
| SERVER_SERVICE | 服务器线程在活跃批次期间服务 worker；不能在这里准备数据或任意加载/变更世界 |
| REGION_WORKER | 当前服务器维度的已服务 region 批次 worker；仍需所有权检查 |
| EXTERNAL | 其他线程或非 region 批次 worker，无世界权限 |

能力存在不等于对应 slice 已启用，phase 也不等于持久 mutation lease。现有调度和跨实体操作继续通过 `RegionThreading` 等接口使用；本阶段不提供 worker 内阻塞等待任意主线程任务的公共接口。

## 验证边界和下一步

`ParallelApiTest` 覆盖注册生命周期、版本、重复、冻结、显式回退、异常、不重放、作用域清理、过期与外部线程。`RegionSpawnTest` 通过实际 spawn plan、worker batch 和真实 NaturalSpawner cap 路径检查数据传递、主线程服务隔离与完整串行单元只执行一次。

这些测试不是 MobSpawnController 的结构过滤验收。下一阶段需要只读结构数据服务，以及原监听器与替代过滤器的原子接管协议；在此之前继续保留其串行保护。首版也未对全量第三方事件监听器提供自动安全认证。
