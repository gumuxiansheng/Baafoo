package com.baafoo.core.model;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ResponseEntryTest {

    @Test
    public void testDefaults() {
        ResponseEntry r = new ResponseEntry();
        assertEquals(200, r.getStatusCode());
        assertTrue(r.getHeaders().isEmpty());
        assertEquals(0, r.getDelayMs());
    }

    @Test
    public void testGettersAndSetters() {
        ResponseEntry r = new ResponseEntry();
        r.setName("成功");
        r.setStatusCode(201);
        r.setBody("{\"ok\":true}");
        r.setDelayMs(500L);

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Custom", "value");
        r.setHeaders(headers);

        MatchCondition cond = MatchCondition.body("contains", "error");
        r.setCondition(cond);

        assertEquals("成功", r.getName());
        assertEquals(201, r.getStatusCode());
        assertEquals("{\"ok\":true}", r.getBody());
        assertEquals(500L, r.getDelayMs());
        assertEquals("value", r.getHeaders().get("X-Custom"));
        assertNotNull(r.getCondition());
        assertEquals("body", r.getCondition().getType());
    }

    @Test
    public void testToString() {
        ResponseEntry r = new ResponseEntry();
        r.setName("test");
        r.setStatusCode(200);
        assertTrue(r.toString().contains("test"));
    }
}
