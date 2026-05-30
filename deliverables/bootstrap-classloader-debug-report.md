# Baafoo Agent Bootstrap ClassLoader 调试记录

## 概述

本文档记录了 Baafoo Agent 在实现 ByteBuddy 字节码增强拦截过程中，解决 **Bootstrap ClassLoader 双加载问题** 的完整调试过程。

**日期**: 2026-05-30
**状态**: 已解决
**最终方案**: GlobalRouteState — 使用纯 JDK 类型的全局状态类

---

## 1. 背景与架构

### 1.1 系统架构

Baafoo 是一个 Java Agent + Server 架构的流量挡板/模拟系统：

- **Agent**: 通过 ByteBuddy 字节码增强拦截应用的外调连接，重定向到 Server 的 stub 端口
- **Server**: 提供管理 API 和多个协议的 stub 服务（HTTP:9000, TCP:9001, Kafka:9002, Pulsar:9003, JMS:9004）

### 1.2 Agent 工作原理

```
应用代码 → Socket.connect(httpbin.org:80)
         → ByteBuddy Advice 拦截
         → 查找 RouteTable: httpbin.org:80 → 127.0.0.1:9000
         → 改写目标地址为 127.0.0.1:9000
         → Server HttpStubHandler 返回 mock 响应
```

### 1.3 Bootstrap ClassLoader 约束

ByteBuddy Advice 代码被**内联**到目标类中。当目标类是 JDK 类（如 `java.net.Socket`），Advice 代码运行在 **Bootstrap ClassLoader** 上下文中。这意味着：

- Advice 引用的所有类必须对 Bootstrap CL 可见
- Bootstrap CL 是 JVM 最顶层的 ClassLoader，无法访问 AppClassLoader 加载的类
- 必须通过 `Instrumentation.appendToBootstrapClassLoaderSearch()` 将 agent jar 添加到 Bootstrap CL 搜索路径

---

## 2. 问题演进与解决

### 2.1 Error 1: `appendToBootstrapClassLoaderSearch` 时序问题

**现象**: Advice 代码未注入到 `java.net.Socket`

**原因**: `appendToBootstrapClassLoaderSearch` 在 `installOn` 之前执行，导致 ByteBuddy 类被 Bootstrap CL 和 AppClassLoader 同时加载，触发 **loader constraint violation**

**修复**: 调整执行顺序为 `installOn` → `appendToBootstrapClassLoaderSearch`

**原理**: `installOn()` 只注册 retransform，不执行 Advice。Advice 在应用代码调用被拦截方法时才执行，此时 Bootstrap CL 已能找到所需类。

---

### 2.2 Error 2: `IllegalAccessError: ControlChannel$1` — 匿名内部类跨 CL 访问

**现象**: `IllegalAccessError` 访问 `ControlChannel$1`（ThreadFactory 匿名内部类）

**原因**: `ControlChannel` 在 `appendToBootstrapClassLoaderSearch` 之后初始化，匿名内部类被 Bootstrap CL 加载，但引用了 AppClassLoader 的类

**修复**: 将 `ControlChannel` 和 `PluginManager` 初始化移到 `appendToBootstrapClassLoaderSearch` 之前

---

### 2.3 Error 3: `java.net.Socket` 被 ByteBuddy IGNORE

**现象**: `java.net.Socket` 的 DISCOVERY/IGNORE/COMPLETE 日志出现，但没有 TRANSFORM

**原因**: `AgentBuilder.Default()` 的默认 ignore 策略忽略了某些类

**修复**: 自定义 ignore 策略：
```java
.ignore(nameStartsWith("net.bytebuddy.")
        .or(nameStartsWith("com.baafoo.agent.shaded."))
        .or(isSynthetic()))
```

---

### 2.4 Error 4: `IllegalAccessError: isBaafooInternal` — private 方法跨 CL 访问

**现象**: Advice 内联到 `java.net.Socket` 后访问 `isBaafooInternal` 方法报 IllegalAccessError

**原因**: `isBaafooInternal` 是 `private static`，Advice 代码内联到 Bootstrap CL 加载的 JDK 类后无法访问 private 成员

**修复**: 将 `isBaafooInternal` 改为 `public static`

---

### 2.5 Error 5: ByteBuddy `TypePool$LazyFacade$LazyResolution` IllegalAccessError

**现象**: ByteBuddy 内部包私有访问被阻止

**原因**: `appendToBootstrapClassLoaderSearch` 将整个 agent jar（含 ByteBuddy 类）添加到 Bootstrap CL，导致 ByteBuddy 内部包私有访问被阻止

**修复**: 创建只含 `AgentManifest.class` 和 `RouteTable.class` 的 **bootstrap helper jar**，避免将 ByteBuddy 类暴露给 Bootstrap CL

---

### 2.6 Error 6（核心问题）: RouteTable 为空 — Bootstrap CL 双加载

**现象**: Advice 代码成功注入并执行，但 `AgentManifest.ROUTE_TABLE` 始终为空

**原因**: 这是整个调试过程中最核心的问题。`AgentManifest` 被 **两个 ClassLoader 分别加载**：

```
AppClassLoader 加载的 AgentManifest:
  - ROUTE_TABLE = {httpbin.org:80 → 127.0.0.1:9000:http}  ← RouteManager 更新的是这个
  - currentMode = 0 (STUB)

Bootstrap CL 加载的 AgentManifest:
  - ROUTE_TABLE = {}  ← Advice 代码读取的是这个（空的！）
  - currentMode = 0 (STUB)
```

两个 ClassLoader 加载的 `AgentManifest` 是**不同的类对象**，静态字段**不共享**。

**尝试修复 1**: `syncAgentManifestToBootstrapCL` — 直接赋值 RouteTable 对象
- **失败**: 类型不兼容，不同 CL 加载的类不能互相赋值

**尝试修复 2**: `rebuildBootstrapRouteTable` — 通过反射重建 Bootstrap CL 版本的 RouteTable
- **失败**: 报 "Unresolved compilation problem" 错误，可能是 shade relocation 导致的类型引用问题

---

### 2.7 最终方案: GlobalRouteState

**核心思路**: 既然自定义类（`AgentManifest`, `RouteTable`）会被双加载导致类型不兼容，那就**只用 JDK 类型**。

**设计**:

```java
public final class GlobalRouteState {
    // 所有字段都是 JDK 类型
    public static final ConcurrentHashMap<String, String> ROUTES = new ConcurrentHashMap<>();
    public static volatile int CURRENT_MODE = 0;
    public static volatile String SERVER_HOST = "127.0.0.1";
    public static volatile int SERVER_PORT = 8080;
    // ...
}
```

**为什么有效**:

1. `ConcurrentHashMap` 是 `java.util.concurrent` 包的 JDK 类，**Bootstrap CL 和 AppClassLoader 引用的是同一个类对象**
2. 通过反射获取 Bootstrap CL 版本的 `GlobalRouteState.ROUTES` 字段，得到的是 `ConcurrentHashMap` 引用
3. 对这个引用调用 `putAll()`，写入的数据**直接存在于 Bootstrap CL 版本的 Map 中**
4. Advice 代码（Bootstrap CL 上下文）读取 `GlobalRouteState.ROUTES.get(key)` 时，能读到正确的数据

**同步机制**:

```
premain() 执行流程:
1. initAgentManifest(config)         → 设置 AppClassLoader 版本
2. GlobalRouteState.SERVER_HOST = ...  → 同步到 AppClassLoader 版本
3. controlChannel.start()            → 注册到 Server，拉取规则
4. installOn(inst)                   → ByteBuddy retransform
5. appendToBootstrapClassLoaderSearch → Bootstrap CL 加载 GlobalRouteState
6. syncGlobalRouteStateToBootstrapCL():
   - 反射获取 Bootstrap CL 版本的 ROUTES 字段
   - bootRoutes.putAll(GlobalRouteState.ROUTES)  ← 关键：操作同一个 ConcurrentHashMap
   - 保存 bootRoutes 引用到 BaafooAgent.bootstrapRoutes
7. 后续 RouteManager.rebuildRouteTable():
   - GlobalRouteState.ROUTES.putAll(newRoutes)   ← 更新 AppClassLoader 版本
   - BaafooAgent.getBootstrapRoutes().putAll()   ← 更新 Bootstrap CL 版本
```

---

## 3. 修改的文件清单

| 文件 | 修改内容 |
|------|----------|
| `GlobalRouteState.java` | 新增 `lookupService()`, `putRoute()`, `putService()`, `clearRoutes()` 方法 |
| `SocketConnectAdvice.java` | 引用 `GlobalRouteState` 替代 `AgentManifest`/`RouteTable`，移除调试输出 |
| `NioSocketConnectAdvice.java` | 引用 `GlobalRouteState` 替代 `AgentManifest`/`RouteTable` |
| `ConsulDnsAdvice.java` | 引用 `GlobalRouteState` 替代 `AgentManifest`/`RouteTable` |
| `ConsulHttpAdvice.java` | 引用 `GlobalRouteState` 替代 `AgentManifest`/`RouteTable` |
| `BaafooAgent.java` | bootstrap helper jar 只含 `GlobalRouteState.class`；新增 `syncGlobalRouteStateToBootstrapCL()`；移除旧的 `syncAgentManifestToBootstrapCL()`, `rebuildBootstrapRouteTable()`, `findBootstrapClass()` 保留 |
| `RouteManager.java` | `rebuildRouteTable()` 同时写入 `GlobalRouteState.ROUTES`；`syncRoutesToBootstrapCL()` 通过 `BaafooAgent.getBootstrapRoutes()` 操作；移除旧的反射同步代码 |

---

## 4. 端到端测试结果

```
=== Baafoo Quick Connectivity Test ===

[1] HTTP call to httpbin.org/get...
    Status: 200
    X-Baafoo-Stub: true
    X-Baafoo-Rule-Id: f01af386ffd64344
    Body: {"url":"http://httpbin.org/get","args":{},"headers":{"Host":"httpbin.org","X-Baafoo-Stub":"true"}}
    RESULT: INTERCEPTED by Baafoo ✓

[2] Baafoo Server management API...
    Status: 200
    RESULT: Server is UP ✓

[3] TCP Socket to 127.0.0.1:9999...
    Exception: RuntimeException: Baafoo: No rule matched for 127.0.0.1:9999 (stub mode — connection blocked)
    → 符合预期：无匹配规则时 stub 模式阻断连接 ✓

[4] Direct HTTP to Baafoo stub port 9000 with Host: httpbin.org...
    Status: 404
    X-Baafoo-Stub: unmatched
    → 符合预期：直接访问 stub 端口但无匹配规则 ✓
```

**关键日志**:
```
Synced 1 routes to Bootstrap CL GlobalRouteState.ROUTES
Synced GlobalRouteState fields to Bootstrap CL: CURRENT_MODE=0, SERVER_HOST=127.0.0.1, SERVER_PORT=8080
=== Baafoo Agent started successfully ===
```

---

## 5. 经验总结

### 5.1 Java Agent Bootstrap ClassLoader 的核心规则

1. **Advice 内联到 Bootstrap CL 加载的类时，Advice 引用的所有类必须对 Bootstrap CL 可见**
2. **`appendToBootstrapClassLoaderSearch` 必须在 `installOn` 之后执行**，否则 ByteBuddy 会被双加载
3. **双加载的类是不同的类型**，不能互相赋值，静态字段不共享
4. **JDK 类（如 `ConcurrentHashMap`）不受双加载影响**，因为 Bootstrap CL 和 AppClassLoader 对 JDK 类使用同一个类对象

### 5.2 Bootstrap Helper Jar 模式

将 agent jar 中只有 Advice 需要引用的类提取到单独的 helper jar，避免将 ByteBuddy 等第三方库暴露给 Bootstrap CL。这是 Java Agent 开发中的常见模式。

### 5.3 ConcurrentHashMap 引用共享

通过反射获取 Bootstrap CL 版本的 `ConcurrentHashMap` 引用后，可以直接操作该 Map。因为 `ConcurrentHashMap` 是 JDK 类，反射返回的对象就是 Bootstrap CL 版本 Map 的实际引用，`putAll()` 操作直接生效。

---

## 6. 临时文件索引

调试过程中生成的临时文件已移动到 `test/` 目录：

| 文件 | 说明 |
|------|------|
| `test/RawSocketTest.java` | 原始 Socket 连接测试类 |
| `test/SocketMethodCheck.java` | Socket 方法签名检查工具 |
| `test/baafoo-agent-test.yml` | 测试用 agent 配置（environment=dev） |
| `test/test-input.txt` | 交互式测试的输入数据 |
| `test/test-output.txt` | 交互式测试的输出记录 |
| `test/worktemp.txt` | Agent 调试过程的工作笔记 |
