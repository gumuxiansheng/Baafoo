# R-A4: KafkaConsumer 拦截

> 实施时间: 2026-06-13
> PRD 需求: R-A4 AC-02

## 需求描述

拦截 `KafkaConsumer` 构造函数，将 `bootstrap.servers` 替换为 Baafoo Kafka Mock Broker 地址，使 Consumer `poll()` 能收到预设消息序列。

## 实施内容

1. 新增 `KafkaConsumerAdvice` 类，逻辑与 `KafkaProducerAdvice` 一致：
   - 检查环境模式（非 passthrough 才拦截）
   - 检查路由表是否有 kafka 规则
   - 替换 `bootstrap.servers` 为 `GlobalRouteState.SERVER_HOST:KAFKA_PORT`
   - 支持 Properties 和 Map 两种配置参数类型
2. 在 `BaafooAgent.installTransforms()` 中注册 `org.apache.kafka.clients.consumer.KafkaConsumer` 构造函数拦截

## 修改文件

- `baafoo-agent/src/main/java/com/baafoo/agent/advice/KafkaConsumerAdvice.java` — 新增
- `baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java` — 注册 KafkaConsumer 拦截
