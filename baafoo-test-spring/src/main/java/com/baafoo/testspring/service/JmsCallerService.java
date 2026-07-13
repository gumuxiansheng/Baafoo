package com.baafoo.testspring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.jms.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JmsCallerService {

    private static final Logger log = LoggerFactory.getLogger(JmsCallerService.class);

    public Map<String, Object> sendMessage(String brokerUrl, String queueName, String message,
                                           String username, String password) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("brokerUrl", brokerUrl);
        result.put("queueName", queueName);

        Connection connection = null;
        try {
            ConnectionFactory factory = new org.apache.activemq.ActiveMQConnectionFactory(brokerUrl);
            String actualUrl = ((org.apache.activemq.ActiveMQConnectionFactory) factory).getBrokerURL();
            result.put("actualBrokerUrl", actualUrl);
            result.put("intercepted", !actualUrl.equals(brokerUrl));

            connection = factory.createConnection(username, password);
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

    public Map<String, Object> receiveMessage(String brokerUrl, String queueName,
                                              String username, String password) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("brokerUrl", brokerUrl);
        result.put("queueName", queueName);

        Connection connection = null;
        try {
            ConnectionFactory factory = new org.apache.activemq.ActiveMQConnectionFactory(brokerUrl);
            String actualUrl = ((org.apache.activemq.ActiveMQConnectionFactory) factory).getBrokerURL();
            result.put("actualBrokerUrl", actualUrl);
            result.put("intercepted", !actualUrl.equals(brokerUrl));

            connection = factory.createConnection(username, password);
            connection.start();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination destination = session.createQueue(queueName);
            MessageConsumer consumer = session.createConsumer(destination);

            Message msg = consumer.receive(5000);
            if (msg == null) {
                result.put("success", true);
                result.put("count", 0);
                result.put("messages", java.util.Collections.emptyList());
            } else {
                List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
                Map<String, Object> msgMap = new LinkedHashMap<String, Object>();
                msgMap.put("jmsMessageId", msg.getJMSMessageID());
                msgMap.put("jmsType", msg.getJMSType());
                if (msg instanceof TextMessage) {
                    msgMap.put("text", ((TextMessage) msg).getText());
                }
                messages.add(msgMap);
                result.put("success", true);
                result.put("count", 1);
                result.put("messages", messages);
            }

            session.close();
            log.info("JMS received: queue={}", queueName);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("JMS receive failed: {}", e.getMessage());
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    public Map<String, Object> sendTopicMessage(String brokerUrl, String topicName, String message,
                                                 String username, String password) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("brokerUrl", brokerUrl);
        result.put("topicName", topicName);

        Connection connection = null;
        try {
            ConnectionFactory factory = new org.apache.activemq.ActiveMQConnectionFactory(brokerUrl);
            String actualUrl = ((org.apache.activemq.ActiveMQConnectionFactory) factory).getBrokerURL();
            result.put("actualBrokerUrl", actualUrl);
            result.put("intercepted", !actualUrl.equals(brokerUrl));

            connection = factory.createConnection(username, password);
            connection.start();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination destination = session.createTopic(topicName);
            MessageProducer producer = session.createProducer(destination);

            TextMessage textMessage = session.createTextMessage(message);
            producer.send(textMessage);

            result.put("success", true);
            result.put("jmsMessageId", textMessage.getJMSMessageID());
            result.put("destinationType", "topic");
            log.info("JMS topic message sent: topic={}, jmsMessageId={}", topicName, textMessage.getJMSMessageID());

            session.close();
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("JMS topic send failed: {}", e.getMessage());
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    public Map<String, Object> receiveTopicMessage(String brokerUrl, String topicName,
                                                    String username, String password) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("brokerUrl", brokerUrl);
        result.put("topicName", topicName);

        Connection connection = null;
        try {
            ConnectionFactory factory = new org.apache.activemq.ActiveMQConnectionFactory(brokerUrl);
            String actualUrl = ((org.apache.activemq.ActiveMQConnectionFactory) factory).getBrokerURL();
            result.put("actualBrokerUrl", actualUrl);
            result.put("intercepted", !actualUrl.equals(brokerUrl));

            connection = factory.createConnection(username, password);
            connection.start();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination destination = session.createTopic(topicName);
            MessageConsumer consumer = session.createConsumer(destination);

            Message msg = consumer.receive(5000);
            if (msg == null) {
                result.put("success", true);
                result.put("count", 0);
                result.put("messages", java.util.Collections.emptyList());
            } else {
                List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
                Map<String, Object> msgMap = new LinkedHashMap<String, Object>();
                msgMap.put("jmsMessageId", msg.getJMSMessageID());
                if (msg instanceof TextMessage) {
                    msgMap.put("text", ((TextMessage) msg).getText());
                }
                messages.add(msgMap);
                result.put("success", true);
                result.put("count", 1);
                result.put("messages", messages);
            }
            result.put("destinationType", "topic");

            session.close();
            log.info("JMS topic received: topic={}", topicName);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("JMS topic receive failed: {}", e.getMessage());
        } finally {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
        }
        return result;
    }
}
