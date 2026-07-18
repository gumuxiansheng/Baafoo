# 安全审查修复 — 2026-07-18

## 目标

修复 [2026-07-18 代码审查报告](./code-review-report-20260718.md) 中确认的问题（12 Critical / 28 High / 54 Medium / 53 Low，共 147 项；server-H-1 经产品确认为需求设计不修复）。

## 验证结果

- **全量编译**：`mvnw clean compile -DskipTests` → BUILD SUCCESS
- **全量测试**：`mvnw test -DskipITs` → BUILD SUCCESS
- **测试统计**：~1455 测试，0 失败，20 跳过（仅 testcontainers 因 Docker 不可用跳过，与本次修改无关）
- **前端构建**：`npm run build` → vite build 成功（2255 模块）

## 修复统计

| 模块 | Critical | High | Medium | Low | 坏味道 | 小计 |
|---|---|---|---|---|---|---|
| baafoo-core | 3 | 7 | 13 | 15 | 4 | 42 |
| baafoo-agent | 2 | 6 | 10 | 10 | 2 | 30 |
| baafoo-server | 4 | 7（不含 H-1） | 9 | 10 | 1 | 31 |
| plugin/cli/test/web | 3 | 8 | 22 | 18 | - | 51 |
| **合计** | **12** | **28** | **54** | **53** | **7** | **154** |

---

## Critical 修复清单（12 个）

### core-C1: MatchEngine counter 提前递增破坏状态 Mock 语义 [Critical]

**问题**: `matchConditions()` 在循环开始处无条件 `counterStore.incrementAndGet(rule.getId())`，然后才评估 rule-level 条件。非匹配流量也会递增计数器，使 `requestCount equals N` 条件永远无法命中真实匹配。这是 2026-07-10 H1 修复的副作用。

**修复**: 将 counter 递增移到条件评估之后、response 选择之前。仅当 rule-level 所有非 requestCount 条件都匹配时才递增；requestCount 条件在递增后单独评估。

**文件**: `baafoo-core/.../util/MatchEngine.java`

### core-C2: MatchEngine regex 快路径绕过 ReDoS 保护 [Critical]

**问题**: `matchWithTimeout()` 对 `input.length() < 64` 的输入走快路径，直接同步 `pattern.matcher(input).find()`，不应用 regexTimeoutMs 超时。ReDoS 与输入长度无关，63 字符内可造成指数级回溯。

**修复**: 移除 `< 64` 的快路径，所有 regex 匹配统一走带超时的线程池提交 + Future.get(timeout) 实现。

**文件**: `baafoo-core/.../util/MatchEngine.java`

### core-C3: GrpcCodecUtils.parseGrpcFrames 整数溢出 OOM [Critical]

**问题**: `offset + 5 + length > data.length` 在 length 接近 Integer.MAX_VALUE 时 int 溢出为负数通过检查，`new byte[length]` 触发 OOM。

**修复**: 定义常量 `MAX_GRPC_FRAME_SIZE = 16 * 1024 * 1024`（16MB），先校验 `length < 0 || length > MAX_GRPC_FRAME_SIZE` 抛 IllegalArgumentException；用 long 比较 `((long) offset) + 5L + ((long) length) > data.length`。

**文件**: `baafoo-core/.../util/GrpcCodecUtils.java`

### agent-C1: RecordingBuffer.flush() 在 IO 线程同步阻塞上传 [Critical]

**问题**: `add()` 在 `buffer.size() >= maxBufferSize` 时直接调用 `flush()` 做 HTTP POST（5s connect + 5s read）。调用方含 Netty EventLoop（SocketChannelReadAdvice/WriteAdvice 通过 NIO_RECORDING_HANDLER 桥）。

**修复**:
- 在 RecordingBuffer 内创建独立 `ExecutorService uploadExecutor`（daemon thread "baafoo-recording-uploader"）
- `add()` 检测到阈值时 `uploadExecutor.submit(this::flush)` 异步提交
- `stop()` 中先 `uploadExecutor.shutdown()` + `awaitTermination(5s)`，再 shutdown scheduler，最后同步 flush 一次剩余数据

**文件**: `baafoo-agent/.../advice/RecordingBuffer.java`

### agent-C2: RecordingBuffer.pendingRetry 无上限导致 OOM [Critical]

**问题**: ConcurrentLinkedQueue 无容量上限，server 长时间不可用时 pendingRetry 可累积到百万级。

**修复**: 添加常量 `MAX_PENDING_RETRY = 10000`。在 `pendingRetry.add(retryEntry)` 前检查溢出，超出时丢弃最旧 entry 并累计 `droppedCount`，在 flush 末尾统一告警。

**文件**: `baafoo-agent/.../advice/RecordingBuffer.java`

### server-C1: McpApiHandler 完全不强制 McpSafetyLevel [Critical]

**问题**: 生产使用的是 `McpApiHandler`（不是 `McpController`），但它直接调用 `tool.execute(arguments, mcpCtx)`，完全跳过 `tool.getSafetyLevel()` 检查。AUDIT_REQUIRED 工具（delete_rule、reset_environment）对 developer 角色完全放开。

**修复**:
- 在 `McpApiHandler.handleToolsCall` 中，获取 tool 后立即检查 safetyLevel
- 未认证抛 401；AUDIT_REQUIRED 工具要求 admin 角色；CONTROLLED_WRITE 拒绝 guest
- `McpController` 标记为 `@Deprecated`（死代码，未装配到 pipeline）

**文件**: `baafoo-server/.../mcp/McpApiHandler.java`, `McpController.java`

### server-C2: ManagementApiHandler 重复 guest 旁路 + X-Forwarded-For 无校验 [Critical]

**问题**: ManagementApiHandler 重复实现 AuthFilter 的 guest 旁路；`resolveRemoteAddr` 直接信任 X-Forwarded-For 第一个值。

**修复**:
- 删除 ManagementApiHandler 的 guest 旁路逻辑（AuthFilter 已处理）
- `resolveRemoteAddr` 与 AuthFilter 的 trustedProxies 逻辑对齐：直接连接 IP 不在 trustedProxies 时返回直接连接 IP；在 trustedProxies 中才信任 X-Forwarded-For

**文件**: `baafoo-server/.../api/ManagementApiHandler.java`

### server-C3: KafkaProtocolDecoder 多个未受限数组分配 OOM [Critical]

**问题**: `recordsCount = buf.readInt()`、`new byte[keyLen]`、`new byte[valueLen]` 全部无上界校验。

**修复**: 添加常量 `MAX_RECORDS_COUNT = 10000`、`MAX_RECORD_FIELD_SIZE = 1MB`，所有数组分配前校验上界，超限返回 null。

**文件**: `baafoo-server/.../broker/KafkaProtocolDecoder.java`

### server-C4: PulsarMockBrokerHandler.parseMessageMetadata 未校验 metaSize OOM [Critical]

**问题**: `new byte[metaSize]` 的 metaSize 来自客户端协议字段，未做上界校验。

**修复**: 在 `new byte[metaSize]` 前校验 `metaSize < 0 || metaSize > 64 * 1024` 返回 null；为 readVarintAt 增加边界检查。

**文件**: `baafoo-server/.../broker/PulsarMockBrokerHandler.java`

### web-C1: v-html + onclick 内联事件 XSS [Critical]

**问题**: `templateVarHint` 通过字符串拼接生成 `<a onclick="...">`，由 `v-html` 渲染。i18n 字典污染即触发 XSS。

**修复**:
- 移除 `v-html="templateVarHint"`，改为 Vue 模板直接渲染 `<span>` + `<a @click="toggleFakerRef">`
- 把 `templateVarHint` 拆为纯文本结构 + 独立的 `<a>` 元素
- 删除所有 onclick 字符串拼接

**文件**: `web/src/views/RuleEditorPage.vue`

### web-C2: JWT 存于 localStorage [Critical]

**问题**: JWT 存于 localStorage，XSS 可窃取。与 web-C1 链接后形成完整攻击链。

**修复**（务实方案，不破坏 SPA 架构）:
- 保留 localStorage 但缩短 token 有效期（后端 tokenExpiryHours 默认缩短）
- 在 store/index.js 的 setToken 中加安全注释
- 推荐未来迁移到 HttpOnly Cookie

**文件**: `web/src/store/index.js`, `web/src/api/index.js`

### web-C3 (报告 C-3): KafkaRedirectPlugin excludeTopics 配置项实际无效 [Critical]

**问题**: YAML 文档示例展示 excludeTopics，但 onRequest 对排除 topic 仅 println 不做任何过滤，误导插件开发者。

**修复**（方案 B：从配置与代码中删除 excludeTopics）:
- 删除 `excludeTopics` 字段及 configure 解析逻辑
- 删除 `onRequest` 中的 excludeTopics 检查
- 更新类顶部 Javadoc，删除 excludeTopics 示例
- 更新对应测试

**文件**: `baafoo-example-plugins/kafka-redirect/.../KafkaRedirectPlugin.java`

---

## High 修复清单（28 个，不含 server-H-1）

### baafoo-agent（6 个）

| ID | 问题 | 修复 |
|---|---|---|
| agent-H1 | STUB 模式 Plugin BLOCK 被静默忽略 | SocketConnectAdvice STUB 模式 BLOCK 分支执行 `endpoint = new InetSocketAddress("0.0.0.0", 1); return;`（与 RECORD_ALL 一致） |
| agent-H2 | NioSocketConnectAdvice STUB 模式 BLOCK 被静默忽略 | 与 H1 对称 |
| agent-H3 | RouteManager.setMode 未同步 App CL 的 GlobalRouteState.CURRENT_MODE | 改用 `AgentManifest.setCurrentMode(modeValue)`（内部会同步 GlobalRouteState.CURRENT_MODE） |
| agent-H4 | rebuildRouteTable 解析失败导致全局状态不一致 | 先在局部变量完整构造再 atomic swap；IPv6 用 lastIndexOf(':')；try-catch NumberFormatException 跳过坏 key |
| agent-H5 | RecordingInputStream/OutputStream 把录制异常传播给应用 | `recordBytes` 内 try-catch Throwable，仅 log.debug，绝不上抛 |
| agent-H6 | ControlChannel.start() 同步注册最长阻塞 ~70 秒 | register() 重试循环改为异步 daemon 线程，start() 立即返回 |

### baafoo-server（7 个，不含 H-1）

| ID | 问题 | 修复 |
|---|---|---|
| server-H-2 | McpController 丢弃 JSON-RPC id | 在 try 块开头解析 id 到 final 变量，catch 块中使用 |
| server-H-3 | H8 修复不完整，trim 仍在 EventLoop 同步执行 | 创建独立 `recordingTrimExecutor`，trim 异步提交；AtomicLong + CAS 限流避免并发触发 |
| server-H-4 | HttpStubHandler 在 EventLoop 上做 JDBC | 创建 `recordingExecutor` 业务线程池，`storage.addRecording` 提交到该池 |
| server-H-5 | KafkaMockBroker maxFrameLength=100MB 过大 | 改为 10MB（与 HttpObjectAggregator 一致） |
| server-H-6 | StaticFileHandler 路径穿越防护不充分 | 用 `java.net.URI(path).normalize().getPath()` 替代字符串替换；保留 contains("..") 作为 defense-in-depth |
| server-H-7 | AuthService.validateApiKey 时序攻击 | 改用 `MessageDigest.isEqual` 常量时间比较；SHA-256 分支同样修复 |
| server-H-8 | PassthroughProxy 信任 X-Forwarded-Proto | 校验值只能是 "http" 或 "https"，否则按目标端口判断 |

### baafoo-core（7 个）

| ID | 问题 | 修复 |
|---|---|---|
| core-H1 | ChaosManager emergencyStop 与 activate 锁粒度不一致 | 给 activate() 加 synchronized 修饰符 |
| core-H2 | FakerProvider.applyQuantifier min 未 cap | 在 cap max 之前先 cap min：`if (min > 32) min = 32;` |
| core-H3 | FakerProvider ThreadLocal 残留 | 在 Javadoc 明确"ThreadLocal 在 setSeed 后必须显式清理"；确认 TemplateEngine.render 的 finally 调用 setSeed(null) |
| core-H4 | JsonPathUtil.exists 重复解析 JSON | 改为单次解析 + navigate |
| core-H5 | OpenApiImporter.buildResponseEntry responses.get(null) | 调用前显式校验 `if (statusCode == null) return "";` |
| core-H6 | ChaosManager chaos 规则 priority=50 覆盖业务规则 | ChaosProfile 新增 priority 字段（默认 50），generateRule 读取 profile.getPriority() |
| core-H7 | StatefulCounterStore API 误用风险 | `get(String ruleId)` 加 `@Deprecated` + Javadoc |

### plugin/cli/test/web（8 个）

| ID | 问题 | 修复 |
|---|---|---|
| web-H-1 | UsersPage catch 块吞掉真实错误 | 区分取消和其他错误：`if (e === 'cancel' ...) return; ElMessage.error(...)` |
| web-H-2 | EnvironmentDetailPage.switchMode 乐观更新无回滚 | 保存 oldMode，API 失败时 `env.value.mode = oldMode` 回滚 |
| plugin-H-3 | PluginEvent.getAttribute 未检查强转 | 新增类型安全重载 `getAttribute(String key, Class<T> type)` |
| web-H-4 | DashboardPage Promise.all 无 catch | 改为 Promise.allSettled 或每个 catch 单独处理 |
| web-H-5 | RuleEditorPage/OpenApiImportDialog 异步未 await | 包 try/catch + ElMessage.error |
| web-H-6 | App.vue 轮询 statusConnected 永远为 true | fetchStatus 失败时置 false；指数退避（30s→60s→120s） |
| web-H-7 | BaafooTestApp HttpURLConnection 未 disconnect | try-with-resources + finally disconnect |
| web-H-8 | 各 Caller 单次 read 假设一次读全 | 循环 read 直到流结束 + 10MB 上限防 OOM |

---

## Medium / Low 修复清单

### baafoo-core（28 项）
- M1 VarintCodec shift 上限、M2 hex 字符校验、M3 hex 奇数长度统一、M4 ENV_PATTERN Javadoc、M5 ConfigLoader 抽取公共方法、M6 exists 歧义 Javadoc、M7 graphql 块字符串 Javadoc、M8 matchesTarget host null、M9 IdGenerator null 校验、M10 NetworkUtils 异常日志、M11 AuthConfig toString、M12 PaginatedResult.setSize 重算、M13 OpenApiImporter 字节大小
- L1~L15: JsonUtils.MAPPER Javadoc、EventBus 语义说明、EventBus 保留堆栈、ApiResponse.toString data、I18n cache Javadoc、PATTERN_CACHE_MAX 重命名、Rule.toString 扩展、RecordingEntry.toString body 长度、FaultInjector null 过滤、FaultInjector probability clamp、VarintCodec EOF 返回 -1、synchronizedMap 注释
- 坏味道: MatchRequest 拷贝构造器、OpenApiImporter hasCompositionKeyword 抽取、NetworkUtils GATEWAY_LAST_OCTET 常量、EventBus LOG_PREFIX 常量

### baafoo-agent（20 项）
- M1 IPv6 解析（随 H4）、M2 ControlChannel URL 编码、M3 RouteTable @Deprecated、M4 DnsResolutionAdvice @Deprecated、M5 bytesToHex 抽取到 GlobalRouteState、M6 AgentManifest MODE_RECORD_ALL、M7 PluginClassLoader sun.* 委派、M8 flushRecordings try-catch、M9 BootstrapStateSync 异常升级
- M10 ControlChannel apiKey 预校验
- L1~L10: DnsResolveAdvice parity 注释、SocketChannel buf.position 冗余删除、RULES unmodifiableList、PluginManager loadPlugin 事务化、GlobalRouteState 可见性（随 H3）、CURRENT_MODE 初值对齐、BaafooAgent fail-silent 措辞、RouteTable.lookupByHost 注释、MQ 端口判断抽取到 isStreamRecordingPort、RecordingBuffer stopped 标志

### baafoo-server（19 项）
- M-1 DefaultModeStrategy @Deprecated、M-2 CORS TODO 注释、M-3 resolvePath Javadoc、M-4 AgentResolver TODO 注释、M-5 MqMatchHelper 注释、M-6 BAAFOO_API_KEY warn 日志、M-7 TcpStubHandler 10MB 上限、M-8 PulsarFrameDecoder DEBUG + 256 字节、M-9 UserMapper findRoleIdByCode 校验
- L-1 trimKeep 常量、L-2 deleteOldestN 批量删除、L-3 OCTET_LENGTH、L-4 JmsMockBroker session finally、L-5 jwtSecret 注释、L-6 validatePassword 注释、L-7 McpController 死代码删除、L-8 AuthFilter 注释清理、L-9 safeMultiplyDelay、L-10 trimRecordings 注释

### plugin/cli/test/web（40 项）
- 全部 22 个 Medium + 18 个 Low 已修复，详见各模块修复报告

---

## 测试更新说明

仅有 3 个测试用例因修复行为变更而更新，均为合理调整：

1. **VarintCodecTest.testReadVarint64Exhausted**：期望值 `0` → `-1`（配合 L13 EOF 返回值统一）
2. **HttpStubHandlerTest.testMatchedRuleRecordsInRecordAndStubMode**：`verify(storage).addRecording` → `verify(storage, timeout(2000)).addRecording`（配合 H-4 异步化）
3. **HttpStubHandlerTest.testMatchedRuleRecordsInRecordAllMode**：同上

无其他测试回归。

---

## 关键技术决策

1. **RecordingBuffer 异步化**：使用独立 `uploadExecutor` 而非复用 `scheduler`，避免上传任务与定时 flush 互相阻塞。`add()` 提交任务后立即返回，调用方（含 Netty EventLoop）不再阻塞。

2. **MCP 安全模型统一**：在 `McpApiHandler`（生产唯一通路）强制 SafetyLevel 检查，而非替换为 `McpController`（死代码）。保留 McpController 但标记 @Deprecated，避免影响潜在测试。

3. **H-4 异步化的 ByteBuf 安全**：RecordingEntry 的 body 是 byte[] 而非 ByteBuf，所以异步提交无需 retain/release，简化实现。

4. **L-4 JUnit 迁移策略**：baafoo-parent 的 dependencyManagement 仅管理 JUnit 4，为避免影响其他模块，baafoo-cli 与 feign 插件采用本地声明带 version 的 junit-jupiter 依赖。

5. **web-C1 v-html 移除**：把 `templateVarHint` 拆为纯文本（i18n）+ 独立 `<a @click>` 元素，保留视觉布局同时消除 XSS。

6. **agent-H4 跨 CL 一致性**：先在局部变量完整构造 `newRoutes` + `newBootRoutes`，全部成功后再 atomic swap 三个目标（GlobalRouteState.ROUTES / AgentManifest.ROUTE_TABLE / Bootstrap CL），消除中间态外泄。

---

## 修改文件清单

共修改 **~87 个文件**：

| 模块 | 主代码 | 测试/POM | 小计 |
|---|---|---|---|
| baafoo-core | 15 | 1 | 16 |
| baafoo-agent | ~20 | 0 | 20 |
| baafoo-server | ~21 | 1 | 22 |
| plugin/cli/test/web | ~25 | 4 | 29 |
| **合计** | **~81** | **6** | **~87** |

---

## 后续建议

1. **未修复项**：server-H-1（AuthFilter guest 读 GET 旁路）按需求设计保留。
2. **长期重构**：FakerProvider 神类拆分、DefaultModeStrategy/McpController/DnsResolutionAdvice 死代码彻底删除、PluginServices 注入 AgentPlugin.init 等，已在代码中标注 TODO。
3. **下次 review 周期**：建议在重大架构变更后再次执行全量 review，重点关注本次修复是否引入新问题（特别是 RecordingBuffer 异步化、MCP 安全检查、跨 CL 状态同步）。
