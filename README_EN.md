<div align="center">

# 🛡️ Baafoo

**JavaAgent-Based API Mock Platform**  
Zero-Intrusion Microservice Stub Platform

[![Java](https://img.shields.io/badge/Java-8%2B-orange?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7-brightgreen?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.4-4FC08D?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](README_EN.md) · [Quick Start](#quick-start) · [Usage Guide](#usage-guide) · [API Reference](#rest-api-reference) · [Plugin Development](#plugin-development)

</div>

---

Baafoo uses JavaAgent bytecode instrumentation to intercept downstream network calls from applications and return Mock responses based on rules — all without modifying any business code. It supports HTTP, TCP, gRPC, Kafka, Pulsar, JMS, and other protocols, as well as service discovery architectures like Consul, Nacos, Eureka, and Feign.

## ✨ Core Features

| Feature | Description |
|:-----|:-----|
| 🚀 **Zero Intrusion** | Only requires adding `-javaagent` to JVM startup arguments; no business code changes needed |
| 🌐 **Multi-Protocol Coverage** | HTTP/REST, gRPC (Unary + Streaming), native TCP Socket, Kafka, Pulsar, JMS |
| 🔍 **Service Discovery Adaptation** | Supports Consul DNS/HTTP API, Nacos, Eureka, Feign, and more service discovery interception |
| 🏗️ **Multi-Environment Management** | Multiple test environments sharing a single server, independently controlling stub/passthrough/recording modes per environment |
| 🎯 **Parameterized Rules** | Same endpoint returns different Mock responses based on different request parameters (Header / Query / Body / JSONPath) |
| 🎬 **Record & Replay** | Automatically record real downstream responses in passthrough mode for later replay |
| 🔥 **Hot Swap** | Environment mode switching without application restart; delivered in real time via control channel |
| 🖥️ **Web Console** | Visual rule management, request logs, environment management, scene set management |
| 📦 **Scene Set Management** | Organize a group of rules into scene sets for one-click enable/disable |
| 📜 **Rule Version Management** | Automatic snapshots on rule modification with one-click rollback |
| ⚡ **Quick Start** | `baafoo init` command generates a complete project skeleton |
| 🔐 **Authentication & Authorization** | JWT + API Key dual authentication, RBAC with 4 roles (admin/developer/tester/guest) |
| 💥 **Chaos Engineering** | Fault injection (delay, error codes, connection reset), Chaos Profile activate/deactivate/emergency stop |
| 🗄️ **Database Persistence** | Supports H2 embedded and PostgreSQL, MyBatis ORM, automatic DDL |
| 📋 **OpenAPI Import** | Auto-generate Mock rules from OpenAPI specifications |
| 📊 **HAR Export** | Export request logs in HAR format, compatible with browser DevTools |
| 🔌 **Multi-Language SDK** | Go / Python / Node.js Thin SDK + Go Sidecar Proxy |
| 🤖 **MCP Server** | Built-in Model Context Protocol Server for AI Agent direct Mock rule management |

---

## 📑 Table of Contents

- [🛡️ Baafoo](#️-baafoo)
  - [✨ Core Features](#-core-features)
  - [📑 Table of Contents](#-table-of-contents)
  - [System Requirements](#system-requirements)
  - [Quick Start](#quick-start)
    - [1️⃣ Build the Project](#1️⃣-build-the-project)
    - [2️⃣ Initialize the Project (Recommended)](#2️⃣-initialize-the-project-recommended)
    - [3️⃣ Start the Server](#3️⃣-start-the-server)
    - [4️⃣ Start the Application (with Agent)](#4️⃣-start-the-application-with-agent)
    - [5️⃣ Open the Web Console](#5️⃣-open-the-web-console)
  - [Project Structure](#project-structure)
  - [Configuration](#configuration)
    - [Agent Configuration (`baafoo-agent.yml`)](#agent-configuration-baafoo-agentyml)
    - [Server Configuration (`baafoo-server.yml`)](#server-configuration-baafoo-serveryml)
      - [Database Configuration](#database-configuration)
      - [Authentication Configuration](#authentication-configuration)
  - [Usage Guide](#usage-guide)
    - [Environment Management](#environment-management)
    - [Multi-Environment Parallel Running](#multi-environment-parallel-running)
    - [Rule Management](#rule-management)
    - [Parameterized Rules](#parameterized-rules)
    - [Fault Injection](#fault-injection)
    - [Chaos Engineering](#chaos-engineering)
    - [Consul Service Discovery](#consul-service-discovery)
    - [Scene Set Management](#scene-set-management)
    - [Authentication & User Management](#authentication--user-management)
    - [OpenAPI Import](#openapi-import)
    - [HAR Export](#har-export)
    - [Multi-Language SDK](#multi-language-sdk)
    - [Sidecar Proxy](#sidecar-proxy)
  - [REST API Reference](#rest-api-reference)
    - [Rule Management](#rule-management-1)
    - [Rule Sets](#rule-sets)
    - [Environment Management](#environment-management-1)
    - [Scene Set Management](#scene-set-management-1)
    - [Agent Control Channel](#agent-control-channel)
    - [Recording Management](#recording-management)
    - [Authentication](#authentication)
    - [User Management](#user-management)
    - [Chaos Engineering](#chaos-engineering-1)
    - [Plugin Management](#plugin-management)
    - [MQ Relationship Mapping](#mq-relationship-mapping)
    - [System](#system)
  - [Web Console Development](#web-console-development)
  - [Plugin Development](#plugin-development)
    - [Plugin Configuration](#plugin-configuration)
    - [Health Monitoring](#health-monitoring)
    - [Detailed Documentation](#detailed-documentation)
  - [Tech Stack](#tech-stack)
  - [Protocol Support Status](#protocol-support-status)
  - [FAQ](#faq)
  - [MCP Server](#mcp-server)
    - [Endpoint](#endpoint)
    - [Authentication](#authentication-1)
    - [Supported Tools](#supported-tools)
    - [Quick Example](#quick-example)
    - [Agent Skill](#agent-skill)
  - [License](#license)

---

## System Requirements

| Item | Requirement |
|:----:|:----:|
| Java | 8+ (Recommended: 8 / 11 / 17) |
| Maven | 3.6+ |
| Node.js | 16+ (Required only for Web Console development) |
| OS | Windows / macOS / Linux |

> ⚠️ **Java 9+ Note**: Additional JVM argument required: `--add-opens java.base/java.net=ALL-UNNAMED`

---

## Quick Start

### 1️⃣ Build the Project

```bash
git clone <repo-url> baafoo
cd baafoo
mvn clean package -DskipTests
```

Build Artifacts:

| File | Path | Description |
|:-----|:-----|:-----|
| `baafoo-agent.jar` | `baafoo-agent/target/` | Agent JAR (with shaded dependencies) |
| `baafoo-server.jar` | `baafoo-server/target/` | Server JAR (with shaded dependencies) |
| `baafoo-cli.jar` | `baafoo-cli/target/` | CLI Tool JAR |

### 2️⃣ Initialize the Project (Recommended)

```bash
java -jar baafoo-cli/target/baafoo-cli.jar init my-project
cd my-project
```

`baafoo init` generates the following files:

```
my-project/
├── baafoo-agent.yml      # Agent configuration template
├── baafoo-server.yml     # Server configuration template
├── baafoo-rules.yml      # Sample rules
├── start-agent.sh        # Agent start script (Linux/macOS)
├── start-agent.bat       # Agent start script (Windows)
├── start-server.sh       # Server start script (Linux/macOS)
└── start-server.bat      # Server start script (Windows)
```

### 3️⃣ Start the Server

```bash
# Linux / macOS
java -jar baafoo-server.jar ./baafoo-server.yml

# Windows
java -jar baafoo-server.jar .\baafoo-server.yml
```

The server listens on the following ports after startup:

| Port | Protocol | Description |
|:----:|:----:|:-----|
| 8084 | Management API + Web Console | API prefix `/__baafoo__/api` |
| 9000 | HTTP Mock | Receives redirected HTTP requests |
| 9001 | TCP Mock | Receives redirected TCP connections |
| 9002 | Kafka Mock | Simulates a Kafka Broker |
| 9003 | Pulsar Mock | Simulates a Pulsar Broker |
| 9004 | JMS Mock | Simulates a JMS Broker |
| 9005 | gRPC Mock | Receives redirected gRPC requests |
| 10005 | gRPC Streaming Mock | Receives redirected gRPC streaming requests (HTTP/2) |

### 4️⃣ Start the Application (with Agent)

```bash
java -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar
```

The Agent automatically registers with the Server, fetches rules, and decides whether to intercept connections based on the environment mode.

### 5️⃣ Open the Web Console

Open in browser: **http://localhost:8084/__baafoo__/**

---

## Project Structure

```
baafoo/
├── baafoo-core/           # Core model, config parsing, rule matching engine
│   └── src/main/java/com/baafoo/core/
│       ├── model/         # Rule, MatchCondition, ResponseEntry, Environment, SceneSet, ChaosProfile, FaultInjection, etc.
│       ├── config/        # AgentConfig, ServerConfig, ConfigLoader
│       ├── api/           # ApiResponse unified response format
│       └── util/          # MatchEngine, ChaosManager, FaultInjector, TemplateEngine, FakerProvider, JsonPathUtil, OpenApiImporter, StatefulCounterStore, VarintCodec, etc.
│
├── baafoo-plugin-api/     # Agent Plugin SPI interfaces
│   └── src/main/java/com/baafoo/plugin/
│       ├── AgentPlugin    # Plugin lifecycle interface (with onConnect/onRequest/onResponse phased hooks)
│       ├── PluginContext  # Interception context
│       └── InterceptResult / InterceptTarget
│       └── PluginServices / ServerAdmin / RuleStore / RecordingStore  # Server-side plugin service interfaces
│
├── baafoo-agent/          # JavaAgent bytecode instrumentation module
│   └── src/main/java/com/baafoo/agent/
│       ├── BaafooAgent    # premain entry point
│       ├── AgentManifest  # Bootstrap classloader safe supply station
│       ├── GlobalRouteState # Routing state facade (delegates to state/ managers)
│       ├── state/         # 6 state management classes (RouteTable/DnsCache/RecordingTracker/LogBridge/PluginBridge/ProtocolMapper)
│       ├── advice/        # Interceptors (Socket/NIO/Kafka/Pulsar/JMS/gRPC/Consul DNS/Consul HTTP/Feign/DNS/HTTP Server)
│       ├── channel/       # Agent-Server control channel (registration/heartbeat/polling/recording upload)
│       ├── plugin/        # PluginManager (SPI discovery + isolated ClassLoader + health monitoring + event bus)
│       ├── loader/        # PluginClassLoader (parent=null isolation)
│       └── transform/     # TransformRegistry
│
├── baafoo-server/         # Stub service module
│   └── src/main/java/com/baafoo/server/
│       ├── bootstrap/     # Server startup (multi-protocol ports)
│       ├── handler/       # HttpStubHandler, TcpStubHandler, GrpcStubHandler, GrpcStreamingHandler, PassthroughProxy, RecordingHelper
│       ├── api/           # ManagementApiHandler + 12 ResourceHandlers (Rules/Environment/Scene/Agent/Recording/Chaos/Auth/User/Plugin/MQ/MCP/Status)
│       ├── auth/          # AuthFilter, AuthService (JWT + API Key + RBAC)
│       ├── broker/        # KafkaMockBroker, PulsarMockBroker, JmsMockBroker (full protocol codec)
│       ├── mcp/           # MCP Server implementation (McpToolRegistry + 8 Tool classes)
│       ├── storage/       # FileStorage + JdbcStorageService (MyBatis, H2/PostgreSQL, auto DDL)
│       └── web/           # StaticFileHandler (Web Console)
│
├── baafoo-cli/            # Command-line tool
│   └── BaafooCli          # baafoo init / version / help
│
├── baafoo-plugin-api/     # Agent Plugin SPI interfaces
├── baafoo-example-plugins/ # Example plugins (feign, kafka-redirect, tdmq)
├── baafoo-spring-boot-starter-test/ # Spring Boot test auto-configuration
├── baafoo-testcontainers/ # Testcontainers module (one-click Baafoo Server startup)
├── baafoo-test-app/       # Test application
├── baafoo-test-spring/    # Spring test application
├── baafoo-test-pulsar/    # Pulsar test application
│
├── web/                   # Web Console frontend
│   └── src/
│       ├── api/           # Axios API wrapper
│       ├── router/        # Vue Router configuration
│       ├── store/         # Pinia state management
│       └── views/         # 7 page components
│
├── agent-skill/           # AI Agent Skill package (MCP integration)
├── sdks/                  # Multi-language Thin SDKs
│   ├── go/baafoo/         # Go SDK (Thin + Full SDK interception layer)
│   ├── python/baafoo/     # Python SDK (Thin + Full SDK interception layer)
│   └── nodejs/            # Node.js SDK (Thin + Full SDK interception layer)
│   └── PROTOCOL-v2.md     # Baafoo Protocol v2 language-agnostic specification
├── proxy/                 # Sidecar Proxy (Go transparent TCP proxy)
│    ├── proxy.go           # Multi-port listening, protocol detection, recording upload
│    └── config.go          # YAML configuration loader
├── docs/                  # Documentation
│   └── plugin-developer-guide.md
├── testing/               # Testing framework
└── deploy/                # Deployment files
```

---

## Testing Framework

### Hierarchy

| Level | Tool | Scope | Docker Required |
|:-----|:-----|:---------|:-----------:|
| **Unit Tests** | JUnit 4 + Mockito | Module core logic, matching engine, codecs | ❌ |
| **Integration Tests** | JUnit 4 + Testcontainers | Protocol compatibility validation (Kafka / Pulsar / JMS) | ✅ |
| **End-to-End Tests** | Testcontainers + Docker Compose | Agent bytecode instrumentation full chain + multi-environment orchestration | ✅ |

### Unit Tests

Basic functionality validation for all modules, no external dependencies:

```bash
# All modules
mvn test

# Single module
mvn test -pl baafoo-core
mvn test -pl baafoo-server -Dtest="KafkaMockBrokerTest"
```

| Test Class | Module | Description |
|:-------|:-----|:-----|
| `KafkaMockBrokerTest` | baafoo-server | Kafka protocol codec, message storage, rule matching, MQ relationship mapping |
| `PulsarMockBrokerTest` | baafoo-server | Pulsar protocol CONNECT/LOOKUP/PRODUCER/SEND/SUBSCRIBE handling |
| `JmsMockBrokerTest` | baafoo-server | JMS Queue/Topic, FIFO ordering, message recording |
| `MatchEngineTest` | baafoo-core | Rule matching engine, priority sorting, condition combination |
| `SocketInterceptionIntegrationTest` | baafoo-agent | Core socket interception logic (non-bytecode instrumentation) |

### Integration Tests (Testcontainers)

Validates **binary protocol compatibility** of Baafoo Mock Brokers using real containers. Starts real services (Kafka, ActiveMQ) in Docker containers, runs the same client operations as against Mock Brokers, and compares behavioral consistency.

```bash
# Prerequisite: Docker must be running
# Run all integration tests
mvn test -pl baafoo-server -Dtest="*CompatibilityTest"

# Run individual protocol tests
mvn test -pl baafoo-server -Dtest="KafkaProtocolCompatibilityTest"
mvn test -pl baafoo-server -Dtest="JmsProtocolCompatibilityTest"
mvn test -pl baafoo-server -Dtest="PulsarProtocolCompatibilityTest"
```

| Test Class | Real Container | Validates |
|:-------|:---------|:---------|
| `KafkaProtocolCompatibilityTest` | `confluentinc/cp-kafka:7.4.0` | ApiVersions handshake, Produce/Fetch round-trip, Stub rule injection |
| `JmsProtocolCompatibilityTest` | `rmohr/activemq:5.15.9` | Queue FIFO ordering, Topic broadcast, message count consistency |
| `PulsarProtocolCompatibilityTest` | — (Mock Broker uses only real Pulsar client) | CONNECT handshake, Producer/SEND, subscription consumption, Stub injection |

> **When Docker is unavailable**: All container-dependent tests are automatically skipped (`assumeTrue`). Mock Broker basic validation still runs normally.

### End-to-End Tests

#### Agent Containerized Integration Tests

Start Baafoo Server + a test application with Agent attached in Docker containers to validate the full bytecode instrumentation chain:

```bash
# Prerequisite: build images first
mvn clean package -DskipTests
docker build -t baafoo-server:latest .
docker build -t baafoo-test-spring:latest -f baafoo-test-spring/Dockerfile .

# Run tests
mvn test -pl baafoo-server -Dtest="AgentContainerizedIntegrationTest"
```

Validation flow:
1. Start Server container → health check `/__baafoo__/api/status`
2. Start test application container (with Agent) → Agent registers with Server
3. Create Mock rules via API
4. Send HTTP requests to test application → requests intercepted by Agent → return Stub response

#### Staging Environment Orchestration

```bash
# Start the full staging cluster (with PostgreSQL, multi-environment Agents)
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build

# Rebuild specific services
docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build server app-env-a
```

#### Full-Chain Integration Test Script

48 complete test cases (covering Framework / HTTP / gRPC / TCP / Kafka / Pulsar / JMS / Plugin / Environment / Condition / Mode), based on Docker Compose staging environment:

```bash
# PowerShell (Windows)
testing/3_SystemTest/test-fullchain.ps1

# Bash (Linux/macOS)
testing/2_IntegrationTest/test-integration.ps1
```

Test assets located in `testing/` directory:

| Directory/File | Description |
|:----------|:-----|
| `testing/2_IntegrationTest/rules/` | 34 JSON rule files covering all protocols and condition types |
| `testing/deploy/staging/` | Staging environment Agent & Server configuration |
| `testing/3_SystemTest/TEST-MANUAL.md` | Complete test manual |
| `testing/3_SystemTest/TEST-REPORT.md` | Latest test report |

### Continuous Integration Recommendations

```yaml
# GitHub Actions example
- name: Unit & Integration Tests
  run: mvn test -pl baafoo-server -Dtest="*MockBrokerTest,*CompatibilityTest"

- name: Full-chain E2E (requires Docker)
  run: |
    mvn clean package -DskipTests
    docker build -t baafoo-server:latest .
    docker build -t baafoo-test-spring:latest -f baafoo-test-spring/Dockerfile .
    pwsh testing/3_SystemTest/test-fullchain.ps1
```

## Testcontainers Module

The `baafoo-testcontainers` module lets users **start Baafoo Server with one click** in integration tests, without manually setting up environments.

### Add Dependency

```xml
<dependency>
    <groupId>com.baafoo</groupId>
    <artifactId>baafoo-testcontainers</artifactId>
    <version>1.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### Quick Start

```java
// JUnit 4
@ClassRule
public static BaafooServerContainer baafoo = new BaafooServerContainer();

@Test
public void testWithBaafoo() {
    String serverUrl = baafoo.getHttpBaseUrl();
    // Your test logic...
}
```

### Core API

#### `BaafooServerContainer`

| Method | Description |
|:-----|:-----|
| `new BaafooServerContainer()` | Use default image `baafoo-server:latest` |
| `new BaafooServerContainer("custom-image:tag")` | Specify custom image |
| `withApiKey(String)` | Set API Key (needed when Server has auth enabled) |
| `withRule(Rule)` | Preload one rule |
| `withRuleFromClasspath("rules/my-rule.json")` | Load rule from classpath JSON file |
| `withRuleFromFile("/path/to/rule.json")` | Load rule from local file |
| `withRuleFromJson("{\"protocol\":\"http\",...}")` | Load rule from JSON string |
| `withEnvironment("staging", "stub")` | Preload one environment |
| `getHttpBaseUrl()` | Get Server API HTTP base URL |
| `getClient()` | Get `BaafooClient` for programmatic rule/environment management |

Preloaded rules and environments are automatically injected via REST API after container startup.

#### `BaafooClient`

```java
BaafooClient client = baafoo.getClient();

// Rule management
client.createRule(rule);
client.listRules();
client.getRule("rule-id");
client.updateRule("rule-id", rule);
client.deleteRule("rule-id");

// Environment management
client.createEnvironment("ft-1", "stub");
client.setEnvironmentMode("ft-1", "passthrough");
client.listEnvironments();

// Scene set management
client.createSceneSet(sceneSet);
client.listSceneSets();

// System status
client.getStatus();
```

### Programmatic Rule Configuration Example

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
    // stubUrl points to Baafoo Server's HTTP Mock port
    // When Agent mode is used, manual port retrieval is not needed — Agent routes automatically
}
```

### Image Build

Build the Baafoo Server image before first use:

```bash
mvn clean package -DskipTests
docker build -t baafoo-server:latest .
```

---

## Configuration

### Agent Configuration (`baafoo-agent.yml`)

```yaml
agentId: ""                          # Unique Agent ID (auto-generated if empty)
environment: "default"               # Target test environment (e.g. ft-1, ft-2)
serverUrl: "http://127.0.0.1:8084"   # Baafoo Server address
heartbeatIntervalSec: 30             # Heartbeat interval (seconds)
pollIntervalSec: 10                  # Rule polling interval (seconds)
protocols: []                        # Protocols to intercept (empty = all)
maxRecordingSize: 10485760           # Maximum recording size (bytes)
hotReload: true                      # Enable rule file hot reload
connectionRetries: 3                 # Server connection retry count
retryBackoffMs: 1000                 # Retry backoff base (milliseconds)
```

<details>
<summary><strong>Full Agent Configuration Options</strong></summary>

```yaml
agentId: ""                          # Unique Agent ID (auto-generated if empty)
environment: "default"               # Target test environment
serverUrl: "http://127.0.0.1:8084"   # Baafoo Server address (legacy field, server.host takes priority)
server:                              # Server connection config (recommended, overrides serverUrl)
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
  apiKey: ""                         # API Key (required when Server auth is enabled)
heartbeatIntervalSec: 30
pollIntervalSec: 10
protocols: []
maxRecordingSize: 10485760
rulesFilePath: ""                    # Rule file path (WatchService hot reload)
hotReload: true
failOpen: false                      # true: silently pass through on Agent init failure (no error)
connectionRetries: 3
retryBackoffMs: 1000
plugins:                             # Plugin system configuration
  enabled: true
  directory: "./plugins"
  configs:                           # Per-plugin configuration
    my-plugin:
      customPort: 9050
```

| Option | Default | Description |
|:-------|:-------|:-----|
| `failOpen` | `false` | Behavior on Agent initialization failure: `true` silently pass through, `false` log ERROR but still pass through |
| `rulesFilePath` | — | Rule file path, used with `hotReload` for WatchService file monitoring |
| `server` | — | Server connection object (host + protocol ports + useSsl + apiKey), higher priority than `serverUrl` |
| `server.apiKey` | — | Server authentication API Key (required when Server auth is enabled) |
| `server.useSsl` | `false` | Whether to use HTTPS for the control channel |

</details>

### Server Configuration (`baafoo-server.yml`)

```yaml
httpPort: 8084                       # Management API + Web Console port
protocolPorts:
  http: 9000                         # HTTP Mock port
  tcp: 9001                          # TCP Mock port
  kafka: 9002                        # Kafka Mock port
  pulsar: 9003                       # Pulsar Mock port
  jms: 9004                          # JMS Mock port
  grpc: 9005                         # gRPC Mock port
dataDir: "./data"                    # Data storage directory
rulesDir: "./data/rules"             # Rule storage directory
recordingsDir: "./data/recordings"   # Recording storage directory
recordingRetentionDays: 7            # Recording data retention (days)
corsEnabled: true                    # Enable CORS
requestLogging: true                 # Enable request logging
agentHeartbeatTimeoutSec: 60         # Agent heartbeat timeout (seconds)
maxAgentsPerEnvironment: 50          # Max Agents per environment
```

<details>
<summary><strong>Full Server Configuration Options</strong></summary>

```yaml
httpPort: 8084                       # Management API + Web Console port
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
recordingMaxSizeMb: 500              # Maximum recording storage (MB); auto-cleanup on overflow
maxRulesPerPage: 100                 # Max rules per page in paginated queries
corsEnabled: true
corsOrigins:                         # Allowed CORS origins list (empty = allow all)
  - "http://localhost:3000"
webConsolePath: ""                   # Web Console static file path (empty = use built-in)
requestLogging: true
agentHeartbeatTimeoutSec: 60
maxAgentsPerEnvironment: 50
unmatchedDefault: "passthrough"      # Default behavior for unmatched rules: passthrough | 404
passthroughSslVerifyDisabled: false  # Disable SSL verification in passthrough (test environments only)
messagingAdvertisedHost: ""          # Advertised address for Kafka/Pulsar (Docker NAT scenarios)
unknownEnvironmentDefault: "passthrough"  # Default mode for unknown environments: passthrough | stub
database:                            # Database configuration
  type: "h2"                         # h2 | postgresql
  url: ""
  username: "sa"
  password: ""
auth:                                # Authentication configuration
  enabled: true                      # Enable authentication
  localBypass: false                 # Auto-grant admin role to 127.0.0.1 requests
  jwtSecret: ""                      # JWT signing key
  tokenExpiryHours: 24               # JWT Token expiry (hours)
  apiKeys:                           # API Key → Role mapping
    dev-key-001: "developer"
    admin-key-001: "admin"
  trustedProxies: []                 # Trusted proxy IPs (allows X-Forwarded-For header)
```

| Option | Default | Description |
|:-------|:-------|:-----|
| `recordingMaxSizeMb` | `500` | Total recording storage cap (MB); `RecordingCleanupTask` auto-cleans expired data |
| `maxRulesPerPage` | `100` | Maximum rules per page in paginated queries |
| `corsOrigins` | — | Allowed CORS origins; empty means allow all origins |
| `webConsolePath` | — | Web Console static file path; empty means use built-in resources |
| `unmatchedDefault` | `passthrough` | Behavior for unmatched requests in stub mode: `passthrough` or `404` |
| `passthroughSslVerifyDisabled` | `false` | Whether to disable SSL certificate verification in passthrough proxy (test environments only) |
| `messagingAdvertisedHost` | — | Advertised address in Kafka Metadata and Pulsar LOOKUP responses. Set to client-reachable hostname in Docker environments |
| `unknownEnvironmentDefault` | `passthrough` | Default mode when Agent registration IP cannot match any environment: `passthrough` (safe) or `stub` (aggressive) |

#### Database Configuration

Server supports two storage backends:

| Type | Description | Use Case |
|:-----|:-----|:---------|
| `h2` | Embedded H2 database, no external dependencies | Dev/test, small-scale deployment |
| `postgresql` | External PostgreSQL database | Production, multi-instance deployment |

```yaml
database:
  type: "postgresql"
  url: "jdbc:postgresql://db-host:5432/baafoo"
  username: "baafoo"
  password: "secret"
```

> When using PostgreSQL, DDL is auto-generated by `DdlBuilder` based on database dialect. MyBatis mappers (RuleMapper, EnvironmentMapper, RecordingMapper, etc., 7 total) auto-adapt.

#### Authentication Configuration

| Option | Default | Description |
|:-------|:-------|:-----|
| `auth.enabled` | `true` | Enable authentication. When `false`, all requests auto-get admin role |
| `auth.localBypass` | `false` | When `true`, 127.0.0.1 requests auto-get admin role, no Token/API Key required |
| `auth.jwtSecret` | — | JWT signing key. If not set, a random key is used (changes on each restart) |
| `auth.tokenExpiryHours` | `24` | JWT Token expiry time (hours) |
| `auth.apiKeys` | — | Static mapping from API Key to role |
| `auth.trustedProxies` | `[]` | Trusted proxy IP list; allows extracting client real IP from `X-Forwarded-For` |

**RBAC Roles**:

| Role | Permissions |
|:-----|:-----|
| `admin` | All operations (user management, rules, environments, scenes, recordings, Chaos) |
| `developer` | Read/write for rules, environments, scenes, recordings, Chaos |
| `tester` | Read for rules, environments, scenes, recordings + recording upload |
| `guest` | Read-only |

</details>

---

## Usage Guide

### Environment Management

Baafoo's core concept is the **environment**. Each Agent declares its environment at startup, and the Server controls the running mode per environment:

| Mode | Behavior |
|:-----|:-----|
| **Stub** | Intercept connections and return Mock responses based on rules |
| **Passthrough** | Do not intercept; pass all requests directly to the real downstream |
| **Record** | Pass through real connections while recording request/response data |
| **Record-and-Stub** | Return Mock responses based on rules while recording |
| **Record-all** | Pass through all requests (including unmatched ones) while recording all request/response data |

Switch environment modes via the Web Console or API — takes effect immediately without restarting the Agent.

```bash
# Create an environment
curl -X POST http://localhost:8084/__baafoo__/api/environments \
  -H 'Content-Type: application/json' \
  -d '{"name":"ft-1","mode":"stub","description":"FT-1 stub self-test environment"}'

# Switch mode
curl -X PUT http://localhost:8084/__baafoo__/api/environments/ft-1 \
  -H 'Content-Type: application/json' \
  -d '{"mode":"passthrough"}'
```

### Multi-Environment Parallel Running

Multiple test environments sharing the same Baafoo Server, each with independent mode control:

```bash
# FT-1 environment Agent
java -javaagent:baafoo-agent.jar=config=baafoo-agent-ft1.yml -jar my-app.jar
# baafoo-agent-ft1.yml → environment: ft-1

# FT-2 environment Agent
java -javaagent:baafoo-agent.jar=config=baafoo-agent-ft2.yml -jar my-app.jar
# baafoo-agent-ft2.yml → environment: ft-2
```

### Rule Management

**Rules are globally shared**, not differentiated by environment. Whether rules take effect depends on the mode of the Agent's environment — Agents in `stub`, `record-and-stub`, or `record-all` mode intercept matches and return Mock responses; `passthrough` and `record` modes pass through to the real downstream.

```bash
# Create a rule
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
      {"name": "Success", "statusCode": 200, "body": "{\"code\":0,\"data\":[]}"}
    ]
  }'

# Roll back a rule modification
curl -X POST http://localhost:8084/__baafoo__/api/rules/{id}/undo
```

### Parameterized Rules

The same endpoint returns different responses based on different request parameters:

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
      "name": "VIP User",
      "condition": {"type": "header", "operator": "equals", "key": "X-User-Level", "value": "VIP"},
      "statusCode": 200,
      "body": "{\"id\":1,\"name\":\"Mock VIP User\",\"discount\":0.8}"
    },
    {
      "name": "Default Response",
      "statusCode": 200,
      "body": "{\"id\":1,\"name\":\"Mock User\",\"status\":\"active\"}"
    }
  ]
}
```

### Consul Service Discovery

The Agent can intercept both Consul DNS and HTTP API modes of service discovery, matching rules by service name:

```yaml
# baafoo-agent.yml
# Service name interception is enabled by default (BaafooAgent mounts DnsResolveAdvice + HttpOpenServerAdvice by default)
# Behavior is dynamically controlled at runtime by the routing table; no configuration items needed
```

Matches DNS resolution for `order-service.service.consul`, rewriting the address to Baafoo Server's stub address.

### Scene Set Management

Organize a group of rules into scene sets for one-click enable/disable:

```bash
# Create a scene set
curl -X POST http://localhost:8084/__baafoo__/api/scenes \
  -H 'Content-Type: application/json' \
  -d '{"name":"Payment Exception Scenario Set","description":"Payment timeout, failure, refund exceptions","itemIds":["rule-1","rule-2","rule-3"]}'
```

### Fault Injection

Rules can configure a `faultInjection` field to implement probabilistic fault injection:

```json
{
  "name": "Order Service Fault Injection",
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

**Supported Fault Types**:

| Fault Type | Applicable Protocol | Description |
|:---------|:---------|:-----|
| `HTTP_ERROR` | HTTP | Return a specified HTTP error status code |
| `DELAY` | HTTP | Delay response by specified milliseconds |
| `CONNECTION_RESET` | HTTP | Directly close the connection (RST) |
| `READ_TIMEOUT` | HTTP | Read timeout |
| `KAFKA_NOT_LEADER_FOR_PARTITION` | Kafka | Simulate non-leader partition error |
| `KAFKA_OFFSET_OUT_OF_RANGE` | Kafka | Simulate offset out-of-range error |
| `KAFKA_PRODUCE_THROTTLE` | Kafka | Simulate producer throttling |
| `KAFKA_DELAY` | Kafka | Delay Kafka response |
| `KAFKA_CONNECTION_RESET` | Kafka | Reset Kafka connection |

### Chaos Engineering

Chaos Engineering allows organizing multiple fault injection rules into named Profiles for batch activation/deactivation:

```bash
# Activate a Chaos Profile
curl -X POST http://localhost:8084/__baafoo__/api/chaos/profiles/activate \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -d '{"profileName": "payment-failure-scenario"}'

# View all Profile statuses
curl http://localhost:8084/__baafoo__/api/chaos/profiles/status \
  -H 'Authorization: Bearer <token>'

# Emergency stop all active Chaos rules
curl -X POST http://localhost:8084/__baafoo__/api/chaos/emergency-stop \
  -H 'Authorization: Bearer <token>'
```

### Authentication & User Management

#### Login to Obtain JWT Token

```bash
curl -X POST http://localhost:8084/__baafoo__/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"your-password","expiresIn":3600}'
```

#### Access API with Token or API Key

```bash
# JWT Token
curl http://localhost:8084/__baafoo__/api/rules \
  -H 'Authorization: Bearer eyJhbGciOi...'

# API Key
curl http://localhost:8084/__baafoo__/api/rules \
  -H 'X-Api-Key: dev-key-001'
```

#### User Management

```bash
# Create user
curl -X POST http://localhost:8084/__baafoo__/api/users \
  -H 'Authorization: Bearer <admin-token>' \
  -H 'Content-Type: application/json' \
  -d '{"username":"tester1","password":"secure-pass","role":"tester","displayName":"Tester One"}'

# Generate API Key
curl -X POST http://localhost:8084/__baafoo__/api/users/tester1/api-key \
  -H 'Authorization: Bearer <admin-token>'

# CSV batch import users
curl -X POST http://localhost:8084/__baafoo__/api/users/import \
  -H 'Authorization: Bearer <admin-token>' \
  -H 'Content-Type: text/csv' \
  --data-binary @users.csv
```

CSV format: `username,password,role,displayName,email`

### OpenAPI Import

Auto-generate Mock rules from OpenAPI 3.x specifications:

```bash
curl -X POST http://localhost:8084/__baafoo__/api/rules/import-openapi \
  -H 'Content-Type: application/json' \
  -d '{"spec": "https://petstore3.swagger.io/api/v3/openapi.json"}'
```

Also supports directly passing OpenAPI JSON content. After import, corresponding Mock rules are automatically generated for each Path.

### HAR Export

Export request logs in HAR (HTTP Archive) format, importable directly into browser DevTools for inspection:

```bash
curl http://localhost:8084/__baafoo__/api/logs/export/har \
  -H 'Authorization: Bearer <token>' \
  -o baafoo-logs.har
```

### Multi-Language SDK

Baafoo provides Thin SDKs in Go, Python, and Node.js for Mock interception in non-Java applications. See [`sdks/PROTOCOL-v2.md`](sdks/PROTOCOL-v2.md) for detailed specifications.

| Language | Package Path | Installation |
|:-----|:-------|:---------|
| Go | `github.com/baafoo/sdk-go` | `go get github.com/baafoo/sdk-go/baafoo` |
| Python | `baafoo` | `pip install baafoo` or `pip install .` |
| Node.js | `@baafoo/sdk` | `npm install @baafoo/sdk` or `npm install ./sdks/nodejs` |

#### Quick Start (Python Example)

```python
from baafoo import BaafooClient

client = BaafooClient(
    server_url="http://localhost:8084",
    environment="default",
    api_key="dev-key-001"
)
client.start()  # Register + heartbeat + polling

# HTTP interception (auto redirects to Baafoo Server)
from baafoo import intercept_http
intercept_http(client)
```

#### Quick Start (Node.js Example)

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

#### Quick Start (Go Example)

```go
import "github.com/baafoo/sdk-go/baafoo"

client := baafoo.NewClient("http://localhost:8084", "default", baafoo.WithAPIKey("dev-key-001"))
client.Start()
baafoo.InterceptHTTP(client)
```

### Sidecar Proxy

For runtimes that don't support SDK interception (e.g. Rust, C++), use the Go Sidecar Proxy for transparent proxying:

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

The Proxy automatically registers as a Baafoo Agent, redirects traffic to the Baafoo Server as configured, and uploads recording data.

---

## REST API Reference

All API prefix: `/__baafoo__/api`. Authentication: JWT Token (`Authorization: Bearer <token>`) or API Key (`X-Api-Key: <key>`). Not required when Server authentication is disabled.

### Rule Management

| Method | Path | Description |
|:----:|:-----|:-----|
| `GET` | `/rules` | List all rules |
| `POST` | `/rules` | Create a rule |
| `GET` | `/rules/{id}` | Get rule details |
| `PUT` | `/rules/{id}` | Update a rule |
| `DELETE` | `/rules/{id}` | Delete a rule |
| `POST` | `/rules/{id}/undo` | Roll back a rule to the previous version |
| `POST` | `/rules/reset-all-state` | Reset stateful counters for all rules |
| `POST` | `/rules/import-openapi` | Import rules from OpenAPI specification |

### Rule Sets

| Method | Path | Description |
|:----:|:-----|:-----|
| `GET` | `/rulesets` | List all rule sets |
| `POST` | `/rulesets` | Create a rule set |

### Environment Management

| Method | Path | Description |
|:----:|:-----|:-----|
| `GET` | `/environments` | List all environments |
| `POST` | `/environments` | Create an environment |
| `GET` | `/environments/{id}` | Get environment details |
| `PUT` | `/environments/{id}` | Update an environment (switch mode) |
| `DELETE` | `/environments/{id}` | Delete an environment |

### Scene Set Management

| Method | Path | Description |
|:----:|:-----|:-----|
| `GET` | `/scenes` | List all scene sets |
| `POST` | `/scenes` | Create a scene set |
| `PUT` | `/scenes/{id}` | Update a scene set |
| `DELETE` | `/scenes/{id}` | Delete a scene set |

### Agent Control Channel

| Method | Path | Description |
|:----:|:-----|:-----|
| `POST` | `/agent/register` | Agent registration |
| `POST` | `/agent/heartbeat` | Agent heartbeat |
| `GET` | `/agent/poll` | Agent pulls rules and mode |
| `POST` | `/agent/recordings` | Agent uploads recording data |
| `GET` | `/agents` | List all registered Agents |

### Recording Management

| Method | Path | Description |
|:----:|:-----|:-----|
| `GET` | `/recordings` | Query recording data |
| `DELETE` | `/recordings/{id}` | Delete a recording |

### Authentication

| Method | Path | Description |
|:----:|:-----|:-----|
| `POST` | `/auth/login` | User login, returns JWT Token |
| `GET` | `/auth/me` | Get current user info and permissions |

### User Management

| Method | Path | Description |
|:----:|:-----|:-----|
| `GET` | `/users` | List all users |
| `POST` | `/users` | Create a user |
| `POST` | `/users/import` | CSV batch import users |
| `PUT` | `/users/{username}/role` | Modify user role |
| `POST` | `/users/{username}/api-key` | Generate user API Key |
| `DELETE` | `/users/{username}/api-key` | Revoke user API Key |
| `DELETE` | `/users/{username}` | Delete a user |

### Chaos Engineering

| Method | Path | Description |
|:----:|:-----|:-----|
| `POST` | `/chaos/profiles/activate` | Activate a Chaos Profile |
| `POST` | `/chaos/profiles/deactivate` | Deactivate a Chaos Profile |
| `GET` | `/chaos/profiles/status` | View all Profile statuses |
| `POST` | `/chaos/emergency-stop` | Emergency stop all active Chaos rules |

### Plugin Management

| Method | Path | Description |
|:----:|:-----|:-----|
| `GET` | `/plugins` | List all Agent plugin statuses (supports `?agentId=xxx` filter) |

### MQ Relationship Mapping

| Method | Path | Description |
|:----:|:-----|:-----|
| `GET` | `/mq-relationships` | List all MQ relationship mappings |
| `POST` | `/mq-relationships` | Create an MQ relationship mapping |
| `DELETE` | `/mq-relationships/{id}` | Delete an MQ relationship mapping |

### System

| Method | Path | Description |
|:----:|:-----|:-----|
| `GET` | `/status` | Get system status |
| `GET` | `/logs/export/har` | Export HAR-formatted request logs |

> **MCP Server endpoint** `POST /mcp` — see [MCP Server section](#mcp-server).

---

## Web Console Development

```bash
cd web

# Install dependencies
npm install

# Start dev server (port 3000, auto-proxies API to localhost:8084)
npm run dev

# Build for production
npm run build
```

Build output goes to `web/dist/`, deployable to Baafoo Server's static resource directory.

Tech Stack: Vue 3 + Element Plus + Pinia + Vue Router + ECharts + Axios + Vite

---

## Plugin Development

Baafoo Agent supports plugin extensions via the Java SPI mechanism. Implement the `com.baafoo.plugin.AgentPlugin` interface, package as a standalone JAR, and place it in the `./plugins/` directory. Plugins can override all protocols (Socket/NIO/Kafka/Pulsar/JMS/gRPC/Consul DNS/Consul API/Feign). Each `InterceptTarget` can only have one plugin registered at a time.

```java
public class MyPlugin implements AgentPlugin {
    @Override
    public String getName() { return "my-plugin"; }

    @Override
    public InterceptTarget getTarget() { return InterceptTarget.KAFKA; }

    @Override
    public void configure(Map<String, Object> config) {
        // Read plugin-level config from baafoo-agent.yml (optional)
    }

    @Override
    public void init() { /* Initialize */ }

    // New plugins should override onConnect/onRequest/onResponse phased hooks;
    // Legacy plugins only need to implement intercept() — default hooks delegate to it.
    @Override
    public ConnectAdvice onConnect(ConnectContext ctx) {
        // redirect: redirect to Mock Broker (binary protocol)
        // passthrough: pass through to real target
        // block: block the connection
    }

    @Override
    public InterceptResult intercept(PluginContext ctx) {
        // Legacy unified hook (@Deprecated; new plugins should use the phased hooks above)
    }

    @Override
    public void destroy() { /* Cleanup */ }
}
```

Register the implementation class in the JAR's `META-INF/services/com.baafoo.plugin.AgentPlugin`.  
Plugins use an isolated ClassLoader (parent=null) for complete dependency isolation from the host application. Plugin runtime is automatically monitored — after 5 consecutive `intercept()` exceptions, the plugin is auto-disabled (UNHEALTHY) and can be re-enabled via REST API.

### Plugin Configuration

Specify independent configuration per plugin in `baafoo-agent.yml`:

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

### Health Monitoring

Plugin runtime is automatically monitored. After 5 consecutive `intercept()` exceptions, the plugin is auto-disabled (UNHEALTHY). Query status via the Server REST API:

```bash
# Query plugin status for all Agents
curl http://localhost:8084/__baafoo__/api/plugins

# Query system status (includes plugin overview)
curl http://localhost:8084/__baafoo__/api/status
```

### Detailed Documentation

For complete API reference, development steps, packaging specifications, and examples, see [Plugin Developer Guide](docs/plugin-developer-guide.md).  
Example plugins: [feign](baafoo-example-plugins/feign/), [kafka-redirect](baafoo-example-plugins/kafka-redirect/), [tdmq](baafoo-example-plugins/tdmq/).

---

## Tech Stack

| Layer | Technology | Version |
|:-----|:-----|:-----|
| Bytecode Instrumentation | Byte Buddy | 1.14.14 |
| Network Layer | Netty | 4.1.100 |
| JSON | Jackson | 2.15.3 |
| YAML | SnakeYAML | 1.33 |
| Logging | SLF4J + Logback | 1.7.36 / 1.2.13 |
| Frontend Framework | Vue 3 | 3.4 |
| UI Library | Element Plus | 2.5 |
| State Management | Pinia | 2.1 |
| Charts | ECharts | 5.5 |
| Build Tools | Maven / Vite | 3.6+ / 5.1 |
| Java | JDK 8+ | 1.8 |

---

## Protocol Support Status

| Protocol | Status | Description |
|:-----|:----:|:-----|
| HTTP/1.1 | ✅ Full Support | Includes parameterized rules, condition matching, delay/error simulation |
| gRPC | ✅ Supported | gRPC over HTTP/1.1; supports service/method/path matching, grpc-status codes, protobuf message frames |
| gRPC Streaming | ✅ Supported | Server Streaming / Client Streaming / Bidi Streaming (port 10005) |
| TCP Socket | ✅ Full Support | Byte-level matching, long-connection multi-round interaction, BIO/NIO dual-mode interception |
| Consul DNS | ✅ Full Support | `.service.consul` domain interception |
| Consul HTTP API | ✅ Supported | OkHttp client interception |
| Nacos / Eureka | ✅ Supported | Service name interception mounted by default (registry-agnostic: Nacos/Consul/Eureka, etc.) |
| Feign | ✅ Supported | Feign Client interception (example plugin `baafoo-example-plugins/feign`) |
| Kafka | ✅ Supported | Metadata/Produce/Fetch core paths; supports topic condition matching, message recording |
| Pulsar / TDMQ | ✅ Supported | Includes Lookup phase simulation; supports topic condition matching, message recording |
| JMS | ✅ Supported | ActiveMQ Artemis embedded mode; supports Queue/Topic, message recording |

---

## FAQ

<details>
<summary><strong>What if the Agent doesn't connect to the Server after startup?</strong></summary>

1. Check that `serverUrl` in `baafoo-agent.yml` is correct
2. Confirm the Server is running and port 8084 is reachable
3. Check application logs for Agent registration failure errors
4. Verify that `connectionRetries` and `retryBackoffMs` configuration is reasonable

</details>

<details>
<summary><strong>What if I get IllegalAccessError on Java 9+ startup?</strong></summary>

Java 9+'s module system restricts reflective access. Add to JVM startup arguments:

```
--add-opens java.base/java.net=ALL-UNNAMED
```

Full startup command:

```bash
java --add-opens java.base/java.net=ALL-UNNAMED -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar
```

</details>

<details>
<summary><strong>How to use different rules for different environments?</strong></summary>

Baafoo's rules are globally shared, but **rule enforcement depends on the mode of the Agent's environment**. Agents in `stub`, `record-and-stub`, or `record-all` mode intercept matches and return Mock responses; `passthrough` and `record` modes pass through to the real downstream. You can organize rule groups via Scene Sets and enable/disable different scene sets in different environments. Rules can also specify effective environment lists via the `environments` field.

</details>

<details>
<summary><strong>What are the limitations of Kafka/Pulsar/JMS?</strong></summary>

Current Kafka/Pulsar/JMS Mock supports core functionality:

**Kafka Mock**:
- ✅ Metadata/Produce/Fetch core paths
- ✅ Topic condition matching rules
- ✅ Message recording (RECORD mode)
- ⚠️ `acks=all`, transactions, Consumer Group Rebalance not yet supported

**Pulsar Mock**:
- ✅ Lookup phase simulation
- ✅ Topic condition matching rules
- ✅ Message recording (RECORD mode)
- ⚠️ v1.0 focuses on non-partitioned Topic + single Producer + single Consumer + Shared subscription

**JMS Mock**:
- ✅ Queue/Topic modes
- ✅ Message recording (RECORD mode)
- ✅ ActiveMQ Artemis embedded mode

For advanced features, use Passthrough mode to pass through to the real broker.

</details>

<details>
<summary><strong>How does multi-environment isolation work?</strong></summary>

Baafoo supports multiple test environments sharing a single Server with independent mode control:

1. **Agent configures environment identifier**: `environment: ft-1`
2. **Server configures environment mode**: `stub` / `passthrough` / `record` / `record-and-stub` / `record-all`
3. **Rules bind to environments**: Rules declare effective environments via `environments` field

```bash
# Create an environment
curl -X POST http://localhost:8084/__baafoo__/api/environments \
  -H 'Content-Type: application/json' \
  -d '{"name":"ft-1","mode":"stub"}'

# Switch mode (takes effect immediately, no Agent restart needed)
curl -X PUT http://localhost:8084/__baafoo__/api/environments/ft-1 \
  -H 'Content-Type: application/json' \
  -d '{"mode":"record"}'
```

</details>

<details>
<summary><strong>How to use the recording mode?</strong></summary>

Set the environment mode to `record`, `record-and-stub`, or `record-all`:

- **record**: Pass through to real downstream while recording request/response data (only requests matched by rules)
- **record-and-stub**: Return Mock responses based on rules while recording (only requests matched by rules)
- **record-all**: Pass through all requests (including unmatched) while recording all request/response data

Recording data is stored on the Server and can be queried via API:

```bash
# View recording data
curl http://localhost:8084/__baafoo__/api/recordings

# Delete a recording
curl -X DELETE http://localhost:8084/__baafoo__/api/recordings/{id}
```

</details>

<details>
<summary><strong>How to enable database persistence?</strong></summary>

The default uses an embedded H2 database with data stored in the `dataDir` directory. To switch to PostgreSQL:

```yaml
database:
  type: "postgresql"
  url: "jdbc:postgresql://db-host:5432/baafoo"
  username: "baafoo"
  password: "secret"
```

DDL is auto-created on Server startup. No manual table creation needed.

</details>

<details>
<summary><strong>How to configure authentication?</strong></summary>

Authentication is enabled by default but uses a random JWT key. For production, configure a fixed key:

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

For development environments, set `localBypass: true` to skip local request authentication, or set `enabled: false` to completely disable authentication.

</details>

<details>
<summary><strong>How are unmatched requests handled?</strong></summary>

Determined by the Server configuration `unmatchedDefault`:

- `passthrough` (default): Pass through to the real downstream service
- `404`: Return a 404 error

In `record-all` mode, unmatched requests are also passed through and recorded.

</details>

<details>
<summary><strong>Kafka/Pulsar clients can't connect in Docker environment?</strong></summary>

Docker NAT causes the IP that the container listens on to differ from the IP the client connects to. Set `messagingAdvertisedHost` to a client-reachable hostname:

```yaml
messagingAdvertisedHost: "localhost"
```

The Server will use this address in Kafka Metadata and Pulsar LOOKUP responses.

</details>

<details>
<summary><strong>Where to place plugin JARs?</strong></summary>

Place plugin JARs in the `./plugins/` directory under the application working directory. The Agent auto-scans this directory on startup, discovering and loading plugins that implement the `AgentPlugin` interface via the SPI mechanism. Each plugin uses an isolated ClassLoader (parent=null) to avoid dependency conflicts with the host application.

</details>

---

## MCP Server

Baafoo provides an [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) Server that allows AI Agents to directly manage Mock rules, environments, scene sets, and more.

### Endpoint

All Management API endpoints are accessible via MCP.

```
POST http://<host>:8084/__baafoo__/api/mcp
```

### Authentication

Same as Management API: JWT Token or API Key. See [Authentication Configuration](#authentication-configuration).

### Supported Tools

| Category | Tools | Description |
|------|--------|------|
| Rules | 6 | List/view/create/update/delete/rollback Mock rules |
| Environments | 6 | List/view/create/update/delete/associate rules to environments |
| Scenes | 5 | List/view/create/update/delete scene sets |
| Recordings | 3 | List/stats/delete recordings |
| MQ Relationships | 3 | List/create/delete message queue relationship mappings |
| Agents | 2 | List/view Agent statuses |
| System | 2 | System status / export OpenAPI |

### Quick Example

```bash
curl -X POST http://localhost:8084/__baafoo__/api/mcp \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: <your-key>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'
```

### Agent Skill Package

The Agent Skill package is located at `agent-skill/baafoo-mock-manager/`, containing scripts and knowledge files that can be integrated into AI Agent workflows. It can also interact with AI Agents in real time through the Baafoo MCP Server.

## License

MIT
