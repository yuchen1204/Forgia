# Forgia API 接口总览

本文是 Forgia 自己提供的模组接入接口目录，覆盖 `21.1.250.55-beta` 的公开 Java 类型、方法、数据类型、注册事件、兼容性声明和诊断入口。上游 NeoForge 原有的完整事件、capability、注册表等 API 不在本目录重复列举。本文只描述已实现接口；RFC 中尚未实现的方法不作为可调用 API。

当前并行门面 **API major = 1，状态 Experimental**。公开类型不等于稳定 ABI；模组应按能力协商，不按 Forgia 构建号猜测行为。标记 `@ApiStatus.Internal` 的运行时、Hooks、Engine、lease、batch 和测试开关不属于模组支持合同，见[内部边界](#12-内部边界与未提供的能力)。

## 目录

1. [按需求选择入口](#1-按需求选择入口)
2. [依赖、加载与生命周期](#2-依赖加载与生命周期)
3. [ParallelApi：服务器服务与能力](#3-parallelapi服务器服务与能力)
4. [适配器注册与自然刷怪准备](#4-适配器注册与自然刷怪准备)
5. [BlockEntityOperations：完整跨区方块实体操作](#5-blockentityoperations完整跨区方块实体操作)
6. [ScheduledTickStatus：scheduled tick 诊断](#6-scheduledtickstatusscheduled-tick-诊断)
7. [RegionThreading：调度与所有权入口](#7-regionthreading调度与所有权入口)
8. [三个调度器与 ScheduledRegionTask](#8-三个调度器与-scheduledregiontask)
9. [EntityInteractionScheduler：完整实体交互](#9-entityinteractionscheduler完整实体交互)
10. [兼容性声明与配置接口](#10-兼容性声明与配置接口)
11. [示例、观测命令与验证资料](#11-示例观测命令与验证资料)
12. [内部边界与未提供的能力](#12-内部边界与未提供的能力)
13. [公开类型源码索引](#13-公开类型源码索引)

## 1. 按需求选择入口

| 需求 | 获取路径 | 主要类型 | 无 region 引擎时 |
|---|---|---|---|
| 查询 API 版本、能力、当前阶段 | `ParallelApi.forServer(server)` | `ParallelApi.Server` | 注册完成后仍有服务 |
| 注册自然刷怪数据准备／适配声明 | `RegisterParallelAdaptersEvent.registrar()` | `ParallelApi.Registrar` | 仍触发注册，原生串行路径不自动执行准备 |
| 读取当前刷怪调用的准备快照 | `ParallelApi.prepared(key)` | `DataKey<S>`、`SpawnUnit` | 通常为空 |
| 注册受审计的逐次 BE tick 准入 | `RegisterParallelAdaptersEvent.registrar()` | `BlockEntityTickAdapter` | 原生串行；注册不启用 slice |
| 完整双 BE 操作、容器槽位搬运 | `api.blockEntities()` | `BlockEntityOperations` | 静止服务器线程立即执行；其他上下文拒绝 |
| scheduled tick 计数与耗时 | `api.scheduledTicks()` | `ScheduledTickStatus` | 返回空列表 |
| 在位置所属 region 安排任务 | `RegionThreadingManager.get()` → `regionScheduler()` | `RegionScheduler` | Manager 返回空，无自动替代调度器 |
| 任务跟随实体迁移 | 同上 → `entityScheduler()` | `EntityRegionScheduler` | 同上 |
| 安排全局任务 | 同上 → `globalScheduler()` | `GlobalRegionScheduler` | 同上 |
| 查询／断言位置或实体所有权 | 同上 | `RegionThreading` | 模组自行选择原生串行分支 |
| 整体实体交互、owner 操作、加载后 owner 操作 | 同上 → `entityInteractions()` | `EntityInteractionScheduler` | 无引擎入口；不同于始终注册的 ParallelApi 服务 |
| 声明模组兼容等级 | mod metadata `regionThreading` | `RegionThreadingSupport` | 仅声明，不安装引擎 |
| 收窄类型／显式承诺 BE 准入 | JSON 声明文件 | `entities`、`block_entities`、`admit_block_entities` | 声明不会自行启用 slice |

所有跨 owner 操作都应在其副作用之前提交。保存一个实体、chunk、容器或上下文对象，不会延长修改权限。不要在 worker 上等待另一个 region、主线程操作或任务结果。

## 2. 依赖、加载与生命周期

### 2.1 两套公开入口的编译范围

构建轻量门面：

```sh
./gradlew --offline :neoforge:parallelApiJar
```

产物为 `projects/neoforge/build/libs/*-parallel-api.jar`，包含：

- `net.neoforged.neoforge.server.threading.api` 包及其嵌套类型。
- `net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent`。

门面 **不包含** `RegionThreadingManager`、三个旧调度器、`EntityInteractionScheduler` 或任何 Engine/Runtime。使用第 7～9 节接口时，需要完整 Forgia 编译依赖；不能仅引入轻量门面就期望这些类型存在。两种方式均仍需要正常的 Minecraft／NeoForge 编译依赖。

```groovy
// 仅示意本地门面文件，非已发布 Maven 坐标。
dependencies {
    compileOnly files('libs/forgia-parallel-api.jar')
}
```

不要 shade、重定位或 jar-in-jar 第二份 API。运行类由 Forgia 核心提供。目前没有已冻结的独立 Maven 发布坐标。

同时兼容普通 NeoForge 的模组，应使用独立 Forgia 适配模组，或只在确认环境后加载专用入口类。普通入口、字段和方法签名不能预先引用可选 Forgia 类型，否则可能在能力检测前发生类加载错误；门面不是供普通 NeoForge 运行的空实现。

### 2.2 服务与引擎生命周期

`ParallelApi.forServer(server)` 在注册冻结前和服务关闭后为空；服务按 MinecraftServer 实例隔离。保留旧 `Server` 后再调用服务查询方法会抛 `IllegalStateException`。旧 `BlockEntityOperations` 在服务关闭后拒绝提交／引用创建；不能用于下一次启服。

`RegionThreadingManager` 是当前已安装引擎的入口。关闭 bridge 时 `get()` 为空，`getOrThrow()` 抛异常；Manager 不负责补一个普通异步 executor。模组需要自己的原生串行路径。

`capabilities()` 描述实现了什么协议，不代表配置已经启用某个 slice，也不证明任意回调线程安全。

## 3. ParallelApi：服务器服务与能力

包：`net.neoforged.neoforge.server.threading.api`。

### 3.1 静态入口

```java
public static final int MAJOR_VERSION = 1;
public static Optional<ParallelApi.Server> forServer(MinecraftServer server);
public static <S> Optional<S> prepared(ParallelApi.DataKey<S> key);
public static Optional<ParallelApi.SpawnUnit> currentSpawnUnit();
```

`forServer`、`prepared` 的参数不允许 null。`prepared` 与 `currentSpawnUnit` 仅在对应自然刷怪 body 及同步回调的有效作用域中可见，不从新线程继承。取得快照不表示候选已经通过筛选，也不能据此取消其他监听器。

### 3.2 Server 全部方法

```java
int apiMajorVersion();
Set<ParallelApi.Capability> capabilities();
boolean regionThreadingInstalled();
ParallelApi.Phase currentPhase();
List<ParallelApi.AdapterStatus> adapters();
List<BlockEntityTickAdapter.Status> blockEntityTickAdapters();
BlockEntityOperations blockEntities();
List<ScheduledTickStatus> scheduledTicks();
```

| 方法 | 语义 |
|---|---|
| `apiMajorVersion()` | 当前为 1 |
| `capabilities()` | 协议能力集合；不要把它当作启用状态 |
| `regionThreadingInstalled()` | 当前是否安装 region 引擎 |
| `currentPhase()` | 当前调用线程的执行阶段；不能作为所有权通行证 |
| `adapters()` | 自然刷怪 preparation 注册者及累计结果 |
| `blockEntityTickAdapters()` | BE 适配器类型集合、准备／检查耗时、逐次拒绝原因和实际 worker 完成数；接口默认实现为空列表 |
| `blockEntities()` | 完整 BE 操作服务；先检查对应 capability。接口默认实现可抛 `UnsupportedOperationException` |
| `scheduledTicks()` | 当前引擎的 block/fluid 阶段快照；无引擎时为空。接口默认实现为空列表 |

### 3.3 Capability 全部枚举值

| 枚举值 | 提供的协议 |
|---|---|
| `EXECUTION_CONTEXT` | `currentPhase()` |
| `NATURAL_SPAWN_PREPARATION` | 逐源 chunk 完整刷怪调用的数据准备 |
| `NATURAL_SPAWN_PLAN_PREPARATION` | 原生 cap/state 构造前的整计划准备 |
| `NATURAL_SPAWN_CLAIMS` | 注册者对特定 mod 刷怪限制的显式验证接管 |
| `ORDERED_SPAWN_FINALIZATION` | worker 候选选择＋主线程有序原生最终化 |
| `BLOCK_ENTITY_TICK_ADMISSION` | 受审计 BE 原生 tick 的静止准备和逐次准入检查 |
| `BLOCK_ENTITY_OPERATIONS` | 完整双 owner BE 操作和容器搬运 |
| `SCHEDULED_TICK_DIAGNOSTICS` | scheduled tick 阶段计数／耗时查询 |

### 3.4 Phase 全部枚举值

| 枚举值 | 含义与边界 |
|---|---|
| `QUIESCENT_SERVER` | 服务器线程处于可证明静止的阶段；仍须遵守具体操作合同 |
| `SERVER_SERVICE` | 主线程为仍活跃的 worker 服务；不能据此任意加载／修改世界 |
| `REGION_WORKER` | 当前在对应服务器的 region 批次 worker 中；只有本次授权范围可修改 |
| `EXTERNAL` | 其他线程／不满足上述执行条件；无世界修改权限 |

这套 Phase 与第 7 节的 `RegionThreadingContext.Kind` 不同：前者区分静止服务器与服务阶段，后者描述引擎上下文种类。不要以 `Kind.LEVEL` 或 `isTickThread()` 替代静止判断。

## 4. 适配器注册与自然刷怪准备

### 4.1 RegisterParallelAdaptersEvent

完整类名：`net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent`。

```java
ParallelApi.Registrar registrar();
MinecraftServer getServer(); // 继承自 ServerLifecycleEvent
```

事件在 `NeoForge.EVENT_BUS` 上触发，位于 biome modifiers 和 `ServerAboutToStartEvent` 之后、世界 tick 之前；region 引擎关闭时也注册服务。构造器为内部入口，模组订阅事件而不自行派发。

### 4.2 Registrar 全部方法

```java
Set<ParallelApi.Capability> capabilities();
<S> void register(int apiMajorVersion,
        ParallelApi.DataKey<S> key,
        ParallelApi.NaturalSpawnPreparation<S> preparation);
void claimNaturalSpawning(ParallelApi.DataKey<?> key,
        String modId,
        BooleanSupplier verified);
void registerBlockEntityTicks(int apiMajorVersion, ResourceLocation id,
        Set<ResourceLocation> types, BlockEntityTickAdapter adapter);
```

注册只在事件内、服务器线程有效，结束后冻结。注册期间的 `Registrar.capabilities()` 可用于协商，此时 `forServer` 尚不发布半成品服务。`claimNaturalSpawning` 的接口默认实现可抛 `UnsupportedOperationException`。

规则：

- 最多 128 个注册，id 唯一，主版本必须匹配；准备回调按 id 排序。
- 重复 id、版本不符、超限等使整次注册失败，不发布部分注册；捕获校验异常也不能绕过这一点。
- claim 必须引用已注册的同一 key、非空 modId，不能重复，且启动时 `verified` 返回 true。
- claim 仅替代其明确覆盖的自然刷怪限制，不解除 engine 禁用、管理员关闭或其他模组限制。
- 注册／claim 是适配器作者的具体合同，不是核心自动证明未知监听器安全。

### 4.3 Key、计划与单元

```java
record DataKey<S>(ResourceLocation id, Class<S> snapshotType) {}
record SpawnPlan(ResourceKey<Level> dimension, long gameTime) {}
record SpawnUnit(ResourceKey<Level> dimension, int chunkX, int chunkZ, long gameTime) {}
```

每个 record 暴露同名访问器。`DataKey` 不接受 null 或 primitive class；`SpawnPlan`／`SpawnUnit` 的维度非 null。`chunkX/chunkZ` 表示刷怪源 chunk，不能当作所有候选位置的边界。这些类型不提供 chunk 加载或 live 世界访问器。

### 4.4 NaturalSpawnPreparation 与所有准备结果

```java
@FunctionalInterface
interface NaturalSpawnPreparation<S> {
    default Preparation<S> preparePlan(SpawnPlan plan); // 默认 new PerUnit<>()
    Preparation<S> prepare(SpawnUnit unit);
}

sealed interface Preparation<S> permits Ready, ServerRequired, PerUnit {}
record PerUnit<S>() implements Preparation<S> {}
record Ready<S>(S snapshot, boolean orderedFinalization) implements Preparation<S> {
    Ready(S snapshot); // orderedFinalization = false
}
record ServerRequired<S>(ResourceLocation reason) implements Preparation<S> {}
```

| 返回位置／结果 | 行为 |
|---|---|
| `preparePlan → PerUnit` | 收集各 chunk 时逐项调用 `prepare` |
| `preparePlan → Ready(snapshot)` | 冻结整计划数据，后续不再为该 provider 调用逐 chunk 准备；计划内串行 fallback 仍读同一数据 |
| `preparePlan → Ready(snapshot, true)` | 在整计划快照上请求有序最终化，需支持对应 capability |
| `preparePlan → ServerRequired(reason)` | cap/state 构造之前保留整个原生串行计划 |
| `prepare → Ready(snapshot)` | 该完整 chunk 刷怪调用准备就绪；全部相关 provider 准备成功才可 worker 准入 |
| `prepare → ServerRequired(reason)` | body 开始前将该完整单元留在串行路径；丢弃该逐项数据，整计划快照仍可保留 |

`PerUnit` 用于整计划入口；逐单元准备应返回 `Ready` 或 `ServerRequired`。不能在逐 chunk 的 `prepare` 中才请求 `orderedFinalization=true`。`Ready.snapshot` 和 `ServerRequired.reason` 非 null；reason 使用有界命名空间标识，例如 `example:structure_unavailable`，不要为每个坐标创建 reason。

准备必须纯：不消费游戏 RNG、不发事件、不构造实体、不写世界。快照必须深层不可变、脱离 live 世界，禁止包含 Entity、LevelChunk、可变 NBT、共享 RNG 等。record／泛型本身不能保证深层不可变。回调异常、null 返回或错误快照类型按失败传播，不静默转换成串行成功。当前不抢占 Java 准备回调，也未提供通用结构快照／缓存服务。

有序最终化保留原生 finalize、加入、afterSpawn 和同步结果语义：候选选择在 worker，完整最终化在其他刷怪 worker 停到安全点之后于服务器执行。该模式不承诺原版全局候选顺序或 RNG 序列逐位一致。

### 4.5 AdapterStatus

```java
record AdapterStatus(ResourceLocation id,
        long preparedUnits, long serialUnits, long serialPlans,
        long preparationNanos, Optional<ResourceLocation> lastSerialReason) {}
```

数值为累计准备单元／串行单元／拒绝计划／准备耗时，不是实体生成数或每 tick 毫秒。使用快照差值计算区间速率。

### 4.6 最小注册示例

```java
static final ParallelApi.DataKey<String> RULES = new ParallelApi.DataKey<>(
        ResourceLocation.parse("example:rules"), String.class);

@SubscribeEvent
public static void register(RegisterParallelAdaptersEvent event) {
    event.registrar().register(ParallelApi.MAJOR_VERSION, RULES,
            unit -> new ParallelApi.Ready<>("immutable-rule-version"));
}

// 对应自然刷怪 body 的同步监听器内读取：
Optional<String> rules = ParallelApi.prepared(RULES);
```

完整筛选／整计划／claim 示例见[刷怪适配指南](MOD-PARALLEL-API.md)。

### 4.7 BlockEntityTickAdapter：完整原生 tick 的逐次准入

```java
public interface BlockEntityTickAdapter {
    default void prepare(ServerLevel level) {}
    Decision check(BlockEntity blockEntity);

    enum Decision {
        ADMITTED, INITIALIZING, TOPOLOGY_CHANGE, CALLBACK_REQUIRED, UNKNOWN_CLASS, UNKNOWN_BEHAVIOUR,
        SHARED_RESOURCE, INVALID_INSTANCE, UNVERIFIED_TRANSFORMATIONS
    }

    record Status(ResourceLocation id, Set<ResourceLocation> types, long checks, long refusals,
            long workerCompletions, long preparationNanos, long checkNanos, Map<Decision, Long> reasons) {}
}
```

`prepare` 在非空 BE 阶段开始前，于静止服务器线程执行；只能检查已加载资源并发布本批次脱离 live 世界的只读元数据。`check` 在 region 所有权下、对应原生 ticker 开始前逐次执行，只读传入实例拥有的字段和不可变元数据。不得加载 chunk、读取外部可变对象、调用任意回调、消费 RNG 或产生副作用；准入结论不缓存。普通类型／内部 chunk 规则和整 chunk 回退仍然适用。

返回 `ADMITTED` 承诺完整继承 tick、behaviour、lazy tick 和第三方变换都满足这次所有权；注册是适配器作者的合同。其他结果在副作用之前选择完整串行 tick；检查异常是失败，不能触发部分正文重跑。`workerCompletions` 只计原生 worker 上成功返回的正文，任务拒绝或服务器串行不计入。

每个 id 最多注册 128 个 type，不能与其他 BE 注册重复。BE 与刷怪适配器共用总数 128 和唯一 id 空间；失败会使整次注册无效。诊断 reason 使用固定枚举，不得按坐标或网络创建无界标签。实例不能被保留作为以后修改的权限。参考 [Create 适配器](../adapters/create/README.md) 和[接口指南](MOD-PARALLEL-API.md#原生方块实体逐次准入)。

## 5. BlockEntityOperations：完整跨区方块实体操作

获取：`api.blockEntities()`；能力：`BLOCK_ENTITY_OPERATIONS`。

### 5.1 服务全部方法

```java
Reference reference(BlockEntity blockEntity);
<R> Task<R> submit(Reference source, Reference target, Operation<R> operation);
Task<TransferResult> transfer(Reference source, Reference target, Transfer request);
Counters counters();
```

`reference` 只在静止服务器线程或当前已准入 BE 的原生 bound ticker 内创建。必须是实际加载 chunk 中的 attached 实例；不加载 chunk。外区 target 引用应预先在静止服务器阶段创建，再作为输入发布给源机器。引用按服务实例隔离，不能跨重启复用。

`submit` 在任何此次操作的副作用之前调用。native worker 必须拥有**该绑定 ticker 对应的精确源 BE 实例**；同位置／同类型替换、其他机器和无相应绑定的任务不继承它的权限。请求最多 4096 项／BE 批次；取消不返还本批次受理名额。

服务器证明静止时立即执行；native BE worker 请求在全部 worker 返回、之前的延迟 publications／removals／scheduled mutations 发布后、串行 fallback 前执行。其他异步线程、实体 worker、普通调度任务或主线程服务阶段没有该 BE 提交权限。双方须同一 ServerLevel；执行前重验位置、类型、实例、世界和 chunk 加载状态。

当前完整双 owner 操作在完成阶段**串行执行**，没有授予跨区并行 lease。不会自动恢复 Java 调用栈、异步加载 chunk、回滚任意回调或将既有漏斗／Create tick 改造成适配器。未知类型的本地 tick 仍需自己的兼容性准入。

### 5.2 Reference、Operation 与 Task 全部成员

```java
interface Reference {
    ResourceKey<Level> dimension();
    BlockPos position();
    ResourceLocation type();
}

@FunctionalInterface
interface Operation<R> {
    @Nullable R execute(BlockEntity source, BlockEntity target);
}

interface Task<R> {
    State state();
    Reason reason();
    boolean cancel();
    Optional<R> result();
    Optional<Throwable> failure();
    default boolean isDone();
}
```

Reference 不透明，只弱引用原始世界／实例，不暴露 live BE 或 lease；自行实现该接口不能伪造有效引用。目标替换后旧引用会失效，需重新建立链接，不会自动修改新机器。

Operation 仅使用传入的参与者及捕获的不可变输入。结果必须是脱离世界的不可变值；允许返回 null，因此 result 为空不等于未完成，要结合 state。不能返回 BE、capability、chunk 等作为未来修改权限。

`cancel()` 仅排队时能成功，成功保证之后不调用回调；RUNNING 后不可撤销。该 Task 没有 `join/get` 等待方法，也没有在未知线程执行的完成回调；在模组下一次安全 tick 中轮询脱离世界的结果。

### 5.3 State 与 Reason 完整枚举

| State | 含义 |
|---|---|
| `QUEUED` | 已排队，尚未开始 |
| `RUNNING` | 正在执行验证／操作，取消无效 |
| `COMPLETED` | 回调正常返回；非 null 结果可由 result 获取 |
| `SKIPPED` | 受理后因生命周期／身份／chunk 条件失效未调用 |
| `CANCELLED` | 排队中取消成功 |
| `REJECTED` | 未受理，无操作效果 |
| `FAILED` | 执行抛异常；保留 failure，不重放已产生的副作用 |

`isDone()` 对除 QUEUED、RUNNING 外的状态返回 true。

Reason 全部值：

```text
NONE, CANCELLED, INVALID_REFERENCE, INVALID_CONTEXT, UNOWNED_SOURCE,
BLOCK_ENTITY_REMOVED, SOURCE_REPLACED, TARGET_REPLACED, LEVEL_CHANGED,
CHUNK_UNAVAILABLE, LEVEL_CLOSED, SHUTDOWN, BATCH_CLOSED, QUEUE_FULL
```

Reason 说明生命周期／拒绝原因，不能代替 State 或 failure；例如 FAILED 的 reason 可以为 NONE。立即执行回调异常向调用者抛出；worker 受理的异常在服务器完成阶段传播，其他已受理请求继续排空。失败不是 rollback 保证。

### 5.4 Transfer、TransferResult、TransferStatus

```java
record Transfer(int sourceSlot, int targetSlot, int maxItems,
        @Nullable Direction extractionSide, @Nullable Direction insertionSide) {}
record TransferResult(int moved, TransferStatus status) {}
```

Transfer 的槽位必须非负，`maxItems` 为 1..64；非法构造参数抛 `IllegalArgumentException`。超出实际容器尺寸以 `INVALID_SLOT` 完成。方向是**源的抽取面／目标的插入面**，不是管道的行进方向。WorldlyContainer 必须给面，并遵守暴露槽位、插入／抽取检查；普通 Container 可传 null。

完整 TransferStatus：

| 值 | moved 与语义 |
|---|---|
| `MOVED` | 实际成功搬运数量，可能小于 maxItems |
| `NO_ITEMS` | 0，源槽为空 |
| `DESTINATION_FULL` | 0，没有可用容量 |
| `ITEM_REJECTED` | 0，类型／组件不匹配，或提取／插入／面规则拒绝 |
| `INVALID_SLOT` | 0，超出实际容器尺寸 |
| `SIDE_REQUIRED` | 0，有方向规则的容器未指定面 |
| `UNSUPPORTED_CONTAINER` | 0，参与者自身未实现 Container |
| `SAME_CONTAINER` | 0，引用同一 BE，不执行自搬运 |

零搬运是正常完成的业务结果，不是调度失败。方法不自动合并双箱、遍历网络或适配任意 IItemHandler。自定义 Container 必须遵守自己的契约；回调抛异常或返回不一致提取结果可能有部分效果，进入失败且不会自动重试。

### 5.5 Counters 全部字段

```java
record Counters(long accepted, long completed, long skipped, long cancelled,
        long rejected, long failed, long callbackNanos, Map<Reason, Long> reasons) {}
```

均为该服务累计值。`callbackNanos` 是回调本身 wall time；`reasons` 为不可修改、有界枚举映射。accepted 不能解释成 completed，cancelled／skipped 也不是已调用 body。

### 5.6 最小搬运示例

```java
// 静止服务器阶段，目标 chunk 已加载：
BlockEntityOperations ops = api.blockEntities();
BlockEntityOperations.Reference targetRef = ops.reference(targetMachine);

// 源机器自己的已准入 bound ticker 内，尚未抽取物品：
var task = ops.transfer(ops.reference(sourceMachine), targetRef,
        new BlockEntityOperations.Transfer(0, 0, 8, Direction.DOWN, Direction.UP));

// 后续安全 tick 轮询，不能在 worker 上等待：
if (task.state() == BlockEntityOperations.State.COMPLETED) {
    task.result().ifPresent(result -> recordMovedCount(result.moved()));
}
```

可独立编译的完整示例见 [ContainerLinkExample.java](../examples/parallel-api/ContainerLinkExample.java)，详细执行顺序见[完整操作指南](CROSS-REGION-OPERATIONS.md)。

## 6. ScheduledTickStatus：scheduled tick 诊断

获取：`api.scheduledTicks()`；能力：`SCHEDULED_TICK_DIAGNOSTICS`。当前每个引擎提供 block／fluid 两项，跨该引擎世界聚合；无引擎为空。不要用列表是否为空推断 slice 已开启，空／非空也不是某类回调的准入声明。

```java
record ScheduledTickStatus(String phase,
        long phases, long collected, long admitted,
        long workerCompletions, long serialCompletions,
        long failedCallbacks, long skippedCallbacks, long serialPhases,
        Map<SerialReason, Long> serialReasons, Timings timings, Latency latency) {}

record Timings(long preparationNanos, long workerNanos,
        long dispatchAwaitNanos, long completionNanos, long phaseNanos) {}
record Latency(int samples, long p50Nanos, long p95Nanos, long p99Nanos) {}
```

| 字段 | 单位与解释 |
|---|---|
| `phase` | `block`／`fluid` 阶段名称 |
| `phases` | 进入该 dispatcher preflight 的阶段数，可包含空阶段 |
| `collected` | scheduler 已选出的回调数 |
| `admitted` | 准入 worker 协议的回调数，不等于实际 worker 完成 |
| `workerCompletions` | 原生 worker 上正常返回的回调数 |
| `serialCompletions` | 串行正常返回数，包括 executor 拒绝后 inline |
| `failedCallbacks` | 已开始且失败的回调数 |
| `skippedCallbacks` | 未调用项，例如失败组余项、关闭世界或串行 clearArea 移除 |
| `serialPhases`／`serialReasons` | 完整阶段被拒绝并行的次数／原因，不是回调数 |
| `preparationNanos` | 准入准备累计时间 |
| `workerNanos` | 各 native worker 回调 wall time 之和，不是单段主线程耗时 |
| `dispatchAwaitNanos` | 派发、等待和该批次传播工作累计时间 |
| `completionNanos` | 批次完成工作累计时间 |
| `phaseNanos` | 从 collection 之后起的非空阶段整体 wall time 累计 |
| `samples`／p50／p95／p99 | 最近最多 128 个非空阶段的精确分位数，包含串行拒绝阶段，单位纳秒 |

SerialReason 全部值：

```text
UNCLASSIFIED_CALLBACK, FLUID_CALLBACK, NON_FIXED_LAYOUT, PROFILER_ACTIVE,
UNLOADED_FOOTPRINT, FOREIGN_FOOTPRINT, UNSAFE_NEIGHBOR, DEFERRED_UPDATE_BUDGET
```

通过两次快照差值求速率。标签／窗口内存有界，map 不可修改。没有用于模组注册任意 scheduled 回调、放行整个 block 类或手动开启测试 gate 的公开 API。当前生产准入仅覆盖有界惰性邻域内的原版灯完整阶段；其他阶段保持原串行队列。开关见第 10 节。

## 7. RegionThreading：调度与所有权入口

包：`net.neoforged.neoforge.server.threading`。本节接口需要完整 Forgia 编译依赖，不在轻量门面 jar 中。

### 7.1 RegionThreadingManager 的公开获取方法

```java
static Optional<RegionThreading> get();
static boolean isInstalled();
static void ifInstalled(Consumer<RegionThreading> action);
static RegionThreading getOrThrow();
```

`ifInstalled` 在**当前调用线程立即**调用 action，不会把 action 调度到安全线程。`getOrThrow` 在引擎未安装时抛 `IllegalStateException`。`currentOrNull`、`install`、`clear` 是 Internal，模组不得安装／卸载核心引擎。

### 7.2 RegionThreading 全部方法

```java
GlobalRegionScheduler globalScheduler();
RegionScheduler regionScheduler();
EntityRegionScheduler entityScheduler();
EntityInteractionScheduler entityInteractions(); // Experimental

boolean isOwnedByCurrentRegion(ServerLevel level, BlockPos pos);
boolean isOwnedByCurrentRegion(Entity entity);
default void assertOwnedByCurrentRegion(ServerLevel level, BlockPos pos);
default void assertOwnedByCurrentRegion(Entity entity);

default void executeOrSchedule(ServerLevel level, BlockPos pos, Runnable task);
default void executeOrSchedule(Entity entity, Runnable task);
RegionThreadingContext currentContext();
```

所有权查询只回答当前 owner，不证明加载权限、所有实体字段操作安全或整个世界静止。断言失败按实现的所有权策略处理，不能把 WARN 模式视为授权；原生存储访问限制仍可能抛出。

`executeOrSchedule` 当前已拥有时**同步调用**，否则交给对应调度器。它可能重入，也不返回结果句柄。只有排程分支才会延后，不能假设一定“下一 tick”。

### 7.3 RegionThreadingContext 全部成员

```java
Kind kind();
Optional<ServerLevel> level();
default boolean isTickThread();

enum Kind { NONE, GLOBAL_REGION, LEVEL, REGION, ASYNC }
```

`isTickThread()` 对 GLOBAL_REGION／LEVEL／REGION 为 true。这个值不是 BE 操作的提交许可，也不是跨区存储修改授权。上下文可以没有 level；保存 context 不会冻结其描述的世界或续期 lease。

## 8. 三个调度器与 ScheduledRegionTask

### 8.1 RegionScheduler

```java
void execute(ServerLevel level, BlockPos pos, Runnable task);
ScheduledRegionTask runDelayed(ServerLevel level, BlockPos pos,
        Runnable task, long delayTicks);
ScheduledRegionTask runAtFixedRate(ServerLevel level, BlockPos pos,
        Runnable task, long initialDelayTicks, long periodTicks);
```

按位置所属 region 路由，队列执行时由引擎建立相应上下文。位置任务不是实体跟随任务，也不等于 ticket-backed chunk 加载。

### 8.2 EntityRegionScheduler

```java
void execute(Entity entity, Runnable task);
ScheduledRegionTask runDelayed(Entity entity, Runnable task, long delayTicks);
ScheduledRegionTask runAtFixedRate(Entity entity, Runnable task,
        long initialDelayTicks, long periodTicks);
```

任务随实体的 owner 迁移，执行前再检查当前 owner。已移除实体提交时返回取消句柄（execute 无返回值）；后续移除、世界退休或引擎关闭会完成／取消对应任务。实体迁移会转移未执行任务和剩余延迟，不把旧 region 当成永久 owner。

### 8.3 GlobalRegionScheduler

```java
void execute(Runnable task);
ScheduledRegionTask runDelayed(Runnable task, long delayTicks);
ScheduledRegionTask runAtFixedRate(Runnable task,
        long initialDelayTicks, long periodTicks);
```

安排属于 global region 的工作。它不是“随意写所有 chunk”的主线程 executor：全局 worker 开关可改变物理执行线程，world／entity／chunk 存储仍遵守原有 guard。

### 8.4 ScheduledRegionTask 全部方法

```java
boolean cancel();
boolean isCancelled();
boolean isDone();
```

这是三个调度器共同的简单句柄，**不是** `EntityInteractionTask` 或 `BlockEntityOperations.Task`。没有 result、failure、Reason 或 future 等待接口。

- 延迟／初始延迟必须 `>= 0`，周期必须 `> 0`，否则抛 `IllegalArgumentException`。
- 时间以所属队列的逻辑 tick 为单位，不是毫秒；负载、drain 限额和生命周期可延迟执行，不保证墙钟时间或截止期。
- 周期从本次实际执行的当前逻辑 tick 加 period 排下次，不承诺补跑所有错过的周期。
- 引擎关闭后新调度可能抛 `IllegalStateException`；退休队列的 scheduled 句柄可被取消。execute 没有句柄，关闭世界时可丢弃任务，不能据提交返回推断已执行。
- 普通 scheduled `cancel()` 不会抢占已经运行的 Runnable；它可能标记取消并阻止后续执行，**没有完整操作句柄那种“成功即回调从未开始”的保证**。
- 回调异常在队列中记录；单次／周期任务失败后不自动重试，其他排队任务可继续。句柄不提供异常取回。
- 队列可由服务器线程或 worker drain，依据配置和实际准入；不要以线程名字识别所有权。拥有 region 上下文不等于拥有任意跨区实体／BE 操作许可。

### 8.5 调度示例

```java
RegionThreadingManager.get().ifPresent(threading -> {
    ScheduledRegionTask task = threading.regionScheduler().runDelayed(
            level, position.immutable(), () -> updateOwnedPosition(level, position), 20L);
    // 持有句柄可以取消；不要阻塞等待它执行。
});
```

示例中的 updateOwnedPosition 仍须遵守自己的访问范围，不能从一个位置任务内任意修改另一 owner。

## 9. EntityInteractionScheduler：完整实体交互

获取：`RegionThreadingManager.getOrThrow().entityInteractions()`。状态 Experimental，需要完整 Forgia 编译依赖。

### 9.1 全部方法

```java
EntityInteractionTask submit(Entity source, Entity target,
        BiConsumer<Entity, Entity> action);
EntityInteractionTask submitToOwner(Entity source, Entity owner,
        Consumer<Entity> action);
default EntityInteractionTask submitToOwnerAfterChunkLoad(
        Entity source, Entity owner, BlockPos position,
        BiConsumer<Entity, LevelChunk> action);
```

| 方法 | 验证与生命周期 |
|---|---|
| `submit` | source／target 都是执行参与者；执行前重验移除与原始世界身份 |
| `submitToOwner` | source 仅授权提交，不作为之后的存活条件；适用于弹射物提交后消耗自己，回调仅收到 live owner |
| `submitToOwnerAfterChunkLoad` | owner 操作需要 FULL chunk；静止服务器阶段取得临时 ticket，同步加载，再次验证 owner／生命周期，调用一次并在 finally 释放 ticket |

native 提交必须来自拥有 source 的 entity tick group；不是任意 region Runnable 或 BE worker。静止服务器线程立即执行；主线程服务活跃 worker 时拒绝；外部线程拒绝。实体交互共享每 entity 批次 4096 项的有界队列，取消不意味着可以无限重用受理名额。

提交必须发生在完整交互副作用之前。回调使用参数中的 live 实体及捕获的不可变输入，不把其他实体引用当成授权。accepted 不保证 completed、不返回同步伤害／传送结果，也不冻结其他 region 的全局 tick 顺序。回调异常按正常服务器／批次失败路径报告，不能重放已执行的效果。

`submitToOwner` 的源移除不取消已受理操作；source 丢弃仍是调用方自己的动作，REJECTED 不应被当成成功。不要捕获会被消耗的 projectile 本体或可变 HitResult。

加载后操作不是异步 chunk 系统或任意 Java continuation。加载可能阻塞服务器 tick；取消只在 RUNNING 之前有效，加载期间再次请求取消失败。位置复制为 immutable，ticket 唯一，失败仍释放，加载后验证失效则跳过回调；不得将 live chunk／ticket 带出作为后续权限。该 default 方法在不支持的实现上可抛 `UnsupportedOperationException`。

### 9.2 EntityInteractionTask 全部成员

```java
State state();
Reason reason();
boolean cancel();
Optional<Throwable> failure();
default boolean isDone();
```

State 完整值同名但属于**独立枚举类型**：

```text
QUEUED, RUNNING, COMPLETED, SKIPPED, CANCELLED, REJECTED, FAILED
```

Reason 完整值：

```text
NONE, CANCELLED, ENTITY_REMOVED, LEVEL_CHANGED, LEVEL_CLOSED, SHUTDOWN,
BATCH_CLOSED, QUEUE_FULL, INVALID_CONTEXT, UNOWNED_SOURCE
```

成功取消保证尚未开始且以后不执行；失败可由 failure 读取，也会走正常失败报告。此句柄没有泛型 result；不能把它当成 `BlockEntityOperations.Task<R>`。

```java
EntityInteractionTask request = threading.entityInteractions().submitToOwner(
        projectile, owner, liveOwner -> applyCompleteImpact(liveOwner, immutableImpact));
if (request.state() == EntityInteractionTask.State.REJECTED) {
    handleRejectionBeforeConsumingProjectile(request.reason());
}
```

更多加载／ticket 细节见 [CHUNK-OWNER-OPERATIONS.md](CHUNK-OWNER-OPERATIONS.md)。

## 10. 兼容性声明与配置接口

### 10.1 RegionThreadingCompatibility 与 RegionThreadingSupport

```java
// net.neoforged.neoforge.server.threading.RegionThreadingCompatibility
static RegionThreadingSupport defaultSupport(); // Experimental
static RegionThreadingSupport supportForMod(String modId); // Experimental

// 同包
public enum RegionThreadingSupport { UNKNOWN, UNSUPPORTED, SUPPORTED, REQUIRED }
```

`supportForMod` 读取该 mod 的 `regionThreading` 元数据，大小写不敏感；缺失／无效时返回 configured default。支持等级是声明，不是运行期并发证明。UNSUPPORTED 可阻止安装 bridge；REQUIRED 在 bridge 关闭时当前产生诊断警告，不能理解为自动开启引擎。

```toml
[[mods]]
modId = "example"
# 与 modId 同一 metadata 节点：
regionThreading = "SUPPORTED"
regionThreadingDeclarations = "example-region-threading.json"
```

不要用 SUPPORTED 替代某个具体机器、事件或网络操作的审计。兼容表可能单独关闭 engine 或某些 slice；claim 只能替代明确覆盖的自然刷怪限制。

### 10.2 JSON 声明合同

两个读取入口：

- 实例的 `config/regionthreading-declarations.json`。
- 各 mod metadata 的 `regionThreadingDeclarations` 指定的 jar 内资源。

```json
{
  "entities": [
    {"type": "example:mob", "reason": "global world callback"}
  ],
  "block_entities": [
    {"type": "example:legacy_machine", "reason": "unadapted shared network"}
  ],
  "admit_block_entities": [
    {"type": "example:adapted_machine", "reason": "audited local tick and complete cross-region operations"}
  ]
}
```

三项均可省略。条目需要 registry type 和非空 reason。`entities`／`block_entities` 收窄到服务器线程，`admit_block_entities` 是显式承诺放行 BE 类型，仍受内部 chunk 等现有规则限制。服务器线程声明优先于同类型 admit；模组自定义 type 不继承原版炉子的边界准入。

声明在服务器启动时读入；当前不能靠运行时更改文件使所有缓存原子热更新。未知类型、无效条目／文件有诊断并被忽略，不能把它们当成生效。

`RegionThreadingDeclarations` 的解析、`Report.applyTo`、`RegionTickCompatibility` setter 和 Engine 类型注册方法是 Internal。对模组公开的这一项是**文件／metadata 格式**，不是可随时修改内部准入缓存的公共注册器。

### 10.3 ForgiaConfig

完整类：`net.neoforged.neoforge.common.ForgiaConfig`，需要完整 Forgia 编译依赖。

```java
public static final String FILE_NAME = "forgia.toml";
public static final ModConfigSpec SERVER_SPEC;
public static final ForgiaConfig.Server SERVER;
public static boolean isServerConfigLoaded();
```

字段是 NeoForge `ModConfigSpec` 值包装，加载后用 `.get()` 读取；先用 `isServerConfigLoaded()` 判断。读取配置不是引擎实际准入状态，也不授予修改权限。配置更改、尤其 worldRestart 项，应按服务器启动／配置生命周期处理，不能通过调用 `.set()` 期待已构建引擎即时重配。

默认读取 `config/forgia.toml`，世界 `serverconfig/forgia.toml` 按 SERVER 配置规则覆盖。它与上游 `neoforge-server.toml` 隔离；迁移规则见 README。

`ForgiaConfig.Server` 全部公开配置字段：

| 字段 | 包装类型 | 默认值／范围 |
|---|---|---|
| `enableRegionThreadingBridge` | BooleanValue | false |
| `enableRegionThreadingWorkers` | BooleanValue | false |
| `enableGlobalRegionThreadingWorker` | BooleanValue | false |
| `enableRegionThreadingEntityTicks` | BooleanValue | false |
| `enableRegionThreadingBlockEntityTicks` | BooleanValue | false |
| `enableRegionThreadingFurnaceWorkWaves` | BooleanValue | false；原版燃烧熔炉细粒度波次实验，需重启；见 [范围与限制](FURNACE-WORK-WAVES.md) |
| `enableRegionThreadingBlockEntityResources` | BooleanValue | false；Furnace 波次使用通用对象资源 claims/lease/scope，需重启并满足旧波次开关；见 [内部资源协议](GENERAL-BLOCK-ENTITY-RESOURCE-OWNERSHIP.md) |
| `enableRegionThreadingScheduledTicks` | BooleanValue | false |
| `enableRegionThreadingRandomTicks` | BooleanValue | false |
| `enableRegionThreadingSpawning` | BooleanValue | false |
| `enableRegionThreadingPoi` | BooleanValue | false |
| `enableRegionThreadingBorderUpdates` | BooleanValue | false |
| `regionThreadingForceWithKnownConflicts` | BooleanValue | false；实验绕过已知冲突的测试用途，不证明安全 |
| `enableRegionThreadingGameEvents` | BooleanValue | false |
| `enableRegionThreadingPersistentWorkers` | BooleanValue | false；持久 worker＋批量 Work-Stealing Dispatcher，需重启；见 [派发与计数](WORK-STEALING-DISPATCHER.md) |
| `enableRegionThreadingPackedRegionLeases` | BooleanValue | false |
| `regionThreadingWorkerThreads` | IntValue | 0，范围 0..512；0 为 max(1, CPU 数量−1) |
| `regionThreadingMode` | EnumValue<RegionPolicy> | FIXED_3X3；另有 DYNAMIC |
| `regionThreadingFixedRegionChunks` | IntValue | 3，范围 3..32 |
| `regionThreadingOwnershipViolationMode` | EnumValue<NeoForgeConfig.RegionThreadingOwnershipViolationMode> | WARN；另有 DISABLED、THROW |
| `defaultRegionThreadingSupport` | EnumValue<RegionThreadingSupport> | UNKNOWN |
| `regionThreadingLongTickWarningNanos` | LongValue | 0，范围 0..Long.MAX_VALUE；0 关闭告警 |
| `regionThreadingMaxImmediateTasksPerDrain` | IntValue | 10000，范围 0..Integer.MAX_VALUE；0 不限数量 |
| `regionThreadingMaxScheduledTasksPerDrain` | IntValue | 10000，范围 0..Integer.MAX_VALUE；0 不限数量 |

`RegionPolicy` 是配置值类型，位于内部实现包；不要因为配置暴露了此枚举而依赖 region 包其他实现。DYNAMIC 不表示所有现代 region-owned slice 都支持动态布局；具体以实际准入／诊断为准。

## 11. 示例、观测命令与验证资料

| 入口／资料 | 作用 |
|---|---|
| `/neoforge regionthreading status` | 可读的 slice、worker、fallback、health 和 region 统计 |
| `/neoforge regionthreading status raw` | 机器可读详细诊断 |
| `/neoforge regionthreading compat` | 兼容声明、适配注册、完整 BE 操作结果、scheduled 阶段原因与延迟 |
| `/neoforge regionthreading workgraph [start <phases>\|stop]` | 有界历史访问观察图、实际熔炉波次正文计数与独立通用资源签发／耗时指标；`.58` 增加 fresh revalidation 和完整实验 BE phase 计时，见 [计时边界](BLOCK-ENTITY-RESOURCE-OWNERSHIP-OPTIMIZATION.md#6-阶段计时的覆盖范围)；历史观察不给准入权限 |
| `/neoforge regionthreading regions [count]` | 按慢 region 列出指标 |
| [ContainerLinkExample.java](../examples/parallel-api/ContainerLinkExample.java) | 只依赖公开 facade 的完整 BE 源／目标链接示例 |
| [模组刷怪 API 指南](MOD-PARALLEL-API.md) | 注册、快照、claim、有序最终化示例 |
| [跨 region 操作指南](CROSS-REGION-OPERATIONS.md) | 线程／身份／结果／迁移与 scheduled 语义 |
| [BE 并行说明](BLOCK-ENTITY-PARALLELISM.md) | 炉子边界准入、保守 fallback 与模组适配边界 |
| [完整实体 owner 加载操作](CHUNK-OWNER-OPERATIONS.md) | 无重放原则、同步 ticket 加载与清理 |
| [实现验证记录](OWNERSHIP-PROTOCOLS-VALIDATION.md) | 单元／GameTest／补丁／facade 编译证据和已知限制 |

这些命令当前属于管理员运维入口，通常需要权限等级 2。计数多为累计值，使用窗口差值；不要把请求、chunk 访问、ticker 调用和回调耗时混作一项。

worker dispatch 行新增 `dispatchBatches`、`batchedTasks`、`batchRunners`、`batchSteals`、`batchStolenTasks`。
`taskSubmissions` 是线程池实际接受的 runner／独立任务数量；`batchedTasks` 是这些 runner 处理的原始不可拆任务数量。
`batchSteals` 是成功的区段转移次数，`batchStolenTasks` 是转移的任务索引数，同一未开始任务可能再次转移。
它们不等于完成的原版正文数；实际熔炉正文仍读取 `workgraph` 命令的 `nativeCompleted`、`failed`、`skipped` 等指标。

## 12. 内部边界与未提供的能力

以下类型虽为 public，供 patch 跨包调用或核心实现协作，**不是模组公开 API 合同**：

| 类型／范围 | 职责／推荐替代 |
|---|---|
| `RegionThreadingHooks` | public patch 桥接入口；改用公开调度器／ParallelApi，不直接调用 guard、handoff、mutation queue |
| `ParallelAdapterRuntime` | 注册、准备快照、服务生命周期实现；改用 `RegisterParallelAdaptersEvent` 和 `ParallelApi` |
| `BlockEntityOperationRuntime` | Reference／Request／容器操作实现；改用 `BlockEntityOperations` |
| `RegionThreadingDeclarations` | 文件解析／应用实现；使用声明文件格式 |
| `RegionThreadingConflicts`／`RegionThreadingCompatReport` | 核心兼容策略和诊断实现；使用 compat 命令与公开状态服务 |
| `RegionAwareRandomSource` | 解析组内 random 的包装；不要构造它作为 worker 安全声明 |
| `NearbySpawnReservations` | 特定刷怪准备／候选 admission 的内部协作对象 |
| `ServerEntityStorageOwner` | 保持原版构造形状的内部 owner 桥，不是模组 storage owner 注册器 |
| `BlockEntityResourceOwnership`／`FurnaceResourcePreparation` | 内部 current-operation claims／lease／scope 签发协议；不提供模组自行声明后取得 worker 权限的公开 API |
| `server.threading.region.*` | Engine、state、lease、batch、mailbox、POI、日志、metrics、tick plan、测试开关和 failure carrier |
| Minecraft patch 新增的 chunk/ticker／存储方法 | 承载内部协议和测试，不自动成为长期模组 API |
| Manager 的 `currentOrNull`／`install`／`clear` | 核心生命周期方法，模组不安装／卸载引擎 |

本文没有将内部 Hooks 的 public 方法逐个包装成“受支持接口”。需要某个缺失能力时应新增明确的公开协议，而不是让模组依赖 ThreadLocal、队列、lease 或 `enable...ForTesting()`。

当前没有提供：通用任意 scheduled 回调注册／安全证明、任意跨区直接读取可变容器、并行多 owner 事务 lease、跨维度 BE 操作、任意 Java 回调回滚／透明续跑、通用异步 chunk 加载／传送、公开结构快照服务，以及稳定的独立 API Maven 发布坐标。现有 owner 加载操作是静止服务器上的同步加载，现有双 BE 操作是完成阶段串行，名称不应掩盖这些执行边界。

## 13. 公开类型源码索引

下表列出本文覆盖的全部顶层 Java API 类型。嵌套接口、record 和枚举见各节；record 还自动拥有标准 `equals/hashCode/toString` 和同名组件访问器，本文不重复列出 Object 方法。

| 顶层类型 | 源码 |
|---|---|
| ParallelApi | [ParallelApi.java](../src/main/java/net/neoforged/neoforge/server/threading/api/ParallelApi.java) |
| BlockEntityTickAdapter | [BlockEntityTickAdapter.java](../src/main/java/net/neoforged/neoforge/server/threading/api/BlockEntityTickAdapter.java) |
| BlockEntityOperations | [BlockEntityOperations.java](../src/main/java/net/neoforged/neoforge/server/threading/api/BlockEntityOperations.java) |
| ScheduledTickStatus | [ScheduledTickStatus.java](../src/main/java/net/neoforged/neoforge/server/threading/api/ScheduledTickStatus.java) |
| RegisterParallelAdaptersEvent | [RegisterParallelAdaptersEvent.java](../src/main/java/net/neoforged/neoforge/event/server/RegisterParallelAdaptersEvent.java) |
| RegionThreadingManager | [RegionThreadingManager.java](../src/main/java/net/neoforged/neoforge/server/threading/RegionThreadingManager.java) |
| RegionThreading | [RegionThreading.java](../src/main/java/net/neoforged/neoforge/server/threading/RegionThreading.java) |
| RegionThreadingContext | [RegionThreadingContext.java](../src/main/java/net/neoforged/neoforge/server/threading/RegionThreadingContext.java) |
| RegionScheduler | [RegionScheduler.java](../src/main/java/net/neoforged/neoforge/server/threading/RegionScheduler.java) |
| EntityRegionScheduler | [EntityRegionScheduler.java](../src/main/java/net/neoforged/neoforge/server/threading/EntityRegionScheduler.java) |
| GlobalRegionScheduler | [GlobalRegionScheduler.java](../src/main/java/net/neoforged/neoforge/server/threading/GlobalRegionScheduler.java) |
| ScheduledRegionTask | [ScheduledRegionTask.java](../src/main/java/net/neoforged/neoforge/server/threading/ScheduledRegionTask.java) |
| EntityInteractionScheduler | [EntityInteractionScheduler.java](../src/main/java/net/neoforged/neoforge/server/threading/EntityInteractionScheduler.java) |
| EntityInteractionTask | [EntityInteractionTask.java](../src/main/java/net/neoforged/neoforge/server/threading/EntityInteractionTask.java) |
| RegionThreadingCompatibility | [RegionThreadingCompatibility.java](../src/main/java/net/neoforged/neoforge/server/threading/RegionThreadingCompatibility.java) |
| RegionThreadingSupport | [RegionThreadingSupport.java](../src/main/java/net/neoforged/neoforge/server/threading/RegionThreadingSupport.java) |
| ForgiaConfig | [ForgiaConfig.java](../src/main/java/net/neoforged/neoforge/common/ForgiaConfig.java) |
