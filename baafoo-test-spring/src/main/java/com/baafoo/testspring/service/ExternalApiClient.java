package com.baafoo.testspring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Service
public class ExternalApiClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiClient.class);

    public String fetchData() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://real-backend:9090/get");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            // M-13: Use explicit UTF-8 charset instead of platform default to avoid mojibake on non-UTF-8 JVMs
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                log.info("External API response code: {}", responseCode);
                return response.toString();
            }
        } catch (Exception e) {
            log.error("Failed to call external API", e);
            return "Error: " + e.getMessage();
        } finally {
            // M-13: Ensure the underlying socket is released back to the keep-alive pool
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
