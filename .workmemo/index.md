# Baafoo 工作文档索引

> 按产品生命周期阶段组织，仅保留"为什么这么做"的有效文档。
> 最后更新：2026-07-16（全量整改：去重 + 补录 + 空目录清理 + ADR 引入）

---

## 文档有效性标准

| 标准 | 处理方式 |
|------|---------|
| 能从代码/注释/Git 历史直接生成 | 不手写，搭自动化 |
| 解释"为什么这么做"（vs "代码做了什么"） | 值得保留 |
| 半年不更新会成为严重误导 | 归档或删除 |

## 文档生命周期管理

### 活跃文档时效校验

| 频率 | 执行者 | 操作 |
|------|--------|------|
| 每季度 | PM/技术负责人 | 审查"活跃文档"表，标注超过 90 天未更新的文档 |
| 超过 90 天 | 文档负责人 | 更新内容或标注"待归档" |
| 超过 180 天 | PM | 强制归档或删除 |

### 状态标记

| 标记 | 含义 |
|------|------|
| ✅ 活跃 | 90 天内有更新，内容准确 |
| ⚠️ 待归档 | 90-180 天未更新，需审查 |
| 📦 已归档 | 超过 180 天或已被替代 |

### 季度审查记录

| 日期 | 审查人 | 审查结果 |
|------|--------|----------|
| 2026-07-16 | Alex (PM) | 首次全量审查，产出 `workmemo_audit_20260716.md` |

---

## 活跃文档（为什么）

| 文档 | 说明 | 最后更新 |
|------|------|----------|
| [2_prd/baafoo-prd.md](2_prd/baafoo-prd.md) | 核心需求基线 v2.5 | 2026-07-16 |
| [2_prd/baafoo-feature-extension-prd.md](2_prd/baafoo-feature-extension-prd.md) | 功能扩展 PRD v1.1 | 2026-06-13 |
| [5_review/index.md](5_review/index.md) | 全量审查索引 | 2026-07-16 |
| [5_review/code-review-report-20260710.md](5_review/code-review-report-20260710.md) | 代码审查报告（1C+11H+14M+20L） | 2026-07-10 |
| [5_review/security-fixes_2026-07-10.md](5_review/security-fixes_2026-07-10.md) | 安全审查修复记录 | 2026-07-11 |
| [5_review/competitive-analysis-comprehensive-20260716.md](5_review/competitive-analysis-comprehensive-20260716.md) | 全面竞品分析报告 | 2026-07-16 |
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
| [5_review/testcontainers-feasibility-analysis-20260624.md](5_review/testcontainers-feasibility-analysis-20260624.md) | Testcontainers 集成分析 | 2026-07-08 |
| [5_review/workmemo_audit_20260716.md](5_review/workmemo_audit_20260716.md) | .workmemo 工程文档审查报告 | 2026-07-16 |
| [5_review/security-issue-tracker.md](5_review/security-issue-tracker.md) | 安全审查问题闭环追踪表 | 2026-07-16 |
| [6_design/grpc_fix_design_20260624.md](6_design/grpc_fix_design_20260624.md) | gRPC P0 修复设计（含方案选型理由） | 2026-06-24 |
| [10_deliverables/plugin_architecture_enhancement_phase1-3_design.md](10_deliverables/plugin_architecture_enhancement_phase1-3_design.md) | 插件架构增强 Phase 1-3 设计 | 2026-06-25 |
| [adr/](adr/) | 架构决策记录（ADR） | 2026-07-16 |
---

## 自动生成替代（不再手写）

| 原文件 | 替代方案 | 状态 |
|--------|---------|------|
| `changelog.md` | `pwsh .workmemo\gen-changelog.ps1` 从 git log 生成 | ⚠️ 未验证（脚本存在但从未执行产出） |
| `9_test-reports/archive/*.md` | CI/测试框架输出测试报告 | 待搭建 |

---

## 目录结构

```
.workmemo/
├── index.md                              ← 本文件
├── gen-changelog.ps1                     ← changelog 自动生成脚本
├── 2_prd/                                ← 产品需求
│   ├── baafoo-prd.md                     ← ✅ 核心基线
│   └── baafoo-feature-extension-prd.md   ← ✅ 扩展基线
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
├── 10_deliverables/                      ← 架构交付物
│   └── plugin_architecture_enhancement_phase1-3_design.md ← ✅ 活跃
├── adr/                                  ← 架构决策记录 (ADR)
│   ├── README.md                         ← ADR 规范说明
│   ├── adr-001-bootstrap-classloader-injection.md
│   ├── adr-002-spi-plugin-architecture.md
│   ├── adr-003-grpc-interception-strategy.md
│   └── adr-004-i18n-accept-language.md
└── .test/                                ← 早期手动测试数据（待迁移至 testing/）
    └── README.md                        ← 目录定位说明
```

---


## 清理记录（2026-07-16 全量整改）

| 操作 | 数量 | 范围 |
|------|------|------|
| 去重 | 5 条 | index.md 活跃文档表重复条目 |
| 补录 | 6 个 | CODE-REVIEW-REPORT、security-fixes、competitive-analysis、testcontainers、workmemo_audit、security-issue-tracker |
| 删除空目录 | 3 个 | 1_concepts/、3_design/、4_deliverables/software-company/ |
| 合并目录 | 1 个 | 3_design → 6_design（3_design 已删除） |
| 清理临时文件 | 12 个 | .test/tmp-*.json |
| 文件重命名 | 2 个 | grpc_review_20250624.md → grpc_review_20260624.md（年份修正）; CODE-REVIEW-REPORT.md → code-review-report-20260710.md（命名规范） |
| 新建 | 6 个 | adr/ 目录 + 4 份 ADR + security-issue-tracker.md |
| PRD 更新 | 1 份 | v2.4 → v2.5（N1/N3 修订 + R-S11/R-S12/R-W8/R-W9 新增） |
| gen-changelog 状态 | 1 项 | "✅ 脚本就绪" → "⚠️ 未验证" |

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
