package com.baafoo.testspring.controller;

import com.baafoo.testspring.service.JmsCallerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/jms")
public class JmsCallerController {

    private final JmsCallerService jmsCallerService;

    public JmsCallerController(JmsCallerService jmsCallerService) {
        this.jmsCallerService = jmsCallerService;
    }

    @GetMapping("/send")
    public Map<String, Object> send(
            @RequestParam(defaultValue = "tcp://jms-broker:61616") String brokerUrl,
            @RequestParam(defaultValue = "BAAFOO.TEST.QUEUE") String queueName,
            @RequestParam(defaultValue = "hello-baafoo-jms") String message,
            @RequestParam(defaultValue = "baafoo") String username,
            @RequestParam(defaultValue = "baafoo") String password) {
        return jmsCallerService.sendMessage(brokerUrl, queueName, message, username, password);
    }

    @GetMapping("/receive")
    public Map<String, Object> receive(
            @RequestParam(defaultValue = "tcp://jms-broker:61616") String brokerUrl,
            @RequestParam(defaultValue = "BAAFOO.TEST.QUEUE") String queueName,
            @RequestParam(defaultValue = "baafoo") String username,
            @RequestParam(defaultValue = "baafoo") String password) {
        return jmsCallerService.receiveMessage(brokerUrl, queueName, username, password);
    }

    @GetMapping("/all")
    public Map<String, Object> sendAll() {
        Map<String, Object> results = new LinkedHashMap<String, Object>();
        results.put("simple", jmsCallerService.sendMessage(
                "tcp://jms-broker:61616", "BAAFOO.TEST.QUEUE", "hello-baafoo-jms", "baafoo", "baafoo"));
        results.put("receive", jmsCallerService.receiveMessage(
                "tcp://jms-broker:61616", "BAAFOO.TEST.QUEUE", "baafoo", "baafoo"));
        return results;
    }
}
