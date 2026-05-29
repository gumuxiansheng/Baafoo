package com.baafoo.agent.advice;

import com.baafoo.agent.BaafooAgent;
import com.baafoo.core.config.AgentConfig;
import com.baafoo.core.model.EnvironmentMode;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Byte Buddy Advice for {@code java.net.InetAddress#getByName(String)}
 * and {@code java.net.InetAddress#getAllByName(String)}.
 *
 * <p>Intercepts DNS resolution to redirect Consul-registered service names
 * to Baafoo stub server IPs. This is the Consul DNS interception mode.</p>
 *
 * <p>Logic:
 * <pre>
 *   String host = getByName / getAllByName argument
 *   if host matches a service name in routing table:
 *       return 127.0.0.1 (or configured stub host)
 *   else:
 *       proceed with normal DNS resolution
 * </pre></p>
 */
public class ConsulDnsAdvice {

    private static final Logger log = LoggerFactory.getLogger(ConsulDnsAdvice.class);

    /**
     * Intercept {@code InetAddress.getByName(String)}.
     * If the host matches a Baafoo rule, replace the returned InetAddress.
     */
    @Advice.OnMethodExit
    public static void onGetByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress result) {

        try {
            if (host == null || host.isEmpty()) {
                return;
            }

            // Only intercept in stub/recording modes
            EnvironmentMode mode = RouteManager.getMode();
            if (mode == EnvironmentMode.PASSTHROUGH) {
                return;
            }

            // Check if host matches a service name in the routing table
            RouteManager.RouteResult routeResult = RouteManager.route(
                    "tcp", null, 0, host, // pass host as serviceName
                    null, null,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null);

            if (routeResult.matched) {
                AgentConfig config = BaafooAgent.getConfig();
                String stubHost = config != null && config.getConsulAddress() != null
                        ? config.getConsulAddress().split(":")[0]
                        : "127.0.0.1";

                result = InetAddress.getByName(stubHost);
                log.debug("Consul DNS getByName: {} → {} (rule: {})", host, stubHost, routeResult.rule.getName());
            }
        } catch (Exception e) {
            log.error("Error in ConsulDnsAdvice.onGetByName: {}", e.getMessage());
            // Fail-closed: let original resolution stand
        }
    }

    /**
     * Intercept {@code InetAddress.getAllByName(String)}.
     * If the host matches a Baafoo rule, replace the returned InetAddress array.
     */
    @Advice.OnMethodExit
    public static void onGetAllByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress[] result) {

        try {
            if (host == null || host.isEmpty()) {
                return;
            }

            // Only intercept in stub/recording modes
            EnvironmentMode mode = RouteManager.getMode();
            if (mode == EnvironmentMode.PASSTHROUGH) {
                return;
            }

            // Check if host matches a service name in the routing table
            RouteManager.RouteResult routeResult = RouteManager.route(
                    "tcp", null, 0, host,
                    null, null,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null);

            if (routeResult.matched) {
                AgentConfig config = BaafooAgent.getConfig();
                String stubHost = config != null && config.getConsulAddress() != null
                        ? config.getConsulAddress().split(":")[0]
                        : "127.0.0.1";

                InetAddress stubAddr = InetAddress.getByName(stubHost);
                result = new InetAddress[]{stubAddr};
                log.debug("Consul DNS getAllByName: {} → {} (rule: {})", host, stubHost, routeResult.rule.getName());
            }
        } catch (Exception e) {
            log.error("Error in ConsulDnsAdvice.onGetAllByName: {}", e.getMessage());
            // Fail-closed: let original resolution stand
        }
    }
}
