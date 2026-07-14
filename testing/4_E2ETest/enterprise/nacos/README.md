# Nacos 企业级测试

## 概述

验证 Baafoo Agent 在 Nacos 注册配置中心场景下的拦截能力和兼容性。

Nacos 是 Alibaba 开源的服务注册与发现 + 配置中心，广泛用于 Spring Cloud Alibaba 生态。Nacos 2.x 同时提供 HTTP API (8848) 和 gRPC 长连接 (9848) 两种通信方式。

## 测试环境

| 组件 | 地址 | 说明 |
|------|------|------|
| Baafoo Server | http://localhost:18084 | 控制台 / API |
| Nacos Server | http://localhost:18848/nacos | Nacos 控制台 |
| 测试应用 | http://localhost:18091 | baafoo-test-spring (挂载 Agent) |
| API Key | enterprise-admin-key | 认证密钥 |

## 快速开始

```powershell
cd testing\4_E2ETest\enterprise\nacos

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
| EG-NACOS-001 | 应用启动 + Agent 挂载 | P0 |
| EG-NACOS-002 | Agent 注册到 Server | P0 |
| EG-NACOS-003 | 服务注册 API Mock | P0 |
| EG-NACOS-004 | 服务发现 API Mock | P0 |
| EG-NACOS-005 | 配置拉取 API Mock | P0 |
| EG-NACOS-006 | Passthrough 透传 | P0 |
| EG-NACOS-007 | Record 录制 | P1 |
| EG-NACOS-008 | 模式热切换 | P1 |
| EG-NACOS-009 | 无类加载冲突 | P0 |
| EG-NACOS-010 | 应用功能完整性 | P0 |
| EG-NACOS-011 | Nacos 控制台可达性 | P1 |
| EG-NACOS-012 | gRPC 长连接拦截 | P1 (SKIP) |
