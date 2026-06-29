package com.baafoo.server.handler;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.storage.StorageService;
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

public class HttpStubHandlerTest {

    @org.junit.Rule
    public Timeout globalTimeout = new Timeout(30, TimeUnit.SECONDS);

    private StorageService storage;
    private ServerConfig config;

    @Before
    public void setUp() {
        storage = mock(StorageService.class);
        config = new ServerConfig();
    }

    private FullHttpRequest buildRequest(String method, String uri, String host) {
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri, Unpooled.buffer(0));
        // Set Host header using the same approach as extractHeaders expects
        req.headers().add(HttpHeaderNames.HOST, host);
        return req;
    }

    @Test
    public void testNoMatchReturns404() throws Exception {
        config.setUnmatchedDefault("404");

        when(storage.listRules()).thenReturn(new ArrayList<Rule>());
        when(storage.listEnvironments()).thenReturn(new ArrayList<Environment>());
        when(storage.listAgents()).thenReturn(new ArrayList<StorageService.AgentRegistration>());

        HttpStubHandler handler = new HttpStubHandler(storage, config);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        FullHttpRequest request = buildRequest("GET", "/api/test", "example.com");
        channel.pipeline().fireChannelRead(request);
        channel.runPendingTasks();
        channel.checkException();

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(404, response.status().code());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("No matching rule found"));
    }

    @Test
    public void testMatchedRuleReturnsStub() throws Exception {
        Rule rule = new Rule();
        rule.setId("r1");
        rule.setName("test-rule");
        rule.setProtocol("http");
        rule.setEnabled(true);
        rule.setHost("example.com");
        rule.setEnvironments(Arrays.asList("test-env"));

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("{\"ok\":true}");
        resp.setStatusCode(200);
        resp.setName("success");
        rule.setResponses(Arrays.asList(resp));

        Environment env = new Environment();
        env.setName("test-env");
        env.setMode(EnvironmentMode.STUB);

        StorageService.AgentRegistration agentReg = new StorageService.AgentRegistration();
        agentReg.agentId = "test-agent";
        agentReg.environment = "test-env";
        agentReg.agentIp = "127.0.0.1";
        agentReg.lastHeartbeat = System.currentTimeMillis();

        when(storage.listRules()).thenReturn(Arrays.asList(rule));
        when(storage.listEnvironments()).thenReturn(Arrays.asList(env));
        when(storage.listAgents()).thenReturn(Arrays.asList(agentReg));

        config.setUnmatchedDefault("404");
        HttpStubHandler handler = new HttpStubHandler(storage, config);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        FullHttpRequest request = buildRequest("GET", "/api/test", "example.com");

        channel.writeInbound(request);
        channel.runPendingTasks();
        channel.checkException();

        FullHttpResponse response = channel.readOutbound();
        assertNotNull("Response should not be null", response);
        assertEquals(200, response.status().code());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertEquals("{\"ok\":true}", body);
    }

    @Test
    public void testExceptionCaughtClosesChannel() {
        HttpStubHandler handler = new HttpStubHandler(storage, config);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.pipeline().fireExceptionCaught(new RuntimeException("test error"));
        assertFalse(channel.isOpen());
    }

    @Test
    public void testMatchedRuleRecordsInRecordAndStubMode() throws Exception {
        Rule rule = new Rule();
        rule.setId("r1");
        rule.setName("test-rule");
        rule.setProtocol("http");
        rule.setEnabled(true);
        rule.setHost("example.com");
        rule.setEnvironments(Arrays.asList("test-env"));

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("{\"ok\":true}");
        resp.setStatusCode(200);
        resp.setName("success");
        rule.setResponses(Arrays.asList(resp));

        Environment env = new Environment();
        env.setName("test-env");
        env.setMode(EnvironmentMode.RECORD_AND_STUB);

        StorageService.AgentRegistration agentReg = new StorageService.AgentRegistration();
        agentReg.agentId = "test-agent";
        agentReg.environment = "test-env";
        agentReg.agentIp = "127.0.0.1";
        agentReg.lastHeartbeat = System.currentTimeMillis();

        when(storage.listRules()).thenReturn(Arrays.asList(rule));
        when(storage.listEnvironments()).thenReturn(Arrays.asList(env));
        when(storage.listAgents()).thenReturn(Arrays.asList(agentReg));

        config.setUnmatchedDefault("404");
        HttpStubHandler handler = new HttpStubHandler(storage, config);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        FullHttpRequest request = buildRequest("GET", "/api/test", "example.com");

        channel.writeInbound(request);
        channel.runPendingTasks();
        channel.checkException();

        FullHttpResponse response = channel.readOutbound();
        assertNotNull("Response should not be null", response);
        assertEquals(200, response.status().code());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertEquals("{\"ok\":true}", body);

        verify(storage).addRecording(any(RecordingEntry.class));
    }

    @Test
    public void testMatchedRuleRecordsInRecordAllMode() throws Exception {
        Rule rule = new Rule();
        rule.setId("r1");
        rule.setName("test-rule");
        rule.setProtocol("http");
        rule.setEnabled(true);
        rule.setHost("example.com");
        rule.setEnvironments(Arrays.asList("test-env"));

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("{\"ok\":true}");
        resp.setStatusCode(200);
        resp.setName("success");
        rule.setResponses(Arrays.asList(resp));

        Environment env = new Environment();
        env.setName("test-env");
        env.setMode(EnvironmentMode.RECORD_ALL);

        StorageService.AgentRegistration agentReg = new StorageService.AgentRegistration();
        agentReg.agentId = "test-agent";
        agentReg.environment = "test-env";
        agentReg.agentIp = "127.0.0.1";
        agentReg.lastHeartbeat = System.currentTimeMillis();

        when(storage.listRules()).thenReturn(Arrays.asList(rule));
        when(storage.listEnvironments()).thenReturn(Arrays.asList(env));
        when(storage.listAgents()).thenReturn(Arrays.asList(agentReg));

        config.setUnmatchedDefault("404");
        HttpStubHandler handler = new HttpStubHandler(storage, config);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        FullHttpRequest request = buildRequest("GET", "/api/test", "example.com");

        channel.writeInbound(request);
        channel.runPendingTasks();
        channel.checkException();

        FullHttpResponse response = channel.readOutbound();
        assertNotNull("Response should not be null", response);
        assertEquals(200, response.status().code());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertEquals("{\"ok\":true}", body);

        verify(storage).addRecording(any(RecordingEntry.class));
    }

    @Test
    public void testStubModeDoesNotRecord() throws Exception {
        Rule rule = new Rule();
        rule.setId("r1");
        rule.setName("test-rule");
        rule.setProtocol("http");
        rule.setEnabled(true);
        rule.setHost("example.com");
        rule.setEnvironments(Arrays.asList("test-env"));

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("{\"ok\":true}");
        resp.setStatusCode(200);
        resp.setName("success");
        rule.setResponses(Arrays.asList(resp));

        Environment env = new Environment();
        env.setName("test-env");
        env.setMode(EnvironmentMode.STUB);

        StorageService.AgentRegistration agentReg = new StorageService.AgentRegistration();
        agentReg.agentId = "test-agent";
        agentReg.environment = "test-env";
        agentReg.agentIp = "127.0.0.1";
        agentReg.lastHeartbeat = System.currentTimeMillis();

        when(storage.listRules()).thenReturn(Arrays.asList(rule));
        when(storage.listEnvironments()).thenReturn(Arrays.asList(env));
        when(storage.listAgents()).thenReturn(Arrays.asList(agentReg));

        config.setUnmatchedDefault("404");
        HttpStubHandler handler = new HttpStubHandler(storage, config);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        FullHttpRequest request = buildRequest("GET", "/api/test", "example.com");

        channel.writeInbound(request);
        channel.runPendingTasks();
        channel.checkException();

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(200, response.status().code());

        verify(storage, never()).addRecording(any(RecordingEntry.class));
    }
}
