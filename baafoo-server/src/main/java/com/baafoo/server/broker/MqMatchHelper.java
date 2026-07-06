package com.baafoo.server.broker;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.handler.AgentResolver;
import com.baafoo.server.storage.StorageService;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
     *
     * <p>MQ fallback: when no agent could be resolved by IP (common for
     * direct-to-broker connections without a registered agent, or in unit
     * tests), fall back to the single configured environment if there is
     * exactly one. This lets MQ rules match even without agent registration.</p>
     */
    AgentResolver.AgentInfo resolveAgent(ChannelHandlerContext ctx) {
        AgentResolver.AgentInfo info = agentResolver.resolveAll(ctx);
        if (info.environment == null) {
            List<Environment> envs = storage.listEnvironments();
            if (envs != null && envs.size() == 1) {
                info.environment = envs.get(0).getName();
            }
        }
        return info;
    }

    /**
     * Filter rules to the agent's environment.
     *
     * <p>MQ fallback: when no environment could be determined (no registered
     * agents and no single environment to fall back to), still allow global
     * rules (rules with no environment association) to match. This is safe
     * because MQ brokers have no real downstream — global rules are the only
     * way to stub MQ traffic without agent registration.</p>
     */
    List<Rule> filterRulesByEnvironment(List<Rule> rules, String environment) {
        if (environment == null) {
            List<Rule> filtered = new ArrayList<Rule>();
            if (rules == null) return filtered;
            for (Rule rule : rules) {
                if (!rule.isEnabled()) continue;
                List<String> envs = rule.getEnvironments();
                if (envs == null || envs.isEmpty()) {
                    filtered.add(rule);
                }
            }
            return filtered;
        }
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
        // Pass topic through the explicit topic slot AND the serviceName slot:
        //  - serviceName slot lets a rule that stored topic in rule.serviceName match
        //  - topic slot lets a rule with a "topic" condition match (explicit, no path aliasing)
        // host/port/path are null/0 — MQ brokers have no concept of a downstream host:port/path.
        return matchEngine.match(
                rules, protocol, null, 0, topic, null, null, topic,
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
     * @param direction    "produce" for producer sends, "consume" for consumer fetches/stubs
     */
    void record(String ruleId, String protocol, String topic, String requestBody, String responseBody, AgentResolver.AgentInfo info, String direction) {
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
            rec.setDirection(direction);
            rec.setResponseSource("STUB");
            if (info != null) {
                rec.setEnvironmentId(info.environment);
                rec.setAgentId(info.agentId);
                rec.setAgentIp(info.agentIp);
            }
            storage.addRecording(rec);
        } catch (Exception e) {
            log.warn("Failed to record MQ message ({}:{}, direction={}): {}", protocol, topic, direction, e.getMessage());
        }
    }

    /**
     * Resolve the environment mode for the agent on the given channel.
     *
     * <p>MQ brokers have no real downstream to passthrough to — the broker
     * itself IS the downstream. When no environment can be determined
     * (no agents registered, no single environment to fall back to),
     * default to STUB so matched rules still apply their stub responses
     * instead of silently passing through the original payload.</p>
     */
    EnvironmentMode resolveMode(ChannelHandlerContext ctx) {
        AgentResolver.AgentInfo info = resolveAgent(ctx);
        EnvironmentMode mode = agentResolver.resolveEnvironmentMode(info.environment);
        if (mode == EnvironmentMode.PASSTHROUGH && info.environment == null) {
            return EnvironmentMode.STUB;
        }
        return mode;
    }
}
