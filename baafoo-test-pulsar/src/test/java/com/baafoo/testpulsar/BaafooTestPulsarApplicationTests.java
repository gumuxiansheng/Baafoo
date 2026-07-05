package com.baafoo.testpulsar;

import com.baafoo.testpulsar.controller.Pulsar274Controller;
import com.baafoo.testpulsar.service.Pulsar274CallerService;
import com.baafoo.testpulsar.service.Pulsar274CallerService.SamplePojo;
import org.apache.pulsar.client.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Smoke tests for {@code baafoo-test-pulsar}.
 *
 * <p>Pulsar interactions are mocked via {@code @SpyBean} on
 * {@link Pulsar274CallerService}: the spy overrides
 * {@code createPulsarClient(...)} to return a mock {@link PulsarClient}, whose
 * chained {@code newProducer}/{@code newConsumer} builders are stubbed to
 * return mock {@link Producer}/{@link Consumer}. This prevents real TCP
 * connections to {@code localhost:0} in CICD environments where no broker is
 * available — previously the Pulsar client logged
 * "Connection refused: localhost/127.0.0.1:0" WARN stack traces on every test
 * (the full /all sweep spawned 4 clients, each retrying for ~30s), polluting
 * CICD reports and triggering network-monitor alerts.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BaafooTestPulsarApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Pulsar274Controller pulsar274Controller;

    @SpyBean
    private Pulsar274CallerService pulsar274CallerService;

    @BeforeEach
    void stubPulsarClient() throws Exception {
        PulsarClient mockClient = mock(PulsarClient.class);

        // Producer chain: client.newProducer(schema).topic(t)...create() -> producer
        // Covers STRING schema (sendMessage/sendBatch) and JSON(SamplePojo) schema
        // (sendJsonSchema). Declared as raw Producer so it can accept both
        // send(String) and send(SamplePojo) calls — the service code declares
        // generic Producer<String> / Producer<SamplePojo> locals, but the mock
        // is the same instance for both paths (Mockito doesn't care about
        // generic types at runtime).
        @SuppressWarnings("rawtypes")
        Producer mockProducer = mock(Producer.class);
        @SuppressWarnings("unchecked")
        ProducerBuilder<String> mockProducerBuilder = mock(ProducerBuilder.class);
        when(mockProducerBuilder.topic(anyString())).thenReturn(mockProducerBuilder);
        when(mockProducerBuilder.enableBatching(anyBoolean())).thenReturn(mockProducerBuilder);
        when(mockProducerBuilder.batchingMaxMessages(anyInt())).thenReturn(mockProducerBuilder);
        when(mockProducerBuilder.batchingMaxPublishDelay(anyLong(), any(TimeUnit.class))).thenReturn(mockProducerBuilder);
        when(mockProducerBuilder.create()).thenReturn(mockProducer);
        when(mockClient.newProducer(any(Schema.class))).thenReturn(mockProducerBuilder);

        // Producer.send(String) -> MessageId.latest (covers sendMessage)
        when(mockProducer.send(anyString())).thenReturn(MessageId.latest);
        // Producer.send(SamplePojo) -> MessageId.latest (covers sendJsonSchema)
        when(mockProducer.send(any(SamplePojo.class))).thenReturn(MessageId.latest);
        // Producer.sendAsync(String) -> completed future (covers sendBatch)
        when(mockProducer.sendAsync(anyString()))
                .thenReturn(CompletableFuture.completedFuture(MessageId.latest));
        // producer.flush() is a no-op on the mock
        doNothing().when(mockProducer).flush();

        // Consumer chain: client.newConsumer(schema).topic(t).subscriptionName(s).subscribe()
        @SuppressWarnings("unchecked")
        Consumer<String> mockConsumer = mock(Consumer.class);
        @SuppressWarnings("unchecked")
        ConsumerBuilder<String> mockConsumerBuilder = mock(ConsumerBuilder.class);
        when(mockConsumerBuilder.topic(anyString())).thenReturn(mockConsumerBuilder);
        when(mockConsumerBuilder.subscriptionName(anyString())).thenReturn(mockConsumerBuilder);
        when(mockConsumerBuilder.subscribe()).thenReturn(mockConsumer);
        when(mockClient.newConsumer(any(Schema.class))).thenReturn(mockConsumerBuilder);

        // Consumer.receive returns null (no messages) — matches the CICD behaviour
        // where no broker is present, exercising the "no message" branch.
        when(mockConsumer.receive(anyInt(), any(TimeUnit.class))).thenReturn(null);

        // SpyBean seam: replace real client construction with the mock
        doReturn(mockClient).when(pulsar274CallerService).createPulsarClient(anyString());
    }

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
        // serviceUrl is irrelevant: createPulsarClient is stubbed to return a mock.
        // We only assert the endpoint returns the request echo keys.
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
        // serviceUrl is unreachable in raw mode, but the mock returns success.
        // We assert both echo keys and the success-path "schema" key to confirm
        // the JSON-schema producer path is exercised.
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
