package com.baafoo.agent.advice;

import com.baafoo.core.model.Rule;
import com.baafoo.core.model.ResponseEntry;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteManagerTest {

    @Test
    public void testRouteResultDefaults() {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        assertNull(result.protocol);
        assertNull(result.host);
        assertEquals(0, result.port);
        assertNull(result.serviceName);
        assertNull(result.method);
        assertNull(result.path);
        assertFalse(result.matched);
        assertNull(result.rule);
        assertNull(result.responseEntry);
        assertEquals(0, result.responseIndex);
    }

    @Test
    public void testRouteResultSetAllFields() {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        result.protocol = "http";
        result.host = "example.com";
        result.port = 8084;
        result.serviceName = "my-service";
        result.method = "GET";
        result.path = "/api/test";
        result.matched = true;
        result.rule = new Rule();
        result.responseEntry = new ResponseEntry();
        result.responseIndex = 1;

        assertEquals("http", result.protocol);
        assertEquals("example.com", result.host);
        assertEquals(8084, result.port);
        assertEquals("my-service", result.serviceName);
        assertEquals("GET", result.method);
        assertEquals("/api/test", result.path);
        assertTrue(result.matched);
        assertNotNull(result.rule);
        assertNotNull(result.responseEntry);
        assertEquals(1, result.responseIndex);
    }
}
