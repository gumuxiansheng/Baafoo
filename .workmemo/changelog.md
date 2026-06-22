# Baafoo 工作文档变更日志

## 2026-06-22

### .workmemo 文档梳理
- 重写 `index.md`：从 10 个文件扩展到收录全部 60+ 文件，按活跃/过时/存档分类
- 创建 `5_review/archive/` 子目录，归档 4 个早期审查报告（20260531/20260603 系列）
- 给 6 个过时文档添加 📦 存档状态头
- 修复 `6_dev-notes/` 编号冲突：`20-code-review-fixes.md` → `20a-code-review-fixes.md`
- 更新 `5_review/index.md` 添加审查后修复进展说明

### 插件系统 P0-P4 完成
- `plugin-arch-advice.md` 更新至 P0-P4 全部完成状态
- 新增 `plugin-improvement-tasks.md`（31 项任务清单）
- 新增 `plugin-improvement-status-review.md`（逐项核查报告）
- 新增 `plugin-analysis-report.md`（插件系统完整性审查 & MockForge 对比）
- 新增 `docs/plugin-developer-guide.md`（开发者指南，含 ClassLoader 图、拦截模式表、扩展 Checklist）
- 新增 `baafoo-example-plugins/` 统一目录（tdmq + feign + kafka-redirect）
- 合并两份插件指南为权威版本

### MCP Server 集成
- 新增 `agent-skill/baafoo-mock-manager/` — QoderWork AI 技能包

## 2026-06-20

### MockForge 对比与迁移分析
- 新增 `Baafoo_mockforge_深度对比与移植建议.md`
- 新增 `P0移植完成度核验_20260619.md`
- 新增 `Phase1_Phase2_Code_Review_20260619.md`
- 新增 `协议版本升级需求分析_20260619.md`
- 新增 `gRPC_WebSocket_MQTT_AMQP协议可行性分析_20260620.md`
- 新增 `mockforge_AI_Baafoo迁移分析_20260620.md`
- 新增 `mockforge_AI集成功能分析_20260620.md`
- 新增 `mockforge_CLI功能分析与迁移评估_20260620.md`
- 新增 `多语言SDK扩展可行性分析_20260620.md`

### 全量代码审查
- 新增 `index.md` + 00~10 编号审查报告（174+37 个问题）
- 新增 `baafoo-feature-extension-prd-review.md`

## 2026-06-19

### 功能扩展开发笔记
- 新增 `6_dev-notes/16-faker-increment.md` 至 `24-code-review-fixes.md`（共 9 篇）
- 涵盖：Faker、GraphQL、有状态Mock、故障注入、Chaos工程、OpenAPI导入、Web控制台UI、代码审查修复

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
