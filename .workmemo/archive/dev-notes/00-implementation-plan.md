# Baafoo 未实现需求开发计划

> 创建时间: 2026-06-13
> 基于 PRD v2.3 与代码库现状的差距分析

## 实施顺序（按依赖关系和优先级排列）

### Phase 1: Agent 端增强（互相独立，可并行）

| # | 需求 | 优先级 | 复杂度 | 说明 |
|---|---|---|---|---|
| 1 | R-A1: fail-open 配置选项 | P2 | 低 | AgentConfig 添加 failOpen 字段，BaafooAgent premain 异常处理 |
| 2 | R-A4: KafkaConsumer 拦截 | P1 | 中 | 新增 KafkaConsumerAdvice，拦截 KafkaConsumer 构造函数 |
| 3 | R-A5: TDMQ SDK 支持 | P2 | 低 | PulsarClientAdvice 增加对 com.tencent.tdmq 的拦截 |
| 4 | R-A6: JMS ConnectionFactory 拦截 | P1 | 中 | 新增 JmsConnectionFactoryAdvice |
| 5 | R-A8: Consul WebClient 拦截 | P2 | 中 | 新增 ConsulWebClientAdvice，拦截 Spring WebClient/RestTemplate |
| 6 | R-A10: Record 模式字节录制 | P1 | 高 | Socket/NIO Advice 增加 record 模式字节录制逻辑 |

### Phase 2: Server 端 TCP 增强

| # | 需求 | 优先级 | 复杂度 | 说明 |
|---|---|---|---|---|
| 7 | R-S3: TCP 正则匹配 + 多轮交互 | P1 | 高 | TcpStubHandler 增加 regex 匹配和多轮交互状态机 |

### Phase 3: Server 端 Mock Broker（复杂，顺序实施）

| # | 需求 | 优先级 | 复杂度 | 说明 |
|---|---|---|---|---|
| 8 | R-S4: Kafka Mock Broker | P1 | 高 | 实现 Kafka 二进制协议子集 (Metadata/Produce/Fetch) |
| 9 | R-S5: Pulsar Mock Broker | P1 | 高 | 实现 Pulsar binary protocol (Lookup + Producer/Consumer) |
| 10 | R-S6: JMS Mock Broker | P1 | 高 | 嵌入 ActiveMQ Artemis |

### Phase 4: Server 端增强

| # | 需求 | 优先级 | 复杂度 | 说明 |
|---|---|---|---|---|
| 11 | R-S7.7: RBAC 完善 | P2 | 中 | Local bypass、细粒度角色权限校验 |
| 12 | R-S8: 录制数据自动清理 | P2 | 中 | retentionDays/maxSizeMb 定时清理 |
| 13 | R-S9: HAR 导出 | P2 | 低 | 请求日志导出为 HAR 格式 |

### Phase 5: CLI

| # | 需求 | 优先级 | 复杂度 | 说明 |
|---|---|---|---|---|
| 14 | R-S7.6: 交互式 baafoo init | P2 | 中 | 交互式问答引导生成配置 |

## 提交策略

每完成一个需求项提交一次，commit message 格式：
```
feat(scope): 简要描述

详细说明（如需要）
```
