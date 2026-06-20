# mockforge AI 集成功能分析

> 分析日期: 2026-06-20
> 分析范围: mockforge 项目中与 AI 集成的所有 crate 和模块
> 生成: 基于 mockforge 源码直接阅读

---

## 1. 项目 AI 模块总览

### 1.1 Crate 层级

```
mockforge-ai-core (独立 crate)
├── LlmProvider trait + 4 实现: OpenAI, Anthropic, Ollama, OpenAI-compatible
├── StatefulAiContext: 跨请求 session 状态管理
├── BehaviorModel: LLM 驱动的 mock 响应决策
├── VectorMemoryStore: embedding 向量语义搜索
├── ConsistencyRules + StateMachine: 一致性规则引擎
├── MutationAnalyzer: schema 突变检测
├── PaginationIntelligence: 上下文感知分页生成
├── RuleGenerator: 从示例自动推断规则
└── ValidationGenerator: AI 驱动校验错误生成

mockforge-intelligence (聚合 crate, 依赖 ai-core)
├── intelligent_behavior/  (子模块集群)
│   ├── mockai.rs: MockAI 统一接口
│   ├── behavior.rs: BehaviorModel
│   ├── config.rs: IntelligentBehaviorConfig + PersonasConfig
│   ├── context.rs: StatefulAiContext
│   ├── llm_client.rs: LlmClient 封装层
│   ├── session.rs: SessionManager
│   ├── rules.rs: ConsistencyRule + StateMachine
│   ├── memory.rs: VectorMemoryStore
│   ├── history.rs: HistoryManager
│   ├── cache.rs: 响应缓存
│   ├── mutation_analyzer.rs
│   ├── pagination_intelligence.rs
│   ├── rule_generator.rs
│   ├── validation_generator.rs
│   ├── openapi_generator.rs
│   ├── spec_suggestion.rs
│   ├── relationship_inference.rs
│   ├── sub_scenario.rs
│   └── visual_layout.rs
├── ai_studio/          (管理面 AI: NL mock 生成、调试、预算)
├── behavioral_cloning/ (重导出)
├── threat_modeling/    (安全分析)
├── failure_analysis/   (失败上下文生成)
├── consistency/        (跨协议一致性引擎)
├── ai_contract_diff/   (OpenAPI diff 语义分析)
├── contract_validation/ (契约校验)
├── behavioral_economics/ (行为经济规则引擎)
├── incidents/          (事件管理)
├── reality/            (真实性级别)
├── fidelity/           (mock 质量评分)
├── deceptive_canary/   (诱饵端点)
├── database/           (PG 连接池)
├── ai_handler/         (HTTP handler 集成)
├── handlers/           (HTTP handlers)
├── pr_generation/      (GitHub/GitLab PR 生成)
├── voice/              (语音)
└── rag_ai_generator/   (RAG 增强生成)

mockforge-behavioral-cloning (独立 crate, 不依赖 LLM)
├── probabilistic_model.rs: 概率状态码 + 延迟分布
├── sequence_learner.rs: 多步流程发现
├── edge_amplifier.rs: 稀有错误放大
├── types.rs: 数据模型
└── error.rs: 错误类型
```

### 1.2 模块依赖关系

```
behavioral-cloning  ←  无外部依赖，纯统计
       ↑
ai-core  ←  foundation + openapi + data
       ↑
intelligence  ←  ai-core + behavioral-cloning + openapi + data + foundation
       ↑
  各种 handler / middleware
```

---

## 2. 核心模块深度分析

### 2.1 LlmClient — 多供应商抽象

**文件**: `mockforge-ai-core/src/llm_client.rs` / `intelligence/src/intelligent_behavior/llm_client.rs`

```
LlmProvider trait:
  ├── generate_chat(system_prompt, user_prompt, temperature, max_tokens) → Result<String>
  ├── generate_chat_with_history(messages) → Result<String>
  └── provider_name() → &str

具体实现:
  ├── OpenAIProvider     → api.openai.com/v1/chat/completions, Bearer token
  ├── AnthropicProvider  → api.anthropic.com/v1/messages, x-api-key
  ├── OllamaProvider     → localhost:11434/api/chat, 本地部署
  └── OpenAICompatibleProvider → 通用兼容端点, Bearer token

LlmClient (封装层):
  ├── generate_json<T>()      → 自动 JSON 解析 + 兜底
  ├── generate_json_raw()     → 原始 JsonValue
  ├── generate_text()         → 纯文本
  └── usage_stats()           → LlmUsage { prompt_tokens, completion_tokens }
```

**设计亮点**:
- Provider trait 接口极简，新增提供商只需 50 行代码
- `generate_json` 对非 JSON 输出做启发式提取（找 `{...}` 块），失败则包装为 `{"response": "原始文本"}`
- 内置 token 用量统计，传递给 budget 模块

### 2.2 StatefulAiContext — 跨请求状态

**文件**: `intelligence/src/intelligent_behavior/context.rs`

```rust
StatefulAiContext {
    session_id: String,
    state: HashMap<String, Value>,       // KV 状态存储
    interactions: Vec<InteractionRecord>, // 交互历史
    behavior_model: BehaviorModel,
    vector_store: Option<VectorMemoryStore>,
    session_started: DateTime<Utc>,
}
```

**关键方法**:
- `record_interaction(method, path, request, response)` → 追加交互记录 + 更新状态
- `get_state()` → 返回 JSON 摘要（状态 + 最近交互 + 统计）
- `build_context_summary()` → 构建注入 LLM prompt 的上下文文本

**状态示例** (电商场景):
```json
{
  "state": {
    "current_user": {"id": "u_123", "name": "张三"},
    "cart": {"id": "cart_456", "items": 2, "total": 99.50},
    "last_order": {"id": "ord_789", "status": "paid"}
  },
  "recent_interactions": [
    "POST /api/users → 201 (created u_123)",
    "POST /api/cart → 201 (cart_456)",
    "POST /api/orders → 201 (ord_789)"
  ]
}
```

### 2.3 BehaviorModel — LLM 响应决策核心

**文件**: `intelligence/src/intelligent_behavior/behavior.rs`

**处理管线**:
```
generate_response(method, path, body, context)
    1. 缓存检查 (method+path+body → TTL 300s)
    2. 一致性规则校验 (check_consistency_rules)
    3. 构建 prompt
       - System: 角色 + 可用 schema + 一致性规则
       - User: 当前请求 + 上下文摘要
    4. LLM 生成 (temperature=0.7, max_tokens=1024 default)
    5. JSON 解析 + 缓存写入
```

**Prompt 构建细节**:
```text
System: You are a realistic API mock server...
Available schemas: [User, Order, Cart, ...]
Consistency rules:
  - POST creates a resource; GET returns the created resource
  - DELETE makes GET return 404
  - Auth endpoints require valid tokens

User:
  Current request: POST /api/orders {"userId": "u_123", "items": [... ]}
  Session context:
    - State: current_user.id = u_123
    - Created cart_456 with 2 items
    - 90% of POST /api/orders return 201, 10% return 422
  
  Generate a realistic JSON response.
```

### 2.4 一致性规则引擎

**文件**: `intelligence/src/intelligent_behavior/rules.rs`

```rust
ConsistencyRule {
    name: String,
    priority: u32,
    condition: ConditionExpr,
    action: RuleAction,
}

RuleAction {
    RequireAuth,             // 检查 auth_token 或 user_id
    EnforceSchema(String),   // 强制指定 schema
    Block,                   // 阻止响应生成
    Log,                     // 仅记录
    Custom(Value),           // 自定义动作
}

StateMachine {
    resource_type: String,
    states: Vec<String>,
    transitions: Vec<StateTransition>,
    current_state: String,
}
```

**设计问题** — `check_consistency_rules` 中创建了 `EvaluationContext`，但未传递给 `rule.matches()` 调用，实际上规则检查可能无效。这是一个 **已知缺陷**。

### 2.5 缓存层

**文件**: `intelligence/src/intelligent_behavior/cache.rs`

- 缓存键: `hash(method + path + request_body)`
- TTL: 默认 300 秒
- 默认启用

**配置不一致问题**: `PerformanceConfig` 中定义了 `enable_response_cache` 和 `cache_ttl_seconds`，但 `BehaviorModel` 使用自己的硬编码缓存。配置值未被实际使用。

### 2.6 MockAI 统一接口

**文件**: `intelligence/src/intelligent_behavior/mockai.rs`

```rust
MockAI {
    rules: BehaviorRules,
    rule_generator: RuleGenerator,
    mutation_analyzer: MutationAnalyzer,
    validation_generator: ValidationGenerator,
    pagination_intelligence: PaginationIntelligence,
    config: IntelligentBehaviorConfig,
    session_contexts: Arc<RwLock<HashMap<String, StatefulAiContext>>>,
}
```

**自动学习管线**:
1. `from_openapi(spec)` → 从 OpenAPI 提取示例 → `RuleGenerator` 生成规则
2. `from_examples(examples)` → 从已有请求/响应对学习规则
3. `process_request(request)` → 路由到合适的 handler，包含：
   - 规则匹配
   - 状态管理 (session)
   - Mutation 检测
   - 分页生成
   - 校验生成

### 2.7 Persona 系统

**文件**: `intelligence/src/intelligent_behavior/config.rs`

```rust
PersonasConfig {
    personas: Vec<Persona>,       // Persona 列表
    active_persona: Option<String>,
}

Persona {
    name: String,
    traits: HashMap<String, String>,  // 键值对，支持范围值 "20-40"
}

// get_active_persona(): 优先 active_persona，否则第一个
// parse_range("20-40") → 30.0 (中点)
```

**用途**: 生成符合特定用户画像的测试数据，如 "premium_user" 的 traits 包含 `{"age": "30-50", "spending": "high", "region": "CN"}`。

### 2.8 向量内存 (VectorMemoryStore)

- 基于 embedding 的语义搜索
- 存储过去的交互记录
- 支持 similarity search (默认 limit=10, threshold=0.7)
- 默认禁用

### 2.9 PaginationIntelligence

- 上下文感知分页生成
- 支持 cursor-based / page-based / offset-based 格式
- 自动推断分页策略
- 生成前后一致的下一页/上一页链接

### 2.10 行为克隆 (behavioral-cloning crate)

**完全独立于 LLM**，基于纯统计。

**ProbabilisticModel**:
```rust
// 从录制数据构建
build_probability_model_from_data(
    endpoint, method,
    status_codes: &[u16],     // 实际状态码序列
    latencies_ms: &[u64],     // 实际延迟序列
    error_responses,          // 错误响应体
    request_payloads,         // 请求体样本
    response_payloads,        // 响应体样本
) → EndpointProbabilityModel

// 状态码分布: HashMap<u16, f64>  (频率归一化)
// 延迟分布: p50/p95/p99/mean/std_dev/min/max
// 错误模式: 按 statusCode 分组 + 样本响应
```

**SequenceLearner**:
```rust
// 从录制流量发现多步序列
discover_sequences_from_traces(
    database: &TraceQueryProvider,
    min_support: f64,          // 最小支持度 0.1 = 10%
    max_sequence_length: Option<usize>,
) → Vec<BehavioralSequence>

// 每个 BehavioralSequence 包含:
// - steps: Vec<SequenceStep> (有序)
// - frequency: 序列出现频率
// - confidence: 置信度
// - learned_from: 贡献的请求 ID 列表
```

**EdgeAmplifier**:
```rust
// 放大稀有错误概率
EdgeAmplificationConfig {
    enabled: bool,
    amplification_factor: f64,   // 0.5 = 原来 1% → 50%
    scope: Global / Endpoint / Sequence,
    target_patterns: Option<Vec<String>>,
    rare_threshold: f64,          // 低于此值的模式视为稀有
}
```

---

## 3. 管理面 AI 功能

### 3.1 AI Studio

管理面的 AI 功能集合：

| 功能 | 文件 | 描述 |
|------|------|------|
| ChatOrchestrator | `ai_studio/chat_orchestrator.rs` | 统一 AI 对话编排 |
| NlMockGenerator | `ai_studio/nl_mock_generator.rs` | 自然语言 → mock 配置 |
| DebugAnalyzer | `ai_studio/debug_analyzer.rs` | AI 调试测试失败 |
| PersonaGenerator | `ai_studio/persona_generator.rs` | 生成/调整 Persona |
| ArtifactFreezer | `ai_studio/artifact_freezer.rs` | AI 输出 → 确定性 YAML/JSON |
| BudgetManager | `ai_studio/budget_manager.rs` | Token 用量 + 预算管理 |
| BehavioralSimulator | `ai_studio/behavioral_simulator.rs` | 行为模拟 |
| ConversationStore | `ai_studio/conversation_store.rs` | 对话持久化 |
| ContractDiffHandler | `ai_studio/contract_diff_handler.rs` | 契约差异分析 |
| ApiCritique | `ai_studio/api_critique.rs` | API 设计 critique |
| SystemGenerator | `ai_studio/system_generator.rs` | 系统级 mock 生成 |

### 3.2 安全/质量类

| 功能 | 描述 |
|------|------|
| ThreatModeling | LLM 驱动的安全分析 (DoS/PII/Schema/Error) |
| FailureAnalysis | 失败上下文 + 叙事生成 |
| AiContractDiff | OpenAPI diff + 语义分析 + 置信度 |
| ContractValidation | 响应 vs Schema 契约校验 |
| Fidelity | Mock 质量评分 |

### 3.3 业务场景类

| 功能 | 描述 |
|------|------|
| BehavioralEconomics | 声明式规则引擎：压力/负载/定价/欺诈/客户分群 |
| ScenarioStudio | 可视化业务流编辑器 |
| DeceptiveCanary | 诱饵端点配置 |
| RelationshipInference | 端点间关系推断 |
| SpecSuggestion | Schema 改进建议 |
| SubScenario | 子场景管理 |
| VisualLayout | 可视化布局 |

---

## 4. 架构设计评估

### 4.1 优点

1. **清晰的抽象分层**
   - `LlmProvider` trait → 多实现 → 懒初始化，标准策略模式
   - `ai-core` → `intelligence` → `handlers` 三层分离，职责明确

2. **渐进式集成设计**
   - 每个 AI 功能都有独立的 `enabled` 开关
   - 缓存 / session / LLM 均可单独启用/禁用
   - 默认 `enabled: false`，需要时显式开启

3. **完整的 AI 生成管线**
   - 请求 → 缓存检查 → 规则校验 → Prompt 构建 → LLM 生成 → JSON 解析 → 缓存写入
   - 每一步都有降级/兜底逻辑

4. **行为克隆与 LLM 互补**
   - 行为克隆：低成本、低延迟、确定性
   - LLM：高灵活度、上下文感知、零配置
   - 组合使用：行为克隆优先，LLM 降级

### 4.2 缺陷

1. **配置不一致**
   - `PerformanceConfig.cache_ttl_seconds` 定义了但 `BehaviorModel` 不用
   - `EvaluationContext` 创建了但 `rule.matches()` 没用到

2. **硬编码**
   - 缓存 TTL 硬编码 300s
   - temperature/max_tokens 有默认值但散落在多处

3. **代码膨胀**
   - `ai_studio` 模块集成了大量功能（15+ 子模块），建议拆分
   - `intelligence/src/intelligent_behavior/` 子模块过多（15+），应考虑进一步 crate 化

4. **文档覆盖不均衡**
   - `behavioral-cloning` 文档优秀
   - `behavioral_economics` / `scenario_studio` 缺少使用示例

---

## 5. 对 Baafoo 的借鉴价值 (按优先级排序)

| 优先级 | 模块 | 借鉴价值 | 理由 |
|--------|------|---------|------|
| ⭐⭐⭐ | behavioral-cloning (ProbabilisticModel + SequenceLearner) | 直接可移植 | 纯统计，零外部依赖，Baafoo 已有录制数据 |
| ⭐⭐⭐ | LlmProvider 接口 + 4 实现 | 接口设计范式 | trait → interface 直译，公司内部 LLM 均为 OpenAI 兼容 |
| ⭐⭐⭐ | StatefulAiContext | 核心概念移植 | 升级挡板从"无状态"到"有状态" |
| ⭐⭐⭐ | 缓存层 (method+path+body TTL) | 降本必备 | 减少 LLM 调用量 |
| ⭐⭐ | BehaviorModel (prompt 构建 + 一致性) | 可参考但需适配 | Java 实现复杂度高于 Rust |
| ⭐⭐ | ConsistencyRules + StateMachine | 可参考 | 业务侧校验，优先级低于行为克隆 |
| ⭐ | AI Studio / ThreatModeling / 等管理面功能 | 暂时不需要 | 投入产出比低 |
