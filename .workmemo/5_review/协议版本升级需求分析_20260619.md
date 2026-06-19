# Baafoo MQ 协议版本兼容性升级 —— 需求分析报告

> 版本：v1.0 | 日期：2026-06-19 | 来源：P0 移植完成度核验 → P1 缺口

---

## 1. 背景与动机

P0 移植（Kafka 故障注入 + MQ Relationship）已完成并通过测试。核验发现 P1 缺口中最关键的一项 —— **协议版本兼容性** —— 直接影响 Baafoo 在生产环境中的可用性：

- Kafka Java 3.x / librdkafka 2.x 客户端连接 Baafoo MockBroker 时会因为 flexible 版本协商失败而断开
- Pulsar 3.0+ 客户端引入了 wire-format 变更，现有 hardcoded 2.10.x codec 不兼容
- 三个协议的版本处理策略各自独立，缺乏统一的版本管理框架

---

## 2. 当前状态

### 2.1 Kafka — 主动封顶 + 保守降级

**代码位置**：`KafkaProtocolDecoder.java`（1342 行），`handleApiVersions()` + `channelRead0()` fallback

**版本声明表**（`supportedApis`）：

| API Key | min | max | 实际协议版本 | 备注 |
|---------|-----|-----|-------------|------|
| Produce (0) | 0 | **3** | v0-v8 | 封顶 v3 阻止 flexible 切换 |
| Fetch (1) | 0 | **7** | v0-v12 | 封顶 v7，v8+ 需要 current_leader_epoch |
| Metadata (3) | 0 | **8** | v0-v9 | 封顶 v8 |
| DescribeConfigs (32) | 0 | **4** | v0-v4 | OK |
| ApiVersions (18) | 0 | **2** | v0-v4 | **封顶 v2 是关键闸门**，阻止 KIP-511 header 切换 |
| JoinGroup (11) | 0 | **7** | v0-v9 | v5+ 需要 protocol_type 解析 |
| SyncGroup (14) | 0 | **5** | v0-v5 | OK |
| Heartbeat (12) | 0 | **4** | v0-v4 | 基本够用 |
| FindCoordinator (10) | 0 | **4** | v0-v5 | 基本够用 |
| LeaveGroup (13) | 0 | **4** | v0-v5 | OK |
| OffsetCommit (8) | 0 | **8** | v0-v9 | OK |
| OffsetFetch (9) | 0 | **8** | v0-v9 | OK |
| ListGroups (16) | 0 | **4** | v0-v5 | OK |
| DescribeGroups (15) | 0 | **5** | v0-v5 | OK |
| InitProducerId (22) | 0 | **1** | v0-v4 | OK |

**灵活的版本降级防御**：`channelRead0()` 中检测 high apiVersion 后 fallback 到固定长度 header 再解析，即客户端请求 v9 Produce 时会先按 flexible header 编码发给 broker，Baafoo 用固定长度重试解析。

**问题**：
1. Produce v3 = Kafka 0.11（2017），无 record header、无 idempotent producer（enable.idempotence=true 需要 v3+）
2. ApiVersions v2 是阻止 flexible 切换的最后防线——如果客户端无论如何尝试 v3+，直接失败
3. 代码注释明确承认了这一点：`"API_VERSIONS max v2 in handleApiVersions to prevent this, but some clients may..."`

### 2.2 Pulsar — 单一版本 hardcode

**代码位置**：`PulsarMockBrokerHandler.java`（733 行）+ `PulsarProtobufCodec.java`（823 行）

**版本处理方式**：

```
CONNECT 请求:
  clientVersion=2.10.2, protocolVersion=19
  → 解析 connectData 中所有字段（field 1-5）
  → CONNECTED 响应: serverVersion="Baafoo-Pulsar-Mock/1.0", protocolVersion=19（回显客户端版本）

所有其他命令:
  → 使用 hardcoded Pulsar 2.10.x lightproto field numbers
  → 无版本分流逻辑
```

**支持的命令**：

| 命令 | 状态 | 备注 |
|------|------|------|
| CONNECT/CONNECTED | ✅ | 回显客户端 protocolVersion |
| PRODUCER/PRODUCER_SUCCESS | ✅ | topic/producerName/requestId |
| SEND/SEND_RECEIPT | ✅ | 含 MessageMetadata + payload |
| SUBSCRIBE/SUCCESS | ✅ | SubType 枚举完整 |
| FLOW/MESSAGE | ✅ | 支持主动推送 |
| LOOKUP/LOOKUP_RESPONSE | ✅ | 返回 self address |
| PARTITIONED_METADATA | ✅ | 返回 partitions=0（non-partitioned） |
| GET_TOPICS_OF_NAMESPACE | ✅ | 从 rules 中返回 topic 列表 |
| PING/PONG | ✅ | 心跳 |
| CLOSE_PRODUCER/CLOSE_CONSUMER | ✅ | 清理连接状态 |
| UNSUBSCRIBE | ⚠️ 部分 | 解析但不完整处理 |
| SEEK | ⚠️ 部分 | 同上 |
| CONSUMER_STATS | ⚠️ 部分 | 同上 |
| AUTH_CHALLENGE/AUTH_RESPONSE | ❌ | 不支持认证 |
| SCHEMA 操作 | ❌ | GetSchema/getOrCreateSchema 不支持 |
| END_OF_TOPIC | ❌ | 不支持 |
| TXN 相关 | ❌ | 不支持事务 |

**问题**：
1. Pulsar 2.10.x lightproto 的 field numbers 与 Pulsar 3.x 的 `PulsarApi.proto` 存在差异
2. Pulsar 3.0 引入了新的命令类型（TcClientConnectRequest 等）
3. `MessageMetadata` 的 protobuf schema 在 Pulsar 2.11+ 增加了新字段（如 `deliver_at_time`）
4. 不支持 Schema Registry，带 Schema 的生产者直接失败
5. 回显客户端 protocolVersion 在 server 端意味着"假装支持任意版本"，但实际 wire format 不变

### 2.3 JMS — 依赖外部 Broker

**代码位置**：`JmsMockBroker.java`（301 行）+ `JmsConnectionFactoryAdvice.java`

**架构**：
```
Agent 拦截 ActiveMQConnectionFactory
  → 替换 brokerURL → tcp://BAFOO_HOST:{port}
  → JmsMockBroker 启动嵌入式 ActiveMQ Artemis
  → OpenWire 协议由 Artemis 自身处理
```

**版本兼容性**：

| 组件 | 版本 | 兼容性 |
|------|------|--------|
| ActiveMQ Artemis (server) | 2.x（Maven 依赖） | 取决于 pom.xml 中 artemis 版本 |
| OpenWire 协议版本 | Artemis 内部协商 | 自动处理 |
| 客户端版本 | 任意 ActiveMQ 5.x / Artemis JMS 客户端 | 通过拦截任意版本 |

**问题**：
1. Artemis 版本是硬依赖（pom.xml），客户端需要兼容对应 OpenWire 版本
2. 如果客户端使用 ActiveMQ 5.x classic（非 Artemis），OpenWire 协议版本不同，存在兼容性问题
3. 无法独立升级 Artemis 版本而不影响 Baafoo 构建
4. `JmsRecordingPlugin` 注册为 Artemis broker plugin，强绑定 Artemis SPI

---

## 3. 客户端兼容性矩阵

### 3.1 Kafka 客户端测试结果矩阵

| 客户端 | 版本 | Produce 版本 | Fetch 版本 | Baafoo 当前 | 问题 |
|--------|------|-------------|-----------|------------|------|
| Java (kafka-clients) | 3.0-3.3 | v9 | v12 | ❌ | flexible header，ApiVersions v2 拦截 |
| Java (kafka-clients) | 2.8 | v8 | v11 | ❌ | Fetch v11 > max v7 |
| Java (kafka-clients) | 2.0-2.4 | v7 | v10 | ⚠️ | Fetch v10 > max v7 |
| Java (kafka-clients) | 0.10-1.1 | v3 | v5 | ✅ | 完全兼容 |
| librdkafka | 2.x | v9 | v12 | ❌ | flexible header |
| librdkafka | 1.x | v7 | v10 | ⚠️ | Fetch v10 > max v7 |
| confluent-kafka-python | 2.x | v9 | v12 | ❌ | lidrdkafka 2.x 底层 |
| Go (sarama) | 1.38+ | v9 | v12 | ❌ | flexible header |

### 3.2 Pulsar 客户端测试结果矩阵

| 客户端 | 版本 | protocolVersion | Baafoo 当前 | 问题 |
|--------|------|-----------------|------------|------|
| Java (pulsar-client) | 3.0+ | 20+ | ⚠️ | field numbers 可能变化，新增命令不识别 |
| Java (pulsar-client) | 2.10-2.11 | 19 | ✅ | 目标兼容基线 |
| Java (pulsar-client) | 2.7-2.9 | 16-18 | ✅ | CONNECT 回显版本，wire 兼容 |
| Java (pulsar-client) | 2.3-2.6 | 14-15 | ⚠️ | lightproto 之前的老格式不兼容 |
| Go (pulsar-client-go) | 0.10+ | 19+ | ⚠️ | 取决于底层 C++ client 版本 |
| C++ (pulsar-client-cpp) | 3.x | 20+ | ⚠️ | 同 Java 3.0+ |

### 3.3 JMS 客户端测试结果矩阵

| 客户端 | 版本 | Baafoo 当前 | 问题 |
|--------|------|------------|------|
| ActiveMQ Artemis JMS | 2.x | ✅ | 与嵌入式 Artemis 版本匹配 |
| ActiveMQ "Classic" 5.x | 5.15-5.18 | ⚠️ | OpenWire 版本协商可能不兼容 |
| Qpid JMS (AMQP) | 2.x | ❌ | 不支持 AMQP 协议 |
| IBM MQ JMS | 9.x | ❌ | 不支持 MQ 专有协议 |
| Spring JMS (Generic) | 5.x-6.x | ✅ | 底层用 ActiveMQ |

---

## 4. 协议版本演进路线图

### 4.1 Kafka 版本演进（KIP 参考）

```
Produce API:
  v0-v2: 基础 Produce，无 timestamp
  v3:    增加 transactional_id（KIP-98, Kafka 0.11, 2017）★ 当前封顶位置
  v4-v5: 增加 record header（KIP-82）
  v6-v7: 增加 transaction marker
  v8:    增加 record_errors（KIP-467）
  v9:    flexible versions（KIP-482, Kafka 2.4, 2019）★ 主要目标

Fetch API:
  v0-v3: 基础 Fetch
  v4:    增加 throttle_time_ms / max_bytes（KIP-74）
  v5:    增加 log_start_offset（KIP-79）
  v7:    增加 session_id / epoch（KIP-227）★ 当前封顶位置
  v8-v9: 增加 current_leader_epoch / rack_id（KIP-320）
  v10:   增加 topic_id（KIP-516, Kafka 2.8, 2021）★ 含 UUID 字段
  v11:   增加 forgotten_topics（KIP-630）
  v12:   flexible versions（KIP-482, Kafka 2.4）★ 主要目标

ApiVersions:
  v0-v2: 基础版本协商 ★ 当前封顶位置
  v3:    flexible versions + 增加 supported_features（KIP-511, Kafka 2.4）★ 突破口
```

### 4.2 Pulsar 版本演进

```
Pulsar 1.x (protocolVersion 0-13):
  - 老式 protobuf format

Pulsar 2.0-2.2 (protocolVersion 14-15):
  - 引入 CommandProducer/CommandSubscribe 等标准命令

Pulsar 2.3-2.6 (protocolVersion 16-18):
  - 增加 CommandGetTopicsOfNamespace / CommandGetSchema
  - 增加 batch message 支持

Pulsar 2.7-2.10 (protocolVersion 19):
  - lightproto 格式（精简版 protobuf schema）★ 当前基线
  - 增加 CommandSeek / CommandGetLastMessageId

Pulsar 2.11 (protocolVersion 19):
  - MessageMetadata 增加 deliver_at_time 字段

Pulsar 3.0+ (protocolVersion 20+):
  - 移除旧式 PulsarApi.proto，全部使用 lightproto
  - Schema Registry 强制要求
  - 引入 TcClientConnectRequest（客户端流量控制）
  - MessageMetadata 增加 broker_entry_metadata
```

### 4.3 JMS / ActiveMQ Artemis 版本演进

```
ActiveMQ Artemis 2.x (2018-2023):
  2.18+ - Java 11+ 要求
  2.20+ - Jakarta EE 迁移
  2.28+ - 当前稳定版

OpenWire 协议:
  v10 - ActiveMQ 5.15-5.16
  v12 - ActiveMQ Artemis 2.x 默认
  版本协商由 Artemis 框架自动完成

Baafoo 不需处理 OpenWire 版本协商，
但需要管理 Artemis 依赖版本与客户端版本匹配。
```

---

## 5. 核心矛盾与风险

### 5.1 Kafka：flexible-versions 是硬骨头

**问题本质**：KIP-482 (flexible versions) 改变了 Kafka 消息帧格式——用 unsigned varint 替代 int32 表示长度，用 compact string（长度 + 内容，无 -1 null marker）替代常规 string。从 ApiVersions v3 开始，客户端收到 `max_version >= flexible_cutoff` 后，后续所有请求都切换到 Request Header v2。

**当前对策**：ApiVersions 响应中 max_version 全部封顶在 flexible_cutoff 以下，让客户端永远不切换。

**问题**：新客户端（Kafka 3.x）根本不支持低版本。kafka-clients 3.0 的 minimum Produce version 是 3，但默认使用 v9。它们期望 broker 至少接受 v8+。

**解决路径**（两条路）：

| 路线 | 工作内容 | 工作量 | 风险 |
|------|---------|--------|------|
| A. 实现 compact string + unsigned varint 编解码 | 120-160 行工具方法 + 所有 handler 适配 | 2-3 人天 | 中：需要全部 handler 回归测试 |
| B. 实现双 codec 分治（mockforge 方案） | 拆出 Flexible 版本 codec，独立文件维护 | 3-4 人天 | 低：改动隔离，但多一倍文件 |

**推荐路线 B**，理由：mockforge 已验证该架构可维护，后续新增 API 版本时只需加文件不改旧代码。

### 5.2 Pulsar：lightproto 版本漂移

**问题本质**：Pulsar 的 protobuf schema 是持续演进的，Baafoo hand-rolled codec 将所有 field numbers 硬编码为 Pulsar 2.10.x 的值。一旦 Pulsar 3.x 改变某个 field number 或增加新的 required field，当前 codec 静默解析错误（protobuf 的 unknown field 语义被忽略）。

**关键增量**：
1. `MessageMetadata` 从 v19→v20 新增 `broker_entry_metadata`（field ~9）
2. `CommandSubscribe` 从 v20 新增 `consumerEpoch`
3. `CommandConnected` 从 v20 可能新增字段
4. Pulsar 3.x 新增 `TcClientConnectRequest` / `TcClientConnectResponse`（新的 BaseCommand type）

**解决路径**：

| 路线 | 工作内容 | 工作量 | 风险 |
|------|---------|--------|------|
| A. 引入 Pulsar protobuf 库依赖 | 替换 hand-rolled codec，用官方 `pulsar-common` 编解码 | 5-7 天 | 高：引入重量级依赖，protobuf 版本冲突风险 |
| B. 版本感知 codec 分叉 | 保留 hand-rolled 但有 v19/v20+ 两套 field number 映射 | 3-4 天 | 中：需要 CONNECT 时记录版本并路由到对应 codec |
| C. 最小可行：仅升级到 v20 field numbers | 更新现有 codec 到 Pulsar 3.x field numbers | 1 天 | 低：但丢失 v19 客户端兼容性（breaking change） |

**推荐路线 C（短期）→ B（中期）**。先确保 Pulsar 3.x 客户端可用，再补版本感知分叉。

### 5.3 JMS：版本升级的耦合风险

**问题本质**：JMS MockBroker 依赖 ActiveMQ Artemis 的嵌入式模式，Artemis 版本通过 Maven 管理。升级 Artemis 版本可能引入：
- API 变更（Class → Jakarta 迁移）
- 配置 API 变更（ConfigurationImpl 的 setter 变化）
- 默认行为变更（如自动创建 queue 的策略）

**当前不构成生产阻塞**：只要 Baafoo 和客户端使用同一 Artemis 大版本（2.x），OpenWire 协议自动兼容。

**解决路径**：
- 短期：维持当前 Artemis 版本，在 `JmsMockBroker.java` 注释中明确支持版本范围
- 中期：抽象 `JmsBrokerProvider` 接口，允许注入不同 Artemis 版本
- 长期：支持 AMQP 1.0 wire protocol，覆盖 Qpid JMS 客户端

---

## 6. 实施计划

### Phase 1：Kafka 协议版本升级（P1，最高优先级，3-5 天）

**目标**：支持 Kafka 2.4+ 客户端（Produce v9 / Fetch v12 / flexible versions）

**技术方案**：路线 B（双 codec 分治）

**文件规划**：

```
baafoo-server/src/main/java/com/baafoo/server/broker/
├── KafkaProtocolDecoder.java        # 主 handler（不变）
├── codec/
│   ├── KafkaFlexibleCodec.java       # 新增：compact string / unsigned varint 工具
│   ├── KafkaProduceCodec.java        # 重构：从 handleProduce 中抽离
│   ├── KafkaProduceCodecV9.java      # 新增：flexible Produce 响应构建
│   ├── KafkaFetchCodec.java          # 重构：从 handleFetch 中抽离
│   ├── KafkaFetchCodecV12.java       # 新增：flexible Fetch 响应构建
│   ├── KafkaMetadataCodec.java       # 重构：从 handleMetadata 中抽离
│   └── KafkaMetadataCodecV9.java     # 新增：flexible Metadata 响应构建
└── broker/
    └── KafkaProtocolVersions.java    # 新增：集中版本声明表
```

**关键任务**：

| 序号 | 任务 | 预估 | 验收标准 |
|------|------|------|---------|
| K-01 | 实现 `KafkaFlexibleCodec`（compact string + unsigned varint 读写） | 0.5 天 | 与 Kafka 协议规范逐字节验证 |
| K-02 | 抽离 `KafkaProduceCodec`（非 flexible，v0-v8） | 0.5 天 | handleProduce 重量从 ~240 行降到 ~60 行 |
| K-03 | 实现 `KafkaProduceCodecV9`（flexible，v9） | 0.5 天 | RecordBatch v2 + flexible response frame |
| K-04 | 抽离 `KafkaFetchCodec` + 实现 `KafkaFetchCodecV12` | 1 天 | v12 需要 topic_id UUID + forgotten_topics |
| K-05 | 实现 `KafkaMetadataCodecV9`（flexible Metadata） | 0.5 天 | 含 ClusterAuthorizedOperations |
| K-06 | 实现 `KafkaProtocolVersions` + 更新 `handleApiVersions` | 0.5 天 | Produce 3→8, Fetch 7→11, Metadata 8→8, ApiVersions 2→2（保留） |
| K-07 | Kafka 客户端兼容性测试矩阵（至少 6 个版本） | 0.5 天 | 全部通过 |
| K-08 | 回归测试（现有 134 测试 + 新增 flexible 专项） | 0.5 天 | 0 failures |

**风险控制**：
- ApiVersions v3 暂不升级（继续封顶 v2），让客户端不切换 flexible header，但接受 Produce v8 / Fetch v11（非 flexible 最高版）
- 这意味着：客户端使用非 flexible 版本发请求 → Baafoo 可以处理到 Produce v8 / Fetch v11 → 覆盖 Java 2.x / librdkafka 1.x 的大部分用例

### Phase 2：Pulsar 协议兼容性升级（P1，2-3 天）

**目标**：支持 Pulsar 3.x 客户端

**技术方案**：路线 C（先升级 v20 field numbers，后续补版本感知分叉）

**关键任务**：

| 序号 | 任务 | 预估 | 验收标准 |
|------|------|------|---------|
| P-01 | 调研 Pulsar 3.x `PulsarApi.proto` vs 2.10.x 的 field number 差异 | 0.5 天 | 形成差异对照表 |
| P-02 | 更新 `PulsarProtobufCodec` field numbers 到 v20 | 0.5 天 | Pulsar 3.0 客户端 CONNECT→CONNECTED→PRODUCER→SEND 全流程通过 |
| P-03 | 实现 `MessageMetadata` v20 新字段的 skip/解析逻辑 | 0.5 天 | broker_entry_metadata 不导致解码错误 |
| P-04 | Pulsar 客户端兼容性测试（2.7 / 2.10 / 3.0） | 0.5 天 | 三个版本全部通过核心流程 |
| P-05 | 在 CONNECT 处理中加入 `protocolVersion` 感知的版本注解（为中期方案打桩） | 0.5 天 | `PulsarSession` 增加 `protocolVersion` 字段 |

### Phase 3：JMS 版本兼容性加固（P2，1-2 天）

**目标**：明确 JMS MockBroker 支持边界，减少误用

**关键任务**：

| 序号 | 任务 | 预估 | 验收标准 |
|------|------|------|---------|
| J-01 | 在 `JmsMockBroker.start()` 中增加版本检测告警日志 | 0.5 天 | Artemis 2.x min/max 版本声明 |
| J-02 | 编写 JMS 客户端兼容性矩阵文档 | 0.5 天 | 覆盖 ActiveMQ 5.x / Artemis 2.x / Spring JMS |
| J-03 | 验证 ActiveMQ 5.x 客户端 OpenWire 协议兼容性 | 0.5 天 | 5.15.x / 5.16.x / 5.18.x 测试通过或明确标注不支持的版本 |
| J-04 | pom.xml 中 Artemis 依赖升级到最新稳定版 | 0.5 天 | 回归测试通过 |

---

## 7. 工作量估算与优先级

| Phase | 协议 | 估时 | 优先级 | 阻塞生产？ |
|-------|------|------|--------|-----------|
| Phase 1 | Kafka | 3-5 天 | **P0-关键** | ✅ 是 — Java 3.x / librdkafka 2.x 完全无法使用 |
| Phase 2 | Pulsar | 2-3 天 | **P1-重要** | ⚠️ 是 — Pulsar 3.x 客户端无法使用 |
| Phase 3 | JMS | 1-2 天 | **P2-改进** | ❌ 否 — 当前版本可用，加固而已 |

**总计**：6-10 人天

---

## 8. 非功能需求

| 类别 | 要求 |
|------|------|
| 向后兼容 | Phase 1/2 完成后，旧客户端（Kafka 0.10 / Pulsar 2.7）仍可用 |
| 测试覆盖 | 每个目标客户端版本至少 1 个集成测试 |
| 文档 | 生成客户端兼容性矩阵文档（含已知不兼容版本列表） |
| 性能 | flexible codec 路径不应增加 >5% 的 CPU 开销 |
| 日志 | 版本协商失败时打印明确的 WARN 日志（含客户端版本号和建议） |

---

## 9. 决策点（需确认）

1. **Kafka ApiVersions v3 是否升级？** 升级意味着客户端切换到 flexible header → 工作量翻倍 → 建议暂不升级，先达到 Produce v8 / Fetch v11 覆盖大部分客户端

2. **Pulsar hand-rolled codec 是否改用官方 protobuf 库？** 引入 `pulsar-common` 会增加 ~10MB 依赖和 protobuf 版本冲突风险 → 建议继续 hand-rolled

3. **JMS 是否支持 ActiveMQ "Classic" 5.x？** OpenWire 协议有差异，Artemis 和 Classic 的协议版本不完全兼容 → 建议明确标注"仅支持 Artemis JMS 客户端"
