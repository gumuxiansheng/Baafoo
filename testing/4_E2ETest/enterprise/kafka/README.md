# Kafka 企业级测试

验证 Baafoo Agent 在真实 Apache Kafka 环境下的兼容性和拦截能力。

## 快速开始

```powershell
# 1. 确保已构建项目
cd c:\Dev\Projects\Baafoo
mvnw clean package -DskipTests

# 2. 启动 Kafka 企业级测试环境
cd testing\4_E2ETest\enterprise\kafka
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml up --build

# 3. 等待所有容器启动后，运行冒烟测试
.\smoke-test.ps1

# 4. 停止环境
docker compose -f ../common/docker-compose.base.yml -f docker-compose.yml down -v
```

## 访问地址

| 服务 | 地址 | 说明 |
|------|------|------|
| Baafoo 控制台 | http://localhost:18084 | Web 管理界面 |
| 测试应用 | http://localhost:18090 | Kafka 测试客户端 |
| Kafka (外部) | localhost:19092 | 宿主机访问 Kafka |
| Kafka (内部) | kafka:9092 | 容器内访问 Kafka |

## 测试接口

### 发送 Kafka 消息
```bash
POST http://localhost:18090/api/stub-demo/kafka/send
Content-Type: application/json

{
  "bootstrapServers": "kafka:9092",
  "topic": "enterprise-test-topic",
  "message": "hello world"
}
```

### 消费 Kafka 消息
```bash
POST http://localhost:18090/api/stub-demo/kafka/consume
Content-Type: application/json

{
  "bootstrapServers": "kafka:9092",
  "topic": "enterprise-test-topic"
}
```

## 测试用例

详细测试用例见 [TEST-CASES.md](TEST-CASES.md)
