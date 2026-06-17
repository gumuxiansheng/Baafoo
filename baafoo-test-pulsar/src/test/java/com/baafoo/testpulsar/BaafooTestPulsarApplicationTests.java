package com.baafoo.testpulsar;

import com.baafoo.testpulsar.controller.Pulsar274Controller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@code baafoo-test-pulsar}.
 *
 * <p>Run with {@link SpringBootTest.WebEnvironment#RANDOM_PORT} so they need no
 * external broker. The send/consume/json/batch endpoints are exercised with a
 * deliberately unreachable {@code serviceUrl} so the underlying Pulsar client
 * fails fast and returns an error map — mirroring the
 * {@code baafoo-test-spring} test strategy. The assertions only check that the
 * endpoints return the expected keys, not that they actually connect.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BaafooTestPulsarApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Pulsar274Controller pulsar274Controller;

    @Test
    void contextLoads() {
        assertThat(pulsar274Controller).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void infoEndpointReportsExpectedVersion() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/pulsar274/info", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("module")).isEqualTo("baafoo-test-pulsar");
        assertThat(result.get("expectedVersion")).isEqualTo("2.7.4");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendEndpointReturnsResultMap() {
        // serviceUrl uses an invalid scheme so the client fails instantly;
        // we only assert the endpoint returns the request echo keys.
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port
                        + "/api/pulsar274/send?serviceUrl=slow://localhost:0000&topic=t&message=m",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("serviceUrl")).isTrue();
        assertThat(result.containsKey("topic")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void consumeEndpointReturnsResultMap() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port
                        + "/api/pulsar274/consume?serviceUrl=slow://localhost:0000&topic=t",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("serviceUrl")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonEndpointReturnsResultMap() {
        // serviceUrl is unreachable, so the call fails fast into the catch
        // branch and only echoes serviceUrl/topic (+ error). The "schema" key
        // is only set on the success path, so we assert the always-present
        // keys here — consistent with the other endpoint tests.
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port
                        + "/api/pulsar274/json?serviceUrl=slow://localhost:0000&topic=t&name=n&value=1",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("serviceUrl")).isTrue();
        assertThat(result.containsKey("topic")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchEndpointReturnsResultMap() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port
                        + "/api/pulsar274/batch?serviceUrl=slow://localhost:0000&topic=t&count=2",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("serviceUrl")).isTrue();
        assertThat(result.get("batchSize")).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void allEndpointReturnsAggregatedResults() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/pulsar274/all", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("send")).isTrue();
        assertThat(result.containsKey("consume")).isTrue();
        assertThat(result.containsKey("json")).isTrue();
        assertThat(result.containsKey("batch")).isTrue();
        assertThat(result.containsKey("info")).isTrue();
    }
}
