# 22. Chaos 工程 (P2)

> **PRD**: §6 R-S13 AC-01~05 — Chaos 配置文件 + REST API
> **提交**: 待提交
> **状态**: 实现完成，测试通过

## 一、需求摘要

PRD §6 R-S13 要求实现 Chaos 工程的场景化封装：YAML/JSON 配置文件定义 Chaos 场景，REST API 激活/停用，紧急停止一键清除。

实现 AC 清单：
- **AC-01**: ChaosProfile 模型（名称、目标环境、故障规则列表、可选定时表达式）
- **AC-02**: `POST /api/chaos/profiles/activate` — 激活场景，生成故障规则并持久化
- **AC-03**: `POST /api/chaos/profiles/deactivate` — 停用场景，清除故障规则
- **AC-04**: `GET /api/chaos/profiles/status` — 查看当前激活的场景列表
- **AC-05**: `POST /api/chaos/emergency-stop` — 紧急停止，一键清除所有 Chaos 规则

明确不实现（v2.0）：
- ❌ 预置行业场景包（电商/金融/IoT）— 数据无依据，放 v3.0
- ❌ Chaos 实验界面 R-W11 — 放 v3.0
- ❌ 实验结果报告（指标采集 + 聚合统计）— 放 v3.0

## 二、文件变更

### 新增文件
| 文件 | 用途 |
|---|---|
| `baafoo-core/src/main/java/com/baafoo/core/model/ChaosProfile.java` | Chaos 场景模型（含 ChaosRule 内部类） |
| `baafoo-core/src/main/java/com/baafoo/core/util/ChaosManager.java` | Chaos 场景管理器（注册/激活/停用/紧急停止/状态查询） |
| `baafoo-core/src/test/java/com/baafoo/core/util/ChaosManagerTest.java` | 单元测试（35 个用例） |
| `baafoo-server/src/main/java/com/baafoo/server/api/ChaosApiHandler.java` | Chaos REST API 端点处理器 |

### 修改文件
| 文件 | 变更 |
|---|---|
| `baafoo-server/src/main/java/com/baafoo/server/api/ManagementApiHandler.java` | 新增 ChaosManager 字段和构造函数；注册 ChaosApiHandler 到 handlers 列表；暴露 `getChaosManager()` 供外部注册 profile |

## 三、架构设计

### 3.1 ChaosProfile 模型

```java
class ChaosProfile {
    String name;              // 唯一标识
    String description;       // 场景描述
    List<String> environments;// 目标环境列表
    List<ChaosRule> rules;    // 故障注入规则
    String schedule;          // 可选 cron 表达式（Phase 3）

    static class ChaosRule {
        String name;
        String method;        // HTTP 方法
        String path;          // HTTP 路径（regex）
        FaultInjection faultInjection;
    }
}
```

### 3.2 ChaosManager 管理器

**线程安全**: 使用 `ConcurrentHashMap` 存储 profiles，`Collections.newSetFromMap` 存储 active 状态。

**核心方法**:
- `registerProfile(profile)` — 注册场景配置
- `activate(name)` — 激活场景，生成 Rule 列表
- `deactivate(name)` — 停用场景，返回需清理的 ruleId 列表
- `emergencyStop()` — 紧急停止所有活跃场景
- `getStatus()` — 获取所有场景状态
- `isActive(name)` — 检查场景是否活跃

### 3.3 规则生成策略

激活场景时，为每个 ChaosRule 生成 Baafoo Rule：

| 字段 | 值 |
|---|---|
| id | `chaos-{profileName}-{index}` |
| name | ChaosRule.name 或 "Chaos: {profile} #{index}" |
| protocol | "http" |
| priority | 50（高于默认 100，确保 Chaos 规则优先匹配） |
| tags | ["chaos", "chaos-{profileName}"] |
| environments | 从 profile 继承 |
| conditions | [method equals, path regex] |
| faultInjection | 从 ChaosRule 继承 |
| responses | 默认 200 响应（无故障触发时） |

**ruleId 命名约定**: `chaos-{profileName}-{index}` 便于：
- 停用时按 ID 列表精确删除
- 紧急停止时批量清理
- 通过 tag "chaos" 快速识别 Chaos 生成的规则

### 3.4 REST API 端点

| 端点 | 方法 | 权限 | 功能 |
|---|---|---|---|
| `/api/chaos/profiles/activate` | POST | `rule:create` | 激活场景，持久化生成的规则 |
| `/api/chaos/profiles/deactivate` | POST | `rule:delete` | 停用场景，删除生成的规则 |
| `/api/chaos/profiles/status` | GET | - | 查询所有场景状态 |
| `/api/chaos/emergency-stop` | POST | `rule:delete` | 紧急停止，删除所有活跃场景的规则 |

**请求体格式**（activate/deactivate）:
```json
{"profileName": "my-scenario"}
```

**响应格式**（activate）:
```json
{
  "profileName": "my-scenario",
  "generatedRuleCount": 3,
  "savedRuleIds": ["chaos-my-scenario-0", "chaos-my-scenario-1", "chaos-my-scenario-2"],
  "summary": "Activated Chaos profile 'my-scenario' with 3 fault injection rules"
}
```

### 3.5 Profile 注册方式

当前版本通过编程方式注册 profile：
```java
ManagementApiHandler handler = new ManagementApiHandler(storage, authService);
handler.getChaosManager().registerProfile(profile);
```

未来可扩展为：
- 从 `chaos-profiles.yaml` 配置文件自动加载（启动时）
- 通过 REST API 动态注册（需新增端点）

## 四、测试覆盖

### ChaosManagerTest（35 个用例）

**Profile 注册（8 个）**:
- 注册单个 profile
- 注册同名 profile 替换
- null profile / null name / empty name 抛异常
- 批量注册
- null 列表 no-op
- 获取不存在的 profile 返回 null
- 空列表

**激活（10 个）**:
- 激活成功
- 激活不存在的 profile
- 重复激活
- ruleId 格式验证
- chaos tags 验证
- 优先级 50（高于默认）
- method + path 条件
- faultInjection 透传
- environments 继承
- 默认 200 响应
- 多规则场景
- 无规则场景

**停用（5 个）**:
- 停用活跃 profile
- 停用非活跃 profile
- 停用不存在的 profile
- 返回所有 ruleId
- 停用后可重新激活

**紧急停止（4 个）**:
- 无活跃场景
- 单场景紧急停止
- 多场景紧急停止
- 清除所有活跃状态

**状态查询（5 个）**:
- 空状态
- 含活跃/非活跃场景
- ruleCount 字段
- environments 字段
- getActiveProfileNames

## 五、验证

```bash
# 单元测试
mvnw test -pl baafoo-core "-Dtest=ChaosManagerTest"
# 结果：35 tests, 0 failures

# 全模块回归
mvnw test -pl baafoo-core
# 结果：364 tests, 0 failures, 0 errors

# 服务端编译
mvnw compile -pl baafoo-server -am -DskipTests
# 结果：BUILD SUCCESS
```

## 六、PRD AC 对齐

| AC | 状态 | 说明 |
|---|---|---|
| AC-01 ChaosProfile 模型 | ✅ | name/environments/rules/可选 schedule |
| AC-02 activate API | ✅ | POST /api/chaos/profiles/activate，持久化规则 |
| AC-03 deactivate API | ✅ | POST /api/chaos/profiles/deactivate，删除规则 |
| AC-04 status API | ✅ | GET /api/chaos/profiles/status，含 active 状态 |
| AC-05 emergency-stop | ✅ | POST /api/chaos/emergency-stop，一键清除 |

## 七、后续工作

- **配置文件加载**: 从 `chaos-profiles.yaml` 自动加载 profile（启动时）
- **定时激活**: 实现 schedule cron 表达式（Phase 3）
- **R-W11**: Chaos 实验界面（v3.0）
- **实验结果报告**: 指标采集 + 聚合统计（v3.0）
- **预置场景包**: 电商/金融/IoT 行业场景（v3.0，需实际数据支撑）
