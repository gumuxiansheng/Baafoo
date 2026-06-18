package com.baafoo.core.util;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link JsonPathUtil}.
 *
 * <p>Covers the JSON path extraction logic shared by {@link TemplateEngine}
 * (for {@code {{request.body.xxx}}} variable substitution) and {@link MatchEngine}
 * (for {@code bodyJsonPath} condition matching and GraphQL syntactic-sugar
 * conditions).</p>
 */
public class JsonPathUtilTest {

    @Test
    public void testExtractNullBody() {
        assertEquals("", JsonPathUtil.extract(null, "$.field"));
    }

    @Test
    public void testExtractEmptyBody() {
        assertEquals("", JsonPathUtil.extract("", "$.field"));
    }

    @Test
    public void testExtractNullPath() {
        assertEquals("", JsonPathUtil.extract("{\"a\":1}", null));
    }

    @Test
    public void testExtractEmptyPath() {
        assertEquals("", JsonPathUtil.extract("{\"a\":1}", ""));
    }

    @Test
    public void testExtractTopLevelField() {
        assertEquals("alice", JsonPathUtil.extract("{\"name\":\"alice\"}", "name"));
    }

    @Test
    public void testExtractTopLevelFieldWithDollarPrefix() {
        assertEquals("alice", JsonPathUtil.extract("{\"name\":\"alice\"}", "$.name"));
    }

    @Test
    public void testExtractNestedField() {
        String body = "{\"user\":{\"address\":{\"city\":\"Beijing\"}}}";
        assertEquals("Beijing", JsonPathUtil.extract(body, "user.address.city"));
    }

    @Test
    public void testExtractNestedFieldWithDollarPrefix() {
        String body = "{\"user\":{\"address\":{\"city\":\"Beijing\"}}}";
        assertEquals("Beijing", JsonPathUtil.extract(body, "$.user.address.city"));
    }

    @Test
    public void testExtractArrayIndex() {
        String body = "{\"items\":[\"a\",\"b\",\"c\"]}";
        assertEquals("a", JsonPathUtil.extract(body, "items[0]"));
        assertEquals("a", JsonPathUtil.extract(body, "$.items[0]"));
        assertEquals("b", JsonPathUtil.extract(body, "$.items[1]"));
        assertEquals("c", JsonPathUtil.extract(body, "$.items[2]"));
    }

    @Test
    public void testExtractArrayIndexOutOfBounds() {
        String body = "{\"items\":[\"a\",\"b\"]}";
        assertEquals("", JsonPathUtil.extract(body, "items[5]"));
    }

    @Test
    public void testExtractMissingField() {
        assertEquals("", JsonPathUtil.extract("{\"a\":1}", "b"));
    }

    @Test
    public void testExtractMissingNestedField() {
        assertEquals("", JsonPathUtil.extract("{\"a\":{\"b\":1}}", "a.c"));
    }

    @Test
    public void testExtractInvalidJson() {
        assertEquals("", JsonPathUtil.extract("not json", "$.field"));
    }

    @Test
    public void testExtractObjectNodeReturnsJsonString() {
        String body = "{\"user\":{\"name\":\"alice\",\"age\":30}}";
        String result = JsonPathUtil.extract(body, "user");
        // The returned string should be valid JSON containing both fields
        assertTrue(result.contains("\"name\":\"alice\""));
        assertTrue(result.contains("\"age\":30"));
    }

    @Test
    public void testExtractArrayNodeReturnsJsonString() {
        String body = "{\"items\":[1,2,3]}";
        String result = JsonPathUtil.extract(body, "items");
        assertEquals("[1,2,3]", result);
    }

    @Test
    public void testExtractNumericValue() {
        assertEquals("42", JsonPathUtil.extract("{\"count\":42}", "count"));
    }

    @Test
    public void testExtractBooleanValue() {
        assertEquals("true", JsonPathUtil.extract("{\"flag\":true}", "flag"));
    }

    @Test
    public void testExtractNullValue() {
        // JSON null → extract returns empty string (consistent with missing field)
        assertEquals("", JsonPathUtil.extract("{\"field\":null}", "field"));
    }

    @Test
    public void testExtractRootDollarOnly() {
        // Path "$" should return the whole body
        String body = "{\"a\":1}";
        assertEquals(body, JsonPathUtil.extract(body, "$"));
    }

    @Test
    public void testExistsPresent() {
        assertTrue(JsonPathUtil.exists("{\"name\":\"alice\"}", "name"));
    }

    @Test
    public void testExistsMissing() {
        assertFalse(JsonPathUtil.exists("{\"a\":1}", "b"));
    }

    @Test
    public void testExistsNested() {
        String body = "{\"user\":{\"address\":{\"city\":\"Beijing\"}}}";
        assertTrue(JsonPathUtil.exists(body, "user.address.city"));
        assertFalse(JsonPathUtil.exists(body, "user.address.country"));
    }

    @Test
    public void testExistsNullBody() {
        assertFalse(JsonPathUtil.exists(null, "field"));
    }

    @Test
    public void testExistsJsonNullValue() {
        // A JSON null is not considered "present" by exists()
        assertFalse(JsonPathUtil.exists("{\"field\":null}", "field"));
    }

    @Test
    public void testExistsArrayIndex() {
        String body = "{\"items\":[\"a\",\"b\"]}";
        assertTrue(JsonPathUtil.exists(body, "items[0]"));
        assertFalse(JsonPathUtil.exists(body, "items[5]"));
    }
}
