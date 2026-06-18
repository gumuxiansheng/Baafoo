# Faker 增量补充 (R-S2 AC-11 increment) — Dev Notes

> 创建时间: 2026-06-18
> 关联 PRD: `.workmemo/2_prd/baafoo-feature-extension-prd.md` §2

## Summary

实现了 PRD §2 "Faker 动态数据函数（增量补充）" 中的 P0 增量需求：
- 新增 `{{faker.randomElement [a,b,c]}}` 函数
- 新增 `{{faker.regexify 'pattern'}}` 函数
- 新增规则级 `fakerSeed` 字段，支持确定性 Faker 输出
- 同步为后续需求（requestCount、faultInjection）预留了 Rule 模型字段和数据库列

## 需求对齐

| AC | 描述 | 实现状态 |
|----|------|---------|
| AC-04 | `{{faker.randomElement [a,b,c]}}` 从数组随机选一个元素 | ✅ |
| AC-05 | `{{faker.regexify '[A-Z]{3}[0-9]{4}'}}` 按正则生成字符串 | ✅ |
| AC-06 | 规则 Schema 新增 `response.fakerSeed`，支持规则级 seed | ✅ |
| AC-07 | Faker 函数与模板变量组合（已实现） | ✅ 无改动 |

**明确不实现**（与 PRD 一致）:
- ❌ 自动类型推断
- ❌ 模板内联 `{{faker.seed}}`

## Files Created

| File | Description |
|------|-------------|
| `baafoo-core/src/test/java/com/baafoo/core/util/FakerIncrementTest.java` | 28 个测试覆盖 randomElement、regexify、seed 三个新功能 |
| `baafoo-core/src/main/java/com/baafoo/core/model/FaultInjection.java` | 故障注入模型（为后续 R-S12 预留，本步骤不使用） |
| `baafoo-server/src/main/java/com/baafoo/server/storage/mybatis/FaultInjectionTypeHandler.java` | FaultInjection 的 MyBatis TypeHandler（为后续预留） |

## Files Modified

| File | Change |
|------|--------|
| `baafoo-core/src/main/java/com/baafoo/core/util/FakerProvider.java` | 新增 `setSeed(Long)` 线程本地 seed 管理；新增 `randomElement` 和 `regexify` 函数；新增 `RegexGenerator` 内部类（支持字符类、量词、分组、交替）；将所有 `RND` 引用替换为 `rnd()` 方法（按线程返回 seeded 或默认 Random） |
| `baafoo-core/src/main/java/com/baafoo/core/util/TemplateEngine.java` | 扩展 `TEMPLATE_PATTERN` 以支持 `randomElement [a,b,c]` 和 `regexify 'pattern'` 中的空格、括号、引号；新增 `render(template, ctx, fakerSeed)` 重载，在渲染期间应用 seed 并在 finally 中清除 |
| `baafoo-core/src/main/java/com/baafoo/core/model/Rule.java` | 新增 `fakerSeed`（Long）、`requestCountReset`（Integer，为 R-S2 AC-13 预留）、`faultInjection`（FaultInjection，为 R-S12 预留）字段及 getter/setter |
| `baafoo-server/src/main/java/com/baafoo/server/handler/StubResponseRenderer.java` | 新增 `sendStubResponse(..., Long fakerSeed)` 重载，将 seed 传递给 TemplateEngine |
| `baafoo-server/src/main/java/com/baafoo/server/handler/HttpStubHandler.java` | 调用 `sendStubResponse` 时传入 `result.getRule().getFakerSeed()` |
| `baafoo-server/src/main/java/com/baafoo/server/storage/dialect/DdlBuilder.java` | 新增 `faker_seed`、`request_count_reset`、`fault_injection_json` 三列（`addColumnIfMissing`，向后兼容） |
| `baafoo-server/src/main/resources/mapper/RuleMapper.xml` | resultMap、INSERT、UPDATE 均加入三个新字段；`fault_injection_json` 使用 `FaultInjectionTypeHandler` |

## Architecture

### Seed 管理（线程本地）

```
HttpStubHandler.channelRead0
  └─ StubResponseRenderer.sendStubResponse(..., rule.getFakerSeed())
       └─ TemplateEngine.render(template, ctx, fakerSeed)
            ├─ if fakerSeed != null: FakerProvider.setSeed(fakerSeed)  // 设置 ThreadLocal<Random>
            ├─ try { 渲染所有 {{faker.xxx}} 变量，使用 rnd() 获取 seeded Random }
            └─ finally: FakerProvider.setSeed(null)  // 清除 ThreadLocal
```

**为什么用 ThreadLocal**：Netty EventLoop 线程池共享，不能在 FakerProvider 中保存实例状态。ThreadLocal 保证：
1. 同一规则并发渲染时各自独立（每个线程自己的 seed）
2. 渲染完成后清除，避免影响后续无 seed 的渲染
3. 跨线程隔离，不会污染其他线程的 Faker 输出

### regexify 实现

`RegexGenerator` 是一个最小化的正则字符串生成器，支持：
- 字面字符
- 字符类 `[a-z]`、`[^0-9]`（取反）
- 量词 `?`、`*`、`+`、`{n}`、`{n,m}`（上限 32 防止失控）
- 交替 `a|b|c`
- 分组 `(...)`
- 转义 `\d`、`\w`、`\s`、`\n`、`\t` 等
- 锚点 `^`、`$`（忽略，仅作位置标记）

**不支持**：反向引用、lookahead/lookbehind、Unicode 类。这些在测试数据生成场景中极少使用。

### randomElement 参数解析

支持三种形式：
- `randomElement [a,b,c]`（带方括号）
- `randomElement a,b,c`（不带方括号）
- `randomElement [a, b, c]`（带空格，元素自动 trim）

## Test Coverage

`FakerIncrementTest.java` 共 28 个测试：

**randomElement（7 个）**:
- 带括号、不带括号、单元素、带空格、空输入、数字、分布验证

**regexify（11 个）**:
- 简单字符类、数字、混合模式、交替、双引号、不带引号、量词范围、`?`、`+`、`\d` 转义、空输入

**seed（10 个）**:
- 同 seed 确定性、不同 seed 差异性、无 seed 非确定性、清除 seed 恢复、seed + randomElement、seed + regexify、线程隔离
- TemplateEngine 集成：seed 渲染、不同 seed、清除后无 seed、seed + randomElement、seed + regexify

## Verification

```bash
# 运行新增测试
mvnw test -pl baafoo-core -Dtest=FakerIncrementTest

# 运行所有 core 测试（确保无回归）
mvnw test -pl baafoo-core

# 编译 server 模块（确保新字段不影响构建）
mvnw compile -pl baafoo-server -am -DskipTests
```

全部通过。
