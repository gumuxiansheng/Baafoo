package com.baafoo.core.model;

/**
 * Protocol types supported by Baafoo.
 */
public enum Protocol {

    HTTP("http", 9000),
    TCP("tcp", 9001),
    KAFKA("kafka", 9002),
    PULSAR("pulsar", 9003),
    JMS("jms", 9004);

    private final String name;
    private final int defaultPort;

    Protocol(String name, int defaultPort) {
        this.name = name;
        this.defaultPort = defaultPort;
    }

    public String getName() { return name; }
    public int getDefaultPort() { return defaultPort; }

    public static Protocol fromName(String name) {
        for (Protocol p : values()) {
            if (p.name.equalsIgnoreCase(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown protocol: " + name);
    }
}
