package com.sympauthy.testcontainers.flow;

import java.util.Map;

/**
 * The client an interactive flow session was started for, as every Flow API step names it in its
 * {@code client} object. {@link #displayName()} is the name the configuration gives the client
 * ({@code clients.<id>.display-name}), or {@code null} when it gives none or no longer declares the
 * client; a page then shows {@link #id()} instead.
 */
public record ClientReference(String id, String displayName) {

    /** Reads the {@code client} object of a step's body, or {@code null} when the step names no client. */
    static ClientReference fromBody(Map<String, Object> body) {
        if (!(body.get("client") instanceof Map<?, ?> client)) {
            return null;
        }
        return new ClientReference(str(client.get("id")), str(client.get("display_name")));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
