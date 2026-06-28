# 19. 故障注入 Phase 1 (P1)

> **PRD**: §4 R-S12 AC-01 — HTTP_ERROR + DELAY 故障类型
> **提交**: 待提交
> **状态**: 实现完成，测试通过

## 一、需求摘要

PRD §4 R-S12 Phase 1 要求在 HTTP 协议上支持两种故障注入类型：

- **HTTP_ERROR**: 按概率返回指定状态码列表中的某一个（如 20% 概率返回 503/504）
- **DELAY**: 按概率延迟响应指定毫秒数（如 50% 概率延迟 2000ms）

故障按声明顺序评估，首个 probability 命中的 fault 生效；全部未命中则走正常响应（AC-04）。

实现 AC 清单：
- **AC-01**: HTTP_ERROR + DELAY 两种故障类型
- **AC-04**: 按声明顺序评估，首个命中生效，全部未命中走正常响应
- **R-C2 扩展**: `faultInjection` 字段已在 Step 1 添加到 Rule 模型和数据库 schema

明确不实现（留待后续 Phase）：
- ❌ CONNECTION_RESET / READ_TIMEOUT — Phase 2
- ❌ Kafka/Pulsar 故障注入 — Phase 3
- ❌ 正态分布延迟（delayStdDevMs）— Phase 3

## 二、文件变更

### 新增文件
| 文件 | 用途 |
|---|---|
| `baafoo-core/src/main/java/com/baafoo/core/util/FaultInjector.java` | 故障注入评估器，无状态工具类 |
| `baafoo-core/src/test/java/com/baafoo/core/util/FaultInjectorTest.java` | 单元测试（25 个用例） |

### 修改文件
| 文件 | 变更 |
|---|---|
| `baafoo-server/src/main/java/com/baafoo/server/handler/StubResponseRenderer.java` | 新增 `sendStubResponse` 重载接收 `faultDelayMs` 参数，总延迟 = entry delay + fault delay；新增 `sendFaultErrorResponse` 方法发送 HTTP_ERROR 故障响应（含 `X-Baafoo-Fault` 头） |
| `baafoo-server/src/main/java/com/baafoo/server/handler/HttpStubHandler.java` | 新增 `faultRandom` 字段；STUB 模式分支集成 `FaultInjector.evaluate`：HTTP_ERROR → 发送故障错误响应，DELAY → 透传延迟到 renderer，NO_FAULT → 正常响应 |

## 三、架构设计

### 3.1 FaultInjector 评估器

**设计原则**: 无状态工具类，线程安全。调用方提供 `Random` 实例，支持确定性测试。

```java
public static FaultResult evaluate(FaultInjection config, Random random) {
    // 1. null/empty config → noFault
    // 2. 遍历 faults，按声明顺序评估
    // 3. 对每个 fault: roll = random.nextDouble()
    //    若 roll < probability → 该 fault 命中，返回对应 FaultResult
    // 4. 全部未命中 → noFault
}
```

**FaultResult 三种 Action**:
- `NO_FAULT` — 正常响应
- `HTTP_ERROR` — 返回错误状态码（从 statusCodes 列表等概率选取）
- `DELAY` — 延迟正常响应 delayMs 毫秒

### 3.2 HTTP_ERROR 处理

当 HTTP_ERROR 故障命中时：
1. 从 `statusCodes` 列表中等概率随机选取一个状态码
2. 空列表/null 列表默认返回 500
3. 调用 `StubResponseRenderer.sendFaultErrorResponse` 发送错误响应
4. 响应包含 `X-Baafoo-Fault: HTTP_ERROR` 头，便于客户端识别故障注入

响应体格式：
```json
{
  "error": "Fault injected",
  "faultType": "HTTP_ERROR",
  "statusCode": 503
}
```

### 3.3 DELAY 处理

当 DELAY 故障命中时：
1. 读取 `delayMs`，负值截断为 0
2. 将 delayMs 透传给 `StubResponseRenderer.sendStubResponse` 的 `faultDelayMs` 参数
3. Renderer 计算总延迟 = `entry.getDelayMs()` + `faultDelayMs`
4. 使用 `ctx.executor().schedule` 在 EventLoop 上调度延迟响应（非阻塞）

**为什么不在 handler 中直接 schedule？** 保持延迟逻辑集中在 renderer，避免分散的 Netty 调度代码。Renderer 已有 entry delay 的调度逻辑，fault delay 只需加到总延迟上。

### 3.4 首命中生效（AC-04）

PRD AC-04 明确："故障按声明顺序评估，首个 probability 命中的 fault 生效"。

实现：遍历 faults 列表，对每个 fault 独立掷骰（`random.nextDouble() < probability`），首个命中的 fault 返回，后续 faults 不再评估。

**PRD 示例验证**（`testPrdExampleScenario` 测试）:
```yaml
faults:
  - type: HTTP_ERROR
    probability: 0.2
    statusCodes: [503, 504]
  - type: DELAY
    probability: 0.5
    delayMs: 2000
```

10000 次模拟结果：
- HTTP_ERROR: ~2000 次（20%）✓
- DELAY: ~4000 次（50% × 80% = 40%）✓
- 正常: ~4000 次（剩余 40%）✓

### 3.5 随机数源

`HttpStubHandler` 持有一个 `Random faultRandom` 实例字段，所有请求共享。`Random` 本身线程安全（内部使用 CAS），适合 Netty 多线程环境。

**不使用 ThreadLocalRandom 的原因**: `ThreadLocalRandom` 不支持种子设置，无法做确定性测试。`FaultInjector.evaluate` 接受外部 `Random` 参数，测试时可传入 `new Random(seed)` 实现确定性。

## 四、测试覆盖

### FaultInjectorTest（25 个用例）

**Null/empty 边界（4 个）**:
- null config → noFault
- empty faults → noFault
- null faults list → noFault
- null random → IllegalArgumentException

**HTTP_ERROR（6 个）**:
- probability 1.0 → 总是触发
- probability 0.0 → 从不触发
- 多状态码等概率分配 → 503/504 均出现
- 单状态码
- 空状态码列表 → 默认 500
- null 状态码列表 → 默认 500

**DELAY（4 个）**:
- probability 1.0 → 总是触发
- probability 0.0 → 从不触发
- 负 delayMs → 截断为 0
- 零 delayMs

**首命中顺序（3 个）**:
- HTTP_ERROR(p=1.0) 在 DELAY(p=1.0) 前 → HTTP_ERROR 生效
- HTTP_ERROR(p=0.0) 在 DELAY(p=1.0) 前 → DELAY 生效
- 全部 p=0.0 → noFault

**边界场景（4 个）**:
- 未知 fault type → noFault
- null fault 元素 → 跳过
- null type fault → 跳过
- seeded random 确定性 → 相同种子相同结果

**概率分布（1 个）**:
- probability 0.5 → 1000 次中约 500 次（±100 容差）

**FaultResult API（2 个）**:
- Action 枚举值
- 便捷方法 isNoFault/isHttpError/isDelay + getter

**PRD 示例场景（1 个）**:
- 10000 次模拟验证 20%/40%/40% 分布

## 五、验证

```bash
# 单元测试
mvnw test -pl baafoo-core "-Dtest=FaultInjectorTest"
# 结果：25 tests, 0 failures

# 全模块回归
mvnw test -pl baafoo-core
# 结果：283 tests, 0 failures, 0 errors

# 服务端编译
mvnw compile -pl baafoo-server -am -DskipTests
# 结果：BUILD SUCCESS
```

## 六、PRD AC 对齐

| AC | 状态 | 说明 |
|---|---|---|
| AC-01 HTTP_ERROR | ✅ | probability + statusCodes，等概率选取 |
| AC-01 DELAY | ✅ | probability + delayMs，负值截断 |
| AC-04 首命中生效 | ✅ | 按声明顺序评估，首个命中返回 |
| AC-04 独立条件概率 | ✅ | 每个 fault 独立掷骰，非分配概率 |
| R-C2 faultInjection 字段 | ✅ | Step 1 已添加到 Rule 模型和 DB schema |

## 七、后续工作

- **Phase 2**: CONNECTION_RESET（写响应前 RST 关闭连接）+ READ_TIMEOUT（不响应等待超时）
- **Phase 3**: Kafka/Pulsar 故障注入 + 正态分布延迟（delayStdDevMs）
- **R-W2**: Web 控制台故障注入配置面板（前端 UI）
- **可观测性**: 可考虑在响应中增加 `X-Baafoo-Fault-Probability` 头辅助调试
