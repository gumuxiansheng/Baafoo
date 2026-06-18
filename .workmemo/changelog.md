# Baafoo 工作文档变更日志

## 2026-06-18

### UI 测试与问题修复
- 新增 `7_test-reports/` 测试报告目录
  - `00-ui-test-summary.md`: UI 测试摘要
  - `full-ui-test-report.md`: 完整 UI 测试报告
- 新增 `6_dev-notes/15-ui-test-bugfix.md`: UI 测试问题修复记录
  - **P0 问题分析**: 认证功能过于宽松是测试环境预期行为（auth.enabled: false）
  - **P1 问题修复**: "登录成功"提示未自动消失 ✅
    - 修改文件: `web/src/views/LoginPage.vue`
    - 添加 `duration: 3000` 配置

### 全链路集成测试
- 执行全链路协议测试 14/14 通过 (100%)
- HTTP/TCP/Kafka/Pulsar/JMS 协议验证通过
- 环境隔离测试通过

## 2026-05-31

### 文档结构整理
- 新增 `index.md` 导航索引
- 新增 `changelog.md` 变更日志
- `product-advice.md` 从 `2_prd/` 移至 `5_review/`（建议类文档归入审查目录）
- `plugin-arch-advice.md` 从 `2_prd/` 移至 `5_review/`（建议类文档归入审查目录）
- `architecture-review.md` 保留在 `4_deliverables/software-company/`（维持原结构）
- `bootstrap-classloader-debug-report.md` 从 `4_deliverables/` 移至 `6_dev-notes/`（开发笔记与交付物分离）
- 各文档元信息增加"对齐状态"字段

### 文档内容变更
- PRD v2.2: 新增 RBAC 用户角色权限控制（R-S7.7、R-W7）
- 代码审查报告: 新增 18 项问题清单（4 项严重已修复）
- 代码坏味道报告: 新增 26 项坏味道（God Class、Long Method 等）

## 2026-05-30

- 新增 Bootstrap ClassLoader 调试报告: 记录 GlobalRouteState 方案的完整调试过程
- PRD v2.1: 场景集关联环境时规则自动继承，新增 `GET /api/rules/{id}/inherited-environments` API

## 2026-05-29

- 概念设计 v0.8: 同步 PRD v1.5 全部变更（fail-closed、baafoo init、规则版本管理等）
- PRD v2.0: 规则与环境双向绑定（架构级变更，影响 6 个需求章节）
- UI 框架设计 v1.5: 同步 PRD v1.5 变更，新增场景集管理、撤销按钮
- 产品建议书 v1.0: 评审反馈（P0 级问题 3 项、P1 级问题 5 项）
- 插件架构建议: Agent 插件化架构设计（Advice 内联留 Core + 逻辑委托 Plugin）

## 2026-05-27

- 架构审查报告: 两位架构师联合审查，产出 P0 缺陷 6 项 + 增量任务 12 项
