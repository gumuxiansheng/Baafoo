# Spring Cloud Gateway 企业级测试用例

## 测试环境信息

| 项目 | 值 |
|------|-----|
| 应用 | Spring Cloud Gateway 3.1.4 |
| 后端服务 | baafoo-test-spring (HTTP 测试应用) |
| 协议覆盖 | HTTP |
| Baafoo Server | baafoo-server:8084 |
| API Key | enterprise-admin-key |
| 测试环境 | enterprise-gateway + enterprise-gateway-backend |

## 架构

```
  Client → Gateway (:8080, 挂载 Agent, env=enterprise-gateway)
               │
               │ 路由: /api/** → backend-service:9090
               │
               ▼
  backend-service (:9090, 挂载 Agent, env=enterprise-gateway-backend)
```

## 测试用例矩阵

### EG-GW-001: Gateway 启动 + Agent 挂载无异常 (P0, EG-COMMON-001)
**前置条件**: 无
**步骤**:
1. 启动 Baafoo Server
2. 启动后端服务（挂载 Agent）
3. 启动 Gateway（挂载 Agent）
**预期结果**:
- Gateway 正常启动，健康检查 UP
- Agent 日志无 ERROR 级别错误

### EG-GW-002: Agent 成功注册到 Server (P0, EG-COMMON-002)
**前置条件**: EG-GW-001 通过
**步骤**:
1. 调用 Server API 查询 Agent 列表
**预期结果**:
- 包含 environment=enterprise-gateway 的 Agent
- 包含 environment=enterprise-gateway-backend 的 Agent

### EG-GW-003: 网关路由转发透传 (P0, EG-COMMON-003)
**前置条件**: EG-GW-002 通过
**步骤**:
1. 两个环境均切换为 passthrough
2. 通过 Gateway 访问 /api/stub-demo/health
**预期结果**:
- 请求透传到后端，返回 OK

### EG-GW-004: 网关层 Mock 拦截 (P0)
**前置条件**: EG-GW-003 通过
**步骤**:
1. enterprise-gateway 切换为 stub
2. 通过 Gateway 访问 /api/mock/test-endpoint
**预期结果**:
- 请求被 Gateway Agent 拦截，返回 Mock 响应
- 响应包含 `mocked: true, source: baafoo-gateway`

### EG-GW-005: 后端 Mock (网关透传) (P0)
**前置条件**: EG-GW-004 通过
**步骤**:
1. enterprise-gateway 切换为 passthrough
2. enterprise-gateway-backend 切换为 stub
3. 通过 Gateway 访问后端 API
**预期结果**:
- Gateway 透传请求到后端
- 后端 Agent Mock 响应

### EG-GW-006: 全链路 Passthrough (P0, EG-COMMON-004)
**前置条件**: 无
**步骤**:
1. 两个环境均切换为 passthrough
2. 通过 Gateway 访问后端健康检查
**预期结果**:
- 全链路透传，返回真实后端响应

### EG-GW-007: Record 模式录制 (P1, EG-COMMON-005)
**前置条件**: EG-GW-006 通过
**步骤**:
1. 两个环境均切换为 record
2. 通过 Gateway 发送多个请求
3. 查询录制数据
**预期结果**:
- 录制数据存在

### EG-GW-008: 环境模式热切换 (P1, EG-COMMON-006)
**前置条件**: EG-GW-004 通过
**步骤**:
1. stub 模式下访问 /api/mock/**，验证 Mock
2. 切换为 passthrough，验证透传
3. 切换回 stub，验证 Mock 恢复
**预期结果**:
- 每次切换后 10s 内生效

### EG-GW-009: 无类加载冲突 (P0, EG-COMMON-008)
**前置条件**: 应用运行中
**步骤**:
1. 检查 Agent 状态
**预期结果**:
- Agent 状态 online

### EG-GW-010: 应用功能完整性 (P0, EG-COMMON-007)
**前置条件**: 应用运行中
**步骤**:
1. 验证 Gateway 健康
2. 验证后端服务健康
**预期结果**:
- Gateway UP，后端 OK

### EG-GW-011: 过滤器链兼容 (P1)
**前置条件**: Gateway 运行中
**步骤**:
1. 访问 Gateway actuator/gateway/routes 端点
**预期结果**:
- 路由列表正常返回，过滤器链未被 Agent 破坏

### EG-GW-012: 多 Agent 环境隔离 (P1)
**前置条件**: EG-GW-004 + EG-GW-005 通过
**步骤**:
1. enterprise-gateway 设为 stub，enterprise-gateway-backend 设为 passthrough
2. 同时访问 Gateway Mock 路径和透传路径
**预期结果**:
- Gateway 层 Mock 生效，Backend 层透传
- 两个 Agent 独立工作，互不干扰
