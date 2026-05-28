# Baafoo Web 控制台 — 前端界面框架设计

> **文档状态**：v1.0  
> **关联文档**：[概念设计说明书 v0.3](../.concepts/baafoo-concept-design.md) | [产品需求文档 v1.1](../.prd/baafoo-prd.md)  
> **目标读者**：前端开发工程师、UI 设计师  
> **技术栈**：Vue 2 + Element UI + Axios + ECharts  
> **最后更新**：2026-05-28

---

## 1. 设计总览

### 1.1 产品定位

Baafoo Web 控制台是 Baafoo Server 内嵌的单页应用（SPA），通过可视化界面管理挡板规则、查看请求日志、管理录制数据和监控系统状态。控制台通过 Baafoo Server 的 REST API 获取数据，无需独立后端服务。

### 1.2 访问方式

- **URL**: `http://localhost:9000/__baafoo__/`
- **部署方式**: 静态资源内嵌于 Baafoo Server jar 包，与 HTTP Mock Handler 共用 9000 端口
- **路径前缀**: `/__baafoo__/` 确保不与 Mock 规则冲突

### 1.3 设计原则

1. **轻量高效**：控制台是辅助工具，不应引入复杂的前端构建链，静态资源整体 < 500KB
2. **即时响应**：所有操作通过 REST API 完成，规则修改 < 500ms 生效
3. **协议感知**：UI 需适应 HTTP / TCP / Kafka / Pulsar / JMS 五种协议的差异化配置
4. **开发者友好**：快捷键支持、YAML 实时预览、表单校验即时反馈

---

## 2. 整体布局架构

### 2.1 布局方案：经典侧边栏布局

```
┌──────────────────────────────────────────────────────────────┐
│  HEADER BAR — Logo + 全局模式切换 + Agent 连接状态指示灯       │
├────────────┬─────────────────────────────────────────────────┤
│            │                                                 │
│  SIDEBAR   │               MAIN CONTENT AREA                │
│            │                                                 │
│  · 规则管理 │   ┌─────────────────────────────────────────┐  │
│  · 请求日志 │   │                                         │  │
│  · 录制管理 │   │         Page-specific Content           │  │
│  · 系统状态 │   │                                         │  │
│            │   │                                         │  │
│            │   └─────────────────────────────────────────┘  │
│            │                                                 │
├────────────┴─────────────────────────────────────────────────┤
│  STATUS BAR — Server 版本 + 规则总数 + 请求速率               │
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
/rules/:protocol          → 按协议过滤（http/tcp/kafka/pulsar/jms）
/rules/:protocol/create   → 新建规则
/rules/:protocol/:id/edit → 编辑规则
/logs                     → 请求日志
/logs/:requestId          → 请求详情
/recordings               → 录制管理
/recordings/:sessionId    → 录制详情
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
│   └── StatusPage.vue        # 系统状态
└── BaafooStatusBar.vue       # 全局底栏
```

---

## 4. 组件树与页面详设

### 4.1 全局组件

#### 4.1.1 BaafooHeader.vue — 全局顶栏

```
┌──────────────────────────────────────────────────────────────┐
│ [Baafoo Logo]    模式: [挡板 ▼]    ● Agent 已连接 (3)       │
└──────────────────────────────────────────────────────────────┘
```

| 元素 | 类型 | 说明 |
|---|---|---|
| Logo | 静态文本/图标 | "Baafoo" + 产品图标，点击回到首页 |
| 全局模式切换 | el-select | stub / passthrough / record / record-and-stub，切换后弹出确认对话框 |
| Agent 连接指示 | 状态指示灯 + 数字 | 绿色● = 有活跃连接，灰色● = 无连接；数字表示连接数 |

#### 4.1.2 BaafooSidebar.vue — 侧边导航

```
┌──────────────┐
│ 📊 控制台首页 │  ← el-menu-item
│ 📋 规则管理   │  ← 可展开子菜单（按协议）
│   · HTTP     │
│   · TCP      │
│   · Kafka    │
│   · Pulsar   │
│   · JMS      │
│ 📜 请求日志   │
│ 🎬 录制管理   │
│ ⚙ 系统状态   │
└──────────────┘
```

**交互细节**：
- 当前路由高亮（el-menu `router` 模式）
- 规则管理为 el-submenu，默认展开
- 每个协议标签旁显示该协议的规则数量 badge
- Sidebar 可收缩（el-menu `collapse`），收缩态仅显示图标

#### 4.1.3 BaafooStatusBar.vue — 全局底栏

```
┌──────────────────────────────────────────────────────────────┐
│ Server v1.0.0  │  HTTP: 9000 ↑  TCP: 9001 ↑  Kafka: 9002 ↑  │
│ 规则: 23 条    │  Pulsar: 9003 ↑  JMS: 9004 —              │
└──────────────────────────────────────────────────────────────┘
```

| 元素 | 说明 |
|---|---|
| Server 版本 | 从 `/api/health` 获取 |
| 端口状态 | ↑ 绿色 = 监听中，— 灰色 = 未启动，✕ 红色 = 异常 |
| 规则总数 | 从 `/api/rules` count |

---

### 4.2 页面组件详设

#### 4.2.1 DashboardPage.vue — 控制台首页

**页面布局**：上排 4 个统计卡片 + 下排 2 个图表（左右分栏）

```
┌──────────────────────────────────────────────────────────────┐
│  控制台首页                                                  │
│                                                              │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│ │ 活跃连接  │ │ 今日请求  │ │ 规则命中  │ │ 平均延迟  │        │
│ │    3     │ │  1,247   │ │  94.2%   │ │  2.3 ms  │        │
│ └──────────┘ └──────────┘ └──────────┘ └──────────┘        │
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
├── StatCard.vue × 4          # 统计卡片（props: title, value, icon, color）
├── RequestTrendChart.vue     # ECharts 折线图
├── ProtocolPieChart.vue      # ECharts 饼图
└── RecentRequestsTable.vue   # el-table，支持点击跳转日志详情
```

**数据来源**：
- 统计卡片：`GET /api/stats`
- 请求趋势：`GET /api/stats/trend?period=1h`
- 协议分布：`GET /api/stats/protocols`
- 最近请求：`GET /api/logs?limit=20`

---

#### 4.2.2 RulesPage.vue — 规则管理列表

**页面布局**：顶部操作栏 + 协议 Tab + 规则表格

```
┌──────────────────────────────────────────────────────────────┐
│  规则管理                                    [+ 新建规则] [导入] [导出]   │
│                                                              │
│  [HTTP 12] [TCP 4] [Kafka 3] [Pulsar 2] [JMS 2]             │
│                                                              │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ 🔍 搜索规则...                            协议: [全部 ▼] ││
│  └──────────────────────────────────────────────────────────┘│
│                                                              │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ # │ 规则ID       │ 匹配条件            │ 状态 │ 操作     ││
│  ├───┼──────────────┼─────────────────────┼──────┼─────────┤│
│  │ 1 │ get-user     │ GET /api/users/{id} │ ✅启用│ 编辑 禁用 删除│
│  │ 2 │ create-order │ POST /api/orders    │ ✅启用│ 编辑 禁用 删除│
│  │ 3 │ login-reset  │ TCP 01 02 03 → ... │ ⏸禁用│ 编辑 启用 删除│
│  └──────────────────────────────────────────────────────────┘│
│                                                              │
│  < 1 2 3 ... 5 >  共 23 条规则                              │
└──────────────────────────────────────────────────────────────┘
```

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
│  ← 返回列表              编辑规则: get-user           [保存] [取消]  │
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
│  ┌─ 条件 1 ───────────────┐│  │     response:             │ │
│  │ 条件类型: [Header ▼]   ││  │       status: 200         │ │
│  │ 键: [X-User-Level    ] ││  │       body: |            │ │
│  │ 值: [VIP             ] ││  │         {"id": "{{path..│ │
│  │              [删除条件] ││  │                         │ │
│  └────────────────────────┘│  └───────────────────────────┘ │
│  [+ 添加匹配条件]           │                                 │
│                            │                                 │
│  响应配置                   │                                 │
│  状态码: [200]              │                                 │
│  响应头: ┌ Key ──┬ Value ─┐│                                 │
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
├── EditorToolbar.vue          # 返回 + 标题 + 保存/取消
├── RuleBasicInfo.vue          # 规则 ID、描述
├── ProtocolSpecificForm.vue   # 按协议路由到子组件
│   ├── HttpRuleForm.vue       # HTTP 表单
│   │   ├── MatchConditionList.vue  # 动态条件列表
│   │   │   └── MatchConditionItem.vue  # 单条条件
│   │   └── ResponseConfig.vue       # 响应配置
│   ├── TcpRuleForm.vue        # TCP 表单
│   ├── KafkaRuleForm.vue      # Kafka 表单
│   ├── PulsarRuleForm.vue     # Pulsar 表单
│   └── JmsRuleForm.vue        # JMS 表单
└── YamlPreview.vue            # 右侧实时 YAML 预览
```

---

#### 4.2.4 LogsPage.vue — 请求日志列表

**页面布局**：过滤器栏 + 实时日志表格

```
┌──────────────────────────────────────────────────────────────┐
│  请求日志                                      [导出 HAR] [自动刷新: ON]  │
│                                                              │
│  协议: [全部 ▼]  规则: [全部 ▼]  时间: [最近 1 小时 ▼]  🔍 [搜索...]  │
│                                                              │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ 时间         │ 协议  │ 请求摘要          │ 规则      │ 状态 │ 耗时  ││
│  ├──────────────┼───────┼───────────────────┼───────────┼──────┼──────┤│
│  │ 22:45:01.234 │ HTTP  │ GET /api/users/.. │ get-user  │ 200  │ 2ms  ││
│  │ 22:45:01.120 │ TCP   │ 01 02 03 ...      │ tcp-login │ 匹配 │ 1ms  ││
│  │ 22:45:00.998 │ Pulsar│ order-events      │ pulsar-ev │ 投递 │ 5ms  ││
│  │ 22:45:00.850 │ HTTP  │ POST /api/orders  │ (未匹配)  │ 404  │ 0ms  ││
│  └──────────────────────────────────────────────────────────┘│
│                                                              │
│  < 1 2 3 ... 12 >  共 1,247 条日志                          │
└──────────────────────────────────────────────────────────────┘
```

**交互细节**：
- 自动刷新：el-switch，开启时每 3 秒轮询追加新日志
- 未匹配请求高亮（浅黄色背景行），提示用户添加规则
- 点击行展开详情抽屉（el-drawer），展示完整请求/响应内容
- HAR 导出仅对 HTTP 日志可用，TCP/Pulsar 等灰显

**组件树**：
```
LogsPage.vue
├── LogFilterBar.vue           # 协议/规则/时间范围/搜索
├── LogTable.vue               # el-table，支持虚拟滚动（大量数据）
│   └── UnmatchedRowHighlight  # 未匹配行高亮样式
└── LogDetailDrawer.vue        # el-drawer 请求/响应详情
    ├── RequestViewer.vue      # 格式化展示请求（JSON 语法高亮 / Hex 视图）
    └── ResponseViewer.vue     # 格式化展示响应
```

---

#### 4.2.5 RecordingsPage.vue — 录制管理

**页面布局**：Session 卡片列表

```
┌──────────────────────────────────────────────────────────────┐
│  录制管理                      磁盘占用: 320MB / 500MB (64%)  │
│                                                              │
│  ┌──────────────────────┐ ┌──────────────────────┐          │
│  │ Session: rec-001     │ │ Session: rec-002     │          │
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

**组件树**：
```
RecordingsPage.vue
├── RecordingStatsBar.vue     # 磁盘占用进度条
├── SessionCardList.vue       # 网格布局的 SessionCard
│   └── SessionCard.vue       # 单个 Session 卡片
│       └── SessionActions.vue # 生成规则 / 回放 / 删除
└── ConfirmDialog.vue         # 删除确认
```

---

#### 4.2.6 StatusPage.vue — 系统状态

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
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ 版本与配置 ────────────────────────────────────────────┐ │
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
├── SystemMetricsPanel.vue    # 系统指标
└── VersionInfoPanel.vue      # 版本与配置
```

---

## 5. 交互流程

### 5.1 核心操作流程图

```
开发者访问 http://localhost:9000/__baafoo__/
    │
    ├── 首次使用 ──→ Dashboard 空白引导 ──→ "创建第一条规则" CTA
    │
    └── 日常使用
        │
        ├── 规则管理
        │   ├── 新建规则 → 选协议 → 表单填写 → YAML 预览 → 保存 → 立即生效
        │   ├── 编辑规则 → 表单修改 → 保存 → 立即生效
        │   ├── 禁用/启用 → el-switch → API 调用 → 即时反馈
        │   └── 拖拽排序 → vuedraggable → API 更新优先级
        │
        ├── 请求日志
        │   ├── 实时监控 → 自动刷新 ON → 滚动查看 → 点击展开详情
        │   └── 问题排查 → 未匹配高亮 → 一键生成规则模板
        │
        ├── 录制管理
        │   ├── 查看 session → 生成回放规则 → 自动填充编辑器
        │   └── 清理过期数据 → 手动删除 / 自动清理策略
        │
        └── 系统状态
            └── 监控端口 → 发现异常 → 查看日志排查
```

### 5.2 全局模式切换流程

```
用户点击 Header 全局模式下拉 → 选择新模式
    │
    ├── stub → 确认弹窗："切换后所有挡板规则将生效，确认？" → API 调用 → Agent 热加载
    ├── passthrough → 确认弹窗："切换后所有请求透传至真实下游，确认？"
    ├── record → 确认弹窗："切换后所有请求透传并自动录制，确认？"
    └── record-and-stub → 同上
        │
        └── API 返回成功后，Header 指示灯和 Status Bar 同步刷新
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
| `--shadow-card` | `0 2px 12px 0 rgba(0,0,0,0.06)` | 卡片阴影 |
| `--shadow-dropdown` | `0 2px 12px 0 rgba(0,0,0,0.10)` | 下拉菜单阴影 |

### 6.5 图标系统

使用 Element UI 内置图标（`el-icon-*`），不引入额外图标库以减少依赖体积：

| 场景 | 图标 |
|---|---|
| Dashboard | `el-icon-s-data` |
| 规则管理 | `el-icon-s-order` |
| 请求日志 | `el-icon-document` |
| 录制管理 | `el-icon-video-camera` |
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
| 框架 | Vue.js | 2.6.x | 与团队 DBaaber/Apiaano 项目一致 |
| UI 库 | Element UI | 2.15.x | 与团队技术栈一致 |
| 路由 | Vue Router | 3.x | Hash 模式（兼容静态文件部署） |
| HTTP | Axios | 0.27.x | API 请求 + 拦截器 |
| 图表 | ECharts | 5.x | Dashboard 统计图表 |
| 拖拽 | vuedraggable | 2.24.x | 规则排序 |
| 代码高亮 | CodeMirror | 5.x | YAML 预览（与 DBaaber 一致） |
| 构建 | Vue CLI | 4.x | 构建为静态资源，内嵌 jar |

### 7.2 目录结构

```
baafoo-server/src/main/resources/webapp/
├── index.html
├── css/
│   ├── app.css                  # 全局样式
│   └── themes/
│       └── dark.css             # 预留暗色主题
├── js/
│   ├── app.js                   # Vue 应用入口
│   ├── router.js                # 路由配置
│   ├── api.js                   # Axios 实例 + API 方法封装
│   ├── components/
│   │   ├── BaafooHeader.js
│   │   ├── BaafooSidebar.js
│   │   ├── BaafooStatusBar.js
│   │   └── common/
│   │       ├── StatCard.js      # 统计卡片
│   │       ├── YamlPreview.js   # YAML 预览组件
│   │       ├── ProtocolIcon.js  # 协议图标
│   │       └── EmptyGuide.js    # 空状态引导
│   └── pages/
│       ├── DashboardPage.js
│       ├── RulesPage.js
│       ├── RuleEditorPage.js
│       ├── LogsPage.js
│       ├── LogDetailPage.js
│       ├── RecordingsPage.js
│       ├── RecordingDetailPage.js
│       └── StatusPage.js
└── vendor/                      # 第三方库（CDN 或内嵌）
    ├── vue.min.js
    ├── element-ui.min.js
    ├── echarts.min.js
    └── ...
```

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
  getSessions:   ()                => axios.get(`${API_BASE}/sessions`),

  // 规则管理
  getRules:      (protocol)        => axios.get(`${API_BASE}/rules`, { params: { protocol } }),
  createRule:    (rule)            => axios.post(`${API_BASE}/rules`, rule),
  updateRule:    (id, rule)        => axios.put(`${API_BASE}/rules/${id}`, rule),
  deleteRule:    (id)              => axios.delete(`${API_BASE}/rules/${id}`),
  importRules:   (file)            => { /* FormData upload */ },
  exportRules:   (format)          => axios.get(`${API_BASE}/rules/export`, { params: { format } }),

  // 请求日志
  getLogs:       (params)          => axios.get(`${API_BASE}/logs`, { params }),
  getLogDetail:  (id)              => axios.get(`${API_BASE}/logs/${id}`),
  exportHar:     ()                => axios.get(`${API_BASE}/logs/export/har`),

  // 录制管理
  getRecordings: ()                => axios.get(`${API_BASE}/recordings`),
  deleteRecording: (sessionId)     => axios.delete(`${API_BASE}/recordings/${sessionId}`),
  generateRule:  (sessionId)       => axios.post(`${API_BASE}/recordings/${sessionId}/generate-rule`),
};
```

### 7.4 全局模式切换

```javascript
// 全局模式状态管理（简化版 Vuex 或 eventBus）
const modeStore = {
  currentMode: 'stub',
  agentCount: 0,

  async switchMode(mode) {
    await api.updateConfig({ mode });  // 调用 Server API
    this.currentMode = mode;
    EventBus.$emit('mode-changed', mode);
  }
};
```

---

## 8. 关键交互规范

### 8.1 操作反馈

| 操作 | 反馈方式 | 时机 |
|---|---|---|
| 保存规则 | `el-message` success "规则已保存" | API 200 后 |
| 删除规则 | `el-message` success "规则已删除" | API 200 后 |
| 规则启用/禁用 | `el-switch` 即时切换 + 行状态更新 | API 200 后 |
| 模式切换 | `el-messageBox` 确认对话框 → `el-message` success | 确认 → API → 反馈 |
| 导入规则 | `el-message` "成功导入 N 条规则" | API 200 后 |
| 操作失败 | `el-message` error + 错误详情 | API 4xx/5xx |

### 8.2 空状态处理

| 场景 | 展示 |
|---|---|
| 无规则 | 插图 + "暂无挡板规则" + "创建第一条规则" 按钮 |
| 无日志 | 插图 + "暂无请求日志" + "启动 Agent 后日志将自动出现" |
| 无录制 | 插图 + "暂无录制数据" + "将 Agent 模式切换为 record 开始录制" |
| 搜索无结果 | "未找到匹配的规则/日志，请调整搜索条件" |
| 端口未启动 | 灰色状态灯 + "未配置该协议的规则" 提示 |

### 8.3 加载状态

| 场景 | 方式 |
|---|---|
| 页面初始加载 | 全局 `v-loading` 遮罩 + 骨架屏 |
| 表格数据刷新 | 表格区域 `v-loading` |
| 保存操作 | 按钮 loading 态 + 禁用 |
| 实时日志刷新 | 静默刷新，仅更新时间戳指示器 |

---

## 9. 静态资源内嵌方案

### 9.1 Server 端配置

```java
// BaafooServer.java — 静态资源映射
// 使用 Netty 或 Spring Boot 静态资源配置
// 路径前缀 /__baafoo__/ 映射到 classpath:/webapp/

// Spring Boot 方式（如使用 Spring Boot 内嵌 Netty）:
@Configuration
public class WebConsoleConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/__baafoo__/**")
                .addResourceLocations("classpath:/webapp/");
    }

    // SPA fallback: 所有 /__baafoo__/ 子路径回退到 index.html
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/__baafoo__/")
                .setViewName("forward:/webapp/index.html");
    }
}
```

### 9.2 Vue Router 配置

```javascript
// router.js — Hash 模式，适配静态文件部署
const router = new VueRouter({
  mode: 'hash',
  base: '/__baafoo__/',
  routes: [
    { path: '/',              redirect: '/dashboard' },
    { path: '/dashboard',     component: DashboardPage },
    { path: '/rules',         component: RulesPage },
    { path: '/rules/:protocol', component: RulesPage },
    { path: '/rules/:protocol/create', component: RuleEditorPage },
    { path: '/rules/:protocol/:id/edit', component: RuleEditorPage },
    { path: '/logs',          component: LogsPage },
    { path: '/logs/:requestId', component: LogDetailPage },
    { path: '/recordings',    component: RecordingsPage },
    { path: '/recordings/:sessionId', component: RecordingDetailPage },
    { path: '/status',        component: StatusPage },
  ]
});
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
| G6 | Web 控制台 80% 日常操作 | 全部页面覆盖规则管理 + 请求查看 + 模式切换 |
| G7 | 接口参数化返回 | RuleEditorPage → MatchConditionList |

---

*本文档为 Baafoo Web 控制台 v1.0 前端界面框架设计，供前端工程师进行组件开发和技术方案评审。*
