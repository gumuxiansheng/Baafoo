# Phase 1 & 2 代码 Review

> 审查范围：commit `661968f`（Kafka 协议升级）+ commit `3257ce4`（Pulsar 协议升级）
> 审查时间：2026-06-19 | 审查人：代可行

---

## 一、总体评价

两个 commit 质量较高。`KafkaFlexibleCodec` 实现正确，`KafkaProtocolVersions` 集中版本管理思路对，Pulsar `protocolVersion` 协商逻辑合理。测试覆盖充分（KafkaMockBrokerTest +273 行，PulsarMockBrokerTest +234 行）。

**但发现一个明确回归和一个潜在风险，建议修复后再合入主干。**

---

## 二、Bug

### 🔴 [HIGH] `API_FIND_COORDINATOR` 版本上限被错误降低：v4 → v2

**位置**：`KafkaProtocolVersions.java` 第 82 行

```java
{API_FIND_COORDINATOR,  0, 2},   // v2 = non-flexible max (v3+ uses compact strings)
```

**问题**：原 `KafkaProtocolDecoder.handleApiVersions()` 中 `FIND_COORDINATOR` 上限是 v4，现在被降到 v2。注释说"v3+ uses compact strings"，但 **FindCoordinator 在所有版本中都不使用 flexible 格式**（KIP-482 未将其列入 flexible 版本清单）。降低上限会直接导致使用 FindCoordinator v3/v4 的客户端（Kafka 2.0+）收到 `UNSUPPORTED_VERSION` 错误。

**修复**：

```java
{API_FIND_COORDINATOR,  0, 4},   // v4 = non-flexible max (FindCoordinator has no flexible version)
```

**验证方法**：`KafkaMockBrokerTest` 中增加一个 FindCoordinator v4 请求，确认返回 error_code=0。

---

## 三、潜在风险

### 🟡 [MEDIUM] `writeUnsignedVarint` 对负值缺乏防御

**位置**：`KafkaFlexibleCodec.java` 第 51 行

```java
public static void writeUnsignedVarint(ByteBuf buf, int value) {
    while ((value & ~0x7F) != 0) {
        buf.writeByte((byte) ((value & 0x7F) | 0x80));
        value >>>= 7;
    }
    buf.writeByte((byte) value);
}
```

**问题**：`value` 为 `int`，调用方如果传入负数（如 `topic_id` UUID 转换错误），`value >>>= 7` 会导致无限循环（`~0x7F = 0xFFFFFF80`，负数的高位永远是 1）。

**实际风险**：当前调用方均传入合法非负值，不会触发。但作为工具类，建议加防御：

```java
public static void writeUnsignedVarint(ByteBuf buf, int value) {
    if (value < 0) {
        throw new IllegalArgumentException("Unsigned varint cannot be negative: " + value);
    }
    while ((value & ~0x7F) != 0) {
        buf.writeByte((byte) ((value & 0x7F) | 0x80));
        value >>>= 7;
    }
    buf.writeByte((byte) value);
}
```

### 🟡 [MEDIUM] `KafkaProtocolVersions.SUPPORTED_APIS` 与 `KafkaProtocolDecoder` 的 fallback 逻辑存在歧义

**位置**：`KafkaProtocolDecoder.java` `channelRead0()` 约第 115-125 行

**问题**：PRD 说"ApiVersions v3 暂不升级，让客户端不切换 flexible header"。但代码中的 fallback 逻辑是：客户端发来 v3+ 时，尝试用固定长度 header 重新解析。这意味着 **客户端实际上可以用 ApiVersions v3 与 Baafoo 通信**，只是后续请求仍用非 flexible 格式。

这与 `SUPPORTED_APIS` 中 `API_API_VERSIONS` 上限 v2 不一致——客户端问"你支持 v3 吗？"，Baafoo 说"不支持，我只到 v2"，但客户端如果无视这个结果坚持用 v3 发 ApiVersions 请求，Baafoo 的 fallback 逻辑又能处理。

**建议**：在 `handleApiVersions()` 中增加明确日志：`log.warn("Client requested ApiVersions v{}, but max supported is v2. Client will use non-flexible header.", apiVersion);`

---

## 四、代码质量

### ✅ 做得好的地方

1. **`KafkaFlexibleCodec` 实现正确**。unsigned varint 的读写、compact string（含 null 语义 `uvarint(0)`）、UUID（16 bytes，全零 = null）均符合 KIP-482 规范。

2. **`KafkaProtocolVersions` 集中版本管理**。将散落在 `handleApiVersions()` 中的 `int[][] supportedApis` 提取到独立文件，后续升级只需改一个文件，架构正确。

3. **Fetch v10/v11 处理完整**。v8 `current_leader_epoch` + `rack_id`、v10 `topic_id`（UUID，响应中写 16 字节全零）、v11 `forgotten_topics_data` 均有解析且正确 skip。

4. **Pulsar `protocolVersion` 协商正确**。CONNECT 请求中的 `protocolVersion` 被读取并与 `MAX_SUPPORTED_PROTOCOL_VERSION=20` 取 `Math.min()`，客户端版本 >20 时主动封顶并打 warn 日志，行为明确。

5. **测试覆盖充分**。KafkaMockBrokerTest 新增 v8/v10/v11 专项用例；PulsarMockBrokerTest 新增 protocolVersion 19/20/21 矩阵测试。

### ⚠️ 可改进的地方

1. **`KafkaProtocolDecoder.java` 仍然 1300+ 行**。`handleProduce()` 约 240 行，`handleFetch()` 约 200 行，PRD 中规划的 `codec/` 子目录（拆分 Produce/Fetch/Metadata 各自 codec）**未实施**。当前 commit 只新增了 `KafkaFlexibleCodec`（工具类），未做 handler 拆分。

   **影响**：后续升级 Produce v9（flexible）时，`handleProduce()` 会继续膨胀到 350+ 行，可维护性持续恶化。

   **建议**：Phase 1 范围内可以不拆，但在下一个 Kafka 协议升级任务中，应作为前置工作完成拆分。

2. **`PulsarProtobufCodec.java` 的 `MAX_SUPPORTED_PROTOCOL_VERSION` 硬编码为 20**，但没有类似 `KafkaProtocolVersions` 的集中版本表。后续 Pulsar 3.1+（protocolVersion 21+）需要改两个地方（常量 + handler 中 `Math.min()`），容易遗漏。

   **建议**：抽取 `PulsarProtocolVersions.java`，与 Kafka 侧保持一致架构。

---

## 五、完整性评估（对照 PRD）

| PRD 任务 | 状态 | 备注 |
|-----------|------|------|
| K-01: `KafkaFlexibleCodec` | ✅ | 完整实现 |
| K-02: 抽离 `KafkaProduceCodec` | ❌ | **未实施**，`handleProduce()` 仍在 `KafkaProtocolDecoder` 中 |
| K-03: `KafkaProduceCodecV9`（flexible Produce） | ⏭ | PRD 说"暂不升级 ApiVersions v3"，所以 v9 不需要，合理跳过 |
| K-04: `KafkaFetchCodecV12` | ⏭ | 同上，Fetch v12 是 flexible，暂不实施，合理 |
| K-05: `KafkaMetadataCodecV9` | ⏭ | 同上，合理 |
| K-06: `KafkaProtocolVersions` + 更新 `handleApiVersions` | ✅ | 完成 |
| K-07: Kafka 客户端兼容性测试矩阵 | ⚠️ | 代码中有测试但不确定是否覆盖 6 个版本（PRD 要求） |
| K-08: 回归测试 | ⚠️ | 134 测试通过，但 FindCoordinator 回归未被捕获，说明测试用例有缺口 |

| PRD 任务 | 状态 | 备注 |
|-----------|------|------|
| P-01: Pulsar 3.x field number 差异调研 | ✅ | commit message 隐含完成 |
| P-02: 更新 `PulsarProtobufCodec` 到 v20 | ✅ | `MAX_SUPPORTED_PROTOCOL_VERSION=20` |
| P-03: `MessageMetadata` v20 新字段 skip/解析 | ⚠️ | 代码中未见显式处理 `broker_entry_metadata`（field ~9），可能静默跳过 |
| P-04: Pulsar 客户端兼容性测试 | ✅ | PulsarMockBrokerTest 新增 234 行 |
| P-05: CONNECT 处理中加入 `protocolVersion` 感知 | ✅ | `PulsarMockBrokerHandler` 中已实现协商 |

---

## 六、建议

### 立即修复（并入当前 commit 或单独 bugfix commit）

1. **修复 FindCoordinator 版本回归**：`KafkaProtocolVersions.SUPPORTED_APIS` 第 82 行，`API_FIND_COORDINATOR` 上限改回 v4。

### 短期改进（下一个 commit）

2. **补充 `MessageMetadata` v20 `broker_entry_metadata` 字段处理**。`PulsarProtobufCodec.parseMessageMetadata()` 中，field number 9 应显式 skip（或解析），避免 protobuf unknown field 语义导致的数据错位。

3. **`KafkaProtocolDecoder` handler 拆分**。将 `handleProduce()` / `handleFetch()` / `handleMetadata()` 抽离到 `codec/KafkaProduceCodec.java` 等文件，降低主类复杂度。

### 中期改进（下一个协议升级任务）

4. **抽取 `PulsarProtocolVersions.java`**，与 Kafka 侧架构对齐。

5. **考虑升级 ApiVersions v3**（KIP-511）。这是支持 Kafka 3.x 客户端的根本解法。需要：
   - 实现 Request Header v2 解析（`KafkaFlexibleCodec` 已有基础）
   - 升级 `handleApiVersions()` 到 v3，声明支持 flexible 版本
   - 所有 handler 增加 `isFlexible()` 分支

---

## 七、结论

**可以合入，但建议先修复 FindCoordinator 回归。** 整体实现质量良好，测试覆盖充分，架构方向正确。FindCoordinator 回归是一个明确的 bug，会在 Kafka 2.0+ 客户端场景下触发。
