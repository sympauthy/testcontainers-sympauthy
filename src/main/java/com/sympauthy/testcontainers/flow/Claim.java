package com.sympauthy.testcontainers.flow;

import java.util.Map;

/**
 * A claim the flow asks the user to provide during the collect-claims step. Mirrors an entry of the
 * {@code claims} array returned by {@code GET /api/v1/flow/claims}.
 */
public record Claim(
        String id,
        boolean required,
        String name,
        String type,
        String group,
        boolean collected,
        Object value,
        Object suggestedValue) {

    static Claim fromMap(Map<String, Object> map) {
        return new Claim(
                asString(map.get("id")),
                Boolean.TRUE.equals(map.get("required")),
                asString(map.get("name")),
                asString(map.get("type")),
                asString(map.get("group")),
                Boolean.TRUE.equals(map.get("collected")),
                map.get("value"),
                map.get("suggested_value"));
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
