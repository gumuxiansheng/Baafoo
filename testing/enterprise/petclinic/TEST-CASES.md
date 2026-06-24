# Spring Boot PetClinic 企业级测试用例

## 测试环境信息

| 项目 | 值 |
|------|-----|
| 应用 | Spring Boot PetClinic REST (springcommunity/spring-petclinic-rest) |
| 框架 | Spring Boot 2.x / 3.x + Spring Data JPA + H2 |
| 协议覆盖 | HTTP |
| Baafoo Server | baafoo-server:8084 |
| API Key | enterprise-admin-key |
| 测试环境 | enterprise-petclinic |

## 测试用例矩阵

### EG-PET-001: 应用启动 + Agent 挂载无异常 (P0)
**前置条件**: 无
**步骤**:
1. 启动 Baafoo Server
2. 启动 PetClinic 应用（挂载 Agent）
**预期结果**:
- PetClinic 正常启动，无异常堆栈
- 应用健康检查通过 (`GET /petclinic/api/vets` 返回 200)
- Agent 日志无 ERROR 级别错误
- 无 ClassNotFoundException / NoClassDefFoundError

### EG-PET-002: Agent 成功注册到 Server (P0)
**前置条件**: EG-PET-001 通过
**步骤**:
1. 调用 Server API 查询 Agent 列表
**预期结果**:
- 返回结果中包含 environment=enterprise-petclinic 的 Agent
- Agent 状态为 online

### EG-PET-003: HTTP GET 请求 Mock 验证 - Vet API (P0)
**前置条件**: EG-PET-002 通过
**步骤**:
1. 确保环境模式为 stub
2. 访问 `GET /petclinic/api/vets`
**预期结果**:
- 返回状态码 200
- 返回体中包含 `"mocked": true`
- 返回体中包含 Mock 兽医数据

### EG-PET-004: HTTP GET 请求 Mock 验证 - Owner API (P0)
**前置条件**: EG-PET-003 通过
**步骤**:
1. 访问 `GET /petclinic/api/owners`
**预期结果**:
- 返回状态码 200
- 返回体中包含 Mock 业主数据

### EG-PET-005: Passthrough 模式透传验证 (P0)
**前置条件**: EG-PET-003 通过
**步骤**:
1. 将环境模式切换为 passthrough
2. 等待规则生效（约 10s）
3. 访问 `GET /petclinic/api/vets`
**预期结果**:
- 返回真实的兽医列表（不含 mocked 字段）
- 应用正常工作，功能不受影响

### EG-PET-006: Record 模式录制验证 (P1)
**前置条件**: EG-PET-005 通过
**步骤**:
1. 将环境模式切换为 record
2. 访问几次 PetClinic API（vets, owners, pets 等）
3. 查询录制数据: `GET /__baafoo__/api/recordings`
**预期结果**:
- 录制数据存在
- 录制内容包含正确的请求路径、方法、响应状态码
- 录制的请求/响应体完整

### EG-PET-007: 环境模式热切换 (P1)
**前置条件**: EG-PET-003 通过
**步骤**:
1. stub 模式下验证 Mock 响应
2. 切换为 passthrough 模式，验证真实响应
3. 切换回 stub 模式，验证 Mock 恢复
4. 切换过程中应用无崩溃、无异常
**预期结果**:
- 每次切换后 10s 内生效
- 切换过程中应用无异常
- 模式切换后行为正确

### EG-PET-008: 应用功能完整性验证 (P0)
**前置条件**: passthrough 模式
**步骤**:
1. 验证 PetClinic 核心 API 都能正常工作：
   - GET /petclinic/api/vets - 兽医列表
   - GET /petclinic/api/owners - 业主列表
   - GET /petclinic/api/pets - 宠物列表
   - GET /petclinic/api/specialties - 专科列表
   - GET /petclinic/api/visits - 就诊记录
2. 验证 POST/PUT/DELETE 操作（如果有）
**预期结果**:
- 所有核心 API 正常返回
- 数据格式正确
- 无功能异常

### EG-PET-009: 无类加载冲突 (P0)
**前置条件**: 应用运行中
**步骤**:
1. 检查应用日志，搜索：
   - ClassNotFoundException
   - NoClassDefFoundError
   - ClassCastException
   - LinkageError
2. 多次调用 API，观察是否有类加载异常
**预期结果**:
- 无类加载相关异常

### EG-PET-010: 内存泄漏检查（短期） (P1)
**前置条件**: 应用稳定运行
**步骤**:
1. 记录初始堆内存使用量
2. 持续调用 API 1 小时（每 10s 调用一次）
3. 触发 Full GC 后记录堆内存使用量
4. 对比初始和结束时的堆内存
**预期结果**:
- Full GC 后堆内存趋势平稳
- 无持续性内存增长
- 无 OOM 异常

### EG-PET-011: CPU 开销评估 (P1)
**前置条件**: passthrough 模式
**步骤**:
1. 无 Agent 时，压测 5 分钟，记录平均 CPU 使用率
2. 有 Agent（stub 模式）时，相同压测，记录平均 CPU 使用率
3. 计算 CPU 增加百分比
**预期结果**:
- CPU 增加在可接受范围内（建议 ≤ 15%）

### EG-PET-012: 异步调用拦截验证 (P2)
**前置条件**: EG-PET-003 通过
**步骤**:
1. 如果 PetClinic 有异步调用的接口，测试其 Mock 效果
2. 验证 `@Async` 注解的方法中的 HTTP 调用能否被拦截
**预期结果**:
- 异步调用中的 HTTP 请求也能被正确拦截和 Mock

### EG-PET-013: 定时任务调用拦截验证 (P2)
**前置条件**: EG-PET-003 通过
**步骤**:
1. 如果 PetClinic 有定时任务（`@Scheduled`），观察其调用的 HTTP 请求
2. 验证定时任务中的调用能否被 Mock
**预期结果**:
- 定时任务中的 HTTP 请求也能被正确拦截
