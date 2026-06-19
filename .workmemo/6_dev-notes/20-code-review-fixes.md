## 代码审查修复工作留痕

**日期**: 2026-06-18
**提交**: `3c524fb` — fix: 修复代码审查发现的 7 个严重问题
**审查范围**: 近 3 小时内 5 次提交（`784493a` ~ `7702986`），30 文件 +4393/-136 行

---

### 修复清单

| 编号 | 严重度 | 问题 | 修复文件 | 修复方案 |
|------|--------|------|----------|----------|
| S1 | 严重 | `FakerProvider.RegexGenerator` 处理 `.*` 时 `pos++` 只跳过了 `.`，`*` 留在输入流中被 `applyQuantifier` 重复应用，导致输出长度不可预期 | `FakerProvider.java` L688 | `pos++` → `pos += 2`，同时消费 `.` 和 `*` |
| S2 | 严重 | `MatchEngine.extractGraphqlOperationType()` 对未识别的首 token 默认返回 `"query"`，`fragment`/`type` 等非法 GraphQL 请求会被误匹配 | `MatchEngine.java` L369-371 | 返回 `null` 表示无法识别操作类型，避免绕过 `graphqlOperationType` 过滤 |
| S3 | 严重 | `StatefulCounterStore.resetIfThreshold` 的 get → 判断 → remove 三步不是原子操作，高并发下计数器可能跳跃式丢失 | `StatefulCounterStore.java` L107-120 | 改用 `ConcurrentHashMap.compute(ruleId, ...)` 将读-判断-删合并为原子操作 |
| S4 | 严重 | `StatefulCounterStore` 的 `ConcurrentHashMap` 无容量限制或过期机制，删除 Rule 后计数器条目永不清理，存在内存泄漏风险 | `RuleApiHandler.java` DELETE 分支 | 在 rule 删除成功后联动调用 `StatefulCounterStore.global().reset(id)` |
| S5 | 严重 | `MatchEngine.match()` 在 `incrementAndGet` 后又调用 `get()` 读取计数，两次调用间其他线程可能做了自增或重置 | `MatchEngine.java` L70-74, L111-152 | `matchConditions` 改为返回 `int[]{responseIdx, count}`，count 在 `incrementAndGet` 时捕获，不再二次读取 |
| S6 | 严重 | `POST /api/rules/{id}/reset-state` 无论 rule 是否存在都返回 200，可被用于 ID 探测 | `RuleApiHandler.java` L65-73 | 调用 `ctx.storage.getRule(id)` 校验存在性，不存在时返回 404 |
| S7 | 严重 | `StubResponseRenderer.sendFaultErrorResponse` 对非标准 HTTP 状态码（如 600、999）调用 `HttpResponseStatus.valueOf()` 抛出 `IllegalArgumentException`，导致客户端收到连接关闭而非错误响应 | `StubResponseRenderer.java` L183-196 | try-catch 捕获 `IllegalArgumentException`，降级为 `INTERNAL_SERVER_ERROR` (500) |

**附带修复**: 修正 `MatchEngine` 中关于 `//` 注释的误导性注释（GraphQL spec 仅定义 `#` 为行注释符）。

---

### 测试验证

运行了与修改直接相关的 6 个测试类，共 192 个用例，全部通过：

| 测试类 | 用例数 | 覆盖修复 |
|--------|--------|----------|
| FakerIncrementTest | 30 | S1 |
| FaultInjectorTest | 25 | S7 |
| JsonPathUtilTest | 25 | S2 |
| MatchEngineTest | 56 | S2, S5 |
| StatefulCounterStoreTest | 15 | S3 |
| TemplateEngineTest | 41 | — |

Server 模块中 `FileStorageTest`、`TcpStubHandlerTest`、`ManagementApiHandlerTest` 存在 Mockito 预存编译错误，与本次修复无关。

---

### 变更统计

5 个文件修改，+59 / -25 行：

```
FakerProvider.java           |  2 +-
MatchEngine.java             | 32 ++++++++++++----------
StatefulCounterStore.java    | 20 ++++++++------
RuleApiHandler.java          | 18 ++++++++++++
StubResponseRenderer.java    | 12 +++++++-
```
