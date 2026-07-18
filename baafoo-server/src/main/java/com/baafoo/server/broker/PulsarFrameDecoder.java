package com.baafoo.server.broker;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Netty {@link ByteToMessageDecoder} that reads Pulsar binary protocol frames.
 *
 * <p>Pulsar frame format:
 * <pre>
 *   [4 bytes totalSize] — total frame size (excluding these 4 bytes)
 *   [4 bytes commandSize] — size of the protobuf command
 *   [commandSize bytes] — PulsarBaseCommand protobuf
 *   [totalSize - 4 - commandSize bytes] — payload (message body, if any)
 * </pre></p>
 */
class PulsarFrameDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(PulsarFrameDecoder.class);

    /**
     * Maximum frame size: 10 MB.
     * H-5: aligned with KafkaMockBroker / BaafooServer HttpObjectAggregator so
     * a single client cannot OOM the broker EventLoop. Real Pulsar frames are
     * well below this limit (Pulsar's default maxMessageSize is 1MB; even
     * batched payloads rarely exceed a few MB).
     */
    private static final int MAX_FRAME_SIZE = 10 * 1024 * 1024;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        log.debug("PulsarFrameDecoder added to pipeline: {}", ctx.channel());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("PulsarFrameDecoder channelActive: {}", ctx.channel().remoteAddress());
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // Need at least 8 bytes for the header
        if (in.readableBytes() < 8) {
            log.debug("Pulsar frame: waiting for header, readableBytes={}", in.readableBytes());
            return;
        }

        in.markReaderIndex();

        int totalSize = in.readInt();
        int commandSize = in.readInt();

        // M-8: per-frame decode path — debug, not info. Logging every frame
        // at INFO would flood the broker log under load (Pulsar clients ping
        // frequently and producers SEND on every message).
        if (log.isDebugEnabled()) {
            log.debug("Pulsar frame: totalSize={}, commandSize={}, readableBytes={}", totalSize, commandSize, in.readableBytes());
        }

        // Validate frame sizes
        if (totalSize < 4 || commandSize < 0 || commandSize > totalSize - 4) {
            log.error("Invalid Pulsar frame: totalSize={}, commandSize={}", totalSize, commandSize);
            ctx.close();
            return;
        }

        if (totalSize > MAX_FRAME_SIZE) {
            log.error("Pulsar frame too large: totalSize={} (max={})", totalSize, MAX_FRAME_SIZE);
            ctx.close();
            return;
        }

        int payloadSize = totalSize - 4 - commandSize;
        int frameBodySize = commandSize + payloadSize;

        if (in.readableBytes() < frameBodySize) {
            in.resetReaderIndex();
            return;
        }

        // Read command bytes
        byte[] commandBytes = new byte[commandSize];
        in.readBytes(commandBytes);

        // Read payload bytes (may be empty)
        byte[] payloadBytes = new byte[payloadSize];
        if (payloadSize > 0) {
            in.readBytes(payloadBytes);
        }

        // Parse the command
        PulsarCommand cmd = PulsarProtobufCodec.decodeCommand(commandBytes);

        // M-8: hex dump for protocol troubleshooting. Guarded by isDebugEnabled()
        // so the String.format loop is skipped entirely when debug logging is
        // disabled (avoiding per-frame allocation on the EventLoop). Capped at
        // 256 bytes (was 64) so streaming/batched commands are fully visible
        // when troubleshooting without unbounded log line length.
        if (log.isDebugEnabled()) {
            int dumpLen = Math.min(commandBytes.length, 256);
            StringBuilder hexDump = new StringBuilder(dumpLen * 3);
            for (int i = 0; i < dumpLen; i++) {
                hexDump.append(String.format("%02x ", commandBytes[i] & 0xFF));
            }
            log.debug("Pulsar command bytes (first {}): type={}, hex={}",
                    dumpLen, cmd.type, hexDump);
        }

        PulsarFrame frame = new PulsarFrame();
        frame.command = cmd;
        frame.payload = payloadBytes;
        out.add(frame);
    }
}
