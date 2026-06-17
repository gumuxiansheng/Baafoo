package com.baafoo.server.broker;

import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.handler.AgentResolver;
import com.baafoo.server.storage.StorageService;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Rule matching + recording helper shared by the Kafka/Pulsar mock brokers.
 *
 * <p>Until this class existed, the MQ brokers ({@link KafkaProtocolDecoder},
 * {@link PulsarMockBrokerHandler}) never invoked {@link MatchEngine} and never
 * recorded traffic — so MQ messages could not match a rule id and were never
 * recorded. This helper mirrors the flow in {@code HttpStubHandler.channelRead0}
 * but is shaped for MQ messages (protocol + topic + body, no HTTP method/path).</p>
 *
 * <p><b>Topic matching convention</b>: a Kafka/Pulsar rule expresses its topic
 * via a {@code MatchCondition} of type {@code "topic"} or {@code "path"} (the two
 * are equivalent aliases in {@link MatchEngine}). The broker passes the topic
 * name through the {@code path} parameter slot of {@code MatchEngine.match}, and
 * also through the {@code serviceName} slot so rules that stored the topic in
 * {@code rule.serviceName} still match.</p>
 *
 * <p>This class is NOT thread-safe per-match call, but each call is stateless;
 * instances are safe to share across channels because {@link MatchEngine} and
 * {@link AgentResolver} are internally thread-safe.</p>
 */
class MqMatchHelper {

    private static final Logger log = LoggerFactory.getLogger(MqMatchHelper.class);

    private final StorageService storage;
    private final MatchEngine matchEngine = new MatchEngine();
    private final AgentResolver agentResolver;

    MqMatchHelper(StorageService storage) {
        this.storage = storage;
        this.agentResolver = new AgentResolver(storage);
    }

    /**
     * Resolve agent/environment info for the given channel.
     */
    AgentResolver.AgentInfo resolveAgent(ChannelHandlerContext ctx) {
        return agentResolver.resolveAll(ctx);
    }

    /**
     * Filter rules to the agent's environment. Delegates to {@link AgentResolver}
     * so brokers don't need their own AgentResolver instance.
     */
    List<Rule> filterRulesByEnvironment(List<Rule> rules, String environment) {
        return agentResolver.filterRulesByEnvironment(rules, environment);
    }

    /**
     * Match a single MQ message against enabled rules for the agent's environment.
     *
     * @param rules   rules already filtered for the agent's environment (pass
     *                {@code null} to let this helper filter from storage)
     * @param protocol  "kafka" or "pulsar"
     * @param topic     the topic/destination the message was sent to
     * @param body      the decoded message payload as a string (may be null)
     * @return match result; {@link MatchEngine.MatchResult#NO_MATCH} if no rule matches
     */
    MatchEngine.MatchResult match(List<Rule> rules, String protocol, String topic, String body) {
        if (rules == null) {
            return MatchEngine.MatchResult.NO_MATCH;
        }
        // Pass topic through BOTH the serviceName and path slots:
        //  - serviceName slot lets a rule that stored topic in rule.serviceName match
        //  - path slot lets a rule with a topic/path condition match
        // host/port are null/0 — MQ brokers have no concept of a downstream host:port.
        return matchEngine.match(
                rules, protocol, null, 0, topic, null, topic,
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(),
                body);
    }

    /**
     * Convenience: resolve agent environment, filter rules, then match.
     * Returns the matched rule (or null) without recording.
     */
    MatchEngine.MatchResult matchAndResolve(ChannelHandlerContext ctx, String protocol, String topic, String body) {
        AgentResolver.AgentInfo info = resolveAgent(ctx);
        List<Rule> rules = agentResolver.filterRulesByEnvironment(storage.listRules(), info.environment);
        return match(rules, protocol, topic, body);
    }

    /**
     * Record an MQ message. Safe to call in RECORD and RECORD_AND_STUB modes.
     * No-op otherwise.
     *
     * @param ruleId       matched rule id (null if unmatched)
     * @param protocol     "kafka" or "pulsar"
     * @param topic        the topic/destination
     * @param requestBody  the decoded request payload (e.g. producer's message body)
     * @param responseBody the decoded response payload (e.g. stub response body), may be null
     * @param info         pre-resolved agent info (avoid re-resolving per message)
     */
    void record(String ruleId, String protocol, String topic, String requestBody, String responseBody, AgentResolver.AgentInfo info) {
        try {
            RecordingEntry rec = new RecordingEntry();
            rec.setRuleId(ruleId);
            rec.setProtocol(protocol);
            rec.setPath(topic);
            rec.setRequestBody(requestBody);
            rec.setResponseBody(responseBody);
            rec.setResponseStatusCode(0);
            rec.setRequestHeaders(Collections.<String, String>emptyMap());
            rec.setResponseHeaders(Collections.<String, String>emptyMap());
            if (info != null) {
                rec.setEnvironmentId(info.environment);
                rec.setAgentId(info.agentId);
                rec.setAgentIp(info.agentIp);
            }
            storage.addRecording(rec);
        } catch (Exception e) {
            log.warn("Failed to record MQ message ({}:{}): {}", protocol, topic, e.getMessage());
        }
    }

    /**
     * Resolve the environment mode for the agent on the given channel.
     */
    EnvironmentMode resolveMode(ChannelHandlerContext ctx) {
        AgentResolver.AgentInfo info = resolveAgent(ctx);
        return agentResolver.resolveEnvironmentMode(info.environment);
    }
}
