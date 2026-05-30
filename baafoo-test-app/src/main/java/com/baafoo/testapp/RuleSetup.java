package com.baafoo.testapp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RuleSetup {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverUrl;

    public RuleSetup(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public List<String> setupAll() {
        List<String> results = new ArrayList<String>();

        results.add(ensureEnvironment());
        results.add(createHttpRule());
        results.add(createTcpRule());
        results.add(createKafkaRule());
        results.add(createPulsarRule());
        results.add(createJmsRule());
        results.add(createConsulDnsRule());
        results.add(createConsulHttpRule());

        return results;
    }

    private String ensureEnvironment() {
        try {
            Map<String, Object> env = new LinkedHashMap<String, Object>();
            env.put("name", "test-env");
            env.put("mode", "stub");
            env.put("description", "Baafoo Test App 环境");

            String resp = doPost("/__baafoo__/api/environments", env);
            return "环境: test-env (stub) → " + (resp != null ? "OK" : "已存在");
        } catch (Exception e) {
            return "环境: test-env → 跳过 (" + e.getMessage() + ")";
        }
    }

    private String createHttpRule() {
        try {
            Map<String, Object> rule = buildRule(
                    "test-http-rule", "HTTP挡板规则", "http",
                    "httpbin.org", 80, null,
                    Collections.singletonList(pathCondition("startsWith", "/")),
                    Collections.singletonList(responseEntry("HTTP挡板响应", 200,
                            "{\"mocked\":true,\"protocol\":\"http\",\"message\":\"Baafoo HTTP stub\"}"))
            );
            String resp = doPost("/__baafoo__/api/rules", rule);
            return "HTTP 规则 → " + (resp != null ? "OK" : "FAIL");
        } catch (Exception e) {
            return "HTTP 规则 → 跳过 (" + e.getMessage() + ")";
        }
    }

    private String createTcpRule() {
        try {
            Map<String, Object> rule = buildRule(
                    "test-tcp-rule", "TCP挡板规则", "tcp",
                    "127.0.0.1", 9999, null,
                    Collections.emptyList(),
                    Collections.singletonList(responseEntry("TCP挡板响应", 200,
                            "BAFOO-TCP-STUB-OK"))
            );
            String resp = doPost("/__baafoo__/api/rules", rule);
            return "TCP 规则 → " + (resp != null ? "OK" : "FAIL");
        } catch (Exception e) {
            return "TCP 规则 → 跳过 (" + e.getMessage() + ")";
        }
    }

    private String createKafkaRule() {
        try {
            Map<String, Object> rule = buildRule(
                    "test-kafka-rule", "Kafka挡板规则", "kafka",
                    "kafka-broker", 9092, null,
                    Collections.emptyList(),
                    Collections.singletonList(responseEntry("Kafka挡板响应", 200,
                            "BAFOO-KAFKA-STUB-OK"))
            );
            String resp = doPost("/__baafoo__/api/rules", rule);
            return "Kafka 规则 → " + (resp != null ? "OK" : "FAIL");
        } catch (Exception e) {
            return "Kafka 规则 → 跳过 (" + e.getMessage() + ")";
        }
    }

    private String createPulsarRule() {
        try {
            Map<String, Object> rule = buildRule(
                    "test-pulsar-rule", "Pulsar挡板规则", "pulsar",
                    "pulsar-broker", 6650, null,
                    Collections.emptyList(),
                    Collections.singletonList(responseEntry("Pulsar挡板响应", 200,
                            "BAFOO-PULSAR-STUB-OK"))
            );
            String resp = doPost("/__baafoo__/api/rules", rule);
            return "Pulsar 规则 → " + (resp != null ? "OK" : "FAIL");
        } catch (Exception e) {
            return "Pulsar 规则 → 跳过 (" + e.getMessage() + ")";
        }
    }

    private String createJmsRule() {
        try {
            Map<String, Object> rule = buildRule(
                    "test-jms-rule", "JMS挡板规则", "jms",
                    "jms-broker", 61616, null,
                    Collections.emptyList(),
                    Collections.singletonList(responseEntry("JMS挡板响应", 200,
                            "BAFOO-JMS-STUB-OK"))
            );
            String resp = doPost("/__baafoo__/api/rules", rule);
            return "JMS 规则 → " + (resp != null ? "OK" : "FAIL");
        } catch (Exception e) {
            return "JMS 规则 → 跳过 (" + e.getMessage() + ")";
        }
    }

    private String createConsulDnsRule() {
        try {
            Map<String, Object> rule = buildRule(
                    "test-consul-dns-rule", "Consul DNS挡板规则", "http",
                    null, 0, "my-service.service.consul",
                    Collections.emptyList(),
                    Collections.singletonList(responseEntry("Consul DNS挡板响应", 200,
                            "{\"mocked\":true,\"protocol\":\"consul-dns\"}"))
            );
            String resp = doPost("/__baafoo__/api/rules", rule);
            return "Consul DNS 规则 → " + (resp != null ? "OK" : "FAIL");
        } catch (Exception e) {
            return "Consul DNS 规则 → 跳过 (" + e.getMessage() + ")";
        }
    }

    private String createConsulHttpRule() {
        try {
            Map<String, Object> rule = buildRule(
                    "test-consul-http-rule", "Consul HTTP挡板规则", "http",
                    "consul-server", 8500, null,
                    Collections.singletonList(pathCondition("startsWith", "/v1/")),
                    Collections.singletonList(responseEntry("Consul HTTP挡板响应", 200,
                            "{\"mocked\":true,\"protocol\":\"consul-http\"}"))
            );
            String resp = doPost("/__baafoo__/api/rules", rule);
            return "Consul HTTP 规则 → " + (resp != null ? "OK" : "FAIL");
        } catch (Exception e) {
            return "Consul HTTP 规则 → 跳过 (" + e.getMessage() + ")";
        }
    }

    private Map<String, Object> buildRule(String id, String name, String protocol,
                                           String host, int port, String serviceName,
                                           List<Map<String, Object>> conditions,
                                           List<Map<String, Object>> responses) {
        Map<String, Object> rule = new LinkedHashMap<String, Object>();
        rule.put("id", id);
        rule.put("name", name);
        rule.put("protocol", protocol);
        if (host != null) rule.put("host", host);
        if (port > 0) rule.put("port", port);
        if (serviceName != null) rule.put("serviceName", serviceName);
        rule.put("conditions", conditions);
        rule.put("responses", responses);
        rule.put("enabled", true);
        rule.put("priority", 100);
        rule.put("tags", Arrays.asList("test-app"));
        return rule;
    }

    private Map<String, Object> pathCondition(String operator, String value) {
        Map<String, Object> cond = new LinkedHashMap<String, Object>();
        cond.put("type", "path");
        cond.put("operator", operator);
        cond.put("value", value);
        return cond;
    }

    private Map<String, Object> responseEntry(String name, int statusCode, String body) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("name", name);
        entry.put("statusCode", statusCode);
        entry.put("body", body);
        entry.put("delayMs", 0);
        return entry;
    }

    private String doPost(String path, Map<String, Object> body) throws Exception {
        URL url = new URL(serverUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);

        byte[] data = MAPPER.writeValueAsBytes(body);
        OutputStream os = conn.getOutputStream();
        os.write(data);
        os.close();

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            return "OK";
        }
        return null;
    }
}
