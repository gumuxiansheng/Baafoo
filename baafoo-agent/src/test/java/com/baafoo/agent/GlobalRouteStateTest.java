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
        GlobalRouteState.addRoute("api.test.com", 80, "127.0.0.1", 9000);
        String[] route = GlobalRouteState.lookup("api.test.com", 80);
        assertNotNull(route);
        assertEquals("127.0.0.1", route[0]);
        assertEquals("9000", route[1]);
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
        GlobalRouteState.addRoute("api.test.com", 80, "127.0.0.1", 9000);
        assertNull(GlobalRouteState.lookup("api.test.com", 0));
    }

    @Test
    public void testLookupService() {
        GlobalRouteState.addService("my-service", "127.0.0.1", 9000);
        GlobalRouteState.HostPort target = GlobalRouteState.lookupService("my-service");
        assertNotNull(target);
        assertEquals("127.0.0.1", target.host);
        assertEquals(9000, target.port);
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
    public void testIsInternal() {
        assertTrue(GlobalRouteState.isInternal("127.0.0.1", 8080));
        assertTrue(GlobalRouteState.isInternal("127.0.0.1", 9000));
        assertTrue(GlobalRouteState.isInternal("localhost", 9001));
        assertFalse(GlobalRouteState.isInternal("external.com", 80));
        assertFalse(GlobalRouteState.isInternal("127.0.0.1", 9999));
    }

    @Test
    public void testAddRoute() {
        GlobalRouteState.addRoute("host", 80, "stub", 9000);
        String[] route = GlobalRouteState.lookup("host", 80);
        assertNotNull(route);
        assertEquals("stub", route[0]);
        assertEquals("9000", route[1]);
    }

    @Test
    public void testAddService() {
        GlobalRouteState.addService("svc", "stub", 9000);
        GlobalRouteState.HostPort target = GlobalRouteState.lookupService("svc");
        assertNotNull(target);
        assertEquals("stub", target.host);
        assertEquals(9000, target.port);
    }

    @Test
    public void testClearRoutes() {
        GlobalRouteState.addRoute("host", 80, "stub", 9000);
        assertEquals(1, GlobalRouteState.ROUTES.size());
        GlobalRouteState.clearRoutes();
        assertEquals(0, GlobalRouteState.ROUTES.size());
    }
}
