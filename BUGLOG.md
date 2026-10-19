# BUGLOG — Baafoo 缺陷记录

> 轻量级缺陷台账：每条修复一个根因，防重犯。改相关代码前先 Grep 本文件。
> 条目格式约定见 bug-recorder skill；ID 规则 `BUG-YYYYMMDD-NN`。

## BUG-20261019-01  HTTP 转发多路径挂起导致客户端超时
- 标签：HTTP / PassthroughProxy / Netty
- 现象：内网机器上 HTTP 端口转发无响应，客户端直接超时。复现：后台读完请求后关闭连接（无响应字节）时，`PassthroughProxy.forward()` 返回的 future 25s 仍未完成，客户端无限等待
- 根因：`PassthroughResponseHandler` 未覆写 `channelInactive`——后台提前关闭连接时只触发 channelInactive，而 `ReadTimeoutHandler`（IdleStateHandler）在 channelInactive 时会取消自己的定时任务，两条兜底路径同时失效，promise 永不完成。另有两处关联缺陷：`writeAndFlush(request)` 失败无监听；`b.connect()` 的 DNS 解析在调用线程（Netty EventLoop）上同步阻塞，`CONNECT_TIMEOUT_MILLIS` 不覆盖 DNS 阶段，不可解析主机名会冻结整个 EventLoop（管理端 8084 与 stub 端 9000+ 共用 workerGroup）
- 修复：channelInactive 时 `promise.setFailure(IOException)`；write 失败传播并失败 promise；新增整体超时兜底（默认 30s，`-Dbaafoo.passthrough.timeoutSec` 可覆盖）；connect（含 DNS）移入静态 `CONNECT_EXECUTOR`（8 个 daemon 线程）执行
- 影响文件：baafoo-server/src/main/java/com/baafoo/server/handler/PassthroughProxy.java, baafoo-server/src/test/java/com/baafoo/server/handler/PassthroughProxyTest.java
- 防重犯：Netty handler 的 promise/future 必须覆盖 channelInactive 与 write 失败路径，不能只依赖 ReadTimeoutHandler；任何在 EventLoop 上调用的阻塞操作（DNS、JDBC）都必须移出 loop；给跨网络调用加有界整体超时
- 关联：提交 `fix(server): 修复 HTTP 转发挂起与超时 (#BUG-20261019-01)`
