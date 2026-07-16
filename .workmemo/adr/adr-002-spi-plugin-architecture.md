# ADR-002: SPI 插件架构 vs 硬编码拦截

- **状态**: Accepted
- **日期**: 2026-06-20
- **关联**: `baafoo-plugin-api/`、`PluginManager.java`、`10_deliverables/plugin_architecture_enhancement_phase1-3_design.md`

## 背景

Baafoo 最初设计为微内核 + SPI 插件架构，所有协议拦截通过插件实现。但实际开发中，Socket/NIO/Kafka/gRPC 等核心协议的拦截逻辑被直接硬编码在 Advice 类中，PluginManager 被标注 `@Deprecated`。

## 选项

| # | 方案 | 优点 | 缺点 |
|---|------|------|------|
| A | 激活 SPI：所有 Advice 通过 PluginManager 咨询 | 可扩展、符合微内核设计 | 改动大、ByteBuddy 内联限制 |
| B | 删除 SPI：承认是硬编码架构 | 简单诚实 | 失去扩展性 |
| C | 混合：核心协议硬编码 + 非核心走 SPI | 务实、渐进 | 架构不纯粹 |

## 决策

选择 **方案 C**（混合方案）。核心协议（Socket/NIO/Kafka/Pulsar/JMS/gRPC）保持硬编码 Advice + GlobalRouteState 桥接函数；PluginManager 取消 `@Deprecated`，用于非核心扩展（如自定义协议、监控插件）。

## 理由

- 方案 A 的全 SPI 化受 ByteBuddy 内联约束：Bootstrap CL Advice 无法直接调用 App CL 的 PluginManager
- 方案 B 放弃了已投入的 SPI 基础设施和文档
- 方案 C 保留了扩展点，同时不破坏已验证的核心拦截链路
- `PLUGIN_CONSULT_FN_EXT` 桥接函数已支持 `{action, host, port, reason}` 返回值

## 后果

- PluginManager 不再 `@Deprecated`，需补充集成测试
- 文档需明确区分"核心协议（硬编码）"和"扩展协议（SPI）"
- 详见架构改进 P1-4
