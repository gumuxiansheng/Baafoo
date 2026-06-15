package com.baafoo.plugin.tdmq;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;

/**
 * TDMQ / Pulsar 2.7.4 protocol-adapter plugin.
 *
 * <p>Redirects Pulsar client connections whose target is a remote broker to the
 * TDMQ stub broker ({@code localhost:9005}) instead of the default Pulsar stub
 * port. This lets a deployment run a dedicated Pulsar-compatible mock on 9005
 * (e.g. a real Pulsar 2.7.4 instance acting as TDMQ) distinct from the built-in
 * mock on 9003.</p>
 *
 * <p>The plugin uses the {@link InterceptResult#redirect(String, int)} result —
 * it only declares the connection target; the actual Pulsar binary protocol
 * handshake is handled by the broker at the redirect target. Returning a
 * {@code stub(bytes, ...)} here would be wrong because Pulsar is a binary
 * protocol with no "response body" to inject at connection-establishment time.</p>
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

    @Override
    public InterceptResult intercept(PluginContext ctx) {
        String protocol = ctx.getProtocol();
        String host = ctx.getHost();

        // Only intercept Pulsar connections to non-local brokers. A local target
        // (localhost / 127.0.0.1) is assumed to already point at a stub, so we
        // leave it alone to avoid a redirect loop.
        if ("pulsar".equalsIgnoreCase(protocol) && host != null && !isLocalAddress(host)) {
            return InterceptResult.redirect("localhost", TDMQ_BROKER_PORT);
        }
        return InterceptResult.passthrough();
    }

    @Override
    public void destroy() {
        System.out.println("[TDMQ Plugin] Destroyed");
    }

    /** True for loopback targets that must not be redirected. */
    private static boolean isLocalAddress(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }
}
