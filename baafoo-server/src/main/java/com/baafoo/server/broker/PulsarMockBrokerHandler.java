package com.baafoo.server.broker;

import com.baafoo.server.broker.PulsarMessageStore.StoredMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** Per-connection state: producer ID counter. */
    private final AtomicInteger producerIdSeq = new AtomicInteger(0);

    /** Per-connection state: consumer ID counter. */
    private final AtomicInteger consumerIdSeq = new AtomicInteger(0);

    /** Maps producer name → topic (for routing SEND commands). */
    private final ConcurrentHashMap<String, String> producerTopics = new ConcurrentHashMap<String, String>();

    /** Maps consumer (topic:subscription) → consumer ID (for MESSAGE delivery). */
    private final ConcurrentHashMap<String, Integer> consumerIds = new ConcurrentHashMap<String, Integer>();

    /** Tracks whether the CONNECT handshake has completed. */
    private volatile boolean handshakeComplete;

    PulsarMockBrokerHandler(PulsarMessageStore messageStore, String brokerHost, int brokerPort) {
        this.messageStore = messageStore;
        this.brokerHost = brokerHost;
        this.brokerPort = brokerPort;
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
                case PulsarProtobufCodec.TYPE_CLOSE:
                    log.debug("Client closing connection");
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
        ctx.writeAndFlush(response);

        handshakeComplete = true;
        log.debug("Sent CONNECTED response");
    }

    private void handleLookup(ChannelHandlerContext ctx, PulsarCommand cmd) {
        String topic = cmd.topic;
        String brokerUrl = "pulsar://" + brokerHost + ":" + brokerPort;
        log.info("Pulsar LOOKUP: topic={}, returning brokerUrl={}", topic, brokerUrl);

        ByteBuf response = PulsarProtobufCodec.encodeLookupTopicResponse(brokerUrl, cmd.requestId);
        ctx.writeAndFlush(response);
    }

    private void handlePartitionedMetadata(ChannelHandlerContext ctx, PulsarCommand cmd) {
        String topic = cmd.topic;
        log.info("Pulsar PARTITIONED_METADATA: topic={}, returning non-partitioned", topic);

        // partitions=0 means non-partitioned topic
        ByteBuf response = PulsarProtobufCodec.encodePartitionMetadataResponse(0, cmd.requestId);
        ctx.writeAndFlush(response);
    }

    private void handleProducer(ChannelHandlerContext ctx, PulsarCommand cmd) {
        String topic = cmd.topic;
        String producerName = cmd.producerName;
        if (producerName == null || producerName.isEmpty()) {
            producerName = "baafoo-producer-" + producerIdSeq.getAndIncrement();
        }

        producerTopics.put(producerName, topic);
        log.info("Pulsar PRODUCER: topic={}, producerName={}", topic, producerName);

        ByteBuf response = PulsarProtobufCodec.encodeProducerSuccess(producerName, cmd.requestId);
        ctx.writeAndFlush(response);
    }

    private void handleSend(ChannelHandlerContext ctx, PulsarCommand cmd, byte[] payload) {
        String producerName = cmd.producerName;
        String topic = producerTopics.get(producerName);

        if (topic == null) {
            log.warn("SEND from unknown producer: {}", producerName);
            topic = "unknown-topic";
        }

        // Store the message
        StoredMessage stored = messageStore.storeMessage(topic, producerName, cmd.sequenceId, payload);
        log.info("Pulsar SEND: producer={}, topic={}, sequenceId={}, payloadSize={}",
                producerName, topic, cmd.sequenceId, payload.length);

        // Send receipt
        ByteBuf response = PulsarProtobufCodec.encodeSendReceipt(
                producerName, cmd.sequenceId, stored.ledgerId, stored.entryId);
        ctx.writeAndFlush(response);

        // Deliver to any active consumers for this topic
        deliverMessagesToConsumers(ctx, topic);
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
        ctx.writeAndFlush(response);
    }

    /**
     * Deliver pending messages to consumers for a given topic.
     */
    private void deliverMessagesToConsumers(ChannelHandlerContext ctx, String topic) {
        for (String consumerKey : consumerIds.keySet()) {
            if (!consumerKey.startsWith(topic + ":")) continue;

            int colonIdx = consumerKey.indexOf(':');
            String subscription = consumerKey.substring(colonIdx + 1);
            Integer consumerId = consumerIds.get(consumerKey);

            StoredMessage msg;
            while ((msg = messageStore.pollMessage(topic, subscription)) != null) {
                log.debug("Delivering message to consumer: topic={}, subscription={}, ledgerId={}, entryId={}",
                        topic, subscription, msg.ledgerId, msg.entryId);

                ByteBuf messageFrame = PulsarProtobufCodec.encodeMessage(
                        msg.ledgerId, msg.entryId, -1, topic, consumerId, msg.payload);
                ctx.writeAndFlush(messageFrame);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("PulsarMockBrokerHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
