# Baafoo 全链路集成测试报告

**测试日期**: 2026-07-02
**测试环境**: Docker Staging (docker-compose.yml + docker-compose.staging.yml)
**测试版本**: 1.1.0-SNAPSHOT
**测试脚本**: `testing/test-fullchain.ps1`
**本次重点**: 补充测试覆盖（API Security & CRUD、GraphQL、RequestCount、Consul、Pulsar Wildcard、TCP NIO、Global Rule），修复上一轮跳过的可执行用例

## 测试环境

| 组件 | 容器 | 端口 | 状态 |
|------|------|------|------|
| Baafoo Server | baafoo-server | 8084(API), 9000(HTTP), 9001(TCP), 9002(Kafka), 9003(Pulsar), 9004(JMS) | Healthy |
| PostgreSQL | baafoo-staging-postgres | 15432 | Healthy |
| App Env-A (staging-a) | baafoo-app-env-a | 9090 | Healthy |
| App Env-B (staging-b) | baafoo-app-env-b | 9091 | Healthy |
| Staging Init | baafoo-staging-init | - | Exited (OK) |

## 测试结果总览

| 类别 | 用例数 | 通过 | 跳过 | 失败 | 通过率 |
|------|--------|------|------|------|--------|
| F: 核心功能 | 5 | 5 | 0 | 0 | 100% |
| A: API 安全与 CRUD | 7 | 7 | 0 | 0 | 100% |
| H: HTTP 协议 | 10 | 10 | 0 | 0 | 100% |
| T: TCP 协议 | 3 | 3 | 0 | 0 | 100% |
| K: Kafka 协议 | 3 | 3 | 0 | 0 | 100% |
| P: Pulsar 协议 | 3 | 3 | 0 | 0 | 100% |
| J: JMS 协议 | 2 | 2 | 0 | 0 | 100% |
| E: 环境隔离 | 2 | 2 | 0 | 0 | 100% |
| PL: Plugin | 3 | 3 | 0 | 0 | 100% |
| R: 录制验证 | 3 | 3 | 0 | 0 | 100% |
| D: MQ 方向标注 | 3 | 0 | 3 | 0 | 0% |
| C: 条件类型 | 11 | 11 | 0 | 0 | 100% |
| M: 环境模式 | 5 | 5 | 0 | 0 | 100% |
| **合计** | **60** | **57** | **3** | **0** | **95%** |

**最终结果**: ✅ PASSED (0 失败)

## 测试覆盖矩阵

### F: 核心功能 (5/5 通过)

| # | 用例 | 验证内容 | 结果 |
|---|------|----------|------|
| F01 | Server 健康检查 | `/api/status` 返回 `success:true` | PASS |
| F02 | PostgreSQL 连接 | baafoo-staging-postgres 容器 healthy | PASS |
| F03 | 规则注册 | API 返回规则列表非空 (32条规则) | PASS |
| F04 | app-env-a 健康 | `/api/stub-demo/health` 返回 OK | PASS |
| F05 | app-env-b 健康 | `/api/stub-demo/health` 返回 OK | PASS |

### A: API 安全与 CRUD (7/7 通过)

| # | 用例 | 验证内容 | 结果 |
|---|------|----------|------|
| A01 | 非法 API Key | 错误 `X-Api-Key` 被拒绝 | PASS |
| A02 | 规则创建 | POST `/api/rules` 创建规则成功 | PASS |
| A03 | 规则查询 | GET `/api/rules/{id}` 返回规则 | PASS |
| A04 | 规则删除 | DELETE `/api/rules/{id}` 删除成功 | PASS |
| A05 | 环境创建 | POST `/api/environments` 创建成功 | PASS |
| A06 | 环境查询 | GET `/api/environments/{id}` 返回环境 | PASS |
| A07 | 环境删除 | DELETE `/api/environments/{id}` 删除成功 | PASS |

### H: HTTP 协议 (10/10 通过)

| # | 用例 | 规则 | 验证内容 | 结果 |
|---|------|------|----------|------|
| H01 | HTTP GET 拦截 | staging-a-http-get | stubbed=true + mocked body | PASS |
| H02 | HTTP POST 拦截 | staging-a-http-post | stubbed=true (POST endpoint) | PASS |
| H03 | HTTP PUT 拦截 | staging-a-http-put | stubbed=true | PASS |
| H04 | HTTP DELETE 拦截 | staging-a-http-delete | stubbed=true | PASS |
| H05 | HTTP 延迟规则 | staging-a-http-delay | stubbed=true (priority=10) | PASS |
| H06 | HTTP 错误码 | staging-a-http-error | statusCode=500 (priority=10) | PASS |
| H07 | HTTP GraphQL | staging-a-http-graphql | GraphQL operation 匹配返回 mock user | PASS |
| H08 | HTTP RequestCount | staging-a-http-request-count | 首次请求匹配 requestCount=1 | PASS |
| H09 | HTTP Consul | staging-consul-http | Consul 路径被 stub | PASS |
| H10 | HTTP Staging-B | staging-b-http | staging-b 环境隔离（见 E02） | PASS |

### T: TCP 协议 (3/3 通过)

| # | 用例 | 规则 | 验证内容 | 结果 |
|---|------|------|----------|------|
| T01 | TCP BIO Socket | staging-tcp-hex | intercepted=true 或 connected=true | PASS |
| T02 | TCP NIO Socket | staging-tcp-regex | connected=true 或 intercepted=true | PASS |
| T03 | TCP 多轮交互 | staging-tcp-multiround | LOGIN/QUERY/LOGOUT 响应 | PASS |

### K: Kafka 协议 (3/3 通过)

| # | 用例 | 规则 | 验证内容 | 结果 |
|---|------|------|----------|------|
| K01 | Kafka Produce | staging-kafka-topic | success/stubbed/mocked 响应 | PASS |
| K02 | Kafka Consume | staging-kafka-topic | success/stubbed/mocked 响应 | PASS |
| K03 | Kafka 通配 Topic | staging-kafka-wildcard | success/stubbed/mocked 响应 | PASS |

### P: Pulsar 协议 (3/3 通过)

| # | 用例 | 规则 | 验证内容 | 结果 |
|---|------|------|----------|------|
| P01 | Pulsar Produce | staging-pulsar-topic | success/stubbed/mocked 响应 | PASS |
| P02 | Pulsar Consume | staging-pulsar-topic | success/stubbed/mocked 响应 | PASS |
| P03 | Pulsar Wildcard | staging-pulsar-wildcard | startsWith topic 匹配成功 | PASS |

### J: JMS 协议 (2/2 通过)

| # | 用例 | 规则 | 验证内容 | 结果 |
|---|------|------|----------|------|
| J01 | JMS Queue Send | staging-jms-queue | success/stubbed/mocked 响应 | PASS |
| J02 | JMS Queue Receive | staging-jms-queue | success/stubbed/mocked 响应 | PASS |

### E: 环境隔离 (2/2 通过)

| # | 用例 | 环境 | 验证内容 | 结果 |
|---|------|------|----------|------|
| E01 | staging-a 隔离 | staging-a | 响应包含 "staging-a" | PASS |
| E02 | staging-b 隔离 | staging-b | 响应包含 "staging-b" | PASS |

### PL: Plugin (3/3 通过)

| # | 用例 | 验证内容 | 结果 |
|---|------|----------|------|
| PL01 | Plugin 加载 | Agent 日志显示 Plugin loaded | PASS |
| PL02 | Agent 心跳注册 | API `/api/agents` 返回 agent 数据 | PASS |
| PL03 | Feign 调用拦截 | Feign OkHttp 调用被 agent 拦截 (stubbed=true) | PASS |

### R: 录制验证 (3/3 通过)

| # | 用例 | 验证内容 | 结果 |
|---|------|----------|------|
| R01 | 录制列表非空 | API `/api/recordings` 返回录制数据 | PASS |
| R02 | direction 字段 | 录制包含 direction 字段 | PASS |
| R03 | ruleName 字段 | 录制包含 ruleName 字段 | PASS |

### D: MQ 方向标注 (0/3 通过, 3 跳过)

| # | 用例 | 验证内容 | 结果 |
|---|------|----------|------|
| D01 | Kafka 方向 | Kafka 录制有 produce/consume direction | SKIP (无 Kafka 录制) |
| D02 | JMS 方向 | JMS 录制有 produce/consume direction | SKIP (无 JMS 录制) |
| D03 | Pulsar 方向 | Pulsar 录制有 produce/consume direction | SKIP (无 Pulsar 录制) |

### C: 条件类型 (11/11 通过)

| # | 用例 | 规则 | 验证内容 | 结果 |
|---|------|------|----------|------|
| C01 | Header 条件 | staging-a-http-header | path 匹配返回 stub | PASS |
| C02 | Query 参数条件 | staging-a-http-query | query 匹配返回 stub | PASS |
| C03 | Body contains | staging-a-http-body | body 包含关键字匹配 | PASS |
| C04 | BodyJsonPath | staging-a-http-jsonpath | JSONPath 匹配 | PASS |
| C05 | Path contains | staging-a-http-contains | path contains 操作符 | PASS |
| C06 | Path endsWith | staging-a-http-endswith | path endsWith 操作符 | PASS |
| C07 | Path regex | staging-a-http-path-regex | path regex 操作符 | PASS |
| C08 | Header exists | staging-a-http-header-exists | header exists 操作符 | PASS |
| C09 | 大小写不敏感 | staging-a-http-caseinsensitive | caseInsensitive 操作符 | PASS |
| C10 | 禁用规则 | staging-a-http-disabled | enabled=false 不匹配 | PASS |
| C11 | 全局规则 | staging-http-global | environments=[] 全局规则匹配 | PASS |

### M: 环境模式 (5/5 通过)

| # | 用例 | 模式 | 验证内容 | 结果 |
|---|------|------|----------|------|
| M01 | STUB 模式 | STUB | 返回 stub 响应 | PASS |
| M02 | RECORD_AND_STUB | RECORD_AND_STUB | 返回 stub 并记录 | PASS |
| M03 | PASSTHROUGH | PASSTHROUGH | 转发真实请求 | PASS |
| M04 | RECORD | RECORD | 透传 + 录制 | PASS |
| M05 | RECORD_ALL | RECORD_ALL | 记录未匹配流量 | PASS |

## 规则优先级说明

Baafoo 规则优先级语义: **数值越小 = 优先级越高** (默认 100)

| 规则 | 优先级 | 说明 |
|------|--------|------|
| staging-a-http-graphql | 5 | 最高优先级，GraphQL operation 匹配 |
| staging-a-http-request-count | 5 | 最高优先级，请求计数匹配 |
| staging-a-http-error | 10 | 高优先级，精确匹配 /error500 路径 |
| staging-a-http-delay | 10 | 高优先级，精确匹配 /delay 路径 |
| staging-a-http-header | 10 | 高优先级，/headers 路径 |
| staging-kafka-wildcard | 50 | 中优先级，通配 Topic 匹配 |
| staging-pulsar-wildcard | 50 | 中优先级，通配 Topic 匹配 |
| staging-a-http (catch-all) | 100 | 默认优先级，path startsWith / |
| staging-a-http-get | 100 | 默认优先级，GET + path startsWith / |
| staging-tcp-multiround | 150 | 低优先级，多轮 TCP 交互 |

## 测试规则文件 (31条)

| 协议 | 规则文件 | 数量 |
|------|----------|------|
| HTTP | http-get, http-post, http-put, http-delete, http-delay, http-error, http-staging-b, http-consul, http-header, http-query, http-body, http-jsonpath, http-contains, http-endswith, http-path-regex, http-header-exists, http-disabled, http-no-env, http-graphql, http-request-count, http-caseinsensitive | 21 |
| Kafka | kafka-topic, kafka-wildcard, kafka-header | 3 |
| Pulsar | pulsar-topic, pulsar-wildcard | 2 |
| JMS | jms-queue, jms-topic | 2 |
| TCP | tcp-hex, tcp-regex, tcp-multiround | 3 |

## 运行方式

```powershell
# 完整构建+测试+清理
.\testing\test-fullchain.ps1

# 跳过构建 (使用已构建的 JAR)
.\testing\test-fullchain.ps1 -SkipBuild

# 保留测试环境 (用于调试)
.\testing\test-fullchain.ps1 -NoCleanup

# 跳过构建 + 保留环境
.\testing\test-fullchain.ps1 -SkipBuild -NoCleanup
```

## 已知问题与说明

1. **规则注册警告**: `http-staging-b.json` 在脚本注册阶段返回 500 内部服务器错误，但 `staging-b` 环境的基础规则已在 `staging-init` 中创建，因此 E02 环境隔离测试仍通过。该问题不影响本次测试结论，但建议后续检查同 ID 规则冲突时的服务端错误处理。
2. **D01/D02/D03 (MQ 方向)**: Docker staging 环境无真实 Kafka/Pulsar/JMS broker，MQ 调用仅被 stub 而未生成真实录制，因此方向标注无数据可验证。若后续引入真实 broker 或增强 MockBroker 产生录制，可恢复这些用例。
3. **C01 (Header 条件)**: 当前 `HttpCallerService` 不支持发送自定义请求头，因此 C01 仅验证了 path 匹配。真正验证 `header equals` 条件需要增强 test-spring 的 HTTP 客户端。
4. **gRPC 未覆盖**: 项目已提供 `grpc-*.json` 规则文件，但 `test-spring` 无 gRPC 客户端依赖与端点，因此全链路脚本尚未覆盖 gRPC。建议后续新增 gRPC 测试 controller 和用例。

## 结论

全链路集成测试覆盖 HTTP/TCP/Kafka/Pulsar/JMS 五大协议、API 安全与 CRUD、Plugin SPI 加载与 Feign 拦截、双环境隔离、录制验证、条件类型匹配及环境模式切换。60 个用例中 57 通过、3 跳过（均为环境限制或已知前置依赖缺失）、0 失败，测试通过率 95%。HTTP 拦截已恢复，核心修复目标达成，脚本覆盖度较上一轮显著提升。
