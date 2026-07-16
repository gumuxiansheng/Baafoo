# 审查与改进索引

> **审查日期**: 2026-06-14（全量）+ 2026-07-10（第二轮）+ 2026-07-16（工程文档审查）
> **审查人**: Alex (PM)

---

## 安全审查

| 文件 | 内容 | 日期 |
|------|------|------|
| [security/code-review-report-20260710.md](./security/code-review-report-20260710.md) | 第二轮代码审查：1C + 11H + 14M + 20L | 2026-07-10 |
| [security/security-fixes-20260710.md](./security/security-fixes-20260710.md) | 修复记录：1C + 7H + 10M 已修复，4 项不修复 | 2026-07-11 |
| [security/issue-tracker.md](./security/issue-tracker.md) | 问题闭环追踪表 | 2026-07-16 |

## 竞品分析

| 文件 | 内容 | 日期 |
|------|------|------|
| [competitive/comprehensive-20260716.md](./competitive/comprehensive-20260716.md) | 全面竞品分析 | 2026-07-16 |
| [competitive/mockforge-comparison.md](./competitive/mockforge-comparison.md) | MockForge 对比 & 战略建议 | 2026-06-18 |
| [competitive/mockforge-ai-migration-20260620.md](./competitive/mockforge-ai-migration-20260620.md) | AI 功能移植方案 | 2026-06-20 |
| [competitive/mockforge-ai-features-20260620.md](./competitive/mockforge-ai-features-20260620.md) | MockForge AI 模块源码分析 | 2026-06-20 |
| [competitive/mockforge-cli-migration-20260620.md](./competitive/mockforge-cli-migration-20260620.md) | CLI 对比与迁移分析 | 2026-06-20 |

## 可行性分析

| 文件 | 内容 | 日期 |
|------|------|------|
| [feasibility/new-protocols-20260620.md](./feasibility/new-protocols-20260620.md) | 新协议（gRPC/WS/MQTT/AMQP） | 2026-06-20 |
| [feasibility/multilang-sdk-20260620.md](./feasibility/multilang-sdk-20260620.md) | 多语言 SDK | 2026-06-20 |
| [feasibility/protocol-version-upgrade-20260619.md](./feasibility/protocol-version-upgrade-20260619.md) | MQ 协议版本兼容性 | 2026-06-19 |
| [feasibility/promotion-20260624.md](./feasibility/promotion-20260624.md) | 推广可行性 | 2026-06-24 |
| [feasibility/testcontainers-20260624.md](./feasibility/testcontainers-20260624.md) | Testcontainers 集成 | 2026-07-08 |

## 架构与产品建议

| 文件 | 内容 | 日期 |
|------|------|------|
| [architecture-improvement-todo.md](./architecture-improvement-todo.md) | 架构改进 TODO（P0-P3） | 2026-07-01 |
| [plugin-arch-advice.md](./plugin-arch-advice.md) | 插件架构决策 & 状态跟踪 | 2026-06-22 |
| [plugin-analysis-report.md](./plugin-analysis-report.md) | 插件完整性分析报告 | 2026-06-20 |
| [product-advice.md](./product-advice.md) | 产品方向建议 | 2026-05-31 |
| [review-feature-extension-prd.md](./review-feature-extension-prd.md) | 扩展 PRD 审查意见 | 2026-06-14 |
| [workmemo-audit-20260716.md](./workmemo-audit-20260716.md) | .workmemo 审查报告 | 2026-07-16 |
| [workmemo-doc-audit-20260717.md](./workmemo-doc-audit-20260717.md) | .workmemo 文档过时审查（P0-P3 共 15 项） | 2026-07-17 |

---

## 归档

| 路径 | 内容 |
|------|------|
| [archive/](./archive/) | 2026-06-14 全量审查后的已归档报告（16 份） |

---

## 与历史报告的差异对比

| 历史报告 | 最新报告 | 差异说明 |
|----------|----------|----------|
| 2026-05-31 第一轮审查 | 2026-07-10 第二轮审查 | 新增安全审查维度、跨模块分析 |
| 2026-06-18 MockForge 对比 | 2026-07-16 全面竞品分析 | 从单一竞品扩展到全市场 |
| 2026-06-20 可行性分析 | — | 6 份分析作为决策输入，已被 ADR 采纳 |
