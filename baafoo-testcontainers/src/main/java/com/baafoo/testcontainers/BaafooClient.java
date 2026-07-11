package com.baafoo.testcontainers;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.Rule;
import com.baafoo.core.model.SceneSet;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BaafooClient {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseUrl;
    private final String apiKey;

    public BaafooClient(String baseUrl) {
        this(baseUrl, null);
    }

    public BaafooClient(String baseUrl, String apiKey) {
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    public Rule createRule(Rule rule) {
        return doPost("/__baafoo__/api/rules", rule, Rule.class);
    }

    public List<Rule> listRules() {
        JsonNode node = doGet("/__baafoo__/api/rules", JsonNode.class);
        // doGet(JsonNode.class) returns the response "data" node, which for
        // GET /rules (legacy, non-paginated mode) is the rules array itself.
        if (node != null && node.isArray()) {
            return MAPPER.convertValue(node, new TypeReference<List<Rule>>() {});
        }
        return Collections.emptyList();
    }

    public Rule getRule(String id) {
        return doGet("/__baafoo__/api/rules/" + id, Rule.class);
    }

    public Rule updateRule(String id, Rule rule) {
        return doPut("/__baafoo__/api/rules/" + id, rule, Rule.class);
    }

    public void deleteRule(String id) {
        doDelete("/__baafoo__/api/rules/" + id);
    }

    // ------------------------------------------------------------------
    // Environments
    // ------------------------------------------------------------------

    public Environment createEnvironment(String name, String mode) {
        Environment env = new Environment();
        env.setName(name);
        env.setMode(EnvironmentMode.fromValue(mode));
        return doPost("/__baafoo__/api/environments", env, Environment.class);
    }

    public Environment createEnvironment(Environment env) {
        return doPost("/__baafoo__/api/environments", env, Environment.class);
    }

    public List<Environment> listEnvironments() {
        JsonNode node = doGet("/__baafoo__/api/environments", JsonNode.class);
        // doGet(JsonNode.class) returns the response "data" node, which for
        // GET /environments is the environments array itself.
        if (node != null && node.isArray()) {
            return MAPPER.convertValue(node, new TypeReference<List<Environment>>() {});
        }
        return Collections.emptyList();
    }

    public Environment getEnvironment(String name) {
        return doGet("/__baafoo__/api/environments/" + name, Environment.class);
    }

    public Environment updateEnvironment(String name, Environment env) {
        return doPut("/__baafoo__/api/environments/" + name, env, Environment.class);
    }

    public void setEnvironmentMode(String name, String mode) {
        Environment env = new Environment();
        env.setMode(EnvironmentMode.fromValue(mode));
        doPut("/__baafoo__/api/environments/" + name, env, Environment.class);
    }

    public void deleteEnvironment(String name) {
        doDelete("/__baafoo__/api/environments/" + name);
    }

    // ------------------------------------------------------------------
    // Scenes
    // ------------------------------------------------------------------

    public SceneSet createSceneSet(SceneSet sceneSet) {
        return doPost("/__baafoo__/api/scenes", sceneSet, SceneSet.class);
    }

    public List<SceneSet> listSceneSets() {
        JsonNode node = doGet("/__baafoo__/api/scenes", JsonNode.class);
        // doGet(JsonNode.class) returns the response "data" node, which for
        // GET /scenes is the scene-sets array itself.
        if (node != null && node.isArray()) {
            return MAPPER.convertValue(node, new TypeReference<List<SceneSet>>() {});
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    public JsonNode getStatus() {
        return doGet("/__baafoo__/api/status", JsonNode.class);
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private <T> T doGet(String path, Class<T> responseType) {
        try {
            URL url = new URL(baseUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");
            applyAuthHeader(conn);

            int code = conn.getResponseCode();
            String body = readBody(conn);

            if (code >= 200 && code < 300) {
                ApiResponse<?> apiResp = MAPPER.readValue(body, ApiResponse.class);
                JsonNode root = MAPPER.readTree(body);
                JsonNode dataNode = root.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    return null;
                }
                if (responseType == JsonNode.class) {
                    return responseType.cast(dataNode);
                }
                return MAPPER.treeToValue(dataNode, responseType);
            }
            throw new RuntimeException("HTTP " + code + " from GET " + path + ": " + body);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GET " + path + " failed", e);
        }
    }

    private <T> T doPost(String path, Object request, Class<T> responseType) {
        return doWrite("POST", path, request, responseType);
    }

    private <T> T doPut(String path, Object request, Class<T> responseType) {
        return doWrite("PUT", path, request, responseType);
    }

    private void applyAuthHeader(HttpURLConnection conn) {
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("X-Api-Key", apiKey);
        }
    }

    private void doDelete(String path) {
        try {
            URL url = new URL(baseUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            applyAuthHeader(conn);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = readBody(conn);
                throw new RuntimeException("HTTP " + code + " from DELETE " + path + ": " + body);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DELETE " + path + " failed", e);
        }
    }

    private <T> T doWrite(String method, String path, Object request, Class<T> responseType) {
        try {
            URL url = new URL(baseUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            applyAuthHeader(conn);
            conn.setDoOutput(true);

            byte[] requestBody = MAPPER.writeValueAsBytes(request);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody);
            }

            int code = conn.getResponseCode();
            String body = readBody(conn);

            if (code >= 200 && code < 300) {
                JsonNode root = MAPPER.readTree(body);
                JsonNode dataNode = root.get("data");
                if (dataNode == null || dataNode.isNull()) {
                    return null;
                }
                if (responseType == JsonNode.class) {
                    return responseType.cast(dataNode);
                }
                return MAPPER.treeToValue(dataNode, responseType);
            }
            throw new RuntimeException("HTTP " + code + " from " + method + " " + path + ": " + body);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(method + " " + path + " failed", e);
        }
    }

    private static String readBody(HttpURLConnection conn) {
        try {
            InputStream stream;
            int code = conn.getResponseCode();
            if (code >= 400) {
                stream = conn.getErrorStream();
            } else {
                stream = conn.getInputStream();
            }
            if (stream == null) {
                return "";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            stream.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
