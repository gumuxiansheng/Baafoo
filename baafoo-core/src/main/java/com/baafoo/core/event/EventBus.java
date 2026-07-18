package com.baafoo.core.event;

import com.baafoo.plugin.PluginEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight event bus for broadcasting {@link PluginEvent}s.
 *
 * <p>Defined in baafoo-core so both Agent and Server can use it independently.
 * Each process holds its own EventBus instance — events do not cross process
 * boundaries (unless Agent and Server are in the same JVM, in which case they
 * share a single instance).</p>
 *
 * <p>Events are <b>observation-only</b>: listeners must not throw. Exceptions
 * are caught and logged — they never affect the request flow.</p>
 *
 * <p>L2: Delivery semantics are <b>at-most-once, best-effort</b>. There is no
 * persistence, no retry, and no acknowledgment — if the JVM crashes between
 * {@link #fire} and a listener completing, the event is lost. This is
 * intentional: the bus carries observation-only telemetry (rule matched,
 * recording captured, agent heartbeat) where occasional loss is acceptable and
 * introducing a durable queue would be disproportionate. Callers that need
 * guaranteed delivery must use a real MQ, not this bus.</p>
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    /**
     * Smell #10: shared log-line prefix for EventBus diagnostics. Extracted
     * from the previously inline {@code "[Baafoo] "} string literal so the
     * prefix is consistent across log calls and greppable in log output.
     */
    private static final String LOG_PREFIX = "[Baafoo] ";

    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Register an event listener.
     *
     * @param listener the listener to add
     */
    public void addListener(EventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Remove a previously registered listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(EventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Fire an event to all registered listeners.
     * Exceptions from individual listeners are caught and logged;
     * they do not propagate to the caller and do not affect the request flow.
     *
     * <p>L3: the throwable is passed as the last SLF4J argument so the full
     * stack trace is preserved in the log output. The previous form
     * {@code log.warn("... {}", t.getMessage())} only logged the exception
     * message, making it impossible to diagnose where the listener actually
     * failed. Remaining listeners are still notified (CopyOnWriteArrayList
     * iteration is unaffected by the catch).</p>
     *
     * @param event the event to fire
     */
    public void fire(PluginEvent event) {
        if (event == null) return;
        for (EventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Throwable t) {
                log.warn(LOG_PREFIX + "EventBus listener {} threw: {}", listener, t.getMessage(), t);
            }
        }
    }

    /**
     * Functional interface for event listeners.
     */
    @FunctionalInterface
    public interface EventListener {
        void onEvent(PluginEvent event);
    }
}
