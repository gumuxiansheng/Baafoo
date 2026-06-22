# Baafoo 代码坏味道报告

> **📦 已归档** — 本报告已被 2026-06-14 全量审查（见 `5_review/index.md`）完全取代。保留仅作历史参考。

> 审查日期：2026-05-31

---

## 一、God Class（上帝类）

### S1. ManagementApiHandler — 728 行巨型处理器

**文件**：`baafoo-server/.../api/ManagementApiHandler.java`

`handleApiRequest` 方法长达 420 行，包含 30+ 个 if 分支处理所有 REST 端点（用户、规则、环境、录制、场景、Agent、状态）。这是典型的 God Class。

**建议**：按资源拆分为独立的 Handler 类：
- `UserApiHandler` — 用户管理
- `RuleApiHandler` — 规则 CRUD
- `EnvironmentApiHandler` — 环境管理
- `SceneApiHandler` — 场景管理
- `AgentApiHandler` — Agent 注册/心跳/轮询
- `StatusApiHandler` — 系统状态

主 `ManagementApiHandler` 只做路由分发。

### S2. H2StorageService — 1195 行巨型存储类

**文件**：`baafoo-server/.../storage/H2StorageService.java`

包含 6 种实体的全部 CRUD + JSON 序列化/反序列化逻辑。`mapRule`、`setRuleParams`、`mapEnvironment`、`setEnvironmentParams` 等方法高度重复。

**建议**：按实体拆分为独立 Repository：
- `RuleRepository` — 规则 + 版本历史
- `EnvironmentRepository` — 环境管理
- `SceneRepository` — 场景管理
- `RecordingRepository` — 录制数据
- `AgentRepository` — Agent 管理

---

## 二、Long Method（过长方法）

### S3. handleApiRequest — 420 行

**文件**：`ManagementApiHandler.java:117-537`

单个方法处理所有 API 路由，包含认证、权限检查、参数解析、业务逻辑、响应构造。

### S4. handleCsvImport — 72 行

**文件**：`ManagementApiHandler.java:543-615`

CSV 解析 + 用户创建 + 错误收集全部混在一个方法中。应拆分为 `parseCsv()` + `importUsers()`。

### S5. BaafooAgent.premain — 110 行

**文件**：`BaafooAgent.java:37-146`

Agent 初始化、Byte Buddy 注册、Bootstrap CL 同步全部在 `premain` 中。应提取 `installTransforms()`、`setupBootstrapCl()` 等方法。

---

## 三、Duplicated Code（重复代码）

### S6. sendPassthroughAndRecord 与 sendPassthroughResponse 高度重复

**文件**：`HttpStubHandler.java`

两个 passthrough 方法有约 80% 相同代码（URL 构建、连接设置、body 读取、响应转发）。唯一区别是前者额外做录制。

**建议**：提取 `doPassthrough()` 通用方法，录制逻辑通过回调或布尔参数控制。

### S7. H2StorageService 中 mapXxx/setXxx 模式重复

**文件**：`H2StorageService.java`

`mapRule`/`setRuleParams`、`mapEnvironment`/`setEnvironmentParams`、`mapSceneSet`/`setSceneSetParams`、`mapRuleSet`/`setRuleSetInsertParams`、`mapRecording`/`insertRecording`、`mapAgent` 模式完全一致：读 ResultSet → 填充对象 / 读对象 → 填充 PreparedStatement。JSON 字段的 try-catch 序列化逻辑重复了 6 次。

**建议**：提取通用的 `JsonMapper` 工具类处理 JSON 列的读写。

### S8. ManagementApiHandler 中 "not found" 响应模式重复

以下模式出现了 10+ 次：
```java
return x != null ? ApiResponse.ok(x) : ApiResponse.notFound("Xxx not found");
return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Xxx not found");
```

**建议**：提取 `foundOr404(T item, String name)` 和 `deletedOr404(boolean ok, String name)` 辅助方法。

### S9. ControlChannel 中 ApiResponse 解包逻辑重复

**文件**：`ControlChannel.java:130-155` 和 `196-212`

`register()` 和 `pollRules()` 都有相同的 `mapper.readTree(body)` → `root.get("data")` → `mapper.treeToValue()` 模式。

**建议**：提取 `unwrapApiResponse(String body, Class<T> type)` 通用方法。

---

## 四、Primitive Obsession（基本类型偏执）

### S10. 路由表使用 `ConcurrentHashMap<String, String>` 存储 `host:port`

**文件**：`GlobalRouteState.java`、`RouteTable.java`

路由值是 `"127.0.0.1:9000"` 格式的字符串，每次查询都需要 `parseHost()`/`parsePort()` 解析。

**建议**：使用 `ConcurrentHashMap<String, HostPort>` 类型安全的数据结构，其中 `HostPort` 是仅含 `String host` + `int port` 的简单类（Bootstrap CL 安全）。

### S11. API 响应使用 `Map<String, Object>` 代替类型化 DTO

**文件**：`ManagementApiHandler.java`

大量代码手动构造 `Map<String, Object>`：
```java
Map<String, Object> result = new HashMap<String, Object>();
result.put("token", loginResult.token);
result.put("role", loginResult.role);
```

**建议**：为每个 API 响应创建类型化的 DTO 类（如 `LoginResponse`、`StatusResponse`、`AgentPollResponse`）。

---

## 五、Feature Envy（特性嫉妒）

### S12. ManagementApiHandler 直接操作 User 对象字段

**文件**：`ManagementApiHandler.java:214-227`

API Handler 直接 `user.setUsername()`、`user.setPasswordHash(authService.hashPassword(password))`。密码哈希逻辑应该在 `AuthService` 或 `UserService` 中。

### S13. ManagementApiHandler 包含 CSV 解析逻辑

**文件**：`ManagementApiHandler.java:543-639`

`handleCsvImport` 和 `parseCsvLine` 是 CSV 解析的通用逻辑，不属于 API Handler 的职责。

**建议**：提取到 `CsvUserImporter` 类。

---

## 六、Dead Code（死代码）

### S14. BaafooAgent 中未使用的反射字段缓存

**文件**：`BaafooAgent.java:33-35`

```java
private static volatile java.lang.reflect.Field bootstrapCurrentModeField;
private static volatile java.lang.reflect.Field bootstrapServerHostField;
private static volatile java.lang.reflect.Field bootstrapServerPortField;
```

这些字段在 `syncGlobalRouteStateToBootstrapCL()` 中赋值，但从未被读取。`getBootstrapCurrentModeField()` 等 getter 也没有调用方。

### S15. MyBatis Mapper 接口和 Entity 类未使用

**文件**：`baafoo-server/.../mapper/` 目录下所有文件

`AgentMapper`、`EnvironmentMapper`、`RecordingMapper`、`RuleMapper`、`SceneMapper` 接口及对应的 Entity 类和 XML 映射文件存在，但 `BaafooServer` 使用的是 `MybatisStorageService`，而实际运行时使用的是 `H2StorageService`。这些代码是未启用的备用实现。

---

## 七、Inappropriate Intimacy（不当亲密）

### S16. ManagementApiHandler 直接访问 AuthService 内部类

**文件**：`ManagementApiHandler.java`

`AuthService.AuthResult`、`AuthService.LoginResult`、`AuthService.PasswordValidation` 都是 `AuthService` 的内部类，但被 `ManagementApiHandler` 大量直接访问其 public 字段（`auth.role`、`auth.username`、`loginResult.token`）。内部类应封装为独立类或至少使用 getter。

### S17. ControlChannel 直接修改 AgentConfig

**文件**：`ControlChannel.java:141`

```java
config.setAgentId(res.agentId);
```

`ControlChannel` 直接修改了传入的 `AgentConfig` 对象，违反了配置对象不可变的原则。

---

## 八、Shotgun Surgery（散弹式修改）

### S18. 新增 API 端点需要修改 ManagementApiHandler 多处

添加一个新的 REST 资源（如 "templates"）需要：
1. 在 `handleApiRequest` 中添加路由分支
2. 在 `getRequiredRoleForAction` 中添加权限映射
3. 在 `authenticate` 的 permissions 列表中添加权限

这三个修改点分散在同一个 728 行文件的不同位置。

---

## 九、Speculative Generality（推测性泛化）

### S19. PluginManager / PluginClassLoader 未被实际使用

**文件**：`baafoo-agent/.../plugin/PluginManager.java`、`baafoo-agent/.../loader/PluginClassLoader.java`

插件系统已实现（`AgentPlugin` SPI、`PluginClassLoader`、`PluginManager`），但 `BaafooAgent.premain` 中只创建了 `PluginManager` 实例，从未调用其任何方法，也没有任何插件实现。

### S20. TransformRegistry 只写不读

**文件**：`baafoo-agent/.../transform/TransformRegistry.java`

`registry.register(...)` 被调用了 6 次，`registry.getCount()` 被调用 1 次用于日志。注册的数据从未被查询或使用。

---

## 十、Mutable Static State（可变静态状态）

### S21. AgentManifest 全部使用 public static 可变字段

**文件**：`baafoo-agent/.../AgentManifest.java`

```java
public static String serverHost = "127.0.0.1";
public static int serverPort = 8080;
public static String environmentId = "default";
public static int currentMode = 1;
public static boolean agentLoaded = false;
```

所有字段都是 public static 可变的，任何代码都可以随时修改。这是 Java Agent 中有意为之的设计（Advice 需要静态访问），但缺乏访问保护。

### S22. GlobalRouteState 同样使用 public static 可变字段

**文件**：`baafoo-agent/.../GlobalRouteState.java`

同 S21，`SERVER_HOST`、`SERVER_PORT`、`CURRENT_MODE` 都是 public static 可变字段。

---

## 十一、其他坏味道

### S23. ManagementApiHandler.currentUri 实例字段用于方法间传参

**文件**：`ManagementApiHandler.java:33`

```java
private String currentUri;
```

`currentUri` 在 `channelRead0` 中赋值，在 `handleApiRequest` 中通过 `parseQueryParam(currentUri, ...)` 读取。这是用实例字段代替方法参数的反模式，在 Netty 的多线程环境下尤其危险——虽然 Netty 保证同一 Channel 的处理是串行的，但这是一个隐含的线程安全假设。

### S24. HttpStubHandler.PASSTHROUGH_EXECUTOR 使用无界线程池

**文件**：`HttpStubHandler.java:61-66`

```java
Executors.newCachedThreadPool(...)
```

`newCachedThreadPool` 没有线程数上限，在高并发 passthrough 场景下可能创建大量线程导致 OOM。

**建议**：使用有界线程池，如 `new ThreadPoolExecutor(4, 64, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000))`。

### S25. AuthService.hasPermission 使用字符串常量做权限判断

**文件**：`AuthService.java:220-256`

权限判断全部基于字符串相等比较（`"admin".equals(role)`、`"rule".equals(resource)`），容易拼写错误且无法编译期检查。

**建议**：使用枚举类型（`Role`、`Resource`、`Action`）替代字符串。

### S26. BaafooAgent.premain 中匿名 Runnable 可以用 lambda 替代

**文件**：`BaafooAgent.java:62-67`

```java
Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
    @Override
    public void run() {
        shutdown();
    }
}, "baafoo-shutdown"));
```

项目其他地方已经使用了 lambda，这里应保持一致。

---

## 十二、坏味道汇总

| # | 类型 | 严重度 | 位置 | 描述 | 状态 |
|---|------|--------|------|------|------|
| S1 | God Class | 🔴 高 | ManagementApiHandler | 728 行，30+ 路由分支 | ✅ 已修复 |
| S2 | God Class | 🔴 高 | H2StorageService | 1195 行，6 种实体 CRUD | ✅ 已修复 |
| S3 | Long Method | 🟡 中 | ManagementApiHandler.handleApiRequest | 420 行 | ✅ 已修复 |
| S4 | Long Method | 🟢 低 | ManagementApiHandler.handleCsvImport | 72 行 | ✅ 已修复 |
| S5 | Long Method | 🟡 中 | BaafooAgent.premain | 110 行 | ✅ 已修复 |
| S6 | Duplicated Code | 🔴 高 | HttpStubHandler 两个 passthrough 方法 | 80% 代码重复 | ✅ 已修复 |
| S7 | Duplicated Code | 🟡 中 | H2StorageService mapXxx/setXxx | 6 次重复模式 | ✅ 已修复 |
| S8 | Duplicated Code | 🟢 低 | ManagementApiHandler not found 响应 | 10+ 次重复 | ✅ 已修复 |
| S9 | Duplicated Code | 🟢 低 | ControlChannel ApiResponse 解包 | 2 次重复 | ✅ 已修复 |
| S10 | Primitive Obsession | 🟡 中 | GlobalRouteState 路由值 | 字符串代替结构化类型 | ✅ 已修复 |
| S11 | Primitive Obsession | 🟡 中 | ManagementApiHandler 响应构造 | Map 代替 DTO | ✅ 已修复 |
| S12 | Feature Envy | 🟢 低 | ManagementApiHandler | 直接操作 User 字段 | ✅ 已修复 |
| S13 | Feature Envy | 🟡 中 | ManagementApiHandler | 包含 CSV 解析逻辑 | ✅ 已修复 |
| S14 | Dead Code | 🟢 低 | BaafooAgent 反射字段缓存 | 赋值后未读取 | ✅ 已修复 |
| S15 | Dead Code | 🟡 中 | MyBatis Mapper/Entity | 未启用的备用实现 | ✅ 已修复 |
| S16 | Inappropriate Intimacy | 🟢 低 | ManagementApiHandler ↔ AuthService | 直接访问内部类字段 | ✅ 已修复 |
| S17 | Inappropriate Intimacy | 🟡 中 | ControlChannel → AgentConfig | 直接修改配置对象 | ✅ 已修复 |
| S18 | Shotgun Surgery | 🟡 中 | ManagementApiHandler | 新端点需改 3 处 | ✅ 已修复 |
| S19 | Speculative Generality | 🟢 低 | PluginManager/PluginClassLoader | 已实现未使用 | ✅ 已文档化 |
| S20 | Speculative Generality | 🟢 低 | TransformRegistry | 只写不读 | ✅ 已文档化 |
| S21 | Mutable Static State | 🟡 中 | AgentManifest | public static 可变字段 | ✅ 已修复 |
| S22 | Mutable Static State | 🟡 中 | GlobalRouteState | public static 可变字段 | ✅ 已修复 |
| S23 | 临时字段传参 | 🟡 中 | ManagementApiHandler.currentUri | 实例字段代替方法参数 | ✅ 已修复 |
| S24 | 无界线程池 | 🔴 高 | HttpStubHandler.PASSTHROUGH_EXECUTOR | 可能 OOM | ✅ 已修复 |
| S25 | 字符串常量权限 | 🟢 低 | AuthService.hasPermission | 应使用枚举 | ✅ 已修复 |
| S26 | 代码风格不一致 | 🟢 低 | BaafooAgent.premain | 匿名类应用 lambda | ✅ 已修复 |
