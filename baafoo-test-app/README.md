# Baafoo Test App

Baafoo 挡板系统的多协议外调测试应用。用于验证 Agent 拦截和 Server stub 功能是否正常工作。

## 前置条件

- JDK 8+
- Maven 3.6+
- Baafoo Server 已启动（默认端口 8080）
- Baafoo Agent 已构建（`baafoo-agent/target/baafoo-agent-1.0.0-SNAPSHOT.jar`）

## 构建

在项目根目录执行：

```bash
mvn clean package -pl baafoo-test-app -am -DskipTests
```

构建产物为 `baafoo-test-app/target/baafoo-test-app-1.0.0-SNAPSHOT.jar`（shade 后的 fat jar）。

## 启动方式

### 方式一：挂载 Agent 启动（推荐）

这是测试 Agent 拦截功能的完整方式。Agent 会拦截应用的外调连接并重定向到 Server stub 端口。

```bash
java -javaagent:<agent-jar-path>=config=<agent-config-path> \
     -cp baafoo-test-app/target/baafoo-test-app-1.0.0-SNAPSHOT.jar \
     com.baafoo.testapp.BaafooTestApp [server-url]
```

示例：

```bash
java -javaagent:baafoo-agent/target/baafoo-agent-1.0.0-SNAPSHOT.jar=config=baafoo-agent/src/main/resources/baafoo-agent.yml \
     -cp baafoo-test-app/target/baafoo-test-app-1.0.0-SNAPSHOT.jar \
     com.baafoo.testapp.BaafooTestApp
```

可选参数 `server-url` 默认为 `http://127.0.0.1:8080`。

### 方式二：快速非交互式测试

不需要交互输入，适合脚本化验证：

```bash
java -javaagent:<agent-jar-path>=config=<agent-config-path> \
     -cp baafoo-test-app/target/baafoo-test-app-1.0.0-SNAPSHOT.jar \
     com.baafoo.testapp.QuickTest
```

QuickTest 会依次执行以下检查并输出结果：

| 序号 | 测试项 | 说明 |
|------|--------|------|
| 1 | HTTP 调用 httpbin.org | 验证 Agent 是否拦截并重定向到 stub 端口 9000 |
| 2 | Server 管理 API | 验证 Server 是否正常运行 |
| 3 | TCP Socket 连接 | 验证 TCP 连接是否被拦截 |
| 4 | 直接访问 stub 端口 | 验证 Server stub handler 是否正确匹配请求 |

### 方式三：不挂 Agent 启动

不加载 Agent，外调请求不会被拦截。用于对比测试或验证应用本身的功能：

```bash
java -cp baafoo-test-app/target/baafoo-test-app-1.0.0-SNAPSHOT.jar \
     com.baafoo.testapp.BaafooTestApp
```

## 交互式菜单

启动 `BaafooTestApp` 后进入交互式菜单：

```
┌─────────────────────────────────────────────────┐
│  选择测试项:                                      │
│                                                   │
│  0  — 一键设置挡板规则 (Setup Rules)              │
│  1  — HTTP 外调测试                               │
│  2  — TCP Socket 外调测试                         │
│  3  — NIO Socket 外调测试                         │
│  4  — Kafka 外调测试                              │
│  5  — Pulsar 外调测试                             │
│  6  — JMS 外调测试                                │
│  7  — Consul DNS 外调测试                         │
│  8  — Consul HTTP API 外调测试                    │
│  A  — 全部运行 (Run All)                          │
│  Q  — 退出                                        │
└─────────────────────────────────────────────────┘
```

### 选项说明

#### 0 — 一键设置挡板规则

通过 Server 管理 API 自动创建所有协议的测试规则，包括：

| 规则 | 协议 | 目标 | Stub 端口 |
|------|------|------|-----------|
| test-http-rule | HTTP | httpbin.org:80 | 9000 |
| test-tcp-rule | TCP | 127.0.0.1:9999 | 9001 |
| test-kafka-rule | Kafka | kafka-broker:9092 | 9002 |
| test-pulsar-rule | Pulsar | pulsar-broker:6650 | 9003 |
| test-jms-rule | JMS | jms-broker:61616 | 9004 |
| test-consul-dns-rule | HTTP (DNS) | my-service.service.consul | 9000 |
| test-consul-http-rule | HTTP | consul-server:8500 | 9000 |

> **注意**: 选项 0 需要在 Agent 已启动并注册到 Server 之后执行，否则 Agent 的路由表不会更新。

#### 1 — HTTP 外调测试

向 httpbin.org 发送多种 HTTP 请求，验证 Agent 拦截：

- **GET** `/get` — 基础 GET 请求
- **POST** `/post` — 带 JSON body 的 POST 请求
- **PUT** `/put` — 带 JSON body 的 PUT 请求
- **DELETE** `/delete` — DELETE 请求
- **GET + Headers** `/get` — 携带自定义 Header 的请求
- **GET + Query** `/get?foo=bar&baz=qux` — 带查询参数的请求

拦截成功时响应会包含 `X-Baafoo-Stub: true` 头。

#### 2 — TCP Socket 外调测试

使用阻塞式 `java.net.Socket` 连接 127.0.0.1:9999：

- **基础连接** — 验证连接是否被重定向
- **发送/接收** — 发送文本并读取响应
- **多次发送** — 在同一连接上发送 3 条消息

#### 3 — NIO Socket 外调测试

使用 `java.nio.channels.SocketChannel` 连接 127.0.0.1:9999：

- **NIO 连接** — 验证 NIO 通道是否被拦截
- **NIO 发送/接收** — 通过 ByteBuffer 发送和接收数据

#### 4 — Kafka 外调测试

创建 `KafkaProducer` 向 `kafka-broker:9092` 发送消息：

- **发送消息** — 基础消息发送
- **发送消息+Key** — 带分区 Key 的消息
- **发送消息+Headers** — 带自定义 Header 的消息

Agent 拦截方式：替换 `bootstrap.servers` 配置为 `127.0.0.1:9002`。

#### 5 — Pulsar 外调测试

创建 `PulsarClient` 连接 `pulsar://pulsar-broker:6650`：

- **发送消息** — 基础消息发送
- **发送消息+Key** — 带 Key 的消息

Agent 拦截方式：替换 `serviceUrl` 为 `pulsar://127.0.0.1:9003`。

#### 6 — JMS 外调测试

使用 ActiveMQ Client 连接 `tcp://jms-broker:61616`：

- **发送 TextMessage** — 基础文本消息
- **发送消息+属性** — 带自定义属性的文本消息

Agent 拦截方式：TCP 层面重定向连接到 127.0.0.1:9004。

#### 7 — Consul DNS 外调测试

通过 `InetAddress.getByName/getAllByName` 解析 Consul 服务名：

- **getByName** — 解析 `my-service.service.consul`
- **getAllByName** — 获取所有解析地址

Agent 拦截方式：替换 DNS 解析结果为 127.0.0.1。

#### 8 — Consul HTTP API 外调测试

向 Consul HTTP API 发送请求：

- **GET** `/v1/health/service/my-service` — 服务健康检查
- **GET** `/v1/kv/config/baafoo` — KV 存储读取

Agent 拦截方式：TCP 层面重定向连接到 stub 端口。

#### A — 全部运行

依次执行所有协议测试，最后输出汇总：

```
══════════════════════════════════════════════════
  测试结果汇总
══════════════════════════════════════════════════
  通过: 2
    ✓ HTTP 外调测试
    ✓ TCP Socket 外调测试
  失败: 1
    ✗ Kafka 外调测试 (连接超时)
══════════════════════════════════════════════════
```

## 典型使用流程

### 首次完整测试

```bash
# 1. 启动 Baafoo Server
cd baafoo-server
mvn exec:java -Dexec.mainClass="com.baafoo.server.bootstrap.BaafooServer"

# 2. 构建 Agent 和 Test App
cd ..
mvn clean package -pl baafoo-agent,baafoo-test-app -am -DskipTests

# 3. 使用 Agent 启动 Test App（交互式）
java -javaagent:baafoo-agent/target/baafoo-agent-1.0.0-SNAPSHOT.jar=config=baafoo-agent/src/main/resources/baafoo-agent.yml \
     -cp baafoo-test-app/target/baafoo-test-app-1.0.0-SNAPSHOT.jar \
     com.baafoo.testapp.BaafooTestApp

# 4. 在交互菜单中:
#    输入 0 → 设置挡板规则
#    输入 1 → 测试 HTTP 拦截
#    输入 A → 运行全部测试
#    输入 Q → 退出
```

### 快速验证联通

```bash
# 一条命令完成 Agent + Server 联通测试
java -javaagent:baafoo-agent/target/baafoo-agent-1.0.0-SNAPSHOT.jar=config=baafoo-agent/src/main/resources/baafoo-agent.yml \
     -cp baafoo-test-app/target/baafoo-test-app-1.0.0-SNAPSHOT.jar \
     com.baafoo.testapp.QuickTest
```

预期输出（Agent 拦截成功时）：

```
[1] HTTP call to httpbin.org/get...
    Status: 200
    X-Baafoo-Stub: true
    RESULT: INTERCEPTED by Baafoo

[2] Baafoo Server management API...
    Status: 200
    RESULT: Server is UP
```

## 判断拦截是否生效

| 标识 | 含义 |
|------|------|
| `X-Baafoo-Stub: true` 响应头 | HTTP 请求被 Agent 拦截并重定向到 Server stub |
| `X-Baafoo-Rule-Id: <id>` 响应头 | 匹配到的挡板规则 ID |
| `挡板拦截: ✓ 是` | 测试应用判断连接已被重定向 |
| `Baafoo: No rule matched for ... (stub mode)` | Agent 拦截了连接但无匹配规则，stub 模式下阻断连接 |
| `挡板拦截: ✗ 否` | 连接未被拦截，直接到达真实目标 |

## 项目结构

```
baafoo-test-app/
├── pom.xml
└── src/main/java/com/baafoo/testapp/
    ├── BaafooTestApp.java       # 交互式菜单主入口
    ├── QuickTest.java           # 非交互式快速测试
    ├── RuleSetup.java           # 通过管理 API 设置挡板规则
    └── caller/
        ├── HttpCaller.java      # HTTP 协议测试
        ├── TcpCaller.java       # TCP Socket 测试
        ├── NioTcpCaller.java    # NIO Socket 测试
        ├── KafkaCaller.java     # Kafka 协议测试
        ├── PulsarCaller.java    # Pulsar 协议测试
        ├── JmsCaller.java       # JMS 协议测试
        ├── ConsulDnsCaller.java # Consul DNS 测试
        └── ConsulHttpCaller.java# Consul HTTP API 测试
```
