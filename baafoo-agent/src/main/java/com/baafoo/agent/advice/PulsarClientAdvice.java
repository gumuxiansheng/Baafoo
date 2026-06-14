package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import com.baafoo.core.model.EnvironmentMode;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Byte Buddy Advice for {@code ClientBuilder.serviceUrl(String)}.
 *
 * <p>Intercepts the Pulsar client builder's {@code serviceUrl()} method
 * to replace the broker URL with the Baafoo stub Pulsar broker
 * (pulsar://localhost:9003 by default).</p>
 *
 * <p><b>CRITICAL</b>: This advice is inlined into Pulsar ClientBuilder by ByteBuddy.
 * Do NOT reference any private fields from this class in the advice
 * method — inlined code runs in the target class's context and cannot access
 * private fields of the advice class. The Logger field MUST be public.</p>
 */
public class PulsarClientAdvice {

    /** Must be public — inlined code in the target class cannot access private fields. */
    public static final Logger log = LoggerFactory.getLogger(PulsarClientAdvice.class);

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

            // Check if there are ANY Pulsar routes in the routing table
            // We intercept all Pulsar connections when Pulsar routes exist
            boolean hasPulsarRoutes = RouteManager.hasProtocolRoutes("pulsar");

            if (hasPulsarRoutes) {
                String stubHost = GlobalRouteState.SERVER_HOST;
                int stubPort = GlobalRouteState.PULSAR_PORT;

                String newServiceUrl = "pulsar://" + stubHost + ":" + stubPort;
                String originalUrl = serviceUrl;
                serviceUrl = newServiceUrl;

                log.info("[Baafoo] Pulsar serviceUrl replaced: {} -> {}", originalUrl, newServiceUrl);
            }
        } catch (Exception e) {
            log.error("[Baafoo] PulsarClientAdvice error: {}", e.getMessage());
            // Fail-closed: let original serviceUrl proceed
        }
    }
}
