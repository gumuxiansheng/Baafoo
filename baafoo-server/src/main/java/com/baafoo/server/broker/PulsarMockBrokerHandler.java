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
        String bodyStr = meta != null && meta.body != null
                ? new String(meta.body, StandardCharsets.UTF_8) : null;

        // Rule match + record + stub injection.
        AgentResolver.AgentInfo agentInfo = matchHelper.resolveAgent(ctx);
        EnvironmentMode mode = matchHelper.resolveMode(ctx);
        List<Rule> rules = matchHelper.filterRulesByEnvironment(storage.listRules(), agentInfo.environment);
        boolean shouldRecord = (mode == EnvironmentMode.RECORD || mode == EnvironmentMode.RECORD_AND_STUB);

        MatchEngine.MatchResult m = matchHelper.match(rules, "pulsar", topic, bodyStr);

        byte[] storedPayload = payload; // default: original payload as produced
        if (m.isMatched()) {
            if (shouldRecord) {
                matchHelper.record(m.getRule().getId(), "pulsar", topic, bodyStr, agentInfo);
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
                matchHelper.record(null, "pulsar", topic, bodyStr, agentInfo);
            }
        }

        // Store the (possibly stubbed) message.
        StoredMessage stored = messageStore.storeMessage(topic, producerName, cmd.sequenceId, storedPayload);
        log.info("Pulsar SEND: producerId={}, producerName={}, topic={}, sequenceId={}, payloadSize={}, matched={}",
                producerId, producerName, topic, cmd.sequenceId, payload.length, m.isMatched());

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
        // magic byte (0x0E) — some clients omit it; tolerate both.
        int idx = 0;
        if (payload[0] == PULSAR_MAGIC) {
            idx = 1;
        }
        try {
            // read varint = metadataSize
            int metaSize = 0;
            int shift = 0;
            int b;
            do {
                if (idx >= payload.length) return null;
                b = payload[idx++] & 0xFF;
                metaSize |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            if (metaSize < 0 || idx + metaSize > payload.length) return null;
            byte[] prefix = new byte[idx + metaSize];
            System.arraycopy(payload, 0, prefix, 0, prefix.length);
            int bodyLen = payload.length - prefix.length;
            byte[] body = new byte[bodyLen];
            System.arraycopy(payload, prefix.length, body, 0, bodyLen);
            return new MetadataInfo(prefix, body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Rebuild a Pulsar payload by keeping the original metadata prefix and replacing
     * the body with the stub body. Falls back to a minimal magic+empty-metadata+body
     * framing when the original metadata could not be parsed.
     */
    private byte[] rebuildPayloadWithBody(MetadataInfo meta, byte[] stubBody) {
        if (meta != null && meta.metadataPrefix != null && meta.metadataPrefix.length > 0) {
            byte[] result = new byte[meta.metadataPrefix.length + stubBody.length];
            System.arraycopy(meta.metadataPrefix, 0, result, 0, meta.metadataPrefix.length);
            System.arraycopy(stubBody, 0, result, meta.metadataPrefix.length, stubBody.length);
            return result;
        }
        // Fallback: magic(1) + metadataSize(1, varint 0) + body
        byte[] result = new byte[2 + stubBody.length];
        result[0] = PULSAR_MAGIC;
        result[1] = 0; // empty metadata (varint length = 0)
        System.arraycopy(stubBody, 0, result, 2, stubBody.length);
        return result;
    }

    private static final byte PULSAR_MAGIC = (byte) 0x0E;

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
                                matchHelper.record(m.getRule().getId(), "pulsar", topic, resp.getBody(), agentInfo);
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
