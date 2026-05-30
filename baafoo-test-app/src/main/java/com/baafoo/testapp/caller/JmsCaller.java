package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

public class JmsCaller implements BaafooTestApp.Caller {

    private static final String BROKER_URL = "tcp://jms-broker:61616";
    private static final String QUEUE_NAME = "BAAFOO.TEST.QUEUE";

    @Override
    public String name() {
        return "JMS 外调测试 (目标: " + BROKER_URL + ")";
    }

    @Override
    public void run() throws Exception {
        testSendTextMessage();
        testSendWithProperties();
    }

    private void testSendTextMessage() throws Exception {
        System.out.println("  [发送TextMessage] queue=" + QUEUE_NAME);
        Connection connection = null;
        try {
            ConnectionFactory factory = new org.apache.activemq.ActiveMQConnectionFactory(BROKER_URL);
            connection = factory.createConnection();
            connection.start();
            System.out.println("    JMS Connection 创建成功");
            System.out.println("    Broker URL: " + BROKER_URL);

            boolean redirected = BROKER_URL.contains("9004") || BROKER_URL.contains("127.0.0.1:9004");
            System.out.println("    挡板拦截: " + (redirected ? "✓ 是 (连接被重定向)" : "✗ 否"));

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination destination = session.createQueue(QUEUE_NAME);
            MessageProducer producer = session.createProducer(destination);

            TextMessage message = session.createTextMessage("hello-baafoo-jms-test");
            producer.send(message);
            System.out.println("    发送成功: " + message.getJMSMessageID());

            session.close();
        } catch (Exception e) {
            System.out.println("    发送失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
        }
        System.out.println();
    }

    private void testSendWithProperties() throws Exception {
        System.out.println("  [发送消息+属性] queue=" + QUEUE_NAME);
        Connection connection = null;
        try {
            ConnectionFactory factory = new org.apache.activemq.ActiveMQConnectionFactory(BROKER_URL);
            connection = factory.createConnection();
            connection.start();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination destination = session.createQueue(QUEUE_NAME);
            MessageProducer producer = session.createProducer(destination);

            TextMessage message = session.createTextMessage("hello-baafoo-jms-with-props");
            message.setStringProperty("X-Baafoo-Test", "true");
            message.setStringProperty("X-Source", "baafoo-test-app");
            message.setJMSType("BaafooTest");
            producer.send(message);
            System.out.println("    发送成功: " + message.getJMSMessageID());

            session.close();
        } catch (Exception e) {
            System.out.println("    发送失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
        }
        System.out.println();
    }
}
