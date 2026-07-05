package com.baafoo.server.broker;

import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory message store for the Pulsar Mock Broker.
 *
 * <p>Stores produced messages by topic and serves them to consumers.
 * Also provides topic discovery from the rules configured in the storage service.</p>
 */
class PulsarMessageStore {

    private static final Logger log = LoggerFactory.getLogger(PulsarMessageStore.class);

    private final StorageService storage;

    /** Messages per topic, queued for consumption. */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<StoredMessage>> messagesByTopic =
            new ConcurrentHashMap<String, ConcurrentLinkedQueue<StoredMessage>>();

    /** Messages per subscription, for tracking delivery. */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<StoredMessage>> messagesBySubscription =
            new ConcurrentHashMap<String, ConcurrentLinkedQueue<StoredMessage>>();

    /**
     * Maximum number of messages retained per topic (Medium 26).
     *
     * <p>Mock broker is intended for functional testing, not long-running
     * production traffic. Without a cap, a runaway producer could exhaust
     * heap. When the cap is exceeded the oldest message is dropped.</p>
     */
    private static final int MAX_MESSAGES_PER_TOPIC = 10000;

    /** Monotonic ID generator for ledger IDs. */
    private final AtomicLong ledgerIdSeq = new AtomicLong(1);

    /** Monotonic ID generator for entry IDs within a ledger. */
    private final ConcurrentHashMap<String, AtomicLong> entryIdSeqs =
            new ConcurrentHashMap<String, AtomicLong>();

    PulsarMessageStore(StorageService storage) {
        this.storage = storage;
    }

    /**
     * Store a produced message and return a unique MessageId (ledgerId + entryId).
     */
    StoredMessage storeMessage(String topic, String producerName, long sequenceId, byte[] payload) {
        long ledgerId = ledgerIdSeq.getAndIncrement();
        AtomicLong entrySeq = entryIdSeqs.computeIfAbsent(topic,
                new java.util.function.Function<String, AtomicLong>() {
                    @Override
                    public AtomicLong apply(String k) {
                        return new AtomicLong(0);
                    }
                });
        long entryId = entrySeq.getAndIncrement();

        StoredMessage msg = new StoredMessage();
        msg.ledgerId = ledgerId;
        msg.entryId = entryId;
        msg.topic = topic;
        msg.producerName = producerName;
        msg.sequenceId = sequenceId;
        msg.payload = payload;
        msg.timestamp = System.currentTimeMillis();

        ConcurrentLinkedQueue<StoredMessage> topicQueue = messagesByTopic.computeIfAbsent(topic,
                new java.util.function.Function<String, ConcurrentLinkedQueue<StoredMessage>>() {
                    @Override
                    public ConcurrentLinkedQueue<StoredMessage> apply(String k) {
                        return new ConcurrentLinkedQueue<StoredMessage>();
                    }
                });
        topicQueue.add(msg);
        // Cap per-topic queue to prevent unbounded growth (Medium 26).
        while (topicQueue.size() > MAX_MESSAGES_PER_TOPIC) {
            topicQueue.poll();
        }

        // Also add to any existing subscriptions for this topic
        for (Map.Entry<String, ConcurrentLinkedQueue<StoredMessage>> entry : messagesBySubscription.entrySet()) {
            String subKey = entry.getKey();
            if (subKey.startsWith(topic + ":")) {
                ConcurrentLinkedQueue<StoredMessage> subQueue = entry.getValue();
                subQueue.add(msg);
                // Cap subscription queue too — a slow consumer can otherwise
                // pile up messages indefinitely.
                while (subQueue.size() > MAX_MESSAGES_PER_TOPIC) {
                    subQueue.poll();
                }
            }
        }

        log.debug("Stored message: topic={}, ledgerId={}, entryId={}, payloadSize={}",
                topic, ledgerId, entryId, payload.length);
        return msg;
    }

    /**
     * Register a subscription for a topic. Returns any already-stored messages
     * that should be delivered to this new consumer.
     */
    List<StoredMessage> registerSubscription(String topic, String subscription) {
        String subKey = topic + ":" + subscription;
        ConcurrentLinkedQueue<StoredMessage> queue =
                messagesBySubscription.computeIfAbsent(subKey,
                        new java.util.function.Function<String, ConcurrentLinkedQueue<StoredMessage>>() {
                            @Override
                            public ConcurrentLinkedQueue<StoredMessage> apply(String k) {
                                return new ConcurrentLinkedQueue<StoredMessage>();
                            }
                        });

        // Copy any existing messages for this topic into the subscription queue
        ConcurrentLinkedQueue<StoredMessage> topicQueue = messagesByTopic.get(topic);
        List<StoredMessage> existing = new ArrayList<StoredMessage>();
        if (topicQueue != null) {
            for (StoredMessage msg : topicQueue) {
                queue.add(msg);
                existing.add(msg);
            }
        }
        return existing;
    }

    /**
     * Poll the next message for a subscription, or null if empty.
     */
    StoredMessage pollMessage(String topic, String subscription) {
        String subKey = topic + ":" + subscription;
        ConcurrentLinkedQueue<StoredMessage> queue = messagesBySubscription.get(subKey);
        if (queue == null) return null;
        return queue.poll();
    }

    /**
     * Peek at the next message for a subscription without removing it, or null if empty.
     */
    StoredMessage peekMessage(String topic, String subscription) {
        String subKey = topic + ":" + subscription;
        ConcurrentLinkedQueue<StoredMessage> queue = messagesBySubscription.get(subKey);
        if (queue == null) return null;
        return queue.peek();
    }

    /**
     * Get the list of topics configured in the rules.
     * Extracts topic names from Pulsar rules (protocol="pulsar").
     */
    List<String> getConfiguredTopics() {
        Set<String> topics = new LinkedHashSet<String>();
        try {
            List<Rule> rules = storage.listRules();
            for (Rule rule : rules) {
                if ("pulsar".equalsIgnoreCase(rule.getProtocol()) && rule.isEnabled()) {
                    // Topic is stored in the rule's conditions or as the rule name
                    // Convention: for pulsar rules, the "path" condition holds the topic name
                    // or the rule name itself is the topic
                    if (rule.getConditions() != null) {
                        for (com.baafoo.core.model.MatchCondition cond : rule.getConditions()) {
                            if ("path".equals(cond.getType()) || "topic".equals(cond.getType())) {
                                topics.add(cond.getValue());
                            }
                        }
                    }
                    // Fallback: use rule name as topic if no path/topic condition found
                    if (rule.getName() != null && !rule.getName().isEmpty()) {
                        topics.add(rule.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read topics from rules: {}", e.getMessage());
        }
        return new ArrayList<String>(topics);
    }

    /**
     * Get topics belonging to a specific namespace.
     * Filters configured topics by namespace prefix.
     * Pulsar topic format: persistent://tenant/namespace/topic-name
     */
    List<String> getTopicsOfNamespace(String namespace) {
        List<String> allTopics = getConfiguredTopics();
        List<String> result = new ArrayList<String>();
        String nsPrefix = "persistent://" + namespace + "/";
        String nsPrefixAlt = "non-persistent://" + namespace + "/";

        for (String topic : allTopics) {
            if (topic.startsWith(nsPrefix) || topic.startsWith(nsPrefixAlt)) {
                result.add(topic);
            }
        }
        return result;
    }

    /**
     * A stored message with its Pulsar MessageId.
     */
    static class StoredMessage {
        long ledgerId;
        long entryId;
        String topic;
        String producerName;
        long sequenceId;
        byte[] payload;
        long timestamp;
    }
}
