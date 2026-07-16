# Baafoo 功能扩展需求文档

> **文档版本**: v1.1
> **创建日期**: 2026-06-13 | **修订日期**: 2026-06-13
> **覆盖功能**: OpenAPI 导入、Faker 动态数据、有状态 Mock、故障注入、GraphQL 支持、Chaos 工程
> **关联文档**: [Baafoo PRD v2.3](./baafoo-prd.md)、[竞品分析报告 v2.0](./competitive-analysis.md)、[功能扩展 Review](./../../../5_review/baafoo-prd-review-20260613.md)
> **版本说明**: v1.1 根据开发同学代可行的 Review 修订，核心变更：(1) Faker 改为增量补充标注已实现；(2) 有状态 Mock 改为轻量方案；(3) GraphQL 改为 MatchCondition 扩展；(4) 多个过度设计项降级或砍掉

---

## 1. OpenAPI/Swagger 规范导入 → 自动生成规则

### 1.1 需求概述

| 属性 | 内容 |
|---|---|
| **描述** | 支持导入 OpenAPI 3.0 规范文件（JSON/YAML），自动生成对应的 HTTP 挡板规则骨架，包括路径、方法、参数化条件、默认响应等，并自动集成 `environments` 字段。消除手动编写规则的门槛，对齐 MockForge 的核心能力。 |
| **优先级** | **P0** |
| **依赖** | 需引入 SnakeYAML（`org.yaml:snakeyaml`）用于 YAML 解析；需 Swagger Parser 或自研解析逻辑用于 OpenAPI 规范解析（Java 8 兼容） |
| **影响模块** | R-S7（规则管理 API）、R-W2（规则管理界面）、R-C2（规则 Schema） |

### 1.2 用户故事

**US-19: OpenAPI 规范导入自动生成规则**
> 作为 **后端开发人员**，我希望通过 Web 控制台或 CLI 导入 OpenAPI/Swagger 规范文件，系统自动生成对应的挡板规则骨架（路径、方法、参数匹配条件、默认响应），以便我无需从零编写规则，只需针对特定场景微调响应内容。

### 1.3 需求明细

#### R-S10: OpenAPI 规范导入 API - P0

| 属性 | 内容 |
|---|---|
| **描述** | 提供 REST API 上传 OpenAPI 规范文件，解析后自动生成 Baafoo 规则并返回预览。`environments` 默认空列表。 |
| **AC-01** | `POST /api/rules/import-openapi` - 上传 OpenAPI 规范文件（multipart/form-data 或 JSON body），Server 解析后返回规则预览列表（路径、方法、默认响应状态码） |
| **AC-02** | 导入时通过 Query 参数 `?environment=ft-1,ft-2` 指定默认关联环境列表；未指定时 `environments` 默认为 `[]` |
| **AC-03** | 支持 OpenAPI 3.0（YAML/JSON）格式；解析失败时返回明确错误信息（行号、错误原因）。**Swagger 2.0 支持为 P2**（2026 年使用率极低） |
| **AC-04** | 自动生成规则包含：`method` + `path`（路径参数 `{id}` 转为 `regex` 类型 MatchCondition，如 `type: path, operator: regex, value: /api/users/[^/]+`）、默认 `response.status`（提取规范中 `responses` 的首个成功状态码）。响应 Body 生成策略：v1.0 仅处理 `type: object` + 简单属性类型，`$ref`/`allOf`/`oneOf`/`anyOf`/递归类型放 v1.5；值为类型对应的占位符 |
| **AC-05** | 冲突判定以 `path + method` 为**稳定键**（不是 ruleId），因为 OpenAPI 规范迭代时 path+method 才是稳定标识。两次导入同一规范时，同 path+method 的规则覆盖更新 |
| **AC-06** | 导入完成后返回统计：成功生成数、跳过数（无 `responses` 定义的路径）、冲突数（与已有规则 path+method 重复） |

**分阶段策略**:
- Phase 1（P0）：OpenAPI 3.0 JSON only，`minimal` + `example-first` 策略，不支持 `$ref` 解析
- Phase 2（P1）：YAML 支持、`schema-first` 策略、`$ref` 解析
- Phase 3（P2）：Swagger 2.0 支持

#### R-W8: OpenAPI 导入界面 - P0

| 属性 | 内容 |
|---|---|
| **描述** | Web 控制台新增"导入 OpenAPI"向导弹窗，支持文件上传、环境选择、预览编辑、确认导入。 |
| **AC-01** | 规则列表页新增"导入 OpenAPI"按钮，点击弹出向导弹窗 |
| **AC-02** | 第一步：上传规范文件（拖拽/选择），支持 `.json`（Phase 1）/`.yaml`/`.yml`（Phase 2）；实时显示解析进度和错误提示 |
| **AC-03** | 第二步：预览生成的规则列表（路径、方法、默认响应状态码、Body 预览），支持勾选/取消勾选单条规则 |
| **AC-04** | 第三步：选择默认关联环境（多选框），支持"不关联任何环境"（默认） |
| **AC-05** | 第四步：确认导入后显示结果统计（成功/失败/冲突数量），失败项可展开查看详细错误 |
| **AC-06** | 导入完成后自动跳转规则列表页，高亮新导入规则（背景色闪烁 3 秒） |

#### R-C3: OpenAPI 导入配置项 - P0

| 属性 | 内容 |
|---|---|
| **描述** | Server 配置新增 OpenAPI 导入相关配置项。 |
| **AC-01** | `openapi.import.default-environment`（默认 `[]`）：导入时默认关联的环境列表 |
| **AC-02** | `openapi.import.response-generation.strategy`（默认 `example-first`）：`example-first` | `schema-first`（Phase 2） | `minimal` |
| **AC-03** | `openapi.import.rule-id-prefix`（默认 `openapi-`）：自动生成规则 ID 前缀 |

---

## 2. Faker 动态数据函数（增量补充）

### 2.1 需求概述

| 属性 | 内容 |
|---|---|
| **描述** | **Faker 核心功能已在 v2.3 中实现**（`FakerProvider.java` 30+ 函数、`TemplateEngine.java` 模板渲染、`RuleEditorPage.vue` 参考面板）。本文档为**增量补充**——补齐缺失函数、统一语法、新增 seed 支持、评估 javafaker 重构。 |
| **优先级** | **P0** |
| **依赖** | 无（如需 javafaker 重构，需引入 `com.github.javafaker:javafaker` 及其传递依赖） |
| **影响模块** | R-S2（HTTP Mock Handler）、R-C2（规则 Schema）、R-W2（规则管理界面） |

### 2.2 用户故事

**US-20: Faker 动态测试数据生成**
> 作为 **后端开发人员**，我希望在配置规则响应 Body 时使用 `{{faker.phone}}`/`{{faker.email}}` 等函数，让每次 Mock 响应都返回不同的逼真测试数据，以便我的前端页面或单元测试能覆盖更多数据变化场景，而无需手动准备大量测试数据。

### 2.3 增量需求明细

#### R-S2 AC-11 增量: Faker 函数补充 - P0

| 属性 | 内容 |
|---|---|
| **描述** | 在已实现的 Faker 基础上补充缺失函数、统一语法、新增 seed 支持。 |
| **AC-01** | **已实现**的基础函数（无需改动）：`{{faker.phone}}`、`{{faker.email}}`、`{{faker.name}}`、`{{faker.idCard}}`、`{{faker.address}}`、`{{faker.company}}`、`{{faker.url}}`、`{{faker.ip}}`、`{{faker.uuid}}`、`{{faker.timestamp}}` |
| **AC-02** | **已实现的额外函数**（需补充到文档中）：`{{faker.firstName}}`、`{{faker.lastName}}`、`{{faker.city}}`、`{{faker.province}}`、`{{faker.zipCode}}`、`{{faker.ipv6}}`、`{{faker.mac}}`、`{{faker.date}}`、`{{faker.dateTime}}`、`{{faker.hex}}`、`{{faker.alphaNumeric}}`、`{{faker.userAgent}}`、`{{faker.color}}` |
| **AC-03** | **语法统一**：数值函数统一为现有语法 `{{faker.int.min.max}}`（而非 PRD v1.0 写的 `{{faker.randomInt min max}}`），`{{faker.boolean}}` 保持现有语法 |
| **AC-04** | **新增** `{{faker.randomElement [a,b,c]}}`：从数组随机选一个元素（当前未实现） |
| **AC-05** | **新增** `{{faker.regexify '[A-Z]{3}[0-9]{4}'}}`：按正则表达式生成字符串（当前未实现） |
| **AC-06** | **新增规则级 seed**：规则 Schema 新增 `response.fakerSeed`（可选整数），设置后该规则所有 Faker 函数使用相同种子（`FakerProvider` 当前使用 `SecureRandom`，需改为支持 seed 的 `java.util.Random`）。**不实现模板内联 `{{faker.seed}}`**，seed 是规则级语义，与模板内联语义矛盾 |
| **AC-07** | Faker 函数与模板变量组合已实现（`TemplateEngine` 逐变量解析），无需改动 |

**明确不实现**（Review 已指出为过度设计）:
- ❌ 自动类型推断（AC-04 原文）：不可控副作用（字段名 `name` 无法区分人名/文件名），架构上需 JSON AST 解析，当前 `TemplateEngine` 为纯文本替换，差异过大
- ❌ 模板内联 `{{faker.seed}}`：与规则级 seed 语义矛盾

#### R-W2 扩展: Faker 函数自动补全 - P0

| 属性 | 内容 |
|---|---|
| **描述** | Web 控制台编辑器已有 Faker 参考面板（`RuleEditorPage.vue`），在此基础上新增自动补全。 |
| **AC-01** | 响应 Body 编辑器中输入 `{{faker.` 时，弹出自动补全列表（显示所有可用 Faker 函数及示例） |
| **AC-02** | 规则编辑页新增"预览响应"按钮，点击后生成 3 条示例响应（含 Faker 函数动态数据），以 JSON 格式展示 |

#### R-C2 扩展: Faker 配置项 - P0

| 属性 | 内容 |
|---|---|
| **描述** | 规则 Schema 新增 `response.fakerSeed`。 |
| **AC-01** | 规则级别：`response.fakerSeed`（可选整数），设置后该规则所有 Faker 函数使用相同种子 |
| **AC-02** | Server 级别（Phase 2）：`faker.locale`（默认 `zh-CN`），控制生成数据的本地化。当前 `FakerProvider` 硬编码中文数据，支持 locale 需要**引入 javafaker 依赖**重构 |

### 2.4 Faker 技术方案评估

| 方案 | 描述 | 工作量 | 风险 |
|------|------|--------|------|
| **方案 A（推荐，P0）** | 在现有 `FakerProvider` 上增量：补 `randomElement` + `regexify` + seed 支持；语法已一致，无架构改动 | 1-2 周 | 低 |
| **方案 B（P1）** | 引入 `com.github.javafaker:javafaker` 依赖，替换 `FakerProvider` 实现，支持 60+ locale。注意传递依赖（`iban4j`、`commons-lang3`）需评估依赖树膨胀 | 2-3 周 | 中（依赖冲突） |

**建议**：Phase 1 走方案 A 快速交付 P0 增量，Phase 2 评估方案 B。

---

## 3. 有状态 Mock（轻量方案）

### 3.1 需求概述

| 属性 | 内容 |
|---|---|
| **描述** | **轻量方案**：不引入通用状态机，扩展 `ResponseEntry.condition` 支持 `requestCount` 条件，通过规则内部的请求计数器实现"第 N 次请求返回不同响应"的效果。覆盖 80% 的"状态流转"场景（如订单创建→查询状态变化），实现成本仅为通用状态机方案的 1/5。 |
| **优先级** | **P1** |
| **依赖** | R-C2（规则 Schema 扩展 ResponseEntry.condition） |
| **影响模块** | R-S2（HTTP Mock Handler 新增请求计数逻辑）、R-C2（规则 Schema）、R-W2（规则编辑界面） |

### 3.2 用户故事

**US-21: 有状态 Mock 场景模拟（轻量）**
> 作为 **QA 测试工程师**，我希望配置规则时通过"第几次请求返回不同响应"来实现状态流转效果——如第一次请求返回 `{"status": "pending"}`，第二次及以后返回 `{"status": "paid"}`，以便在不依赖真实支付服务的情况下测试订单状态变化。

### 3.3 需求明细

#### R-S2 AC-13: requestCount 条件匹配 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 在 `MatchCondition` 中新增 `requestCount` 条件类型。规则内部维护 per-rule 请求计数器，每次匹配命中后递增。`ResponseEntry.condition` 支持 `requestCount` 类型的条件，决定当前次数下使用哪个响应分支。 |
| **AC-01** | `MatchCondition` 新增 `type: requestCount`，支持操作符：`equals`（等于）、`greaterThan`（大于）、`lessThan`（小于）、`range`（区间，`[1,3]` 表示第 1 到第 3 次）、`mod`（取模，如 `mod: 3, equals: 0` 表示每 3 次触发一次） |
| **AC-02** | per-rule 请求计数器：每个 `Rule` 实例维护一个 `AtomicInteger` 计数器。每次规则匹配命中后计数器原子递增（`getAndIncrement()`） |
| **AC-03** | `ResponseEntry` 评估顺序：先评估带 `condition` 的条目（含 requestCount 条件），按声明顺序；命中则返回该条响应；若无命中则走无条件默认响应 |
| **AC-04** | 计数器支持重置：通过 `POST /api/rules/{id}/reset-state` 或 `POST /api/rules/reset-all-state` 清空计数器 |
| **AC-05** | 规则 Schema 示例：

```yaml
- id: get-order
  request:
    method: GET
    path: /api/orders/{id}
  responses:
    - condition:
        type: requestCount
        operator: lessThan
        value: 3
      response:
        status: 200
        body: '{"status": "pending", "count": {{requestCount}}}'
    - response:  # 第 3 次起返回 paid
        status: 200
        body: '{"status": "paid", "count": {{requestCount}}}'
``` |

**明确不实现**（砍掉原通用状态机设计）:
- ❌ 通用状态机模型（states/transitions/events/actions）— 过度设计，降为 v3.0 评估
- ❌ `resourceIdExtractor` / per-resource 状态跟踪 — 引入 JSONPath 依赖，复杂度高
- ❌ `action.type: setVariable` 运行时变量绑定机制
- ❌ R-W9 拖拽式可视化编辑器 — ROI 太低，引入图形编辑库增加打包体积

#### R-C2 扩展: requestCount 计数器配置 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 规则级别配置 `response.requestCountReset`（可选，整数），指定请求计数器在达到该值后自动重置为 0（用于循环模式）。 |
| **AC-01** | `response.requestCountReset`（可选，整数）：达到该计数值后计数器自动重置为 0；不配置则持续递增不重置 |

---

## 4. 故障注入

### 4.1 需求概述

| 属性 | 内容 |
|---|---|
| **描述** | 在规则配置中支持故障注入，模拟网络异常、服务故障等场景。per-route 故障注入（概率、延迟分布、错误模式）。对标 MockForge Chaos Lab 的基础能力。 |
| **优先级** | **P1** |
| **依赖** | R-S2（HTTP Mock Handler）、R-S4（Kafka Mock Broker）、R-S5（Pulsar Mock Broker） |
| **影响模块** | R-S2、R-S4、R-S5、R-C2（规则 Schema） |

### 4.2 用户故事

**US-22: 故障注入模拟下游异常**
> 作为 **QA 测试工程师**，我希望在规则中配置故障注入策略（如"30% 概率返回 503 错误"、"Kafka Produce 延迟 2 秒"），以便验证我的应用是否正确处理了超时、重试、降级等容错逻辑。

### 4.3 需求明细

#### R-S12: 故障注入配置模型 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 规则 Schema 新增 `faultInjection` 配置块。`faults` 数组中的每个 fault 有独立 `probability`，按声明顺序评估，首个命中的 fault 生效；全部未命中则走正常响应。 |
| **Phase 1（P1，3 周）** | HTTP 协议：`DELAY` + `HTTP_ERROR`（固定延迟 / 状态码概率） |
| **Phase 2（P1，1 周）** | HTTP 协议：`CONNECTION_RESET` + `READ_TIMEOUT` |
| **Phase 3（P2）** | Kafka/Pulsar 协议故障注入 + 正态分布延迟 + 条件化触发 |

**AC-01** | HTTP Phase 1 支持故障类型：
  - `HTTP_ERROR`：`probability`（本 fault 的触发概率）+ `statusCodes`（状态码列表，等概率分配，如 `[503, 504]` 各 50%）。故障触发后，从列表中随机选一个状态码返回
  - `DELAY`：`probability` + `delayMs`（固定延迟毫秒数）

**AC-02** | HTTP Phase 2 支持故障类型：
  - `CONNECTION_RESET`：在写响应前关闭连接（Netty 发送 RST，需 `ctx.channel().unsafe().closeForcibly()` 或设置 `SO_LINGER=0`，**非标准操作，需明确技术方案**）
  - `READ_TIMEOUT`：收到请求后不响应，等待连接超时

**AC-03** | Kafka/Pulsar 故障注入（Phase 3）：
  - Kafka：`PRODUCE_THROTTLE`（延迟处理）、`PRODUCE_NOT_LEADER`（返回 `NOT_LEADER_OR_FOLLOWER` 错误码）、`OFFSET_OUT_OF_RANGE`。当前 `KafkaMockBroker` Produce 处理硬编码成功，需重构为从规则读取故障配置后注入错误码
  - Pulsar：`PRODUCE_THROTTLE`、`LOOKUP_FAILURE`

**AC-04** | 故障按声明顺序评估，首个 probability 命中的 fault 生效；全部未命中则走正常响应。每个 fault 的 `probability` 是独立条件概率（相对于正常请求），不是在所有 fault 中的分配概率

**AC-05** | 正态分布延迟（Phase 3）：`delayMs`（均值）+ `delayStdDevMs`（标准差），结果截断至 0（`Math.max(0, nextGaussianResult)`），避免负延迟

#### R-C2 扩展: 规则 `faultInjection` 字段 - P1

```yaml
# Phase 1 HTTP 故障注入示例
- id: get-user-with-fault
  request:
    method: GET
    path: /api/users/{id}
  faultInjection:
    faults:
      - type: HTTP_ERROR
        probability: 0.2
        statusCodes: [503, 504]
      - type: DELAY
        probability: 0.5
        delayMs: 2000
  responses:
    - response:
        status: 200
        body: '{"id": "{{path.id}}", "name": "Mock User"}'
```

**依赖清单**（Java 8 兼容）:
- JSONPath 解析：`com.jayway.jsonpath:json-path`（当前项目无此依赖，Phase 1 不需要，Phase 2 按需引入）

#### R-W2 扩展: 故障注入配置界面 - P1

| 属性 | 内容 |
|---|---|
| **描述** | Web 控制台规则编辑页新增"故障注入"配置面板。 |
| **AC-01** | 规则编辑页新增"故障注入"折叠面板，默认隐藏；勾选"启用故障注入"后展开表单 |
| **AC-02** | 故障类型下拉选择（按协议过滤）；选择后动态显示对应参数表单 |
| **AC-03** | 支持添加多条故障规则（数组），按声明顺序评估 |
| **AC-04** | 提供"故障模拟预览"按钮：发送测试请求，显示是否触发故障及触发了哪种故障 |

---

## 5. GraphQL 支持（MatchCondition 扩展）

### 5.1 需求概述

| 属性 | 内容 |
|---|---|
| **描述** | **GraphQL 请求本质上是 HTTP POST 的子集**（Body 含 `query`/`operationName`/`variables`），Agent 的 HTTP 拦截已覆盖。真正需要的是在 `MatchCondition` 中支持 GraphQL 请求 Body 的解析匹配，而非新建独立 Handler。方案：扩展 `MatchCondition` 支持 `bodyJsonPath: $.operationName`，规则编辑页新增 GraphQL 快捷创建面板。 |
| **优先级** | **P1** |
| **依赖** | 无（`extractJsonField` 已在 `TemplateEngine` 中实现，可复用） |
| **影响模块** | R-A9（MatchCondition 扩展）、R-C2（规则 Schema）、R-W2（规则编辑界面） |

### 5.2 用户故事

**US-23: GraphQL 接口挡板**
> 作为 **后端开发人员**，我的应用使用 GraphQL 接口（如 Apollo Client），我希望 Baafoo 能拦截 GraphQL 请求并返回 Mock 数据，以便我能在不依赖真实 GraphQL 服务的情况下开发前端。

### 5.3 需求明细

#### R-S2 AC-14: GraphQL 规则的 MatchCondition 扩展 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 不新建独立 GraphQL Handler。GraphQL 请求作为普通 HTTP POST 处理，但扩展 `MatchCondition` 使其能便捷匹配 GraphQL 操作的 `operationName` 和 `operationType`。**
** |
| **AC-01** | `MatchCondition` 新增 `type: graphqlOperationName`（语法糖，等价于 `bodyJsonPath: $.operationName`）：匹配请求 Body 中的 `operationName` 字段 |
| **AC-02** | `MatchCondition` 新增 `type: graphqlOperationType`（语法糖，等价于 `bodyJsonPath` 判断 `query` 字段是否匹配 `query`/`mutation`/`subscription`）：匹配操作类型 |
| **AC-03** | 规则示例（直接在 HTTP 规则下，无需独立 `graphql` 段落）：

```yaml
http:
  - id: graphql-get-user
    request:
      method: POST
      path: /graphql
      conditions:
        - type: graphqlOperationName
          operator: equals
          value: GetUser
        - type: graphqlOperationType
          operator: equals
          value: query
    responses:
      - response:
          status: 200
          headers:
            Content-Type: application/json
          body: |
            {"data": {"user": {"id": "1", "name": "Mock User", "email": "{{faker.email}}"}}}
``` |

**AC-04** | GraphQL Schema 导入：**复用 R-S10 的 OpenAPI 导入框架**，不另建独立 API。`POST /api/rules/import-graphql-schema` 为 R-S10 的扩展端点，上传 `.graphql`/`.gql` 文件后为每个 Query/Mutation 生成 HTTP 规则骨架（含自动填充的 `graphqlOperationName` 条件） |

**明确不实现**:
- ❌ 独立 GraphQL Mock Handler（PRD v1.0 的 R-S14）— GraphQL 是 HTTP POST 子集，无需独立处理
- ❌ GraphQL Subscription（WebSocket）— Agent 当前不支持 WebSocket 拦截，在 v2.0 规划中明确优先级

#### R-W2 扩展: GraphQL 规则编辑面板 - P1

| 属性 | 内容 |
|---|---|
| **描述** | 在 HTTP 规则编辑界面新增"GraphQL 快捷配置"面板。 |
| **AC-01** | 当规则 `path` 包含 `/graphql` 时，自动显示"GraphQL 配置"辅助面板 |
| **AC-02** | 辅助面板提供：`operationName` 输入框（自动生成 `graphqlOperationName` 匹配条件）、`operationType` 下拉选择（自动生成 `graphqlOperationType` 匹配条件） |
| **AC-03** | "上传 GraphQL Schema"按钮：上传后自动生成规则骨架 |

---

## 6. Chaos 工程（故障注入场景化）

### 6.1 需求概述

| 属性 | 内容 |
|---|---|
| **描述** | Chaos 工程是故障注入的场景化封装。**v2.0 不做 UI 和预置场景包**，只做 Chaos 配置文件 YAML + REST API。预置行业包的数据来源不明（"503 概率 10%"缺乏依据），放到更晚版本。 |
| **优先级** | **P2** |
| **依赖** | R-S12（故障注入配置模型） |
| **影响模块** | R-S13（Chaos 配置文件） |

### 6.2 需求明细

#### R-S13: Chaos 配置文件 - P2

| 属性 | 内容 |
|---|---|
| **描述** | YAML 配置文件定义 Chaos 场景。用户手动编写，系统提供 API 激活/停用。 |
| **AC-01** | `chaos-profiles.yaml` 定义：每个场景包含名称、目标环境列表、故障注入规则列表（复用 `faultInjection` 模型）、定时表达式（可选） |
| **AC-02** | `POST /api/chaos/profiles/activate` - 激活指定场景（注入故障规则到目标环境） |
| **AC-03** | `POST /api/chaos/profiles/deactivate` - 停用指定场景（清除故障规则） |
| **AC-04** | `GET /api/chaos/profiles/status` - 查看当前激活的场景列表 |
| **AC-05** | 紧急停止：`POST /api/chaos/emergency-stop` - 一键清除所有 Chaos 场景注入的故障规则 |

**明确不实现**（v2.0）:
- ❌ 预置行业场景包（电商大促/金融高可用/IoT）— 数据无依据；放到 v3.0，届时根据实际团队数据汇总
- ❌ Chaos 实验界面 R-W11 — 放到 v3.0
- ❌ 实验结果报告（指标采集 + 聚合统计）— 当前 Baafoo 无指标采集能力，需新增 `RecordingEntry` 聚合查询体系，工作量远超 2-3 周

---

## 7. 全局性问题

### 7.1 Java 8 依赖检查清单

以下为各功能引入的外部依赖及其 Java 8 兼容性：

| 功能 | 依赖 | 版本 | Java 8 | 备注 |
|------|------|------|--------|------|
| OpenAPI 导入 | `org.yaml:snakeyaml` | 2.0+ | ✅ | v1.x 已广泛用于 Java 8 |
| OpenAPI 导入 | Swagger Parser | `io.swagger.parser.v3:swagger-parser` | ✅ | 兼容 Java 8 |
| Faker (方案 B) | `com.github.javafaker:javafaker` | 1.0.2 | ✅ | 传递依赖 `commons-lang3` 需确认版本 |
| 状态机 (原方案) | `com.jayway.jsonpath:json-path` | 2.8.0 | ✅ | 轻量方案不需要，保留以备后续 |
| GraphQL | `com.graphql-java:graphql-java` | v20+ | ❌（需 v17 以下） | v17 为最后一个 Java 8 兼容版本 |
| 故障注入 | 无新增依赖 | - | ✅ | 纯 Netty API 实现 |

### 7.2 Rule 模型扩展约束

当前 `Rule.java` 已有 20+ 字段。PRD 新增字段为 `response.fakerSeed`（Rule 级别）、`faultInjection`（Rule 级别）。为避免 God Object，**协议/功能特定配置抽取为 `protocolConfig` Map 或独立配置对象**：

```yaml
# 建议的 Rule 模型结构
rule:
  id: get-user
  # 通用字段（保留）
  enabled: true
  environments: [ft-1]
  priority: 100
  # 协议特定配置（抽取）
  protocolConfig:
    http:
      path: /api/users/{id}
      method: GET
  # 功能特定配置（抽取）
  features:
    faker:
      seed: 12345
    faultInjection:
      faults: [...]
```

AC 要求：`Rule.java` 通用字段保持稳定，`protocolConfig` 和 `features` 为 `Map<String, Object>` 类型，各功能模块自行解析。

### 7.3 工作量重估

| 功能 | PRD v1.0 估计 | Review 实际估计 | 差距原因 |
|------|--------------|---------------|---------|
| OpenAPI 导入 | 2-3 周 | 4-5 周 | `$ref` 解析、路径参数 `regex` 转换、Schema→占位符 |
| Faker 增量 | 1-2 周 | 1-2 周 ✅ | 已实现为主，增量小（如需 javafaker 重构再加 2 周） |
| 有状态 Mock（轻量） | 4-6 周 | 3-4 周 | 砍掉状态机、per-resource、可视化编辑器，仅 `requestCount` |
| 故障注入 | 3-4 周 | 4-5 周 | Kafka/Pulsar 协议层重构 + Netty `CONNECTION_RESET` 非标准实现 |
| GraphQL | 3-5 周 | **1-2 周** | 简化为 MatchCondition 扩展，无独立 Handler |
| Chaos 工程 | 2-3 周 | 2-3 周 ✅ | 仅 API + 配置文件，砍掉 UI 和预置包 |

---

## 8. 实施优先级总结 & 交付节奏

| 功能 | 优先级 | 重估工作量 | 建议版本 |
|---|---|---|---|
| OpenAPI 导入 Phase 1（JSON only） | P0 | 3 周 | v1.5 |
| Faker 增量（方案 A） | P0 | 1-2 周 | v1.5 |
| GraphQL 支持（MatchCondition 扩展） | P1 | 1-2 周 | v2.0 |
| 有状态 Mock（轻量方案） | P1 | 3-4 周 | v2.0 |
| 故障注入 Phase 1（HTTP DELAY + ERROR） | P1 | 3 周 | v2.0 |
| 故障注入 Phase 2（HTTP CONNECTION_RESET） | P1 | 1 周 | v2.0 |
| Faker 方案 B（javafaker 重构 + locale） | P1 | 2 周 | v2.0 |
| OpenAPI 导入 Phase 2（YAML + $ref） | P1 | 2 周 | v2.0 |
| Chaos 工程（配置文件 + API） | P2 | 2-3 周 | v2.5 |
| 故障注入 Phase 3（Kafka/Pulsar） | P2 | 2-3 周 | v2.5 |
| OpenAPI 导入 Phase 3（Swagger 2.0） | P2 | 1 周 | v2.5 |
| 有状态 Mock 通用状态机 + per-resource | P3 | 8-10 周 | v3.0 |
| Chaos 预置场景包 + 实验结果报告 | P3 | 4-6 周 | v3.0 |
| 状态机可视化编辑器 | P3 | 4-6 周 | v3.0（或砍掉） |
| Faker 自动类型推断 | 已砍 | - | 不实现 |

**建议交付节奏**:

- **v1.5**（2-3 个月）：OpenAPI 导入 Phase 1 + Faker 增量方案 A + `baafoo init` 支持 `--openapi` 参数
- **v2.0**（5-6 个月）：GraphQL + 有状态 Mock（轻量）+ 故障注入 Phase 1+2 + OpenAPI Phase 2 + Faker 方案 B
- **v2.5**（8-9 个月）：Chaos 配置文件 + 故障注入 Phase 3 + OpenAPI Phase 3
- **v3.0**（12 个月+）：通用状态机、Chaos 实验界面、预置场景包

---

## 9. 开放问题

| # | 问题 | 待决议 | 影响需求 |
|---|---|---|---|
| O1 | OpenAPI 导入处理 `security` 定义？ | 建议：Phase 1 忽略 security，用户手动添加 Header 匹配规则；Phase 2 支持自动生成 Authentication 规则 | R-S10 |
| O2 | Faker locale 是否支持规则级别覆盖？ | 建议：v2.0 方案 B 时支持规则级 `fakerLocale`，默认继承 Server 配置 | R-C2 |
| O3 | 轻量有状态 Mock 的请求计数器是否需要 per-resource？ | 现行方案仅 per-rule 全局计数。如用户需要 per-resource（每个 orderId 独立），放到通用状态机（P3）中解决 | R-S2 AC-13 |
| O4 | GraphQL Subscription 在 Agent WebSocket 拦截中的优先级？ | 放 v3.0，前提是 Agent 支持 WebSocket 拦截。当前 Agent 无此能力 | 路线图 |
| O5 | CONNECTION_RESET 在 Netty 中发 RST 的具体方案？ | `ctx.channel().unsafe().closeForcibly()` vs `SO_LINGER=0`，需在技术方案中定 | R-S12 AC-02 |
| O6 | Chaos 工程是否需要指标采集体系？ | 当前 Baafoo 无聚合统计。建议 v2.5 先不做报告功能，仅提供"激活/停用"API，指标采集和报告放 v3.0 | R-S13 |

---

## 10. 与 PRD v2.3 的合并准则

| 原 PRD 内容 | 变更 | 合并方式 |
|---|---|---|
| R-S2 AC-11（Faker 描述） | AC-11 已实现，本文档 AC-01/AC-02 为实际函数列表，AC-03~AC-06 为增量 | **追加替换**（避免直接覆盖，否则丢失已实现细节） |
| R-S2 AC-12（Faker 与模板变量组合） | 已实现，本文档 AC-07 确认 | **保留不变** |
| R-C2 AC-01（规则 Schema） | 新增 `response.fakerSeed`、`faultInjection` 字段 | **追加** |
| R-W2 AC-03（参数化规则编辑） | 新增 Faker 自动补全 AC | **追加** |
| 用户故事 US-19~US-24 | 新增 | 按顺序追加到 PRD 4.3 节 |
| 开放问题 O1~O6 | 新增 | 追加到 PRD 第 7 节 |
| R-S2 规则示例 | 新增 `requestCount` 条件、`faultInjection` 示例 | **追加** |
| GraphQL 规则 | **不新增独立 `graphql` 段落**，合并到 `http` 段落的 `conditions` 中 | **无需变动** |

> **注意**：合并时遵循"增量追加"策略，避免直接替换已实现功能的文档描述。R-S2 AC-11 的现有实现细节（30+ 函数的完整列表、语法约定等）在 `FakerProvider.java` 和 `TemplateEngine.java` 中有注释，PRD 应保持同步。

---

*本文档 v1.1 根据开发 Review 全面修订。核心变化：Faker 从"全新需求"改为"增量补充"；有状态 Mock 从通用状态机缩为轻量 requestCount；GraphQL 从独立 Handler 缩为 MatchCondition 扩展；Chaos 砍掉 UI 和预置场景包；工作量 ×1.5 重新评估。*
