package com.baafoo.agent;

import com.baafoo.agent.advice.*;
import com.baafoo.agent.channel.ControlChannel;
import com.baafoo.agent.plugin.PluginManager;
import com.baafoo.agent.transform.TransformRegistry;
import com.baafoo.core.config.AgentConfig;
import com.baafoo.core.config.ConfigLoader;
import com.baafoo.core.model.RecordingEntry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class BaafooAgent {

    private static final Logger log = LoggerFactory.getLogger(BaafooAgent.class);

    private static volatile AgentConfig config;
    private static volatile ControlChannel controlChannel;
    private static volatile PluginManager pluginManager;
    private static volatile RecordingBuffer recordingBuffer;
    private static volatile boolean initialized = false;

    /** The Instrumentation instance, retained so shutdown can remove transformers. */
    private static volatile Instrumentation instrumentation;
    /** The ByteBuddy class file transformer installed by installTransforms. */
    private static volatile java.lang.instrument.ClassFileTransformer classFileTransformer;

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

            // IMPORTANT: setupBootstrapClassPath MUST run before installTransforms.
            // Advice code (e.g., DnsGetByNameAdvice) references GlobalRouteState,
            // which must be on the Bootstrap CL search path before ByteBuddy tries
            // to inline the advice into target classes like InetAddress.
            setupBootstrapClassPath(inst);
            instrumentation = inst;

            // Register SLF4J-backed log handlers so that Bootstrap CL advice code
            // (inlined into java.net.Socket, InetAddress, etc.) can log through
            // GlobalRouteState.logInfo/logWarn/logError instead of System.out.
            Logger adviceLogger = LoggerFactory.getLogger("com.baafoo.agent.advice");
            GlobalRouteState.LOG_INFO_HANDLER = (Consumer<String>) adviceLogger::info;
            GlobalRouteState.LOG_WARN_HANDLER = (Consumer<String>) adviceLogger::warn;
            GlobalRouteState.LOG_ERROR_HANDLER = (Consumer<String>) adviceLogger::error;
            // Also sync handlers to the Bootstrap CL copy of GlobalRouteState
            syncLogHandlersToBootstrapCL(adviceLogger);

            // Set up recording infrastructure
            initRecording(config);

            installTransforms(config, inst);

            AgentManifest.agentLoaded = true;
            initialized = true;

            log.info("=== Baafoo Agent started successfully ===");

        } catch (Throwable e) {
            boolean failOpen = config != null && config.isFailOpen();
            if (failOpen) {
                log.warn("Baafoo Agent initialization failed (fail-open mode). " +
                        "All requests will pass through silently. Error: {}", e.getMessage());
            } else {
                log.error("FAILED to start Baafoo Agent (fail-closed). " +
                        "All requests will pass through to real downstreams. Error: {}", e.getMessage(), e);
            }
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
        AgentConfig.ServerConnection sc = cfg.getServer();

        // server.host takes precedence; fall back to serverUrl parsing
        String host = sc.getHost();
        int apiPort = sc.getApiPort();

        if (host == null || host.isEmpty()) {
            // Legacy: parse from serverUrl
            if (cfg.getServerUrl() != null) {
                try {
                    java.net.URI uri = new java.net.URI(cfg.getServerUrl());
                    host = uri.getHost();
                    int urlPort = uri.getPort();
                    if (host == null) {
                        host = "127.0.0.1";
                    }
                    if (urlPort > 0) {
                        apiPort = urlPort;
                    } else {
                        apiPort = "https".equals(uri.getScheme()) ? 443 : 8084;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse server URL: {}, using defaults", cfg.getServerUrl());
                    host = "127.0.0.1";
                    apiPort = 8084;
                }
            } else {
                host = "127.0.0.1";
            }
        }

        AgentManifest.setServerHost(host);
        AgentManifest.setServerPort(apiPort);

        // Set protocol-specific stub ports
        AgentManifest.setHttpPort(sc.getHttpPort());
        AgentManifest.setTcpPort(sc.getTcpPort());
        AgentManifest.setKafkaPort(sc.getKafkaPort());
        AgentManifest.setPulsarPort(sc.getPulsarPort());
        AgentManifest.setJmsPort(sc.getJmsPort());

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
            if (recordingBuffer != null) {
                recordingBuffer.stop();
            }
            if (controlChannel != null) {
                controlChannel.stop();
            }
            if (pluginManager != null) {
                pluginManager.shutdown();
            }
            // Remove the installed class file transformer so that a hot-restart
            // of the agent doesn't stack a second transformer on top of the first.
            if (instrumentation != null && classFileTransformer != null) {
                try {
                    instrumentation.removeTransformer(classFileTransformer);
                    log.info("Removed class file transformer");
                } catch (Exception e) {
                    log.warn("Failed to remove class file transformer: {}", e.getMessage());
                }
                classFileTransformer = null;
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
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                        if ((typeName.startsWith("org.apache.pulsar.client.impl") && typeName.contains("Builder"))
                                || typeName.contains("ActiveMQConnectionFactory")
                                || typeName.equals("java.net.InetAddress")
                                || typeName.equals("java.net.Socket")) {
                            log.info("ByteBuddy discovered: typeName={}, loaded={}, classLoader={}", typeName, loaded, classLoader);
                        }
                    }
                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
                        log.info("ByteBuddy transformed: typeName={}, loaded={}, classLoader={}", typeDescription.getName(), loaded, classLoader);
                    }
                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                        log.warn("ByteBuddy transform error for {}: {}", typeName, throwable.getMessage());
                    }
                })
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
                                    .on(named("getByName").and(takesArguments(1))))
                            .visit(Advice.to(ConsulDnsAdvice.class)
                                    .on(named("getAllByName").and(takesArguments(1)))));
            registry.register("java.net.InetAddress", "ConsulDnsAdvice", "dns+consul");
        } else {
            agentBuilder = agentBuilder
                    .type(named("java.net.InetAddress"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(DnsGetByNameAdvice.class)
                                    .on(named("getByName").and(takesArguments(1))))
                            .visit(Advice.to(DnsGetAllByNameAdvice.class)
                                    .on(named("getAllByName").and(takesArguments(1)))));
            registry.register("java.net.InetAddress", "DnsResolutionAdvice", "dns");
        }

        agentBuilder = agentBuilder
                .type(named("java.net.Socket"))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(SocketConnectAdvice.class)
                                .on(named("connect")
                                        .and(takesArguments(1)
                                                .or(takesArguments(2)))))
                        .visit(Advice.to(SocketInputStreamAdvice.class)
                                .on(named("getInputStream").and(takesArguments(0))))
                        .visit(Advice.to(SocketOutputStreamAdvice.class)
                                .on(named("getOutputStream").and(takesArguments(0))))
                        .visit(Advice.to(SocketCloseAdvice.class)
                                .on(named("close").and(takesArguments(0)))));
        registry.register("java.net.Socket", "SocketConnectAdvice", "tcp");
        registry.register("java.net.Socket", "SocketGetStreamAdvice", "tcp-recording");
        registry.register("java.net.Socket", "SocketCloseAdvice", "tcp-recording");

        agentBuilder = agentBuilder
                .type(named("sun.nio.ch.SocketChannelImpl"))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(NioSocketConnectAdvice.class)
                                .on(named("connect").and(takesArguments(1))))
                        .visit(Advice.to(NioSocketFinishConnectAdvice.class)
                                .on(named("finishConnect").and(takesArguments(0))))
                        .visit(Advice.to(SocketChannelReadAdvice.class)
                                .on(named("read").and(takesArguments(1))
                                        .and(takesArgument(0, named("java.nio.ByteBuffer")))))
                        .visit(Advice.to(SocketChannelWriteAdvice.class)
                                .on(named("write").and(takesArguments(1))
                                        .and(takesArgument(0, named("java.nio.ByteBuffer"))))));
        registry.register("sun.nio.ch.SocketChannelImpl", "NioSocketConnectAdvice", "tcp");
        registry.register("sun.nio.ch.SocketChannelImpl", "NioSocketFinishConnectAdvice", "tcp");
        registry.register("sun.nio.ch.SocketChannelImpl", "SocketChannelReadAdvice", "tcp-recording");
        registry.register("sun.nio.ch.SocketChannelImpl", "SocketChannelWriteAdvice", "tcp-recording");

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
                .type(named("org.apache.kafka.clients.consumer.KafkaConsumer"))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(KafkaConsumerAdvice.class)
                                .on(isConstructor())));
        registry.register("org.apache.kafka.clients.consumer.KafkaConsumer", "KafkaConsumerAdvice", "kafka");

        agentBuilder = agentBuilder
                .type(nameContains("ClientBuilder").and(nameStartsWith("org.apache.pulsar")))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(PulsarClientAdvice.class)
                                .on(named("serviceUrl").and(takesArguments(1)))));
        registry.register("org.apache.pulsar.client.api.ClientBuilder", "PulsarClientAdvice", "pulsar");

        // JMS: intercept ActiveMQConnectionFactory constructor (OnMethodExit) to replace brokerURL
        agentBuilder = agentBuilder
                .type(named("org.apache.activemq.ActiveMQConnectionFactory")
                        .or(named("org.apache.activemq.ActiveMQXAConnectionFactory")))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(JmsConnectionFactoryAdvice.class)
                                .on(isConstructor())));
        registry.register("org.apache.activemq.ActiveMQConnectionFactory", "JmsConnectionFactoryAdvice", "jms");

        classFileTransformer = agentBuilder.installOn(inst);
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

    public static RecordingBuffer getRecordingBuffer() {
        return recordingBuffer;
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

    /**
     * Update the cached reference to the Bootstrap CL's ROUTES map after an atomic swap.
     * Called by {@link RouteManager#syncRoutesToBootstrapCL} so that subsequent accessors
     * see the new map instance.
     */
    public static void updateBootstrapRoutes(ConcurrentHashMap<String, GlobalRouteState.HostPort> newRoutes) {
        bootstrapRoutes = newRoutes;
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
                Class<?> bootHostPortClass = Class.forName("com.baafoo.agent.GlobalRouteState$HostPort", false, bootGRS.getClassLoader());
                java.lang.reflect.Constructor<?> ctor = bootHostPortClass.getConstructor(String.class, int.class);
                // Build a new map and atomically swap the ROUTES field reference,
                // avoiding the clear+putAll window where concurrent readers see an empty table.
                // NOTE: bootHostPort instances are from the Bootstrap CL HostPort class, which is
                // a *different* class from the App CL GlobalRouteState.HostPort. We use a raw
                // ConcurrentHashMap to avoid a ClassCastException across class-loader boundaries.
                @SuppressWarnings({"unchecked", "rawtypes"})
                ConcurrentHashMap newBootRoutes = new ConcurrentHashMap();
                for (Map.Entry<String, GlobalRouteState.HostPort> entry : GlobalRouteState.ROUTES.entrySet()) {
                    Object bootHostPort = ctor.newInstance(entry.getValue().host, entry.getValue().port);
                    newBootRoutes.put(entry.getKey(), bootHostPort);
                }
                bootGRS.getField("ROUTES").set(null, newBootRoutes);
                bootstrapRoutes = newBootRoutes;
                bootstrapHostPortCtor = ctor;
                log.info("Synced {} routes to Bootstrap CL GlobalRouteState.ROUTES (atomic swap)", newBootRoutes.size());
            }

            bootGRS.getField("CURRENT_MODE").setInt(null, GlobalRouteState.CURRENT_MODE);

            bootGRS.getField("SERVER_HOST").set(null, GlobalRouteState.SERVER_HOST);

            bootGRS.getField("SERVER_HOST_IP").set(null, GlobalRouteState.SERVER_HOST_IP);

            bootGRS.getField("SERVER_PORT").setInt(null, GlobalRouteState.SERVER_PORT);

            bootGRS.getField("HTTP_PORT").setInt(null, GlobalRouteState.HTTP_PORT);
            bootGRS.getField("TCP_PORT").setInt(null, GlobalRouteState.TCP_PORT);
            bootGRS.getField("KAFKA_PORT").setInt(null, GlobalRouteState.KAFKA_PORT);
            bootGRS.getField("PULSAR_PORT").setInt(null, GlobalRouteState.PULSAR_PORT);
            bootGRS.getField("JMS_PORT").setInt(null, GlobalRouteState.JMS_PORT);

            bootstrapGRSClass = bootGRS;

            log.info("Synced GlobalRouteState fields to Bootstrap CL: CURRENT_MODE={}, SERVER_HOST={}, SERVER_HOST_IP={}, SERVER_PORT={}, " +
                            "HTTP_PORT={}, TCP_PORT={}, KAFKA_PORT={}, PULSAR_PORT={}, JMS_PORT={}",
                    GlobalRouteState.CURRENT_MODE, GlobalRouteState.SERVER_HOST, GlobalRouteState.SERVER_HOST_IP, GlobalRouteState.SERVER_PORT,
                    GlobalRouteState.HTTP_PORT, GlobalRouteState.TCP_PORT, GlobalRouteState.KAFKA_PORT,
                    GlobalRouteState.PULSAR_PORT, GlobalRouteState.JMS_PORT);
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

    /**
     * Initialize the recording infrastructure: create RecordingBuffer, set up
     * stream wrapper bridge functions in GlobalRouteState, and sync them to
     * the Bootstrap CL copy.
     */
    private static void initRecording(AgentConfig cfg) {
        // Create RecordingBuffer with configurable max size and 30s flush interval
        int maxBufferSize = 100; // flush after 100 entries
        recordingBuffer = new RecordingBuffer(maxBufferSize, 30);
        recordingBuffer.start();
        log.info("RecordingBuffer initialized (maxSize={}, flushInterval=30s)", maxBufferSize);

        // Set up the INPUT_STREAM_WRAPPER bridge function.
        // This is called from Bootstrap CL advice (SocketGetStreamAdvice.afterGetInputStream)
        // to wrap the socket's InputStream with a RecordingInputStream.
        // The sessionInfo array contains {sessionId, host, portString}.
        GlobalRouteState.INPUT_STREAM_WRAPPER = (java.io.InputStream in, String[] sessionInfo) -> {
            if (sessionInfo == null || sessionInfo.length < 3) {
                log.warn("Invalid sessionInfo for INPUT_STREAM_WRAPPER: {}", Arrays.toString(sessionInfo));
                return in;
            }
            String sessionId = sessionInfo[0];
            String host = sessionInfo[1];
            int port = Integer.parseInt(sessionInfo[2]);
            return new RecordingInputStream(in, sessionId, host, port, recordingBuffer);
        };

        // Set up the OUTPUT_STREAM_WRAPPER bridge function
        GlobalRouteState.OUTPUT_STREAM_WRAPPER = (java.io.OutputStream out, String[] sessionInfo) -> {
            if (sessionInfo == null || sessionInfo.length < 3) {
                log.warn("Invalid sessionInfo for OUTPUT_STREAM_WRAPPER: {}", Arrays.toString(sessionInfo));
                return out;
            }
            String sessionId = sessionInfo[0];
            String host = sessionInfo[1];
            int port = Integer.parseInt(sessionInfo[2]);
            return new RecordingOutputStream(out, sessionId, host, port, recordingBuffer);
        };

        // Set up the NIO_RECORDING_HANDLER bridge function.
        // This is called from Bootstrap CL advice (SocketChannelReadAdvice/SocketChannelWriteAdvice)
        // to record NIO SocketChannel read/write data.
        GlobalRouteState.NIO_RECORDING_HANDLER = (Object[] args) -> {
            if (args == null || args.length < 1 || !(args[0] instanceof String[])) {
                log.warn("Invalid args for NIO_RECORDING_HANDLER");
                return;
            }
            String[] sessionInfo = (String[]) args[0];
            if (sessionInfo == null || sessionInfo.length < 3) {
                log.warn("Invalid sessionInfo for NIO_RECORDING_HANDLER: {}", Arrays.toString(sessionInfo));
                return;
            }
            String direction = (String) args[1];
            String hexData = (String) args[2];
            String host = sessionInfo[1];
            int port = Integer.parseInt(sessionInfo[2]);
            RecordingEntry entry = new RecordingEntry();
            entry.setSessionId(sessionInfo[0]);
            entry.setHost(host);
            entry.setPort(port);
            entry.setProtocol(GlobalRouteState.inferProtocol(host, port));
            entry.setDirection(direction);
            entry.setDataHex(hexData);
            entry.setRecordedAt(System.currentTimeMillis());
            recordingBuffer.add(entry);
        };

        // Sync wrapper functions to Bootstrap CL
        syncRecordingWrappersToBootstrapCL();

        log.info("Recording stream wrappers configured and synced to Bootstrap CL");
    }

    /**
     * Sync the recording stream wrapper functions to the Bootstrap CL copy of GlobalRouteState.
     */
    private static void syncRecordingWrappersToBootstrapCL() {
        try {
            Class<?> bootGRS = bootstrapGRSClass;
            if (bootGRS == null) {
                log.warn("Bootstrap CL GlobalRouteState class not found, skipping recording wrapper sync");
                return;
            }

            java.lang.reflect.Field iswField = bootGRS.getField("INPUT_STREAM_WRAPPER");
            java.lang.reflect.Field oswField = bootGRS.getField("OUTPUT_STREAM_WRAPPER");
            java.lang.reflect.Field nioField = bootGRS.getField("NIO_RECORDING_HANDLER");

            iswField.set(null, GlobalRouteState.INPUT_STREAM_WRAPPER);
            oswField.set(null, GlobalRouteState.OUTPUT_STREAM_WRAPPER);
            nioField.set(null, GlobalRouteState.NIO_RECORDING_HANDLER);

            log.info("Synced recording stream wrappers and NIO handler to Bootstrap CL GlobalRouteState");
        } catch (Exception e) {
            log.warn("Failed to sync recording wrappers to Bootstrap CL GlobalRouteState: {}. " +
                    "Stream recording will not work.", e.getMessage());
        }
    }

    /**
     * Sync the SLF4J-backed log handlers to the Bootstrap CL copy of GlobalRouteState.
     * The Bootstrap CL copy is a separate class instance; its static fields are
     * independent from the App CL version, so we must set them via reflection.
     */
    private static void syncLogHandlersToBootstrapCL(Logger adviceLogger) {
        try {
            Class<?> bootGRS = bootstrapGRSClass;
            if (bootGRS == null) {
                log.warn("Bootstrap CL GlobalRouteState class not found, skipping log handler sync");
                return;
            }

            Class<?> consumerClass = java.util.function.Consumer.class;
            java.lang.reflect.Field infoField = bootGRS.getField("LOG_INFO_HANDLER");
            java.lang.reflect.Field warnField = bootGRS.getField("LOG_WARN_HANDLER");
            java.lang.reflect.Field errorField = bootGRS.getField("LOG_ERROR_HANDLER");

            Consumer<String> infoHandler = (Consumer<String>) adviceLogger::info;
            Consumer<String> warnHandler = (Consumer<String>) adviceLogger::warn;
            Consumer<String> errorHandler = (Consumer<String>) adviceLogger::error;

            infoField.set(null, infoHandler);
            warnField.set(null, warnHandler);
            errorField.set(null, errorHandler);

            log.info("Synced SLF4J log handlers to Bootstrap CL GlobalRouteState");
        } catch (Exception e) {
            log.warn("Failed to sync log handlers to Bootstrap CL GlobalRouteState: {}. " +
                    "Bootstrap CL advice will fall back to System.out.", e.getMessage());
        }
    }
}
