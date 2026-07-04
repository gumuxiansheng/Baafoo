package com.baafoo.test.sca.provider;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider REST API - 模拟 PetClinic 风格的后端服务.
 */
@RestController
public class EchoController {

    @GetMapping("/echo/{str}")
    public String echo(@PathVariable String str) {
        return "hello Nacos Discovery " + str;
    }

    @GetMapping("/divide")
    public String divide(@RequestParam Integer a, @RequestParam Integer b) {
        if (b == 0) {
            return "0";
        }
        return String.valueOf(a / b);
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
