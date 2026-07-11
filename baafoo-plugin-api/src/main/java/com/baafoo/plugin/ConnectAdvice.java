package com.baafoo.plugin;

/**
 * Advice returned from the connection-phase hook.
 *
 * <p>Three actions:</p>
 * <ul>
 *   <li>PASSTHROUGH — allow the connection to proceed to the original target</li>
 *   <li>REDIRECT — redirect the connection to a different host:port</li>
 *   <li>BLOCK — block the connection</li>
 * </ul>
 */
public class ConnectAdvice {

    public enum Action {
        /** Allow the connection to proceed to the original target */
        PASSTHROUGH,
        /** Redirect the connection to a different host:port */
        REDIRECT,
        /** Block the connection */
        BLOCK
    }

    private final Action action;
    private final String redirectHost;
    private final int redirectPort;
    private final String blockReason;

    private ConnectAdvice(Action action, String redirectHost, int redirectPort, String blockReason) {
        this.action = action;
        this.redirectHost = redirectHost;
        this.redirectPort = redirectPort;
        this.blockReason = blockReason;
    }

    public static ConnectAdvice passthrough() {
        return new ConnectAdvice(Action.PASSTHROUGH, null, 0, null);
    }

    public static ConnectAdvice redirect(String host, int port) {
        return new ConnectAdvice(Action.REDIRECT, host, port, null);
    }

    public static ConnectAdvice block(String reason) {
        return new ConnectAdvice(Action.BLOCK, null, 0, reason);
    }

    // --- Getters ---

    public Action getAction() { return action; }
    public String getRedirectHost() { return redirectHost; }
    public int getRedirectPort() { return redirectPort; }
    public String getBlockReason() { return blockReason; }

    public boolean isPassthrough() { return action == Action.PASSTHROUGH; }
    public boolean isRedirect() { return action == Action.REDIRECT; }
    public boolean isBlock() { return action == Action.BLOCK; }
}
