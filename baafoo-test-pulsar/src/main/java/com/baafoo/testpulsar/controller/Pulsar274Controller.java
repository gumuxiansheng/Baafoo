package com.baafoo.testpulsar.controller;

import com.baafoo.testpulsar.service.Pulsar274CallerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoints driving {@link Pulsar274CallerService}.
 *
 * <p>Defaults deliberately match {@code baafoo-test-spring}'s
 * {@code PulsarCallerController} ({@code pulsar://pulsar-broker:6650},
 * {@code persistent://public/default/baafoo-test-topic}) so the Agent
 * {@code PulsarClientAdvice} rewrites both apps' connections to the same
 * {@code pulsar://&lt;host&gt;:9003} target, and so the
 * {@code baafoo-example-plugins/tdmq} redirect (-> 9005) is exercised identically.</p>
 */
@RestController
@RequestMapping("/api/pulsar274")
public class Pulsar274Controller {

    private static final String DEFAULT_SERVICE_URL = "pulsar://pulsar-broker:6650";
    private static final String DEFAULT_TOPIC = "persistent://public/default/baafoo-test-topic";
    private static final String DEFAULT_MESSAGE = "hello-baafoo-pulsar-2.7.4";

    private final Pulsar274CallerService service;

    public Pulsar274Controller(Pulsar274CallerService service) {
        this.service = service;
    }

    @GetMapping("/send")
    public Map<String, Object> send(
            @RequestParam(defaultValue = DEFAULT_SERVICE_URL) String serviceUrl,
            @RequestParam(defaultValue = DEFAULT_TOPIC) String topic,
            @RequestParam(defaultValue = DEFAULT_MESSAGE) String message) {
        return service.sendMessage(serviceUrl, topic, message);
    }

    @GetMapping("/consume")
    public Map<String, Object> consume(
            @RequestParam(defaultValue = DEFAULT_SERVICE_URL) String serviceUrl,
            @RequestParam(defaultValue = DEFAULT_TOPIC) String topic) {
        return service.consumeMessage(serviceUrl, topic);
    }

    @GetMapping("/json")
    public Map<String, Object> json(
            @RequestParam(defaultValue = DEFAULT_SERVICE_URL) String serviceUrl,
            @RequestParam(defaultValue = DEFAULT_TOPIC) String topic,
            @RequestParam(defaultValue = "baafoo") String name,
            @RequestParam(defaultValue = "42") int value) {
        return service.sendJsonSchema(serviceUrl, topic, name, value);
    }

    @GetMapping("/batch")
    public Map<String, Object> batch(
            @RequestParam(defaultValue = DEFAULT_SERVICE_URL) String serviceUrl,
            @RequestParam(defaultValue = DEFAULT_TOPIC) String topic,
            @RequestParam(defaultValue = "3") int count) {
        if (count < 1) count = 1;
        if (count > 1000) count = 1000;
        return service.sendBatch(serviceUrl, topic, count);
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return service.info();
    }

    @GetMapping("/all")
    public Map<String, Object> all() {
        Map<String, Object> results = new LinkedHashMap<String, Object>();
        results.put("send", service.sendMessage(DEFAULT_SERVICE_URL, DEFAULT_TOPIC, DEFAULT_MESSAGE));
        results.put("consume", service.consumeMessage(DEFAULT_SERVICE_URL, DEFAULT_TOPIC));
        results.put("json", service.sendJsonSchema(DEFAULT_SERVICE_URL, DEFAULT_TOPIC, "baafoo", 42));
        results.put("batch", service.sendBatch(DEFAULT_SERVICE_URL, DEFAULT_TOPIC, 3));
        results.put("info", service.info());
        return results;
    }
}
