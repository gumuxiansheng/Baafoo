package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;

import java.util.concurrent.TimeUnit;

public class PulsarCaller implements BaafooTestApp.Caller {

    private static final String SERVICE_URL = "pulsar://pulsar-broker:6650";
    private static final String TOPIC = "persistent://public/default/baafoo-test-topic";

    @Override
    public String name() {
        return "Pulsar 外调测试 (目标: " + SERVICE_URL + ")";
    }

    @Override
    public void run() throws Exception {
        testProduce();
        testProduceWithKey();
    }

    private void testProduce() throws Exception {
        System.out.println("  [发送消息] topic=" + TOPIC);
        PulsarClient client = null;
        try {
            client = PulsarClient.builder()
                    .serviceUrl(SERVICE_URL)
                    .connectionTimeout(5, TimeUnit.SECONDS)
                    .operationTimeout(5, TimeUnit.SECONDS)
                    .build();
            System.out.println("    PulsarClient 创建成功");
            System.out.println("    serviceUrl: " + SERVICE_URL);

            boolean redirected = SERVICE_URL.contains("9003") || SERVICE_URL.contains("127.0.0.1:9003");
            System.out.println("    挡板拦截: " + (redirected ? "✓ 是 (serviceUrl 已被替换)" : "✗ 否"));

            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(TOPIC)
                    .create();

            org.apache.pulsar.client.api.MessageId msgId = producer.send("hello-baafoo-pulsar-test");
            System.out.println("    发送成功: messageId=" + msgId);

            producer.close();
        } catch (Exception e) {
            System.out.println("    发送失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            if (client != null) client.close();
        }
        System.out.println();
    }

    private void testProduceWithKey() throws Exception {
        System.out.println("  [发送消息+Key] topic=" + TOPIC);
        PulsarClient client = null;
        try {
            client = PulsarClient.builder()
                    .serviceUrl(SERVICE_URL)
                    .connectionTimeout(5, TimeUnit.SECONDS)
                    .operationTimeout(5, TimeUnit.SECONDS)
                    .build();

            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(TOPIC)
                    .create();

            org.apache.pulsar.client.api.MessageId msgId = producer.newMessage()
                    .key("test-key")
                    .value("hello-baafoo-pulsar-with-key")
                    .send();
            System.out.println("    发送成功: key=test-key messageId=" + msgId);

            producer.close();
        } catch (Exception e) {
            System.out.println("    发送失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            if (client != null) client.close();
        }
        System.out.println();
    }
}
