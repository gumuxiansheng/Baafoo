package com.baafoo.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Event fired by the EventBus.
 * Plugins receive these via {@link AgentPlugin#onEvent(PluginEvent)}.
 *
 * <p>Events are <b>observation-only</b>: they do not participate in the
 * request flow and must not throw. Exceptions from onEvent are caught
 * and logged by the EventBus / PluginManager.</p>
 *
 * <p>Only uses JDK types — no domain model dependency.</p>
 */
public class PluginEvent {

    public enum Type {
        // ---- Request lifecycle ----
        /** A request was received and parsed */
        REQUEST_RECEIVED,
        /** A rule matched the request */
        RULE_MATCHED,
        /** No rule matched the request */
        RULE_NOT_MATCHED,
        /** The response was sent to the client */
        RESPONSE_SENT,

        // ---- Recording ----
        /** A recording entry was saved */
        RECORDING_SAVED,
        /** Recording session started */
        RECORDING_STARTED,
        /** Recording session ended */
        RECORDING_ENDED,

        // ---- Connection ----
        /** A connection was intercepted and redirected */
        CONNECTION_REDIRECTED,
        /** A connection was allowed through (passthrough) */
        CONNECTION_PASSTHROUGH,

        // ---- Rule lifecycle (Phase 3) ----
        /** Rules were reloaded from storage */
        RULES_RELOADED,
        /** A specific rule was created/updated/deleted */
        RULE_CHANGED,

        // ---- Plugin lifecycle ----
        /** A plugin was loaded */
        PLUGIN_LOADED,
        /** A plugin was unloaded */
        PLUGIN_UNLOADED,
        /** A plugin encountered an error */
        PLUGIN_ERROR,

        // ---- System ----
        /** Agent started */
        AGENT_STARTED,
        /** Agent shutting down */
        AGENT_SHUTDOWN
    }

    private final Type type;
    private final long timestamp;
    private final String environmentId;
    private final Map<String, Object> attributes;

    public PluginEvent(Type type, String environmentId, Map<String, Object> attributes) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.environmentId = environmentId;
        this.attributes = attributes != null ? attributes : Collections.emptyMap();
    }

    // --- Factory methods ---

    public static PluginEvent requestReceived(String protocol, String method, String path) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("method", method);
        a.put("path", path);
        return new PluginEvent(Type.REQUEST_RECEIVED, null, a);
    }

    public static PluginEvent ruleMatched(String ruleId, String ruleName, String protocol) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("ruleId", ruleId);
        a.put("ruleName", ruleName);
        a.put("protocol", protocol);
        return new PluginEvent(Type.RULE_MATCHED, null, a);
    }

    public static PluginEvent ruleNotMatched(String protocol, String host, int port) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("host", host);
        a.put("port", port);
        return new PluginEvent(Type.RULE_NOT_MATCHED, null, a);
    }

    public static PluginEvent responseSent(String protocol, int statusCode, long durationMs) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("statusCode", statusCode);
        a.put("durationMs", durationMs);
        return new PluginEvent(Type.RESPONSE_SENT, null, a);
    }

    public static PluginEvent recordingSaved(String recordingId, String protocol, String environmentId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("recordingId", recordingId);
        a.put("protocol", protocol);
        return new PluginEvent(Type.RECORDING_SAVED, environmentId, a);
    }

    public static PluginEvent connectionRedirected(String protocol, String from, String to) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("from", from);
        a.put("to", to);
        return new PluginEvent(Type.CONNECTION_REDIRECTED, null, a);
    }

    public static PluginEvent connectionPassthrough(String protocol, String target) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("protocol", protocol);
        a.put("target", target);
        return new PluginEvent(Type.CONNECTION_PASSTHROUGH, null, a);
    }

    public static PluginEvent rulesReloaded(String environmentId, int ruleCount) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("ruleCount", ruleCount);
        return new PluginEvent(Type.RULES_RELOADED, environmentId, a);
    }

    public static PluginEvent ruleChanged(String ruleId, String action, String environmentId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("ruleId", ruleId);
        a.put("action", action);
        return new PluginEvent(Type.RULE_CHANGED, environmentId, a);
    }

    public static PluginEvent pluginLoaded(String pluginName, String target) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("pluginName", pluginName);
        a.put("target", target);
        return new PluginEvent(Type.PLUGIN_LOADED, null, a);
    }

    public static PluginEvent pluginUnloaded(String pluginName) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("pluginName", pluginName);
        return new PluginEvent(Type.PLUGIN_UNLOADED, null, a);
    }

    public static PluginEvent pluginError(String pluginName, String error) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("pluginName", pluginName);
        a.put("error", error);
        return new PluginEvent(Type.PLUGIN_ERROR, null, a);
    }

    public static PluginEvent agentStarted() {
        return new PluginEvent(Type.AGENT_STARTED, null, null);
    }

    public static PluginEvent agentShutdown() {
        return new PluginEvent(Type.AGENT_SHUTDOWN, null, null);
    }

    // --- Getters ---

    public Type getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public String getEnvironmentId() { return environmentId; }
    public Map<String, Object> getAttributes() { return attributes; }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    @Override
    public String toString() {
        return "PluginEvent{" + type + ", env=" + environmentId + ", attrs=" + attributes + "}";
    }
}
