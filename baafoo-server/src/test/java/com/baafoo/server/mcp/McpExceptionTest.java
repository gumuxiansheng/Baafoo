package com.baafoo.server.mcp;

import org.junit.Test;
import static org.junit.Assert.*;

public class McpExceptionTest {

    @Test
    public void constructorSetsFields() {
        McpException ex = new McpException(403, "Forbidden");
        assertEquals(403, ex.getStatusCode());
        assertEquals("Forbidden", ex.getMessage());
    }

    @Test
    public void isRuntimeException() {
        assertTrue(new McpException(500, "err") instanceof RuntimeException);
    }
}
