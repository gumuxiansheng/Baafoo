# baafoo-server 详细审查报告

> 共审查 60 个源文件，发现 36 个问题 (Critical: 6, High: 12, Medium: 12, Low: 6)

---

## Critical (6)

### SRV-C1: 硬编码管理员密码
- **文件**: `BaafooServer.java:237, 258`
- **描述**: 默认管理员密码 `"B@af00!Adm1n#2026"` 以明文硬编码在源码中。任何能访问源码的攻击者都可获得管理权限。
- **建议**: 通过环境变量或首次启动时生成；移除源码中的所有明文密码字符串

### SRV-C2: SSL/TLS 证书验证完全禁用
- **文件**: `PassthroughProxy.java:84-86`
- **描述**: `InsecureTrustManagerFactory.INSTANCE` 接受所有证书(包括恶意证书)，HTTPS passthrough 完全暴露于 MITM。
- **建议**: 生产环境使用默认 TrustManager；测试环境通过配置开关控制

### SRV-C3: 弱密码哈希 (SHA-256 单次无迭代)
- **文件**: `AuthService.java:201-212`
- **描述**: 使用 `MessageDigest.getInstance("SHA-256")` + salt 单次哈希。GPU/ASIC 上每秒可进行数十亿次暴力破解尝试。
- **建议**: 使用 PBKDF2WithHmacSHA256 (≥600,000 迭代) 或 bcrypt/scrypt/Argon2

### SRV-C4: 登录端点无速率限制
- **文件**: `AuthApiHandler.java:32-44`
- **描述**: `/__baafoo__/api/auth/login` 未实现任何速率限制、账户锁定或验证码。
- **建议**: 添加每 IP/每用户名的速率限制 + N 次失败后指数退避

### SRV-C5: 路径遍历漏洞
- **文件**: `StaticFileHandler.java:136`
- **描述**: 路径清理使用 `.replace("..", "").replace("//", "/")` — 可被 `..../..../` 绕过。
- **建议**: 使用 `Path.normalize()` 并验证结果以 webRoot 开头

### SRV-C6: 双权限检查系统不一致
- **文件**: `AuthFilter.java:79-84` vs `ApiContext.java:32-37`
- **描述**: AuthFilter 在管道早期做权限检查(映射 GET→read)，而各 Handler 用 `requirePermission()` 做二次检查。两套映射不同，guest 认证下行为可能不一致。
- **建议**: 统一到单一权限检查点

---

## High (12)

### SRV-H1: 认证关闭时所有请求获得 admin 角色
- **文件**: `AuthService.java:105-107`
- **描述**: `authEnabled=false` 时 `authenticate()` 返回 `new AuthResult(true, "admin", ...)` → 所有请求都成为管理员。
- **建议**: 认证关闭时返回降级角色(如 guest)，或用警告日志阻止启动

### SRV-H2: 错误消息未转义 -> XSS
- **文件**: `StaticFileHandler.java:230`
- **描述**: 错误消息通过字符串拼接插入 HTML（`"<p>" + message + "</p>"`），攻击者可构造含恶意脚本的请求。
- **建议**: 对 HTML 特殊字符进行转义

### SRV-H3: CORS `Access-Control-Allow-Origin: *`
- **文件**: `ManagementApiHandler.java:122`
- **描述**: 所有响应都设置 CORS 为 `*`。
- **建议**: 使 CORS 来源可通过配置控制

### SRV-H4: Kafka 协议处理中修改共享字节数组(竞争)
- **文件**: `KafkaProtocolDecoder.java:446`
- **描述**: `writeLongToBytes(msg.value, 0, msg.offset)` 直接修改 `StoredMessage.value`。两个并发消费者会互相破坏对方的 offset。
- **建议**: 修改前 clone() 字节数组

### SRV-H5: 清理任务删除了最新记录而不是最旧的
- **文件**: `RecordingCleanupTask.java:111`
- **描述**: 调用 `storage.listRecordings(null, count)` 按 `recorded_at DESC` 排序，删除了最新记录。应使用 `listOldestRecordings`。
- **建议**: 改用按 ASC 排序的查询

### SRV-H6: FileStorage.deleteScene 中的不可达代码
- **文件**: `FileStorage.java:296`
- **描述**: `return scenes.remove(id) != null;` 在 saveScenes 失败后仍返回 true（因为 scenes.remove 可能成功）。
- **建议**: 改为 `return false;`

### SRV-H7: JMS connection.start() 在 producer.send() 之后
- **文件**: `JmsMockBroker.java:165-180`
- **描述**: `producer.send(message)` 在 `connection.start()` 之前调用。AUTO_ACKNOWLEDGE 模式下行为未定义。
- **建议**: 将 `connection.start()` 移到 `producer.send()` 之前

### SRV-H8: Kafka 固定 producerId=1
- **文件**: `KafkaProtocolDecoder.java:564-585`
- **描述**: `handleInitProducerId` 返回固定 producerId=1，epoch=0。幂等生产者会产生 OutOfOrderSequenceException。
- **建议**: 添加 producerId 状态管理

### SRV-H9: EventLoop 上编译正则表达式 (ReDoS)
- **文件**: `TcpStubHandler.java:316`
- **描述**: `Pattern.compile(regex)` 在 Netty EventLoop 线程上执行，恶意正则可导致灾难性回溯。
- **建议**: 在 EventLoop 外编译或添加超时机制

### SRV-H10: HttpStubHandler ByteBuf 未及时释放
- **文件**: `HttpStubHandler.java:74`
- **描述**: `request.content()` 消费后未释放 `ByteBuf`。Netty reference-counting 虽会最终释放，但不够及时。
- **建议**: 使用后立即 `request.content().release()`

### SRV-H11: JdbcStorageService 缓存不一致
- **文件**: `JdbcStorageService.java:172-188`
- **描述**: `rulesCache` 和 `rulesCacheTime` 是独立 volatile 字段，线程 A 设 cache=null，线程 B 读到过期 cacheTime。
- **建议**: 使用 `AtomicReference` 原子化存储 cache+time

### SRV-H12: JWT Secret 未配置时自动生成
- **文件**: `AuthService.java:93`
- **描述**: jwtSecret 未配置或 < 32 字节时自动生成新密钥 → 重启后所有现有 token 失效。
- **建议**: 未配置有效 JWT Secret 时启动失败

---

## Medium (12)

| ID | 文件:行 | 问题 | 建议 |
|----|---------|------|------|
| SRV-M1 | ApiUtils.java:9 / AuthFilter:93 / HttpStubHandler:248 / StaticFileHandler:241 | extractPath 重复 4 处 | 集中到 ApiUtils |
| SRV-M2 | BaafooServer.java:210-212 | 所有协议共享 EventLoopGroup，一个 DoS 影响所有 | 为 Kafka/Pulsar 用专用 EventLoopGroup |
| SRV-M3 | SceneApiHandler.java:23-24 | GET /api/scenes/{id} 用 O(n) 遍历 | 改用 ctx.storage.getScene(id) |
| SRV-M4 | KafkaProtocolDecoder.java:528-560 | 不支持 v9+ 紧凑格式 | 限制版本到 v8 或实现紧凑编码 |
| SRV-M5 | PulsarMockBrokerHandler.java:269 | partition 硬编码为 -1 | 使用实际分区或 0 |
| SRV-M6 | PulsarProtobufCodec.java:539-541 | PONG 编码带空子消息 | 使用 encodeBaseCommandSimple |
| SRV-M7 | PulsarProtobufCodec.java:740-761 | readVarint 和 readVarint64 重复实现 | 统一用 long 版 |
| SRV-M8 | PulsarFrameDecoder.java:53,94-95 | 解码热路径上使用 log.info | 改为 log.debug |
| SRV-M9 | StorageService.java:142-154 | AgentRegistration 字段/Getter 不一致 | 统一使用 getter 或字段 |
| SRV-M10 | HttpStubHandler.java:135 | result.getRule() 未做 null 防御 | 添加 null 检查 |
| SRV-M11 | UserApiHandler.java:121-189 | CSV 导入未忽略 BOM 头 | 去除首行的 \uFEFF |
| SRV-M12 | UserApiHandler.java:124-184 | 错误信息中英文混用 | 统一英文或国际化 |

---

## Low (6)

| ID | 文件:行 | 问题 |
|----|---------|------|
| SRV-L1 | 多处 | log.info 用字符串拼接而非格式化参数 |
| SRV-L2 | 多处 | 集合字段 null 而非空集合 |
| SRV-L3 | RecordingHelper.java:36 | responseTimeMs 用了 delayMs（已有记录，保留） |
| SRV-L4 | RecordingHelper.java:49,73 | 协议硬编码 http（已有记录，保留） |
| SRV-L5 | HttpStubHandler.java:236-248 | parseQueryParams 未 URL 解码（已有记录） |
| SRV-L6 | StubResponseRenderer.java:104 | body.getBytes 重复调用（已有记录） |

---

## 与历史报告的关系

| 历史编号 | 对应新编号 |
|---------|-----------|
| BUG-4 (Tcp Thread.sleep) | SRV-H9 (部分涵盖) |
| THREAD-2 (CompletableFuture ctx) | 保留为独立项 |
| SEC-1 (SSL) | SRV-C2 |
| SEC-4 (Content-Type) | SRV-L 收集 |
| LEAK-1 (Tcp channel listener) | 保留 |
| SMELL-1 (URL decode) | SRV-L5 |
| SMELL-2 (fragment) | 保留 |
