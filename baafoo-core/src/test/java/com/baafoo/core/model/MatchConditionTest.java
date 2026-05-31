package com.baafoo.core.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class MatchConditionTest {

    @Test
    public void testDefaults() {
        MatchCondition c = new MatchCondition();
        assertTrue(c.isCaseSensitive());
    }

    @Test
    public void testMethodFactory() {
        MatchCondition c = MatchCondition.method("GET");
        assertEquals("method", c.getType());
        assertEquals("equals", c.getOperator());
        assertEquals("GET", c.getValue());
    }

    @Test
    public void testPathFactory() {
        MatchCondition c = MatchCondition.path("startsWith", "/api");
        assertEquals("path", c.getType());
        assertEquals("startsWith", c.getOperator());
        assertEquals("/api", c.getValue());
    }

    @Test
    public void testHeaderFactory() {
        MatchCondition c = MatchCondition.header("Content-Type", "contains", "json");
        assertEquals("header", c.getType());
        assertEquals("Content-Type", c.getKey());
        assertEquals("contains", c.getOperator());
        assertEquals("json", c.getValue());
    }

    @Test
    public void testQueryFactory() {
        MatchCondition c = MatchCondition.query("page", "equals", "1");
        assertEquals("query", c.getType());
        assertEquals("page", c.getKey());
        assertEquals("equals", c.getOperator());
        assertEquals("1", c.getValue());
    }

    @Test
    public void testBodyFactory() {
        MatchCondition c = MatchCondition.body("contains", "error");
        assertEquals("body", c.getType());
        assertEquals("contains", c.getOperator());
        assertEquals("error", c.getValue());
    }

    @Test
    public void testGettersAndSetters() {
        MatchCondition c = new MatchCondition();
        c.setType("custom");
        c.setOperator("regex");
        c.setKey("key");
        c.setValue("val");
        c.setCaseSensitive(false);

        assertEquals("custom", c.getType());
        assertEquals("regex", c.getOperator());
        assertEquals("key", c.getKey());
        assertEquals("val", c.getValue());
        assertFalse(c.isCaseSensitive());
    }

    @Test
    public void testToString() {
        MatchCondition c = MatchCondition.method("POST");
        assertTrue(c.toString().contains("POST"));
    }
}
