# Baafoo 架构改进 TODO

> 生成时间：2026-07-01
> 依据：代码扫描 + 《Baafoo 架构批判：一个设计蓝图优秀但实现严重欠债的半成品》
> 状态：待实施

---

## 说明

本文件汇总 Baafoo 项目当前代码与架构承诺之间的差距，按优先级分为 P0/P1/P2/P3 四级。每项包含问题描述、影响、涉及文件、修改步骤、验收标准和风险。

---

## P0：立刻修复（安全底线 + 数据一致性）

### P0-1 替换 SHA-256 密码哈希为 bcrypt/Argon2

- **问题**：`AuthService.hashPassword()` 使用 SHA-256 + 盐，GPU 暴力破解速度可达每秒数十亿次，不符合现代密码学标准。
- **位置**：
  - [baafoo-server/src/main/java/com/baafoo/server/auth/AuthService.java](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/auth/AuthService.java#L202-L229)
- **影响**：所有用户密码存储机制不安全，属于 P0 级漏洞。
- **修改步骤**：
  1. 引入 bcrypt 或 Argon2 依赖（推荐 `org.springframework.security:spring-security-crypto` 或 `de.mkammerer:argon2-jvm`）。
  2. 修改 `hashPassword()` 使用 `BCryptPasswordEncoder` 或 Argon2。
  3. 修改 `verifyPassword()` 兼容新哈希格式。
  4. 升级时自动识别旧 SHA-256 哈希（以 Base64 盐+哈希格式），登录成功后重新哈希为新格式。
  5. 更新密码复杂度校验，确保最小 12 位（可配置）。
- **验收标准**：
  - 单元测试验证 bcrypt/Argon2 哈希与校验。
  - 旧 SHA-256 密码可平滑迁移。
  - 安全扫描工具不再报告 SHA-256 密码哈希。
- **风险**：迁移期间需要兼容旧密码格式，建议只升级一次。

### P0-2 删除源码硬编码弱口令

- **问题**：`BaafooServer.ensureAdminUser()` 中硬编码了 `admin123` 和 `B@af00!Adm1n#2026` 作为弱口令检测依据。
- **位置**：
  - [baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java#L288-L292)
- **影响**：源码泄露即可直接获得默认管理员密码枚举清单。
- **修改步骤**：
  1. 将弱口令列表提取到外部配置文件（`application.yml` 或独立文件），源码中不再保留任何明文密码。
  2. 使用 `PasswordValidation` 的强度规则（长度、大小写、数字、特殊字符）判断是否为弱口令，而非匹配固定字符串。
  3. 首次启动时若 admin 不存在，生成随机强密码并写入受保护文件，不再使用任何默认密码。
- **验收标准**：
  - 源码中搜索不到 `admin123`、`B@af00!Adm1n#2026` 或类似明文。
  - 首次启动生成的 admin 密码为随机 16 位以上强密码。
  - 现有弱口令在启动时被检测到并重置。

### P0-3 禁用 localBypass 默认值并安全处理 X-Forwarded-For

- **问题**：`ServerConfig.AuthConfig.localBypass` 默认为 `true`，任意 localhost 请求免认证；`AuthFilter.resolveRemoteAddr()` 直接使用 `X-Forwarded-For` 做鉴权决策。
- **位置**：
  - [baafoo-core/src/main/java/com/baafoo/core/config/ServerConfig.java](file:///c:/Dev/Projects/Baafoo/baafoo-core/src/main/java/com/baafoo/core/config/ServerConfig.java#L205-L208)
  - [baafoo-server/src/main/java/com/baafoo/server/auth/AuthFilter.java](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/auth/AuthFilter.java#L118-L130)
- **影响**：反向代理后任意客户端可通过伪造 `X-Forwarded-For: 127.0.0.1` 绕过认证。
- **修改步骤**：
  1. `localBypass` 默认值改为 `false`。
  2. 新增受信任代理 IP 列表配置 `trustedProxies`。
  3. 只有请求确实来自 `127.0.0.1` 或 `::1` 且未经过不可信代理时才允许 local bypass。
  4. `resolveRemoteAddr()` 优先使用连接层 remote address；仅在存在受信任代理且 `X-Forwarded-For` 最左侧为可信内网地址时才解析。
  5. 文档中明确说明 local bypass 的安全风险。
- **验收标准**：
  - 默认配置下，伪造 `X-Forwarded-For: 127.0.0.1` 无法绕过认证。
  - 单元测试覆盖可信代理和不可信代理场景。

### P0-4 修复管理员密码重启自动重置为文件明文的问题

- **问题**：`BaafooServer.ensureAdminUser()` 在检测到 admin 角色或弱口令时会删除用户并重新创建，将随机密码明文写入 `.admin-credentials` 文件。
- **位置**：
  - [baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/bootstrap/BaafooServer.java#L283-L340)
- **影响**：每次重启都可能导致密码变更，且密码以明文落盘；运维难以管理。
- **修改步骤**：
  1. 删除自动删除+重建 admin 用户的逻辑。
  2. 仅在新安装（admin 不存在）时生成随机密码并写入文件。
  3. 角色修复改为 update 而非 delete+create，避免密码变更。
  4. 提供显式管理 API 或环境变量设置 admin 密码，替代自动重置。
- **验收标准**：
  - 正常重启不再修改已有 admin 密码。
  - 角色错误可在不重置密码的情况下修复。

### P0-5 移除或严格限制 PassthroughProxy 的 SSL 证书校验绕过

- **问题**：`PassthroughProxy.getInsecureSslContext()` 使用 `InsecureTrustManagerFactory.INSTANCE`，全局跳过 SSL 证书校验。
- **位置**：
  - [baafoo-server/src/main/java/com/baafoo/server/handler/PassthroughProxy.java](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/handler/PassthroughProxy.java#L80-L91)
- **影响**：中间人攻击风险；企业环境通常禁止完全跳过 SSL 校验。
- **修改步骤**：
  1. 默认使用安全 SSL 上下文（`SslContextBuilder.forClient().build()`）。
  2. 仅在配置显式开启 `baafoo.passthrough.insecure-ssl=true` 且日志输出 WARNING 时才使用不安全上下文。
  3. 支持配置受信任 CA 证书路径 `baafoo.passthrough.trusted-ca-file`。
- **验收标准**：
  - 默认配置下 SSL 校验生效。
  - 测试用例验证自签名证书可配置信任，不依赖全局跳过。

### P0-6 修复 RouteManager.rebuildRouteTable 的非原子更新

- **问题**：`GlobalRouteState.ROUTES` 在 `RouteManager.rebuildRouteTable()` 中通过 `clear()` + `putAll()` 更新，存在中间状态，并发请求可能拿到空路由表。
- **位置**：
  - [baafoo-agent/src/main/java/com/baafoo/agent/advice/RouteManager.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/advice/RouteManager.java)
  - [baafoo-agent/src/main/java/com/baafoo/agent/GlobalRouteState.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/GlobalRouteState.java#L19)
- **影响**：高并发下出现偶发匹配失败或错误路由。
- **修改步骤**：
  1. 使用 `AtomicReference<ConcurrentHashMap<String, HostPort>>` 或 volatile 引用替换。
  2. 重建时先构建新 Map，再一次性 CAS 替换旧引用。
  3. 删除 `GlobalRouteState.ROUTES.clear()` 这类破坏性公开方法，或改为内部私有。
- **验收标准**：
  - 路由更新期间不存在中间空表状态。
  - 并发测试通过 JCTools 或 JCStress 验证。

---

## P1：1-2 周内完成（架构还债）

### P1-1 统一 Handler 的环境模式决策树：引入 ModeStrategy 接口

- **问题**：`HttpStubHandler`、`TcpStubHandler`、`GrpcUnifiedHandler` 中各自复制了环境模式决策逻辑（PASSTHROUGH/RECORD/RECORD_ALL/RECORD_AND_STUB/STUB）。
- **位置**：
  - [baafoo-server/src/main/java/com/baafoo/server/handler/HttpStubHandler.java](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/handler/HttpStubHandler.java#L179-L375)
  - [baafoo-server/src/main/java/com/baafoo/server/handler/TcpStubHandler.java](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/handler/TcpStubHandler.java#L135-L220)
  - [baafoo-server/src/main/java/com/baafoo/server/handler/GrpcUnifiedHandler.java](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/handler/GrpcUnifiedHandler.java#L262-L315)
- **影响**：模式行为不一致风险高，修改需改三处，维护成本大。
- **修改步骤**：
  1. 定义 `ModeStrategy` 接口：`ModeDecision decide(MatchResult, EnvironmentMode, RequestContext)`。
  2. 实现 5 个策略类：`StubModeStrategy`、`RecordModeStrategy`、`RecordAndStubModeStrategy`、`PassthroughModeStrategy`、`RecordAllModeStrategy`。
  3. 创建 `ModeStrategyFactory` 根据模式返回对应策略。
  4. 在三个 Handler 中统一调用 `ModeStrategy`，删除重复分支。
  5. 定义 `RequestContext` 和 `ModeDecision` 值对象。
- **验收标准**：
  - 三个 Handler 不再包含硬编码的 `switch(mode)` 或 `if (mode == ...)` 决策树。
  - 新增模式只需新增策略类 + 注册。
  - 全量集成测试通过。

### P1-2 拆分 GlobalRouteState 为 6 个职责单一的管理类

- **问题**：`GlobalRouteState` 474 行，同时承担路由表、DNS 缓存、录制会话跟踪、日志桥接、插件桥接函数、协议推断器 6 种职责；所有字段 `public static volatile`。
- **位置**：
  - [baafoo-agent/src/main/java/com/baafoo/agent/GlobalRouteState.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/GlobalRouteState.java)
- **影响**：无封装边界，任何 Advice 都可破坏全局状态；测试困难。
- **Agent ClassLoader 约束（必须遵守）**：

  > **背景**：Advice 代码被 ByteBuddy 内联到 `java.net.Socket`、`java.net.InetAddress`、`sun.nio.ch.SocketChannelImpl` 等 Bootstrap CL 类中。这些代码只能看到 Bootstrap CL 的 `GlobalRouteState` 副本。App CL 和 Bootstrap CL 各有独立的 `GlobalRouteState` 类实例，通过反射同步字段。

  1. **Bootstrap JAR 约束**：当前 `createBootstrapJar()`（[BaafooAgent.java:513-553](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java#L513-L553)）仅打包 `GlobalRouteState.class` + `GlobalRouteState$HostPort.class`。拆分后，**任何被 Bootstrap CL Advice 直接引用的新类都必须加入 Bootstrap JAR**。
  2. **双 CL 副本 + 反射同步约束**：拆分后每个新类如果被 Bootstrap CL Advice 引用，就需要在 App CL 和 Bootstrap CL 各保留一份副本，并通过反射同步。当前有 5 个同步方法需要对应更新：
     - `BaafooAgent.syncGlobalRouteStateToBootstrapCL()`（10 个标量字段 + ROUTES）
     - `BaafooAgent.syncLogHandlersToBootstrapCL()`（3 个日志 handler）
     - `BaafooAgent.syncRecordingWrappersToBootstrapCL()`（6 个函数式桥）
     - `RouteManager.syncModeToBootstrapCL()`（CURRENT_MODE 增量）
     - `RouteManager.syncRoutesToBootstrapCL()`（ROUTES 原子交换）
  3. **字段直访 vs 包装方法约束**：部分 Advice 直接读取 `GlobalRouteState.PLUGIN_CONSULT_FN_EXT` 等字段（无包装方法），拆分后这些字段必须保留在 Bootstrap CL 可见的类中，或改为通过包装方法访问。

- **拆分方案（基于 CL 约束的分层设计）**：

  | 新类 | 是否加入 Bootstrap JAR | 是否需要双 CL 同步 | 说明 |
  |------|:---:|:---:|------|
  | `RouteTable` | **是** | **是** | `ROUTES` + `lookup()` + `HostPort`，被 `SocketConnectAdvice` 等直接引用 |
  | `DnsCache` | **是** | 否（运行时各自维护） | `DNS_CACHE` + `DNS_REDIRECT_TARGET`，两个 CL 各自填充，闭环自洽 |
  | `RecordingTracker` | **是** | **是** | `RECORDING_SESSIONS` + `INPUT/OUTPUT_STREAM_WRAPPER` + `NIO_RECORDING_HANDLER`，被 `SocketInputStreamAdvice` 等直接引用 |
  | `LogBridge` | **是** | **是** | `LOG_*_HANDLER` + `logInfo/logWarn/logError/logDebug` 包装方法 |
  | `PluginBridge` | **是** | **是** | `PLUGIN_CONSULT_FN` + `PLUGIN_CONSULT_FN_EXT` + `EVENT_FIRE_FN`，被 3 个 Advice 直接字段访问 |
  | `ProtocolMapper` | **是** | **是** | `CURRENT_MODE` + 所有 `*_PORT` 常量 + `isInternal()` + `inferProtocol()` |

  > **关键设计决策**：由于 6 个新类都需要被 Bootstrap CL Advice 引用，**全部必须加入 Bootstrap JAR**。`GlobalRouteState` 退化为 Facade，将所有静态方法委托到这 6 个类，保持 Advice 代码无需改动（或最小改动）。

- **修改步骤**：
  1. 新建 6 个类，将 `GlobalRouteState` 的字段和方法按上表分配。
  2. `GlobalRouteState` 保留为 Facade，所有静态字段改为委托到新类（如 `GlobalRouteState.ROUTES` → `RouteTable.getRoutes()`），保持 Advice 代码无需改动。
  3. 更新 `createBootstrapJar()` 的 `classResources` 数组，加入 6 个新类的 `.class` 文件。
  4. 更新 5 个反射同步方法，分别同步各新类的字段。
  5. 将字段改为 `private static volatile`，通过 getter/setter 访问。
  6. **测试验证**：确保 Bootstrap CL Advice 仍能正确读取同步后的状态。
- **验收标准**：
  - 每个新类行数 < 150 行。
  - `createBootstrapJar()` 包含所有 8 个 class（6 新类 + `GlobalRouteState` + `HostPort`）。
  - 5 个反射同步方法均更新且功能正确。
  - Agent 启动和集成测试通过，Advice 拦截功能不受影响。
- **风险**：Bootstrap JAR 体积增大；反射同步方法增多导致维护成本上升。建议考虑用序列化 DTO 替代逐字段反射（见 P2-4）。

### P1-3 从 JdbcStorageService 中提取场景-规则继承逻辑到 SceneService

- **问题**：`JdbcStorageService`（1087 行）中混入了 `syncSceneEnvironmentsToRules` 和 `isEnvironmentInheritedFromOtherScene` 等领域逻辑，产生 O(R×E×S) 次 SQL 查询。
- **位置**：
  - [baafoo-server/src/main/java/com/baafoo/server/storage/JdbcStorageService.java](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/storage/JdbcStorageService.java#L520-L590)
- **影响**：存储层承担业务逻辑；场景更新性能差。
- **修改步骤**：
  1. 新建 `SceneService`，负责场景与规则的关联、环境继承计算。
  2. 在 `SceneService` 中批量查询规则和环境，一次性计算 diff，减少 SQL 次数。
  3. `JdbcStorageService` 只提供原子 CRUD 操作（`getRule`、`updateRuleEnvironments` 等）。
  4. 上层 API（`ManagementApiHandler`）调用 `SceneService` 而非直接调用 `StorageService` 的领域方法。
- **验收标准**：
  - `JdbcStorageService` 不再包含 `syncSceneEnvironmentsToRules`。
  - 场景更新 SQL 次数从 O(R×E×S) 降至 O(R+E)。
  - 场景相关单元测试通过。

### P1-4 全面激活 Plugin SPI 或彻底删除半吊子实现

- **问题**：文档和代码声称采用微内核 + SPI，但实际上 `SocketConnectAdvice`、`KafkaProducerAdvice`、`JmsConnectionFactoryAdvice`、`GrpcChannelAdvice` 全部直接调用 `GlobalRouteState.lookup()`；`PluginManager` 被标注 `@Deprecated`。
- **位置**：
  - [baafoo-agent/src/main/java/com/baafoo/agent/advice/SocketConnectAdvice.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/advice/SocketConnectAdvice.java)
  - [baafoo-agent/src/main/java/com/baafoo/agent/advice/KafkaProducerAdvice.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/advice/KafkaProducerAdvice.java)
  - [baafoo-agent/src/main/java/com/baafoo/agent/advice/JmsConnectionFactoryAdvice.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/advice/JmsConnectionFactoryAdvice.java)
  - [baafoo-agent/src/main/java/com/baafoo/agent/advice/GrpcChannelAdvice.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/advice/GrpcChannelAdvice.java)
  - [baafoo-agent/src/main/java/com/baafoo/agent/plugin/PluginManager.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/plugin/PluginManager.java)
- **影响**：大量 SPI/Plugin 代码和文档成为无效资产，架构承诺不兑现。
- **Agent 字节码内联约束（必须遵守）**：

  > **背景**：Advice 代码被 ByteBuddy 内联到目标类中。Bootstrap CL Advice（`SocketConnectAdvice`、`NioSocketConnectAdvice` 等）内联到 JDK 类，**无法直接调用 App CL 类**（如 `PluginManager`）。App CL Advice（`GrpcChannelAdvice`、`KafkaProducerAdvice`、`JmsConnectionFactoryAdvice`）内联到应用类，可以直接调用 App CL 类。

  1. **函数桥接机制不可绕过**：当前 `PluginManager` 不直接设置 `GlobalRouteState` 的任何字段。所有桥接由 `BaafooAgent.premain()` 充当中介，通过 lambda 闭包捕获 `pluginManager` 实例，包装成 `Function`/`Consumer` 设置到 `GlobalRouteState` 的静态字段：
     - `PLUGIN_CONSULT_FN`（[BaafooAgent.java:103-129](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java#L103-L129)）：入参 `{host, port}` → 调用 `pluginManager.getPlugin(SOCKET/NIO_SOCKET).intercept(ctx)` → 返回 `{targetHost, targetPort}`
     - `PLUGIN_CONSULT_FN_EXT`（[BaafooAgent.java:136-176](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java#L136-L176)）：入参 `{host, port, protocol}` → 调用 `pluginManager.connectWithMonitor(target, ctx)` → 返回 `{action, host, port, reason}`
     - `EVENT_FIRE_FN`（[BaafooAgent.java:179-184](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java#L179-L184)）：入参 `PluginEvent` → 调用 `pluginManager.fireEvent(event)`
  2. **Advice 访问模式**：Bootstrap CL Advice 通过**直接字段读取**访问 `PLUGIN_CONSULT_FN`/`PLUGIN_CONSULT_FN_EXT`（无包装方法），通过**包装方法**访问 `firePluginEvent()`。App CL Advice 的访问模式相同，但读取的是 App CL 副本。
  3. **同步约束**：3 个桥接函数由 `syncRecordingWrappersToBootstrapCL()`（[BaafooAgent.java:695-722](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java#L695-L722)）同步到 Bootstrap CL 副本。

- **修改步骤（方案 A：激活 SPI，推荐）**：
  1. 移除 `PluginManager` 的 `@Deprecated`。
  2. **维持函数桥接机制**：所有 Advice 继续通过 `GlobalRouteState.PLUGIN_CONSULT_FN` / `PLUGIN_CONSULT_FN_EXT` / `EVENT_FIRE_FN` 访问插件，**不直接引用 `PluginManager`**。
  3. 扩展 `PLUGIN_CONSULT_FN_EXT` 的 lambda 闭包，增加对 Kafka/Pulsar/JMS/gRPC 协议的插件查找（当前仅处理 SOCKET/NIO_SOCKET）：
     ```
     switch (protocol) {
         case "tcp": case "nio": target = SOCKET/NIO_SOCKET; break;
         case "kafka": target = KAFKA; break;
         case "pulsar": target = PULSAR; break;
         case "jms": target = JMS; break;
         case "grpc": target = GRPC; break;  // 新增
     }
     ```
  4. 为 `GrpcChannelAdvice`（App CL）增加 SPI 咨询逻辑（当前已有，确认完整）。
  5. 为 `KafkaProducerAdvice`、`JmsConnectionFactoryAdvice`、`PulsarClientAdvice` 增加 SPI 咨询逻辑（当前直接调用 `BaafooAgent.getPluginManager()`，应改为通过桥接函数或保持直接调用——因为它们在 App CL 运行，可直接访问 `PluginManager`）。
  6. **注意**：App CL Advice（Kafka/JMS/Pulsar/gRPC）可以直接调用 `BaafooAgent.getPluginManager()`，不需要通过桥接函数。只有 Bootstrap CL Advice（Socket/NioSocket）必须通过桥接函数。
  7. 补充 SPI 集成测试。
- **修改步骤（方案 B：删除 SPI）**：
  1. 删除 `baafoo-plugin-api`、`PluginClassLoader`、`PluginManager`、所有 SPI 接口。
  2. 删除 `BaafooAgent.premain()` 中的 lambda 闭包设置（L103-184）。
  3. 删除 `GlobalRouteState` 中的 `PLUGIN_CONSULT_FN`、`PLUGIN_CONSULT_FN_EXT`、`EVENT_FIRE_FN` 字段。
  4. 删除 `syncRecordingWrappersToBootstrapCL()` 中对这 3 个字段的同步。
  5. 从所有 Advice 中移除对这 3 个字段的引用。
  6. 删除 `docs/plugin-developer-guide.md` 和 `.workmemo/10_deliverables/plugin_architecture_enhancement*`。
  7. 更新 README 不再宣传微内核/SPI。
- **验收标准**：
  - 方案 A：所有协议的 Advice 都能通过 SPI 扩展，且默认行为（无插件时）不变。
  - 方案 A：Bootstrap CL Advice 仍通过桥接函数访问插件，App CL Advice 可直接访问 `PluginManager`。
  - 方案 B：项目中不再存在无法运行的 SPI 代码和文档引用。

---

## P2：1 个月内完成（代码质量 + 稳定性）

### P2-1 用 MatchRequest 封装消灭 MatchEngine.match() 的 14 参数

- **问题**：`MatchEngine.match()` 方法签名包含 11 个参数（含重载后可达 14 个），调用方容易参数顺序错误。
- **位置**：
  - [baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java](file:///c:/Dev/Projects/Baafoo/baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java#L45-L119)
- **影响**：可读性差、易出错、难扩展。
- **修改步骤**：
  1. 新建 `MatchRequest` 值对象，包含 protocol、host、port、serviceName、method、path、topic、headers、queryParams、body 等字段。
  2. 修改 `match(List<Rule>, MatchRequest)` 签名。
  3. 修改所有调用方（Http/TCP/gRPC Handler、Advice、测试）。
  4. 保留旧方法作为 `@Deprecated` 委托，逐步迁移。
- **验收标准**：
  - `match()` 方法参数 <= 2 个。
  - 所有调用方使用 Builder 或工厂方法构建 `MatchRequest`。
  - 不影响匹配行为。

### P2-2 修复 PluginManager.fireEvent() 双重投递 Bug

- **问题**：`fireEvent()` 先 `eventBus.fire(event)`，再遍历插件调用 `plugin.onEvent(event)`；而 `loadPlugin()` 又将 `plugin::onEvent` 注册为 EventBus listener，导致每个事件被投递两次。
- **位置**：
  - [baafoo-agent/src/main/java/com/baafoo/agent/plugin/PluginManager.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/plugin/PluginManager.java#L353-L363)
- **影响**：事件处理副作用重复（如重复录制、重复日志）。
- **修改步骤**：
  1. 选择单一投递路径：要么只通过 EventBus（推荐），要么只通过显式遍历。
  2. 若走 EventBus，则移除 `fireEvent()` 中的显式遍历。
  3. 若走显式遍历，则 `loadPlugin()` 不再注册 `plugin::onEvent` 到 EventBus。
  4. 补充 `PluginManagerTest` 验证事件只投递一次。
- **验收标准**：
  - 单元测试断言每个事件每个插件只处理一次。

### P2-3 修复 PluginClassLoader 的 SPI 接口类委派错误

- **问题**：`PluginClassLoader.loadClass()` 对 `com.baafoo.plugin.*` 类委派给 `ClassLoader.getSystemClassLoader()`，在 Java EE/Web 容器中 `getSystemClassLoader()` 不一定是应用类加载器，会导致 SPI 接口找不到。
- **位置**：
  - [baafoo-agent/src/main/java/com/baafoo/agent/loader/PluginClassLoader.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/loader/PluginClassLoader.java#L29-L71)
- **影响**：插件在非标准环境中无法加载。
- **修改步骤**：
  1. 对 SPI 接口类使用 `super.loadClass(name, resolve)`（即父加载器委派），而非 `getSystemClassLoader()`。
  2. 确保 `BaafooAgent` 初始化时 `PluginClassLoader` 的父加载器能加载 `baafoo-plugin-api`。
  3. 补充插件加载集成测试。
- **验收标准**：
  - 插件在标准 JAR 和嵌套类加载器环境中都能加载。

### P2-4 加固 Bootstrap ClassLoader 反射桥接

- **问题**：`syncGlobalRouteStateToBootstrapCL()` 通过反射按字段名匹配同步状态，字段重命名或类型变更会静默断裂。
- **位置**：
  - [baafoo-agent/src/main/java/com/baafoo/agent/advice/RouteManager.java](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/advice/RouteManager.java#L270-L304)
- **影响**：运行时出现无法拦截连接等诡异故障，且没有错误提示。
- **修改步骤**：
  1. 使用显式同步方法（如 `RouteTableSnapshot` 序列化对象）替代反射字段逐个同步。
  2. 在 Agent 和 Bootstrap CL 两侧定义相同的 DTO 类。
  3. 同步失败时抛出明确异常。
  4. 减少 `@SuppressWarnings("unchecked")` 的使用。
- **验收标准**：
  - 字段重命名后编译期即可发现不匹配。
  - 启动时同步失败有明确日志。

### P2-5 给 MatchEngine.matchRegex() 添加超时机制防 ReDoS

- **问题**：`MatchEngine.matchRegex()` 直接调用 `Pattern.matches()`，恶意正则可能导致 ReDoS。
- **位置**：
  - [baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java](file:///c:/Dev/Projects/Baafoo/baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java#L575-L590)
- **影响**：恶意规则可拖垮匹配线程。
- **修改步骤**：
  1. 使用 `java.util.regex.Pattern` + 独立线程或 `GuardedInvoker` 限制单次匹配时间（如 100ms）。
  2. 超时时视为不匹配并记录警告。
  3. 配置化超时阈值。
- **验收标准**：
  - ReDoS 正则测试用例在 200ms 内返回不匹配。

### P2-6 解耦 MatchEngine 对 StatefulCounterStore.global() 的静态依赖

- **问题**：`matchConditions()` 直接静态引用 `StatefulCounterStore.global()`，单元测试需要初始化全局状态。
- **位置**：
  - [baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java](file:///c:/Dev/Projects/Baafoo/baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java#L179-L190)
- **影响**：依赖注入缺失，测试脆弱。
- **修改步骤**：
  1. 将 `StatefulCounterStore` 作为构造函数参数传入 `MatchEngine`。
  2. 默认实现仍使用全局 store，但测试可注入 mock。
  3. 修改所有 `new MatchEngine()` 的调用方。
- **验收标准**：
  - `MatchEngineTest` 不再依赖全局状态初始化。

### P2-7 修复 GRPC_PORT/GRPC_STREAMING_PORT 未同步到 Bootstrap CL

- **问题**：`BaafooAgent.syncGlobalRouteStateToBootstrapCL()` 同步了 `HTTP_PORT`~`JMS_PORT` 共 8 个端口，但遗漏了 `GRPC_PORT`（[GlobalRouteState.java:56](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/GlobalRouteState.java#L56)）和 `GRPC_STREAMING_PORT`（[GlobalRouteState.java:59](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/GlobalRouteState.java#L59)）。
- **位置**：
  - [BaafooAgent.java:589-593](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java#L589-L593)（同步方法，缺失 GRPC 端口）
- **影响**：当前不影响功能，因为 `GrpcChannelAdvice` 运行在 App CL（直接访问 App CL 副本）。但 `GlobalRouteState.isInternal()` 会被 Bootstrap CL Advice 调用，若 Server 配置非默认端口（非 9005/10005），Bootstrap CL 副本中 `isInternal()` 会判断错误。
- **修改步骤**：
  1. 在 `syncGlobalRouteStateToBootstrapCL()` 中增加 `GRPC_PORT` 和 `GRPC_STREAMING_PORT` 的同步。
  2. 在 `AgentManifest` 的端口 setter 中增加对应的增量同步。
- **验收标准**：
  - Bootstrap CL 副本的 `GRPC_PORT`/`GRPC_STREAMING_PORT` 与 App CL 一致。

### P2-8 修复 LOG_DEBUG_HANDLER 从未设置

- **问题**：`BaafooAgent.premain()` 设置了 `LOG_INFO_HANDLER`、`LOG_WARN_HANDLER`、`LOG_ERROR_HANDLER`，但**从未设置 `LOG_DEBUG_HANDLER`**（[GlobalRouteState.java:76](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/GlobalRouteState.java#L76)）。Bootstrap CL 副本中 `logDebug()` 永远 fallback 到 `System.out.println`，导致 debug 日志无法通过 SLF4J 过滤。
- **位置**：
  - [BaafooAgent.java:91-96](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java#L91-L96)（设置 3 个 handler，遗漏 DEBUG）
  - [BaafooAgent.java:729-755](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java#L729-L755)（同步 3 个 handler，遗漏 DEBUG）
- **影响**：Agent debug 日志直接输出到 stdout，不受日志框架控制，可能干扰宿主应用输出。
- **修改步骤**：
  1. 在 `premain()` 中增加 `LOG_DEBUG_HANDLER` 的设置（`log::debug`）。
  2. 在 `syncLogHandlersToBootstrapCL()` 中增加 `LOG_DEBUG_HANDLER` 的同步。
- **验收标准**：
  - debug 日志通过 SLF4J 输出，可通过日志配置控制级别。

---

## P3：长期重构（架构统一）

### P3-1 重建统一的协议抽象层

- **问题**：HTTP/TCP/gRPC/Kafka/Pulsar/JMS 的 Handler 和 Advice 实现重复度高，没有统一的协议抽象层。
- **位置**：
  - [baafoo-server/src/main/java/com/baafoo/server/handler/](file:///c:/Dev/Projects/Baafoo/baafoo-server/src/main/java/com/baafoo/server/handler/)
  - [baafoo-agent/src/main/java/com/baafoo/agent/advice/](file:///c:/Dev/Projects/Baafoo/baafoo-agent/src/main/java/com/baafoo/agent/advice/)
- **修改步骤**：
  1. 定义 `ProtocolBroker` 接口：encode/decode/match/render/record。
  2. 为每个协议实现 `HttpBroker`、`TcpBroker`、`GrpcBroker`、`KafkaBroker`、`PulsarBroker`、`JmsBroker`。
  3. Server 和 Agent 统一通过 `ProtocolBroker` 处理协议细节。
- **验收标准**：
  - 新增协议只需新增 Broker 实现和注册。

### P3-2 Plugin SPI 接口协议特有字段的一等公民建模

- **问题**：当前 SPI 接口使用通用 `Map<String, Object>` 传递协议字段，gRPC/Kafka 等协议特有字段（如 `grpcService`、`topic`）不是一等公民。
- **位置**：
  - [baafoo-plugin-api/src/main/java/com/baafoo/plugin/AgentPlugin.java](file:///c:/Dev/Projects/Baafoo/baafoo-plugin-api/src/main/java/com/baafoo/plugin/AgentPlugin.java)
- **修改步骤**：
  1. 在 SPI 中引入 `InterceptContext` 层次结构（`HttpContext`、`GrpcContext`、`MqContext` 等）。
  2. 插件可按需处理强类型字段。
  3. 保持向后兼容的通用 Map fallback。
- **验收标准**：
  - 插件开发者可直接访问 `context.getGrpcService()` 等类型化 API。

### P3-3 补齐文档一致性

- **问题**：README/AGENTS/docs 中关于插件、SPI、环境模式的描述与代码实现不一致。
- **位置**：
  - [README.md](file:///c:/Dev/Projects/Baafoo/README.md)
  - [AGENTS.md](file:///c:/Dev/Projects/Baafoo/AGENTS.md)
  - [docs/plugin-developer-guide.md](file:///c:/Dev/Projects/Baafoo/docs/plugin-developer-guide.md)
- **修改步骤**：
  1. P1-4 完成后，统一更新所有文档。
  2. 明确说明 SPI 支持范围、加载方式、事件机制。
  3. 删除过时的 `PluginManager @Deprecated` 描述。

---

## 附录：已确认不存在的虚假问题

以下问题在最初审查时被误报，实际代码已正确实现：

| 误报问题 | 实际情况 |
|---------|---------|
| Java Core Model 缺少 gRPC 字段 | `Rule.java`、`RecordingEntry.java`、`ResponseEntry.java` 已包含完整 gRPC 字段 |
| 前端规则编辑器缺少 gRPC 选项 | `RuleEditorPage.vue` 协议下拉框已包含 gRPC |
| `isMqProtocol` 错误包含 gRPC | 已正确拆分为 `isMqProtocol` 和 `isGrpcProtocol` |
| Agent gRPC 默认端口 9090 不一致 | `GrpcChannelAdvice` 已使用 `GlobalRouteState.GRPC_PORT = 9005` |

---

## 执行建议

1. 先完成 P0-1 到 P0-6 的安全和一致性修复，每项独立提交。
2. P2-7/P2-8（Agent 同步缺陷修复）可立即执行，无需等待 P1。
3. P1 按依赖顺序执行：P1-2（拆分 GlobalRouteState）完成后，P1-1（ModeStrategy）和 P1-4（SPI 激活）更容易落地。P1-2 必须严格遵守上述 Bootstrap CL 约束。
4. P1-4（SPI 激活）需区分 Bootstrap CL Advice 和 App CL Advice 的不同约束：
   - Bootstrap CL Advice（Socket/NioSocket）：必须通过 `GlobalRouteState` 桥接函数访问插件
   - App CL Advice（Kafka/JMS/Pulsar/gRPC）：可直接调用 `PluginManager`
5. P2 其余项可与 P1 并行推进，但需避免大规模重构与模式行为修改混合提交。
6. P3 作为长期技术债，可在后续迭代中分阶段实施。
