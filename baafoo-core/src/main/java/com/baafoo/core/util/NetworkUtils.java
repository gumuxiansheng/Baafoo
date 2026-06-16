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
                return fallbackHost(advertisedHost, localAddress);
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
        return fallbackHost(advertisedHost, localAddress);
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

    private static String fallbackHost(String advertisedHost, InetSocketAddress localAddress) {
        if (advertisedHost != null && !advertisedHost.isEmpty()) {
            return advertisedHost;
        }
        return localAddress != null ? localAddress.getAddress().getHostAddress() : "127.0.0.1";
    }
}
