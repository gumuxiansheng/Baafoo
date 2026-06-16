package com.baafoo.server.broker;

import com.baafoo.server.storage.StorageService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
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
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final PulsarMessageStore messageStore;
    private final StorageService storage;
    private final String advertisedHost;
    private Channel serverChannel;

    /** Cached broker host resolved from the first client connection. */
    private volatile String resolvedHost;

    public PulsarMockBroker(int port, EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                            StorageService storage, String advertisedHost) {
        this.port = port;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.storage = storage;
        this.advertisedHost = advertisedHost;
        this.messageStore = new PulsarMessageStore(storage);
    }

    /**
     * Resolve the broker host that is reachable from in-Docker clients.
     * Always auto-detects from the local hostname/IP.
     * The {@code advertisedHost} is only used for external clients (handled in the handler).
     */
    private String resolveBrokerHost() {
        if (resolvedHost != null) {
            return resolvedHost;
        }
        try {
            java.net.InetAddress addr = java.net.InetAddress.getLocalHost();
            String hostname = addr.getHostName();
            String ip = addr.getHostAddress();
            // Prefer hostname (in Docker, this is the container ID which is resolvable)
            // over IP if the IP is a loopback address
            if ("127.0.0.1".equals(ip) || "0.0.0.0".equals(ip)) {
                resolvedHost = hostname;
            } else {
                resolvedHost = ip;
            }
            log.info("Pulsar broker host resolved: {}", resolvedHost);
            return resolvedHost;
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * Start the Pulsar Mock Broker.
     */
    public void start() throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new PulsarFrameDecoder());
                        p.addLast(new PulsarMockBrokerHandler(messageStore, storage, resolveBrokerHost(), port, advertisedHost));
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
