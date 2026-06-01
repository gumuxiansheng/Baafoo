package com.baafoo.testspring.controller;

import com.baafoo.testspring.service.ExternalApiClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stub-demo")
public class StubDemoController {

    private final ExternalApiClient externalApiClient;

    public StubDemoController(ExternalApiClient externalApiClient) {
        this.externalApiClient = externalApiClient;
    }

    @GetMapping("/external")
    public String callExternal() {
        return externalApiClient.fetchData();
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
