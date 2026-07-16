# MyBatis Mapper XML 审查补充报告

> 审查 6 个 mapper XML 文件 + 对应 Java 接口
> 发现 17 个问题 (High: 2, Medium: 10, Low: 5)

---

## High (2)

### MAP-H1: RuleMapper.createRuleSet 返回类型不匹配 → ClassCastException
- **文件**: `RuleMapper.java:48` (Java 接口) + `RuleMapper.xml`
- **描述**: Java 接口声明 `RuleSet createRuleSet(RuleSet ruleSet)`，但 XML `<insert>` 返回 `int`（影响行数）。MyBatis 会在运行时尝试将 `Integer` 转型为 `RuleSet`，抛出 `ClassCastException`。
- **建议**: 改为 `int createRuleSet(RuleSet ruleSet)` 或 `void`

### MAP-H2: RecordingMapper 大文本列前导通配符 LIKE
- **文件**: `RecordingMapper.xml:71-75`
- **描述**: `request_body`, `response_body`, `request_headers_json`, `response_headers_json` 是大 TEXT/CLOB 列，使用前导通配符 LIKE 每次查询都强制全表扫描，规模增长后灾难性性能问题。
- **建议**: 需要全文检索引擎（已有 dialect 分支），或设置最小关键词长度，或至少加 LIMIT

---

## Medium (10)

| ID | 文件:行 | 问题 | 建议 |
|----|---------|------|------|
| MAP-M1 | AgentMapper.xml:48-50 | `listAgents` 无 pagination | 加 LIMIT |
| MAP-M2 | EnvironmentMapper.xml:24-26 | `listEnvironments` 无 pagination | 加 LIMIT |
| MAP-M3 | RecordingMapper.xml:47 | `agent_ip` 前导通配符 LIKE | 改为后缀匹配或全文本索引 |
| MAP-M4 | RecordingMapper.xml:56 | `path` 前导通配符 LIKE | 同上 |
| MAP-M5 | RecordingMapper.xml:116-125 | `trimRecordings` 用 `NOT IN (SELECT ... LIMIT ...)` 低效 | 改用 CTE 或 OFFSET |
| MAP-M6 | RuleMapper.xml:61-62 | `name`/`id` 前导通配符 LIKE | 使用 trigram 索引或后缀匹配 |
| MAP-M7 | RuleMapper.xml:129-138 | `deleteOldRuleHistory` 用 `NOT IN (SELECT ... LIMIT ...)` 低效 | 改用 CTE 或 OFFSET |
| MAP-M8 | RuleMapper.xml:50-52 | `listRules` 无 pagination | 加 LIMIT + OFFSET |
| MAP-M9 | RuleMapper.xml:163-165 | `listRuleSets` 无 pagination | 加 LIMIT |
| MAP-M10 | SceneMapper.xml:24-26 | `listScenes` 无 pagination | 加 LIMIT |

---

## Low (5)

| ID | 文件:行 | 问题 |
|----|---------|------|
| MAP-L1 | AgentMapper.xml:48-49 | `SELECT *` 脆弱，列变更可能静默破坏映射 |
| MAP-L2 | RecordingMapper.xml:80-86 | `listRecordings` 未复用 `searchConditions` SQL 片段 |
| MAP-L3 | RuleMapper.xml:89-90 | INSERT/UPDATE 与 resultMap 类型处理器不一致 |
| MAP-L4 | RuleMapper.xml:56 | `WHERE 1=1` 旧式动态 SQL 写法 |
| MAP-L5 | UserMapper.xml:19-21 | `listUsers` 无 pagination（但用户数通常很小） |

---

## ✅ 正面发现

- **无 `${}` SQL 注入风险** — 所有 mapper 一致使用 `#{}` 参数绑定
- **所有 DELETE/UPDATE 都有 WHERE 子句** — 无全表误操作风险
