# 18. 有状态 Mock 轻量方案 (P1)

> **PRD**: §3 R-S2 AC-13 — 有状态 Mock 轻量方案
> **提交**: 待提交
> **状态**: 实现完成，测试通过

## 一、需求摘要

PRD §3 R-S2 AC-13 要求在不引入完整状态机的前提下，提供轻量级有状态 Mock 能力。核心思路：基于 per-rule 请求计数器 + `requestCount` 条件 + `{{requestCount}}` 模板变量，覆盖"前 N 次返回 A，之后返回 B"等常见有状态场景。

实现 AC 清单：
- **AC-13.1**: `MatchCondition` 新增 `type: requestCount`，支持 `equals`/`greaterThan`/`lessThan`/`range`/`mod` 五种操作符
- **AC-13.2**: 每个 Rule 维护独立的 `AtomicInteger` 计数器，1-based 计数
- **AC-13.3**: `Rule.requestCountReset` 字段：当计数达到阈值时自动重置（实现循环场景）
- **AC-13.4**: `TemplateEngine` 支持 `{{requestCount}}` 模板变量
- **AC-13.5**: REST API 提供手动重置端点（单 rule / 全局）

明确不实现：
- ❌ 完整状态机（State Machine）— PRD 明确指出"轻量方案"
- ❌ 跨 Rule 的状态共享 — 每个 Rule 独立计数
- ❌ 持久化计数器 — 进程重启后归零（PRD 接受此限制）

## 二、文件变更

### 新增文件
| 文件 | 用途 |
|---|---|
| `baafoo-core/src/main/java/com/baafoo/core/util/StatefulCounterStore.java` | 全局单例计数器存储，per-rule AtomicInteger |
| `baafoo-core/src/test/java/com/baafoo/core/util/StatefulCounterStoreTest.java` | 计数器存储单元测试（15 个用例） |

### 修改文件
| 文件 | 变更 |
|---|---|
| `baafoo-core/src/main/java/com/baafoo/core/model/MatchCondition.java` | 新增 `requestCount(operator, value)` 和 `requestCount(operator, value, key)` 工厂方法 |
| `baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java` | 集成计数器：rule 匹配后自增计数；新增 `selectResponseEntry` 方法支持 per-entry 条件；新增 `requestCount` 条件分支和 `matchRequestCount` 辅助方法；`MatchResult` 增加 `requestCount` 字段 |
| `baafoo-core/src/main/java/com/baafoo/core/util/TemplateEngine.java` | 新增 `{{requestCount}}` 模板变量；`RequestContext` 增加 `requestCount` 字段及 getter/setter |
| `baafoo-core/src/test/java/com/baafoo/core/util/MatchEngineTest.java` | 新增 `@Before setUp()` 重置计数器；新增 10 个有状态 Mock 测试 |
| `baafoo-core/src/test/java/com/baafoo/core/util/TemplateEngineTest.java` | 新增 3 个 `{{requestCount}}` 模板测试 |
| `baafoo-server/src/main/java/com/baafoo/server/handler/StubResponseRenderer.java` | 新增 `sendStubResponse` 重载，接收 `int requestCount` 参数，注入到模板上下文 |
| `baafoo-server/src/main/java/com/baafoo/server/handler/HttpStubHandler.java` | 调用 renderer 时透传 `result.getRequestCount()` |
| `baafoo-server/src/main/java/com/baafoo/server/api/RuleApiHandler.java` | 新增 `POST /api/rules/reset-all-state` 和 `POST /api/rules/{id}/reset-state` 端点 |

## 三、架构设计

### 3.1 计数器存储：StatefulCounterStore

**问题**: HTTP/TCP/MQ 等多个 Handler 各自创建 `MatchEngine` 实例，但需要共享同一份 per-rule 计数器。

**方案**: 全局单例 `StatefulCounterStore.global()`，内部使用 `ConcurrentHashMap<String, AtomicInteger>`：

```java
public int incrementAndGet(String ruleId) {
    if (ruleId == null || ruleId.isEmpty()) return 0;
    return counters.computeIfAbsent(ruleId, k -> new AtomicInteger(0))
            .incrementAndGet();
}
```

设计要点：
- **1-based 计数**: 首次请求返回 1（`AtomicInteger(0)` + `incrementAndGet`）
- **懒初始化**: 首次访问时创建 counter，避免预分配
- **线程安全**: `ConcurrentHashMap` + `AtomicInteger` 保证多线程下原子自增
- **null 安全**: `ruleId` 为 null/空时返回 0，不影响匹配流程

### 3.2 计数时机：rule 匹配后、response entry 评估前

`matchConditions` 流程调整：

```
1. 评估 rule 级条件（method/path/header/query/body）
2. 若通过 → 自增计数器：count = StatefulCounterStore.global().incrementAndGet(ruleId)
3. 评估 response entry 级条件（含 requestCount 条件）：selectResponseEntry(rule, ..., count)
4. 检查 reset 阈值：若 requestCountReset 达到，自动重置计数器
```

**为什么不在 rule 匹配前自增？** 避免未命中规则也消耗计数（如 path 不匹配的请求不应影响计数）。

### 3.3 requestCount 条件操作符

`matchRequestCount(condition, count)` 支持五种操作符：

| operator | value 格式 | 示例 | 语义 |
|---|---|---|---|
| `equals` | 整数字符串 | `"3"` | `count == 3` |
| `greaterThan` | 整数字符串 | `"5"` | `count > 5` |
| `lessThan` | 整数字符串 | `"3"` | `count < 3` |
| `range` | `[min,max]` 闭区间 | `"[1,3]"` | `1 <= count <= 3` |
| `mod` | `key`=除数, `value`=余数 | `key="2"`, `value="0"` | `count % 2 == 0`（偶数次请求） |

非法 value（非数字）返回 `false`，不抛异常，保证 Mock 稳定性。

### 3.4 自动重置：requestCountReset

`Rule.requestCountReset` 字段（Step 1 已添加）：
- 当 `count >= resetThreshold` 时，调用 `resetIfThreshold` 将计数器归零
- 典型场景：循环返回 A→B→C→A→B→C...，设置 `requestCountReset: 3`

```java
Integer resetThreshold = rule.getRequestCountReset();
if (resetThreshold != null && resetThreshold > 0) {
    StatefulCounterStore.global().resetIfThreshold(rule.getId(), resetThreshold);
}
```

注意：重置发生在 response entry 选择**之后**，确保本次请求仍按当前 count 匹配，下一次请求从 1 开始。

### 3.5 模板变量：{{requestCount}}

`TemplateEngine` 新增 `requestCount` 分支：

```java
if ("requestCount".equals(expression)) {
    return context != null ? String.valueOf(context.getRequestCount()) : "0";
}
```

典型用法：`{"requestId": "req-{{requestCount}}", "message": "第 {{requestCount}} 次请求"}`

### 3.6 REST API：手动重置

| 端点 | 方法 | 权限 | 用途 |
|---|---|---|---|
| `/api/rules/reset-all-state` | POST | `rule:update` | 重置所有 rule 计数器 |
| `/api/rules/{id}/reset-state` | POST | `rule:update` | 重置指定 rule 计数器 |

响应格式：`{"success": true, "data": "Rule counter reset", "timestamp": "..."}`

## 四、测试覆盖

### StatefulCounterStoreTest（15 个用例）
- `incrementAndGet` 1-based 计数：首次返回 1，连续自增
- 多 ruleId 独立计数：互不干扰
- `get` 未初始化返回 0
- `incrementAndGet` null/空 ruleId 返回 0
- `reset` 特定 rule 后归零
- `resetAll` 清空所有计数器
- `resetIfThreshold`：未达阈值不重置 / 达到阈值重置 / 超过阈值重置 / 阈值为 0 不重置 / 负阈值不重置 / null 阈值不重置 / 无计数器时 no-op
- `size` 返回当前计数器数量
- 重置后计数从 1 重新开始

### MatchEngineTest 新增用例（10 个）
- `testStatefulLessThan`: PRD 示例——前 3 次返回 A，之后返回 B
- `testStatefulEquals`: 第 2 次请求返回特定响应
- `testStatefulGreaterThan`: 第 5 次之后返回降级响应
- `testStatefulRange`: `[1,3]` 范围内返回 A，`[4,6]` 返回 B
- `testStatefulMod`: 偶数次请求（mod 2 == 0）返回 A，奇数返回 B
- `testStatefulResetThreshold`: `requestCountReset: 3` 实现循环
- `testStatefulManualReset`: 模拟手动调用 reset 后计数从 1 重新开始
- `testStatefulNoConditions`: 无 requestCount 条件时默认返回首个 entry
- `testStatefulInvalidValue`: 非数字 value 不抛异常，返回 false
- `testStatefulUnknownOperator`: 未知操作符返回 false

### TemplateEngineTest 新增用例（3 个）
- `testRequestCountTemplateVariable`: `{{requestCount}}` 正常替换
- `testRequestCountDefaultZero`: 无 context 时返回 "0"
- `testRequestCountWithFakerCombo`: `{{requestCount}}` 与 `{{faker.name}}` 组合使用

## 五、验证

```bash
# 单元测试（计数器 + 匹配引擎 + 模板引擎）
mvnw test -pl baafoo-core "-Dtest=StatefulCounterStoreTest,MatchEngineTest,TemplateEngineTest"
# 结果：112 tests, 0 failures

# 全模块回归
mvnw test -pl baafoo-core
# 结果：258 tests, 0 failures, 0 errors

# 服务端编译
mvnw compile -pl baafoo-server -am -DskipTests
# 结果：BUILD SUCCESS
```

## 六、PRD AC 对齐

| AC | 状态 | 说明 |
|---|---|---|
| AC-13.1 requestCount 条件 | ✅ | 支持 equals/greaterThan/lessThan/range/mod 五种操作符 |
| AC-13.2 per-rule 计数器 | ✅ | StatefulCounterStore 全局单例，AtomicInteger 保证线程安全 |
| AC-13.3 requestCountReset | ✅ | 自动重置阈值，支持循环场景 |
| AC-13.4 {{requestCount}} 模板 | ✅ | TemplateEngine 支持，可与 faker 组合 |
| AC-13.5 手动重置 API | ✅ | POST /api/rules/reset-all-state 和 /api/rules/{id}/reset-state |

## 七、后续工作

- Web 控制台 UI：在 Rule 编辑页增加 requestCount 条件的可视化配置和"重置状态"按钮（R-W2 扩展）
- 持久化计数器：当前进程重启后归零，如需持久化可后续扩展为基于 H2 表的存储
- TCP/MQ Handler 适配：当前仅 HttpStubHandler 透传 requestCount，TCP/MQ Handler 可按需扩展
