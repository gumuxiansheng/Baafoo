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

        StorageService.AgentRegistration firstOnline = null;
        StorageService.AgentRegistration firstNonLoopback = null;
        StorageService.AgentRegistration anyWithIp = null;

        for (StorageService.AgentRegistration agent : agents) {
            if (agent.lastHeartbeat > onlineThreshold) {
                if (firstOnline == null) {
                    firstOnline = agent;
                }
                if (firstNonLoopback == null && agent.agentIp != null
                        && !agent.agentIp.isEmpty() && !"127.0.0.1".equals(agent.agentIp)) {
                    firstNonLoopback = agent;
                }
                if (anyWithIp == null && agent.agentIp != null && !agent.agentIp.isEmpty()) {
                    anyWithIp = agent;
                }
            }
        }

        // Environment from first online agent
        if (firstOnline != null) {
            info.environment = firstOnline.environment;
        }

        // Agent ID
        if (firstOnline != null && firstOnline.agentId != null && !firstOnline.agentId.isEmpty()) {
            info.agentId = firstOnline.agentId;
        }

        // Agent IP — three-tier fallback
        if (info.environment != null) {
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
        if (info.agentIp == null && firstNonLoopback != null) {
            info.agentIp = firstNonLoopback.agentIp;
        }
        if (info.agentIp == null && anyWithIp != null) {
            info.agentIp = anyWithIp.agentIp;
        }

        // Channel IP fallback
        if (info.agentIp == null && ctx != null) {
            String channelIp = resolveAgentIpFromChannel(ctx);
            if (channelIp != null) {
                info.agentIp = channelIp;
            }
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

    public List<Rule> filterRulesByEnvironment(List<Rule> rules, String agentEnvironment) {
        List<Rule> filtered = new ArrayList<Rule>();
        for (Rule rule : rules) {
            if (!rule.isEnabled()) continue;
            List<String> envs = rule.getEnvironments();
            if (envs == null || envs.isEmpty()) {
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
