package com.baafoo.server.broker;

/**
 * A decoded Pulsar frame containing the parsed command and optional payload.
 */
class PulsarFrame {

    /** The parsed Pulsar command. */
    PulsarCommand command;

    /** The raw payload bytes (message body for SEND commands, empty otherwise). */
    byte[] payload;

    PulsarFrame() {
        this.payload = new byte[0];
    }
}
