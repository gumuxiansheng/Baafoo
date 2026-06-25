package com.baafoo.testspring.controller;

import com.baafoo.testspring.service.PulsarCallerService;
import com.baafoo.testspring.service.TdmqCallerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/pulsar")
public class PulsarCallerController {

    private final PulsarCallerService pulsarCallerService;
    private final TdmqCallerService tdmqCallerService;

    public PulsarCallerController(PulsarCallerService pulsarCallerService,
                                   TdmqCallerService tdmqCallerService) {
        this.pulsarCallerService = pulsarCallerService;
        this.tdmqCallerService = tdmqCallerService;
    }

    @GetMapping("/send")
    public Map<String, Object> send(
            @RequestParam(defaultValue = "pulsar://pulsar-broker:6650") String serviceUrl,
            @RequestParam(defaultValue = "persistent://public/default/baafoo-test-topic") String topic,
            @RequestParam(defaultValue = "hello-baafoo-pulsar") String message) {
        return pulsarCallerService.sendMessage(serviceUrl, topic, message);
    }

    @GetMapping("/consume")
    public Map<String, Object> consume(
            @RequestParam(defaultValue = "pulsar://pulsar-broker:6650") String serviceUrl,
            @RequestParam(defaultValue = "persistent://public/default/baafoo-test-topic") String topic) {
        return pulsarCallerService.consumeMessage(serviceUrl, topic);
    }

    @GetMapping("/tdmq")
    public Map<String, Object> sendTdmq(
            @RequestParam(defaultValue = "pulsar://pulsar-tdmq.dev:6650") String serviceUrl,
            @RequestParam(defaultValue = "persistent://public/default/baafoo-test-topic") String topic,
            @RequestParam(defaultValue = "hello-baafoo-tdmq") String message) {
        return tdmqCallerService.sendMessage(serviceUrl, topic, message);
    }

    @GetMapping("/tdmq-config")
    public Map<String, Object> tdmqConfig() {
        return tdmqCallerService.checkConfig();
    }

    @GetMapping("/all")
    public Map<String, Object> all(
            @RequestParam(defaultValue = "pulsar://pulsar-broker:6650") String serviceUrl,
            @RequestParam(defaultValue = "persistent://public/default/baafoo-test-topic") String topic,
            @RequestParam(defaultValue = "hello-baafoo-pulsar") String message) {
        Map<String, Object> results = new LinkedHashMap<String, Object>();
        results.put("pulsar", pulsarCallerService.sendMessage(serviceUrl, topic, message));
        results.put("consume", pulsarCallerService.consumeMessage(serviceUrl, topic));
        results.put("tdmqInfo", tdmqCallerService.checkConfig());
        return results;
    }
}
