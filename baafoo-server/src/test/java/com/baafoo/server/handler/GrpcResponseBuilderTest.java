package com.baafoo.server.handler;

import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.util.TemplateEngine;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for {@link GrpcResponseBuilder}.
 *
 * <p>Focuses on logic correctness:
 * <ul>
 *   <li>gRPC status extraction from dedicated fields vs headers fallback</li>
 *   <li>Response message building with template rendering</li>
 *   <li>Multi-message splitting for streaming</li>
 *   <li>Edge cases: null body, empty body, hex body</li>
 * </ul>
 */
public class GrpcResponseBuilderTest {

    // ---- getGrpcStatus ----

    @Test
    public void getGrpcStatus_dedicatedField_takesPrecedence() {
        ResponseEntry entry = new ResponseEntry();
        entry.setGrpcStatus(13); // INTERNAL
        entry.setHeaders(Collections.singletonMap("grpc-status", "0")); // should be ignored
        assertEquals(13, GrpcResponseBuilder.getGrpcStatus(entry));
    }

    @Test
    public void getGrpcStatus_dedicatedFieldZero_fallsToHeaders() {
        ResponseEntry entry = new ResponseEntry();
        entry.setGrpcStatus(0); // default
        entry.setHeaders(Collections.singletonMap("grpc-status", "14")); // SERVICE_UNAVAILABLE
        assertEquals(14, GrpcResponseBuilder.getGrpcStatus(entry));
    }

    @Test
    public void getGrpcStatus_noDedicatedField_noHeaders_returns0() {
        ResponseEntry entry = new ResponseEntry();
        assertEquals(0, GrpcResponseBuilder.getGrpcStatus(entry));
    }

    @Test
    public void getGrpcStatus_caseInsensitiveHeaderLookup() {
        ResponseEntry entry = new ResponseEntry();
        entry.setHeaders(Collections.singletonMap("Grpc-Status", "7"));
        assertEquals(7, GrpcResponseBuilder.getGrpcStatus(entry));
    }

    @Test
    public void getGrpcStatus_invalidHeaderValue_returns0() {
        ResponseEntry entry = new ResponseEntry();
        entry.setHeaders(Collections.singletonMap("grpc-status", "not-a-number"));
        assertEquals(0, GrpcResponseBuilder.getGrpcStatus(entry));
    }

    @Test
    public void getGrpcStatus_nullHeaders_returns0() {
        ResponseEntry entry = new ResponseEntry();
        entry.setHeaders(null);
        assertEquals(0, GrpcResponseBuilder.getGrpcStatus(entry));
    }

    // ---- getGrpcMessage ----

    @Test
    public void getGrpcMessage_dedicatedField_takesPrecedence() {
        ResponseEntry entry = new ResponseEntry();
        entry.setGrpcStatusMessage("something went wrong");
        entry.setHeaders(Collections.singletonMap("grpc-message", "should be ignored"));
        assertEquals("something went wrong", GrpcResponseBuilder.getGrpcMessage(entry));
    }

    @Test
    public void getGrpcMessage_dedicatedFieldEmpty_fallsToHeaders() {
        ResponseEntry entry = new ResponseEntry();
        entry.setGrpcStatusMessage("");
        entry.setHeaders(Collections.singletonMap("grpc-message", "fallback message"));
        assertEquals("fallback message", GrpcResponseBuilder.getGrpcMessage(entry));
    }

    @Test
    public void getGrpcMessage_noDedicatedField_noHeaders_returnsEmpty() {
        ResponseEntry entry = new ResponseEntry();
        assertEquals("", GrpcResponseBuilder.getGrpcMessage(entry));
    }

    @Test
    public void getGrpcMessage_nullDedicatedField_fallsToHeaders() {
        ResponseEntry entry = new ResponseEntry();
        entry.setGrpcStatusMessage(null);
        entry.setHeaders(Collections.singletonMap("grpc-message", "from headers"));
        assertEquals("from headers", GrpcResponseBuilder.getGrpcMessage(entry));
    }

    // ---- buildResponseMessages ----

    @Test
    public void buildResponseMessages_nullBody_returnsEmptyBytes() {
        ResponseEntry entry = new ResponseEntry();
        entry.setBody(null);
        List<byte[]> messages = GrpcResponseBuilder.buildResponseMessages(entry, null, null);
        assertEquals(1, messages.size());
        assertArrayEquals(new byte[0], messages.get(0));
    }

    @Test
    public void buildResponseMessages_emptyBody_returnsEmptyBytes() {
        ResponseEntry entry = new ResponseEntry();
        entry.setBody("");
        List<byte[]> messages = GrpcResponseBuilder.buildResponseMessages(entry, null, null);
        assertEquals(1, messages.size());
        assertArrayEquals(new byte[0], messages.get(0));
    }

    @Test
    public void buildResponseMessages_singleMessage() {
        ResponseEntry entry = new ResponseEntry();
        entry.setBody("hello");
        List<byte[]> messages = GrpcResponseBuilder.buildResponseMessages(entry, null, null);
        assertEquals(1, messages.size());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), messages.get(0));
    }

    @Test
    public void buildResponseMessages_commaSeparated_splitIntoMultiple() {
        ResponseEntry entry = new ResponseEntry();
        entry.setBody("msg1,msg2,msg3");
        List<byte[]> messages = GrpcResponseBuilder.buildResponseMessages(entry, null, null);
        assertEquals(3, messages.size());
        assertArrayEquals("msg1".getBytes(StandardCharsets.UTF_8), messages.get(0));
        assertArrayEquals("msg2".getBytes(StandardCharsets.UTF_8), messages.get(1));
        assertArrayEquals("msg3".getBytes(StandardCharsets.UTF_8), messages.get(2));
    }

    @Test
    public void buildResponseMessages_newlineSeparated_splitIntoMultiple() {
        ResponseEntry entry = new ResponseEntry();
        entry.setBody("line1\nline2\nline3");
        List<byte[]> messages = GrpcResponseBuilder.buildResponseMessages(entry, null, null);
        assertEquals(3, messages.size());
        assertArrayEquals("line1".getBytes(StandardCharsets.UTF_8), messages.get(0));
        assertArrayEquals("line2".getBytes(StandardCharsets.UTF_8), messages.get(1));
        assertArrayEquals("line3".getBytes(StandardCharsets.UTF_8), messages.get(2));
    }

    @Test
    public void buildResponseMessages_hexBody_decodedToBytes() {
        ResponseEntry entry = new ResponseEntry();
        entry.setBody("0xCAFEBABE");
        List<byte[]> messages = GrpcResponseBuilder.buildResponseMessages(entry, null, null);
        assertEquals(1, messages.size());
        assertArrayEquals(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}, messages.get(0));
    }

    @Test
    public void buildResponseMessages_templateRendering() {
        ResponseEntry entry = new ResponseEntry();
        entry.setBody("Hello {{request.method}}!");
        TemplateEngine.RequestContext ctx = TemplateEngine.RequestContext.builder()
                .method("POST")
                .build();
        List<byte[]> messages = GrpcResponseBuilder.buildResponseMessages(entry, ctx, null);
        assertEquals(1, messages.size());
        assertArrayEquals("Hello POST!".getBytes(StandardCharsets.UTF_8), messages.get(0));
    }

    @Test
    public void buildResponseMessages_emptyMessagesFilteredOut() {
        ResponseEntry entry = new ResponseEntry();
        // Comma split produces "msg1", "", "msg3" — empty string should be filtered
        entry.setBody("msg1,,msg3");
        List<byte[]> messages = GrpcResponseBuilder.buildResponseMessages(entry, null, null);
        assertEquals(2, messages.size());
        assertArrayEquals("msg1".getBytes(StandardCharsets.UTF_8), messages.get(0));
        assertArrayEquals("msg3".getBytes(StandardCharsets.UTF_8), messages.get(1));
    }

    @Test
    public void buildResponseMessages_whitespaceOnlyBody_filteredToEmpty() {
        ResponseEntry entry = new ResponseEntry();
        entry.setBody("   ");
        List<byte[]> messages = GrpcResponseBuilder.buildResponseMessages(entry, null, null);
        assertEquals(1, messages.size());
        assertArrayEquals(new byte[0], messages.get(0));
    }

    // ---- findHeader (case-insensitive lookup) ----

    @Test
    public void buildResponseMessages_caseInsensitiveHeader_forStatus() {
        ResponseEntry entry = new ResponseEntry();
        entry.setHeaders(Collections.singletonMap("GRPC-STATUS", "5"));
        assertEquals(5, GrpcResponseBuilder.getGrpcStatus(entry));
    }

    @Test
    public void buildResponseMessages_caseInsensitiveHeader_forMessage() {
        ResponseEntry entry = new ResponseEntry();
        entry.setHeaders(Collections.singletonMap("Grpc-Message", "custom error"));
        assertEquals("custom error", GrpcResponseBuilder.getGrpcMessage(entry));
    }
}
