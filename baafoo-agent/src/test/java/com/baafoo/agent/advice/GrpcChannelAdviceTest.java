package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link GrpcChannelAdvice#parseTarget(String)}.
 *
 * <p>parseTarget is a pure function (no side effects, no static state dependency)
 * so it can be tested in isolation. The onForTarget() advice method depends on
 * GlobalRouteState and RouteManager which require the full agent bootstrap —
 * those integration paths are covered by the integration tests.</p>
 */
public class GrpcChannelAdviceTest {

    @Before
    public void setUp() {
        GlobalRouteState.GRPC_PORT = 9005;
    }

    @After
    public void tearDown() {
        GlobalRouteState.GRPC_PORT = 9005;
    }

    // ---- Null / empty ----

    @Test
    public void parseTarget_null_returnsNull() {
        assertNull(GrpcChannelAdvice.parseTarget(null));
    }

    @Test
    public void parseTarget_empty_returnsNull() {
        assertNull(GrpcChannelAdvice.parseTarget(""));
    }

    // ---- dns:/// scheme ----

    @Test
    public void parseTarget_dnsScheme_hostAndPort() {
        String[] result = GrpcChannelAdvice.parseTarget("dns:///myhost:50051");
        assertNotNull(result);
        assertEquals("myhost", result[0]);
        assertEquals("50051", result[1]);
    }

    @Test
    public void parseTarget_dnsScheme_hostOnly() {
        String[] result = GrpcChannelAdvice.parseTarget("dns:///myhost");
        assertNotNull(result);
        assertEquals("myhost", result[0]);
        assertEquals(String.valueOf(GlobalRouteState.GRPC_PORT), result[1]);
    }

    @Test
    public void parseTarget_dnsScheme_emptyAfterPrefix_returnsNull() {
        assertNull(GrpcChannelAdvice.parseTarget("dns:///"));
    }

    // ---- static:/// scheme ----

    @Test
    public void parseTarget_staticScheme_hostAndPort() {
        String[] result = GrpcChannelAdvice.parseTarget("static:///10.0.0.1:8080");
        assertNotNull(result);
        assertEquals("10.0.0.1", result[0]);
        assertEquals("8080", result[1]);
    }

    @Test
    public void parseTarget_staticScheme_emptyAfterPrefix_returnsNull() {
        assertNull(GrpcChannelAdvice.parseTarget("static:///"));
    }

    // ---- dns: scheme (without ///) ----

    @Test
    public void parseTarget_dnsColonScheme() {
        String[] result = GrpcChannelAdvice.parseTarget("dns:myhost:50051");
        assertNotNull(result);
        assertEquals("myhost", result[0]);
        assertEquals("50051", result[1]);
    }

    // ---- host:port (no scheme) ----

    @Test
    public void parseTarget_hostPort() {
        String[] result = GrpcChannelAdvice.parseTarget("example.com:443");
        assertNotNull(result);
        assertEquals("example.com", result[0]);
        assertEquals("443", result[1]);
    }

    @Test
    public void parseTarget_localhostPort() {
        String[] result = GrpcChannelAdvice.parseTarget("localhost:9090");
        assertNotNull(result);
        assertEquals("localhost", result[0]);
        assertEquals("9090", result[1]);
    }

    // ---- host only (no port) ----

    @Test
    public void parseTarget_hostOnly_usesDefaultGrpcPort() {
        String[] result = GrpcChannelAdvice.parseTarget("my-service");
        assertNotNull(result);
        assertEquals("my-service", result[0]);
        assertEquals(String.valueOf(GlobalRouteState.GRPC_PORT), result[1]);
    }

    @Test
    public void parseTarget_hostOnly_customDefaultPort() {
        GlobalRouteState.GRPC_PORT = 50051;
        String[] result = GrpcChannelAdvice.parseTarget("my-service");
        assertNotNull(result);
        assertEquals("my-service", result[0]);
        assertEquals("50051", result[1]);
    }

    // ---- Invalid port (non-numeric) ----

    @Test
    public void parseTarget_invalidPort_treatsAsHostWithDefaultPort() {
        // "host:notaport" — last colon found, but port part is not numeric
        // Should treat entire string as host with default port
        String[] result = GrpcChannelAdvice.parseTarget("host:notaport");
        assertNotNull(result);
        assertEquals("host:notaport", result[0]);
        assertEquals(String.valueOf(GlobalRouteState.GRPC_PORT), result[1]);
    }

    // ---- Empty host after parsing ----

    @Test
    public void parseTarget_emptyHost_returnsNull() {
        // ":8080" — host part is empty
        assertNull(GrpcChannelAdvice.parseTarget(":8080"));
    }

    // ---- Edge cases ----

    @Test
    public void parseTarget_ipv4Address() {
        String[] result = GrpcChannelAdvice.parseTarget("192.168.1.100:8080");
        assertNotNull(result);
        assertEquals("192.168.1.100", result[0]);
        assertEquals("8080", result[1]);
    }

    @Test
    public void parseTarget_port0() {
        String[] result = GrpcChannelAdvice.parseTarget("host:0");
        assertNotNull(result);
        assertEquals("host", result[0]);
        assertEquals("0", result[1]);
    }

    @Test
    public void parseTarget_largePort() {
        String[] result = GrpcChannelAdvice.parseTarget("host:65535");
        assertNotNull(result);
        assertEquals("host", result[0]);
        assertEquals("65535", result[1]);
    }

    @Test
    public void parseTarget_multipleColons_usesLastColon() {
        // "host:port:extra" — last colon is the delimiter
        // port part "extra" is not numeric, so treated as host with default port
        String[] result = GrpcChannelAdvice.parseTarget("host:port:extra");
        assertNotNull(result);
        assertEquals("host:port:extra", result[0]);
        assertEquals(String.valueOf(GlobalRouteState.GRPC_PORT), result[1]);
    }
}
