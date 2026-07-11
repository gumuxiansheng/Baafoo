package com.baafoo.server.handler;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import com.baafoo.core.util.HexUtils;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.core.util.TemplateEngine;
import com.baafoo.plugin.PluginEvent;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Netty handler for TCP stub server (port 9001+).
 *
 * <p>Handles raw TCP connections relayed from the agent.
 * Supports:</p>
 * <ul>
 *   <li>Prefix hex matching (existing)</li>
 *   <li>Regex pattern matching on hex string of request bytes (R-S3 AC-02)</li>
 *   <li>Offset-based byte matching (R-S3 AC-05)</li>
 *   <li>Multi-round TCP interaction with state machine (R-S3 AC-03)</li>
 * </ul>
 */
public class TcpStubHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(TcpStubHandler.class);

    /** Attribute key for tracking which rule a connection is bound to (multi-round) */
    private static final AttributeKey<String> ATTR_RULE_ID = AttributeKey.valueOf("tcpRuleId");

    /** Attribute key for caching the bound Rule object (avoids N+1 lookups in multi-round) */
    private static final AttributeKey<Rule> ATTR_RULE = AttributeKey.valueOf("tcpRule");

    /** Attribute key for tracking current round index in multi-round interaction */
    private static final AttributeKey<Integer> ATTR_ROUND_INDEX = AttributeKey.valueOf("tcpRoundIndex");

    private final StorageService storage;
    private final MatchEngine matchEngine;
    private final AgentResolver agentResolver;
    /** P2: Event bus for plugin event firing */
    private final com.baafoo.core.event.EventBus eventBus;

    /**
     * Pre-compiled regex patterns (cached by pattern string).
     *
     * <p>Bounded LRU cache (Medium 30) — replaces the previous
     * {@link ConcurrentHashMap} with a soft 512 cap that stopped caching
     * new patterns after the cap, causing recompilation on every request.
     * Now uses {@link java.util.LinkedHashMap} with access-order + LRU
     * eviction, keeping the hottest patterns resident.</p>
     */
    private static final int PATTERN_CACHE_MAX = 512;
    private final Map<String, Pattern> patternCache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, Pattern>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                            return size() > PATTERN_CACHE_MAX;
                        }
                    });

    public TcpStubHandler(StorageService storage, ServerConfig config) {
        this(storage, config, null);
    }

    /**
     * P2: Constructor with EventBus for plugin event firing.
     */
    public TcpStubHandler(StorageService storage, ServerConfig config,
                          com.baafoo.core.event.EventBus eventBus) {
        this.storage = storage;
        this.matchEngine = new MatchEngine();
        this.agentResolver = new AgentResolver(storage, config);
        this.eventBus = eventBus;
    }

    /**
     * P2: Fire a plugin event if event bus is available.
     */
    private void fireEvent(PluginEvent event) {
        if (eventBus != null) {
            eventBus.fire(event);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("TCP stub connection: {}", ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        byte[] data = new byte[msg.readableBytes()];
        msg.readBytes(data);
        String payload = new String(data, StandardCharsets.UTF_8);
        String hexPayload = bytesToHex(data);

        // P2: Fire REQUEST_RECEIVED event
        fireEvent(PluginEvent.requestReceived("tcp", null, null));

        // Resolve agent info (single pass over agent list)
        AgentResolver.AgentInfo agentInfo = agentResolver.resolveAll(ctx);
        String agentEnvironment = agentInfo.environment;
        String agentId = agentInfo.agentId;
        String agentIp = agentInfo.agentIp;

        // Check if this connection is already in a multi-round interaction
        String boundRuleId = ctx.channel().attr(ATTR_RULE_ID).get();
        if (boundRuleId != null) {
            handleMultiRoundRequest(ctx, boundRuleId, data, hexPayload, payload,
                    agentEnvironment, agentId, agentIp);
            return;
        }

        // Match TCP rules
        List<Rule> rules = storage.listRules();
        List<Rule> filteredRules = agentResolver.filterRulesByEnvironment(rules, agentEnvironment);

        // First try TCP-specific matching (pattern/prefixHex/offset/rounds)
        for (Rule rule : filteredRules) {
            if (!"tcp".equalsIgnoreCase(rule.getProtocol()) && rule.getProtocol() != null && !rule.getProtocol().isEmpty()) {
                continue;
            }

            // Multi-round rules: match first round
            if (rule.getTcpRounds() != null && !rule.getTcpRounds().isEmpty()) {
                TcpRound firstRound = rule.getTcpRounds().get(0);
                if (matchTcpRound(firstRound, data, hexPayload, payload)) {
                    // Bind this connection to the rule and set round index
                    ctx.channel().attr(ATTR_RULE_ID).set(rule.getId());
                    ctx.channel().attr(ATTR_RULE).set(rule);
                    ctx.channel().attr(ATTR_ROUND_INDEX).set(0);

                    ResponseEntry response = firstRound.getResponse() != null
                            ? firstRound.getResponse() : getFirstResponse(rule);

                    String decodedPayload = decodeWithRuleCharset(data, rule, payload);
                    recordIfNeeded(rule, response, agentEnvironment, agentId, agentIp, decodedPayload);

                    // Close after last round if no loop
                    boolean isLastRound = (rule.getTcpRounds().size() == 1) && !rule.isTcpLoop();
                    sendTcpResponse(ctx, response, decodedPayload, isLastRound, agentEnvironment);
                    return;
                }
                continue;
            }

            // Single-round TCP-specific matching (pattern/prefixHex/offset)
            if (matchTcpRule(rule, data, hexPayload, payload)) {
                ResponseEntry response = getFirstResponse(rule);
                String decodedPayload = decodeWithRuleCharset(data, rule, payload);
                recordIfNeeded(rule, response, agentEnvironment, agentId, agentIp, decodedPayload);
                sendTcpResponse(ctx, response, decodedPayload, true, agentEnvironment);
                return;
            }
        }

        // Fall back to generic MatchEngine matching
        MatchEngine.MatchResult result = matchEngine.match(
                filteredRules, "tcp", "127.0.0.1", 0, null,
                null, null,
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                payload);

        if (result.isMatched()) {
            // P2: Fire RULE_MATCHED event
            fireEvent(PluginEvent.ruleMatched(
                    result.getRule().getId(), result.getRule().getName(), "tcp"));
            ResponseEntry entry = result.getResponse();
            EnvironmentMode currentMode = agentResolver.resolveEnvironmentMode(agentEnvironment);

            // Re-decode the request bytes with the matched rule's charset for template/recording.
            String decodedPayload = decodeWithRuleCharset(data, result.getRule(), payload);

            if (currentMode == EnvironmentMode.RECORD || currentMode == EnvironmentMode.RECORD_AND_STUB || currentMode == EnvironmentMode.RECORD_ALL) {
                RecordingEntry rec = RecordingHelper.buildFromStub(
                        result, "tcp", "127.0.0.1", 0, null, null,
                        Collections.<String, String>emptyMap(), decodedPayload);
                rec.setAgentId(agentId);
                rec.setAgentIp(agentIp);
                rec.setEnvironmentId(agentEnvironment);
                storage.addRecording(rec);
                // P2: Fire RECORDING_SAVED event
                fireEvent(PluginEvent.recordingSaved(rec.getId(), "tcp", agentEnvironment));
            }

            sendTcpResponse(ctx, entry, decodedPayload, true, agentEnvironment);
            // P2: Fire RESPONSE_SENT event
            fireEvent(PluginEvent.responseSent("tcp", 200, 0));
        } else {
            // P2: Fire RULE_NOT_MATCHED event
            fireEvent(PluginEvent.ruleNotMatched("tcp", "127.0.0.1", 0));
            // RECORD_ALL: record the unmatched TCP payload as raw hex data.
            //
            // NOTE: In the current design, RECORD_ALL TCP passthrough is handled at the
            // Agent side — the Agent does NOT redirect unmatched generic TCP connections
            // to the stub port. Instead it connects directly to the real target and records
            // at the stream level (RecordingInputStream/OutputStream + SocketChannelRead/Write).
            //
            // This Server-side branch is a fallback for cases where traffic still arrives
            // (e.g., matched route in RECORD_ALL mode, or protocol-specific stub ports).
            // The connection is recorded and closed.
            EnvironmentMode currentMode = agentResolver.resolveEnvironmentMode(agentEnvironment);
            if (currentMode == EnvironmentMode.RECORD_ALL) {
                RecordingEntry rec = new RecordingEntry();
                rec.setProtocol("tcp");
                rec.setHost("127.0.0.1");
                rec.setPort(0);
                rec.setDirection("request");
                rec.setDataHex(hexPayload);
                rec.setRequestBody(payload);
                rec.setAgentId(agentId);
                rec.setAgentIp(agentIp);
                rec.setEnvironmentId(agentEnvironment);
                rec.setUnmatched(true);
                rec.setRecordedAt(System.currentTimeMillis());
                storage.addRecording(rec);
                log.info("RECORD_ALL — unmatched TCP recorded: {} bytes", data.length);
            }
            log.debug("No TCP rule matched, closing connection");
            ctx.close();
        }
    }

    /**
     * Handle a request on a connection that is already in a multi-round interaction.
     */
    private void handleMultiRoundRequest(ChannelHandlerContext ctx, String ruleId,
                                          byte[] data, String hexPayload, String payload,
                                          String agentEnvironment, String agentId, String agentIp) {
        // Use the cached Rule object from the channel attribute to avoid an N+1
        // storage lookup on every round of a multi-round TCP interaction.
        Rule rule = ctx.channel().attr(ATTR_RULE).get();
        if (rule == null) {
            rule = findRuleById(ruleId);
        }
        if (rule == null) {
            log.warn("Bound rule {} not found, closing connection", ruleId);
            ctx.close();
            return;
        }

        List<TcpRound> rounds = rule.getTcpRounds();
        if (rounds == null || rounds.isEmpty()) {
            log.warn("Bound rule {} has no rounds, closing connection", ruleId);
            ctx.close();
            return;
        }

        int currentRoundIdx = ctx.channel().attr(ATTR_ROUND_INDEX).get();
        int nextRoundIdx = currentRoundIdx + 1;

        // Check if all rounds are exhausted
        if (nextRoundIdx >= rounds.size()) {
            if (rule.isTcpLoop()) {
                // Loop back to round 0
                nextRoundIdx = 0;
                log.debug("TCP multi-round loop: rule={}, resetting to round 0", ruleId);
            } else {
                // All rounds exhausted, close connection
                log.debug("TCP multi-round complete: rule={}, all {} rounds done, closing",
                        ruleId, rounds.size());
                ctx.close();
                return;
            }
        }

        TcpRound nextRound = rounds.get(nextRoundIdx);
        if (matchTcpRound(nextRound, data, hexPayload, payload)) {
            // Advance to next round
            ctx.channel().attr(ATTR_ROUND_INDEX).set(nextRoundIdx);

            ResponseEntry response = nextRound.getResponse() != null
                    ? nextRound.getResponse() : getFirstResponse(rule);

            String decodedPayload = decodeWithRuleCharset(data, rule, payload);
            recordIfNeeded(rule, response, agentEnvironment, agentId, agentIp, decodedPayload);

            // Don't close after response in multi-round (unless it's the last round and no loop)
            boolean isLastRound = (nextRoundIdx == rounds.size() - 1) && !rule.isTcpLoop();
            sendTcpResponse(ctx, response, decodedPayload, isLastRound, agentEnvironment);
        } else {
            log.debug("TCP multi-round: round {} of rule {} did not match, closing connection",
                    nextRoundIdx, ruleId);
            ctx.close();
        }
    }

    /**
     * Match a single-round TCP rule using tcpPattern, tcpPrefixHex, and tcpOffset fields.
     * All specified matchers must pass (AND logic).
     */
    private boolean matchTcpRule(Rule rule, byte[] data, String hexPayload, String payload) {
        boolean hasTcpMatcher = false;
        boolean allMatch = true;

        // Pattern matching (R-S3 AC-02): regex on hex string
        if (rule.getTcpPattern() != null && !rule.getTcpPattern().isEmpty()) {
            hasTcpMatcher = true;
            if (!matchRegex(hexPayload, rule.getTcpPattern())) {
                allMatch = false;
            }
        }

        // Prefix hex matching
        if (rule.getTcpPrefixHex() != null && !rule.getTcpPrefixHex().isEmpty()) {
            hasTcpMatcher = true;
            if (!hexPayload.startsWith(rule.getTcpPrefixHex().toLowerCase())) {
                allMatch = false;
            }
        }

        // Offset matching (R-S3 AC-05)
        if (rule.getTcpOffsetStart() >= 0 && rule.getTcpOffsetEnd() > rule.getTcpOffsetStart()
                && rule.getTcpOffsetHex() != null && !rule.getTcpOffsetHex().isEmpty()) {
            hasTcpMatcher = true;
            if (!matchOffset(data, rule.getTcpOffsetStart(), rule.getTcpOffsetEnd(), rule.getTcpOffsetHex())) {
                allMatch = false;
            }
        }

        // If no TCP-specific matchers were defined, this rule doesn't use TCP matching
        if (!hasTcpMatcher) {
            return false;
        }

        return allMatch;
    }

    /**
     * Match a TcpRound using its pattern, prefixHex, offset, and conditions.
     * All specified matchers must pass (AND logic).
     */
    private boolean matchTcpRound(TcpRound round, byte[] data, String hexPayload, String payload) {
        boolean hasMatcher = false;
        boolean allMatch = true;

        // Pattern matching (R-S3 AC-02): regex on hex string
        if (round.getPattern() != null && !round.getPattern().isEmpty()) {
            hasMatcher = true;
            if (!matchRegex(hexPayload, round.getPattern())) {
                allMatch = false;
            }
        }

        // Prefix hex matching
        if (round.getPrefixHex() != null && !round.getPrefixHex().isEmpty()) {
            hasMatcher = true;
            if (!hexPayload.startsWith(round.getPrefixHex().toLowerCase())) {
                allMatch = false;
            }
        }

        // Offset matching (R-S3 AC-05)
        if (round.getOffsetStart() >= 0 && round.getOffsetEnd() > round.getOffsetStart()
                && round.getOffsetHex() != null && !round.getOffsetHex().isEmpty()) {
            hasMatcher = true;
            if (!matchOffset(data, round.getOffsetStart(), round.getOffsetEnd(), round.getOffsetHex())) {
                allMatch = false;
            }
        }

        // If no TCP-specific matchers, match anything (wildcard round)
        if (!hasMatcher) {
            return true;
        }

        return allMatch;
    }

    /**
     * Match bytes at a specific offset range against an expected hex value.
     */
    private boolean matchOffset(byte[] data, int offsetStart, int offsetEnd, String expectedHex) {
        int byteCount = offsetEnd - offsetStart;
        if (data.length < offsetEnd) {
            return false;
        }
        StringBuilder actualHex = new StringBuilder(byteCount * 2);
        for (int i = offsetStart; i < offsetEnd; i++) {
            // Lookup-table conversion — avoids String.format on hot path (Critical 4).
            HexUtils.appendByte(actualHex, data[i] & 0xFF);
        }
        return actualHex.toString().equalsIgnoreCase(expectedHex);
    }

    /**
     * Match input against a regex pattern (cached, LRU).
     */
    private boolean matchRegex(String input, String regex) {
        try {
            Pattern pattern;
            synchronized (patternCache) {
                pattern = patternCache.get(regex);
                if (pattern == null) {
                    pattern = Pattern.compile(regex);
                    patternCache.put(regex, pattern);
                }
            }
            return pattern.matcher(input).find();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid TCP regex pattern: {}", regex);
            return false;
        }
    }

    /**
     * Convert byte array to lowercase hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        // Delegates to HexUtils — lookup table instead of String.format (Critical 4).
        return HexUtils.bytesToHex(bytes);
    }

    /**
     * Re-decode the raw request bytes using the matched rule's {@code requestCharset}
     * so that {@code {{request.body}}} template variables and recording capture the
     * correct text when the client sends GBK/GB2312/Big5 payloads.
     *
     * <p>Matching is always performed with the UTF-8 decoded payload first (preserving
     * existing behaviour for hex/path matchers). Only after a rule matches do we
     * re-decode for template rendering and recording. Returns the original
     * {@code defaultPayload} when the rule does not declare a non-UTF-8 charset.</p>
     */
    private String decodeWithRuleCharset(byte[] data, Rule rule, String defaultPayload) {
        if (rule == null) return defaultPayload;
        String cs = rule.getRequestCharset();
        if (cs == null || cs.isEmpty() || "UTF-8".equalsIgnoreCase(cs)) return defaultPayload;
        try {
            return new String(data, Charset.forName(cs));
        } catch (Exception e) {
            log.warn("Failed to decode TCP payload with charset {}, falling back to UTF-8", cs);
            return defaultPayload;
        }
    }

    private Rule findRuleById(String ruleId) {
        for (Rule rule : storage.listRules()) {
            if (ruleId.equals(rule.getId())) {
                return rule;
            }
        }
        return null;
    }

    private ResponseEntry getFirstResponse(Rule rule) {
        if (rule.getResponses() != null && !rule.getResponses().isEmpty()) {
            return rule.getResponses().get(0);
        }
        ResponseEntry fallback = new ResponseEntry();
        fallback.setBody("");
        return fallback;
    }

    private void recordIfNeeded(Rule rule, ResponseEntry entry,
                                String agentEnvironment, String agentId, String agentIp,
                                String payload) {
        EnvironmentMode currentMode = agentResolver.resolveEnvironmentMode(agentEnvironment);
        if (currentMode == EnvironmentMode.RECORD || currentMode == EnvironmentMode.RECORD_AND_STUB || currentMode == EnvironmentMode.RECORD_ALL) {
            RecordingEntry rec = RecordingHelper.buildFromStub(
                    rule, entry, "tcp", "127.0.0.1", 0, null, null,
                    Collections.<String, String>emptyMap(), payload);
            rec.setAgentId(agentId);
            rec.setAgentIp(agentIp);
            rec.setEnvironmentId(agentEnvironment);
            storage.addRecording(rec);
        }
    }

    /**
     * Send a TCP response.
     *
     * @param closeAfterSend whether to close the connection after sending
     */
    private void sendTcpResponse(ChannelHandlerContext ctx, ResponseEntry entry, String payload,
                                  boolean closeAfterSend, String environment) {
        try {
            String rawBody = entry.getBody() != null ? entry.getBody() : "";
            String body = rawBody;
            if (rawBody.contains("{{")) {
                TemplateEngine.RequestContext templateCtx = new TemplateEngine.RequestContext(
                        null, null, null,
                        Collections.<String, String>emptyMap(),
                        Collections.<String, String>emptyMap(),
                        payload, environment);
                body = TemplateEngine.render(rawBody, templateCtx);
            }

            String charsetName = entry.getCharset() != null && !entry.getCharset().isEmpty() ? entry.getCharset() : "UTF-8";
            java.nio.charset.Charset charset = java.nio.charset.Charset.forName(charsetName);
            ByteBuf response = Unpooled.copiedBuffer(body, charset);

            if (entry.getDelayMs() > 0) {
                ctx.executor().schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (closeAfterSend) {
                            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                        } else {
                            ctx.writeAndFlush(response);
                        }
                    }
                }, entry.getDelayMs(), TimeUnit.MILLISECONDS);
            } else {
                if (closeAfterSend) {
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                } else {
                    ctx.writeAndFlush(response);
                }
            }
            log.debug("TCP stub response: {} bytes, closeAfterSend={}", body.length(), closeAfterSend);
        } catch (Exception e) {
            log.error("Error sending TCP response: {}", e.getMessage());
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Clean up multi-round state
        ctx.channel().attr(ATTR_RULE_ID).set(null);
        ctx.channel().attr(ATTR_ROUND_INDEX).set(null);
        log.debug("TCP stub connection closed: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("TcpStubHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
