# Baafoo 新协议可行性分析：gRPC / WebSocket / MQTT / AMQP

> 分析日期: 2026-06-20
> 参考: mockforge 对应模块实现

---

## 现状回顾

Baafoo 已支持的协议及架构：

| 层 | 已有能力 |
|----|---------|
| **Agent** (拦截层) | ByteBuddy 字节码增强，拦截 `java.net.Socket`/`HttpURLConnection`/Kafka Client/Pulsar Client/JMS API |
| **Server** (Broker 层) | `KafkaMockBroker` + `KafkaProtocolDecoder`、`PulsarMockBroker` + `PulsarProtobufCodec`、`JmsMockBroker` |
| **规则匹配** | `MqMatchHelper` — MQ 协议的通用匹配引擎 |
| **录制** | `RecordingEntry` → `StorageService`，已有 `KafkaMessageStore`/`PulsarMessageStore` |
| **挡板** | `FaultInjector` (HTTP 故障注入) + `KafkaFaultAggregation` (Kafka 故障注入) |

---

## 一、gRPC

### 1.1 mockforge 参考实现

mockforge-grpc 提供了两类模式：

| 模式 | 说明 |
|------|------|
| **Dynamic** | 解析 `.proto` 文件，自动生成 mock service，无需代码生成 |
| **Reflection** | 使用 gRPC Server Reflection 协议 + proxy/forward，支持 .proto 缓存 |

核心能力：
- `ProtoParser` — 运行时解析 .proto 文件
- `ServiceGenerator` — 自动生成 unary/bidi/client/server streaming RPC 的 mock handler
- `HTTP Bridge` — 将 gRPC service 暴露为 REST API（带 OpenAPI 文档）
- `SmartMockGenerator` + `RagSynthesis` — AI 辅助生成 mock 响应
- 支持通过 `DynamicConfig` 的 `proto_dir` 或 `proto_files` 指定 .proto 目录

架构：**直接启动 gRPC server，作为目标 gRPC 服务的替代品。不需要 Agent，是自包含的 server 端 mock**。

### 1.2 Baafoo 实现可行性

#### 方案 A：自建 gRPC Mock Server（低复杂度，推荐）

直接在 Baafoo Server 中启动 gRPC 端口（如 50051），解析 .proto 文件生成 mock service。

```
用户流程：
  1. 提供 .proto 文件（通过 Web Console 上传或指定目录）
  2. Baafoo 解析 .proto → 生成 mock handler
  3. 应用端只需将 gRPC 地址改为 Baafoo Server:50051
  4. 无需修改应用代码，无需 Java Agent
```

**优势**：不依赖 gRPC Java 生态的任何内部 API，纯 server 端实现。
**劣势**：需要引入 gRPC Java server 依赖（io.grpc:grpc-netty-shaded 等），增量 ~3MB。

#### 方案 B：Agent 拦截 gRPC Client（高复杂度，不推荐）

在 Agent 层拦截 `io.grpc.ManagedChannel` 的实现类，注入 ClientInterceptor 来拦截所有 RPC 调用。

**优势**：对应用完全透明，和现有 HTTP/Kafka Agent 模式一致。
**劣势**：
- gRPC 内部使用 HTTP/2 + protobuf 二进制序列化，拦截后需要能够解码 protobuf（在没有 .proto 的情况下只能用 `DynamicMessage`）
- 必须感知 gRPC 的 streaming 语义（服务端流/客户端流/双向流）
- gRPC Java 内部实现类（NettyChannelBuilder 等）不是公开 API，升级风险高

### 1.3 可行性结论

| 维度 | 评估 |
|------|------|
| **技术可行性** | ✅ 可行（方案 A） |
| **复杂度** | 中 — 需要 .proto 解析 + 动态 mock handler 生成 |
| **必需依赖** | io.grpc:grpc-netty-shaded、protobuf-java、protobuf-java-util |
| **核心工作** | ProtoParser（解析 .proto → FileDescriptorProto）、DynamicServiceHandler（响应体用 JSON 序列化返回）、gRPC 生命周期集成到 Baafoo Server |
| **Agent 改造** | 方案 A 不需要 |
| **预计人天** | 10-12 人天（proto 解析 3d + gRPC server 3d + 挡板规则 2d + 测试 2d） |
| **风险点** | proto3 optional / Any / oneof / map 类型的 Mock 响应生成；streaming RPC 的 mock 需要支持流式消息序列 |

### 1.4 优先级

⭐⭐⭐ — gRPC 是微服务通信的事实标准，Java 生态广泛使用。**建议作为下一个协议的首选**。

---

## 二、WebSocket

### 2.1 mockforge 参考实现

mockforge-ws 是一个基于 axum 的 WebSocket server，支持：

| 模式 | 说明 |
|------|------|
| **Replay** | 从 JSONL 文件回放预录制的消息序列 |
| **Interactive** | 根据客户端消息动态响应，支持 JSONPath 匹配 |
| **AI Events** | 基于 LLM 生成叙事驱动的实时事件流 |
| **Proxy** | 转发消息到真实后端，可选消息转换 |

支持延迟模拟（LatencyConfig），内置 tracing。

### 2.2 Baafoo 实现可行性

WebSocket 和 HTTP/REST 有本质不同：
- **持久连接**：一次握手后保持 TCP 连接，双向任意消息
- **帧协议**：WebSocket framing（FIN/opcode/mask/payload）
- **非请求-响应模型**：server 可主动推送

#### 拦截策略

WebSocket 在 Java 中有两个主流入口：

| 入口 | 说明 | 拦截难度 |
|------|------|---------|
| `java.net.http.WebSocket` (Java 11+) | JDK 内置，标准 API | 中 |
| `javax.websocket` / Jakarta WebSocket | 传统 API，Spring 集成 | 中 |
| Spring WebSocket (`WebSocketClient`) | Spring 生态 | 中 |
| OkHttp WebSocket | okhttp3 生态 | 中 |

**核心挑战**：Baafoo Agent 的拦截模型基于"请求 → 响应"的同步/异步模型。WebSocket 是"连接 → 双向消息流"，需要全新的连接生命周期管理。

#### 推荐方案：Server 端 WebSocket Mock Server

```
用户流程：
  1. 在 Baafoo Server 配置中指定 WebSocket 端口（如 3001）
  2. 配置消息匹配规则（JSONPath / 正则）和响应序列
  3. 应用将 WebSocket URL 改为 Baafoo Server
  4. 可选录制模式：Baafoo 作为中间人代理转发 → 真实后端
```

### 2.3 可行性结论

| 维度 | 评估 |
|------|------|
| **技术可行性** | ✅ 可行 |
| **复杂度** | 中高 — 连接生命周期管理 + 双向消息流 + 匹配引擎改造 |
| **Agent 拦截** | ❌ 不推荐 — WebSocket 连接模型与现有 Agent 请求-响应模型不兼容 |
| **Server 端 Mock** | ✅ 推荐 — 启动独立 WebSocket server，规则引擎扩展 |
| **预计人天** | 8-10 人天（WebSocket server 2d + 消息匹配 2d + 录制代理 3d + 测试 1d） |
| **风险点** | 消息时序编排复杂（A→B→C→D 的有序回放）；ping/pong 心跳模拟 |

### 2.4 优先级

⭐⭐ — WebSocket 在实时推送场景常见（行情推送、协作编辑），但总体需求频率低于 gRPC。

---

## 三、MQTT

### 3.1 mockforge 参考实现

mockforge-mqtt 是一个完整的 MQTT 3.1.1 broker 实现：

| 模块 | 功能 |
|------|------|
| `protocol.rs` | MQTT 3.1.1 所有控制包类型编解码（CONNECT/CONNACK/PUBLISH/PUBACK/SUBSCRIBE/SUBACK/UNSUBSCRIBE/PINGREQ/PINGRESP/DISCONNECT） |
| `broker.rs` | 核心 broker 逻辑：消息路由、主题匹配（+ / # 通配符） |
| `qos.rs` | QoS 0/1/2 完整实现（包括 PUBREC/PUBREL/PUBCOMP） |
| `session.rs` | 持久会话管理：订阅恢复、离线消息队列 |
| `topics.rs` | 主题树 + 通配符匹配 |
| `fixtures.rs` | 预置消息加载 |

### 3.2 Baafoo 实现可行性

MQTT 和 Kafka/Pulsar 在协议层面有本质差异：

| 特性 | Kafka | MQTT |
|------|-------|------|
| 协议 | 自定义二进制（flexible 编码逐渐引入） | MQTT 3.1.1 固定头 + 可变头 + payload |
| 连接模型 | 短连接（produce/fetch 后关闭）或长连接 | 长连接，保活心跳 |
| 消息可靠性 | at-least-once（ack 机制） | QoS 0/1/2 三级 |
| 主题 | 普通字符串匹配 | 通配符（`+` 单级、`#` 多级） |
| 会话 | 无持久化 | 持久会话（clean session = 0 时保留订阅和消息） |

#### Agent 拦截难度

Java 中 MQTT 主流客户端是 Eclipse Paho (`org.eclipse.paho.client.mqttv3.MqttClient`)。

| 拦截点 | 说明 |
|--------|------|
| `MqttClient.publish()` | 可拦截，类似 Kafka produce |
| `MqttClient.subscribe()` | 订阅本身不需要 mock，但 Baafoo Agent 需要知道订阅的主题 |
| `MqttCallback.messageArrived()` | **关键拦截点** — 这是服务端推送消息到客户端的回调 |

挑战：和 Kafka Agent 一样，MQTT 也是 agent→server 的模式。但 MQTT 的 QoS 和会话持久化增加了状态管理复杂度。

#### 推荐方案：Server 端 MQTT Mock Broker（类似 Kafka 模式）

```
Agent 层：
  - 拦截 MqttClient.publish() → 转发到 Baafoo Server
  - 拦截 MqttCallback.messageArrived() → 注入 mock 消息

Server 层：
  - MqttMockBroker：完整 MQTT 3.1.1 协议实现
  - MqttMessageStore：消息存储
  - 挡板规则：主题通配符匹配 + QoS 感知

和 Kafka 的架构高度一致，可以复用 MqMatchHelper/MqRelationshipRenderer
```

### 3.3 可行性结论

| 维度 | 评估 |
|------|------|
| **技术可行性** | ✅ 可行 |
| **复杂度** | 高 — 完整 MQTT 协议实现 + QoS 0/1/2 + 持久会话 |
| **Agent 改造** | 需要新增 `MqttAdvice`（~300 行） |
| **Server 改造** | 需要新增 `MqttMockBroker` + `MqttProtocolDecoder`（~2000 行） |
| **预计人天** | 15-18 人天（协议实现 8d + Agent 2d + 规则匹配 2d + 测试 3d） |
| **风险点** | QoS 2 的 4 步握手（PUBLISH→PUBREC→PUBREL→PUBCOMP）在 mock 模式下如何简化；TLS/WebSocket 作为 MQTT transport 的支持 |

### 3.4 优先级

⭐ — IoT 场景专用协议，Java 后端开发中需求频率低。**除非有明确的物联网/车联网场景需求，不建议优先投入**。

---

## 四、AMQP 0.9.1（RabbitMQ）

### 4.1 mockforge 参考实现

mockforge-amqp 是一个完整的 AMQP 0.9.1 broker：

| 模块 | 功能 |
|------|------|
| `protocol.rs` | AMQP 0.9.1 帧编解码（frame header + method header + content header + body） |
| `connection.rs` | Connection.Start/Tune/Open/Close 握手流 |
| `channel.rs` | channel 多路复用 |
| `exchanges.rs` | direct/fanout/topic/headers 四种 exchange 类型 |
| `queues.rs` | 队列管理（TTL、死信队列、max-length） |
| `bindings.rs` | exchange-queue binding + routing key 匹配 |
| `consumers.rs` | 消费者管理 + ack/reject/nack |
| `messages.rs` | 消息持久化 + publisher confirm |

### 4.2 Baafoo 实现可行性

AMQP 是这些协议中**协议最复杂的**，主要问题：

| 复杂度来源 | 说明 |
|-----------|------|
| **帧协议** | 三层帧结构（frame header + method/content/header/body frames），channel 多路复用 |
| **连接协商** | Connection.Start→StartOk→Tune→TuneOk→Open→OpenOk 9 步握手 |
| **类方法体系** | AMQP 定义了大量 class.method（40+ 种），每种有不同参数 |
| **Exchange 路由** | 4 种 exchange 类型的路由逻辑各不同 |
| **消费确认** | ack/reject/nack + requeue + prefetch count |
| **事务与确认** | tx.select/tx.commit/tx.rollback + publisher confirm（异步确认） |

#### Java 客户端生态

RabbitMQ Java Client (`com.rabbitmq:amqp-client`) 是最主流的客户端。

| 拦截点 | 说明 |
|--------|------|
| `Channel.basicPublish()` | 发布消息 |
| `Channel.basicConsume()` | 注册消费者 |
| `Consumer.handleDelivery()` | **关键拦截点** — 消息投递回调 |

#### 推荐方案

**Server 端 AMQP Mock Broker**。Agent 拦截方式和 Kafka/MQTT 一致：
- 拦截 `basicPublish()` → 转发到 Baafoo Server
- 拦截 `Consumer.handleDelivery()` → 注入 mock 消息

但在 Server 端实现完整 AMQP 0.9.1 broker **工作量大**。

**可选的简化策略**：
- 仅实现 AMQP 0.9.1 的子集：basic.publish / basic.consume / basic.ack / basic.nack / basic.qos
- 不做真正的队列持久化和事务
- Exchange 路由只支持 direct 和 topic（占 90% 使用场景）

### 4.3 可行性结论

| 维度 | 评估 |
|------|------|
| **技术可行性** | ⚠️ 可行但工作量大 |
| **复杂度** | 极高 — 完整的 AMQP 0.9.1 broker 相当于一个小型 RabbitMQ |
| **完整实现** | 25-30 人天 |
| **简化实现** | 12-15 人天（仅 basic.publish/consume/ack + direct/topic exchange） |
| **Agent 改造** | 需要新增 `AmqpAdvice`（~400 行） |
| **风险点** | 简化实现可能导致部分客户端库行为不兼容；channel 多路复用的线程安全 |

### 4.4 优先级

⭐ — 复杂度最高，收益最小。在 Kafka 已经覆盖大部分消息队列场景的情况下，AMQP 的需求明确性很低。

---

## 五、综合评估

### 5.1 宏观对比

```
协议       | 技术可行性 | 复杂度 | 人天    | 优先级 | 理由
-----------|----------|--------|---------|--------|------
gRPC       | ✅ 高    | 中     | 10-12   | ⭐⭐⭐  | 微服务标配，Agent 可选
WebSocket  | ✅ 中    | 中高   | 8-10    | ⭐⭐    | 实时推送场景
MQTT       | ✅ 中    | 高     | 15-18   | ⭐     | IoT 专用，受众窄
AMQP(RMQ)  | ⚠️ 中低  | 极高   | 12-15+  | ⭐     | 复杂度最高，Kafka 已覆盖
```

### 5.2 建议的协议扩展优先级

```
P0: gRPC (server-side mock, no Agent)         ← 现在就可以开始
P1: WebSocket (server-side mock)               ← gRPC 完成后
P2: MQTT (Agent + Server, 参考 Kafka 模式)     ← 有明确需求时
P3: AMQP/RabbitMQ (简化实现)                    ← 最后考虑
```

### 5.3 架构共性抽象

随着协议增多，建议抽取一个通用的 **Protocol Broker 框架**：

```java
/**
 * Baafoo 协议 Broker 抽象。
 * 所有新协议实现此接口即可融入 Baafoo Server。
 */
public interface ProtocolBroker {
    String protocolName();                    // "kafka" / "grpc" / "mqtt" / ...
    int defaultPort();                        // 9092 / 50051 / 1883 / ...
    void start(int port, BaafooConfig config); // 启动
    void stop();                              // 停止
    List<Map<String, Object>> getStats();     // 监控统计
    boolean supportRecording();               // 是否支持录制
    boolean supportFaultInjection();          // 是否支持故障注入
}
```

当前 `KafkaMockBroker`、`PulsarMockBroker`、`JmsMockBroker` 应逐步统一到此接口。

### 5.4 与 mockforge 的关键差异

mockforge 对所有协议均采用 **自建 server** 模式（不需要 Agent），Baafoo 的核心差异在于：

| | mockforge | Baafoo |
|---|----------|--------|
| 入口 | CLI 启动 server | Java Agent 透明拦截 + Server |
| 优势 | 零侵入，任何语言都能用 | 对 Java 应用零配置，自动感知下游依赖 |
| 劣势 | 需要改连接地址 | 仅限 JVM 语言 |
| gRPC 推荐 | 自建 server | 自建 server（Agent 不划算） |
| MQ 推荐 | 自建 broker | Agent + Broker（复用现有 Kafka 模式） |

### 5.5 总体投入估算

| 协议 | 方案 | 人天 | 阶段 |
|------|------|------|------|
| gRPC | Server-side mock | 10-12 | Phase 1 |
| WebSocket | Server-side mock | 8-10 | Phase 2 |
| MQTT | Agent + Broker | 15-18 | Phase 3 |
| AMQP | 简化 Broker + Agent | 12-15 | Phase 4 |
| 协议框架抽象 | 统一 ProtocolBroker | 3 | 随 Phase 1 推进 |
| **合计** | | **48-58** | |

---

## 六、附录：Java 依赖清单

### gRPC

```xml
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty-shaded</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-protobuf</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-stub</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java-util</artifactId>
    <version>3.25.3</version>
</dependency>
```

### WebSocket (JDK 内置或)

```xml
<!-- JDK 11+ java.net.http.WebSocket 无需额外依赖 -->
<!-- 或使用 Tyrus 作为 server 端实现 -->
<dependency>
    <groupId>org.glassfish.tyrus</groupId>
    <artifactId>tyrus-server</artifactId>
    <version>2.1.5</version>
</dependency>
```

### MQTT

```xml
<!-- 仅用于协议编解码参考，不直接依赖 Paho -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-codec-mqtt</artifactId>
    <version>4.1.110.Final</version>
</dependency>
```

### AMQP

AMQP 协议从零实现，无直接第三方库依赖（参考 RabbitMQ amqp-client 的协议实现）。
