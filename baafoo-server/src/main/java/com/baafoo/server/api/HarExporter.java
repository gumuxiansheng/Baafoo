package com.baafoo.server.api;

import com.baafoo.core.model.RecordingEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.*;

/**
 * Exports recording entries to HAR 1.2 format.
 *
 * @see <a href="http://www.softwareishard.com/blog/har-12-spec/">HAR 1.2 Spec</a>
 */
public class HarExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static {
        MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Convert a list of recording entries to HAR 1.2 JSON string.
     *
     * @param recordings the recording entries to export
     * @return HAR 1.2 formatted JSON string
     */
    public static String export(List<RecordingEntry> recordings) {
        try {
            Map<String, Object> har = new LinkedHashMap<String, Object>();

            Map<String, Object> log = new LinkedHashMap<String, Object>();
            log.put("version", "1.2");

            Map<String, Object> creator = new LinkedHashMap<String, Object>();
            creator.put("name", "Baafoo");
            creator.put("version", "1.0.0");
            log.put("creator", creator);

            List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
            for (RecordingEntry rec : recordings) {
                entries.add(toEntry(rec));
            }
            log.put("entries", entries);

            har.put("log", log);
            return MAPPER.writeValueAsString(har);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export HAR: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> toEntry(RecordingEntry rec) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();

        // Request
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("method", rec.getMethod() != null ? rec.getMethod() : "GET");

        String url = buildUrl(rec);
        request.put("url", url);

        // Request headers
        List<Map<String, String>> reqHeaders = new ArrayList<Map<String, String>>();
        if (rec.getRequestHeaders() != null) {
            for (Map.Entry<String, String> h : rec.getRequestHeaders().entrySet()) {
                Map<String, String> header = new LinkedHashMap<String, String>();
                header.put("name", h.getKey());
                header.put("value", h.getValue());
                reqHeaders.add(header);
            }
        }
        request.put("headers", reqHeaders);

        // Request body
        if (rec.getRequestBody() != null && !rec.getRequestBody().isEmpty()) {
            Map<String, Object> postData = new LinkedHashMap<String, Object>();
            postData.put("mimeType", "application/json");
            postData.put("text", rec.getRequestBody());
            request.put("postData", postData);
        }

        entry.put("request", request);

        // Response
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("status", rec.getResponseStatusCode());
        response.put("statusText", getStatusText(rec.getResponseStatusCode()));

        // Response headers
        List<Map<String, String>> respHeaders = new ArrayList<Map<String, String>>();
        if (rec.getResponseHeaders() != null) {
            for (Map.Entry<String, String> h : rec.getResponseHeaders().entrySet()) {
                Map<String, String> header = new LinkedHashMap<String, String>();
                header.put("name", h.getKey());
                header.put("value", h.getValue());
                respHeaders.add(header);
            }
        }
        response.put("headers", respHeaders);

        // Response body
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        String contentType = rec.getResponseHeaders() != null ? rec.getResponseHeaders().get("Content-Type") : null;
        content.put("mimeType", contentType != null ? contentType : "application/json");
        content.put("size", rec.getResponseBody() != null ? rec.getResponseBody().length() : 0);
        if (rec.getResponseBody() != null) {
            content.put("text", rec.getResponseBody());
        }
        response.put("content", content);

        entry.put("response", response);

        // Time
        entry.put("time", rec.getResponseTimeMs());

        // Started date-time
        if (rec.getRecordedAt() > 0) {
            entry.put("startedDateTime", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date(rec.getRecordedAt())));
        }

        return entry;
    }

    private static String buildUrl(RecordingEntry rec) {
        StringBuilder sb = new StringBuilder();
        String protocol = rec.getProtocol() != null ? rec.getProtocol().toLowerCase() : "http";
        sb.append(protocol).append("://");
        if (rec.getHost() != null) sb.append(rec.getHost());
        if (rec.getPort() > 0) sb.append(":").append(rec.getPort());
        if (rec.getPath() != null) sb.append(rec.getPath());
        return sb.toString();
    }

    private static String getStatusText(int statusCode) {
        switch (statusCode) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Status " + statusCode;
        }
    }
}
