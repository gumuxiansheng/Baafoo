package com.baafoo.plugin;

import org.junit.Test;
import static org.junit.Assert.*;

public class InterceptTargetTest {

    @Test
    public void testValues() {
        assertEquals(9, InterceptTarget.values().length);
        assertEquals(InterceptTarget.SOCKET, InterceptTarget.valueOf("SOCKET"));
        assertEquals(InterceptTarget.NIO_SOCKET, InterceptTarget.valueOf("NIO_SOCKET"));
        assertEquals(InterceptTarget.KAFKA, InterceptTarget.valueOf("KAFKA"));
        assertEquals(InterceptTarget.PULSAR, InterceptTarget.valueOf("PULSAR"));
        assertEquals(InterceptTarget.JMS, InterceptTarget.valueOf("JMS"));
        assertEquals(InterceptTarget.GRPC, InterceptTarget.valueOf("GRPC"));
        assertEquals(InterceptTarget.CONSUL_DNS, InterceptTarget.valueOf("CONSUL_DNS"));
        assertEquals(InterceptTarget.CONSUL_API, InterceptTarget.valueOf("CONSUL_API"));
        assertEquals(InterceptTarget.FEIGN, InterceptTarget.valueOf("FEIGN"));
    }
}
