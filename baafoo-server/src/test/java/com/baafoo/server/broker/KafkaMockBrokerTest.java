package com.baafoo.server.broker;

import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.MqRelationship;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.server.broker.codec.KafkaCodecUtils;
import com.baafoo.server.broker.codec.KafkaFlexibleCodec;
import com.baafoo.server.storage.StorageService;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the Kafka Mock Broker.
 * Uses real Netty channels to test the Kafka binary protocol handling.
 */
public class KafkaMockBrokerTest {

    private static final int TEST_PORT = 19092;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private KafkaMessageStore messageStore;
    private Channel serverChannel;
    private StorageService storage;

    @Before
    public void setUp() throws Exception {
        storage = mock(StorageService.class);
        when(storage.listRules()).thenReturn(new ArrayList<>());
        when(storage.listEnvironments()).thenReturn(new ArrayList<>());
        when(storage.listAgents()).thenReturn(new ArrayList<>());

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        messageStore = new KafkaMessageStore();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                100 * 1024 * 1024, 0, 4, 0, 4));
                        ch.pipeline().addLast(new KafkaProtocolDecoder(messageStore, storage, TEST_PORT, null));
                    }
                });

        serverChannel = b.bind(TEST_PORT).sync().channel();
    }

    @After
    public void tearDown() {
        if (serverChannel != null) serverChannel.close();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    @Test
    public void testApiVersionsRequest() throws Exception {
        ByteBuf response = sendRequest(buildApiVersionsRequest(1));
        assertNotNull("Should receive a response", response);
        assertTrue("Response should have data", response.readableBytes() > 4);

        // Read correlation ID
        int correlationId = response.readInt();
        assertEquals("Correlation ID should match", 1, correlationId);

        // Read error code
        short errorCode = response.readShort();
        assertEquals("Error code should be 0", 0, errorCode);
    }

    @Test
    public void testMetadataRequest() throws Exception {
        ByteBuf response = sendRequest(buildMetadataRequest("test-topic"));
        assertNotNull("Should receive a response", response);

        int correlationId = response.readInt();
        assertEquals(1, correlationId);

        // throttle_time_ms (v3+)
        response.readInt();

        // Brokers array
        int brokerCount = response.readInt();
        assertEquals("Should have 1 broker", 1, brokerCount);

        // Read broker: broker_id, host, port, rack (matching server output order)
        int brokerId = response.readInt();
        String host = readNullableString(response);
        int port = response.readInt();
        assertEquals("Broker port should match", TEST_PORT, port);
        // rack (v1+)
        readNullableString(response);

        // cluster_id (v2+)
        readNullableString(response);

        // controller_id (v1+)
        response.readInt();

        // Topic metadata array
        int topicCount = response.readInt();
        assertEquals("Should have 1 topic", 1, topicCount);

        short topicError = response.readShort();
        assertEquals("Topic error code should be 0", 0, topicError);

        String topicName = readNullableString(response);
        assertEquals("test-topic", topicName);
    }

    @Test
    public void testProduceAndFetch() throws Exception {
        // First produce a message
        ByteBuf produceResponse = sendRequest(buildProduceRequest("test-topic", 0, "key1", "value1"));
        assertNotNull("Should receive produce response", produceResponse);

        int correlationId = produceResponse.readInt();
        assertEquals(1, correlationId);

        // Topics array (server writes topics BEFORE throttle_time_ms)
        int topicCount = produceResponse.readInt();
        assertEquals(1, topicCount);

        String topic = readNullableString(produceResponse);
        assertEquals("test-topic", topic);

        int partitionCount = produceResponse.readInt();
        assertEquals(1, partitionCount);

        // Server writes partition_index BEFORE error_code
        int partition = produceResponse.readInt();
        assertEquals(0, partition);

        short errorCode = produceResponse.readShort();
        assertEquals("Produce error code should be 0", 0, errorCode);

        long offset = produceResponse.readLong();
        assertTrue("Offset should be >= 0", offset >= 0);

        // log_append_time_ms (v2+)
        produceResponse.readLong();

        // throttle_time_ms (v1+, comes AFTER topics in server output)
        produceResponse.readInt();

        // Now fetch the message
        ByteBuf fetchResponse = sendRequest(buildFetchRequest("test-topic", 0, 0));
        assertNotNull("Should receive fetch response", fetchResponse);

        int fetchCorrelationId = fetchResponse.readInt();
        assertEquals(2, fetchCorrelationId);

        // throttle_time_ms
        fetchResponse.readInt();

        // Topics array
        int fetchTopicCount = fetchResponse.readInt();
        assertEquals(1, fetchTopicCount);

        String fetchTopic = readNullableString(fetchResponse);
        assertEquals("test-topic", fetchTopic);

        int fetchPartitionCount = fetchResponse.readInt();
        assertEquals(1, fetchPartitionCount);

        short fetchError = fetchResponse.readShort();
        assertEquals("Fetch error code should be 0", 0, fetchError);

        int fetchPartition = fetchResponse.readInt();
        assertEquals(0, fetchPartition);

        long highWatermark = fetchResponse.readLong();
        assertTrue("High watermark should be > 0", highWatermark > 0);
    }

    // ----------------------------------------------------------------------
    // Rule matching / recording / stub-injection tests
    // ----------------------------------------------------------------------

    /**
     * A kafka rule with a {@code topic} condition must stub incoming produces:
     * the produced record's value is replaced by the rule's response body in
     * the store (default mode is STUB).
     */
    @Test
    public void testProduceMatchesTopicRuleAndInjectsStub() throws Exception {
        Rule rule = new Rule();
        rule.setId("rule-stub-1");
        rule.setProtocol("kafka");
        rule.setEnabled(true);
        MatchCondition cond = MatchCondition.topic("equals", "order-events");
        rule.setConditions(Collections.singletonList(cond));
        ResponseEntry resp = new ResponseEntry();
        resp.setBody("STUB-RESPONSE");
        rule.setResponses(Collections.singletonList(resp));

        when(storage.listRules()).thenReturn(Collections.singletonList(rule));
        when(storage.listEnvironments()).thenReturn(new ArrayList<>());

        // Produce a real payload to order-events
        ByteBuf produceResponse = sendRequest(buildProduceRequest("order-events", 0, "k1", "real-payload"));
        consumeProduceResponse(produceResponse);

        // The store should hold the STUB body, not the real payload.
        List<KafkaMessageStore.StoredMessage> stored =
                messageStore.fetch("order-events", 0, 0, 1024 * 1024);
        assertEquals("Should store 1 message", 1, stored.size());
        String storedValue = stored.get(0).value != null
                ? new String(stored.get(0).value, StandardCharsets.UTF_8) : null;
        assertEquals("Stored value should be the stub body", "STUB-RESPONSE", storedValue);
    }

    /**
     * A {@code bodyContains} condition matches on the decoded record payload.
     */
    @Test
    public void testProduceMatchesBodyContainsCondition() throws Exception {
        Rule rule = new Rule();
        rule.setId("rule-body-1");
        rule.setProtocol("kafka");
        rule.setEnabled(true);
        MatchCondition cond = new MatchCondition();
        cond.setType("bodyContains");
        cond.setValue("VIP");
        rule.setConditions(Collections.singletonList(cond));
        ResponseEntry resp = new ResponseEntry();
        resp.setBody("VIP-STUB");
        rule.setResponses(Collections.singletonList(resp));

        when(storage.listRules()).thenReturn(Collections.singletonList(rule));

        // Producing a body containing "VIP" should stub it.
        ByteBuf r1 = sendRequest(buildProduceRequest("any-topic", 0, null, "{\"user\":\"VIP-carl\"}"));
        consumeProduceResponse(r1);
        List<KafkaMessageStore.StoredMessage> s1 =
                messageStore.fetch("any-topic", 0, 0, 1024 * 1024);
        assertEquals(1, s1.size());
        assertEquals("VIP-STUB", new String(s1.get(0).value, StandardCharsets.UTF_8));

        // Producing a body WITHOUT "VIP" should NOT stub — original payload stored.
        messageStore.clear();
        ByteBuf r2 = sendRequest(buildProduceRequest("any-topic", 0, null, "plain-message"));
        consumeProduceResponse(r2);
        List<KafkaMessageStore.StoredMessage> s2 =
                messageStore.fetch("any-topic", 0, 0, 1024 * 1024);
        assertEquals(1, s2.size());
        assertEquals("plain-message", new String(s2.get(0).value, StandardCharsets.UTF_8));
    }

    /**
     * RECORD mode must persist a RecordingEntry with the decoded payload
     * (not the raw batch bytes) and the matched rule id.
     */
    @Test
    public void testProduceRecordsWithRuleIdAndDecodedPayload() throws Exception {
        Rule rule = new Rule();
        rule.setId("rule-rec-1");
        rule.setProtocol("kafka");
        rule.setEnabled(true);
        rule.setConditions(Collections.singletonList(MatchCondition.topic("equals", "rec-topic")));
        rule.setResponses(Collections.singletonList(resp("RECORDED")));

        when(storage.listRules()).thenReturn(Collections.singletonList(rule));
        // RECORD mode → storage.addRecording must be invoked.
        com.baafoo.core.model.Environment recEnv =
                new com.baafoo.core.model.Environment();
        recEnv.setName("default");
        recEnv.setMode(EnvironmentMode.RECORD);
        when(storage.listEnvironments()).thenReturn(Collections.singletonList(recEnv));

        ByteBuf r = sendRequest(buildProduceRequest("rec-topic", 0, "k", "hello-payload"));
        consumeProduceResponse(r);

        // Verify a recording was added with the decoded payload + rule id.
        verify(storage, atLeastOnce()).addRecording(
                org.mockito.ArgumentMatchers.argThat(entry ->
                        "rule-rec-1".equals(entry.getRuleId())
                                && "kafka".equals(entry.getProtocol())
                                && "rec-topic".equals(entry.getPath())
                                && "hello-payload".equals(entry.getRequestBody())));
    }

    /**
     * A flexible-version request (ApiVersions v3) uses Request Header v2
     * (compact string + tag buffer). The decoder must correctly parse it
     * using isFlexible() routing.
     */
    @Test
    public void testCompactStringClientIdDoesNotCrash() throws Exception {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(18); // API key: ApiVersions
        buf.writeShort(3);  // API version: v3 (flexible)
        buf.writeInt(99);   // correlation ID
        // compact-string clientId "c": length 2 (= 1 + 1), then byte 'c'
        buf.writeByte(2);
        buf.writeByte('c');
        // empty tag buffer (1 byte: 0x00)
        buf.writeByte(0);
        // ApiVersions v3 request body: compact string for client software name + version + tag buffer
        // client software name (compact not-nullable string): "test"
        buf.writeByte(5); // length = 4 + 1 = 5
        buf.writeBytes("test".getBytes());
        // client software version (compact not-nullable string): "1.0"
        buf.writeByte(4); // length = 3 + 1 = 4
        buf.writeBytes("1.0".getBytes());
        // empty tag buffer
        buf.writeByte(0);

        ByteBuf resp = sendRequest(frameRequest(buf));
        assertNotNull("Should not crash on flexible header", resp);
        int correlationId = resp.readInt();
        assertEquals("Correlation id echoed despite flexible header", 99, correlationId);
        // v3 response: error_code (i16)
        short errorCode = resp.readShort();
        assertEquals("ApiVersions v3 should succeed", 0, errorCode);
    }

    // helper: consume & assert a successful produce response (single topic/partition)
    private void consumeProduceResponse(ByteBuf produceResponse) {
        assertNotNull(produceResponse);
        produceResponse.readInt();      // correlation id
        produceResponse.readInt();      // topic count
        readNullableString(produceResponse); // topic
        produceResponse.readInt();      // partition count
        produceResponse.readInt();      // partition index
        // error code, base_offset, log_append_time(v2+), throttle(v1+)
    }

    private static ResponseEntry resp(String body) {
        ResponseEntry r = new ResponseEntry();
        r.setBody(body);
        return r;
    }


    @Test
    public void testFindCoordinatorReturnsBroker() throws Exception {
        ByteBuf response = sendRequest(buildFindCoordinatorRequest("test-group"));
        assertNotNull(response);

        int correlationId = response.readInt();
        assertEquals(1, correlationId);

        // throttle_time_ms (v1+)
        response.readInt();

        short errorCode = response.readShort();
        assertEquals("FindCoordinator error code should be 0", 0, errorCode);

        // error_message (v1+)
        readNullableString(response);

        int nodeId = response.readInt();
        assertEquals("Node ID should be 0", 0, nodeId);

        String host = readNullableString(response);
        assertNotNull("Host should not be null", host);

        int port = response.readInt();
        assertEquals("Port should match", TEST_PORT, port);
    }

    @Test
    public void testHeartbeatReturnsSuccess() throws Exception {
        ByteBuf response = sendRequest(buildHeartbeatRequest());
        assertNotNull(response);

        int correlationId = response.readInt();
        assertEquals(1, correlationId);

        response.readInt(); // throttle_time_ms
        short errorCode = response.readShort();
        assertEquals("Heartbeat error code should be 0", 0, errorCode);
    }

    @Test
    public void testOffsetCommitReturnsEmpty() throws Exception {
        ByteBuf response = sendRequest(buildOffsetCommitRequest());
        assertNotNull(response);

        int correlationId = response.readInt();
        assertEquals(1, correlationId);

        // Should not throw, just return data
        assertTrue("Response should have data", response.readableBytes() > 0);
    }

    @Test
    public void testMessageStoreAppendAndFetch() {
        long offset1 = messageStore.append("topic1", 0, "key1".getBytes(StandardCharsets.UTF_8), "value1".getBytes(StandardCharsets.UTF_8));
        assertEquals(0L, offset1);

        long offset2 = messageStore.append("topic1", 0, "key2".getBytes(StandardCharsets.UTF_8), "value2".getBytes(StandardCharsets.UTF_8));
        assertEquals(1L, offset2);

        List<KafkaMessageStore.StoredMessage> messages = messageStore.fetch("topic1", 0, 0, 1024 * 1024);
        assertEquals(2, messages.size());
        assertEquals(0L, messages.get(0).offset);
        assertEquals(1L, messages.get(1).offset);
    }

    @Test
    public void testMessageStoreFetchFromOffset() {
        messageStore.append("topic1", 0, null, "v0".getBytes(StandardCharsets.UTF_8));
        messageStore.append("topic1", 0, null, "v1".getBytes(StandardCharsets.UTF_8));
        messageStore.append("topic1", 0, null, "v2".getBytes(StandardCharsets.UTF_8));

        List<KafkaMessageStore.StoredMessage> messages = messageStore.fetch("topic1", 0, 1, 1024 * 1024);
        assertEquals(2, messages.size());
        assertEquals(1L, messages.get(0).offset);
        assertEquals(2L, messages.get(1).offset);
    }

    @Test
    public void testMessageStoreEmptyTopic() {
        List<KafkaMessageStore.StoredMessage> messages = messageStore.fetch("nonexistent", 0, 0, 1024);
        assertTrue("Should return empty list for nonexistent topic", messages.isEmpty());
    }

    @Test
    public void testMessageStorePresetMessages() {
        List<KafkaMessageStore.PresetMessage> presets = new ArrayList<>();
        presets.add(new KafkaMessageStore.PresetMessage("pk1", "pv1"));
        presets.add(new KafkaMessageStore.PresetMessage("pk2", "pv2"));
        messageStore.setPresetMessages("preset-topic", 0, presets);

        List<KafkaMessageStore.StoredMessage> messages = messageStore.fetch("preset-topic", 0, 0, 1024 * 1024);
        assertEquals(2, messages.size());
        assertEquals("pk1", new String(messages.get(0).key, StandardCharsets.UTF_8));
        assertEquals("pv1", new String(messages.get(0).value, StandardCharsets.UTF_8));
    }

    @Test
    public void testMessageStoreClear() {
        messageStore.append("topic1", 0, null, "v0".getBytes(StandardCharsets.UTF_8));
        assertFalse(messageStore.fetch("topic1", 0, 0, 1024).isEmpty());

        messageStore.clear();
        assertTrue(messageStore.fetch("topic1", 0, 0, 1024).isEmpty());
    }

    // ----------------------------------------------------------------------
    // MQ Relationship derivation tests
    // ----------------------------------------------------------------------

    /**
     * A configured MQ relationship derives a downstream Kafka message from
     * an upstream produce, applying key/value templates.
     */
    @Test
    public void testMqRelationshipDerivesMessage() throws Exception {
        MqRelationship rel = new MqRelationship();
        rel.setId("rel-1");
        rel.setFromProtocol("kafka");
        rel.setFromTopic("source-topic");
        rel.setToProtocol("kafka");
        rel.setToTopic("target-topic");
        rel.setKeyTemplate("derived-{{key}}");
        rel.setValueTemplate("from-{{topic}}:{{request.body}}");
        rel.setDelayMs(0);
        rel.setEnabled(true);

        when(storage.listMqRelationshipsByFrom("kafka", "source-topic"))
                .thenReturn(Collections.singletonList(rel));

        ByteBuf r = sendRequest(buildProduceRequest("source-topic", 0, "k1", "hello"));
        consumeProduceResponse(r);

        List<KafkaMessageStore.StoredMessage> derived =
                messageStore.fetch("target-topic", 0, 0, 1024 * 1024);
        assertEquals("Should derive 1 message", 1, derived.size());
        assertEquals("derived-k1",
                new String(derived.get(0).key, StandardCharsets.UTF_8));
        assertEquals("from-source-topic:hello",
                new String(derived.get(0).value, StandardCharsets.UTF_8));
    }

    /**
     * Relationships with a non-zero delay schedule the derived message.
     */
    @Test
    public void testMqRelationshipDelayedDerivation() throws Exception {
        MqRelationship rel = new MqRelationship();
        rel.setId("rel-delay");
        rel.setFromProtocol("kafka");
        rel.setFromTopic("source-topic");
        rel.setToProtocol("kafka");
        rel.setToTopic("target-topic");
        rel.setValueTemplate("delayed-body");
        rel.setDelayMs(300);
        rel.setEnabled(true);

        when(storage.listMqRelationshipsByFrom("kafka", "source-topic"))
                .thenReturn(Collections.singletonList(rel));

        ByteBuf r = sendRequest(buildProduceRequest("source-topic", 0, null, "hello"));
        consumeProduceResponse(r);

        // Immediately after produce, the delayed message should not yet exist.
        assertTrue("Delayed message should not exist immediately",
                messageStore.fetch("target-topic", 0, 0, 1024).isEmpty());

        // Wait for the scheduled task to run.
        Thread.sleep(600);

        List<KafkaMessageStore.StoredMessage> derived =
                messageStore.fetch("target-topic", 0, 0, 1024 * 1024);
        assertEquals("Delayed message should be derived", 1, derived.size());
        assertEquals("delayed-body",
                new String(derived.get(0).value, StandardCharsets.UTF_8));
    }

    /**
     * Relationships whose target protocol is not kafka must be ignored.
     */
    @Test
    public void testMqRelationshipIgnoresDifferentProtocol() throws Exception {
        MqRelationship rel = new MqRelationship();
        rel.setId("rel-pulsar");
        rel.setFromProtocol("kafka");
        rel.setFromTopic("source-topic");
        rel.setToProtocol("pulsar");
        rel.setToTopic("target-topic");
        rel.setValueTemplate("pulsar-body");
        rel.setDelayMs(0);
        rel.setEnabled(true);

        when(storage.listMqRelationshipsByFrom("kafka", "source-topic"))
                .thenReturn(Collections.singletonList(rel));

        ByteBuf r = sendRequest(buildProduceRequest("source-topic", 0, null, "hello"));
        consumeProduceResponse(r);

        assertTrue("Non-kafka target protocol should be ignored",
                messageStore.fetch("target-topic", 0, 0, 1024).isEmpty());
    }

    /**
     * Disabled relationships must not produce derived messages.
     */
    @Test
    public void testMqRelationshipDisabledNotDerived() throws Exception {
        MqRelationship rel = new MqRelationship();
        rel.setId("rel-disabled");
        rel.setFromProtocol("kafka");
        rel.setFromTopic("source-topic");
        rel.setToProtocol("kafka");
        rel.setToTopic("target-topic");
        rel.setValueTemplate("should-not-appear");
        rel.setDelayMs(0);
        rel.setEnabled(false);

        when(storage.listMqRelationshipsByFrom("kafka", "source-topic"))
                .thenReturn(Collections.singletonList(rel));

        ByteBuf r = sendRequest(buildProduceRequest("source-topic", 0, null, "hello"));
        consumeProduceResponse(r);

        assertTrue("Disabled relationship should not derive messages",
                messageStore.fetch("target-topic", 0, 0, 1024).isEmpty());
    }

    // ----------------------------------------------------------------------
    // Protocol version upgrade tests (Phase 1: Produce v8 / Fetch v11)
    // ----------------------------------------------------------------------

    /**
     * ApiVersions response must advertise the upgraded caps:
     * Produce v8, Fetch v11, Metadata v8, ApiVersions v2 (KIP-511 gate).
     */
    @Test
    public void testApiVersionsReturnsUpgradedCaps() throws Exception {
        ByteBuf response = sendRequest(buildApiVersionsRequest(1));
        assertNotNull(response);
        response.readInt(); // correlationId
        short errorCode = response.readShort();
        assertEquals("ApiVersions error code should be 0", 0, errorCode);

        int apiCount = response.readInt();
        short produceMax = -1, fetchMax = -1, metadataMax = -1, apiVersionsMax = -1;
        for (int i = 0; i < apiCount; i++) {
            short apiKey = response.readShort();
            response.readShort(); // minVersion
            short maxVer = response.readShort();
            switch (apiKey) {
                case 0:  produceMax = maxVer; break;
                case 1:  fetchMax = maxVer; break;
                case 3:  metadataMax = maxVer; break;
                case 18: apiVersionsMax = maxVer; break;
                default: break;
            }
        }
        assertEquals("Produce max should be v9 (flexible)", 9, produceMax);
        assertEquals("Fetch max should be v12 (flexible)", 12, fetchMax);
        assertEquals("Metadata max should be v9 (flexible)", 9, metadataMax);
        assertEquals("ApiVersions max should be v3 (flexible, KIP-511)", 3, apiVersionsMax);
    }

    /**
     * Produce v8 request (non-flexible max) must be accepted and return
     * a response with log_append_time (v2+) and log_start_offset (v5+).
     */
    @Test
    public void testProduceV8() throws Exception {
        ByteBuf response = sendRequest(buildProduceRequestV8("produce-v8-topic", 0, "k", "v8-payload"));
        assertNotNull(response);
        response.readInt(); // correlationId

        int topicCount = response.readInt();
        assertEquals(1, topicCount);
        String topic = readNullableString(response);
        assertEquals("produce-v8-topic", topic);

        int partitionCount = response.readInt();
        assertEquals(1, partitionCount);
        response.readInt(); // partition index
        short errorCode = response.readShort();
        assertEquals("Produce v8 error code should be 0", 0, errorCode);
        long offset = response.readLong(); // base_offset
        assertTrue("Offset should be >= 0", offset >= 0);
        response.readLong(); // log_append_time_ms (v2+)
        response.readLong(); // log_start_offset (v5+)
        response.readInt();  // throttle_time_ms (v1+)
    }

    /**
     * Fetch v8 adds current_leader_epoch (INT32) and rack_id (NULLABLE_STRING)
     * per partition. The handler must parse them without leaving leftover bytes.
     */
    @Test
    public void testFetchV8() throws Exception {
        // Seed a message first
        sendRequest(buildProduceRequestV8("fetch-v8-topic", 0, null, "v8-data"));

        ByteBuf response = sendRequest(buildFetchRequestV8("fetch-v8-topic", 0, 0));
        assertNotNull(response);
        response.readInt(); // correlationId
        response.readInt(); // throttle_time_ms (v1+)
        response.readShort(); // error_code (v7+)
        response.readInt();  // session_id (v7+)

        int topicCount = response.readInt();
        assertEquals(1, topicCount);
        String topic = readNullableString(response);
        assertEquals("fetch-v8-topic", topic);
        // v8 < v10, so no topic_id UUID in response
        int partitionCount = response.readInt();
        assertEquals(1, partitionCount);

        response.readShort(); // error_code
        response.readInt();   // partition
        long highWatermark = response.readLong();
        assertTrue("High watermark should be > 0", highWatermark > 0);
    }

    /**
     * Fetch v10 adds ZStd compression support only — no structural changes from v9.
     * The request includes CurrentLeaderEpoch (v9+) per partition.
     * TopicId is v13+ in both request and response, so it is NOT present here.
     */
    @Test
    public void testFetchV10() throws Exception {
        sendRequest(buildProduceRequestV8("fetch-v10-topic", 0, null, "v10-data"));

        ByteBuf response = sendRequest(buildFetchRequestV10("fetch-v10-topic", 0, 0));
        assertNotNull(response);
        response.readInt(); // correlationId
        response.readInt(); // throttle_time_ms
        response.readShort(); // error_code
        response.readInt();  // session_id

        int topicCount = response.readInt();
        assertEquals(1, topicCount);
        readNullableString(response); // topic name
        // TopicId is v13+ in response — NOT present at v10
        int partitionCount = response.readInt();
        assertEquals(1, partitionCount);

        response.readShort(); // error_code
        response.readInt();   // partition
        long highWatermark = response.readLong();
        assertTrue("High watermark should be > 0", highWatermark > 0);
    }

    /**
     * Fetch v11 adds RackId at request level and PreferredReadReplica in response.
     * TopicId is v13+ in both request and response, so it is NOT present here.
     */
    @Test
    public void testFetchV11() throws Exception {
        sendRequest(buildProduceRequestV8("fetch-v11-topic", 0, null, "v11-data"));

        ByteBuf response = sendRequest(buildFetchRequestV11("fetch-v11-topic", 0, 0));
        assertNotNull(response);
        response.readInt(); // correlationId
        response.readInt(); // throttle_time_ms
        response.readShort(); // error_code
        response.readInt();  // session_id

        int topicCount = response.readInt();
        assertEquals(1, topicCount);
        readNullableString(response); // topic name
        // TopicId is v13+ in response — NOT present at v11
        int partitionCount = response.readInt();
        assertEquals(1, partitionCount);

        response.readShort(); // error_code
        response.readInt();   // partition
        long highWatermark = response.readLong();
        assertTrue("High watermark should be > 0", highWatermark > 0);
        response.readLong(); // last_stable_offset (v4+)
        response.readLong(); // log_start_offset (v5+)
        response.readInt();  // aborted_transactions count (v4+)
        // preferred_read_replica (v11+)
        int preferredReadReplica = response.readInt();
        assertEquals("preferred_read_replica should be -1", -1, preferredReadReplica);
    }

    // ----------------------------------------------------------------------
    // Flexible version tests (Phase 2: Produce v9 / Fetch v12 / ApiVersions v3)
    // ----------------------------------------------------------------------

    /**
     * ApiVersions v3 (flexible) must return a response using compact array
     * format (uvarint length) with per-entry tag buffers.
     */
    @Test
    public void testApiVersionsV3FlexibleResponse() throws Exception {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(18); // API key: ApiVersions
        buf.writeShort(3);  // API version: v3 (flexible)
        buf.writeInt(42);   // correlation ID
        // compact-string clientId "test"
        buf.writeByte(5); // length = 4 + 1 = 5
        buf.writeBytes("test".getBytes());
        // empty tag buffer
        buf.writeByte(0);
        // ApiVersions v3 body: client software name + version + tag buffer
        buf.writeByte(5); // "test" compact string
        buf.writeBytes("test".getBytes());
        buf.writeByte(4); // "1.0" compact string
        buf.writeBytes("1.0".getBytes());
        buf.writeByte(0); // empty tag buffer

        ByteBuf response = sendRequest(frameRequest(buf));
        assertNotNull(response);
        int correlationId = response.readInt();
        assertEquals(42, correlationId);

        // v3 flexible body: error_code (i16)
        short errorCode = response.readShort();
        assertEquals(0, errorCode);

        // compact array length (uvarint where N = count + 1)
        int compactLen = KafkaFlexibleCodec.readUnsignedVarint(response);
        int apiCount = compactLen - 1;
        assertTrue("Should have multiple API entries", apiCount > 0);

        // Read first few entries to verify format
        boolean foundProduce = false, foundApiVersions = false;
        for (int i = 0; i < apiCount; i++) {
            short apiKey = response.readShort();
            response.readShort(); // minVersion
            short maxVer = response.readShort();
            // per-entry tag buffer
            KafkaFlexibleCodec.skipTagBuffer(response);
            if (apiKey == 0) { foundProduce = true; assertEquals("Produce max should be v9", 9, maxVer); }
            if (apiKey == 18) { foundApiVersions = true; assertEquals("ApiVersions max should be v3", 3, maxVer); }
        }
        assertTrue("Should find Produce API", foundProduce);
        assertTrue("Should find ApiVersions API", foundApiVersions);

        // throttle_time_ms
        response.readInt();
        // top-level tag buffer
        KafkaFlexibleCodec.skipTagBuffer(response);
    }

    /**
     * Produce v9 (flexible) must be accepted and return a response using
     * compact arrays, compact strings, and tag buffers.
     */
    @Test
    public void testProduceV9Flexible() throws Exception {
        ByteBuf response = sendRequest(buildProduceRequestV9("produce-v9-topic", 0, "k", "v9-payload"));
        assertNotNull(response);
        response.readInt(); // correlationId
        // Flexible Produce v9 response: compact array of topics
        int topicCount = KafkaFlexibleCodec.readCompactArrayLength(response);
        assertEquals(1, topicCount);
        String topic = KafkaFlexibleCodec.readCompactStringNotNullable(response);
        assertEquals("produce-v9-topic", topic);
        int partitionCount = KafkaFlexibleCodec.readCompactArrayLength(response);
        assertEquals(1, partitionCount);
        response.readInt(); // partition_index
        short errorCode = response.readShort();
        assertEquals(0, errorCode);
        long offset = response.readLong(); // base_offset
        assertTrue("Offset should be >= 0", offset >= 0);
        response.readLong(); // log_append_time_ms
        response.readLong(); // log_start_offset
        // RecordErrors compact array
        KafkaFlexibleCodec.readCompactArrayLength(response);
        // ErrorMessage compact string
        KafkaFlexibleCodec.readCompactString(response);
        // per-partition tag buffer
        KafkaFlexibleCodec.skipTagBuffer(response);
        // per-topic tag buffer
        KafkaFlexibleCodec.skipTagBuffer(response);
        // throttle_time_ms
        response.readInt();
        // top-level tag buffer
        KafkaFlexibleCodec.skipTagBuffer(response);
    }

    /**
     * Fetch v12 (flexible) must be accepted and return a response using
     * compact arrays, compact strings, and tag buffers.
     */
    @Test
    public void testFetchV12Flexible() throws Exception {
        // First produce some data
        sendRequest(buildProduceRequestV9("fetch-v12-topic", 0, null, "v12-data"));

        ByteBuf response = sendRequest(buildFetchRequestV12("fetch-v12-topic", 0, 0));
        assertNotNull(response);
        response.readInt(); // correlationId
        response.readInt(); // throttle_time_ms
        response.readShort(); // error_code
        response.readInt(); // session_id

        // Topics compact array
        int topicCount = KafkaFlexibleCodec.readCompactArrayLength(response);
        assertEquals(1, topicCount);
        String topic = KafkaFlexibleCodec.readCompactStringNotNullable(response);
        assertEquals("fetch-v12-topic", topic);
        // topic_id (null UUID)
        byte[] uuid = new byte[16];
        response.readBytes(uuid);
        // Partitions compact array
        int partitionCount = KafkaFlexibleCodec.readCompactArrayLength(response);
        assertEquals(1, partitionCount);
        response.readInt(); // partition_index
        response.readShort(); // error_code
        long highWatermark = response.readLong();
        assertTrue("High watermark should be > 0", highWatermark > 0);
        response.readLong(); // last_stable_offset
        response.readLong(); // log_start_offset
        // aborted_transactions: compact nullable array (null = uvarint 0)
        KafkaFlexibleCodec.readCompactArrayLength(response);
        response.readInt(); // preferred_read_replica (-1)
        // record data (int32 length prefix still used for record sets)
        int recordLen = response.readInt();
        if (recordLen > 0) {
            response.skipBytes(recordLen);
        }
        // per-partition tag buffer
        KafkaFlexibleCodec.skipTagBuffer(response);
        // per-topic tag buffer
        KafkaFlexibleCodec.skipTagBuffer(response);
        // top-level tag buffer
        KafkaFlexibleCodec.skipTagBuffer(response);
    }

    // --- Helper methods for building Kafka protocol requests ---

    private ByteBuf buildApiVersionsRequest(int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(18); // API key: ApiVersions
        buf.writeShort(2);  // API version
        buf.writeInt(correlationId);
        writeNullableString(buf, "test-client");
        return frameRequest(buf);
    }

    private ByteBuf buildMetadataRequest(String topic) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(3);  // API key: Metadata
        buf.writeShort(4);  // API version
        buf.writeInt(1);    // correlation ID
        writeNullableString(buf, "test-client");
        // Topics array
        buf.writeInt(1);
        writeNullableString(buf, topic);
        // allow_auto_topic_creation (v4+)
        buf.writeBoolean(true);
        return frameRequest(buf);
    }

    private ByteBuf buildProduceRequest(String topic, int partition, String key, String value) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(0);  // API key: Produce
        buf.writeShort(3);  // API version
        buf.writeInt(1);    // correlation ID
        writeNullableString(buf, "test-client");
        // transactional_id (v3+)
        writeNullableString(buf, null);
        // acks
        buf.writeShort(-1);
        // timeout_ms
        buf.writeInt(30000);
        // Topics array
        buf.writeInt(1);
        writeNullableString(buf, topic);
        // Partitions array
        buf.writeInt(1);
        buf.writeInt(partition);
        // Record batch
        ByteBuf recordBatch = buildRecordBatch(key, value);
        buf.writeBytes(recordBatch);
        recordBatch.release();
        return frameRequest(buf);
    }

    private ByteBuf buildRecordBatch(String key, String value) {
        byte[] keyBytes = key != null ? key.getBytes(StandardCharsets.UTF_8) : null;
        byte[] valueBytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : null;

        // Build record body (without the leading length varint, added below)
        ByteBuf body = Unpooled.buffer();
        body.writeByte(0); // attributes
        writeVarint(body, 0); // timestampDelta
        writeVarint(body, 0); // offsetDelta
        if (keyBytes != null) {
            writeVarint(body, keyBytes.length);
            body.writeBytes(keyBytes);
        } else {
            writeVarint(body, -1);
        }
        if (valueBytes != null) {
            writeVarint(body, valueBytes.length);
            body.writeBytes(valueBytes);
        } else {
            writeVarint(body, -1);
        }
        writeVarint(body, 0); // headers count

        int recordLen = body.readableBytes();

        // Build batch
        ByteBuf batchBuf = Unpooled.buffer();
        batchBuf.writeLong(0); // baseOffset
        // batchLength placeholder
        int batchLengthPos = batchBuf.writerIndex();
        batchBuf.writeInt(0); // placeholder
        batchBuf.writeInt(0); // partitionLeaderEpoch
        batchBuf.writeByte(2); // magic = 2
        batchBuf.writeInt(0); // CRC
        batchBuf.writeShort(0); // attributes
        batchBuf.writeInt(0); // lastOffsetDelta
        batchBuf.writeLong(System.currentTimeMillis()); // baseTimestamp
        batchBuf.writeLong(System.currentTimeMillis()); // maxTimestamp
        batchBuf.writeLong(0); // producerId
        batchBuf.writeShort(0); // producerEpoch
        batchBuf.writeInt(0); // baseSequence
        batchBuf.writeInt(1); // recordsCount
        // Per-record length varint prefix (matches real Kafka RecordBatch v2)
        writeVarint(batchBuf, recordLen);
        batchBuf.writeBytes(body);
        body.release();

        // Fix batchLength
        int batchLength = batchBuf.readableBytes() - 12; // exclude baseOffset(8) + batchLength(4)
        batchBuf.setInt(batchLengthPos, batchLength);

        // Wrap in partition's record_set with length prefix
        ByteBuf result = Unpooled.buffer();
        result.writeInt(batchBuf.readableBytes());
        result.writeBytes(batchBuf);
        batchBuf.release();
        return result;
    }

    private ByteBuf buildFetchRequest(String topic, int partition, long offset) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(1);  // API key: Fetch
        buf.writeShort(4);  // API version
        buf.writeInt(2);    // correlation ID
        writeNullableString(buf, "test-client");
        buf.writeInt(-1);   // replica_id
        buf.writeInt(500);  // max_wait_ms
        buf.writeInt(1);    // min_bytes
        buf.writeInt(1024 * 1024); // max_bytes (v3+)
        buf.writeByte(0);   // isolation_level (v4+)
        // Topics array
        buf.writeInt(1);
        writeNullableString(buf, topic);
        // Partitions array
        buf.writeInt(1);
        buf.writeInt(partition);
        buf.writeLong(offset); // fetch_offset
        // v4 has no log_start_offset (added in v5 as INT64)
        buf.writeInt(1024 * 1024); // partition_max_bytes
        return frameRequest(buf);
    }

    /**
     * Build a Produce request at v8 (non-flexible max). Wire format is identical
     * to v3; only the version number changes.
     */
    private ByteBuf buildProduceRequestV8(String topic, int partition, String key, String value) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(0);  // API key: Produce
        buf.writeShort(8);  // API version (upgraded cap)
        buf.writeInt(1);    // correlation ID
        writeNullableString(buf, "test-client");
        writeNullableString(buf, null); // transactional_id (v3+)
        buf.writeShort(-1); // acks
        buf.writeInt(30000); // timeout_ms
        buf.writeInt(1);    // topics array count
        writeNullableString(buf, topic);
        buf.writeInt(1);    // partitions array count
        buf.writeInt(partition);
        ByteBuf recordBatch = buildRecordBatch(key, value);
        buf.writeBytes(recordBatch);
        recordBatch.release();
        return frameRequest(buf);
    }

    /**
     * Build a Fetch request at v8. Per the Kafka protocol spec (FetchRequest.json),
     * v8 is the same as v7 — no structural changes. CurrentLeaderEpoch was added
     * in v9, RackId in v11, TopicId in v13.
     */
    private ByteBuf buildFetchRequestV8(String topic, int partition, long offset) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(1);  // API key: Fetch
        buf.writeShort(8);  // API version
        buf.writeInt(2);    // correlation ID
        writeNullableString(buf, "test-client");
        buf.writeInt(-1);   // replica_id
        buf.writeInt(500);  // max_wait_ms
        buf.writeInt(1);    // min_bytes
        buf.writeInt(1024 * 1024); // max_bytes (v3+)
        buf.writeByte(0);   // isolation_level (v4+)
        buf.writeInt(0);    // session_id (v7+)
        buf.writeInt(0);    // session_epoch (v7+)
        // Topics array
        buf.writeInt(1);
        writeNullableString(buf, topic);
        // Partitions array
        buf.writeInt(1);
        buf.writeInt(partition);       // partition_index
        buf.writeLong(offset);         // fetch_offset
        buf.writeLong(0L);             // log_start_offset (v5+, INT64)
        buf.writeInt(1024 * 1024);     // partition_max_bytes
        // forgotten_topics_data (v7+) — empty array
        buf.writeInt(0);
        return frameRequest(buf);
    }

    /**
     * Build a Fetch request at v10. Per the Kafka protocol spec (FetchRequest.json),
     * v10 only adds ZStd compression support — no structural changes from v9.
     * CurrentLeaderEpoch (v9+) is present per partition. TopicId is v13+ (not here).
     */
    private ByteBuf buildFetchRequestV10(String topic, int partition, long offset) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(1);  // API key: Fetch
        buf.writeShort(10); // API version
        buf.writeInt(2);    // correlation ID
        writeNullableString(buf, "test-client");
        buf.writeInt(-1);   // replica_id
        buf.writeInt(500);  // max_wait_ms
        buf.writeInt(1);    // min_bytes
        buf.writeInt(1024 * 1024); // max_bytes (v3+)
        buf.writeByte(0);   // isolation_level (v4+)
        buf.writeInt(0);    // session_id (v7+)
        buf.writeInt(0);    // session_epoch (v7+)
        // Topics array
        buf.writeInt(1);
        writeNullableString(buf, topic);
        // Partitions array
        buf.writeInt(1);
        buf.writeInt(partition);       // partition_index
        buf.writeInt(-1);              // current_leader_epoch (v9+, default -1)
        buf.writeLong(offset);         // fetch_offset
        buf.writeLong(0L);             // log_start_offset (v5+, INT64)
        buf.writeInt(1024 * 1024);     // partition_max_bytes
        // forgotten_topics_data (v7+) — empty array
        buf.writeInt(0);
        return frameRequest(buf);
    }

    /**
     * Build a Fetch request at v11. Per the Kafka protocol spec (FetchRequest.json),
     * v11 adds RackId (NULLABLE_STRING) at the request level, after forgotten_topics.
     * CurrentLeaderEpoch (v9+) is present per partition. TopicId is v13+ (not here).
     */
    private ByteBuf buildFetchRequestV11(String topic, int partition, long offset) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(1);  // API key: Fetch
        buf.writeShort(11); // API version
        buf.writeInt(2);    // correlation ID
        writeNullableString(buf, "test-client");
        buf.writeInt(-1);   // replica_id
        buf.writeInt(500);  // max_wait_ms
        buf.writeInt(1);    // min_bytes
        buf.writeInt(1024 * 1024); // max_bytes (v3+)
        buf.writeByte(0);   // isolation_level (v4+)
        buf.writeInt(0);    // session_id (v7+)
        buf.writeInt(0);    // session_epoch (v7+)
        // Topics array
        buf.writeInt(1);
        writeNullableString(buf, topic);
        // Partitions array
        buf.writeInt(1);
        buf.writeInt(partition);       // partition_index
        buf.writeInt(-1);              // current_leader_epoch (v9+, default -1)
        buf.writeLong(offset);         // fetch_offset
        buf.writeLong(0L);             // log_start_offset (v5+, INT64)
        buf.writeInt(1024 * 1024);     // partition_max_bytes
        // forgotten_topics_data (v7+) — empty array
        buf.writeInt(0);
        // rack_id (v11+) — request level, after forgotten_topics
        writeNullableString(buf, null);
        return frameRequest(buf);
    }

    /**
     * Build a Produce request at v9 (flexible). Uses Request Header v2
     * (compact clientId + tag buffer), compact nullable string for
     * transactional_id, compact arrays, compact bytes for record data,
     * and tag buffers.
     */
    private ByteBuf buildProduceRequestV9(String topic, int partition, String key, String value) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(0);  // API key: Produce
        buf.writeShort(9);  // API version: v9 (flexible)
        buf.writeInt(1);    // correlation ID
        // compact clientId "test"
        buf.writeByte(5);
        buf.writeBytes("test".getBytes());
        // empty tag buffer
        buf.writeByte(0);

        // Body
        // transactional_id: compact nullable string (null = uvarint 0)
        KafkaFlexibleCodec.writeUnsignedVarint(buf, 0);
        buf.writeShort((short) -1); // acks
        buf.writeInt(30000); // timeoutMs

        // Topics: compact array (1 element = uvarint 2)
        KafkaFlexibleCodec.writeCompactArrayLength(buf, 1);
        // topic name: compact not-nullable string
        KafkaFlexibleCodec.writeCompactStringNotNullable(buf, topic);
        // Partitions: compact array (1 element)
        KafkaFlexibleCodec.writeCompactArrayLength(buf, 1);
        buf.writeInt(partition); // partition index

        // Build record batch
        byte[] keyBytes = key != null ? key.getBytes(StandardCharsets.UTF_8) : null;
        byte[] valueBytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : null;

        // Build the record
        ByteBuf record = Unpooled.buffer();
        record.writeByte(0); // attributes
        KafkaCodecUtils.writeVarint(record, 0); // timestampDelta
        KafkaCodecUtils.writeVarint(record, 0); // offsetDelta
        if (keyBytes != null) {
            KafkaCodecUtils.writeVarint(record, keyBytes.length);
            record.writeBytes(keyBytes);
        } else {
            KafkaCodecUtils.writeVarint(record, -1);
        }
        if (valueBytes != null) {
            KafkaCodecUtils.writeVarint(record, valueBytes.length);
            record.writeBytes(valueBytes);
        } else {
            KafkaCodecUtils.writeVarint(record, -1);
        }
        KafkaCodecUtils.writeVarint(record, 0); // headers count

        int recordLen = record.readableBytes();

        ByteBuf contentBuf = Unpooled.buffer();
        contentBuf.writeShort(0); // attributes
        contentBuf.writeInt(0); // lastOffsetDelta
        long now = System.currentTimeMillis();
        contentBuf.writeLong(now); // baseTimestamp
        contentBuf.writeLong(now); // maxTimestamp
        contentBuf.writeLong(0); // producerId
        contentBuf.writeShort(0); // producerEpoch
        contentBuf.writeInt(0); // baseSequence
        contentBuf.writeInt(1); // recordsCount
        KafkaCodecUtils.writeVarint(contentBuf, recordLen);
        contentBuf.writeBytes(record);
        record.release();

        byte[] contentBytes = new byte[contentBuf.readableBytes()];
        contentBuf.readBytes(contentBytes);
        contentBuf.release();

        int crc = KafkaCodecUtils.computeCrc32c(contentBytes, 0, contentBytes.length);
        int batchLength = 4 + 1 + 4 + contentBytes.length;

        ByteBuf batchBuf = Unpooled.buffer();
        batchBuf.writeLong(0); // baseOffset
        batchBuf.writeInt(batchLength); // batchLength
        batchBuf.writeInt(0); // partitionLeaderEpoch
        batchBuf.writeByte(2); // magic
        batchBuf.writeInt(crc); // CRC32C
        batchBuf.writeBytes(contentBytes);

        // In flexible Produce v9, record data uses compact bytes (uvarint length + data)
        KafkaFlexibleCodec.writeCompactBytes(buf, toArray(batchBuf));
        batchBuf.release();

        // per-partition tag buffer
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf);
        // per-topic tag buffer
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf);
        // top-level tag buffer
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf);

        return frameRequest(buf);
    }

    /**
     * Build a Fetch request at v12 (flexible). Uses Request Header v2
     * (compact clientId + tag buffer), compact arrays, compact strings,
     * and tag buffers.
     */
    private ByteBuf buildFetchRequestV12(String topic, int partition, long offset) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(1);  // API key: Fetch
        buf.writeShort(12); // API version: v12 (flexible)
        buf.writeInt(2);    // correlation ID
        // compact clientId "test"
        buf.writeByte(5);
        buf.writeBytes("test".getBytes());
        // empty tag buffer
        buf.writeByte(0);

        // Body
        buf.writeInt(-1);   // replica_id
        buf.writeInt(500);  // max_wait_ms
        buf.writeInt(1);    // min_bytes
        buf.writeInt(1024 * 1024); // max_bytes
        buf.writeByte(0);   // isolation_level
        buf.writeInt(0);    // session_id
        buf.writeInt(0);    // session_epoch

        // Topics: compact array (1 element)
        KafkaFlexibleCodec.writeCompactArrayLength(buf, 1);
        KafkaFlexibleCodec.writeCompactStringNotNullable(buf, topic);
        // Partitions: compact array (1 element)
        KafkaFlexibleCodec.writeCompactArrayLength(buf, 1);
        buf.writeInt(partition); // partition_index
        buf.writeInt(-1);        // current_leader_epoch
        buf.writeLong(offset);   // fetch_offset
        buf.writeInt(-1);        // last_fetched_epoch
        buf.writeLong(0L);       // log_start_offset
        buf.writeInt(1024 * 1024); // partition_max_bytes
        // per-partition tag buffer
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf);
        // per-topic tag buffer
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf);

        // forgotten_topics_data: compact array (0 elements = uvarint 1)
        KafkaFlexibleCodec.writeCompactArrayLength(buf, 0);

        // rack_id: compact nullable string (null)
        KafkaFlexibleCodec.writeCompactString(buf, null);

        // top-level tag buffer
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf);

        return frameRequest(buf);
    }

    private static byte[] toArray(ByteBuf buf) {
        byte[] arr = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), arr);
        return arr;
    }

    private ByteBuf buildFindCoordinatorRequest(String groupId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(10); // API key: FindCoordinator
        buf.writeShort(1);  // API version
        buf.writeInt(1);    // correlation ID
        writeNullableString(buf, "test-client");
        writeNullableString(buf, groupId);
        buf.writeByte(0);   // coordinator_type (0 = group)
        return frameRequest(buf);
    }

    private ByteBuf buildHeartbeatRequest() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(12); // API key: Heartbeat
        buf.writeShort(1);  // API version
        buf.writeInt(1);    // correlation ID
        writeNullableString(buf, "test-client");
        writeNullableString(buf, "test-group"); // group_id
        buf.writeInt(1);    // generation_id
        writeNullableString(buf, "member-1");   // member_id
        return frameRequest(buf);
    }

    private ByteBuf buildOffsetCommitRequest() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(8);  // API key: OffsetCommit
        buf.writeShort(2);  // API version
        buf.writeInt(1);    // correlation ID
        writeNullableString(buf, "test-client");
        writeNullableString(buf, "test-group"); // group_id
        buf.writeInt(1);    // generation_id
        writeNullableString(buf, "member-1");   // member_id
        buf.writeInt(0);    // retention_time_ms (v2+)
        // Topics array
        buf.writeInt(0);
        return frameRequest(buf);
    }

    private ByteBuf frameRequest(ByteBuf payload) {
        ByteBuf frame = Unpooled.buffer(4 + payload.readableBytes());
        frame.writeInt(payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private void writeNullableString(ByteBuf buf, String value) {
        if (value == null) {
            buf.writeShort(-1);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            buf.writeShort(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    private String readNullableString(ByteBuf buf) {
        short length = buf.readShort();
        if (length < 0) return null;
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeVarint(ByteBuf buf, int value) {
        int zigzag = (value << 1) ^ (value >> 31);
        while ((zigzag & ~0x7F) != 0) {
            buf.writeByte((byte) ((zigzag & 0x7F) | 0x80));
            zigzag >>>= 7;
        }
        buf.writeByte((byte) zigzag);
    }

    /**
     * Send a request to the Kafka Mock Broker and return the response.
     */
    private ByteBuf sendRequest(ByteBuf request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ByteBuf> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                100 * 1024 * 1024, 0, 4, 0, 4));
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                // Copy the response since the buffer will be released
                                ByteBuf copy = Unpooled.copiedBuffer(msg);
                                responseRef.set(copy);
                                latch.countDown();
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                errorRef.set(cause);
                                latch.countDown();
                                ctx.close();
                            }
                        });
                    }
                });

        Channel ch = b.connect("127.0.0.1", TEST_PORT).sync().channel();
        ch.writeAndFlush(request);

        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue("Should receive response within timeout", received);
        assertNull("Should not have error", errorRef.get());
        return responseRef.get();
    }
}
