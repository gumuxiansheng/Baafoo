# Spring Cloud Gateway 企业级测试

## 概述

验证 Baafoo Agent 在 Spring Cloud Gateway 网关层拦截、过滤器链兼容、路由转发场景下的能力。

Spring Cloud Gateway 是 Spring 官方的 API 网关，基于 WebFlux + Netty，支持路由、过滤器、断言等丰富功能。测试重点验证 Agent 与 Gateway 的兼容性及多层 Agent 环境隔离能力。

## 测试环境

| 组件 | 地址 | 说明 |
|------|------|------|
| Baafoo Server | http://localhost:18084 | 控制台 / API |
| Gateway | http://localhost:18080 | Spring Cloud Gateway (挂载 Agent) |
| 后端服务 | http://localhost:18092 | baafoo-test-spring (挂载 Agent) |
| API Key | enterprise-admin-key | 认证密钥 |

## 架构

```
Client → Gateway (:8080, Agent, env=enterprise-gateway)
             │ 路由 /api/** → backend:9090
             ▼
         backend-service (:9090, Agent, env=enterprise-gateway-backend)
```

## 快速开始

```powershell
cd testing\4_E2ETest\enterprise\spring-cloud-gateway

# 启动环境
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml up --build -d

# 运行冒烟测试
.\smoke-test.ps1

# 停止环境
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml down -v
```

## 测试用例

共 12 个用例，详见 [TEST-CASES.md](TEST-CASES.md)。

| ID | 场景 | 优先级 |
|------|------|------|
| EG-GW-001 | Gateway 启动 + Agent 挂载 | P0 |
| EG-GW-002 | Agent 注册到 Server | P0 |
| EG-GW-003 | 网关路由转发透传 | P0 |
| EG-GW-004 | 网关层 Mock 拦截 | P0 |
| EG-GW-005 | 后端 Mock (网关透传) | P0 |
| EG-GW-006 | 全链路 Passthrough | P0 |
| EG-GW-007 | Record 录制 | P1 |
| EG-GW-008 | 模式热切换 | P1 |
| EG-GW-009 | 无类加载冲突 | P0 |
| EG-GW-010 | 应用功能完整性 | P0 |
| EG-GW-011 | 过滤器链兼容 | P1 |
| EG-GW-012 | 多 Agent 环境隔离 | P1 |
