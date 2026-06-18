package com.baafoo.core.util;

import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.OpenApiImporter.OpenApiImportException;
import com.baafoo.core.util.OpenApiImporter.OpenApiImportResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link OpenApiImporter} (PRD §1 R-S10 Phase 1).
 *
 * <p>Tests cover OpenAPI 3.0 JSON parsing, rule generation, path parameter
 * regex conversion, example-first response body strategy, and edge cases.</p>
 */
public class OpenApiImporterTest {

    private final OpenApiImporter importer = new OpenApiImporter();
    private final ObjectMapper mapper = new ObjectMapper();

    // ===== Basic parsing =====

    @Test
    public void testEmptyContentThrowsException() throws Exception {
        try {
            importer.importSpec("", "openapi-", Collections.<String>emptyList());
            fail("Should throw OpenApiImportException");
        } catch (OpenApiImportException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void testNullContentThrowsException() throws Exception {
        try {
            importer.importSpec(null, "openapi-", Collections.<String>emptyList());
            fail("Should throw OpenApiImportException");
        } catch (OpenApiImportException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void testInvalidJsonThrowsException() throws Exception {
        try {
            importer.importSpec("{invalid json", "openapi-", Collections.<String>emptyList());
            fail("Should throw OpenApiImportException");
        } catch (OpenApiImportException e) {
            assertTrue(e.getMessage().contains("Failed to parse JSON"));
        }
    }

    @Test
    public void testMissingOpenapiFieldThrowsException() throws Exception {
        try {
            importer.importSpec("{\"info\":{\"title\":\"Test\"}}", "openapi-",
                    Collections.<String>emptyList());
            fail("Should throw OpenApiImportException");
        } catch (OpenApiImportException e) {
            assertTrue(e.getMessage().contains("openapi"));
        }
    }

    @Test
    public void testSwagger2NotSupported() throws Exception {
        try {
            importer.importSpec("{\"swagger\":\"2.0\",\"paths\":{}}", "openapi-",
                    Collections.<String>emptyList());
            fail("Should throw OpenApiImportException");
        } catch (OpenApiImportException e) {
            assertTrue(e.getMessage().contains("Swagger 2.0"));
        }
    }

    @Test
    public void testOpenApi2NotSupported() throws Exception {
        try {
            importer.importSpec("{\"openapi\":\"2.0\",\"paths\":{}}", "openapi-",
                    Collections.<String>emptyList());
            fail("Should throw OpenApiImportException");
        } catch (OpenApiImportException e) {
            assertTrue(e.getMessage().contains("3.x"));
        }
    }

    @Test
    public void testEmptyPathsReturnsEmptyResult() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());
        assertEquals(0, result.getGeneratedCount());
        assertEquals(0, result.getSkippedCount());
    }

    @Test
    public void testMissingPathsReturnsEmptyResult() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\"}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());
        assertEquals(0, result.getGeneratedCount());
    }

    // ===== Rule generation =====

    @Test
    public void testSimpleGetRule() throws Exception {
        String spec = buildSimpleSpec();
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        assertEquals(1, result.getGeneratedCount());
        assertEquals(0, result.getSkippedCount());

        Rule rule = result.getRules().get(0);
        assertEquals("openapi-get-api-users", rule.getId());
        assertEquals("http", rule.getProtocol());
        assertTrue(rule.isEnabled());
        assertEquals(100, rule.getPriority());
        assertNotNull(rule.getConditions());
        assertEquals(2, rule.getConditions().size());

        // Method condition
        MatchCondition methodCond = rule.getConditions().get(0);
        assertEquals("method", methodCond.getType());
        assertEquals("equals", methodCond.getOperator());
        assertEquals("GET", methodCond.getValue());

        // Path condition (no params → equals)
        MatchCondition pathCond = rule.getConditions().get(1);
        assertEquals("path", pathCond.getType());
        assertEquals("equals", pathCond.getOperator());
        assertEquals("/api/users", pathCond.getValue());
    }

    @Test
    public void testRuleNameFromOperationId() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"operationId\":\"listUsers\"," +
                "\"responses\":{\"200\":{\"description\":\"OK\"}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        Rule rule = result.getRules().get(0);
        assertEquals("listUsers", rule.getName());
    }

    @Test
    public void testRuleNameFallbackToMethodPath() throws Exception {
        String spec = buildSimpleSpec();
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        Rule rule = result.getRules().get(0);
        assertEquals("GET /api/users", rule.getName());
    }

    @Test
    public void testMultipleMethodsGenerateMultipleRules() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{" +
                "\"get\":{\"operationId\":\"listUsers\",\"responses\":{\"200\":{\"description\":\"OK\"}}}," +
                "\"post\":{\"operationId\":\"createUser\",\"responses\":{\"201\":{\"description\":\"Created\"}}}" +
                "}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        assertEquals(2, result.getGeneratedCount());
        assertEquals("listUsers", result.getRules().get(0).getName());
        assertEquals("createUser", result.getRules().get(1).getName());
    }

    @Test
    public void testMultiplePathsGenerateMultipleRules() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{\"description\":\"OK\"}}}}," +
                "\"/api/orders\":{\"get\":{\"responses\":{\"200\":{\"description\":\"OK\"}}}}" +
                "}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        assertEquals(2, result.getGeneratedCount());
    }

    // ===== Path parameter regex conversion =====

    @Test
    public void testPathParamConvertedToRegex() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users/{id}\":{\"get\":{\"responses\":{\"200\":{\"description\":\"OK\"}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        Rule rule = result.getRules().get(0);
        MatchCondition pathCond = rule.getConditions().get(1);
        assertEquals("path", pathCond.getType());
        assertEquals("regex", pathCond.getOperator());
        assertEquals("/api/users/[^/]+", pathCond.getValue());
    }

    @Test
    public void testMultiplePathParamsConvertedToRegex() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users/{userId}/orders/{orderId}\":{\"get\":{\"responses\":{\"200\":{\"description\":\"OK\"}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        Rule rule = result.getRules().get(0);
        MatchCondition pathCond = rule.getConditions().get(1);
        assertEquals("regex", pathCond.getOperator());
        assertEquals("/api/users/[^/]+/orders/[^/]+", pathCond.getValue());
    }

    @Test
    public void testConvertPathToRegexDirectly() {
        assertEquals("/api/users/[^/]+", importer.convertPathToRegex("/api/users/{id}"));
        assertEquals("/api/users", importer.convertPathToRegex("/api/users"));
        assertEquals("/api/users/[^/]+/orders/[^/]+",
                importer.convertPathToRegex("/api/users/{userId}/orders/{orderId}"));
    }

    // ===== Status code extraction =====

    @Test
    public void testStatusCode200Extracted() throws Exception {
        String spec = buildSimpleSpec();
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        assertEquals(200, entry.getStatusCode());
    }

    @Test
    public void testStatusCode201Extracted() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"post\":{\"responses\":{\"201\":{\"description\":\"Created\"}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        assertEquals(201, entry.getStatusCode());
    }

    @Test
    public void testStatusCodePrefers200OverOther2xx() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{" +
                "\"201\":{\"description\":\"Created\"}," +
                "\"200\":{\"description\":\"OK\"}" +
                "}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        assertEquals(200, entry.getStatusCode());
    }

    @Test
    public void testStatusCodeFallsBackToFirst2xx() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{" +
                "\"204\":{\"description\":\"No Content\"}" +
                "}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        assertEquals(204, entry.getStatusCode());
    }

    // ===== Example-first response body =====

    @Test
    public void testResponseBodyFromResponseExample() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{" +
                "\"description\":\"OK\"," +
                "\"example\":{\"id\":1,\"name\":\"John\"}" +
                "}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        JsonNode body = mapper.readTree(entry.getBody());
        assertEquals(1, body.get("id").asInt());
        assertEquals("John", body.get("name").asText());
    }

    @Test
    public void testResponseBodyFromContentExample() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{" +
                "\"description\":\"OK\"," +
                "\"content\":{\"application/json\":{" +
                "\"example\":{\"id\":42,\"email\":\"test@test.com\"}" +
                "}}" +
                "}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        JsonNode body = mapper.readTree(entry.getBody());
        assertEquals(42, body.get("id").asInt());
        assertEquals("test@test.com", body.get("email").asText());
    }

    @Test
    public void testResponseBodyFromSchemaExample() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{" +
                "\"description\":\"OK\"," +
                "\"content\":{\"application/json\":{\"schema\":{" +
                "\"type\":\"object\"," +
                "\"properties\":{\"id\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"}}," +
                "\"example\":{\"id\":99,\"name\":\"Alice\"}" +
                "}}" +
                "}}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        JsonNode body = mapper.readTree(entry.getBody());
        assertEquals(99, body.get("id").asInt());
        assertEquals("Alice", body.get("name").asText());
    }

    @Test
    public void testResponseBodyFromSchemaProperties() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{" +
                "\"description\":\"OK\"," +
                "\"content\":{\"application/json\":{\"schema\":{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"id\":{\"type\":\"integer\"}," +
                "\"name\":{\"type\":\"string\"}," +
                "\"active\":{\"type\":\"boolean\"}," +
                "\"score\":{\"type\":\"number\"}" +
                "}" +
                "}}" +
                "}}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        JsonNode body = mapper.readTree(entry.getBody());
        assertEquals(0, body.get("id").asInt());
        assertEquals("string", body.get("name").asText());
        assertFalse(body.get("active").asBoolean());
        assertTrue(body.has("score"));
    }

    @Test
    public void testResponseBodyWithStringFormats() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{" +
                "\"description\":\"OK\"," +
                "\"content\":{\"application/json\":{\"schema\":{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"email\":{\"type\":\"string\",\"format\":\"email\"}," +
                "\"createdAt\":{\"type\":\"string\",\"format\":\"date-time\"}," +
                "\"birthDate\":{\"type\":\"string\",\"format\":\"date\"}," +
                "\"id\":{\"type\":\"string\",\"format\":\"uuid\"}" +
                "}" +
                "}}" +
                "}}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        JsonNode body = mapper.readTree(entry.getBody());
        assertEquals("user@example.com", body.get("email").asText());
        assertEquals("2024-01-01T00:00:00Z", body.get("createdAt").asText());
        assertEquals("2024-01-01", body.get("birthDate").asText());
        assertEquals("00000000-0000-0000-0000-000000000000", body.get("id").asText());
    }

    @Test
    public void testResponseBodyWithArrayProperty() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{" +
                "\"description\":\"OK\"," +
                "\"content\":{\"application/json\":{\"schema\":{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}" +
                "}" +
                "}}" +
                "}}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        JsonNode body = mapper.readTree(entry.getBody());
        assertTrue(body.get("tags").isArray());
        assertEquals(1, body.get("tags").size());
        assertEquals("string", body.get("tags").get(0).asText());
    }

    @Test
    public void testResponseBodyWithRefReturnsEmptyObject() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{" +
                "\"description\":\"OK\"," +
                "\"content\":{\"application/json\":{\"schema\":{" +
                "\"$ref\":\"#/components/schemas/User\"" +
                "}}" +
                "}}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        assertEquals("{}", entry.getBody());
    }

    @Test
    public void testResponseBodyEmptyWhenNoSchema() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{" +
                "\"description\":\"OK\"" +
                "}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        assertEquals("", entry.getBody());
    }

    @Test
    public void testResponseBodyWithNestedObject() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{" +
                "\"description\":\"OK\"," +
                "\"content\":{\"application/json\":{\"schema\":{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"user\":{\"type\":\"object\",\"properties\":{" +
                "\"id\":{\"type\":\"integer\"}," +
                "\"name\":{\"type\":\"string\"}" +
                "}}" +
                "}" +
                "}}" +
                "}}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        ResponseEntry entry = result.getRules().get(0).getResponses().get(0);
        JsonNode body = mapper.readTree(entry.getBody());
        assertTrue(body.has("user"));
        assertEquals(0, body.get("user").get("id").asInt());
        assertEquals("string", body.get("user").get("name").asText());
    }

    // ===== Environments =====

    @Test
    public void testEnvironmentsAssociatedToRules() throws Exception {
        String spec = buildSimpleSpec();
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Arrays.asList("ft-1", "ft-2"));

        Rule rule = result.getRules().get(0);
        assertEquals(Arrays.asList("ft-1", "ft-2"), rule.getEnvironments());
    }

    @Test
    public void testEmptyEnvironmentsByDefault() throws Exception {
        String spec = buildSimpleSpec();
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        Rule rule = result.getRules().get(0);
        assertTrue(rule.getEnvironments().isEmpty());
    }

    // ===== Tags =====

    @Test
    public void testTagsFromOpenApiSpec() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{" +
                "\"tags\":[\"user\",\"admin\"]," +
                "\"responses\":{\"200\":{\"description\":\"OK\"}}" +
                "}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        Rule rule = result.getRules().get(0);
        assertEquals(Arrays.asList("user", "admin"), rule.getTags());
    }

    // ===== Skipped paths =====

    @Test
    public void testPathWithoutResponsesSkipped() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"operationId\":\"listUsers\"}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        assertEquals(0, result.getGeneratedCount());
        assertEquals(1, result.getSkippedCount());
        assertTrue(result.getWarnings().size() > 0);
    }

    @Test
    public void testMixedSkippedAndGenerated() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{\"description\":\"OK\"}}}}," +
                "\"/api/orders\":{\"get\":{\"operationId\":\"noResponses\"}}" +
                "}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        assertEquals(1, result.getGeneratedCount());
        assertEquals(1, result.getSkippedCount());
    }

    // ===== Rule ID generation =====

    @Test
    public void testRuleIdWithCustomPrefix() throws Exception {
        String spec = buildSimpleSpec();
        OpenApiImportResult result = importer.importSpec(spec, "custom-",
                Collections.<String>emptyList());

        Rule rule = result.getRules().get(0);
        assertEquals("custom-get-api-users", rule.getId());
    }

    @Test
    public void testRuleIdWithPathParams() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users/{id}/orders/{orderId}\":{\"get\":{\"responses\":{\"200\":{\"description\":\"OK\"}}}}}}";
        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Collections.<String>emptyList());

        Rule rule = result.getRules().get(0);
        assertEquals("openapi-get-api-users-id-orders-orderId", rule.getId());
    }

    // ===== Full spec integration test =====

    @Test
    public void testFullPetstoreLikeSpec() throws Exception {
        String spec = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Petstore\",\"version\":\"1.0\"}," +
                "\"paths\":{" +
                "\"/pets\":{\"get\":{" +
                "\"operationId\":\"listPets\"," +
                "\"tags\":[\"pets\"]," +
                "\"responses\":{\"200\":{" +
                "\"description\":\"List of pets\"," +
                "\"content\":{\"application/json\":{\"schema\":{" +
                "\"type\":\"array\"," +
                "\"items\":{\"type\":\"object\",\"properties\":{" +
                "\"id\":{\"type\":\"integer\"}," +
                "\"name\":{\"type\":\"string\"}" +
                "}}" +
                "}}" +
                "}}" +
                "}}," +
                "\"post\":{" +
                "\"operationId\":\"createPet\"," +
                "\"responses\":{\"201\":{\"description\":\"Created\"}}" +
                "}}," +
                "\"/pets/{petId}\":{\"get\":{" +
                "\"operationId\":\"showPetById\"," +
                "\"responses\":{\"200\":{" +
                "\"description\":\"A pet\"," +
                "\"content\":{\"application/json\":{\"schema\":{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"id\":{\"type\":\"integer\"}," +
                "\"name\":{\"type\":\"string\"}," +
                "\"tag\":{\"type\":\"string\"}" +
                "}" +
                "}}" +
                "}}" +
                "}}}" +
                "}}";

        OpenApiImportResult result = importer.importSpec(spec, "openapi-",
                Arrays.asList("test-env"));

        assertEquals(3, result.getGeneratedCount());
        assertEquals(0, result.getSkippedCount());

        // Verify listPets
        Rule listPets = result.getRules().get(0);
        assertEquals("listPets", listPets.getName());
        assertEquals(Arrays.asList("pets"), listPets.getTags());
        assertEquals(Arrays.asList("test-env"), listPets.getEnvironments());

        // Verify showPetById has regex path
        Rule showPet = result.getRules().get(2);
        MatchCondition pathCond = showPet.getConditions().get(1);
        assertEquals("regex", pathCond.getOperator());
        assertEquals("/pets/[^/]+", pathCond.getValue());

        // Verify response body for showPetById
        ResponseEntry entry = showPet.getResponses().get(0);
        JsonNode body = mapper.readTree(entry.getBody());
        assertEquals(0, body.get("id").asInt());
        assertEquals("string", body.get("name").asText());
        assertEquals("string", body.get("tag").asText());
    }

    // ===== Helper =====

    private String buildSimpleSpec() {
        return "{\"openapi\":\"3.0.0\",\"paths\":{\"" +
                "/api/users\":{\"get\":{\"responses\":{\"200\":{\"description\":\"OK\"}}}}}}";
    }
}
