package com.baafoo.testspring;

import com.baafoo.testspring.controller.FeignCallerController;
import com.baafoo.testspring.controller.HttpCallerController;
import com.baafoo.testspring.controller.KafkaCallerController;
import com.baafoo.testspring.controller.PulsarCallerController;
import com.baafoo.testspring.controller.SocketCallerController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BaafooTestSpringApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private HttpCallerController httpCallerController;

    @Autowired
    private SocketCallerController socketCallerController;

    @Autowired
    private FeignCallerController feignCallerController;

    @Autowired
    private KafkaCallerController kafkaCallerController;

    @Autowired
    private PulsarCallerController pulsarCallerController;

    @Test
    void contextLoads() {
        assertThat(httpCallerController).isNotNull();
        assertThat(socketCallerController).isNotNull();
        assertThat(feignCallerController).isNotNull();
        assertThat(kafkaCallerController).isNotNull();
        assertThat(pulsarCallerController).isNotNull();
    }

    @Test
    void healthEndpointShouldReturnOk() {
        String response = restTemplate.getForObject(
                "http://localhost:" + port + "/api/http/health", String.class);
        assertThat(response).isEqualTo("OK");
    }

    @SuppressWarnings("unchecked")
    @Test
    void httpGetEndpointReturnsResultMap() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/http/get?url=http://httpbin.org/get",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("statusCode")).isTrue();
        assertThat(result.containsKey("stubbed")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void httpMethodsEndpointReturnsAllMethods() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/http/methods",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("get")).isTrue();
        assertThat(result.containsKey("post")).isTrue();
        assertThat(result.containsKey("put")).isTrue();
        assertThat(result.containsKey("delete")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void socketBioEndpointReturnsResult() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/socket/bio",
                Map.class);
        assertThat(result).isNotNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void socketNioEndpointReturnsResult() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/socket/nio",
                Map.class);
        assertThat(result).isNotNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void socketAllEndpointReturnsBoth() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/socket/all",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("bio")).isTrue();
        assertThat(result.containsKey("nio")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void feignGetEndpointReturnsResult() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/feign/get?baseUrl=http://httpbin.org",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("statusCode")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void feignAllEndpointReturnsMethods() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/feign/all",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("get")).isTrue();
        assertThat(result.containsKey("post")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void kafkaSendEndpointReturnsResult() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/kafka/send",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("bootstrapServers")).isTrue();
        assertThat(result.containsKey("topic")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void kafkaAllEndpointReturnsResults() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/kafka/all",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("simple")).isTrue();
        assertThat(result.containsKey("withKey")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void pulsarSendEndpointReturnsResult() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/pulsar/send?serviceUrl=slow://localhost:0000&topic=test&message=test",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("serviceUrl")).isTrue();
        assertThat(result.containsKey("topic")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void pulsarTdmqEndpointReturnsResult() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/pulsar/tdmq",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("tdmqCompatible")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void pulsarTdmqConfigEndpointReturnsConfig() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/pulsar/tdmq-config",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("protocol")).isEqualTo("Pulsar Binary Protocol (compatible with TDMQ)");
    }

    @SuppressWarnings("unchecked")
    @Test
    void pulsarAllEndpointReturnsAll() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/pulsar/all?serviceUrl=slow://localhost:0000&topic=test&message=test",
                Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("pulsar")).isTrue();
        assertThat(result.containsKey("tdmqInfo")).isTrue();
        Map<String, Object> pulsarResult = (Map<String, Object>) result.get("pulsar");
        assertThat(pulsarResult.containsKey("serviceUrl")).isTrue();
    }
}
