package com.baafoo.server.handler;

import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.util.GrpcCodecUtils;
import com.baafoo.core.util.TemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds gRPC response messages from {@link ResponseEntry} configurations.
 *
 * <p>Handles:
 * <ul>
 *   <li>Template rendering (with faker seed and request context)</li>
 *   <li>Hex/UTF-8 body conversion</li>
 *   <li>Multi-message splitting for streaming responses</li>
 *   <li>gRPC status code and message extraction from headers</li>
 * </ul>
 * </p>
 *
 * <p>Extracted from the original gRPC handlers to eliminate
 * code duplication (D5 fix).</p>
 */
public final class GrpcResponseBuilder {

    private static final Logger log = LoggerFactory.getLogger(GrpcResponseBuilder.class);

    private GrpcResponseBuilder() {}

    /**
     * Build response message bytes from a ResponseEntry.
     *
     * <p>For streaming responses, the body is split by comma or newline into
     * multiple messages. For unary, a single message is returned.</p>
     *
     * @param entry       the response entry configuration
     * @param templateCtx the template rendering context (null = no template rendering)
     * @param fakerSeed   optional faker seed for deterministic random data
     * @return list of message byte arrays (one per streaming message, or one for unary)
     */
    public static List<byte[]> buildResponseMessages(ResponseEntry entry,
                                                      TemplateEngine.RequestContext templateCtx,
                                                      Long fakerSeed) {
        String rawBody = entry.getBody() != null ? entry.getBody() : "";

        // Split into individual messages (streaming support)
        String[] messageStrs = GrpcCodecUtils.splitStreamingMessages(rawBody);

        List<byte[]> messages = new ArrayList<>(messageStrs.length);
        for (String msgStr : messageStrs) {
            String trimmed = msgStr.trim();
            if (trimmed.isEmpty()) continue;

            // Render template if present
            if (templateCtx != null && trimmed.contains("{{")) {
                trimmed = TemplateEngine.render(trimmed, templateCtx, fakerSeed);
            }

            messages.add(GrpcCodecUtils.responseBodyToBytes(trimmed));
        }

        if (messages.isEmpty()) {
            messages.add(new byte[0]);
        }
        return messages;
    }

    /**
     * Get the gRPC status code from a ResponseEntry's headers.
     *
     * <p>Looks for headers in order: grpc-status, Grpc-Status, x-grpc-status.
     * Defaults to 0 (OK) if not found.</p>
     *
     * @param entry the response entry
     * @return gRPC status code
     */
    public static int getGrpcStatus(ResponseEntry entry) {
        // D1 fix: prefer dedicated field over headers map
        int status = entry.getGrpcStatus();
        if (status != 0) return status;
        // Fallback: check headers map for backward compatibility
        if (entry.getHeaders() != null) {
            String statusStr = findHeader(entry.getHeaders(), "grpc-status");
            if (statusStr != null) {
                try {
                    return Integer.parseInt(statusStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid grpc-status value: {}", statusStr);
                }
            }
        }
        return 0; // OK
    }

    /**
     * Get the gRPC status message from a ResponseEntry's headers.
     *
     * <p>Looks for headers in order: grpc-message, Grpc-Message, x-grpc-message.
     * Defaults to empty string if not found.</p>
     *
     * @param entry the response entry
     * @return gRPC status message
     */
    public static String getGrpcMessage(ResponseEntry entry) {
        // D1 fix: prefer dedicated field over headers map
        String message = entry.getGrpcStatusMessage();
        if (message != null && !message.isEmpty()) return message;
        // Fallback: check headers map for backward compatibility
        if (entry.getHeaders() != null) {
            String msgStr = findHeader(entry.getHeaders(), "grpc-message");
            return msgStr != null ? msgStr : "";
        }
        return "";
    }

    /**
     * Find a header value case-insensitively.
     */
    private static String findHeader(Map<String, String> headers, String key) {
        // Try exact match first
        String value = headers.get(key);
        if (value != null) return value;
        // Try case-insensitive
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
