# PROJECT-TEST-PLAN.md 全面审查报告

**审查对象**：`testing/7_Others/PROJECT-TEST-PLAN.md`（v2.5, 174497 字节, ~2800 行）
**审查重点**：缺失测试案例、与 `testing/2_IntegrationTest` 和 `testing/3_SystemTest` 的覆盖范围比对与重复情况
**审查日期**：2026-07-11

---

## 一、文档总体评价

PROJECT-TEST-PLAN.md（以下简称"计划"）是一份**极其完整的项目级测试计划**，覆盖 17 个章节 + 附录，从单元测试到企业级应用测试、性能、兼容性、稳定性、安全、前端、文档契约、执行排期、风险应对、交付物，几乎无所不包。仅就**计划文档的广度**而言，在同类开源/内部项目中属于顶级水准。

但计划文档的完整性 ≠ 实际测试覆盖的完整性。本次审查的核心是：**计划里写的用例 vs 实际已执行的用例**，以及 **计划自身的缺失项**。

---

## 二、与实际测试目录的覆盖比对

### 2.1 testing/2_IntegrationTest（集成测试规则 + 脚本）

**目录内容**：
- 46 个规则 JSON 文件（HTTP 21 + Kafka 3 + Pulsar 2 + JMS 2 + TCP 3 + gRPC 6 + OpenAPI 1 + staging 2 + 多编码 3 + Consul 1 + GraphQL 1 + requestCount 1）
- `test-integration.ps1`（一键脚本，仅编排 docker compose up/down，40 行）
- `test-integration.sh` / `run-fullchain-tests.sh`（Shell 版编排脚本）
- `rules/register-all.sh` / `register-rules.ps1`（规则注册辅助）

**计划 §6 对集成测试的分层定义**：
- L1 Agent 模块集成 → 对应 `baafoo-agent/src/test/java/.../integration/`
- L2 单协议集成 → 对应 `2_IntegrationTest/rules/` + test-spring 驱动
- L3 全链路联调 → 对应 `3_SystemTest/test-fullchain.ps1`
- L4 Testcontainers → 对应 `baafoo-testcontainers/` + server 模块

**覆盖情况**：
| 计划分层 | 实际目录 | 覆盖状态 |
|----------|---------|---------|
| L1 Agent 模块集成 | `baafoo-agent/src/test/java/.../integration/` | ✅ 有代码，但未在 `2_IntegrationTest` 目录体现 |
| L2 单协议集成 | `2_IntegrationTest/rules/` 46 个规则 | ✅ 规则文件齐全，注册脚本就位 |
| L3 全链路联调 | `3_SystemTest/test-fullchain.ps1` | ✅ 88 用例（73 PASS / 0 FAIL / 15 SKIP） |
| L4 Testcontainers | `baafoo-testcontainers/` | ⚠️ 计划提到但审查未深入该模块 |

**重复情况**：
- `2_IntegrationTest` 和 `3_SystemTest` **不重复**。前者是规则定义 + 轻量编排，后者是完整的端到端编排器（74KB 脚本）。`3_SystemTest` 引用 `2_IntegrationTest/rules/` 下的规则文件。
- `test-integration.ps1`（2_IntegrationTest）非常简陋（仅 docker compose up/down），**实际测试执行依赖 `3_SystemTest/test-fullchain.ps1`**。
- 存在 `test-fullchain.ps1`（74KB）和 `test-fullchain.sh`（66KB）两个版本，功能基本对等，属合理的跨平台冗余。

### 2.2 testing/3_SystemTest（系统测试执行 + 报告）

**目录内容**：
- `test-fullchain.ps1`（74KB，88 用例编排器）
- `test-fullchain.sh`（66KB，Shell 版）
- `FULL-TEST-REPORT.md`（最新报告，2026-07-10，88 用例 73 PASS / 0 FAIL / 15 SKIP）
- `TEST-REPORT.md`（旧报告，2026-07-08，60 用例 100% PASS）
- `TEST-MANUAL.md`（全协议测试手册）
- `junit-report.xml`（CI 消费）

**与计划 §6.4（L3 全链路集成测试）的对照**：

| 计划 §6.4 用例分组 | 实际脚本用例 | 差异 |
|-------------------|-------------|------|
| F 核心功能 | F01–F05 (5) | ✅ 一致 |
| A API 安全与 CRUD | A01–A07 (7) | ✅ 一致 |
| H HTTP 协议 | H01–H09 (9) | ✅ 一致（计划写 10，H10 合并到 E02） |
| T TCP | T01–T03 (3) | ✅ 一致 |
| K Kafka | K01–K03 (3) | ✅ 一致 |
| P Pulsar | P01–P03 (3) | ✅ 一致 |
| J JMS | J01–J02 (2) | ✅ 一致 |
| E 环境隔离 | E01–E02 (2) | ✅ 一致 |
| PL 插件 | PL01–PL03 (3) | ✅ 一致 |
| R 录制 | R01–R03 (3) | ✅ 一致 |
| D MQ 方向 | D01–D03 (3) | ✅ 一致 |
| C 条件类型 | C01–C11 (11) | ✅ 一致（C11 SKIP：全局规则优先级与期望不符） |
| M 环境模式 | M01–M05 (5) | ✅ 一致 |
| AS 规则集 | AS01–AS03 (3) | ✅ **计划审查时缺失，现已补齐** |
| REC 录制管理 | REC-PAGE / REC-DEL (2) | ✅ **计划审查时缺失，现已补齐** |
| RU/RST 撤销/重置 | RU01 / RST01 (2) | ✅ **计划审查时缺失，现已补齐** |
| OAPI OpenAPI 导入 | OAPI01–OAPI02 (2) | ✅ **计划审查时缺失，现已补齐** |
| G gRPC | G01–G06 (6) | ✅ **计划审查时缺失，现已补齐** |
| MX 协议×模式矩阵缺口 | MX×13 (13 SKIP) | ✅ 已标注缺口 |

**结论**：`COVERAGE-REVIEW-2026-07-07.md` 中指出的 3 类结构性问题（伪通过、零覆盖、矩阵缺口），在 `FULL-TEST-REPORT.md`（2026-07-10）中**大部分已修复**：
- ✅ C01–C09 已去除 `|mocked` 兜底，改为精确断言 `matchedBy`
- ✅ P/J 断言已收紧（error/timeout/null 判 FAIL）
- ✅ 规则集 AS01–AS03、OpenAPI OAPI01–OAPI02、撤销 RU01、重置 RST01 已补齐
- ✅ gRPC G01–G06 全绿
- ✅ H08 已前置调用 reset-state
- ✅ H09 Consul 已补 consul 服务点亮
- ✅ PL03 Feign 已通过 DnsResolveAdvice 修复点亮
- ⚠️ C11 仍 SKIP（全局规则 vs env catch-all 优先级设计问题）
- ⚠️ MX×13 仍 SKIP（Staging 无真实 broker）
- ⚠️ PL01 仍 SKIP（harness 无法取容器日志）

---

## 三、缺失测试案例分析

### 3.1 计划中有定义但实际脚本未覆盖的用例（P0/P1 级）

| 计划章节 | 用例 ID | 用例名称 | 优先级 | 缺失原因 |
|----------|---------|---------|--------|---------|
| §7.4 | FT-SCENE-001~004 | 场景集启用/禁用/切换 | P1 | 脚本未实现 |
| §7.9 | FT-MCP-001~003 | MCP Server 工具调用 | P1 | 脚本未实现 |
| §7.10 | FT-FAULT-001~004 | 故障注入（延迟/错误/断连） | P1 | 脚本未实现 |
| §7.3 | FT-REC-007 | 录制分页查询 | P1 | 脚本仅做了 REC-PAGE 基础版 |
| §7.3 | FT-REC-010 | 录制保留策略（retentionDays/maxSizeMb） | P2 | 脚本未实现 |
| §7.2 | FT-RULE-009 | 规则优先级多条件组合 | P1 | C11 SKIP，未解 |
| §7.2 | FT-RULE-012 | 规则 tags 筛选 | P2 | 脚本未实现 |
| §7.2 | FT-RULE-013 | 规则多响应分支 | P2 | 脚本未实现 |
| §7.6 | FT-PLUGIN-004 | 插件热加载/卸载 | P2 | 脚本未实现 |
| §7.7 | FT-AUTH-005 | API Key CRUD（多 key） | P2 | 脚本未实现 |
| §6.4 | IT-L3-CONSUL-DNS | Consul DNS 服务发现重定向 | P1 | H09 只测了 Consul HTTP stub，未测 DNS 名→broker 重定向 |
| §6.4 | IT-L3-FAILOPEN | fail-open 降级（Server 断连后 Agent 行为） | P1 | 脚本未实现（STAB-REC-001 部分覆盖但未执行） |

### 3.2 计划中有定义但完全没有对应测试代码的领域

| 计划章节 | 领域 | 状态 |
|----------|------|------|
| §9 性能测试 | 全部 | ⚠️ 计划完整（PT-BASE/PT-HTTP/PT-TCP/PT-KAFKA/PT-GRPC/PT-MIX/PT-SERVER/PT-ENT/PT-STARTUP/PT-RULESET），但**无实际性能测试脚本**。验收标准已定义（如 QPS 下降 ≤ 15%），但无 wrk/JMeter 脚本。 |
| §10 兼容性测试 | 全部 | ⚠️ 计划完整（JDK 8/11/17/21 + OS + HTTP/gRPC 客户端 + TLS + 数据库 + 多 Agent + 容器化），但**无兼容性测试自动化脚本**。多 Agent 测试已有 `4_E2ETest/enterprise/spring-cloud-alibaba/`，其余未落地。 |
| §11 稳定性测试 | 全部 | ⚠️ 计划完整（72h 运行 + 高并发 + 内存泄漏 + 线程安全 + 异常恢复 + 资源泄漏专项），但**无稳定性测试脚本**。 |
| §12 前端测试 | 全部 | ⚠️ 计划完整（Vitest 单元测试 + Playwright E2E + 响应式 + a11y + 视觉回归 + 浏览器兼容），但 `web/tests/` 仅有 Playwright 骨架，Vitest 未安装。 |
| §13 安全测试 | 全部 | ⚠️ 计划完整（认证/输入验证/网络/依赖/敏感数据），但**无安全测试脚本**。仅依赖 OWASP Dependency Check 的 Maven 插件。 |
| §14 文档与 API 契约 | 全部 | ⚠️ 计划完整（API 格式/分页/状态码/错误码/版本兼容/MCP Schema），但**无契约测试脚本**。 |
| §8 企业级应用测试 | 部分 | ⚠️ 计划列出 7 行业 30+ 应用，实际只有 3 个环境就位：Kafka / PetClinic / Spring Cloud Alibaba。多 Agent 共存测试报告已存在。其余 20+ 应用未落地。 |
| §8.9 SDK 测试 | 全部 | ⚠️ 计划列出 Go/Node.js/Python SDK 11 个用例，**无 SDK 测试脚本**。 |
| §8.10 Go Proxy Sidecar | 全部 | ⚠️ 计划列出 5 个用例，**无 Sidecar 测试脚本**。 |
| §5.6 变异测试 | 全部 | ⚠️ 计划详细定义 PIT 配置/算子/执行方式，pom.xml 中 PIT 插件**配置状态未验证**。 |

### 3.3 计划自身的缺失项（文档层面）

| 缺失项 | 说明 |
|--------|------|
| **WebSocket 协议测试** | 项目若支持 WebSocket（或计划支持），当前计划完全没有提及 WebSocket 拦截测试。 |
| **Reactive/WebFlux 兼容测试** | §10.9 仅提到 COMP-FRAME-004 WebFlux P2，但无具体用例定义。 |
| **Agent 自身 API 端点安全** | Agent 暴露的 `/api/stub-demo/*` 端点是否有鉴权？计划未覆盖。 |
| **配置热加载测试** | Agent/Server 配置变更（如 pollIntervalSec 调整）是否热生效？计划未明确。 |
| **日志脱敏验证** | §13.6 SEC-DATA-005 提到日志脱敏但无具体用例步骤。 |
| **多租户隔离测试** | 多 environment 共存时规则串扰测试仅有 E01/E02 基础断言，缺乏深度。 |
| **规则版本化/历史记录** | PRD 是否有规则版本管理？计划未覆盖。 |
| **国际化/多语言** | 前端中文/英文切换测试未覆盖。 |

---

## 四、重复情况分析

### 4.1 计划内部重复

| 重复项 | 位置 | 说明 |
|--------|------|------|
| **协议×模式矩阵** | §6.4.2 和 §9.4 | §6.4.2 从"功能正确性"角度定义矩阵缺口（MX:* SKIP），§9.4 从"性能影响"角度定义 Agent 开销测试。两者维度不同不算真重复，但矩阵结构高度相似，建议交叉引用。 |
| **TCP/Kafka/Pulsar/JMS PASSTHROUGH** | §6.4 MX:* 和 §11.5 STAB-REC-001 | MX 缺口标注"无真实 broker"，STAB-REC-001 标注"Server 断连恢复"，两者都涉及"非 STUB 模式下的协议行为"，但关注点不同。 |
| **多 Agent 共存** | §10.7 和 §8.7 | §10.7 定义了 COMP-AGENT-001~008 矩阵和 MULTI-001~008 详细用例，§8.7 企业应用测试也提到多 Agent 场景。实际已统一到 `4_E2ETest/enterprise/spring-cloud-alibaba/MULTI-AGENT-TEST-PLAN.md`。 |
| **安全测试 vs 兼容性测试** | §13.4 SEC-NET-001 TLS 配置 和 §10.5 COMP-TLS-001~009 | TLS 测试在两处都有定义，§10.5 更偏兼容性（TLS 1.2/1.3/自签名/CA），§13.4 更偏安全（SSL 验证绕过）。建议合并或交叉引用。 |

### 4.2 计划与实际测试脚本的重复

| 重复项 | 说明 |
|--------|------|
| **TEST-REPORT.md vs FULL-TEST-REPORT.md** | `3_SystemTest` 下有两份报告：旧报告（2026-07-08, 60 用例）和新报告（2026-07-10, 88 用例）。旧报告应归档或删除。 |
| **test-fullchain.ps1 vs test-fullchain.sh** | PowerShell 和 Shell 双版本，功能对等。合理的跨平台冗余，但需注意同步更新。 |
| **test-integration.ps1（2_IntegrationTest）vs test-fullchain.ps1（3_SystemTest）** | 前者仅 40 行 docker compose 编排，后者 74KB 完整编排。前者基本是"废脚本"，实际执行全靠后者。建议合并或标注前者为 deprecated。 |
| **COVERAGE-REVIEW-2026-07-07.md 的改进项 vs FULL-TEST-REPORT.md 的修复记录** | 审查报告的大部分 P0 项已在后续报告中修复。两份文档应建立追溯关系。 |

---

## 五、协议 × 模式矩阵覆盖现状

基于 `FULL-TEST-REPORT.md`（2026-07-10）的实际执行结果：

| 协议 \ 模式 | STUB | PASSTHROUGH | RECORD | RECORD_AND_STUB | RECORD_ALL |
|-------------|:----:|:-----------:|:------:|:---------------:|:----------:|
| **HTTP** | ✅ H01–H08, C01–C10 | ✅ M03 | ✅ M04 | ✅ M02+D | ✅ M05 |
| **TCP** | ✅ T01–T03 | ⚠️ MX SKIP | ⚠️ MX SKIP | ⚠️ MX SKIP | ⚠️ MX SKIP |
| **Kafka** | ✅ K01–K03 | ⚠️ MX SKIP | ⚠️ MX SKIP | ✅ D01 | ⚠️ MX SKIP |
| **Pulsar** | ✅ P01–P03 | ⚠️ MX SKIP | ⚠️ MX SKIP | ✅ D02 | ⚠️ MX SKIP |
| **JMS** | ✅ J01–J02 | ⚠️ MX SKIP | ⚠️ MX SKIP | ✅ D03 | ⚠️ MX SKIP |
| **gRPC** | ✅ G01–G06 | ⚠️ MX SKIP | ⚠️ MX SKIP | ⚠️ MX SKIP | ⚠️ MX SKIP |

**缺口统计**：25 个组合中，已覆盖 **9 个**（HTTP 5 + TCP/Kafka/Pulsar/JMS 各 1），SKIP **16 个**。所有 SKIP 均因 Staging 无真实 broker，已在计划 §6.4.2 标注。

**与计划的一致性**：完全一致。计划没有"声称覆盖但实际没测"的虚假声明。

---

## 六、优先级改进建议

### P0（影响测试结论可信度，应立即处理）

1. **C11 全局规则优先级**：明确产品语义（env catch-all vs global rule 优先级），修正测试期望或修正产品代码。当前 SKIP 意味着规则优先级这个核心功能未被验证。
2. **MX×13 矩阵缺口**：引入真实 broker（或 Testcontainers 临时 broker）补齐 TCP/Kafka/Pulsar/JMS/gRPC 的 PASSTHROUGH/RECORD/RECORD_ALL。这是计划中最大的功能覆盖缺口。
3. **PL01 插件加载**：增强 test harness 使其能取容器日志，或改用 API `/api/agents` 间接断言插件状态。

### P1（补齐核心场景）

4. **场景集（Scene）**：FT-SCENE-001~004 是 P1 用例，脚本完全未实现。
5. **MCP Server**：FT-MCP-001~003 是 P1 用例，脚本完全未实现。
6. **故障注入**：FT-FAULT-001~004 是 P1 用例，脚本完全未实现。
7. **Consul DNS 重定向**：IT-L3-CONSUL-DNS 是 P1 用例，H09 只测了 HTTP 层。
8. **fail-open 降级**：IT-L3-FAILOPEN 是 P1 用例，验证 Server 断连后 Agent 不阻塞应用。
9. **性能测试落地**：§9 计划完整但无脚本，至少落地 PT-HTTP-001~007 和 PT-STARTUP-001~003。
10. **前端测试落地**：至少安装 Vitest 并实现 FE-UNIT-001~014。

### P2（增强与文档同步）

11. **旧 TEST-REPORT.md 归档**：被 FULL-TEST-REPORT.md 取代的旧报告应移到 `archive/` 或删除。
12. **test-integration.ps1 标注 deprecated**：或合并到 test-fullchain.ps1。
13. **计划文档同步**：§6.4 "当前覆盖 33 个用例"已过时，应更新为 88 用例（73 PASS / 15 SKIP）。
14. **安全测试脚本化**：至少落地 SEC-AUTH-001~007 和 SEC-INPUT-001~004 的自动化脚本。
15. **兼容性测试自动化**：JDK 多版本兼容至少用 GitHub Actions matrix 实现。
16. **计划内部交叉引用**：§10.5 TLS 与 §13.4 TLS、§10.7 多 Agent 与 §8.7 应互相引用避免重复定义。

---

## 七、总结

### 7.1 计划文档质量

**广度：A+**。17 个章节覆盖了从单元到企业级、从功能到安全性能稳定性、从后端到前端、从计划到交付的完整链条。7 大行业 30+ 企业应用清单、多 Agent 共存 8 用例、资源泄漏 4 类专项检查，在同类项目中极其罕见。

**深度：A**。每个用例有 ID、步骤、预期结果、验收标准、优先级。变异测试有 PIT 配置细节，性能测试有 15 个指标定义和 7 类工具推荐。

**可执行性：B**。计划写了"做什么"但缺少"怎么做"的可执行脚本。性能/兼容性/稳定性/安全/前端/SDK 6 个领域都是"计划完整但脚本为零"。

**时效性：B-**。§6.4 用例数过时（33→88），部分已知修复（H08 reset-state、PL03 Feign、H09 Consul）未在计划文档中更新状态。

### 7.2 实际测试覆盖

**核心功能：A**。HTTP 全模式 5/5 覆盖，gRPC STUB 6/6 全绿，条件匹配 10/11（C11 SKIP），规则集/OpenAPI/撤销/重置全补齐，88 用例 0 失败。

**协议×模式矩阵：C+**。9/25 覆盖，16 个 SKIP 全因基础设施缺失（无真实 broker），非产品失败但确实未覆盖。

**非功能性测试：F**。性能/稳定性/安全/兼容性/前端 5 个领域，计划满分但脚本为零。

**企业级应用：C**。30+ 应用清单 vs 3 个实际落地（Kafka/PetClinic/SCA），覆盖率 ~10%。

### 7.3 最终结论

计划文档是**优秀的蓝图**，但**落地率不足**。核心功能测试已经做到 88 用例 0 失败且 hermetic 可离线运行，这是非常好的基础。但要达到计划定义的验收标准（P0 100% / P1 ≥95% / P2 ≥80%），还需补齐场景集、MCP、故障注入、性能测试、稳定性测试、安全测试、前端测试、兼容性测试 8 个领域的工作。

**建议优先级**：先补 P0 的 MX 矩阵和 C11（影响核心功能验证完整性）→ 再补 P1 的场景集/MCP/故障注入/性能测试 → 最后补 P2 的安全/前端/兼容性/企业应用。
