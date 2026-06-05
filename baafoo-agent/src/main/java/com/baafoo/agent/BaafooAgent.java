package com.baafoo.agent;

import com.baafoo.agent.advice.*;
import com.baafoo.agent.channel.ControlChannel;
import com.baafoo.agent.plugin.PluginManager;
import com.baafoo.agent.transform.TransformRegistry;
import com.baafoo.core.config.AgentConfig;
import com.baafoo.core.config.ConfigLoader;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class BaafooAgent {

    private static final Logger log = LoggerFactory.getLogger(BaafooAgent.class);

    private static volatile AgentConfig config;
    private static volatile ControlChannel controlChannel;
    private static volatile PluginManager pluginManager;
    private static volatile boolean initialized = false;

    private static volatile ConcurrentHashMap<String, GlobalRouteState.HostPort> bootstrapRoutes;

    private static volatile Class<?> bootstrapGRSClass;
    private static volatile java.lang.reflect.Constructor<?> bootstrapHostPortCtor;

    public static Class<?> getBootstrapGRSClass() {
        return bootstrapGRSClass;
    }

    public static java.lang.reflect.Constructor<?> getBootstrapHostPortCtor() {
        return bootstrapHostPortCtor;
    }

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

            log.info("GlobalRouteState initialized: SERVER_HOST={}, SERVER_PORT={}, CURRENT_MODE={}",
                    GlobalRouteState.SERVER_HOST, GlobalRouteState.SERVER_PORT, GlobalRouteState.CURRENT_MODE);

            controlChannel = new ControlChannel(config);
            controlChannel.setAgentIdCallback(id -> config.setAgentId(id));
            controlChannel.start();

            pluginManager = new PluginManager();

            Runtime.getRuntime().addShutdownHook(new Thread(BaafooAgent::shutdown, "baafoo-shutdown"));

            installTransforms(config, inst);

            setupBootstrapClassPath(inst);

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
                AgentManifest.setServerHost(host);
                AgentManifest.setServerPort(port);
            } catch (Exception e) {
                log.warn("Failed to parse server URL: {}, using defaults", cfg.getServerUrl());
                AgentManifest.setServerHost("127.0.0.1");
                AgentManifest.setServerPort(8080);
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

    private static void installTransforms(AgentConfig cfg, Instrumentation inst) {
        TransformRegistry registry = new TransformRegistry();
        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.Listener.StreamWriting.toSystemOut())
                .ignore(nameStartsWith("net.bytebuddy.")
                        .or(nameStartsWith("com.baafoo.agent.shaded."))
                        .or(isSynthetic()));

        // DNS resolution interception — records domain-to-IP mappings so that
        // SocketConnectAdvice can look up domain-based routes when socket connects
        // using a resolved IP address instead of the original hostname.
        // When consulEnabled, ConsulDnsAdvice also handles service name redirection.
        if (cfg.isConsulEnabled()) {
            agentBuilder = agentBuilder
                    .type(named("java.net.InetAddress"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(ConsulDnsAdvice.class)
                                    .on(named("getByName").and(takesArguments(1)))))
                    .type(named("java.net.InetAddress"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(ConsulDnsAdvice.class)
                                    .on(named("getAllByName").and(takesArguments(1)))));
            registry.register("java.net.InetAddress", "ConsulDnsAdvice", "dns+consul");
        } else {
            agentBuilder = agentBuilder
                    .type(named("java.net.InetAddress"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(DnsResolutionAdvice.class)
                                    .on(named("getByName").and(takesArguments(1)))))
                    .type(named("java.net.InetAddress"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(DnsResolutionAdvice.class)
                                    .on(named("getAllByName").and(takesArguments(1)))));
            registry.register("java.net.InetAddress", "DnsResolutionAdvice", "dns");
        }

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

        if (cfg.isConsulEnabled()) {
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
    }

    private static void setupBootstrapClassPath(Instrumentation inst) {
        appendToBootstrapClassLoaderSearch(inst);

        syncGlobalRouteStateToBootstrapCL();
        log.info("GlobalRouteState synced to Bootstrap CL version");
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

    public static ConcurrentHashMap<String, GlobalRouteState.HostPort> getBootstrapRoutes() {
        return bootstrapRoutes;
    }

    private static String getVersion() {
        String version = BaafooAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "1.0.0-SNAPSHOT";
    }

    private static void appendToBootstrapClassLoaderSearch(Instrumentation inst) {
        try {
            File bootstrapJar = createBootstrapJar();
            if (bootstrapJar != null && bootstrapJar.exists()) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar));
                log.info("Added Bootstrap helper jar to Bootstrap CL: {}", bootstrapJar.getAbsolutePath());
            } else {
                log.error("Failed to create Bootstrap helper jar. " +
                        "Advice classes will fail with ClassNotFoundException.");
            }
        } catch (Exception e) {
            log.error("Failed to add Bootstrap helper jar to Bootstrap ClassLoader search path: {}", e.getMessage(), e);
        }
    }

    /**
     * Creates a minimal jar containing only the classes needed by inlined Advice code
     * running in the Bootstrap ClassLoader context (e.g. GlobalRouteState).
     *
     * <p>Uses {@code ClassLoader.getResourceAsStream()} instead of {@code JarFile}
     * so this works regardless of how the agent is packaged — shaded jar, exploded
     * directory, or IDE classpath. ByteBuddy and other third-party classes are
     * intentionally excluded to prevent version conflicts with the host application
     * or other agents.</p>
     */
    private static File createBootstrapJar() {
        try {
            File tempJar = File.createTempFile("baafoo-bootstrap-", ".jar");
            tempJar.deleteOnExit();

            String[] classResources = {
                    "com/baafoo/agent/GlobalRouteState.class",
                    "com/baafoo/agent/GlobalRouteState$HostPort.class"
            };

            java.util.jar.Manifest manifest = new java.util.jar.Manifest();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempJar);
            java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(fos, manifest);

            try {
                ClassLoader cl = BaafooAgent.class.getClassLoader();
                for (String resource : classResources) {
                    java.io.InputStream is = cl.getResourceAsStream(resource);
                    if (is != null) {
                        try {
                            jos.putNextEntry(new java.util.jar.JarEntry(resource));
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                jos.write(buf, 0, n);
                            }
                            jos.closeEntry();
                        } finally {
                            is.close();
                        }
                    } else {
                        log.warn("Bootstrap class resource not found: {}", resource);
                    }
                }
            } finally {
                jos.close();
            }

            return tempJar;
        } catch (Exception e) {
            log.error("Failed to create Bootstrap helper jar: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void syncGlobalRouteStateToBootstrapCL() {
        try {
            Class<?> bootGRS = findBootstrapClass("com.baafoo.agent.GlobalRouteState");

            Object bootRoutesObj = bootGRS.getField("ROUTES").get(null);
            if (bootRoutesObj instanceof ConcurrentHashMap) {
                ConcurrentHashMap<String, GlobalRouteState.HostPort> bootRoutes = (ConcurrentHashMap<String, GlobalRouteState.HostPort>) bootRoutesObj;
                Class<?> bootHostPortClass = Class.forName("com.baafoo.agent.GlobalRouteState$HostPort", false, bootGRS.getClassLoader());
                java.lang.reflect.Constructor<?> ctor = bootHostPortClass.getConstructor(String.class, int.class);
                ((ConcurrentHashMap) bootRoutes).clear();
                for (Map.Entry<String, GlobalRouteState.HostPort> entry : GlobalRouteState.ROUTES.entrySet()) {
                    Object bootHostPort = ctor.newInstance(entry.getValue().host, entry.getValue().port);
                    ((ConcurrentHashMap) bootRoutes).put(entry.getKey(), bootHostPort);
                }
                bootstrapRoutes = bootRoutes;
                bootstrapHostPortCtor = ctor;
                log.info("Synced {} routes to Bootstrap CL GlobalRouteState.ROUTES", bootRoutes.size());
            }

            bootGRS.getField("CURRENT_MODE").setInt(null, GlobalRouteState.CURRENT_MODE);

            bootGRS.getField("SERVER_HOST").set(null, GlobalRouteState.SERVER_HOST);

            bootGRS.getField("SERVER_PORT").setInt(null, GlobalRouteState.SERVER_PORT);

            bootstrapGRSClass = bootGRS;

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
