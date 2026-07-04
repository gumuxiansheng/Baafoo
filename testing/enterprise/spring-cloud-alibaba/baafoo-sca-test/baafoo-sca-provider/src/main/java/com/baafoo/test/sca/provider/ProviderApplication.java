package com.baafoo.test.sca.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Baafoo SCA Provider - 服务提供方.
 * 通过 Nacos Discovery 注册到 Nacos Server，对外提供 Echo / Divide 等 REST API。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
    }
}
