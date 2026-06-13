package com.baafoo.core.config;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ServerConfigTest {

    @Test
    public void testDefaults() {
        ServerConfig c = new ServerConfig();
        assertEquals(8084, c.getHttpPort());
        assertTrue(c.getProtocolPorts().containsKey("http"));
        assertEquals(9000, c.getPortForProtocol("http"));
        assertEquals(9001, c.getPortForProtocol("tcp"));
        assertEquals(9002, c.getPortForProtocol("kafka"));
        assertEquals("./data", c.getDataDir());
        assertTrue(c.isCorsEnabled());
        assertEquals(60, c.getAgentHeartbeatTimeoutSec());
        assertEquals(50, c.getMaxAgentsPerEnvironment());
        assertEquals("passthrough", c.getUnmatchedDefault());
        assertNotNull(c.getDatabase());
        assertEquals("h2", c.getDatabase().getType());
    }

    @Test
    public void testGetPortForProtocolUnknown() {
        ServerConfig c = new ServerConfig();
        assertEquals(0, c.getPortForProtocol("unknown"));
    }

    @Test
    public void testGettersAndSetters() {
        ServerConfig c = new ServerConfig();
        c.setHttpPort(9090);
        assertEquals(9090, c.getHttpPort());

        Map<String, Integer> ports = new HashMap<String, Integer>();
        ports.put("custom", 9999);
        c.setProtocolPorts(ports);
        assertEquals(9999, c.getPortForProtocol("custom"));

        c.setDataDir("/data/baafoo");
        assertEquals("/data/baafoo", c.getDataDir());

        c.setRulesDir("/data/rules");
        assertEquals("/data/rules", c.getRulesDir());

        c.setRecordingsDir("/data/rec");
        assertEquals("/data/rec", c.getRecordingsDir());

        c.setRecordingRetentionDays(30);
        assertEquals(30, c.getRecordingRetentionDays());

        c.setMaxRulesPerPage(50);
        assertEquals(50, c.getMaxRulesPerPage());

        c.setCorsEnabled(false);
        assertFalse(c.isCorsEnabled());

        c.setRequestLogging(true);
        assertTrue(c.isRequestLogging());

        c.setAgentHeartbeatTimeoutSec(120);
        assertEquals(120, c.getAgentHeartbeatTimeoutSec());

        c.setMaxAgentsPerEnvironment(100);
        assertEquals(100, c.getMaxAgentsPerEnvironment());

        c.setUnmatchedDefault("404");
        assertEquals("404", c.getUnmatchedDefault());

        c.setWebConsolePath("./web/dist");
        assertEquals("./web/dist", c.getWebConsolePath());
    }

    @Test
    public void testDatabaseConfig() {
        ServerConfig.DatabaseConfig db = new ServerConfig.DatabaseConfig();
        assertEquals("h2", db.getType());
        assertEquals("sa", db.getUsername());
        assertEquals("", db.getPassword());

        db.setType("postgresql");
        db.setUrl("jdbc:postgresql://localhost:5432/baafoo");
        db.setUsername("admin");
        db.setPassword("secret");

        assertEquals("postgresql", db.getType());
        assertEquals("jdbc:postgresql://localhost:5432/baafoo", db.getUrl());
        assertEquals("admin", db.getUsername());
        assertEquals("secret", db.getPassword());
    }

    @Test
    public void testToString() {
        ServerConfig c = new ServerConfig();
        assertNotNull(c.toString());
        assertTrue(c.toString().contains("8084"));
    }

    @Test
    public void testDatabaseConfigToString() {
        ServerConfig.DatabaseConfig db = new ServerConfig.DatabaseConfig();
        assertTrue(db.toString().contains("h2"));
    }
}
