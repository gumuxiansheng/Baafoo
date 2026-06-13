package com.baafoo.server.bootstrap;

import com.baafoo.core.config.ConfigLoader;
import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.User;
import com.baafoo.server.api.*;
import com.baafoo.server.auth.AuthFilter;
import com.baafoo.server.auth.AuthService;
import com.baafoo.server.broker.JmsMockBroker;
import com.baafoo.server.broker.KafkaMockBroker;
import com.baafoo.server.broker.PulsarMockBroker;
import com.baafoo.server.handler.HttpStubHandler;
import com.baafoo.server.handler.TcpStubHandler;
import com.baafoo.server.storage.RecordingCleanupTask;
import com.baafoo.server.storage.StorageService;
import com.baafoo.server.storage.StorageServiceFactory;
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
 *   <li>HTTP Management API + Web Console (port from config, default 8084)</li>
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
    private final AuthService authService;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final List<Channel> channels;
    private KafkaMockBroker kafkaBroker;
    private PulsarMockBroker pulsarBroker;
    private JmsMockBroker jmsBroker;
    private RecordingCleanupTask recordingCleanupTask;

    public BaafooServer(ServerConfig config) {
        this.config = config;
        this.storage = StorageServiceFactory.create(config);
        this.authService = createAuthService(config, storage);
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.channels = new ArrayList<Channel>();
    }

    private AuthService createAuthService(ServerConfig config, StorageService storage) {
        ServerConfig.AuthConfig authConfig = config.getAuth();
        if (authConfig == null) {
            authConfig = new ServerConfig.AuthConfig();
        }
        return new AuthService(
                storage,
                authConfig.getJwtSecret(),
                authConfig.isEnabled(),
                authConfig.isLocalBypass(),
                authConfig.getApiKeys()
        );
    }

    /**
     * Start all servers.
     */
    public void start() throws Exception {
        log.info("=== Baafoo Server starting ===");

        // Initialize storage
        storage.init();

        // Start recording cleanup task
        recordingCleanupTask = new RecordingCleanupTask(storage, config);
        recordingCleanupTask.start();

        // Initialize default admin user if auth is enabled
        ensureDefaultAdmin();

        // Start HTTP management server (API + Web console)
        startManagementServer();

        // Start protocol stub servers
        Integer httpPort = config.getPortForProtocol("http");
        if (httpPort > 0) startHttpStubServer(httpPort);

        Integer tcpPort = config.getPortForProtocol("tcp");
        if (tcpPort > 0) startTcpStubServer(tcpPort);

        Integer kafkaPort = config.getPortForProtocol("kafka");
        Integer pulsarPort = config.getPortForProtocol("pulsar");
        Integer jmsPort = config.getPortForProtocol("jms");

        // Protocol-specific stub servers (Kafka, Pulsar, JMS — Beta)
        startProtocolServers();

        log.info("=== Baafoo Server started ===");
        log.info("Management API: http://localhost:{}", config.getHttpPort());
        log.info("Stub HTTP:      localhost:{}", httpPort);
        log.info("Stub TCP:       localhost:{}", tcpPort);
        log.info("Stub Kafka:     localhost:{}", kafkaPort);
        log.info("Stub Pulsar:    localhost:{}", pulsarPort);
        log.info("Stub JMS:       localhost:{}", jmsPort);
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
                        p.addLast(new AuthFilter(authService));
                        p.addLast(new ManagementApiHandler(storage, authService));
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
                        p.addLast(new HttpStubHandler(storage, config, workerGroup));
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

    private void startProtocolStubServer(String protocol, int port) throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // Beta: Kafka/Pulsar/JMS currently share TcpStubHandler for basic connectivity.
                        // Protocol-specific handlers (e.g., KafkaStubHandler) will be needed for
                        // proper application-layer protocol frame parsing in a future release.
                        ch.pipeline().addLast(new TcpStubHandler(storage));
                    }
                });
        Channel ch = b.bind(port).sync().channel();
        channels.add(ch);
        log.info("{} stub (Beta) on port {}", protocol, port);
    }

    private void startProtocolServers() throws Exception {
        // Kafka uses the dedicated KafkaMockBroker with binary protocol parsing
        Integer kafkaPort = config.getPortForProtocol("kafka");
        if (kafkaPort > 0) {
            kafkaBroker = new KafkaMockBroker(kafkaPort, storage, bossGroup, workerGroup);
            kafkaBroker.start();
        }

        // Pulsar uses the dedicated PulsarMockBroker with binary protocol parsing
        Integer pulsarPort = config.getPortForProtocol("pulsar");
        if (pulsarPort > 0) {
            pulsarBroker = new PulsarMockBroker(pulsarPort, bossGroup, workerGroup, storage);
            pulsarBroker.start();
        }

        // JMS uses the embedded Artemis broker with OpenWire protocol support
        Integer jmsPort = config.getPortForProtocol("jms");
        if (jmsPort != null && jmsPort > 0) {
            jmsBroker = new JmsMockBroker(jmsPort);
            jmsBroker.start();
            jmsBroker.loadRules(storage.listRules());
        }
    }

    private void ensureDefaultAdmin() {
        if (!authService.isAuthEnabled()) return;
        User existing = storage.getUserByUsername("admin");
        if (existing == null) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPasswordHash(authService.hashPassword("B@af00!Adm1n#2026"));
            admin.setDisplayName("系统管理员");
            admin.setEmail("admin@baafoo.local");
            admin.setRole("admin");
            storage.createUser(admin);
            log.info("Default admin user created (username: admin) — please change the password after first login");
            return;
        }
        boolean needsFix = false;
        if (!"admin".equals(existing.getRole())) {
            needsFix = true;
            log.info("Admin user has incorrect role '{}', fixing to 'admin'", existing.getRole());
        }
        if (authService.verifyPassword("admin123", existing.getPasswordHash())) {
            needsFix = true;
            log.info("Admin user still has weak password, upgrading");
        }
        if (needsFix) {
            storage.deleteUser("admin");
            User admin = new User();
            admin.setUsername("admin");
            admin.setPasswordHash(authService.hashPassword("B@af00!Adm1n#2026"));
            admin.setDisplayName("系统管理员");
            admin.setEmail("admin@baafoo.local");
            admin.setRole("admin");
            storage.createUser(admin);
            log.info("Default admin user repaired — please change the password after first login");
        }
    }

    private void stop() {
        log.info("Shutting down Baafoo Server...");
        if (recordingCleanupTask != null) {
            recordingCleanupTask.stop();
        }
        if (jmsBroker != null) {
            try {
                jmsBroker.stop();
            } catch (Exception e) {
                log.warn("Error stopping JMS broker: {}", e.getMessage());
            }
        }
        if (kafkaBroker != null) {
            kafkaBroker.stop();
        }
        if (pulsarBroker != null) {
            pulsarBroker.stop();
        }
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
