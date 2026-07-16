# mockforge AI 功能迁移到 Baafoo — 详细分析文档

> 生成时间: 2026-06-20
> 分析范围: mockforge AI 集成功能 → Baafoo Java/SpringBoot 项目
> 目标: 评估 mockforge 的 AI 模块中哪些可以移植到 Baafoo，如何移植，投入产出比

## 目录

1. [现状对齐](#1-现状对齐)
2. [Phase 1: 行为克隆（概率挡板）](#2-phase-1-行为克隆概率挡板)
3. [Phase 2: LLM 增强智能挡板](#3-phase-2-llm-增强智能挡板)
4. [Phase 3: 跨协议一致性](#4-phase-3-跨协议一致性)
5. [风险与约束](#5-风险与约束)
6. [投入估算](#6-投入估算)

---

## 1. 现状对齐

### 1.1 Baafoo 已有的基础

| 组件 | 路径 | 对应 mockforge 概念 |
|------|------|---------------------|
| `RecordingEntry` | `baafoo-core/.../model/` | 录制实体，已包含 method/path/statusCode/responseBody/responseTimeMs 等字段 |
| `StorageService` | `baafoo-server/.../storage/` | 录制持久化接口，支持 MySQL (JDBC) 和文件存储 |
| `FaultInjector` | `baafoo-core/.../util/` | 故障注入引擎，已有 HTTP_ERROR/DELAY/CONNECTION_RESET/READ_TIMEOUT 及 Kafka 故障 |
| `HttpStubHandler` | `baafoo-server/.../handler/` | HTTP 挡板处理器，请求匹配 → 挡板响应 / 透传 → 录制 |
| `KafkaProtocolDecoder` | `baafoo-server/.../broker/` | Kafka 协议解码，已支持 Produce/Fetch/Metadata 解析与录制 |
| `PulsarMockBroker` | `baafoo-server/.../broker/` | Pulsar 协议挡板 |
| `MatchEngine` | `baafoo-core/.../util/` | 规则匹配引擎 |
| `StubResponseRenderer` | `baafoo-server/.../handler/` | 挡板响应渲染 |

**关键结论**: Baafoo 已有完整的录制-存储-匹配-回放链路。行为克隆本质上是对已有录制数据的统计建模，**基础设施已就位**。

### 1.2 mockforge 参考模块

| 模块 | Crate | 代码量估计 | 核心价值 |
|------|-------|-----------|---------|
| `behavioral-cloning` | 独立 crate | ~800 行 Rust | 纯统计模型，无外部依赖 |
| `ai-core` | 独立 crate | ~2000 行 Rust | LLM 多供应商抽象 + StatefulAiContext + 缓存 |
| `intelligence/intelligent_behavior` | intelligence 子模块 | ~4000 行 Rust | MockAI 统一接口、规则生成、分页智能等 |

### 1.3 语言映射

| mockforge (Rust) | Baafoo (Java) |
|---|---|
| `trait LlmProvider` | `interface LlmProvider` |
| `Arc<RwLock<T>>` | `ConcurrentHashMap` / `synchronized` |
| `tokio::spawn` | `CompletableFuture` / `ExecutorService` |
| `serde_json::Value` | `com.fasterxml.jackson.databind.JsonNode` |
| `#[async_trait]` | `CompletableFuture<T>` 返回值 |
| `HashMap<String, f64>` | `Map<String, Double>` |
| `enum` (带数据) | `sealed class` / `enum` + `record` |
| `chrono::DateTime<Utc>` | `java.time.Instant` |

---

## 2. Phase 1: 行为克隆（概率挡板）

**目标**: 从录制流量中学习统计分布，生成概率挡板。**不依赖任何 LLM API**。

### 2.1 架构设计

```
录制流量 (RecordingEntry[])
    │
    ▼
┌─────────────────────────────────────┐
│  BehavioralCloningEngine             │
│  ┌─────────────────────────────┐    │
│  │ SequenceLearner              │    │  发现多步流程
│  │  discoverSequences()         │    │
│  └─────────────────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │ ProbabilisticModel           │    │  统计分布建模
│  │  buildFromRecordings()       │    │
│  └─────────────────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │ EdgeAmplifier                │    │  边缘放大
│  │  amplifyRareErrors()         │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
    │
    ▼
EndpointProbabilityModel[]  →  写入 .workmemo/ 或 DB
    │
    ▼
HttpStubHandler / KafkaProtocolDecoder
    │  匹配命中时按分布概率采样
    ▼
响应（状态码 + 延迟 + 负载变体）
```

### 2.2 新增类清单

#### `baafoo-core` 模块

```
com.baafoo.core.model.cloning/
├── EndpointProbabilityModel.java      -- 端点概率模型（状态码分布 + 延迟分布 + 变体）
├── LatencyDistribution.java            -- 延迟统计（p50/p95/p99/mean/stdDev/min/max）
├── BehaviorSequence.java               -- 多步行为序列
├── SequenceStep.java                   -- 序列中的单步
├── PayloadVariation.java               -- 负载变体
├── ErrorPattern.java                   -- 错误模式
└── EdgeAmplificationConfig.java        -- 边缘放大配置
```

#### `baafoo-core` 工具类

```
com.baafoo.core.util.cloning/
├── BehavioralCloningEngine.java        -- 核心引擎（序列发现 + 概率建模 + 放大）
├── ProbabilitySampler.java             -- 基于分布的采样器
└── LatencySampler.java                 -- 延迟采样器（对数正态分布近似）
```

#### `baafoo-server` 集成

```
com.baafoo.server.handler.cloning/
├── CloningStubHandler.java             -- HTTP 概率挡板处理器（装饰 HttpStubHandler）
└── CloningMatchResult.java             -- 概率匹配结果
```

```
com.baafoo.server.broker.cloning/
└── KafkaCloningHandler.java            -- Kafka 概率挡板（装饰 KafkaProtocolDecoder）
```

#### `baafoo-server` API & 存储

```
com.baafoo.server.api.cloning/
├── CloningApiHandler.java              -- REST API：触发分析、查询模型、启停
└── dto/
    ├── CloningAnalysisRequest.java
    └── CloningModelResponse.java

com.baafoo.server.storage.cloning/
├── CloningModelStore.java              -- 模型持久化接口
└── FileCloningModelStore.java          -- 文件存储实现
```

### 2.3 核心算法

#### 2.3.1 端点概率建模 (ProbabilisticModel.buildFromRecordings)

```java
/**
 * 从录制列表构建端点概率模型。
 *
 * 输入: List<RecordingEntry> (按 ruleId + method + path 过滤)
 * 输出: EndpointProbabilityModel
 *
 * 算法:
 * 1. 按 statusCode 分组计数 → 归一化为概率分布
 * 2. responseTimeMs 升序排列 → 计算 p50/p95/p99/mean/stdDev
 * 3. statusCode >= 400 分组为 ErrorPattern，记录 sampleResponses
 * 4. 相似 responseBody 聚类 → PayloadVariation（可选，先用简单哈希分组）
 */
public static EndpointProbabilityModel buildFromRecordings(
    String endpoint, String method, List<RecordingEntry> recordings) {

    int n = recordings.size();
    if (n == 0) throw new IllegalArgumentException("No recordings");

    // 1. 状态码分布
    Map<Integer, Long> statusCounts = recordings.stream()
        .collect(Collectors.groupingBy(RecordingEntry::getResponseStatusCode, Collectors.counting()));
    Map<Integer, Double> statusDistribution = new HashMap<>();
    statusCounts.forEach((code, count) ->
        statusDistribution.put(code, (double) count / n));

    // 2. 延迟分布
    long[] latencies = recordings.stream()
        .mapToLong(RecordingEntry::getResponseTimeMs)
        .sorted().toArray();
    LatencyDistribution latency = LatencyDistribution.fromSorted(latencies);

    // 3. 错误模式
    Map<Integer, ErrorPattern> errorPatterns = new HashMap<>();
    for (RecordingEntry rec : recordings) {
        if (rec.getResponseStatusCode() >= 400) {
            errorPatterns.computeIfAbsent(rec.getResponseStatusCode(), code ->
                new ErrorPattern(String.valueOf(code), 0.0))
                .addSample(rec.getResponseBody());
        }
    }
    // 归一化错误概率
    errorPatterns.values().forEach(ep -> {
        long count = statusCounts.getOrDefault(Integer.parseInt(ep.getType()), 0L);
        ep.setProbability((double) count / n);
    });

    return new EndpointProbabilityModel(
        endpoint, method,
        statusDistribution,
        latency,
        new ArrayList<>(errorPatterns.values()),
        Collections.emptyList(),
        n,
        Instant.now()
    );
}
```

#### 2.3.2 采样 (ProbabilitySampler)

```java
/**
 * 从端点模型采样一个响应决策。
 *
 * 包含: HTTP 状态码、模拟延迟、payload 变体选择
 */
public static class SampleResult {
    int statusCode;
    long simulatedLatencyMs;
    String responseBody;       // 从 sampleResponses 或 payloadVariations 选
    Map<String, String> responseHeaders;
}

public static SampleResult sample(EndpointProbabilityModel model, Random rng) {
    // Roll status code
    double roll = rng.nextDouble();
    double cumulative = 0;
    int chosenCode = 200;
    for (Map.Entry<Integer, Double> entry : model.getStatusDistribution().entrySet()) {
        cumulative += entry.getValue();
        if (roll <= cumulative) {
            chosenCode = entry.getKey();
            break;
        }
    }

    // Sample latency (log-normal approximation from mean/stdDev)
    long simulatedLatency = LatencySampler.sample(
        model.getLatencyDistribution(), rng);

    // Pick response body
    String body = pickBodyForStatus(model, chosenCode, rng);

    return new SampleResult(chosenCode, simulatedLatency, body, ...);
}
```

#### 2.3.3 序列发现 (SequenceLearner)

```java
/**
 * 从录制流量中发现多步行为序列。
 *
 * 算法: 按 sessionId 分组 → 按 recordedAt 排序 → 前缀树聚类
 *
 * 复杂度: O(N log N + N * avg_seq_len)
 *         在 10000 条录制上 < 1 秒
 */
public List<BehaviorSequence> discoverSequences(
    List<RecordingEntry> recordings, double minSupport, ...) {

    // 1. 按 sessionId 分组，组内按时间排序
    Map<String, List<RecordingEntry>> sessions = recordings.stream()
        .filter(r -> r.getSessionId() != null)
        .collect(Collectors.groupingBy(RecordingEntry::getSessionId));
    
    // 2. 每组提取 (method, path) 序列
    List<List<String>> traces = new ArrayList<>();
    for (var session : sessions.values()) {
        session.sort(Comparator.comparingLong(RecordingEntry::getRecordedAt));
        traces.add(session.stream()
            .map(r -> r.getMethod() + " " + r.getPath())
            .collect(Collectors.toList()));
    }

    // 3. 频繁子序列挖掘 (PrefixSpan 或 GSP)
    return FrequentSequenceMiner.mine(traces, minSupport);
}
```

### 2.4 集成到 HttpStubHandler

**关键设计约束**: 不改动现有 `HttpStubHandler` 的主逻辑。通过 **装饰模式** 注入概率采样行为。

```java
// 现有流程:
FullHttpRequest → MatchEngine.match() → StubResponseRenderer.render() → RESPONSE

// 概率挡板流程（新增分支）:
FullHttpRequest → MatchEngine.match()
    ├→ 命中规则 + 规则启用 cloning → CloningMatchResult.sample() → RESPONSE
    └→ 否则 → 现有逻辑（render / passthrough）
```

`HttpStubHandler` 只需增加一个 `CloningModelStore` 成员和 `handleCloningResponse()` 方法，约 30 行改动。

### 2.5 存储方案

模拟模型数据量估算: 每个 endpoint 约 2-5KB（JSON 序列化），100 个 endpoint 约 200-500KB。建议直接 **文件存储**(`.baafoo/models/`) ，JSON 格式，与 mockforge 的模型格式保持一致，方便跨工具互操作。

---

## 3. Phase 2: LLM 增强智能挡板

**前提**: Phase 1 已落地，录制→建模链路通畅。

**目标**: 概率挡板覆盖不了的请求（冷启动、无匹配、需要上下文一致性），降级到 LLM 生成。

### 3.1 架构设计

```
Request → MatchEngine.match()
    ├→ 概率挡板命中 → Phase 1 逻辑
    ├→ 规则命中（固定响应） → 现有逻辑
    └→ 未命中
        ├→ 缓存命中 → 返回缓存
        └→ LLM 生成
            ├→ StatefulAiContext.getState()
            ├→ buildPrompt(method, path, body, schema, context)
            ├→ LlmProvider.generate()
            ├→ parseResponse()
            ├→ cache.put()
            └→ RESPONSE
```

### 3.2 新增类清单

```
com.baafoo.core.model.ai/
├── LlmGenerationRequest.java           -- LLM 请求 (systemPrompt + userPrompt + temperature + maxTokens)
├── LlmGenerationResponse.java          -- LLM 响应 + usage 统计
├── BehaviorRules.java                  -- 行为规则 + schema 映射
└── ConsistencyRule.java                -- 一致性规则

com.baafoo.core.ai/
├── LlmProvider.java                    -- interface: generate(LlmGenerationRequest) → LlmGenerationResponse
├── OpenAiProvider.java                 -- implements LlmProvider (OpenAI API)
├── OllamaProvider.java                 -- implements LlmProvider (Ollama，本地部署)
├── OpenAiCompatibleProvider.java       -- implements LlmProvider (通用 OpenAI 兼容 API)
└── AnthropicProvider.java              -- implements LlmProvider (Anthropic API)

com.baafoo.server.ai/
├── StatefulAiContext.java              -- session 级上下文（KV 状态 + 交互历史）
├── BehaviorModel.java                  -- LLM 驱动的行为模型（prompt 构建 + 一致性校验 + 缓存）
├── LlmResponseCache.java               -- TTL 缓存 (method+path+body → response)
└── SessionManager.java                 -- session 生命周期管理

com.baafoo.server.api.ai/
├── AiConfigApiHandler.java             -- REST API: 配置 LLM provider/model/temperature
└── dto/AiConfigDto.java
```

### 3.3 LlmProvider 接口设计

```java
public interface LlmProvider {
    /**
     * 生成 chat completion。
     */
    LlmGenerationResponse generate(LlmGenerationRequest request) throws LlmException;

    /**
     * 检查 provider 是否可用（健康检查）。
     */
    boolean isAvailable();

    /**
     * 返回 provider 标识。
     */
    String getName();
}

public record LlmGenerationRequest(
    String systemPrompt,
    String userPrompt,
    double temperature,    // default 0.7
    int maxTokens,         // default 1024
    Map<String, Object> extraParams
) {
    public static LlmGenerationRequest simple(String system, String user) {
        return new LlmGenerationRequest(system, user, 0.7, 1024, Map.of());
    }
}

public record LlmGenerationResponse(
    String content,
    long promptTokens,
    long completionTokens
) {}
```

**实现选择**: 优先 `OpenAiCompatibleProvider`，因为公司内部 LLM 网关几乎都是 OpenAI 兼容格式。

### 3.4 StatefulAiContext 设计

```java
public class StatefulAiContext {
    private final String sessionId;
    private final Map<String, JsonNode> state;           // KV 状态
    private final List<InteractionRecord> history;       // 最近 N 次交互
    private final int maxHistory;
    private final long sessionTimeoutMs;

    public void recordInteraction(String method, String path,
        JsonNode request, JsonNode response, int status) { ... }

    public void set(String key, JsonNode value) { ... }
    public Optional<JsonNode> get(String key) { ... }
    public JsonNode remove(String key) { ... }

    /** 构建注入 LLM prompt 的上下文摘要 */
    public String buildContextSummary() {
        StringBuilder sb = new StringBuilder("# Session Context\n");
        if (!state.isEmpty()) {
            sb.append("## State\n");
            state.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        }
        if (!history.isEmpty()) {
            sb.append("## Recent Interactions\n");
            history.reversed().stream().limit(5).forEach(ir ->
                sb.append("- ").append(ir.method()).append(" ").append(ir.path())
                  .append(" (status ").append(ir.status()).append(")\n"));
        }
        return sb.toString();
    }
}
```

### 3.5 BehaviorModel 设计

```java
public class BehaviorModel {
    private final LlmProvider llm;
    private final LlmResponseCache cache;
    private final BehaviorRules rules;
    private final double temperature;
    private final int maxTokens;

    public JsonNode generateResponse(
        String method, String path,
        JsonNode requestBody,
        StatefulAiContext context) throws LlmException {

        // 1. 缓存检查
        String cacheKey = CacheKeys.of(method, path, requestBody);
        Optional<JsonNode> cached = cache.get(cacheKey);
        if (cached.isPresent()) return cached.get();

        // 2. 一致性规则校验
        checkConsistency(method, path, context);

        // 3. 构建 prompt
        String prompt = buildPrompt(method, path, requestBody, context);

        // 4. LLM 生成
        var request = new LlmGenerationRequest(
            rules.systemPrompt(), prompt, temperature, maxTokens, Map.of());
        var response = llm.generate(request);

        // 5. 解析 JSON
        JsonNode result = parseJsonResponse(response.content());

        // 6. 缓存
        cache.put(cacheKey, result);

        return result;
    }

    private String buildPrompt(...) {
        return """
            Generate a realistic response for this API request:

            Method: %s
            Path: %s
            %s

            %s

            # Available Schemas
            %s

            Generate a realistic JSON response that:
            1. Matches the request method and path
            2. Is consistent with the session context
            3. Conforms to the relevant schema if applicable
            4. Maintains logical consistency
            """.formatted(method, path, bodySection, context.buildContextSummary(),
                          schemasSection);
    }
}
```

### 3.6 集成到 HttpStubHandler（Phase 2 增强版）

在 Phase 1 基础上增加降级逻辑：

```java
FullHttpRequest → MatchEngine.match()
    ├→ 概率挡板命中 → Phase 1
    ├→ 规则命中 → 现有逻辑
    └→ 未命中
        ├→ LLM enabled?
        │   ├→ Yes → behaviorModel.generateResponse()
        │   │         → 录制到 storage（可选）
        │   └→ No → passthrough / default response
```

### 3.7 成本控制

| 措施 | 说明 |
|------|------|
| **缓存 TTL** | 默认 5 分钟，可配置 |
| **Session 超时** | 默认 1 小时，超时自动清理 |
| **token 上限** | 单次请求默认 1024 tokens，可配置 |
| **限流** | 每秒最多 N 次 LLM 调用，超出排队/拒绝 |
| **全局开关** | `baafoo.ai.enabled=false` 完全关闭 |
| **按规则开关** | 每条规则可独立启用/禁用 AI |

---

## 4. Phase 3: 跨协议一致性

**依赖**: Phase 2 的 `StatefulAiContext` 机制。

**目标**: 同一个 session 内，HTTP 挡板 + Kafka/Pulsar 挡板之间数据保持逻辑一致性。

### 4.1 场景

```
1. POST /api/orders {"amount": 100} 
   → HTTP 挡板返回 {"orderId": "ord_99", "status": "paid"}
   → context.set("order:ord_99:status", "paid")

2. Kafka producer 发送 PaymentEvent { orderId: "ord_99", amount: 100 }
   → Kafka 挡板读取 context.get("order:ord_99:status")
   → 如果 status=="paid"，返回正常 ACK
   → 如果 context 中不存在，LLM 推断合理行为

3. Kafka consumer 拉取 OrderStatusTopic
   → 挡板根据 context 中该 order 的当前状态生成对应事件
```

### 4.2 所需改动

| 组件 | 改动 |
|------|------|
| `KafkaProtocolDecoder` | 注入 `StatefulAiContext`，Produce 处理前读取 context |
| `PulsarMockBrokerHandler` | 同上 |
| `JmsMockBroker` | 同上 |
| `SessionManager` | 增加协议间 session 共享（HTTP sessionId ↔ Kafka clientId 映射） |

**投入产出评估**: 这个阶段对实际测试价值大，但实现复杂度高（协议间 session 关联需要额外标识机制）。建议 Phase 2 落地后观察 1-2 个月再决定是否推进。

---

## 5. 风险与约束

### 5.1 技术风险

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| LLM 生成延迟影响挡板性能 | 🔴 高 | 缓存 + 异步预生成 + 超时降级到固定响应 |
| LLM 输出非 JSON | 🟡 中 | 提取 `{...}` + 兜底包装 |
| JSON 不符合 schema | 🟡 中 | 生成后 schema 校验，不通过则重试 1 次 |
| LLM API 不可用 | 🟡 中 | 自动降级到 Phase 1 概率挡板或 passthrough |
| 行为克隆模型过时 | 🟢 低 | 定时重新分析（`CloningApiHandler.analyze(ruleId)`） |

### 5.2 隐私与合规

- LLM prompt 中会包含请求路径和 request body **片段**。需要评估：
  - 请求数据中是否包含 PII（手机号、身份证等）？
  - 公司内部 LLM 网关是否有数据脱敏策略？
- **建议**: `BehaviorModel` 的 prompt 构建时增加 `DataSanitizer.filter()`，对常见 PII 字段做掩码处理。

### 5.3 测试策略

| 模块 | 测试类型 | 覆盖目标 |
|------|---------|---------|
| `EndpointProbabilityModel` | 单元测试 | 100% 方法覆盖 |
| `ProbabilitySampler` | 单元测试 | 100% 分支覆盖（seeded random） |
| `SequenceLearner` | 单元测试 | 已知序列输入 → 验证输出 |
| `LlmProvider` | 集成测试 | mock HTTP server 替代真实 API |
| `BehaviorModel` | 集成测试 | 含缓存命中/未命中/一致性校验 |
| `HttpStubHandler` (cloning) | 集成测试 | 端到端：录制 → 建模 → 采样 → 回放 |

---

## 6. 投入估算

### Phase 1: 行为克隆

| 任务 | 工作量 | 说明 |
|------|--------|------|
| 数据模型类 (7 个) | 0.5 人天 | 直接翻译 mockforge types.rs |
| `ProbabilisticModel` + `ProbabilitySampler` + `LatencySampler` | 1 人天 | 核心算法 |
| `SequenceLearner` + FrequentSequenceMiner | 1.5 人天 | 序列挖掘 |
| `EdgeAmplifier` | 0.5 人天 | 边缘放大 |
| `CloningModelStore` + File 实现 | 0.5 人天 | 模型持久化 |
| `CloningApiHandler` | 0.5 人天 | REST API |
| `CloningStubHandler` 集成 | 1 人天 | 装饰 HttpStubHandler |
| 测试用例 | 2 人天 | 单元 + 集成 |
| **Phase 1 合计** | **7.5 人天** | |

### Phase 2: LLM 增强

| 任务 | 工作量 | 说明 |
|------|--------|------|
| `LlmProvider` 接口 + 4 个实现 | 1 人天 | OkHttpClient 直连 |
| `StatefulAiContext` + `SessionManager` | 1 人天 | |
| `BehaviorModel` + `BehaviorRules` + prompt 构建 | 2 人天 | |
| `LlmResponseCache` | 0.5 人天 | |
| `AiConfigApiHandler` | 0.5 人天 | |
| HttpStubHandler 集成（降级链路） | 1 人天 | |
| 测试用例 | 2 人天 | |
| **Phase 2 合计** | **8 人天** | |

### Phase 3: 跨协议一致性

| 任务 | 工作量 | 说明 |
|------|--------|------|
| Kafka 集成 | 1 人天 | |
| Pulsar + JMS 集成 | 1 人天 | |
| 协议间 session 映射 | 1.5 人天 | |
| 测试用例 | 1.5 人天 | |
| **Phase 3 合计** | **5 人天** | |

### 总估算

| Phase | 人天 | 里程碑 |
|-------|------|--------|
| Phase 1 | 7.5 | 录制 → 自动化概率挡板 |
| Phase 2 | 8 | 智能 LLM 挡板 + 降级链路 |
| Phase 3 | 5 | 跨协议一致性 |
| **总计** | **20.5** | |

---

## 附录 A: 与 mockforge 的数据模型对照

### A.1 端点概率模型

| mockforge (Rust) | Baafoo (Java) | 备注 |
|---|---|---|
| `EndpointProbabilityModel.endpoint` | `String endpoint` | 1:1 |
| `EndpointProbabilityModel.method` | `String method` | 1:1 |
| `status_code_distribution: HashMap<u16, f64>` | `Map<Integer, Double>` | 类型映射 |
| `latency_distribution: LatencyDistribution` | `LatencyDistribution record(...)` | record 更简洁 |
| `error_patterns: Vec<ErrorPattern>` | `List<ErrorPattern>` | |
| `payload_variations: Vec<PayloadVariation>` | `List<PayloadVariation>` | |
| `sample_count: u64` | `long sampleCount` | |
| `updated_at: DateTime<Utc>` | `Instant updatedAt` | |

### A.2 LLM Provider

| mockforge (Rust) | Baafoo (Java) |
|---|---|
| `trait LlmProvider` | `interface LlmProvider` |
| `async fn generate_chat() -> Result<String>` | `LlmGenerationResponse generate(...)` (同步或 CompletableFuture) |
| `struct OpenAIProvider` | `class OpenAiProvider implements LlmProvider` |
| `reqwest::Client` | `okhttp3.OkHttpClient` |
| `serde_json::json!({})` | `new ObjectMapper().createObjectNode()` |

---

## 附录 B: 构建与依赖

### B.1 Phase 1 新增 Maven 依赖

无。所有依赖均可在 JDK 标准库中找到（`java.util.stream`、`java.time`、`com.fasterxml.jackson` 已存在）。

### B.2 Phase 2 新增 Maven 依赖

```xml
<!-- OkHttp for HTTP client (LLM API calls) -->
<!-- 注意: Baafoo 可能已通过其他依赖传递引入，检查后按需显式声明 -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
    <version>4.12.0</version>
</dependency>
```

如果 Baafoo 已经依赖 Netty (io.netty) 并且想复用其 HTTP client，可以不用 OkHttp，用 `io.netty.handler.codec.http.HttpClientCodec` 代替。

### B.3 配置项

```yaml
# Phase 1
baafoo:
  cloning:
    enabled: false
    model-dir: .baafoo/models
    min-sample-count: 50          # 最少录制数，低于此值不建模
    auto-rebuild-interval: 1h     # 自动重建模型间隔

# Phase 2
baafoo:
  ai:
    enabled: false
    provider: openai-compatible    # openai / anthropic / ollama / openai-compatible
    model: gpt-4o-mini
    endpoint: https://internal-llm-gateway.company.com/v1/chat/completions
    api-key: ${BAFOO_AI_API_KEY}  # 从环境变量读取
    temperature: 0.7
    max-tokens: 1024
    cache:
      ttl-seconds: 300
      enabled: true
    session:
      timeout-seconds: 3600
      max-history: 50
    rate-limit:
      max-per-second: 10
    data-sanitization:
      enabled: true
      masked-fields: [phone, id_card, password, token, secret]
```
