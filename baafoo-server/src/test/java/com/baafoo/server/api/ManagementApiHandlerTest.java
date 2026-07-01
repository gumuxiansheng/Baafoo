package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.*;
import com.baafoo.server.auth.AuthService;
import com.baafoo.server.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ManagementApiHandlerTest {

    @org.junit.Rule
    public Timeout globalTimeout = new Timeout(30, TimeUnit.SECONDS);

    private StorageService storage;
    private AuthService authService;
    private EmbeddedChannel channel;
    private ObjectMapper mapper;

    @Before
    public void setUp() {
        storage = mock(StorageService.class);
        authService = new AuthService(storage, null, false, false, null);
        mapper = new ObjectMapper();
        ManagementApiHandler handler = new ManagementApiHandler(storage, authService);
        channel = new EmbeddedChannel(handler);
    }

    private FullHttpRequest createRequest(String method, String uri, String body) {
        ByteBuf content = body != null ? Unpooled.copiedBuffer(body, StandardCharsets.UTF_8) : Unpooled.buffer(0);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri, content);
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        return request;
    }

    private JsonNode assertOkResponse(FullHttpResponse response) throws Exception {
        assertNotNull(response);
        assertEquals(200, response.status().code());
        String respBody = response.content().toString(StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(respBody);
        assertTrue("Response should be successful", json.get("success").asBoolean());
        return json;
    }

    @Test
    public void testNonApiPathPassesThrough() {
        FullHttpRequest request = createRequest("GET", "/some-path", null);
        channel.writeInbound(request);
        Object out = channel.readOutbound();
        assertNull(out);
    }

    @Test
    public void testOptionsReturnsOk() {
        when(storage.listRules()).thenReturn(new ArrayList<Rule>());

        FullHttpRequest request = createRequest("OPTIONS", "/__baafoo__/api/rules", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(200, response.status().code());
    }

    @Test
    public void testListRules() throws Exception {
        when(storage.listRules()).thenReturn(new ArrayList<Rule>());

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/rules", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("data should be an array", json.get("data").isArray());
    }

    @Test
    public void testCreateRule() throws Exception {
        Rule rule = new Rule();
        rule.setName("new-rule");
        when(storage.createRule(any(Rule.class))).thenReturn(rule);

        String body = mapper.writeValueAsString(rule);
        FullHttpRequest request = createRequest("POST", "/__baafoo__/api/rules", body);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertEquals("new-rule", json.get("data").get("name").asText());
    }

    @Test
    public void testGetRule() throws Exception {
        Rule rule = new Rule();
        rule.setId("r1");
        rule.setName("found");
        when(storage.getRule("r1")).thenReturn(rule);

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/rules/r1", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertEquals("r1", json.get("data").get("id").asText());
        assertEquals("found", json.get("data").get("name").asText());
    }

    @Test
    public void testGetRuleNotFound() throws Exception {
        when(storage.getRule("nonexistent")).thenReturn(null);

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/rules/nonexistent", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(200, response.status().code());
        String respBody = response.content().toString(StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(respBody);
        assertFalse("Should indicate failure", json.get("success").asBoolean());
        assertEquals("Rule not found", json.get("message").asText());
    }

    @Test
    public void testUpdateRule() throws Exception {
        Rule existing = new Rule();
        existing.setId("r1");
        existing.setName("existing");
        when(storage.getRule("r1")).thenReturn(existing);

        Rule updated = new Rule();
        updated.setId("r1");
        updated.setName("updated");
        when(storage.updateRule(eq("r1"), any(Rule.class))).thenReturn(updated);

        String body = mapper.writeValueAsString(updated);
        FullHttpRequest request = createRequest("PUT", "/__baafoo__/api/rules/r1", body);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertEquals("updated", json.get("data").get("name").asText());
    }

    @Test
    public void testDeleteRule() throws Exception {
        when(storage.deleteRule("r1")).thenReturn(true);

        FullHttpRequest request = createRequest("DELETE", "/__baafoo__/api/rules/r1", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertEquals("Deleted", json.get("message").asText());
    }

    @Test
    public void testUndoRule() throws Exception {
        when(storage.undoRule("r1")).thenReturn(true);

        FullHttpRequest request = createRequest("POST", "/__baafoo__/api/rules/r1/undo", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("Should contain 'Undo'", json.get("message").asText().contains("Undo"));
    }

    @Test
    public void testListEnvironments() throws Exception {
        when(storage.listEnvironments()).thenReturn(new ArrayList<Environment>());

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/environments", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("data should be an array", json.get("data").isArray());
    }

    @Test
    public void testCreateEnvironment() throws Exception {
        Environment env = new Environment();
        env.setName("test-env");
        when(storage.createEnvironment(any(Environment.class))).thenReturn(env);

        String body = mapper.writeValueAsString(env);
        FullHttpRequest request = createRequest("POST", "/__baafoo__/api/environments", body);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertEquals("test-env", json.get("data").get("name").asText());
    }

    @Test
    public void testGetEnvironment() throws Exception {
        Environment env = new Environment();
        env.setId("e1");
        when(storage.getEnvironment("e1")).thenReturn(env);

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/environments/e1", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertEquals("e1", json.get("data").get("id").asText());
    }

    @Test
    public void testAgentRegister() throws Exception {
        StorageService.AgentRegistration reg = new StorageService.AgentRegistration();
        reg.agentId = "agent-1";
        when(storage.registerAgent(anyString(), anyString(), anyString(), anyString(), anyList(), anyString()))
                .thenReturn(reg);
        when(storage.getEnvironmentByName(anyString())).thenReturn(null);

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("agentId", "agent-1");
        body.put("environment", "dev");
        String json = mapper.writeValueAsString(body);

        FullHttpRequest request = createRequest("POST", "/__baafoo__/api/agent/register", json);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode respJson = assertOkResponse(response);
        assertTrue("data should be present", respJson.has("data"));
    }

    @Test
    public void testAgentHeartbeat() throws Exception {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("agentId", "agent-1");
        String json = mapper.writeValueAsString(body);

        FullHttpRequest request = createRequest("POST", "/__baafoo__/api/agent/heartbeat", json);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertOkResponse(response);
    }

    @Test
    public void testAgentPoll() throws Exception {
        when(storage.listRules()).thenReturn(new ArrayList<Rule>());
        when(storage.listAgents()).thenReturn(new ArrayList<StorageService.AgentRegistration>());

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/agent/poll?agentId=agent-1", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("data should be present", json.has("data"));
    }

    @Test
    public void testAgentRecordings() throws Exception {
        List<RecordingEntry> recordings = new ArrayList<RecordingEntry>();
        recordings.add(new RecordingEntry());
        String json = mapper.writeValueAsString(recordings);

        FullHttpRequest request = createRequest("POST", "/__baafoo__/api/agent/recordings", json);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertOkResponse(response);
    }

    @Test
    public void testListRecordings() throws Exception {
        when(storage.listRecordings(anyString(), anyInt())).thenReturn(new ArrayList<RecordingEntry>());

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/recordings", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("data should be an array", json.get("data").isArray());
    }

    @Test
    public void testListScenes() throws Exception {
        when(storage.listScenes()).thenReturn(new ArrayList<SceneSet>());

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/scenes", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("data should be an array", json.get("data").isArray());
    }

    @Test
    public void testCreateScene() throws Exception {
        SceneSet scene = new SceneSet();
        scene.setName("test-scene");
        when(storage.createScene(any(SceneSet.class))).thenReturn(scene);

        String body = mapper.writeValueAsString(scene);
        FullHttpRequest request = createRequest("POST", "/__baafoo__/api/scenes", body);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertEquals("test-scene", json.get("data").get("name").asText());
    }

    @Test
    public void testGetScene() throws Exception {
        SceneSet scene = new SceneSet();
        scene.setId("s1");
        // P1-3: SceneApiHandler now prefers SceneService.getScene(id) over
        // listScenes().stream().filter(...). Mock both so the test works
        // whether the adapter uses getScene (Jdbc) or the listScenes fallback.
        when(storage.listScenes()).thenReturn(Arrays.asList(scene));
        when(storage.getScene("s1")).thenReturn(scene);

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/scenes/s1", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertEquals("s1", json.get("data").get("id").asText());
    }

    @Test
    public void testDeleteScene() throws Exception {
        when(storage.deleteScene("s1")).thenReturn(true);

        FullHttpRequest request = createRequest("DELETE", "/__baafoo__/api/scenes/s1", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertOkResponse(response);
    }

    @Test
    public void testSystemStatus() throws Exception {
        when(storage.listAgents()).thenReturn(new ArrayList<StorageService.AgentRegistration>());
        when(storage.listRules()).thenReturn(new ArrayList<Rule>());
        when(storage.listEnvironments()).thenReturn(new ArrayList<Environment>());
        when(storage.listScenes()).thenReturn(new ArrayList<SceneSet>());

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/status", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("data should have rules field", json.get("data").has("rules"));
        assertTrue("data should have agents field", json.get("data").has("agents"));
    }

    @Test
    public void testUnknownEndpoint() throws Exception {
        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/unknown", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertEquals(404, response.status().code());
        String respBody = response.content().toString(StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(respBody);
        assertFalse(json.get("success").asBoolean());
        assertEquals(404, json.get("code").asInt());
    }

    @Test
    public void testListRuleSets() throws Exception {
        when(storage.listRuleSets()).thenReturn(new ArrayList<RuleSet>());

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/rulesets", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("data should be an array", json.get("data").isArray());
    }

    @Test
    public void testListAgents() throws Exception {
        when(storage.listAgents()).thenReturn(new ArrayList<StorageService.AgentRegistration>());

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/agents", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("data should be an array", json.get("data").isArray());
    }

    @Test
    public void testInheritedEnvironments() throws Exception {
        Rule rule = new Rule();
        rule.setId("r1");
        when(storage.getRule("r1")).thenReturn(rule);
        when(storage.listScenes()).thenReturn(new ArrayList<SceneSet>());

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/rules/r1/inherited-environments", null);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode json = assertOkResponse(response);
        assertTrue("data should be an array", json.get("data").isArray());
    }

    @Test
    public void testExceptionCaughtClosesChannel() {
        channel.pipeline().fireExceptionCaught(new RuntimeException("test error"));
        assertFalse(channel.isOpen());
    }
}
