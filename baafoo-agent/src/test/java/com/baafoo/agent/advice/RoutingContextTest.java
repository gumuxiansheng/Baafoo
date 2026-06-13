package com.baafoo.agent.advice;

import com.baafoo.core.model.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class RoutingContextTest {

    @Test
    public void testSetAndGet() {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        result.matched = true;
        result.rule = new Rule();
        RoutingContext.set(result);
        try {
            assertSame(result, RoutingContext.get());
        } finally {
            RoutingContext.clear();
        }
    }

    @Test
    public void testClear() {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        result.matched = true;
        result.rule = new Rule();
        RoutingContext.set(result);
        RoutingContext.clear();
        assertNull(RoutingContext.get());
    }

    @Test
    public void testInitialNull() {
        assertNull(RoutingContext.get());
    }

    @Test
    public void testRunAndClear() {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        result.matched = true;
        result.rule = new Rule();
        RoutingContext.set(result);
        RoutingContext.runAndClear(() -> {
            assertSame(result, RoutingContext.get());
        });
        assertNull(RoutingContext.get());
    }

    @Test
    public void testRunAndClearAlwaysCleansUp() {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        result.matched = true;
        result.rule = new Rule();
        RoutingContext.set(result);
        try {
            RoutingContext.runAndClear(() -> {
                throw new RuntimeException("test exception");
            });
        } catch (RuntimeException e) {
            assertEquals("test exception", e.getMessage());
        }
        assertNull(RoutingContext.get());
    }

    @Test
    public void testExecuteAndClear() throws Exception {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        result.matched = true;
        result.rule = new Rule();
        RoutingContext.set(result);
        String value = RoutingContext.executeAndClear(() -> {
            assertSame(result, RoutingContext.get());
            return "done";
        });
        assertEquals("done", value);
        assertNull(RoutingContext.get());
    }

    @Test
    public void testExecuteAndClearAlwaysCleansUp() {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        result.matched = true;
        result.rule = new Rule();
        RoutingContext.set(result);
        try {
            RoutingContext.executeAndClear(() -> {
                throw new Exception("test exception");
            });
        } catch (Exception e) {
            assertEquals("test exception", e.getMessage());
        }
        assertNull(RoutingContext.get());
    }

    @Test
    public void testThreadIsolation() throws Exception {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        result.matched = true;
        result.rule = new Rule();
        RoutingContext.set(result);

        AtomicReference<RouteManager.RouteResult> otherThreadResult = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            otherThreadResult.set(RoutingContext.get());
            latch.countDown();
        });
        t.start();
        latch.await();

        assertNull(otherThreadResult.get());
        assertSame(result, RoutingContext.get());
        RoutingContext.clear();
    }
}
