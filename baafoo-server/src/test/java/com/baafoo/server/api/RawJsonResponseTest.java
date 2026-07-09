package com.baafoo.server.api;

import org.junit.Test;
import static org.junit.Assert.*;

public class RawJsonResponseTest {

    @Test
    public void gettersReturnConstructorValues() {
        RawJsonResponse resp = new RawJsonResponse("{\"ok\":true}", "application/json");
        assertEquals("{\"ok\":true}", resp.getJson());
        assertEquals("application/json", resp.getContentType());
    }
}
