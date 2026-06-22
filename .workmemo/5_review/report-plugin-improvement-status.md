## 插件系统改进任务实施状态审查报告

审查日期：2026-06-21

对照 `plan-plugin-improvement-tasks.md` 中的 31 项任务，逐项核查代码实现状态。

---

### 总览

| 优先级 | 总任务数 | 已完成 | 部分完成 | 未开始 | 完成率 |
|--------|---------|--------|---------|--------|--------|
| P0 | 8 | 8 | 0 | 0 | **100%** |
| P1 | 8 | 0 | 0 | 8 | 0% |
| P2 | 4 | 0 | 1 | 3 | 12.5% |
| P3 | 6 | 0 | 0 | 6 | 0% |
| P4 | 5 | 0 | 1 | 4 | 10% |
| **合计** | **31** | **8** | **2** | **21** | **29%** |

P0 全部完成，P1-P4 基本未动。

---

### P0：将 SPI 路径接入所有协议 — 全部完成

| 任务 | 状态 | 代码证据 |
|------|------|----------|
| P0-1 移除 @Deprecated | **已完成** | `PluginManager.java` 第 23 行，类上无 `@Deprecated` 注解，Javadoc 已更新为 "Manages the lifecycle of Baafoo agent plugins via the AgentPlugin SPI" |
| P0-2 补全 resolveTarget() | **已完成** | `PluginManager.java` 第 65-91 行，switch 分支覆盖全部 8 个 InterceptTarget 值，新增 `nio-socket`/`nio`→NIO_SOCKET、`consul-dns`→CONSUL_DNS、`consul-api`/`consul`→CONSUL_API、`feign`→FEIGN |
| P0-3 KafkaProducerAdvice SPI | **已完成** | `KafkaProducerAdvice.java` 第 71 行 `BaafooAgent.getPluginManager()`，第 73 行 `pm.getPlugin(InterceptTarget.KAFKA)`，第 75-79 行构造 PluginContext 并调用 `plugin.intercept(ctx)`，第 87-89 行 fail-closed |
| P0-4 KafkaConsumerAdvice SPI | **已完成** | `KafkaConsumerAdvice.java` 第 71-84 行，结构与 Producer 完全对称 |
| P0-5 JmsConnectionFactoryAdvice SPI | **已完成** | `JmsConnectionFactoryAdvice.java` 第 67 行 `BaafooAgent.getPluginManager()`，第 69 行 `pm.getPlugin(InterceptTarget.JMS)`，第 71-75 行 PluginContext + intercept，保持 `@OnMethodExit` + 反射调用模式 |
| P0-6 SocketConnectAdvice SPI | **已完成** | `SocketConnectAdvice.java` 第 183-196 行通过 `GlobalRouteState.PLUGIN_CONSULT_FN` 桥接函数调用 SPI，`GlobalRouteState.java` 第 148 行声明 `public static volatile Function<Object[], Object[]> PLUGIN_CONSULT_FN` |
| P0-7 NioSocketConnectAdvice SPI | **已完成** | `NioSocketConnectAdvice.java` 第 184-197 行，与 Socket 完全对称的桥接函数方案 |
| P0-8 SPI 集成测试 | **已完成** | `PluginSpiIntegrationTest.java`（408 行，12 个测试方法）覆盖 Kafka Producer/Consumer、JMS、Socket Bridge 的 redirect/passthrough/fail-closed/no-plugin 场景，含 CountingPlugin、PassthroughPlugin、ThrowingPlugin 三种测试桩。`PulsarClientAdviceTest.java`（242 行）另含 3 个 SPI 用例 |

**P0 评价**：实现质量高。App CL 级别的 Advice（Kafka/JMS）直接通过 `BaafooAgent.getPluginManager()` 调用 SPI，Bootstrap CL 级别的 Advice（Socket/NIO Socket）通过 `GlobalRouteState.PLUGIN_CONSULT_FN` 桥接函数间接调用，两种路径都有完善的 fail-closed 异常保护和充分的测试覆盖。

---

### P1：引入插件级配置 — 全部未开始

| 任务 | 状态 | 代码证据 |
|------|------|----------|
| P1-1 AgentConfig plugins 段 | **未开始** | `AgentConfig.java`（210 行）无 `PluginsConfig` 内部类，无 `plugins` 字段 |
| P1-2 YAML plugins 配置块 | **未开始** | `baafoo-agent.yml`（59 行）无 `plugins:` 配置段 |
| P1-3 PluginContext pluginConfig 字段 | **未开始** | `PluginContext.java`（102 行）无 `pluginConfig` 字段 |
| P1-4 PluginManager 配置注入 | **未开始** | `PluginManager.java` 构造函数仅接受 `String pluginDir`（第 43 行），`loadPlugin()` 中无配置注入逻辑 |
| P1-5 AgentPlugin configure() 方法 | **未开始** | `AgentPlugin.java`（53 行）仍为 5 个原始方法 |
| P1-6 ConfigLoader 解析 | **未开始** | `ConfigLoader.java`（92 行）无 plugins 配置段处理 |
| P1-7 BaafooAgent 传递配置 | **未开始** | `BaafooAgent.java` 第 77 行仍为 `new PluginManager()`（无参构造） |
| P1-8 配置测试 | **未开始** | PluginContextTest / PluginManagerTest / ConfigLoaderTest 均无配置相关新用例 |

---

### P2：丰富协议语义 — 大部分未开始

| 任务 | 状态 | 代码证据 |
|------|------|----------|
| P2-1 PluginContext 协议特有字段 | **未开始** | `PluginContext.java` 无 `topic`、`partition`、`key`、`tenant`、`namespace`、`destination`、`messageType`、`method`、`path`、`queryParams` 等字段 |
| P2-2 Advice 填充协议字段 | **未开始** | KafkaProducerAdvice 第 75-77 行仅设置 protocol/host/port，PulsarClientAdvice 第 78-81 行同理，JmsConnectionFactoryAdvice 第 71-73 行同理 |
| P2-3 resolveTarget() 别名 | **部分完成** | P0-2 的 resolveTarget() 已支持所有 8 个 InterceptTarget 的标准名称映射和少量别名（`nio`/`socket`/`consul`），但无扩展的协议别名（如 `mq`→KAFKA、`amq`→JMS） |
| P2-4 P2 测试 | **未开始** | PluginContextTest 无新字段测试 |

---

### P3：健康检查和运行时管理 — 全部未开始

| 任务 | 状态 | 代码证据 |
|------|------|----------|
| P3-1 PluginHealth 枚举 | **未开始** | 项目中无 `PluginHealth.java` 文件 |
| P3-2 健康检查机制 | **未开始** | `PluginManager.java` 无 `ScheduledExecutorService`、无 health 相关字段或内部类 |
| P3-3 启用/禁用 API | **未开始** | `PluginManager.java` 无 `disablePlugin()`/`enablePlugin()` 方法 |
| P3-4 插件 REST API | **未开始** | Server api 目录中无 `PluginApiHandler.java` |
| P3-5 StatusApiHandler 集成 | **未开始** | `StatusApiHandler.java`（40 行）的 `SystemStatusResponse` 无 `plugins` 字段 |
| P3-6 健康检查测试 | **未开始** | 无 `PluginHealthCheckTest.java` |

---

### P4：文档和工具链 — 大部分未开始

| 任务 | 状态 | 代码证据 |
|------|------|----------|
| P4-1 开发者指南 | **未开始** | 无 `docs/plugin-developer-guide.md` |
| P4-2 示例插件 | **未开始** | 无 `baafoo-example-plugins/` 目录（现有的 `baafoo-test-plugin/feign/` 是 SPI 演示，不是面向开发者的示例） |
| P4-3 Maven archetype | **未开始** | 无 `baafoo-plugin-archetype/` 模块 |
| P4-4 plugin-arch-advice.md 更新 | **未开始** | `.workmemo/5_review/plugin-arch-advice.md`（382 行）仍为原始设计提案，SPI 接口定义为 `com.baafoo.spi` 包（实际为 `com.baafoo.plugin`）、PluginContext 定义为 interface（实际为 POJO）、InterceptTarget 定义为 record（实际为 enum）、InterceptResult 定义为 sealed interface（实际为具体类），且无 `redirect` 结果类型的描述 |
| P4-5 README 插件章节 | **部分完成** | `README.md` 第 446-475 行有约 30 行的 "Plugin Development" 章节，展示了基本的 AgentPlugin 实现示例和 META-INF/services 注册，但缺少配置文档、协议字段说明、最佳实践和示例链接 |

---

### 下一步行动建议

P0 已全部落地，插件系统的核心 SPI 委托机制已完整可用。建议按以下顺序推进：

1. **P1 优先**（配置链路）—— 这是让插件真正可用的基础。没有插件级配置，TDMQ Plugin 的 `brokerPort: 9005` 仍然硬编码在代码中，无法通过配置文件调整。实施路径：AgentConfig → YAML → PluginContext → PluginManager → BaafooAgent，一次性贯通。

2. **P2 紧随**（协议语义）—— 当前 PluginContext 只有 protocol/host/port 三个有效字段，Kafka 插件无法获取 topic 信息做精细化路由，Pulsar 插件无法获取 tenant/namespace。在 P1 完成后，P2 的改动量很小（主要是加字段 + Advice 中多填几个 setter）。

3. **P4-4 应尽快做**（更新 plugin-arch-advice.md）—— 该文档仍描述的是一个"假想的"设计（sealed interface、record、com.baafoo.spi 包），与实际代码严重脱节。如果团队成员以此文档为参考来开发插件，会产生大量困惑。建议 P0 完成后立即更新。

4. **P3 和 P4 其余项**（健康检查、REST API、文档工具链）可以放入后续迭代。
