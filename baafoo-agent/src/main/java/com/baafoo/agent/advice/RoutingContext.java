package com.baafoo.agent.advice;

/**
 * ThreadLocal routing context for recording calls during bytecode interception.
 *
 * <p>Stored in a ThreadLocal because Advice methods run on the same thread
 * as the intercepted method. This allows downstream Advice classes
 * (e.g., in SocketOutputStream write()) to access the routing decision
 * made by SocketConnectAdvice.</p>
 */
public final class RoutingContext {

    private static final ThreadLocal<RouteManager.RouteResult> CURRENT = new ThreadLocal<RouteManager.RouteResult>();

    private RoutingContext() {}

    public static void set(RouteManager.RouteResult result) {
        CURRENT.set(result);
    }

    public static RouteManager.RouteResult get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void runAndClear(Runnable task) {
        try {
            task.run();
        } finally {
            CURRENT.remove();
        }
    }

    public static <T> T executeAndClear(java.util.concurrent.Callable<T> task) throws Exception {
        try {
            return task.call();
        } finally {
            CURRENT.remove();
        }
    }
}
