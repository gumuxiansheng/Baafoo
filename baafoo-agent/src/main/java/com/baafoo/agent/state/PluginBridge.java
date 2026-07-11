package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;
import com.baafoo.plugin.PluginEvent;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * P1-2: Plugin consultation and event bridge.
 *
 * <p>Encapsulates the plugin SPI bridge functions ({@code PLUGIN_CONSULT_FN_EXT},
 * {@code EVENT_FIRE_FN}) and the {@code firePluginEvent} dispatch previously
 * inlined in {@link GlobalRouteState}. The bridge fields stay on
 * {@code GlobalRouteState} for Bootstrap-CL compatibility (Bootstrap-CL
 * advice reads {@code PLUGIN_CONSULT_FN_EXT} etc. by field name); this
 * class provides typed dispatch for App-CL callers.</p>
 */
public final class PluginBridge {

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
     * Safe to call from Bootstrap-CL advice code.
     *
     * <p>The event is accepted as {@code Object} so that Bootstrap-CL advice
     * (which cannot load {@code com.baafoo.plugin.PluginEvent}) can still
     * trigger the bridge. The App-CL side casts it back safely.</p>
     *
     * @param event the plugin event to fire (as an Object to avoid Bootstrap-CL
     *              type linkage issues)
     */
    public void fireEvent(Object event) {
        Consumer<Object> fn = GlobalRouteState.EVENT_FIRE_FN;
        if (fn != null) {
            try {
                fn.accept(event);
            } catch (Throwable t) {
                GlobalRouteState.logDebug("[Baafoo] Event fire skipped: " + t.getMessage());
            }
        }
    }

    public void setConsultExtFn(Function<Object[], Object[]> fn) {
        GlobalRouteState.PLUGIN_CONSULT_FN_EXT = fn;
    }

    public void setEventFireFn(Consumer<PluginEvent> fn) {
        GlobalRouteState.EVENT_FIRE_FN = event -> {
            if (event instanceof PluginEvent) {
                fn.accept((PluginEvent) event);
            }
        };
    }
}
