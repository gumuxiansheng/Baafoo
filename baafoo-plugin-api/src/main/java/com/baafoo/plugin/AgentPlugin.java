package com.baafoo.plugin;

import java.util.Map;

/**
 * SPI interface for Baafoo agent plugins.
 *
 * <p>Implementations handle protocol-specific interception logic
 * and are loaded by a dedicated Plugin ClassLoader (parent=null)
 * to isolate SDK dependencies (e.g., Pulsar, Kafka clients).</p>
 *
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #getName()} / {@link #getTarget()} — identify</li>
 *   <li>{@link #configure(Map)} — inject config</li>
 *   <li>{@link #init()} — initialize</li>
 *   <li>{@link #onConnect(ConnectContext)} — connection-phase hook (Agent only)</li>
 *   <li>{@link #onRequest(RequestContext)} — request-phase hook</li>
 *   <li>{@link #onResponse(ResponseContext)} — response-phase hook</li>
 *   <li>{@link #onEvent(PluginEvent)} — observation-only event hook</li>
 *   <li>{@link #destroy()} — cleanup</li>
 * </ol>
 */
public interface AgentPlugin {

    /**
     * @return unique plugin name
     */
    String getName();

    /**
     * @return the intercept target this plugin handles
     */
    InterceptTarget getTarget();

    /**
     * Called before {@link #init()} with per-plugin configuration from baafoo-agent.yml.
     * <p>The default implementation is a no-op for backward compatibility.</p>
     *
     * @param config plugin-specific configuration map (never null, may be empty)
     */
    default void configure(Map<String, Object> config) {}

    /**
     * Called once after plugin is loaded, instantiated, and configured.
     */
    void init();


    // ---- Phase Hooks (new) ----

    /**
     * Connection-phase hook. Called before the actual connection is established.
     * <p><b>Agent-only:</b> No equivalent in WireMock because WireMock operates
     * at HTTP proxy level, not at Socket.connect().</p>
     *
     * <p>Default implementation returns {@link ConnectAdvice#passthrough()},
     * meaning the connection proceeds to the original target. Plugins that
     * need to redirect or block connections must override this method.</p>
     *
     * @param ctx connection context
     * @return connect advice (passthrough / redirect / block)
     */
    default ConnectAdvice onConnect(ConnectContext ctx) {
        return ConnectAdvice.passthrough();
    }

    /**
     * Request-phase hook. Called after the request is parsed but before
     * rule matching.
     *
     * <p>Default implementation returns {@link RequestAdvice#continue()},
     * meaning the request proceeds to normal rule matching.</p>
     *
     * @param ctx request context
     * @return request advice (continue / shortcut / modify)
     */
    default RequestAdvice onRequest(RequestContext ctx) {
        return RequestAdvice.proceed();
    }

    /**
     * Response-phase hook. Called after the stub response is generated
     * but before it is sent to the client.
     *
     * <p>Default implementation returns {@link ResponseAdvice#continue()},
     * meaning the response is sent as-is.</p>
     *
     * @param ctx response context
     * @return response advice (continue / replace / augment)
     */
    default ResponseAdvice onResponse(ResponseContext ctx) {
        return ResponseAdvice.proceed();
    }

    /**
     * Event hook. Called for lifecycle events that don't participate in
     * the request flow. Implementations must not throw — exceptions are
     * caught and logged by the PluginManager / EventBus.
     *
     * <p>Default implementation is a no-op.</p>
     *
     * @param event plugin event
     */
    default void onEvent(PluginEvent event) {}

    /**
     * Called on JVM shutdown / agent unload.
     */
    void destroy();
}
