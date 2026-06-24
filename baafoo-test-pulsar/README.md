# Baafoo Test Pulsar

Pulsar **2.7.4** 客户端专项测试应用。结构与 `baafoo-test-spring` 对齐，但将 `pulsar-client` 硬锁定到 **2.7.4**——即腾讯 TDMQ for Pulsar 所基于的 Pulsar 版本，用来验证 Agent 与 `baafoo-example-plugins/tdmq` 在 2.7.4 线协议下的拦截/重定向是否正确。

## 为什么需要这个模块

| 模块 | pulsar-client 版本 | 覆盖的线协议 |
|:-----|:-------------------|:-------------|
| `baafoo-test-spring` | 2.10.4 | Pulsar 2.10.x |
| `baafoo-test-pulsar` | **2.7.4** | **Pulsar 2.7.4（TDMQ for Pulsar 基线）** |

`baafoo-example-plugins/tdmq` 自称 "TDMQ / Pulsar 2.7.4 protocol-adapter" 并把非本地 broker 重定向到 `localhost:9005`，但仓库内此前从未用真正的 2.7.4 client 跑过端到端验证。本模块补齐这一空缺。

## 前置条件

- JDK 8+
- Maven 3.6+
- Baafoo Server 已启动（默认端口 8084）
- Baafoo Agent 已构建（`baafoo-agent/target/baafoo-agent-1.1.0-SNAPSHOT.jar`）

## 构建

在项目根目录执行：

```bash
mvnw clean package -pl baafoo-test-pulsar -am -DskipTests
```

产物：`baafoo-test-pulsar/target/baafoo-test-pulsar-1.1.0-SNAPSHOT.jar`

## 启动

### 方式一：挂载 Agent（推荐）

```bash
java -javaagent:baafoo-agent/target/baafoo-agent-1.1.0-SNAPSHOT.jar=config=baafoo-agent/src/main/resources/baafoo-agent.yml \
     -jar baafoo-test-pulsar/target/baafoo-test-pulsar-1.1.0-SNAPSHOT.jar
```

> Java 9+ 需追加：`--add-opens java.base/java.net=ALL-UNNAMED`

Agent 会拦截 `ClientBuilder.serviceUrl(...)`，把 `pulsar://pulsar-broker:6650` 重写到 `pulsar://<host>:9003`（内置 PulsarMockBroker）。若 `plugins/` 中有 TDMQ 插件 JAR，则非本地 broker 会被进一步重定向到 `localhost:9005`。

### 方式二：不挂 Agent

```bash
java -jar baafoo-test-pulsar/target/baafoo-test-pulsar-1.1.0-SNAPSHOT.jar
```

外调直连真实目标，用于对比测试。

默认端口 `9092`，可通过 `--server.port=xxxx` 覆盖（避开 Server 的 9002、test-spring 的 9090）。

## API 接口

所有接口前缀 `/api/pulsar274`，返回 JSON。

| 方法 | 路径 | 说明 | 默认 serviceUrl / topic |
|:----:|:-----|:-----|:-----|
| `GET` | `/send` | STRING Producer 发送，返回 messageId | `pulsar://pulsar-broker:6650` / `persistent://public/default/baafoo-test-topic` |
| `GET` | `/consume` | STRING Consumer 接收 + ack | 同上 |
| `GET` | `/json` | `Schema.JSON(SamplePojo)` 发送，验证 2.7.4 JSON-Schema 帧 | 同上 |
| `GET` | `/batch` | `enableBatching(true)` + `sendAsync` + `flush`，验证批量帧（默认 n=3） | 同上 |
| `GET` | `/info` | 返回 pulsar-client 运行时版本与重定向目标说明 | — |
| `GET` | `/all` | 聚合执行 send→consume→json→batch→info | — |

**参数：**

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `serviceUrl` | Pulsar/TDMQ 服务地址 | `pulsar://pulsar-broker:6650` |
| `topic` | 目标 Topic | `persistent://public/default/baafoo-test-topic` |
| `message` | 消息内容（`/send`） | `hello-baafoo-pulsar-2.7.4` |
| `name` / `value` | JSON Payload 字段（`/json`） | `baafoo` / `42` |
| `count` | 批量条数（`/batch`，1–1000） | `3` |

**示例：**

```bash
# 基础发送
curl http://localhost:9092/api/pulsar274/send

# 查看运行时 client 版本（应为 2.7.4）
curl http://localhost:9092/api/pulsar274/info

# 全量冒烟
curl http://localhost:9092/api/pulsar274/all
```

## 返回示例（Agent 拦截命中 mock broker 时）

```json
{
  "serviceUrl": "pulsar://pulsar-broker:6650",
  "topic": "persistent://public/default/baafoo-test-topic",
  "success": true,
  "messageId": "1:0:0:-1"
}
```

> `/info` 返回示例：
> ```json
> {
>   "module": "baafoo-test-pulsar",
>   "pulsarClientVersion": "2.7.4",
>   "expectedVersion": "2.7.4",
>   "redirectTargets": {
>     "default": "pulsar://${baafoo.server-host}:9003 (PulsarMockBroker)",
>     "tdmqPlugin": "pulsar://localhost:9005 (baafoo-example-plugins/tdmq)"
>   }
> }
> ```

## 验证 Agent / TDMQ 插件

1. 启动 Server（监听 9003 PulsarMockBroker）。
2. 构建 Agent：`mvnw clean package -pl baafoo-agent -am -DskipTests`。
3. 挂 Agent 启动本模块，触发 `curl http://localhost:9092/api/pulsar274/send`。
4. 应用日志应出现：
   ```
   [Baafoo] Pulsar serviceUrl replaced: pulsar://pulsar-broker:6650 -> pulsar://<host>:9003
   ```
5. 将 TDMQ 插件 JAR（从 `baafoo-example-plugins/tdmq/target/` 构建）复制到 `plugins/` 再重启应用，触发 `/send`，日志应出现：
   ```
   [Baafoo] Pulsar plugin redirected to localhost:9005
   [Baafoo] Pulsar serviceUrl replaced: pulsar://pulsar-broker:6650 -> pulsar://localhost:9005
   ```

## 运行测试

```bash
mvnw test -pl baafoo-test-pulsar
```

测试使用随机端口（`server.port=0`），不依赖真实 broker；send/consume/json/batch 端点用不可达的 `slow://localhost:0000` 触发即时失败，断言仅校验返回结构。

## 项目结构

```
baafoo-test-pulsar/
├── pom.xml                                       # pulsar-client 锁定 2.7.4
└── src/
    ├── main/
    │   ├── java/com/baafoo/testpulsar/
    │   │   ├── BaafooTestPulsarApplication.java  # @SpringBootApplication
    │   │   ├── controller/
    │   │   │   └── Pulsar274Controller.java      # /api/pulsar274/*
    │   │   └── service/
    │   │       └── Pulsar274CallerService.java   # send / consume / json / batch
    │   └── resources/
    │       ├── application.yml                   # 端口 9092
    │       └── logback.xml
    └── test/
        ├── java/com/baafoo/testpulsar/
        │   └── BaafooTestPulsarApplicationTests.java
        └── resources/
            └── application.yml                   # 随机端口
```

## 技术栈

| 依赖 | 版本 | 用途 |
|:-----|:-----|:-----|
| Spring Boot | 2.7.x | Web 框架 |
| Pulsar Client | **2.7.4** | TDMQ for Pulsar 基线协议测试 |
| Jackson | 2.15.3 | JSON 序列化 |
