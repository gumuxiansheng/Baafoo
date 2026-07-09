package com.baafoo.server.mcp;

import org.junit.Test;

import static org.junit.Assert.*;

public class McpSchemaBuilderTest {

    @Test
    public void emptySchemaReturnsTypeObject() {
        String json = McpSchemaBuilder.create().buildJson();
        assertEquals("{\"type\":\"object\",\"properties\":{}}", json);
    }

    @Test
    public void stringPropertyAddsToStringProperties() {
        String json = McpSchemaBuilder.create()
                .stringProperty("name", "The name")
                .buildJson();
        assertTrue(json.contains("\"name\":{\"type\":\"string\",\"description\":\"The name\"}"));
    }

    @Test
    public void integerPropertyAddsIntegerType() {
        String json = McpSchemaBuilder.create()
                .integerProperty("port", "Port number")
                .buildJson();
        assertTrue(json.contains("\"port\":{\"type\":\"integer\",\"description\":\"Port number\"}"));
    }

    @Test
    public void booleanPropertyAddsBooleanType() {
        String json = McpSchemaBuilder.create()
                .booleanProperty("debug", "Enable debug")
                .buildJson();
        assertTrue(json.contains("\"debug\":{\"type\":\"boolean\",\"description\":\"Enable debug\"}"));
    }

    @Test
    public void requiredPropertyAppearsInRequiredArray() {
        String json = McpSchemaBuilder.create()
                .requiredString("host", "Server host")
                .buildJson();
        assertTrue(json.contains("\"required\":[\"host\"]"));
    }

    @Test
    public void requiredIntegerAppearsInRequiredArray() {
        String json = McpSchemaBuilder.create()
                .requiredInteger("port", "Port")
                .buildJson();
        assertTrue(json.contains("\"required\":[\"port\"]"));
    }

    @Test
    public void requiredBooleanAppearsInRequiredArray() {
        String json = McpSchemaBuilder.create()
                .requiredBoolean("flag", "A flag")
                .buildJson();
        assertTrue(json.contains("\"required\":[\"flag\"]"));
    }

    @Test
    public void multipleRequiredAppearInOrder() {
        String json = McpSchemaBuilder.create()
                .requiredString("a", "A")
                .requiredString("b", "B")
                .buildJson();
        assertTrue(json.contains("\"required\":[\"a\",\"b\"]"));
    }

    @Test
    public void noRequiredKeyWhenNoneAdded() {
        String json = McpSchemaBuilder.create()
                .stringProperty("opt", "Optional")
                .buildJson();
        assertFalse(json.contains("\"required\""));
    }

    @Test
    public void enumPropertyAddsEnumArray() {
        String json = McpSchemaBuilder.create()
                .enumProperty("mode", "Mode", "A", "B", "C")
                .buildJson();
        assertTrue(json.contains("\"enum\":[\"A\",\"B\",\"C\"]"));
        assertTrue(json.contains("\"type\":\"string\""));
    }

    @Test
    public void arrayPropertyAddsArrayType() {
        String json = McpSchemaBuilder.create()
                .arrayProperty("tags", "Tags list", "string")
                .buildJson();
        assertTrue(json.contains("\"type\":\"array\""));
        assertTrue(json.contains("\"items\":{\"type\":\"string\"}"));
    }

    @Test
    public void propertyNamedCorrectly() {
        String json = McpSchemaBuilder.create()
                .property("myProp", "number", "A number")
                .buildJson();
        assertTrue(json.contains("\"myProp\":{\"type\":\"number\",\"description\":\"A number\"}"));
    }

    @Test
    public void multiplePropertiesPreserveInsertionOrder() {
        String json = McpSchemaBuilder.create()
                .stringProperty("alpha", "First")
                .integerProperty("beta", "Second")
                .booleanProperty("gamma", "Third")
                .buildJson();
        int alphaIdx = json.indexOf("\"alpha\"");
        int betaIdx = json.indexOf("\"beta\"");
        int gammaIdx = json.indexOf("\"gamma\"");
        assertTrue(alphaIdx < betaIdx);
        assertTrue(betaIdx < gammaIdx);
    }

    @Test
    public void escapingQuotesInDescription() {
        String json = McpSchemaBuilder.create()
                .stringProperty("x", "Say \"hello\"")
                .buildJson();
        assertTrue(json.contains("Say \\\"hello\\\""));
    }

    @Test
    public void escapingNewlinesInDescription() {
        String json = McpSchemaBuilder.create()
                .stringProperty("x", "line1\nline2")
                .buildJson();
        assertTrue(json.contains("line1\\nline2"));
    }

    @Test
    public void escapingTabsInDescription() {
        String json = McpSchemaBuilder.create()
                .stringProperty("x", "col1\tcol2")
                .buildJson();
        assertTrue(json.contains("col1\\tcol2"));
    }

    @Test
    public void escapingBackslashesInDescription() {
        String json = McpSchemaBuilder.create()
                .stringProperty("x", "path\\to\\file")
                .buildJson();
        assertTrue(json.contains("path\\\\to\\\\file"));
    }

    @Test
    public void complexSchemaCombinesAllFeatures() {
        String json = McpSchemaBuilder.create()
                .requiredString("name", "Name")
                .requiredInteger("port", "Port")
                .booleanProperty("verbose", "Verbose")
                .enumProperty("level", "Level", "LOW", "HIGH")
                .arrayProperty("tags", "Tags", "string")
                .buildJson();
        assertTrue(json.contains("\"required\":[\"name\",\"port\"]"));
        assertTrue(json.contains("\"verbose\""));
        assertTrue(json.contains("\"level\""));
        assertTrue(json.contains("\"tags\""));
    }
}
