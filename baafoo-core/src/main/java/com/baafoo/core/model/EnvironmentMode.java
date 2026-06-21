package com.baafoo.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

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
    RECORD_AND_STUB("record-and-stub"),
    /** Agent records ALL traffic, regardless of rule match */
    RECORD_ALL("record-all");

    private final String value;

    EnvironmentMode(String value) { this.value = value; }

    @JsonValue
    public String getValue() { return value; }

    @JsonCreator
    public static EnvironmentMode fromValue(String value) {
        if (value == null) return STUB;
        String normalized = value.replace('-', '_').toLowerCase();
        for (EnvironmentMode mode : values()) {
            if (mode.value.replace('-', '_').equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        for (EnvironmentMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        return STUB;
    }
}
