package com.baafoo.agent.advice;

import com.baafoo.agent.BaafooAgent;
import com.baafoo.core.config.AgentConfig;
import com.baafoo.core.model.EnvironmentMode;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Byte Buddy Advice for {@code ClientBuilder.serviceUrl(String)}.
 *
 * <p>Intercepts the Pulsar client builder's {@code serviceUrl()} method
 * to replace the broker URL with the Baafoo stub Pulsar broker
 * (pulsar://localhost:9003 by default).</p>
 *
 * <p>This approach is superior to intercepting createProducer/subscribe
 * because it redirects ALL Pulsar protocol traffic (connections, lookups,
 * producers, consumers) to the stub broker at the earliest possible point.</p>
 *
 * <p>Per plugin-arch-advice.md, the actual Pulsar protocol handling
 * is delegated to the Pulsar Plugin loaded by a separate ClassLoader
 * to avoid SDK dependency conflicts.</p>
 *
 * <p>Pulsar interception is Beta per PRD v1.5.</p>
 */
public class PulsarClientAdvice {

    private static final Logger log = LoggerFactory.getLogger(PulsarClientAdvice.class);

    /**
     * Intercept ClientBuilder.serviceUrl(String) to replace the Pulsar broker URL.
     * The argument is the service URL string (e.g., "pulsar://localhost:6650").
     */
    @Advice.OnMethodEnter
    public static void onServiceUrl(
            @Advice.Argument(value = 0, readOnly = false) String serviceUrl) {

        try {
            // Only intercept in stub/recording modes
            EnvironmentMode mode = RouteManager.getMode();
            if (mode == EnvironmentMode.PASSTHROUGH) {
                return;
            }

            // Check if Pulsar is in the routing table
            RouteManager.RouteResult routeResult = RouteManager.route(
                    "pulsar", "pulsar-broker", 0, null,
                    null, null,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null);

            if (routeResult.matched) {
                AgentConfig config = BaafooAgent.getConfig();
                String stubHost = "127.0.0.1";
                int stubPort = getStubPort();

                String newServiceUrl = "pulsar://" + stubHost + ":" + stubPort;
                String originalUrl = serviceUrl;
                serviceUrl = newServiceUrl;

                log.info("Pulsar serviceUrl replaced: {} → {} (rule: {})",
                        originalUrl, newServiceUrl, routeResult.rule.getName());

                RoutingContext.set(routeResult);
            }
        } catch (Exception e) {
            log.error("Error in PulsarClientAdvice: {}", e.getMessage());
            // Fail-closed: let original serviceUrl proceed
        }
    }

    /**
     * Get the stub port for Pulsar protocol.
     */
    private static int getStubPort() {
        // Default Pulsar stub port is 9003
        return 9003;
    }
}
