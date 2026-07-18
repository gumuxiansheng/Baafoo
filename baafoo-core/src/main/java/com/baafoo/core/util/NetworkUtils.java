package com.baafoo.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Network utility methods for resolving the host address a client can reach.
 *
 * <p>Given a client's source IP and the server's local IP, resolves the address
 * the client should reconnect to (e.g. in a broker LOOKUP / Metadata response).
 * The logic is based purely on IP reachability and is NOT Docker-specific: it
 * behaves identically for Docker containers, bare-metal hosts, and VMs — including
 * multi-NIC hosts where {@code InetAddress.getLocalHost()} may pick a different
 * interface than the one the client actually connected to.</p>
 *
 * <p>Detection rules: a loopback client reconnects to the local address; a client
 * appearing to come through a NAT gateway (private-subnet IPv4 whose last byte is
 * 1) or an external client gets the advertised host; an in-subnet client gets the
 * local address of the current connection (guaranteed reachable from its subnet).</p>
 *
 * <p><b>Limitation</b>: The gateway heuristic (last byte == 1) follows the common
 * Docker convention but may not hold for custom network configurations (e.g. a
 * real host whose IP ends in .1). For such cases, set {@code messagingAdvertisedHost}
 * in the server config to explicitly specify the advertised host.</p>
 */
public final class NetworkUtils {

    private static final Logger log = LoggerFactory.getLogger(NetworkUtils.class);

    /**
     * Smell #9: the last-octet value conventionally used by Docker / common
     * home routers for the subnet gateway (e.g. 172.17.0.1, 192.168.1.1).
     * Extracted from the previous inline magic literal {@code == 1} so the
     * heuristic is greppable and documented in one place.
     */
    private static final int GATEWAY_LAST_OCTET = 1;

    private NetworkUtils() {}

    /**
     * Resolve the host address that a client can reach, based on
     * the client's source IP and the server's local IP.
     *
     * <p>Logic:
     * <ol>
     *   <li>External / NAT'd client (remote is a NAT gateway — last byte == 1 in a
     *       private subnet, e.g. the Docker gateway 172.x.x.1 — or a loopback client
     *       on the same host): return {@code advertisedHost}
     *       (localhost / host IP) — the interface/container IP we see is unreachable
     *       from outside.</li>
     *   <li>Wildcard-bound server (local address is 0.0.0.0): fall back to
     *       {@code defaultHost} for in-Docker clients (preserves the previous
     *       edge-case behaviour).</li>
     *   <li>In-Docker client on a private subnet (the normal case): return the
     *       <b>local address of the current connection</b>
     *       ({@code localAddress.getHostAddress()}). This is the exact container
     *       IP the client connected to, so it is guaranteed reachable from the
     *       client's subnet. It MUST NOT be {@code defaultHost}, because
     *       {@code defaultHost} is often derived from
     *       {@code InetAddress.getLocalHost()}, which can pick a DIFFERENT network
     *       interface of the server (e.g. in a multi-network Docker stack) that the
     *       client cannot route to — that mismatch is exactly what produced
     *       {@code "connection timed out: ...:<port>"} before this fix.</li>
     *   <li>External client on a public address: return {@code advertisedHost}
     *       (or {@code defaultHost} as a last resort).</li>
     * </ol></p>
     *
     * @param remoteAddress  the client's remote socket address
     * @param localAddress   the server's local socket address (of THIS connection)
     * @param defaultHost    the configured default host (container-internal IP);
     *                       used only as a fallback, not for in-Docker clients
     * @param advertisedHost the advertised host for external clients (may be null)
     * @return the host address that the client can reach
     */
    public static String resolveClientReachableHost(InetSocketAddress remoteAddress,
                                                     InetSocketAddress localAddress,
                                                     String defaultHost,
                                                     String advertisedHost) {
        try {
            if (remoteAddress == null || localAddress == null) {
                return defaultHost;
            }
            byte[] remoteBytes = remoteAddress.getAddress().getAddress();
            byte[] localBytes = localAddress.getAddress().getAddress();

            // Only handle IPv4
            if (remoteBytes.length != 4 || localBytes.length != 4) {
                return fallbackHost(advertisedHost, defaultHost, localAddress);
            }

            // External / NAT'd client reached us through the Docker gateway (remote
            // is the gateway IP, last byte == 1) or a loopback client on the same
            // host. In both cases the local address we see is the container-internal
            // IP, which the client CANNOT reach directly, so we must return the
            // advertised host (localhost / host IP) instead.
            if (isLikelyGateway(remoteBytes) || isLoopback(remoteBytes)) {
                if (advertisedHost != null && !advertisedHost.isEmpty()) {
                    return advertisedHost;
                }
                // No advertised host configured: a loopback client CAN reach the
                // local address; a gateway/NAT client cannot, but we have no better
                // answer than the configured default host.
                return isLoopback(remoteBytes)
                        ? localAddress.getAddress().getHostAddress()
                        : (defaultHost != null ? defaultHost : localAddress.getAddress().getHostAddress());
            }

            // Wildcard-bound server: the accepted connection's local address may
            // carry no subnet info (0.0.0.0). For in-Docker clients fall back to
            // the configured container IP (defaultHost) — this preserves the
            // previous behaviour for that edge case.
            if (isWildcardAddress(localBytes)) {
                return defaultHost != null ? defaultHost
                        : localAddress.getAddress().getHostAddress();
            }

            // In-Docker client (another container on a private subnet — same network
            // or a different one the client can route to). The local address of THIS
            // connection is the exact container IP the client connected to, so it is
            // guaranteed reachable from the client. Return it instead of defaultHost,
            // which may be a DIFFERENT interface IP of the server (e.g.
            // InetAddress.getLocalHost() can pick an interface the client cannot
            // route to). Returning the wrong interface IP is exactly what produced
            // "connection timed out: ...:<port>" in multi-network Docker stacks.
            if (isPrivateSubnet(remoteBytes)) {
                return localAddress.getAddress().getHostAddress();
            }

            // External client on a public address: the container IP is not reachable;
            // use the advertised host (or defaultHost as a last resort).
            return fallbackHost(advertisedHost, defaultHost, localAddress);
        } catch (Exception e) {
            // M10: log the exception at debug level so malformed/unexpected
            // socket addresses (e.g. unresolved InetSocketAddress, IPv6 edge
            // cases) are diagnosable without spamming logs — this is a
            // fallback path that's expected for some inputs.
            log.debug("resolveClientReachableHost fell back to default host", e);
        }
        return fallbackHost(advertisedHost, defaultHost, localAddress);
    }

    /**
     * Check if two IPv4 addresses are in the same /16 subnet.
     */
    public static boolean isSameSubnet(byte[] a, byte[] b) {
        return a.length == 4 && b.length == 4
                && a[0] == b[0] && a[1] == b[1];
    }

    /**
     * Check if an IPv4 address is in a private subnet (10.x, 172.16-31.x, 192.168.x).
     */
    public static boolean isPrivateSubnet(byte[] addr) {
        if (addr.length != 4) return false;
        int b0 = addr[0] & 0xFF;
        int b1 = addr[1] & 0xFF;
        return b0 == 10
                || (b0 == 172 && b1 >= 16 && b1 <= 31)
                || (b0 == 192 && b1 == 168);
    }

    /**
     * Check if an IPv4 address looks like a Docker gateway.
     * Docker typically assigns x.x.x.1 as the gateway address.
     */
    public static boolean isLikelyGateway(byte[] addr) {
        return addr.length == 4 && (addr[3] & 0xFF) == GATEWAY_LAST_OCTET;
    }

    private static String fallbackHost(String advertisedHost, String defaultHost, InetSocketAddress localAddress) {
        if (advertisedHost != null && !advertisedHost.isEmpty()) {
            return advertisedHost;
        }
        // Prefer the server's real (container) address over the wildcard local
        // address, which is unreachable from other containers.
        if (defaultHost != null && !defaultHost.isEmpty()) {
            return defaultHost;
        }
        return localAddress != null ? localAddress.getAddress().getHostAddress() : "127.0.0.1";
    }

    /**
     * True if the address is the IPv4/IPv6 wildcard (0.0.0.0 / ::).
     * A Netty server started with {@code bind(port)} (no explicit host) binds to
     * the wildcard address, so the local socket address carries no subnet info.
     */
    private static boolean isWildcardAddress(byte[] addr) {
        if (addr == null) return false;
        if (addr.length == 4) {
            return addr[0] == 0 && addr[1] == 0 && addr[2] == 0 && addr[3] == 0;
        }
        if (addr.length == 16) {
            for (byte b : addr) {
                if (b != 0) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * True if the address is IPv4 loopback (127.0.0.0/8).
     */
    private static boolean isLoopback(byte[] addr) {
        if (addr == null || addr.length != 4) return false;
        return (addr[0] & 0xFF) == 127;
    }
}
