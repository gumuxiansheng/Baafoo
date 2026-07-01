package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P1-2: DNS cache manager.
 *
 * <p>Encapsulates the DNS resolution cache (IP -> domain) and the
 * DNS redirect target ThreadLocal previously inlined in
 * {@link GlobalRouteState}. Both backing fields ({@code DNS_CACHE} and
 * {@code DNS_REDIRECT_TARGET}) stay on {@code GlobalRouteState} for
 * Bootstrap-CL compatibility (ByteBuddy-inlined advice reads
 * {@code DNS_CACHE} by name); this class provides typed accessors.</p>
 */
public final class DnsCache {

    /** Maximum number of entries in the DNS cache (best-effort bound). */
    private static final int MAX_DNS_CACHE_SIZE = 10000;

    /** Guards the full-cache eviction so only one thread clears at a time. */
    private static final AtomicBoolean EVICTION_IN_PROGRESS = new AtomicBoolean(false);

    /**
     * Record a DNS resolution for later route lookup.
     *
     * @param domain the original domain name (e.g., "api.example.com")
     * @param ip     the resolved IP address (e.g., "93.184.216.34")
     */
    public void recordDns(String domain, String ip) {
        if (domain == null || domain.isEmpty() || ip == null || ip.isEmpty()) {
            return;
        }
        ConcurrentHashMap<String, String> cache = GlobalRouteState.DNS_CACHE;
        if (cache.size() >= MAX_DNS_CACHE_SIZE) {
            evictIfFull();
            return;
        }
        cache.putIfAbsent(ip, domain);
    }

    /** Insert an IP -> hostname mapping directly. */
    public void put(String ip, String hostname) {
        if (ip == null || ip.isEmpty() || hostname == null || hostname.isEmpty()) {
            return;
        }
        GlobalRouteState.DNS_CACHE.putIfAbsent(ip, hostname);
    }

    /** Look up the original domain name for a resolved IP. */
    public String get(String ip) {
        if (ip == null) return null;
        return GlobalRouteState.DNS_CACHE.get(ip);
    }

    /** @return the current number of cached entries. */
    public int size() {
        return GlobalRouteState.DNS_CACHE.size();
    }

    /**
     * Best-effort full-cache eviction. Only one thread performs the clear;
     * concurrent callers simply skip (the cache stays momentarily over the
     * bound, which is acceptable for this best-effort cache).
     */
    public void evictIfFull() {
        if (EVICTION_IN_PROGRESS.compareAndSet(false, true)) {
            try {
                GlobalRouteState.DNS_CACHE.clear();
            } finally {
                EVICTION_IN_PROGRESS.set(false);
            }
        }
    }

    // ---- DNS redirect target ThreadLocal ----

    public String getRedirectTarget() {
        return GlobalRouteState.DNS_REDIRECT_TARGET.get();
    }

    public void setRedirectTarget(String target) {
        GlobalRouteState.DNS_REDIRECT_TARGET.set(target);
    }

    public void clearRedirectTarget() {
        GlobalRouteState.DNS_REDIRECT_TARGET.remove();
    }
}
