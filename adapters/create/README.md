# Forgia Create Adapter 0.1.0

实验性独立适配模组，限定 **Minecraft 1.21.1 + Create 6.0.10**，需要本次增加
`BLOCK_ENTITY_TICK_ADMISSION` 的 Forgia 核心（构建版本 `21.1.250.52-beta`）。
原 Create jar 保留；适配器通过公开注册 API 和四个只读 Mixin accessor 工作。

实现的是部分机器的**原始 tick 在原生 region worker 上执行**。第一版支持传动部件和磨石的稳定运行路径；
没有宣称整个 Create、动力网络重建或工业产线已经并行，也没有已测得的整服加速倍数。

| 方块实体类型 | 可在 worker 上执行 | 必须完整串行的 tick |
|---|---|---|
| `create:simple_kinetic`（轴、小/大齿轮） | 已初始化、网络未变、无需校验的原始 tick，包括原有 behaviour/lazy tick | 初始化、速度传播、网络 dirty、周期校验、旋转粒子发送、未知 behaviour |
| `create:encased_shaft`、`encased_cogwheel`、`encased_large_cogwheel`、`gearbox` | 同上 | 同上 |
| `create:water_wheel`、`large_water_wheel` | 网络稳定且本 tick 无流量检查/动力源重激活时的原始 tick | 定期流量重算、网络和动力源更新 |
| `create:millstone` | 同上，且空闲、停转、输出已满，或计时器扣减后仍大于 0 | 配方查找、设置加工时长、计时完成、消耗原料、随机产物与奖励、未知物品处理器 |

混合器、辊压机、传送带、溜槽、漏斗、分液池、储罐、流体网络、机械手、其它动力生成器、列车和移动结构仍走原有串行路径。
第一版只放行完全由上述已审查成员组成的稳定动力网；连接其它 Create 机器/附属模组动力源时，关联的已支持机器也会回退。
仅添加模组不会打开管理员关闭的核心开关。

## 安装与配置

1. 停服后安装带上述能力的新 Forgia 核心。
2. 将 `forgia-create-adapter-0.1.0.jar` 放进服务器 `mods/`，保留原 `create-1.21.1-6.0.10.jar`。
3. 在实际生效的 `forgia.toml`（实例或世界 `serverconfig`）启用：

```toml
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingBlockEntityTicks = true
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 8
```

已有 worker 数量可以继续使用。区域仍然使用核心的内部 chunk 准入规则：8×8 区域内，外沿一圈 chunk
保留串行；对于满区域，内部是 6×6 chunk。不要为了本适配器把 Create 类型加入
`admit_block_entities`；适配器自身注册候选类型并逐次检查，服务器的显式串行声明仍优先。

核心仍采用保守的整 chunk 准入：同一 chunk 存在未准入的其它机器时，该 chunk 整体串行。
逐实例状态检查在真正调用 ticker 前执行，因此缓存不会把昨天的安全状态误当作今天的准入。

## 诊断

```text
/neoforge regionthreading compat
/neoforge regionthreading status raw
```

`compat` 新增类似输出：

```text
Block entity tick adapter forgia_create:steady_ticks: types=[...], checks=..., refused=..., nativeCompleted=..., checkMs=..., reasons={...}
```

- `prepareMs`：整网预检的累计主线程耗时。
- `checks`：实际进入逐次 predicate 的次数。
- `nativeCompleted`：原生 worker 上成功返回的 ticker 调用；不把任务提交或主线程回退当作完成。
- `refused`/`reasons`：在 body 开始前决定回到主线程的调用。
- `TOPOLOGY_CHANGE`：附着/速度更新、network dirty 或周期校验。
- `CALLBACK_REQUIRED`：加工、配方或粒子发布边界。
- `UNKNOWN_CLASS`、`UNKNOWN_BEHAVIOUR`、`SHARED_RESOURCE`：实例或行为超出已审查范围。
- `UNVERIFIED_TRANSFORMATIONS`：原始类 SHA-256 或已合并 Mixin 来源不受支持。

计数是累计次数，不能当作 CPU 时间，也不能用 worker 占比推导加速倍数。应记录同一测量窗口的增量
和完整 MSPT（包括准备、准入、屏障及回退），在同一初始世界、相同配置与预热下比较开关。
简单传动部件的 tick 很轻，调度/检查成本可能大于收益；第一版更适合验证并行路径和缩小后续热点范围。

## 并发与行为合同

每批开始前在静止的服务器线程检查动力注册表：整网成员、加载/实例身份、未知类/behaviour、校验与网络脏标记、
水车流量回调、源重激活和共享库存均影响整网准入。存在尚未加载的网络成员时也回退。
检查有 4096 个网络/65536 个成员的上限；超限保留串行。worker 只读取不可变网络 id 集和自己的实例字段，
不会并发遍历或更改 Create 的 `HashMap` 动力注册表。

原始 `SmartBlockEntity.tick`、`KineticBlockEntity.tick`、磨石 tick 不被取消、替写或拆掉部分回调。
准入器仅读当前 owner 的字段，不碰 live 动力网络或外部库存。周期校验/拓扑/加工边界保留整次原调用，
并在所有本批 worker 结束后的服务器阶段执行一次。已经开始并抛出异常的 body 不做串行重试。

exact class 检查阻止未经审查的子类继承许可；behaviour 检查每次执行，动态添加的未知 behaviour 即时回退。
被查阅的原始 Create 类使用固定 SHA-256，并检查已经合并到这些类及 `ItemStackHandler` 的 Mixin 来源。
未知变换不自动放行。该检查不能自动认证任意 ASM/coremod、异步能力访问或会替换公共字段/共享库存的附属模组。
原始 jar 与真实整合包需要分别验证，整合包新增 Mixin 可能使这些类型全部回退。

跨 region 双方块实体操作 API 的完成阶段仍是串行；本实现没有把它伪装成并行机器。

## 重复真实服务器验证

仓库提供 `tools/server_smoke.py`。它复制已安装服务器的 libraries 作为缓存，在新的私有目录安装当前核心，
只装 Create 与适配器，创建两个真实水车装置并检查实际加工、worker 完成、安全计数和正常退出。
不会覆盖已有目录或修改作为输入的世界/模组；必须提供已经接受的 `eula.txt`。

```sh
python3 adapters/create/tools/server_smoke.py \
  --run-dir /absolute/path/to/new-create-smoke \
  --libraries /absolute/path/to/existing-server/libraries \
  --create-jar /absolute/path/to/create-1.21.1-6.0.10.jar \
  --eula /absolute/path/to/existing-server/eula.txt \
  --java /absolute/path/to/jdk-21/bin/java
```

这是功能验证，不测定加速倍数。产物与详细结果见 [验证记录](../../region-threading/CREATE-ADAPTER-VALIDATION.md)。

## 从仓库构建

使用 JDK 21，从仓库根目录执行。原 jar 仅由开发者本地提供，不提交、不打包：

```sh
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.52-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:jar :neoforge:parallelApiJar :neoforge:installerJar

./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.52-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:junitTest
```

另用独立 JVM 注入未知 Mixin 的对照：

```sh
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.52-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  -PcreateAdapterUnverifiedHookControl=true :create-adapter:junitTest
```

该测试必须观察到实际原始机器完整串行和 `UNVERIFIED_TRANSFORMATIONS`，之后重新运行普通测试恢复报告。

产物在 `adapters/create/build/libs/forgia-create-adapter-0.1.0.jar`。
未提供 Create jar 时编译、打包和真实 jar 测试明确跳过，不发布一个缺少类的空适配器。
发布 jar 只应包含适配器、accessor 和元数据；不包含上游实现、测试类、Minecraft/NeoForge 或 API 副本。

测试使用真实已变换的 Create 方块实体、真实 Minecraft bound ticker 和真实 Forgia worker batch；世界、chunk
服务是测试替身。覆盖两个 region 同时执行、计时轨迹对照、完整原配方调用、库存/RNG、缓存重用、未知行为/子类、
身份替换和异常不重放。它们不等价于多人长期运行、真实整包产线吞吐量或完整存档往返验收。
