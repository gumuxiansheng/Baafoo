package com.baafoo.server.broker.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-flexible Kafka Metadata API codec (v0-v8).
 *
 * <p>Parses Metadata requests and serializes Metadata responses using the
 * int16-prefixed nullable string format. Supports all non-flexible versions
 * up to the flexible threshold (v9); Baafoo currently advertises v8 in
 * ApiVersions so clients stay in this format.
 */
public final class KafkaMetadataCodec {

    private KafkaMetadataCodec() {
    }

    /** Parsed Metadata request. */
    public static final class MetadataRequest {
        private final List<String> topics;
        private final boolean allowAutoTopicCreation;

        public MetadataRequest(List<String> topics, boolean allowAutoTopicCreation) {
            this.topics = topics;
            this.allowAutoTopicCreation = allowAutoTopicCreation;
        }

        public List<String> getTopics() {
            return topics;
        }

        public boolean isAllowAutoTopicCreation() {
            return allowAutoTopicCreation;
        }
    }

    /**
     * Parse a non-flexible Metadata request body.
     *
     * @param msg        the request body (after the request header)
     * @param apiVersion the Metadata request version
     * @return a parsed request DTO
     */
    public static MetadataRequest parseRequest(ByteBuf msg, short apiVersion) {
        int topicCount = msg.readInt();
        List<String> topics = new ArrayList<String>();
        if (topicCount >= 0) {
            for (int i = 0; i < topicCount; i++) {
                topics.add(KafkaCodecUtils.readNullableString(msg));
            }
        }
        boolean allowAutoTopicCreation = false;
        if (apiVersion >= 4 && msg.isReadable()) {
            allowAutoTopicCreation = msg.readByte() != 0;
        }
        return new MetadataRequest(topics, allowAutoTopicCreation);
    }

    /**
     * Serialize a non-flexible Metadata response.
     *
     * @param correlationId the correlation id from the request
     * @param apiVersion    the Metadata request version
     * @param brokerHost    the host the client should connect to
     * @param brokerPort    the port the client should connect to
     * @param topics        the requested topic names
     * @return the framed response buffer
     */
    public static ByteBuf serializeResponse(int correlationId, short apiVersion,
                                            String brokerHost, int brokerPort,
                                            List<String> topics) {
        ByteBuf buf = Unpooled.buffer();

        // Correlation ID.
        buf.writeInt(correlationId);

        // throttle_time_ms (v3+).
        if (apiVersion >= 3) {
            buf.writeInt(0);
        }

        // Brokers array (1 broker = ourselves).
        buf.writeInt(1);
        buf.writeInt(0); // broker_id
        KafkaCodecUtils.writeNullableString(buf, brokerHost);
        buf.writeInt(brokerPort);
        // rack (v1+).
        if (apiVersion >= 1) {
            KafkaCodecUtils.writeNullableString(buf, null);
        }

        // cluster_id (v2+).
        if (apiVersion >= 2) {
            KafkaCodecUtils.writeNullableString(buf, "baafoo-mock-cluster");
        }

        // controller_id (v1+).
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        // Topic metadata array.
        buf.writeInt(topics.size());
        for (String topic : topics) {
            buf.writeShort(KafkaProtocolVersions.NONE); // error_code
            KafkaCodecUtils.writeNullableString(buf, topic);
            if (apiVersion >= 1) {
                buf.writeBoolean(false); // is_internal
            }

            // 1 partition per topic.
            buf.writeInt(1);
            buf.writeShort(KafkaProtocolVersions.NONE); // error_code
            buf.writeInt(0); // partition_index
            buf.writeInt(0); // leader_id
            // replica_nodes.
            buf.writeInt(1);
            buf.writeInt(0);
            // isr_nodes.
            buf.writeInt(1);
            buf.writeInt(0);
            // offline_replicas (v5+).
            if (apiVersion >= 5) {
                buf.writeInt(0);
            }
            // Topic authorized operations (v8+).
            if (apiVersion >= 8) {
                buf.writeInt(0);
            }
        }

        // Cluster authorized operations (v8+).
        if (apiVersion >= 8) {
            buf.writeInt(0);
        }

        return KafkaCodecUtils.frameResponse(buf);
    }
}
