package com.baafoo.server.broker;

import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.handler.AgentResolver;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Netty handler that implements a subset of the Kafka binary protocol.
 *
 * <p>Handles: Metadata (3), Produce (0), Fetch (1), ApiVersions (18).
 * Returns empty/default responses for all other API keys so that
 * Kafka clients don't crash on unsupported operations.</p>
 *
 * <p>The Kafka protocol is big-endian. Request frame format:
 * 4 bytes size | 2 bytes API key | 2 bytes API version | 4 bytes correlation_id | string client_id | payload</p>
 */
public class KafkaProtocolDecoder extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(KafkaProtocolDecoder.class);

    // Kafka API keys
    private static final short PRODUCE = 0;
    private static final short FETCH = 1;
    private static final short METADATA = 3;
    private static final short OFFSET_COMMIT = 8;
    private static final short OFFSET_FETCH = 9;
    private static final short FIND_COORDINATOR = 10;
    private static final short JOIN_GROUP = 11;
    private static final short HEARTBEAT = 12;
    private static final short LEAVE_GROUP = 13;
    private static final short SYNC_GROUP = 14;
    private static final short DESCRIBE_GROUPS = 15;
    private static final short LIST_GROUPS = 16;
    private static final short API_VERSIONS = 18;
    private static final short INIT_PRODUCER_ID = 22;
    private static final short DESCRIBE_CONFIGS = 32;

    // Kafka error codes
    private static final short NONE = 0;
    private static final short UNKNOWN_SERVER_ERROR = -1;
    private static final short UNSUPPORTED_VERSION = 35;

    private final KafkaMessageStore messageStore;
    private final StorageService storage;
    private final int brokerPort;
    private final MqMatchHelper matchHelper;
    private final String advertisedHost;

    /** Cached broker host resolved from the first client connection (for Docker-internal clients). */
    private volatile String cachedBrokerHost;

    public KafkaProtocolDecoder(KafkaMessageStore messageStore, StorageService storage,
                                int brokerPort, String advertisedHost) {
        this.messageStore = messageStore;
        this.storage = storage;
        this.brokerPort = brokerPort;
        this.advertisedHost = advertisedHost;
        this.matchHelper = new MqMatchHelper(storage);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("Kafka client connected: {}", ctx.channel().remoteAddress());
        // Resolve broker host from the server's local address on this channel.
        // This ensures the Metadata response advertises a reachable address
        // instead of 127.0.0.1 (which doesn't work in Docker networks).
        if (cachedBrokerHost == null) {
            try {
                java.net.InetSocketAddress localAddr = (java.net.InetSocketAddress) ctx.channel().localAddress();
                String ip = localAddr.getAddress().getHostAddress();
                // In Docker, the local address is typically the container's IP (e.g., 172.x.x.x)
                // which is reachable from other containers on the same network.
                // If it's 0.0.0.0, fall back to hostname resolution.
                if (!"0.0.0.0".equals(ip) && !"127.0.0.1".equals(ip)) {
                    cachedBrokerHost = ip;
                } else {
                    // Try hostname — in Docker this returns the container ID
                    // which is resolvable as a Docker network hostname
                    String hostname = java.net.InetAddress.getLocalHost().getHostName();
                    cachedBrokerHost = hostname;
                }
                log.info("Kafka broker host resolved: {} (from localAddress={})", cachedBrokerHost, localAddr);
            } catch (Exception e) {
                log.warn("Failed to resolve broker host from channel: {}", e.getMessage());
            }
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        // The LengthFieldBasedFrameDecoder already stripped the 4-byte size prefix
        // Read the request header (v0/v1: int16-prefixed nullable string for clientId).
        short apiKey = msg.readShort();
        short apiVersion = msg.readShort();
        int correlationId = msg.readInt();
        // Resilient clientId parsing: modern Kafka clients (3.x) negotiate ApiVersions v3
        // which enables flexible versions (KIP-511), switching subsequent requests to
        // Request Header v2 with compact strings (varint length prefix). We advertise
        // API_VERSIONS max v2 in handleApiVersions to prevent this, but some clients may
        // still send compact headers — so try int16 first, fall back to compact on failure.
        String clientId;
        int headerMark = msg.readerIndex();
        try {
            clientId = readNullableString(msg);
        } catch (Exception e) {
            msg.readerIndex(headerMark);
            try {
                clientId = readCompactString(msg);
            } catch (Exception e2) {
                clientId = null;
            }
        }

        log.info("Kafka request: apiKey={}, apiVersion={}, correlationId={}, clientId={}",
                apiKey, apiVersion, correlationId, clientId);

        ByteBuf response;
        try {
            switch (apiKey) {
                case PRODUCE:
                    response = handleProduce(ctx, msg, apiVersion, correlationId);
                    break;
                case FETCH:
                    response = handleFetch(msg, apiVersion, correlationId);
                    break;
                case METADATA:
                    response = handleMetadata(ctx, msg, apiVersion, correlationId);
                    break;
                case API_VERSIONS:
                    response = handleApiVersions(apiVersion, correlationId);
                    break;
                case INIT_PRODUCER_ID:
                    response = handleInitProducerId(msg, apiVersion, correlationId);
                    break;
                case OFFSET_COMMIT:
                    response = handleOffsetCommit(apiVersion, correlationId);
                    break;
                case OFFSET_FETCH:
                    response = handleOffsetFetch(msg, apiVersion, correlationId);
                    break;
                case FIND_COORDINATOR:
                    response = handleFindCoordinator(ctx, apiVersion, correlationId);
                    break;
                case JOIN_GROUP:
                    response = handleJoinGroup(apiVersion, correlationId);
                    break;
                case HEARTBEAT:
                    response = handleHeartbeat(apiVersion, correlationId);
                    break;
                case LEAVE_GROUP:
                    response = handleLeaveGroup(apiVersion, correlationId);
                    break;
                case SYNC_GROUP:
                    response = handleSyncGroup(apiVersion, correlationId);
                    break;
                case DESCRIBE_GROUPS:
                    response = handleDescribeGroups(apiVersion, correlationId);
                    break;
                case LIST_GROUPS:
                    response = handleListGroups(apiVersion, correlationId);
                    break;
                case DESCRIBE_CONFIGS:
                    response = handleDescribeConfigs(apiVersion, correlationId);
                    break;
                default:
                    log.debug("Unsupported Kafka API key={}, returning empty response", apiKey);
                    response = buildEmptyResponse(correlationId);
                    break;
            }
        } catch (Exception e) {
            log.error("Error handling Kafka request apiKey={}: {}", apiKey, e.getMessage());
            response = buildErrorResponse(correlationId, UNKNOWN_SERVER_ERROR);
        }

        ctx.writeAndFlush(response);
    }

    // --- Metadata (API key 3) ---

    private ByteBuf handleMetadata(ChannelHandlerContext ctx, ByteBuf msg, short apiVersion, int correlationId) {
        // Request: [topics array] + allow_auto_topic_creation (v4+)
        int topicCount = msg.readInt();
        log.info("Metadata request: apiVersion={}, topicCount={}", apiVersion, topicCount);
        List<String> topics = new ArrayList<String>();
        if (topicCount < 0) {
            // null array = request all topics; return empty for mock
            log.info("Metadata request: null topic array (all topics requested)");
        } else {
            for (int i = 0; i < topicCount; i++) {
                String t = readNullableString(msg);
                topics.add(t);
                log.info("Metadata request: topic[{}]={}", i, t);
            }
        }
        // v4+: allow_auto_topic_creation (boolean)
        if (apiVersion >= 4) {
            if (msg.isReadable()) msg.readByte();
        }

        // Build response
        ByteBuf buf = Unpooled.buffer();

        // Correlation ID
        buf.writeInt(correlationId);

        // Throttle_time_ms (v3+)
        if (apiVersion >= 3) {
            buf.writeInt(0);
        }

        // Brokers array (1 broker = ourselves)
        buf.writeInt(1); // array count
        buf.writeInt(0); // broker_id = 0
        writeNullableString(buf, resolveBrokerHost(ctx));
        buf.writeInt(brokerPort);
        // rack (v1+)
        if (apiVersion >= 1) {
            writeNullableString(buf, null); // no rack
        }

        // cluster_id (v2+)
        if (apiVersion >= 2) {
            writeNullableString(buf, "baafoo-mock-cluster");
        }

        // controller_id (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0); // controller is broker 0
        }

        // Topic metadata array
        buf.writeInt(topics.size());
        log.info("Metadata response: returning {} topics, brokerHost={}, brokerPort={}", topics.size(), resolveBrokerHost(ctx), brokerPort);
        for (String topic : topics) {
            buf.writeShort(NONE); // error_code
            writeNullableString(buf, topic);
            if (apiVersion >= 1) {
                buf.writeBoolean(false); // is_internal
            }

            // Partition array — 1 partition per topic
            buf.writeInt(1); // partition count
            buf.writeShort(NONE); // error_code
            buf.writeInt(0); // partition_index
            buf.writeInt(0); // leader_id (broker 0 = us)
            // replica_nodes
            buf.writeInt(1);
            buf.writeInt(0);
            // isr_nodes
            buf.writeInt(1);
            buf.writeInt(0);
            // offline_replicas (v5+)
            if (apiVersion >= 5) {
                buf.writeInt(0);
            }

            // Topic authorized operations (v8+)
            if (apiVersion >= 8) {
                buf.writeInt(0);
            }
        }

        // Cluster authorized operations (v8+) — must come AFTER topic_metadata array
        if (apiVersion >= 8) {
            buf.writeInt(0);
        }

        return frameResponse(buf);
    }

    // --- Produce (API key 0) ---

    private ByteBuf handleProduce(ChannelHandlerContext ctx, ByteBuf msg, short apiVersion, int correlationId) {
        // Request: transactional_id (nullable string, v3+) + acks + timeout_ms + topics array
        if (apiVersion >= 3) {
            readNullableString(msg); // transactional_id
        }
        short acks = msg.readShort(); // acks
        int timeoutMs = msg.readInt(); // timeout_ms
        log.info("Produce request: apiVersion={}, acks={}, timeoutMs={}", apiVersion, acks, timeoutMs);

        // Resolve agent + environment + rules ONCE per produce request.
        AgentResolver.AgentInfo agentInfo = matchHelper.resolveAgent(ctx);
        EnvironmentMode mode = matchHelper.resolveMode(ctx);
        List<Rule> rules = matchHelper.filterRulesByEnvironment(storage.listRules(), agentInfo.environment);
        boolean shouldRecord = (mode == EnvironmentMode.RECORD || mode == EnvironmentMode.RECORD_AND_STUB);

        int topicCount = msg.readInt();
        // Collect results for response
        List<ProduceTopicResult> results = new ArrayList<ProduceTopicResult>();

        for (int t = 0; t < topicCount; t++) {
            String topic = readNullableString(msg);
            int partitionCount = msg.readInt();
            List<ProducePartitionResult> partitionResults = new ArrayList<ProducePartitionResult>();

            for (int p = 0; p < partitionCount; p++) {
                int partition = msg.readInt();
                // Record batch: 8 bytes baseOffset + 4 bytes batchLength + batchData
                int batchLength = msg.readInt();
                byte[] batchData = new byte[batchLength];
                msg.readBytes(batchData);

                // Try to parse records out of the batch to match/record real payloads.
                List<ParsedRecord> records = parseRecordBatch(batchData);

                long offset;
                if (records != null && !records.isEmpty()) {
                    // RecordBatch v2 parsed — process each record against rules.
                    long lastOffset = 0;
                    for (ParsedRecord rec : records) {
                        String bodyStr = rec.value != null
                                ? new String(rec.value, StandardCharsets.UTF_8) : null;

                        MatchEngine.MatchResult m = matchHelper.match(rules, "kafka", topic, bodyStr);
                        if (m.isMatched()) {
                            // Record the original payload (so replays show what the app sent).
                            if (shouldRecord) {
                                matchHelper.record(m.getRule().getId(), "kafka", topic, bodyStr, agentInfo);
                            }
                            // In STUB / RECORD_AND_STUB, replace the value with the stub body so
                            // consumers fetch the stub instead of the producer's original payload.
                            if (mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB) {
                                ResponseEntry resp = m.getResponse();
                                byte[] stubValue = resp != null && resp.getBody() != null
                                        ? resp.getBody().getBytes(StandardCharsets.UTF_8) : rec.value;
                                lastOffset = messageStore.append(topic, partition, rec.key, stubValue);
                            } else {
                                // PASSTHROUGH / RECORD — store the original record.
                                lastOffset = messageStore.append(topic, partition, rec.key, rec.value);
                            }
                        } else {
                            // Unmatched — store the original record (passthrough behaviour).
                            if (shouldRecord) {
                                matchHelper.record(null, "kafka", topic, bodyStr, agentInfo);
                            }
                            lastOffset = messageStore.append(topic, partition, rec.key, rec.value);
                        }
                    }
                    offset = lastOffset;
                } else {
                    // Non-v2 batch or parse failed — fall back to storing the raw batch,
                    // but still attempt a topic-only match so topic rules can stub it.
                    MatchEngine.MatchResult m = matchHelper.match(rules, "kafka", topic, null);
                    if (m.isMatched() && (mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB)) {
                        ResponseEntry resp = m.getResponse();
                        if (resp != null && resp.getBody() != null) {
                            byte[] stubValue = resp.getBody().getBytes(StandardCharsets.UTF_8);
                            offset = messageStore.append(topic, partition, null, stubValue);
                            if (shouldRecord) {
                                matchHelper.record(m.getRule().getId(), "kafka", topic, null, agentInfo);
                            }
                        } else {
                            offset = messageStore.append(topic, partition, null, batchData);
                        }
                    } else {
                        if (shouldRecord && m.isMatched()) {
                            matchHelper.record(m.getRule().getId(), "kafka", topic, null, agentInfo);
                        }
                        offset = messageStore.append(topic, partition, null, batchData);
                    }
                }
                partitionResults.add(new ProducePartitionResult(partition, offset));
            }
            results.add(new ProduceTopicResult(topic, partitionResults));
        }

        // Check for remaining bytes in request (indicates parsing issue)
        if (msg.isReadable()) {
            log.warn("Produce request has {} remaining bytes after parsing! This indicates a request parsing issue.", msg.readableBytes());
        }

        // Build response
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // Topics array (comes BEFORE throttle_time_ms in Produce response)
        buf.writeInt(results.size());
        for (ProduceTopicResult topicResult : results) {
            writeNullableString(buf, topicResult.topic);
            buf.writeInt(topicResult.partitions.size());
            for (ProducePartitionResult partResult : topicResult.partitions) {
                buf.writeInt(partResult.partition); // index (partition_index) — comes BEFORE error_code
                buf.writeShort(NONE); // error_code
                buf.writeLong(partResult.offset); // base_offset
                // log_append_time_ms (v2+)
                if (apiVersion >= 2) {
                    buf.writeLong(System.currentTimeMillis());
                }
                // log_start_offset (v5+)
                if (apiVersion >= 5) {
                    buf.writeLong(0);
                }
            }
        }

        // throttle_time_ms (v1+) — comes AFTER topics array in Produce response
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        log.info("Produce response: {} topics, apiVersion={}", results.size(), apiVersion);
        ByteBuf framed = frameResponse(buf);
        log.info("Produce response hex: {}", io.netty.buffer.ByteBufUtil.hexDump(framed));
        return framed;
    }

    // --- Fetch (API key 1) ---

    private ByteBuf handleFetch(ByteBuf msg, short apiVersion, int correlationId) {
        // Request varies by version
        // v0: replica_id + max_wait_ms + min_bytes + topics
        // v3+: isolation_level added
        // v7+: session_id + session_epoch added
        if (apiVersion >= 7) {
            msg.readInt(); // replica_id (or forgotten_topics in newer)
            msg.readInt(); // max_wait_ms
            msg.readInt(); // min_bytes
            if (apiVersion >= 4) {
                msg.readInt(); // max_bytes
            }
            if (apiVersion >= 5) {
                msg.readByte(); // isolation_level
            }
            msg.readInt(); // session_id
            msg.readInt(); // session_epoch
        } else {
            msg.readInt(); // replica_id
            msg.readInt(); // max_wait_ms
            msg.readInt(); // min_bytes
            if (apiVersion >= 3) {
                msg.readInt(); // max_bytes
            }
            if (apiVersion >= 4) {
                msg.readByte(); // isolation_level
            }
        }

        int topicCount = msg.readInt();
        List<FetchTopicResult> results = new ArrayList<FetchTopicResult>();

        for (int t = 0; t < topicCount; t++) {
            String topic = readNullableString(msg);
            int partitionCount = msg.readInt();
            List<FetchPartitionResult> partitionResults = new ArrayList<FetchPartitionResult>();

            for (int p = 0; p < partitionCount; p++) {
                int partition = msg.readInt();
                long fetchOffset = msg.readLong();
                msg.readInt(); // log_start_offset (v5+)
                int partitionMaxBytes = msg.readInt();

                // Fetch messages from store
                List<KafkaMessageStore.StoredMessage> messages =
                        messageStore.fetch(topic, partition, fetchOffset, partitionMaxBytes);

                partitionResults.add(new FetchPartitionResult(partition, messages));
            }
            results.add(new FetchTopicResult(topic, partitionResults));
        }

        // Build response
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        // error_code (v7+)
        if (apiVersion >= 7) {
            buf.writeShort(NONE);
        }

        // session_id (v7+)
        if (apiVersion >= 7) {
            buf.writeInt(0);
        }

        // Topics array
        buf.writeInt(results.size());
        for (FetchTopicResult topicResult : results) {
            writeNullableString(buf, topicResult.topic);
            buf.writeInt(topicResult.partitions.size());

            for (FetchPartitionResult partResult : topicResult.partitions) {
                buf.writeShort(NONE); // error_code
                buf.writeInt(partResult.partition);
                buf.writeLong(messageStore.getOffset(topicResult.topic, partResult.partition)); // high_watermark
                // last_stable_offset (v4+)
                if (apiVersion >= 4) {
                    buf.writeLong(messageStore.getOffset(topicResult.topic, partResult.partition));
                }
                // log_start_offset (v5+)
                if (apiVersion >= 5) {
                    buf.writeLong(0);
                }
                // aborted_transactions (v4+)
                if (apiVersion >= 4) {
                    buf.writeInt(0); // no aborted transactions
                }
                // preferred_read_replica (v11+)
                if (apiVersion >= 11) {
                    buf.writeInt(-1);
                }

                // Record data — write as a single byte buffer
                ByteBuf recordData = buildRecordSet(partResult.messages);
                buf.writeBytes(recordData);
                recordData.release();
            }
        }

        return frameResponse(buf);
    }

    private ByteBuf buildRecordSet(List<KafkaMessageStore.StoredMessage> messages) {
        ByteBuf buf = Unpooled.buffer();
        if (messages.isEmpty()) {
            // Empty record set: write 0 bytes (just the length prefix of 0)
            // In Kafka protocol, empty record set is represented as -1 (0xFFFFFFFF) for the length
            // or 0 length. Let's use 0.
            buf.writeInt(0);
            return buf;
        }

        // Build all records first, then wrap in a length-prefixed buffer
        ByteBuf records = Unpooled.buffer();
        for (KafkaMessageStore.StoredMessage msg : messages) {
            // If the value looks like a full RecordBatch (produced by our Produce handler),
            // write it directly. Otherwise, wrap in a simple record.
            if (msg.value != null && msg.value.length > 22 && isRecordBatch(msg.value)) {
                // Re-write baseOffset to match the stored offset
                writeLongToBytes(msg.value, 0, msg.offset);
                records.writeBytes(msg.value);
            } else {
                // Simple record wrapper for preset messages
                writeSimpleRecord(records, msg.offset, msg.key, msg.value);
            }
        }

        buf.writeInt(records.readableBytes());
        buf.writeBytes(records);
        records.release();
        return buf;
    }

    private boolean isRecordBatch(byte[] data) {
        // A RecordBatch starts with baseOffset(8) + batchLength(4) + partitionLeaderEpoch(4) + magic=2(1)
        return data.length > 17 && data[16] == 2;
    }

    private void writeSimpleRecord(ByteBuf buf, long offset, byte[] key, byte[] value) {
        // Write a minimal RecordBatch containing one record
        // RecordBatch format:
        // baseOffset(8) + batchLength(4) + partitionLeaderEpoch(4) + magic(1) + crc(4) + attributes(2)
        // + lastOffsetDelta(4) + baseTimestamp(8) + maxTimestamp(8) + producerId(8) + producerEpoch(2)
        // + baseSequence(4) + recordsCount(4) + [records]

        // Build the inner record first
        ByteBuf recordBuf = Unpooled.buffer();
        // Record: attributes(1) + timestampDelta(varint) + offsetDelta(varint) + keyLength(varint) + key + valueLength(varint) + value + headers
        recordBuf.writeByte(0); // attributes: no compression, no timestamp type
        writeVarint(recordBuf, 0); // timestampDelta
        writeVarint(recordBuf, 0); // offsetDelta
        if (key != null) {
            writeVarint(recordBuf, key.length);
            recordBuf.writeBytes(key);
        } else {
            writeVarint(recordBuf, -1);
        }
        if (value != null) {
            writeVarint(recordBuf, value.length);
            recordBuf.writeBytes(value);
        } else {
            writeVarint(recordBuf, -1);
        }
        writeVarint(recordBuf, 0); // headers count

        int recordLen = recordBuf.readableBytes();

        // Now build the batch
        ByteBuf batchBuf = Unpooled.buffer();
        batchBuf.writeLong(offset); // baseOffset
        // batchLength: everything after this field
        // partitionLeaderEpoch(4) + magic(1) + crc(4) + attributes(2) + lastOffsetDelta(4) +
        // baseTimestamp(8) + maxTimestamp(8) + producerId(8) + producerEpoch(2) + baseSequence(4) + recordsCount(4)
        // + record-length-varint + recordData
        int recordLenVarintSize = varintEncodedSize(recordLen);
        int batchLength = 4 + 1 + 4 + 2 + 4 + 8 + 8 + 8 + 2 + 4 + 4 + recordLenVarintSize + recordLen;
        batchBuf.writeInt(batchLength);
        batchBuf.writeInt(0); // partitionLeaderEpoch
        batchBuf.writeByte(2); // magic = 2
        batchBuf.writeInt(0); // CRC (0 for mock — clients don't validate this)
        batchBuf.writeShort(0); // attributes
        batchBuf.writeInt(0); // lastOffsetDelta
        batchBuf.writeLong(System.currentTimeMillis()); // baseTimestamp
        batchBuf.writeLong(System.currentTimeMillis()); // maxTimestamp
        batchBuf.writeLong(0); // producerId
        batchBuf.writeShort(0); // producerEpoch
        batchBuf.writeInt(0); // baseSequence
        batchBuf.writeInt(1); // recordsCount
        // Per-record length varint prefix (matches real Kafka RecordBatch v2)
        writeVarint(batchBuf, recordLen);
        batchBuf.writeBytes(recordBuf);
        recordBuf.release();

        buf.writeBytes(batchBuf);
        batchBuf.release();
    }

    /** Number of bytes the unsigned-zigzag varint encoding of {@code value} occupies. */
    private int varintEncodedSize(int value) {
        int zigzag = (value << 1) ^ (value >> 31);
        int bytes = 1;
        while ((zigzag & ~0x7F) != 0) {
            bytes++;
            zigzag >>>= 7;
        }
        return bytes;
    }

    // --- ApiVersions (API key 18) ---

    private ByteBuf handleApiVersions(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);
        buf.writeShort(NONE); // error_code

        // Supported API versions — capped below flexible-version thresholds to avoid
        // clients switching to Request Header v2 (compact strings), which this decoder's
        // int16 readNullableString cannot parse. See channelRead0 for the fallback path.
        //   PRODUCE  flexible at v9  -> cap v3 (well within range, matches v0-v8 body shape)
        //   FETCH    flexible at v12 -> cap v10 (v11+ uses compact message format + tagged fields)
        //   METADATA flexible at v9  -> cap v8
        //   API_VERSIONS flexible at v3 -> cap v2 (the gate that triggers KIP-511 header switch)
        int[][] supportedApis = {
                {PRODUCE, 0, 3},
                {FETCH, 0, 10},
                {METADATA, 0, 8},
                {OFFSET_COMMIT, 0, 8},
                {OFFSET_FETCH, 0, 8},
                {FIND_COORDINATOR, 0, 4},
                {JOIN_GROUP, 0, 7},
                {HEARTBEAT, 0, 4},
                {LEAVE_GROUP, 0, 4},
                {SYNC_GROUP, 0, 5},
                {DESCRIBE_GROUPS, 0, 5},
                {LIST_GROUPS, 0, 4},
                {API_VERSIONS, 0, 2},
                {INIT_PRODUCER_ID, 0, 1},
                {DESCRIBE_CONFIGS, 0, 4}
        };

        buf.writeInt(supportedApis.length);
        for (int[] api : supportedApis) {
            buf.writeShort(api[0]); // apiKey
            buf.writeShort(api[1]); // minVersion
            buf.writeShort(api[2]); // maxVersion
        }

        // throttle_time_ms (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        return frameResponse(buf);
    }

    // --- InitProducerId (API key 22) ---

    private ByteBuf handleInitProducerId(ByteBuf msg, short apiVersion, int correlationId) {
        // Request: transactional_id (nullable string) + transaction_timeout_ms (int32)
        // v3+: producer_id (int64) + producer_epoch (int16)
        readNullableString(msg); // transactional_id
        msg.readInt(); // transaction_timeout_ms
        if (apiVersion >= 3) {
            msg.readLong(); // producer_id
            msg.readShort(); // producer_epoch
        }

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms — present in ALL versions
        buf.writeInt(0);

        buf.writeShort(NONE); // error_code
        buf.writeLong(1); // producer_id (arbitrary non-zero value)
        buf.writeShort(0); // producer_epoch

        return frameResponse(buf);
    }

    // --- OffsetCommit (API key 8) ---

    private ByteBuf handleOffsetCommit(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // v0-v1: topics array with partition results
        // v2+: throttle_time_ms first
        if (apiVersion >= 2) {
            buf.writeInt(0); // throttle_time_ms
        }

        // Topics array
        buf.writeInt(0); // no topics in response for simplicity
        // Actually for v0-v1 we need topic results
        // Let's return an empty topics array
        buf.writeInt(0);

        return frameResponse(buf);
    }

    // --- OffsetFetch (API key 9) ---

    private ByteBuf handleOffsetFetch(ByteBuf msg, short apiVersion, int correlationId) {
        // Read request to consume the bytes
        int topicCount = msg.readInt();
        for (int t = 0; t < topicCount; t++) {
            readNullableString(msg); // topic
            int partitionCount = msg.readInt();
            for (int p = 0; p < partitionCount; p++) {
                msg.readInt(); // partition
            }
        }
        if (apiVersion >= 2) {
            msg.readByte(); // require_stable
        }

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // Topics array — return 0 offset for everything
        buf.writeInt(0);

        // error_code (v2+)
        if (apiVersion >= 2) {
            buf.writeShort(NONE);
        }

        return frameResponse(buf);
    }

    // --- FindCoordinator (API key 10) ---

    private ByteBuf handleFindCoordinator(ChannelHandlerContext ctx, short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        buf.writeShort(NONE); // error_code
        writeNullableString(buf, resolveBrokerHost(ctx)); // host
        buf.writeInt(brokerPort); // port
        buf.writeInt(0); // node_id

        return frameResponse(buf);
    }

    // --- JoinGroup (API key 11) ---

    private ByteBuf handleJoinGroup(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        buf.writeShort(NONE); // error_code
        buf.writeInt(0); // generation_id
        writeNullableString(buf, ""); // protocol_name
        writeNullableString(buf, ""); // leader
        writeNullableString(buf, ""); // skip_assignment (v5+ handled below)

        // members array — empty
        buf.writeInt(0);

        return frameResponse(buf);
    }

    // --- Heartbeat (API key 12) ---

    private ByteBuf handleHeartbeat(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        buf.writeShort(NONE); // error_code

        return frameResponse(buf);
    }

    // --- LeaveGroup (API key 13) ---

    private ByteBuf handleLeaveGroup(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        buf.writeShort(NONE); // error_code

        // members array (v3+)
        if (apiVersion >= 3) {
            buf.writeInt(0);
        }

        return frameResponse(buf);
    }

    // --- SyncGroup (API key 14) ---

    private ByteBuf handleSyncGroup(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        buf.writeShort(NONE); // error_code
        writeNullableBytes(buf, new byte[0]); // assignment

        return frameResponse(buf);
    }

    // --- DescribeGroups (API key 15) ---

    private ByteBuf handleDescribeGroups(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        // groups array — empty
        buf.writeInt(0);

        return frameResponse(buf);
    }

    // --- ListGroups (API key 16) ---

    private ByteBuf handleListGroups(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v1+)
        if (apiVersion >= 1) {
            buf.writeInt(0);
        }

        buf.writeShort(NONE); // error_code

        // groups array — empty
        buf.writeInt(0);

        return frameResponse(buf);
    }

    // --- DescribeConfigs (API key 32) ---

    private ByteBuf handleDescribeConfigs(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v0+)
        buf.writeInt(0);

        // configs array — empty
        buf.writeInt(0);

        return frameResponse(buf);
    }

    // --- Helper methods ---

    private ByteBuf buildEmptyResponse(int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);
        buf.writeShort(NONE); // error_code
        return frameResponse(buf);
    }

    private ByteBuf buildErrorResponse(int correlationId, short errorCode) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);
        buf.writeShort(errorCode);
        return frameResponse(buf);
    }

    /**
     * Wrap the response payload in a 4-byte size frame.
     */
    private ByteBuf frameResponse(ByteBuf payload) {
        ByteBuf frame = Unpooled.buffer(4 + payload.readableBytes());
        frame.writeInt(payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    private String readNullableString(ByteBuf buf) {
        short length = buf.readShort();
        if (length < 0) return null;
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Read a Kafka "compact string" (Request/Response Header v2+, flexible versions).
     * Compact nullable string: uvarint length where 0 = null, N = N-1 bytes follow.
     * Used as a fallback in {@link #channelRead0} when a client sends a v2 header.
     */
    private String readCompactString(ByteBuf buf) {
        int length = readUnsignedVarint(buf);
        if (length == 0) return null; // 0 in compact encoding means null
        int actualLen = length - 1;   // compact encodes real length as N+1
        if (actualLen < 0) return null;
        byte[] bytes = new byte[actualLen];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Read a Kafka uvarint (big-endian base-128 varint, NOT zig-zag encoded).
     */
    private int readUnsignedVarint(ByteBuf buf) {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = buf.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    private void writeNullableString(ByteBuf buf, String value) {
        if (value == null) {
            buf.writeShort(-1);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            buf.writeShort(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    private void writeNullableBytes(ByteBuf buf, byte[] value) {
        if (value == null) {
            buf.writeInt(-1);
        } else {
            buf.writeInt(value.length);
            buf.writeBytes(value);
        }
    }

    private void writeVarint(ByteBuf buf, int value) {
        // Unsigned varint encoding (zigzag + varint)
        int zigzag = (value << 1) ^ (value >> 31);
        while ((zigzag & ~0x7F) != 0) {
            buf.writeByte((byte) ((zigzag & 0x7F) | 0x80));
            zigzag >>>= 7;
        }
        buf.writeByte((byte) zigzag);
    }

    /**
     * Read a zig-zag varint as used inside Kafka RecordBatch records
     * (timestampDelta, offsetDelta, key/value lengths). Mirrors {@link #writeVarint}.
     */
    private int readVarint(ByteBuf buf) {
        int raw = readUnsignedVarint(buf);
        // un-zigzag
        return (raw >>> 1) ^ -(raw & 1);
    }

    /**
     * Container for a parsed record's key/value from a Kafka RecordBatch v2.
     */
    private static final class ParsedRecord {
        final byte[] key;
        final byte[] value;
        ParsedRecord(byte[] key, byte[] value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * Parse all records out of a Kafka RecordBatch v2 byte array.
     *
     * <p>{@code batchData} is the full framed batch as read from the wire after the
     * outer partition length prefix:
     * <pre>
     *   baseOffset(8) + batchLength(4) + partitionLeaderEpoch(4) + magic(1) +
     *   crc(4) + attributes(2) + lastOffsetDelta(4) + baseTimestamp(8) +
     *   maxTimestamp(8) + producerId(8) + producerEpoch(2) + baseSequence(4) +
     *   recordsCount(4) + [records...]
     * </pre>
     * i.e. 61 bytes of header before the first record. The magic byte sits at
     * offset 16 (8 baseOffset + 4 batchLength + 4 partitionLeaderEpoch).
     *
     * <p>Each record: length(varint) + attributes(1) + timestampDelta(varint) +
     * offsetDelta(varint) + keyLen(varint,-1=null) + [key] + valueLen(varint,-1=null) + [value] +
     * headerCount(varint) + [headers...]
     *
     * @return parsed records, or null if the batch is malformed / not magic 2
     *         (caller should then fall back to raw storage)
     */
    private List<ParsedRecord> parseRecordBatch(byte[] batchData) {
        // Need at least the full 61-byte header (21 fixed fields before recordsCount
        // is read at offset 57, plus the 4-byte recordsCount itself).
        if (batchData == null || batchData.length < 61) return null;
        // magic at offset 16 (8 baseOffset + 4 batchLength + 4 partitionLeaderEpoch)
        if (batchData[16] != 2) return null; // only magic 2 supported

        try {
            ByteBuf buf = Unpooled.wrappedBuffer(batchData);
            buf.skipBytes(8);  // baseOffset
            buf.skipBytes(4);  // batchLength
            buf.skipBytes(4);  // partitionLeaderEpoch
            buf.readByte();    // magic (==2, already validated)
            buf.skipBytes(4);  // crc
            buf.skipBytes(2);  // attributes
            buf.skipBytes(4);  // lastOffsetDelta
            buf.skipBytes(8);  // baseTimestamp
            buf.skipBytes(8);  // maxTimestamp
            buf.skipBytes(8);  // producerId
            buf.skipBytes(2);  // producerEpoch
            buf.skipBytes(4);  // baseSequence
            int recordsCount = buf.readInt();

            List<ParsedRecord> records = new ArrayList<ParsedRecord>(recordsCount);
            for (int i = 0; i < recordsCount && buf.isReadable(); i++) {
                int recordLen = readVarint(buf);
                int recordStart = buf.readerIndex();
                // record body: attributes(1) + timestampDelta(varint) + offsetDelta(varint) +
                //              keyLen(varint) + [key] + valueLen(varint) + [value] + headerCount(varint) + headers
                buf.readByte(); // attributes
                readVarint(buf); // timestampDelta
                readVarint(buf); // offsetDelta

                int keyLen = readVarint(buf);
                byte[] key = null;
                if (keyLen >= 0) {
                    key = new byte[keyLen];
                    buf.readBytes(key);
                }

                int valueLen = readVarint(buf);
                byte[] value = null;
                if (valueLen >= 0) {
                    value = new byte[valueLen];
                    buf.readBytes(value);
                }
                records.add(new ParsedRecord(key, value));

                // Skip any remaining bytes in this record (headers etc.) so the next
                // record starts at the right position even if we didn't parse everything.
                int consumed = buf.readerIndex() - recordStart;
                int remaining = recordLen - consumed;
                if (remaining > 0) {
                    buf.skipBytes(remaining);
                }
            }
            buf.release();
            return records;
        } catch (Exception e) {
            log.debug("Failed to parse RecordBatch v2: {}", e.getMessage());
            return null;
        }
    }

    private long readLongFromBytes(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 56)
                | ((long) (data[offset + 1] & 0xFF) << 48)
                | ((long) (data[offset + 2] & 0xFF) << 40)
                | ((long) (data[offset + 3] & 0xFF) << 32)
                | ((long) (data[offset + 4] & 0xFF) << 24)
                | ((long) (data[offset + 5] & 0xFF) << 16)
                | ((long) (data[offset + 6] & 0xFF) << 8)
                | ((long) (data[offset + 7] & 0xFF));
    }

    private void writeLongToBytes(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >> 56);
        data[offset + 1] = (byte) (value >> 48);
        data[offset + 2] = (byte) (value >> 40);
        data[offset + 3] = (byte) (value >> 32);
        data[offset + 4] = (byte) (value >> 24);
        data[offset + 5] = (byte) (value >> 16);
        data[offset + 6] = (byte) (value >> 8);
        data[offset + 7] = (byte) value;
    }

    /**
     * Resolve the broker host for Metadata/DescribeCluster responses.
     * Uses the same Docker gateway detection logic as Pulsar:
     * - Docker-internal clients (same subnet, not gateway) → cachedBrokerHost
     * - Docker gateway clients (host machine via port mapping) → advertisedHost
     * - External clients → advertisedHost or local IP
     */
    private String resolveBrokerHost(ChannelHandlerContext ctx) {
        try {
            java.net.InetSocketAddress remote = (java.net.InetSocketAddress) ctx.channel().remoteAddress();
            java.net.InetSocketAddress local = (java.net.InetSocketAddress) ctx.channel().localAddress();
            if (remote != null && local != null) {
                byte[] remoteBytes = remote.getAddress().getAddress();
                byte[] localBytes = local.getAddress().getAddress();
                if (remoteBytes.length == 4 && localBytes.length == 4) {
                    boolean sameSubnet = remoteBytes[0] == localBytes[0] && remoteBytes[1] == localBytes[1];
                    boolean isPrivate = localBytes[0] == 10
                            || (localBytes[0] == (byte) 172 && (localBytes[1] & 0xFF) >= 16 && (localBytes[1] & 0xFF) <= 31)
                            || (localBytes[0] == (byte) 192 && localBytes[1] == (byte) 168);
                    if (sameSubnet && isPrivate) {
                        boolean isGateway = (remoteBytes[3] & 0xFF) == 1;
                        if (isGateway) {
                            if (advertisedHost != null && !advertisedHost.isEmpty()) {
                                return advertisedHost;
                            }
                            return local.getAddress().getHostAddress();
                        }
                        return cachedBrokerHost != null ? cachedBrokerHost : local.getAddress().getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        if (advertisedHost != null && !advertisedHost.isEmpty()) {
            return advertisedHost;
        }
        return cachedBrokerHost != null ? cachedBrokerHost : "127.0.0.1";
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("KafkaProtocolDecoder error: {}", cause.getMessage());
        ctx.close();
    }

    // --- Result containers ---

    private static final class ProduceTopicResult {
        final String topic;
        final List<ProducePartitionResult> partitions;

        ProduceTopicResult(String topic, List<ProducePartitionResult> partitions) {
            this.topic = topic;
            this.partitions = partitions;
        }
    }

    private static final class ProducePartitionResult {
        final int partition;
        final long offset;

        ProducePartitionResult(int partition, long offset) {
            this.partition = partition;
            this.offset = offset;
        }
    }

    private static final class FetchTopicResult {
        final String topic;
        final List<FetchPartitionResult> partitions;

        FetchTopicResult(String topic, List<FetchPartitionResult> partitions) {
            this.topic = topic;
            this.partitions = partitions;
        }
    }

    private static final class FetchPartitionResult {
        final int partition;
        final List<KafkaMessageStore.StoredMessage> messages;

        FetchPartitionResult(int partition, List<KafkaMessageStore.StoredMessage> messages) {
            this.partition = partition;
            this.messages = messages;
        }
    }
}
