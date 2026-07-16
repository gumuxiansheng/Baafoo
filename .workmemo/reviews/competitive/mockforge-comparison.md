# Baafoo vs mockforge 深度对比与移植建议

> 背景：Baafoo Phase 1 故障注入（HTTP_ERROR + DELAY）已上线。本文对比 mockforge（Rust）能力，提出系统性的移植优先级建议。

---

## 一、现状总览

| 维度 | Baafoo（Java + JavaAgent） | mockforge（Rust） |
|------|--------------------------|-------------------|
| 故障注入范围 | HTTP（Phase 1） | HTTP + Kafka + AMQP |
| 故障类型 | HTTP_ERROR, DELAY | HttpError, ConnectionError, Timeout, PartialResponse, PayloadCorruption |
| 注入模式 | probability 掷骰（无状态） | Random + Burst + Sequential（有状态） |
| MQ 挡板 | Kafka / Pulsar / JMS（stub，无故障注入） | Kafka / AMQP（完整协议 + 故障注入） |
| Topic 关系 | 无 | Relationship（from/to topic 派生） |
| 协议版本 | 保守封顶（Produce v3 / Fetch v10） | 完整覆盖（Produce v3-v9 / Fetch v4-v12） |
| 架构 | Agent-Server，per-rule 注入 | 分层 Cargo workspace + 全局 ChaosMiddleware |

---

## 二、移植价值矩阵

| 优先级 | 模块 | 依据 | 预估 |
|--------|------|------|------|
| **P0** | Kafka 故障注入（evaluate_fault） | Phase 3 核心，mockforge 三维匹配设计可直接复用 | 2-3 天 |
| **P0** | MQ Relationship（topic 因果链路） | MQ 挡板从"单 topic stub"升级为"链路模拟"的质变能力 | 3-5 天 |
| **P1** | RequestMatcher（故障条件过滤） | "只对灰度流量注入"的刚需，复用已有 MatchCondition 体系 | 1-2 天 |
| **P1** | CONNECTION_RESET / READ_TIMEOUT | Phase 2 明确计划，mockforge 有现成实现 | 1 天 |
| **P1** | Kafka 协议版本升级（放开封顶） | 兼容现代客户端（Java 3.x / librdkafka 2.x）的生产需求 | 3-5 天 |
| **P2** | 正态分布延迟（delayStdDevMs） | 字段已预留，实现简单，mockforge 有完整设计 | 0.5 天 |
| **P2** | 场景编排（ScenarioEngine） | 混沌实验从"手动配规则"升级为"一键启动预定义实验" | 5-7 天 |
| **P3** | ErrorPattern（Burst / Sequential） | 需改 FaultInjector 为有状态，Phase 3 一起做 | 2-3 天 |
| **P3** | 限流（RateLimiter） | stub broker 稳定性保障，非混沌核心 | 1-2 天 |
| **P4** | 弹性模式（CB / Bulkhead / Retry） | 约 1900 行，高成本，建议独立 PRD | 2-3 周 |
| **P4** | 流量塑形 / NetworkProfile | 低 ROI，DELAY 故障已覆盖主要场景 | 3-5 天 |

---

## 三、P0 模块详解

### 3.1 Kafka 故障注入（evaluate_fault）

**mockforge 核心设计**：`broker.rs` 的 `evaluate_fault(topic, partition, kind)` 按三维匹配 + 概率掷骰：

```
ProduceThrottle   → Throttle { ms }     → produce 前 sleep
ProduceNotLeader  → Error { code: 6 }  → NOT_LEADER_FOR_PARTITION
OffsetOutOfRange  → Error { code: 1 }  → OFFSET_OUT_OF_RANGE
```

**移植方案**：

```java
// 新增 KafkaFaultConfig.java
public class KafkaFaultConfig {
    private List<KafkaFaultRule> faults;

    public static class KafkaFaultRule {
        private String topic;           // null = 所有 topic
        private Integer partition;     // null = 所有分区
        private KafkaFaultKind kind;   // PRODUCE_THROTTLE / NOT_LEADER / OFFSET_OUT_OF_RANGE
        private Double probability;    // null = 1.0
        private Long delayMs;           // THROTTLE 专用
    }

    public enum KafkaFaultKind {
        PRODUCE_THROTTLE, NOT_LEADER, OFFSET_OUT_OF_RANGE
    }
}

// KafkaProtocolDecoder.handleProduce 中插入：
KafkaFaultOutcome outcome = KafkaFaultEvaluator.evaluate(config, topic, partition, KafkaFaultKind.PRODUCE);
switch (outcome.getAction()) {
    case THROTTLE -> ctx.executor().schedule(() -> processProduce(...), outcome.getDelayMs(), ...);
    case ERROR    -> ctx.writeAndFlush(buildProduceError(correlationId, outcome.getErrorCode()));
    case NONE     -> { /* 正常流程 */ }
}
```

### 3.2 MQ Relationship（topic 因果链路）

**mockforge 核心设计**：`fixture_executor.rs` 的 `on_produced_records` 热路径——每次 Produce 后扫描 relationship 列表，匹配 `from_topic` 则渲染模板向 `to_topic` 派生消息。

**移植方案**：

```java
// 新增 MqRelationship.java
public class MqRelationship {
    private String fromProtocol;   // "kafka" / "pulsar" / "jms"
    private String fromTopic;
    private String toProtocol;
    private String toTopic;
    private String keyTemplate;   // "{{source.key}}-derived"
    private String valueTemplate; // "{{source.value}}" / "{{faker.*}}"
    private long delayMs;
    private boolean enabled;
}

// KafkaProtocolDecoder.handleProduce，存储消息后调用：
for (MqRelationship rel : storage.listRelationships()) {
    if (!rel.isEnabled()) continue;
    if (!rel.getFromProtocol().equals("kafka")) continue;
    if (!rel.getFromTopic().equals(topic)) continue;
    byte[] derivedKey   = render(rel.getKeyTemplate(), msg.key, fakerSeed);
    byte[] derivedValue = render(rel.getValueTemplate(), msg.value, fakerSeed);
    messageStore.append(rel.getToTopic(), 0, derivedKey, derivedValue);
}
```

价值：让"订单创建 → 库存扣减 → 支付通知"这类跨 topic 链路测试成为可能，ROI 极高。

---

## 四、协议版本兼容性

### 现状对比

| API | mockforge | Baafoo | 差距 |
|-----|-----------|--------|------|
| Produce | v3–v9（双 codec） | v0–v3（单 handler，保守封顶） | 6 个版本 |
| Fetch | v4–v12（双 codec） | v0–v10（单 handler） | 2 个版本 |
| Metadata | v0–v9 | v0–v8 | 1 个版本 |
| ApiVersions | v0–v4 | v0–v2 | 2 个版本 |

**Baafoo 问题**：Produce 封顶 v3（2017 年 Kafka 0.11 水平），Java 3.x / librdkafka 2.x 客户端握手后尝试发 v9，连接失败。

### 改进方案

**短期**：拆分 `KafkaProduceCodec.java`（v0–v8）与 `KafkaProduceCodecV9.java`（v9+ flexible），将 Produce max_version 从 3 提升至 8。同时：

```java
// 放开版本封顶（随 codec 实现进度逐步上探）
{PRODUCE, 0, 8}    // v9 flexible 待实现
{FETCH, 0, 11}     // v12 flexible 待实现
```

**长期**：建立 `KafkaProtocolVersions.java` 集中管理 API 版本声明表，与 codec 实现进度解耦：

```java
static Map<Integer, VersionRange> SUPPORTED = Map.of(
    0,  new VersionRange(0, 8),   // Produce
    1,  new VersionRange(0, 11),  // Fetch
    3,  new VersionRange(0, 8),   // Metadata
    18, new VersionRange(0, 2),   // ApiVersions
);
```

---

## 五、架构差异与策略

**不做**：
- 照搬 mockforge 的 `ChaosMiddleware` 全局管道——Baafoo 的 per-rule 注入更灵活，坚持
- 照搬 mockforge 的 `CircuitBreaker` 中间件实现——在 JavaAgent 拦截层做更自然

**要做**：
- 借鉴 mockforge 的具体能力模块（evaluate_fault、Relationship、RequestMatcher）
- 借鉴 mockforge 的双 codec 分治架构处理协议版本
- 借鉴 mockforge 的 `scenario` 聚合层思路，在 Rule 之上建立批量管理

---

## 六、结论

Baafoo Phase 1 基座扎实（无状态评估器 + 首命中语义 + 25 个测试），但在故障注入深度、MQ 覆盖广度、协议兼容性三个维度均落后于 mockforge。

**最值得优先移植的两个模块**：
1. **Kafka evaluate_fault**（P0）：Phase 3 骨架，设计简洁，直接复用
2. **MQ Relationship**（P0）：MQ 挡板的质变能力，mockforge 的热路径设计值得借鉴

**协议版本**应作为 P1 独立推进，放开封顶 + 双 codec 拆分是正确路径。
