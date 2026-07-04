# Baafoo 工作文档索引

> 按产品生命周期阶段组织，仅保留"为什么这么做"的有效文档。
> 最后更新：2026-07-05（补充遗漏文档 + 更新审查报告路径）

---

## 文档有效性标准

| 标准 | 处理方式 |
|------|---------|
| 能从代码/注释/Git 历史直接生成 | 不手写，搭自动化 |
| 解释"为什么这么做"（vs "代码做了什么"） | 值得保留 |
| 半年不更新会成为严重误导 | 归档或删除 |

---

## 活跃文档（为什么）

| 文档 | 说明 | 最后更新 |
|------|------|----------|
| [2_prd/baafoo-prd.md](2_prd/baafoo-prd.md) | 核心需求基线 v2.4 | 2026-06-15 |
| [2_prd/baafoo-feature-extension-prd.md](2_prd/baafoo-feature-extension-prd.md) | 功能扩展 PRD v1.1 | 2026-06-13 |
| [5_review/index.md](5_review/index.md) | 全量审查索引 | 2026-06-22 |
| [5_review/baafoo_architecture_improvement_todo.md](5_review/baafoo_architecture_improvement_todo.md) | 架构改进 TODO（P0-P3） | 2026-07-01 |
| [5_review/baafoo_architecture_improvement_todo.md](5_review/baafoo_architecture_improvement_todo.md) | 架构改进 TODO（P0-P3） | 2026-07-01 |
| [5_review/baafoo_architecture_improvement_todo.md](5_review/baafoo_architecture_improvement_todo.md) | 架构改进 TODO（P0-P3） | 2026-07-01 |
| [5_review/baafoo_architecture_improvement_todo.md](5_review/baafoo_architecture_improvement_todo.md) | 架构改进 TODO（P0-P3） | 2026-07-01 |
| [5_review/baafoo_architecture_improvement_todo.md](5_review/baafoo_architecture_improvement_todo.md) | 架构改进 TODO（P0-P3） | 2026-07-01 |
| [5_review/plugin-arch-advice.md](5_review/plugin-arch-advice.md) | 插件架构决策 & 状态跟踪 | 2026-06-22 |
| [5_review/plugin-analysis-report.md](5_review/plugin-analysis-report.md) | 插件完整性分析报告 | 2026-06-20 |
| [5_review/product-advice.md](5_review/product-advice.md) | 产品方向建议 | 2026-05-31 |
| [5_review/review-feature-extension-prd.md](5_review/review-feature-extension-prd.md) | 扩展 PRD 审查意见 | 2026-06-14 |
| [5_review/analysis-mockforge-comparison.md](5_review/analysis-mockforge-comparison.md) | MockForge 竞品对比 & 战略建议 | 2026-06-18 |
| [5_review/analysis-protocol-version-upgrade-20260619.md](5_review/analysis-protocol-version-upgrade-20260619.md) | MQ 协议版本兼容性升级分析 | 2026-06-19 |
| [5_review/analysis-new-protocols-feasibility-20260620.md](5_review/analysis-new-protocols-feasibility-20260620.md) | 新协议（gRPC/WS/MQTT/AMQP）可行性 | 2026-06-20 |
| [5_review/analysis-mockforge-ai-migration-20260620.md](5_review/analysis-mockforge-ai-migration-20260620.md) | AI 功能移植方案分析 | 2026-06-20 |
| [5_review/analysis-mockforge-ai-features-20260620.md](5_review/analysis-mockforge-ai-features-20260620.md) | MockForge AI 模块源码分析 | 2026-06-20 |
| [5_review/analysis-mockforge-cli-migration-20260620.md](5_review/analysis-mockforge-cli-migration-20260620.md) | CLI 对比与迁移分析 | 2026-06-20 |
| [5_review/analysis-multilang-sdk-feasibility-20260620.md](5_review/analysis-multilang-sdk-feasibility-20260620.md) | 多语言 SDK 方案研究 | 2026-06-20 |
| [5_review/promotion-feasibility-analysis-20260624.md](5_review/promotion-feasibility-analysis-20260624.md) | 推广可行性（Docker/Helm/Plugin） | 2026-06-24 |
| [5_review/testcontainers-feasibility-analysis-20260624.md](5_review/testcontainers-feasibility-analysis-20260624.md) | Testcontainers 集成分析 | 2026-06-28 |
| [6_design/grpc_fix_design_20260624.md](6_design/grpc_fix_design_20260624.md) | gRPC P0 修复设计（含方案选型理由） | 2026-06-24 |
| [10_deliverables/plugin_architecture_enhancement_phase1-3_design.md](10_deliverables/plugin_architecture_enhancement_phase1-3_design.md) | 插件架构增强 Phase 1-3 设计 | 2026-06-25 |

---

## 自动生成替代（不再手写）

| 原文件 | 替代方案 | 状态 |
|--------|---------|------|
| `changelog.md` | `pwsh .workmemo\gen-changelog.ps1` 从 git log 生成 | ✅ 脚本就绪 |
| `9_test-reports/archive/*.md` | CI/测试框架输出测试报告 | 待搭建 |

---

## 目录结构

```
.workmemo/
├── index.md                              ← 本文件
├── gen-changelog.ps1                     ← changelog 自动生成脚本
├── 1_concepts/                           ← （空）
├── 2_prd/                                ← 产品需求
│   ├── baafoo-prd.md                     ← ✅ 核心基线
│   └── baafoo-feature-extension-prd.md   ← ✅ 扩展基线
├── 3_design/                             ← （空）
├── 4_deliverables/                       ← （空）
├── 5_review/                             ← 审查与建议
│   ├── index.md                          ← ✅ 审查索引
│   ├── baafoo_architecture_improvement_todo.md ← ✅ 架构改进 TODO
│   ├── plugin-arch-advice.md             ← ✅ 活跃
│   ├── plugin-analysis-report.md         ← ✅ 活跃
│   ├── product-advice.md                 ← ✅ 活跃
│   ├── review-feature-extension-prd.md   ← ✅ 活跃
│   ├── analysis-*.md                     ← ✅ 可行性分析(8个)
│   ├── promotion-feasibility-analysis-*.md  ← ✅ 活跃
│   ├── testcontainers-feasibility-analysis-*.md  ← ✅ 活跃
│   └── archive/                          ← 📦 已归档审查报告(16)
├── 6_design/                             ← 设计方案
│   └── grpc_fix_design_20260624.md       ← ✅ 活跃
├── 7_dev-notes/                          ← 📦 全部已归档
│   └── archive/                          ← 27 个实施记录
├── 8_implementation/                     ← 📦 全部已归档
│   └── archive/                          ← 实施记录
├── 9_test-reports/                       ← 📦 全部已归档
│   └── archive/                          ← 测试报告
└── 10_deliverables/                      ← 架构交付物
    └── plugin_architecture_enhancement_phase1-3_design.md ← ✅ 活跃
```

---

## 清理记录（2026-07-05）

| 操作 | 数量 | 范围 |
|------|------|------|
| 补充遗漏 | 1 个 | 5_review/baafoo_architecture_improvement_todo.md |
| 修复链向 | 11 个 | 5_review/index.md 中审查报告路径（→ archive/） |
| 更新日期 | 2 个 | index.md 主日期 |

## 清理记录（2026-06-28）

| 操作 | 数量 | 范围 |
|------|------|------|
| 删除（过时） | 6 个 | concept-design, competitive-analysis, prototype.html, ui-framework-design, architecture-review, archive/ |
| 归档（"做了什么"类噪音） | 47 个 | 7_dev-notes/ (27), 5_review 审查记录 (16), 9_test-reports (2), 8_implementation (1), changelog.md |
| 保留（"为什么"类有效） | 18 个 | PRD, 架构决策, 可行性分析, 设计文档 |
