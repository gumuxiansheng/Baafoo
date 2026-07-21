package com.baafoo.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.jar.JarFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baafoo.agent.advice.DnsResolveAdvice;
import com.baafoo.agent.advice.DnsResolveAllAdvice;
import com.baafoo.agent.advice.GrpcChannelAdvice;
import com.baafoo.agent.advice.HttpOpenServerAdvice;
import com.baafoo.agent.advice.JmsConnectionFactoryAdvice;
import com.baafoo.agent.advice.KafkaConsumerAdvice;
import com.baafoo.agent.advice.KafkaProducerAdvice;
import com.baafoo.agent.advice.NioSocketConnectAdvice;
import com.baafoo.agent.advice.NioSocketFinishConnectAdvice;
import com.baafoo.agent.advice.PulsarClientAdvice;
import com.baafoo.agent.advice.RecordingBuffer;
import com.baafoo.agent.advice.RecordingInputStream;
import com.baafoo.agent.advice.RecordingOutputStream;
import com.baafoo.agent.advice.RouteManager;
import com.baafoo.agent.advice.SocketChannelReadAdvice;
import com.baafoo.agent.advice.SocketChannelWriteAdvice;
import com.baafoo.agent.advice.SocketCloseAdvice;
import com.baafoo.agent.advice.SocketConnectAdvice;
import com.baafoo.agent.advice.SocketInputStreamAdvice;
import com.baafoo.agent.advice.SocketOutputStreamAdvice;
import com.baafoo.agent.bootstrap.BootstrapClassPathSetup;
import com.baafoo.agent.bootstrap.BootstrapStateSync;
import com.baafoo.agent.channel.ControlChannel;
import com.baafoo.agent.plugin.PluginManager;
import com.baafoo.agent.transform.TransformInstaller;
import com.baafoo.agent.transform.TransformRegistry;
import com.baafoo.core.config.AgentConfig;
import com.baafoo.core.config.ConfigLoader;
import com.baafoo.core.model.RecordingEntry;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import net.bytebuddy.utility.JavaModule;

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

    public static Class<?> getBootstrapGRSClass() {
        return BootstrapClassPathSetup.getBootstrapGRSClass();
    }

    public static java.lang.reflect.Constructor<?> getBootstrapHostPortCtor() {
        return BootstrapClassPathSetup.getBootstrapHostPortCtor();
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
            // NOTE: controlChannel.start() is deferred to AFTER installTransforms.
            // The control channel uses HttpURLConnection, which loads
            // sun.net.www.http.HttpClient. If this class is loaded BEFORE
            // installTransforms runs, ByteBuddy must RETRANSFORM the already-
            // loaded class. Retransformation of sun.* classes can fail silently
            // on some JVM implementations (observed on eclipse-temurin:8-jre-
            // alpine in CI), causing HttpOpenServerAdvice to never fire and
            // all HTTP stub cases to return stubbed=false. By deferring
            // controlChannel.start() to after installTransforms, HttpClient
            // is loaded AFTER the ByteBuddy transform is installed, so the
            // transform applies on first load — no retransformation needed.

            pluginManager = new PluginManager(config.getPlugins());

            Runtime.getRuntime().addShutdownHook(new Thread(BaafooAgent::shutdown, "baafoo-shutdown"));

            // IMPORTANT: setupBootstrapClassPath MUST run before installTransforms.
            // Advice code (e.g., DnsResolveAdvice) references GlobalRouteState,
            // which must be on the Bootstrap CL search path before ByteBuddy tries
            // to inline the advice into target classes like InetAddress.
            BootstrapClassPathSetup.setupBootstrapClassPath(inst);
            instrumentation = inst;

            // Register SLF4J-backed log handlers so that Bootstrap CL advice code
            // (inlined into java.net.Socket, InetAddress, etc.) can log through
            // GlobalRouteState.logInfo/logWarn/logError instead of System.out.
            Logger adviceLogger = LoggerFactory.getLogger("com.baafoo.agent.advice");
            GlobalRouteState.LOG_INFO_HANDLER = (Consumer<String>) adviceLogger::info;
            GlobalRouteState.LOG_WARN_HANDLER = (Consumer<String>) adviceLogger::warn;
            GlobalRouteState.LOG_ERROR_HANDLER = (Consumer<String>) adviceLogger::error;
            GlobalRouteState.LOG_DEBUG_HANDLER = (Consumer<String>) adviceLogger::debug;
            // Also sync handlers to the Bootstrap CL copy of GlobalRouteState
            BootstrapStateSync.syncLogHandlersToBootstrapCL(adviceLogger);

            // P1: Set up PLUGIN_CONSULT_FN_EXT — extended bridge with action semantics.
            // Input: Object[] { String host, Integer port, String protocol }
            // Output: Object[] { Integer action, String targetHost, Integer targetPort, String reason }
            //   action=0: PASSTHROUGH, action=1: REDIRECT, action=2: BLOCK
            //   null: no plugin consulted (proceed with default routing)
            GlobalRouteState.PLUGIN_CONSULT_FN_EXT = (java.util.function.Function<Object[], Object[]>) args -> {
                if (args == null || args.length < 2) return null;
                try {
                    String host = (String) args[0];
                    int port = (Integer) args[1];
                    String protocol = args.length > 2 ? (String) args[2] : "tcp";
                    PluginManager pm = pluginManager;
                    if (pm == null) return null;

                    com.baafoo.plugin.InterceptTarget target;
                    switch (protocol.toLowerCase()) {
                        case "nio":
                        case "nio-socket":
                            target = com.baafoo.plugin.InterceptTarget.NIO_SOCKET;
                            break;
                        case "kafka":
                            target = com.baafoo.plugin.InterceptTarget.KAFKA;
                            break;
                        case "pulsar":
                            target = com.baafoo.plugin.InterceptTarget.PULSAR;
                            break;
                        case "jms":
                            target = com.baafoo.plugin.InterceptTarget.JMS;
                            break;
                        case "grpc":
                            target = com.baafoo.plugin.InterceptTarget.GRPC;
                            break;
                        case "tcp":
                        case "socket":
                        default:
                            target = com.baafoo.plugin.InterceptTarget.SOCKET;
                            break;
                    }
                    com.baafoo.plugin.AgentPlugin plugin = pm.getPlugin(target);
                    if (plugin == null) return null;

                    com.baafoo.plugin.ConnectContext connectCtx =
                            new com.baafoo.plugin.ConnectContext(protocol, host, port, null, null, null, null);
                    com.baafoo.plugin.ConnectAdvice advice = pm.connectWithMonitor(target, connectCtx);

                    switch (advice.getAction()) {
                        case PASSTHROUGH:
                            return new Object[]{0, null, null, null};
                        case REDIRECT:
                            return new Object[]{1, advice.getRedirectHost(), advice.getRedirectPort(), null};
                        case BLOCK:
                            return new Object[]{2, null, null, advice.getBlockReason()};
                        default:
                            return null;
                    }
                } catch (Throwable t) {
                    log.debug("[Baafoo] Plugin consult EXT skipped: {}", t.getMessage());
                    return null;
                }
            };

            // P2: Set up EVENT_FIRE_FN bridge for Bootstrap CL advice.
            // Stored as Consumer<Object> on GlobalRouteState to avoid Bootstrap-CL
            // loading of com.baafoo.plugin.PluginEvent. The App-CL side casts back.
            GlobalRouteState.EVENT_FIRE_FN = (java.util.function.Consumer<Object>) event -> {
                PluginManager pm = pluginManager;
                if (pm != null && event instanceof com.baafoo.plugin.PluginEvent) {
                    pm.fireEvent((com.baafoo.plugin.PluginEvent) event);
                }
            };

            // Set up recording infrastructure
            initRecording(config);

            classFileTransformer = TransformInstaller.installTransforms(config, inst);

            // Start the control channel AFTER transforms are installed.
            // See the note at controlChannel construction above: deferring
            // start() ensures HttpClient is loaded after ByteBuddy transforms
            // are in place, avoiding the need for class retransformation.
            controlChannel.start();

            AgentManifest.agentLoaded = true;
            initialized = true;

            log.info("=== Baafoo Agent started successfully ===");

        } catch (Throwable e) {
            boolean failOpen = config != null && config.isFailOpen();
            if (failOpen) {
                log.warn("Baafoo Agent initialization failed (fail-open mode). " +
                        "All requests will pass through silently. Error: {}", e.getMessage());
            } else {
                // L7: The agent is a bytecode-instrumenting sidecar, not a
                // firewall — there is no "fail-closed" path that could block
                // traffic without breaking the host app. When initialization
                // fails the actual runtime behavior is identical to fail-open
                // (passthrough): no interception, requests reach real
                // downstreams. The previous wording ("fail-closed") was
                // misleading because it implied traffic would be blocked.
                // Use "fail-silent (passthrough)" to honestly describe the
                // outcome: the agent silently does nothing.
                log.error("FAILED to start Baafoo Agent (fail-silent, passthrough). " +
                        "No interception will be applied; all requests will pass through to real downstreams. Error: {}", e.getMessage(), e);
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
        AgentManifest.setGrpcPort(sc.getGrpcPort());

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
            int port = parseSessionPort(sessionInfo[2]);
            if (port < 0) return in;
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
            int port = parseSessionPort(sessionInfo[2]);
            if (port < 0) return out;
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
            int port = parseSessionPort(sessionInfo[2]);
            if (port < 0) return;
            String inferredProtocol = GlobalRouteState.inferProtocol(host, port);
            RecordingEntry entry = new RecordingEntry();
            entry.setSessionId(sessionInfo[0]);
            entry.setHost(host);
            entry.setPort(port);
            entry.setProtocol(inferredProtocol);
            entry.setDirection(direction);
            // For TCP (and unidentified stream protocols), borrow the path field
            // for "host:port" so the recordings/logs list shows a meaningful
            // identifier. Higher-level protocols (http/kafka/jms/grpc/pulsar) have
            // their own dedicated handlers that set a real path — skip those.
            if (inferredProtocol == null || inferredProtocol.isEmpty()
                    || "tcp".equalsIgnoreCase(inferredProtocol)
                    || "udp".equalsIgnoreCase(inferredProtocol)) {
                entry.setPath(host + ":" + port);
            }
            entry.setDataHex(hexData);
            entry.setRecordedAt(System.currentTimeMillis());
            recordingBuffer.add(entry);
        };

        // Sync wrapper functions to Bootstrap CL
        BootstrapStateSync.syncRecordingWrappersToBootstrapCL();

        log.info("Recording stream wrappers configured and synced to Bootstrap CL");
    }

    /**
     * Parse the port component of a recording sessionInfo array.
     *
     * <p>sessionInfo[2] is normally a numeric port string but is produced by
     * advice code that intercepts arbitrary socket connect calls; malformed
     * values (null, non-numeric, empty) must not propagate as an uncaught
     * {@link NumberFormatException} — that would tear down the bridge
     * function and silently disable recording for the affected channel.
     * Returns -1 on failure so the caller can skip recording gracefully.</p>
     */
    private static int parseSessionPort(String portStr) {
        if (portStr == null || portStr.isEmpty()) {
            log.warn("Empty port in sessionInfo, skipping recording");
            return -1;
        }
        try {
            return Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            log.warn("Non-numeric port '{}' in sessionInfo, skipping recording", portStr);
            return -1;
        }
    }
}
