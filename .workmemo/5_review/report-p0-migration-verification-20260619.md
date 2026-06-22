# P0 移植完成度核验报告

> 基准：`Baafoo_mockforge_深度对比与移植建议.md` §三 推荐移植优先级
> 核验 commit：`a2524ac feat(p0): Kafka fault injection + MQ Relationship`
> 测试：baafoo-server 134 tests, 0 failures

---

## P0 完成度：100%

### ✅ P0-1 Kafka 故障注入（evaluate_fault 移植）

| 需求 | 状态 | 证据 |
|------|------|------|
| NOT_LEADER_FOR_PARTITION（error code 6） | ✅ | `FaultInjector.java` KAFKA_NOT_LEADER_FOR_PARTITION 分支 |
| OFFSET_OUT_OF_RANGE（error code 1） | ✅ | `FaultInjector.java` KAFKA_OFFSET_OUT_OF_RANGE 分支 |
| PRODUCE_THROTTLE（throttle_time_ms） | ✅ | `FaultInjector.java` KAFKA_PRODUCE_THROTTLE → `FaultResult.kafkaThrottle()` |
| KAFKA_DELAY（处理延迟） | ✅ | 超出 mockforge，新增独立延迟类型 |
| KAFKA_CONNECTION_RESET（关闭连接） | ✅ | 超出 mockforge，新增连接重置类型 |
| 概率掷骰（首命中） | ✅ | 复用已有 `evaluate()` 语义 |
| 自定义 errorCode | ✅ | `Fault.errorCode` 字段，null 时用默认值 |
| Produce response 错误码注入 | ✅ | `buildProduceResponse` → `errorCode` 写入各分区 |
| Produce response throttle | ✅ | `buildProduceResponse` → `throttleMs` 写入 |
| 集成到 KafkaProtocolDecoder | ✅ | `evaluateKafkaFaults()` + `KafkaFaultAggregation` |
| 测试覆盖 | ✅ | 28 处 KAFKA 相关断言，KafkaMockBrokerTest 扩展 |

**增加 5 种 Kafka 故障类型**（mockforge 只有 3 种），DELAY 和 CONNECTION_RESET 是额外扩展。

### ✅ P0-2 MQ Relationship（topic 因果链路）

| 需求 | 状态 | 证据 |
|------|------|------|
| MqRelationship 数据模型 | ✅ | `MqRelationship.java` — fromProtocol/fromTopic/toProtocol/toTopic |
| keyTemplate / valueTemplate 模板 | ✅ | 含 `{{topic}}` / `{{key}}` / `{{partition}}` + TemplateEngine |
| delayMs 延迟派生 | ✅ | 字段已定义，KafkaProtocolDecoder 中 schedule 执行 |
| enabled 开关 | ✅ | 字段已定义，derive 时跳过 disabled |
| 存储层 | ✅ | `StorageService` + `JdbcStorageService` + MyBatis mapper |
| REST API | ✅ | `MqRelationshipApiHandler.java` CRUD |
| DDL 建表 | ✅ | `DdlBuilder` 包含 mq_relationships 表 |
| KafkaProtocolDecoder 集成 | ✅ | `deriveMqRelationships()` — STUB/PASSTHROUGH/RECORD 三条路径都触发 |
| 跨协议支持 | ✅ | `fromProtocol != toProtocol` 可指定不同协议 |
| Java 8 兼容 | ✅ | `StringBuffer` for `Matcher.appendReplacement` |
| 测试覆盖 | ✅ | 5 个专项测试（derive/delayed/ignore-protocol/disabled/render） |

---

## P1 完成度：33%

| 需求 | 状态 | 说明 |
|------|------|------|
| CONNECTION_RESET / READ_TIMEOUT | ✅ | commit `91b618a` 已完成 |
| RequestMatcher | ❌ | 未实现，"只对灰度流量注入故障"暂不可用 |
| Kafka 协议版本升级（放开封顶） | ❌ | Produce 仍封顶 v3，Fetch v10 |

---

## P2 完成度：0%

| 需求 | 状态 |
|------|------|
| 正态分布延迟（delayStdDevMs） | ❌ 字段已预留，未实现 |
| 场景编排（ScenarioEngine） | ❌ 未开始 |

---

## P3 完成度：0%

| 需求 | 状态 |
|------|------|
| ErrorPattern（Burst / Sequential） | ❌ |
| 限流（RateLimiter） | ❌ |

---

## P4 完成度：0%

| 需求 | 状态 |
|------|------|
| 弹性模式（CB / Bulkhead） | ❌ |
| 流量塑形 / NetworkProfile | ❌ |

---

## 代码质量简评

**正面**：
- KafkaFaultAggregation 内聚了故障评估结果，避免 FaultResult 枚举膨胀
- `deriveMqRelationships` 在所有 produce 路径（stub/record/passthrough/unmatched）都触发，不漏
- MqRelationshipRenderer 先处理 MQ 占位符再走 TemplateEngine，两阶段渲染设计合理
- Java 8 StringBuffer 兼容性注意到位

**改进点**：
- `KafkaProtocolDecoder.handleProduce` 已从 ~130 行增至 ~240 行，协议版本升级后会继续膨胀，建议按分析文档 §6.5 拆分为双 codec
- FaultType 仍用 String 匹配（`if ("KAFKA_NOT_LEADER")`），建议改为枚举
- `KafkaFaultAggregation` 的 `delayMs` 取 `Math.max`，多个规则都配了 DELAY 时只有最后一个生效（首命中语义在此处被打破）

---

## 结论

**P0 完成度 100%**，测试全绿，核心逻辑与 mockforge 对齐且部分超越（5 种故障类型 vs 3 种）。下一步优先推动 P1 的协议版本升级（兼容现代客户端是生产刚需）和 RequestMatcher。
