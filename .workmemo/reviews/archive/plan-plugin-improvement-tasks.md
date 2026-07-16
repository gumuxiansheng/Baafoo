## Baafoo 插件系统改进任务清单

基于与 MockForge 的对比分析，按 P0-P4 优先级拆解的具体可执行任务。

---

### P0：将 SPI 路径接入所有协议

> 目标：让所有 Advice 都走 PluginManager SPI 委托路径，使插件系统从"预留扩展点"变为"可用的扩展框架"。

---

#### P0-1 移除 PluginManager 的 @Deprecated 注解

- **文件**: `baafoo-agent/src/main/java/com/baafoo/agent/plugin/PluginManager.java` 第 24 行
- **操作**: 移除 `@Deprecated` 注解，更新 Javadoc 为 "Active plugin SPI manager for third-party protocol extensions"
- **关联**: `BaafooAgent.java` 第 34 行的 `pluginManager` 字段如有 deprecated 警告也一并清理

#### P0-2 补全 PluginManager.resolveTarget() 的协议映射

- **文件**: `PluginManager.java` 第 67-80 行的 `resolveTarget()` 方法
- **操作**: 在 switch 中补充缺失的映射分支
  - `"nio-socket"` / `"nio"` → `InterceptTarget.NIO_SOCKET`
  - `"consul-dns"` → `InterceptTarget.CONSUL_DNS`
  - `"consul-api"` / `"consul"` → `InterceptTarget.CONSUL_API`
  - `"feign"` → `InterceptTarget.FEIGN`
- **验证**: 新增对应单元测试，覆盖所有新增映射

#### P0-3 改造 KafkaProducerAdvice 接入 SPI

- **文件**: `baafoo-agent/src/main/java/com/baafoo/agent/advice/KafkaProducerAdvice.java`（76 行）
- **参照模板**: `PulsarClientAdvice.java` 第 73-93 行的 SPI 调用模式
- **操作**:
  1. 在确定默认重定向目标后（第 47-48 行 `stubHost`/`stubPort` 赋值之后），插入 SPI 咨询代码块
  2. 用独立 `try-catch` 包裹，构建 `PluginContext`（protocol="kafka", host=原始 bootstrap.servers 的 host, port=原始 port）
  3. 调用 `PluginManager.getPlugin(InterceptTarget.KAFKA).intercept(ctx)`
  4. 如果结果 `isRedirect()`，覆盖 `stubHost`/`stubPort`
  5. 异常时 fail-closed，保持默认重定向目标
  6. **注意**: KafkaProducerAdvice 在 App CL 级别，可以直接引用 `BaafooAgent.getPluginManager()`，不受 Bootstrap CL 约束
- **测试**: 参照 `PulsarClientAdviceTest.java` 的 `CountingPlugin` / `ThrowingPlugin` 模式，编写 `KafkaProducerAdviceTest` 的 SPI 相关用例

#### P0-4 改造 KafkaConsumerAdvice 接入 SPI

- **文件**: `baafoo-agent/src/main/java/com/baafoo/agent/advice/KafkaConsumerAdvice.java`（76 行）
- **操作**: 与 P0-3 完全对称，同样在默认重定向目标后插入 SPI 咨询
- **测试**: 同上

#### P0-5 改造 JmsConnectionFactoryAdvice 接入 SPI

- **文件**: `baafoo-agent/src/main/java/com/baafoo/agent/advice/JmsConnectionFactoryAdvice.java`（64 行）
- **操作**:
  1. 在第 45-47 行 `stubHost`/`stubPort`/`newBrokerUrl` 赋值之后，插入 SPI 咨询
  2. 构建 `PluginContext`（protocol="jms", host=原始 brokerURL 的 host, port=原始 port）
  3. 如果插件返回 `redirect`，用新目标重新构造 `newBrokerUrl = "tcp://" + targetHost + ":" + targetPort`
  4. fail-closed 保护
- **特殊性**: 此 Advice 使用 `@OnMethodExit`（不是 OnMethodEnter），需确认 SPI 调用在正确的时间点
- **测试**: 参照 Pulsar 模板编写

#### P0-6 改造 SocketConnectAdvice 接入 SPI（需解决 Bootstrap CL 约束）

- **文件**: `baafoo-agent/src/main/java/com/baafoo/agent/advice/SocketConnectAdvice.java`（125 行）
- **核心难点**: 此 Advice 被内联到 `java.net.Socket`（Bootstrap CL 加载），**无法直接引用** `PluginManager`、`AgentPlugin`、`PluginContext` 等 App CL 类
- **解决方案（二选一）**:
  - **方案 A — 桥接函数模式**（推荐）: 在 `GlobalRouteState` 中新增一个 `volatile` 函数引用字段，如 `public static volatile Object PLUGIN_CONSULT_FN`，类型为 `java.util.function.Function`（Bootstrap CL 可见）。在 `BaafooAgent.premain()` 中设置桥接函数，内部封装 PluginManager 调用逻辑。Advice 中通过 `GlobalRouteState.PLUGIN_CONSULT_FN.apply(args)` 间接调用
  - **方案 B — 反射调用**: 通过 `Class.forName("com.baafoo.agent.BaafooAgent", true, ClassLoader.getSystemClassLoader())` 反射获取 PluginManager 实例并调用。性能开销较大且代码脆弱
- **操作**:
  1. `GlobalRouteState.java` 中新增桥接字段
  2. `BaafooAgent.premain()` 中设置桥接函数（包含 PluginManager 查找 + PluginContext 构造 + intercept 调用 + 结果解析的完整逻辑）
  3. `SocketConnectAdvice.java` 第 74 行和第 107 行的 `GlobalRouteState.lookup()` 之后，增加桥接调用：如果 lookup 返回 null（无匹配路由），尝试插件咨询；如果插件返回 redirect，使用插件的目标地址
  4. `syncGlobalRouteStateToBootstrapCL()` 中同步桥接函数到 Bootstrap CL 的 GlobalRouteState 副本
- **测试**: 需要验证 Bootstrap CL 上下文中的桥接函数可正常执行

#### P0-7 改造 NioSocketConnectAdvice 接入 SPI

- **文件**: `baafoo-agent/src/main/java/com/baafoo/agent/advice/NioSocketConnectAdvice.java`（126 行）
- **操作**: 与 P0-6 完全对称（Javadoc 已说明逻辑是从 SocketConnectAdvice 复制的），使用相同的桥接函数方案
- **测试**: 同上

#### P0-8 编写 SPI 集成测试

- **新增文件**: `baafoo-agent/src/test/java/com/baafoo/agent/plugin/PluginSpiIntegrationTest.java`
- **覆盖场景**:
  - 每个协议（Kafka Producer / Kafka Consumer / JMS / Socket / NIO Socket / Pulsar）的 SPI 委托成功路径
  - 插件返回 `passthrough` 时使用默认重定向目标
  - 插件返回 `redirect` 时覆盖默认目标
  - 插件抛出异常时 fail-closed 到默认目标
  - 无插件安装时正常工作（getPlugin 返回 null）
  - 插件 `intercept()` 耗时过长时的行为（当前无超时机制，记录为 P3 改进项）
- **参照**: `PulsarClientAdviceTest.java` 的 `CountingPlugin` / `PassthroughPlugin` / `ThrowingPlugin` 模式

---

### P1：引入插件级配置

> 目标：支持为每个插件指定独立配置参数，而非所有插件共享全局配置。

---

#### P1-1 扩展 AgentConfig 添加插件配置段

- **文件**: `baafoo-core/src/main/java/com/baafoo/core/config/AgentConfig.java`（210 行）
- **操作**:
  1. 新增内部类 `PluginsConfig`，包含字段：`enabled`（boolean, 默认 true）、`directory`（String, 默认 "./plugins"）、`configs`（Map<String, Map<String, Object>>，按插件名索引的独立配置）
  2. 在 `AgentConfig` 中添加 `plugins` 字段（类型 `PluginsConfig`）

#### P1-2 更新 YAML 配置文件

- **文件**: `baafoo-agent/src/main/resources/baafoo-agent.yml`（58 行）
- **操作**: 在末尾添加 plugins 配置段：
  ```yaml
  plugins:
    enabled: true
    directory: "./plugins"
    configs:
      tdmq:
        brokerPort: 9005
      feign:
        defaultStubStatus: 200
  ```
- **同步更新**: `.deploy/mono/baafoo-agent.yml`、`.deploy/split/agent/baafoo-agent.yml`、`deploy/staging/` 下的所有配置文件

#### P1-3 在 PluginContext 中传递插件专属配置

- **文件**: `baafoo-plugin-api/src/main/java/com/baafoo/plugin/PluginContext.java`（102 行）
- **操作**: 新增字段 `pluginConfig`（类型 `Map<String, Object>`），getter/setter，toString 包含此字段
- **兼容性**: 默认值为空 Map，不影响已有插件

#### P1-4 在 PluginManager 加载时注入配置

- **文件**: `PluginManager.java`
- **操作**:
  1. 构造函数接受 `PluginsConfig` 参数（或整个 `AgentConfig`）
  2. `loadPlugin()` 方法中，在调用 `plugin.init()` 之前，根据 `plugin.getName()` 从 `configs` 中查找对应配置
  3. 将配置注入到后续创建的 `PluginContext` 中（通过 PluginManager 内部缓存 `Map<String, Map<String, Object>>`）

#### P1-5 更新 AgentPlugin 接口（可选方案）

- **文件**: `AgentPlugin.java`（53 行）
- **操作**: 新增带默认方法的 `void configure(Map<String, Object> config)` —— 使用 Java 8 default method 保持向后兼容
- **替代方案**: 如果认为在 PluginContext 中传递配置已足够，可以跳过此步骤

#### P1-6 更新 ConfigLoader 解析逻辑

- **文件**: `baafoo-core/src/main/java/com/baafoo/core/config/ConfigLoader.java`
- **操作**: 确保 YAML 解析器（Jackson）正确反序列化新增的 `plugins` 配置段，包括嵌套的 `configs` Map

#### P1-7 更新 BaafooAgent 传递配置

- **文件**: `BaafooAgent.java`
- **操作**: 第 77 行 `new PluginManager()` 改为 `new PluginManager(agentConfig.getPlugins())`

#### P1-8 编写配置相关测试

- **新增/修改文件**:
  - `PluginContextTest.java` — 新增 `pluginConfig` 字段的测试
  - `PluginManagerTest.java` — 新增配置注入测试（插件收到的 PluginContext 包含正确的 pluginConfig）
  - `ConfigLoaderTest.java`（如存在） — 新增 plugins 配置段解析测试

---

### P2：丰富 InterceptTarget 和 PluginContext 的协议语义

> 目标：为各协议的特有概念提供一等公民字段，而非让插件从 byte[] 中自行解析。

---

#### P2-1 在 PluginContext 中添加协议特有字段

- **文件**: `PluginContext.java`（102 行）
- **操作**: 新增以下字段（均为可选，默认 null/0）：

  | 字段 | 类型 | 适用协议 | 说明 |
  |------|------|----------|------|
  | `topic` | String | Kafka / Pulsar / JMS / MQTT | 消息主题 |
  | `partition` | Integer | Kafka | 分区号 |
  | `key` | String | Kafka | 消息 key |
  | `tenant` | String | Pulsar | 租户 |
  | `namespace` | String | Pulsar | 命名空间 |
  | `destination` | String | JMS | 目标队列/主题名 |
  | `messageType` | String | JMS | 消息类型 (text/bytes/map/object) |
  | `method` | String | HTTP / Feign | HTTP 方法 |
  | `path` | String | HTTP / Feign / Consul | 请求路径 |
  | `queryParams` | Map<String, String> | HTTP / Feign | 查询参数 |

- **兼容性**: 所有字段可选，已有插件不受影响

#### P2-2 在各 Advice 中填充协议特有字段

- **文件清单**:
  - `KafkaProducerAdvice.java` — 从 `ProducerRecord` 中提取 `topic`、`partition`、`key` 填入 PluginContext
  - `KafkaConsumerAdvice.java` — 从 `ConsumerRecord` / subscription 中提取 `topic`
  - `JmsConnectionFactoryAdvice.java` — 从 brokerURL 中解析 `destination`（如果可从连接工厂获取）
  - `PulsarClientAdvice.java` — 从 serviceUrl 中解析 `tenant`/`namespace`（如果 URL 中包含）
  - `SocketConnectAdvice.java` — 从 HTTP 请求中提取 `method`、`path`（如果能获取到）
- **注意**: 部分字段在 Advice 拦截点可能不可用（如 Socket.connect() 时无法获取 HTTP path），应留 null 并在 Javadoc 中标注

#### P2-3 在 PluginManager.resolveTarget() 中支持协议别名

- **文件**: `PluginManager.java` 第 67-80 行
- **操作**: 为常见协议添加别名支持，例如 `"mq"` → KAFKA（如果项目内部有使用 MQ 泛指 Kafka 的场景）、`"amq"` → JMS
- **或者**: 保持严格的协议名映射，在文档中明确列出支持的协议名

#### P2-4 更新测试

- **文件**: `PluginContextTest.java`
- **操作**: 为所有新增字段添加 getter/setter 测试和默认值测试
- **新增**: 各 Advice 的 SPI 测试中验证 PluginContext 的协议特有字段被正确填充

---

### P3：添加插件健康检查和运行时管理

> 目标：引入插件状态管理和 REST API，支持运维监控和操作。

---

#### P3-1 定义 PluginHealth 枚举

- **新增文件**: `baafoo-plugin-api/src/main/java/com/baafoo/plugin/PluginHealth.java`
- **枚举值**:
  - `UNKNOWN` — 刚加载，尚未执行健康检查
  - `HEALTHY` — 最近 N 次 intercept() 调用均正常
  - `DEGRADED` — 有错误但仍在服务（如 fail-closed 降级到默认行为）
  - `UNHEALTHY` — 持续异常，已自动禁用
  - `DISABLED` — 被管理员手动禁用

#### P3-2 在 PluginManager 中添加健康检查机制

- **文件**: `PluginManager.java`
- **操作**:
  1. 为每个插件维护 `PluginHealthStatus` 内部类，包含字段：`health`（PluginHealth）、`lastError`（String）、`lastErrorTime`（Instant）、`successCount`（long）、`errorCount`（long）、`totalLatencyMs`（long）
  2. 新增 `ScheduledExecutorService` 定期（如每 30 秒）汇总健康状态
  3. 包装 `plugin.intercept()` 调用：成功时递增 successCount，异常时递增 errorCount 并在连续失败 N 次后自动标记为 UNHEALTHY
  4. 新增 `Map<InterceptTarget, PluginHealthStatus> healthStatuses` 字段
  5. 新增 `getHealthStatus(InterceptTarget)` 和 `getAllHealthStatuses()` 方法

#### P3-3 添加插件启用/禁用 API

- **文件**: `PluginManager.java`
- **操作**:
  1. 新增 `Map<InterceptTarget, Boolean> disabledPlugins`（ConcurrentHashMap）
  2. 新增 `disablePlugin(InterceptTarget)` / `enablePlugin(InterceptTarget)` 方法
  3. `getPlugin()` 方法中检查 disabledPlugins，如果已禁用返回 null（等效于无插件，走默认路径）

#### P3-4 新增插件管理 REST API

- **新增文件**: `baafoo-server/src/main/java/com/baafoo/server/api/PluginApiHandler.java`
- **操作**: 参照现有 `ManagementApiHandler.java` 的风格，实现以下端点：
  - `GET /api/plugins` — 返回所有已加载插件的列表（名称、目标协议、健康状态、启用/禁用、调用统计）
  - `GET /api/plugins/{name}` — 返回单个插件详情
  - `POST /api/plugins/{name}/disable` — 禁用指定插件
  - `POST /api/plugins/{name}/enable` — 启用指定插件
  - `POST /api/plugins/{name}/reload` — 重新加载指定插件（预留热加载能力）
- **数据来源**: Server 需要能查询 Agent 的 PluginManager 状态。当前 Server 与 Agent 通过心跳通信，可以在心跳响应中携带插件状态摘要，或者新增专用的 Agent → Server 上报通道

#### P3-5 在 StatusApiHandler 中集成插件概览

- **文件**: `baafoo-server/src/main/java/com/baafoo/server/api/StatusApiHandler.java`
- **操作**: 在现有的系统状态 API 响应中添加 `plugins` 段，包含已加载插件数量、各插件健康状态摘要

#### P3-6 编写健康检查测试

- **新增文件**: `baafoo-agent/src/test/java/com/baafoo/agent/plugin/PluginHealthCheckTest.java`
- **覆盖场景**:
  - 插件连续失败 N 次后自动标记为 UNHEALTHY
  - 手动 disable/enable 插件
  - 健康状态统计数据正确性（successCount / errorCount / 平均延迟）
  - 被禁用的插件不参与 intercept() 调用

---

### P4：构建开发者文档和示例

> 目标：降低第三方插件开发门槛，提供完整的开发指南和示例代码。

---

#### P4-1 编写插件开发者指南

- **新增文件**: `docs/plugin-developer-guide.md`
- **内容大纲**:
  1. 插件系统概述（架构模式、设计理念）
  2. AgentPlugin 接口 API 参考（每个方法的详细说明、参数含义、返回值约定）
  3. PluginContext 字段参考（通用字段 + 协议特有字段）
  4. InterceptResult 使用指南（四种结果类型的使用场景和约束）
  5. 开发步骤（从创建 Maven 项目到打包 jar 的完整流程）
  6. 打包规范（maven-shade-plugin 配置、SPI 文件位置、依赖 scope）
  7. 部署说明（jar 放入 plugins/ 目录、配置参数）
  8. 调试技巧（日志查看、单元测试方法、本地调试环境搭建）

#### P4-2 编写协议插件示例

- **新增目录**: `baafoo-example-plugins/`
- **示例插件清单**:
  - `kafka-redirect` — Kafka 协议重定向插件（演示 InterceptResult.redirect 用法，包含 topic 过滤逻辑）
  - `jms-stub` — JMS 协议 Stub 插件（演示 InterceptResult.stub 用法，根据 destination 返回不同 Mock 消息）
  - `http-header-injector` — HTTP 插件（演示在 Socket 拦截点上注入自定义 header 的逻辑）
- **每个示例包含**: 完整的 `pom.xml`、`META-INF/services` 文件、`README.md`、单元测试

#### P4-3 创建 Maven archetype（脚手架）

- **新增模块**: `baafoo-plugin-archetype/`
- **操作**: 创建标准 Maven archetype，包含：
  - `pom.xml` 模板（maven-shade-plugin 配置、baafoo-plugin-api 为 provided scope）
  - `src/main/java/__groupId__/MyPlugin.java` 模板
  - `src/main/resources/META-INF/services/com.baafoo.plugin.AgentPlugin`
  - `src/test/java/__groupId__/MyPluginTest.java` 模板
- **使用方式**: `mvn archetype:generate -DarchetypeGroupId=com.baafoo -DarchetypeArtifactId=baafoo-plugin-archetype`

#### P4-4 更新 plugin-arch-advice.md

- **文件**: `.workmemo/reviews/plugin-arch-advice.md`
- **操作**: 更新文档反映 P0 完成后的实际状态（所有 Advice 已接入 SPI），移除"未来方向"中已完成的项目，添加新的演进方向（热加载、远程注册中心等）

#### P4-5 在 README 中添加插件系统章节

- **文件**: 项目根目录的 `README.md`
- **操作**: 添加或扩展"插件开发"章节，包含快速开始链接（指向 P4-1 的开发者指南）、内置插件列表、配置示例

---

### 任务依赖关系

```
P0-1 (移除 @Deprecated)
  └─→ P0-2 (补全 resolveTarget)
        ├─→ P0-3 (KafkaProducer SPI)  ─→ P0-4 (KafkaConsumer SPI)
        ├─→ P0-5 (JMS SPI)
        ├─→ P0-6 (Socket SPI)  ─→ P0-7 (NIO Socket SPI)
        └─→ P0-8 (集成测试, 依赖 P0-3 ~ P0-7 全部完成)

P1-1 (AgentConfig 扩展)  ─→ P1-2 (YAML 更新)
  ├─→ P1-3 (PluginContext 扩展)
  ├─→ P1-6 (ConfigLoader 更新)
  └─→ P1-4 (PluginManager 注入配置)  ─→ P1-7 (BaafooAgent 传递)
                                           └─→ P1-8 (测试)

P2-1 (PluginContext 协议字段)  ─→ P2-2 (Advice 填充)  ─→ P2-4 (测试)
P2-3 (协议别名, 可独立)

P3-1 (PluginHealth 枚举)  ─→ P3-2 (健康检查机制)  ─→ P3-3 (启用/禁用)
                                                          └─→ P3-4 (REST API)  ─→ P3-5 (Status 集成)
                                                                                    └─→ P3-6 (测试)

P4-1 (开发者指南, 依赖 P0 完成)
P4-2 (示例插件, 依赖 P0 完成)
P4-3 (Maven archetype, 依赖 P4-2)
P4-4 (架构文档更新, 依赖 P0 完成)
P4-5 (README 更新, 最后执行)
```

### 工作量估算

| 优先级 | 任务数 | 预估工时 | 说明 |
|--------|--------|----------|------|
| P0 | 8 个 | 3-5 人天 | P0-6/P0-7（Bootstrap CL 桥接）是最复杂的部分 |
| P1 | 8 个 | 2-3 人天 | 配置链路贯穿 core → agent → plugin-api |
| P2 | 4 个 | 1-2 人天 | 主要是字段添加和 Advice 适配 |
| P3 | 6 个 | 3-4 人天 | 健康检查 + REST API + Server-Agent 通信 |
| P4 | 5 个 | 2-3 人天 | 文档 + 示例 + archetype |
| **合计** | **31 个** | **11-17 人天** | |
