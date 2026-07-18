package com.baafoo.plugin;

import java.util.Map;

/**
 * Advice returned from the request-phase hook.
 *
 * <p>Three actions:</p>
 * <ul>
 *   <li>CONTINUE — proceed to normal rule matching</li>
 *   <li>SHORTCIRCUIT — skip rule matching, return this response directly</li>
 *   <li>MODIFY — continue, but with modified request</li>
 * </ul>
 */
public class RequestAdvice {

    public enum Action {
        /** Continue to normal rule matching */
        CONTINUE,
        /** Skip rule matching, return this response directly */
        SHORTCIRCUIT,
        /** Continue, but with modified request */
        MODIFY
    }

    private final Action action;
    private final byte[] shortcutBody;
    private final int shortcutStatusCode;
    private final Map<String, String> shortcutHeaders;
    private final Map<String, String> modifiedHeaders;
    private final byte[] modifiedBody;

    private RequestAdvice(Action action, byte[] shortcutBody, int shortcutStatusCode,
                          Map<String, String> shortcutHeaders,
                          Map<String, String> modifiedHeaders, byte[] modifiedBody) {
        this.action = action;
        this.shortcutBody = shortcutBody;
        this.shortcutStatusCode = shortcutStatusCode;
        this.shortcutHeaders = shortcutHeaders;
        this.modifiedHeaders = modifiedHeaders;
        this.modifiedBody = modifiedBody;
    }

    public static RequestAdvice proceed() {
        return new RequestAdvice(Action.CONTINUE, null, 0, null, null, null);
    }

    public static RequestAdvice shortCircuit(byte[] body, int statusCode, Map<String, String> headers) {
        // L-13: Defensive copy of the body so a later mutation by the caller doesn't change the
        // advice's payload after the factory returns. Headers are intentionally not copied here —
        // they're treated as transferred ownership and wrapped unmodifiable by the consumer.
        byte[] bodyCopy = body != null ? body.clone() : null;
        return new RequestAdvice(Action.SHORTCIRCUIT, bodyCopy, statusCode, headers, null, null);
    }

    public static RequestAdvice modify(Map<String, String> newHeaders, byte[] newBody) {
        return new RequestAdvice(Action.MODIFY, null, 0, null, newHeaders, newBody);
    }

    // --- Getters ---

    public Action getAction() { return action; }
    public byte[] getShortcutBody() { return shortcutBody; }
    public int getShortcutStatusCode() { return shortcutStatusCode; }
    public Map<String, String> getShortcutHeaders() { return shortcutHeaders; }
    public Map<String, String> getModifiedHeaders() { return modifiedHeaders; }
    public byte[] getModifiedBody() { return modifiedBody; }
}
