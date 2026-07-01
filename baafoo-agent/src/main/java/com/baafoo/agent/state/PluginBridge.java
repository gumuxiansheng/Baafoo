package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;
import com.baafoo.plugin.PluginEvent;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * P1-2: Plugin consultation and event bridge.
 *
 * <p>Encapsulates the plugin SPI bridge functions ({@code PLUGIN_CONSULT_FN},
 * {@code PLUGIN_CONSULT_FN_EXT}, {@code EVENT_FIRE_FN}) and the
 * {@code firePluginEvent} dispatch previously inlined in
 * {@link GlobalRouteState}. The bridge fields stay on
 * {@code GlobalRouteState} for Bootstrap-CL compatibility (Bootstrap-CL
 * advice reads {@code PLUGIN_CONSULT_FN_EXT} etc. by field name); this
 * class provides typed dispatch for App-CL callers.</p>
 */
public final class PluginBridge {

    /**
     * Consult the legacy plugin function.
     *
     * @param args {@code { String host, Integer port }}
     * @return {@code { String targetHost, Integer targetPort }} or {@code null}
     *         if no redirect applies or the function is not set.
     */
    public Object[] consult(Object[] args) {
        Function<Object[], Object[]> fn = GlobalRouteState.PLUGIN_CONSULT_FN;
        if (fn == null) {
            return null;
        }
        try {
            return fn.apply(args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Consult the extended plugin function.
     *
     * @param args {@code { String host, Integer port, String protocol }}
     * @return {@code { Integer action, String targetHost, Integer targetPort,
     *         String reason }} or {@code null} if not set / no opinion.
     */
    public Object[] consultExt(Object[] args) {
        Function<Object[], Object[]> fn = GlobalRouteState.PLUGIN_CONSULT_FN_EXT;
        if (fn == null) {
            return null;
        }
        try {
            return fn.apply(args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Fire a plugin event through the bridge to PluginManager.
     * Safe to call from Bootstrap-CL advice code (the bridge is synced to
     * the Bootstrap-CL copy of GlobalRouteState via reflection).
     *
     * @param event the plugin event to fire
     */
    public void fireEvent(PluginEvent event) {
        Consumer<PluginEvent> fn = GlobalRouteState.EVENT_FIRE_FN;
        if (fn != null) {
            try {
                fn.accept(event);
            } catch (Throwable t) {
                GlobalRouteState.logDebug("[Baafoo] Event fire skipped: " + t.getMessage());
            }
        }
    }

    public void setConsultFn(Function<Object[], Object[]> fn) {
        GlobalRouteState.PLUGIN_CONSULT_FN = fn;
    }

    public void setConsultExtFn(Function<Object[], Object[]> fn) {
        GlobalRouteState.PLUGIN_CONSULT_FN_EXT = fn;
    }

    public void setEventFireFn(Consumer<PluginEvent> fn) {
        GlobalRouteState.EVENT_FIRE_FN = fn;
    }
}
