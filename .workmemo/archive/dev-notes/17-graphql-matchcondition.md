# 17. GraphQL MatchCondition 扩展 (P1)

> **PRD**: §5 R-S2 AC-14 — GraphQL 支持（MatchCondition 扩展）
> **提交**: 待提交
> **状态**: 实现完成，测试通过

## 一、需求摘要

PRD §5 明确指出 GraphQL 请求本质上是 HTTP POST 的子集（Body 含 `query`/`operationName`/`variables`），Agent 的 HTTP 拦截已覆盖。真正需要的是在 `MatchCondition` 中支持 GraphQL 请求 Body 的解析匹配，而非新建独立 Handler。

实现 AC 清单：
- **AC-01**: `MatchCondition` 新增 `type: graphqlOperationName`（语法糖，等价于 `bodyJsonPath: $.operationName`）
- **AC-02**: `MatchCondition` 新增 `type: graphqlOperationType`（匹配 query/mutation/subscription）
- **AC-03**: 规则示例（直接在 HTTP 规则下，无需独立 `graphql` 段落）
- **AC-04**: GraphQL Schema 导入复用 R-S10 OpenAPI 导入框架（不在本步骤实现，留待 OpenAPI 导入步骤）

明确不实现：
- ❌ 独立 GraphQL Mock Handler — GraphQL 是 HTTP POST 子集
- ❌ GraphQL Subscription（WebSocket）— Agent 当前不支持 WebSocket 拦截

## 二、文件变更

### 新增文件
| 文件 | 用途 |
|---|---|
| `baafoo-core/src/main/java/com/baafoo/core/util/JsonPathUtil.java` | 公共 JSON 路径提取工具，供 TemplateEngine 和 MatchEngine 共用 |
| `baafoo-core/src/test/java/com/baafoo/core/util/JsonPathUtilTest.java` | JsonPathUtil 单元测试（25 个用例） |

### 修改文件
| 文件 | 变更 |
|---|---|
| `baafoo-core/src/main/java/com/baafoo/core/model/MatchCondition.java` | 新增 `bodyJsonPath` / `graphqlOperationName` / `graphqlOperationType` 三个工厂方法；更新 type 字段 Javadoc |
| `baafoo-core/src/main/java/com/baafoo/core/util/MatchEngine.java` | 实现 `bodyJsonPath` 匹配（原先为 stub 返回 false）；新增 `graphqlOperationName` / `graphqlOperationType` 条件分支；新增 `extractGraphqlOperationType` 私有方法 |
| `baafoo-core/src/main/java/com/baafoo/core/util/TemplateEngine.java` | 移除私有 `extractJsonField` 方法和 `MAPPER` 字段，改用 `JsonPathUtil.extract` |
| `baafoo-core/src/test/java/com/baafoo/core/util/MatchEngineTest.java` | 移除 `testBodyJsonPathNotImplemented`；新增 15 个测试覆盖 bodyJsonPath 和 GraphQL 条件 |

## 三、架构设计

### 3.1 JsonPathUtil 抽取

**问题**: 原先 `TemplateEngine.extractJsonField` 是私有方法，`MatchEngine` 无法复用。PRD §5 明确指出"`extractJsonField` 已在 `TemplateEngine` 中实现，可复用"。

**方案**: 抽取为公共工具类 `JsonPathUtil`，提供两个静态方法：
- `extract(body, jsonPath)`: 提取字段值，返回字符串（标量返回文本，对象/数组返回 JSON 序列化，缺失返回空串）
- `exists(body, jsonPath)`: 判断路径是否存在（区分"字段值为空串"和"字段缺失"）

支持的路径语法：
- 点分字段：`user.address.city`
- 可选 `$` 前缀：`$.operationName` 或 `$.user.name`
- 数组索引：`items[0]`、`$.items[1]`

明确不支持（避免引入 Jayway json-path 依赖）：
- 过滤器 `[?(@.age > 18)]`
- 通配符 `[*]`
- 递归下降 `..`

### 3.2 bodyJsonPath 条件实现

**字段约定**（与 `header`/`query` 条件一致）：
- `key` = JSON 路径表达式（如 `$.operationName`）
- `value` = 期望值（用于 `equals`/`contains`/`regex` 等比较操作符；`exists` 操作符忽略）

**向后兼容**: 当 `key` 为空时，回退到旧语义——将 `value` 视为路径，隐式使用 `exists` 语义。这保留了原 `testBodyJsonPathNotImplemented` 测试中的配置方式（虽然该测试已被替换）。

### 3.3 GraphQL 操作类型解析

`extractGraphqlOperationType` 方法从请求 Body 的 `query` 字段解析操作类型：

1. 跳过前导空白和 `#` 行注释
2. 若首个非空白字符为 `{`，判定为匿名 query（GraphQL 规范 §6.1.2 简写语法）
3. 否则提取首个标识符 token，匹配 `query`/`mutation`/`subscription`
4. 未识别的 token 默认按 `query` 处理

返回 `null` 表示 Body 不是有效的 GraphQL 请求（如缺少 `query` 字段）。

### 3.4 语法糖等价关系

| GraphQL 条件 | 等价的 bodyJsonPath 条件 |
|---|---|
| `graphqlOperationName` + `equals` + `GetUser` | `bodyJsonPath` + `key=$.operationName` + `equals` + `GetUser` |
| `graphqlOperationType` + `equals` + `query` | （无直接等价，需解析 `query` 字段首 token） |

## 四、测试覆盖

### JsonPathUtilTest（25 个用例）
- 空值边界：null body / empty body / null path / empty path
- 顶层字段提取：`name` / `$.name`
- 嵌套字段提取：`user.address.city` / `$.user.address.city`
- 数组索引：`items[0]` / 越界
- 缺失字段处理
- 非法 JSON
- 对象/数组节点返回 JSON 序列化
- 标量类型：数字 / 布尔 / JSON null
- 根路径 `$` 返回整个 body
- exists 方法：存在 / 缺失 / 嵌套 / null body / JSON null / 数组索引

### MatchEngineTest 新增用例（15 个）
- `testBodyJsonPathEquals`: bodyJsonPath + equals 操作符
- `testBodyJsonPathExists`: bodyJsonPath + exists 操作符
- `testBodyJsonPathBackwardCompatValueAsPath`: 向后兼容（value 作为路径）
- `testBodyJsonPathNested`: 嵌套路径
- `testGraphqlOperationNameEquals`: operationName 等值匹配
- `testGraphqlOperationNameExists`: operationName 存在性检查
- `testGraphqlOperationTypeQuery`: 显式 query + 匿名 query
- `testGraphqlOperationTypeMutation`: mutation 匹配 + 与 query 互斥
- `testGraphqlOperationTypeSubscription`: subscription 匹配
- `testGraphqlOperationTypeWithLeadingComment`: 前导 `#` 注释
- `testGraphqlCombinedConditions`: operationName + operationType AND 组合
- `testGraphqlOperationTypeMissingQueryField`: 缺少 query 字段
- `testGraphqlOperationNameCaseInsensitive`: 大小写不敏感

## 五、验证

```bash
# 单元测试
mvnw test -pl baafoo-core "-Dtest=JsonPathUtilTest,MatchEngineTest"
# 结果：71 tests, 0 failures

# 全模块回归
mvnw test -pl baafoo-core
# 结果：230 tests, 0 failures

# 服务端编译
mvnw compile -pl baafoo-server -am -DskipTests
# 结果：BUILD SUCCESS
```

## 六、PRD AC 对齐

| AC | 状态 | 说明 |
|---|---|---|
| AC-01 graphqlOperationName | ✅ | 实现，等价于 bodyJsonPath: $.operationName |
| AC-02 graphqlOperationType | ✅ | 实现，解析 query 字段首 token |
| AC-03 规则示例 | ✅ | 测试用例覆盖示例中的所有场景 |
| AC-04 GraphQL Schema 导入 | ⏳ | 复用 R-S10 OpenAPI 导入框架，留待 OpenAPI 导入步骤 |

## 七、后续工作

- AC-04 GraphQL Schema 导入将在 OpenAPI 导入 Phase 1 步骤中实现，复用 R-S10 的导入框架
- Web 控制台的 GraphQL 快捷配置面板（R-W2 扩展）将在前端 UI 更新步骤中实现
