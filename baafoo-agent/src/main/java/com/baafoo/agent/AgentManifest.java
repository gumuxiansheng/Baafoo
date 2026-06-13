package com.baafoo.agent;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstrap-safe agent manifest — the ONLY bridge between inlined Advice code
 * and the rest of the agent.
 *
 * <p>All fields use types loadable by the Bootstrap ClassLoader:
 * AtomicReference, RouteTable (same package, loaded before transform),
 * String, boolean, int. No Logger, no RouteManager, no Rule, no Enum.</p>
 *
 * <p>Advice classes read from this class exclusively. Background threads
 * (RouteManager, ControlChannel) write to it via atomic swap.</p>
 */
public final class AgentManifest {

    // ---- Mode constants (int, NOT enum — enum would need Bootstrap CL) ----

    /** Stub mode: return pre-configured stub responses */
    public static final int MODE_STUB = 0;

    /** Passthrough mode: forward to real downstream */
    public static final int MODE_PASSTHROUGH = 1;

    /** Record mode: record real responses for later replay */
    public static final int MODE_RECORD = 2;

    /** Record-and-stub mode: record AND return stubs */
    public static final int MODE_RECORD_AND_STUB = 3;

    // ---- Atomic route table (swap for hot-reload) ----

    /** Current route table, atomically swappable */
    public static final AtomicReference<RouteTable> ROUTE_TABLE =
            new AtomicReference<RouteTable>(new RouteTable());

    // ---- Agent state ----

    /** Current agent mode (volatile for visibility) */
    public static volatile int currentMode = MODE_STUB;

    /** Whether the agent has been loaded successfully */
    public static volatile boolean agentLoaded = false;

    /** Baafoo server host (e.g., "127.0.0.1") */
    public static volatile String serverHost = "127.0.0.1";

    /** Baafoo server port (e.g., 8084) */
    public static volatile int serverPort = 8084;

    /** Environment ID this agent belongs to */
    public static volatile String environmentId = "default";

    /** Agent ID assigned by the server */
    public static volatile String agentId = "";

    private AgentManifest() {}

    public static void setServerHost(String host) {
        serverHost = host;
        com.baafoo.agent.GlobalRouteState.SERVER_HOST = host;
    }

    public static void setServerPort(int port) {
        serverPort = port;
        com.baafoo.agent.GlobalRouteState.SERVER_PORT = port;
    }

    public static void setCurrentMode(int mode) {
        currentMode = mode;
        com.baafoo.agent.GlobalRouteState.CURRENT_MODE = mode;
    }

    /**
     * Check if current mode is passthrough (i.e., should we skip interception).
     *
     * @return true if in passthrough mode
     */
    public static boolean isPassthrough() {
        return currentMode == MODE_PASSTHROUGH;
    }

    /**
     * Check if current mode involves recording.
     *
     * @return true if recording is active
     */
    public static boolean isRecording() {
        return currentMode == MODE_RECORD || currentMode == MODE_RECORD_AND_STUB;
    }

    /**
     * Get mode name string for logging.
     *
     * @return mode name
     */
    public static String getModeName() {
        switch (currentMode) {
            case 0: return "stub";
            case 1: return "passthrough";
            case 2: return "record";
            case 3: return "record-and-stub";
            default: return "unknown";
        }
    }
}
