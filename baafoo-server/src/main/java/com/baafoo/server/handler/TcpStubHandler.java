package com.baafoo.server.handler;

import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.core.util.TemplateEngine;
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
import java.util.concurrent.TimeUnit;

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
    private final AgentResolver agentResolver;

    public TcpStubHandler(StorageService storage) {
        this.storage = storage;
        this.matchEngine = new MatchEngine();
        this.agentResolver = new AgentResolver(storage);
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

        // Resolve agent info (single pass over agent list)
        AgentResolver.AgentInfo agentInfo = agentResolver.resolveAll(ctx);
        String agentEnvironment = agentInfo.environment;
        String agentId = agentInfo.agentId;
        String agentIp = agentInfo.agentIp;

        // Match TCP rules
        List<Rule> rules = storage.listRules();
        List<Rule> filteredRules = agentResolver.filterRulesByEnvironment(rules, agentEnvironment);
        MatchEngine.MatchResult result = matchEngine.match(
                filteredRules, "tcp", "127.0.0.1", 0, null,
                null, null,
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                payload);

        if (result.isMatched()) {
            ResponseEntry entry = result.getResponse();
            EnvironmentMode currentMode = agentResolver.resolveEnvironmentMode(agentEnvironment);

            if (currentMode == EnvironmentMode.RECORD || currentMode == EnvironmentMode.RECORD_AND_STUB) {
                RecordingEntry rec = RecordingHelper.buildFromStub(
                        result, "tcp", "127.0.0.1", 0, null, null,
                        Collections.<String, String>emptyMap(), payload);
                rec.setAgentId(agentId);
                rec.setAgentIp(agentIp);
                storage.addRecording(rec);
            }

            sendTcpResponse(ctx, entry, payload);
        } else {
            log.debug("No TCP rule matched, closing connection");
            ctx.close();
        }
    }

    private void sendTcpResponse(ChannelHandlerContext ctx, ResponseEntry entry, String payload) {
        try {
            String rawBody = entry.getBody() != null ? entry.getBody() : "";
            String body = rawBody;
            if (rawBody.contains("{{")) {
                TemplateEngine.RequestContext templateCtx = new TemplateEngine.RequestContext(
                        null, null, null,
                        Collections.<String, String>emptyMap(),
                        Collections.<String, String>emptyMap(),
                        payload);
                body = TemplateEngine.render(rawBody, templateCtx);
            }

            String charsetName = entry.getCharset() != null && !entry.getCharset().isEmpty() ? entry.getCharset() : "UTF-8";
            java.nio.charset.Charset charset = java.nio.charset.Charset.forName(charsetName);
            ByteBuf response = Unpooled.copiedBuffer(body, charset);

            // Use scheduled executor for delay instead of blocking the EventLoop thread
            if (entry.getDelayMs() > 0) {
                ctx.executor().schedule(new Runnable() {
                    @Override
                    public void run() {
                        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                    }
                }, entry.getDelayMs(), TimeUnit.MILLISECONDS);
            } else {
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
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
