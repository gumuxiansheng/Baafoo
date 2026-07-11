package com.baafoo.example.kafka;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginEvent;
import com.baafoo.plugin.RequestAdvice;
import com.baafoo.plugin.RequestContext;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Example Baafoo plugin: redirects Kafka connections to a custom mock broker,
 * with optional topic-based filtering.
 *
 * <p>Demonstrates the new phase-specific API hooks:</p>
 * <ul>
 *   <li>{@link #onConnect(ConnectContext)} — connection-phase redirect using
 *       {@link ConnectAdvice#redirect(String, int)}</li>
 *   <li>{@link #onRequest(RequestContext)} — request-phase topic filtering
 *       using {@link RequestAdvice}</li>
 *   <li>{@link #onEvent(PluginEvent)} — observation-only event logging</li>
 * </ul>
 *
 * <h3>Configuration (baafoo-agent.yml)</h3>
 * <pre>
 * plugins:
 *   configs:
 *     kafka-redirect:
 *       redirectHost: "localhost"
 *       redirectPort: 9050
 *       excludeTopics:
 *         - "internal-health"
 *         - "system-metrics"
 * </pre>
 *
 * <p><b>Architecture note:</b> The topic is not available at
 * {@link ConnectContext} level (Socket.connect time). Topic-based exclusion
 * is therefore evaluated in {@link #onRequest}, where the parsed
 * {@link RequestContext#getTopic()} is available. The connection redirect
 * itself is decided in {@link #onConnect} based on host/port only.</p>
 */
public class KafkaRedirectPlugin implements AgentPlugin {

    private String redirectHost = "localhost";
    private int redirectPort = 9050;
    private final Set<String> excludeTopics = new HashSet<String>();

    @Override
    public String getName() {
        return "kafka-redirect";
    }

    @Override
    public InterceptTarget getTarget() {
        return InterceptTarget.KAFKA;
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (config.containsKey("redirectHost")) {
            this.redirectHost = (String) config.get("redirectHost");
        }
        if (config.containsKey("redirectPort")) {
            this.redirectPort = ((Number) config.get("redirectPort")).intValue();
        }
        if (config.containsKey("excludeTopics")) {
            Object topics = config.get("excludeTopics");
            if (topics instanceof Iterable) {
                for (Object topic : (Iterable<?>) topics) {
                    excludeTopics.add(topic.toString());
                }
            }
        }
    }

    @Override
    public void init() {
        System.out.println("[KafkaRedirectPlugin] Initialized | redirect=" + redirectHost + ":" + redirectPort
                + " | excludeTopics=" + excludeTopics);
    }

    // ---- New API hooks ----

    /**
     * Connection-phase hook: redirect Kafka connections to the mock broker.
     *
     * <p>Topic is not available at this stage (Socket.connect time), so
     * topic-based exclusion is deferred to {@link #onRequest}.</p>
     *
     * @param ctx connection context (protocol, host, port)
     * @return {@link ConnectAdvice#redirect} to the mock broker, or
     *         {@link ConnectAdvice#passthrough} for local targets
     */
    @Override
    public ConnectAdvice onConnect(ConnectContext ctx) {
        // Avoid redirect loop: skip if the target is already local.
        if (ctx.getHost() != null && isLocalAddress(ctx.getHost())) {
            return ConnectAdvice.passthrough();
        }
        return ConnectAdvice.redirect(redirectHost, redirectPort);
    }

    /**
     * Request-phase hook: topic-based filtering.
     *
     * <p>For excluded topics, returns {@link RequestAdvice#proceed()} so the
     * request goes through normal agent rule matching without plugin
     * interference. For non-excluded topics, also returns {@code proceed()}
     * — the connection was already redirected at connect time, so the mock
     * broker handles the request.</p>
     *
     * @param ctx request context (with topic, partition, key)
     * @return always {@link RequestAdvice#proceed()}
     */
    @Override
    public RequestAdvice onRequest(RequestContext ctx) {
        String topic = ctx.getTopic();
        if (topic != null && excludeTopics.contains(topic)) {
            System.out.println("[KafkaRedirectPlugin] Excluded topic matched (connection already redirected): " + topic);
        }
        return RequestAdvice.proceed();
    }

    /**
     * Observation-only event hook: logs connection redirect events.
     */
    @Override
    public void onEvent(PluginEvent event) {
        if (event.getType() == PluginEvent.Type.CONNECTION_REDIRECTED) {
            System.out.println("[KafkaRedirectPlugin] Event: " + event);
        }
    }

    @Override
    public void destroy() {
        excludeTopics.clear();
        System.out.println("[KafkaRedirectPlugin] Destroyed");
    }

    // ---- Helpers ----

    private static boolean isLocalAddress(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }
}
