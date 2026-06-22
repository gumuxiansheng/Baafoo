# Baafoo 项目架构优化分析报告

> **📦 已归档** — 本报告已被 2026-06-14 全量审查取代，且插件系统此后经历了 P0-P4 大规模重构。保留仅作历史参考。

> 审查日期：2026-06-03

## 一、项目概览

Baafoo 是一个基于 JavaAgent 字节码增强的 API Mock 平台，由 7 个模块组成：

| 模块 | 职责 |
|------|------|
| `baafoo-plugin-api` | SPI 接口定义，零外部依赖 |
| `baafoo-core` | 共享模型、配置、工具类 |
| `baafoo-agent` | JavaAgent 字节码增强 + 路由控制 |
| `baafoo-server` | Netty 多协议 Stub 服务端 + Web 控制台 |
| `baafoo-cli` | 命令行工具 |
| `baafoo-test-app/spring` | 测试应用 |

依赖关系：`plugin-api ← core ← agent/server/cli`

---

## 二、架构优化建议（按优先级排序）

### 1. [高] HttpStubHandler 职责过重，需拆分

`baafoo-server/.../handler/HttpStubHandler.java` 长达 590 行，同时承担了：
- HTTP 请求解析
- 规则匹配
- Stub 响应渲染
- Passthrough 代理（含 HttpURLConnection 实现）
- 录制逻辑
- 环境解析
- 错误处理

**建议拆分为：**
- `HttpRequestParser` — 请求解析
- `PassthroughProxy` — 代理转发（当前用 `HttpURLConnection`，可考虑用 Netty 异步客户端替代）
- `RecordingService` — 录制逻辑
- `StubResponseRenderer` — Stub 响应构建

---

### 2. [高] Passthrough 使用阻塞式 HttpURLConnection

`HttpStubHandler#doPassthrough` 方法使用 `HttpURLConnection` 进行同步阻塞调用，虽然提交到了线程池，但：
- 线程池资源有限（4-64 线程），高并发下会成为瓶颈
- 与 Netty 的事件驱动模型不匹配
- 无法复用 Netty 的连接池和 SSL 上下文

**建议：** 使用 Netty 的 `HttpClient`（异步非阻塞）替代，消除线程池依赖，与 Netty Pipeline 统一模型。

---

### 3. [高] Agent 全局状态管理过于静态化

`BaafooAgent` 和 `RouteManager` 大量使用 `static` 字段和 `volatile` 变量管理状态：
- `BaafooAgent` 的 `config`、`controlChannel`、`pluginManager` 都是 static volatile
- `RouteManager` 完全是 static 方法 + static 字段
- `GlobalRouteState` 使用 static ConcurrentHashMap + 反射同步到 Bootstrap CL

**问题：**
- 测试困难（无法 mock/注入）
- 状态同步逻辑散落在多处（`syncModeToBootstrapCL`、`syncRoutesToBootstrapCL`）
- 反射操作脆弱，重构容易遗漏

**建议：** 引入一个 `AgentContext` 单例对象，集中管理所有运行时状态，将 Bootstrap CL 同步逻辑封装为内部实现细节，而非暴露给外部。

---

### 4. [高] JdbcStorageService 每次 CRUD 都新建 SqlSession

`JdbcStorageService` 的每个方法都通过 `openSession()` 获取新 Session：

```java
try (SqlSession session = openSession()) {
    return session.getMapper(RuleMapper.class).listRules();
}
```

**问题：**
- `init()` 中获取的 mapper 实例在 `try-with-resources` 后已失效，后续方法每次重新获取
- 每次操作都有获取/释放连接的开销
- 批量操作（如 `syncSceneEnvironmentsToRules`）中循环调用 `updateRule`，每次都是独立事务

**建议：**
- 移除 `init()` 中的无效 mapper 缓存
- 对于高频读操作（如 `listRules`），考虑引入本地缓存（Caffeine/Guava Cache），避免每次请求都查数据库
- 批量操作使用同一 SqlSession + 手动事务

---

### 5. [中] HttpStubHandler 每次请求都查数据库

每个 HTTP 请求都执行 `storage.listRules()` + `storage.listEnvironments()` + `storage.listAgents()`，在高流量场景下数据库会成为瓶颈。

**建议：** 在 Server 端引入规则缓存，规则变更时通过事件通知刷新，Agent 端已有类似机制（ControlChannel 推送），Server 端可复用。

---

### 6. [中] Advice 类缺乏抽象，存在代码重复

Agent 中的 Advice 类（`SocketConnectAdvice`、`NioSocketConnectAdvice`、`ConsulDnsAdvice` 等）都有相似的模式：
- 检查 `AgentManifest.agentLoaded`
- 从 `GlobalRouteState.ROUTES` 查找路由
- 替换地址为 stub 地址

**建议：** 提取 `AbstractConnectAdvice` 基类或工具方法，统一路由查找和地址替换逻辑，减少重复代码。

---

### 7. [中] PluginManager 已就绪但未激活

`PluginManager` 和 `PluginClassLoader` 已实现，但 `BaafooAgent.premain` 中只是 `new PluginManager()` 后未调用任何加载方法。同时 `TransformRegistry` 仅做登记，不影响实际增强逻辑。

**建议：**
- 明确插件系统的定位：是让用户自定义拦截逻辑，还是仅作为内部扩展点？
- 如果暂不启用，可添加 `@Deprecated` 或文档说明，避免维护负担
- 如果要启用，需将 Advice 注册逻辑与 PluginManager 集成

---

### 8. [中] BaafooServer 硬编码依赖 JdbcStorageService

```java
this.storage = new JdbcStorageService(config);
```

直接 `new` 了具体实现，虽然 `StorageService` 接口存在且有 `FileStorage` 实现，但无法通过配置切换。

**建议：** 通过工厂方法或配置选择 StorageService 实现，使 FileStorage 和 JdbcStorageService 可按需切换。

---

### 9. [中] Kafka/Pulsar/JMS Stub 复用 TcpStubHandler

Kafka、Pulsar、JMS 的 Stub 都使用 `TcpStubHandler`，但这些协议有各自的应用层协议格式，直接用 TCP raw 数据处理无法正确解析协议帧。

**建议：** 为每个协议实现独立的 Handler（如 `KafkaStubHandler`、`PulsarStubHandler`），至少解析协议握手阶段，否则 Beta 功能无法真正工作。

---

### 10. [低] 前端缺少 TypeScript 和组件化

Web 前端使用纯 JavaScript + Vue 3，无 TypeScript，无组件拆分（所有页面都是单文件 Vue 组件）。

**建议：**
- 迁移到 TypeScript 提升类型安全
- 抽取公共组件（表格、表单、对话框等）
- API 层按模块拆分（auth、rule、environment 等）

---

### 11. [低] Java 版本停留在 1.8

Parent POM 中 `java.version=1.8`，限制了使用现代 Java 特性（如 Record、sealed class、var 等）。

**建议：** 考虑升级到 Java 11 或 17，Agent 模块可保持 1.8 兼容（通过 Multi-Release JAR），Server 和 Core 模块可使用更高版本。

---

## 三、总结

| 优先级 | 问题 | 影响 |
|--------|------|------|
| 高 | HttpStubHandler 职责过重 | 可维护性差 |
| 高 | 阻塞式 Passthrough | 高并发性能瓶颈 |
| 高 | Agent 全局静态状态 | 测试困难、同步脆弱 |
| 高 | Storage 无缓存 | 每请求查库，性能瓶颈 |
| 中 | Advice 代码重复 | 维护成本 |
| 中 | 插件系统未激活 | 死代码 |
| 中 | Storage 实现硬编码 | 扩展性差 |
| 中 | 协议 Stub 未实现 | Beta 功能不可用 |
| 低 | 前端无 TS/组件化 | 可维护性 |
| 低 | Java 版本过旧 | 功能受限 |

核心优化方向：**拆分大类、引入缓存、异步化 Passthrough、收敛 Agent 状态管理**。
