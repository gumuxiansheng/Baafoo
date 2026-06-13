package com.baafoo.core.config;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AgentConfigTest {

    @Test
    public void testDefaults() {
        AgentConfig c = new AgentConfig();
        assertEquals(30, c.getHeartbeatIntervalSec());
        assertEquals(10, c.getPollIntervalSec());
        assertFalse(c.isConsulEnabled());
        assertTrue(c.getProtocols().isEmpty());
        assertEquals(10 * 1024 * 1024, c.getMaxRecordingSize());
        assertTrue(c.isHotReload());
        assertEquals(3, c.getConnectionRetries());
        assertEquals(1000, c.getRetryBackoffMs());

        // Nested ServerConnection defaults
        assertNotNull(c.getServer());
        assertEquals("127.0.0.1", c.getServer().getHost());
        assertFalse(c.getServer().isUseSsl());
        assertEquals(8084, c.getServer().getApiPort());
        assertEquals(9000, c.getServer().getHttpPort());
        assertEquals(9001, c.getServer().getTcpPort());
        assertEquals(9002, c.getServer().getKafkaPort());
        assertEquals(9003, c.getServer().getPulsarPort());
        assertEquals(9004, c.getServer().getJmsPort());
    }

    @Test
    public void testGettersAndSetters() {
        AgentConfig c = new AgentConfig();
        c.setAgentId("agent-1");
        c.setEnvironment("prod");
        c.setServerUrl("http://server:8084");
        c.setConsulEnabled(true);
        c.setConsulAddress("consul:8500");
        c.setProtocols(Arrays.asList("http", "tcp"));
        c.setRulesFilePath("/tmp/rules.yml");
        c.setHotReload(false);
        c.setConnectionRetries(5);
        c.setRetryBackoffMs(2000);

        assertEquals("agent-1", c.getAgentId());
        assertEquals("prod", c.getEnvironment());
        assertEquals("http://server:8084", c.getServerUrl());
        assertTrue(c.isConsulEnabled());
        assertEquals("consul:8500", c.getConsulAddress());
        assertEquals(2, c.getProtocols().size());
        assertEquals("/tmp/rules.yml", c.getRulesFilePath());
        assertFalse(c.isHotReload());
        assertEquals(5, c.getConnectionRetries());
        assertEquals(2000, c.getRetryBackoffMs());
    }

    @Test
    public void testMaxRecordingSizeSetter() {
        AgentConfig c = new AgentConfig();
        c.setMaxRecordingSize(5 * 1024 * 1024);
        assertEquals(5 * 1024 * 1024, c.getMaxRecordingSize());
    }

    @Test
    public void testToString() {
        AgentConfig c = new AgentConfig();
        c.setAgentId("a1");
        c.setEnvironment("dev");
        c.setServerUrl("http://localhost:8084");
        String s = c.toString();
        assertTrue(s.contains("a1"));
        assertTrue(s.contains("dev"));
        assertTrue(s.contains("http://localhost:8084"));
    }
}
