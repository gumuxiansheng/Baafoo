package com.baafoo.core.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolTest {

    @Test
    public void testValues() {
        assertEquals(6, Protocol.values().length);
        assertEquals(Protocol.HTTP, Protocol.valueOf("HTTP"));
        assertEquals(Protocol.TCP, Protocol.valueOf("TCP"));
        assertEquals(Protocol.KAFKA, Protocol.valueOf("KAFKA"));
        assertEquals(Protocol.PULSAR, Protocol.valueOf("PULSAR"));
        assertEquals(Protocol.JMS, Protocol.valueOf("JMS"));
        assertEquals(Protocol.GRPC, Protocol.valueOf("GRPC"));
    }

    @Test
    public void testGetName() {
        assertEquals("http", Protocol.HTTP.getName());
        assertEquals("tcp", Protocol.TCP.getName());
        assertEquals("kafka", Protocol.KAFKA.getName());
        assertEquals("pulsar", Protocol.PULSAR.getName());
        assertEquals("jms", Protocol.JMS.getName());
        assertEquals("grpc", Protocol.GRPC.getName());
    }

    @Test
    public void testGetDefaultPort() {
        assertEquals(9000, Protocol.HTTP.getDefaultPort());
        assertEquals(9001, Protocol.TCP.getDefaultPort());
        assertEquals(9002, Protocol.KAFKA.getDefaultPort());
        assertEquals(9003, Protocol.PULSAR.getDefaultPort());
        assertEquals(9004, Protocol.JMS.getDefaultPort());
        assertEquals(9005, Protocol.GRPC.getDefaultPort());
    }

    @Test
    public void testFromName() {
        assertEquals(Protocol.HTTP, Protocol.fromName("http"));
        assertEquals(Protocol.TCP, Protocol.fromName("tcp"));
        assertEquals(Protocol.HTTP, Protocol.fromName("HTTP"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromNameUnknown() {
        Protocol.fromName("unknown");
    }
}
