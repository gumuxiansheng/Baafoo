package com.baafoo.core.api;

import org.junit.Test;
import static org.junit.Assert.*;

public class ApiResponseTest {

    @Test
    public void testOkWithData() {
        ApiResponse<String> r = ApiResponse.ok("test-data");
        assertTrue(r.isSuccess());
        assertEquals(200, r.getCode());
        assertEquals("OK", r.getMessage());
        assertEquals("test-data", r.getData());
        assertTrue(r.getTimestamp() > 0);
    }

    @Test
    public void testOkWithMessageAndData() {
        ApiResponse<Integer> r = ApiResponse.ok("custom msg", 42);
        assertTrue(r.isSuccess());
        assertEquals(200, r.getCode());
        assertEquals("custom msg", r.getMessage());
        assertEquals(Integer.valueOf(42), r.getData());
    }

    @Test
    public void testCreated() {
        ApiResponse<String> r = ApiResponse.created("new-id");
        assertTrue(r.isSuccess());
        assertEquals(201, r.getCode());
        assertEquals("Created", r.getMessage());
        assertEquals("new-id", r.getData());
    }

    @Test
    public void testFail() {
        ApiResponse<String> r = ApiResponse.fail(400, "bad request");
        assertFalse(r.isSuccess());
        assertEquals(400, r.getCode());
        assertEquals("bad request", r.getMessage());
        assertNull(r.getData());
    }

    @Test
    public void testBadRequest() {
        ApiResponse<String> r = ApiResponse.badRequest("invalid input");
        assertFalse(r.isSuccess());
        assertEquals(400, r.getCode());
        assertEquals("invalid input", r.getMessage());
    }

    @Test
    public void testNotFound() {
        ApiResponse<String> r = ApiResponse.notFound("not found");
        assertFalse(r.isSuccess());
        assertEquals(404, r.getCode());
        assertEquals("not found", r.getMessage());
    }

    @Test
    public void testInternalError() {
        ApiResponse<String> r = ApiResponse.internalError("server error");
        assertFalse(r.isSuccess());
        assertEquals(500, r.getCode());
        assertEquals("server error", r.getMessage());
    }

    @Test
    public void testToString() {
        ApiResponse<String> r = ApiResponse.ok("data");
        String str = r.toString();
        assertTrue(str.contains("success=true"));
        assertTrue(str.contains("code=200"));
    }
}
