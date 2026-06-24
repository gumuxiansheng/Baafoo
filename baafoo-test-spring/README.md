# Baafoo Test Spring

Baafoo 挡板系统的 Spring Boot 多协议外调测试应用。通过 REST API 触发各协议的外调请求，配合 Baafoo Agent 验证拦截和 Stub 功能是否正常工作。

## 前置条件

- JDK 8+
- Maven 3.6+
- Baafoo Server 已启动（默认端口 8084）
- Baafoo Agent 已构建（`baafoo-agent/target/baafoo-agent-1.1.0-SNAPSHOT.jar`）

## 构建

在项目根目录执行：

```bash
mvn clean package -pl baafoo-test-spring -am -DskipTests
```

构建产物为 `baafoo-test-spring/target/baafoo-test-spring-1.1.0-SNAPSHOT.jar`。

## 启动方式

### 方式一：挂载 Agent 启动（推荐）

Agent 会拦截应用的外调连接并重定向到 Server Stub 端口，用于完整验证拦截功能。

```bash
java -javaagent:baafoo-agent/target/baafoo-agent-1.1.0-SNAPSHOT.jar=config=baafoo-agent/src/main/resources/baafoo-agent.yml \
     -jar baafoo-test-spring/target/baafoo-test-spring-1.1.0-SNAPSHOT.jar
```

> **Java 9+** 需要额外添加 JVM 参数：`--add-opens java.base/java.net=ALL-UNNAMED`

### 方式二：不挂 Agent 启动

不加载 Agent，外调请求直接到达真实目标，用于对比测试：

```bash
java -jar baafoo-test-spring/target/baafoo-test-spring-1.1.0-SNAPSHOT.jar
```

### 自定义端口

默认端口为 `9090`，可通过配置修改：

```bash
java -jar baafoo-test-spring-1.1.0-SNAPSHOT.jar --server.port=8081
```

或在 `application.yml` 中修改：

```yaml
server:
  port: 8081
```

## API 接口一览

应用启动后，通过 HTTP 请求触发各协议的外调测试。所有接口返回 JSON 格式结果。

### HTTP 外调测试

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/api/http/get` | 发送 GET 请求（默认目标 `http://httpbin.org/get`） |
| `POST` | `/api/http/post` | 发送 POST 请求（默认目标 `http://httpbin.org/post`） |
| `GET` | `/api/http/methods` | 依次执行 GET/POST/PUT/DELETE 四种方法 |
| `GET` | `/api/http/health` | 健康检查 |

**参数：**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `url` | 请求目标 URL | `http://httpbin.org/get`（GET）/ `http://httpbin.org/post`（POST） |
| `body` | POST 请求体 | `{"test":"baafoo"}` |

**示例：**

```bash
# 基础 GET 请求
curl http://localhost:9090/api/http/get

# 指定目标 URL
curl "http://localhost:9090/api/http/get?url=http://api.example.com/users"

# POST 请求
curl "http://localhost:9090/api/http/post?url=http://httpbin.org/post&body={\"key\":\"value\"}"

# 全方法测试
curl http://localhost:9090/api/http/methods
```

**返回示例（Agent 拦截时）：**

```json
{
  "statusCode": 200,
  "stubbed": true,
  "ruleId": "test-http-rule",
  "body": "{\"code\":0,\"data\":[]}"
}
```

### Feign 外调测试

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/api/feign/get` | 通过 OpenFeign 发送 GET 请求 |
| `GET` | `/api/feign/post` | 通过 OpenFeign 发送 POST 请求 |
| `GET` | `/api/feign/all` | 依次执行 GET 和 POST |

**参数：**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `baseUrl` | Feign 目标基础 URL | `http://httpbin.org` |
| `body` | POST 请求体 | `{"test":"baafoo-feign"}` |

**示例：**

```bash
curl http://localhost:9090/api/feign/get
curl http://localhost:9090/api/feign/all
```

### Kafka 外调测试

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/api/kafka/send` | 向 Kafka 发送消息 |
| `GET` | `/api/kafka/all` | 发送普通消息和带 Key 消息 |

**参数：**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `bootstrapServers` | Kafka Broker 地址 | `kafka-broker:9092` |
| `topic` | 目标 Topic | `baafoo-test-topic` |
| `message` | 消息内容 | `hello-baafoo-kafka` |

**示例：**

```bash
# 使用默认参数
curl http://localhost:9090/api/kafka/send

# 指定 Broker 和 Topic
curl "http://localhost:9090/api/kafka/send?bootstrapServers=localhost:9092&topic=my-topic&message=test"
```

**返回示例：**

```json
{
  "bootstrapServers": "kafka-broker:9092",
  "topic": "baafoo-test-topic",
  "success": true,
  "partition": 0,
  "offset": 1
}
```

> Agent 拦截方式：替换 `bootstrap.servers` 为 `127.0.0.1:9002`。

### Pulsar / TDMQ 外调测试

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/api/pulsar/send` | 向 Pulsar 发送消息 |
| `GET` | `/api/pulsar/tdmq` | 通过 TDMQ 协议发送消息 |
| `GET` | `/api/pulsar/tdmq-config` | 查看 TDMQ 兼容配置 |
| `GET` | `/api/pulsar/all` | 执行 Pulsar 和 TDMQ 全部测试 |

**参数：**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `serviceUrl` | Pulsar/TDMQ 服务地址 | `pulsar://pulsar-broker:6650` |
| `topic` | 目标 Topic | `persistent://public/default/baafoo-test-topic` |
| `message` | 消息内容 | `hello-baafoo-pulsar` |

**示例：**

```bash
curl http://localhost:9090/api/pulsar/send
curl http://localhost:9090/api/pulsar/tdmq?serviceUrl=pulsar://pulsar-tdmq.dev:6650
curl http://localhost:9090/api/pulsar/tdmq-config
```

> Agent 拦截方式：替换 `serviceUrl` 为 `pulsar://127.0.0.1:9003`。
> TDMQ for Pulsar 使用与 Apache Pulsar 相同的二进制协议，因此通过 Pulsar Client 即可覆盖 TDMQ 场景。

### TCP Socket 外调测试

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/api/socket/bio` | 阻塞式 Socket（BIO）连接测试 |
| `GET` | `/api/socket/nio` | NIO SocketChannel 连接测试 |
| `GET` | `/api/socket/all` | 同时执行 BIO 和 NIO 测试 |

**参数：**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `host` | 目标主机 | `127.0.0.1` |
| `port` | 目标端口 | `9999` |

**示例：**

```bash
curl http://localhost:9090/api/socket/bio
curl "http://localhost:9090/api/socket/nio?host=127.0.0.1&port=9999"
curl http://localhost:9090/api/socket/all
```

**返回示例：**

```json
{
  "connected": true,
  "remoteAddress": "/127.0.0.1:9001",
  "intercepted": true,
  "sent": "HELLO-BAAFOO-TCP",
  "received": "stub-response"
}
```

### Stub Demo

| 方法 | 路径 | 说明 |
|:----:|:-----|:-----|
| `GET` | `/api/stub-demo/external` | 调用外部 API（`https://httpbin.org/get`） |
| `GET` | `/api/stub-demo/health` | 健康检查 |

最简单的端到端验证入口，适合快速确认 Agent 拦截是否生效。

```bash
curl http://localhost:9090/api/stub-demo/external
```

## 判断拦截是否生效

| 标识 | 含义 |
|------|------|
| HTTP 响应中 `stubbed: true` | 请求被 Agent 拦截并重定向到 Server Stub |
| HTTP 响应中 `ruleId` 非空 | 匹配到的挡板规则 ID |
| HTTP 响应头 `X-Baafoo-Stub: true` | 原始响应头中的拦截标识 |
| Socket 结果中 `intercepted: true` | TCP 连接被重定向到 Stub 端口 |
| Kafka/Pulsar 结果中 `success: true` | 消息发送成功（被 Stub 接收） |

## 典型使用流程

### 完整测试流程

```bash
# 1. 启动 Baafoo Server
java -jar baafoo-server/target/baafoo-server-1.1.0-SNAPSHOT.jar baafoo-server/src/main/resources/baafoo-server.yml

# 2. 构建 Agent 和 Test Spring
mvn clean package -pl baafoo-agent,baafoo-test-spring -am -DskipTests

# 3. 使用 Agent 启动 Test Spring
java -javaagent:baafoo-agent/target/baafoo-agent-1.1.0-SNAPSHOT.jar=config=baafoo-agent/src/main/resources/baafoo-agent.yml \
     -jar baafoo-test-spring/target/baafoo-test-spring-1.1.0-SNAPSHOT.jar

# 4. 通过 API 创建挡板规则（或使用 Web 控制台）
curl -X POST http://localhost:8084/__baafoo__/api/rules \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "test-http-rule",
    "protocol": "http",
    "host": "httpbin.org",
    "port": 80,
    "conditions": [
      {"type": "method", "operator": "equals", "value": "GET"},
      {"type": "path", "operator": "startsWith", "value": "/get"}
    ],
    "responses": [
      {"name": "默认响应", "statusCode": 200, "body": "{\"stubbed\":true}"}
    ]
  }'

# 5. 触发测试请求
curl http://localhost:9090/api/http/get
curl http://localhost:9090/api/feign/get
curl http://localhost:9090/api/socket/all
```

### 快速验证

```bash
# 仅验证 HTTP 拦截
curl http://localhost:9090/api/stub-demo/external

# 验证全协议
curl http://localhost:9090/api/http/methods
curl http://localhost:9090/api/feign/all
curl http://localhost:9090/api/kafka/all
curl http://localhost:9090/api/pulsar/all
curl http://localhost:9090/api/socket/all
```

## 运行测试

```bash
mvn test -pl baafoo-test-spring
```

测试使用随机端口（`server.port=0`），无需手动配置。

## 项目结构

```
baafoo-test-spring/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/baafoo/testspring/
    │   │   ├── BaafooTestSpringApplication.java   # Spring Boot 启动类
    │   │   ├── controller/
    │   │   │   ├── HttpCallerController.java      # HTTP 外调测试
    │   │   │   ├── FeignCallerController.java     # Feign 外调测试
    │   │   │   ├── KafkaCallerController.java     # Kafka 外调测试
    │   │   │   ├── PulsarCallerController.java    # Pulsar/TDMQ 外调测试
    │   │   │   ├── SocketCallerController.java    # TCP Socket 外调测试
    │   │   │   └── StubDemoController.java        # 简单 Stub 演示
    │   │   └── service/
    │   │       ├── HttpCallerService.java         # HttpURLConnection 调用
    │   │       ├── FeignCallerService.java        # OpenFeign + OkHttp 调用
    │   │       ├── KafkaCallerService.java        # Kafka Producer 调用
    │   │       ├── PulsarCallerService.java       # Pulsar Client 调用
    │   │       ├── TdmqCallerService.java         # TDMQ 兼容调用
    │   │       ├── SocketCallerService.java       # BIO/NIO Socket 调用
    │   │       └── ExternalApiClient.java         # 简单外部 API 调用
    │   └── resources/
    │       ├── application.yml                    # 应用配置（端口 9090）
    │       └── logback.xml                        # 日志配置
    └── test/
        ├── java/com/baafoo/testspring/
        │   └── BaafooTestSpringApplicationTests.java  # 集成测试
        └── resources/
            └── application.yml                    # 测试配置（随机端口）
```

## 技术栈

| 依赖 | 版本 | 用途 |
|:-----|:-----|:-----|
| Spring Boot | 2.7.x | Web 框架 |
| OkHttp | 3.14.9 | Feign 底层 HTTP 客户端 |
| OpenFeign | 11.10 | 声明式 HTTP 客户端 |
| Kafka Clients | 3.4.1 | Kafka 协议测试 |
| Pulsar Client | 2.10.4 | Pulsar/TDMQ 协议测试 |
| Jackson | 2.15.3 | JSON 序列化 |
