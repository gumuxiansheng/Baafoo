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
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

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
     * @param event the event to fire
     */
    public void fire(PluginEvent event) {
        if (event == null) return;
        for (EventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Throwable t) {
                log.warn("[Baafoo] EventBus listener {} threw: {}", listener, t.getMessage());
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
