# Baafoo 项目完整测试计划

**版本**: 1.2
**日期**: 2026-06-24
**项目**: Baafoo - JavaAgent-Based API Mock Platform

---

## 目录

1. [测试计划概述](#1-测试计划概述)
2. [仿真测试计划适配性评估](#2-仿真测试计划适配性评估)
3. [测试策略](#3-测试策略)
4. [测试环境](#4-测试环境)
5. [单元测试计划](#5-单元测试计划)
6. [集成测试计划](#6-集成测试计划)
7. [功能测试计划](#7-功能测试计划)
8. [企业级应用测试计划](#8-企业级应用测试计划)
9. [性能测试计划](#9-性能测试计划)
10. [兼容性测试计划](#10-兼容性测试计划)
11. [稳定性测试计划](#11-稳定性测试计划)
12. [前端测试计划](#12-前端测试计划)
13. [测试执行计划](#13-测试执行计划)
14. [风险与应对](#14-风险与应对)
15. [测试交付物](#15-测试交付物)

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
| TCP Socket | ✅ 完全支持 | BIO + NIO 双模式 | TcpStubHandler |
| Kafka | ✅ 支持 | Producer + Consumer | KafkaMockBroker |
| Pulsar/TDMQ | ✅ 支持 | PulsarClient | PulsarMockBroker |
| JMS | ✅ 支持 | ConnectionFactory | JmsMockBroker |
| Consul DNS | ✅ 支持 | InetAddress | ConsulDnsAdvice |
| Consul HTTP | ✅ 支持 | OkHttp | ConsulHttpAdvice |

### 1.3 测试目标

1. **功能正确性**：验证所有协议的拦截与 Mock 功能符合预期
2. **规则引擎正确性**：验证规则匹配、优先级、条件组合的正确性
3. **Agent 稳定性**：确保 Agent 在各种场景下不崩溃、不内存泄漏
4. **Server 可靠性**：确保多协议 Server 在高并发下稳定运行
5. **插件系统健壮性**：验证 Plugin SPI 的加载、隔离、健康监控机制
6. **企业级应用兼容性**：在真实企业级应用中验证 Agent 的稳定性和有效性
7. **前端功能完整**：Web 控制台所有页面功能正常

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
- gRPC 协议（项目暂不支持）
- Dubbo/Spring Cloud 框架级集成（项目是协议级拦截）

---

## 2. 仿真测试计划适配性评估

### 2.1 总体评估结论

**适配度：约 60%**。仿真测试计划的框架思路（对照测试法、分层测试、四大维度）具有参考价值，但针对 Baafoo 项目的实际情况需要较大调整。

### 2.2 可直接复用的部分

| 仿真计划内容 | 适配情况 | 说明 |
|-------------|---------|------|
| 对照测试法（有/无 Agent 对比） | ✅ 完全适配 | 核心测试方法论，适用于性能和功能验证 |
| 功能测试维度（协议拦截与 Mock） | ✅ 基本适配 | 需调整协议列表（增加 Consul，移除 gRPC） |
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

当前单元测试分布（约 50+ 个测试文件）：

| 模块 | 测试文件数 | 覆盖率（估算） |
|------|-----------|--------------|
| baafoo-core | 20+ | ~70% |
| baafoo-agent | 15+ | ~50% |
| baafoo-server | 8+ | ~40% |
| baafoo-plugin-api | 3 | ~80% |
| baafoo-cli | 1 | ~30% |

### 5.2 单元测试覆盖目标

| 模块 | 行覆盖率目标 | 重点覆盖内容 |
|------|------------|-------------|
| baafoo-core | ≥ 85% | MatchEngine, TemplateEngine, FaultInjector, ChaosManager |
| baafoo-agent | ≥ 70% | RouteTable, RouteManager, RoutingContext, PluginManager |
| baafoo-server | ≥ 65% | HttpStubHandler, TcpStubHandler, KafkaMockBroker, StorageService |
| 总体 | ≥ 75% | - |

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

### 5.4 单元测试执行

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

#### Consul 集成

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| IT-L2-CONSUL-001 | DNS 解析拦截 | P1 |
| IT-L2-CONSUL-002 | HTTP API 拦截 | P1 |

### 6.4 L3 - 全链路集成测试

使用 Docker Staging 环境执行完整的全链路测试，已由 [test-fullchain.ps1](test-fullchain.ps1) 脚本实现。

**当前覆盖**：33 个用例，91% 通过率（30 通过 / 3 跳过 / 0 失败）

**待补充用例**：

| 用例ID | 测试项 | 优先级 |
|--------|-------|-------|
| IT-L3-001 | 规则热更新（动态添加规则即时生效） | P0 |
| IT-L3-002 | 环境模式热切换（stub ↔ passthrough） | P0 |
| IT-L3-003 | 多环境规则隔离 | P0 |
| IT-L3-004 | 场景集启用/禁用 | P1 |
| IT-L3-005 | 规则版本撤销 | P1 |
| IT-L3-006 | 录制数据查询与删除 | P1 |
| IT-L3-007 | 插件热加载（需验证） | P2 |
| IT-L3-008 | MCP Server 工具调用 | P2 |
| IT-L3-009 | 故障注入功能验证 | P2 |
| IT-L3-010 | OpenAPI 导入功能 | P2 |

**执行方式**：

```powershell
# 完整构建 + 测试 + 清理
.\testing\test-fullchain.ps1

# 跳过构建（已有 JAR）
.\testing\test-fullchain.ps1 -SkipBuild

# 保留环境（调试用）
.\testing\test-fullchain.ps1 -NoCleanup
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
| FT-RULE-006 | 更新规则 | 规则存在 | 更新后立即生效 | P0 |
| FT-RULE-007 | 删除规则 | 规则存在 | 删除后不再匹配 | P0 |
| FT-RULE-008 | 规则优先级 | 多条规则可匹配同一请求 | 高优先级规则先匹配 | P0 |
| FT-RULE-009 | 禁用规则 | 规则存在 | 禁用后不再匹配 | P0 |
| FT-RULE-010 | 规则版本撤销 | 规则被修改过 | 撤销到上一版本 | P1 |
| FT-RULE-011 | 多响应条件 | 规则有多个 response | 满足不同条件返回不同响应 | P1 |
| FT-RULE-012 | 规则标签（tags） | 规则有标签 | 可按标签筛选 | P2 |

### 7.3 录制回放功能

| 用例ID | 测试项 | 前置条件 | 预期结果 | 优先级 |
|--------|-------|---------|---------|-------|
| FT-REC-001 | HTTP 请求录制 | record 模式 | 请求/响应被录制 | P0 |
| FT-REC-002 | TCP 报文录制 | record 模式 | 报文被录制 | P1 |
| FT-REC-003 | Kafka 消息录制 | record 模式 | produce/consume 被录制 | P1 |
| FT-REC-004 | Pulsar 消息录制 | record 模式 | 消息被录制 | P1 |
| FT-REC-005 | JMS 消息录制 | record 模式 | 消息被录制 | P1 |
| FT-REC-006 | 录制列表查询 | 有录制数据 | 分页查询正常 | P0 |
| FT-REC-007 | 录制详情查看 | 有录制数据 | 可查看完整请求/响应 | P1 |
| FT-REC-008 | 删除录制 | 有录制数据 | 删除成功 | P1 |
| FT-REC-009 | 录制保留策略 | 配置了保留天数 | 过期录制自动清理 | P2 |
| FT-REC-010 | 录制方向标注 | 有 MQ 录制 | 有 produce/consume 方向 | P1 |

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

### 8.3 企业级测试应用清单

#### 8.3.1 API 网关类

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Spring Cloud Gateway** | 3.1.x | HTTP | 复杂过滤器链、路由转发、高并发请求 | Docker / Jar | P1 |
| **Kong** | 2.8.x | HTTP | 网关层拦截验证、插件机制 | Docker | P2 |
| **Nginx (反向代理后端)** | 1.20+ | HTTP | 反向代理场景下的拦截验证 | Docker | P2 |

**Spring Cloud Gateway 测试场景**：
- EG-GW-001: Gateway 路由转发 HTTP 请求被 Agent 拦截
- EG-GW-002: Gateway 过滤器链中 Agent 正常工作（GlobalFilter / GatewayFilter）
- EG-GW-003: WebFlux 响应式环境下 Agent 兼容性
- EG-GW-004: 高并发下 Gateway + Agent 的稳定性
- EG-GW-005: 服务发现模式下（Consul/Eureka）的拦截验证

#### 8.3.2 服务注册与配置中心类

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Nacos** | 2.2.x | HTTP + TCP | 配置中心、服务发现、长连接 | Docker / Jar | P1 |
| **Eureka Server** | 2.x | HTTP | 服务注册与发现 | Docker / Jar | P2 |
| **Apollo Config** | 2.x | HTTP | 配置中心长轮询 | Docker | P2 |

**Nacos 测试场景**：
- EG-NACOS-001: Nacos Client 配置拉取被 Agent 拦截
- EG-NACOS-002: Nacos 服务注册请求被拦截
- EG-NACOS-003: Nacos gRPC 长连接（确认是否支持，不支持则记录）
- EG-NACOS-004: Nacos 集群模式下的 Agent 表现
- EG-NACOS-005: 配置热更新时的 Agent 稳定性

#### 8.3.3 消息中间件类

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Apache Kafka** | 2.8.x / 3.x | Kafka | 高吞吐生产消费、客户端版本兼容性 | Docker | P0 |
| **Apache RocketMQ** | 4.9.x / 5.x | TCP / Kafka 兼容 | 国产消息队列验证 | Docker | P1 |
| **ActiveMQ Artemis** | 2.28.x | JMS / AMQP | JMS 协议企业级验证 | Docker | P1 |
| **RabbitMQ** | 3.12.x | AMQP | 确认是否可通过 TCP 拦截 | Docker | P2 |

**Kafka 企业级测试场景**：
- EG-KAFKA-001: Kafka Producer 高吞吐下 Agent 拦截正确性
- EG-KAFKA-002: Kafka Consumer 消费组模式下的拦截
- EG-KAFKA-003: Kafka 多版本客户端兼容性（2.8 / 3.0 / 3.5）
- EG-KAFKA-004: Kafka 事务消息（确认 Mock 支持度）
- EG-KAFKA-005: Kafka SSL 连接（确认是否支持）
- EG-KAFKA-006: Kafka 批量发送/批量消费

#### 8.3.4 企业级单体/微服务应用

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Spring Boot PetClinic** | 2.7.x / 3.x | HTTP | 经典 Spring Boot 应用验证 | Docker / Jar | P0 |
| **Spring Boot Admin** | 3.x | HTTP | 应用监控、Actuator 端点 | Docker / Jar | P1 |
| **Flowable / Activiti** | 7.x / 7.x | HTTP + JDBC | 工作流引擎、复杂业务逻辑 | Docker | P2 |
| **Apianno**（同项目组） | 1.0 | HTTP | 真实业务系统端到端验证 | Docker / Jar | P1 |

**Spring Boot PetClinic 测试场景**：
- EG-PET-001: PetClinic 正常启动 + Agent 挂载无异常
- EG-PET-002: Owner API HTTP 调用被 Mock
- EG-PET-003: Vet API HTTP 调用被 Mock
- EG-PET-004: 多数据源 / JPA 场景下 Agent 兼容性
- EG-PET-005: 缓存（Spring Cache）场景下的拦截
- EG-PET-006: 定时任务（@Scheduled）中调用的拦截
- EG-PET-007: 异步调用（@Async）中的拦截

#### 8.3.5 微服务架构应用

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Spring PetClinic Microservices** | 3.x | HTTP + 消息 | 微服务调用链、服务间通信 | Docker Compose | P1 |
| **PiggyMetrics**（微服务示例） | latest | HTTP + Eureka | 微服务全链路验证 | Docker Compose | P2 |
| **Mall**（电商微服务） | latest | HTTP + Redis + MQ | 复杂电商业务场景 | Docker Compose | P2 |

**Spring PetClinic Microservices 测试场景**：
- EG-MS-001: 多微服务间 HTTP 调用全链路拦截
- EG-MS-002: 服务发现（Eureka/Consul）场景下的拦截
- EG-MS-003: API Gateway 入口 + 服务间调用双层拦截
- EG-MS-004: 消息驱动微服务（Kafka/RabbitMQ）拦截
- EG-MS-005: 分布式追踪（Sleuth/Zipkin）与 Agent 共存

#### 8.3.6 企业管理/安全类

| 应用 | 版本 | 协议覆盖 | 测试重点 | 部署方式 | 优先级 |
|------|------|---------|---------|---------|-------|
| **Keycloak** | 21.x / 22.x | HTTP | 安全框架、复杂过滤器链、Quarkus 运行时 | Docker | P1 |
| **SonarQube** | 9.x / 10.x | HTTP | 复杂 Java Web 应用、ElasticSearch 集成 | Docker | P2 |
| **Jenkins** | 2.3xx | HTTP | 插件机制、长连接、定时任务 | Docker | P2 |

**Keycloak 测试场景**：
- EG-KEY-001: Keycloak 正常启动 + Agent 挂载
- EG-KEY-002: Keycloak Admin REST API Mock
- EG-KEY-003: OAuth2 / OIDC 协议流程中的拦截
- EG-KEY-004: Quarkus / Undertow 容器兼容性
- EG-KEY-005: 多租户（Realm）场景下的拦截

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

| 阶段 | 应用 | 预计工时 | 优先级 |
|------|------|---------|-------|
| 第一批（P0） | Kafka + Spring Boot PetClinic | 3 天 | P0 |
| 第二批（P1） | Spring Cloud Gateway + Nacos + ActiveMQ Artemis | 5 天 | P1 |
| 第三批（P1） | RocketMQ + Spring Boot Admin + Keycloak + Apianno | 5 天 | P1 |
| 第四批（P2） | Eureka + Apollo + RabbitMQ + SonarQube + Jenkins | 5 天 | P2 |

**合计**：约 18 个工作日，可与其他测试并行进行。

### 8.7 企业级应用测试的特殊风险

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| 应用启动失败 | 测试阻塞 | 先做冒烟测试，快速验证启动兼容性；准备回退方案 |
| 类加载冲突 | 功能异常 | 检查 Agent 的 shade 配置，必要时增加更多 relocation |
| 与应用的 Agent 冲突 | 功能异常 | 调整 Agent 加载顺序，记录冲突的 Agent 类型 |
| 应用复杂度高，难以定位问题 | 排期延长 | 从简单应用开始，逐步增加复杂度；保留无 Agent 对照组 |
| 第三方依赖版本不兼容 | 功能异常 | 测试多种版本，记录兼容版本范围 |
| 许可证问题 | 无法使用 | 优先使用 Apache 2.0 / MIT 等宽松许可证的开源应用 |

### 8.8 企业级应用测试环境搭建与执行指南

#### 8.8.1 目录结构

企业级应用测试配置位于 `testing/enterprise/` 目录：

```
testing/enterprise/
├── README.md                          # 企业级测试总览
├── enterprise-env.ps1                 # 统一环境管理脚本
├── run-all-smoke-tests.ps1            # 统一冒烟测试脚本
├── common/
│   ├── baafoo-agent-enterprise.yml    # 通用 Agent 配置模板
│   ├── baafoo-server-enterprise.yml   # 通用 Server 配置模板
│   └── docker-compose.base.yml        # 基础服务（Baafoo Server）
├── kafka/                             # Apache Kafka 企业级测试
│   ├── README.md
│   ├── docker-compose.yml
│   ├── baafoo-agent-kafka.yml
│   ├── TEST-CASES.md                  # 详细测试用例
│   └── smoke-test.ps1                 # 冒烟测试脚本
├── petclinic/                         # Spring Boot PetClinic 企业级测试
│   ├── README.md
│   ├── docker-compose.yml
│   ├── Dockerfile.petclinic           # PetClinic + Agent 镜像
│   ├── baafoo-agent-petclinic.yml
│   ├── TEST-CASES.md
│   └── smoke-test.ps1
├── spring-cloud-gateway/              # Spring Cloud Gateway（计划中）
├── nacos/                             # Nacos（计划中）
├── artemis/                           # ActiveMQ Artemis（计划中）
└── keycloak/                          # Keycloak（计划中）
```

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
   - 19000-19004: Baafoo 各协议 Mock 端口
   - 各应用的独立端口（见各应用 README）

#### 8.8.3 快速开始

**方式一：使用统一环境管理脚本（推荐）**

```powershell
cd testing\enterprise

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
cd testing\enterprise\kafka

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

---

## 9. 性能测试计划

### 9.1 测试方法论

采用**对照测试法**，对比"无 Agent"和"有 Agent（不同模式）"的性能差异，量化 Agent 带来的性能开销。

### 9.2 性能指标定义

| 指标 | 说明 | 采集方式 |
|------|------|---------|
| QPS | 每秒查询数 | JMeter / wrk 统计 |
| Avg RT | 平均响应时间 | JMeter / wrk 统计 |
| TP90 | 90 百分位响应时间 | JMeter 统计 |
| TP99 | 99 百分位响应时间 | JMeter 统计 |
| CPU % | CPU 使用率 | JVisualVM / top / Docker stats |
| Heap Memory | 堆内存使用量 | JVisualVM / jmap |
| GC 次数 | Young GC / Full GC 次数 | GC 日志 / JVisualVM |

### 9.3 性能基线（无 Agent）

| 用例ID | 测试场景 | 并发数 | 持续时间 | 指标 |
|--------|---------|-------|---------|------|
| PT-BASE-001 | HTTP GET 空响应 | 10 / 50 / 100 / 500 | 60s | QPS, Avg RT, TP99 |
| PT-BASE-002 | HTTP POST 1KB Body | 10 / 50 / 100 / 500 | 60s | QPS, Avg RT, TP99 |
| PT-BASE-003 | TCP 短连接 | 10 / 50 / 100 | 60s | QPS, Avg RT |
| PT-BASE-004 | TCP 长连接 | 10 / 50 / 100 | 60s | QPS, Avg RT |
| PT-BASE-005 | Kafka Produce 1KB | 10 / 50 / 100 | 60s | TPS, 延迟 |
| PT-BASE-006 | Kafka Consume 1KB | 10 / 50 / 100 | 60s | TPS, 延迟 |

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

### 9.6 企业级应用性能测试

| 用例ID | 测试场景 | 应用 | 指标 | 优先级 |
|--------|---------|------|------|-------|
| PT-ENT-001 | PetClinic 首页 QPS 影响 | Spring Boot PetClinic | QPS 下降 ≤ 20% | P1 |
| PT-ENT-002 | PetClinic 数据库查询性能 | Spring Boot PetClinic | RT 增加 ≤ 15% | P1 |
| PT-ENT-003 | Spring Cloud Gateway 转发性能 | Spring Cloud Gateway | QPS 下降 ≤ 20% | P1 |
| PT-ENT-004 | Nacos 配置拉取性能 | Nacos | RT 增加 ≤ 15% | P2 |
| PT-ENT-005 | Keycloak 登录接口性能 | Keycloak | RT 增加 ≤ 20% | P2 |

### 9.7 性能测试工具选择

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
| COMP-JDK-001 | JDK 8 | OpenJDK / Oracle | 全功能测试（HTTP + TCP + Kafka） | P0 |
| COMP-JDK-002 | JDK 11 | OpenJDK | 全功能测试 | P0 |
| COMP-JDK-003 | JDK 17 | OpenJDK / Eclipse Temurin | 全功能测试 + `--add-opens` 验证 | P0 |
| COMP-JDK-004 | JDK 21 | OpenJDK | 冒烟测试（启动 + 基本功能） | P2 |

**注意事项**：
- JDK 9+ 需要 `--add-opens java.base/java.net=ALL-UNNAMED`
- Byte Buddy 版本与 JDK 版本兼容性验证
- JDK 21 虚拟线程（Project Loom）影响评估

### 10.2 操作系统兼容性

| 用例ID | 操作系统 | 版本 | 测试内容 | 优先级 |
|--------|---------|------|---------|-------|
| COMP-OS-001 | Linux | Ubuntu 20.04 / 22.04 | 全功能测试 | P0 |
| COMP-OS-002 | Windows | Windows 10 / 11 | 冒烟测试（开发环境） | P1 |
| COMP-OS-003 | macOS | macOS 12+ | 冒烟测试（开发环境） | P1 |

### 10.3 HTTP 客户端兼容性

| 用例ID | HTTP 客户端 | 版本 | 测试内容 | 优先级 |
|--------|------------|------|---------|-------|
| COMP-HTTP-001 | OkHttp | 3.x / 4.x | GET/POST 拦截 | P0 |
| COMP-HTTP-002 | Feign (OkHttp 底层) | 10.x / 11.x | Feign 调用拦截 | P0 |
| COMP-HTTP-003 | RestTemplate | Spring 5.x | RestTemplate 拦截 | P1 |
| COMP-HTTP-004 | URLConnection | JDK 内置 | 基础拦截 | P1 |
| COMP-HTTP-005 | WebClient (Spring 5) | - | 响应式客户端（需确认是否支持） | P2 |
| COMP-HTTP-006 | Apache HttpClient | 4.x / 5.x | HttpClient 拦截 | P1 |

### 10.4 数据库兼容性

| 用例ID | 数据库 | 版本 | 测试内容 | 优先级 |
|--------|-------|------|---------|-------|
| COMP-DB-001 | H2 (内嵌) | 2.2.224 | 所有存储操作 | P0 |
| COMP-DB-002 | PostgreSQL | 14 / 15 | 所有存储操作 | P0 |
| COMP-DB-003 | MySQL | 8.0 | 兼容性验证（可选） | P2 |

### 10.5 多 Agent 共存兼容性

| 用例ID | 共存 Agent | 测试内容 | 优先级 |
|--------|-----------|---------|-------|
| COMP-AGENT-001 | JaCoCo (本项目使用) | 同时挂载，两者功能均正常 | P0 |
| COMP-AGENT-002 | SkyWalking | 同时挂载，功能不冲突 | P1 |
| COMP-AGENT-003 | Pinpoint | 同时挂载，功能不冲突 | P2 |
| COMP-AGENT-004 | Arthas | 动态 Attach 共存 | P2 |
| COMP-AGENT-005 | Prometheus JMX Exporter | JMX 采集与 Agent 共存 | P2 |

**Agent 加载顺序注意事项**：
- JaCoCo 必须放在第一位（`-javaagent:jacoco.jar -javaagent:baafoo-agent.jar`）
- 多个字节码增强 Agent 可能有类转换冲突
- Bootstrap ClassLoader 注入的类可能冲突

### 10.6 容器化兼容性

| 用例ID | 容器运行时 | 测试内容 | 优先级 |
|--------|-----------|---------|-------|
| COMP-DOCKER-001 | Docker Engine | Docker 镜像正常运行 | P0 |
| COMP-DOCKER-002 | Kubernetes | K8s 中部署 Agent | P2 |
| COMP-DOCKER-003 | containerd | containerd 运行时兼容 | P2 |

### 10.7 企业级应用框架兼容性

| 用例ID | 应用框架 | 版本 | 测试内容 | 优先级 |
|--------|---------|------|---------|-------|
| COMP-FRAME-001 | Spring Boot | 2.7.x | 核心功能验证 | P0 |
| COMP-FRAME-002 | Spring Boot | 3.x | 验证支持度 | P1 |
| COMP-FRAME-003 | Spring Cloud | 2021.x | 微服务环境验证 | P1 |
| COMP-FRAME-004 | Quarkus | 2.x / 3.x | （Keycloak 等应用使用） | P2 |
| COMP-FRAME-005 | Solon / JFinal | - | 国产框架验证（可选） | P2 |

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

---

## 12. 前端测试计划

### 12.1 前端单元测试

**当前状态**：暂无单元测试，使用 Playwright 做 E2E 测试。

**建议补充**（优先级 P2）：
- Vue 组件单元测试（Vitest + Vue Test Utils）
- Pinia store 测试
- API 层 mock 测试

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

### 12.3 前端兼容性

| 用例ID | 浏览器 | 版本 | 测试内容 | 优先级 |
|--------|-------|------|---------|-------|
| FE-COMP-001 | Chrome | 最新版 | 全功能验证 | P0 |
| FE-COMP-002 | Firefox | 最新版 | 主要功能验证 | P1 |
| FE-COMP-003 | Edge | 最新版 | 主要功能验证 | P1 |
| FE-COMP-004 | Safari | 最新版 | 冒烟测试 | P2 |

### 12.4 前端执行方式

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

## 13. 测试执行计划

### 13.1 执行阶段划分

| 阶段 | 内容 | 预计工时 | 负责人角色 |
|------|------|---------|-----------|
| **阶段一** | 测试环境准备 + 单元测试完善 | 3 天 | 开发 + 测试 |
| **阶段二** | 集成测试 + 功能测试 | 5 天 | 测试 |
| **阶段三** | 企业级应用测试（第一批 + 第二批） | 8 天 | 测试 |
| **阶段四** | 兼容性测试 + 第三批企业应用 | 5 天 | 测试 |
| **阶段五** | 性能测试 + 性能基线建立 | 4 天 | 测试 |
| **阶段六** | 稳定性测试 + 前端 E2E | 5 天（含 72h 运行） | 测试 |
| **阶段七** | 第四批企业应用 + 缺陷修复 + 回归 | 5 天 | 开发 + 测试 |
| **合计** | - | **35 天（约 7 周）** | - |

### 13.2 详细时间线

#### 第 1 周：环境准备 + 单元测试

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | 测试环境搭建、Docker 环境验证 | 测试环境就绪 |
| 周二 | baafoo-core 单元测试补充 | 核心模块覆盖率提升 |
| 周三 | baafoo-agent 单元测试补充 | Agent 模块覆盖率提升 |
| 周四 | baafoo-server 单元测试补充 | Server 模块覆盖率提升 |
| 周五 | 单元测试覆盖率报告 + 评审 | 覆盖率报告 |

#### 第 2 周：集成测试 + 功能测试

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | L1 Agent 模块集成测试 | 集成测试报告 |
| 周二-周三 | L2 单协议集成测试（HTTP/TCP/Kafka/Pulsar/JMS） | 协议测试报告 |
| 周四 | L3 全链路集成测试（运行 test-fullchain.ps1） | 全链路测试报告 |
| 周五 | 功能测试（环境/规则/录制/场景集/插件） | 功能测试报告 |

#### 第 3 周：企业级应用测试（第一批 + 第二批）

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | 企业应用测试环境准备、Kafka 测试 | Kafka 企业级测试报告 |
| 周二 | Spring Boot PetClinic 测试 | PetClinic 测试报告 |
| 周三-周四 | Spring Cloud Gateway + Nacos 测试 | Gateway + Nacos 测试报告 |
| 周五 | ActiveMQ Artemis 测试 + 第一阶段总结 | 企业应用测试周报（一） |

#### 第 4 周：兼容性测试 + 第三批企业应用

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一-周二 | JDK 版本兼容性测试（8/11/17） | JDK 兼容性报告 |
| 周三 | 数据库兼容性（H2/PostgreSQL）+ 多 Agent 共存 | 兼容性报告 |
| 周四 | RocketMQ + Spring Boot Admin 测试 | 企业应用测试周报（二） |
| 周五 | Keycloak + Apianno 测试 + 问题修复 | 第三批企业应用报告 |

#### 第 5 周：性能测试

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | 性能测试环境准备 + 基线测试 | 性能基线数据 |
| 周二-周三 | HTTP/TCP/Kafka 性能影响测试 | 性能测试报告 |
| 周四 | Server 端性能 + 企业应用性能 | Server + 企业应用性能报告 |
| 周五 | 性能分析 + 优化建议 | 性能分析报告 |

#### 第 6 周：稳定性测试 + 前端测试

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一 | 启动 72h 稳定性测试 | - |
| 周一-周二 | 前端 E2E 测试用例编写 + 执行 | 前端测试报告 |
| 周三 | 稳定性测试结果分析 + 企业应用长稳 | 稳定性测试报告 |
| 周四 | 内存泄漏专项检查 + 线程安全验证 | 内存/线程安全报告 |
| 周五 | 全量回归测试（第一轮） | 回归测试报告（一） |

#### 第 7 周：收尾 + 第四批 + 最终报告

| 时间 | 任务 | 交付物 |
|------|------|--------|
| 周一-周二 | 第四批企业应用测试（P2 级别） | 企业应用测试周报（三） |
| 周三 | 全量回归测试（第二轮）+ 缺陷修复验证 | 回归测试报告（二） |
| 周四 | 测试数据汇总 + 报告编写 | 测试总结报告初稿 |
| 周五 | 最终报告评审 + 交付 | 测试总结报告（终版） |

### 13.3 测试执行流程

```
1. 环境就绪检查
   ↓
2. 单元测试（全量）
   ├── 所有模块单元测试通过
   └── 覆盖率达标
   ↓
3. 集成测试（L1 → L2 → L3）
   ├── Agent 模块集成
   ├── 单协议联调
   └── 全链路联调
   ↓
4. 功能测试
   ├── 环境管理
   ├── 规则管理
   ├── 录制回放
   ├── 场景集
   └── 插件系统
   ↓
5. 企业级应用测试（分四批）
   ├── P0: Kafka + PetClinic
   ├── P1: Gateway + Nacos + Artemis + RocketMQ + Keycloak + ...
   └── P2: Eureka + Apollo + RabbitMQ + SonarQube + ...
   ↓
6. 兼容性测试
   ├── JDK 版本
   ├── 数据库
   ├── 客户端
   └── 多 Agent 共存
   ↓
7. 性能测试
   ├── 基线测试（无 Agent）
   ├── Agent 性能影响
   ├── Server 性能
   └── 企业应用性能
   ↓
8. 稳定性测试
   ├── 72h 长时间运行
   ├── 高并发稳定性
   └── 内存泄漏检查
   ↓
9. 前端 E2E 测试
   ↓
10. 缺陷修复与回归（两轮）
   ↓
11. 输出最终测试报告
```

### 13.4 自动化测试执行

| 测试类型 | 执行频率 | 触发方式 |
|---------|---------|---------|
| 单元测试 | 每次提交 | CI/CD 自动执行 |
| 集成测试（L1） | 每日构建 | CI/CD 定时执行 |
| 全链路集成测试 | 每周 | 手动触发 / 每周定时 |
| 前端 E2E 测试 | 每周 | 手动触发 |
| 企业级应用冒烟测试 | 里程碑 | 手动触发 |
| 性能测试 | 里程碑 | 手动触发 |
| 稳定性测试 | 版本发布前 | 手动触发 |

---

## 14. 风险与应对

### 14.1 技术风险

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| 性能测试环境不稳定 | 性能数据不准确 | 中 | 使用 Docker 隔离，多次测试取平均值，预热后再统计 |
| JDK 版本兼容性问题 | Agent 在某些 JDK 下失效 | 中 | 优先保证 8/11/17，21 只做冒烟；准备回退方案 |
| 多 Agent 共存冲突 | 与其他 Agent 同时挂载异常 | 低 | 明确加载顺序要求，测试前调研冲突点 |
| 内存泄漏定位困难 | 稳定性测试发现问题但难定位 | 中 | 使用 JProfiler/Arthas 辅助，提前准备 heap dump 脚本 |
| 高并发下的竞态条件 | 偶发 Bug 难以复现 | 中 | 增加并发单元测试，使用线程安全分析工具 |
| 企业应用启动失败 | 企业级测试阻塞 | 中 | 先做冒烟测试，快速验证；从简单应用开始，逐步深入 |
| 企业应用类加载冲突 | 功能异常 | 中 | 检查 shade 配置，必要时增加 relocation；记录冲突类型 |

### 14.2 资源风险

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| 性能测试机器不足 | 无法模拟真实高并发 | 低 | 降低并发规模，或使用云资源弹性扩容 |
| 72h 稳定性测试占用资源 | 影响其他测试 | 低 | 夜间/周末执行，或使用独立环境 |
| 企业应用测试环境复杂 | 部署耗时长 | 中 | 使用 Docker Compose 一键部署；准备预构建镜像 |
| 测试人员不足 | 测试周期延长 | 中 | 优先保证 P0/P1 用例，P2 可延后；企业应用分批测试 |

### 14.3 管理风险

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| 需求变更导致测试范围扩大 | 测试延期 | 中 | 严格控制变更，新增需求放入下一轮 |
| 缺陷修复不及时 | 回归测试延期 | 中 | 建立缺陷分级响应机制，P0 缺陷 24h 内响应 |
| 测试环境被占用 | 测试计划打乱 | 低 | 提前预约，使用 Docker 环境快速重建 |
| 企业应用许可证问题 | 无法使用某些应用 | 低 | 优先使用 Apache 2.0/MIT 等宽松许可证的开源应用 |

---

## 15. 测试交付物

### 15.1 文档交付物

| 交付物 | 说明 | 时间 |
|-------|------|------|
| 测试计划文档 | 本文档 | 测试开始前 |
| 测试用例文档 | 详细测试用例（含步骤、数据、预期） | 阶段一结束 |
| 单元测试报告 | 含覆盖率数据 | 阶段一结束 |
| 集成测试报告 | L1/L2/L3 测试结果 | 阶段二结束 |
| 功能测试报告 | 全功能测试结果 | 阶段二结束 |
| 企业级应用测试报告 | 所有企业应用测试汇总 | 阶段三-四结束 |
| 兼容性测试报告 | JDK/OS/DB/Agent 兼容性 | 阶段四结束 |
| 性能测试报告 | 含基线对比、性能图表、分析结论 | 阶段五结束 |
| 稳定性测试报告 | 72h 运行结果、内存分析 | 阶段六结束 |
| 前端测试报告 | E2E 测试结果 | 阶段六结束 |
| 缺陷报告 | 发现的 Bug 及修复状态 | 持续更新 |
| 测试总结报告 | 整体结论、风险评估、上线建议 | 阶段七结束 |

### 15.2 代码交付物

| 交付物 | 位置 | 说明 |
|-------|------|------|
| 单元测试代码 | `*/src/test/java/` | 各模块单元测试 |
| 集成测试代码 | `baafoo-agent/src/test/java/.../integration/` | Agent 集成测试 |
| 全链路测试脚本 | [testing/test-fullchain.ps1](test-fullchain.ps1) | PowerShell 编排脚本 |
| 集成测试规则 | [testing/test-rules/](test-rules/) | 测试用规则 JSON |
| 企业应用测试配置 | [testing/enterprise/](enterprise/) | 企业应用 Docker Compose 配置 + 测试脚本 |
| 企业应用 - Kafka | [testing/enterprise/kafka/](enterprise/kafka/) | Kafka 企业级测试环境 |
| 企业应用 - PetClinic | [testing/enterprise/petclinic/](enterprise/petclinic/) | PetClinic 企业级测试环境 |
| 前端 E2E 测试 | [web/tests/](../web/tests/) | Playwright 测试 |
| Docker 配置 | [docker-compose.staging.yml](../docker-compose.staging.yml) | Staging 环境 |

### 15.3 验收标准

| 类别 | 验收标准 |
|------|---------|
| P0 用例 | 100% 通过 |
| P1 用例 | ≥ 95% 通过 |
| P2 用例 | ≥ 80% 通过 |
| 单元测试覆盖率 | ≥ 75% 行覆盖率 |
| 性能影响 | HTTP QPS 下降 ≤ 15%，响应时间增加 ≤ 20% |
| 稳定性 | 72 小时无 Crash、无 OOM、无内存泄漏 |
| 企业级应用兼容 | 至少 80% 的 P1 级企业应用通过冒烟测试 |
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

# 全链路集成测试
.\testing\test-fullchain.ps1

# 前端 E2E 测试
cd web && npx playwright test

# Docker Staging 环境
docker compose -f docker-compose.yml -f docker-compose.staging.yml up --build -d
docker compose -f docker-compose.yml -f docker-compose.staging.yml down -v
```
