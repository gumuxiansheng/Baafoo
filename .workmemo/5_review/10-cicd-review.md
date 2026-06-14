# CI/CD 与构建脚本审查补充报告

> 审查范围: Dockerfile ×3, docker-compose ×3, deploy/staging/*, scripts/*, .github/*, .vscode/*
> 发现 14 个问题 (High: 4, Medium: 7, Low: 3)

---

## High (4)

### CICD-H1: Dockerfile 构建缺少 baafoo-agent JAR
- **文件**: `Dockerfile:48, 68`
- **描述**: 第 48 行 `mvn clean package -pl baafoo-server -am ...` 只构建 baafoo-server 及其 Maven 依赖（baafoo-core, baafoo-plugin-api）。baafoo-agent **不是** baafoo-server 的 Maven 依赖（运行时 `-javaagent` 加载），所以第 68 行 `COPY baafoo-agent/target/*.jar` 时会找不到文件 → **docker build 直接失败**。
- **建议**: 改为 `-pl baafoo-server,baafoo-agent -am`

### CICD-H2: docker-compose.yml depends_on 语法无效
- **文件**: `docker-compose.yml:38-40`
- **描述**: 
  ```yaml
  depends_on:
    postgres:
      condition: service_healthy
      required: false   # ← 无效！Compose V2/V3 都不支持 required
  ```
  Docker Compose 解析直接失败。
- **建议**: 移除 `required: false`；postgres 可选时应移除整个 depends_on

### CICD-H3: docker-compose.test.yml 中 nslookup 命令不存在
- **文件**: `docker-compose.test.yml:40`
- **描述**: `nslookup server` 在 `curlimages/curl:latest` 镜像中不存在 — 该镜像只包含 curl，不包含 nslookup/dig。测试永远显示 `✗ DNS解析失败`（虽不阻断但误导）。
- **建议**: 用 `curl http://server:8084/__baafoo__/api/status` 替代 DNS 解析检查

### CICD-H4: deploy/staging 配置明文凭据
- **文件**: `deploy/staging/baafoo-server.yml:55-56,62`
- **描述**: JWT 密钥 `baafoo-staging-jwt-secret-change-in-production-2024` 和 DB 密码 `baafoo` 以明文提交。
- **建议**: 使用 Docker secrets 或环境变量注入

---

## Medium (7)

| ID | 文件:行 | 问题 | 建议 |
|----|---------|------|------|
| CICD-M1 | scripts/test-integration.ps1:24 | 缺少 `--exit-code-from test-runner` | 加上，否则 server 异常退出会掩盖测试结果 |
| CICD-M2 | recordToolUse.sh:6-7 | Shell 参数展开解析 JSON 不安全，嵌套 tool_name 时误匹配 | 改用 `jq` 或 Python |
| CICD-M3 | recordToolUse.ps1:13 | 相对路径 `$hooksDir = '.github\...'` 依赖 CWD | 使用 `$PSScriptRoot` |
| CICD-M4 | docker-compose.yml:38 | server 的 depends_on postgres 与 profiles 冲突（无 profile 时 postgres 不可用） | 移除 depends_on 或统一 profile |
| CICD-M5 | docker-compose.staging.yml:204,210,216,221 | API 密钥在 entrypoint 命令中硬编码 | 使用环境变量 |
| CICD-M6 | Dockerfile:83 | `ENTRYPOINT ["sh", "-c", "java …"]` 无 exec，SIGTERM 无法优雅关闭 | 使用 `exec java …` |
| CICD-M7 | .github/ | **无 GitHub Actions 工作流文件** — 完全无自动化 CI | 添加 CI 工作流 |

---

## Low (3)

| ID | 文件:行 | 问题 |
|----|---------|------|
| CICD-L1 | .vscode/settings.json:2 | `updateBuildConfiguration: "interactive"` 在无头 CI 中可能卡住 |
| CICD-L2 | baafoo-test-app/Dockerfile / baafoo-test-spring/Dockerfile | 同样缺少 agent 构建确认（依赖检查需要看 pom.xml 确认） |
| CICD-L3 | Dockerfile | 无 `HEALTHCHECK` 指令 |

---

## 缺失的 CI 基础设施

- ❌ **无 GitHub Actions**、无 Jenkinsfile、无 GitLab CI
- ❌ **无 shell 版测试脚本** — 只有 `test-integration.ps1`，排除 Linux CI runner
