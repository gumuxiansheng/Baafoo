# Baafoo 全链路测试审查报告（用例合理性 / 覆盖缺口 / 协议×模式矩阵）

**审查对象**：`testing/3_SystemTest/test-fullchain.ps1`（60 用例）、`testing/7_Others/PROJECT-TEST-PLAN.md` v2.0、`.workmemo/2_prd/baafoo-prd.md`、规则文件 37 个、Server/ Agent/ test-spring 代码
**审查日期**：2026-07-07
**审查结论**：脚本整体可用（HTTP 核心链路健康），但存在 **3 类结构性问题**——(1) 多条用例靠 `|mocked` 兜底造成"伪通过"；(2) 多个已实现功能零覆盖，尤其你提到的**规则集（RuleSet）完全没测**；(3) **协议×环境模式矩阵只有 HTTP 全覆盖，其余协议仅 STUB 模式**。

---

## 一、测试用例合理性审查（逐项）

### 1.1 伪通过 / 松散断言（高危：绿灯但没真验证）

test-spring 的 `HttpCallerService.parseResponse` 返回顶层字段只有 `statusCode / stubbed / ruleId / body`，**没有 `matchedBy`**。而 `matchedBy` 只是 stub 响应 body 字符串里的内容。因此下面这些用例的断言里 `|mocked` 兜底会让"任意规则命中"都算通过，**无法证明目标条件类型真的匹配**。

| 用例 | 问题 | 严重度 |
|------|------|--------|
| **C01 Header** | 请求 `http://httpbin.org/headers` 时 `HttpCallerService.doGet` **根本不发送 `X-Test-Header`**，而 `http-header.json` 规则要求 `path=/headers AND header X-Test-Header=baafoo-test`。该条件**永远无法命中**，用例实质上测不到 header 匹配（要么 SKIP，要么靠 `mocked` 兜底误过）。 | 🔴 高 |
| **C02–C09** | 断言形如 `$resp -match "matchedBy.*query|mocked"`。`mocked` 兜底使得：即使命中的是 `http-get` 默认规则而非目标条件，用例仍 PASS。无法区分"目标条件命中"vs"别的规则命中"。 | 🔴 高 |
| **H07 GraphQL** | 断言 `"Baafoo Mock User|mocked"`，`mocked` 兜底同样会误过。 | 🟠 中 |
| **P01 / P02 Pulsar** | 断言含 `error|timeout`，即 **Pulsar 调用报错/超时也算通过**，方向完全反了。 | 🔴 高 |
| **J02 JMS receive** | 断言含 `null`，即 **返回 null（未消费到）也算通过**。 | 🔴 高 |
| **K01–K03 Kafka** | 断言含 `baafoo`（topic 名子串），真实 broker 回显含 `baafoo` 也会过，较松。 | 🟡 低 |
| **A01 非法 API Key** | 若 server 对非法 key 返回 200（guest 只读放行）则判 SKIP 而非 FAIL，会掩盖鉴权回退。 | 🟡 低 |

**改进方向**：
- 条件类型用例应**发送能唯一命中目标规则的请求**，并从 body 里**精确提取 `matchedBy`** 断言（如 `Get-JsonBody` 后解析 `matchedBy` 字段），去掉 `|mocked` 兜底。
- C01 必须让 test-spring 支持自定义请求头（或新增一个带头的端点），否则删掉该用例。
- P/J/K 断言应只接受成功态（`success`/`stubbed":true`），把 error/timeout/null 归入 FAIL。

### 1.2 逻辑缺陷 / 脆弱写法

| 用例 | 问题 |
|------|------|
| **H04 HTTP DELETE** | 复用 H03 的 `$resp` 变量（H03 在第 466 行赋值，H04 第 472 行未重新请求），靠 `/api/http/methods` 一次返回四个方法的结果再正则匹配。若 JSON 顺序变化或 `.*?` 跨字段匹配，结果不可靠。应分别请求或显式按 key 解析。 |
| **H08 requestCount** | 规则 `requestCount=1` 只在"第一次请求"命中。脚本**从不调用 `/rules/{id}/reset-state` 重置计数器**，二次运行（或并发）时 count 已 >1，用例会偶发失败——**潜伏的 flaky 用例**。 |
| **T02 TCP NIO** | 至今仍是 `Test-Skip`（无响应 body 即跳过），没有真正断言 NIO 拦截。 |
| **M03/M04/M05 模式恢复** | 切模式后 `Start-Sleep -Seconds 3`（M04/M05）短于 `pollIntervalSec=10`，可能 agent 尚未同步就断言；M03 已用 `$MODE_SETTLE_WAIT=12`，但 M04/M05 仍是 3s，不一致。 |

### 1.3 计划与脚本不一致

- `PROJECT-TEST-PLAN.md` §6.4 仍写"当前覆盖 33 个用例 / 91% 通过"，但脚本已扩到 **60 用例**。计划文档过时，应同步更新。
- 计划"待补充用例 IT-L3-001~010"中，规则热更新/模式热切换/环境隔离/录制查询等现已覆盖，但 **OpenAPI 导入、MCP、故障注入、场景集仍缺**。

---

## 二、未覆盖的测试场景

### 2.1 你点名的「规则集（RuleSet）」—— 完全零覆盖 🔴

Server 已实现 `RuleApiHandler` 的 `GET/POST /__baafoo__/api/rulesets`（`FileStorage.listRuleSets/createRuleSet/deleteRuleSet`），PRD US-10 把"规则集导入/导出/共享"列为需求。**但脚本 A 段只测了 rules + environments 的 CRUD，从未碰 `/rulesets`**。
→ 建议新增 `AS01~AS03`：创建规则集 → 查询 → 删除，并验证规则集与规则的关联。

### 2.2 gRPC —— 6 个规则文件是"死资产" 🔴

`grpc-greeter / grpc-error / grpc-delay / grpc-server-streaming / grpc-client-streaming / grpc-bidirectional-streaming` 共 6 个规则文件**从未被脚本注册**（注册数组只有 31 个非 gRPC 规则）。Server 有 `GrpcStubHandler`，但 `baafoo-test-spring` **没有任何 gRPC 客户端 controller**（仅有 Http/Socket/Kafka/Jms/Pulsar/Feign）。结果：gRPC 在脚本里 **0 覆盖**，计划 §7.2 FT-RULE-006、§7.3 FT-REC-006 均未验证。
→ 需给 test-spring 增加 gRPC client 端点，并补充 G01–G06；否则至少把 6 个 grpc 规则从资产里标注"待 client 支持"。

### 2.3 其他已实现但零覆盖的 Server 能力

| 功能 | 代码/PRD 依据 | 脚本覆盖 |
|------|--------------|---------|
| **OpenAPI 导入** | `POST /rules/import-openapi`（PRD R-S10，计划 IT-L3-010） | ❌ |
| **录制删除** | `DELETE /api/recordings/{id}`（PRD FT-REC-009，计划 IT-L3-006） | ❌ 仅 GET 列表 |
| **录制分页 / 详情 / 保留策略** | `GET /recordings?page=`、retentionDays/maxSizeMb（PRD FT-REC-007/008/010） | ❌ |
| **规则撤销 undo** | `POST /rules/{id}/undo`（PRD FT-RULE-011） | ❌ |
| **有状态计数器重置** | `POST /rules/reset-all-state`、`/rules/{id}/reset-state`（R-S2 AC-04） | ❌（导致 H08 潜在 flaky） |
| **继承环境** | `GET /rules/{id}/inherited-environments` | ❌ |
| **场景集（Scene）** | `SceneApiHandler`（PRD FT-SCENE-001~004，计划 IT-L3-004） | ❌ |
| **MCP Server** | 计划 IT-L3-008 | ❌ |
| **故障注入** | 计划 IT-L3-009 | ❌ |
| **Consul DNS 服务发现** | `ConsulDnsAdvice`（InetAddress 重定向到 MockBroker） | ❌ 仅 H09 测了 Consul HTTP stub 规则，未测 DNS 名→broker 重定向 |
| **fail-open 降级** | `BaafooAgent.failOpen`（PRD AC-202） | ❌ |
| **规则优先级 / 多响应分支 / tags 筛选** | PRD FT-RULE-009/012/013 | ❌ |
| **规则分页 API** | `GET /rules?page=&size=` | ❌ 脚本用 legacy 全量 GET |

---

## 三、协议 × 环境模式 覆盖矩阵

环境模式共 5 种：`STUB / PASSTHROUGH / RECORD / RECORD_AND_STUB / RECORD_ALL`。

| 协议 \ 模式 | STUB | PASSTHROUGH | RECORD | RECORD_AND_STUB | RECORD_ALL |
|-------------|:----:|:-----------:|:------:|:---------------:|:----------:|
| **HTTP** | ✅ M01/H* | ✅ M03 | ✅ M04 | ✅ M02 | ✅ M05 |
| **TCP** | ✅ T01–T03 | ❌ | ❌ | ❌ | ❌ |
| **Kafka** | ✅ K01–K03 | ❌ | ❌ | ⚠️ 仅录制方向(D段) | ❌ |
| **Pulsar** | ✅ P01–P03 | ❌ | ❌ | ⚠️ 仅录制方向(D段) | ❌ |
| **JMS** | ✅ J01–J02 | ❌ | ❌ | ⚠️ 仅录制方向(D段) | ❌ |
| **gRPC** | ❌ | ❌ | ❌ | ❌ | ❌ |

**结论**：**只有 HTTP 做到了 5 模式全覆盖**；TCP/Kafka/Pulsar/JMS 仅 STUB 模式有断言；gRPC 全空。你问的"每个协议×每个模式的组合是否都覆盖"——**答案是否定的**，缺口为 5×5 − (HTTP 5 + TCP 1 + Kafka 1 + Pulsar 1 + JMS 1 + 0) = 25 − 9 = **16 个组合未覆盖**（其中 gRPC 5 个整列缺失）。

> 说明：D 段在 `RECORD_AND_STUB` 下重驱了 MQ 的 send/consume 并验证录制 direction，属于"录制验证"而非"模式行为验证"，故标 ⚠️ 而非 ✅。真正的模式行为（如 Kafka PASSTHROUGH 应透传真实 broker、Kafka RECORD 应透传+录制）尚未断言。

---

## 四、优先级改进建议

**P0（先修，影响结论可信度）**
1. 收紧 C01–C09 / H07 断言：去掉 `|mocked` 兜底，改为从 body 解析 `matchedBy` 精确比对；C01 必须先让 test-spring 能发自定义头。
2. 修 P01/P02/J02 断言：error/timeout/null 必须判 FAIL，不能算通过。
3. 补齐 **规则集（RuleSet）CRUD** 用例（AS01–AS03）。
4. H08 前置调用 `/rules/{id}/reset-state` 重置计数器，消除 flaky。

**P1（补齐核心场景）**
5. 扩展 M 段：对 **TCP/Kafka/Pulsar/JMS 也做 PASSTHROUGH / RECORD / RECORD_AND_STUB** 模式断言（至少各选 1 条代表性规则），把矩阵填满。
6. 新增 **录制删除 / 分页** 用例（FT-REC-009/007）。
7. 新增 **规则 undo / 计数器重置 / 继承环境** 用例。
8. 新增 **OpenAPI 导入** 用例（IT-L3-010）。

**P2（增强）**
9. gRPC：给 test-spring 加 gRPC client，激活 6 个 grpc 规则 + G01–G06。
10. 场景集 / MCP / 故障注入 / Consul DNS 重定向 / fail-open 专项用例。
11. 规则优先级、多响应分支、tags 筛选用例。
12. 同步更新 `PROJECT-TEST-PLAN.md` §6.4 的"当前覆盖 33 用例"为 60 用例，并刷新"待补充用例"清单。

---

## 五、总结

脚本当前 60 用例全绿，**但绿色里有水分**：① 多条条件类用例靠 `mocked` 兜底伪通过；② 已实现的重要功能（规则集、gRPC、OpenAPI 导入、录制删除、规则撤销/重置、场景集、MCP、故障注入、Consul DNS、fail-open）完全没测；③ 协议×模式矩阵只有 HTTP 全绿，其余 16 个组合缺失。建议按 P0→P1→P2 顺序补齐，先把"伪通过"和"规则集零覆盖"这两件事解决，测试结论才真正可信。
