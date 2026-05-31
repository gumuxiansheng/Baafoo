package com.baafoo.agent;

import org.junit.Test;
import static org.junit.Assert.*;

public class RouteTableTest {

    @Test
    public void testDefaultState() {
        RouteTable rt = new RouteTable();
        assertEquals(0, rt.size());
        assertEquals(0L, rt.getVersion());
    }

    @Test
    public void testLookup() {
        RouteTable rt = new RouteTable();
        rt.put("api.test.com", 80, "127.0.0.1", 9000);
        GlobalRouteState.HostPort route = rt.lookup("api.test.com", 80);
        assertNotNull(route);
        assertEquals("127.0.0.1", route.host);
        assertEquals(9000, route.port);
    }

    @Test
    public void testLookupNullHost() {
        RouteTable rt = new RouteTable();
        assertNull(rt.lookup(null, 80));
    }

    @Test
    public void testLookupNotFound() {
        RouteTable rt = new RouteTable();
        assertNull(rt.lookup("unknown", 80));
    }

    @Test
    public void testLookupService() {
        RouteTable rt = new RouteTable();
        rt.putService("my-service", "127.0.0.1", 9000);
        GlobalRouteState.HostPort target = rt.lookupService("my-service");
        assertNotNull(target);
        assertEquals("127.0.0.1", target.host);
        assertEquals(9000, target.port);
    }

    @Test
    public void testLookupServiceNull() {
        RouteTable rt = new RouteTable();
        assertNull(rt.lookupService(null));
        assertNull(rt.lookupService("unknown"));
    }

    @Test
    public void testRemove() {
        RouteTable rt = new RouteTable();
        rt.put("host", 80, "stub", 9000);
        assertEquals(1, rt.size());
        rt.remove("host", 80);
        assertEquals(0, rt.size());
    }

    @Test
    public void testRemoveNullHost() {
        RouteTable rt = new RouteTable();
        rt.remove(null, 80);
        assertEquals(0, rt.size());
    }

    @Test
    public void testRemoveService() {
        RouteTable rt = new RouteTable();
        rt.putService("svc", "stub", 9000);
        assertEquals(1, rt.size());
        rt.removeService("svc");
        assertEquals(0, rt.size());
    }

    @Test
    public void testRemoveServiceNull() {
        RouteTable rt = new RouteTable();
        rt.removeService(null);
    }

    @Test
    public void testClear() {
        RouteTable rt = new RouteTable();
        rt.put("host1", 80, "stub", 9000);
        rt.put("host2", 443, "stub", 9000);
        rt.putService("svc", "stub", 9000);
        assertEquals(3, rt.size());
        rt.clear();
        assertEquals(0, rt.size());
    }

    @Test
    public void testIncrementVersion() {
        RouteTable rt = new RouteTable();
        assertEquals(0L, rt.getVersion());
        rt.incrementVersion();
        assertEquals(1L, rt.getVersion());
    }

    @Test
    public void testGetRoutes() {
        RouteTable rt = new RouteTable();
        rt.put("host", 80, "stub", 9000);
        assertFalse(rt.getRoutes().isEmpty());
        GlobalRouteState.HostPort hp = rt.getRoutes().get("host:80");
        assertNotNull(hp);
        assertEquals("stub", hp.host);
        assertEquals(9000, hp.port);
    }

    @Test
    public void testGetServiceNames() {
        RouteTable rt = new RouteTable();
        rt.putService("svc", "stub", 9000);
        assertFalse(rt.getServiceNames().isEmpty());
        GlobalRouteState.HostPort hp = rt.getServiceNames().get("svc");
        assertNotNull(hp);
        assertEquals("stub", hp.host);
        assertEquals(9000, hp.port);
    }
}
