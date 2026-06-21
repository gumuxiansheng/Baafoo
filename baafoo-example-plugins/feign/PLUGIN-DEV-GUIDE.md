# Baafoo Feign Plugin

> 通用插件开发指南请参见 [docs/plugin-developer-guide.md](../../docs/plugin-developer-guide.md)。
> 本文档仅包含 Feign 插件的特定说明。

---

## 项目结构

```
baafoo-example-plugins/feign/
├── pom.xml
├── src/main/java/com/baafoo/plugin/feign/
│   ├── FeignPlugin.java              ← AgentPlugin 实现（stub 注册表 + 拦截逻辑）
│   ├── FeignClientAdvice.java        ← Byte Buddy Advice（参考实现）
│   └── demo/
│       └── FeignPluginDemo.java      ← 独立演示应用
├── src/main/resources/
│   └── META-INF/services/
│       └── com.baafoo.plugin.AgentPlugin   ← SPI 声明
└── src/test/java/com/baafoo/plugin/feign/
    └── FeignPluginTest.java          ← 单元测试（14 个用例）
```

## Feign 插件的特殊设计

FeignPlugin 使用 `InterceptResult.stub()` 返回 Mock 响应（HTTP 协议插件的典型模式），而非 `redirect()`（二进制协议插件的模式）。

插件内部维护了一个 `ConcurrentHashMap<String, StubEntry>` 作为 stub 注册表，key 为 `method + " " + path`（如 `GET /api/users`）。`intercept()` 方法通过请求头 `X-Feign-Method` 和 `X-Feign-Path` 查找匹配的 stub：

- 找到匹配 → 返回 `InterceptResult.stub(body, headers, statusCode)`
- 未找到 + 有 `originalCall` → 执行原始调用（passthrough）
- 未找到 + 无 `originalCall` → 返回 `InterceptResult.passthrough()`

## 运行演示

```bash
cd baafoo-example-plugins/feign
mvn compile exec:java -Dexec.mainClass=com.baafoo.plugin.feign.demo.FeignPluginDemo
```

## 运行测试

```bash
cd baafoo-example-plugins/feign
mvn test
```
