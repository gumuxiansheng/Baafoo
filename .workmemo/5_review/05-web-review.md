# Web 前端详细审查报告

> 共审查 21 个源文件（18 个 Vue/JS 源文件 + 3 个构建配置）
> 发现 25 个问题 (Critical: 0, High: 5, Medium: 7, Low: 13)

---

## High (5)

### WEB-H1: 全局事件监听器未移除
- **文件**: `RuleEditorPage.vue:317-321`
- **描述**: `document.addEventListener('toggle-faker-ref', ...)` 在组件挂载时注册但从未移除。组件卸载/重挂载后会累积重复监听器。
- **建议**: `onUnmounted` 中 `document.removeEventListener`

### WEB-H2: Faker 变量插入写入错误的 textarea
- **文件**: `RuleEditorPage.vue:374-385`
- **描述**: `insertFakerVar` 使用 `document.querySelector('.response-card textarea')` 总是定位到**第一个** textarea。当有多条响应分支时，点选后面的 faker 标签会插入到错误的卡片。
- **建议**: 传入具体响应索引或使用 template ref

### WEB-H3: v-html 内联 onclick 按钮存在 XSS 风险
- **文件**: `RuleEditorPage.vue:314,242`
- **描述**: `templateVarHint` 包含 `<a href="javascript:void(0)" onclick="document.dispatchEvent(...)">` 通过 `v-html` 渲染。绕过 Vue 事件系统且无法测试。若任何用户数据进入此字符串即 XSS。
- **建议**: 改为 Vue 组件 + `@click`

### WEB-H4: BaafooLogo.vue variant prop 死参数
- **文件**: `BaafooLogo.vue:15-23`
- **描述**: `variant` prop 声明后，LoginPage 传入 `variant="login"`，但 `logoSrc` computed 始终返回同一 SVG。误导性 API。
- **建议**: 实现 variant 逻辑或移除 prop

### WEB-H5: Mode Radio 按钮值与 API 不一致
- **文件**: `EnvironmentDetailPage.vue:19-24,72-74`
- **描述**: Radio 按钮值用大写（`STUB`, `PASSTHROUGH`），API 用小写（`stub`, `passthrough`）。切换模式时发送错误值；加载时因值不匹配导致无选中项。
- **建议**: Radio 按钮值改为小写匹配 API 约定

---

## Medium (7)

| ID | 文件:行 | 问题 | 建议 |
|----|---------|------|------|
| WEB-M1 | RuleEditorPage.vue:347-348 | 0 值被 falsy 检查吞没（`port: rule.value.port || null`） | 使用 `?? ` 代替 `\|\|` |
| WEB-M2 | DashboardPage.vue:70 | import * as echarts 全量打包 (~1MB+) | 使用 tree-shakeable 导入 |
| WEB-M3 | DashboardPage.vue:108-139 | ECharts init 后未 dispose，路由切换时泄漏 | onUnmounted 中 dispose |
| WEB-M4 | RulesPage.vue:55,139 | 乐观 UI 修改 data 后调 API，失败后状态需等 refetch | API 失败时回滚 UI |
| WEB-M5 | StatusPage.vue:84-87 | isOnline 中用 Date.now() - agent.lastHeartbeat；若 lastHeartbeat 为 ISO 字符串则结果为 NaN | 解析时间戳 |
| WEB-M6 | EnvironmentsPage.vue:127-129 | getRuleCountForEnv O(n*m) 无 memo | 预计算 lookup Map |
| WEB-M7 | EnvironmentsPage.vue:140-157 | saveAssociation 客户端 diff，保存时可能与其他 admin 冲突 | 服务端原子替换 |

---

## Low (13)

| ID | 文件:行 | 问题 |
|----|---------|------|
| WEB-L1 | RuleEditorPage.vue:66 | allow-create 环境选择器可输入任意字符串 |
| WEB-L2 | EnvironmentsPage.vue:115-119 | loadEnvs 无 try/finally，loading 可能卡住 |
| WEB-L3 | ScenesPage.vue:137 | createScene 硬编码 active: false |
| WEB-L4 | LogsPage.vue:51 | 传空字符串而非 undefined |
| WEB-L5 | api/index.js:9 | Token 存 localStorage（XSS 可窃取），已有记录 |
| WEB-L6 | api/index.js:28-33 | 错误拦截器统一转为 { success: false }，caller 无法区分网络错误和 API 错误 |
| WEB-L7 | api/index.js:79 | DELETE 请求带 body，部分代理不支持 |
| WEB-L8 | store/index.js:16-17 | fetchStatus catch 为空 |
| WEB-L9 | store/index.js:136-152 | fetchMe 在成功和失败路径都设 initialized=true |
| WEB-L10 | App.vue:123 | setInterval 30s 无条件轮询，失败无退避 |
| WEB-L11 | main.js:15-17 | 全量注册 Element Plus Icons (~300+) |
| WEB-L12 | RecordingsPage.vue:206-209 | formatHeaders 未检查 headers 是对象 |
| WEB-L13 | LoginPage.vue:114 | 用户名原始存 localStorage |

---

## 与历史报告的关系

| 历史编号 | 对应新编号 |
|---------|-----------|
| SEC-2 (localStorage token) | WEB-L5 |
| SEC-3 (前端路由权限) | 保留为独立项 |
