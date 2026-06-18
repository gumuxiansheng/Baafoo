# 24. 代码审查修复 (S1-S12)

> **来源**: 代码审查反馈 12 项
> **提交**: 待提交
> **状态**: 全部修复，370 测试通过，前端构建通过

## 一、修复清单

### OpenApiImporter (S1, S2)

| # | 问题 | 修复 |
|---|---|---|
| S1 | `convertPathToRegex` 未转义正则特殊字符，`/api/v1.0/users` 的 `.` 匹配任意字符 | 新增 `quoteStaticSegments()` 方法，对静态段做 `Pattern.quote()`，参数占位符 `{id}` 替换为 `[^/]+` |
| S2 | 无输入大小限制，恶意大 JSON 可致 OOM | 新增 `MAX_INPUT_SIZE_BYTES = 10MB` 常量，`importSpec` 和 `handleOpenApiImport` 双重校验 |

### HttpStubHandler (S3, S4)

| # | 问题 | 修复 |
|---|---|---|
| S3 | READ_TIMEOUT 不响应不关闭，Channel 永久占用致 fd 耗尽 | 添加 `ctx.executor().schedule(ctx::close, 30, SECONDS)` 兜底关闭 |
| S4 | `new Random()` 非线程安全，依赖 handler 不被共享的隐式假设 | 改用 `ThreadLocalRandom.current()`，添加注释说明不可标 `@Sharable` |

### ChaosManager (S5, S6, S7, S8)

| # | 问题 | 修复 |
|---|---|---|
| S5 | `activate()` contains→生成→add 非原子，可重复生成规则 | 用 `activeProfileNames.add()` 返回值做原子检查 |
| S6 | `deactivate()` contains→收集→remove 非原子，可重复删除 | 用 `activeProfileNames.remove()` 返回值做原子检查 |
| S7 | `emergencyStop()` 快照与 `clear()` 之间可产生孤儿规则 | 改为 `synchronized` + 逐个 `remove()`，不用 `clear()` |
| S8 | `profileName` 无字符约束，特殊字符破坏 ruleId | 新增 `VALID_PROFILE_NAME = [a-zA-Z0-9_-]+` 校验 |

### ChaosApiHandler (S9, S10)

| # | 问题 | 修复 |
|---|---|---|
| S9 | `/status` 缺权限检查，可暴露故障策略 | 添加 `ctx.requirePermission("rule", "read")` |
| S10 | `handleActivate` 持久化无事务保护，部分失败致状态不一致 | try-catch 补偿回滚：`chaosManager.deactivate()` + 删除已保存规则 |

### OpenApiImportDialog (S11, S12)

| # | 问题 | 修复 |
|---|---|---|
| S11 | `setTimeout(300ms)` 重置状态，快速重开弹窗会清空新状态 | 改用 `@closed` 事件（动画结束后触发）重置状态 |
| S12 | checkbox 勾选未实际生效，误导用户 | 移除 checkbox 改为序号列，添加提示"当前版本将导入全部规则" |

## 二、测试新增

- `OpenApiImporterTest`: +2 测试 (`testOversizedContentThrowsException`, `testPathWithRegexMetacharactersIsEscaped`)
- `ChaosManagerTest`: +4 测试 (profileName 字符约束: 空格/斜杠/中文/合法特殊字符)
- 更新 4 个路径正则测试断言以匹配 `Pattern.quote()` 产生的 `\Q...\E` 格式

## 三、验证

- baafoo-core: 370 测试通过 (原 364 + 6 新增)
- baafoo-server: 编译通过
- web: `npm run build` 成功 (2238 modules)
