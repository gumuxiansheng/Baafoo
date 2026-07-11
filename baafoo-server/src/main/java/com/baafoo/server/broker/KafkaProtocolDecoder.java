package com.baafoo.server.broker;

import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.FaultInjection;
import com.baafoo.core.model.MqRelationship;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.FaultInjector;
import com.baafoo.core.util.HexUtils;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.handler.AgentResolver;
import com.baafoo.server.storage.StorageService;
import com.baafoo.server.broker.codec.KafkaCodecUtils;
import com.baafoo.server.broker.codec.KafkaFetchCodec;
import com.baafoo.server.broker.codec.KafkaFetchCodecV12;
import com.baafoo.server.broker.codec.KafkaFlexibleCodec;
import com.baafoo.server.broker.codec.KafkaMetadataCodec;
import com.baafoo.server.broker.codec.KafkaMetadataCodecV9;
import com.baafoo.server.broker.codec.KafkaProduceCodec;
import com.baafoo.server.broker.codec.KafkaProduceCodecV9;
import com.baafoo.server.broker.codec.KafkaProtocolVersions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
    private static final short LIST_OFFSETS = 2;
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

    /** Tracks the last subscribed topic from JoinGroup requests, used by SyncGroup assignment. */
    private volatile String lastSubscribedTopic;

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
        // Read the request header.
        int startPos = msg.readerIndex();
        short apiKey = msg.readShort();
        short apiVersion = msg.readShort();
        int correlationId = msg.readInt();

        // Debug: log raw bytes for protocol version negotiation troubleshooting.
        // Guard with isDebugEnabled() so the hex conversion is skipped entirely
        // when debug logging is disabled (Low 43) — the previous code allocated
        // a byte[] and built a hex string on every request regardless of log level.
        if (log.isDebugEnabled()) {
            int remainingBytes = msg.readableBytes();
            int peekLen = Math.min(remainingBytes, 16);
            byte[] peekBytes = new byte[peekLen];
            msg.getBytes(msg.readerIndex(), peekBytes);
            StringBuilder hexSb = new StringBuilder();
            for (byte b : peekBytes) {
                HexUtils.appendByte(hexSb, b);
                hexSb.append(' ');
            }
            log.debug("Kafka raw header: apiKey={}, apiVersion={}, correlationId={}, ridx={}, widx={}, nextBytes=[{}]",
                    apiKey, apiVersion, correlationId, msg.readerIndex(), msg.writerIndex(), hexSb.toString().trim());
        }

        // Request Header v1 (non-flexible): int16-prefixed nullable string for clientId
        // Request Header v2 (flexible, KIP-482): compact string + tag buffer
        //
        // ApiVersions is special per KIP-511: its REQUEST header is always v1
        // (non-flexible) even for v3+, because the client doesn't know if the
        // broker supports flexible versions yet. Only the body is flexible.
        String clientId;
        boolean headerIsFlexible = apiKey != API_VERSIONS
                && KafkaProtocolVersions.isFlexible(apiKey, apiVersion);
        log.debug("Header parsing: apiKey={}, apiVersion={}, headerIsFlexible={}", apiKey, apiVersion, headerIsFlexible);
        if (headerIsFlexible) {
            clientId = KafkaFlexibleCodec.readCompactString(msg);
            KafkaFlexibleCodec.skipTagBuffer(msg); // header tag buffer
        } else {
            clientId = readNullableString(msg);
        }

        log.debug("Kafka request: apiKey={}, apiVersion={}, correlationId={}, clientId={}",
                apiKey, apiVersion, correlationId, clientId);

        ByteBuf response;
        try {
            switch (apiKey) {
                case PRODUCE:
                    response = handleProduce(ctx, msg, apiVersion, correlationId);
                    break;
                case FETCH:
                    response = handleFetch(ctx, msg, apiVersion, correlationId);
                    break;
                case LIST_OFFSETS:
                    response = handleListOffsets(msg, apiVersion, correlationId);
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
                    response = handleJoinGroup(msg, apiVersion, correlationId);
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

        if (response != null) {
            // Debug: log response size for troubleshooting
            log.debug("Kafka response: apiKey={}, apiVersion={}, correlationId={}, responseBytes={}",
                    apiKey, apiVersion, correlationId, response.readableBytes());
            if (apiKey == API_VERSIONS) {
                // Hex dump for ApiVersions response troubleshooting.
                // Guard with isDebugEnabled() (Low 43).
                if (log.isDebugEnabled()) {
                    byte[] dumpBytes = new byte[Math.min(response.readableBytes(), 64)];
                    response.getBytes(response.readerIndex(), dumpBytes);
                    StringBuilder hexResp = new StringBuilder();
                    for (byte b : dumpBytes) {
                        HexUtils.appendByte(hexResp, b);
                        hexResp.append(' ');
                    }
                    log.debug("ApiVersions v{} response hex (first {} bytes): [{}]",
                            apiVersion, dumpBytes.length, hexResp.toString().trim());
                }
            }
            ctx.writeAndFlush(response);
        }
    }

    // --- ListOffsets (API key 2) ---

    private ByteBuf handleListOffsets(ByteBuf msg, short apiVersion, int correlationId) {
        // ListOffsets request: replica_id(int32) + isolation_level(v1+, int8) +
        //   topics[] { name, partitions[] { partition_index, current_leader_epoch(v4+), timestamp } }
        // Response v0: topics[] { name, partitions[] { partition_index, error_code, old_style_offsets[]int64 } }
        // Response v1-v3: topics[] { name, partitions[] { partition_index, error_code, timestamp, offset } }
        // Response v4+: topics[] { name, partitions[] { partition_index, error_code, timestamp, offset, leader_epoch } }

        // Parse request to get topic/partition info
        msg.readInt(); // replica_id (-1 for consumer)
        if (apiVersion >= 1) { msg.readByte(); } // isolation_level

        int topicCount = msg.readInt();
        List<String> topics = new ArrayList<String>();
        List<Integer> partitionIndexes = new ArrayList<Integer>();
        if (topicCount > 0 && topicCount < 1000) {
            for (int t = 0; t < topicCount; t++) {
                String topic = readNullableString(msg);
                topics.add(topic);
                int partCount = msg.readInt();
                for (int p = 0; p < partCount; p++) {
                    int partIndex = msg.readInt(); // partition_index
                    partitionIndexes.add(partIndex);
                    if (apiVersion >= 4) { msg.readInt(); } // current_leader_epoch
                    msg.readLong(); // timestamp (-2=earliest, -1=latest)
                }
            }
        }

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);
        // throttle_time_ms (v2+)
        if (apiVersion >= 2) { buf.writeInt(0); }

        // Response topics
        buf.writeInt(topics.size());
        for (int t = 0; t < topics.size(); t++) {
            writeNullableString(buf, topics.get(t));
            buf.writeInt(partitionIndexes.size()); // partition count (simplified: all partitions per topic)
            for (int partIndex : partitionIndexes) {
                buf.writeInt(partIndex); // partition_index
                buf.writeShort(NONE); // error_code
                if (apiVersion == 0) {
                    // v0: old_style_offsets (array of int64)
                    buf.writeInt(1); // array length = 1
                    buf.writeLong(0); // offset
                } else {
                    // v1+: timestamp + offset
                    buf.writeLong(0); // timestamp
                    buf.writeLong(0); // offset
                    if (apiVersion >= 4) {
                        buf.writeInt(-1); // leader_epoch (-1 = unknown)
                    }
                }
            }
        }

        return frameResponse(buf);
    }

    // --- Metadata (API key 3) ---

    private ByteBuf handleMetadata(ChannelHandlerContext ctx, ByteBuf msg, short apiVersion, int correlationId) {
        KafkaMetadataCodec.MetadataRequest request;
        if (apiVersion >= 9) {
            request = KafkaMetadataCodecV9.parseRequest(msg);
        } else {
            request = KafkaMetadataCodec.parseRequest(msg, apiVersion);
        }
        log.debug("Metadata request: apiVersion={}, topicCount={}", apiVersion, request.getTopics().size());
        for (int i = 0; i < request.getTopics().size(); i++) {
            log.debug("Metadata request: topic[{}]={}", i, request.getTopics().get(i));
        }
        log.debug("Metadata response: returning {} topics, brokerHost={}, brokerPort={}",
                request.getTopics().size(), resolveBrokerHost(ctx), brokerPort);

        if (apiVersion >= 9) {
            return KafkaMetadataCodecV9.serializeResponse(correlationId,
                    resolveBrokerHost(ctx), brokerPort, request.getTopics());
        } else {
            return KafkaMetadataCodec.serializeResponse(correlationId, apiVersion,
                    resolveBrokerHost(ctx), brokerPort, request.getTopics());
        }
    }

    // --- Produce (API key 0) ---

    private ByteBuf handleProduce(ChannelHandlerContext ctx, ByteBuf msg, short apiVersion, int correlationId) {
        KafkaProduceCodec.ProduceRequest request;
        if (apiVersion >= 9) {
            request = KafkaProduceCodecV9.parseRequest(msg);
        } else {
            request = KafkaProduceCodec.parseRequest(msg, apiVersion);
        }
        log.debug("Produce request (direction=produce): apiVersion={}, acks={}, timeoutMs={}", apiVersion, request.getAcks(), request.getTimeoutMs());

        // Resolve agent + environment + rules ONCE per produce request.
        AgentResolver.AgentInfo agentInfo = matchHelper.resolveAgent(ctx);
        EnvironmentMode mode = matchHelper.resolveMode(ctx);
        List<Rule> rules = matchHelper.filterRulesByEnvironment(storage.listRules(), agentInfo.environment);
        boolean shouldRecord = (mode == EnvironmentMode.RECORD || mode == EnvironmentMode.RECORD_AND_STUB);

        KafkaFaultAggregation faults = new KafkaFaultAggregation();
        List<KafkaProduceCodec.ProduceTopicResult> results = new ArrayList<KafkaProduceCodec.ProduceTopicResult>();

        for (KafkaProduceCodec.ProduceTopic topic : request.getTopics()) {
            List<KafkaProduceCodec.ProducePartitionResult> partitionResults = new ArrayList<KafkaProduceCodec.ProducePartitionResult>();
            List<MqRelationship> relationships = storage.listMqRelationshipsByFrom("kafka", topic.getName());

            for (KafkaProduceCodec.ProducePartition partition : topic.getPartitions()) {
                long offset = processProducePartition(ctx, topic.getName(), partition.getPartition(),
                        partition.getBatchData(), agentInfo, mode, rules, shouldRecord, relationships, faults);
                partitionResults.add(new KafkaProduceCodec.ProducePartitionResult(partition.getPartition(), offset));
            }
            results.add(new KafkaProduceCodec.ProduceTopicResult(topic.getName(), partitionResults));
        }

        // Check for remaining bytes in request (indicates parsing issue)
        if (msg.isReadable()) {
            log.warn("Produce request has {} remaining bytes after parsing! This indicates a request parsing issue.", msg.readableBytes());
        }

        // Apply terminal faults that bypass the normal response.
        if (faults.connectionReset) {
            log.info("Kafka fault injected: CONNECTION_RESET on channel {}", ctx.channel());
            ctx.close();
            return null;
        }

        short errorCode = faults.error ? faults.errorCode : NONE;
        int throttleMs = faults.throttle ? faults.throttleMs : 0;
        ByteBuf response;
        if (apiVersion >= 9) {
            response = KafkaProduceCodecV9.serializeResponse(correlationId,
                    new KafkaProduceCodec.ProduceResponse(results), errorCode);
        } else {
            response = KafkaProduceCodec.serializeResponse(correlationId, apiVersion,
                    new KafkaProduceCodec.ProduceResponse(results), errorCode, throttleMs);
        }

        if (faults.delay && faults.delayMs > 0) {
            log.info("Kafka fault injected: DELAY {}ms before produce response", faults.delayMs);
            ctx.executor().schedule((Runnable) () -> ctx.writeAndFlush(response), faults.delayMs, TimeUnit.MILLISECONDS);
            return null;
        }

        return response;
    }

    /**
     * Resolve the response charset for encoding a stub body. Falls back to UTF-8
     * when the rule does not declare a non-UTF-8 charset on the matched ResponseEntry.
     */
    private static Charset resolveResponseCharset(ResponseEntry resp) {
        if (resp == null) return StandardCharsets.UTF_8;
        String cs = resp.getCharset();
        if (cs == null || cs.isEmpty() || "UTF-8".equalsIgnoreCase(cs)) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(cs);
        } catch (Exception e) {
            log.warn("Unsupported response charset '{}', falling back to UTF-8", cs);
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Re-decode the original request bytes using the matched rule's requestCharset
     * so that recording captures the correct text when the client used GBK/GB2312/Big5.
     * Returns {@code defaultBody} when the rule does not declare a non-UTF-8 charset.
     */
    private static String decodeRequestBody(byte[] data, Rule rule, String defaultBody) {
        if (rule == null || data == null) return defaultBody;
        String cs = rule.getRequestCharset();
        if (cs == null || cs.isEmpty() || "UTF-8".equalsIgnoreCase(cs)) return defaultBody;
        try {
            return new String(data, Charset.forName(cs));
        } catch (Exception e) {
            log.warn("Unsupported request charset '{}', falling back to UTF-8", cs);
            return defaultBody;
        }
    }

    private long processProducePartition(ChannelHandlerContext ctx, String topic, int partition,
                                           byte[] batchData, AgentResolver.AgentInfo agentInfo,
                                           EnvironmentMode mode, List<Rule> rules, boolean shouldRecord,
                                           List<MqRelationship> relationships, KafkaFaultAggregation faults) {
        // batchData already contains the full RecordBatch (baseOffset + batchLength + ...).
        // Pre-compute the baseOffset for this batch (the offset the first record will get).
        long batchBaseOffset = messageStore.getOffset(topic, partition);
        byte[] rawBatch = batchData;
        writeLongToBytes(rawBatch, 0, batchBaseOffset);

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
                    // Evaluate Kafka protocol faults configured on the matched rule.
                    evaluateKafkaFaults(m.getRule().getFaultInjection(), faults);

                    // Re-decode request body with the matched rule's charset for recording
                    // so GBK/GB2312/Big5 payloads are captured as readable text.
                    String recordedBody = decodeRequestBody(rec.value, m.getRule(), bodyStr);

                    // Record the original payload (so replays show what the app sent).
                    if (shouldRecord) {
                        String respBody = null;
                        if ((mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB)
                                && m.getResponse() != null && m.getResponse().getBody() != null) {
                            respBody = m.getResponse().getBody();
                        }
                        matchHelper.record(m.getRule().getId(), "kafka", topic, recordedBody, respBody, agentInfo, "produce");
                    }
                    // In STUB / RECORD_AND_STUB, replace the value with the stub body so
                    // consumers fetch the stub instead of the producer's original payload.
                    if (mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB) {
                        ResponseEntry resp = m.getResponse();
                        byte[] stubValue = resp != null && resp.getBody() != null
                                ? resp.getBody().getBytes(resolveResponseCharset(resp)) : rec.value;
                        lastOffset = messageStore.append(topic, partition, rec.key, stubValue);
                        deriveMqRelationships(ctx, relationships, topic, partition, rec, agentInfo.environment);
                    } else {
                        // PASSTHROUGH / RECORD — store the original record with raw batch.
                        lastOffset = messageStore.append(topic, partition, rec.key, rec.value, rawBatch);
                        deriveMqRelationships(ctx, relationships, topic, partition, rec, agentInfo.environment);
                    }
                } else {
                    // Unmatched — store the original record with raw batch (passthrough behaviour).
                    // Only matched requests should be recorded; skip recording for unmatched.
                    lastOffset = messageStore.append(topic, partition, rec.key, rec.value, rawBatch);
                    deriveMqRelationships(ctx, relationships, topic, partition, rec, agentInfo.environment);
                }
            }
            offset = lastOffset;
        } else {
            // Non-v2 batch or parse failed — fall back to storing the raw batch,
            // but still attempt a topic-only match so topic rules can stub it.
            MatchEngine.MatchResult m = matchHelper.match(rules, "kafka", topic, null);
            if (m.isMatched() && (mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB)) {
                evaluateKafkaFaults(m.getRule().getFaultInjection(), faults);
                ResponseEntry resp = m.getResponse();
                if (resp != null && resp.getBody() != null) {
                    byte[] stubValue = resp.getBody().getBytes(resolveResponseCharset(resp));
                    offset = messageStore.append(topic, partition, null, stubValue);
                    if (shouldRecord) {
                        matchHelper.record(m.getRule().getId(), "kafka", topic, null, resp.getBody(), agentInfo, "produce");
                    }
                    deriveMqRelationships(ctx, relationships, topic, partition,
                            new ParsedRecord(null, stubValue), agentInfo.environment);
                } else {
                    offset = messageStore.append(topic, partition, null, batchData);
                    deriveMqRelationships(ctx, relationships, topic, partition,
                            new ParsedRecord(null, batchData), agentInfo.environment);
                }
            } else {
                if (shouldRecord && m.isMatched()) {
                    matchHelper.record(m.getRule().getId(), "kafka", topic, null, null, agentInfo, "produce");
                }
                offset = messageStore.append(topic, partition, null, batchData);
                deriveMqRelationships(ctx, relationships, topic, partition,
                        new ParsedRecord(null, batchData), agentInfo.environment);
            }
        }
        return offset;
    }

    private void evaluateKafkaFaults(FaultInjection faultInjection, KafkaFaultAggregation faults) {
        if (faultInjection == null) {
            return;
        }
        FaultInjector.FaultResult result = FaultInjector.evaluate(faultInjection, ThreadLocalRandom.current());
        if (!result.isNoFault()) {
            log.info("Kafka fault evaluated: action={}", result.getAction());
        }
        faults.apply(result);
    }

    /**
     * Derive downstream Kafka messages from an upstream record according to
     * configured MQ relationships. Relationships with a non-zero delay are
     * scheduled on the channel's event loop.
     */
    private void deriveMqRelationships(ChannelHandlerContext ctx, List<MqRelationship> relationships,
                                        String topic, int partition, ParsedRecord rec, String environment) {
        if (relationships == null || relationships.isEmpty()) {
            return;
        }
        String bodyStr = rec.value != null ? new String(rec.value, StandardCharsets.UTF_8) : null;
        String keyStr = rec.key != null ? new String(rec.key, StandardCharsets.UTF_8) : null;
        for (MqRelationship rel : relationships) {
            if (!rel.isEnabled() || !"kafka".equals(rel.getToProtocol())) {
                continue;
            }
            String derivedKey = MqRelationshipRenderer.renderKey(rel, topic, keyStr, partition, bodyStr, environment);
            String derivedValue = MqRelationshipRenderer.renderValue(rel, topic, keyStr, partition, bodyStr, environment);
            Runnable appendTask = new Runnable() {
                @Override
                public void run() {
                    byte[] keyBytes = derivedKey != null ? derivedKey.getBytes(StandardCharsets.UTF_8) : null;
                    byte[] valueBytes = derivedValue != null ? derivedValue.getBytes(StandardCharsets.UTF_8) : null;
                    messageStore.append(rel.getToTopic(), partition, keyBytes, valueBytes);
                    log.info("Derived Kafka message from {} to {} (partition={}, delayMs={})",
                            topic, rel.getToTopic(), partition, rel.getDelayMs());
                }
            };
            if (rel.getDelayMs() > 0) {
                ctx.executor().schedule(appendTask, rel.getDelayMs(), TimeUnit.MILLISECONDS);
            } else {
                appendTask.run();
            }
        }
    }

    /**
     * Accumulates Kafka protocol faults found while processing a Produce request.
     * Multiple records / partitions may trigger faults; the most severe effect wins
     * for the aggregated response (error code, throttle time, delay time).
     */
    private static class KafkaFaultAggregation {
        boolean error;
        short errorCode;
        boolean throttle;
        int throttleMs;
        boolean delay;
        long delayMs;
        boolean connectionReset;

        void apply(FaultInjector.FaultResult result) {
            if (result == null || result.isNoFault()) {
                return;
            }
            if (result.isKafkaError()) {
                this.error = true;
                this.errorCode = (short) result.getErrorCode();
            }
            if (result.isKafkaThrottle()) {
                this.throttle = true;
                this.throttleMs = (int) Math.max(this.throttleMs, result.getDelayMs());
            }
            if (result.isKafkaDelay()) {
                this.delay = true;
                this.delayMs = Math.max(this.delayMs, result.getDelayMs());
            }
            if (result.isKafkaConnectionReset()) {
                this.connectionReset = true;
            }
        }
    }

    // --- Fetch (API key 1) ---

    private ByteBuf handleFetch(ChannelHandlerContext ctx, ByteBuf msg, short apiVersion, int correlationId) {
        KafkaFetchCodec.FetchRequest request;
        if (apiVersion >= 12) {
            request = KafkaFetchCodecV12.parseRequest(msg);
        } else {
            request = KafkaFetchCodec.parseRequest(msg, apiVersion);
        }
        log.debug("Fetch request (direction=consume): apiVersion={}, topics={}", apiVersion, request.getTopics().size());

        // Resolve agent + environment + rules for consume-side stub
        AgentResolver.AgentInfo agentInfo = matchHelper.resolveAgent(ctx);
        EnvironmentMode mode = matchHelper.resolveMode(ctx);
        List<Rule> rules = matchHelper.filterRulesByEnvironment(storage.listRules(), agentInfo.environment);

        List<KafkaFetchCodec.FetchTopicResult> results = new ArrayList<KafkaFetchCodec.FetchTopicResult>();

        for (KafkaFetchCodec.FetchTopic topic : request.getTopics()) {
            List<KafkaFetchCodec.FetchPartitionResult> partitionResults = new ArrayList<KafkaFetchCodec.FetchPartitionResult>();

            for (KafkaFetchCodec.FetchPartition partition : topic.getPartitions()) {
                int partitionId = partition.getPartition();
                long fetchOffset = partition.getFetchOffset();
                int maxBytes = partition.getPartitionMaxBytes();

                // Fetch messages from store
                List<KafkaMessageStore.StoredMessage> messages =
                        messageStore.fetch(topic.getTopic(), partitionId, fetchOffset, maxBytes);
                log.info("Kafka Fetch: topic={}, partition={}, fetchOffset={}, maxBytes={}, found={} messages, storeOffset={}",
                        topic.getTopic(), partitionId, fetchOffset, maxBytes, messages.size(),
                        messageStore.getOffset(topic.getTopic(), partitionId));

                // If no stored messages and STUB mode, try to match rules and return stub response
                if (messages.isEmpty() && (mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB)) {
                    MatchEngine.MatchResult m = matchHelper.match(rules, "kafka", topic.getTopic(), null);
                    if (m.isMatched()) {
                        ResponseEntry resp = m.getResponse();
                        if (resp != null && resp.getBody() != null) {
                            byte[] stubValue = resp.getBody().getBytes(resolveResponseCharset(resp));
                            long offset = messageStore.append(topic.getTopic(), partitionId, null, stubValue);
                            messages = messageStore.fetch(topic.getTopic(), partitionId, offset, maxBytes);
                            if (mode == EnvironmentMode.RECORD_AND_STUB) {
                                // Consumer Fetch stub: requestBody = null, responseBody = stub body
                                matchHelper.record(m.getRule().getId(), "kafka", topic.getTopic(), null, resp.getBody(), agentInfo, "consume");
                            }
                            log.info("Kafka Fetch stub: topic={}, partition={}, matched rule={}, stubBodySize={}",
                                    topic.getTopic(), partitionId, m.getRule().getId(), stubValue.length);
                        }
                    }
                }

                partitionResults.add(new KafkaFetchCodec.FetchPartitionResult(partitionId, messages));
            }
            results.add(new KafkaFetchCodec.FetchTopicResult(topic.getTopic(), partitionResults));
        }

        if (apiVersion >= 12) {
            return KafkaFetchCodecV12.serializeResponse(correlationId,
                    new KafkaFetchCodec.FetchResponse(results), messageStore);
        } else {
            return KafkaFetchCodec.serializeResponse(correlationId, apiVersion,
                    new KafkaFetchCodec.FetchResponse(results), messageStore);
        }
    }

    // --- ApiVersions (API key 18) ---

    private ByteBuf handleApiVersions(short apiVersion, int correlationId) {
        ByteBuf buf = Unpooled.buffer();
        // ApiVersions response header is ALWAYS v0 (correlation_id only) —
        // even for v3+. This is a special case in the Kafka protocol because
        // the client must parse the response before knowing the server version.
        // (Both request and response headers for ApiVersions are always v0.)
        buf.writeInt(correlationId);

        int[][] supportedApis = com.baafoo.server.broker.codec.KafkaProtocolVersions.SUPPORTED_APIS;

        if (apiVersion >= 3) {
            // Flexible (v3+) body format — but header is still v0
            buf.writeShort(NONE); // error_code
            // Compact array of api versions
            KafkaFlexibleCodec.writeCompactArrayLength(buf, supportedApis.length);
            for (int[] api : supportedApis) {
                buf.writeShort(api[0]); // apiKey
                buf.writeShort(api[1]); // minVersion
                buf.writeShort(api[2]); // maxVersion
                KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // per-entry tag buffer
            }
            buf.writeInt(0); // throttle_time_ms
            KafkaFlexibleCodec.writeEmptyTagBuffer(buf); // top-level tag buffer
        } else {
            // Non-flexible (v0-v2) body format
            buf.writeShort(NONE); // error_code
            buf.writeInt(supportedApis.length);
            for (int[] api : supportedApis) {
                buf.writeShort(api[0]); // apiKey
                buf.writeShort(api[1]); // minVersion
                buf.writeShort(api[2]); // maxVersion
            }
            if (apiVersion >= 1) {
                buf.writeInt(0); // throttle_time_ms
            }
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

        // v2+: throttle_time_ms first
        if (apiVersion >= 2) {
            buf.writeInt(0); // throttle_time_ms
        }

        // Topics array — empty (no partition results).
        // v0-v1 expect a topics array (int32 count + entries); returning 0 entries
        // is a valid empty response that satisfies the protocol frame.
        buf.writeInt(0); // topics array length = 0

        return frameResponse(buf);
    }

    // --- OffsetFetch (API key 9) ---

    private ByteBuf handleOffsetFetch(ByteBuf msg, short apiVersion, int correlationId) {
        // Read group_id (present in all versions)
        readNullableString(msg); // group_id — consumed but not used

        // Read request topics (nullable array: -1 = null, >=0 = array length)
        List<String> topics = new ArrayList<String>();
        int topicCount = msg.readInt();
        if (topicCount >= 0) {
            for (int t = 0; t < topicCount; t++) {
                String topic = readNullableString(msg);
                topics.add(topic);
                int partitionCount = msg.readInt();
                for (int p = 0; p < partitionCount; p++) {
                    msg.readInt(); // partition
                }
            }
        }
        if (apiVersion >= 2 && msg.readableBytes() > 0) {
            msg.readByte(); // require_stable
        }

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v3+)
        if (apiVersion >= 3) {
            buf.writeInt(0);
        }

        // Topics array — return offset 0 for each requested topic
        buf.writeInt(topics.size());
        for (String topic : topics) {
            writeNullableString(buf, topic);
            // 1 partition per topic
            buf.writeInt(1); // partition count
            buf.writeInt(0); // partition_index
            buf.writeLong(-1); // committed_offset: -1 = no committed offset (consumer will use auto.offset.reset)
            // committed_leader_epoch (v5+)
            if (apiVersion >= 5) {
                buf.writeInt(-1); // -1 = unknown
            }
            writeNullableString(buf, null); // metadata
            buf.writeShort(NONE); // error_code
        }

        // top-level error_code (v2+)
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

        // FindCoordinator v0-v2: single coordinator fields.
        // Note: cap is v2 in SUPPORTED_APIS; v3+ is flexible and not advertised.
        buf.writeShort(NONE); // error_code
        if (apiVersion >= 1) {
            writeNullableString(buf, null); // error_message (v1+)
        }
        buf.writeInt(0); // node_id
        writeNullableString(buf, resolveBrokerHost(ctx)); // host
        buf.writeInt(brokerPort); // port

        return frameResponse(buf);
    }

    // --- JoinGroup (API key 11) ---

    private ByteBuf handleJoinGroup(ByteBuf msg, short apiVersion, int correlationId) {
        // Parse request body to extract subscribed topics for SyncGroup assignment.
        // JoinGroup request: group_id | session_timeout_ms | rebalance_timeout_ms(v1+) |
        //   member_id | group_instance_id(v3+) | protocol_type | protocols[] { name, metadata }
        // metadata format: version(int16) | topics[] (string) | user_data(nullable_bytes)
        try {
            int savedReaderIndex = msg.readerIndex();
            readNullableString(msg); // group_id
            msg.readInt(); // session_timeout_ms
            if (apiVersion >= 1) { msg.readInt(); } // rebalance_timeout_ms
            readNullableString(msg); // member_id
            if (apiVersion >= 3) { readNullableString(msg); } // group_instance_id
            readNullableString(msg); // protocol_type
            int protocolCount = msg.readInt();
            if (protocolCount > 0) {
                readNullableString(msg); // protocol_name
                // metadata: version(int16) + topics array + user_data
                int metaLen = msg.readInt(); // metadata bytes length
                if (metaLen > 0) {
                    int metaStart = msg.readerIndex();
                    short metaVersion = msg.readShort();
                    int topicCount = msg.readInt();
                    if (topicCount > 0 && topicCount < 1000) {
                        String topic = readNullableString(msg);
                        if (topic != null && !topic.isEmpty()) {
                            lastSubscribedTopic = topic;
                            log.info("Kafka JoinGroup: tracked subscribed topic={}", topic);
                        }
                    }
                    // skip remaining metadata bytes
                    msg.readerIndex(metaStart + metaLen);
                }
            }
            // restore reader index (the remaining bytes will be discarded anyway)
            msg.readerIndex(savedReaderIndex);
        } catch (Exception e) {
            log.warn("Kafka JoinGroup: failed to parse request body for topic tracking: {}", e.getMessage());
        }

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(correlationId);

        // throttle_time_ms (v2+)
        if (apiVersion >= 2) {
            buf.writeInt(0);
        }

        buf.writeShort(NONE); // error_code
        buf.writeInt(1); // generation_id
        // protocol_type (v7+, nullable at v7+) — not present in v0-v6
        if (apiVersion >= 7) {
            writeNullableString(buf, "consumer");
        }
        writeNullableString(buf, "range"); // protocol_name
        writeNullableString(buf, "baafoo-mock-leader"); // leader
        // skip_assignment (v9+) — not present in v0-v8
        if (apiVersion >= 9) {
            buf.writeBoolean(false);
        }
        writeNullableString(buf, "baafoo-mock-member"); // member_id

        // members array — 1 member (ourselves)
        buf.writeInt(1);
        writeNullableString(buf, "baafoo-mock-member"); // member_id
        // group_instance_id (v5+, nullable)
        if (apiVersion >= 5) {
            writeNullableString(buf, null);
        }
        writeNullableBytes(buf, new byte[0]); // metadata (bytes type, not string)

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

        // Assignment: ConsumerPartitionAssignor format — assign all partitions to our member.
        // Format: {version(int16), assigned_partitions: {topic: string, partitions: [int32]},
        //          user_data: nullable_bytes, owned_partitions: {topic: string, partitions: [int32]} (v3+)}
        String assignedTopic = lastSubscribedTopic != null ? lastSubscribedTopic : "baafoo-test-topic";
        ByteBuf assignment = Unpooled.buffer();
        assignment.writeShort(0); // assignment version
        assignment.writeInt(1); // 1 topic
        writeNullableString(assignment, assignedTopic); // topic name
        assignment.writeInt(1); // 1 partition
        assignment.writeInt(0); // partition 0
        writeNullableBytes(assignment, null); // user_data (nullable)
        // owned_partitions (v3+) — not present since we cap at v3
        byte[] assignmentBytes = new byte[assignment.readableBytes()];
        assignment.readBytes(assignmentBytes);
        assignment.release();

        writeNullableBytes(buf, assignmentBytes); // assignment (bytes)

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
        return KafkaCodecUtils.frameResponse(payload);
    }

    private String readNullableString(ByteBuf buf) {
        return KafkaCodecUtils.readNullableString(buf);
    }

    /**
     * Read a Kafka "compact string" (Request/Response Header v2+, flexible versions).
     * Compact nullable string: uvarint length where 0 = null, N = N-1 bytes follow.
     * Used as a fallback in {@link #channelRead0} when a client sends a v2 header.
     */
    private String readCompactString(ByteBuf buf) {
        return KafkaFlexibleCodec.readCompactString(buf);
    }

    /**
     * Read a Kafka uvarint (big-endian base-128 varint, NOT zig-zag encoded).
     */
    private int readUnsignedVarint(ByteBuf buf) {
        return KafkaFlexibleCodec.readUnsignedVarint(buf);
    }

    private void writeNullableString(ByteBuf buf, String value) {
        KafkaCodecUtils.writeNullableString(buf, value);
    }

    private void writeNullableBytes(ByteBuf buf, byte[] value) {
        KafkaCodecUtils.writeNullableBytes(buf, value);
    }

    private void writeVarint(ByteBuf buf, int value) {
        KafkaCodecUtils.writeVarint(buf, value);
    }

    /**
     * Read a zig-zag varint as used inside Kafka RecordBatch records
     * (timestampDelta, offsetDelta, key/value lengths). Mirrors {@link #writeVarint}.
     */
    private int readVarint(ByteBuf buf) {
        return KafkaCodecUtils.readVarint(buf);
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
     * Delegates to NetworkUtils (IP-reachability based; works for Docker,
     * bare-metal, and VM clients alike).
     */
    private String resolveBrokerHost(ChannelHandlerContext ctx) {
        java.net.InetSocketAddress remote = (java.net.InetSocketAddress) ctx.channel().remoteAddress();
        java.net.InetSocketAddress local = (java.net.InetSocketAddress) ctx.channel().localAddress();
        String defaultHost = cachedBrokerHost != null ? cachedBrokerHost : "127.0.0.1";
        String resolved = com.baafoo.core.util.NetworkUtils.resolveClientReachableHost(
                remote, local, defaultHost, advertisedHost);
        return resolved != null ? resolved : defaultHost;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("KafkaProtocolDecoder error: {}", cause.getMessage());
        ctx.close();
    }
}
