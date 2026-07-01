package com.baafoo.plugin;

/**
 * Intercept target types supported by the Baafoo agent.
 */
public enum InterceptTarget {

    /** Socket connect - java.net.Socket#connect() */
    SOCKET,

    /** NIO SocketChannel connect - sun.nio.ch.SocketChannelImpl#connect() */
    NIO_SOCKET,

    /** Kafka producer/consumer */
    KAFKA,

    /** Pulsar client */
    PULSAR,

    /** JMS producer/consumer */
    JMS,

    /** gRPC channel - io.grpc.ClientChannel/ManagedChannelBuilder */
    GRPC,

    /** Consul DNS resolution - java.net.InetAddress#getByName() */
    CONSUL_DNS,

    /** Consul HTTP API (SDK mode) */
    CONSUL_API,

    /** Feign declarative HTTP client */
    FEIGN
}
