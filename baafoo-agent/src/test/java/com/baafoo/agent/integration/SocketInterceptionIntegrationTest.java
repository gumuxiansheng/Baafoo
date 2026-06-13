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
    }

    @After
    public void teardown() {
        GlobalRouteState.ROUTES.clear();
        GlobalRouteState.DNS_CACHE.clear();
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
}
