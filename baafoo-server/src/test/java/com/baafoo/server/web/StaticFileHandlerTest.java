package com.baafoo.server.web;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.Test;

import static org.junit.Assert.*;

public class StaticFileHandlerTest {

    @Test
    public void testUnknownPathReturns404() {
        StaticFileHandler handler = new StaticFileHandler("./nonexistent");
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/unknown");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(404, response.status().code());
    }

    @Test
    public void testExceptionCaughtClosesChannel() {
        StaticFileHandler handler = new StaticFileHandler("./web");
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.pipeline().fireExceptionCaught(new RuntimeException("test error"));
        assertFalse(channel.isOpen());
    }
}
