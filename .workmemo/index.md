# Baafoo 工作文档索引

> 本目录存放 Baafoo 项目的工作记录文档，按产品生命周期阶段组织。

---

## 文档地图

| 阶段 | 文档 | 版本 | 状态 | 一句话描述 |
|------|------|------|------|-----------|
| 概念 | [1_concepts/baafoo-concept-design.md](1_concepts/baafoo-concept-design.md) | v0.8 | ⚠️ 需同步 | 系统整体概念、架构分层与核心设计 |
| 需求 | [2_prd/baafoo-prd.md](2_prd/baafoo-prd.md) | v2.2 | ✅ 最新 | 产品需求、用户故事与验收标准 |
| 设计 | [3_design/ui-framework-design.md](3_design/ui-framework-design.md) | v1.5 | ⚠️ 需同步 | Web 控制台前端界面框架设计 |
| 设计 | [3_design/prototype.html](3_design/prototype.html) | v1.4 | ⚠️ 需同步 | Web 控制台 UI 原型（可浏览器打开） |
| 交付 | [4_deliverables/software-company/architecture-review.md](4_deliverables/software-company/architecture-review.md) | — | ✅ | 架构审查报告与增量任务列表 |
| 审查 | [5_review/product-advice.md](5_review/product-advice.md) | v1.0 | ✅ | 产品建议书（评审反馈） |
| 审查 | [5_review/plugin-arch-advice.md](5_review/plugin-arch-advice.md) | — | ✅ | Agent 插件化架构建议 |
| 审查 | [5_review/code-review-report.md](5_review/code-review-report.md) | — | ✅ | 代码审查报告（18 项问题） |
| 审查 | [5_review/code-smells-report.md](5_review/code-smells-report.md) | — | ✅ | 代码坏味道报告（26 项） |
| 开发笔记 | [6_dev-notes/bootstrap-classloader-debug-report.md](6_dev-notes/bootstrap-classloader-debug-report.md) | — | ✅ | Bootstrap ClassLoader 双加载问题调试记录 |

---

## 阅读顺序

```
1. 概念设计 → 理解系统是什么、解决什么问题
2. PRD      → 理解要做什么、验收标准是什么
3. 产品建议 → 理解关键产品决策与风险
4. 插件架构建议 → 理解 Agent 插件化设计
5. UI 设计 + 原型 → 理解用户界面
6. 架构审查 → 理解技术风险与增量任务
7. 代码审查/坏味道 → 理解代码质量现状
8. CL 调试报告 → 理解 Bootstrap CL 问题的来龙去脉
```

---

## 目录结构

```
.workmemo/
├── index.md                                    ← 本文件（导航索引）
├── changelog.md                                ← 变更日志
├── 1_concepts/                                 ← 概念设计
│   └── baafoo-concept-design.md
├── 2_prd/                                      ← 产品需求
│   └── baafoo-prd.md
├── 3_design/                                   ← 设计文档
│   ├── ui-framework-design.md
│   └── prototype.html
├── 4_deliverables/                             ← 交付物
│   └── software-company/
│       └── architecture-review.md
├── 5_review/                                   ← 审查与建议
│   ├── product-advice.md
│   ├── plugin-arch-advice.md
│   ├── code-review-report.md
│   └── code-smells-report.md
└── 6_dev-notes/                                ← 开发笔记
    └── bootstrap-classloader-debug-report.md
```

---

## 版本对齐说明

| 文档 | 当前版本 | 对齐基准 | 差异 |
|------|---------|---------|------|
| 概念设计 | v0.8 | PRD v1.5 | 缺少规则环境绑定、RBAC 等 v2.0+ 变更 |
| PRD | v2.2 | 自身 | ✅ 最新 |
| UI 设计 | v1.5 | PRD v1.5 | 缺少 RBAC 用户管理界面等 v2.2 变更 |
| 原型 | v1.4 | UI 设计 v1.5 | 缺少撤销按钮、场景集管理等 v1.5 变更 |
