# R-S6: JMS Mock Broker

> 实施时间: 2026-06-13
> PRD 需求: R-S6 AC-01/AC-02/AC-03/AC-04

## 需求描述

在 Baafoo Server 中嵌入 JMS Mock Broker，监听端口 9004，支持：
- AC-01: Queue 模式 FIFO 投递
- AC-02: Topic 模式广播
- AC-03: 消息延迟与排序
- AC-04: 死信队列模拟（消息重试 N 次后进入 DLQ）

Agent 已拦截 `ActiveMQConnectionFactory` 构造函数并将 brokerURL 替换为 `tcp://SERVER_HOST:9004`。

## 实施策略

嵌入 Apache ActiveMQ Artemis 作为内存 Broker。Artemis 提供：
- 原生 OpenWire 协议支持（ActiveMQ 5.x 客户端兼容）
- 内置 DLQ 机制（通过 AddressSettings 配置 maxDeliveryAttempts + deadLetterAddress）
- 定时投递（通过 `_AMQ_SCHED_DELIVERY` 属性设置绝对时间戳）
- Queue（ANYCAST）和 Topic（MULTICAST）路由类型

## 依赖版本

- Artemis 版本: **2.19.1**（Spring Boot BOM `spring-boot-dependencies:2.7.18` 覆盖了自定义的 `artemis.version` 属性）
- ActiveMQ Client 5.18.3（test scope，用于 OpenWire 协议集成测试）
- commons-logging 1.2（Artemis 运行时依赖）

### 关键发现：Spring Boot BOM 属性覆盖

在父 POM 中定义了 `<artemis.version>2.15.0</artemis.version>`，但 Spring Boot BOM 导入后覆盖了该属性值为 2.19.1。这是因为 Spring Boot BOM 使用相同的 `artemis.version` 属性名。

解决方案：使用 `${artemis.version}` 引用即可，实际解析为 2.19.1。如需固定版本，应使用不与 Spring Boot 冲突的属性名（如 `baafoo-artemis.version`）。

### Netty 版本冲突处理

Artemis 依赖 Netty，与项目已有的 Netty 4.1.100.Final 冲突。通过在 `artemis-server` 和 `artemis-jms-client` 依赖中排除所有 `io.netty` 模块解决。项目已有的 `netty-all` 提供了所有 Netty 类。

## 修改文件

- `pom.xml`（父 POM）— 添加 `artemis.version` 属性
- `baafoo-server/pom.xml` — 添加 Artemis 依赖 + commons-logging + activemq-client(test)
- `baafoo-server/src/main/java/com/baafoo/server/broker/JmsMockBroker.java` — 新增
- `baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java` — 集成 JmsMockBroker
- `baafoo-server/src/test/java/com/baafoo/server/broker/JmsMockBrokerTest.java` — 新增

## JmsMockBroker 设计

### 配置

```java
ConfigurationImpl config = new ConfigurationImpl();
config.setPersistenceEnabled(false);   // 纯内存，无持久化
config.setSecurityEnabled(false);      // 无认证
config.addAcceptorConfiguration("jms-tcp", "tcp://0.0.0.0:9004");   // OpenWire 客户端
config.addAcceptorConfiguration("in-vm", "vm://0");                  // 内部预设消息投递
```

### DLQ 配置

```java
AddressSettings defaultSettings = new AddressSettings();
defaultSettings.setMaxDeliveryAttempts(3);  // 默认 3 次重试
defaultSettings.setDeadLetterAddress(SimpleString.toSimpleString("DLQ"));
defaultSettings.setAutoCreateQueues(true);
defaultSettings.setAutoCreateAddresses(true);
config.addAddressesSetting("#", defaultSettings);
```

### 规则映射

JMS 规则使用现有 Rule 模型字段：
- `rule.protocol` = "jms"
- `rule.serviceName` = 目标名称（如 "orderQueue"、"priceTopic"）
- `MatchCondition` type="jmsType" value="queue" 或 "topic"（默认 queue）
- `rule.responses` = 预设消息列表，body 为消息体，delayMs 为投递延迟

### 消息延迟

使用 Artemis 定时投递属性：
```java
message.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + delayMs);
```

## 测试覆盖

12 个测试用例，全部通过：

| 测试 | AC | 说明 |
|------|----|------|
| testQueueFifoDelivery | AC-01 | Queue FIFO 顺序投递 |
| testTopicBroadcast | AC-02 | Topic 广播给多个订阅者 |
| testMessageDelay | AC-03 | 延迟投递验证 |
| testMessageOrderingWithDelay | AC-03 | 延迟消息排序 |
| testDeadLetterQueue | AC-04 | 3 次回滚后消息进入 DLQ |
| testLoadRulesCreatesQueuesAndTopics | — | 规则加载创建队列/主题 |
| testLoadRulesSkipsNonJmsRules | — | 跳过非 JMS 规则 |
| testLoadRulesWithDelay | — | 带延迟的规则加载 |
| testBrokerStartAndStop | — | 生命周期管理 |
| testBrokerCustomMaxDeliveryAttempts | — | 自定义最大重试次数 |
| testCreateQueueBeforeStartThrows | — | 未启动时操作抛异常 |
| testReloadRules | — | 规则热重载 |

## 已知限制

1. **Java 版本**: Artemis 2.19.1 编译目标为 Java 11，在 Java 8 JVM 上无法加载。当前开发环境 JDK 为 1.8.0_202，但编译使用更高版本 JDK（Maven 编译器配置 `-source 1.8 -target 1.8`，但实际 JDK 可能是 11+）。运行时需要 Java 11+ 才能使用 JMS Mock Broker 功能。

2. **规则热重载**: 当前 `reloadRules()` 实现 stop+start+load，会断开所有客户端连接。生产环境应考虑增量更新。

3. **Topic 预设消息**: Topic 预设消息在创建订阅者之前发送，新订阅者不会收到已发送的消息（JMS 规范行为）。如需所有订阅者都能收到预设消息，应在订阅者连接后再发送。

4. **OpenWire 协议兼容性**: 已通过 ActiveMQ 5.x 客户端测试验证。其他 JMS 提供者（IBM MQ、Tibco EMS 等）需要额外的拦截和协议支持。
