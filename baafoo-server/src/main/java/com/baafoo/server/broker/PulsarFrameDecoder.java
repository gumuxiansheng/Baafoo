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

    /** Maximum frame size: 16 MB (same as Pulsar default maxMessageSize) */
    private static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // Need at least 8 bytes for the header
        if (in.readableBytes() < 8) {
            return;
        }

        in.markReaderIndex();

        int totalSize = in.readInt();
        int commandSize = in.readInt();

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

        PulsarFrame frame = new PulsarFrame();
        frame.command = cmd;
        frame.payload = payloadBytes;
        out.add(frame);
    }
}
