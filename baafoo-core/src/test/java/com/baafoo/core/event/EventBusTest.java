package com.baafoo.core.event;

import com.baafoo.plugin.PluginEvent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class EventBusTest {

    @Test
    public void testFireNullEventDoesNotThrow() {
        EventBus bus = new EventBus();
        bus.fire(null);
    }

    @Test
    public void testFireWithNoListeners() {
        EventBus bus = new EventBus();
        bus.fire(PluginEvent.agentStarted());
    }

    @Test
    public void testAddAndFire() {
        EventBus bus = new EventBus();
        List<PluginEvent> received = new ArrayList<>();
        bus.addListener(received::add);

        PluginEvent event = PluginEvent.agentStarted();
        bus.fire(event);
        assertEquals(1, received.size());
        assertSame(event, received.get(0));
    }

    @Test
    public void testMultipleListeners() {
        EventBus bus = new EventBus();
        AtomicInteger count1 = new AtomicInteger();
        AtomicInteger count2 = new AtomicInteger();
        bus.addListener(e -> count1.incrementAndGet());
        bus.addListener(e -> count2.incrementAndGet());

        bus.fire(PluginEvent.agentStarted());
        assertEquals(1, count1.get());
        assertEquals(1, count2.get());
    }

    @Test
    public void testRemoveListener() {
        EventBus bus = new EventBus();
        AtomicInteger count = new AtomicInteger();
        EventBus.EventListener listener = e -> count.incrementAndGet();
        bus.addListener(listener);
        bus.fire(PluginEvent.agentStarted());
        assertEquals(1, count.get());

        bus.removeListener(listener);
        bus.fire(PluginEvent.agentStarted());
        assertEquals(1, count.get());
    }

    @Test
    public void testAddNullListenerIgnored() {
        EventBus bus = new EventBus();
        bus.addListener(null);
        bus.fire(PluginEvent.agentStarted());
    }

    @Test
    public void testListenerExceptionDoesNotPropagate() {
        EventBus bus = new EventBus();
        bus.addListener(e -> { throw new RuntimeException("boom"); });

        bus.fire(PluginEvent.agentStarted());
    }

    @Test
    public void testOtherListenersStillCalledAfterException() {
        EventBus bus = new EventBus();
        bus.addListener(e -> { throw new RuntimeException("boom"); });

        AtomicInteger count = new AtomicInteger();
        bus.addListener(e -> count.incrementAndGet());

        bus.fire(PluginEvent.agentStarted());
        assertEquals(1, count.get());
    }

    @Test
    public void testRemoveNonexistentListenerDoesNotThrow() {
        EventBus bus = new EventBus();
        bus.removeListener(e -> {});
    }

    @Test
    public void testFireMultipleEvents() {
        EventBus bus = new EventBus();
        List<PluginEvent> received = new ArrayList<>();
        bus.addListener(received::add);

        bus.fire(PluginEvent.agentStarted());
        bus.fire(PluginEvent.agentShutdown());
        assertEquals(2, received.size());
        assertEquals(PluginEvent.Type.AGENT_STARTED, received.get(0).getType());
        assertEquals(PluginEvent.Type.AGENT_SHUTDOWN, received.get(1).getType());
    }
}
