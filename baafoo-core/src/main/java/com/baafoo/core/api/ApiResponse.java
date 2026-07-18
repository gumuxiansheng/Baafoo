package com.baafoo.core.api;

/**
 * Standard API response wrapper.
 * All Baafoo Server API endpoints use this format.
 */
public class ApiResponse<T> {

    /** Success indicator */
    private boolean success;

    /** HTTP status code */
    private int code;

    /** Human-readable message */
    private String message;

    /** Response data payload */
    private T data;

    /** Request timestamp */
    private long timestamp;

    private ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<T>();
        r.success = true;
        r.code = 200;
        r.message = "OK";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        ApiResponse<T> r = new ApiResponse<T>();
        r.success = true;
        r.code = 200;
        r.message = message;
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> created(T data) {
        ApiResponse<T> r = new ApiResponse<T>();
        r.success = true;
        r.code = 201;
        r.message = "Created";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        ApiResponse<T> r = new ApiResponse<T>();
        r.success = false;
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return fail(400, message);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return fail(404, message);
    }

    public static <T> ApiResponse<T> internalError(String message) {
        return fail(500, message);
    }

    // --- Getters ---

    public boolean isSuccess() { return success; }
    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        // L4: include the data field's type (and identity hash for correlation)
        // so the toString is actually useful in logs — previously toString
        // omitted data entirely, making it impossible to tell two 200-OK
        // responses apart. The value itself is NOT included to avoid dumping
        // large payloads (e.g. paginated rule lists) into log lines.
        return "ApiResponse{" +
                "success=" + success +
                ", code=" + code +
                ", message='" + message + '\'' +
                ", data=" + (data != null
                        ? data.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(data))
                        : "null") +
                '}';
    }
}
