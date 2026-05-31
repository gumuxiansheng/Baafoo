# Baafoo Robot Framework 测试

## 前置条件

1. **Python 3.8+** 已安装
2. **Robot Framework 7.x** 已安装
3. **robotframework-requests** 已安装
4. **Baafoo Server 已启动**（默认端口 8080）

## 安装依赖

```powershell
pip install robotframework robotframework-requests
```

## 启动 Baafoo Server

```powershell
# 先构建项目
cd C:\Dev\Projects\Baafoo
mvn clean package -DskipTests

# 启动 Server
java -jar baafoo-server/target/baafoo-server-1.0.0-SNAPSHOT.jar
```

## 运行测试

```powershell
# 运行全部测试
python -m robot --outputdir results robot-tests/

# 只运行 API 基础测试
python -m robot --outputdir results robot-tests/api_tests.robot

# 只运行 CRUD 流程测试
python -m robot --outputdir results robot-tests/crud_tests.robot

# 只运行 Agent 控制通道测试
python -m robot --outputdir results robot-tests/agent_tests.robot

# 只运行 HTTP Mock 功能测试
python -m robot --outputdir results robot-tests/mock_tests.robot

# 按标签运行
python -m robot --outputdir results --include smoke robot-tests/
python -m robot --outputdir results --include crud robot-tests/
python -m robot --outputdir results --exclude negative robot-tests/

# 指定 Server 地址（默认 localhost:8080）
python -m robot --outputdir results --variable BASE_URL:http://192.168.1.100:8080/__baafoo__/api robot-tests/api_tests.robot
```

## 测试文件说明

| 文件 | 说明 | 覆盖 API |
|------|------|----------|
| `api_tests.robot` | 基础 API 冒烟测试 | 全部 GET 列表接口 + 创建接口 + 异常场景 |
| `crud_tests.robot` | 完整 CRUD 生命周期 | Rules / Environments / Scenes 的增删改查 + Undo |
| `agent_tests.robot` | Agent 控制通道 | register / heartbeat / poll / recordings |
| `mock_tests.robot` | HTTP 挡板功能 | Mock 端口 9000 的请求匹配和响应返回 |

## 标签体系

| 标签 | 含义 |
|------|------|
| `smoke` | 冒烟测试，核心接口可用性 |
| `api` | REST API 测试 |
| `crud` | 完整增删改查流程 |
| `agent` | Agent 控制通道 |
| `mock` | 挡板功能 |
| `create` | 创建操作 |
| `negative` | 异常/边界场景 |
| `cors` | CORS 相关 |

## 测试报告

运行完成后在 `results/` 目录查看：
- `report.html` — 测试报告
- `log.html` — 详细日志
- `output.xml` — 机器可读结果
