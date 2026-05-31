package com.baafoo.agent;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class GlobalRouteStateTest {

    @Before
    public void setUp() {
        GlobalRouteState.clearRoutes();
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        GlobalRouteState.SERVER_HOST = "127.0.0.1";
        GlobalRouteState.SERVER_PORT = 8080;
    }

    @Test
    public void testLookup() {
        GlobalRouteState.putRoute("api.test.com", 80, "127.0.0.1", 9000, "http");
        String route = GlobalRouteState.lookup("api.test.com", 80);
        assertEquals("127.0.0.1:9000:http", route);
    }

    @Test
    public void testLookupNullHost() {
        assertNull(GlobalRouteState.lookup(null, 80));
    }

    @Test
    public void testLookupNotFound() {
        assertNull(GlobalRouteState.lookup("unknown", 80));
    }

    @Test
    public void testLookupByHostOnlyReturnsNullWithoutHostKey() {
        GlobalRouteState.putRoute("api.test.com", 80, "127.0.0.1", 9000, "http");
        // lookup stores "host:port" keys, not bare host keys
        // So lookup with port 0 won't find "api.test.com:80" and no bare "api.test.com" key exists
        assertNull(GlobalRouteState.lookup("api.test.com", 0));
    }

    @Test
    public void testLookupService() {
        GlobalRouteState.putService("my-service", "127.0.0.1", 9000, "http");
        String route = GlobalRouteState.lookupService("my-service");
        assertEquals("127.0.0.1:9000:http", route);
    }

    @Test
    public void testLookupServiceNull() {
        assertNull(GlobalRouteState.lookupService(null));
        assertNull(GlobalRouteState.lookupService("unknown"));
    }

    @Test
    public void testIsPassthrough() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;
        assertTrue(GlobalRouteState.isPassthrough());

        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        assertFalse(GlobalRouteState.isPassthrough());
    }

    @Test
    public void testIsRecording() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_RECORD;
        assertTrue(GlobalRouteState.isRecording());

        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_RECORD_AND_STUB;
        assertTrue(GlobalRouteState.isRecording());

        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        assertFalse(GlobalRouteState.isRecording());

        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;
        assertFalse(GlobalRouteState.isRecording());
    }

    @Test
    public void testParseHost() {
        assertEquals("127.0.0.1", GlobalRouteState.parseHost("127.0.0.1:9000:http"));
        assertEquals("127.0.0.1", GlobalRouteState.parseHost(null));
        assertEquals("127.0.0.1", GlobalRouteState.parseHost("no-colon"));
    }

    @Test
    public void testParsePort() {
        assertEquals(9000, GlobalRouteState.parsePort("127.0.0.1:9000:http"));
        assertEquals(9001, GlobalRouteState.parsePort(null));
        assertEquals(9001, GlobalRouteState.parsePort("no-colon"));
        assertEquals(9001, GlobalRouteState.parsePort("host:bad:http"));
    }

    @Test
    public void testParsePortNormal() {
        assertEquals(8080, GlobalRouteState.parsePort("host:8080:proto"));
    }

    @Test
    public void testIsInternal() {
        assertTrue(GlobalRouteState.isInternal("127.0.0.1", 8080));
        assertTrue(GlobalRouteState.isInternal("127.0.0.1", 9000));
        assertTrue(GlobalRouteState.isInternal("localhost", 9001));
        assertFalse(GlobalRouteState.isInternal("external.com", 80));
        assertFalse(GlobalRouteState.isInternal("127.0.0.1", 9999));
    }

    @Test
    public void testPutRoute() {
        GlobalRouteState.putRoute("host", 80, "stub", 9000, "http");
        assertEquals("stub:9000:http", GlobalRouteState.lookup("host", 80));
    }

    @Test
    public void testPutService() {
        GlobalRouteState.putService("svc", "stub", 9000, "http");
        assertEquals("stub:9000:http", GlobalRouteState.lookupService("svc"));
    }

    @Test
    public void testClearRoutes() {
        GlobalRouteState.putRoute("host", 80, "stub", 9000, "http");
        assertEquals(1, GlobalRouteState.ROUTES.size());
        GlobalRouteState.clearRoutes();
        assertEquals(0, GlobalRouteState.ROUTES.size());
    }
}
