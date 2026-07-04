# 多 Agent 共存测试方案（SkyWalking + JaCoCo + Baafoo）

**测试目标**: 验证 Baafoo Agent 与主流 APM/Coverage Agent（SkyWalking、JaCoCo）同时挂载到 Spring Cloud Alibaba 微服务应用时，三方的字节码增强互不冲突，各项功能均正常工作。

**测试范围**: spring-cloud-alibaba 微服务测试场景（Provider + Consumer + Nacos + Feign），覆盖应用启动、Baafoo Mock 拦截（host-based + serviceName-based）、SkyWalking 链路追踪、JaCoCo 覆盖率采集四大能力。

**参考计划**: `testing/PROJECT-TEST-PLAN.md` 第 10.7 节 COMP-AGENT-001（JaCoCo）、COMP-AGENT-002（SkyWalking）、COMP-AGENT-008（多 Agent 组合）

**优先级**: P0（JaCoCo 是本项目自身的覆盖率工具，必须验证共存；SkyWalking 是国内最常用的 APM Agent）

## 1. 测试矩阵

| Agent | 版本 | 类型 | 加载顺序 | 作用 |
|-------|------|------|---------|------|
| JaCoCo | 0.8.12 | 覆盖率采集 | 1 (首位) | 采集测试覆盖率数据 |
| SkyWalking | 9.4.0 | APM 监控 | 2 | 链路追踪、性能监控 |
| Baafoo | 1.1.0-SNAPSHOT | Mock/挡板 | 3 (末位) | 微服务调用拦截、Mock 响应 |

**加载顺序选择依据**:
- JaCoCo 必须在首位（按 PROJECT-TEST-PLAN.md 第 1859 行说明）
- SkyWalking 在中间，APM 类 Agent 应在 Mock 类 Agent 前
- Baafoo 在末位，业务挡板类 Agent 应在监控类 Agent 后

## 2. 测试环境

### 2.1 新增容器

| 容器 | 镜像 | 端口 | 作用 |
|------|------|------|------|
| `baafoo-enterprise-oap` | `apache/skywalking-oap-server:9.4.0-java17` | 11800(gRPC), 12800(HTTP) | SkyWalking OAP 后端 |
| `baafoo-enterprise-ui` | `apache/skywalking-ui:9.4.0-java17` | 18080→8080 | SkyWalking Web UI |

### 2.2 Agent JAR 来源

| Agent | 下载地址 | 大小（约） |
|-------|---------|-----------|
| JaCoCo | `https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/0.8.12/org.jacoco.agent-0.8.12.jar` | 290KB |
| SkyWalking | `https://archive.apache.org/dist/skywalking/java-agent/9.4.0/apache-skywalking-java-agent-9.4.0.tgz` | 25MB（解压后） |

### 2.3 Agent 配置参数

**JaCoCo**（append 模式，输出到 `/tmp/jacoco/jacoco.exec`）:
```
-javaagent:/app/agents/jacoco-agent.jar=output=tcpserver,address=0.0.0.0,port=6300,append=true,classdumpdir=/tmp/jacoco/classdumps
```

**SkyWalking**（指向 OAP 后端，服务名按容器区分）:
```
-javaagent:/app/agents/skywalking/skywalking-agent.jar
-DSW_AGENT_NAME=service-consumer
-DSW_AGENT_COLLECTOR_BACKEND_SERVICES=baafoo-enterprise-oap:11800
```

**Baafoo**（保持现有配置）:
```
-javaagent:/app/baafoo-agent.jar=config=/app/baafoo-agent.yml
```

### 2.4 最终 JVM 启动命令

```bash
java ${JAVA_OPTS} \
  -javaagent:/app/agents/jacoco-agent.jar=output=tcpserver,address=0.0.0.0,port=6300,append=true \
  -javaagent:/app/agents/skywalking/skywalking-agent.jar \
  -DSW_AGENT_NAME=service-consumer \
  -DSW_AGENT_COLLECTOR_BACKEND_SERVICES=baafoo-enterprise-oap:11800 \
  -javaagent:/app/baafoo-agent.jar=config=/app/baafoo-agent.yml \
  -jar /app/app.jar
```

## 3. 测试用例

### MULTI-001: 三 Agent 同时加载应用启动正常 (P0)

**目标**: 验证 Provider/Consumer 同时挂载 JaCoCo + SkyWalking + Baafoo 三个 Agent 后，应用能正常启动，健康检查通过，无字节码转换冲突。

| 项 | 内容 |
|----|------|
| **前置条件** | Nacos、Baafoo Server、SkyWalking OAP 均健康 |
| **执行步骤** | 1. 构建含 3 个 Agent 的 Provider/Consumer 镜像<br>2. `docker compose up -d`<br>3. 等待 90 秒观察启动日志 |
| **预期结果** | 1. 容器健康检查通过（`healthy`）<br>2. 日志含 SkyWalking agent 启动信息（`SkyWalking agent initialized`）<br>3. 日志含 Baafoo agent 启动信息（`Baafoo Agent started`）<br>4. 日志无 `ClassCastException`/`NoClassDefFoundError`/`LinkageError` 等字节码冲突错误<br>5. 日志无 ByteBuddy `transform error` |
| **通过标准** | 全部预期结果满足 |

### MULTI-002: Baafoo Mock 拦截功能正常 (P0)

**目标**: 验证在 JaCoCo + SkyWalking 同时存在时，Baafoo 的 host-based 和 serviceName-based Mock 拦截能力均正常工作。

| 项 | 内容 |
|----|------|
| **前置条件** | MULTI-001 通过；Mock 规则 `sca-provider-echo-mock-svc` 已创建 |
| **执行步骤** | 1. `GET http://localhost:18083/echo-feign/test`<br>2. 检查响应 body<br>3. 检查 Consumer Agent 日志的 HttpOpenServerAdvice/DnsResolve 拦截记录<br>4. 检查 Server 端规则匹配日志 |
| **预期结果** | 1. 响应 200，body=`hello Nacos Discovery mock via serviceName`<br>2. Agent 日志含 `HttpOpenServerAdvice redirect` 和 `DnsResolve redirect`<br>3. Server 端无 `No Baafoo rule matched` 日志<br>4. JaCoCo/SkyWalking 不干扰 Baafoo 的 Socket/HttpClient 拦截 |
| **通过标准** | 全部预期结果满足 |

### MULTI-003: SkyWalking 链路追踪数据生成正常 (P0)

**目标**: 验证 Baafoo Agent 不影响 SkyWalking 的字节码注入，链路追踪数据能正常上报 OAP 后端。

| 项 | 内容 |
|----|------|
| **前置条件** | MULTI-001 通过；SkyWalking OAP + UI 已启动 |
| **执行步骤** | 1. 触发 Feign 调用 `GET http://localhost:18083/echo-feign/test`（触发一次 trace）<br>2. 等待 15 秒（SkyWalking 数据上报周期）<br>3. 查询 SkyWalking OAP API: `GET http://localhost:12800/graphql`<br>4. 在 UI（`http://localhost:18080`）查看 Trace 列表 |
| **预期结果** | 1. SkyWalking OAP 收到 Consumer 服务的 trace 数据<br>2. Trace 中含 `service-consumer` 节点<br>3. Trace 含 Feign 调用 span（HTTP GET /echo-feign/test）<br>4. 不出现 SkyWalking 上报失败的错误日志 |
| **通过标准** | 至少 1 条 trace 含 Consumer 节点 |

### MULTI-004: JaCoCo 覆盖率数据生成正常 (P0)

**目标**: 验证 Baafoo + SkyWalking 不影响 JaCoCo 的字节码注入，覆盖率数据能正常采集。

| 项 | 内容 |
|----|------|
| **前置条件** | MULTI-001 通过 |
| **执行步骤** | 1. 触发若干次 Feign 调用（产生覆盖率数据）<br>2. 通过 JaCoCo TCP API（端口 6300）dump 覆盖率数据<br>3. 用 `jacococli` 工具生成 HTML 报告<br>4. 检查报告中 `com.baafoo.test.sca.consumer` 包的覆盖率 |
| **预期结果** | 1. JaCoCo dump 成功（生成 jacoco.exec）<br>2. HTML 报告中 Consumer 应用代码有覆盖率数据（行覆盖 > 0）<br>3. 关键类 `TestController`、`EchoClient` 有覆盖率记录<br>4. 不出现 JaCoCo 字节码注入失败日志 |
| **通过标准** | 关键类覆盖率 > 0 |

### MULTI-005: Feign 调用链路在 SkyWalking 中可见 (P1)

**目标**: 验证 Mock 拦截的 Feign 调用也能被 SkyWalking 追踪到（Baafoo 重定向后 trace 不断裂）。

| 项 | 内容 |
|----|------|
| **前置条件** | MULTI-002 和 MULTI-003 通过 |
| **执行步骤** | 1. 触发 `GET http://localhost:18083/echo-feign/test`<br>2. 等待 15 秒<br>3. 在 SkyWalking UI 查看 trace 详情<br>4. 检查 trace 中是否含 Consumer → Baafoo-Server 的 span |
| **预期结果** | 1. Trace 含 Consumer 入口 span（HTTP GET /echo-feign/test）<br>2. Trace 含出站 HTTP 调用 span（指向 service-provider）<br>3. Trace 不因 Baafoo 重定向而断裂<br>4. span 中可见 Baafoo Server IP（172.x.x.x:9000） |
| **通过标准** | Trace 含入口 + 出站两个 span，未断裂 |

### MULTI-006: 多 Agent 加载顺序影响测试 (P1)

**目标**: 验证不同 Agent 加载顺序对功能的影响，确定推荐顺序。

**测试变体**:
- 变体 A: `jacoco → skywalking → baafoo`（推荐顺序）
- 变体 B: `skywalking → jacoco → baafoo`
- 变体 C: `baafoo → jacoco → skywalking`（Baafoo 在首位）

| 项 | 内容 |
|----|------|
| **执行步骤** | 对每个变体重新构建镜像、启动、运行 MULTI-001~002 测试 |
| **预期结果** | 变体 A、B 全部通过；变体 C 可能出现 Baafoo Bootstrap 类被 JaCoCo 增强后冲突 |
| **通过标准** | 变体 A、B 通过即可；变体 C 失败不影响通过 |

### MULTI-007: 性能影响评估 (P2)

**目标**: 评估多 Agent 同时挂载对应用性能的影响。

| 项 | 内容 |
|----|------|
| **执行步骤** | 1. 单 Baafoo Agent 场景下用 `ab` 压测 1000 次<br>2. 三 Agent 场景下用 `ab` 压测 1000 次<br>3. 对比平均响应时间、QPS |
| **预期结果** | 三 Agent 场景响应时间增加 < 50% |
| **通过标准** | 性能衰减在可接受范围（< 50%） |

### MULTI-008: 类转换冲突检测 (P1)

**目标**: 检测多 Agent 是否存在字节码转换冲突（同一类被多个 Agent 重复增强导致异常）。

| 项 | 内容 |
|----|------|
| **执行步骤** | 1. 检查应用启动日志中 ByteBuddy/ASM/ByteBuddy 的 retransform 错误<br>2. 检查 SkyWalking 是否能正常增强 `HttpURLConnection`、`Socket` 等类（与 Baafoo 拦截点重叠）<br>3. 检查 JaCoCo 是否能正常增强 Consumer 应用业务类 |
| **预期结果** | 1. 无 `ByteBuddy transform error` 日志<br>2. 无 `ClassFormatError`/`VerifyError`<br>3. SkyWalking 增强的类不与 Baafoo 增强的类（java.net.InetAddress、sun.net.www.http.HttpClient）冲突 |
| **通过标准** | 启动日志无转换冲突错误 |

## 4. 验收标准

| 用例 ID | 优先级 | 必须通过 |
|---------|-------|---------|
| MULTI-001 | P0 | ✅ |
| MULTI-002 | P0 | ✅ |
| MULTI-003 | P0 | ✅ |
| MULTI-004 | P0 | ✅ |
| MULTI-005 | P1 | ✅ |
| MULTI-006 | P1 | 推荐顺序变体 A 必须通过 |
| MULTI-007 | P2 | 可选 |
| MULTI-008 | P1 | ✅ |

**最终判定**: P0 用例全部通过 + P1 用例至少通过 2/3 = 测试通过

## 5. 风险与限制

### 5.1 已知风险

1. **Bootstrap CL 类冲突**: Baafoo 将 GlobalRouteState 注入到 Bootstrap CL，SkyWalking 也可能注入 Bootstrap 类，存在类定义冲突风险
2. **java.net.InetAddress 增强冲突**: SkyWalking 增强 HTTP 客户端相关类，Baafoo 通过 DnsResolve*Advice 增强 `java.net.InetAddress`，可能冲突
3. **JaCoCo 增强时机**: JaCoCo 在类加载时（file install）增强，Baafoo 在类加载后（retransform）增强，理论上不冲突，但需验证
4. **内存压力**: 三 Agent 同时挂载增加约 100MB JVM metaspace，需调整容器内存上限

### 5.2 缓解措施

- 内存上限从 256m 提升到 512m
- 若发现冲突，记录冲突类名和 Agent 加载顺序，提供共存指南
- 若变体 A 失败，尝试变体 B（SkyWalking 在前）

## 6. 执行流程

```
[阶段 1: 设计文档] → [阶段 2: 下载 Agent JAR]
                     ↓
                  [阶段 3: 修改 Dockerfile/docker-compose]
                     ↓
                  [阶段 4: 构建镜像]
                     ↓
                  [阶段 5: 启动环境，等待健康]
                     ↓
                  [阶段 6: 执行 MULTI-001~005 测试]
                     ↓
                  [阶段 7: 执行 MULTI-008 冲突检测]
                     ↓
                  [阶段 8: 汇总测试报告]
```

## 7. 测试报告输出

测试报告输出到 `testing/enterprise/MULTI-AGENT-TEST-REPORT.md`，包含:
- 测试环境拓扑图
- 每个用例的执行结果（PASS/FAIL/SKIP）
- 关键证据（日志片段、截图、数据）
- 发现的问题与解决方案
- 多 Agent 共存指南（推荐加载顺序、配置注意事项）
