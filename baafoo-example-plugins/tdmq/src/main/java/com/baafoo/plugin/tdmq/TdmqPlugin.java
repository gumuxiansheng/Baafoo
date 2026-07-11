package com.baafoo.plugin.tdmq;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginEvent;

/**
 * TDMQ / Pulsar 2.7.4 protocol-adapter plugin.
 *
 * <p>Redirects Pulsar client connections whose target is a remote broker to the
 * TDMQ stub broker ({@code localhost:9005}) instead of the default Pulsar stub
 * port. This lets a deployment run a dedicated Pulsar-compatible mock on 9005
 * (e.g. a real Pulsar 2.7.4 instance acting as TDMQ) distinct from the built-in
 * mock on 9003.</p>
 *
 * <p>Uses the new phase-specific API hooks:</p>
 * <ul>
 *   <li>{@link #onConnect(ConnectContext)} — connection-phase redirect using
 *       {@link ConnectAdvice#redirect(String, int)}</li>
 *   <li>{@link #onEvent(PluginEvent)} — observation-only event logging</li>
 * </ul>
 *
 * <p>The plugin uses {@link ConnectAdvice#redirect(String, int)} — it only
 * declares the connection target; the actual Pulsar binary protocol handshake
 * is handled by the broker at the redirect target.</p>
 */
public class TdmqPlugin implements AgentPlugin {

    private static final String PLUGIN_NAME = "tdmq";

    /** Dedicated TDMQ stub broker port (distinct from the default PULSAR_PORT 9003). */
    static final int TDMQ_BROKER_PORT = 9005;

    @Override
    public String getName() {
        return PLUGIN_NAME;
    }

    @Override
    public InterceptTarget getTarget() {
        return InterceptTarget.PULSAR;
    }

    @Override
    public void init() {
        // SLF4J is intentionally not used here to keep the plugin-API module
        // dependency-free; the plugin prints via System.out on the App CL.
        System.out.println("[TDMQ Plugin] Initialized for Pulsar/TDMQ 2.7.4");
    }

    // ---- New API hooks ----

    /**
     * Connection-phase hook: redirect Pulsar connections to the TDMQ stub broker.
     *
     * <p>Only intercepts Pulsar connections to non-local brokers. A local target
     * (localhost / 127.0.0.1) is assumed to already point at a stub, so it is
     * left alone to avoid a redirect loop.</p>
     *
     * @param ctx connection context (protocol, host, port)
     * @return {@link ConnectAdvice#redirect} to {@code localhost:9005} for remote
     *         Pulsar targets, {@link ConnectAdvice#passthrough} otherwise
     */
    @Override
    public ConnectAdvice onConnect(ConnectContext ctx) {
        String protocol = ctx.getProtocol();
        String host = ctx.getHost();

        if ("pulsar".equalsIgnoreCase(protocol) && host != null && !isLocalAddress(host)) {
            return ConnectAdvice.redirect("localhost", TDMQ_BROKER_PORT);
        }
        return ConnectAdvice.passthrough();
    }

    /**
     * Observation-only event hook: logs connection redirect events.
     */
    @Override
    public void onEvent(PluginEvent event) {
        if (event.getType() == PluginEvent.Type.CONNECTION_REDIRECTED) {
            System.out.println("[TDMQ Plugin] Event: " + event);
        }
    }

    @Override
    public void destroy() {
        System.out.println("[TDMQ Plugin] Destroyed");
    }

    // ---- Helpers ----

    private static boolean isLocalAddress(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }
}
