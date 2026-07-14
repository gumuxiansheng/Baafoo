package com.baafoo.server.handler;

import com.baafoo.core.model.*;
import com.baafoo.server.storage.AgentRegistration;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TcpStubHandlerTest {

    @org.junit.Rule
    public Timeout globalTimeout = new Timeout(30, TimeUnit.SECONDS);

    private StorageService storage;

    @Before
    public void setUp() {
        storage = mock(StorageService.class);
    }

    private EmbeddedChannel createChannel() {
        TcpStubHandler handler = new TcpStubHandler(storage, null);
        return new EmbeddedChannel(handler);
    }

    private void setupAgentAndEnv(String envName) {
        setupAgentAndEnv(envName, EnvironmentMode.STUB);
    }

    private void setupAgentAndEnv(String envName, EnvironmentMode mode) {
        Environment env = new Environment();
        env.setName(envName);
        env.setMode(mode);

        AgentRegistration agentReg = new AgentRegistration();
        agentReg.agentId = "test-agent";
        agentReg.environment = envName;
        agentReg.agentIp = "127.0.0.1";
        agentReg.lastHeartbeat = System.currentTimeMillis();

        when(storage.listEnvironments()).thenReturn(Arrays.asList(env));
        when(storage.listAgents()).thenReturn(Arrays.asList(agentReg));
    }

    // --- Existing tests (adapted) ---

    @Test
    public void testNoMatchClosesConnection() {
        when(storage.listRules()).thenReturn(new ArrayList<Rule>());
        setupAgentAndEnv("test-env");

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("test payload", StandardCharsets.UTF_8));
        assertFalse(channel.isOpen());
    }

    @Test
    public void testMatchedRuleReturnsResponse() {
        Rule rule = new Rule();
        rule.setId("r1");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("stub-response");
        resp.setStatusCode(200);
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("test payload", StandardCharsets.UTF_8));
        Object out = channel.readOutbound();
        assertNotNull(out);
    }

    @Test
    public void testExceptionCaughtClosesChannel() {
        EmbeddedChannel channel = createChannel();
        channel.pipeline().fireExceptionCaught(new RuntimeException("test error"));
        assertFalse(channel.isOpen());
    }

    // --- R-S3 AC-02: Regex pattern matching ---

    @Test
    public void testTcpPatternMatchesHexPayload() {
        // "hello" in hex is "68656c6c6f"
        Rule rule = new Rule();
        rule.setId("r-regex");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpPattern("68656c6c6f"); // regex matching hex of "hello"

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("matched-hello");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        assertEquals("matched-hello", new String(outBytes, StandardCharsets.UTF_8));
        assertFalse(channel.isOpen()); // single-round closes
    }

    @Test
    public void testTcpPatternDoesNotMatch() {
        Rule rule = new Rule();
        rule.setId("r-regex-nomatch");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpPattern("abcdef"); // won't match "hello" hex

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("should-not-match");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        // Should fall through to MatchEngine (no match) and close
        assertFalse(channel.isOpen());
    }

    @Test
    public void testTcpPatternWithRegexWildcard() {
        // Match any payload starting with "he" (hex 6865)
        Rule rule = new Rule();
        rule.setId("r-regex-wild");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpPattern("6865.*");

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("starts-with-he");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        assertEquals("starts-with-he", new String(outBytes, StandardCharsets.UTF_8));
    }

    // --- R-S3 AC-05: Offset matching ---

    @Test
    public void testTcpOffsetMatchesBytes() {
        // Create a rule that matches bytes at offset 4-5 = 0x0001
        Rule rule = new Rule();
        rule.setId("r-offset");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpOffsetStart(4);
        rule.setTcpOffsetEnd(5);
        rule.setTcpOffsetHex("01"); // byte at offset 4 = 0x01

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("offset-matched");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        // Build payload: 4 bytes + 0x01 + rest
        byte[] data = new byte[]{0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03};
        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer(data));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        assertEquals("offset-matched", new String(outBytes, StandardCharsets.UTF_8));
    }

    @Test
    public void testTcpOffsetDoesNotMatch() {
        Rule rule = new Rule();
        rule.setId("r-offset-nomatch");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpOffsetStart(4);
        rule.setTcpOffsetEnd(5);
        rule.setTcpOffsetHex("02"); // expecting 0x02 at offset 4

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("should-not-match");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        // Byte at offset 4 is 0x01, not 0x02
        byte[] data = new byte[]{0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03};
        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer(data));
        assertFalse(channel.isOpen());
    }

    @Test
    public void testTcpOffsetMultiByteRange() {
        // Match bytes 4-6 (2 bytes) = 0x0001
        Rule rule = new Rule();
        rule.setId("r-offset-multi");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpOffsetStart(4);
        rule.setTcpOffsetEnd(6);
        rule.setTcpOffsetHex("0001");

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("offset-multi-matched");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        byte[] data = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03};
        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer(data));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        assertEquals("offset-multi-matched", new String(outBytes, StandardCharsets.UTF_8));
    }

    @Test
    public void testTcpOffsetPayloadTooShort() {
        Rule rule = new Rule();
        rule.setId("r-offset-short");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpOffsetStart(4);
        rule.setTcpOffsetEnd(6);
        rule.setTcpOffsetHex("0001");

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("should-not-match");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        // Only 3 bytes, offset 4-6 is out of range
        byte[] data = new byte[]{0x00, 0x00, 0x00};
        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer(data));
        assertFalse(channel.isOpen());
    }

    // --- Prefix hex matching ---

    @Test
    public void testTcpPrefixHexMatches() {
        Rule rule = new Rule();
        rule.setId("r-prefix");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpPrefixHex("68656c6c6f"); // "hello" in hex

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("prefix-matched");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello world", StandardCharsets.UTF_8));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        assertEquals("prefix-matched", new String(outBytes, StandardCharsets.UTF_8));
    }

    @Test
    public void testTcpPrefixHexDoesNotMatch() {
        Rule rule = new Rule();
        rule.setId("r-prefix-nomatch");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpPrefixHex("abcdef"); // won't match "hello"

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("should-not-match");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        assertFalse(channel.isOpen());
    }

    // --- R-S3 AC-03: Multi-round interaction ---

    @Test
    public void testMultiRoundTwoRounds() {
        Rule rule = new Rule();
        rule.setId("r-multi");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));

        // Round 1: wildcard match (no pattern = match anything)
        TcpRound round1 = new TcpRound();
        round1.setName("handshake");
        ResponseEntry resp1 = new ResponseEntry();
        resp1.setBody("handshake-ok");
        round1.setResponse(resp1);

        // Round 2: match "world" (hex 776f726c64)
        TcpRound round2 = new TcpRound();
        round2.setName("data");
        round2.setPattern("776f726c64");
        ResponseEntry resp2 = new ResponseEntry();
        resp2.setBody("data-ok");
        round2.setResponse(resp2);

        rule.setTcpRounds(Arrays.asList(round1, round2));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();

        // Round 1: send "hello"
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out1 = channel.readOutbound();
        assertNotNull("Round 1 should produce output", out1);
        byte[] out1Bytes = new byte[out1.readableBytes()];
        out1.readBytes(out1Bytes);
        assertEquals("handshake-ok", new String(out1Bytes, StandardCharsets.UTF_8));
        assertTrue("Channel should stay open for round 2", channel.isOpen());

        // Round 2: send "world"
        channel.writeInbound(Unpooled.copiedBuffer("world", StandardCharsets.UTF_8));
        ByteBuf out2 = channel.readOutbound();
        assertNotNull("Round 2 should produce output", out2);
        byte[] out2Bytes = new byte[out2.readableBytes()];
        out2.readBytes(out2Bytes);
        assertEquals("data-ok", new String(out2Bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void testMultiRoundClosesAfterLastRound() {
        Rule rule = new Rule();
        rule.setId("r-multi-close");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpLoop(false);

        TcpRound round1 = new TcpRound();
        round1.setName("only");
        ResponseEntry resp1 = new ResponseEntry();
        resp1.setBody("done");
        round1.setResponse(resp1);

        rule.setTcpRounds(Arrays.asList(round1));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();

        // Round 1: send anything
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out1 = channel.readOutbound();
        assertNotNull(out1);

        // After last round with no loop, next request should close
        // Actually, the last round response itself closes the connection
        // because isLastRound=true and tcpLoop=false
        // Let's verify: after round 1 (the only round), connection should close
        assertFalse("Channel should close after last round (no loop)", channel.isOpen());
    }

    @Test
    public void testMultiRoundLoop() {
        Rule rule = new Rule();
        rule.setId("r-multi-loop");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpLoop(true);

        TcpRound round1 = new TcpRound();
        round1.setName("greeting");
        ResponseEntry resp1 = new ResponseEntry();
        resp1.setBody("hi");
        round1.setResponse(resp1);

        TcpRound round2 = new TcpRound();
        round2.setName("ack");
        ResponseEntry resp2 = new ResponseEntry();
        resp2.setBody("ack");
        round2.setResponse(resp2);

        rule.setTcpRounds(Arrays.asList(round1, round2));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();

        // Round 1
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out1 = channel.readOutbound();
        assertNotNull(out1);
        byte[] b1 = new byte[out1.readableBytes()];
        out1.readBytes(b1);
        assertEquals("hi", new String(b1, StandardCharsets.UTF_8));

        // Round 2
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out2 = channel.readOutbound();
        assertNotNull(out2);
        byte[] b2 = new byte[out2.readableBytes()];
        out2.readBytes(b2);
        assertEquals("ack", new String(b2, StandardCharsets.UTF_8));

        // Round 1 again (loop)
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out3 = channel.readOutbound();
        assertNotNull(out3);
        byte[] b3 = new byte[out3.readableBytes()];
        out3.readBytes(b3);
        assertEquals("hi", new String(b3, StandardCharsets.UTF_8));
        assertTrue("Channel should stay open with loop", channel.isOpen());
    }

    @Test
    public void testMultiRoundMismatchClosesConnection() {
        Rule rule = new Rule();
        rule.setId("r-multi-mismatch");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));

        TcpRound round1 = new TcpRound();
        round1.setName("handshake");
        ResponseEntry resp1 = new ResponseEntry();
        resp1.setBody("handshake-ok");
        round1.setResponse(resp1);

        // Round 2 requires specific pattern
        TcpRound round2 = new TcpRound();
        round2.setName("data");
        round2.setPattern("abcdef"); // won't match "hello" hex
        ResponseEntry resp2 = new ResponseEntry();
        resp2.setBody("data-ok");
        round2.setResponse(resp2);

        rule.setTcpRounds(Arrays.asList(round1, round2));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();

        // Round 1: wildcard match
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out1 = channel.readOutbound();
        assertNotNull(out1);

        // Round 2: pattern doesn't match
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        assertFalse("Channel should close on round mismatch", channel.isOpen());
    }

    // --- Multi-round with offset matching ---

    @Test
    public void testMultiRoundWithOffsetMatching() {
        Rule rule = new Rule();
        rule.setId("r-multi-offset");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));

        // Round 1: wildcard
        TcpRound round1 = new TcpRound();
        round1.setName("connect");
        ResponseEntry resp1 = new ResponseEntry();
        resp1.setBody("connected");
        round1.setResponse(resp1);

        // Round 2: offset matching - bytes 0-1 = 0x0001 (success)
        TcpRound round2 = new TcpRound();
        round2.setName("command");
        round2.setOffsetStart(0);
        round2.setOffsetEnd(2);
        round2.setOffsetHex("0001");
        ResponseEntry resp2 = new ResponseEntry();
        resp2.setBody("success");
        round2.setResponse(resp2);

        rule.setTcpRounds(Arrays.asList(round1, round2));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();

        // Round 1: send anything
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out1 = channel.readOutbound();
        assertNotNull(out1);

        // Round 2: send bytes starting with 0x00 0x01
        byte[] data = new byte[]{0x00, 0x01, 0x02, 0x03};
        channel.writeInbound(Unpooled.copiedBuffer(data));
        ByteBuf out2 = channel.readOutbound();
        assertNotNull(out2);
        byte[] b2 = new byte[out2.readableBytes()];
        out2.readBytes(b2);
        assertEquals("success", new String(b2, StandardCharsets.UTF_8));
    }

    // --- Combined pattern + offset (AND logic) ---

    @Test
    public void testTcpPatternAndOffsetBothMustMatch() {
        Rule rule = new Rule();
        rule.setId("r-combined");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        // Pattern matches hex starting with "00"
        rule.setTcpPattern("00.*");
        // Offset at bytes 2-3 must be 0x0102
        rule.setTcpOffsetStart(2);
        rule.setTcpOffsetEnd(4);
        rule.setTcpOffsetHex("0102");

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("combined-match");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        // Both match: starts with 00 and bytes 2-3 = 0102
        byte[] data = new byte[]{0x00, 0x00, 0x01, 0x02, 0x03};
        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer(data));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        assertEquals("combined-match", new String(outBytes, StandardCharsets.UTF_8));
    }

    @Test
    public void testTcpPatternMatchesButOffsetDoesNot() {
        Rule rule = new Rule();
        rule.setId("r-combined-fail");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpPattern("00.*");
        rule.setTcpOffsetStart(2);
        rule.setTcpOffsetEnd(4);
        rule.setTcpOffsetHex("ffff"); // won't match

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("should-not-match");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        byte[] data = new byte[]{0x00, 0x00, 0x01, 0x02, 0x03};
        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer(data));
        assertFalse(channel.isOpen());
    }

    // --- Round with prefixHex matching ---

    @Test
    public void testMultiRoundWithPrefixHex() {
        Rule rule = new Rule();
        rule.setId("r-round-prefix");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));

        // Round 1: prefix hex = "68656c6c6f" ("hello")
        TcpRound round1 = new TcpRound();
        round1.setName("hello-round");
        round1.setPrefixHex("68656c6c6f");
        ResponseEntry resp1 = new ResponseEntry();
        resp1.setBody("hello-response");
        round1.setResponse(resp1);

        // Round 2: prefix hex = "776f726c64" ("world")
        TcpRound round2 = new TcpRound();
        round2.setName("world-round");
        round2.setPrefixHex("776f726c64");
        ResponseEntry resp2 = new ResponseEntry();
        resp2.setBody("world-response");
        round2.setResponse(resp2);

        rule.setTcpRounds(Arrays.asList(round1, round2));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();

        // Round 1: send "hello"
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out1 = channel.readOutbound();
        assertNotNull(out1);
        byte[] b1 = new byte[out1.readableBytes()];
        out1.readBytes(b1);
        assertEquals("hello-response", new String(b1, StandardCharsets.UTF_8));

        // Round 2: send "world"
        channel.writeInbound(Unpooled.copiedBuffer("world", StandardCharsets.UTF_8));
        ByteBuf out2 = channel.readOutbound();
        assertNotNull(out2);
        byte[] b2 = new byte[out2.readableBytes()];
        out2.readBytes(b2);
        assertEquals("world-response", new String(b2, StandardCharsets.UTF_8));
    }

    // --- Fallback to MatchEngine for rules without TCP-specific matchers ---

    @Test
    public void testFallbackToMatchEngine() {
        // Rule with no TCP-specific fields, uses body condition via MatchEngine
        Rule rule = new Rule();
        rule.setId("r-fallback");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setConditions(Arrays.asList(MatchCondition.body("contains", "hello")));

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("fallback-response");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello world", StandardCharsets.UTF_8));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        assertEquals("fallback-response", new String(outBytes, StandardCharsets.UTF_8));
    }

    // --- Recording ---

    @Test
    public void testRecordsSingleRoundInRecordMode() {
        Rule rule = new Rule();
        rule.setId("r-record");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("record-env"));
        rule.setTcpPrefixHex("68656c6c6f"); // "hello"

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("recorded-response");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("record-env", EnvironmentMode.RECORD_AND_STUB);
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello world", StandardCharsets.UTF_8));

        org.mockito.ArgumentCaptor<RecordingEntry> captor =
                org.mockito.ArgumentCaptor.forClass(RecordingEntry.class);
        verify(storage).addRecording(captor.capture());

        RecordingEntry rec = captor.getValue();
        assertEquals("tcp", rec.getProtocol());
        assertEquals("record-env", rec.getEnvironmentId());
        assertEquals("test-agent", rec.getAgentId());
        assertEquals("127.0.0.1", rec.getAgentIp());
        assertEquals("hello world", rec.getRequestBody());
    }

    @Test
    public void testRecordsMultiRoundInRecordMode() {
        Rule rule = new Rule();
        rule.setId("r-record-multi");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("record-env"));
        rule.setTcpLoop(true);

        TcpRound round1 = new TcpRound();
        round1.setName("auth");
        round1.setPattern(".*61757468.*"); // hex of "auth"
        ResponseEntry resp1 = new ResponseEntry();
        resp1.setBody("auth-ok");
        round1.setResponse(resp1);

        TcpRound round2 = new TcpRound();
        round2.setName("data");
        round2.setPattern(".*64617461.*"); // hex of "data"
        ResponseEntry resp2 = new ResponseEntry();
        resp2.setBody("data-ok");
        round2.setResponse(resp2);

        rule.setTcpRounds(Arrays.asList(round1, round2));

        setupAgentAndEnv("record-env", EnvironmentMode.RECORD);
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("auth-request", StandardCharsets.UTF_8));
        channel.readOutbound();
        channel.writeInbound(Unpooled.copiedBuffer("data-request", StandardCharsets.UTF_8));
        channel.readOutbound();

        org.mockito.ArgumentCaptor<RecordingEntry> captor =
                org.mockito.ArgumentCaptor.forClass(RecordingEntry.class);
        verify(storage, times(2)).addRecording(captor.capture());

        List<RecordingEntry> recordings = captor.getAllValues();
        assertEquals("auth-request", recordings.get(0).getRequestBody());
        assertEquals("data-request", recordings.get(1).getRequestBody());
        for (RecordingEntry rec : recordings) {
            assertEquals("tcp", rec.getProtocol());
            assertEquals("record-env", rec.getEnvironmentId());
        }
    }

    @Test
    public void testDoesNotRecordInStubMode() {
        Rule rule = new Rule();
        rule.setId("r-no-record");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("stub-env"));
        rule.setTcpPrefixHex("68656c6c6f");

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("stub-response");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("stub-env", EnvironmentMode.STUB);
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello world", StandardCharsets.UTF_8));

        verify(storage, never()).addRecording(any(RecordingEntry.class));
    }

    @Test
    public void testRecordsUnmatchedInRecordAllMode() {
        when(storage.listRules()).thenReturn(new ArrayList<Rule>());
        setupAgentAndEnv("record-all-env", EnvironmentMode.RECORD_ALL);

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("test payload", StandardCharsets.UTF_8));

        verify(storage).addRecording(any(RecordingEntry.class));
    }

    @Test
    public void testMatchEngineFallbackRecordsInRecordMode() {
        Rule rule = new Rule();
        rule.setId("r-fallback-record");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("record-env"));
        rule.setConditions(Arrays.asList(MatchCondition.body("contains", "hello")));

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("recorded-response");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("record-env", EnvironmentMode.RECORD);
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello world", StandardCharsets.UTF_8));

        verify(storage).addRecording(any(RecordingEntry.class));
    }

    @Test
    public void testMatchEngineFallbackDoesNotRecordInStubMode() {
        Rule rule = new Rule();
        rule.setId("r-fallback-stub");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("stub-env"));
        rule.setConditions(Arrays.asList(MatchCondition.body("contains", "hello")));

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("stub-response");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("stub-env", EnvironmentMode.STUB);
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello world", StandardCharsets.UTF_8));

        verify(storage, never()).addRecording(any(RecordingEntry.class));
    }

    // --- Multi-charset (GBK) support ---

    /**
     * Response with charset=GBK should be encoded in GBK bytes, not UTF-8.
     */
    @Test
    public void testResponseCharsetGBK() {
        Rule rule = new Rule();
        rule.setId("r-gbk-resp");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setTcpPrefixHex("68656c6c6f"); // "hello" in hex

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("你好世界"); // Chinese text
        resp.setCharset("GBK");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        // Decode with GBK to verify the response was GBK-encoded
        String decoded = new String(outBytes, java.nio.charset.Charset.forName("GBK"));
        assertEquals("你好世界", decoded);
    }

    /**
     * When rule.requestCharset=GBK and the client sends GBK bytes, the handler
     * should re-decode the payload using GBK after hex-based matching succeeds,
     * so that template variables like {{request.body}} render correct text.
     */
    @Test
    public void testRequestCharsetGBKWithTemplate() {
        Rule rule = new Rule();
        rule.setId("r-gbk-req");
        rule.setProtocol("tcp");
        rule.setEnabled(true);
        rule.setEnvironments(Arrays.asList("test-env"));
        rule.setRequestCharset("GBK");

        // Use hex prefix matching to avoid body-text matching issues with GBK bytes.
        // "你好" in GBK is "c4e3bac3" (4 bytes).
        rule.setTcpPrefixHex("c4e3bac3");

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("echo:{{request.body}}");
        resp.setCharset("GBK");
        rule.setResponses(Arrays.asList(resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(rule));

        byte[] gbkBytes = "你好".getBytes(java.nio.charset.Charset.forName("GBK"));
        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer(gbkBytes));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        // The template should render the GBK-decoded body, then encode the result in GBK
        String decoded = new String(outBytes, java.nio.charset.Charset.forName("GBK"));
        assertEquals("echo:你好", decoded);
    }

    /**
     * Response with default charset (null/UTF-8) should still work correctly
     * alongside GBK rules — no regression.
     */
    @Test
    public void testResponseDefaultCharsetWithGBKRulePresent() {
        Rule gbkRule = new Rule();
        gbkRule.setId("r-gbk-other");
        gbkRule.setProtocol("tcp");
        gbkRule.setEnabled(true);
        gbkRule.setEnvironments(Arrays.asList("other-env"));
        gbkRule.setTcpPrefixHex("abcdef");
        ResponseEntry gbkResp = new ResponseEntry();
        gbkResp.setBody("GBK-rule");
        gbkResp.setCharset("GBK");
        gbkRule.setResponses(Arrays.asList(gbkResp));

        Rule utf8Rule = new Rule();
        utf8Rule.setId("r-utf8");
        utf8Rule.setProtocol("tcp");
        utf8Rule.setEnabled(true);
        utf8Rule.setEnvironments(Arrays.asList("test-env"));
        utf8Rule.setTcpPrefixHex("68656c6c6f"); // "hello"

        ResponseEntry utf8Resp = new ResponseEntry();
        utf8Resp.setBody("utf8-response");
        utf8Rule.setResponses(Arrays.asList(utf8Resp));

        setupAgentAndEnv("test-env");
        when(storage.listRules()).thenReturn(Arrays.asList(gbkRule, utf8Rule));

        EmbeddedChannel channel = createChannel();
        channel.writeInbound(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));
        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        assertEquals("utf8-response", new String(outBytes, StandardCharsets.UTF_8));
    }
}
