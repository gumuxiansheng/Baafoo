package com.baafoo.server.handler;

import com.baafoo.core.model.*;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TcpStubHandlerTest {

    private StorageService storage;
    private EmbeddedChannel channel;

    @Before
    public void setUp() {
        storage = mock(StorageService.class);
        TcpStubHandler handler = new TcpStubHandler(storage);
        channel = new EmbeddedChannel(handler);
    }

    @Test
    public void testNoMatchClosesConnection() {
        when(storage.listRules()).thenReturn(new ArrayList<Rule>());

        channel.writeInbound(Unpooled.copiedBuffer("test payload", StandardCharsets.UTF_8));
        assertFalse(channel.isOpen());
    }

    @Test
    public void testMatchedRuleReturnsResponse() {
        Rule rule = new Rule();
        rule.setId("r1");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("stub-response");
        resp.setStatusCode(200);
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

        channel.writeInbound(Unpooled.copiedBuffer("test payload", StandardCharsets.UTF_8));
        Object out = channel.readOutbound();
        assertNotNull(out);
    }

    @Test
    public void testExceptionCaughtClosesChannel() {
        channel.pipeline().fireExceptionCaught(new RuntimeException("test error"));
        assertFalse(channel.isOpen());
    }
}
