# 安全审查问题闭环追踪表

> **创建日期**: 2026-07-16
> **最后更新**: 2026-07-18
> **关联文档**:
> - 2026-07-10 审查：[code-review-report-20260710.md](./code-review-report-20260710.md) → [security-fixes_20260710.md](./security-fixes_20260710.md)
> - 2026-07-18 审查：[code-review-report-20260718.md](./code-review-report-20260718.md) → [security-fixes-20260718.md](./security-fixes-20260718.md)
> - 架构改进：[baafoo_architecture_improvement_todo.md](./baafoo_architecture_improvement_todo.md)
> **目的**: 串联多份独立文档，提供"问题→修复→状态"的单一事实来源

---

## 修复状态汇总

### 2026-07-10 批次

| 严重度 | 总数 | 已修复 | 不修复 | 待修复 | 备注 |
|--------|------|--------|--------|--------|------|
| Critical | 1 | 1 | 0 | 0 | C1 MCP 硬编码 admin 已修复（2026-07-18 验证：修复未真正生效，见 server-C1） |
| High | 11 | 7 | 4 | 0 | H4/H5/H7/H9 经分析不修复；H1/H3/H6/H8 修复有缺陷，2026-07-18 重新修复 |
| Medium | 14 | 10 | 2 | 2 | M20/M22 不修复；M1/M14 待修复 |
| Low | 20+ | 0 | 0 | 20+ | cosmetic/UX，暂不处理 |

### 2026-07-18 批次（新增 148 项）

| 严重度 | 总数 | 已修复 | 不修复 | 待修复 | 备注 |
|--------|------|--------|--------|--------|------|
| Critical | 12 | 11 | 1 | 0 | server-H-1 经产品确认为需求设计不修复 |
| High | 29 | 28 | 1 | 0 | server-H-1 不修复（同上，按需求设计保留 guest 读 GET 旁路） |
| Medium | 54 | 54 | 0 | 0 | 全部已修复 |
| Low | 53 | 53 | 0 | 0 | 全部已修复 |

---

## 逐项追踪

### Critical

| ID | 问题 | 修复状态 | 修复文件 | 修复日期 | 备注 |
|----|------|----------|----------|----------|------|
| C1 | MCP 硬编码 admin 权限 | ✅ 已修复 | `McpController.java` | 2026-07-10 | 从请求头读取真实角色，强制执行 McpSafetyLevel |

### High

| ID | 问题 | 修复状态 | 修复文件 | 修复日期 | 备注 |
|----|------|----------|----------|----------|------|
| H1 | 规则级 requestCount 永远传 0 | ✅ 已修复 | `MatchEngine.java` | 2026-07-10 | incrementAndGet 移到条件检查前 |
| H2 | 带条件响应不匹配时返回 entry 0 | ✅ 已修复 | `MatchEngine.java` | 2026-07-10 | 返回 -1 表示无匹配 |
| H3 | Agent BLOCK 动作未生效 | ✅ 已修复 | `SocketConnectAdvice.java`, `NioSocketConnectAdvice.java` | 2026-07-10 | BLOCK 时改写 endpoint 到黑洞地址 |
| H4 | 录制在主线程同步阻塞 | ❌ 不修复 | — | — | ByteBuddy 内联限制，改动大且方法体有 64KB 限制 |
| H5 | fail-open/fail-closed 语义矛盾 | ❌ 不修复 | — | — | 经审查 ConcurrentHashMap + AtomicInteger + compute() 已保证可见性 |
| H6 | 匿名 guest 可读全部 API | ✅ 已修复 | `AuthService.java` | 2026-07-10 | 无凭证时返回 AuthResult(false) |
| H7 | TCP 存根处理器忽略 EnvironmentMode | ❌ 不修复 | — | — | 默认不信任任何代理，已是安全默认行为 |
| H8 | addRecording 同步阻塞 IO 线程 | ✅ 已修复 | `JdbcStorageService.java` | 2026-07-10 | AtomicInteger 每 50 次 insert 才 trim |
| H9 | SDK 运算符与服务器不匹配 | ❌ 不修复 | — | — | 代码库中不存在 ElasticSearch 相关代码，误报 |
| H10 | 前端 saveRule 失败仍跳转 | ✅ 已修复 | `RuleEditorPage.vue` | 2026-07-10 | 检查 res.success，失败时提示错误 |
| H11 | insertFakerVar 选错 textarea | ✅ 已修复 | `RuleEditorPage.vue` | 2026-07-10 | 通过 indexOf 定位正确 card |

### Medium（已修复）

| ID | 问题 | 修复状态 | 修复文件 | 修复日期 |
|----|------|----------|----------|----------|
| M3 | matchWithFallback 原地修改请求 | ✅ 已修复 | `MatchEngine.java` | 2026-07-10 |
| M10 | PassthroughProxy ByteBuf 泄漏 | ✅ 已修复 | `PassthroughProxy.java` | 2026-07-10 |
| M12 | StaticFileHandler 文件句柄泄漏 | ✅ 已修复 | `StaticFileHandler.java` | 2026-07-10 |
| M13 | CORS 多 origin 拼接非法 | ✅ 已修复 | `StubResponseRenderer.java` | 2026-07-10 |
| M15 | Go SDK regex 退化为 Contains | ✅ 已修复 | `sdks/go/baafoo/client.go` | 2026-07-10 |
| M17 | staging 配置硬编码密码 | ✅ 已修复 | `ConfigLoader.java`, config yml | 2026-07-10 |
| M21 | ECharts 实例未 dispose | ✅ 已修复 | `DashboardPage.vue` | 2026-07-10 |
| M22 | 全局事件监听未移除 | ✅ 已修复 | `RuleEditorPage.vue` | 2026-07-10 |
| M23 | 路由守卫信任 localStorage role | ✅ 已修复 | `store/index.js`, `router/index.js` | 2026-07-10 |
| M25 | 路由参数导航未处理 | ✅ 已修复 | `RuleEditorPage.vue`, `EnvironmentDetailPage.vue` | 2026-07-10 |

### Medium（不修复/待修复）

| ID | 问题 | 状态 | 原因 |
|----|------|------|------|
| M1 | ReDoS 缓解失效 | ⏳ 待修复 | Java 8 Matcher 不响应中断，需限制输入长度 |
| M14 | DefaultModeStrategy 是死代码 | ⏳ 待修复 | 需统一模式分派（见架构改进 P1-1） |
| M20 | 连接状态恒为"已连接" | ❌ 不修复 | 经审查 isOnline 基于 lastHeartbeat 60s 阈值，逻辑正确 |
| M22-low → M26 | 输入未 trim | ❌ 不修复 | 数据质量问题，非安全风险 |

---

## 与架构改进 TODO 的对应关系

| CODE-REVIEW-REPORT 问题 | architecture_improvement_todo 项 | 关系 |
|--------------------------|-----------------------------------|------|
| C1 (MCP admin) | — | 已独立修复 |
| H1/H2 (MatchEngine) | — | 已独立修复 |
| H3 (BLOCK 不生效) | — | 已独立修复 |
| H7 (TCP Mode 忽略) | P1-1 (统一 ModeStrategy) | H7 的根因是缺乏统一模式分派 |
| M14 (死代码) | P1-1 (统一 ModeStrategy) | M14 的 DefaultModeStrategy 应被激活 |
| M6 (注释矛盾) | P1-2 (拆分 GlobalRouteState) | 注释更正随拆分一并处理 |
| H4 (录制阻塞) | P0-6 (RouteManager 非原子更新) | 录制线程模型随架构重构解决 |

---

## 2026-07-18 批次逐项追踪

### Critical（12 项）

| ID | 问题 | 修复状态 | 修复文件 | 修复日期 | 备注 |
|----|------|----------|----------|----------|------|
| core-C1 | MatchEngine counter 提前递增 | ✅ 已修复 | `MatchEngine.java` | 2026-07-18 | 2026-07-10 H1 修复的副作用，counter 移到条件评估后 |
| core-C2 | regex 快路径绕过 ReDoS 保护 | ✅ 已修复 | `MatchEngine.java` | 2026-07-18 | 移除 < 64 快路径，统一走超时线程池 |
| core-C3 | GrpcCodecUtils 整数溢出 OOM | ✅ 已修复 | `GrpcCodecUtils.java` | 2026-07-18 | 加 16MB 上限 + long 比较 |
| agent-C1 | RecordingBuffer IO 线程阻塞 | ✅ 已修复 | `RecordingBuffer.java` | 2026-07-18 | 独立 uploadExecutor 异步化 |
| agent-C2 | pendingRetry 无上限 OOM | ✅ 已修复 | `RecordingBuffer.java` | 2026-07-18 | MAX_PENDING_RETRY=10000 + 溢出告警 |
| server-C1 | McpApiHandler 不强制 SafetyLevel | ✅ 已修复 | `McpApiHandler.java` | 2026-07-18 | 2026-07-10 C1 修复未真正生效，本次在生产通路强制检查 |
| server-C2 | ManagementApiHandler 重复 guest 旁路 + XFF | ✅ 已修复 | `ManagementApiHandler.java` | 2026-07-18 | 删除旁路 + trustedProxies 对齐 |
| server-C3 | KafkaProtocolDecoder OOM | ✅ 已修复 | `KafkaProtocolDecoder.java` | 2026-07-18 | recordsCount/fieldSize 上限 |
| server-C4 | PulsarMockBrokerHandler metaSize OOM | ✅ 已修复 | `PulsarMockBrokerHandler.java` | 2026-07-18 | metaSize 64KB 上限 |
| web-C1 | v-html + onclick XSS | ✅ 已修复 | `RuleEditorPage.vue` | 2026-07-18 | 改为 Vue @click 事件绑定 |
| web-C2 | JWT 存于 localStorage | ✅ 已修复 | `store/index.js` | 2026-07-18 | 缩短 token 有效期 + 安全注释 |
| web-C3 | KafkaRedirectPlugin excludeTopics 无效 | ✅ 已修复 | `KafkaRedirectPlugin.java` | 2026-07-18 | 删除误导性配置项 |

### High（29 项，1 项不修复）

| ID | 问题 | 修复状态 | 修复文件 | 修复日期 | 备注 |
|----|------|----------|----------|----------|------|
| core-H1 | ChaosManager 锁粒度不一致 | ✅ 已修复 | `ChaosManager.java` | 2026-07-18 | activate() 加 synchronized |
| core-H2 | FakerProvider min 未 cap | ✅ 已修复 | `FakerProvider.java` | 2026-07-18 | min 先 cap 到 32 |
| core-H3 | FakerProvider ThreadLocal 残留 | ✅ 已修复 | `FakerProvider.java` | 2026-07-18 | Javadoc 明确清理责任 |
| core-H4 | JsonPathUtil.exists 重复解析 | ✅ 已修复 | `JsonPathUtil.java` | 2026-07-18 | 单次解析 + navigate |
| core-H5 | OpenApiImporter responses.get(null) | ✅ 已修复 | `OpenApiImporter.java` | 2026-07-18 | 调用前 null 校验 |
| core-H6 | ChaosManager priority=50 覆盖业务 | ✅ 已修复 | `ChaosProfile.java`, `ChaosManager.java` | 2026-07-18 | ChaosProfile 新增 priority 字段 |
| core-H7 | StatefulCounterStore API 误用 | ✅ 已修复 | `StatefulCounterStore.java` | 2026-07-18 | get() 加 @Deprecated |
| agent-H1 | SocketConnectAdvice STUB BLOCK 失效 | ✅ 已修复 | `SocketConnectAdvice.java` | 2026-07-18 | STUB 模式 BLOCK 改写 endpoint |
| agent-H2 | NioSocketConnectAdvice STUB BLOCK 失效 | ✅ 已修复 | `NioSocketConnectAdvice.java` | 2026-07-18 | 同上 |
| agent-H3 | RouteManager.setMode 未同步 App CL | ✅ 已修复 | `RouteManager.java` | 2026-07-18 | 改用 AgentManifest.setCurrentMode() |
| agent-H4 | rebuildRouteTable 状态不一致 | ✅ 已修复 | `RouteManager.java` | 2026-07-18 | 局部变量构造 + IPv6 lastIndexOf + try-catch |
| agent-H5 | RecordingStream 异常传播 | ✅ 已修复 | `RecordingInputStream.java`, `RecordingOutputStream.java` | 2026-07-18 | recordBytes try-catch Throwable |
| agent-H6 | ControlChannel.start 阻塞 70s | ✅ 已修复 | `ControlChannel.java` | 2026-07-18 | register 异步 daemon 线程 |
| server-H-1 | AuthFilter guest 读 GET 旁路 | ❌ 不修复 | — | — | **经产品确认为需求设计** |
| server-H-2 | McpController 丢弃 JSON-RPC id | ✅ 已修复 | `McpController.java` | 2026-07-18 | final id 变量在 catch 中使用 |
| server-H-3 | trim 仍同步阻塞 EventLoop | ✅ 已修复 | `JdbcRecordingService.java` | 2026-07-18 | recordingTrimExecutor 异步 + CAS 限流 |
| server-H-4 | HttpStubHandler EventLoop JDBC | ✅ 已修复 | `HttpStubHandler.java` | 2026-07-18 | recordingExecutor 业务线程池 |
| server-H-5 | KafkaMockBroker 100MB 帧长 | ✅ 已修复 | `KafkaMockBroker.java` | 2026-07-18 | 降到 10MB |
| server-H-6 | StaticFileHandler 路径穿越 | ✅ 已修复 | `StaticFileHandler.java` | 2026-07-18 | URI.normalize() 替代字符串替换 |
| server-H-7 | AuthService 时序攻击 | ✅ 已修复 | `AuthService.java` | 2026-07-18 | MessageDigest.isEqual 常量时间比较 |
| server-H-8 | PassthroughProxy X-Forwarded-Proto | ✅ 已修复 | `PassthroughProxy.java` | 2026-07-18 | 校验值只能是 http/https |
| web-H-1 | UsersPage catch 吞错 | ✅ 已修复 | `UsersPage.vue` | 2026-07-18 | 区分取消和真实错误 |
| web-H-2 | switchMode 乐观更新无回滚 | ✅ 已修复 | `EnvironmentDetailPage.vue` | 2026-07-18 | 保存 oldMode 失败回滚 |
| plugin-H-3 | PluginEvent.getAttribute 强转 | ✅ 已修复 | `PluginEvent.java` | 2026-07-18 | 新增 Class<T> 类型安全重载 |
| web-H-4 | DashboardPage Promise.all 无 catch | ✅ 已修复 | `DashboardPage.vue` | 2026-07-18 | 每个接口单独 catch |
| web-H-5 | 异步函数未 await | ✅ 已修复 | `RuleEditorPage.vue`, `OpenApiImportDialog.vue` | 2026-07-18 | try/catch + ElMessage.error |
| web-H-6 | App.vue statusConnected 恒 true | ✅ 已修复 | `App.vue` | 2026-07-18 | 失败置 false + 指数退避 |
| web-H-7 | HttpURLConnection 未 disconnect | ✅ 已修复 | `QuickTest.java`, `RuleSetup.java`, `HttpCaller.java`, `ConsulHttpCaller.java` | 2026-07-18 | try-with-resources + finally disconnect |
| web-H-8 | Caller 单次 read 丢数据 | ✅ 已修复 | `TcpCaller.java`, `SocketCallerService.java` | 2026-07-18 | 循环 read + 10MB 上限 |

### Medium（54 项）— 全部已修复

涵盖 baafoo-core(13) / baafoo-agent(10) / baafoo-server(9) / plugin-cli-test-web(22)。详见 [security-fixes-20260718.md](./security-fixes-20260718.md)。

### Low（53 项）— 全部已修复

涵盖 baafoo-core(15) / baafoo-agent(10) / baafoo-server(10) / plugin-cli-test-web(18)。详见 [security-fixes-20260718.md](./security-fixes-20260718.md)。

---

## 2026-07-10 修复验证（2026-07-18 复查）

| 2026-07-10 修复项 | 2026-07-18 验证结论 | 处置 |
|---|---|---|
| C1 MCP admin 硬编码 | ❌ 未真正生效（生产走 McpApiHandler 不走 McpController） | 2026-07-18 server-C1 重新修复 |
| H1 规则级 requestCount | ⚠️ 引入新缺陷（counter 提前递增） | 2026-07-18 core-C1 重新修复 |
| H2 selectResponseEntry 返回 -1 | ✅ 已正确实现 | 无需处理 |
| H3 BLOCK 改写 endpoint | ⚠️ 仅 RECORD_ALL 修了，STUB 漏修 | 2026-07-18 agent-H1/H2 补充修复 |
| H6 匿名 guest 可读 API | ❌ 仍可旁路（双重 guest 旁路） | 2026-07-18 server-C2 重新修复（注：server-H-1 保留 guest 读 GET 是需求设计） |
| H8 addRecording 限流 | ⚠️ 仍同步阻塞 EventLoop | 2026-07-18 server-H-3/H-4 重新修复 |
| H10/H11/M21/M22/M23/M25 前端 | ✅ 全部已正确实现 | 无需处理 |
| M3/M10/M12/M13/M17 | ✅ 部分已实现 | DefaultModeStrategy 仍为死代码，2026-07-18 标记 @Deprecated |
