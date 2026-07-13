package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DnsCacheTest {

    private final DnsCache cache = new DnsCache();

    @Before
    public void clearCache() {
        GlobalRouteState.DNS_CACHE.clear();
    }

    @After
    public void clearCacheAfter() {
        GlobalRouteState.DNS_CACHE.clear();
    }

    @Test
    public void recordDnsAddsEntry() {
        cache.recordDns("api.example.com", "93.184.216.34");
        assertEquals("api.example.com", cache.get("93.184.216.34"));
    }

    @Test
    public void recordDnsNullDomainIgnored() {
        cache.recordDns(null, "1.2.3.4");
        assertNull(cache.get("1.2.3.4"));
    }

    @Test
    public void recordDnsEmptyDomainIgnored() {
        cache.recordDns("", "1.2.3.4");
        assertNull(cache.get("1.2.3.4"));
    }

    @Test
    public void recordDnsNullIpIgnored() {
        cache.recordDns("example.com", null);
        assertEquals(0, cache.size());
    }

    @Test
    public void recordDnsEmptyIpIgnored() {
        cache.recordDns("example.com", "");
        assertEquals(0, cache.size());
    }

    @Test
    public void putAddsEntry() {
        cache.put("10.0.0.1", "myhost.com");
        assertEquals("myhost.com", cache.get("10.0.0.1"));
    }

    @Test
    public void putNullIpIgnored() {
        cache.put(null, "host.com");
        assertEquals(0, cache.size());
    }

    @Test
    public void putEmptyIpIgnored() {
        cache.put("", "host.com");
        assertEquals(0, cache.size());
    }

    @Test
    public void putNullHostnameIgnored() {
        cache.put("1.2.3.4", null);
        assertNull(cache.get("1.2.3.4"));
    }

    @Test
    public void putEmptyHostnameIgnored() {
        cache.put("1.2.3.4", "");
        assertNull(cache.get("1.2.3.4"));
    }

    @Test
    public void getNullReturnsNull() {
        assertNull(cache.get(null));
    }

    @Test
    public void getMissingReturnsNull() {
        assertNull(cache.get("1.2.3.4"));
    }

    @Test
    public void sizeReturnsCount() {
        assertEquals(0, cache.size());
        cache.recordDns("a.com", "1.1.1.1");
        assertEquals(1, cache.size());
        cache.recordDns("b.com", "2.2.2.2");
        assertEquals(2, cache.size());
    }

    @Test
    public void putIfAbsentDoesNotOverwrite() {
        cache.put("1.1.1.1", "first.com");
        cache.put("1.1.1.1", "second.com");
        assertEquals("first.com", cache.get("1.1.1.1"));
    }

    @Test
    public void redirectTargetLifecycle() {
        assertNull(cache.getRedirectTarget());
        cache.setRedirectTarget("10.0.0.5");
        assertEquals("10.0.0.5", cache.getRedirectTarget());
        cache.clearRedirectTarget();
        assertNull(cache.getRedirectTarget());
    }

    @Test
    public void evictIfFullClearsCache() {
        cache.recordDns("a.com", "1.1.1.1");
        cache.recordDns("b.com", "2.2.2.2");
        assertEquals(2, cache.size());
        cache.evictIfFull();
        assertEquals(0, cache.size());
    }
}
