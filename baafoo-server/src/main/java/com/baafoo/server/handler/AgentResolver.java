package com.baafoo.server.handler;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves agent identity and environment information from storage.
 *
 * <p>Extracted from HttpStubHandler to separate agent resolution
 * concerns from request handling logic.</p>
 */
public class AgentResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentResolver.class);

    private final StorageService storage;

    public AgentResolver(StorageService storage) {
        this.storage = storage;
    }

    /**
     * Resolve all agent info in a single pass over the agent list.
     *
     * <p>Environment isolation rules:
     * <ul>
     *   <li>Match by source IP — the primary mechanism for determining environment</li>
     *   <li>If multiple agents share the same IP but have different environments,
     *       the match is ambiguous and environment is set to null (safe default)</li>
     *   <li>If no IP match is found, environment is null — only global rules
     *       (rules with no environment association) will match</li>
     *   <li>There is NO fallback to "first online agent" — this prevents
     *       environment A from accidentally getting environment B's rules</li>
     * </ul></p>
     */
    public AgentInfo resolveAll(ChannelHandlerContext ctx) {
        AgentInfo info = new AgentInfo();
        List<StorageService.AgentRegistration> agents = storage.listAgents();
        long onlineThreshold = System.currentTimeMillis() - 90000;

        // Extract channel IP for environment matching
        String channelIp = null;
        if (ctx != null) {
            channelIp = resolveAgentIpFromChannel(ctx);
        }

        // Collect all agents that match by IP
        StorageService.AgentRegistration ipMatched = null;
        boolean ipMatchAmbiguous = false;

        for (StorageService.AgentRegistration agent : agents) {
            if (agent.lastHeartbeat > onlineThreshold) {
                if (channelIp != null && channelIp.equals(agent.agentIp)) {
                    if (ipMatched == null) {
                        ipMatched = agent;
                    } else {
                        // Multiple agents with same IP — check if environments differ
                        if (!java.util.Objects.equals(ipMatched.environment, agent.environment)) {
                            ipMatchAmbiguous = true;
                        }
                    }
                }
            }
        }

        // Resolve environment: only use IP-matched agent, never fall back to "first online"
        StorageService.AgentRegistration resolved = null;
        if (ipMatched != null && !ipMatchAmbiguous) {
            resolved = ipMatched;
        } else if (ipMatchAmbiguous) {
            log.warn("Multiple online agents share IP {} with different environments — " +
                    "cannot determine environment, only global rules will match", channelIp);
        }

        // Environment from resolved agent
        if (resolved != null) {
            info.environment = resolved.environment;
        }

        // Agent ID
        if (resolved != null && resolved.agentId != null && !resolved.agentId.isEmpty()) {
            info.agentId = resolved.agentId;
        }

        // Agent IP
        if (resolved != null) {
            info.agentIp = resolved.agentIp;
        } else if (channelIp != null) {
            // Channel IP fallback for recording purposes only
            info.agentIp = channelIp;
        }

        return info;
    }

    /** Single-pass agent resolution (compatibility). */
    @Deprecated
    public String resolveAgentEnvironment(String host, int port) {
        AgentInfo info = resolveAll(null);
        return info.environment;
    }

    /** Single-pass agent resolution (compatibility). */
    @Deprecated
    public String resolveAgentId(String agentEnvironment) {
        AgentInfo info = resolveAll(null);
        return info.agentId;
    }

    /** Single-pass agent resolution (compatibility). */
    @Deprecated
    public String resolveAgentIp(String agentEnvironment) {
        AgentInfo info = resolveAll(null);
        return info.agentIp;
    }

    public String resolveAgentIpFromChannel(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() != null) {
            String addr = ctx.channel().remoteAddress().toString();
            if (addr.startsWith("/")) addr = addr.substring(1);
            int colonIdx = addr.indexOf(':');
            return colonIdx > 0 ? addr.substring(0, colonIdx) : addr;
        }
        return null;
    }

    /**
     * Filter rules by agent environment.
     *
     * <p>Environment isolation rules:
     * <ul>
     *   <li>If agentEnvironment is null (cannot determine environment), NO rules match</li>
     *   <li>Rules with environments are only included if the agent's environment is in the list</li>
     *   <li>Rules with no environment association are treated as global rules (match all environments),
     *       but still require a non-null agentEnvironment to match</li>
     * </ul></p>
     */
    public List<Rule> filterRulesByEnvironment(List<Rule> rules, String agentEnvironment) {
        List<Rule> filtered = new ArrayList<Rule>();
        // Cannot determine environment — match nothing to prevent cross-environment leakage
        if (agentEnvironment == null) {
            return filtered;
        }
        for (Rule rule : rules) {
            if (!rule.isEnabled()) continue;
            List<String> envs = rule.getEnvironments();
            if (envs == null || envs.isEmpty()) {
                // Global rule — applies to all environments
                filtered.add(rule);
                continue;
            }
            if (agentEnvironment != null && envs.contains(agentEnvironment)) {
                filtered.add(rule);
            }
        }
        return filtered;
    }

    public EnvironmentMode resolveEnvironmentMode(String agentEnvironment) {
        if (agentEnvironment != null) {
            for (Environment env : storage.listEnvironments()) {
                if (agentEnvironment.equals(env.getName())) {
                    return env.getMode();
                }
            }
        }
        // When environment is null or not found, default to PASSTHROUGH
        // (safe default — don't stub if we can't determine the environment)
        return EnvironmentMode.PASSTHROUGH;
    }

    /**
     * All resolved agent info from a single agent list traversal.
     */
    public static class AgentInfo {
        public String environment;
        public String agentId;
        public String agentIp;
    }
}
