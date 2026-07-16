# Baafoo 全量代码审查 — 汇总报告

## 严重性分布

| 严重性 | 数量 | 占比 |
|--------|------|------|
| **Critical** | 17 | 12.4% |
| **High** | 28 | 20.4% |
| **Medium** | 47 | 34.3% |
| **Low** | 45 | 32.9% |
| **总计** | **137** | 100% |

## 模块分布

| 模块 | Critical | High | Medium | Low | 总计 |
|------|----------|------|--------|-----|------|
| baafoo-core | 3 | 2 | 10 | 8 | 23 |
| baafoo-server | 6 | 12 | 12 | 6 | 36 |
| baafoo-agent | 5 | 6 | 8 | 10 | 29 |
| plugin-api/cli/test | 0 | 3 | 8 | 13 | 24 |
| Web 前端 | 0 | 5 | 7 | 13 | 25 |
| 构建/配置 | 3 | 2 | 2 | 8 | 15 |

## P0 — 必须立即修复 (17 个)

| ID | 模块 | 文件:行 | 问题 | 影响 |
|----|------|---------|------|------|
| CORE-C1 | core | ServerConfig.java:187-213 | JWT Secret 和 API Keys 通过 Jackson getter 序列化泄露 | 凭据泄露 |
| CORE-C2 | core | User.java:26-27,38-39 | passwordHash 和 apiKey 通过 getter 泄露 | 凭据泄露 |
| CORE-C3 | core | PaginatedResult.java:34,49 | size<=0 时除零崩溃 | 服务可用性 |
| SRV-C1 | server | BaafooServer.java:237,258 | 硬编码管理密码 `B@af00!Adm1n#2026` | 权限绕过 |
| SRV-C2 | server | PassthroughProxy.java:84-86 | SSL 证书验证跳过 (MITM) | 中间人攻击 |
| SRV-C3 | server | AuthService.java:201-212 | SHA-256 单次无迭代密码哈希 | 凭据提取 |
| SRV-C4 | server | AuthApiHandler.java:32-44 | 登录端点无限速/锁定 | 暴力破解 |
| SRV-C5 | server | StaticFileHandler.java:136 | 路径遍历漏洞 (replace 绕过) | 任意文件读取 |
| SRV-C6 | server | AuthFilter.java/ApiContext.java | 双权限检查系统不一致 | 权限绕过 |
| AGT-C1 | agent | RouteManager.java:82-84 | setMode() 未更新 App CL 的 CURRENT_MODE | 路由模式不一致 |
| AGT-C2 | agent | GlobalRouteState.java:250-255 | startRecording TOCTOU 竞争导致数据丢失 | 录制丢失 |
| AGT-C3 | agent | RecordingBuffer.java:95-121 | flush() 与 add() 竞态导致数据丢失 | 录制丢失 |
| AGT-C4 | agent | GlobalRouteState.java:158-173 | recordDns 缓存满时丢弃新条目 | DNS 路由失败 |
| AGT-C5 | agent | RouteManager.java:277-295 | Bootstrap CL 上非原子的 clear+putAll | 路由瞬时空 |
| BLD-C1 | build | docker-compose.yml:33,52 | 硬编码数据库密码 (明文) | 凭据泄露 |
| BLD-C2 | build | .env.test | 数据库凭据已提交到 Git | 凭据泄露 |
| BLD-C3 | build | baafoo-server.yml:66 | auth.enabled 默认关闭 | 无认证访问 |

## P1 — 高优先级 (28 个)

### 安全 (6)
| ID | 文件:行 | 问题 |
|----|---------|------|
| SRV-H1 | AuthService.java:105-107 | 认证关闭时所有请求获得 admin 角色 |
| SRV-H2 | StaticFileHandler.java:230 | 错误消息未转义 -> XSS |
| SRV-H3 | ManagementApiHandler.java:122 | CORS `Access-Control-Allow-Origin: *` |
| AGT-H1 | ConsulDnsAdvice.java:71 / ConsulHttpAdvice.java:28 | 异常被静默吞没 |
| AGT-H2 | BaafooAgent.java:467-477 | Java 9+ 模块系统下 findBootstrapClass 失败 |
| AGT-H3 | ControlChannel.java:207-208 | agentId 未 URL 编码 |

### 逻辑/正确性 (7)
| ID | 文件:行 | 问题 |
|----|---------|------|
| SRV-H4 | KafkaProtocolDecoder.java:446 | buildRecordSet 修改共享字节数组(竞争) |
| SRV-H5 | RecordingCleanupTask.java:111 | 清理任务删除了最新记录而不是最旧的 |
| SRV-H6 | FileStorage.java:296 | deleteScene 中的不可达代码 |
| SRV-H7 | JmsMockBroker.java:165-180 | connection.start() 在 producer.send() 之后 |
| SRV-H8 | KafkaProtocolDecoder.java:564-585 | 固定 producerId=1，幂等生产者失败 |
| AGT-H4 | RecordingBuffer.java:71-77 | start() 双启动竞争 |
| AGT-H5 | AgentManifest.setCurrentMode | 模式变更后 Bootstrap CL 过时 |

### 性能/稳定性 (3)
| ID | 文件:行 | 问题 |
|----|---------|------|
| SRV-H9 | TcpStubHandler.java:316 | EventLoop 上编译正则表达式(ReDoS) |
| SRV-H10 | HttpStubHandler.java:74 | ByteBuf 未释放 |
| SRV-H11 | JdbcStorageService.java:172-188 | 缓存不一致(volatile 字段独立) |

### 测试模块资源泄漏 (3)
| ID | 文件:行 | 问题 |
|----|---------|------|
| TST-H1 | FeignCallerService.java:42-47 | OkHttpClient 每请求创建(线程池泄漏) |
| TST-H2 | PulsarCallerService.java:34-43 | Producer 未在 finally 中关闭 |
| TST-H3 | ExternalApiClient.java:27 | InputStreamReader 未指定 charset |

### Docker/构建 (4)
| ID | 问题 |
|----|------|
| BLD-H1 | Dockerfile 使用 EOL 的 openjdk:8-jre-alpine |
| BLD-H2 | docker-compose.test.yml:16 `!override` 语法不兼容 v1 |
| BLD-H3 | Dockerfile 缺少 `exec`，SIGTERM 无法优雅停止 |
| BLD-H4 | data/baafoo.mv.db 和 trace.db 已提交到 Git |

## P2 — 中等优先级 (47 个)

详见各模块详细报告。关键类别：
- **线程安全**: MatchEngine patternCache 边界检查非原子、ConcurrentHashMap 可见性问题
- **代码异味**: extractPath 重复 4 处、AgentRegistration 重复定义、死代码 DnsResolutionAdvice
- **API 设计**: 集合返回可变内部引用、equals/hashCode 缺失、toString 泄露
- **资源管理**: Pulsar 生产者泄漏、KafkaProducer 每请求创建、Connection 未关闭
- **前端**: ECharts 未 dispose、`allow-create` 环境选择器风险、乐观 UI 错误处理

## P3 — 低优先级 (45 个)

- 命名不一致、日志拼接代替格式化、字符串常量散落
- 平台默认编码依赖、未使用的导入和方法
- 文档与实际配置不一致
- 前端 bundle 过大（全量注册 Element Plus Icons + ECharts）

---

## 最高风险模式总结

1. **凭据硬编码与泄露** — 5 处发现（源码中硬编码密码 ×2、配置文件明文密码 ×2、getter 泄露 ×2）
2. **认证与授权绕过** — 4 处发现（auth 默认关闭、双权限系统不一致、硬编码 admin 密码、JWT 密钥自动生成）
3. **Netty EventLoop 阻塞** — 2 处发现（Thread.sleep + Pattern.compile）
4. **不安全 SSL/TLS** — 1 处发现（InsecureTrustManagerFactory）
5. **线程安全竞争** — 7 处发现（TOCTOU、clear+putAll、flush 与 add 竞态、非原子边界检查）
6. **路径遍历** — 1 处发现（String.replace 绕过）
7. **前端 XSS** — 2 处发现（v-html + inline onclick、错误消息未转义）
