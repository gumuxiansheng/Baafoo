package com.baafoo.agent.channel;

import com.baafoo.core.model.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Collections;

public class ControlChannelTest {

    @Test
    public void testAgentRegisterRequestDefaults() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        assertNull(req.agentId);
        assertNull(req.environment);
        assertNull(req.hostname);
        assertNull(req.version);
        assertNull(req.protocols);
    }

    @Test
    public void testAgentRegisterRequestSetFields() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.agentId = "agent-1";
        req.environment = "prod";
        req.hostname = "host-1";
        req.version = "1.0.0";
        req.protocols = Collections.singletonList("http");
        assertEquals("agent-1", req.agentId);
        assertEquals("prod", req.environment);
        assertEquals("host-1", req.hostname);
        assertEquals("1.0.0", req.version);
        assertEquals(1, req.protocols.size());
        assertEquals("http", req.protocols.get(0));
    }

    @Test
    public void testAgentRegisterResponseDefaults() {
        ControlChannel.AgentRegisterResponse res = new ControlChannel.AgentRegisterResponse();
        assertNull(res.agentId);
        assertNull(res.mode);
        assertEquals(0, res.pollIntervalSec);
    }

    @Test
    public void testAgentRegisterResponseSetFields() {
        ControlChannel.AgentRegisterResponse res = new ControlChannel.AgentRegisterResponse();
        res.agentId = "agent-1";
        res.mode = "stub";
        res.pollIntervalSec = 30;
        assertEquals("agent-1", res.agentId);
        assertEquals("stub", res.mode);
        assertEquals(30, res.pollIntervalSec);
    }

    @Test
    public void testHeartbeatRequestDefaults() {
        ControlChannel.HeartbeatRequest req = new ControlChannel.HeartbeatRequest();
        assertNull(req.agentId);
        assertEquals(0L, req.timestamp);
    }

    @Test
    public void testHeartbeatRequestSetFields() {
        ControlChannel.HeartbeatRequest req = new ControlChannel.HeartbeatRequest();
        req.agentId = "agent-1";
        req.timestamp = 123456789L;
        assertEquals("agent-1", req.agentId);
        assertEquals(123456789L, req.timestamp);
    }

    @Test
    public void testPollResponseDefaults() {
        ControlChannel.PollResponse res = new ControlChannel.PollResponse();
        assertNull(res.rules);
        assertNull(res.mode);
        assertEquals(0L, res.version);
    }

    @Test
    public void testPollResponseSetFields() {
        ControlChannel.PollResponse res = new ControlChannel.PollResponse();
        res.rules = Collections.singletonList(new Rule());
        res.mode = "passthrough";
        res.version = 42L;
        assertNotNull(res.rules);
        assertEquals(1, res.rules.size());
        assertEquals("passthrough", res.mode);
        assertEquals(42L, res.version);
    }

    // --- AgentRegisterRequest.validate() (P2-3) ---

    @Test
    public void validateReturnsNullWhenAllRequiredFieldsPresent() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.agentId = "agent-1";
        req.environment = "prod";
        req.hostname = "host-1";
        req.version = "1.0.0";
        req.protocols = Collections.singletonList("http");
        assertNull("Valid request should return null", req.validate());
    }

    @Test
    public void validateRejectsNullAgentId() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.environment = "prod";
        req.hostname = "host-1";
        String err = req.validate();
        assertNotNull(err);
        assertTrue(err.contains("agentId"));
    }

    @Test
    public void validateRejectsEmptyAgentId() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.agentId = "   ";
        req.environment = "prod";
        req.hostname = "host-1";
        String err = req.validate();
        assertNotNull(err);
        assertTrue(err.contains("agentId"));
    }

    @Test
    public void validateRejectsNullEnvironment() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.agentId = "agent-1";
        req.hostname = "host-1";
        String err = req.validate();
        assertNotNull(err);
        assertTrue(err.contains("environment"));
    }

    @Test
    public void validateRejectsEmptyEnvironment() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.agentId = "agent-1";
        req.environment = "";
        req.hostname = "host-1";
        String err = req.validate();
        assertNotNull(err);
        assertTrue(err.contains("environment"));
    }

    @Test
    public void validateRejectsNullHostname() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.agentId = "agent-1";
        req.environment = "prod";
        String err = req.validate();
        assertNotNull(err);
        assertTrue(err.contains("hostname"));
    }

    @Test
    public void validateAllowsNullProtocols() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.agentId = "agent-1";
        req.environment = "prod";
        req.hostname = "host-1";
        req.protocols = null;
        assertNull("Null protocols should be allowed", req.validate());
    }

    @Test
    public void validateAllowsEmptyProtocolsList() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.agentId = "agent-1";
        req.environment = "prod";
        req.hostname = "host-1";
        req.protocols = Collections.emptyList();
        assertNull("Empty protocols list should be allowed", req.validate());
    }

    @Test
    public void validateRejectsNullEntryInProtocols() {
        ControlChannel.AgentRegisterRequest req = new ControlChannel.AgentRegisterRequest();
        req.agentId = "agent-1";
        req.environment = "prod";
        req.hostname = "host-1";
        java.util.List<String> protocols = new java.util.ArrayList<>();
        protocols.add("http");
        protocols.add(null);
        protocols.add("tcp");
        req.protocols = protocols;
        String err = req.validate();
        assertNotNull(err);
        assertTrue("Error should mention protocols[1]", err.contains("protocols[1]"));
    }
}
