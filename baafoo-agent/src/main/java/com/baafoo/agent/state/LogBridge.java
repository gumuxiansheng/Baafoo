package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;

import java.util.function.Consumer;

/**
 * P1-2: Logging bridge.
 *
 * <p>Encapsulates the SLF4J-backed log handlers ({@code LOG_INFO_HANDLER},
 * {@code LOG_WARN_HANDLER}, {@code LOG_ERROR_HANDLER},
 * {@code LOG_DEBUG_HANDLER}) and the log dispatch methods previously
 * inlined in {@link GlobalRouteState}. The handler fields stay on
 * {@code GlobalRouteState} for Bootstrap-CL compatibility (advice calls
 * {@code GlobalRouteState.logInfo(...)} etc.); this class provides typed
 * dispatch for App-CL callers.</p>
 *
 * <p>When a handler is not set, the message is written to {@code System.out}
 * so no log line is lost during early startup.</p>
 */
public final class LogBridge {

    public void info(String msg) {
        Consumer<String> h = GlobalRouteState.LOG_INFO_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    public void warn(String msg) {
        Consumer<String> h = GlobalRouteState.LOG_WARN_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    public void error(String msg) {
        Consumer<String> h = GlobalRouteState.LOG_ERROR_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    public void debug(String msg) {
        Consumer<String> h = GlobalRouteState.LOG_DEBUG_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    /**
     * Install all four log handlers at once.
     *
     * @param info  INFO handler (or {@code null} to clear)
     * @param warn  WARN handler (or {@code null} to clear)
     * @param error ERROR handler (or {@code null} to clear)
     * @param debug DEBUG handler (or {@code null} to clear)
     */
    public void setHandlers(Consumer<String> info, Consumer<String> warn,
                            Consumer<String> error, Consumer<String> debug) {
        GlobalRouteState.LOG_INFO_HANDLER = info;
        GlobalRouteState.LOG_WARN_HANDLER = warn;
        GlobalRouteState.LOG_ERROR_HANDLER = error;
        GlobalRouteState.LOG_DEBUG_HANDLER = debug;
    }
}
