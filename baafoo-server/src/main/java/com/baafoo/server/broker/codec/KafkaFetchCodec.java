package com.baafoo.server.broker.codec;

import com.baafoo.server.broker.KafkaMessageStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-flexible Kafka Fetch API codec (v0-v11).
 *
 * <p>Parses Fetch requests and serializes Fetch responses using the
 * int16-prefixed nullable string format. Supports all non-flexible versions
 * up to the flexible threshold (v12); Baafoo currently advertises v11 in
 * ApiVersions so clients stay in this format.
 */
public final class KafkaFetchCodec {

    private KafkaFetchCodec() {
    }

    // ===== Request DTOs =====

    public static final class FetchRequest {
        private final int replicaId;
        private final int maxWaitMs;
        private final int minBytes;
        private final int maxBytes;
        private final byte isolationLevel;
        private final int sessionId;
        private final int sessionEpoch;
        private final List<FetchTopic> topics;
        private final List<ForgottenTopic> forgottenTopics;
        private final String rackId;

        public FetchRequest(int replicaId, int maxWaitMs, int minBytes, int maxBytes,
                            byte isolationLevel, int sessionId, int sessionEpoch,
                            List<FetchTopic> topics, List<ForgottenTopic> forgottenTopics,
                            String rackId) {
            this.replicaId = replicaId;
            this.maxWaitMs = maxWaitMs;
            this.minBytes = minBytes;
            this.maxBytes = maxBytes;
            this.isolationLevel = isolationLevel;
            this.sessionId = sessionId;
            this.sessionEpoch = sessionEpoch;
            this.topics = topics;
            this.forgottenTopics = forgottenTopics;
            this.rackId = rackId;
        }

        public int getReplicaId() { return replicaId; }
        public int getMaxWaitMs() { return maxWaitMs; }
        public int getMinBytes() { return minBytes; }
        public int getMaxBytes() { return maxBytes; }
        public byte getIsolationLevel() { return isolationLevel; }
        public int getSessionId() { return sessionId; }
        public int getSessionEpoch() { return sessionEpoch; }
        public List<FetchTopic> getTopics() { return topics; }
        public List<ForgottenTopic> getForgottenTopics() { return forgottenTopics; }
        public String getRackId() { return rackId; }
    }

    public static final class FetchTopic {
        private final String topic;
        private final List<FetchPartition> partitions;

        public FetchTopic(String topic, List<FetchPartition> partitions) {
            this.topic = topic;
            this.partitions = partitions;
        }

        public String getTopic() { return topic; }
        public List<FetchPartition> getPartitions() { return partitions; }
    }

    public static final class FetchPartition {
        private final int partition;
        private final int currentLeaderEpoch;
        private final long fetchOffset;
        private final long logStartOffset;
        private final int lastFetchedEpoch;
        private final int partitionMaxBytes;

        public FetchPartition(int partition, int currentLeaderEpoch, long fetchOffset,
                              long logStartOffset, int lastFetchedEpoch,
                              int partitionMaxBytes) {
            this.partition = partition;
            this.currentLeaderEpoch = currentLeaderEpoch;
            this.fetchOffset = fetchOffset;
            this.logStartOffset = logStartOffset;
            this.lastFetchedEpoch = lastFetchedEpoch;
            this.partitionMaxBytes = partitionMaxBytes;
        }

        public int getPartition() { return partition; }
        public int getCurrentLeaderEpoch() { return currentLeaderEpoch; }
        public long getFetchOffset() { return fetchOffset; }
        public long getLogStartOffset() { return logStartOffset; }
        public int getLastFetchedEpoch() { return lastFetchedEpoch; }
        public int getPartitionMaxBytes() { return partitionMaxBytes; }
    }

    public static final class ForgottenTopic {
        private final String topic;
        private final List<Integer> partitions;

        public ForgottenTopic(String topic, List<Integer> partitions) {
            this.topic = topic;
            this.partitions = partitions;
        }

        public String getTopic() { return topic; }
        public List<Integer> getPartitions() { return partitions; }
    }

    // ===== Response DTOs =====

    public static final class FetchResponse {
        private final List<FetchTopicResult> topics;

        public FetchResponse(List<FetchTopicResult> topics) {
            this.topics = topics;
        }

        public List<FetchTopicResult> getTopics() { return topics; }
    }

    public static final class FetchTopicResult {
        private final String topic;
        private final List<FetchPartitionResult> partitions;

        public FetchTopicResult(String topic, List<FetchPartitionResult> partitions) {
            this.topic = topic;
            this.partitions = partitions;
        }

        public String getTopic() { return topic; }
        public List<FetchPartitionResult> getPartitions() { return partitions; }
    }

    public static final class FetchPartitionResult {
        private final int partition;
        private final List<KafkaMessageStore.StoredMessage> messages;

        public FetchPartitionResult(int partition, List<KafkaMessageStore.StoredMessage> messages) {
            this.partition = partition;
            this.messages = messages;
        }

        public int getPartition() { return partition; }
        public List<KafkaMessageStore.StoredMessage> getMessages() { return messages; }
    }

    // ===== Request parsing =====

    public static FetchRequest parseRequest(ByteBuf msg, short apiVersion) {
        int replicaId = msg.readInt();
        int maxWaitMs = msg.readInt();
        int minBytes = msg.readInt();
        int maxBytes = apiVersion >= 3 ? msg.readInt() : 0;
        byte isolationLevel = apiVersion >= 4 ? msg.readByte() : 0;
        int sessionId = 0;
        int sessionEpoch = 0;
        if (apiVersion >= 7) {
            sessionId = msg.readInt();
            sessionEpoch = msg.readInt();
        }

        List<FetchTopic> topics = new ArrayList<FetchTopic>();
        int topicCount = msg.readInt();
        for (int t = 0; t < topicCount; t++) {
            String topic = KafkaCodecUtils.readNullableString(msg);
            // topic_id (UUID, 16 bytes) — only in v13+; skip if present.
            if (apiVersion >= 13) {
                msg.skipBytes(16);
            }
            List<FetchPartition> partitions = new ArrayList<FetchPartition>();
            int partitionCount = msg.readInt();
            for (int p = 0; p < partitionCount; p++) {
                int partition = msg.readInt();
                int currentLeaderEpoch = apiVersion >= 9 ? msg.readInt() : -1;
                long fetchOffset = msg.readLong();
                int lastFetchedEpoch = apiVersion >= 12 ? msg.readInt() : -1;
                long logStartOffset = apiVersion >= 5 ? msg.readLong() : -1;
                int partitionMaxBytes = msg.readInt();
                partitions.add(new FetchPartition(partition, currentLeaderEpoch, fetchOffset,
                        logStartOffset, lastFetchedEpoch, partitionMaxBytes));
            }
            topics.add(new FetchTopic(topic, partitions));
        }

        List<ForgottenTopic> forgottenTopics = new ArrayList<ForgottenTopic>();
        if (apiVersion >= 7) {
            int forgottenCount = msg.readInt();
            for (int f = 0; f < forgottenCount; f++) {
                String topic;
                if (apiVersion < 13) {
                    topic = KafkaCodecUtils.readNullableString(msg);
                } else {
                    msg.skipBytes(16);
                    topic = null;
                }
                List<Integer> fPartitions = new ArrayList<Integer>();
                int fpCount = msg.readInt();
                for (int fp = 0; fp < fpCount; fp++) {
                    fPartitions.add(msg.readInt());
                }
                forgottenTopics.add(new ForgottenTopic(topic, fPartitions));
            }
        }

        String rackId = apiVersion >= 11 ? KafkaCodecUtils.readNullableString(msg) : null;

        return new FetchRequest(replicaId, maxWaitMs, minBytes, maxBytes, isolationLevel,
                sessionId, sessionEpoch, topics, forgottenTopics, rackId);
    }

    // ===== Response serialization =====

    public static ByteBuf serializeResponse(int correlationId, short apiVersion,
                                            FetchResponse response,
                                            KafkaMessageStore messageStore) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v1+).
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        // error_code (v7+).
        if (apiVersion >= 7) {
            buf.writeShort(KafkaProtocolVersions.NONE);
        }

        // session_id (v7+).
        if (apiVersion >= 7) {
            buf.writeInt(0);
        }

        // Topics array.
        buf.writeInt(response.getTopics().size());
        for (FetchTopicResult topicResult : response.getTopics()) {
            KafkaCodecUtils.writeNullableString(buf, topicResult.getTopic());
            // topic_id (UUID) — only in v13+; write null UUID.
            if (apiVersion >= 13) {
                buf.writeZero(16);
            }
            buf.writeInt(topicResult.getPartitions().size());

            for (FetchPartitionResult partResult : topicResult.getPartitions()) {
                buf.writeInt(partResult.getPartition()); // partition_index
                buf.writeShort(KafkaProtocolVersions.NONE); // error_code
                long highWatermark = messageStore.getOffset(topicResult.getTopic(), partResult.getPartition());
                buf.writeLong(highWatermark); // high_watermark
                // last_stable_offset (v4+).
                if (apiVersion >= 4) {
                    buf.writeLong(highWatermark);
                }
                // log_start_offset (v5+).
                if (apiVersion >= 5) {
                    buf.writeLong(0);
                }
                // aborted_transactions (v4+).
                if (apiVersion >= 4) {
                    buf.writeInt(0);
                }
                // preferred_read_replica (v11+).
                if (apiVersion >= 11) {
                    buf.writeInt(-1);
                }

                // Record data.
                ByteBuf recordData = buildRecordSet(partResult.getMessages());
                buf.writeBytes(recordData);
                recordData.release();
            }
        }

        return KafkaCodecUtils.frameResponse(buf);
    }

    /**
     * Build a length-prefixed record set from stored messages.
     */
    public static ByteBuf buildRecordSet(List<KafkaMessageStore.StoredMessage> messages) {
        ByteBuf buf = Unpooled.buffer();
        if (messages == null || messages.isEmpty()) {
            buf.writeInt(0);
            return buf;
        }

        ByteBuf records = Unpooled.buffer();
        byte[] lastRawBatchRef = null;
        for (KafkaMessageStore.StoredMessage msg : messages) {
            if (msg.rawBatch != null) {
                if (msg.rawBatch != lastRawBatchRef) {
                    records.writeBytes(msg.rawBatch);
                    lastRawBatchRef = msg.rawBatch;
                }
            } else {
                writeSimpleRecord(records, msg.offset, msg.key, msg.value);
            }
        }

        buf.writeInt(records.readableBytes());
        buf.writeBytes(records);
        records.release();
        return buf;
    }

    /**
     * Write a minimal RecordBatch v2 containing a single record with a valid CRC32C.
     */
    public static void writeSimpleRecord(ByteBuf buf, long offset, byte[] key, byte[] value) {
        ByteBuf recordBuf = Unpooled.buffer();
        recordBuf.writeByte(0); // attributes
        KafkaCodecUtils.writeVarint(recordBuf, 0); // timestampDelta
        KafkaCodecUtils.writeVarint(recordBuf, 0); // offsetDelta
        if (key != null) {
            KafkaCodecUtils.writeVarint(recordBuf, key.length);
            recordBuf.writeBytes(key);
        } else {
            KafkaCodecUtils.writeVarint(recordBuf, -1);
        }
        if (value != null) {
            KafkaCodecUtils.writeVarint(recordBuf, value.length);
            recordBuf.writeBytes(value);
        } else {
            KafkaCodecUtils.writeVarint(recordBuf, -1);
        }
        KafkaCodecUtils.writeVarint(recordBuf, 0); // headers count

        int recordLen = recordBuf.readableBytes();

        ByteBuf contentBuf = Unpooled.buffer();
        contentBuf.writeShort(0); // attributes
        contentBuf.writeInt(0); // lastOffsetDelta
        long now = System.currentTimeMillis();
        contentBuf.writeLong(now); // baseTimestamp
        contentBuf.writeLong(now); // maxTimestamp
        contentBuf.writeLong(0); // producerId
        contentBuf.writeShort(0); // producerEpoch
        contentBuf.writeInt(0); // baseSequence
        contentBuf.writeInt(1); // recordsCount
        KafkaCodecUtils.writeVarint(contentBuf, recordLen);
        contentBuf.writeBytes(recordBuf);
        recordBuf.release();

        byte[] contentBytes = new byte[contentBuf.readableBytes()];
        contentBuf.readBytes(contentBytes);
        contentBuf.release();

        int crc = KafkaCodecUtils.computeCrc32c(contentBytes, 0, contentBytes.length);
        int batchLength = 4 + 1 + 4 + contentBytes.length;

        buf.writeLong(offset); // baseOffset
        buf.writeInt(batchLength); // batchLength
        buf.writeInt(0); // partitionLeaderEpoch
        buf.writeByte(2); // magic
        buf.writeInt(crc); // CRC32C
        buf.writeBytes(contentBytes); // content
    }
}
