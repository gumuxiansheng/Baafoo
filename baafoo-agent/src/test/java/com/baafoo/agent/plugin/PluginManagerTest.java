package com.baafoo.agent.plugin;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptTarget;
import org.junit.Test;

import static org.junit.Assert.*;

public class PluginManagerTest {

    @Test
    public void testNoPluginDirectory() {
        // Non-existent directory should not throw
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        assertNull(pm.getPlugin(InterceptTarget.SOCKET));
    }

    @Test
    public void testGetPluginForProtocolHttp() {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        // No plugins loaded, but protocol resolution should work
        assertNull(pm.getPluginForProtocol("http"));
    }

    @Test
    public void testGetPluginForProtocolKafka() {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        assertNull(pm.getPluginForProtocol("kafka"));
    }

    @Test
    public void testGetPluginForProtocolNull() {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        assertNull(pm.getPluginForProtocol(null));
    }

    @Test
    public void testGetPluginForProtocolUnknown() {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        assertNull(pm.getPluginForProtocol("unknown-protocol"));
    }

    @Test
    public void testShutdownWithNoPlugins() {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        // Should not throw
        pm.shutdown();
        assertNull(pm.getPlugin(InterceptTarget.SOCKET));
    }
}
