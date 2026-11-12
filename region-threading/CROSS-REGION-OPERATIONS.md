# 跨 region 方块实体操作与 scheduled tick

状态：Experimental，随 `ParallelApi` major 1 提供。已实现的协议包括完整双 owner 操作、容器槽位搬运，以及受约束的原版红石灯 scheduled tick 并行。接口和具体 tick 准入是不同能力：取得服务不会自动让某个模组的机器 tick 变成并行。

## BlockEntityOperations

从服务器实例服务获取：

```java
ParallelApi.Server api = ParallelApi.forServer(server).orElseThrow();
if (!api.capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_OPERATIONS)) {
    // 保留模组自己原来的串行调度。
    return;
}
BlockEntityOperations operations = api.blockEntities();
```

编译使用 `:neoforge:parallelApiJar`，依赖仍为 `compileOnly`，不要打包第二份 API。普通 NeoForge 的可选加载规则与[接入指南](MOD-PARALLEL-API.md)一致。Forgia 关闭 region 引擎时服务仍存在：静止的服务器线程上立即执行，其他线程返回明确拒绝，不把操作送入公共异步线程池。

引用是带实例身份的、不透明的 `Reference`，包含维度、不可变位置和类型 id，内部只弱引用世界与方块实体。它不暴露 live BE、chunk 或 lease，也不能自行构造出有效权限。主线程静止时准备远端引用；当前机器的原生 worker ticker 可以取得自己的源引用。不同机器、相同位置的替换实例和未准入的 worker 都不能借用源 ticker 的权限。

```java
// 主线程静止阶段：建立目标链接。邻区必须已经加载；此调用不加载 chunk。
BlockEntityOperations.Reference target = operations.reference(targetMachine);
// 将这个不透明引用作为输入发布给源机器；以后目标替换时需要重新建立链接。

// 源机器已准入的 ticker 中，在此次搬运的任何副作用之前提交：
BlockEntityOperations.Task<BlockEntityOperations.TransferResult> task =
        operations.transfer(operations.reference(sourceMachine), target,
                new BlockEntityOperations.Transfer(sourceSlot, targetSlot, 8,
                        Direction.DOWN, Direction.UP));
```

方向分别是源容器的抽取面和目标容器的插入面。`WorldlyContainer` 必须指定方向，并检查实际暴露的槽位及面规则。普通 `Container` 可传 `null`。槽位非负，单项请求上限为 1..64 个物品；目标只能容纳一部分时只抽取该部分。`TransferResult` 返回实际 `moved` 数量及 `MOVED`、`NO_ITEMS`、`DESTINATION_FULL`、`ITEM_REJECTED`、`INVALID_SLOT`、`SIDE_REQUIRED`、`UNSUPPORTED_CONTAINER` 或 `SAME_CONTAINER`。零搬运是已完成的业务结果，不等于调度失败。

该内建操作不遍历管网、不自动合并双箱，也不适配任意 `IItemHandler`。传入的是两个被引用的 BE 自身实现的 Container 槽位。它先完成兼容性、容量和方向判断，再调用实际 remove/set，并通知双方 setChanged。自定义 Container 的方法仍必须遵守自身合同；如果它抛异常、重入修改或提取结果不一致，操作进入失败，不能承诺回滚任意模组副作用。内建接口对原版容器和遵守合同的实现验证物品守恒。

模组可以提交自己的完整操作及脱离世界的结果：

```java
record ExchangeResult(int moved) {}

BlockEntityOperations.Task<ExchangeResult> task = operations.submit(sourceRef, targetRef,
        (liveSource, liveTarget) -> {
            // 在这个上下文中完成双方协商、修改和对应同步回调。
            // 仅操作传入的参与者；捕获的输入必须不可变，不捕获其他 live 世界对象。
            return new ExchangeResult(performCompleteExchange(liveSource, liveTarget));
        });
```

结果必须是脱离世界的不可变数据。Java 泛型不能证明这一点；保留 live BE、可变 NBT、capability 或 chunk 作为结果不延长未来修改权限。

## 调度、生命周期与失败

| 调用点 | 行为 |
|---|---|
| 当前 BE 的原生已准入 ticker | 向本 BE 批次提交，不阻塞 worker；最多 4096 项请求，取消不返还该批次的受理名额 |
| 所有 worker 已返回的服务器阶段 | 立即执行，包括引擎关闭的串行模式 |
| 主线程正在为活跃 worker 做服务 | `REJECTED / INVALID_CONTEXT` |
| 其他机器的 ticker、无对应绑定的 worker | `REJECTED / UNOWNED_SOURCE` 或 `INVALID_CONTEXT` |
| 网络、异步计算等外部线程 | `REJECTED / INVALID_CONTEXT` |

一个 BE 批次的执行顺序是：worker 结束 → 边界传播 → 发布延迟 block/light 效果 → 实体移除 → 若有完整操作，先发布 worker 已排队的 scheduled mutations → 完整操作 → 原有串行 fallback → 剩余 scheduled mutations → 批次结束。完整操作运行时，所有 worker 已返回；它没有获得多个 region 的并行 lease。当前跨区操作在这个完成阶段串行化，独立机器的原有 tick 仍按 region 并行。没有 worker `join/get` 或隐藏的同步跨区等待接口。

句柄状态为 `QUEUED → RUNNING → COMPLETED / SKIPPED / FAILED`，排队时还能变成 `CANCELLED`；未受理是 `REJECTED`。只有 `COMPLETED` 的非 null 返回值出现在 `result()`；空 result 不能单独表示未完成，必须读取 state。`cancel()` 成功保证回调未开始且以后不会调用，RUNNING 后取消失败。没有注册任意线程上的完成回调；在自己下一次安全的 tick 中检查句柄并处理脱离世界的结果。

执行前验证双方原始世界身份、不可变位置、类型、实例和已加载 chunk，目标替换不会自动转给新机器。卸载、关闭、源/目标移除、世界变化、队列满、上下文无权均有独立 Reason。引用不自动加载 chunk。跨维度的双 owner 操作暂不受理。

操作至多调用一次。异常不会变成成功，也不重新执行已经产生效果的搬运。worker 提交的回调异常在服务器完成阶段报告，其他已受理请求继续排空；立即执行的回调异常向当前服务器调用者抛出。最终句柄清除源、目标和操作引用，失败句柄保留异常，成功句柄只保留返回值。

这不是数据库事务，也不会自动恢复任意 Java 调用栈。管理员强制串行声明仍优先；未知机器仍需审计本地 tick、使用显式类型准入，并把所有跨区效果在发生前改造成完整操作。只在捕获一次越区异常后提交整个 tick 仍然无效。

## Scheduled tick 的生产准入

开关仍为默认关闭的 `enableRegionThreadingScheduledTicks`，需要 region 引擎。生产仅支持**完整 block tick 阶段全部为原版红石灯**，并在任何回调之前检查：

- 固定 region 布局，服务器 profiler 未激活。
- 每个回调位置的半径 2 方块立方体全部来自已实际加载的 chunk，且位于同一 region。
- 范围内只有原版空气、洞穴空气、虚空空气、石头、玻璃、红石块和红石灯；这些方块不会从这条调用路径扩展任意形状级联或访问实体。
- 完整阶段能放进延迟更新预算。

半径包括信号读取穿过导体再读直接信号的位置，以及 flags=2 写入触发的形状更新。灯只切换自己的 LIT，不消费随机数；生产组的派生 random 不推进共享世界 RNG。每个 region 内保持 LevelTicks 收集顺序，互不相交的 region 并行。该限定足以验证安全路径，但不代表灯和任意红石电路组合都能并行。

任一未知回调、流体阶段、越区范围、未加载范围、不安全邻居、profiler 或预算不足，整个阶段保留原来 live LevelTicks 串行队列。priority、subTickOrder、clearArea、copyArea 和 willTickThisTick 继续采用原路径；不先抽空队列再模拟串行。vanilla 在 clearArea 后可能仍从其延迟构造的 predicate set 回答 true，测试也保留这种细节，而不是重新定义该方法。

worker 回调一旦开始，任何异常，包括不稳定 chunk 读取，都进入正常失败报告；不静默截断成成功，不重试。失败组中未开始的余项单独记为 skipped。测试专用的 unclassified 开关继续仅用于内部机械验证，不是模组准入接口。

## 观测

`/neoforge regionthreading compat` 和 `api.blockEntities().counters()` 提供完整操作累计数：accepted、completed、skipped、cancelled、rejected、failed、回调 wall time 和枚举化 reason。既有 block entity attribution 将完整操作完成阶段单独计时，避免混进实体移除耗时。

`api.scheduledTicks()` 和 compat 按 block/fluid 两个阶段提供：

| 指标 | 单位 |
|---|---|
| phases / serialPhases / serialReasons | 阶段数；拒绝原因为有界枚举 |
| collected / admitted | 被选回调数 / 准入回调数，均非已完成数 |
| workerCompletions / serialCompletions | 正常返回的回调调用数；线程池拒绝后 inline 不算 native completion |
| failedCallbacks / skippedCallbacks | 失败回调 / 未调用余项，含串行 clearArea 移除 |
| preparation / worker / dispatchAwait / completion / phase | 累计纳秒；worker 为各线程回调时间之和，phase 为整阶段 wall time |
| p50/p95/p99 | 最近 128 个非空阶段的精确延迟分位数，包含串行拒绝阶段，起点在 collection 之后 |

使用两次快照的差值计算速率。不能把累计请求数当作唯一机器数，也不能把较高 worker 比例当作吞吐提升。只按固定阶段/枚举分类，内存和标签数不随方块位置或类型数量增长。关闭 scheduled 开关时保持原路径，不为了统计遍历 scheduler。

## 验证范围

单元测试使用真实 worker、真实 bound ticker、公开 API、原版容器和真实 LevelTicks；覆盖跨区完成、源身份替换、目标替换、缺 chunk、方向槽位、部分插入、取消、队列上限、服务关闭、异步/服务上下文拒绝、异常不重放和后续请求排空。scheduled 测试对比实际灯的逐 tick 状态、待执行 tick 和共享 RNG，并检查真实并发、混合阶段顺序、clearArea、未加载/越区/邻居/预算拒绝及失败计数。

新增真实服务器 GameTest 包括显式声明的模组源通过 facade 搬运、生产 scheduled 灯回调，以及上一阶段的边界熔炉＋比较器。最终验证：1085 项 JUnit 全部通过，默认关闭状态的新世界 173/173 required GameTest 通过，并行开启的三个专项 3/3 通过；758 个补丁重新应用、修改补丁往返和公开 facade 独立编译检查通过。首次失败、夹具修正及既有辅助诊断的说明见[验证记录](OWNERSHIP-PROTOCOLS-VALIDATION.md)。这些行为验证不等于任意整合包长测或性能提升。
