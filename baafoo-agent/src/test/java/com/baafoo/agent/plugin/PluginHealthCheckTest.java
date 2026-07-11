package com.baafoo.agent.plugin;

import com.baafoo.plugin.*;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Tests for P3: Plugin health monitoring, auto-disable, and enable/disable.
 */
public class PluginHealthCheckTest {

    // ==================== Health Status Tracking ====================

    @Test
    public void testInitialHealthIsUnknown() {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        // No plugins loaded — no health status
        assertNull(pm.getHealthStatus(InterceptTarget.KAFKA));
        assertTrue(pm.getAllHealthStatuses().isEmpty());
    }

    @Test
    public void testPluginHealthStatus_recordSuccess() {
        PluginManager.PluginHealthStatus status =
                new PluginManager.PluginHealthStatus("test-plugin");
        assertEquals(PluginHealth.UNKNOWN, status.getHealth());

        status.recordSuccess(10);
        assertEquals(PluginHealth.HEALTHY, status.getHealth());
        assertEquals(1, status.getSuccessCount());
        assertEquals(0, status.getErrorCount());
        assertEquals(10, status.getTotalLatencyMs());
        assertEquals(10, status.getAvgLatencyMs());
        assertEquals(0, status.getConsecutiveErrors());
        assertTrue(status.getLastSuccessTime() > 0);
    }

    @Test
    public void testPluginHealthStatus_recordError_degrades() {
        PluginManager.PluginHealthStatus status =
                new PluginManager.PluginHealthStatus("test-plugin");

        status.recordError(5, "timeout");
        assertEquals(PluginHealth.DEGRADED, status.getHealth());
        assertEquals(0, status.getSuccessCount());
        assertEquals(1, status.getErrorCount());
        assertEquals("timeout", status.getLastError());
        assertEquals(1, status.getConsecutiveErrors());
    }

    @Test
    public void testPluginHealthStatus_autoUnhealthy_afterThreshold() {
        PluginManager.PluginHealthStatus status =
                new PluginManager.PluginHealthStatus("test-plugin");

        // 4 errors = DEGRADED (below threshold of 5)
        for (int i = 0; i < 4; i++) {
            status.recordError(5, "error-" + i);
        }
        assertEquals(PluginHealth.DEGRADED, status.getHealth());
        assertEquals(4, status.getConsecutiveErrors());

        // 5th error = UNHEALTHY
        status.recordError(5, "error-4");
        assertEquals(PluginHealth.UNHEALTHY, status.getHealth());
        assertEquals(5, status.getConsecutiveErrors());
    }

    @Test
    public void testPluginHealthStatus_successResetsConsecutiveErrors() {
        PluginManager.PluginHealthStatus status =
                new PluginManager.PluginHealthStatus("test-plugin");

        status.recordError(5, "error-1");
        status.recordError(5, "error-2");
        status.recordError(5, "error-3");
        assertEquals(3, status.getConsecutiveErrors());

        status.recordSuccess(10);
        assertEquals(0, status.getConsecutiveErrors());
        assertEquals(PluginHealth.HEALTHY, status.getHealth());
    }

    @Test
    public void testPluginHealthStatus_avgLatency() {
        PluginManager.PluginHealthStatus status =
                new PluginManager.PluginHealthStatus("test-plugin");

        status.recordSuccess(10);
        status.recordSuccess(20);
        status.recordError(30, "err");

        assertEquals(3, status.getSuccessCount() + status.getErrorCount());
        assertEquals(60, status.getTotalLatencyMs());
        assertEquals(20, status.getAvgLatencyMs()); // 60 / 3
    }

    @Test
    public void testPluginHealthStatus_toMap() {
        PluginManager.PluginHealthStatus status =
                new PluginManager.PluginHealthStatus("my-plugin");
        status.recordSuccess(15);

        Map<String, Object> map = status.toMap();
        assertEquals("my-plugin", map.get("name"));
        assertEquals("HEALTHY", map.get("health"));
        assertEquals(1L, map.get("successCount"));
        assertEquals(0L, map.get("errorCount"));
        assertTrue(map.containsKey("loadedAt"));
    }

    // ==================== Enable / Disable ====================

    @Test
    public void testDisablePlugin_hidesFromGetPlugin() throws Exception {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        // Inject a test plugin via reflection
        TestPlugin plugin = new TestPlugin(InterceptTarget.KAFKA);
        injectPlugin(pm, plugin);

        assertNotNull(pm.getPlugin(InterceptTarget.KAFKA));
        assertFalse(pm.isDisabled(InterceptTarget.KAFKA));

        pm.disablePlugin(InterceptTarget.KAFKA);
        assertNull("Disabled plugin must return null from getPlugin()",
                pm.getPlugin(InterceptTarget.KAFKA));
        assertTrue(pm.isDisabled(InterceptTarget.KAFKA));
        assertEquals(PluginHealth.DISABLED, pm.getHealthStatus(InterceptTarget.KAFKA).getHealth());
    }

    @Test
    public void testEnablePlugin_restoresAccess() throws Exception {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        TestPlugin plugin = new TestPlugin(InterceptTarget.PULSAR);
        injectPlugin(pm, plugin);

        pm.disablePlugin(InterceptTarget.PULSAR);
        assertNull(pm.getPlugin(InterceptTarget.PULSAR));

        pm.enablePlugin(InterceptTarget.PULSAR);
        assertNotNull("Re-enabled plugin must be accessible",
                pm.getPlugin(InterceptTarget.PULSAR));
        assertFalse(pm.isDisabled(InterceptTarget.PULSAR));
        assertEquals(PluginHealth.UNKNOWN, pm.getHealthStatus(InterceptTarget.PULSAR).getHealth());
    }

    @Test
    public void testAutoUnhealthy_hidesFromGetPlugin() throws Exception {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        ThrowingPlugin plugin = new ThrowingPlugin(InterceptTarget.JMS);
        injectPlugin(pm, plugin);

        ConnectContext ctx = new ConnectContext("jms", "test", 9092, null, null, null, null);

        // Trigger 5 consecutive errors to reach UNHEALTHY threshold
        for (int i = 0; i < 5; i++) {
            pm.connectWithMonitor(InterceptTarget.JMS, ctx);
        }

        assertEquals(PluginHealth.UNHEALTHY,
                pm.getHealthStatus(InterceptTarget.JMS).getHealth());
        assertNull("UNHEALTHY plugin must return null from getPlugin()",
                pm.getPlugin(InterceptTarget.JMS));
    }

    // ==================== connectWithMonitor ====================

    @Test
    public void testConnectWithMonitor_success() throws Exception {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        TestPlugin plugin = new TestPlugin(InterceptTarget.KAFKA);
        injectPlugin(pm, plugin);

        ConnectContext ctx = new ConnectContext("kafka", "test", 9092, null, null, null, null);
        ConnectAdvice advice = pm.connectWithMonitor(InterceptTarget.KAFKA, ctx);

        assertNotNull(advice);
        assertTrue(advice.isRedirect());
        assertEquals(1, plugin.invocationCount.get());

        PluginManager.PluginHealthStatus status = pm.getHealthStatus(InterceptTarget.KAFKA);
        assertEquals(PluginHealth.HEALTHY, status.getHealth());
        assertEquals(1, status.getSuccessCount());
    }

    @Test
    public void testConnectWithMonitor_noPlugin() {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        ConnectContext ctx = new ConnectContext("kafka", "test", 9092, null, null, null, null);
        ConnectAdvice advice = pm.connectWithMonitor(InterceptTarget.KAFKA, ctx);
        assertTrue("No plugin → must return passthrough", advice.isPassthrough());
    }

    @Test
    public void testConnectWithMonitor_disabledPlugin() throws Exception {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        TestPlugin plugin = new TestPlugin(InterceptTarget.KAFKA);
        injectPlugin(pm, plugin);
        pm.disablePlugin(InterceptTarget.KAFKA);

        ConnectContext ctx = new ConnectContext("kafka", "test", 9092, null, null, null, null);
        ConnectAdvice advice = pm.connectWithMonitor(InterceptTarget.KAFKA, ctx);
        assertTrue("Disabled plugin → must return passthrough", advice.isPassthrough());
        assertEquals(0, plugin.invocationCount.get());
    }

    @Test
    public void testConnectWithMonitor_errorTracking() throws Exception {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        ThrowingPlugin plugin = new ThrowingPlugin(InterceptTarget.KAFKA);
        injectPlugin(pm, plugin);

        ConnectContext ctx = new ConnectContext("kafka", "test", 9092, null, null, null, null);
        ConnectAdvice advice = pm.connectWithMonitor(InterceptTarget.KAFKA, ctx);
        assertNotNull(advice);
        assertTrue("Error → must return passthrough", advice.isPassthrough());

        PluginManager.PluginHealthStatus status = pm.getHealthStatus(InterceptTarget.KAFKA);
        assertEquals(PluginHealth.DEGRADED, status.getHealth());
        assertEquals(1, status.getErrorCount());
        assertEquals(0, status.getSuccessCount());
        assertEquals("simulated failure", status.getLastError());
    }

    // ==================== Helpers ====================

    private void injectPlugin(PluginManager pm, AgentPlugin plugin) throws Exception {
        java.lang.reflect.Field f = PluginManager.class.getDeclaredField("plugins");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<InterceptTarget, AgentPlugin> map =
                (Map<InterceptTarget, AgentPlugin>) f.get(pm);
        map.put(plugin.getTarget(), plugin);

        // Also initialize health status
        java.lang.reflect.Field hf = PluginManager.class.getDeclaredField("healthStatuses");
        hf.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<InterceptTarget, PluginManager.PluginHealthStatus> hmap =
                (Map<InterceptTarget, PluginManager.PluginHealthStatus>) hf.get(pm);
        hmap.put(plugin.getTarget(), new PluginManager.PluginHealthStatus(plugin.getName()));
    }

    private static class TestPlugin implements AgentPlugin {
        final AtomicInteger invocationCount = new AtomicInteger();
        private final InterceptTarget target;

        TestPlugin(InterceptTarget target) { this.target = target; }

        @Override public String getName() { return "test-" + target; }
        @Override public InterceptTarget getTarget() { return target; }
        @Override public void init() {}
        @Override public ConnectAdvice onConnect(ConnectContext ctx) {
            invocationCount.incrementAndGet();
            return ConnectAdvice.redirect("localhost", 9999);
        }
        @Override public void destroy() {}
    }

    private static class ThrowingPlugin implements AgentPlugin {
        private final InterceptTarget target;

        ThrowingPlugin(InterceptTarget target) { this.target = target; }

        @Override public String getName() { return "throwing-" + target; }
        @Override public InterceptTarget getTarget() { return target; }
        @Override public void init() {}
        @Override public ConnectAdvice onConnect(ConnectContext ctx) {
            throw new RuntimeException("simulated failure");
        }
        @Override public void destroy() {}
    }
}
