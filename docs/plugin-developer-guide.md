## Baafoo 插件开发者指南

Baafoo 的插件系统允许你通过 Java SPI 机制扩展 Agent 的协议拦截行为，而无需修改 Agent Core 代码。每个插件是一个独立的 JAR 文件，通过 `plugins/` 目录热加载，运行在隔离的 ClassLoader 中。

---

### 1. 架构概览

```
Bootstrap ClassLoader
  ├── baafoo-agent.jar          ← appendToBootstrapClassLoaderSearch()
  │     ├── Advice 类（SocketConnectAdvice, KafkaProducerAdvice 等）
  │     ├── GlobalRouteState / RouteTable
  │     └── PluginManager
  │
  └── baafoo-plugin-api.jar    ← SPI 接口，Bootstrap CL 可见
        └── AgentPlugin, PluginContext, InterceptTarget, InterceptResult

Plugin ClassLoader (URLClassLoader, parent=null)  ← 每个 plugin JAR 独立
  └── your-plugin.jar
        ├── YourPlugin（实现 AgentPlugin，接口从 Bootstrap CL 加载）
        └── 依赖：protocol-sdk（仅在此 ClassLoader 内可见）

AppClassLoader
  └── 宿主应用依赖（如 pulsar-client 2.10）
        ← 与 Plugin ClassLoader 中的 pulsar-client 2.7 互不干扰
```

**核心设计原则**：
- Agent Core 只负责"拦不拦"（路由查找 + 地址重写）
- 插件负责"怎么模拟"（协议特定的处理逻辑）
- `PluginClassLoader` 的 `parent=null`，插件看不到宿主应用的类
- SPI 接口（`AgentPlugin` 等）通过 Bootstrap CL 加载，Core 和 Plugin 都能访问
- 插件内的协议依赖（如 feign-core）只在插件 ClassLoader 内可见，与应用的同名依赖完全隔离

---

### 2. AgentPlugin 接口

```java
package com.baafoo.plugin;

public interface AgentPlugin {
    /** 插件唯一名称，用于配置索引和日志标识 */
    String getName();

    /** 此插件处理的拦截目标类型 */
    InterceptTarget getTarget();

    /** 插件级配置注入（Java 8 default method，可选实现） */
    default void configure(Map<String, Object> config) {}

    /** 初始化（加载后调用一次） */
    void init();

    /** 每次拦截时调用，返回 InterceptResult */
    InterceptResult intercept(PluginContext ctx);

    /** JVM 关闭时调用 */
    void destroy();
}
```

**生命周期**：`getName()` → `getTarget()` → `configure(config)` → `init()` → `intercept(ctx) × N` → `destroy()`

---

### 3. InterceptTarget 枚举

```java
public enum InterceptTarget {
    SOCKET,      // java.net.Socket#connect()     — HTTP/TCP 协议
    NIO_SOCKET,  // SocketChannelImpl#connect()   — NIO TCP
    KAFKA,       // KafkaProducer/KafkaConsumer    — Kafka 协议
    PULSAR,      // PulsarClientBuilder           — Pulsar 协议
    JMS,         // ActiveMQConnectionFactory     — JMS 协议
    CONSUL_DNS,  // InetAddress#getByName()       — Consul DNS 拦截
    CONSUL_API,  // Consul HTTP API               — Consul SDK 模式
    FEIGN        // feign.Client#execute()        — Feign 声明式 HTTP
}
```

每个 `InterceptTarget` 同一时刻只能有一个插件注册。后加载的 JAR 会覆盖先加载的。

---

### 4. PluginContext 字段参考

#### 通用字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `protocol` | String | 协议名: http, tcp, kafka, pulsar, jms |
| `host` | String | 原始目标主机 |
| `port` | int | 原始目标端口 |
| `serviceName` | String | Consul 服务名（Consul 模式下） |
| `headers` | Map<String, String> | 请求头 |
| `requestData` | byte[] | 请求体原始字节 |
| `originalCall` | Callable<InterceptResult> | 原始调用的可执行引用 |
| `ruleId` / `ruleName` | String | 匹配到的规则 ID/名称 |
| `pluginConfig` | Map<String, Object> | 插件级配置（从 baafoo-agent.yml 注入） |
| `recording` | boolean | 是否为录制会话 |

#### 协议特有字段（均为可选，默认 null）

| 字段 | 类型 | 适用协议 | 说明 |
|------|------|----------|------|
| `topic` | String | Kafka/Pulsar/JMS | 消息主题 |
| `partition` | Integer | Kafka | 分区号 |
| `key` | String | Kafka | 消息 key |
| `tenant` | String | Pulsar | 租户 |
| `namespace` | String | Pulsar | 命名空间 |
| `destination` | String | JMS | 目标队列/主题名 |
| `messageType` | String | JMS | 消息类型 |
| `method` | String | HTTP/TCP | HTTP 方法 |
| `path` | String | HTTP/TCP | 请求路径 |
| `queryParams` | Map<String, String> | HTTP | 查询参数 |

> **注意**：部分字段在 Advice 拦截点不可用。例如 Kafka 的 topic/partition/key 在构造函数拦截时无法获取（它们在 `ProducerRecord` 中，send() 调用时才有）。这些字段留 null。

---

### 5. InterceptResult 使用指南

```java
// 返回预设 Mock 响应（仅适用于 HTTP 协议）
InterceptResult.stub(byte[] body, Map<String,String> headers, int statusCode);

// 透传，不拦截（放行到真实目标）
InterceptResult.passthrough();

// 连接重定向（二进制协议首选：Kafka/Pulsar/JMS/TCP）
InterceptResult.redirect(String host, int port);

// 返回错误
InterceptResult.error(String message);
```

**关键约束**：`redirect` 和 `stub` 互斥。对于 Pulsar/Kafka/JMS 等二进制协议，在连接建立阶段无法注入"响应体"，因此插件只能返回 `redirect`（将连接导向 Mock Broker）。

#### 5.1 四种拦截模式

Agent 的全局模式（由 `RouteManager` 管理）决定了 `intercept()` 的调用上下文：

| 模式 | 行为 | 插件职责 |
|------|------|----------|
| **stub** | 重定向到 Mock Broker 或返回预设响应 | 返回 `redirect` 或 `stub` |
| **passthrough** | 所有连接放行，不拦截 | `intercept()` 不会被调用 |
| **record** | 执行原始请求，记录响应数据 | 返回 `passthrough`，录制由 Agent 自动处理 |
| **record-and-stub** | 录制真实数据 + 同时返回 stub | 返回 `stub`，录制由 Agent 自动处理 |

---

### 6. 开发步骤

#### 6.1 创建 Maven 项目

```xml
<project>
    <groupId>com.yourcompany</groupId>
    <artifactId>my-baafoo-plugin</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <dependencies>
        <!-- baafoo-plugin-api 必须为 provided scope -->
        <dependency>
            <groupId>com.baafoo</groupId>
            <artifactId>baafoo-plugin-api</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        <!-- 你的协议 SDK（compile scope，打包进插件 JAR） -->
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>example-protocol-sdk</artifactId>
            <version>2.0.0</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.1</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

#### 6.2 实现 AgentPlugin

```java
package com.yourcompany;

import com.baafoo.plugin.*;

public class MyKafkaPlugin implements AgentPlugin {
    private int customPort = 9050;

    @Override public String getName() { return "my-kafka"; }
    @Override public InterceptTarget getTarget() { return InterceptTarget.KAFKA; }

    @Override
    public void configure(Map<String, Object> config) {
        // 从 baafoo-agent.yml 读取插件级配置
        if (config.containsKey("customPort")) {
            this.customPort = ((Number) config.get("customPort")).intValue();
        }
    }

    @Override public void init() {
        System.out.println("MyKafkaPlugin initialized with port=" + customPort);
    }

    @Override
    public InterceptResult intercept(PluginContext ctx) {
        // 根据 topic 过滤路由
        if ("internal-topic".equals(ctx.getTopic())) {
            return InterceptResult.passthrough(); // 内部 topic 不拦截
        }
        return InterceptResult.redirect("localhost", customPort);
    }

    @Override public void destroy() {
        // 清理资源
    }
}
```

#### 6.3 注册 SPI 服务

创建文件 `src/main/resources/META-INF/services/com.baafoo.plugin.AgentPlugin`：

```
com.yourcompany.MyKafkaPlugin
```

#### 6.4 打包

```bash
mvn clean package
```

产物为 `target/my-baafoo-plugin-1.0.0.jar`（shade 后的 fat jar）。

---

### 7. 部署与配置

#### 7.1 部署

将编译好的 JAR 放入 Agent 的 `plugins/` 目录：

```bash
cp target/my-baafoo-plugin-1.0.0.jar /path/to/baafoo/plugins/
```

Agent 启动时自动扫描并加载。

#### 7.2 配置

在 `baafoo-agent.yml` 中添加插件级配置：

```yaml
plugins:
  enabled: true
  directory: "./plugins"
  configs:
    my-kafka:
      customPort: 9050
      logLevel: "DEBUG"
```

`configs` 下的 key 必须与 `AgentPlugin.getName()` 返回值一致。

---

### 8. 调试技巧

1. **查看插件加载日志**：Agent 启动时输出 `Plugin loaded: my-kafka (target=KAFKA, config=[customPort])`
2. **健康状态查询**：通过 Server REST API 查询插件健康状态：
   ```bash
   curl http://localhost:8084/__baafoo__/api/plugins
   curl http://localhost:8084/__baafoo__/api/status
   ```
3. **单元测试**：直接在 JUnit 中实例化插件并调用 `intercept()`，无需启动 Agent
4. **fail-closed 机制**：如果 `intercept()` 抛出异常，Agent 会自动降级到默认路由行为，不会影响应用正常运行

---

### 9. 健康监控

插件运行时会被 `PluginManager` 自动监控：

| 状态 | 含义 |
|------|------|
| `UNKNOWN` | 刚加载，尚未被调用 |
| `HEALTHY` | 最近调用全部成功 |
| `DEGRADED` | 有间歇性错误，但仍在服务 |
| `UNHEALTHY` | 连续失败 ≥5 次，已自动禁用 |
| `DISABLED` | 被管理员通过 REST API 手动禁用 |

连续 5 次 `intercept()` 抛异常后，插件自动进入 `UNHEALTHY` 状态并从拦截链中移除（等效于无插件）。

---

### 10. 最佳实践

1. **`getName()` 保持稳定**：配置索引和日志都依赖此名称，不要随意修改
2. **`intercept()` 不要抛异常**：用 `InterceptResult.passthrough()` 代替异常来放行
3. **`init()` / `destroy()` 保持轻量**：不要在 init 中启动长时间阻塞的操作
4. **利用 `pluginConfig`**：将可变参数（端口、超时等）放入 YAML 配置，而非硬编码
5. **二进制协议用 `redirect`**：Kafka/Pulsar/JMS 的连接建立阶段只能返回重定向地址
6. **HTTP 协议可用 `stub`**：在 Socket 拦截点上可以直接返回 Mock 响应体

---

### 11. 扩展新协议 Checklist

如果需要支持一种全新协议（如 Redis、gRPC），按以下清单执行：

- [ ] 在 `InterceptTarget` 枚举中添加新协议值（`baafoo-plugin-api`）
- [ ] 在 `PluginManager.resolveTarget()` 中添加协议名到 `InterceptTarget` 的映射
- [ ] 创建插件 Maven 工程，依赖 `baafoo-plugin-api`（scope=provided）
- [ ] 实现 `AgentPlugin` 接口（`getName`, `getTarget`, `configure`, `init`, `intercept`, `destroy`）
- [ ] 创建 `META-INF/services/com.baafoo.plugin.AgentPlugin` SPI 声明文件
- [ ] 在 `baafoo-agent` 的 `advice` 包中编写对应的 `@Advice` 类（需修改 Core）
- [ ] 在 `BaafooAgent.installTransforms()` 中注册新的 Transform
- [ ] 编写单元测试
- [ ] `mvn package` 构建 JAR，部署到 `plugins/` 目录
- [ ] 集成测试验证拦截效果

> **注意**：添加新协议需要修改 Agent Core（编写 Advice + 注册 Transform），这不是纯外部插件能完成的。外部插件只能定制已有 Advice 的处理逻辑。
