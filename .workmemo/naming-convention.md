# .workmemo 文档命名规范

> **生效日期**: 2026-07-16

## 命名规则

所有新建文档统一采用 `{topic}-{date}.md` 格式：

- `topic`: 小写英文，连字符分隔（如 `security-fixes`、`competitive-analysis`）
- `date`: `YYYYMMDD` 格式（如 `20260716`）
- 扩展名: `.md`

## 历史文件

已存在的文档不强制重命名，但以下例外已修正：

| 原文件名 | 新文件名 | 原因 |
|----------|----------|------|
| `grpc_review_20250624.md` | `grpc_review_20260624.md` | 年份错误（2025→2026） |
| `CODE-REVIEW-REPORT.md` | `code-review-report-20260710.md` | 全大写不符合规范 |

## 目录命名

- 数字前缀: `1_concepts`、`2_prd`（按产品生命周期阶段编号）
- 全小写英文，连字符分隔
- `archive/` 子目录统一命名

## 特殊目录

| 目录 | 用途 |
|------|------|
| `adr/` | 架构决策记录（Architecture Decision Records） |
| `archive/` | 已归档文档，不再活跃维护 |
