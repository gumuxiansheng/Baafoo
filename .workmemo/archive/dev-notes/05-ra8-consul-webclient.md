# R-A8: Consul WebClient/RestTemplate 拦截

> 实施时间: 2026-06-13
> PRD 需求: R-A8 AC-03

## 分析

### 现有实现覆盖范围

当前 `ConsulHttpAdvice` 拦截 `sun.net.www.http.HttpClient.openServer()`，覆盖了所有基于 `java.net.HttpURLConnection` 的 HTTP 客户端，包括：

- **Ecwid Consul SDK** — Spring Cloud Consul 默认使用，底层走 `HttpURLConnection`
- **Orbitz Consul SDK** — 同样走 `HttpURLConnection`
- **原生 HttpURLConnection 调用** — 任何直接使用 `HttpURLConnection` 的代码

### 未覆盖路径

- **Spring WebClient (Reactor Netty)** — Spring Cloud Consul 可配置使用 WebClient 替代 Ecwid SDK，底层走 NIO，不经过 `sun.net.www.http.HttpClient`
- **Apache HttpClient** — 如果应用配置了 Apache HttpClient 作为 HTTP 传输层

### 决策

PRD AC-03 明确说明："v1.0 仅保证 DNS 模式 + OkHttp 客户端；Spring Cloud Consul 通过 WebClient 放 v1.5"。

当前实现已满足 PRD v1.0 要求。WebClient 拦截推迟到 v1.5。

## 修改文件

无代码修改。仅记录分析结论。
