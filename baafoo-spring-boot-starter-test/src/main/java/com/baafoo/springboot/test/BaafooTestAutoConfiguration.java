package com.baafoo.springboot.test;

import com.baafoo.testcontainers.BaafooClient;
import com.baafoo.testcontainers.BaafooServerContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(BaafooServerContainer.class)
@AutoConfigureOrder
public class BaafooTestAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BaafooTestAutoConfiguration.class);

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public BaafooServerContainer baafooServerContainer() {
        log.info("Starting BaafooServerContainer for test...");
        BaafooServerContainer container = new BaafooServerContainer();
        container.start();
        log.info("BaafooServerContainer started at {}", container.getHttpBaseUrl());
        return container;
    }

    @Bean
    @ConditionalOnMissingBean
    public BaafooClient baafooClient(BaafooServerContainer container) {
        return container.getClient();
    }
}
