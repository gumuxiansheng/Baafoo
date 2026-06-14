# Plugin API / CLI / 测试模块 详细审查报告

> 共审查 33 个源文件（plugin-api:4, cli:1, test-plugin:3, test-app:13, test-spring:14）
> 发现 24 个问题 (Critical: 0, High: 3, Medium: 8, Low: 13)

---

## High (3)

### TST-H1: FeignCallerService OkHttpClient 每请求创建
- **文件**: `FeignCallerService.java:42-47, 63-68`
- **描述**: `OkHttpClient` 维护连接池和线程池，每请求创建新实例导致线程泄漏。应对大量并发请求时，线程数会爆炸。
- **建议**: 改为 Spring @Bean 单例

### TST-H2: PulsarCallerService Producer 未在 finally 中关闭
- **文件**: `PulsarCallerService.java:34-43`
- **描述**: `producer.close()` 不在 `finally` 块中。`producer.send()` 抛出异常时会泄漏 producer。
- **建议**: 将 close() 移到 finally 块

### TST-H3: ExternalApiClient InputStreamReader 未指定 charset
- **文件**: `ExternalApiClient.java:27`
- **描述**: `new InputStreamReader(conn.getInputStream())` 使用平台默认编码。非 UTF-8 系统上含 Unicode 字符的响应会乱码。
- **建议**: 使用 `new InputStreamReader(..., StandardCharsets.UTF_8)`

---

## Medium (8)

| ID | 文件:行 | 问题 | 建议 |
|----|---------|------|------|
| PLG-M1 | PluginContext.java:68-69 | setHeaders(null) 静默接受 null，getHeaders() 返回 null 导致 NPE | 拒绝 null 或存 emptyMap() |
| PLG-M2 | InterceptResult.java:32,34-39 | metadata 字段从未初始化，getMetadata() 返回 null | 初始化为 emptyMap() |
| CLI-M1 | BaafooCli.java:345-349,372-376,460-466 | writeExampleRules 双打开文件，YAML write 后追加可能交错 | 使用同一输出流 |
| TST-M1 | FeignClientAdvice.java:37-43 | Request.create() 可能丢失原始 charset | 保留原请求的 charset |
| TST-M2 | FeignPlugin.java:87-92 | destroy() → clear() → initialized=false，intercept() 期间状态不一致 | 加锁或状态机 |
| TST-M3 | RuleSetup.java:253-257 | doPost 对非 2xx 返回 null，错误响应体从未消费 | 消费响应体或调用 disconnect() |
| TST-M4 | QuickTest.java:62,107 | InputStreamReader 未指定 charset | 添加 StandardCharsets.UTF_8 |
| TST-M5 | FeignCallerService:48-52,69-73 | Feign API 代理每请求创建 | 缓存为单例 |

---

## Low (13)

| ID | 文件:行 | 问题 |
|----|---------|------|
| PLG-L1 | InterceptResult.java:44-51 | stub() factory 存储 headers 时未防御性拷贝 |
| PLG-L2 | InterceptResult.java:63-69 | error() 设置 stubbed=true，stub 和 error 无法区分 |
| PLG-L3 | PluginContext.java:44 | conditionIndex 默认 0，与 "第一个条件匹配" 混淆 |
| CLI-L1 | BaafooCli.java:70 | BufferedReader(System.in) 未关闭 |
| CLI-L2 | BaafooCli.java:457 | YAML_MAPPER.writeValue 后未 flush |
| TST-L1 | FeignClientAdvice:33-35 | 耦合的 Header 命名约定（X-Feign-Method / X-Feign-Path） |
| TST-L2 | FeignPlugin.java:34-38 | initialized = true 无 happens-before 保证 |
| TST-L3 | FeignPlugin.java:114-118 | buildStubKey 将 null 默认为 "GET /"，掩盖配置错误 |
| TST-L4 | FeignPluginDemo.java:117 | body.getBytes() 使用平台默认编码 |
| TST-L5 | BaafooTestApp.java:40-86 | while(true) 无中断/关闭钩子 |
| TST-L6 | HttpCaller/ConsulHttpCaller | printResponse() 代码在多个 caller 中重复 |
| TST-L7 | KafkaCaller.java:44-50 | createProducerConfig() 调用两次 |
| TST-L8 | JmsCaller.java:41-49 | MessageProducer 未显式关闭 |
| TST-L9 | PulsarCaller.java:26-86 | Producer 不在 finally 中关闭 |
| TST-L10 | SocketCallerService.java | 方法名 testBiologicalSocket 拼写错误（应为 testBlockingSocket） |
| TST-L11 | KafkaCallerService:23-56 | KafkaProducer 每请求创建（昂贵） |
