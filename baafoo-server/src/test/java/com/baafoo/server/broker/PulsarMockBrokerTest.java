package com.baafoo.server.broker;

import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the Pulsar Mock Broker.
 * Uses real Netty channels to test the Pulsar binary protocol handling.
 */
public class PulsarMockBrokerTest {

    private static final int TEST_PORT = 19093;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private PulsarMessageStore messageStore;
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
        messageStore = new PulsarMessageStore(storage);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new PulsarFrameDecoder());
                        ch.pipeline().addLast(new PulsarMockBrokerHandler(messageStore, storage, "localhost", TEST_PORT, null));
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
    public void testConnectHandshake() throws Exception {
        ByteBuf connectFrame = buildConnectFrame("test-client", 12);
        ByteBuf response = sendFrame(connectFrame);
        assertNotNull("Should receive CONNECTED response", response);

        // Parse the response frame
        PulsarCommand cmd = parseResponseFrame(response);
        assertEquals("Should be CONNECTED type", PulsarProtobufCodec.TYPE_CONNECTED, cmd.type);
    }

    @Test
    public void testLookupReturnsSelf() throws Exception {
        // First connect
        sendFrame(buildConnectFrame("test-client", 12));

        // Then lookup
        ByteBuf lookupFrame = buildLookupFrame("persistent://public/default/test-topic", 1);
        ByteBuf response = sendFrame(lookupFrame);
        assertNotNull("Should receive LOOKUP_RESPONSE", response);

        PulsarCommand cmd = parseResponseFrame(response);
        assertEquals("Should be LOOKUP_RESPONSE type", PulsarProtobufCodec.TYPE_LOOKUP_RESPONSE, cmd.type);
    }

    @Test
    public void testPartitionedMetadataReturnsNonPartitioned() throws Exception {
        // First connect
        sendFrame(buildConnectFrame("test-client", 12));

        ByteBuf metadataFrame = buildPartitionMetadataFrame("persistent://public/default/test-topic", 2);
        ByteBuf response = sendFrame(metadataFrame);
        assertNotNull("Should receive PARTITIONED_METADATA_RESPONSE", response);

        PulsarCommand cmd = parseResponseFrame(response);
        assertEquals("Should be PARTITIONED_METADATA_RESPONSE type",
                PulsarProtobufCodec.TYPE_PARTITIONED_METADATA_RESPONSE, cmd.type);
    }

    @Test
    public void testPingPong() throws Exception {
        // First connect
        sendFrame(buildConnectFrame("test-client", 12));

        ByteBuf pingFrame = buildPingFrame();
        ByteBuf response = sendFrame(pingFrame);
        assertNotNull("Should receive PONG response", response);

        PulsarCommand cmd = parseResponseFrame(response);
        assertEquals("Should be PONG type", PulsarProtobufCodec.TYPE_PONG, cmd.type);
    }

    @Test
    public void testMessageStoreStoreAndRetrieve() {
        byte[] payload = "hello-pulsar".getBytes(StandardCharsets.UTF_8);
        PulsarMessageStore.StoredMessage msg = messageStore.storeMessage(
                "persistent://public/default/test-topic", "producer-1", 0, payload);

        assertNotNull("Stored message should not be null", msg);
        assertTrue("Ledger ID should be > 0", msg.ledgerId > 0);
        assertEquals("Entry ID should be 0 for first message", 0, msg.entryId);
        assertEquals("Topic should match", "persistent://public/default/test-topic", msg.topic);

        // Store another message
        PulsarMessageStore.StoredMessage msg2 = messageStore.storeMessage(
                "persistent://public/default/test-topic", "producer-1", 1, payload);
        assertEquals("Entry ID should be 1 for second message", 1, msg2.entryId);
    }

    @Test
    public void testMessageStoreSubscriptionDelivery() {
        byte[] payload1 = "msg1".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "msg2".getBytes(StandardCharsets.UTF_8);

        // Store messages before subscription
        messageStore.storeMessage("test-topic", "p1", 0, payload1);
        messageStore.storeMessage("test-topic", "p1", 1, payload2);

        // Register subscription — should get existing messages
        java.util.List<PulsarMessageStore.StoredMessage> existing =
                messageStore.registerSubscription("test-topic", "sub1");
        assertEquals("Should receive 2 existing messages", 2, existing.size());

        // Poll messages
        PulsarMessageStore.StoredMessage polled = messageStore.pollMessage("test-topic", "sub1");
        assertNotNull("Should poll first message", polled);

        polled = messageStore.pollMessage("test-topic", "sub1");
        assertNotNull("Should poll second message", polled);

        polled = messageStore.pollMessage("test-topic", "sub1");
        assertNull("Should return null when queue is empty", polled);
    }

    @Test
    public void testMessageStoreGetTopicsOfNamespace() {
        when(storage.listRules()).thenReturn(new ArrayList<>());

        java.util.List<String> topics = messageStore.getTopicsOfNamespace("public/default");
        // When no rules configured, should return empty list
        assertTrue("Should return empty list when no rules", topics.isEmpty());
    }

    // ----------------------------------------------------------------------
    // Rule matching / recording / stub-injection tests
    // ----------------------------------------------------------------------

    /**
     * A pulsar rule with a {@code topic} condition stubs incoming SENDs: the
     * stored message's payload body is replaced by the rule's response body
     * (default mode is STUB). The MessageMetadata prefix is preserved.
     */
    @Test
    public void testSendMatchesTopicRuleAndInjectsStub() throws Exception {
        Rule rule = new Rule();
        rule.setId("rule-p-stub");
        rule.setProtocol("pulsar");
        rule.setEnabled(true);
        rule.setConditions(Collections.singletonList(MatchCondition.topic("equals", "persistent://public/default/orders")));
        rule.setResponses(Collections.singletonList(pulsarResp("PULSAR-STUB-BODY")));

        when(storage.listRules()).thenReturn(Collections.singletonList(rule));

        // Connect + register a producer for the topic, then send a message — all on
        // one connection so the per-channel producer map is shared.
        sendFramesSameConnection(
                buildConnectFrame("test-client", 12),
                buildProducerFrame(100L, 1L, "persistent://public/default/orders"),
                buildSendFrame(100L, 1L, pulsarPayload("real-body")));

        // The store should hold the stub body, with metadata prefix preserved.
        java.util.List<PulsarMessageStore.StoredMessage> stored =
                messageStore.registerSubscription("persistent://public/default/orders", "test-sub");
        assertNotNull(stored);
        assertEquals(1, stored.size());
        byte[] storedPayload = stored.get(0).payload;
        assertNotNull(storedPayload);
        // The metadata prefix (magic + varint) must be preserved, body must be the stub.
        assertEquals("PULSAR-STUB-BODY",
                new String(stripPulsarMetadata(storedPayload), StandardCharsets.UTF_8));
    }

    /**
     * RECORD mode persists a RecordingEntry with the decoded payload body and
     * the matched rule id.
     */
    @Test
    public void testSendRecordsWithRuleIdAndDecodedPayload() throws Exception {
        Rule rule = new Rule();
        rule.setId("rule-p-rec");
        rule.setProtocol("pulsar");
        rule.setEnabled(true);
        rule.setConditions(Collections.singletonList(MatchCondition.topic("equals", "persistent://public/default/rec")));
        rule.setResponses(Collections.singletonList(pulsarResp("STUB")));

        when(storage.listRules()).thenReturn(Collections.singletonList(rule));
        com.baafoo.core.model.Environment recEnv = new com.baafoo.core.model.Environment();
        recEnv.setName("default");
        recEnv.setMode(EnvironmentMode.RECORD);
        when(storage.listEnvironments()).thenReturn(Collections.singletonList(recEnv));

        sendFramesSameConnection(
                buildConnectFrame("test-client", 12),
                buildProducerFrame(200L, 1L, "persistent://public/default/rec"),
                buildSendFrame(200L, 1L, pulsarPayload("rec-payload")));

        verify(storage, atLeastOnce()).addRecording(
                org.mockito.ArgumentMatchers.argThat(entry ->
                        "rule-p-rec".equals(entry.getRuleId())
                                && "pulsar".equals(entry.getProtocol())
                                && "persistent://public/default/rec".equals(entry.getPath())
                                && "rec-payload".equals(entry.getRequestBody())));
    }

    // --- Pulsar test helpers ---

    /**
     * Send multiple frames on a SINGLE connection (so per-channel state like the
     * producer map is shared), waiting for a response between each. Returns the
     * last response. Needed because {@link #sendFrame} opens a fresh connection
     * per call, which would isolate PRODUCER/SEND state from each other.
     */
    private ByteBuf sendFramesSameConnection(ByteBuf... frames) throws Exception {
        final AtomicReference<ByteBuf> lastResponse = new AtomicReference<>();
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        // A single-slot latch recreated per frame so we can wait for each response.
        final CountDownLatch[] latchHolder = new CountDownLatch[1];

        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new PulsarFrameDecoder());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<PulsarFrame>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, PulsarFrame msg) {
                                lastResponse.set(encodePulsarFrame(msg));
                                if (latchHolder[0] != null) latchHolder[0].countDown();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                errorRef.set(cause);
                                if (latchHolder[0] != null) latchHolder[0].countDown();
                                ctx.close();
                            }
                        });
                    }
                });

        Channel ch = b.connect("127.0.0.1", TEST_PORT).sync().channel();
        for (ByteBuf frame : frames) {
            latchHolder[0] = new CountDownLatch(1);
            ch.writeAndFlush(frame);
            if (!latchHolder[0].await(5, TimeUnit.SECONDS)) {
                fail("Timed out waiting for response to a frame");
            }
            assertNull("Should not have error", errorRef.get());
        }
        ch.close().await(2, TimeUnit.SECONDS);
        return lastResponse.get();
    }


    private static ResponseEntry pulsarResp(String body) {
        ResponseEntry r = new ResponseEntry();
        r.setBody(body);
        return r;
    }

    /** Build a minimal Pulsar SEND command frame (field 6: producerId, sequenceId). */
    private ByteBuf buildSendFrame(long producerId, long sequenceId, byte[] payload) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_SEND);

        ByteArrayOutputStream sendOut = new ByteArrayOutputStream();
        // producerId (field 1, int64)
        PulsarProtobufCodec.writeVarint(sendOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint64(sendOut, producerId);
        // sequenceId (field 2, int64)
        PulsarProtobufCodec.writeVarint(sendOut, (2 << 3) | 0);
        PulsarProtobufCodec.writeVarint64(sendOut, sequenceId);

        byte[] sendBytes = sendOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (6 << 3) | 2); // field 6 (SEND), wire type 2
        PulsarProtobufCodec.writeVarint(cmdOut, sendBytes.length);
        try { cmdOut.write(sendBytes); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        return wrapInFrame(cmdOut.toByteArray(), payload);
    }

    /** Build a minimal Pulsar PRODUCER command frame (field 5: topic, producerId, requestId). */
    private ByteBuf buildProducerFrame(long producerId, long requestId, String topic) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_PRODUCER);

        ByteArrayOutputStream prodOut = new ByteArrayOutputStream();
        writeProtobufString(prodOut, 1, topic); // topic
        // producerId (field 2, int64)
        PulsarProtobufCodec.writeVarint(prodOut, (2 << 3) | 0);
        PulsarProtobufCodec.writeVarint64(prodOut, producerId);
        // requestId (field 3, int64)
        PulsarProtobufCodec.writeVarint(prodOut, (3 << 3) | 0);
        PulsarProtobufCodec.writeVarint64(prodOut, requestId);

        byte[] prodBytes = prodOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (5 << 3) | 2); // field 5 (PRODUCER), wire type 2
        PulsarProtobufCodec.writeVarint(cmdOut, prodBytes.length);
        try { cmdOut.write(prodBytes); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        return wrapInFrame(cmdOut.toByteArray(), new byte[0]);
    }

    /**
     * Build a Pulsar payload with the standard framing: magic(0x0E) +
     * varint(metadataSize) + MessageMetadata + body. Uses an empty metadata
     * (size 0) since the handler only needs to skip past it.
     */
    private static byte[] pulsarPayload(String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[2 + bodyBytes.length];
        result[0] = (byte) 0x0E; // magic
        result[1] = 0;           // empty metadata (varint length = 0)
        System.arraycopy(bodyBytes, 0, result, 2, bodyBytes.length);
        return result;
    }

    /** Strip the magic + varint-metadataSize + metadata to get the body from a stored payload. */
    private static byte[] stripPulsarMetadata(byte[] payload) {
        if (payload == null || payload.length < 2) return new byte[0];
        int idx = (payload[0] == (byte) 0x0E) ? 1 : 0;
        int metaSize = 0, shift = 0, b;
        do {
            b = payload[idx++] & 0xFF;
            metaSize |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        int bodyLen = payload.length - idx - metaSize;
        byte[] body = new byte[bodyLen];
        System.arraycopy(payload, idx + metaSize, body, 0, bodyLen);
        return body;
    }


    @Test
    public void testProtobufVarintEncoding() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PulsarProtobufCodec.writeVarint(out, 0);
        assertArrayEquals("Varint 0 should encode as [0]", new byte[]{0}, out.toByteArray());

        out = new ByteArrayOutputStream();
        PulsarProtobufCodec.writeVarint(out, 1);
        assertArrayEquals("Varint 1 should encode as [1]", new byte[]{1}, out.toByteArray());

        out = new ByteArrayOutputStream();
        PulsarProtobufCodec.writeVarint(out, 127);
        assertArrayEquals("Varint 127 should encode as [127]", new byte[]{127}, out.toByteArray());

        out = new ByteArrayOutputStream();
        PulsarProtobufCodec.writeVarint(out, 128);
        assertEquals("Varint 128 should be 2 bytes", 2, out.toByteArray().length);
    }

    @Test
    public void testProtobufVarintRoundTrip() {
        int[] testValues = {0, 1, 127, 128, 255, 256, 1000, 65535, Integer.MAX_VALUE};
        for (int value : testValues) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PulsarProtobufCodec.writeVarint(out, value);
            byte[] encoded = out.toByteArray();

            int[] pos = {0};
            int decoded = PulsarProtobufCodec.readVarint(encoded, pos);
            assertEquals("Round-trip for value " + value, value, decoded);
        }
    }

    @Test
    public void testProtobufVarint64RoundTrip() {
        long[] testValues = {0L, 1L, 127L, 128L, 255L, 256L, 1000L, 65535L,
                Integer.MAX_VALUE * 2L, Long.MAX_VALUE};
        for (long value : testValues) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PulsarProtobufCodec.writeVarint64(out, value);
            byte[] encoded = out.toByteArray();

            int[] pos = {0};
            long decoded = PulsarProtobufCodec.readVarint64(encoded, pos);
            assertEquals("Round-trip for value " + value, value, decoded);
        }
    }

    @Test
    public void testProtobufCommandDecoding() {
        // Build a CONNECT command using lightproto field numbers
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type field (field 1, varint)
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0); // tag
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_CONNECT); // value = 2

        // connect sub-message (field 2 in BaseCommand, per lightproto)
        ByteArrayOutputStream connectOut = new ByteArrayOutputStream();
        // client_version (field 1, string) — lightproto field number
        writeProtobufString(connectOut, 1, "test-client-1.0");
        // auth_method (field 2, enum/varint) — 0 = None
        PulsarProtobufCodec.writeVarint(connectOut, (2 << 3) | 0);
        PulsarProtobufCodec.writeVarint(connectOut, 0);
        // auth_data (field 3, bytes)
        writeProtobufBytes(connectOut, 3, new byte[0]);
        // protocol_version (field 4, varint)
        PulsarProtobufCodec.writeVarint(connectOut, (4 << 3) | 0);
        PulsarProtobufCodec.writeVarint(connectOut, 12);

        byte[] connectBytes = connectOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (2 << 3) | 2); // tag for field 2, wire type 2
        PulsarProtobufCodec.writeVarint(cmdOut, connectBytes.length);
        try {
            cmdOut.write(connectBytes);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        byte[] commandBytes = cmdOut.toByteArray();
        PulsarCommand cmd = PulsarProtobufCodec.decodeCommand(commandBytes);

        assertEquals("Type should be CONNECT", PulsarProtobufCodec.TYPE_CONNECT, cmd.type);
        assertEquals("Client version should match", "test-client-1.0", cmd.clientVersion);
        assertEquals("Protocol version should match", 12, cmd.protocolVersion);
    }

    // --- Helper methods for building Pulsar protocol frames ---

    private ByteBuf buildConnectFrame(String clientVersion, int protocolVersion) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type = CONNECT (2)
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_CONNECT);

        // connect sub-message (field 2 in BaseCommand, per lightproto)
        ByteArrayOutputStream connectOut = new ByteArrayOutputStream();
        // client_version (field 1, string) — lightproto field number
        writeProtobufString(connectOut, 1, clientVersion);
        // auth_method (field 2, enum/varint) — 0 = None
        PulsarProtobufCodec.writeVarint(connectOut, (2 << 3) | 0);
        PulsarProtobufCodec.writeVarint(connectOut, 0);
        // auth_data (field 3, bytes)
        writeProtobufBytes(connectOut, 3, new byte[0]);
        // protocol_version (field 4, varint)
        PulsarProtobufCodec.writeVarint(connectOut, (4 << 3) | 0);
        PulsarProtobufCodec.writeVarint(connectOut, protocolVersion);

        byte[] connectBytes = connectOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (2 << 3) | 2); // field 2, wire type 2
        PulsarProtobufCodec.writeVarint(cmdOut, connectBytes.length);
        try { cmdOut.write(connectBytes); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        return wrapInFrame(cmdOut.toByteArray(), new byte[0]);
    }

    private ByteBuf buildLookupFrame(String topic, int requestId) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type = LOOKUP (23)
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_LOOKUP);

        // lookupTopic sub-message (field 23 in BaseCommand, per lightproto)
        ByteArrayOutputStream lookupOut = new ByteArrayOutputStream();
        writeProtobufString(lookupOut, 1, topic); // topic
        PulsarProtobufCodec.writeVarint(lookupOut, (2 << 3) | 0); // requestId (int64)
        PulsarProtobufCodec.writeVarint(lookupOut, requestId);
        PulsarProtobufCodec.writeVarint(lookupOut, (3 << 3) | 0); // authoritative
        PulsarProtobufCodec.writeVarint(lookupOut, 0);

        byte[] lookupBytes = lookupOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (23 << 3) | 2); // field 23, wire type 2
        PulsarProtobufCodec.writeVarint(cmdOut, lookupBytes.length);
        try { cmdOut.write(lookupBytes); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        return wrapInFrame(cmdOut.toByteArray(), new byte[0]);
    }

    private ByteBuf buildPartitionMetadataFrame(String topic, int requestId) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type = PARTITIONED_METADATA (21)
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_PARTITIONED_METADATA);

        // partitionMetadataRequest sub-message (field 21 in BaseCommand, per lightproto)
        ByteArrayOutputStream metaOut = new ByteArrayOutputStream();
        writeProtobufString(metaOut, 1, topic); // topic
        PulsarProtobufCodec.writeVarint(metaOut, (2 << 3) | 0); // requestId (int64)
        PulsarProtobufCodec.writeVarint(metaOut, requestId);

        byte[] metaBytes = metaOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (21 << 3) | 2); // field 21, wire type 2
        PulsarProtobufCodec.writeVarint(cmdOut, metaBytes.length);
        try { cmdOut.write(metaBytes); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        return wrapInFrame(cmdOut.toByteArray(), new byte[0]);
    }

    private ByteBuf buildPingFrame() {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type = PING (18)
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_PING);

        return wrapInFrame(cmdOut.toByteArray(), new byte[0]);
    }

    /**
     * Wrap command bytes + payload into a Pulsar frame:
     * [4 bytes totalSize] [4 bytes commandSize] [command bytes] [payload bytes]
     */
    private ByteBuf wrapInFrame(byte[] commandBytes, byte[] payload) {
        int commandSize = commandBytes.length;
        int totalSize = 4 + commandSize + payload.length;

        ByteBuf frame = Unpooled.buffer(4 + totalSize);
        frame.writeInt(totalSize);
        frame.writeInt(commandSize);
        frame.writeBytes(commandBytes);
        if (payload.length > 0) {
            frame.writeBytes(payload);
        }
        return frame;
    }

    private void writeProtobufString(ByteArrayOutputStream out, int fieldNumber, String value) {
        PulsarProtobufCodec.writeVarint(out, (fieldNumber << 3) | 2); // wire type 2
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        PulsarProtobufCodec.writeVarint(out, bytes.length);
        try { out.write(bytes); } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    private void writeProtobufBytes(ByteArrayOutputStream out, int fieldNumber, byte[] value) {
        PulsarProtobufCodec.writeVarint(out, (fieldNumber << 3) | 2); // wire type 2
        PulsarProtobufCodec.writeVarint(out, value.length);
        try { out.write(value); } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    /**
     * Parse a response frame into a PulsarCommand.
     */
    private PulsarCommand parseResponseFrame(ByteBuf frame) {
        int totalSize = frame.readInt();
        int commandSize = frame.readInt();
        byte[] commandBytes = new byte[commandSize];
        frame.readBytes(commandBytes);
        return PulsarProtobufCodec.decodeCommand(commandBytes);
    }

    /**
     * Send a Pulsar frame to the broker and return the raw response.
     */
    private ByteBuf sendFrame(ByteBuf frame) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ByteBuf> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new PulsarFrameDecoder());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<PulsarFrame>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, PulsarFrame msg) {
                                // Re-encode the response frame so we can parse it
                                ByteBuf raw = encodePulsarFrame(msg);
                                responseRef.set(raw);
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
        ch.writeAndFlush(frame);

        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue("Should receive response within timeout", received);
        assertNull("Should not have error", errorRef.get());
        return responseRef.get();
    }

    /**
     * Re-encode a PulsarFrame back into a raw ByteBuf for parsing.
     */
    private ByteBuf encodePulsarFrame(PulsarFrame pulsarFrame) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, pulsarFrame.command.type);

        byte[] commandBytes = cmdOut.toByteArray();
        int commandSize = commandBytes.length;
        int totalSize = 4 + commandSize;

        ByteBuf frame = Unpooled.buffer(4 + totalSize);
        frame.writeInt(totalSize);
        frame.writeInt(commandSize);
        frame.writeBytes(commandBytes);
        return frame;
    }
}
