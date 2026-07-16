# 安全审查修复 — 2026-07-10

## 目标

修复安全审查报告中确认的问题（2 Critical, 5 High）。

## 修复清单

### C1: MCP 硬编码 admin 权限 [Critical]

**问题**: `McpController.handleToolsCall` 硬编码 `"admin"` 角色，任何调用 MCP API 的人都能以 admin 身份执行所有工具。同时 `McpSafetyLevel` 定义了三级安全级别但从未被强制执行。

**修复**:
- 从请求头 `X-Baafoo-Auth-Role` / `X-Baafoo-Auth-User` 读取 AuthFilter 设置的真实角色（fallback `guest`）
- 对 `AUDIT_REQUIRED` 级别的工具强制要求 admin 角色
- `McpToolContext` 现在携带真实角色，`requirePermission()` / `requireAdmin()` 正常工作

**文件**: `McpController.java`

### H6: 匿名用户可读全部 API [High]

**问题**: `AuthService.authenticate()` 在无凭证时返回 `AuthResult(true, "guest", ...)`，允许匿名读取所有 API 端点（包括用户列表）。

**修复**: 无凭证时返回 `AuthResult(false, null, "Authentication required")`，拒绝匿名访问。

**文件**: `AuthService.java`, `AuthServiceTest.java`

### H1: 规则级 requestCount 条件永远传 0 [High]

**问题**: `MatchEngine.matchConditions()` 在 `incrementAndGet()` 之前检查规则级条件，传入 `requestCount=0`。导致 `lessThan N` 永远 true，`greaterThan N` 永远 false。

**修复**: 将 `counterStore.incrementAndGet()` 移到规则级条件检查之前，规则级和响应级条件使用同一个递增后的 count。

**文件**: `MatchEngine.java`

### H2: 全部带条件响应不匹配时错误返回 entry 0 [High]

**问题**: `selectResponseEntry()` 在所有响应都带条件且无匹配时返回 `0`（第一个响应），导致用户设置的条件被忽略。

**修复**: 返回 `-1` 表示无匹配，`matchConditions()` 检测到 `-1` 后返回 `null`，让匹配引擎继续尝试下一条规则。

**文件**: `MatchEngine.java`, `MatchEngineTest.java`

### H10: 前端 saveRule 不检查结果 [High]

**问题**: `RuleEditorPage.vue` 的 `saveRule()` 调用 `createRule`/`updateRule` 后不检查 `res.success`，直接 `router.back()`，导致保存失败时用户无感知。

**修复**: 检查 `res.success`，失败时显示错误消息而非跳转。

**文件**: `RuleEditorPage.vue`

### H11: insertFakerVar 选错 textarea [High]

**问题**: `insertFakerVar()` 使用 `document.querySelector('.response-card textarea')` 恒返回第一个 response card 的 textarea，导致变量插入到错误的响应体中。

**修复**: 通过 `form.responses.indexOf(resp)` 找到索引，再用 `querySelectorAll` 定位到正确的 card。

**文件**: `RuleEditorPage.vue`

### H3: SocketConnectAdvice BLOCK 不生效 [High]

**问题**: `SocketConnectAdvice` 和 `NioSocketConnectAdvice` 在 plugin 返回 BLOCK 时只 `return`，但 `@Advice.OnMethodEnter` 的 return 只是退出 advice 方法，原始 `Socket.connect()` 仍会执行。

**修复**: BLOCK 时将 endpoint 改为 `new InetSocketAddress("0.0.0.0", 1)`，使 `Socket.connect()` 抛出 `ConnectException`。

**文件**: `SocketConnectAdvice.java`, `NioSocketConnectAdvice.java`

## 验证

- `mvn compile` 通过
- `mvn test` 437 测试全部通过，0 失败
- `vite build` 前端构建成功

### H8: addRecording 同步阻塞 IO 线程 [High]

**问题**: `addRecording` 每次调用都执行 `trimRecordings(1000)`，在 Netty IO 线程同步阻塞执行 DELETE 查询。高吞吐场景下会阻塞事件循环。

**修复**: 用 `AtomicInteger` 计数器，每 50 次 insert 才执行一次 trim，大幅减少 IO 线程的阻塞频率。

**文件**: `JdbcStorageService.java`

## 未修复

以下问题经分析后决定不修复：
- H4: NioSocketConnectAdvice 重复代码 — ByteBuddy 内联限制，共享逻辑需提取到 GlobalRouteState（Bootstrap CL），改动大且方法体有 64KB 限制，风险/收益比不合理
- H5: counterStore 并发可见性 — 经审查，`ConcurrentHashMap` + `AtomicInteger` + `compute()` 已经保证了可见性和原子性，实际无问题
- H7: AuthFilter X-Forwarded-For 信任链 — 默认不信任任何代理（`isTrustedProxy` 返回 false），已是安全默认行为
- H9: ElasticSearch scroll 未关闭 — 代码库中不存在 ElasticSearch 相关代码，可能已移除或为误报

## Medium 修复

### M3: matchWithFallback 原地修改请求 [Medium]

**问题**: `MatchEngine.matchWithFallback()` 直接在传入的 `MatchRequest` 上修改字段，可能影响调用方。

**修复**: 改用副本进行匹配。

**文件**: `MatchEngine.java`

### M10: PassthroughProxy ByteBuf 泄漏 [Medium]

**问题**: 连接失败分支未 release request ByteBuf。

**修复**: 在连接失败分支添加 `ReferenceCountUtil.release()`。

**文件**: `PassthroughProxy.java`

### M12: StaticFileHandler RandomAccessFile 泄漏 [Medium]

**问题**: 文件写入完成后未关闭 `RandomAccessFile`。

**修复**: 添加 write-complete listener 关闭 `raf`。

**文件**: `StaticFileHandler.java`

### M13: CORS 多 origin 拼接非法 [Medium]

**问题**: 多个 origin 直接逗号拼接，不符合 CORS 规范。

**修复**: 改为回显请求 `Origin` 头。

**文件**: `StubResponseRenderer.java`

### M15: Go SDK regex 退化为 strings.Contains [Medium]

**问题**: Go SDK 中路径匹配用 `strings.Contains` 代替正则。

**修复**: 改用 `regexp.Compile`。

**文件**: `sdks/go/baafoo/client.go`

### M17: staging 配置硬编码密码 [Medium]

**问题**: `.deploy/mono/config/baafoo-server-postgresql.yml` 中硬编码数据库密码。

**修复**: 改为环境变量引用，`ConfigLoader` 加 `resolveEnvVars` 替换 `${ENV_VAR:default}`。

**文件**: `ConfigLoader.java`, `.deploy/mono/config/baafoo-server-postgresql.yml`

### M21: ECharts 实例未 dispose [Medium]

**问题**: `DashboardPage.vue` 每次 `renderRulesChart`/`renderTrendChart` 都 `echarts.init()` 新实例，不 dispose 旧实例，反复进出页面泄漏内存。

**修复**: 保存 chart 实例引用，re-init 前 dispose 旧的，`onUnmounted` 时 dispose。

**文件**: `web/src/views/DashboardPage.vue`

### M22: 全局事件监听未移除 [Medium]

**问题**: `RuleEditorPage.vue` 注册 `toggle-faker-ref` 事件监听后从不移除，离开页面后监听持续修改已死组件。

**修复**: 提取具名 handler，`onBeforeUnmount` 时 `removeEventListener`。

**文件**: `web/src/views/RuleEditorPage.vue`

### M23: 路由守卫信任 localStorage role [Medium]

**问题**: 前端 `useAuthStore` 初始化时从 `localStorage.getItem('baafoo_role')` 读取角色，用户可直接篡改 localStorage 绕过 admin 页面守卫。

**修复**: `role` 初始值改为 `null`，不再持久化到 localStorage；路由守卫在 token 存在但 role 未获取时先调 `fetchMe()` 从服务端验证。

**文件**: `web/src/store/index.js`, `web/src/router/index.js`

### M25: 路由参数导航未处理 [Medium]

**问题**: `RuleEditorPage` 和 `EnvironmentDetailPage` 仅在 `onMounted` 读 `route.params.id`，同路由换 `:id` 时 `onMounted` 不重跑，数据陈旧。

**修复**: 抽取 load 函数，添加 `watch(() => route.params.id, ...)` 在参数变化时重新加载。

**文件**: `web/src/views/RuleEditorPage.vue`, `web/src/views/EnvironmentDetailPage.vue`

## 未修复的 Medium/Low

- M20: 连接状态恒为“已连接” — 经审查 `isOnline` 基于 `lastHeartbeat` 60秒阈值判断，逻辑正确，非真实问题
- M22 输入未 trim: 数据质量问题，影响范围广，非安全风险，暂不处理
- 其余 Low 级问题： cosmetic / UX，暂不处理
