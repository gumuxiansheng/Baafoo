# 23. Web 控制台 UI 更新

> **PRD**: §1 R-W8, §2 R-W2 扩展, §3 R-W2 扩展, §4 R-W2 扩展, §5 R-W2 扩展
> **提交**: 待提交
> **状态**: 实现完成，前端构建通过

## 一、需求摘要

为后端已实现的 6 大功能补齐 Web 控制台前端界面：

1. **OpenAPI 导入向导** (R-W8, P0) — 4 步向导：上传 → 预览 → 选环境 → 导入
2. **故障注入配置面板** (R-W2 扩展, P1) — 规则编辑页新增故障注入折叠面板
3. **GraphQL 辅助面板** (R-W2 扩展, P1) — path 含 /graphql 时自动提示快捷添加条件
4. **Stateful Mock UI** (R-W2 扩展, P1) — requestCount 条件类型 + 计数器重置按钮
5. **Faker 增量** (R-W2 扩展, P0) — 参考面板补充 randomElement/regexify + fakerSeed 字段
6. **Chaos 工程** (P2) — PRD 明确"v2.0 不做 UI"，仅添加 API 层方法供未来使用

## 二、文件变更

### 新增文件
| 文件 | 用途 |
|---|---|
| `web/src/components/OpenApiImportDialog.vue` | OpenAPI 导入向导弹窗组件（4 步向导） |

### 修改文件
| 文件 | 变更 |
|---|---|
| `web/src/api/index.js` | 新增 `importOpenApi`、`resetRuleState`、`resetAllRuleState`、`chaosActivate`、`chaosDeactivate`、`chaosStatus`、`chaosEmergencyStop` API 方法 |
| `web/src/views/RuleEditorPage.vue` | 新增故障注入面板、GraphQL 辅助提示、requestCount/graphql 条件类型、fakerSeed/requestCountReset 字段、计数器重置按钮、Faker 参考面板补充 randomElement/regexify |
| `web/src/views/RulesPage.vue` | 新增"导入 OpenAPI"按钮，集成 OpenApiImportDialog 组件 |

## 三、实现细节

### 3.1 API 层扩展 (`web/src/api/index.js`)

新增 7 个 API 方法：

```javascript
// OpenAPI 导入 (R-S10 / R-W8)
importOpenApi(jsonContent, { environment, save, prefix })

// Stateful Mock 计数器重置 (R-S2 AC-13 AC-04)
resetRuleState(id)              // POST /api/rules/{id}/reset-state
resetAllRuleState()             // POST /api/rules/reset-all-state

// Chaos 工程 (R-S13)
chaosActivate(profileName)      // POST /api/chaos/profiles/activate
chaosDeactivate(profileName)    // POST /api/chaos/profiles/deactivate
chaosStatus()                   // GET  /api/chaos/profiles/status
chaosEmergencyStop()            // POST /api/chaos/emergency-stop
```

### 3.2 RuleEditorPage 增强

#### 故障注入面板 (R-W2 扩展 AC-01~03)
- 折叠面板，通过 `el-switch` 启用/禁用
- 支持添加多条故障规则（数组），按 declaration 顺序评估
- 故障类型下拉：HTTP_ERROR / DELAY / CONNECTION_RESET / READ_TIMEOUT
- HTTP_ERROR：概率 + 状态码列表（逗号分隔输入）
- DELAY：概率 + 延迟毫秒
- CONNECTION_RESET / READ_TIMEOUT：仅概率，无额外参数
- 保存时构建 `faultInjection` 对象，statusCodes 字符串转整数数组

#### GraphQL 辅助面板 (R-W2 扩展 AC-01~02)
- `isGraphqlPath` computed：检测 path 条件值是否包含 `/graphql`
- 自动显示 `el-alert` 提示和"+ GraphQL 快捷"按钮
- 点击按钮一次性添加 `graphqlOperationName` + `graphqlOperationType` 两个条件

#### 条件类型扩展
- 新增 `requestCount` 类型，操作符动态显示：equals/greaterThan/lessThan/range/mod
- 新增 `graphqlOperationName`、`graphqlOperationType` 类型
- `getValuePlaceholder` 函数为新类型提供占位提示
- `onConditionTypeChange` 函数为新类型设置默认操作符

#### Faker 增量
- `fakerGroups.misc` 数组新增 `faker.randomElement`、`faker.regexify`
- 新增 `fakerSeed` 字段（`el-input-number`），保存时传入规则
- 新增 `requestCountReset` 字段（`el-input-number`），循环模式配置

#### 计数器重置
- 编辑页新增"重置请求计数器"按钮（仅 HTTP 协议、非新建规则显示）
- 调用 `api.resetRuleState(id)`，成功后 `ElMessage.success` 提示

### 3.3 OpenApiImportDialog 组件 (R-W8)

4 步向导实现：

| 步骤 | 功能 | AC |
|---|---|---|
| 1. 上传规范 | 文件拖拽上传 + JSON 粘贴文本框；前端 JSON 格式校验 | AC-02 |
| 2. 预览规则 | 表格展示生成规则（名称/方法/路径/状态码/Body预览），支持勾选 | AC-03 |
| 3. 选择环境 | 多选环境（allow-create）、规则 ID 前缀输入 | AC-04 |
| 4. 导入结果 | 成功/失败/冲突统计，警告详情展开 | AC-05 |

**关键实现**:
- `el-steps` 组件驱动步骤切换
- `el-upload` 拖拽上传，`FileReader` 读取文件内容
- 步骤 1 调用 `api.importOpenApi(json, { save: false })` 仅预览
- 步骤 3 调用 `api.importOpenApi(json, { save: true, environment, prefix })` 持久化
- `handleClose` 重置所有状态，支持重复打开
- `@imported` 事件通知父组件刷新规则列表

### 3.4 RulesPage 集成

- 页面头部新增"导入 OpenAPI"按钮（`authStore.canWriteRule` 权限控制）
- 引入 `OpenApiImportDialog` 组件，`v-model` 控制显示
- `onImported` 回调：`ElMessage.success` + `rulesStore.fetchRulesPaged()` 刷新列表

## 四、验证

```bash
cd web
npm run build
# 结果：✓ built in 8.93s
# 2238 modules transformed
# RuleEditorPage-Duj204ab.js  26.92 kB
# RulesPage-C0LfCV5y.js       15.98 kB
# 无编译错误
```

## 五、PRD AC 对齐

| PRD AC | 状态 | 说明 |
|---|---|---|
| R-W8 AC-01 导入按钮 | ✅ | RulesPage 新增"导入 OpenAPI"按钮 |
| R-W8 AC-02 上传规范 | ✅ | 拖拽上传 + JSON 粘贴，前端格式校验 |
| R-W8 AC-03 预览规则 | ✅ | 表格展示，支持勾选 |
| R-W8 AC-04 选择环境 | ✅ | 多选环境 + 规则 ID 前缀 |
| R-W8 AC-05 导入结果 | ✅ | 成功/失败/冲突统计 |
| R-W8 AC-06 跳转高亮 | ⚠️ | 自动刷新列表，未实现高亮闪烁（Phase 2） |
| R-W2 故障注入 AC-01~03 | ✅ | 折叠面板 + 多故障 + 类型下拉 |
| R-W2 故障注入 AC-04 预览 | ⚠️ | 未实现故障模拟预览按钮（Phase 2） |
| R-W2 GraphQL AC-01~02 | ✅ | path 含 /graphql 自动提示 + 快捷按钮 |
| R-W2 GraphQL AC-03 Schema 上传 | ⚠️ | 未实现（Phase 2，依赖后端） |
| R-W2 Faker AC-01 自动补全 | ⚠️ | 已有参考面板，未实现输入触发补全（Phase 2） |
| R-W2 Faker AC-02 预览响应 | ⚠️ | 未实现（Phase 2） |
| R-C2 fakerSeed 字段 | ✅ | 规则编辑页新增 fakerSeed 输入 |
| R-C2 requestCountReset | ✅ | 规则编辑页新增 requestCountReset 输入 |

## 六、后续工作

- **R-W8 AC-06**: 导入完成后高亮闪烁新规则（需后端返回新规则 ID 列表）
- **R-W2 故障注入 AC-04**: 故障模拟预览按钮（需后端测试请求 API）
- **R-W2 GraphQL AC-03**: GraphQL Schema 上传（依赖后端 import-graphql-schema 端点）
- **R-W2 Faker AC-01**: 输入 `{{faker.` 触发自动补全（需 CodeMirror 或类似编辑器）
- **R-W2 Faker AC-02**: 预览响应按钮（需后端渲染 API）
- **Chaos UI (R-W11)**: v3.0 实现
