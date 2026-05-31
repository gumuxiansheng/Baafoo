package com.baafoo.core.model;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class RecordingEntryTest {

    @Test
    public void testDefaults() {
        RecordingEntry r = new RecordingEntry();
        assertTrue(r.getRequestHeaders().isEmpty());
        assertTrue(r.getResponseHeaders().isEmpty());
        assertTrue(r.getTags().isEmpty());
    }

    @Test
    public void testGettersAndSetters() {
        RecordingEntry r = new RecordingEntry();
        r.setId("rec-1");
        r.setRuleId("rule-1");
        r.setEnvironmentId("env-1");
        r.setAgentId("agent-1");
        r.setProtocol("http");
        r.setHost("api.example.com");
        r.setPort(80);
        r.setServiceName("my-service");
        r.setMethod("GET");
        r.setPath("/users");
        r.setRequestBody("request-body");
        r.setResponseStatusCode(200);
        r.setResponseBody("response-body");
        r.setResponseTimeMs(100L);
        r.setRecordedAt(12345L);

        Map<String, String> reqHeaders = new HashMap<String, String>();
        reqHeaders.put("Accept", "application/json");
        r.setRequestHeaders(reqHeaders);

        Map<String, String> respHeaders = new HashMap<String, String>();
        respHeaders.put("Content-Type", "application/json");
        r.setResponseHeaders(respHeaders);

        Map<String, String> tags = new HashMap<String, String>();
        tags.put("source", "test");
        r.setTags(tags);

        assertEquals("rec-1", r.getId());
        assertEquals("rule-1", r.getRuleId());
        assertEquals("env-1", r.getEnvironmentId());
        assertEquals("agent-1", r.getAgentId());
        assertEquals("http", r.getProtocol());
        assertEquals("api.example.com", r.getHost());
        assertEquals(80, r.getPort());
        assertEquals("my-service", r.getServiceName());
        assertEquals("GET", r.getMethod());
        assertEquals("/users", r.getPath());
        assertEquals("request-body", r.getRequestBody());
        assertEquals(200, r.getResponseStatusCode());
        assertEquals("response-body", r.getResponseBody());
        assertEquals(100L, r.getResponseTimeMs());
        assertEquals(12345L, r.getRecordedAt());
        assertEquals("application/json", r.getRequestHeaders().get("Accept"));
        assertEquals("application/json", r.getResponseHeaders().get("Content-Type"));
        assertEquals("test", r.getTags().get("source"));
    }

    @Test
    public void testToString() {
        RecordingEntry r = new RecordingEntry();
        r.setId("r1");
        r.setProtocol("http");
        r.setMethod("GET");
        assertTrue(r.toString().contains("r1"));
    }
}
