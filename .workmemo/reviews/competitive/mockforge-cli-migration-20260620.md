# mockforge CLI 功能分析与迁移评估

> 分析日期: 2026-06-20
> 分析范围: mockforge CLI、mockforge-plugin CLI vs Baafoo CLI

---

## 1. mockforge CLI 总览

### 1.1 主 CLI（`mockforge`）— 47 个子命令

```
mockforge
├── serve           启动所有协议 mock 服务器（60+ CLI 参数）
├── quick           零配置快速 REST mock（给 JSON → 自动 CRUD）
├── import          导入 OpenAPI/AsyncAPI/Insomnia/Postman/cURL
├── init            项目脚手架（支持 blueprint 模板）
├── wizard          交互式引导
├── generate        代码生成 + watch 模式
├── schema          JSON Schema 生成/校验
├── dev-setup       前端框架一键集成
├── client          前端 typed client 生成
├── backend         后端项目生成
├── blueprint       应用蓝图管理
├── validate-fixtures  Fixture 批量校验
├── config          配置管理
├── recorder        录制数据转 stub mapping
├── flow            多步流程录制 + 行为克隆
├── scenario        场景市场
├── reality-profile 真实度配置包
├── snapshot        全局状态快照/恢复
├── time            虚拟时钟（时间旅行）
├── mockai          MockAI 管理
├── voice           自然语言对话创建 mock
├── contract-diff   AI 契约差异分析
├── contract-sync   契约同步
├── governance      API 治理 + 威胁建模
├── behavior-rule   行为经济规则
├── drift-learning  漂移学习
├── test-ai         AI 功能测试
├── template        模板库管理
├── workspace       多租户工作区
├── cloud           云端同步
├── login           认证
├── tunnel          公网隧道
├── deploy          生产部署
├── logs            日志查看（实时 tail）
├── git-watch       Git OpenAPI 变更监控
├── mod             Mock-Oriented Development
├── kafka           Kafka broker 管理
├── mqtt            MQTT broker 管理
├── amqp            AMQP broker 管理
├── smtp            SMTP mailbox 管理
├── ftp             FTP VFS 管理
├── data            合成数据生成
├── admin           独立 Admin UI
├── sync            workspace 同步守护进程
├── completions     Shell 补全生成
└── vbr             Virtual Backend Reality（CRUD 自动生成）

```

### 1.2 插件 CLI（`mockforge-plugin`）— 10 个子命令

| 命令 | 功能 |
|------|------|
| `new` | 创建插件项目（auth/template/response/datasource） |
| `init` | 生成 plugin.yaml 模板 |
| `build` | 编译 WASM 模块 |
| `test` | 运行插件测试 |
| `package` | 打包 .zip |
| `validate` | 校验 manifest + WASM |
| `info` | 查看插件信息 |
| `clean` | 清理构建产物 |
| `publish` | 发布到 registry（Ed25519 签名 + SBOM） |
| `key` | SBOM 签名密钥管理 |

### 1.3 `serve` 参数分组

`mockforge serve` 的参数组织结构：

```
ServeCliArgs
├── 协议端口: http_port, https_port, ws_port, grpc_port, kafka_port,
│            amqp_port, mqtt_port, smtp_port, tcp_port, graphql_port
├── TLS: tls_enabled, tls_cert, tls_key, tls_ca, tls_min_version, mtls
├── Observability: metrics, metrics_port, tracing, tracing_service_name,
│                  tracing_environment, jaeger_endpoint, tracing_sampling_rate
├── Recorder: recorder, recorder_db, recorder_no_api, recorder_api_port,
│             recorder_max_requests, recorder_retention_days
├── Chaos (HTTP): chaos, chaos_scenario, chaos_latency_ms, chaos_latency_range,
│                 chaos_latency_probability, chaos_http_errors,
│                 chaos_http_error_probability, chaos_rate_limit,
│                 chaos_bandwidth_limit, chaos_packet_loss
├── Chaos (gRPC): chaos_grpc, chaos_grpc_status_codes,
│                 chaos_grpc_stream_interruption_probability
├── Chaos (WebSocket): chaos_websocket, chaos_websocket_close_codes,
│                      chaos_websocket_message_drop_probability,
│                      chaos_websocket_message_corruption_probability
├── Chaos (GraphQL): chaos_graphql, chaos_graphql_error_codes,
│                    chaos_graphql_partial_data_probability,
│                    chaos_graphql_resolver_latency
├── Chaos (Random): chaos_random, chaos_random_error_rate,
│                   chaos_random_delay_rate, chaos_random_min_delay,
│                   chaos_random_max_delay
├── Resilience: circuit_breaker, bulkhead (及各自参数)
├── Traffic Shaping: traffic_shaping, bandwidth_limit, burst_size
├── AI: ai_enabled, rag_provider, rag_model, rag_api_key, reality_level
├── API Config: spec, spec_dir, merge_conflicts, api_versioning, base_path
├── Admin: admin, admin_port
├── Validation: dry_run, progress, verbose
└── Misc: no_config, no_rate_limit, shadow, conformance_buffer_*
```

---

## 2. Baafoo CLI 现状

**BaafooCli.java** — 仅 3 个子命令：

```java
baafoo init [dir] [--non-interactive]   // 生成配置模板 + 启动脚本
baafoo version                           // 显示版本
baafoo help                              // 显示帮助
```

`init` 生成物：
- `baafoo-agent.yml` — Agent 配置
- `baafoo-server.yml` — Server 配置
- `baafoo-rules.yml` — 示例规则
- 启动脚本 (sh/bat)

功能非常基础，没有 spec 导入、没有录制管理、没有校验能力。

---

## 3. 功能对比

| 能力 | mockforge CLI | Baafoo CLI | 差距 |
|------|:---:|:---:|------|
| 项目脚手架 | ✅ blueprint + wizard | ✅ 基础 init | 中等 |
| OpenAPI 导入 | ✅ 完整 | ❌ | **巨大** |
| AsyncAPI 导入 | ✅ 完整 | ❌ | **巨大** |
| Postman/Insomnia/cURL 导入 | ✅ | ❌ | 大 |
| 录制管理 | ✅ recorder/flow | ❌ (只有采集) | **巨大** |
| 故障注入 (CLI) | ✅ 20+ 参数 | ❌ (配置式) | 不适用 |
| 状态快照 | ✅ | ❌ | 大 |
| 时间旅行 | ✅ | ❌ | 小 |
| Fixture 校验 | ✅ | ❌ | 中 |
| 日志查看 | ✅ | ❌ | 小 |
| AI 交互 (CLI) | ✅ voice/mockai/... | ❌ | 不适用 |
| 代码生成 | ✅ client/backend | ❌ | 不适用 |
| 云端协作 | ✅ cloud/workspace | ❌ | 不适用 |
| 协议独立 serve | ✅ 6 种协议 | ❌ | 不适用 |
| 插件系统 | ✅ WASM | ❌ | 不适用 |
| Shell 补全 | ✅ | ❌ | 小 |

---

## 4. 迁移评估

### 4.1 值得迁移（按 ROI 排序）

#### P0: `import openapi` / `import asyncapi`（5 人天）

**为什么高 ROI**：这是降低 Baafoo 使用门槛最直接的方式。目前用户需要：
1. 手动在 Web Console 创建规则
2. 手动填写 host/port/path/method/statusCode/body
3. 或依赖 Java Agent 录制真实流量后补充规则

导入 OpenAPI/AsyncAPI spec 可以自动生成完整的挡板规则集。

**实现要点**：
```
baafoo import openapi ./api.yaml --output data/rules/
baafoo import openapi ./api.yaml --verbose              # 显示覆盖率报告
baafoo import asyncapi ./events.yaml --protocol kafka

OpenApiImporter:
  1. 解析 Swagger/OpenAPI 3.x YAML/JSON
  2. 遍历 paths → 生成 Rule (method/path/status/body)
  3. 从 examples 或 schema 生成响应体 (JSON Schema → JSON)
  4. 从 servers[0].url 提取 base host
  5. 输出 YAML 到 data/rules/

AsyncApiImporter:
  1. 解析 AsyncAPI 2.x/3.x
  2. 遍历 channels → 生成 Kafka/Pulsar/JMS 规则
  3. 从 bindings 推断协议
  4. 输出 YAML 规则
```

#### P1: `init` 增强（1.5 人天）

**做法**：
- `--blueprint`：从预置模板（电商/支付/微服务）创建完整项目
- `--spec`：直接传入 OpenAPI 文件，init 同时生成规则
- `--protocols`：覆盖协议选择

```
baafoo init my-project --blueprint ecommerce
baafoo init . --spec api.yaml
baafoo init . --protocols kafka,pulsar
```

#### P2: `recorder convert`（2 人天）

录制数据 → 挡板规则的转换。Baafoo 已有 `RecordingEntry` 存储，缺的是"转成可用的 Rule YAML"。

```
baafoo recorder convert --recording-id abc123 --output data/rules/
baafoo recorder convert --input recordings/ --output data/rules/ --format yaml
```

#### P3: `validate-fixtures`（1 人天）

批量校验 `data/rules/` 下所有 YAML 的结构合法性。

#### 低优先级（有需求时再考虑）

| 功能 | 人天 | 备注 |
|------|------|------|
| `flow` 流程录制管理 | 3 | Phase 1 行为克隆的前置 |
| `snapshot` 状态快照 | 2 | 测试环境重置场景 |
| `completions` shell 补全 | 0.5 | picocli 自带，一行配置 |
| `logs` 日志查看 | 1 | 需要 Admin API 暴露 |

### 4.2 不建议迁移

| 功能 | 理由 |
|------|------|
| `serve` 多协议端口 CLI | 配置式（baafoo-server.yml）更适合长期服务 |
| Chaos/Traffic 全套 CLI 参数 | 已有 FaultInjection 规则配置 |
| `client`/`backend` 代码生成 | 不是 Baafoo 定位，mockforge 的差异化产品功能 |
| `cloud`/`workspace`/`login`/`tunnel` | 云端协作，Baafoo 定位是企业内网使用 |
| `voice`/`mockai`/`contract-diff`/`governance` AI CLI | Baafoo AI 交互更适合在 Web Console 做 |
| `kafka`/`mqtt`/`amqp`/`smtp`/`ftp` 独立 serve | server 已经内嵌所有协议 broker |
| `time`/`mod`/`drift-learning` | 边缘功能 |
| `plugin` CLI | WASM 机制 vs Java Agent bytebuddy，架构不兼容 |

---

## 5. 总结

**mockforge CLI 约 70-80% 功能对 Baafoo 不适用**，原因：

1. **入口模式不同** — mockforge CLI 是"一条命令启动一切"，Baafoo 的服务是长期运行进程
2. **协议管理方式不同** — mockforge 对每个协议提供独立 CLI 入口，Baafoo 只是通过 Java Agent 透明拦截
3. **AI 交互入口不同** — mockforge 暴露了大量 CLI 入口给 AI 功能，Baafoo 的配置式 Web Console 更合适

**真正值得做的 5 项，总计 ~12.5 人天**：

| 优先级 | 功能 | 人天 | 核心价值 |
|--------|------|------|---------|
| P0 | OpenAPI/AsyncAPI 导入 | 5 | 自动生成挡板规则，零配置启动 |
| P1 | `init` 增强 | 1.5 | blueprint 模板 + spec 驱动初始化 |
| P2 | `recorder convert` | 2 | 录制→规则，闭环 |
| P3 | `validate-fixtures` | 1 | 静态校验，避免生产报错 |
| P3 | `flow` 流程管理 | 3 | 行为克隆的前置基础设施 |
