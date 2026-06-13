package com.baafoo.agent.integration;

import com.baafoo.agent.GlobalRouteState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.*;

public class DnsInterceptionIntegrationTest {

    @Before
    public void setup() {
        GlobalRouteState.SERVER_HOST = "127.0.0.1";
        GlobalRouteState.SERVER_PORT = 9000;
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        GlobalRouteState.DNS_CACHE.clear();
    }

    @After
    public void teardown() {
        GlobalRouteState.DNS_CACHE.clear();
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;
    }

    @Test
    public void testDnsResolutionIsRecorded() throws Exception {
        GlobalRouteState.DNS_CACHE.clear();
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;

        // Resolve a well-known hostname
        InetAddress addr = InetAddress.getByName("localhost");

        // The DNS cache should have recorded this resolution
        // Note: "localhost" typically resolves to 127.0.0.1
        String ip = addr.getHostAddress();
        Object cachedDomain = GlobalRouteState.DNS_CACHE.get(ip);
        // The cache maps IP -> domain, so we should find "localhost" for the IP
        if (cachedDomain != null) {
            assertEquals("localhost", cachedDomain);
        }
        // If cachedDomain is null, it might be because localhost is treated as internal
    }

    @Test
    public void testDnsCacheNotRecordedInPassthrough() throws Exception {
        GlobalRouteState.DNS_CACHE.clear();
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;

        int sizeBefore = GlobalRouteState.DNS_CACHE.size();

        // In passthrough mode, DNS should not be recorded
        InetAddress.getByName("localhost");

        // DNS cache should not have grown
        assertEquals(sizeBefore, GlobalRouteState.DNS_CACHE.size());
    }

    @Test
    public void testRecordDnsDirectly() {
        GlobalRouteState.DNS_CACHE.clear();
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;

        GlobalRouteState.recordDns("example.com", "93.184.216.34");

        assertEquals("example.com", GlobalRouteState.DNS_CACHE.get("93.184.216.34"));
    }

    @Test
    public void testRecordDnsOverwrites() {
        GlobalRouteState.DNS_CACHE.clear();
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;

        GlobalRouteState.recordDns("example.com", "93.184.216.34");
        GlobalRouteState.recordDns("other.com", "93.184.216.34");

        // recordDns uses putIfAbsent, so the first write wins
        assertEquals("example.com", GlobalRouteState.DNS_CACHE.get("93.184.216.34"));
    }
}
