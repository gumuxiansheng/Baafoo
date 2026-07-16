# ADR-003: gRPC 拦截策略 — GrpcChannelAdvice

- **状态**: Accepted
- **日期**: 2026-06-24
- **关联**: `GrpcChannelAdvice.java`、`GrpcUnifiedHandler.java`、`decisions/grpc-fix-design-20260624.md`

## 背景

PRD v2.4 非目标 N3 明确排除 gRPC，理由是"HTTP/2 多路复用字节码拦截复杂度显著高于 HTTP/1.1"。但实际需求推动 gRPC 支持，需要选择拦截策略。

## 选项

| # | 方案 | 优点 | 缺点 |
|---|------|------|------|
| A | 拦截 ManagedChannelBuilder.build() | 入口单一 | 无法感知具体 RPC 调用 |
| B | 拦截 io.grpc.ClientInterceptors | 可感知调用 | 需应用使用 Interceptor 注册 |
| C | 拦截 ManagedChannel.newCall() | 底层统一入口 | gRPC 内部实现复杂 |
| D | 拦截 io.grpc.internal.ManagedChannelImpl | 构造时注入 | 依赖内部 API |

## 决策

选择 **方案 A + 增强**：拦截 `ManagedChannelBuilder.build()`，在构建时将 Channel 的 target 重定向到 Baafoo Server 的 gRPC 端口。同时通过 `GrpcUnifiedHandler` 在 Server 端处理 Unary/Server Streaming/Client Streaming/Bidi Streaming（辅助类：`GrpcPassthroughForwarder`、`GrpcResponseBuilder`）。

## 理由

- 方案 A 入口最统一（所有 gRPC 客户端都经过 `ManagedChannelBuilder`）
- 重定向 target 而非拦截方法调用，与 HTTP/TCP 的拦截模式一致
- `GrpcUnifiedHandler` 统一处理 4 种 RPC 模式，避免 4 套独立 handler
- PRD N3 非目标已在 v2.5 中修订为"已超越"

## 后果

- gRPC 拦截依赖 `ManagedChannelBuilder` 的 ByteBuddy 增强，需确保类可见性
- Server 端需监听独立 gRPC 端口（默认 9005）
- 详见 `decisions/grpc-fix-design-20260624.md`（31KB 完整设计文档）
