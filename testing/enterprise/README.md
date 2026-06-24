# Baafoo 企业级应用测试

使用真实的企业级应用验证 Baafoo Agent 的兼容性、有效性、性能影响和稳定性。

## 测试应用清单

### P0 优先级（必须）
| 应用 | 目录 | 协议覆盖 | 状态 |
|------|------|---------|------|
| Apache Kafka | [kafka/](./kafka) | Kafka | 待搭建 |
| Spring Boot PetClinic | [petclinic/](./petclinic) | HTTP | 待搭建 |

### P1 优先级（重要）
| 应用 | 目录 | 协议覆盖 | 状态 |
|------|------|---------|------|
| Spring Cloud Gateway | [spring-cloud-gateway/](./spring-cloud-gateway) | HTTP | 待搭建 |
| Nacos | [nacos/](./nacos) | HTTP + TCP | 待搭建 |
| ActiveMQ Artemis | [artemis/](./artemis) | JMS | 待搭建 |
| Keycloak | [keycloak/](./keycloak) | HTTP | 待搭建 |

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
9. **内存泄漏检查**：运行 1 小时后，内存趋势平稳
10. **CPU 开销评估**：与无 Agent 对比，CPU 增加在可接受范围

## 快速开始

### 前置条件
- Docker 20.10+
- Docker Compose 2.0+
- 已构建 Baafoo JAR：`mvnw clean package -DskipTests`

### 运行单个应用测试

```powershell
# 进入应用目录
cd testing/enterprise/kafka

# 启动环境
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml up --build

# 运行冒烟测试
.\smoke-test.ps1

# 停止环境
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml down -v
```

### 通用配置

所有企业级应用测试共享以下配置：
- Baafoo Server API: `http://localhost:18084`
- Baafoo Server 内部地址: `baafoo-server:8084`
- API Key: `enterprise-admin-key`
- 网络: `baafoo-enterprise-net`
