package com.baafoo.agent.advice;

import com.baafoo.agent.AgentManifest;
import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.GlobalRouteState;
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

    private static final String STUB_HOST = "127.0.0.1";

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
        recording = (mode == EnvironmentMode.RECORD || mode == EnvironmentMode.RECORD_AND_STUB);
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

    public static boolean isRecording() {
        return recording;
    }

    private static void rebuildRouteTable(List<Rule> rules) {
        ConcurrentHashMap<String, GlobalRouteState.HostPort> newRoutes = new ConcurrentHashMap<String, GlobalRouteState.HostPort>();

        for (Rule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }

            String protocol = rule.getProtocol() != null ? rule.getProtocol().toLowerCase() : "tcp";
            int stubPort = getStubPort(protocol);
            GlobalRouteState.HostPort routeValue = new GlobalRouteState.HostPort(STUB_HOST, stubPort);

            if (rule.getHost() != null && !rule.getHost().isEmpty()) {
                if (rule.getPort() != null && rule.getPort() > 0) {
                    String key = rule.getHost() + ":" + rule.getPort();
                    newRoutes.put(key, routeValue);
                }
                newRoutes.put(rule.getHost(), routeValue);
            }

            if (rule.getServiceName() != null && !rule.getServiceName().isEmpty()) {
                String key = "svc:" + rule.getServiceName();
                newRoutes.put(key, routeValue);
            }
        }

        GlobalRouteState.ROUTES.clear();
        GlobalRouteState.ROUTES.putAll(newRoutes);

        AgentManifest.ROUTE_TABLE.get().clear();
        for (Map.Entry<String, GlobalRouteState.HostPort> entry : newRoutes.entrySet()) {
            String key = entry.getKey();
            GlobalRouteState.HostPort value = entry.getValue();
            if (key.startsWith("svc:")) {
                String serviceName = key.substring(4);
                AgentManifest.ROUTE_TABLE.get().putService(serviceName, value.host, value.port);
            } else {
                int colonIdx = key.indexOf(':');
                if (colonIdx > 0) {
                    String host = key.substring(0, colonIdx);
                    int port = Integer.parseInt(key.substring(colonIdx + 1));
                    AgentManifest.ROUTE_TABLE.get().put(host, port, value.host, value.port);
                } else {
                    AgentManifest.ROUTE_TABLE.get().getRoutes().put(key, value);
                }
            }
        }
        AgentManifest.ROUTE_TABLE.get().incrementVersion();

        log.info("RouteTable rebuilt: {} routes (GlobalRouteState.ROUTES size={})", newRoutes.size(), GlobalRouteState.ROUTES.size());

        syncRoutesToBootstrapCL(newRoutes);
    }

    private static int getStubPort(String protocol) {
        if (protocol == null) return 9001;
        switch (protocol.toLowerCase()) {
            case "http": return 9000;
            case "tcp": return 9001;
            case "kafka": return 9002;
            case "pulsar": return 9003;
            case "jms": return 9004;
            default: return 9001;
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
        RECORDING_BUFFER.add(recording);

        if (RECORDING_BUFFER.size() >= 100) {
            flushRecordings();
        }
    }

    public static void flushRecordings() {
        List<RecordingEntry> batch = new ArrayList<RecordingEntry>(RECORDING_BUFFER);
        RECORDING_BUFFER.clear();

        if (!batch.isEmpty()) {
            ControlChannel channel = BaafooAgent.getControlChannel();
            if (channel != null) {
                channel.uploadRecordings(batch);
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
            java.lang.reflect.Field field = bootGRS.getField("CURRENT_MODE");
            field.setInt(null, modeValue);
            log.debug("Synced mode {} to Bootstrap CL GlobalRouteState", modeValue);
        } catch (Exception e) {
            log.error("Failed to sync mode to Bootstrap CL: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void syncRoutesToBootstrapCL(ConcurrentHashMap<String, GlobalRouteState.HostPort> newRoutes) {
        ConcurrentHashMap<String, GlobalRouteState.HostPort> bootRoutes = BaafooAgent.getBootstrapRoutes();
        java.lang.reflect.Constructor<?> bootCtor = BaafooAgent.getBootstrapHostPortCtor();
        if (bootRoutes == null || bootCtor == null) return;
        try {
            ((ConcurrentHashMap) bootRoutes).clear();
            for (Map.Entry<String, GlobalRouteState.HostPort> entry : newRoutes.entrySet()) {
                Object bootHostPort = bootCtor.newInstance(entry.getValue().host, entry.getValue().port);
                ((ConcurrentHashMap) bootRoutes).put(entry.getKey(), bootHostPort);
            }
            log.info("Synced {} routes to Bootstrap CL GlobalRouteState.ROUTES", bootRoutes.size());
        } catch (Exception e) {
            log.error("Failed to sync routes to Bootstrap CL: {}", e.getMessage());
        }
    }
}
