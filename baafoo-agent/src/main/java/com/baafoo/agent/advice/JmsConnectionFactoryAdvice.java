package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import com.baafoo.core.model.EnvironmentMode;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Byte Buddy Advice for JMS {@code ConnectionFactory.createConnection()} methods.
 *
 * <p>Intercepts ActiveMQ {@code ActiveMQConnectionFactory} construction to replace
 * the broker URL with the Baafoo JMS Mock Broker address (port 9004 by default).</p>
 *
 * <p>This covers the most common JMS client: Apache ActiveMQ. Other JMS providers
 * (IBM MQ, Tibco EMS, etc.) can be added as needed.</p>
 *
 * <p><b>CRITICAL</b>: This advice is inlined into ActiveMQConnectionFactory by ByteBuddy.
 * Do NOT reference any private fields from this class in the advice
 * method — inlined code runs in the target class's context and cannot access
 * private fields of the advice class. The Logger field MUST be public.</p>
 */
public class JmsConnectionFactoryAdvice {

    /** Must be public — inlined code in the target class cannot access private fields. */
    public static final Logger log = LoggerFactory.getLogger(JmsConnectionFactoryAdvice.class);

    /**
     * Intercept ActiveMQConnectionFactory constructor to replace the brokerURL.
     * The first argument is typically the brokerURL string (e.g., "tcp://localhost:61616").
     */
    @Advice.OnMethodEnter
    public static void onConstructor(@Advice.AllArguments Object[] args) {

        try {
            // Only intercept in stub/recording modes
            EnvironmentMode mode = RouteManager.getMode();
            if (mode == EnvironmentMode.PASSTHROUGH) {
                return;
            }

            // Check if JMS is in the routing table
            RouteManager.RouteResult routeResult = RouteManager.route(
                    "jms", "jms-broker", 0, null,
                    null, null,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null);

            if (!routeResult.matched) {
                return;
            }

            String stubHost = GlobalRouteState.SERVER_HOST;
            int stubPort = GlobalRouteState.JMS_PORT;
            String newBrokerUrl = "tcp://" + stubHost + ":" + stubPort;

            // ActiveMQConnectionFactory(String brokerURL) constructor
            if (args != null && args.length > 0 && args[0] instanceof String) {
                String originalUrl = (String) args[0];
                args[0] = newBrokerUrl;
                log.info("[Baafoo] JMS brokerURL replaced: {} -> {}", originalUrl, newBrokerUrl);
            }

            RoutingContext.set(routeResult);

        } catch (Exception e) {
            log.error("[Baafoo] JmsConnectionFactoryAdvice error: {}", e.getMessage());
            // Fail-closed: let original constructor proceed with real broker URL
        }
    }
}
