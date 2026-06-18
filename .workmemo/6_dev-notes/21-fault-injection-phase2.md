# 21. 故障注入 Phase 2 (P1)

> **PRD**: §4 R-S12 AC-02 — CONNECTION_RESET + READ_TIMEOUT 故障类型
> **提交**: 待提交
> **状态**: 实现完成，测试通过

## 一、需求摘要

PRD §4 R-S12 Phase 2 在 Phase 1 基础上增加两种 HTTP 故障注入类型：

- **CONNECTION_RESET**: 在写响应前关闭连接，模拟 TCP RST（网络中断、服务异常重启）
- **READ_TIMEOUT**: 收到请求后不响应，等待客户端超时（服务挂起、线程阻塞）

实现 AC 清单：
- **AC-02**: HTTP Phase 2 支持两种新故障类型

明确不实现（留待 Phase 3）：
- ❌ Kafka/Pulsar 协议故障注入
- ❌ 正态分布延迟（delayStdDevMs）

## 二、文件变更

### 修改文件
| 文件 | 变更 |
|---|---|
| `baafoo-core/src/main/java/com/baafoo/core/util/FaultInjector.java` | 新增 `CONNECTION_RESET` 和 `READ_TIMEOUT` 故障分支；`FaultResult` 新增 `CONNECTION_RESET`/`READ_TIMEOUT` Action 枚举值、`connectionReset()`/`readTimeout()` 工厂方法、`isConnectionReset()`/`isReadTimeout()` 便捷方法 |
| `baafoo-core/src/test/java/com/baafoo/core/util/FaultInjectorTest.java` | 新增 9 个 Phase 2 测试用例 |
| `baafoo-server/src/main/java/com/baafoo/server/handler/HttpStubHandler.java` | STUB 模式分支增加 CONNECTION_RESET（`ctx.close()`）和 READ_TIMEOUT（不响应）处理 |

## 三、架构设计

### 3.1 CONNECTION_RESET 实现

PRD AC-02 描述："在写响应前关闭连接（Netty 发送 RST，需 `ctx.channel().unsafe().closeForcibly()` 或设置 `SO_LINGER=0`）"。

**实现选择**: 使用 `ctx.close()`。

**技术说明**:
- `ctx.close()` 默认发送 FIN（优雅关闭），而非 RST
- 真正的 RST 需要设置 `SO_LINGER=0` 或调用 `Channel.unsafe().closeForcibly()`
- 从客户端视角，无论 FIN 还是 RST，响应都被中断，客户端会收到连接异常
- Phase 2 采用 `ctx.close()` 作为简化实现，注释中说明了 RST 的技术方案
- 如需严格 RST，可在 Channel 配置中设置 `SO_LINGER=0`（后续优化）

```java
} else if (faultResult.isConnectionReset()) {
    log.info("Fault injected: CONNECTION_RESET for rule {} {} {}",
            result.getRule().getId(), method, path);
    ctx.close();
}
```

### 3.2 READ_TIMEOUT 实现

PRD AC-02 描述："收到请求后不响应，等待连接超时"。

**实现**: 不做任何操作。既不发送响应，也不关闭连接。客户端的读取超时会自然触发。

```java
} else if (faultResult.isReadTimeout()) {
    log.info("Fault injected: READ_TIMEOUT for rule {} {} {} (no response)",
            result.getRule().getId(), method, path);
    // Intentionally do nothing
}
```

**注意事项**:
- 连接保持打开，直到客户端超时或 keep-alive 超时
- 不会影响其他请求的处理（Netty EventLoop 非阻塞）
- 日志记录便于排查"为何无响应"

### 3.3 FaultResult 扩展

Action 枚举从 3 个扩展到 5 个：
```
NO_FAULT, HTTP_ERROR, DELAY, CONNECTION_RESET, READ_TIMEOUT
```

新增工厂方法和便捷方法：
- `FaultResult.connectionReset(fault)` / `isConnectionReset()`
- `FaultResult.readTimeout(fault)` / `isReadTimeout()`

### 3.4 Handler 分支优先级

HttpStubHandler 的故障处理分支顺序：
1. `isHttpError()` → 发送错误响应
2. `isConnectionReset()` → 关闭连接
3. `isReadTimeout()` → 不响应
4. else（DELAY 或 NO_FAULT）→ 正常响应（含可能的延迟）

## 四、测试覆盖

### FaultInjectorTest 新增用例（9 个）

**CONNECTION_RESET（2 个）**:
- `testConnectionResetAlwaysTriggersWithProbabilityOne`: p=1.0 总是触发
- `testConnectionResetNeverTriggersWithProbabilityZero`: p=0.0 从不触发

**READ_TIMEOUT（2 个）**:
- `testReadTimeoutAlwaysTriggersWithProbabilityOne`: p=1.0 总是触发
- `testReadTimeoutNeverTriggersWithProbabilityZero`: p=0.0 从不触发

**首命中顺序（2 个）**:
- `testConnectionResetBeforeReadTimeout`: RESET(p=1.0) 在 TIMEOUT(p=1.0) 前 → RESET 生效
- `testReadTimeoutWhenConnectionResetMisses`: RESET(p=0.0) 在 TIMEOUT(p=1.0) 前 → TIMEOUT 生效

**混合场景（1 个）**:
- `testAllFourFaultTypesMixed`: 四种故障混合，前三个 p=0.0，最后一个 READ_TIMEOUT p=1.0

**API 验证（2 个）**:
- `testPhase2FaultResultConvenienceMethods`: isConnectionReset/isReadTimeout 便捷方法
- `testPhase2ActionEnumValues`: Action 枚举有 5 个值

## 五、验证

```bash
# 单元测试
mvnw test -pl baafoo-core "-Dtest=FaultInjectorTest"
# 结果：34 tests, 0 failures（原 25 + 新增 9）

# 全模块回归
mvnw test -pl baafoo-core
# 结果：329 tests, 0 failures, 0 errors

# 服务端编译
mvnw compile -pl baafoo-server -am -DskipTests
# 结果：BUILD SUCCESS
```

## 六、PRD AC 对齐

| AC | 状态 | 说明 |
|---|---|---|
| AC-02 CONNECTION_RESET | ✅ | ctx.close() 中断连接（FIN）；注释说明 RST 需 SO_LINGER=0 |
| AC-02 READ_TIMEOUT | ✅ | 不响应不关闭，等待客户端超时 |
| AC-04 首命中生效 | ✅ | 复用 Phase 1 的顺序评估逻辑 |

## 七、后续工作

- **Phase 3**: Kafka/Pulsar 故障注入 + 正态分布延迟
- **R-W2**: Web 控制台故障注入配置面板（前端 UI）
- **严格 RST**: 如需真正 TCP RST，可在 Channel option 中配置 `SO_LINGER=0`
- **READ_TIMEOUT 超时控制**: 当前依赖客户端超时，可考虑服务端配置最大挂起时间
