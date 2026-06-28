# Baafoo UI 测试问题修复记录

**日期**: 2026-06-18
**作者**: 开发团队
**状态**: P0 已分析, P1 已修复

---

## 问题总览

| 问题编号 | 优先级 | 问题描述 | 状态 |
|---------|--------|---------|------|
| UI-001 | P0 | 认证功能过于宽松 | 设计如此 (测试环境) |
| UI-002 | P1 | "登录成功"提示未自动消失 | ✅ 已修复 |

---

## 问题详情

### UI-001: 认证功能过于宽松 (P0)

#### 问题描述
使用任意用户名/密码（如 `wronguser/wrongpass`）均可成功登录管理控制台。

#### 根因分析
**这是测试环境的预期行为**，不是 Bug。

在 `deploy/staging/baafoo-server.yml` 中，认证配置为：
```yaml
auth:
  enabled: false  # 测试环境关闭认证
  localBypass: false
```

当 `auth.enabled = false` 时，`AuthService.login()` 方法会跳过密码验证，直接返回 admin token：

```java
// AuthService.java 第 165-168 行
public LoginResult login(String username, String password, Long expiresInMs) {
    if (!authEnabled) {
        String token = generateToken("system", "admin", DEFAULT_TOKEN_EXPIRY_MS);
        return new LoginResult(true, token, "admin", "Authentication disabled, admin token issued");
    }
    // ... 正常验证逻辑
}
```

#### 设计原因
Staging 环境配置注释明确说明了原因：
```yaml
# Authentication configuration — 测试环境关闭认证（Agent不支持API Key）
auth:
  enabled: false
```

Baafoo Agent 使用 Java Agent 方式注入，无法在请求中携带 API Key 头。因此在测试环境中关闭认证，允许 Agent 正常连接挡板服务。

#### 解决方案

| 环境 | 建议配置 | 说明 |
|------|---------|------|
| **开发环境** | `enabled: false` | 方便调试，支持 Agent 接入 |
| **测试环境** | `enabled: true` | 需要严格认证 |
| **生产环境** | `enabled: true` | 必须启用认证 |

**生产环境部署检查清单**:
- [ ] `auth.enabled: true`
- [ ] 设置强密码的 JWT Secret（至少 32 字符）
- [ ] 配置 API Key 映射或启用数据库用户认证
- [ ] 关闭 `localBypass`（除非确实需要）

#### 影响评估
| 场景 | 影响 |
|------|------|
| 测试环境 (Docker Staging) | 无影响 - 预期行为 |
| 生产环境 | 高风险 - 必须修复 |

---

### UI-002: "登录成功"提示未自动消失 (P1)

#### 问题描述
登录成功后显示的 "登录成功" 提示消息在整个会话中持续显示，不自动消失。

#### 根因分析
`LoginPage.vue` 中使用 `ElMessage.success()` 时未设置 `duration` 参数：

```javascript
// 修复前
ElMessage.success('登录成功')
```

Element Plus 默认情况下 success 消息不会自动关闭，需要显式设置 duration。

#### 修复方案

```javascript
// 修复后 (LoginPage.vue 第 86 行)
ElMessage.success({ message: '登录成功', duration: 3000 })
```

#### 修复内容
- 文件: `web/src/views/LoginPage.vue`
- 修改: 为成功提示添加 `duration: 3000`（3秒后自动消失）
- 状态: ✅ 已修复

#### 验证方法
1. 重新构建前端: `cd web && npm run build`
2. 访问登录页面
3. 输入任意凭据登录
4. 确认 "登录成功" 消息在 3 秒后自动消失

---

## 相关代码

### 前端修改

| 文件 | 修改内容 |
|------|---------|
| `web/src/views/LoginPage.vue` | P1: 添加消息自动关闭 |

### 后端配置

| 文件 | 配置项 |
|------|--------|
| `deploy/staging/baafoo-server.yml` | 测试环境配置 (auth.enabled: false) |
| `baafoo-server/src/main/resources/baafoo-server.yml` | 默认配置模板 |

---

## 测试建议

### P0 问题测试（生产环境部署前）
```bash
# 1. 启用认证
sed -i 's/enabled: false/enabled: true/' deploy/production/baafoo-server.yml

# 2. 测试错误凭据应被拒绝
curl -X POST http://localhost:8084/__baafoo__/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"wronguser","password":"wrongpass"}'
# 预期: {"success":false,"code":401,"message":"Invalid username or password"}

# 3. 测试正确凭据登录
curl -X POST http://localhost:8084/__baafoo__/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin@123456"}'
# 预期: {"success":true,"data":{"token":"...","role":"admin"}}
```

### P1 问题测试（已修复）
```bash
# 重新构建前端
cd web && npm run build

# 启动服务器
java -jar ../baafoo-server/target/baafoo-server-*.jar

# 浏览器测试
# 1. 访问 http://localhost:8084/#/login
# 2. 输入任意凭据登录
# 3. 确认 "登录成功" 消息在 3 秒后自动消失
```

---

## 后续优化建议

1. **统一消息提示行为**
   - 所有 `ElMessage.success()` 调用添加 `duration: 3000`
   - 考虑创建消息提示封装组件

2. **认证配置健康检查**
   - 启动时检测 `auth.enabled: false` 并发出警告日志
   - 生产环境强制要求设置 JWT Secret

3. **测试用例补充**
   - 添加认证功能 E2E 测试
   - 测试不同角色权限隔离
