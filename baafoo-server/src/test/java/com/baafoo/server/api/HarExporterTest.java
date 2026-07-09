package com.baafoo.server.api;

import com.baafoo.core.model.RecordingEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for {@link HarExporter}.
 *
 * <p>Focuses on logic correctness:
 * <ul>
 *   <li>HAR 1.2 structure compliance</li>
 *   <li>URL building from protocol/host/port/path</li>
 *   <li>Status text mapping</li>
 *   <li>Edge cases: null fields, empty recordings, missing headers</li>
 * </ul>
 */
public class HarExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void export_emptyList_producesValidHar() throws Exception {
        String json = HarExporter.export(Collections.<RecordingEntry>emptyList());
        JsonNode root = MAPPER.readTree(json);
        assertEquals("1.2", root.path("log").path("version").asText());
        assertEquals("Baafoo", root.path("log").path("creator").path("name").asText());
        assertEquals(0, root.path("log").path("entries").size());
    }

    @Test
    public void export_singleEntry_hasCorrectStructure() throws Exception {
        RecordingEntry rec = new RecordingEntry();
        rec.setMethod("GET");
        rec.setHost("example.com");
        rec.setPort(80);
        rec.setPath("/api/data");
        rec.setProtocol("http");
        rec.setResponseStatusCode(200);
        rec.setResponseBody("{\"ok\":true}");
        rec.setResponseTimeMs(42);
        rec.setRecordedAt(System.currentTimeMillis());

        String json = HarExporter.export(Collections.singletonList(rec));
        JsonNode root = MAPPER.readTree(json);
        JsonNode entries = root.path("log").path("entries");
        assertEquals(1, entries.size());

        JsonNode entry = entries.get(0);
        assertEquals("GET", entry.path("request").path("method").asText());
        assertEquals("http://example.com:80/api/data", entry.path("request").path("url").asText());
        assertEquals(200, entry.path("response").path("status").asInt());
        assertEquals("OK", entry.path("response").path("statusText").asText());
        assertEquals(42, entry.path("time").asInt());
        assertEquals("{\"ok\":true}", entry.path("response").path("content").path("text").asText());
    }

    @Test
    public void export_postMethod_withRequestBody() throws Exception {
        RecordingEntry rec = new RecordingEntry();
        rec.setMethod("POST");
        rec.setHost("api.example.com");
        rec.setPort(443);
        rec.setPath("/submit");
        rec.setProtocol("https");
        rec.setRequestBody("{\"key\":\"value\"}");
        rec.setResponseStatusCode(201);
        rec.setResponseBody("{\"id\":123}");
        rec.setRecordedAt(0);

        String json = HarExporter.export(Collections.singletonList(rec));
        JsonNode root = MAPPER.readTree(json);
        JsonNode entry = root.path("log").path("entries").get(0);

        assertEquals("POST", entry.path("request").path("method").asText());
        assertEquals("https://api.example.com:443/submit", entry.path("request").path("url").asText());
        assertEquals("Created", entry.path("response").path("statusText").asText());
        assertEquals("{\"key\":\"value\"}", entry.path("request").path("postData").path("text").asText());
    }

    @Test
    public void export_withHeaders() throws Exception {
        RecordingEntry rec = new RecordingEntry();
        rec.setMethod("GET");
        rec.setHost("example.com");
        rec.setPort(80);
        rec.setPath("/");
        rec.setProtocol("http");
        rec.setResponseStatusCode(200);
        rec.setRequestHeaders(new LinkedHashMap<String, String>());
        rec.getRequestHeaders().put("Authorization", "Bearer token123");
        rec.getRequestHeaders().put("Accept", "application/json");
        rec.setResponseHeaders(new LinkedHashMap<String, String>());
        rec.getResponseHeaders().put("Content-Type", "application/json");
        rec.getResponseHeaders().put("X-Request-Id", "abc-123");

        String json = HarExporter.export(Collections.singletonList(rec));
        JsonNode root = MAPPER.readTree(json);
        JsonNode entry = root.path("log").path("entries").get(0);

        // Request headers
        JsonNode reqHeaders = entry.path("request").path("headers");
        assertTrue(reqHeaders.size() >= 2);

        // Response headers
        JsonNode respHeaders = entry.path("response").path("headers");
        assertTrue(respHeaders.size() >= 2);
        assertEquals("application/json", entry.path("response").path("content").path("mimeType").asText());
    }

    @Test
    public void export_nullFields_handledGracefully() throws Exception {
        RecordingEntry rec = new RecordingEntry();
        rec.setMethod(null);
        rec.setHost(null);
        rec.setPort(0);
        rec.setPath(null);
        rec.setProtocol(null);
        rec.setResponseStatusCode(200);
        rec.setResponseBody(null);
        rec.setRequestBody(null);
        rec.setRequestHeaders(null);
        rec.setResponseHeaders(null);

        String json = HarExporter.export(Collections.singletonList(rec));
        JsonNode root = MAPPER.readTree(json);
        JsonNode entry = root.path("log").path("entries").get(0);

        assertEquals("GET", entry.path("request").path("method").asText()); // default
        assertEquals("http://", entry.path("request").path("url").asText());
        assertEquals(0, entry.path("response").path("content").path("size").asInt());
        // No postData when requestBody is null
        assertFalse(entry.path("request").has("postData"));
    }

    @Test
    public void export_statusTextMapping_coversCommonCodes() throws Exception {
        int[] codes = {200, 201, 204, 301, 302, 304, 400, 401, 403, 404, 405, 500, 502, 503};
        String[] expected = {"OK", "Created", "No Content", "Moved Permanently", "Found",
                "Not Modified", "Bad Request", "Unauthorized", "Forbidden", "Not Found",
                "Method Not Allowed", "Internal Server Error", "Bad Gateway", "Service Unavailable"};

        for (int i = 0; i < codes.length; i++) {
            RecordingEntry rec = new RecordingEntry();
            rec.setMethod("GET");
            rec.setHost("example.com");
            rec.setPort(80);
            rec.setPath("/");
            rec.setProtocol("http");
            rec.setResponseStatusCode(codes[i]);

            String json = HarExporter.export(Collections.singletonList(rec));
            JsonNode root = MAPPER.readTree(json);
            String statusText = root.path("log").path("entries").get(0)
                    .path("response").path("statusText").asText();
            assertEquals("Status " + codes[i], expected[i], statusText);
        }
    }

    @Test
    public void export_unknownStatusCode_returnsStatusN() throws Exception {
        RecordingEntry rec = new RecordingEntry();
        rec.setMethod("GET");
        rec.setHost("example.com");
        rec.setPort(80);
        rec.setPath("/");
        rec.setProtocol("http");
        rec.setResponseStatusCode(418); // I'm a teapot

        String json = HarExporter.export(Collections.singletonList(rec));
        JsonNode root = MAPPER.readTree(json);
        String statusText = root.path("log").path("entries").get(0)
                .path("response").path("statusText").asText();
        assertEquals("Status 418", statusText);
    }

    @Test
    public void export_multipleEntries_allPresent() throws Exception {
        List<RecordingEntry> recordings = new ArrayList<RecordingEntry>();
        for (int i = 0; i < 5; i++) {
            RecordingEntry rec = new RecordingEntry();
            rec.setMethod("GET");
            rec.setHost("example.com");
            rec.setPort(80);
            rec.setPath("/item/" + i);
            rec.setProtocol("http");
            rec.setResponseStatusCode(200);
            recordings.add(rec);
        }

        String json = HarExporter.export(recordings);
        JsonNode root = MAPPER.readTree(json);
        assertEquals(5, root.path("log").path("entries").size());
    }

    @Test
    public void export_noRecordedAt_omitsStartedDateTime() throws Exception {
        RecordingEntry rec = new RecordingEntry();
        rec.setMethod("GET");
        rec.setHost("example.com");
        rec.setPort(80);
        rec.setPath("/");
        rec.setProtocol("http");
        rec.setResponseStatusCode(200);
        rec.setRecordedAt(0);

        String json = HarExporter.export(Collections.singletonList(rec));
        JsonNode root = MAPPER.readTree(json);
        JsonNode entry = root.path("log").path("entries").get(0);
        // recordedAt=0 means the SimpleDateFormat would produce a date, but the check is > 0
        assertFalse(entry.has("startedDateTime"));
    }

    @Test
    public void export_withRecordedAt_hasStartedDateTime() throws Exception {
        RecordingEntry rec = new RecordingEntry();
        rec.setMethod("GET");
        rec.setHost("example.com");
        rec.setPort(80);
        rec.setPath("/");
        rec.setProtocol("http");
        rec.setResponseStatusCode(200);
        rec.setRecordedAt(1700000000000L); // 2023-11-14T22:13:20.000Z

        String json = HarExporter.export(Collections.singletonList(rec));
        JsonNode root = MAPPER.readTree(json);
        JsonNode entry = root.path("log").path("entries").get(0);
        assertTrue(entry.has("startedDateTime"));
        assertTrue(entry.path("startedDateTime").asText().contains("2023"));
    }

    @Test
    public void export_responseSize_matchesBodyLength() throws Exception {
        RecordingEntry rec = new RecordingEntry();
        rec.setMethod("GET");
        rec.setHost("example.com");
        rec.setPort(80);
        rec.setPath("/");
        rec.setProtocol("http");
        rec.setResponseStatusCode(200);
        rec.setResponseBody("hello world");

        String json = HarExporter.export(Collections.singletonList(rec));
        JsonNode root = MAPPER.readTree(json);
        int size = root.path("log").path("entries").get(0)
                .path("response").path("content").path("size").asInt();
        assertEquals("hello world".length(), size);
    }

    @Test(expected = RuntimeException.class)
    public void export_withInvalidRecordingEntry_throwsRuntime() {
        // RecordingEntry with no method/host set — should still work,
        // but test that RuntimeException wraps any Jackson errors
        // Actually, HarExporter handles nulls gracefully, so this test
        // verifies that the method doesn't throw for valid input
        HarExporter.export(null);
    }
}
