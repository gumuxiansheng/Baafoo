package com.baafoo.testspring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JmsCallerService {

    private static final Logger log = LoggerFactory.getLogger(JmsCallerService.class);

    public Map<String, Object> sendMessage(String brokerUrl, String queueName, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("brokerUrl", brokerUrl);
        result.put("queueName", queueName);

        Connection connection = null;
        try {
            ConnectionFactory factory = new org.apache.activemq.ActiveMQConnectionFactory(brokerUrl);
            String actualUrl = ((org.apache.activemq.ActiveMQConnectionFactory) factory).getBrokerURL();
            result.put("actualBrokerUrl", actualUrl);
            result.put("intercepted", !actualUrl.equals(brokerUrl));

            connection = factory.createConnection();
            connection.start();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination destination = session.createQueue(queueName);
            MessageProducer producer = session.createProducer(destination);

            TextMessage textMessage = session.createTextMessage(message);
            producer.send(textMessage);

            result.put("success", true);
            result.put("jmsMessageId", textMessage.getJMSMessageID());
            log.info("JMS message sent: queue={}, jmsMessageId={}", queueName, textMessage.getJMSMessageID());

            session.close();
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("JMS send failed: {}", e.getMessage());
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
        }
        return result;
    }
}
