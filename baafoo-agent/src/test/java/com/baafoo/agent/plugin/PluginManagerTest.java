package com.baafoo.agent.plugin;

import com.baafoo.core.config.AgentConfig;
import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptTarget;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

    @Test
    public void testPluginsConfigConstructor() {
        AgentConfig.PluginsConfig pluginsConfig = new AgentConfig.PluginsConfig();
        pluginsConfig.setDirectory("/nonexistent/path/plugins");
        PluginManager pm = new PluginManager(pluginsConfig);
        assertNull(pm.getPlugin(InterceptTarget.SOCKET));
    }

    @Test
    public void testPluginsConfigNull() {
        // Null PluginsConfig should fall back to defaults
        PluginManager pm = new PluginManager((AgentConfig.PluginsConfig) null);
        assertNull(pm.getPlugin(InterceptTarget.SOCKET));
    }

    @Test
    public void testPluginsConfigDisabled() {
        AgentConfig.PluginsConfig pluginsConfig = new AgentConfig.PluginsConfig();
        pluginsConfig.setEnabled(false);
        PluginManager pm = new PluginManager(pluginsConfig);
        // Disabled: no plugins loaded regardless of directory
        assertNull(pm.getPlugin(InterceptTarget.SOCKET));
    }

    @Test
    public void testGetPluginConfigByName() {
        Map<String, Object> tdmqConfig = new HashMap<String, Object>();
        tdmqConfig.put("brokerPort", 9005);

        Map<String, Map<String, Object>> configs = new HashMap<String, Map<String, Object>>();
        configs.put("tdmq", tdmqConfig);

        AgentConfig.PluginsConfig pluginsConfig = new AgentConfig.PluginsConfig();
        pluginsConfig.setDirectory("/nonexistent/path/plugins");
        pluginsConfig.setConfigs(configs);

        PluginManager pm = new PluginManager(pluginsConfig);
        Map<String, Object> config = pm.getPluginConfig("tdmq");
        assertEquals(9005, config.get("brokerPort"));

        // Non-existent plugin returns empty map
        assertTrue(pm.getPluginConfig("nonexistent").isEmpty());
        assertTrue(pm.getPluginConfig((String) null).isEmpty());
    }

    @Test
    public void testGetPluginConfigByTarget() {
        // No plugins loaded, so config by target should return empty
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        assertTrue(pm.getPluginConfig(InterceptTarget.KAFKA).isEmpty());
        assertTrue(pm.getPluginConfig(InterceptTarget.PULSAR).isEmpty());
    }

    @Test
    public void testResolveTargetAllValues() {
        PluginManager pm = new PluginManager("/nonexistent/path/plugins");
        // No plugins loaded, so getPluginForProtocol returns null for all.
        // The key assertion is that no exception is thrown for any protocol name.
        assertNull(pm.getPluginForProtocol("http"));
        assertNull(pm.getPluginForProtocol("tcp"));
        assertNull(pm.getPluginForProtocol("socket"));
        assertNull(pm.getPluginForProtocol("nio"));
        assertNull(pm.getPluginForProtocol("nio-socket"));
        assertNull(pm.getPluginForProtocol("kafka"));
        assertNull(pm.getPluginForProtocol("pulsar"));
        assertNull(pm.getPluginForProtocol("jms"));
        assertNull(pm.getPluginForProtocol("consul-dns"));
        assertNull(pm.getPluginForProtocol("consul"));
        assertNull(pm.getPluginForProtocol("consul-api"));
        assertNull(pm.getPluginForProtocol("feign"));
        assertNull(pm.getPluginForProtocol(""));
        assertNull(pm.getPluginForProtocol("random"));
    }
}
