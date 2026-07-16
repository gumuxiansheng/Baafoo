# ADR-001: Bootstrap ClassLoader 注入方案

- **状态**: Accepted
- **日期**: 2026-05-30
- **关联**: `baafoo-agent/.../BaafooAgent.java`、`GlobalRouteState.java`

## 背景

Baafoo Agent 需要拦截 `java.net.Socket.connect()` 等 JDK 核心方法。ByteBuddy 字节码增强会将被拦截方法的 advice 代码内联到目标类中。如果 advice 代码引用了 App ClassLoader 的类，Bootstrap ClassLoader 下的 JDK 类无法看到这些类，导致 `NoClassDefFoundError`。

## 选项

| # | 方案 | 优点 | 缺点 |
|---|------|------|------|
| A | Java Instrumentation API + App CL | 标准 API | Bootstrap CL 无法访问 App CL 类 |
| B | Bootstrap JAR 注入 + 双 CL 副本 + 反射同步 | 解决 CL 隔离 | 同步复杂度高 |
| C | Java Agent + retransform | 可热替换 | 性能开销大 |

## 决策

选择 **方案 B**：`BaafooAgent.createBootstrapJar()` 将 `GlobalRouteState.class` 打包到 Bootstrap JAR，通过 `Instrumentation.appendToBootstrapClassLoaderSearch()` 注入。App CL 和 Bootstrap CL 各持有独立副本，通过反射同步关键字段。

## 理由

- 方案 A 无法工作：Bootstrap CL 的 JDK 类内联 advice 后，advice 引用的 `GlobalRouteState` 必须在 Bootstrap CL 可见
- 方案 C 的 retransform 对 `java.net.Socket` 等核心类风险过高
- 方案 B 虽然同步复杂，但已被验证可行（`BaafooAgent.syncGlobalRouteStateToBootstrapCL()` 等 5 个同步方法）

## 后果

- 新增被 Bootstrap CL 引用的类时必须加入 Bootstrap JAR
- 字段重命名/类型变更需同步更新反射代码
- 详见架构改进 P1-2（拆分 GlobalRouteState）
