# 所有 Critical 级别问题汇总

## 按模块分类

### 1. baafoo-core (3)

| ID | 文件 | 问题 | 影响 |
|----|------|------|------|
| CORE-C1 | ServerConfig.java:187-213 | JWT Secret + API Keys 通过 getter 序列化泄露 | 凭据泄露 |
| CORE-C2 | User.java:26-27,38-39 | passwordHash + apiKey 通过 getter 泄露 | 凭据泄露 |
| CORE-C3 | PaginatedResult.java:34,49 | size<=0 时除零崩溃 | 服务可用性 |

### 2. baafoo-server (6)

| ID | 文件 | 问题 | 影响 |
|----|------|------|------|
| SRV-C1 | BaafooServer.java:237,258 | 硬编码管理密码 `B@af00!Adm1n#2026` | 权限绕过 |
| SRV-C2 | PassthroughProxy.java:84-86 | SSL 证书验证跳过 | MITM 攻击 |
| SRV-C3 | AuthService.java:201-212 | SHA-256 单次密码哈希 | 凭据提取 |
| SRV-C4 | AuthApiHandler.java:32-44 | 登录端点无限速 | 暴力破解 |
| SRV-C5 | StaticFileHandler.java:136 | 路径遍历（replace 绕过） | 任意文件读取 |
| SRV-C6 | AuthFilter / ApiContext | 双权限检查系统不一致 | 权限绕过 |

### 3. baafoo-agent (5)

| ID | 文件 | 问题 | 影响 |
|----|------|------|------|
| AGT-C1 | RouteManager.java:82-84 | setMode 不更新全局 CURRENT_MODE | 路由模式不一致 |
| AGT-C2 | GlobalRouteState.java:250-255 | startRecording TOCTOU | 录制数据丢失 |
| AGT-C3 | RecordingBuffer.java:95-121 | flush 与 add 竞态 | 录制数据丢失 |
| AGT-C4 | GlobalRouteState.java:158-173 | recordDns 缓存满丢条目 | DNS 路由失败 |
| AGT-C5 | RouteManager.java:277-295 | Bootstrap CL 上非原子 clear+putAll | 路由瞬时空 |

### 4. 构建/配置 (3)

| ID | 文件 | 问题 | 影响 |
|----|------|------|------|
| BLD-C1 | docker-compose.yml:33,52 | 硬编码数据库密码 | 凭据泄露 |
| BLD-C2 | .env.test | 数据库凭据已提交到 Git | 凭据泄露 |
| BLD-C3 | baafoo-server.yml:66 | 默认关闭认证 | 无认证访问 |

---

## 修复优先级矩阵

| 优先级 | ID | 风险类型 | 修复难度 | 影响范围 |
|--------|----|---------|---------|---------|
| **P0** | SRV-C1 / SRV-C2 / SRV-C3 / SRV-C5 / SRV-C6 | 安全 (可被直接利用) | 低-中 | 全局 |
| **P0** | BLD-C1 / BLD-C2 / BLD-C3 | 安全 (配置) | 低 | 全局 |
| **P0** | CORE-C1 / CORE-C2 | 安全 (数据泄露) | 低 | API 响应 |
| **P0** | AGT-C1 / AGT-C2 / AGT-C3 / AGT-C4 / AGT-C5 | 逻辑/数据丢失 | 中-高 | Agent 核心功能 |
| **P1** | CORE-C3 | 可用性 | 低 | Pagination API |
| **P1** | SRV-C4 | 安全 (暴力破解) | 低 | 登录端点 |

---

## 跨模块依赖关系

```mermaid
graph TD
    SRV-C1(硬编码密码) -->|直接利用| 管理员权限
    SRV-C2(SSL 跳过) -->|MITM| 数据泄露
    SRV-C3(SHA-256 单次) -->|破解| 用户凭据
    SRV-C5(路径遍历) -->|任意文件| 配置/数据泄露
    SRV-C6(双权限系统) -->|绕过| 未授权访问
    CORE-C1/JWT泄露 + CORE-C2/User泄露 -->|序列化| API响应数据泄露
    AGT-C1(模式不同步) -->|级联| Agent 路由错误
    AGT-C2/AGT-C3(竞态) -->|数据丢失| 录制不可靠
    AGT-C4(DNS缓存) -->|路由失败| 拦截失效
    AGT-C5(clear+putAll) -->|瞬时空| 请求错误路由
    BLD-C1/BLD-C2(硬编码凭据) -->|SCM泄露| 数据库访问
    BLD-C3(auth关闭) -->|全网暴露| 完全控制
```

---

## 快速修复指南 (5 分钟内可修复的 Critical 问题)

1. **`User.java` + `ServerConfig.java`**: 添加 `@JsonIgnore` — 4 行变更
2. **`PaginatedResult.java`**: 添加 size<=0 guard — 2 行变更
3. **`docker-compose.yml` + `.env.test`**: 移除明文密码 — 文件编辑
4. **`baafoo-server.yml`**: `auth.enabled: true` — 1 行变更
5. **`RouteManager.java:82-84`**: 改为 `AgentManifest.setCurrentMode()` — 1 行变更
6. **`GlobalRouteState.java:250-255`**: 检查后 put 模式 — 5 行变更
7. **`RecordingBuffer.java:95-121`**: synchronized 保护 — 5 行变更
