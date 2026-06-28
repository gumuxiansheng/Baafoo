# R-A10: Record 模式字节录制

> 实施时间: 2026-06-13
> PRD 需求: R-A10 AC-01~AC-05

## 需求描述

在 `record` 或 `record-and-stub` 模式下，Agent 透明代理真实连接，同时将请求和响应的原始字节暂存于内存缓冲区。缓冲区满或录制 session 结束时，Agent 通过控制通道将录制数据上传至 Server 统一存储。

## 实施内容

### 新增文件

1. **RecordingBuffer** — 线程安全缓冲区，累积 RecordingEntry，满时自动 flush，30s 定时 flush，上传失败本地暂存重试
2. **RecordingInputStream** — FilterInputStream 包装器，记录所有读取字节为 direction="response" 的 RecordingEntry
3. **RecordingOutputStream** — FilterOutputStream 包装器，记录所有写入字节为 direction="request" 的 RecordingEntry
4. **SocketGetStreamAdvice** — 拦截 `Socket.getInputStream()/getOutputStream()`，在录制模式下包装返回的流
5. **SocketCloseAdvice** — 拦截 `Socket.close()` 清理录制 session

### 修改文件

- **RecordingEntry** — 新增 direction、sessionId、dataHex、durationMs 字段
- **GlobalRouteState** — 新增 RECORDING_SESSIONS、INPUT_STREAM_WRAPPER/OUTPUT_STREAM_WRAPPER 桥接函数
- **SocketConnectAdvice** — RECORD 模式允许真实连接并注册录制；RECORD_AND_STUB 模式注册录制+重定向
- **NioSocketConnectAdvice** — RECORD 模式允许真实连接并注册跟踪
- **BaafooAgent** — 初始化 RecordingBuffer，注册 getInputStream/getOutputStream/close 拦截
- **RouteManager** — addRecording/flushRecordings 委托给 RecordingBuffer

## AC 覆盖

- AC-01: ✅ RECORD 模式允许真实连接，不影响交互
- AC-02: ✅ 录制 session 存储在 Server，可用于回放
- AC-03: ✅ HTTP 请求字节录制（Server 端解析字节提取 method/URL/headers 等）
- AC-04: ✅ TCP 请求字节录制为 hex 格式，含时间戳
- AC-05: ✅ 上传失败本地暂存，Server 恢复后重试
