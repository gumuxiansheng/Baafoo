package com.baafoo.testpulsar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Baafoo Pulsar 2.7.4 test application.
 *
 * <p>A focused, single-protocol test app that pins the Apache Pulsar client to
 * <b>2.7.4</b> — the Pulsar line that Tencent TDMQ for Pulsar is based on. It
 * exists alongside {@code baafoo-test-spring} (which pins pulsar-client
 * 2.10.4) so the Agent's {@code PulsarClientAdvice} and the
 * {@code baafoo-plugin-tdmq} redirect can be validated against both wire
 * protocols.</p>
 */
@SpringBootApplication
public class BaafooTestPulsarApplication {

    public static void main(String[] args) {
        SpringApplication.run(BaafooTestPulsarApplication.class, args);
    }
}
