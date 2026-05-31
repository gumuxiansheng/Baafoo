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
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class BaafooAgent {

    private static final Logger log = LoggerFactory.getLogger(BaafooAgent.class);

    private static volatile AgentConfig config;
    private static volatile ControlChannel controlChannel;
    private static volatile PluginManager pluginManager;
    private static volatile boolean initialized = false;

    private static volatile ConcurrentHashMap<String, String> bootstrapRoutes;
    private static volatile java.lang.reflect.Field bootstrapCurrentModeField;
    private static volatile java.lang.reflect.Field bootstrapServerHostField;
    private static volatile java.lang.reflect.Field bootstrapServerPortField;

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            log.info("=== Baafoo Agent {} starting ===", getVersion());
            log.info("Agent args: {}", agentArgs);

            String configPath = resolveConfigPath(agentArgs);
            config = ConfigLoader.loadAgentConfig(configPath);
            log.info("Config loaded: {}", config);

            initAgentManifest(config);
            log.info("AgentManifest initialized: serverHost={}, serverPort={}, envId={}, mode={}",
                    AgentManifest.serverHost, AgentManifest.serverPort,
                    AgentManifest.environmentId, AgentManifest.getModeName());

            GlobalRouteState.SERVER_HOST = AgentManifest.serverHost;
            GlobalRouteState.SERVER_PORT = AgentManifest.serverPort;
            GlobalRouteState.CURRENT_MODE = AgentManifest.currentMode;
            log.info("GlobalRouteState initialized: SERVER_HOST={}, SERVER_PORT={}, CURRENT_MODE={}",
                    GlobalRouteState.SERVER_HOST, GlobalRouteState.SERVER_PORT, GlobalRouteState.CURRENT_MODE);

            controlChannel = new ControlChannel(config);
            controlChannel.start();

            pluginManager = new PluginManager();

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    shutdown();
                }
            }, "baafoo-shutdown"));

            TransformRegistry registry = new TransformRegistry();
            AgentBuilder agentBuilder = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.Listener.StreamWriting.toSystemOut())
                    .ignore(nameStartsWith("net.bytebuddy.")
                            .or(nameStartsWith("com.baafoo.agent.shaded."))
                            .or(isSynthetic()));

            agentBuilder = agentBuilder
                    .type(named("java.net.Socket"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(SocketConnectAdvice.class)
                                    .on(named("connect")
                                            .and(takesArguments(1)
                                                    .or(takesArguments(2))))));
            registry.register("java.net.Socket", "SocketConnectAdvice", "tcp");

            agentBuilder = agentBuilder
                    .type(named("sun.nio.ch.SocketChannelImpl"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(NioSocketConnectAdvice.class)
                                    .on(named("connect").and(takesArguments(1)))));
            registry.register("sun.nio.ch.SocketChannelImpl", "NioSocketConnectAdvice", "tcp");

            if (config.isConsulEnabled()) {
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

                agentBuilder = agentBuilder
                        .type(named("sun.net.www.http.HttpClient"))
                        .transform((builder, typeDesc, classLoader, module, pd) ->
                                builder.visit(Advice.to(ConsulHttpAdvice.class)
                                        .on(named("openServer").and(takesArguments(2)))));
                registry.register("sun.net.www.http.HttpClient", "ConsulHttpAdvice", "http");
            }

            agentBuilder = agentBuilder
                    .type(named("org.apache.kafka.clients.producer.KafkaProducer"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(KafkaProducerAdvice.class)
                                    .on(isConstructor())));
            registry.register("org.apache.kafka.clients.producer.KafkaProducer", "KafkaProducerAdvice", "kafka");

            agentBuilder = agentBuilder
                    .type(named("org.apache.pulsar.client.api.ClientBuilder"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(PulsarClientAdvice.class)
                                    .on(named("serviceUrl").and(takesArguments(1)))));
            registry.register("org.apache.pulsar.client.api.ClientBuilder", "PulsarClientAdvice", "pulsar");

            agentBuilder.installOn(inst);
            log.info("Bytecode transforms installed: {} transforms registered", registry.getCount());

            appendToBootstrapClassLoaderSearch(inst);
            log.info("Agent jar added to Bootstrap ClassLoader search path");

            syncGlobalRouteStateToBootstrapCL();
            log.info("GlobalRouteState synced to Bootstrap CL version");

            AgentManifest.agentLoaded = true;
            initialized = true;

            log.info("=== Baafoo Agent started successfully ===");

        } catch (Throwable e) {
            log.error("FAILED to start Baafoo Agent (fail-closed). " +
                    "All requests will pass through to real downstreams. Error: {}", e.getMessage(), e);
            initialized = false;
        }
    }

    private static String resolveConfigPath(String agentArgs) {
        if (agentArgs != null && !agentArgs.isEmpty()) {
            String[] parts = agentArgs.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.startsWith("config=")) {
                    return trimmed.substring("config=".length()).trim();
                }
            }
            if (!agentArgs.contains("=")) {
                return agentArgs;
            }
        }
        return System.getProperty("baafoo.agent.config", "./baafoo-agent.yml");
    }

    private static void initAgentManifest(AgentConfig cfg) {
        if (cfg.getServerUrl() != null) {
            try {
                java.net.URI uri = new java.net.URI(cfg.getServerUrl());
                String host = uri.getHost();
                int port = uri.getPort();
                if (host == null) {
                    host = "127.0.0.1";
                }
                if (port < 0) {
                    port = "https".equals(uri.getScheme()) ? 443 : 8080;
                }
                AgentManifest.serverHost = host;
                AgentManifest.serverPort = port;
            } catch (Exception e) {
                log.warn("Failed to parse server URL: {}, using defaults", cfg.getServerUrl());
                AgentManifest.serverHost = "127.0.0.1";
                AgentManifest.serverPort = 8080;
            }
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

    public static ConcurrentHashMap<String, String> getBootstrapRoutes() {
        return bootstrapRoutes;
    }

    public static java.lang.reflect.Field getBootstrapCurrentModeField() {
        return bootstrapCurrentModeField;
    }

    public static java.lang.reflect.Field getBootstrapServerHostField() {
        return bootstrapServerHostField;
    }

    public static java.lang.reflect.Field getBootstrapServerPortField() {
        return bootstrapServerPortField;
    }

    private static String getVersion() {
        String version = BaafooAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "1.0.0-SNAPSHOT";
    }

    private static void appendToBootstrapClassLoaderSearch(Instrumentation inst) {
        try {
            java.security.CodeSource codeSource = BaafooAgent.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                log.warn("Cannot determine agent jar location (codeSource is null). " +
                        "Advice classes may fail with ClassNotFoundException.");
                return;
            }

            File agentJar = new File(codeSource.getLocation().toURI());
            if (!agentJar.exists() || !agentJar.getName().endsWith(".jar")) {
                log.warn("Agent is running from a directory (not a jar): {}. " +
                        "Bootstrap CL search path not updated. " +
                        "Advice classes may fail. Use the shaded jar for production.",
                        agentJar.getAbsolutePath());
                return;
            }

            File bootstrapJar = createBootstrapJar(agentJar);
            if (bootstrapJar != null && bootstrapJar.exists()) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar));
                log.info("Added Bootstrap helper jar to Bootstrap CL: {}", bootstrapJar.getAbsolutePath());
            } else {
                log.warn("Failed to create Bootstrap helper jar, falling back to full agent jar");
                inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
                log.info("Added full agent jar to Bootstrap CL: {}", agentJar.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to add agent jar to Bootstrap ClassLoader search path: {}", e.getMessage(), e);
        }
    }

    private static File createBootstrapJar(File agentJar) {
        try {
            java.util.jar.JarFile jar = new java.util.jar.JarFile(agentJar);
            File bootstrapJar = new File(agentJar.getParentFile(), "baafoo-bootstrap-helper.jar");
            java.util.jar.Manifest manifest = new java.util.jar.Manifest();
            java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                    new java.io.FileOutputStream(bootstrapJar), manifest);

            String[] bootstrapClasses = {
                    "com/baafoo/agent/GlobalRouteState.class"
            };

            for (String entryName : bootstrapClasses) {
                java.util.jar.JarEntry entry = jar.getJarEntry(entryName);
                if (entry != null) {
                    jos.putNextEntry(new java.util.jar.JarEntry(entryName));
                    java.io.InputStream is = jar.getInputStream(entry);
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        jos.write(buf, 0, n);
                    }
                    is.close();
                    jos.closeEntry();
                } else {
                    log.warn("Bootstrap class not found in agent jar: {}", entryName);
                }
            }

            jos.close();
            jar.close();
            return bootstrapJar;
        } catch (Exception e) {
            log.error("Failed to create Bootstrap helper jar: {}", e.getMessage());
            return null;
        }
    }

    private static void syncGlobalRouteStateToBootstrapCL() {
        try {
            Class<?> bootGRS = findBootstrapClass("com.baafoo.agent.GlobalRouteState");

            Object bootRoutesObj = bootGRS.getField("ROUTES").get(null);
            if (bootRoutesObj instanceof ConcurrentHashMap) {
                @SuppressWarnings("unchecked")
                ConcurrentHashMap<String, String> bootRoutes = (ConcurrentHashMap<String, String>) bootRoutesObj;
                bootRoutes.putAll(GlobalRouteState.ROUTES);
                bootstrapRoutes = bootRoutes;
                log.info("Synced {} routes to Bootstrap CL GlobalRouteState.ROUTES", bootRoutes.size());
            }

            bootstrapCurrentModeField = bootGRS.getField("CURRENT_MODE");
            bootstrapCurrentModeField.setInt(null, GlobalRouteState.CURRENT_MODE);

            bootstrapServerHostField = bootGRS.getField("SERVER_HOST");
            bootstrapServerHostField.set(null, GlobalRouteState.SERVER_HOST);

            bootstrapServerPortField = bootGRS.getField("SERVER_PORT");
            bootstrapServerPortField.setInt(null, GlobalRouteState.SERVER_PORT);

            log.info("Synced GlobalRouteState fields to Bootstrap CL: CURRENT_MODE={}, SERVER_HOST={}, SERVER_PORT={}",
                    GlobalRouteState.CURRENT_MODE, GlobalRouteState.SERVER_HOST, GlobalRouteState.SERVER_PORT);
        } catch (Exception e) {
            log.error("Failed to sync GlobalRouteState to Bootstrap CL: {}", e.getMessage(), e);
        }
    }

    private static Class<?> findBootstrapClass(String name) throws ClassNotFoundException {
        try {
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            while (cl.getParent() != null) {
                cl = cl.getParent();
            }
            return Class.forName(name, false, cl);
        } catch (Exception e) {
            return Class.forName(name);
        }
    }
}
