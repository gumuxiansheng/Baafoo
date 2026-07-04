package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

/**
 * Intercepts InetAddress.getByName (single-result variant) for service-name
 * (or host-based) redirection.
 *
 * <p>Activated when {@code serviceInterceptionEnabled: true} in the agent
 * config. Despite the legacy flag name, this advice is registry-agnostic:
 * it works with Nacos, Consul, Eureka, or any registry whose service names
 * are routed via Baafoo rules. The redirect target is the Baafoo Server
 * (which then mocks or forwards the request based on rule matching).</p>
 *
 * <p><b>CRITICAL</b>: Must be a separate class from {@link ServiceNameDnsGetAllByNameAdvice}
 * because ByteBuddy's {@code Advice.to()} discovers ALL @Advice.OnMethodExit
 * methods in a class and tries to apply them to the target method, causing a
 * "Duplicate advice" error when two methods with different return types
 * (InetAddress vs InetAddress[]) are in the same class.</p>
 *
 * <p>Same separation rationale as {@link DnsGetByNameAdvice} vs
 * {@link DnsGetAllByNameAdvice}.</p>
 *
 * <p><b>Bootstrap CL constraint:</b> inlined into java.net.InetAddress by ByteBuddy;
 * only reference Bootstrap CL-visible classes.</p>
 */
public final class ServiceNameDnsAdvice {

    private ServiceNameDnsAdvice() {}

    @Advice.OnMethodExit
    public static void onGetByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress result) {

        if (GlobalRouteState.isPassthrough()) {
            return;
        }

        // Re-entry guard lives in GlobalRouteState (not as a static field here)
        // because this advice is inlined into java.net.InetAddress (Bootstrap CL)
        // and static field accesses are not inlined — the field must be reachable
        // from the Bootstrap CL. GlobalRouteState is in the Bootstrap JAR.
        // Null (unset) is treated as false — see GlobalRouteState.SERVICE_NAME_DNS_REENTRY_GUARD javadoc.
        if (Boolean.TRUE.equals(GlobalRouteState.SERVICE_NAME_DNS_REENTRY_GUARD.get())) {
            return;
        }

        try {
            if (host == null || host.isEmpty()) {
                return;
            }

            // Record DNS resolution for IP-based route lookup fallback
            if (result != null) {
                String ip = result.getHostAddress();
                if (ip != null && !ip.isEmpty()) {
                    GlobalRouteState.recordDns(host, ip);
                }
            }

            // Service-name / host redirection: if hostname matches a rule
            // (either by serviceName or by host), override the resolved
            // InetAddress with the Baafoo Server's address.
            // Check serviceName first, then fall back to host-based lookup.
            GlobalRouteState.HostPort target = GlobalRouteState.lookupService(host);
            if (target == null) {
                target = GlobalRouteState.lookupByHost(host);
            }
            if (target != null) {
                GlobalRouteState.logInfo("[Baafoo] ServiceNameDns redirect (getByName): " + host + " -> " + target.host);
                GlobalRouteState.SERVICE_NAME_DNS_REENTRY_GUARD.set(Boolean.TRUE);
                try {
                    result = InetAddress.getByName(target.host);
                } finally {
                    GlobalRouteState.SERVICE_NAME_DNS_REENTRY_GUARD.set(Boolean.FALSE);
                }
            }
        } catch (Throwable t) {
            GlobalRouteState.SERVICE_NAME_DNS_REENTRY_GUARD.set(Boolean.FALSE);
            GlobalRouteState.logInfo("[Baafoo] ServiceNameDns (getByName) error: " + t.getMessage());
        }
    }
}
