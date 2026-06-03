package com.baafoo.agent.advice;

import net.bytebuddy.asm.Advice;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class NioSocketConnectAdvice {

    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress remote) {
        try {
            InetSocketAddress rerouted = ConnectAdviceHelper.resolveRoute(remote);
            if (rerouted != null) {
                remote = rerouted;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
        }
    }
}
