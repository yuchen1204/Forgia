# Forgia MobSpawnController Adapter

版本：`0.2.0`，实验性，限定 Minecraft 1.21.1 + MobSpawnController **1.2.3**。
必须与本源码构建的 Forgia 核心配套使用（包含 Parallel API 的 natural-spawn claims 能力），不能将 0.2.0 单独安装到旧核心或原版 NeoForge。

## 支持范围

| 配置/操作 | 行为 |
|---|---|
| 没有 natural-settings 条目 | 冻结生成开关和属性映射，可在 region worker 执行 |
| 结构、附近数量、光照、高度、季节等 natural 设置 | 在核心 cap 构造前保留完整原生串行刷怪计划，完整执行原规则 |
| `spawn_multiplier` | 也是 natural 设置，当前保守回退，保留原权重和 cap 行为 |
| prepared body 之外的非自然生成 | 继续原模组路径和实时规则 |
| 配置热改 | 已准入工作继续使用原 revision，之后的新计划读取新 revision |
| 空结构选择列表 | 直接返回 false，避免无意义的世界查询；非空列表继续原方法 |
| 异常/嵌套刷怪 | 恢复原模组的 boost ThreadLocal 上下文，不残留到下一任务 |

这是正确性优先的分级适配，不是“所有配置都能加速”。例如本地包的 25 段 natural 配置都设置了 `max_nearby`，因此这些配置保持不变时，准备阶段仍会要求串行。不会用 region-local 的少计结果或静态快照绕过附近数量限制。

源 chunk 的同步回调可能生成 JOCKEY 等次级实体，因此 prepared body 内的这些操作也读取该计划同一 revision；仅适用于次级原因的世界规则也会触发回退。

第一版在 cap/state 构造之前按完整配置保守决策：只要存在任意 natural-settings 条目，本次整个刷怪计划就走原生串行；尚未按生物群系/候选类型缩小范围。这样会产生额外串行回退，但不会把未知候选误放进 worker。

## 接入原模组的方式

- 原 FinalizeSpawn 监听器保留在原 EventBus 位置，不替换、不重复发事件、不重复调用 finalizeSpawn。
- placement/position/finalize 对当前执行单元内的生成开关读取同一冻结规则；属性在允许后仍由原实现设置一次。
- prepared 分支没有 natural-settings 条目，因此保持原 evaluator 的“无配置直接允许”路径，不额外消耗 chance RNG。
- 无适用设置时 multiplier 本应为 1，prepared 分支不读全局 multiplier 缓存；不把旧 revision 写入新配置的缓存。
- setter/load/clear 与快照捕获共用重入门锁；worker 读快照不持该锁；计划级数据固定到计划结束，主线程回退单元也保持该 revision。嵌套 Map 被复制，NaturalSpawnSettings 值保留原不可变 record。
- 原模组 jar 不作为发布内容，也没有复制原 evaluator。

空列表短路有意移除了原本无意义的区块访问/加载副作用；匹配结果仍然相同。这个优化不是对 chunk load 时机“完全无变化”的承诺。

## 兼容保护如何放行

适配器注册自己的 preparation，然后显式调用 `claimNaturalSpawning`。核心只在注册成功、Mixin 标记验证成功后，将目标 mod 的 `DISABLE_SPAWNING` 改成适配器负责逐单元准入。

这不会打开管理员关闭的刷怪开关，不会解除 c2me 等 `DISABLE_ENGINE` 限制，也不会认证其他 FinalizeSpawn 监听器。claim 是适配作者的明确合同与启动验证，不是核心自动证明第三方代码线程安全。

如果配置需要世界条件，compat 中可能看到 spawn worker 开关已开启，但 adapter 的 serialPlans 次数持续增加、实际 worker 次数为 0——这是正确的保守回退，不代表配置里的规则已被并行实现。

## 构建

从仓库根目录：

```sh
./gradlew :mobspawncontroller-adapter:jar --offline
./gradlew :neoforge:installerJar -PforgiaVersion=21.1.250.46-beta --offline
```

适配器产物：`adapters/mobspawncontroller/build/libs/forgia-mobspawncontroller-adapter-0.2.0.jar`。

原模组不作为编译依赖；以名字和精确描述符接入 mod-owned 方法。metadata 依赖锁定 `[1.2.3]`，每个 Mixin 注入/包裹要求恰好命中一次。生产核心提供 MixinExtras，适配器不重复打包该库。

## 真实 jar 集成验证

```sh
./gradlew :mobspawncontroller-adapter:junitTest --offline \
  -PmobSpawnControllerJar=/absolute/path/mobspawncontroller-neoforge-1.21.1-1.2.3.jar
```

测试期间只在 build 目录展开原 jar，由 NeoForge 加载真实模组与适配器。验证的是真实变换后的方法、原属性副作用和实际 region spawn worker，不是复制的规则实现。

负对照只运行空结构列表测试，移除适配器后预期两个空列表用例失败：

```sh
./gradlew :mobspawncontroller-adapter:junitTest --offline \
  -PmobSpawnControllerJar=/absolute/path/mobspawncontroller-neoforge-1.21.1-1.2.3.jar \
  -PmscAdapterNegativeControl=true
```

未提供 jar 时集成任务明确跳过。发布 jar 不包含原模组类、测试类、反编译源码或依赖副本。

## 诊断和剩余边界

`/neoforge regionthreading compat` 显示 adapter ready/serial/serialPlans 次数、准备耗时和最近回退原因；世界条件回退原因为 `forgia_mobspawncontroller:world_dependent_rules`。

仍未并行实现：结构引用/包围盒数据服务、任意半径附近数量限制的重叠窗口准入与实体取消联动、带 multiplier 的 cap 协议。它们当前通过完整原生串行执行保留功能，而不是返回虚构的空数据。

更完整的背景见 [审查报告](../../region-threading/MOBSPAWNCONTROLLER-ADAPTER-AUDIT.md) 和
[API 指南](../../region-threading/MOD-PARALLEL-API.md)。

所有性能收益必须以具体配置测量，本地方法/worker 测试不代表真实整合包多人长跑或性能领先已经验收。
