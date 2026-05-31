package com.baafoo.server.bootstrap;

import com.baafoo.core.config.ConfigLoader;
import com.baafoo.core.config.ServerConfig;
import com.baafoo.server.api.*;
import com.baafoo.server.handler.HttpStubHandler;
import com.baafoo.server.handler.TcpStubHandler;
import com.baafoo.server.storage.StorageService;
import com.baafoo.server.storage.H2StorageService;
import com.baafoo.server.web.StaticFileHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Baafoo Server main bootstrap.
 *
 * <p>Starts multiple Netty servers:
 * <ul>
 *   <li>HTTP Management API + Web Console (port from config, default 8080)</li>
 *   <li>HTTP Stub (port 9000)</li>
 *   <li>TCP Stub (port 9001)</li>
 *   <li>Kafka Stub (port 9002) — Beta</li>
 *   <li>Pulsar Stub (port 9003) — Beta</li>
 *   <li>JMS Stub (port 9004) — Beta</li>
 * </ul></p>
 */
public class BaafooServer {

    private static final Logger log = LoggerFactory.getLogger(BaafooServer.class);

    private final ServerConfig config;
    private final StorageService storage;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final List<Channel> channels;

    public BaafooServer(ServerConfig config) {
        this.config = config;
        this.storage = new H2StorageService(config);
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.channels = new ArrayList<Channel>();
    }

    /**
     * Start all servers.
     */
    public void start() throws Exception {
        log.info("=== Baafoo Server starting ===");

        // Initialize storage
        storage.init();

        // Start HTTP management server (API + Web console)
        startManagementServer();

        // Start protocol stub servers
        Integer httpPort = config.getPortForProtocol("http");
        if (httpPort > 0) startHttpStubServer(httpPort);

        Integer tcpPort = config.getPortForProtocol("tcp");
        if (tcpPort > 0) startTcpStubServer(tcpPort);

        // Protocol-specific stub servers (Kafka, Pulsar, JMS — Beta)
        startProtocolServers();

        log.info("=== Baafoo Server started ===");
        log.info("Management API: http://localhost:{}", config.getHttpPort());
        log.info("Stub HTTP:      localhost:{}", httpPort);
        log.info("Stub TCP:       localhost:{}", tcpPort);
        log.info("Web Console:    http://localhost:{}/__baafoo__/", config.getHttpPort());

        // Wait for shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                stop();
            }
        }));
    }

    private void startManagementServer() throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(new ManagementApiHandler(storage));
                        p.addLast(new StaticFileHandler(config.getWebConsolePath()));
                    }
                });

        Channel ch = b.bind(config.getHttpPort()).sync().channel();
        channels.add(ch);
    }

    private void startHttpStubServer(int port) throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(new HttpStubHandler(storage, config));
                    }
                });

        Channel ch = b.bind(port).sync().channel();
        channels.add(ch);
    }

    private void startTcpStubServer(int port) throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new TcpStubHandler(storage));
                    }
                });

        Channel ch = b.bind(port).sync().channel();
        channels.add(ch);
    }

    private void startProtocolServers() throws Exception {
        // Kafka stub
        Integer kafkaPort = config.getPortForProtocol("kafka");
        if (kafkaPort > 0) {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new TcpStubHandler(storage));
                        }
                    });
            Channel ch = b.bind(kafkaPort).sync().channel();
            channels.add(ch);
            log.info("Kafka stub (Beta) on port {}", kafkaPort);
        }

        // Pulsar stub
        Integer pulsarPort = config.getPortForProtocol("pulsar");
        if (pulsarPort > 0) {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new TcpStubHandler(storage));
                        }
                    });
            Channel ch = b.bind(pulsarPort).sync().channel();
            channels.add(ch);
            log.info("Pulsar stub (Beta) on port {}", pulsarPort);
        }

        // JMS stub
        Integer jmsPort = config.getPortForProtocol("jms");
        if (jmsPort > 0) {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new TcpStubHandler(storage));
                        }
                    });
            Channel ch = b.bind(jmsPort).sync().channel();
            channels.add(ch);
            log.info("JMS stub (Beta) on port {}", jmsPort);
        }
    }

    private void stop() {
        log.info("Shutting down Baafoo Server...");
        storage.shutdown();
        for (Channel ch : channels) {
            ch.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        log.info("Baafoo Server stopped");
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "./baafoo-server.yml";
        ServerConfig config = ConfigLoader.loadServerConfig(configPath);
        new BaafooServer(config).start();

        // Keep running until interrupted
        Thread.currentThread().join();
    }
}
