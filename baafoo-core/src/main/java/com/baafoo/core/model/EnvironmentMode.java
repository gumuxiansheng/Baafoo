package com.baafoo.core.model;

/**
 * Environment modes that control agent behavior.
 */
public enum EnvironmentMode {

    /** Agent returns pre-configured stub responses */
    STUB("stub"),

    /** Agent passes all requests through to real downstream */
    PASSTHROUGH("passthrough"),

    /** Agent records real responses for later replay */
    RECORD("record"),

    /** Agent records real responses AND returns stubs */
    RECORD_AND_STUB("record-and-stub");

    private final String value;

    EnvironmentMode(String value) { this.value = value; }

    public String getValue() { return value; }

    public static EnvironmentMode fromValue(String value) {
        for (EnvironmentMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown environment mode: " + value);
    }
}
