package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * P1-2: Recording session tracker.
 *
 * <p>Encapsulates the recording-session tracking previously inlined in
 * {@link GlobalRouteState}. The {@code RECORDING_SESSIONS} map and the
 * stream wrapper bridge functions ({@code INPUT_STREAM_WRAPPER},
 * {@code OUTPUT_STREAM_WRAPPER}, {@code NIO_RECORDING_HANDLER}) stay on
 * {@code GlobalRouteState} for Bootstrap-CL compatibility (advice reads
 * them by name); this class provides typed accessors and lifecycle
 * helpers that operate on those fields.</p>
 */
public final class RecordingTracker {

    /** Maximum number of concurrent recording sessions (prevents memory leak). */
    private static final int MAX_RECORDING_SESSIONS = 10000;

    /**
     * Register a socket for recording. Called from SocketConnectAdvice in
     * RECORD / RECORD_AND_STUB / RECORD_ALL modes.
     *
     * @param socketIdentity {@code System.identityHashCode} of the socket
     * @param sessionId      unique session ID for this recording
     * @param host           original target host
     * @param port           original target port
     */
    public void startRecording(int socketIdentity, String sessionId, String host, int port) {
        ConcurrentHashMap<Integer, String[]> sessions = GlobalRouteState.RECORDING_SESSIONS;
        if (sessions.size() >= MAX_RECORDING_SESSIONS) {
            sessions.clear();
        }
        sessions.put(socketIdentity, new String[]{sessionId, host, String.valueOf(port)});
    }

    /**
     * Remove a socket from recording tracking.
     *
     * @param socketIdentity {@code System.identityHashCode} of the socket
     */
    public void stopRecording(int socketIdentity) {
        GlobalRouteState.RECORDING_SESSIONS.remove(socketIdentity);
    }

    /**
     * Check if a socket is being recorded.
     *
     * @param socketIdentity {@code System.identityHashCode} of the socket
     * @return session info array {@code {sessionId, host, portString}} or {@code null}
     */
    public String[] getRecordingSession(int socketIdentity) {
        return GlobalRouteState.RECORDING_SESSIONS.get(socketIdentity);
    }

    /** @return the live recording-sessions map (keyed by socket identity hash). */
    public ConcurrentHashMap<Integer, String[]> getActiveSessions() {
        return GlobalRouteState.RECORDING_SESSIONS;
    }

    /**
     * Add NIO recording data (called from SocketChannelReadAdvice /
     * SocketChannelWriteAdvice). Delegates to the
     * {@code NIO_RECORDING_HANDLER} bridge function set by the App CL.
     *
     * @param sessionInfo {@code {sessionId, host, portString}}
     * @param direction   "request" or "response"
     * @param hexData     hex string of recorded bytes
     */
    public void addNioRecording(String[] sessionInfo, String direction, String hexData) {
        java.util.function.Consumer<Object[]> handler = GlobalRouteState.NIO_RECORDING_HANDLER;
        if (handler != null) {
            handler.accept(new Object[]{sessionInfo, direction, hexData});
        }
    }
}
