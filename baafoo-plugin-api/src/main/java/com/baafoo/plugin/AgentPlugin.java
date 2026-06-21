package com.baafoo.plugin;

import java.util.Map;

/**
 * SPI interface for Baafoo agent plugins.
 *
 * <p>Implementations handle protocol-specific interception logic
 * and are loaded by a dedicated Plugin ClassLoader (parent=null)
 * to isolate SDK dependencies (e.g., Pulsar, Kafka clients).</p>
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #getName()} and {@link #getTarget()} are called to identify the plugin</li>
 *   <li>{@link #configure(Map)} is called with per-plugin config from baafoo-agent.yml (may be empty)</li>
 *   <li>{@link #init()} is called after loading</li>
 *   <li>{@link #intercept(PluginContext)} is called for each intercepted call</li>
 *   <li>{@link #destroy()} is called on shutdown</li>
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
     */
    InterceptResult intercept(PluginContext ctx);

    /**
     * Called on JVM shutdown / agent unload.
     */
    void destroy();
}
