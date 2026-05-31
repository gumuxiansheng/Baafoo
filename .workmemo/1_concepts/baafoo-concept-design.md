# Baafoo 挡板系统 — 概念设计说明书

> **文档状态**：概念设计（v0.8）  
> **对齐状态**：⚠️ PRD 已迭代至 v2.2，本文档尚未同步规则环境绑定（v2.0）、RBAC（v2.2）等变更  
> **目标读者**：产品经理、架构师、开发负责人  
> **最后更新**：2026-05-29  
> **撰写目的**：为产品经理提供完整的技术背景与产品边界，便于进一步形成产品说明书及需求文档  
> **变更摘要**：v0.8 — 同步 PRD v1.5 全部变更：默认未匹配行为修正、fail-closed、baafoo init、规则版本管理/Undo、场景集管理、Kafka Beta 状态、配置项说明表、风险表同步、路线图修正

---

## 1. 背景与问题定义

### 1.1 现实痛点

在微服务/分布式系统的日常开发中，应用通常依赖多个下游服务（REST API、Socket 服务、消息队列等）。在 **开发/联调阶段**，下游服务普遍存在以下问题：

| 问题类型 | 具体表现 |
|---|---|
| 环境不稳定 | 下游开发环境频繁重启、接口变更导致联调阻塞 |
| 访问受限 | 生产/预发布环境下游无法在开发机直接访问 |
| 数据不可控 | 测试场景需要特定边界数据，下游环境无法构造 |
| 协同等待 | 下游团队开发进度滞后，导致上游阻塞 |
| 异常难复现 | 超时、断链、错误码等异常场景难以主动触发 |

### 1.2 现有解决方案的局限

- **Mock 框架（Mockito/PowerMock）**：侵入代码，必须修改测试代码或业务代码
- **WireMock**：仅支持 HTTP，无法覆盖 Socket、MQ 等协议
- **手动 hosts 修改**：系统全局生效，影响其他进程；粒度粗，无法按请求路由
- **Proxy 环境变量**：仅对 HTTP 有效，无法处理私有 TCP 协议

### 1.3 目标

**Baafoo** 是一款基于 **JavaAgent 技术**的开发阶段挡板系统，目标是：

> 在**不修改任何业务代码、不改变启动脚本结构**的前提下，拦截应用对下游服务的网络调用，按规则转发至模拟挡板，支持 HTTP/REST、原生 TCP/Socket、消息队列等多种协议的 Mock 模拟。

---

## 2. 核心概念

### 2.1 名词定义

| 术语 | 定义 |
|---|---|
| **Baafoo Agent** | 以 `-javaagent` 形式附加到目标 JVM 的字节码增强组件，负责拦截网络连接请求并做地址重写。**Agent 声明自己所属的环境**（如 `ft-1`），运行模式由 Server 端根据该环境的配置下发 |
| **Baafoo Server（挡板服务）** | 独立进程，接收被重定向的网络请求，按配置规则返回 Mock 响应。**Server 端维护环境→模式的映射关系** |
| **环境（Environment）** | Agent 所属的测试环境标识（如 `ft-1`、`ft-2`）。**模式（stub/passthrough/record）是环境的属性**，同一环境的所有 Agent 使用相同模式 |
| **挡板规则（Stub Rule）** | 描述"如何识别一个请求"以及"返回什么响应"的配置单元。**规则全局共享**，对所有环境可见；仅当 Agent 处于 `stub` 模式时才参与匹配 |
| **透传（Passthrough）** | Agent 所在环境为 `passthrough` 模式时，Agent 不拦截连接，等效于未安装 Agent |
| **录制（Record）** | Agent 所在环境为 `record` 模式时，Agent 透明代理真实连接，同时将请求/响应原始字节复制存储，用于后续回放 |
| **录制+挡板（Record-and-Stub）** | Agent 所在环境为 `record-and-stub` 模式时，Agent 同时执行挡板拦截和录制：按规则返回 Mock 响应，同时将请求/响应录制存储，用于后续回放和对比 |
| **热切换** | 通过 Server 端环境管理 API 切换环境模式，Agent 通过控制通道实时接收模式变更，无需重启应用 |
| **控制通道** | Agent 与 Server 之间的 HTTP 长轮询通道，用于心跳上报、规则拉取、模式切换指令下发 |

### 2.2 设计原则

1. **零侵入**：业务代码无任何修改，通过 JVM 启动参数附加能力
2. **进程级隔离**：仅影响挂载了 Agent 的 JVM 进程，不影响系统其他进程
3. **协议无关性**：Agent 层工作在 TCP 连接层面，天然支持所有基于 TCP 的协议
4. **环境级模式控制**：多套测试环境共享同一 Server，按环境维度独立控制模式（stub / passthrough / record / record-and-stub）
5. **可观测**：每条请求的来源、目标、转发结果、响应时延均可记录

---

## 3. 系统架构

### 3.1 整体架构分层

```
┌─────────────────────────────────────────────────────────┐
│                  目标应用进程（ft-1 环境）           │
│                                                         │
│   业务代码  ──→  Socket / NIO Channel / MQ Client  │
│                        │                               │
│              ┌─────────▼──────────┐                    │
│              │   Baafoo Agent     │  字节码增强层        │
│              │  (字节码注入)       │                    │
│              │  · connect() 拦截  │                    │
│              │  · 地址路由重写     │                    │
│              │  · 环境: ft-1     │  （从配置文件读取） │
│              └─────────┬──────────┘                    │
└────────────────┼────────────────────────────────────────┘
                         │  TCP 连接（地址已被重写）
          ┌──────────────▼──────────────┐
          │       Baafoo Server         │
          │       （独立进程）           │
          │                             │
          │  ┌──────────┐ ┌──────────┐  │
          │  │ HTTP 处理 │ │ TCP 处理 │  │
          │  └──────────┘ └──────────┘  │
          │  ┌──────────┐ ┌──────────┐  │
          │  │ MQ 模拟  │ │ 规则引擎 │  │
          │  └──────────┘ └──────────┘  │
          │                             │
          │  ┌─────────────────────┐  │
          │  │  环境管理                │  │
          │  │  ft-1 → stub              │  │
  │  │  ft-2 → passthrough      │  │
  │  │  ft-3 → record            │  │
  │  │  ft-4 → record-and-stub  │  │
          │  └─────────────────────┘  │
          └──────────────┬──────────────┘
                         │
          ┌──────────────▼──────────────┐
          │          规则配置            │
          │  stub-rules.yml（热加载）    │
          │  （全局共享，无环境字段）    │
          └─────────────────────────────┘
```

### 3.2 Baafoo Agent 工作原理

Agent 基于 Java Instrumentation API，在 JVM 启动的 `premain` 阶段完成字节码增强：

**拦截点**（按优先级）：

| 拦截目标 | 覆盖场景 | 实现方式 |
|---|---|---|
| `java.net.Socket#connect()` | OkHttp、Apache HttpClient、原生 Socket、JDBC | Byte Buddy Advice 内联 |
| `sun.nio.ch.SocketChannelImpl#connect()` | Netty、NIO 框架 | Byte Buddy Advice 内联（需 `--add-opens`） |
| `javax.jms.ConnectionFactory#createConnection()` | ActiveMQ、RocketMQ JMS 客户端 | Byte Buddy 方法代理 |
| `org.apache.kafka.clients.producer.KafkaProducer` / `KafkaConsumer` | Kafka 客户端 | 构造函数拦截，替换 bootstrap.servers |
| `org.apache.pulsar.client.api.PulsarClient#builder()` + `ClientBuilder#serviceUrl()` | **TDMQ for Pulsar** / Apache Pulsar 客户端 | Byte Buddy 拦截 Builder 的 `serviceUrl()` 参数和 `build()` 方法，将 `pulsar://broker:6650` 替换为 `pulsar://localhost:9003` 指向挡板 |
| `java.net.InetAddress#getByName()` / `getAllByName()` | **Consul DNS 模式**（8600 端口）| Byte Buddy 包裹方法，拦截 `.consul` 后缀域名的解析结果，返回挡板地址 |
| `okhttp3.RealCall#execute()` + Consul API 路径匹配 | **Consul HTTP API** 服务发现模式 | 拦截对 Consul Agent `/v1/catalog/service/*` 的调用，篡改返回的 Address:Port |

> **字节码增强框架统一说明**：所有拦截点统一使用 Byte Buddy 实现。对于 `sun.nio.ch.SocketChannelImpl` 等 JDK 内部类，采用 Byte Buddy 的 `Advice` 内联机制（而非独立的 ASM 改写），通过 `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` 参数开放访问。针对不同 JDK 版本（8/11/17）的内部类签名差异，通过运行时版本检测加载对应的 Advice 实现类来适配。

**地址重写逻辑**（双入口）：

```
场景一：固定 host:port（无注册中心）
  原始目标: downstream-a.dev:8080
  ↓ 查询路由表（精确匹配）
  匹配规则: downstream-a.dev:8080 → 挡板模式
  ↓ 重写目标地址
  实际连接: localhost:9000

场景二：Consul 服务发现（有注册中心）
  业务代码: consulClient.discover("order-service")
  ↓ Consul 返回: order-service → 10.0.1.5:8080
  ↓ Agent 在 InetAddress 解析层拦截：
  ↓ 查路由表: order-service（按服务名匹配）
  匹配规则: order-service → 挡板模式
  ↓ 重写解析结果
  实际连接: localhost:9000（带有 order-service 标识）
```

**Agent 运行模式决策流程**：

```
Agent 启动
  │
  ▼
读取配置文件: environment: ft-1
  │
  ▼
向 Server 注册: POST /api/agent/register { environment: "ft-1" }
  │
  ▼
Server 查询环境 ft-1 的模式: stub / passthrough / record / record-and-stub
  │
  ▼
下发模式到 Agent（注册响应 + 控制通道长轮询）
  │
  ├── 模式 = stub
  │   → Agent 正常拦截连接，路由规则参与匹配
  │   → 匹配成功 → 重定向到 Baafoo Server 对应协议端口
  │   → 匹配失败 → 抛出异常（或返回 404，取决于配置）

  > **fail-closed 策略**：Agent 加载失败（如配置文件不存在、字节码变换异常）时，默认 fail-closed：应用正常启动但输出 ERROR 日志，所有请求走真实下游；可通过 `baafoo.agent.fail-open=true` 配置项改为静默透传

  ├── 模式 = passthrough
  │   → Agent 不拦截连接，等效于未安装 Agent
  │   → 所有请求直接透传到真实下游
  │
  ├── 模式 = record
  │   → Agent 透明代理真实连接（不重定向）
  │   → 同时录制请求/响应字节到内存缓冲区
  │   → 缓冲区满或 session 结束时上传至 Server
  │
  └── 模式 = record-and-stub
      → Agent 按规则拦截连接，重定向到挡板（同 stub 模式）
      → 同时录制请求/响应字节到内存缓冲区（同 record 模式）
      → 缓冲区满或 session 结束时上传至 Server
```

#### 3.2.1 插件化架构

> 详见《Baafoo Agent 插件化架构建议》

Agent 采用 **Core Advice 内联 + Plugin 逻辑委托** 的分层架构，解决 Pulsar/TDMQ 等协议的 SDK 依赖冲突问题：

| 层 | 职责 | ClassLoader | 部署方式 |
|---|---|---|---|
| **Core** | 内置 HTTP/TCP/Consul 拦截 Advice + Plugin SPI 接口 | Bootstrap CL | Agent 主 jar |
| **Plugin** | 协议特定逻辑（Kafka/Pulsar/JMS） | 独立 URLClassLoader（parent=null） | v1.0 内嵌；v1.5 外置 jar |

**设计要点**：
- Advice 类（字节码增强的拦截点）必须在 Core 中预定义，确保目标类可见
- Plugin 只实现处理逻辑（协议编解码、Mock 行为），不直接提供 Advice 类
- 插件 ClassLoader `parent=null`，与宿主应用依赖完全隔离
- v1.0 Pulsar 插件代码内嵌在 Core 中但按 Plugin 接口编写，验证后 v1.5 再拆出独立 jar

**SPI 接口定义**：

```java
public interface AgentPlugin {
    String name();                                    // 插件名（如 "pulsar"）
    void onInstall(PluginContext ctx);                 // 安装回调
    InterceptResult onIntercept(InterceptTarget target); // 处理拦截
    void onUninstall();                               // 卸载回调
}
```

### 3.3 Baafoo Server 工作原理

Baafoo Server **按协议分配独立监听端口**，不同协议的网络特征差异大（Kafka 二进制协议、Pulsar binary protocol、HTTP、TCP 等），独立端口消除了协议嗅探的不确定性和误判风险，同时使 Agent 端口重写逻辑更简洁明确。

**默认端口分配**：

| 协议 | 默认端口 | 配置键 | 说明 |
|---|---|---|---|
| HTTP（Mock + Web 控制台） | 9000 | `server.ports.http` | Web 控制台路径前缀 `/__baafoo__/` |
| TCP（Raw Socket） | 9001 | `server.ports.tcp` | 非协议嗅探，独立监听 |
| Kafka | 9002 | `server.ports.kafka` | 模拟 Metadata / Produce / Fetch |
| Pulsar | 9003 | `server.ports.pulsar` | 含 Lookup 阶段模拟 |
| JMS | 9004 | `server.ports.jms` | ActiveMQ Artemis 内嵌 |

所有端口均可通过配置文件覆盖。未启用某协议的 Mock Broker 时（如无 Kafka 规则），对应端口不启动监听，减少资源占用。

**各协议处理流程**：

```
入站 TCP 连接（按端口区分协议）
    │
    ├── HTTP 端口 (9000) ──→ HTTP Mock Handler（规则匹配 → 响应模板）
    │
    ├── TCP 端口 (9001) ──→ Raw TCP Handler（字节级匹配 → 录制回放）
    │
    ├── Kafka 端口 (9002) ──→ Kafka Mock Broker（topic/partition 模拟）
    │
    ├── Pulsar 端口 (9003) ──→ Pulsar Mock Broker（tenant/namespace/topic 模拟）
    │
    └── JMS 端口 (9004) ──→ MQ Mock Broker（队列/主题模拟）
```

### 3.4 Agent-Server 控制通道

Agent 与 Server 之间除数据通道（TCP 连接重定向）外，还需要一个轻量 HTTP 控制通道，用于以下场景：

**3.4.1 控制通道职责**

| 功能 | 方向 | 说明 |
|---|---|---|
| 心跳上报 | Agent → Server | Agent 每 30s 上报存活状态，Server 据此维护活跃 Agent 列表 |
| 规则拉取 | Agent → Server | Agent 启动时及规则变更通知后，从 Server 拉取最新路由规则（规则全局共享） |
| 规则变更通知 | Server → Agent | Server 端规则变更后，通过长轮询或 WebSocket 通知 Agent 刷新 |
| 模式切换指令 | Server → Agent | Server 端切换某环境的模式时，通过控制通道向该环境下所有 Agent 下发模式切换指令 |
| 录制数据上传 | Agent → Server | Agent 录制模式下，将录制数据上传至 Server 统一存储 |

**3.4.2 控制通道协议**

```
Agent ─── HTTP POST /api/agent/register ───→ Server
           Body: { agentId, pid, appName, environment, timestamp }
           Response: { agentId, mode }  # Server 返回该环境的当前模式

Agent ─── HTTP POST /api/agent/heartbeat ───→ Server
           Body: { agentId, pid, appName, mode, timestamp }

Agent ─── HTTP GET /api/agent/rules ─────────→ Server
           Response: { version, rules: [...] }  # 规则全局共享，无环境过滤

Agent ─── HTTP POST /api/agent/recordings ───→ Server
           Body: multipart/form-data (session metadata + recording blob)

Server ─── 长轮询 /api/agent/poll?agentId=xxx ─→ Agent
           Response: { command: "RELOAD_RULES" | "SWITCH_MODE", payload: ... }
           # SWITCH_MODE: { mode: "stub" | "passthrough" | "record" | "record-and-stub" }
```

**3.4.3 设计要点**

- 控制通道使用 HTTP 协议，复用 Server 的 HTTP 端口（9000），路径前缀 `/api/agent/`
- Agent 启动时向 Server 注册（`POST /api/agent/register`），获取该环境的当前模式
- 心跳超时（默认 90s）后 Server 将 Agent 标记为离线，Web 控制台状态同步更新
- 录制数据上传采用分片上传机制，避免大文件传输失败
- Agent 本地仍保留路由规则文件的 WatchService 监听，作为 Server 不可用时的降级方案
- **模式切换实时下发**：Server 端环境模式变更后，通过控制通道长轮询立即下发到该环境下所有在线 Agent，无需重启

---

### 3.5 微服务/Consul 架构适配

#### 3.5.1 问题本质

Consul 作为注册中心，改变了传统"硬编码 host:port"的连接模式。服务消费者不再直连固定地址，而是通过**两步**完成调用：

```
业务代码请求 "order-service"
    │
    ▼
服务发现层（Consul）
    ├── DNS 模式：查询 order-service.service.consul → 10.0.1.5:8080
    └── HTTP API 模式：GET /v1/catalog/service/order-service → [{Address:"10.0.1.5", Port: 8080}]
    │
    ▼
TCP 连接层：Socket.connect("10.0.1.5", 8080)
```

如果 Agent 只在 `Socket.connect()` 层面拦截，**拿到的是已解析的 IP**，丢失了服务名语义——无法按 `order-service` 这个逻辑名称来配置挡板规则。

#### 3.5.2 拦截策略：前移一个抽象层

Baafoo 的设计是**在服务发现层拦截，而非 TCP 连接层**，以保留服务名上下文。针对 Consul 的两种解析路径，分别采用对应策略：

**策略 A：InetAddress 解析拦截（适用 DNS 模式）**

Java 应用通过 `InetAddress.getByName("order-service.service.consul")` 完成 DNS 解析。Agent 在此方法处拦截，识别 `.consul` 后缀域名：

```java
@RuntimeType
public static InetAddress getByName(@Argument(0) String host,
                                     @SuperCall Callable<InetAddress> zuper) {
    if (host.endsWith(".service.consul")) {
        String serviceName = extractServiceName(host);
        InetSocketAddress stubAddr = StubConfig.getStubAddrByService(serviceName);
        if (stubAddr != null) {
            return InetAddress.getByAddress(stubAddr.getHostString(),
                    stubAddr.getAddress().getAddress());
        }
    }
    return zuper.call();
}
```

**策略 B：Consul HTTP API 响应篡改（适用 SDK 模式）**

使用 Consul SDK（如 Spring Cloud Consul、Orbitz Consul Client）的应用，通过 HTTP 调用 Consul Agent 的 REST API。Agent 拦截 HTTP 客户端对 `/v1/catalog/service/*` 和 `/v1/health/service/*` 的请求：

1. 截获 HTTP 请求，识别目标 URL 中的服务名
2. 查询路由表，判断该服务名是否需要挡板
3. 若是：不发起真实 HTTP 请求，直接构造响应体，将返回的 IP:Port 替换为挡板地址
4. 若否：透传正常请求

```json
// Consul 真实返回 → Agent 篡改后返回
[{"Address":"10.0.1.5","ServicePort":8080}] → [{"Address":"127.0.0.1","ServicePort":9000}]
```

#### 3.5.3 Socket 直连在 Consul 架构中的处理

即便部分服务使用原生 Socket 协议（非 HTTP），其连接流程依然先经过 Consul 解析。Socket 连接的地址来源于：

| 方式 | 拦截时机 | 说明 |
|---|---|---|
| 代码中通过服务名调用 Consul SDK 解析 | 策略 B（HTTP API 拦截） | Agent 篡改返回的 Address:Port，Socket.connect() 自动使用挡板地址 |
| 使用 `service.consul` DNS 名 | 策略 A（InetAddress 拦截） | Socket 连接前 IP 已被 Agent 替换 |
| 硬编码 IP + Consul Sidecar（如 Envoy）| 策略 A+B 组合 | 需同时拦截 Sidecar 的 DNS 查询和 xDS 协议 |

**核心结论**：Socket 直连场景**不需要额外的协议层面处理**——只要在服务发现阶段完成地址替换，Socket 层自然连到挡板。挡板 Server 端通过协议嗅探识别为 TCP 二进制流量，走 Raw TCP Handler。

#### 3.5.4 服务名路由 vs host:port 路由

Baafoo 路由表同时支持两种匹配维度：

| 维度 | 配置方式 | 适用场景 |
|---|---|---|
| **服务名匹配**（推荐）| `service: "order-service"` | Consul 注册中心架构 |
| **host:port 匹配** | `target: "10.0.1.5:8080"` | 直连下游、无注册中心 |
| **组合匹配** | `service + target` 可选指定 | 精确区分同名服务的不同实例 |

服务名匹配的优先级**高于** host:port 匹配——因为服务名语义更清晰，更贴近开发者的心智模型。

#### 3.5.5 支持的注册中心

| 注册中心 | 支持状态 | 拦截策略 |
|---|---|---|
| **Consul** | ✅ v1.0 全面支持 | DNS 拦截 + HTTP API 拦截 |
| **Eureka** (Spring Cloud) | 🔶 v1.5 规划 | DiscoveryClient 拦截 |
| **Nacos** | 🔶 v2.0 规划 | DNS 拦截 + HTTP API 拦截 |
| **ZooKeeper** (Dubbo) | 🔶 v2.0 规划 | ZooKeeper 客户端拦截 |
| **Kubernetes DNS** | 🔶 v1.5 规划 | CoreDNS 解析拦截 |

---

## 4. 功能模块

### 4.1 Agent 功能模块

#### 4.1.1 连接拦截器

- 拦截 Socket 层的 `connect()` 调用
- 根据目标 `host:port` 查询路由规则
- **Agent 运行行为取决于所属环境的模式**（从 Server 端获取）：
  - `stub` 模式：按路由规则重定向到挡板
  - `passthrough` 模式：不拦截，等效于未安装 Agent
  - `record` 模式：透明代理真实连接，同时录制请求/响应
  - `record-and-stub` 模式：按规则拦截返回 Mock 响应，同时录制请求/响应

#### 4.1.2 路由规则引擎

- 支持精确匹配：`downstream-a.dev:8080`
- 支持通配符：`*.dev:*`、`192.168.1.*:3306`
- 规则优先级：精确 > 通配 > 默认（默认返回 404 并记录 WARN 日志；可通过 `baafoo.stub.unmatched-default` 配置项修改为 passthrough）
- 配置文件热重载（WatchService 监听文件变化，无需重启应用）
- **热加载竞态安全策略**：规则切换采用"版本号 + 原子引用替换"机制——新请求读取最新版本规则，正在匹配中的旧连接继续使用旧版本规则完成处理，避免规则半加载状态下的不一致匹配。规则对象整体替换（不可变快照），而非逐字段更新。

#### 4.1.3 录制器（可选）

- 在 `record` 模式下，透明代理真实连接，同时复制请求/响应字节流暂存于 Agent 内存缓冲区
- 缓冲区满或录制 session 结束时，Agent 通过控制通道将录制数据上传至 Server 统一存储
- Server 端存储格式：JSON 元数据文件 + 原始字节 blob 文件，以 session ID 组织
- 支持按 session 录制，生成 session ID
- Server 端提供 `GET /api/recordings` API 列出所有录制 session，支持 Web 控制台管理
- 录制数据保留策略由 Server 端配置（`recording.retentionDays` + `recording.maxSizeMb`），自动清理过期数据

### 4.2 Baafoo Server 功能模块

#### 4.2.1 HTTP/REST 挡板

- 请求匹配维度：`method + path + query + headers + body`（支持 JSONPath/正则）
- 响应配置：`status code + headers + body`（支持模板变量）
- 支持响应延迟模拟（固定/随机/正态分布）
- 支持异常模拟：连接重置、读超时、5xx 错误

#### 4.2.2 原生 TCP/Socket 挡板

- 字节级请求匹配（前缀匹配、正则匹配原始 bytes）
- 响应数据支持：静态字节序列、录制回放、脚本生成
- 支持长连接保持与多轮交互模拟。

#### 4.2.3 Kafka 挡板

- 模拟 Kafka Broker 的最小协议子集（Producer/Consumer 核心 API）
- 支持指定 Topic 产生预设消息序列
- 支持消费者 offset 管理模拟
- 支持生产者响应延迟与 ack 模式模拟。

#### 4.2.4 JMS/MQ 挡板

- 内嵌轻量 MQ Broker（基于 ActiveMQ Artemis 嵌入模式）
- 应用的 JMS ConnectionFactory 被 Agent 替换为指向内嵌 Broker 的工厂实例
- 支持 Queue、Topic 两种模式
- 支持消息延迟投递、消息顺序控制、死信队列模拟。

#### 4.2.5 Pulsar / TDMQ 挡板

- 模拟 Pulsar Broker 的最小协议子集（基于 Pulsar binary protocol，端口默认 6650）
- Agent 层通过拦截 `PulsarClient.builder().serviceUrl()` 将连接重定向至 Baafoo Server
- Server 内嵌轻量 Pulsar Mock Broker，支持以下核心功能：
  - **Producer**：模拟消息确认（`send()` 返回 MessageId）
  - **Consumer**：按 subscription 投递预设消息序列
  - **Schema 注册**：支持基础 Primitive Schema（STRING / JSON / AVRO）
  - **延迟消息投递**：配置 `delayMs` 指定消息投递间隔
- 命中 TDMQ for Pulsar 的全部 Java SDK，**对业务代码零侵入**：
  - `com.tencent.tdmq:tdmq-client`（腾讯封装 SDK）
  - `org.apache.pulsar:pulsar-client`（Apache 官方 SDK）
- 规则示例见 5.2 挡板规则文件

> **说明**：TDMQ for Pulsar 是腾讯云基于 Apache Pulsar 的托管消息队列服务。Baafoo 拦截的是 Pulsar 客户端的二进制协议，与 TDMQ 的管控面无关，因此 TDMQ 和开源 Pulsar **均被同一套拦截逻辑覆盖**。

#### 4.2.6 环境管理

- **环境是 Server 端的概念**：Server 维护环境列表，每个环境有独立的模式配置
- **Agent 注册时声明所属环境**：Agent 启动时才告知 Server 自己属于哪个环境
- **模式是环境的属性**：切换环境的模式（stub ↔ passthrough ↔ record ↔ record-and-stub），该环境下所有 Agent 实时同步切换
- **环境自动创建**：Agent 注册时声明的 `environment` 在 Server 端不存在时，Server 自动创建该环境（默认模式 `passthrough`），避免 Agent 无法启动
- **环境管理 API**：提供 REST API 供外部系统或 Web 控制台管理环境（增删改查、模式切换）
- **环境模式切换实时下发**：模式变更后，Server 通过控制通道向该环境下所有 Agent 下发模式切换指令，Agent 无需重启

#### 4.2.7 规则管理 API

- 提供 REST API 供外部管理规则（增删改查）
- **规则全局共享**：规则对所有环境可见，无 `environments` 字段
- 规则是否生效取决于 Agent 的模式：仅 `stub` 模式 Agent 才参与规则匹配
- 支持 Web 控制台（可选）：可视化查看规则、请求日志、实时流量
- 规则变更实时生效，无需重启 Server
- **规则版本管理与 Undo**：每次规则保存前自动快照前一版本，保留最近 10 个版本，支持通过 API 或 Web 控制台一键回退
- **场景集管理（简化版）**：支持将一组规则组织为场景集（1:N 关系），一键启用/禁用整组规则

#### 4.2.8 请求日志与可观测性

- 记录每条入站请求的：来源 Agent 进程、协议类型、请求摘要、匹配规则、响应内容、耗时
- 支持导出为 HAR 格式（HTTP）或自定义格式（TCP/MQ）
- 提供简单的 Dashboard（请求数、命中率、平均响应时间）

---

## 5. 配置模型

### 5.1 Agent 配置文件（`baafoo-agent.yml`）

```yaml
# Baafoo Agent 配置
# 注意：mode 字段已移除，模式由 Server 端根据环境配置下发

server:
  host: localhost
  ports:
    http: 9000       # HTTP Mock + Web 控制台
    tcp: 9001        # Raw TCP
    kafka: 9002      # Kafka Mock Broker
    pulsar: 9003     # Pulsar Mock Broker
    jms: 9004        # JMS Mock Broker

# 环境标识：Agent 所属的测试环境（必填）
# Server 端根据此环境名的配置下发模式（stub/passthrough/record）
# 若不配置，默认为 "default" 环境
environment: ft-1

consul:
  enabled: true                         # 启用 Consul 集成
  interceptionMode: dns                 # dns | api | auto（自动检测）
  agentUrl: http://consul.agent:8500    # 仅 api 模式需要

recording:
  retentionDays: 7                      # 录制数据保留天数
  maxSizeMb: 500                        # 录制数据最大磁盘占用

stubs:
  # 方式一：服务名匹配（Consul 架构推荐）
  - service: "order-service"
    # 注意：不配置 mode，mode 由 Agent 所属环境的 Server 端配置决定

  # 方式二：host:port 匹配（直连架构）
  - target: "downstream-a.dev:8080"

  # 方式三：服务名 + 协议提示（混合）
  - service: "socket-legacy-service"
    protocol: tcp                       # 协议提示，决定重定向到哪个 Server 端口

  # Kafka 连接挡板
  - target: "kafka.dev:9092"
    protocol: kafka

  # TDMQ for Pulsar 挡板
  - target: "pulsar-tdmq.dev:6650"
    protocol: pulsar

  # ActiveMQ JMS 挡板
  - target: "mq.dev:61616"
    protocol: jms

  # 透传规则（仅对 stub 模式 Agent 生效；passthrough 模式 Agent 不拦截）
  - target: "db.dev:5432"
    # 不需要显式配置，passthrough 模式下 Agent 自动透传

  # 录制规则（仅对 record 模式 Agent 生效）
  - service: "*.unstable.*"
```

> **配置说明**：
> - `mode` 字段已移除：Agent 不在本地配置模式，模式由 Server 端根据环境配置下发
> - `environment` 字段：声明 Agent 所属的环境，Server 端根据此环境名的配置下发模式
> - 规则中的 `mode` 字段也已移除：规则全局共享，是否生效取决于 Agent 的模式

### 5.2 挡板规则文件（`stub-rules.yml`）

```yaml
# 规则全局共享，无 environments 字段
# 规则是否生效取决于 Agent 的模式：仅 stub 模式 Agent 才参与规则匹配

# HTTP 挡板规则示例
http:
  - id: get-user-success
    request:
      method: GET
      path: /api/users/{id}
    responses:                          # 统一使用 responses 数组
      - condition:
          header:
            X-User-Level: VIP
        response:
          status: 200
          headers:
            Content-Type: application/json
          body: |
            {"id": "{{path.id}}", "name": "Mock VIP User", "discount": 0.8}
      - response:                       # 默认响应（无条件匹配）
          status: 200
          headers:
            Content-Type: application/json
          body: |
            {"id": "{{path.id}}", "name": "Mock User", "status": "active"}
          delay: 50   # ms

  - id: create-order-timeout
    request:
      method: POST
      path: /api/orders
    responses:
      - response:
          fault: READ_TIMEOUT   # 模拟超时

# TCP 挡板规则示例
tcp:
  - id: binary-protocol-login
    request:
      prefixHex: "01 02 03"   # 请求魔数
    responses:
      - response:
          dataHex: "01 02 00 00 00 01"  # 登录成功响应

# Kafka 挡板规则示例
kafka:
  - topic: order-events
    messages:
      - key: "order-001"
        value: '{"orderId":"001","status":"PAID"}'
        delay: 100

# Pulsar / TDMQ 挡板规则示例
pulsar:
  - tenant: public
    namespace: default
    topic: order-events
    subscription: my-sub
    messages:
      - key: "order-001"
        value: '{"orderId":"001","status":"PAID"}'
        delay: 100   # 投递延迟 (ms)
        properties:
          eventType: "PAYMENT_SUCCESS"
      - key: "order-002"
        value: '{"orderId":"002","status":"SHIPPED"}'
```

### 5.3 Server 环境配置（运行时，通过 API 或 Web 控制台管理）

```
环境列表（Server 内存状态）：
{
  "environments": [
    {
      "name": "ft-1",
      "mode": "stub",           // 该环境下所有 Agent 的运行模式
      "description": "FT-1 挡板自测环境",
      "createdAt": "2026-05-29T10:00:00Z",
      "agentCount": 3
    },
    {
      "name": "ft-2",
      "mode": "passthrough",
      "description": "FT-2 透传联调环境",
      "createdAt": "2026-05-29T10:05:00Z",
      "agentCount": 2
    },
    {
      "name": "ft-3",
      "mode": "record",
      "description": "FT-3 录制环境",
      "createdAt": "2026-05-29T10:10:00Z",
      "agentCount": 1
    },
    {
      "name": "ft-4",
      "mode": "record-and-stub",
      "description": "FT-4 录制+挡板环境",
      "createdAt": "2026-05-29T10:15:00Z",
      "agentCount": 1
    }
  ]
}
```

### 5.4 关键配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `baafoo.agent.fail-open` | boolean | false | Agent 加载失败时是否静默透传；false = fail-closed（应用正常启动但输出 ERROR 日志，所有请求走真实下游） |
| `baafoo.stub.unmatched-default` | enum | `"404"` | 未匹配规则时的行为：`"404"`（返回 404 + WARN 日志）或 `"passthrough"`（透传真实下游） |
| `baafoo.recording.memory-limit` | String | `"64MB"` | Agent 录制缓冲区上限，超过后触发上传或丢弃 |
| `baafoo.recording.auto-cleanup-days` | int | `7` | Server 端录制数据自动清理天数 |
| `baafoo.heartbeat.interval` | Duration | `30s` | Agent 心跳上报间隔 |
| `baafoo.agent.self-check` | boolean | true | Agent 启动时是否进行自检（验证字节码增强是否生效） |

---

## 6. 使用流程

### 6.1 开发者使用步骤

```
Step 0（可选）：运行 baafoo init 快速起步工具
  生成配置文件模板、JVM 启动参数示例和示例规则，5 分钟内完成挡板环境搭建

Step 1：启动 Baafoo Server
  java -jar baafoo-server.jar --config stub-rules.yml

Step 2：修改应用启动命令，附加 Agent，指定环境
  java -javaagent:baafoo-agent.jar=config=baafoo-agent.yml \
       -jar my-app.jar

Step 3：按需调整规则
  编辑 stub-rules.yml → 文件变化自动热加载 → 立即生效（对所有 stub 模式 Agent）

Step 4（可选）：切换环境模式（通过 Web 控制台或 API）
  将 ft-1 环境从 passthrough 切换为 stub
  → Server 通过控制通道向 ft-1 环境下所有 Agent 下发模式切换指令
  → Agent 无需重启，自动开始拦截

Step 5（可选）：录制模式
  将 ft-3 环境配置为 record 模式
  → Agent 透明代理真实连接，同时录制请求/响应
  → 录制数据上传至 Server 后可用于回放
```

### 6.2 多环境并行工作流

```
场景：团队有 3 套功能测试环境，共享同一个 Baafoo Server

环境          Agent 配置           Server 端模式       行为
──────────    ──────────────────    ──────────────    ─────────────────────────────
FT-1          environment: ft-1    stub               所有请求被挡板拦截，返回 Mock 响应
FT-2          environment: ft-2    passthrough        所有请求透传到真实下游联调
FT-3          environment: ft-3    record            连接真实下游，同时录制请求/响应
FT-4          environment: ft-4    record-and-stub   按规则拦截返回 Mock 响应，同时录制请求/响应

模式切换（无需重启 Agent）：
  Web 控制台将 FT-2 从 passthrough 切换为 stub
    → Server 通过控制通道向 FT-2 环境下所有 Agent 下发 SWITCH_MODE { mode: "stub" }
    → Agent 收到指令后，下一次请求开始按规则拦截
```

### 6.3 CI/CD 集成

- Baafoo Server 可以 Docker 镜像形式在流水线中启动
- 通过环境变量覆盖配置，支持不同流水线阶段使用不同挡板规则集
- 提供 `baafoo-maven-plugin`（规划），支持在集成测试阶段自动启停挡板服务

---

## 7. 技术约束与边界

### 7.1 支持范围

| 协议/场景 | 支持状态 | 说明 |
|---|---|---|
| HTTP/1.1 REST | ✅ 完全支持 | 含 body、header 匹配 |
| HTTPS/TLS | ✅ 支持 | 需信任挡板自签证书或配置 trustAll |
| HTTP/2 + gRPC | 🔶 部分支持 | 基础 Unary 调用，流式 RPC 待规划 |
| 原生 TCP Socket | ✅ 完全支持 | 字节级匹配与响应 |
| **Consul 服务发现** | ✅ v1.0 支持 | DNS 模式 + HTTP API 模式 |
| **Socket 直连（Consul 架构）** | ✅ 支持 | 服务发现层拦截，Socket 层自动接驳挡板 |
| Kafka（JVM 客户端）| ✅ v1.0 支持（Beta：支持 Kafka Client 2.8+，Metadata/Produce/Fetch 三个核心 API；不支持 acks=all、事务、Consumer Group Rebalance） | Producer / Consumer |
| **TDMQ for Pulsar / Apache Pulsar** | ✅ v1.0 支持（Producer/Consumer，binary protocol（6650），覆盖腾讯云 TDMQ SDK + Apache 官方 SDK；v1.0 仅覆盖最简路径：非分区 Topic + 单 Producer + 单 Consumer + Shared 订阅） | Producer / Consumer，binary protocol（6650），覆盖腾讯云 TDMQ SDK + Apache 官方 SDK |
| ActiveMQ JMS | ✅ 完全支持 | Queue / Topic |
| RocketMQ | 🔶 部分支持 | 基于 JMS 封装的场景 |
| **Eureka / Nacos / ZooKeeper** | 🔶 规划中 | 见 3.4.5 注册中心路线图 |
| WebSocket | 🔶 规划中 | 升级握手+帧处理 |
| UDP | ❌ 不支持 | JavaAgent TCP 拦截点不覆盖 UDP |
| 非 JVM 进程 | ❌ 不支持 | Agent 仅作用于挂载的 JVM 进程 |

### 7.2 Java 版本要求

- **最低 Java 8**（目标兼容版本）
- Java 9+ 需额外 JVM 参数：`--add-opens java.base/java.net=ALL-UNNAMED`
- Byte Buddy 版本需与目标 JVM 版本兼容

### 7.3 性能基准（预期目标）

| 指标 | 目标值 |
|---|---|
| Agent 连接拦截额外延迟 | < 1ms |
| Baafoo Server HTTP 响应时延（P99）| < 5ms（不含配置的 delay）|
| 规则热加载生效时间 | < 500ms |
| 单 Server 并发 Agent 连接数 | > 1000 |
| 环境模式切换生效时间 | < 1s（控制通道长轮询超时） |

---

## 8. 竞品对比

| 产品 | 协议支持 | 侵入性 | Java 版本 | 录制回放 | MQ 支持 | 多环境模式控制 |
|---|---|---|---|---|---|---|
| **Baafoo** | HTTP + TCP + MQ(Kafka/Pulsar/JMS) | 零侵入（Agent）| Java 8+ | ✅ | ✅ | ✅ 环境级模式控制 |
| WireMock | HTTP only | 零侵入（Proxy）| Java 8+ | ✅ | ❌ | ❌ |
| Hoverfly | HTTP + gRPC | 零侵入（Proxy）| 无要求 | ✅ | ❌ | ❌ |
| MockServer | HTTP + SOCKS | 零侵入（Proxy）| Java 8+ | ✅ | ❌ | ❌ |
| Mountebank | HTTP + TCP | 零侵入（Proxy）| Node.js | ✅ | ❌ | ❌ |

**Baafoo 差异化优势**：
1. 唯一通过 JavaAgent 实现进程内 TCP 拦截的方案，**无需修改 hosts、无需配置网络代理**
2. 原生支持 MQ 挡板（Kafka / Pulsar(TDMQ) / JMS），覆盖事件驱动架构
3. 全 Java 技术栈，与 Java 生态天然集成，便于嵌入 CI/CD 流水线
4. **环境级模式控制**：多套测试环境共享同一 Server，按环境维度独立控制挡板/透传/录制模式

---

## 9. 规划路线图

### v1.0（MVP）
- [ ] Baafoo Agent：Socket.connect() + NIO SocketChannel 拦截，Consul 拦截（DNS + HTTP API），Pulsar Client 拦截（serviceUrl 重写），路由规则引擎，配置热加载
- [ ] Baafoo Server：HTTP Mock Handler，Raw TCP Handler，**Pulsar Mock Broker**，规则管理 REST API
- [ ] **环境管理**：环境创建/删除/模式切换 API，Agent 注册时绑定环境，控制通道模式下发
- [ ] 基础录制/回放（HTTP 层）
- [ ] 命令行工具（启动/状态查看）
- [ ] **Web 控制台**：规则管理 + 请求日志 + **环境管理页面**
- [ ] **Kafka Mock Broker（Beta）**
- [ ] **JMS 内嵌 Broker**

### v1.5
- [ ] 规则版本管理/Undo
- [ ] 场景集管理（简化版）
- [ ] baafoo init 快速起步工具
- [ ] Kafka Beta 正式版
- [ ] Consul HTTP API 完善（Spring Cloud Consul WebClient）
- [ ] Docker 镜像发布
- [ ] Web 控制台增强（规则管理 + 请求日志 + 环境管理完善）

### v2.0
- [ ] gRPC/HTTP2 支持
- [ ] 录制/回放增强（按场景分组、断言验证）
- [ ] Maven/Gradle 插件（集成测试生命周期绑定）
- [ ] WebSocket 支持
- [ ] Eureka/Nacos 注册中心支持

---

## 10. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| Bootstrap ClassLoader 隔离 | Agent 类无法访问 `java.net` 包 | 使用 `appendToBootstrapClassLoaderSearch()` |
| JDK 内部类跨版本变更 | Byte Buddy Advice 对 `sun.nio.ch.*` 的增强可能在 JDK 小版本升级时失效 | 运行时版本检测 + 多版本 Advice 实现类适配 |
| 连接池预热时机 | 部分框架在 Agent 注册前已完成连接建立 | 文档说明 Agent 必须在应用 main() 之前完成增强 |
| TLS 证书信任 | HTTPS 场景需信任挡板自签证书 | 提供便捷的 trustAll 模式（仅开发环境） |
| 业务代码检测绕过 | 极少数框架绕过 Socket 直接使用 JNI | 影响极小，文档说明，可通过 iptables 兜底 |
| **Consul 健康检查误判** | Agent 篡改地址后 Consul 健康检查看到的 IP 与注册不一致，可能误标记服务不健康 | Agent 对 `127.0.0.1:8500` 的调用透传，不拦截健康检查请求 |
| **Consul SDK 版本差异** | 不同版本 Consul SDK（Orbitz / Ecwid / Spring Cloud）内部实现类名不同 | 拦截点优先使用通用 HTTP 层匹配 URL，不依赖具体 SDK 实现 |
| **Pulsar 协议复杂度** | Pulsar binary protocol 包含 Lookup、Partitioned Topic、ManagedLedger 等复杂机制，Mock Broker 无法完整模拟 | v1.0 聚焦 Producer/Consumer 核心路径；复杂特性（事务消息、Schema/Protobuf 原生注册、Key_Shared 订阅）在 v1.5 迭代 |
| **热加载竞态** | 规则热加载过程中，正在匹配的连接可能读到半加载状态 | 采用"版本号 + 原子引用替换"策略，规则对象整体替换为不可变快照 |
| **控制通道可靠性** | Agent-Server 控制通道断开时，Agent 无法获取最新规则和模式切换指令 | Agent 本地 WatchService 作为降级方案；控制通道断开时 Agent 使用最后已知规则继续运行；模式切换指令丢失时，Agent 定期轮询（长轮询超时后重连） |
| **环境模式不一致** | 同一环境下的多个 Agent 可能因为控制通道延迟而短暂处于不同模式 | 可接受的最终一致性；Agent 定期长轮询保证最终同步；规则匹配失败有明确错误日志便于排查 |
| **Agent 环境配置错误** | 开发者错误配置 environment 字段，导致 Agent 加入错误环境 | Web 控制台环境管理页面显示每个环境下的 Agent 列表，便于运维排查；Agent 启动时日志明确输出所属环境 |
| **Plugin Advice 类不在 Bootstrap CL**（🔴 高） | Plugin 的 installTransformers 注册的 Advice 类如果写在 Plugin jar 里，Bootstrap CL 找不到 → 目标类调用时崩溃 | Advice 类必须在 Core 中预定义；Plugin 只实现处理逻辑，不直接提供 Advice 类 |
| **Plugin 初始化失败**（🟡 中） | Pulsar/TDMQ 插件初始化失败（如依赖版本不兼容），影响同进程其他协议 | 捕获插件初始化异常，打印 WARN 日志，该协议降级为 passthrough，不影响其他协议 |
| **录制数据丢失**（🟡 中） | Agent 上传录制数据前 Server 重启，数据在 Agent 内存中丢失 | 录制数据写入磁盘后才返回成功；Agent 本地暂存录制数据，上传失败重试 |
| **Agent 环境归属混淆**（🟡 中） | 规则全局共享但 Agent 分属不同环境，开发者可能混淆"为什么我的规则不生效" | Web 控制台全局显示当前 Agent 所属环境；日志中明确输出 Agent 环境 + 模式；文档中明确说明规则全局共享机制 |

---

## 附录 A：目录结构（规划）

```
baafoo/
├── baafoo-agent/          # JavaAgent 模块
│   ├── src/main/java/
│   │   ├── BaafooAgent.java          # premain 入口
│   │   ├── transformer/              # 字节码变换器（统一 Byte Buddy）
│   │   │   ├── SocketTransformer.java
│   │   │   ├── NioChannelTransformer.java
│   │   │   ├── ConsulDnsTransformer.java
│   │   │   ├── ConsulApiTransformer.java
│   │   │   ├── KafkaClientTransformer.java
│   │   │   └── PulsarClientTransformer.java
│   │   ├── router/                   # 路由规则引擎（版本号+原子引用替换）
│   │   ├── recorder/                 # 录制器（缓冲+上传）
│   │   └── control/                  # Agent-Server 控制通道
│   │       ├── AgentRegistration.java
│   │       ├── HeartbeatWorker.java
│   │       ├── RuleSyncWorker.java
│   │       ├── ModeSyncWorker.java    # 新增：环境模式同步
│   │       └── RecordingUploader.java
│   └── pom.xml
│
├── baafoo-server/         # 挡板服务模块
│   ├── src/main/java/
│   │   ├── BaafooServer.java         # 启动入口
│   │   ├── handler/                  # 协议处理器
│   │   │   ├── HttpMockHandler.java
│   │   │   ├── TcpMockHandler.java
│   │   │   ├── KafkaMockBroker.java
│   │   │   ├── PulsarMockBroker.java
│   │   │   └── JmsMockBroker.java
│   │   ├── rule/                     # 规则引擎
│   │   ├── api/                      # 管理 REST API
│   │   ├── env/                      # 新增：环境管理模块
│   │   │   ├── EnvironmentManager.java
│   │   │   ├── Environment.java
│   │   │   └── ModeDispatcher.java    # 模式切换指令下发
│   │   ├── control/                  # Server 端控制通道处理
│   │   │   ├── AgentRegistry.java
│   │   │   ├── AgentHeartbeatTracker.java
│   │   │   └── AgentCommandDispatcher.java
│   │   ├── recording/                # 录制数据存储与清理
│   │   └── webapp/                   # Web 控制台静态资源
│   └── pom.xml
│
├── baafoo-core/           # 公共模块（规则模型、配置解析）
├── baafoo-cli/            # 命令行工具
├── config/
│   ├── baafoo-agent.yml   # Agent 配置示例（含 environment 字段，不含 mode）
│   └── stub-rules.yml     # 规则文件示例（无 environments 字段）
└── README.md
```

## 附录 B：关键技术依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| Byte Buddy | 1.14.x | Agent 字节码增强（统一框架，含 JDK 内部类 Advice 内联） |
| Netty | 4.1.x | Server 网络层 |
| SnakeYAML | 1.33 | 配置文件解析（Java 8 兼容）|
| Jackson | 2.15.x | JSON 处理 |
| Apache Kafka Client | 3.x | Kafka 协议参考 |
| Apache Pulsar Client | 2.10.x | Pulsar 协议参考（兼容 TDMQ） |
| ActiveMQ Artemis Core | 2.x | JMS 内嵌 Broker |
| SLF4J + Logback | 1.7.x | 日志（Java 8 兼容）|

---

*本文档为概念设计 v0.7，详细技术规格与 UI 设计将在产品说明书阶段进一步细化。*