package com.baafoo.core.util;

import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.Assert.*;

public class NetworkUtilsTest {

    // --- isSameSubnet ---

    @Test
    public void testSameSubnet_true() {
        byte[] a = addr(172, 19, 0, 3);
        byte[] b = addr(172, 19, 0, 1);
        assertTrue(NetworkUtils.isSameSubnet(a, b));
    }

    @Test
    public void testSameSubnet_false_differentThirdOctet() {
        byte[] a = addr(172, 19, 1, 3);
        byte[] b = addr(172, 19, 0, 1);
        // Same /16 subnet — still true (only first two octets matter)
        assertTrue(NetworkUtils.isSameSubnet(a, b));
    }

    @Test
    public void testSameSubnet_false_differentSecondOctet() {
        byte[] a = addr(172, 19, 0, 3);
        byte[] b = addr(172, 18, 0, 1);
        assertFalse(NetworkUtils.isSameSubnet(a, b));
    }

    @Test
    public void testSameSubnet_false_differentFirstOctet() {
        byte[] a = addr(10, 0, 0, 3);
        byte[] b = addr(172, 19, 0, 1);
        assertFalse(NetworkUtils.isSameSubnet(a, b));
    }

    // --- isPrivateSubnet ---

    @Test
    public void testPrivateSubnet_10_x() {
        assertTrue(NetworkUtils.isPrivateSubnet(addr(10, 0, 0, 1)));
        assertTrue(NetworkUtils.isPrivateSubnet(addr(10, 255, 255, 255)));
    }

    @Test
    public void testPrivateSubnet_172_16_to_31() {
        assertTrue(NetworkUtils.isPrivateSubnet(addr(172, 16, 0, 1)));
        assertTrue(NetworkUtils.isPrivateSubnet(addr(172, 31, 255, 255)));
        assertFalse(NetworkUtils.isPrivateSubnet(addr(172, 15, 0, 1)));
        assertFalse(NetworkUtils.isPrivateSubnet(addr(172, 32, 0, 1)));
    }

    @Test
    public void testPrivateSubnet_192_168() {
        assertTrue(NetworkUtils.isPrivateSubnet(addr(192, 168, 0, 1)));
        assertTrue(NetworkUtils.isPrivateSubnet(addr(192, 168, 255, 255)));
    }

    @Test
    public void testPrivateSubnet_public() {
        assertFalse(NetworkUtils.isPrivateSubnet(addr(8, 8, 8, 8)));
        assertFalse(NetworkUtils.isPrivateSubnet(addr(172, 33, 0, 1)));
        assertFalse(NetworkUtils.isPrivateSubnet(addr(192, 169, 0, 1)));
    }

    // --- isLikelyGateway ---

    @Test
    public void testLikelyGateway_true() {
        assertTrue(NetworkUtils.isLikelyGateway(addr(172, 19, 0, 1)));
        assertTrue(NetworkUtils.isLikelyGateway(addr(10, 0, 0, 1)));
        assertTrue(NetworkUtils.isLikelyGateway(addr(192, 168, 1, 1)));
    }

    @Test
    public void testLikelyGateway_false() {
        assertFalse(NetworkUtils.isLikelyGateway(addr(172, 19, 0, 2)));
        assertFalse(NetworkUtils.isLikelyGateway(addr(172, 19, 0, 100)));
        assertFalse(NetworkUtils.isLikelyGateway(addr(10, 0, 0, 254)));
    }

    // --- resolveClientReachableHost ---

    @Test
    public void testResolve_dockerInternalClient_returnsDefaultHost() throws Exception {
        // Container client (172.19.0.5) talking to server (172.19.0.3)
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(addr(172, 19, 0, 5)), 12345);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(addr(172, 19, 0, 3)), 9003);

        String result = NetworkUtils.resolveClientReachableHost(remote, local, "172.19.0.3", "localhost");
        assertEquals("172.19.0.3", result);
    }

    @Test
    public void testResolve_dockerGatewayClient_returnsAdvertisedHost() throws Exception {
        // Host machine via Docker gateway (172.19.0.1) talking to server (172.19.0.3)
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(addr(172, 19, 0, 1)), 12345);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(addr(172, 19, 0, 3)), 9003);

        String result = NetworkUtils.resolveClientReachableHost(remote, local, "172.19.0.3", "localhost");
        assertEquals("localhost", result);
    }

    @Test
    public void testResolve_dockerGatewayClient_noAdvertisedHost_returnsLocalIp() throws Exception {
        // Host machine via Docker gateway, no advertisedHost configured
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(addr(172, 19, 0, 1)), 12345);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(addr(172, 19, 0, 3)), 9003);

        String result = NetworkUtils.resolveClientReachableHost(remote, local, "172.19.0.3", null);
        assertEquals("172.19.0.3", result);
    }

    @Test
    public void testResolve_externalClient_returnsAdvertisedHost() throws Exception {
        // External client (8.8.8.8) talking to server (172.19.0.3)
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(addr(8, 8, 8, 8)), 12345);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(addr(172, 19, 0, 3)), 9003);

        String result = NetworkUtils.resolveClientReachableHost(remote, local, "172.19.0.3", "my-host.example.com");
        assertEquals("my-host.example.com", result);
    }

    @Test
    public void testResolve_externalClient_noAdvertisedHost_returnsLocalIp() throws Exception {
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(addr(8, 8, 8, 8)), 12345);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(addr(172, 19, 0, 3)), 9003);

        String result = NetworkUtils.resolveClientReachableHost(remote, local, "172.19.0.3", null);
        assertEquals("172.19.0.3", result);
    }

    @Test
    public void testResolve_nullAddresses_returnsDefaultHost() {
        String result = NetworkUtils.resolveClientReachableHost(null, null, "172.19.0.3", "localhost");
        assertEquals("172.19.0.3", result);
    }

    @Test
    public void testResolve_10Subnet_gatewayClient() throws Exception {
        // 10.0.0.x subnet, gateway client
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(addr(10, 0, 0, 1)), 12345);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(addr(10, 0, 0, 5)), 9003);

        String result = NetworkUtils.resolveClientReachableHost(remote, local, "10.0.0.5", "localhost");
        assertEquals("localhost", result);
    }

    @Test
    public void testResolve_192_168Subnet_internalClient() throws Exception {
        // 192.168.1.x subnet, regular container client
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(addr(192, 168, 1, 100)), 12345);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(addr(192, 168, 1, 1)), 9003);

        String result = NetworkUtils.resolveClientReachableHost(remote, local, "192.168.1.1", "localhost");
        assertEquals("192.168.1.1", result);
    }

    // --- helper ---

    private static byte[] addr(int a, int b, int c, int d) {
        return new byte[]{(byte) a, (byte) b, (byte) c, (byte) d};
    }
}
