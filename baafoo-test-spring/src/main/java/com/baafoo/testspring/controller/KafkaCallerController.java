package com.baafoo.testspring.controller;

import com.baafoo.testspring.service.KafkaCallerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/kafka")
public class KafkaCallerController {

    private final KafkaCallerService kafkaCallerService;

    public KafkaCallerController(KafkaCallerService kafkaCallerService) {
        this.kafkaCallerService = kafkaCallerService;
    }

    @GetMapping("/send")
    public Map<String, Object> send(
            @RequestParam(defaultValue = "kafka-broker:9092") String bootstrapServers,
            @RequestParam(defaultValue = "baafoo-test-topic") String topic,
            @RequestParam(defaultValue = "hello-baafoo-kafka") String message) {
        return kafkaCallerService.sendMessage(bootstrapServers, topic, message);
    }

    @GetMapping("/consume")
    public Map<String, Object> consume(
            @RequestParam(defaultValue = "kafka-broker:9092") String bootstrapServers,
            @RequestParam(defaultValue = "baafoo-test-topic") String topic) {
        return kafkaCallerService.consumeMessage(bootstrapServers, topic);
    }

    @GetMapping("/all")
    public Map<String, Object> sendAll() {
        Map<String, Object> results = new LinkedHashMap<String, Object>();
        results.put("simple", kafkaCallerService.sendMessage(
                "kafka-broker:9092", "baafoo-test-topic", "hello-baafoo-kafka"));
        results.put("withKey", kafkaCallerService.sendMessage(
                "kafka-broker:9092", "baafoo-test-topic-keyed", "hello-baafoo-kafka-keyed"));
        results.put("consume", kafkaCallerService.consumeMessage(
                "kafka-broker:9092", "baafoo-test-topic"));
        return results;
    }
}
