package com.baafoo.agent.channel;

import com.baafoo.core.config.AgentConfig;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.Rule;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent-to-Server control channel via HTTP long-polling.
 *
 * <p>Uses only JDK HttpURLConnection — NO Netty dependency.
 * This is critical because the agent module must be lightweight
 * and not conflict with application classpaths.</p>
 *
 * <p>Protocol per concept-design:
 * <ul>
 *   <li>POST /__baafoo__/api/agent/register — initial registration</li>
 *   <li>POST /__baafoo__/api/agent/heartbeat — periodic heartbeat</li>
 *   <li>GET  /__baafoo__/api/agent/poll — long-poll for mode/rules changes</li>
 *   <li>POST /__baafoo__/api/agent/recordings — upload recorded data</li>
 * </ul></p>
 */
public class ControlChannel {

    private static final Logger log = LoggerFactory.getLogger(ControlChannel.class);

    /** API base path on the Baafoo server */
    private static final String API_BASE = "/__baafoo__/api";

    private final AgentConfig config;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final java.util.concurrent.ExecutorService ioWorker;
    private final AtomicBoolean running;
    private java.util.function.Consumer<String> agentIdCallback;

    /** Circuit breaker for heartbeat/poll HTTP calls. */
    private final CircuitBreaker circuitBreaker;

    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> pollTask;

    /**
     * H6: registration status flag. Set to {@code true} by the background
     * registration thread after a successful {@code register()} call.
     * {@code doHeartbeat()} and {@code doPollRules()} skip work while this is
     * false — the server doesn't know about this agent until registration
     * succeeds, so heartbeat/poll would just produce 401/404s.
     */
    private volatile boolean registered = false;

    public ControlChannel(AgentConfig config) {
        this.config = config;
        this.mapper = com.baafoo.core.util.JsonUtils.MAPPER;
        // Scheduler: 2 threads, only dispatches tasks (no blocking I/O)
        this.scheduler = Executors.newScheduledThreadPool(2, new java.util.concurrent.ThreadFactory() {
            private int count = 0;
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "baafoo-channel-" + (++count));
                t.setDaemon(true);
                return t;
            }
        });
        // IO worker: bounded thread pool for blocking HTTP calls.
        // Scheduler tasks submit I/O work here and return immediately, so
        // the scheduler threads never block on network calls.
        this.ioWorker = new ThreadPoolExecutor(
                2, 2, 60L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>(32),
                new java.util.concurrent.ThreadFactory() {
                    private int count = 0;
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "baafoo-io-" + (++count));
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        // Open after 5 consecutive failures, stay open for 30s before probing
        this.circuitBreaker = new CircuitBreaker("control-channel", 5, 30_000L);
        this.running = new AtomicBoolean(false);
    }

    /**
     * Build the server base URL from config.
     * Uses server.host + server.apiPort if available, falls back to legacy serverUrl.
     */
    private String getServerBaseUrl() {
        AgentConfig.ServerConnection sc = config.getServer();
        if (sc != null && sc.getHost() != null && !sc.getHost().isEmpty()) {
            String scheme = sc.isUseSsl() ? "https" : "http";
            return scheme + "://" + sc.getHost() + ":" + sc.getApiPort();
        }
        // Legacy fallback
        String url = config.getServerUrl();
        return (url != null && !url.isEmpty()) ? url : "http://127.0.0.1:8084";
    }

    public void setAgentIdCallback(java.util.function.Consumer<String> callback) {
        this.agentIdCallback = callback;
    }

    private <T> T unwrapApiResponse(String body, Class<T> type) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
        com.fasterxml.jackson.databind.JsonNode dataNode = root.get("data");
        if (dataNode != null) {
            return mapper.treeToValue(dataNode, type);
        }
        return null;
    }

    /**
     * Start the control channel: spawn the registration thread, then start
     * heartbeat + poll loops.
     *
     * <p>H6: registration runs on a dedicated daemon thread so that premain
     * is not blocked for up to ~70s (3 retries × backoff + 5s connect × 3).
     * Heartbeat and poll start immediately but skip work until
     * {@link #registered} flips to true.</p>
     *
     * @throws IllegalStateException if (M10) the server requires auth
     *         ({@code server.authEnabled: true}) but no {@code apiKey} is
     *         configured — failing fast here is far clearer than a stream
     *         of 401 responses on heartbeat/poll.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        // M10: pre-validate apiKey if auth is enabled. Without this the agent
        // would appear to start successfully and then fail every heartbeat/poll
        // with HTTP 401, which is much harder to diagnose than a startup error.
        AgentConfig.ServerConnection sc = config.getServer();
        if (sc != null && sc.isAuthEnabled()) {
            String apiKey = sc.getApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException(
                        "Server requires auth (server.authEnabled=true) but agent apiKey is not configured. "
                                + "Set 'server.apiKey' in agent YAML.");
            }
        }

        // 1. Register with server on a daemon thread (H6). The retry loop can
        // block for up to ~70s (3 retries × backoff + 5s connect each); running
        // it inline would block premain and delay application startup.
        Thread registerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                for (int i = 0; i < config.getConnectionRetries(); i++) {
                    ok = register();
                    if (ok) break;
                    if (i < config.getConnectionRetries() - 1) {
                        long backoff = config.getRetryBackoffMs() * (i + 1);
                        log.warn("Registration attempt {}/{} failed, retrying in {}ms...",
                                i + 1, config.getConnectionRetries(), backoff);
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (!ok) {
                    log.warn("Failed to register with server at {} after {} attempts; "
                                    + "heartbeat/poll will retry registration on subsequent cycles",
                            getServerBaseUrl(), config.getConnectionRetries());
                }
            }
        }, "baafoo-control-register");
        registerThread.setDaemon(true);
        registerThread.start();

        // 2. Start heartbeat (skips work until registered == true)
        heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeat, 0, config.getHeartbeatIntervalSec(), TimeUnit.SECONDS);

        pollTask = scheduler.scheduleAtFixedRate(this::pollRules, 0, config.getPollIntervalSec(), TimeUnit.SECONDS);

        log.info("Control channel started (heartbeat={}s, poll={}s, registration async)",
                config.getHeartbeatIntervalSec(), config.getPollIntervalSec());
    }

    /**
     * Stop the control channel.
     */
    public void stop() {
        running.set(false);
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        if (pollTask != null) pollTask.cancel(false);
        scheduler.shutdown();
        ioWorker.shutdown();
        try {
            if (!ioWorker.awaitTermination(5, TimeUnit.SECONDS)) {
                ioWorker.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioWorker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // --- Control API calls ---

    private boolean register() {
        try {
            String hostname;
            try {
                hostname = java.net.InetAddress.getLocalHost().getHostName();
            } catch (java.net.UnknownHostException e) {
                // Java 8 on Alpine (musl libc) can fail to resolve the container
                // hostname. Use a fallback so registration doesn't permanently fail.
                String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
                hostname = "baafoo-agent-" + pid.split("@")[0];
                log.warn("Could not resolve local hostname, using fallback: {}", hostname);
            }
            AgentRegisterRequest req = new AgentRegisterRequest();
            String agentId = config.getAgentId();
            if (agentId == null || agentId.trim().isEmpty()) {
                agentId = hostname;
            }
            req.agentId = agentId;
            req.environment = config.getEnvironment();
            req.hostname = hostname;
            req.version = "1.0.0";
            req.protocols = config.getProtocols();
            req.agentIp = resolveLocalIp();

            // Validate before sending — a malformed request would cause an
            // opaque 400 or NPE on the server side.
            String validationError = req.validate();
            if (validationError != null) {
                log.error("Registration request invalid: {}", validationError);
                return false;
            }

            String json = mapper.writeValueAsString(req);
            HttpURLConnection conn = post(API_BASE + "/agent/register", json);
            int code = conn.getResponseCode();

            if (code == 200 || code == 201) {
                String body = readResponse(conn);
                AgentRegisterResponse res = unwrapApiResponse(body, AgentRegisterResponse.class);
                if (res != null) {

                    if (res.agentId != null) {
                        if (agentIdCallback != null) {
                            agentIdCallback.accept(res.agentId);
                        }
                    }

                    // Apply initial mode
                    if (res.mode != null) {
                        com.baafoo.agent.advice.RouteManager.setMode(
                                EnvironmentMode.fromValue(res.mode));
                    }

                    log.info("Registered with server. Agent ID: {}, mode: {}",
                            config.getAgentId(), res.mode);

                    // Retry DNS resolution for SERVER_HOST_IP now that network is confirmed up
                    com.baafoo.agent.AgentManifest.resolveServerHostIp();

                    // H6: flip the registration flag so heartbeat/poll stop skipping.
                    registered = true;
                    return true;
                } else {
                    log.warn("Registration response missing 'data' field: {}", body);
                    return false;
                }
            } else {
                log.warn("Registration failed: HTTP {}", code);
                return false;
            }
        } catch (Exception e) {
            log.error("Registration error: {}", e.getMessage());
            return false;
        }
    }

    private void heartbeat() {
        // Submit I/O to the worker pool so the scheduler thread is never blocked.
        // If the queue is full (server slow), the oldest pending heartbeat is
        // discarded — heartbeats are idempotent so this is safe.
        try {
            ioWorker.submit(this::doHeartbeat);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("Heartbeat skipped — I/O queue full");
        }
    }

    private void doHeartbeat() {
        // H6: skip until registration has succeeded. The server doesn't know
        // about this agent yet, so a heartbeat would be rejected (401/404).
        if (!registered) {
            log.debug("Heartbeat skipped — not yet registered with server");
            return;
        }
        if (!circuitBreaker.allowRequest()) {
            log.debug("Heartbeat skipped — circuit breaker OPEN (failures={})",
                    circuitBreaker.getConsecutiveFailures());
            return;
        }
        try {
            HeartbeatRequest req = new HeartbeatRequest();
            req.agentId = config.getAgentId();
            req.timestamp = System.currentTimeMillis();
            req.agentIp = resolveLocalIp();

            // P3: Include plugin health statuses in heartbeat
            req.pluginStatuses = collectPluginStatuses();

            String json = mapper.writeValueAsString(req);
            HttpURLConnection conn = post(API_BASE + "/agent/heartbeat", json);

            if (conn.getResponseCode() == 200) {
                circuitBreaker.recordSuccess();
            } else {
                log.warn("Heartbeat failed: HTTP {}", conn.getResponseCode());
                circuitBreaker.recordFailure();
            }
        } catch (Exception e) {
            log.warn("Heartbeat error: {}", e.getMessage());
            circuitBreaker.recordFailure();
        }
    }

    private void pollRules() {
        try {
            ioWorker.submit(this::doPollRules);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("Poll skipped — I/O queue full");
        }
    }

    private void doPollRules() {
        // H6: skip until registration has succeeded. The server doesn't know
        // about this agent yet, so poll would return 401/404 (not 204/200).
        if (!registered) {
            log.debug("Poll skipped — not yet registered with server");
            return;
        }
        if (!circuitBreaker.allowRequest()) {
            log.debug("Poll skipped — circuit breaker OPEN (failures={})",
                    circuitBreaker.getConsecutiveFailures());
            return;
        }
        try {
            String agentId = config.getAgentId();
            String environment = config.getEnvironment();
            StringBuilder params = new StringBuilder();
            if (agentId != null && !agentId.trim().isEmpty()) {
                params.append("agentId=").append(java.net.URLEncoder.encode(agentId, "UTF-8"));
            }
            if (environment != null && !environment.isEmpty()) {
                if (params.length() > 0) params.append("&");
                params.append("environment=").append(java.net.URLEncoder.encode(environment, "UTF-8"));
            }
            String url = getServerBaseUrl() + API_BASE + "/agent/poll?" + params.toString();

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            applyApiKey(conn);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(config.getPollIntervalSec() * 1000 + 1000);

            int code = conn.getResponseCode();
            if (code == 200) {
                circuitBreaker.recordSuccess();
                String body = readResponse(conn);
                PollResponse res = unwrapApiResponse(body, PollResponse.class);
                if (res != null) {

                    // Update rules
                    if (res.rules != null) {
                        com.baafoo.agent.advice.RouteManager.updateRules(res.rules);
                    }

                    // Update mode
                    if (res.mode != null) {
                        com.baafoo.agent.advice.RouteManager.setMode(
                                EnvironmentMode.fromValue(res.mode));
                    }
                }
            } else if (code == 204) {
                // No changes — still a success from the breaker's perspective
                circuitBreaker.recordSuccess();
            } else {
                log.warn("Poll failed: HTTP {}", code);
                circuitBreaker.recordFailure();
            }
        } catch (java.net.SocketTimeoutException e) {
            // Long-poll timeout is expected, retry — not a failure
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            log.warn("Poll error: {}", e.getMessage());
            circuitBreaker.recordFailure();
        }
    }

    /**
     * Upload recorded data to server (batched to avoid oversized requests).
     */
    public void uploadRecordings(List<RecordingEntry> recordings) {
        int batchSize = 50;
        for (int i = 0; i < recordings.size(); i += batchSize) {
            List<RecordingEntry> batch = recordings.subList(i, Math.min(i + batchSize, recordings.size()));
            try {
                String json = mapper.writeValueAsString(batch);
                // M2: URL-encode agentId and environment. Previously they were
                // concatenated raw — a value containing '&' or '=' or non-ASCII
                // would silently corrupt the query string or 400 on the server.
                java.util.LinkedHashMap<String, String> params = new java.util.LinkedHashMap<String, String>();
                params.put("agentId", config.getAgentId() != null ? config.getAgentId() : "");
                params.put("environment", config.getEnvironment() != null ? config.getEnvironment() : "");
                HttpURLConnection conn = post(buildUrl(API_BASE + "/agent/recordings", params), json);
                int code = conn.getResponseCode();
                if (code == 200) {
                    log.info("Uploaded {} recordings (batch {}/{})", batch.size(),
                            (i / batchSize) + 1, (recordings.size() + batchSize - 1) / batchSize);
                } else {
                    log.warn("Recording upload failed: HTTP {} (batch {}/{})", code,
                            (i / batchSize) + 1, (recordings.size() + batchSize - 1) / batchSize);
                    throw new RuntimeException("Upload failed with HTTP " + code);
                }
            } catch (Exception e) {
                log.warn("Recording upload error: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * M2: build a URL with query parameters, URL-encoding each value with
     * UTF-8. Skips entries whose value is null. Returns just the
     * {@code path?k1=v1&k2=v2} portion (caller prepends the server base URL).
     */
    private String buildUrl(String path, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path);
        sb.append(path.indexOf('?') >= 0 ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    // --- HTTP helpers (JDK HttpURLConnection only, NO Netty) ---

    private void applyApiKey(HttpURLConnection conn) {
        AgentConfig.ServerConnection sc = config.getServer();
        if (sc != null && sc.getApiKey() != null && !sc.getApiKey().isEmpty()) {
            conn.setRequestProperty("X-Api-Key", sc.getApiKey());
        }
    }

    private HttpURLConnection post(String path, String json) throws Exception {
        String url = getServerBaseUrl() + path;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        applyApiKey(conn);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes("UTF-8"));
            os.flush();
        }

        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        java.io.InputStream is = conn.getResponseCode() >= 400
                ? conn.getErrorStream()
                : conn.getInputStream();

        if (is == null) return "";

        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString("UTF-8");
        } finally {
            is.close();
        }
    }

    /**
     * P3: Collect plugin health statuses from PluginManager.
     * Returns a map of target name → status map for serialization in heartbeat.
     */
    private Map<String, Object> collectPluginStatuses() {
        try {
            com.baafoo.agent.plugin.PluginManager pm = com.baafoo.agent.BaafooAgent.getPluginManager();
            if (pm == null) return Collections.emptyMap();
            Map<com.baafoo.plugin.InterceptTarget, com.baafoo.agent.plugin.PluginManager.PluginHealthStatus> allStatuses =
                    pm.getAllHealthStatuses();
            if (allStatuses.isEmpty()) return Collections.emptyMap();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<com.baafoo.plugin.InterceptTarget, com.baafoo.agent.plugin.PluginManager.PluginHealthStatus> entry : allStatuses.entrySet()) {
                result.put(entry.getKey().name(), entry.getValue().toMap());
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to collect plugin statuses for heartbeat: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // --- Request/Response DTOs ---

    public static class AgentRegisterRequest {
        public String agentId;
        public String environment;
        public String hostname;
        public String version;
        public List<String> protocols;
        public String agentIp;

        /**
         * Validate the request fields before sending to the server.
         * @return null if valid, otherwise an error message describing the first problem.
         */
        public String validate() {
            if (agentId == null || agentId.trim().isEmpty()) {
                return "agentId must not be null or empty";
            }
            if (environment == null || environment.trim().isEmpty()) {
                return "environment must not be null or empty";
            }
            if (hostname == null || hostname.trim().isEmpty()) {
                return "hostname must not be null or empty";
            }
            // protocols may be null/empty for agents that don't intercept specific protocols,
            // but if provided must not contain null entries
            if (protocols != null) {
                for (int i = 0; i < protocols.size(); i++) {
                    if (protocols.get(i) == null) {
                        return "protocols[" + i + "] must not be null";
                    }
                }
            }
            return null;
        }
    }

    /**
     * Resolve the agent's non-loopback IPv4 address.
     * Iterates network interfaces to find the first non-loopback IPv4 address.
     * Falls back to InetAddress.getLocalHost() or 127.0.0.1.
     */
    private static String resolveLocalIp() {
        try {
            Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            java.net.InetAddress local = java.net.InetAddress.getLocalHost();
            if (!local.isLoopbackAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception e) {
            // fall through
        }
        return "127.0.0.1";
    }

    public static class AgentRegisterResponse {
        public String agentId;
        public String mode;
        public int pollIntervalSec;
    }

    public static class HeartbeatRequest {
        public String agentId;
        public long timestamp;
        public String agentIp;
        /** P3: Plugin health statuses (target → status map), reported to Server. */
        public Map<String, Object> pluginStatuses;
    }

    public static class PollResponse {
        public List<Rule> rules;
        public String mode;
        public long version;
    }
}
