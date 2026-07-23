# Baafoo Web 控制台 UI 自动化测试

本目录存放 Baafoo Web 控制台（Vue 3 + Element Plus）的 UI 自动化测试脚本、运行入口与说明文档。
测试框架为 [Playwright](https://playwright.dev)，覆盖仪表盘、场景集、录制管理、请求日志、系统状态等核心页面。

> 测试代码本体在 [web/tests/](../../web/tests/)，Playwright 配置在 [web/playwright.config.js](../../web/playwright.config.js)。
> 本目录只放**运行脚本**与**报告产物**，避免与前端工程耦合。

## 目录结构

```
testing/6_UITest/
├── run-ui-tests.ps1          # Windows 运行脚本（PowerShell）
├── run-ui-tests.sh           # Linux/macOS/Git Bash 运行脚本
├── README.md                 # 本文档
└── playwright-report/        # 最近一次运行的 HTML 报告（自动生成，勿手工编辑）

web/                          # 前端工程（测试代码所在地）
├── playwright.config.js      # Playwright 配置（globalSetup / baseURL / 报告路径）
├── tests/
│   ├── global-setup.js       # 运行前自动登录 admin，生成 admin-storage.json
│   └── frontend-features.spec.js  # 5 个 UI 用例
└── package.json              # npm test / test:ui / test:report 脚本
```

## 前置条件

| 依赖 | 版本 | 说明 |
|------|------|------|
| Node.js | ≥ 16 | 运行 Playwright；首次运行脚本会自动 `npm install` |
| Chrome | 稳定版 | `playwright.config.js` 用 `channel: 'chrome'` 复用系统 Chrome |
| Docker Desktop | 任意 | 仅当本地未运行 Server 时，脚本会用它启动 staging 集群 |
| Baafoo Server | — | 监听 `http://localhost:8084`；缺失时脚本自动 docker compose 启动 |

> Playwright 浏览器二进制：因为配置用了 `channel: 'chrome'`（系统 Chrome），**无需** `npx playwright install`。

## 快速开始

### Windows（PowerShell）

```powershell
# 在项目根目录执行
.\testing\6_UITest\run-ui-tests.ps1
```

### Linux / macOS / Git Bash

```bash
chmod +x testing/6_UITest/run-ui-tests.sh
./testing/6_UITest/run-ui-tests.sh
```

脚本会自动完成：检测/启动 Server → 抽取 admin 密码 → 安装依赖 → 跑 Playwright → 复制 HTML 报告到本目录。

## 运行流程详解

```
┌──────────────────────────────────────────────────────────────────┐
│ 1. 检测 http://localhost:8084/__baafoo__/api/status 是否 200     │
│    └─ 不可达 → docker compose up -d                               │
│                server postgres app-env-a app-env-b staging-init   │
│       └─ 轮询 120s 等 server 健康，再等 8s 让 agent 注册           │
├──────────────────────────────────────────────────────────────────┤
│ 2. 准备 admin 密码（三选一，按优先级）                             │
│    a) 环境变量 BAAFOO_ADMIN_PASSWORD                              │
│    b) docker exec <container> cat .../.admin-credentials          │
│       → 解析 "Pass:  XXX" → 写入 testing/7_Others/tmp/.admin-password │
│    c) 本地 $HOME/.baafoo/data/.admin-credentials（java -jar 场景）│
├──────────────────────────────────────────────────────────────────┤
│ 3. cd web && npm install（仅 node_modules 缺失时）                │
├──────────────────────────────────────────────────────────────────┤
│ 4. npx playwright test                                           │
│    └─ globalSetup：POST /auth/login 拿 JWT                        │
│       → 写入 localStorage.baafoo_token                            │
│       → storageState 存 testing/7_Others/tmp/admin-storage.json   │
│    └─ 5 个用例串行执行（workers=1）                               │
├──────────────────────────────────────────────────────────────────┤
│ 5. 复制 HTML 报告                                                 │
│    testing/7_Others/tmp/playwright-report/                        │
│      → testing/6_UITest/playwright-report/index.html              │
└──────────────────────────────────────────────────────────────────┘
```

## 用例清单

| # | 用例 | 覆盖页面 | 关键断言 |
|---|------|---------|---------|
| 1 | 仪表盘展示统计卡片与图表 | `/dashboard` | 4 个 stat-card、规则概览饼图、请求趋势折线图、最近录制表格 |
| 2 | 场景集 - 新建、列表展示与删除 | `/scenes` | 新建对话框、关联规则/环境、列表出现新场景、删除后消失 |
| 3 | 录制管理 - 列表、搜索与详情 | `/recordings` | 表格有数据、按规则ID搜索、详情弹窗含请求/响应 |
| 4 | 请求日志 - 展示录制记录 | `/logs` | 表格有数据，含协议/方法/路径/状态码列 |
| 5 | 系统状态 - 展示 Agent 列表与服务信息 | `/status` | 4 个 stat-card、Agent 列表=2 行、服务信息含 HTTP Stub |

> 用例 3 的 `beforeAll` 会通过 `app-env-a:9090` 触发一条 HTTP 录制；用例 5 断言 Agent 数=2。
> 因此**必须启动完整 staging 集群**（含 `app-env-a` / `app-env-b`），不能只起 server。

## 登录态原理（globalSetup）

Web 控制台登录后把 JWT 存在 `localStorage.baafoo_token`，axios 拦截器以 `Authorization: Bearer <token>` 携带。
Playwright 的 `storageState` 机制可以把 `localStorage` + `cookies` 序列化成 JSON 复用。

[web/tests/global-setup.js](../../web/tests/global-setup.js) 在所有用例前执行：

1. `POST /__baafoo__/api/auth/login`（admin + 密码）→ 拿 `token`
2. 启动一个 headless Chrome，`goto(baseURL)` 建立同源
3. `localStorage.setItem('baafoo_token', token)` + `baafoo_username`
4. `context.storageState({ path: '.../admin-storage.json' })` 落盘
5. 每个用例通过 `use.storageState` 自动加载该文件，省去重复登录

密码来源（优先级）：
- `BAAFOO_ADMIN_PASSWORD` 环境变量
- `BAAFOO_ADMIN_PASSWORD_FILE` 指向的文件（默认 `testing/7_Others/tmp/.admin-password`）

## 常用命令

### 只跑匹配标题的用例

```powershell
.\testing\6_UITest\run-ui-tests.ps1 -Grep "仪表盘"
```
```bash
./testing/6_UITest/run-ui-tests.sh --grep "仪表盘"
```

### 不自动启动 Server（假定已手工启动）

```powershell
.\testing\6_UITest\run-ui-tests.ps1 -NoAutoStart
```
```bash
./testing/6_UITest/run-ui-tests.sh --no-auto-start
```

### 交互式调试 UI（带 Playwright Inspector）

```powershell
cd web
npx playwright test --ui
```

### 直接用 npm 脚本（已配好，不经过本目录的运行脚本）

```powershell
cd web
npm test                  # = playwright test
npm run test:ui           # = playwright test --ui
npm run test:report       # 打开上次 HTML 报告
```

> 用 `npm test` 时需自行保证：Server 在跑、`BAAFOO_ADMIN_PASSWORD` 环境变量已设或
> `testing/7_Others/tmp/.admin-password` 已存在（globalSetup 会读它）。

### 查看 HTML 报告

```powershell
start testing\6_UITest\playwright-report\index.html
```
```bash
open testing/6_UITest/playwright-report/index.html
```

## 新增 UI 用例

1. 在 [web/tests/](../../web/tests/) 新建 `*.spec.js`，沿用现有 `test.describe` + `test(...)` 风格
2. 页面用 hash 路由：`await page.goto('/#/rules')`
3. 等数据加载：`await page.waitForLoadState('networkidle')`
4. 元素定位优先用 Element Plus 的语义类（`.el-table__row`、`.el-dialog__title`）或 `getByRole`
5. 写完后 `.\testing\6_UITest\run-ui-tests.ps1 -Grep "<新用例标题>"` 单跑验证

## 排错

| 现象 | 排查 |
|------|------|
| `globalSetup 未找到 admin 密码` | server 是 docker 启的？确认容器名是 `baafoo-server`，再 `docker exec baafoo-server sh -c 'find / -name .admin-credentials 2>/dev/null'`（路径随 `dataDir` 配置变化，staging 实际在 `/app/~/.baafoo/data/`）；或直接 `$env:BAAFOO_ADMIN_PASSWORD='<密码>'` |
| `admin 登录失败：Invalid username or password` | admin 密码已被改过；用新密码设到 `BAAFOO_ADMIN_PASSWORD`，或删除 admin 用户让 server 重新生成（删库重建：`docker compose ... down -v` 后重启） |
| 用例 5 Agent 数≠2 | `app-env-a` / `app-env-b` 没起来或没注册成功；`docker compose -f docker-compose.yml -f docker-compose.staging.yml logs app-env-a` 看日志，或延长 `等待 agent 注册` 时间 |
| 用例 3 录制列表为空 | `beforeAll` 调 `app-env-a:9090` 失败；确认 `http://localhost:9090/api/http/get?url=http://httpbin.org/get` 可达，且 staging-a 处于 `record_and_stub` 模式 |
| `channel: 'chrome' not found` | 系统未装 Chrome；改装 chromium：删 `playwright.config.js` 里 `channel: 'chrome'` 一行，再 `npx playwright install chromium` |
| `docker compose up` 卡住 | Docker Desktop 未运行；或端口 8084/9000-9005/9090/9091 被占用；`docker compose ps` + `netstat -ano \| findstr :8084` |

## 与其它测试目录的关系

| 目录 | 关注点 | 与本目录的区别 |
|------|--------|---------------|
| `1_UnitTest/` | 代码突变测试 | 后端 Java，无 UI |
| `2_IntegrationTest/` | 协议 stub 集成 | API 层，curl 驱动 |
| `3_SystemTest/` | 全链路（88 用例） | 含 Docker staging，但走 API 不走浏览器 |
| `4_E2ETest/` | 企业级应用 | Kafka/PetClinic/SCA 真实业务场景 |
| `6_UITest/`（本目录） | **Web 控制台浏览器** | Playwright 驱动 Chrome，验证前端渲染与交互 |
| `7_Others/` | 测试计划/评审文档 | `tmp/` 子目录存放运行时临时产物（admin-storage.json 等，已 gitignore） |

## 临时产物（gitignored）

以下文件由运行脚本/globalSetup 自动生成，位于 `testing/7_Others/tmp/`，**不要提交**：
- `admin-storage.json` — Playwright storageState（含 JWT）
- `.admin-password` — 从容器抽取的 admin 一次性密码
- `playwright-report/` — 原始 HTML 报告（脚本会复制一份到本目录）

参见 [testing/7_Others/](../7_Others/) 目录的 `.gitignore` 规则。
