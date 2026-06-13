package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.Rule;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

public class KafkaProducerAdviceTest {

    @Before
    public void setup() {
        GlobalRouteState.ROUTES.clear();
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;
        RouteManager.setMode(EnvironmentMode.PASSTHROUGH);
        RouteManager.updateRules(Collections.<Rule>emptyList());
    }

    @Test
    public void testPassthroughModeDoesNotIntercept() {
        RouteManager.setMode(EnvironmentMode.PASSTHROUGH);
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;

        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "real-kafka:9092");

        // In passthrough mode, the advice should not modify the properties
        // We test this by calling the advice method directly
        Object[] args = { props };
        KafkaProducerAdvice.onConstructor(args);

        assertEquals("real-kafka:9092", props.getProperty("bootstrap.servers"));
    }

    @Test
    public void testStubModeReplacesBootstrapServers() {
        // Set up a matching rule for kafka
        Rule kafkaRule = new Rule();
        kafkaRule.setId("kafka-rule");
        kafkaRule.setName("kafka-stub");
        kafkaRule.setProtocol("kafka");
        kafkaRule.setHost("kafka-broker");
        kafkaRule.setEnabled(true);

        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        RouteManager.setMode(EnvironmentMode.STUB);
        RouteManager.updateRules(Arrays.asList(kafkaRule));

        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "real-kafka:9092");

        Object[] args = { props };
        KafkaProducerAdvice.onConstructor(args);

        assertEquals("127.0.0.1:9002", props.getProperty("bootstrap.servers"));
    }

    @Test
    public void testStubModeReplacesMapConfig() {
        // Set up a matching rule for kafka
        Rule kafkaRule = new Rule();
        kafkaRule.setId("kafka-rule");
        kafkaRule.setName("kafka-stub");
        kafkaRule.setProtocol("kafka");
        kafkaRule.setHost("kafka-broker");
        kafkaRule.setEnabled(true);

        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        RouteManager.setMode(EnvironmentMode.STUB);
        RouteManager.updateRules(Arrays.asList(kafkaRule));

        Map<String, Object> configs = new HashMap<String, Object>();
        configs.put("bootstrap.servers", "real-kafka:9092");

        Object[] args = { configs };
        KafkaProducerAdvice.onConstructor(args);

        assertEquals("127.0.0.1:9002", configs.get("bootstrap.servers"));
    }

    @Test
    public void testNoKafkaRouteDoesNotIntercept() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        RouteManager.setMode(EnvironmentMode.STUB);
        // No rule for kafka

        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "real-kafka:9092");

        Object[] args = { props };
        KafkaProducerAdvice.onConstructor(args);

        assertEquals("real-kafka:9092", props.getProperty("bootstrap.servers"));
    }

    @Test
    public void testNullArgsDoesNotThrow() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        RouteManager.setMode(EnvironmentMode.STUB);

        // Should not throw NPE
        KafkaProducerAdvice.onConstructor(null);
    }

    @Test
    public void testNonConfigArgsDoesNotThrow() {
        // Set up a matching rule for kafka
        Rule kafkaRule = new Rule();
        kafkaRule.setId("kafka-rule");
        kafkaRule.setName("kafka-stub");
        kafkaRule.setProtocol("kafka");
        kafkaRule.setHost("kafka-broker");
        kafkaRule.setEnabled(true);

        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        RouteManager.setMode(EnvironmentMode.STUB);
        RouteManager.updateRules(Arrays.asList(kafkaRule));

        Object[] args = { "not-a-config" };
        KafkaProducerAdvice.onConstructor(args);
        // Should not throw
    }
}
