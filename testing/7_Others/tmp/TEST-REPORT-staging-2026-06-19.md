# Baafoo Docker Staging 全协议集成测试报告

**测试时间**: 2026-06-19 09:53:44 ~ 09:53:51  
**测试环境**: Docker Staging (`docker-compose.yml` + `docker-compose.staging.yml`)  
**测试版本**: 1.0.0-SNAPSHOT  
**执行脚本**: [`testing/7_Others/tmp/integration-test-staging.py`](../../testing/7_Others/tmp/integration-test-staging.py)  

---

## 1. 环境状态

| 组件 | 容器名 | 端口 | 状态 |
|------|--------|------|------|
| Baafoo Server | `baafoo-server` | 8084(API/Web), 9000(HTTP), 9001(TCP), 9002(Kafka), 9003(Pulsar), 9004(JMS) | Healthy |
| PostgreSQL | `baafoo-staging-postgres` | 15432 | Healthy |
| 测试应用 A | `baafoo-app-env-a` | 9090 | Healthy |
| 测试应用 B | `baafoo-app-env-b` | 9091 | Healthy |

---

## 2. 测试规则

- 基础规则：16 条来自 [`testing/2_IntegrationTest/rules/all-protocols-rules.json`](../../testing/2_IntegrationTest/rules/all-protocols-rules.json)，通过 `testing/7_Others/tmp/register-rules.py` 注册。
- 动态规则：脚本为从宿主机直接访问 stub server 创建了 6 条高优先级规则，覆盖 HTTP 方法、延迟、错误码、Faker、Stateful Mock、故障注入。
- 测试结束时规则总数：**26** 条。

---

## 3. 测试结果总览

| 类别 | 用例数 | 通过 | 失败 | 通过率 |
|------|--------|------|------|--------|
| 基础/前端 | 2 | 2 | 0 | 100% |
| HTTP 协议 | 7 | 7 | 0 | 100% |
| TCP 协议 | 1 | 1 | 0 | 100% |
| Kafka 协议 | 2 | 2 | 0 | 100% |
| Pulsar 协议 | 1 | 1 | 0 | 100% |
| JMS 协议 | 1 | 1 | 0 | 100% |
| 扩展功能 (Faker/Stateful/Fault) | 3 | 3 | 0 | 100% |
| 环境隔离 | 1 | 1 | 0 | 100% |
| **合计** | **18** | **18** | **0** | **100%** |

---

## 4. 详细测试结果

### 4.1 基础与前端

| 用例 | 请求 | 关键断言 | 结果 |
|------|------|---------|------|
| F01 API status | `GET /__baafoo__/api/status` | 返回 `"success":true` | PASS |
| W01 Web console | `GET http://localhost:8084/` | 返回 200 且包含 HTML | PASS |

### 4.2 HTTP 协议

| 用例 | 请求 | 关键断言 | 结果 |
|------|------|---------|------|
| H01 HTTP basic stub via Agent | `GET localhost:9090/api/http/get?url=http://httpbin.org/get` | 返回 `"stubbed":true`、`mocked`、`staging-a` | PASS |
| H02 HTTP env isolation | `GET localhost:9090/9091/api/http/methods` | A 返回 `staging-a`，B 返回 `staging-b` | PASS |
| H03 HTTP GET stub | `GET localhost:9000/direct/get Host:httpbin.org` | 返回 200 + mocked body | PASS |
| H03 HTTP POST stub | `POST localhost:9000/direct/post Host:httpbin.org` | 返回 201 + mocked body | PASS |
| H03 HTTP PUT stub | `PUT localhost:9000/direct/put Host:httpbin.org` | 返回 200 + mocked body | PASS |
| H03 HTTP DELETE stub | `DELETE localhost:9000/direct/delete Host:httpbin.org` | 返回 204 | PASS |
| H04 HTTP delay | `GET localhost:9000/direct/delay Host:httpbin.org` | 返回 200，延迟 >= 400ms | PASS |
| H05 HTTP error status | `GET localhost:9000/direct/error500 Host:httpbin.org` | 返回 500 + error body | PASS |

### 4.3 TCP 协议

| 用例 | 请求 | 关键断言 | 结果 |
|------|------|---------|------|
| T01 TCP regex stub | Socket `HELLO BAFOO` -> `localhost:9001` | 响应包含 `TCP-HEX-STUB-OK` | PASS |

### 4.4 Kafka 协议（通过 Agent 拦截）

| 用例 | 请求 | 关键断言 | 结果 |
|------|------|---------|------|
| K01 Kafka topic produce | `GET localhost:9090/api/kafka/send?topic=baafoo-test-topic` | 返回 `"success":true`、`partition`、`offset` | PASS |
| K02 Kafka wildcard topic | `GET localhost:9090/api/kafka/send?topic=baafoo-wildcard` | 返回 `"success":true` | PASS |

### 4.5 Pulsar 协议（通过 Agent 拦截）

| 用例 | 请求 | 关键断言 | 结果 |
|------|------|---------|------|
| P01 Pulsar topic produce | `GET localhost:9090/api/pulsar/send?topic=persistent://public/default/baafoo-test-topic` | 返回 `"success":true`、`messageId` | PASS |

### 4.6 JMS 协议（通过 Agent 拦截）

| 用例 | 请求 | 关键断言 | 结果 |
|------|------|---------|------|
| J01 JMS queue send | `GET localhost:9090/api/jms/send?queueName=BAAFOO.TEST.QUEUE` | 返回 `"success":true`、`jmsMessageId`、`intercepted` | PASS |

### 4.7 扩展功能

| 用例 | 请求 | 关键断言 | 结果 |
|------|------|---------|------|
| E01 Faker variable rendering | `GET localhost:9000/faker Host:example.com` | 返回 JSON 包含 `uuid`、`name` | PASS |
| E02 Stateful mock requestCount | `GET localhost:9000/stateful Host:example.com` (2 次) | 第 1 次 `count=1`，第 2 次 `count="other"` | PASS |
| E03 Fault injection HTTP_ERROR | `GET localhost:9000/fault Host:example.com` | 返回 503 | PASS |

---

## 5. 结论

本次 Docker Staging 全协议集成测试 **18/18 通过，通过率 100%**。覆盖内容：

- HTTP 基础挡板、方法匹配、延迟、错误码
- Agent HTTP/Kafka/Pulsar/JMS 流量拦截
- staging-a / staging-b 环境隔离
- TCP 正则/Hex 挡板
- Faker 动态数据、Stateful Mock（requestCount）、故障注入（HTTP_ERROR 503）
- API 状态与 Web 控制台可用性

测试结论：**通过**。
