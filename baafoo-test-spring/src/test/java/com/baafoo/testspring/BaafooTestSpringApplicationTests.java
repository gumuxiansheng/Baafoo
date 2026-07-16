package com.baafoo.testspring;

import com.baafoo.testspring.controller.BackendEchoController;
import com.baafoo.testspring.controller.FeignCallerController;
import com.baafoo.testspring.controller.GrpcCallerController;
import com.baafoo.testspring.controller.HttpCallerController;
import com.baafoo.testspring.controller.JmsCallerController;
import com.baafoo.testspring.controller.KafkaCallerController;
import com.baafoo.testspring.controller.PulsarCallerController;
import com.baafoo.testspring.controller.SocketCallerController;
import com.baafoo.testspring.controller.StubDemoController;
import com.baafoo.testspring.service.ExternalApiClient;
import com.baafoo.testspring.service.FeignCallerService;
import com.baafoo.testspring.service.GrpcCallerService;
import com.baafoo.testspring.service.HttpCallerService;
import com.baafoo.testspring.service.JmsCallerService;
import com.baafoo.testspring.service.PulsarCallerService;
import org.apache.pulsar.client.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Autowired
    private JmsCallerController jmsCallerController;

    @Autowired
    private GrpcCallerController grpcCallerController;

    @Autowired
    private BackendEchoController backendEchoController;

    @Autowired
    private StubDemoController stubDemoController;

    @SpyBean
    private PulsarCallerService pulsarCallerService;

    @SpyBean
    private FeignCallerService feignCallerService;

    @SpyBean
    private HttpCallerService httpCallerService;

    @SpyBean
    private JmsCallerService jmsCallerService;

    @SpyBean
    private GrpcCallerService grpcCallerService;

    @SpyBean
    private ExternalApiClient externalApiClient;

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

    /**
     * Mock the JMS caller, gRPC caller and external API client so the
     * additional controller scenarios can run hermetically in CICD without a
     * real ActiveMQ / gRPC server / backend host. Mirrors the
     * {@code stubPulsarClient()} seam above.
     */
    @BeforeEach
    void stubJmsGrpcAndExternal() throws Exception {
        // ---- JMS: avoid a real ActiveMQ broker connection ----
        Map<String, Object> jmsSend = new LinkedHashMap<String, Object>();
        jmsSend.put("success", true);
        jmsSend.put("brokerUrl", "tcp://jms-broker:61616");
        jmsSend.put("queueName", "BAAFOO.TEST.QUEUE");
        jmsSend.put("jmsMessageId", "ID:mock");
        jmsSend.put("intercepted", false);
        doReturn(jmsSend).when(jmsCallerService)
                .sendMessage(anyString(), anyString(), anyString(), anyString(), anyString());

        Map<String, Object> jmsReceive = new LinkedHashMap<String, Object>();
        jmsReceive.put("success", true);
        jmsReceive.put("count", 0);
        jmsReceive.put("messages", Collections.emptyList());
        doReturn(jmsReceive).when(jmsCallerService)
                .receiveMessage(anyString(), anyString(), anyString(), anyString());

        Map<String, Object> jmsSendTopic = new LinkedHashMap<String, Object>();
        jmsSendTopic.put("success", true);
        jmsSendTopic.put("destinationType", "topic");
        jmsSendTopic.put("jmsMessageId", "ID:mock-topic");
        doReturn(jmsSendTopic).when(jmsCallerService)
                .sendTopicMessage(anyString(), anyString(), anyString(), anyString(), anyString());

        Map<String, Object> jmsReceiveTopic = new LinkedHashMap<String, Object>();
        jmsReceiveTopic.put("success", true);
        jmsReceiveTopic.put("destinationType", "topic");
        jmsReceiveTopic.put("count", 0);
        jmsReceiveTopic.put("messages", Collections.emptyList());
        doReturn(jmsReceiveTopic).when(jmsCallerService)
                .receiveTopicMessage(anyString(), anyString(), anyString(), anyString());

        // ---- gRPC: avoid a real gRPC server (greeter/stream example.com:50051) ----
        Map<String, Object> grpcOk = new LinkedHashMap<String, Object>();
        grpcOk.put("completed", true);
        grpcOk.put("grpcStatus", "0");
        grpcOk.put("grpcMessage", null);
        grpcOk.put("error", null);
        grpcOk.put("messages", Arrays.asList("hello mocked"));
        grpcOk.put("latencyMs", 1);
        // Generic unary stub covers greeter / slow / status-test / delay-test.
        doReturn(grpcOk).when(grpcCallerService)
                .callUnary(anyString(), anyString(), anyString(), anyInt(), anyString());

        // Specific unary stub for the /error endpoint (grpc-error rule -> status 5).
        Map<String, Object> grpcErr = new LinkedHashMap<String, Object>();
        grpcErr.put("completed", true);
        grpcErr.put("grpcStatus", "5");
        grpcErr.put("grpcMessage", "NOT_FOUND");
        grpcErr.put("error", null);
        grpcErr.put("messages", Collections.emptyList());
        grpcErr.put("latencyMs", 1);
        doReturn(grpcErr).when(grpcCallerService)
                .callUnary(eq("helloworld.Greeter"), eq("GetUser"), anyString(), anyInt(), anyString());

        Map<String, Object> grpcStream = new LinkedHashMap<String, Object>();
        grpcStream.put("completed", true);
        grpcStream.put("grpcStatus", "0");
        grpcStream.put("grpcMessage", null);
        grpcStream.put("error", null);
        grpcStream.put("messages", Arrays.asList("e1", "e2"));
        grpcStream.put("latencyMs", 1);
        doReturn(grpcStream).when(grpcCallerService)
                .callServerStreaming(anyString(), anyString(), anyString(), anyInt(), anyString());
        doReturn(grpcStream).when(grpcCallerService)
                .callClientStreaming(anyString(), anyString(), anyString(), anyInt(), anyList());
        doReturn(grpcStream).when(grpcCallerService)
                .callBidi(anyString(), anyString(), anyString(), anyInt(), anyList());

        // ---- ExternalApiClient (StubDemo): avoid real httpbin/real-backend ----
        doReturn("{\"mocked\":true}").when(externalApiClient).fetchData();
    }

    @Test
    void contextLoads() {
        assertThat(httpCallerController).isNotNull();
        assertThat(socketCallerController).isNotNull();
        assertThat(feignCallerController).isNotNull();
        assertThat(kafkaCallerController).isNotNull();
        assertThat(pulsarCallerController).isNotNull();
        assertThat(jmsCallerController).isNotNull();
        assertThat(grpcCallerController).isNotNull();
        assertThat(backendEchoController).isNotNull();
        assertThat(stubDemoController).isNotNull();
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

    // ==================== JMS (previously untested controller) ====================

    @SuppressWarnings("unchecked")
    @Test
    void jmsSendEndpointReturnsResult() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/jms/send", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("success")).isTrue();
        assertThat(result.containsKey("brokerUrl")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void jmsReceiveEndpointReturnsResult() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/jms/receive", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("count")).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void jmsTopicEndpointsReturnResult() {
        Map<String, Object> send = restTemplate.getForObject(
                "http://localhost:" + port + "/api/jms/send-topic", Map.class);
        assertThat(send).isNotNull();
        assertThat(send.get("destinationType")).isEqualTo("topic");

        Map<String, Object> receive = restTemplate.getForObject(
                "http://localhost:" + port + "/api/jms/receive-topic", Map.class);
        assertThat(receive).isNotNull();
        assertThat(receive.get("destinationType")).isEqualTo("topic");
    }

    @SuppressWarnings("unchecked")
    @Test
    void jmsAllEndpointReturnsBoth() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/jms/all", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("simple")).isTrue();
        assertThat(result.containsKey("receive")).isTrue();
    }

    // ==================== gRPC (previously untested controller) ====================

    @SuppressWarnings("unchecked")
    @Test
    void grpcGreeterEndpointReturnsResult() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/grpc/greeter", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.containsKey("completed")).isTrue();
        assertThat(result.containsKey("grpcStatus")).isTrue();
        assertThat(result.get("grpcStatus")).isEqualTo("0");
    }

    @SuppressWarnings("unchecked")
    @Test
    void grpcErrorEndpointReturnsErrorStatus() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/api/grpc/error", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("grpcStatus")).isEqualTo("5");
    }

    @SuppressWarnings("unchecked")
    @Test
    void grpcStreamingEndpointsReturnResults() {
        Map<String, Object> server = restTemplate.getForObject(
                "http://localhost:" + port + "/api/grpc/server-stream", Map.class);
        assertThat(server).isNotNull();
        assertThat(server.containsKey("messages")).isTrue();

        Map<String, Object> client = restTemplate.getForObject(
                "http://localhost:" + port + "/api/grpc/client-stream", Map.class);
        assertThat(client).isNotNull();
        assertThat(client.containsKey("messages")).isTrue();

        Map<String, Object> bidi = restTemplate.getForObject(
                "http://localhost:" + port + "/api/grpc/bidi", Map.class);
        assertThat(bidi).isNotNull();
        assertThat(bidi.containsKey("messages")).isTrue();
    }

    // ==================== BackendEcho (previously untested controller) ====================

    @SuppressWarnings("unchecked")
    @Test
    void backendEchoReturnsRealBackendMarker() {
        Map<String, Object> result = restTemplate.getForObject(
                "http://localhost:" + port + "/backend-echo-probe", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("realBackend")).isEqualTo(true);
        assertThat(result.get("method")).isEqualTo("GET");
        assertThat(result.get("path")).isEqualTo("/backend-echo-probe");
    }

    @SuppressWarnings("unchecked")
    @Test
    void backendEchoPostReturnsBody() {
        Map<String, Object> result = restTemplate.postForObject(
                "http://localhost:" + port + "/backend-echo-post", "hello-body", Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("realBackend")).isEqualTo(true);
        assertThat(result.get("body")).isEqualTo("hello-body");
    }

    // ==================== StubDemo (previously untested controller) ====================

    @Test
    void stubDemoHealthReturnsOk() {
        String response = restTemplate.getForObject(
                "http://localhost:" + port + "/api/stub-demo/health", String.class);
        assertThat(response).isEqualTo("OK");
    }

    @Test
    void stubDemoExternalReturnsFetchedData() {
        String response = restTemplate.getForObject(
                "http://localhost:" + port + "/api/stub-demo/external", String.class);
        assertThat(response).isNotNull();
        assertThat(response).contains("mocked");
    }
}
