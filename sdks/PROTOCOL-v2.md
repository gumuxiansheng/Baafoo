# Baafoo Protocol v2 规范

> 版本: 2.0
> 日期: 2026-06-20
> 状态: 稳定

---

## 1. 概述

Baafoo Protocol v2 是 Agent/SDK 与 Baafoo Server 之间的通信规范。基于 HTTP/1.1 + JSON，完全语言无关。

### 1.1 设计约束

| 约束 | 理由 |
|------|------|
| 仅使用 HTTP/1.1 + JSON | 兼容性最大化，每种语言都有 HTTP client |
| 无二进制编码 | Thin SDK 可以用任何 HTTP 库实现 |
| Long-poll 而非 WebSocket | 避免 WebSocket 库的额外依赖 |
| 批量上传录制 | 减少网络开销，SDK 本地缓冲后批量上传 |
| AgentId 持久化 | SDK 启动时生成本地 UUID，重启后复用 |

### 1.2 Base URL

```
http://{baafoo-server}:{port}/__baafoo__/api
```

默认端口 `8084`（API + Web 控制台共用）。

### 1.3 认证

支持三种认证方式，按优先级：

| 方式 | Header | 说明 |
|------|--------|------|
| JWT Token | `Authorization: Bearer <token>` | 通过 `/auth/login` 获取 |
| API Key | `X-Api-Key: <key>` | 在 Server 配置中静态映射角色 |
| 本地绕过 | 无 | 127.0.0.1 请求自动获得 admin 角色 |

当 `auth.enabled=false`（默认）时，所有请求自动获得 admin 角色，无需认证。

### 1.4 统一响应格式

所有 API 响应使用统一的 `ApiResponse` 包装：

```json
{
  "success": true,
  "code": 200,
  "message": "OK",
  "data": { ... },
  "timestamp": 1718889600000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | boolean | 是否成功 |
| `code` | int | HTTP 状态码 |
| `message` | string | 描述信息 |
| `data` | object/null | 业务数据 |
| `timestamp` | long | 服务器时间戳（毫秒） |

---

## 2. API 端点

### 2.1 注册 Agent

```
POST /agent/register
```

**请求体**：
```json
{
  "agentId": "my-app-001",
  "environment": "default",
  "hostname": "app-host-01",
  "version": "1.0.0",
  "protocols": ["http", "kafka"]
}
```

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `agentId` | string | 否 | hostname | Agent 唯一标识，缺省时用 hostname |
| `environment` | string | 否 | `"default"` | 环境名称 |
| `hostname` | string | 否 | `"unknown"` | 主机名 |
| `version` | string | 否 | `"1.0.0"` | Agent/SDK 版本 |
| `protocols` | string[] | 否 | `[]` | 支持的协议列表 |

**响应**：
```json
{
  "success": true,
  "code": 200,
  "message": "OK",
  "data": {
    "agentId": "my-app-001",
    "mode": "record-and-stub",
    "pollIntervalSec": 10
  },
  "timestamp": 1718889600000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `agentId` | string | 服务器确认的 Agent ID |
| `mode` | string | 环境模式：`stub`/`record`/`record-and-stub`/`passthrough` |
| `pollIntervalSec` | int | 建议的轮询间隔（秒） |

**说明**：服务器使用观察到的源 IP 覆盖 agent 自报的 IP，以应对 Docker NAT 场景。

---

### 2.2 心跳

```
POST /agent/heartbeat
```

**请求体**：
```json
{
  "agentId": "my-app-001",
  "timestamp": 1718889600000,
  "pluginStatuses": {}
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agentId` | string | 是 | Agent ID |
| `timestamp` | long | 否 | 心跳时间戳（毫秒） |
| `pluginStatuses` | object | 否 | 插件健康状态映射 |

**响应**：
```json
{
  "success": true,
  "code": 200,
  "message": "OK",
  "data": null,
  "timestamp": 1718889600000
}
```

---

### 2.3 拉取规则

```
GET /agent/poll?agentId={agentId}&environment={environment}
```

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agentId` | string | 是 | Agent ID |
| `environment` | string | 否 | 环境名称（agentId 匹配失败时回退） |

**响应**：
```json
{
  "success": true,
  "code": 200,
  "message": "OK",
  "data": {
    "rules": [ { ... Rule 对象 ... } ],
    "mode": "record-and-stub",
    "version": 1718889600000
  },
  "timestamp": 1718889600000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `rules` | Rule[] | 当前环境生效的规则列表 |
| `mode` | string | 环境模式 |
| `version` | long | 规则版本号（当前为时间戳） |

**说明**：
- 服务器先按 `agentId` 查找已注册 agent 的环境，匹配失败则回退到 `environment` 查询参数
- 仅返回属于该 agent 环境的规则（环境隔离）
- `readTimeout` 建议设为 `pollIntervalSec * 1000 + 1000`，支持长轮询
- HTTP 204 表示无变化

---

### 2.4 上传录制数据

```
POST /agent/recordings?agentId={agentId}&environment={environment}
```

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agentId` | string | 是 | Agent ID |
| `environment` | string | 否 | 环境名称 |

**请求体**：`RecordingEntry` 数组

```json
[
  {
    "id": "rec-001",
    "ruleId": "rule-uuid-001",
    "protocol": "http",
    "host": "order-service",
    "port": 8080,
    "method": "GET",
    "path": "/api/orders/123",
    "requestHeaders": { "Content-Type": "application/json" },
    "requestBody": "",
    "responseStatusCode": 200,
    "responseHeaders": { "Content-Type": "application/json" },
    "responseBody": "{\"id\":123,\"status\":\"shipped\"}",
    "responseTimeMs": 42,
    "recordedAt": 1718889600000,
    "direction": "request",
    "sessionId": "sess-001"
  }
]
```

**响应**：
```json
{
  "success": true,
  "code": 200,
  "message": "Recorded 1",
  "data": null,
  "timestamp": 1718889600000
}
```

**说明**：
- 服务器自动填充缺失的 `agentId`、`agentIp`、`environmentId`
- 建议按 50 条/批分批上传，避免请求体过大
- 需要至少 `tester` 角色权限

---

### 2.5 列出所有 Agent

```
GET /agents
```

**响应**：
```json
{
  "success": true,
  "code": 200,
  "message": "OK",
  "data": [
    {
      "agentId": "my-app-001",
      "environment": "default",
      "hostname": "app-host-01",
      "version": "1.0.0",
      "protocols": ["http"],
      "agentIp": "192.168.1.100",
      "registeredAt": 1718889600000,
      "lastHeartbeat": 1718889660000,
      "pluginStatuses": {}
    }
  ],
  "timestamp": 1718889600000
}
```

---

## 3. 数据模型

### 3.1 RecordingEntry

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 唯一录制 ID |
| `ruleId` | string | 触发录制的规则 ID |
| `environmentId` | string | 环境 ID |
| `agentId` | string | 录制此数据的 Agent ID |
| `agentIp` | string | Agent 服务器 IP |
| `protocol` | string | 协议（http/tcp/kafka/pulsar/jms 等） |
| `host` | string | 目标主机 |
| `port` | int | 目标端口 |
| `serviceName` | string | 服务名（Consul） |
| `method` | string | HTTP 请求方法 |
| `path` | string | HTTP 请求路径 |
| `requestHeaders` | map\<string, string\> | 请求头 |
| `requestBody` | string | 请求体 |
| `responseStatusCode` | int | 响应状态码 |
| `responseHeaders` | map\<string, string\> | 响应头 |
| `responseBody` | string | 响应体 |
| `responseTimeMs` | long | 响应时间（毫秒） |
| `recordedAt` | long | 录制时间戳 |
| `tags` | map\<string, string\> | 标签 |
| `direction` | string | 方向：TCP 用 `request`/`response`；MQ 用 `produce`/`consume` |
| `sessionId` | string | 会话 ID |
| `dataHex` | string | 原始数据十六进制（TCP 字节录制） |
| `durationMs` | long | 持续时间（毫秒） |
| `unmatched` | boolean | 是否无匹配规则的录制 |

### 3.2 Rule

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | string | 唯一规则 ID |
| `name` | string | 规则名 |
| `protocol` | string | 协议 |
| `serviceName` | string | 目标服务名 |
| `host` | string | 目标主机 |
| `port` | int | 目标端口 |
| `conditions` | MatchCondition[] | 匹配条件 |
| `responses` | ResponseEntry[] | 响应配置 |
| `enabled` | boolean | 是否启用 |
| `priority` | int | 优先级（数值越低越高） |
| `tags` | string[] | 规则标签 |
| `environments` | string[] | 生效环境列表 |
| `tcpRounds` | TcpRound[] | TCP 多轮交互 |
| `tcpLoop` | boolean | TCP 是否循环 |
| `fakerSeed` | long | Faker 种子 |
| `faultInjection` | FaultInjection | 故障注入配置 |
| `version` | int | 规则版本 |
| `createdAt` | long | 创建时间戳 |
| `updatedAt` | long | 更新时间戳 |

### 3.3 MatchCondition

| 字段 | 类型 | 说明 |
|------|------|------|
| `field` | string | 匹配字段（method/path/header.xxx/body.xxx 等） |
| `operator` | string | 操作符（equals/contains/regex/exists 等） |
| `value` | string | 匹配值 |

### 3.4 ResponseEntry

| 字段 | 类型 | 说明 |
|------|------|------|
| `statusCode` | int | HTTP 状态码 |
| `headers` | map\<string, string\> | 响应头 |
| `body` | string | 响应体 |
| `delayMs` | int | 延迟（毫秒） |

---

## 4. 环境模式

| 模式 | 说明 |
|------|------|
| `stub` | 仅挡板：匹配规则返回 mock，不匹配返回 404/passthrough |
| `record` | 仅录制：所有请求透传到真实后端，同时录制 |
| `record-and-stub` | 录制+挡板：匹配规则返回 mock，不匹配透传并录制 |
| `passthrough` | 透传：所有请求直接转发到真实后端 |

---

## 5. SDK 实现指南

### 5.1 最小实现

Thin SDK 最少需要实现以下 4 个方法：

1. `register()` — 注册 agent，获取 agentId 和 mode
2. `heartbeat()` — 定期心跳，维持在线状态
3. `pollRules()` — 拉取规则，SDK 本地缓存
4. `reportRecordings()` — 上报录制数据

### 5.2 AgentId 持久化

SDK 应在本地持久化 agentId（如写入 `~/.baafoo/agent_id` 文件），重启后复用，以便 Server 识别同一应用。

### 5.3 调度模型

```
启动 → register() → 成功后启动两个定时任务:
  ├─ heartbeat: 每 heartbeatIntervalSec 秒
  └─ poll:      每 pollIntervalSec 秒（支持长轮询）
```

### 5.4 录制缓冲

建议本地缓冲录制数据，按以下策略批量上传：
- 满 50 条触发上传
- 或每 5 秒定时上传
- 或 SDK 关闭时 flush

### 5.5 错误处理

- `register()` 失败时按指数退避重试
- `heartbeat()` 失败时静默，下次重试
- `pollRules()` 超时是预期行为（长轮询），静默处理
- `reportRecordings()` 失败时保留数据，下次重试
