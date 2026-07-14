# Nacos 企业级测试用例

## 测试环境信息

| 项目 | 值 |
|------|-----|
| 应用 | Nacos Server v2.2.3 |
| 测试客户端 | baafoo-test-spring (HTTP 调用 Nacos API) |
| 协议覆盖 | HTTP (gRPC 长连接为 P1 待支持) |
| Baafoo Server | baafoo-server:8084 |
| API Key | enterprise-admin-key |
| 测试环境 | enterprise-nacos |

## 测试用例矩阵

### EG-NACOS-001: 应用启动 + Agent 挂载无异常 (P0, EG-COMMON-001)
**前置条件**: 无
**步骤**:
1. 启动 Nacos Server
2. 启动 Baafoo Server
3. 启动 nacos-test-app（挂载 Agent）
**预期结果**:
- nacos-test-app 正常启动，无异常堆栈
- 应用健康检查通过 (HTTP 200)
- Agent 日志无 ERROR 级别错误

### EG-NACOS-002: Agent 成功注册到 Server (P0, EG-COMMON-002)
**前置条件**: EG-NACOS-001 通过
**步骤**:
1. 调用 Server API 查询 Agent 列表: `GET /__baafoo__/api/agents`
**预期结果**:
- 返回结果中包含 environment=enterprise-nacos 的 Agent
- Agent 状态为 online

### EG-NACOS-003: Nacos 服务注册 API Mock (P0, EG-COMMON-003)
**前置条件**: EG-NACOS-002 通过
**步骤**:
1. 确保环境模式为 stub
2. 通过测试应用 HTTP 调用 Nacos 注册 API: `POST /nacos/v1/ns/instance`
**预期结果**:
- 请求被 Agent 拦截，返回 Mock 响应
- 响应包含 `mocked: true` 标记

### EG-NACOS-004: Nacos 服务发现 API Mock (P0)
**前置条件**: EG-NACOS-003 通过
**步骤**:
1. 通过测试应用 HTTP 调用 Nacos 服务发现 API: `GET /nacos/v1/ns/instance/list`
**预期结果**:
- 返回 Mock 服务列表，包含 `mocked: true` 标记
- 服务列表格式符合 Nacos API 规范

### EG-NACOS-005: Nacos 配置拉取 API Mock (P0)
**前置条件**: EG-NACOS-003 通过
**步骤**:
1. 通过测试应用 HTTP 调用 Nacos 配置 API: `GET /nacos/v1/cs/configs`
**预期结果**:
- 返回 Mock 配置内容
- 配置包含 `baafoo-nacos` 来源标记

### EG-NACOS-006: Passthrough 模式透传真实 Nacos (P0, EG-COMMON-004)
**前置条件**: 真实 Nacos 运行正常
**步骤**:
1. 将环境模式切换为 passthrough
2. 调用 Nacos 运维 API: `GET /nacos/v1/ns/operator/metrics`
**预期结果**:
- 请求到达真实 Nacos，返回真实指标数据
- Agent 不修改请求/响应内容

### EG-NACOS-007: Record 模式录制 (P1, EG-COMMON-005)
**前置条件**: EG-NACOS-006 通过
**步骤**:
1. 将环境模式切换为 record
2. 发送多个请求到真实 Nacos
3. 查询录制数据: `GET /__baafoo__/api/recordings?environment=enterprise-nacos`
**预期结果**:
- 录制数据存在
- 录制内容包含正确的请求路径和响应

### EG-NACOS-008: 环境模式热切换 (P1, EG-COMMON-006)
**前置条件**: EG-NACOS-003 通过
**步骤**:
1. stub 模式下调用服务发现 API，验证 Mock
2. 切换为 passthrough 模式
3. 调用相同 API，验证透传
4. 切换回 stub 模式，验证 Mock 恢复
**预期结果**:
- 每次切换后 10s 内生效
- 切换过程应用无异常
- 模式切换后行为正确

### EG-NACOS-009: 无类加载冲突 (P0, EG-COMMON-008)
**前置条件**: 应用运行中
**步骤**:
1. 检查 Agent 状态是否保持 online
**预期结果**:
- Agent 状态正常
- 无 ClassNotFoundException / NoClassDefFoundError

### EG-NACOS-010: 应用功能完整性 (P0, EG-COMMON-007)
**前置条件**: 应用运行中
**步骤**:
1. 验证测试应用自身健康端点
2. 验证 HTTP 调用功能正常
**预期结果**:
- 应用核心功能正常

### EG-NACOS-011: Nacos 控制台可达性 (P1)
**前置条件**: Nacos Server 启动完成
**步骤**:
1. 访问 Nacos 控制台健康检查端点
**预期结果**:
- Nacos 控制台可达，返回 UP 状态

### EG-NACOS-012: Nacos gRPC 长连接拦截 (P1)
**前置条件**: Agent 配置 gRPC 协议支持
**步骤**:
1. 配置 gRPC 规则拦截 Nacos 9848 端口长连接
**预期结果**:
- gRPC 长连接被正确拦截
- 服务注册/发现通过 gRPC 的请求也被 Mock
**当前状态**: SKIP（Agent 仅配置 HTTP 协议，gRPC 待后续支持）
