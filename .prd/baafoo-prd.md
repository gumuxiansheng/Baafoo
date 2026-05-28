# Baafoo 挡板系统 — 产品需求文档（PRD）

> **文档状态**：PRD v1.0  
> **目标读者**：产品团队、工程团队、QA 团队  
> **关联文档**：[概念设计说明书 v0.3](../.concepts/baafoo-concept-design.md)  
> **最后更新**：2026-05-28

---

## 1. 问题陈述

在微服务/分布式系统的日常开发中，一个 Java 应用通常依赖多个下游服务（REST API、Socket 服务、消息队列如 Kafka/Pulsar/JMS 等）。开发人员在本地开发或联调阶段面临以下核心痛点：

- **下游环境不稳定**：下游开发环境频繁重启、接口变更、数据脏污，导致联调阻塞，开发人员平均每天浪费 30-60 分钟在等待和排查上；
- **下游不可达**：预发布/生产环境的下游服务无法从开发机直接访问，开发人员被迫"猜测"接口行为或远程调试；
- **异常场景难复现**：超时、断链、错误码、消息乱序等边界场景需要下游团队配合构造，沟通成本高且不可控；
- **协同等待**：下游团队进度滞后时，上游开发完全停滞，形成瀑布式阻塞链。

**谁受影响**：Java 后端开发人员（主要）、QA 测试工程师（次要）、DevOps/平台工程师（次要）。

**不解决的代价**：开发效率持续受制于外部依赖的稳定性；交付周期延长；测试覆盖率不足（异常路径无法验证）；团队间耦合度居高不下。

**现有方案局限**：Mockito/PowerMock 侵入业务代码；WireMock 仅支持 HTTP；hosts 修改影响系统全局；Proxy 环境变量无法覆盖 TCP 私有协议和消息队列。

---

## 2. 目标

| # | 目标 | 类型 | 衡量标准 |
|---|---|---|---|
| G1 | 开发人员可在 **5 分钟内** 完成挡板环境搭建，无需修改业务代码 | 用户目标 | 从零到首次拦截成功的平均耗时 ≤ 5 分钟 |
| G2 | 覆盖团队 90% 的下游依赖协议类型（HTTP/TCP/Kafka/Pulsar/JMS）| 业务目标 | 协议覆盖矩阵中"完全支持"项 ≥ 5 种 |
| G3 | 挡板模式与透传模式之间 **零重启** 切换 | 用户目标 | 修改配置文件后 < 500ms 生效，应用进程不重启 |
| G4 | 开发阶段因下游依赖导致的阻塞时间降低 **70%** | 业务目标 | 上线后 4 周内，团队匿名调研中"下游阻塞"耗时下降 ≥ 70% |
| G5 | 支持 Consul 注册中心架构，按**服务名**而非 IP 管理挡板规则 | 用户目标 | Agent 在 Consul DNS + HTTP API 两种模式下均能正确拦截 |

---

## 3. 非目标（Non-Goals）

| # | 非目标 | 原因 |
|---|---|---|
| N1 | **不覆盖非 JVM 语言**（Go/Python/Node.js 应用） | JavaAgent 技术天然限制；非 JVM 场景建议使用 Sidecar Proxy 或 iptables 方案 |
| N2 | **不覆盖 UDP 协议** | Agent 拦截点在 TCP Socket 层面，UDP 走 `DatagramSocket`，需完全不同的拦截策略，v1 不做 |
| N3 | **不覆盖 gRPC/HTTP2 流式 RPC** | gRPC 基于 HTTP/2 多路复用，字节码拦截复杂度显著高于 HTTP/1.1；v1 聚焦 Unary 调用 + 基础协议 |
| N4 | **不替代生产环境流量录制** | Baafoo 定位是**开发阶段**挡板工具，不承担生产流量镜像、压力测试等职责 |
| N5 | **不提供图形化挡板规则编辑器** | v1 以 YAML 配置文件为核心交互界面；Web 控制台（v1.5）仅提供查看和规则管理 REST API |
| N6 | **不修改 Consul 注册中心的数据** | Agent 只读 Consul 查询结果，不向 Consul 写入或修改任何注册信息 |

---

## 4. 用户故事

### 4.1 角色定义

| 角色 | 描述 |
|---|---|
| **后端开发人员（主要）** | 日常需要调用多个下游 REST/Socket/MQ 服务进行本地开发联调 |
| **QA 测试工程师** | 需要在测试环境中模拟特定下游响应（边界数据、异常场景） |
| **DevOps/平台工程师** | 负责开发环境基础设施建设，关心部署便利性和资源占用 |

### 4.2 用户故事列表（按优先级排序）

#### P0（Must-Have — 没有这些功能产品不可发布）

**US-01：附加 Agent 启动应用**
> 作为 **后端开发人员**，我希望只需在 JVM 启动参数中增加 `-javaagent` 即可启用挡板功能，以便我无需修改任何业务代码，也无需改变现有的构建和部署流程。

**US-02：HTTP 请求挡板**
> 作为 **后端开发人员**，我希望配置 Mock 规则后，应用对下游 REST API 的 HTTP 请求能自动返回我预设的响应（状态码、Header、Body、延迟），以便我可以在下游不可用或数据不符合预期时继续开发和自测。

**US-03：配置文件热切换挡板/透传模式**
> 作为 **后端开发人员**，我希望修改挡板配置文件后，无需重启应用即可在"挡板模式"和"透传模式"之间切换，以便我可以随时按需对比真实下游行为与挡板行为。

**US-04：TCP Socket 字节级挡板**
> 作为 **后端开发人员**，我希望对使用私有 TCP 协议的下游服务也能配置挡板规则（按字节前缀/正则匹配请求，返回预定义字节序列），以便即便下游是二进制协议我也能独立开发。

**US-05：Consul 注册中心适配**
> 作为 **后端开发人员**，在 Consul 微服务架构下，我希望挡板规则能按**服务名**（如 `order-service`）而非具体 IP 来配置，以便我不需要关心服务实例的动态变化。

#### P1（Should-Have — 显著改善体验，核心路径可用但不完整时可接受）

**US-06：Kafka 消息挡板**
> 作为 **后端开发人员**，我希望 Baafoo 能模拟 Kafka Broker，让我的 Kafka Producer 能正常发送消息、Consumer 能收到预设消息序列，以便我在不依赖真实 Kafka 集群的情况下开发和测试事件驱动逻辑。

**US-07：Pulsar / TDMQ 消息挡板**
> 作为 **后端开发人员**，使用腾讯云 TDMQ for Pulsar 的项目中，我希望 Baafoo 能模拟 Pulsar Broker，让 Producer/Consumer 在本地正常运行，以便我不依赖云端 TDMQ 实例即可完成开发。

**US-08：JMS 消息挡板**
> 作为 **后端开发人员**，我希望 Baafoo 能模拟 JMS Broker（ActiveMQ 协议），让我的 JMS Producer/Consumer 在本地正常运行，以便覆盖使用传统 MQ 的老系统。

**US-09：请求录制与回放**
> 作为 **后端开发人员**，我希望在透传模式下 Agent 能自动录制真实下游的请求和响应，后续切换到挡板模式时可以回放这些录制数据，以便我能用真实交互数据来驱动 Mock，减少手动编写挡板规则的工作量。

**US-10：请求日志查看**
> 作为 **后端开发人员 / QA 测试工程师**，我希望查看每次挡板匹配的请求详情（来源进程、协议类型、请求摘要、匹配规则、响应内容、耗时），以便我调试验证挡板行为是否符合预期。

#### P2（Future Considerations — v1 不实现但设计需预留扩展点）

**US-11：Web 控制台**
> 作为 **QA 测试工程师**，我希望通过浏览器可视化查看和管理挡板规则、查看请求日志和流量统计，以便我无需直接编辑 YAML 文件也能操作挡板。

**US-12：CI/CD 集成（Maven/Gradle 插件）**
> 作为 **DevOps/平台工程师**，我希望在 CI 流水线中自动启停 Baafoo Server，并在集成测试阶段注入挡板规则，以便实现"下游隔离"的自动化测试环境。

**US-13：gRPC / WebSocket 支持**
> 作为 **后端开发人员**，使用 gRPC 或 WebSocket 协议的项目中，我希望 Baafoo 也能模拟这些协议的下游，以便覆盖更广泛的微服务通信场景。

---

## 5. 需求明细

### 5.1 Baafoo Agent（JavaAgent 模块）

#### R-A1：Agent 加载与生命周期 — P0

| 属性 | 内容 |
|---|---|
| **描述** | Agent 通过 `-javaagent` JVM 参数在应用启动前完成字节码增强。Agent 应在 `premain` 阶段注册 `ClassFileTransformer`，在目标类（`java.net.Socket`、`sun.nio.ch.SocketChannelImpl` 等）被加载时完成字节码注入。 |
| **AC-01** | 应用启动命令增加 `-javaagent:baafoo-agent.jar=config=<path>` 后，Agent 日志输出 "Baafoo Agent initialized" 并列出已激活的拦截点 |
| **AC-02** | 未配置 `-javaagent` 时，目标应用启动和行为与正常无差异（零影响） |
| **AC-03** | Agent 加载失败（如配置文件不存在、字节码变换异常）时，应用应**正常启动**并以 `passthrough` 模式运行（全部透传），同时输出 WARN 日志 |
| **AC-04** | Java 8 环境下正常运行，Java 9+ 环境下需支持的额外 JVM 参数在文档中明确列出 |
| **技术约束** | 使用 Byte Buddy 1.14.x 实现字节码增强；Agent jar 需通过 `appendToBootstrapClassLoaderSearch()` 处理 Bootstrap ClassLoader 隔离问题 |

#### R-A2：Socket 连接拦截 (`java.net.Socket`) — P0

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `Socket.connect(SocketAddress)` 方法，在连接建立前根据路由规则判断是否需要重写目标地址。拦截范围覆盖 OkHttp、Apache HttpClient、JDBC Driver、原生 Socket 等所有基于 `java.net.Socket` 的网络框架。 |
| **AC-01** | 配置某 `host:port` 为 `stub` 模式后，应用对该地址的 `Socket.connect()` 实际连接到 Baafoo Server |
| **AC-02** | 配置为 `passthrough` 的 `host:port`，连接行为与未装 Agent 完全一致 |
| **AC-03** | 拦截逻辑导致的额外延迟在 P99 场景下不超过 1ms |
| **AC-04** | 连接建立失败时（如 Baafoo Server 未启动），Agent 应输出明确错误日志，并可选降级为 `passthrough` |

#### R-A3：NIO SocketChannel 拦截 — P0

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `sun.nio.ch.SocketChannelImpl#connect()` 方法，覆盖 Netty 及基于 NIO 的框架。 |
| **AC-01** | 使用 Netty 发起的 TCP 连接同样被拦截和地址重写 |
| **AC-02** | ASM 改写兼容 JDK 8 / 11 / 17（多版本分支） |
| **技术约束** | 使用 ASM 直接操作字节码，需针对不同 JDK 版本维护适配分支 |

#### R-A4：Kafka Client 拦截 — P1

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `KafkaProducer` / `KafkaConsumer` 的构造函数，将 `bootstrap.servers` 配置替换为 Baafoo Server 中 Kafka Mock Broker 的地址。 |
| **AC-01** | 配置 `protocol: kafka` 规则后，Producer `send()` 返回正常元数据，不抛连接异常 |
| **AC-02** | Consumer `poll()` 能收到规则中预设的消息序列 |
| **AC-03** | 未配置 Kafka 规则时，Kafka 客户端行为不变化（透传） |

#### R-A5：Pulsar Client 拦截 — P1

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `org.apache.pulsar.client.api.PulsarClient#builder()` 及 `ClientBuilder#serviceUrl()` 方法，将 `pulsar://broker:6650`（或 TDMQ 地址）替换为 Baafoo Server 中 Pulsar Mock Broker 的 `pulsar://localhost:9002`。 |
| **AC-01** | 同时覆盖 `org.apache.pulsar:pulsar-client`（Apache 官方 SDK）和 `com.tencent.tdmq:tdmq-client`（腾讯云封装 SDK） |
| **AC-02** | Producer `send()` 返回 MessageId，Consumer `receive()` 能收到规则预设消息 |
| **AC-03** | 拦截仅在 `PulsarClient` 通过 Builder 模式构造时生效（覆盖 95%+ 场景）；直接反射调用构造函数的边缘场景文档说明 |

#### R-A6：JMS ConnectionFactory 拦截 — P1

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `javax.jms.ConnectionFactory#createConnection()` 方法，将返回的 Connection 对象替换为指向 Baafoo Server 内嵌 JMS Broker 的代理对象。 |
| **AC-01** | ActiveMQ JMS 客户端创建的 Connection/Queue/Topic 对象功能正常 |
| **AC-02** | 配置的 Queue/Topic 名称与规则匹配 |

#### R-A7：Consul 服务发现拦截（DNS 模式）— P0

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `java.net.InetAddress#getByName()` / `getAllByName()`，识别以 `.service.consul` 结尾的域名，从域名中提取服务名，查询路由表判断是否需要挡板。若需要，返回挡板地址对应的 `InetAddress`。 |
| **AC-01** | `getByName("order-service.service.consul")` 被正确拦截，提取服务名为 `order-service` |
| **AC-02** | 未匹配到挡板规则的服务名，DNS 解析行为不变（透传） |
| **AC-03** | 不支持 `.consul` 后缀的普通域名不受影响 |

#### R-A8：Consul 服务发现拦截（HTTP API 模式）— P0

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 HTTP 客户端对 Consul Agent 的 REST API 调用（`/v1/catalog/service/*`、`/v1/health/service/*`），匹配 URL 路径中的服务名。若需要挡板，不发起真实 HTTP 请求，直接构造响应 JSON 将 Address/Port 替换为挡板地址。 |
| **AC-01** | Spring Cloud Consul 通过 HTTP API 做服务发现时，返回的实例地址被替换为挡板地址 |
| **AC-02** | 对 `127.0.0.1:8500` 的健康检查调用**不受拦截**（避免 Consul 误判服务不健康） |
| **AC-03** | 兼容 Orbitz、Ecwid、Spring Cloud Consul 等主流 Consul SDK |

#### R-A9：路由规则引擎 — P0

| 属性 | 内容 |
|---|---|
| **描述** | 路由规则引擎根据请求目标匹配规则，决定连接的处理模式。支持两种维度匹配：**host:port 精确/通配** 和 **服务名**。服务名匹配优先级高于 host:port 匹配。 |
| **AC-01** | 精确匹配 `192.168.1.100:8080` — 正确命中 |
| **AC-02** | 通配匹配 `*.dev:*` — 命中 `api.dev:8080`、`db.dev:5432` |
| **AC-03** | 服务名匹配 `order-service` — 命中所有 Consul 解析到 order-service 的连接 |
| **AC-04** | 同时配置服务名规则和 host:port 规则时，服务名规则优先 |
| **AC-05** | 未匹配任何规则时，默认 `passthrough`（透传） |
| **AC-06** | 规则配置文件变化后，< 500ms 内热加载生效（基于 WatchService） |

#### R-A10：录制器 — P1

| 属性 | 内容 |
|---|---|
| **描述** | 在 `record` 或 `record-and-stub` 模式下，Agent 透明代理真实连接，同时将请求和响应的原始字节复制到本地文件系统。存储格式：JSON 元数据文件 + 原始字节 blob 文件，以 session ID 组织。 |
| **AC-01** | 录制模式下，应用与真实下游的交互不受影响 |
| **AC-02** | 切换到挡板模式后，能选择录制 session 进行回放 |
| **AC-03** | HTTP 请求录制包含：method、URL、headers、body、响应 status、响应 headers、响应 body、耗时 |
| **AC-04** | TCP 请求录制包含：请求 bytes（hex）、响应 bytes（hex）、时间戳 |

---

### 5.2 Baafoo Server（挡板服务模块）

#### R-S1：HTTP Mock Handler — P0

| 属性 | 内容 |
|---|---|
| **描述** | 接收被 Agent 重定向的 HTTP 请求，按规则匹配并返回 Mock 响应。请求匹配维度：`method + path + query + headers + body（JSONPath/正则）`。响应配置：`status code + headers + body（模板变量）`。 |
| **AC-01** | 精确路径匹配 `GET /api/users/123` 返回预设 JSON body |
| **AC-02** | 路径参数匹配 `GET /api/users/{id}` 并使用 `{{path.id}}` 模板变量填充响应 |
| **AC-03** | 请求 body JSONPath 匹配（如 `$.orderType == "VIP"` 返回特定响应） |
| **AC-04** | 规则可配置响应延迟（固定/随机区间/正态分布），单位 ms |
| **AC-05** | 支持异常模拟：`READ_TIMEOUT`（读超时）、`CONNECTION_RESET`（连接重置）、`HTTP_502` 等 |
| **AC-06** | 未匹配到任何 HTTP 规则时，返回 404 并记录 WARN 日志 |

#### R-S2：Raw TCP Mock Handler — P0

| 属性 | 内容 |
|---|---|
| **描述** | 接收非 HTTP 协议的 TCP 连接，按字节级规则匹配请求并返回 Mock 字节序列。 |
| **AC-01** | 按请求前缀 hex 值匹配（如 `01 02 03` → 返回 `01 02 00 00 00 01`） |
| **AC-02** | 支持正则匹配原始字节 |
| **AC-03** | 支持长连接多轮交互模拟（请求1→响应1→请求2→响应2） |
| **AC-04** | 支持录制回放模式（匹配 request bytes → 返回录制的 response bytes） |

#### R-S3：Kafka Mock Broker — P1

| 属性 | 内容 |
|---|---|
| **描述** | 模拟 Kafka Broker 的最小协议子集，支持 Producer 发送和 Consumer 拉取。 |
| **AC-01** | Producer `send()` 正常返回 RecordMetadata（含 offset） |
| **AC-02** | Consumer 按 subscription 收到预设消息序列 |
| **AC-03** | 支持配置消息投递延迟（`delay`）和 ack 模式 |
| **AC-04** | 支持 topic 通配符订阅 |

#### R-S4：Pulsar Mock Broker — P1

| 属性 | 内容 |
|---|---|
| **描述** | 模拟 Pulsar Broker 的最小协议子集（基于 Pulsar binary protocol，默认端口 6650），支持 Producer/Consumer 基础交互。 |
| **AC-01** | Producer `send()` 正常返回 MessageId |
| **AC-02** | Consumer `receive()` 按 subscription 收到预设消息序列 |
| **AC-03** | 支持 tenant/namespace/topic 三级隔离 |
| **AC-04** | 支持 Producer 配置的 `delay` 投递延迟 |
| **AC-05** | 支持基础 Primitive Schema（STRING / JSON） |
| **技术约束** | v1 聚焦 Producer/Consumer 核心路径；事务消息、Key_Shared 订阅、Protobuf 原生 Schema 注册等在后续版本迭代 |

#### R-S5：JMS Mock Broker — P1

| 属性 | 内容 |
|---|---|
| **描述** | 基于 ActiveMQ Artemis 内嵌模式，提供 JMS Broker 模拟。 |
| **AC-01** | Queue 模式消息 FIFO 投递 |
| **AC-02** | Topic 模式消息广播 |
| **AC-03** | 支持消息延迟投递和顺序控制 |
| **AC-04** | 支持死信队列模拟（消息重试 N 次后进入 DLQ） |

#### R-S6：规则管理 REST API — P0

| 属性 | 内容 |
|---|---|
| **描述** | 提供 HTTP REST API 供外部系统或 Web 控制台管理挡板规则。 |
| **AC-01** | `GET /api/rules` — 查看所有规则 |
| **AC-02** | `POST /api/rules` — 新增规则 |
| **AC-03** | `PUT /api/rules/{id}` — 更新规则 |
| **AC-04** | `DELETE /api/rules/{id}` — 删除规则 |
| **AC-05** | `GET /api/health` — 健康检查 |
| **AC-06** | 规则变更实时生效，无需重启 Server |

#### R-S7：请求日志与可观测性 — P1

| 属性 | 内容 |
|---|---|
| **描述** | 记录每次入站请求的详细信息，支持导出和简单 dashboard。 |
| **AC-01** | 每条日志包含：时间戳、来源 Agent 进程 ID、协议类型、请求摘要、匹配规则 ID、响应摘要、耗时（ms） |
| **AC-02** | 支持导出 HTTP 请求为 HAR 格式 |
| **AC-03** | 提供简单的 Dashboard：请求总数、命中率、平均响应时间、按协议分布饼图 |

---

### 5.3 配置模型

#### R-C1：Agent 配置文件（`baafoo-agent.yml`）— P0

| 属性 | 内容 |
|---|---|
| **描述** | YAML 格式配置文件，定义 Agent 的运行模式和路由规则。支持热加载。 |
| **AC-01** | 支持全局 mode：`stub` / `passthrough` / `record` / `record-and-stub` |
| **AC-02** | Server 连接配置：`server.host` + `server.port` |
| **AC-03** | Consul 集成配置：`consul.enabled` + `consul.interceptionMode`（`dns` / `api` / `auto`） |
| **AC-04** | 路由规则支持 `target`（host:port 匹配）、`service`（服务名匹配）、`protocol`（协议提示）字段 |
| **AC-05** | 每条规则可独立覆盖全局 `mode` |

#### R-C2：挡板规则文件（`stub-rules.yml`）— P0

| 属性 | 内容 |
|---|---|
| **描述** | YAML 格式文件，定义 Baafoo Server 的 Mock 响应规则。按协议分区（http/tcp/kafka/pulsar/jms）。 |
| **AC-01** | HTTP 规则包含：`id`、`request`（method/path/query/headers/body）、`response`（status/headers/body/delay/fault） |
| **AC-02** | TCP 规则包含：`id`、`request`（prefixHex/pattern/replaySession）、`response`（dataHex/replay） |
| **AC-03** | Kafka 规则包含：`topic`、`messages`（key/value/delay） |
| **AC-04** | Pulsar 规则包含：`tenant`、`namespace`、`topic`、`subscription`、`messages`（key/value/delay/properties） |
| **AC-05** | JMS 规则包含：`type`（queue/topic）、`name`、`messages`（content/delay/redeliveryCount） |

---

## 6. 成功指标

### 6.1 领先指标（上线后 1-30 天）

| 指标 | 测量方法 | 目标值 |
|---|---|---|
| Agent 激活率 | 团队内使用 `-javaagent` 启动应用的开发者占比 | Week 4 ≥ 60% |
| 首次配置成功率 | 从零配置到首次成功拦截的开发者占比（排除文档咨询）| ≥ 80% |
| 挡板模式使用时长占比 | 开发者本地运行时间中挡板模式 vs 透传模式的比例 | Week 4 ≥ 50% |
| 规则热切换使用频次 | 每人每天平均切换次数 | ≥ 3 次/人/天 |
| 协议覆盖使用率 | HTTP / TCP / Kafka / Pulsar / JMS 五种协议至少各有 1 个活跃规则 | Week 4 全达成 |

### 6.2 滞后指标（上线后 30-90 天）

| 指标 | 测量方法 | 目标值 |
|---|---|---|
| 下游阻塞时间下降率 | 匿名调研对比上线前后的"因下游不可用/不稳定导致的日均等待时间" | 下降 ≥ 70% |
| 本地自测覆盖率提升 | 对比上线前后本地测试用例中"可脱离下游独立运行"的比例 | 提升 ≥ 50% |
| 开发周期缩短 | 对比同类需求从"开始开发"到"提测"的平均时间 | 缩短 ≥ 30% |
| Net Promoter Score | 团队内部调研："你会向其他团队推荐 Baafoo 吗？"（0-10 分） | NPS ≥ 50 |
| 跨团队依赖解耦效果 | "你的开发进度是否曾被其他团队阻塞？"（是/否） | "是"比例下降 ≥ 50% |

### 6.3 评估时间点

| 时间点 | 评估内容 |
|---|---|
| **Week 1** | 检查 Agent 激活率和技术问题反馈 |
| **Week 2** | 首次配置成功率 + 规则热切换体验 |
| **Week 4** | 完整领先指标评估 + 第一次滞后指标基线采集 |
| **Month 2** | 滞后指标趋势观察 + 用户深度访谈（≥ 5 人） |
| **Month 3** | 全面指标评估，决定 v1.5 功能优先级排序依据 |

---

## 7. 开放问题

| # | 问题 | 负责人 | 阻塞级别 | 备注 |
|---|---|---|---|---|
| Q1 | Kafka Mock Broker 的协议子集需要覆盖哪些具体 API？（Metadata / Produce / Fetch / OffsetCommit / FindCoordinator / ...） | 工程 | **阻塞 P1 开发** | 需调研团队实际使用的 Kafka 客户端 API 范围 |
| Q2 | Baafoo Server 的端口分配策略是什么？一个 Server 实例端口统一还是按协议分端口？| 工程 | 非阻塞 | 当前概念设计中为统一端口 9000 + 协议嗅探，但 Pulsar 的 `pulsar://` URL scheme 可能需要独立端口 |
| Q3 | 录制数据的存储上限和清理策略？ | 工程 / PM | 非阻塞 | 长期录制可能占用大量磁盘，需定义默认上限和自动清理规则 |
| Q4 | 是否需要支持挡板规则的导入/导出（方便团队间共享规则集）？ | PM | 非阻塞 | v1 可通过 Git 管理 YAML 文件变通，但专门的导入导出是 v1.5 考量点 |
| Q5 | Pulsar Mock Broker 是否需要模拟 Lookup 阶段（`lookupTopic` 返回 broker 地址）？ | 工程 | **阻塞 P1 开发** | Pulsar 客户端在发送/接收前会先做 Topic Lookup，Mock 是否要模拟二阶段连接 |
| Q6 | Java 9+ 的模块化限制（`--add-opens`）是否会导致某些企业安全策略拒绝？ | 工程 / DevOps | 非阻塞 | 需评估目标团队的实际 JDK 版本分布和安全策略 |
| Q7 | Consul 拦截在健康检查场景下是否会造成时序竞态（健康检查先于 Agent 的 DNS 缓存）？ | 工程 | **阻塞 P0 开发** | 关系到 Consul 集成的稳定性，需实际验证 |

---

## 8. 时间线考量

### 8.1 硬性里程碑

| 里程碑 | 时间 | 说明 |
|---|---|---|
| PRD 评审通过 | 待定 | 工程、QA、产品三方评审 |
| 技术方案设计完成 | 待定 | 详细架构设计、接口定义、测试策略 |
| v1.0 MVP 开发完成 | 待定 | P0 需求全部实现 + P1 核心需求 |
| v1.0 内部试用 | 待定 | 团队内 3-5 人试用，收集反馈 |
| v1.0 正式发布 | 待定 | 修复试用期关键问题后发布 |

### 8.2 依赖关系

| 依赖 | 依赖方 | 影响 | 状态 |
|---|---|---|---|
| Byte Buddy 1.14.x + Java 8 兼容性验证 | 外部开源 | Agent 字节码增强核心依赖 | 已确认 |
| Pulsar binary protocol 文档与客户端源码分析 | 外部开源 | Pulsar Mock Broker 实现 | 待研究 |
| 团队实际下游服务列表与协议统计 | 内部 | 验证协议覆盖率目标 | 待收集 |
| 团队 JDK 版本分布（8/11/17 占比） | 内部 | 决定多版本适配优先级 | 待收集 |

### 8.3 建议分阶段交付

**Phase 1（v1.0 MVP）**：Agent 基础拦截（Socket + NIO + Consul）+ Server HTTP + TCP Handler + 配置热加载 + 规则管理 API
**Phase 2（v1.0 完整版）**：Kafka Mock Broker + Pulsar Mock Broker + JMS Mock Broker + 录制回放（HTTP 层）
**Phase 3（v1.5）**：Web 控制台 + Docker 镜像 + 请求日志 Dashboard + Maven 插件
**Phase 4（v2.0）**：gRPC/WebSocket + 录制回放增强 + Eureka/Nacos 注册中心 + 规则 Git 版本管理

---

## 附录 A：术语对照

| 术语 | 定义 |
|---|---|
| Baafoo Agent | 以 `-javaagent` 加载到 JVM 的字节码增强组件 |
| Baafoo Server | 独立进程，接收重定向请求并返回 Mock 响应 |
| 挡板（Stub） | 模拟下游服务的 Mock 行为 |
| 透传（Passthrough） | 不拦截，直接连接真实下游 |
| 录制（Record） | 透明代理 + 复制请求/响应数据 |
| 回放（Replay） | 用录制数据作为 Mock 响应 |
| 服务名路由 | 按 Consul 注册的服务名（如 `order-service`）匹配规则 |

## 附录 B：竞品简要对比

| 产品 | HTTP | TCP | Kafka | Pulsar | JMS | 零侵入 | 录制回放 |
|---|---|---|---|---|---|---|---|
| **Baafoo** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（Agent） | ✅ |
| WireMock | ✅ | ❌ | ❌ | ❌ | ❌ | ✅（Proxy） | ✅ |
| Hoverfly | ✅ | ❌ | ❌ | ❌ | ❌ | ✅（Proxy） | ✅ |
| MockServer | ✅ | ❌ | ❌ | ❌ | ❌ | ✅（Proxy） | ✅ |
| Mountebank | ✅ | ✅ | ❌ | ❌ | ❌ | ✅（Proxy） | ✅ |

---

*本文档为 Baafoo v1.0 产品需求文档。需求优先级和排期将在技术方案设计阶段与工程团队对齐后确定。*
