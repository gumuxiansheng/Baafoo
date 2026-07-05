package com.baafoo.server.broker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory message storage for the Kafka Mock Broker.
 *
 * <p>Stores produced messages by topic-partition and tracks offsets.
 * Also supports preset messages from rules for consumer fetch scenarios.</p>
 */
public class KafkaMessageStore {

    /**
     * Maximum number of messages retained per partition (Medium 25).
     *
     * <p>The mock broker is intended for functional/staging testing. Without
     * a cap, a runaway producer could exhaust heap. When the cap is exceeded
     * the oldest message is dropped (offsets continue to advance).</p>
     */
    static final int MAX_MESSAGES_PER_PARTITION = 10000;

    private final ConcurrentHashMap<TopicPartition, PartitionLog> logs = new ConcurrentHashMap<>();

    /**
     * Append a produced message and return its offset.
     * The rawBatch parameter is the full RecordBatch (baseOffset + batchLength + batchData)
     * as received from the producer, which can be replayed verbatim on fetch.
     */
    public long append(String topic, int partition, byte[] key, byte[] value, byte[] rawBatch) {
        TopicPartition tp = new TopicPartition(topic, partition);
        PartitionLog log = logs.computeIfAbsent(tp, k -> new PartitionLog());
        return log.append(key, value, rawBatch);
    }

    /**
     * Append a produced message and return its offset (without raw batch).
     */
    public long append(String topic, int partition, byte[] key, byte[] value) {
        return append(topic, partition, key, value, null);
    }

    /**
     * Fetch messages from a topic-partition starting at the given offset.
     * Returns messages up to maxBytes total size.
     */
    public List<StoredMessage> fetch(String topic, int partition, long offset, int maxBytes) {
        TopicPartition tp = new TopicPartition(topic, partition);
        PartitionLog log = logs.get(tp);
        if (log == null) {
            return Collections.emptyList();
        }
        return log.fetch(offset, maxBytes);
    }

    /**
     * Get the current offset (next write position) for a topic-partition.
     */
    public long getOffset(String topic, int partition) {
        TopicPartition tp = new TopicPartition(topic, partition);
        PartitionLog log = logs.get(tp);
        return log != null ? log.nextOffset.get() : 0;
    }

    /**
     * Check if a topic-partition has any data.
     */
    public boolean hasData(String topic, int partition) {
        TopicPartition tp = new TopicPartition(topic, partition);
        PartitionLog log = logs.get(tp);
        return log != null && !log.messages.isEmpty();
    }

    /**
     * Set preset messages for a topic-partition (from rules).
     * These replace any previously produced messages.
     */
    public void setPresetMessages(String topic, int partition, List<PresetMessage> presets) {
        TopicPartition tp = new TopicPartition(topic, partition);
        PartitionLog log = logs.computeIfAbsent(tp, k -> new PartitionLog());
        log.setPresetMessages(presets);
    }

    /**
     * Clear all stored messages.
     */
    public void clear() {
        logs.clear();
    }

    // --- Inner types ---

    static final class TopicPartition {
        final String topic;
        final int partition;

        TopicPartition(String topic, int partition) {
            this.topic = topic;
            this.partition = partition;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TopicPartition that = (TopicPartition) o;
            return partition == that.partition && topic.equals(that.topic);
        }

        @Override
        public int hashCode() {
            return 31 * topic.hashCode() + partition;
        }
    }

    static final class PartitionLog {
        final List<StoredMessage> messages = Collections.synchronizedList(new ArrayList<StoredMessage>());
        final AtomicLong nextOffset = new AtomicLong(0);

        long append(byte[] key, byte[] value, byte[] rawBatch) {
            long offset = nextOffset.getAndIncrement();
            messages.add(new StoredMessage(offset, key, value, rawBatch));
            // Cap partition log to prevent unbounded growth (Medium 25).
            // Note: dropping messages here means subsequent fetch() calls
            // referencing the dropped offsets will simply not return them —
            // acceptable for a mock broker.
            while (messages.size() > MAX_MESSAGES_PER_PARTITION) {
                messages.remove(0);
            }
            return offset;
        }

        List<StoredMessage> fetch(long startOffset, int maxBytes) {
            List<StoredMessage> result = new ArrayList<StoredMessage>();
            int totalBytes = 0;
            synchronized (messages) {
                // Linear scan — required because the per-partition cap
                // (MAX_MESSAGES_PER_PARTITION) may have dropped messages from
                // the front of the list, so message[i].offset != i in general.
                // List size is bounded by MAX_MESSAGES_PER_PARTITION (10k),
                // so the scan is cheap (~100µs).
                for (StoredMessage msg : messages) {
                    if (msg.offset < startOffset) continue;
                    int msgSize = 4 + 4; // offset + message size prefix
                    if (msg.key != null) msgSize += msg.key.length;
                    if (msg.value != null) msgSize += msg.value.length;
                    msgSize += 4 + 4; // key length + value length fields
                    if (totalBytes + msgSize > maxBytes && !result.isEmpty()) break;
                    result.add(msg);
                    totalBytes += msgSize;
                }
            }
            return result;
        }

        void setPresetMessages(List<PresetMessage> presets) {
            synchronized (messages) {
                messages.clear();
                nextOffset.set(0);
                for (PresetMessage preset : presets) {
                    byte[] key = preset.key != null ? preset.key.getBytes(StandardCharsets.UTF_8) : null;
                    byte[] value = preset.value != null ? preset.value.getBytes(StandardCharsets.UTF_8) : null;
                    long offset = nextOffset.getAndIncrement();
                    messages.add(new StoredMessage(offset, key, value, null));
                }
            }
        }
    }

    /**
     * A stored message with offset, key, and value.
     */
    public static final class StoredMessage {
        public final long offset;
        public final byte[] key;
        public final byte[] value;
        /** Full RecordBatch (baseOffset + batchLength + batchData) from the producer, or null. */
        public final byte[] rawBatch;

        public StoredMessage(long offset, byte[] key, byte[] value, byte[] rawBatch) {
            this.offset = offset;
            this.key = key;
            this.value = value;
            this.rawBatch = rawBatch;
        }
    }

    /**
     * A preset message from a rule definition (string key/value).
     */
    public static final class PresetMessage {
        public String key;
        public String value;

        public PresetMessage() {}

        public PresetMessage(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
