# Baafoo 工作文档索引

> 按文档类型组织，仅保留"为什么这么做"的有效文档。
> 最后更新：2026-07-17（结构重构：语义化目录 + 子域拆分）

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
| 每季度 | PM/技术负责人 | 审查活跃文档表，标注超过 90 天未更新的文档 |
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
| 2026-07-16 | Alex (PM) | 首次全量审查，产出 `reviews/workmemo-audit-20260716.md` |

---

## 活跃文档（为什么）

### 产品需求

| 文档 | 说明 | 最后更新 |
|------|------|----------|
| [prd/baafoo-prd.md](prd/baafoo-prd.md) | 核心需求基线 v2.5 | 2026-07-16 |
| [prd/baafoo-feature-extension-prd.md](prd/baafoo-feature-extension-prd.md) | 功能扩展 PRD v1.1 | 2026-06-13 |

### 架构决策（ADR + 设计文档）

| 文档 | 说明 | 最后更新 |
|------|------|----------|
| [decisions/README.md](decisions/README.md) | ADR 规范说明 | 2026-07-16 |
| [decisions/adr-001-bootstrap-classloader-injection.md](decisions/adr-001-bootstrap-classloader-injection.md) | Bootstrap CL 注入方案 | 2026-05-30 |
| [decisions/adr-002-spi-plugin-architecture.md](decisions/adr-002-spi-plugin-architecture.md) | SPI 插件架构 vs 硬编码 | 2026-06-20 |
| [decisions/adr-003-grpc-interception-strategy.md](decisions/adr-003-grpc-interception-strategy.md) | gRPC 拦截策略 | 2026-06-24 |
| [decisions/adr-004-i18n-accept-language.md](decisions/adr-004-i18n-accept-language.md) | i18n: Accept-Language 头驱动 | 2026-07-05 |
| [decisions/grpc-fix-design-20260624.md](decisions/grpc-fix-design-20260624.md) | gRPC P0 修复设计 | 2026-06-24 |
| [decisions/plugin-architecture-enhancement-phase1-3-design.md](decisions/plugin-architecture-enhancement-phase1-3-design.md) | 插件架构增强 Phase 1-3 | 2026-06-25 |

### 审查与改进

| 文档 | 说明 | 最后更新 |
|------|------|----------|
| [reviews/index.md](reviews/index.md) | 审查索引 | 2026-07-16 |
| [reviews/architecture-improvement-todo.md](reviews/architecture-improvement-todo.md) | 架构改进 TODO（P0-P3） | 2026-07-01 |
| [reviews/plugin-arch-advice.md](reviews/plugin-arch-advice.md) | 插件架构决策 & 状态跟踪 | 2026-06-22 |
| [reviews/plugin-analysis-report.md](reviews/plugin-analysis-report.md) | 插件完整性分析报告 | 2026-06-20 |
| [reviews/product-advice.md](reviews/product-advice.md) | 产品方向建议 | 2026-05-31 |
| [reviews/review-feature-extension-prd.md](reviews/review-feature-extension-prd.md) | 扩展 PRD 审查意见 | 2026-06-14 |
| [reviews/workmemo-audit-20260716.md](reviews/workmemo-audit-20260716.md) | .workmemo 工程文档审查报告 | 2026-07-16 |

#### 安全审查

| 文档 | 说明 | 最后更新 |
|------|------|----------|
| [reviews/security/code-review-report-20260710.md](reviews/security/code-review-report-20260710.md) | 代码审查报告（1C+11H+14M+20L） | 2026-07-10 |
| [reviews/security/security-fixes-20260710.md](reviews/security/security-fixes-20260710.md) | 安全审查修复记录 | 2026-07-11 |
| [reviews/security/issue-tracker.md](reviews/security/issue-tracker.md) | 安全审查问题闭环追踪表 | 2026-07-16 |

#### 竞品分析

| 文档 | 说明 | 最后更新 |
|------|------|----------|
| [reviews/competitive/comprehensive-20260716.md](reviews/competitive/comprehensive-20260716.md) | 全面竞品分析报告 | 2026-07-16 |
| [reviews/competitive/mockforge-comparison.md](reviews/competitive/mockforge-comparison.md) | MockForge 竞品对比 & 战略建议 | 2026-06-18 |
| [reviews/competitive/mockforge-ai-migration-20260620.md](reviews/competitive/mockforge-ai-migration-20260620.md) | AI 功能移植方案 | 2026-06-20 |
| [reviews/competitive/mockforge-ai-features-20260620.md](reviews/competitive/mockforge-ai-features-20260620.md) | MockForge AI 模块源码分析 | 2026-06-20 |
| [reviews/competitive/mockforge-cli-migration-20260620.md](reviews/competitive/mockforge-cli-migration-20260620.md) | CLI 对比与迁移分析 | 2026-06-20 |

#### 可行性分析

| 文档 | 说明 | 最后更新 |
|------|------|----------|
| [reviews/feasibility/new-protocols-20260620.md](reviews/feasibility/new-protocols-20260620.md) | 新协议（gRPC/WS/MQTT/AMQP）可行性 | 2026-06-20 |
| [reviews/feasibility/multilang-sdk-20260620.md](reviews/feasibility/multilang-sdk-20260620.md) | 多语言 SDK 方案研究 | 2026-06-20 |
| [reviews/feasibility/protocol-version-upgrade-20260619.md](reviews/feasibility/protocol-version-upgrade-20260619.md) | MQ 协议版本兼容性升级 | 2026-06-19 |
| [reviews/feasibility/promotion-20260624.md](reviews/feasibility/promotion-20260624.md) | 推广可行性（Docker/Helm/Plugin） | 2026-06-24 |
| [reviews/feasibility/testcontainers-20260624.md](reviews/feasibility/testcontainers-20260624.md) | Testcontainers 集成分析 | 2026-07-08 |

---

## 自动生成替代（不再手写）

| 原文件 | 替代方案 | 状态 |
|--------|---------|------|
| `changelog.md` | `pwsh .workmemo\gen-changelog.ps1` 从 git log 生成 | ⚠️ 未验证（脚本存在但从未执行产出） |
| `archive/test-reports/*.md` | CI/测试框架输出测试报告 | 待搭建 |

---

## 目录结构

```
.workmemo/
├── index.md                              ← 本文件
├── naming-convention.md                  ← 文档命名规范
├── gen-changelog.ps1                     ← changelog 自动生成脚本（未验证）
│
├── prd/                                  ← 产品需求
│   ├── baafoo-prd.md                     ← ✅ 核心基线 v2.5
│   └── baafoo-feature-extension-prd.md   ← ✅ 扩展基线 v1.1
│
├── decisions/                            ← 架构决策（ADR + 设计文档）
│   ├── README.md                         ← ADR 规范
│   ├── adr-001-bootstrap-classloader-injection.md
│   ├── adr-002-spi-plugin-architecture.md
│   ├── adr-003-grpc-interception-strategy.md
│   ├── adr-004-i18n-accept-language.md
│   ├── grpc-fix-design-20260624.md       ← gRPC 修复设计
│   └── plugin-architecture-enhancement-phase1-3-design.md
│
├── reviews/                              ← 审查与改进
│   ├── index.md                          ← 审查索引
│   ├── architecture-improvement-todo.md  ← 架构改进 TODO
│   ├── plugin-arch-advice.md             ← 插件架构建议
│   ├── plugin-analysis-report.md         ← 插件分析报告
│   ├── product-advice.md                 ← 产品方向建议
│   ├── review-feature-extension-prd.md   ← 扩展 PRD 审查
│   ├── workmemo-audit-20260716.md        ← 文档审查报告
│   ├── security/                         ← 安全审查
│   │   ├── code-review-report-20260710.md
│   │   ├── security-fixes-20260710.md
│   │   └── issue-tracker.md
│   ├── competitive/                      ← 竞品分析
│   │   ├── comprehensive-20260716.md
│   │   ├── mockforge-comparison.md
│   │   ├── mockforge-ai-migration-20260620.md
│   │   ├── mockforge-ai-features-20260620.md
│   │   └── mockforge-cli-migration-20260620.md
│   ├── feasibility/                      ← 可行性分析
│   │   ├── new-protocols-20260620.md
│   │   ├── multilang-sdk-20260620.md
│   │   ├── protocol-version-upgrade-20260619.md
│   │   ├── promotion-20260624.md
│   │   └── testcontainers-20260624.md
│   └── archive/                          ← 📦 已归档审查报告
│
├── archive/                              ← 📦 历史归档（只读）
│   ├── dev-notes/                        ← 27 个实施记录
│   ├── implementation/                   ← 1 个实施记录
│   └── test-reports/                     ← 2 个测试报告
│
└── .test/                                ← 早期手动测试数据（gitignore，待迁移至 testing/）
    └── README.md
```

---

## 清理记录

### 2026-07-17 结构重构

| 操作 | 数量 | 范围 |
|------|------|------|
| 去编号前缀 | 7 个目录 | 2_prd→prd, 5_review→reviews, 6_design→decisions, 7_dev-notes→archive, 8_implementation→archive, 9_test-reports→archive, 10_deliverables→decisions, adr→decisions |
| 子域拆分 | 3 个 | reviews/ 拆为 security/ + competitive/ + feasibility/ |
| 合并僵尸目录 | 3 个 | 7_dev-notes + 8_implementation + 9_test-reports → archive/ |
| 合并决策文档 | 3 个目录 | adr/ + 6_design/ + 10_deliverables/ → decisions/ |
| 文件重命名 | 8 个 | 移除多余前缀（analysis-、promotion-feasibility- 等） |

### 2026-07-16 全量整改

| 操作 | 数量 | 范围 |
|------|------|------|
| 去重 | 5 条 | index.md 活跃文档表重复条目 |
| 补录 | 6 个 | code-review-report, security-fixes, competitive-analysis, testcontainers, workmemo-audit, security-issue-tracker |
| 删除空目录 | 3 个 | 1_concepts/, 3_design/, 4_deliverables/ |
| 清理临时文件 | 12 个 | .test/tmp-*.json |
| 文件重命名 | 2 个 | grpc_review 年份修正, CODE-REVIEW-REPORT 命名规范 |
| 新建 | 6 个 | adr/ + 4 份 ADR + security-issue-tracker |
| PRD 更新 | 1 份 | v2.4 → v2.5 |

### 2026-07-05

| 操作 | 数量 | 范围 |
|------|------|------|
| 补充遗漏 | 1 个 | architecture-improvement-todo |
| 修复链向 | 11 个 | 审查报告路径（→ archive/） |

### 2026-06-28

| 操作 | 数量 | 范围 |
|------|------|------|
| 删除（过时） | 6 个 | concept-design 等早期文档 |
| 归档 | 47 个 | dev-notes, 审查记录, 测试报告 |
| 保留 | 18 个 | PRD, 架构决策, 可行性分析 |
