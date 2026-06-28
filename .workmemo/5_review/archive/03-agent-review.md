# baafoo-agent 详细审查报告

> 共审查 27 个源文件，发现 29 个问题 (Critical: 5, High: 6, Medium: 8, Low: 10)

---

## Critical (5)

### AGT-C1: RouteManager.setMode() 未更新 App CL 的 `GlobalRouteState.CURRENT_MODE`
- **文件**: `RouteManager.java:82-84`
- **描述**: `setMode()` 设置 `AgentManifest.currentMode` 并同步到 Bootstrap CL，但**没有更新 `GlobalRouteState.CURRENT_MODE`**（App CL 副本）。第一次模式变更后，所有 App CL advice 读到的是过期值。
- **代码**:
  ```java
  // 错误：跳过了 GlobalRouteState.CURRENT_MODE 的更新
  AgentManifest.currentMode = modeValue;        // 只设 AgentManifest
  syncModeToBootstrapCL(modeValue);             // 只设 Bootstrap CL
  // 应改为：AgentManifest.setCurrentMode(modeValue);  // 同时更新 GlobalRouteState
  ```
- **建议**: 替换为 `AgentManifest.setCurrentMode(modeValue)`

### AGT-C2: GlobalRouteState.startRecording() TOCTOU 竞争
- **文件**: `GlobalRouteState.java:250-255`
- **描述**: `size()` 检查和 `clear()` 不是原子操作。线程 A 看到 size >= MAX 并 clear，线程 B 在之间 put 了数据，然后被 A 的 clear 清除。
- **建议**: 使用 check-after-put 模式或 LRU 淘汰策略代替 `clear()`

### AGT-C3: RecordingBuffer.flush() 与 add() 竞态导致数据丢失
- **文件**: `RecordingBuffer.java:95-121`
- **描述**: `flush()` 从 timer 和 `add()` 都可能被调用。`pendingRetry` 是普通 `ArrayList`（非线程安全）。snapshot 和 clear 之间的时间窗内 add 的数据会丢失。
- **建议**: 使用 `synchronized` 块锁定 drain-staging 操作；`pendingRetry` 改用线程安全结构

### AGT-C4: GlobalRouteState.recordDns() 缓存满时丢弃新条目
- **文件**: `GlobalRouteState.java:158-173`
- **描述**: 缓存满时，CAS 胜出的线程 clear 后**不添加当前触发**条目；失败的线程直接 return 也不会重试。当前条目永久丢失。
- **建议**: clear 后一定添加当前条目；失败的线程应自旋等待或重试

### AGT-C5: RouteManager 同步到 Bootstrap CL 时非原子 clear+putAll
- **文件**: `RouteManager.java:277-295`
- **描述**: `bootRoutes.clear()` 后 `bootRoutes.putAll(newBootRoutes)` 之间，Bootstrap CL 上运行的 advice（如 SocketConnectAdvice）读取 ROUTES 会看到空/部分 Map。
- **建议**: 构建新 ConcurrentHashMap 后用 volatile 引用替换（App CL 侧已使用此模式）

---

## High (6)

### AGT-H1: ConsulDnsAdvice 和 ConsulHttpAdvice 异常被静默吞没
- **文件**: `ConsulDnsAdvice.java:71` / `ConsulHttpAdvice.java:28-29`
- **描述**: `catch (Throwable t)` 内空代码块。任何 Consul 重定向错误完全不可见。
- **建议**: 至少记录 debug 级别日志

### AGT-H2: Java 9+ 模块系统下 findBootstrapClass 失败
- **文件**: `BaafooAgent.java:467-477`
- **描述**: Java 9+ 上 `getSystemClassLoader().getParent()` 返回 PlatformClassLoader（非 null），循环停在那里，`Class.forName(name, false, platformCL)` 找不到 Bootstrap CL 中的类。
- **建议**: 直接使用 `Class.forName(name, false, null)` 搜索 Bootstrap CL

### AGT-H3: ControlChannel.pollRules() agentId 未 URL 编码
- **文件**: `ControlChannel.java:207-208`
- **描述**: `agentId` 直接拼接到 URL，如果含 `&`/`=`/`#` 等字符会导致 URL 畸形。
- **建议**: 使用 `URLEncoder.encode(config.getAgentId(), "UTF-8")`

### AGT-H4: RecordingBuffer.start() 双启动竞争
- **文件**: `RecordingBuffer.java:71-77`
- **描述**: `flushTask == null` 检查未同步，并发调用 `start()` 可创建两个定时任务。
- **建议**: 同步或使用 AtomicReference 保护 flushTask

### AGT-H5: 模式变更后 Bootstrap CL 的 CURRENT_MODE 仍过时
- **文件**: `BaafooAgent.java:443` + `RouteManager.java:82-84`
- **描述**: 初始同步后，任何 `RouteManager.setMode()` 都不更新 App CL 的 `GlobalRouteState.CURRENT_MODE`（参见 AGT-C1）。Bootstrap CL 的 initial sync 只复制了默认值。
- **建议**: 修复 AGT-C1 即可解决此问题

### AGT-H6: 共享 EventLoopGroup + sun.nio.ch.SocketChannelImpl JDK 特定
- **文件**: `BaafooAgent.java:268-272`
- **描述**: `sun.nio.ch.SocketChannelImpl` 是特定 JDK 实现的私有类。非 HotSpot JDK 上可能不存在；Java 9+ 上模块封装需要 `--add-opens`。
- **建议**: 添加 `NoClassDefFoundError` 回退或使用公共 API

---

## Medium (8)

| ID | 文件:行 | 问题 | 建议 |
|----|---------|------|------|
| AGT-M1 | RouteTable.java:66-72 | getRoutes() / getServiceNames() 暴露内部可变 Map | 返回 unmodifiableMap |
| AGT-M2 | RecordingOutputStream:40 / RecordingInputStream:40 | 单字节写入创建 byte[1] 数组 + 完整 RecordingEntry | 单字节走快路径 |
| AGT-M3 | DnsResolutionAdvice.java:全文件 | 死代码 + ByteBuddy 重复 advice 风险 | 移除或标记 |
| AGT-M4 | PluginClassLoader.java:39 | SPI 类加载路径用 getSystemClassLoader() | 缓存 AgentPlugin.class 的 defining loader |
| AGT-M5 | PulsarClientAdvice.java:49 | 硬编码 `pulsar://` 协议，丢失 `pulsar+ssl://` | 解析原始 URL scheme 复用 |
| AGT-M6 | RecordingBuffer.java:42 | pendingRetry 无界增长 | 添加最大容量限制和 TTL 淘汰 |
| AGT-M7 | JmsConnectionFactoryAdvice:51-54 | 每次构造调用反射 getMethod | 缓存 Method 引用为 static final |
| AGT-M8 | ControlChannel.java:187-242 | 无指数退避重连 | 实现退避策略 (1s→2s→4s→max 30s) |

---

## Low (10)

| ID | 文件:行 | 问题 |
|----|---------|------|
| AGT-L1 | GlobalRouteState.java:193-206 | lookupByHost O(n) 遍历路由表（大规则集下性能问题） |
| AGT-L2 | RouteManager.java:167 | version 计数器始终重置为 1，两次连续更新无法区分 |
| AGT-L3 | RecordingInputStream:75 / RecordingOutputStream:74 | 每字节用 String.format("%02x")，预计算表格更好 |
| AGT-L4 | PluginManager.java:24,38 | @Deprecated 但实际被实例化，启动时扫描磁盘浪费 I/O |
| AGT-L5 | SocketConnectAdvice:35-99 / NioSocketConnectAdvice:35-86 | ~90% 重复代码 |
| AGT-L6 | BaafooAgent.java:247 | TransformRegistry 中使用已废弃的 "DnsResolutionAdvice" 名称 |
| AGT-L7 | BaafooAgent.java:486 | maxBufferSize 硬编码 100 |
| AGT-L8 | GlobalRouteState.java:19,72,97 | 全局可变 public static 字段（设计如此，但攻击面大） |
| AGT-L9 | ControlChannel.java:187-203 | heartbeat() 忽略响应体，错过服务端返回的模式变更 |
| AGT-L10 | DnsGetByNameAdvice:50-52 / DnsGetAllByNameAdvice:50-52 | Throwable 被静默捕获，DNS 拦截失败完全不可见 |

---

## 与历史报告的关系

| 历史编号 | 对应新编号 |
|---------|-----------|
| THREAD-1 (RouteManager) | AGT-C5 (扩展) |
| THREAD-3 (patternCache) | CORE-M7 (已移至 core) |
| LEAK-3 (ControlChannel) | AGT-M8 |
| FINDING-1 (Bootstrap CL) | AGT-C1 / AGT-H5 (重新分析) |
