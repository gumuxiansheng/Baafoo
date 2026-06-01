# Baafoo Agent Plugin 开发指南

## 一、概述

Baafoo Agent 采用 **"Advice 内联留 Core + 逻辑委托 Plugin"** 的分层架构：

- **Core 层**（`baafoo-agent`）：负责字节码拦截（Byte Buddy `@Advice`），拦截点预定义在 Core 中
- **Plugin 层**（独立 JAR）：负责拦截后的业务逻辑（stub/record/passthrough），通过 Java SPI 加载

这种分层的核心原因是：Byte Buddy `@Advice` 的代码会被**内联**到目标类的字节码中，而目标类可能由 Bootstrap ClassLoader 加载。因此 Advice 类必须在 Bootstrap CL 可见，而 Plugin 的处理逻辑可以在独立的 ClassLoader 中运行。

```
应用代码调用 Feign Client
    ↓
[注入的 Advice 代码 — Bootstrap CL 中，始终可见]
    ↓
RouteResult route = RouteTable.lookup(host, port)
if (route == null) return;  // 不拦截
    ↓
AgentPlugin plugin = PluginManager.getPlugin(route.getProtocol())
InterceptResult result = plugin.intercept(ctx)  // Plugin 逻辑在独立 ClassLoader 中执行
    ↓
返回拦截结果（stub / passthrough / record）
```

---

## 二、核心接口

### 2.1 AgentPlugin（SPI 接口）

```java
package com.baafoo.plugin;

public interface AgentPlugin {
    String getName();                          // 插件唯一标识
    InterceptTarget getTarget();               // 拦截目标类型
    void init();                               // 初始化回调
    InterceptResult intercept(PluginContext ctx); // 拦截处理
    void destroy();                            // 销毁回调
}
```

### 2.2 InterceptTarget（拦截目标枚举）

```java
public enum InterceptTarget {
    SOCKET,        // java.net.Socket#connect()
    NIO_SOCKET,    // sun.nio.ch.SocketChannelImpl#connect()
    KAFKA,         // Kafka Producer/Consumer
    PULSAR,        // Pulsar Client
    JMS,           // JMS Producer/Consumer
    CONSUL_DNS,    // InetAddress#getByName()
    CONSUL_API,    // Consul HTTP API
    FEIGN          // OpenFeign 声明式 HTTP 客户端
}
```

> **新增协议**：在 `baafoo-plugin-api` 的 `InterceptTarget` 枚举中添加新值即可。

### 2.3 PluginContext（拦截上下文）

```java
public class PluginContext {
    String protocol;                    // 协议：http, tcp, kafka, pulsar, jms
    String host;                        // 目标主机
    int port;                           // 目标端口
    String serviceName;                 // 服务名（Consul 发现场景）
    Map<String, String> headers;        // 请求头
    byte[] requestData;                 // 请求体
    Callable<InterceptResult> originalCall; // 原始调用（可执行真实请求）
    String ruleId;                      // 匹配的规则 ID
    String ruleName;                    // 匹配的规则名
    boolean recording;                  // 是否录制模式
}
```

### 2.4 InterceptResult（拦截结果）

```java
public class InterceptResult {
    boolean stubbed;                    // 是否已 stub
    byte[] responseData;                // 响应体
    Map<String, String> responseHeaders; // 响应头
    int statusCode;                     // HTTP 状态码
    String errorMessage;                // 错误信息

    // 工厂方法
    static InterceptResult stub(byte[] data, Map<String, String> headers, int statusCode);
    static InterceptResult passthrough();
    static InterceptResult error(String message);
}
```

---

## 三、开发步骤

### Step 1：创建 Maven 工程

```xml
<project>
    <groupId>com.baafoo</groupId>
    <artifactId>baafoo-plugin-feign</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <dependencies>
        <!-- Plugin API（生产环境由 Bootstrap CL 提供，scope=provided） -->
        <dependency>
            <groupId>com.baafoo</groupId>
            <artifactId>baafoo-plugin-api</artifactId>
            <version>${baafoo.version}</version>
        </dependency>

        <!-- 协议特定依赖（打包进 plugin jar） -->
        <dependency>
            <groupId>io.github.openfeign</groupId>
            <artifactId>feign-core</artifactId>
            <version>${feign.version}</version>
        </dependency>
    </dependencies>
</project>
```

### Step 2：实现 AgentPlugin 接口

```java
package com.baafoo.plugin.feign;

import com.baafoo.plugin.*;

public class FeignPlugin implements AgentPlugin {

    @Override
    public String getName() {
        return "feign-plugin";
    }

    @Override
    public InterceptTarget getTarget() {
        return InterceptTarget.FEIGN;
    }

    @Override
    public void init() {
        // 注册默认 stub、初始化资源
    }

    @Override
    public InterceptResult intercept(PluginContext ctx) {
        // 1. 根据 ctx 中的信息查找 stub
        // 2. 找到 → 返回 stub 结果
        // 3. 未找到 → 执行 originalCall 或返回 passthrough
        String method = ctx.getHeaders().get("X-Feign-Method");
        String path = ctx.getHeaders().get("X-Feign-Path");
        // ... 查找 stub 逻辑 ...

        if (stubFound) {
            return InterceptResult.stub(body, headers, statusCode);
        }
        if (ctx.getOriginalCall() != null) {
            return ctx.getOriginalCall().call();
        }
        return InterceptResult.passthrough();
    }

    @Override
    public void destroy() {
        // 释放资源
    }
}
```

### Step 3：注册 SPI 服务

创建文件 `src/main/resources/META-INF/services/com.baafoo.plugin.AgentPlugin`：

```
com.baafoo.plugin.feign.FeignPlugin
```

### Step 4：编写 Advice 类（在 Core 中）

> **关键约束**：Advice 类必须在 `baafoo-agent` 模块中定义，因为 `@Advice` 代码会被内联到目标类的字节码中，Bootstrap ClassLoader 必须能找到它。

```java
package com.baafoo.agent.advice;

import feign.Request;
import net.bytebuddy.asm.Advice;

public class FeignClientAdvice {

    @Advice.OnMethodEnter
    public static void onExecute(
            @Advice.Argument(value = 0, readOnly = false) Request request) {
        // 1. 提取请求信息（method, url, headers）
        // 2. 查路由表判断是否拦截
        // 3. 如果拦截，修改请求目标地址或设置拦截标记
    }

    @Advice.OnMethodExit
    public static void afterExecute(@Advice.Return Response response) {
        // 后置处理（如录制响应）
    }
}
```

### Step 5：在 Agent 中注册 Transform

在 `BaafooAgent.installTransforms()` 中添加：

```java
agentBuilder = agentBuilder
    .type(named("feign.Client"))
    .transform((builder, typeDesc, classLoader, module, pd) ->
        builder.visit(Advice.to(FeignClientAdvice.class)
            .on(named("execute")
                .and(takesArguments(2)))));
registry.register("feign.Client", "FeignClientAdvice", "feign");
```

### Step 6：打包与部署

```bash
# 构建插件 JAR（包含协议依赖）
mvn clean package

# 部署到 Agent 的 plugins 目录
cp target/baafoo-plugin-feign-1.0.0-SNAPSHOT.jar $AGENT_HOME/plugins/
```

Agent 启动时会自动扫描 `plugins/` 目录下的 JAR 文件，通过 `ServiceLoader` 发现并加载插件。

---

## 四、ClassLoader 隔离机制

```
Bootstrap ClassLoader
  ├── baafoo-agent.jar          ← appendToBootstrapClassLoaderSearch()
  │     ├── Advice 类（SocketConnectAdvice, FeignClientAdvice 等）
  │     ├── PluginManager
  │     └── GlobalRouteState / RouteTable
  │
  └── baafoo-plugin-api.jar    ← SPI 接口，Bootstrap CL 可见
        └── AgentPlugin, PluginContext, InterceptTarget, InterceptResult

Plugin ClassLoader (URLClassLoader, parent=null)  ← 每个 plugin JAR 独立
  └── baafoo-plugin-feign.jar
        ├── FeignPlugin（实现 AgentPlugin，接口从 Bootstrap CL 加载 ✅）
        └── 依赖：feign-core, feign-jackson（仅在此 ClassLoader 内可见）

AppClassLoader
  └── 应用本身的依赖（如 feign-core 11.8）
        ← 与 Plugin ClassLoader 中的 feign-core 11.10 互不干扰 ✅
```

**关键规则**：

1. `PluginClassLoader` 的 `parent = null`，插件看不到应用的类
2. SPI 接口（`AgentPlugin` 等）通过 Bootstrap CL 加载，Core 和 Plugin 都能访问
3. 插件内的协议依赖（如 feign-core）只在插件 ClassLoader 内可见，与应用的同名依赖完全隔离

---

## 五、拦截模式说明

| 模式 | 行为 | InterceptResult |
|------|------|-----------------|
| **stub** | 返回预配置的 mock 响应 | `InterceptResult.stub(data, headers, statusCode)` |
| **passthrough** | 放行原始请求 | `InterceptResult.passthrough()` |
| **record** | 执行原始请求，记录响应 | 调用 `ctx.getOriginalCall().call()` 并存储 |
| **record-and-stub** | 执行原始请求 + 返回 stub | 同时 record 和 stub |

---

## 六、完整示例：Feign 插件

本项目（`baafoo-test-plugin/feign`）是一个完整的 Feign Client 拦截插件示例，包含：

```
baafoo-test-plugin/feign/
├── pom.xml
├── src/main/java/com/baafoo/plugin/feign/
│   ├── FeignPlugin.java              ← AgentPlugin 实现（stub 注册、拦截逻辑）
│   ├── FeignClientAdvice.java        ← Byte Buddy Advice（参考实现，需移至 Core）
│   └── demo/
│       └── FeignPluginDemo.java      ← 独立演示应用
├── src/main/resources/
│   └── META-INF/services/
│       └── com.baafoo.plugin.AgentPlugin   ← SPI 声明
└── src/test/java/com/baafoo/plugin/feign/
    └── FeignPluginTest.java          ← 单元测试（14 个测试用例）
```

### 运行演示

```bash
cd baafoo-test-plugin/feign
mvn compile exec:java -Dexec.mainClass=com.baafoo.plugin.feign.demo.FeignPluginDemo
```

### 运行测试

```bash
cd baafoo-test-plugin/feign
mvn test
```

---

## 七、注意事项与最佳实践

### 7.1 Advice 类必须在 Core 中

`@Advice` 注解的方法在 premain 阶段被**内联**到目标类字节码中。如果目标类由 Bootstrap CL 加载，Advice 类也必须在 Bootstrap CL 可见。因此：

- ✅ **正确做法**：Advice 类写在 `baafoo-agent` 模块的 `advice` 包中
- ❌ **错误做法**：Advice 类写在 Plugin JAR 中（Bootstrap CL 找不到 → ClassNotFoundException）

### 7.2 Plugin 只做逻辑处理

Plugin 的 `intercept()` 方法只负责：
- 查找匹配的 stub
- 构造拦截结果
- 调用原始请求（passthrough/record 模式）

不负责字节码拦截本身。

### 7.3 PluginContext 是跨 ClassLoader 传递的 POJO

`PluginContext` 和 `InterceptResult` 都是简单 POJO，只使用 JDK 类型（String, byte[], Map），确保跨 ClassLoader 传递无障碍。

### 7.4 依赖隔离

插件 JAR 中可以包含任意协议依赖（feign-core, pulsar-client 等），这些依赖只在插件的 ClassLoader 内可见，不会与应用的同名依赖冲突。

### 7.5 初始化失败降级

如果插件初始化失败（如版本不兼容），Agent 会捕获异常并打印 WARN 日志，该协议自动降级为 passthrough 模式，不影响其他协议。

### 7.6 性能考量

`PluginManager.getPlugin()` 使用 `ConcurrentHashMap` 做 protocol→plugin 映射，O(1) 查找，在热路径上无性能损耗。

---

## 八、扩展新协议 Checklist

开发一个新的协议插件，按以下清单执行：

- [ ] 在 `InterceptTarget` 枚举中添加新协议值
- [ ] 创建 Maven 工程，依赖 `baafoo-plugin-api`
- [ ] 实现 `AgentPlugin` 接口（getName, getTarget, init, intercept, destroy）
- [ ] 创建 `META-INF/services/com.baafoo.plugin.AgentPlugin` SPI 声明文件
- [ ] 在 `baafoo-agent` 的 `advice` 包中编写对应的 `@Advice` 类
- [ ] 在 `BaafooAgent.installTransforms()` 中注册新的 Transform
- [ ] 在 `PluginManager.resolveTarget()` 中添加协议名到 InterceptTarget 的映射
- [ ] 编写单元测试
- [ ] `mvn package` 构建 JAR，部署到 `plugins/` 目录
- [ ] 集成测试验证拦截效果
