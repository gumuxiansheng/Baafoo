package com.baafoo.server.broker;

import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.broker.PulsarMessageStore.StoredMessage;
import com.baafoo.server.handler.AgentResolver;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty handler that processes Pulsar binary protocol commands.
 *
 * <p>Implements the core Pulsar mock broker logic:
 * <ul>
 *   <li>CONNECT → CONNECTED handshake</li>
 *   <li>LOOKUP → LOOKUP_RESPONSE (redirect to self)</li>
 *   <li>PARTITIONED_METADATA → response (non-partitioned)</li>
 *   <li>PRODUCER → PRODUCER_SUCCESS</li>
 *   <li>SEND → SEND_RECEIPT</li>
 *   <li>SUBSCRIBE → SUCCESS + MESSAGE delivery</li>
 *   <li>FLOW → trigger message delivery</li>
 *   <li>GET_TOPICS_OF_NAMESPACE → response</li>
 *   <li>PING → PONG</li>
 * </ul></p>
 */
class PulsarMockBrokerHandler extends SimpleChannelInboundHandler<PulsarFrame> {

    private static final Logger log = LoggerFactory.getLogger(PulsarMockBrokerHandler.class);

    private final PulsarMessageStore messageStore;
    private final String brokerHost;
    private final int brokerPort;
    private final String advertisedHost;
    private final MqMatchHelper matchHelper;
    private final StorageService storage;

    /** Per-connection state: producer ID counter. */
    private final AtomicInteger producerIdSeq = new AtomicInteger(0);

    /** Per-connection state: consumer ID counter. */
    private final AtomicInteger consumerIdSeq = new AtomicInteger(0);

    /** Maps producer ID → topic (for routing SEND commands). */
    private final ConcurrentHashMap<Long, String> producerTopics = new ConcurrentHashMap<Long, String>();

    /** Maps producer name → producer ID (for PRODUCER_SUCCESS response). */
    private final ConcurrentHashMap<Long, String> producerNames = new ConcurrentHashMap<Long, String>();

    /** Maps consumer (topic:subscription) → consumer ID (for MESSAGE delivery). */
    private final ConcurrentHashMap<String, Integer> consumerIds = new ConcurrentHashMap<String, Integer>();

    /** Tracks whether the CONNECT handshake has completed. */
    private volatile boolean handshakeComplete;

    PulsarMockBrokerHandler(PulsarMessageStore messageStore, StorageService storage,
                            String brokerHost, int brokerPort, String advertisedHost) {
        this.messageStore = messageStore;
        this.storage = storage;
        this.brokerHost = brokerHost;
        this.brokerPort = brokerPort;
        this.advertisedHost = advertisedHost;
        this.matchHelper = new MqMatchHelper(storage);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("Pulsar client connected: {}", ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, PulsarFrame frame) {
        PulsarCommand cmd = frame.command;
        log.debug("Received Pulsar command: {}", cmd);

        try {
            switch (cmd.type) {
                case PulsarProtobufCodec.TYPE_CONNECT:
                    handleConnect(ctx, cmd);
                    break;
                case PulsarProtobufCodec.TYPE_LOOKUP:
                    handleLookup(ctx, cmd);
                    break;
                case PulsarProtobufCodec.TYPE_PARTITIONED_METADATA:
                    handlePartitionedMetadata(ctx, cmd);
                    break;
                case PulsarProtobufCodec.TYPE_PRODUCER:
                    handleProducer(ctx, cmd);
                    break;
                case PulsarProtobufCodec.TYPE_SEND:
                    handleSend(ctx, cmd, frame.payload);
                    break;
                case PulsarProtobufCodec.TYPE_SUBSCRIBE:
                    handleSubscribe(ctx, cmd);
                    break;
                case PulsarProtobufCodec.TYPE_FLOW:
                    handleFlow(ctx, cmd);
                    break;
                case PulsarProtobufCodec.TYPE_GET_TOPICS_OF_NAMESPACE:
                    handleGetTopicsOfNamespace(ctx, cmd);
                    break;
                case PulsarProtobufCodec.TYPE_PING:
                    handlePing(ctx);
                    break;
                case PulsarProtobufCodec.TYPE_ACK:
                    // ACK from consumer — just log
                    log.debug("Received ACK for sequenceId={}", cmd.sequenceId);
                    break;
                case PulsarProtobufCodec.TYPE_CLOSE_PRODUCER:
                case PulsarProtobufCodec.TYPE_CLOSE_CONSUMER:
                    log.debug("Client closing producer/consumer");
                    ctx.close();
                    break;
                default:
                    log.warn("Unhandled Pulsar command type: {}", cmd.type);
                    break;
            }
        } catch (Exception e) {
            log.error("Error handling Pulsar command {}: {}", cmd, e.getMessage(), e);
            ctx.close();
        }
    }

    private void handleConnect(ChannelHandlerContext ctx, PulsarCommand cmd) {
        log.info("Pulsar CONNECT: clientVersion={}, protocolVersion={}", cmd.clientVersion, cmd.protocolVersion);

        ByteBuf response = PulsarProtobufCodec.encodeConnected("Baafoo-Pulsar-Mock/1.0", cmd.protocolVersion > 0 ? cmd.protocolVersion : 12);
        logResponse("CONNECTED", response);
        ctx.writeAndFlush(response);

        handshakeComplete = true;
        log.debug("Sent CONNECTED response");
    }

    private void handleLookup(ChannelHandlerContext ctx, PulsarCommand cmd) {
        String topic = cmd.topic;
        // Determine the broker URL that the client can actually reach.
        // When the client connects from outside Docker (via port mapping), the
        // brokerHost (container IP) is not reachable. In that case, we return the
        // IP that the client actually connected to, which is the host-side address.
        String host = resolveClientReachableHost(ctx);
        String brokerUrl = "pulsar://" + host + ":" + brokerPort;
        log.info("Pulsar LOOKUP: topic={}, returning brokerUrl={}", topic, brokerUrl);

        ByteBuf response = PulsarProtobufCodec.encodeLookupTopicResponse(brokerUrl, cmd.requestId);
        logResponse("LOOKUP_RESPONSE", response);
        ctx.writeAndFlush(response);
    }

    /**
     * Resolve a host that the current client can reach for the LOOKUP response.
     * Delegates to NetworkUtils for Docker gateway detection.
     */
    private String resolveClientReachableHost(ChannelHandlerContext ctx) {
        java.net.InetSocketAddress remote = (java.net.InetSocketAddress) ctx.channel().remoteAddress();
        java.net.InetSocketAddress local = (java.net.InetSocketAddress) ctx.channel().localAddress();
        return com.baafoo.core.util.NetworkUtils.resolveClientReachableHost(
                remote, local, brokerHost, advertisedHost);
    }

    private void handlePartitionedMetadata(ChannelHandlerContext ctx, PulsarCommand cmd) {
        String topic = cmd.topic;
        log.info("Pulsar PARTITIONED_METADATA: topic={}, returning non-partitioned", topic);

        // partitions=0 means non-partitioned topic
        ByteBuf response = PulsarProtobufCodec.encodePartitionMetadataResponse(0, cmd.requestId);
        logResponse("PARTITIONED_METADATA_RESPONSE", response);
        ctx.writeAndFlush(response);
    }

    private void handleProducer(ChannelHandlerContext ctx, PulsarCommand cmd) {
        String topic = cmd.topic;
        long producerId = cmd.producerId;
        String producerName = cmd.producerName;
        if (producerName == null || producerName.isEmpty()) {
            producerName = "baafoo-producer-" + producerIdSeq.getAndIncrement();
        }

        producerTopics.put(producerId, topic);
        producerNames.put(producerId, producerName);
        log.info("Pulsar PRODUCER: topic={}, producerId={}, producerName={}", topic, producerId, producerName);

        ByteBuf response = PulsarProtobufCodec.encodeProducerSuccess(producerName, cmd.requestId);
        logResponse("PRODUCER_SUCCESS", response);
        ctx.writeAndFlush(response);
    }

    private void handleSend(ChannelHandlerContext ctx, PulsarCommand cmd, byte[] payload) {
        long producerId = cmd.producerId;
        String producerName = producerNames.get(producerId);
        String topic = producerTopics.get(producerId);

        if (topic == null) {
            log.warn("SEND from unknown producerId: {}", producerId);
            topic = "unknown-topic";
        }
        if (producerName == null) {
            producerName = "unknown-producer";
        }

        // Strip the Pulsar MessageMetadata prefix to get the real business payload.
        // Wire format: magic(1, 0x0E) + varint(metadataSize) + MessageMetadata(protobuf) + body
        MetadataInfo meta = parseMessageMetadata(payload);
        String bodyStr = null;
        if (meta != null && meta.body != null) {
            // Strip null bytes — PostgreSQL rejects 0x00 in UTF8 text columns
            byte[] cleaned = stripNullBytes(meta.body);
            bodyStr = new String(cleaned, StandardCharsets.UTF_8);
        }

        // Rule match + record + stub injection.
        AgentResolver.AgentInfo agentInfo = matchHelper.resolveAgent(ctx);
        EnvironmentMode mode = matchHelper.resolveMode(ctx);
        List<Rule> rules = matchHelper.filterRulesByEnvironment(storage.listRules(), agentInfo.environment);
        log.debug("Pulsar handleSend: agentInfo.env={}, mode={}, rulesCount={}",
                agentInfo.environment, mode, rules.size());
        boolean shouldRecord = (mode == EnvironmentMode.RECORD || mode == EnvironmentMode.RECORD_AND_STUB);

        MatchEngine.MatchResult m = matchHelper.match(rules, "pulsar", topic, bodyStr);

        byte[] storedPayload = payload; // default: original payload as produced
        if (m.isMatched()) {
            if (shouldRecord) {
                // Producer SEND: requestBody = original message, responseBody = stub body (if stub mode)
                String respBody = null;
                if ((mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB)
                        && m.getResponse() != null && m.getResponse().getBody() != null) {
                    respBody = m.getResponse().getBody();
                }
                matchHelper.record(m.getRule().getId(), "pulsar", topic, bodyStr, respBody, agentInfo);
            }
            // STUB / RECORD_AND_STUB: rebuild the payload with the rule's response body so
            // consumers decode a stub message instead of the producer's original body.
            if (mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB) {
                ResponseEntry resp = m.getResponse();
                if (resp != null && resp.getBody() != null) {
                    storedPayload = rebuildPayloadWithBody(meta, resp.getBody().getBytes(StandardCharsets.UTF_8));
                }
            }
        } else {
            if (shouldRecord) {
                matchHelper.record(null, "pulsar", topic, bodyStr, null, agentInfo);
            }
        }

        // Store the (possibly stubbed) message.
        StoredMessage stored = messageStore.storeMessage(topic, producerName, cmd.sequenceId, storedPayload);
        log.info("Pulsar SEND: producerId={}, topic={}, sequenceId={}, payloadSize={}, matched={}",
                producerId, topic, cmd.sequenceId, payload.length, m.isMatched());

        // Send receipt — use producerId (field 1) and sequenceId (field 2)
        ByteBuf response = PulsarProtobufCodec.encodeSendReceipt(
                producerId, cmd.sequenceId, stored.ledgerId, stored.entryId);
        logResponse("SEND_RECEIPT", response);
        ctx.writeAndFlush(response);

        // Deliver to any active consumers for this topic
        deliverMessagesToConsumers(ctx, topic);
    }

    /** Captured MessageMetadata framing so a stub body can reuse the original prefix. */
    private static final class MetadataInfo {
        final byte[] metadataPrefix; // magic + varint-size + metadata bytes
        final byte[] body;           // business payload (after metadata)
        MetadataInfo(byte[] metadataPrefix, byte[] body) {
            this.metadataPrefix = metadataPrefix;
            this.body = body;
        }
    }

    /**
     * Parse the Pulsar payload framing: {@code magic(1) + varint(metadataSize) +
     * MessageMetadata + body}. Returns the metadata prefix bytes and the real body,
     * or null if the payload is not in the expected framing.
     */
    private MetadataInfo parseMessageMetadata(byte[] payload) {
        if (payload == null || payload.length < 2) return null;
        try {
            if (payload.length >= 2 && payload[0] == PULSAR_MAGIC && payload[1] == 0x01) {
                // Full 0x0E01 magic — CRC32C is present.
                // Format: [0x0E01][4-byte CRC32C][4-byte big-endian metadataSize][MessageMetadata][body]
                int idx = 2 + 4; // skip magic + CRC32C
                if (idx + 4 > payload.length) return null;
                // Read 4-byte big-endian metadataSize
                int metaSize = ((payload[idx] & 0xFF) << 24)
                             | ((payload[idx + 1] & 0xFF) << 16)
                             | ((payload[idx + 2] & 0xFF) << 8)
                             | (payload[idx + 3] & 0xFF);
                idx += 4;
                if (metaSize < 0 || idx + metaSize > payload.length) return null;
                log.debug("parseMessageMetadata (0x0E01): payloadLen={}, metaSize={}, bodyStart={}",
                        payload.length, metaSize, idx + metaSize);
                byte[] prefix = new byte[idx + metaSize];
                System.arraycopy(payload, 0, prefix, 0, prefix.length);
                int bodyLen = payload.length - prefix.length;
                byte[] body = new byte[bodyLen];
                System.arraycopy(payload, prefix.length, body, 0, bodyLen);
                return new MetadataInfo(prefix, body);
            } else if (payload.length >= 1 && payload[0] == PULSAR_MAGIC) {
                // 0x0E only (no CRC32C) — old format
                // Format: [0x0E][varint metadataSize][MessageMetadata][body]
                int idx = 1;
                int metaSize = readVarint(payload, idx);
                if (metaSize < 0) return null;
                int varintBytes = varintSize(metaSize);
                idx += varintBytes;
                if (idx + metaSize > payload.length) return null;
                log.debug("parseMessageMetadata (0x0E): payloadLen={}, metaSize={}, bodyStart={}",
                        payload.length, metaSize, idx + metaSize);
                byte[] prefix = new byte[idx + metaSize];
                System.arraycopy(payload, 0, prefix, 0, prefix.length);
                int bodyLen = payload.length - prefix.length;
                byte[] body = new byte[bodyLen];
                System.arraycopy(payload, prefix.length, body, 0, bodyLen);
                return new MetadataInfo(prefix, body);
            } else {
                // No magic byte — try to parse as raw [varint metadataSize][metadata][body]
                int metaSize = readVarint(payload, 0);
                if (metaSize <= 0) return null;
                int varintBytes = varintSize(metaSize);
                if (varintBytes + metaSize >= payload.length) return null;
                log.debug("parseMessageMetadata (no magic): payloadLen={}, metaSize={}, bodyStart={}",
                        payload.length, metaSize, varintBytes + metaSize);
                byte[] prefix = new byte[varintBytes + metaSize];
                System.arraycopy(payload, 0, prefix, 0, prefix.length);
                int bodyLen = payload.length - prefix.length;
                byte[] body = new byte[bodyLen];
                System.arraycopy(payload, prefix.length, body, 0, bodyLen);
                return new MetadataInfo(prefix, body);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Find the end of a protobuf message in a byte array starting at the given offset.
     * Protobuf fields are: (varint tag + value) pairs. Varint tags encode (field_number << 3 | wire_type).
     * Wire types: 0=varint, 1=64-bit, 2=length-delimited, 5=32-bit.
     * We scan until we encounter a byte that doesn't look like a valid protobuf tag
     * or we've consumed all plausible fields.
     */
    private static int findProtobufEnd(byte[] data, int offset) {
        int idx = offset;
        while (idx < data.length) {
            int tagStart = idx;
            int tag = readVarint(data, idx);
            if (tag < 0) break;
            idx += varintSize(tag);
            int wireType = tag & 0x07;
            int fieldNumber = tag >>> 3;
            if (fieldNumber == 0 || fieldNumber > 100) break; // invalid field number

            switch (wireType) {
                case 0: // varint
                    int v = readVarint(data, idx);
                    if (v < 0) return tagStart;
                    idx += varintSize(v);
                    break;
                case 1: // 64-bit fixed
                    if (idx + 8 > data.length) return tagStart;
                    idx += 8;
                    break;
                case 2: // length-delimited
                    int len = readVarint(data, idx);
                    if (len < 0) return tagStart;
                    idx += varintSize(len);
                    if (idx + len > data.length) return tagStart;
                    idx += len;
                    break;
                case 5: // 32-bit fixed
                    if (idx + 4 > data.length) return tagStart;
                    idx += 4;
                    break;
                default:
                    return tagStart; // unknown wire type — end of metadata
            }
        }
        return idx;
    }

    private static int readVarint(byte[] data, int offset) {
        int value = 0;
        int shift = 0;
        int idx = offset;
        int b;
        do {
            if (idx >= data.length) return -1;
            b = data[idx++] & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    private static int varintSize(int value) {
        int size = 0;
        int v = value;
        do {
            size++;
            v >>>= 7;
        } while (v != 0);
        return size;
    }

    /**
     * Rebuild a Pulsar payload by keeping the original metadata and replacing
     * the body with the stub body. Falls back to a minimal metadata+body
     * framing when the original metadata could not be parsed.
     *
     * Wire format (0x0E01): [0x0E01][4-byte CRC32C][4-byte big-endian metadataSize][MessageMetadata][body]
     */
    /**
     * Rebuild a Pulsar payload with a new body, stripping batch metadata
     * so the consumer treats it as a single (non-batch) message.
     *
     * <p>Pulsar producers default to enableBatching=true. When batching is on,
     * MessageMetadata contains num_messages_in_batch (field 11) and the body
     * is encoded as [SingleMessageMetadata+payload] per message. When we
     * replace the body with a stub, we must either (a) strip the batch flag
     * so the consumer reads the body as-is, or (b) re-encode the stub body
     * in batch format. Option (a) is simpler and sufficient for mocking.</p>
     *
     * Wire format (0x0E01): [0x0E01][4-byte CRC32C][4-byte big-endian metadataSize][MessageMetadata][body]
     */
    private byte[] rebuildPayloadWithBody(MetadataInfo meta, byte[] stubBody) {
        // Always rebuild MessageMetadata from scratch — strip num_messages_in_batch (field 11)
        // and any batch-related fields so the consumer processes a plain (non-batch) message.
        //
        // MessageMetadata (Pulsar 2.10.x PulsarApi.proto):
        //   required string producer_name   = 1;
        //   required uint64 sequence_id     = 2;
        //   required uint64 publish_time    = 3;
        //   repeated KeyValue properties     = 4;
        //   optional CompressionType compression = 8;
        // We intentionally omit field 11 (num_messages_in_batch) and field 27/28/29 (chunk fields)
        // so the consumer treats the body as a single, uncompressed message.

        String producerName = "baafoo-stub";
        long sequenceId = 0;
        long publishTime = System.currentTimeMillis();

        // Try to extract original metadata fields for a more realistic stub
        if (meta != null && meta.metadataPrefix != null && meta.metadataPrefix.length > 0) {
            int prefixHeaderLen = 2 + 4; // magic(2) + CRC32C(4)
            if (meta.metadataPrefix.length > prefixHeaderLen + 4) { // +4 for metadataSize
                int metaSize = ((meta.metadataPrefix[prefixHeaderLen] & 0xFF) << 24)
                             | ((meta.metadataPrefix[prefixHeaderLen + 1] & 0xFF) << 16)
                             | ((meta.metadataPrefix[prefixHeaderLen + 2] & 0xFF) << 8)
                             | (meta.metadataPrefix[prefixHeaderLen + 3] & 0xFF);
                int metaStart = prefixHeaderLen + 4;
                if (metaStart + metaSize <= meta.metadataPrefix.length) {
                    // Parse key fields from original MessageMetadata
                    int[] pos = {metaStart};
                    byte[] data = meta.metadataPrefix;
                    int end = metaStart + metaSize;
                    while (pos[0] < end) {
                        int tag = readVarintAt(data, pos);
                        if (tag < 0) break;
                        int fieldNumber = tag >>> 3;
                        int wireType = tag & 0x7;
                        switch (fieldNumber) {
                            case 1: // producer_name (string/bytes)
                                if (wireType == 2) {
                                    byte[] name = readBytesField(data, pos);
                                    if (name != null) producerName = new String(name, StandardCharsets.UTF_8);
                                } else { skipMetadataField(data, pos, wireType); }
                                break;
                            case 2: // sequence_id (varint)
                                if (wireType == 0) sequenceId = readVarint64From(data, pos);
                                else skipMetadataField(data, pos, wireType);
                                break;
                            case 3: // publish_time (varint)
                                if (wireType == 0) publishTime = readVarint64From(data, pos);
                                else skipMetadataField(data, pos, wireType);
                                break;
                            default:
                                // Skip all other fields (including field 11 = num_messages_in_batch)
                                skipMetadataField(data, pos, wireType);
                                break;
                        }
                    }
                }
            }
        }

        // Rebuild clean MessageMetadata (no batch fields)
        ByteArrayOutputStream metaOut = new ByteArrayOutputStream();
        PulsarProtobufCodec.writeBytesField(metaOut, 1, producerName.getBytes(StandardCharsets.UTF_8));
        PulsarProtobufCodec.writeVarintField64(metaOut, 2, sequenceId);
        PulsarProtobufCodec.writeVarintField64(metaOut, 3, publishTime);

        byte[] metadataBytes = metaOut.toByteArray();
        // Wire format: [4-byte big-endian metadataSize][metadata bytes][body]
        byte[] metaAndBody = new byte[4 + metadataBytes.length + stubBody.length];
        int metaSize = metadataBytes.length;
        metaAndBody[0] = (byte) (metaSize >>> 24);
        metaAndBody[1] = (byte) (metaSize >>> 16);
        metaAndBody[2] = (byte) (metaSize >>> 8);
        metaAndBody[3] = (byte) metaSize;
        System.arraycopy(metadataBytes, 0, metaAndBody, 4, metadataBytes.length);
        System.arraycopy(stubBody, 0, metaAndBody, 4 + metadataBytes.length, stubBody.length);

        int checksum = computeCrc32c(metaAndBody, 0, metaAndBody.length);

        byte[] result = new byte[2 + 4 + metaAndBody.length];
        result[0] = PULSAR_MAGIC;  // 0x0E
        result[1] = 0x01;          // 0x01 (full magic, indicates CRC32C present)
        result[2] = (byte) (checksum >>> 24);
        result[3] = (byte) (checksum >>> 16);
        result[4] = (byte) (checksum >>> 8);
        result[5] = (byte) checksum;
        System.arraycopy(metaAndBody, 0, result, 6, metaAndBody.length);
        log.debug("rebuildPayloadWithBody: producerName={}, seqId={}, stubBodySize={}, resultLen={}",
                producerName, sequenceId, stubBody.length, result.length);
        return result;
    }

    /** Read a length-delimited bytes field at the current position. */
    private static byte[] readBytesField(byte[] data, int[] pos) {
        int len = readVarintAt(data, pos);
        if (len < 0 || pos[0] + len > data.length) return null;
        byte[] result = new byte[len];
        System.arraycopy(data, pos[0], result, 0, len);
        pos[0] += len;
        return result;
    }

    /** Read a varint at the current position, updating pos[0]. */
    private static int readVarintAt(byte[] data, int[] pos) {
        int value = 0;
        int shift = 0;
        int b;
        do {
            if (pos[0] >= data.length) return -1;
            b = data[pos[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }
        if (len < 0 || pos[0] + len > data.length) return null;
        byte[] result = new byte[len];
        System.arraycopy(data, pos[0], result, 0, len);
        pos[0] += len;
        return result;
    }

    /** Read a varint64 value at the current position (unsigned). */
    private static long readVarint64From(byte[] data, int[] pos) {
        long value = 0;
        int shift = 0;
        int b;
        do {
            if (pos[0] >= data.length) return 0;
            b = data[pos[0]++] & 0xFF;
            value |= ((long) (b & 0x7F)) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    /** Skip a single protobuf field based on wire type. */
    private static void skipMetadataField(byte[] data, int[] pos, int wireType) {
        switch (wireType) {
            case 0: // varint
                while (pos[0] < data.length && (data[pos[0]++] & 0x80) != 0) {}
                break;
            case 1: // 64-bit fixed
                pos[0] += 8;
                break;
            case 2: // length-delimited
                int len = readVarintAt(data, pos);
                if (len >= 0) pos[0] += len;
                break;
            case 5: // 32-bit fixed
                pos[0] += 4;
                break;
            default:
                // Unknown wire type — cannot skip safely
                pos[0] = data.length; // force exit
                break;
        }
    }

    private static final byte PULSAR_MAGIC = (byte) 0x0E;

    /** CRC32C lookup table (Castagnoli polynomial 0x1EDC6F41) */
    private static final int[] CRC32C_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0x82F63B78; // reflected polynomial
                } else {
                    crc >>>= 1;
                }
            }
            CRC32C_TABLE[i] = crc;
        }
    }

    private static int computeCrc32c(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = CRC32C_TABLE[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }

    /** Strip null bytes (0x00) from a byte array — PostgreSQL rejects them in UTF8 text. */
    private static byte[] stripNullBytes(byte[] data) {
        int count = 0;
        for (byte b : data) {
            if (b != 0) count++;
        }
        if (count == data.length) return data;
        byte[] result = new byte[count];
        int idx = 0;
        for (byte b : data) {
            if (b != 0) result[idx++] = b;
        }
        return result;
    }

    private void handleSubscribe(ChannelHandlerContext ctx, PulsarCommand cmd) {
        String topic = cmd.topic;
        String subscription = cmd.subscription;
        int consumerId = consumerIdSeq.getAndIncrement();

        String consumerKey = topic + ":" + subscription;
        consumerIds.put(consumerKey, consumerId);

        log.info("Pulsar SUBSCRIBE: topic={}, subscription={}, consumerId={}, subType={}",
                topic, subscription, consumerId, cmd.subType);

        // Register the subscription and get any existing messages
        messageStore.registerSubscription(topic, subscription);

        // Send SUCCESS response
        ByteBuf response = PulsarProtobufCodec.encodeSubscribeSuccess(cmd.requestId);
        ctx.writeAndFlush(response);

        // Deliver any pending messages for this subscription
        deliverMessagesToConsumers(ctx, topic);
    }

    private void handleFlow(ChannelHandlerContext ctx, PulsarCommand cmd) {
        int permits = cmd.messagePermits;
        log.debug("Pulsar FLOW: permits={}", permits);

        // Deliver messages for all active subscriptions on this connection
        for (String consumerKey : consumerIds.keySet()) {
            int colonIdx = consumerKey.indexOf(':');
            if (colonIdx > 0) {
                String topic = consumerKey.substring(0, colonIdx);
                deliverMessagesToConsumers(ctx, topic);
            }
        }
    }

    private void handleGetTopicsOfNamespace(ChannelHandlerContext ctx, PulsarCommand cmd) {
        String namespace = cmd.namespaceName;
        log.info("Pulsar GET_TOPICS_OF_NAMESPACE: namespace={}", namespace);

        List<String> topics = messageStore.getTopicsOfNamespace(namespace);
        if (topics.isEmpty()) {
            // If no topics from rules, return a default topic based on the namespace
            topics.add("persistent://" + namespace + "/default-topic");
        }

        ByteBuf response = PulsarProtobufCodec.encodeGetTopicsOfNamespaceResponse(topics, cmd.requestId);
        ctx.writeAndFlush(response);
    }

    private void handlePing(ChannelHandlerContext ctx) {
        log.debug("Pulsar PING → PONG");
        ByteBuf response = PulsarProtobufCodec.encodePong();
        logResponse("PONG", response);
        ctx.writeAndFlush(response);
    }

    /**
     * Deliver pending messages to consumers for a given topic.
     * If no stored messages exist and STUB mode is active, matches rules
     * and delivers the rule's response body as a mock message.
     */
    private void deliverMessagesToConsumers(ChannelHandlerContext ctx, String topic) {
        for (String consumerKey : consumerIds.keySet()) {
            if (!consumerKey.startsWith(topic + ":")) continue;

            int colonIdx = consumerKey.indexOf(':');
            String subscription = consumerKey.substring(colonIdx + 1);
            Integer consumerId = consumerIds.get(consumerKey);

            StoredMessage msg;
            boolean delivered = false;
            while ((msg = messageStore.pollMessage(topic, subscription)) != null) {
                log.debug("Delivering message to consumer: topic={}, subscription={}, ledgerId={}, entryId={}",
                        topic, subscription, msg.ledgerId, msg.entryId);

                ByteBuf messageFrame = PulsarProtobufCodec.encodeMessage(
                        msg.ledgerId, msg.entryId, -1, topic, consumerId, msg.payload);
                ctx.writeAndFlush(messageFrame);
                delivered = true;
            }

            // If no stored messages, try rule-based stub delivery
            if (!delivered) {
                AgentResolver.AgentInfo agentInfo = matchHelper.resolveAgent(ctx);
                EnvironmentMode mode = matchHelper.resolveMode(ctx);
                if (mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB) {
                    List<Rule> rules = matchHelper.filterRulesByEnvironment(storage.listRules(), agentInfo.environment);
                    MatchEngine.MatchResult m = matchHelper.match(rules, "pulsar", topic, null);
                    if (m.isMatched()) {
                        ResponseEntry resp = m.getResponse();
                        if (resp != null && resp.getBody() != null) {
                            byte[] stubBody = resp.getBody().getBytes(StandardCharsets.UTF_8);
                            byte[] stubPayload = rebuildPayloadWithBody(null, stubBody);
                            StoredMessage stubMsg = messageStore.storeMessage(
                                    topic, "baafoo-stub-producer", 0, stubPayload);
                            if (mode == EnvironmentMode.RECORD_AND_STUB) {
                                // Consumer receives stub: requestBody = null, responseBody = stub body
                                matchHelper.record(m.getRule().getId(), "pulsar", topic, null, resp.getBody(), agentInfo);
                            }
                            log.info("Pulsar Subscribe stub: topic={}, matched rule={}, stubBodySize={}",
                                    topic, m.getRule().getId(), stubBody.length);
                            ByteBuf messageFrame = PulsarProtobufCodec.encodeMessage(
                                    stubMsg.ledgerId, stubMsg.entryId, -1, topic, consumerId, stubMsg.payload);
                            ctx.writeAndFlush(messageFrame);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("PulsarMockBrokerHandler error: {}", cause.getMessage());
        ctx.close();
    }

    private void logResponse(String label, ByteBuf buf) {
        if (!log.isDebugEnabled()) return;
        int readerIndex = buf.readerIndex();
        byte[] bytes = new byte[Math.min(buf.readableBytes(), 64)];
        buf.getBytes(readerIndex, bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x ", b & 0xFF));
        }
        log.debug("Pulsar response {}: {} bytes, hex={}", label, buf.readableBytes(), hex);
    }
}
