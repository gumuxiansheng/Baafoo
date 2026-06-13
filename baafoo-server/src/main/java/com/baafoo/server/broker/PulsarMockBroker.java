package com.baafoo.server.broker;

import com.baafoo.server.storage.StorageService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulsar Mock Broker — a Netty server that implements a subset of the
 * Apache Pulsar binary protocol for testing purposes.
 *
 * <p>Listens on a configurable port (default 9003) and handles:
 * <ul>
 *   <li>CONNECT/CONNECTED handshake</li>
 *   <li>Topic LOOKUP (returns self address)</li>
 *   <li>PRODUCER creation and SEND (stores messages, returns SEND_RECEIPT)</li>
 *   <li>SUBSCRIBE and MESSAGE delivery (sends preset/stored messages)</li>
 *   <li>PARTITIONED_METADATA (returns non-partitioned)</li>
 *   <li>GET_TOPICS_OF_NAMESPACE (returns topics from rules)</li>
 * </ul></p>
 *
 * <p>The Agent already intercepts PulsarClient builder and replaces serviceUrl
 * with {@code pulsar://SERVER_HOST:9003}, so all Pulsar traffic from
 * instrumented applications is routed to this broker.</p>
 */
public class PulsarMockBroker {

    private static final Logger log = LoggerFactory.getLogger(PulsarMockBroker.class);

    private final int port;
    private final String host;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final PulsarMessageStore messageStore;
    private Channel serverChannel;

    public PulsarMockBroker(int port, EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                            StorageService storage) {
        this.port = port;
        this.host = "localhost";
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.messageStore = new PulsarMessageStore(storage);
    }

    /**
     * Start the Pulsar Mock Broker.
     */
    public void start() throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new PulsarFrameDecoder());
                        p.addLast(new PulsarMockBrokerHandler(messageStore, host, port));
                    }
                });

        serverChannel = b.bind(port).sync().channel();
        log.info("Pulsar Mock Broker started on port {}", port);
    }

    /**
     * Stop the Pulsar Mock Broker.
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            log.info("Pulsar Mock Broker stopped");
        }
    }

    /**
     * Get the message store (for testing or programmatic access).
     */
    public PulsarMessageStore getMessageStore() {
        return messageStore;
    }
}
