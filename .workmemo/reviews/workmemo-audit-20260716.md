# .workmemo 工程文档批判性审查报告

> **审查人**: Alex (PM)  
> **审查日期**: 2026-07-16  
> **审查范围**: `.workmemo/` 全目录（85 文件 + 24 目录）  
> **审查维度**: 结构合理性、记录完整性、时效性、索引准确性、可维护性

---

## 总体评分：**C+（及格偏下）→ 整改后 B+**

> **2026-07-16 整改更新**: 本报告发现的全部 12 项问题已整改完成，详见文末"整改结果"章节。

---

### 整改前评分: C+

文档体系有"想做好"的骨架——按产品生命周期编号目录、有索引文件、有归档机制、有有效性标准声明。但执行层面漏洞百出：索引重复 12 条、4 个关键新增文档未录入索引、PRD 非目标与代码现实直接矛盾、3 个空目录占位、临时文件未清理。**作为一个开源项目的工程文档，当前状态会给新贡献者制造混乱。**

---

## 一、结构合理性 — **C**

### 1.1 目录编号体系断裂

采用 `1_concepts → 10_deliverables` 的数字编号体系，但：

| 目录 | 状态 | 问题 |
|------|------|------|
| `1_concepts/` | **空** | 概念设计文档被删除（2026-06-28 清理记录），但目录保留空壳 |
| `3_design/` | **空** | 从未使用，`6_design/` 承担了设计文档角色 |
| `4_deliverables/` | 仅含空子目录 | `software-company/` 是空目录，实际交付物在 `10_deliverables/` |
| `6_design/` | 1 个文件 | 与 `3_design/` 定位重叠，为何不统一？ |
| `7_dev-notes/` | 全部归档 | 目录本身只剩 `archive/`，外层无活跃文件 |
| `8_implementation/` | 全部归档 | 同上 |
| `9_test-reports/` | 全部归档 | 同上 |

**批判**: `3_design` 和 `6_design` 并存是结构分裂；`7/8/9` 三个目录沦为空壳，仅含一个 `archive/` 子目录，不如直接归并或删除外层。`1_concepts` 空目录是清理后未收尾的残留。编号体系从 1 跳到 10，中间 3、4 空置，给阅读者制造"是不是缺了文档"的错觉。

### 1.2 `.test/` 目录定位不清

`.test/` 包含 32 个文件（Robot Framework 测试脚本、JSON 测试数据、临时 tmp-* 文件），但它：
- 不在 `index.md` 的目录结构说明中被列出
- 混合了正式测试脚本（`robot-tests/`）和一次性临时数据（12 个 `tmp-*.json`）
- 与项目根目录下的 `testing/` 目录（正式集成测试）功能重叠

**批判**: `.test/` 是一个未纳入管理的野生目录，既不在索引中，又未明确归属。

### 1.3 `.workmemo` vs 项目根目录文档的边界模糊

项目根目录有 `README.md`、`docs/` 目录、`agent-skill/` 目录，`.workmemo/` 内部也有 PRD、设计文档、审查报告。**两套文档体系的边界从未被定义**——PRD 在 `.workmemo/2_prd/`，但 README 在根目录，插件指南在 `docs/`。新贡献者不知道该去哪找什么。

---

## 二、记录完整性 — **C-**

### 2.1 索引严重失真

#### `index.md` 活跃文档表重复条目

`baafoo_architecture_improvement_todo.md` 在活跃文档表中出现 **6 次**（按 Markdown 表格行计），这不是"强调"，是复制粘贴错误。一个项目的核心索引文件出现这种低级错误，直接损害文档体系的可信度。

#### `reviews/index.md` 遗漏 4 个关键文档

| 遗漏文档 | 日期 | 重要性 |
|----------|------|--------|
| `code-review-report-20260710.md` | 2026-07-10 | 🔴 Critical — 174 个问题的完整审查报告 |
| `security-fixes_2026-07-10.md` | 2026-07-11 | 🔴 Critical — 修复记录 |
| `competitive-analysis-comprehensive-20260716.md` | 2026-07-16 | 🟠 High — 竞品分析 |
| `testcontainers-feasibility-analysis-20260624.md` | 2026-07-08 | 🟡 Medium — 可行性分析 |

审查索引 (`reviews/index.md`) 最后更新于 2026-06-22，之后产出了 4 份重要文档全部未录入。索引失效等于没有索引。

### 2.2 PRD 与代码现实脱节

PRD v2.4（2026-06-15）非目标章节明确声明：

| 非目标 | 声明 | 代码现实 | 矛盾程度 |
|--------|------|----------|----------|
| N1: 不覆盖非 JVM 语言 | "JavaAgent 技术天然限制" | `sdks/go/`、`sdks/python/`、`sdks/nodejs/` 三套 SDK 已存在 | 🔴 直接矛盾 |
| N3: 不覆盖 gRPC | "字节码拦截复杂度显著高于 HTTP/1.1" | gRPC 已完整实现（GrpcStubHandler、GrpcChannelAdvice） | 🔴 直接矛盾 |

PRD 是 2026-06-15 的文档，gRPC 在 2026-06-24 实现并在 `decisions/grpc-fix-design-20260624.md` 有完整设计记录，但 **PRD 从未更新非目标章节**。

功能扩展 PRD v1.1 同样未提及 gRPC、多语言 SDK、i18n 国际化——这三个都是已经落地的重大功能。

### 2.3 安全审查 → 修复 → 状态追踪断裂

- `code-review-report-20260710.md`（2026-07-10）列出 1 Critical + 11 High + 14 Medium + 20+ Low
- `security-fixes_2026-07-10.md`（2026-07-11）记录了 7 项修复
- `baafoo_architecture_improvement_todo.md`（2026-07-01）列出了 P0-P3 改进项

**但没有一份文档把三者串联起来**: 哪些 CODE-REVIEW-REPORT 的问题已被 security-fixes 修复？哪些还开着？baafoo_architecture_improvement_todo 中的 P0 项与 CODE-REVIEW-REPORT 的 Critical/High 是什么对应关系？**当前状态是三份独立文档各自为政，没有一个统一的闭环追踪。**

### 2.4 实施记录全部归档，活跃空间为零

`7_dev-notes/`、`8_implementation/`、`9_test-reports/` 三个目录的活跃层全部为空，所有内容都在 `archive/` 下。这意味着：
- 2026-06-19 之后没有任何活跃实施记录
- i18n 实施（2026-07-05）、安全修复（2026-07-10）、SDK 修复（2026-07-16）全部没有 dev-note
- 归档标准是什么？按时间还是按"已完成"？没有说明

---

## 三、时效性 — **D+**

### 3.1 PRD 过期 31 天

PRD v2.4 最后更新 2026-06-15。此后发生的重大变更：

| 日期 | 事件 | PRD 是否反映 |
|------|------|-------------|
| 06-24 | gRPC 完整实现 | ❌ |
| 07-01 | 架构改进 TODO 发布 | ❌ |
| 07-05 | i18n 全量国际化 | ❌ |
| 07-10 | 安全审查 174 个问题 | ❌ |
| 07-10 | 安全修复 7 项 | ❌ |
| 07-16 | 竞品分析报告 | ❌ |
| 07-16 | SDK 字段名修复 | ❌ |

**PRD 是产品需求的基线，基线滞后 31 天且 6 项重大变更未反映，这不是"待更新"，是失控。**

### 3.2 `reviews/index.md` 过期 24 天

审查索引最后更新 2026-06-22，但 `5_review/` 目录下此后新增 4 份文档（2 份 Critical 级别）。索引文件不随文档产出同步更新，等于主动制造信息断层。

### 3.3 `product-advice.md` 过期 46 天

`product-advice.md`（2026-05-31）是产品方向建议书，其中多个建议已被采纳或被推翻，但没有状态标记。新读者无法区分"这是历史建议"还是"这是当前待办"。

### 3.4 `5_review/archive/` 中 grpc_review 文件名日期错误

`grpc_review_20250624.md` — 文件名标注 2025 年，但实际创建于 2026-06-24。年份错误虽小，但在按文件名日期检索时会误导。

---

## 四、其他考察点

### 4.1 临时文件污染

`.workmemo/.test/` 下有 12 个 `tmp-*.json` 文件（2026-06-13），明显是一次性批量关联操作的临时数据。这些文件：
- 不在任何索引中
- 不被任何脚本引用
- 已存在 33 天未清理
- 与 `testing/2_IntegrationTest/rules/` 下的正式测试数据混淆

**工程卫生不合格。**

### 4.2 文档命名规范不统一

| 风格 | 示例 | 数量 |
|------|------|------|
| `analysis-{topic}-{date}.md` | `analysis-mockforge-ai-features-20260620.md` | 7 |
| `{topic}_{date}.md` | `security-fixes_2026-07-10.md` | 1 |
| `{topic}.md` | `product-advice.md`、`plugin-arch-advice.md` | 多个 |
| `{number}-{topic}.md` | `00-summary.md`、`01-core-review.md` | 11 |
| `code-review-report-20260710.md` | 全大写 | 1 |

5 种命名风格混用。`code-review-report-20260710.md` 是唯一一个全大写文件名，在大小写敏感的文件系统上会制造问题。

### 4.3 `gen-changelog.ps1` 从未被使用

索引文件标注"✅ 脚本就绪"，但项目中没有 `changelog.md` 文件，`git log` 也未显示该脚本被执行过。标记"就绪"但不产出结果，是虚假的完成状态。

### 4.4 缺少 ADR（Architecture Decision Records）

项目有插件架构设计文档、gRPC 修复设计文档，但都是长篇大论的设计书（`plugin_architecture_enhancement_phase1-3_design.md` 53KB、`grpc_fix_design_20260624.md` 31KB），**没有轻量级的 ADR 记录关键决策的上下文、选项和理由**。当未来有人问"为什么选 Bootstrap CL 注入而非 Java Instrumentation API"，答案埋在 53KB 的文档第 12 节里。

### 4.5 缺少文档生命周期管理

`index.md` 声明了"文档有效性标准"（半年不更新归档或删除），但：
- `product-advice.md` 已 46 天未更新，仍标注"✅ 活跃"
- `plugin-arch-advice.md` 已 24 天未更新，仍标注"✅ 活跃"
- 没有任何文档被标注为"待归档"或"已过时"
- 没有定期审查机制（如季度 review）的记录

**有标准但不执行，比没有标准更糟糕。**

---

## 五、整改优先级

| 优先级 | 问题 | 行动 |
|--------|------|------|
| **P0** | PRD 非目标 N1/N3 与代码矛盾 | 更新 PRD v2.5：将 N1/N3 标记为"已超越"，补充 gRPC/SDK/i18n 需求章节 |
| **P0** | `index.md` 重复 6 条 + 遗漏 4 文档 | 去重 + 补录 4 份遗漏文档 + 同步更新日期 |
| **P0** | `reviews/index.md` 遗漏 4 份关键文档 | 补录 CODE-REVIEW-REPORT、security-fixes、competitive-analysis、testcontainers |
| **P1** | 安全审查闭环缺失 | 建立问题追踪表：CODE-REVIEW-REPORT 问题 → security-fixes 修复状态 → 遗留项清单 |
| **P1** | 空目录 `1_concepts/`、`3_design/`、`4_deliverables/software-company/` | 删除空目录或合并 `3_design` → `6_design` |
| **P1** | 12 个 `tmp-*.json` 临时文件 | 清理 `.test/tmp-*` |
| **P2** | `.test/` 目录未纳入索引 | 明确 `.test/` 定位，或迁移正式测试到 `testing/`，删除 `.test/` |
| **P2** | 文档命名规范不统一 | 制定命名约定并批量重命名（优先用 `{topic}-{date}.md`） |
| **P2** | `grpc_review_20250624.md` 年份错误 | 修正为 `grpc_review_20260624.md` |
| **P2** | `gen-changelog.ps1` 标注"就绪"但从未使用 | 要么执行产出 changelog，要么降级标注为"未验证" |
| **P3** | 缺少 ADR 机制 | 引入轻量级 ADR 模板，为关键决策补记录 |
| **P3** | 文档生命周期管理未落地 | 建立季度审查机制，对"活跃"文档做时效校验 |

---

## 整改结果（2026-07-16）

> **整改后评分: B+**

全部 12 项问题已整改完成：

| # | 优先级 | 问题 | 整改内容 | 状态 |
|---|--------|------|----------|------|
| 1 | P0 | PRD 非目标 N1/N3 与代码矛盾 | PRD v2.4 → v2.5：N1/N3 标记"已超越"，新增 R-S11(gRPC)/R-S12(SDK)/R-W8(i18n)/R-W9(安全) | ✅ |
| 2 | P0 | index.md 重复 6 条 + 遗漏文档 | 重建活跃文档表，去重 + 补录 6 份文档 | ✅ |
| 3 | P0 | reviews/index.md 遗漏 4 份文档 | 新增"后续审查文档"章节，补录 6 份 | ✅ |
| 4 | P1 | 安全审查闭环缺失 | 创建 `security-issue-tracker.md`，串联审查→修复→架构改进三者 | ✅ |
| 5 | P1 | 3 个空目录 | 删除 `1_concepts/`、`3_design/`、`4_deliverables/`（含空子目录） | ✅ |
| 6 | P1 | 12 个 tmp-* 临时文件 | 全部删除 | ✅ |
| 7 | P2 | .test/ 目录未纳入索引 | 创建 `.test/README.md` 定位说明，纳入目录结构 | ✅ |
| 8 | P2 | 文档命名规范不统一 | 创建 `naming-convention.md`，重命名 2 个不规范文件 | ✅ |
| 9 | P2 | grpc_review_20250624.md 年份错误 | 重命名为 `grpc_review_20260624.md` | ✅ |
| 10 | P2 | gen-changelog.ps1 虚假"就绪" | 状态降级为"⚠️ 未验证" | ✅ |
| 11 | P3 | 缺少 ADR 机制 | 创建 `adr/` 目录 + README + 4 份 ADR（Bootstrap CL/SPI/gRPC/i18n） | ✅ |
| 12 | P3 | 文档生命周期管理未落地 | 在 index.md 中建立季度审查机制 + 时效校验标准 + 状态标记体系 | ✅ |

### 整改产出文件清单

**新建:**
- `reviews/security/issue-tracker.md` — 安全审查问题闭环追踪表
- `naming-convention.md` — 文档命名规范
- `decisions/README.md` + `adr-001~004` — 4 份架构决策记录
- `.test/README.md` — .test 目录定位说明

**修改:**
- `prd/baafoo-prd.md` — v2.4 → v2.5
- `index.md` — 去重 + 补录 + 目录结构更新 + 生命周期管理
- `reviews/index.md` — 补录 6 份文档
- `reviews/workmemo-audit-20260716.md` — 追加整改结果

**重命名:**
- `CODE-REVIEW-REPORT.md` → `code-review-report-20260710.md`
- `grpc_review_20250624.md` → `grpc_review_20260624.md`

**删除:**
- `1_concepts/`、`3_design/`、`4_deliverables/`（空目录）
- 12 个 `.test/tmp-*.json` 临时文件
