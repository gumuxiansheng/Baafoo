package com.baafoo.server.handler;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import io.netty.channel.ChannelHandlerContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves agent identity and environment information from storage.
 *
 * <p>Extracted from HttpStubHandler to separate agent resolution
 * concerns from request handling logic.</p>
 */
public class AgentResolver {

    private final StorageService storage;

    public AgentResolver(StorageService storage) {
        this.storage = storage;
    }

    /**
     * Resolve all agent info in a single pass over the agent list.
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

        StorageService.AgentRegistration firstOnline = null;
        StorageService.AgentRegistration matchedByIp = null;

        for (StorageService.AgentRegistration agent : agents) {
            if (agent.lastHeartbeat > onlineThreshold) {
                if (firstOnline == null) {
                    firstOnline = agent;
                }
                // Match agent by source IP — this allows the shared stub port
                // to correctly identify which environment a request comes from
                if (channelIp != null && channelIp.equals(agent.agentIp)) {
                    matchedByIp = agent;
                }
            }
        }

        // Prefer IP-matched agent for environment resolution
        StorageService.AgentRegistration resolved = matchedByIp != null ? matchedByIp : firstOnline;

        // Environment from resolved agent
        if (resolved != null) {
            info.environment = resolved.environment;
        }

        // Agent ID
        if (resolved != null && resolved.agentId != null && !resolved.agentId.isEmpty()) {
            info.agentId = resolved.agentId;
        }

        // Agent IP — prefer IP-matched, then environment-matched
        if (matchedByIp != null) {
            info.agentIp = matchedByIp.agentIp;
        } else if (info.environment != null) {
            for (StorageService.AgentRegistration agent : agents) {
                if (agent.lastHeartbeat > onlineThreshold
                        && info.environment.equals(agent.environment)
                        && agent.agentIp != null && !agent.agentIp.isEmpty()
                        && !"127.0.0.1".equals(agent.agentIp)) {
                    info.agentIp = agent.agentIp;
                    break;
                }
            }
        }

        // Channel IP fallback
        if (info.agentIp == null && channelIp != null) {
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
     * Rules with no environment association are treated as global rules (included for all environments).
     * Rules with environments are only included if the agent's environment matches.
     */
    public List<Rule> filterRulesByEnvironment(List<Rule> rules, String agentEnvironment) {
        List<Rule> filtered = new ArrayList<Rule>();
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
        for (Environment env : storage.listEnvironments()) {
            return env.getMode();
        }
        return EnvironmentMode.STUB;
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
