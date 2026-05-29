package com.baafoo.agent;

import com.baafoo.agent.advice.*;
import com.baafoo.agent.channel.ControlChannel;
import com.baafoo.agent.loader.PluginClassLoader;
import com.baafoo.agent.plugin.PluginManager;
import com.baafoo.agent.transform.TransformRegistry;
import com.baafoo.core.config.AgentConfig;
import com.baafoo.core.config.ConfigLoader;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Baafoo JavaAgent entry point.
 *
 * <p>Loaded via -javaagent:baafoo-agent.jar.
 * Injects bytecode into key JDK and library classes to intercept
 * network calls and redirect them to the Baafoo stub server.</p>
 *
 * <p>Fail-closed design: If agent fails to load, errors are logged
 * and all requests pass through to real downstreams untouched.</p>
 */
public class BaafooAgent {

    private static final Logger log = LoggerFactory.getLogger(BaafooAgent.class);

    /** Global agent config */
    private static volatile AgentConfig config;

    /** Control channel to Baafoo server */
    private static volatile ControlChannel controlChannel;

    /** Plugin manager */
    private static volatile PluginManager pluginManager;

    /** Whether agent started successfully */
    private static volatile boolean initialized = false;

    /**
     * JVM premain entry point.
     *
     * <p>Agent argument format: {@code config=/path/to/baafoo-agent.yml}
     * Multiple arguments may be comma-separated.</p>
     *
     * @param agentArgs agent arguments (e.g., "config=./baafoo-agent.yml")
     * @param inst      instrumentation instance
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            log.info("=== Baafoo Agent {} starting ===", getVersion());
            log.info("Agent args: {}", agentArgs);

            // 0. CRITICAL: Add agent jar to Bootstrap ClassLoader search path.
            // Advice code is inlined into JDK classes (Socket, InetAddress, SocketChannelImpl)
            // which are loaded by the Bootstrap ClassLoader. Without this, the inlined code
            // cannot find AgentManifest, RouteTable, or any com.baafoo.* class.
            appendToBootstrapClassLoaderSearch(inst);
            log.info("Agent jar added to Bootstrap ClassLoader search path");

            // 1. Load config (supports config=/path format)
            String configPath = resolveConfigPath(agentArgs);
            config = ConfigLoader.loadAgentConfig(configPath);
            log.info("Config loaded: {}", config);

            // 2. Initialize AgentManifest (Bootstrap-safe state for Advice classes)
            initAgentManifest(config);
            log.info("AgentManifest initialized: serverHost={}, serverPort={}, envId={}, mode={}",
                    AgentManifest.serverHost, AgentManifest.serverPort,
                    AgentManifest.environmentId, AgentManifest.getModeName());

            // 3. Init control channel (JDK HttpURLConnection only, no Netty)
            controlChannel = new ControlChannel(config);
            controlChannel.start();

            // 4. Init plugin manager
            pluginManager = new PluginManager();

            // 5. Register bytecode transforms
            TransformRegistry registry = new TransformRegistry();
            AgentBuilder agentBuilder = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly());

            // --- Socket interception ---
            agentBuilder = agentBuilder
                    .type(named("java.net.Socket"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(SocketConnectAdvice.class)
                                    .on(named("connect")
                                            .and(takesArguments(1)))));
            registry.register("java.net.Socket", "SocketConnectAdvice", "tcp");

            // --- NIO SocketChannel interception ---
            agentBuilder = agentBuilder
                    .type(named("sun.nio.ch.SocketChannelImpl"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(NioSocketConnectAdvice.class)
                                    .on(named("connect").and(takesArguments(1)))));
            registry.register("sun.nio.ch.SocketChannelImpl", "NioSocketConnectAdvice", "tcp");

            // --- Consul DNS interception ---
            if (config.isConsulEnabled()) {
                // Intercept both getByName and getAllByName
                agentBuilder = agentBuilder
                        .type(named("java.net.InetAddress"))
                        .transform((builder, typeDesc, classLoader, module, pd) ->
                                builder.visit(Advice.to(ConsulDnsAdvice.class)
                                        .on(named("getByName").and(takesArguments(1)))))
                        .type(named("java.net.InetAddress"))
                        .transform((builder, typeDesc, classLoader, module, pd) ->
                                builder.visit(Advice.to(ConsulDnsAdvice.class)
                                        .on(named("getAllByName").and(takesArguments(1)))));
                registry.register("java.net.InetAddress", "ConsulDnsAdvice", "consul-dns");

                // --- Consul HTTP interception ---
                agentBuilder = agentBuilder
                        .type(named("sun.net.www.http.HttpClient"))
                        .transform((builder, typeDesc, classLoader, module, pd) ->
                                builder.visit(Advice.to(ConsulHttpAdvice.class)
                                        .on(named("openServer").and(takesArguments(2)))));
                registry.register("sun.net.www.http.HttpClient", "ConsulHttpAdvice", "http");
            }

            // --- Kafka interception (Beta) ---
            // Intercept KafkaProducer constructor to replace bootstrap.servers
            // KafkaProducer has constructors: (Properties), (Map), (Properties, Callback), etc.
            // We intercept all constructors and check for Properties/Map as first argument
            agentBuilder = agentBuilder
                    .type(named("org.apache.kafka.clients.producer.KafkaProducer"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(KafkaProducerAdvice.class)
                                    .on(isConstructor())));
            registry.register("org.apache.kafka.clients.producer.KafkaProducer", "KafkaProducerAdvice", "kafka");

            // --- Pulsar interception (Beta) ---
            // Intercept ClientBuilder.serviceUrl() to replace broker URL
            agentBuilder = agentBuilder
                    .type(named("org.apache.pulsar.client.api.ClientBuilder"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(PulsarClientAdvice.class)
                                    .on(named("serviceUrl").and(takesArguments(1)))));
            registry.register("org.apache.pulsar.client.api.ClientBuilder", "PulsarClientAdvice", "pulsar");

            // 6. Install agent
            agentBuilder.installOn(inst);
            AgentManifest.agentLoaded = true;
            initialized = true;
            log.info("Bytecode transforms installed: {} transforms registered", registry.getCount());

            // 7. Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    shutdown();
                }
            }, "baafoo-shutdown"));

            log.info("=== Baafoo Agent started successfully ===");

        } catch (Throwable e) {
            // Fail-closed: log ERROR, do NOT crash the JVM.
            // All requests will pass through (no interception).
            log.error("FAILED to start Baafoo Agent (fail-closed). " +
                    "All requests will pass through to real downstreams. Error: {}", e.getMessage(), e);
            initialized = false;
        }
    }

    /**
     * Parse agent arguments in {@code config=/path/to/file} format.
     *
     * <p>Supports formats:
     * <ul>
     *   <li>{@code config=/path/to/baafoo-agent.yml}</li>
     *   <li>{@code /path/to/baafoo-agent.yml} (legacy — entire string is path)</li>
     *   <li>{@code null} or empty — falls back to system property / default</li>
     * </ul></p>
     *
     * @param agentArgs raw agent argument string
     * @return resolved config file path
     */
    private static String resolveConfigPath(String agentArgs) {
        if (agentArgs != null && !agentArgs.isEmpty()) {
            // Check for config= prefix
            String[] parts = agentArgs.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.startsWith("config=")) {
                    return trimmed.substring("config=".length()).trim();
                }
            }
            // Legacy: treat entire argument as path (if it doesn't contain '=')
            if (!agentArgs.contains("=")) {
                return agentArgs;
            }
        }
        // Fallback to system property or default
        return System.getProperty("baafoo.agent.config", "./baafoo-agent.yml");
    }

    /**
     * Initialize AgentManifest fields from config.
     * Must be called BEFORE any bytecode transform is installed.
     */
    private static void initAgentManifest(AgentConfig cfg) {
        if (cfg.getServerUrl() != null) {
            String url = cfg.getServerUrl();
            String host = url;
            int port = 8080;

            if (host.startsWith("http://")) {
                host = host.substring(7);
            } else if (host.startsWith("https://")) {
                host = host.substring(8);
            }

            int colonIdx = host.lastIndexOf(':');
            if (colonIdx > 0) {
                try {
                    port = Integer.parseInt(host.substring(colonIdx + 1));
                    host = host.substring(0, colonIdx);
                } catch (NumberFormatException e) {
                    // Ignore, use defaults
                }
            }

            AgentManifest.serverHost = host;
            AgentManifest.serverPort = port;
        }

        AgentManifest.environmentId = cfg.getEnvironment() != null ? cfg.getEnvironment() : "default";

        if (cfg.getAgentId() != null) {
            AgentManifest.agentId = cfg.getAgentId();
        }
    }

    private static void shutdown() {
        log.info("Shutting down Baafoo Agent...");
        try {
            AgentManifest.agentLoaded = false;

            // Flush any remaining recordings
            RouteManager.flushRecordings();

            if (controlChannel != null) {
                controlChannel.stop();
            }
            if (pluginManager != null) {
                pluginManager.shutdown();
            }
        } catch (Exception e) {
            log.error("Error during shutdown: {}", e.getMessage());
        }
        log.info("Baafoo Agent stopped");
    }

    // --- Static accessors for Advice classes ---

    public static AgentConfig getConfig() {
        return config;
    }

    public static ControlChannel getControlChannel() {
        return controlChannel;
    }

    public static PluginManager getPluginManager() {
        return pluginManager;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    private static String getVersion() {
        String version = BaafooAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "1.0.0-SNAPSHOT";
    }

    /**
     * Add the agent jar to the Bootstrap ClassLoader search path.
     *
     * <p>This is CRITICAL for Byte Buddy Advice to work correctly. When Advice
     * code is inlined into JDK classes (like Socket, InetAddress), those classes
     * are loaded by the Bootstrap ClassLoader. The inlined code references
     * AgentManifest and RouteTable, which must also be visible to the
     * Bootstrap ClassLoader.</p>
     *
     * <p>Without this call, any Advice referencing com.baafoo.* classes
     * will throw ClassNotFoundException at runtime.</p>
     *
     * @param inst the Instrumentation instance
     */
    private static void appendToBootstrapClassLoaderSearch(Instrumentation inst) {
        try {
            // Find the agent jar file from the code source location
            java.security.CodeSource codeSource = BaafooAgent.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                log.warn("Cannot determine agent jar location (codeSource is null). " +
                        "Advice classes may fail with ClassNotFoundException.");
                return;
            }

            File jarFile = new File(codeSource.getLocation().toURI());
            if (jarFile.exists() && jarFile.getName().endsWith(".jar")) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(jarFile));
                log.info("Added to Bootstrap CL: {}", jarFile.getAbsolutePath());
            } else if (jarFile.isDirectory()) {
                // Running from IDE (target/classes) — need to create a temp jar
                // For now, log a warning. In production, always use the shaded jar.
                log.warn("Agent is running from a directory (not a jar): {}. " +
                        "Bootstrap CL search path not updated. " +
                        "Advice classes may fail. Use the shaded jar for production.",
                        jarFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to add agent jar to Bootstrap ClassLoader search path: {}", e.getMessage(), e);
        }
    }
}
