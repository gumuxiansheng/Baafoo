package com.baafoo.server.broker.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-flexible Kafka Produce API codec (v0-v8).
 *
 * <p>Parses Produce requests and serializes Produce responses using the
 * int16-prefixed nullable string format. Supports all non-flexible versions
 * up to the flexible threshold (v9); Baafoo currently advertises v8 in
 * ApiVersions so clients stay in this format.
 */
public final class KafkaProduceCodec {

    private KafkaProduceCodec() {
    }

    // ===== Request DTOs =====

    public static final class ProduceRequest {
        private final short acks;
        private final int timeoutMs;
        private final List<ProduceTopic> topics;

        public ProduceRequest(short acks, int timeoutMs, List<ProduceTopic> topics) {
            this.acks = acks;
            this.timeoutMs = timeoutMs;
            this.topics = topics;
        }

        public short getAcks() { return acks; }
        public int getTimeoutMs() { return timeoutMs; }
        public List<ProduceTopic> getTopics() { return topics; }
    }

    public static final class ProduceTopic {
        private final String name;
        private final List<ProducePartition> partitions;

        public ProduceTopic(String name, List<ProducePartition> partitions) {
            this.name = name;
            this.partitions = partitions;
        }

        public String getName() { return name; }
        public List<ProducePartition> getPartitions() { return partitions; }
    }

    public static final class ProducePartition {
        private final int partition;
        private final byte[] batchData;

        public ProducePartition(int partition, byte[] batchData) {
            this.partition = partition;
            this.batchData = batchData;
        }

        public int getPartition() { return partition; }
        public byte[] getBatchData() { return batchData; }
    }

    // ===== Response DTOs =====

    public static final class ProduceResponse {
        private final List<ProduceTopicResult> topics;

        public ProduceResponse(List<ProduceTopicResult> topics) {
            this.topics = topics;
        }

        public List<ProduceTopicResult> getTopics() { return topics; }
    }

    public static final class ProduceTopicResult {
        private final String topic;
        private final List<ProducePartitionResult> partitions;

        public ProduceTopicResult(String topic, List<ProducePartitionResult> partitions) {
            this.topic = topic;
            this.partitions = partitions;
        }

        public String getTopic() { return topic; }
        public List<ProducePartitionResult> getPartitions() { return partitions; }
    }

    public static final class ProducePartitionResult {
        private final int partition;
        private final long offset;

        public ProducePartitionResult(int partition, long offset) {
            this.partition = partition;
            this.offset = offset;
        }

        public int getPartition() { return partition; }
        public long getOffset() { return offset; }
    }

    // ===== Request parsing =====

    public static ProduceRequest parseRequest(ByteBuf msg, short apiVersion) {
        if (apiVersion >= 3) {
            KafkaCodecUtils.readNullableString(msg); // transactional_id
        }
        short acks = msg.readShort();
        int timeoutMs = msg.readInt();

        List<ProduceTopic> topics = new ArrayList<ProduceTopic>();
        int topicCount = msg.readInt();
        for (int t = 0; t < topicCount; t++) {
            String topic = KafkaCodecUtils.readNullableString(msg);
            List<ProducePartition> partitions = new ArrayList<ProducePartition>();
            int partitionCount = msg.readInt();
            for (int p = 0; p < partitionCount; p++) {
                int partition = msg.readInt();
                int batchLength = msg.readInt();
                byte[] batchData = new byte[batchLength];
                msg.readBytes(batchData);
                partitions.add(new ProducePartition(partition, batchData));
            }
            topics.add(new ProduceTopic(topic, partitions));
        }

        return new ProduceRequest(acks, timeoutMs, topics);
    }

    // ===== Response serialization =====

    public static ByteBuf serializeResponse(int correlationId, short apiVersion,
                                            ProduceResponse response,
                                            short errorCode, int throttleMs) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // Topics array (before throttle_time_ms in Produce response).
        buf.writeInt(response.getTopics().size());
        for (ProduceTopicResult topicResult : response.getTopics()) {
            KafkaCodecUtils.writeNullableString(buf, topicResult.getTopic());
            buf.writeInt(topicResult.getPartitions().size());
            for (ProducePartitionResult partResult : topicResult.getPartitions()) {
                buf.writeInt(partResult.getPartition()); // partition_index
                buf.writeShort(errorCode); // error_code
                buf.writeLong(partResult.getOffset()); // base_offset
                // log_append_time_ms (v2+).
                if (apiVersion >= 2) {
                    buf.writeLong(System.currentTimeMillis());
                }
                // log_start_offset (v5+).
                if (apiVersion >= 5) {
                    buf.writeLong(0);
                }
                // RecordErrors array (v8+, KIP-467).
                if (apiVersion >= 8) {
                    buf.writeInt(0);
                }
                // ErrorMessage (v8+).
                if (apiVersion >= 8) {
                    KafkaCodecUtils.writeNullableString(buf, null);
                }
            }
        }

        // throttle_time_ms (v1+).
        if (apiVersion >= 1) {
            buf.writeInt(throttleMs);
        }

        return KafkaCodecUtils.frameResponse(buf);
    }
}
