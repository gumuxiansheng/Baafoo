# Baafoo 多语言 SDK 扩展可行性分析与技术方案

> 分析日期: 2026-06-20
> 状态: 探索阶段

---

## 1. 现状分析

### 1.1 Java Agent 架构回顾

```
┌─────────────────────────────────────────────────┐
│                    Baafoo Server                 │
│  ┌─────────────────────────────────────────┐    │
│  │  REST API: /__baafoo__/api/agent/...     │    │
│  │    POST /register   → 注册 Agent         │    │
│  │    POST /heartbeat   → 心跳              │    │
│  │    GET  /poll        → 长轮询(规则/模式)  │    │
│  │    POST /recordings  → 上传录制数据       │    │
│  └──────────────┬──────────────────────────┘    │
└─────────────────┼───────────────────────────────┘
                  │ HTTP (JSON)
┌─────────────────┼───────────────────────────────┐
│  Java Agent     │                               │
│  ┌──────────────▼──────────────────────────┐    │
│  │  ControlChannel (HTTP long-polling)      │    │
│  └──────────────┬──────────────────────────┘    │
│  ┌──────────────▼──────────────────────────┐    │
│  │  ByteBuddy Advice (字节码拦截)           │    │
│  │  ├─ Socket.connect()          → 重定向   │    │
│  │  ├─ Socket.getInputStream()   → 录制/注入 │    │
│  │  ├─ Socket.getOutputStream()  → 录制     │    │
│  │  ├─ NioSocketChannel.*        → 重定向   │    │
│  │  ├─ Kafka Producer/Consumer   → 拦截     │    │
│  │  ├─ Pulsar Client             → 拦截     │    │
│  │  ├─ JMS ConnectionFactory     → 拦截     │    │
│  │  ├─ DNS resolution            → 重定向   │    │
│  │  └─ Consul HTTP               → 重定向   │    │
│  └─────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

**核心事实**：Agent ↔ Server 通信是纯 HTTP REST + JSON，不绑定任何 Java 特性。

### 1.2 Agent 核心能力抽象

| 能力 | 说明 | 语言依赖度 |
|------|------|-----------|
| **连接重定向** | TCP 连接到目标下游服务时，改为连 Baafoo Server | **高**（平台网络 API） |
| **流量录制** | 拦截 socket I/O 流，记录请求/响应 | **高**（平台 I/O API） |
| **响应注入** | 匹配挡板规则后，不发出真实请求，直接返回 mock 数据 | **高**（平台 I/O API） |
| **规则同步** | HTTP long-polling 从 Server 获取规则/mode | **低**（HTTP + JSON） |
| **录制上传** | HTTP POST 将录制数据发回 Server | **低**（HTTP + JSON） |
| **域名劫持** | DNS 查询时返回 Baafoo Server 地址 | **中**（平台 DNS API） |
| **MQ 协议拦截** | Kafka/Pulsar/JMS 客户端拦截 | **高**（协议库 API） |

---

## 2. 三个方案

### 方案 A：语言特化 SDK（逐语言开发）

```
每个语言独立实现：网络拦截 + Baafoo Protocol 客户端

Go SDK:
  import "github.com/baafoo/sdk-go"
  bf := baafoo.New(baafoo.Config{ServerURL: "http://localhost:8084"})
  bf.HTTP() // 自动拦截 net/http

Python SDK:
  from baafoo import BaafooSDK
  sdk = BaafooSDK(server_url="http://localhost:8084")
  sdk.patch()  # monkey-patch socket/requests/urllib3

Node.js SDK:
  import { Baafoo } from '@baafoo/sdk';
  const bf = new Baafoo({ serverURL: 'http://localhost:8084' });
  bf.intercept(); // 替换 http/https module
```

| 语言 | 拦截机制 | 可行性 | 人天 |
|------|---------|--------|------|
| **Go** | `net.Dialer.Control` + 自定义 `http.RoundTripper` | ⭐⭐⭐ | 5-7 |
| **Python** | `gevent.monkey_patch` 或 `socket.socket` 替换 | ⭐⭐⭐ | 4-6 |
| **Node.js** | `require.cache` 替换 `http`/`https`/`net` module 或 undici 拦截 | ⭐⭐⭐ | 4-6 |
| **Rust** | 条件编译 `cfg` + `hyper`/`reqwest` 自定义 connector | ⭐⭐ | 6-8 |
| **C#/.NET** | `HttpClientHandler` 替换或 `System.Net.Sockets` hook | ⭐⭐ | 5-7 |
| **C++** | `LD_PRELOAD` 劫持 `connect()`/`send()`/`recv()` | ⭐ | 8-10 |

**优势**：
- 开发者体验好：`import` + 一行 `intercept()` 就接入
- 可以针对每个语言的包管理生态定制安装方式（go get / pip install / npm install）
- 网络透明，不需要改端口/地址

**劣势**：
- 每个语言需要单独维护，人力成本随语言数量线性增长
- 不同语言的拦截机制不同，功能覆盖度会有差异（例如 C++ 的 LD_PRELOAD 方案无法拦截 Kubernetes DNS）
- 版本兼容性噩梦——Python 3.8 vs 3.12 的 socket 内部实现可能不同

### 方案 B：Sidecar Proxy（零语言差异）

```
┌──────────────────────────────────────────────────────┐
│  应用进程 (任意语言)                                   │
│  ┌──────────┐  http://localhost:8080                  │
│  │  代码    │────▶ 下游 API                           │
│  └──────────┘                                       │
│       │  实际网络调用                                  │
│       ▼                                              │
│  ┌──────────────┐                                    │
│  │  Baafoo Proxy│  ← 本地 sidecar 进程（127.0.0.1）   │
│  │  Port 端口映射 │                                    │
│  │  :15001→:8080│                                    │
│  │  :15002→:9092│                                    │
│  └──────┬───────┘                                    │
└─────────┼────────────────────────────────────────────┘
          │ gRPC / HTTP / TCP
┌─────────▼────────────────────────────────────────────┐
│              Baafoo Server                            │
│   规则匹配 / 录制 / 注入 / 故障模拟                     │
└──────────────────────────────────────────────────────┘
```

实现方式：Baafoo Proxy 是一个独立进程（Go 或 Rust 编译的二进制），部署在每个开发机或容器 sidecar 中。

**协议映射配置**：
```yaml
# baafoo-proxy.yml
proxy:
  mappings:
    - listen: "127.0.0.1:15001"
      target: "order-service:8080"
      protocol: http
    - listen: "127.0.0.1:15002"
      target: "kafka-broker:9092"
      protocol: kafka
    - listen: "127.0.0.1:15003"
      target: "grpc-service:50051"
      protocol: grpc
server:
  url: "http://baafoo-server:8084"
```

**优势**：
- **零语言绑定**：任何语言、任何框架，只要改一下连接地址就行
- **统一实现**：一套代码覆盖所有场景，没有重复劳动
- **协议感知**：Proxy 可以做协议级别的规则匹配和录制（和 Baafoo Server 配合）
- **容器友好**：作为 Sidecar 部署，和 Service Mesh 模式完全一致
- **解耦应用**：应用代码无依赖，不需要引入任何 Baafoo SDK

**劣势**：
- **需要改连接地址**：应用必须把下游地址改为 `localhost:端口映射`（而 Java Agent 零改动）
- **端口冲突风险**：如果开发者本地跑多个服务，端口映射管理复杂
- **对直接 IP 调用的覆盖不完整**：应用直接用 IP:Port 调用时，需要显式配置映射
- **DNS 劫持复杂**：应用用域名调用时，需要改 hosts 或配置 DNS

### 方案 C：混合方案（SDK + Sidecar 可选）

```
高侵入性 ←──────────────────────────→ 低侵入性
   SDK              Thin SDK          Sidecar
   │                  │                  │
   ▼                  ▼                  ▼
┌──────┐         ┌──────────┐      ┌─────────┐
│ Go   │         │ Baafoo   │      │ Baafoo  │
│ SDK  │         │ Shim     │      │ Proxy   │
│(全拦截)│        │(轻量上报) │      │(透明代理) │
└──┬───┘         └────┬─────┘      └────┬────┘
   │                  │                  │
   └──────────────────┼──────────────────┘
                      ▼
              Baafoo Protocol
            (JSON over HTTP)
                      │
                      ▼
              Baafoo Server
```

| 层级 | 说明 | 适用场景 |
|------|------|---------|
| **Full SDK** | 完整拦截：网络 I/O 重定向 + 录制 + 注入 | 核心语言（Go/Python/Node.js），需要零配置体验 |
| **Thin SDK** | 仅上报：跟踪出站请求元数据到 Server，不拦截 I/O | 所有语言，用于录制模式（靠真实调用 + 录制） |
| **Sidecar Proxy** | 透明代理：在 TCP/HTTP 层拦截 | 任何语言，不需要改代码但需要改地址 |

---

## 3. 推荐方案：优先级分层

### 3.1 架构总览

```
Phase 1: Thin SDK（3 人天）→ 所有语言的基线
Phase 2: Sidecar Proxy（8-10 人天）→ 语言无关的完整拦截
Phase 3: Full SDK for Go（5-7 人天）→ 核心语言零配置体验
Phase 4: Full SDK for Python / Node.js（各 4-6 人天）→ 扩展生态
```

### 3.2 Phase 1：Universal Thin SDK（立即可做）

**目标**：让任何语言的程序都能**上报出站请求**到 Baafoo Server。不做拦截，只做录制。

**核心**：这是最低投入、最高覆盖的方案。Thin SDK 只是一个 HTTP client wrapper，不需要拦截任何东西。

```
// 所有语言共享同一个概念
type BaafooSDK struct {
    serverURL string
    client    *http.Client
}

func (b *BaafooSDK) Register(appName string, protocols []string) error { ... }
func (b *BaafooSDK) ReportRecording(entry RecordingEntry) error { ... }
func (b *BaafooSDK) PollRules() ([]Rule, error) { ... }
```

**Thin SDK 提供的能力**：

| 能力 | 实现 |
|------|------|
| 注册 | POST /register |
| 心跳 | POST /heartbeat |
| 上报录制 | POST /recordings — 用户在 HTTP client 层传入响应 |
| 获取规则 | GET /poll → 规则列表 |
| 模式同步 | GET /poll → stub/record/passthrough |

Thin SDK 不做网络拦截——用户自己选择是否改用 SDK 的 HTTP client 来获取录制能力。

**每个语言 Thin SDK 只需 ~400 行代码**：

```go
// Go Thin SDK 示例
package baafoo

type Client struct {
    opts   Options
    rules  atomic.Value // []Rule
    mode   atomic.Value // Mode
}

func New(opts Options) *Client { ... }
func (c *Client) Register() error { ... }
func (c *Client) Start(ctx context.Context) { ... } // heartbeat + poll loop
func (c *Client) ReportRecording(entries []RecordingEntry) error { ... }
func (c *Client) GetRules() []Rule { ... }
func (c *Client) MatchRequest(method, path string) *Rule { ... }
```

### 3.3 Phase 2：Sidecar Proxy（核心投入）

**目标**：语言无关的透明代理，TCP 层拦截 → Baafoo Server。

**技术选型**：用 Go 实现（单 binary、跨平台、goroutine 并发处理连接）。

**架构**：

```
baafoo-proxy (Go binary)
├── listener manager (TCP/HTTP 多端口监听)
├── protocol detector (HTTP/Kafka/gRPC/... 协议嗅探)
├── rule engine client (HTTP long-poll Baafoo Server)
├── recording buffer (本地缓冲 + 批量上传)
├── traffic duplicator (录制模式：复制流量到真实后端)
└── dns interceptor (可选：DNS 劫持)
```

**协议映射**：Proxy 根据监听端口规则，判断是否走 mock 还是 passthrough。

```
客户端                     Baafoo Proxy          Baafoo Server
  │                           │                      │
  │ connect(localhost:15001)  │                      │
  │─────────────────────────▶│                      │
  │                           │ POST /match          │
  │                           │─────────────────────▶│
  │                           │ {host, port, method}  │
  │                           │◀─────────────────────│
  │                           │ {stub: true/false,    │
  │                           │  body, status, delay} │
  │                           │                      │
  │  if stub → mock response  │                      │
  │◀─────────────────────────│                      │
  │  if not → passthrough     │                      │
  │                           │ connect(real-host)    │
  │                           │──────────────────────▶ upstream
```

**Proxy 部署方式**：

```bash
# 本地开发
baafoo-proxy --config proxy.yml

# Docker Compose 中作为 sidecar
services:
  my-app:
    image: my-app:latest
    network_mode: "service:baafoo-proxy"
  baafoo-proxy:
    image: baafoo/proxy:latest
    volumes:
      - ./proxy.yml:/etc/baafoo/proxy.yml
```

### 3.4 Phase 3+：语言 Full SDK

当 Thin SDK + Proxy 覆盖了大部分场景后，针对高频语言做深度集成。

**Go Full SDK**（最高 ROI 的第二语言）：

```go
// 一行接入，自动拦截 net/http
import _ "github.com/baafoo/sdk-go/http"

// 或显式配置
sdk := baafoo.New(baafoo.Options{
    ServerURL: "http://localhost:8084",
    Mode:      baafoo.ModeStub,
})
defer sdk.Close()

// 之后所有 http.Get / http.Post 都自动经过 Baafoo
resp, _ := http.Get("http://order-service:8080/api/orders")
// 如果规则匹配，返回 mock 数据；否则 pass through
```

拦截机制：
- `http.DefaultTransport` 替换为 Baafoo RoundTripper
- gRPC：`grpc.WithContextDialer` + 自定义 resolver
- Kafka：`sarama` 自定义 `ConsumerGroup`/`Producer`

**Python Full SDK**：

```python
import baafoo
baafoo.patch()  # monkey-patch socket / urllib3 / requests

import requests
r = requests.get("http://order-service:8080/api/orders")  # 自动拦截
```

拦截机制：
- `gevent.monkey_patch_socket()` 或直接替换 `socket.create_connection`
- `urllib3.HTTPConnectionPool._new_conn` monkey-patch
- kafka-python / pulsar-client 等库需要单独适配

**Node.js Full SDK**：

```javascript
import '@baafoo/sdk/register'; // 最顶层引入

// 之后所有 http.request / fetch / axios 都自动拦截
const response = await fetch('http://order-service:8080/api/orders');
```

拦截机制：
- 通过 Node.js `--require` 预加载脚本替换 `http`/`https` module
- 或通过 undici 的 `Dispatcher` 拦截
- gRPC：`@grpc/grpc-js` 自定义 Channel

---

## 4. 通信协议标准化

### 4.1 Baafoo Protocol v2

当前 Java Agent 使用的协议可以抽取为与语言无关的规范：

```
Base URL: http://{baafoo-server}:{port}/__baafoo__/api

1. POST /agent/register
   Request:  { "agentId": "...", "appName": "...", "protocols": ["http", "kafka"],
               "language": "go", "sdkVersion": "1.0.0", "hostname": "..." }
   Response: { "code": 0, "data": { "agentId": "xxx", "mode": "stub", "rules": [...] } }

2. POST /agent/heartbeat
   Request:  { "agentId": "...", "timestamp": ..., "stats": { "connections": 42 } }
   Response: { "code": 0 }

3. GET /agent/poll?agentId=xxx&since=1234567890
   Response: { "code": 0, "data": { "mode": "stub", "rules": [...],
               "updates": [{ "type": "rule_added", "rule": {...} }] } }
   Timeout: 30s long-poll

4. POST /agent/recordings
   Request:  { "agentId": "...", "entries": [{ "host": "...", "port": 8080,
               "protocol": "http", "request": {...}, "response": {...}, "timestamp": ... }] }
   Response: { "code": 0 }
```

这个协议完全语言无关，用 curl 都能调用。

### 4.2 关键设计约束

| 约束 | 理由 |
|------|------|
| 仅使用 HTTP/1.1 + JSON | 兼容性最大化，每种语言都有 HTTP client |
| 无二进制编码 | Thin SDK 可以用任何 HTTP 库实现 |
| Long-poll 而非 WebSocket | 避免 WebSocket 库的额外依赖和复杂连接管理 |
| 批量上传录制 | 减少网络开销，Thin SDK 本地缓冲 50ms 再上传 |
| AgentId 持久化 | SDK 启动时生成本地 UUID，重启后复用，Server 据此识别同一应用 |

---

## 5. 录制模式差异

Java Agent 的录制是全自动的——拦截 Socket I/O 流的同时记录请求和响应。非 Agent SDK 不可能做到同样程度的透明录制。

SDK 的录制需要分两层：

```
Layer 1: 自动录制（Thin SDK）
  - 用户使用 SDK 提供的 HTTP client 时自动录制
  - 覆盖：http.Get / requests.get / fetch

Layer 2: 手动录制（所有语言）
  - 用户调用 SDK 的 record() API 传入请求/响应对
  - 覆盖：SDK client 覆盖不了的场景（自定义协议、老代码库）

Layer 3: Proxy 录制（Sidecar）
  - 透明代理在 TCP 层录制字节流
  - 覆盖：所有协议，但需要 Proxy 知道协议格式才能正确解析字段
```

---

## 6. 总体投入估算

| 阶段 | 内容 | 语言 | 人天 |
|------|------|------|------|
| **Phase 1** | Protocol v2 规范 + Go Thin SDK + 文档 | Go | 5 |
| **Phase 1** | Python Thin SDK | Python | 2 |
| **Phase 1** | Node.js Thin SDK | Node.js | 2 |
| **Phase 2** | Sidecar Proxy (Go binary) | Go | 10 |
| **Phase 2** | Proxy Docker/K8s 部署方案 | — | 2 |
| **Phase 3** | Go Full SDK（HTTP + gRPC 拦截） | Go | 7 |
| **Phase 3** | Python Full SDK（socket 劫持） | Python | 6 |
| **Phase 3** | Node.js Full SDK（module 劫持） | Node.js | 6 |
| | | **合计** | **40** |

### 推荐启动顺序

```
现在开工 → Phase 1
  ├─ 5 天：Go Thin SDK + Protocol v2 规范
  ├─ 2 天：Python Thin SDK
  └─ 2 天：Node.js Thin SDK
  
Phase 1 完成后再 → Phase 2  
  └─ 12 天：Sidecar Proxy
  
Phase 2 完成后择机 → Phase 3
  └─ 按需求选择语言（Go 优先，Python 其次，Node.js 最后）
```

---

## 7. 风险与约束

| 风险 | 说明 | 缓解 |
|------|------|------|
| **SDK 覆盖率不一致** | 不同语言的功能覆盖度不同，用户感知到差异 | 在文档中明确标注每个 SDK 的覆盖矩阵 |
| **版本兼容** | Go 1.21 → 1.24 API 变化，Python 3.8 → 3.13 内部实现变化 | CI 矩阵测试，Thin SDK 不受影响 |
| **Proxy 性能** | 所有流量经过 sidecar，增加延迟 | Go 实现，预估 +0.5ms per connection |
| **SDK 与 Proxy 冲突** | 用户同时装了 SDK 和 Proxy，请求被双重拦截 | SDK 检测 Proxy 端口后自动 bypass 或报错 |
| **生态碎片化** | 多语言 SDK + Proxy + Thin SDK，维护负担重 | 需要成立 SDK 维护小组或社区维护 |

---

## 8. 关键决策建议

1. **不要试图在每个语言复刻 Java Agent**。ByteBuddy 级别的字节码拦截在其他语言中没有等价物。SDK 的定位应该从"透明拦截"调整为"低摩擦集成"。

2. **Thin SDK 比 Full SDK 优先**。Thin SDK 覆盖所有用户（任何语言都能用），Full SDK 只覆盖核心语言（Go/Python/Node.js）。先让所有语言能用，再让核心语言好用。

3. **Sidecar Proxy 是最重要的中期投资**。它是唯一真正做到"零语言差异"的方案，也是唯二能覆盖 C++/Rust/C# 的方案（另一个是 Thin SDK）。

4. **Protocol v2 需要保持向后兼容**。Java Agent 是零号用户，新的 Protocol v2 规范需要兼容现有的 register/heartbeat/poll/recordings 端点。
