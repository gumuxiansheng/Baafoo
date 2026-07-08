# Spring Boot PetClinic 企业级测试

验证 Baafoo Agent 在经典 Spring Boot 企业应用中的兼容性和拦截能力。

## 快速开始

```powershell
# 1. 确保已构建项目
cd c:\Dev\Projects\Baafoo
mvnw clean package -DskipTests

# 2. 启动 PetClinic 企业级测试环境
cd testing\4_E2ETest\enterprise\petclinic
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
| PetClinic 应用 | http://localhost:19966/petclinic/ | PetClinic REST API |
| PetClinic API 文档 | http://localhost:19966/petclinic/swagger-ui.html | Swagger UI |

## 主要测试 API

| 接口 | 方法 | 说明 |
|------|------|------|
| /petclinic/api/vets | GET | 兽医列表 |
| /petclinic/api/owners | GET | 业主列表 |
| /petclinic/api/pets | GET | 宠物列表 |
| /petclinic/api/specialties | GET | 专科列表 |
| /petclinic/api/visits | GET | 就诊记录 |
| /petclinic/api/pettypes | GET | 宠物类型 |

## 测试用例

详细测试用例见 [TEST-CASES.md](TEST-CASES.md)

## 注意事项

1. **首次启动**：PetClinic 首次启动需要初始化数据库，可能需要 30-60 秒
2. **镜像拉取**：首次运行需要拉取 springcommunity/spring-petclinic-rest 镜像
3. **端口冲突**：确保 18084、19966 端口未被占用
4. **模式切换**：环境模式切换后约 10 秒生效（Agent 轮询间隔）
