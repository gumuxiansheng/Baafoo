package com.baafoo.core.util;

import java.net.InetSocketAddress;

/**
 * Network utility methods for Docker environment detection.
 *
 * <p>Provides methods to detect whether a client is connecting from
 * outside a Docker container (via port mapping) by examining the
 * remote/local IP addresses. When the client comes through Docker
 * NAT, the remote address is typically the Docker gateway (e.g.
 * 172.19.0.1), which can be detected by checking if the last byte
 * of the IPv4 address is 1 within a private subnet.</p>
 *
 * <p><b>Limitation</b>: The gateway heuristic (last byte == 1) is
 * the default Docker convention but may not hold for custom network
 * configurations. For such cases, set {@code messagingAdvertisedHost}
 * in the server config to explicitly specify the advertised host.</p>
 */
public final class NetworkUtils {

    private NetworkUtils() {}

    /**
     * Resolve the host address that a client can reach, based on
     * the client's source IP and the server's local IP.
     *
     * <p>Logic:
     * <ol>
     *   <li>If both addresses are IPv4 and in the same private subnet:
     *       <ul>
     *         <li>If the client IP looks like a Docker gateway (last byte == 1),
     *             return {@code advertisedHost} (or the local IP if not set)</li>
     *         <li>Otherwise return {@code defaultHost} (container-internal address)</li>
     *       </ul>
     *   </li>
     *   <li>If not in the same subnet or not IPv4, return
     *       {@code advertisedHost} if set, otherwise the local IP</li>
     * </ol></p>
     *
     * @param remoteAddress  the client's remote socket address
     * @param localAddress   the server's local socket address
     * @param defaultHost    the default host (typically the container-internal IP)
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

            // When the broker is bound to the wildcard address (0.0.0.0 / ::), the
            // local socket address carries no usable subnet information. This is the
            // NORMAL case for a Netty server started with ServerBootstrap.bind(port)
            // (no explicit host). The subnet check below would then fail and the
            // function would fall back to the advertised host (e.g. "localhost"),
            // which is UNREACHABLE from another container. For in-Docker clients we
            // must return the server's real container address (defaultHost) instead.
            if (isWildcardAddress(localBytes)) {
                if (isLikelyGateway(remoteBytes) || isLoopback(remoteBytes)) {
                    // External client reached us via the Docker gateway / NAT, or a
                    // loopback client on the same host: the advertised host
                    // (localhost for host port-mapping) is the reachable address.
                    if (advertisedHost != null && !advertisedHost.isEmpty()) {
                        return advertisedHost;
                    }
                    return defaultHost != null ? defaultHost
                            : localAddress.getAddress().getHostAddress();
                }
                // In-Docker client (another container on the same network): return
                // the server's container-reachable address so the client reconnects
                // to the broker, not to its own loopback.
                return defaultHost != null ? defaultHost
                        : localAddress.getAddress().getHostAddress();
            }

            boolean sameSubnet = isSameSubnet(remoteBytes, localBytes);
            boolean isPrivate = isPrivateSubnet(localBytes);

            if (sameSubnet && isPrivate) {
                if (isLikelyGateway(remoteBytes)) {
                    if (advertisedHost != null && !advertisedHost.isEmpty()) {
                        return advertisedHost;
                    }
                    return localAddress.getAddress().getHostAddress();
                }
                return defaultHost;
            }
        } catch (Exception e) {
            // Fall through
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
        return addr.length == 4 && (addr[3] & 0xFF) == 1;
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
