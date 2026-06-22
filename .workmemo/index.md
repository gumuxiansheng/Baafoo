# Baafoo 工作文档索引

> 本目录存放 Baafoo 项目的工作记录文档，按产品生命周期阶段组织。
> 最后更新：2026-06-22

---

## 文档状态图例

| 标记 | 含义 |
|------|------|
| ✅ 活跃 | 内容与当前代码一致，持续维护 |
| ⚠️ 过时 | 内容已过时或被后续文档取代，仅供参考 |
| 📦 存档 | 历史决策记录，不需要更新 |

---

## 1. 概念与需求

| 文档 | 版本 | 状态 | 说明 |
|------|------|------|------|
| [1_concepts/baafoo-concept-design.md](1_concepts/baafoo-concept-design.md) | v0.8 | ⚠️ | 系统整体概念设计（落后 PRD v2.4 两个大版本） |
| [2_prd/baafoo-prd.md](2_prd/baafoo-prd.md) | v2.4 | ✅ | **核心需求基线** — 用户故事、功能清单、验收标准 |
| [2_prd/baafoo-feature-extension-prd.md](2_prd/baafoo-feature-extension-prd.md) | v1.1 | ✅ | 功能扩展 PRD — OpenAPI/Faker/有状态Mock/故障注入/GraphQL/Chaos |
| [2_prd/competitive-analysis.md](2_prd/competitive-analysis.md) | v2.0 | 📦 | 竞品分析（WireMock/Hoverfly/Mountebank/MockForge） |

---

## 2. 设计文档

| 文档 | 版本 | 状态 | 说明 |
|------|------|------|------|
| [3_design/ui-framework-design.md](3_design/ui-framework-design.md) | v1.5 | ⚠️ | Web 控制台 UI 设计（未反映 23-web-console-ui 前端大更新） |
| [4_deliverables/software-company/architecture-review.md](4_deliverables/software-company/architecture-review.md) | — | 📦 | 2025-07 早期架构审查 |

---

## 3. 插件系统（活跃）

插件系统是近期最活跃的开发领域，以下文档构成完整链路：

| 文档 | 状态 | 说明 |
|------|------|------|
| [5_review/plugin-arch-advice.md](5_review/plugin-arch-advice.md) | ✅ | **架构决策文档** — P0-P4 实施状态跟踪 |
| [5_review/plan-plugin-improvement-tasks.md](5_review/plan-plugin-improvement-tasks.md) | ✅ | P0-P4 共 31 项任务清单 |
| [5_review/report-plugin-improvement-status.md](5_review/report-plugin-improvement-status.md) | ✅ | 逐项实施状态核查 |
| [5_review/plugin-analysis-report.md](5_review/plugin-analysis-report.md) | 📦 | 插件系统完整性审查 & MockForge 对比（初始分析） |

---

## 4. 代码审查

### 4a. 全量审查（2026-06-14，部分问题已修复）

| 文档 | 说明 |
|------|------|
| [5_review/index.md](5_review/index.md) | 全量审查入口（174+37 个问题） |
| [5_review/00-summary.md](5_review/00-summary.md) | 汇总报告 |
| [5_review/01-core-review.md](5_review/01-core-review.md) | baafoo-core（23 个问题） |
| [5_review/02-server-review.md](5_review/02-server-review.md) | baafoo-server（36 个问题） |
| [5_review/03-agent-review.md](5_review/03-agent-review.md) | baafoo-agent（29 个问题） |
| [5_review/04-plugin-cli-test-review.md](5_review/04-plugin-cli-test-review.md) | Plugin API/CLI/测试（24 个问题） |
| [5_review/05-web-review.md](5_review/05-web-review.md) | Web 前端（25 个问题） |
| [5_review/06-build-config-review.md](5_review/06-build-config-review.md) | 构建与配置（15 个问题） |
| [5_review/07-combined-critical.md](5_review/07-combined-critical.md) | Critical 级别汇总（17 个） |
| [5_review/08-mapper-review.md](5_review/08-mapper-review.md) | MyBatis Mapper（17 个问题） |
| [5_review/09-test-review.md](5_review/09-test-review.md) | 测试代码（23 个问题） |
| [5_review/10-cicd-review.md](5_review/10-cicd-review.md) | CI/CD 与构建脚本（14 个问题） |

### 4b. 早期审查（已被全量审查取代）

| 文档 | 状态 | 说明 |
|------|------|------|
| [5_review/archive/review-code-20260531.md](5_review/archive/review-code-20260531.md) | ⚠️ | 5-31 首轮审查（6 个模块），已被全量审查取代 |
| [5_review/archive/review-code-smells-20260531.md](5_review/archive/review-code-smells-20260531.md) | ⚠️ | 5-31 代码坏味道（26 项） |
| [5_review/archive/review-architecture-20260603.md](5_review/archive/review-architecture-20260603.md) | ⚠️ | 6-03 架构分析，插件系统已大幅重构 |
| [5_review/archive/review-code-20260603.md](5_review/archive/review-code-20260603.md) | ⚠️ | 6-03 第二轮审查（7 个模块） |

### 4c. 产品建议与 PRD 审查

| 文档 | 状态 | 说明 |
|------|------|------|
| [5_review/product-advice.md](5_review/product-advice.md) | 📦 | 产品建议书（P0-P2 分级方向建议） |
| [5_review/review-feature-extension-prd.md](5_review/review-feature-extension-prd.md) | 📦 | 功能扩展 PRD v1.0 审查意见 |

---

## 5. MockForge 对比与迁移分析

| 文档 | 状态 | 说明 |
|------|------|------|
| [5_review/analysis-mockforge-comparison.md](5_review/analysis-mockforge-comparison.md) | 📦 | P0 移植规划（P0 已 100% 完成） |
| [5_review/report-p0-migration-verification-20260619.md](5_review/report-p0-migration-verification-20260619.md) | 📦 | P0 验收里程碑 |
| [5_review/review-phase1-phase2-code-20260619.md](5_review/review-phase1-phase2-code-20260619.md) | 📦 | Kafka/Pulsar 协议升级专项审查 |

---

## 6. 未来规划（尚未启动）

| 文档 | 说明 |
|------|------|
| [5_review/analysis-protocol-version-upgrade-20260619.md](5_review/analysis-protocol-version-upgrade-20260619.md) | MQ 协议版本兼容性升级需求 |
| [5_review/analysis-new-protocols-feasibility-20260620.md](5_review/analysis-new-protocols-feasibility-20260620.md) | 4 种新协议可行性研究 |
| [5_review/analysis-mockforge-ai-migration-20260620.md](5_review/analysis-mockforge-ai-migration-20260620.md) | AI 功能移植方案 |
| [5_review/analysis-mockforge-ai-features-20260620.md](5_review/analysis-mockforge-ai-features-20260620.md) | mockforge AI 模块源码分析 |
| [5_review/analysis-mockforge-cli-migration-20260620.md](5_review/analysis-mockforge-cli-migration-20260620.md) | CLI 对比分析 |
| [5_review/analysis-multilang-sdk-feasibility-20260620.md](5_review/analysis-multilang-sdk-feasibility-20260620.md) | 多语言 SDK 方案研究 |

---

## 7. 开发笔记（6_dev-notes/）

全部为已完成的实施记录，按时间顺序排列。

| 编号 | 文件 | 说明 |
|------|------|------|
| 00 | [00-implementation-plan.md](6_dev-notes/00-implementation-plan.md) | 基于 PRD v2.3 的实施计划（已被实际开发取代） |
| 01 | [01-ra1-fail-open.md](6_dev-notes/01-ra1-fail-open.md) | R-A1 fail-open 配置 |
| 02 | [02-ra4-kafka-consumer.md](6_dev-notes/02-ra4-kafka-consumer.md) | R-A4 KafkaConsumer 拦截 |
| 03 | [03-ra5-tdmq-sdk.md](6_dev-notes/03-ra5-tdmq-sdk.md) | R-A5 TDMQ SDK 支持 |
| 04 | [04-ra6-jms-interception.md](6_dev-notes/04-ra6-jms-interception.md) | R-A6 JMS ConnectionFactory |
| 05 | [05-ra8-consul-webclient.md](6_dev-notes/05-ra8-consul-webclient.md) | R-A8 Consul WebClient |
| 06 | [06-ra10-record-mode.md](6_dev-notes/06-ra10-record-mode.md) | R-A10 录制模式 |
| 07 | [07-rs3-tcp-regex-multi-round.md](6_dev-notes/07-rs3-tcp-regex-multi-round.md) | TCP Regex 匹配 + 多轮交互 |
| 08 | [08-rs4-kafka-mock-broker.md](6_dev-notes/08-rs4-kafka-mock-broker.md) | Kafka Mock Broker |
| 09 | [09-rs5-pulsar-mock-broker.md](6_dev-notes/09-rs5-pulsar-mock-broker.md) | Pulsar Mock Broker |
| 10 | [10-rs6-jms-mock-broker.md](6_dev-notes/10-rs6-jms-mock-broker.md) | JMS Mock Broker |
| 11 | [11-rs77-rbac.md](6_dev-notes/11-rs77-rbac.md) | RBAC 权限控制 |
| 12 | [12-rs8-recording-cleanup.md](6_dev-notes/12-rs8-recording-cleanup.md) | 录制数据自动清理 |
| 13 | [13-rs9-har-export.md](6_dev-notes/13-rs9-har-export.md) | HAR 1.2 导出 |
| 14 | [14-rs76-interactive-init.md](6_dev-notes/14-rs76-interactive-init.md) | baafoo init 交互式 |
| 15 | [15-ui-test-bugfix.md](6_dev-notes/15-ui-test-bugfix.md) | UI 测试问题修复 |
| 16 | [16-faker-increment.md](6_dev-notes/16-faker-increment.md) | Faker 增量补充 |
| 17 | [17-graphql-matchcondition.md](6_dev-notes/17-graphql-matchcondition.md) | GraphQL MatchCondition |
| 18 | [18-stateful-mock.md](6_dev-notes/18-stateful-mock.md) | 有状态 Mock 轻量方案 |
| 19 | [19-fault-injection-phase1.md](6_dev-notes/19-fault-injection-phase1.md) | 故障注入 Phase 1 |
| 20 | [20-openapi-import-phase1.md](6_dev-notes/20-openapi-import-phase1.md) | OpenAPI 导入 Phase 1 |
| 20a | [20a-code-review-fixes.md](6_dev-notes/20a-code-review-fixes.md) | 代码审查修复（7 项） |
| 21 | [21-fault-injection-phase2.md](6_dev-notes/21-fault-injection-phase2.md) | 故障注入 Phase 2 |
| 22 | [22-chaos-engineering.md](6_dev-notes/22-chaos-engineering.md) | Chaos 工程场景化 |
| 23 | [23-web-console-ui.md](6_dev-notes/23-web-console-ui.md) | Web 控制台 UI 更新 |
| 24 | [24-code-review-fixes.md](6_dev-notes/24-code-review-fixes.md) | 代码审查修复 S1-S12 |
| — | [bootstrap-classloader-debug-report.md](6_dev-notes/bootstrap-classloader-debug-report.md) | Bootstrap CL 双加载调试 |

---

## 8. 测试报告

| 文档 | 说明 |
|------|------|
| [7_test-reports/00-ui-test-summary.md](7_test-reports/00-ui-test-summary.md) | 2026-06-18 UI 测试摘要 |
| [7_test-reports/full-ui-test-report.md](7_test-reports/full-ui-test-report.md) | 同日完整 UI 测试报告 |
| [.test/API_Test_Report.md](.test/API_Test_Report.md) | 2026-05-31 API 批量测试 |
| [.test/TEST-MANUAL.md](.test/TEST-MANUAL.md) | 全协议测试手册 v1.0 |
| [.test/TEST-REPORT.md](.test/TEST-REPORT.md) | 2026-06-15 全范围集成测试 |
| [.test/robot-tests/README.md](.test/robot-tests/README.md) | Robot Framework 测试基础设施 |

---

## 目录结构

```
.workmemo/
├── index.md                        ← 本文件
├── changelog.md                    ← 变更日志
├── 1_concepts/                     ← 概念设计
│   └── baafoo-concept-design.md
├── 2_prd/                          ← 产品需求
│   ├── baafoo-prd.md
│   ├── baafoo-feature-extension-prd.md
│   └── competitive-analysis.md
├── 3_design/                       ← 设计文档
│   └── ui-framework-design.md
├── 4_deliverables/                 ← 交付物
│   └── software-company/
├── 5_review/                       ← 审查与建议
│   ├── index.md                    ← 全量审查入口
│   ├── 00~10 编号报告              ← 全量审查子报告
│   ├── plugin-*.md                 ← 插件系统（活跃）
│   ├── mockforge_*/协议_*/多语言*.md ← 未来规划
│   └── archive/                    ← 早期审查（已归档）
├── 6_dev-notes/                    ← 开发笔记（全部已完成）
├── 7_test-reports/                 ← 测试报告
└── .test/                          ← 测试资产
```
