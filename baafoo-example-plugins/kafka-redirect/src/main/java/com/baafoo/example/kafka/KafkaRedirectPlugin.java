package com.baafoo.example.kafka;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginEvent;
import com.baafoo.plugin.RequestAdvice;
import com.baafoo.plugin.RequestContext;

import java.util.Map;

/**
 * Example Baafoo plugin: redirects Kafka connections to a custom mock broker.
 *
 * <p>Demonstrates the new phase-specific API hooks:</p>
 * <ul>
 *   <li>{@link #onConnect(ConnectContext)} — connection-phase redirect using
 *       {@link ConnectAdvice#redirect(String, int)}</li>
 *   <li>{@link #onRequest(RequestContext)} — request-phase observation hook</li>
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
 * </pre>
 *
 * <p><b>Architecture note:</b> The topic is not available at
 * {@link ConnectContext} level (Socket.connect time). Topic-based filtering
 * cannot be evaluated at connect time, so this plugin no longer exposes an
 * {@code excludeTopics} option — it was misleading because the connection
 * was already redirected before the topic could be inspected. Use server-side
 * rule matching ({@code enabled=false} / environment scoping) to filter
 * topics instead.</p>
 */
public class KafkaRedirectPlugin implements AgentPlugin {

    private String redirectHost = "localhost";
    private int redirectPort = 9050;

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
    }

    @Override
    public void init() {
        System.out.println("[KafkaRedirectPlugin] Initialized | redirect=" + redirectHost + ":" + redirectPort);
    }

    // ---- New API hooks ----

    /**
     * Connection-phase hook: redirect Kafka connections to the mock broker.
     *
     * <p>Topic is not available at this stage (Socket.connect time), so
     * topic-based filtering is not possible here.</p>
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
     * Request-phase hook: observation only.
     *
     * <p>The connection was already redirected at connect time, so the mock
     * broker handles all requests. This hook returns {@code proceed()} for
     * every topic — use server-side rule matching to filter by topic.</p>
     *
     * @param ctx request context (with topic, partition, key)
     * @return always {@link RequestAdvice#proceed()}
     */
    @Override
    public RequestAdvice onRequest(RequestContext ctx) {
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
        System.out.println("[KafkaRedirectPlugin] Destroyed");
    }

    // ---- Helpers ----

    private static boolean isLocalAddress(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }
}
