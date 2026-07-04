package com.baafoo.test.sca.consumer;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Provider 的 Feign 客户端 - 通过 Nacos 服务发现调用 service-provider.
 */
@FeignClient(name = "service-provider", contextId = "service-provider")
public interface EchoClient {

    @GetMapping("/echo/{str}")
    String echo(@PathVariable("str") String str);

    @GetMapping("/divide")
    String divide(@RequestParam("a") Integer a, @RequestParam("b") Integer b);
}
