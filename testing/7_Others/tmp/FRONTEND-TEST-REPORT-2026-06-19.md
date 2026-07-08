# Baafoo Web 控制台前端功能测试报告

**测试时间**: 2026-06-19 11:00 左右  
**测试目标**: 验证 Docker Staging 环境下 Web 控制台的仪表盘、场景集、请求日志、录制管理、系统状态等前端功能  
**测试工具**: Playwright（使用系统已安装的 Google Chrome）  
**被测地址**: `http://localhost:8084`  

---

## 1. 环境准备

- 已重新构建 `baafoo-server` JAR（含最新 `web/dist`）：
  ```bash
  .\mvnw.cmd clean package -pl baafoo-server -am -DskipTests
  ```
- 已重新构建并启动 Docker 镜像：
  ```bash
  docker compose -f docker-compose.yml -f docker-compose.staging.yml up -d --build server
  ```
- 为生成录制数据，已将 `staging-a` 环境切换为 `record_and_stub` 模式，并通过 Agent 触发 HTTP 请求。

---

## 2. 测试文件

| 文件 | 说明 |
|------|------|
| [`web/playwright.config.js`](../../web/playwright.config.js) | Playwright 配置（baseURL、Chrome 通道、admin 角色 storageState） |
| [`web/tests/frontend-features.spec.js`](../../web/tests/frontend-features.spec.js) | 前端功能测试用例 |
| [`testing/7_Others/tmp/admin-storage.json`](../../testing/7_Others/tmp/admin-storage.json) | 设置 `baafoo_role=admin` 的 Playwright storageState |

---

## 3. 测试结果总览

**5/5 通过（100%）**

| 用例 | 关键验证点 | 结果 |
|------|-----------|------|
| 仪表盘展示统计卡片与图表 | 统计卡片、规则概览图表、请求趋势图表、最近录制表格均可见 | PASS |
| 场景集 - 新建、列表展示与删除 | 可打开新建弹窗、填写表单、关联规则/环境、提交后列表显示、删除后消失 | PASS |
| 录制管理 - 列表、搜索与详情 | 表格显示录制记录、按规则 ID 搜索、详情弹窗展示请求/响应 | PASS |
| 请求日志 - 展示录制记录 | 日志表格可见，包含协议/方法/路径/状态码列 | PASS |
| 系统状态 - 展示 Agent 列表与服务信息 | 统计卡片、Agent 列表（2 行）、服务信息均可见 | PASS |

---

## 4. 关键发现

- **空白页面根因**：此前 Docker 镜像中的 `web/dist` 为旧构建产物，未包含仪表盘/场景集/日志/录制/系统状态等页面的组件。重新构建 server JAR 并重建镜像后，页面正常渲染。
- **录制数据依赖**：录制与日志同源，需环境处于 `record_and_stub` 模式并产生实际流量后，页面才有数据。测试已通过 API 自动完成该前置条件。
- **权限处理**：测试使用 `storageState` 预设 `baafoo_role=admin`，确保“新建场景集”“删除”等按钮可见。

---

## 5. 结论

Baafoo Web 控制台的仪表盘、场景集、请求日志、录制管理、系统状态等前端功能在最新 Docker 镜像下均可正常渲染与交互。**前端测试通过**。
