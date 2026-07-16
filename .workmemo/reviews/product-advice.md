# Baafoo 产品建议书

> 版本: 1.0  
> 日期: 2026-05-29  
> 来源: 概念设计 v0.6 + PRD v1.4 + UI框架设计 v1.4 + 原型 v1.4 评审
> 
> ⚠ **过时说明**: 本文档基于 PRD v1.4 编写，部分建议已在 v2.0-v2.5 中采纳（如默认透传、fail-closed 讨论等）。保留供历史参考。

---

## 一、P0 级问题（影响核心体验，必须解决）

### 1.1 规则匹配失败的默认行为需统一

> **⚠ v2.0 决议更新**：经团队讨论，PRD v2.0 已决定**默认透传**。理由：零配置不误伤优先于严格模式；用户可通过 `baafoo.stub.unmatched-default=404` 切换为严格模式。以下原始建议保留供参考，但默认行为已改为透传。

**现状**：概念设计 §3.4 写"匹配失败→抛异常或返回404取决于配置"，PRD R-A2 写"默认透传"。两个文档矛盾。

**问题**：对用户而言，"配置了挡板但某个请求没匹配到规则"是高频场景，行为必须确定。挡板场景下，用户期望的是"未覆盖的请求应当告警而非悄悄透传"。如果默认透传，用户会以为挡板在生效但实际在走真实下游——这违背了装挡板的初衷。

**建议**：

- 未匹配规则时**默认返回 404 + 明确日志**，而非透传
- 透传应是**显式配置**（如在规则末尾加一条 `match: "*" → passthrough` 的兜底规则）
- 在请求日志中对"未匹配"请求标记醒目状态（黄色/橙色），与"命中规则"和"透传模式"区分
- 在 Web 控制台首页增加"未匹配请求数"统计卡片

**v2.0 决议**：默认透传，`baafoo.stub.unmatched-default` 默认值改为 `passthrough`，保留配置项允许切换为 `404`。请求日志中对"未匹配"请求仍标记醒目状态。

**PRD 影响**：R-A9 AC-05 已更新为默认透传；R-S2 AC-09 已更新；配置表已更新默认值。

---

### 1.2 Agent 加载失败时应 fail-closed 而非静默透传

**现状**：PRD R-A1 AC-03 写"加载失败时以 passthrough 模式运行"。

**问题**：用户装 Agent 的意图就是拦截。Agent 挂了还悄悄透传，等于给用户假安全感——用户以为挡板生效，实际请求全部走真实下游。

**建议**：

- Agent 加载失败时**默认 fail-closed**：在启动日志打印 `ERROR` 级别告警，内容明确"Agent 未成功加载，所有请求将走真实下游"
- 在 Web 控制台的 Agent 列表中，加载失败的 Agent 状态应为**红色告警**而非灰色"离线"
- 提供 `baafoo.agent.fail-open=true` 配置项，允许用户主动选择 fail-open 行为，但**默认为 fail-closed**

**PRD 影响**：R-A1 AC-03 需修改为"默认 fail-closed + 日志 ERROR 告警"；新增配置项 `baafoo.agent.fail-open`。

---

### 1.3 5分钟搭建目标需配套 Quick Start 工具

**现状**：PRD G1 目标"5分钟内完成挡板环境搭建"。实际需要：启动 Server → 编辑 YAML → 修改 JVM 启动参数 → 重启应用。对不熟悉 JavaAgent 的开发者，5分钟不现实。

**建议**：

- 提供 `baafoo init` 命令：一键生成配置文件 + JVM 启动参数模板 + 示例规则
- 或提供 **Spring Boot Starter**（`baafoo-spring-boot-starter`）：自动注册 Agent，无需手动加 `-javaagent` 参数
- 在文档首页提供 3 步快速上手指南（而非当前的概念设计文档式说明）

**PRD 影响**：G1 目标保留，但 AC 需加"通过 Quick Start 工具辅助"；新增功能需求 `baafoo init`。

---

## 二、P1 级问题（影响体验，建议 v1.0 解决或 v1.1 跟进）

### 2.1 Header 全局模式指示灯意义不大

**现状**：UI 设计中 Header 显示"最近操作的环境模式"。

**问题**：用户可能同时在多个环境间操作，"最近操作的"模式信息没有参考价值。

**建议**：改为显示"当前用户关注的环境"（可在环境管理页选择关注），或直接移除该指示灯，把模式信息下放到环境管理页面。

**UI 影响**：BaafooHeader.vue 简化，移除或改版全局模式指示灯。

---

### 2.2 新手容易困惑"规则配了但不生效"

**现状**：规则全局共享 + 环境维度模式控制，新手不理解为什么规则存在但不生效。

**建议**：

- 规则列表页增加一列"生效环境"标签（显示哪些环境当前为 stub 模式）
- 规则保存后弹出提示："规则已保存，将在以下 stub 模式环境中生效：ft-1, ft-3"
- 在"规则管理"页面顶部增加一个 banner："当前无 stub 模式环境，所有规则不会生效"（当无任何环境处于 stub 模式时显示）

**UI 影响**：RulesPage.vue 增加环境状态列和提示信息。

---

### 2.3 录制数据仅存本地文件系统

**现状**：录制数据存储在 Server 本地文件系统。

**问题**：无横向扩展能力，Server 重启可能丢失未持久化的录制数据。

**建议**：

- v1.0 可接受本地存储，但需确保录制数据写入磁盘后才返回成功（避免内存中丢失）
- v1.5 规划对象存储（如 COS）或数据库持久化
- 录制数据增加自动清理策略（如保留最近 7 天、单环境最大 1GB）

**PRD 影响**：R-R1 补充数据持久化和清理策略。

---

### 2.4 无规则版本管理

**现状**：规则修改后无法回滚。

**建议**：

- v1.0 至少提供 **undo 能力**：每次规则修改保存前一版本快照，支持一键回退到上一版本
- v1.5 接入 Git 版本管理（PRD 已规划）

**PRD 影响**：R-R2 增加"规则历史版本"功能。

---

### 2.5 无批量场景管理

**现状**：无法一键切换"支付异常场景集"等批量规则组合。

**建议**：

- v1.0 增加**场景集**概念：一组规则的命名集合，支持一键启用/禁用整组规则
- 场景集与规则是多对多关系（同一规则可属于多个场景集）

**PRD 影响**：新增功能需求"场景集管理"。

---

## 三、用户画像补充

### 3.1 后端开发者：缺少 IDE 集成

**现状**：所有操作需在浏览器 Web 控制台完成。

**建议**：

- v1.5 提供 **IntelliJ IDEA 插件**：在 IDE 内查看/切换环境模式、查看请求日志
- v1.0 可先提供 CLI 工具（`baafoo cli`）满足脚本化需求

### 3.2 QA 测试：缺少场景集管理

（见 §2.5）

### 3.3 DevOps：单 Server 无集群方案

**现状**：单 Baafoo Server 实例。

**建议**：

- v1.0 不做集群，但在架构上预留扩展点
- v1.5 规划多 Server 注册发现（如基于 Consul）

---

## 四、技术风险对产品的影响

以下技术风险会直接影响产品承诺，产品经理在输出 PRD 时需注意：

### 4.1 Pulsar/TDMQ Mock Broker 风险较高

**风险**：Pulsar binary protocol 极其复杂，TDMQ SDK 可能有私有协议扩展未验证。

**对产品的影响**：

- v1.0 Pulsar Mock Broker 仅覆盖**最简路径**（非分区 Topic + 单 Producer + 单 Consumer + Shared 订阅）
- TDMQ SDK 兼容性需抓包验证后才能承诺
- 建议通过 **Agent 插件化架构**（详见《Baafoo Agent 插件化架构建议》）隔离协议复杂度风险

**PRD 建议**：Pulsar Mock Broker 的 AC 需明确列出 v1.0 支持的场景范围，避免过度承诺。

### 4.2 Kafka Mock Broker 需标记 Beta

**风险**：Kafka 未覆盖 API 的默认响应可能导致客户端崩溃。

**对产品的影响**：

- v1.0 Kafka Mock Broker 标记为 **Beta**
- 明确列出支持的 Kafka Client 版本范围（建议 2.8+）
- 文档中明确不支持的场景（如 `acks=all`、事务、Consumer Group Rebalance）

**PRD 建议**：Kafka Mock Broker AC 增加"Beta 标识"和"版本兼容范围"说明。

### 4.3 Consul HTTP API 拦截范围需收窄

**风险**：Spring Cloud Consul 用 WebClient（Reactor Netty），不走 OkHttp，一个拦截点覆盖不了。

**对产品的影响**：

- v1.0 仅保证 **DNS 模式 + OkHttp 客户端**（Orbitz/Ecwid Consul SDK）
- Spring Cloud Consul HTTP API 模式放 v1.5

**PRD 建议**：R-A3 AC 需明确列出 v1.0 支持的 Consul SDK 及版本矩阵。

---

## 五、v1.0 范围建议（综合产品+技术）

| 模块 | 优先级 | v1.0 范围 | 理由 |
|---|---|---|---|
| HTTP 挡板 | P0 | 完整交付 | 核心场景，无技术风险 |
| TCP 挡板 | P0 | 完整交付 | 核心场景，无技术风险 |
| Consul 拦截 | P0 | DNS 模式 + OkHttp 客户端 | HTTP API 多 SDK 问题延后 |
| Kafka Mock Broker | P1 | Beta，Kafka Client 2.8+ | 协议风险需 Beta 标识 |
| Pulsar Mock Broker | P0 | 最简路径（非分区+Shared），通过插件化架构实现 | TDMQ 高优先级，但需控制范围 |
| TDMQ 适配 | P0 | 插件化架构支持，需 TDMQ SDK 抓包验证 | 高优先级，插件化隔离风险 |
| JMS Mock Broker | P1 | 保持 P1 不变 | — |
| 录制器 | P1 | 仅 HTTP 层录制 | TCP/MQ 录制复杂度延后 |
| Web 控制台 | P0 | 完整交付，日志推送用轮询（非 WebSocket） | 降低 v1.0 复杂度 |
| Quick Start | P0 | `baafoo init` 命令 | 5分钟搭建目标的前提 |
| Agent 自检 | P0 | 启动后自动验证拦截是否生效 | 避免"装了但没生效"的静默失败 |
| 规则 undo | P1 | 保存前一版本快照，支持一键回退 | 基础安全保障 |

---

## 六、关键配置项建议

产品 PRD 中需明确的配置项：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `baafoo.agent.fail-open` | `false` | Agent 加载失败时的行为：false=fail-closed（打 ERROR 日志），true=fail-open（静默透传） |
| `baafoo.stub.unmatched-default` | `passthrough` | 未匹配规则的请求默认行为：`passthrough`（透传，v2.0 起默认）、`404`（返回404，挡板严格模式） |
| `baafoo.recording.memory-limit` | `256MB` | 单 Agent 录制内存硬上限 |
| `baafoo.recording.auto-cleanup-days` | `7` | 录制数据自动清理天数 |
| `baafoo.heartbeat.interval` | `10s` | Agent-Server 心跳间隔（PRD 原 30s 偏长） |
| `baafoo.agent.self-check` | `true` | Agent 启动后是否自动验证拦截生效 |
