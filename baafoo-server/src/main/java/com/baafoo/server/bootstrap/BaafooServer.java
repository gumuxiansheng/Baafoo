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
import com.baafoo.server.handler.GrpcUnifiedHandler;
import com.baafoo.server.storage.RecordingCleanupTask;
import com.baafoo.core.event.EventBus;
import com.baafoo.server.storage.ServerPluginServices;
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
    /** P2: Server-side event bus */
    private EventBus eventBus;
    /** P1: Plugin services exposed to plugins */
    private ServerPluginServices pluginServices;
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

        // P1/P2: Initialize EventBus and PluginServices
        this.eventBus = new EventBus();
        this.pluginServices = new ServerPluginServices(storage, new ServerAdminImpl());
        log.info("EventBus and PluginServices initialized");

        // Start recording cleanup task
        recordingCleanupTask = new RecordingCleanupTask(storage, config);
        recordingCleanupTask.start();

        // Initialize default admin user if auth is enabled
        ensureDefaultAdmin();

        // Propagate CORS origins to the static stub response renderer
        com.baafoo.server.handler.StubResponseRenderer.setCorsOrigins(config.getCorsOrigins());

        // Start HTTP management server (API + Web console)
        startManagementServer();

        // Start protocol stub servers
        Integer httpPort = config.getPortForProtocol("http");
        if (httpPort != null && httpPort > 0) startHttpStubServer(httpPort);

        Integer tcpPort = config.getPortForProtocol("tcp");
        if (tcpPort != null && tcpPort > 0) startTcpStubServer(tcpPort);

        Integer grpcPort = config.getPortForProtocol("grpc");
        if (grpcPort != null && grpcPort > 0) startGrpcStubServer(grpcPort);

        Integer kafkaPort = config.getPortForProtocol("kafka");
        Integer pulsarPort = config.getPortForProtocol("pulsar");
        Integer jmsPort = config.getPortForProtocol("jms");

        // Protocol-specific stub servers (Kafka, Pulsar, JMS — Beta)
        startProtocolServers();

        log.info("=== Baafoo Server started ===");
        log.info("Management API: http://localhost:{}", config.getHttpPort());
        log.info("Stub HTTP:      localhost:{}", httpPort);
        log.info("Stub TCP:       localhost:{}", tcpPort);
        log.info("Stub gRPC:      localhost:{} (HTTP/2 unified)", grpcPort);
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
                        p.addLast(new HttpObjectAggregator(10 * 1024 * 1024));
                        p.addLast(new AuthFilter(authService));
                        p.addLast(new ManagementApiHandler(storage, authService, config));
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
                        p.addLast(new HttpObjectAggregator(10 * 1024 * 1024));
                        p.addLast(new HttpStubHandler(storage, config, workerGroup, eventBus));
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
                        ch.pipeline().addLast(new TcpStubHandler(storage, config));
                    }
                });

        Channel ch = b.bind(port).sync().channel();
        channels.add(ch);
    }

    private void startGrpcStubServer(int port) throws Exception {
        // Unified HTTP/2 gRPC server — handles all four call types (unary + streaming)
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new GrpcUnifiedHandler(storage, config, eventBus));
                    }
                });

        Channel ch = b.bind(port).sync().channel();
        channels.add(ch);
        log.info("gRPC stub (HTTP/2 unified) on port {}", port);
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
                        ch.pipeline().addLast(new TcpStubHandler(storage, config));
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
            kafkaBroker = new KafkaMockBroker(kafkaPort, storage, bossGroup, workerGroup, config.getMessagingAdvertisedHost());
            kafkaBroker.start();
        }

        // Pulsar uses the dedicated PulsarMockBroker with binary protocol parsing
        Integer pulsarPort = config.getPortForProtocol("pulsar");
        if (pulsarPort > 0) {
            pulsarBroker = new PulsarMockBroker(pulsarPort, bossGroup, workerGroup, storage, config.getMessagingAdvertisedHost());
            pulsarBroker.start();
        }

        // JMS uses the embedded Artemis broker with OpenWire protocol support
        Integer jmsPort = config.getPortForProtocol("jms");
        if (jmsPort != null && jmsPort > 0) {
            jmsBroker = new JmsMockBroker(jmsPort, 3, storage);
            jmsBroker.start();
            jmsBroker.loadRules(storage.listRules());
        }
    }

    private void ensureDefaultAdmin() {
        // Always create default admin user, regardless of auth state
        User existing = storage.getUserByUsername("admin");
        if (existing == null) {
            String tempPassword = generateTempPassword();
            User admin = new User();
            admin.setUsername("admin");
            admin.setPasswordHash(authService.hashPassword(tempPassword));
            admin.setDisplayName("系统管理员");
            admin.setEmail("admin@baafoo.local");
            admin.setRole("admin");
            storage.createUser(admin);
            writeAdminCredentials(tempPassword);
            log.warn("Default admin user created (username: admin) — initial credentials written to credentials file, change the password after first login");
            return;
        }
        boolean needsFix = false;
        if (!"admin".equals(existing.getRole())) {
            needsFix = true;
            log.info("Admin user has incorrect role '{}', fixing to 'admin'", existing.getRole());
        }
        if (authService.verifyPassword("admin123", existing.getPasswordHash())
                || authService.verifyPassword("B@af00!Adm1n#2026", existing.getPasswordHash())) {
            needsFix = true;
            log.warn("Admin user still has default/weak password, upgrading to a randomly generated one");
        }
        if (needsFix) {
            String tempPassword = generateTempPassword();
            storage.deleteUser("admin");
            User admin = new User();
            admin.setUsername("admin");
            admin.setPasswordHash(authService.hashPassword(tempPassword));
            admin.setDisplayName("系统管理员");
            admin.setEmail("admin@baafoo.local");
            admin.setRole("admin");
            storage.createUser(admin);
            writeAdminCredentials(tempPassword);
            log.warn("Default admin user repaired (username: admin) — initial credentials written to credentials file, change the password after first login");
        }
    }

    private void writeAdminCredentials(String password) {
        try {
            String credsDir = config.getDataDir();
            if (credsDir == null || credsDir.isEmpty()) {
                credsDir = "data";
            }
            java.io.File dir = new java.io.File(credsDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.io.File credsFile = new java.io.File(dir, ".admin-credentials");
            String content = "Default admin credentials (ONE-TIME, change immediately after login):\n" +
                    "  URL:   http://localhost:" + config.getHttpPort() + "/__baafoo__/\n" +
                    "  User:  admin\n" +
                    "  Pass:  " + password + "\n\n" +
                    "This file will be regenerated on next server start if the admin user is deleted\n" +
                    "or reset due to weak password detection. Delete this file after first login.\n";
            java.nio.file.Path credsPath = credsFile.toPath();
            java.nio.file.Files.write(credsPath, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Restrict file permissions on POSIX systems (owner read/write only)
            try {
                java.nio.file.attribute.PosixFilePermission[] perms = {
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                };
                java.nio.file.Files.setAttribute(credsPath, "posix:permissions",
                        java.util.EnumSet.copyOf(java.util.Arrays.asList(perms)));
            } catch (UnsupportedOperationException e) {
                // Non-POSIX filesystem (e.g., FAT32, NTFS) — ignore
            }

            log.info("Admin credentials written to: {}", credsFile.getCanonicalPath());
        } catch (Exception e) {
            log.error("Failed to write admin credentials file: {}", e.getMessage());
        }
    }

    private String generateTempPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
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
        List<ChannelFuture> closeFutures = new ArrayList<ChannelFuture>();
        for (Channel ch : channels) {
            closeFutures.add(ch.close());
        }
        // Wait for all channels to close before shutting down event loops
        for (ChannelFuture f : closeFutures) {
            try { f.sync(); } catch (Exception e) { /* ignore */ }
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

    // ---- P1/P2: Plugin Infrastructure ----

    /**
     * @return server-side event bus, or null if not yet initialized
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * @return plugin services, or null if not yet initialized
     */
    public ServerPluginServices getPluginServices() {
        return pluginServices;
    }

    /**
     * Minimal ServerAdmin implementation for plugins.
     */
    private class ServerAdminImpl implements com.baafoo.plugin.service.ServerAdmin {

        private final java.util.Map<String, com.baafoo.plugin.service.AdminHandler> endpoints =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void registerEndpoint(String path, com.baafoo.plugin.service.AdminHandler handler) {
            endpoints.put(path, handler);
            log.info("Plugin admin endpoint registered: {}", path);
        }

        @Override
        public void reloadRules() {
            // Trigger rule reload from storage
            log.info("Plugin-triggered rule reload");
            // StorageService doesn't have explicit cache invalidation;
            // rules are read from storage on each request (or cached with TTL).
            // This is a no-op for now — future implementations can add caching.
        }

        @Override
        public String getConfig(String key) {
            // ServerConfig doesn't expose arbitrary properties.
            // Return null for unknown keys; future implementations can
            // add a properties map to ServerConfig if needed.
            return null;
        }

        java.util.Map<String, com.baafoo.plugin.service.AdminHandler> getEndpoints() {
            return endpoints;
        }
    }
}
