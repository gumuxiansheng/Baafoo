package com.baafoo.agent.plugin;

import com.baafoo.agent.loader.PluginClassLoader;
import com.baafoo.core.config.AgentConfig;
import com.baafoo.core.event.EventBus;
import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginEvent;
import com.baafoo.plugin.PluginHealth;
import com.baafoo.plugin.RequestAdvice;
import com.baafoo.plugin.RequestContext;
import com.baafoo.plugin.ResponseAdvice;
import com.baafoo.plugin.ResponseContext;
import com.baafoo.plugin.service.PluginServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of Baafoo agent plugins via the AgentPlugin SPI.
 *
 * <p>The plugin system is activated in the agent bootstrap flow. All protocol
 * Advice classes (Socket, NIO, Kafka, Pulsar, JMS) consult the PluginManager
 * before falling back to the built-in routing logic. A plugin may return a
 * {@link com.baafoo.plugin.ConnectAdvice#redirect} to override the default
 * stub target.</p>
 *
 * <p>P3: Includes health monitoring (success/error tracking, auto-disable on
 * consecutive failures) and runtime enable/disable via REST API.</p>
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    /** Consecutive error threshold before auto-disabling a plugin. */
    private static final int UNHEALTHY_THRESHOLD = 5;

    /** Loaded plugins (target -> plugin instance) */
    private final Map<InterceptTarget, AgentPlugin> plugins = new ConcurrentHashMap<InterceptTarget, AgentPlugin>();

    /** Per-plugin configuration from baafoo-agent.yml (pluginName -> config map) */
    private final Map<String, Map<String, Object>> pluginConfigs;

    /** P3: Health status per plugin target */
    private final Map<InterceptTarget, PluginHealthStatus> healthStatuses =
            new ConcurrentHashMap<InterceptTarget, PluginHealthStatus>();

    /** P3: Manually disabled plugins */
    private final Set<InterceptTarget> disabledPlugins =
            Collections.newSetFromMap(new ConcurrentHashMap<InterceptTarget, Boolean>());

    /** P2: Event bus for broadcasting PluginEvents */
    private final EventBus eventBus = new EventBus();

    /** P1: Injected services (null in Agent-only mode) */
    private PluginServices services;

    /** Default plugins directory */
    private static final String DEFAULT_PLUGIN_DIR = "./plugins";

    /**
     * Default constructor - loads plugins from default directory, no per-plugin config.
     */
    public PluginManager() {
        this(DEFAULT_PLUGIN_DIR, null);
    }

    /**
     * Load plugins from specified directory, no per-plugin config.
     */
    public PluginManager(String pluginDir) {
        this(pluginDir, null);
    }

    /**
     * Load plugins with PluginsConfig (from AgentConfig.getPlugins()).
     *
     * @param pluginsConfig plugin system configuration, or null for defaults
     */
    public PluginManager(AgentConfig.PluginsConfig pluginsConfig) {
        this(pluginsConfig != null ? pluginsConfig.getDirectory() : DEFAULT_PLUGIN_DIR,
             pluginsConfig);
    }

    /**
     * Internal constructor: loads plugins from directory with optional config.
     */
    private PluginManager(String pluginDir, AgentConfig.PluginsConfig pluginsConfig) {
        this.pluginConfigs = (pluginsConfig != null && pluginsConfig.getConfigs() != null)
                ? pluginsConfig.getConfigs()
                : Collections.<String, Map<String, Object>>emptyMap();
        if (pluginsConfig == null || pluginsConfig.isEnabled()) {
            loadPlugins(pluginDir);
        } else {
            log.info("Plugin system disabled by configuration, no plugins loaded");
        }
    }

    // ==================== Plugin Lookup ====================

    /**
     * Get plugin for a specific intercept target.
     * Returns null if plugin is disabled, unhealthy, or not loaded.
     *
     * @param target intercept target
     * @return plugin instance, or null if not available
     */
    public AgentPlugin getPlugin(InterceptTarget target) {
        if (disabledPlugins.contains(target)) return null;
        PluginHealthStatus status = healthStatuses.get(target);
        if (status != null && status.health == PluginHealth.UNHEALTHY) return null;
        return plugins.get(target);
    }

    /**
     * Get plugin for a protocol name.
     */
    public AgentPlugin getPluginForProtocol(String protocol) {
        InterceptTarget target = resolveTarget(protocol);
        return target != null ? getPlugin(target) : null;
    }

    // ==================== P3: Enable / Disable ====================

    /**
     * Manually disable a plugin. Disabled plugins are excluded from
     * getPlugin() results (equivalent to no plugin, falls back to default).
     */
    public void disablePlugin(InterceptTarget target) {
        if (plugins.containsKey(target)) {
            disabledPlugins.add(target);
            PluginHealthStatus status = healthStatuses.get(target);
            if (status != null) status.health = PluginHealth.DISABLED;
            log.info("[Baafoo] Plugin disabled: target={}", target);
        }
    }

    /**
     * Re-enable a manually disabled plugin.
     */
    public void enablePlugin(InterceptTarget target) {
        if (disabledPlugins.remove(target)) {
            PluginHealthStatus status = healthStatuses.get(target);
            if (status != null) status.health = PluginHealth.UNKNOWN;
            log.info("[Baafoo] Plugin re-enabled: target={}", target);
        }
    }

    /**
     * Check whether a plugin target is manually disabled.
     */
    public boolean isDisabled(InterceptTarget target) {
        return disabledPlugins.contains(target);
    }

    // ==================== P3: Health Status ====================

    /**
     * Get health status for a specific plugin target.
     *
     * @param target intercept target
     * @return health status, or null if no plugin registered for target
     */
    public PluginHealthStatus getHealthStatus(InterceptTarget target) {
        return healthStatuses.get(target);
    }

    /**
     * Get health status for all loaded plugins.
     *
     * @return unmodifiable map of target -> health status
     */
    public Map<InterceptTarget, PluginHealthStatus> getAllHealthStatuses() {
        return Collections.unmodifiableMap(healthStatuses);
    }

    // ==================== Config ====================

    /**
     * Get per-plugin configuration for a plugin name.
     *
     * @param pluginName plugin name (from {@code AgentPlugin.getName()})
     * @return config map, or empty map if not configured
     */
    public Map<String, Object> getPluginConfig(String pluginName) {
        if (pluginName == null) return Collections.emptyMap();
        Map<String, Object> config = pluginConfigs.get(pluginName);
        return config != null ? config : Collections.<String, Object>emptyMap();
    }

    /**
     * Get per-plugin configuration for a plugin registered to the given target.
     *
     * @param target intercept target
     * @return config map, or empty map if no plugin or no config
     */
    public Map<String, Object> getPluginConfig(InterceptTarget target) {
        AgentPlugin plugin = plugins.get(target);
        if (plugin == null) return Collections.emptyMap();
        return getPluginConfig(plugin.getName());
    }

    // ==================== P1: Phase Hooks ====================

    /**
     * Connection-phase hook with health monitoring.
     * Calls plugin.onConnect() and tracks success/error.
     *
     * @param target intercept target
     * @param ctx connection context
     * @return connect advice, or passthrough if plugin unavailable
     */
    public ConnectAdvice connectWithMonitor(InterceptTarget target, ConnectContext ctx) {
        AgentPlugin plugin = getPlugin(target);
        if (plugin == null) return ConnectAdvice.passthrough();

        PluginHealthStatus status = healthStatuses.get(target);
        long start = System.currentTimeMillis();
        try {
            ConnectAdvice advice = plugin.onConnect(ctx);
            long elapsed = System.currentTimeMillis() - start;
            if (status != null) status.recordSuccess(elapsed);
            return advice;
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            if (status != null) status.recordError(elapsed, t.getMessage());
            log.warn("[Baafoo] Plugin {} onConnect failed: {}", plugin.getName(), t.getMessage());
            return ConnectAdvice.passthrough();
        }
    }

    /**
     * Request-phase hook with health monitoring.
     *
     * @param target intercept target
     * @param ctx request context
     * @return request advice, or continue() if plugin unavailable
     */
    public RequestAdvice requestWithMonitor(InterceptTarget target, RequestContext ctx) {
        AgentPlugin plugin = getPlugin(target);
        if (plugin == null) return RequestAdvice.proceed();

        PluginHealthStatus status = healthStatuses.get(target);
        long start = System.currentTimeMillis();
        try {
            RequestAdvice advice = plugin.onRequest(ctx);
            long elapsed = System.currentTimeMillis() - start;
            if (status != null) status.recordSuccess(elapsed);
            return advice;
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            if (status != null) status.recordError(elapsed, t.getMessage());
            log.warn("[Baafoo] Plugin {} onRequest failed: {}", plugin.getName(), t.getMessage());
            return RequestAdvice.proceed();
        }
    }

    /**
     * Response-phase hook with health monitoring.
     *
     * @param target intercept target
     * @param ctx response context
     * @return response advice, or continue() if plugin unavailable
     */
    public ResponseAdvice responseWithMonitor(InterceptTarget target, ResponseContext ctx) {
        AgentPlugin plugin = getPlugin(target);
        if (plugin == null) return ResponseAdvice.proceed();

        PluginHealthStatus status = healthStatuses.get(target);
        long start = System.currentTimeMillis();
        try {
            ResponseAdvice advice = plugin.onResponse(ctx);
            long elapsed = System.currentTimeMillis() - start;
            if (status != null) status.recordSuccess(elapsed);
            return advice;
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            if (status != null) status.recordError(elapsed, t.getMessage());
            log.warn("[Baafoo] Plugin {} onResponse failed: {}", plugin.getName(), t.getMessage());
            return ResponseAdvice.proceed();
        }
    }

    // ==================== P1: Service Injection ====================

    /**
     * Inject PluginServices into this manager.
     * When set, all plugin contexts created via this manager will
     * have services attached.
     *
     * @param services service instance, or null to clear
     */
    public void setServices(PluginServices services) {
        this.services = services;
    }

    public PluginServices getServices() {
        return services;
    }

    // ==================== P2: Event Bus ====================

    /**
     * Get the event bus for registering external listeners (Metrics, Audit, etc.).
     *
     * @return the event bus instance
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * Fire an event to the EventBus.
     *
     * <p>Plugins receive events exclusively via the EventBus — they are
     * auto-registered as listeners in {@link #loadPlugin(File)} via
     * {@code eventBus.addListener(plugin::onEvent)}. Do NOT iterate plugins
     * and call {@code onEvent} directly here; that would double-deliver
     * every event (P2-2 fix).</p>
     *
     * @param event the event to fire
     */
    public void fireEvent(PluginEvent event) {
        eventBus.fire(event);
    }

    // ==================== Protocol Resolution ====================

    private InterceptTarget resolveTarget(String protocol) {
        if (protocol == null) return null;
        switch (protocol.toLowerCase()) {
            case "http":
            case "tcp":
            case "socket":
                return InterceptTarget.SOCKET;
            case "nio-socket":
            case "nio":
                return InterceptTarget.NIO_SOCKET;
            case "kafka":
                return InterceptTarget.KAFKA;
            case "pulsar":
                return InterceptTarget.PULSAR;
            case "jms":
                return InterceptTarget.JMS;
            case "grpc":
                return InterceptTarget.GRPC;
            case "consul-dns":
                return InterceptTarget.CONSUL_DNS;
            case "consul-api":
            case "consul":
                return InterceptTarget.CONSUL_API;
            case "feign":
                return InterceptTarget.FEIGN;
            default:
                return null;
        }
    }

    // ==================== Loading ====================

    /**
     * Load all plugins from the plugin directory.
     * Uses a 7-step loading process per plugin-arch-advice.md.
     */
    private void loadPlugins(String pluginDir) {
        File dir = new File(pluginDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.info("Plugin directory not found: {}, no plugins loaded", pluginDir);
            return;
        }

        File[] jarFiles = dir.listFiles(new java.io.FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });

        if (jarFiles == null || jarFiles.length == 0) {
            log.info("No plugin JARs found in {}", pluginDir);
            return;
        }

        for (File jar : jarFiles) {
            try {
                loadPlugin(jar);
            } catch (Exception e) {
                log.error("Failed to load plugin from {}: {}", jar.getName(), e.getMessage());
            }
        }

        log.info("Loaded {} plugins", plugins.size());
    }

    private void loadPlugin(File jarFile) throws Exception {
        List<URL> urls = new ArrayList<URL>();
        urls.add(jarFile.toURI().toURL());

        PluginClassLoader classLoader = new PluginClassLoader(urls.toArray(new URL[0]));

        // Use ServiceLoader to discover AgentPlugin implementations
        ServiceLoader<AgentPlugin> loader = ServiceLoader.load(AgentPlugin.class, classLoader);
        for (AgentPlugin plugin : loader) {
            // Inject per-plugin config before init (P1: plugin-level configuration)
            Map<String, Object> config = getPluginConfig(plugin.getName());
            plugin.configure(config);
            plugin.init();
            plugins.put(plugin.getTarget(), plugin);
            // P3: Initialize health status
            healthStatuses.put(plugin.getTarget(), new PluginHealthStatus(plugin.getName()));
            // P2: Auto-register plugin as event listener
            eventBus.addListener(plugin::onEvent);
            // P2: Fire PLUGIN_LOADED event
            eventBus.fire(PluginEvent.pluginLoaded(plugin.getName(), plugin.getTarget().name()));
            log.info("Plugin loaded: {} (target={}, config={})", plugin.getName(), plugin.getTarget(),
                    config.isEmpty() ? "none" : config.keySet());
        }
    }

    // ==================== Shutdown ====================

    /**
     * Shutdown all plugins.
     */
    public void shutdown() {
        for (AgentPlugin plugin : plugins.values()) {
            try {
                plugin.destroy();
                eventBus.fire(PluginEvent.pluginUnloaded(plugin.getName()));
            } catch (Exception e) {
                log.error("Error destroying plugin {}: {}", plugin.getName(), e.getMessage());
            }
        }
        plugins.clear();
        healthStatuses.clear();
        disabledPlugins.clear();
        log.info("All plugins shut down");
    }

    // ==================== P3: Health Status Model ====================

    /**
     * Per-plugin health metrics and status.
     * Thread-safe: all mutating methods use synchronized blocks.
     */
    public static class PluginHealthStatus {
        private final String pluginName;
        private final long loadedAt;

        volatile PluginHealth health = PluginHealth.UNKNOWN;
        private long successCount;
        private long errorCount;
        private long totalLatencyMs;
        private int consecutiveErrors;
        private String lastError;
        private long lastErrorTime;
        private long lastSuccessTime;

        public PluginHealthStatus(String pluginName) {
            this.pluginName = pluginName;
            this.loadedAt = System.currentTimeMillis();
        }

        synchronized void recordSuccess(long latencyMs) {
            successCount++;
            totalLatencyMs += latencyMs;
            consecutiveErrors = 0;
            lastSuccessTime = System.currentTimeMillis();
            if (health != PluginHealth.DISABLED) {
                health = PluginHealth.HEALTHY;
            }
        }

        synchronized void recordError(long latencyMs, String errorMsg) {
            errorCount++;
            totalLatencyMs += latencyMs;
            consecutiveErrors++;
            lastError = errorMsg;
            lastErrorTime = System.currentTimeMillis();
            if (health != PluginHealth.DISABLED) {
                if (consecutiveErrors >= UNHEALTHY_THRESHOLD) {
                    health = PluginHealth.UNHEALTHY;
                    log.warn("[Baafoo] Plugin {} auto-disabled after {} consecutive errors",
                            pluginName, consecutiveErrors);
                } else if (consecutiveErrors > 0) {
                    health = PluginHealth.DEGRADED;
                }
            }
        }

        // --- Getters ---

        public String getPluginName() { return pluginName; }
        public long getLoadedAt() { return loadedAt; }
        public PluginHealth getHealth() { return health; }
        public synchronized long getSuccessCount() { return successCount; }
        public synchronized long getErrorCount() { return errorCount; }
        public synchronized long getTotalLatencyMs() { return totalLatencyMs; }
        public synchronized int getConsecutiveErrors() { return consecutiveErrors; }
        public synchronized String getLastError() { return lastError; }
        public synchronized long getLastErrorTime() { return lastErrorTime; }
        public synchronized long getLastSuccessTime() { return lastSuccessTime; }

        public synchronized long getAvgLatencyMs() {
            long total = successCount + errorCount;
            return total > 0 ? totalLatencyMs / total : 0;
        }

        /**
         * Convert to a serializable map for heartbeat / REST API.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", pluginName);
            m.put("health", health.name());
            m.put("loadedAt", loadedAt);
            m.put("successCount", getSuccessCount());
            m.put("errorCount", getErrorCount());
            m.put("avgLatencyMs", getAvgLatencyMs());
            m.put("consecutiveErrors", getConsecutiveErrors());
            if (lastError != null) m.put("lastError", lastError);
            if (lastErrorTime > 0) m.put("lastErrorTime", lastErrorTime);
            if (lastSuccessTime > 0) m.put("lastSuccessTime", lastSuccessTime);
            return m;
        }

        @Override
        public String toString() {
            return "PluginHealthStatus{name='" + pluginName + "', health=" + health +
                    ", success=" + getSuccessCount() + ", error=" + getErrorCount() +
                    ", avgLatency=" + getAvgLatencyMs() + "ms}";
        }
    }
}
