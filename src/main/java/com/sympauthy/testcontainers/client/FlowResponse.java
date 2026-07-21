package com.sympauthy.testcontainers.client;

import java.util.Map;

/**
 * A parsed Flow API response: the JSON body as a map plus the HTTP status code. Every Flow API step
 * either points onwards with a {@link #redirectUrl()} or returns data for the current step.
 */
public final class FlowResponse {

    private final int statusCode;
    private final Map<String, Object> body;

    FlowResponse(int statusCode, Map<String, Object> body) {
        this.statusCode = statusCode;
        this.body = body;
    }

    public int statusCode() {
        return statusCode;
    }

    public Map<String, Object> body() {
        return body;
    }

    public boolean has(String key) {
        return body.containsKey(key);
    }

    public Object get(String key) {
        return body.get(key);
    }

    /** The {@code redirect_url} the server points to next, or {@code null} if the step needs input. */
    public String redirectUrl() {
        Object value = body.get("redirect_url");
        return value == null ? null : value.toString();
    }
}
