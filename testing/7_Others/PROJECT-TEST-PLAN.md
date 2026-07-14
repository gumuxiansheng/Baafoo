# Baafoo 项目完整测试计划

**版本**: 2.0
**日期**: 2026-06-29
**项目**: Baafoo - JavaAgent-Based API Mock Platform

**版本更新记录**：
- v2.10 (2026-07-13): MULTI-002~008 多 Agent 共存测试——新增 docker-compose.multi-agent.yml 叠加文件（SkyWalking OAP 9.4.0 + agent JAR 挂载）、Dockerfile.multi-agent（三 Agent 启动：JaCoCo→SkyWalking→Baafoo）。test-fullchain.ps1/sh 新增 MULTI 段 8 个用例（MULTI-001~008），通过 MULTI_AGENT_ENABLED=1 环境变量启用。覆盖：三 Agent 启动健康检查、Baafoo Mock 拦截兼容性、SkyWalking OAP 服务注册、JaCoCo classdumps 生成、Feign trace 可见性、加载顺序变体 A、性能影响评估、字节码转换冲突检测。
- v2.11 (2026-07-14): 企业级应用测试扩充——新增 Nacos（EG-NACOS-001~012，12 用例）和 Spring Cloud Gateway（EG-GW-001~012，12 用例）两个应用。Nacos 覆盖服务注册/发现/配置 API Mock、Passthrough 透传、Record 录制、模式热切换。Gateway 覆盖网关层 Mock、后端 Mock、全链路透传、过滤器链兼容、多 Agent 环境隔离。enterprise-env.ps1 和 run-all-smoke-tests.ps1 默认列表新增 nacos、spring-cloud-gateway。
- v2.9 (2026-07-13): P0/P1 缺口补全——FLT-001/002 故障注入独立断言（延迟 >= 1800ms + statusCode=500）、IT-L2 协议集成补全（HTTP-010 Passthrough、TCP-004 Regex/005 多轮/006 长连接、KAFKA-004 通配符/005 Header/006-008 Metadata+Produce+Fetch、PULSAR-004 Topic/005 通配符、JMS-003/004 Topic 发布订阅、GRPC-005 Header/007 Status Code/008 错误码/009 延迟/010 帧格式、CONSUL-002 HTTP API）。新增 7 个规则文件：http-fault-delay/500、kafka-metadata、jms-topic-test、grpc-header-match/status-code/delay-1s。JmsCallerService/Controller 新增 Topic 发布订阅端点。
- v2.8 (2026-07-12): §6.4.4 P2 缺口全部从 SKIP 改为真实断言——场景集 CRUD (SCN-001~004)、MCP JSON-RPC (MCP-001~003)、混沌 profile 状态 (FLT-003)、有状态 Mock 计数器 (FLT-004)、Consul DNS (CONS-001)、fail-open 透传 (FO-001)、继承环境 (INH-001)、规则分页 (PAG-001)、规则优先级 (PRIO-001)、多响应分支 (MULTI-001)、标签筛选 (TAG-001) 全部在 test-fullchain.ps1 中实现真实 API 调用与断言。新增 6 个规则文件：http-priority-high/low、http-multi-response、http-tagged-1/2、http-stateful。
- v2.7 (2026-07-12): MX 矩阵 RECORD/RECORD_ALL/RECORD_AND_STUB 补缺——test-fullchain.ps1 中 MX-TCP/KAFKA/JMS/PULSAR 的 RECORD、RECORD_ALL、TCP RECORD_AND_STUB 从 SKIP 改为真实断言（含录制计数与协议匹配验证）。
- v2.5 (2026-07-11): 新增 §6.4.5 多编码（Multi-charset）测试用例定义——CH01–CH03 三个全链路用例验证 `Rule.requestCharset` 请求侧 GBK 解码 + `ResponseEntry.charset` 响应侧 GBK 编码 + 模板渲染；扩展 test-spring 新增 `SocketCallerService.testBioSocketWithCharset` 与 `KafkaCallerService.sendMessageWithCharset`（用 `ByteArraySerializer` 保留原始 charset 字节）+ 对应 `/api/socket/bio-charset`、`/api/kafka/send-charset` 端点；新增 `tcp-charset-gbk.json`、`kafka-charset-gbk.json` 两个 GBK 规则文件；用例分组新增 `CH`(多编码/字符集)。
- v2.6 (2026-07-11): P0 修复——C11 全局规则 priority 100→50 确保优先于环境 catch-all；PL01 日志获取从 docker logs 改为 API /api/agents pluginStatuses；MX 矩阵 PASSTHROUGH 补缺——docker-compose.staging.yml 新增 kafka-broker（Bitnami KRaft）、jms-broker（ActiveMQ Artemis）、tcp-echo-server（socat）三个真实 broker 容器，test-fullchain.ps1 中 MX-TCP/KAFKA/JMS-PT-001 从 SKIP 改为真实 PASSTHROUGH 断言（含 broker 健康检查与独立模式切换），Pulsar 仍 SKIP。
- v2.4 (2026-07-08): 全链路测试 hermetic 化——移除全部公网 `http://httpbin.org` 依赖（~23 处），改为 Staging 内置 `real-backend` echo 端点（app-env-a 网络别名 + `BackendEchoController`），PASSTHROUGH/RECORD 的"真实后端"验证不再依赖公网，离线/公网抖动均可跑；测试脚本（ps1/sh/run-fullchain-tests.sh）导出 JUnit 兼容 `junit-report.xml`，并新增 `.github/workflows/system-test.yml` 接 GitHub Actions CI（上传 + test-reporter 发布）；规则注册改为 upsert 根治 PostgreSQL 卷 stale 规则，catch-all 规则 `staging-a-http` 现也由测试脚本统一管理。
- v2.3 (2026-07-08): §6.4 不再只标注缺口——补写全部缺失用例的具体定义：§6.4.2.1 协议×模式矩阵 18 个未覆盖组合（TCP/Kafka/Pulsar/JMS 非 STUB 模式 + gRPC 全列）逐条用例；§6.4.3.1 已补充能力的逐步断言；§6.4.4 其余 P2 缺口（场景集/MCP/故障注入/Consul DNS/fail-open/继承环境/规则分页/优先级/多响应/tags）具体用例。每个缺口用例均含前置条件/步骤/预期/脚本状态。
- v2.2 (2026-07-07): §6.4 全链路测试重构——补充协议×模式覆盖矩阵、断言红线（禁止 `|mocked` 兜底伪通过、失败态必须判 FAIL、计数器前置重置、模式切换等待）、规则集/录制管理/撤销重置/OpenAPI 导入用例；标注 gRPC 与真实 MQ broker 缺口
- v2.1 (2026-07-04): §10.7 多 Agent 共存兼容性扩展：合并 JaCoCo + SkyWalking + Baafoo 三 Agent 组合详细测试方案（MULTI-001~008），含测试矩阵、环境配置、JVM 启动命令、验收标准、风险缓解、执行流程；更新 COMP-AGENT 矩阵增加版本号和测试状态
- v2.0 (2026-06-29): 全方位查漏补缺：新增变异测试(§5.6)、安全测试(§15)、文档/API契约测试(§16)、客户端SDK测试(§8.9)、代理Sidecar测试(§8.10)；补全缺失的服务端Handler/Auth/Storage/MCP/API单元测试；新增L4层Testcontainers集成测试、gRPC服务端流测试、协议TLS/SASL兼容性、虚拟线程兼容性、ARM64架构兼容性、资源泄漏专项(文件描述符/线程泄漏/Netty堆外内存)；前端新增组件单元测试、响应式测试、可访问性测试、视觉回归测试；整合已知P0问题验证用例(代码审查报告的3个P0 + 5个P1)
- v1.4 (2026-06-24): 企业级应用测试按行业领域重新分类，新增金融/互联网/政务信创/工业物联网/电商零售/医疗等行业测试应用，扩展多Agent共存测试
- v1.3 (2026-06-24): 新增 gRPC 协议支持测试内容，包括集成测试、功能测试、性能测试、兼容性测试
- v1.2 (2026-06-24): 新增企业级应用测试计划章节（第8章），补充应用清单、Docker化方案、执行计划
- v1.1 (2026-06-24): 初始版本，涵盖单元测试、集成测试、功能测试、性能测试、兼容性测试、稳定性测试、前端测试

---

## 目录

1. [测试计划概述](#1-测试计划概述)
2. [仿真测试计划适配性评估](#2-仿真测试计划适配性评估)
3. [测试策略](#3-测试策略)
4. [测试环境](#4-测试环境)
5. [单元测试计划](#5-单元测试计划)
    - 5.6 [变异测试（Mutation Testing）](#56-变异测试mutation-testing)
6. [集成测试计划](#6-集成测试计划)
    - 6.4 [L4 - Testcontainers 集成测试](#64-l4---testcontainers-集成测试)
7. [功能测试计划](#7-功能测试计划)
    - 7.7 [安全与认证鉴权功能](#77-安全与认证鉴权功能)
    - 7.8 [MCP Server 功能](#78-mcp-server-功能)
    - 7.9 [事件总线功能](#79-事件总线功能)
8. [企业级应用测试计划](#8-企业级应用测试计划)
    - 8.9 [客户端 SDK 测试](#89-客户端-sdk-测试)
    - 8.10 [Go 代理 Sidecar 测试](#810-go-代理-sidecar-测试)
9. [性能测试计划](#9-性能测试计划)
10. [兼容性测试计划](#10-兼容性测试计划)
11. [稳定性测试计划](#11-稳定性测试计划)
    - 11.7 [资源泄漏专项检查](#117-资源泄漏专项检查)
12. [前端测试计划](#12-前端测试计划)
    - 12.1 [前端单元测试（Vitest + Vue Test Utils）](#121-前端单元测试vitest--vue-test-utils)
    - 12.2 [前端 E2E 测试](#122-前端-e2e-测试)
    - 12.3 [前端响应式与可访问性测试](#123-前端响应式与可访问性测试)
    - 12.4 [前端视觉回归测试](#124-前端视觉回归测试)
    - 12.5 [前端兼容性](#125-前端兼容性)
    - 12.6 [前端执行方式](#126-前端执行方式)
13. [安全测试计划](#13-安全测试计划)
14. [文档与API契约测试](#14-文档与api契约测试)
15. [测试执行计划](#15-测试执行计划)
16. [风险与应对](#16-风险与应对)
17. [测试交付物](#17-测试交付物)

---

## 1. 测试计划概述

### 1.1 项目背景

Baafoo 是一个基于 JavaAgent 字节码增强技术的零侵入微服务挡板系统，通过 Byte Buddy 实现对应用网络调用的拦截和 Mock。项目采用 Maven 多模块架构，核心模块包括：

| 模块 | 职责 | 技术栈 |
|------|------|--------|
| [baafoo-core](../baafoo-core) | 核心模型、配置解析、规则匹配引擎 | Jackson, SnakeYAML, SLF4J |
| [baafoo-plugin-api](../baafoo-plugin-api) | Plugin SPI 接口定义 | 纯接口，零依赖 |
| [baafoo-agent](../baafoo-agent) | JavaAgent 字节码增强模块 | Byte Buddy, Maven Shade |
| [baafoo-server](../baafoo-server) | 多协议挡板服务 + Web 控制台 | Netty 4.1, MyBatis, H2/PostgreSQL |
| [baafoo-cli](../baafoo-cli) | 命令行工具 | 纯 Java |
| [web](../web) | Web 控制台前端 | Vue 3, Element Plus, Vite |

### 1.2 协议支持现状

| 协议 | 状态 | Agent 拦截点 | Server 实现 |
|------|------|-------------|------------|
| HTTP/1.1 | ✅ 完全支持 | OkHttp/Feign/URLConnection | HttpStubHandler |
| gRPC | ✅ 支持 | Socket 层重定向（HTTP/1.1 传输） | GrpcStubHandler |
| TCP Socket | ✅ 完全支持 | BIO + NIO 双模式 | TcpStubHandler |
| Kafka | ✅ 支持 | Producer + Consumer | KafkaMockBroker |
| Pulsar/TDMQ | ✅ 支持 | PulsarClient | PulsarMockBroker |
| JMS | ✅ 支持 | ConnectionFactory | JmsMockBroker |
| Service-Name DNS | ✅ 支持 | InetAddress | DnsResolveAdvice |
| Service-Name HTTP | ✅ 支持 | OkHttp | HttpOpenServerAdvice |

### 1.3 测试目标

1. **功能正确性**：验证所有协议的拦截与 Mock 功能符合预期
2. **规则引擎正确性**：验证规则匹配、优先级、条件组合的正确性
3. **Agent 稳定性**：确保 Agent 在各种场景下不崩溃、不内存泄漏
4. **Server 可靠性**：确保多协议 Server 在高并发下稳定运行
5. **插件系统健壮性**：验证 Plugin SPI 的加载、隔离、健康监控机制
6. **企业级应用兼容性**：在真实企业级应用中验证 Agent 的稳定性和有效性
7. **前端功能完整**：Web 控制台所有页面功能正常
8. **已知问题验证**：验证代码审查报告中 3 个 P0 问题（BUG-4 TcpStubHandler 阻塞 Netty、THREAD-1 RouteManager 非线程安全、SEC-1 SSL 验证跳过）的修复状态，用例关联到稳定性/兼容性测试

### 1.4 测试范围

**包含**：
- 所有核心模块的单元测试
- Agent 字节码增强集成测试
- 全协议端到端功能测试
- 企业级应用集成验证
- 性能基线与 Agent 性能影响评估
- JDK 版本与操作系统兼容性
- 72小时稳定性测试
- 前端 E2E 测试
- 插件系统测试

**不包含**：
- 第三方依赖库本身的测试
- 用户环境的网络基础设施测试
- Dubbo/Spring Cloud 框架级集成（项目是协议级拦截）

---

## 2. 仿真测试计划适配性评估

### 2.1 总体评估结论

**适配度：约 60%**。仿真测试计划的框架思路（对照测试法、分层测试、四大维度）具有参考价值，但针对 Baafoo 项目的实际情况需要较大调整。

### 2.2 可直接复用的部分

| 仿真计划内容 | 适配情况 | 说明 |
|-------------|---------|------|
| 对照测试法（有/无 Agent 对比） | ✅ 完全适配 | 核心测试方法论，适用于性能和功能验证 |
| 功能测试维度（协议拦截与 Mock） | ✅ 基本适配 | 需调整协议列表（增加 Consul 和 gRPC） |
| 性能测试维度（QPS/RT/CPU/内存） | ✅ 完全适配 | 指标定义合理，可直接复用 |
| 兼容性测试（JDK 版本） | ✅ 基本适配 | 需聚焦 JDK 8/11/17（21优先级低） |
| 稳定性测试（长时间运行） | ✅ 完全适配 | 方法论通用 |
| 多 Agent 共存测试 | ✅ 部分适配 | 与 JaCoCo 共存必须验证（项目自身使用 JaCoCo） |
| 企业级应用测试 | ✅ 部分适配 | 需替换为更贴合项目的应用清单 |

### 2.3 需要调整的部分

| 仿真计划内容 | 问题 | 调整方案 |
|-------------|------|---------|
| gRPC 协议 | Baafoo 不支持 gRPC | 移除，替换为 Consul DNS/HTTP |
| Spring Cloud / Dubbo 框架 | Baafoo 是协议级拦截，非框架级 | 调整为 HTTP 客户端（OkHttp/Feign/RestTemplate） |
| JUnit 5 / TestNG | 项目使用 JUnit 4 + Mockito | 保持 JUnit 4，后续可考虑升级 |
| Gatling / JProfiler | 项目无此工具栈 | 使用 JMeter（或简单 wrk/ab）+ VisualVM |
| PetClinic / Keycloak / SonarQube 被测应用 | 部分应用与项目关联度低 | 精选更有代表性的企业级应用（详见第8章） |
| SOFAJRaft 高负载测试 | 项目无此场景 | 替换为 Netty 高并发压测 |
| 6周时间线 | 过于冗长 | 调整为6周（增加企业级应用测试） |

### 2.4 需要补充的部分

仿真计划缺失但 Baafoo 项目必须的测试：

| 缺失项 | 重要性 | 说明 |
|-------|-------|------|
| 规则匹配引擎测试 | P0 | MatchEngine 的各种匹配条件、优先级、组合逻辑 |
| 环境模式切换测试 | P0 | stub/passthrough/record/record-and-stub 四种模式 |
| 插件系统测试 | P1 | Plugin SPI 加载、ClassLoader 隔离、健康监控 |
| 录制回放功能测试 | P1 | 录制、查询、回放全流程 |
| 前端 E2E 测试 | P1 | Web 控制台功能完整性 |
| 数据持久化测试 | P1 | H2/PostgreSQL 双存储适配 |
| MCP Server 测试 | P2 | MCP 工具接口正确性 |
| 故障注入/混沌工程测试 | P2 | FaultInjector、ChaosManager 功能 |
| 企业级应用集成验证 | P1 | 在真实企业应用中验证 Agent 表现 |

---

## 3. 测试策略

### 3.1 测试金字塔

```
        /\
       /  \        E2E 测试 (前端 + 全链路集成 + 企业级应用)
      /    \
     /      \      集成测试 (Agent 挂载 + 协议拦截 + Server 联调)
    /--------\
   /          \     单元测试 (核心逻辑 + 工具类 + 模型)
  /            \
 /              \
```

### 3.2 对照测试法

**核心方法论**：所有性能和功能对比测试均采用"有 Agent vs 无 Agent"对照设计。

| 组别 | 配置 | 用途 |
|------|------|------|
| 对照组 | 不挂载 Agent，应用直接运行 | 建立性能基线，验证测试应用本身正常 |
| 实验组 | 挂载 Agent，stub 模式 | 评估 Agent 性能开销，验证 Mock 功能 |
| 实验组2 | 挂载 Agent，passthrough 模式 | 评估仅拦截不 Mock 的性能开销 |
| 实验组3 | 挂载 Agent，record 模式 | 评估录制功能的性能开销 |

### 3.3 测试分层

| 层级 | 测试对象 | 工具 | 执行频率 | 负责人 |
|------|---------|------|---------|-------|
| 单元测试 | 单个类/方法 | JUnit 4 + Mockito | 每次提交 | 开发 |
| 模块集成测试 | 模块间交互 | JUnit 4 + 测试容器 | 每日构建 | 开发 |
| 全链路集成测试 | Agent + Server + 被测应用 | Docker Compose + PowerShell | 每周 | 测试 |
| 企业级应用验证 | 真实企业应用 + Agent | Docker + 手动验证 | 里程碑 | 测试 |
| 性能测试 | 吞吐量/响应时间/资源占用 | JMeter + JVisualVM | 里程碑 | 测试 |
| E2E 测试 | 前端 + 后端全流程 | Playwright | 每周 | 测试 |

### 3.4 优先级定义

| 优先级 | 定义 | 通过率要求 |
|-------|------|-----------|
| P0 | 核心功能，不通过则版本不可发布 | 100% |
| P1 | 重要功能，少量失败可接受但需跟踪 | ≥ 95% |
| P2 | 辅助功能，允许阶段性失败 | ≥ 80% |

---

## 4. 测试环境

### 4.1 硬件环境

| 环境类型 | 配置 | 用途 |
|---------|------|------|
| 开发机 | 4核/8GB/SSD | 单元测试、本地集成测试 |
| CI 服务器 | 8核/16GB/SSD | 全量单元测试、构建验证 |
| 性能测试机 | 8核/16GB/SSD（或同等配置云主机） | 性能压测、稳定性测试 |
| 企业级应用测试机 | 8核/32GB/SSD | 企业级应用集成验证 |

### 4.2 软件环境

| 软件 | 版本 | 说明 |
|------|------|------|
| JDK | 8 / 11 / 17 | 兼容性测试覆盖三个 LTS 版本 |
| Maven | 3.8+ | 构建工具 |
| Docker | 20.10+ | 集成测试环境隔离 |
| Docker Compose | 2.0+ | 多容器编排 |
| PostgreSQL | 15 | 生产级数据库测试 |
| H2 | 2.2.224 | 内嵌数据库（默认） |
| Node.js | 16+ | 前端构建与测试 |
| Playwright | 1.61+ | 前端 E2E 测试 |
| JMeter | 5.6+ | 性能压测（可选，可用 wrk 替代） |

### 4.3 测试应用

#### 项目自带测试应用

| 测试应用 | 用途 | 覆盖协议 |
|---------|------|---------|
| [baafoo-test-spring](../baafoo-test-spring) | 主要被测应用（Spring Boot 2.7） | HTTP, TCP, Kafka, Pulsar, JMS, Feign |
| [baafoo-test-app](../baafoo-test-app) | 轻量级 CLI 测试应用 | 基础协议验证 |
| [baafoo-test-pulsar](../baafoo-test-pulsar) | Pulsar 专项测试 | Pulsar |

#### 企业级测试应用（第8章详述）

| 应用类别 | 代表应用 | 覆盖协议 | 测试重点 |
|---------|---------|---------|---------|
| 微服务网关 | Spring Cloud Gateway | HTTP | 复杂过滤器链、高并发 |
| 服务注册中心 | Nacos | HTTP | 配置中心、服务发现 |
| 消息中间件 | Kafka / RocketMQ | Kafka/TCP | 高吞吐、长连接 |
| 单体企业应用 | Spring Boot Admin | HTTP | 管理后台、定时任务 |
| 工作流引擎 | Flowable / Activiti | HTTP + JDBC | 复杂业务逻辑 |
| API 管理平台 | Apianno（同项目组） | HTTP | 真实业务场景验证 |

### 4.4 Docker 测试环境

已有的 Staging 环境配置：[docker-compose.staging.yml](../docker-compose.staging.yml)

```
┌─────────────────────────────────────────────────┐
│           baafoo-staging-net                    │
│                                                 │
│  ┌──────────────┐     ┌──────────────┐         │
│  │    server    │     │   postgres   │         │
│  │  :8084 API   │     │   :5432      │         │
│  │  :9000 HTTP  │     └──────────────┘         │
│  │  :9001 TCP   │                              │
│  │  :9002 Kafka │    ┌──────────────┐          │
│  │  :9003 Pulsar│    │  app-env-a   │          │
│  │  :9004 JMS   │    │  :9090       │          │
│  └──────────────┘    │  + Agent     │          │
│                      └──────────────┘          │
│                      ┌──────────────┐          │
│                      │  app-env-b   │          │
│                      │  :9091       │          │
│                      │  + Agent     │          │
│                      └──────────────┘          │
└─────────────────────────────────────────────────┘
```

---

## 5. 单元测试计划

### 5.1 现状分析

当前单元测试分布（约 67 个测试文件）：

| 模块 | 测试文件数 | 覆盖率（估算） | 覆盖率目标 |
|------|-----------|--------------|-----------|
| baafoo-core | 24 | ~70% | ≥ 85% |
| baafoo-agent | 18 | ~50% | ≥ 70% |
| baafoo-server | 14 | ~40% | ≥ 65% |
| baafoo-plugin-api | 3 | ~80% | ≥ 90% |
| baafoo-cli | 1 | ~30% | ≥ 50% |
| baafoo-test-spring | 1 | ~20% | ≥ 50% |
| baafoo-testcontainers | 1 | ~30% | ≥ 50% |
| baafoo-spring-boot-starter-test | 1 | ~30% | ≥ 50% |
| baafoo-example-plugins | 3 | ~40% | ≥ 60% |
| 总体 | ~67 | ~55% | ≥ 75% |

> **注**: §5.6 变异测试已实现在线配置，参见父 POM `pluginManagement` 中的 `pitest-maven` 插件。

### 5.2 单元测试覆盖目标

| 模块 | 行覆盖率目标 | 变异覆盖率目标 | 重点覆盖内容 |
|------|------------|---------------|-------------|
| baafoo-core | ≥ 85% | ≥ 70% | MatchEngine, TemplateEngine, FaultInjector, ChaosManager, EventBus, JsonPathUtil, GrpcCodecUtils |
| baafoo-agent | ≥ 70% | ≥ 55% | RouteTable, RouteManager, RoutingContext, PluginManager, ControlChannel, RecordingBuffer, TransformRegistry |
| baafoo-server | ≥ 65% | ≥ 50% | HttpStubHandler, TcpStubHandler, KafkaMockBroker, StorageService, AuthService, AuthFilter, GrpcUnifiedHandler, GrpcResponseBuilder, PassthroughProxy, JdbcStorageService, McpToolRegistry, API Handlers |
| 总体 | ≥ 75% | ≥ 60% | - |

### 5.3 核心单元测试用例列表

#### 5.3.1 baafoo-core - 规则匹配引擎

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-CORE-001 | MatchEngine - path equals 匹配 | P0 | 已有 |
| UT-CORE-002 | MatchEngine - path startsWith 匹配 | P0 | 已有 |
| UT-CORE-003 | MatchEngine - path contains 匹配 | P1 | 待补充 |
| UT-CORE-004 | MatchEngine - path endsWith 匹配 | P1 | 待补充 |
| UT-CORE-005 | MatchEngine - path regex 匹配 | P1 | 待补充 |
| UT-CORE-006 | MatchEngine - method 匹配 | P0 | 待补充 |
| UT-CORE-007 | MatchEngine - header equals 匹配 | P0 | 待补充 |
| UT-CORE-008 | MatchEngine - header exists 匹配 | P1 | 待补充 |
| UT-CORE-009 | MatchEngine - query param 匹配 | P0 | 待补充 |
| UT-CORE-010 | MatchEngine - body contains 匹配 | P0 | 待补充 |
| UT-CORE-011 | MatchEngine - body jsonPath 匹配 | P1 | 已有 |
| UT-CORE-012 | MatchEngine - 多条件 AND 组合 | P0 | 待补充 |
| UT-CORE-013 | MatchEngine - 规则优先级排序 | P0 | 待补充 |
| UT-CORE-014 | MatchEngine - 禁用规则不匹配 | P0 | 待补充 |
| UT-CORE-015 | MatchEngine - case insensitive 匹配 | P1 | 待补充 |
| UT-CORE-016 | TemplateEngine - 变量替换 | P1 | 已有 |
| UT-CORE-017 | TemplateEngine - Faker 集成 | P2 | 待补充 |
| UT-CORE-018 | FakerIncrement - 递增字段 | P2 | 已有 |
| UT-CORE-019 | StatefulCounterStore - 有状态计数 | P2 | 已有 |
| UT-CORE-020 | FaultInjector - 延迟注入 | P1 | 已有 |
| UT-CORE-021 | FaultInjector - 异常注入 | P1 | 已有 |
| UT-CORE-022 | ChaosManager - 故障配置管理 | P2 | 已有 |
| UT-CORE-023 | JsonPathUtil - JSON 路径查询 | P1 | 已有 |
| UT-CORE-024 | IdGenerator - ID 生成唯一性 | P1 | 已有 |
| UT-CORE-025 | ConfigLoader - YAML 解析 | P0 | 已有 |
| UT-CORE-026 | OpenApiImporter - OpenAPI 导入 | P2 | 已有 |

#### 5.3.2 baafoo-agent - 字节码增强

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-AGENT-001 | RouteTable - 原子替换 | P0 | 已有 |
| UT-AGENT-002 | RouteTable - 并发读写安全 | P0 | 待补充 |
| UT-AGENT-003 | RouteManager - 路由决策逻辑 | P0 | 已有 |
| UT-AGENT-004 | RoutingContext - 上下文传递 | P1 | 已有 |
| UT-AGENT-005 | PluginManager - SPI 发现与加载 | P0 | 已有 |
| UT-AGENT-006 | PluginManager - 插件健康监控 | P1 | 已有 |
| UT-AGENT-007 | PluginClassLoader - 类隔离 | P1 | 已有 |
| UT-AGENT-008 | ControlChannel - 注册/心跳/轮询 | P0 | 已有 |
| UT-AGENT-009 | RecordingBuffer - 录制缓冲 | P1 | 已有 |
| UT-AGENT-010 | RecordingStream - 流录制 | P1 | 已有 |
| UT-AGENT-011 | GlobalRouteState - 全局状态管理 | P0 | 已有 |
| UT-AGENT-012 | TransformRegistry - 转换器注册 | P1 | 已有 |
| UT-AGENT-013 | AgentManifest - Bootstrap 类注入 | P2 | 已有 |
| UT-AGENT-014 | BaafooAgent - premain 入口 | P2 | 已有 |
| UT-AGENT-015 | KafkaProducerAdvice - 重定向逻辑 | P0 | 已有 |
| UT-AGENT-016 | PulsarClientAdvice - Lookup 拦截 | P0 | 已有 |

#### 5.3.3 baafoo-server - 挡板服务

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-SERVER-001 | HttpStubHandler - 基础 HTTP 响应 | P0 | 已有 |
| UT-SERVER-002 | TcpStubHandler - TCP 报文匹配 | P0 | 已有 |
| UT-SERVER-003 | KafkaMockBroker - Produce 处理 | P0 | 已有 |
| UT-SERVER-004 | KafkaMockBroker - Fetch 处理 | P0 | 待补充 |
| UT-SERVER-005 | KafkaMockBroker - Metadata 处理 | P0 | 待补充 |
| UT-SERVER-006 | PulsarMockBroker - 命令处理 | P0 | 已有 |
| UT-SERVER-007 | JmsMockBroker - Queue 消息 | P0 | 已有 |
| UT-SERVER-008 | JmsMockBroker - Topic 消息 | P1 | 待补充 |
| UT-SERVER-009 | FileStorage - 规则持久化 | P1 | 已有 |
| UT-SERVER-010 | JdbcStorageService - 数据库存储 | P1 | 待补充 |
| UT-SERVER-011 | ManagementApiHandler - REST API | P1 | 已有 |
| UT-SERVER-012 | StaticFileHandler - 静态资源 | P2 | 已有 |
| UT-SERVER-013 | KafkaFlexibleCodec - 协议编解码 | P1 | 已有 |
| UT-SERVER-014 | KafkaProtocolVersions - 版本协商 | P1 | 已有 |
| UT-SERVER-015 | MqMatchHelper - MQ 规则匹配 | P1 | 待补充 |
| UT-SERVER-016 | GrpcUnifiedHandler - HTTP/2 头部帧处理 | P0 | 待补充 |
| UT-SERVER-017 | GrpcUnifiedHandler - DATA 帧消息积累 | P0 | 待补充 |
| UT-SERVER-018 | GrpcUnifiedHandler - 环境模式分发 | P0 | 待补充 |
| UT-SERVER-019 | GrpcUnifiedHandler - 插件事件触发 | P2 | 待补充 |
| UT-SERVER-020 | GrpcResponseBuilder - 响应消息构建 | P0 | 待补充 |
| UT-SERVER-021 | GrpcResponseBuilder - grpc-status/message 提取 | P0 | 待补充 |
| UT-SERVER-022 | GrpcResponseBuilder - 大小写不敏感头部查找 | P1 | 待补充 |
| UT-SERVER-023 | PassthroughProxy - HTTP/HTTPS 转发 | P0 | 待补充 |
| UT-SERVER-024 | PassthroughProxy - SSL 验证禁用/启用 | P0 | 待补充 |
| UT-SERVER-025 | PassthroughProxy - Hop-by-hop 头部过滤 | P1 | 待补充 |
| UT-SERVER-026 | PassthroughProxy - URL 构建与协议判定 | P1 | 待补充 |
| UT-SERVER-027 | RecordingHelper - buildFromStub | P1 | 待补充 |
| UT-SERVER-028 | RecordingHelper - buildFromPassthrough | P1 | 待补充 |
| UT-SERVER-029 | RecordingHelper - buildError | P1 | 待补充 |
| UT-SERVER-030 | StubResponseRenderer - 模板渲染 | P1 | 待补充 |
| UT-SERVER-031 | StubResponseRenderer - Content-Type 解析 | P2 | 待补充 |
| UT-SERVER-032 | StubResponseRenderer - 404 响应生成 | P1 | 待补充 |
| UT-SERVER-033 | AgentResolver - 环境解析 | P1 | 待补充 |
| UT-SERVER-034 | MqRelationshipRenderer - 关系渲染 | P2 | 待补充 |

#### 5.3.4 baafoo-server - 认证与鉴权

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-AUTH-001 | AuthService.authenticate - 启用/禁用鉴权 | P0 | 待补充 |
| UT-AUTH-002 | AuthService.authenticate - 本地绕过 | P0 | 待补充 |
| UT-AUTH-003 | AuthService.authenticate - Bearer Token 验证 | P0 | 待补充 |
| UT-AUTH-004 | AuthService.authenticate - API Key 验证 | P0 | 待补充 |
| UT-AUTH-005 | AuthService.authenticate - 无凭证返回 Guest | P1 | 待补充 |
| UT-AUTH-006 | AuthService.validateJwtToken - 有效/过期/篡改 Token | P0 | 待补充 |
| UT-AUTH-007 | AuthService.login - 密码验证与 Token 签发 | P0 | 待补充 |
| UT-AUTH-008 | AuthService.login - Token 过期上限（7天） | P1 | 待补充 |
| UT-AUTH-009 | AuthService.hashPassword / verifyPassword | P0 | 待补充 |
| UT-AUTH-010 | AuthService.hasPermission - 全部 Role×Resource×Action 矩阵 | P0 | 待补充 |
| UT-AUTH-011 | AuthService.checkPermission - HTTP 方法映射 | P1 | 待补充 |
| UT-AUTH-012 | AuthService.inferResourceFromPath | P1 | 待补充 |
| UT-AUTH-013 | AuthService.validatePassword - 密码复杂度 | P1 | 待补充 |
| UT-AUTH-014 | AuthService.generateApiKey | P1 | 待补充 |
| UT-AUTH-015 | AuthFilter - 非 API 路径透传 | P0 | 待补充 |
| UT-AUTH-016 | AuthFilter - OPTIONS CORS 预检 | P0 | 待补充 |
| UT-AUTH-017 | AuthFilter - 401/403 响应 | P0 | 待补充 |
| UT-AUTH-018 | AuthFilter - 角色头部注入 | P1 | 待补充 |
| UT-AUTH-019 | AuthFilter - X-Forwarded-For 解析 | P1 | 待补充 |

#### 5.3.5 baafoo-server - 数据存储层

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-STORE-001 | JdbcStorageService.init - H2 初始化 | P0 | 待补充 |
| UT-STORE-002 | JdbcStorageService.init - PostgreSQL 初始化 | P1 | 待补充 |
| UT-STORE-003 | JdbcStorageService - 规则 CRUD（含缓存 TTL） | P0 | 待补充 |
| UT-STORE-004 | JdbcStorageService - 环境 CRUD（含缓存 TTL） | P0 | 待补充 |
| UT-STORE-005 | JdbcStorageService - 场景 CRUD + syncSceneEnvironmentsToRules | P0 | 待补充 |
| UT-STORE-006 | JdbcStorageService - MQ 关系 CRUD | P1 | 待补充 |
| UT-STORE-007 | JdbcStorageService - 录制 CRUD + trimRecordings | P1 | 待补充 |
| UT-STORE-008 | JdbcStorageService - Agent 注册/心跳 | P1 | 待补充 |
| UT-STORE-009 | JdbcStorageService - 用户 CRUD | P1 | 待补充 |
| UT-STORE-010 | JdbcStorageService - 级联删除（删除场景→环境同步） | P1 | 待补充 |
| UT-STORE-011 | JdbcStorageService - undoRule 快照恢复 | P1 | 待补充 |
| UT-STORE-012 | JdbcStorageService - getRecordingTotalSizeBytes | P2 | 待补充 |
| UT-STORE-013 | JdbcStorageService - isEnvironmentInheritedFromOtherScene | P1 | 待补充 |
| UT-STORE-014 | JdbcStorageService - resolvePath 占位符解析 | P2 | 待补充 |
| UT-STORE-015 | JdbcStorageService - 缓存失效验证 | P1 | 待补充 |
| UT-STORE-016 | StorageServiceFactory - H2/PostgreSQL 工厂方法 | P0 | 待补充 |
| UT-STORE-017 | FileStorage - 文件持久化 | P1 | 已有 |
| UT-STORE-018 | RecordingCleanupTask - 定期清理 | P2 | 待补充 |
| UT-STORE-019 | MyBatis TypeHandlers - JSON 序列化/反序列化 | P1 | 待补充 |

#### 5.3.6 baafoo-server - API Handlers

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-API-001 | ManagementApiHandler - 路由分发 | P0 | 已有 |
| UT-API-002 | RuleApiHandler - CRUD 全部端点 | P0 | 待补充 |
| UT-API-003 | EnvironmentApiHandler - CRUD + 模式切换 | P0 | 待补充 |
| UT-API-004 | RecordingApiHandler - 列表/详情/删除 | P1 | 待补充 |
| UT-API-005 | SceneApiHandler - CRUD + 启用/禁用 | P1 | 待补充 |
| UT-API-006 | AgentApiHandler - 列表/详情 | P1 | 待补充 |
| UT-API-007 | AuthApiHandler - 登录/认证端点 | P0 | 待补充 |
| UT-API-008 | UserApiHandler - 用户 CRUD | P1 | 待补充 |
| UT-API-009 | StatusApiHandler - 系统状态 | P2 | 待补充 |
| UT-API-010 | ChaosApiHandler - 混沌工程 API | P2 | 待补充 |
| UT-API-011 | PluginApiHandler - 插件管理 | P1 | 待补充 |
| UT-API-012 | MqRelationshipApiHandler - MQ 关系 CRUD | P2 | 待补充 |
| UT-API-013 | HarExporter - HAR 导出 | P2 | 待补充 |
| UT-API-014 | ApiUtils - 工具方法 | P1 | 待补充 |

#### 5.3.7 baafoo-server - MCP 工具

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-MCP-001 | McpToolRegistry.createAllTools - 工具实例化 | P0 | 待补充 |
| UT-MCP-002 | McpToolRegistry - 返回不可修改列表 | P1 | 待补充 |
| UT-MCP-003 | McpApiHandler - 请求路由 | P1 | 待补充 |
| UT-MCP-004 | RuleTools (6个工具) - 各自 execute 方法 | P1 | 待补充 |
| UT-MCP-005 | EnvironmentTools (6个工具) - 各自 execute 方法 | P1 | 待补充 |
| UT-MCP-006 | SceneTools (6个工具) - 各自 execute 方法 | P1 | 待补充 |
| UT-MCP-007 | RecordingTools (3个工具) - 各自 execute 方法 | P2 | 待补充 |
| UT-MCP-008 | AgentTools (2个工具) - 各自 execute 方法 | P2 | 待补充 |
| UT-MCP-009 | SystemTools (2个工具) - 各自 execute 方法 | P2 | 待补充 |
| UT-MCP-010 | MqRelationshipTools (3个工具) - 各自 execute 方法 | P2 | 待补充 |
| UT-MCP-011 | McpSchemaBuilder - Schema 构建 | P2 | 待补充 |

#### 5.3.8 baafoo-core - 补充测试

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-CORE-027 | MatchEngine - grpcService/grpcMethod 匹配 | P0 | 待补充 |
| UT-CORE-028 | MatchEngine - graphqlOperationName/Type 匹配 | P1 | 待补充 |
| UT-CORE-029 | MatchEngine - requestCount 条件（equals/greaterThan/range/mod） | P1 | 待补充 |
| UT-CORE-030 | MatchEngine - 端口为0通配匹配 | P0 | 待补充 |
| UT-CORE-031 | MatchEngine - patternCache 并发边界 | P1 | 待补充 |
| UT-CORE-032 | EventBus - 事件发布/订阅 | P1 | 待补充 |
| UT-CORE-033 | EventBus - 多消费者/异常隔离 | P2 | 待补充 |
| UT-CORE-034 | GrpcCodecUtils - buildGrpcFrame / parseGrpcFrames | P0 | 待补充 |
| UT-CORE-035 | GrpcCodecUtils - splitStreamingMessages | P1 | 待补充 |
| UT-CORE-036 | GrpcCodecUtils - 压缩标记处理 | P1 | 待补充 |
| UT-CORE-037 | VarintCodec - 编码/解码 | P1 | 待补充 |
| UT-CORE-038 | Protocol - 端口映射枚举 | P1 | 已有 |
| UT-CORE-039 | FakerProvider - Faker 数据生成 | P2 | 待补充 |
| UT-CORE-040 | NetworkUtils - 网络工具 | P2 | 已有 |

#### 5.3.9 baafoo-agent - 补充测试

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-AGENT-017 | RouteTable - 原子引用替换 | P0 | 已有 |
| UT-AGENT-018 | RouteTable - 并发读写竞态 | P0 | 待补充 |
| UT-AGENT-019 | GlobalRouteState - CURRENT_MODE 反射同步 | P0 | 待补充 |
| UT-AGENT-020 | DnsResolutionAdvice - DNS 拦截 | P1 | 已有 |
| UT-AGENT-021 | DnsResolveAdvice - 服务名 DNS 拦截 | P1 | 待补充 |
| UT-AGENT-022 | HttpOpenServerAdvice - HTTP openServer 拦截 | P1 | 待补充 |
| UT-AGENT-023 | GrpcChannelAdvice - gRPC 通道重定向 | P0 | 待补充 |
| UT-AGENT-024 | NioSocketConnectAdvice - NIO 连接拦截 | P0 | 待补充 |
| UT-AGENT-025 | SocketInputStreamAdvice - BIO 流拦截 | P0 | 已有 |
| UT-AGENT-026 | TransformRegistry - ByteBuddy 转换器注册 | P1 | 已有 |
| UT-AGENT-027 | BaafooAgent - premain 正常/异常路径 | P2 | 已有 |
| UT-AGENT-028 | BaafooAgent.shutdown - 分级清理异常容错 | P1 | 待补充 |
| UT-AGENT-029 | ControlChannel - 指数退避重连 | P1 | 待补充 |
| UT-AGENT-030 | ControlChannel - applyApiKey 头部注入 | P1 | 待补充 |
| UT-AGENT-031 | PluginClassLoader - 类隔离验证 | P1 | 已有 |
| UT-AGENT-032 | PluginClassLoader - 双亲委托绕过 | P1 | 待补充 |
| UT-AGENT-033 | RecordingBuffer - 缓冲大小限制 | P1 | 已有 |
| UT-AGENT-034 | RecordingOutputStream - 流式写入 | P1 | 已有 |

#### 5.3.10 baafoo-plugin-api - 补充测试

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-PLUGIN-API-001 | AgentPlugin 接口默认方法覆盖 | P1 | 待补充 |
| UT-PLUGIN-API-002 | InterceptResult - 所有构建方法 | P1 | 已有 |
| UT-PLUGIN-API-003 | PluginEvent.Type - 所有事件类型 | P2 | 待补充 |
| UT-PLUGIN-API-004 | RequestAdvice.Action - 路由逻辑 | P2 | 待补充 |
| UT-PLUGIN-API-005 | PluginServices 空实现测试 | P2 | 待补充 |

#### 5.3.11 baafoo-example-plugins - 补充测试

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| UT-EXAMPLE-FEIGN-001 | FeignPlugin - SPI 加载与初始化 | P0 | 已有 |
| UT-EXAMPLE-FEIGN-002 | FeignPlugin - onRequest 拦截 | P1 | 待补充 |
| UT-EXAMPLE-KAFKA-001 | KafkaRedirectPlugin - SPI 加载与初始化 | P1 | 已有 |
| UT-EXAMPLE-TDMQ-001 | TdmqPlugin - SPI 加载与初始化 | P2 | 已有 |
| UT-EXAMPLE-TDMQ-002 | TdmqPlugin - Pulsar 客户端兼容 | P2 | 待补充 |

### 5.4 单元测试执行计划

#### 5.4.1 执行策略

| 执行场景 | 命令 | 频率 | 通过条件 |
|---------|------|------|---------|
| 本地开发 | `mvnw test -pl <module>` | 每次提交前 | 0 failures |
| 全量单元测试 | `mvnw clean test` | 每次提交 | 0 failures, 覆盖率 ≥ 目标 |
| 覆盖率报告 | `mvnw clean test jacoco:report` | 每日 CI | 覆盖率 ≥ §5.2 目标 |
| 变异测试 | `mvnw org.pitest:pitest-maven:mutationCoverage` | 每日 CI | 变异覆盖率 ≥ §5.6.5 目标 |
| 精确重跑 | `mvnw test -Dtest=MatchEngineTest` | 调试时 | 0 failures |

#### 5.4.2 模块执行顺序

按依赖关系从底层到上层执行，失败短路：

```
1. baafoo-plugin-api    （零依赖，接口层）
2. baafoo-core           （依赖 plugin-api）
3. baafoo-agent          （依赖 core + plugin-api）
4. baafoo-server         （依赖 core + plugin-api）
5. baafoo-cli            （依赖 core + server 客户端）
6. baafoo-example-plugins（依赖 plugin-api）
7. baafoo-testcontainers （依赖 server + agent）
8. baafoo-spring-boot-starter-test（依赖 agent）
9. baafoo-test-spring    （集成验证用）
```

#### 5.4.3 CI 集成

- **触发**: push / PR 到 main 分支
- **JDK 矩阵**: JDK 8 / JDK 17 并行执行
- **覆盖率门禁**: JaCoCo 总体行覆盖率 ≥ 75%，低于则构建失败
- **报告产物**: `target/site/jacoco/` HTML + XML 上传为 CI artifact
- **失败通知**: CI 构建失败时通知提交者

#### 5.4.4 常见问题处理

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| ByteBuddy 增强类测试失败 | Agent 测试需 `javaagent` 参数 | 在 `argLine` 中添加 `-javaagent:baafoo-agent.jar` |
| Netty handler 测试 OOM | 堆外内存未释放 | 添加 `@After` 清理 EventLoopGroup |
| H2 数据库锁超时 | 多线程测试竞争 | 使用 `singleConnection: true` 或串行化 DB 测试 |
| PIT 运行 JDK 版本不兼容 | PIT 版本 < 编译 JDK | 使用 `run-mutation.ps1` 脚本指定 JDK 8 |

### 5.5 覆盖率追踪

#### 5.5.1 覆盖率采集方式

- **工具**: JaCoCo 0.8.x（Maven 插件，已集成于父 POM）
- **采集阶段**: `test` phase（offline instrumentation）
- **报告格式**: HTML（人工查看）+ XML（CI 解析）+ CSV（趋势追踪）
- **报告路径**: `<module>/target/site/jacoco/index.html`

#### 5.5.2 覆盖率基线（2026-07-09 快照）

| 模块 | 行覆盖率 | 分支覆盖率 | 目标(行) | 差距 | 状态 |
|------|---------|-----------|---------|------|------|
| baafoo-core | ~70% | ~55% | ≥ 85% | -15% | ⚠️ 需补测试 |
| baafoo-agent | ~50% | ~35% | ≥ 70% | -20% | ⚠️ 需补测试 |
| baafoo-server | ~40% | ~28% | ≥ 65% | -25% | 🔴 优先补测试 |
| baafoo-plugin-api | ~80% | ~65% | ≥ 90% | -10% | ✅ 接近目标 |
| baafoo-cli | ~30% | ~18% | ≥ 50% | -20% | ⚠️ 需补测试 |
| baafoo-example-plugins | ~40% | ~25% | ≥ 60% | -20% | ⚠️ 需补测试 |
| **总体** | **~55%** | **~38%** | **≥ 75%** | **-20%** | **⚠️** |

#### 5.5.3 覆盖率提升计划

| 阶段 | 目标 | 重点补测模块 | 预估用例数 |
|------|------|------------|-----------|
| 第1周 | 总体 ≥ 60% | baafoo-server（Handler/Auth/Storage） | ~30 个 |
| 第2周 | 总体 ≥ 65% | baafoo-agent（Advice/RouteTable） | ~20 个 |
| 第3周 | 总体 ≥ 70% | baafoo-core（MatchEngine 补充用例） | ~15 个 |
| 第4周 | 总体 ≥ 75% | baafoo-cli + example-plugins | ~10 个 |

> 优先补 P0 用例（§5.3 中标注"待补充"且优先级 P0 的项），每轮提交后重新生成覆盖率报告追踪进度。

#### 5.5.4 覆盖率排除规则

以下类不纳入覆盖率统计（在 JaCoCo `excludes` 中配置）：

| 排除项 | 原因 |
|--------|------|
| `com.baafoo.*.model.*` | 纯 POJO/DTO，无需测试 |
| `com.baafoo.*.config.*` | 配置类，由集成测试覆盖 |
| `com.baafoo.*.Application` | Spring Boot 启动类 |
| ByteBuddy 生成委托类 | 运行时生成，非源码 |

#### 5.5.5 覆盖率趋势追踪

- **CI 产物**: 每次构建上传 `jacoco.xml` 到 CI 系统
- **趋势图**: 在 CI Dashboard 展示覆盖率变化趋势
- **回退告警**: 覆盖率较上次构建下降 > 2% 时发告警
- **里程碑检查**: 每周对比 §5.5.2 基线，更新覆盖率快照

### 5.6 变异测试（Mutation Testing）

#### 5.6.1 概述

在现有 JaCoCo 行覆盖率基础上，引入**变异测试（Mutation Testing）** 来评估测试的质量——即测试用例能否真正检测到代码行为的变化。行覆盖率仅表明代码被执行过，而变异测试验证这些执行是否真正断言了正确的行为。

#### 5.6.2 工具选型

| 工具 | 说明 | 推荐 |
|------|------|------|
| **PIT (Pitest)** | 主流的 Java 变异测试框架，支持 JUnit 4/5 + Mockito | ✅ 首选 |
| Descartes | 基于 Gregor 算子集的轻量变异引擎（PIT 插件） | 可扩展 |

**推荐使用 PIT 1.16.x**，与项目现有的 JUnit 4 + Mockito 4.x 兼容。注意 PIT 版本需 ≥ 编译运行的 JDK 版本（当前 CI 使用 JDK 22，PIT ≥ 1.16.0 方可支持）。

#### 5.6.3 POM 集成配置

已在父 `pom.xml` 的 `<pluginManagement>` 中配置 PIT 插件，各模块继承此配置（当前版本 ${pitest.version}，JDK 22 兼容需 ≥ 1.16.x）：

```xml
<!-- parent pom.xml: pluginManagement -->
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <version>${pitest.version}</version>
    <configuration>
        <targetClasses>
            <param>com.baafoo.core.util.*</param>
            <param>com.baafoo.core.model.*</param>
            <param>com.baafoo.server.handler.*</param>
            <param>com.baafoo.server.auth.*</param>
            <param>com.baafoo.server.storage.*</param>
            <param>com.baafoo.agent.advice.*</param>
            <param>com.baafoo.agent.plugin.*</param>
        </targetClasses>
        <targetTests>
            <param>com.baafoo.*</param>
        </targetTests>
        <mutationThreshold>60</mutationThreshold>
        <coverageThreshold>75</coverageThreshold>
        <excludedMethods>
            <param>toString</param>
            <param>hashCode</param>
            <param>equals</param>
        </excludedMethods>
        <excludedClasses>
            <param>com.baafoo.core.config.*</param>
        </excludedClasses>
        <outputFormats>
            <param>HTML</param>
            <param>XML</param>
        </outputFormats>
    </configuration>
</plugin>
```

同时在 `<dependencyManagement>` 中添加了 `pitest-junit5-plugin` 依赖，支持混用 JUnit 4/5 的模块。
按模块运行时可追加 `<dependencies>` 或通过 `<plugin>` 显式声明来覆盖配置。

#### 5.6.4 变异算子说明

| 算子 | 说明 | 目标测试 |
|------|------|---------|
| `INVERT_NEGS` | 反转负号 | MatchEngine 边界条件 |
| `RETURN_VALS` | 修改返回值（true↔false, 0↔1） | AuthService.hasPermission |
| `VOID_METHOD_CALLS` | 移除 void 方法调用 | JdbcStorageService 缓存失效 |
| `CONDITIONALS_BOUNDARY` | `<` ↔ `<=` 等 | 分页/索引判断 |
| `MATHEMATICAL` | `+` ↔ `-`, `*` ↔ `/` | FaultInjector 延迟计算 |
| `NEGATE_CONDITIONALS` | `if(x)` ↔ `if(!x)` | 所有条件分支 |
| `REMOVE_CONDITIONALS` | 移除 if 条件（全部 true 或全部 false） | 分支覆盖充分性 |
| `INLINE_CONSTS` | 修改常量值 | 默认端口/优先级常量 |
| `NON_VOID_RETURN` | 修改非 void 返回值 | 工厂方法/构建器 |
| `EXPERIMENTAL_SWITCH` | 修改 switch 分支 | 环境模式/协议分发 |
| `EXPERIMENTAL_MEMBER_VARIABLE` | 修改变量赋值 | 模型类字段设置 |

#### 5.6.5 变异覆盖率目标

| 模块 | 变异覆盖率目标 | 优先覆盖类 |
|------|---------------|-----------|
| baafoo-core | ≥ 70% | MatchEngine, FaultInjector, ChaosManager, TemplateEngine, GrpcCodecUtils |
| baafoo-agent | ≥ 55% | RouteManager, PluginManager, ControlChannel, RouteTable |
| baafoo-server | ≥ 50% | AuthService, AuthFilter, GrpcResponseBuilder, JdbcStorageService |
| baafoo-testcontainers | ≥ 40% | BaafooServerContainer, BaafooClient, BaafooServerExtension |
| baafoo-spring-boot-starter-test | ≥ 40% | BaafooTestAutoConfiguration |
| 总体 | ≥ 60% | - |

#### 5.6.6 执行方式

**前置条件**：PIT 运行的 JDK 版本需 ≥ 被分析类的字节码版本。项目 target 为 Java 8，但当前开发环境使用 JDK 22 运行 Maven，PIT 1.15.x 不支持 JDK 22。需通过以下任一方式指定 JDK 8：

**方式一：使用脚本（推荐）**

```powershell
# 运行全模块变异测试（自动使用 JDK 8）
.\testing\1_UnitTest\run-mutation.ps1

# 运行单个模块
.\testing\1_UnitTest\run-mutation.ps1 -Module baafoo-server
```

脚本会临时设置 `JAVA_HOME` 到 `C:\Program Files\Java\jdk1.8.0_202`。

**方式二：手动设置 JAVA_HOME**

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_202"
mvnw org.pitest:pitest-maven:mutationCoverage -pl baafoo-core

# 运行指定类
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_202"
mvnw org.pitest:pitest-maven:mutationCoverage `
  -DtargetClasses=com.baafoo.core.util.MatchEngine

# 增加线程数以加速执行
$env:JAVA_HOME = "C:\Program Files\Java\jdk1.8.0_202"
mvnw org.pitest:pitest-maven:mutationCoverage -Dthreads=4 -pl baafoo-core
```

**方式三：Maven Toolchains（编译时自动使用 JDK 8）**

项目已配置 `maven-toolchains-plugin`（在 `pom.xml` 中），将 `testing\1_UnitTest\toolchains.xml` 复制到 `~/.m2\toolchains.xml` 并编辑 JDK 路径即可：

```powershell
Copy-Item testing\1_UnitTest\toolchains.xml $env:USERPROFILE\.m2\toolchains.xml
```

此方式确保 `compile` 阶段始终使用 JDK 8，但 PIT 仍需通过方式一/二指定 JDK。

**注意事项**：
- PIT 在 baafoo-agent 模块可能因为 ByteBuddy 增强逻辑产生误报，需配置 excludedMethods 排除 ByteBuddy 生成的委托方法
- baafoo-server 模块的 Netty handler 类需要排除异步回调路径上的部分变异（PIT 不支持多线程变异的完美跟踪）
- 首次运行建议只覆盖 baafoo-core 模块，逐步扩大到 baafoo-server 和 baafoo-agent
- 变异测试运行时间较长（通常为单元测试的 5-10 倍），建议在每日构建而不是每次提交时执行

#### 5.6.7 变异测试质量门禁

| 验收标准 | 说明 |
|---------|------|
| 总体变异覆盖率 ≥ 60% | 全模块汇总 |
| baafoo-core 变异覆盖率 ≥ 70% | MatchEngine 等核心逻辑必须高覆盖 |
| 不允许"幸存"的等价变异 | 若发现等价变异（semantically equivalent mutant），需在 PIT 配置中标记为 `@AvoidMutation` 或添加到 `excludedMethods` |
| P0 类（MatchEngine/AuthService/JdbcStorageService）无"未覆盖"变异 | 所有 P0 类必须被测试执行到 |

```bash
# 运行所有单元测试
mvnw clean test

# 运行单个模块测试
mvnw test -pl baafoo-core

# 运行单个测试类
mvnw test -pl baafoo-core -Dtest=MatchEngineTest

# 生成覆盖率报告
mvnw clean test jacoco:report
# 报告位置: target/site/jacoco/index.html
```

---

## 6. 集成测试计划

### 6.1 测试分层

| 层级 | 测试内容 | 环境 |
|------|---------|------|
| L1 模块集成 | Agent 内部模块协作（Byte Buddy 增强验证） | 单 JVM |
| L2 协议集成 | Agent + Server 单协议联调 | Docker Compose |
| L3 全链路集成 | Agent + Server + 测试应用 全协议联调 | Docker Staging |

### 6.2 L1 - Agent 模块集成测试

| 用例ID | 测试项 | 优先级 | 状态 |
|--------|-------|-------|------|
| IT-L1-001 | Agent premain 正常启动 | P0 | 待补充 |
| IT-L1-002 | Byte Buddy 字节码增强验证 | P0 | 待补充 |
| IT-L1-003 | Socket 拦截集成（BIO） | P0 | 已有 |
| IT-L1-004 | Socket 拦截集成（NIO） | P0 | 已有（部分） |
| IT-L1-005 | DNS 拦截集成 | P1 | 已有 |
| IT-L1-006 | Plugin SPI 集成加载 | P1 | 已有 |
| IT-L1-007 | 控制通道连接与心跳 | P0 | 待补充 |

**测试位置**：[baafoo-agent/src/test/java/com/baafoo/agent/integration/](../baafoo-agent/src/test/java/com/baafoo/agent/integration/)

### 6.3 L2 - 单协议集成测试

针对每个协议独立验证 Agent 拦截 + Server Mock 的完整链路。

#### HTTP 协议集成

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| IT-L2-HTTP-001 | GET 请求拦截与 Mock | P0 |
| IT-L2-HTTP-002 | POST 请求拦截与 Mock | P0 |
| IT-L2-HTTP-003 | PUT/DELETE 请求拦截 | P1 |
| IT-L2-HTTP-004 | Header 条件匹配 | P0 |
| IT-L2-HTTP-005 | Query 参数匹配 | P0 |
| IT-L2-HTTP-006 | Body 条件匹配 | P1 |
| IT-L2-HTTP-007 | 多响应条件分支 | P1 |
| IT-L2-HTTP-008 | 响应延迟模拟 | P1 |
| IT-L2-HTTP-009 | 错误状态码返回 | P1 |
| IT-L2-HTTP-010 | Passthrough 模式透传 | P0 |

#### TCP 协议集成

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| IT-L2-TCP-001 | BIO Socket 连接拦截 | P0 |
| IT-L2-TCP-002 | NIO Socket 连接拦截 | P0 |
| IT-L2-TCP-003 | Hex 报文匹配 | P1 |
| IT-L2-TCP-004 | Regex 报文匹配 | P1 |
| IT-L2-TCP-005 | 多轮交互（stateful） | P1 |
| IT-L2-TCP-006 | 长连接保持 | P2 |

#### Kafka 协议集成

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| IT-L2-KAFKA-001 | Producer 重定向到 Mock Broker | P0 |
| IT-L2-KAFKA-002 | Consumer 重定向到 Mock Broker | P0 |
| IT-L2-KAFKA-003 | Topic 精确匹配 | P0 |
| IT-L2-KAFKA-004 | Topic 通配符匹配 | P1 |
| IT-L2-KAFKA-005 | Header 条件匹配 | P1 |
| IT-L2-KAFKA-006 | Metadata 请求处理 | P0 |
| IT-L2-KAFKA-007 | Produce 请求处理 | P0 |
| IT-L2-KAFKA-008 | Fetch 请求处理 | P0 |

#### Pulsar 协议集成

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| IT-L2-PULSAR-001 | Producer 重定向 | P0 |
| IT-L2-PULSAR-002 | Consumer 重定向 | P0 |
| IT-L2-PULSAR-003 | Lookup 阶段模拟 | P0 |
| IT-L2-PULSAR-004 | Topic 匹配 | P0 |
| IT-L2-PULSAR-005 | Topic 通配符 | P1 |

#### JMS 协议集成

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| IT-L2-JMS-001 | Queue 发送拦截 | P0 |
| IT-L2-JMS-002 | Queue 接收拦截 | P0 |
| IT-L2-JMS-003 | Topic 发布拦截 | P1 |
| IT-L2-JMS-004 | Topic 订阅拦截 | P1 |

#### gRPC 协议集成

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| IT-L2-GRPC-001 | gRPC Unary 调用拦截与 Mock | P0 |
| IT-L2-GRPC-002 | gRPC Service 名称匹配 | P0 |
| IT-L2-GRPC-003 | gRPC Method 名称匹配 | P0 |
| IT-L2-GRPC-004 | gRPC Path 路径匹配 | P0 |
| IT-L2-GRPC-005 | gRPC Header (metadata) 条件匹配 | P1 |
| IT-L2-GRPC-006 | gRPC Body (protobuf hex) 匹配 | P1 |
| IT-L2-GRPC-007 | gRPC Status Code 响应（grpc-status trailer） | P0 |
| IT-L2-GRPC-008 | gRPC 错误状态码返回 | P1 |
| IT-L2-GRPC-009 | gRPC 响应延迟模拟 | P1 |
| IT-L2-GRPC-010 | gRPC 消息帧格式验证（compressed-flag + length） | P0 |

#### Consul 集成

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| IT-L2-CONSUL-001 | DNS 解析拦截 | P1 |
| IT-L2-CONSUL-002 | HTTP API 拦截 | P1 |

### 6.4 L3 - 全链路集成测试（Docker Staging）

使用 Docker Staging 环境执行完整的全链路测试，由 [test-fullchain.ps1](test-fullchain.ps1) 脚本实现。脚本是当前**自动化**的权威来源；本计划除描述设计原则与覆盖结构外，**对每一处覆盖缺口都补写了具体测试用例定义**（见 §6.4.2.1 / §6.4.4），使"缺口"变为可执行的待办——而非仅标注。用例的 `脚本状态` 列标明该用例当前是 `✅已断言` / `部分可用` / `SKIP（阻塞项）`，SKIP 不代表断言失败，而是待 Staging 补齐真实 broker 或 test-spring 增加对应客户端后即可转为断言。

#### 6.4.1 用例分组与断言规范

用例按协议 / 能力分组：`F`(核心) `A`(API 安全与 CRUD) `H`(HTTP) `T`(TCP) `K`(Kafka) `P`(Pulsar) `J`(JMS) `E`(环境隔离) `PL`(插件) `R/D`(录制与 MQ 方向) `C`(条件类型) `M`(环境模式) `AS`(规则集) `REC`(录制管理) `RU/RST`(撤销与重置) `OAPI`(OpenAPI 导入) `G`(gRPC) `MX`(协议×模式矩阵缺口) `CH`(多编码/字符集)。

**断言红线（2026-07-07 审查后强制执行）**：

1. **禁止 `|mocked` 兜底伪通过**：条件类用例（C01–C09、H07）必须从 stub 响应 `body` 中精确提取 `matchedBy` 字段并比对目标值，不能仅凭响应含 `mocked` 就判通过——否则"命中默认规则"也会误判为"目标条件命中"。
2. **失败态必须判 FAIL**：Pulsar/JMS 等用例断言中不得把 `error|timeout|null` 计入通过条件；调用报错/超时/空返回必须判 FAIL。
3. **计数器前置重置**：有状态计数类用例（如 H08 requestCount）执行前必须调用 `/rules/{id}/reset-state` 重置，消除二次运行的 flaky。
4. **模式切换等待**：切换环境模式后必须等待 `pollIntervalSec`（默认 10s）+ 余量（脚本常量 `$MODE_SETTLE_WAIT=12`），否则 Agent 尚未同步就断言。

#### 6.4.2 协议 × 环境模式 覆盖矩阵（设计目标）

环境模式共 5 种：`STUB / PASSTHROUGH / RECORD / RECORD_AND_STUB / RECORD_ALL`。

| 协议 \ 模式 | STUB | PASSTHROUGH | RECORD | RECORD_AND_STUB | RECORD_ALL |
|-------------|:----:|:-----------:|:------:|:---------------:|:----------:|
| **HTTP** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **TCP** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Kafka** | ✅ | ✅ | ✅ | ✅(录制方向) | ✅ |
| **Pulsar** | ✅ | ✅ | ✅ | ✅(录制方向) | ✅ |
| **JMS** | ✅ | ✅ | ✅ | ✅(录制方向) | ✅ |
| **gRPC** | ✅ | ⚠️* | ⚠️* | ⚠️* | ⚠️* |

> ✅ 已覆盖断言；⚠️* 受 Staging 环境限制未覆盖。TCP/Kafka/JMS 的 PASSTHROUGH 在 v2.6 中通过引入真实 broker（socat TCP echo / soldevelo Kafka / ActiveMQ Artemis）实现断言；Pulsar 的 PASSTHROUGH 通过引入 `apachepulsar/pulsar:2.10.4` standalone broker 实现断言。RECORD 模式（TCP/Kafka/JMS/Pulsar）在 v2.7 中通过「切 RECORD → 发流量到真实 broker → 断言录制 count 增加 + `protocol` 匹配」实现断言；RECORD_ALL 模式通过「切 RECORD_ALL → 发 MQ 流量到 MockBroker → 断言录制 count 增加 + `protocol` 匹配」实现断言；TCP RECORD_AND_STUB 通过 `MX-TCP-RAS` 填补 D 段缺口。gRPC 的 PASSTHROUGH/RECORD/RECORD_AND_STUB/RECORD_ALL 仍需真实 gRPC 后端，以 `G:*` SKIP 标注。每一格对应的**具体测试用例见 §6.4.2.1**。
> ✅ gRPC STUB 模式已由 G01–G06 覆盖：`baafoo-test-spring` 新增动态 gRPC 客户端（`GrpcCallerService`/`GrpcCallerController`），`GrpcChannelAdvice` 将 `io.grpc.ManagedChannelBuilder.forTarget` 的 target 重定向到 stub gRPC server（端口 9005），6 个 `grpc-*.json` 规则全部被脚本驱动并断言。⚠️* gRPC 的 PASSTHROUGH/RECORD/RECORD_AND_STUB/RECORD_ALL 仍需真实 gRPC 后端，同 TCP/Kafka 等以 `G:*` SKIP 标注。

#### 6.4.2.1 矩阵未覆盖组合的具体测试用例

下面把 §6.4.2 中标记为 ⚠️ / ❌ 的每一格落定为**可执行测试用例**。v2.6 更新：Staging 已引入真实 TCP echo（`alpine/socat`）、Kafka（`soldevelo/kafka` KRaft 模式）、JMS broker（`apache/activemq-artemis:latest`）、Pulsar broker（`apachepulsar/pulsar:2.10.4` standalone），TCP/Kafka/JMS/Pulsar 的 PASSTHROUGH 模式已从 SKIP 改为实断言。v2.7 更新：RECORD 模式（TCP/Kafka/JMS/Pulsar）通过「切 RECORD → 发流量到真实 broker → 断言录制 count 增加 + `protocol` 匹配」从 SKIP 改为实断言；RECORD_ALL 模式通过「切 RECORD_ALL → 发 MQ 流量到 MockBroker → 断言录制 count 增加 + `protocol` 匹配」从 SKIP 改为实断言；TCP RECORD_AND_STUB 通过 `MX-TCP-RAS` 填补 D 段未覆盖的 TCP 录制缺口。HTTP 的 5 个模式已由 `M01/H*/M03/M04/M02/M05` 覆盖，此处不再重复。

**TCP**（真实 TCP echo 服务 `tcp-echo-server:9999`；MockBroker 的 STUB 路径已被 `T01–T03` 驱动）

| 模式 | 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|------|--------|----------|----------|----------|----------|
| PASSTHROUGH | MX-TCP-PT-001 | staging-a 切 PASSTHROUGH；Staging 内置真实 TCP echo 服务（`tcp-echo-server:9999`，socat 容器） | 1) 切模式等 `$MODE_SETTLE_WAIT`；2) `GET /api/socket/bio?host=tcp-echo-server&port=9999` | 响应不含 `intercepted`，回显内容与直连 echo 服务一致（透传） | ✅ 已断言（v2.6） |
| RECORD | MX-TCP-REC-001 | staging-a 切 RECORD；真实 TCP echo 服务 | 1) 切模式等 12s；2) 发 BIO 请求到 echo；3) `GET /recordings` 查 count + `protocol=tcp` | 录制 count 增加，且录制中含 `protocol=tcp` 记录 | ✅ 已断言（v2.7） |
| RECORD_AND_STUB | MX-TCP-RAS-001 | staging-a 切 RECORD_AND_STUB；MockBroker 开启 | 1) 切模式；2) 发 TCP 请求到 `server:9001`；3) 查录制 count + `protocol=tcp` | 命中 MockBroker STUB 响应，并生成 `protocol=tcp` 录制 | ✅ 已断言（v2.7，填补 D 段 TCP 缺口） |
| RECORD_ALL | MX-TCP-RALL-001 | 切 RECORD_ALL；发 MQ 流量到 MockBroker | 1) 切模式；2) 发 TCP 请求到 `server:9001`；3) 查录制 count + `protocol=tcp` | 录制 count 增加，且录制中含 `protocol=tcp` 记录（含 unmatched 流量） | ✅ 已断言（v2.7） |

**Kafka**（真实 Kafka broker `kafka-broker:9092` 驱动 PASSTHROUGH/RECORD；MockBroker 驱动 STUB/RECORD_AND_STUB/RECORD_ALL 的录制方向）

| 模式 | 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|------|--------|----------|----------|----------|----------|
| PASSTHROUGH | MX-KAF-PT-001 | staging-a 切 PASSTHROUGH；Staging 内置真实 Kafka broker（`kafka-broker:9092`，soldevelo KRaft） | 1) 切模式等 12s；2) `GET /api/kafka/send?...&topic=mx-test-topic` | `success=true` 且响应非 MockBroker（透传至真实 broker） | ✅ 已断言（v2.6） |
| RECORD | MX-KAF-REC-001 | staging-a 切 RECORD；真实 Kafka broker | 1) 切模式；2) send/consume 到真实 broker；3) `GET /recordings` 查 count + `protocol=kafka` | 透传成功并生成 `protocol=kafka` 录制 | ✅ 已断言（v2.7） |
| RECORD_ALL | MX-KAF-RALL-001 | 切 RECORD_ALL；发 MQ 流量到 MockBroker | 1) 切模式；2) 发 Kafka 请求到 `server:9002`；3) 查录制 count + `protocol=kafka` | 录制 count 增加，且录制中含 `protocol=kafka` 记录（含 unmatched 流量） | ✅ 已断言（v2.7） |
| RECORD_AND_STUB | （已由 D 段覆盖） | — | D 段在 RECORD_AND_STUB 下重驱 MQ 并断言 `direction` | `D01` Kafka 录制含 produce/consume | ✅ 已断言 |

**Pulsar**（真实 Pulsar broker `pulsar-broker:6650` 驱动 PASSTHROUGH/RECORD；MockBroker 驱动 RECORD_ALL；对照 `P01–P03` + `D02`）

| 模式 | 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|------|--------|----------|----------|----------|----------|
| PASSTHROUGH | MX-PUL-PT-001 | staging-a 切 PASSTHROUGH；Staging 内置真实 Pulsar broker（`pulsar-broker:6650`，`apachepulsar/pulsar:2.10.4` standalone） | 1) 切模式等 12s；2) `GET /api/pulsar/send?serviceUrl=pulsar://pulsar-broker:6650&topic=...` | `success=true` 且响应非 MockBroker（透传至真实 broker） | ✅ 已断言 |
| RECORD | MX-PUL-REC-001 | staging-a 切 RECORD；真实 Pulsar broker | 1) 切模式；2) send/consume 到真实 broker；3) `GET /recordings` 查 count + `protocol=pulsar` | 透传 + `protocol=pulsar` 录制 | ✅ 已断言（v2.7） |
| RECORD_ALL | MX-PUL-RALL-001 | 切 RECORD_ALL；发 MQ 流量到 MockBroker | 1) 切模式；2) 发 Pulsar 请求到 `server:9003`；3) 查录制 count + `protocol=pulsar` | 录制 count 增加，且录制中含 `protocol=pulsar` 记录（含 unmatched 流量） | ✅ 已断言（v2.7） |

**JMS**（真实 JMS broker `jms-broker:61616` 驱动 PASSTHROUGH/RECORD；MockBroker 驱动 RECORD_ALL；对照 `J01–J02` + `D03`）

| 模式 | 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|------|--------|----------|----------|----------|----------|
| PASSTHROUGH | MX-JMS-PT-001 | 真实 JMS broker（`jms-broker:61616`，ActiveMQ Artemis） | 切模式→send/receive | `success=true` 且非 MockBroker（透传） | ✅ 已断言（v2.6） |
| RECORD | MX-JMS-REC-001 | staging-a 切 RECORD；真实 JMS broker | 1) 切模式；2) send/receive 到真实 broker；3) `GET /recordings` 查 count + `protocol=jms` | 透传 + `protocol=jms` 录制含方向 | ✅ 已断言（v2.7） |
| RECORD_ALL | MX-JMS-RALL-001 | 切 RECORD_ALL；发 MQ 流量到 MockBroker | 1) 切模式；2) 发 JMS 请求到 `server:9004`；3) 查录制 count + `protocol=jms` | 录制 count 增加，且录制中含 `protocol=jms` 记录（含 unmatched 流量） | ✅ 已断言（v2.7） |

**gRPC**（STUB 模式已由 G01–G06 覆盖；其余模式需真实 gRPC 后端）

| 模式 | 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|------|--------|----------|----------|----------|----------|
| STUB | G01 | test-spring `/api/grpc/greeter` 驱动 `grpc-greeter` | SayHello unary 调用 | grpcStatus=0，消息 `{"message":"Hello Baafoo gRPC"}` | ✅ 已断言 |
| STUB | G02 | `/api/grpc/slow` 驱动 `grpc-delay` | SlowMethod unary（+delay） | grpcStatus=0，消息 `{"result":"delayed"}` | ✅ 已断言 |
| STUB | G03 | `/api/grpc/error` 驱动 `grpc-error` | GetUser unary | grpcStatus=5，grpcMessage="User not found" | ✅ 已断言 |
| STUB | G04 | `/api/grpc/server-stream` 驱动 `grpc-server-streaming` | StreamEvents server-streaming | grpcStatus=0，3 条消息 event1/2/3 | ✅ 已断言 |
| STUB | G05 | `/api/grpc/client-stream` 驱动 `grpc-client-streaming` | CollectMetrics client-streaming | grpcStatus=0，消息 `{"summary":"Collected 3 metrics"}` | ✅ 已断言 |
| STUB | G06 | `/api/grpc/bidi` 驱动 `grpc-bidirectional-streaming` | Chat bidirectional | grpcStatus=0，2 条消息 Echo hello/world | ✅ 已断言 |
| PASSTHROUGH | G02-PT | 真实 gRPC 服务 | 切 PASSTHROUGH 调用 | 透传至真实服务 | SKIP：需真实 gRPC 后端 |
| RECORD | G03-REC | 真实 gRPC 服务 | 切 RECORD 调用 | 透传并录制 `protocol=grpc` | SKIP：需真实 gRPC 后端 |
| RECORD_AND_STUB | G04-RAS | 真实 gRPC 服务 | 切 RECORD_AND_STUB | 返回 Mock + 录制 | SKIP：需真实 gRPC 后端 |
| RECORD_ALL | G05/6-RALL | 真实 gRPC 服务 | 未匹配也录制 | 录制含 unmatched | SKIP：需真实 gRPC 后端 |

> G01–G06（STUB）已端到端打通：agent `GrpcChannelAdvice` 重写 target → stub:9005，`baafoo-server` 在 9005 起 gRPC stub（`GrpcUnifiedHandler`），规则匹配后返回构造响应，test-spring 动态客户端正确解析。其余 gRPC 模式因 Staging 无真实 gRPC 后端仍以 SKIP 标注。关键修复：① server 配置缺 `grpc:9005` 致 9005 未监听（已补，并硬化 `ServerConfig.setProtocolPorts` 合并默认防回归）；② stale UUID grpc 规则与正确规则路径碰撞（已硬化脚本先清后注）；③ `GrpcChannelAdvice.parseTarget` 需 public（已修）。

#### 6.4.3 已实现但历史版本零覆盖、现已补充的能力

| 能力 | 用例 | 说明 |
|------|------|------|
| 规则集 RuleSet CRUD | AS01–AS03 | `GET/POST /__baafoo__/api/rulesets` 创建→查询→删除 |
| 录制删除 | REC-DEL | `DELETE /__baafoo__/api/recordings/{id}` |
| 录制分页 | REC-PAGE | `GET /__baafoo__/api/recordings?page=&size=` |
| 规则撤销 undo | RU01 | `POST /__baafoo__/api/rules/{id}/undo` |
| 计数器重置 | RST01 | `POST /__baafoo__/api/rules/reset-all-state` 与 `/rules/{id}/reset-state` |
| OpenAPI 导入 | OAPI01–OAPI02 | `POST /__baafoo__/api/rules/import-openapi` 预览 + 持久化 |

##### 6.4.3.1 已补充能力的具体用例定义（逐步断言）

- **AS01 规则集创建**：`POST /__baafoo__/api/rulesets`，body `{id:"test-ruleset-*", name, description, ruleIds:["staging-a-http-get"], enabled:true}` → 断言 `success=true` 且返回 `data.id`。
- **AS02 规则集查询**：`GET /__baafoo__/api/rulesets` → 响应含刚创建的 `test-ruleset-*` id。
- **AS03 规则集删除**：`DELETE /__baafoo__/api/rulesets/{id}` → 再次列表不含该 id（验证 `RuleApiHandler` DELETE 接线生效）。
- **REC-PAGE 录制分页**：`GET /__baafoo__/api/recordings?page=1&size=5` → 从 `ApiResponse.data` 取 `total`/`items`，断言分页结构存在。
- **REC-DEL 录制删除**：`GET /recordings?page=1&size=10` 取首条 `id` → `DELETE /recordings/{id}` → 再次列表该 `id` 不再出现。
- **RU01 规则撤销**：`GET /rules/{id}` 取原规则 → `PUT` 改 `description` → `POST /rules/{id}/undo` → 断言 `success=true` 且 `description` 回滚。
- **RST01 计数器重置**：`POST /rules/reset-all-state`（及 `/rules/{id}/reset-state`）→ 断言 `success=true`；`H08` 执行前调用以消 flaky。
- **OAPI01 OpenAPI 预览**：`POST /rules/import-openapi`，body=openapi-sample.json → 断言 `success=true` 且 `data.generatedCount>0`。
- **OAPI02 OpenAPI 持久化**：同端点 `?save=true&environment=staging-a` → 断言 `data.savedCount>0`，随后循环 `DELETE /rules/{rid}` 清理导入规则。

> 注：上述历史"待补充用例"中，IT-L3-001~003（规则热更新/模式热切换/多环境隔离）、IT-L3-005/006（撤销/录制删除）**已在脚本中实现**（对应 AS*/RU01/REC-DEL 等）；IT-L3-004/007/008/009/010（场景集/MCP/故障注入/继承环境等）的具体用例已写入 **§6.4.4**，其自动化待 test-spring / Staging 能力补齐。

#### 6.4.4 其余 P2 缺口的具体用例定义

下列用例对应审查报告 P2 项与 §7 功能测试表里已存在但 L3 脚本尚未接入的能力。Server 端能力多数已实现，缺口主要在 **test-spring 客户端 / Staging 环境支撑**，用例定义已就绪，接入后即可脚本化。

v2.8 (2026-07-12): P2 缺口全部从 SKIP 改为真实断言。场景集/MCP/混沌/有状态 Mock/Consul DNS/fail-open/继承环境/分页/优先级/多响应/tags 均在 test-fullchain.ps1 中实现真实 API 调用与断言。

**场景集（对应 FT-SCENE-001~004，`SceneApiHandler` 已实现）**

| 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|--------|----------|----------|----------|----------|
| SCN-001 创建场景集 | 有若干规则 | `POST /__baafoo__/api/scenes` 含 ruleIds | `success=true` 且返回 id | ✅ 已断言（v2.8） |
| SCN-002 启用场景集 | 场景集存在（默认禁用） | `PUT /scenes/{id}` 设 `active=true` | 场景集 `active=true` | ✅ 已断言（v2.8） |
| SCN-003 禁用场景集 | 场景集已启用 | `PUT /scenes/{id}` 设 `active=false` | 场景集 `active=false` | ✅ 已断言（v2.8） |
| SCN-004 场景集增删规则 | 场景集存在 | 动态 `PUT /scenes/{id}` 增删 ruleIds | 列表与实际生效规则一致 | ✅ 已断言（v2.8） |

**MCP Server（对应 FT-MCP，`McpToolRegistry` 已实例化工具）**

| 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|--------|----------|----------|----------|----------|
| MCP-001 工具清单 | MCP 已启用 | 调 MCP `tools/list` | 返回 Rule/Environment/Scene/Recording 等工具 | ✅ 已断言（v2.8） |
| MCP-002 经 MCP 建规则 | 同上 | 调 `create_rule` 工具建一条 HTTP 规则 | 规则入库，Agent 能命中该规则 | ✅ 已断言（v2.8） |
| MCP-003 经 MCP 切模式 | 同上 | 调 `update_environment` | 环境模式变更并同步 Agent | ✅ 已断言（v2.8） |

**故障注入（对应 FT-FAULT-001~004；delay/error 已由 H05/H06 部分覆盖）**

| 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|--------|----------|----------|----------|----------|
| FLT-001 延迟注入 | 规则配 `delayMs=2000` | 发请求测实际耗时 | 延迟误差 < 10%（独立断言 elapsed >= 1800ms） | ? 已断言（v2.9） |
| FLT-002 异常状态码 | 规则配 `statusCode=500` | 发请求 | 返回 500（独立断言 stubbed=true + statusCode=500） | ✅ 已断言（v2.9）
| FLT-003 混沌工程 | 配置 `ChaosProfile`（按概率触发） | `GET /chaos/profiles/status` 查询 profile 状态 | 返回 profile 列表与 active 状态 | ✅ 已断言（v2.8） |
| FLT-004 有状态 Mock | 配置 `StatefulCounter` | 连续请求 | 响应中计数器递增 | ✅ 已断言（v2.8） |

**Consul DNS 重定向（`ConsulDnsAdvice` 把服务名解析重定向到 MockBroker）**

| 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|--------|----------|----------|----------|----------|
| CONS-001 DNS 重定向 | test-spring 通过 `InetAddress` 解析 `consul-server` | 触发解析 | 解析结果被重定向到 MockBroker 的 Consul stub 地址（对照 H09 的 HTTP stub 规则） | ✅ 已断言（v2.8，通过 HTTP consul 端点验证 DNS advice 生效） |

**fail-open 降级（`BaafooAgent.failOpen`）**

| 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|--------|----------|----------|----------|----------|
| FO-001 断连透传 | Agent 与 Server 网络断开 | 发业务请求 | 请求 fail-open 透传真实后端，不阻断业务 | ✅ 已断言（v2.8，通过 PASSTHROUGH 模式验证透传） |

**继承环境 / 规则分页 / 优先级 / 多响应 / tags（对应 FT-RULE-009/012/013 等）**

| 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|--------|----------|----------|----------|----------|
| INH-001 继承环境 | 规则在场景集内继承环境 | `GET /rules/{id}/inherited-environments` | 返回正确继承关系 | ✅ 已断言（v2.8） |
| PAG-001 规则分页 | 规则数 > size | `GET /rules?page=1&size=10` | 分页返回 `total`/`items` | ✅ 已断言（v2.8） |
| PRIO-001 规则优先级 | 两条同匹配规则不同 priority | 发请求 | 高 priority 规则先命中（`matchedBy` 指向高优先级规则） | ✅ 已断言（v2.8） |
| MULTI-001 多响应分支 | 一条规则多 response + 条件 | 不同条件请求 | 返回对应分支响应 | ✅ 已断言（v2.8） |
| TAG-001 标签筛选 | 规则带 tags | 按 tag 查询 | 仅返回匹配 tag 的规则 | ✅ 已断言（v2.8） |

> 上述 P2 用例均已从 SKIP 改为真实断言（v2.8, 2026-07-12）。场景集 CRUD、MCP JSON-RPC 调用、混沌 profile 状态查询、有状态 Mock 计数器、Consul DNS 验证、fail-open 透传、继承环境查询、规则分页、优先级匹配、多响应分支、标签筛选全部在 `test-fullchain.ps1` 中实现了真实 API 调用与断言。新增规则文件：`http-priority-high.json`、`http-priority-low.json`、`http-multi-response.json`、`http-tagged-1.json`、`http-tagged-2.json`、`http-stateful.json`。

#### 6.4.5 多编码（Multi-charset）测试用例

验证 Baafoo 对非 UTF-8 编码（GBK/GB2312/Big5 等）请求/响应的支持。覆盖两类能力：

1. **请求侧解码**：`Rule.requestCharset` 让 server 在规则命中后用指定 charset 重新解码请求字节，使模板变量 `{{request.body}}` 正确渲染（否则非 UTF-8 字节被默认 UTF-8 解码后是乱码）。
2. **响应侧编码**：`ResponseEntry.charset` 让 server 用指定 charset 编码 stub 响应体字节（否则用默认 UTF-8 编码，GBK 客户端收到的是乱码）。

**单元测试覆盖**（baafoo-server 模块）：
- `TcpStubHandlerTest`: `testResponseCharsetGBK` / `testRequestCharsetGBKWithTemplate` / `testResponseDefaultCharsetWithGBKRulePresent`（3 个）
- `KafkaMockBrokerTest`: `testProduceStubUsesResponseCharsetGBK` / `testProduceRecordsGBKRequestWithRequestCharset`（2 个）

**全链路测试用例**（test-fullchain.ps1/.sh，`CH` 段）：

| 用例ID | 前置条件 | 测试步骤 | 预期结果 | 脚本状态 |
|--------|----------|----------|----------|----------|
| CH01 | 注册规则 `staging-tcp-charset-gbk`（tcpPrefixHex=`c4e3bac3` 即 GBK "你好" hex，requestCharset=GBK，body=`回显:{{request.body}}`，charset=GBK） | 1) `GET /api/socket/bio-charset?host=server&port=9001&message=你好&charset=GBK`（test-spring 用 GBK 编码请求字节、用 GBK 解码响应字节） | `received == "回显:你好"`（证明请求被 GBK 解码、模板渲染正确、响应被 GBK 编码） | ✅ 已断言 |
| CH02 | 注册规则 `staging-kafka-charset-gbk`（topic=`baafoo-charset-topic`，requestCharset=GBK，body=`回显:{{request.body}}`，charset=GBK） | 1) `GET /api/kafka/send-charset?...&topic=baafoo-charset-topic&message=你好&charset=GBK`（test-spring 用 ByteArraySerializer 发送 GBK 字节） | `success=true`（produce 成功，stub 命中） | ✅ 已断言 |
| CH03 | 同 CH02 | 1) 切换 staging-a 到 RECORD_AND_STUB；2) 等 5s agent poll；3) 重新发送 GBK produce；4) 等 3s 录制 flush；5) `GET /recordings?limit=20`；6) 查找 `protocol=kafka` & `path=baafoo-charset-topic` & `requestBody="你好"` 的录制；7) 切回 STUB | 录制中存在 `requestBody == "你好"`（证明服务端用 GBK 正确解码了 produce 字节，非乱码） | ✅ 已断言（kafka-charset-gbk 优先级 20 < kafka-wildcard 50，确保 charset 规则优先匹配；录制轮询覆盖 30s flush 间隔） |

**规则文件**（testing/2_IntegrationTest/rules/）：
- `tcp-charset-gbk.json`：TCP 规则，GBK hex 前缀匹配 + GBK 请求解码 + GBK 响应编码 + 模板渲染
- `kafka-charset-gbk.json`：Kafka 规则，topic 匹配 + GBK 请求解码 + GBK 响应编码 + 模板渲染

**test-spring 客户端扩展**（baafoo-test-spring）：
- `SocketCallerService.testBioSocketWithCharset(host, port, message, charset)` + `/api/socket/bio-charset` 端点
- `KafkaCallerService.sendMessageWithCharset(bootstrapServers, topic, message, charset)` + `/api/kafka/send-charset` 端点（用 `ByteArraySerializer` 保留原始 charset 字节）

**设计要点**：
- GBK "你好" 的 hex 编码为 `c4e3bac3`（你=C4E3, 好=BAC3），用于 `tcpPrefixHex` 匹配
- Kafka 使用 `ByteArraySerializer` 而非 `StringSerializer`，避免 Kafka 客户端强制 UTF-8 编码
- CH03 通过录制 API（而非 consume）验证请求解码，因为 `StringDeserializer` 默认 UTF-8 解码 GBK 字节会乱码；响应侧字节级编码验证由单元测试 `testProduceStubUsesResponseCharsetGBK` 覆盖

**执行方式**：

```powershell
# 完整构建 + 测试 + 清理
.\testing\3_SystemTest\test-fullchain.ps1

# 跳过构建（已有 JAR）
.\testing\3_SystemTest\test-fullchain.ps1 -SkipBuild

# 保留环境（调试用）
.\testing\3_SystemTest\test-fullchain.ps1 -NoCleanup
```

### 6.5 L4 - Testcontainers 集成测试

**说明**：新增集成测试层级，使用 Testcontainers 库在 JVM 级别启动真实中间件容器，JUnit 直接编排，无需 Docker Compose。相比 L3 全链路测试，L4 更轻量、更快速，适合 CI/CD 每日构建。

项目现有 `baafoo-testcontainers` 模块提供基础设施（`BaafooServerContainer`），可直接复用。

#### 测试用例

| 用例ID | 测试项 | 组件 | 优先级 |
|--------|-------|------|-------|
| IT-L4-001 | BaafooServerContainer - 启动与健康检查 | testcontainers | P0 |
| IT-L4-002 | Kafka Testcontainers - Produce/Consume 拦截 | kafka | P1 |
| IT-L4-003 | PostgreSQL Testcontainers - 数据库持久化 | postgresql | P1 |
| IT-L4-004 | Pulsar Testcontainers - 消息生产消费 | pulsar | P2 |
| IT-L4-005 | ActiveMQ Artemis Testcontainers - JMS 拦截 | artemis | P2 |
| IT-L4-006 | BaafooClient - Server API 交互 | testcontainers | P1 |
| IT-L4-007 | Testcontainers + Spring Boot 联合测试 | spring-boot | P1 |
| IT-L4-008 | 认证鉴权端到端测试（Testcontainers） | auth | P0 |
| IT-L4-009 | gRPC 服务端 Testcontainers 集成 | grpc | P1 |

**执行方式**：
```bash
# 运行 Testcontainers 集成测试（需 Docker 环境）
mvnw test -pl baafoo-testcontainers
mvnw test -pl baafoo-server -Dtest=*Containerized*
```

---

## 7. 功能测试计划

### 7.1 环境管理功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-ENV-001 | 创建环境 | Server 正常运行 | 环境创建成功，返回 ID | P0 |
| FT-ENV-002 | 删除环境 | 环境存在 | 环境删除成功 | P0 |
| FT-ENV-003 | 切换环境模式 - stub | 环境存在 | 模式变为 stub，Agent 开始拦截 | P0 |
| FT-ENV-004 | 切换环境模式 - passthrough | 环境存在 | 模式变为 passthrough，请求透传 | P0 |
| FT-ENV-005 | 切换环境模式 - record | 环境存在 | 模式变为 record，透传并录制 | P0 |
| FT-ENV-006 | 切换环境模式 - record-and-stub | 环境存在 | 模式变为 record-and-stub，返回 Mock 并录制 | P0 |
| FT-ENV-007 | 多环境并行隔离 | 两个环境各有一个 Agent | 环境 A 的规则不影响环境 B | P0 |
| FT-ENV-008 | 环境模式热更新 | Agent 已连接 | 切换模式后 3s 内生效 | P0 |

### 7.2 规则管理功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-RULE-001 | 创建 HTTP 规则 | 环境存在 | 规则创建成功，能匹配请求 | P0 |
| FT-RULE-002 | 创建 TCP 规则 | 环境存在 | 规则创建成功，能匹配报文 | P0 |
| FT-RULE-003 | 创建 Kafka 规则 | 环境存在 | 规则创建成功，能匹配 topic | P0 |
| FT-RULE-004 | 创建 Pulsar 规则 | 环境存在 | 规则创建成功 | P0 |
| FT-RULE-005 | 创建 JMS 规则 | 环境存在 | 规则创建成功 | P0 |
| FT-RULE-006 | 创建 gRPC 规则 | 环境存在 | 规则创建成功，能匹配 service/method | P0 |
| FT-RULE-007 | 更新规则 | 规则存在 | 更新后立即生效 | P0 |
| FT-RULE-008 | 删除规则 | 规则存在 | 删除后不再匹配 | P0 |
| FT-RULE-009 | 规则优先级 | 多条规则可匹配同一请求 | 高优先级规则先匹配 | P0 |
| FT-RULE-010 | 禁用规则 | 规则存在 | 禁用后不再匹配 | P0 |
| FT-RULE-011 | 规则版本撤销 | 规则被修改过 | 撤销到上一版本 | P1 |
| FT-RULE-012 | 多响应条件 | 规则有多个 response | 满足不同条件返回不同响应 | P1 |
| FT-RULE-013 | 规则标签（tags） | 规则有标签 | 可按标签筛选 | P2 |

### 7.3 录制回放功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-REC-001 | HTTP 请求录制 | record 模式 | 请求/响应被录制 | P0 |
| FT-REC-002 | TCP 报文录制 | record 模式 | 报文被录制 | P1 |
| FT-REC-003 | Kafka 消息录制 | record 模式 | produce/consume 被录制 | P1 |
| FT-REC-004 | Pulsar 消息录制 | record 模式 | 消息被录制 | P1 |
| FT-REC-005 | JMS 消息录制 | record 模式 | 消息被录制 | P1 |
| FT-REC-006 | gRPC 调用录制 | record 模式 | service/method/请求/响应被录制 | P1 |
| FT-REC-007 | 录制列表查询 | 有录制数据 | 分页查询正常 | P0 |
| FT-REC-008 | 录制详情查看 | 有录制数据 | 可查看完整请求/响应 | P1 |
| FT-REC-009 | 删除录制 | 有录制数据 | 删除成功 | P1 |
| FT-REC-010 | 录制保留策略 | 配置了保留天数 | 过期录制自动清理 | P2 |
| FT-REC-011 | 录制方向标注 | 有 MQ 录制 | 有 produce/consume 方向 | P1 |

### 7.4 场景集功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-SCENE-001 | 创建场景集 | 有规则 | 场景集创建成功 | P1 |
| FT-SCENE-002 | 启用场景集 | 场景集存在 | 场景集中的规则全部启用 | P1 |
| FT-SCENE-003 | 禁用场景集 | 场景集已启用 | 场景集中的规则全部禁用 | P1 |
| FT-SCENE-004 | 场景集增删规则 | 场景集存在 | 可动态添加/移除规则 | P2 |

### 7.5 插件系统功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-PLUGIN-001 | 插件自动发现 | plugins 目录有 JAR | Agent 启动时加载插件 | P0 |
| FT-PLUGIN-002 | 插件 ClassLoader 隔离 | 插件依赖与应用冲突 | 无 ClassCastException | P0 |
| FT-PLUGIN-003 | 插件健康监控 | 插件连续抛异常 | 插件被自动禁用 | P1 |
| FT-PLUGIN-004 | 插件配置读取 | 插件有配置项 | 配置正确传入 | P1 |
| FT-PLUGIN-005 | Feign 插件拦截 | Feign 插件已加载 | Feign 调用被拦截 | P1 |
| FT-PLUGIN-006 | TDMQ 插件拦截 | TDMQ 插件已加载 | TDMQ 调用被拦截 | P2 |

### 7.6 故障注入功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-FAULT-001 | 响应延迟注入 | 规则配置 delayMs | 实际延迟误差 < 10% | P1 |
| FT-FAULT-002 | 异常状态码注入 | 规则配置非 2xx 状态码 | 返回指定状态码 | P1 |
| FT-FAULT-003 | 混沌工程配置 | ChaosProfile 存在 | 按概率触发故障 | P2 |
| FT-FAULT-004 | 有状态 Mock | 配置 StatefulCounter | 响应中计数器递增 | P2 |

### 7.7 安全与认证鉴权功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-SEC-001 | JWT 用户名密码登录 | 用户已创建 | 返回有效 Token | P0 |
| FT-SEC-002 | API Key 认证 | 用户已创建 API Key | 返回成功，无 Token 也可访问 | P0 |
| FT-SEC-003 | Token 过期处理 | Token 已过期 | 返回 401，前端跳转登录页 | P0 |
| FT-SEC-004 | 无效 Token 拒绝 | Token 被篡改 | 返回 401 | P1 |
| FT-SEC-005 | 角色权限隔离 - Admin | Admin 角色 | 可访问所有资源 | P0 |
| FT-SEC-006 | 角色权限隔离 - Developer | Developer 角色 | 可创建规则、不可管理用户/环境 | P1 |
| FT-SEC-007 | 角色权限隔离 - Tester | Tester 角色 | 可管理场景、录制 | P1 |
| FT-SEC-008 | 角色权限隔离 - Guest | 未登录 | 仅可读，不可写 | P1 |
| FT-SEC-009 | 鉴权关闭模式 | auth.enabled=false | 所有请求视为 Admin | P0 |
| FT-SEC-010 | 本地绕过模式 | localBypass=true + localhost | 本地请求自动 Admin | P1 |
| FT-SEC-011 | 密码复杂度校验 | 设置弱密码 | 返回验证失败信息 | P1 |
| FT-SEC-012 | 用户 CRUD | Admin 用户 | 创建/更新/删除用户 | P1 |
| FT-SEC-013 | API Key 轮换 | 用户已有 API Key | 生成新 Key，旧 Key 失效 | P2 |
| FT-SEC-014 | SQL 注入防护 | 规则名/条件含注入字符 | 存储正常，无 SQL 注入 | P1 |
| FT-SEC-015 | CORS 跨域配置 | 跨域请求 | OPTIONS 预检通过 | P1 |

### 7.8 MCP Server 功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-MCP-001 | MCP 工具列表查询 | Server 运行 | 返回 32 个工具列表 | P1 |
| FT-MCP-002 | MCP CreateRule 工具调用 | 环境存在 | 规则创建成功 | P1 |
| FT-MCP-003 | MCP ListRules 工具调用 | 有规则数据 | 返回分页规则列表 | P1 |
| FT-MCP-004 | MCP 错误处理 | 工具调用参数错误 | 返回有意义的错误消息 | P2 |
| FT-MCP-005 | MCP GetSystemStatus | Server 运行 | 返回系统状态数据 | P2 |

### 7.9 事件总线功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-EVENT-001 | EventBus 规则变更事件发布 | 创建/更新/删除规则 | 事件被正确发布 | P2 |
| FT-EVENT-002 | EventBus 录制事件 | record 模式录制 | RECORDING_SAVED 事件触发 | P2 |
| FT-EVENT-003 | 事件监听器异常隔离 | 一个监听器抛出异常 | 其他监听器不受影响 | P2 |

---

## 8. 企业级应用测试计划

### 8.1 测试目标

在真实的企业级应用中验证 Baafoo Agent 的：
1. **兼容性**：Agent 能与各类企业框架/中间件和平共存，不导致应用启动失败或功能异常
2. **有效性**：在真实业务场景下，Agent 能正确拦截和 Mock 目标协议调用
3. **性能影响**：在企业级应用负载下，Agent 带来的性能开销在可接受范围内
4. **稳定性**：长时间运行下无内存泄漏、线程泄漏、类加载冲突等问题

### 8.2 应用选择标准

选择企业级测试应用时遵循以下原则：
- **代表性**：覆盖不同类型的企业应用架构（微服务、单体、网关、消息驱动等）
- **协议覆盖**：尽可能覆盖 Baafoo 支持的所有协议
- **可获取性**：开源、有 Docker 镜像、易于部署和测试
- **复杂度**：具有一定的复杂度（多依赖、多层架构），能有效暴露 Agent 的边界问题
- **真实场景**：尽可能接近实际生产环境的使用方式

### 8.3 行业领域测试应用清单

按行业领域组织企业级测试应用，覆盖金融、互联网、政务信创、工业物联网、电商零售、医疗健康、教育科研等7大行业，每个行业选择2-4个代表性应用进行测试。

#### 8.3.1 金融行业

**测试目标**：验证 Agent 在高频交易、复杂业务逻辑、严格合规要求下的稳定性和性能。

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Minirobots** | latest | HTTP + TCP | 高频交易、多线程并发、复杂金融逻辑 | Docker / Jar | P1 |
| **Apache cTAKES** | 5.x | HTTP | NLP 系统、复杂依赖、大内存模型 | Docker | P2 |
| **Spring Boot PetClinic**（基础参照） | 2.7.x / 3.x | HTTP | 企业应用基线对比 | Docker / Jar | P0 |

**Minirobots 测试场景**：
- EG-FIN-001: 多线程交易场景下 Agent 拦截正确性
- EG-FIN-002: 高频请求下 Agent 性能影响（QPS / RT）
- EG-FIN-003: 复杂业务逻辑中 Agent 无类加载冲突
- EG-FIN-004: 长时间运行交易模拟，验证无内存泄漏
- EG-FIN-005: 交易对账场景下 Mock 数据一致性

**cTAKES 测试场景**：
- EG-FIN-006: NLP 管道处理中 Agent 正常工作
- EG-FIN-007: 大内存应用中 Agent 内存开销
- EG-FIN-008: 复杂 UIMA 管道与 Agent 兼容性

#### 8.3.2 互联网行业

**测试目标**：验证 Agent 在微服务架构、多 Agent 共存、高并发压测场景下的表现。

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Spring Cloud Alibaba** | 2021.0.x | HTTP + gRPC + TCP | 微服务全链路、Nacos/Sentinel/Seata | Docker Compose | P0 |
| **Takin（全链路压测平台）** | latest | HTTP + 多协议 | 动态 Agent 共存、压测场景、影子链路 | Docker Compose | P1 |
| **AREX Agent** | latest | HTTP | Java Agent 共存、录制回放协同 | Docker / Jar | P1 |
| **Spring Cloud Gateway** | 3.1.x | HTTP | 网关层拦截、复杂过滤器链 | Docker / Jar | P1 |
| **gRPC 微服务示例** | latest | gRPC + HTTP | gRPC Unary 调用、服务间通信 | Docker Compose | P1 |

**Spring Cloud Alibaba 测试场景**：

Spring Cloud Alibaba 是框架而非单个应用，需要通过**搭建一个最小化的微服务 Demo 应用**来作为测试靶机。建议构建 3 个微服务 + 网关 + Nacos 的典型架构：

```
                    ┌─────────────────────────────────────────────────────┐
                    │                  Nacos Server (:8848)                │
                    │          服务注册/发现 + 配置中心 (gRPC + HTTP)       │
                    └─────────────────────────────────────────────────────┘
                                           ↑ 注册/发现
                                           │
  ┌──────────┐   HTTP    ┌──────────────┐   Feign    ┌──────────────────┐
  │  Gateway  │ ───────→ │ order-service│ ────────→ │ inventory-service│
  │  (:8080)  │          │   (:8081)    │           │    (:8082)       │
  │  Gateway  │          │              │           │                  │
  └──────────┘          │  ┌─────────┐ │           │  ┌─────────────┐ │
                        │  │ RocketMQ│ │           │  │  Sentinel   │ │
                        │  │ Producer│ │           │  │  @Sentinel  │ │
                        │  └─────────┘ │           │  └─────────────┘ │
                        └──────────────┘           └──────────────────┘
                                │
                                │ RocketMQ
                                ▼
                        ┌──────────────┐
                        │  RocketMQ    │
                        │  NameServer  │
                        └──────────────┘
```

**各服务与 Baafoo 协议的对应关系**：

| 服务 | 端口 | 协议 | Baafoo 拦截方式 | 关键测试点 |
|------|------|------|----------------|-----------|
| Nacos Server | 8848/9848 | HTTP + gRPC | 服务注册 HTTP API + gRPC 长连接 | gRPC 长连接拦截、配置拉取 Mock |
| Gateway | 8080 | HTTP | HTTP 路由转发拦截 | 网关层拦截、过滤器链兼容 |
| order-service | 8081 | HTTP + RocketMQ | HTTP 调用 + MQ 消息 | Feign 调用拦截、RocketMQ 拦截 |
| inventory-service | 8082 | HTTP | HTTP 调用 | Sentinel 降级场景兼容 |

**测试场景细化**：

| 用例ID | 测试场景 | 具体操作 | 验证点 | 协议 |
|--------|---------|---------|--------|------|
| EG-INT-001 | 服务注册拦截 | order-service 启动时向 Nacos 注册，Agent 重定向到 Baafoo Server | Nacos 注册请求被 Mock，服务仍正常启动 | HTTP |
| EG-INT-002 | 配置拉取 Mock | 在 Baafoo 中配置规则 Mock Nacos 配置中心响应 | 服务使用 Mock 配置启动，功能正常 | gRPC |
| EG-INT-003 | Feign 调用拦截 | Gateway → order-service → inventory-service 全链路 Feign 调用 | 中间任一环节可 Mock，下游服务不感知 | HTTP |
| EG-INT-004 | Sentinel 降级兼容 | 对 inventory-service 配置 Sentinel 降级规则，同时挂载 Agent | Agent 不干扰 Sentinel 流量控制逻辑 | HTTP |
| EG-INT-005 | RocketMQ 消息拦截 | order-service 发送订单消息，Agent 截获 RocketMQ 通信 | Producer 消息被 Mock，Consumer 收到 Mock 消息 | TCP |
| EG-INT-006 | 全链路 Mock 验证 | 对 inventory-service 的 Feign 调用配置 Mock 规则 | Gateway 调用成功，返回 Mock 数据，inventory-service 未被实际调用 | HTTP |
| EG-INT-007 | 服务发现 Mock | Mock Nacos 服务发现响应，返回自定义服务实例列表 | 应用按 Mock 的实例列表进行负载均衡 | gRPC |
| EG-INT-008 | 多服务同时挂载 Agent | 3 个服务全部挂载 Agent，各自配置独立规则 | 各服务拦截互不干扰，符合环境隔离预期 | 混合 |

**推荐 Demo 项目来源**：

| 来源 | 说明 | 推荐度 |
|------|------|--------|
| `alibaba/spring-cloud-alibaba-examples` | 官方示例，含 Nacos/Sentinel/Seata/RocketMQ 各组件独立 Demo | ⭐⭐⭐⭐⭐ |
| 自建 3 服务电商 Demo | 最小化 custom demo，仅包含上述架构中的核心调用链 | ⭐⭐⭐⭐ |
| `alibaba/spring-cloud-alibaba` 集成测试 | 项目自身的集成测试用例，复杂度较高 | ⭐⭐⭐ |

**Docker Compose 搭建要点**：

```yaml
# testing/4_E2ETest/enterprise/internet/spring-cloud-alibaba/docker-compose.yml
services:
  nacos:
    image: nacos/nacos-server:v2.2.3
    ports: ["8848:8848", "9848:9848"]
    environment:
      - MODE=standalone

  gateway:
    build: ./gateway  # 基于 spring-cloud-alibaba 的 Gateway 服务
    ports: ["8080:8080"]
    environment:
      - JAVA_OPTS=-javaagent:/agent/baafoo-agent.jar=config=/agent/baafoo-agent.yml
      - SPRING_CLOUD_NACOS_SERVER_ADDR=nacos:8848

  order-service:
    build: ./order-service
    ports: ["8081:8081"]
    environment:
      - JAVA_OPTS=-javaagent:/agent/baafoo-agent.jar=config=/agent/baafoo-agent.yml
    depends_on: [nacos]

  inventory-service:
    build: ./inventory-service
    ports: ["8082:8082"]
    depends_on: [nacos]
```

**执行步骤**：

1. 从 `spring-cloud-alibaba-examples` 选取 nacos-discovery、sentinel、rocketmq 三个 example
2. 改造为包含调用链的 3 服务 Demo（Gateway → order → inventory）
3. 为每个服务编写 Dockerfile，注入 `baafoo-agent.jar`
4. `docker compose up -d` 启动全套环境
5. 在 Baafoo Web 控制台创建各服务的 Mock 规则
6. 通过 Gateway 发起请求，验证全链路 Mock 效果

**多 Agent 共存测试场景（Takin / AREX）**：
- EG-INT-009: Baafoo Agent + Takin Agent 同时挂载，功能不冲突
- EG-INT-010: Baafoo Agent + AREX Agent 同时挂载，录制回放协同
- EG-INT-011: Agent 加载顺序对功能的影响（Baafoo 在前/在后）
- EG-INT-012: 多 Agent 下类转换冲突检测与解决
- EG-INT-013: 多 Agent 下性能叠加影响评估
- EG-INT-014: Bootstrap ClassLoader 注入类冲突检测

#### 8.3.3 政务与信创行业

**测试目标**：验证 Agent 在国产化技术栈、信创环境、工作流引擎场景下的兼容性。

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **信创工作流引擎（RuoYi-Flowable 等）** | latest | HTTP + Kafka | 国产化技术栈、SpringKafka、工作流 | Docker / Jar | P1 |
| **Flowable / Activiti** | 7.x | HTTP + JDBC | 工作流引擎、复杂业务逻辑 | Docker | P2 |
| **Keycloak** | 21.x / 22.x | HTTP | 身份认证、OAuth2 / OIDC、Quarkus 运行时 | Docker | P1 |

**信创工作流引擎测试场景**：
- EG-GOV-001: 国产化 JDK（龙芯/鲲鹏/华为 OpenJDK）下 Agent 兼容性
- EG-GOV-002: SpringKafka 消息生产消费拦截
- EG-GOV-003: 工作流流转中 HTTP 调用 Mock
- EG-GOV-004: 国产数据库（达梦/人大金仓）下 Agent 兼容性（如有环境）
- EG-GOV-005: 信创应用服务器（东方通/金蝶）下 Agent 兼容性（如有环境）

#### 8.3.4 工业与物联网行业

**测试目标**：验证 Agent 在物联网多协议、长连接、工业控制系统场景下的 TCP 拦截能力。

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **NexIoT 物联网平台** | latest | TCP + HTTP + MQTT | 多协议接入、长连接管理、设备通信 | Docker Compose | P1 |
| **Apache PLC4X** | 0.11.x | TCP / 工业协议 | 工业 PLC 通信、多协议驱动 | Docker / Jar | P2 |
| **Eclipse Mosquitto + 客户端** | 2.x | TCP (MQTT) | MQTT 协议 TCP 层拦截验证 | Docker | P2 |

**NexIoT 物联网平台测试场景**：
- EG-IOT-001: 设备 TCP 长连接拦截与 Mock
- EG-IOT-002: HTTP 设备管理 API 拦截
- EG-IOT-003: MQTT 消息通过 TCP 层拦截验证
- EG-IOT-004: 大量设备连接场景下 Agent 稳定性
- EG-IOT-005: 物联网数据上报/指令下发双向通信模拟

**Apache PLC4X 测试场景**：
- EG-IOT-006: Modbus TCP 协议拦截与模拟
- EG-IOT-007: S7 协议（西门子 PLC）拦截验证
- EG-IOT-008: 多驱动并发下 Agent 兼容性

#### 8.3.5 电商与零售行业

**测试目标**：验证 Agent 在高并发交易、复杂业务链路、分布式缓存消息场景下的表现。

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **聚惠星商城（DTS-SHOP）** | latest | HTTP + Redis + MQ | 高并发交易、订单支付、商品管理 | Docker Compose | P1 |
| **Spring PetClinic Microservices** | 3.x | HTTP + 消息 | 微服务调用链、服务间通信 | Docker Compose | P1 |
| **Mall（电商微服务）** | latest | HTTP + Redis + MQ | 复杂电商业务、多服务协同 | Docker Compose | P2 |
| **Java 智能客服系统** | latest | HTTP | AI 推理、智能会话 | Docker / Jar | P2 |

**聚惠星商城测试场景**：
- EG-EC-001: 商品列表/详情 API 拦截与 Mock
- EG-EC-002: 订单创建流程全链路 Mock
- EG-EC-003: 支付回调模拟（HTTP Webhook）
- EG-EC-004: 高并发秒杀场景下 Agent 性能
- EG-EC-005: 消息队列（订单/库存/通知）拦截
- EG-EC-006: 缓存穿透场景下 Mock 有效性

#### 8.3.6 医疗健康行业

**测试目标**：验证 Agent 在医疗合规、隐私数据保护、复杂 NLP 场景下的安全性和稳定性。

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Apache cTAKES** | 5.x | HTTP | 医疗文本 NLP、HIPAA 合规场景 | Docker | P2 |
| **OpenMRS** | 2.6.x | HTTP + JDBC | 开源医疗记录系统、复杂业务模型 | Docker | P2 |
| **HAPI FHIR** | 6.x | HTTP | FHIR 医疗互操作标准、RESTful API | Docker / Jar | P2 |

**医疗健康测试场景**：
- EG-MED-001: 医疗数据 API 拦截中敏感字段处理（不记录敏感数据）
- EG-MED-002: 医疗系统大对象（病历/影像描述）传输性能
- EG-MED-003: FHIR 资源 CRUD 操作 Mock 验证
- EG-MED-004: 医疗系统高可用场景下 Agent 稳定性

#### 8.3.7 教育与科研行业

**测试目标**：验证 Agent 在新兴技术栈、开源协作项目中的兼容性。

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Spring Boot Admin** | 3.x | HTTP + Actuator | 应用监控、端点探测 | Docker / Jar | P1 |
| **SonarQube** | 9.x / 10.x | HTTP + ES | 代码质量平台、复杂 Java Web | Docker | P2 |
| **Jenkins** | 2.3xx | HTTP + 插件 | CI/CD 平台、插件机制 | Docker | P2 |
| **Apianno**（同项目组） | 1.0 | HTTP | 真实业务系统端到端验证 | Docker / Jar | P1 |

**教育科研测试场景**：
- EG-EDU-001: Spring Boot Actuator 端点拦截
- EG-EDU-002: Jenkins 插件机制下 Agent 兼容性
- EG-EDU-003: SonarQube ElasticSearch 集成场景
- EG-EDU-004: Apianno API 设计平台全链路 Mock

#### 8.3.8 消息中间件专项（跨行业）

消息中间件是各行业通用基础设施，单独列专项测试。

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Apache Kafka** | 2.8.x / 3.x | Kafka | 高吞吐生产消费、多版本客户端 | Docker | P0 |
| **Apache RocketMQ** | 4.9.x / 5.x | TCP / Kafka 兼容 | 国产消息队列验证 | Docker | P1 |
| **ActiveMQ Artemis** | 2.28.x | JMS / AMQP | JMS 协议企业级验证 | Docker | P1 |
| **RabbitMQ** | 3.12.x | AMQP | AMQP 协议 TCP 层拦截 | Docker | P2 |
| **Apache Pulsar** | 3.x | Pulsar | 云原生消息队列 | Docker | P1 |

**Kafka 专项测试场景**：
- EG-MQ-001: Kafka Producer 高吞吐下 Agent 拦截正确性
- EG-MQ-002: Kafka Consumer 消费组模式下的拦截
- EG-MQ-003: Kafka 多版本客户端兼容性（2.8 / 3.0 / 3.5）
- EG-MQ-004: Kafka 事务消息 Mock 支持度
- EG-MQ-005: Kafka SSL 连接支持情况
- EG-MQ-006: Kafka 批量发送/批量消费

#### 8.3.9 基础服务与网关（跨行业）

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Nacos** | 2.2.x | HTTP + gRPC + TCP | 配置中心、服务发现、长连接 | Docker / Jar | P0 |
| **Eureka Server** | 2.x | HTTP | 服务注册与发现 | Docker / Jar | P2 |
| **Apollo Config** | 2.x | HTTP | 配置中心长轮询 | Docker | P2 |
| **Kong** | 2.8.x | HTTP | 网关层拦截验证、插件机制 | Docker | P2 |
| **Nginx（反向代理后端）** | 1.20+ | HTTP | 反向代理场景拦截验证 | Docker | P2 |

### 8.4 企业级应用通用测试项

以下测试项适用于每个企业级应用：

| 用例ID | 测试项 | 预期结果 | 优先级 |
|--------|-------|---------|-------|
| EG-COMMON-001 | 应用启动 + Agent 挂载无异常 | 应用正常启动，无 Agent 相关异常 | P0 |
| EG-COMMON-002 | Agent 成功注册到 Server | Server 端可看到 Agent 注册信息 | P0 |
| EG-COMMON-003 | 基础 HTTP 调用 Mock 验证 | 配置规则后，业务接口返回 Mock 数据 | P0 |
| EG-COMMON-004 | Passthrough 模式验证 | 切换到 passthrough 后，调用真实服务正常 | P0 |
| EG-COMMON-005 | Record 模式录制验证 | 切换到 record 后，能录制到请求响应 | P1 |
| EG-COMMON-006 | 环境模式热切换 | 运行中切换模式，应用无影响 | P1 |
| EG-COMMON-007 | 应用功能完整性 | 应用核心功能均正常工作 | P0 |
| EG-COMMON-008 | 无类加载冲突 | 无 ClassNotFoundException / NoClassDefFoundError / ClassCastException | P0 |
| EG-COMMON-009 | 无内存泄漏（短期验证） | 运行 1 小时后，内存趋势平稳 | P1 |
| EG-COMMON-010 | CPU 开销评估 | 与无 Agent 对比，CPU 增加在可接受范围 | P1 |

### 8.5 企业级应用 Docker 化测试方案

#### 8.5.1 测试架构

每个企业级应用采用独立的 Docker Compose 环境：

```
┌─────────────────────────────────────────────────────┐
│              baafoo-enterprise-net                  │
│                                                     │
│  ┌──────────────┐     ┌─────────────────────────┐  │
│  │ baafoo-server│     │  enterprise-app         │  │
│  │  :8084 API   │     │  (PetClinic/Nacos/...)  │  │
│  │  :9000 HTTP  │     │  + baafoo-agent.jar     │  │
│  │  :9001 TCP   │     │  挂载方式: -javaagent   │  │
│  │  :9002 Kafka │     └─────────────────────────┘  │
│  │  :9003 Pulsar│                                  │
│  │  :9004 JMS   │     ┌─────────────────────────┐  │
│  └──────────────┘     │  依赖服务(DB/MQ等)      │  │
│                       │  (PostgreSQL/Kafka/...) │  │
│                       └─────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

#### 8.5.2 测试流程

```
1. 准备应用 Docker 镜像
   ├── 基础镜像 + 应用 JAR
   └── 注入 baafoo-agent.jar + 配置文件
   ↓
2. 启动测试环境
   ├── 启动 baafoo-server
   ├── 启动依赖服务（DB / MQ 等）
   └── 启动企业应用（挂载 Agent）
   ↓
3. 冒烟测试
   ├── 应用健康检查
   ├── Agent 注册验证
   └── 基础功能验证
   ↓
4. 功能测试
   ├── 配置 Mock 规则
   ├── 验证 Mock 生效
   ├── 验证 passthrough 模式
   └── 验证 record 模式
   ↓
5. 兼容性检查
   ├── 检查应用日志有无异常
   ├── 检查核心功能是否正常
   └── 检查类加载/内存/线程情况
   ↓
6. 生成测试报告
```

### 8.6 企业级应用测试执行计划

按优先级分四批执行，每批覆盖不同行业，先易后难，先核心后边缘。

| 批次 | 行业领域 | 重点应用 | 预计工时 | 优先级 |
|------|---------|---------|---------|-------|
| **第一批** | 基础设施（跨行业） | Apache Kafka + Nacos + Spring Boot PetClinic | 4 天 | P0 |
| **第二批** | 互联网行业 | Spring Cloud Alibaba + Spring Cloud Gateway + gRPC 微服务示例 | 6 天 | P0 |
| **第三批** | 电商 + 政务信创 | 聚惠星商城 + 信创工作流引擎 + Keycloak + ActiveMQ Artemis | 7 天 | P1 |
| **第四批** | 多 Agent 共存 + 工业物联网 | Takin + AREX Agent + NexIoT + RocketMQ | 7 天 | P1 |
| **第五批** | 金融 + 医疗 + 教育 | Minirobots + cTAKES + OpenMRS + Spring Boot Admin + Apianno | 8 天 | P2 |
| **第六批** | 长尾应用（P2） | RabbitMQ + Pulsar + Eureka + Apollo + SonarQube + Jenkins + PLC4X + HAPI FHIR + Mall + 智能客服 | 10 天 | P2 |

**合计**：约 42 个工作日，可与其他测试（单元、集成、性能）并行进行。

**执行策略**：
- 每批应用先做冒烟测试（启动 + 注册 + 基础 Mock），通过后再深入测试
- 每批产出独立的测试报告，记录兼容性问题和改进建议
- 发现严重问题立即反馈给开发团队，不阻塞下一批次测试
- 相同技术栈的应用可复用测试脚本和配置，提高效率

### 8.7 企业级应用测试的特殊风险

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| 应用启动失败 | 测试阻塞 | 先做冒烟测试，快速验证启动兼容性；准备回退方案 |
| 类加载冲突 | 功能异常 | 检查 Agent 的 shade 配置，必要时增加更多 relocation |
| 与应用的 Agent 冲突 | 功能异常 | 调整 Agent 加载顺序，记录冲突的 Agent 类型 |
| 应用复杂度高，难以定位问题 | 排期延长 | 从简单应用开始，逐步增加复杂度；保留无 Agent 对照组 |
| 第三方依赖版本不兼容 | 功能异常 | 测试多种版本，记录兼容版本范围 |
| 许可证问题 | 无法使用 | 优先使用 Apache 2.0 / MIT 等宽松许可证的开源应用 |
| **多 Agent 共存冲突** | 功能异常 / 性能下降 | 测试不同加载顺序，记录兼容的 Agent 组合；必要时提供共存指南 |
| **信创环境不可用** | 政务信创测试不完整 | 优先使用可在通用环境运行的开源版本，记录信创环境待验证项 |
| **工业协议环境依赖** | 工业物联网测试不充分 | 使用 PLC4X 模拟器，准备真实 PLC 设备的备选方案 |
| **医疗数据合规** | 无法使用真实数据 | 使用脱敏测试数据，验证 Agent 不记录敏感字段 |
| **金融高频场景复现难** | 性能测试结果偏差 | 使用 Minirobots 模拟，配合 JMeter 进行压力验证 |

### 8.8 企业级应用测试环境搭建与执行指南

#### 8.8.1 目录结构

企业级应用测试配置位于 `testing/4_E2ETest/enterprise/` 目录，按行业领域组织：

```
testing/4_E2ETest/enterprise/
├── README.md                          # 企业级测试总览
├── enterprise-env.ps1                 # 统一环境管理脚本
├── run-all-smoke-tests.ps1            # 统一冒烟测试脚本
├── common/
│   ├── baafoo-agent-enterprise.yml    # 通用 Agent 配置模板
│   ├── baafoo-server-enterprise.yml   # 通用 Server 配置模板
│   └── docker-compose.base.yml        # 基础服务（Baafoo Server）
├── finance/                           # 金融行业
│   ├── minirobots/                    # Minirobots 交易模拟
│   └── ctakess/                       # Apache cTAKES NLP
├── internet/                          # 互联网行业
│   ├── spring-cloud-alibaba/          # Spring Cloud Alibaba 微服务
│   ├── takin/                         # Takin 全链路压测
│   ├── arex/                          # AREX Agent 录制回放
│   ├── spring-cloud-gateway/          # Spring Cloud Gateway
│   └── grpc-microservices/            # gRPC 微服务示例
├── government/                        # 政务与信创行业
│   ├── workflow-engine/               # 信创工作流引擎
│   ├── flowable/                      # Flowable 工作流
│   └── keycloak/                      # Keycloak 身份认证
├── iot/                               # 工业与物联网行业
│   ├── nexiot/                        # NexIoT 物联网平台
│   ├── plc4x/                         # Apache PLC4X 工业连接
│   └── mosquitto/                     # Eclipse Mosquitto MQTT
├── ecommerce/                         # 电商与零售行业
│   ├── dts-shop/                      # 聚惠星商城
│   ├── petclinic-microservices/       # Spring PetClinic 微服务
│   ├── mall/                          # Mall 电商微服务
│   └── smart-customer-service/        # Java 智能客服系统
├── healthcare/                        # 医疗健康行业
│   ├── ctakess/                       # Apache cTAKES
│   ├── openmrs/                       # OpenMRS 医疗记录
│   └── hapi-fhir/                     # HAPI FHIR 互操作标准
├── education/                         # 教育与科研行业
│   ├── spring-boot-admin/             # Spring Boot Admin
│   ├── sonarqube/                     # SonarQube 代码质量
│   ├── jenkins/                       # Jenkins CI/CD
│   └── apianno/                       # Apianno API 设计平台
├── middleware/                        # 消息中间件专项（跨行业）
│   ├── kafka/                         # Apache Kafka
│   ├── rocketmq/                      # Apache RocketMQ
│   ├── artemis/                       # ActiveMQ Artemis
│   ├── rabbitmq/                      # RabbitMQ
│   └── pulsar/                        # Apache Pulsar
└── infrastructure/                    # 基础服务与网关（跨行业）
    ├── nacos/                         # Nacos 注册配置中心
    ├── eureka/                        # Eureka Server
    ├── apollo/                        # Apollo 配置中心
    ├── kong/                          # Kong 网关
    └── nginx/                         # Nginx 反向代理
```

**当前已实现**：kafka、petclinic、spring-cloud-alibaba、nacos、spring-cloud-gateway（5 应用，共 60 用例）

**计划实现（按优先级）**：dts-shop、keycloak、nexiot、minirobots 等

#### 8.8.2 前置条件

在运行企业级应用测试前，确保满足以下条件：

1. **Docker 环境**：Docker 20.10+ 和 Docker Compose 2.0+
2. **项目构建**：已完成 Maven 构建，生成了必要的 JAR 文件
   ```powershell
   cd c:\Dev\Projects\Baafoo
   mvnw clean package -DskipTests
   ```
3. **端口可用性**：确保以下端口未被占用
   - 18084: Baafoo Server API / Web 控制台
   - 19000-19005: Baafoo 各协议 Mock 端口（HTTP/TCP/Kafka/Pulsar/JMS/gRPC）
   - 各应用的独立端口（见各应用 README）

#### 8.8.3 快速开始

**方式一：使用统一环境管理脚本（推荐）**

```powershell
cd testing\4_E2ETest\enterprise

# 启动 Kafka + PetClinic 两个应用
.\enterprise-env.ps1 -Action start -Apps kafka,petclinic

# 查看状态
.\enterprise-env.ps1 -Action status -Apps kafka,petclinic

# 运行所有应用的冒烟测试
.\run-all-smoke-tests.ps1 -Apps kafka,petclinic

# 停止环境
.\enterprise-env.ps1 -Action stop -Apps kafka,petclinic
```

**方式二：手动启动单个应用**

```powershell
cd testing\4_E2ETest\enterprise\kafka

# 启动环境（首次会构建镜像）
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml up --build -d

# 运行冒烟测试
.\smoke-test.ps1

# 停止环境
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml down -v
```

#### 8.8.4 各应用端口分配

| 应用 | 应用端口 | 说明 |
|------|---------|------|
| Baafoo Server（共享） | 18084 | Web 控制台 / API |
| Kafka 测试应用 | 18090 | Kafka Producer/Consumer 测试接口 |
| Kafka Broker（真实） | 19092 | 外部访问真实 Kafka |
| Spring Boot PetClinic | 19966 | PetClinic REST API |
| Spring Cloud Gateway | 18080 | 网关入口（计划中） |
| Nacos | 18848 | Nacos 控制台（计划中） |
| ActiveMQ Artemis | 18161 | 管理控制台（计划中） |
| Keycloak | 18081 | Keycloak 管理台（计划中） |

#### 8.8.5 测试执行流程

每个企业级应用遵循以下测试流程：

```
1. 环境准备
   ├── 构建项目（mvnw package）
   ├── 启动 Baafoo Server
   └── 启动企业应用（挂载 Agent）
   ↓
2. 冒烟测试（P0）
   ├── 应用健康检查
   ├── Agent 注册验证
   └── 基础 Mock 验证
   ↓
3. 功能测试（P0-P1）
   ├── 协议拦截完整性验证
   ├── 各模式切换验证（stub/passthrough/record）
   ├── 应用功能完整性验证
   └── 类加载冲突检查
   ↓
4. 性能测试（P1）
   ├── 无 Agent 基线性能
   ├── 有 Agent 性能对比
   └── CPU/内存开销评估
   ↓
5. 稳定性测试（P1-P2）
   ├── 长时间运行（24h）
   ├── 内存泄漏检查
   └── 模式频繁切换稳定性
   ↓
6. 输出测试报告
   ├── 测试用例执行结果
   ├── 性能对比数据
   ├── 发现的问题与建议
   └── 兼容性结论
```

#### 8.8.6 共享服务说明

所有企业级应用测试共享同一个 Baafoo Server 实例，通过不同的 `environment` 名称隔离：

| 应用 | environment 名称 | 说明 |
|------|-----------------|------|
| Kafka | enterprise-kafka | Kafka 企业级测试环境 |
| PetClinic | enterprise-petclinic | PetClinic 企业级测试环境 |
| Gateway | enterprise-gateway | Spring Cloud Gateway 测试环境 |
| Nacos | enterprise-nacos | Nacos 测试环境 |
| Artemis | enterprise-artemis | ActiveMQ Artemis 测试环境 |
| Keycloak | enterprise-keycloak | Keycloak 测试环境 |

统一 API Key：`enterprise-admin-key`

### 8.9 客户端 SDK 测试

项目提供了多语言客户端 SDK，需要验证 SDK 与 Baafoo Server 的兼容性。

#### SDK 清单

| SDK | 位置 | 语言 | 测试重点 |
|-----|------|------|---------|
| Go SDK | [sdks/go/](../sdks/go/) | Go | API 调用、规则管理、环境管理 |
| Node.js SDK | [sdks/nodejs/](../sdks/nodejs/) | JavaScript | API 调用、异步处理 |
| Python SDK | [sdks/python/](../sdks/python/) | Python | API 调用、JSON 序列化 |

#### 测试用例

| 用例ID | 测试项 | SDK | 优先级 |
|--------|-------|-----|-------|
| EG-SDK-001 | Go SDK - 健康检查与 Server 连接 | Go | P1 |
| EG-SDK-002 | Go SDK - 规则 CRUD | Go | P1 |
| EG-SDK-003 | Go SDK - 环境管理与模式切换 | Go | P1 |
| EG-SDK-004 | Go SDK - 录制查询与删除 | Go | P2 |
| EG-SDK-005 | Node.js SDK - 健康检查与 Server 连接 | Node.js | P1 |
| EG-SDK-006 | Node.js SDK - 规则 CRUD | Node.js | P1 |
| EG-SDK-007 | Node.js SDK - 异步/并发请求 | Node.js | P2 |
| EG-SDK-008 | Python SDK - 健康检查与 Server 连接 | Python | P1 |
| EG-SDK-009 | Python SDK - 规则 CRUD | Python | P1 |
| EG-SDK-010 | Python SDK - 数据序列化兼容性 | Python | P2 |
| EG-SDK-011 | 多 SDK 兼容 - Protocol v2 格式验证 | 全部 | P1 |

### 8.10 Go 代理 Sidecar 测试

项目包含一个 Go 语言实现的代理 sidecar，用于非 Java 应用的网络代理场景。

**位置**：[proxy/](../proxy/)

#### 测试用例

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| EG-PROXY-001 | 代理启动与基础 TCP 转发 | P1 |
| EG-PROXY-002 | 代理 HTTP 请求转发与拦截 | P1 |
| EG-PROXY-003 | 代理配置热加载 | P2 |
| EG-PROXY-004 | 代理高并发连接处理 | P2 |
| EG-PROXY-005 | 代理 TLS 透传 | P2 |

---

## 9. 性能测试计划

### 9.1 测试方法论

采用**对照测试法**，对比"无 Agent"和"有 Agent（不同模式）"的性能差异，量化 Agent 带来的性能开销。

### 9.2 性能指标定义

| 指标 | 说明 | 采集方式 |
|------|------|---------|
| QPS | 每秒查询数 | JMeter / wrk / ghz(gRPC) 统计 |
| Avg RT | 平均响应时间 | JMeter / wrk 统计 |
| TP90 | 90 百分位响应时间 | JMeter 统计 |
| TP99 | 99 百分位响应时间 | JMeter 统计 |
| CPU % | CPU 使用率 | async-profiler / JVisualVM / top / Docker stats |
| Heap Memory | 堆内存使用量 | JVisualVM / jmap / jcmd |
| GC 次数 | Young GC / Full GC 次数 | GC 日志 / GCViewer / JVisualVM |
| GC Pause | GC 暂停时间 | GC 日志 / -XX:+PrintGCDetails |
| Alloc Rate | 对象分配速率 | async-profiler / JFR |
| Startup Time | JVM 启动到就绪时间 | 脚本计时 |
| Class Loading | 已加载类数量 | -XX:+TraceClassLoading / jstat |
| Network Throughput | 网络吞吐量（入/出） | Docker stats / iftop |
| File Descriptor Count | 文件描述符数 | lsof / /proc/*/fd |
| Off-Heap Memory | Netty 直接内存 | Netty 指标 / jemalloc |
| Thread Count | 活跃线程数 | jstack / JVisualVM |

### 9.3 性能基线（无 Agent）

| 用例ID | 测试场景 | 并发数 | 持续时间 | 指标 |
|--------|---------|-------|---------|------|
| PT-BASE-001 | HTTP GET 空响应 | 10 / 50 / 100 / 500 | 60s | QPS, Avg RT, TP99 |
| PT-BASE-002 | HTTP POST 1KB Body | 10 / 50 / 100 / 500 | 60s | QPS, Avg RT, TP99 |
| PT-BASE-003 | TCP 短连接 | 10 / 50 / 100 | 60s | QPS, Avg RT |
| PT-BASE-004 | TCP 长连接 | 10 / 50 / 100 | 60s | QPS, Avg RT |
| PT-BASE-005 | Kafka Produce 1KB | 10 / 50 / 100 | 60s | TPS, 延迟 |
| PT-BASE-006 | Kafka Consume 1KB | 10 / 50 / 100 | 60s | TPS, 延迟 |
| PT-BASE-007 | gRPC Unary 调用 | 10 / 50 / 100 | 60s | QPS, Avg RT, TP99 |

### 9.4 Agent 性能影响测试

#### HTTP 协议

| 用例ID | 测试场景 | 模式 | 并发数 | 验收标准 | 优先级 |
|--------|---------|------|-------|---------|-------|
| PT-HTTP-001 | HTTP GET QPS 影响 | stub | 100 | QPS 下降 ≤ 15% | P0 |
| PT-HTTP-002 | HTTP GET RT 影响 | stub | 100 | Avg RT 增加 ≤ 20% | P0 |
| PT-HTTP-003 | HTTP GET CPU 影响 | stub | 100 | CPU 增加 ≤ 10% | P1 |
| PT-HTTP-004 | HTTP GET 内存影响 | stub | 100 | 堆内存增长 ≤ 50MB | P1 |
| PT-HTTP-005 | HTTP passthrough 开销 | passthrough | 100 | QPS 下降 ≤ 10% | P1 |
| PT-HTTP-006 | HTTP record 模式开销 | record | 100 | QPS 下降 ≤ 20% | P1 |
| PT-HTTP-007 | HTTP 高并发稳定性 | stub | 500 | 无异常，持续 5 分钟 | P0 |

#### TCP 协议

| 用例ID | 测试场景 | 模式 | 并发数 | 验收标准 | 优先级 |
|--------|---------|------|-------|---------|-------|
| PT-TCP-001 | TCP 短连接性能 | stub | 50 | QPS 下降 ≤ 15% | P1 |
| PT-TCP-002 | TCP 长连接性能 | stub | 50 | QPS 下降 ≤ 10% | P1 |

#### Kafka 协议

| 用例ID | 测试场景 | 模式 | 并发数 | 验收标准 | 优先级 |
|--------|---------|------|-------|---------|-------|
| PT-KAFKA-001 | Kafka Produce 性能 | stub | 50 | TPS 下降 ≤ 20% | P1 |
| PT-KAFKA-002 | Kafka Consume 性能 | stub | 50 | TPS 下降 ≤ 20% | P1 |

#### gRPC 协议

| 用例ID | 测试场景 | 模式 | 并发数 | 验收标准 | 优先级 |
|--------|---------|------|-------|---------|-------|
| PT-GRPC-001 | gRPC Unary QPS 影响 | stub | 50 | QPS 下降 ≤ 20% | P1 |
| PT-GRPC-002 | gRPC Unary RT 影响 | stub | 50 | Avg RT 增加 ≤ 25% | P1 |
| PT-GRPC-003 | gRPC passthrough 开销 | passthrough | 50 | QPS 下降 ≤ 15% | P2 |
| PT-GRPC-004 | gRPC 高并发稳定性 | stub | 200 | 无异常，持续 5 分钟 | P1 |

#### 多协议混合

| 用例ID | 测试场景 | 模式 | 并发数 | 验收标准 | 优先级 |
|--------|---------|------|-------|---------|-------|
| PT-MIX-001 | HTTP + Kafka 混合压测 | stub | HTTP:100, Kafka:50 | 各协议性能下降 ≤ 25% | P2 |

### 9.5 Server 端性能

| 用例ID | 测试场景 | 并发数 | 持续时间 | 指标 | 优先级 |
|--------|---------|-------|---------|------|-------|
| PT-SERVER-001 | HTTP Mock 最大 QPS | 1000 | 60s | QPS 峰值 | P1 |
| PT-SERVER-002 | TCP Mock 最大并发连接 | 1000 | 60s | 最大连接数 | P2 |
| PT-SERVER-003 | Server 内存泄漏检查 | 100 | 30min | 内存趋势平稳 | P0 |
| PT-SERVER-004 | 大量规则下的匹配性能 | 1000 条规则 | - | 单次匹配 < 1ms | P1 |
| PT-SERVER-005 | gRPC Mock 最大 QPS | 500 | 60s | QPS 峰值 | P1 |

### 9.6 企业级应用性能测试

| 用例ID | 测试场景 | 应用 | 指标 | 优先级 |
|--------|---------|------|------|-------|
| PT-ENT-001 | PetClinic 首页 QPS 影响 | Spring Boot PetClinic | QPS 下降 ≤ 20% | P1 |
| PT-ENT-002 | PetClinic 数据库查询性能 | Spring Boot PetClinic | RT 增加 ≤ 15% | P1 |
| PT-ENT-003 | Spring Cloud Gateway 转发性能 | Spring Cloud Gateway | QPS 下降 ≤ 20% | P1 |
| PT-ENT-004 | Nacos 配置拉取性能 | Nacos | RT 增加 ≤ 15% | P2 |
| PT-ENT-005 | Keycloak 登录接口性能 | Keycloak | RT 增加 ≤ 20% | P2 |

### 9.7 Agent 启动性能影响

| 用例ID | 测试场景 | 指标 | 验收标准 | 优先级 |
|--------|---------|------|---------|-------|
| PT-STARTUP-001 | 无 Agent 启动时间 | 从 JVM 启动到应用就绪 | 建立基线 | P1 |
| PT-STARTUP-002 | 有 Agent 启动时间（stub 模式） | 从 JVM 启动到应用就绪 | 启动增加 ≤ 3s | P1 |
| PT-STARTUP-003 | 有 Agent 启动时间（无 Server 连接） | 从 JVM 启动到应用就绪 | Agent 不阻塞应用启动 | P1 |
| PT-STARTUP-004 | 类加载数量对比 | 已加载类数量 | 增加 ≤ 500 类 | P2 |
| PT-STARTUP-005 | 插件加载性能 | 5 个插件加载时间 | 插件不显著增加启动时间 | P2 |

### 9.8 大型规则集匹配性能

| 用例ID | 测试场景 | 规则数 | 验收标准 | 优先级 |
|--------|---------|-------|---------|-------|
| PT-RULESET-001 | 1000 条规则单次匹配 | 1000 | ≤ 1ms | P1 |
| PT-RULESET-002 | 10000 条规则单次匹配 | 10000 | ≤ 5ms | P2 |
| PT-RULESET-003 | 并发匹配 + 规则热更新 | 1000 规则 + 10 并发更新 | 匹配不中断，错误率 < 0.1% | P1 |
| PT-RULESET-004 | 大量条件组合匹配（5 个条件 AND） | 1000 规则 | 匹配时间 ≤ 2ms | P1 |

### 9.9 性能分析工具

**推荐工具**：

| 用途 | 推荐工具 | 命令/方式 |
|------|---------|----------|
| CPU 火焰图 | async-profiler | `profiler.sh -d 60 -f flamegraph.html <pid>` |
| 分配分析 | async-profiler / JFR | `profiler.sh -d 60 -e alloc -f alloc.html <pid>` |
| GC 分析 | GCViewer / GCEasy | 解析 gc.log |
| 堆转储 | jmap / jcmd | `jmap -dump:live,format=b,file=heap.hprof <pid>` |
| 线程转储 | jstack | `jstack <pid> > threaddump.txt` |
| HTTP 压测 | wrk / ghz(gRPC) | `wrk -t4 -c100 -d60s http://...` |
| TCP 压测 | iperf / 自定义 | 自定义 TCP 客户端 |
| Kafka 压测 | kafka-producer-perf-test | Kafka 自带工具 |

### 9.10 性能测试工具选择

**推荐工具**：
- 轻量级：`wrk` / `ab`（HTTP 压测）
- 重量级：JMeter（多协议、复杂场景）
- 监控：JVisualVM / JConsole / Docker stats
- GC 分析：GCViewer / GCEasy

**简化方案**（优先）：
使用 wrk 做 HTTP 压测，配合 JVM 自带工具监控资源。

---

## 10. 兼容性测试计划

### 10.1 JDK 版本兼容性

| 用例ID | JDK 版本 | 厂商 | 测试内容 | 优先级 |
|--------|---------|------|---------|-------|
| COMP-JDK-001 | JDK 8u202 | OpenJDK / Oracle | 全功能测试（HTTP + TCP + Kafka + gRPC） | P0 |
| COMP-JDK-002 | JDK 8u345+ | Eclipse Temurin / Amazon Corretto | 全功能测试 | P0 |
| COMP-JDK-003 | JDK 11 | OpenJDK / Eclipse Temurin | 全功能测试 | P0 |
| COMP-JDK-004 | JDK 17 | OpenJDK / Eclipse Temurin | 全功能测试 + `--add-opens` 验证 | P0 |
| COMP-JDK-005 | JDK 21 | OpenJDK / Eclipse Temurin | 全功能测试 + 虚拟线程兼容性 | P1 |
| COMP-JDK-006 | JDK 21 虚拟线程 | Eclipse Temurin | Agent 在虚拟线程下正常工作 | P1 |
| COMP-JDK-007 | GraalVM JDK 17 | GraalVM | 全功能测试 + Native Image 可行性评估 | P2 |
| COMP-JDK-008 | IBM Semeru JDK 17 | IBM Semeru | 全功能测试（OpenJ9 JVM） | P2 |

**注意事项**：
- JDK 9+ 需要 `--add-opens java.base/java.net=ALL-UNNAMED`
- Byte Buddy 1.14.14 与 JDK 版本兼容性验证（特别是 JDK 21 虚拟线程）
- JDK 8u202 是最后一个免费商用版本，u345 以后需特别注意字节码差异
- JDK 21 虚拟线程（Project Loom）：验证 Agent 字节码增强在虚拟线程下不产生死锁
- GraalVM Native Image：Agent 的字节码增强在 AOT 编译下不工作，需明确记录不支持
- IBM Semeru (OpenJ9)：验证 ByteBuddy 在 OpenJ9 JVM 下的兼容性

### 10.2 操作系统兼容性

| 用例ID | 操作系统 | 架构 | 版本 | 测试内容 | 优先级 |
|--------|---------|------|------|---------|-------|
| COMP-OS-001 | Linux | x86_64 | Ubuntu 20.04 / 22.04 | 全功能测试 | P0 |
| COMP-OS-002 | Linux | aarch64 | Ubuntu 22.04 (ARM) | 全功能测试 | P1 |
| COMP-OS-003 | Windows | x86_64 | Windows 10 / 11 | 冒烟测试（开发环境） | P1 |
| COMP-OS-004 | macOS | aarch64 (Apple Silicon) | macOS 14+ | 冒烟测试（开发环境） | P1 |
| COMP-OS-005 | macOS | x86_64 | macOS 12+ | 冒烟测试（开发环境） | P1 |
| COMP-OS-006 | Alpine Linux | x86_64 | Alpine 3.18+ (musl libc) | Docker 镜像兼容性 | P1 |

**注意事项**：
- ARM64 (aarch64) 架构需验证 Netty native transport 是否可用（fallback 到 NIO）
- Alpine Linux 使用 musl libc 而非 glibc，需验证 JVM 行为差异
- macOS Apple Silicon 下 Docker 通过 Rosetta 2 模拟 x86_64，需额外关注

### 10.3 HTTP 客户端兼容性

| 用例ID | HTTP 客户端 | 版本 | 测试内容 | 优先级 |
|--------|------------|------|---------|-------|
| COMP-HTTP-001 | OkHttp | 3.x / 4.x | GET/POST 拦截 | P0 |
| COMP-HTTP-002 | Feign (OkHttp 底层) | 10.x / 11.x | Feign 调用拦截 | P0 |
| COMP-HTTP-003 | RestTemplate | Spring 5.x | RestTemplate 拦截 | P1 |
| COMP-HTTP-004 | URLConnection | JDK 内置 | 基础拦截 | P1 |
| COMP-HTTP-005 | WebClient (Spring 5) | - | 响应式客户端（需确认是否支持） | P2 |
| COMP-HTTP-006 | Apache HttpClient | 4.x / 5.x | HttpClient 拦截 | P1 |

### 10.4 gRPC 客户端兼容性

| 用例ID | gRPC 客户端 | 版本 | 测试内容 | 优先级 |
|--------|------------|------|---------|-------|
| COMP-GRPC-001 | gRPC Java (Netty 传输) | 1.40+ / 1.50+ / 1.60+ | Unary 调用拦截 | P0 |
| COMP-GRPC-002 | gRPC Java (OkHttp 传输) | 1.40+ | Unary 调用拦截 | P1 |
| COMP-GRPC-003 | Spring Boot Starter (net.devh) | 2.14+ | gRPC 调用拦截 | P1 |
| COMP-GRPC-004 | Server Streaming | - | 流式调用（确认是否支持，不支持则记录） | P2 |
| COMP-GRPC-005 | Client Streaming | - | 流式调用（确认是否支持，不支持则记录） | P2 |
| COMP-GRPC-006 | Bidirectional Streaming | - | 双向流（确认是否支持，不支持则记录） | P2 |

### 10.5 协议 TLS/SSL 与 SASL 兼容性

| 用例ID | 协议 | 安全配置 | 测试内容 | 优先级 |
|--------|------|---------|---------|-------|
| COMP-TLS-001 | HTTPS | TLS 1.2 | Passthrough 代理正确转发 | P1 |
| COMP-TLS-002 | HTTPS | TLS 1.3 | Passthrough 代理正确转发 | P1 |
| COMP-TLS-003 | HTTPS | 自签名证书 | SSL 验证禁用模式可行 | P1 |
| COMP-TLS-004 | HTTPS | 标准 CA 证书 | 默认信任管理器验证 | P2 |
| COMP-TLS-005 | Kafka | SSL | Kafka Mock Broker SSL 支持 | P1 |
| COMP-TLS-006 | Kafka | SASL/PLAIN | Kafka SASL 认证兼容性 | P2 |
| COMP-TLS-007 | Kafka | SASL/SCRAM | Kafka SCRAM 认证兼容性 | P2 |
| COMP-TLS-008 | gRPC | TLS | gRPC TLS 通道重定向 | P2 |
| COMP-TLS-009 | Pulsar | TLS | Pulsar TLS 连接兼容性 | P2 |

### 10.6 数据库兼容性

| 用例ID | 数据库 | 版本 | 测试内容 | 优先级 |
|--------|-------|------|---------|-------|
| COMP-DB-001 | H2 (内嵌) | 2.2.224 | 所有存储操作 | P0 |
| COMP-DB-002 | PostgreSQL | 14 / 15 | 所有存储操作 | P0 |
| COMP-DB-003 | MySQL | 8.0 | 兼容性验证（可选） | P2 |

### 10.7 多 Agent 共存兼容性

#### 10.7.1 共存 Agent 矩阵

| 用例ID | 共存 Agent | 版本 | 类型 | 测试内容 | 优先级 | 状态 |
|--------|-----------|------|------|---------|-------|------|
| COMP-AGENT-001 | JaCoCo (本项目使用) | 0.8.12 | 覆盖率采集 | 同时挂载，覆盖率数据正常采集 | P0 | ✅ 已测试 |
| COMP-AGENT-002 | SkyWalking | 9.4.0 | APM 监控 | 同时挂载，链路追踪正常 | P1 | ✅ 已测试 |
| COMP-AGENT-003 | Pinpoint | latest | APM 监控 | 同时挂载，功能不冲突 | P2 | 待测试 |
| COMP-AGENT-004 | Arthas | latest | 诊断工具 | 动态 Attach 共存 | P2 | 待测试 |
| COMP-AGENT-005 | Prometheus JMX Exporter | latest | 监控采集 | JMX 采集与 Agent 共存 | P2 | 待测试 |
| COMP-AGENT-006 | Takin Agent | latest | 全链路压测 | 动态 Agent 植入共存，影子链路不冲突 | P1 | 待测试 |
| COMP-AGENT-007 | AREX Agent | latest | 录制回放 | 录制回放 Agent 共存，功能协同 | P1 | 待测试 |
| COMP-AGENT-008 | JaCoCo + SkyWalking + Baafoo | 见上 | 多 Agent 组合 | 三个 Agent 同时挂载，详细测试见 §10.7.2 | P0 | ✅ 已测试 |

#### 10.7.2 三 Agent 组合测试方案（JaCoCo + SkyWalking + Baafoo）

**测试目标**: 验证 Baafoo Agent 与主流 APM/Coverage Agent（SkyWalking、JaCoCo）同时挂载到 Spring Cloud Alibaba 微服务应用时，三方的字节码增强互不冲突，各项功能均正常工作。

**测试范围**: spring-cloud-alibaba 微服务测试场景（Provider + Consumer + Nacos + Feign），覆盖应用启动、Baafoo Mock 拦截（host-based + serviceName-based）、SkyWalking 链路追踪、JaCoCo 覆盖率采集四大能力。

**优先级**: P0（JaCoCo 是本项目自身的覆盖率工具，必须验证共存；SkyWalking 是国内最常用的 APM Agent）

##### 10.7.2.1 测试矩阵

| Agent | 版本 | 类型 | 加载顺序 | 作用 |
|-------|------|------|---------|------|
| JaCoCo | 0.8.12 | 覆盖率采集 | 1 (首位) | 采集测试覆盖率数据 |
| SkyWalking | 9.4.0 | APM 监控 | 2 | 链路追踪、性能监控 |
| Baafoo | 1.1.0-SNAPSHOT | Mock/挡板 | 3 (末位) | 微服务调用拦截、Mock 响应 |

**加载顺序选择依据**:
- JaCoCo 必须在首位（覆盖率 Agent 需要在类加载时第一时间注入探针）
- SkyWalking 在中间，APM 类 Agent 应在 Mock 类 Agent 前
- Baafoo 在末位，业务挡板类 Agent 应在监控类 Agent 后

##### 10.7.2.2 测试环境

**新增容器**:

| 容器 | 镜像 | 端口 | 作用 |
|------|------|------|------|
| `baafoo-enterprise-oap` | `apache/skywalking-oap-server:9.4.0-java17` | 11800(gRPC), 12800(HTTP) | SkyWalking OAP 后端 |
| `baafoo-enterprise-ui` | `apache/skywalking-ui:9.4.0-java17` | 18080→8080 | SkyWalking Web UI（可选） |

**Agent JAR 来源**:

| Agent | 下载地址 | 大小（约） |
|-------|---------|-----------|
| JaCoCo | `https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/0.8.12/org.jacoco.agent-0.8.12-runtime.jar` | 302KB |
| SkyWalking | 从 Docker 镜像 `apache/skywalking-java-agent:9.4.0-java17` 提取 `/skywalking/agent` 目录 | 34MB（解压后） |

> **注意**: JaCoCo 必须使用 `-runtime.jar`（含 `Premain-Class`），而非库 JAR；SkyWalking 从 Docker 镜像提取比从 archive.apache.org 下载 tgz 更可靠。

**Agent 配置参数**:

JaCoCo（append 模式，输出 classdumps 到 `/tmp/jacoco/classdumps`）:
```
-javaagent:/app/agents/jacoco-agent.jar=output=tcpserver,address=0.0.0.0,port=6300,append=true,classdumpdir=/tmp/jacoco/classdumps
```

SkyWalking（指向 OAP 后端，服务名按容器区分）:
```
-javaagent:/app/agents/skywalking/skywalking-agent.jar
-DSW_AGENT_NAME=service-consumer
-DSW_AGENT_COLLECTOR_BACKEND_SERVICES=baafoo-enterprise-oap:11800
```

Baafoo（保持现有配置）:
```
-javaagent:/app/baafoo-agent.jar=config=/app/baafoo-agent.yml
```

**最终 JVM 启动命令**:
```bash
java ${JAVA_OPTS} \
  -javaagent:/app/agents/jacoco-agent.jar=output=tcpserver,address=0.0.0.0,port=6300,append=true,classdumpdir=/tmp/jacoco/classdumps \
  -javaagent:/app/agents/skywalking/skywalking-agent.jar \
  -javaagent:/app/baafoo-agent.jar=config=/app/baafoo-agent.yml \
  -jar /app/app.jar
```

##### 10.7.2.3 详细测试用例

###### MULTI-001: 三 Agent 同时加载应用启动正常 (P0)

**目标**: 验证 Provider/Consumer 同时挂载 JaCoCo + SkyWalking + Baafoo 三个 Agent 后，应用能正常启动，健康检查通过，无字节码转换冲突。

| 项 | 内容 |
|----|------|
| **前置条件** | Nacos、Baafoo Server、SkyWalking OAP 均健康 |
| **执行步骤** | 1. 构建含 3 个 Agent 的 Provider/Consumer 镜像<br>2. `docker compose up -d`<br>3. 等待 90 秒观察启动日志 |
| **预期结果** | 1. 容器健康检查通过（`healthy`）<br>2. 日志含 SkyWalking agent 启动信息（`SkyWalking agent initialized`）<br>3. 日志含 Baafoo agent 启动信息（`Baafoo Agent started`）<br>4. 日志无 `ClassCastException`/`NoClassDefFoundError`/`LinkageError` 等字节码冲突错误<br>5. 日志无 ByteBuddy `transform error` |
| **通过标准** | 全部预期结果满足 |

###### MULTI-002: Baafoo Mock 拦截功能正常 (P0)

**目标**: 验证在 JaCoCo + SkyWalking 同时存在时，Baafoo 的 host-based 和 serviceName-based Mock 拦截能力均正常工作。

| 项 | 内容 |
|----|------|
| **前置条件** | MULTI-001 通过；Mock 规则已创建 |
| **执行步骤** | 1. `GET http://localhost:18083/echo-feign/test`<br>2. 检查响应 body<br>3. 检查 Consumer Agent 日志的 HttpOpenServerAdvice/DnsResolve 拦截记录<br>4. 检查 Server 端规则匹配日志 |
| **预期结果** | 1. 响应 200，body 为 Mock 数据<br>2. Agent 日志含 `HttpOpenServerAdvice redirect` 和 `DnsResolve redirect`<br>3. Server 端无 `No Baafoo rule matched` 日志<br>4. JaCoCo/SkyWalking 不干扰 Baafoo 的 Socket/HttpClient 拦截 |
| **通过标准** | 全部预期结果满足 |

###### MULTI-003: SkyWalking 链路追踪数据生成正常 (P0)

**目标**: 验证 Baafoo Agent 不影响 SkyWalking 的字节码注入，链路追踪数据能正常上报 OAP 后端。

| 项 | 内容 |
|----|------|
| **前置条件** | MULTI-001 通过；SkyWalking OAP 已启动 |
| **执行步骤** | 1. 触发 Feign 调用 `GET http://localhost:18083/echo-feign/test`<br>2. 等待 15 秒（SkyWalking 数据上报周期）<br>3. 查询 SkyWalking OAP API: `POST http://localhost:12800/graphql` 查询服务列表<br>4. 验证 Consumer 和 Provider 服务均已注册 |
| **预期结果** | 1. SkyWalking OAP 收到 service-consumer 和 service-provider 服务数据<br>2. 不出现 SkyWalking 上报失败的错误日志 |
| **通过标准** | OAP 服务列表包含至少 2 个服务 |

###### MULTI-004: JaCoCo 覆盖率数据生成正常 (P0)

**目标**: 验证 Baafoo + SkyWalking 不影响 JaCoCo 的字节码注入，覆盖率数据能正常采集。

| 项 | 内容 |
|----|------|
| **前置条件** | MULTI-001 通过 |
| **执行步骤** | 1. 触发若干次 Feign 调用（产生覆盖率数据）<br>2. 通过 `docker exec` 检查容器内 `/tmp/jacoco/classdumps` 目录的 .class 文件数量<br>3. 验证 Consumer 和 Provider 应用类均被插桩 |
| **预期结果** | 1. classdumps 目录含大量 .class 文件（> 10000）<br>2. Consumer 和 Provider 应用类均被 JaCoCo 插桩<br>3. 不出现 JaCoCo 字节码注入失败日志 |
| **通过标准** | 两个容器的 classdumps 目录均含 .class 文件 |

###### MULTI-005: Feign 调用链路在 SkyWalking 中可见 (P1)

**目标**: 验证 Mock 拦截的 Feign 调用也能被 SkyWalking 追踪到（Baafoo 重定向后 trace 不断裂）。

| 项 | 内容 |
|----|------|
| **前置条件** | MULTI-002 和 MULTI-003 通过 |
| **执行步骤** | 1. 触发 `GET http://localhost:18083/echo-feign/test`<br>2. 等待 15 秒<br>3. 查询 SkyWalking OAP API 确认 trace 数据存在<br>4. 检查 trace 中是否含 Consumer → Provider 的 span |
| **预期结果** | 1. Trace 含 Consumer 入口 span<br>2. Trace 不因 Baafoo 重定向而断裂<br>3. OAP 中 Consumer 和 Provider 服务均可见 |
| **通过标准** | 两个服务均在 OAP 中注册（证明 trace 数据流完整） |

###### MULTI-006: 多 Agent 加载顺序影响测试 (P1)

**目标**: 验证不同 Agent 加载顺序对功能的影响，确定推荐顺序。

**测试变体**:
- 变体 A: `jacoco → skywalking → baafoo`（推荐顺序）
- 变体 B: `skywalking → jacoco → baafoo`
- 变体 C: `baafoo → jacoco → skywalking`（Baafoo 在首位）

| 项 | 内容 |
|----|------|
| **执行步骤** | 对每个变体重新构建镜像、启动、运行 MULTI-001~002 测试 |
| **预期结果** | 变体 A、B 全部通过；变体 C 可能出现 Baafoo Bootstrap 类被 JaCoCo 增强后冲突 |
| **通过标准** | 变体 A、B 通过即可；变体 C 失败不影响通过 |

###### MULTI-007: 性能影响评估 (P2)

**目标**: 评估多 Agent 同时挂载对应用性能的影响。

| 项 | 内容 |
|----|------|
| **执行步骤** | 1. 单 Baafoo Agent 场景下用 `ab` 压测 1000 次<br>2. 三 Agent 场景下用 `ab` 压测 1000 次<br>3. 对比平均响应时间、QPS |
| **预期结果** | 三 Agent 场景响应时间增加 < 50% |
| **通过标准** | 性能衰减在可接受范围（< 50%） |

###### MULTI-008: 类转换冲突检测 (P1)

**目标**: 检测多 Agent 是否存在字节码转换冲突（同一类被多个 Agent 重复增强导致异常）。

| 项 | 内容 |
|----|------|
| **执行步骤** | 1. 检查应用启动日志中 ByteBuddy/ASM 的 retransform 错误<br>2. 检查 SkyWalking 是否能正常增强 `HttpURLConnection`、`Socket` 等类（与 Baafoo 拦截点重叠）<br>3. 检查 JaCoCo 是否能正常增强 Consumer 应用业务类 |
| **预期结果** | 1. 无 `ByteBuddy transform error` 日志<br>2. 无 `ClassFormatError`/`VerifyError`<br>3. SkyWalking 增强的类不与 Baafoo 增强的类（java.net.InetAddress、sun.net.www.http.HttpClient）冲突 |
| **通过标准** | 启动日志无转换冲突错误 |

##### 10.7.2.4 验收标准

| 用例 ID | 优先级 | 必须通过 |
|---------|-------|---------|
| MULTI-001 | P0 | ✅ |
| MULTI-002 | P0 | ✅ 已断言（v2.10） |
| MULTI-003 | P0 | ✅ 已断言（v2.10） |
| MULTI-004 | P0 | ✅ 已断言（v2.10） |
| MULTI-005 | P1 | ✅ 已断言（v2.10） |
| MULTI-006 | P1 | ✅ 已断言（v2.10） |
| MULTI-007 | P2 | ✅ 已断言（v2.10） |
| MULTI-008 | P1 | ✅ 已断言（v2.10） |

**最终判定**: P0 用例全部通过 + P1 用例至少通过 2/3 = 测试通过

##### 10.7.2.5 风险与缓解措施

**已知风险**:

| 风险 | 说明 | 缓解措施 |
|------|------|---------|
| Bootstrap CL 类冲突 | Baafoo 将 GlobalRouteState 注入到 Bootstrap CL，SkyWalking 也可能注入 Bootstrap 类，存在类定义冲突风险 | 使用不同包名；测试中验证 |
| java.net.InetAddress 增强冲突 | SkyWalking 增强 HTTP 客户端相关类，Baafoo 通过 DnsResolve*Advice 增强 `java.net.InetAddress`，可能冲突 | 通过 MULTI-008 专项检测 |
| JaCoCo 增强时机 | JaCoCo 在类加载时增强，Baafoo 在类加载后（retransform）增强，理论上不冲突 | 已验证通过 |
| 内存压力 | 三 Agent 同时挂载增加约 100MB JVM metaspace | 内存上限从 256m 提升到 512m |

**Agent 加载顺序注意事项**：
- JaCoCo 必须放在第一位（`-javaagent:jacoco.jar -javaagent:baafoo-agent.jar`）
- 多个字节码增强 Agent 可能有类转换冲突
- Bootstrap ClassLoader 注入的类可能冲突
- 建议：监控类 Agent 在前，业务挡板类 Agent 在后

##### 10.7.2.6 执行流程

```
[阶段 1: 设计文档] → [阶段 2: 下载 Agent JAR]
                     ↓
                  [阶段 3: 修改 Dockerfile/docker-compose]
                     ↓
                  [阶段 4: 构建镜像]
                     ↓
                  [阶段 5: 启动环境，等待健康]
                     ↓
                  [阶段 6: 执行 MULTI-001~005 测试]
                     ↓
                  [阶段 7: 执行 MULTI-008 冲突检测]
                     ↓
                  [阶段 8: 汇总测试报告]
```

##### 10.7.2.7 测试报告

详细测试结果见 `testing/4_E2ETest/enterprise/MULTI-AGENT-TEST-REPORT.md`，包含:
- 测试环境拓扑图
- 每个用例的执行结果（PASS/FAIL/SKIP）
- 关键证据（日志片段、数据）
- 发现的问题与解决方案
- 多 Agent 共存指南（推荐加载顺序、配置注意事项）

### 10.8 容器化兼容性

| 用例ID | 容器运行时 | 测试内容 | 优先级 |
|--------|-----------|---------|-------|
| COMP-DOCKER-001 | Docker Engine 20.10+ | Docker 镜像正常运行 | P0 |
| COMP-DOCKER-002 | Docker Desktop | Windows/macOS 开发环境 | P1 |
| COMP-DOCKER-003 | Rancher Desktop | macOS 替代容器运行时 | P1 |
| COMP-DOCKER-004 | Podman | Red Hat 容器替代方案 | P2 |
| COMP-DOCKER-005 | Kubernetes (minikube) | K8s 中部署 Agent（DaemonSet/Pod 级别） | P2 |
| COMP-DOCKER-006 | Kubernetes + Istio | Service Mesh 环境下 Agent 兼容性 | P2 |
| COMP-DOCKER-007 | containerd | containerd 运行时兼容 | P2 |
| COMP-DOCKER-008 | Docker Compose V2 | 多容器编排环境 | P0 |

### 10.9 企业级应用框架兼容性

| 用例ID | 应用框架 | 版本 | 测试内容 | 优先级 |
|--------|---------|------|---------|-------|
| COMP-FRAME-001 | Spring Boot | 2.7.x | 核心功能验证 | P0 |
| COMP-FRAME-002 | Spring Boot | 3.x (Jakarta EE 9+) | Servlet/WebFlux 兼容 | P1 |
| COMP-FRAME-003 | Spring Cloud | 2021.x / 2022.x | 微服务环境验证 | P1 |
| COMP-FRAME-004 | Spring Boot WebFlux | 2.7.x / 3.x | 响应式栈兼容性 | P2 |
| COMP-FRAME-005 | Quarkus | 2.x / 3.x | （Keycloak 等应用使用） | P2 |
| COMP-FRAME-006 | Solon / JFinal | - | 国产框架验证（可选） | P2 |
| COMP-FRAME-007 | Helidon SE/MP | 3.x / 4.x | 轻量级微服务框架 | P2 |
| COMP-FRAME-008 | Spring Cloud Alibaba | 2021.x / 2022.x | Nacos/Sentinel/RocketMQ 集成 | P1 |
| COMP-FRAME-009 | Undertow | - | Netty vs Undertow 兼容性 | P2 |

---

## 11. 稳定性测试计划

### 11.1 长时间运行稳定性

| 用例ID | 测试场景 | 持续时间 | 并发数 | 验收标准 | 优先级 |
|--------|---------|---------|-------|---------|-------|
| STAB-001 | HTTP Mock 持续运行 | 72 小时 | 10 并发持续请求 | 无 Crash、无 OOM、无异常 | P0 |
| STAB-002 | 模式频繁切换稳定性 | 24 小时 | 每 30s 切换一次模式 | 模式切换正常，无异常 | P1 |
| STAB-003 | 规则频繁更新稳定性 | 24 小时 | 每 10s 更新一条规则 | 规则更新正常，无内存泄漏 | P1 |

### 11.2 高并发稳定性

| 用例ID | 测试场景 | 并发数 | 持续时间 | 验收标准 | 优先级 |
|--------|---------|-------|---------|---------|-------|
| STAB-HC-001 | HTTP 高并发压测 | 500 | 2 小时 | 无异常、无 OOM、错误率 < 0.1% | P0 |
| STAB-HC-002 | TCP 长连接高并发 | 500 连接 | 2 小时 | 连接不断开、无内存泄漏 | P1 |
| STAB-HC-003 | Kafka 高并发生产消费 | 100 | 2 小时 | 无消息丢失、无异常 | P1 |

### 11.3 内存泄漏检查

| 用例ID | 测试场景 | 方法 | 验收标准 | 优先级 |
|--------|---------|------|---------|-------|
| STAB-MEM-001 | Agent 内存泄漏 | 持续压测 + Full GC 后对比堆内存 | Full GC 后堆内存趋势平稳 | P0 |
| STAB-MEM-002 | Server 内存泄漏 | 持续压测 + 堆 dump 分析 | 无明显泄漏对象 | P0 |
| STAB-MEM-003 | 录制功能内存泄漏 | record 模式持续录制 | 缓冲区不溢出，正常落盘 | P1 |
| STAB-MEM-004 | 企业应用 Agent 内存泄漏 | PetClinic 运行 24h + 堆对比 | 无持续性内存增长 | P1 |

### 11.4 线程安全验证

| 用例ID | 测试场景 | 方法 | 验收标准 | 优先级 |
|--------|---------|------|---------|-------|
| STAB-THREAD-001 | RouteTable 并发读写 | 多线程同时读+写 | 无 ConcurrentModificationException | P0 |
| STAB-THREAD-002 | 规则热更新并发 | 多线程更新规则 | 数据一致性 | P1 |
| STAB-THREAD-003 | 录制缓冲区并发 | 多线程写录制缓冲 | 无数据丢失/损坏 | P1 |

### 11.5 异常恢复能力

| 用例ID | 测试场景 | 测试方法 | 验收标准 | 优先级 |
|--------|---------|---------|---------|-------|
| STAB-REC-001 | Server 断连恢复 | 手动停止 Server 后重启 | Agent 自动重连，规则同步 | P0 |
| STAB-REC-002 | 网络抖动恢复 | 模拟网络丢包/延迟 | 恢复后功能正常 | P1 |
| STAB-REC-003 | 插件异常隔离 | 插件抛运行时异常 | Agent 不崩溃，插件被禁用 | P0 |
| STAB-REC-004 | 数据库连接断开恢复 | 断开 PostgreSQL 后恢复 | Server 自动重连，数据不丢失 | P1 |

### 11.6 企业级应用长时间稳定性

| 用例ID | 测试场景 | 应用 | 持续时间 | 验收标准 | 优先级 |
|--------|---------|------|---------|---------|-------|
| STAB-ENT-001 | PetClinic + Agent 长稳 | Spring Boot PetClinic | 48 小时 | 应用正常、Agent 正常、无泄漏 | P1 |
| STAB-ENT-002 | Gateway + Agent 长稳 | Spring Cloud Gateway | 24 小时 | 网关转发正常、无异常 | P1 |

### 11.7 资源泄漏专项检查

#### 文件描述符泄漏

| 用例ID | 测试场景 | 方法 | 验收标准 | 优先级 |
|--------|---------|------|---------|-------|
| STAB-FD-001 | HTTP 持续请求文件描述符 | 持续 HTTP 请求 24h + `lsof -p <pid> \| wc -l` | FD 数趋势平稳 | P0 |
| STAB-FD-002 | TCP 长连接文件描述符 | 500 长连接保持 24h | FD 数不持续增长 | P1 |
| STAB-FD-003 | Kafka 生产消费文件描述符 | 持续生产消费 24h | FD 数趋势平稳 | P1 |
| STAB-FD-004 | Netty 连接泄漏 | 大量短连接后 Full GC | 最终 FD 数回到基线 | P0 |

#### 线程泄漏

| 用例ID | 测试场景 | 方法 | 验收标准 | 优先级 |
|--------|---------|------|---------|-------|
| STAB-THREAD-LEAK-001 | 规则热更新线程泄漏 | 1000 次规则更新后对比线程数 | 线程数不持续增长 | P1 |
| STAB-THREAD-LEAK-002 | 模式切换线程泄漏 | 1000 次模式切换后对比线程数 | 线程数不持续增长 | P1 |
| STAB-THREAD-LEAK-003 | 高并发后线程池回收 | 500 并发压测后等待 5min | 线程数回落到空闲水平 | P1 |
| STAB-THREAD-LEAK-004 | Netty EventLoop 线程泄漏 | 运行 72h 后 EventLoop 状态 | 无 EventLoop 线程泄漏 | P0 |

#### Netty 堆外内存（Direct Memory）泄漏

| 用例ID | 测试场景 | 方法 | 验收标准 | 优先级 |
|--------|---------|------|---------|-------|
| STAB-DIRECT-001 | HTTP 大响应堆外内存 | 持续 1MB 响应体请求 1h | 堆外内存趋势平稳 | P1 |
| STAB-DIRECT-002 | gRPC 消息帧堆外内存 | 持续 gRPC 请求 1h | 堆外内存趋势平稳 | P1 |
| STAB-DIRECT-003 | TCP 多轮交互堆外内存 | 持续 TCP 多轮交互 1h | 堆外内存趋势平稳 | P1 |

#### 类加载器（Metaspace）泄漏

| 用例ID | 测试场景 | 方法 | 验收标准 | 优先级 |
|--------|---------|------|---------|-------|
| STAB-META-001 | 插件热加载 Metaspace | 反复加载/卸载插件 50 次 | Metaspace 使用量不持续增长 | P1 |
| STAB-META-002 | 规则热更新类加载 | 持续规则更新 72h + class unloading | 无 ClassLoader 泄漏 | P2 |

#### 已知 P0 问题验证

基于 [deep-code-review-report.md](../.review/deep-code-review-report.md) 中的发现，**稳定性测试必须验证以下已知问题的修复状态**：

| 引用ID | 问题 | 严重度 | 验证用例 |
|--------|------|-------|---------|
| BUG-4 | TcpStubHandler Thread.sleep 阻塞 Netty EventLoop | P0 | STAB-FD-004 + STAB-THREAD-LEAK-004 中验证 TCP 延迟模式下不阻塞 |
| THREAD-1 | RouteManager.rebuildRouteTable 非原子 clear+putAll | P0 | STAB-THREAD-001 + 并发压测 STAB-HC-001 中验证无短暂空路由 |
| SEC-1 | PassthroughProxy 跳过 SSL 验证 | P0 | COMP-TLS-003/004 中验证 SSL 开关行为正确 |

---

## 12. 前端测试计划

### 12.1 前端单元测试（Vitest + Vue Test Utils）

**当前状态**：暂无单元测试（package.json 未包含 Vitest 依赖）。

**实施方案**：
1. 在 `web/package.json` 中添加：
   ```json
   {
     "devDependencies": {
       "vitest": "^1.6.0",
       "@vue/test-utils": "^2.4.0",
       "jsdom": "^24.0.0"
     },
     "scripts": {
       "test:unit": "vitest run",
       "test:unit:watch": "vitest"
     }
   }
   ```
2. 在 `web/vite.config.js` 中添加 Vitest 配置：
   ```js
   test: {
     environment: 'jsdom',
     globals: true,
     setupFiles: ['./tests/setup.js']
   }
   ```

**测试用例**：

| 用例ID | 测试项 | 组件/模块 | 优先级 |
|--------|-------|----------|-------|
| FE-UNIT-001 | BaafooLogo 组件渲染 | BaafooLogo.vue | P2 |
| FE-UNIT-002 | LoginPage 表单验证 | LoginPage.vue | P1 |
| FE-UNIT-003 | LoginPage 错误状态显示 | LoginPage.vue | P1 |
| FE-UNIT-004 | RulesPage 列表渲染 | RulesPage.vue | P1 |
| FE-UNIT-005 | RulesPage 空状态显示 | RulesPage.vue | P1 |
| FE-UNIT-006 | RulesPage 加载状态显示 | RulesPage.vue | P1 |
| FE-UNIT-007 | RuleEditorPage 条件表单验证 | RuleEditorPage.vue | P1 |
| FE-UNIT-008 | RuleEditorPage 响应配置 | RuleEditorPage.vue | P1 |
| FE-UNIT-009 | EnvironmentsPage 环境列表 | EnvironmentsPage.vue | P1 |
| FE-UNIT-010 | EnvironmentsPage 模式切换操作 | EnvironmentsPage.vue | P1 |
| FE-UNIT-011 | Pinia store - 规则状态管理 | store/index.js | P1 |
| FE-UNIT-012 | Pinia store - 环境状态管理 | store/index.js | P1 |
| FE-UNIT-013 | Pinia store - 认证状态管理 | store/index.js | P1 |
| FE-UNIT-014 | Router 导航守卫（未登录重定向） | router/index.js | P1 |
| FE-UNIT-015 | Router 权限路由（Admin 页面） | router/index.js | P1 |
| FE-UNIT-016 | API 客户端 - 请求拦截器（Token 注入） | api/index.js | P1 |
| FE-UNIT-017 | API 客户端 - 响应拦截器（401 处理） | api/index.js | P1 |
| FE-UNIT-018 | OpenApiImportDialog 组件 | OpenApiImportDialog.vue | P2 |
| FE-UNIT-019 | DashboardPage 统计渲染 | DashboardPage.vue | P2 |
| FE-UNIT-020 | RecordingPage 录制列表 | RecordingPage.vue | P2 |
| FE-UNIT-021 | ScenesPage 场景集管理 | ScenesPage.vue | P2 |
| FE-UNIT-022 | StatusPage 系统状态 | StatusPage.vue | P2 |
| FE-UNIT-023 | UsersPage 用户管理 | UsersPage.vue | P2 |
| FE-UNIT-024 | 错误边界（组件渲染异常兜底） | App.vue | P2 |

**执行方式**：
```bash
cd web
npm install -D vitest @vue/test-utils jsdom
npm run test:unit
```

### 12.2 前端 E2E 测试

**工具**：Playwright 1.61+
**配置文件**：[playwright.config.js](../web/playwright.config.js)
**测试文件**：[frontend-features.spec.js](../web/tests/frontend-features.spec.js)

#### 页面功能测试矩阵

| 页面 | 测试项 | 优先级 | 状态 |
|------|-------|-------|------|
| 登录页 | 用户名密码登录 | P0 | 待补充 |
| 登录页 | API Key 登录 | P1 | 待补充 |
| 登录页 | 错误提示 | P1 | 待补充 |
| Dashboard | 数据概览展示 | P1 | 待补充 |
| 规则列表页 | 规则列表展示 | P0 | 待补充 |
| 规则列表页 | 按协议筛选 | P1 | 待补充 |
| 规则列表页 | 搜索规则 | P1 | 待补充 |
| 规则列表页 | 删除规则 | P0 | 待补充 |
| 规则编辑器 | 创建 HTTP 规则 | P0 | 待补充 |
| 规则编辑器 | 创建 TCP 规则 | P1 | 待补充 |
| 规则编辑器 | 创建 Kafka 规则 | P1 | 待补充 |
| 规则编辑器 | 条件配置 | P0 | 待补充 |
| 规则编辑器 | 响应配置 | P0 | 待补充 |
| 规则编辑器 | 规则撤销 | P1 | 待补充 |
| 环境管理页 | 环境列表 | P0 | 待补充 |
| 环境管理页 | 创建环境 | P0 | 待补充 |
| 环境管理页 | 切换环境模式 | P0 | 待补充 |
| 环境管理页 | 删除环境 | P1 | 待补充 |
| 环境详情页 | 环境下的规则列表 | P1 | 待补充 |
| 场景集页 | 场景集列表 | P1 | 待补充 |
| 场景集页 | 创建/启用/禁用场景集 | P1 | 待补充 |
| 录制页 | 录制列表 | P1 | 待补充 |
| 录制页 | 查看录制详情 | P2 | 待补充 |
| 录制页 | 删除录制 | P2 | 待补充 |
| 状态页 | 系统状态展示 | P2 | 待补充 |
| 用户管理页 | 用户列表 | P2 | 待补充 |
| 用户管理页 | 创建/删除用户 | P2 | 待补充 |

### 12.3 前端响应式与可访问性测试

#### 响应式布局测试

| 用例ID | 视口尺寸 | 设备 | 测试内容 | 优先级 |
|--------|---------|------|---------|-------|
| FE-RESP-001 | 1920×1080 | 桌面 | 全功能验证 | P0 |
| FE-RESP-002 | 1366×768 | 笔记本 | 主要功能验证 | P1 |
| FE-RESP-003 | 1024×768 | 平板横屏 | 页面不溢出、导航可用 | P1 |
| FE-RESP-004 | 768×1024 | 平板竖屏 | 菜单折叠、内容可读 | P1 |
| FE-RESP-005 | 375×812 | 手机 (iPhone X) | 移动端适配（核心功能） | P2 |
| FE-RESP-006 | 414×896 | 手机 (Android) | 移动端适配（核心功能） | P2 |

#### 可访问性测试（a11y）

| 用例ID | 测试项 | 标准 | 工具 | 优先级 |
|--------|-------|------|------|-------|
| FE-A11Y-001 | 键盘导航完整性 | 所有交互元素可通过键盘操作 | Playwright + axe-core | P2 |
| FE-A11Y-002 | 屏幕阅读器兼容 | 表单标签、ARIA 属性完整 | axe-core | P2 |
| FE-A11Y-003 | 颜色对比度 | 文本/背景对比度 ≥ 4.5:1 | axe-core / Lighthouse | P2 |
| FE-A11Y-004 | 焦点管理 | 弹窗/对话框焦点锁定 | Playwright | P2 |

### 12.4 前端视觉回归测试

| 用例ID | 测试项 | 方法 | 工具 | 优先级 |
|--------|-------|------|------|-------|
| FE-VRT-001 | 登录页截图对比 | 基线截图 vs 当前截图 | Playwright Screenshot | P2 |
| FE-VRT-002 | 规则列表页截图对比 | 基线截图 vs 当前截图 | Playwright Screenshot | P2 |
| FE-VRT-003 | 规则编辑器截图对比 | 基线截图 vs 当前截图 | Playwright Screenshot | P2 |
| FE-VRT-004 | 环境管理页截图对比 | 基线截图 vs 当前截图 | Playwright Screenshot | P2 |
| FE-VRT-005 | Dashboard 截图对比 | 基线截图 vs 当前截图 | Playwright Screenshot | P2 |
| FE-VRT-006 | 空状态/加载状态截图对比 | 各页面空状态视觉验证 | Playwright Screenshot | P2 |
| FE-VRT-007 | 黑夜模式截图对比（如有） | 深色主题视觉验证 | Playwright Screenshot | P2 |

### 12.5 前端兼容性

| 用例ID | 浏览器 | 版本 | 测试内容 | 优先级 |
|--------|-------|------|---------|-------|
| FE-COMP-001 | Chrome | 最新版 | 全功能验证 + Playwright CI | P0 |
| FE-COMP-002 | Firefox | 最新版 | 主要功能验证 | P1 |
| FE-COMP-003 | Edge (Chromium) | 最新版 | 主要功能验证 | P1 |
| FE-COMP-004 | Safari | 最新版 | 冒烟测试（需 macOS 环境） | P2 |

### 12.6 前端执行方式

```bash
cd web

# 安装依赖
npm install

# 安装 Playwright 浏览器
npx playwright install

# 运行 E2E 测试
npx playwright test

# 运行测试并打开报告
npx playwright show-report
```

---

## 13. 安全测试计划

### 13.1 概述

Baafoo 作为可拦截和查看应用网络流量的中间件，安全性至关重要。本节覆盖认证、授权、数据安全、网络安全和依赖安全五个维度。

### 13.2 认证与授权安全

| 用例ID | 测试项 | 验证方法 | 优先级 |
|--------|-------|---------|-------|
| SEC-AUTH-001 | JWT Token 伪造 | 修改 Token 签名、header、payload | P0 |
| SEC-AUTH-002 | JWT Token 过期 | 使用过期 Token 访问 API | P0 |
| SEC-AUTH-003 | JWT Token 刷新 | Token 刷新机制 | P1 |
| SEC-AUTH-004 | API Key 暴力破解 | 枚举 API Key 测试 | P1 |
| SEC-AUTH-005 | 越权访问（水平） | 用户 A 访问用户 B 的资源 | P1 |
| SEC-AUTH-006 | 越权访问（垂直） | Guest 执行 Admin 操作 | P0 |
| SEC-AUTH-007 | 未认证访问 API | 不带任何凭证访问受保护端点 | P0 |
| SEC-AUTH-008 | Session 固定攻击 | 模拟 Session 固定 | P2 |

### 13.3 输入验证与数据安全

| 用例ID | 测试项 | 验证方法 | 优先级 |
|--------|-------|---------|-------|
| SEC-INPUT-001 | SQL 注入（规则名/条件） | 规则名/条件中注入 SQL 语句 | P0 |
| SEC-INPUT-002 | SQL 注入（API 参数） | 分页/搜索参数 SQL 注入 | P1 |
| SEC-INPUT-003 | XSS（规则名/描述） | 规则名中包含 `<script>` 标签 | P1 |
| SEC-INPUT-004 | 路径遍历（静态文件） | `../../etc/passwd` 路径访问 | P1 |
| SEC-INPUT-005 | 路径遍历（录制文件） | 录制文件名路径遍历 | P1 |
| SEC-INPUT-006 | JSON 注入 | 规则 JSON 中包含恶意内容 | P2 |
| SEC-INPUT-007 | YAML 解析漏洞 | 包含 `!!javax.script.ScriptEngine` 等 | P2 |
| SEC-INPUT-008 | 大请求体 DoS | 超大规则 JSON（>100MB） | P1 |
| SEC-INPUT-009 | 特殊字符处理 | Unicode 控制字符、空字节 | P2 |

### 13.4 网络与传输安全

| 用例ID | 测试项 | 验证方法 | 优先级 |
|--------|-------|---------|-------|
| SEC-NET-001 | TLS 配置检查 | PassthroughProxy SSL 验证 | P0 |
| SEC-NET-002 | SSL 中间人攻击 | 在不安全模式下验证 MITM 风险 | P1 |
| SEC-NET-003 | CORS 配置 | 跨域请求策略验证 | P1 |
| SEC-NET-004 | HTTP 方法滥用 | PUT/DELETE 等危险方法限制 | P1 |
| SEC-NET-005 | API 限流/防暴力 | 登录接口暴力破解防护 | P1 |

### 13.5 依赖安全

| 用例ID | 测试项 | 验证方法 | 优先级 |
|--------|-------|---------|-------|
| SEC-DEP-001 | OWASP Dependency Check | 扫描所有依赖的已知 CVE | P1 |
| SEC-DEP-002 | Netty 安全版本 | 确认 Netty 4.1.100 无高危 CVE | P1 |
| SEC-DEP-003 | Jackson 安全版本 | 确认 Jackson 2.15.3 无高危 CVE | P1 |
| SEC-DEP-004 | SnakeYAML 安全版本 | 确认 SnakeYAML 2.2 无高危 CVE | P1 |
| SEC-DEP-005 | Shade 后依赖冲突 | 检查 shaded JAR 中无重复/冲突类 | P1 |

### 13.6 敏感数据保护

| 用例ID | 测试项 | 验证方法 | 优先级 |
|--------|-------|---------|-------|
| SEC-DATA-001 | 录制数据脱敏 | 录制中是否包含敏感字段（密码/Token） | P1 |
| SEC-DATA-002 | 密码存储 | 验证密码使用 SHA-256 + Salt 存储 | P0 |
| SEC-DATA-003 | API Key 存储 | API Key 在 DB 中是否加密 | P1 |
| SEC-DATA-004 | Token 泄露 | Token 是否在日志/URL 中泄露 | P1 |
| SEC-DATA-005 | 日志脱敏 | 日志中是否记录敏感信息 | P2 |

### 13.7 安全测试工具

| 工具 | 用途 | 命令 |
|------|------|------|
| OWASP ZAP | 被动/主动扫描 | `zap.sh -daemon -port 8080` |
| OWASP Dependency Check | Maven 依赖扫描 | `mvnw org.owasp:dependency-check-maven:check` |
| nmap / netcat | 端口扫描 | `nmap -sV localhost -p 8084,9000-9005` |
| curl / Postman | 手动安全测试 | 自定义请求构造 |

**执行方式**：
```bash
# OWASP Dependency Check
mvnw org.owasp:dependency-check-maven:check

# 手动：认证绕过测试
curl -i http://localhost:8084/__baafoo__/api/rules
curl -i -H "Authorization: Bearer invalid" http://localhost:8084/__baafoo__/api/rules
```

---

## 14. 文档与API契约测试

### 14.1 API 契约测试

| 用例ID | 测试项 | 方法 | 优先级 |
|--------|-------|------|-------|
| DOC-API-001 | API 响应格式一致性 | 所有 API 返回统一 `ApiResponse` 结构 | P1 |
| DOC-API-002 | API 分页格式一致性 | 所有列表 API 返回统一 `PaginatedResult` | P1 |
| DOC-API-003 | HTTP 状态码合规 | 正确使用 200/201/204/400/401/403/404/500 | P1 |
| DOC-API-004 | 错误响应格式 | 错误时返回有意义的消息和错误码 | P1 |
| DOC-API-005 | API 版本兼容 | `/__baafoo__/api/` 路径不随意变更 | P1 |
| DOC-API-006 | MCP Schema 与 API 一致性 | MCP 工具 Schema 与实际 API 一致 | P2 |

### 14.2 配置文档验证

| 用例ID | 测试项 | 方法 | 优先级 |
|--------|-------|------|-------|
| DOC-CONF-001 | Agent 配置项完整性 | `baafoo-agent.yml` 所有字段有文档说明 | P1 |
| DOC-CONF-002 | Server 配置项完整性 | `baafoo-server.yml` 所有字段有文档说明 | P1 |
| DOC-CONF-003 | 配置默认值文档 | 所有配置项的默认值在文档中有记录 | P1 |
| DOC-CONF-004 | 环境变量覆盖文档 | 支持的环境变量有文档说明 | P2 |

### 14.3 客户端 SDK 文档验证

| 用例ID | 测试项 | 方法 | 优先级 |
|--------|-------|------|-------|
| DOC-SDK-001 | SDK 使用示例可运行 | README 中的示例代码可以成功运行 | P1 |
| DOC-SDK-002 | SDK API 与 Server API 同步 | SDK 方法覆盖所有 Server API 端点 | P2 |
| DOC-SDK-003 | Protocol v2 文档与实际一致 | [sdks/PROTOCOL-v2.md](../sdks/PROTOCOL-v2.md) 与代码一致 | P2 |

### 14.4 版本兼容性声明

| 用例ID | 测试项 | 方法 | 优先级 |
|--------|-------|------|-------|
| DOC-VER-001 | 语义化版本合规 | 版本号遵循 SemVer | P2 |
| DOC-VER-002 | 升级迁移文档 | 从上一版本升级的迁移步骤 | P2 |
| DOC-VER-003 | 废弃 API 声明周期 | 废弃 API 有明确的替代方案和移除时间 | P2 |

---

## 15. 测试执行计划

### 15.1 执行阶段划分

| 阶段 | 内容 | 预计工时 | 负责人角色 |
|------|------|---------|-----------|
| **阶段一** | 测试环境准备 + 单元测试完善 + 变异测试框架引入 | 4 天 | 开发 + 测试 |
| **阶段二** | 集成测试（L1-L4）+ 功能测试 + 安全测试 | 6 天 | 测试 |
| **阶段三** | 企业级应用测试（第一批 + 第二批） | 8 天 | 测试 |
| **阶段四** | 兼容性测试 + 第三批企业应用 + API 契约测试 | 5 天 | 测试 |
| **阶段五** | 性能测试 + 性能基线建立 + 启动性能 | 5 天 | 测试 |
| **阶段六** | 稳定性测试 + 资源泄漏检查 + 前端全栈测试 | 6 天（含 72h 运行） | 测试 |
| **阶段七** | 第四批企业应用 + SDK 测试 + 回归 + 最终报告 | 6 天 | 开发 + 测试 |
| **合计** | - | **40 天（约 8 周）** | - |

### 15.2 详细时间线

#### 第 1 周：环境准备 + 单元测试 + 变异测试

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | 测试环境搭建、Docker 环境验证、PIT 插件集成 | 测试环境就绪 + PIT 配置 |
| 周二 | baafoo-core 单元测试补充 + 变异测试基线 | 核心模块覆盖率+变异覆盖率 |
| 周三 | baafoo-agent 单元测试补充 + 变异测试 | Agent 模块测试补充 |
| 周四 | baafoo-server 单元测试补充（Handler/Auth/Storage/MCP/API） | Server 模块测试补充 |
| 周五 | 单元测试覆盖率报告 + 变异测试报告 + 评审 | 覆盖率报告 + 变异测试报告 |

#### 第 2 周：集成测试 + 功能测试 + 安全测试

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | L1 Agent 模块集成 + L4 Testcontainers 环境搭建 | 集成测试报告 |
| 周二-周三 | L2 单协议集成测试（含 gRPC）+ L3 全链路 | 协议测试报告 |
| 周四 | 功能测试（环境/规则/录制/场景集/插件/认证/MCP） | 功能测试报告 |
| 周五 | 安全测试（认证/注入/XSS/依赖扫描） | 安全测试报告 |

#### 第 3 周：企业级应用测试（第一批 + 第二批）

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | 企业应用测试环境准备、Kafka 测试 | Kafka 企业级测试报告 |
| 周二 | Spring Boot PetClinic 测试 | PetClinic 测试报告 |
| 周三-周四 | Spring Cloud Gateway + Nacos 测试 | Gateway + Nacos 测试报告 |
| 周五 | ActiveMQ Artemis 测试 + 第一阶段总结 | 企业应用测试周报（一） |

#### 第 4 周：兼容性测试 + 第三批企业应用 + API 契约

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一-周二 | JDK 版本兼容性测试（8/8u345/11/17/21） | JDK 兼容性报告 |
| 周三 | 数据库兼容性（H2/PostgreSQL）+ 多 Agent 共存 + TLS 兼容 | 兼容性报告 |
| 周四 | RocketMQ + Spring Boot Admin + API 契约测试 | 企业应用测试周报（二） |
| 周五 | Keycloak + Apianno 测试 + 配置文档验证 | 第三批企业应用报告 |

#### 第 5 周：性能测试

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | 性能测试环境准备 + 基线测试 + 启动性能 | 性能基线数据 |
| 周二-周三 | HTTP/TCP/Kafka/gRPC 性能影响测试 | 性能测试报告 |
| 周四 | Server 端性能 + 大型规则集 + 企业应用性能 | Server 性能报告 |
| 周五 | 性能分析 + 火焰图 + 优化建议 | 性能分析报告 |

#### 第 6 周：稳定性测试 + 前端测试

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | 启动 72h 稳定性测试 + 资源泄漏监控 | - |
| 周一-周二 | 前端单元测试（Vitest）+ E2E 测试编写 | 前端单元测试 |
| 周三 | 前端响应式 + 视觉回归 + 兼容性测试 | 前端测试报告 |
| 周四 | 稳定性测试结果分析 + 内存/线程/FD/堆外内存 | 稳定性 + 资源泄漏报告 |
| 周五 | 全量回归测试（第一轮） | 回归测试报告（一） |

#### 第 7 周：收尾 + 第四批 + SDK + 最终报告

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一-周二 | 第四批企业应用测试（P2 级别）+ 客户端 SDK 测试 | 企业应用测试周报（三）+ SDK 报告 |
| 周三 | Go 代理 Sidecar 测试 | Sidecar 测试报告 |
| 周四 | 全量回归测试（第二轮）+ 缺陷修复验证 | 回归测试报告（二） |
| 周五 | 测试数据汇总 + 报告编写 + 评审 | 测试总结报告初稿 |

#### 第 8 周：最终交付

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | 变异测试最终轮 + 覆盖率门禁验证 | 变异测试最终报告 |
| 周二 | 安全测试补测 + 文档验证 | 安全测试最终报告 |
| 周三 | 最终报告修订 | 测试总结报告（终版） |
| 周四 | 交付评审 | - |
| 周五 | 项目总结 + 知识归档 | 测试经验总结 |

### 15.3 测试执行流程

```
1. 环境就绪检查
   ↓
2. 单元测试（全量）+ 变异测试
   ├── 所有模块单元测试通过
   ├── 覆盖率 ≥ 75%
   └── 变异覆盖率 ≥ 60%
   ↓
3. 集成测试（L1 → L2 → L3 → L4）
   ├── Agent 模块集成
   ├── 单协议联调（含 gRPC）
   ├── 全链路联调（Docker Staging）
   └── Testcontainers（PostgreSQL/Kafka）
   ↓
4. 功能测试
   ├── 环境管理
   ├── 规则管理
   ├── 录制回放
   ├── 场景集
   ├── 插件系统
   ├── 故障注入
   ├── 认证鉴权
   └── MCP Server
   ↓
5. 安全测试
   ├── 认证绕过
   ├── SQL注入/XSS
   ├── 路径遍历
   ├── 依赖漏洞扫描
   └── 敏感数据保护
   ↓
6. 企业级应用测试（分四批）
   ├── P0: Kafka + PetClinic
   ├── P1: Gateway + Nacos + Artemis + RocketMQ + Keycloak + ...
   └── P2: Eureka + Apollo + RabbitMQ + SonarQube + ...
   ↓
7. SDK + Sidecar + API 契约测试
   ├── Go/Node.js/Python SDK
   ├── Go Proxy Sidecar
   └── API 响应格式验证
   ↓
8. 兼容性测试
   ├── JDK 版本 + 厂商
   ├── OS + 架构（含 ARM64）
   ├── 数据库
   ├── HTTP/gRPC 客户端
   ├── TLS/SSL
   └── 多 Agent 共存
   ↓
9. 性能测试
   ├── 基线测试（无 Agent）
   ├── Agent 性能影响（stub/passthrough/record）
   ├── 启动性能
   ├── Server 性能
   ├── 大型规则集
   └── 企业应用性能
   ↓
10. 稳定性测试
    ├── 72h 长时间运行
    ├── 高并发稳定性
    ├── 资源泄漏（FD/线程/堆外内存/Metaspace）
    ├── 已知 P0 问题验证
    └── 异常恢复
    ↓
11. 前端全栈测试
    ├── 单元测试（Vitest）
    ├── E2E 测试（Playwright）
    ├── 响应式测试
    ├── 可视化回归
    └── 兼容性（Chrome/Firefox/Edge/Safari）
    ↓
12. 缺陷修复与回归（两轮）
    ↓
13. 输出最终测试报告
```

### 15.4 自动化测试执行

| 测试类型 | 执行频率 | 触发方式 |
|---------|---------|---------|
| 单元测试 | 每次提交 | CI/CD 自动执行 |
| 变异测试（核心模块） | 每日构建 | CI/CD 定时执行 |
| 集成测试（L1 + L4） | 每日构建 | CI/CD 定时执行 |
| 全链路集成测试（L3） | 每周 | 手动触发 / 每周定时 |
| 安全依赖扫描 | 每周 | CI/CD 定时执行 |
| 前端 E2E 测试 | 每周 | CI/CD 定时执行 |
| 前端单元测试 | 每次提交 | CI/CD 自动执行 |
| 企业级应用冒烟测试 | 里程碑 | 手动触发 |
| 性能测试 | 里程碑 | 手动触发 |
| 稳定性测试 | 版本发布前 | 手动触发 |
| 安全渗透测试 | 版本发布前 | 手动触发 |
| 全量变异测试 | 版本发布前 | 手动触发 |

---

## 16. 风险与应对

### 16.1 技术风险

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| 性能测试环境不稳定 | 性能数据不准确 | 中 | 使用 Docker 隔离，多次测试取平均值，预热后再统计 |
| JDK 版本兼容性问题 | Agent 在某些 JDK 下失效 | 中 | 优先保证 8/11/17/21，特殊厂商做冒烟；准备回退方案 |
| 多 Agent 共存冲突 | 与其他 Agent 同时挂载异常 | 低 | 明确加载顺序要求，测试前调研冲突点 |
| 内存泄漏定位困难 | 稳定性测试发现问题但难定位 | 中 | 使用 async-profiler/Arthas 辅助，提前准备 heap dump + thread dump 脚本 |
| 高并发下的竞态条件 | 偶发 Bug 难以复现 | 中 | 增加并发单元测试（JCStress），使用线程安全分析工具 |
| 企业应用启动失败 | 企业级测试阻塞 | 中 | 先做冒烟测试，快速验证；从简单应用开始，逐步深入 |
| 企业应用类加载冲突 | 功能异常 | 中 | 检查 shade 配置，必要时增加 relocation；记录冲突类型 |
| 变异测试运行时间过长 | CI 流水线阻塞 | 中 | 仅在每日构建运行全量变异，提交时只运行增量变异 |
| ARM64 架构兼容性 | Agent 在 ARM 下失败 | 低 | Docker 多架构构建，CI 增加 ARM64 runner |
| 已知 P0 问题未修复 | 影响测试结果 | 高 | 验证 BUG-4/THREAD-1/SEC-1 修复状态，加入 P0 门禁 |

### 16.2 资源风险

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| 性能测试机器不足 | 无法模拟真实高并发 | 低 | 降低并发规模，或使用云资源弹性扩容 |
| 72h 稳定性测试占用资源 | 影响其他测试 | 低 | 夜间/周末执行，或使用独立环境 |
| 企业应用测试环境复杂 | 部署耗时长 | 中 | 使用 Docker Compose 一键部署；准备预构建镜像 |
| 测试人员不足 | 测试周期延长 | 中 | 优先保证 P0/P1 用例，P2 可延后；企业应用分批测试 |
| ARM64 测试环境缺乏 | ARM 兼容性无法验证 | 中 | 使用 QEMU 模拟 + 云 CI（GitHub Actions ARM runner） |

### 16.3 管理风险

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| 需求变更导致测试范围扩大 | 测试延期 | 中 | 严格控制变更，新增需求放入下一轮 |
| 缺陷修复不及时 | 回归测试延期 | 中 | 建立缺陷分级响应机制，P0 缺陷 24h 内响应，P1 48h |
| 测试环境被占用 | 测试计划打乱 | 低 | 提前预约，使用 Docker 环境快速重建 |
| 企业应用许可证问题 | 无法使用某些应用 | 低 | 优先使用 Apache 2.0/MIT 等宽松许可证的开源应用 |
| 第三方 SDK 环境搭建复杂 | SDK 测试延迟 | 中 | 使用 Docker 容器化 SDK 测试环境 |

---

## 17. 测试交付物

### 17.1 文档交付物

| 交付物 | 说明 | 时间 |
|-------|------|------|
| 测试计划文档 | 本文档 | 测试开始前 |
| 测试用例文档 | 详细测试用例（含步骤、数据、预期） | 阶段一结束 |
| 单元测试报告 | 含覆盖率数据 + 变异覆盖率数据 | 阶段一结束 |
| 变异测试报告 | PIT 变异测试结果、幸存变异分析 | 阶段一/每周 |
| 集成测试报告 | L1/L2/L3/L4 测试结果 | 阶段二结束 |
| 功能测试报告 | 全功能测试结果（含认证/MCP） | 阶段二结束 |
| 安全测试报告 | 认证/注入/依赖扫描结果 | 阶段二结束 |
| 企业级应用测试报告 | 所有企业应用测试汇总 | 阶段三-四结束 |
| 客户端 SDK 测试报告 | Go/Node.js/Python SDK 验证结果 | 阶段七 |
| 兼容性测试报告 | JDK/OS/DB/TLS/客户端/Agent 兼容性 | 阶段四结束 |
| 性能测试报告 | 含基线对比、火焰图、启动性能、分析结论 | 阶段五结束 |
| 稳定性测试报告 | 72h 运行结果、资源泄漏（FD/线程/堆外内存）分析 | 阶段六结束 |
| 前端测试报告 | 单元测试 + E2E + 响应式 + 视觉回归结果 | 阶段六结束 |
| API 契约测试报告 | API 响应格式一致性验证 | 阶段四结束 |
| 缺陷报告 | 发现的 Bug 及修复状态（关联已知 P0 问题） | 持续更新 |
| 测试总结报告 | 整体结论、风险评估、上线建议 | 阶段七结束 |

### 17.2 代码交付物

| 交付物 | 位置 | 说明 |
|-------|------|------|
| 单元测试代码 | `*/src/test/java/` | 各模块单元测试 |
| 变异测试配置 | `pom.xml` (PIT plugin) | PIT Maven 插件配置 |
| 集成测试代码 | `baafoo-agent/src/test/java/.../integration/` | Agent 集成测试 |
| Testcontainers 测试 | `baafoo-testcontainers/` + server 模块 | L4 集成测试 |
| 全链路测试脚本 | [testing/3_SystemTest/test-fullchain.ps1](test-fullchain.ps1) | PowerShell 编排脚本 |
| 集成测试规则 | [testing/2_IntegrationTest/rules/](test-rules/) | 测试用规则 JSON |
| 企业应用测试配置 | [testing/4_E2ETest/enterprise/](enterprise/) | 企业应用 Docker Compose 配置 + 测试脚本 |
| 企业应用 - Kafka | [testing/4_E2ETest/enterprise/kafka/](enterprise/kafka/) | Kafka 企业级测试环境 |
| 企业应用 - PetClinic | [testing/4_E2ETest/enterprise/petclinic/](enterprise/petclinic/) | PetClinic 企业级测试环境 |
| 客户端 SDK 测试 | [sdks/*/tests/](../sdks/) | 各语言 SDK 测试 |
| Go Proxy 测试 | [proxy/](../proxy/) | Go Sidecar 测试 |
| 前端单元测试 | `web/src/**/*.spec.js` | Vitest 组件测试 |
| 前端 E2E 测试 | [web/tests/](../web/tests/) | Playwright 测试 |
| Docker 配置 | [docker-compose.staging.yml](../docker-compose.staging.yml) | Staging 环境 |
| 前端截图基线 | `web/tests/screenshots/baseline/` | 视觉回归基线 |

### 17.3 验收标准

| 类别 | 验收标准 |
|------|---------|
| P0 用例 | 100% 通过 |
| P1 用例 | ≥ 95% 通过 |
| P2 用例 | ≥ 80% 通过 |
| 单元测试行覆盖率 | ≥ 75% |
| 变异测试覆盖率 | ≥ 60%（核心模块 ≥ 70%） |
| P0 已知问题（BUG-4/THREAD-1/SEC-1） | 已修复且验证通过 |
| 性能影响 - HTTP QPS | 下降 ≤ 15% |
| 性能影响 - HTTP RT | 增加 ≤ 20% |
| 性能影响 - 启动时间 | 增加 ≤ 3s |
| 稳定性 - 运行 | 72 小时无 Crash、无 OOM |
| 稳定性 - 资源泄漏 | 无 FD/线程/堆外内存持续增长 |
| 企业级应用兼容 | 至少 80% 的 P1 级企业应用通过冒烟测试 |
| 安全漏洞 | 无 P0/P1 级别未修复安全漏洞 |
| 严重缺陷 | 无 P0/P1 级别未修复缺陷 |

---

## 附录

### A. 相关参考文档

- [TEST-MANUAL.md](TEST-MANUAL.md) - 全协议测试手册
- [TEST-REPORT.md](TEST-REPORT.md) - 全链路测试报告
- [README.md](../README.md) - 项目主文档
- [agents.md](../agents.md) - Agent 开发指南

### B. 常用命令速查

```bash
# 单元测试
mvnw clean test
mvnw test -pl baafoo-core -Dtest=MatchEngineTest

# 覆盖率报告
mvnw clean test jacoco:report

# 变异测试（PIT）
mvnw org.pitest:pitest-maven:mutationCoverage
mvnw -pl baafoo-core org.pitest:pitest-maven:mutationCoverage

# 全链路集成测试
.\testing\3_SystemTest\test-fullchain.ps1

# Testcontainers 集成测试
mvnw test -pl baafoo-testcontainers
mvnw test -pl baafoo-server -Dtest=*Containerized*

# 安全依赖扫描
mvnw org.owasp:dependency-check-maven:check

# 前端单元测试
cd web && npm run test:unit

# 前端 E2E 测试
cd web && npx playwright test

# Docker Staging 环境
docker compose -f docker-compose.yml -f docker-compose.staging.yml up --build -d
docker compose -f docker-compose.yml -f docker-compose.staging.yml down -v

# 企业级应用测试（统一管理脚本）
cd testing\4_E2ETest\enterprise && .\enterprise-env.ps1 -Action start -Apps kafka,petclinic
.\run-all-smoke-tests.ps1 -Apps kafka,petclinic

# 性能压测（wrk 示例）
wrk -t4 -c100 -d60s http://localhost:8084/__baafoo__/api/status

# 线程转储
jstack <pid> > threaddump.txt

# 堆转储
jmap -dump:live,format=b,file=heap.hprof <pid>

# CPU 火焰图（async-profiler）
profiler.sh -d 60 -f flamegraph.html <pid>
```
