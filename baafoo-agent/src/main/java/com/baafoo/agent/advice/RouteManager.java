package com.baafoo.agent.advice;

import com.baafoo.agent.AgentManifest;
import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.GlobalRouteState;
import com.baafoo.agent.RouteTable;
import com.baafoo.agent.channel.ControlChannel;
import com.baafoo.core.model.*;
import com.baafoo.core.util.MatchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RouteManager {

    private static final Logger log = LoggerFactory.getLogger(RouteManager.class);

    private static final AtomicReference<List<Rule>> RULES = new AtomicReference<List<Rule>>(new ArrayList<Rule>());

    private static final AtomicReference<Map<String, Environment>> ENVIRONMENTS = new AtomicReference<Map<String, Environment>>(new HashMap<String, Environment>());

    private static final MatchEngine MATCH_ENGINE = new MatchEngine();

    private static volatile EnvironmentMode currentMode = EnvironmentMode.RECORD_AND_STUB;

    private static volatile boolean recording = false;

    private static final List<RecordingEntry> RECORDING_BUFFER = new CopyOnWriteArrayList<RecordingEntry>();

    private RouteManager() {}

    public static void updateRules(List<Rule> newRules) {
        List<Rule> sorted = new ArrayList<Rule>(newRules);
        Collections.sort(sorted, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                return Integer.compare(a.getPriority(), b.getPriority());
            }
        });
        RULES.set(sorted);
        log.info("Rules updated: {} rules loaded", sorted.size());

        rebuildRouteTable(sorted);
    }

    public static List<Rule> getRules() {
        return RULES.get();
    }

    public static void updateEnvironments(Map<String, Environment> envs) {
        ENVIRONMENTS.set(envs);
    }

    public static void setMode(EnvironmentMode mode) {
        currentMode = mode;
        recording = (mode == EnvironmentMode.RECORD || mode == EnvironmentMode.RECORD_AND_STUB
                || mode == EnvironmentMode.RECORD_ALL);
        log.info("Mode changed to: {} (recording={})", mode.getValue(), recording);

        int modeValue;
        switch (mode) {
            case STUB:
                modeValue = GlobalRouteState.MODE_STUB;
                break;
            case PASSTHROUGH:
                modeValue = GlobalRouteState.MODE_PASSTHROUGH;
                break;
            case RECORD:
                modeValue = GlobalRouteState.MODE_RECORD;
                break;
            case RECORD_AND_STUB:
                modeValue = GlobalRouteState.MODE_RECORD_AND_STUB;
                break;
            case RECORD_ALL:
                modeValue = GlobalRouteState.MODE_RECORD_ALL;
                break;
            default:
                modeValue = GlobalRouteState.MODE_STUB;
                break;
        }

        AgentManifest.currentMode = modeValue;

        syncModeToBootstrapCL(modeValue);
    }

    public static EnvironmentMode getMode() {
        return currentMode;
    }

    /**
     * Check if there are any enabled rules for the given protocol.
     * Used by protocol-specific advice (Kafka, Pulsar, JMS) to decide
     * whether to intercept, regardless of the specific host in the rule.
     */
    public static boolean hasProtocolRoutes(String protocol) {
        if (protocol == null) return false;
        for (Rule rule : RULES.get()) {
            if (rule.isEnabled() && protocol.equalsIgnoreCase(rule.getProtocol())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRecording() {
        return recording;
    }

    private static void rebuildRouteTable(List<Rule> rules) {
        ConcurrentHashMap<String, GlobalRouteState.HostPort> newRoutes = new ConcurrentHashMap<String, GlobalRouteState.HostPort>();

        for (Rule rule : rules) {
            if (!rule.isEnabled()) {
                log.debug("Skipping disabled rule: {}", rule.getName());
                continue;
            }

            String protocol = rule.getProtocol() != null ? rule.getProtocol().toLowerCase() : "tcp";
            int stubPort = getStubPort(protocol);
            String stubHost = GlobalRouteState.SERVER_HOST;
            GlobalRouteState.HostPort routeValue = new GlobalRouteState.HostPort(stubHost, stubPort);

            if (rule.getHost() != null && !rule.getHost().isEmpty()) {
                if (rule.getPort() != null && rule.getPort() > 0) {
                    String key = rule.getHost() + ":" + rule.getPort();
                    newRoutes.put(key, routeValue);
                    log.debug("Route entry: {} -> {}:{} (protocol={})", key, stubHost, stubPort, protocol);
                } else {
                    newRoutes.put(rule.getHost(), routeValue);
                    log.debug("Route entry: {} -> {}:{} (protocol={})", rule.getHost(), stubHost, stubPort, protocol);
                }
            } else {
                log.debug("Rule '{}' has no host, protocol={}", rule.getName(), protocol);
            }

            if (rule.getServiceName() != null && !rule.getServiceName().isEmpty()) {
                String key = "svc:" + rule.getServiceName();
                newRoutes.put(key, routeValue);
                log.debug("Route entry: {} -> {}:{} (protocol={})", key, stubHost, stubPort, protocol);
            }
        }

        // Atomic swap: replace the entire map reference instead of clear+putAll
        // to avoid a window where concurrent readers see an empty route table
        GlobalRouteState.ROUTES = newRoutes;

        // Build a new RouteTable and swap atomically
        RouteTable newTable = new RouteTable();
        for (Map.Entry<String, GlobalRouteState.HostPort> entry : newRoutes.entrySet()) {
            String key = entry.getKey();
            GlobalRouteState.HostPort value = entry.getValue();
            if (key.startsWith("svc:")) {
                String serviceName = key.substring(4);
                newTable.putService(serviceName, value.host, value.port);
            } else {
                int colonIdx = key.indexOf(':');
                if (colonIdx > 0) {
                    String host = key.substring(0, colonIdx);
                    int port = Integer.parseInt(key.substring(colonIdx + 1));
                    newTable.put(host, port, value.host, value.port);
                } else {
                    newTable.getRoutes().put(key, value);
                }
            }
        }
        newTable.incrementVersion();
        AgentManifest.ROUTE_TABLE.set(newTable);

        log.info("RouteTable rebuilt: {} routes (GlobalRouteState.ROUTES size={})", newRoutes.size(), GlobalRouteState.ROUTES.size());

        syncRoutesToBootstrapCL(newRoutes);
    }

    private static int getStubPort(String protocol) {
        if (protocol == null) return GlobalRouteState.TCP_PORT;
        switch (protocol.toLowerCase()) {
            case "http": return GlobalRouteState.HTTP_PORT;
            case "tcp": return GlobalRouteState.TCP_PORT;
            case "kafka": return GlobalRouteState.KAFKA_PORT;
            case "pulsar": return GlobalRouteState.PULSAR_PORT;
            case "jms": return GlobalRouteState.JMS_PORT;
            case "grpc": return GlobalRouteState.GRPC_PORT;
            default: return GlobalRouteState.TCP_PORT;
        }
    }

    public static RouteResult route(String protocol, String host, int port,
                                     String serviceName, String method, String path,
                                     Map<String, String> headers, Map<String, String> queryParams,
                                     String body) {

        MatchEngine.MatchResult matchResult = MATCH_ENGINE.match(
                getRules(), protocol, host, port, serviceName,
                method, path, headers, queryParams, body);

        RouteResult result = new RouteResult();
        result.protocol = protocol;
        result.host = host;
        result.port = port;
        result.serviceName = serviceName;
        result.method = method;
        result.path = path;
        result.matched = matchResult.isMatched();

        if (matchResult.isMatched()) {
            result.rule = matchResult.getRule();
            result.responseEntry = matchResult.getResponse();
            result.responseIndex = matchResult.getResponseIndex();

            log.debug("Request routed to rule: {} response: {}",
                    result.rule.getName(),
                    result.responseEntry != null ? result.responseEntry.getName() : "default");
        } else {
            log.debug("No rule matched for {}://{}:{}{}, request will passthrough",
                    protocol, host, port, path != null ? path : "");
        }

        return result;
    }

    public static void addRecording(RecordingEntry recording) {
        RecordingBuffer buffer = BaafooAgent.getRecordingBuffer();
        if (buffer != null) {
            buffer.add(recording);
        } else {
            // Fallback to legacy buffer if RecordingBuffer not initialized
            RECORDING_BUFFER.add(recording);
            if (RECORDING_BUFFER.size() >= 100) {
                flushRecordings();
            }
        }
    }

    public static void flushRecordings() {
        RecordingBuffer buffer = BaafooAgent.getRecordingBuffer();
        if (buffer != null) {
            buffer.flush();
        }
        // Also flush any remaining entries in the legacy buffer
        if (!RECORDING_BUFFER.isEmpty()) {
            List<RecordingEntry> batch = new ArrayList<RecordingEntry>(RECORDING_BUFFER);
            RECORDING_BUFFER.clear();
            if (!batch.isEmpty()) {
                ControlChannel channel = BaafooAgent.getControlChannel();
                if (channel != null) {
                    channel.uploadRecordings(batch);
                }
            }
        }
    }

    public static class RouteResult {
        public String protocol;
        public String host;
        public int port;
        public String serviceName;
        public String method;
        public String path;
        public boolean matched;
        public Rule rule;
        public ResponseEntry responseEntry;
        public int responseIndex;
    }

    private static void syncModeToBootstrapCL(int modeValue) {
        try {
            Class<?> bootGRS = BaafooAgent.getBootstrapGRSClass();
            if (bootGRS == null) return;
            java.lang.reflect.Field field;
            try {
                field = bootGRS.getField("CURRENT_MODE");
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(
                        "Bootstrap CL GlobalRouteState is missing field 'CURRENT_MODE'. "
                                + "The Bootstrap JAR is out of sync with the agent source — "
                                + "rebuild the agent JAR and restart. Cause: " + e.getMessage(), e);
            }
            field.setInt(null, modeValue);
            log.debug("Synced mode {} to Bootstrap CL GlobalRouteState", modeValue);
        } catch (IllegalStateException e) {
            // P2-4: re-throw actionable configuration errors so the operator sees them
            throw e;
        } catch (Exception e) {
            log.error("Failed to sync mode to Bootstrap CL: {}", e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void syncRoutesToBootstrapCL(ConcurrentHashMap<String, GlobalRouteState.HostPort> newRoutes) {
        Class<?> bootGRS = BaafooAgent.getBootstrapGRSClass();
        java.lang.reflect.Constructor<?> bootCtor = BaafooAgent.getBootstrapHostPortCtor();
        if (bootGRS == null || bootCtor == null) return;
        try {
            // Build the new map first, then atomically swap the ROUTES field reference
            // (mirroring the App-CL atomic swap in rebuildRouteTable). This avoids the
            // clear+putAll window where concurrent readers see an empty route table.
            ConcurrentHashMap newBootRoutes = new ConcurrentHashMap();
            for (Map.Entry<String, GlobalRouteState.HostPort> entry : newRoutes.entrySet()) {
                Object bootHostPort = bootCtor.newInstance(entry.getValue().host, entry.getValue().port);
                newBootRoutes.put(entry.getKey(), bootHostPort);
            }
            java.lang.reflect.Field routesField;
            try {
                routesField = bootGRS.getField("ROUTES");
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(
                        "Bootstrap CL GlobalRouteState is missing field 'ROUTES'. "
                                + "The Bootstrap JAR is out of sync with the agent source — "
                                + "rebuild the agent JAR and restart. Cause: " + e.getMessage(), e);
            }
            routesField.set(null, newBootRoutes);
            // Update the cached reference used by subsequent syncs
            BaafooAgent.updateBootstrapRoutes(newBootRoutes);
            log.info("Synced {} routes to Bootstrap CL GlobalRouteState.ROUTES (atomic swap)", newBootRoutes.size());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to sync routes to Bootstrap CL: {}", e.getMessage());
        }
    }
}
