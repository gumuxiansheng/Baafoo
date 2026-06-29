package com.baafoo.server.auth;

import com.baafoo.core.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AuthFilterTest {

    private AuthService authService;
    private EmbeddedChannel channel;

    @Before
    public void setUp() {
        authService = mock(AuthService.class);
        AuthFilter filter = new AuthFilter(authService);
        channel = new EmbeddedChannel(filter);
    }

    private FullHttpRequest createRequest(String method, String uri) {
        ByteBuf content = Unpooled.buffer(0);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri, content);
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        return request;
    }

    @Test
    public void nonApiPathPassesThrough() {
        FullHttpRequest request = createRequest("GET", "/some-path");
        channel.writeInbound(request);
        Object out = channel.readOutbound();
        assertNull(out);
    }

    @Test
    public void optionsRequestPassesThrough() {
        when(authService.authenticate(any(), any(), any())).thenReturn(
                new AuthService.AuthResult(true, "guest", "no creds"));

        FullHttpRequest request = createRequest("OPTIONS", "/__baafoo__/api/rules");
        channel.writeInbound(request);
        Object out = channel.readOutbound();
        assertNull(out);
    }

    @Test
    public void authPathPassesThrough() {
        FullHttpRequest request = createRequest("POST", "/__baafoo__/api/auth/login");
        channel.writeInbound(request);
        Object out = channel.readOutbound();
        assertNull(out);
    }

    @Test
    public void unauthenticatedReturns401() throws Exception {
        when(authService.authenticate(any(), any(), any())).thenReturn(
                new AuthService.AuthResult(false, null, "Invalid credentials"));

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/rules");
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();

        assertNotNull(response);
        assertEquals(401, response.status().code());
    }

    @Test
    public void insufficientPermissionReturns403() throws Exception {
        when(authService.authenticate(any(), any(), any())).thenReturn(
                new AuthService.AuthResult(true, "guest", "no creds"));

        FullHttpRequest request = createRequest("POST", "/__baafoo__/api/rules");
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();

        assertNotNull(response);
        assertEquals(403, response.status().code());
    }

    @Test
    public void authenticatedRequestPassesThroughWithHeaders() {
        when(authService.authenticate(any(), any(), any())).thenReturn(
                new AuthService.AuthResult(true, "developer", "authenticated", "testuser"));

        FullHttpRequest request = createRequest("GET", "/__baafoo__/api/rules");
        channel.writeInbound(request);
        Object out = channel.readOutbound();
        assertNull(out);

        FullHttpRequest captured = channel.readInbound();
        assertNotNull(captured);
        assertEquals("developer", captured.headers().get("X-Baafoo-Auth-Role"));
        assertEquals("testuser", captured.headers().get("X-Baafoo-Auth-User"));
    }

    @Test
    public void authenticatedRequestWithNullUsernamePassesThrough() {
        when(authService.authenticate(any(), any(), any())).thenReturn(
                new AuthService.AuthResult(true, "admin", "authenticated"));

        FullHttpRequest request = createRequest("DELETE", "/__baafoo__/api/rules/r1");
        channel.writeInbound(request);
        Object out = channel.readOutbound();
        assertNull(out);

        FullHttpRequest captured = channel.readInbound();
        assertNotNull(captured);
        assertEquals("admin", captured.headers().get("X-Baafoo-Auth-Role"));
        assertEquals("", captured.headers().get("X-Baafoo-Auth-User"));
    }

    @Test
    public void exceptionCaughtClosesChannel() {
        channel.pipeline().fireExceptionCaught(new RuntimeException("test error"));
        assertFalse(channel.isOpen());
    }
}
