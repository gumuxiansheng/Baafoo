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
 *   <li>{@link #intercept(PluginContext)} — legacy unified hook (deprecated)</li>
 *   <li>{@link #destroy()} — cleanup</li>
 * </ol>
 *
 * <p><b>Backward compatibility:</b> Plugins that only implement {@link #intercept}
 * continue to work unchanged. The new hooks are {@code default} methods that
 * delegate to {@code intercept()} for legacy plugins.</p>
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
     * <p>Default implementation delegates to {@link #intercept} for backward
     * compatibility. New plugins should override this instead of intercept().</p>
     *
     * @param ctx connection context
     * @return connect advice (passthrough / redirect / block)
     */
    default ConnectAdvice onConnect(ConnectContext ctx) {
        PluginContext legacy = ctx.toLegacyContext();
        InterceptResult result = intercept(legacy);
        return ConnectAdvice.fromInterceptResult(result);
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

    // ---- Legacy (deprecated) ----

    /**
     * Process an intercepted call.
     * <ul>
     *   <li>In <b>stub</b> mode: return a mocked InterceptResult</li>
     *   <li>In <b>passthrough</b> mode: execute originalCall and return result</li>
     *   <li>In <b>record</b> mode: execute originalCall, store result, return real result</li>
     *   <li>In <b>record-and-stub</b> mode: execute originalCall, store, return stubbed</li>
     * </ul>
     *
     * @param ctx interception context
     * @return interception result
     * @deprecated Use {@link #onConnect}, {@link #onRequest}, or
     *             {@link #onResponse} instead. This method is retained
     *             for backward compatibility and is called by the default
     *             implementations of the new hooks.
     */
    @Deprecated
    InterceptResult intercept(PluginContext ctx);

    /**
     * Called on JVM shutdown / agent unload.
     */
    void destroy();
}
