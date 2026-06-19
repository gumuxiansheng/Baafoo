package com.baafoo.server.broker.codec;

import com.baafoo.server.broker.KafkaMessageStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Flexible Kafka Fetch API codec (v12, KIP-482).
 *
 * <p>Uses compact strings, compact arrays, and tag buffers as defined by
 * KIP-482 flexible versions. This codec is used when the client negotiates
 * Fetch v12 (the flexible threshold).
 */
public final class KafkaFetchCodecV12 {

    private KafkaFetchCodecV12() {}

    public static KafkaFetchCodec.FetchRequest parseRequest(ByteBuf msg) {
        int replicaId = msg.readInt();
        int maxWaitMs = msg.readInt();
        int minBytes = msg.readInt();
        int maxBytes = msg.readInt();
        byte isolationLevel = msg.readByte();
        int sessionId = msg.readInt();
        int sessionEpoch = msg.readInt();

        // Topics: compact array
        int topicCount = KafkaFlexibleCodec.readCompactArrayLength(msg);
        if (topicCount < 0) topicCount = 0;
        List<KafkaFetchCodec.FetchTopic> topics = new ArrayList<KafkaFetchCodec.FetchTopic>();
        for (int t = 0; t < topicCount; t++) {
            String topic = KafkaFlexibleCodec.readCompactStringNotNullable(msg);
            int partitionCount = KafkaFlexibleCodec.readCompactArrayLength(msg);
            if (partitionCount < 0) partitionCount = 0;
            List<KafkaFetchCodec.FetchPartition> partitions = new ArrayList<KafkaFetchCodec.FetchPartition>();
            for (int p = 0; p < partitionCount; p++) {
                int partition = msg.readInt();
                int currentLeaderEpoch = msg.readInt();
                long fetchOffset = msg.readLong();
                int lastFetchedEpoch = msg.readInt();
                long logStartOffset = msg.readLong();
                int partitionMaxBytes = msg.readInt();
                KafkaFlexibleCodec.skipTagBuffer(msg); // per-partition tag buffer
                partitions.add(new KafkaFetchCodec.FetchPartition(partition, currentLeaderEpoch,
                        fetchOffset, logStartOffset, lastFetchedEpoch, partitionMaxBytes));
            }
            KafkaFlexibleCodec.skipTagBuffer(msg); // per-topic tag buffer
            topics.add(new KafkaFetchCodec.FetchTopic(topic, partitions));
        }

        // forgotten_topics_data: compact array
        List<KafkaFetchCodec.ForgottenTopic> forgottenTopics = new ArrayList<KafkaFetchCodec.ForgottenTopic>();
        int forgottenCount = KafkaFlexibleCodec.readCompactArrayLength(msg);
        if (forgottenCount < 0) forgottenCount = 0;
        for (int f = 0; f < forgottenCount; f++) {
            String topic = KafkaFlexibleCodec.readCompactStringNotNullable(msg);
            int fpCount = KafkaFlexibleCodec.readCompactArrayLength(msg);
            if (fpCount < 0) fpCount = 0;
            List<Integer> fPartitions = new ArrayList<Integer>();
            for (int fp = 0; fp < fpCount; fp++) {
                fPartitions.add(msg.readInt());
            }
            KafkaFlexibleCodec.skipTagBuffer(msg); // per-forgotten-topic tag buffer
            forgottenTopics.add(new KafkaFetchCodec.ForgottenTopic(topic, fPartitions));
        }

        // rack_id: compact nullable string
        String rackId = KafkaFlexibleCodec.readCompactString(msg);

        KafkaFlexibleCodec.skipTagBuffer(msg); // top-level tag buffer

        return new KafkaFetchCodec.FetchRequest(replicaId, maxWaitMs, minBytes, maxBytes,
                isolationLevel, sessionId, sessionEpoch, topics, forgottenTopics, rackId);
    }

    public static ByteBuf serializeResponse(int correlationId,
                                            KafkaFetchCodec.FetchResponse response,
                                            KafkaMessageStore messageStore) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        buf.writeInt(0); // throttle_time_ms
        buf.writeShort(KafkaProtocolVersions.NONE); // error_code
        buf.writeInt(0); // session_id

        // Topics: compact array
        KafkaFlexibleCodec.writeCompactArrayLength(buf, response.getTopics().size());
        for (KafkaFetchCodec.FetchTopicResult topicResult : response.getTopics()) {
            KafkaFlexibleCodec.writeCompactStringNotNullable(buf, topicResult.getTopic());
            // topic_id: null UUID (we route by name)
            KafkaFlexibleCodec.writeUuid(buf, null);
            // Partitions: compact array
            KafkaFlexibleCodec.writeCompactArrayLength(buf, topicResult.getPartitions().size());
            for (KafkaFetchCodec.FetchPartitionResult partResult : topicResult.getPartitions()) {
                buf.writeInt(partResult.getPartition()); // partition_index
                buf.writeShort(KafkaProtocolVersions.NONE); // error_code
                long highWatermark = messageStore.getOffset(topicResult.getTopic(), partResult.getPartition());
                buf.writeLong(highWatermark); // high_watermark
                buf.writeLong(highWatermark); // last_stable_offset
                buf.writeLong(0); // log_start_offset
                // aborted_transactions: compact nullable array (null)
                KafkaFlexibleCodec.writeCompactArrayLength(buf, -1);
                // preferred_read_replica
                buf.writeInt(-1);
                // Record data
                ByteBuf recordData = KafkaFetchCodec.buildRecordSet(partResult.getMessages());
                buf.writeBytes(recordData);
                recordData.release();
                KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // per-partition tag buffer
            }
            KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // per-topic tag buffer
        }
        KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // top-level tag buffer

        return KafkaCodecUtils.frameResponse(buf);
    }
}
