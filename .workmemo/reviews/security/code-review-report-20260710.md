# Baafoo 代码审查报告

> 审查范围：全部源码（Java 后端 5 大模块、Python/Go/Node SDK、Go 代理、Docker/部署配置、Vue 3 前端）
> 审查方式：分模块并行静态审查（逻辑正确性、并发安全、资源泄漏、安全、错误处理、代码坏味道）
> 日期：2026-07-10
> 说明：本报告标注的"已验证"指已直接读源码确认；其余为审查推断，建议修复前二次确认。

---

## 一、总体结论

整体工程质量中等偏上：核心并发结构（StatefulCounterStore、ChaosManager、字节桥接）设计合理，SQL 全部使用 `#{}` 参数化（无注入），Go SDK 资源处理规范。但存在 **1 个严重授权绕过**、**多处 High 级逻辑/安全缺陷**，以及大量重复代码与资源泄漏。

| 严重度 | 数量 | 模块分布 |
|--------|------|----------|
| 🔴 Critical | 1 | server（MCP 授权绕过） |
| 🟠 High | 11 | core(2) / agent(3) / server(4) / sdk(2) / frontend(2)（其中 3 项已验证） |
| 🟡 Medium | 14 | 各模块 |
| 🟢 Low | 20+ | 各模块 |

**最该优先修的 5 件事**：
1. MCP 工具以硬编码 `admin` 执行，且 `McpSafetyLevel` 从未被强制执行 → 任意通过鉴权的调用者都能做管理员级变更（**已验证**）。
2. 未携带凭证的请求被当作 `guest` 且 `READ` 对所有人开放 → 匿名可读全部 API（含用户列表）（**已验证**）。
3. Agent 的 `BLOCK` 动作未真正阻断，只是 `logInfo + return`，连接照常打到真实后端（**已验证**）。
4. SDK 运算符与服务器不匹配（`startsWith/endsWith/regex` vs `equals/contains/prefix/suffix/exists`）→ 由服务器生成的规则在 SDK 侧匹配退化为精确相等，stub 静默失效。
5. TCP 存根处理器忽略 EnvironmentMode，且录制在 Netty IO 线程同步阻塞 DB → 模式语义不一致 + 吞吐崩塌。

---

## 二、🔴 Critical

### C1. MCP 工具以硬编码 `admin` 角色执行，安全级别形同虚设
- **文件**：`baafoo-server/.../mcp/McpController.java:152`（已验证）
- **问题**：
  ```java
  McpToolContext ctx = new McpToolContext(storage, authService, "admin", "mcp-user");
  ```
  角色被写死为 `admin`，注释却写 "use guest role as fallback"。同时 grep 确认 `McpSafetyLevel`（READ_ONLY/CONTROLLED_WRITE/AUDIT_REQUIRED）在 `McpController`/`McpToolContext` 中从未被读取——只有枚举定义和每个工具的 `getSafetyLevel()`。
- **影响**：任何通过 `AuthFilter`（developer+）校验的调用者，都能对 `AUDIT_REQUIRED` 的破坏性工具（规则删除/重置、环境/场景重置）执行无审计的管理员级变更。若未来 `AuthFilter` 被放宽，这里是潜在的完全绕过。
- **修复**：
  - 把 `AuthFilter` 已设置的真实角色（`X-Baafoo-Auth-Role`）传入 `McpToolContext`；
  - 在 `handleToolsCall` 中强制执行 `tool.getSafetyLevel()`（对 `AUDIT_REQUIRED` 拒绝或要求显式确认）。

---

## 三、🟠 High

### H1. 规则级 `requestCount` 条件永远以 0 求值（逻辑错误）
- **文件**：`baafoo-core/.../util/MatchEngine.java:255-266`
- **问题**：`matchConditions` 对规则级条件循环时传入 `requestCount=0`，`incrementAndGet` 在循环之后才执行。因此：
  - `lessThan N (N>0)` → `0 < N` 永远 true，条件形同虚设；
  - `greaterThan N` / `equals N (N>0)` → 永远 false。
- **影响**：用户在规则级用 `requestCount` 做状态化分流时行为完全相反。
- **修复**：规则级 `requestCount` 也使用递增后的 `count`；或在 `MatchCondition` 工厂与文档中明确禁止规则级使用，只允许放在 ResponseEntry 级（那里用递增后的值，已正确）。

### H2. 全部带条件响应均不匹配时仍返回 entry 0（返回未满足条件项）
- **文件**：`baafoo-core/.../util/MatchEngine.java:301-315`
- **问题**：`selectResponseEntry` 遍历响应项，遇到无条件项即返回，遇匹配项返回；若所有响应都带条件且无一匹配，循环结束落入 `return 0`，返回一个条件未被满足的项。代码未保证 entry 0 是无条件默认项。
- **影响**：错误的响应被返回给用户，且难以排查。
- **修复**：无默认且无命中时返回 `-1`（视为该规则不匹配，继续下一条）；或强制要求每条规则存在默认项。

### H3. Agent `BLOCK` 动作未生效（隔离被绕过）
- **文件**：`baafoo-agent/.../advice/SocketConnectAdvice.java:132-135`、`NioSocketConnectAdvice.java:131-133`（已验证）
- **问题**：plugin 返回 `action==2`（BLOCK）时仅 `logInfo` 后 `return`，未抛异常也未改写 endpoint，连接照常打到真实后端。注释写 "blocked" 但实际是 passthrough。`PLUGIN_CONSULT_FN_EXT` 的 BLOCK 语义完全未实现。
- **影响**：stub 场景下仍打到真实服务，违背隔离前提。
- **修复**：BLOCK 应在 `onConnect` 抛出 `IOException`（或改写 endpoint 到黑洞地址）真正阻断。

### H4. 录制在主线程同步阻塞
- **文件**：`baafoo-agent/.../RecordingBuffer.java:70-77`、`ControlChannel.uploadRecordings:277-301`
- **问题**：`add()` 缓冲满 100 条时同步 `flush()`，走 `HttpURLConnection`（connect/read 各 5s）。`RecordingInputStream/OutputStream` 在 socket IO 路径调用 `add()`，缓冲满时会在**应用 IO 线程**阻塞最多 ~5s+ 并可能触发重试。
- **影响**：record / stub-and-record 模式下给真实流量注入显著延迟与可用性风险。
- **修复**：满缓冲仅 `offer` 到队列，由调度线程异步上传；`add()` 绝不阻塞 IO 线程。

### H5. fail-open / fail-closed 语义自相矛盾
- **文件**：`baafoo-agent/.../advice/SocketConnectAdvice.java:246-248`、`KafkaProducerAdvice.java:120-123` 等
- **问题**：`catch` 块注释 "Fail-closed: let original proceed"，但"继续执行原构造/连接"实为 **fail-open**（打到真实后端）。
- **影响**：任何 advice 异常都会绕过 stub/重定向，违反影子/隔离意图。
- **修复**：统一术语；对需隔离的模式，异常时应显式阻断或告警升级，并补测试覆盖。

### H6. 匿名 `guest` 可读全部 API（含用户）
- **文件**：`baafoo-server/.../auth/AuthService.java:124`（已验证）、`hasPermission:302`
- **问题**：`authEnabled=true` 且无凭证时，`authenticate` 返回 `AuthResult(true,"guest",…)`；`hasPermission` 对任意角色都允许 `READ`。`AuthFilter` 因此允许匿名 `GET` 所有 `/__baafoo__/api/*`（规则、录制、环境、**用户**）。
- **影响**：敏感信息匿名泄露。
- **修复**：默认拒绝未认证访问；仅当显式配置时才允许匿名。

### H7. TCP 存根处理器忽略 EnvironmentMode
- **文件**：`baafoo-server/.../handler/TcpStubHandler.java:135-168`
- **问题**：多轮/pattern/prefixHex/offset 匹配分支在返回 stub 前**不检查 `EnvironmentMode`**；`PASSTHROUGH`/`RECORD` 模式下命中规则仍返回 stub（不转发，RECORD 还会录制 stub 而非真实响应）。只有通用 `MatchEngine` 兜底分支检查了模式。
- **影响**：TCP 模式语义与 HTTP/gRPC 不一致。
- **修复**：所有命中路径统一经过一个 `ModeOutcome` 决策。

### H8. Netty IO 线程同步做阻塞 DB + 每次插入 DELETE
- **文件**：`baafoo-server/.../storage/JdbcStorageService.java:690`（被 `channelRead0` 同步调用）
- **问题**：`addRecording` 每次插入都跑 `rcm.trimRecordings(1000)`（DELETE），且在 `HttpStubHandler`/`TcpStubHandler`/`GrpcStubHandler` 的 RECORD 路径中被 `channelRead0` 同步调用。
- **影响**：事件循环线程阻塞在 JDBC；RECORD_ALL 下是每请求 DELETE+INSERT，吞吐严重下降、延迟升高。
- **修复**：录制卸载到独立 executor/队列；`trimRecordings` 改为周期性执行。

### H9. SDK 运算符与服务器不匹配，stub 静默失效
- **文件**：`sdks/python/.../intercept.py:137-150`、`sdks/go/.../client.go:254-271`、`sdks/nodejs/.../intercept.js:206-215`
- **问题**：SDK 只识别 `equals/contains/prefix/suffix/exists`；服务器规则生成器与 `AGENTS.md` 使用 `startsWith/endsWith/regex`。未识别的运算符落入 `default` 分支退化为 `actual == expected`（精确相等）。staging 规则与 `testing/` 全部用 `"operator":"startsWith"`，SDK 侧匹配几乎恒为 false → 全部透传。
- **影响**：由服务器生成的规则在 SDK 侧匹配失败，stub 静默不生效。
- **修复**：在 SDK 增加 `startsWith/endsWith` 与真正的 `regex` 分支；或统一服务器→SDK 的运算符命名。

### H10. 前端 `saveRule()` 失败时仍跳转，静默丢失数据
- **文件**：`web/src/views/RuleEditorPage.vue:712-772`
- **问题**：`saveRule` 调用 `createRule/updateRule` 后从不检查 `res.success`，无条件 `router.back()`。任何 API 失败（校验错、500、冲突）都直接退回列表、无错误提示，用户输入全部丢失。
- **修复**：检查 `res.success`；失败时 `ElMessage.error(res.message)` 并 `return`（不跳转）。

### H11. 前端 `insertFakerVar()` 选错 textarea
- **文件**：`web/src/views/RuleEditorPage.vue:595-606`
- **问题**：`document.querySelector('.response-card textarea')` 恒返回 DOM 中**第一个**响应卡的 textarea。多响应时点击第 3 个响应的 faker 变量，会在第 1 个 textarea 的 `selectionStart/End` 处插入到 `resp.body`，位置错误。
- **修复**：通过事件/当前响应定位 textarea（传 event target 或用 `ref`/`data-*`），在该元素的 `selectionStart` 处插入。

---

## 四、🟡 Medium

### 核心 (core)
- **M1 ReDoS 缓解失效**：`MatchEngine.java:798-799` 靠 `future.cancel(true)` 终止正则，但 Java 8 `Matcher` 不响应中断，后台线程跑完灾难性回溯；固定 `REGEX_POOL_SIZE` 线程会被全部拖死。建议限制输入长度上限，或注释如实说明该限制。
- **M2 VarintCodec 不一致**：`readVarint64` 截断返回 `0`，`readVarint` 返回 `-1`；32 位对超 5 字节畸形 varint 高位被静默丢弃。建议统一错误哨兵与字节上限。
- **M3 `matchWithFallback` 原地改请求**：`MatchEngine.java:144-153` `req.setPort(0)` 再还原，若 `MatchRequest` 被并发复用会产生数据竞争；与 11 参重载实现不一致。建议构造临时对象而非改 `req`。
- **M4 `NetworkUtils.resolveClientReachableHost` 吞异常**：`NetworkUtils.java:75-78` `catch(Exception)` 静默落到 fallback，隐藏 Docker 网络/NPE 问题。建议至少 `log.warn`。

### Agent
- **M5 部分上传失败导致整批重报**：`ControlChannel.uploadRecordings:279-300` + `RecordingBuffer.flush:122-157` 某批失败 `throw`，但前批已成功；`flush` 的 `catch` 把**整个 batch** 重新 `add` 到 `pendingRetry` → 已成功批次被重复上传。
- **M6 `GlobalRouteState` 注释与实现严重矛盾**：注释称 state manager 在 Bootstrap CL 上为 null、调用会抛 `NoClassDefFoundError`，但 `BaafooAgent.createBootstrapJar` 确实把 state 类打进了 bootstrap jar。维护者按注释"清理"会静默破坏全部 socket/NIO/DNS 拦截。建议更正注释。
- **M7 死代码/误导 helper**：`DnsResolutionAdvice`（未被注册，已被 `DnsResolveAdvice` 取代）、`ModeGates`、`RoutingContext`、`AgentManifest.ROUTE_TABLE`（写入但生产未读）、`GlobalRouteState.shouldIntercept/shouldRecordStream/…`（advice 直接内联 `CURRENT_MODE==2||3||4` 而非调用）。
- **M8 SocketConnect vs NioSocketConnect 大规模重复**（约 250 行近乎逐字复制），BLOCK 修复需改两处。建议抽取共享 `public static` 策略方法（注意 ByteBuddy 不内联私有方法）。
- **M9 `RecordingBuffer.add` 每次 `buffer.size()`（O(n)）** → 摊销 O(n²)；`flush()` 无同步。max=100 尚可，属代码异味。

### Server
- **M10 PassthroughProxy — SSRF + ByteBuf 泄漏**：信任客户端 `X-Forwarded-Proto` 选 HTTPS，并代理到客户端请求的任意 `host:port`（无出网白名单）→ SSRF 到内网；`FullHttpRequest` 在连接失败/读超时分支未 `release()`。建议加出网白名单 + 失败分支 `request.release()`。
- **M11 AgentResolver 可能解析错环境**：`AgentResolver.java:118-154` 网关/服务器子网/唯一环境回退可能把流量归因到错误环境（歧义仅 warning 仍选一个）。`cachedServerSubnets` 是跨实例共享的 `static` 可变字段（良性竞态但属坏味道）。
- **M12 StaticFileHandler 文件句柄泄漏 + 弱穿越防御**：`RandomAccessFile` 被 `ChunkedFile` 使用后从不关闭（Windows 下文件锁泄漏）；classpath 模式仅靠 `replace("..","")` + `contains("..")`，无规范化、无 URL 解码（`%2e%2e` 可绕过）。建议加 write-complete 监听关闭 `raf`，classpath 模式也做规范化/解码。
- **M13 CORS 头非法**：`StubResponseHandler.java:46` 多 origin 用 `", "` 拼进 `Access-Control-Allow-Origin`（浏览器拒绝，ACAO 必须是单个回显 origin）。
- **M14 `DefaultModeStrategy`/`ModeOutcome` 是死代码**，无生产 handler 使用，`HttpStubHandler`/`TcpStubHandler`/`GrpcStubHandler` 各自重实现 5 模式矩阵 → 发散风险。

### SDK / 代理 / 部署
- **M15 Go `regex` 运算符是空操作**：`client.go:264-266` 用 `strings.Contains` 实现 `regex`，与文档宣称不符。
- **M16 代理做整连接缓冲而非流式**：`proxy/proxy.go:98-157` 把每侧读满到 EOF 再解析首个请求/响应；HTTP/1.1 keep-alive 与 `chunked` 处理不可靠（`parseHTTPResponse` 只按 `\r\n\r\n` 切分，丢失分块帧）。
- **M17 提交到 git 的硬编码密钥**：`deploy/staging/baafoo-server.yml`、`docker-compose.staging.yml`、`deploy/staging/baafoo-agent-env-a.yml` 内含 `jwtSecret`/`apiKeys` 及默认库口令 `baafoo/baafoo`。属 staging 默认但应移入 env/.gitignore。
- **M18 `passthroughSslVerifyDisabled: true`** 提交在 staging server 配置中，关闭 TLS 校验。应靠 env 覆盖，而非提交 `true`。
- **M19 基础 `docker-compose.yml` 的 `server` 无 healthcheck**（仅 `postgres` 有），`restart: unless-stopped` 下运行无健康检查的服务。

### 前端
- **M20 连接状态恒为"已连接"**：`App.vue:105` `statusConnected = ref(true)` 从不重赋值；`fetchStatus` 吞掉所有错误 → 后端宕机时绿点仍亮。
- **M21 ECharts 实例卸载未释放**：`DashboardPage.vue:140,156` `echarts.init` 后无 `dispose()`，每次回 Dashboard 都新建 → 内存泄漏/僵尸 canvas。
- **M22 全局事件监听未移除**：`RuleEditorPage.vue:514-518` `addEventListener('toggle-faker-ref')` 注册后从不移除，离开页面后监听持续修改已死组件，配合 `v-html` 内联 `onclick` 是脆弱 hack。
- **M23 路由守卫信任 localStorage 角色**：`router/index.js:85-103` `requireAdmin` 依赖 `authStore.role`（来自 localStorage），用户可改 `baafoo_role=admin` 进 `/users`。应只从已校验 token/`getMe` 取角色。
- **M24 多数数据加载缺错误反馈**：`Environments/Scenes/Status/Recordings/Dashboard/EnvDetail` 加载失败仅空白页/空表，无 toast。
- **M25 路由参数导航未处理**：`RuleEditorPage`/`EnvironmentDetailPage` 仅在 `onMounted` 读 `route.params.id`，同路由换 `:id` 时 `onMounted` 不重跑 → 数据陈旧。建议 `watch(() => route.params.id, load, {immediate:true})`。

---

## 五、🟢 Low（节选，完整见各模块审查）

- core：`JsonPathUtil.extract` 无法区分"字段缺失"与"空串"；`OpenApiImporter` 规则 ID slug 可能碰撞；`PaginatedResult.totalPages` 两处各算一遍可能分叉；`matchRegex` 短输入（<64）快速路径无超时。
- agent：`RECORDING_SESSIONS` 以 `System.identityHashCode` 为键可能哈希冲突；`ControlChannel.start()` 在 premain 线程同步注册带重试可能阻塞启动；魔法常量 `CURRENT_MODE==2||3||4` 应改用 `AgentManifest.MODE_*`。
- server：`TcpStubHandler.patternCache` 每连接实例一份，LRU 不跨连接摊销；`FileStorage.addRecording` 只剪内存队列、不持久化单条录制（重启丢失）；若干 `@Deprecated` 死代码。
- sdk：三 SDK API 表面不一致；匹配逻辑复制 3 份易发散；Python/Node 广泛 `except/`.catch` 吞异常；无版本锁定镜像（`curlimages/curl:latest`）；Go `InterceptHTTP` 死代码/`RestoreHTTP` 只退一层；Python mock `read()` 忽略 `amt`；Node record 模式漏录请求体/头。
- 前端：`main.js:30-32` 死循环；`App.vue:107` 子路由不高亮菜单；`StatusPage` 硬编码版本/端口；`UsersPage` 关闭弹窗未清 `generatedApiKey`；`LogsPage` 与 `RecordingsPage` 高度重复。

---

## 六、跨模块/架构建议

1. **统一模式分派**：把 5 模式矩阵收敛到单一 `ModeOutcome` 决策（HTTP/TCP/gRPC 共用），消灭 `DefaultModeStrategy` 死代码与 `CURRENT_MODE==2||3||4` 魔法内联。
2. **统一匹配语义**：核心 `MatchEngine` 与三 SDK 的运算符集合必须对齐（建议以服务器为准，SDK 补齐 `startsWith/endsWith/regex`）。
3. **资源生命周期**：录制上传、DB 写入一律移到独立线程/队列；Netty `ByteBuf` 在所有失败分支 `release()`；前端 `onUnmounted` 释放 ECharts 与事件监听。
4. **授权模型落地**：修复 MCP 硬编码 admin、`guest` 匿名读；真正强制执行 `McpSafetyLevel`；前端角色只来自已校验 token。
5. **清理误导注释/死代码**：`GlobalRouteState`、`DnsResolutionAdvice`、`ModeGates`、`RoutingContext`、`AgentManifest.ROUTE_TABLE` 等，避免维护者"按注释改坏系统"。
6. **密钥与配置**：staging 密钥移出 git；`passthroughSslVerifyDisabled` 与匿名 guest 默认关闭；基础 compose 补 healthcheck。

---

## 七、已确认干净的区域（保持）

- **SQL 注入**：MyBatis mapper 全部 `#{}`，`ORDER BY/LIMIT` 为静态字面量，JDBC 均参数化（grep 确认无 `${}`）。
- **Go SDK**：`atomic.Value` 存 rules/mode，`defer resp.Body.Close()` 与 context cancel 规范，无 goroutine 泄漏、无库内 panic。
- **核心并发**：`StatefulCounterStore`（`ConcurrentHashMap` + 原子操作）、`ChaosManager`（`ConcurrentHashMap` + 原子 add/remove）、`FakerProvider`（`ThreadLocal<Random>` 线程隔离）均正确。
- **配置加载**：`ConfigLoader` 显式 `disable(USE_NATIVE_TYPE_ID)` 规避 YAML 多态反序列化 gadget 风险。
- **字节桥接**：Bootstrap-CL 隔离（`BiFunction`/`Object[]` + `Consumer<Object>`）做法正确；`SocketConnect/Nio/InputStream/OutputStream/Close/DnsResolve/ChannelRead/Write` 等 advice 仅引用 `GlobalRouteState` + JDK 类型，Bootstrap 安全。
- **前端 API 客户端**：`api/index.js` 错误拦截统一归一化为 `{success:false}`，处理 401/登出规范。
