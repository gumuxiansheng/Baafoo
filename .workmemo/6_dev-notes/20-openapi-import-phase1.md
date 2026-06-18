# 20. OpenAPI 导入 Phase 1 (P0)

> **PRD**: §1 R-S10 AC-01~06 — OpenAPI 3.0 JSON 导入，example-first 策略
> **提交**: 待提交
> **状态**: 实现完成，测试通过

## 一、需求摘要

PRD §1 R-S10 Phase 1 要求支持导入 OpenAPI 3.0 JSON 规范文件，自动生成对应的 HTTP 挡板规则骨架，包括路径、方法、参数化条件、默认响应。

实现 AC 清单：
- **AC-01**: `POST /api/rules/import-openapi` 端点，接收 JSON body，返回规则预览
- **AC-02**: 通过 `?environment=ft-1,ft-2` 指定默认关联环境
- **AC-03**: 支持 OpenAPI 3.0 JSON；Swagger 2.0 明确拒绝（Phase 3）
- **AC-04**: 路径参数 `{id}` 转为 `regex` 类型 MatchCondition；example-first 响应 Body 生成
- **AC-05**: 冲突判定以 path+method 为稳定键（编码在 ruleId 中），`?save=true` 时覆盖更新
- **AC-06**: 返回统计：generatedCount、skippedCount、conflictCount、warnings

明确不实现（留待后续 Phase）：
- ❌ YAML 支持 — Phase 2
- ❌ `$ref` / `allOf` / `oneOf` / `anyOf` 解析 — Phase 2（返回空对象 `{}`）
- ❌ Swagger 2.0 — Phase 3
- ❌ schema-first 策略 — Phase 2

## 二、文件变更

### 新增文件
| 文件 | 用途 |
|---|---|
| `baafoo-core/src/main/java/com/baafoo/core/util/OpenApiImporter.java` | OpenAPI 3.0 JSON 解析器，生成 Rule 列表 |
| `baafoo-core/src/test/java/com/baafoo/core/util/OpenApiImporterTest.java` | 单元测试（37 个用例） |

### 修改文件
| 文件 | 变更 |
|---|---|
| `baafoo-server/src/main/java/com/baafoo/server/api/RuleApiHandler.java` | 新增 `POST /api/rules/import-openapi` 端点和 `handleOpenApiImport` 方法，支持 `?environment=`、`?save=`、`?prefix=` 查询参数 |

## 三、架构设计

### 3.1 OpenApiImporter 解析器

**设计原则**: 无状态工具类，线程安全。每次调用创建独立 ObjectMapper。

```java
public OpenApiImportResult importSpec(String jsonContent, String ruleIdPrefix,
                                       List<String> environments)
        throws OpenApiImportException
```

**解析流程**:
1. 校验 JSON 格式和 `openapi` 字段（必须 3.x）
2. 遍历 `paths` 对象的每个 path + method 组合
3. 跳过无 `responses` 定义的路径（计入 skippedCount）
4. 为每个有效操作生成 Rule：
   - ruleId = `{prefix}{method}-{slugified-path}`
   - conditions = [method equals, path equals/regex]
   - responses = [首个 2xx 状态码 + example-first body]
   - environments/tags 从 spec 提取

### 3.2 路径参数 regex 转换（AC-04）

OpenAPI 路径 `/api/users/{id}/orders/{orderId}` 转换为 regex：
```
/api/users/[^/]+/orders/[^/]+
```

实现使用正则替换：`\{[^}]+\}` → `[^/]+`。当路径包含 `{` 时使用 `regex` 操作符，否则使用 `equals`。

### 3.3 example-first 响应 Body 生成策略（AC-04）

按优先级查找 example：

1. **response.example** — 响应级 example
2. **content.application/json.example** — content 级 example
3. **schema.example** — schema 级 example
4. **schema.default** — schema 默认值
5. **schema properties 生成** — 从 `type: object` 的 properties 生成占位 JSON
6. 空字符串 — 无 schema 信息

**占位符类型映射**:
| OpenAPI type | format | 占位值 |
|---|---|---|
| string | (无) | `"string"` |
| string | email | `"user@example.com"` |
| string | date-time | `"2024-01-01T00:00:00Z"` |
| string | date | `"2024-01-01"` |
| string | uuid | `"00000000-0000-0000-0000-000000000000"` |
| string | uri | `"https://example.com"` |
| integer | * | `0` |
| number | * | `0` |
| boolean | * | `false` |
| array | * | `[单个 item 占位]` |
| object | * | `{properties 占位}` |

**$ref / allOf / oneOf / anyOf 处理**: Phase 1 不解析，返回 `{}`（空对象）。

### 3.4 状态码提取

优先级：
1. `200`（首选）
2. 任意 `2xx`
3. `default`
4. 首个可用状态码

### 3.5 REST API 端点

```
POST /api/rules/import-openapi
  Query: ?environment=ft-1,ft-2&save=true&prefix=openapi-
  Body: OpenAPI 3.0 JSON
  Response: {
    "rules": [...],
    "generatedCount": 3,
    "skippedCount": 1,
    "conflictCount": 0,
    "warnings": [...],
    "summary": "..."
  }
```

**save 参数**:
- `save=false`（默认）: 仅预览，不持久化
- `save=true`: 持久化规则，按 ruleId 检测冲突并覆盖更新

**权限**: 需要 `rule:create` 权限。

### 3.6 冲突判定（AC-05）

PRD AC-05 明确："冲突判定以 path + method 为稳定键"。

实现：ruleId 编码了 path + method 信息（如 `openapi-get-api-users-id`），因此按 ruleId 检测冲突等价于按 path+method 检测。同一规范二次导入时，相同 path+method 的规则会被覆盖更新。

## 四、测试覆盖

### OpenApiImporterTest（37 个用例）

**基础解析（8 个）**:
- empty/null content → 异常
- invalid JSON → 异常
- missing openapi field → 异常
- Swagger 2.0 → 拒绝
- OpenAPI 2.x → 拒绝
- empty paths → 空结果
- missing paths → 空结果

**规则生成（4 个）**:
- 简单 GET 规则（method + path 条件）
- operationId 作为 rule name
- 无 operationId 时 fallback 到 "METHOD path"
- 多 method / 多 path 生成多规则

**路径参数 regex（3 个）**:
- 单参数 `{id}` → `[^/]+`
- 多参数 `{userId}/orders/{orderId}`
- 直接调用 convertPathToRegex

**状态码提取（4 个）**:
- 200 优先
- 201 提取
- 200 优先于其他 2xx
- fallback 到首个 2xx

**example-first 响应 Body（9 个）**:
- response.example
- content.application/json.example
- schema.example
- schema properties 生成（integer/string/boolean/number）
- string formats（email/date-time/date/uuid）
- array property
- $ref → 空对象
- 无 schema → 空字符串
- 嵌套 object

**环境/标签（3 个）**:
- environments 关联
- 默认空 environments
- tags 从 spec 提取

**跳过逻辑（2 个）**:
- 无 responses 的路径跳过
- 混合跳过和生成

**ruleId 生成（2 个）**:
- 自定义 prefix
- 带路径参数的 slugify

**集成测试（1 个）**:
- 完整 Petstore-like spec（3 个路径，验证 name/tags/environments/regex/body）

## 五、验证

```bash
# 单元测试
mvnw test -pl baafoo-core "-Dtest=OpenApiImporterTest"
# 结果：37 tests, 0 failures

# 全模块回归
mvnw test -pl baafoo-core
# 结果：320 tests, 0 failures, 0 errors

# 服务端编译
mvnw compile -pl baafoo-server -am -DskipTests
# 结果：BUILD SUCCESS
```

## 六、PRD AC 对齐

| AC | 状态 | 说明 |
|---|---|---|
| AC-01 import-openapi 端点 | ✅ | POST /api/rules/import-openapi，返回预览 |
| AC-02 environment 参数 | ✅ | ?environment=ft-1,ft-2 逗号分隔 |
| AC-03 OpenAPI 3.0 JSON | ✅ | 校验 openapi 字段 3.x；Swagger 2.0 拒绝 |
| AC-04 路径参数 regex + example-first | ✅ | {id}→[^/]+；example 优先级查找 |
| AC-05 path+method 冲突判定 | ✅ | ruleId 编码 path+method，save=true 时覆盖 |
| AC-06 统计返回 | ✅ | generatedCount/skippedCount/conflictCount/warnings |

## 七、后续工作

- **Phase 2**: YAML 支持 + `$ref` 解析 + schema-first 策略
- **Phase 3**: Swagger 2.0 支持
- **R-W8**: Web 控制台导入向导 UI（文件上传、预览编辑、环境选择）
- **R-C3**: Server 配置项（default-environment、strategy、rule-id-prefix）
- **GraphQL Schema 导入**: 复用 R-S10 框架（AC-04 of §5）
