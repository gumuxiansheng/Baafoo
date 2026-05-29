# Baafoo 架构审查报告 — 增量任务列表

> 审查人：高见远（Gao） · 架构师（两位架构师联合审查）
> 日期：2025-07-26
> 基于：概念设计 v0.8 + PRD v1.5 + 插件架构建议 + 产品建议书 + UI 框架设计 v1.5

---

## 〇、P0 关键缺陷汇总（两位架构师共识）

| # | 缺陷 | 严重度 | 来源 |
|---|------|--------|------|
| P0-1 | Agent 未调用 `appendToBootstrapClassLoaderSearch()`，Advice 类引用非 Bootstrap CL 类将导致 ClassNotFoundException | **致命** | 架构师-1 + 架构师-2 |
| P0-2 | Kafka 拦截点错误：应拦截 KafkaProducer 构造函数替换 `bootstrap.servers`，而非拦截 send() | **严重** | 架构师-2 |
| P0-3 | Pulsar 拦截点错误：应拦截 `ClientBuilder.serviceUrl()`，而非拦截 createProducer/subscribe | **严重** | 架构师-2 |
| P0-4 | Advice 中硬编码 stub 端口 `9000/9001`，多环境部署无法指定 Server 地址 | **严重** | 架构师-2 |
| P0-5 | Agent 参数格式不支持 `config=` 前缀解析 | 中 | 架构师-2 |
| P0-6 | `baafoo-plugin-api/pom.xml` 错误配置了 `Premain-Class` manifest | 中 | 架构师-2 |

---

## 一、现有代码评估

### 已完成（✅ 可复用）
| 模块 | 内容 | 质量 |
|------|------|------|
| baafoo-plugin-api | AgentPlugin/PluginContext/InterceptResult/InterceptTarget SPI 接口 | 良好，与设计文档一致 |
| baafoo-core/model | Rule/Environment/MatchEngine/ApiResponse 等模型 | 良好，参数化规则模型完整 |
| baafoo-core/config | AgentConfig/ServerConfig/ConfigLoader | 良好 |
| baafoo-agent | BaafooAgent premain 入口 | 基本可用，需优化 |
| baafoo-server | BaafooServer Netty 多端口启动 | 基本可用 |
| web/ | Vue 3 + Element Plus 脚手架 + 9 页面视图 | 脚手架完整，需 API 集成 |

### 需重写/大幅修改（⚠️）
| 模块 | 问题 | 严重度 |
|------|------|--------|
| SocketConnectAdvice | @Advice.OnMethodEnter 引用 RouteManager 等非 Bootstrap CL 类，违反 Byte Buddy Advice 内联约束 | **P0 严重** |
| NioSocketConnectAdvice | 同上 | **P0 严重** |
| ConsulDnsAdvice | 同上 + 缺少 Consul HTTP API 拦截 | **P0 严重** |
| KafkaProducerAdvice | 同上 + 仅为 stub | P1 |
| PulsarClientAdvice | 同上 + 仅为 stub | P1 |
| ControlChannel | HTTP 长轮询不完整，缺少心跳/规则拉取/模式切换 | **P0** |
| ManagementApiHandler | 仅有骨架，缺少所有 REST 端点实现 | **P0** |
| PluginManager | 仅为 stub，无 SPI 加载逻辑 | P1 |

### 缺失（❌ 需新建）
| 内容 | 优先级 |
|------|--------|
| baafoo-agent.yml / baafoo-server.yml 配置文件 | P0 |
| Byte Buddy Advice 辅助类（Bootstrap CL 安全） | P0 |
| AgentManifest 动态路由表（AtomicReference 替换） | P0 |
| ConsulHttpInterceptor（HTTP API 响应篡改） | P0 |
| RuleVersionManager（规则版本管理 + Undo） | P1 |
| RecordingManager（录制数据存储/清理） | P1 |
| Web API 集成（Axios 调用 + 实时数据） | P1 |
| Maven Wrapper | P1 |
| 单元测试 | P1 |
| JMS Advice + Plugin | P2 |

---

## 二、增量任务列表（按实现顺序）

### Phase 1：核心拦截修复 + 服务端完善（P0 — 必须）

**T1. 升级依赖版本**
- Byte Buddy 1.14.14（设计文档附录 B 推荐）
- Jackson 2.15.3
- 涉及：`pom.xml`（父 POM dependencyManagement）
- 依赖：无

**T2. 修复 Agent 核心路由架构（最关键）**
- 按照 plugin-arch-advice.md 的"Advice 内联留 Core + 逻辑委托 Plugin"架构，重写路由机制
- 新建 `AgentManifest`（Bootstrap CL 可加载的原子路由表，使用 AtomicReference<RouteTable>）
- 新建 `RouteTable`（仅含原始类型 + String 的路由映射，可被 Advice 类安全引用）
- 重写 `SocketConnectAdvice`：@Advice.OnMethodEnter 仅查 RouteTable + 返回重写地址，不引用任何非 Bootstrap CL 类
- 重写 `NioSocketConnectAdvice`：同上
- 重写 `ConsulDnsAdvice`：同上
- `BaafooAgent.premain` 中初始化 AgentManifest + 启动后台线程加载 RouteManager
- 涉及：
  - `baafoo-agent/src/main/java/com/baaafoo/agent/AgentManifest.java`（新建）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/RouteTable.java`（新建）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/advice/SocketConnectAdvice.java`（重写）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/advice/NioSocketConnectAdvice.java`（重写）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/advice/ConsulDnsAdvice.java`（重写）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/BaafooAgent.java`（修改）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/advice/RouteManager.java`（修改，作为后台服务）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/advice/RoutingContext.java`（修改）
- 依赖：T1

**T3. 实现 ControlChannel HTTP 长轮询**
- Agent 端：启动后台线程，定期 POST /api/agent/register + GET /api/agent/poll
- 处理 Server 下发的模式切换指令
- 处理规则拉取（拉到规则后更新 AgentManifest 的 RouteTable）
- 心跳上报（含 Agent 信息、当前模式、连接数）
- 录制数据上传（POST /api/agent/recordings）
- 涉及：
  - `baafoo-agent/src/main/java/com/baaafoo/agent/channel/ControlChannel.java`（重写）
- 依赖：T2

**T4. 实现 ManagementApiHandler REST 端点**
- 规则管理：GET/POST/PUT/DELETE /api/rules
- 环境管理：GET/POST/PUT/DELETE /api/environments
- Agent 控制：POST /api/agent/register, GET /api/agent/poll, POST /api/agent/heartbeat
- 录制数据：POST /api/agent/recordings
- 场景集：GET/POST/PUT/DELETE /api/scenes
- 健康检查：GET /api/health
- 涉及：
  - `baafoo-server/src/main/java/com/baaafoo/server/api/ManagementApiHandler.java`（重写）
  - `baafoo-server/src/main/java/com/baaafoo/server/storage/FileStorage.java`（完善）
- 依赖：无（可与 T2/T3 并行）

**T5. 新增 Consul HTTP API 拦截**
- 拦截 Consul HTTP API `/v1/health/service/:name` 响应
- 修改返回的 Service 地址为 Baafoo Server 地址
- 新建 ConsulHttpInterceptor Advice
- 涉及：
  - `baafoo-agent/src/main/java/com/baaafoo/agent/advice/ConsulHttpAdvice.java`（新建）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/transform/TransformRegistry.java`（修改）
- 依赖：T2

**T6. 新增配置文件 + Maven Wrapper**
- baafoo-agent.yml（Server 地址、Agent 名称、环境 ID、心跳间隔、日志级别）
- baafoo-server.yml（端口配置、数据目录、Agent 超时）
- Maven Wrapper（mvnw/mvnw.cmd/.mvn/wrapper/）
- 涉及：
  - `baafoo-agent/src/main/resources/baafoo-agent.yml`（新建）
  - `baafoo-server/src/main/resources/baafoo-server.yml`（新建）
  - `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`（新建）
- 依赖：T1

### Phase 2：Web 控制台 + 高级功能（P1 — 应该有）

**T7. Web 控制台 API 集成**
- 完善 web/src/api/index.js 的所有 API 调用
- 串联 Pinia Store 与 API
- 所有页面视图接入真实数据
- 涉及：
  - `web/src/api/index.js`（重写）
  - `web/src/store/index.js`（重写）
  - `web/src/views/*.vue`（修改全部 9 个视图）
- 依赖：T4

**T8. 规则版本管理 + Undo**
- RuleVersionManager：每次规则变更保存版本快照
- 支持回滚到指定版本
- Undo API：POST /api/rules/{id}/undo
- 涉及：
  - `baafoo-server/src/main/java/com/baaafoo/server/storage/RuleVersionManager.java`（新建）
  - `baafoo-server/src/main/java/com/baaafoo/server/api/ManagementApiHandler.java`（修改）
- 依赖：T4

**T9. 录制回放功能**
- 录制：Agent 在 record/record-and-stub 模式下捕获请求+响应
- 存储：Server 端保存录制数据到文件系统
- 回放：从录制数据生成规则
- 清理策略：按时间/数量自动清理
- 涉及：
  - `baafoo-agent/src/main/java/com/baaafoo/agent/channel/ControlChannel.java`（修改）
  - `baafoo-server/src/main/java/com/baaafoo/server/storage/RecordingManager.java`（新建）
  - `baafoo-server/src/main/java/com/baaafoo/server/api/ManagementApiHandler.java`（修改）
- 依赖：T3, T4

**T10. 插件加载器完善**
- PluginClassLoader 使用 parent=null 隔离
- SPI ServiceLoader 加载 AgentPlugin 实现
- 插件生命周期管理（init/destroy）
- 涉及：
  - `baafoo-agent/src/main/java/com/baaafoo/agent/loader/PluginClassLoader.java`（重写）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/plugin/PluginManager.java`（重写）
- 依赖：T2

### Phase 3：完善与测试（P1-P2）

**T11. 单元测试**
- Core 模块：MatchEngine、ConfigLoader、Model 序列化
- Agent 模块：RouteTable、AgentManifest
- Server 模块：HttpStubHandler、ManagementApiHandler
- 涉及：各模块 src/test/java/
- 依赖：T1-T6

**T12. Kafka/Pulsar/JMS Plugin 实现**
- KafkaProducerAdvice + KafkaPlugin
- PulsarClientAdvice + PulsarPlugin
- JmsAdvice + JmsPlugin
- 涉及：
  - `baafoo-agent/src/main/java/com/baaafoo/agent/advice/KafkaProducerAdvice.java`（重写）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/advice/PulsarClientAdvice.java`（重写）
  - `baafoo-agent/src/main/java/com/baaafoo/agent/advice/JmsAdvice.java`（新建）
  - `baafoo-plugin-kafka/`（可选新模块）
  - `baafoo-plugin-pulsar/`（可选新模块）
- 依赖：T2, T10

---

## 三、文件变更清单

| 文件路径 | 变更类型 | 说明 |
|---------|---------|------|
| pom.xml | 修改 | 升级 Byte Buddy/Jackson 版本 |
| baafoo-agent/src/main/java/com/baaafoo/agent/AgentManifest.java | 新增 | Bootstrap CL 安全的原子路由表 |
| baafoo-agent/src/main/java/com/baaafoo/agent/RouteTable.java | 新增 | 仅原始类型的路由映射 |
| baafoo-agent/src/main/java/com/baaafoo/agent/BaafooAgent.java | 修改 | 初始化 AgentManifest |
| baafoo-agent/src/main/java/com/baaafoo/agent/advice/SocketConnectAdvice.java | 重写 | 符合 Advice 内联约束 |
| baafoo-agent/src/main/java/com/baaafoo/agent/advice/NioSocketConnectAdvice.java | 重写 | 符合 Advice 内联约束 |
| baafoo-agent/src/main/java/com/baaafoo/agent/advice/ConsulDnsAdvice.java | 重写 | 符合 Advice 内联约束 |
| baafoo-agent/src/main/java/com/baaafoo/agent/advice/RouteManager.java | 修改 | 作为后台服务而非 Advice 依赖 |
| baafoo-agent/src/main/java/com/baaafoo/agent/advice/ConsulHttpAdvice.java | 新增 | Consul HTTP API 拦截 |
| baafoo-agent/src/main/java/com/baaafoo/agent/channel/ControlChannel.java | 重写 | 完整 HTTP 长轮询实现 |
| baafoo-agent/src/main/java/com/baaafoo/agent/transform/TransformRegistry.java | 修改 | 注册 ConsulHttpAdvice |
| baafoo-agent/src/main/resources/baafoo-agent.yml | 新增 | Agent 配置文件 |
| baafoo-server/src/main/java/com/baaafoo/server/api/ManagementApiHandler.java | 重写 | 完整 REST API |
| baafoo-server/src/main/java/com/baaafoo/server/storage/FileStorage.java | 修改 | 完善存储操作 |
| baafoo-server/src/main/java/com/baaafoo/server/storage/RuleVersionManager.java | 新增 | 规则版本管理 |
| baafoo-server/src/main/java/com/baaafoo/server/storage/RecordingManager.java | 新增 | 录制数据管理 |
| baafoo-server/src/main/resources/baafoo-server.yml | 新增 | Server 配置文件 |
| web/src/api/index.js | 重写 | 完整 API 调用 |
| web/src/store/index.js | 重写 | Store 集成 API |
| web/src/views/*.vue | 修改 | 9 个页面接入真实数据 |
| mvnw / mvnw.cmd / .mvn/ | 新增 | Maven Wrapper |

## 四、依赖包变更

| 包名 | 旧版本 | 新版本 | 原因 |
|------|--------|--------|------|
| byte-buddy | 1.12.19 | 1.14.14 | 设计文档附录 B 推荐，支持 Java 8 + 改进 Advice 内联 |
| jackson-databind | 2.13.4 | 2.15.3 | 设计文档附录 B 推荐，安全修复 |
| jackson-core | 2.13.4 | 2.15.3 | 与 databind 对齐 |
| jackson-annotations | 2.13.4 | 2.15.3 | 与 databind 对齐 |
| netty-all | 4.1.84 | 4.1.100.Final | 稳定性改进 |

## 五、跨文件约定

1. **Advice 类铁律**：@Advice.OnMethodEnter/OnMethodExit 中只能引用 Bootstrap ClassLoader 可加载的类（java.* + AgentManifest/RouteTable），绝对不能引用 RouteManager/ConfigLoader 等应用类
2. **AgentManifest 单例**：通过 static AtomicReference 持有 RouteTable，由 BaafooAgent.premain 初始化，后台线程原子替换
3. **配置格式**：统一 YAML，Agent 配置前缀 `baafoo.agent`，Server 配置前缀 `baafoo.server`
4. **API 路径**：所有 REST API 以 `/api/` 为前缀，Web 控制台以 `/__baafoo__/` 为前缀
5. **API 响应格式**：统一使用 `ApiResponse<T>` 包装，包含 code/message/data
6. **Java 8 兼容**：不使用 Lombok，不使用 Java 9+ API
7. **包名规范**：`com.baafoo`（已确认）
8. **API 路径前缀**（架构师-2 补充）：Server 端 API 路径为 `/__baafoo__/api/*`，Web 控制台静态文件为 `/__baafoo__/*`，前端 baseURL 应对齐
9. **端口映射统一来源**（架构师-2 补充）：所有 stub 端口映射必须来自 `AgentConfig.stubPortMapping`（Map<Protocol, Integer>），禁止硬编码
10. **Agent fail-closed**（架构师-2 补充）：premain 方法必须用 try-catch 包裹，加载失败时打印 ERROR 日志，请求透传（不拦截也不崩溃）
11. **规则版本校验**（架构师-2 补充）：规则更新必须携带 version 字段，Server 端比对 version 避免旧覆盖新

## 六、v1.0 MVP 范围

**Phase 1（本次迭代目标）= T1-T6**：
- Agent 核心拦截修复（Socket + NIO + Consul DNS + Consul HTTP API）
- ControlChannel 完整实现
- Server Management API 完整实现
- 配置文件 + Maven Wrapper
- 编译通过 + 基本运行验证

**Phase 1 不含**：
- Kafka/Pulsar/JMS Mock（P1，Phase 2）
- 录制回放（P1，Phase 2）
- Web 控制台 API 集成（P1，Phase 2）
- 规则版本管理（P1，Phase 2）
- 单元测试（Phase 3）

**判断依据**：产品建议书 v1.0 范围建议 — 基于技术风险收窄，先确保"拦截→重写→挡板响应"链路端到端跑通。
