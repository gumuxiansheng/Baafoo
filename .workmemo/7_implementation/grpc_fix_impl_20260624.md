# gRPC 修复实施记录

## 日期: 2026-06-24

## 目标
修复 Baafoo 项目中所有 gRPC 相关问题，使 gRPC 功能优雅可用。

## 修复的缺陷清单

| ID | 缺陷 | 修复方式 |
|----|------|----------|
| D1 | GrpcStubHandler 使用 HTTP/1.1 伪装 gRPC，不符合标准 | 新建 GrpcUnifiedHandler 统一使用 HTTP/2，废弃 GrpcStubHandler |
| D2 | GrpcStreamingHandler.determineStreamType 仅靠 endStream 判断，无法区分 Client Streaming 和 Bidirectional | GrpcUnifiedHandler 不显式判断流类型，统一累积请求消息后匹配规则，响应消息数量由规则配置决定 |
| D3 | gRPC 端口(9005)与 Streaming 端口(10005)分离，客户端需连接不同端口 | 统一到单一端口 9005，GrpcUnifiedHandler 处理所有四种调用类型 |
| D4 | GrpcStreamingHandler.accumulateData 未释放传入的 ByteBuf，导致内存泄漏 | accumulateData 中添加 data.release()；新增 release() 方法清理 accumulatedBuffer；handleReset/channelInactive 中调用 release() |
| D5 | GrpcStubHandler 和 GrpcStreamingHandler 中大量重复代码（hex转换、gRPC帧构建、状态码提取等） | 提取 GrpcCodecUtils（baafoo-core）和 GrpcResponseBuilder（baafoo-server）两个工具类 |
| D6 | 每个 handler 中重复 port=0 fallback 匹配逻辑 | MatchEngine 新增 matchWithFallback() 方法封装两步匹配 |
| D7 | PASSTHROUGH/RECORD 模式下返回错误码 14(UNAVAILABLE)，应为 12(UNIMPLEMENTED) | 统一修改为 12 |
| D8 | Bidirectional 未匹配规则时 echo 所有消息（不符合挡板语义） | 统一返回 NOT_FOUND(5)，删除 echo 逻辑 |
| D9 | GrpcStreamingHandler 使用 ConcurrentHashMap 跟踪流，但 Http2MultiplexHandler 保证单线程访问 | 改为 HashMap（已废弃类中）；GrpcUnifiedHandler 利用 Http2MultiplexHandler 每 stream 独立 handler 实例，无共享状态 |
| D10 | Agent 层无 gRPC 感知，只能走 TCP level 拦截 | 新建 GrpcChannelAdvice 拦截 ManagedChannelBuilder.forTarget()，在 Agent 层重定向 gRPC 通道 |

## 新建文件

1. `baafoo-core/src/main/java/com/baafoo/core/util/GrpcCodecUtils.java` - gRPC 编解码工具类
2. `baafoo-server/src/main/java/com/baafoo/server/handler/GrpcUnifiedHandler.java` - 统一 HTTP/2 gRPC 处理器
3. `baafoo-server/src/main/java/com/baafoo/server/handler/GrpcResponseBuilder.java` - gRPC 响应构建器
4. `baafoo-agent/src/main/java/com/baafoo/agent/advice/GrpcChannelAdvice.java` - gRPC 通道拦截 Advice

## 修改文件

1. `baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java` - 新增 matchWithFallback() 方法
2. `baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java` - 使用 GrpcUnifiedHandler 替代两个旧 handler，移除 streaming port +1000 逻辑
3. `baafoo-server/src/main/java/com/baafoo/server/handler/GrpcStubHandler.java` - 标记 @Deprecated，修复 D7
4. `baafoo-server/src/main/java/com/baafoo/server/handler/GrpcStreamingHandler.java` - 标记 @Deprecated，修复 D4/D8/D9
5. `baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java` - 注册 GrpcChannelAdvice 拦截 ManagedChannelBuilder.forTarget()
6. `baafoo-agent/src/main/java/com/baafoo/agent/GlobalRouteState.java` - forceRedirectPort 统一 gRPC 端口到 GRPC_PORT(9005)

## 编译验证

- baafoo-core: 编译通过
- baafoo-server: 编译通过
- baafoo-agent: 编译通过

## 架构变更总结

### Before
```
Client -> [9005 HTTP/1.1 GrpcStubHandler (unary only)]
Client -> [10005 HTTP/2 GrpcStreamingHandler (streaming, flawed type detection)]
Agent -> TCP level socket拦截 (no gRPC awareness)
```

### After
```
Client -> [9005 HTTP/2 GrpcUnifiedHandler (all call types)]
Agent -> GrpcChannelAdvice (ManagedChannelBuilder.forTarget拦截)
Agent -> SocketConnectAdvice (TCP level, unchanged)
```
