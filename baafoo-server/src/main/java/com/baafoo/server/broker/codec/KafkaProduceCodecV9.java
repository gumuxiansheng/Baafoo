package com.baafoo.server.broker.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Flexible Kafka Produce API codec (v9, KIP-482).
 *
 * <p>Uses compact strings, compact arrays, and tag buffers as defined by
 * KIP-482 flexible versions. This codec is used when the client negotiates
 * Produce v9 (the flexible threshold).
 */
public final class KafkaProduceCodecV9 {

    private KafkaProduceCodecV9() {}

    public static KafkaProduceCodec.ProduceRequest parseRequest(ByteBuf msg) {
        // transactional_id: compact nullable string
        KafkaFlexibleCodec.readCompactString(msg);
        short acks = msg.readShort();
        int timeoutMs = msg.readInt();

        // Topics: compact array
        int topicCount = KafkaFlexibleCodec.readCompactArrayLength(msg);
        if (topicCount < 0) topicCount = 0;
        List<KafkaProduceCodec.ProduceTopic> topics = new ArrayList<KafkaProduceCodec.ProduceTopic>();
        for (int t = 0; t < topicCount; t++) {
            String topic = KafkaFlexibleCodec.readCompactStringNotNullable(msg);
            int partitionCount = KafkaFlexibleCodec.readCompactArrayLength(msg);
            if (partitionCount < 0) partitionCount = 0;
            List<KafkaProduceCodec.ProducePartition> partitions = new ArrayList<KafkaProduceCodec.ProducePartition>();
            for (int p = 0; p < partitionCount; p++) {
                int partition = msg.readInt();
                byte[] batchData = KafkaFlexibleCodec.readCompactBytes(msg);
                partitions.add(new KafkaProduceCodec.ProducePartition(partition, batchData));
            }
            KafkaFlexibleCodec.skipTagBuffer(msg); // per-topic tag buffer
            topics.add(new KafkaProduceCodec.ProduceTopic(topic, partitions));
        }
        KafkaFlexibleCodec.skipTagBuffer(msg); // top-level tag buffer

        return new KafkaProduceCodec.ProduceRequest(acks, timeoutMs, topics);
    }

    public static ByteBuf serializeResponse(int correlationId,
                                            KafkaProduceCodec.ProduceResponse response,
                                            short errorCode) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // Topics: compact array
        KafkaFlexibleCodec.writeCompactArrayLength(buf, response.getTopics().size());
        for (KafkaProduceCodec.ProduceTopicResult topicResult : response.getTopics()) {
            KafkaFlexibleCodec.writeCompactStringNotNullable(buf, topicResult.getTopic());
            KafkaFlexibleCodec.writeCompactArrayLength(buf, topicResult.getPartitions().size());
            for (KafkaProduceCodec.ProducePartitionResult partResult : topicResult.getPartitions()) {
                buf.writeInt(partResult.getPartition()); // partition_index
                buf.writeShort(errorCode); // error_code
                buf.writeLong(partResult.getOffset()); // base_offset
                buf.writeLong(System.currentTimeMillis()); // log_append_time_ms
                buf.writeLong(0); // log_start_offset
                // RecordErrors: compact array (empty)
                KafkaFlexibleCodec.writeCompactArrayLength(buf, 0);
                // ErrorMessage: compact nullable string
                KafkaFlexibleCodec.writeCompactString(buf, null);
                KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // per-partition tag buffer
            }
            KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // per-topic tag buffer
        }

        buf.writeInt(0); // throttle_time_ms
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // top-level tag buffer

        return KafkaCodecUtils.frameResponse(buf);
    }
}
