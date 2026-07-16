# .workmemo 文档过时审查报告

> **日期**: 2026-07-17  
> **审查人**: Alex (PM)  
> **审查范围**: `.workmemo/` 下 31 个活跃文档（排除 archive/）  
> **审查方法**: 逐文档与代码实现、git 历史、当前架构比对

---

## 审查结论

| 严重度 | 数量 | 说明 |
|--------|------|------|
| 🔴 P0 — 文件引用断裂 | 3 | 文档引用的路径已不存在 |
| 🟠 P1 — 内容与代码矛盾 | 5 | 文档声明的内容与实际代码不一致 |
| 🟡 P2 — 版本/时间线过时 | 4 | 文档版本号或时间线引用已过时 |
| 🔵 P3 — 轻微不一致 | 3 | 可改进但非阻塞 |

---

## 🔴 P0: 文件引用断裂

### P0-1: PRD 头部引用 `../1_concepts/baafoo-concept-design.md` — 目录已删除

- **文件**: `.workmemo/prd/baafoo-prd.md` 第 5 行
- **内容**: `> **关联文档**:[概念设计说明书 v0.8](../1_concepts/baafoo-concept-design.md)`
- **问题**: `1_concepts/` 目录在 2026-07-16 重构中已删除
- **修复**: 删除该引用行，或改为引用 ADR 中的设计决策

### P0-2: PRD R-W9 引用 `5_review/CODE-REVIEW-REPORT.md` — 已重命名

- **文件**: `.workmemo/prd/baafoo-prd.md` R-W9 行
- **内容**: `**详细报告** | `5_review/CODE-REVIEW-REPORT.md`、`reviews/security/security-fixes-20260710.md` |`
- **问题**: 文件已重命名为 `reviews/security/code-review-report-20260710.md`
- **修复**: 更新路径为 `reviews/security/code-review-report-20260710.md`

### P0-3: PRD R-S11 关键文件引用 `GrpcStubHandler.java` — 代码中不存在

- **文件**: `.workmemo/prd/baafoo-prd.md` R-S11 行
- **内容**: `**关键文件** | `GrpcChannelAdvice.java`、`GrpcStubHandler.java`、`GrpcUnifiedHandler.java` |`
- **问题**: `GrpcStubHandler.java` 在代码中不存在。实际 gRPC Server 端只有 `GrpcUnifiedHandler.java`（+ `GrpcPassthroughForwarder.java`、`GrpcResponseBuilder.java`）
- **修复**: 移除 `GrpcStubHandler.java`，补充 `GrpcPassthroughForwarder.java` 和 `GrpcResponseBuilder.java`

---

## 🟠 P1: 内容与代码矛盾

### P1-1: ADR-003 引用 `GrpcStubHandler.java` — 代码中不存在

- **文件**: `.workmemo/decisions/adr-003-grpc-interception-strategy.md` 第 5 行
- **内容**: `**关联**: `GrpcChannelAdvice.java`、`GrpcStubHandler.java`、`decisions/grpc-fix-design-20260624.md``
- **问题**: 同 P0-3，`GrpcStubHandler.java` 不存在
- **修复**: 移除引用，补充 `GrpcUnifiedHandler.java`

### P1-2: plugin-analysis-report.md 声称 "PluginManager 被标注 @Deprecated" — 已移除

- **文件**: `.workmemo/reviews/plugin-analysis-report.md`
- **内容**: "`PluginManager` 本身甚至被标注为 `@Deprecated`"
- **问题**: ADR-002 已决策取消 `@Deprecated`，代码中已确认移除。该审查报告写作时的事实已过时
- **修复**: 添加更新说明注记，或修改为 "曾标注 @Deprecated（已在 ADR-002 中移除）"

### P1-3: plugin-analysis-report.md 声称 "仅 Pulsar 调用 PluginManager" — 已过时

- **文件**: `.workmemo/reviews/plugin-analysis-report.md`
- **内容**: "只有 `PulsarClientAdvice` 在实际生产路径中调用了 `PluginManager.getPlugin()`"
- **问题**: 当前代码中 SocketConnectAdvice 和 NioSocketConnectAdvice 已通过 `PLUGIN_CONSULT_FN_EXT` 桥接函数接入 SPI；KafkaProducerAdvice、KafkaConsumerAdvice、JmsConnectionFactoryAdvice、GrpcChannelAdvice、PulsarClientAdvice 均引用了 PluginManager
- **修复**: 添加更新说明，或重写该章节以反映当前 SPI 接入状态

### P1-4: plugin-arch-advice.md 声称 "P0: 所有 Advice 已接入 SPI 委托路径" — 部分不准确

- **文件**: `.workmemo/reviews/plugin-arch-advice.md`
- **内容**: "✅ P0：所有 Advice 已接入 SPI 委托路径（Socket/NIO 通过桥接函数）"
- **问题**: DNS Advice（DnsResolveAdvice/DnsResolveAllAdvice/DnsResolutionAdvice）、Socket I/O Advice（SocketInputStreamAdvice/SocketOutputStreamAdvice/SocketChannelReadAdvice/SocketChannelWriteAdvice/SocketCloseAdvice）、HttpOpenServerAdvice、NioSocketFinishConnectAdvice 均未接入 SPI 桥接。虽然这些可能不需要 SPI 委托（属于辅助拦截点），但 "所有" 这个表述不准确
- **修复**: 改为 "核心路由 Advice（Socket/NIO/Kafka/JMS/Pulsar/gRPC）已接入 SPI 委托路径"

### P1-5: PRD Phase 4 (v2.0) 仍列 "gRPC/WebSocket" — gRPC 已在 v2.5 实现

- **文件**: `.workmemo/prd/baafoo-prd.md` §8.3 Phase 4
- **内容**: `**Phase 4(v2.0)**:gRPC/WebSocket + 录制回放增强 + ...`
- **问题**: gRPC 已在 R-S11 (v2.5) 中实现。Phase 4 应移除 gRPC，仅保留 WebSocket
- **修复**: 改为 `**Phase 4(v2.0)**:WebSocket + 录制回放增强 + ...`

---

## 🟡 P2: 版本/时间线过时

### P2-1: product-advice.md 引用 "PRD v1.4" — 当前为 v2.5

- **文件**: `.workmemo/reviews/product-advice.md` 第 4 行
- **内容**: `> 来源: 概念设计 v0.6 + PRD v1.4 + UI框架设计 v1.4 + 原型 v1.4 评审`
- **问题**: 文档基于 PRD v1.4 编写，当前 PRD 已 v2.5。其中多条建议（如默认透传、fail-closed、Quick Start 工具）已在后续 PRD 版本中采纳
- **修复**: 在文档头部添加 `> ⚠ 本文档基于 PRD v1.4 编写，部分建议已在 v2.0-v2.5 中采纳。保留供历史参考。`

### P2-2: PRD Q8 引用 "v1.1" — 当前为 v2.5

- **文件**: `.workmemo/prd/baafoo-prd.md` Q8 行
- **内容**: `> 以下为 v1.1 中基于团队讨论确定的问题决议`
- **问题**: 版本号过时，Q8 本身是 v2.0 的架构级决议
- **修复**: 改为 `> 以下为 v1.1-v2.0 中基于团队讨论确定的问题决议`

### P2-3: PRD Phase 3 (v1.5) 列 "Docker 镜像" — 已实现

- **文件**: `.workmemo/prd/baafoo-prd.md` §8.3 Phase 3
- **内容**: `**Phase 3(v1.5)**:Docker 镜像 + 场景集管理(简化版)+ ...`
- **问题**: Docker（`Dockerfile`、`docker-compose.yml`、`docker-compose.staging.yml` 等）已实现
- **修复**: 从 Phase 3 移除 "Docker 镜像"，标注为已实现

### P2-4: feature-extension-prd.md 引用 "PRD v2.3" — 当前为 v2.5

- **文件**: `.workmemo/prd/baafoo-feature-extension-prd.md` 第 5 行
- **内容**: `**关联文档**: [Baafoo PRD v2.3](./baafoo-prd.md)`
- **问题**: PRD 已更新至 v2.5
- **修复**: 改为 `[Baafoo PRD v2.5](./baafoo-prd.md)`

---

## 🔵 P3: 轻微不一致

### P3-1: PRD §8.3 Phase 3 列 "Kafka Beta 正式版" — Kafka 已正式支持

- **文件**: `.workmemo/prd/baafoo-prd.md` §8.3 Phase 3
- **内容**: `Kafka Beta 正式版`
- **问题**: R-S4 已标注 "✅ 已实现并通过集成测试验证"，不再是 Beta
- **修复**: 移除该项或标注为已完成

### P3-2: PRD §8.3 Phase 3 列 "Pulsar 范围扩展" — Pulsar 已正式支持

- **文件**: `.workmemo/prd/baafoo-prd.md` §8.3 Phase 3
- **内容**: `Pulsar 范围扩展`
- **问题**: R-S5 已标注 "✅ 已实现并通过集成测试验证"
- **修复**: 标注为已完成或移除

### P3-3: security/issue-tracker.md Medium 表格 M22 重复

- **文件**: `.workmemo/reviews/security/issue-tracker.md`
- **内容**: Medium 已修复表有 `M22 | 全局事件监听未移除 | ✅ 已修复`，Medium 不修复/待修复表又有 `M22-low | 输入未 trim | ❌ 不修复`
- **问题**: M22 在两个表中出现，虽然后缀不同（M22 vs M22-low），但容易混淆
- **修复**: 将 M22-low 重命名为 M26 或其他编号

---

## 已确认与代码一致的文档

以下文档经审查与代码实现一致，无需修改：

- **ADR-001** (Bootstrap ClassLoader 注入) — 与 `BaafooAgent.java`、`GlobalRouteState.java` 一致
- **ADR-002** (SPI 插件架构) — 与 `PluginManager.java` 一致（@Deprecated 已移除）
- **ADR-004** (i18n Accept-Language) — 与 `I18n.java`、`LocaleSwitcher.vue` 一致
- **architecture-improvement-todo.md** — P0-P3 待办项状态与代码一致
- **security/issue-tracker.md** — 修复状态与代码一致（C1/H1/H2/H3/H6/H8/H10/H11 已修复）
- **naming-convention.md** — 与当前目录结构一致
- **所有 feasibility/ 文档** — 可行性分析文档，时效性要求低
- **所有 competitive/ 文档** — 竞品分析文档，时效性要求低

---

## 修复优先级建议

1. **立即修复** (P0): 3 个断裂引用 — 影响文档可用性
2. **本轮修复** (P1): 5 个内容矛盾 — 影响文档可信度
3. **下次迭代** (P2): 4 个版本过时 — 影响文档准确性
4. **择机修复** (P3): 3 个轻微不一致 — 影响文档整洁度
