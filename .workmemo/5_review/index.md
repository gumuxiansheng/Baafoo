# Baafoo 全量代码审查报告

> **审查日期**: 2026-06-14
> **审查范围**: 全量源码 — baafoo-core, baafoo-server, baafoo-agent, baafoo-plugin-api, baafoo-cli, baafoo-test-app, baafoo-test-spring, baafoo-example-plugins, web/ 前端, 构建配置
> **总源文件数**: ~180 个 Java 文件 + ~20 个前端源文件 + 构建配置
> **新发现总问题数**: **174 个** (Critical: 17, High: 32, Medium: 57, Low: 53)
> **+ 第二轮补充**: 37 个新问题（Mapper XML: 17, 测试代码: 23, CI/CD: 14, 部分去重）

---

## 报告索引

| 文件 | 内容 |
|------|------|
| [00-summary.md](./00-summary.md) | 全局汇总与优先级建议 |
| [01-core-review.md](./01-core-review.md) | baafoo-core 详细发现 |
| [02-server-review.md](./02-server-review.md) | baafoo-server 详细发现 |
| [03-agent-review.md](./03-agent-review.md) | baafoo-agent 详细发现 |
| [04-plugin-cli-test-review.md](./04-plugin-cli-test-review.md) | plugin-api/cli/test-app/test-spring/plugin 详细发现 |
| [05-web-review.md](./05-web-review.md) | Web 前端详细发现 |
| [06-build-config-review.md](./06-build-config-review.md) | 构建与配置详细发现 |
| [07-combined-critical.md](./07-combined-critical.md) | 所有 Critical 级别问题汇总 |
| [08-mapper-review.md](./08-mapper-review.md) | MyBatis Mapper XML 详细发现 |
| [09-test-review.md](./09-test-review.md) | 测试代码详细发现 |
| [10-cicd-review.md](./10-cicd-review.md) | CI/CD 与构建脚本详细发现 |

---

## 与历史报告的差异对比

| 对比项 | 历史报告 (`.review/deep-code-review-report.md`) | 本次全量审查 |
|--------|-----------------------------------------------|-------------|
| 覆盖模块 | core + server + agent + web (主要路径) | 全量模块 + 根配置 + Docker + 构建 |
| 文件数 | ~110 个 | ~200 个 |
| 发现总数 | 20 个 | 137 个 |
| Critical/P0 | 3 个 (BUG-4, THREAD-1, SEC-1) | 17 个 (新增 14 个关键级) |
| 新增重点 | — | 凭据泄露、线程安全重竞、前端 XSS、全局状态不一致 |

> 历史 `deep-code-review-report.md` 的 20 个问题仍有效，本次审查将其重新归类到各模块详细报告中。
