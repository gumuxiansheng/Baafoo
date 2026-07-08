package com.baafoo.test.sca.consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer REST API - 对外暴露触发 Feign 调用的接口.
 */
@RestController
public class TestController {

    @Autowired
    private EchoClient echoClient;

    @Autowired
    private DiscoveryClient discoveryClient;

    @GetMapping("/echo-feign/{str}")
    public String feignEcho(@PathVariable String str) {
        return echoClient.echo(str);
    }

    @GetMapping("/divide-feign")
    public String feignDivide(@RequestParam Integer a, @RequestParam Integer b) {
        return echoClient.divide(a, b);
    }

    @GetMapping("/services")
    public Object services() {
        return discoveryClient.getServices();
    }

    @GetMapping("/services/{service}")
    public Object instances(@PathVariable String service) {
        return discoveryClient.getInstances(service);
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
