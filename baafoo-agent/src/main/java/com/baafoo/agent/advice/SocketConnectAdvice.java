package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class SocketConnectAdvice {

    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress endpoint) {
        try {
            if (GlobalRouteState.isPassthrough()) {
                return;
            }

            if (!(endpoint instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) endpoint;
            String host = addr.getHostString();
            int port = addr.getPort();

            if (GlobalRouteState.isInternal(host, port)) {
                return;
            }

            String[] routeValue = GlobalRouteState.lookup(host, port);

            if (routeValue != null) {
                String stubHost = routeValue[0];
                int stubPort = Integer.parseInt(routeValue[1]);
                endpoint = new InetSocketAddress(stubHost, stubPort);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
        }
    }
}
