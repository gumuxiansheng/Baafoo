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
        rt.put("api.test.com", 80, "127.0.0.1", 9000, "http");
        String route = rt.lookup("api.test.com", 80);
        assertEquals("127.0.0.1:9000:http", route);
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
        rt.putService("my-service", "127.0.0.1", 9000, "http");
        assertEquals("127.0.0.1:9000:http", rt.lookupService("my-service"));
    }

    @Test
    public void testLookupServiceNull() {
        RouteTable rt = new RouteTable();
        assertNull(rt.lookupService(null));
        assertNull(rt.lookupService("unknown"));
    }

    @Test
    public void testParseHost() {
        assertEquals("127.0.0.1", RouteTable.parseHost("127.0.0.1:9000:http"));
        assertEquals("127.0.0.1", RouteTable.parseHost(null));
        assertEquals("127.0.0.1", RouteTable.parseHost("no-colon"));
    }

    @Test
    public void testParsePort() {
        assertEquals(9000, RouteTable.parsePort("host:9000:http"));
        assertEquals(9001, RouteTable.parsePort(null));
        assertEquals(9001, RouteTable.parsePort("no-colon"));
    }

    @Test
    public void testParsePortInvalidNumber() {
        assertEquals(9001, RouteTable.parsePort("host:abc:http"));
    }

    @Test
    public void testParseProtocol() {
        assertEquals("http", RouteTable.parseProtocol("host:9000:http"));
        assertEquals("tcp", RouteTable.parseProtocol(null));
        assertEquals("tcp", RouteTable.parseProtocol("no-colon"));
        assertEquals("tcp", RouteTable.parseProtocol("only:one"));
    }

    @Test
    public void testRemove() {
        RouteTable rt = new RouteTable();
        rt.put("host", 80, "stub", 9000, "http");
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
        rt.putService("svc", "stub", 9000, "http");
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
        rt.put("host1", 80, "stub", 9000, "http");
        rt.put("host2", 443, "stub", 9000, "http");
        rt.putService("svc", "stub", 9000, "http");
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
        rt.put("host", 80, "stub", 9000, "http");
        assertFalse(rt.getRoutes().isEmpty());
        assertEquals("stub:9000:http", rt.getRoutes().get("host:80"));
    }

    @Test
    public void testGetServiceNames() {
        RouteTable rt = new RouteTable();
        rt.putService("svc", "stub", 9000, "http");
        assertFalse(rt.getServiceNames().isEmpty());
        assertEquals("stub:9000:http", rt.getServiceNames().get("svc"));
    }
}
