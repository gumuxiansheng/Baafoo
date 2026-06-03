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

    public String resolveAgentEnvironment(String host, int port) {
        for (StorageService.AgentRegistration agent : storage.listAgents()) {
            long onlineThreshold = System.currentTimeMillis() - 90000;
            if (agent.lastHeartbeat > onlineThreshold && agent.environment != null) {
                return agent.environment;
            }
        }
        return null;
    }

    public String resolveAgentId(String agentEnvironment) {
        if (agentEnvironment == null) return null;
        for (StorageService.AgentRegistration agent : storage.listAgents()) {
            long onlineThreshold = System.currentTimeMillis() - 90000;
            if (agent.lastHeartbeat > onlineThreshold
                    && agentEnvironment.equals(agent.environment)
                    && agent.agentId != null
                    && !agent.agentId.isEmpty()) {
                return agent.agentId;
            }
        }
        return null;
    }

    public String resolveAgentIp(String agentEnvironment) {
        // 1. Try to get IP from agent registration (non-loopback)
        if (agentEnvironment != null) {
            for (StorageService.AgentRegistration agent : storage.listAgents()) {
                long onlineThreshold = System.currentTimeMillis() - 90000;
                if (agent.lastHeartbeat > onlineThreshold
                        && agentEnvironment.equals(agent.environment)
                        && agent.agentIp != null
                        && !agent.agentIp.isEmpty()
                        && !"127.0.0.1".equals(agent.agentIp)) {
                    return agent.agentIp;
                }
            }
        }
        // 2. Fallback: any online agent with a non-loopback IP
        for (StorageService.AgentRegistration agent : storage.listAgents()) {
            long onlineThreshold = System.currentTimeMillis() - 90000;
            if (agent.lastHeartbeat > onlineThreshold
                    && agent.agentIp != null
                    && !agent.agentIp.isEmpty()
                    && !"127.0.0.1".equals(agent.agentIp)) {
                return agent.agentIp;
            }
        }
        // 3. Fallback: use registered IP even if it's 127.0.0.1
        for (StorageService.AgentRegistration agent : storage.listAgents()) {
            long onlineThreshold = System.currentTimeMillis() - 90000;
            if (agent.lastHeartbeat > onlineThreshold
                    && agent.agentIp != null
                    && !agent.agentIp.isEmpty()) {
                return agent.agentIp;
            }
        }
        return null;
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
}
