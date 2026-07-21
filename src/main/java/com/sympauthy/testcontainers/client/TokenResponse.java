package com.sympauthy.testcontainers.client;

import java.util.Map;

/**
 * Parsed token endpoint response. {@link #raw()} exposes every field returned, including any not
 * surfaced as a typed accessor.
 */
public record TokenResponse(
        String accessToken,
        String idToken,
        String tokenType,
        Long expiresIn,
        String refreshToken,
        String scope,
        Map<String, Object> raw) {

    static TokenResponse fromMap(Map<String, Object> map) {
        return new TokenResponse(
                str(map.get("access_token")),
                str(map.get("id_token")),
                str(map.get("token_type")),
                asLong(map.get("expires_in")),
                str(map.get("refresh_token")),
                str(map.get("scope")),
                map);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
