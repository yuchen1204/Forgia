# MobSpawnController 1.2.3 并行适配审查

状态：实现前审查完成；[独立适配模块 0.3.0](../adapters/mobspawncontroller/README.md) 已实现空结构列表短路、冻结规则与有序最终化实验。**默认保留串行保护、不接管自然刷怪**：整包实验出现截断，未证明加速。附近数量/概率子集仅可显式开启实验；结构、倍率和其他未实现条件始终保留完整串行。见 [本地结果](MOBSPAWNCONTROLLER-LOCAL-RESULTS.md) 和 [准入协议](NEARBY-SPAWN-ADMISSION.md)。

## 审查对象与可复现证据

本地整合包 jar：`SkyExplorer-forgia/mods/mobspawncontroller-neoforge-1.21.1-1.2.3.jar`。

- mod id：`mobspawncontroller`，版本：`1.2.3`。
- SHA-256：`ea5f5080d587fd059a3163a7b58689f2b94427743aa7dbaaa7bb231cf0b9ddc3`。
- metadata 标记 `All Rights Reserved`。适配方案使用独立接入代码，不将反编译实现复制或打包进仓库。
- 本地使用 CFR 帮助阅读，并通过 `javap -p -c/-s` 校验关键调用顺序、方法描述符。反编译结果仅位于被忽略的 `tmp/mobspawn-adapter/`，不作为项目源码提交。

可重复的只读检查：

```sh
python3 tools/audit-mobspawncontroller.py \
  --jar /path/to/mobspawncontroller-neoforge-1.21.1-1.2.3.jar \
  --rules /path/to/config/mobspawncontroller/rules.json \
  --javap /path/to/jdk-21/bin/javap
```

工具只解析 metadata、方法描述符和本地原始 JSON；不初始化模组、不加载世界、不认证线程安全。ABI 不匹配时退出码为 2，即使匹配也始终输出 `parallel_activation_certified=false`。

## 结论：结构过滤不是唯一适配边界

首次适配需要覆盖四类状态：

1. 可热更新的规则及属性映射，必须与本次执行的规则 revision 对应。
2. 候选实体的规则判断与 RNG 消耗，必须留在原有判断时点。
3. 世界数据查询：结构、附近同类数量、玩家、光照、生物群系、天气与可选季节接口。
4. `spawnMultiplier` 对候选权重与 mob cap 的修改，不能与核心的 cap 协议分离处理。

不能仅在结构查询处替换一个返回值，随后宣称整个模组支持并行。

## 1. 空结构选择列表也会触发区块查询

`MobSpawnManager.isNaturalSpawnAllowed`：

- 包含结构列表非空时才检查 whitelist；
- 但每次都会调用 `matchesAnyStructure` 检查 excludedStructures，包括空列表。

`matchesAnyStructure` 的原始字节码顺序：

| 指令偏移 | 操作 |
|---|---|
| 18 | 调用 `StructureManager.getAllStructuresAt(pos)` |
| 62 | 对结构调用 `getStructureAt(pos, structure)` |
| 102 | 才开始遍历传入的 selectors |

因此 selectors 为空时，结果最终必为 false，但它仍可遍历结构、请求引用 chunk 的起点数据，并在 worker 上触发 `STRUCTURE_STARTS` 拒绝。空列表并不能证明原实现不会加载区块。

可单独做的修复是“空列表直接 false”：不改变匹配结果和 RNG 消耗，消除无意义查询。但这只是查询优化，不解决共享规则表、附近数量和其他监听器，也不应单独解除保护。对非空结构列表仍必须给出完整的数据合同。

实际结构语义已经确认：使用 `StructureManager.getStructureAt`，即有效结构起点的**整体包围盒**包含候选位置，不是 `getStructureWithPieceAt` 的单个 piece 判定。支持具体 id、`#tag` 和 `*`。起点可能在另一个 chunk，需要保留结构引用语义。

## 2. 本地配置说明优先级应调整

本地 rules.json 的原始静态盘点（不是线上现状，也不是归一化后的有效规则数）：

| 项目 | 数量 |
|---|---:|
| mob 配置条目 | 209 |
| natural 配置段 | 25 |
| 包含非空结构选择列表 | 0 |
| 结构列表均为空 | 25 |
| 配置 max_nearby | 25 |
| 非空季节限制 | 0 |

例如 piglin_brute、spider、fairy 等配置了半径 64 的同类数量限制。这说明本地包即使从来没有设置结构过滤，也能走到第 1 节的失败路径；而消除它之后，**附近数量限制会成为真正的并发正确性问题**。

这份配置不能替代线上配置验收，更不能据此判断其他规则永远不启用。

## 3. 附近数量限制不能直接换成静态计数

`isNaturalSpawnAllowed` 最后会调用 `getEntitiesOfClass(LivingEntity.class, AABB(pos).inflate(radius), predicate)`，只统计同类型，排除正在检查的实体，然后与 `maxNearby` 比较。

这里至少有两个问题：

- Forgia worker 的范围查询可能被租约裁剪，结果不一定覆盖完整 64-block 邻域，可能少计。
- 两个并行单元看到相同的“还剩一个名额”快照，都允许生成，会突破限制。

此外，Forgia 会延迟实体加入世界：已经被 worker 接受但尚未在 barrier 加入的实体，是否应该计数，需要和 admission/cancellation 一起定义。简单把 pending 请求都计入，会在 join 被其他模组取消时产生额外拒绝；只数快照则可能超额。

因此不得仅为消除异常把附近查询换成 region-local 计数，或宣称一个 AtomicInteger 就解决任意位置、任意半径的重叠窗口。第一版遇到无法完整处理的邻域规则应在 body 前回退；要让这个本地配置真正获益，需要补齐候选范围、邻域保留/提交/撤销和重叠单元顺序的协议。

## 4. 全局规则与热更新

四个静态表是 HashMap：RULES、ATTRIBUTE_OVERRIDES、NATURAL_SPAWN_SETTINGS、ACTIVE_SPAWN_SETTINGS。

- RULES 的值包含可变 EnumMap，属性值包含嵌套 Map。
- `getAllRules` 等方法的 unmodifiableMap 只包住外层原表，不是副本，也不阻止原表热更新。
- load/clear/set 等路径会修改这些表；自然设置更新还会 invalidate boost 缓存。
- GUI/network 处理会调用同一批 setter。其线程安排仍需结合 NeoForge 网络注册合同验证，不能仅因服务端命令在主线程就宣称所有更新安全。

`NaturalSpawnSettings` 自身是 record，构造函数对列表做不可变化处理，可作为 snapshot 的值复用；表及其嵌套规则/属性映射仍需复制并冻结。不得让 worker 直接读取原表或让多个 revision 的数据混用。

## 5. 原监听器接管应保留事件时点与属性副作用

NeoForge 入口有三个刷怪监听器：placement、position 和 finalize。前两者读取 spawn-type 允许表，finalize 执行：

1. 检查 SpawnInterception.isPrechecked；
2. 调用 isSpawnAllowed；拒绝时 setSpawnCancelled(true) 并返回；
3. 允许时 applyAttributeOverrides。

自然规则的 chance 随机数在其他条件前消耗；即使 chance=1，原实现也会抽一次 nextDouble。不能在 prepare 阶段提前消耗，也不能因重放抽两次。

属性覆盖会修改属性基值，并在涉及最大生命值时设置实体生命值。它不是纯过滤结果，不允许为了“接管过滤”把整个 finalize 监听器随意跳过。

建议接管位置是原 finalize 监听器中的**规则调用边界**，保留它在 EventBus 中的相对位置，以及后续原属性逻辑。必须同时覆盖 placement/position 的规则表读取，且不影响非自然生成。

不能调用 `SpawnInterception.finalizeNaturalSpawn` 来处理现有 FinalizeSpawnEvent：该方法会执行规则、属性、设置线程局部 prechecked 标记并再次调用 finalizeSpawn，容易重复副作用或递归事件。公共 isPrechecked 并不提供独立、完整的监听器接管 API。

## 6. Boost 与异常清理不能遗漏

`NaturalSpawnBoost` 不只是配置读取：

- 按规则修改 spawn weights；
- 通过线程局部 ACTIVE_LEVEL 关联 category cap；
- 用 ConcurrentHashMap 缓存每维度的 EnumMap cap multiplier，缓存的计算又会读原 natural 规则表。

仅替换 FinalizeSpawn 的规则表读取，不能保证权重与 cap 使用同一个 revision。也不能把过期 snapshot 的计算结果发布到原模组的全局缓存中。

现有 NaturalSpawnerBoostMixin 在 spawnForChunk HEAD 设置 ACTIVE_LEVEL，在 RETURN 清除，异常退出没有等价的 finally 保证。适配器需要审查并修复自身触及的异常清理路径；不能假定 ThreadLocal 天然不会泄漏。

## 7. 核心 API 需要补的实际接口

当前第一阶段 API 只给 metadata、准备数据传递和完整单元开始前回退。以下能力尚不存在：

- 有完整来源说明和加载状态的只读结构引用/整体包围盒查询；未知不能等同不存在。
- 能覆盖完整候选范围的邻域数据与重叠生成准入协议；与真实实体 admission/cancellation 联动。
- 规则 revision 对 weights、caps、过滤与属性读取的一致性约束。
- 适配器验证完成后对某条兼容限制进行明确、原子的接管；当前引擎兼容决策早于注册事件，不能用“发现 adapter jar”绕过。
- 如接管 boost 上下文，需要短生命周期的 enter/exit（异常时也执行）合同，而非让模组自己残留 ThreadLocal。

这些应是可供其他模组复用的 API，不能新增 `if (mobspawncontroller)` 到 worker 的区块守卫或让模组依赖 Engine 私有状态。

## 8. 开发与验收顺序

1. 独立适配工程锁定 1.2.3 的 mod id 与关键 ABI，不复制原实现；不支持的版本保持串行并明确报告。
2. 先做空结构列表查询优化及测试，证明不访问世界、不耗 RNG，非空列表保留原路径。
3. 提供统一的冻结规则视图，让 placement/position/finalize、weights/caps 读取一致 revision；不改变原事件顺序。
4. 实现完整结构数据服务，并处理附近数量规则：不支持的单元必须提前串行。明确区分“正确回退”与“实际并行获益”。
5. 再实现兼容限制接管，接管失败保留原监听器和串行保护；不能打开全局 force 开关。
6. 使用原模组实际方法做固定 RNG 的允许/拒绝对照，覆盖其他监听器取消、属性只执行一次、结构跨 chunk、两个重叠窗口争抢名额、规则热改、异常后的 worker 复用。
7. 真实整合包跑有玩家的长测，同时计入准备成本和串行比例。只有无结构异常不等于适配完成，更不等于性能已经提升。

审查阶段只执行了本地离线分析。其后的适配模块仅改变空结构列表查询：省去原本无意义的世界访问，不改变匹配结果。该模块以真实 1.2.3 jar 验证，移除适配器时两个空列表测试会复现原方法的世界访问失败；非空列表测试保持通过。尚未操作线上服务器或解除兼容保护。
