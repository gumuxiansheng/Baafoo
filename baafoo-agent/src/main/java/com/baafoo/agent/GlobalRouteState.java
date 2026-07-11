package com.baafoo.agent;

import com.baafoo.agent.state.DnsCache;
import com.baafoo.agent.state.LogBridge;
import com.baafoo.agent.state.PluginBridge;
import com.baafoo.agent.state.ProtocolMapper;
import com.baafoo.agent.state.RecordingTracker;
import com.baafoo.agent.state.RouteTable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Global route + mode + bridge state for the Baafoo agent.
 *
 * <p><b>P1-2 Facade:</b> This class is now a Facade over six manager classes in
 * {@code com.baafoo.agent.state} — {@link RouteTable}, {@link DnsCache},
 * {@link RecordingTracker}, {@link LogBridge}, {@link PluginBridge}, and
 * {@link ProtocolMapper}. The actual logic now lives in those managers; the
 * public static methods below delegate to them.</p>
 *
 * <p><b>Bootstrap ClassLoader constraint (critical):</b> This class is packaged
 * into the Bootstrap JAR by {@code BaafooAgent.createBootstrapJar()} and loaded
 * by BOTH the Bootstrap CL and the App CL. The two copies are different class
 * objects; their static fields are kept in sync via reflection (see
 * {@code BaafooAgent.syncGlobalRouteStateToBootstrapCL()}). ByteBuddy-inlined
 * advice running in JDK classes (java.net.Socket, java.net.InetAddress,
 * sun.nio.ch.SocketChannelImpl) resolves {@code GlobalRouteState} via the
 * Bootstrap CL.</p>
 *
 * <p>Because of this:</p>
 * <ul>
 *   <li>All existing {@code public static volatile} fields are KEPT with their
 *       original names and JDK-only types. Bootstrap-CL advice reads them by
 *       field name; renaming or removing any field breaks the inlined advice.</li>
 *   <li>The six manager classes ({@link RouteTable}, {@link DnsCache}, etc.)
 *       live in {@code com.baafoo.agent.state} and are packaged into the
 *       Bootstrap JAR by {@code BaafooAgent.createBootstrapJar()}. They are
 *       therefore loadable on BOTH the App CL and the Bootstrap CL, and the
 *       static block below instantiates them on both. The managers are
 *       stateless — every method operates on {@code GlobalRouteState}'s own
 *       static fields, which are kept in sync across the two CLs by the five
 *       reflection sync methods in {@code BaafooAgent} / {@code RouteManager} —
 *       so no additional cross-CL sync is required for the managers.</li>
 *   <li>The {@link #logError(String)} / {@link #logInfo(String)} /
 *       {@link #logWarn(String)} / {@link #logDebug(String)} methods are kept
 *       inline (they do not delegate to {@link LogBridge}) so that advice
 *       catch-blocks can still emit diagnostics even if a manager is somehow
 *       null (e.g. a future packaging regression drops the state classes from
 *       the Bootstrap JAR).</li>
 * </ul>
 */
public final class GlobalRouteState {

    public static final class HostPort {
        public final String host;
        public final int port;

        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static volatile ConcurrentHashMap<String, HostPort> ROUTES = new ConcurrentHashMap<String, HostPort>();

    public static volatile int CURRENT_MODE = 0;

    public static final int MODE_STUB = 0;
    public static final int MODE_PASSTHROUGH = 1;
    public static final int MODE_RECORD = 2;
    public static final int MODE_RECORD_AND_STUB = 3;
    public static final int MODE_RECORD_ALL = 4;

    public static volatile String SERVER_HOST = "127.0.0.1";

    /** Resolved IP address of SERVER_HOST (e.g., Docker container IP).
     *  Set lazily when DNS resolution succeeds. Used by isInternal() to
     *  recognize connections to the server via its container IP. */
    public static volatile String SERVER_HOST_IP = null;

    public static volatile int SERVER_PORT = 8084;

    // ---- Protocol stub ports (set from AgentConfig, synced to Bootstrap CL) ----

    /** HTTP stub port (default 9000) */
    public static volatile int HTTP_PORT = 9000;

    /** TCP stub port (default 9001) */
    public static volatile int TCP_PORT = 9001;

    /** Kafka stub port (default 9002) */
    public static volatile int KAFKA_PORT = 9002;

    /** Pulsar stub port (default 9003) */
    public static volatile int PULSAR_PORT = 9003;

    /** JMS stub port (default 9004) */
    public static volatile int JMS_PORT = 9004;

    /** gRPC stub port (default 9005) */
    public static volatile int GRPC_PORT = 9005;

    // ---- Logging bridge ----
    // Set by the App CL side (BaafooAgent) with SLF4J-backed implementations.
    // Advice code inlined into Bootstrap CL classes calls logInfo/logWarn/logError/logDebug,
    // which delegate to these handlers. Falls back to System.out when not set.

    /** @see #logInfo(String) */
    public static volatile Consumer<String> LOG_INFO_HANDLER;

    /** @see #logWarn(String) */
    public static volatile Consumer<String> LOG_WARN_HANDLER;

    /** @see #logError(String) */
    public static volatile Consumer<String> LOG_ERROR_HANDLER;

    /** @see #logDebug(String) */
    public static volatile Consumer<String> LOG_DEBUG_HANDLER;

    /**
     * DNS resolution cache: maps resolved IP addresses back to original domain names.
     * Populated when InetAddress.getByName is intercepted.
     * Used in SocketConnectAdvice/NioSocketConnectAdvice to look up routes by domain
     * with an IP address instead of the original hostname.
     *
     * Bounded at {@code MAX_DNS_CACHE_SIZE} entries to prevent memory leak.
     * Eviction strategy: when full, removes all entries — this is a best-effort
     * cache for route-lookup fallback, so occasional full clears are acceptable.
     */
    public static final ConcurrentHashMap<String, String> DNS_CACHE = new ConcurrentHashMap<String, String>();

    /**
     * ThreadLocal for passing DNS redirect target from OnMethodEnter to OnMethodExit.
     * Set when a hostname matches a route, so that if DNS resolution fails,
     * we can provide a fake resolution pointing to the stub server.
     */
    public static final ThreadLocal<String> DNS_REDIRECT_TARGET = new ThreadLocal<String>();

    /**
     * Re-entry guard for DnsResolveAdvice / DnsResolveAllAdvice.
     *
     * <p>Set to {@code Boolean.TRUE} while the advice is in the middle of
     * resolving a redirect target via {@link InetAddress#getByName(String)}.
     * Without this guard, if the redirect target host (typically
     * {@link #SERVER_HOST}) also matches a service-name or host route, the
     * advice would re-enter itself recursively until StackOverflowError.</p>
     *
     * <p><b>Must live here (GlobalRouteState) rather than in the advice class</b>:
     * the advice is inlined by ByteBuddy into {@link java.net.InetAddress}
     * (a Bootstrap ClassLoader class). Static field accesses are not inlined,
     * so the field must be reachable from the Bootstrap CL. GlobalRouteState
     * is packaged into the Bootstrap JAR by {@code BaafooAgent.createBootstrapJar()},
     * making it visible from both CLs.</p>
     *
     * <p><b>No anonymous subclass</b>: written as a plain {@code new ThreadLocal<>()}
     * (no {@code initialValue} override) so the compiler does not synthesize a
     * separate {@code GlobalRouteState$1} class — that synthetic class is not in
     * the Bootstrap JAR's classResources list, so referencing it from inlined
     * advice would throw {@code NoClassDefFoundError}. Callers must treat
     * {@code null} as {@code false} via {@code Boolean.TRUE.equals(guard.get())}.</p>
     */
    public static final ThreadLocal<Boolean> DNS_REENTRY_GUARD = new ThreadLocal<Boolean>();

    // ---- Recording session tracking ----
    // Maps socket identity (System.identityHashCode) to session info:
    // String[] { sessionId, host, portString }
    // Populated by SocketConnectAdvice in RECORD/RECORD_AND_STUB mode.
    // Consumed by SocketGetStreamAdvice to wrap streams with recording.

    /**
     * Active recording sessions keyed by socket identity hash.
     * Value is String[] { sessionId, host, portString }.
     */
    public static final ConcurrentHashMap<Integer, String[]> RECORDING_SESSIONS =
            new ConcurrentHashMap<Integer, String[]>();

    /**
     * Bridge function to wrap an InputStream with recording.
     * Set from the App CL (BaafooAgent) with a real implementation.
     * Arguments: (InputStream, String[] sessionInfo where sessionInfo = {sessionId, host, portString})
     * Returns: wrapped InputStream.
     * If null, no recording wrapping is applied.
     */
    public static volatile java.util.function.BiFunction<java.io.InputStream, String[], java.io.InputStream> INPUT_STREAM_WRAPPER;

    /**
     * Bridge function to wrap an OutputStream with recording.
     * Set from the App CL (BaafooAgent) with a real implementation.
     * Arguments: (OutputStream, String[] sessionInfo where sessionInfo = {sessionId, host, portString})
     * Returns: wrapped OutputStream.
     * If null, no recording wrapping is applied.
     */
    public static volatile java.util.function.BiFunction<java.io.OutputStream, String[], java.io.OutputStream> OUTPUT_STREAM_WRAPPER;

    /**
     * Bridge function for NIO SocketChannel recording.
     * Set from the App CL (BaafooAgent) with a real implementation.
     * Arguments: Object[] { String[] sessionInfo, String direction, String hexData }
     * where sessionInfo = {sessionId, host, portString}.
     * If null, NIO recording data is silently dropped.
     */
    public static volatile java.util.function.Consumer<Object[]> NIO_RECORDING_HANDLER;

    /**
     * Extended plugin consultation function (P1).
     *
     * <p>Arguments: Object[] { String host, Integer port, String protocol }</p>
     * <p>Returns: Object[] { Integer action, String targetHost, Integer targetPort, String reason }</p>
     * <ul>
     *   <li>action=0: PASSTHROUGH (targetHost/targetPort ignored)</li>
     *   <li>action=1: REDIRECT to targetHost:targetPort</li>
     *   <li>action=2: BLOCK with reason</li>
     *   <li>null: no plugin consulted (proceed with default routing)</li>
     * </ul>
     */
    public static volatile java.util.function.Function<Object[], Object[]> PLUGIN_CONSULT_FN_EXT;

    /**
     * P2: Event fire bridge for Bootstrap CL advice.
     * Set from App CL (BaafooAgent) to forward events to PluginManager.fireEvent().
     *
     * <p>Typed as {@code Consumer<Object>} intentionally: the Bootstrap CL copy
     * of GlobalRouteState must not reference {@code com.baafoo.plugin.PluginEvent}
     * directly. Bootstrap-CL advice passes the event as an opaque Object; the
     * App-CL side casts it back to PluginEvent before dispatching.</p>
     */
    public static volatile java.util.function.Consumer<Object> EVENT_FIRE_FN;

    // ---- P1-2: manager instances ----
    //
    // These are App-CL only. The Bootstrap CL copy of GlobalRouteState cannot
    // load com.baafoo.agent.state.* (those classes are intentionally excluded
    // from the Bootstrap JAR), so the static block below leaves them null on
    // the Bootstrap CL. The delegating methods will throw NoClassDefFoundError
    // when invoked from Bootstrap-CL advice; the advice's try/catch blocks
    // handle that. See the class javadoc for the full rationale.

    private static volatile RouteTable routeTable;
    private static volatile DnsCache dnsCache;
    private static volatile RecordingTracker recordingTracker;
    private static volatile LogBridge logBridge;
    private static volatile PluginBridge pluginBridge;
    private static volatile ProtocolMapper protocolMapper;

    static {
        try {
            routeTable = new RouteTable();
            dnsCache = new DnsCache();
            recordingTracker = new RecordingTracker();
            logBridge = new LogBridge();
            pluginBridge = new PluginBridge();
            protocolMapper = new ProtocolMapper();
        } catch (Throwable t) {
            // Defensive: the six manager classes are packaged into the Bootstrap
            // JAR by BaafooAgent.createBootstrapJar() so this block succeeds on
            // both the App CL and the Bootstrap CL. If a future packaging
            // change drops them from the Bootstrap JAR, the managers stay null
            // here and delegating methods will throw — advice catch blocks
            // degrade gracefully (connection proceeds without interception),
            // but socket/NIO/DNS interception will be silently disabled. The
            // Bootstrap JAR's classResources array is the source of truth.
        }
    }

    private GlobalRouteState() {}

    // ---- Logging methods for Bootstrap CL advice ----
    //
    // P1-2: these are KEPT INLINE (they do not delegate to LogBridge) so that
    // Bootstrap-CL advice catch-blocks can still emit diagnostics when a
    // delegating method throws NoClassDefFoundError. LogBridge exposes the
    // same logic for App-CL callers.

    public static void logInfo(String msg) {
        Consumer<String> h = LOG_INFO_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    public static void logWarn(String msg) {
        Consumer<String> h = LOG_WARN_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    public static void logError(String msg) {
        Consumer<String> h = LOG_ERROR_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    public static void logDebug(String msg) {
        Consumer<String> h = LOG_DEBUG_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    /** @return the LogBridge manager (App-CL only; null on the Bootstrap CL). */
    public static LogBridge getLogBridge() {
        return logBridge;
    }

    // ---- Plugin event bridge ----

    /**
     * P2: Fire a plugin event through the bridge to PluginManager.
     * Safe to call from Bootstrap CL advice code.
     *
     * <p>The event is accepted as {@code Object} so that Bootstrap-CL advice
     * (which cannot load {@code com.baafoo.plugin.PluginEvent}) can still
     * trigger the bridge. The App-CL implementation casts it back safely.</p>
     *
     * @param event the plugin event to fire (typically a PluginEvent instance)
     */
    public static void firePluginEvent(Object event) {
        // P1-2: delegates to PluginBridge
        pluginBridge.fireEvent(event);
    }

    /** @return the PluginBridge manager (App-CL only; null on the Bootstrap CL). */
    public static PluginBridge getPluginBridge() {
        return pluginBridge;
    }

    // ---- DNS cache ----

    /**
     * Record a DNS resolution for later route lookup.
     *
     * @param domain the original domain name (e.g., "api.example.com")
     * @param ip     the resolved IP address (e.g., "93.184.216.34")
     */
    public static void recordDns(String domain, String ip) {
        // P1-2: delegates to DnsCache
        dnsCache.recordDns(domain, ip);
    }

    /** @return the DnsCache manager (App-CL only; null on the Bootstrap CL). */
    public static DnsCache getDnsCache() {
        return dnsCache;
    }

    // ---- Route table ----

    public static String[] lookup(String host, int port) {
        // P1-2: delegates to RouteTable
        return routeTable.lookup(host, port);
    }

    public static HostPort lookupByHost(String host) {
        // P1-2: delegates to RouteTable
        return routeTable.lookupByHost(host);
    }

    public static HostPort lookupService(String serviceName) {
        // P1-2: delegates to RouteTable
        return routeTable.lookupService(serviceName);
    }

    public static void addRoute(String originalHost, int originalPort, String targetHost, int targetPort) {
        // P1-2: delegates to RouteTable
        routeTable.addRoute(originalHost, originalPort, targetHost, targetPort);
    }

    public static void addService(String serviceName, String targetHost, int targetPort) {
        // P1-2: delegates to RouteTable
        routeTable.addService(serviceName, targetHost, targetPort);
    }

    public static void clearRoutes() {
        // P1-2: delegates to RouteTable
        routeTable.clear();
    }

    /** @return the RouteTable manager (App-CL only; null on the Bootstrap CL). */
    public static RouteTable getRouteTable() {
        return routeTable;
    }

    // ---- Mode + protocol mapping ----

    public static boolean isPassthrough() {
        // P1-2: delegates to ProtocolMapper
        return protocolMapper.isPassthrough();
    }

    public static boolean isRecording() {
        // P1-2: delegates to ProtocolMapper
        return protocolMapper.isRecording();
    }

    /**
     * Infer a fallback stub port from the destination port when no route matches.
     * Used in RECORD_ALL mode to redirect unmatched traffic to Baafoo for recording.
     *
     * @param port destination port in the original connection attempt
     * @return stub port number (never -1)
     */
    public static int forceRedirectPort(int port) {
        // P1-2: delegates to ProtocolMapper
        return protocolMapper.forceRedirectPort(port);
    }

    public static boolean isInternal(String host, int port) {
        // P1-2: delegates to ProtocolMapper
        return protocolMapper.isInternal(host, port);
    }

    // ---- Mode dispatch helpers (P1-1) ----
    //
    // These static int-based helpers consolidate the duplicated mode-ordinal
    // checks scattered across SocketConnectAdvice / NioSocketConnectAdvice /
    // NioSocketFinishConnectAdvice. They live on GlobalRouteState so they
    // are reachable from Bootstrap-CL-inlined advice (no baafoo-core types
    // referenced, no relocation concerns). ByteBuddy-inlined bytecode can
    // safely call these static methods.

    /** True unless the active mode is PASSTHROUGH (i.e., the agent should intercept). */
    public static boolean shouldIntercept(int mode) {
        // P1-2: delegates to ProtocolMapper
        return protocolMapper.shouldIntercept(mode);
    }

    /**
     * True for modes that record the matched request/response at the stream
     * level (RECORD, RECORD_AND_STUB, RECORD_ALL). Used by the Bootstrap-CL
     * socket advice to decide whether to register a stream-recording session.
     */
    public static boolean shouldRecordStream(int mode) {
        // P1-2: delegates to ProtocolMapper
        return protocolMapper.shouldRecordStream(mode);
    }

    /**
     * True for RECORD_AND_STUB mode, where the matched connection must be
     * redirected to the stub port (in addition to recording).
     */
    public static boolean isRecordAndStub(int mode) {
        // P1-2: delegates to ProtocolMapper
        return protocolMapper.isRecordAndStub(mode);
    }

    /**
     * True for RECORD_ALL mode, where unmatched traffic should also be
     * redirected to a stub port (for HTTP/MQ) or recorded as raw bytes
     * (for generic TCP).
     */
    public static boolean shouldRedirectUnmatched(int mode) {
        // P1-2: delegates to ProtocolMapper
        return protocolMapper.shouldRedirectUnmatched(mode);
    }

    /**
     * Infer the high-level protocol name from a connection's target host:port.
     *
     * <p>Socket-level recording only sees raw TCP bytes, but a connection can be
     * mapped back to a protocol via the stub port it was redirected to:
     * <ul>
     *   <li>9000 → http</li>
     *   <li>9001 → tcp</li>
     *   <li>9002 → kafka</li>
     *   <li>9003 → pulsar</li>
     *   <li>9004 → jms</li>
     * </ul>
     * For connections to an internal stub port the port itself identifies the
     * protocol; for external connections the route table is consulted to find
     * the redirect target port (with a DNS-cache fallback for Docker/IP cases).
     * Returns {@code "tcp"} when no mapping is found.</p>
     */
    public static String inferProtocol(String host, int port) {
        // P1-2: delegates to ProtocolMapper
        return protocolMapper.inferProtocol(host, port);
    }

    /** @return the ProtocolMapper manager (App-CL only; null on the Bootstrap CL). */
    public static ProtocolMapper getProtocolMapper() {
        return protocolMapper;
    }

    // ---- Recording session tracking ----

    /**
     * Register a socket for recording. Called from SocketConnectAdvice in RECORD mode.
     * @param socketIdentity System.identityHashCode of the socket
     * @param sessionId unique session ID for this recording
     * @param host original target host
     * @param port original target port
     */
    public static void startRecording(int socketIdentity, String sessionId, String host, int port) {
        // P1-2: delegates to RecordingTracker
        recordingTracker.startRecording(socketIdentity, sessionId, host, port);
    }

    /**
     * Remove a socket from recording tracking.
     * @param socketIdentity System.identityHashCode of the socket
     */
    public static void stopRecording(int socketIdentity) {
        // P1-2: delegates to RecordingTracker
        recordingTracker.stopRecording(socketIdentity);
    }

    /**
     * Check if a socket is being recorded.
     * @param socketIdentity System.identityHashCode of the socket
     * @return session info array or null
     */
    public static String[] getRecordingSession(int socketIdentity) {
        // P1-2: delegates to RecordingTracker
        return recordingTracker.getRecordingSession(socketIdentity);
    }

    /**
     * Add NIO recording data (called from SocketChannelReadAdvice/SocketChannelWriteAdvice).
     * Delegates to the NIO_RECORDING_HANDLER bridge function set by the App CL.
     * @param sessionInfo {sessionId, host, portString}
     * @param direction "request" or "response"
     * @param hexData hex string of recorded bytes
     */
    public static void addNioRecording(String[] sessionInfo, String direction, String hexData) {
        // P1-2: delegates to RecordingTracker
        recordingTracker.addNioRecording(sessionInfo, direction, hexData);
    }

    /** @return the RecordingTracker manager (App-CL only; null on the Bootstrap CL). */
    public static RecordingTracker getRecordingTracker() {
        return recordingTracker;
    }
}
