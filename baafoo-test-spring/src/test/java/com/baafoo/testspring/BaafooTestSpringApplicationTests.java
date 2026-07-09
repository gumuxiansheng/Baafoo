package com.baafoo.testspring;

import com.baafoo.testspring.controller.FeignCallerController;
import com.baafoo.testspring.controller.HttpCallerController;
import com.baafoo.testspring.controller.KafkaCallerController;
import com.baafoo.testspring.controller.PulsarCallerController;
import com.baafoo.testspring.controller.SocketCallerController;
import com.baafoo.testspring.service.FeignCallerService;
import com.baafoo.testspring.service.HttpCallerService;
import com.baafoo.testspring.service.PulsarCallerService;
import org.apache.pulsar.client.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Spring Boot integration tests for the Baafoo test-spring sample app.
 *
 * <p>Pulsar interactions are mocked via {@code @SpyBean} on {@link PulsarCallerService}:
 * the spy overrides {@code createPulsarClient(...)} to return a mock
 * {@link PulsarClient}, whose chained {@code newProducer}/{@code newConsumer}
 * builders are stubbed to return mock {@link Producer}/{@link Consumer}. This
 * prevents real TCP connections to {@code localhost:0} in CICD environments
 * where no broker is available — previously the Pulsar client logged
 * "Connection refused: localhost/127.0.0.1:0" WARN stack traces on every test,
 * which polluted CICD reports and triggered network-monitor alerts.</p>
 */
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

    @SpyBean
    private PulsarCallerService pulsarCallerService;

    @SpyBean
    private FeignCallerService feignCallerService;

    @SpyBean
    private HttpCallerService httpCallerService;

    @BeforeEach
    void stubPulsarClient() throws Exception {
        PulsarClient mockClient = mock(PulsarClient.class);
        Producer<String> mockProducer = mock(Producer.class);
        Consumer<String> mockConsumer = mock(Consumer.class);

        // Producer chain: client.newProducer(schema).topic(t).create() -> producer
        @SuppressWarnings("unchecked")
        ProducerBuilder<String> mockProducerBuilder = mock(ProducerBuilder.class);
        when(mockProducerBuilder.topic(anyString())).thenReturn(mockProducerBuilder);
        when(mockProducerBuilder.create()).thenReturn(mockProducer);
        when(mockClient.newProducer(Schema.STRING)).thenReturn(mockProducerBuilder);

        // Producer.send(msg) -> MessageId.latest (a stable non-null id)
        when(mockProducer.send(anyString())).thenReturn(MessageId.latest);

        // Consumer chain: client.newConsumer(schema).topic(t).subscriptionName(s).subscribe()
        @SuppressWarnings("unchecked")
        ConsumerBuilder<String> mockConsumerBuilder = mock(ConsumerBuilder.class);
        when(mockConsumerBuilder.topic(anyString())).thenReturn(mockConsumerBuilder);
        when(mockConsumerBuilder.subscriptionName(anyString())).thenReturn(mockConsumerBuilder);
        when(mockConsumerBuilder.subscribe()).thenReturn(mockConsumer);
        when(mockClient.newConsumer(Schema.STRING)).thenReturn(mockConsumerBuilder);

        // Consumer.receive returns null (no messages) so the service's "no message"
        // branch is exercised; this matches the CICD behaviour where the broker is absent.
        when(mockConsumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(null);

        // SpyBean seam: replace real client construction with the mock
        doReturn(mockClient).when(pulsarCallerService).createPulsarClient(anyString());

        // Stub Feign calls to avoid external httpbin.org dependency in CICD
        Map<String, Object> feignGetResult = new LinkedHashMap<String, Object>();
        feignGetResult.put("statusCode", 200);
        feignGetResult.put("stubbed", false);
        feignGetResult.put("ruleId", null);
        feignGetResult.put("body", "{}");
        doReturn(feignGetResult).when(feignCallerService).callViaFeign(anyString());

        Map<String, Object> feignPostResult = new LinkedHashMap<String, Object>();
        feignPostResult.put("statusCode", 200);
        feignPostResult.put("stubbed", false);
        feignPostResult.put("ruleId", null);
        feignPostResult.put("body", "{}");
        doReturn(feignPostResult).when(feignCallerService).callViaFeignPost(anyString(), anyString());

        // Stub HttpCallerService calls to avoid external httpbin.org dependency in CICD
        Map<String, Object> httpGetResult = new LinkedHashMap<String, Object>();
        httpGetResult.put("statusCode", 200);
        httpGetResult.put("stubbed", false);
        httpGetResult.put("ruleId", null);
        httpGetResult.put("body", "{}");
        doReturn(httpGetResult).when(httpCallerService).doGet(anyString());

        Map<String, Object> httpPostResult = new LinkedHashMap<String, Object>();
        httpPostResult.put("statusCode", 200);
        httpPostResult.put("stubbed", false);
        httpPostResult.put("ruleId", null);
        httpPostResult.put("body", "{}");
        doReturn(httpPostResult).when(httpCallerService).doPost(anyString(), anyString());

        Map<String, Object> httpPutResult = new LinkedHashMap<String, Object>();
        httpPutResult.put("statusCode", 200);
        httpPutResult.put("stubbed", false);
        httpPutResult.put("ruleId", null);
        httpPutResult.put("body", "{}");
        doReturn(httpPutResult).when(httpCallerService).doPut(anyString(), anyString());

        Map<String, Object> httpDeleteResult = new LinkedHashMap<String, Object>();
        httpDeleteResult.put("statusCode", 200);
        httpDeleteResult.put("stubbed", false);
        httpDeleteResult.put("ruleId", null);
        httpDeleteResult.put("body", "{}");
        doReturn(httpDeleteResult).when(httpCallerService).doDelete(anyString());
    }

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
                "http://localhost:" + port + "/api/http/get?url=http://localhost:" + port + "/get",
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
                "http://localhost:" + port + "/api/feign/get?baseUrl=http://localhost:" + port,
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
