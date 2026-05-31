# Baafoo 项目代码审查报告

> 审查日期：2026-05-31
> 审查范围：全部 6 个 Java 模块 + Vue 3 前端

---

## 一、项目概览

**Baafoo** 是一个基于 JavaAgent 的 API Mock 平台，通过 Byte Buddy 字节码增强拦截应用的网络调用（HTTP/TCP/Kafka/Pulsar/JMS/Consul），将流量路由到 Baafoo Server 的 Stub 服务，实现服务虚拟化。

**技术栈**：Java 8 + Byte Buddy + Netty + H2 + MyBatis + Jackson + Vue 3

**模块结构**：

| 模块 | 职责 |
|------|------|
| `baafoo-core` | 公共模型、配置、匹配引擎 |
| `baafoo-plugin-api` | 插件 SPI 接口 |
| `baafoo-agent` | Java Agent（字节码增强 + 控制通道） |
| `baafoo-server` | Stub 服务器 + 管理 API |
| `baafoo-cli` | 命令行工具 |
| `baafoo-test-app` | 测试应用 |
| `web` | Vue 3 前端控制台 |

---

## 二、架构亮点

1. **Bootstrap ClassLoader 隔离设计**：`GlobalRouteState` 和 `RouteTable` 仅使用 Bootstrap CL 可加载的类型（String、ConcurrentHashMap、int），避免了 Advice 内联代码的 ClassNotFoundException。
2. **插件隔离 ClassLoader**：`PluginClassLoader` 使用 `parent=null` 隔离 SDK 依赖冲突，同时通过自定义 `loadClass` 保证 SPI API 和 JDK 类从系统 CL 加载。
3. **Fail-closed 设计**：Agent 启动失败时所有请求 passthrough 到真实下游，不会阻断业务。
4. **Agent 无 Netty 依赖**：`ControlChannel` 使用 JDK HttpURLConnection，避免与应用的 Netty 版本冲突。
5. **规则版本历史 + Undo**：`H2StorageService` 实现了 rule_history 表，支持最多 10 个版本的回滚。

---

## 三、问题清单

### 🔴 严重问题

#### #1 H2StorageService 线程安全 — 单 Connection 共享

**文件**：`baafoo-server/.../storage/H2StorageService.java`

全类使用单个 `Connection conn` 字段，Netty 的多线程 worker 同时调用 `listRules()`、`createRule()` 等方法时，JDBC Connection 不是线程安全的。

**修复方案**：为所有公开方法添加 `synchronized` 关键字，确保同一时刻只有一个线程操作 Connection。

---

#### #2 HttpStubHandler Passthrough 查询参数拼接 Bug

**文件**：`baafoo-server/.../handler/HttpStubHandler.java` 第 210 行

`sendPassthroughAndRecord` 方法中 `first` 标志在循环内被错误重置为 `true`，导致每个参数之间缺少 `&` 分隔符：

```java
first = true;  // BUG: 应为 first = false
```

同文件第 398 行的 `sendPassthroughResponse` 方法中是正确的 `first = false`。

**修复方案**：将 `first = true` 改为 `first = false`。

---

#### #3 Passthrough 仅支持 HTTP，不支持 HTTPS

**文件**：`baafoo-server/.../handler/HttpStubHandler.java` 第 203 行

passthrough 硬编码 `http://` 协议，如果原始请求目标是 HTTPS 服务，passthrough 会失败。

**修复方案**：根据端口号（443）和 `X-Forwarded-Proto` 头动态选择协议。

---

#### #4 undoRule 中 SQL 未参数化

**文件**：`baafoo-server/.../storage/H2StorageService.java` 第 350-353 行

删除 history 记录时拼接了 SQL 字符串而非使用参数化查询。

**修复方案**：改用两步参数化查询（先查 id，再删记录）。

---

### 🟡 重要问题

#### #5 ~~GlobalRouteState 与 RouteTable 功能重复~~ → 设计方案（非 Bug）

**文件**：`baafoo-agent/.../GlobalRouteState.java` + `baafoo-agent/.../RouteTable.java`

**结论：这是解决 Bootstrap ClassLoader 双加载问题的有意设计，不是 Bug。**

详见 `deliverables/bootstrap-classloader-debug-report.md` 第 2.6-2.7 节：

- `GlobalRouteState`（纯 JDK 类型）供 Advice 内联代码在 Bootstrap CL 上下文中使用
- `RouteTable`/`AgentManifest`（丰富类型）供 AppClassLoader 侧的 RouteManager 等使用
- `RouteManager.rebuildRouteTable()` 同时写入两者以保持同步
- `ConcurrentHashMap` 是 JDK 类，Bootstrap CL 和 AppClassLoader 引用同一个类对象，因此 `putAll()` 操作直接生效

这是 Java Agent 开发中处理 Bootstrap CL 约束的标准模式。

---

#### #6 MatchEngine.patternCache 无界缓存

**文件**：`baafoo-core/.../util/MatchEngine.java` 第 27 行

`patternCache` 是普通 `HashMap`，没有淘汰策略，频繁创建 regex 规则会导致内存泄漏。

**修复方案**：改为 `ConcurrentHashMap` 并添加大小限制（最大 256 个条目）。

---

#### #7 MatchEngine 不是线程安全的

**文件**：`baafoo-core/.../util/MatchEngine.java`

`patternCache`（HashMap）在多线程环境下并发读写可能导致死循环或数据丢失。

**修复方案**：与 #6 合并修复，改用 `ConcurrentHashMap`。

---

#### #8 KafkaProducerAdvice / PulsarClientAdvice 引用非 Bootstrap CL 类

**文件**：`baafoo-agent/.../advice/KafkaProducerAdvice.java` + `PulsarClientAdvice.java`

这两个 Advice 引用了 `RouteManager`、`Logger` 等非 Bootstrap CL 类。

**风险评估**：由于目标类（`KafkaProducer`、`ClientBuilder`）由 AppClassLoader 加载（非 JDK 类），Advice 内联代码在 AppClassLoader 上下文执行，因此引用 AppClassLoader 的类是安全的。这与拦截 JDK 类（Socket、InetAddress）的 Advice 不同——后者必须仅引用 Bootstrap CL 可加载的类。

**结论**：当前实现可正常工作，但存在潜在风险——如果未来 `appendToBootstrapClassLoaderSearch` 导致类解析顺序变化，可能引发问题。建议在代码中添加架构说明注释。

---

#### #9 Passthrough 未处理无 body 响应

**文件**：`baafoo-server/.../handler/HttpStubHandler.java` 第 249-273 行

当 `Content-Length` 为 -1 且 `inputStream` 也为 null（如 204 No Content）时，代码逻辑正确（返回空字节数组），但当 `Content-Length > 0` 但实际 body 为空时，可能读取阻塞。

**修复方案**：添加 inputStream null 检查和更健壮的 body 读取逻辑。

---

#### #10 ManagementApiHandler 缺少 CORS OPTIONS 预检处理

**文件**：`baafoo-server/.../api/ManagementApiHandler.java`

浏览器跨域请求会先发 OPTIONS 预检，当前返回 404。

**修复方案**：在 `handleApiRequest` 开头添加 OPTIONS 方法处理。

---

#### #11 H2StorageService.addRecordings 批量插入效率低

**文件**：`baafoo-server/.../storage/H2StorageService.java` 第 964-968 行

批量上传录制数据时逐条插入，每条还触发 `trimRecordings()`。

**修复方案**：使用 JDBC batch insert，批量后再执行一次 trim。

---

### 🟢 一般问题

#### #12 BaafooAgent URL 解析不够健壮

**文件**：`baafoo-agent/.../BaafooAgent.java` 第 164-183 行

手动解析 URL，不支持带路径的 URL 和 IPv6 地址。

**修复方案**：使用 `java.net.URI` 类解析。

---

#### #13 RoutingContext 的 ThreadLocal 可能泄漏

**文件**：`baafoo-agent/.../advice/RoutingContext.java`

`set()` 后没有对应的 `clear()` 调用点。在线程池场景下，ThreadLocal 值会残留。

**修复方案**：在 Advice 的 `@Advice.OnMethodExit` 中调用 `clear()`。

---

#### #14 GlobalRouteState.isInternal 魔法端口号

**文件**：`baafoo-agent/.../GlobalRouteState.java` 第 65 行

硬编码了 `8080, 9000, 9001, 9002, 9003, 9004` 端口号。

**修复方案**：添加 `INTERNAL_PORTS` 常量集合，并支持通过配置扩展。

---

#### #15 缺少单元测试

项目根 pom.xml 定义了 JUnit 和 Mockito 依赖，但各模块 `src/test` 目录下没有测试类。

**修复方案**：后续迭代补充核心逻辑的单元测试。

---

#### #16 SnakeYAML 版本安全漏洞

**文件**：`pom.xml` 第 33 行

SnakeYAML 1.33 存在 CVE-2022-1471 反序列化漏洞。

**修复方案**：升级到 2.2。

---

#### #17 sendError 中 JSON 拼接不安全

**文件**：`baafoo-server/.../handler/HttpStubHandler.java` 第 509 行

错误消息直接拼入 JSON 字符串，特殊字符会破坏 JSON 格式。

**修复方案**：使用 ObjectMapper 序列化。

---

#### #18 BaafooServer.startProtocolServers 代码重复

**文件**：`baafoo-server/.../bootstrap/BaafooServer.java` 第 147-198 行

Kafka/Pulsar/JMS 的 stub server 启动代码几乎完全相同。

**修复方案**：提取为通用方法 `startTcpStubServer(String protocol, int port)`。

---

## 四、问题汇总

| # | 严重度 | 问题 | 状态 |
|---|--------|------|------|
| 1 | 🔴 严重 | H2StorageService 线程安全 | ✅ 已修复 |
| 2 | 🔴 严重 | Passthrough 查询参数 Bug | ✅ 已修复 |
| 3 | 🔴 严重 | Passthrough 不支持 HTTPS | ✅ 已修复 |
| 4 | 🔴 严重 | undoRule SQL 未参数化 | ✅ 已修复 |
| 5 | 🟡 设计 | GlobalRouteState + RouteTable 双存储 | 非Bug（设计方案） |
| 6 | 🟡 重要 | MatchEngine patternCache 无界 | ✅ 已修复 |
| 7 | 🟡 重要 | MatchEngine 线程不安全 | ✅ 已修复 |
| 8 | 🟡 注意 | Kafka/Pulsar Advice 非 Bootstrap CL 引用 | 可工作（风险评估通过） |
| 9 | 🟡 重要 | Passthrough 无 body 响应处理 | ✅ 已修复 |
| 10 | 🟡 重要 | CORS OPTIONS 预检缺失 | ✅ 已修复 |
| 11 | 🟡 重要 | 批量插入效率低 | ✅ 已修复 |
| 12 | 🟢 一般 | URL 解析不健壮 | ✅ 已修复 |
| 13 | 🟢 一般 | ThreadLocal 泄漏 | ✅ 已修复 |
| 14 | 🟢 一般 | 魔法端口号 | ✅ 已修复 |
| 15 | 🟢 一般 | 缺少单元测试 | 后续迭代 |
| 16 | 🟢 一般 | SnakeYAML 安全漏洞 | ✅ 已修复 |
| 17 | 🟢 一般 | JSON 拼接不安全 | ✅ 已修复 |
| 18 | 🟢 一般 | 代码重复 | ✅ 已修复 |
