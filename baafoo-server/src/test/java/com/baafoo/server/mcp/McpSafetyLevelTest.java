package com.baafoo.server.mcp;

import org.junit.Test;
import static org.junit.Assert.*;

public class McpSafetyLevelTest {

    @Test
    public void hasThreeValues() {
        assertEquals(3, McpSafetyLevel.values().length);
    }

    @Test
    public void containsExpectedValues() {
        assertNotNull(McpSafetyLevel.valueOf("READ_ONLY"));
        assertNotNull(McpSafetyLevel.valueOf("CONTROLLED_WRITE"));
        assertNotNull(McpSafetyLevel.valueOf("AUDIT_REQUIRED"));
    }
}
