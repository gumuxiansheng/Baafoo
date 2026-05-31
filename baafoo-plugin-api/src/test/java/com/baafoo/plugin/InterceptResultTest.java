package com.baafoo.plugin;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class InterceptResultTest {

    @Test
    public void testDefaultValues() {
        InterceptResult r = new InterceptResult();
        assertFalse(r.isStubbed());
        assertNotNull(r.getResponseData());
        assertEquals(0, r.getResponseData().length);
        assertTrue(r.getResponseHeaders().isEmpty());
        assertEquals(200, r.getStatusCode());
        assertNull(r.getErrorMessage());
    }

    @Test
    public void testStubFactory() {
        byte[] data = "hello".getBytes();
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Content-Type", "text/plain");
        InterceptResult r = InterceptResult.stub(data, headers, 201);

        assertTrue(r.isStubbed());
        assertArrayEquals(data, r.getResponseData());
        assertEquals("text/plain", r.getResponseHeaders().get("Content-Type"));
        assertEquals(201, r.getStatusCode());
    }

    @Test
    public void testPassthroughFactory() {
        InterceptResult r = InterceptResult.passthrough();
        assertFalse(r.isStubbed());
        assertEquals(200, r.getStatusCode());
    }

    @Test
    public void testErrorFactory() {
        InterceptResult r = InterceptResult.error("something went wrong");
        assertTrue(r.isStubbed());
        assertEquals("something went wrong", r.getErrorMessage());
        assertEquals(500, r.getStatusCode());
    }

    @Test
    public void testGettersAndSetters() {
        InterceptResult r = new InterceptResult();
        r.setStubbed(true);
        r.setResponseData("data".getBytes());
        r.setStatusCode(400);

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Error", "true");
        r.setResponseHeaders(headers);

        r.setErrorMessage("bad request");

        Map<String, Object> meta = new HashMap<String, Object>();
        meta.put("key", "val");
        r.setMetadata(meta);

        assertTrue(r.isStubbed());
        assertArrayEquals("data".getBytes(), r.getResponseData());
        assertEquals(400, r.getStatusCode());
        assertEquals("true", r.getResponseHeaders().get("X-Error"));
        assertEquals("bad request", r.getErrorMessage());
        assertEquals("val", r.getMetadata().get("key"));
    }

    @Test
    public void testToString() {
        InterceptResult r = InterceptResult.stub("test".getBytes(), new HashMap<String, String>(), 200);
        assertNotNull(r.toString());
        assertTrue(r.toString().contains("stubbed=true"));
    }
}
