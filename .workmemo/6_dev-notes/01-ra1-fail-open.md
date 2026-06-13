# R-A1: fail-open 配置选项

> 实施时间: 2026-06-13
> PRD 需求: R-A1 AC-03

## 需求描述

Agent 加载失败时，默认 fail-closed（打 ERROR 日志，请求透传）；提供 `baafoo.agent.fail-open=true` 配置项允许用户主动选择 fail-open 行为（静默透传，仅 WARN 级别日志）。

## 实施内容

1. **AgentConfig** 新增 `failOpen` 字段（默认 `false`），对应配置键 `failOpen`
2. **BaafooAgent.premain()** catch 块根据 `failOpen` 值决定日志级别：
   - `failOpen=false`（默认）：ERROR 级别日志，明确提示 "fail-closed"
   - `failOpen=true`：WARN 级别日志，提示 "fail-open mode"
3. **baafoo-agent.yml** 新增 `failOpen: false` 配置项及注释说明

## 修改文件

- `baafoo-core/src/main/java/com/baafoo/core/config/AgentConfig.java` — 新增 failOpen 字段和 getter/setter
- `baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java` — premain catch 块使用 failOpen 配置
- `baafoo-agent/src/main/resources/baafoo-agent.yml` — 新增 failOpen 配置项
