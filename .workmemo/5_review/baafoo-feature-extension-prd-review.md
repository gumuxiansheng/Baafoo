# Baafoo 功能扩展 PRD Review

> Reviewer: 代可行
> 日期: 2026-06-13
> 审查文档: `baafoo-feature-extension-prd.md` v1.0

---

## 总体评价

文档结构清晰，用户故事完整，AC 可验收。但存在几个系统性问题：**与现有实现的重复/冲突**、**部分需求过度设计**、**P0 优先级判断有误**、**技术可行性存在盲区**。以下按模块逐一展开。

---

## 1. OpenAPI/Swagger 导入

### 1.1 与现有架构的契合度 ✅

- `RuleApiHandler` 已有完整的规则 CRUD API，新增 `POST /api/rules/import-openapi` 是自然扩展
- `MatchCondition` 已支持 `method`/`path`/`header`/`query`/`body`/`bodyJsonPath`，OpenAPI 生成的规则可直接复用现有模型
- `Rule.environments` 字段已存在，AC-02 的 `?environment=ft-1,ft-2` 参数直接映射

### 1.2 问题

**P1 — 路径参数转换方案不完整**

AC-04 写"支持路径参数如 `{id}` 转换为 Baafoo 的 `{{path.id}}` 模板变量"，但现有 `MatchCondition` 的 `path` 类型只支持 `equals`/`contains`/`startsWith`/`endsWith`/`regex` 五种操作符，**没有路径模板匹配**。`/api/users/{id}` 需要转为正则 `/api/users/[^/]+`，然后在模板中用 `{{request.path}}` + JSONPath 提取——这需要新增 `pathTemplate` 操作符或扩展 `regex` 分组捕获能力。**当前 MatchEngine 不支持**。

建议：AC-04 明确为 OpenAPI 导入的路径参数生成 `regex` 类型的 MatchCondition，如 `type: path, operator: regex, value: /api/users/[^/]+`，并在模板变量中通过 `{{request.path}}` 的子串提取（需扩展 TemplateEngine 支持 path segment 提取，如 `{{request.pathSegments.1}}`）。

**P2 — merge=true 的冲突检测逻辑不清**

AC-05 "相同 ID 覆盖"——但 OpenAPI 生成的规则 ID 是 `openapi-` 前缀自动生成的，与手动规则 ID 体系不同。如果两次导入同一个 OpenAPI 文件，第二次生成的 ID 和第一次一样吗？如果规范中 path+method 组合变了呢？

建议：明确冲突判定标准是 `ruleId` 还是 `path + method`。推荐后者，因为 OpenAPI 规范迭代时 path/method 组合才是稳定键。

**P3 — 响应 Body 生成策略过于理想**

AC-04 "根据 schema 生成占位符"——OpenAPI 3.0 的 `$ref` 引用、`allOf`/`oneOf`/`anyOf` 组合、`additionalProperties`、递归类型等复杂 schema 解析工作量远超预估。v1.0 应限定只处理 `type: object` + 简单属性类型，`$ref` 解析和组合类型放 v1.5。

**P4 — Swagger 2.0 支持的 ROI 值得商榷**

2026 年了，还在用 Swagger 2.0 的项目极少。建议降为 P2 或去掉，专注 OpenAPI 3.0/3.1。

**P5 — YAML 解析依赖缺失**

当前项目 pom 中没有 SnakeYAML 依赖。OpenAPI YAML 导入需要引入 `org.yaml:snakeyaml`。这不是问题，但需要显式列入技术方案。

### 1.3 优先级建议

**P0 合理**，但建议分两阶段：
- Phase 1: OpenAPI 3.0 JSON only，`minimal` + `example-first` 策略，不支持 `$ref` 解析
- Phase 2: YAML 支持、`schema-first` 策略、`$ref` 解析、Swagger 2.0

---

## 2. Faker 动态数据

### 2.1 与现有实现的冲突 🔴

**这是最大的问题。** PRD 写得像 Faker 功能还不存在，但实际上：

- `FakerProvider.java` **已经实现**了 30+ 函数（phone/email/name/idCard/address/company/url/ip/uuid/timestamp/int/boolean/hex/color 等）
- `TemplateEngine.java` **已经实现**了 `{{faker.xxx}}` 和 `{{request.*}}` 的模板渲染
- `StubResponseRenderer.java` **已经在** stub 响应流程中调用了 `TemplateEngine.render()`
- `RuleEditorPage.vue` **已经有了** Faker 参考面板 + 点击插入功能
- PRD v2.3 已经有了 R-S2 AC-11 和 AC-12

PRD 中大量 AC 在重复描述已实现的功能，且与现有实现对不上：

| PRD 描述 | 实际现状 | 问题 |
|----------|---------|------|
| AC-01 基础函数列表 | FakerProvider 已实现 phone/email/name/idCard/address/company/url/ip/uuid/timestamp，**但 PRD 缺少 firstName/lastName/city/province/zipCode/ipv6/mac/date/dateTime/hex/alphaNumeric/userAgent/color** | PRD 列表不完整 |
| AC-02 `{{faker.randomInt min max}}` | 实际语法是 `{{faker.int.min.max}}` | **语法不一致** |
| AC-02 `{{faker.randomElement [a,b,c]}}` | **未实现** | 需新增 |
| AC-03 `{{faker.regexify '[A-Z]{3}[0-9]{4}'}}` | **未实现** | 需新增 |
| AC-04 自动类型推断 | **未实现** | 设计复杂，见下文 |
| AC-06 `{{faker.seed 12345}}` | **未实现**，且与 PRD v2.3 的 `response.fakerSeed` 字段矛盾 | 需统一方案 |
| AC-05 与模板变量组合 | **已实现**，TemplateEngine 逐变量解析 | 无问题 |
| R-C2 AC-01 `response.fakerSeed` | FakerProvider 使用 SecureRandom，**不支持 seed** | 实现方案待定 |
| R-C2 AC-02 `faker.locale` 默认 zh-CN | FakerProvider **硬编码中文数据**，无 locale 概念 | 架构改造 |

### 2.2 技术方案问题

**自动类型推断（AC-04）——过度设计**

AC-04 "当响应 Body 字段名匹配常见模式时自动用 Faker 函数生成"——这意味着 TemplateEngine 需要理解 JSON 结构，而不是做字符串模板替换。当前 TemplateEngine 是纯文本替换，不解析 JSON 结构。要实现自动推断，需要：

1. 解析 body 为 JSON AST
2. 遍历每个字段，根据字段名匹配推断
3. 对推断的字段注入 Faker 值
4. 序列化回 JSON

这和当前的 `{{faker.xxx}}` 显式标记方案完全不同架构。且自动推断有**不可控的副作用**——用户写 `"name": "张三"` 期望固定值，系统却因为字段名匹配 `name` 而替换成随机值。

建议：**砍掉 AC-04 自动类型推断**，或降为 P2。理由：
1. 显式 `{{faker.xxx}}` 标记更可控、可预测
2. 自动推断不可靠（`name` 可能是人名、文件名、变量名，推断逻辑无法区分）
3. 实现成本高，收益低

**Faker seed 方案冲突**

PRD 提了两个 seed 机制：
- AC-06: `{{faker.seed 12345}}` 模板内联
- R-C2 AC-01: `response.fakerSeed` 规则级配置

两者语义矛盾：模板内联 seed 是在渲染流中间插入的，前面的 faker 调用已经用了随机数，后面 seed 只影响后续调用。而规则级 seed 应该在请求开始时就设定。

建议：**只保留规则级 `response.fakerSeed`**，去掉模板内联 `{{faker.seed}}`。理由：seed 的目的是"同一规则多次请求返回相同数据"，这是规则级语义，不是模板级语义。

**Faker locale 架构改造**

当前 FakerProvider 是零依赖、硬编码中文数据的实现。要支持 locale，两条路：
1. 引入 `com.github.javafaker:javafaker` 依赖（PRD 提到了但没说是否替换现有实现）
2. 在现有 FakerProvider 上加 locale 分支

建议：**引入 javafaker 依赖**。FakerProvider 的硬编码数据量已经很大（90 个姓、70 个名、31 个省、城市数组…），多 locale 维护成本不可接受。javafaker 支持 60+ locale，成熟稳定。但注意 javafaker 依赖 `com.github.britter:iban4j` 和 Apache Commons Lang，需评估依赖树膨胀。

### 2.3 优先级建议

Faker 核心功能**已实现**，PRD 应改为"增量补充"而非"全新需求"：
- P0: 补齐 `randomElement` + `regexify`，统一语法（`randomInt` → `int.min.max`），seed 支持
- P1: javafaker 重构 + locale 支持
- P2（或砍掉）: 自动类型推断

---

## 3. 有状态 Mock / 场景状态机

### 3.1 设计过度 🔴

这是整个 PRD 中**过度设计最严重**的部分。

**问题一：状态机模型过于通用**

AC-01 定义了一个通用状态机：states → transitions → events → actions。这本质上是把 Baafoo 从"挡板工具"变成了"工作流引擎"。考虑以下复杂度：

- `resourceIdExtractor: "$.orderId"` → 需要引入 JSONPath 库（当前项目无此依赖）
- per-resource 状态跟踪 → 需要一个 `ConcurrentHashMap<resourceKey, currentState>` 存储层
- `action.type: setVariable` → 需要一个运行时变量绑定机制，模板渲染时注入
- 状态机与规则关联 → 规则匹配后的副作用执行顺序（先匹配规则 → 触发状态转换 → 修改变量 → 下次请求渲染新值）

这不是 4-6 周能做完的，保守估计 8-10 周。

**问题二：与现有 Rule + ResponseEntry 模型的冲突**

当前模型：Rule 包含多个 ResponseEntry，每个 ResponseEntry 可有 MatchCondition。这已经可以做"有状态"——用不同的 condition 返回不同响应（如 `body contains "pending"` vs `body contains "paid"`）。状态机方案是另一种范式，两者如何共存？

如果用户既用了 `ResponseEntry.condition` 又关联了状态机，优先级怎么定？PRD 没有回答。

**问题三：可视化编辑器 ROI 太低**

R-W9 的拖拽式状态机编辑器（画布 + 节点 + 箭头 + 属性面板），前端工作量可能比后端还大。Baafoo 的前端是 Vue 3 + Element Plus，没有图形编辑库。引入 x6/g6/vuex-flow 等库又增加依赖和打包体积。

### 3.2 替代方案建议

**轻量方案：场景集 + 条件响应链**

不引入通用状态机，而是用现有模型组合实现"状态流转"效果：

1. 扩展 `ResponseEntry.condition` 支持 `requestCount` 条件（第 N 次请求匹配）
2. 规则内部维护一个请求计数器（per-rule，per-resource 可选）
3. 第一次请求返回 `pending`，第二次返回 `paid`

```yaml
- id: get-order
  request:
    method: GET
    path: /api/orders/{id}
  responses:
    - condition:
        type: requestCount
        operator: equals
        value: 1
      response:
        status: 200
        body: '{"status": "pending"}'
    - condition:
        type: requestCount
        operator: greaterThan
        value: 1
      response:
        status: 200
        body: '{"status": "paid"}'
```

这覆盖了 80% 的"订单状态流转"场景，实现成本只有状态机方案的 1/5。

**如果确实需要状态机**，建议：
1. v1.0 只做 YAML 定义 + API，不做可视化编辑器
2. 去掉 `resourceIdExtractor`，v1.0 只支持 per-rule 全局状态（不 per-resource）
3. 去掉 `action` 机制，用"状态 → 对应 ResponseEntry"的直接映射

### 3.3 优先级建议

**P1 合理，但需大幅缩减范围**。建议：
- v2.0: 轻量方案（requestCount + per-rule 状态），4 周可交付
- v2.5: 通用状态机 + per-resource，8-10 周
- 可视化编辑器放 v3.0 或砍掉

---

## 4. 故障注入

### 4.1 与现有架构的契合度 ✅

- `StubResponseRenderer.sendStubResponse()` 已有 `delayMs` 支持，DELAY 类型可复用
- `HttpStubHandler` 的 stub 分支是插入故障逻辑的自然位置
- Kafka/Pulsar Mock Broker 可在协议处理层插入故障

### 4.2 问题

**P1 — HTTP_ERROR 概率分布逻辑缺失**

AC-01 "HTTP_ERROR（指定状态码列表，按概率返回）"——多个 statusCodes 之间是等概率还是有权重？概率是在故障触发后分配，还是每个状态码有独立概率？PRD 没说。

建议：`faultInjection.faults` 数组中每个 fault 有自己的 `probability`，故障按声明顺序评估，首个命中的生效（和 R-W2 AC-03 一致）。如果都没命中，走正常响应。

**P2 — CONNECTION_RESET 在 Netty 中的实现**

`CONNECTION_RESET` 需要在写响应前 `ctx.close()` + 发送 RST。Netty 的 `Channel.close()` 发的是 FIN 不是 RST。要发 RST 需要 `ctx.channel().unsafe().closeForcibly()` 或设置 `SO_LINGER=0`。这在 Netty 中不是标准操作，需要明确技术方案。

**P3 — 正态分布延迟**

AC-05 "delayMs（均值）+ delayStdDevMs（标准差）"——正态分布可能产生负值，需要截断到 0。PRD 没提。

**P4 — Kafka 故障注入的协议层复杂度**

`PRODUCE_NOT_LEADER`（NOT_LEADER_OR_FOLLOWER 错误）需要 Kafka Mock Broker 在返回 ProduceResponse 时填入 error code。当前 `KafkaMockBroker` 的 Produce 处理逻辑是硬编码成功的。要支持故障注入，需要重构为从规则读取故障配置，然后条件性注入错误码。这比 HTTP 故障注入复杂得多，因为 Kafka 协议有特定的错误码体系和响应格式。

**P5 — 概率条件化触发（AC-04）**

"支持按请求特征（如 Header、Query 参数）条件化触发"——这本质上是在 `faultInjection` 里又嵌套了一个 `MatchCondition`。设计上可行但增加了配置复杂度。建议 v1.0 只支持 `probability`，条件化触发放 v1.5。

### 4.3 优先级建议

P1 合理。建议分阶段：
- v2.0 Phase 1: HTTP DELAY + HTTP_ERROR（概率），3 周
- v2.0 Phase 2: CONNECTION_RESET + READ_TIMEOUT，1 周
- v2.5: Kafka/Pulsar 故障注入 + 正态分布 + 条件化触发

---

## 5. GraphQL 支持

### 5.1 关键架构问题 🔴

**问题一：GraphQL 只是 HTTP POST 的子集**

GraphQL 请求是通过 HTTP POST 发送的，Body 是 `{"query": "...", "operationName": "...", "variables": {...}}`。Agent 的 HTTP 拦截已经覆盖了 GraphQL 请求——它们就是普通的 HTTP POST。

**真正需要的是在 MatchEngine 中支持 GraphQL 请求体的解析和匹配**，而不是新建一个 GraphQL Mock Handler。

当前 `MatchCondition` 已支持 `bodyJsonPath` 类型。如果要匹配 GraphQL 的 `operationName`，只需扩展 `MatchCondition` 支持 `bodyJsonPath: $.operationName` 即可。现有的 `MatchEngine` 已经有 JSON body 解析能力（`extractJsonField` 在 TemplateEngine 中已实现）。

PRD 提出的 `protocol: graphql` + 独立 Handler 是**过度架构**。

**问题二：GraphQL Schema 导入 vs OpenAPI 导入重复**

R-S14 AC-06 `POST /api/rules/import-graphql-schema` 和 R-S10 的 OpenAPI 导入是同类型的"规范文件 → 规则生成"功能。应统一设计导入框架，而不是两套独立 API。

**问题三：Subscription 支持**

AC-05 明确了 v1.0 不支持 Subscription，这很好。但 PRD 没有提到 WebSocket 拦截（Agent 当前不支持），而 GraphQL Subscription 几乎都走 WebSocket。建议在 v2.0 规划中明确 WebSocket 拦截的优先级。

### 5.2 替代方案建议

不新建 GraphQL Handler，而是：

1. 在 `MatchCondition` 中新增 `graphqlOperationName` 和 `graphqlOperationType` 类型（本质上是 `bodyJsonPath` 的语法糖）
2. 在规则编辑界面新增 GraphQL 快捷创建面板（自动填充 `bodyJsonPath` 条件）
3. 支持 GraphQL Schema 文件导入生成规则（复用 OpenAPI 导入框架）

这样 GraphQL 支持只需 1-2 周而非 3-5 周。

### 5.3 优先级建议

P1 合理，但实现方案应简化为 MatchCondition 扩展而非独立 Handler。

---

## 6. Chaos 工程

### 6.1 与故障注入的关系

第 6 章是第 4 章的场景化封装，P2 合理。

### 6.2 问题

**P1 — 实验结果报告需要数据采集能力**

AC-04 "故障触发次数、受影响请求数、应用返回的错误率变化、平均响应时间变化"——当前 Baafoo 没有指标采集能力。`RecordingEntry` 只记录单次请求，没有聚合统计。要生成实验报告，需要：

1. 在 RecordingEntry 中标记是否受 Chaos 影响
2. 新增聚合查询 API（按时间窗口统计错误率、延迟分布）
3. 前端图表渲染

这不是 2-3 周的量。

**P2 — 预置场景包的行业数据从哪来**

"电商大促"的 HTTP 503 概率 10%、延迟 +50%——这些数字的依据是什么？如果只是拍脑袋的数据，预置场景的可信度很低。

建议：预置场景放 v2.5 或更后，先让用户手动定义 Chaos 配置文件。

### 6.3 优先级建议

P2 合理，但建议：
- v2.0: 只做 Chaos 配置文件 + API，不做 UI
- v2.5: 实验界面 + 简单统计
- v3.0: 预置场景包 + 完整报告

---

## 7. 全局性问题

### 7.1 Java 8 约束

项目约束 Java 8，但 PRD 中多处提到需要 Java 8 不原生支持的库或特性：
- JSONPath 解析（状态机的 `resourceIdExtractor`）→ 需要 `com.jayway.jsonpath:json-path`
- OpenAPI 3.0 解析 → 需要 Swagger Parser 或自研
- GraphQL Schema 解析 → 需要 `com.graphql-java:graphql-java`

这些库本身兼容 Java 8，但会增加依赖树体积。PRD 应明确列出新增依赖及其 Java 8 兼容性。

### 7.2 Rule 模型膨胀

当前 `Rule.java` 已有 20+ 字段（id/name/protocol/serviceName/host/port/conditions/responses/enabled/priority/tags/environments/tcpRounds/tcpLoop/tcpPattern/tcpPrefixHex/tcpOffsetStart/tcpOffsetEnd/tcpOffsetHex/version/createdAt/updatedAt）。

PRD 又要新增：`stateMachineId`、`fakerSeed`、`fakerLocale`、`faultInjection`。Rule 类正在变成一个 God Object。

建议：将 HTTP/TCP/GrahpQL/FaultInjection 各协议和功能的特定配置抽取为 `protocolConfig` (Map) 或独立的配置对象，Rule 只保留通用字段。

### 7.3 工作量预估偏低

| 功能 | PRD 估计 | 实际估计 | 差距原因 |
|------|---------|---------|---------|
| OpenAPI 导入 | 2-3 周 | 4-5 周 | $ref 解析、路径参数转换、Schema→占位符 |
| Faker 增量 | 1-2 周 | 2-3 周 | javafaker 重构 + locale + seed |
| 有状态 Mock | 4-6 周 | 8-10 周 | 通用状态机 + per-resource + 可视化编辑器 |
| 故障注入 | 3-4 周 | 5-6 周 | Kafka/Pulsar 协议层改造 |
| GraphQL | 3-5 周 | 1-2 周 | 简化为 MatchCondition 扩展 |
| Chaos 工程 | 2-3 周 | 4-6 周 | 指标采集 + 报告 |

### 7.4 与 PRD v2.3 的合并风险

PRD 第 9 节提到了合并映射，但 R-S2 AC-11 扩展会**覆盖**现有 Faker 实现的文档描述。如果直接替换，会丢失当前实现的细节（30+ 函数的完整列表、语法约定等）。建议合并时采用"增量追加"而非"替换"。

---

## 8. 建议的修订摘要

| # | PRD 原文 | 建议修改 | 理由 |
|---|---------|---------|------|
| 1 | Faker 作为 P0 全新需求 | 改为"增量补充"，标注已实现功能 | 避免重复开发 |
| 2 | `{{faker.randomInt min max}}` | 统一为现有语法 `{{faker.int.min.max}}` | 与已实现代码一致 |
| 3 | AC-04 自动类型推断 | 砍掉或降为 P2 | 过度设计，不可控副作用 |
| 4 | `{{faker.seed 12345}}` + `response.fakerSeed` | 只保留规则级 `fakerSeed` | 语义清晰 |
| 5 | 状态机可视化编辑器 R-W9 | 砍掉或放 v3.0 | ROI 太低 |
| 6 | 状态机 per-resource | v1.0 只做 per-rule 全局状态 | 降低复杂度 |
| 7 | 状态机 resourceIdExtractor JSONPath | v1.0 去掉，或用更简单的 key 提取 | 避免引入 JSONPath 依赖 |
| 8 | GraphQL 独立 Handler R-S14 | 改为 MatchCondition 扩展 | GraphQL 是 HTTP 子集，无需独立 Handler |
| 9 | GraphQL Schema 导入 API | 复用 OpenAPI 导入框架 | 减少重复 |
| 10 | CONNECTION_RESET | 明确技术方案（SO_LINGER vs closeForcibly） | Netty 实现非标准 |
| 11 | 正态分布延迟 | 加上"截断至 0"约束 | 避免负延迟 |
| 12 | 故障条件化触发 AC-04 | v1.0 去掉，只支持 probability | 降低 v1.0 复杂度 |
| 13 | Swagger 2.0 支持 | 降为 P2 | ROI 低 |
| 14 | Rule 模型字段膨胀 | 抽取协议/功能特定配置为独立对象 | 防止 God Object |
| 15 | 交付节奏 | 重新评估工作量，至少 ×1.5 | 当前估计偏低 |
