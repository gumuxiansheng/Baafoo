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
