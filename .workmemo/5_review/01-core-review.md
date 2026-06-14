# baafoo-core 详细审查报告

> 共审查 20 个主要源文件，发现 23 个问题 (Critical: 3, High: 2, Medium: 10, Low: 8)

---

## Critical (3)

### CORE-C1: ServerConfig.AuthConfig JWT Secret 和 API Keys 通过 Jackson 序列化泄露
- **文件**: `ServerConfig.java:187-213` (AuthConfig 内部类)
- **描述**: `getJwtSecret()` 和 `getApiKeys()` 是标准 getter，Jackson 在序列化 `AuthConfig` 时会输出这些敏感字段。任何返回配置信息的 API 都会泄露凭据。
- **建议**: 添加 `@JsonIgnore` 到两个 getter；字段上使用 `@JsonProperty(access = WRITE_ONLY)`

### CORE-C2: User.java passwordHash 和 apiKey 通过 getter 泄露
- **文件**: `User.java:26-27, 38-39`
- **描述**: `getPasswordHash()` 和 `getApiKey()` 会在任何返回 User 对象的 API 响应中序列化，泄露密码哈希和 API 密钥（即使哈希本身，也是可彩虹表攻击的）。
- **建议**: 添加 `@JsonIgnore`；考虑使用 DTO 层分离内部字段

### CORE-C3: PaginatedResult size<=0 时除零崩溃
- **文件**: `PaginatedResult.java:34, 49`
- **描述**: `Math.ceil((double) total / size)` 当 `size <= 0` 时造成整数除零或除零异常。
- **建议**: 计算前加 `if (size <= 0) size = 1;`

---

## High (2)

### CORE-H1: MatchEngine 多值 HTTP Header 丢失
- **文件**: `MatchEngine.java:181-185`
- **描述**: 使用 `Map<String, String>` 存储 headers，多个同名 header（如 Set-Cookie、Accept）只有最后一个值被保留。
- **建议**: 改为 `Map<String, List<String>>` 或明确合并值并文档化局限

### CORE-H2: TemplateEngine JSON 根级数组导航损坏
- **文件**: `TemplateEngine.java:140-182`
- **描述**: JSONPath 为 `[0].name` 时，因为 root node 是数组，`node.has("")` 返回 false，导致空字符串。
- **建议**: 当 arrayField 为空时，直接将 part 作为数组索引处理

---

## Medium (10)

| ID | 文件:行 | 问题 | 建议 |
|----|---------|------|------|
| CORE-M1 | PaginatedResult.java:48-52 | `setTotal()` 重算 totalPages 但 `setSize()` 不重算 | 让 `getTotalPages()` 动态计算，或在 `setSize()` 中也重算 |
| CORE-M2 | PaginatedResult.java:55 | `setTotalPages(int)` 是 public，可被外部覆盖导致不一致 | 移除 setter 或设为 private |
| CORE-M3 | AgentConfig.java:99 | getProtocols() 返回可变内部引用 | 返回 `Collections.unmodifiableList()` |
| CORE-M4 | ConfigLoader.java:34,54,76,88 | 无路径穿越防护 | `path.normalize()` + 限制到配置目录 |
| CORE-M5 | ServerConfig.java:94,97 | getProtocolPorts() 返回可变 Map；未知协议返回 0 | unmodifiableMap + 返回 -1 或抛异常 |
| CORE-M6 | Rule.java:110-137 | 多处集合返回可变引用 | unmodifiable 包装 |
| CORE-M7 | MatchEngine.java:257-258 | patternCache 边界检查与 putIfAbsent 非原子 | 使用 bounded ConcurrentLinkedHashMap |
| CORE-M8 | MatchEngine.java:140-158 | 无匹配时静默返回 index 0，可能错误匹配 | 返回 -1 让调用方处理 |
| CORE-M9 | IdGenerator.java:18-20 | uuid() 截取到 16 位，碰撞概率增加 | 使用完整 32 位 UUID |
| CORE-M10 | TemplateEngine.java:56-64 | 使用 StringBuffer（同步）代替 StringBuilder | 替换为 StringBuilder |

---

## Low (8)

| ID | 文件:行 | 问题 |
|----|---------|------|
| CORE-L1 | ApiResponse.java:84-90 | toString() 遗漏 data 和 timestamp |
| CORE-L2 | ServerConfig.java:84-85 | serverUrl/setServerUrl 声称被覆盖但无覆盖逻辑 |
| CORE-L3 | Protocol.java:7 | name 字段遮蔽 Enum.name，getName() 与 name() 不一致 |
| CORE-L4 | EnvironmentMode.java:28-42 | fromValue() 对 null/未知输入静默默认 STUB |
| CORE-L5 | FakerProvider.java:398-404 | randomDate/randomDateTime 每次创建 SimpleDateFormat（非线程安全） |
| CORE-L6 | FakerProvider.java:44 | SecureRandom 过重，可用 Random |
| CORE-L7 | IdGenerator.java:11 | SEQ 初始化用 currentTimeMillis，重启后可能重复 |
| CORE-L8 | TemplateEngine.java:150-163 | 数组索引负数未校验（`node.get(-1)` 返回 null） |

---

## 与历史报告的关系

| 历史编号 | 对应新编号 | 状态 |
|---------|-----------|------|
| BUG-7 (patternCache) | CORE-M7 | 保留，重新分类 |
| SMELL-4 (bodyJsonPath) | CORE-M7 附近 | 保留 |
| — | CORE-C1/CORE-C2 (凭据泄露) | **新增重大发现** |
| — | CORE-C3 (除零) | **新增** |
| — | CORE-H1 (Header 丢失) | **新增** |
| — | CORE-H2 (JSON 数组导航) | **新增** |
