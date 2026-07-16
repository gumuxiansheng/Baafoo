# Baafoo gRPC 支持评估报告

## 评估结论

**gRPC 支持不完整，存在架构级缺陷。** 当前实现是"能用但不够"的水平，对于生产环境的 gRPC 挡板场景存在明显短板。

---

## 1. 架构概览

Baafoo 的 gRPC 支持分为两个层面：

| 组件 | 职责 | 端口 |
|------|------|------|
| `GrpcStubHandler` | HTTP/1.1 兼容的 Unary gRPC | 9005 |
| `GrpcStreamingHandler` | HTTP/2 流式 gRPC | 10005 (9005+1000) |
| `MatchEngine` | 规则匹配（含 grpcService/grpcMethod） | - |
| `GlobalRouteState` | Agent 端路由状态 | - |

---

## 2. 功能完整性分析

### 2.1 已实现的 gRPC 特性

**✅ 支持：**
- **Unary 调用** - 基本的请求-响应模式
- **gRPC 消息帧解析** - 正确实现 `compressed-flag (1B) + length (4B, big-endian) + message` 格式
- **服务/方法匹配** - `grpcService` 和 `grpcMethod` 条件类型，从路径 `/package.Service/Method` 提取
- **gRPC 状态码** - 通过 `grpc-status` trailer 返回
- **模板渲染** - 支持 `{{requestCount}}` 等模板变量
- **延迟模拟** - `delayMs` 支持
- **HTTP/2 流式基础框架** - `GrpcStreamingHandler` 使用 Netty Http2FrameCodec

### 2.2 缺失的关键特性

**❌ 严重缺失：**

| 缺失特性 | 影响 | 优先级 |
|---------|------|--------|
| **Agent 层 gRPC 拦截** | 没有专门的 gRPC 拦截 Advice，gRPC 流量走通用 TCP/Socket 拦截 | P0 |
| **HTTP/2 原生支持（Unary）** | `GrpcStubHandler` 基于 HTTP/1.1，不是真正的 HTTP/2 | P1 |
| **流式 gRPC 完整实现** | `GrpcStreamingHandler` 的流类型判断逻辑有缺陷 | P1 |
| **gRPC 元数据（Metadata）匹配** | 只能匹配 header，不支持 gRPC 特定的 metadata 语义 | P2 |
| **Protobuf 解析** | 只能匹配 hex/base64 原始字节，无法解析 protobuf 结构 | P2 |
| **gRPC 错误码语义** | 错误码使用混乱（如用 14 UNAVAILABLE 表示 passthrough 不支持） | P3 |

---

## 3. 代码层面问题

### 3.1 架构设计问题

#### 问题 1: Agent 层没有 gRPC 感知
```java
// BaafooAgent.java - installTransforms 方法
// 注册了 HTTP、TCP、Kafka、Pulsar、JMS 的拦截，但没有 gRPC
agentBuilder = agentBuilder
    .type(named("java.net.Socket"))
    .transform(...)
    // ... Kafka, Pulsar, JMS ...
    // 缺少 gRPC 特定的拦截！
```

**影响：** gRPC 流量只能通过底层的 `SocketConnectAdvice` 或 `NioSocketConnectAdvice` 进行 TCP 级别的拦截。这意味着：
- 无法识别 gRPC 协议（只能当普通 TCP 处理）
- 无法提取 gRPC 服务名、方法名进行路由
- 无法对 gRPC 进行协议级别的录制

**建议：** 添加 `GrpcChannelAdvice` 拦截常见的 gRPC 客户端（如 `io.grpc.ManagedChannelBuilder`、`io.grpc.netty.NettyChannelBuilder`），在构建阶段替换 target 地址。

#### 问题 2: HTTP/1.1 伪装 HTTP/2
```java
// GrpcStubHandler.java
// 这个 handler 使用 HTTP/1.1 处理 gRPC，但 gRPC 标准要求 HTTP/2
private static final String CONTENT_TYPE_GRPC = "application/grpc";
// 虽然能处理简单的 Unary 调用，但不符合 gRPC 协议规范
```

**注释自己也承认了：**
```java
/**
 * Note: This implementation uses HTTP/1.1 for simplicity. 
 * For full HTTP/2 support, a future upgrade using Netty Http2FrameCodec is planned.
 */
```

**实际情况：** 已经有了 `GrpcStreamingHandler` 用 HTTP/2，但 Unary 还是走 HTTP/1.1。这导致：
- 标准 gRPC 客户端（使用 HTTP/2）连接 9005 端口可能失败
- 需要客户端降级或特殊配置

#### 问题 3: 流类型判断逻辑错误
```java
// GrpcStreamingHandler.java - determineStreamType
private StreamType determineStreamType(String method, boolean endStream) {
    if (endStream) {
        return StreamType.UNARY;  // ❌ 错误！Server Streaming 也可能 endStream=false
    }
    return StreamType.SERVER_STREAMING;  // ❌ 所有非 endStream 都当成 Server Streaming？
}
```

**问题：**
- gRPC 的流类型应该根据 `:method` 和 `TE: trailers` 头判断，而不是仅靠 `endStream`
- Client Streaming 和 Bidirectional Streaming 无法区分
- 注释说支持三种流式模式，但代码无法正确区分

### 3.2 代码质量问题

#### 问题 4: 重复匹配逻辑
```java
// GrpcStubHandler.java - channelRead0
MatchEngine.MatchResult result = matchEngine.match(
    rules, "grpc", host, port, null,
    "POST", path, headers, null, requestBodyHex);

if (!result.isMatched()) {
    result = matchEngine.match(
        rules, "grpc", host, 0, null,  // ❌ port 改为 0 再匹配一次？
        "POST", path, headers, null, requestBodyHex);
}
```

**问题：** 两次匹配唯一的区别是 `port` 参数（第一次是实际端口，第二次是 0）。这种 fallback 逻辑应该封装在 `MatchEngine` 内部，而不是每个 handler 都写一遍。

**同样的问题在 `GrpcStreamingHandler` 中也存在：**
```java
// processServerStreaming 和 processClientStream 中都有完全相同的重复匹配
```

#### 问题 5: 错误码使用不当
```java
// GrpcStubHandler.java
if (currentMode == EnvironmentMode.PASSTHROUGH || currentMode == EnvironmentMode.RECORD) {
    log.info("Mode {} — gRPC passthrough for: {}", currentMode.getValue(), path);
    sendGrpcError(ctx, 14, "Passthrough not supported for gRPC stub");  // ❌ 14 = UNAVAILABLE
}
```

**问题：** 使用 `UNAVAILABLE (14)` 表示"功能不支持"是错误的，应该使用 `UNIMPLEMENTED (12)`。

#### 问题 6: 魔法数字
```java
// GlobalRouteState.java
if (GlobalRouteState.CURRENT_MODE == 2 || GlobalRouteState.CURRENT_MODE == 3
        || GlobalRouteState.CURRENT_MODE == 4)
```

**问题：** 到处使用数字字面量（2=RECORD, 3=RECORD_AND_STUB, 4=RECORD_ALL），虽然类中定义了常量（`MODE_RECORD` 等），但 Advice 代码中仍然直接使用数字。这在 Bootstrap CL 上下文中可能是故意的（避免引用枚举类），但降低了可读性。

#### 问题 7: 资源泄漏风险
```java
// GrpcStreamingHandler.java - handleData
ByteBuf partial = data.readBytes(data.readableBytes());
streamContext.accumulateData(partial);
// partial 被交给 accumulateData，但 accumulateData 只是 writeBytes，没有 release
```

**问题：** `accumulateData` 方法将 `ByteBuf` 写入 `accumulatedBuffer`，但没有释放传入的 `partial`。如果 `accumulatedBuffer` 是 `Unpooled.buffer()`，这会导致内存泄漏。

#### 问题 8: 线程安全问题
```java
// GrpcStreamingHandler.java
private final Map<Integer, GrpcStreamContext> activeStreams = new ConcurrentHashMap<>();
```

**问题：** 虽然用了 `ConcurrentHashMap`，但 `GrpcStreamContext` 内部的 `accumulatedBuffer` 是 `ByteBuf`，多线程访问同一个 stream 时存在竞争条件。HTTP/2 的 stream 是单线程处理的，但 channelInactive 时 `activeStreams.clear()` 可能与其他操作竞争。

### 3.3 设计模式问题

#### 问题 9: 代码重复
`GrpcStubHandler` 和 `GrpcStreamingHandler` 中有大量重复的代码：
- `bytesToHex` / `hexToBytes` / `isHexString` 方法完全相同
- gRPC 帧构建逻辑重复
- 模板渲染逻辑重复
- 错误发送逻辑重复

**建议：** 提取 `GrpcUtils` 工具类，或创建共享的 `GrpcResponseBuilder`。

#### 问题 10: 不匹配的回退逻辑
```java
// GrpcStreamingHandler.java - processClientStream
if (streamContext.streamType == StreamType.BIDIRECTIONAL) {
    // Echo back for bidirectional if no rule matched
    echoBidirectionalMessages(streamContext);
} else {
    sendGrpcError(streamContext, 5, "No matching rule found");
}
```

**问题：** Bidirectional 流在没有匹配规则时"echo"回所有消息，这个行为：
1. 没有文档说明
2. 可能不是用户期望的（用户可能希望返回错误）
3. 与 Unary/Server Streaming 的行为不一致（它们返回 NOT_FOUND）

---

## 4. 与 HTTP 协议的对比

| 特性 | HTTP 支持 | gRPC 支持 | 差距 |
|------|----------|----------|------|
| Agent 拦截 | ✅ `ConsulHttpAdvice` | ❌ 无 | 大 |
| 协议识别 | ✅ 明确 | ❌ 仅 TCP 层 | 大 |
| 规则匹配 | ✅ 完整 | ✅ 基本 | 小 |
| 录制回放 | ✅ 完整 | ⚠️ 部分 | 中 |
| 流式支持 | ✅ HTTP/1.1 chunked | ⚠️ HTTP/2 框架 | 中 |
| 错误处理 | ✅ 标准 HTTP 状态码 | ⚠️ gRPC 状态码混乱 | 小 |

---

## 5. 改进建议

### 短期（1-2 周）

1. **统一 gRPC 端口处理**
   ```java
   // 在 BaafooAgent 的 installTransforms 中，添加 gRPC 端口到 isInternal 检查
   // 确保 gRPC 流量被正确路由到 9005/10005
   ```

2. **修复流类型判断**
   ```java
   // 根据 gRPC 规范正确判断流类型
   private StreamType determineStreamType(Http2Headers headers, boolean endStream) {
       String te = headers.get("te");
       if (!"trailers".equals(te)) return StreamType.UNARY; // 非 gRPC
       
       // 根据 content-type 和 path 判断
       // Unary: endStream=true on headers (有 body 但立即结束)
       // Server Streaming: endStream=false, 无请求 body
       // Client Streaming: endStream=false, 有请求 body
       // Bidirectional: 类似 Client Streaming
   }
   ```

3. **提取公共工具类**
   - 创建 `GrpcCodecUtils` 包含 `buildGrpcFrame` / `parseGrpcFrame` / `bytesToHex` 等
   - 创建 `GrpcResponseBuilder` 统一响应构建逻辑

### 中期（1 个月）

4. **添加 Agent 层 gRPC 拦截**
   - 拦截 `io.grpc.ManagedChannelBuilder` 的 `forTarget` / `forAddress` 方法
   - 拦截 `io.grpc.netty.NettyChannelBuilder` 的 `build` 方法
   - 在构建阶段替换目标地址为 Baafoo stub 地址

5. **统一 HTTP/2 处理**
   - 将 `GrpcStubHandler` 升级为真正的 HTTP/2 handler
   - 或统一走 `GrpcStreamingHandler` 处理所有 gRPC（包括 Unary）

6. **完善录制支持**
   - 在 `GrpcStreamingHandler` 中集成 RecordingEntry 生成
   - 支持流式消息的逐帧录制

### 长期（3 个月）

7. **Protobuf 支持**
   - 集成 protobuf 解析器，支持根据 message type 匹配
   - 支持 JSON 格式的 protobuf 响应体

8. **gRPC 反射支持**
   - 支持 gRPC Server Reflection 协议
   - 动态生成 proto 描述

---

## 6. 总结

| 维度 | 评分 | 说明 |
|------|------|------|
| 功能完整性 | ⭐⭐⭐☆☆ (3/5) | Unary 基本可用，流式框架有但实现不完整，Agent 层无感知 |
| 代码质量 | ⭐⭐⭐☆☆ (3/5) | 有重复代码、魔法数字、资源泄漏风险，但基本结构清晰 |
| 架构优雅度 | ⭐⭐☆☆☆ (2/5) | HTTP/1.1 伪装 gRPC 是架构妥协，Agent 层缺少协议感知 |
| 生产可用性 | ⭐⭐☆☆☆ (2/5) | 简单场景可用，复杂场景（流式、TLS、负载均衡）不支持 |

**核心问题：** gRPC 在 Baafoo 中被当作"二等公民"处理——Server 层有专门的 handler，但 Agent 层没有对应的拦截 Advice，导致整个 gRPC 支持是"半吊子"。真正的 gRPC 挡板需要在 Agent 层识别 gRPC 协议（通过端口、内容类型或类名），并在 Server 层提供完整的 HTTP/2 支持。
