package com.baafoo.agent.plugin;

import com.baafoo.agent.loader.PluginClassLoader;
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
 * <p>NOTE: The plugin system is implemented but not yet activated in the
 * current agent bootstrap flow. It is reserved for future extensibility
 * when third-party protocol interceptors are needed beyond the built-in
 * set (Socket, NIO, Kafka, Pulsar, Consul).</p>
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    /** Loaded plugins (target → plugin instance) */
    private final Map<InterceptTarget, AgentPlugin> plugins = new ConcurrentHashMap<InterceptTarget, AgentPlugin>();

    /** Default plugins directory */
    private static final String DEFAULT_PLUGIN_DIR = "./plugins";

    /**
     * Default constructor - loads plugins from default directory.
     */
    public PluginManager() {
        this(DEFAULT_PLUGIN_DIR);
    }

    /**
     * Load plugins from specified directory.
     */
    public PluginManager(String pluginDir) {
        loadPlugins(pluginDir);
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

    private InterceptTarget resolveTarget(String protocol) {
        if (protocol == null) return null;
        switch (protocol.toLowerCase()) {
            case "http":
            case "tcp":
                return InterceptTarget.SOCKET;
            case "kafka":
                return InterceptTarget.KAFKA;
            case "pulsar":
                return InterceptTarget.PULSAR;
            case "jms":
                return InterceptTarget.JMS;
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
            plugin.init();
            plugins.put(plugin.getTarget(), plugin);
            log.info("Plugin loaded: {} (target={})", plugin.getName(), plugin.getTarget());
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
