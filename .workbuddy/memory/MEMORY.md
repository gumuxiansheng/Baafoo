# Baafoo 项目记忆

## 项目概述
Baafoo 挡板（Mock）平台，基于 JavaAgent + Byte Buddy，不修改业务代码拦截网络调用。

## 技术栈
- Java 8（严格兼容）+ Byte Buddy 1.14.14 + Netty 4.1.100.Final + Jackson 2.15.3
- 后端：Spring Boot + MyBatis-Plus 无 Lombok
- 前端：Vue2 + ElementUI + CodeMirror 5
- 无 Lombok 依赖

## 核心架构
- **AgentManifest + RouteTable 模式**：Bootstrap CL 安全的全局单例，Advice 通过 `AgentManifest.ROUTE_TABLE` 读取路由
- **appendToBootstrapClassLoaderSearch()**：premain 中必须调用，将 agent jar 加入 Bootstrap CL
- **Advice 内联约束**：拦截 JDK 类的 Advice 只能引用 AgentManifest/RouteTable/java.*（Bootstrap-safe）

## Maven 结构
baafoo-parent → baafoo-plugin-api / baafoo-core / baafoo-agent / baafoo-server / baafoo-cli / web

## 关键端口
HTTP=9000, TCP=9001, Kafka=9002, Pulsar=9003, JMS=9004
API前缀：`/__baafoo__/api/*`

## 编译命令
```bash
export JAVA_HOME="C:/Program Files/Java/jdk1.8.0_202" && cd "C:/Dev/Projects/Baafoo" && "C:/Users/win11/.m2/wrapper/dists/apache-maven-3.9.9-bin/33b4b2b4/apache-maven-3.9.9/bin/mvn.cmd" compile
```

## Phase 2 待办
- KafkaProducerAdvice / PulsarClientAdvice 迁移到 AgentManifest 模式（当前仍引用 RouteManager）
- MockBroker 实现（Kafka/Pulsar/JMS 延后，MVP 先做 TCP 级端口重定向）

## Bootstrap-safe 四件套（已验证）
SocketConnectAdvice / NioSocketConnectAdvice / ConsulDnsAdvice / ConsulHttpAdvice

## Bootstrap-safe 事件桥接约束（关键教训）
- 拦截 JDK 类的 Advice（SocketConnectAdvice / NioSocketConnectAdvice）绝对不能直接引用 `com.baafoo.plugin.PluginEvent`
- 即使 `GlobalRouteState` 在 Bootstrap CL 中，其事件桥接方法也必须以 `Object` 类型接受事件：`Consumer<Object> EVENT_FIRE_FN` 和 `firePluginEvent(Object)`
- App CL 侧通过 `PluginBridge` 安全地将 Object 转回 `PluginEvent` 再分发给 `PluginManager`
- 违反此约束会导致 `NoClassDefFoundError` / `LinkageError`，使对应拦截 silently fail-closed

## 测试脚本陷阱
- `testing/test-fullchain.ps1` 中从 `/api/environments` 取 `staging-a` ID 时，不能用跨对象正则（id→name 可能跨条目），必须用 JSON 解析（如 `ConvertFrom-Json` + 按 name 查找）。
- 环境模式切换后需等待 agent 轮询周期（默认 `pollIntervalSec=10s`）+ 余量，否则 agent 仍持有旧模式。
- M03 PASSTHROUGH 被误判为功能 bug，实为脚本提取了错误环境 ID 导致模式未真正切到 staging-a。
- 拦截 JDK 类的 Advice（SocketConnectAdvice / NioSocketConnectAdvice）绝对不能直接引用 `com.baafoo.plugin.PluginEvent`
- 即使 `GlobalRouteState` 在 Bootstrap CL 中，其事件桥接方法也必须以 `Object` 类型接受事件：`Consumer<Object> EVENT_FIRE_FN` 和 `firePluginEvent(Object)`
- App CL 侧通过 `PluginBridge` 安全地将 Object 转回 `PluginEvent` 再分发给 `PluginManager`
- 违反此约束会导致 `NoClassDefFoundError` / `LinkageError`，使对应拦截 silently fail-closed
