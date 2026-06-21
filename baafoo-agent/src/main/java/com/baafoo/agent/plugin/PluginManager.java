package com.baafoo.agent.plugin;

import com.baafoo.agent.loader.PluginClassLoader;
import com.baafoo.core.config.AgentConfig;
import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptTarget;
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
 * before falling back to the built-in routing logic. A plugin may return an
 * {@link com.baafoo.plugin.InterceptResult#redirect} to override the default
 * stub target.</p>
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    /** Loaded plugins (target → plugin instance) */
    private final Map<InterceptTarget, AgentPlugin> plugins = new ConcurrentHashMap<InterceptTarget, AgentPlugin>();

    /** Per-plugin configuration from baafoo-agent.yml (pluginName → config map) */
    private final Map<String, Map<String, Object>> pluginConfigs;

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

    /**
     * Get plugin for a specific intercept target.
     *
     * @param target intercept target
     * @return plugin instance, or null if not found
     */
    public AgentPlugin getPlugin(InterceptTarget target) {
        return plugins.get(target);
    }

    /**
     * Get plugin for a protocol name.
     */
    public AgentPlugin getPluginForProtocol(String protocol) {
        InterceptTarget target = resolveTarget(protocol);
        return target != null ? plugins.get(target) : null;
    }

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
            log.info("Plugin loaded: {} (target={}, config={})", plugin.getName(), plugin.getTarget(),
                    config.isEmpty() ? "none" : config.keySet());
        }
    }

    /**
     * Shutdown all plugins.
     */
    public void shutdown() {
        for (AgentPlugin plugin : plugins.values()) {
            try {
                plugin.destroy();
            } catch (Exception e) {
                log.error("Error destroying plugin {}: {}", plugin.getName(), e.getMessage());
            }
        }
        plugins.clear();
        log.info("All plugins shut down");
    }
}
