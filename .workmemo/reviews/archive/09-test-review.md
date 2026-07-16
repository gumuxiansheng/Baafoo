# 测试代码审查补充报告

> 审查 46 个测试源文件（core 17 + server 8 + agent 15 + plugin-api 3 + cli 1 + test-spring 1 + feign-plugin 1）
> 发现 23 个新问题 (P0: 6, P1: 7, P2: 6, P3: 4)

---

## P0 — 逻辑 Bug / 脆性测试 (6)

### TST-P0-1: FileStorageTest.testUndoRule 断言值错误
- **文件**: `FileStorageTest.java:127`
- **描述**: `testUndoRule` 创建 v1 → 更新到 v2 → undo → 断言 `assertEquals("v2", undone.getName())`。但 undo 应恢复为 v1（undo 前保存了当前值）。断言实际应为 `"v1"`。当前测试是**循环论证**：只有 undo 也坏了测试才通过。
- **建议**: 改为 `assertEquals("v1", undone.getName())`

### TST-P0-2: JmsMockBrokerTest Thread.sleep 等待订阅
- **文件**: `JmsMockBrokerTest.java:113`
- **描述**: `Thread.sleep(200)` 等待 subscriber 注册。CI 负载高时可能不够，快的机器上浪费 200ms。
- **建议**: 使用 `CountDownLatch` 替代

### TST-P0-3: PulsarMockBrokerTest / KafkaMockBrokerTest 固定端口绑定
- **文件**: `PulsarMockBrokerTest.java:63`, `KafkaMockBrokerTest.java:34`
- **描述**: 绑定固定端口 19093/19092。CI 并行运行或端口被占用时测试全失败。
- **建议**: 使用端口 0 + `localAddress()` 读取实际端口

### TST-P0-4: BaafooTestSpringApplicationTests 依赖 httpbin.org
- **文件**: `BaafooTestSpringApplicationTests.java:62,115`
- **描述**: 测试请求 `http://httpbin.org/get`，依赖外部网络。CI 无网络出口时失败。
- **建议**: 使用本地 WireMock 或加 `@DisabledIfEnvironmentVariable`

### TST-P0-5: DnsInterceptionIntegrationTest 总是通过
- **文件**: `DnsInterceptionIntegrationTest.java:38-49`
- **描述**: `InetAddress.getByName("localhost")` 后 `if (cachedDomain != null)` 条件通过——实际上**总是通过**，因为 null 时测试静默跳过。循环论证。
- **建议**: 直接使用 `GlobalRouteState.recordDns()` 设置缓存后断言

### TST-P0-6: RecordingBufferTest.testStartAndStop 零断言
- **文件**: `RecordingBufferTest.java:104-114`
- **描述**: 调用了 `start()`, `add()`, `stop()` 但**没有任何断言**。测试名说"After stop buffer should be flushed"但从未验证。
- **建议**: 添加 `assertEquals(0, buffer.size())` 和 pending 断言

---

## P1 — 代码坏味道 / 弱断言 (7)

| ID | 文件:行 | 问题 | 建议 |
|----|---------|------|------|
| TST-P1-1 | ManagementApiHandlerTest:251-252 | 连续两次相同的 `when(storage.listScenes()).thenReturn(...)` | 删掉重复行 |
| TST-P1-2 | HttpStubHandlerTest:51 | 用 `fireChannelRead` 而非 `writeInbound`，绕过 inbound buffer | 统一用 `writeInbound` |
| TST-P1-3 | TemplateEngineTest:228-239,243-253 | 静默捕获 `NoClassDefFoundError` 后跳过测试 | 用 `Assume.assumeNoException()` |
| TST-P1-4 | DnsInterceptionIntegrationTest:84-86 | 测试 first-write-wins 但无文档契约，改 putIfAbsent 后测试静默通过 | 添加注释引用语义 |
| TST-P1-5 | SocketInterceptionIntegrationTest:36-44 | 测试名 `testPassthroughModeDoesNotIntercept` 但只检查了 flag，未验证拦截 | 改名或加真实拦截测试 |
| TST-P1-6 | RecordingBufferTest:85-86 | 并发测试断言 `buffer.size() > 0`，500 条中进 1 条也通过 | 断言总数 500 |
| TST-P1-7 | FeignPluginTest | 未验证实际 SPI 加载逻辑 | 补充 SPI 发现测试 |

---

## P2 — 价值低 / 覆盖缺口 (6)

| ID | 文件 | 问题 |
|----|------|------|
| TST-P2-1 | 9 个核心 model 测试文件 | 纯 getter/setter 往返测试，编译期就能保证，价值近零 |
| TST-P2-2 | MatchEngineTest:362-370 | out-of-bounds 行为测试但语义不明确（返回第一个 vs 抛异常） |
| TST-P2-3 | JmsMockBrokerTest:267-278 | topic preset 验证不完整 |
| TST-P2-4 | StaticFileHandlerTest | 仅 2 个测试，缺少路径遍历、MIME 类型、304 等关键覆盖 |
| TST-P2-5 | BaafooAgentTest:8-44 | 7 个测试全在 premain 前检查静态字段初始值，无 premain 集成测试 |
| TST-P2-6 | PluginManagerTest:9-50 | 目录不存在，无真实插件加载测试 |

---

## P3 — 小问题 (4)

| ID | 文件:行 | 问题 |
|----|---------|------|
| TST-P3-1 | FileStorageTest:33-35 | tearDown 未删除临时目录 |
| TST-P3-2 | GlobalRouteStateTest 等 3 文件 | 共享静态状态可能干扰并行测试 |
| TST-P3-3 | BaafooCliTest:91-92 | 平台依赖的 `canExecute()`，Windows 上可能失败 |
| TST-P3-4 | PulsarMockBrokerTest:222 / KafkaMockBrokerTest:245 | 单测试方法过长（35+ 行），测多个行为 |
