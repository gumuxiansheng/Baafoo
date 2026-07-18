# Baafoo 代码审查报告 — 2026-07-18

> **审查范围**：baafoo-core / baafoo-agent / baafoo-server / baafoo-plugin-api / baafoo-cli / baafoo-test-app / baafoo-test-spring / baafoo-example-plugins / web 前端
> **审查方式**：分模块并行深度静态审查（逻辑正确性、并发安全、资源泄漏、安全、错误处理、代码坏味道）
> **对比基线**：[2026-07-10 安全 Review](./code-review-report-20260710.md) + [修复记录](./security-fixes-20260710.md) + [问题追踪](./issue-tracker.md)
> **日期**：2026-07-18
> **说明**：本报告对 2026-07-10 声称已修复的项做了验证，并发现了 12 个 Critical / 29 个 High / 54 个 Medium / 53 个 Low 共 148 个新问题。

---

## 一、2026-07-10 修复验证结论

| 修复项 | 验证结论 | 说明 |
|---|---|---|
| C1 MCP admin 硬编码 | ❌ 未真正生效 | 生产走 `McpApiHandler`（无 SafetyLevel 检查），`McpController` 是死代码 |
| H1 规则级 requestCount | ⚠️ 修复引入新缺陷 | counter 提前递增破坏状态 Mock 语义（见 core-C1） |
| H2 selectResponseEntry 返回 -1 | ✅ 已正确实现 | — |
| H3 BLOCK 改写 endpoint | ⚠️ 仅 RECORD_ALL 修了 | STUB 模式漏修（见 agent-H1/H2） |
| H6 匿名 guest 可读 API | ❌ 仍可旁路 | AuthFilter + ManagementApiHandler 双重 guest 旁路（见 server-C2/H-1） |
| H8 addRecording 限流 | ⚠️ 仍同步阻塞 EventLoop | 只是降到每 50 次 trim 一次，仍同步（见 server-H-3） |
| H10/H11/M21/M22/M23/M25 前端 | ✅ 全部已正确实现 | — |
| M3/M10/M12/M13/M17 | ✅ 部分已实现 | DefaultModeStrategy 是死代码（server-M-1） |

---

## 二、问题统计

| 模块 | Critical | High | Medium | Low | 小计 |
|---|---|---|---|---|---|
| baafoo-core | 3 | 7 | 13 | 15 | 38 |
| baafoo-agent | 2 | 6 | 10 | 10 | 28 |
| baafoo-server | 4 | 8 | 9 | 10 | 31 |
| plugin/cli/test/web | 3 | 8 | 22 | 18 | 51 |
| **合计** | **12** | **29** | **54** | **53** | **148** |

---

## 三、🔴 Critical 问题（12 个）

### 安全/授权类（5 个）

#### server-C1 McpApiHandler 完全不强制 McpSafetyLevel
- **文件**：`baafoo-server/.../mcp/McpApiHandler.java:114-139`
- **问题**：生产唯一通路的 `McpApiHandler.handleToolsCall` 直接执行 tool，跳过 `getSafetyLevel()` 检查。`McpController`（含检查逻辑）从未被装配到 Netty pipeline，是死代码。AUDIT_REQUIRED 工具（delete_rule、reset_environment）对 developer 角色完全放开。
- **影响**：严重违反 C1 修复意图，可审计性完全失效。

#### server-C2 ManagementApiHandler 重复 guest 旁路 + X-Forwarded-For 无校验
- **文件**：`baafoo-server/.../api/ManagementApiHandler.java:145-180`
- **问题**：重复实现 AuthFilter 的 guest 旁路；`resolveRemoteAddr` 直接信任 X-Forwarded-For 第一个值，可伪造 IP 绕过 localBypass。

#### server-H-1 AuthFilter 仍允许 guest 读所有非 /users 端点
- **文件**：`baafoo-server/.../auth/AuthFilter.java:93-106`
- **问题**：`GET /api/rules`、`/api/recordings`、`/api/environments`、`/api/agents` 不需任何凭证即可访问。
- **备注**：**经产品确认，此为需求设计，不修复**。

#### web-C1 v-html + onclick 内联事件 XSS
- **文件**：`web/src/views/RuleEditorPage.vue:376, 521-527`
- **问题**：`templateVarHint` 通过字符串拼接生成 `<a onclick="...">`，由 `v-html` 渲染。i18n 字典污染即触发任意脚本执行。

#### web-C2 JWT 存于 localStorage
- **文件**：`web/src/store/index.js:122`
- **问题**：与 web-C1 链接后形成完整攻击链：i18n 污染 → XSS → 窃取 JWT → 伪造管理员请求。

### DoS/OOM 类（5 个）

#### core-C2 MatchEngine regex 快路径绕过 ReDoS 保护
- **文件**：`baafoo-core/.../util/MatchEngine.java:786-826`
- **问题**：输入长度 < 64 时直接同步 `pattern.matcher(input).find()`，不应用 regexTimeoutMs 超时。ReDoS 与输入长度无关。

#### core-C3 GrpcCodecUtils.parseGrpcFrames 整数溢出 OOM
- **文件**：`baafoo-core/.../util/GrpcCodecUtils.java:68-85`
- **问题**：`offset + 5 + length > data.length` 在 length 接近 Integer.MAX_VALUE 时溢出为负数通过检查，`new byte[length]` 触发 OOM。

#### server-C3 KafkaProtocolDecoder 多个未受限数组分配 OOM
- **文件**：`baafoo-server/.../broker/KafkaProtocolDecoder.java:1157`
- **问题**：`recordsCount = buf.readInt()`、`new byte[keyLen]`、`new byte[valueLen]` 全部无上界校验，配合 KafkaMockBroker 100MB 帧长上限可触发 OOM。

#### server-C4 PulsarMockBrokerHandler.parseMessageMetadata 未校验 metaSize
- **文件**：`baafoo-server/.../broker/PulsarMockBrokerHandler.java:361-419`
- **问题**：`new byte[metaSize]` 的 metaSize 来自客户端协议字段，恶意客户端可让 Broker OOM。

#### agent-C1 RecordingBuffer.flush() 在 IO 线程同步阻塞上传
- **文件**：`baafoo-agent/.../advice/RecordingBuffer.java:70-77`
- **问题**：H4 标记"不修复"但实际仍存在。缓冲满 100 条时同步 HTTP POST（最长 10s），调用方含 Netty EventLoop，会卡死整个 NIO pipeline。

### 逻辑正确性类（2 个）

#### core-C1 MatchEngine counter 提前递增破坏状态 Mock 语义
- **文件**：`baafoo-core/.../util/MatchEngine.java:252-291`
- **问题**：H1 修复的副作用。`matchConditions()` 在循环开始处无条件 `incrementAndGet`，非匹配流量也会递增计数器，使 `requestCount equals N` 这类条件永远无法命中真实匹配。

#### agent-C2 RecordingBuffer.pendingRetry 无上限导致 OOM
- **文件**：`baafoo-agent/.../advice/RecordingBuffer.java:51`
- **问题**：ConcurrentLinkedQueue 无容量上限，server 长时间不可用时 pendingRetry 可累积到百万级。

---

## 四、🟠 High 问题（29 个，精选关键项）

### baafoo-agent（6 个）
- **agent-H1/H2**：SocketConnectAdvice/NioSocketConnectAdvice STUB 模式 BLOCK 失效。H3 修复只在 RECORD_ALL 模式生效，STUB 模式 plugin BLOCK 仅 return 未改写 endpoint。
- **agent-H3**：RouteManager.setMode 未同步 App CL 的 GlobalRouteState.CURRENT_MODE，App CL 永远停留在 0。
- **agent-H4**：RouteManager.rebuildRouteTable 解析失败导致三处状态不一致（App CL ROUTES / AgentManifest.ROUTE_TABLE / Bootstrap CL）。
- **agent-H5**：RecordingInputStream/OutputStream 把录制异常传播给应用，破坏 IO 语义。
- **agent-H6**：ControlChannel.start() 同步注册最长阻塞 ~70 秒，延迟 JVM main() 启动。

### baafoo-server（8 个，不含 H-1）
- **server-H-2**：McpController 丢弃 JSON-RPC id，错误响应不可关联。
- **server-H-3**：H8 修复不完整，trim 仍在 EventLoop 同步执行。
- **server-H-4**：HttpStubHandler.handlePassthroughAndRecord 在 EventLoop 上做 JDBC。
- **server-H-5**：KafkaMockBroker maxFrameLength=100MB 过大。
- **server-H-6**：StaticFileHandler 路径穿越防护不充分（naive 字符串替换）。
- **server-H-7**：AuthService.validateApiKey 时序攻击（HashMap.get）。
- **server-H-8**：PassthroughProxy.determineProtocol 信任 X-Forwarded-Proto（SSRF/协议混淆）。

### baafoo-core（7 个）
- **core-H1**：ChaosManager.emergencyStop 与 activate 锁粒度不一致。
- **core-H2**：FakerProvider.applyQuantifier min 未 cap，`regexify '{1000000,2000000}'` 触发 OOM。
- **core-H3**：FakerProvider ThreadLocal 残留导致跨规则污染。
- **core-H4**：JsonPathUtil.exists 重复解析 JSON。
- **core-H5**：OpenApiImporter.buildResponseEntry responses.get(null) 行为依赖 Jackson 版本。
- **core-H6**：ChaosManager chaos 规则 priority=50 可能覆盖用户业务规则。
- **core-H7**：StatefulCounterStore API 误用风险（resetIfThreshold 后 get() 返回 0）。

### plugin/cli/test/web（8 个）
- **web-H-1**：UsersPage catch 块吞掉真实错误。
- **web-H-2**：EnvironmentDetailPage.switchMode 乐观更新无回滚。
- **plugin-H-3**：PluginEvent.getAttribute 未检查强转。
- **web-H-4**：DashboardPage Promise.all 无 catch，任一失败整页空白。
- **web-H-5**：RuleEditorPage/OpenApiImportDialog 在 setup 中调用异步函数未 await。
- **web-H-6**：App.vue 轮询 statusConnected 永远为 true，无指数退避。
- **web-H-7**：BaafooTestApp HttpURLConnection 未 disconnect。
- **web-H-8**：各 Caller 单次 read 假设一次读全，TCP 分片会丢数据。

---

## 五、Medium / Low 问题汇总

### Medium（54 个）
覆盖：VarintCodec shift 上限、GrpcCodecUtils hex 字符校验、ConfigLoader 重复实现、MatchEngine exists 歧义、ChaosManager 锁粒度、IdGenerator null 校验、NetworkUtils 异常吞噬、ServerConfig toString、PaginatedResult.setSize、OpenApiImporter 字节大小、RouteManager IPv6 解析、ControlChannel URL 编码、AgentManifest 死状态、DnsResolutionAdvice 死代码、bytesToHex 性能、PluginClassLoader sun.* 委派、BootstrapStateSync 异常吞噬、apiKey 预校验、DefaultModeStrategy 死代码、CORS 硬编码、JdbcStorageService System.getProperty、AgentResolver instanceof、MqMatchHelper 单环境回退、BaafooServer BAAFOO_API_KEY、TcpStubHandler 无帧长上限、PulsarFrameDecoder INFO hex dump、UserMapper NULL role_id、RequestContext 并发、ResponseContext 防御性拷贝、OkHttpClient 单例、GrpcEchoServer catch Throwable、LocaleSwitcher 死代码、StatusPage lastHeartbeat 单位、EnvironmentsPage 部分失败、RecordingsPage 页码错位、ScenesPage active 硬编码、JMS/Pulsar session 泄漏、GrpcCallerService awaitTermination、latch 超时无日志、InputStreamReader 字符集、body 截断、JSON.parse 深拷贝、FeignPlugin 无法访问 PluginServices、KafkaCaller 重复构造、IPv6 loopback 判断、BaafooCli 端口校验、FileWriter 字符集、TdmqCallerService 注释矛盾、JmsCallerService 反射耦合等。

### Low（53 个）
覆盖：JsonUtils.MAPPER public、EventBus eventual consistency、EventBus 丢堆栈、ApiResponse.toString、I18n cache 永不失效、PATTERN_CACHE_MAX 命名冲突、Rule.toString、RecordingEntry.toString、FaultInjector null/概率校验、VarintCodec EOF 返回值、HexUtils 优化、synchronizedMap 同步开销、示例插件 System.out.println、BaafooCli 异常吞噬、BaafooTestApp stderr、JUnit 4/5 不一致、parseInt NaN、STRING_MARSHALLER 重复、ByteBuffer flip 强转、RulesPage version undefined、LogsPage 空字符串契约、OpenApiImportDialog file.raw 校验、LoginPage 暴力枚举、App.vue setInterval 清理、RequestAdvice 防御性拷贝、BaafooCli 空密码、RecordingStore 接口缺失、FeignCaller 未 close、BaafooCli reader 未关闭、KafkaCaller getBytes 字符串形式等。

---

## 六、代码坏味道（7 项）

1. DnsResolveAdvice / DnsResolveAllAdvice / DnsResolutionAdvice 三个类大段重复。
2. DefaultModeStrategy 是死代码，生产用 inline 分派。
3. McpController 是死代码，与 McpApiHandler 重复实现。
4. FakerProvider 神类（866 行）。
5. String.format("%02x", ...) 在 IO 热路径（4 处 advice）。
6. MQ 端口 skip 逻辑三处重复。
7. GrpcCodecUtils.hexToBytes 静默吞错。

---

## 七、Netty EventLoop 阻塞点汇总

| 位置 | 阻塞操作 | 严重度 |
|---|---|---|
| RecordingBuffer.flush | 同步 HTTP POST 上传录制 | Critical |
| JdbcRecordingService.addRecording | 每 50 次同步 trim + 每次同步 INSERT | High |
| HttpStubHandler.handlePassthroughAndRecord | storage.addRecording JDBC | High |
| TcpStubHandler | 同步 DB 录制 | High |
| PulsarFrameDecoder | INFO 级别 hex dump | Medium |
| GrpcUnifiedHandler.delayMs * i | 流式 delay 可能溢出 | Low |

---

**报告完成。** 详细修复方案见 [security-fixes-20260718.md](./security-fixes-20260718.md)，问题状态追踪见 [issue-tracker.md](./issue-tracker.md)。
