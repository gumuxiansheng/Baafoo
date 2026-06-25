# Baafoo Plugin Architecture Enhancement — Phase 1-3 Design (v2)

> **Author:** 代可行
> **Date:** 2026-06-25
> **Status:** Design (revised — v2 fixes 4 architecture constraints)
> **Scope:** baafoo-plugin-api, baafoo-core, baafoo-agent, baafoo-server

---

## 0. v2 修订说明

v1 存在 4 个架构约束违反，v2 逐项修复：

| # | 问题 | v1 错误 | v2 修复 |
|---|------|---------|---------|
| P1 | PluginServices 引用 `com.baafoo.core.model.*` | 定义在 baafoo-plugin-api 中引用 core 模型 | 服务接口使用 `Map<String,Object>` 基础类型，零领域模型依赖 |
| P2 | Server 侧无 PluginManager | Server Handler 直接调用 `pluginManager.fireEvent()` | 事件总线下沉到 baafoo-core，Agent/Server 各持独立实例 |
| P3 | GrpcChannelAdvice 不经 PluginManager | 设计中要求改为 `connectWithMonitor()` | 保持现状，GrpcChannelAdvice 继续用 `GlobalRouteState.lookup()` |
| P4 | Bootstrap CL 桥接返回值语义不足 | PLUGIN_CONSULT_FN 只返回 `{host, port}` | 扩展为 `{action, host, port, reason}`，兼容旧签名 |

---

## 1. 背景与目标

### 1.1 现状

Baafoo 当前插件体系基于单一 `AgentPlugin` 接口：

```java
public interface AgentPlugin {
    String getName();
    InterceptTarget getTarget();
    default void configure(Map<String, Object> config) {}
    void init();
    InterceptResult intercept(PluginContext ctx);
    void destroy();
}
```

**问题：**

| # | 问题 | 影响 |
|---|------|------|
| P1 | 所有逻辑集中在 `intercept()` 一个方法 | 插件需自行判断阶段，逻辑臃肿 |
| P2 | `PluginContext` 缺少核心服务访问能力 | 插件无法主动查询规则、存储录制、调用 Admin API |
| P3 | 无事件机制 | 监控、日志、Metrics 等横切关注点必须侵入核心 Handler |
| P4 | 规则热重载无通知 | 插件无法感知规则变更，缓存可能过期 |

### 1.2 目标

借鉴 WireMock Extension 体系，在**保持 Agent 字节码拦截优势**的前提下：

1. **Phase 1**：拆分 `AgentPlugin` 接口为细粒度钩子 + `PluginContext` 服务注入
2. **Phase 2**：事件总线（`PluginEvent` + `EventBus`）+ Handler 埋点
3. **Phase 3**：`RuleChangeListener`（复用事件总线）

### 1.3 设计原则

- **向后兼容**：现有插件不改代码仍能运行
- **最小接口**：4 个钩子 + 事件总线，不照搬 WireMock 10+ 接口
- **Agent 优先**：`onConnect` 钩子是 Baafoo 独有
- **零侵入事件**：事件监听不参与主流程，失败不影响请求处理
- **零依赖约束**：`baafoo-plugin-api` 只依赖 slf4j-api，不引入任何领域模型（v2 新增）

### 1.4 模块依赖关系（不可变）

```
baafoo-plugin-api  (零外部依赖，Bootstrap CL 可加载)
       ↑
baafoo-core        (依赖 plugin-api，含领域模型)
       ↑
baafoo-agent       (依赖 core + plugin-api)
baafoo-server      (依赖 core + plugin-api)
```

> **硬约束：** baafoo-plugin-api 绝不能依赖 baafoo-core。所有定义在 plugin-api 中的接口只能使用 JDK 原生类型或 plugin-api 内部类型。

---

## 2. Phase 1：接口拆分 + 服务注入

### 2.1 AgentPlugin 接口演进

#### 2.1.1 新接口定义

```java
package com.baafoo.plugin;

import java.util.Map;

/**
 * SPI interface for Baafoo agent plugins.
 *
 * <p><b>Backward compatibility:</b> Plugins that only implement {@link #intercept}
 * continue to work unchanged. The new hooks are {@code default} methods that
 * delegate to {@code intercept()} for legacy plugins.</p>
 */
public interface AgentPlugin {

    // ---- Identity ----

    String getName();
    InterceptTarget getTarget();

    // ---- Lifecycle ----

    default void configure(Map<String, Object> config) {}
    void init();
    void destroy();

    // ---- Phase Hooks (new in v2) ----

    /**
     * Connection-phase hook. Called before the actual connection is established.
     * <p><b>Agent-only:</b> No equivalent in WireMock.</p>
     *
     * <p>Default implementation delegates to {@link #intercept} for backward
     * compatibility. New plugins should override this instead of intercept().</p>
     */
    default ConnectAdvice onConnect(ConnectContext ctx) {
        PluginContext legacy = ctx.toLegacyContext();
        InterceptResult result = intercept(legacy);
        return ConnectAdvice.fromInterceptResult(result);
    }

    /**
     * Request-phase hook. Called after the request is parsed but before
     * rule matching.
     */
    default RequestAdvice onRequest(RequestContext ctx) {
        return RequestAdvice.continue();
    }

    /**
     * Response-phase hook. Called after the stub response is generated
     * but before it is sent to the client.
     */
    default ResponseAdvice onResponse(ResponseContext ctx) {
        return ResponseAdvice.continue();
    }

    /**
     * Event hook. Called for lifecycle events that don't participate in
     * the request flow. Implementations must not throw — exceptions are
     * caught and logged by the PluginManager / EventBus.
     */
    default void onEvent(PluginEvent event) {}

    // ---- Legacy (deprecated) ----

    /**
     * @deprecated Use {@link #onConnect}, {@link #onRequest}, or
     *             {@link #onResponse} instead.
     */
    @Deprecated
    InterceptResult intercept(PluginContext ctx);
}
```

#### 2.1.2 向后兼容策略

```
新插件路径:  Advice → onConnect() → onRequest() → onResponse() → onEvent()
                          ↓ (如果插件没覆写 onConnect)
旧插件路径:  Advice → intercept()  (通过 default 方法委托)
```

#### 2.1.3 PluginManager 适配

```java
public class PluginManager {

    /**
     * P1: Connection-phase hook with health monitoring.
     */
    public ConnectAdvice connectWithMonitor(InterceptTarget target, ConnectContext ctx) {
        AgentPlugin plugin = getPlugin(target);
        if (plugin == null) return ConnectAdvice.passthrough();
        // ... health monitoring ...
        return plugin.onConnect(ctx);
    }

    public RequestAdvice requestWithMonitor(InterceptTarget target, RequestContext ctx) {
        AgentPlugin plugin = getPlugin(target);
        if (plugin == null) return RequestAdvice.continue();
        return plugin.onRequest(ctx);
    }

    public ResponseAdvice responseWithMonitor(InterceptTarget target, ResponseContext ctx) {
        AgentPlugin plugin = getPlugin(target);
        if (plugin == null) return ResponseAdvice.continue();
        return plugin.onResponse(ctx);
    }
}
```

### 2.2 ConnectContext / ConnectAdvice

#### 2.2.1 ConnectContext

```java
package com.baafoo.plugin;

/**
 * Context for the connection-phase hook.
 * Only uses JDK types and plugin-api internal types — no domain model dependency.
 */
public class ConnectContext {

    private final String protocol;
    private final String host;
    private final int port;
    private final String serviceName;
    private final String environmentId;
    private final String agentId;
    private final String rawTarget;

    public ConnectContext(String protocol, String host, int port,
                          String serviceName, String environmentId,
                          String agentId, String rawTarget) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.serviceName = serviceName;
        this.environmentId = environmentId;
        this.agentId = agentId;
        this.rawTarget = rawTarget;
    }

    // --- Getters ---

    public String getProtocol() { return protocol; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getServiceName() { return serviceName; }
    public String getEnvironmentId() { return environmentId; }
    public String getAgentId() { return agentId; }
    public String getRawTarget() { return rawTarget; }

    /**
     * Convert to legacy PluginContext for backward-compatible intercept() call.
     */
    public PluginContext toLegacyContext() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol(protocol);
        ctx.setHost(host);
        ctx.setPort(port);
        ctx.setServiceName(serviceName);
        return ctx;
    }
}
```

#### 2.2.2 ConnectAdvice

```java
package com.baafoo.plugin;

/**
 * Advice returned from the connection-phase hook.
 */
public class ConnectAdvice {

    public enum Action {
        PASSTHROUGH,
        REDIRECT,
        BLOCK
    }

    private final Action action;
    private final String redirectHost;
    private final int redirectPort;
    private final String blockReason;

    private ConnectAdvice(Action action, String redirectHost, int redirectPort, String blockReason) {
        this.action = action;
        this.redirectHost = redirectHost;
        this.redirectPort = redirectPort;
        this.blockReason = blockReason;
    }

    public static ConnectAdvice passthrough() {
        return new ConnectAdvice(Action.PASSTHROUGH, null, 0, null);
    }

    public static ConnectAdvice redirect(String host, int port) {
        return new ConnectAdvice(Action.REDIRECT, host, port, null);
    }

    public static ConnectAdvice block(String reason) {
        return new ConnectAdvice(Action.BLOCK, null, 0, reason);
    }

    /**
     * Convert from legacy InterceptResult.
     */
    public static ConnectAdvice fromInterceptResult(InterceptResult result) {
        if (result == null) return passthrough();
        if (result.isRedirect()) {
            return redirect(result.getRedirectHost(), result.getRedirectPort());
        }
        if (result.isStubbed()) {
            return block(result.getErrorMessage() != null ? result.getErrorMessage() : "stubbed");
        }
        return passthrough();
    }

    // --- Getters ---

    public Action getAction() { return action; }
    public String getRedirectHost() { return redirectHost; }
    public int getRedirectPort() { return redirectPort; }
    public String getBlockReason() { return blockReason; }
    public boolean isPassthrough() { return action == Action.PASSTHROUGH; }
    public boolean isRedirect() { return action == Action.REDIRECT; }
    public boolean isBlock() { return action == Action.BLOCK; }
}
```

### 2.3 RequestContext / RequestAdvice / ResponseContext / ResponseAdvice

> 这四个类与 v1 设计一致，仅使用 JDK 原生类型（String, Map, byte[]）。完整定义见附录 B。

### 2.4 PluginServices 服务注入（v2 修订：零领域模型依赖）

#### 2.4.1 问题回顾

v1 中 `RuleStore` 引用 `com.baafoo.core.model.Rule`，`RecordingStore` 引用 `com.baafoo.core.model.RecordingEntry`。但 `baafoo-plugin-api` 的 pom 零外部依赖（仅 slf4j-api），且 `baafoo-core` 依赖 `baafoo-plugin-api`（不可反向）。引入 core 模型会制造循环依赖。

#### 2.4.2 v2 方案：基础类型接口

```java
package com.baafoo.plugin.service;

import java.util.List;
import java.util.Map;

/**
 * Read-only access to the rule store.
 * Uses Map<String,Object> instead of domain models to keep baafoo-plugin-api
 * zero-dependency. Plugins that need strong typing can convert manually.
 *
 * <p>Map keys for rules: id, name, protocol, host, port, serviceName,
 * priority, enabled, environments, conditions (List<Map>), responses (List<Map>).</p>
 */
public interface RuleStore {

    /** List all rules for the given environment. Returns empty list if none. */
    List<Map<String, Object>> listRules(String environmentId);

    /** Get a specific rule by ID. Returns null if not found. */
    Map<String, Object> getRule(String ruleId);
}

/**
 * Recording storage service.
 * Map keys for recordings: id, ruleId, protocol, host, port, method, path,
 * requestBody, responseBody, responseStatusCode, recordedAt, environmentId.
 */
public interface RecordingStore {

    /** Save a recording entry. */
    void save(Map<String, Object> recording);

    /** Query recordings by environment. Returns empty list if none. */
    List<Map<String, Object>> listByEnvironment(String environmentId, int limit);
}

/**
 * Server-side administration API.
 */
public interface ServerAdmin {

    /** Register a custom admin endpoint at the given path. */
    void registerEndpoint(String path, AdminHandler handler);

    /** Trigger a rule reload. */
    void reloadRules();

    /** Get server config value by key. Returns null if not found. */
    String getConfig(String key);
}

/**
 * Custom admin endpoint handler.
 */
@FunctionalInterface
public interface AdminHandler {
    /** Handle an admin API request. */
    String handle(String method, String path, Map<String, String> headers, String body);
}

/**
 * Unified service context injected into PluginContext.
 * Null when running in Agent-only mode (no Server attached).
 */
public interface PluginServices {
    RuleStore getRuleStore();
    RecordingStore getRecordingStore();
    ServerAdmin getServerAdmin();
}
```

#### 2.4.3 PluginContext 扩展

```java
public class PluginContext {
    // ... existing fields ...

    /** P1: Injected services (null in Agent-only mode) */
    private PluginServices services;

    public PluginServices getServices() { return services; }
    public void setServices(PluginServices services) { this.services = services; }
}
```

#### 2.4.4 服务注入位置

```
PluginServices 实现 (baafoo-server，可访问 StorageService + 领域模型)
       │
  PluginManager (baafoo-agent)
       │
  ┌────┴────────────────┐
  ↓                     ↓
ConnectContext     PluginContext
```

- `PluginServices` 接口定义在 `baafoo-plugin-api`（零依赖）
- 实现在 `baafoo-server`（`PluginServicesImpl` 包装 `StorageService`，内部做 `Rule` → `Map` 转换）
- Agent 启动时从 Server 获取实现实例（通过 HTTP API 或直接注入）
- 纯 Agent 模式下 `services` 为 null，插件需 null-check

#### 2.4.5 与 WireMock ExtensionFactory 对比

| WireMock | Baafoo |
|----------|--------|
| `ExtensionFactory` 注入 Admin/Options/Stores/Files/Extensions | `PluginServices` 注入 RuleStore/RecordingStore/ServerAdmin |
| Extension 直接操作强类型领域模型 | 插件操作 `Map<String,Object>`，保持 plugin-api 零依赖 |
| 所有 Extension 共享同一服务实例 | 同上 |

### 2.5 GrpcChannelAdvice 处理策略（v2 修订）

#### 2.5.1 问题回顾

v1 设计要求 GrpcChannelAdvice 改为调用 `pluginManager.connectWithMonitor()`。实际上 GrpcChannelAdvice 运行在 App CL，通过 `GlobalRouteState.lookup()` 做路由查找，不经过 PluginManager。

#### 2.5.2 v2 决策：保持现状

GrpcChannelAdvice **不改造**。理由：

1. 它已经通过 `GlobalRouteState.lookup()` 正确工作
2. gRPC channel 重定向逻辑简单（查路由表 → 替换 target），不需要插件的细粒度控制
3. 如果需要 gRPC 插件干预，插件可以实现 `onConnect` 钩子，通过 SocketConnectAdvice 的 PLUGIN_CONSULT_FN 桥接生效（因为 gRPC 底层也是 Socket）

> **结论：** Phase 1 不修改 GrpcChannelAdvice。Phase 2 的 Agent 侧事件埋点通过 GlobalRouteState 桥接触发，不依赖 PluginManager。

### 2.6 Bootstrap CL 桥接扩展（v2 修订：PLUGIN_CONSULT_FN 返回值语义）

#### 2.6.1 问题回顾

`GlobalRouteState.PLUGIN_CONSULT_FN` 当前签名：
```java
Function<Object[], Object[]>
// 入参: {host, port}
// 返回: {targetHost, targetPort} 或 null
```

只支持 redirect 语义，无法表达 passthrough（明确放行）或 block（阻止连接）。新 `onConnect` 钩子返回 `ConnectAdvice`，包含三种 Action。

#### 2.6.2 v2 方案：扩展返回值，兼容旧签名

```java
// GlobalRouteState 中新增

/**
 * Extended plugin consultation function (P1).
 *
 * <p>Arguments: Object[] { String host, Integer port, String protocol }</p>
 *
 * <p>Returns: Object[] { Integer action, String targetHost, Integer targetPort, String reason }</p>
 * <ul>
 *   <li>action=0: PASSTHROUGH (targetHost/targetPort ignored)</li>
 *   <li>action=1: REDIRECT to targetHost:targetPort</li>
 *   <li>action=2: BLOCK with reason</li>
 *   <li>null: no plugin consulted (fallback to default routing)</li>
 * </ul>
 *
 * <p><b>Backward compatibility:</b> If PLUGIN_CONSULT_FN_EXT returns null
 * or is not set, the old PLUGIN_CONSULT_FN is consulted as fallback.
 * The old function's return value {host, port} is interpreted as action=1 (REDIRECT).</p>
 */
public static volatile java.util.function.Function<Object[], Object[]> PLUGIN_CONSULT_FN_EXT;
```

#### 2.6.3 SocketConnectAdvice 适配

```java
// In SocketConnectAdvice (and NioSocketConnectAdvice symmetrically)

// P1: Try extended plugin consultation first
java.util.function.Function<Object[], Object[]> consultExt = GlobalRouteState.PLUGIN_CONSULT_FN_EXT;
if (consultExt != null) {
    try {
        Object[] result = consultExt.apply(new Object[]{host, port, protocol});
        if (result != null && result.length >= 1) {
            int action = (Integer) result[0];
            switch (action) {
                case 0: // PASSTHROUGH
                    // Skip redirect, proceed with original target
                    break;
                case 1: // REDIRECT
                    String rHost = (String) result[1];
                    int rPort = (Integer) result[2];
                    routeValue = new String[]{rHost, String.valueOf(rPort)};
                    break;
                case 2: // BLOCK
                    GlobalRouteState.logInfo("[Baafoo] Connection blocked by plugin: " + result[3]);
                    throw new java.net.ConnectException(
                        result[3] != null ? result[3].toString() : "Blocked by plugin");
                    // (Advice cannot throw; actual implementation uses a different
                    //  mechanism — e.g., connect to a closed port to fail fast.)
                default:
                    // Unknown action, treat as passthrough
            }
        }
    } catch (Throwable t) {
        GlobalRouteState.logDebug("[Baafoo] Extended plugin consult skipped: " + t.getMessage());
    }
}

// Fallback: legacy PLUGIN_CONSULT_FN (unchanged)
if (routeValue == null) {
    java.util.function.Function<Object[], Object[]> consultFn = GlobalRouteState.PLUGIN_CONSULT_FN;
    // ... existing logic unchanged ...
}
```

#### 2.6.4 PluginManager 侧桥接设置

```java
// In BaafooAgent.setupPluginBridge()

GlobalRouteState.PLUGIN_CONSULT_FN_EXT = (args) -> {
    String host = (String) args[0];
    int port = (Integer) args[1];
    String protocol = args.length > 2 ? (String) args[2] : "tcp";

    InterceptTarget target = resolveTarget(protocol);
    if (target == null) return null;

    AgentPlugin plugin = pluginManager.getPlugin(target);
    if (plugin == null) return null;

    ConnectContext ctx = new ConnectContext(protocol, host, port, null, null, null, null);
    ConnectAdvice advice = plugin.onConnect(ctx);

    switch (advice.getAction()) {
        case PASSTHROUGH:
            return new Object[]{0, null, null, null};
        case REDIRECT:
            return new Object[]{1, advice.getRedirectHost(), advice.getRedirectPort(), null};
        case BLOCK:
            return new Object[]{2, null, null, advice.getBlockReason()};
        default:
            return null;
    }
};
```

### 2.7 文件变更清单（Phase 1）

| 操作 | 文件 | 模块 | 说明 |
|------|------|------|------|
| 新建 | `ConnectContext.java` | plugin-api | 连接阶段上下文（零领域模型依赖） |
| 新建 | `ConnectAdvice.java` | plugin-api | 连接阶段返回值 |
| 新建 | `RequestContext.java` | plugin-api | 请求阶段上下文 |
| 新建 | `RequestAdvice.java` | plugin-api | 请求阶段返回值 |
| 新建 | `ResponseContext.java` | plugin-api | 响应阶段上下文 |
| 新建 | `ResponseAdvice.java` | plugin-api | 响应阶段返回值 |
| 新建 | `service/PluginServices.java` | plugin-api | 服务注入接口（Map 基础类型） |
| 新建 | `service/RuleStore.java` | plugin-api | 规则存储服务（Map 基础类型） |
| 新建 | `service/RecordingStore.java` | plugin-api | 录制存储服务（Map 基础类型） |
| 新建 | `service/ServerAdmin.java` | plugin-api | 管理服务接口 |
| 新建 | `service/AdminHandler.java` | plugin-api | 自定义 Admin 端点 |
| 修改 | `AgentPlugin.java` | plugin-api | 新增 4 个 default 方法 |
| 修改 | `PluginContext.java` | plugin-api | 新增 `services` 字段 |
| 修改 | `PluginManager.java` | agent | 新增 connectWithMonitor/requestWithMonitor/responseWithMonitor |
| 修改 | `GlobalRouteState.java` | agent | 新增 `PLUGIN_CONSULT_FN_EXT` |
| 修改 | `SocketConnectAdvice.java` | agent | 优先使用 EXT 桥接，fallback 旧桥接 |
| 修改 | `NioSocketConnectAdvice.java` | agent | 同上 |
| 修改 | `BaafooAgent.java` | agent | setupPluginBridge 中设置 EXT 函数 |
| 新建 | `PluginServicesImpl.java` | server | 实现 PluginServices（包装 StorageService） |
| **不修改** | `GrpcChannelAdvice.java` | agent | 保持现状（v2 决策） |

---

## 3. Phase 2：事件总线 + Handler 埋点

### 3.1 事件总线下沉到 baafoo-core（v2 修订）

#### 3.1.1 问题回顾

v1 将事件总线放在 `PluginManager`（baafoo-agent）中。但 Server 侧 Handler（HttpStubHandler、GrpcUnifiedHandler 等）需要直接触发事件，它们不持有 PluginManager 实例。

#### 3.1.2 v2 方案：EventBus 定义在 baafoo-core

```java
package com.baafoo.core.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight event bus for broadcasting PluginEvents.
 * Defined in baafoo-core so both Agent and Server can use it independently.
 *
 * <p>Events are <b>observation-only</b>: listeners must not throw. Exceptions
 * are caught and logged — they never affect the request flow.</p>
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    /** The shared PluginEvent class is in baafoo-core (or baafoo-plugin-api). */
    // → PluginEvent 定义在 baafoo-plugin-api（见下文），因为 Agent 的 onEvent
    //   需要接收 PluginEvent，而 Agent 插件只依赖 plugin-api。

    public void addListener(EventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(EventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Fire an event to all registered listeners.
     * Exceptions from listeners are caught and logged.
     */
    public void fire(PluginEvent event) {
        for (EventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Throwable t) {
                log.warn("[Baafoo] Event listener threw: {}", t.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface EventListener {
        void onEvent(PluginEvent event);
    }
}
```

#### 3.1.3 PluginEvent 定义位置

`PluginEvent` 必须定义在 `baafoo-plugin-api`（不是 baafoo-core），因为：

1. `AgentPlugin.onEvent(PluginEvent)` 在 plugin-api 中声明
2. 插件只依赖 plugin-api，需要能访问 PluginEvent
3. baafoo-core 依赖 plugin-api，可以访问 PluginEvent

```java
// bafoo-plugin-api 中
package com.baafoo.plugin;

import java.util.Map;

public class PluginEvent {
    // ... 完整定义同 v1，使用 JDK 原生类型 ...
    // 16 种事件类型，工厂方法，零领域模型依赖
}
```

`EventBus` 在 baafoo-core 中，引用 `PluginEvent`（plugin-api），合法。

#### 3.1.4 双实例架构

```
┌─────────────────────────────────────────────┐
│ baafoo-agent (JVM 进程)                      │
│                                              │
│  PluginManager                               │
│    └─ EventBus agentBus  ←─ Agent 侧埋点     │
│         │                                    │
│         ├─ Plugin A.onEvent()                │
│         ├─ Plugin B.onEvent()                │
│         └─ (外部 listener)                    │
│                                              │
│  GlobalRouteState                            │
│    └─ EVENT_QUEUE  ←── Advice 侧写入         │
│         │                                    │
│         └─→ agentBus.fire()  (异步消费)      │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│ baafoo-server (JVM 进程，可能同进程也可能分离) │
│                                              │
│  EventBus serverBus  ←── Server 侧埋点       │
│    ├─ Plugin A.onEvent()  (如果同进程)        │
│    └─ (外部 listener: Metrics、Audit 等)     │
│                                              │
│  HttpStubHandler                             │
│    └─ serverBus.fire(REQUEST_RECEIVED)       │
│                                              │
│  RuleApiHandler                              │
│    └─ serverBus.fire(RULE_CHANGED)           │
└─────────────────────────────────────────────┘
```

- **同进程部署**（Agent + Server 一体）：共享一个 EventBus 实例
- **分离部署**：各自独立 EventBus，互不感知（Agent 事件不传到 Server，反之亦然）
- **插件 onEvent**：PluginManager 在注册插件时自动将 `plugin::onEvent` 注册为 EventBus listener

#### 3.1.5 PluginManager 与 EventBus 的关系

```java
public class PluginManager {

    private final EventBus eventBus;

    public PluginManager(...) {
        this.eventBus = new EventBus();
        // ...
    }

    /**
     * P2: Expose EventBus for external listeners (Metrics, Audit, etc.)
     */
    public EventBus getEventBus() { return eventBus; }

    /**
     * P2: Fire event to EventBus + all plugins' onEvent.
     */
    public void fireEvent(PluginEvent event) {
        eventBus.fire(event);
        // Also deliver to all plugins' onEvent hook
        for (AgentPlugin plugin : plugins.values()) {
            try {
                plugin.onEvent(event);
            } catch (Throwable t) {
                log.warn("[Baafoo] Plugin {} onEvent threw: {}", plugin.getName(), t.getMessage());
            }
        }
    }

    private void loadPlugin(File jarFile) throws Exception {
        // ... existing loading logic ...
        for (AgentPlugin plugin : loader) {
            plugin.configure(config);
            plugin.init();
            plugins.put(plugin.getTarget(), plugin);
            // P2: Auto-register plugin as event listener
            eventBus.addListener(plugin::onEvent);
        }
    }
}
```

### 3.2 Server 侧 EventBus 注入

#### 3.2.1 Server 启动时创建 EventBus

```java
public class BaafooServer {

    private EventBus serverEventBus;

    private void start() {
        this.serverEventBus = new EventBus();

        // If Agent is in-process, share the same EventBus
        PluginManager pm = pluginManager; // may be null in server-only mode
        if (pm != null) {
            // Merge: Server handlers use pm's EventBus
            serverEventBus = pm.getEventBus();
        }

        // Pass to handlers
        httpStubHandler = new HttpStubHandler(storage, config, workerGroup, serverEventBus);
        grpcUnifiedHandler = new GrpcUnifiedHandler(storage, config, serverEventBus);
        tcpStubHandler = new TcpStubHandler(storage, config, serverEventBus);
        ruleApiHandler = new RuleApiHandler(storage, serverEventBus);
    }
}
```

#### 3.2.2 Handler 构造函数变更

```java
public class HttpStubHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final EventBus eventBus; // may be null (no event bus configured)

    public HttpStubHandler(StorageService storage, ServerConfig config,
                           EventLoopGroup workerGroup, EventBus eventBus) {
        // ... existing init ...
        this.eventBus = eventBus; // null-safe: fireEvent checks for null
    }

    private void fireEvent(PluginEvent event) {
        if (eventBus != null) eventBus.fire(event);
    }
}
```

### 3.3 PluginEvent 设计

#### 3.3.1 事件类型

```java
package com.baafoo.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class PluginEvent {

    public enum Type {
        // ---- Request lifecycle ----
        REQUEST_RECEIVED,
        RULE_MATCHED,
        RULE_NOT_MATCHED,
        RESPONSE_SENT,

        // ---- Recording ----
        RECORDING_SAVED,
        RECORDING_STARTED,
        RECORDING_ENDED,

        // ---- Connection ----
        CONNECTION_REDIRECTED,
        CONNECTION_PASSTHROUGH,

        // ---- Rule lifecycle (Phase 3) ----
        RULES_RELOADED,
        RULE_CHANGED,

        // ---- Plugin lifecycle ----
        PLUGIN_LOADED,
        PLUGIN_UNLOADED,
        PLUGIN_ERROR,

        // ---- System ----
        AGENT_STARTED,
        AGENT_SHUTDOWN
    }

    private final Type type;
    private final long timestamp;
    private final String environmentId;
    private final Map<String, Object> attributes;

    public PluginEvent(Type type, String environmentId, Map<String, Object> attributes) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.environmentId = environmentId;
        this.attributes = attributes != null ? attributes : Collections.emptyMap();
    }

    // --- Factory methods (零领域模型依赖) ---

    public static PluginEvent requestReceived(String protocol, String method, String path) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("method", method);
        a.put("path", path);
        return new PluginEvent(Type.REQUEST_RECEIVED, null, a);
    }

    public static PluginEvent ruleMatched(String ruleId, String ruleName, String protocol) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("ruleId", ruleId);
        a.put("ruleName", ruleName);
        a.put("protocol", protocol);
        return new PluginEvent(Type.RULE_MATCHED, null, a);
    }

    public static PluginEvent ruleNotMatched(String protocol, String host, int port) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("host", host);
        a.put("port", port);
        return new PluginEvent(Type.RULE_NOT_MATCHED, null, a);
    }

    public static PluginEvent responseSent(String protocol, int statusCode, long durationMs) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("statusCode", statusCode);
        a.put("durationMs", durationMs);
        return new PluginEvent(Type.RESPONSE_SENT, null, a);
    }

    public static PluginEvent recordingSaved(String recordingId, String protocol, String environmentId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("recordingId", recordingId);
        a.put("protocol", protocol);
        return new PluginEvent(Type.RECORDING_SAVED, environmentId, a);
    }

    public static PluginEvent connectionRedirected(String protocol, String from, String to) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("from", from);
        a.put("to", to);
        return new PluginEvent(Type.CONNECTION_REDIRECTED, null, a);
    }

    public static PluginEvent connectionPassthrough(String protocol, String target) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("target", target);
        return new PluginEvent(Type.CONNECTION_PASSTHROUGH, null, a);
    }

    public static PluginEvent rulesReloaded(String environmentId, int ruleCount) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("ruleCount", ruleCount);
        return new PluginEvent(Type.RULES_RELOADED, environmentId, a);
    }

    public static PluginEvent ruleChanged(String ruleId, String action, String environmentId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("ruleId", ruleId);
        a.put("action", action);
        return new PluginEvent(Type.RULE_CHANGED, environmentId, a);
    }

    public static PluginEvent pluginLoaded(String pluginName, String target) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("pluginName", pluginName);
        a.put("target", target);
        return new PluginEvent(Type.PLUGIN_LOADED, null, a);
    }

    public static PluginEvent pluginError(String pluginName, String error) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("pluginName", pluginName);
        a.put("error", error);
        return new PluginEvent(Type.PLUGIN_ERROR, null, a);
    }

    // --- Getters ---

    public Type getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public String getEnvironmentId() { return environmentId; }
    public Map<String, Object> getAttributes() { return attributes; }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
```

### 3.4 Handler 埋点

#### 3.4.1 埋点清单

| # | 位置 | 事件 | 模块 |
|---|------|------|------|
| 1 | HttpStubHandler.channelRead0 入口 | REQUEST_RECEIVED | server |
| 2 | HttpStubHandler 规则匹配后 | RULE_MATCHED / RULE_NOT_MATCHED | server |
| 3 | HttpStubHandler 响应发送后 | RESPONSE_SENT | server |
| 4 | HttpStubHandler 录制保存后 | RECORDING_SAVED | server |
| 5 | GrpcUnifiedHandler 请求解析后 | REQUEST_RECEIVED | server |
| 6 | GrpcUnifiedHandler 规则匹配后 | RULE_MATCHED | server |
| 7 | GrpcUnifiedHandler 响应发送后 | RESPONSE_SENT | server |
| 8 | TcpStubHandler 接收数据后 | REQUEST_RECEIVED | server |
| 9 | TcpStubHandler 响应发送后 | RESPONSE_SENT | server |
| 10 | SocketConnectAdvice 重定向时 | CONNECTION_REDIRECTED | agent |
| 11 | SocketConnectAdvice 透传时 | CONNECTION_PASSTHROUGH | agent |
| 12 | NioSocketConnectAdvice 重定向时 | CONNECTION_REDIRECTED | agent |
| 13 | NioSocketConnectAdvice 透传时 | CONNECTION_PASSTHROUGH | agent |
| 14 | PluginManager.loadPlugin 后 | PLUGIN_LOADED | agent |
| 15 | PluginManager 异常时 | PLUGIN_ERROR | agent |

#### 3.4.2 Agent 侧事件桥接

Agent 侧 Advice（Bootstrap CL）无法直接访问 EventBus（baafoo-core / App CL）。通过 GlobalRouteState 中转：

```java
// GlobalRouteState 新增
public static volatile java.util.function.Consumer<PluginEvent> EVENT_FIRE_FN;

public static void firePluginEvent(PluginEvent event) {
    Consumer<PluginEvent> fn = EVENT_FIRE_FN;
    if (fn != null) {
        try { fn.accept(event); } catch (Throwable t) {
            logDebug("[Baafoo] Event fire skipped: " + t.getMessage());
        }
    }
}
```

```java
// BaafooAgent.setupEventBridge()
GlobalRouteState.EVENT_FIRE_FN = (event) -> {
    if (pluginManager != null) pluginManager.fireEvent(event);
};
```

```java
// SocketConnectAdvice 中
if (routeValue != null) {
    GlobalRouteState.firePluginEvent(PluginEvent.connectionRedirected(
        protocol, host + ":" + port, routeValue[0] + ":" + routeValue[1]));
} else {
    GlobalRouteState.firePluginEvent(PluginEvent.connectionPassthrough(
        protocol, host + ":" + port));
}
```

> **注：** `PluginEvent` 定义在 baafoo-plugin-api，Bootstrap CL 可访问（因为 plugin-api jar 被 bootstrap-jar 同步）。

### 3.5 文件变更清单（Phase 2）

| 操作 | 文件 | 模块 | 说明 |
|------|------|------|------|
| 新建 | `PluginEvent.java` | plugin-api | 事件定义（16 种类型，零领域模型） |
| 新建 | `EventBus.java` | core | 轻量事件总线 |
| 修改 | `PluginManager.java` | agent | 持有 EventBus，fireEvent，自动注册插件 onEvent |
| 修改 | `GlobalRouteState.java` | agent | 新增 `EVENT_FIRE_FN` + `firePluginEvent()` |
| 修改 | `BaafooAgent.java` | agent | setupEventBridge 设置 EVENT_FIRE_FN |
| 修改 | `BaafooServer.java` | server | 创建/注入 EventBus 到 Handler |
| 修改 | `HttpStubHandler.java` | server | 构造函数加 EventBus，4 个埋点 |
| 修改 | `GrpcUnifiedHandler.java` | server | 构造函数加 EventBus，3 个埋点 |
| 修改 | `TcpStubHandler.java` | server | 构造函数加 EventBus，2 个埋点 |
| 修改 | `SocketConnectAdvice.java` | agent | 2 个埋点（通过 firePluginEvent） |
| 修改 | `NioSocketConnectAdvice.java` | agent | 2 个埋点（通过 firePluginEvent） |

---

## 4. Phase 3：RuleChangeListener

### 4.1 设计

复用 Phase 2 的事件总线。Rule CRUD 操作后触发 `RULE_CHANGED` / `RULES_RELOADED` 事件。

### 4.2 触发点

```
Admin API (POST /api/rules)  ──→  StorageService.save()  ──→  serverBus.fire(RULE_CHANGED)
Admin API (POST /api/rules/reload)  ──→  StorageService.reload()  ──→  serverBus.fire(RULES_RELOADED)
```

### 4.3 实现

```java
public class RuleApiHandler {

    private final EventBus eventBus; // may be null

    // 创建规则后
    public void afterRuleCreated(String ruleId, String environmentId) {
        if (eventBus != null) {
            eventBus.fire(PluginEvent.ruleChanged(ruleId, "created", environmentId));
        }
    }

    // 更新规则后
    public void afterRuleUpdated(String ruleId, String environmentId) {
        if (eventBus != null) {
            eventBus.fire(PluginEvent.ruleChanged(ruleId, "updated", environmentId));
        }
    }

    // 删除规则后
    public void afterRuleDeleted(String ruleId, String environmentId) {
        if (eventBus != null) {
            eventBus.fire(PluginEvent.ruleChanged(ruleId, "deleted", environmentId));
        }
    }

    // 规则重载后
    public void afterRulesReloaded(String environmentId, int count) {
        if (eventBus != null) {
            eventBus.fire(PluginEvent.rulesReloaded(environmentId, count));
        }
    }
}
```

### 4.4 插件使用示例

```java
public class MetricsPlugin implements AgentPlugin {

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong matchedRequests = new AtomicLong();
    private final Map<String, AtomicLong> ruleHitCounts = new ConcurrentHashMap<>();

    @Override
    public void onEvent(PluginEvent event) {
        switch (event.getType()) {
            case REQUEST_RECEIVED:
                totalRequests.incrementAndGet();
                break;
            case RULE_MATCHED:
                matchedRequests.incrementAndGet();
                String ruleId = event.getAttribute("ruleId");
                ruleHitCounts.computeIfAbsent(ruleId, k -> new AtomicLong())
                             .incrementAndGet();
                break;
            case RULES_RELOADED:
                ruleHitCounts.clear();
                break;
            default:
                break;
        }
    }

    // ... other methods ...
}
```

### 4.5 文件变更清单（Phase 3）

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `RuleApiHandler.java` | 规则 CRUD 后触发事件（4 个方法） |
| 无新文件 | — | 复用 Phase 2 的事件总线 |

---

## 5. 实施计划

### 5.1 时间线

| 阶段 | 工作量 | 产出 |
|------|--------|------|
| Phase 1 | 2-3 天 | 接口拆分 + 服务注入 + PluginManager 适配 + 桥接扩展 |
| Phase 2 | 1.5 天 | EventBus + PluginEvent + 15 个埋点 + Agent/Server 桥接 |
| Phase 3 | 0.5 天 | Rule API 事件触发 |
| **合计** | **4-5 天** | |

### 5.2 依赖关系

```
Phase 1 ──→ Phase 2 ──→ Phase 3
```

### 5.3 风险

| # | 风险 | 缓解 |
|---|------|------|
| R1 | Bootstrap CL ↔ App CL 事件桥接延迟 | 事件队列异步处理，不阻塞 Advice 主流程 |
| R2 | 事件风暴（高频请求场景） | EventBus 支持采样模式（可配置每 N 次请求发一次） |
| R3 | 旧插件未实现 onEvent | PluginManager 检测是否覆写，未覆写则跳过（CopyOnWriteArrayList 仍会调用，但 default 方法是空实现，开销极小） |
| R4 | PluginServices 在纯 Agent 模式下为 null | 接口文档标注 nullable，插件需 null-check |
| R5 | PLUGIN_CONSULT_FN_EXT 旧插件不兼容 | 旧桥接作为 fallback，EXT 返回 null 时自动回退到旧桥接 |
| R6 | 分离部署时 Agent 事件不传到 Server | 这是预期行为，文档明确说明。如需跨进程事件，未来可引入 gRPC event streaming |

### 5.4 测试策略

| 层级 | 测试内容 |
|------|----------|
| 单元测试 | ConnectAdvice/RequestAdvice/ResponseAdvice 工厂方法 |
| 单元测试 | PluginEvent 工厂方法 + 属性 |
| 单元测试 | EventBus（add/remove/fire/exception isolation） |
| 单元测试 | PluginManager 向后兼容（旧插件只实现 intercept） |
| 单元测试 | PLUGIN_CONSULT_FN_EXT 返回值解析 + fallback 到旧桥接 |
| 集成测试 | HttpStubHandler 埋点 → 事件被正确触发 |
| 集成测试 | Rule CRUD → RULE_CHANGED 事件 |
| 集成测试 | PluginServices 注入 → 插件可调用 RuleStore（返回 Map） |
| 集成测试 | Agent/Server 分离部署 → 事件各自独立 |

---

## 6. 与 WireMock 对比

| 维度 | WireMock | Baafoo (v2) |
|------|----------|-------------|
| 扩展接口数 | 10+ | 4 个钩子 + 1 个事件方法 |
| 服务注入 | ExtensionFactory（强类型领域模型） | PluginServices（Map 基础类型，零依赖约束） |
| 事件机制 | ServeEventListener（请求生命周期） | EventBus + PluginEvent（全生命周期，含连接/规则/插件） |
| 向后兼容 | 3.x → 4.x 有 breaking changes | 完全兼容，旧插件不改代码 |
| 连接阶段钩子 | 无 | onConnect（Agent 独有） |
| 事件总位置 | ExtensionFactory 内部 | baafoo-core（Agent/Server 各持独立实例） |
| 协议支持 | HTTP 核心 + Extension 扩展 | 6 协议原生内置 |
| 插件间通信 | services.getExtension() | 事件总线（解耦） |

---

## 7. 目录结构（实施后）

```
baafoo-plugin-api/src/main/java/com/baafoo/plugin/
├── AgentPlugin.java              (修改)
├── ConnectAdvice.java            (新建)
├── ConnectContext.java           (新建)
├── InterceptResult.java          (不变)
├── InterceptTarget.java          (不变)
├── PluginContext.java            (修改：新增 services)
├── PluginEvent.java              (新建)
├── PluginHealth.java             (不变)
├── RequestAdvice.java            (新建)
├── RequestContext.java           (新建)
├── ResponseAdvice.java           (新建)
├── ResponseContext.java          (新建)
└── service/
    ├── AdminHandler.java         (新建)
    ├── PluginServices.java       (新建)
    ├── RecordingStore.java       (新建)
    ├── RuleStore.java            (新建)
    └── ServerAdmin.java          (新建)

baafoo-core/src/main/java/com/baafoo/core/
├── event/
│   └── EventBus.java             (新建)
└── (existing packages unchanged)

baafoo-agent/src/main/java/com/baafoo/agent/
├── BaafooAgent.java              (修改)
├── GlobalRouteState.java         (修改)
├── plugin/
│   └── PluginManager.java        (修改)
└── advice/
    ├── SocketConnectAdvice.java  (修改)
    └── NioSocketConnectAdvice.java (修改)
    (GrpcChannelAdvice.java — 不修改)

baafoo-server/src/main/java/com/baafoo/server/
├── bootstrap/
│   └── BaafooServer.java         (修改)
├── handler/
│   ├── HttpStubHandler.java      (修改)
│   ├── GrpcUnifiedHandler.java   (修改)
│   ├── TcpStubHandler.java       (修改)
│   └── PluginServicesImpl.java   (新建)
└── api/
    └── RuleApiHandler.java       (修改)
```

---

## 附录 A：现有插件迁移指南

### 迁移前（当前，不改也行）

```java
public class KafkaRedirectPlugin implements AgentPlugin {
    @Override
    public InterceptResult intercept(PluginContext ctx) {
        if (excludeTopics.contains(ctx.getTopic())) {
            return InterceptResult.passthrough();
        }
        return InterceptResult.redirect(redirectHost, redirectPort);
    }
}
```

### 迁移后（推荐，可选）

```java
public class KafkaRedirectPlugin implements AgentPlugin {

    @Override
    public ConnectAdvice onConnect(ConnectContext ctx) {
        return ConnectAdvice.redirect(redirectHost, redirectPort);
    }

    @Override
    public void onEvent(PluginEvent event) {
        if (event.getType() == PluginEvent.Type.RULES_RELOADED) {
            // Clear caches if any
        }
    }

    @Override
    @Deprecated
    public InterceptResult intercept(PluginContext ctx) {
        return InterceptResult.redirect(redirectHost, redirectPort);
    }
}
```

> **迁移是可选的。** 旧插件不改代码仍能运行。

---

## 附录 B：RequestContext / RequestAdvice / ResponseContext / ResponseAdvice

### B.1 RequestContext

```java
package com.baafoo.plugin;

import java.util.Collections;
import java.util.Map;

/**
 * Context for the request-phase hook.
 * Only uses JDK types — no domain model dependency.
 */
public class RequestContext {

    private final String protocol;
    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final Map<String, String> queryParams;
    private final byte[] body;
    private final String host;
    private final int port;
    private final String environmentId;
    private final boolean recording;

    // Protocol-specific
    private final String grpcService;
    private final String grpcMethod;
    private final String topic;
    private final Integer partition;
    private final String messageKey;

    // Modifiable fields (for MODIFY advice)
    private Map<String, String> modifiedHeaders;
    private byte[] modifiedBody;

    public RequestContext(String protocol, String method, String path,
                          Map<String, String> headers, Map<String, String> queryParams,
                          byte[] body, String host, int port,
                          String environmentId, boolean recording) {
        this.protocol = protocol;
        this.method = method;
        this.path = path;
        this.headers = headers != null ? headers : Collections.emptyMap();
        this.queryParams = queryParams != null ? queryParams : Collections.emptyMap();
        this.body = body != null ? body : new byte[0];
        this.host = host;
        this.port = port;
        this.environmentId = environmentId;
        this.recording = recording;
        this.grpcService = null;
        this.grpcMethod = null;
        this.topic = null;
        this.partition = null;
        this.messageKey = null;
    }

    // --- Getters ---

    public String getProtocol() { return protocol; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public Map<String, String> getHeaders() { return headers; }
    public Map<String, String> getQueryParams() { return queryParams; }
    public byte[] getBody() { return body; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getEnvironmentId() { return environmentId; }
    public boolean isRecording() { return recording; }
    public String getGrpcService() { return grpcService; }
    public String getGrpcMethod() { return grpcMethod; }
    public String getTopic() { return topic; }
    public Integer getPartition() { return partition; }
    public String getMessageKey() { return messageKey; }
    public Map<String, String> getModifiedHeaders() { return modifiedHeaders; }
    public byte[] getModifiedBody() { return modifiedBody; }

    public void setModifiedHeaders(Map<String, String> h) { this.modifiedHeaders = h; }
    public void setModifiedBody(byte[] b) { this.modifiedBody = b; }
}
```

### B.2 RequestAdvice

```java
package com.baafoo.plugin;

import java.util.Map;

public class RequestAdvice {

    public enum Action {
        CONTINUE,
        SHORTCIRCUIT,
        MODIFY
    }

    private final Action action;
    private final byte[] shortcutBody;
    private final int shortcutStatusCode;
    private final Map<String, String> shortcutHeaders;
    private final Map<String, String> modifiedHeaders;
    private final byte[] modifiedBody;

    private RequestAdvice(Action action, byte[] shortcutBody, int shortcutStatusCode,
                          Map<String, String> shortcutHeaders,
                          Map<String, String> modifiedHeaders, byte[] modifiedBody) {
        this.action = action;
        this.shortcutBody = shortcutBody;
        this.shortcutStatusCode = shortcutStatusCode;
        this.shortcutHeaders = shortcutHeaders;
        this.modifiedHeaders = modifiedHeaders;
        this.modifiedBody = modifiedBody;
    }

    public static RequestAdvice continue() {
        return new RequestAdvice(Action.CONTINUE, null, 0, null, null, null);
    }

    public static RequestAdvice shortCircuit(byte[] body, int statusCode, Map<String, String> headers) {
        return new RequestAdvice(Action.SHORTCIRCUIT, body, statusCode, headers, null, null);
    }

    public static RequestAdvice modify(Map<String, String> newHeaders, byte[] newBody) {
        return new RequestAdvice(Action.MODIFY, null, 0, null, newHeaders, newBody);
    }

    // --- Getters ---

    public Action getAction() { return action; }
    public byte[] getShortcutBody() { return shortcutBody; }
    public int getShortcutStatusCode() { return shortcutStatusCode; }
    public Map<String, String> getShortcutHeaders() { return shortcutHeaders; }
    public Map<String, String> getModifiedHeaders() { return modifiedHeaders; }
    public byte[] getModifiedBody() { return modifiedBody; }
}
```

### B.3 ResponseContext

```java
package com.baafoo.plugin;

import java.util.Map;

public class ResponseContext {

    private final String protocol;
    private final String ruleId;
    private final String ruleName;
    private final int statusCode;
    private final Map<String, String> headers;
    private byte[] body;
    private final RequestContext request;
    private final boolean stubbed;

    public ResponseContext(String protocol, String ruleId, String ruleName,
                           int statusCode, Map<String, String> headers, byte[] body,
                           RequestContext request, boolean stubbed) {
        this.protocol = protocol;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
        this.request = request;
        this.stubbed = stubbed;
    }

    // --- Getters / Setters ---

    public String getProtocol() { return protocol; }
    public String getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public int getStatusCode() { return statusCode; }
    public Map<String, String> getHeaders() { return headers; }
    public byte[] getBody() { return body; }
    public void setBody(byte[] body) { this.body = body; }
    public RequestContext getRequest() { return request; }
    public boolean isStubbed() { return stubbed; }
}
```

### B.4 ResponseAdvice

```java
package com.baafoo.plugin;

import java.util.Map;

public class ResponseAdvice {

    public enum Action {
        CONTINUE,
        REPLACE,
        AUGMENT
    }

    private final Action action;
    private final byte[] replaceBody;
    private final int replaceStatusCode;
    private final Map<String, String> replaceHeaders;
    private final Map<String, String> additionalHeaders;

    private ResponseAdvice(Action action, byte[] replaceBody, int replaceStatusCode,
                           Map<String, String> replaceHeaders,
                           Map<String, String> additionalHeaders) {
        this.action = action;
        this.replaceBody = replaceBody;
        this.replaceStatusCode = replaceStatusCode;
        this.replaceHeaders = replaceHeaders;
        this.additionalHeaders = additionalHeaders;
    }

    public static ResponseAdvice continue() {
        return new ResponseAdvice(Action.CONTINUE, null, 0, null, null);
    }

    public static ResponseAdvice replace(byte[] body, int statusCode, Map<String, String> headers) {
        return new ResponseAdvice(Action.REPLACE, body, statusCode, headers, null);
    }

    public static ResponseAdvice augment(Map<String, String> additionalHeaders) {
        return new ResponseAdvice(Action.AUGMENT, null, 0, null, additionalHeaders);
    }

    // --- Getters ---

    public Action getAction() { return action; }
    public byte[] getReplaceBody() { return replaceBody; }
    public int getReplaceStatusCode() { return replaceStatusCode; }
    public Map<String, String> getReplaceHeaders() { return replaceHeaders; }
    public Map<String, String> getAdditionalHeaders() { return additionalHeaders; }
}
```
