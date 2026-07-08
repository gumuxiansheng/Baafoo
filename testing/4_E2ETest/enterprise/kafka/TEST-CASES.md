# Kafka 企业级测试用例

## 测试环境信息

| 项目 | 值 |
|------|-----|
| 应用 | Apache Kafka 3.6.1 (Bitnami) |
| 测试客户端 | baafoo-test-spring (Kafka Producer/Consumer) |
| 协议覆盖 | Kafka |
| Baafoo Server | baafoo-server:8084 |
| API Key | enterprise-admin-key |
| 测试环境 | enterprise-kafka |

## 测试用例矩阵

### EG-KAFKA-001: 应用启动 + Agent 挂载无异常 (P0)
**前置条件**: 无
**步骤**:
1. 启动 Kafka Broker
2. 启动 Baafoo Server
3. 启动 kafka-test-app（挂载 Agent）
**预期结果**:
- kafka-test-app 正常启动，无异常堆栈
- 应用健康检查通过 (HTTP 200)
- Agent 日志无 ERROR 级别错误

### EG-KAFKA-002: Agent 成功注册到 Server (P0)
**前置条件**: EG-KAFKA-001 通过
**步骤**:
1. 调用 Server API 查询 Agent 列表: `GET /__baafoo__/api/agents`
**预期结果**:
- 返回结果中包含 environment=enterprise-kafka 的 Agent
- Agent 状态为 online

### EG-KAFKA-003: Kafka Producer 消息拦截与 Mock (P0)
**前置条件**: EG-KAFKA-002 通过
**步骤**:
1. 确保环境模式为 stub
2. 配置 Topic 匹配规则（已由 init 容器创建）
3. 调用测试应用发送消息: `POST /api/stub-demo/kafka/send`
   - bootstrapServers: `kafka:9092`
   - topic: `enterprise-test-topic`
   - message: `hello enterprise`
**预期结果**:
- 消息发送成功 (success=true)
- 返回的 offset 由 Mock Broker 生成
- 消息内容为配置的 Mock 响应（消费时验证）

### EG-KAFKA-004: Kafka Consumer 消息拦截与 Mock (P0)
**前置条件**: EG-KAFKA-003 通过
**步骤**:
1. 调用测试应用消费消息: `POST /api/stub-demo/kafka/consume`
   - bootstrapServers: `kafka:9092`
   - topic: `enterprise-test-topic`
**预期结果**:
- 消费成功 (success=true)
- 消费到的消息 value 包含 Mock 响应内容
- 消息格式符合配置的模板

### EG-KAFKA-005: Topic 通配符匹配 (P1)
**前置条件**: EG-KAFKA-003 通过
**步骤**:
1. 发送消息到 topic: `enterprise-wildcard-test`
2. 消费该 topic 的消息
**预期结果**:
- 消息被通配符规则匹配
- 返回 Mock 响应

### EG-KAFKA-006: Passthrough 模式透传真实 Kafka (P0)
**前置条件**: 真实 Kafka 运行正常
**步骤**:
1. 将环境模式切换为 passthrough
2. 发送消息到真实 Kafka 的某个 topic
3. 使用 kafka-console-consumer 直接从真实 Kafka 消费
**预期结果**:
- 消息成功发送到真实 Kafka
- 真实 Kafka 中能消费到原始消息
- Agent 不修改消息内容

### EG-KAFKA-007: Record 模式录制 (P1)
**前置条件**: EG-KAFKA-006 通过
**步骤**:
1. 将环境模式切换为 record
2. 发送几条消息到真实 Kafka
3. 查询录制数据: `GET /__baafoo__/api/recordings`
**预期结果**:
- 录制数据存在
- 录制内容包含正确的 topic、key、value

### EG-KAFKA-008: 环境模式热切换 (P1)
**前置条件**: EG-KAFKA-003 通过
**步骤**:
1. stub 模式下发送消息，验证 Mock
2. 运行中切换为 passthrough 模式
3. 发送消息，验证透传
4. 切换回 stub 模式，验证 Mock 恢复
**预期结果**:
- 每次切换后 10s 内生效
- 切换过程中应用无异常
- 模式切换后行为正确

### EG-KAFKA-009: 无类加载冲突 (P0)
**前置条件**: 应用运行中
**步骤**:
1. 检查应用日志，搜索 ClassNotFoundException / NoClassDefFoundError / ClassCastException
2. 多次发送/消费消息，观察是否有类加载异常
**预期结果**:
- 无类加载相关异常

### EG-KAFKA-010: 高吞吐下 Agent 稳定性 (P1)
**前置条件**: EG-KAFKA-003 通过
**步骤**:
1. 持续发送消息 5 分钟（每秒 100 条）
2. 观察应用和 Agent 状态
**预期结果**:
- 无内存溢出
- 无异常抛出
- Mock 成功率 > 99%

### EG-KAFKA-011: Kafka 多版本客户端兼容 (P2)
**前置条件**: 有多个 Kafka 客户端版本的测试能力
**步骤**:
1. 使用 Kafka 2.8 客户端测试
2. 使用 Kafka 3.0 客户端测试
3. 使用 Kafka 3.5 客户端测试
**预期结果**:
- 各版本客户端均能正常工作
- Mock 功能正常
