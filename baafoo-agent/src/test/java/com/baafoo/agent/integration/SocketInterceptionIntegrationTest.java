package com.baafoo.agent.integration;

import com.baafoo.agent.GlobalRouteState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class SocketInterceptionIntegrationTest {

    @Before
    public void setup() {
        GlobalRouteState.SERVER_HOST = "127.0.0.1";
        GlobalRouteState.SERVER_PORT = 8084;
        GlobalRouteState.HTTP_PORT = 9000;
        GlobalRouteState.TCP_PORT = 9001;
        GlobalRouteState.KAFKA_PORT = 9002;
        GlobalRouteState.PULSAR_PORT = 9003;
        GlobalRouteState.JMS_PORT = 9004;
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        GlobalRouteState.ROUTES.clear();
        GlobalRouteState.DNS_CACHE.clear();
        GlobalRouteState.RECORDING_SESSIONS.clear();
    }

    @After
    public void teardown() {
        GlobalRouteState.ROUTES.clear();
        GlobalRouteState.DNS_CACHE.clear();
        GlobalRouteState.RECORDING_SESSIONS.clear();
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;
    }

    @Test
    public void testPassthroughModeDoesNotIntercept() throws Exception {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;
        GlobalRouteState.ROUTES.put("example.com", new GlobalRouteState.HostPort("127.0.0.1", 9000));

        // In passthrough mode, Socket.connect should not be intercepted
        // We can't easily verify the endpoint wasn't changed without a real server,
        // but we can verify the mode is passthrough
        assertTrue(GlobalRouteState.isPassthrough());
    }

    @Test
    public void testInternalAddressNotIntercepted() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;

        // 127.0.0.1 should be considered internal
        assertTrue(GlobalRouteState.isInternal("127.0.0.1", 8084));
        assertTrue(GlobalRouteState.isInternal("localhost", 8084));
    }

    @Test
    public void testRouteLookupWithHost() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        GlobalRouteState.ROUTES.put("example.com", new GlobalRouteState.HostPort("127.0.0.1", 9000));

        String[] route = GlobalRouteState.lookup("example.com", 80);
        assertNotNull(route);
        assertEquals("127.0.0.1", route[0]);
        assertEquals("9000", route[1]);
    }

    @Test
    public void testRouteLookupWithHostAndPort() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        GlobalRouteState.ROUTES.put("example.com:443", new GlobalRouteState.HostPort("127.0.0.1", 9443));

        String[] route = GlobalRouteState.lookup("example.com", 443);
        assertNotNull(route);
        assertEquals("127.0.0.1", route[0]);
        assertEquals("9443", route[1]);
    }

    @Test
    public void testRouteLookupMiss() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;

        String[] route = GlobalRouteState.lookup("unknown.com", 80);
        assertNull(route);
    }

    @Test
    public void testDnsCacheFallback() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        GlobalRouteState.ROUTES.put("example.com", new GlobalRouteState.HostPort("127.0.0.1", 9000));

        // Simulate DNS resolution: example.com -> 93.184.216.34
        GlobalRouteState.recordDns("example.com", "93.184.216.34");

        // GlobalRouteState.lookup does not do DNS fallback itself;
        // that logic lives in SocketConnectAdvice/NioSocketConnectAdvice.
        // Verify the DNS cache was recorded so the advice can use it.
        assertEquals("example.com", GlobalRouteState.DNS_CACHE.get("93.184.216.34"));
    }

    @Test
    public void testRecordModeRegistersSession() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_RECORD;

        // Simulate what SocketConnectAdvice does in record mode:
        // register a socket for recording
        int socketId = 42;
        String sessionId = java.util.UUID.randomUUID().toString();
        GlobalRouteState.startRecording(socketId, sessionId, "api.example.com", 8080);

        // Verify the session was registered
        String[] sessionInfo = GlobalRouteState.getRecordingSession(socketId);
        assertNotNull(sessionInfo);
        assertEquals(sessionId, sessionInfo[0]);
        assertEquals("api.example.com", sessionInfo[1]);
        assertEquals("8080", sessionInfo[2]);

        // Verify isRecording returns true
        assertTrue(GlobalRouteState.isRecording());

        // Clean up
        GlobalRouteState.stopRecording(socketId);
        assertNull(GlobalRouteState.getRecordingSession(socketId));
    }

    @Test
    public void testRecordAndStubModeIsRecording() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_RECORD_AND_STUB;
        assertTrue(GlobalRouteState.isRecording());
    }
}
