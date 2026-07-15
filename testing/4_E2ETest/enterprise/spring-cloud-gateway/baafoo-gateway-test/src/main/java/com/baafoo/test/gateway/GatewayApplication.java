package com.baafoo.test.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Baafoo Spring Cloud Gateway - 企业级测试网关.
 * 替换不存在的 springcloud/spring-cloud-gateway:3.1.4 官方镜像，
 * 提供最小化的 Spring Cloud Gateway 运行时，路由配置见 application.yml。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
