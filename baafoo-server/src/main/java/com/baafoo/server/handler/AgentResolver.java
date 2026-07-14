package com.baafoo.server.handler;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.JdbcStorageService;
import com.baafoo.server.storage.AgentRegistration;
import com.baafoo.server.storage.StorageService;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves agent identity and environment information from storage.
 *
 * <p>Extracted from HttpStubHandler to separate agent resolution
 * concerns from request handling logic.</p>
 */
public class AgentResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentResolver.class);

    private final StorageService storage;
    private final ServerConfig config;

    public AgentResolver(StorageService storage) {
        this.storage = storage;
        // Best-effort: extract ServerConfig from JdbcStorageService so the
        // single-arg constructor still honours global settings such as
        // unknownEnvironmentDefault. Falls back to null (→ safe PASSTHROUGH
        // default) for non-JDBC storage implementations (e.g. in-memory test
        // doubles). This mirrors the pattern used by ApiContext.getSceneService().
        this.config = (storage instanceof JdbcStorageService)
                ? ((JdbcStorageService) storage).getServerConfig()
                : null;
    }

    public AgentResolver(StorageService storage, ServerConfig config) {
        this.storage = storage;
        this.config = config;
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
        String channelIp = null;
        if (ctx != null) {
            channelIp = resolveAgentIpFromChannel(ctx);
        }
        return resolveByIp(channelIp);
    }

    /**
     * Resolve agent info from a remote IP string.
     *
     * <p>Used by protocol handlers that do not have a Netty {@link ChannelHandlerContext}
     * but still need to associate traffic with an agent/environment (e.g. JMS broker
     * interceptors).</p>
     */
    public AgentInfo resolveByIp(String channelIp) {
        AgentInfo info = new AgentInfo();
        List<AgentRegistration> agents = storage.listAgents();
        long onlineThreshold = System.currentTimeMillis() - 90000;

        // Collect all agents that match by IP.
        // When multiple agents share the same IP:
        //  - same environment → pick the one with the most recent heartbeat (container restart)
        //  - different environments → mark ambiguous (safety: don't guess)
        AgentRegistration ipMatched = null;
        boolean ipMatchAmbiguous = false;

        for (AgentRegistration agent : agents) {
            if (agent.lastHeartbeat > onlineThreshold) {
                if (channelIp != null && channelIp.equals(agent.agentIp)) {
                    if (ipMatched == null) {
                        ipMatched = agent;
                    } else if (java.util.Objects.equals(ipMatched.environment, agent.environment)) {
                        // Same environment: prefer the more recent heartbeat (container restart scenario)
                        if (agent.lastHeartbeat > ipMatched.lastHeartbeat) {
                            ipMatched = agent;
                        }
                    } else {
                        // Different environments on the same IP — ambiguous, cannot safely resolve
                        ipMatchAmbiguous = true;
                    }
                }
            }
        }

        // Resolve environment: only use IP-matched agent, never fall back to "first online"
        AgentRegistration resolved = null;
        if (ipMatched != null && !ipMatchAmbiguous) {
            resolved = ipMatched;
        } else if (ipMatchAmbiguous) {
            log.warn("Multiple online agents share IP {} with different environments — " +
                    "cannot determine environment, only global rules will match", channelIp);
        }

        // Fallback for MQ (Kafka/Pulsar/JMS) connections: the source IP is often a
        // Docker gateway (e.g. 172.x.0.1) because the Agent redirects the client to
        // localhost:PORT, and the Server sees the gateway IP instead of the container IP.
        // Also applies when channelIp is null (e.g. JmsRecordingPlugin.afterDeliver where
        // the connection cannot be resolved from the consumer). In that case we skip the
        // gateway-subnet heuristic (which requires an IP) but still try the server-subnet
        // and unique-environment fallbacks.
        if (resolved == null) {
            String ipLabel = channelIp != null ? channelIp : "null";
            // 1) Gateway subnet: gateway 172.19.0.1 → find agent in 172.19.0.x
            //    (only applicable when we have a channel IP)
            if (channelIp != null) {
                resolved = findAgentByGatewaySubnet(agents, channelIp, onlineThreshold);
                if (resolved != null) {
                    log.info("IP {} not matched directly; resolved via gateway subnet fallback to agent {} (env={})",
                            channelIp, resolved.agentId, resolved.environment);
                }
            }
            // 2) Server subnets: find agents on any /24 subnet the server is connected to.
            //    This handles Docker deployments where the server has multiple network
            //    interfaces and the MQ connection comes through a different network
            //    than the agent's registered IP.
            if (resolved == null) {
                resolved = findAgentByServerSubnets(agents, onlineThreshold);
                if (resolved != null) {
                    log.info("IP {} not matched; resolved via server subnet fallback to agent {} (env={})",
                            ipLabel, resolved.agentId, resolved.environment);
                } else {
                    // 3) Unique environment: if all online agents share the same environment, use it
                    String env = findUniqueOnlineEnvironment(agents, onlineThreshold);
                    if (env != null) {
                        log.info("IP {} not matched; all online agents share environment '{}', using as fallback",
                                ipLabel, env);
                        for (AgentRegistration agent : agents) {
                            if (agent.lastHeartbeat > onlineThreshold && env.equals(agent.environment)) {
                                if (resolved == null || agent.lastHeartbeat > resolved.lastHeartbeat) {
                                    resolved = agent;
                                }
                            }
                        }
                    }
                }
            }
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
     * If all online agents belong to the same environment, return it.
     * Returns null if there are zero online agents or multiple environments.
     */
    private String findUniqueOnlineEnvironment(List<AgentRegistration> agents, long onlineThreshold) {
        String env = null;
        for (AgentRegistration agent : agents) {
            if (agent.lastHeartbeat > onlineThreshold) {
                if (env == null) {
                    env = agent.environment;
                } else if (!env.equals(agent.environment)) {
                    return null; // multiple environments
                }
            }
        }
        return env;
    }

    /**
     * When the channel IP is a Docker gateway (e.g. 172.19.0.1), the actual
     * container IP is in the same /24 subnet (e.g. 172.19.0.x). Find the
     * most recent online agent in that subnet.
     */
    private AgentRegistration findAgentByGatewaySubnet(
            List<AgentRegistration> agents, String channelIp, long onlineThreshold) {
        // Only apply this heuristic for IPs ending in the configured gateway octet
        // (default "1" — typical Docker gateway). Override via -Dbaafoo.gateway.octet=...
        String gatewayOctet = System.getProperty("baafoo.gateway.octet", "1");
        int lastDot = channelIp.lastIndexOf('.');
        if (lastDot < 0) return null;
        String lastOctet = channelIp.substring(lastDot + 1);
        if (!gatewayOctet.equals(lastOctet)) return null;

        String subnet = channelIp.substring(0, lastDot); // e.g. "172.19.0"
        AgentRegistration best = null;
        for (AgentRegistration agent : agents) {
            if (agent.lastHeartbeat > onlineThreshold
                    && agent.agentIp != null
                    && agent.agentIp.startsWith(subnet + ".")) {
                if (best == null || agent.lastHeartbeat > best.lastHeartbeat) {
                    best = agent;
                }
            }
        }
        return best;
    }

    /** Cached server subnets for cross-network agent matching. */
    private static volatile List<String> cachedServerSubnets;
    private static volatile long serverSubnetsCacheTime;

    /**
     * Get the /24 subnets of the server's private network interfaces.
     * Results are cached for 60 seconds.
     */
    private List<String> getServerSubnets() {
        long now = System.currentTimeMillis();
        if (cachedServerSubnets != null && (now - serverSubnetsCacheTime) < 60000) {
            return cachedServerSubnets;
        }

        List<String> subnets = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                NetworkInterface netIf = nets.nextElement();
                for (InterfaceAddress addr : netIf.getInterfaceAddresses()) {
                    String ip = addr.getAddress().getHostAddress();
                    if (isPrivateDockerIp(ip)) {
                        int lastDot = ip.lastIndexOf('.');
                        if (lastDot > 0) {
                            String subnet = ip.substring(0, lastDot);
                            if (!subnets.contains(subnet)) {
                                subnets.add(subnet);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to enumerate server network interfaces: {}", e.getMessage());
        }

        cachedServerSubnets = subnets;
        serverSubnetsCacheTime = now;
        return subnets;
    }

    private static boolean isPrivateDockerIp(String ip) {
        // Docker typically uses 172.17-31.x.x ranges
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return ip.startsWith("10.") || ip.startsWith("192.168.");
    }

    /**
     * When the channel IP doesn't match any agent directly and the gateway
     * subnet fallback also failed, try finding agents on any /24 subnet
     * that the server is connected to. This handles Docker deployments where
     * the server has multiple network interfaces (e.g. baafoo-net and
     * baafoo-staging-net) and the MQ connection comes through a different
     * network than the agent's registered IP.
     *
     * <p>If multiple agents on different environments are found, the match
     * is ambiguous and this method returns {@code null} — consistent with
     * {@link #resolveByIp}'s ambiguity handling. Returning a best-effort
     * agent here would let traffic from environment A pick up environment
     * B's rules, violating environment isolation. Only agents sharing the
     * same environment may compete, in which case the most recent heartbeat
     * wins (container restart scenario).</p>
     */
    private AgentRegistration findAgentByServerSubnets(
            List<AgentRegistration> agents, long onlineThreshold) {
        List<String> serverSubnets = getServerSubnets();
        if (serverSubnets.isEmpty()) return null;

        AgentRegistration best = null;
        String bestEnv = null;
        boolean ambiguous = false;

        for (AgentRegistration agent : agents) {
            if (agent.lastHeartbeat > onlineThreshold && agent.agentIp != null) {
                int lastDot = agent.agentIp.lastIndexOf('.');
                if (lastDot > 0) {
                    String agentSubnet = agent.agentIp.substring(0, lastDot);
                    if (serverSubnets.contains(agentSubnet)) {
                        if (best == null) {
                            best = agent;
                            bestEnv = agent.environment;
                        } else if (java.util.Objects.equals(bestEnv, agent.environment)) {
                            if (agent.lastHeartbeat > best.lastHeartbeat) {
                                best = agent;
                            }
                        } else {
                            // Different environments on the same server subnet — ambiguous.
                            // Do NOT pick a winner; return null so only global rules match.
                            ambiguous = true;
                        }
                    }
                }
            }
        }

        if (ambiguous) {
            log.warn("Multiple online agents on server subnets belong to different environments — " +
                    "cannot determine environment, only global rules will match");
            return null;
        }

        return best;
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
        // When environment is null or not found, use configured default
        String defaultMode = (config != null) ? config.getUnknownEnvironmentDefault() : null;
        if ("stub".equalsIgnoreCase(defaultMode)) {
            return EnvironmentMode.STUB;
        }
        // Default: PASSTHROUGH (safe — don't stub if we can't determine the environment)
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
