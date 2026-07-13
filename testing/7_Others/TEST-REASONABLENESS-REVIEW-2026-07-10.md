# Baafoo 测试合理性复审报告（Testing Reasonableness Review）

**复审对象**：`testing/` 全量测试内容（1_UnitTest / 2_IntegrationTest / 3_SystemTest / 4_E2ETest / 5_PerformanceTest / 6_UITest / 7_Others）
**复审日期**：2026-07-10
**对照基线**：`COVERAGE-REVIEW-2026-07-07.md`（上轮覆盖审查）、`FULL-TEST-REPORT.md`（88/0/15）、`TEST-REPORT.md`（60/60/0/0）
**复审结论**：L3 系统测试的**断言质量已显著提升**，但她**脚本—报告—脚本之间的不一致**会直接削弱"敢发布"的结论可信度，必须收敛。

---

## 一、总体结论

✅ **好的一面（已确认）**：L3 `test-fullchain.sh` 相比 07-07 覆盖审查时已大幅改进——
- 条件类断言已从 `|mocked` 兜底伪通过，改为精确 `matchedBy`（C01–C09）核对；
- 补齐了规则集（AS01–03）、OpenAPI 导入（OAPI01–02）、录制管理（REC-PAGE/REC-DEL）、规则撤销（RU01）、gRPC（G01–G06）等 P0/P1 缺口；
- Hermetic 化彻底（全仓 0 个公网 `httpbin.org`）；
- H08 前置 `reset-state`，C10 用 `ruleId` 精确判负，消除了已知 flaky / 误判。

🔴 **坏的一面（本复审新发现）**：**三套全链路脚本质量不齐、两份官方报告互相矛盾**，导致"PASS 数"在不同文件里对不上，存在**注水绿 / 假 FAIL / 报告不可复现**三类风险。

---

## 二、🔴 高危问题（直接影响结论可信度）

### H1 — PL01：报告与当前脚本严重不一致（报告不可复现）
- **现状**：`FULL-TEST-REPORT.md` L97 / F5 把 PL01 记为 **SKIP**，理由"本运行器无法取容器日志"。
- **脚本实情**：当前 `test-fullchain.sh` L1019–1051 的 PL01 逻辑已改为**优先用 Agent API `/api/agents` 的 `pluginStatuses` 判定**，且代码里**根本没有 SKIP 分支**（只有 `test_pass` 或 `test_fail`）。
- **矛盾**：用当前脚本重跑，PL01 只能是 PASS 或 FAIL，**绝不可能产出报告里写的 SKIP**。报告是基于"升级前"的脚本生成的，与磁盘上的脚本已脱节。
- **影响**：真实口径很可能已是 **74/0/14**（PL01 实际 PASS），而非报告宣称的 73/0/15。
- **建议**：重跑 `./testing/3_SystemTest/test-fullchain.sh`，以生成的 `junit-report.xml` 为唯一事实源，重算 PASS/SKIP 并刷新 `FULL-TEST-REPORT.md` 与 L13–16 / L97 / F5。

### H2 — C11：同一用例在 .ps1 里"硬 FAIL"、在 .sh 里"SKIP"、报告却写"PASS"
- **.ps1 (L1323–1330)**：`if ($_c11RuleId -eq "staging-http-global") { PASS } else { Test-Fail "env catch-all stole the request" }` —— 在**当前产品行为**（env catch-all 优先于全局规则，见 FULL F4）下会**直接 FAIL**，阻塞整链。
- **.sh (L1203–1204)**：`else test_skip` —— 正确降级为 SKIP（这是"优先级约定待明确"的已知问题，非产品失败）。
- **TEST-REPORT.md (L157)**：却写 C11 = **PASS**，系基于旧 .ps1 运行（当时全局规则胜出），属**过度声明**。
- **建议**：① 把 `.ps1` 的 C11 改为与 `.sh` 一致的 `Test-Skip`（附"全局 vs env catch-all 优先级待明确"说明），避免任何人跑 `.ps1` 时被一个非失败用例卡死；② `TEST-REPORT.md` 的 C11 状态纠正为 SKIP（或整体弃用该报告，见 M1）。

### H3 — 三套全链路脚本质量不齐，L2 仍是"伪通过"版本
- `2_IntegrationTest/run-fullchain-tests.sh`（L2，60 用例）**未同步** L3 的精确化改造，仍使用宽松断言：
  - `assert_contains ... "mocked"`（L186）兜底；
  - `grep -qi "success\|stubbed\|mocked\|baafoo\|error\|timeout"`（L280/288）、含 `null`（L308）——正是 07-07 覆盖审查 P0-1/P0-2 点名要去掉的"Pulsar error/timeout 算过、JMS null 算过"伪通过写法。
  - grep 确认该脚本**无 `matchedBy` 精确断言**。
- **风险**：若任何流水线/人工误跑 L2 脚本，会得到注水绿。好消息是 `system-test.yml` 实际跑的是 L3 `.sh`（已正确），但 L2 脚本仍躺在仓库里误导读者。
- **建议**：要么**删除/归档** L2 脚本（避免与 L3 重复且质量更低），要么把它重构成 L3 `.sh` 的薄包装、复用同一断言库，彻底消除"多份真相"。

---

## 三、🟠 中危问题（一致性 / 覆盖）

### M1 — 两份"官方报告"互相矛盾
| 维度 | TEST-REPORT.md（基于 .ps1） | FULL-TEST-REPORT.md（基于 .sh） |
|------|------------------------------|----------------------------------|
| 总数 | 60 / 60 / 0 / 0 | 88 / 0 / 15 |
| H 组 | 含 H10(staging-b)，10 条全 PASS | 不含 H10，9 条 |
| PL01 | PASS | SKIP |
| C11 | PASS | SKIP |

根因：TEST-REPORT 基于 2026-07-02/08 的 .ps1 运行，FULL 基于 2026-07-10 的 .sh 运行，两套脚本各自演进、报告未对齐。
**建议**：确立**单一事实源**（推荐 L3 `test-fullchain.sh` + `FULL-TEST-REPORT.md`），将 `TEST-REPORT.md` 降级为"历史归档"或合并进 FULL 报告，避免读者被两份矛盾数字误导。

### M2 — 性能测试层（5_PerformanceTest）完全空置
目录存在但**无任何测试文件**。测试金字塔里"性能 / Lighthouse / k6 / 负载"零覆盖。对 Mock 平台虽非 P0，但若 PRD/计划承诺了性能基线，则属于"承诺未兑现"。
**建议**：在计划里明示"性能测试 N 不阻塞发布"，或至少补 1 条 smoke（server 冷启动延迟、规则匹配 P99 延迟）。

### M3 — UI 测试层（6_UITest）只有报告、没有 spec
仅 `playwright-report/index.html`，**无 `.spec.ts`/`.spec.js`**。说明 Playwright 用例要么在他处、要么从未落地，报告是孤儿。
**建议**：若确有 admin 控制台 UI，把 spec 入库；若暂不测 UI，移除孤儿报告或在 README 注明"UI 测试暂缓"。

### M4 — 测试计划文档（PROJECT-TEST-PLAN.md）§6.4 可能仍未同步
07-07 覆盖审查 P2-12 要求把"33 用例"更新为实际数量。需确认是否已对齐到 L3 的 88 口径，否则计划与执行继续脱节。

---

## 四、🟢 已确认的良好实践（保持）
- L3 `.sh` 条件类断言精确化为 `matchedBy`（C01–C09），H08 前置 `reset-state` 消 flaky，C10 用 `ruleId` 精确判负——都修得好。
- Hermetic 化彻底（0 公网依赖），断网/公网抖动可跑。
- `jq` 缺失时有 `python3` 兜底，CI 又显式 `apt-get install -y jq`，依赖健壮。
- JUnit XML 由 `TEST_RESULTS` 单一数据源推导，报告自洽。
- 企业级 E2E（kafka/petclinic/sca）是独立的、带真实断言的 PowerShell 冒烟脚本（`4_E2ETest/enterprise/*/smoke-test.ps1`），质量 OK 且**不与 L3 重复**。
- 单元测试层有 PIT 变异测试脚本（`1_UnitTest/run-mutation.sh`），JDK 探测 / 两段构建（install 再 PIT）处理得当。
- CI（`system-test.yml`）确实跑的是质量最高的 L3 `.sh` 并发布 `junit-report.xml`——这点是对的，H3 的风险主要在"人工误跑 L2"。

---

## 五、优先级行动清单

**P0（立即，影响结论可信度）**
1. 重跑 L3 `.sh`，用 `junit-report.xml` 刷新 `FULL-TEST-REPORT.md`（解决 H1 PL01 漂移，确认真实是 73/0/15 还是 74/0/14）。
2. 修 `.ps1` 的 C11 为 `Test-Skip`，并纠正 `TEST-REPORT.md` 的 C11/PL01 状态（H2）。
3. 处理 L2 `run-fullchain-tests.sh`：删除/归档，或重构为 L3 的薄包装（H3）。

**P1（收敛）**
4. 确立单一报告事实源，对齐 TEST-REPORT.md 与 FULL-TEST-REPORT.md（M1）。
5. 补性能/UI 层，或明文暂缓（M2/M3）。
6. 同步 `PROJECT-TEST-PLAN.md` §6.4 到 88 口径（M4）。

**P2（增强，非阻塞）**
7. 明确"全局规则 vs env catch-all"优先级约定，据此定 C11 是 PASS 还是 SKIP（当前 SKIP 合理）。
8. 补齐协议×模式矩阵缺口 MX×13（需引入真实 broker / testcontainers）。

---

*复审方法：通读 L3 `.sh`/`.ps1`、L2 `run-fullchain-tests.sh`、两份报告、企业 E2E 冒烟脚本、单元变异脚本、CI 工作流；以"断言精确性 / 报告可复现性 / 脚本单一真相 / 覆盖完整性"四维度评估。本复审未改动任何测试代码，仅给出结论与建议。*
