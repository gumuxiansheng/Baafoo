<div align="center">

# 🛡️ Baafoo

**JavaAgent-Based API Mock Platform**  
零侵入的微服务挡板系统

[![Java](https://img.shields.io/badge/Java-8%2B-orange?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.4-4FC08D?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](README_EN.md) · [快速开始](#快速开始) · [使用指南](#使用指南) · [API 参考](#rest-api-参考) · [插件开发](#插件开发)

</div>

---

Baafoo 通过 JavaAgent 字节码增强技术，在不修改任何业务代码的前提下，拦截应用对下游服务的网络调用，按规则返回 Mock 响应。支持 HTTP、TCP、gRPC、Kafka、Pulsar、JMS 等多种协议，以及 Consul / Nacos / Eureka / Feign 等服务发现架构。

## ✨ 核心特性

| 特性 | 说明 |
|:-----|:-----|
| 🚀 **零侵入** | 仅需在 JVM 启动参数中增加 `-javaagent`，业务代码无需任何修改 |
| 🌐 **多协议覆盖** | HTTP/REST、gRPC（Unary + Streaming）、原生 TCP Socket、Kafka、Pulsar、JMS |
| 🔍 **服务发现适配** | 支持 Consul DNS/HTTP API、Nacos、Eureka、Feign 等多种服务发现拦截 |
| 🏗️ **多环境管理** | 多套测试环境共享同一 Server，按环境维度独立控制挡板/透传/录制模式 |
| 🎯 **参数化规则** | 同一接口按不同请求参数（Header / Query / Body / JSONPath）返回不同 Mock 响应 |
| 🎬 **录制回放** | 透传模式下自动录制真实下游响应，后续可回放 |
| 🔥 **热切换** | 环境模式切换无需重启应用，通过控制通道实时下发 |
| 🖥️ **Web 控制台** | 可视化规则管理、请求日志、环境管理、场景集管理 |
| 📦 **场景集管理** | 将一组规则组织为场景集，一键启用/禁用 |
| 📜 **规则版本管理** | 规则修改自动快照，支持一键撤销 |
| ⚡ **快速起步** | `baafoo init` 命令生成完整项目骨架 |
| 🔐 **认证与权限** | JWT + API Key 双认证，RBAC 四角色（admin/developer/tester/guest） |
| 💥 **Chaos 工程** | 故障注入（延迟、错误码、连接重置）、Chaos Profile 激活/停用/紧急停止 |
| 🗄️ **数据库持久化** | 支持 H2 内嵌和 PostgreSQL，MyBatis ORM，自动 DDL |
| 📋 **OpenAPI 导入** | 从 OpenAPI 规范自动生成 Mock 规则 |
| 📊 **HAR 导出** | 请求日志导出为 HAR 格式，兼容浏览器 DevTools |
| 🔌 **多语言 SDK** | Go / Python / Node.js Thin SDK + Go Sidecar Proxy |
| 🤖 **MCP Server** | 内置 Model Context Protocol Server，AI Agent 可直接管理 Mock 规则 |

---

## 📑 目录

- [🛡️ Baafoo](#️-baafoo)
  - [✨ 核心特性](#-核心特性)
  - [📑 目录](#-目录)
  - [系统要求](#系统要求)
  - [快速开始](#快速开始)
    - [1️⃣ 构建项目](#1️⃣-构建项目)
    - [2️⃣ 初始化项目（推荐）](#2️⃣-初始化项目推荐)
    - [3️⃣ 启动 Server](#3️⃣-启动-server)
    - [4️⃣ 启动应用（附加 Agent）](#4️⃣-启动应用附加-agent)
    - [5️⃣ 打开 Web 控制台](#5️⃣-打开-web-控制台)
  - [项目结构](#项目结构)
  - [配置说明](#配置说明)
    - [Agent 配置（`baafoo-agent.yml`）](#agent-配置baafoo-agentyml)
    - [Server 配置（`baafoo-server.yml`）](#server-配置baafoo-serveryml)
      - [数据库配置](#数据库配置)
      - [认证配置](#认证配置)
  - [使用指南](#使用指南)
    - [环境管理](#环境管理)
    - [多环境并行](#多环境并行)
    - [规则管理](#规则管理)
    - [参数化规则](#参数化规则)
    - [故障注入](#故障注入)
    - [Chaos 工程](#chaos-工程)
    - [Consul 服务发现](#consul-服务发现)
    - [场景集管理](#场景集管理)
    - [认证与用户管理](#认证与用户管理)
    - [OpenAPI 导入](#openapi-导入)
    - [HAR 导出](#har-导出)
    - [多语言 SDK](#多语言-sdk)
    - [Sidecar Proxy](#sidecar-proxy)
  - [REST API 参考](#rest-api-参考)
    - [规则管理](#规则管理-1)
    - [规则集](#规则集-1)
    - [环境管理](#环境管理-1)
    - [场景集管理](#场景集管理-1)
    - [Agent 控制通道](#agent-控制通道)
    - [录制管理](#录制管理)
    - [认证](#认证-1)
    - [用户管理](#用户管理)
    - [Chaos 工程](#chaos-工程-1)
    - [插件管理](#插件管理)
    - [MQ 关系映射](#mq-关系映射)
    - [系统](#系统)
  - [Web 控制台开发](#web-控制台开发)
  - [插件开发](#插件开发)
    - [插件配置](#插件配置)
    - [健康监控](#健康监控)
    - [详细文档](#详细文档)
  - [技术栈](#技术栈)
  - [协议支持状态](#协议支持状态)
  - [常见问题](#常见问题)
  - [MCP Server](#mcp-server)
    - [端点](#端点)
    - [认证](#认证)
    - [支持的工具](#支持的工具)
    - [快速示例](#快速示例)
    - [Agent Skill](#agent-skill)
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
| 9002 | Kafka Mock | 模拟 Kafka Broker |
| 9003 | Pulsar Mock | 模拟 Pulsar Broker |
| 9004 | JMS Mock | 模拟 JMS Broker |
| 9005 | gRPC Mock | 接收被重定向的 gRPC 请求 |
| 10005 | gRPC Streaming Mock | 接收被重定向的 gRPC streaming 请求（HTTP/2） |

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
│       ├── model/         # Rule, MatchCondition, ResponseEntry, Environment, SceneSet, ChaosProfile, FaultInjection 等
│       ├── config/        # AgentConfig, ServerConfig, ConfigLoader
│       ├── api/           # ApiResponse 统一响应格式
│       └── util/          # MatchEngine, ChaosManager, FaultInjector, TemplateEngine, FakerProvider, JsonPathUtil, OpenApiImporter, StatefulCounterStore, VarintCodec 等
│
├── baafoo-plugin-api/     # Agent Plugin SPI 接口
│   └── src/main/java/com/baafoo/plugin/
│       ├── AgentPlugin    # 插件生命周期接口（含 onConnect/onRequest/onResponse 分阶段钩子）
│       ├── PluginContext  # 拦截上下文
│       └── InterceptResult / InterceptTarget
│       └── PluginServices / ServerAdmin / RuleStore / RecordingStore  # Server 端插件服务接口
│
├── baafoo-agent/          # JavaAgent 字节码增强模块
│   └── src/main/java/com/baafoo/agent/
│       ├── BaafooAgent    # premain 入口
│       ├── AgentManifest  # Bootstrap CL 安全供给站
│       ├── GlobalRouteState # 路由状态门面（Facade，委托给 state/ 下的管理类）
│       ├── state/         # 6 个状态管理类（RouteTable/DnsCache/RecordingTracker/LogBridge/PluginBridge/ProtocolMapper）
│       ├── advice/        # 拦截器（Socket/NIO/Kafka/Pulsar/JMS/gRPC/Consul DNS/Consul HTTP/Feign/DNS/HTTP Server）
│       ├── channel/       # Agent-Server 控制通道（注册/心跳/轮询/录制上传）
│       ├── plugin/        # PluginManager（SPI 发现 + 独立 ClassLoader + 健康监控 + 事件总线）
│       ├── loader/        # PluginClassLoader（parent=null 隔离）
│       └── transform/     # TransformRegistry
│
├── baafoo-server/         # 挡板服务模块
│   └── src/main/java/com/baafoo/server/
│       ├── bootstrap/     # Server 启动（多协议端口）
│       ├── handler/       # HttpStubHandler, TcpStubHandler, GrpcStubHandler, GrpcStreamingHandler, PassthroughProxy, RecordingHelper
│       ├── api/           # ManagementApiHandler + 12 个 ResourceHandler（规则/环境/场景/Agent/录制/Chaos/Auth/User/Plugin/MQ/MCP/Status）
│       ├── auth/          # AuthFilter, AuthService（JWT + API Key + RBAC）
│       ├── broker/        # KafkaMockBroker, PulsarMockBroker, JmsMockBroker（完整协议编解码）
│       ├── mcp/           # MCP Server 实现（McpToolRegistry + 8 个 Tool 类）
│       ├── storage/       # FileStorage + JdbcStorageService（MyBatis, H2/PostgreSQL, 自动 DDL）
│       └── web/           # StaticFileHandler（Web 控制台）
│
├── baafoo-cli/            # 命令行工具
│   └── BaafooCli          # baafoo init / version / help
│
├── baafoo-plugin-api/     # Agent Plugin SPI 接口
├── baafoo-example-plugins/ # 示例插件（feign, kafka-redirect, tdmq）
├── baafoo-spring-boot-starter-test/ # Spring Boot 测试自动配置
├── baafoo-testcontainers/ # Testcontainers 模块（一键启动 Baafoo Server）
├── baafoo-test-app/       # 测试应用
├── baafoo-test-spring/    # Spring 测试应用
├── baafoo-test-pulsar/    # Pulsar 测试应用
│
├── web/                   # Web 控制台前端
│   └── src/
│       ├── api/           # Axios API 封装
│       ├── router/        # Vue Router 路由配置
│       ├── store/         # Pinia 状态管理
│       └── views/         # 7 个页面组件
│
├── agent-skill/           # AI Agent Skill 包（MCP 集成）
├── sdks/                  # 多语言 Thin SDK
│   ├── go/baafoo/         # Go SDK（Thin + Full SDK 拦截层）
│   ├── python/baafoo/     # Python SDK（Thin + Full SDK 拦截层）
│   └── nodejs/            # Node.js SDK（Thin + Full SDK 拦截层）
│   └── PROTOCOL-v2.md     # Baafoo Protocol v2 语言无关规范
├── proxy/                 # Sidecar Proxy（Go 透明 TCP 代理）
│    ├── proxy.go           # 多端口监听、协议检测、录制上传
│    └── config.go          # YAML 配置加载
├── docs/                  # 文档
│   └── plugin-developer-guide.md
├── testing/               # 测试体系
└── deploy/                # 部署文件
```

---

## 测试体系

### 层次结构

| 层级 | 工具 | 覆盖范围 | Docker 依赖 |
|:-----|:-----|:---------|:-----------:|
| **单元测试** | JUnit 4 + Mockito | 各模块核心逻辑、匹配引擎、编解码器 | ❌ |
| **集成测试** | JUnit 4 + Testcontainers | 协议兼容性验证（Kafka / Pulsar / JMS） | ✅ |
| **端到端测试** | Testcontainers + Docker Compose | Agent 字节码增强全链路 + 多环境编排 | ✅ |

### 单元测试

所有模块的基本功能验证，无需外部依赖：

```bash
# 全部模块
mvn test

# 单模块
mvn test -pl baafoo-core
mvn test -pl baafoo-server -Dtest="KafkaMockBrokerTest"
```

| 测试类 | 模块 | 说明 |
|:-------|:-----|:-----|
| `KafkaMockBrokerTest` | baafoo-server | Kafka 协议编解码、消息存储、规则匹配、MQ 关系映射 |
| `PulsarMockBrokerTest` | baafoo-server | Pulsar 协议 CONNECT/LOOKUP/PRODUCER/SEND/SUBSCRIBE 处理 |
| `JmsMockBrokerTest` | baafoo-server | JMS Queue/Topic、FIFO 顺序、消息录制 |
| `MatchEngineTest` | baafoo-core | 规则匹配引擎、优先级排序、condition 条件组合 |
| `SocketInterceptionIntegrationTest` | baafoo-agent | Socket 拦截核心逻辑（非字节码增强） |

### 集成测试 (Testcontainers)

使用真实容器验证 Baafoo Mock Broker 的**二进制协议兼容性**。通过在 Docker 容器中启动真实服务（Kafka、ActiveMQ），运行与 Mock Broker 相同的客户端操作，对比行为一致性。

```bash
# 前置条件：Docker 需运行中
# 运行全部集成测试
mvn test -pl baafoo-server -Dtest="*CompatibilityTest"

# 运行单个协议测试
mvn test -pl baafoo-server -Dtest="KafkaProtocolCompatibilityTest"
mvn test -pl baafoo-server -Dtest="JmsProtocolCompatibilityTest"
mvn test -pl baafoo-server -Dtest="PulsarProtocolCompatibilityTest"
```

| 测试类 | 真实容器 | 验证内容 |
|:-------|:---------|:---------|
| `KafkaProtocolCompatibilityTest` | `confluentinc/cp-kafka:7.4.0` | ApiVersions 握手、Produce/Fetch 往返、Stub 规则注入 |
| `JmsProtocolCompatibilityTest` | `rmohr/activemq:5.15.9` | Queue FIFO 顺序、Topic 广播、消息计数一致性 |
| `PulsarProtocolCompatibilityTest` | —（Mock Broker 仅使用真实 Pulsar 客户端） | CONNECT 握手、Producer/SEND、订阅消费、Stub 注入 |

> **Docker 不可用时**：所有容器依赖的测试自动跳过（`assumeTrue`），Mock Broker 基础验证仍正常执行。

### 端到端测试

#### Agent 容器化集成测试

在 Docker 容器中启动 Baafoo Server + 挂载 Agent 的测试应用，验证字节码增强全链路：

```bash
# 前置条件：先构建镜像
mvn clean package -DskipTests
docker build -t baafoo-server:latest .
docker build -t baafoo-test-spring:latest -f baafoo-test-spring/Dockerfile .

# 运行测试
mvn test -pl baafoo-server -Dtest="AgentContainerizedIntegrationTest"
```

验证流程：
1. 启动 Server 容器 → 健康检查 `/__baafoo__/api/status`
2. 启动测试应用容器（挂载 Agent）→ Agent 向 Server 注册
3. 通过 API 创建 Mock 规则
4. 向测试应用发送 HTTP 请求 → 请求经 Agent 拦截 → 返回 Stub 响应

#### Staging 环境编排

```bash
# 启动完整 staging 集群（含 PostgreSQL、多环境 Agent）
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build

# 重建特定服务
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build server app-env-a
```

#### 全链路集成测试脚本

完整 48 个测试用例（覆盖 Framework / HTTP / gRPC / TCP / Kafka / Pulsar / JMS / Plugin / Environment / Condition / Mode），基于 Docker Compose staging 环境：

```bash
# PowerShell（Windows）
testing/test-fullchain.ps1

# Bash（Linux/macOS）
testing/test-integration.ps1
```

测试资产位于 `testing/` 目录：

| 目录/文件 | 说明 |
|:----------|:-----|
| `testing/test-rules/` | 34 个 JSON 规则文件，覆盖全部协议与条件类型 |
| `testing/deploy/staging/` | Staging 环境 Agent & Server 配置 |
| `testing/TEST-MANUAL.md` | 完整测试手册 |
| `testing/TEST-REPORT.md` | 最新测试报告 |

### 持续集成建议

```yaml
# GitHub Actions 示例
- name: Unit & Integration Tests
  run: mvn test -pl baafoo-server -Dtest="*MockBrokerTest,*CompatibilityTest"

- name: Full-chain E2E (requires Docker)
  run: |
    mvn clean package -DskipTests
    docker build -t baafoo-server:latest .
    docker build -t baafoo-test-spring:latest -f baafoo-test-spring/Dockerfile .
    pwsh testing/test-fullchain.ps1
```

## Testcontainers 模块

`baafoo-testcontainers` 模块让用户在集成测试中**一键启动 Baafoo Server**，无需手动搭建环境。

### 添加依赖

```xml
<dependency>
    <groupId>com.baafoo</groupId>
    <artifactId>baafoo-testcontainers</artifactId>
    <version>1.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### 快速开始

```java
// JUnit 4
@ClassRule
public static BaafooServerContainer baafoo = new BaafooServerContainer();

@Test
public void testWithBaafoo() {
    String serverUrl = baafoo.getHttpBaseUrl();
    // 你的测试逻辑...
}
```

### 核心 API

#### `BaafooServerContainer`

| 方法 | 说明 |
|:-----|:-----|
| `new BaafooServerContainer()` | 使用默认镜像 `baafoo-server:latest` |
| `new BaafooServerContainer("custom-image:tag")` | 指定自定义镜像 |
| `withApiKey(String)` | 设置 API Key（Server 开启 auth 时需要） |
| `withRule(Rule)` | 预加载一条规则 |
| `withRuleFromClasspath("rules/my-rule.json")` | 从 classpath JSON 文件加载规则 |
| `withRuleFromFile("/path/to/rule.json")` | 从本地文件加载规则 |
| `withRuleFromJson("{\"protocol\":\"http\",...}")` | 从 JSON 字符串加载规则 |
| `withEnvironment("staging", "stub")` | 预加载一个环境 |
| `getHttpBaseUrl()` | 获取 Server API 的 HTTP 基础地址 |
| `getClient()` | 获取 `BaafooClient`，用于编程式管理规则/环境 |

预加载的规则和环境在容器启动后自动通过 REST API 注入。

#### `BaafooClient`

```java
BaafooClient client = baafoo.getClient();

// 规则管理
client.createRule(rule);
client.listRules();
client.getRule("rule-id");
client.updateRule("rule-id", rule);
client.deleteRule("rule-id");

// 环境管理
client.createEnvironment("ft-1", "stub");
client.setEnvironmentMode("ft-1", "passthrough");
client.listEnvironments();

// 场景集管理
client.createSceneSet(sceneSet);
client.listSceneSets();

// 系统状态
client.getStatus();
```

### 编程式规则配置示例

```java
@ClassRule
public static BaafooServerContainer baafoo = new BaafooServerContainer()
        .withEnvironment("test", "stub")
        .withRule(new Rule() {{
            setName("GET /api/users");
            setProtocol("http");
            setHost("api.example.com");
            setConditions(Collections.singletonList(
                    MatchCondition.path("equals", "/api/users")));
            setResponses(Collections.singletonList(
                    new ResponseEntry() {{ setBody("{\"users\":[]}"); }}));
        }});

@Test
public void testUserService() {
    String stubUrl = "http://localhost:" + baafoo.getMappedPort(9000);
    // stubUrl 指向 Baafoo Server 的 HTTP Mock 端口
    // Agent 模式下无需手动获取端口，Agent 自动路由
}
```

### 镜像构建

首次使用前需构建 Baafoo Server 镜像：

```bash
mvn clean package -DskipTests
docker build -t baafoo-server:latest .
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
protocols: []                        # 拦截的协议列表（空=全部）
maxRecordingSize: 10485760           # 最大录制大小（字节）
hotReload: true                      # 是否启用规则文件热加载
connectionRetries: 3                 # Server 连接重试次数
retryBackoffMs: 1000                 # 重试退避基数（毫秒）
```

<details>
<summary><strong>完整 Agent 配置项</strong></summary>

```yaml
agentId: ""                          # Agent 唯一 ID（留空自动生成）
environment: "default"               # 所属测试环境
serverUrl: "http://127.0.0.1:8084"   # Baafoo Server 地址（旧字段，server.host 优先）
server:                              # Server 连接配置（推荐，覆盖 serverUrl）
  host: "127.0.0.1"
  apiPort: 8084
  httpPort: 9000
  tcpPort: 9001
  kafkaPort: 9002
  pulsarPort: 9003
  jmsPort: 9004
  grpcPort: 9005
  grpcStreamingPort: 10005
  useSsl: false
  apiKey: ""                         # API Key（Server 开启 auth 时需要）
heartbeatIntervalSec: 30
pollIntervalSec: 10
protocols: []
maxRecordingSize: 10485760
rulesFilePath: ""                    # 规则文件路径（WatchService 热加载）
hotReload: true
failOpen: false                      # true: Agent 初始化失败时静默放行（不报错）
connectionRetries: 3
retryBackoffMs: 1000
plugins:                             # 插件系统配置
  enabled: true
  directory: "./plugins"
  configs:                           # 按插件名配置
    my-plugin:
      customPort: 9050
```

| 配置项 | 默认值 | 说明 |
|:-------|:-------|:-----|
| `failOpen` | `false` | Agent 初始化失败时的行为：`true` 静默放行不报错，`false` 记录 ERROR 日志但仍放行 |
| `rulesFilePath` | — | 规则文件路径，配合 `hotReload` 实现 WatchService 文件监听 |
| `server` | — | Server 连接对象（host + 各协议端口 + useSsl + apiKey），优先级高于 `serverUrl` |
| `server.apiKey` | — | Server 认证 API Key（Server 开启 auth 时必需） |
| `server.useSsl` | `false` | 控制通道是否使用 HTTPS |

</details>

### Server 配置（`baafoo-server.yml`）

```yaml
httpPort: 8084                       # 管理 API + Web 控制台端口
protocolPorts:
  http: 9000                         # HTTP Mock 端口
  tcp: 9001                          # TCP Mock 端口
  kafka: 9002                        # Kafka Mock 端口
  pulsar: 9003                       # Pulsar Mock 端口
  jms: 9004                          # JMS Mock 端口
  grpc: 9005                         # gRPC Mock 端口
dataDir: "./data"                    # 数据存储目录
rulesDir: "./data/rules"             # 规则存储目录
recordingsDir: "./data/recordings"   # 录制存储目录
recordingRetentionDays: 7            # 录制数据保留天数
corsEnabled: true                    # 是否启用 CORS
requestLogging: true                 # 是否启用请求日志
agentHeartbeatTimeoutSec: 60         # Agent 心跳超时（秒）
maxAgentsPerEnvironment: 50          # 每环境最大 Agent 数
```

<details>
<summary><strong>完整 Server 配置项</strong></summary>

```yaml
httpPort: 8084                       # 管理 API + Web 控制台端口
protocolPorts:
  http: 9000
  tcp: 9001
  kafka: 9002
  pulsar: 9003
  jms: 9004
  grpc: 9005
dataDir: "./data"
rulesDir: "./data/rules"
recordingsDir: "./data/recordings"
recordingRetentionDays: 7
recordingMaxSizeMb: 500              # 录制存储上限（MB），超出自动清理
maxRulesPerPage: 100                 # 分页查询每页规则数上限
corsEnabled: true
corsOrigins:                         # CORS 允许的来源列表（空=允许所有）
  - "http://localhost:3000"
webConsolePath: ""                   # Web 控制台静态文件路径（空=内置）
requestLogging: true
agentHeartbeatTimeoutSec: 60
maxAgentsPerEnvironment: 50
unmatchedDefault: "passthrough"      # 未匹配规则时的默认行为：passthrough | 404
passthroughSslVerifyDisabled: false  # 透传时禁用 SSL 验证（仅测试环境）
messagingAdvertisedHost: ""          # Kafka/Pulsar 广播地址（Docker NAT 场景）
unknownEnvironmentDefault: "passthrough"  # 未知环境的默认模式：passthrough | stub
database:                            # 数据库配置
  type: "h2"                         # h2 | postgresql
  url: ""
  username: "sa"
  password: ""
auth:                                # 认证配置
  enabled: true                      # 是否启用认证
  localBypass: false                 # 127.0.0.1 请求自动获得 admin 角色
  jwtSecret: ""                      # JWT 签名密钥
  tokenExpiryHours: 24               # JWT Token 有效期（小时）
  apiKeys:                           # API Key → 角色映射
    dev-key-001: "developer"
    admin-key-001: "admin"
  trustedProxies: []                 # 信任的代理 IP（允许设置 X-Forwarded-For）
```

| 配置项 | 默认值 | 说明 |
|:-------|:-------|:-----|
| `recordingMaxSizeMb` | `500` | 录制存储总上限（MB），`RecordingCleanupTask` 自动清理过期数据 |
| `maxRulesPerPage` | `100` | 分页查询时每页最大规则数 |
| `corsOrigins` | — | CORS 允许来源列表，为空时允许所有来源 |
| `webConsolePath` | — | Web 控制台静态文件路径，为空时使用内置资源 |
| `unmatchedDefault` | `passthrough` | Stub 模式下未匹配规则的请求行为：`passthrough` 透传或 `404` 拒绝 |
| `passthroughSslVerifyDisabled` | `false` | 透传代理是否禁用 SSL 证书验证（仅测试环境使用） |
| `messagingAdvertisedHost` | — | Kafka Metadata 和 Pulsar LOOKUP 响应中的广播地址。Docker 环境下设为客户端可达的主机名 |
| `unknownEnvironmentDefault` | `passthrough` | Agent 注册时 IP 无法匹配任何环境的默认模式：`passthrough`（安全）或 `stub`（激进） |

#### 数据库配置

Server 支持两种存储后端：

| 类型 | 说明 | 适用场景 |
|:-----|:-----|:---------|
| `h2` | 内嵌 H2 数据库，无需外部依赖 | 开发测试、小规模部署 |
| `postgresql` | 外部 PostgreSQL 数据库 | 生产环境、多实例部署 |

```yaml
database:
  type: "postgresql"
  url: "jdbc:postgresql://db-host:5432/baafoo"
  username: "baafoo"
  password: "secret"
```

> 使用 PostgreSQL 时，DDL 由 `DdlBuilder` 根据数据库方言自动生成。MyBatis 映射器（RuleMapper、EnvironmentMapper、RecordingMapper 等 7 个）自动适配。

#### 认证配置

| 配置项 | 默认值 | 说明 |
|:-------|:-------|:-----|
| `auth.enabled` | `true` | 是否启用认证。`false` 时所有请求自动获得 admin 角色 |
| `auth.localBypass` | `false` | `true` 时 127.0.0.1 请求自动获得 admin 角色，无需提供 Token/API Key |
| `auth.jwtSecret` | — | JWT 签名密钥。未设置时使用随机密钥（每次重启变化） |
| `auth.tokenExpiryHours` | `24` | JWT Token 有效期（小时） |
| `auth.apiKeys` | — | API Key 到角色的静态映射 |
| `auth.trustedProxies` | `[]` | 信任的代理 IP 列表，允许从 `X-Forwarded-For` 提取客户端真实 IP |

**RBAC 角色**：

| 角色 | 权限 |
|:-----|:-----|
| `admin` | 全部操作（用户管理、规则、环境、场景、录制、Chaos） |
| `developer` | 规则、环境、场景、录制、Chaos 的读写 |
| `tester` | 规则、环境、场景、录制的读取 + 录制上传 |
| `guest` | 只读 |

</details>

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
| **Record-all** | 透传所有请求（含未匹配），同时录制所有请求/响应数据 |

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

**规则全局共享**，不按环境区分。规则是否生效取决于 Agent 所属环境的模式——`stub`、`record-and-stub`、`record-all` 模式的 Agent 会拦截匹配并返回 Mock 响应；`passthrough`、`record` 模式则透传到真实下游。

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
# 服务名拦截默认启用（由 BaafooAgent 默认挂载 DnsResolveAdvice + HttpOpenServerAdvice）
# 行为由运行时路由表动态控制，无需配置项
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

### 故障注入

规则可以配置 `faultInjection` 字段，实现概率性故障注入：

```json
{
  "name": "订单服务故障注入",
  "protocol": "http",
  "host": "order-service",
  "port": 8080,
  "conditions": [
    {"field": "path", "operator": "startsWith", "value": "/api/orders"}
  ],
  "responses": [
    {"statusCode": 200, "body": "{\"status\":\"ok\"}"}
  ],
  "faultInjection": {
    "faults": [
      {"type": "DELAY", "probability": 0.3, "delayMs": 2000},
      {"type": "HTTP_ERROR", "probability": 0.1, "statusCodes": [500, 502, 503]},
      {"type": "CONNECTION_RESET", "probability": 0.05},
      {"type": "READ_TIMEOUT", "probability": 0.05, "timeoutMs": 5000}
    ]
  }
}
```

**支持的故障类型**：

| 故障类型 | 适用协议 | 说明 |
|:---------|:---------|:-----|
| `HTTP_ERROR` | HTTP | 返回指定的 HTTP 错误状态码 |
| `DELAY` | HTTP | 延迟响应指定毫秒数 |
| `CONNECTION_RESET` | HTTP | 直接关闭连接（RST） |
| `READ_TIMEOUT` | HTTP | 读取超时 |
| `KAFKA_NOT_LEADER_FOR_PARTITION` | Kafka | 模拟非 Leader 分区错误 |
| `KAFKA_OFFSET_OUT_OF_RANGE` | Kafka | 模拟 Offset 越界错误 |
| `KAFKA_PRODUCE_THROTTLE` | Kafka | 模拟生产限流 |
| `KAFKA_DELAY` | Kafka | 延迟 Kafka 响应 |
| `KAFKA_CONNECTION_RESET` | Kafka | 重置 Kafka 连接 |

### Chaos 工程

Chaos 工程允许将多个故障注入规则组织为命名 Profile，批量激活/停用：

```bash
# 激活 Chaos Profile
curl -X POST http://localhost:8084/__baafoo__/api/chaos/profiles/activate \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -d '{"profileName": "payment-failure-scenario"}'

# 查看所有 Profile 状态
curl http://localhost:8084/__baafoo__/api/chaos/profiles/status \
  -H 'Authorization: Bearer <token>'

# 紧急停止所有活跃 Chaos 规则
curl -X POST http://localhost:8084/__baafoo__/api/chaos/emergency-stop \
  -H 'Authorization: Bearer <token>'
```

### 认证与用户管理

#### 登录获取 JWT Token

```bash
curl -X POST http://localhost:8084/__baafoo__/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"your-password","expiresIn":3600}'
```

#### 使用 Token 或 API Key 访问 API

```bash
# JWT Token
curl http://localhost:8084/__baafoo__/api/rules \
  -H 'Authorization: Bearer eyJhbGciOi...'

# API Key
curl http://localhost:8084/__baafoo__/api/rules \
  -H 'X-Api-Key: dev-key-001'
```

#### 用户管理

```bash
# 创建用户
curl -X POST http://localhost:8084/__baafoo__/api/users \
  -H 'Authorization: Bearer <admin-token>' \
  -H 'Content-Type: application/json' \
  -d '{"username":"tester1","password":"secure-pass","role":"tester","displayName":"测试一号"}'

# 生成 API Key
curl -X POST http://localhost:8084/__baafoo__/api/users/tester1/api-key \
  -H 'Authorization: Bearer <admin-token>'

# CSV 批量导入用户
curl -X POST http://localhost:8084/__baafoo__/api/users/import \
  -H 'Authorization: Bearer <admin-token>' \
  -H 'Content-Type: text/csv' \
  --data-binary @users.csv
```

CSV 格式：`username,password,role,displayName,email`

### OpenAPI 导入

从 OpenAPI 3.x 规范自动生成 Mock 规则：

```bash
curl -X POST http://localhost:8084/__baafoo__/api/rules/import-openapi \
  -H 'Content-Type: application/json' \
  -d '{"spec": "https://petstore3.swagger.io/api/v3/openapi.json"}'
```

也支持直接传入 OpenAPI JSON 内容。导入后自动为每个 Path 生成对应的 Mock 规则。

### HAR 导出

将请求日志导出为 HAR (HTTP Archive) 格式，可直接在浏览器 DevTools 中导入查看：

```bash
curl http://localhost:8084/__baafoo__/api/logs/export/har \
  -H 'Authorization: Bearer <token>' \
  -o baafoo-logs.har
```

### 多语言 SDK

Baafoo 提供 Go / Python / Node.js 三种语言的 Thin SDK，用于非 Java 应用的 Mock 拦截。详细规范参见 [`sdks/PROTOCOL-v2.md`](sdks/PROTOCOL-v2.md)。

| 语言 | 包路径 | 安装方式 |
|:-----|:-------|:---------|
| Go | `github.com/baafoo/sdk-go` | `go get github.com/baafoo/sdk-go/baafoo` |
| Python | `baafoo` | `pip install baafoo` 或 `pip install .` |
| Node.js | `@baafoo/sdk` | `npm install @baafoo/sdk` 或 `npm install ./sdks/nodejs` |

#### 快速开始（Python 示例）

```python
from baafoo import BaafooClient

client = BaafooClient(
    server_url="http://localhost:8084",
    environment="default",
    api_key="dev-key-001"
)
client.start()  # 注册 + 心跳 + 轮询

# HTTP 拦截（自动重定向到 Baafoo Server）
from baafoo import intercept_http
intercept_http(client)
```

#### 快速开始（Node.js 示例）

```javascript
const { BaafooClient, interceptHttp } = require('@baafoo/sdk');

const client = new BaafooClient({
    serverUrl: 'http://localhost:8084',
    environment: 'default',
    apiKey: 'dev-key-001'
});
await client.start();
interceptHttp(client);
```

#### 快速开始（Go 示例）

```go
import "github.com/baafoo/sdk-go/baafoo"

client := baafoo.NewClient("http://localhost:8084", "default", baafoo.WithAPIKey("dev-key-001"))
client.Start()
baafoo.InterceptHTTP(client)
```

### Sidecar Proxy

对于不支持 SDK 拦截的运行时（如 Rust、C++），可使用 Go 编写的 Sidecar Proxy 透明代理：

```yaml
# proxy.yml
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
  apiKey: "dev-key-001"

agent:
  environment: "default"
  appName: "baafoo-proxy"
  heartbeatIntervalSec: 15
  pollIntervalSec: 10
```

```bash
./proxy --config proxy.yml
```

Proxy 会自动注册为 Baafoo Agent，按配置将流量重定向到 Baafoo Server，并上传录制数据。

---

## REST API 参考

所有 API 前缀：`/__baafoo__/api`。认证方式：JWT Token（`Authorization: Bearer <token>`）或 API Key（`X-Api-Key: <key>`）。Server 关闭认证时无需提供。

### 规则管理

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/rules` | 列出所有规则 |
| `POST` | `/rules` | 创建规则 |
| `GET` | `/rules/{id}` | 获取规则详情 |
| `PUT` | `/rules/{id}` | 更新规则 |
| `DELETE` | `/rules/{id}` | 删除规则 |
| `POST` | `/rules/{id}/undo` | 撤销规则到上一版本 |
| `POST` | `/rules/reset-all-state` | 重置所有规则的有状态计数器 |
| `POST` | `/rules/import-openapi` | 从 OpenAPI 规范导入规则 |

### 规则集-1

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/rulesets` | 列出所有规则集 |
| `POST` | `/rulesets` | 创建规则集 |

### 环境管理

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/environments` | 列出所有环境 |
| `POST` | `/environments` | 创建环境 |
| `GET` | `/environments/{id}` | 获取环境详情 |
| `PUT` | `/environments/{id}` | 更新环境（切换模式） |
| `DELETE` | `/environments/{id}` | 删除环境 |

### 场景集管理-1

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
| `GET` | `/agents` | 列出所有已注册 Agent |

### 录制管理

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/recordings` | 查询录制数据 |
| `DELETE` | `/recordings/{id}` | 删除录制 |

### 认证-1

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `POST` | `/auth/login` | 用户登录，返回 JWT Token |
| `GET` | `/auth/me` | 获取当前用户信息与权限列表 |

### 用户管理

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/users` | 列出所有用户 |
| `POST` | `/users` | 创建用户 |
| `POST` | `/users/import` | CSV 批量导入用户 |
| `PUT` | `/users/{username}/role` | 修改用户角色 |
| `POST` | `/users/{username}/api-key` | 生成用户 API Key |
| `DELETE` | `/users/{username}/api-key` | 吊销用户 API Key |
| `DELETE` | `/users/{username}` | 删除用户 |

### Chaos 工程-1

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `POST` | `/chaos/profiles/activate` | 激活 Chaos Profile |
| `POST` | `/chaos/profiles/deactivate` | 停用 Chaos Profile |
| `GET` | `/chaos/profiles/status` | 查看所有 Profile 状态 |
| `POST` | `/chaos/emergency-stop` | 紧急停止所有活跃 Chaos 规则 |

### 插件管理

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/plugins` | 列出所有 Agent 插件状态（支持 `?agentId=xxx` 过滤） |

### MQ 关系映射

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/mq-relationships` | 列出所有 MQ 关系映射 |
| `POST` | `/mq-relationships` | 创建 MQ 关系映射 |
| `DELETE` | `/mq-relationships/{id}` | 删除 MQ 关系映射 |

### 系统

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/status` | 获取系统状态 |
| `GET` | `/logs/export/har` | 导出 HAR 格式请求日志 |

> **MCP Server 端点** `POST /mcp` 参见 [MCP Server 章节](#mcp-server)。

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

Baafoo Agent 通过 Java SPI 机制支持插件化扩展。实现 `com.baafoo.plugin.AgentPlugin` 接口，打包为独立 JAR 放入 `./plugins/` 目录即可。插件可覆盖所有协议（Socket/NIO/Kafka/Pulsar/JMS/gRPC/Consul DNS/Consul API/Feign），每个 `InterceptTarget` 同一时刻只能有一个插件注册。

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

    // 新插件推荐覆写 onConnect/onRequest/onResponse 分阶段钩子；
    // 旧插件只需实现 intercept()，默认钩子会委托到它。
    @Override
    public ConnectAdvice onConnect(ConnectContext ctx) {
        // redirect: 重定向到 Mock Broker（二进制协议）
        // passthrough: 放行到真实目标
        // block: 阻断连接
    }

    @Override
    public InterceptResult intercept(PluginContext ctx) {
        // 旧版统一钩子（@Deprecated，新插件用上面的分阶段钩子）
    }

    @Override
    public void destroy() { /* 清理 */ }
}
```

在 JAR 的 `META-INF/services/com.baafoo.plugin.AgentPlugin` 中注册实现类。  
插件使用独立 ClassLoader（parent=null），与宿主应用依赖完全隔离。插件运行时会被自动监控——连续 5 次 `intercept()` 抛异常后自动禁用（UNHEALTHY），可通过 REST API 重新启用。

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
示例插件：[feign](baafoo-example-plugins/feign/)、[kafka-redirect](baafoo-example-plugins/kafka-redirect/)、[tdmq](baafoo-example-plugins/tdmq/)。

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
| gRPC | ✅ 支持 | gRPC over HTTP/1.1；支持 service/method/path 匹配、grpc-status 状态码、protobuf 消息帧 |
| gRPC Streaming | ✅ 支持 | Server Streaming / Client Streaming / Bidi Streaming（端口 10005） |
| TCP Socket | ✅ 完全支持 | 字节级匹配、长连接多轮交互、BIO/NIO 双模式拦截 |
| Consul DNS | ✅ 完全支持 | `.service.consul` 域名拦截 |
| Consul HTTP API | ✅ 支持 | OkHttp 客户端拦截 |
| Nacos / Eureka | ✅ 支持 | 默认挂载的服务名拦截（注册中心无关：Nacos/Consul/Eureka 等） |
| Feign | ✅ 支持 | Feign Client 拦截（示例插件 `baafoo-example-plugins/feign`） |
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

Baafoo 的规则是全局共享的，但**规则的生效取决于 Agent 所属环境的模式**。处于 `stub`、`record-and-stub`、`record-all` 模式的环境中的 Agent 会拦截匹配并返回 Mock 响应；`passthrough`、`record` 模式则透传到真实下游。你可以通过场景集（Scene Set）来组织规则组，然后在不同环境中启用/禁用不同的场景集。规则也可以通过 `environments` 字段指定生效环境列表。

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
2. **Server 端配置环境模式**：`stub` / `passthrough` / `record` / `record-and-stub` / `record-all`
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

将环境模式设置为 `record`、`record-and-stub` 或 `record-all`：

- **record**：透传真实下游，同时录制请求/响应数据（仅录制被规则匹配的请求）
- **record-and-stub**：按规则返回 Mock 响应，同时录制（仅录制被规则匹配的请求）
- **record-all**：透传所有请求（含未被规则匹配的），同时录制所有请求/响应数据

录制数据存储在 Server 端，可通过 API 查询：

```bash
# 查看录制数据
curl http://localhost:8084/__baafoo__/api/recordings

# 删除录制
curl -X DELETE http://localhost:8084/__baafoo__/api/recordings/{id}
```

</details>

<details>
<summary><strong>如何启用数据库持久化？</strong></summary>

默认使用内嵌 H2 数据库，数据存储在 `dataDir` 目录下。切换到 PostgreSQL：

```yaml
database:
  type: "postgresql"
  url: "jdbc:postgresql://db-host:5432/baafoo"
  username: "baafoo"
  password: "secret"
```

DDL 会在 Server 启动时自动创建。无需手动建表。

</details>

<details>
<summary><strong>如何配置认证？</strong></summary>

默认认证已启用但使用随机 JWT 密钥。生产环境需配置固定密钥：

```yaml
auth:
  enabled: true
  jwtSecret: "your-fixed-secret-key"
  tokenExpiryHours: 24
  apiKeys:
    dev-key-001: "developer"
    admin-key-001: "admin"
  localBypass: false
  trustedProxies:
    - "10.0.0.1"
```

开发环境可设置 `localBypass: true` 跳过本地请求认证，或设置 `enabled: false` 完全关闭认证。

</details>

<details>
<summary><strong>未匹配规则的请求怎么处理？</strong></summary>

由 Server 配置 `unmatchedDefault` 决定：

- `passthrough`（默认）：透传到真实下游服务
- `404`：返回 404 错误

`record-all` 模式下，未匹配的请求也会被透传并录制。

</details>

<details>
<summary><strong>Docker 环境下 Kafka/Pulsar 客户端连不上怎么办？</strong></summary>

Docker NAT 会导致容器内监听的 IP 与客户端连接的 IP 不一致。设置 `messagingAdvertisedHost` 为客户端可达的主机名：

```yaml
messagingAdvertisedHost: "localhost"
```

Server 会在 Kafka Metadata 和 Pulsar LOOKUP 响应中使用这个地址。

</details>

<details>
<summary><strong>插件 JAR 放在哪个目录？</strong></summary>

将插件 JAR 放入应用工作目录下的 `./plugins/` 文件夹。Agent 启动时会自动扫描该目录，通过 SPI 机制发现并加载实现了 `AgentPlugin` 接口的插件。每个插件使用独立的 ClassLoader（parent=null），不会与宿主应用产生依赖冲突。

</details>

---

## MCP Server

Baafoo 提供 [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) Server，允许 AI Agent 直接管理 Mock 规则、环境、场景集等。

### 端点

所有 Management API 端点均可通过 MCP 访问。

```
POST http://<host>:8084/__baafoo__/api/mcp
```

### 认证

与 Management API 相同：JWT Token 或 API Key。参见[认证配置](#认证配置)。

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

### Agent Skill 包

Agent Skill 包位于 `agent-skill/baafoo-mock-manager/`，包含脚本和知识文件，可集成到 AI Agent 工作流中。也可通过 Baafoo MCP Server 与 AI Agent 实时交互。

## 许可证

MIT
