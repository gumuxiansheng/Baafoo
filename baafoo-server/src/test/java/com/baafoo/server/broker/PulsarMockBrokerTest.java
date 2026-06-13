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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
                        ch.pipeline().addLast(new PulsarMockBrokerHandler(messageStore, "localhost", TEST_PORT));
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
        // Build a CONNECT command
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type field (field 1, varint)
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0); // tag
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_CONNECT); // value = 3

        // connect sub-message (field 5, length-delimited)
        ByteArrayOutputStream connectOut = new ByteArrayOutputStream();
        // auth_method_name (field 1, string)
        writeProtobufString(connectOut, 1, "");
        // auth_data (field 2, bytes)
        writeProtobufBytes(connectOut, 2, new byte[0]);
        // protocol_version (field 3, varint)
        PulsarProtobufCodec.writeVarint(connectOut, (3 << 3) | 0);
        PulsarProtobufCodec.writeVarint(connectOut, 12);
        // client_version (field 4, string)
        writeProtobufString(connectOut, 4, "test-client-1.0");

        byte[] connectBytes = connectOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (5 << 3) | 2); // tag for field 5, wire type 2
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
        // type = CONNECT (3)
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_CONNECT);

        // connect sub-message (field 5)
        ByteArrayOutputStream connectOut = new ByteArrayOutputStream();
        writeProtobufString(connectOut, 1, ""); // auth_method_name
        writeProtobufBytes(connectOut, 2, new byte[0]); // auth_data
        PulsarProtobufCodec.writeVarint(connectOut, (3 << 3) | 0); // protocol_version
        PulsarProtobufCodec.writeVarint(connectOut, protocolVersion);
        writeProtobufString(connectOut, 4, clientVersion); // client_version

        byte[] connectBytes = connectOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (5 << 3) | 2);
        PulsarProtobufCodec.writeVarint(cmdOut, connectBytes.length);
        try { cmdOut.write(connectBytes); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        return wrapInFrame(cmdOut.toByteArray(), new byte[0]);
    }

    private ByteBuf buildLookupFrame(String topic, int requestId) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type = LOOKUP (15)
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_LOOKUP);

        // lookupTopic sub-message (field 17)
        ByteArrayOutputStream lookupOut = new ByteArrayOutputStream();
        writeProtobufString(lookupOut, 1, topic); // topic
        PulsarProtobufCodec.writeVarint(lookupOut, (2 << 3) | 0); // authoritative
        PulsarProtobufCodec.writeVarint(lookupOut, 0);
        PulsarProtobufCodec.writeVarint(lookupOut, (3 << 3) | 0); // requestId
        PulsarProtobufCodec.writeVarint(lookupOut, requestId);

        byte[] lookupBytes = lookupOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (17 << 3) | 2);
        PulsarProtobufCodec.writeVarint(cmdOut, lookupBytes.length);
        try { cmdOut.write(lookupBytes); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        return wrapInFrame(cmdOut.toByteArray(), new byte[0]);
    }

    private ByteBuf buildPartitionMetadataFrame(String topic, int requestId) {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type = PARTITIONED_METADATA (16)
        PulsarProtobufCodec.writeVarint(cmdOut, (1 << 3) | 0);
        PulsarProtobufCodec.writeVarint(cmdOut, PulsarProtobufCodec.TYPE_PARTITIONED_METADATA);

        // partitionMetadataRequest sub-message (field 18)
        ByteArrayOutputStream metaOut = new ByteArrayOutputStream();
        writeProtobufString(metaOut, 1, topic); // topic
        PulsarProtobufCodec.writeVarint(metaOut, (2 << 3) | 0); // requestId
        PulsarProtobufCodec.writeVarint(metaOut, requestId);

        byte[] metaBytes = metaOut.toByteArray();
        PulsarProtobufCodec.writeVarint(cmdOut, (18 << 3) | 2);
        PulsarProtobufCodec.writeVarint(cmdOut, metaBytes.length);
        try { cmdOut.write(metaBytes); } catch (java.io.IOException e) { throw new RuntimeException(e); }

        return wrapInFrame(cmdOut.toByteArray(), new byte[0]);
    }

    private ByteBuf buildPingFrame() {
        ByteArrayOutputStream cmdOut = new ByteArrayOutputStream();
        // type = PING (1)
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
        // We need to re-encode the command. Since the PulsarCommand doesn't
        // keep the raw bytes, we'll just return a buffer with the command type.
        // For test purposes, we just need to read the type from the response.
        // Let's create a minimal frame from the command.
        //
        // Actually, a simpler approach: just return a buffer that contains
        // the raw frame data. But PulsarFrameDecoder already parsed it.
        // For testing, we can just check the command type directly.
        //
        // Let me change the approach: instead of re-encoding, just return
        // a buffer with the type info we need.

        // Actually, the simplest approach is to just encode a fake frame
        // with the command type. But that loses information.
        //
        // Better approach: modify the test to work with PulsarFrame directly.
        // But that requires changing the sendFrame method.
        //
        // For now, let me just encode the command type into a buffer.
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
