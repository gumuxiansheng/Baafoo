<div align="center">

# 🛡️ Baafoo

**JavaAgent-Based API Mock Platform**  
零侵入的微服务挡板系统

[![Java](https://img.shields.io/badge/Java-8%2B-orange?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.4-4FC08D?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-Private-red.svg)]()

[English](#) · [快速开始](#快速开始) · [使用指南](#使用指南) · [API 参考](#rest-api-参考) · [插件开发](#插件开发)

</div>

---

Baafoo 通过 JavaAgent 字节码增强技术，在不修改任何业务代码的前提下，拦截应用对下游服务的网络调用，按规则返回 Mock 响应。支持 HTTP、TCP、Kafka、Pulsar、JMS 等多种协议，以及 Consul 服务发现架构。

## ✨ 核心特性

| 特性 | 说明 |
|:-----|:-----|
| 🚀 **零侵入** | 仅需在 JVM 启动参数中增加 `-javaagent`，业务代码无需任何修改 |
| 🌐 **多协议覆盖** | HTTP/REST、原生 TCP Socket、Kafka（Beta）、Pulsar（Beta）、JMS（Beta） |
| 🔍 **Consul 适配** | 支持 DNS 模式和 HTTP API 模式的服务发现拦截 |
| 🏗️ **多环境管理** | 多套测试环境共享同一 Server，按环境维度独立控制挡板/透传/录制模式 |
| 🎯 **参数化规则** | 同一接口按不同请求参数（Header / Query / Body）返回不同 Mock 响应 |
| 🎬 **录制回放** | 透传模式下自动录制真实下游响应，后续可回放 |
| 🔥 **热切换** | 环境模式切换无需重启应用，通过控制通道实时下发 |
| 🖥️ **Web 控制台** | 可视化规则管理、请求日志、环境管理、场景集管理 |
| 📦 **场景集管理** | 将一组规则组织为场景集，一键启用/禁用 |
| 📜 **规则版本管理** | 规则修改自动快照，支持一键撤销 |
| ⚡ **快速起步** | `baafoo init` 命令生成完整项目骨架 |

---

## 📑 目录

- [系统要求](#系统要求)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [配置说明](#配置说明)
- [使用指南](#使用指南)
  - [环境管理](#环境管理)
  - [多环境并行](#多环境并行)
  - [规则管理](#规则管理)
  - [参数化规则](#参数化规则)
  - [Consul 服务发现](#consul-服务发现)
  - [场景集管理](#场景集管理)
- [REST API 参考](#rest-api-参考)
- [Web 控制台开发](#web-控制台开发)
- [插件开发](#插件开发)
- [技术栈](#技术栈)
- [协议支持状态](#协议支持状态)
- [常见问题](#常见问题)
- [许可证](#许可证)

---

## 系统要求

| 项目 | 要求 |
|:----:|:----:|
| Java | 8+（推荐 8 / 11 / 17） |
| Maven | 3.6+ |
| Node.js | 16+（仅 Web 控制台开发时需要） |
| 操作系统 | Windows / macOS / Linux |

> ⚠️ **Java 9+ 注意**：需要额外 JVM 参数 `--add-opens java.base/java.net=ALL-UNNAMED`

---

## 快速开始

### 1️⃣ 构建项目

```bash
git clone <repo-url> baafoo
cd baafoo
mvn clean package -DskipTests
```

构建产物：

| 文件 | 路径 | 说明 |
|:-----|:-----|:-----|
| `baafoo-agent.jar` | `baafoo-agent/target/` | Agent JAR（含 shade 依赖） |
| `baafoo-server.jar` | `baafoo-server/target/` | Server JAR（含 shade 依赖） |
| `baafoo-cli.jar` | `baafoo-cli/target/` | CLI 工具 JAR |

### 2️⃣ 初始化项目（推荐）

```bash
java -jar baafoo-cli/target/baafoo-cli.jar init my-project
cd my-project
```

`baafoo init` 会生成以下文件：

```
my-project/
├── baafoo-agent.yml      # Agent 配置模板
├── baafoo-server.yml     # Server 配置模板
├── baafoo-rules.yml      # 示例规则
├── start-agent.sh        # Agent 启动脚本 (Linux/macOS)
├── start-agent.bat       # Agent 启动脚本 (Windows)
├── start-server.sh       # Server 启动脚本 (Linux/macOS)
└── start-server.bat      # Server 启动脚本 (Windows)
```

### 3️⃣ 启动 Server

```bash
# Linux / macOS
java -jar baafoo-server.jar ./baafoo-server.yml

# Windows
java -jar baafoo-server.jar .\baafoo-server.yml
```

Server 启动后会监听以下端口：

| 端口 | 协议 | 说明 |
|:----:|:----:|:-----|
| 8084 | 管理 API + Web 控制台 | API 前缀 `/__baafoo__/api` |
| 9000 | HTTP Mock | 接收被重定向的 HTTP 请求 |
| 9001 | TCP Mock | 接收被重定向的 TCP 连接 |
| 9002 | Kafka Mock (Beta) | 模拟 Kafka Broker |
| 9003 | Pulsar Mock (Beta) | 模拟 Pulsar Broker |
| 9004 | JMS Mock (Beta) | 模拟 JMS Broker |

### 4️⃣ 启动应用（附加 Agent）

```bash
java -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar
```

Agent 会自动向 Server 注册、拉取规则，并根据环境模式决定是否拦截连接。

### 5️⃣ 打开 Web 控制台

浏览器访问：**http://localhost:8084/__baafoo__/**

---

## 项目结构

```
baafoo/
├── baafoo-core/           # 核心模型、配置解析、规则匹配引擎
│   └── src/main/java/com/baafoo/core/
│       ├── model/         # Rule, MatchCondition, ResponseEntry, Environment, SceneSet 等
│       ├── config/        # AgentConfig, ServerConfig, ConfigLoader
│       ├── api/           # ApiResponse 统一响应格式
│       └── util/          # MatchEngine 匹配引擎, IdGenerator
│
├── baafoo-plugin-api/     # Agent Plugin SPI 接口
│   └── src/main/java/com/baafoo/plugin/
│       ├── AgentPlugin    # 插件生命周期接口
│       ├── PluginContext  # 拦截上下文
│       └── InterceptResult / InterceptTarget
│
├── baafoo-agent/          # JavaAgent 字节码增强模块
│   └── src/main/java/com/baafoo/agent/
│       ├── BaafooAgent    # premain 入口
│       ├── AgentManifest  # Bootstrap CL 安全供给站
│       ├── RouteTable     # 路由规则引擎（原子替换）
│       ├── advice/        # 7 个拦截器（Socket/NIO/Kafka/Pulsar/Consul DNS/Consul HTTP）
│       ├── channel/       # Agent-Server 控制通道（注册/心跳/轮询/录制上传）
│       ├── plugin/        # PluginManager（SPI 发现 + 独立 ClassLoader）
│       ├── loader/        # PluginClassLoader（parent=null 隔离）
│       └── transform/     # TransformRegistry
│
├── baafoo-server/         # 挡板服务模块
│   └── src/main/java/com/baafoo/server/
│       ├── bootstrap/     # Server 启动（多协议端口）
│       ├── handler/       # HttpStubHandler, TcpStubHandler
│       ├── api/           # ManagementApiHandler（REST API）
│       ├── storage/       # FileStorage（规则/环境/录制持久化）
│       └── web/           # StaticFileHandler（Web 控制台）
│
├── baafoo-cli/            # 命令行工具
│   └── BaafooCli          # baafoo init / version / help
│
└── web/                   # Web 控制台前端
    └── src/
        ├── api/           # Axios API 封装
        ├── router/        # Vue Router 路由配置
        ├── store/         # Pinia 状态管理
        └── views/         # 7 个页面组件
```

---

## 配置说明

### Agent 配置（`baafoo-agent.yml`）

```yaml
agentId: ""                          # Agent 唯一 ID（留空自动生成）
environment: "default"               # 所属测试环境（如 ft-1, ft-2）
serverUrl: "http://127.0.0.1:8084"   # Baafoo Server 地址
heartbeatIntervalSec: 30             # 心跳间隔（秒）
pollIntervalSec: 10                  # 规则轮询间隔（秒）
consulEnabled: false                 # 是否启用 Consul 拦截
consulAddress: "localhost:8500"      # Consul Agent 地址
protocols: []                        # 拦截的协议列表（空=全部）
maxRecordingSize: 10485760           # 最大录制大小（字节）
hotReload: true                      # 是否启用规则文件热加载
connectionRetries: 3                 # Server 连接重试次数
retryBackoffMs: 1000                 # 重试退避基数（毫秒）
```

### Server 配置（`baafoo-server.yml`）

```yaml
httpPort: 8084                       # 管理 API + Web 控制台端口
protocolPorts:
  http: 9000                         # HTTP Mock 端口
  tcp: 9001                          # TCP Mock 端口
  kafka: 9002                        # Kafka Mock 端口
  pulsar: 9003                       # Pulsar Mock 端口
  jms: 9004                          # JMS Mock 端口
dataDir: "./data"                    # 数据存储目录
rulesDir: "./data/rules"             # 规则存储目录
recordingsDir: "./data/recordings"   # 录制存储目录
recordingRetentionDays: 7            # 录制数据保留天数
corsEnabled: true                    # 是否启用 CORS
requestLogging: true                 # 是否启用请求日志
agentHeartbeatTimeoutSec: 60         # Agent 心跳超时（秒）
maxAgentsPerEnvironment: 50          # 每环境最大 Agent 数
```

---

## 使用指南

### 环境管理

Baafoo 的核心概念是**环境**。每个 Agent 启动时声明自己所属的环境，Server 端按环境维度控制运行模式：

| 模式 | 行为 |
|:-----|:-----|
| **Stub** | 拦截连接，按规则返回 Mock 响应 |
| **Passthrough** | 不拦截，所有请求直接透传到真实下游 |
| **Record** | 透传真实连接，同时录制请求/响应数据 |
| **Record-and-Stub** | 按规则返回 Mock 响应，同时录制 |

通过 Web 控制台或 API 切换环境模式，即时生效，无需重启 Agent。

```bash
# 创建环境
curl -X POST http://localhost:8084/__baafoo__/api/environments \
  -H 'Content-Type: application/json' \
  -d '{"name":"ft-1","mode":"stub","description":"FT-1 挡板自测环境"}'

# 切换模式
curl -X PUT http://localhost:8084/__baafoo__/api/environments/ft-1 \
  -H 'Content-Type: application/json' \
  -d '{"mode":"passthrough"}'
```

### 多环境并行

多套测试环境共享同一 Baafoo Server，各自独立控制模式：

```bash
# FT-1 环境 Agent
java -javaagent:baafoo-agent.jar=config=baafoo-agent-ft1.yml -jar my-app.jar
# baafoo-agent-ft1.yml → environment: ft-1

# FT-2 环境 Agent
java -javaagent:baafoo-agent.jar=config=baafoo-agent-ft2.yml -jar my-app.jar
# baafoo-agent-ft2.yml → environment: ft-2
```

### 规则管理

**规则全局共享**，不按环境区分。规则是否生效取决于 Agent 所属环境的模式——仅 `stub` 模式的 Agent 才会拦截匹配。

```bash
# 创建规则
curl -X POST http://localhost:8084/__baafoo__/api/rules \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "GET /api/users",
    "protocol": "http",
    "host": "api.example.com",
    "port": 8084,
    "conditions": [
      {"type": "method", "operator": "equals", "value": "GET"},
      {"type": "path", "operator": "startsWith", "value": "/api/users"}
    ],
    "responses": [
      {"name": "成功", "statusCode": 200, "body": "{\"code\":0,\"data\":[]}"}
    ]
  }'

# 撤销规则修改
curl -X POST http://localhost:8084/__baafoo__/api/rules/{id}/undo
```

### 参数化规则

同一接口按不同请求参数返回不同响应：

```json
{
  "name": "GET /api/users/{id}",
  "protocol": "http",
  "conditions": [
    {"type": "method", "operator": "equals", "value": "GET"},
    {"type": "path", "operator": "startsWith", "value": "/api/users"}
  ],
  "responses": [
    {
      "name": "VIP 用户",
      "condition": {"type": "header", "operator": "equals", "key": "X-User-Level", "value": "VIP"},
      "statusCode": 200,
      "body": "{\"id\":1,\"name\":\"Mock VIP User\",\"discount\":0.8}"
    },
    {
      "name": "默认响应",
      "statusCode": 200,
      "body": "{\"id\":1,\"name\":\"Mock User\",\"status\":\"active\"}"
    }
  ]
}
```

### Consul 服务发现

Agent 可拦截 Consul DNS 和 HTTP API 两种模式的服务发现，按服务名匹配规则：

```yaml
# baafoo-agent.yml
consulEnabled: true
consulAddress: "localhost:8500"
```

匹配 `order-service.service.consul` 的 DNS 解析，将地址重写为 Baafoo Server 的挡板地址。

### 场景集管理

将一组规则组织为场景集，一键启用/禁用：

```bash
# 创建场景集
curl -X POST http://localhost:8084/__baafoo__/api/scenes \
  -H 'Content-Type: application/json' \
  -d '{"name":"支付异常场景集","description":"支付超时、失败、退款异常","itemIds":["rule-1","rule-2","rule-3"]}'
```

---

## REST API 参考

所有 API 前缀：`/__baafoo__/api`

### 规则管理

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/rules` | 列出所有规则 |
| `POST` | `/rules` | 创建规则 |
| `GET` | `/rules/{id}` | 获取规则详情 |
| `PUT` | `/rules/{id}` | 更新规则 |
| `DELETE` | `/rules/{id}` | 删除规则 |
| `POST` | `/rules/{id}/undo` | 撤销规则到上一版本 |

### 环境管理

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/environments` | 列出所有环境 |
| `POST` | `/environments` | 创建环境 |
| `GET` | `/environments/{id}` | 获取环境详情 |
| `PUT` | `/environments/{id}` | 更新环境（切换模式） |
| `DELETE` | `/environments/{id}` | 删除环境 |

### 场景集管理

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/scenes` | 列出所有场景集 |
| `POST` | `/scenes` | 创建场景集 |
| `PUT` | `/scenes/{id}` | 更新场景集 |
| `DELETE` | `/scenes/{id}` | 删除场景集 |

### Agent 控制通道

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `POST` | `/agent/register` | Agent 注册 |
| `POST` | `/agent/heartbeat` | Agent 心跳 |
| `GET` | `/agent/poll` | Agent 拉取规则和模式 |
| `POST` | `/agent/recordings` | Agent 上传录制数据 |

### 录制管理

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/recordings` | 查询录制数据 |
| `DELETE` | `/recordings/{id}` | 删除录制 |

### 规则集

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/rulesets` | 列出所有规则集 |
| `POST` | `/rulesets` | 创建规则集 |

### 系统

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/status` | 获取系统状态 |

---

## Web 控制台开发

```bash
cd web

# 安装依赖
npm install

# 启动开发服务器（端口 3000，自动代理 API 到 localhost:8084）
npm run dev

# 构建生产版本
npm run build
```

构建产物输出到 `web/dist/`，可部署到 Baafoo Server 的静态资源目录。

技术栈：Vue 3 + Element Plus + Pinia + Vue Router + ECharts + Axios + Vite

---

## 插件开发

Baafoo Agent 支持插件化扩展。实现 `com.baafoo.plugin.AgentPlugin` 接口，打包为独立 JAR 放入 `./plugins/` 目录即可：

```java
public class MyPlugin implements AgentPlugin {
    @Override
    public String getName() { return "my-plugin"; }

    @Override
    public InterceptTarget getTarget() { return InterceptTarget.KAFKA; }

    @Override
    public void configure(Map<String, Object> config) {
        // 从 baafoo-agent.yml 读取插件级配置（可选）
    }

    @Override
    public void init() { /* 初始化 */ }

    @Override
    public InterceptResult intercept(PluginContext ctx) {
        // redirect: 重定向到 Mock Broker（二进制协议）
        // stub: 返回 Mock 响应（HTTP 协议）
        // passthrough: 放行到真实目标
    }

    @Override
    public void destroy() { /* 清理 */ }
}
```

在 JAR 的 `META-INF/services/com.baafoo.plugin.AgentPlugin` 中注册实现类。  
插件使用独立 ClassLoader（parent=null），与宿主应用依赖完全隔离。

### 插件配置

在 `baafoo-agent.yml` 中为每个插件指定独立配置：

```yaml
plugins:
  enabled: true
  directory: "./plugins"
  configs:
    my-plugin:
      redirectPort: 9050
      excludeTopics:
        - "internal-health"
```

### 健康监控

插件运行时会被自动监控。连续 5 次 `intercept()` 抛异常后自动禁用（UNHEALTHY）。可通过 Server REST API 查询状态：

```bash
# 查询所有 Agent 的插件状态
curl http://localhost:8084/__baafoo__/api/plugins

# 查询系统状态（含插件概览）
curl http://localhost:8084/__baafoo__/api/status
```

### 详细文档

完整的 API 参考、开发步骤、打包规范和示例，参见 [插件开发者指南](docs/plugin-developer-guide.md)。  
示例插件：[kafka-redirect](baafoo-example-plugins/kafka-redirect/)。

---

## 技术栈

| 层级 | 技术 | 版本 |
|:-----|:-----|:-----|
| 字节码增强 | Byte Buddy | 1.14.14 |
| 网络层 | Netty | 4.1.100 |
| JSON | Jackson | 2.15.3 |
| YAML | SnakeYAML | 1.33 |
| 日志 | SLF4J + Logback | 1.7.36 / 1.2.13 |
| 前端框架 | Vue 3 | 3.4 |
| UI 库 | Element Plus | 2.5 |
| 状态管理 | Pinia | 2.1 |
| 图表 | ECharts | 5.5 |
| 构建工具 | Maven / Vite | 3.6+ / 5.1 |
| Java | JDK 8+ | 1.8 |

---

## 协议支持状态

| 协议 | 状态 | 说明 |
|:-----|:----:|:-----|
| HTTP/1.1 | ✅ 完全支持 | 含参数化规则、条件匹配、延迟模拟、异常模拟 |
| TCP Socket | ✅ 完全支持 | 字节级匹配、长连接多轮交互、BIO/NIO 双模式拦截 |
| Consul DNS | ✅ 完全支持 | `.service.consul` 域名拦截 |
| Consul HTTP API | ✅ 支持 | OkHttp 客户端拦截 |
| Kafka | ✅ 支持 | Metadata/Produce/Fetch 核心路径；支持 topic 条件匹配、消息录制 |
| Pulsar / TDMQ | ✅ 支持 | 含 Lookup 阶段模拟；支持 topic 条件匹配、消息录制 |
| JMS | ✅ 支持 | ActiveMQ Artemis 内嵌模式；支持 Queue/Topic、消息录制 |

---

## 常见问题

<details>
<summary><strong>Agent 启动后没有连接到 Server 怎么办？</strong></summary>

1. 检查 `baafoo-agent.yml` 中 `serverUrl` 是否正确
2. 确认 Server 已启动且端口 8084 可达
3. 查看应用日志中是否有 Agent 注册失败的错误
4. 检查 `connectionRetries` 和 `retryBackoffMs` 配置是否合理

</details>

<details>
<summary><strong>Java 9+ 启动时报 IllegalAccessError 怎么办？</strong></summary>

Java 9+ 的模块系统限制了反射访问，需要在 JVM 启动参数中添加：

```
--add-opens java.base/java.net=ALL-UNNAMED
```

完整的启动命令：

```bash
java --add-opens java.base/java.net=ALL-UNNAMED -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar
```

</details>

<details>
<summary><strong>如何让多个环境使用不同的规则？</strong></summary>

Baafoo 的规则是全局共享的，但**规则的生效取决于 Agent 所属环境的模式**。只有处于 `stub` 模式的环境中的 Agent 才会拦截和匹配规则。你可以通过场景集（Scene Set）来组织规则组，然后在不同环境中启用/禁用不同的场景集。

</details>

<details>
<summary><strong>Kafka/Pulsar/JMS 有哪些限制？</strong></summary>

当前 Kafka/Pulsar/JMS Mock 已支持核心功能：

**Kafka Mock**:
- ✅ Metadata/Produce/Fetch 核心路径
- ✅ topic 条件匹配规则
- ✅ 消息录制（RECORD 模式）
- ⚠️ 暂不支持 `acks=all`、事务、Consumer Group Rebalance

**Pulsar Mock**:
- ✅ Lookup 阶段模拟
- ✅ topic 条件匹配规则
- ✅ 消息录制（RECORD 模式）
- ⚠️ v1.0 聚焦非分区 Topic + 单 Producer + 单 Consumer + Shared 订阅

**JMS Mock**:
- ✅ Queue/Topic 模式
- ✅ 消息录制（RECORD 模式）
- ✅ ActiveMQ Artemis 内嵌模式

如果需要高级特性，建议使用 Passthrough 模式透传到真实 Broker。

</details>

<details>
<summary><strong>多环境隔离是如何工作的？</strong></summary>

Baafoo 支持多套测试环境共享同一 Server，各自独立控制模式：

1. **Agent 配置环境标识**：`environment: ft-1`
2. **Server 端配置环境模式**：`stub` / `passthrough` / `record` / `record-and-stub`
3. **规则绑定环境**：规则通过 `environments` 字段声明生效环境

```bash
# 创建环境
curl -X POST http://localhost:8084/__baafoo__/api/environments \
  -H 'Content-Type: application/json' \
  -d '{"name":"ft-1","mode":"stub"}'

# 切换模式（即时生效，无需重启 Agent）
curl -X PUT http://localhost:8084/__baafoo__/api/environments/ft-1 \
  -H 'Content-Type: application/json' \
  -d '{"mode":"record"}'
```

</details>

<details>
<summary><strong>录制模式如何使用？</strong></summary>

将环境模式设置为 `record` 或 `record-and-stub`：

- **record**：透传真实下游，同时录制请求/响应数据
- **record-and-stub**：按规则返回 Mock 响应，同时录制

录制数据存储在 Server 端，可通过 API 查询：

```bash
# 查看录制数据
curl http://localhost:8084/__baafoo__/api/recordings

# 删除录制
curl -X DELETE http://localhost:8084/__baafoo__/api/recordings/{id}
```

</details>

<details>
<summary><strong>插件 JAR 放在哪个目录？</strong></summary>

将插件 JAR 放入应用工作目录下的 `./plugins/` 文件夹。Agent 启动时会自动扫描该目录，通过 SPI 机制发现并加载实现了 `AgentPlugin` 接口的插件。每个插件使用独立的 ClassLoader（parent=null），不会与宿主应用产生依赖冲突。

</details>

---

## MCP Server

Baafoo 提供 [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) Server，允许 AI Agent 直接管理 Mock 规则、环境、场景集等。

### 端点

```
POST http://<host>:8084/__baafoo__/api/mcp
```

### 认证

与 Management API 相同：
- `Authorization: Bearer <jwt-token>`
- `X-Api-Key: <api-key>`

### 支持的工具

| 类别 | 工具数 | 说明 |
|------|--------|------|
| Rules | 6 | 列出/查看/创建/更新/删除/撤销 Mock 规则 |
| Environments | 6 | 列出/查看/创建/更新/删除/关联规则到环境 |
| Scenes | 5 | 列出/查看/创建/更新/删除场景集 |
| Recordings | 3 | 列出/统计/删除录制 |
| MQ Relationships | 3 | 列出/创建/删除消息队列关系映射 |
| Agents | 2 | 列出/查看 Agent 状态 |
| System | 2 | 系统状态/导出 OpenAPI |

### 快速示例

```bash
curl -X POST http://localhost:8084/__baafoo__/api/mcp \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: <your-key>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'
```

### Agent Skill

Agent Skill 包位于 `agent-skill/baafoo-mock-manager/`，包含脚本和知识文件，可集成到 AI Agent 工作流中。

## 许可证

Private — Internal Use Only
