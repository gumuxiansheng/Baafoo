package com.baafoo.testspring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class HttpCallerService {

    private static final Logger log = LoggerFactory.getLogger(HttpCallerService.class);
    private static final int TIMEOUT = 3000;

    public Map<String, Object> doGet(String targetUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        return parseResponse(conn);
    }

    public Map<String, Object> doPost(String targetUrl, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return parseResponse(conn);
    }

    public Map<String, Object> doPut(String targetUrl, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return parseResponse(conn);
    }

    public Map<String, Object> doDelete(String targetUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        return parseResponse(conn);
    }

    private Map<String, Object> parseResponse(HttpURLConnection conn) throws Exception {
        int statusCode = conn.getResponseCode();
        String stubHeader = conn.getHeaderField("X-Baafoo-Stub");
        String ruleId = conn.getHeaderField("X-Baafoo-Rule-Id");

        InputStream is = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder bodyBuilder = new StringBuilder();
        if (is != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    bodyBuilder.append(line);
                }
            }
        }
        String body = bodyBuilder.toString();
        if (body.length() > 500) {
            body = body.substring(0, 500) + "... (truncated)";
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("statusCode", statusCode);
        result.put("stubbed", "true".equals(stubHeader));
        result.put("ruleId", ruleId);
        result.put("body", body);
        log.info("HTTP call complete: status={}, stubbed={}", statusCode, result.get("stubbed"));
        return result;
    }
}
