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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private final AtomicBoolean running;
    private java.util.function.Consumer<String> agentIdCallback;

    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> pollTask;

    public ControlChannel(AgentConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.scheduler = Executors.newScheduledThreadPool(2, new java.util.concurrent.ThreadFactory() {
            private int count = 0;
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "baafoo-channel-" + (++count));
                t.setDaemon(true);
                return t;
            }
        });
        this.running = new AtomicBoolean(false);
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
     * Start the control channel: register, then start heartbeat + poll loops.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        // 1. Register with server
        boolean registered = register();
        if (!registered) {
            log.warn("Failed to register with server at {}, will retry", config.getServerUrl());
        }

        // 2. Start heartbeat
        heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeat, 0, config.getHeartbeatIntervalSec(), TimeUnit.SECONDS);

        pollTask = scheduler.scheduleAtFixedRate(this::pollRules, 0, config.getPollIntervalSec(), TimeUnit.SECONDS);

        log.info("Control channel started (heartbeat={}s, poll={}s)",
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
    }

    // --- Control API calls ---

    private boolean register() {
        try {
            AgentRegisterRequest req = new AgentRegisterRequest();
            req.agentId = config.getAgentId();
            req.environment = config.getEnvironment();
            req.hostname = java.net.InetAddress.getLocalHost().getHostName();
            req.version = "1.0.0";
            req.protocols = config.getProtocols();

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
        try {
            HeartbeatRequest req = new HeartbeatRequest();
            req.agentId = config.getAgentId();
            req.timestamp = System.currentTimeMillis();

            String json = mapper.writeValueAsString(req);
            HttpURLConnection conn = post(API_BASE + "/agent/heartbeat", json);

            if (conn.getResponseCode() != 200) {
                log.warn("Heartbeat failed: HTTP {}", conn.getResponseCode());
            }
        } catch (Exception e) {
            log.warn("Heartbeat error: {}", e.getMessage());
        }
    }

    private void pollRules() {
        try {
            String url = config.getServerUrl() + API_BASE + "/agent/poll?agentId=" +
                    (config.getAgentId() != null ? config.getAgentId() : "");

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(config.getPollIntervalSec() * 1000 + 1000);

            int code = conn.getResponseCode();
            if (code == 200) {
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
                // No changes
            } else {
                log.warn("Poll failed: HTTP {}", code);
            }
        } catch (java.net.SocketTimeoutException e) {
            // Long-poll timeout is expected, retry
        } catch (Exception e) {
            log.warn("Poll error: {}", e.getMessage());
        }
    }

    /**
     * Upload recorded data to server.
     */
    public void uploadRecordings(List<RecordingEntry> recordings) {
        try {
            String json = mapper.writeValueAsString(recordings);
            HttpURLConnection conn = post(API_BASE + "/agent/recordings?agentId=" + config.getAgentId(), json);
            int code = conn.getResponseCode();
            if (code == 200) {
                log.info("Uploaded {} recordings", recordings.size());
            } else {
                log.warn("Recording upload failed: HTTP {}", code);
            }
        } catch (Exception e) {
            log.warn("Recording upload error: {}", e.getMessage());
        }
    }

    // --- HTTP helpers (JDK HttpURLConnection only, NO Netty) ---

    private HttpURLConnection post(String path, String json) throws Exception {
        String url = config.getServerUrl() + path;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);

        OutputStream os = conn.getOutputStream();
        os.write(json.getBytes("UTF-8"));
        os.flush();
        os.close();

        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        java.io.InputStream is = conn.getResponseCode() >= 400
                ? conn.getErrorStream()
                : conn.getInputStream();

        if (is == null) return "";

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        is.close();
        return baos.toString("UTF-8");
    }

    // --- Request/Response DTOs ---

    public static class AgentRegisterRequest {
        public String agentId;
        public String environment;
        public String hostname;
        public String version;
        public List<String> protocols;
    }

    public static class AgentRegisterResponse {
        public String agentId;
        public String mode;
        public int pollIntervalSec;
    }

    public static class HeartbeatRequest {
        public String agentId;
        public long timestamp;
    }

    public static class PollResponse {
        public List<Rule> rules;
        public String mode;
        public long version;
    }
}
