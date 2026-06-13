package com.baafoo.server.broker;

import com.baafoo.server.storage.StorageService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka Mock Broker — a lightweight Netty server that implements a subset
 * of the Kafka binary protocol.
 *
 * <p>Starts on port 9002 (configurable) and handles:
 * <ul>
 *   <li>Metadata API (API key 3) — returns partition info with this broker as leader</li>
 *   <li>Produce API (API key 0) — stores messages in memory, returns offset</li>
 *   <li>Fetch API (API key 1) — returns stored/preset messages</li>
 *   <li>ApiVersions API (API key 18) — returns supported version ranges</li>
 *   <li>All other APIs — return empty/default responses (no exceptions)</li>
 * </ul></p>
 *
 * <p>The Agent intercepts KafkaProducer/KafkaConsumer constructors and replaces
 * {@code bootstrap.servers} with this broker's address, so all Kafka traffic
 * from instrumented applications is routed here.</p>
 */
public class KafkaMockBroker {

    private static final Logger log = LoggerFactory.getLogger(KafkaMockBroker.class);

    private final int port;
    private final KafkaMessageStore messageStore;
    private final StorageService storage;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel serverChannel;

    public KafkaMockBroker(int port, StorageService storage, EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        this.port = port;
        this.storage = storage;
        this.messageStore = new KafkaMessageStore();
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
    }

    /**
     * Start the Kafka Mock Broker.
     */
    public void start() throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // Kafka protocol: 4-byte big-endian length prefix
                        p.addLast(new LengthFieldBasedFrameDecoder(
                                100 * 1024 * 1024, // maxFrameLength: 100MB (large produce batches)
                                0,                  // lengthFieldOffset
                                4,                  // lengthFieldLength
                                0,                  // lengthAdjustment
                                4                   // initialBytesToStrip: strip the length field
                        ));
                        p.addLast(new KafkaProtocolDecoder(messageStore, storage, port));
                    }
                });

        serverChannel = b.bind(port).sync().channel();
        log.info("Kafka Mock Broker started on port {}", port);
    }

    /**
     * Stop the Kafka Mock Broker.
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            log.info("Kafka Mock Broker stopped");
        }
        messageStore.clear();
    }

    /**
     * Get the message store (for testing or preset message injection).
     */
    public KafkaMessageStore getMessageStore() {
        return messageStore;
    }

    /**
     * Get the port this broker is listening on.
     */
    public int getPort() {
        return port;
    }
}
