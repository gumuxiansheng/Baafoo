# Baafoo 企业级应用测试

使用真实的企业级应用验证 Baafoo Agent 的兼容性、有效性、性能影响和稳定性。

## 测试应用清单

### P0 优先级（必须）
| 应用 | 目录 | 协议覆盖 | 用例数 | 状态 |
|------|------|---------|--------|------|
| Apache Kafka | [kafka/](./kafka) | Kafka | 11 (10 PASS + 1 SKIP) | ✅ 已完成 |
| Spring Boot PetClinic | [petclinic/](./petclinic) | HTTP | 13 (10 PASS + 1 SKIP + 2 SKIP) | ✅ 已完成 |
| Spring Cloud Alibaba | [spring-cloud-alibaba/](./spring-cloud-alibaba) | HTTP + Feign + Nacos | 12 | ✅ 已完成 |

### P1 优先级（重要）
| 应用 | 目录 | 协议覆盖 | 状态 |
|------|------|---------|------|
| Spring Cloud Gateway | — | HTTP | 待搭建 |
| Nacos (独立) | — | HTTP + TCP | 待搭建 |
| ActiveMQ Artemis | — | JMS | 待搭建 |
| Keycloak | — | HTTP | 待搭建 |

## 通用测试项

每个企业级应用都执行以下测试：

1. **冒烟测试**：应用启动 + Agent 挂载无异常
2. **Agent 注册验证**：Server 端可看到 Agent 注册信息
3. **基础 Mock 验证**：配置规则后，业务接口返回 Mock 数据
4. **Passthrough 模式验证**：切换到 passthrough 后，调用真实服务正常
5. **Record 模式验证**：切换到 record 后，能录制到请求响应
6. **环境模式热切换**：运行中切换模式，应用无影响
7. **应用功能完整性**：应用核心功能均正常工作
8. **无类加载冲突**：无 ClassNotFoundException / NoClassDefFoundError
9. **内存泄漏检查**：短期运行后，内存趋势平稳
10. **CPU 开销评估**：与无 Agent 对比，CPU 增加在可接受范围

## 快速开始

### 前置条件
- Docker 20.10+
- Docker Compose 2.0+
- 已构建 Baafoo JAR：`mvnw clean package -DskipTests`

### 运行单个应用测试

```powershell
# Kafka
cd testing/4_E2ETest/enterprise/kafka
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml up --build
.\smoke-test.ps1
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml down -v

# PetClinic
cd testing/4_E2ETest/enterprise/petclinic
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml up --build
.\smoke-test.ps1
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml down -v

# Spring Cloud Alibaba
cd testing/4_E2ETest/enterprise/spring-cloud-alibaba
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml up --build
.\smoke-test.ps1
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml down -v
```

### 运行所有应用测试

```powershell
cd testing/4_E2ETest/enterprise

# 启动所有环境
.\enterprise-env.ps1 -Action start -Apps all

# 运行统一冒烟测试
.\run-all-smoke-tests.ps1

# 停止所有环境
.\enterprise-env.ps1 -Action stop -Apps all
```

### 通用配置

所有企业级应用测试共享以下配置：
- Baafoo Server API: `http://localhost:18084`
- Baafoo Server 内部地址: `baafoo-server:8084`
- API Key: `enterprise-admin-key`
- 网络: `baafoo-enterprise-net`

## 测试报告

- [ENTERPRISE-TEST-REPORT.md](./ENTERPRISE-TEST-REPORT.md) — Kafka + PetClinic 测试报告
- [MULTI-AGENT-TEST-REPORT.md](./MULTI-AGENT-TEST-REPORT.md) — 多 Agent 共存测试报告
