# Baafoo Web 控制台 — 前端界面框架设计

> **文档状态**：v1.5  
> **关联文档**：[概念设计说明书 v0.7](../.concepts/baafoo-concept-design.md) | [产品需求文档 v1.5](../.prd/baafoo-prd.md)  
> **目标读者**：前端开发工程师、UI 设计师  
> **技术栈**：Vue 3 + Element Plus + Pinia + Axios + ECharts  
> **最后更新**：2026-05-29  
> **变更摘要**：v1.5 — 同步 PRD v1.5 变更：关联文档版本号更新；规则编辑器新增撤销按钮；新增场景集管理设计；Kafka 增加 Beta 标识；规则列表新增无 stub 环境 banner；保存反馈信息补充

---

## 1. 设计总览

### 1.1 产品定位

Baafoo Web 控制台是 Baafoo Server 内嵌的单页应用（SPA），通过可视化界面管理挡板规则、查看请求日志、管理录制数据、管理测试环境和监控系统状态。控制台通过 Baafoo Server 的 REST API 获取数据，无需独立后端服务。

### 1.2 访问方式

- **URL**: `http://localhost:9000/__baafoo__/`
- **部署方式**: 静态资源内嵌于 Baafoo Server jar 包，与 HTTP Mock Handler 共用 9000 端口
- **路径前缀**: `/__baafoo__/` 确保不与 Mock 规则冲突

### 1.3 设计原则

1. **轻量高效**：控制台是辅助工具，不应引入复杂的前端构建链，静态资源整体 < 2MB（gzip 后约 700KB）
2. **即时响应**：所有操作通过 REST API 完成，规则修改 < 500ms 生效
3. **协议感知**：UI 需适应 HTTP / TCP / Kafka / Pulsar / JMS 五种协议的差异化配置
4. **开发者友好**：快捷键支持、YAML 实时预览、表单校验即时反馈
5. **环境感知**：UI 需支持环境管理（环境是 Agent 的属性）、环境维度模式切换、按环境过滤日志/录制

---

## 2. 整体布局架构

### 2.1 布局方案：经典侧边栏布局

```
┌──────────────────────────────────────────────────────────────┐
│  HEADER BAR — Logo + 全局模式指示灯 + Agent 连接状态       │
├─────────────┬────────────────────────────────────────────────┤
│             │                                                 │
│  SIDEBAR  │               MAIN CONTENT AREA                │
│             │                                                 │
│  · 规则管理 │   ┌─────────────────────────────────────────┐  │
│  · 请求日志 │   │                                         │  │
│  · 录制管理 │   │         Page-specific Content           │  │
│  · 环境管理 │   │                                         │  │
│  · 系统状态 │   │                                         │  │
│             │   └─────────────────────────────────────────┘  │
│             │                                                 │
├─────────────┴────────────────────────────────────────────────┤
│  STATUS BAR — Server 版本 + 规则总数 + 请求速率 + 活跃环境数     │
└──────────────────────────────────────────────────────────────┘
```

**布局规格**：

| 区域 | 宽度/高度 | 说明 |
|---|---|---|
| Header Bar | 全宽 × 48px | 固定顶部，z-index: 100 |
| Sidebar | 220px × 全高 | 固定左侧，收缩态 64px（可选） |
| Main Content | 自适应 | 滚动区域，padding: 24px |
| Status Bar | 全宽 × 32px | 固定底部，z-index: 100 |

### 2.2 响应式断点

| 断点 | 宽度 | Sidebar | 适配策略 |
|---|---|---|---|
| Desktop | ≥ 1280px | 展开 220px | 完整布局 |
| Tablet | 768-1279px | 收缩 64px（仅图标）| 内容区自适应 |
| Mobile | < 768px | 隐藏（抽屉式）| 单列堆叠 |

---

## 3. 页面层级与路由设计

### 3.1 路由表

```
/                         → 重定向到 /dashboard
/dashboard                → 控制台首页（Dashboard）
/rules                    → 规则管理列表
/rules/:protocol          → 按协议过滤（http/tcp/kafka(Beta)/pulsar/jms）
/rules/:protocol/create   → 新建规则
/rules/:protocol/:id/edit → 编辑规则
/logs                     → 请求日志
/logs/:requestId          → 请求详情
/recordings               → 录制管理
/recordings/:sessionId    → 录制详情
/environments             → 环境管理（新增）
/environments/:name      → 环境详情（查看该环境下 Agent 列表）
/scenes                   → 场景集管理（新增）
/scenes/:id               → 场景集详情（新增）
/status                   → 系统状态
```

### 3.2 页面层级树

```
App.vue
├── BaafooHeader.vue          # 全局顶栏
├── BaafooSidebar.vue         # 侧边导航
├── <router-view>             # 主内容区
│   ├── DashboardPage.vue     # 首页
│   ├── RulesPage.vue         # 规则列表
│   ├── RuleEditorPage.vue    # 规则编辑（共用创建/编辑）
│   ├── LogsPage.vue          # 请求日志列表
│   ├── LogDetailPage.vue     # 请求详情
│   ├── RecordingsPage.vue    # 录制管理
│   ├── RecordingDetailPage.vue # 录制详情
│   ├── EnvironmentsPage.vue  # 环境管理（新增）
│   ├── EnvironmentDetailPage.vue # 环境详情（新增）
│   ├── SceneSetPage.vue     # 场景集管理（新增）
│   └── StatusPage.vue        # 系统状态
└── BaafooStatusBar.vue       # 全局底栏
```

---

## 4. 组件树与页面详设

### 4.1 全局组件

#### 4.1.1 BaafooHeader.vue — 全局顶栏

```
┌──────────────────────────────────────────────────────────────┐
│ [Baafoo Logo]    模式指示灯: ● stub    ● Agent 已连接 (3)       │
└──────────────────────────────────────────────────────────────┘
```

| 元素 | 类型 | 说明 |
|---|---|---|
| Logo | 静态文本/图标 | "Baafoo" + 产品图标，点击回到首页 |
| 全局模式指示灯 | 状态指示灯 + 文本 | 显示**最近操作的环境模式**（用户在环境管理页面切换某环境模式后，此处显示该环境的当前模式）；绿色● = stub，灰色● = passthrough，蓝色● = record，紫色● = record-and-stub |
| Agent 连接指示 | 状态指示灯 + 数字 | 绿色● = 有活跃连接，灰色● = 无连接；数字表示连接数 |

> **设计说明**：Header 不再有"全局模式切换"下拉，因为模式是**按环境维度**控制的，不是全局的。模式切换在"环境管理"页面按环境操作。

#### 4.1.2 BaafooSidebar.vue — 侧边导航

```
┌──────────────┐
│ 🏠 控制台首页 │  ← el-menu-item
│ 📋 规则管理   │  ← 可展开子菜单（按协议）
│   · HTTP     │
│   · TCP      │
│   · Kafka (Beta)    │
│   · Pulsar   │
│   · JMS      │
│ 📜 请求日志   │
│ 🎬 录制管理   │
│ 🏢 环境管理   │  ← 新增
│ ⚙ 系统状态   │
└──────────────┘
```

**交互细节**：
- 当前路由高亮（el-menu `router` 模式）
- 规则管理为 el-submenu，默认展开
- 每个协议标签旁显示该协议的规则数量 badge
- Sidebar 可收缩（el-menu `collapse`），收缩态仅显示图标
- **新增"环境管理"入口**，与规则管理、请求日志、录制管理、系统状态并列

#### 4.1.3 BaafooStatusBar.vue — 全局底栏

```
┌──────────────────────────────────────────────────────────────┐
│ Server v1.0.0  │  HTTP: 9000 ↑  TCP: 9001 ↑  Kafka: 9002 ↑  │
│ 规则: 23 条    │  Pulsar: 9003 ↑  JMS: 9004 —              │
│ 活跃环境: 3     │
└──────────────────────────────────────────────────────────────┘
```

| 元素 | 说明 |
|---|---|
| Server 版本 | 从 `/api/health` 获取 |
| 端口状态 | ↑ 绿色 = 监听中，— 灰色 = 未启动，✕ 红色 = 异常 |
| 规则总数 | 从 `/api/rules` count |
| 活跃环境数 | 从 `/api/environments` 统计有活跃 Agent 的环境数量 |

---

### 4.2 页面组件详设

#### 4.2.1 DashboardPage.vue — 控制台首页

**页面布局**：上排 5 个统计卡片 + 下排 2 个图表（左右分栏）

```
┌──────────────────────────────────────────────────────────────┐
│  控制台首页                                                  │
│                                                              │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│ │ 活跃连接  │ │ 今日请求  │ │ 规则命中  │ │ 平均延迟  │ │ 活跃环境  │
│ │    3     │ │  1,247   │ │  94.2%   │ │  2.3 ms  │ │    3     │
│ └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘
│                                                              │
│ ┌─────────────────────┐ ┌─────────────────────┐             │
│ │   请求趋势（折线图）  │ │ 协议分布（饼图）     │             │
│ │   近 1 小时          │ │ HTTP 65%  TCP 18%   │             │
│ │                     │ │ Kafka 10% Pulsar 7%  │             │
│ └─────────────────────┘ └─────────────────────┘             │
│                                                              │
│ ┌──────────────────────────────────────────────┐             │
│ │ 最近请求（表格，显示最近 20 条）                │             │
│ │ 时间 │ 协议 │ 请求摘要 │ 规则 │ 状态 │ 耗时   │             │
│ └──────────────────────────────────────────────┘             │
└──────────────────────────────────────────────────────────────┘
```

**组件树**：
```
DashboardPage.vue
├── StatCard.vue × 5          # 统计卡片（props: title, value, icon, color）
│   └── 第 5 张卡片："活跃环境"，value 来自 useEnvStore().activeEnvCount
├── RequestTrendChart.vue     # ECharts 折线图
├── ProtocolPieChart.vue      # ECharts 饼图
└── RecentRequestsTable.vue   # el-table，支持点击跳转日志详情
```

**数据来源**：
- 统计卡片：`GET /api/stats`
- 请求趋势：`GET /api/stats/trend?period=1h`
- 协议分布：`GET /api/stats/protocols`
- 最近请求：`GET /api/logs?limit=20`
- 活跃环境：`GET /api/environments` 汇总有活跃 Agent 的环境数量

---

#### 4.2.2 RulesPage.vue — 规则管理列表

**页面布局**：顶部操作栏 + 协议 Tab + 规则表格

```
┌──────────────────────────────────────────────────────────────┐
│  规则管理                          [+ 新建规则] [导入] [导出]   │
│                                                                  │
│  ┌─ ⚠️ 当前无 stub 模式环境，所有规则不会生效 ─────────────────┐│
│  │  请前往环境管理页面将至少一个环境切换为 stub 模式          ││
│  └───────────────────────────────────────────────────────────┘│
│                                                                  │
│  [HTTP 12] [TCP 4] [Kafka (Beta) 3] [Pulsar 2] [JMS 2]             │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────────────┐│
│  │ 🔍 搜索规则...                            协议: [全部 ▼] ││
│  └──────────────────────────────────────────────────────────────────┘│
│                                                                  │
│  ┌──────────────────────────────────────────────────────────────────┐│
│  │ # │ 规则ID       │ 匹配条件            │ 状态 │ 操作     ││
│  ├───┼──────────────┼─────────────────────┼──────┼─────────┤│
│  │ 1 │ get-user     │ GET /api/users/{id} │ ✅启用│ 编辑 禁用 删除││
│  │ 2 │ create-order │ POST /api/orders    │ ✅启用│ 编辑 禁用 删除││
│  │ 3 │ login-reset  │ TCP 01 02 03 → ... │ ⏸禁用│ 编辑 启用 删除││
│  └──────────────────────────────────────────────────────────────────┘│
│                                                                  │
│  < 1 2 3 ... 5 >  共 23 条规则                              │
└──────────────────────────────────────────────────────────────────────┘
```

> **设计变更说明（v1.3）**：
> - **移除**了顶部的"环境选择器"（旧设计是"规则按环境过滤"）
> - **原因**：新设计下规则是**全局共享**的，不按环境区分。规则是否生效取决于 Agent 所属环境的模式（stub 模式下才拦截）
>
> **设计说明（v1.5）**：
> - 当无任何环境处于 stub 模式时，规则管理页面顶部显示黄色 banner："当前无 stub 模式环境，所有规则不会生效"，引导用户到环境管理页面切换模式

**交互细节**：
- 新建规则：弹出协议选择对话框（5 种协议卡片），选择后跳转对应编辑器
- 导入：el-upload 按钮，接受 `.yml` / `.yaml` / `.json`
- 导出：下拉菜单选择格式（YAML / JSON），调用导出 API
- 启用/禁用：el-switch 开关，切换时调用 `PUT /api/rules/{id}` 更新 status
- 删除：el-popconfirm 确认后调用 `DELETE /api/rules/{id}`
- 支持拖拽排序（vuedraggable），排序后调用 API 更新优先级

**组件树**：
```
RulesPage.vue
├── RuleToolbar.vue            # 顶部操作栏
├── ProtocolTabs.vue           # el-tabs 协议切换
├── RuleSearchBar.vue          # 搜索 + 过滤器
├── RuleTable.vue              # el-table + vuedraggable
│   └── RuleRowActions.vue     # 每行操作按钮组
└── ImportExportDialog.vue     # 导入/导出对话框
```

---

#### 4.2.3 RuleEditorPage.vue — 规则编辑器（HTTP 示例）

**页面布局**：左侧表单编辑区 + 右侧实时预览

```
┌──────────────────────────────────────────────────────────────┐
│  ← 返回列表              编辑规则: get-user-success           [撤销上次修改] [保存] [取消]  │
├────────────────────────────┬─────────────────────────────────┤
│  基本信息                   │  实时预览                       │
│  规则ID: [get-user     ]   │                                 │
│  描述:   [获取用户信息  ]   │  ┌───────────────────────────┐ │
│                            │  │ # HTTP 挡板规则 (YAML)     │ │
│  请求匹配                   │  │ http:                     │ │
│  方法: [GET ▼]             │  │   - id: get-user          │ │
│  路径: [/api/users/{id}]   │  │     request:              │ │
│                            │  │       method: GET         │ │
│  ── 匹配条件（可添加多条）──│  │       path: /api/users/..│ │
│  ┌─ 条件 1 ──────────────┐│  │     responses:            │ │
│  │ 条件类型: [Header ▼]   ││  │       - condition:        │ │
│  │ 键: [X-User-Level    ] ││  │           header:       │ │
│  │ 值: [VIP             ] ││  │             X-User...  │ │
│  │              [删除条件] ││  │         response:       │ │
│  └────────────────────────┘│  │           status: 200   │ │
│  [+ 添加匹配条件]           │  │           body: |        │ │
│                            │  │             {"id": ...   │ │
│  响应配置                   │  │                         │ │
│  状态码: [200]              │  │                         │ │
│  响应头: ┌ Key ──┬ Value ─┐│  └───────────────────────────┘ │
│         │Content │ app/json││                                 │
│         └───────┴────────┘│                                 │
│  Body: ┌──────────────────┐│                                 │
│        │ {                ││                                 │
│        │   "id": "{{path. ││                                 │
│        │   "name": "Mock" ││                                 │
│        │ }                ││                                 │
│        └──────────────────┘│                                 │
│  延迟: [50] ms              │                                 │
│  异常模拟: [无 ▼]           │                                 │
└────────────────────────────┴─────────────────────────────────┘
```

> **设计变更说明（v1.3）**：
> - **移除**了"环境多选器"（旧设计是"选择规则生效环境，不选则所有环境生效"）
> - **原因**：新设计下规则是**全局共享**的，没有 `environments` 字段。规则是否生效取决于 Agent 所属环境的模式。

**协议差异表单**：

| 协议 | 独有字段 |
|---|---|
| HTTP | method, path, query, headers, body(JSONPath), status code, response headers, response body, fault type |
| TCP | prefixHex, pattern(regex), replaySession, offsetMatch, responseHex |
| Kafka | topic, messages(key/value/delay/acks) |
| Pulsar | tenant, namespace, topic, subscription, messages(key/value/delay/properties) |
| JMS | type(queue/topic), name, messages(content/delay/redeliveryCount) |

**组件树**：
```
RuleEditorPage.vue
├── EditorToolbar.vue          # 返回 + 标题 + 撤销/保存/取消（新增撤销按钮，调用 POST /api/rules/{id}/restore?version=N）
├── RuleBasicInfo.vue          # 规则 ID、描述（移除环境选择）
├── ProtocolSpecificForm.vue   # 按协议路由到子组件
│   ├── HttpRuleForm.vue       # HTTP 表单
│   │   ├── MatchConditionList.vue  # 动态条件列表
│   │   │   └── MatchConditionItem.vue  # 单条条件
│   │   └── ResponseConfig.vue       # 响应配置
│   ├── TcpRuleForm.vue        # TCP 表单
│   ├── KafkaRuleForm.vue      # Kafka 表单
│   ├── PulsarRuleForm.vue     # Pulsar 表单
│   └── JmsRuleForm.vue        # JMS 表单
└── YamlPreview.vue            # 右侧实时 YAML 预览（移除 environments 字段）
```

---

#### 4.2.4 SceneSetPage.vue — 场景集管理（新增）

**页面说明**：场景集管理页面，以卡片/列表形式展示所有场景集

**页面布局**：顶部操作栏 + 场景集列表

```
┌──────────────────────────────────────────────────────────────┐
│  场景集管理                              [+ 创建场景集]        │
│                                                              │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ 🔍 搜索场景集...                                         ││
│  └──────────────────────────────────────────────────────────┘│
│                                                              │
│  ┌──────────────────────┐ ┌──────────────────────┐          │
│  │ 📦 用户管理场景集     │ │ 📦 订单管理场景集     │          │
│  │ 描述: 用户模块挡板   │ │ 描述: 订单流程挡板   │          │
│  │ 关联规则: 5 条       │ │ 关联规则: 8 条       │          │
│  │ 状态: ✅ 启用        │ │ 状态: ⏸ 禁用        │          │
│  │ [启用] [编辑] [删除] │ │ [启用] [编辑] [删除] │          │
│  └──────────────────────┘ └──────────────────────┘          │
│                                                              │
│  ┌──────────────────────┐                                   │
│  │ 📦 支付管理场景集     │                                   │
│  │ 描述: 支付流程挡板   │                                   │
│  │ 关联规则: 3 条       │                                   │
│  │ 状态: ✅ 启用        │                                   │
│  │ [启用] [编辑] [删除] │                                   │
│  └──────────────────────┘                                   │
│                                                              │
│  < 1 2 3 >  共 3 个场景集                                │
└──────────────────────────────────────────────────────────────┘
```

> **设计说明（v1.5）**：
> - v1.0 简化版：场景集与规则 1:N 关系（一条规则只属于一个场景集）
> - 一键启用/禁用整组规则：切换场景集状态时，该场景集下所有规则同步启用/禁用
> - 路由：`/scenes` 和 `/scenes/:id`

**交互细节**：
- 创建场景集：点击"+ 创建场景集"按钮 → 弹出创建对话框，填写名称、描述
- 启用/禁用：el-switch 开关，切换时调用 API 更新场景集状态，该场景集下所有规则同步启用/禁用
- 编辑场景集：点击"编辑"按钮 → 弹出编辑对话框，修改名称/描述
- 删除场景集：el-popconfirm 确认后调用 API 删除，仅允许删除无关联规则的场景集
- 点击场景集卡片或名称 → 跳转到 `/scenes/:id` 查看该场景集下所有规则

**组件树**：
```
SceneSetPage.vue
├── SceneSetToolbar.vue         # 顶部操作栏（搜索 + 创建按钮）
├── SceneSetCardList.vue        # 卡片网格布局
│   └── SceneSetCard.vue        # 单个场景集卡片
│       └── SceneSetActions.vue # 操作按钮组（启用/编辑/删除）
└── SceneSetCreateDialog.vue    # 创建/编辑场景集对话框
```

---

#### 4.2.5 LogsPage.vue — 请求日志列表

**页面布局**：过滤器栏 + 实时日志表格

```
┌──────────────────────────────────────────────────────────────┐
│  请求日志                                      [导出 HAR] [自动刷新: ON]  │
│                                                              │
│  环境: [全部 ▼]  协议: [全部 ▼]  规则: [全部 ▼]  时间: [最近 1 小时 ▼]  🔍 [搜索...]  │
│                                                              │
│  ┌──────────────────────────────────────────────────────────────────┐│
│  │ 时间         │ 协议  │ 请求摘要          │ 规则      │ 状态 │ 耗时  ││
│  ├─────────────┼─────────┼───────────────────┼───────────┼──────┼──────┤│
│  │ 22:45:01.234 │ HTTP  │ GET /api/users/.. │ get-user  │ 200  │ 2ms  ││
│  │ 22:45:01.120 │ TCP   │ 01 02 03 ...      │ tcp-login │ 匹配 │ 1ms  ││
│  │ 22:45:00.998 │ Pulsar│ order-events      │ pulsar-ev │ 投递 │ 5ms  ││
│  │ 22:45:00.850 │ HTTP  │ POST /api/orders  │ (未匹配)  │ 404  │ 0ms  ││
│  └──────────────────────────────────────────────────────────────────┘│
│                                                              │
│  < 1 2 3 ... 12 >  共 1,247 条日志                              │
└──────────────────────────────────────────────────────────────────────┘
```

> **设计变更说明（v1.3）**：
> - **新增**"环境过滤器"（下拉选择环境）
> - **原因**：日志是 Agent 产生的，Agent 有环境属性。按环境过滤日志可以只查看某个测试环境下 Agent 产生的请求。

**交互细节**：
- **环境过滤器**：新增下拉选择器，选项为 `全部` / `ft-1` / `ft-2` / `ft-3` / ...（动态从 Server 获取已注册环境列表）
- 选择特定环境后，日志表格仅显示该环境下 Agent 产生的日志
- 自动刷新：el-switch，开启时每 3 秒轮询追加新日志
- 未匹配请求高亮（浅黄色背景行），提示用户添加规则
- 点击行展开详情抽屉（el-drawer），展示完整请求/响应内容
- HAR 导出仅对 HTTP 日志可用，TCP/Pulsar 等灰显

**组件树**：
```
LogsPage.vue
├── LogFilterBar.vue           # 环境/协议/规则/时间范围/搜索
│   └── EnvironmentFilter.vue  # 环境过滤器（新增）
├── LogTable.vue               # el-table，支持虚拟滚动（大量数据）
│   └── UnmatchedRowHighlight  # 未匹配行高亮样式
└── LogDetailDrawer.vue        # el-drawer 请求/响应详情
    ├── RequestViewer.vue      # 格式化展示请求（JSON 语法高亮 / Hex 视图）
    └── ResponseViewer.vue     # 格式化展示响应
```

---

#### 4.2.6 RecordingsPage.vue — 录制管理

**页面布局**：Session 卡片列表

```
┌──────────────────────────────────────────────────────────────┐
│  录制管理                      磁盘占用: 320MB / 500MB (64%)  │
│                                                              │
│  环境: [全部 ▼]                                              │
│                                                              │
│  ┌──────────────────────┐ ┌──────────────────────┐          │
│  │ Session: rec-001     │ │ Session: rec-002     │          │
│  │ 环境: ft-1          │ │ 环境: ft-2          │          │
│  │ 时间: 05-28 20:00    │ │ 时间: 05-28 18:30    │          │
│  │      ~ 05-28 22:00   │ │      ~ 05-28 19:45   │          │
│  │ 协议: HTTP (85%)     │ │ 协议: TCP            │          │
│  │      TCP (15%)       │ │                      │          │
│  │ 大小: 45MB           │ │ 大小: 120MB          │          │
│  │ [生成规则] [回放] [删除]│ │ [生成规则] [回放] [删除]│          │
│  └──────────────────────┘ └──────────────────────┘          │
│                                                              │
│  ┌──────────────────────┐                                   │
│  │ Session: rec-003     │                                   │
│  │ ...                  │                                   │
│  └──────────────────────┘                                   │
└──────────────────────────────────────────────────────────────┘
```

> **设计变更说明（v1.3）**：
> - **新增**"环境过滤器"（下拉选择环境）
> - **原因**：录制是 Agent 完成的，Agent 有环境属性。按环境过滤录制可以只查看某个测试环境下 Agent 的录制数据。

**组件树**：
```
RecordingsPage.vue
├── RecordingStatsBar.vue     # 磁盘占用进度条
├── EnvironmentFilter.vue     # 环境过滤器（新增）
├── SessionCardList.vue       # 网格布局的 SessionCard
│   └── SessionCard.vue       # 单个 Session 卡片
│       └── SessionActions.vue # 生成规则 / 回放 / 删除
└── ConfirmDialog.vue         # 删除确认
```

---

#### 4.2.7 EnvironmentsPage.vue — 环境管理（新增）

**页面布局**：环境卡片列表 + 环境详情抽屉

```
┌──────────────────────────────────────────────────────────────┐
│  环境管理                                    [+ 创建环境]          │
│                                                              │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ 环境名称  │ 当前模式  │ 活跃 Agent 数  │ 描述         │ 操作     ││
│  ├──────────┼───────────┼────────────────┼─────────────┼──────────┤│
│  │ ft-1      │ ● stub    │ 3              │ FT-1 挡板自测│ 查看 编辑 ││
│  │ ft-2      │ ○ passthrough │ 2              │ FT-2 透传联调│ 查看 编辑 ││
│  │ ft-3      │ ● record  │ 1              │ FT-3 录制环境│ 查看 编辑 ││
│  └──────────────────────────────────────────────────────────┘│
│                                                              │
│  < 1 2 3 >  共 3 个环境                                  │
└──────────────────────────────────────────────────────────────┘
```

**交互细节**：

1. **创建环境**：
   - 点击"+ 创建环境"按钮 → 弹出创建对话框
   - 填写：环境名称（如 `ft-4`）、初始模式（默认 `passthrough`）、描述（可选）
   - 提交后调用 `POST /api/environments`

2. **模式切换**：
   - 点击"编辑"按钮 → 弹出编辑对话框
   - 可以切换模式（stub / passthrough / record / record-and-stub）
   - 切换后，Server 通过控制通道向该环境下所有 Agent 下发模式切换指令
   - **即时生效**，无需重启 Agent

3. **查看环境详情**：
   - 点击"查看"按钮 → 打开详情抽屉（或跳转到 `/environments/:name`）
   - 展示该环境下所有活跃 Agent 的列表（PID、应用名、连接时间、状态）

4. **删除环境**：
   - 仅允许删除**无活跃 Agent**的环境
   - 删除前需确认

**组件树**：
```
EnvironmentsPage.vue
├── EnvToolbar.vue            # 顶部操作栏（创建环境按钮）
├── EnvTable.vue              # el-table 环境列表
│   └── EnvRowActions.vue    # 每行操作按钮组（查看/编辑/删除）
├── EnvCreateDialog.vue       # 创建环境对话框
├── EnvEditDialog.vue        # 编辑环境对话框（含模式切换）
└── EnvDetailDrawer.vue      # 环境详情抽屉（Agent 列表）
    └── AgentList.vue         # Agent 列表表格
```

---

#### 4.2.8 EnvironmentDetailPage.vue — 环境详情（新增）

**页面布局**：环境基本信息 + Agent 列表

```
┌──────────────────────────────────────────────────────────────┐
│  ← 返回环境列表              环境: ft-1                    │
├────────────────────────────┬─────────────────────────────────┤
│  基本信息                   │  Agent 列表                     │
│  环境名称: ft-1            │                                 │
│  当前模式: ● stub         │  ┌───────────────────────────┐ │
│  描述: FT-1 挡板自测环境 │  │ PID  │ 应用名   │ 连接时间   │ │
│  活跃 Agent 数: 3        │  ├─────┼──────────┼────────────┤ │
│                            │  │ 1234 │ my-app   │ 10:00:00   │ │
│  [切换模式]                │  │ 1235 │ my-app   │ 10:05:00   │ │
│  [编辑]                   │  │ 1236 │ my-app   │ 10:10:00   │ │
│  [删除环境]               │  └───────────────────────────┘ │
└────────────────────────────┴─────────────────────────────────┘
```

---

#### 4.2.9 StatusPage.vue — 系统状态

**页面布局**：端口状态 + 系统指标 + 配置信息

```
┌──────────────────────────────────────────────────────────────┐
│  系统状态                                                    │
│                                                              │
│  ┌─ 端口状态 ──────────────────────────────────────────────┐ │
│  │ HTTP  ● 9000 监听中    │ Kafka  ● 9002 监听中            │ │
│  │ TCP   ● 9001 监听中    │ Pulsar ● 9003 监听中            │ │
│  │                        │ JMS    — 9004 未启动            │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ 系统指标 ──────────────────────────────────────────────┐ │
│  │ Agent 连接数: 3          规则总数: 23                     │ │
│  │ 今日请求总数: 1,247      平均延迟: 2.3 ms               │ │
│  │ 规则命中率: 94.2%        录制数据: 320MB / 500MB         │ │
│  │ Server 运行时间: 2d 4h 32m                              │ │
│  │ 活跃环境数: 3                                             │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ 版本与配置 ──────────────────────────────────────────────┐ │
│  │ Baafoo Server: v1.0.0                                   │ │
│  │ Java: 1.8.0_352 (Oracle Corporation)                    │ │
│  │ 配置文件: /etc/baafoo/stub-rules.yml                    │ │
│  │ 日志级别: INFO                                          │ │
│  └─────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

**组件树**：
```
StatusPage.vue
├── PortStatusPanel.vue       # 端口状态网格
├── SystemMetricsPanel.vue    # 系统指标（含活跃环境数）
└── VersionInfoPanel.vue      # 版本与配置
```

---

## 5. 交互流程

### 5.1 核心操作流程图

```
开发者访问 http://localhost:9000/__baafoo__/
    │
    ├── 首次使用 ─→ Dashboard 空白引导 ─→ "创建第一条规则" CTA
    │
    └── 日常使用
        │
        ├── 规则管理
        │   ├── 新建规则 → 选协议 → 表单填写 → YAML 预览 → 保存 → 立即生效
        │   ├── 编辑规则 → 修改 → 保存 → 立即生效
        │   ├── 禁用/启用 → el-switch → API 调用 → 即时反馈
        │   └── 拖拽排序 → vuedraggable → API 更新优先级
        │
        ├── 请求日志
        │   ├── 按环境过滤 → 选择环境下拉 → 仅显示该环境日志
        │   ├── 实时监控 → 自动刷新 ON → 滚动查看 → 点击展开详情
        │   └── 问题排查 → 未匹配高亮 → 一键生成规则模板
        │
        ├── 录制管理
        │   ├── 按环境过滤 → 选择环境下拉 → 仅显示该环境录制
        │   ├── 查看 session → 生成回放规则 → 自动填充编辑器
        │   └── 清理过期数据 → 手动删除 / 自动清理策略
        │
        ├── 环境管理（新增）
        │   ├── 创建环境 → 填写名称/模式/描述 → 提交 → Server 注册环境
        │   ├── 切换模式 → 选择环境 → 切换模式 → Server 下发到该环境下所有 Agent
        │   ├── 查看 Agent 列表 → 点击环境 → 查看该环境下所有 Agent
        │   └── 删除环境 → 仅允许删除无活跃 Agent 的环境
        │
        └── 系统状态
            └── 监控端口 → 发现异常 → 查看日志排查
```

### 5.2 环境模式切换流程（新增）

```
开发者在 Agent 配置文件声明所属环境：
# baafoo-agent.yml
environment: ft-1

Agent 启动后注册到 Server，上报 environment: "ft-1"
Server 根据环境配置下发模式（如 stub）

在 Web 控制台：
    │
    ├── 环境管理 → 环境列表 → 点击"编辑" → 切换模式 → 提交
    │   → Server 立即通过控制通道向 ft-1 环境下所有 Agent 下发模式切换指令
    │   → Agent 无需重启，自动开始拦截（或停止拦截）
    │
    └── Agent 配置文件修改 environment 字段 → 重启 Agent → 注册到新环境
```

---

## 6. 设计系统

### 6.1 色彩体系

| 色阶 | Hex | 用途 |
|---|---|---|
| `--color-primary` | `#409EFF` | 主色调（Element UI 默认蓝），按钮、链接、选中态 |
| `--color-primary-light` | `#66B1FF` | 悬停态 |
| `--color-primary-dark` | `#3A8EE6` | 按下态 |
| `--color-success` | `#67C23A` | 成功状态、启用中、端口在线 |
| `--color-warning` | `#E6A23C` | 警告状态、未匹配请求高亮背景 |
| `--color-danger` | `#F56C6C` | 危险操作、删除按钮、端口异常 |
| `--color-info` | `#909399` | 中性信息、禁用状态、端口未启动 |
| `--bg-page` | `#F2F3F5` | 页面背景 |
| `--bg-card` | `#FFFFFF` | 卡片/表格背景 |
| `--bg-sidebar` | `#304156` | 侧边栏深色背景 |
| `--text-primary` | `#303133` | 主要文字 |
| `--text-regular` | `#606266` | 常规文字 |
| `--text-secondary` | `#909399` | 次要文字 |
| `--border-color` | `#DCDFE6` | 边框线 |
| `--border-light` | `#E4E7ED` | 浅色分割线 |

### 6.2 排版体系

| 层级 | 字号 | 字重 | 行高 | 用途 |
|---|---|---|---|---|
| H1 | 20px | 600 | 28px | 页面标题 |
| H2 | 18px | 600 | 26px | 区块标题 |
| H3 | 16px | 600 | 24px | 卡片标题 |
| Body-L | 14px | 400 | 22px | 正文、表格内容 |
| Body-S | 13px | 400 | 20px | 辅助说明、状态栏 |
| Caption | 12px | 400 | 18px | 标签、badge、时间戳 |
| Code | 13px | 400 | 20px | 代码/YAML 预览（JetBrains Mono） |

**字体栈**：
```css
--font-family-ui: "Helvetica Neue", Helvetica, "PingFang SC", "Microsoft YaHei", Arial, sans-serif;
--font-family-code: "JetBrains Mono", "Fira Code", "Consolas", monospace;
```

### 6.3 间距体系（基于 8px）

| Token | 值 | 用途 |
|---|---|---|
| `--space-xs` | 4px | 紧凑间距、图标与文字间距 |
| `--space-sm` | 8px | 组件内部间距、标签间距 |
| `--space-md` | 16px | 表单间距、卡片内边距 |
| `--space-lg` | 24px | 页面主内容区内边距、区块间距 |
| `--space-xl` | 32px | 大区块分隔 |
| `--space-2xl` | 48px | 页面级大分隔 |

### 6.4 圆角与阴影

| Token | 值 | 用途 |
|---|---|---|
| `--radius-sm` | 2px | 标签、badge |
| `--radius-md` | 4px | 按钮、输入框、表格 |
| `--radius-lg` | 8px | 卡片、对话框 |
| `--shadow-card` | `0 2px 12px 0 rgba(0,0,0,.06)` | 卡片阴影 |
| `--shadow-dropdown` | `0 2px 12px 0 rgba(0,0,0,.10)` | 下拉菜单阴影 |

### 6.5 图标系统

使用 Element UI 内置图标（`el-icon-*`），不引入额外图标库以减少依赖体积：

| 场景 | 图标 |
|---|---|
| Dashboard | `el-icon-s-data` |
| 规则管理 | `el-icon-s-order` |
| 请求日志 | `el-icon-document` |
| 录制管理 | `el-icon-video-camera` |
| 环境管理 | `el-icon-office-building` |
| 系统状态 | `el-icon-setting` |
| HTTP 协议 | `el-icon-link` |
| TCP 协议 | `el-icon-connection` |
| Kafka | `el-icon-s-marketing` |
| Pulsar | `el-icon-share` |
| JMS | `el-icon-message` |

---

## 7. 前端技术架构

### 7.1 技术栈

| 层级 | 技术 | 版本 | 说明 |
|---|---|---|---|
| 框架 | Vue.js | 3.3.x | Composition API + `<script setup>` 语法 |
| UI 库 | Element Plus | 2.4.x | Vue 3 生态主流 UI 库 |
| 状态管理 | Pinia | 2.1.x | Vue 3 官方推荐状态管理，替代 Vuex |
| 路由 | Vue Router | 4.x | Hash 模式（兼容静态文件部署） |
| HTTP | Axios | 1.6.x | API 请求 + 拦截器 |
| 图表 | ECharts | 5.x | Dashboard 统计图表（按需引入） |
| 拖拽 | vuedraggable | 4.x | Vue 3 兼容版本，规则排序 |
| 代码高亮 | CodeMirror | 6.x | YAML 预览 |
| 构建 | Vite | 5.x | 快速构建，输出静态资源内嵌 jar |

### 7.2 目录结构

```
baafoo-console/                          # 独立前端工程目录
├── index.html
├── vite.config.js                       # Vite 构建配置
├── package.json
├── src/
│   ├── main.js                          # Vue 应用入口
│   ├── App.vue                          # 根组件
│   ├── router/
│   │   └── index.js                     # 路由配置
│   ├── stores/                          # Pinia 状态管理
│   │   ├── rules.js                     # 规则列表状态
│   │   ├── agents.js                    # Agent 连接状态
│   │   └── environments.js              # 环境管理状态
│   ├── api/
│   │   └── index.js                     # Axios 实例 + API 方法封装
│   ├── composables/                     # 组合式函数
│   │   ├── useWebSocket.js             # WebSocket 连接管理
│   │   ├── useAutoRefresh.js           # 自动刷新逻辑
│   │   └── useEnvironments.js          # 环境管理组合式函数
│   ├── components/
│   │   ├── BaafooHeader.vue
│   │   ├── BaafooSidebar.vue
│   │   ├── BaafooStatusBar.vue
│   │   └── common/
│   │       ├── StatCard.vue             # 统计卡片
│   │       ├── YamlPreview.vue          # YAML 预览组件
│   │       ├── ProtocolIcon.vue         # 协议图标
│   │       └── EmptyGuide.vue           # 空状态引导
│   └── pages/
│       ├── DashboardPage.vue
│       ├── RulesPage.vue
│       ├── RuleEditorPage.vue
│       ├── LogsPage.vue
│       ├── LogDetailPage.vue
│       ├── RecordingsPage.vue
│       ├── RecordingDetailPage.vue
│       ├── EnvironmentsPage.vue         # 新增
│       ├── EnvironmentDetailPage.vue    # 新增
│       ├── SceneSetPage.vue             # 场景集管理（新增）
│       └── StatusPage.vue
└── dist/                                # 构建输出，复制到 baafoo-server/src/main/resources/webapp/
```

> **构建集成**：Vite 构建输出到 `dist/` 目录，通过 Maven/Gradle 构建脚本自动复制到 `baafoo-server/src/main/resources/webapp/`，最终内嵌于 Server jar 包。

### 7.3 API 封装

```javascript
// api.js — 统一 API 层
const API_BASE = '/__baafoo__/api';

const api = {
  // 系统
  health:        ()                => axios.get(`${API_BASE}/health`),
  getStats:      ()                => axios.get(`${API_BASE}/stats`),
  getTrend:      (period)          => axios.get(`${API_BASE}/stats/trend`, { params: { period } }),
  getProtocols:  ()                => axios.get(`${API_BASE}/stats/protocols`),

  // 环境管理（重设计）
  getEnvironments: ()                => axios.get(`${API_BASE}/environments`),
  createEnvironment: (data)          => axios.post(`${API_BASE}/environments`, data),
  updateEnvironment: (name, data)   => axios.put(`${API_BASE}/environments/${name}`, data),
  deleteEnvironment: (name)         => axios.delete(`${API_BASE}/environments/${name}`),
  getEnvAgents:     (name)         => axios.get(`${API_BASE}/environments/${name}/agents`),

  // 规则管理（移除 environments 参数）
  getRules:      (protocol)         => axios.get(`${API_BASE}/rules`, { 
    params: { 
      protocol 
    } 
  }),
  createRule:    (rule)            => axios.post(`${API_BASE}/rules`, rule),
  updateRule:    (id, rule)        => axios.put(`${API_BASE}/rules/${id}`, rule),
  deleteRule:    (id)              => axios.delete(`${API_BASE}/rules/${id}`),
  importRules:   (file)            => { /* FormData upload */ },
  exportRules:   (format)          => axios.get(`${API_BASE}/rules/export`, { 
    params: { 
      format
    } 
  }),

  // Agent 管理
  getAgents:     ()                => axios.get(`${API_BASE}/agents`),

  // 请求日志（新增 environment 参数）
  getLogs:       (params)          => axios.get(`${API_BASE}/logs`, { params }),
  getLogDetail:  (id)              => axios.get(`${API_BASE}/logs/${id}`),
  exportHar:     (environment)     => axios.get(`${API_BASE}/logs/export/har`, { 
    params: { 
      environment: environment || undefined 
    } 
  }),

  // 录制管理（新增 environment 参数）
  getRecordings: (environment)     => axios.get(`${API_BASE}/recordings`, { 
    params: { 
      environment: environment || undefined 
    } 
  }),
  deleteRecording: (sessionId)     => axios.delete(`${API_BASE}/recordings/${sessionId}`),
  generateRule:  (sessionId)       => axios.post(`${API_BASE}/recordings/${sessionId}/generate-rule`),
};
```

### 7.4 全局状态管理（Pinia）

```javascript
// stores/environments.js — 环境管理状态（重设计）
import { defineStore } from 'pinia';

export const useEnvStore = defineStore('environments', {
  state: () => ({
    environments: [],           // 所有已注册的环境列表
    currentFilter: 'all',      // 当前过滤的环境（'all' 表示全部）
  }),
  getters: {
    activeEnvCount: (state) => {
      // 统计有活跃 Agent 的环境数量
      return state.environments.filter(env => env.agentCount > 0).length;
    },
    envOptions: (state) => {
      return [
        { label: '全部', value: 'all' },
        ...state.environments.map(env => ({ label: env.name, value: env.name }))
      ];
    },
  },
  actions: {
    async fetchEnvironments() {
      const { data } = await api.getEnvironments();
      this.environments = data;
    },
    async createEnvironment(data) {
      await api.createEnvironment(data);
      await this.fetchEnvironments();
    },
    async updateEnvironment(name, data) {
      await api.updateEnvironment(name, data);
      await this.fetchEnvironments();
    },
    async deleteEnvironment(name) {
      await api.deleteEnvironment(name);
      await this.fetchEnvironments();
    },
    setCurrentFilter(filter) {
      this.currentFilter = filter;
    },
  },
});

// stores/rules.js — 规则列表状态（移除 environments 相关逻辑）
export const useRuleStore = defineStore('rules', {
  state: () => ({
    rules: [],
    loading: false,
  }),
  actions: {
    async fetchRules(protocol) {
      this.loading = true;
      const { data } = await api.getRules(protocol);
      this.rules = data;
      this.loading = false;
    },
    // ... 其他 actions
  },
});
```

> **变更说明**：
> - `stores/environments.js`：从"规则环境过滤"改为"环境管理"
> - `stores/rules.js`：移除 `environments` 相关逻辑

### 7.5 实时日志推送（WebSocket）

```javascript
// composables/useWebSocket.js
import { ref, onUnmounted } from 'vue';

const WS_URL = `ws://${location.host}/__baafoo__/ws/logs`;

export function useLogStream() {
  const logs = ref([]);
  const connected = ref(false);
  let ws = null;
  let reconnectTimer = null;

  function connect() {
    ws = new WebSocket(WS_URL);
    ws.onopen = () => { connected.value = true; };
    ws.onmessage = (event) => {
      const entry = JSON.parse(event.data);
      logs.value.unshift(entry);
      if (logs.value.length > 1000) logs.value.pop();
    };
    ws.onclose = () => {
      connected.value = false;
      reconnectTimer = setTimeout(connect, 3000);
    };
    ws.onerror = () => { ws.close(); };
  }

  function disconnect() {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    if (ws) ws.close();
  }

  onUnmounted(disconnect);

  return { logs, connected, connect, disconnect };
}
```

### 7.6 错误边界与离线处理

```javascript
// 全局 Axios 拦截器 — api.js
axios.interceptors.response.use(
  (response) => response,
  (error) => {
    if (!error.response) {
      ElMessage.error('Server 连接中断，请检查 Baafoo Server 是否运行');
    } else if (error.response.status === 409) {
      ElMessage.warning('规则已被他人修改，请刷新后重试');
    } else if (error.response.status >= 500) {
      ElMessage.error(`Server 错误: ${error.response.data?.message || '未知错误'}`);
    }
    return Promise.reject(error);
  }
);
```

**离线处理策略**：

| 场景 | 表现 | 恢复策略 |
|---|---|---|
| Server 重启 | API 请求失败，Header 栏显示"Server 连接中断" | Axios 拦截器检测到响应恢复后自动更新状态 |
| 网络断开 | WebSocket 断开，日志停止更新 | WebSocket 3s 自动重连 |
| API 5xx 错误 | `ElMessage.error` 提示具体错误 | 用户手动重试 |
| 规则并发冲突 | 409 Conflict | 提示"规则已被他人修改，请刷新后重试" |
| 环境模式切换时序延迟 | 同一环境下多个 Agent 因控制通道延迟短暂处于不同模式 | 提示"模式切换中，部分 Agent 尚未同步，请稍候" |

---

## 8. 关键交互规范

### 8.1 操作反馈

| 操作 | 反馈方式 | 时机 |
|---|---|---|
| 保存规则 | `el-message` success "规则已保存，将在以下 stub 模式环境中生效：{环境列表}" | API 200 后 |
| 删除规则 | `el-message` success "规则已删除" | API 200 后 |
| 规则启用/禁用 | `el-switch` 即时切换 + 行状态更新 | API 200 后 |
| 模式切换（环境） | `el-messageBox` 确认对话框 → `el-message` success | 确认 → API → 反馈 |
| 导入规则 | `el-message` "成功导入 N 条规则" | API 200 后 |
| 环境切换 | `el-message` success "已切换到 {env} 环境模式" | 环境下拉选择后 |
| 操作失败 | `el-message` error + 错误详情 | API 4xx/5xx |

### 8.2 空状态处理

| 场景 | 展示 |
|---|---|
| 无规则 | 插图 + "暂无挡板规则" + "创建第一条规则" 按钮 |
| 无日志 | 插图 + "暂无请求日志" + "启动 Agent 后日志将自动出现" |
| 无录制 | 插图 + "暂无录制数据" + "将 Agent 模式切换为 record 开始录制" |
| 搜索无结果 | "未找到匹配的规则/日志，请调整搜索条件" |
| 端口未启动 | 灰色状态灯 + "未配置该协议的规则" 提示 |
| 当前环境无 Agent | 插图 + "当前环境无活跃 Agent" + "启动 Agent 并配置 environment 字段" |

### 8.3 加载状态

| 场景 | 方式 |
|---|---|
| 页面初始加载 | 全局 `v-loading` 遮罩 + 骨架屏 |
| 表格数据刷新 | 表格区域 `v-loading` |
| 保存操作 | 按钮 loading 态 + 禁用 |
| 实时日志刷新 | 静默刷新，仅更新时间戳指示器 |
| 环境切换 | 表格区域 `v-loading` |

---

## 9. 静态资源内嵌方案

### 9.1 Server 端配置

```java
// BaafooServer.java — Netty 静态资源处理
// 使用 Netty 的 HttpStaticFileServerHandler 模式
// 路径前缀 /__baafoo__/ 映射到 classpath:/webapp/

public class WebConsoleHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String WEB_ROOT = "/webapp/";
    private static final String PREFIX = "/__baafoo__/";

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        if (uri.startsWith(PREFIX)) {
            String resourcePath = WEB_ROOT + uri.substring(PREFIX.length());
            if (resourcePath.equals(WEB_ROOT) || resourcePath.equals(WEB_ROOT + "/")) {
                resourcePath = WEB_ROOT + "index.html";  // SPA fallback
            }
            serveStaticResource(ctx, request, resourcePath);
        } else {
            ctx.fireChannelRead(request.retain());  // 传递给 HTTP Mock Handler
        }
    }

    // SPA fallback: 所有 /__baafoo__/ 子路径（非静态资源）回退到 index.html
    private void serveStaticResource(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        // 1. 尝试从 classpath 加载文件
        // 2. 若文件不存在且路径无文件扩展名，回退到 index.html
        // 3. 设置正确的 Content-Type 和缓存头
    }
}
```

### 9.2 Vue Router 配置

```javascript
// router/index.js — Vue Router 4，Hash 模式，适配静态文件部署
import { createRouter, createWebHashHistory } from 'vue-router';

const routes = [
  { path: '/',              redirect: '/dashboard' },
  { path: '/dashboard',     component: () => import('../pages/DashboardPage.vue') },
  { path: '/rules',         component: () => import('../pages/RulesPage.vue') },
  { path: '/rules/:protocol', component: () => import('../pages/RulesPage.vue') },
  { path: '/rules/:protocol/create', component: () => import('../pages/RuleEditorPage.vue') },
  { path: '/rules/:protocol/:id/edit', component: () => import('../pages/RuleEditorPage.vue') },
  { path: '/logs',          component: () => import('../pages/LogsPage.vue') },
  { path: '/logs/:requestId', component: () => import('../pages/LogDetailPage.vue') },
  { path: '/recordings',    component: () => import('../pages/RecordingsPage.vue') },
  { path: '/recordings/:sessionId', component: () => import('../pages/RecordingDetailPage.vue') },
  { path: '/environments',          component: () => import('../pages/EnvironmentsPage.vue') },
  { path: '/environments/:name',     component: () => import('../pages/EnvironmentDetailPage.vue') },
  { path: '/scenes',                 component: () => import('../pages/SceneSetPage.vue') },
  { path: '/scenes/:id',             component: () => import('../pages/SceneSetPage.vue') },
  { path: '/status',        component: () => import('../pages/StatusPage.vue') },
];

const router = createRouter({
  history: createWebHashHistory('/__baafoo__/'),
  routes,
});

export default router;
```

---

## 10. 与概念文档及 PRD 的追溯矩阵

| PRD 需求 ID | 描述 | 对应页面/组件 |
|---|---|---|
| R-W1 | 控制台概览与导航 | BaafooHeader + BaafooSidebar + DashboardPage |
| R-W2 | 规则管理界面 | RulesPage + RuleEditorPage + ImportExportDialog |
| R-W3 | 请求日志界面 | LogsPage + LogDetailDrawer |
| R-W4 | 录制管理界面 | RecordingsPage + SessionCard |
| R-W5 | 系统状态界面 | StatusPage |
| R-W6 | 测试环境管理界面 | EnvironmentsPage + EnvironmentDetailPage |
| R-S7.3 | 测试环境管理 API | EnvironmentsPage + EnvironmentDetailPage |
| R-S7.4 | 规则版本管理 | RuleEditorPage |
| R-C1 | Agent 配置文件（environment 字段）| EnvironmentsPage（环境列表） |
| R-C2 | 挡板规则文件（无 environments 字段）| RuleEditorPage + YAML 预览 |
| G6 | Web 控制台 80% 日常操作 | 全部页面覆盖规则管理 + 请求查看 + 环境管理 |
| G7 | 接口参数化返回 | RuleEditorPage → MatchConditionList |
| US-15 | 场景集管理 | SceneSetPage |
| US-16 | baafoo init | 非 UI 范围，无需追溯 |

---

## 11. 实施 Checklist

### 11.1 前端实施步骤

1. **新增 SceneSetPage.vue 组件**
   - [ ] 场景集卡片列表
   - [ ] 创建/编辑场景集对话框
   - [ ] 启用/禁用场景集（同步更新关联规则状态）
   - [ ] 删除场景集确认对话框

2. **新增 EnvironmentsPage.vue 组件**
   - [ ] 环境列表表格
   - [ ] 创建环境对话框
   - [ ] 编辑环境对话框（含模式切换）
   - [ ] 环境详情抽屉（Agent 列表）
   - [ ] 删除环境确认对话框

3. **更新 RulesPage.vue**
   - [ ] **移除**环境选择器（规则全局共享）
   - [ ] 保留协议 Tab + 搜索过滤

4. **更新 RuleEditorPage.vue**
   - [ ] **移除**环境多选器（规则无 environments 字段）
   - [ ] YAML 预览移除 environments 字段

5. **更新 DashboardPage.vue**
   - [ ] 保留"活跃环境"统计卡片
   - [ ] 数据来源接入 useEnvStore().activeEnvCount

6. **更新 LogsPage.vue**
   - [ ] **新增**环境过滤器
   - [ ] API 调用时传递 environment 参数

7. **更新 RecordingsPage.vue**
   - [ ] **新增**环境过滤器
   - [ ] API 调用时传递 environment 参数

8. **新增 stores/environments.js**
   - [ ] 环境列表状态管理
   - [ ] 环境创建/更新/删除 actions
   - [ ] 当前过滤环境状态

9. **更新 API 封装**
   - [ ] 新增 `/api/environments` 端点
   - [ ] 移除规则 API 的 environments 参数
   - [ ] 新增日志/录制 API 的 environment 参数

### 11.2 后端 API 实施步骤（参考）

1. **新增 `/api/environments` GET 端点**
   - [ ] 返回所有已注册的环境列表
   - [ ] 数据来源：所有 Agent 的 environment 字段汇总

2. **新增 `/api/environments` POST 端点**
   - [ ] 创建测试环境
   - [ ] 请求体包含 `name`、`mode`、`description`

3. **新增 `/api/environments/{name}` PUT 端点**
   - [ ] 更新环境配置（主要用于切换模式）
   - [ ] 模式变更后，Server 立即通过控制通道向该环境下所有 Agent 下发模式切换指令

4. **新增 `/api/environments/{name}` DELETE 端点**
   - [ ] 删除测试环境
   - [ ] 仅允许删除无活跃 Agent 的环境

5. **新增 `/api/environments/{name}/agents` GET 端点**
   - [ ] 查看指定环境下所有已注册 Agent 的状态列表

6. **更新 `/api/rules` GET 端点**
   - [ ] **移除** `environment` 查询参数（规则全局共享）

7. **更新 `/api/rules` POST/PUT 端点**
   - [ ] **移除** `environments` 字段（规则无环境属性）

8. **更新 `/api/agents` GET 端点**
   - [ ] 返回 Agent 的 environment 字段
   - [ ] 支持按 environment 过滤

---

*本文档为 Baafoo v1.5 前端界面框架设计，供前端工程师进行组件开发和技术方案评审。*
