package com.baafoo.agent.advice;

import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;

/**
 * Byte Buddy Advice for sun.nio.ch.SocketChannelImpl#connect(SocketAddress).
 *
 * <p>Same logic as SocketConnectAdvice but for NIO channels.
 * The NIO SocketChannelImpl is an internal JDK class that may not be
 * accessible in newer JDK versions, but for Java 8 it works.</p>
 */
public class NioSocketConnectAdvice {

    private static final Logger log = LoggerFactory.getLogger(NioSocketConnectAdvice.class);

    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress remote) {

        try {
            if (!(remote instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) remote;
            String host = addr.getHostString();
            int port = addr.getPort();

            RouteManager.RouteResult result = RouteManager.route(
                    "tcp", host, port, null,
                    null, null,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null);

            if (result.unmatched404) {
                log.warn("NIO: No matching rule for {}:{}, forbidding connection", host, port);
                throw new SocketConnectAdvice.UnmatchedStubException("No Baafoo rule matched for " + host + ":" + port);
            }

            if (result.matched) {
                InetSocketAddress stubAddr = new InetSocketAddress("127.0.0.1", getStubPort(result.protocol));
                remote = stubAddr;
                RoutingContext.set(result);
                log.debug("NIO connect {}:{} → stub", host, port);
            }
        } catch (SocketConnectAdvice.UnmatchedStubException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in NioSocketConnectAdvice: {}", e.getMessage());
        }
    }

    private static int getStubPort(String protocol) {
        if (protocol == null) return 9001;
        switch (protocol.toLowerCase()) {
            case "http": return 9000;
            case "tcp": return 9001;
            default: return 9001;
        }
    }
}
