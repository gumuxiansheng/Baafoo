# Baafoo gRPC 修复设计文档

> 产出时间：2026-06-24 ~21:30 GMT+8
> 目标：修复所有 gRPC 架构缺陷，使 gRPC 在 Agent + Server 两层完整可用、代码优雅。

---

## 一、当前缺陷清单（按严重度排序）

| # | 缺陷 | 层次 | 严重度 |
|---|------|------|--------|
| D1 | Agent 层无 gRPC 感知，gRPC 流量仅走通用 Socket/NIO TCP 拦截，无法提取 service/method 做协议级路由 | Agent | P0 |
| D2 | `GrpcStubHandler` 用 HTTP/1.1 伪装 gRPC，标准 gRPC 客户端（HTTP/2 only）连接 9005 端口会握手失败 | Server | P0 |
| D3 | `GrpcStreamingHandler.determineStreamType` 仅靠 `endStream` 判断，把所有非 unary 都当 SERVER_STREAMING，CLIENT_STREAMING 和 BIDIRECTIONAL 无法区分 | Server | P0 |
| D4 | `GrpcStreamingHandler.handleData` 中 `ByteBuf partial` 传入 `accumulateData` 后未 release，造成内存泄漏 | Server | P1 |
| D5 | `GrpcStubHandler` 和 `GrpcStreamingHandler` 重复 `bytesToHex/hexToBytes/isHexString/buildGrpcFrame/parseGrpcFrame` 等工具方法 | Server | P1 |
| D6 | 两个 Handler 中重复的"port=0 fallback"匹配逻辑应下沉到 MatchEngine | Server/Core | P1 |
| D7 | `GrpcStubHandler` 使用 `UNAVAILABLE(14)` 表示"passthrough 不支持"，应使用 `UNIMPLEMENTED(12)` | Server | P2 |
| D8 | `GrpcStreamingHandler.processClientStream` 在 bidirectional 无规则时 echo 消息，行为未文档化且与其他流类型不一致 | Server | P2 |
| D9 | `GrpcStreamingHandler.activeStreams.clear()` 在 channelInactive 执行，与并发 stream 处理存在竞态（虽然 Netty 单线程，但 `ConcurrentHashMap` 非必要） | Server | P2 |
| D10 | GlobalRouteState 中 `forceRedirectPort` 对 gRPC 端口统一返回 `GRPC_STREAMING_PORT`，未区分 unary/streaming，且 `isInternal` 已覆盖 `GRPC_PORT` | Agent | P2 |

---

## 二、修复方案

### 2.1 D1 — Agent 层 gRPC 感知

**方案**：新增 `GrpcChannelAdvice`，拦截 gRPC 客户端构建入口，在 channel 构建阶段替换 target 地址。

```
拦截点（ByteBuddy advice）：
  io.grpc.ManagedChannelBuilder.forTarget(String)  → 静态方法
  io.grpc.ManagedChannelBuilder.forAddress(String, int)  → 静态方法
  io.grpc.netty.NettyChannelBuilder.build()  → 实例方法
```

**设计要点**：
- `GrpcChannelAdvice` 在 `OnMethodEnter` 阶段读取 `GlobalRouteState.lookup(host, port)`，若命中路由则替换参数为 Baafoo stub 地址
- 对 `forTarget`：解析 `host:port` 格式的 target 字符串，替换后重新拼装
- 对 `forAddress`：直接替换 host/port 参数
- 需要同步到 Bootstrap ClassLoader（与 SocketConnectAdvice 同机制）
- **不在 Agent 层做 HTTP/2 分帧**——只做地址替换，让 gRPC 流量自然走到 Baafoo Server 的 HTTP/2 端口

**为什么不拦截 Socket 层做 HTTP/2 解析**：
1. ByteBuddy advice 内联到 Bootstrap CL，无法引用 `io.grpc.*`（App CL 类）
2. gRPC 客户端用的是 Netty NIO SocketChannel，已被 `NioSocketConnectAdvice` 拦截到 stub 端口
3. 缺的只是"按 service/method 路由"能力，这个在 Server 层的 HTTP/2 handler 里做更自然

**补充**：对 `GlobalRouteState.forceRedirectPort` 的 gRPC 端口判断，区分 unary（`GRPC_PORT`）和 streaming（`GRPC_STREAMING_PORT`）：
- 端口 50051 → `GRPC_PORT`（unary 默认走 9005，升级后 9005 也是 HTTP/2）
- 端口 50052/9090 → `GRPC_STREAMING_PORT`

**实际上统一走 HTTP/2 后，9005 端口可以处理所有 gRPC 调用**（包括 streaming），10005 端口可作为专用 streaming 端口保留但不再是必须。见 D2 修复。

---

### 2.2 D2 — `GrpcStubHandler` 升级为 HTTP/2

**方案**：废弃 HTTP/1.1 的 `GrpcStubHandler`，统一由 HTTP/2 handler 处理所有 gRPC 调用（unary + streaming）。

**新架构**：
```
端口 9005 (gRPC):
  Http2FrameCodec → Http2MultiplexHandler → GrpcUnifiedHandler

端口 10005 (gRPC streaming, 可选保留):
  同上（独立 EventLoop 或共享）
```

**`GrpcUnifiedHandler`** 替代 `GrpcStubHandler` + `GrpcStreamingHandler`：
- 继承 `ChannelInitializer<Channel>`，initChannel 中安装 `Http2FrameCodec` + `Http2MultiplexHandler`
- 内部 `GrpcStreamHandler extends ChannelInboundHandlerAdapter` 处理所有 HTTP/2 stream
- 正确解析 HEADERS frame 的 `:path`、`:method`、`content-type`、`te`、`grpc-encoding`
- **所有 gRPC 调用（unary/server streaming/client streaming/bidirectional）都走这一个 handler**

**对 BaafooServer 的改动**：
- `startGrpcStubServer(port)` 改为只启动一个 HTTP/2 server（不再启动 HTTP/1.1 + HTTP/2 两个）
- 端口 10005 可选保留作为"专用 streaming 端口"用于配置兼容，但实际处理逻辑相同
- `config.getPortForProtocol("grpc")` 返回的端口直接绑定 `GrpcUnifiedHandler`

**对 Agent 层的改动**：
- `GlobalRouteState.GRPC_PORT` 和 `GRPC_STREAMING_PORT` 都指向同一个 HTTP/2 handler
- `forceRedirectPort` 中 gRPC 端口统一返回 `GRPC_PORT`（不再区分 unary/streaming）

---

### 2.3 D3 — 流类型正确判断

**方案**：基于 HTTP/2 HEADERS frame 的语义正确判断四种 gRPC 流类型。

gRPC 协议规范（[gRPC over HTTP/2](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md)）：
- 所有 gRPC 调用：`:method = POST`，`content-type = application/grpc`，`te = trailers`
- **Unary**：1 个 HEADERS（endStream=false）+ 1 个 DATA（endStream=true）→ Server 发 1 个响应
- **Server Streaming**：1 个 HEADERS（endStream=false）+ 1 个 DATA（endStream=true）→ Server 发多个响应
- **Client Streaming**：1 个 HEADERS（endStream=false）+ N 个 DATA（最后一个 endStream=true）→ Server 发 1 个响应
- **Bidirectional**：1 个 HEADERS（endStream=false）+ N 个 DATA（最后一个 endStream=true）→ Server 发 M 个响应

**关键洞察**：**HTTP/2 层无法在 HEADERS frame 到达时区分四种流类型**。gRPC 的流类型是由 protobuf 定义（`.proto` 文件的 `stream` 关键字）决定的，不是由 HTTP/2 帧语义决定的。

**实际可行的判断策略**：

```java
enum GrpcStreamType {
    UNARY,              // 1 request → 1 response
    SERVER_STREAMING,   // 1 request → N responses
    CLIENT_STREAMING,   // N requests → 1 response
    BIDIRECTIONAL       // N requests → M responses
}

/**
 * 在 HEADERS frame 到达时，无法确定流类型。
 * 采用延迟判断策略：
 * 1. HEADERS 到达 → 标记为 UNKNOWN
 * 2. 第一个 DATA frame 到达 → 仍为 UNKNOWN
 * 3. DATA frame 的 endStream=true：
 *    - 如果在此之前没有发过响应 → 可能是 UNARY 或 SERVER_STREAMING
 *    - 根据规则配置的 response 数量决定（1个=UNARY，多个=SERVER_STREAMING）
 * 4. DATA frame 的 endStream=false 且已有消息积累：
 *    - 可能是 CLIENT_STREAMING 或 BIDIRECTIONAL
 *    - 根据规则配置的 response 数量决定（1个=CLIENT_STREAMING，多个=BIDIRECTIONAL）
 */
```

**实际实现**：不依赖流类型判断，而是**根据规则的 response 配置决定响应行为**：

```java
// 规则匹配后，检查 response 配置
ResponseEntry response = matchResult.getResponse();

// response.body 中包含多个消息（逗号或换行分隔）→ streaming 响应
// response.body 中只有单个消息 → unary 响应
// 客户端发送多个消息后 endStream → 在 endStream 时统一处理
```

**简化后的处理流程**：
```java
void onHeaders(frame) {
    // 记录 stream context，不判断流类型
    streamContext = new GrpcStreamContext(...);
    activeStreams.put(streamId, streamContext);
}

void onData(frame) {
    streamContext.addMessage(parseGrpcFrame(frame));
    if (frame.isEndStream()) {
        // 客户端发送完毕，执行匹配和响应
        processRequest(streamContext);
    }
    // 如果不是 endStream，继续积累（client streaming / bidirectional）
    // 对于 bidirectional，可以在每条消息到达时就匹配并响应
}

void processRequest(streamContext) {
    // 1. 规则匹配
    // 2. 根据 response 配置决定响应方式：
    //    - 单条消息 → 发送 1 个 DATA + HEADERS(trailers, endStream=true)
    //    - 多条消息 → 逐条发送 DATA，最后发送 HEADERS(trailers, endStream=true)
}
```

**对 bidirectional 的特殊处理**：如果规则配置了"每收到一条消息就回一条"的语义，可以在 `onData` 中对每条消息执行匹配+响应（而不是等 endStream）。但这需要规则模型扩展，作为 v2 特性，v1 先按"等客户端 endStream 后统一响应"处理。

---

### 2.4 D4 — ByteBuf 内存泄漏修复

**问题定位**：`GrpcStreamingHandler.handleData` 中：

```java
ByteBuf partial = data.readBytes(data.readableBytes());
streamContext.accumulateData(partial);
// partial 没有 release！
```

而 `accumulateData` 内部：
```java
void accumulateData(ByteBuf data) {
    accumulatedBuffer.writeBytes(data);
    // data 写入后没有 release
}
```

**修复**：

方案A（推荐）：`accumulateData` 内部 release 传入的 ByteBuf
```java
void accumulateData(ByteBuf data) {
    try {
        if (accumulatedBuffer == null) {
            accumulatedBuffer = Unpooled.buffer();
        }
        accumulatedBuffer.writeBytes(data);
    } finally {
        data.release();
    }
}
```

方案B：调用方 release
```java
ByteBuf partial = data.readBytes(data.readableBytes());
try {
    streamContext.accumulateData(partial);
} finally {
    partial.release();
}
```

选 A，因为 `accumulateData` 的语义是"消费这个 ByteBuf"，内部 release 更安全。

**额外修复**：
- `GrpcStreamContext.accumulatedBuffer` 在 stream 结束时（`channelInactive` / `handleReset`）需要 release
- `GrpcStreamContext` 增加 `release()` 方法

```java
void release() {
    if (accumulatedBuffer != null) {
        accumulatedBuffer.release();
        accumulatedBuffer = null;
    }
}
```

- `handleReset` 和 `channelInactive` 中调用 `streamContext.release()`

---

### 2.5 D5 — 提取公共工具类

**新增 `GrpcCodecUtils`**（放在 `baafoo-core/src/main/java/com/baafoo/core/util/`）：

```java
public final class GrpcCodecUtils {
    private GrpcCodecUtils() {}

    /** gRPC frame: compressed-flag(1B) + length(4B big-endian) + message */
    public static byte[] buildGrpcFrame(byte[] message) { ... }

    /** Parse gRPC frame, return message bytes */
    public static byte[] parseGrpcFrame(byte[] frame) { ... }

    /** Parse multiple gRPC frames from a byte array */
    public static List<byte[]> parseGrpcFrames(byte[] data) { ... }

    public static String bytesToHex(byte[] bytes) { ... }
    public static byte[] hexToBytes(String hex) { ... }
    public static boolean isHexString(String s) { ... }

    /** Extract gRPC service name from path /package.Service/Method */
    public static String extractGrpcService(String path) { ... }

    /** Extract gRPC method name from path /package.Service/Method */
    public static String extractGrpcMethod(String path) { ... }
}
```

**新增 `GrpcResponseBuilder`**（放在 `baafoo-server/src/main/java/com/baafoo/server/handler/`）：

```java
public final class GrpcResponseBuilder {
    /**
     * Build a gRPC stub response body bytes from a ResponseEntry.
     * Handles template rendering, hex/UTF-8 conversion, and multi-message splitting.
     */
    public static List<byte[]> buildResponseMessages(ResponseEntry entry,
                                                      TemplateEngine.RequestContext ctx,
                                                      Long fakerSeed) { ... }

    /** Get gRPC status code from ResponseEntry headers */
    public static int getGrpcStatus(ResponseEntry entry) { ... }

    /** Get gRPC message from ResponseEntry headers */
    public static String getGrpcMessage(ResponseEntry entry) { ... }
}
```

---

### 2.6 D6 — MatchEngine port=0 fallback 下沉

**当前问题**：每个 handler 都写两遍 match：

```java
MatchEngine.MatchResult result = matchEngine.match(
    rules, "grpc", host, port, null, "POST", path, headers, null, bodyHex);
if (!result.isMatched()) {
    result = matchEngine.match(
        rules, "grpc", host, 0, null, "POST", path, headers, null, bodyHex);
}
```

**修复**：在 `MatchEngine.matchesTarget` 中已有 `port > 0` 的保护：

```java
// 当前 MatchEngine 已有的逻辑：
if (rule.getPort() != null && rule.getPort() > 0 && port > 0) {
    if (port != rule.getPort()) return false;
}
```

这意味着 **port=0 已经被当作"匹配任意端口"处理**。所以 handler 中的二次匹配是冗余的——只要第一次 match 时传入实际 port，规则没配 port 或配了 port=0 的规则都会匹配。

**但问题在于**：如果规则配了 `port=9005`，而请求经过 Agent 重定向后实际到达 Server 的端口是 9005，这能匹配。但如果请求是经过 NIO SocketChannel 重定向的，handler 拿到的 port 可能是 0（因为 HTTP/2 HEADERS frame 不携带端口信息，`GrpcStreamingHandler` 中 host=null, port=0）。

**正确修复**：
1. `MatchEngine` 增加一个 `matchWithFallback` 方法，封装 port fallback 逻辑：

```java
public MatchResult matchWithFallback(List<Rule> rules, String protocol,
        String host, int port, String serviceName, String method, String path,
        String topic, Map<String, String> headers, Map<String, String> queryParams,
        String body) {
    MatchResult result = match(rules, protocol, host, port, serviceName,
            method, path, topic, headers, queryParams, body);
    if (!result.isMatched() && port > 0) {
        result = match(rules, protocol, host, 0, serviceName,
                method, path, topic, headers, queryParams, body);
    }
    return result;
}
```

2. 所有 gRPC handler 调用 `matchWithFallback` 而不是两次 `match`。

---

### 2.7 D7 — gRPC 错误码修正

| 场景 | 当前 | 修正 |
|------|------|------|
| Passthrough 不支持 | `14 (UNAVAILABLE)` | `12 (UNIMPLEMENTED)` |
| 无匹配规则 (404 模式) | `5 (NOT_FOUND)` | ✅ 正确 |
| 无匹配规则 (passthrough 模式) | `12 (UNIMPLEMENTED)` | ✅ 正确 |
| RECORD_ALL 无匹配 | `12 (UNIMPLEMENTED)` | ✅ 正确 |
| 内部错误 | `13 (INTERNAL)` | ✅ 正确 |

**修改位置**：`GrpcStubHandler.channelRead0` 中 passthrough 分支。

---

### 2.8 D8 — Bidirectional 无规则时行为统一

**修复**：无规则时统一返回 `NOT_FOUND(5)`，不再 echo。

```java
// 修改前
if (streamContext.streamType == StreamType.BIDIRECTIONAL) {
    echoBidirectionalMessages(streamContext);
} else {
    sendGrpcError(streamContext, 5, "No matching rule found");
}

// 修改后
sendGrpcError(streamContext, 5, "No matching rule found");
```

删除 `echoBidirectionalMessages` 方法。如果未来需要 echo 语义，通过规则配置实现（response body = `{{request.body}}`）。

---

### 2.9 D9 — activeStreams 竞态修复

**分析**：Netty 的 `ChannelInboundHandler` 是单线程执行的（同一个 EventLoop），`channelInactive` 不会与 `channelRead` 并发。所以 `ConcurrentHashMap` 实际上不必要，用普通 `HashMap` 即可。

但考虑到 `GrpcStreamingHandler` 是 `ChannelInitializer`，每个新连接会创建一个实例——等等，不对。看代码：

```java
public class GrpcStreamingHandler extends ChannelInitializer<Channel> {
    private final Map<Integer, GrpcStreamContext> activeStreams = new ConcurrentHashMap<>();
    ...
}
```

`ChannelInitializer` 是每个新连接创建一个实例的（在 `childHandler` 中 new 的）。所以 `activeStreams` 是 per-connection 的，不存在跨连接共享。**单个连接内 HTTP/2 的 stream 处理是单线程的**，`ConcurrentHashMap` 确实多余。

**修复**：
- `activeStreams` 改为 `HashMap`（或 `LinkedHashMap`）
- `channelInactive` 中遍历 release 所有 streamContext

```java
private final Map<Integer, GrpcStreamContext> activeStreams = new HashMap<>();
```

---

### 2.10 D10 — forceRedirectPort gRPC 端口统一

**修复**：统一 gRPC 端口走 `GRPC_PORT`（HTTP/2 handler 能处理所有类型）：

```java
public static int forceRedirectPort(int port) {
    if (port == 80 || port == 443 || port == 8080 || port == 8443) {
        return HTTP_PORT;
    }
    if (port == 50051 || port == 50052 || port == 9090) {
        return GRPC_PORT;  // 统一走 HTTP/2 gRPC handler
    }
    // ... 其余不变
}
```

`GRPC_STREAMING_PORT` 保留作为配置兼容（用户可显式配置 streaming 端口），但 `forceRedirectPort` 不再使用它。

---

## 三、实施步骤

### Phase 1: 基础设施（无破坏性）

1. **新建 `GrpcCodecUtils`** — 提取工具方法，两个 handler 暂不改，先让工具类可用
2. **新建 `GrpcResponseBuilder`** — 提取响应构建逻辑
3. **`MatchEngine.matchWithFallback`** — 封装 port fallback

### Phase 2: Server 层统一（核心改动）

4. **新建 `GrpcUnifiedHandler`** — 基于 `GrpcStreamingHandler` 重构，支持所有四种流类型
5. **修改 `BaafooServer.startGrpcStubServer`** — 9005 端口绑定 `GrpcUnifiedHandler`
6. **废弃 `GrpcStubHandler`** — 标记 @Deprecated，下一个版本删除
7. **废弃 `GrpcStreamingHandler`** — 标记 @Deprecated，功能合并到 `GrpcUnifiedHandler`

### Phase 3: Agent 层增强

8. **新建 `GrpcChannelAdvice`** — 拦截 `ManagedChannelBuilder.forTarget/forAddress`
9. **修改 `BaafooAgent.installTransforms`** — 注册 `GrpcChannelAdvice`
10. **修改 `GlobalRouteState.forceRedirectPort`** — gRPC 端口统一返回 `GRPC_PORT`

### Phase 4: 清理与修复

11. **ByteBuf 泄漏修复** — `GrpcStreamContext.accumulateData` release 传入的 ByteBuf
12. **错误码修正** — `GrpcStubHandler` passthrough 改为 `UNIMPLEMENTED(12)`（如果保留的话）
13. **bidirectional echo 删除** — 统一返回 `NOT_FOUND`
14. **`activeStreams` 改为 `HashMap`**
15. **删除重复的工具方法** — 两个 handler 引用 `GrpcCodecUtils`

### Phase 5: 测试验证

16. **单元测试** — `GrpcCodecUtils`、`GrpcResponseBuilder`
17. **集成测试** — 使用 grpcurl 或真实 gRPC 客户端测试四种调用模式
18. **内存泄漏测试** — 压测 streaming 场景，监控 ByteBuf 引用计数

---

## 四、`GrpcUnifiedHandler` 核心设计

```java
/**
 * Unified HTTP/2 gRPC handler.
 * Handles all four gRPC call types: Unary, Server Streaming, Client Streaming, Bidirectional.
 *
 * Architecture:
 * - Http2FrameCodec → Http2MultiplexHandler → GrpcStreamHandler (per stream)
 * - Each HTTP/2 stream gets its own GrpcStreamHandler instance via Http2MultiplexHandler
 * - Stream type determined lazily based on message flow, not at HEADERS time
 */
public class GrpcUnifiedHandler extends ChannelInitializer<Channel> {

    private final StorageService storage;
    private final ServerConfig config;
    private final AgentResolver agentResolver;
    private final MatchEngine matchEngine;

    @Override
    public void initChannel(Channel ch) {
        Http2FrameCodec codec = Http2FrameCodecBuilder.forServer()
                .initialSettings(Http2Settings.defaultSettings()
                        .maxConcurrentStreams(100)
                        .headerTableSize(65536)
                        .maxFrameSize(16384))
                .build();

        ch.pipeline().addLast(codec);
        ch.pipeline().addLast(new Http2MultiplexHandler(new GrpcStreamChildHandler(
                storage, config, agentResolver, matchEngine)));
    }

    /**
     * Per-stream handler. Http2MultiplexHandler creates a new instance for each
     * HTTP/2 stream, so no shared state — no concurrency concern.
     */
    static class GrpcStreamChildHandler extends ChannelInboundHandlerAdapter {
        // Per-stream state
        private String path;
        private Map<String, String> metadata;
        private final List<byte[]> requestMessages = new ArrayList<>();
        private String accumulatedHex = "";
        private boolean clientEnded = false;
        private Http2FrameStream frameStream;

        private final StorageService storage;
        private final AgentResolver agentResolver;
        private final MatchEngine matchEngine;
        private final ServerConfig config;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                onHeaders(ctx, (Http2HeadersFrame) msg);
            } else if (msg instanceof Http2DataFrame) {
                onData(ctx, (Http2DataFrame) msg);
            }
        }

        private void onHeaders(ChannelHandlerContext ctx, Http2HeadersFrame frame) {
            this.frameStream = frame.stream();
            this.path = frame.headers().get(":path").toString();
            this.metadata = extractMetadata(frame.headers());

            if (frame.isEndStream()) {
                // HEADERS with endStream = no body (rare for gRPC, but handle it)
                processAndRespond(ctx);
            }
        }

        private void onData(ChannelHandlerContext ctx, Http2DataFrame frame) {
            ByteBuf data = frame.content();
            try {
                // Parse gRPC frames from data
                List<byte[]> messages = GrpcCodecUtils.parseGrpcFrames(data);
                for (byte[] msg : messages) {
                    requestMessages.add(msg);
                    accumulatedHex += GrpcCodecUtils.bytesToHex(msg);
                }
            } finally {
                data.release();
            }

            if (frame.isEndStream()) {
                clientEnded = true;
                processAndRespond(ctx);
            }
        }

        private void processAndRespond(ChannelHandlerContext ctx) {
            // 1. Resolve agent environment
            AgentResolver.AgentInfo agentInfo = agentResolver.resolveAll(ctx);
            List<Rule> rules = agentResolver.filterRulesByEnvironment(
                    storage.listRules(), agentInfo.environment);

            // 2. Match rule (with port=0 fallback)
            MatchEngine.MatchResult result = matchEngine.matchWithFallback(
                    rules, "grpc", null, 0, null,
                    "POST", path, null, metadata, null, accumulatedHex);

            // 3. Send response
            if (result.isMatched()) {
                EnvironmentMode mode = agentResolver.resolveEnvironmentMode(agentInfo.environment);
                if (mode == EnvironmentMode.PASSTHROUGH || mode == EnvironmentMode.RECORD) {
                    sendGrpcError(ctx, 12, "Passthrough not supported for gRPC stub");
                } else {
                    sendStubResponse(ctx, result, agentInfo);
                }
            } else {
                sendGrpcError(ctx, 5, "No matching rule found");
            }
        }

        private void sendStubResponse(ChannelHandlerContext ctx, MatchEngine.MatchResult result,
                                       AgentResolver.AgentInfo agentInfo) {
            ResponseEntry entry = result.getResponse();
            Long fakerSeed = result.getRule().getFakerSeed();
            int requestCount = result.getRequestCount();

            TemplateEngine.RequestContext templateCtx = new TemplateEngine.RequestContext(
                    "POST", path, null, metadata, null, accumulatedHex, agentInfo.environment);
            templateCtx.setRequestCount(requestCount);

            List<byte[]> responseMessages = GrpcResponseBuilder.buildResponseMessages(
                    entry, templateCtx, fakerSeed);

            // Send each message as a DATA frame
            for (int i = 0; i < responseMessages.size(); i++) {
                boolean isLast = (i == responseMessages.size() - 1);
                sendData(ctx, responseMessages.get(i), false);

                // Delay between messages (for streaming)
                if (entry.getDelayMs() > 0 && i < responseMessages.size() - 1) {
                    try { Thread.sleep(entry.getDelayMs()); } catch (InterruptedException e) { break; }
                }
            }

            // Send trailers (end of stream)
            sendTrailers(ctx, GrpcResponseBuilder.getGrpcStatus(entry),
                    GrpcResponseBuilder.getGrpcMessage(entry));
        }

        // ... sendData, sendTrailers, sendGrpcError helpers
    }
}
```

**关键设计决策**：

1. **`Http2MultiplexHandler`** 会为每个 HTTP/2 stream 创建独立的 `GrpcStreamChildHandler` 实例，所以 per-stream 状态不需要并发保护
2. **流类型不显式判断**——根据 response 配置的消息数量决定发一个还是多个 DATA frame
3. **客户端 streaming / bidirectional**——在 `onData` 中积累消息，`endStream` 时统一匹配+响应。v2 可以支持"每消息即时响应"的 bidirectional 模式
4. **ByteBuf 释放**——`onData` 中 `try/finally` 确保 `data.release()`

---

## 五、对 BaafooServer.startGrpcStubServer 的改动

```java
private void startGrpcStubServer(int port) throws Exception {
    ServerBootstrap b = new ServerBootstrap();
    b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new GrpcUnifiedHandler(storage, config));
                }
            });

    Channel ch = b.bind(port).sync().channel();
    channels.add(ch);
    log.info("gRPC stub (HTTP/2) on port {}", port);

    // 可选：保留 streaming 端口用于向后兼容
    // 如果配置了 grpcStreaming 端口且不等于 grpc 端口，则额外启动
    // 但实际处理逻辑相同（都是 GrpcUnifiedHandler）
}
```

---

## 六、对 BaafooAgent 的改动

```java
// 在 BaafooAgent.installTransformes 中新增：

// gRPC Channel 拦截 — 在 channel 构建阶段替换 target
agentBuilder = agentBuilder
        .type(hasSuperType(named("io.grpc.ManagedChannelBuilder")))
        .transform(new AgentBuilder.Transformer() {
            @Override
            public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                    TypeDescription typeDescription,
                                                    ClassLoader classLoader,
                                                    JavaModule module) {
                return builder.visit(Advice.to(GrpcChannelAdvice.class).on(named("forTarget")));
            }
        });

// 注意：io.grpc.* 在 App ClassLoader 中，不需要 Bootstrap CL 同步
// GrpcChannelAdvice 可以直接引用 GlobalRouteState（已在 Bootstrap CL）
```

`GrpcChannelAdvice`：

```java
public final class GrpcChannelAdvice {

    @Advice.OnMethodEnter
    public static void onForTarget(@Advice.Argument(value = 0, readOnly = false) String target) {
        try {
            // 解析 target: "host:port" 或 "dns:///host:port"
            String[] parts = parseTarget(target);
            if (parts == null) return;

            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9090;

            // 查路由表
            String[] route = GlobalRouteState.lookup(host, port);
            if (route != null) {
                target = route[0] + ":" + route[1];
                GlobalRouteState.logInfo("[Baafoo] gRPC channel redirect: " + host + ":" + port
                        + " -> " + target);
            }
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] GrpcChannelAdvice error: " + t);
        }
    }

    private static String[] parseTarget(String target) {
        // dns:///host:port → host:port
        // host:port → host:port
        // host → host:9090 (default gRPC port)
        if (target == null || target.isEmpty()) return null;
        String cleaned = target;
        if (cleaned.startsWith("dns:///")) cleaned = cleaned.substring(7);
        if (cleaned.startsWith("static:///")) cleaned = cleaned.substring(10);
        int colonIdx = cleaned.lastIndexOf(':');
        if (colonIdx < 0) return new String[]{cleaned, "9090"};
        return new String[]{
                cleaned.substring(0, colonIdx),
                cleaned.substring(colonIdx + 1)
        };
    }
}
```

---

## 七、风险与缓解

| 风险 | 缓解 |
|------|------|
| `GrpcUnifiedHandler` 上线后 `GrpcStubHandler` 的 HTTP/1.1 客户端不兼容 | 9005 端口统一 HTTP/2 后，原有 HTTP/1.1 客户端需要升级。Baafoo 的 Agent 重定向只关心 TCP 层，不受影响。如果有外部 HTTP/1.1 客户端直连 9005，需要在文档中标注 breaking change。 |
| `GrpcChannelAdvice` 拦截 `io.grpc.*` 需要 App CL 可见 | ByteBuddy advice 可以引用 App CL 类（不像 Socket advice 需要 Bootstrap CL），因为 `ManagedChannelBuilder` 本身在 App CL 中。 |
| Bidirectional streaming 的"等 endStream 统一响应"不满足实时性 | v1 先实现基础功能，v2 可以增加"每消息即时匹配+响应"模式（需要规则模型扩展，如 `streamingMode: per-message`）。 |
| `GrpcCodecUtils.parseGrpcFrames` 处理跨 frame 的半包 | 需要积累逻辑。在 `onData` 中维护一个 `ByteBuf accumulationBuffer`，每次新 data 到来时追加，然后尝试解析完整 frame。 |

---

## 八、验收标准

- [ ] `grpcurl -plaintext -d '{}' localhost:9005 package.Service/Method` 能正确返回 stub 响应
- [ ] 标准 gRPC Java/Go/Python 客户端能连接 9005 端口完成 unary 调用
- [ ] Server streaming 调用能收到多个响应消息
- [ ] Client streaming 调用能发送多个消息并收到单个响应
- [ ] Bidirectional streaming 调用能完成全双工通信
- [ ] 无规则时返回 `grpc-status=5`（NOT_FOUND）
- [ ] Passthrough 模式返回 `grpc-status=12`（UNIMPLEMENTED）
- [ ] 压测 1000 次 streaming 调用，内存无持续增长（ByteBuf 泄漏验证）
- [ ] Agent 拦截 `ManagedChannelBuilder.forTarget` 后，gRPC 流量正确路由到 Baafoo stub

---

## 九、文件变更清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `baafoo-core/src/main/java/com/baafoo/core/util/GrpcCodecUtils.java` | gRPC 编解码工具类 |
| 新建 | `baafoo-server/src/main/java/com/baafoo/server/handler/GrpcResponseBuilder.java` | gRPC 响应构建器 |
| 新建 | `baafoo-server/src/main/java/com/baafoo/server/handler/GrpcUnifiedHandler.java` | 统一 HTTP/2 gRPC handler |
| 新建 | `baafoo-agent/src/main/java/com/baafoo/agent/advice/GrpcChannelAdvice.java` | gRPC Channel 拦截 advice |
| 修改 | `baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java` | 新增 `matchWithFallback` |
| 修改 | `baafoo-agent/src/main/java/com/baafoo/agent/GlobalRouteState.java` | `forceRedirectPort` gRPC 端口统一 |
| 修改 | `baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java` | 注册 `GrpcChannelAdvice` |
| 修改 | `baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java` | `startGrpcStubServer` 绑定 `GrpcUnifiedHandler` |
| 废弃 | `baafoo-server/src/main/java/com/baafoo/server/handler/GrpcStubHandler.java` | @Deprecated，下版本删除 |
| 废弃 | `baafoo-server/src/main/java/com/baafoo/server/handler/GrpcStreamingHandler.java` | @Deprecated，功能合并到 `GrpcUnifiedHandler` |
