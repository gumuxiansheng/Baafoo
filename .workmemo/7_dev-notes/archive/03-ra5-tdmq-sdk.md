# R-A5: TDMQ SDK 支持

> 实施时间: 2026-06-13
> PRD 需求: R-A5 AC-01

## 需求描述

同时覆盖 `org.apache.pulsar:pulsar-client`（Apache 官方 SDK）和 `com.tencent.tdmq:tdmq-client`（腾讯云封装 SDK）。

## 分析

TDMQ for Pulsar 使用与 Apache Pulsar 完全相同的二进制协议和客户端 API。TDMQ SDK 的 `PulsarClient.builder()` 返回的 `ClientBuilder` 实现类是 `org.apache.pulsar.client.impl.PulsarClientBuilder`（与 Apache Pulsar 相同），因为 TDMQ SDK 本质上是 Pulsar 客户端的封装。

## 实施内容

1. 扩展 Pulsar 拦截的类匹配范围，同时匹配：
   - `org.apache.pulsar.client.api.ClientBuilder`（接口）
   - `org.apache.pulsar.client.impl.PulsarClientBuilder`（实现类，TDMQ SDK 也使用此类）
2. 使用 ByteBuddy 的 `.or()` 组合匹配器

## 修改文件

- `baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java` — 扩展 Pulsar 类匹配范围
