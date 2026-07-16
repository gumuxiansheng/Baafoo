# Baafoo 全面竞品分析报告（含 mockforge 深度对比 + 竞品全景）

> **编制日期**: 2026-07-16
> **代码基线**: `C:\Dev\Projects\Baafoo` 工作区实测（非文档口径）；参考既有 `.workmemo/5_review/` 下 7 份 mockforge 分析报告（2026-06）
> **结论先行**: Baafoo 的**真实护城河**是「JavaAgent 零代码透明拦截 + JVM 内多协议 MQ 挡板（Kafka/Pulsar/JMS）+ gRPC 全流式」这一组合——全市场**没有第二款工具**同时做到。但相对 mockforge（Rust 真实开源项目，crates.io v0.3.165，15 天前仍在发版），Baafoo 在 **AI/行为克隆、WebSocket/GraphQL、混沌弹性（熔断/隔离/限流/流量塑形）、WASM 插件、请求链路编排、正态分布延迟** 等维度差距比 6 月计划的预期更大；而 gRPC 已真实落地（超预期）。

---

## 更新记录（二次更新 · 2026-07-16）

> **本次更新性质**：基于**代码实测复核**（非文档口径，已交叉验证两次）对报告做修正。原始报告编制于 2026-07-16，本次为同日二次更新。

### 用户主张 vs 代码事实（冲突已核验）

用户提出两条主张，均经代码实测**证伪**：

| 主张 | 主理人判定 | 代码证据（文件:行号） |
|---|---|---|
| **A. "GraphQL 已实现"** | ⚠️ 部分成立（口径差异） | `baafoo-core/.../model/MatchCondition.java` 仅有 `graphqlOperationName()`/`graphqlOperationType()` 工厂方法；`baafoo-core/.../util/MatchEngine.java` 有 `extractGraphqlOperationType()` 及 `case "graphqlOperationType"`/`"graphqlOperationName"`；`baafoo-server/.../api/RuleApiHandler.java:255-257` 仅把上述两者列为允许的条件类型。**全代码库无任何 `GraphqlHandler`/`GraphQLStubHandler`、无 decoder、无端口**。即 **GraphQL-over-HTTP 请求今天已可经 HTTP 协议 + 这两个匹配条件被匹配/挡板**，能力属于 HTTP 的子集——用户以该口径理解"已实现"则**成立**；但若指"独立 GraphQL 协议 handler/解析器"则**不成立**（无独立 handler/decoder/端口，Web 侧仅 `RuleEditorPage.vue` 的"+ GraphQL 快捷"按钮）。 |
| **B. "MQ 协议不再是 Beta"** | ❌ 不成立（标签层面） | `baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java` 第 44-46 行 Javadoc、第 147 行、第 253 行、第 261 行共 5 处明确标注 Kafka/Pulsar/JMS 为 `Beta`；全代码库无 `ProtocolStatus`/`Beta`/`GA`/`STABLE` 枚举或常量，无 `@Beta` 注解，AGENTS.md/web/README 均无 Beta/GA 标签。**但**：实际 broker 已是真实实现（`KafkaMockBroker`+`broker/codec/Kafka*Codec*`、`PulsarMockBroker`+`PulsarFrameDecoder`、内嵌 Artemis 的 `JmsMockBroker`），功能已超越注释中"仅 TcpStubHandler 基础连通"的描述。 |

**主理人判定**：两条主张需按口径区分——**GraphQL**：用户澄清其为"HTTP 的子集，经 graphqlOperationName/Type 匹配条件支持"，该口径下**成立**（GraphQL-over-HTTP 可被匹配/挡板，属 HTTP 能力子集）；但**不存在独立 GraphQL 协议 handler/decoder/端口**。**MQ**：Kafka/Pulsar/JMS 的 Beta 标注确为**落后注释**（用户确认），实际 broker 已真实落地，Beta 标签已于 **2026-07-16 通过代码清理移除**（`BaafooServer.java` 5 处注释/日志）。报告据此更新。

---

## 0. 与既有 mockforge 分析报告的关系

既有 7 份报告（2026-06）把 mockforge 当作"能力参考源"，并据此给出移植优先级：

| 既有报告 | 核心结论 | 当前（2026-07）核验结果 |
|---|---|---|
| `analysis-mockforge-comparison.md` | Kafka 故障注入 / MQ Relationship 为 P0；协议版本封顶为 P1 | Kafka 故障注入已落地；**gRPC 已真实实现**（原报告未列入）；MQ Relationship 状态待确认 |
| `analysis-mockforge-ai-features-20260620.md` | behavioral-cloning / LlmProvider / StatefulAiContext 可借鉴 | **代码层面零实现**（src 全仓 grep 无命中） |
| `analysis-mockforge-ai-migration-20260620.md` | Phase1/2/3 共 20.5 人天 | **Phase1/2/3 均未启动** |
| `analysis-mockforge-cli-migration-20260620.md` | CLI 应补 OpenAPI 导入等 | **CLI 仍为 init/version/help** |
| `analysis-multilang-sdk-feasibility-20260620.md` | Thin SDK + Proxy + Full SDK | **仅 Thin SDK（Go/Py/Node）+ Go Proxy**，无 Full SDK |
| `analysis-new-protocols-feasibility-20260620.md` | gRPC P0、WebSocket P1、MQTT/AMQP P2/P3 | **gRPC 已做**；WebSocket/MQTT/AMQP **未做** |
| `analysis-protocol-version-upgrade-20260619.md` | Kafka/Pulsar 协议版本升级 6-10 人天 | Kafka 已拆分 V9/V12 codec（见 `KafkaProduceCodecV9`/`KafkaFetchCodecV12`），与计划一致 |

**本报告在上述事实基线上，做三件事**：① 把 mockforge 对比更新到其**当前真实版本能力**；② 把视野从 mockforge 单一竞品**扩展到全市场**；③ 给出一份**基于现状**的优先级路线图。

---

## 一、Baafoo 现状基线（代码实测，2026-07）

### 1.1 已实现能力（代码确认）

| 维度 | 状态 | 关键证据（类名/文件） |
|---|---|---|
| **协议 - HTTP** | ✅ 完整 | `HttpStubHandler`（端口 9000），含 header/query/body/bodyJsonPath/contains/endsWith/regex/exists/caseInsensitive/disabled 全条件类型 |
| **协议 - TCP** | ✅ 完整 | `TcpStubHandler`（9001），hex/regex/multiround |
| **协议 - gRPC** | ✅ 完整（超预期） | `GrpcUnifiedHandler` + `GrpcResponseBuilder` + `GrpcPassthroughForwarder`（9005），Unary/Server-Stream/Client-Stream/Bidi-Stream 四型齐备 |
| **协议 - GraphQL** | ⚠️ 仅 HTTP 层匹配糖（子集支持） | `MatchCondition.graphqlOperationName()`/`graphqlOperationType()`、`MatchEngine.extractGraphqlOperationType()` + `RuleApiHandler.java:255-257` 仅列为匹配条件；**无独立协议 handler/decoder/端口**，实际当作 HTTP 流量（`/graphql`）匹配——即 **GraphQL-over-HTTP 今天已可经 HTTP 协议 + 该匹配条件被挡板**（属 HTTP 子集）；独立 GraphQL 协议 handler 仍缺失 |
| **协议 - Kafka** | ✅ 已实现（真实 broker） | `KafkaMockBroker` + `broker/codec/*`，已拆分 `KafkaProduceCodecV9`/`KafkaFetchCodecV12`/`KafkaMetadataCodecV9`。**源码 Beta 标签已于 2026-07-16 移除**，真实 broker 已落地（功能早已超原 Beta 描述） |
| **协议 - Pulsar** | ✅ 已实现（真实 broker） | `PulsarMockBroker` + `PulsarProtobufCodec` + `PulsarFrameDecoder`。**源码 Beta 标签已于 2026-07-16 移除**，真实 broker 已落地（功能早已超原 Beta 描述） |
| **协议 - JMS** | ✅ 已实现（真实 broker） | `JmsMockBroker`（内嵌 ActiveMQ Artemis）。**源码 Beta 标签已于 2026-07-16 移除**，真实 broker 已落地（功能早已超原 Beta 描述） |
| **故障注入 - HTTP** | ✅ 部分 | `FaultInjector`：`HTTP_ERROR`、`DELAY`、`CONNECTION_RESET`、`READ_TIMEOUT` |
| **故障注入 - Kafka** | ✅ 部分 | `KAFKA_NOT_LEADER_FOR_PARTITION`(6)、`KAFKA_OFFSET_OUT_OF_RANGE`(1)、`KAFKA_PRODUCE_THROTTLE`、`KAFKA_DELAY`、`KAFKA_CONNECTION_RESET` |
| **环境模式** | ✅ 完整 | `STUB`/`PASSTHROUGH`/`RECORD`/`RECORD_AND_STUB`/`RECORD_ALL` |
| **Agent 架构** | ✅ 完整 | `AgentManifest` + `RouteTable` 模式；`appendToBootstrapClassLoaderSearch()`；Bootstrap-safe 四件套（Socket/NioSocket/Consul DNS/HTTP）；插件事件桥接以 `Object` 规避 `LinkageError` |
| **插件 SPI** | ✅ 完整 | `baafoo-plugin-api`（`AgentPlugin` + `ConnectAdvice`/`RequestAdvice`/`ResponseAdvice`）；`PluginManager` 独立 `PluginClassLoader`(parent=null) + 健康自动禁用；示例 feign/kafka-redirect/tdmq |
| **录制** | ✅ 完整 | `RecordingEntry` 全字段；`RecordingHelper`；gRPC 结构化字段已填 |
| **存储后端** | ⚠️ 受限 | `StorageServiceFactory` **永远返回 `JdbcStorageService`**；方言仅 `H2`/`POSTGRESQL`；`FileStorage` 存在但未接入；**无 MySQL** |
| **Web 控制台** | ✅ 完整 | rules/env/recordings/scenes/users/status/logs + **OpenAPI 导入** + 鉴权 + i18n（zh/en） |
| **多语言 SDK** | ⚠️ Thin 级 | `sdks/{go,python,nodejs}` 均为 Thin（回连 Server）；`proxy/` Go 独立代理；`PROTOCOL-v2.md` 规范 |
| **CLI** | ⚠️ 极简 | `BaafooCli` 仅 `init`/`version`/`help` |

### 1.2 明确缺失（代码确认）

| 缺失项 | 证据 |
|---|---|
| **AI / 行为克隆 / LLM** | src 全仓 grep `BehavioralCloning`/`ProbabilisticModel`/`LlmProvider`/`BehaviorModel`/`StatefulAiContext`/`MockAI` **零命中** |
| **WebSocket / GraphQL / MQTT / AMQP** | WebSocket/MQTT/AMQP 无任何 handler；GraphQL 仅 `MatchCondition.graphqlOperationName()`/`graphqlOperationType()` + `MatchEngine.extractGraphqlOperationType()` + `RuleApiHandler.java:255-257` 作为 **HTTP 层匹配糖**，**无独立协议 handler/decoder/端口** |
| **混沌弹性（作为故障）** | `CircuitBreaker` 仅用于 Agent `ControlChannel` 心跳弹性，**非故障注入**；无 `RateLimiter`/`Bulkhead`/`TrafficShaping` |
| **部分响应 / 载荷损坏** | `FaultInjector` 无 `PartialResponse`/`PayloadCorruption` 分支 |
| **正态分布延迟** | `FaultInjection.delayStdDevMs` 字段存在，但 `FaultInjector` 仅用 `delayMs` |
| **请求链路编排（Request Chaining）** | 无 |
| **CLI 导入/转换/校验** | `switch` 仅 init/version |
| **Web 插件管理页 / AI Studio 页** | `views/` 无 `PluginsPage`/`AiStudioPage` |
| **Full SDK（进程内 stub 引擎）** | 三种 SDK 均回连 Server |

---

## 二、竞品全景与分类

API 模拟/服务虚拟化/混沌注入市场可分成五类，Baafoo 身处"第 4 类 + 部分第 2 类"交叉位：

| 类别 | 代表 | 与 Baafoo 关系 |
|---|---|---|
| **① HTTP 模拟老牌** | WireMock、Mockoon、MockServer、Stoplight/Prism、Postman、Apidog | 直接竞争 HTTP 挡板；生态/成熟度碾压 Baafoo |
| **② 流量捕获/代理型** | Hoverfly、Mountebank、Toxiproxy | 捕获回放 + 混沌；多协议但不含真实 MQ |
| **③ MQ/ESB 商业虚拟化** | Traffic Parrot、Parasoft、Broadcom/CA、IBM RTVS | **唯一与 Baafoo 在 MQ 上重叠**；但全商业 + 配置式 |
| **④ Agent/零侵入型** | **Baafoo（唯一）** | 不改代码、自动感知下游；市场空白 |
| **⑤ 混沌工程平台** | Chaos Mesh、Gremlin、Resilic | 偏基础设施层混沌，不与 Baafoo 直接重叠 |
| **⑥ 综合 AI Mock 平台** | **mockforge（Rust）** | 功能最广、最年轻、最激进；Baafoo 头号对标 |

**关键判断**：Baafoo 真正的"无人区"是 **④**——没有任何竞品用 Agent 在 JVM 内透明拦截 Kafka/Pulsar/JMS/gRPC/HTTP。这是必须守住并放大的护城河。

---

## 三、核心竞品深度对比：mockforge（当前真实版本）

> mockforge 是 GitHub `SaaSy-Solutions/mockforge` 的 Rust 项目，crates.io `mockforge-core` v0.3.165（15 天前发版），`docs.mockforge.dev` 有完整文档。它比 6 月报告分析的版本**更完整**。

### 3.1 mockforge 当前能力（实测/文档）

- **协议**：REST、**gRPC**、**GraphQL**、**WebSocket**、Kafka、AMQP —— 比 Baafoo 多 GraphQL/WebSocket/AMQP。
- **故障注入**：HTTP Error、Connection Error、Timeout、**Partial Response（截断）**、**Payload Corruption**；`mockforge-chaos` 含 **Circuit Breaker / Bulkhead / Rate Limiting / Traffic Shaping（带宽/丢包）/ Latency（正态+抖动）/ Failure（全局错误率/网络失败率/超时率）**，且支持 **per-tag 包含排除**。
- **请求匹配**：Per-Request Matchers（v0.3.125+）：source_ips(CIDR)、headers、body size、header/query 条件 + AND/OR/NOT 表达式。
- **优先级链**：Replay → Stateful → Route Chaos → Global Fail → Proxy → Mock → Record。
- **AI**：`MockAI`（RAG 驱动响应）、`behavioral-cloning`（概率模型 + 序列学习 + 边缘放大）、VBR 引擎、Temporal Simulation（时间旅行）、Scenario State Machines、Chaos Lab、Reality Slider、Drift Learning。
- **编排**：Request Chaining（多步工作流 + 上下文传递）、Workspace 多租户、云同步。
- **插件**：**WASM 插件**（`mockforge-plugin` CLI：new/init/build/test/package/validate/publish/key，Ed25519 签名 + SBOM）。
- **其他**：SMTP mock server、Browser Proxy 条件转发、E2E 加密、现代 Admin UI、Docker。

### 3.2 Baafoo vs mockforge（当前真实）

| 维度 | Baafoo（2026-07 实测） | mockforge（v0.3.165） | 差距 |
|---|---|---|---|
| 拦截模式 | **JavaAgent 零代码透明** | 改连接地址（server 模式） | **Baafoo 胜**（JVM 场景） |
| HTTP 挡板 | ✅ 全条件 | ✅ 全条件 + 表达式 | 持平/微劣 |
| gRPC | ✅ 四流式完整 | ✅ 完整 | 持平 |
| WebSocket | ❌ | ✅ | **Baafoo 劣** |
| GraphQL | ◐（HTTP 子集：经 graphqlOperationName/Type 匹配条件挡板，无独立 handler） | ✅ | **Baafoo 劣**（缺独立协议 handler/解析器） |
| Kafka/Pulsar/JMS | ✅（真实 broker，Beta 标签已移除） | Kafka/AMQP（无 Pulsar/JMS） | **Baafoo 胜**（Pulsar/JMS） |
| 故障：HTTP_ERROR/DELAY | ✅ | ✅ | 持平 |
| 故障：连接重置/超时 | ✅ | ✅ | 持平 |
| 故障：部分响应/载荷损坏 | ❌ | ✅ | **Baafoo 劣** |
| 故障：熔断/隔离/限流/塑形 | ❌ | ✅ | **Baafoo 劣（大）** |
| 正态分布延迟 | ❌ | ✅ | **Baafoo 劣** |
| 请求链路编排 | ❌ | ✅ | **Baafoo 劣** |
| AI/行为克隆 | ❌ | ✅（RAG+克隆） | **Baafoo 劣（大）** |
| 插件机制 | Java SPI（合理） | WASM（跨语言） | 路线差异，非绝对优劣 |
| 录制→回放 | ✅（H2/PG） | ✅ + 表达式回放 | 持平 |
| 云协作/多租户 | ❌ | ✅ Workspace | **Baafoo 劣** |
| 时间旅行/状态机场景 | ❌（仅 env 模式） | ✅ | **Baafoo 劣** |

**小结**：mockforge 在"功能广度"上明显领先（尤其 AI、混沌弹性、WebSocket/GraphQL、编排）；Baafoo 在"JVM 零侵入 + Pulsar/JMS + gRPC 流式"上独有优势。两者**目标用户有交集但不完全重叠**：mockforge 偏"独立 mock server + AI 生成"，Baafoo 偏"生产/测试环境对 Java 应用的透明挡板"。

---

## 四、其他关键竞品速览（定位对比）

| 竞品 | 协议 | 混沌/故障 | 与 Baafoo 的胜负手 |
|---|---|---|---|
| **WireMock**（Java，5M 下载/月） | HTTP/HTTPS（gRPC/GraphQL 扩展中） | 延迟（**lognormal/uniform**）、故障、chunked dribble、stateful scenarios | **Baafoo 赢在多协议/MQ/Agent**；WireMock 赢在生态/成熟度/多语言/Cloud/OpenAPI 双向同步 |
| **MockServer**（Java） | HTTP/TCP | 延迟、部分故障、proxy record、验证 | 与 WireMock 类似；Baafoo 赢在 MQ/gRPC/Agent |
| **Hoverfly**（Go） | HTTP/HTTPS | capture/simulate、Lua 脚本、网络条件 | 轻量、K8s sidecar；**Baafoo 赢在 MQ/gRPC** |
| **Mountebank**（Node） | HTTP/TCP/SMTP/HTTPS（多协议） | 可编程响应 | 唯一 OSS 多协议；**但无真实 MQ/gRPC**；Baafoo 赢在 MQ/gRPC/Agent |
| **Toxiproxy**（Shopify） | TCP/HTTP（代理） | 延迟/超时/带宽/乱序（纯混沌） | 混沌专注；Baafoo 含挡板+混沌；定位不同 |
| **Traffic Parrot**（商业） | HTTP/JMS/IBM MQ/Kafka/gRPC/文件 | record/replay、契约、故障 | **唯一商业 MQ 竞品**；Baafoo 赢在零代码/Agent/Pulsar；TP 赢在金融协议(ISO8583/SWIFT)/商业 SLA |

**横向结论**：除 mockforge（综合）与 Traffic Parrot（MQ 商业）外，**没有任何竞品在"MQ 挡板 + Agent 零侵入"上构成实质威胁**。Baafoo 的多协议 MQ 能力在全市场是稀缺的。

---

## 五、统一能力矩阵（6 工具 × 关键维度）

图例：● 完整  ◐ 部分/实验  ○ 无  — 不适用

| 维度 | Baafoo | mockforge | WireMock | Mountebank | Traffic Parrot | Hoverfly |
|---|---|---|---|---|---|---|
| HTTP 挡板 | ● | ● | ● | ● | ● | ● |
| gRPC | ●(全流) | ● | ◐ | ○ | ● | ○ |
| WebSocket | ○ | ● | ○ | ○ | ○ | ○ |
| GraphQL | ◐(HTTP子集) | ● | ◐ | ○ | ○ | ○ |
| Kafka | ● | ● | ○ | ○ | ● | ○ |
| Pulsar | ● | ○ | ○ | ○ | ○ | ○ |
| JMS | ● | ○ | ○ | ○ | ●(IBM MQ) | ○ |
| AMQP/Rabbit | ○ | ● | ○ | ○ | ○ | ○ |
| 零代码拦截 | **●(Agent)** | ○ | ○ | ○ | ○ | ○ |
| 故障:ERROR/DELAY | ● | ● | ● | ● | ● | ● |
| 故障:连接重置/超时 | ● | ● | ◐ | ◐ | ● | ◐ |
| 故障:部分响应/损坏 | ○ | ● | ○ | ◐ | ○ | ○ |
| 混沌弹性(CB/隔离/限流) | ○ | ● | ○ | ○ | ○ | ◐ |
| 正态分布延迟 | ○ | ● | ● | ○ | ○ | ◐ |
| 请求链路编排 | ○ | ● | ○ | ○ | ○ | ○ |
| AI/行为克隆 | ○ | ● | ○(Cloud) | ○ | ○ | ○ |
| 录制→回放 | ● | ● | ● | ● | ● | ● |
| 状态机/场景 | ◐(env) | ● | ● | ◐ | ● | ◐ |
| 多语言 SDK | ◐(Thin) | ● | ●(多) | ○ | — | ◐ |
| 云协作/多租户 | ○ | ● | ●(Cloud) | ○ | ● | ◐ |
| 插件机制 | ●(Java SPI) | ●(WASM) | ●(扩展) | ●(脚本) | — | ◐ |
| 开源/商业 | OSS | OSS | OSS+Cloud | OSS | 商业 | OSS+Cloud |

> **脚注（2026-07-16 二次更新）**：Baafoo 的 GraphQL 维持 ○（仅 HTTP 层匹配糖，无独立协议 handler，用户"已实现"主张不成立）；Kafka/Pulsar/JMS 维持 ●（**Beta 标签已于 2026-07-16 代码清理移除**）。其真实 broker 实现（`KafkaMockBroker`/`PulsarMockBroker`/`JmsMockBroker`）**功能早已超原 Beta 描述**，Beta 标签现已移除。

---

## 六、Baafoo 的差异化护城河（必须守住）

1. **JavaAgent 零代码透明拦截**（市场唯一）：`AgentManifest`+`RouteTable`、`appendToBootstrapClassLoaderSearch`、Bootstrap-safe 四件套——应用**不改一行代码**、自动感知下游依赖。mockforge/Traffic Parrot 都要改连接地址；WireMock/Mountebank 是独立 server。这是 Baafoo 的"非对称优势"。
2. **JVM 内多协议 MQ 挡板**：Kafka + Pulsar + JMS 三件套，且 Pulsar/JMS 是 mockforge 没有的。Traffic Parrot 有 Kafka/JMS 但是商业、配置式。
3. **gRPC 全流式挡板**：Unary/三态流式齐备，且通过 Agent 透明拦截（mockforge 是 server 模式）。
4. **企业内网定位**：鉴权（X-Api-Key）、H2/PostgreSQL、i18n、Web 控制台——契合国内企业交付。

> **补充（2026-07-16 二次更新）**：MQ（Kafka/Pulsar/JMS）源码 Beta 标签已于 **2026-07-16 移除**（`BaafooServer.java` 五处注释/日志已清理，Javadoc 补回 gRPC(9005)），其**真实 broker 实现已构成护城河实质**，交付质量与标签现已一致。

---

## 七、能力差距与优先级路线图（基于 2026-07 现状）

> 原则：**先补"挡板工具桌子上的筹码"（与 Agent 零侵入正交、能直接放大护城河），再追 mockforge 的广度。**

### P0 — 守住并放大护城河（应立即做）
| 项 | 现状 | 动作 | 估时 |
|---|---|---|---|
| **移除 MQ（Kafka/Pulsar/JMS）Beta 源码标签** | ✅ **已完成（2026-07-16）**：`BaafooServer.java` 5 处 Beta 注释/日志已清除，`startProtocolStubServer` 误导性注释已修正，Javadoc 协议清单补回 gRPC(9005)；仅注释/字符串变动、逻辑零改动，QA 静态校验通过 | 清除 `BaafooServer.java` 中 5 处 Beta 注释/日志，补一处文档/注释与实现一致性 | 0.5d（已用） |
| **存储后端补全** | 仅 H2/PG，`FileStorage` 未接入，无 MySQL | 接入 FileStorage（已实现类）、补 MySQL 方言+schema | 2-3d |
| **MQ 协议版本/客户端兼容性收口** | Kafka V9/V12 已拆，Pulsar 2.10 基线 | 按 `analysis-protocol-version-upgrade` 收尾 Pulsar 3.x；JMS 版本声明 | 3-5d |
| **Kafka 故障注入+MQ Relationship 走实** | Kafka 故障已落地 | 串联到 Web 控制台 + 测试闭环 | 2-3d |

### P1 — 补齐"挡板工具"的桌子筹码（高 ROI，与护城河正交）
| 项 | 对标 | 估时 |
|---|---|---|
| **正态分布延迟**（`delayStdDevMs` 字段已预留） | WireMock lognormal / mockforge | 0.5d |
| **部分响应 / 载荷损坏** | mockforge PartialResponse/PayloadCorruption | 1-2d |
| **HTTP 限流（Rate Limit）作为故障** | mockforge `RateLimitConfig` | 1-2d |
| **CLI：`import openapi`/`asyncapi`/`validate`** | 既有 CLI 迁移报告 P0/P3 | 5-7d |
| **请求链路编排（Request Chaining）** | mockforge RequestChain | 3-5d |

### P2 — 追上 mockforge 广度（中期，谨慎投入）
| 项 | 说明 | 估时 |
|---|---|---|
| **WebSocket 挡板（server 模式）** | 既有新协议可行性报告 P0；mockforge 已有 | 8-12d |
| **GraphQL 挡板（独立协议 handler）** | **GraphQL 作为独立协议 handler 仍缺失**：当前仅 HTTP 层匹配糖（`MatchCondition.graphqlOperationName()`/`graphqlOperationType()` + `MatchEngine.extractGraphqlOperationType()` + `RuleApiHandler.java:255-257`），无独立 handler/decoder/端口——但 **GraphQL-over-HTTP 已可经 HTTP 协议 + 这两个匹配条件被挡板（属 HTTP 子集）**。若需独立 GraphQL 解析/校验/WS 订阅等增强能力，需新建独立 handler | 8-10d |
| **混沌弹性：熔断/隔离/流量塑形** | mockforge `CircuitBreaker`/`Bulkhead`/`TrafficShaping`；注意 Baafoo 现有 `CircuitBreaker` 仅用于 Agent 心跳，需新建故障型 | 5-8d |
| **行为克隆 Phase1（无 LLM 概率挡板）** | 既有 AI 迁移报告 Phase1（7.5d）；复用已有录制数据 | 7-8d |
| **Web 插件管理页** | 现有 Java SPI 已就绪，仅缺 UI | 2-3d |

### P3 — 差异化/探索（低优先，按需）
| 项 | 说明 |
|---|---|
| LLM 智能挡板 Phase2/3（跨协议一致性） | 投入大、隐私合规门槛高；建议观望 |
| Full SDK（进程内 stub 引擎） | Thin+Proxy 已覆盖，Full 边际收益低 |
| MQTT/AMQP 协议 | 复杂度高、需求窄（既有报告已定 P2/P3） |
| 云协作/多租户 | 与 Baafoo 企业内网定位冲突，暂不做 |

### 明确不做（避免 mockforge 式功能膨胀）
- 照搬 mockforge 的 **WASM 插件**：Java SPI 对 JVM 生态更自然，且已带隔离 ClassLoader。
- **VBR / Temporal Simulation / Reality Slider / Drift Learning** 等 AI 噱头：ROI 低，先验证行为克隆基础价值。
- **SMTP / FTP / 网关类边缘协议**：非 Baafoo 定位。

---

## 八、战略建议

1. **定位口号**：把 Baafoo 讲成「**给 Java 微服务的零侵入挡板与故障演练平台**」，而非"又一个 mock server"。这是对标 mockforge/WireMock 时最锋利的差异点。
2. **对标话术**：
   - vs mockforge：「它有 AI 和 WebSocket，但你要改地址；Baafoo 不改一行代码就能挡住 Kafka/Pulsar/JMS/gRPC。」
   - vs WireMock：「它 HTTP 生态强，但挡不了你的消息队列；Baafoo 一套 Agent 全协议覆盖。」
   - vs Traffic Parrot：「它做 MQ 但要花钱、要配置；Baafoo 开源、零侵入、还支持 Pulsar。」
3. **投资顺序**：P0（存储+协议收口）巩固交付质量 → P1（延迟分布/部分响应/限流/CLI 导入/链路编排）补齐"桌子筹码" → P2（WS/GraphQL/混沌弹性/行为克隆）扩大覆盖面。任何阶段都**不要牺牲 Agent 零侵入这一核心架构**。
4. **风险预警**：mockforge 迭代极快（15 天前发版、已含 GraphQL/WS/混沌弹性），若 Baafoo 长期只守 MQ 护城河而不补齐"桌子筹码"，会在 HTTP 挡板场景被 WireMock + AI 组合逐步侵蚀心智。

---

## 附：信息来源
- 代码实测：Baafoo 工作区 `baafoo-server`/`baafoo-core`/`baafoo-agent`/`baafoo-plugin-api`/`baafoo-cli`/`sdks`/`web`（2026-07-16）
- 既有分析：`.workmemo/5_review/analysis-mockforge-*.md`（2026-06）
- mockforge：GitHub `SaaSy-Solutions/mockforge`、crates.io `mockforge-core` v0.3.165、`docs.mockforge.dev`
- 竞品：wiremock.org、mountebank、hoverfly、trafficparrot、browserstack/api7 2025 工具对比
- 2026-07-16 二次代码实测复核（GraphQL/MQ Beta 主张核验，结论：两条主张均不成立，按代码事实更新）
- 2026-07-16 代码清理：移除 `BaafooServer.java` 中 MQ Beta 注释/日志 + Javadoc 补 gRPC(9005)，QA 静态校验通过（报告 §1.1/§3/§5/§6/§7 对应 Beta 表述同步更新为"已移除"）
