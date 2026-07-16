# Baafoo Agent 插件化架构建议

> 背景: Pulsar/TDMQ 协议复杂度高，但 TDMQ 支持是高优先级需求  
> 目标: Agent 核心稳定，协议实现外置为可加载插件，TDMQ 可独立迭代  
> 结论: ✅ 可行，推荐"Advice 内联留 Core + 逻辑委托 Plugin"分层架构

**实施状态 (2026-06-21)**：
- ✅ P0：核心路由 Advice（Socket/NIO/Kafka/JMS/Pulsar/gRPC）已接入 SPI 委托路径（Socket/NIO 通过 `PLUGIN_CONSULT_FN_EXT` 桥接函数）
- ✅ P1：插件级配置系统已实现（PluginsConfig + pluginConfig 注入）
- ✅ P2：PluginContext 协议特有字段已添加（tenant/namespace/destination 等）
- ✅ P3：健康监控 + 启用/禁用 + 心跳上报 + REST API（GET /api/plugins）
- ✅ P4：开发者指南 + kafka-redirect 示例插件

---

## 一、要解决的问题

Pulsar/TDMQ 的难点不在"拦截点"，而在：

1. **Mock Broker 协议实现重**：Lookup/Producer/Consumer/Schema 每个子协议都有大量状态机
2. **TDMQ SDK 可能有私有扩展**：不确定是否有非标准字段，改一次就得重新发 Agent
3. **依赖冲突**：Agent pom 引了 pulsar-client compile scope → 打进 agent fat jar → 与应用自己的 pulsar-client 版本冲突

插件化要解决的本质问题：**让 Agent 核心只负责"拦不拦"（拦截+地址重写），"怎么模拟"交给可插拔的协议实现**。

---

## 二、核心矛盾：Byte Buddy Advice vs ClassLoader 隔离

### 2.1 矛盾描述

Byte Buddy `@Advice` 注解的方法在 premain 阶段被**内联**到目标类的字节码中。这意味着：

- 目标类（如 `sun.nio.ch.SocketChannelImpl`）由 **Bootstrap ClassLoader** 加载
- Bootstrap CL 只能看到 Bootstrap 路径下的类（即 `rt.jar` + 通过 `appendToBootstrapClassLoaderSearch()` 加入的 jar）
- **如果 Advice 类在插件的 URLClassLoader 里，Bootstrap CL 找不到它 → 目标类调用 Advice 时崩溃**

这是插件化架构的最大技术障碍。

### 2.2 解法：Advice 留 Core，逻辑委托 Plugin

**原则**：Advice 代码放在 Core（Bootstrap CL 可见），但 Advice 内部只做两件事：
1. 查路由表（Core 数据，AtomicReference 不可变快照，极快）
2. 委托给 Plugin（按 protocol 找到对应插件，调用其处理接口）

```
应用代码调用 Socket.connect(targetAddress)
    ↓
[注入的 Advice 代码 — 在 Bootstrap CL 中，始终可见]
    ↓
RouteResult route = RouteTable.lookup(host, port);   // Core 逻辑，O(1)
if (route == null) return;  // 不拦截，原始连接
    ↓
AgentPlugin plugin = PluginManager.getPlugin(route.getProtocol());
if (plugin != null) {
    InterceptResult result = plugin.onIntercept(interceptTarget);
    // plugin 内的逻辑在插件自己的 ClassLoader 中执行
    // 因此 plugin 可以自由使用 pulsar-client / tdmq-client 依赖
    RedirectHolder.set(result.getRedirectAddress());
}
    ↓
Socket.connect() 实际连接到 localhost:9003（Baafoo Mock Broker）
```

**关键**：Advice 极简 → 性能无损。协议特定逻辑（Pulsar Lookup 握手、TDMQ 私有扩展处理）全部在 Plugin 内，不影响 Core 稳定性。

---

## 三、详细架构设计

### 3.1 模块拆分

```
baafoo/
├── baafoo-agent-core/           ← 最终产物: baafoo-agent.jar
│   ├── premain 入口
│   ├── 内置拦截器（Socket/NIO/InetAddress）
│   ├── PluginManager（扫描、加载、调度）
│   ├── RouteTable（路由规则原子引用）
│   ├── SPI 接口定义（AgentPlugin, PluginContext, InterceptTarget, InterceptResult）
│   └── 预定义 Advice 类（所有 @Advice 在这里）
│
├── baafoo-plugin-api/          ← 最终产物: baafoo-plugin-api.jar
│   └── 接口定义（供 Core 和 Plugin 共同依赖，通过 Bootstrap CL 加载）
│
└── plugins/
    ├── baafoo-plugin-pulsar/  ← 最终产物: baafoo-plugin-pulsar.jar
    │   ├── PulsarPlugin implements AgentPlugin
    │   └── 依赖: pulsar-client（scope=compile，打包进 plugin jar）
    │
    └── baafoo-plugin-tdmq/    ← 最终产物: baafoo-plugin-tdmq.jar
        ├── TdmqPulsarPlugin implements AgentPlugin
        └── 依赖: tdmq-client（scope=compile，打包进 plugin jar）
```

### 3.2 SPI 接口定义（已实现版本）

> 注意：以下代码反映实际实现，与原始提案（§3.2 早期版本）有差异。
> 实际包名为 `com.baafoo.plugin`（非 `com.baafoo.spi`），
> PluginContext 为 POJO 类（非 interface），InterceptTarget 为 enum（非 record），
> InterceptResult 为具体类（非 sealed interface）。

```java
// === baafoo-plugin-api 模块 ===
package com.baafoo.plugin;

public interface AgentPlugin {
    String getName();                                    // 插件唯一标识
    InterceptTarget getTarget();                         // 处理的拦截目标
    default void configure(Map<String, Object> config) {} // P1: 插件级配置 (Java 8 default)
    void init();                                         // 初始化
    InterceptResult intercept(PluginContext ctx);         // 核心拦截处理
    void destroy();                                      // 销毁
}

public enum InterceptTarget {
    SOCKET, NIO_SOCKET, KAFKA, PULSAR, JMS,
    CONSUL_DNS, CONSUL_API, FEIGN
}

public class PluginContext {
    // 通用字段
    String protocol, host; int port;
    String serviceName; Map<String, String> headers; byte[] requestData;
    Callable<InterceptResult> originalCall;
    String ruleId, ruleName, responseName; int conditionIndex; boolean recording;
    Map<String, Object> pluginConfig;  // P1: 插件级配置

    // P2: 协议特有字段 (均为可选，默认 null)
    String topic;              // Kafka/Pulsar/JMS/MQTT
    Integer partition;         // Kafka
    String key;                // Kafka
    String tenant, namespace;  // Pulsar
    String destination;        // JMS
    String messageType;        // JMS
    String method, path;       // HTTP/TCP
    Map<String, String> queryParams; // HTTP
}

public class InterceptResult {
    static InterceptResult stub(byte[] body, Map<String,String> headers, int status);
    static InterceptResult passthrough();
    static InterceptResult redirect(String host, int port);
    static InterceptResult error(String message);
}
```

### 3.3 Core 内置 Advice 示例

```java
// === baafoo-agent-core 模块 ===
package com.baafoo.agent.advice;

public class SocketConnectAdvice {

    @Advice.OnMethodEnter
    public static void onConnect(
            @Advice.Argument(0) SocketAddress target,
            @Advice.Local("original") InetSocketAddress[] original) {

        if (!(target instanceof InetSocketAddress addr)) return;

        // 1. 查路由表（Core 逻辑，原子引用，< 1μs）
        RouteResult route = RouteTable.lookup(addr.getHostString(), addr.getPort());
        if (route == null) return;  // 不拦截

        // 2. 委托给 Plugin
        AgentPlugin plugin = PluginManager.getPlugin(route.getProtocol());
        if (plugin != null) {
            InterceptResult result = plugin.onIntercept(
                new InterceptTarget(
                    route.getServiceName(),
                    addr.getHostString(),
                    addr.getPort(),
                    route.getProtocol()
                )
            );
            if (result instanceof InterceptResult.Redirect r) {
                original[0] = addr;
                RedirectHolder.set(r.newAddress());
            }
            return;
        }

        // 3. 无插件，走 Core 内置的默认重写逻辑（HTTP/TCP）
        RedirectHolder.set(route.getDefaultRedirectAddress());
    }

    @Advice.OnMethodExit
    public static void afterConnect(
            @Advice.Local("original") InetSocketAddress original) {
        RedirectHolder.clear();
        // 如需要，可在此记录日志
    }
}
```

### 3.4 Plugin 实现示例

```java
// === baafoo-plugin-pulsar.jar ===
package com.baafoo.plugin.pulsar;

public class PulsarPlugin implements AgentPlugin {

    @Override
    public String getId() { return "pulsar"; }

    @Override
    public Protocol getProtocol() { return Protocol.PULSAR; }

    @Override
    public void installTransformers(Instrumentation inst, PluginContext ctx) {
        // 注意：这里的 Advice 类（PulsarBuilderAdvice）必须在 Core 中预定义
        // 因为 Advice 字节码会被内联到目标类，Bootstrap CL 必须能找到
        // 所以 installTransformers 的职责是"注册 Transformer"，
        // 但 Advice 类本身写在 Core 的 advice 包中
        new AgentBuilder.Default()
            .type(ElementMatchers.named("org.apache.pulsar.client.api.PulsarClient"))
            .transform((builder, typeDescription, classLoader, module) ->
                builder.method(ElementMatchers.named("builder"))
                       .intercept(Advice.to(PulsarBuilderAdvice.class))
            ).installOn(inst);
    }

    @Override
    public InterceptResult onIntercept(InterceptTarget target) {
        // 核心逻辑：将 pulsar://broker:6650 重写为 pulsar://localhost:9003
        return new InterceptResult.Redirect(
            new InetSocketAddress("localhost", 9003)
        );
    }

    @Override
    public void onModeChanged(Mode newMode) {
        // 可在模式切换时清理缓存等
    }
}
```

### 3.5 ClassLoader 隔离方案

```
Bootstrap ClassLoader
  ├── baafoo-agent-core.jar        ← 通过 appendToBootstrapClassLoaderSearch() 加入
  │     ├── Advice 类（SocketConnectAdvice, PulsarBuilderAdvice 等）
  │     ├── PluginManager
  │     └── RouteTable
  │
  └── baafoo-plugin-api.jar       ← SPI 接口，也必须加入 Bootstrap CL
        └── AgentPlugin, PluginContext, InterceptTarget, InterceptResult

Plugin ClassLoader A (URLClassLoader, parent = null)
  └── baafoo-plugin-pulsar.jar
        ├── PulsarPlugin（实现 AgentPlugin，接口从 Bootstrap CL 加载 ✅）
        └── 依赖：pulsar-client 2.11.0（仅在此 ClassLoader 内可见）

Plugin ClassLoader B (URLClassLoader, parent = null)
  └── baafoo-plugin-tdmq.jar
        ├── TdmqPulsarPlugin
        └── 依赖：tdmq-client 1.2.3（仅在此 ClassLoader 内可见）

AppClassLoader
  └── 应用本身的依赖（pulsar-client 2.10.0）
        ← 与 Plugin ClassLoader A 中的 2.11.0 互不干扰 ✅
```

**关键细节**：

1. `parent = null`（不是 AppClassLoader），这样插件看不到应用的类，避免意外依赖
2. SPI 接口（`AgentPlugin` 等）必须在 Bootstrap CL → 通过 `Instrumentation.appendToBootstrapClassLoaderSearch()` 把 `baafoo-plugin-api.jar` 加入
3. 插件内部使用的 `pulsar-client` / `tdmq-client` 只在插件 ClassLoader 内可见，**与应用的同名依赖完全隔离**

### 3.6 插件加载流程

```
premain(String args, Instrumentation inst) 启动
  │
  ├── 1. 将 Core jar 和 plugin-api jar 加入 Bootstrap CL
  │     inst.appendToBootstrapClassLoaderSearch(JarFile(coreJar))
  │     inst.appendToBootstrapClassLoaderSearch(JarFile(pluginApiJar))
  │
  ├── 2. 安装 Core 内置拦截器（Socket/NIO/InetAddress）
  │     这些 Advice 内联到 JDK 类中，始终生效
  │
  ├── 3. 扫描 plugins/ 目录
  │     baafoo-agent.jar 同级 plugins/ 目录下所有 *.jar
  │
  ├── 4. ServiceLoader 扫描每个 jar 的 META-INF/services/com.baafoo.spi.AgentPlugin
  │     找到 AgentPlugin 实现类
  │
  ├── 5. 创建独立 URLClassLoader(pluginJar, null) 加载插件
  │     ⚠️ parent=null，插件看不到应用类
  │
  ├── 6. 调用 plugin.installTransformers(inst, ctx)
  │     插件注册自己的 Byte Buddy Transformer
  │     ⚠️ Transformer 的 Advice 类必须在 Core 中预定义
  │     （因为 Advice 字节码内联到目标类，Bootstrap CL 必须能找到）
  │
  └── 7. 注册插件到 PluginManager 的 protocol→plugin 映射表
         后续 Core Advice 委托时按 protocol 查找
```

---

## 四、Advice 与 Plugin 的职责边界

| 职责 | 所在层 | 原因 |
|---|---|---|
| `Socket.connect()` 拦截 | Core Advice | JDK 内部类，Advice 必须在 Bootstrap CL |
| `NIO SocketChannel.connect()` 拦截 | Core Advice | 同上 |
| `InetAddress.getByName()` 拦截 | Core Advice | 同上 |
| `PulsarClient.builder()` 拦截 | Core 预定义 Advice | Advice 字节码内联到目标类，必须在 Bootstrap CL |
| `PulsarClient.builder()` 的**处理逻辑** | Plugin | 拦截后的地址重写、协议特定逻辑委托给插件 |
| `KafkaProducer` 构造拦截 | Core 预定义 Advice | 同 Pulsar |
| `JMS ConnectionFactory.createConnection()` 拦截 | Core 预定义 Advice | 同 Pulsar |
| 协议特定地址重写 | Plugin | 可按需替换 |
| Mock Broker 协议实现 | Server 端 Plugin | Agent 不涉及 |

**核心原则**：拦截点（Advice）在 Core 预定义，拦截后的行为逻辑在 Plugin 实现。

---

## 五、Server 端配套改造

Agent 插件化后，Server 端的 Mock Broker 也应插件化（更简单，无 Byte Buddy/ClassLoader 约束）：

```
baafoo-server/
├── baafoo-server-core/        ← 核心框架（端口管理、规则引擎、REST API、环境管理）
└── plugins/
    ├── baafoo-server-plugin-http.jar     ← HTTP Mock Handler
    ├── baafoo-server-plugin-tcp.jar      ← TCP Mock Handler
    ├── baafoo-server-plugin-kafka.jar    ← Kafka Mock Broker
    ├── baafoo-server-plugin-pulsar.jar   ← Pulsar Mock Broker（含 TDMQ 适配）
    └── baafoo-server-plugin-jms.jar     ← JMS Mock Broker
```

Server 端插件化是标准 Java SPI + 工厂模式，无额外复杂度。

---

## 六、对现有设计文档的影响

| 文档 | 需要变更 | 内容 |
|---|---|---|
| 概念设计 §3.2 | 重写 | Agent 拦截点分为"Core 内置"+"Plugin 注册"两层 |
| 概念设计 附录 A | 重写目录结构 | 新增 `baafoo-plugin-api/`、`plugins/` 目录 |
| 概念设计 附录 B | 新增依赖 | plugin-api 模块说明 |
| PRD R-A4/R-A5/R-A6 | 调整 | Kafka/Pulsar/JMS 拦截器改为 Plugin 实现，AC 不变 |
| PRD 新增章节 | 新增 | "插件管理"功能需求（插件列表、安装、启用/禁用、版本） |
| UI 设计 | 无变更 | 插件化是内部架构变更，不影响用户界面（v1.0 可不做插件管理 UI，通过文件部署） |

---

## 七、落地优先级

| 阶段 | 内容 | 目标 |
|---|---|---|
| v1.0 Phase 1 | Agent Core + Plugin API + 内置 HTTP/TCP/Consul | 架构骨架先立住 |
| v1.0 Phase 2 | Pulsar 插件代码内嵌（不独立 jar），按 Plugin 接口写 | TDMQ 可用性验证，架构可演进 |
| v1.0 Phase 3 | 如 TDMQ SDK 有私有扩展，拆分为独立 `baafoo-plugin-tdmq.jar` | TDMQ 全面支持 |
| v1.5 | Kafka/JMS 插件外置为独立 jar | 所有协议插件化 |
| v1.5 | Web 控制台"插件管理"页面 | 可视化插件安装/启用/禁用 |

**v1.0 可以先不拆 jar**——Pulsar 插件代码内嵌在 Agent Core 中，但**严格按 Plugin 接口编写**。这样既不增加 v1.0 的构建和部署复杂度，又确保架构可演进。验证 TDMQ SDK 行为后再决定是否拆出独立插件 jar。

---

## 八、风险与注意事项

| 风险 | 严重度 | 缓解方案 |
|---|---|---|
| Plugin 的 installTransformers 注册的 Advice 不在 Bootstrap CL | 🔴 高 | Advice 类必须在 Core 中预定义，Plugin 只实现处理逻辑 |
| 插件 ClassLoader 的 parent 设为 AppClassLoader | 🟡 中 | 必须设为 null，否则插件能看到应用的类，失去隔离意义 |
| PluginManager.getPlugin() 在热路径上（每个连接都调用） | 🟡 中 | 用 ConcurrentHashMap 做 protocol→plugin 映射，O(1) 查找 |
| 插件初始化失败（如 pulsar-client 版本不兼容） | 🟡 中 | 捕获异常，打印 WARN 日志，该协议降级为 passthrough，不影响其他协议 |
| 多个插件注册了同一个 Transformer（协议冲突） | 🟢 低 | PluginManager 启动时检测 protocol 唯一性，冲突时拒绝加载 |

---

## 八点五、P1 插件级配置系统（已实现）

### 配置链路

```
baafoo-agent.yml (plugins: 段)
    → ConfigLoader (Jackson YAML 反序列化)
    → AgentConfig.PluginsConfig (enabled, directory, configs Map)
    → PluginManager 构造函数 (接受 PluginsConfig)
    → plugin.configure(config) (init 之前调用)
    → PluginContext.pluginConfig (每次 intercept 时注入)
```

### YAML 配置示例

```yaml
plugins:
  enabled: true
  directory: "./plugins"
  configs:
    tdmq:
      brokerPort: 9005
    feign:
      defaultStubStatus: 200
```

### 生命周期变更

原：`getName() → getTarget() → init() → intercept() × N → destroy()`
新：`getName() → getTarget() → configure(config) → init() → intercept() × N → destroy()`

`configure()` 是 Java 8 default method（空实现），已有插件无需修改即可兼容。

---

## 八点六、P2 协议特有字段（已实现）

### PluginContext 新增字段

| 字段 | 类型 | 适用协议 | Advice 提取点 |
|------|------|----------|---------------|
| `topic` | String | Kafka/Pulsar/JMS | 构造函数不可用（send/receive 时才有） |
| `partition` | Integer | Kafka | 同上 |
| `key` | String | Kafka | 同上 |
| `tenant` | String | Pulsar | `PulsarClientAdvice.extractPathSegments()` |
| `namespace` | String | Pulsar | 同上（从 serviceUrl 路径解析） |
| `destination` | String | JMS | `JmsConnectionFactoryAdvice.extractDestination()` |
| `messageType` | String | JMS | 构造函数不可用 |
| `method` | String | HTTP | 预留（需 Socket 层 HTTP 解析） |
| `path` | String | HTTP | 同上 |
| `queryParams` | Map | HTTP | 同上 |

所有字段默认为 null，不影响已有插件。Advice 尽力提取，不可用时留 null。

### 提取限制说明

Kafka Producer/Consumer Advice 拦截的是构造函数（`new KafkaProducer(props)`），此时还没有 topic/partition/key 信息（这些在 `ProducerRecord` 中指定，发生在 send() 调用时）。如果未来需要按 topic 路由，需要添加拦截 `KafkaProducer.send()` 的 Advice。

---

## 九、总结

**插件化架构的核心价值**：

1. **Core 稳定**：内置 HTTP/TCP/Consul，经过充分测试，极少变更
2. **协议独立迭代**：Pulsar/TDMQ 有私有扩展？更新 plugin jar 即可，Core 不动
3. **依赖隔离**：插件内的 pulsar-client / tdmq-client 与应用的同名依赖完全不冲突
4. **用户可扩展**：公司内部私有 RPC 协议，写个 jar 丢到 `plugins/` 目录即可

**对 TDMQ 高优先级需求的直接支持**：通过插件化，TDMQ 的适配工作可以独立进行，不影响 Core 稳定性，也不阻塞其他协议的开发进度。
