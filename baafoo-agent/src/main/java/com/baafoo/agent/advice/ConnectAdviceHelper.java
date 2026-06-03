package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Shared logic for socket connect interception.
 *
 * <p>Both {@link SocketConnectAdvice} and {@link NioSocketConnectAdvice}
 * perform the same routing logic. This helper eliminates the duplication
 * while keeping each Advice class as a thin delegate (Byte Buddy requires
 * Advice methods to be static and in the advice class itself).</p>
 */
public final class ConnectAdviceHelper {

    private ConnectAdviceHelper() {}

    /**
     * Attempt to reroute a socket connection to the stub server.
     *
     * @param endpoint the original socket address
     * @return rerouted InetSocketAddress, or null if no rerouting needed
     */
    public static InetSocketAddress resolveRoute(SocketAddress endpoint) {
        if (GlobalRouteState.isPassthrough()) {
            return null;
        }

        if (!(endpoint instanceof InetSocketAddress)) {
            return null;
        }

        InetSocketAddress addr = (InetSocketAddress) endpoint;
        String host = addr.getHostString();
        int port = addr.getPort();

        if (GlobalRouteState.isInternal(host, port)) {
            return null;
        }

        String[] routeValue = GlobalRouteState.lookup(host, port);

        if (routeValue != null) {
            String stubHost = routeValue[0];
            int stubPort = Integer.parseInt(routeValue[1]);
            return new InetSocketAddress(stubHost, stubPort);
        }

        return null;
    }
}
