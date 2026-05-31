package com.baafoo.core.model;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class EnvironmentTest {

    @Test
    public void testDefaults() {
        Environment env = new Environment();
        assertEquals(EnvironmentMode.RECORD_AND_STUB, env.getMode());
        assertTrue(env.getAgentIds().isEmpty());
        assertTrue(env.getVariables().isEmpty());
        assertTrue(env.getMetadata().isEmpty());
    }

    @Test
    public void testGettersAndSetters() {
        Environment env = new Environment();
        env.setId("env-1");
        env.setName("dev");
        env.setMode(EnvironmentMode.STUB);
        env.setAgentIds(Arrays.asList("agent-a", "agent-b"));

        Map<String, String> vars = new HashMap<String, String>();
        vars.put("key1", "val1");
        env.setVariables(vars);

        Map<String, String> meta = new HashMap<String, String>();
        meta.put("createdBy", "admin");
        env.setMetadata(meta);

        env.setCreatedAt(1000L);
        env.setUpdatedAt(2000L);

        assertEquals("env-1", env.getId());
        assertEquals("dev", env.getName());
        assertEquals(EnvironmentMode.STUB, env.getMode());
        assertEquals(2, env.getAgentIds().size());
        assertEquals("val1", env.getVariables().get("key1"));
        assertEquals("admin", env.getMetadata().get("createdBy"));
        assertEquals(1000L, env.getCreatedAt());
        assertEquals(2000L, env.getUpdatedAt());
    }

    @Test
    public void testIsStubMode() {
        Environment env = new Environment();
        env.setMode(EnvironmentMode.STUB);
        assertTrue(env.isStubMode());
        assertFalse(env.isRecording());

        env.setMode(EnvironmentMode.PASSTHROUGH);
        assertFalse(env.isStubMode());
        assertFalse(env.isRecording());

        env.setMode(EnvironmentMode.RECORD);
        assertFalse(env.isStubMode());
        assertTrue(env.isRecording());

        env.setMode(EnvironmentMode.RECORD_AND_STUB);
        assertTrue(env.isStubMode());
        assertTrue(env.isRecording());
    }

    @Test
    public void testToString() {
        Environment env = new Environment();
        env.setId("e1");
        env.setName("prod");
        env.setMode(EnvironmentMode.STUB);
        String s = env.toString();
        assertTrue(s.contains("e1"));
        assertTrue(s.contains("prod"));
        assertTrue(s.contains("STUB"));
    }
}
