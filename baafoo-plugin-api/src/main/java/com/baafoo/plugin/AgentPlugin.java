package com.baafoo.plugin;

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
     * Called once after plugin is loaded and instantiated.
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
