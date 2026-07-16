# R-A6: JMS ConnectionFactory 拦截

> 实施时间: 2026-06-13
> PRD 需求: R-A6 AC-01/AC-02

## 需求描述

拦截 `javax.jms.ConnectionFactory#createConnection()` 方法，将返回的 Connection 对象替换为指向 Baafoo Server 内嵌 JMS Broker 的代理对象。

## 实施策略

JMS 是一个 API 规范，不同厂商有不同的 ConnectionFactory 实现。最常见的是 Apache ActiveMQ 的 `ActiveMQConnectionFactory`。

拦截策略：拦截 `ActiveMQConnectionFactory(String brokerURL)` 构造函数，将 brokerURL 参数替换为 Baafoo JMS Mock Broker 地址（`tcp://SERVER_HOST:JMS_PORT`）。这样创建的 Connection 自然指向 Baafoo 的 JMS Broker。

## 实施内容

1. 新增 `JmsConnectionFactoryAdvice` 类：
   - 检查环境模式（非 passthrough 才拦截）
   - 检查路由表是否有 jms 规则
   - 替换构造函数的第一个参数（brokerURL）为 `tcp://SERVER_HOST:JMS_PORT`
2. 在 `BaafooAgent.installTransforms()` 中注册：
   - `org.apache.activemq.ActiveMQConnectionFactory`
   - `org.apache.activemq.ActiveMQXAConnectionFactory`

## 修改文件

- `baafoo-agent/src/main/java/com/baafoo/agent/advice/JmsConnectionFactoryAdvice.java` — 新增
- `baafoo-agent/src/main/java/com/baafoo/agent/BaafooAgent.java` — 注册 JMS 拦截
