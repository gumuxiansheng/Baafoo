package com.baafoo.springboot.test;

import com.baafoo.testcontainers.BaafooClient;
import com.baafoo.testcontainers.BaafooServerContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class BaafooTestAutoConfigurationTest {

    @Configuration
    static class TestConfig {
    }

    @Test
    void testAutoConfigurationCreatesBeans() {
        assumeTrue(isDockerAvailable(), "Docker must be available");

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TestConfig.class);
            ctx.register(BaafooTestAutoConfiguration.class);
            ctx.refresh();

            BaafooServerContainer container = ctx.getBean(BaafooServerContainer.class);
            assertNotNull(container, "BaafooServerContainer bean should exist");

            BaafooClient client = ctx.getBean(BaafooClient.class);
            assertNotNull(client, "BaafooClient bean should exist");

            container.stop();
        }
    }

    @Test
    void testConditionalOnMissingBean() {
        assumeTrue(isDockerAvailable(), "Docker must be available");

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(CustomContainerConfig.class);
            ctx.register(BaafooTestAutoConfiguration.class);
            ctx.refresh();

            BaafooServerContainer container = ctx.getBean(BaafooServerContainer.class);
            assertNotNull(container, "Custom container bean should exist");
            assertNotNull(ctx.getBean(BaafooClient.class),
                    "BaafooClient should be auto-configured from the existing container");

            container.stop();
        }
    }

    @Configuration
    static class CustomContainerConfig {
        @Bean
        public BaafooServerContainer baafooServerContainer() {
            BaafooServerContainer container = new BaafooServerContainer();
            container.start();
            return container;
        }
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
