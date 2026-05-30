package com.baafoo.server.handler;

import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Netty handler for TCP stub server (port 9001+).
 *
 * <p>Handles raw TCP connections relayed from the agent.
 * Matches TCP rules and returns pre-configured responses.</p>
 */
public class TcpStubHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(TcpStubHandler.class);

    private final StorageService storage;
    private final MatchEngine matchEngine;

    public TcpStubHandler(StorageService storage) {
        this.storage = storage;
        this.matchEngine = new MatchEngine();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("TCP stub connection: {}", ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        byte[] data = new byte[msg.readableBytes()];
        msg.readBytes(data);
        String payload = new String(data, StandardCharsets.UTF_8);

        // Match TCP rules
        List<Rule> rules = storage.listRules();
        MatchEngine.MatchResult result = matchEngine.match(
                rules, "tcp", "127.0.0.1", 0, null,
                null, null,
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                payload);

        if (result.isMatched()) {
            ResponseEntry entry = result.getResponse();

            RecordingEntry rec = new RecordingEntry();
            rec.setRuleId(result.getRule().getId());
            rec.setProtocol("tcp");
            rec.setRequestBody(payload);
            rec.setResponseStatusCode(entry.getStatusCode());
            rec.setResponseBody(entry.getBody());
            rec.setResponseTimeMs(entry.getDelayMs());
            storage.addRecording(rec);

            sendTcpResponse(ctx, entry);
        } else {
            // TCP unmatched = close connection
            log.debug("No TCP rule matched, closing connection");
            ctx.close();
        }
    }

    private void sendTcpResponse(ChannelHandlerContext ctx, ResponseEntry entry) {
        try {
            if (entry.getDelayMs() > 0) {
                Thread.sleep(entry.getDelayMs());
            }

            String body = entry.getBody() != null ? entry.getBody() : "";
            ByteBuf response = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            log.debug("TCP stub response: {} bytes", body.length());
        } catch (Exception e) {
            log.error("Error sending TCP response: {}", e.getMessage());
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("TcpStubHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
