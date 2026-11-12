# Forgia MobSpawnController Adapter

版本：`0.3.0`，实验性，限定 Minecraft 1.21.1 + MobSpawnController **1.2.3**。
需要本源码的 Forgia 核心（包含 ordered spawn finalization 协议）；不能单独装到原版 NeoForge 或缺少该协议的旧 Forgia。

> **默认不接管刷怪。** 本地探索性 A/B 记录有序模式中位 mspt **39.3**、串行 **17.8**；但有序轮次与 Gradle 测试重叠，这个比值不能当作受控性能结论。更关键的是，有序模式出现 **5294 次刷怪 body 截断、4437 次外部区块读拒绝**，未通过完整行为验收。没有证据证明它能加速当前配置。
> 因此默认保留 `DISABLE_SPAWNING`，自然刷怪保持完整串行。`-Dforgia.mscOrderedSpawning=true` 仅供实验，不是推荐配置。完整证据和测量限制见 [本地结果](../../region-threading/MOBSPAWNCONTROLLER-LOCAL-RESULTS.md)。

## 实测结论（必须先读）

有序最终化的原监听器、加入取消时序和规则传递已通过集成测试；整包测试仍发现大量截断，不能称为行为等价或可上线：

| 指标（60 秒，同世界同规则） | 有序并行 | 串行对照 |
|---|---|---|
| 中位 mspt | 39.3 | 17.8 |
| 自然生成实体 | 210 | 283 |
| 截断刷怪区块 | 5294 | 0 |
| 所有权违规 | 0 | 0 |

停点、主线程查询和任务调度都有成本；本轮还受并发测试占用 CPU 的影响，尚不能定量归因。截断已经足以阻止默认启用。

当前配置继续使用完整串行。保留实验实现用于进一步研究，不宣称已为其他配置证明性能收益。

## 实际并行范围

| 配置/操作 | 行为 |
|---|---|
| 没有 natural-settings 条目 | 冻结生成开关和属性映射，沿用原有 worker 路径 |
| natural 设置仅含 `chance`、`max_nearby`、`nearby_radius`、`spawn_types`、`excluded_spawn_types` | 仅在显式 `-Dforgia.mscOrderedSpawning=true` 时生效：候选选择与位置检查在 worker，原规则评估、finalize、真实加入世界及后续数量回调在安全停点的主线程执行。默认仍走串行 |
| 结构、光照、高度、季节等其他 natural 条件 | 在核心 cap 构造前保留完整原生串行计划 |
| `spawn_multiplier != 1` | 保留完整原生串行计划，继续原权重与 cap 协议 |
| 未验证的 NaturalSpawner Mixin | natural 设置保留串行，原因为 `unverified_spawn_hooks` |
| prepared body 之外的非自然生成 | 原模组路径和实时规则 |
| 配置热改 | 已准入计划固定原 revision；后续计划读取新 revision |
| 空结构选择列表 | 直接返回 false，避免无意义的世界查询；非空列表仍走原方法 |
| 异常/嵌套生成 | 恢复原模组的 boost ThreadLocal，不遗留到下一个任务 |

这不是“整个模组都在 worker 上跑”。有附近数量限制时，必须把最终规则判断和真实加入结果放在同一个有序操作里。主线程执行该操作期间，其他刷怪 worker 都停在安全点；因此不会有两个候选同时误抢最后一个名额，也不会把最终被取消的生成当作已经占用名额。

源 chunk 不限定候选位置。附近计数仍调用原模组的完整三维 AABB 查询，使用真实服务器实体视图，不用 region 局部计数、静态空快照或近似距离替代。自然 tick 同步产生的 JOCKEY 等次级生成也沿用同一规则 revision；未加入世界的父实体不会被一个投机名额错误计入子实体的邻域。

本地整合包的 25 段 natural 设置均属于上述附近数量/概率子集。配置形状满足支持条件不等于整合包验证通过：引擎开关、其他 Mixin、实际 worker/最终化执行和本地负载测试都必须分别检查。

## 接入与时序

- 原 FinalizeSpawn 监听器保留在原 EventBus 位置；没有复制原 evaluator，也没有替换或重发事件。
- `RULES`、natural settings 和属性映射固定到计划级不可变视图；`NATURAL_SPAWN_SETTINGS` 的数据来源被重定向，规则逻辑仍由原模组执行。
- 属性覆盖仍由原监听器在原位置执行一次；取消生成时保留原行为与 RNG 消耗。
- natural 设置必须通过完整字段检查：除上表允许的字段外，其余 record 字段必须等于上游默认值。未知条件、非有限数值、非默认倍率都回退。
- core 在原生选择循环设置协作停点，按真实开始的 worker 组登记参与方；未提交/未开始的组不占屏障名额。
- 所有参与 worker 停下后，主线程执行完整的 finalize、passenger admission、afterSpawn 和 cluster/group-limit 回调；worker 只收到组数据和停止标志，不拿已入库实体继续执行回调。
- 最终化前发布选择回调此前延期的实体、方块及 tick 效果，避免规则判断漏掉已经发生的修改。操作已经产生副作用时不重放。
- 同一 unit 的随机数源和 prepared 数据传到主线程续跑；只传递已准备的数据，不在 `SERVER_SERVICE` 阶段重新准备。
- `NaturalSpawnBoost.ACTIVE_LEVEL` 在 worker 和主线程续跑中分别保存/恢复，包含异常和嵌套路径。支持的配置倍率为 1，不访问旧版 multiplier 缓存。
- 线程池拒绝组、普通主线程请求、关闭屏障和生成异常都有独立处理；主线程等待安全停点时继续服务原有请求。

默认非 ordered 路径的原 `finalizeSpawn` 调用点保留。ordered 分支移动了完整候选续跑，因此还检查实际合并到 NaturalSpawner 的 Mixin 来源。当前额外审查范围为 ByePregen **1.1.2.1**（chunk cache/最近玩家选择）与 YUNG Better Nether Fortresses **1.21.1-NeoForge-3.1.5**（要塞候选选择），这些选择调用点保留。未知来源/版本触发串行回退。此检查不认证任意 ASM/coremod 或任意第三方事件监听器。

## 兼容与诊断

注册 preparation 不会自动放行。适配器还需通过启动标记验证并显式 `claimNaturalSpawning`。核心仅替换该 mod 的 `DISABLE_SPAWNING`；不会打开管理员关闭的刷怪开关，也不会解除 C2ME 等 `DISABLE_ENGINE` 限制。

`/neoforge regionthreading compat` 显示：

- adapter `ready`、`serial`、`serialPlans`、准备耗时及最近回退原因；这些是准备次数，不是成功生成的实体数。
- `Ordered spawn finalization: transactions=..., serverMs=...`：实际续跑次数（包含拒绝/取消），以及续跑正文的主线程耗时。这个耗时不包含全部等待、调度和延期效果发布成本；性能比较应使用实际 tick 时间。

世界条件不支持时，原因是 `forgia_mobspawncontroller:world_dependent_rules`；未审查的原生注入为 `forgia_mobspawncontroller:unverified_spawn_hooks`。刷怪 worker 开关打开、但 `serialPlans` 持续增加且实际 worker 次数为零，仍是串行回退。

完整机器输出继续使用 `/neoforge regionthreading status raw`。

## 构建与真实 jar 测试

从仓库根目录执行：

```sh
./gradlew :mobspawncontroller-adapter:jar :neoforge:parallelApiJar :neoforge:installerJar \
  -PforgiaVersion=21.1.250.48-beta --offline

./gradlew :mobspawncontroller-adapter:junitTest \
  -PforgiaVersion=21.1.250.48-beta \
  -PmobSpawnControllerJar=/absolute/path/mobspawncontroller-neoforge-1.21.1-1.2.3.jar --offline
```

适配器产物：`adapters/mobspawncontroller/build/libs/forgia-mobspawncontroller-adapter-0.3.0.jar`。

集成测试加载真实 1.2.3 jar、真实变换后的方法与原监听器。覆盖两个真实 region 组的共享名额、真实 finalize 事件、取消后的下一候选、禁止超刷、规则热改、相同随机数源、主线程上下文清理、属性只执行一次，以及不支持配置在 cap 构造前回退。实体存储/加入取消、屏障异常和线程池拒绝还由核心测试独立覆盖。

负对照移除 adapter mod，仅运行空结构列表测试，预期恰好两个空列表用例失败，其余四个通过：

```sh
./gradlew :mobspawncontroller-adapter:junitTest \
  -PforgiaVersion=21.1.250.48-beta \
  -PmobSpawnControllerJar=/absolute/path/mobspawncontroller-neoforge-1.21.1-1.2.3.jar \
  -PmscAdapterNegativeControl=true --offline
```

另一个独立 JVM 对照通过 `-PmscUnverifiedHookControl=true` 注入未审查的测试 Mixin，验证即使附近数量配置受支持，也必须在计划开始前回退，不能取得 worker 准入：

```sh
./gradlew :mobspawncontroller-adapter:junitTest \
  -PforgiaVersion=21.1.250.48-beta \
  -PmobSpawnControllerJar=/absolute/path/mobspawncontroller-neoforge-1.21.1-1.2.3.jar \
  -PmscUnverifiedHookControl=true --offline
```

未提供原 jar 时集成任务明确跳过。原 jar、反编译实现、API 副本和测试类都不进入发布 jar；生产核心提供 MixinExtras。

## 验证边界

[准入与调度协议](../../region-threading/NEARBY-SPAWN-ADMISSION.md) 记录实现及验证范围；[审查报告](../../region-threading/MOBSPAWNCONTROLLER-ADAPTER-AUDIT.md) 记录原模组风险，[API 指南](../../region-threading/MOD-PARALLEL-API.md) 记录公开协议。

结构过滤、其他世界条件和倍率并行协议未实现，继续完整串行。安全停点的跨组事务按提交顺序执行，不保证与原版全局候选顺序/RNG 序列逐位相同。实际性能必须按具体负载测量；单元测试与本地假玩家测试不等于多人长跑、所有模组机制或整体性能领先已验收。
