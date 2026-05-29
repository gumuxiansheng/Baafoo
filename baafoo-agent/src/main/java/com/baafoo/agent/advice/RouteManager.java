package com.baafoo.agent.advice;

import com.baafoo.agent.AgentManifest;
import com.baafoo.agent.RouteTable;
import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.channel.ControlChannel;
import com.baafoo.core.model.*;
import com.baafoo.core.util.MatchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central route manager for intercepted requests.
 *
 * <p><b>Architecture note (Phase 1 refactor)</b>: RouteManager is now a background
 * service that is NOT referenced by any Advice class. Advice classes read from
 * {@link AgentManifest#ROUTE_TABLE} and {@link AgentManifest#currentMode} exclusively.
 * RouteManager pulls rules from ControlChannel, matches them via MatchEngine, and
 * writes the results into the Bootstrap-safe RouteTable.</p>
 *
 * <p>Design pattern: Simple static singleton, since this runs in
 * the context of intercepted classes across multiple threads.</p>
 *
 * <p>Phase 2 note: KafkaProducerAdvice, PulsarClientAdvice, and RoutingContext
 * still reference RouteManager.route() and RouteResult. These will be migrated
 * to AgentManifest in Phase 2.</p>
 */
public final class RouteManager {

    private static final Logger log = LoggerFactory.getLogger(RouteManager.class);

    /** Current rule set (atomic reference swap for hot-reload) */
    private static final AtomicReference<List<Rule>> RULES = new AtomicReference<List<Rule>>(new ArrayList<Rule>());

    /** Current environments */
    private static final AtomicReference<Map<String, Environment>> ENVIRONMENTS = new AtomicReference<Map<String, Environment>>(new HashMap<String, Environment>());

    /** Match engine */
    private static final MatchEngine MATCH_ENGINE = new MatchEngine();

    /** Current agent environment mode (from server) */
    private static volatile EnvironmentMode currentMode = EnvironmentMode.STUB;

    /** Whether agent is in recording mode */
    private static volatile boolean recording = false;

    /** Recording buffer (batched upload to server) */
    private static final List<RecordingEntry> RECORDING_BUFFER = new CopyOnWriteArrayList<RecordingEntry>();

    /** Default stub host */
    private static final String STUB_HOST = "127.0.0.1";

    private RouteManager() {}

    // --- Rule management (hot-reload safe) ---

    /**
     * Update rules atomically (called by ControlChannel on poll).
     * Also rebuilds the Bootstrap-safe RouteTable in AgentManifest.
     */
    public static void updateRules(List<Rule> newRules) {
        // Sort by priority
        List<Rule> sorted = new ArrayList<Rule>(newRules);
        Collections.sort(sorted, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                return Integer.compare(a.getPriority(), b.getPriority());
            }
        });
        RULES.set(sorted);
        log.info("Rules updated: {} rules loaded", sorted.size());

        // Rebuild the Bootstrap-safe RouteTable
        rebuildRouteTable(sorted);
    }

    public static List<Rule> getRules() {
        return RULES.get();
    }

    // --- Environment management ---

    public static void updateEnvironments(Map<String, Environment> envs) {
        ENVIRONMENTS.set(envs);
    }

    public static void setMode(EnvironmentMode mode) {
        currentMode = mode;
        recording = (mode == EnvironmentMode.RECORD || mode == EnvironmentMode.RECORD_AND_STUB);
        log.info("Mode changed to: {} (recording={})", mode.getValue(), recording);

        // Sync to AgentManifest (int mode constants)
        switch (mode) {
            case STUB:
                AgentManifest.currentMode = AgentManifest.MODE_STUB;
                break;
            case PASSTHROUGH:
                AgentManifest.currentMode = AgentManifest.MODE_PASSTHROUGH;
                break;
            case RECORD:
                AgentManifest.currentMode = AgentManifest.MODE_RECORD;
                break;
            case RECORD_AND_STUB:
                AgentManifest.currentMode = AgentManifest.MODE_RECORD_AND_STUB;
                break;
            default:
                AgentManifest.currentMode = AgentManifest.MODE_STUB;
                break;
        }
    }

    public static EnvironmentMode getMode() {
        return currentMode;
    }

    public static boolean isRecording() {
        return recording;
    }

    // --- Bootstrap-safe RouteTable rebuild ---

    /**
     * Rebuild the RouteTable in AgentManifest from the current rule set.
     * This is the critical bridge between the "Plugin" side (RouteManager with
     * full model access) and the "Core" side (Advice with only Bootstrap-safe types).
     */
    private static void rebuildRouteTable(List<Rule> rules) {
        RouteTable newTable = new RouteTable();

        for (Rule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }

            String protocol = rule.getProtocol() != null ? rule.getProtocol().toLowerCase() : "tcp";
            int stubPort = getStubPort(protocol);

            // Add host:port route
            if (rule.getHost() != null && !rule.getHost().isEmpty() && rule.getPort() != null && rule.getPort() > 0) {
                newTable.put(rule.getHost(), rule.getPort(), STUB_HOST, stubPort, protocol);
            }

            // Add service name route (for Consul DNS interception)
            if (rule.getServiceName() != null && !rule.getServiceName().isEmpty()) {
                newTable.putService(rule.getServiceName(), STUB_HOST, stubPort, protocol);
            }
        }

        newTable.incrementVersion();

        // Atomic swap — Advice classes will see the new table immediately
        AgentManifest.ROUTE_TABLE.set(newTable);
        log.info("RouteTable rebuilt: {} host:port routes, {} service name routes, version={}",
                newTable.getRoutes().size(), newTable.getServiceNames().size(), newTable.getVersion());
    }

    /**
     * Get stub port for a given protocol.
     *
     * @param protocol protocol name
     * @return stub server port
     */
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

    // --- Request routing (Phase 2: used by KafkaProducerAdvice, PulsarClientAdvice) ---

    /**
     * Route an intercepted request.
     *
     * <p>Phase 2 note: This method is still used by KafkaProducerAdvice and
     * PulsarClientAdvice which have not been migrated to the Bootstrap-safe
     * pattern yet. After Phase 2 migration, this method can be removed.</p>
     *
     * @param protocol    protocol
     * @param host        target host
     * @param port        target port
     * @param serviceName service name (Consul)
     * @param method      HTTP method (nullable)
     * @param path        request path (nullable)
     * @param headers     request headers
     * @param queryParams query parameters
     * @param body        request body (nullable)
     * @return routing result
     */
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
            if (isStubMode()) {
                // In stub mode, unmatched = 404 (safety design)
                result.unmatched404 = true;
            }
        }

        return result;
    }

    private static boolean isStubMode() {
        return currentMode == EnvironmentMode.STUB || currentMode == EnvironmentMode.RECORD_AND_STUB;
    }

    // --- Recording ---

    public static void addRecording(RecordingEntry recording) {
        RECORDING_BUFFER.add(recording);

        // Flush if buffer exceeds threshold
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

    /**
     * Route result.
     *
     * <p>Phase 2 note: Still referenced by RoutingContext, KafkaProducerAdvice,
     * and PulsarClientAdvice. Will be migrated to Bootstrap-safe types in Phase 2.</p>
     */
    public static class RouteResult {
        public String protocol;
        public String host;
        public int port;
        public String serviceName;
        public String method;
        public String path;
        public boolean matched;
        public boolean unmatched404;
        public Rule rule;
        public ResponseEntry responseEntry;
        public int responseIndex;
    }
}
