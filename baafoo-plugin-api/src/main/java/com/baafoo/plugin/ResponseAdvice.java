package com.baafoo.plugin;

import java.util.Map;

/**
 * Advice returned from the response-phase hook.
 *
 * <p>Three actions:</p>
 * <ul>
 *   <li>CONTINUE — send the response as-is</li>
 *   <li>REPLACE — replace the response entirely</li>
 *   <li>AUGMENT — add/modify headers, keep body</li>
 * </ul>
 */
public class ResponseAdvice {

    public enum Action {
        /** Send the response as-is */
        CONTINUE,
        /** Replace the response entirely */
        REPLACE,
        /** Augment: add/modify headers, keep body */
        AUGMENT
    }

    private final Action action;
    private final byte[] replaceBody;
    private final int replaceStatusCode;
    private final Map<String, String> replaceHeaders;
    private final Map<String, String> additionalHeaders;

    private ResponseAdvice(Action action, byte[] replaceBody, int replaceStatusCode,
                           Map<String, String> replaceHeaders,
                           Map<String, String> additionalHeaders) {
        this.action = action;
        this.replaceBody = replaceBody;
        this.replaceStatusCode = replaceStatusCode;
        this.replaceHeaders = replaceHeaders;
        this.additionalHeaders = additionalHeaders;
    }

    public static ResponseAdvice proceed() {
        return new ResponseAdvice(Action.CONTINUE, null, 0, null, null);
    }

    public static ResponseAdvice replace(byte[] body, int statusCode, Map<String, String> headers) {
        return new ResponseAdvice(Action.REPLACE, body, statusCode, headers, null);
    }

    public static ResponseAdvice augment(Map<String, String> additionalHeaders) {
        return new ResponseAdvice(Action.AUGMENT, null, 0, null, additionalHeaders);
    }

    // --- Getters ---

    public Action getAction() { return action; }
    public byte[] getReplaceBody() { return replaceBody; }
    public int getReplaceStatusCode() { return replaceStatusCode; }
    public Map<String, String> getReplaceHeaders() { return replaceHeaders; }
    public Map<String, String> getAdditionalHeaders() { return additionalHeaders; }
}
