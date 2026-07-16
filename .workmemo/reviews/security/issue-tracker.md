# 安全审查问题闭环追踪表

> **创建日期**: 2026-07-16
> **关联文档**: [code-review-report-20260710.md](./code-review-report-20260710.md)（审查）→ [security-fixes_2026-07-10.md](./security-fixes_2026-07-10.md)（修复）→ [baafoo_architecture_improvement_todo.md](./baafoo_architecture_improvement_todo.md)（架构改进）
> **目的**: 串联三份独立文档，提供"问题→修复→状态"的单一事实来源

---

## 修复状态汇总

| 严重度 | 总数 | 已修复 | 不修复 | 待修复 | 备注 |
|--------|------|--------|--------|--------|------|
| Critical | 1 | 1 | 0 | 0 | C1 MCP 硬编码 admin 已修复 |
| High | 11 | 7 | 4 | 0 | H4/H5/H7/H9 经分析不修复 |
| Medium | 14 | 10 | 2 | 2 | M20/M22 不修复；M1/M14 待修复 |
| Low | 20+ | 0 | 0 | 20+ | cosmetic/UX，暂不处理 |

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
| M22-low | 输入未 trim | ❌ 不修复 | 数据质量问题，非安全风险 |

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
