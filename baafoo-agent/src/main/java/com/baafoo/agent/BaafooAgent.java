package com.baafoo.agent;

import com.baafoo.agent.advice.*;
import com.baafoo.agent.channel.ControlChannel;
import com.baafoo.agent.loader.PluginClassLoader;
import com.baafoo.agent.plugin.PluginManager;
import com.baafoo.agent.transform.TransformRegistry;
import com.baafoo.core.config.AgentConfig;
import com.baafoo.core.config.ConfigLoader;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Baafoo JavaAgent entry point.
 *
 * <p>Loaded via -javaagent:baafoo-agent.jar.
 * Injects bytecode into key JDK and library classes to intercept
 * network calls and redirect them to the Baafoo stub server.</p>
 *
 * <p>Fail-closed design: If agent fails to load, errors are logged
 * and all requests pass through to real downstreams untouched.</p>
 */
public class BaafooAgent {

    private static final Logger log = LoggerFactory.getLogger(BaafooAgent.class);

    /** Global agent config */
    private static volatile AgentConfig config;

    /** Control channel to Baafoo server */
    private static volatile ControlChannel controlChannel;

    /** Plugin manager */
    private static volatile PluginManager pluginManager;

    /**
     * JVM premain entry point.
     *
     * @param agentArgs agent arguments
     * @param inst      instrumentation instance
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            log.info("=== Baafoo Agent {} starting ===", getVersion());
            log.info("Agent args: {}", agentArgs);

            // 1. Load config
            String configPath = resolveConfigPath(agentArgs);
            config = ConfigLoader.loadAgentConfig(configPath);
            log.info("Config loaded: {}", config);

            // 2. Init control channel
            controlChannel = new ControlChannel(config);
            controlChannel.start();

            // 3. Init plugin manager
            pluginManager = new PluginManager();

            // 4. Register bytecode transforms
            TransformRegistry registry = new TransformRegistry();
            AgentBuilder agentBuilder = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onError(String typeName, ClassLoader classLoader,
                                            java.lang.instrument.IllegalClassFormatException e,
                                            java.lang.reflect.Module module, boolean loaded,
                                            Throwable throwable) {
                            log.error("Bytecode transform failed for {}: {}", typeName, throwable.getMessage());
                            // Do not re-throw — fail-closed
                        }
                    });

            // --- Socket interception ---
            agentBuilder = agentBuilder
                    .type(named("java.net.Socket"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(SocketConnectAdvice.class)
                                    .on(named("connect")
                                            .and(takesArguments(1))
                                            .and(takesArgument(0, named("java.net.SocketAddress"))))));

            // --- NIO SocketChannel interception ---
            agentBuilder = agentBuilder
                    .type(named("sun.nio.ch.SocketChannelImpl"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(NioSocketConnectAdvice.class)
                                    .on(named("connect").and(takesArguments(1)))));

            // --- Consul DNS interception ---
            if (config.isConsulEnabled()) {
                agentBuilder = agentBuilder
                        .type(named("java.net.InetAddress"))
                        .transform((builder, typeDesc, classLoader, module, pd) ->
                                builder.visit(Advice.to(ConsulDnsAdvice.class)
                                        .on(named("getByName").and(takesArguments(1)))));
            }

            // --- Kafka interception (Beta) ---
            agentBuilder = agentBuilder
                    .type(named("org.apache.kafka.clients.producer.KafkaProducer"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(KafkaProducerAdvice.class)
                                    .on(named("send").and(takesArguments(2)))));

            // --- Pulsar interception (Beta) ---
            agentBuilder = agentBuilder
                    .type(named("org.apache.pulsar.client.impl.PulsarClientImpl"))
                    .transform((builder, typeDesc, classLoader, module, pd) ->
                            builder.visit(Advice.to(PulsarClientAdvice.class)
                                    .on(named("createProducer").or(named("subscribe")))));

            // 5. Install agent
            agentBuilder.installOn(inst);
            log.info("Bytecode transforms installed successfully");

            // 6. Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    shutdown();
                }
            }, "baafoo-shutdown"));

            log.info("=== Baafoo Agent started successfully ===");

        } catch (Exception e) {
            log.error("FAILED to start Baafoo Agent (fail-closed): {}", e.getMessage(), e);
            // Fail-closed: do not prevent JVM from starting
        }
    }

    private static String resolveConfigPath(String agentArgs) {
        if (agentArgs != null && !agentArgs.isEmpty()) {
            return agentArgs;
        }
        return System.getProperty("baafoo.agent.config", "./baafoo-agent.yml");
    }

    private static void shutdown() {
        log.info("Shutting down Baafoo Agent...");
        try {
            if (controlChannel != null) {
                controlChannel.stop();
            }
            if (pluginManager != null) {
                pluginManager.shutdown();
            }
        } catch (Exception e) {
            log.error("Error during shutdown: {}", e.getMessage());
        }
        log.info("Baafoo Agent stopped");
    }

    // --- Static accessors for Advice classes ---

    public static AgentConfig getConfig() {
        return config;
    }

    public static ControlChannel getControlChannel() {
        return controlChannel;
    }

    public static PluginManager getPluginManager() {
        return pluginManager;
    }

    private static String getVersion() {
        String version = BaafooAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "1.0.0-SNAPSHOT";
    }
}
