package com.sympauthy.testcontainers.internal.json;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON bridge: parses SympAuthy API responses into {@link Map}/{@link List} trees and
 * serializes request bodies. This is the only class that touches the JSON parser (the shaded, relocated
 * {@code minimal-json}), so swapping the library stays a one-file change.
 *
 * <p>Lives under {@code com.sympauthy.testcontainers.internal.*} — the namespace for internal,
 * non-published utilities — next to the Shadow-relocated {@code minimal-json} classes.
 */
public final class JsonCodec {

    private JsonCodec() {
    }

    /** Parses a JSON document into a nested {@link Map}/{@link List}/scalar tree. */
    public static Object parse(String text) {
        return toJava(Json.parse(text));
    }

    /** Parses a JSON object document into a map, failing if the top level is not an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object parsed = parse(text);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object but got: " + text);
        }
        return (Map<String, Object>) parsed;
    }

    /** Serializes a nested {@link Map}/{@link List}/scalar tree to a compact JSON document. */
    public static String write(Object value) {
        return toJson(value).toString();
    }

    private static Object toJava(JsonValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (JsonObject.Member member : value.asObject()) {
                map.put(member.getName(), toJava(member.getValue()));
            }
            return map;
        }
        if (value.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonValue element : value.asArray()) {
                list.add(toJava(element));
            }
            return list;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            return value.asString();
        }
        // Numbers are stored as text by minimal-json; prefer a long for integral values so callers
        // get clean ids and durations rather than doubles.
        double number = value.asDouble();
        if (number == Math.rint(number) && !Double.isInfinite(number)) {
            return (long) number;
        }
        return number;
    }

    private static JsonValue toJson(Object value) {
        if (value == null) {
            return Json.NULL;
        }
        if (value instanceof String s) {
            return Json.value(s);
        }
        if (value instanceof Boolean b) {
            return Json.value(b);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return Json.value(((Number) value).longValue());
        }
        if (value instanceof Number n) {
            return Json.value(n.doubleValue());
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = Json.object();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.add(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            JsonArray array = Json.array();
            for (Object element : list) {
                array.add(toJson(element));
            }
            return array;
        }
        return Json.value(value.toString());
    }
}
