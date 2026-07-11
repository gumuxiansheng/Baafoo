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

    private int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final PulsarMessageStore messageStore;
    private final StorageService storage;
    private final String advertisedHost;
    private Channel serverChannel;
    private int actualPort;

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
                .option(ChannelOption.SO_REUSEADDR, true)
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
        this.actualPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
        log.info("Pulsar Mock Broker started on port {}", actualPort);

        // Self-connect verification: confirm the broker is actually accepting
        // TCP connections on the bound port. This converts a silent bind/accept
        // failure into a visible error so integration tests can tell the
        // difference between "broker not listening" and "protocol handshake stall".
        verifyListening(actualPort);
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
     * Verify the broker is actually accepting TCP connections on the given port.
     * Opens a short-lived socket to 127.0.0.1:port and closes it. A successful
     * connect proves the Netty acceptor is up; a failure is logged as an ERROR
     * (not thrown) so the broker still starts but the problem is visible.
     */
    private void verifyListening(int port) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            log.info("Pulsar Mock Broker self-connect OK on 127.0.0.1:{}", port);
        } catch (Exception e) {
            log.error("Pulsar Mock Broker self-connect FAILED on 127.0.0.1:{} — "
                    + "broker may not be reachable from clients: {}", port, e.getMessage());
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignore) { /* ignore */ }
            }
        }
    }

    /**
     * Get the message store (for testing or programmatic access).
     */
    public PulsarMessageStore getMessageStore() {
        return messageStore;
    }

    /**
     * Get the actual port this broker is listening on.
     * Supports port=0 (OS-assigned) by returning the bound port.
     */
    public int getPort() {
        return actualPort > 0 ? actualPort : port;
    }
}
