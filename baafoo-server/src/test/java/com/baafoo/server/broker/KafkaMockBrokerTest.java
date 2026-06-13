package com.baafoo.server.broker;

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
                        ch.pipeline().addLast(new KafkaProtocolDecoder(messageStore, storage, TEST_PORT));
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

        // Read broker: host, port, broker_id
        String host = readNullableString(response);
        int port = response.readInt();
        int brokerId = response.readInt();
        assertEquals("Broker port should match", TEST_PORT, port);

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

        // throttle_time_ms (v1+, comes before topics in Kafka protocol)
        produceResponse.readInt();

        // Topics array
        int topicCount = produceResponse.readInt();
        assertEquals(1, topicCount);

        String topic = readNullableString(produceResponse);
        assertEquals("test-topic", topic);

        int partitionCount = produceResponse.readInt();
        assertEquals(1, partitionCount);

        short errorCode = produceResponse.readShort();
        assertEquals("Produce error code should be 0", 0, errorCode);

        int partition = produceResponse.readInt();
        assertEquals(0, partition);

        long offset = produceResponse.readLong();
        assertTrue("Offset should be >= 0", offset >= 0);

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

        String host = readNullableString(response);
        assertNotNull("Host should not be null", host);

        int port = response.readInt();
        assertEquals("Port should match", TEST_PORT, port);

        int nodeId = response.readInt();
        assertEquals("Node ID should be 0", 0, nodeId);
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

        // Build record
        ByteBuf recordBuf = Unpooled.buffer();
        recordBuf.writeByte(0); // attributes
        writeVarint(recordBuf, 0); // timestampDelta
        writeVarint(recordBuf, 0); // offsetDelta
        if (keyBytes != null) {
            writeVarint(recordBuf, keyBytes.length);
            recordBuf.writeBytes(keyBytes);
        } else {
            writeVarint(recordBuf, -1);
        }
        if (valueBytes != null) {
            writeVarint(recordBuf, valueBytes.length);
            recordBuf.writeBytes(valueBytes);
        } else {
            writeVarint(recordBuf, -1);
        }
        writeVarint(recordBuf, 0); // headers count

        int recordLen = recordBuf.readableBytes();

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
        batchBuf.writeBytes(recordBuf);
        recordBuf.release();

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
        buf.writeInt(1024 * 1024); // max_bytes
        buf.writeByte(0);   // isolation_level
        // Topics array
        buf.writeInt(1);
        writeNullableString(buf, topic);
        // Partitions array
        buf.writeInt(1);
        buf.writeInt(partition);
        buf.writeLong(offset); // fetch_offset
        buf.writeInt(0);       // log_start_offset
        buf.writeInt(1024 * 1024); // partition_max_bytes
        return frameRequest(buf);
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
