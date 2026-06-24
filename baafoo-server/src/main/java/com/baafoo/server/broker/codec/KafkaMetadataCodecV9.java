package com.baafoo.server.broker.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Flexible Kafka Metadata API codec (v9, KIP-482).
 *
 * <p>Uses compact strings, compact arrays, and tag buffers as defined by
 * KIP-482 flexible versions. This codec is used when the client negotiates
 * Metadata v9 (the flexible threshold).
 */
public final class KafkaMetadataCodecV9 {

    private KafkaMetadataCodecV9() {}

    /**
     * Parse a flexible Metadata v9 request body.
     */
    public static KafkaMetadataCodec.MetadataRequest parseRequest(ByteBuf msg) {
        // Topics: compact nullable array
        int topicCount = KafkaFlexibleCodec.readCompactArrayLength(msg);
        List<String> topics = new ArrayList<String>();
        if (topicCount > 0) {
            for (int i = 0; i < topicCount; i++) {
                topics.add(KafkaFlexibleCodec.readCompactStringNotNullable(msg));
            }
        }
        boolean allowAutoTopicCreation = msg.readBoolean();
        KafkaFlexibleCodec.skipTagBuffer(msg); // top-level tag buffer
        return new KafkaMetadataCodec.MetadataRequest(topics, allowAutoTopicCreation);
    }

    /**
     * Serialize a flexible Metadata v9 response.
     *
     * @param correlationId the correlation id from the request
     * @param brokerHost    the host the client should connect to
     * @param brokerPort    the port the client should connect to
     * @param topics        the requested topic names
     * @return the framed response buffer
     */
    public static ByteBuf serializeResponse(int correlationId,
                                            String brokerHost, int brokerPort,
                                            List<String> topics) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);
        // Response Header v1 tag buffer (flexible versions, KIP-482)
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf);

        // throttle_time_ms
        buf.writeInt(0);

        // Brokers: compact array (1 broker = ourselves)
        KafkaFlexibleCodec.writeCompactArrayLength(buf, 1);
        buf.writeInt(0); // broker_id
        KafkaFlexibleCodec.writeCompactStringNotNullable(buf, brokerHost);
        buf.writeInt(brokerPort);
        // rack: compact nullable string
        KafkaFlexibleCodec.writeCompactString(buf, null);
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // per-broker tag buffer

        // cluster_id: compact nullable string
        KafkaFlexibleCodec.writeCompactString(buf, "baafoo-mock-cluster");

        // controller_id
        buf.writeInt(0);

        // Topic metadata: compact array
        KafkaFlexibleCodec.writeCompactArrayLength(buf, topics.size());
        for (String topic : topics) {
            buf.writeShort(KafkaProtocolVersions.NONE); // error_code
            KafkaFlexibleCodec.writeCompactStringNotNullable(buf, topic);
            buf.writeBoolean(false); // is_internal

            // 1 partition per topic: compact array
            KafkaFlexibleCodec.writeCompactArrayLength(buf, 1);
            buf.writeShort(KafkaProtocolVersions.NONE); // error_code
            buf.writeInt(0); // partition_index
            buf.writeInt(0); // leader_id
            // replica_nodes: compact array
            KafkaFlexibleCodec.writeCompactArrayLength(buf, 1);
            buf.writeInt(0);
            // isr_nodes: compact array
            KafkaFlexibleCodec.writeCompactArrayLength(buf, 1);
            buf.writeInt(0);
            // offline_replicas: compact array
            KafkaFlexibleCodec.writeCompactArrayLength(buf, 0);
            KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // per-partition tag buffer

            // Topic authorized operations (v8+)
            buf.writeInt(0);
            KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // per-topic tag buffer
        }

        // Cluster authorized operations (v8+)
        buf.writeInt(0);

        KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // top-level tag buffer

        return KafkaCodecUtils.frameResponse(buf);
    }
}
