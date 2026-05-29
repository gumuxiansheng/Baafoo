package com.baafoo.agent.advice;

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
 * <p>Maintains the current rule set (updated by ControlChannel via hot-reload).
 * Uses "version + atomic reference swap" for race-condition-free updates.</p>
 *
 * <p>Design pattern: Simple static singleton, since this runs in
 * the context of intercepted classes across multiple threads.</p>
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

    private RouteManager() {}

    // --- Rule management (hot-reload safe) ---

    /**
     * Update rules atomically (called by ControlChannel on poll).
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
    }

    public static EnvironmentMode getMode() {
        return currentMode;
    }

    public static boolean isRecording() {
        return recording;
    }

    // --- Request routing ---

    /**
     * Route an intercepted request.
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
