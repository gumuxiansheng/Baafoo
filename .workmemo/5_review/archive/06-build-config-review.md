# 构建与配置详细审查报告

> 审查范围: 根 pom.xml, README, AGENTS.md, Dockerfile, docker-compose.yml/.test.yml, 
> .gitignore, .env.test, mvnw, .dockerignore, baafoo-server.yml, baafoo-agent.yml, 
> logback.xml, MyBatis Mapper XML, data/ 目录
> 发现 15 个问题 (Critical: 3, High: 2, Medium: 2, Low: 8)

---

## Critical (3)

### BLD-C1: docker-compose.yml 硬编码数据库密码
- **文件**: `docker-compose.yml:33, 52`
- **描述**: `BAAFOO_DB_PASSWORD=baafoo` 和 `POSTGRES_PASSWORD=baafoo` 以明文提交到版本控制。
- **建议**: 使用 Docker secrets 或环境变量覆盖；从不提交密码

### BLD-C2: .env.test 包含数据库凭据已提交到 Git
- **文件**: `.env.test:10-11`
- **描述**: `BAAFOO_DB_USERNAME=baafoo`, `BAAFOO_DB_PASSWORD=baafoo` 直接提交。该文件不在 `.gitignore` 中。
- **建议**: 移除文件或添加到 `.gitignore`；使用模板 `.env.test.example`

### BLD-C3: baafoo-server.yml 默认关闭认证
- **文件**: `baafoo-server.yml:66`
- **描述**: `auth.enabled: false` — 任何能访问 8084 端口的人可完全控制 mock 平台。
- **建议**: 默认启用认证或在文档中显著警告

---

## High (2)

### BLD-H1: Dockerfile 使用 EOL 基础镜像
- **文件**: `Dockerfile:51 (运行时), 22 (构建)`
- **描述**: `openjdk:8-jre-alpine` 和 `maven:3.8-openjdk-8-slim` 都基于 JDK 8（2019 年 EOL），不再接收安全补丁。
- **建议**: 升级到 `eclipse-temurin:17-jre-alpine` 或 `21-jre-alpine`

### BLD-H2: docker-compose.test.yml 使用非标准 `!override` YAML 标签
- **文件**: `docker-compose.test.yml:16`
- **描述**: `ports: !override []` 是 `docker compose` v2 扩展，`docker-compose` v1 不兼容，会导致解析错误。
- **建议**: 移除 `!override` 或显式声明需要 Docker Compose v2

---

## Medium (2)

### BLD-M1: Dockerfile 缺少 exec 导致 SIGTERM 无法优雅关闭
- **文件**: `Dockerfile:83`
- **描述**: `ENTRYPOINT ["sh", "-c", "java …"]` 启动的进程不是 PID 1，不接收 Unix 信号。
- **建议**: 使用 `exec java …` 或 `ENTRYPOINT ["java", …]`

### BLD-M2: data/baafoo.mv.db 和 trace.db 已提交到 Git
- **文件**: `data/baafoo.mv.db`, `data/baafoo.trace.db`
- **描述**: H2 数据库运行时文件和跟踪文件已提交到版本控制。可能包含敏感数据。
- **建议**: 使用 `git rm --cached` 并添加到 `.gitignore`；`data/` 虽在 `.gitignore` 中，但文件是在规则添加前被跟踪的

---

## Low (8)

| ID | 问题 | 详情 |
|----|------|------|
| BLD-L1 | pom.xml 使用 JUnit 4.x（已终结） | 建议升级到 JUnit 5 |
| BLD-L2 | pom.xml 缺少 maven-enforcer-plugin | 无法保证 Java 版本 / 依赖收敛 |
| BLD-L3 | pom.xml JaCoCo 挂载在 prepare-package | 标准做法是 verify 阶段 |
| BLD-L4 | .gitignore 中 `.mvn/wrapper/maven-wrapper.jar` 模式矛盾 | 同时有取消忽略和重新忽略 |
| BLD-L5 | .gitignore 缺少 `.env` 模式 | 只有 `.env.*.local` 被忽略 |
| BLD-L6 | README.md:484 技术栈表格显示 SnakeYAML 1.33，实际 pom.xml 是 2.2 | 文档与实际不一致 |
| BLD-L7 | README.md 配置示例与实际 baafoo-agent.yml 结构不同 | 扁平 vs 嵌套结构 |
| BLD-L8 | baafoo-server.yml:51 `unmatchedDefault: passthrough` 默认转发到真实下游 | 若配置疏忽可能导致生产事故 |

---

## MyBatis Mapper XML 补充审查

| 文件 | 问题 | 严重性 |
|------|------|--------|
| RuleMapper.xml:61 | 前导通配符 LIKE（`'%' || #{keyword} || '%'`）无法使用索引 | Low |
| RecordingMapper.xml:67,71-75 | 大型文本列上前导通配符 LIKE + H2 全文搜索需特定运行时类 | Low |
| 所有 mapper | 一致使用 `#{}` 参数绑定，无 `${}` 替换 | ✅ 良好 |
| 所有 mapper | JSON 存储在 `*_json` 文本列，数据库层无 JSON schema 验证 | Info |
