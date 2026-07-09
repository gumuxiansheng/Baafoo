# Baafoo 挡板系统 — 完整测试方案

> **版本**: v1.0  
> **编制日期**: 2026-07-09  
> **依据**: PRD v2.4 + 源码实际实现  
> **范围**: 单元测试 → 集成测试 → 端到端测试，覆盖全协议、全规则分支、全环境模式  
> **排除**: 已有 testing/ 目录下的测试文档（独立编制，不参考不依赖）

---

## 目录

1. [测试策略总览](#1-测试策略总览)
2. [测试环境矩阵](#2-测试环境矩阵)
3. [单元测试](#3-单元测试)
   - 3.1 [baafoo-core 单元测试](#31-baafoo-core-单元测试)
   - 3.2 [baafoo-agent 单元测试](#32-baafoo-agent-单元测试)
   - 3.3 [baafoo-server 单元测试](#33-baafoo-server-单元测试)
   - 3.4 [baafoo-plugin-api 单元测试](#34-baafoo-plugin-api-单元测试)
   - 3.5 [baafoo-cli 单元测试](#35-baafoo-cli-单元测试)
4. [集成测试](#4-集成测试)
   - 4.1 [HTTP 协议集成测试](#41-http-协议集成测试)
   - 4.2 [TCP 协议集成测试](#42-tcp-协议集成测试)
   - 4.3 [Kafka Mock Broker 集成测试](#43-kafka-mock-broker-集成测试)
   - 4.4 [Pulsar Mock Broker 集成测试](#44-pulsar-mock-broker-集成测试)
   - 4.5 [JMS Mock Broker 集成测试](#45-jms-mock-broker-集成测试)
   - 4.6 [gRPC 协议集成测试](#46-grpc-协议集成测试)
   - 4.7 [Consul 服务发现集成测试](#47-consul-服务发现集成测试)
   - 4.8 [Agent-Server 控制通道集成测试](#48-agent-server-控制通道集成测试)
   - 4.9 [环境与模式切换集成测试](#49-环境与模式切换集成测试)
   - 4.10 [录制回放集成测试](#410-录制回放集成测试)
5. [端到端测试](#5-端到端测试)
   - 5.1 [Docker Compose 多环境 E2E](#51-docker-compose-多环境-e2e)
   - 5.2 [Spring Boot 应用 E2E](#52-spring-boot-应用-e2e)
   - 5.3 [Web 控制台 E2E](#53-web-控制台-e2e)
6. [RBAC 权限测试](#6-rbac-权限测试)
7. [故障注入测试](#7-故障注入测试)
8. [边界与异常测试](#8-边界与异常测试)
9. [性能与压力测试](#9-性能与压力测试)
10. [兼容性测试](#10-兼容性测试)
11. [测试数据与规则模板](#11-测试数据与规则模板)

---

## 1. 测试策略总览

### 1.1 测试金字塔

```
                    ┌─────────┐
                    │   E2E   │  ← Docker Compose 全链路、Spring Boot 应用、Web 控制台
                   /───────────\
                  │ Integration │  ← 协议级 Agent+Server 联调、控制通道、模式切换
                 /───────────────\
                │     Unit       │  ← Core 模型/工具、Agent Advice、Server Handler
               /───────────────────\
```

### 1.2 覆盖维度

| 维度 | 覆盖范围 |
|------|---------|
| **协议** | HTTP、TCP (BIO/NIO)、Kafka、Pulsar、JMS、gRPC |
| **环境模式** | STUB、PASSTHROUGH、RECORD、RECORD_AND_STUB、RECORD_ALL |
| **规则匹配** | 精确匹配、路径参数、JSONPath、Header、Query、Body、正则、前缀 Hex、偏移量、通配符 |
| **响应模板** | `{{path.*}}`、`{{query.*}}`、`{{header.*}}`、`{{body.*}}`、`{{faker.*}}` |
| **多编码** | UTF-8、GBK、GB2312、Big5、Shift_JIS、EUC-KR、ISO-8859-1、Windows-1252 |
| **故障注入** | HTTP_ERROR、DELAY、CONNECTION_RESET、READ_TIMEOUT、KAFKA_* 系列 |
| **RBAC 角色** | admin、developer、tester、guest |
| **认证方式** | 本地免认证、静态 API Key、JWT Bearer Token |
| **并发** | 乐观锁冲突检测、多 Agent 同时注册、规则热加载竞态 |
| **JDK 版本** | 8、11、17 |

### 1.3 测试工具链

| 工具 | 用途 |
|------|------|
| JUnit 5 + Mockito | Java 单元测试 |
| Testcontainers | 集成测试容器化依赖（PostgreSQL、Kafka、Pulsar） |
| baafoo-testcontainers | Baafoo Server 自定义容器 |
| Playwright | Web 控制台 E2E |
| Docker Compose | 多环境 E2E 编排 |
| Robot Framework | 可选 API 自动化 |
| baafoo-test-app | 协议调用器（HTTP/TCP/Kafka/Pulsar/JMS/Consul） |
| baafoo-test-spring | Spring Boot 集成测试应用 |

---

## 2. 测试环境矩阵

### 2.1 环境模式 × 协议 × 匹配结果 矩阵

| 模式 | 协议 | 规则匹配 | 预期行为 |
|------|------|---------|---------|
| STUB | HTTP | 命中 | 返回 Mock 响应，不转发 |
| STUB | HTTP | 未命中 | 透传（`unmatched-default=passthrough`）或 404（`unmatched-default=404`） |
| STUB | TCP | 命中 | 返回 Mock 字节序列 |
| STUB | TCP | 未命中 | 透传 |
| STUB | Kafka | 命中 | Consumer 收到预设消息 |
| STUB | Kafka | 未命中 | 透传到真实 Kafka（或无消息） |
| STUB | Pulsar | 命中 | Lookup 返回 Mock Broker，Consumer 收到预设消息 |
| STUB | Pulsar | 未命中 | 透传 |
| STUB | JMS | 命中 | Queue/Topic 收到预设消息 |
| STUB | JMS | 未命中 | 透传 |
| STUB | gRPC | 命中 | 返回 Mock gRPC 响应 |
| STUB | gRPC | 未命中 | 返回 UNIMPLEMENTED 或透传 |
| PASSTHROUGH | * | 命中 | 直接转发真实下游，不拦截 |
| PASSTHROUGH | * | 未命中 | 直接转发真实下游 |
| RECORD | HTTP | 命中 | 转发真实下游 + 录制响应 |
| RECORD | HTTP | 未命中 | 转发真实下游，不录制 |
| RECORD | TCP | 命中 | 转发 + 录制字节流 |
| RECORD | Kafka | 命中 | 转发 + 录制消息 |
| RECORD | Pulsar | 命中 | 转发 + 录制消息 |
| RECORD | JMS | 命中 | 转发 + 录制消息 |
| RECORD_AND_STUB | HTTP | 命中 | 返回 Mock 响应 + 录制请求 |
| RECORD_AND_STUB | * | 未命中 | 转发真实下游 |
| RECORD_ALL | * | 命中 | 返回 Mock 响应 + 录制请求 |
| RECORD_ALL | * | 未命中 | 转发真实下游 + 录制为 unmatched |

### 2.2 JDK 版本 × 拦截点 矩阵

| 拦截点 | JDK 8 | JDK 11 | JDK 17 |
|--------|:-----:|:------:|:------:|
| `Socket.connect()` (BIO) | ✅ | ✅ | ✅ |
| `SocketChannelImpl.connect()` (NIO) | ✅ | ✅ | ✅ |
| `SocketChannelImpl.finishConnect()` | ✅ | ✅ | ✅ |
| `InetAddress.getByName()` | ✅ | ✅ | ✅ |
| `InetAddress.getAllByName()` | ✅ | ✅ | ✅ |
| `KafkaProducer` 构造函数 | ✅ | ✅ | ✅ |
| `KafkaConsumer` 构造函数 | ✅ | ✅ | ✅ |
| `PulsarClient$Builder` | ✅ | ✅ | ✅ |
| `ConnectionFactory.createConnection()` | ✅ | ✅ | ✅ |

---

## 3. 单元测试

### 3.1 baafoo-core 单元测试

#### 3.1.1 MatchEngine 匹配引擎

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| UT-ME-001 | 精确路径匹配 | 加载 1 条 HTTP 规则 `GET /api/users/123` | 发送 `GET /api/users/123` | 匹配成功，返回规则 ID | R-S2 AC-01 |
| UT-ME-002 | 路径参数匹配 | 规则 `GET /api/users/{id}` | 发送 `GET /api/users/456` | 匹配成功，`{{path.id}}`=456 | R-S2 AC-02 |
| UT-ME-003 | 路径正则匹配 | 规则 path regex `^/api/orders/[A-Z]{3}-\d+$` | 发送 `GET /api/orders/ABC-123` | 匹配成功 | R-S2 AC-01 |
| UT-ME-004 | Query 参数匹配 | 规则 query `type=detail` | 发送 `GET /api/users/1?type=detail` | 匹配成功 | R-S2 AC-04 |
| UT-ME-005 | Header 匹配 | 规则 header `X-User-Level=VIP` | 发送带该 Header 的请求 | 匹配成功 | R-S2 AC-05 |
| UT-ME-006 | Body JSONPath 匹配 | 规则 bodyJsonPath `$.orderType=VIP` | 发送 POST body `{"orderType":"VIP"}` | 匹配成功 | R-S2 AC-03 |
| UT-ME-007 | Body contains 匹配 | 规则 bodyContains `"payment"` | 发送 body 含 "payment" | 匹配成功 | — |
| UT-ME-008 | 多条件 AND 匹配 | 规则含 method+path+header 三个条件 | 全部满足 | 匹配成功 | — |
| UT-ME-009 | 多条件 AND 不匹配 | 同上 | 仅满足 2 个条件 | 匹配失败 | — |
| UT-ME-010 | responses 数组顺序匹配 | 规则有 3 个 response 分支（VIP→detail→default） | 依次发送 VIP、detail、普通请求 | 分别命中第 1、2、3 分支 | R-S2 AC-06 |
| UT-ME-011 | 默认兜底响应 | responses 最后一项无 condition | 发送不匹配任何条件的请求 | 命中最后一项默认响应 | R-S2 AC-06 |
| UT-ME-012 | 禁用规则跳过 | 规则 `enabled=false` | 发送匹配该规则的请求 | 不匹配，继续下一条 | R-W2 AC-04 |
| UT-ME-013 | 协议过滤 | HTTP 规则 | 发送 TCP 请求 | 不匹配 | R-A9 AC-07 |
| UT-ME-014 | 服务名匹配优先于 host:port | 同时配置服务名和 host:port 规则 | 发送请求 | 服务名规则优先命中 | R-A9 AC-04 |
| UT-ME-015 | host:port 精确匹配 | 规则 `192.168.1.100:8080` | 请求到该地址 | 匹配成功 | R-A9 AC-01 |
| UT-ME-016 | host 通配匹配 `*.dev:*` | 规则 `*.dev:*` | 请求 `api.dev:8080` | 匹配成功 | R-A9 AC-02 |
| UT-ME-017 | port=0 fallback 匹配 | 规则 `host:0` | 请求 `host:8080` | 先精确匹配失败，port=0 fallback 匹配成功 | — |
| UT-ME-018 | 未匹配返回 NO_MATCH | 空规则列表 | 发送任意请求 | 返回 `MatchResult.NO_MATCH` | R-A9 AC-05 |
| UT-ME-019 | 正则超时保护 (ReDoS) | 规则含灾难性正则 `(a+)+$` | 发送 `aaaaaaaaaaaaaaaaaaaaaa!` | 100ms 超时，返回不匹配 | — |
| UT-ME-020 | 正则缓存 LRU 驱逐 | 注入 600 个不同正则 | 发送请求 | 缓存大小不超过 512 | — |
| UT-ME-021 | 大小写敏感/不敏感 | 规则 header 匹配 `caseSensitive=false` | 发送 `x-user-level: vip` | 匹配成功 | — |
| UT-ME-022 | topic 条件匹配 (Kafka) | Kafka 规则 topic=`test-topic` | 请求 topic=`test-topic` | 匹配成功 | R-S4 AC-09 |
| UT-ME-023 | topic 通配匹配 (Pulsar) | Pulsar 规则 topic regex `persistent://.*test.*` | topic=`persistent://tenant/ns/test-topic` | 匹配成功 | R-S5 AC-09 |
| UT-ME-024 | GraphQL operationName 匹配 | 规则 graphqlOperationName=`GetUser` | body 含 `"operationName":"GetUser"` | 匹配成功 | — |
| UT-ME-025 | GraphQL operationType 匹配 | 规则 graphqlOperationType=`query` | body 含 `"query":` | 匹配成功 | — |
| UT-ME-026 | exists 操作符 | 规则 header exists `X-Trace-Id` | 请求含该 Header | 匹配成功 | — |
| UT-ME-027 | startsWith 操作符 | 规则 path startsWith `/api/v1` | 请求 `/api/v1/users` | 匹配成功 | — |
| UT-ME-028 | endsWith 操作符 | 规则 path endsWith `/health` | 请求 `/api/v1/health` | 匹配成功 | — |
| UT-ME-029 | 状态计数器匹配 | 规则含 requestCount 条件 `>=3` | 第 3 次请求 | 匹配成功，前 2 次不匹配 | R-S2 AC-13 |
| UT-ME-030 | faker 种子确定性 | 规则 `fakerSeed=42` | 相同种子两次请求 | Faker 输出序列一致 | — |

#### 3.1.2 TemplateEngine 模板引擎

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-TE-001 | `{{path.id}}` 替换 | path 参数 id=123 | 输出 `123` |
| UT-TE-002 | `{{query.type}}` 替换 | query 参数 type=detail | 输出 `detail` |
| UT-TE-003 | `{{header.X-User-Level}}` 替换 | header 值 VIP | 输出 `VIP` |
| UT-TE-004 | `{{body.orderType}}` JSONPath 提取 | body `{"orderType":"VIP"}` | 输出 `VIP` |
| UT-TE-005 | `{{faker.phone}}` 生成手机号 | 无参数 | 输出合法手机号格式 |
| UT-TE-006 | `{{faker.email}}` 生成邮箱 | 无参数 | 输出合法邮箱格式 |
| UT-TE-007 | `{{faker.name}}` 生成姓名 | 无参数 | 输出非空中文姓名 |
| UT-TE-008 | `{{faker.idCard}}` 生成身份证 | 无参数 | 输出 18 位身份证号 |
| UT-TE-009 | `{{faker.uuid}}` 生成 UUID | 无参数 | 输出合法 UUID 格式 |
| UT-TE-010 | `{{faker.timestamp}}` 生成时间戳 | 无参数 | 输出合法 ISO-8601 时间戳 |
| UT-TE-011 | 多变量混合模板 | `{{path.id}}-{{faker.uuid}}` | 两部分均正确替换 |
| UT-TE-012 | 无变量模板 | 纯文本 `hello world` | 原样输出 |
| UT-TE-013 | 未定义变量 | `{{undefined.var}}` | 保持原样或输出空 |
| UT-TE-014 | 嵌套 JSONPath | `{{body.user.address.city}}` | 正确提取嵌套值 |
| UT-TE-015 | faker 种子确定性 | 种子=42 两次调用 | 相同输出序列 |

#### 3.1.3 EnvironmentMode 枚举

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-EM-001 | `fromValue("stub")` | 解析字符串 | 返回 `STUB` |
| UT-EM-002 | `fromValue("passthrough")` | 解析字符串 | 返回 `PASSTHROUGH` |
| UT-EM-003 | `fromValue("record")` | 解析字符串 | 返回 `RECORD` |
| UT-EM-004 | `fromValue("record-and-stub")` | 解析字符串 | 返回 `RECORD_AND_STUB` |
| UT-EM-005 | `fromValue("record-all")` | 解析字符串 | 返回 `RECORD_ALL` |
| UT-EM-006 | `fromValue(null)` | null 输入 | 返回 `STUB`（默认） |
| UT-EM-007 | `fromValue("unknown")` | 未知值 | 返回 `STUB`（默认） |
| UT-EM-008 | `fromValue("STUB")` | 大写 | 返回 `STUB` |
| UT-EM-009 | `getValue()` 序列化 | 各模式 | 输出对应小写字符串 |
| UT-EM-010 | `fromValue("record_and_stub")` | 下划线变体 | 返回 `RECORD_AND_STUB` |

#### 3.1.4 Rule / RuleSet 模型

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-RM-001 | Rule 默认 environments 为空列表 | new Rule() | `environments` = `[]` |
| UT-RM-002 | Rule enabled 默认 true | new Rule() | `enabled` = true |
| UT-RM-003 | Rule priority 默认 100 | new Rule() | `priority` = 100 |
| UT-RM-004 | RuleSet 按 priority 排序 | 添加 priority 200, 50, 100 | 排序为 50, 100, 200 |
| UT-RM-005 | TCP rounds 设置/获取 | 设置 3 轮 TcpRound | `tcpRounds.size()` = 3 |
| UT-RM-006 | TCP loop 标志 | 设置 `tcpLoop=true` | `isTcpLoop()` = true |
| UT-RM-007 | fakerSeed 确定性 | 设置 seed=42 | 两次相同请求 Faker 输出一致 |
| UT-RM-008 | autoResetThreshold | 设置阈值 100 | `getAutoResetThreshold()` = 100 |

#### 3.1.5 FaultInjector 故障注入器

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-FI-001 | HTTP_ERROR 100% 触发 | probability=1.0, type=HTTP_ERROR, statusCodes=[502] | 返回 502 |
| UT-FI-002 | HTTP_ERROR 0% 触发 | probability=0.0 | 不触发，正常响应 |
| UT-FI-003 | DELAY 100% 触发 | probability=1.0, type=DELAY, delayMs=100 | 延迟 ≥ 100ms |
| UT-FI-004 | CONNECTION_RESET 100% 触发 | probability=1.0, type=CONNECTION_RESET | 连接被 RST 关闭 |
| UT-FI-005 | READ_TIMEOUT 100% 触发 | probability=1.0, type=READ_TIMEOUT | 不响应，等待客户端超时 |
| UT-FI-006 | 多故障顺序评估 | 两个故障：HTTP_ERROR(0.0) + DELAY(1.0) | 第一个不中，第二个命中 |
| UT-FI-007 | KAFKA_NOT_LEADER_FOR_PARTITION | probability=1.0 | 返回 Kafka error code 6 |
| UT-FI-008 | KAFKA_OFFSET_OUT_OF_RANGE | probability=1.0 | 返回 Kafka error code 1 |
| UT-FI-009 | KAFKA_PRODUCE_THROTTLE | probability=1.0, delayMs=50 | 延迟 ≥ 50ms |
| UT-FI-010 | 概率边界 0.5 | probability=0.5, 1000 次请求 | 触发率 ≈ 50% (±5%) |

#### 3.1.6 配置加载 (ConfigLoader)

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-CL-001 | 加载完整 Agent 配置 | 读取 `baafoo-agent.yml` | 各字段正确解析 |
| UT-CL-002 | 加载完整 Server 配置 | 读取 `baafoo-server.yml` | 端口、DB 配置正确 |
| UT-CL-003 | 配置文件不存在 | 指向不存在的路径 | 抛出明确异常或使用默认值 |
| UT-CL-004 | 环境变量覆盖 | 设置 `BAAFOO_SERVER_PORT=9999` | 配置中端口被覆盖 |
| UT-CL-005 | Agent mode 字段已移除 | 配置含 `mode: stub` | 忽略或警告，mode 由 Server 下发 |
| UT-CL-006 | environment 默认值 | 未配置 environment | 默认为 `default` |
| UT-CL-007 | heartbeat 默认值 | 未配置 heartbeat | interval=30s, timeout=90s |
| UT-CL-008 | 录制配置默认值 | 未配置 recording | retentionDays=7, maxSizeMb=500 |

#### 3.1.7 工具类

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-UT-001 | HexUtils 编解码 | encode `0x01 0x02 0xFF` → decode | 往返一致 |
| UT-UT-002 | HexUtils 空输入 | encode 空字节数组 | 返回空字符串 |
| UT-UT-003 | HexUtils 非法字符 | decode `"XY"` | 抛出 IllegalArgumentException |
| UT-UT-004 | VarintCodec 编码 0 | encode(0) | 输出 `0x00` |
| UT-UT-005 | VarintCodec 编码 300 | encode(300) | 输出 `0xAC 0x02` |
| UT-UT-006 | VarintCodec 解码往返 | encode → decode | 值一致 |
| UT-UT-007 | IdGenerator 唯一性 | 连续生成 10000 个 ID | 无重复 |
| UT-UT-008 | IdGenerator 格式 | 生成一个 ID | 符合预期格式 |
| UT-UT-009 | JsonPathUtil 提取 | `$.user.name` from `{"user":{"name":"Alice"}}` | 返回 `Alice` |
| UT-UT-010 | JsonPathUtil 不存在路径 | `$.missing` from `{}` | 返回 null |
| UT-UT-011 | JsonPathUtil 非法表达式 | `$[invalid` | 返回 null 或抛异常 |
| UT-UT-012 | NetworkUtils 本地 IP | 获取本机 IP | 返回非 null 的 IPv4 |
| UT-UT-013 | FakerProvider phone 格式 | 生成手机号 | 匹配 `^1[3-9]\d{9}$` |
| UT-UT-014 | FakerProvider email 格式 | 生成邮箱 | 匹配 `^[\w.]+@[\w.]+\.\w+$` |
| UT-UT-015 | FakerProvider idCard 格式 | 生成身份证 | 18 位，含合法校验位 |
| UT-UT-016 | StatefulCounterStore 递增 | increment 同一 ruleId 3 次 | getCount=3 |
| UT-UT-017 | StatefulCounterStore 重置 | reset 后 getCount | 返回 0 |
| UT-UT-018 | StatefulCounterStore 阈值重置 | count 达到 autoResetThreshold | 自动重置为 0 |
| UT-UT-019 | ChaosManager 概率触发 | probability=1.0 | 总是触发 |
| UT-UT-020 | OpenApiImporter 解析 | 导入合法 OpenAPI 3.0 JSON | 生成对应 HTTP 规则 |

#### 3.1.8 DefaultModeStrategy 模式策略

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|---------|
| UT-MS-001 | STUB + matched | mode=STUB, matched=true | resolve() | forward=false, sendStub=true, record=false |
| UT-MS-002 | PASSTHROUGH + matched | mode=PASSTHROUGH, matched=true | resolve() | forward=true, sendStub=false, record=false |
| UT-MS-003 | RECORD + matched | mode=RECORD, matched=true | resolve() | forward=true, sendStub=false, recordResponse=true |
| UT-MS-004 | RECORD_AND_STUB + matched | mode=RECORD_AND_STUB, matched=true | resolve() | forward=false, sendStub=true, recordRequest=true |
| UT-MS-005 | RECORD_ALL + matched | mode=RECORD_ALL, matched=true | resolve() | forward=false, sendStub=true, recordRequest=true |
| UT-MS-006 | RECORD_ALL + unmatched | mode=RECORD_ALL, matched=false | resolve() | forward=true, recordUnmatched=true |
| UT-MS-007 | STUB + unmatched | mode=STUB, matched=false | resolve() | forward=false, sendStub=false (handler 决定 404/passthrough) |
| UT-MS-008 | null mode 防御 | mode=null, matched=true | resolve() | 按 STUB 处理 |

---

### 3.2 baafoo-agent 单元测试

#### 3.2.1 BaafooAgent 生命周期

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| UT-AG-001 | premain 正常加载 | 合法配置文件 | 调用 `premain(args, inst)` | 输出 "Baafoo Agent initialized" 日志，列出激活拦截点 | R-A1 AC-01 |
| UT-AG-002 | 未配置 -javaagent | 无 Agent | 正常启动应用 | 应用行为无差异 | R-A1 AC-02 |
| UT-AG-003 | 配置文件不存在 | 指向不存在的路径 | premain | 输出 ERROR 日志 "Agent 未成功加载"，请求透传 | R-A1 AC-03 |
| UT-AG-004 | fail-open 模式 | `baafoo.agent.fail-open=true` + 配置缺失 | premain | 静默透传，无 ERROR | R-A1 AC-03 |
| UT-AG-005 | fail-closed 模式 | `baafoo.agent.fail-open=false`（默认） | premain | 输出 ERROR 日志，请求透传 | R-A1 AC-03 |
| UT-AG-006 | Bootstrap CL 注入 | premain 成功 | 检查 `appendToBootstrapClassLoaderSearch` 被调用 | Agent jar 加入 Bootstrap CL | R-A1 技术约束 |
| UT-AG-007 | AgentManifest 读取 | 合法 MANIFEST.MF | 解析 | 版本号、配置正确 | — |

#### 3.2.2 RouteTable 路由表

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-RT-001 | 添加路由规则 | addTarget("host", 8080, stubAddr) | 路由表包含该条目 |
| UT-RT-002 | 精确查找 | 查询已存在的 host:port | 返回 stubAddr |
| UT-RT-003 | 通配匹配 | 查询 `api.dev:8080` 匹配 `*.dev:*` | 返回 stubAddr |
| UT-RT-004 | 服务名查找 | 查询 "order-service" | 返回对应 stubAddr |
| UT-RT-005 | 服务名优先于 host:port | 同时存在两种匹配 | 返回服务名对应的 stubAddr |
| UT-RT-006 | 未匹配返回 null | 查询不存在的地址 | 返回 null |
| UT-RT-007 | 热加载更新 | 更新规则文件后 < 500ms | 路由表更新 | 
| UT-RT-008 | 原子引用替换 | 并发读取+写入 | 不出现半加载状态 |
| UT-RT-009 | environment 过滤 | 规则 environments=["ft-1"]，Agent 属于 ft-2 | 不匹配 |
| UT-RT-010 | environment 匹配 | 规则 environments=["ft-1"]，Agent 属于 ft-1 | 匹配 |

#### 3.2.3 ModeGates 模式门控

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|---------|
| UT-MG-001 | STUB 模式允许拦截 | mode=STUB | 检查 shouldIntercept() | 返回 true |
| UT-MG-002 | PASSTHROUGH 模式禁止拦截 | mode=PASSTHROUGH | 检查 shouldIntercept() | 返回 false |
| UT-MG-003 | RECORD 模式允许拦截 | mode=RECORD | 检查 shouldIntercept() | 返回 true |
| UT-MG-004 | RECORD_AND_STUB 允许拦截 | mode=RECORD_AND_STUB | 检查 shouldIntercept() | 返回 true |
| UT-MG-005 | RECORD_ALL 允许拦截 | mode=RECORD_ALL | 检查 shouldIntercept() | 返回 true |
| UT-MG-006 | 模式切换后即时生效 | STUB→PASSTHROUGH | 切换后 shouldIntercept() | 返回 false |

#### 3.2.4 ControlChannel 控制通道

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-CC-001 | 注册 Agent | POST /api/agent/register | 返回 agentId + 当前模式 |
| UT-CC-002 | 心跳上报 | POST /api/agent/heartbeat | Server 更新最后心跳时间 |
| UT-CC-003 | 拉取规则 | GET /api/agent/rules | 返回该环境过滤后的规则 |
| UT-CC-004 | 长轮询等待 | GET /api/agent/poll，无变更 | 阻塞直到超时或变更 |
| UT-CC-005 | 长轮询即时返回 | GET /api/agent/poll，有模式切换 | 立即返回新模式 |
| UT-CC-006 | 上传录制数据 | POST /api/agent/recordings | Server 存储成功 |
| UT-CC-007 | Server 不可用时降级 | Server 宕机 | Agent 使用本地最后已知规则 |
| UT-CC-008 | 心跳超时 90s | 90s 无心跳 | Server 标记 Agent 离线 |

#### 3.2.5 Advice 类约束测试

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-AD-001 | Bootstrap Advice 导入约束 | 扫描所有 Advice 类 | 不引用非 Bootstrap CL 的类 |
| UT-AD-002 | SocketConnectAdvice 逻辑 | 模拟 Socket.connect | 连接地址被重写 |
| UT-AD-003 | NioSocketConnectAdvice 逻辑 | 模拟 SocketChannelImpl.connect | 连接地址被重写 |
| UT-AD-004 | NioSocketFinishConnectAdvice | 模拟 finishConnect | Pulsar 等客户端正确建立连接 |
| UT-AD-005 | DnsResolveAdvice 拦截 | 模拟 InetAddress.getByName("*.service.consul") | 返回挡板地址 |
| UT-AD-006 | DnsResolveAllAdvice 拦截 | 模拟 getAllByName | 返回挡板地址数组 |
| UT-AD-007 | KafkaProducerAdvice 拦截 | 模拟 KafkaProducer 构造 | bootstrap.servers 被替换 |
| UT-AD-008 | KafkaConsumerAdvice 拦截 | 模拟 KafkaConsumer 构造 | bootstrap.servers 被替换 |
| UT-AD-009 | PulsarClientAdvice 拦截 | 模拟 PulsarClient.builder() | serviceUrl 被替换 |
| UT-AD-010 | JmsConnectionFactoryAdvice | 模拟 createConnection() | 返回 Mock Connection |
| UT-AD-011 | GrpcChannelAdvice 拦截 | 模拟 ManagedChannelBuilder | target 被替换 |
| UT-AD-012 | HttpOpenServerAdvice | 模拟 ServerSocket 构造 | 端口被重写 |
| UT-AD-013 | RecordingInputStream | 包装真实 InputStream | 读取同时录制字节 |
| UT-AD-014 | RecordingOutputStream | 包装真实 OutputStream | 写入同时录制字节 |
| UT-AD-015 | RecordingBuffer 容量限制 | 写入超过 256MB | 停止录制 + WARN 日志 |
| UT-AD-016 | SocketCloseAdvice | Socket 关闭 | 触发录制 flush |

#### 3.2.6 PluginManager 插件管理

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-PM-001 | 加载插件 | 扫描 plugin SPI | 插件初始化成功 |
| UT-PM-002 | 插件初始化失败降级 | 模拟插件异常 | 该协议降级 passthrough，其他协议不受影响 |
| UT-PM-003 | PluginClassLoader 隔离 | 加载插件类 | 与应用 ClassLoader 隔离 |
| UT-PM-004 | 插件健康检查 | 调用 health() | 返回 PluginHealth 状态 |
| UT-PM-005 | 插件 SPI 集成 | 加载 FeignPlugin | Advice 正确注册 |

---

### 3.3 baafoo-server 单元测试

#### 3.3.1 HttpStubHandler

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|---------|
| UT-HS-001 | 匹配规则返回 Mock | 存在 HTTP 规则 | 发送匹配请求 | 返回预设 status/headers/body |
| UT-HS-002 | 未匹配透传 (passthrough) | `unmatched-default=passthrough` | 发送未匹配请求 | 代理到真实下游 |
| UT-HS-003 | 未匹配返回 404 | `unmatched-default=404` | 发送未匹配请求 | 返回 404 |
| UT-HS-004 | 响应延迟 (固定) | delay=200ms | 发送请求 | 响应延迟 ≥ 200ms |
| UT-HS-005 | 响应延迟 (随机区间) | delayMin=100, delayMax=300 | 发送请求 | 延迟在 [100,300]ms |
| UT-HS-006 | 响应延迟 (正态分布) | delayMean=200, delayStd=50 | 发送 1000 请求 | 延迟均值 ≈ 200ms |
| UT-HS-007 | 模板变量替换 | body 含 `{{path.id}}` | 发送请求 | 变量被替换 |
| UT-HS-008 | Faker 函数替换 | body 含 `{{faker.phone}}` | 发送请求 | 生成合法手机号 |
| UT-HS-009 | 多编码响应 GBK | charset=GBK | 发送请求 | 响应体 GBK 编码，Content-Type 含 charset=GBK |
| UT-HS-010 | 多编码响应 Shift_JIS | charset=Shift_JIS | 发送请求 | 响应体 Shift_JIS 编码 |
| UT-HS-011 | 故障注入 HTTP_502 | fault type=HTTP_ERROR, statusCodes=[502] | 发送请求 | 返回 502 |
| UT-HS-012 | 故障注入 CONNECTION_RESET | fault type=CONNECTION_RESET, probability=1.0 | 发送请求 | 连接被 RST |
| UT-HS-013 | 故障注入 READ_TIMEOUT | fault type=READ_TIMEOUT, probability=1.0 | 发送请求 | 30s 后连接关闭 |
| UT-HS-014 | 故障注入 DELAY | fault type=DELAY, delayMs=500 | 发送请求 | 延迟 ≥ 500ms |
| UT-HS-015 | Agent 身份解析 | 请求含 Agent 标识 | AgentResolver 解析 | 正确关联 Agent + 环境 |
| UT-HS-016 | 未知 Agent 请求 | 无 Agent 标识 | 发送请求 | 按默认环境处理或拒绝 |
| UT-HS-017 | RECORD 模式透传+录制 | mode=RECORD, 规则命中 | 发送请求 | 转发到下游 + 录制响应 |
| UT-HS-018 | RECORD_AND_STUB 模式 | mode=RECORD_AND_STUB | 发送请求 | 返回 Mock + 录制请求 |
| UT-HS-019 | RECORD_ALL 未匹配录制 | mode=RECORD_ALL, 未命中 | 发送请求 | 转发 + 录制为 unmatched |
| UT-HS-020 | PassthroughProxy 异步转发 | 下游可用 | 发送请求 | 异步代理，返回真实响应 |
| UT-HS-021 | PassthroughProxy 下游不可用 | 下游不可达 | 发送请求 | 返回 502 或连接错误 |
| UT-HS-022 | 请求日志记录 | 任意请求 | 检查日志 | 包含时间戳、Agent PID、协议、匹配规则、耗时 |
| UT-HS-023 | HAR 导出 | 有 HTTP 请求日志 | 调用 HAR 导出 | 输出合法 HAR JSON |
| UT-HS-024 | 状态计数器条件 | 规则含 requestCount>=3 | 发送 3 次请求 | 第 3 次命中不同响应 |
| UT-HS-025 | 自动重置阈值 | autoResetThreshold=100 | 发送 101 次 | 计数器重置为 0 |

#### 3.3.2 TcpStubHandler

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| UT-TS-001 | 前缀 Hex 匹配 | 规则 prefixHex=`010203` | 发送 `01 02 03 ...` | 返回预设字节序列 | R-S3 AC-01 |
| UT-TS-002 | 正则匹配字节 | 规则 pattern=`^01.{1}03` | 发送匹配字节 | 返回预设响应 | R-S3 AC-02 |
| UT-TS-003 | 长连接多轮交互 | 规则有 3 轮 TcpRound | 依次发送 3 个请求 | 分别返回 round1/round2/round3 响应 | R-S3 AC-03 |
| UT-TS-004 | 多轮循环 (tcpLoop=true) | tcpLoop=true | 发送 4 个请求 | 第 4 次回到 round1 | R-S3 AC-03 |
| UT-TS-005 | 多轮不循环 (tcpLoop=false) | tcpLoop=false | 发送 4 个请求 | 第 4 次连接关闭 | R-S3 AC-03 |
| UT-TS-006 | 偏移量匹配 | offsetStart=4, offsetEnd=6, offsetHex=`0001` | 发送含偏移字段请求 | 返回成功响应 | R-S3 AC-05 |
| UT-TS-007 | 偏移量不匹配 | 同上但 offsetHex=`0002` | 发送偏移值 `0002` | 返回失败响应或不匹配 | R-S3 AC-05 |
| UT-TS-008 | 录制回放模式 | 有录制 session | 发送匹配 request bytes | 返回录制的 response bytes | R-S3 AC-04 |
| UT-TS-009 | 未匹配规则透传 | `unmatched-default=passthrough` | 发送未匹配字节 | 透传到真实下游 | — |
| UT-TS-010 | BIO 模式拦截 | Socket.connect | 发起 BIO TCP 连接 | 连接被重定向 | R-A2 AC-01 |
| UT-TS-011 | NIO 模式拦截 | SocketChannelImpl.connect | 发起 NIO TCP 连接 | 连接被重定向 | R-A3 AC-01 |
| UT-TS-012 | NIO finishConnect | SocketChannelImpl.finishConnect | 完成 NIO 连接 | 连接正确建立 | R-A3 AC-03 |

#### 3.3.3 Kafka Mock Broker

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| UT-KB-001 | Metadata API 返回 | Mock Broker 启动 | 客户端查询 Topic 元数据 | 返回 partition 信息，leader=Mock Broker | R-S4 AC-01 |
| UT-KB-002 | Produce API | 有 Kafka 规则 | Producer send() | 返回 RecordMetadata(topic,partition,offset) | R-S4 AC-02 |
| UT-KB-003 | Fetch API | 有预设消息 | Consumer poll() | 收到预设消息序列 | R-S4 AC-03 |
| UT-KB-004 | 消费完毕返回空 | 预设消息已消费完 | Consumer poll() | 返回空消息集 | R-S4 AC-03 |
| UT-KB-005 | 投递延迟 | delay=200ms | Producer send() | 延迟 ≥ 200ms | R-S4 AC-04 |
| UT-KB-006 | topic 通配订阅 | topic=`test.*` | 订阅 `test.foo` | 匹配成功 | R-S4 AC-05 |
| UT-KB-007 | 未覆盖 API 不崩溃 | 客户端调 OffsetCommit | 调用 | 返回空/默认响应，不抛异常 | R-S4 AC-06 |
| UT-KB-008 | 不支持特性明确错误 | acks=all | Producer send() | 返回明确错误响应 | R-S4 AC-08 |
| UT-KB-009 | topic 条件匹配 | 规则 conditions topic=`orders` | Producer send to `orders` | 匹配规则 | R-S4 AC-09 |
| UT-KB-010 | 消息录制 | mode=RECORD | Producer send | 录制到 `/api/recordings` | R-S4 AC-10 |
| UT-KB-011 | Kafka Client 2.8 兼容 | Client v2.8 | 全流程 | 正常工作 | R-S4 AC-07 |
| UT-KB-012 | Kafka Client 3.x 兼容 | Client v3.5 | 全流程 | 正常工作 | R-S4 AC-07 |
| UT-KB-013 | 协议版本协商 | 客户端 v12 | API 版本握手 | 返回支持的版本 | — |
| UT-KB-014 | Kafka 故障注入 NOT_LEADER | fault KAFKA_NOT_LEADER_FOR_PARTITION | Produce | 返回 error code 6 | — |
| UT-KB-015 | Kafka 故障注入 THROTTLE | fault KAFKA_PRODUCE_THROTTLE, delayMs=100 | Produce | 延迟 ≥ 100ms | — |

#### 3.3.4 Pulsar Mock Broker

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| UT-PB-001 | Lookup 返回自身 | Mock Broker 启动 | lookupTopic 请求 | 返回 `localhost:9003` | R-S5 AC-01 |
| UT-PB-002 | Producer send | 有 Pulsar 规则 | Producer send() | 返回 MessageId | R-S5 AC-02 |
| UT-PB-003 | Consumer receive | 有预设消息 | Consumer receive() | 收到预设消息 | R-S5 AC-03 |
| UT-PB-004 | tenant/ns/topic 隔离 | 配置多租户 | 不同租户消息 | 互不干扰 | R-S5 AC-04 |
| UT-PB-005 | 投递延迟 | delay=200ms | Producer send | 延迟 ≥ 200ms | R-S5 AC-05 |
| UT-PB-006 | STRING Schema | schema=STRING | send/receive | 消息正确序列化/反序列化 | R-S5 AC-06 |
| UT-PB-007 | JSON Schema | schema=JSON | send/receive | 消息正确序列化/反序列化 | R-S5 AC-06 |
| UT-PB-008 | getTopicsOfNamespace | 有规则配置 | 请求 Topic 列表 | 返回配置的 Topic 列表 | R-S5 AC-07 |
| UT-PB-009 | Shared 订阅 | subscription=Shared | Consumer receive | 收到消息 | R-S5 AC-08 |
| UT-PB-010 | topic 条件匹配 | 规则 topic=`persistent://tenant/ns/test` | send to 匹配 topic | 匹配规则 | R-S5 AC-09 |
| UT-PB-011 | 消息录制 | mode=RECORD | Producer send | 录制到 Server | R-S5 AC-10 |
| UT-PB-012 | Apache Pulsar SDK | pulsar-client 2.x | 全流程 | 正常工作 | R-A5 AC-01 |
| UT-PB-013 | TDMQ SDK | tdmq-client | 全流程 | 正常工作 | R-A5 AC-01 |
| UT-PB-014 | 非分区 Topic | non-partitioned | send/receive | 正常工作 | R-S5 AC-08 |

#### 3.3.5 JMS Mock Broker

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| UT-JB-001 | Queue FIFO 投递 | 有 Queue 规则 | send 3 条 → receive | 按 FIFO 顺序收到 | R-S6 AC-01 |
| UT-JB-002 | Topic 广播 | 有 Topic 规则，2 个 Consumer | send 1 条 | 两个 Consumer 都收到 | R-S6 AC-02 |
| UT-JB-003 | 延迟投递 | delay=200ms | send | 200ms 后收到 | R-S6 AC-03 |
| UT-JB-004 | 死信队列 | redeliveryCount=3 | send + 3 次 rollback | 进入 DLQ | R-S6 AC-04 |
| UT-JB-005 | topic 条件匹配 | 规则 name=`test-queue` | send to `test-queue` | 匹配规则 | R-S6 AC-05 |
| UT-JB-006 | 消息录制 | mode=RECORD | send | 录制到 Server | R-S6 AC-06 |
| UT-JB-007 | ActiveMQ 客户端兼容 | ActiveMQ 5.x client | 全流程 | 正常工作 | R-A6 AC-01 |

#### 3.3.6 gRPC Handler

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|---------|
| UT-GR-001 | Unary RPC 匹配 | 有 gRPC 规则 | 发送 Unary 请求 | 返回 Mock 响应 |
| UT-GR-002 | Server Streaming | 有 streaming 规则 | 发送请求 | 返回流式 Mock 响应 |
| UT-GR-003 | Client Streaming | 有 streaming 规则 | 发送流式请求 | 返回 Mock 响应 |
| UT-GR-004 | Bidirectional Streaming | 有 bidi streaming 规则 | 发送双向流 | 返回流式 Mock 响应 |
| UT-GR-005 | 延迟响应 | delay=300ms | 发送请求 | 延迟 ≥ 300ms |
| UT-GR-006 | 错误响应 | 规则配置 error | 发送请求 | 返回 gRPC error status |
| UT-GR-007 | 未匹配返回 UNIMPLEMENTED | 无匹配规则 | 发送请求 | 返回 UNIMPLEMENTED |
| UT-GR-008 | port=0 fallback | 规则 host:0 | 发送请求到 host:8080 | fallback 匹配成功 |
| UT-GR-009 | Protobuf 编解码 | protobuf 消息 | 发送 protobuf | 正确解码 + 返回 protobuf |

#### 3.3.7 REST API Handlers

##### 3.3.7.1 RuleApiHandler

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|
| UT-RA-001 | GET /api/rules 查询全部 | 调用 API | 返回所有规则 | R-S7 AC-01 |
| UT-RA-002 | GET /api/rules?protocol=http | 按协议过滤 | 仅返回 HTTP 规则 | R-S7 AC-01 |
| UT-RA-003 | GET /api/rules?scenarioId=s1 | 按场景集过滤 | 仅返回该场景集规则 | R-S7 AC-01 |
| UT-RA-004 | GET /api/rules?environment=ft-1 | 按环境过滤 | 仅返回关联 ft-1 的规则 | R-S7 AC-01 |
| UT-RA-005 | POST /api/rules 新增 | 创建规则 | 201, environments 默认 [] | R-S7 AC-02 |
| UT-RA-006 | PUT /api/rules/{id} 更新 | 修改规则 | 200, 实时生效 | R-S7 AC-03 |
| UT-RA-007 | DELETE /api/rules/{id} | 删除规则 | 204 | R-S7 AC-04 |
| UT-RA-008 | GET /api/health | 健康检查 | 200, {"status":"UP"} | R-S7 AC-05 |
| UT-RA-009 | POST /api/rules/import | 导入 YAML | 规则合并，相同 ID 覆盖 | R-S7 AC-07 |
| UT-RA-010 | GET /api/rules/export?format=json | 导出 JSON | 下载 JSON 文件 | R-S7 AC-08 |
| UT-RA-011 | GET /api/rules/export?format=yaml | 导出 YAML | 下载 YAML 文件 | R-S7 AC-08 |
| UT-RA-012 | GET /api/sessions | 查看活跃 Agent | 返回 Agent 列表 | R-S7 AC-09 |
| UT-RA-013 | 规则 ID 重复拒绝 | POST 已存在的 ID | 返回 409 Conflict | R-S7.1 AC-01 |
| UT-RA-014 | 相同 method+path 提示 | POST 已有同路径规则 | 返回提示信息 | R-S7.1 AC-02 |
| UT-RA-015 | 乐观锁冲突 | PUT version 不匹配 | 返回 409 Conflict | R-S7.1 AC-03 |
| UT-RA-016 | 规则版本历史 | GET /api/rules/{id}/history | 返回最近 10 版本 | R-S7.4 AC-02 |
| UT-RA-017 | 回滚版本 | POST /api/rules/{id}/restore?version=N | 规则回滚 | R-S7.4 AC-03 |
| UT-RA-018 | 继承环境不可删除 | PUT 规则试图删除场景集继承环境 | 自动合并继承环境 | R-S7.5 AC-13 |
| UT-RA-019 | 查询继承环境 | GET /api/rules/{id}/inherited-environments | 返回继承列表 | R-S7.5 AC-14 |

##### 3.3.7.2 EnvironmentApiHandler

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|
| UT-EA-001 | POST /api/environments | 创建环境 | 201, mode=passthrough 默认 | R-S7.3 AC-01 |
| UT-EA-002 | GET /api/environments | 列出环境 | 返回环境列表+Agent 数量 | R-S7.3 AC-02 |
| UT-EA-003 | PUT /api/environments/{name} 切换模式 | 修改 mode=stub | 200, 下发指令给 Agent | R-S7.3 AC-03 |
| UT-EA-004 | DELETE /api/environments/{name} | 删除无 Agent 环境 | 204 | R-S7.3 AC-04 |
| UT-EA-005 | DELETE 有 Agent 环境失败 | 删除有活跃 Agent 的环境 | 返回 409 | R-S7.3 AC-04 |
| UT-EA-006 | GET /api/environments/{name}/agents | 查看环境 Agent | 返回 Agent 列表 | R-S7.3 AC-05 |
| UT-EA-007 | Agent 注册自动创建环境 | 未知 environment | Server 自动创建 | R-S7.3 AC-06 |
| UT-EA-008 | 模式切换不影响关联 | stub→passthrough→stub | 规则关联不变 | R-S7.3 AC-07 |
| UT-EA-009 | 新环境不含已有规则 | 创建新环境 | 已有规则 environments 不含新环境 | R-S7.3 AC-08 |
| UT-EA-010 | POST /api/environments/{name}/rules | 批量关联规则 | 200 | R-S7.3 AC-09 |
| UT-EA-011 | DELETE /api/environments/{name}/rules | 批量取消关联 | 200 | R-S7.3 AC-10 |

##### 3.3.7.3 SceneApiHandler

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|
| UT-SA-001 | POST /api/scenarios | 创建场景集 | 201, environments=[] | R-S7.5 AC-01 |
| UT-SA-002 | GET /api/scenarios | 列出场景集 | 含规则数量、状态、环境 | R-S7.5 AC-02 |
| UT-SA-003 | PUT /api/scenarios/{id} | 更新场景集 | 200 | R-S7.5 AC-03 |
| UT-SA-004 | DELETE /api/scenarios/{id} 已禁用 | 删除已禁用场景集 | 204 | R-S7.5 AC-04 |
| UT-SA-005 | DELETE 启用状态场景集 | 删除已启用场景集 | 返回 409 | R-S7.5 AC-04 |
| UT-SA-006 | POST /api/scenarios/{id}/activate | 一键启用 | 所有规则 enabled=true | R-S7.5 AC-05 |
| UT-SA-007 | POST /api/scenarios/{id}/deactivate | 一键禁用 | 所有规则 enabled=false | R-S7.5 AC-06 |
| UT-SA-008 | 规则指定 scenarioId | 创建规则时指定 | 归属该场景集 | R-S7.5 AC-07 |
| UT-SA-009 | 启用后环境才参与匹配 | 场景集禁用时 | 规则不匹配 | R-S7.5 AC-08 |
| UT-SA-010 | POST /api/scenarios/{id}/environments | 批量关联环境 | 200 | R-S7.5 AC-09 |
| UT-SA-011 | DELETE /api/scenarios/{id}/environments | 批量取消环境 | 200 | R-S7.5 AC-10 |
| UT-SA-012 | 环境同步到规则 | 场景集新增环境 | 规则 environments 自动新增 | R-S7.5 AC-12 |
| UT-SA-013 | 移除环境同步删除 | 场景集移除环境（无其他继承） | 规则 environments 自动删除 | R-S7.5 AC-12 |
| UT-SA-014 | 移除环境不删除（有其他继承） | 场景集移除环境（另一场景集仍继承） | 规则 environments 保留 | R-S7.5 AC-12 |

##### 3.3.7.4 AgentApiHandler

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|
| UT-AA-001 | POST /api/agent/register | Agent 注册 | 返回 agentId + mode | R-S7.2 AC-01 |
| UT-AA-002 | POST /api/agent/heartbeat | 心跳上报 | 200, 更新最后心跳 | R-S7.2 AC-02 |
| UT-AA-003 | GET /api/agent/rules | 拉取规则 | 返回环境过滤后的规则 + version | R-S7.2 AC-03 |
| UT-AA-004 | GET /api/agent/poll?agentId=x | 长轮询 | 有变更即时返回 | R-S7.2 AC-04 |
| UT-AA-005 | POST /api/agent/recordings | 上传录制 | 200, 存储成功 | R-S7.2 AC-05 |
| UT-AA-006 | GET /api/agents | 查看所有 Agent | 返回 Agent 状态列表 | R-S7.2 AC-06 |
| UT-AA-007 | 心跳超时 90s | 90s 无心跳 | Agent 标记离线 | R-S7.2 AC-02 |

##### 3.3.7.5 AuthApiHandler + AuthFilter

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|
| UT-AU-001 | GET /api/auth/me 未认证 | 无 Token | 返回 guest 角色 | R-S7.7 AC-01 |
| UT-AU-002 | POST /api/auth/login | 用户名/密码 | 返回 JWT Token | R-S7.7 AC-02 |
| UT-AU-003 | GET /api/users (admin) | admin Token | 返回用户列表 | R-S7.7 AC-03 |
| UT-AU-004 | GET /api/users (non-admin) | developer Token | 返回 403 | R-S7.7 AC-03 |
| UT-AU-005 | POST /api/users | admin 创建用户 | 201 | R-S7.7 AC-04 |
| UT-AU-006 | PUT /api/users/{username}/role | 修改角色 | 200 | R-S7.7 AC-05 |
| UT-AU-007 | DELETE /api/users/{username} | 删除用户 | 204 | R-S7.7 AC-06 |
| UT-AU-008 | 删除自己失败 | admin 删除自己 | 返回 403 | R-S7.7 AC-06 |
| UT-AU-009 | 写操作权限校验 | guest POST /api/rules | 返回 403 + 权限信息 | R-S7.7 AC-07 |
| UT-AU-010 | guest 读操作允许 | guest GET /api/rules | 200 | R-S7.7 AC-08 |
| UT-AU-011 | JWT Bearer 认证 | Authorization: Bearer <jwt> | 解析 role | R-S7.7 AC-09 |
| UT-AU-012 | X-Api-Key 认证 | X-Api-Key: <key> | 映射到 role | R-S7.7 AC-09 |
| UT-AU-013 | 本地 127.0.0.1 免认证 | 来自 localhost | 自动 admin | R-S7.7 AC-10 |
| UT-AU-014 | auth.local-bypass=false | 关闭本地绕过 | localhost 需认证 | R-S7.7 AC-10 |
| UT-AU-015 | JWT 24h 有效期 | Token 过期 | 认证失败 | R-S7.7 AC-11 |
| UT-AU-016 | JWT 自定义有效期 | expiresIn=1h | 1h 后过期 | R-S7.7 AC-11 |
| UT-AU-017 | auth.enabled=false | 禁用认证 | 返回 admin Token | R-S7.7 AC-02 |

##### 3.3.7.6 RecordingApiHandler

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|
| UT-RC-001 | GET /api/recordings | 列出录制 session | 返回列表+大小+时间 | R-S8 AC-04 |
| UT-RC-002 | DELETE /api/recordings/{id} | 删除 session | 204 | R-S8 AC-05 |
| UT-RC-003 | 录制保留 7 天 | 超过 7 天的 session | 自动清理 | R-S8 AC-01 |
| UT-RC-004 | 录制容量 500MB | 超过 500MB | 清理最旧 session | R-S8 AC-02 |
| UT-RC-005 | 清理日志输出 | 执行清理 | INFO 日志 | R-S8 AC-06 |
| UT-RC-006 | 录制数据持久化 | Server 重启 | 已持久化数据不丢失 | R-S8 描述 |

##### 3.3.7.7 其他 API Handler

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-OT-001 | GET /api/recordings → 生成回放规则 | 选择 session | 生成 stub rule |
| UT-OT-002 | HAR 导出 | GET /api/logs/export?format=har | 输出 HAR JSON |
| UT-OT-003 | 系统状态 API | GET /api/status | 返回端口状态+Agent数+规则数 |
| UT-OT-004 | 请求日志过滤 | GET /api/logs?protocol=http | 仅返回 HTTP 日志 |
| UT-OT-005 | 请求日志时间范围 | GET /api/logs?from=...&to=... | 返回范围内日志 |
| UT-OT-006 | 请求统计 Dashboard | GET /api/stats | 返回总数+命中率+平均响应时间 |

#### 3.3.8 Storage 层

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-ST-001 | JdbcStorage CRUD Rule | 增删改查规则 | 数据正确持久化 |
| UT-ST-002 | JdbcStorage CRUD Environment | 增删改查环境 | 数据正确持久化 |
| UT-ST-003 | JdbcStorage CRUD Scene | 增删改查场景集 | 数据正确持久化 |
| UT-ST-004 | JdbcStorage CRUD Agent | 增删改查 Agent | 数据正确持久化 |
| UT-ST-005 | JdbcStorage CRUD User | 增删改查用户 | 数据正确持久化 |
| UT-ST-006 | JdbcStorage CRUD Recording | 增删改查录制 | 数据正确持久化 |
| UT-ST-007 | FileStorage 存储 | 写入/读取文件 | 数据正确 |
| UT-ST-008 | DatabaseDialect PostgreSQL | 生成 DDL | PostgreSQL 方言正确 |
| UT-ST-009 | DatabaseDialect H2 | 生成 DDL | H2 方言正确 |
| UT-ST-010 | TypeHandler 序列化 | EnvironmentMode/JSON/Fault/List | 正确序列化/反序列化 |
| UT-ST-011 | RecordingCleanupTask | 执行清理 | 按策略清理数据 |

#### 3.3.9 MCP 工具链

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-MC-001 | MCP Schema 构建 | 构建 tool schema | 输出合法 JSON Schema |
| UT-MC-002 | MCP Tool 注册 | 注册 RuleTools | 工具可调用 |
| UT-MC-003 | MCP Tool 执行 - listRules | 调用工具 | 返回规则列表 |
| UT-MC-004 | MCP Tool 执行 - createRule | 调用工具 | 创建规则 |
| UT-MC-005 | MCP Safety Level | 只读 vs 写操作 | 正确分级 |
| UT-MC-006 | MCP Exception | 工具执行失败 | 返回结构化错误 |

---

### 3.4 baafoo-plugin-api 单元测试

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-PL-001 | PluginManifest 加载 | 解析 plugin.yaml | 各字段正确 |
| UT-PL-002 | PluginHealth UP | 调用 health() | 返回 UP 状态 |
| UT-PL-003 | PluginHealth DOWN | 模拟插件异常 | 返回 DOWN + 错误信息 |
| UT-PL-004 | PluginEvent 构造 | 构造事件 | type/timestamp/payload 正确 |
| UT-PL-005 | EventBus 发布订阅 | publish 事件 → 订阅者收到 | 事件正确投递 |
| UT-PL-006 | EventBus 无订阅者 | publish 事件 | 不抛异常 |
| UT-PL-007 | PluginClassLoader 隔离 | 加载插件类 | 插件类与主 ClassLoader 隔离 |
| UT-PL-008 | FeignPlugin SPI | 加载 Feign 插件 | Advice 正确注册 |

### 3.5 baafoo-cli 单元测试

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| UT-CLI-001 | `baafoo init` | 执行 init 命令 | 生成默认配置文件 |
| UT-CLI-002 | `baafoo start` | 执行 start 命令 | 启动 Server 进程 |
| UT-CLI-003 | `baafoo stop` | 执行 stop 命令 | 停止 Server 进程 |
| UT-CLI-004 | `baafoo status` | 执行 status 命令 | 输出运行状态 |
| UT-CLI-005 | `baafoo rules list` | 列出规则 | 输出规则表格 |
| UT-CLI-006 | `baafoo rules add -f rules.yaml` | 导入规则文件 | 规则创建成功 |
| UT-CLI-007 | `baafoo env switch ft-1 stub` | 切换模式 | 环境模式变更 |
| UT-CLI-008 | `baafoo agent attach <pid>` | 附着进程 | Agent 成功 attach |
| UT-CLI-009 | `baafoo agent list` | 列出 Agent | 输出 Agent 列表 |
| UT-CLI-010 | `baafoo record start --env ft-1` | 开始录制 | 环境 mode→RECORD |
| UT-CLI-011 | `baafoo record stop --env ft-1` | 停止录制 | 环境 mode→STUB |
| UT-CLI-012 | `baafoo replay --session <id>` | 回放录制 | 生成 stub 规则 |
| UT-CLI-013 | `baafoo import openapi api.yaml` | 导入 OpenAPI | 生成 HTTP 规则 |
| UT-CLI-014 | `baafoo import har trace.har` | 导入 HAR | 生成 HTTP 规则 |
| UT-CLI-015 | 命令帮助 | `baafoo --help` | 输出帮助信息 |
| UT-CLI-016 | 未知命令 | `baafoo unknown` | 输出错误 + 帮助 |

---

## 4. 集成测试

### 4.1 HTTP 协议集成测试

**环境**: baafoo-testcontainers 启动 Server + Agent attach 到测试应用

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| IT-HTTP-001 | 基础 GET 请求 Mock | HTTP 规则 `GET /api/users/123` → 200 | curl GET | 返回预设 body | R-S2 AC-01 |
| IT-HTTP-002 | POST 请求 Mock | HTTP 规则 `POST /api/orders` → 201 | curl POST | 返回预设 body | R-S2 AC-01 |
| IT-HTTP-003 | 路径参数提取 | 规则 `GET /api/users/{id}` | curl GET /api/users/999 | `{{path.id}}`=999 | R-S2 AC-02 |
| IT-HTTP-004 | Query 参数 Mock | 规则 query `type=detail` | curl `?type=detail` | 返回预设响应 | R-S2 AC-04 |
| IT-HTTP-005 | Header 匹配 | 规则 header `X-Trace-Id` exists | curl 带 Header | 匹配成功 | R-S2 AC-05 |
| IT-HTTP-006 | Body JSONPath 匹配 | 规则 bodyJsonPath `$.type=order` | curl POST body | 匹配成功 | R-S2 AC-03 |
| IT-HTTP-007 | 多分支响应 | 规则 3 个 response 分支 | 分别发送 VIP/detail/default | 分别命中 | R-S2 AC-06 |
| IT-HTTP-008 | Faker 模板变量 | body 含 `{{faker.phone}}` | curl GET | 返回合法手机号 | R-C2 AC-01 |
| IT-HTTP-009 | 固定延迟 | delay=500ms | curl GET | 延迟 ≥ 500ms | R-S2 AC-07 |
| IT-HTTP-010 | 随机延迟区间 | delayMin=100,delayMax=300 | curl GET 100 次 | 延迟在 [100,300] | R-S2 AC-07 |
| IT-HTTP-011 | 正态分布延迟 | delayMean=200,delayStd=50 | curl GET 1000 次 | 均值 ≈ 200ms | R-S2 AC-07 |
| IT-HTTP-012 | GBK 编码响应 | charset=GBK | curl GET | 响应体 GBK 正确解码 | R-S2 AC-08 |
| IT-HTTP-013 | Shift_JIS 编码响应 | charset=Shift_JIS | curl GET | 响应体 Shift_JIS 正确解码 | R-S2 AC-08 |
| IT-HTTP-014 | Big5 编码响应 | charset=Big5 | curl GET | 响应体 Big5 正确解码 | R-S2 AC-08 |
| IT-HTTP-015 | 状态计数器响应 | 规则 requestCount>=2 → 不同响应 | curl GET 3 次 | 第 3 次返回不同响应 | R-S2 AC-13 |
| IT-HTTP-016 | 自动重置阈值 | autoResetThreshold=10 | curl GET 11 次 | 第 11 次计数器重置 | R-S2 AC-13 |
| IT-HTTP-017 | Faker 种子确定性 | fakerSeed=42 | curl GET 2 次 | 相同 Faker 输出序列 | R-C2 AC-01 |
| IT-HTTP-018 | GraphQL operationName | 规则 graphqlOperationName=GetUser | curl POST GraphQL | 匹配成功 | R-S2 AC-14 |
| IT-HTTP-019 | GraphQL operationType | 规则 graphqlOperationType=mutation | curl POST GraphQL mutation | 匹配成功 | R-S2 AC-14 |
| IT-HTTP-020 | PASSTHROUGH 模式 | mode=PASSTHROUGH | curl GET | 透传到真实下游 | — |
| IT-HTTP-021 | RECORD 模式录制 | mode=RECORD + 真实下游 | curl GET | 响应被录制到 Server | R-S8 AC-01 |
| IT-HTTP-022 | RECORD_AND_STUB | mode=RECORD_AND_STUB | curl GET | 返回 Mock + 请求被录制 | — |
| IT-HTTP-023 | RECORD_ALL 未匹配录制 | mode=RECORD_ALL, 未命中规则 | curl GET | 透传 + 录制为 unmatched | — |
| IT-HTTP-024 | 透传代理正常 | unmatched-default=passthrough | curl 未匹配请求 | 代理到真实下游 | — |
| IT-HTTP-025 | 未匹配返回 404 | unmatched-default=404 | curl 未匹配请求 | 返回 404 | — |
| IT-HTTP-026 | 规则热加载 < 500ms | 更新规则文件 | curl GET | 500ms 内新规则生效 | R-A9 AC-08 |
| IT-HTTP-027 | 请求日志完整性 | 发送请求 | 检查日志 | 含时间戳/PID/协议/规则/耗时 | R-S2 AC-15 |
| IT-HTTP-028 | HAR 导出 | 有请求日志 | GET /api/logs/export?format=har | 输出合法 HAR | R-S2 AC-16 |
| IT-HTTP-029 | 并发 100 请求 | 100 并发 | curl GET | 全部正确响应 | — |
| IT-HTTP-030 | 长连接 Keep-Alive | Keep-Alive | curl 复用连接 | 多请求复用同一连接 | — |

### 4.2 TCP 协议集成测试

**环境**: baafoo-testcontainers + baafoo-test-app TCP 调用器

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| IT-TCP-001 | BIO Socket 拦截 | Agent + TCP 规则 | BIO 连接 | 连接重定向到挡板 | R-A2 AC-01 |
| IT-TCP-002 | NIO Socket 拦截 | Agent + TCP 规则 | NIO 连接 | 连接重定向到挡板 | R-A3 AC-01 |
| IT-TCP-003 | 前缀 Hex 匹配 | prefixHex=`010203` | 发送 `010203...` | 返回预设字节 | R-S3 AC-01 |
| IT-TCP-004 | 正则匹配字节 | pattern=`^01.{1}03` | 发送匹配字节 | 返回预设字节 | R-S3 AC-02 |
| IT-TCP-005 | 多轮交互 3 轮 | 3 个 TcpRound | 依次发送 3 请求 | 分别返回 round1/2/3 | R-S3 AC-03 |
| IT-TCP-006 | 多轮循环 | tcpLoop=true | 发送 4 请求 | 第 4 次回到 round1 | R-S3 AC-03 |
| IT-TCP-007 | 多轮不循环关闭 | tcpLoop=false | 发送 4 请求 | 第 4 次连接关闭 | R-S3 AC-03 |
| IT-TCP-008 | 偏移量匹配 | offsetStart=4,offsetEnd=6,offsetHex=`0001` | 发送含偏移字段 | 返回成功响应 | R-S3 AC-05 |
| IT-TCP-009 | 录制回放 | 有 TCP 录制 session | 发送匹配请求 | 返回录制的字节 | R-S3 AC-04 |
| IT-TCP-010 | 未匹配透传 | unmatched-default=passthrough | 发送未匹配字节 | 透传到真实下游 | — |
| IT-TCP-011 | NIO finishConnect | Pulsar 客户端 NIO | 建立 NIO 连接 | 连接正确建立 | R-A3 AC-03 |
| IT-TCP-012 | 多客户端并发 | 3 个 TCP 客户端 | 同时发送 | 各自独立多轮交互 | — |
| IT-TCP-013 | 长连接保持 | 连接保持 5min | 周期发送 | 连接不中断 | — |

### 4.3 Kafka Mock Broker 集成测试

**环境**: baafoo-testcontainers + Kafka Client (2.8 & 3.x)

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| IT-KAFKA-001 | Producer send 成功 | Kafka 规则 | Producer send() | 返回 RecordMetadata | R-S4 AC-02 |
| IT-KAFKA-002 | Consumer poll 收到消息 | 预设消息 | Consumer poll() | 收到预设消息 | R-S4 AC-03 |
| IT-KAFKA-003 | 消费完毕返回空 | 消息已消费完 | Consumer poll() | 返回空消息集 | R-S4 AC-03 |
| IT-KAFKA-004 | 投递延迟 | delay=200ms | Producer send | 延迟 ≥ 200ms | R-S4 AC-04 |
| IT-KAFKA-005 | topic 通配订阅 | topic=`test.*` | 订阅 test.foo | 匹配成功 | R-S4 AC-05 |
| IT-KAFKA-006 | topic 条件匹配 | 规则 topic=`orders` | send to orders | 匹配规则 | R-S4 AC-09 |
| IT-KAFKA-007 | 消息录制 | mode=RECORD | Producer send | 录制到 Server | R-S4 AC-10 |
| IT-KAFKA-008 | Client 2.8 兼容 | Kafka Client 2.8 | 全流程 | 正常工作 | R-S4 AC-07 |
| IT-KAFKA-009 | Client 3.x 兼容 | Kafka Client 3.5 | 全流程 | 正常工作 | R-S4 AC-07 |
| IT-KAFKA-010 | 不支持 API 不崩溃 | 调 OffsetCommit | 调用 | 返回空/默认 | R-S4 AC-06 |
| IT-KAFKA-011 | acks=all 明确错误 | acks=all | Producer send | 返回明确错误 | R-S4 AC-08 |
| IT-KAFKA-012 | Kafka 故障 NOT_LEADER | fault KAFKA_NOT_LEADER_FOR_PARTITION | Produce | error code 6 | — |
| IT-KAFKA-013 | Kafka 故障 THROTTLE | fault KAFKA_PRODUCE_THROTTLE, delayMs=100 | Produce | 延迟 ≥ 100ms | — |
| IT-KAFKA-014 | Kafka 故障 OFFSET_OUT_OF_RANGE | fault KAFKA_OFFSET_OUT_OF_RANGE | Fetch | error code 1 | — |
| IT-KAFKA-015 | Kafka 故障 CONNECTION_RESET | fault KAFKA_CONNECTION_RESET | Produce | 连接关闭 | — |
| IT-KAFKA-016 | 多 Topic 隔离 | 两个 Topic 规则 | 分别 send | 互不干扰 | — |
| IT-KAFKA-017 | Agent 拦截 Producer 构造 | KafkaProducer 构造 | bootstrap.servers 被替换 | R-A4 AC-01 |
| IT-KAFKA-018 | Agent 拦截 Consumer 构造 | KafkaConsumer 构造 | bootstrap.servers 被替换 | R-A4 AC-01 |

### 4.4 Pulsar Mock Broker 集成测试

**环境**: baafoo-testcontainers + Pulsar Client (Apache & TDMQ)

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| IT-PULSAR-001 | Lookup 返回 Mock Broker | Pulsar 规则 | lookupTopic | 返回 localhost:9003 | R-S5 AC-01 |
| IT-PULSAR-002 | Producer send | Pulsar 规则 | Producer send() | 返回 MessageId | R-S5 AC-02 |
| IT-PULSAR-003 | Consumer receive | 预设消息 | Consumer receive() | 收到预设消息 | R-S5 AC-03 |
| IT-PULSAR-004 | tenant/ns/topic 隔离 | 多租户配置 | 不同租户 send | 互不干扰 | R-S5 AC-04 |
| IT-PULSAR-005 | 投递延迟 | delay=200ms | Producer send | 延迟 ≥ 200ms | R-S5 AC-05 |
| IT-PULSAR-006 | STRING Schema | schema=STRING | send/receive | 正确序列化 | R-S5 AC-06 |
| IT-PULSAR-007 | JSON Schema | schema=JSON | send/receive | 正确序列化 | R-S5 AC-06 |
| IT-PULSAR-008 | getTopicsOfNamespace | 有规则 | 请求 Topic 列表 | 返回配置列表 | R-S5 AC-07 |
| IT-PULSAR-009 | Shared 订阅 | subscription=Shared | Consumer receive | 收到消息 | R-S5 AC-08 |
| IT-PULSAR-010 | topic 条件匹配 | 规则 topic=`persistent://t/n/test` | send to 匹配 topic | 匹配规则 | R-S5 AC-09 |
| IT-PULSAR-011 | 消息录制 | mode=RECORD | Producer send | 录制到 Server | R-S5 AC-10 |
| IT-PULSAR-012 | Apache Pulsar SDK | pulsar-client 2.x | 全流程 | 正常工作 | R-A5 AC-01 |
| IT-PULSAR-013 | TDMQ SDK | tdmq-client | 全流程 | 正常工作 | R-A5 AC-01 |
| IT-PULSAR-014 | 非分区 Topic | non-partitioned | send/receive | 正常工作 | R-S5 AC-08 |
| IT-PULSAR-015 | Agent 拦截 PulsarClient | PulsarClient.builder() | serviceUrl 被替换 | R-A5 AC-02 |
| IT-PULSAR-016 | NIO 连接建立 | Pulsar NIO 模式 | 连接 | finishConnect 正确 | R-A3 AC-03 |

### 4.5 JMS Mock Broker 集成测试

**环境**: baafoo-testcontainers + ActiveMQ Client

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| IT-JMS-001 | Queue FIFO 投递 | Queue 规则 | send 3 条 → receive | FIFO 顺序收到 | R-S6 AC-01 |
| IT-JMS-002 | Topic 广播 | Topic 规则 + 2 Consumer | send 1 条 | 两个 Consumer 都收到 | R-S6 AC-02 |
| IT-JMS-003 | 延迟投递 | delay=200ms | send | 200ms 后收到 | R-S6 AC-03 |
| IT-JMS-004 | 死信队列 | redeliveryCount=3 | send + 3 次 rollback | 进入 DLQ | R-S6 AC-04 |
| IT-JMS-005 | topic 条件匹配 | 规则 name=`test-queue` | send to test-queue | 匹配规则 | R-S6 AC-05 |
| IT-JMS-006 | 消息录制 | mode=RECORD | send | 录制到 Server | R-S6 AC-06 |
| IT-JMS-007 | ActiveMQ 客户端兼容 | ActiveMQ 5.x | 全流程 | 正常工作 | R-A6 AC-01 |
| IT-JMS-008 | Agent 拦截 ConnectionFactory | createConnection() | 返回 Mock Connection | R-A6 AC-02 |

### 4.6 gRPC 协议集成测试

**环境**: baafoo-testcontainers + gRPC Client

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|---------|
| IT-GRPC-001 | Unary RPC Mock | gRPC 规则 | 发送 Unary 请求 | 返回 Mock 响应 |
| IT-GRPC-002 | Server Streaming Mock | streaming 规则 | 发送请求 | 返回流式 Mock 响应 |
| IT-GRPC-003 | Client Streaming Mock | streaming 规则 | 发送流式请求 | 返回 Mock 响应 |
| IT-GRPC-004 | Bidi Streaming Mock | bidi 规则 | 发送双向流 | 返回流式 Mock 响应 |
| IT-GRPC-005 | 延迟响应 | delay=300ms | 发送请求 | 延迟 ≥ 300ms |
| IT-GRPC-006 | 错误响应 | 规则配置 error | 发送请求 | 返回 gRPC error status |
| IT-GRPC-007 | 未匹配 UNIMPLEMENTED | 无匹配规则 | 发送请求 | 返回 UNIMPLEMENTED |
| IT-GRPC-008 | port=0 fallback | 规则 host:0 | 发送到 host:8080 | fallback 匹配 |
| IT-GRPC-009 | Agent 拦截 ManagedChannel | ManagedChannelBuilder | target 被替换 |
| IT-GRPC-010 | Protobuf 编解码 | protobuf 消息 | send/receive | 正确编解码 |

### 4.7 Consul 服务发现集成测试

**环境**: baafoo-testcontainers + Consul 容器

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| IT-CONSUL-001 | 服务名匹配 | Consul 注册了 `order-service` | 请求 order-service | 匹配规则 | R-A9 AC-04 |
| IT-CONSUL-002 | DNS `*.service.consul` 解析 | Agent DNS 拦截 | 解析 `order-service.service.consul` | 返回挡板地址 | R-A7 AC-01 |
| IT-CONSUL-003 | Agent DNS 拦截生效 | Agent 启动 | 应用内 DNS 解析 | 返回挡板地址 | R-A7 AC-02 |
| IT-CONSUL-004 | 服务名优先于 host:port | 同时配置 | 发送请求 | 服务名规则优先 | R-A9 AC-04 |
| IT-CONSUL-005 | host 通配 `*.dev:*` | 规则 `*.dev:*` | 请求 `api.dev:8080` | 匹配成功 | R-A9 AC-02 |
| IT-CONSUL-006 | host:port 精确匹配 | 规则 `10.0.0.1:8080` | 请求该地址 | 匹配成功 | R-A9 AC-01 |
| IT-CONSUL-007 | 未匹配默认 404 | 空规则列表 | 发送请求 | 返回 404 | R-A9 AC-05 |

### 4.8 Agent-Server 控制通道集成测试

**环境**: baafoo-testcontainers + Agent attach

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| IT-CC-001 | Agent 注册 | Agent 启动 | POST /api/agent/register | 返回 agentId + mode | R-S7.2 AC-01 |
| IT-CC-002 | 心跳上报 | Agent 运行中 | POST /api/agent/heartbeat | Server 更新状态 | R-S7.2 AC-02 |
| IT-CC-003 | 拉取规则 | Agent 注册后 | GET /api/agent/rules | 返回环境过滤后规则 | R-S7.2 AC-03 |
| IT-CC-004 | 长轮询模式切换 | Agent 长轮询中 | Server 切换 mode | Agent < 1s 收到通知 | R-S7.2 AC-04 |
| IT-CC-005 | 上传录制数据 | mode=RECORD | POST /api/agent/recordings | Server 存储成功 | R-S7.2 AC-05 |
| IT-CC-006 | Server 不可用降级 | Server 宕机 | Agent 请求 | 使用本地最后已知规则 | R-A1 AC-03 |
| IT-CC-007 | 心跳超时 90s | 90s 无心跳 | Server 检查 | Agent 标记离线 | R-S7.2 AC-02 |
| IT-CC-008 | 规则热加载 < 500ms | 更新规则 | Agent 拉取 | 500ms 内生效 | R-A9 AC-08 |
| IT-CC-009 | 多 Agent 同环境 | 2 个 Agent 同 env | 模式切换 | 两个 Agent 都收到 | — |
| IT-CC-010 | 多 Agent 不同环境 | 2 个 Agent 不同 env | 分别切换 | 各自独立 | — |

### 4.9 环境与模式切换集成测试

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| IT-ENV-001 | STUB→PASSTHROUGH 切换 | Agent 运行中 | 切换模式 | Agent 停止拦截，请求透传 | R-S7.3 AC-03 |
| IT-ENV-002 | PASSTHROUGH→STUB 切换 | Agent 运行中 | 切换模式 | Agent 恢复拦截 | R-S7.3 AC-03 |
| IT-ENV-003 | STUB→RECORD 切换 | 真实下游可用 | 切换模式 | 开始录制响应 | R-S7.3 AC-03 |
| IT-ENV-004 | RECORD→STUB 切换 | 有录制数据 | 切换模式 | 停止录制，返回 Mock | — |
| IT-ENV-005 | STUB→RECORD_AND_STUB | Agent 运行中 | 切换模式 | 返回 Mock + 录制请求 | — |
| IT-ENV-006 | STUB→RECORD_ALL | Agent 运行中 | 切换模式 | 匹配+未匹配都录制 | — |
| IT-ENV-007 | 模式切换 < 1s 生效 | Agent 长轮询 | 切换 | 1s 内 Agent 行为变更 | R-A9 AC-06 |
| IT-ENV-008 | 新环境不含已有规则 | 创建新环境 ft-2 | 查看规则 | 已有规则 environments 不含 ft-2 | R-S7.3 AC-08 |
| IT-ENV-009 | Agent 注册自动创建环境 | 未知 env | Agent 注册 | Server 自动创建环境 | R-S7.3 AC-06 |
| IT-ENV-010 | 删除无 Agent 环境 | 无活跃 Agent | DELETE | 204 | R-S7.3 AC-04 |
| IT-ENV-011 | 删除有 Agent 环境失败 | 有活跃 Agent | DELETE | 409 | R-S7.3 AC-04 |
| IT-ENV-012 | 模式切换不影响规则关联 | stub→passthrough→stub | 检查规则 | 关联不变 | R-S7.3 AC-07 |

### 4.10 录制回放集成测试

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| IT-REC-001 | HTTP 录制+回放 | mode=RECORD | 录制 → 切 STUB → 回放 | 回放响应与录制一致 | R-S8 AC-03 |
| IT-REC-002 | TCP 录制+回放 | mode=RECORD | 录制 → 回放 | 字节级一致 | R-S3 AC-04 |
| IT-REC-003 | Kafka 录制+回放 | mode=RECORD | 录制 → 回放 | 消息内容一致 | R-S4 AC-10 |
| IT-REC-004 | Pulsar 录制+回放 | mode=RECORD | 录制 → 回放 | 消息内容一致 | R-S5 AC-10 |
| IT-REC-005 | JMS 录制+回放 | mode=RECORD | 录制 → 回放 | 消息内容一致 | R-S6 AC-06 |
| IT-REC-006 | 录制保留 7 天 | 有旧 session | 等待/模拟过期 | 自动清理 | R-S8 AC-01 |
| IT-REC-007 | 录制容量 500MB | 超容量 | 上传 | 清理最旧 | R-S8 AC-02 |
| IT-REC-008 | 录制数据持久化 | Server 重启 | 重启后查看 | 数据不丢失 | — |
| IT-REC-009 | 生成回放规则 | 有录制 session | 调用 API | 生成 stub 规则 | R-S8 AC-03 |
| IT-REC-010 | 清理日志输出 | 执行清理 | 检查日志 | INFO 日志输出 | R-S8 AC-06 |

---

## 5. 端到端测试

### 5.1 Docker Compose 多环境 E2E

**环境**: Docker Compose 编排 Baafoo Server + 多个应用容器

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| E2E-DC-001 | 5 分钟搭建环境 | Docker + Compose | 启动 → 首条 Mock 响应 | ≤ 5 分钟 | R-S1 AC-01 |
| E2E-DC-002 | 全协议覆盖 | 6 协议规则配置 | 依次发送 6 协议请求 | 全部正确响应 | R-S1 AC-02 |
| E2E-DC-003 | 多环境隔离 | ft-1 + ft-2 | 分别请求 | 互不干扰 | R-S1 AC-03 |
| E2E-DC-004 | 场景集一键切换 | 2 个场景集 | activate 场景 A → deactivate → activate B | 规则集切换 | R-S7.5 AC-05/06 |
| E2E-DC-005 | 录制→回放全流程 | 真实下游 | RECORD → 停止 → 回放 | 响应一致 | R-S8 AC-03 |
| E2E-DC-006 | Agent attach Spring Boot | Spring Boot 应用 | -javaagent 启动 | 全协议拦截 | R-A1 AC-01 |
| E2E-DC-007 | 5 并发 Agent | 5 个应用容器 | 同时请求 | 各自独立 Mock | — |
| E2E-DC-008 | 规则热加载 E2E | 运行中 | 修改规则文件 | < 500ms 生效 | R-A9 AC-08 |
| E2E-DC-009 | 模式切换 E2E | 运行中 | stub→passthrough→record | 各模式行为正确 | R-S7.3 AC-03 |
| E2E-DC-010 | Server 重启恢复 | 运行中 | 重启 Server | 规则/环境/录制不丢失 | — |
| E2E-DC-011 | JDK 8 兼容 | JDK 8 应用 | 全流程 | 正常工作 | R-A8 AC-01 |
| E2E-DC-012 | JDK 11 兼容 | JDK 11 应用 | 全流程 | 正常工作 | R-A8 AC-01 |
| E2E-DC-013 | JDK 17 兼容 | JDK 17 应用 | 全流程 | 正常工作 | R-A8 AC-01 |

### 5.2 Spring Boot 应用 E2E

**环境**: baafoo-test-spring (Spring Boot 2.x + 3.x)

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|---------|
| E2E-SB-001 | Spring Boot 2.x HTTP Mock | -javaagent 启动 | RestTemplate 调用 | 返回 Mock 响应 | R-A8 AC-02 |
| E2E-SB-002 | Spring Boot 3.x HTTP Mock | -javaagent 启动 | RestTemplate 调用 | 返回 Mock 响应 | R-A8 AC-02 |
| E2E-SB-003 | Feign Client Mock | Feign 声明式客户端 | Feign 调用 | 返回 Mock 响应 | R-A8 AC-03 |
| E2E-SB-004 | WebClient Mock | Spring WebFlux | WebClient 调用 | 返回 Mock 响应 | R-A8 AC-04 |
| E2E-SB-005 | Kafka Producer/Consumer | Spring Kafka | send/poll | 消息被 Mock | R-A8 AC-05 |
| E2E-SB-006 | Pulsar Producer/Consumer | Spring Pulsar | send/receive | 消息被 Mock | R-A8 AC-06 |
| E2E-SB-007 | JMS 发送/接收 | Spring JMS | send/receive | 消息被 Mock | R-A8 AC-07 |
| E2E-SB-008 | gRPC Client | grpc-spring-boot-starter | gRPC 调用 | 返回 Mock 响应 | R-A8 AC-08 |
| E2E-SB-009 | 全协议联合调用 | 同时配置 6 协议规则 | 依次调用 6 协议 | 全部正确 Mock | R-A8 AC-09 |
| E2E-SB-010 | 配置文件修改热生效 | 修改规则 | < 500ms | 新规则生效 | R-A9 AC-08 |
| E2E-SB-011 | actuator/health 不拦截 | Spring Boot Actuator | GET /actuator/health | 透传或返回真实 | — |
| E2E-SB-012 | ApplicationContext 正常启动 | -javaagent | Spring 启动 | 无 Bean 创建异常 | R-A1 AC-01 |

### 5.3 Web 控制台 E2E

**环境**: Playwright + baafoo-server

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|---------|---------|---------|
| E2E-WEB-001 | 登录页面 | 导航到 /login | 显示登录表单 | R-W1 AC-01 |
| E2E-WEB-002 | 登录成功 | 输入 admin/admin | 跳转到 Dashboard | R-W1 AC-02 |
| E2E-WEB-003 | 登录失败 | 输入错误密码 | 显示错误提示 | R-W1 AC-03 |
| E2E-WEB-004 | Dashboard 概览 | 登录后 | 显示端口状态+Agent数+规则数 | R-W2 AC-01 |
| E2E-WEB-005 | 规则列表页 | 导航到 Rules | 显示规则表格 | R-W2 AC-02 |
| E2E-WEB-006 | 新增规则 | 点击 New → 填写 → Save | 规则出现在列表 | R-W2 AC-03 |
| E2E-WEB-007 | 编辑规则 | 点击规则 → 修改 → Save | 规则更新 | R-W2 AC-04 |
| E2E-WEB-008 | 删除规则 | 点击 Delete → Confirm | 规则消失 | R-W2 AC-05 |
| E2E-WEB-009 | 禁用/启用规则 | 切换 enabled | 状态切换 | R-W2 AC-04 |
| E2E-WEB-010 | 环境列表页 | 导航到 Environments | 显示环境列表 | R-W3 AC-01 |
| E2E-WEB-011 | 切换环境模式 | 点击 mode → stub | 模式切换 | R-W3 AC-02 |
| E2E-WEB-012 | 查看 Agent 列表 | 导航到 Agents | 显示 Agent 状态 | R-W3 AC-03 |
| E2E-WEB-013 | 录制管理页 | 导航到 Recordings | 显示录制列表 | R-W4 AC-01 |
| E2E-WEB-014 | 开始录制 | 点击 Start Recording | 环境模式→RECORD | R-W4 AC-02 |
| E2E-WEB-015 | 停止录制 | 点击 Stop | 环境模式→STUB | R-W4 AC-03 |
| E2E-WEB-016 | 回放录制 | 点击 Replay | 生成 stub 规则 | R-W4 AC-04 |
| E2E-WEB-017 | 删除录制 | 点击 Delete | 录制消失 | R-W4 AC-05 |
| E2E-WEB-018 | 请求日志页 | 导航到 Logs | 显示请求日志表格 | R-W5 AC-01 |
| E2E-WEB-019 | 日志过滤 | 按协议过滤 | 仅显示对应协议 | R-W5 AC-02 |
| E2E-WEB-020 | HAR 导出 | 点击 Export HAR | 下载 HAR 文件 | R-W5 AC-03 |
| E2E-WEB-021 | 场景集管理 | 导航到 Scenarios | 显示场景集列表 | R-W6 AC-01 |
| E2E-WEB-022 | 创建场景集 | New → 填写 → Save | 场景集创建 | R-W6 AC-02 |
| E2E-WEB-023 | 一键启用场景集 | 点击 Activate | 所有规则启用 | R-W6 AC-03 |
| E2E-WEB-024 | 一键禁用场景集 | 点击 Deactivate | 所有规则禁用 | R-W6 AC-04 |
| E2E-WEB-025 | 场景集关联环境 | 选择环境 → Save | 关联成功 | R-W6 AC-05 |
| E2E-WEB-026 | 用户管理 (admin) | 导航到 Users | 显示用户列表 | R-W7 AC-01 |
| E2E-WEB-027 | 创建用户 | New → 填写 → Save | 用户创建 | R-W7 AC-02 |
| E2E-WEB-028 | 修改角色 | 修改用户角色 | 角色更新 | R-W7 AC-03 |
| E2E-WEB-029 | 删除用户 | Delete → Confirm | 用户消失 | R-W7 AC-04 |
| E2E-WEB-030 | guest 权限受限 | guest 登录 | 看不到用户管理/写操作 | R-W7 AC-05 |
| E2E-WEB-031 | 响应式布局 | 浏览器调整大小 | 布局自适应 | R-W1 AC-04 |
| E2E-WEB-032 | 深色模式 | 切换深色 | 主题切换 | R-W1 AC-05 |

---

## 6. RBAC 权限测试

### 6.1 角色权限矩阵

| 资源/操作 | admin | developer | tester | guest |
|-----------|:-----:|:---------:|:------:|:-----:|
| GET /api/rules | ✅ | ✅ | ✅ | ✅ |
| POST /api/rules | ✅ | ✅ | ✅ | ❌ |
| PUT /api/rules/{id} | ✅ | ✅ | ✅ | ❌ |
| DELETE /api/rules/{id} | ✅ | ✅ | ❌ | ❌ |
| POST /api/rules/import | ✅ | ✅ | ✅ | ❌ |
| GET /api/rules/export | ✅ | ✅ | ✅ | ✅ |
| GET /api/environments | ✅ | ✅ | ✅ | ✅ |
| PUT /api/environments/{name} | ✅ | ✅ | ✅ | ❌ |
| DELETE /api/environments/{name} | ✅ | ✅ | ❌ | ❌ |
| POST /api/scenarios | ✅ | ✅ | ✅ | ❌ |
| DELETE /api/scenarios/{id} | ✅ | ✅ | ❌ | ❌ |
| POST /api/scenarios/{id}/activate | ✅ | ✅ | ✅ | ❌ |
| GET /api/agents | ✅ | ✅ | ✅ | ✅ |
| GET /api/users | ✅ | ❌ | ❌ | ❌ |
| POST /api/users | ✅ | ❌ | ❌ | ❌ |
| PUT /api/users/{username}/role | ✅ | ❌ | ❌ | ❌ |
| DELETE /api/users/{username} | ✅ | ❌ | ❌ | ❌ |
| GET /api/recordings | ✅ | ✅ | ✅ | ✅ |
| DELETE /api/recordings/{id} | ✅ | ✅ | ❌ | ❌ |
| GET /api/logs | ✅ | ✅ | ✅ | ✅ |
| GET /api/logs/export | ✅ | ✅ | ✅ | ✅ |
| GET /api/stats | ✅ | ✅ | ✅ | ✅ |
| GET /api/health | ✅ | ✅ | ✅ | ✅ |

### 6.2 RBAC 集成测试用例

| 用例 ID | 测试名称 | 角色 | 操作 | 预期结果 |
|---------|---------|------|------|---------|
| RBAC-001 | admin 全权限 | admin | 执行所有操作 | 全部成功 |
| RBAC-002 | developer 创建规则 | developer | POST /api/rules | 201 |
| RBAC-003 | developer 删除规则 | developer | DELETE /api/rules/{id} | 200 |
| RBAC-004 | developer 删除环境 | developer | DELETE /api/environments/{name} | 200 |
| RBAC-005 | developer 管理用户 | developer | GET /api/users | 403 |
| RBAC-006 | tester 创建规则 | tester | POST /api/rules | 201 |
| RBAC-007 | tester 删除规则 | tester | DELETE /api/rules/{id} | 403 |
| RBAC-008 | tester 删除环境 | tester | DELETE /api/environments/{name} | 403 |
| RBAC-009 | tester 管理用户 | tester | GET /api/users | 403 |
| RBAC-010 | guest 读规则 | guest | GET /api/rules | 200 |
| RBAC-011 | guest 写规则 | guest | POST /api/rules | 403 |
| RBAC-012 | guest 删除录制 | guest | DELETE /api/recordings/{id} | 403 |
| RBAC-013 | 本地 127.0.0.1 免认证 | localhost | 任意操作 | admin 权限 |
| RBAC-014 | auth.local-bypass=false | localhost | 任意操作 | 需认证 |
| RBAC-015 | JWT 过期 | 过期 Token | GET /api/rules | 401 |
| RBAC-016 | X-Api-Key 认证 | 有效 Key | GET /api/rules | 200 |
| RBAC-017 | auth.enabled=false | 禁用认证 | GET /api/users | 200 (admin) |
| RBAC-018 | 删除自己失败 | admin | DELETE /api/users/admin | 403 |
| RBAC-019 | 修改自己角色 | admin | PUT /api/users/admin/role | 403 |
| RBAC-020 | 403 含权限信息 | guest | POST /api/rules | 403 + {required: "developer+"} |

---

## 7. 故障注入测试

### 7.1 HTTP 故障注入

| 用例 ID | 故障类型 | 参数 | 测试步骤 | 预期结果 | 对应 AC |
|---------|---------|------|---------|---------|---------|
| FI-HTTP-001 | HTTP_ERROR 502 | statusCodes=[502], prob=1.0 | GET | 返回 502 | R-S12 AC-01 |
| FI-HTTP-002 | HTTP_ERROR 503 | statusCodes=[503], prob=1.0 | GET | 返回 503 | R-S12 AC-01 |
| FI-HTTP-003 | HTTP_ERROR 随机状态码 | statusCodes=[500,502,503], prob=1.0 | GET 100 次 | 状态码在列表内 | R-S12 AC-01 |
| FI-HTTP-004 | DELAY 固定 | delayMs=500, prob=1.0 | GET | 延迟 ≥ 500ms | R-S12 AC-02 |
| FI-HTTP-005 | DELAY 随机 | delayMin=100, delayMax=300, prob=1.0 | GET 100 次 | 延迟在区间内 | R-S12 AC-02 |
| FI-HTTP-006 | CONNECTION_RESET | prob=1.0 | GET | 连接 RST | R-S12 AC-03 |
| FI-HTTP-007 | READ_TIMEOUT | prob=1.0 | GET | 30s 后连接关闭 | R-S12 AC-04 |
| FI-HTTP-008 | 概率 0% 不触发 | prob=0.0 | GET 100 次 | 全部正常响应 | R-S12 AC-05 |
| FI-HTTP-009 | 概率 50% | prob=0.5 | GET 1000 次 | 触发率 ≈ 50% (±5%) | R-S12 AC-05 |
| FI-HTTP-010 | 多故障顺序评估 | HTTP_ERROR(0.0) + DELAY(1.0) | GET | DELAY 触发 | R-S12 AC-06 |
| FI-HTTP-011 | 故障 + 正常响应交替 | prob=0.5 | GET 10 次 | 约 5 次故障 5 次正常 | — |

### 7.2 Kafka 故障注入

| 用例 ID | 故障类型 | 参数 | 测试步骤 | 预期结果 |
|---------|---------|------|---------|---------|
| FI-KAFKA-001 | KAFKA_NOT_LEADER_FOR_PARTITION | prob=1.0 | Produce | error code 6 |
| FI-KAFKA-002 | KAFKA_OFFSET_OUT_OF_RANGE | prob=1.0 | Fetch | error code 1 |
| FI-KAFKA-003 | KAFKA_PRODUCE_THROTTLE | delayMs=100, prob=1.0 | Produce | 延迟 ≥ 100ms |
| FI-KAFKA-004 | KAFKA_DELAY | delayMs=200, prob=1.0 | Produce | 延迟 ≥ 200ms |
| FI-KAFKA-005 | KAFKA_CONNECTION_RESET | prob=1.0 | Produce | 连接关闭 |
| FI-KAFKA-006 | 概率 0% 不触发 | prob=0.0 | Produce 100 次 | 全部正常 |

### 7.3 故障注入边界测试

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| FI-EDGE-001 | probability=0.0 | 1000 次请求 | 0 次触发 |
| FI-EDGE-002 | probability=1.0 | 1000 次请求 | 1000 次触发 |
| FI-EDGE-003 | probability 边界 0.000001 | 1 次请求 | 不触发（极低概率）|
| FI-EDGE-004 | probability 边界 0.999999 | 1 次请求 | 几乎必然触发 |
| FI-EDGE-005 | probability > 1.0 | 配置 | 按概率 1.0 处理或报错 |
| FI-EDGE-006 | probability < 0.0 | 配置 | 按概率 0.0 处理或报错 |
| FI-EDGE-007 | delayMs=0 | 配置 | 立即响应 |
| FI-EDGE-008 | delayMs 极大值 | delayMs=3600000 | 1h 后响应或超时 |
| FI-EDGE-009 | statusCodes 空列表 | 配置 | 使用默认状态码 |
| FI-EDGE-010 | READ_TIMEOUT fallback 30s | 配置 | 30s 后连接关闭 |

---

## 8. 边界与异常测试

### 8.1 规则匹配边界

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| EDGE-RM-001 | 空规则列表 | 发送请求 | 返回 404 或透传 |
| EDGE-RM-002 | 所有规则禁用 | 发送请求 | 返回 404 或透传 |
| EDGE-RM-003 | 规则 conditions 为空 | 发送请求 | 匹配所有请求（通配）|
| EDGE-RM-004 | 超长 path (10KB) | 发送请求 | 正常匹配，不截断 |
| EDGE-RM-005 | 超长 body (10MB) | 发送请求 | 正常匹配或优雅拒绝 |
| EDGE-RM-006 | 特殊字符 path | path 含 `<>{}|\\^~[]` | 正确处理 |
| EDGE-RM-007 | Unicode path | path 含中文/emoji | 正确匹配 |
| EDGE-RM-008 | 正则 ReDoS 防护 | 灾难性正则 + 长输入 | 100ms 超时 |
| EDGE-RM-009 | 正则缓存满 512 | 600 个不同正则 | LRU 驱逐正确 |
| EDGE-RM-010 | 端口 0 fallback | port=0 规则 | 匹配任意端口 |
| EDGE-RM-011 | host 为空 | host="" | 按通配处理 |
| EDGE-RM-012 | 1000 条规则性能 | 1000 条规则 | 匹配 < 10ms |

### 8.2 模板变量边界

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| EDGE-TV-001 | 变量不存在 | `{{undefined.var}}` | 保持原样或空 |
| EDGE-TV-002 | JSONPath 不存在 | `{{body.missing}}` | 输出空 |
| EDGE-TV-003 | 嵌套 10 层 JSONPath | `{{body.a.b.c.d.e.f.g.h.i.j}}` | 正确提取 |
| EDGE-TV-004 | 模板含 100 个变量 | 替换全部 | 全部正确替换 |
| EDGE-TV-005 | 变量值含特殊字符 | 值含 `{{}}` | 不递归替换 |
| EDGE-TV-006 | Faker 种子 0 | fakerSeed=0 | 正常生成 |
| EDGE-TV-007 | Faker 种子 Long.MAX | fakerSeed=9223372036854775807 | 正常生成 |

### 8.3 编码边界

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| EDGE-EN-001 | UTF-8 多字节 | 中文/emoji body | 正确编解码 |
| EDGE-EN-002 | GBK 中文 | GBK body | 正确编解码 |
| EDGE-EN-003 | GB2312 中文 | GB2312 body | 正确编解码 |
| EDGE-EN-004 | Big5 繁体 | Big5 body | 正确编解码 |
| EDGE-EN-005 | Shift_JIS 日文 | Shift_JIS body | 正确编解码 |
| EDGE-EN-006 | EUC-KR 韩文 | EUC-KR body | 正确编解码 |
| EDGE-EN-007 | ISO-8859-1 | ISO-8859-1 body | 正确编解码 |
| EDGE-EN-008 | Windows-1252 | Windows-1252 body | 正确编解码 |
| EDGE-EN-009 | 未知 charset | charset=unknown | 跳过或 UTF-8 fallback |
| EDGE-EN-010 | 响应 Content-Type charset | 各编码 | Content-Type 含正确 charset |

### 8.4 并发与线程安全

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| CONC-001 | 100 并发 HTTP 请求 | 100 线程同时请求 | 全部正确响应 |
| CONC-002 | 1000 并发 HTTP 请求 | 1000 线程 | 全部正确响应 |
| CONC-003 | 并发规则热加载 | 读+写并发 | 不出现半加载状态 |
| CONC-004 | 并发模式切换 | 多线程切换模式 | 最终状态一致 |
| CONC-005 | 并发 Agent 注册 | 10 Agent 同时注册 | 全部成功 |
| CONC-006 | 乐观锁冲突检测 | 并发 PUT 同一规则 | 仅一个成功，其余 409 |
| CONC-007 | 计数器并发递增 | 100 线程 increment | 最终值 = 100 |
| CONC-008 | 正则缓存并发访问 | 并发 match | 无 ConcurrentModificationException |

### 8.5 异常场景

| 用例 ID | 测试名称 | 测试步骤 | 预期结果 |
|---------|---------|---------|---------|
| EXC-001 | Server 启动端口占用 | 端口 9000 被占 | 输出明确错误 |
| EXC-002 | 数据库连接失败 | DB 不可达 | 输出错误 + 退出 |
| EXC-003 | 配置文件格式错误 | 非法 YAML | 输出解析错误 |
| EXC-004 | Agent attach 失败 | PID 不存在 | 输出错误 |
| EXC-005 | 下游不可达 | 真实下游宕机 | 透传返回 502 |
| EXC-006 | Server 宕机恢复 | Agent 运行中 → Server 重启 | Agent 重新连接 |
| EXC-007 | 磁盘空间不足 | 录制写入失败 | 输出错误 + 停止录制 |
| EXC-008 | OOM 保护 | RecordingBuffer > 256MB | 停止录制 + WARN |

---

## 9. 性能与压力测试

### 9.1 HTTP 性能

| 用例 ID | 测试名称 | 前置条件 | 测试步骤 | 预期指标 |
|---------|---------|---------|---------|---------|
| PERF-HTTP-001 | 单规则 QPS | 1 条 HTTP 规则 | wrk 10s | ≥ 10000 QPS |
| PERF-HTTP-002 | 100 规则 QPS | 100 条规则 | wrk 10s | ≥ 5000 QPS |
| PERF-HTTP-003 | 1000 规则 QPS | 1000 条规则 | wrk 10s | ≥ 1000 QPS |
| PERF-HTTP-004 | 延迟 P99 | 1 条规则 | wrk 10s | P99 < 5ms |
| PERF-HTTP-005 | 并发 500 连接 | 1 条规则 | wrk -c500 | 全部成功 |
| PERF-HTTP-006 | 并发 1000 连接 | 1 条规则 | wrk -c1000 | 全部成功 |
| PERF-HTTP-007 | 大 body 响应 | body=1MB | wrk 10s | ≥ 1000 QPS |
| PERF-HTTP-008 | 模板替换性能 | body 含 10 个变量 | wrk 10s | ≥ 5000 QPS |
| PERF-HTTP-009 | Faker 性能 | body 含 5 个 faker | wrk 10s | ≥ 3000 QPS |
| PERF-HTTP-010 | 正则匹配性能 | 100 条正则规则 | wrk 10s | ≥ 2000 QPS |

### 9.2 TCP 性能

| 用例 ID | 测试名称 | 测试步骤 | 预期指标 |
|---------|---------|---------|---------|
| PERF-TCP-001 | TCP 单连接 QPS | 连续发送 1000 请求 | ≥ 5000 QPS |
| PERF-TCP-002 | TCP 100 并发连接 | 100 连接同时 | 全部正确响应 |
| PERF-TCP-003 | TCP 多轮交互延迟 | 3 轮交互 | 每轮 < 2ms |

### 9.3 Kafka 性能

| 用例 ID | 测试名称 | 测试步骤 | 预期指标 |
|---------|---------|---------|---------|
| PERF-KAFKA-001 | Producer send QPS | 1000 条消息 | ≥ 5000 msg/s |
| PERF-KAFKA-002 | Consumer poll QPS | 1000 条消息 | ≥ 5000 msg/s |
| PERF-KAFKA-003 | 多 Topic 并发 | 10 Topic 同时 send | 全部正确 |

### 9.4 Agent 性能

| 用例 ID | 测试名称 | 测试步骤 | 预期指标 |
|---------|---------|---------|---------|
| PERF-AG-001 | Agent 拦截开销 | 对比有/无 Agent | 开销 < 5% |
| PERF-AG-002 | Agent 内存占用 | 运行 1h | 堆内存增量 < 50MB |
| PERF-AG-003 | 规则热加载延迟 | 更新规则 | < 500ms 生效 |
| PERF-AG-004 | RecordingBuffer 内存 | 录制 1h | 不超过 256MB |

### 9.5 Server 性能

| 用例 ID | 测试名称 | 测试步骤 | 预期指标 |
|---------|---------|---------|---------|
| PERF-SRV-001 | REST API QPS | GET /api/rules | ≥ 5000 QPS |
| PERF-SRV-002 | 规则写入 QPS | POST /api/rules | ≥ 1000 QPS |
| PERF-SRV-003 | 100 Agent 并发心跳 | 100 Agent 心跳 | CPU < 50% |
| PERF-SRV-004 | 录制写入磁盘 | 100 MB 录制 | < 5s |
| PERF-SRV-005 | 数据库查询性能 | 10000 条规则 | 查询 < 100ms |

---

## 10. 兼容性测试

### 10.1 JDK 兼容性

| 用例 ID | JDK 版本 | 测试范围 | 预期结果 |
|---------|---------|---------|---------|
| COMP-JDK-001 | JDK 8 | 全协议 + Agent | 正常工作 |
| COMP-JDK-002 | JDK 11 | 全协议 + Agent | 正常工作 |
| COMP-JDK-003 | JDK 17 | 全协议 + Agent | 正常工作 |
| COMP-JDK-004 | JDK 8 + Spring Boot 2.x | 全流程 | 正常工作 |
| COMP-JDK-005 | JDK 17 + Spring Boot 3.x | 全流程 | 正常工作 |

### 10.2 客户端兼容性

| 用例 ID | 客户端 | 版本 | 测试范围 | 预期结果 |
|---------|--------|------|---------|---------|
| COMP-CL-001 | Kafka Client | 2.8.x | 全流程 | 正常工作 |
| COMP-CL-002 | Kafka Client | 3.5.x | 全流程 | 正常工作 |
| COMP-CL-003 | Apache Pulsar Client | 2.x | 全流程 | 正常工作 |
| COMP-CL-004 | TDMQ Pulsar Client | latest | 全流程 | 正常工作 |
| COMP-CL-005 | ActiveMQ Client | 5.x | 全流程 | 正常工作 |
| COMP-CL-006 | gRPC Java | 1.5x+ | 全流程 | 正常工作 |
| COMP-CL-007 | OkHttp | 3.x/4.x | HTTP Mock | 正常工作 |
| COMP-CL-008 | Apache HttpClient | 4.x/5.x | HTTP Mock | 正常工作 |
| COMP-CL-009 | Spring RestTemplate | 5.x/6.x | HTTP Mock | 正常工作 |
| COMP-CL-010 | Spring WebClient | 5.x/6.x | HTTP Mock | 正常工作 |
| COMP-CL-011 | OpenFeign | latest | HTTP Mock | 正常工作 |
| COMP-CL-012 | Java HttpURLConnection | JDK 内置 | HTTP Mock | 正常工作 |

### 10.3 数据库兼容性

| 用例 ID | 数据库 | 版本 | 测试范围 | 预期结果 |
|---------|--------|------|---------|---------|
| COMP-DB-001 | PostgreSQL | 12+ | CRUD | 正常工作 |
| COMP-DB-002 | H2 | 2.x | CRUD | 正常工作 |

### 10.4 OS 兼容性

| 用例 ID | 操作系统 | 测试范围 | 预期结果 |
|---------|---------|---------|---------|
| COMP-OS-001 | Linux (Ubuntu 22.04) | 全流程 | 正常工作 |
| COMP-OS-002 | macOS 13+ | 全流程 | 正常工作 |
| COMP-OS-003 | Windows 11 | 全流程 | 正常工作 |

---

## 11. 测试数据与规则模板

### 11.1 HTTP 规则模板

```yaml
rules:
  - id: http-basic-get
    name: "GET users by id"
    protocol: http
    host: "*"
    port: 0
    conditions:
      - type: method
        operator: equals
        value: GET
      - type: path
        operator: equals
        value: "/api/users/{id}"
    responses:
      - status: 200
        headers:
          Content-Type: "application/json; charset=UTF-8"
        body: '{"id":"{{path.id}}","name":"{{faker.name}}","phone":"{{faker.phone}}"}'
        delay:
          type: fixed
          ms: 100

  - id: http-multi-branch
    name: "Multi-branch response"
    protocol: http
    conditions:
      - type: method
        operator: equals
        value: POST
      - type: path
        operator: equals
        value: "/api/orders"
    responses:
      - condition:
          bodyJsonPath:
            key: "$.type"
            value: "VIP"
        status: 200
        body: '{"type":"VIP","discount":0.8}'
      - condition:
          bodyJsonPath:
            key: "$.type"
            value: "detail"
        status: 200
        body: '{"type":"detail","info":"..."}'
      - status: 200
        body: '{"type":"default"}'

  - id: http-gbk-encoding
    name: "GBK encoding response"
    protocol: http
    conditions:
      - type: path
        operator: equals
        value: "/api/chinese"
    responses:
      - status: 200
        headers:
          Content-Type: "text/plain; charset=GBK"
        body: "中文GBK编码测试"
        charset: GBK

  - id: http-fault-injection
    name: "Fault injection"
    protocol: http
    conditions:
      - type: path
        operator: equals
        value: "/api/error"
    responses:
      - status: 200
        body: "{}"
    faultInjection:
      faults:
        - type: HTTP_ERROR
          probability: 0.3
          statusCodes: [502, 503]
        - type: DELAY
          probability: 0.5
          delayMs: 500

  - id: http-stateful-counter
    name: "Stateful counter"
    protocol: http
    conditions:
      - type: path
        operator: equals
        value: "/api/stateful"
    responses:
      - condition:
          requestCount: ">=3"
        status: 200
        body: "{\"count\":\"3+\"}"
      - status: 200
        body: "{\"count\":\"<3\"}"
    autoResetThreshold: 10
```

### 11.2 TCP 规则模板

```yaml
rules:
  - id: tcp-prefix-hex
    name: "Prefix hex match"
    protocol: tcp
    conditions: []
    tcpPrefixHex: "01020304"
    responses:
      - body: "05060708"

  - id: tcp-multi-round
    name: "Multi-round interaction"
    protocol: tcp
    conditions: []
    tcpRounds:
      - requestHex: "0001"
        responseHex: "A001"
      - requestHex: "0002"
        responseHex: "A002"
      - requestHex: "0003"
        responseHex: "A003"
    tcpLoop: true

  - id: tcp-offset-match
    name: "Offset match"
    protocol: tcp
    conditions: []
    tcpOffsetStart: 4
    tcpOffsetEnd: 6
    tcpOffsetHex: "0001"
    responses:
      - body: "OK"
```

### 11.3 Kafka 规则模板

```yaml
rules:
  - id: kafka-produce-mock
    name: "Kafka produce mock"
    protocol: kafka
    conditions:
      - type: topic
        operator: equals
        value: "test-topic"
    responses:
      - body: "{\"key\":\"{{faker.uuid}}\",\"value\":\"test message\"}"
        delay: 100

  - id: kafka-consume-mock
    name: "Kafka consume mock"
    protocol: kafka
    conditions:
      - type: topic
        operator: equals
        value: "test-topic"
    responses:
      - body: "{\"offset\":0,\"key\":\"k1\",\"value\":\"message-1\"}"
      - body: "{\"offset\":1,\"key\":\"k2\",\"value\":\"message-2\"}"
      - body: "{\"offset\":2,\"key\":\"k3\",\"value\":\"message-3\"}
```

### 11.4 gRPC 规则模板

```yaml
rules:
  - id: grpc-unary-mock
    name: "gRPC unary mock"
    protocol: grpc
    conditions:
      - type: path
        operator: equals
        value: "/com.baafoo.test.UserService/GetUser"
    responses:
      - status: 0
        body: "{\"id\":\"1\",\"name\":\"Alice\"}"
        delay: 100
```

### 11.5 测试环境 Docker Compose 模板

```yaml
version: '3.8'
services:
  baafoo-server:
    image: baafoo/server:latest
    ports:
      - "9000-9005:9000-9005"
      - "8080:8080"  # REST API
    environment:
      - BAAFOO_DB_URL=jdbc:postgresql://postgres:5432/baafoo
      - BAAFOO_DB_USER=baafoo
      - BAAFOO_DB_PASS=baafoo
    depends_on:
      - postgres

  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: baafoo
      POSTGRES_USER: baafoo
      POSTGRES_PASSWORD: baafoo
    volumes:
      - pgdata:/var/lib/postgresql/data

  test-app:
    image: baafoo/test-app:latest
    environment:
      - JAVA_TOOL_OPTIONS=-javaagent:/opt/baafoo/baafoo-agent.jar=baafoo.agent.server-url=http://baafoo-server:8080,baafoo.agent.environment=ft-1
    depends_on:
      - baafoo-server

  consul:
    image: consul:1.15
    ports:
      - "8500:8500"

volumes:
  pgdata:
```

---

## 附录 A: 测试用例统计

| 测试层级 | 用例数 |
|---------|--------|
| 单元测试 (baafoo-core) | ~85 |
| 单元测试 (baafoo-agent) | ~40 |
| 单元测试 (baafoo-server) | ~100 |
| 单元测试 (baafoo-plugin-api) | ~8 |
| 单元测试 (baafoo-cli) | ~16 |
| 集成测试 (HTTP) | ~30 |
| 集成测试 (TCP) | ~13 |
| 集成测试 (Kafka) | ~18 |
| 集成测试 (Pulsar) | ~16 |
| 集成测试 (JMS) | ~8 |
| 集成测试 (gRPC) | ~10 |
| 集成测试 (Consul) | ~7 |
| 集成测试 (控制通道) | ~10 |
| 集成测试 (模式切换) | ~12 |
| 集成测试 (录制回放) | ~10 |
| E2E (Docker Compose) | ~13 |
| E2E (Spring Boot) | ~12 |
| E2E (Web 控制台) | ~32 |
| RBAC 权限测试 | ~20 |
| 故障注入测试 | ~17 |
| 边界与异常测试 | ~40 |
| 性能与压力测试 | ~20 |
| 兼容性测试 | ~20 |
| **总计** | **~519** |

## 附录 B: AC 覆盖追踪

| PRD AC 编号 | 覆盖用例 ID（示例） |
|------------|---------------------|
| R-S1 AC-01 (5 分钟搭建) | E2E-DC-001 |
| R-S1 AC-02 (5 种协议) | E2E-DC-002 |
| R-S1 AC-03 (多环境隔离) | E2E-DC-003 |
| R-S2 AC-01 (精确匹配) | UT-ME-001, IT-HTTP-001 |
| R-S2 AC-02 (路径参数) | UT-ME-002, IT-HTTP-003 |
| R-S2 AC-03 (JSONPath) | UT-ME-006, IT-HTTP-006 |
| R-S2 AC-04 (Query) | UT-ME-004, IT-HTTP-004 |
| R-S2 AC-05 (Header) | UT-ME-005, IT-HTTP-005 |
| R-S2 AC-06 (多分支) | UT-ME-010, IT-HTTP-007 |
| R-S2 AC-07 (延迟) | IT-HTTP-009/010/011 |
| R-S2 AC-08 (多编码) | IT-HTTP-012/013/014 |
| R-S2 AC-13 (状态计数器) | UT-ME-029, IT-HTTP-015 |
| R-S2 AC-14 (GraphQL) | UT-ME-024/025, IT-HTTP-018/019 |
| R-S2 AC-15 (请求日志) | IT-HTTP-027 |
| R-S2 AC-16 (HAR 导出) | IT-HTTP-028 |
| R-S3 AC-01~05 (TCP) | UT-TS-001~008, IT-TCP-001~012 |
| R-S4 AC-01~10 (Kafka) | UT-KB-001~015, IT-KAFKA-001~018 |
| R-S5 AC-01~10 (Pulsar) | UT-PB-001~014, IT-PULSAR-001~016 |
| R-S6 AC-01~06 (JMS) | UT-JB-001~007, IT-JMS-001~008 |
| R-S7 AC-01~08 (REST API) | UT-RA-001~012 |
| R-S7.1 AC-01~03 (规则冲突) | UT-RA-013~015 |
| R-S7.2 AC-01~06 (Agent API) | UT-AA-001~007, IT-CC-001~010 |
| R-S7.3 AC-01~10 (环境 API) | UT-EA-001~011, IT-ENV-001~012 |
| R-S7.4 AC-02~03 (版本历史) | UT-RA-016/017 |
| R-S7.5 AC-01~14 (场景集) | UT-SA-001~014 |
| R-S7.7 AC-01~11 (认证) | UT-AU-001~017, RBAC-001~020 |
| R-S8 AC-01~06 (录制回放) | UT-RC-001~006, IT-REC-001~010 |
| R-S12 AC-01~06 (故障注入) | FI-HTTP-001~011, FI-KAFKA-001~006 |
| R-A1 AC-01~03 (Agent 生命周期) | UT-AG-001~007, E2E-SB-012 |
| R-A2 AC-01 (BIO Socket) | IT-TCP-001 |
| R-A3 AC-01~03 (NIO Socket) | IT-TCP-002, IT-TCP-011 |
| R-A4 AC-01 (Kafka 拦截) | IT-KAFKA-017/018 |
| R-A5 AC-01~02 (Pulsar 拦截) | IT-PULSAR-015 |
| R-A6 AC-01~02 (JMS 拦截) | IT-JMS-008 |
| R-A7 AC-01~02 (DNS 拦截) | IT-CONSUL-002/003 |
| R-A8 AC-01~09 (框架兼容) | E2E-SB-001~009 |
| R-A9 AC-01~08 (路由表) | UT-RT-001~010, IT-CONSUL-001~007 |
| R-C2 AC-01 (Faker 种子) | UT-TE-015, IT-HTTP-017 |
| R-W1~W7 (Web 控制台) | E2E-WEB-001~032 |

---

## 附录 C: 测试执行顺序

1. **Phase 1**: 单元测试 (baafoo-core) — 基础模型/引擎验证
2. **Phase 2**: 单元测试 (baafoo-agent + baafoo-server) — 组件级验证
3. **Phase 3**: 集成测试 (HTTP + TCP) — 核心协议联调
4. **Phase 4**: 集成测试 (Kafka + Pulsar + JMS + gRPC) — MQ/gRPC 协议联调
5. **Phase 5**: 集成测试 (控制通道 + 模式切换 + 录制回放) — 系统级联调
6. **Phase 6**: RBAC + 故障注入 + 边界异常 — 安全与鲁棒性
7. **Phase 7**: E2E (Docker Compose + Spring Boot + Web) — 全链路验证
8. **Phase 8**: 性能压测 + 兼容性 — 非功能性验证

---

*文档结束*