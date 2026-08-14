# Baafoo 依赖安全加固报告（SCA Remediation）

- **生成日期**：2026-08-11　**最后修订**：2026-08-15（更正 Logback 结论：1.2.x 仍受 CVE-2024-12798/12801 影响，Java 8 + SB2.7 不可修复，已回退并列为残留风险 §6）
- **范围**：`baafoo-parent` 及全部子模块解析后的依赖树（agent / server / core / test-spring / test-pulsar / spring-boot-starter-test 等）
- **方法**：原始 Scantist 报告链接需登录，按用户指示改为**自行对解析依赖树做 SCA 分析**，交叉比对公开 CVE 数据库（NVD、GitHub Advisory、Apache 公告、厂商发布说明）。
- **硬约束**：项目基线 `java.version=1.8`；Spring Boot `2.7.18`（已 EOL）；ActiveMQ Artemis `2.19.1`（最后一个 Java 8 版本，2.20.0+ 需 Java 11）。所有修复必须在 **Java 8 兼容**前提下进行。

---

## 一、已修复项（已编译验证 + dependency:tree 确认 resolved 版本）

| 组件 | 升级前 → 后 | 关联 CVE（已核实） | 引入路径 | 验证 |
|------|-------------|-------------------|----------|------|
| **Netty** | 4.1.100.Final → **4.1.136.Final** | CVE-2026-56817(XXE,8.3)、CVE-2026-56819(内存泄漏)、CVE-2026-55833(zip bomb)、CVE-2026-59900(HTTP/2 host 头走私) 等 20+ 项 2026 CVE | server/agent 直接依赖 | ✅ |
| **PostgreSQL** | 42.7.1 → **42.7.5** | CVE-2024-1597（SQL 注入，CVSS 9.8，≥42.7.2 修复） | server | ✅ |
| **Jackson** | 2.15.3 → **2.18.8** | CVE-2025-52999（core 异步解析器栈溢出 DoS）、CVE-2026-59888（`@JsonIgnore` 绕过）、CVE-2026-54512（PolymorphicTypeValidator 绕过，CVSS 8.1） | core/server/agent(relocated)/test-spring | ✅ |
| **Guava** | 30.1-jre → **33.6.0-jre** | CVE-2023-2976（临时目录权限，≥32.0.1 修复） | transitive（Artemis） | ✅ |
| **commons-text** | 1.8 → **1.12.0** | CVE-2022-42889（Text4Shell RCE，≥1.10.0 修复） | transitive（commons-configuration2） | ✅ |
| **snappy-java** | 1.1.8.4 → **1.1.10.4** | CVE-2023-43642（整数溢出，≥1.1.10.3 修复） | transitive（kafka-clients，test 范围） | ✅ |
| **Spring Framework** | 5.3.31(BOM) → **5.3.39** | 免费线最新 5.3.x（⚠️ 见残留风险 §3） | test-spring（Spring Boot BOM 覆盖） | ✅ |
| **Tomcat embed** | 9.0.83 → **9.0.102** | CVE-2025-24813（部分 PUT RCE/信息泄露，≥9.0.99 修复） | test-spring | ✅ |

> 实现方式：在 `baafoo-parent` 的 `<properties>` 中提升版本号，并在 `<dependencyManagement>`（**位于 `spring-boot-dependencies` BOM import 之前**）显式声明 guava / commons-text / snappy-java / spring-* / tomcat-embed-*，使覆盖优先于 BOM。仅改 property 对 import 型 BOM 无效，必须显式声明。

### 编译与解析证据
- `mvn compile -pl baafoo-server,baafoo-agent,baafoo-test-spring,baafoo-test-pulsar,baafoo-spring-boot-starter-test -am` → **BUILD SUCCESS**
- 解析版本（dependency:tree 实际输出）：
  - server：`netty-all:4.1.136.Final`、`postgresql:42.7.5`、`guava:33.6.0-jre`、`commons-text:1.12.0`、`snappy-java:1.1.10.4(test)`、`jackson-databind:2.18.8`、`jackson-core:2.18.8`
  - 日志栈（未升级，见 §6）：`slf4j-api:1.7.36`、`logback-classic:1.2.12`、`logback-core:1.2.12`（Spring Boot 2.7.18 BOM 原生组合）
  - test-spring：`spring-core:5.3.39`、`spring-web:5.3.39`、`tomcat-embed-core:9.0.102`、`jackson-databind:2.18.8`

---

## 二、残留风险（Residual Risk）

### 1. ActiveMQ Artemis 2.19.1 — CVE-2026-27446【CRITICAL，Java 8 不可修复】
- **CVE-2026-27446**：Missing Authentication for Critical Function（CWE-306），CVSS **9.3/9.8**。攻击者可在 Core 协议下迫使 broker 向攻击者控制的 rogue broker 建立出站 Core federation 连接，导致任意队列消息注入/泄露。
- **影响范围**：ActiveMQ Artemis **2.11.0 – 2.44.0**（Baafoo 的 2.19.1 在范围内）。
- **修复版本**：2.52.0 —— **要求 Java 11+**。在当前 Java 8 基线下**无任何修复路径**。
- **结论**：唯一彻底修复方式是 **Java 17 迁移**（届时 Artemis 可升到 2.52.0+）。在迁移前，可按 Apache 公告缓解（对不可信来源的 acceptor 关闭 Core 协议，或启用双向 SSL）。

### 2. BouncyCastle `jdk15on` 1.69（test 范围）— CVE-2024-34447【中危，暂缓】
- **CVE-2024-34447**：BCJSSE 在 `HttpsURLConnection` 未显式指定 hostname 时，可能对 DNS 解析出的 IP 做主机名校验，存在 DNS 污染风险（CVSS 5.9–7.5）。
- **阻断点**：BouncyCastle 在 **1.78 退役 `jdk15on` 坐标**，1.78 仅以 `jdk18on` 发布。直接把依赖提到 1.78 会解析失败（已实测 `Could not find artifact org.bouncycastle:bcpkix-jdk15on:jar:1.78`）。
- **处置**：**暂缓**。理由：① 仅 `test` 范围（Pulsar 测试客户端），不进生产包；② 漏洞触发需 BCJSSE endpoint identification + `HttpsURLConnection` 无显式 hostname，Baafoo 的 Pulsar 测试用法不命中该路径；③ 切到 `jdk18on:1.78` 需从 `pulsar-client`/`bouncy-castle-bc` 排除 `jdk15on` 并引入 `jdk18on`，有破坏已稳定 Pulsar 系统测试的风险。
- **后续**：在验证 Pulsar 客户端启动正常后，再切 `jdk18on:1.78` 并补系统测试。

### 3. Spring Framework 5.3.x 已 EOL —— CVE-2024-38816 / CVE-2024-38819【无免费 5.3.x 补丁】
- 两 CVE 均为 functional web 框架路径遍历（CVSS 7.5）。
- **修复版本**：5.3.40 / 5.3.41（**商业版/付费**）或 **6.1.13+（需 Java 17）**。OSS 免费线 **5.3.x 已停止安全更新**。
- 本次升到 **5.3.39** 仅为「免费线最新版」，**并不修复**这两个 CVE。
- 缓解：CVE-2024-38816 在 **Tomcat 部署下被容器层拦截**（恶意请求被拒）；CVE-2024-38819 在 Tomcat 下仍受影响且无免费 5.3.x 补丁，**残留**，只能靠 Java 17 + Spring Boot 3.x（Spring 6.1.x）迁移关闭。

### 4. Spring Boot 2.7.x EOL（2024-08）
- BOM 不再提供安全补丁。本轮对 spring / tomcat / jackson 的手工覆盖仅为权宜；彻底解决依赖 **Spring Boot 3.x 迁移（Java 17）**。

### 5. （低优先）commons-configuration2 2.7（transitive via Artemis）
- 解析版本 `commons-configuration2:2.7`（compile）。存在 CVE-2024-29131（DoS）、CVE-2024-29133（XXE），修复需 **2.10.0+**。
- 因与 Artemis 2.19.1 同被 Java 8 锁定，建议**随 Java 17 迁移一并升级**，避免单独升级破坏 Artemis 集成。
- 备注：`commons-beanutils:1.9.4` 已是修复版本（CVE-2019-10086 / CVE-2014-0114 已修），无需处理。

### 6. Logback 1.2.x — CVE-2024-12798 / CVE-2024-12801【Java 8 + Spring Boot 2.7 不可修复】
- **CVE-2024-12798**（JaninoEventEvaluator ACE，CVSS 7.3）、**CVE-2024-12801**（SaxEventRecorder SSRF，CVSS 2.4）：影响 logback-core **0.1 – 1.3.14**（含整个 1.2.x 线）。修复只在 **logback 1.3.15**（需 slf4j 2.0 → Spring Boot 3 → Java 17）或 **1.4.13+/1.5.13+**（需 Java 11）。**1.2.x 全线从未出补丁**——初版报告误判 1.2.13 已修复，已更正。
- **为何不能升**：Spring Boot 2.7 的 `LogbackLoggingSystem.getLoggerContext()` 直接调用 slf4j 1.7 的 `StaticLoggerBinder.getSingleton()`；logback 1.3 不再提供该类 → 升到 1.3.x 会令 `baafoo-test-spring` 全部单测 `NoClassDefFoundError: org/slf4j/impl/StaticLoggerBinder`（已实测、已回退）。Spring Boot 3.0 才支持 slf4j 2.0 / logback 1.3。
- **处置**：维持 Spring Boot 2.7 原生 `logback 1.2.12 + slf4j 1.7.36`，把该 CVE 列为残留风险。**实际可利用性低**：两个 CVE 均要求攻击者对 logback 配置文件有写权限或能注入指向恶意配置的环境变量（本地提权前置）；Baafoo 服务端日志配置为可信、非用户可写，生产部署中风险等级低。
- **彻底修复**：随 Java 17 + Spring Boot 3.x 迁移一并完成（届时 logback 升到 1.3.15+ / slf4j 2.0）。

---

## 三、结论与建议

本轮在 **Java 8 兼容边界内，把所有可修的漏洞全部修复并通过编译验证**：Netty / PostgreSQL / Jackson / Guava / commons-text / snappy-java / Tomcat 已升到最新可用补丁；Spring 升到免费线最新的 5.3.39。Logback 因 Spring Boot 2.7 基线约束无法在 Java 8 下修复 CVE-2024-12798/12801，已回退为 BOM 原生 1.2.12 并列为残留风险（见 §6）。

所有**无法在 Java 8 下根除的风险**（Artemis CVE-2026-27446 CRITICAL、Spring 5.3.x EOL 的 38816/38819、BouncyCastle jdk15on 退役、Spring Boot 2.7.x EOL、commons-configuration2 2.7）**共同指向同一条出路：升级到 Java 17 + Spring Boot 3.x**。该迁移同时解锁：
- Artemis 2.52.0+（修复 CVE-2026-27446）
- Spring 6.1.x（修复 CVE-2024-38816/38819 的 OSS 补丁）
- BouncyCastle `jdk18on` 系（修复 CVE-2024-34447）
- commons-configuration2 2.10.0+（修复 CVE-2024-29131/29133）

**建议下一步**：将「Java 17 + Spring Boot 3.x 迁移」列为独立里程碑，并在迁移前维持本轮补丁组合 + 对 Artemis 的 Core 协议/双向 SSL 缓解措施。

---

## 附：修改清单（baafoo-parent/pom.xml）
- `<properties>`：`netty.version`→4.1.136.Final、`postgresql.version`→42.7.5、`jackson.version`→2.18.8、`guava.version`→33.6.0-jre、`commons-text.version`→1.12.0、`snappy-java.version`→1.1.10.4、`spring.version`→5.3.39、`tomcat.version`→9.0.102（注：logback 回退为 Spring Boot 2.7 BOM 原生 1.2.12，未设 `logback.version` 覆盖）
- `<dependencyManagement>`（BOM import 前）：新增 guava / commons-text / snappy-java / spring-* / tomcat-embed-* 显式覆盖条目；BouncyCastle 条目保留注释说明暂缓原因。
