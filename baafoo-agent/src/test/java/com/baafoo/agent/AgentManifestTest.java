package com.baafoo.agent;

import org.junit.Test;
import static org.junit.Assert.*;

public class AgentManifestTest {

    @Test
    public void testConstants() {
        assertEquals(0, AgentManifest.MODE_STUB);
        assertEquals(1, AgentManifest.MODE_PASSTHROUGH);
        assertEquals(2, AgentManifest.MODE_RECORD);
        assertEquals(3, AgentManifest.MODE_RECORD_AND_STUB);
    }

    @Test
    public void testIsPassthrough() {
        AgentManifest.currentMode = AgentManifest.MODE_PASSTHROUGH;
        assertTrue(AgentManifest.isPassthrough());

        AgentManifest.currentMode = AgentManifest.MODE_STUB;
        assertFalse(AgentManifest.isPassthrough());
    }

    @Test
    public void testIsRecording() {
        AgentManifest.currentMode = AgentManifest.MODE_RECORD;
        assertTrue(AgentManifest.isRecording());

        AgentManifest.currentMode = AgentManifest.MODE_RECORD_AND_STUB;
        assertTrue(AgentManifest.isRecording());

        AgentManifest.currentMode = AgentManifest.MODE_STUB;
        assertFalse(AgentManifest.isRecording());

        AgentManifest.currentMode = AgentManifest.MODE_PASSTHROUGH;
        assertFalse(AgentManifest.isRecording());
    }

    @Test
    public void testGetModeName() {
        AgentManifest.currentMode = AgentManifest.MODE_STUB;
        assertEquals("stub", AgentManifest.getModeName());

        AgentManifest.currentMode = AgentManifest.MODE_PASSTHROUGH;
        assertEquals("passthrough", AgentManifest.getModeName());

        AgentManifest.currentMode = AgentManifest.MODE_RECORD;
        assertEquals("record", AgentManifest.getModeName());

        AgentManifest.currentMode = AgentManifest.MODE_RECORD_AND_STUB;
        assertEquals("record-and-stub", AgentManifest.getModeName());

        AgentManifest.currentMode = -1;
        assertEquals("unknown", AgentManifest.getModeName());
    }

    @Test
    public void testDefaults() {
        assertFalse(AgentManifest.agentLoaded);
        assertEquals("127.0.0.1", AgentManifest.serverHost);
        assertEquals(8080, AgentManifest.serverPort);
        assertEquals("default", AgentManifest.environmentId);
        assertEquals("", AgentManifest.agentId);
    }

    @Test
    public void testRouteTableInitialized() {
        assertNotNull(AgentManifest.ROUTE_TABLE.get());
    }
}
