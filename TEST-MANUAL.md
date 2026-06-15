# Baafoo 全协议测试手册

**版本**: 1.0
**日期**: 2026-06-15
**项目**: Baafoo 挡板系统
**环境**: Docker Staging (生产环境模拟)

---

## 目录

1. [概述](#1-概述)
2. [测试架构](#2-测试架构)
3. [前置条件](#3-前置条件)
4. [测试用例矩阵](#4-测试用例矩阵)
5. [执行步骤](#5-执行步骤)
6. [验收标准](#6-验收标准)
7. [故障排查](#7-故障排查)

---

## 1. 概述

### 1.1 测试目标

本手册用于对 Baafoo 挡板系统进行完整的生产环境模拟测试，覆盖：
- 全协议支持验证 (HTTP/TCP/Kafka/Pulsar/JMS)
- 前后端集成测试
- Agent 流量拦截验证
- 多环境隔离测试
- 录制回放功能测试

### 1.2 支持的协议

| 协议 | 端口 | Handler | Agent拦截点 |
|------|------|---------|-----------|
| HTTP | 9000 | `HttpStubHandler` | OkHttp/Feign/URLConnection |
| TCP | 9001 | `TcpStubHandler` | `NioSocketConnectAdvice` |
| Kafka | 9002 | `KafkaMockBroker` | `KafkaConsumerAdvice`/`KafkaProducerAdvice` |
| Pulsar | 9003 | `PulsarMockBroker` | `PulsarClientAdvice` |
| JMS | 9004 | `JmsMockBroker` | `JmsConnectionFactoryAdvice` |

---

## 2. 测试架构

### 2.1 Docker 网络拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│  baafoo-staging-net (bridge)                                     │
│                                                                 │
│  ┌──────────────┐     ┌──────────────────────────────────────┐ │
│  │   postgres   │     │         server (挡板核心)              │ │
│  │   :5432      │◄────│  :8084 API + Web Console             │ │
│  │  (数据存储)   │     │  :9000 HTTP Mock                     │ │
│  └──────────────┘     │  :9001 TCP Mock                     │ │
│                       │  :9002 Kafka Mock                   │ │
│  ┌──────────────┐     │  :9003 Pulsar Mock                  │ │
│  │  app-env-a   │◄────│  :9004 JMS Mock                      │ │
│  │  :9090       │     └──────────────────────────────────────┘ │
│  │  + Agent    │                                                │
│  └──────────────┘     ┌──────────────────────────────────────┐ │
│                       │         app-env-b (副本)              │ │
│  ┌──────────────┐     │  :9091 + Agent                      │ │
│  │  cli-env-a   │     └──────────────────────────────────────┘ │
│  │  (交互式CLI)  │                                               │
│  └──────────────┘                                               │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 组件说明

| 组件 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| `server` | `baafoo-server` | 8084,9000-9004 | 挡板服务核心 |
| `postgres` | postgres:15-alpine | 5432 | PostgreSQL 15 |
| `app-env-a` | `baafoo-test-spring` | 9090 | Spring Boot + Agent |
| `app-env-b` | `baafoo-test-spring` | 9091 | Spring Boot + Agent |
| `cli-env-a` | `baafoo-test-app` | - | 交互式CLI测试 |

### 2.3 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `BAAFOO_HTTP_PORT` | 8084 | API服务端口 |
| `BAAFOO_DB_TYPE` | h2 | 数据库类型 (h2/postgresql) |
| `BAAFOO_DB_URL` | jdbc:postgresql://postgres:5432/baafoo | 数据库连接 |
| `BAAFOO_SERVER_HOST` | server | 挡板服务主机名 |
| `BAAFOO_ENV` | staging-a | 环境标识 |
| `JAVA_OPTS` | -Xms256m -Xmx512m | JVM参数 |

---

## 3. 前置条件

### 3.1 系统要求

- Docker Engine 20.10+
- Docker Compose 2.0+
- Maven 3.8+ (构建用)
- JDK 8+ (运行用)

### 3.2 构建步骤

```bash
# 在项目根目录执行
mvnw clean package -DskipTests
```

### 3.3 目录结构要求

```
Baafoo/
├── baafoo-server/target/
│   └── baafoo-server-*.jar
├── baafoo-agent/target/
│   └── baafoo-agent-*.jar
├── baafoo-test-spring/target/
│   └── baafoo-test-spring-*.jar
├── baafoo-test-app/target/
│   └── baafoo-test-app-*.jar
├── deploy/staging/
│   ├── baafoo-agent-env-a.yml
│   ├── baafoo-agent-env-b.yml
│   └── baafoo-server.yml
├── docker-compose.yml
├── docker-compose.staging.yml
└── Dockerfile
```

---

## 4. 测试用例矩阵

### 4.1 核心功能测试

| 用例ID | 模块 | 测试项 | 预期结果 | 优先级 |
|--------|------|--------|---------|--------|
| F01 | Server | 服务启动 | 所有端口正常监听 | P0 |
| F02 | Server | API健康检查 | `/__baafoo__/api/status` 返回 UP | P0 |
| F03 | Database | PostgreSQL连接 | 数据持久化正常 | P1 |
| F04 | Auth | API Key认证 | 正确Key通过，错误Key拒绝 | P1 |

### 4.2 HTTP协议测试

| 用例ID | 测试项 | 请求 | 预期响应 | 优先级 |
|--------|--------|------|---------|--------|
| H01 | 基础stub响应 | GET /__baafoo__/api/stub-demo/http-call | `{"mocked":true,"env":"staging-a"}` | P0 |
| H02 | Header匹配 | GET with `X-Custom: test` | 匹配Header规则 | P1 |
| H03 | Query参数匹配 | GET /path?foo=bar | 匹配Query规则 | P1 |
| H04 | POST Body匹配 | POST /api/order `{"id":1}` | 匹配Body规则 | P1 |
| H05 | 404未匹配 | GET /unknown | 404响应 | P1 |
| H06 | 响应延迟 | 规则设置delayMs: 1000 | 实际延迟≥1000ms | P2 |

### 4.3 TCP协议测试

| 用例ID | 测试项 | 输入 | 预期响应 | 优先级 |
|--------|--------|------|---------|--------|
| T01 | 基础连接 | Connect :9001 | 连接建立成功 | P0 |
| T02 | Hex发送 | `0x01 0x02 0x03` | 匹配规则返回 | P1 |
| T03 | Text发送 | `Hello World` | 匹配规则返回 | P1 |
| T04 | 长连接保持 | 多次发送 | 连接不中断 | P2 |

### 4.4 Kafka协议测试

| 用例ID | 测试项 | 操作 | 预期结果 | 优先级 |
|--------|--------|------|---------|--------|
| K01 | Metadata API | 获取Topic元数据 | 返回分区信息 | P0 |
| K02 | Produce | 发送消息 | 返回offset | P0 |
| K03 | Fetch | 消费消息 | 消息内容一致 | P0 |
| K04 | 多Topic | 操作多个Topic | 隔离正常 | P1 |

### 4.5 Pulsar协议测试

| 用例ID | 测试项 | 操作 | 预期结果 | 优先级 |
|--------|--------|------|---------|--------|
| P01 | Producer | 发送消息 | 成功ACK | P0 |
| P02 | Consumer | 订阅消费 | 消息不丢失 | P0 |
| P03 | Shared模式 | 多消费者 | 负载均衡 | P1 |

### 4.6 JMS协议测试

| 用例ID | 测试项 | 操作 | 预期结果 | 优先级 |
|--------|--------|------|---------|--------|
| J01 | Queue发送 | send to Queue | 消息入队 | P0 |
| J02 | Queue消费 | receive from Queue | 消息出队 | P0 |
| J03 | Topic发布 | publish to Topic | 订阅者收到 | P1 |

### 4.7 Agent拦截测试

| 用例ID | 测试项 | 验证方式 | 预期结果 | 优先级 |
|--------|--------|---------|---------|--------|
| A01 | HTTP拦截 | app-env-a调用外部HTTP | 被挡板拦截 | P0 |
| A02 | Kafka拦截 | app-env-a发送Kafka | 被挡板拦截 | P0 |
| A03 | 环境隔离 | app-env-a vs app-env-b | 规则隔离 | P0 |
| A04 | 心跳注册 | Agent启动 | 注册到Server | P1 |

### 4.8 前端集成测试

| 用例ID | 测试项 | 操作 | 预期结果 | 优先级 |
|--------|--------|------|---------|--------|
| W01 | Web控制台 | 访问 http://localhost:8084 | 登录页面 | P0 |
| W02 | 规则列表 | 查看规则 | 显示所有规则 | P0 |
| W03 | 规则创建 | 新增规则 | 创建成功 | P1 |
| W04 | 环境切换 | 切换模式 | 模式变更 | P1 |
| W05 | 录制查看 | 查看录制 | 显示录制列表 | P1 |

---

## 5. 执行步骤

### 5.1 第一阶段：环境准备

```bash
# 步骤1: 清理旧环境
docker compose -f docker-compose.yml -f docker-compose.staging.yml down -v

# 步骤2: 构建项目
mvnw clean package -DskipTests

# 步骤3: 启动Staging环境
docker compose -f docker-compose.yml -f docker-compose.staging.yml up --build -d

# 步骤4: 等待服务就绪 (约30秒)
sleep 30

# 步骤5: 验证服务状态
docker compose -f docker-compose.yml -f docker-compose.staging.yml ps
```

### 5.2 第二阶段：基础验证

```bash
# 验证1: Server API健康检查
curl -s http://localhost:8084/__baafoo__/api/status
# 预期: {"status":"UP"...}

# 验证2: app-env-a健康检查
curl -s http://localhost:9090/api/stub-demo/health
# 预期: Spring Boot响应

# 验证3: app-env-b健康检查
curl -s http://localhost:9091/api/stub-demo/health
# 预期: Spring Boot响应
```

### 5.3 第三阶段：协议测试

#### HTTP协议测试
```bash
# H01: 基础stub响应
curl -s http://localhost:9090/api/stub-demo/http-call
# 预期: {"mocked":true,"env":"staging-a","protocol":"http"}

curl -s http://localhost:9091/api/stub-demo/http-call
# 预期: {"mocked":true,"env":"staging-b","protocol":"http"}
```

#### TCP协议测试
```bash
# 使用netcat测试TCP端口
echo -n "test" | nc localhost 9001
```

#### Kafka协议测试
```bash
# 检查Kafka端口是否监听
telnet localhost 9002
```

### 5.4 第四阶段：前后端测试

```bash
# W01: Web控制台
open http://localhost:8084

# W02: API规则列表
curl -s http://localhost:8084/__baafoo__/api/rules | jq '.'
```

### 5.5 第五阶段：生产场景模拟

#### 场景1: 多环境隔离验证
```bash
# 同时请求两个环境
curl -s http://localhost:9090/api/stub-demo/http-call &
curl -s http://localhost:9091/api/stub-demo/http-call &
wait
```

#### 场景2: 录制回放验证
```bash
# 查看录制列表
curl -s http://localhost:8084/__baafoo__/api/recordings | jq '.'
```

---

## 6. 验收标准

### 6.1 通过标准

| 类别 | 通过率 | 说明 |
|------|--------|------|
| P0用例 | 100% | 核心功能必须全部通过 |
| P1用例 | ≥90% | 重要功能允许少量失败 |
| P2用例 | ≥80% | 辅助功能允许部分失败 |

### 6.2 测试报告格式

```
测试报告
================================================================
测试时间: YYYY-MM-DD HH:mm:ss
测试环境: Docker Staging
执行人员: [执行者]
================================================================

一、执行摘要
   总用例数: XX
   通过数: XX
   失败数: XX
   通过率: XX%

二、P0用例结果 (必须100%通过)
   [ ] F01: Server启动 - PASS
   [ ] F02: API健康检查 - PASS
   ...

三、P1用例结果
   ...

四、P2用例结果
   ...

五、问题记录
   [问题ID] 描述 - 严重程度 - 状态

================================================================
测试结论: [通过/不通过]
================================================================
```

---

## 7. 故障排查

### 7.1 常见问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| Server启动失败 | 端口被占用 | `docker compose down` 后重试 |
| Agent未注册 | 网络隔离 | 检查docker网络配置 |
| PostgreSQL连接失败 | 等待时间不足 | 增加start_period时间 |
| 测试超时 | 服务未就绪 | 增加healthcheck等待时间 |

### 7.2 日志查看

```bash
# 查看Server日志
docker compose -f docker-compose.yml -f docker-compose.staging.yml logs server

# 查看app-env-a日志
docker compose -f docker-compose.yml -f docker-compose.staging.yml logs app-env-a

# 实时跟踪日志
docker compose -f docker-compose.yml -f docker-compose.staging.yml logs -f
```

### 7.3 网络诊断

```bash
# 检查容器网络
docker network inspect baafoo-staging-net

# 容器内网络测试
docker exec baafoo-app-env-a ping server
docker exec baafoo-app-env-a curl http://server:8084/__baafoo__/api/status
```

---

## 附录

### A. API Endpoints

| 端点 | 方法 | 说明 |
|------|------|------|
| `/__baafoo__/api/status` | GET | 服务状态 |
| `/__baafoo__/api/rules` | GET/POST | 规则管理 |
| `/__baafoo__/api/rules/{id}` | GET/PUT/DELETE | 单个规则 |
| `/__baafoo__/api/environments` | GET/POST | 环境管理 |
| `/__baafoo__/api/environments/{name}/mode` | PUT | 切换模式 |
| `/__baafoo__/api/recordings` | GET | 录制列表 |
| `/__baafoo__/api/agents/heartbeat` | POST | Agent心跳 |

### B. 环境模式

| 模式 | 说明 |
|------|------|
| `stub` | 仅返回挡板响应 |
| `passthrough` | 透传到真实服务 |
| `record` | 录制请求并透传 |
| `record_and_stub` | 录制并返回挡板响应 |

### C. 联系方式

- 项目主页: https://github.com/baafoo/baafoo
- 问题反馈: https://github.com/baafoo/baafoo/issues
