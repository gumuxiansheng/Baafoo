package com.baafoo.agent.transform;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baafoo.agent.advice.DnsResolveAdvice;
import com.baafoo.agent.advice.DnsResolveAllAdvice;
import com.baafoo.agent.advice.GrpcChannelAdvice;
import com.baafoo.agent.advice.HttpOpenServerAdvice;
import com.baafoo.agent.advice.JmsConnectionFactoryAdvice;
import com.baafoo.agent.advice.KafkaConsumerAdvice;
import com.baafoo.agent.advice.KafkaProducerAdvice;
import com.baafoo.agent.advice.NioSocketConnectAdvice;
import com.baafoo.agent.advice.NioSocketFinishConnectAdvice;
import com.baafoo.agent.advice.PulsarClientAdvice;
import com.baafoo.agent.advice.SocketChannelReadAdvice;
import com.baafoo.agent.advice.SocketChannelWriteAdvice;
import com.baafoo.agent.advice.SocketCloseAdvice;
import com.baafoo.agent.advice.SocketConnectAdvice;
import com.baafoo.agent.advice.SocketInputStreamAdvice;
import com.baafoo.agent.advice.SocketOutputStreamAdvice;
import com.baafoo.core.config.AgentConfig;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import net.bytebuddy.utility.JavaModule;

/**
 * Installs the ByteBuddy transforms (advice) onto target classes via the
 * supplied {@link Instrumentation}. Returns the installed
 * {@link ClassFileTransformer} so the caller can remove it on shutdown.
 *
 * <p>This is a static utility class extracted from {@code BaafooAgent}.</p>
 */
public class TransformInstaller {

    private static final Logger log = LoggerFactory.getLogger(TransformInstaller.class);

    public static ClassFileTransformer installTransforms(AgentConfig cfg, Instrumentation inst) {
        TransformRegistry registry = new TransformRegistry();
        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
                        if ((typeName.startsWith("org.apache.pulsar.client.impl") && typeName.contains("Builder"))
                                || typeName.contains("ActiveMQConnectionFactory")
                                || typeName.equals("java.net.InetAddress")
                                || typeName.equals("java.net.Socket")) {
                            log.info("ByteBuddy discovered: typeName={}, loaded={}, classLoader={}", typeName, loaded, classLoader);
                        }
                    }
                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
                        log.info("ByteBuddy transformed: typeName={}, loaded={}, classLoader={}", typeDescription.getName(), loaded, classLoader);
                    }
                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                        log.warn("ByteBuddy transform error for {}: {}", typeName, throwable.getMessage());
                    }
                })
                .ignore(nameStartsWith("net.bytebuddy.")
                        .or(nameStartsWith("com.baafoo.agent.shaded."))
                        .or(isSynthetic()));

        // DNS resolution interception — always mounted. Records domain-to-IP
        // mappings so that SocketConnectAdvice can look up domain-based routes
        // when socket connects using a resolved IP address instead of the
        // original hostname. Also handles service-name and host-based redirection
        // (registry-agnostic: Nacos/Consul/Eureka) — when a route matches, the
        // resolved InetAddress is overridden to point at the Baafoo Server.
        // Behavior is fully controlled by the runtime route table; no static
        // config needed.
        agentBuilder = agentBuilder
                .type(named("java.net.InetAddress"))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(DnsResolveAdvice.class)
                                .on(named("getByName").and(takesArguments(1))))
                        .visit(Advice.to(DnsResolveAllAdvice.class)
                                .on(named("getAllByName").and(takesArguments(1)))));
        registry.register("java.net.InetAddress", "DnsResolveAdvice/DnsResolveAllAdvice", "dns");

        agentBuilder = agentBuilder
                .type(named("java.net.Socket"))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(SocketConnectAdvice.class)
                                .on(named("connect")
                                        .and(takesArguments(1)
                                                .or(takesArguments(2)))))
                        .visit(Advice.to(SocketInputStreamAdvice.class)
                                .on(named("getInputStream").and(takesArguments(0))))
                        .visit(Advice.to(SocketOutputStreamAdvice.class)
                                .on(named("getOutputStream").and(takesArguments(0))))
                        .visit(Advice.to(SocketCloseAdvice.class)
                                .on(named("close").and(takesArguments(0)))));
        registry.register("java.net.Socket", "SocketConnectAdvice", "tcp");
        registry.register("java.net.Socket", "SocketInputStreamAdvice", "tcp-recording");
        registry.register("java.net.Socket", "SocketOutputStreamAdvice", "tcp-recording");
        registry.register("java.net.Socket", "SocketCloseAdvice", "tcp-recording");

        agentBuilder = agentBuilder
                .type(named("sun.nio.ch.SocketChannelImpl"))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(NioSocketConnectAdvice.class)
                                .on(named("connect").and(takesArguments(1))))
                        .visit(Advice.to(NioSocketFinishConnectAdvice.class)
                                .on(named("finishConnect").and(takesArguments(0))))
                        .visit(Advice.to(SocketChannelReadAdvice.class)
                                .on(named("read").and(takesArguments(1))
                                        .and(takesArgument(0, named("java.nio.ByteBuffer")))))
                        .visit(Advice.to(SocketChannelWriteAdvice.class)
                                .on(named("write").and(takesArguments(1))
                                        .and(takesArgument(0, named("java.nio.ByteBuffer"))))));
        registry.register("sun.nio.ch.SocketChannelImpl", "NioSocketConnectAdvice", "tcp");
        registry.register("sun.nio.ch.SocketChannelImpl", "NioSocketFinishConnectAdvice", "tcp");
        registry.register("sun.nio.ch.SocketChannelImpl", "SocketChannelReadAdvice", "tcp-recording");
        registry.register("sun.nio.ch.SocketChannelImpl", "SocketChannelWriteAdvice", "tcp-recording");

        // HttpClient.openServer interception — always mounted. Redirects HTTP
        // traffic targeting a service-name (or hostname) that matches a Baafoo
        // rule. Only modifies the port (preserving the Host header); the actual
        // IP redirect is handled by DnsResolveAdvice above. Behavior is
        // fully controlled by the runtime route table; no static config needed.
        agentBuilder = agentBuilder
                .type(named("sun.net.www.http.HttpClient"))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(HttpOpenServerAdvice.class)
                                .on(named("openServer").and(takesArguments(2)))));
        registry.register("sun.net.www.http.HttpClient", "HttpOpenServerAdvice", "http");

        agentBuilder = agentBuilder
                .type(named("org.apache.kafka.clients.producer.KafkaProducer"))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(KafkaProducerAdvice.class)
                                .on(isConstructor())));
        registry.register("org.apache.kafka.clients.producer.KafkaProducer", "KafkaProducerAdvice", "kafka");

        agentBuilder = agentBuilder
                .type(named("org.apache.kafka.clients.consumer.KafkaConsumer"))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(KafkaConsumerAdvice.class)
                                .on(isConstructor())));
        registry.register("org.apache.kafka.clients.consumer.KafkaConsumer", "KafkaConsumerAdvice", "kafka");

        agentBuilder = agentBuilder
                .type(nameContains("ClientBuilder").and(nameStartsWith("org.apache.pulsar")))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(PulsarClientAdvice.class)
                                .on(named("serviceUrl").and(takesArguments(1)))));
        registry.register("org.apache.pulsar.client.api.ClientBuilder", "PulsarClientAdvice", "pulsar");

        // JMS: intercept ActiveMQConnectionFactory constructor (OnMethodExit) to replace brokerURL
        agentBuilder = agentBuilder
                .type(named("org.apache.activemq.ActiveMQConnectionFactory")
                        .or(named("org.apache.activemq.ActiveMQXAConnectionFactory")))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(JmsConnectionFactoryAdvice.class)
                                .on(isConstructor())));
        registry.register("org.apache.activemq.ActiveMQConnectionFactory", "JmsConnectionFactoryAdvice", "jms");

        // gRPC: intercept ManagedChannelBuilder.forTarget() to redirect channel targets
        // to the Baafoo stub server. This runs in the App CL (io.grpc.* is App CL visible).
        agentBuilder = agentBuilder
                .type(nameStartsWith("io.grpc.").and(nameEndsWith("ManagedChannelBuilder")))
                .transform((builder, typeDesc, classLoader, module, pd) ->
                        builder.visit(Advice.to(GrpcChannelAdvice.class)
                                .on(named("forTarget").and(takesArguments(1)))));
        registry.register("io.grpc.ManagedChannelBuilder", "GrpcChannelAdvice", "grpc");

        ClassFileTransformer transformer = agentBuilder.installOn(inst);
        log.info("Bytecode transforms installed: {} transforms registered", registry.getCount());
        return transformer;
    }
}
