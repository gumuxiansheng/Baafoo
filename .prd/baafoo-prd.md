# Baafoo 挡板系统 - 产品需求文档(PRD)

> **文档状态**:PRD v1.5
> **目标读者**:产品团队、工程团队、QA 团队
> **关联文档**:[概念设计说明书 v0.7](../.concepts/baafoo-concept-design.md)
> **最后更新**:2026-05-29
> **变更摘要**:v1.5 - 采纳产品建议书反馈:未匹配规则默认 404(非透传);Agent 加载失败 fail-closed;新增 `baafoo init` Quick Start 工具;新增规则版本管理/Undo;新增场景集管理(简化版);Pulsar 范围收窄;Kafka 标记 Beta;Consul HTTP API 放 v1.5;新增配置项说明表;Agent 插件化架构决策录入。v1.5 审阅修复:US-03 标题修正、US-15/US-16 补充 R-S7.5/R-S7.6 需求定义、R-W1/R-W2 补充场景集 UI 交互、R-S2 AC-09 补充 unmatched-default 交叉引用、R-C2 AC-01 补充 scenarioId 字段、R-S7 AC-01 补充场景集过滤
---

## 1. 问题陈述

在微服务/分布式系统的日常开发中,一个 Java 应用通常依赖多个下游服务(REST API、Socket 服务、消息队列如 Kafka/Pulsar/JMS 等)。开发人员在本地开发或联调阶段面临以下核心痛点:

- **下游环境不稳定**:下游开发环境频繁重启、接口变更、数据脏污,导致联调阻塞,开发人员平均每天浪费 30-60 分钟在等待和排查上;
- **下游不可达**:预发布/生产环境的下游服务无法从开发机直接访问,开发人员被迫"猜测"接口行为或远程调试;
- **异常场景难复现**:超时、断链、错误码、消息乱序等边界场景需要下游团队配合构造,沟通成本高且不可控;
- **协同等待**:下游团队进度滞后时,上游开发完全停滞,形成瀑布式阻塞链。

**谁受影响**:Java 后端开发人员(主要)、QA 测试工程师(次要)、DevOps/平台工程师(次要)。

**不解决的代价**:开发效率持续受制于外部依赖的稳定性;交付周期延长;测试覆盖率不足(异常路径无法验证);团队间耦合度居高不下。

**现有方案局限**:Mockito/PowerMock 侵入业务代码;WireMock 仅支持 HTTP;hosts 修改影响系统全局;Proxy 环境变量无法覆盖 TCP 私有协议和消息队列。

---

## 2. 目标

| # | 目标 | 类型 | 衡量标准 |
|---|---|---|---|
| G1 | 开发人员可在 **5 分钟内** 完成挡板环境搭建,无需修改业务代码(通过 `baafoo init` Quick Start 工具辅助) | 用户目标 | 从零到首次拦截成功的平均耗时 ≤ 5 分钟 |
| G2 | 覆盖团队 90% 的下游依赖协议类型(HTTP/TCP/Kafka/Pulsar/JMS)| 业务目标 | 协议覆盖矩阵中"完全支持"项 ≥ 5 种 |
| G3 | 挡板模式与透传模式之间 **零重启** 切换 | 用户目标 | 修改配置文件后 < 500ms 生效,应用进程不重启 |
| G4 | 开发阶段因下游依赖导致的阻塞时间显著降低 | 方向性目标 | 上线后 4 周内,使用 Baafoo 的开发者 vs 未使用的开发者,日均下游等待时间对比下降 ≥ 50%(方向性指标,受团队规模、项目阶段等多因素影响) |
| G5 | 支持 Consul 注册中心架构,按**服务名**而非 IP 管理挡板规则 | 用户目标 | Agent 在 Consul DNS + HTTP API 两种模式下均能正确拦截 |
| G6 | 开发人员可通过 Web 控制台完成 **80% 的日常操作**(规则管理、请求查看、模式切换),无需手动编辑 YAML | 用户目标 | Web 控制台上线后 2 周,YAML 手动编辑操作占比 ≤ 20% |
| G7 | 同一接口可以按不同请求参数返回不同 Mock 响应,覆盖 **95% 的条件分支场景** | 用户目标 | 参数化规则能表达:按路径参数 / Header / Query / Body 字段值返回不同响应 |

---

## 3. 非目标(Non-Goals)

| # | 非目标 | 原因 |
|---|---|---|
| N1 | **不覆盖非 JVM 语言**(Go/Python/Node.js 应用) | JavaAgent 技术天然限制;非 JVM 场景建议使用 Sidecar Proxy 或 iptables 方案 |
| N2 | **不覆盖 UDP 协议** | Agent 拦截点在 TCP Socket 层面,UDP 走 `DatagramSocket`,需完全不同的拦截策略,v1 不做 |
| N3 | **不覆盖 gRPC/HTTP2 流式 RPC** | gRPC 基于 HTTP/2 多路复用,字节码拦截复杂度显著高于 HTTP/1.1;v1 聚焦 Unary 调用 + 基础协议 |
| N4 | **不替代生产环境流量录制** | Baafoo 定位是**开发阶段**挡板工具,不承担生产流量镜像、压力测试等职责 |
| N5 | **不修改 Consul 注册中心的数据** | Agent 只读 Consul 查询结果,不向 Consul 写入或修改任何注册信息 |
| N6 | **规则不按环境区分** | 规则全局共享;不同环境下规则是否生效,由 Agent 所属环境的模式决定,而非规则本身绑定环境 |

---

## 4. 用户故事

### 4.1 角色定义

| 角色 | 描述 |
|---|---|
| **后端开发人员(主要)** | 日常需要调用多个下游 REST/Socket/MQ 服务进行本地开发联调 |
| **QA 测试工程师** | 需要在测试环境中模拟特定下游响应(边界数据、异常场景) |
| **DevOps/平台工程师** | 负责开发环境基础设施建设,关心部署便利性和资源占用 |

### 4.2 用户故事列表(按优先级排序)

#### P0(Must-Have - 没有这些功能产品不可发布)

**US-01:附加 Agent 启动应用**
> 作为 **后端开发人员**,我希望只需在 JVM 启动参数中增加 `-javaagent` 即可启用挡板功能,以便我无需修改任何业务代码,也无需改变现有的构建和部署流程。

**US-02:HTTP 请求挡板**
> 作为 **后端开发人员**,我希望配置 Mock 规则后,应用对下游 REST API 的 HTTP 请求能自动返回我预设的响应(状态码、Header、Body、延迟),以便我可以在下游不可用或数据不符合预期时继续开发和自测。

**US-03:模式热切换(挡板/透传)**
> 作为 **后端开发人员**,我希望通过 Web 控制台或 API **切换环境模式**后,该环境下的所有 Agent 自动生效(无需重启应用),以便我可以随时按需对比真实下游行为与挡板行为。

**US-04:TCP Socket 字节级挡板**
> 作为 **后端开发人员**,我希望对使用私有 TCP 协议的下游服务也能配置挡板规则(按字节前缀/正则匹配请求,返回预定义字节序列),以便即便下游是二进制协议我也能独立开发。

**US-05:Consul 注册中心适配**
> 作为 **后端开发人员**,在 Consul 微服务架构下,我希望挡板规则能按**服务名**(如 `order-service`)而非具体 IP 来配置,以便我不需要关心服务实例的动态变化。

**US-06:接口参数化定制返回**
> 作为 **后端开发人员**,我希望对同一个接口,根据不同的请求参数(路径参数、Query 参数、Header、Body 字段)配置返回不同的 Mock 响应,以便我能模拟各种业务分支场景(如 VIP 用户返回折扣价、普通用户返回原价),而不用为每个场景写独立接口规则。

#### P1(Should-Have - 显著改善体验,核心路径可用但不完整时可接受)

**US-07:Kafka 消息挡板**
> 作为 **后端开发人员**,我希望 Baafoo 能模拟 Kafka Broker,让我的 Kafka Producer 能正常发送消息、Consumer 能收到预设消息序列,以便我在不依赖真实 Kafka 集群的情况下开发和测试事件驱动逻辑。

**US-08:Pulsar / TDMQ 消息挡板**
> 作为 **后端开发人员**,使用腾讯云 TDMQ for Pulsar 的项目中,我希望 Baafoo 能模拟 Pulsar Broker(含 Topic Lookup 阶段),让 Producer/Consumer 在本地正常运行,以便我不依赖云端 TDMQ 实例即可完成开发。

**US-09:JMS 消息挡板**
> 作为 **后端开发人员**,我希望 Baafoo 能模拟 JMS Broker(ActiveMQ 协议),让我的 JMS Producer/Consumer 在本地正常运行,以便覆盖使用传统 MQ 的老系统。

**US-10:请求录制与回放**
> 作为 **后端开发人员**,我希望在透传模式下 Agent 能自动录制真实下游的请求和响应,后续切换到挡板模式时可以回放这些录制数据,以便我能用真实交互数据来驱动 Mock,减少手动编写挡板规则的工作量。

**US-11:请求日志查看**
> 作为 **后端开发人员 / QA 测试工程师**,我希望查看每次挡板匹配的请求详情(来源进程、协议类型、请求摘要、匹配规则、响应内容、耗时),以便我调试验证挡板行为是否符合预期。

**US-12:Web 控制台**
> 作为 **后端开发人员 / QA 测试工程师**,我希望通过浏览器可视化地管理挡板规则、查看请求日志和流量统计、在线编辑和切换规则,以便我无需直接编辑 YAML 文件即可完成日常操作。

**US-13:规则导入/导出**
> 作为 **后端开发人员 / QA 测试工程师**,我希望可以将挡板规则导出为文件(YAML/JSON),也可以从文件导入规则集,以便我能在团队成员间共享规则、在项目中版本化管理挡板配置。

**US-14:多测试环境独立模式控制**
> 作为 **QA 测试工程师 / DevOps 平台工程师**,我们的多套功能测试环境(FT-1、FT-2、FT-3)都连接同一个 Baafoo Server,我希望按测试环境维度独立控制挡板模式或透传模式(如 FT-1 走挡板自测、FT-2 走透传联调真实下游、FT-3 走录制模式),以便不同测试环境可以并行执行不同的验证策略,互不影响,无需为每套环境部署独立的挡板服务。

**US-15:场景集管理(简化版)**
> 作为 **QA 测试工程师**,我希望将一组规则组织为"场景集"(如"支付异常场景集"),支持一键启用/禁用整组规则,以便我在不同测试场景间快速切换,无需逐条操作规则。

*(v1.0 简化:场景集与规则是 1:N 关系(一条规则只属于一个场景集);v1.5 规划多对多 + Git 版本管理)*

**US-16:`baafoo init` 快速起步工具**
> 作为 **后端开发人员**,我希望运行 `baafoo init` 命令就能生成配置文件模板、JVM 启动参数示例和示例规则,以便我在 5 分钟内完成挡板环境搭建,无需手动编写 YAML 配置。

#### P2(Future Considerations - v1 不实现但设计需预留扩展点)

**US-17:CI/CD 集成(Maven/Gradle 插件)**
> 作为 **DevOps/平台工程师**,我希望在 CI 流水线中自动启停 Baafoo Server,并在集成测试阶段注入挡板规则,以便实现"下游隔离"的自动化测试环境。

**US-18:gRPC / WebSocket 支持**
> 作为 **后端开发人员**,使用 gRPC 或 WebSocket 协议的项目中,我希望 Baafoo 也能模拟这些协议的下游,以便覆盖更广泛的微服务通信场景。

---

## 5. 需求明细

### 5.1 Baafoo Agent(JavaAgent 模块)

#### R-A1:Agent 加载与生命周期 - P0

| 属性 | 内容 |
|---|---|
| **描述** | Agent 通过 `-javaagent` JVM 参数在应用启动前完成字节码增强。Agent 应在 `premain` 阶段注册 `ClassFileTransformer`,在目标类(`java.net.Socket`、`sun.nio.ch.SocketChannelImpl` 等)被加载时完成字节码注入。 |
| **AC-01** | 应用启动命令增加 `-javaagent:baafoo-agent.jar=config=<path>` 后,Agent 日志输出 "Baafoo Agent initialized" 并列出已激活的拦截点 |
| **AC-02** | 未配置 `-javaagent` 时,目标应用启动和行为与正常无差异(零影响) |
| **AC-03** | Agent 加载失败(如配置文件不存在、字节码变换异常)时,默认 **fail-closed**:应用正常启动但输出 ERROR 级别日志 "Agent 未成功加载,所有请求将走真实下游",所有请求透传;提供 `baafoo.agent.fail-open=true` 配置项允许用户主动选择 fail-open 行为 |
| **AC-04** | Java 8 环境下正常运行,Java 9+ 环境下需支持的额外 JVM 参数在文档中明确列出 |
| **技术约束** | 使用 Byte Buddy 1.14.x 实现字节码增强;Agent jar 需通过 `appendToBootstrapClassLoaderSearch()` 处理 Bootstrap ClassLoader 隔离问题;插件化架构下 Advice 类必须在 Core(Bootstrap CL)中预定义,Plugin 只实现处理逻辑 |

#### R-A2:Socket 连接拦截 (`java.net.Socket`) - P0

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `Socket.connect(SocketAddress)` 方法,在连接建立前根据路由规则判断是否需要重写目标地址。拦截范围覆盖 OkHttp、Apache HttpClient、JDBC Driver、原生 Socket 等所有基于 `java.net.Socket` 的网络框架。 |
| **AC-01** | 配置某 `host:port` 为 `stub` 模式后,应用对该地址的 `Socket.connect()` 实际连接到 Baafoo Server 对应协议端口 |
| **AC-02** | 配置为 `passthrough` 的 `host:port`,连接行为与未装 Agent 完全一致 |
| **AC-03** | 拦截逻辑导致的额外延迟在 P99 场景下不超过 1ms |
| **AC-04** | 连接建立失败时(如 Baafoo Server 未启动),Agent 输出 ERROR 日志,连接失败不静默降级;用户可通过 `baafoo.agent.fail-open=true` 配置显式允许降级为 passthrough |

#### R-A3:NIO SocketChannel 拦截 - P0

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `sun.nio.ch.SocketChannelImpl#connect()` 方法,覆盖 Netty 及基于 NIO 的框架。 |
| **AC-01** | 使用 Netty 发起的 TCP 连接同样被拦截和地址重写 |
| **AC-02** | 在 JDK 8u352 / 11.0.18 / 17.0.6 三个版本上,Netty 4.1.x 客户端发起的 TCP 连接均被正确拦截和地址重写 |
| **技术约束** | 统一使用 Byte Buddy Advice 内联机制,通过 `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` 开放访问;针对不同 JDK 版本通过运行时版本检测加载对应的 Advice 实现类 |

#### R-A4:Kafka Client 拦截 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `KafkaProducer` / `KafkaConsumer` 的构造函数,将 `bootstrap.servers` 配置替换为 Baafoo Server 中 Kafka Mock Broker 的地址(独立端口)。 |
| **AC-01** | 配置 `protocol: kafka` 规则后,Producer `send()` 返回正常元数据,不抛连接异常 |
| **AC-02** | Consumer `poll()` 能收到规则中预设的消息序列 |
| **AC-03** | 未配置 Kafka 规则时,Kafka 客户端行为不变化(透传) |

#### R-A5:Pulsar Client 拦截 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `org.apache.pulsar.client.api.PulsarClient#builder()` 及 `ClientBuilder#serviceUrl()` 方法,将 `pulsar://broker:6650`(或 TDMQ 地址)替换为 Baafoo Server 中 Pulsar Mock Broker 的独立端口地址。 |
| **AC-01** | 同时覆盖 `org.apache.pulsar:pulsar-client`(Apache 官方 SDK)和 `com.tencent.tdmq:tdmq-client`(腾讯云封装 SDK) |
| **AC-02** | Producer `send()` 返回 MessageId,Consumer `receive()` 能收到规则预设消息 |
| **AC-03** | 拦截仅在 `PulsarClient` 通过 Builder 模式构造时生效(覆盖 95%+ 场景);直接反射调用构造函数的边缘场景文档说明 |

#### R-A6:JMS ConnectionFactory 拦截 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `javax.jms.ConnectionFactory#createConnection()` 方法,将返回的 Connection 对象替换为指向 Baafoo Server 内嵌 JMS Broker 的代理对象。 |
| **AC-01** | ActiveMQ JMS 客户端创建的 Connection/Queue/Topic 对象功能正常 |
| **AC-02** | 配置的 Queue/Topic 名称与规则匹配 |

#### R-A7:Consul 服务发现拦截(DNS 模式)- P0

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 `java.net.InetAddress#getByName()` / `getAllByName()`,识别以 `.service.consul` 结尾的域名,从域名中提取服务名,查询路由表判断是否需要挡板。若需要,返回挡板地址对应的 `InetAddress`。 |
| **AC-01** | `getByName("order-service.service.consul")` 被正确拦截,提取服务名为 `order-service` |
| **AC-02** | 未匹配到挡板规则的服务名,DNS 解析行为不变(透传) |
| **AC-03** | 不支持 `.consul` 后缀的普通域名不受影响 |

#### R-A8:Consul 服务发现拦截(HTTP API 模式)- P0

| 属性 | 内容 |
|---|---|
| **描述** | 拦截 HTTP 客户端对 Consul Agent 的 REST API 调用(`/v1/catalog/service/*`、`/v1/health/service/*`),匹配 URL 路径中的服务名。若需要挡板,不发起真实 HTTP 请求,直接构造响应 JSON 将 Address/Port 替换为挡板地址。 |
| **AC-01** | Spring Cloud Consul 通过 HTTP API 做服务发现时,返回的实例地址被替换为挡板地址(含对应协议端口) |
| **AC-02** | 对 `127.0.0.1:8500` 的健康检查调用**不受拦截**(避免 Consul 误判服务不健康) |
| **AC-03** | v1.0 仅保证 **DNS 模式 + OkHttp 客户端**(Orbitz/Ecwid Consul SDK);Spring Cloud Consul 通过 WebClient(Reactor Netty)不走 OkHttp,HTTP API 模式放 v1.5 |
| **AC-04** | 兼容 Orbitz、Ecwid Consul SDK(DNS 模式) |

#### R-A9:路由规则引擎 - P0

| 属性 | 内容 |
|---|---|
| **描述** | 路由规则引擎根据请求目标匹配规则,决定连接的处理模式。支持两种维度匹配:**host:port 精确/通配** 和 **服务名**。服务名匹配优先级高于 host:port 匹配。规则中指定协议类型(`protocol` 字段),Agent 根据协议将连接重定向到 Server 对应端口。规则的生效受 **Agent 所属环境的模式** 控制:**仅当 Agent 所属环境为 `stub` 模式时,路由规则才参与匹配**;环境为 `passthrough` 时 Agent 不拦截连接(等效于未安装 Agent);环境为 `record` 或 `record-and-stub` 时,路由规则参与匹配 + 录制器工作。 |
| **AC-01** | 精确匹配 `192.168.1.100:8080` - 正确命中 |
| **AC-02** | 通配匹配 `*.dev:*` - 命中 `api.dev:8080`、`db.dev:5432` |
| **AC-03** | 服务名匹配 `order-service` - 命中所有 Consul 解析到 order-service 的连接 |
| **AC-04** | 同时配置服务名规则和 host:port 规则时,服务名规则优先 |
| **AC-05** | 未匹配任何规则时,默认返回 **404** 并记录 WARN 日志;可通过 `baafoo.stub.unmatched-default` 配置项修改为 `passthrough`(显式配置,非默认) |
| **AC-06** | 规则配置文件变化后,< 500ms 内热加载生效(基于 WatchService) |
| **AC-07** | 规则指定 `protocol: kafka` 时,Agent 将连接重定向到 Server 的 Kafka 端口;`protocol: pulsar` 重定向到 Pulsar 端口;HTTP/TCP 协议根据默认端口映射 |
| **AC-08** | Agent 所属环境为 `stub` 模式时,路由规则正常参与匹配;环境为 `passthrough` 模式时,Agent 不拦截连接(等效于未安装 Agent),所有请求直接透传 |
| **AC-09** | Agent 所属环境为 `record` 或 `record-and-stub` 模式时,路由规则参与匹配 + 录制器工作 |

#### R-A10:录制器 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 在 `record` 或 `record-and-stub` 模式下,Agent 透明代理真实连接,同时将请求和响应的原始字节暂存于内存缓冲区。缓冲区满或录制 session 结束时,Agent 通过控制通道将录制数据上传至 Server 统一存储。Server 端存储格式:JSON 元数据文件 + 原始字节 blob 文件,以 session ID 组织。 |
| **AC-01** | 录制模式下,应用与真实下游的交互不受影响 |
| **AC-02** | 切换到挡板模式后,能选择录制 session 进行回放 |
| **AC-03** | HTTP 请求录制包含:method、URL、headers、body、响应 status、响应 headers、响应 body、耗时 |
| **AC-04** | TCP 请求录制包含:请求 bytes(hex)、响应 bytes(hex)、时间戳 |
| **AC-05** | Agent 上传录制数据失败时(如 Server 不可用),本地暂存并在 Server 恢复后重试上传 |

---

### 5.2 Baafoo Server(挡板服务模块)

#### R-S1:多协议端口分配策略 - P0

| 属性 | 内容 |
|---|---|
| **描述** | Baafoo Server **按协议分配独立监听端口**,取代原先的"统一端口 + 协议嗅探"方案。不同协议的网络特征差异大(Kafka 二进制协议、Pulsar binary protocol、HTTP、TCP 等),独立端口可消除协议嗅探的不确定性和误判风险,同时使 Agent 端口重写逻辑更简洁明确。 |
| **AC-01** | 默认端口分配:HTTP=9000,TCP=9001,Kafka=9002,Pulsar=9003,JMS=9004;所有端口均可通过配置文件覆盖 |
| **AC-02** | Server 启动时日志列出所有监听端口与协议对应关系 |
| **AC-03** | 未启用某协议的 Mock Broker 时(如无 Kafka 规则),对应端口不启动监听,减少资源占用 |
| **AC-04** | Agent 路由规则中 `protocol` 字段决定连接重定向的目标端口,无需协议嗅探 |
| **AC-05** | 配置文件示例:`server.ports.http=9000` / `server.ports.tcp=9001` / `server.ports.kafka=9002` / `server.ports.pulsar=9003` / `server.ports.jms=9004` |

#### R-S2:HTTP Mock Handler - P0

| 属性 | 内容 |
|---|---|
| **描述** | 接收被 Agent 重定向的 HTTP 请求,按规则匹配并返回 Mock 响应。请求匹配维度:`method + path + query + headers + body`(JSONPath/正则)。响应配置统一使用 `responses` 数组格式,支持同一接口按不同请求参数返回不同响应。 |
| **AC-01** | 精确路径匹配 `GET /api/users/123` 返回预设 JSON body |
| **AC-02** | 路径参数匹配 `GET /api/users/{id}` 并使用 `{{path.id}}` 模板变量填充响应 |
| **AC-03** | 请求 body JSONPath 匹配(如 `$.orderType == "VIP"` 返回特定响应) |
| **AC-04** | 请求 Query 参数匹配(如 `?type=detail` 返回详细响应,`?type=summary` 返回摘要响应) |
| **AC-05** | 请求 Header 匹配(如 `X-User-Level: VIP` 返回折扣价,`X-User-Level: NORMAL` 返回原价) |
| **AC-06** | **多规则优先级**:`responses` 数组按声明顺序从上到下匹配,首个命中的条件-响应对返回对应响应;无条件匹配的默认响应(数组最后一项无 `condition` 字段)作为兜底 |
| **AC-07** | 规则可配置响应延迟(固定/随机区间/正态分布),单位 ms |
| **AC-08** | 支持异常模拟:`READ_TIMEOUT`(读超时)、`CONNECTION_RESET`(连接重置)、`HTTP_502` 等 |
| **AC-09** | 未匹配到任何 HTTP 规则时,默认返回 404 并记录 WARN 日志(受 `baafoo.stub.unmatched-default` 配置项控制,见 R-A9 AC-05) |
| **AC-10** | 响应 body 支持模板变量:`{{path.xxx}}`(路径参数)、`{{query.xxx}}`(Query 参数)、`{{header.xxx}}`(请求头)、`{{body.xxx}}`(请求体 JSONPath 提取值) |

#### R-S3:Raw TCP Mock Handler - P0

| 属性 | 内容 |
|---|---|
| **描述** | 接收非 HTTP 协议的 TCP 连接,按字节级规则匹配请求并返回 Mock 字节序列。 |
| **AC-01** | 按请求前缀 hex 值匹配(如 `01 02 03` → 返回 `01 02 00 00 00 01`) |
| **AC-02** | 支持正则匹配原始字节 |
| **AC-03** | 支持长连接多轮交互模拟(请求1→响应1→请求2→响应2) |
| **AC-04** | 支持录制回放模式(匹配 request bytes → 返回录制的 response bytes) |
| **AC-05** | 支持按请求字节中的偏移量字段值返回不同响应(如偏移 4-5 字节为 `0x0001` 返回成功响应,`0x0002` 返回失败响应) |

#### R-S4:Kafka Mock Broker - P1

| 属性 | 内容 |
|---|---|
| **描述** | (**Beta**)模拟 Kafka Broker 的协议子集,在独立端口(默认 9002)监听。v1 明确覆盖以下 API:**Metadata**(集群元数据查询)、**Produce**(生产者发送消息)、**Fetch**(消费者拉取消息)。这三个 API 覆盖了 Kafka Producer/Consumer 的核心运行路径,确保应用在 Mock 环境下的 Producer `send()` 和 Consumer `poll()` 均可正常工作。**v1.0 标记为 Beta,支持 Kafka Client 2.8+;不支持 `acks=all`、事务、Consumer Group Rebalance**。 |
| **AC-01** | **Metadata API**:客户端查询 Topic 元数据时,Mock Broker 返回该 Topic 的 partition 信息(默认 1 partition,leader 为 Mock Broker 自身),Producer/Consumer 无需连接真实集群即可获取元数据 |
| **AC-02** | **Produce API**:Producer `send()` 正常返回 RecordMetadata(含 topic、partition、offset),消息被 Mock Broker 内存存储 |
| **AC-03** | **Fetch API**:Consumer 按 topic + partition + offset 拉取时,Mock Broker 返回规则预设的消息序列;若预设消息已消费完毕,返回空消息集 |
| **AC-04** | 支持配置消息投递延迟(`delay`)和 ack 模式(`acks=1` / `acks=all`) |
| **AC-05** | 支持 topic 通配符订阅 |
| **AC-06** | 未覆盖的 Kafka API(OffsetCommit、FindCoordinator、JoinGroup、SyncGroup、Heartbeat、ListGroups、DescribeConfigs 等)在客户端调用时返回空/默认响应,不抛异常,确保客户端不崩溃 |
| **AC-07** | Mock Broker 启动时日志明确列出已支持的 API 列表、版本范围(Kafka Client 2.8+)和 Beta 标识 |
| **AC-08** | 不支持的 Kafka 特性(`acks=all`、事务、Consumer Group Rebalance)在文档和 Web 控制台中明确标注"不支持",调用时返回明确错误响应而非静默异常 |

#### R-S5:Pulsar Mock Broker - P1

| 属性 | 内容 |
|---|---|
| **描述** | 模拟 Pulsar Broker 的最小协议子集(基于 Pulsar binary protocol,独立端口默认 9003)。**必须模拟 Topic Lookup 阶段**(`lookupTopic` / `getTopicsOfNamespace`),因为 Pulsar 客户端在 Producer/Consumer 创建前会先通过 Lookup 请求获取 Topic 所在 Broker 地址。Mock Broker 需在 Lookup 阶段返回自身地址,引导客户端直接与 Mock Broker 交互。 |
| **AC-01** | **Lookup 阶段**:客户端发起 `lookupTopic` 请求时,Mock Broker 返回自身地址(`localhost:9003`),引导客户端后续 Producer/Consumer 连接指向 Mock Broker |
| **AC-02** | Producer `send()` 正常返回 MessageId |
| **AC-03** | Consumer `receive()` 按 subscription 收到预设消息序列 |
| **AC-04** | 支持 tenant/namespace/topic 三级隔离 |
| **AC-05** | 支持 Producer 配置的 `delay` 投递延迟 |
| **AC-06** | 支持基础 Primitive Schema(STRING / JSON) |
| **AC-07** | `getTopicsOfNamespace` 请求返回规则中配置的 Topic 列表,确保客户端发现逻辑正常 |
| **AC-08** | v1.0 仅覆盖最简路径:非分区 Topic + 单 Producer + 单 Consumer + Shared 订阅;分区 Topic、Key_Shared 订阅、Protobuf Schema 在 v1.5 迭代 |
| **AC-09** | TDMQ SDK 兼容性需在抓包验证后确认;若 TDMQ 有私有协议扩展,通过插件化架构(见概念设计)隔离适配 |
| **技术约束** | v1 聚焦 Lookup + Producer/Consumer 核心路径;事务消息、Key_Shared 订阅、Protobuf 原生 Schema 注册等在后续版本迭代 |

#### R-S6:JMS Mock Broker - P1

| 属性 | 内容 |
|---|---|
| **描述** | 基于 ActiveMQ Artemis 内嵌模式,在独立端口(默认 9004)提供 JMS Broker 模拟。 |
| **AC-01** | Queue 模式消息 FIFO 投递 |
| **AC-02** | Topic 模式消息广播 |
| **AC-03** | 支持消息延迟投递和顺序控制 |
| **AC-04** | 支持死信队列模拟(消息重试 N 次后进入 DLQ) |

#### R-S7:规则管理 REST API - P0

| 属性 | 内容 |
|---|---|
| **描述** | 提供 HTTP REST API 供外部系统或 Web 控制台管理挡板规则。**规则全局共享,无 `environments` 字段**。规则是否生效取决于 Agent 的模式:仅 `stub` 模式 Agent 才参与规则匹配。 |
| **AC-01** | `GET /api/rules` - 查看所有规则(支持按协议类型过滤:`?protocol=http`,按场景集过滤:`?scenarioId=xxx`) |
| **AC-02** | `POST /api/rules` - 新增规则 |
| **AC-03** | `PUT /api/rules/{id}` - 更新规则 |
| **AC-04** | `DELETE /api/rules/{id}` - 删除规则 |
| **AC-05** | `GET /api/health` - 健康检查 |
| **AC-06** | 规则变更实时生效,无需重启 Server |
| **AC-07** | `POST /api/rules/import` - 从 YAML/JSON 文件批量导入规则(与已有规则合并,相同 ID 覆盖) |
| **AC-08** | `GET /api/rules/export` - 导出全部规则为 YAML/JSON 文件(`?format=yaml` 或 `?format=json`) |
| **AC-09** | `GET /api/sessions` - 查看当前活跃的 Agent 连接会话列表 |

#### R-S7.1:规则冲突检测与并发编辑 - P0

| 属性 | 内容 |
|---|---|
| **描述** | 规则保存时进行冲突检测,防止多条规则产生歧义匹配;多人同时编辑规则时提供并发保护。 |
| **AC-01** | 规则 ID 全局唯一,创建/导入时若 ID 重复则拒绝并返回明确错误信息 |
| **AC-02** | 新增或修改 HTTP 规则时,Server 检测是否存在相同 `method + path` 的已有规则,若存在则返回提示:"该接口路径下已有 N 条规则,请确认条件分支是否完整覆盖" |
| **AC-03** | 规则保存时携带 `version` 字段(乐观锁),若 Server 端版本号与请求不一致,返回 409 Conflict 并提示"规则已被他人修改,请刷新后重试" |
| **AC-04** | 规则删除前检查是否有其他规则依赖该规则(如录制回放引用),若有则提示确认 |

#### R-S7.2:Agent 控制通道 API - P0

| 属性 | 内容 |
|---|---|
| **描述** | 提供 Agent 与 Server 之间的控制通道 API,支持心跳上报、规则同步、录制数据上传和**环境模式切换指令下发**。 |
| **AC-01** | `POST /api/agent/register` - Agent 启动时注册,返回 `agentId` **和该 Agent 所属环境的当前模式**;请求体包含 `pid`、`appName`、`environment`(Agent 所属测试环境标识,如 `"ft-1"`,从 Agent 配置文件读取) |
| **AC-02** | `POST /api/agent/heartbeat` - Agent 每 30s 上报心跳;心跳超时 90s 后 Server 将 Agent 标记为离线 |
| **AC-03** | `GET /api/agent/rules` - Agent 拉取最新路由规则(规则全局共享,无环境过滤),响应包含 `version` 字段用于增量判断 |
| **AC-04** | `GET /api/agent/poll?agentId=xxx` - 长轮询端点,Server 端有规则变更、**环境模式切换指令**时立即返回(含该 Agent 所属环境的当前 mode) |
| **AC-05** | `POST /api/agent/recordings` - Agent 上传录制数据(multipart/form-data),支持分片上传 |
| **AC-06** | `GET /api/agents` - 查看所有已注册 Agent 的状态列表(供 Web 控制台使用) |

#### R-S7.3:测试环境管理 API - P0

| 属性 | 内容 |
|---|---|
| **描述** | 提供测试环境的管理 API,支持创建环境、配置环境模式、查看环境下活跃 Agent 列表、删除环境。环境是 Server 端的概念,每个环境有独立的模式配置(`stub` / `passthrough` / `record` / `record-and-stub`)。模式变更后,Server 通过控制通道向该环境下所有 Agent 下发模式切换指令。 |
| **AC-01** | `POST /api/environments` - 创建测试环境,请求体包含 `name`(环境标识,如 `ft-1`)、`mode`(初始模式,默认 `passthrough`)、`description`(可选) |
| **AC-02** | `GET /api/environments` - 查看所有测试环境列表,包含环境名称、当前模式、活跃 Agent 数量 |
| **AC-03** | `PUT /api/environments/{name}` - 更新环境配置(主要用于切换模式);模式变更后,Server 立即通过控制通道向该环境下所有 Agent 下发模式切换指令 |
| **AC-04** | `DELETE /api/environments/{name}` - 删除测试环境;仅允许删除无活跃 Agent 的环境 |
| **AC-05** | `GET /api/environments/{name}/agents` - 查看指定环境下所有已注册 Agent 的状态列表 |
| **AC-06** | Agent 注册时声明的 `environment` 在 Server 端不存在时,Server **自动创建该环境**(默认模式 `passthrough`),避免 Agent 无法启动 |
| **AC-07** | 环境模式切换不影响规则:从 `stub` 切换到 `passthrough` 时,Agent 停止拦截;切回 `stub` 时,Agent 恢复拦截并匹配现有规则,无需重新配置 |

#### R-S7.4:规则版本管理与 Undo - P1

| 属性 | 内容 |
|---|---|
| **描述** | 每次规则保存前自动快照前一版本,支持一键回退到上一版本。避免规则修改失误导致挡板行为异常且无法恢复。 |
| **AC-01** | 每次 `PUT /api/rules/{id}` 或 `POST /api/rules` 时,Server 自动保存前一版本到版本历史(保留最近 10 个版本) |
| **AC-02** | `GET /api/rules/{id}/history` - 查看规则版本历史(版本号、修改时间、修改人) |
| **AC-03** | `POST /api/rules/{id}/restore?version=N` - 回滚到指定版本 |
| **AC-04** | Web 控制台规则编辑页提供"撤销上次修改"按钮(回退到上一版本),无需离开当前页面 |
| **AC-05** | 版本历史随规则删除而清理,不残留孤立版本数据 |

#### R-S7.5:场景集管理(简化版)- P1

| 属性 | 内容 |
|---|---|
| **描述** | 支持将一组规则组织为"场景集"(如"支付异常场景集"),支持一键启用/禁用整组规则,方便 QA 在不同测试场景间快速切换。v1.0 简化版:场景集与规则是 1:N 关系(一条规则只属于一个场景集)。 |
| **AC-01** | `POST /api/scenarios` - 创建场景集,请求体包含 `name`(场景集名称)、`description`(可选) |
| **AC-02** | `GET /api/scenarios` - 查看所有场景集列表,包含场景集名称、规则数量、启用/禁用状态 |
| **AC-03** | `PUT /api/scenarios/{id}` - 更新场景集(修改名称、描述、关联规则列表) |
| **AC-04** | `DELETE /api/scenarios/{id}` - 删除场景集(仅允许删除已禁用的场景集,删除后关联规则变为无场景集状态) |
| **AC-05** | `POST /api/scenarios/{id}/activate` - 一键启用场景集:启用该场景集下所有规则 |
| **AC-06** | `POST /api/scenarios/{id}/deactivate` - 一键禁用场景集:禁用该场景集下所有规则 |
| **AC-07** | 规则创建/编辑时可指定 `scenarioId` 字段归属某场景集;未指定则为独立规则 |
| **AC-08** | v1.0 场景集与规则 1:N 关系(一条规则只属于一个场景集);v1.5 规划多对多 + Git 版本管理 |

#### R-S7.6:`baafoo init` 快速起步工具 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 提供 CLI 工具 `baafoo init`,一键生成 Agent 配置文件模板、JVM 启动参数示例和示例规则,降低首次配置门槛,确保 5 分钟内从零到首次拦截成功。 |
| **AC-01** | 运行 `baafoo init` 后在当前目录生成 `baafoo-agent.yml`(含注释说明的配置模板) |
| **AC-02** | 运行 `baafoo init` 后生成 `baafoo-rules.yml`(含 HTTP/TCP 各一条示例规则,注释说明字段含义) |
| **AC-03** | 运行 `baafoo init` 后输出 JVM 启动参数示例:`java -javaagent:baafoo-agent.jar=config=baafoo-agent.yml -jar your-app.jar` |
| **AC-04** | 支持交互式问答引导:"你的应用依赖哪些下游?" → 自动生成对应的路由规则模板 |
| **AC-05** | 已存在配置文件时提示确认覆盖,不静默覆盖 |

#### R-S8:录制数据存储与清理 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 录制数据由 Agent 上传至 Server 统一存储在 Server 本地文件系统,采用可配置的保留策略自动清理,避免长期录制占用大量磁盘空间。**录制数据写入磁盘后才向 Agent 返回成功**,避免内存中丢失;Server 重启不丢失已持久化的录制数据。 |
| **AC-01** | Server 配置文件新增 `recording.retentionDays` 参数,默认值 **7 天**,表示仅保留最近 N 天的录制数据 |
| **AC-02** | Server 配置文件新增 `recording.maxSizeMb` 参数,默认值 **500MB**,录制数据总量超限时自动清理最旧的 session |
| **AC-03** | 清理策略同时生效:时间超限(> retentionDays)或容量超限(> maxSizeMb)均触发清理,先清理最旧的数据 |
| **AC-04** | Server 端提供 `GET /api/recordings` API,列出所有录制 session 及其大小、时间范围 |
| **AC-05** | Server 端提供 `DELETE /api/recordings/{sessionId}` API,手动删除指定录制 session |
| **AC-06** | 清理动作执行时输出 INFO 日志(清理了哪些 session、释放了多少空间) |

#### R-S9:请求日志与可观测性 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 记录每次入站请求的详细信息,支持导出和简单 dashboard。 |
| **AC-01** | 每条日志包含:时间戳、来源 Agent 进程 ID、协议类型、请求摘要、匹配规则 ID、响应摘要、耗时(ms) |
| **AC-02** | 支持导出 HTTP 请求为 HAR 格式 |
| **AC-03** | 提供简单的 Dashboard:请求总数、命中率、平均响应时间、按协议分布饼图 |
| **AC-04** | 请求日志支持按协议类型、规则 ID、时间范围过滤查询 |

---

### 5.3 Web 控制台

#### R-W1:控制台概览与导航 - P1

| 属性 | 内容 |
|---|---|
| **描述** | Baafoo Server 内嵌 Web 控制台(默认监听端口与 HTTP Mock Handler 共用 9000 端口,路径前缀 `/__baafoo__/`),提供可视化的规则管理、请求查看、环境管理和系统状态界面。 |
| **AC-01** | 访问 `http://localhost:9000/__baafoo__/` 可进入控制台首页 |
| **AC-02** | 控制台导航栏包含：规则管理、**场景集管理**、请求日志、录制管理、**环境管理**、系统状态 |
| **AC-03** | 控制台通过 Server 的 REST API 获取数据,无需独立后端 |
| **AC-04** | 控制台支持响应式布局,桌面端和移动端均可使用 |

#### R-W2:规则管理界面 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 在 Web 控制台中对挡板规则进行可视化的增删改查操作,取代手动编辑 YAML 文件。**规则全局共享,无环境过滤**;规则是否生效取决于 Agent 所属环境的模式。 |
| **AC-01** | 规则列表页:按协议类型(HTTP / TCP / Kafka / Pulsar / JMS)分 Tab 展示,支持搜索和过滤;规则列表增加"生效环境"标签列(显示当前处于 stub 模式的环境列表) |
| **AC-02** | 规则新增/编辑:表单化录入规则,HTTP 规则支持可视化配置请求匹配条件(method、path、query、header、body 匹配)和响应内容(status、headers、body、delay、fault) |
| **AC-03** | **参数化规则编辑**:HTTP 规则编辑界面支持"添加匹配条件"和"添加响应分支",用户可对同一接口配置多组条件-响应对,条件支持 JSONPath、Header 等匹配,响应支持模板变量 |
| **AC-04** | 规则启用/禁用:单条规则可临时禁用而不删除,禁用后该规则不参与匹配 |
| **AC-05** | 规则导入/导出:界面上提供"导入规则"(上传 YAML/JSON 文件)和"导出规则"(下载 YAML/JSON 文件)按钮 |
| **AC-06** | 规则排序:支持拖拽调整同一接口下多规则的优先级顺序 |
| **AC-07** | 修改实时生效:界面修改规则后,自动调用 REST API 更新,无需手动重载 |
| **AC-08** | 当无任何环境处于 stub 模式时,规则管理页面顶部显示 banner:"当前无 stub 模式环境,所有规则不会生效",引导用户到环境管理页面切换模式 |
| **AC-09** | 规则保存后弹出提示：“规则已保存，将在以下 stub 模式环境中生效：{环境列表}” |
| **AC-10** | 规则列表页增加“场景集”下拉过滤，可按场景集筛选规则；规则编辑时可指定归属场景集 |
| **AC-11** | 规则编辑页提供“撤销上次修改”按钮（对应 R-S7.4 AC-04） |
| **AC-12** | 规则列表页增加“启用/禁用”开关列，支持单条规则快速切换启用状态 |

#### R-W3:请求日志界面 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 在 Web 控制台中查看实时和历史请求日志。 |
| **AC-01** | 请求列表实时刷新(WebSocket 推送或轮询),展示:时间、协议、请求摘要、匹配规则、响应摘要、耗时 |
| **AC-02** | 点击单条请求可展开详情:完整请求内容(headers、body)和响应内容 |
| **AC-03** | 支持按协议类型、规则 ID、时间范围、关键词搜索过滤 |
| **AC-04** | 支持"导出为 HAR"按钮(HTTP 请求) |

#### R-W4:录制管理界面 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 在 Web 控制台中管理录制 session。 |
| **AC-01** | 列出所有录制 session:session ID、时间范围、数据大小、协议类型 |
| **AC-02** | 支持删除指定 session |
| **AC-03** | 支持选择录制 session 一键生成回放规则(HTTP 请求自动生成对应的 stub rule) |

#### R-W5:系统状态界面 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 在 Web 控制台中查看 Baafoo Server 运行状态。 |
| **AC-01** | 展示:各协议端口的监听状态(已启动/未启动)、活跃 Agent 连接数、规则总数 |
| **AC-02** | 展示:请求统计 Dashboard(请求总数、命中率、平均响应时间、按协议分布) |
| **AC-03** | 展示:录制数据磁盘占用和保留策略配置 |

#### R-W6:测试环境管理界面 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 在 Web 控制台中管理多套测试环境,包括查看环境列表、切换环境模式、查看各环境下活跃 Agent 列表。 |
| **AC-01** | 环境列表页:展示所有测试环境(环境名称、当前模式、活跃 Agent 数量、描述),支持创建新环境 |
| **AC-02** | 模式切换:每个环境卡片上提供模式切换按钮(`stub` / `passthrough` / `record` / `record-and-stub`),点击后即时生效,Server 通过控制通道向该环境下所有 Agent 下发模式切换指令 |
| **AC-03** | Agent 列表:点击环境卡片可展开查看该环境下所有活跃 Agent(PID、应用名、连接时间、状态) |
| **AC-04** | 删除环境:仅允许删除无活跃 Agent 的环境,删除前需确认 |
| **AC-05** | 导航栏新增"环境管理"入口,与规则管理、请求日志、录制管理、系统状态并列 |
| **AC-06** | 环境模式切换时显示确认弹窗,明确提示影响范围:"即将将环境 {name} 从 {当前模式} 切换为 {新模式},影响 {N} 个活跃 Agent" |
| **AC-07** | 环境列表支持按模式筛选(如只看 stub 模式环境) |
| **AC-08** | 无活跃 Agent 的环境在列表中灰显,提示"该环境无活跃 Agent,规则不会生效" |

---

### 5.4 配置模型

#### R-C1:Agent 配置文件(`baafoo-agent.yml`)- P0

| 属性 | 内容 |
|---|---|
| **描述** | YAML 格式配置文件,定义 Agent 的运行参数。**注意:`mode` 字段已移除,Agent 不在本地配置模式,模式由 Server 端根据环境配置下发**。Agent 启动时从配置文件读取 `environment` 字段,向 Server 注册时上报所属环境。 |
| **AC-01** | 支持配置 `environment` 字段:声明 Agent 所属的测试环境(如 `ft-1`、`ft-2`),Agent 启动时从配置文件读取并上传到 Server;未配置时默认为 `default` 环境 |
| **AC-02** | Agent 注册到 Server 后,Server 根据环境配置下发模式(stub / passthrough / record),Agent 根据收到的模式决定是否拦截连接 |
| **AC-03** | Server 连接配置:`server.host` + 按协议端口配置(`server.ports.http` / `server.ports.tcp` / `server.ports.kafka` / `server.ports.pulsar` / `server.ports.jms`) |
| **AC-04** | Consul 集成配置:`consul.enabled` + `consul.interceptionMode`(`dns` / `api` / `auto`) |
| **AC-05** | 路由规则支持 `target`(host:port 匹配)、`service`(服务名匹配)、`protocol`(协议类型,决定重定向到哪个 Server 端口)字段 |
| **AC-06** | 录制配置:`recording.retentionDays`(默认 7)、`recording.maxSizeMb`(默认 500) |
| **AC-07** | 控制通道配置:`control.heartbeatInterval`(默认 30s)、`control.heartbeatTimeout`(默认 90s) |
| **AC-08** | **`mode` 字段已移除**:Agent 不在本地配置模式,模式由 Server 端根据环境配置下发 |

#### R-C2:挡板规则文件(`stub-rules.yml`)- P0

| 属性 | 内容 |
|---|---|
| **描述** | YAML 格式文件,定义 Baafoo Server 的 Mock 响应规则。按协议分区(http/tcp/kafka/pulsar/jms)。**规则全局共享,无 `environments` 字段**。规则是否生效取决于 Agent 的模式:仅 `stub` 模式 Agent 才参与规则匹配。 |
| **AC-01** | HTTP 规则包含:`id`、`scenarioId`(可选,归属场景集)、`enabled`(可选,默认 true)、`request`(method/path/query/headers/body)、`responses`(数组,每项包含可选 `condition` + 必选 `response`),`response` 包含 status/headers/body/delay/fault。简单规则可只含一项无 `condition` 的默认响应 |
| **AC-02** | TCP 规则包含:`id`、`request`(prefixHex/pattern/replaySession/offsetMatch)、`responses`(数组,每项包含可选 `condition` + 必选 `response`),`response` 包含 dataHex/replay |
| **AC-03** | Kafka 规则包含:`topic`、`messages`(key/value/delay) |
| **AC-04** | Pulsar 规则包含:`tenant`、`namespace`、`topic`、`subscription`、`messages`(key/value/delay/properties) |
| **AC-05** | JMS 规则包含:`type`(queue/topic)、`name`、`messages`(content/delay/redeliveryCount) |

---


### 5.5 关键配置项说明

以下配置项影响 Baafoo 的核心安全行为和默认策略,在产品部署前需明确。

| 配置项 | 默认值 | 说明 | 配置位置 |
|---|---|---|---|
| `baafoo.agent.fail-open` | `false` | Agent 加载失败时的行为:`false`=fail-closed(打 ERROR 日志,请求透传);`true`=fail-open(静默透传) | Agent 启动参数或 `baafoo-agent.yml` |
| `baafoo.stub.unmatched-default` | `404` | 未匹配规则的请求默认行为:`404`(返回 404 + 日志)、`passthrough`(透传);建议保持默认 `404` | Server 配置文件或环境变量 |
| `baafoo.recording.memory-limit` | `256MB` | 单 Agent 录制内存硬上限,超限后停止录制并输出 WARN 日志 | Agent 配置文件 |
| `baafoo.recording.auto-cleanup-days` | `7` | 录制数据自动清理天数(与 R-S8 `retentionDays` 一致) | Server 配置文件 |
| `baafoo.heartbeat.interval` | `30s` | Agent-Server 心跳间隔(原 PRD 30s;如需更灵敏可改为 10s) | Agent 配置文件 |
| `baafoo.agent.self-check` | `true` | Agent 启动后是否自动验证拦截是否生效(发送测试请求并验证是否被拦截) | Agent 启动参数 |



### 5.6 Agent 插件化架构(架构决策)

> 详见概念设计文档附录及《Baafoo Agent 插件化架构建议》

**决策**:Agent 采用"Core Advice 内联 + Plugin 逻辑委托"的分层架构。
- **Core(Bootstrap CL)**:内置 HTTP/TCP/Consul 拦截点(Advice 类),以及 Plugin SPI 接口定义
- **Plugin(独立 ClassLoader)**:协议特定逻辑(Pulsar/TDMQ/Kafka/JMS)在独立 URLClassLoader 中执行,与应用依赖完全隔离
- **v1.0**:Pulsar 插件代码内嵌在 Core 中(按 Plugin 接口编写但不独立 jar),降低部署复杂度;验证 TDMQ SDK 后再决定是否拆出独立插件 jar
- **v1.5**:所有协议插件外置为独立 jar,Web 控制台提供"插件管理"页面

**对 PRD 的影响**:R-A4/R-A5/R-A6 拦截器实现方式改为"Core Advice + Plugin 逻辑",AC 不变;新增"插件管理"功能需求(v1.5)。

## 6. 成功指标

### 6.1 领先指标(上线后 1-30 天)

| 指标 | 测量方法 | 目标值 |
|---|---|---|
| Agent 激活率 | 团队内使用 `-javaagent` 启动应用的开发者占比 | Week 4 ≥ 60% |
| 首次配置成功率 | 从零配置到首次成功拦截的开发者占比(排除文档咨询)| ≥ 80% |
| 挡板模式使用时长占比 | 开发者本地运行时间中挡板模式 vs 透传模式的比例 | Week 4 ≥ 50% |
| 规则热切换使用频次 | 每人每天平均切换次数 | ≥ 3 次/人/天 |
| 协议覆盖使用率 | HTTP / TCP / Kafka / Pulsar / JMS 五种协议至少各有 1 个活跃规则 | Week 4 全达成 |
| Web 控制台使用率 | 通过 Web 控制台操作 vs 手动编辑 YAML 的比例 | Week 4 ≥ 80% |
| 参数化规则使用率 | 配置了多条件分支的 HTTP 规则占全部 HTTP 规则的比例 | Week 4 ≥ 30% |

### 6.2 滞后指标(上线后 30-90 天)

| 指标 | 测量方法 | 目标值 |
|---|---|---|
| 下游阻塞时间下降率 | 匿名调研对比上线前后的"因下游不可用/不稳定导致的日均等待时间" | 下降 ≥ 50%(方向性指标) |
| 本地自测覆盖率提升 | 对比上线前后本地测试用例中"可脱离下游独立运行"的比例 | 提升 ≥ 50% |
| 开发周期缩短 | 对比同类需求从"开始开发"到"提测"的平均时间 | 缩短 ≥ 30% |
| Net Promoter Score | 团队内部调研:"你会向其他团队推荐 Baafoo 吗?"(0-10 分) | NPS ≥ 50 |
| 跨团队依赖解耦效果 | "你的开发进度是否曾被其他团队阻塞?"(是/否) | "是"比例下降 ≥ 50% |

### 6.3 评估时间点

| 时间点 | 评估内容 |
|---|---|
| **Week 1** | 检查 Agent 激活率和技术问题反馈 |
| **Week 2** | 首次配置成功率 + 规则热切换体验 + Web 控制台易用性 |
| **Week 4** | 完整领先指标评估 + 第一次滞后指标基线采集 |
| **Month 2** | 滞后指标趋势观察 + 用户深度访谈(≥ 5 人) |
| **Month 3** | 全面指标评估,决定 v1.5 功能优先级排序依据 |

---

## 7. 开放问题决议

> 以下为 v1.1 中基于团队讨论确定的问题决议,原开放问题已关闭。

| # | 原问题 | 决议 | 影响的需求 |
|---|---|---|---|
| Q1 | Kafka Mock Broker 的协议子集需要覆盖哪些具体 API? | **覆盖 Metadata / Produce / Fetch** 三个核心 API,其余 API 返回空/默认响应确保客户端不崩溃 | R-S4 |
| Q2 | Baafoo Server 的端口分配策略是什么? | **按协议分端口**:HTTP=9000, TCP=9001, Kafka=9002, Pulsar=9003, JMS=9004,均可配置覆盖;未启用协议的端口不启动监听 | R-S1, R-C1 |
| Q3 | 录制数据的存储上限和清理策略? | **可配置化**:`recording.retentionDays`(默认 7 天)+ `recording.maxSizeMb`(默认 500MB),双重条件触发自动清理最旧数据 | R-S8 |
| Q4 | 是否需要支持挡板规则的导入/导出? | **需要支持**,v1.0 即提供 REST API 和 Web 控制台的导入/导出功能,支持 YAML/JSON 格式 | R-S7, R-W2 |
| Q5 | Pulsar Mock Broker 是否需要模拟 Lookup 阶段? | **需要**,Mock Broker 必须模拟 `lookupTopic` 和 `getTopicsOfNamespace`,返回自身地址引导客户端直连 Mock Broker | R-S5 |
| Q6 | Java 9+ 的模块化限制是否会导致企业安全策略拒绝? | **不会**,评估后确认目标团队环境无此限制,`--add-opens` 参数可在开发环境正常使用 | 风险表更新 |
| Q7 | Consul 拦截在健康检查场景下是否会造成时序竞态? | **暂不明确**,标记为"待实际验证",不阻塞 P0 开发;当前 AC 中已明确 `127.0.0.1:8500` 健康检查透传,若验证发现竞态问题,在 v1.0 发布前修复 | R-A8 |
| **Q8** | **规则是否应该按环境区分?模式应该由谁控制?** | **规则全局共享,模式由环境控制**。环境是 Agent 的属性(启动时指定),模式是环境的属性(Server 端维护)。规则是否生效取决于 Agent 所属环境的模式。 | **架构级变更**,影响 R-A9, R-C1, R-C2, R-S7, R-W2, R-W6 |

---

## 8. 时间线考量

### 8.1 硬性里程碑

| 里程碑 | 时间 | 说明 |
|---|---|---|
| PRD 评审通过 | 待定 | 工程、QA、产品三方评审 |
| 技术方案设计完成 | 待定 | 详细架构设计、接口定义、测试策略 |
| v1.0 MVP 开发完成 | 待定 | P0 需求全部实现 + P1 核心需求(含 Web 控制台 + 规则导入导出 + **环境管理**) |
| v1.0 内部试用 | 待定 | 团队内 3-5 人试用,收集反馈 |
| v1.0 正式发布 | 待定 | 修复试用期关键问题后发布 |

### 8.2 依赖关系

| 依赖 | 依赖方 | 影响 | 状态 |
|---|---|---|---|
| Byte Buddy 1.14.x + Java 8 兼容性验证 | 外部开源 | Agent 字节码增强核心依赖 | 已确认 |
| Pulsar binary protocol 文档与客户端源码分析 | 外部开源 | Pulsar Mock Broker 实现(含 Lookup 阶段) | 待研究 |
| 团队实际下游服务列表与协议统计 | 内部 | 验证协议覆盖率目标 | 待收集 |
| 团队 JDK 版本分布(8/11/17 占比) | 内部 | 决定多版本适配优先级 | 待收集 |

### 8.3 建议分阶段交付

**Phase 1(v1.0 MVP)**:Agent 基础拦截(Socket + NIO + Consul)+ Server 多协议端口 + HTTP Handler(含参数化规则)+ TCP Handler + **环境管理 API** + 规则管理 API(含导入导出)+ 配置热加载 + `baafoo init` 快速起步工具
**Phase 2(v1.0 完整版)**:Kafka Mock Broker(Metadata/Produce/Fetch)+ Pulsar Mock Broker(含 Lookup)+ JMS Mock Broker + 录制回放(HTTP 层)+ **Web 控制台(含环境管理页面)** + 规则版本管理与 Undo
**Phase 3(v1.5)**:Docker 镜像 + 场景集管理(简化版)+ 请求日志 Dashboard 增强 + Maven 插件 + Consul HTTP API 完善(Spring Cloud Consul WebClient)+ Kafka Beta 正式版 + Pulsar 范围扩展 + 插件外置 jar 化
**Phase 4(v2.0)**:gRPC/WebSocket + 录制回放增强 + Eureka/Nacos 注册中心 + 规则 Git 版本管理

---

## 9. 风险与注意事项

| 风险 | 影响 | 概率 | 缓解措施 |
|---|---|---|---|
| Bootstrap ClassLoader 隔离 | Agent 类无法访问 `java.net` 包 | 中 | 使用 `appendToBootstrapClassLoaderSearch()` |
| JDK 内部类跨版本变更 | Byte Buddy Advice 对 `sun.nio.ch.*` 的增强可能在 JDK 小版本升级时失效 | 中 | 运行时版本检测 + 多版本 Advice 实现类适配 |
| 连接池预热时机 | 部分框架在 Agent 注册前已完成连接建立 | 低 | 文档说明 Agent 必须在应用 main() 之前完成增强 |
| TLS 证书信任 | HTTPS 场景需信任挡板自签证书 | 中 | 提供便捷的 trustAll 模式(仅开发环境) |
| 业务代码检测绕过 | 极少数框架绕过 Socket 直接使用 JNI | 低 | 影响极小,文档说明,可通过 iptables 兜底 |
| Consul 健康检查误判 | Agent 篡改地址后 Consul 健康检查看到的 IP 与注册不一致,可能误标记服务不健康 | 低 | Agent 对 `127.0.0.1:8500` 的调用透传,不拦截健康检查请求 |
| Consul SDK 版本差异 | 不同版本 Consul SDK(Orbitz / Ecwid / Spring Cloud)内部实现类名不同 | 低 | 拦截点优先使用通用 HTTP 层匹配 URL,不依赖具体 SDK 实现 |
| Pulsar 协议复杂度 | Pulsar binary protocol 包含 Lookup、Partitioned Topic、ManagedLedger 等复杂机制,Mock Broker 无法完整模拟 | 中 | v1 聚焦 Lookup + Producer/Consumer 核心路径;复杂特性在 v1.5 迭代 |
| Kafka API 版本兼容 | Kafka 客户端不同版本使用不同 API 版本 | 中 | Metadata/Produce/Fetch 三个 API 覆盖 v0-v12 主流版本;未覆盖 API 返回默认响应 |
| Consul 健康检查时序竞态 | DNS 缓存与 Agent 拦截的时序问题 | 未知 | 待实际验证;当前设计已透传健康检查,若验证发现竞态问题在 v1.0 发布前修复 |
| **热加载竞态** | 规则热加载过程中,正在匹配的连接可能读到半加载状态 | 低 | 采用"版本号 + 原子引用替换"策略,规则对象整体替换为不可变快照 |
| **多 Agent 实例模式一致性** | 同一环境下的多个 Agent 可能因为控制通道延迟而短暂处于不同模式 | 低 | 可接受的最终一致性;Agent 定期长轮询保证最终同步;模式切换指令丢失时,Agent 在下次长轮询超时后获取最新模式 |
| **控制通道可靠性** | Agent-Server 控制通道断开时,Agent 无法获取最新规则和模式切换指令 | 中 | Agent 本地 WatchService 作为降级方案;控制通道断开时 Agent 使用最后已知规则继续运行;模式切换指令通过长轮询最终一致性保证 |
| **环境配置错误** | 开发者错误配置 `environment` 字段,导致 Agent 加入错误环境 | 中 | Web 控制台环境管理页面显示每个环境下的 Agent 列表,便于运维排查;Agent 启动时日志明确输出所属环境 |
| **Plugin Advice 类不在 Bootstrap CL** | Plugin 的 installTransformers 注册的 Advice 类如果写在 Plugin jar 里,Bootstrap CL 找不到 → 目标类调用时崩溃 | 🔴 高 | Advice 类必须在 Core 中预定义;Plugin 只实现处理逻辑,不直接提供 Advice 类 |
| **Plugin 初始化失败** | Pulsar/TDMQ 插件初始化失败(如依赖版本不兼容),影响同进程其他协议 | 🟡 中 | 捕获插件初始化异常,打印 WARN 日志,该协议降级为 passthrough,不影响其他协议 |
| **录制数据丢失** | Agent 上传录制数据前 Server 重启,数据在 Agent 内存中丢失 | 🟡 中 | 录制数据写入磁盘后才返回成功;Agent 本地暂存录制数据,上传失败重试(R-A10 AC-05) |
| **Agent 环境归属混淆** | 规则全局共享但 Agent 分属不同环境,开发者可能混淆"为什么我的规则不生效" | 中 | Web 控制台全局显示当前 Agent 所属环境;日志中明确输出 Agent 环境 + 模式;文档中明确说明"规则全局共享,是否拦截取决于环境模式" |

---

## 附录 A:术语对照

| 术语 | 定义 |
|---|---|
| Baafoo Agent | 以 `-javaagent` 加载到 JVM 的字节码增强组件 |
| Baafoo Server | 独立进程,按协议分端口监听,接收重定向请求并返回 Mock 响应 |
| 挡板(Stub) | 模拟下游服务的 Mock 行为 |
| 透传(Passthrough) | 不拦截,直接连接真实下游 |
| 录制(Record) | 透明代理 + 复制请求/响应数据 |
| 回放(Replay) | 用录制数据作为 Mock 响应 |
| 服务名路由 | 按 Consul 注册的服务名(如 `order-service`)匹配规则 |
| 参数化规则 | 同一接口路径下,按不同请求参数条件返回不同 Mock 响应的规则 |
| Lookup | Pulsar 客户端在创建 Producer/Consumer 前查询 Topic 所在 Broker 地址的阶段 |
| **环境(Environment)** | Agent 所属的测试环境标识(如 ft-1、ft-2)。**环境是 Agent 的属性**,每个环境有独立的模式配置。多套测试环境的 Agent 连接同一 Server,Server 按环境维度独立控制挡板/透传/录制模式 |
| **模式(Mode)** | **模式是环境的属性**,由 Server 端维护。可选值:`stub`(拦截并挡板)、`passthrough`(透传不拦截)、`record`(透传并录制)、`record-and-stub`(挡板并录制) |

## 附录 B:竞品简要对比

| 产品 | HTTP | TCP | Kafka | Pulsar | JMS | 零侵入 | 录制回放 | 参数化规则 | Web 控制台 | 规则导入导出 | 多环境模式控制 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Baafoo** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅(Agent) | ✅ | ✅ | ✅ | ✅ |
| WireMock | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅(Proxy) | ✅ | ✅ | ✅ | ❌ |
| Hoverfly | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅(Proxy) | ✅ | 🔶 | ✅ | ❌ |
| MockServer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅(Proxy) | ✅ | 🔶 | ❌ | ❌ |
| Mountebank | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅(Proxy) | ✅ | 🔶 | ❌ | ❌ |

## 附录 C:默认端口分配表

| 协议 | 默认端口 | 配置键 | 说明 |
|---|---|---|---|
| HTTP(Mock + Web 控制台) | 9000 | `server.ports.http` | Web 控制台路径前缀 `/__baafoo__/` |
| TCP(Raw Socket) | 9001 | `server.ports.tcp` | 非协议嗅探,独立监听 |
| Kafka | 9002 | `server.ports.kafka` | 模拟 Metadata / Produce / Fetch |
| Pulsar | 9003 | `server.ports.pulsar` | 含 Lookup 阶段模拟 |
| JMS | 9004 | `server.ports.jms` | ActiveMQ Artemis 内嵌 |

## 附录 D:参数化规则配置示例

```yaml
http:
  # 同一接口,不同参数返回不同响应
  - id: get-user-by-level
    request:
      method: GET
      path: /api/users/{id}
    responses:                          # 多条件分支(按顺序匹配,首个命中返回)
      - condition:
          header:
            X-User-Level: VIP
        response:
          status: 200
          body: |
            {"id": "{{path.id}}", "name": "Mock VIP User", "discount": 0.8}
      - condition:
          query:
            detail: "true"
        response:
          status: 200
          body: |
            {"id": "{{path.id}}", "name": "Mock User", "detail": "full profile data"}
      - response:                       # 默认响应(无条件匹配)
          status: 200
          body: |
            {"id": "{{path.id}}", "name": "Mock User", "status": "active"}

  # JSONPath body 匹配
  - id: create-order-by-type
    request:
      method: POST
      path: /api/orders
    responses:
      - condition:
          body:
            jsonPath: "$.orderType"
            value: "VIP"
        response:
          status: 201
          body: |
            {"orderId": "ORD-VIP-001", "priority": "high", "discount": 0.8}
      - condition:
          body:
            jsonPath: "$.orderType"
            value: "BULK"
        response:
          status: 201
          body: |
            {"orderId": "ORD-BULK-001", "priority": "normal", "batch": true}
      - response:                        # 默认响应
          status: 201
          body: |
            {"orderId": "ORD-{{body.orderType}}-001", "priority": "normal"}
```

## 附录 E:多测试环境配置与运行示例

### Agent 配置文件示例(`baafoo-agent.yml`)

```yaml
# Server 连接配置
server:
  host: 127.0.0.1
  ports:
    http: 9000
    tcp: 9001
    kafka: 9002
    pulsar: 9003
    jms: 9004

# 环境标识(Agent 所属的测试环境,由 Server 端配置该环境的模式)
# 注意:mode 字段已移除,不在本地配置模式
environment: ft-1

# 控制通道配置
control:
  heartbeatInterval: 30s
  heartbeatTimeout: 90s

# 路由规则(规则全局共享,不按环境区分)
# 规则是否生效取决于 Agent 所属环境的模式
rules:
  - target: "order-service.service.consul"
    protocol: http

  - target: "payment-service.service.consul"
    protocol: http

  - target: "kafka-cluster:9092"
    protocol: kafka

  - target: "192.168.1.100:8080"
    protocol: http
```

### Server 端环境配置(通过 REST API 或 Web 控制台操作)

```bash
# 创建测试环境,配置模式
curl -X POST http://localhost:9000/__baafoo__/api/environments \
  -H 'Content-Type: application/json' \
  -d '{"name": "ft-1", "mode": "stub", "description": "FT-1 挡板自测环境"}'

curl -X POST http://localhost:9000/__baafoo__/api/environments \
  -H 'Content-Type: application/json' \
  -d '{"name": "ft-2", "mode": "passthrough", "description": "FT-2 透传联调环境"}'

curl -X POST http://localhost:9000/__baafoo__/api/environments \
  -H 'Content-Type: application/json' \
  -d '{"name": "ft-3", "mode": "record", "description": "FT-3 录制环境"}'

# 切换 ft-2 从透传到挡板(即时生效,无需重启 Agent)
curl -X PUT http://localhost:9000/__baafoo__/api/environments/ft-2 \
  -H 'Content-Type: application/json' \
  -d '{"mode": "stub"}'
```

### 运行场景示例

**场景**:团队有 3 套功能测试环境,共享同一个 Baafoo Server

| 测试环境 | Agent 配置 `environment` | Server 端模式 | 行为 |
|---|---|---|---|
| FT-1 | `ft-1` | `stub` | 所有请求被挡板拦截,返回 Mock 响应 |
| FT-2 | `ft-2` | `passthrough` | 所有请求透传到真实下游联调 |
| FT-3 | `ft-3` | `record` | 连接真实下游,同时录制请求/响应 |

FT-1 启动:`java -javaagent:baafoo-agent.jar=config=baafoo-agent-ft1.yml -jar my-app.jar`
FT-2 启动:`java -javaagent:baafoo-agent.jar=config=baafoo-agent-ft2.yml -jar my-app.jar`

(两个配置文件仅 `environment` 字段不同,分别为 `ft-1` 和 `ft-2`)

Agent 注册时上传环境标识,Server 根据环境配置下发模式。模式切换通过 Server 端 API 或 Web 控制台操作,即时生效,无需重启任何 Agent。

---

*本文档为 Baafoo v1.5 产品需求文档。需求优先级和排期将在技术方案设计阶段与工程团队对齐后确定。*
